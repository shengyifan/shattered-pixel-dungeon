#!/usr/bin/env python3
"""Real render/actor runtime cancellation, driven only through public NDJSON in a test fixture.

The fixture is a fresh profile and is not evidence of a legitimate game completion.
Private data is never used to choose an action or a target.
"""
import argparse
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid

from fixture_smoke import FixtureClient, reach_game


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["rest", "travel", "travel-audit-fail"], default="rest")
    mode = parser.parse_args().mode
    root = Path("desktop-control/build/fixtures").resolve()
    profile = root / ("cancel-" + mode + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    classpath = Path("desktop-control/build/test-runtime-classpath.txt").read_text().strip()
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    command = [java, "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "class:WARRIOR" if mode == "rest" else "ui:travel"]
    client = FixtureClient(command, profile)
    report = {"test_fixture": True, "counts_as_win": False, "profile": str(profile)}
    try:
        hello = client.request("protocol.info")
        assert hello["ok"], hello
        before = reach_game(client, "WARRIOR")
        origin = before["observation"]["hero"]["cell"]
        target = None
        if mode == "rest":
            assert any(a["action"] == "rest" for a in before["actions"]), before["actions"]
            command_args = {"action": "rest"}
        else:
            grid = before["observation"]["map"]
            own_tile = next(tile for tile in grid["cells"] if tile["cell"] == origin)
            corridor = [tile for tile in grid["cells"] if tile["y"] == own_tile["y"] and tile["terrain"] == own_tile["terrain"]]
            destination = max(corridor, key=lambda tile: tile["x"])
            assert destination["x"] - own_tile["x"] >= 12, corridor
            target = destination["cell"]
            command_args = {"action": "cell.select", "cell": target}
        original = mode + "-" + uuid.uuid4().hex
        started = time.monotonic()
        first = client.request("action.execute", command_args, request_id=original)
        first_wire = json.loads(client.last_recv_bytes)
        elapsed = time.monotonic() - started
        assert first.get("ok") and first.get("status") == "in_progress", first
        activity = first["result"]
        assert activity["phase"] == "continuous_activity", activity
        token = activity["state_version"]
        assert token.startswith("activity:"), token
        assert any(a["action"] == "action.cancel" and a["target_id"] == original for a in activity["actions"])
        assert activity["observation"]["continuous_activity"]["kind"] == ("rest" if mode == "rest" else "travel")
        cancel_id = "cancel-" + uuid.uuid4().hex
        if mode == "travel-audit-fail":
            with sqlite3.connect(profile / "audit/internal.sqlite3") as db:
                db.execute("CREATE TRIGGER reject_test_cancel BEFORE UPDATE ON requests WHEN OLD.id='" + cancel_id + "' AND NEW.status='EXECUTING' BEGIN SELECT RAISE(ABORT,'fixture audit failure'); END")
        cancelled = client.request("action.execute", {"action": "action.cancel", "target_id": original}, request_id=cancel_id, version=token)
        if mode == "travel-audit-fail":
            assert cancelled.get("error", {}).get("code") == "AUDIT_UNAVAILABLE", cancelled
            client.process.wait(timeout=25)
            with sqlite3.connect(profile / "audit/public.sqlite3") as db:
                original_status = db.execute("SELECT status FROM requests WHERE scope_id=? AND id=?", (first["scope_id"], original)).fetchone()[0]
                rejected_status = db.execute("SELECT status FROM requests WHERE scope_id=? AND id=?", (first["scope_id"], cancel_id)).fetchone()[0]
            assert original_status != "INTERRUPTED", original_status
            assert rejected_status != "COMPLETED", rejected_status
            report.update(mode=mode, verified=True, original_status=original_status, cancel_status=rejected_status,
                          first_response_seconds=round(elapsed, 3), failure="AUDIT_UNAVAILABLE")
            (profile / "cancellation-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(json.dumps(report, ensure_ascii=False))
            return
        assert cancelled.get("ok") and cancelled.get("status") == "completed", cancelled
        record = client.request("request.get", {"target_id": original, "get": ["reply"]}, scope=first["scope_id"])
        assert record["result"]["status"] == "INTERRUPTED", record
        assert record["result"]["response"]["status"] == "interrupted", record
        after = client.state()
        assert after["phase"] == "player_ready", after
        assert after["observation"]["hero"]["hp"] > 0, after["observation"]["hero"]
        assert not any(a["action"] == "action.cancel" for a in after["actions"])
        if target is not None:
            stopped = after["observation"]["hero"]["cell"]
            cancellation = client.request("request.get", {"target_id": cancel_id, "get": ["before"]}, scope=first["scope_id"])["result"]
            prepared = cancellation["before_snapshot"]["hero"]["cell"]
            assert origin < stopped < target, {"origin": origin, "stopped": stopped, "target": target}
            assert stopped == prepared, {"prepared": prepared, "stopped": stopped}
            repeated = client.request("action.execute", {"action": "action.cancel", "target_id": original}, request_id=cancel_id, version=token)
            assert repeated.get("error", {}).get("code") == "DUPLICATE_REQUEST_ID", repeated
            expired = client.request("action.execute", {"action": "action.cancel", "target_id": original}, version=token)
            assert expired.get("error", {}).get("code") == "ACTIVITY_NOT_ACTIVE", expired
            report.update(mode=mode, origin=origin, destination=target, stopped=stopped, prepared_before_cancel=prepared,
                          duplicate_rejected=True, expired_rejected=True)
        assert elapsed < 15, {"first_response_seconds": elapsed}
        report.update(first_response_seconds=round(elapsed, 3), original_status="INTERRUPTED",
                      cancel_status=cancelled["status"], hp_after=after["observation"]["hero"]["hp"], verified=True)
        client.finish()
        with sqlite3.connect(profile / "audit/public.sqlite3") as db:
            rows = db.execute("SELECT response_json FROM exchanges WHERE scope_id=? AND id=?", (first["scope_id"], original)).fetchall()
            assert len(rows) == 1 and json.loads(rows[0][0]) == first_wire, rows
        (profile / "cancellation-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps(report, ensure_ascii=False))
    finally:
        if client.process.poll() is None:
            client.process.stdin.close()
            try:
                client.process.wait(timeout=40)
            except Exception:
                client.process.terminate()
                client.process.wait(timeout=10)
        client.stderr.close()
        client.trace.close()


if __name__ == "__main__":
    main()
