#!/usr/bin/env python3
"""Offline development diagnosis, restricted to failed test profiles in one frozen runtime.

These internal exception details never choose a game action or become a public DTO.
The generated diagnostic file remains in build/fixtures, outside release artifacts.
"""
import argparse
import json
from pathlib import Path
import re
import sqlite3


def checked(path, boundary):
    """Reject links before resolving; do not silently bless a link's outside target."""
    path, boundary = Path(path), Path(boundary)
    try:
        relative = path.relative_to(boundary)
    except ValueError as error:
        raise ValueError("Test evidence path is outside its allowed directory") from error
    current = boundary
    for part in relative.parts:
        if part in {".", ".."}:
            raise ValueError("Test evidence path contains traversal")
        current = current / part
        if current.is_symlink():
            raise ValueError("Test evidence may not be a symbolic link")
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(boundary):
        raise ValueError("Resolved test evidence path is outside its allowed directory")
    return resolved


def collect(root, runtime_id):
    root = Path(root).resolve(strict=True)
    if not re.fullmatch(r"runtime-[a-f0-9]{32}", runtime_id):
        raise ValueError("Invalid frozen test runtime identifier")
    directory = checked(root / "desktop-control/build/fixtures", root)
    runtime = checked(directory / runtime_id, directory)
    checked(runtime / "test-runtime.json", directory)
    # Only a completed runtime manifest establishes membership. Never enumerate
    # other profiles or open their reports to discover which run they belong to.
    manifest = json.loads(checked(runtime / "results.json", directory).read_text())
    if manifest.get("runtime_id") != runtime_id or manifest.get("test_fixture") is not True or manifest.get("counts_as_win") is not False:
        raise ValueError("Mismatched completed test runtime manifest")
    results = []
    for listed in manifest["results"]:
        if listed.get("runtime_id") != runtime_id:
            raise ValueError("Manifest contains a different runtime")
        profile = Path(listed["profile"])
        if not profile.is_absolute():
            profile = root / profile
        profile = checked(profile, directory)
        if profile.parent != directory:
            raise ValueError("Profile is not an immediate test fixture directory")
        path = checked(profile / "fixture-result.json", directory)
        report = json.loads(path.read_text())
        if report.get("runtime_id") != runtime_id:
            raise ValueError("Fixture report does not belong to the requested runtime")
        assert report["test_fixture"] is True and report["counts_as_win"] is False
        row = {"case": report["fixture"], "passed": report["ok"], "profile": str(path.parent.relative_to(root))}
        if not report["ok"]:
            row["error"] = report.get("error")
            database = path.parent / "audit/internal.sqlite3"
            if database.exists() or database.is_symlink():
                database = checked(database, directory)
                for suffix in ("-wal", "-shm", "-journal"):
                    sidecar = Path(str(database) + suffix)
                    if sidecar.exists() or sidecar.is_symlink():
                        checked(sidecar, directory)
                with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as connection:
                    # A legitimate earlier STALE_STATE may precede the displayed-text
                    # failure. This is an explicitly labeled sample, not a claim that
                    # the first exception in the database caused the failed request.
                    errors = connection.execute("SELECT id,exception_class,stack_trace FROM exceptions "
                                                "ORDER BY CASE WHEN exception_class LIKE '%PublicTextUnavailableException' "
                                                "THEN 0 ELSE 1 END, sequence LIMIT 1").fetchall()
                if errors:
                    request_id, exception_class, trace = errors[0]
                    row.update(request_id=request_id, exception_class=exception_class,
                               exception_selection="first_translation_failure_else_first_exception")
                    if "Private displayed text: " in trace:
                        displayed = trace.split("Private displayed text: ", 1)[1]
                        if "\nTranslation diagnostic: " in displayed:
                            displayed, diagnostic = displayed.rsplit("\nTranslation diagnostic: ", 1)
                            row["diagnostic"] = diagnostic
                        row["displayed_text"] = displayed
        results.append(row)
    results.sort(key=lambda row: row["case"])
    summary = {"test_fixture": True, "internal_assertion_only": True, "counts_as_win": False,
               "runtime_id": runtime_id, "completed": len(results), "passed": sum(row["passed"] for row in results),
               "failures": [row for row in results if not row["passed"]]}
    destination = runtime / "failure-diagnostics.json"
    if destination.exists() or destination.is_symlink():
        checked(destination, directory)
    destination.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("runtime_id")
    args = parser.parse_args()
    collect(Path(__file__).resolve().parents[4], args.runtime_id)
