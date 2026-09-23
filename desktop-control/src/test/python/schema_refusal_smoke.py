#!/usr/bin/env python3
"""Actual launcher refuses each older audit marker without changing a disposable profile."""
import argparse
import json
from pathlib import Path
import sqlite3
import subprocess
import uuid


def snapshot(profile):
    return {str(path.relative_to(profile)): (path.stat().st_mtime_ns,
                                            path.read_bytes() if path.is_file() else None)
            for path in [profile, *profile.rglob("*")]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--launcher", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    output = root / "desktop-control/build/fixtures/packaging8.0" / ("old-schema-" + uuid.uuid4().hex)
    output.mkdir(parents=True)
    cases = []
    for version in range(1, 11):
        profile = output / ("schema-" + str(version))
        audit = profile / "audit"
        audit.mkdir(parents=True)
        for name in ("public", "internal"):
            database = audit / (name + ".sqlite3")
            with sqlite3.connect(database) as connection:
                connection.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                connection.executemany("INSERT INTO metadata VALUES(?,?)",
                                       [("schema_version", str(version)), ("profile_id", "refusal-test")])
            connection.close()
            Path(str(database) + "-wal").write_bytes(b"unchanged fixture WAL sentinel")
            Path(str(database) + "-shm").write_bytes(b"unchanged fixture SHM sentinel")
        (profile / "fixture-sentinel").write_bytes(b"The new CLI must not touch this old profile")
        before = snapshot(profile)
        process = subprocess.run([str(args.launcher.resolve()), "run", "--machine", "--no-terminal",
                                  "--data-dir", str(profile), "--trace-dir", str(output / "transport")],
                                 input=b'{"v":8,"id":"info","op":"info"}\n', capture_output=True, timeout=30)
        assert process.returncode != 0, process
        assert process.stdout == b"", process.stdout
        assert b"AUDIT_SCHEMA_UNSUPPORTED" in process.stderr, process.stderr
        assert snapshot(profile) == before, "Old profile bytes, paths or timestamps changed"
        cases.append({"schema": version, "exit_code": process.returncode, "unchanged": True})
    result = {"test_fixture": True, "counts_as_win": False, "verified": True, "cases": cases,
              "launcher": str(args.launcher.resolve()), "profile_root": str(output)}
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
