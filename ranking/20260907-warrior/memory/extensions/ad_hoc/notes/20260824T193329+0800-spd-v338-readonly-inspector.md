#!/usr/bin/env python3
"""
Persistent Shattered Pixel Dungeon v3.3.8 read-only inspector.

This memory artifact is deliberately both a Markdown-named memory update and a
valid Python program. Run it with Python; do not copy it to /tmp:

  python3 THIS_FILE slots
  python3 THIS_FILE --slot 1 status
  python3 THIS_FILE --slot 1 --json status
  python3 THIS_FILE --slot 1 map
  python3 THIS_FILE --slot 1 entities
  python3 THIS_FILE --slot 1 --wait-new --timeout 120 route exit
  python3 THIS_FILE --slot 1 --wait-new --timeout 120 route item:ScrollOfUpgrade
  python3 THIS_FILE --slot 1 --wait-new --timeout 120 status
  python3 THIS_FILE selftest

Profile: game version code 896, display version 3.3.8, source commit
7b8b845a76fe76c6b7c031ae9e570852411f56db.

Normal commands only open game files O_RDONLY and write to stdout/stderr. They
never create, edit, delete, repair, back up, or copy files in the game-data
directory. `selftest` alone creates an isolated TemporaryDirectory and removes
it automatically.

Evidence boundary:

* The game has no transaction ID joining game.dat to depthN.dat. This tool can
  prove that a pair was stable across two complete reads, and `--wait-new` can
  prove that both canonical files were replaced/updated after a baseline. It
  cannot mathematically prove that two files came from one saveAll call.
* A persisted checkpoint can lag the focused live UI. Start `--wait-new` before
  minimizing the actual game window, or minimize first and inspect file mtimes
  and snapshot age. Focus switching and Esc are not save proof.
* Saved data has visited/mapped arrays but no heroFOV. Mob.seen means the mob's
  enemySeen state, not that the hero currently sees that mob. Generic Char
  alignment is not saved. These facts remain unknown unless separately derived
  from exact source/runtime state.
* Routes are static advisories bound to one snapshot. Default output exposes
  only one next keypad action and always requires replanning. It never operates
  the game, never enters a transition rectangle, and never calls a route safe.
* Saves are state snapshots, not event logs. Hero actions, attempted targets,
  hit/miss outcomes, and the last damage source are not serialized. Claims
  about an action require a controlled single input plus matching before/after
  checkpoints; hit/miss claims additionally require visible UI/combat-log
  evidence when the same final state could have multiple causes.

Legacy-script audit incorporated here:

* spd_ascii.py hard-coded depth1 and the hero coordinate.
* spd_save_read.py checked files separately but not .spdtmp or pair coherence.
* spd_route.py assumed slot 1/main branch/gzip, used four-way movement, treated
  key possession too broadly, ignored blobs/plants, and emitted executable long
  paths.
* spd_live.py had the strongest inode/hash/schema foundation but omitted the
  explicit .spdtmp gate, mislabeled some inferred fields, and could route onto
  transitions or advertise a heuristic as safe.
* spd_checkpoint.py restored .spdtmp checks and branch filenames but regressed
  inode/double-hash/JSON detail. Its route unblocked any target cell, ignored
  plants and transition rectangles, made --allow-traps internally inconsistent,
  and printed multi-step directions that encouraged stale-route execution.

This file supersedes the recovered one-shot temporary helpers for future
assisted runs. The recovered `spd_live.py` was also one-shot status/map/route
code; continuous watch/diff and every form of game control remain out of scope.
"""

from __future__ import annotations

import argparse
import collections
import gzip
import hashlib
import io
import json
import os
import sys
import tempfile
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, Sequence


TOOL_VERSION = "1.0.0"
SCHEMA_VERSION = "spd.inspect.snapshot/v1"
ROUTE_SCHEMA_VERSION = "spd.inspect.route/v1"
PROFILE_CODE = 896
PROFILE_NAME = "3.3.8"
SOURCE_COMMIT = "7b8b845a76fe76c6b7c031ae9e570852411f56db"
DEFAULT_ROOT = Path.home() / "Library/Application Support/Shattered Pixel Dungeon"
MAX_STORED_BYTES = 64 * 1024 * 1024
MAX_DECODED_BYTES = 256 * 1024 * 1024
MAX_PAIR_DELTA_NS = 30_000_000_000


class InspectError(RuntimeError):
    code = "inspect_error"
    retryable = False


class NoActiveRun(InspectError):
    code = "no_active_run"


class SaveBusy(InspectError):
    code = "save_busy"
    retryable = True


class DecodeError(InspectError):
    code = "decode_error"


class SchemaError(InspectError):
    code = "schema_invalid"


class RouteError(InspectError):
    code = "route_unavailable"


@dataclass(frozen=True)
class TerrainInfo:
    name: str
    glyph: str
    passable: bool = False
    avoid: bool = False
    pit: bool = False
    solid: bool = False
    los_blocking: bool = False


TERRAIN: dict[int, TerrainInfo] = {
    0: TerrainInfo("CHASM", "V", avoid=True, pit=True),
    1: TerrainInfo("EMPTY", ".", passable=True),
    2: TerrainInfo("GRASS", ",", passable=True),
    3: TerrainInfo("EMPTY_WELL", "o", passable=True),
    4: TerrainInfo("WALL", "#", solid=True, los_blocking=True),
    5: TerrainInfo("DOOR", "+", passable=True, solid=True, los_blocking=True),
    6: TerrainInfo("OPEN_DOOR", "/", passable=True),
    7: TerrainInfo("ENTRANCE", "<", passable=True),
    8: TerrainInfo("EXIT", ">", passable=True),
    9: TerrainInfo("EMBERS", ":", passable=True),
    10: TerrainInfo("LOCKED_DOOR", "L", solid=True, los_blocking=True),
    11: TerrainInfo("PEDESTAL", "p", passable=True),
    12: TerrainInfo("WALL_DECO", "#", solid=True, los_blocking=True),
    13: TerrainInfo("BARRICADE", "B", solid=True, los_blocking=True),
    14: TerrainInfo("EMPTY_SP", ".", passable=True),
    15: TerrainInfo("HIGH_GRASS", '"', passable=True, los_blocking=True),
    16: TerrainInfo("SECRET_DOOR", "s", solid=True, los_blocking=True),
    17: TerrainInfo("SECRET_TRAP", "?", passable=True),
    18: TerrainInfo("TRAP", "^", avoid=True),
    19: TerrainInfo("INACTIVE_TRAP", "_", passable=True),
    20: TerrainInfo("EMPTY_DECO", ".", passable=True),
    21: TerrainInfo("LOCKED_EXIT", "X", solid=True),
    22: TerrainInfo("UNLOCKED_EXIT", ">", passable=True),
    23: TerrainInfo("CUSTOM_DECO", "#", solid=True),
    24: TerrainInfo("WELL", "O", avoid=True),
    25: TerrainInfo("STATUE", "&", solid=True),
    26: TerrainInfo("STATUE_SP", "&", solid=True),
    27: TerrainInfo("BOOKSHELF", "b", solid=True, los_blocking=True),
    28: TerrainInfo("ALCHEMY", "A", solid=True),
    29: TerrainInfo("WATER", "~", passable=True),
    30: TerrainInfo("FURROWED_GRASS", ";", passable=True, los_blocking=True),
    31: TerrainInfo("CRYSTAL_DOOR", "C", solid=True),
    32: TerrainInfo("CUSTOM_DECO_EMPTY", ".", passable=True),
    33: TerrainInfo("REGION_DECO", "#", solid=True),
    34: TerrainInfo("REGION_DECO_ALT", "#", solid=True),
    35: TerrainInfo("MINE_CRYSTAL", "c", solid=True),
    36: TerrainInfo("MINE_BOULDER", "r", solid=True),
    37: TerrainInfo("ENTRANCE_SP", "<", passable=True),
    38: TerrainInfo("HERO_LKD_DR", "L", solid=True, los_blocking=True),
}

TRANSITION_TERRAIN = {7, 8, 21, 22, 37}
DOOR_TERRAIN = {5, 6, 10, 16, 31, 38}
ACTIVE_TRAP_TERRAIN = {17, 18}
ROUTE_CAUTION_TERRAIN = {5, 15, 17, 18, 29, 30}

# Exact v3.3.8 Blob semantics verified from source. Unknown active Blob classes
# remain conservatively route-blocking, but are labelled unknown rather than
# asserted to be harmful.
BLOB_PROFILE: dict[str, dict[str, Any]] = {
    "WeakFloorRoom$WellID": {
        "role": "landmark_marker",
        "hazard": False,
        "route_blocked": False,
        "map_glyph": "w",
        "basis": "v3.3.8 WeakFloorRoom.WellID tracks DISTANT_WELL visibility only",
    },
}

DIRS: tuple[tuple[int, int, str, str], ...] = (
    (-1, -1, "NW", "KP7"),
    (0, -1, "N", "KP8"),
    (1, -1, "NE", "KP9"),
    (-1, 0, "W", "KP4"),
    (1, 0, "E", "KP6"),
    (-1, 1, "SW", "KP1"),
    (0, 1, "S", "KP2"),
    (1, 1, "SE", "KP3"),
)
DIR_BY_DELTA = {(dx, dy): (name, key) for dx, dy, name, key in DIRS}


@dataclass(frozen=True)
class FileStamp:
    path: Path
    dev: int
    ino: int
    size: int
    mtime_ns: int
    ctime_ns: int
    raw_sha256: str
    decoded_sha256: str
    encoding: str
    decoded_size: int

    @property
    def signature(self) -> tuple[Any, ...]:
        return (
            str(self.path), self.dev, self.ino, self.size,
            self.mtime_ns, self.ctime_ns, self.raw_sha256,
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "path": str(self.path),
            "stored_bytes": self.size,
            "decoded_bytes": self.decoded_size,
            "mtime_ns": self.mtime_ns,
            "mtime_local": datetime.fromtimestamp(self.mtime_ns / 1e9).astimezone().isoformat(),
            "inode": self.ino,
            "raw_sha256": self.raw_sha256,
            "decoded_sha256": self.decoded_sha256,
            "encoding": self.encoding,
            "stable_during_read": True,
        }


@dataclass(frozen=True)
class BundleRead:
    data: dict[str, Any]
    raw: bytes
    decoded: bytes
    stamp: FileStamp


@dataclass
class Snapshot:
    root: Path
    slot: int
    game_read: BundleRead
    level_read: BundleRead
    level: dict[str, Any]
    depth: int
    branch: int
    coherence: str
    warnings: list[str]

    @property
    def game(self) -> dict[str, Any]:
        return self.game_read.data

    @property
    def width(self) -> int:
        return int(self.level["width"])

    @property
    def height(self) -> int:
        return int(self.level["height"])

    @property
    def length(self) -> int:
        return self.width * self.height

    @property
    def hero(self) -> dict[str, Any]:
        return self.game["hero"]

    @property
    def hero_cell(self) -> int:
        return int(self.hero["pos"])

    @property
    def snapshot_id(self) -> str:
        h = hashlib.sha256()
        h.update(f"{PROFILE_CODE}:{SOURCE_COMMIT}\0".encode())
        for read in (self.game_read, self.level_read):
            h.update(str(read.stamp.path.relative_to(self.root)).encode())
            h.update(b"\0")
            h.update(read.raw)
            h.update(b"\0")
        return h.hexdigest()

    @property
    def age_seconds(self) -> float:
        oldest = min(self.game_read.stamp.mtime_ns, self.level_read.stamp.mtime_ns)
        return max(0.0, (time.time_ns() - oldest) / 1e9)

    def valid_cell(self, cell: Any) -> bool:
        return isinstance(cell, int) and 0 <= cell < self.length

    def xy(self, cell: int) -> tuple[int, int]:
        return cell % self.width, cell // self.width

    def cell(self, x: int, y: int) -> int:
        return y * self.width + x


