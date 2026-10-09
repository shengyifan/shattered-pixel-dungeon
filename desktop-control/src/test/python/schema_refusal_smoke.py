#!/usr/bin/env python3
"""Actual launcher rejects incompatible disposable profiles without changing them.

Every profile is an explicitly synthetic fixture. Older schema cases
intentionally contain only metadata. Game identity cases copy a
new accepted fixture profile, mutate only the named test metadata, then prove
rejection preserves every byte and timestamp. Actual run save/reopen is
separately exercised by controller_package_smoke.py.
"""
import argparse
from contextlib import closing
import json
import os
import shutil
from pathlib import Path
import sqlite3
import subprocess
import uuid


def snapshot(profile):
    return {str(path.relative_to(profile)): (path.stat().st_mtime_ns,
                                            path.read_bytes() if path.is_file() else None)
            for path in [profile, *profile.rglob("*")]}


def fixture(profile, schema=12):
    audit = profile / "audit"
    audit.mkdir(parents=True)
    for name in ("public", "internal"):
        metadata = {"schema_version": str(schema), "profile_id": "refusal-test",
                    "game_version": "4.0.2", "game_version_code": "922"}
        database = audit / (name + ".sqlite3")
        with closing(sqlite3.connect(database)) as connection, connection:
            connection.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            connection.executemany("INSERT INTO metadata VALUES(?,?)", metadata.items())
        Path(str(database) + "-wal").write_bytes(b"unchanged fixture WAL sentinel")
        Path(str(database) + "-shm").write_bytes(b"unchanged fixture SHM sentinel")
    (profile / "fixture-sentinel").write_bytes(b"The new CLI must not touch this incompatible profile")


def accepted_profile(launcher, output):
    from controller_package_smoke import ControllerClient, native_operation
    profile = output / "accepted-profile"
    for name in ("initial", "reopened"):
        client = ControllerClient(launcher, profile, output / ("accepted-" + name), dict(os.environ))
        try:
            client.state()
            native_operation(client, "quit")
            client.finish_exit()
        finally:
            client.cleanup()
    return profile


def corrupt_identity(profile, side, key, value):
    database = profile / "audit" / (side + ".sqlite3")
    with closing(sqlite3.connect(database)) as connection, connection:
        triggers = connection.execute("SELECT name,sql FROM sqlite_master WHERE type='trigger' "
                                      "AND name LIKE 'immutable_game_identity_%'").fetchall()
        assert len(triggers) == 3, triggers
        for name, _ in triggers:
            connection.execute('DROP TRIGGER "' + name + '"')
        if value is None:
            connection.execute("DELETE FROM metadata WHERE key=?", (key,))
        else:
            connection.execute("UPDATE metadata SET value=? WHERE key=?", (value, key))
        for _, sql in triggers:
            connection.execute(sql)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--launcher", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    output = root / "desktop-control/build/fixtures/packaging9.0" / ("profile-refusal-" + uuid.uuid4().hex)
    output.mkdir(parents=True)
    cases = []
    for version in range(1, 12):
        profile = output / ("schema-" + str(version))
        fixture(profile, schema=version)
        cases.append((profile, "AUDIT_SCHEMA_UNSUPPORTED"))
    accepted = accepted_profile(args.launcher.resolve(), output)
    for side in ("public", "internal"):
        for key, wrong in (("game_version", "3.3.8"), ("game_version_code", "896")):
            for value in (None, wrong):
                profile = output / (side + "-" + key + ("-missing" if value is None else "-mismatch"))
                shutil.copytree(accepted, profile)
                corrupt_identity(profile, side, key, value)
                cases.append((profile, "CLI_PROFILE_UNSUPPORTED"))
        profile = output / ("missing-" + side)
        shutil.copytree(accepted, profile)
        (profile / "audit" / (side + ".sqlite3")).unlink()
        cases.append((profile, "AUDIT_PAIR_MISSING"))
    ambiguous = output / "ambiguous-nonempty"
    ambiguous.mkdir()
    (ambiguous / "fixture-sentinel").write_bytes(b"Not a new empty profile")
    cases.append((ambiguous, "CLI_PROFILE_UNSUPPORTED"))
    results = []
    for profile, code in cases:
        before = snapshot(profile)
        process = subprocess.run([str(args.launcher.resolve()), "run", "--machine", "--no-terminal",
                                  "--data-dir", str(profile), "--trace-dir", str(output / "transport")],
                                 input=b'{"v":9,"id":"info","op":"info"}\n', capture_output=True, timeout=30)
        assert process.returncode != 0, process
        assert process.stdout == b"", process.stdout
        assert code.encode() in process.stderr, process.stderr
        assert snapshot(profile) == before, "Incompatible profile bytes, paths or timestamps changed"
        results.append({"case": profile.name, "error": code, "exit_code": process.returncode, "unchanged": True})
    result = {"test_fixture": True, "counts_as_win": False, "verified": True, "cases": results,
              "launcher": str(args.launcher.resolve()), "profile_root": str(output), "fresh_schema12_profile_reopened": True}
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
