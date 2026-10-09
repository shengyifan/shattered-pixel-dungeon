#!/usr/bin/env python3
"""Read-only public-snapshot Ballistica aid; never sends gameplay or intents.

Default source is the observed hero. --source may select another explicitly
observed character for a hypothetical geometric aim (not a hidden enemy aim).
Read one explicitly supplied public response; its binding is never live.
Use this frame's public terrain descriptor, including publicly presented solid
custom decorations. Missing public sources never authorize recovering private
terrain or occupants; every prediction remains conditional on those omissions.
"""
import argparse
import ast
import hashlib
import re
import sys

sys.dont_write_bytecode = True
from public_snapshot import (REPO, SnapshotError, add_response_argument, emit,
                             load_snapshot, report_error)

JAVA = REPO / "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon"

REVIEWED_BALLISTICA = "3cce12645f1bf662481887df0fca19f0d68130741ff58a66557995ba3a359427"


def expression(text, constants, flags):
    def visit(node):
        if isinstance(node, ast.Constant) and type(node.value) is int:
            return node.value
        if isinstance(node, ast.Name) and node.id in constants:
            return constants[node.id]
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr):
            return visit(node.left) | visit(node.right)
        if isinstance(node, ast.Subscript) and isinstance(node.value, ast.Name) and node.value.id == "flags":
            return flags[visit(node.slice)]
        raise ValueError("Unsupported source integer expression: " + text)
    return visit(ast.parse(text.strip(), mode="eval").body)


def constants(text):
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
    result = {}
    for name, value in re.findall(r"public\s+static\s+final\s+int\s+(\w+)\s*=\s*([^;]+);", text):
        result[name] = expression(value, result, {})
    return result, text


def source_model():
    paths = {"ballistica": JAVA / "mechanics/Ballistica.java", "terrain": JAVA / "levels/Terrain.java"}
    raw = {name: path.read_bytes() for name, path in paths.items()}
    hashes = {name: hashlib.sha256(value).hexdigest() for name, value in raw.items()}
    if hashes["ballistica"] != REVIEWED_BALLISTICA:
        raise ValueError("Ballistica.java changed; review this port before using predictions")
    bc, _ = constants(raw["ballistica"].decode())
    tc, terrain_text = constants(raw["terrain"].decode())
    tf = {}
    for name, value in re.findall(r"flags\[(\w+)\]\s*=\s*([^;]+);", terrain_text):
        tf[tc[name]] = expression(value, tc, tf)
    return bc, tc, tf, {
        "files": {name: {"path": str(paths[name]), "sha256": hashes[name]} for name in paths},
        "references": {
            "line_and_collision_order": str(paths["ballistica"]) + ":74",
            "integer_error_and_ties": str(paths["ballistica"]) + ":114",
            "inside_map_boundary": str(JAVA / "levels/Level.java") + ":1564",
            "item_projectile": str(JAVA / "items/Item.java") + ":634",
            "wand_default": str(JAVA / "items/wands/Wand.java") + ":132",
            "blast_wave_override": str(JAVA / "items/wands/WandOfBlastWave.java") + ":63",
            "eye_stop_solid": str(JAVA / "actors/mobs/Eye.java") + ":94",
            "public_web_solid": str(JAVA / "actors/blobs/Web.java") + ":104",
            "public_light_wall_overrides": str(JAVA / "actors/hero/spells/WallOfLight.java") + ":298",
            "public_key_wall_overrides": str(JAVA / "items/artifacts/SkeletonKey.java") + ":547",
            "public_eternal_fire_overrides": str(JAVA / "levels/rooms/special/MagicalFireRoom.java") + ":281",
        },
    }


def inside_map(cell, width, height):
    return not (cell < width or cell >= width * height - width or cell % width in (0, width - 1))