def short_class(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    return value.rsplit(".", 1)[-1]


def class_identity(obj: Any) -> dict[str, Any]:
    full = obj.get("__className") if isinstance(obj, dict) else None
    return {"class_full": full, "class_short": short_class(full)}


def blob_semantics(raw: dict[str, Any]) -> dict[str, Any]:
    name = short_class(raw.get("__className")) or "?"
    known = BLOB_PROFILE.get(name)
    if known is not None:
        return dict(known)
    return {
        "role": "unknown_active_blob",
        "hazard": None,
        "route_blocked": True,
        "map_glyph": "%",
        "basis": "unknown Blob class is conservatively route-blocked; harm is not asserted",
    }


def read_fd_all(fd: int, limit: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = os.read(fd, min(1024 * 1024, limit + 1 - total))
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)
        total += len(chunk)
        if total > limit:
            raise DecodeError(f"stored file exceeds {limit} bytes")


def decode_bundle(raw: bytes, path: Path) -> tuple[dict[str, Any], bytes, str]:
    if raw == b"\x01":
        raise NoActiveRun(f"deleted/empty slot marker 01 at {path}")
    if len(raw) <= 1:
        marker = raw.hex() if raw else "empty"
        raise DecodeError(f"invalid zero/one-byte bundle marker {marker} at {path}")

    def reject_constant(value: str) -> None:
        raise DecodeError(f"non-finite JSON number {value} in {path}")

    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise DecodeError(f"duplicate JSON key {key!r} in {path}")
            result[key] = value
        return result
    try:
        if raw[:2] == b"\x1f\x8b":
            with gzip.GzipFile(fileobj=io.BytesIO(raw), mode="rb") as stream:
                decoded = stream.read(MAX_DECODED_BYTES + 1)
            encoding = "gzip+json"
        else:
            decoded = raw
            encoding = "json"
        if len(decoded) > MAX_DECODED_BYTES:
            raise DecodeError(f"decoded bundle exceeds {MAX_DECODED_BYTES} bytes: {path}")
        data = json.loads(
            decoded.decode("utf-8"),
            parse_constant=reject_constant,
            object_pairs_hook=reject_duplicate_keys,
        )
    except InspectError:
        raise
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise DecodeError(f"cannot decode complete gzip/JSON bundle {path}: {error}") from error
    if not isinstance(data, dict):
        raise DecodeError(f"expected a JSON object in {path}")
    return data, decoded, encoding


def read_bundle_stable(path: Path) -> BundleRead:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    try:
        fd = os.open(path, flags)
    except FileNotFoundError as error:
        raise SaveBusy(f"canonical file missing during save rotation: {path}") from error
    try:
        before = os.fstat(fd)
        raw = read_fd_all(fd, MAX_STORED_BYTES)
        after = os.fstat(fd)
    finally:
        os.close(fd)
    try:
        canonical = path.stat()
    except FileNotFoundError as error:
        raise SaveBusy(f"canonical path disappeared during read: {path}") from error
    fd_stable = (
        before.st_dev == after.st_dev
        and before.st_ino == after.st_ino
        and before.st_size == after.st_size
        and before.st_mtime_ns == after.st_mtime_ns
        and before.st_ctime_ns == after.st_ctime_ns
    )
    path_stable = (
        after.st_dev == canonical.st_dev
        and after.st_ino == canonical.st_ino
        and after.st_size == canonical.st_size
        and after.st_mtime_ns == canonical.st_mtime_ns
        and after.st_ctime_ns == canonical.st_ctime_ns
    )
    if not fd_stable or not path_stable or len(raw) != canonical.st_size:
        raise SaveBusy(f"file changed or was replaced during read: {path}")
    data, decoded, encoding = decode_bundle(raw, path)
    stamp = FileStamp(
        path=path,
        dev=canonical.st_dev,
        ino=canonical.st_ino,
        size=canonical.st_size,
        mtime_ns=canonical.st_mtime_ns,
        ctime_ns=canonical.st_ctime_ns,
        raw_sha256=hashlib.sha256(raw).hexdigest(),
        decoded_sha256=hashlib.sha256(decoded).hexdigest(),
        encoding=encoding,
        decoded_size=len(decoded),
    )
    return BundleRead(data, raw, decoded, stamp)


def slot_dir(root: Path, slot: int) -> Path:
    return root / f"game{slot}"


def level_path_for(slot_path: Path, depth: int, branch: int) -> Path:
    return slot_path / (f"depth{depth}.dat" if branch == 0 else f"depth{depth}-branch{branch}.dat")


def spdtmp_files(slot_path: Path) -> list[Path]:
    return sorted(slot_path.glob("*.spdtmp"))


def path_stat_signature(path: Path) -> tuple[int, int, int, int, int] | None:
    try:
        stat = path.stat()
    except FileNotFoundError:
        return None
    return (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)


def stamp_stat_signature(stamp: FileStamp) -> tuple[int, int, int, int, int]:
    return (stamp.dev, stamp.ino, stamp.size, stamp.mtime_ns, stamp.ctime_ns)


def discover_slots(root: Path) -> list[dict[str, Any]]:
    result = []
    for directory in sorted(root.glob("game*")):
        if not directory.is_dir() or not directory.name[4:].isdigit():
            continue
        number = int(directory.name[4:])
        game_path = directory / "game.dat"
        try:
            stat = game_path.stat()
            size = stat.st_size
            mtime_ns = stat.st_mtime_ns
        except FileNotFoundError:
            size = 0
            mtime_ns = None
        entry = {
            "slot": number,
            "path": str(directory),
            "game_bytes": size,
            "non_tombstone_candidate": size > 1,
            "mtime_ns": mtime_ns,
            "spdtmp": [path.name for path in spdtmp_files(directory)],
            "game_loadable_version": False,
            "inspectable_pair": False,
            "diagnostic": None,
        }
        if size > 1:
            try:
                game_read = read_bundle_stable(game_path)
                version = game_read.data.get("version")
                entry["game_version"] = version
                entry["game_loadable_version"] = is_int(version) and version >= 802
                if not entry["game_loadable_version"]:
                    raise SchemaError(f"game version {version!r} is below the game's loadable floor 802")
                read_pair_once(root, number)
                entry["inspectable_pair"] = True
            except InspectError as error:
                entry["diagnostic"] = {"code": error.code, "message": str(error)}
        result.append(entry)
    return result


def choose_slot(root: Path, requested: str) -> int:
    if requested != "auto":
        try:
            slot = int(requested)
        except ValueError as error:
            raise InspectError("--slot must be a positive integer or auto") from error
        if slot < 1:
            raise InspectError("--slot must be at least 1")
        return slot
    slots = discover_slots(root)
    inspectable = [entry["slot"] for entry in slots if entry["inspectable_pair"]]
    if len(inspectable) == 1:
        return inspectable[0]
    if not inspectable:
        candidates = [entry for entry in slots if entry["non_tombstone_candidate"]]
        if candidates:
            raise NoActiveRun(f"non-tombstone slots exist but none has an inspectable game/current-level pair: {candidates}")
        raise NoActiveRun(f"no non-tombstone save slot under {root}")
    raise InspectError(f"multiple inspectable slots {inspectable}; pass --slot explicitly")


def is_int(value: Any) -> bool:
    return type(value) is int


def require_list(value: Any, name: str, length: int | None = None) -> list[Any]:
    if not isinstance(value, list):
        raise SchemaError(f"{name} must be an array")
    if length is not None and len(value) != length:
        raise SchemaError(f"{name} length {len(value)} != {length}")
    return value


def validate_pos(snapshot_length: int, obj: dict[str, Any], label: str, key: str = "pos") -> None:
    pos = obj.get(key)
    if not is_int(pos) or not 0 <= pos < snapshot_length:
        raise SchemaError(f"{label}.{key} is outside the level: {pos!r}")


def transition_cells_raw(raw: dict[str, Any], width: int, height: int) -> list[int]:
    center = raw.get("center")
    left, top, right, bottom = (raw.get(key) for key in ("left", "top", "right", "bottom"))
    if all(is_int(v) for v in (left, top, right, bottom)):
        if not (0 <= left <= right < width and 0 <= top <= bottom < height):
            raise SchemaError(f"invalid transition rectangle {(left, top, right, bottom)}")
        cells = [y * width + x for y in range(top, bottom + 1) for x in range(left, right + 1)]
    elif is_int(center) and 0 <= center < width * height:
        cells = [center]
    else:
        raise SchemaError("transition lacks valid rectangle/center")
    if not is_int(center) or center not in cells:
        raise SchemaError(f"transition center {center!r} is not inside its rectangle")
    return cells


def validate_schema(game: dict[str, Any], level: dict[str, Any], level_path: Path) -> list[str]:
    warnings = [
        "game.dat and depth data have no shared transaction ID; stable pairing is best-effort evidence",
        "persisted data may lag the focused live UI until a lifecycle save completes",
        "mob.seen is enemySeen, not current hero visibility; current heroFOV and generic alignment are not saved",
    ]
    if "branch" not in game:
        raise SchemaError("game.dat lacks required branch field; refusing to assume main branch")
    depth, branch = game.get("depth"), game.get("branch")
    if not is_int(depth) or depth < 1 or not is_int(branch) or branch < 0:
        raise SchemaError("game.dat lacks valid integer depth/branch")
    expected = level_path_for(level_path.parent, depth, branch).name
    if level_path.name != expected:
        raise SchemaError(f"level filename {level_path.name} != expected {expected}")
    game_version, level_version = game.get("version"), level.get("version")
    if not is_int(game_version) or not is_int(level_version):
        raise SchemaError("game and level must both contain integer version")
    if game_version != level_version:
        raise SchemaError(f"game.version {game_version} != level.version {level_version}")
    if game_version != PROFILE_CODE:
        warnings.append(
            f"profile mismatch: saved version {game_version}, inspector profile {PROFILE_CODE}; route disabled"
        )
    width, height = level.get("width"), level.get("height")
    if not is_int(width) or not is_int(height) or width <= 2 or height <= 2:
        raise SchemaError(f"invalid level dimensions {width!r}x{height!r}")
    length = width * height
    raw_map = require_list(level.get("map"), "level.map", length)
    visited = require_list(level.get("visited"), "level.visited", length)
    mapped = require_list(level.get("mapped"), "level.mapped", length)
    if not all(type(value) is bool for value in visited):
        raise SchemaError("level.visited must contain booleans")
    if not all(type(value) is bool for value in mapped):
        raise SchemaError("level.mapped must contain booleans")
    if not all(is_int(value) for value in raw_map):
        raise SchemaError("level.map must contain integer terrain IDs; JSON booleans are not integers here")
    unknown = sorted({value for value in raw_map if value not in TERRAIN})
    if unknown:
        if game_version == PROFILE_CODE:
            raise SchemaError(f"unknown terrain IDs in exact v3.3.8 profile: {unknown}")
        warnings.append(f"unknown terrain IDs {unknown}; route disabled")
    if type(level.get("locked")) is not bool:
        raise SchemaError(f"level.locked must be a boolean, got {level.get('locked')!r}")
    hero = game.get("hero")
    if not isinstance(hero, dict):
        raise SchemaError("game.dat lacks hero object")
    if game_version == PROFILE_CODE:
        expected_hero = "com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero"
        if hero.get("__className") != expected_hero:
            raise SchemaError(f"unexpected exact-profile hero class {hero.get('__className')!r}")
        if branch == 0:
            if 1 <= depth <= 4:
                expected_level = "SewerLevel"
            elif depth == 5:
                expected_level = "SewerBossLevel"
            elif 6 <= depth <= 9:
                expected_level = "PrisonLevel"
            elif depth == 10:
                expected_level = "PrisonBossLevel"
            elif 11 <= depth <= 14:
                expected_level = "CavesLevel"
            elif depth == 15:
                expected_level = "CavesBossLevel"
            elif 16 <= depth <= 19:
                expected_level = "CityLevel"
            elif depth == 20:
                expected_level = "CityBossLevel"
            elif 21 <= depth <= 24:
                expected_level = "HallsLevel"
            elif depth == 25:
                expected_level = "HallsBossLevel"
            elif depth == 26:
                expected_level = "LastLevel"
            else:
                expected_level = "DeadEndLevel"
        elif branch == 1 and 11 <= depth <= 14:
            expected_level = "MiningLevel"
        elif branch == 1 and 16 <= depth <= 19:
            expected_level = "VaultLevel"
        else:
            expected_level = "DeadEndLevel"
        expected_level_full = f"com.shatteredpixel.shatteredpixeldungeon.levels.{expected_level}"
        if level.get("__className") != expected_level_full:
            raise SchemaError(
                f"depth/branch {depth}/{branch} requires {expected_level_full}, got {level.get('__className')!r}"
            )
    validate_pos(length, hero, "hero")
    ht, hp = hero.get("HT"), hero.get("HP")
    if not is_int(ht) or ht <= 0 or not is_int(hp):
        raise SchemaError(f"invalid hero HP/HT {hp!r}/{ht!r}")
    if hp > ht:
        warnings.append(f"hero HP {hp} exceeds saved HT {ht}; retained as state rather than treated as corruption")
    for collection_name in ("mobs", "heaps", "traps", "plants"):
        for index, obj in enumerate(require_list(level.get(collection_name), f"level.{collection_name}")):
            if not isinstance(obj, dict):
                raise SchemaError(f"{collection_name}[{index}] must be an object")
            validate_pos(length, obj, f"{collection_name}[{index}]")
    for index, raw in enumerate(require_list(level.get("transitions"), "level.transitions")):
        if not isinstance(raw, dict):
            raise SchemaError(f"transitions[{index}] must be an object")
        if game_version == PROFILE_CODE and not all(key in raw for key in ("left", "top", "right", "bottom", "center")):
            raise SchemaError(f"exact-profile transition[{index}] lacks its complete inclusive rectangle")
        transition_cells_raw(raw, width, height)
        if raw.get("type") not in {"SURFACE", "REGULAR_ENTRANCE", "REGULAR_EXIT", "BRANCH_ENTRANCE", "BRANCH_EXIT"}:
            raise SchemaError(f"invalid transition type {raw.get('type')!r}")
        if not is_int(raw.get("dest_depth")) or not is_int(raw.get("dest_branch")):
            raise SchemaError("transition lacks integer destination depth/branch")
        dest_type = raw.get("dest_type")
        if dest_type is not None and dest_type not in {"SURFACE", "REGULAR_ENTRANCE", "REGULAR_EXIT", "BRANCH_ENTRANCE", "BRANCH_EXIT"}:
            raise SchemaError(f"invalid transition destination type {dest_type!r}")
        if game_version == PROFILE_CODE:
            if raw.get("type") == "SURFACE":
                if raw.get("dest_depth") != 0 or raw.get("dest_branch") != 0:
                    raise SchemaError("SURFACE transition must lead to depth=0 branch=0")
            elif "dest_type" not in raw or dest_type is None:
                raise SchemaError(f"exact-profile {raw.get('type')} transition lacks dest_type")
    for index, raw in enumerate(require_list(level.get("blobs"), "level.blobs")):
        if not isinstance(raw, dict):
            raise SchemaError(f"blobs[{index}] must be an object")
        start, cur = raw.get("start", 0), raw.get("cur", [])
        if not is_int(start) or not isinstance(cur, list) or start < 0 or start + len(cur) > length:
            raise SchemaError(f"blob[{index}] compressed cells exceed level bounds")
        if cur:
            if not is_int(raw.get("length")) or raw.get("length") != length:
                raise SchemaError(f"blob[{index}].length must equal map length {length} when cur is present")
            if not all(is_int(value) and value >= 0 for value in cur):
                raise SchemaError(f"blob[{index}].cur must contain non-negative integer concentrations")
    return warnings


def read_pair_once(root: Path, slot: int) -> Snapshot:
    directory = slot_dir(root, slot)
    pending = spdtmp_files(directory)
    if pending:
        raise SaveBusy("temporary save files present: " + ", ".join(path.name for path in pending))
    game_path = directory / "game.dat"
    if not game_path.exists():
        raise NoActiveRun(f"missing {game_path}")
    game_read = read_bundle_stable(game_path)
    if "branch" not in game_read.data:
        raise SchemaError("game.dat lacks required branch field")
    depth, branch = game_read.data.get("depth"), game_read.data.get("branch")
    if not is_int(depth) or not is_int(branch):
        raise SchemaError("game.dat lacks integer depth/branch")
    level_path = level_path_for(directory, depth, branch)
    level_read = read_bundle_stable(level_path)
    pending = spdtmp_files(directory)
    if pending:
        raise SaveBusy("temporary save files appeared during read: " + ", ".join(path.name for path in pending))
    level = level_read.data.get("level")
    if not isinstance(level, dict):
        raise SchemaError(f"{level_path} lacks a level object")
    delta = level_read.stamp.mtime_ns - game_read.stamp.mtime_ns
    if delta < 0 or delta > MAX_PAIR_DELTA_NS:
        raise SaveBusy(f"implausible game->level write delta {delta / 1e6:.3f}ms")
    warnings = validate_schema(game_read.data, level, level_path)
    return Snapshot(root, slot, game_read, level_read, level, depth, branch, "stable_pair", warnings)


def same_pair(a: Snapshot, b: Snapshot) -> bool:
    return (
        a.depth == b.depth
        and a.branch == b.branch
        and a.game_read.stamp.signature == b.game_read.stamp.signature
        and a.level_read.stamp.signature == b.level_read.stamp.signature
    )


def read_snapshot(
    root: Path,
    slot: int,
    timeout: float,
    settle_ms: int,
    wait_new: bool,
    max_age: float | None,
) -> Snapshot:
    deadline = time.monotonic() + max(0.0, timeout)
    baseline: Snapshot | None = None
    baseline_depth_files: dict[str, tuple[int, int, int, int, int]] = {}
    last_error: InspectError | None = None
    if wait_new:
        try:
            baseline = read_pair_once(root, slot)
            baseline_depth_files = {
                path.name: signature
                for path in slot_dir(root, slot).glob("depth*.dat")
                if (signature := path_stat_signature(path)) is not None
            }
        except InspectError as error:
            raise SaveBusy(
                "--wait-new requires a readable idle baseline before the save-triggering minimize action; "
                f"baseline unavailable: {error}"
            ) from error
    while True:
        try:
            first = read_pair_once(root, slot)
            time.sleep(max(0, settle_ms) / 1000)
            second = read_pair_once(root, slot)
            if not same_pair(first, second):
                raise SaveBusy("game/level pair changed between the two complete reads")
            if wait_new and baseline is not None:
                game_changed = second.game_read.stamp.signature != baseline.game_read.stamp.signature
                final_level_name = second.level_read.stamp.path.name
                prior_target_signature = baseline_depth_files.get(final_level_name)
                level_changed = (
                    prior_target_signature is None
                    or stamp_stat_signature(second.level_read.stamp) != prior_target_signature
                )
                if not (game_changed and level_changed):
                    raise SaveBusy(
                        "waiting for game.dat and the final target depth file to both update after baseline"
                    )
                second.coherence = "observed_pair_after_baseline"
            if max_age is not None and second.age_seconds > max_age:
                raise SaveBusy(f"stable checkpoint is {second.age_seconds:.3f}s old, above --max-age {max_age}")
            return second
        except InspectError as error:
            last_error = error
            if time.monotonic() >= deadline:
                if isinstance(error, NoActiveRun) and not wait_new:
                    raise
                raise type(error)(str(error)) from error
            time.sleep(0.05)
    raise last_error or SaveBusy("unable to read snapshot")


def point(snapshot: Snapshot, cell: Any) -> dict[str, Any]:
    result = {"cell": cell, "x": None, "y": None}
    if snapshot.valid_cell(cell):
        result["x"], result["y"] = snapshot.xy(cell)
    return result


def scalar_fields(obj: dict[str, Any], excluded: Iterable[str] = ()) -> dict[str, Any]:
    skip = set(excluded) | {"__className"}
    result: dict[str, Any] = {}
    for key in sorted(obj):
        if key in skip:
            continue
        value = obj[key]
        if value is None or isinstance(value, (str, int, float, bool)):
            result[key] = value
        elif isinstance(value, list) and len(value) <= 128 and all(
            item is None or isinstance(item, (str, int, float, bool)) for item in value
        ):
            result[key] = value
    return result


def normalize_node(obj: Any, depth: int = 0) -> Any:
    if not isinstance(obj, dict):
        return obj
    result = {**class_identity(obj), "fields": scalar_fields(obj)}
    if depth >= 4:
        return result
    nested = {}
    collections_out = {}
    for key, value in obj.items():
        if key == "__className":
            continue
        if isinstance(value, dict):
            nested[key] = normalize_node(value, depth + 1)
        elif isinstance(value, list) and value and all(isinstance(item, dict) for item in value):
            collections_out[key] = [normalize_node(item, depth + 1) for item in value]
    if nested:
        result["nested"] = nested
    if collections_out:
        result["collections"] = collections_out
    return result


def normalize_item(item: Any) -> Any:
    if not isinstance(item, dict):
        return item
    result = {**class_identity(item), "fields": scalar_fields(item, ("inventory",))}
    result["level"] = item.get("level")
    result["level_known"] = bool(item.get("levelKnown", False))
    result["cursed"] = item.get("cursed")
    result["curse_known"] = bool(item.get("cursedKnown", False))
    if isinstance(item.get("inventory"), list):
        result["contents"] = [normalize_item(child) for child in item["inventory"]]
    nested = {}
    for key in ("enchantment", "glyph", "seal"):
        if isinstance(item.get(key), dict):
            nested[key] = normalize_node(item[key])
    if nested:
        result["nested"] = nested
    return result


def normalize_buff(buff: dict[str, Any]) -> dict[str, Any]:
    return {**class_identity(buff), "fields": scalar_fields(buff)}


def normalize_hero(snapshot: Snapshot) -> dict[str, Any]:
    hero = snapshot.hero
    buffs = [normalize_buff(buff) for buff in hero.get("buffs", []) if isinstance(buff, dict)]
    hunger = None
    shielding_components = []
    for buff in buffs:
        fields = buff["fields"]
        if buff.get("class_short") == "Hunger" and isinstance(fields.get("level"), (int, float)):
            value = fields["level"]
            hunger = {
                "value": value,
                "state": "normal" if value < 300 else "hungry" if value < 450 else "starving",
                "hungry_threshold": 300,
                "starving_threshold": 450,
                "partial_damage": fields.get("partialDamage"),
            }
        if isinstance(fields.get("shielding"), (int, float)):
            shielding_components.append({
                "class_short": buff.get("class_short"),
                "shielding": fields["shielding"],
            })
    equipment_keys = ("weapon", "armor", "artifact", "misc", "ring", "second_wep")
    return {
        **point(snapshot, hero.get("pos")),
        "class": hero.get("class"),
        "subclass": hero.get("subClass"),
        "armor_ability": normalize_node(hero.get("armorAbility")) if isinstance(hero.get("armorAbility"), dict) else hero.get("armorAbility"),
        "HP": hero.get("HP"),
        "HT": hero.get("HT"),
        "STR_base_saved": hero.get("STR"),
        "level": hero.get("lvl"),
        "exp": hero.get("exp"),
        "attack_skill": hero.get("attackSkill"),
        "defense_skill": hero.get("defenseSkill"),
        "hunger": hunger,
        "shielding_saved_components": shielding_components,
        "shielding_saved_total": sum(component["shielding"] for component in shielding_components),
        "shielding_note": "sum of saved buffs carrying shielding; Hero does not save a standalone SHLD field",
        "equipment": {
            key: normalize_item(hero[key]) if isinstance(hero.get(key), dict) else None
            for key in equipment_keys
        },
        "inventory": [normalize_item(item) for item in hero.get("inventory", [])],
        "talents": {
            key: hero[key] for key in sorted(hero) if key.startswith("talents_tier_")
        },
        "buffs": buffs,
        "saved_scalar_fields": scalar_fields(hero, equipment_keys + ("inventory", "buffs")),
    }


def blob_cells(snapshot: Snapshot, raw: dict[str, Any]) -> list[dict[str, Any]]:
    start = int(raw.get("start", 0))
    result = []
    for offset, volume in enumerate(raw.get("cur", [])):
        cell = start + offset
        if isinstance(volume, (int, float)) and volume > 0:
            result.append({**point(snapshot, cell), "volume": volume})
    return result


def normalize_custom_tile(snapshot: Snapshot, raw: dict[str, Any]) -> dict[str, Any]:
    x, y = raw.get("tileX"), raw.get("tileY")
    width, height = raw.get("tileW", 1), raw.get("tileH", 1)
    cells = []
    if all(is_int(value) for value in (x, y, width, height)) and width > 0 and height > 0:
        for tile_y in range(y, y + height):
            for tile_x in range(x, x + width):
                if 0 <= tile_x < snapshot.width and 0 <= tile_y < snapshot.height:
                    cells.append(snapshot.cell(tile_x, tile_y))
    return {
        **class_identity(raw),
        "tile_x": x,
        "tile_y": y,
        "tile_width": width,
        "tile_height": height,
        "cells": cells,
        "saved_scalar_fields": scalar_fields(raw),
    }


def normalize_entities(snapshot: Snapshot) -> dict[str, Any]:
    mobs = []
    for raw in snapshot.level.get("mobs", []):
        hostility = {"value": "unknown", "basis": "generic Char.alignment is not saved"}
        if raw.get("alignment") in {"ENEMY", "NEUTRAL", "ALLY"}:
            hostility = {"value": raw["alignment"].lower(), "basis": "class-specific saved alignment"}
        elif raw.get("rat_ally") is True:
            hostility = {"value": "ally", "basis": "class-specific saved rat_ally"}
        mobs.append({
            **class_identity(raw),
            **point(snapshot, raw.get("pos")),
            "actor_id": raw.get("id"),
            "HP": raw.get("HP"),
            "HT": raw.get("HT"),
            "state": raw.get("state"),
            "enemy_seen": raw.get("seen"),
            "enemy_seen_note": "mob has seen its enemy; not current hero visibility",
            "target_cell": raw.get("target"),
            "enemy_actor_id": raw.get("enemy_id"),
            "hostility": hostility,
            "buffs": [normalize_buff(buff) for buff in raw.get("buffs", []) if isinstance(buff, dict)],
            "saved_scalar_fields": scalar_fields(raw, ("pos", "buffs")),
        })
    mobs.sort(key=lambda obj: (obj.get("cell", -1), obj.get("actor_id") or -1, obj.get("class_full") or ""))
    heaps = []
    for raw in snapshot.level.get("heaps", []):
        heaps.append({
            "id": f"heap:{raw.get('pos')}",
            **point(snapshot, raw.get("pos")),
            "type": raw.get("type", "HEAP"),
            "seen": raw.get("seen"),
            "hidden": raw.get("hidden"),
            "haunted": raw.get("haunted"),
            "items": [normalize_item(item) for item in raw.get("items", [])],
            "saved_scalar_fields": scalar_fields(raw, ("pos", "items")),
        })
    heaps.sort(key=lambda obj: obj["cell"])
    traps = []
    for raw in snapshot.level.get("traps", []):
        traps.append({
            **class_identity(raw), **point(snapshot, raw.get("pos")),
            "active": raw.get("active", True), "visible": raw.get("visible"),
            "saved_scalar_fields": scalar_fields(raw, ("pos",)),
        })
    traps.sort(key=lambda obj: obj["cell"])
    plants = []
    for raw in snapshot.level.get("plants", []):
        plants.append({
            **class_identity(raw), **point(snapshot, raw.get("pos")),
            "saved_scalar_fields": scalar_fields(raw, ("pos",)),
        })
    plants.sort(key=lambda obj: obj["cell"])
    blobs = []
    for raw in snapshot.level.get("blobs", []):
        cells = blob_cells(snapshot, raw)
        semantics = blob_semantics(raw)
        blobs.append({
            **class_identity(raw),
            **semantics,
            "volume_derived": sum(cell["volume"] for cell in cells),
            "volume_basis": "sum(saved trimmed cur)",
            "cells": cells,
            "active_cell_count": len(cells),
            "saved_scalar_fields": scalar_fields(raw, ("start", "cur", "off")),
        })
    blobs.sort(key=lambda obj: obj.get("class_full") or "")
    transitions = []
    for index, raw in enumerate(snapshot.level.get("transitions", [])):
        cells = transition_cells_raw(raw, snapshot.width, snapshot.height)
        transitions.append({
            "id": f"transition:{index}",
            "type": raw.get("type"),
            **point(snapshot, raw.get("center")),
            "bounds": {key: raw.get(key) for key in ("left", "top", "right", "bottom")},
            "cells": cells,
            "destination": {
                "depth": raw.get("dest_depth"),
                "branch": raw.get("dest_branch"),
                "type": raw.get("dest_type"),
            },
        })
    doors_stairs = []
    for cell, terrain_id in enumerate(snapshot.level["map"]):
        if terrain_id in DOOR_TERRAIN | TRANSITION_TERRAIN:
            info = TERRAIN[terrain_id]
            doors_stairs.append({
                **point(snapshot, cell), "terrain_id": terrain_id,
                "terrain": info.name, "glyph": info.glyph,
                "passable_flag": info.passable, "avoid_flag": info.avoid,
            })
    return {
        "mobs": mobs,
        "heaps": heaps,
        "traps": traps,
        "plants": plants,
        "blobs": blobs,
        "transitions": transitions,
        "doors_and_transition_terrain": doors_stairs,
        "custom_tiles": [normalize_custom_tile(snapshot, raw) for raw in snapshot.level.get("customTiles", []) if isinstance(raw, dict)],
        "custom_walls": [normalize_custom_tile(snapshot, raw) for raw in snapshot.level.get("customWalls", []) if isinstance(raw, dict)],
        "respawner": normalize_node(snapshot.level.get("respawner")) if isinstance(snapshot.level.get("respawner"), dict) else None,
    }


def snapshot_model(snapshot: Snapshot) -> dict[str, Any]:
    entities = normalize_entities(snapshot)
    game_version = snapshot.game.get("version")
    return {
        "schema": SCHEMA_VERSION,
        "tool": {"version": TOOL_VERSION},
        "profile": {
            "game": "shattered-pixel-dungeon",
            "expected_display_version": PROFILE_NAME,
            "expected_game_version_code": PROFILE_CODE,
            "source_commit": SOURCE_COMMIT,
            "saved_game_version_code": game_version,
            "compatibility": "exact" if game_version == PROFILE_CODE else "unknown_version",
        },
        "snapshot": {
            "id": snapshot.snapshot_id,
            "slot": snapshot.slot,
            "coherence": snapshot.coherence,
            "captured_at": datetime.now().astimezone().isoformat(),
            "age_seconds": round(snapshot.age_seconds, 3),
            "knowledge": "persisted_internal",
            "files": [snapshot.game_read.stamp.to_json(), snapshot.level_read.stamp.to_json()],
            "warnings": list(snapshot.warnings),
        },
        "run": {
            "depth": snapshot.depth,
            "branch": snapshot.branch,
            "duration": snapshot.game.get("duration"),
            "enemies_slain": snapshot.game.get("enemiesSlain"),
            "saved_scalar_fields": scalar_fields(snapshot.game, ("hero",)),
            "quickslot_saved_sections": {
                key: snapshot.game[key] for key in sorted(snapshot.game) if key.startswith("quickslot")
            },
            "top_level_keys": sorted(snapshot.game),
        },
        "hero": normalize_hero(snapshot),
        "level": {
            **class_identity(snapshot.level),
            "version": snapshot.level.get("version"),
            "width": snapshot.width,
            "height": snapshot.height,
            "feeling": snapshot.level.get("feeling"),
            "locked": snapshot.level.get("locked"),
            "visited_cells": sum(bool(v) for v in snapshot.level["visited"]),
            "mapped_cells": sum(bool(v) for v in snapshot.level["mapped"]),
            "terrain_ids_present": sorted(set(snapshot.level["map"])),
        },
        "entities": entities,
        "counts": {
            "mobs": len(entities["mobs"]),
            "heaps": len(entities["heaps"]),
            "traps": len(entities["traps"]),
            "plants": len(entities["plants"]),
            "blobs": len(entities["blobs"]),
            "transitions": len(entities["transitions"]),
            "custom_tiles": len(entities["custom_tiles"]),
            "custom_walls": len(entities["custom_walls"]),
            "respawner": 1 if entities["respawner"] else 0,
        },
    }


def item_label(item: Any) -> str:
    if not isinstance(item, dict):
        return "-"
    name = short_class(item.get("__className")) or "?"
    qty = item.get("quantity", 1)
    level = item.get("level", 0)
    known = bool(item.get("levelKnown", False))
    curse_known = bool(item.get("cursedKnown", False))
    bits = [name + (f"x{qty}" if qty != 1 else "")]
    if level:
        bits.append(f"stored-level={level:+d}" + (" known" if known else " unknown-to-hero"))
    if curse_known:
        bits.append("cursed" if item.get("cursed") else "uncursed")
    elif item.get("cursed"):
        bits.append("stored-cursed, unknown-to-hero")
    return " ".join(bits)


def flatten_inventory(items: Sequence[Any], prefix: str = "") -> list[str]:
    result = []
    for item in items:
        result.append(prefix + item_label(item))
        if isinstance(item, dict) and isinstance(item.get("inventory"), list):
            result.extend(flatten_inventory(item["inventory"], prefix + "  "))
    return result


def iter_item_tree(items: Sequence[Any]) -> Iterable[dict[str, Any]]:
    for item in items:
        if not isinstance(item, dict):
            continue
        yield item
        contents = item.get("inventory")
        if isinstance(contents, list):
            yield from iter_item_tree(contents)


def print_status(snapshot: Snapshot) -> None:
    hero = normalize_hero(snapshot)
    pair_delta = (snapshot.level_read.stamp.mtime_ns - snapshot.game_read.stamp.mtime_ns) / 1e6
    print(
        f"snapshot={snapshot.snapshot_id[:16]} coherence={snapshot.coherence} "
        f"age={snapshot.age_seconds:.3f}s pair_delta={pair_delta:.3f}ms slot={snapshot.slot}"
    )
    print(
        f"files=game.dat:{snapshot.game_read.stamp.size}B@{snapshot.game_read.stamp.mtime_ns} "
        f"{snapshot.level_read.stamp.path.name}:{snapshot.level_read.stamp.size}B@{snapshot.level_read.stamp.mtime_ns} spdtmp=0"
    )
    print(
        f"version={snapshot.game.get('version')} profile={PROFILE_CODE}/{PROFILE_NAME} "
        f"depth={snapshot.depth} branch={snapshot.branch} level={short_class(snapshot.level.get('__className'))} "
        f"size={snapshot.width}x{snapshot.height} feeling={snapshot.level.get('feeling')} locked={snapshot.level.get('locked')}"
    )
    hunger = hero.get("hunger") or {}
    print(
        f"hero={hero.get('class')}/{hero.get('subclass')} cell={hero.get('cell')} "
        f"xy=({hero.get('x')},{hero.get('y')}) HP={hero.get('HP')}/{hero.get('HT')} "
        f"shield={hero.get('shielding_saved_total')} STR={hero.get('STR_base_saved')} "
        f"lvl={hero.get('level')} exp={hero.get('exp')} hunger={hunger.get('state')}:{hunger.get('value')} "
        f"gold={snapshot.game.get('gold')} energy={snapshot.game.get('energy')}"
    )
    equipment = snapshot.hero
    print("equipment=" + ", ".join(
        f"{slot}:{item_label(equipment.get(slot))}"
        for slot in ("weapon", "armor", "artifact", "misc", "ring", "second_wep")
    ))
    print("inventory=" + ("; ".join(flatten_inventory(snapshot.hero.get("inventory", []))) or "-"))
    print("talents=" + json.dumps(hero["talents"], ensure_ascii=False, sort_keys=True))
    print("buffs=" + (", ".join(buff.get("class_short") or "?" for buff in hero["buffs"]) or "-"))
    entities = normalize_entities(snapshot)
    respawner_fields = entities["respawner"].get("fields", {}) if entities["respawner"] else {}
    print(
        f"statistics=duration:{snapshot.game.get('duration')} enemiesSlain:{snapshot.game.get('enemiesSlain')} "
        f"respawner_time:{respawner_fields.get('time')}"
    )
    print(
        f"entities=mobs:{len(entities['mobs'])} heaps:{len(entities['heaps'])} "
        f"traps:{len(entities['traps'])} plants:{len(entities['plants'])} "
        f"blobs_with_cells:{sum(bool(blob['cells']) for blob in entities['blobs'])} "
        f"blob_hazards:{sum(blob['hazard'] is True and bool(blob['cells']) for blob in entities['blobs'])} "
        f"blob_unknown:{sum(blob['hazard'] is None and bool(blob['cells']) for blob in entities['blobs'])} "
        f"blob_landmarks:{sum(blob['role'] == 'landmark_marker' and bool(blob['cells']) for blob in entities['blobs'])} "
        f"transitions:{len(entities['transitions'])} custom_tiles:{len(entities['custom_tiles'])} "
        f"custom_walls:{len(entities['custom_walls'])} respawner:{1 if entities['respawner'] else 0}"
    )
    for warning in snapshot.warnings:
        print("warning=" + warning)


def heap_glyph(raw: dict[str, Any]) -> str:
    heap_type = raw.get("type", "HEAP")
    if heap_type == "FOR_SALE":
        return "q"
    if heap_type != "HEAP":
        return {"CHEST": "c", "LOCKED_CHEST": "l", "CRYSTAL_CHEST": "v", "TOMB": "t", "SKELETON": "b", "REMAINS": "b"}.get(heap_type, "c")
    names = [short_class(item.get("__className")) or "" for item in raw.get("items", []) if isinstance(item, dict)]
    joined = " ".join(names)
    if "ScrollOfUpgrade" in joined:
        return "U"
    if "PotionOfStrength" in joined:
        return "S"
    if "PotionOfHealing" in joined:
        return "H"
    if any(token in joined for token in ("Food", "Pasty", "Meat", "Ration", "Berry")):
        return "F"
    if "Key" in joined:
        return "K"
    if "Gold" in joined:
        return "$"
    if "Potion" in joined:
        return "!"
    if "Scroll" in joined:
        return "?"
    if "Armor" in joined:
        return "a"
    if any(token in joined for token in ("Weapon", "Sword", "Dagger", "Mace", "Spear", "Axe", "Bow", "Wand", "Staff", "Shield", "Gauntlet")):
        return "w"
    return "i"


def render_map(snapshot: Snapshot, historical_visited_mask: bool) -> tuple[str, list[str]]:
    visited, mapped = snapshot.level["visited"], snapshot.level["mapped"]
    rows = []
    for y in range(snapshot.height):
        row = []
        for x in range(snapshot.width):
            cell = snapshot.cell(x, y)
            if historical_visited_mask and not (visited[cell] or mapped[cell] or cell == snapshot.hero_cell):
                row.append("?")
            else:
                row.append(TERRAIN.get(snapshot.level["map"][cell], TerrainInfo("UNKNOWN", "?", solid=True)).glyph)
        rows.append(row)
    overlays: dict[int, list[tuple[int, str, str]]] = collections.defaultdict(list)
    for raw in snapshot.level.get("heaps", []):
        overlays[int(raw["pos"])].append((20, heap_glyph(raw), "heap"))
    for raw in snapshot.level.get("plants", []):
        overlays[int(raw["pos"])].append((30, "p", "plant"))
    for raw in snapshot.level.get("customTiles", []):
        if short_class(raw.get("__className")) == "WeakFloorRoom$HiddenWell":
            cell = snapshot.cell(int(raw.get("tileX", -1)), int(raw.get("tileY", -1)))
            if snapshot.valid_cell(cell):
                overlays[cell].append((35, "w", "custom tile:distant well"))
    for raw in snapshot.level.get("blobs", []):
        semantics = blob_semantics(raw)
        for entry in blob_cells(snapshot, raw):
            overlays[int(entry["cell"])].append(
                (40, semantics["map_glyph"], f"blob:{semantics['role']}")
            )
    for raw in snapshot.level.get("traps", []):
        overlays[int(raw["pos"])].append((50, "^" if raw.get("active", True) else "_", "trap"))
    for raw in snapshot.level.get("transitions", []):
        glyph = ">" if raw.get("type") in {"REGULAR_EXIT", "BRANCH_EXIT"} else "<"
        for cell in transition_cells_raw(raw, snapshot.width, snapshot.height):
            overlays[cell].append((60, glyph, f"transition:{raw.get('type')}"))
    for raw in snapshot.level.get("mobs", []):
        overlays[int(raw["pos"])].append((70, "M", f"mob:{short_class(raw.get('__className'))}"))
    overlays[snapshot.hero_cell].append((100, "@", "hero"))
    conflicts = []
    for cell, values in overlays.items():
        if not snapshot.valid_cell(cell):
            continue
        if historical_visited_mask and not (visited[cell] or mapped[cell] or cell == snapshot.hero_cell):
            continue
        values.sort(reverse=True)
        x, y = snapshot.xy(cell)
        rows[y][x] = values[0][1]
        if len(values) > 1:
            conflicts.append(f"cell={cell} xy=({x},{y}) layers=" + ",".join(value[2] for value in values))
    row_digits = max(2, len(str(snapshot.height - 1)))
    prefix = " " * (row_digits + 2)
    output = [
        prefix + "".join(str((x // 10) % 10) if x >= 10 else " " for x in range(snapshot.width)),
        prefix + "".join(str(x % 10) for x in range(snapshot.width)),
    ]
    output.extend(f"{y:0{row_digits}d}  {''.join(row)}" for y, row in enumerate(rows))
    return "\n".join(output), conflicts


def apply_historical_visited_mask(snapshot: Snapshot, payload: dict[str, Any]) -> None:
    """Filter a normalized map payload to historically visited/mapped cells.

    This is intentionally not called a player-knowledge or current-FOV view.
    Persisted secrets in a historically known cell remain persisted internals.
    """
    visited, mapped = snapshot.level["visited"], snapshot.level["mapped"]
    known = {
        cell for cell in range(snapshot.length)
        if visited[cell] or mapped[cell] or cell == snapshot.hero_cell
    }
    entities = payload["entities"]
    for key in ("mobs", "heaps", "traps", "plants", "doors_and_transition_terrain"):
        entities[key] = [entry for entry in entities[key] if entry.get("cell") in known]
    for key in ("custom_tiles", "custom_walls"):
        entities[key] = [
            entry for entry in entities[key] if any(cell in known for cell in entry.get("cells", []))
        ]
    masked_blobs = []
    for blob in entities["blobs"]:
        blob["cells"] = [entry for entry in blob["cells"] if entry.get("cell") in known]
        blob["active_cell_count"] = len(blob["cells"])
        blob["volume_derived"] = sum(entry["volume"] for entry in blob["cells"])
        if blob["cells"]:
            masked_blobs.append(blob)
    entities["blobs"] = masked_blobs
    entities["transitions"] = [
        transition
        for transition in entities["transitions"]
        if transition.get("cell") in known
    ]
    payload["snapshot"]["knowledge"] = "persisted_internal_filtered_to_historical_visited_mapped_cells"
    payload["snapshot"]["warnings"].append(
        "historical visited/mapped mask is not current FOV or pure player knowledge; persisted secrets in retained cells remain internal data"
    )
    masked_terrain = [
        value if cell in known else None
        for cell, value in enumerate(snapshot.level["map"])
    ]
    payload["level"]["terrain_ids_present"] = sorted({
        value for value in masked_terrain if value is not None
    })
    payload["map"] = {
        "terrain": masked_terrain,
        "visited": snapshot.level["visited"],
        "mapped": snapshot.level["mapped"],
        "historical_visited_mask": True,
    }


def print_entities(snapshot: Snapshot) -> None:
    entities = normalize_entities(snapshot)
    print("Mobs (all are persisted internal entities; hostility/current visibility may be unknown):")
    for mob in entities["mobs"]:
        print(
            f"  {mob.get('class_short')} id={mob.get('actor_id')} HP={mob.get('HP')}/{mob.get('HT')} "
            f"state={mob.get('state')} enemySeen={mob.get('enemy_seen')} target={mob.get('target_cell')} "
            f"cell={mob.get('cell')} xy=({mob.get('x')},{mob.get('y')}) hostility={mob['hostility']['value']}"
        )
    print("Heaps/items:")
    for heap in entities["heaps"]:
        raw = next(raw for raw in snapshot.level["heaps"] if raw.get("pos") == heap["cell"])
        print(
            f"  {heap_glyph(raw)} type={heap.get('type')} seen={heap.get('seen')} hidden={heap.get('hidden')} "
            f"haunted={heap.get('haunted')} cell={heap.get('cell')} xy=({heap.get('x')},{heap.get('y')}) "
            f"items=[{', '.join(item.get('class_short') or '?' for item in heap['items'])}]"
        )
    print("Traps:")
    for trap in entities["traps"]:
        print(
            f"  {trap.get('class_short')} active={trap.get('active')} visible={trap.get('visible')} "
            f"cell={trap.get('cell')} xy=({trap.get('x')},{trap.get('y')})"
        )
    print("Plants:")
    for plant in entities["plants"]:
        print(f"  {plant.get('class_short')} cell={plant.get('cell')} xy=({plant.get('x')},{plant.get('y')})")
    print("Blobs:")
    for blob in entities["blobs"]:
        print(
            f"  {blob.get('class_short')} role={blob.get('role')} hazard={blob.get('hazard')} "
            f"route_blocked={blob.get('route_blocked')} volume={blob.get('volume_derived')} "
            f"basis={blob.get('volume_basis')} active_cells={blob.get('active_cell_count')} "
            f"cells=[{','.join(str(cell['cell']) + ':' + str(cell['volume']) for cell in blob['cells'])}]"
        )
    print("Custom tilemaps/walls:")
    for tile in entities["custom_tiles"] + entities["custom_walls"]:
        print(
            f"  {tile.get('class_short')} tile=({tile.get('tile_x')},{tile.get('tile_y')}) "
            f"size={tile.get('tile_width')}x{tile.get('tile_height')} cells={tile.get('cells')}"
        )
    if not entities["custom_tiles"] and not entities["custom_walls"]:
        print("  -")
    print("Respawner actor (not a currently instantiated Mob):")
    respawner = entities.get("respawner")
    if respawner:
        print(
            f"  {respawner.get('class_short')} fields="
            + json.dumps(respawner.get("fields", {}), ensure_ascii=False, sort_keys=True)
        )
    else:
        print("  -")
    print("Transitions (rectangles are inclusive and every contained cell may transition):")
    for transition in entities["transitions"]:
        print(
            f"  {transition.get('type')} center={transition.get('cell')} xy=({transition.get('x')},{transition.get('y')}) "
            f"bounds={transition.get('bounds')} cells={transition.get('cells')} -> {transition.get('destination')}"
        )


def transition_cell_set(snapshot: Snapshot) -> set[int]:
    result: set[int] = set()
    for raw in snapshot.level.get("transitions", []):
        result.update(transition_cells_raw(raw, snapshot.width, snapshot.height))
    return result


def adjacent_cells(snapshot: Snapshot, cells: Iterable[int]) -> set[int]:
    source = set(cells)
    result = set()
    for cell in source:
        x, y = snapshot.xy(cell)
        for dx, dy, _, _ in DIRS:
            nx, ny = x + dx, y + dy
            if 0 <= nx < snapshot.width and 0 <= ny < snapshot.height:
                candidate = snapshot.cell(nx, ny)
                if candidate not in source:
                    result.add(candidate)
    return result


def chebyshev(snapshot: Snapshot, a: int, b: int) -> int:
    ax, ay = snapshot.xy(a)
    bx, by = snapshot.xy(b)
    return max(abs(ax - bx), abs(ay - by))


def resolve_route_goals(
    snapshot: Snapshot,
    target: str,
    allow_special_transition_after_source_review: bool,
) -> tuple[str, set[int], set[int], str | None]:
    lower = target.lower()
    transitions = snapshot.level.get("transitions", [])
    transition_targets = {
        "exit": {"REGULAR_EXIT"},
        "entrance": {"REGULAR_ENTRANCE"},
        "surface": {"SURFACE"},
        "branch-exit": {"BRANCH_EXIT"},
        "branch-entrance": {"BRANCH_ENTRANCE"},
    }
    if lower in transition_targets:
        if snapshot.level.get("locked") is True:
            raise RouteError(f"level is locked; {lower} transition is not currently usable")
        if lower in {"branch-exit", "branch-entrance"}:
            if not allow_special_transition_after_source_review:
                raise RouteError(
                    "branch transitions have quest/item/buff-specific activation rules; inspect exact level source and pass "
                    "--allow-special-transition-after-source-review only to navigate adjacent, never as proof the transition works"
                )
            if short_class(snapshot.level.get("__className")) == "VaultLevel":
                raise RouteError("VaultLevel transitions cannot be activated by stepping; use the source-defined EscapeCrystal flow")
        if lower == "surface":
            lost_inventory = any(
                short_class(buff.get("__className")) == "LostInventory"
                for buff in snapshot.hero.get("buffs", []) if isinstance(buff, dict)
            )
            accessible_amulet = any(
                short_class(item.get("__className")) == "Amulet"
                and (not lost_inventory or item.get("kept_lost") is True)
                for item in iter_item_tree(snapshot.hero.get("inventory", []))
            )
            if not accessible_amulet:
                raise RouteError("SURFACE transition is unusable without an Amulet accessible under current LostInventory state")
        wanted = transition_targets[lower]
        originals = set()
        for raw in transitions:
            if raw.get("type") in wanted:
                originals.update(transition_cells_raw(raw, snapshot.width, snapshot.height))
        if not originals:
            raise RouteError(f"no {lower} transition in this snapshot")
        if snapshot.hero_cell in originals:
            interaction = "hero is already inside the transition rectangle; do not move, use only a separate explicit transition action after rechecking"
            return lower, originals, {snapshot.hero_cell}, interaction
        interaction = "stop outside transition rectangle; change level only by a separate explicit action"
        if lower in {"branch-exit", "branch-entrance"}:
            interaction = "stop outside special transition; source-specific quest/item action may be required and stepping is not assumed usable"
        return lower, originals, adjacent_cells(snapshot, originals), interaction
    if lower.startswith("cell:"):
        try:
            cell = int(target.split(":", 1)[1])
        except ValueError as error:
            raise RouteError("cell target must be cell:INTEGER") from error
    elif lower.startswith("xy:"):
        try:
            x_text, y_text = target.split(":", 1)[1].split(",", 1)
            x, y = int(x_text), int(y_text)
        except ValueError as error:
            raise RouteError("xy target must be xy:X,Y") from error
        if not (0 <= x < snapshot.width and 0 <= y < snapshot.height):
            raise RouteError(f"xy ({x},{y}) is outside {snapshot.width}x{snapshot.height}")
        cell = snapshot.cell(x, y)
    elif lower.startswith("item:"):
        query = target.split(":", 1)[1]
        exact_heaps = []
        for raw in snapshot.level.get("heaps", []):
            identities = {
                identity
                for item in raw.get("items", []) if isinstance(item, dict)
                for identity in (item.get("__className"), short_class(item.get("__className")))
                if identity
            }
            if query in identities:
                exact_heaps.append(raw)
        if not exact_heaps:
            raise RouteError(f"no heap contains exact item class {query!r}")
        originals = {int(raw["pos"]) for raw in exact_heaps}
        direct = {
            int(raw["pos"])
            for raw in exact_heaps
            if raw.get("type", "HEAP") in {"HEAP", "FOR_SALE"}
        }
        goals = direct | adjacent_cells(snapshot, originals - direct)
        interaction = "move/pick up only after rechecking" if direct else "container interaction required from adjacent cell"
        return target, originals, goals, interaction
    else:
        raise RouteError(
            "target must be exit, entrance, surface, branch-exit, branch-entrance, cell:N, xy:X,Y, or item:ExactClassName"
        )
    if not snapshot.valid_cell(cell):
        raise RouteError(f"cell {cell} is outside the map")
    info = TERRAIN.get(snapshot.level["map"][cell])
    if info and info.passable:
        return target, {cell}, {cell}, None
    return target, {cell}, adjacent_cells(snapshot, {cell}), "target itself is not enterable; route ends adjacent"


def compute_route(
    snapshot: Snapshot,
    target: str,
    policy: str,
    known_only: bool,
    mob_radius: int,
    allow_mobs_after_source_review: bool = False,
    allow_special_transition_after_source_review: bool = False,
    allow_hero_buffs_after_source_review: bool = False,
) -> dict[str, Any]:
    if snapshot.game.get("version") != PROFILE_CODE or any(value not in TERRAIN for value in snapshot.level["map"]):
        raise RouteError("route requires exact v3.3.8/code-896 terrain profile")
    if not is_int(snapshot.hero.get("HP")) or snapshot.hero.get("HP") <= 0:
        raise RouteError(
            "hero HP is non-positive; persisted state may be a death/resurrection UI window, so no movement route is valid"
        )
    if snapshot.level.get("locked") is True:
        raise RouteError(
            "generic routing is disabled on a locked combat/Boss level; inspect persisted telegraphs and exact enemy source, then plan one action manually"
        )
    if snapshot.level.get("mobs") and not allow_mobs_after_source_review:
        raise RouteError(
            "persisted mobs are present; generic routing is disabled because ranged attacks and saved telegraphs are not simulated. "
            "Inspect exact enemy source/state first, then pass --allow-mobs-after-source-review only for a reviewed one-step candidate."
        )
    active_ground_items = [
        {
            "cell": raw.get("pos"),
            "class": short_class(item.get("__className")),
        }
        for raw in snapshot.level.get("heaps", [])
        for item in raw.get("items", []) if isinstance(item, dict) and isinstance(item.get("fuse"), dict)
    ]
    if active_ground_items:
        raise RouteError(
            "generic routing is disabled while a persisted ground item has an active fuse: "
            + json.dumps(active_ground_items, ensure_ascii=False, sort_keys=True)
        )
    route_neutral_buffs = {
        "Regeneration", "Barrier", "BrokenSeal$WarriorShield"
    }
    unreviewed_buff_set = set()
    for buff in snapshot.hero.get("buffs", []):
        if not isinstance(buff, dict):
            continue
        name = short_class(buff.get("__className")) or "?"
        if name == "Hunger":
            level = buff.get("level")
            if not isinstance(level, (int, float)) or level >= 449:
                unreviewed_buff_set.add(f"Hunger(level={level!r}, threshold-risk)")
            continue
        if name not in route_neutral_buffs:
            unreviewed_buff_set.add(name)
    unreviewed_buffs = sorted(unreviewed_buff_set)
    if unreviewed_buffs and not allow_hero_buffs_after_source_review:
        raise RouteError(
            "generic routing is disabled while unreviewed hero buffs may alter movement or contain positional telegraphs: "
            + ", ".join(unreviewed_buffs)
            + ". Inspect exact buff source/state first, then use --allow-hero-buffs-after-source-review only for one reviewed action."
        )
    label, original_goals, candidate_goals, interaction = resolve_route_goals(
        snapshot, target, allow_special_transition_after_source_review
    )
    transitions = transition_cell_set(snapshot)
    mobs = {int(raw["pos"]) for raw in snapshot.level.get("mobs", [])}
    traps = {int(raw["pos"]) for raw in snapshot.level.get("traps", []) if raw.get("active", True)}
    plants = {int(raw["pos"]) for raw in snapshot.level.get("plants", [])}
    blob_hazards = {
        int(cell["cell"])
        for raw in snapshot.level.get("blobs", [])
        if blob_semantics(raw)["route_blocked"]
        for cell in blob_cells(snapshot, raw)
    }
    containers = {
        int(raw["pos"])
        for raw in snapshot.level.get("heaps", [])
        if raw.get("type", "HEAP") not in {"HEAP", "FOR_SALE"}
    }
    mob_exclusion = set(mobs)
    if mob_radius > 0:
        for mob in mobs:
            mx, my = snapshot.xy(mob)
            for dy in range(-mob_radius, mob_radius + 1):
                for dx in range(-mob_radius, mob_radius + 1):
                    x, y = mx + dx, my + dy
                    if 0 <= x < snapshot.width and 0 <= y < snapshot.height:
                        mob_exclusion.add(snapshot.cell(x, y))
    blocked = transitions | mobs | traps | plants | blob_hazards | containers | mob_exclusion
    start = snapshot.hero_cell
    blocked.discard(start)
    visited, mapped = snapshot.level["visited"], snapshot.level["mapped"]

    def enterable(cell: int) -> bool:
        x, y = snapshot.xy(cell)
        if x in {0, snapshot.width - 1} or y in {0, snapshot.height - 1}:
            return False
        info = TERRAIN.get(snapshot.level["map"][cell])
        if info is None or info.pit:
            return False
        if policy == "strict" and not info.passable:
            return False
        if policy == "geometry" and not (info.passable or info.avoid):
            return False
        if known_only and not (visited[cell] or mapped[cell]):
            return False
        if cell in blocked:
            return False
        return True

    goals = {cell for cell in candidate_goals if snapshot.valid_cell(cell) and enterable(cell)}
    if not goals:
        raise RouteError("no candidate goal remains under current blockers/policy")
    queue = collections.deque([start])
    previous: dict[int, int | None] = {start: None}
    found = start if start in goals else None
    while queue and found is None:
        current = queue.popleft()
        x, y = snapshot.xy(current)
        for dx, dy, _, _ in DIRS:
            nx, ny = x + dx, y + dy
            if not (0 <= nx < snapshot.width and 0 <= ny < snapshot.height):
                continue
            nxt = snapshot.cell(nx, ny)
            if nxt in previous or not enterable(nxt):
                continue
            previous[nxt] = current
            if nxt in goals:
                found = nxt
                break
            queue.append(nxt)
    if found is None:
        raise RouteError("no route under current static blockers; do not weaken policy without reviewing hazards")
    path = [found]
    while path[-1] != start:
        parent = previous[path[-1]]
        if parent is None:
            break
        path.append(parent)
    path.reverse()
    original = min(original_goals, key=lambda cell: (chebyshev(snapshot, found, cell), cell))
    target_metadata = None
    if label.lower().startswith("item:"):
        query = label.split(":", 1)[1]
        matching_heaps = []
        for raw in snapshot.level.get("heaps", []):
            identities = {
                identity
                for item in raw.get("items", []) if isinstance(item, dict)
                for identity in (item.get("__className"), short_class(item.get("__className")))
                if identity
            }
            if query in identities:
                pos = int(raw["pos"])
                heap_type = raw.get("type", "HEAP")
                if heap_type in {"HEAP", "FOR_SALE"} and pos == found:
                    matching_heaps.append((0, pos, raw))
                elif heap_type not in {"HEAP", "FOR_SALE"} and chebyshev(snapshot, pos, found) == 1:
                    matching_heaps.append((1, pos, raw))
        if matching_heaps:
            _, original, selected_heap = min(matching_heaps, key=lambda entry: (entry[0], entry[1]))
        else:
            selected_heap = next(raw for raw in snapshot.level.get("heaps", []) if int(raw["pos"]) == original)
        heap_type = selected_heap.get("type", "HEAP")
        target_metadata = {
            "heap": point(snapshot, original),
            "heap_type": heap_type,
            "seen": selected_heap.get("seen"),
            "hidden": selected_heap.get("hidden"),
            "haunted": selected_heap.get("haunted"),
            "item_classes": [
                short_class(item.get("__className"))
                for item in selected_heap.get("items", []) if isinstance(item, dict)
            ],
        }
        if heap_type == "HEAP" and found == original:
            interaction = "move/pick up only after rechecking the destination cell"
        elif heap_type == "FOR_SALE" and found == original:
            interaction = "arrive on the sale heap, then use a separately verified shop/buy interaction"
        else:
            danger = " haunted" if selected_heap.get("haunted") else ""
            interaction = f"{danger} {heap_type} container interaction required from the adjacent route endpoint".strip()
    next_step = None
    if len(path) > 1:
        nxt = path[1]
        sx, sy = snapshot.xy(start)
        nx, ny = snapshot.xy(nxt)
        direction, keypad = DIR_BY_DELTA[(nx - sx, ny - sy)]
        terrain_id = snapshot.level["map"][nxt]
        reasons = []
        if terrain_id == 5:
            reasons.append(
                "closed door: this action opens and enters it; new visibility invalidates the route, and the door normally closes again after the hero leaves"
            )
            action = "OPEN_AND_ENTER_DOOR"
        else:
            action = "MOVE_OR_INTERACT"
        if not (visited[nxt] or mapped[nxt]):
            reasons.append("unvisited/unmapped destination")
        if terrain_id in {15, 29, 30}:
            reasons.append(f"caution terrain {TERRAIN[terrain_id].name}")
        if any(raw.get("pos") == nxt for raw in snapshot.level.get("heaps", [])):
            reasons.append("heap/item interaction")
            action = "MOVE_PICKUP_OR_INTERACT"
        nearby = [mob for mob in mobs if chebyshev(snapshot, nxt, mob) <= max(2, mob_radius)]
        if nearby:
            reasons.append(f"persisted mobs within Chebyshev {max(2, mob_radius)}: {sorted(nearby)}")
        next_step = {
            "from": point(snapshot, start),
            "to": point(snapshot, nxt),
            "direction": direction,
            "keypad": keypad,
            "action_kind": action,
            "hard_stop_reasons": reasons,
            "replan_after": True,
        }
    result = {
        "schema": ROUTE_SCHEMA_VERSION,
        "snapshot_id": snapshot.snapshot_id,
        "snapshot": {
            "coherence": snapshot.coherence,
            "age_seconds": round(snapshot.age_seconds, 3),
            "knowledge": "visited_mapped_only" if known_only else "persisted_internal",
            "game_version": snapshot.game.get("version"),
            "level_minus_game_mtime_ms": round(
                (snapshot.level_read.stamp.mtime_ns - snapshot.game_read.stamp.mtime_ns) / 1e6, 3
            ),
            "files": [snapshot.game_read.stamp.to_json(), snapshot.level_read.stamp.to_json()],
            "warnings": list(snapshot.warnings),
        },
        "policy": policy,
        "known_only": known_only,
        "mob_exclusion_radius": mob_radius,
        "mobs_allowed_after_source_review": allow_mobs_after_source_review,
        "special_transition_allowed_after_source_review": allow_special_transition_after_source_review,
        "hero_buffs_allowed_after_source_review": allow_hero_buffs_after_source_review,
        "target": label,
        "target_original": point(snapshot, original),
        "route_end": point(snapshot, found),
        "distance_steps_static": len(path) - 1,
        "path_uses_unvisited_or_unmapped": any(
            not (visited[cell] or mapped[cell]) for cell in path[1:]
        ),
        "interaction": interaction,
        "target_metadata": target_metadata,
        "recommended_execution_prefix": 1 if next_step else 0,
        "next_step": next_step,
        "replan_triggers": [
            "after every action", "new/moved/following mob", "door or visibility change",
            "HP/buff change", "trap/plant/blob change", "item interaction", "before any transition",
        ],
        "assumptions": [
            "all persisted mobs are blocked because generic alignment is not saved",
            "all profile-route-blocked or unknown Blob cells and all plants are conservatively blocked; known landmark markers are not",
            "the saved MobSpawner respawner is reported but future spawn timing/position is not simulated",
            "no ranged line-of-fire or future AI/RNG simulation is performed",
            "route never enters any saved transition rectangle",
        ],
        "warning": "static advisory only; never batch the path or send keys automatically",
    }
    return result


def print_route(route: dict[str, Any]) -> None:
    evidence = route["snapshot"]
    print(
        f"snapshot={route['snapshot_id'][:16]} coherence={evidence['coherence']} "
        f"age={evidence['age_seconds']:.3f}s knowledge={evidence['knowledge']} "
        f"target={route['target']} policy={route['policy']} "
        f"distance={route['distance_steps_static']} end={route['route_end']}"
    )
    if route.get("interaction"):
        print("interaction=" + route["interaction"])
    print(f"path_uses_unvisited_or_unmapped={route['path_uses_unvisited_or_unmapped']}")
    step = route.get("next_step")
    if step:
        print(
            f"next_only={step['direction']} {step['keypad']} action={step['action_kind']} "
            f"to={step['to']}"
        )
        print("hard_stop=" + ("; ".join(step["hard_stop_reasons"]) or "mandatory one-action recheck"))
    else:
        print("next_only=already at route endpoint; perform no movement without a fresh decision")
    for warning in evidence["warnings"]:
        print("evidence_warning=" + warning)
    print("warning=" + route["warning"])


def write_test_bundle(path: Path, obj: dict[str, Any], mtime_ns: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
    path.write_bytes(gzip.compress(raw, mtime=0))
    os.utime(path, ns=(mtime_ns, mtime_ns))


def run_selftest() -> None:
    passed = []
    try:
        decode_bundle(b"\x01", Path("game.dat"))
    except NoActiveRun:
        passed.append("exact 0x01 deleted-slot marker")
    else:
        raise AssertionError("0x01 marker was not classified as an empty/deleted slot")
    for malformed in (b"\x02", b'{"x":NaN}'):
        try:
            decode_bundle(malformed, Path("malformed.dat"))
        except DecodeError:
            pass
        else:
            raise AssertionError(f"malformed bundle was accepted: {malformed!r}")
    passed.append("one-byte and non-finite JSON rejection")
    with tempfile.TemporaryDirectory(prefix="spd-inspect-selftest-") as temp:
        root = Path(temp)
        width = height = 9
        raw_map = [4] * (width * height)
        for y in range(1, height - 1):
            for x in range(1, width - 1):
                raw_map[y * width + x] = 1
        raw_map[2 * width + 3] = 5
        raw_map[4 * width + 4] = 18
        raw_map[7 * width + 7] = 8
        game = {
            "version": PROFILE_CODE, "depth": 1, "branch": 0, "gold": 12, "energy": 3,
            "duration": 0, "enemiesSlain": 0,
            "hero": {
                "__className": "com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero",
                "class": "WARRIOR", "subClass": "NONE", "pos": width + 1,
                "HP": 20, "HT": 20, "STR": 10, "lvl": 1, "exp": 0,
                "weapon": {"__className": "x.WornShortsword", "level": 0, "levelKnown": True},
                "armor": {"__className": "x.ClothArmor", "level": 0, "levelKnown": True},
                "inventory": [{"__className": "x.Waterskin", "volume": 3}],
                "buffs": [{"__className": "x.Hunger", "level": 44, "partialDamage": 0}],
                "talents_tier_1": {"IRON_WILL": 1},
            },
        }
        blob_cell = 3 * width + 5
        well_cell = 2 * width + 2
        level = {
            "__className": "com.shatteredpixel.shatteredpixeldungeon.levels.SewerLevel", "version": PROFILE_CODE,
            "width": width, "height": height, "map": raw_map,
            "visited": [True] * (width * height), "mapped": [False] * (width * height),
            "locked": False, "feeling": "NONE",
            "mobs": [],
            "heaps": [
                {"pos": width + 2, "type": "HEAP", "seen": True, "hidden": False, "items": [{"__className": "x.ScrollOfUpgrade", "quantity": 1}]},
                {"pos": 2 * width + 1, "type": "TOMB", "seen": True, "hidden": False, "haunted": True, "items": [{"__className": "x.PotionOfHealing", "quantity": 1}]},
                {"pos": width + 6, "type": "HEAP", "seen": True, "hidden": False, "items": [{"__className": "x.PotionOfHealing", "quantity": 1}]},
            ],
            "traps": [{"__className": "x.PoisonDartTrap", "pos": 4 * width + 4, "active": True, "visible": True}],
            "plants": [{"__className": "x.Sorrowmoss", "pos": 3 * width + 6}],
            "blobs": [
                {"__className": "x.ToxicGas", "start": blob_cell, "length": width * height, "cur": [4]},
                {
                    "__className": "com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.WeakFloorRoom$WellID",
                    "start": well_cell, "length": width * height, "cur": [1],
                },
            ],
            "customTiles": [{
                "__className": "com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.WeakFloorRoom$HiddenWell",
                "tileX": 2, "tileY": 2, "tileW": 1, "tileH": 1,
            }],
            "customWalls": [],
            "respawner": {
                "__className": "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner",
                "time": 50, "id": 14,
            },
            "transitions": [
                {"left": 6, "top": 6, "right": 7, "bottom": 7, "center": 7 * width + 7, "type": "REGULAR_EXIT", "dest_depth": 2, "dest_branch": 0, "dest_type": "REGULAR_ENTRANCE"},
                {"left": 1, "top": 3, "right": 1, "bottom": 3, "center": 3 * width + 1, "type": "BRANCH_EXIT", "dest_depth": 1, "dest_branch": 1, "dest_type": "BRANCH_ENTRANCE"},
            ],
        }
        base = time.time_ns() - 1_000_000_000
        game_path = root / "game1/game.dat"
        level_path = root / "game1/depth1.dat"
        write_test_bundle(game_path, game, base)
        write_test_bundle(level_path, {"level": level}, base + 1_000_000)
        before = (hashlib.sha256(game_path.read_bytes()).hexdigest(), hashlib.sha256(level_path.read_bytes()).hexdigest())
        snapshot = read_snapshot(root, 1, 0.5, 5, False, None)
        assert snapshot.game.get("version") == PROFILE_CODE
        passed.append("double-read exact-profile snapshot")
        model = snapshot_model(snapshot)
        assert model["hero"]["hunger"]["state"] == "normal"
        assert model["hero"]["talents"]["talents_tier_1"]["IRON_WILL"] == 1
        toxic = next(blob for blob in model["entities"]["blobs"] if blob["class_short"] == "ToxicGas")
        well_marker = next(blob for blob in model["entities"]["blobs"] if blob["class_short"] == "WeakFloorRoom$WellID")
        assert toxic["volume_derived"] == 4 and toxic["route_blocked"] is True
        assert well_marker["role"] == "landmark_marker" and well_marker["hazard"] is False
        assert well_marker["route_blocked"] is False and well_marker["map_glyph"] == "w"
        assert model["entities"]["respawner"]["class_short"] == "MobSpawner"
        assert model["entities"]["respawner"]["fields"]["time"] == 50
        assert model["entities"]["custom_tiles"][0]["cells"] == [well_cell]
        passed.append("hero/talent/hunger plus Blob landmark/custom-tile/respawner normalization")
        assert model["run"]["duration"] == 0 and model["run"]["enemies_slain"] == 0
        assert model["counts"]["mobs"] == 0 and model["counts"]["respawner"] == 1
        passed.append("promoted run statistics and entity counts")
        route = compute_route(snapshot, "exit", "strict", False, 0)
        all_transition_cells = transition_cell_set(snapshot)
        assert route["route_end"]["cell"] not in all_transition_cells
        assert route["target_original"]["cell"] in transition_cells_raw(level["transitions"][0], width, height)
        assert route["recommended_execution_prefix"] <= 1
        branch_route = compute_route(snapshot, "branch-exit", "strict", False, 0, False, True)
        assert branch_route["target_original"]["cell"] == 3 * width + 1
        passed.append("regular/branch exits stay distinct, stop outside rectangles, and emit one action")
        original_hero_cell = snapshot.hero["pos"]
        snapshot.hero["pos"] = 7 * width + 7
        already_on_exit = compute_route(snapshot, "exit", "strict", False, 0)
        assert already_on_exit["distance_steps_static"] == 0 and already_on_exit["next_step"] is None
        snapshot.hero["pos"] = original_hero_cell
        passed.append("hero already in transition rectangle receives no movement step")
        snapshot.hero["pos"] = 2 * width + 2
        door_action = compute_route(snapshot, f"cell:{2 * width + 3}", "strict", False, 0)
        assert door_action["next_step"]["action_kind"] == "OPEN_AND_ENTER_DOOR"
        assert door_action["next_step"]["to"]["cell"] == 2 * width + 3
        snapshot.hero["pos"] = original_hero_cell
        passed.append("adjacent closed-door action is labelled open-and-enter")
        snapshot.level["mobs"].append({
            "__className": "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Eye",
            "id": 9, "pos": 5 * width + 5, "HP": 100, "HT": 100,
            "state": "HUNTING", "seen": True, "target": snapshot.hero_cell,
            "beamTarget": snapshot.hero_cell, "beamCharged": True, "buffs": [],
        })
        try:
            compute_route(snapshot, "exit", "strict", False, 1)
        except RouteError:
            passed.append("persisted mob/telegraph disables generic route until explicit source review")
        else:
            raise AssertionError("generic route ignored a persisted mob telegraph")
        snapshot.level["mobs"].clear()
        snapshot.hero["buffs"].append({
            "__className": "com.shatteredpixel.shatteredpixeldungeon.levels.traps.PitfallTrap$DelayedPit",
            "positions": [snapshot.hero_cell + 1], "depth": 1, "branch": 0,
        })
        try:
            compute_route(snapshot, "exit", "strict", False, 0)
        except RouteError:
            passed.append("unreviewed hero positional-telegraph buff disables generic route")
        else:
            raise AssertionError("generic route ignored a persisted hero telegraph")
        snapshot.hero["buffs"].pop()
        hunger_buff = snapshot.hero["buffs"][0]
        saved_hunger_level, saved_hp = hunger_buff["level"], snapshot.hero["HP"]
        hunger_buff["level"], snapshot.hero["HP"] = 450, 1
        try:
            compute_route(snapshot, "exit", "strict", False, 0)
        except RouteError:
            passed.append("starving/threshold-risk Hunger disables generic route")
        else:
            raise AssertionError("generic route treated lethal Hunger timing as neutral")
        hunger_buff["level"], snapshot.hero["HP"] = saved_hunger_level, saved_hp
        snapshot.hero["HP"] = 0
        try:
            compute_route(snapshot, "exit", "strict", False, 0)
        except RouteError:
            passed.append("non-positive HP/death-resurrection state disables movement route")
        else:
            raise AssertionError("generic route treated a non-positive-HP hero as movable")
        snapshot.hero["HP"] = saved_hp
        snapshot.level["heaps"].append({
            "pos": 2 * width + 2, "type": "HEAP", "seen": True, "hidden": False,
            "items": [{"__className": "x.Bomb", "fuse": {"__className": "x.Bomb$Fuse", "time": 1}}],
        })
        try:
            compute_route(snapshot, "exit", "strict", False, 0)
        except RouteError:
            passed.append("active ground-item fuse disables generic route")
        else:
            raise AssertionError("generic route ignored an active bomb fuse")
        snapshot.level["heaps"].pop()
        wall_route = compute_route(snapshot, "xy:0,0", "strict", False, 0)
        assert wall_route["route_end"]["cell"] != 0
        passed.append("impassable target is never forcibly unblocked")
        item_route = compute_route(snapshot, "item:ScrollOfUpgrade", "strict", False, 0)
        assert item_route["next_step"] is not None
        passed.append("exact item target and pickup route")
        container_route = compute_route(snapshot, "item:PotionOfHealing", "strict", False, 0)
        assert container_route["target_metadata"]["heap_type"] == "TOMB"
        assert container_route["target_metadata"]["haunted"] is True
        assert "container" in container_route["interaction"]
        passed.append("mixed item targets preserve selected haunted-container metadata")
        try:
            compute_route(snapshot, f"cell:{3 * width + 6}", "strict", False, 0)
        except RouteError:
            passed.append("plant cell remains blocked even when it is the target")
        else:
            raise AssertionError("plant target was forcibly unblocked")
        try:
            compute_route(snapshot, f"cell:{blob_cell}", "strict", False, 0)
        except RouteError:
            passed.append("active blob cell remains blocked even when it is the target")
        else:
            raise AssertionError("active blob target was forcibly unblocked")
        rendered, _ = render_map(snapshot, False)
        assert "@" in rendered and ">" in rendered and "^" in rendered and "p" in rendered
        assert "%" in rendered and "w" in rendered
        passed.append("layered persisted map rendering")
        hidden_cell = 4 * width + 4
        snapshot.level["visited"][hidden_cell] = False
        masked = snapshot_model(snapshot)
        apply_historical_visited_mask(snapshot, masked)
        assert masked["map"]["terrain"][hidden_cell] is None
        assert 18 not in masked["level"]["terrain_ids_present"]
        snapshot.level["visited"][hidden_cell] = True
        passed.append("JSON historical mask removes unvisited terrain")
        snapshot.level["locked"] = True
        try:
            compute_route(snapshot, "exit", "strict", False, 0)
        except RouteError:
            passed.append("locked level refuses transition route")
        else:
            raise AssertionError("locked transition was treated as usable")
        snapshot.level["locked"] = False
        marker = root / "game1/game.dat.spdtmp"
        marker.write_bytes(b"busy")
        try:
            read_pair_once(root, 1)
        except SaveBusy:
            passed.append("spdtmp busy gate")
        else:
            raise AssertionError(".spdtmp was accepted")
        marker.unlink()
        after = (hashlib.sha256(game_path.read_bytes()).hexdigest(), hashlib.sha256(level_path.read_bytes()).hexdigest())
        assert before == after
        passed.append("normal inspection left fixture bytes unchanged")
        branch_game = dict(game)
        branch_game["branch"] = 1
        branch_level = dict(level)
        branch_level["__className"] = "com.shatteredpixel.shatteredpixeldungeon.levels.DeadEndLevel"
        write_test_bundle(root / "game2/game.dat", branch_game, base + 2_000_000)
        write_test_bundle(root / "game2/depth1-branch1.dat", {"level": branch_level}, base + 3_000_000)
        branch_snapshot = read_snapshot(root, 2, 0.5, 5, False, None)
        assert branch_snapshot.level_read.stamp.path.name == "depth1-branch1.dat"
        passed.append("branch filename")
        write_test_bundle(root / "game3/game.dat", game, base + 4_000_000)
        write_test_bundle(root / "game3/depth1.dat", {"level": level}, base + 5_000_000)

        def update_observed_pair() -> None:
            time.sleep(0.08)
            updated_game = dict(game)
            updated_game["gold"] = 13
            write_test_bundle(root / "game3/game.dat", updated_game, base + 6_000_000)
            write_test_bundle(root / "game3/depth1.dat", {"level": level}, base + 7_000_000)

        updater = threading.Thread(target=update_observed_pair)
        updater.start()
        observed = read_snapshot(root, 3, 1.0, 5, True, None)
        updater.join()
        assert observed.coherence == "observed_pair_after_baseline" and observed.game.get("gold") == 13
        passed.append("wait-new observes both rewritten files before publishing")
        game4 = dict(game)
        write_test_bundle(root / "game4/game.dat", game4, base + 8_000_000)
        write_test_bundle(root / "game4/depth1.dat", {"level": level}, base + 9_000_000)
        level2 = dict(level)
        write_test_bundle(root / "game4/depth2.dat", {"level": level2}, base + 12_000_000)

        def switch_game_only_to_preexisting_depth() -> None:
            time.sleep(0.08)
            switched = dict(game4)
            switched["depth"] = 2
            write_test_bundle(root / "game4/game.dat", switched, base + 11_000_000)

        switcher = threading.Thread(target=switch_game_only_to_preexisting_depth)
        switcher.start()
        try:
            read_snapshot(root, 4, 0.3, 5, True, None)
        except SaveBusy:
            passed.append("wait-new rejects game-only switch to a preexisting unchanged depth file")
        else:
            raise AssertionError("unchanged preexisting target depth was mislabeled observed")
        switcher.join()
    print(f"selftest=PASS tests={len(passed)}")
    for name in passed:
        print("  PASS " + name)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Persistent read-only SPD v3.3.8 inspector")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--slot", default="auto", help="positive save slot or auto")
    parser.add_argument("--timeout", type=float, default=1.0, help="seconds to retry rotating/incomplete saves")
    parser.add_argument("--settle-ms", type=int, default=50, help="delay between two full pair reads")
    parser.add_argument("--wait-new", action="store_true", help="wait until game and current depth files both change after baseline")
    parser.add_argument("--max-age", type=float, help="reject a stable checkpoint older than this many seconds")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("slots")
    sub.add_parser("status")
    map_parser = sub.add_parser("map")
    map_parser.add_argument(
        "--historical-visited-mask",
        action="store_true",
        help="filter to visited/mapped cells; not current FOV or pure player knowledge",
    )
    sub.add_parser("entities")
    route_parser = sub.add_parser("route")
    route_parser.add_argument("target", help="exit | entrance | surface | branch-exit | branch-entrance | cell:N | xy:X,Y | item:ExactClassName")
    route_parser.add_argument("--policy", choices=("strict", "geometry"), default="strict")
    route_parser.add_argument(
        "--known-only",
        action="store_true",
        help="route only through visited/mapped terrain; default uses explicitly labelled persisted-internal map data",
    )
    route_parser.add_argument("--mob-radius", type=int, default=1, help="block this Chebyshev radius around every persisted mob")
    route_parser.add_argument(
        "--allow-mobs-after-source-review",
        action="store_true",
        help="permit a one-step candidate with persisted mobs only after exact enemy/telegraph source review",
    )
    route_parser.add_argument(
        "--allow-special-transition-after-source-review",
        action="store_true",
        help="navigate adjacent to a branch transition after source review; never proves it can activate",
    )
    route_parser.add_argument(
        "--allow-hero-buffs-after-source-review",
        action="store_true",
        help="permit one-step routing with non-neutral saved buffs only after exact buff/telegraph source review",
    )
    route_parser.add_argument(
        "--allow-stable-pair",
        action="store_true",
        help="explicitly permit a non-observed stable pair; also requires --max-age",
    )
    sub.add_parser("raw", help="emit exact decoded game and level JSON plus snapshot identity")
    sub.add_parser("selftest")
    return parser


def emit_error(error: InspectError, json_mode: bool) -> None:
    if json_mode:
        json.dump({
            "schema": "spd.inspect.error/v1",
            "error": {"code": error.code, "message": str(error), "retryable": error.retryable},
        }, sys.stdout, ensure_ascii=False, indent=2)
        print()
    else:
        print(f"{error.code}: {error}", file=sys.stderr)


def main() -> int:
    args = build_parser().parse_args()
    if args.timeout < 0 or args.settle_ms < 0 or args.max_age is not None and args.max_age < 0:
        raise InspectError("timeout, settle-ms, and max-age must be non-negative")
    root = args.root.expanduser()
    if args.command == "selftest":
        run_selftest()
        return 0
    if args.command == "slots":
        slots = discover_slots(root)
        if args.json:
            json.dump({"schema": "spd.inspect.slots/v1", "root": str(root), "slots": slots}, sys.stdout, ensure_ascii=False, indent=2)
            print()
        else:
            print(f"root={root}")
            for entry in slots:
                print(
                    f"slot={entry['slot']} candidate={entry['non_tombstone_candidate']} "
                    f"loadable_version={entry['game_loadable_version']} inspectable_pair={entry['inspectable_pair']} "
                    f"game_bytes={entry['game_bytes']} mtime_ns={entry['mtime_ns']} "
                    f"spdtmp={entry['spdtmp']} diagnostic={entry['diagnostic']}"
                )
        return 0
    slot = choose_slot(root, args.slot)
    snapshot = read_snapshot(root, slot, args.timeout, args.settle_ms, args.wait_new, args.max_age)
    if args.command == "status":
        if args.json:
            json.dump(snapshot_model(snapshot), sys.stdout, ensure_ascii=False, indent=2)
            print()
        else:
            print_status(snapshot)
        return 0
    if args.command == "map":
        if args.json:
            payload = snapshot_model(snapshot)
            if args.historical_visited_mask:
                apply_historical_visited_mask(snapshot, payload)
            else:
                payload["map"] = {
                    "terrain": snapshot.level["map"],
                    "visited": snapshot.level["visited"],
                    "mapped": snapshot.level["mapped"],
                    "historical_visited_mask": False,
                }
            json.dump(payload, sys.stdout, ensure_ascii=False, indent=2)
            print()
        else:
            print_status(snapshot)
            print()
            rendered, conflicts = render_map(snapshot, args.historical_visited_mask)
            print(rendered)
            if args.historical_visited_mask:
                print("warning=historical visited/mapped mask is not current FOV or pure player knowledge; retained cells still show persisted internals")
            print("Legend: @ hero; M persisted mob; < > transition rectangle; + closed door; / open door; L/C locked door")
            print("        U upgrade; S strength; H healing; F food; K key; $ gold; ! potion; ? scroll/secret trap")
            print("        V chasm; w distant-well landmark; p plant; ^ active trap; % route-blocked unknown/dangerous blob")
            print("        q sale item; c/l/v container; s persisted secret door")
            if conflicts:
                print("Overlay conflicts (highest-priority glyph shown):")
                for conflict in conflicts:
                    print("  " + conflict)
            print()
            if args.historical_visited_mask:
                print("entity_table=omitted in historical-visited-mask text mode; use the separate entities command for full persisted internals")
            else:
                print_entities(snapshot)
        return 0
    if args.command == "entities":
        if args.json:
            json.dump({
                "schema": "spd.inspect.entities/v1",
                "snapshot_id": snapshot.snapshot_id,
                "knowledge": "persisted_internal",
                "entities": normalize_entities(snapshot),
            }, sys.stdout, ensure_ascii=False, indent=2)
            print()
        else:
            print_status(snapshot)
            print()
            print_entities(snapshot)
        return 0
    if args.command == "route":
        if args.mob_radius < 0:
            raise RouteError("--mob-radius must be non-negative")
        observed = snapshot.coherence == "observed_pair_after_baseline"
        if not observed and not args.allow_stable_pair:
            raise RouteError(
                "route requires --wait-new so both final files are observed updating; "
                "use --allow-stable-pair with --max-age only for an explicitly reviewed fallback"
            )
        if not observed and args.allow_stable_pair and args.max_age is None:
            raise RouteError("--allow-stable-pair requires an explicit --max-age freshness bound")
        route = compute_route(
            snapshot,
            args.target,
            args.policy,
            args.known_only,
            args.mob_radius,
            args.allow_mobs_after_source_review,
            args.allow_special_transition_after_source_review,
            args.allow_hero_buffs_after_source_review,
        )
        if args.json:
            json.dump(route, sys.stdout, ensure_ascii=False, indent=2)
            print()
        else:
            print_route(route)
        return 0
    if args.command == "raw":
        json.dump({
            "schema": "spd.inspect.raw/v1",
            "snapshot_id": snapshot.snapshot_id,
            "warning": "raw Bundle fields are version-specific; use normalized status for stable automation",
            "game": snapshot.game_read.data,
            "level_bundle": snapshot.level_read.data,
        }, sys.stdout, ensure_ascii=False, indent=2)
        print()
        return 0
    raise InspectError(f"unknown command {args.command}")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except InspectError as error:
        json_mode = "--json" in sys.argv
        emit_error(error, json_mode)
        raise SystemExit(
            3 if isinstance(error, NoActiveRun)
            else 4 if isinstance(error, SaveBusy)
            else 5 if isinstance(error, (DecodeError, SchemaError))
            else 6 if isinstance(error, RouteError)
            else 2
        )