def create_line(source, target, width, height):
    # Preserve native ties, including source == target: dA == dB == 0 steps
    # northwest after the source. Only STOP_TARGET collides at that source.
    dx, dy = target % width - source % width, target // width - source // width
    sx, sy = (1 if dx > 0 else -1), (1 if dy > 0 else -1)
    dx, dy = abs(dx), abs(dy)
    if dx > dy:
        sa, sb, da, db = sx, sy * width, dx, dy
    else:
        sa, sb, da, db = sy * width, sx, dy, dx
    cell, error, line = source, da // 2, []
    while inside_map(cell, width, height):
        line.append({"cell": cell, "error_before_step": error})
        cell += sa
        error += db
        if error >= da:
            error -= da
            cell += sb
        if len(line) > width * height + 1:
            raise ValueError("Unexpected nonterminating line")
    return line, {"step_a": sa, "step_b": sb, "d_a": da, "d_b": db,
                  "initial_error": da // 2, "first_outside_insideMap": cell}


def public_collision_flags(cell, terrain_constants, terrain_flags):
    """Apply explicit public effect overrides without guessing from names or prose.

    Current native producers only write false passability/avoidance and true
    solidity, so their partial overrides commute. Conflicting future facts do
    not gain artificial priority from the serialized effect order.
    """
    bits = terrain_flags.get(cell.get("terrain"))
    flags = {key: None if bits is None else bool(bits & terrain_constants[key.upper()])
             for key in ("passable", "avoid", "solid")}
    overrides = {}
    for effect in cell.get("environment", []):
        collision = effect.get("collision")
        if collision is None:
            if effect.get("type") in {"light_wall", "key_wall", "eternal_fire", "web"}:
                return flags, overrides, "missing_public_environment_collision"
            continue
        for key, value in collision.items():
            if key not in flags or type(value) is not bool:
                raise ValueError("Invalid public environment collision override")
            if key in overrides and overrides[key] != value:
                return flags, overrides, "conflicting_public_environment_collision"
            overrides[key] = value
    flags.update(overrides)
    return flags, overrides, None


def predict(frame, target, op, model, requested_source=None):
    bc, tc, tf, provenance = model
    data = frame["data"]
    m = data["map"]
    width, height = m["w"], m["h"]
    hero = data["hero"]["cell"]
    entities = {}
    for e in data.get("entities", []):
        if type(e.get("cell")) is int:
            entities.setdefault(e["cell"], []).append(e)
    source_characters = {q for q, group in entities.items()
                         if any(e.get("kind") == "character" for e in group)}
    source_characters.add(hero)
    # Native OBJECTs use Actor.findChar for projectile collision but cannot
    # supply a hypothetical character attack source. Appearance cues, including
    # movement shadows and disguised objects, never establish occupancy.
    collision_occupants = source_characters | {
        q for q, group in entities.items() if any(
            e.get("kind") == "object" and e.get("projectile_collision") == "character"
            for e in group)}
    source = hero if requested_source is None else requested_source
    if source not in source_characters:
        raise ValueError("Source must be the hero or an explicitly observed character in this snapshot")
    if any(type(v) is not int for v in (source, target, width, height)) or min(width, height) < 1:
        raise ValueError("Integer source, target and positive dimensions are required")
    if not 0 <= source < width * height or not 0 <= target < width * height:
        raise ValueError("Endpoint is outside this map")
    profiles = {"projectile": bc["PROJECTILE"], "magicbolt": bc["MAGIC_BOLT"], "solidbeam": bc["STOP_SOLID"]}
    params = profiles[op]
    st, sc, ss, soft = (bool(params & bc[k]) for k in ("STOP_TARGET", "STOP_CHARS", "STOP_SOLID", "IGNORE_SOFT_SOLID"))
    cells = m.get("decoded_cells", m.get("cells", []))
    known = {c["cell"]: c for c in cells}
    geometry, stepping = create_line(source, target, width, height)
    evaluated, assumptions, remembered = [], [], []
    collision = unknown = None
    for index, step in enumerate(geometry):
        q = step["cell"]
        c = known.get(q)
        row = {"index": index, **step, "x": q % width, "y": q // width,
               "known_cell": c, "same_frame_entities": entities.get(q, []),
               "public_character_present": q in source_characters,
               "public_collision_occupant_present": q in collision_occupants}
        evaluated.append(row)
        if q == source:
            if st and q == target:
                collision = {"cell": q, "trigger_cell": q, "reason": "stop_target"}
                break
            continue
        # CLI.9.0.1 retains publicly presented solid decorations in terrain.
        # Use only this descriptor, never names, nearby actors or private flags.
        flags, overrides, environment_unknown = (public_collision_flags(c, tc, tf)
                if c is not None else ({key: None for key in ("passable", "avoid", "solid")}, {}, None))
        row["flags"] = flags
        row["public_environment_collision_overrides"] = overrides
        if environment_unknown is not None:
            unknown = {"cell": q, "index": index, "reason": environment_unknown}
            break
        if any(value is None for value in flags.values()):
            if q in collision_occupants and sc:
                collision = {"cell": q, "trigger_cell": q, "reason": "known_collision_occupant_independent_of_unknown_flags"}
            else:
                unknown = {"cell": q, "index": index,
                           "reason": "unknown_or_unclassified_terrain"}
            break
        p, av, solid = (flags[key] for key in ("passable", "avoid", "solid"))
        if q not in collision_occupants:
            assumptions.append(q)
        if c.get("visibility") != "visible":
            remembered.append(q)
        # This native test occurs before path.add(cell), so hard terrain can
        # select the PREVIOUS cell. Closed passable doors instead stop on self.
        if ss and not p and not av and q not in collision_occupants:
            collision = {"cell": geometry[index - 1]["cell"], "trigger_cell": q,
                         "reason": "hard_terrain_without_observed_collision_occupant_stops_at_previous"}
        elif ss and solid and not (soft and (p or av)):
            collision = {"cell": q, "trigger_cell": q, "reason": "solid_cell"}
        elif sc and q in collision_occupants:
            collision = {"cell": q, "trigger_cell": q, "reason": "known_collision_occupant"}
        elif st and q == target:
            collision = {"cell": q, "trigger_cell": q, "reason": "stop_target"}
        if collision is not None:
            break
    # Ballistica's constructor inserts the source if insideMap rejects it and
    # build produces no cells. This is a geometric fallback, not a legal aim.
    line = [r["cell"] for r in geometry] or [source]
    if collision is None and unknown is None:
        collision = {"cell": line[-1] if line else source, "trigger_cell": None,
                     "reason": "native_insideMap_boundary_fallback"}
    travel = None
    if collision is not None:
        distance = line.index(collision["cell"])
        collision.update(dist=distance, conditional=True)
        travel = line[:distance + 1]
    target_index = line.index(target) if target in line else None
    return {
        "source": source, "target": target, "hero_cell": hero, "width": width, "height": height,
        "source_mode": "observed_hero" if source == hero else "observed_character_hypothetical_aim",
        "op": op, "flags": params, "stop_target": st, "stop_characters": sc, "stop_solid": ss,
        "stepping": stepping, "geometry_line_including_after_collision": line,
        "evaluated_path_including_blocking_probe": evaluated,
        "conditional_collision_prediction": collision, "conditional_travel_path_to_collision": travel,
        "first_unknown_stops_prediction": unknown,
        "cells_assuming_no_unreported_collision_occupant": assumptions,
        "remembered_terrain_or_unobservable_occupancy_cells": remembered,
        "predicted_reaches_target_under_assumptions": None if collision is None else
            target_index is not None and collision["dist"] >= target_index,
    }



def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--target", required=True, type=int)
    p.add_argument("--op", required=True, choices=("projectile", "magicbolt", "solidbeam"))
    p.add_argument("--source", type=int, help="Optional same-frame observed character source")
    add_response_argument(p)
    args = p.parse_args()
    snapshot = None
    try:
        snapshot = load_snapshot(args.response)
        model = source_model()
        f = snapshot.frame
        report = {**snapshot.context(), "kind": "read_only_geometry_prediction_not_game_reply",
                  "source_model": model[3],
                  "warnings": [
                      "No gameplay, intent, profile, save or audit access. This prediction proves no execution, hit, effect or save.",
                      "Unknown terrain is not filled in. Absent public collision occupants do not prove absence of invisible or disguised occupants.",
                      "Terrain collision uses only this snapshot's public descriptors, including presented custom decorations. An absent public source never proves absence of an unreported blocker; no private terrain is recovered.",
                      "Visited/mapped terrain and absent effects may omit off-screen changes. Explicit public environment collision overrides are partial: omitted flags retain the public terrain value. Conflicting or missing reviewed overrides stop prediction.",
                      "MAGIC_BOLT=6 is the base wand rule; BlastWave uses PROJECTILE=7. Other overrides, ranges and effect areas are not modeled.",
                      "SOLIDBEAM=4 is a supplied hypothetical geometric aim. It does not read or predict enemy targets, charge state, cooldowns or movement.",
                      "Yog's WONT_STOP piercing rays are not modeled by any of these three profiles.",
                      "Equal endpoints and boundary sources preserve native Ballistica fallback geometry; they do not establish a legal item action.",
                      "All current public occupants remain at their observed cells. Supplying a target does not relocate the hero there.",
                      "The full geometry line includes cells after collision; use the separate conditional travel prefix. Reobserve after every game action.",
                      "This supplied snapshot is analysis only; never substitute its revision for the live controller binding or replay an action.",
                  ]}
        hero = f.data.get("hero")
        if not isinstance(f.data.get("map"), dict) or not isinstance(hero, dict) or hero.get("cell") is None:
            snapshot.fail("analysis", "NO_MAP_OR_HERO", "No public game map or hero in this observation")
        report.update(depth=hero.get("depth"), hero_ready=hero.get("ready"))
        report.update(status="source_prediction", ray=predict(f.frame, args.target, args.op, model, args.source))
        emit(report)
        return 0
    except (SnapshotError, OSError, ValueError, KeyError, TypeError) as error:
        return report_error(error, snapshot)



if __name__ == "__main__":
    raise SystemExit(main())
