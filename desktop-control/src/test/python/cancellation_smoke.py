#!/usr/bin/env python3
"""Real render/actor runtime cancellation, driven only through public NDJSON in a test fixture.

The fixture is a fresh profile and is not evidence of a legitimate game completion.
Private data is never used to choose an action or a target.
"""
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid

from fixture_smoke import FixtureClient, reach_game


def main():
    root = Path("desktop-control/build/fixtures").resolve()
    profile = root / ("cancel-rest-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    classpath = Path("desktop-control/build/test-runtime-classpath.txt").read_text().strip()
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    command = [java, "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "class:WARRIOR"]
    client = FixtureClient(command, profile)
    report = {"test_fixture": True, "counts_as_win": False, "profile": str(profile)}
    try:
        hello = client.request("protocol.info")
        assert hello["ok"], hello
        before = reach_game(client, "WARRIOR")
        assert any(a["action"] == "rest" for a in before["actions"]), before["actions"]
        original = "rest-" + uuid.uuid4().hex
        started = time.monotonic()
        first = client.request("action.execute", {"action": "rest"}, request_id=original)
        elapsed = time.monotonic() - started
        assert first.get("ok") and first.get("status") == "in_progress", first
        activity = first["result"]
        assert activity["phase"] == "continuous_activity", activity
        token = activity["state_version"]
        assert token.startswith("activity:"), token
        assert any(a["action"] == "action.cancel" and a["target_id"] == original for a in activity["actions"])
        cancelled = client.request("action.execute", {"action": "action.cancel", "target_id": original}, version=token)
        assert cancelled.get("ok") and cancelled.get("status") == "completed", cancelled
        record = client.request("request.get", {"target_id": original}, scope=first["scope_id"])
        assert record["result"]["status"] == "INTERRUPTED", record
        assert record["result"]["response"]["status"] == "interrupted", record
        after = client.state()
        assert after["phase"] == "player_ready", after
        assert after["observation"]["hero"]["hp"] > 0, after["observation"]["hero"]
        assert not any(a["action"] == "action.cancel" for a in after["actions"])
        assert elapsed < 15, {"first_response_seconds": elapsed}
        report.update(first_response_seconds=round(elapsed, 3), original_status="INTERRUPTED",
                      cancel_status=cancelled["status"], hp_after=after["observation"]["hero"]["hp"], verified=True)
        client.finish()
        with sqlite3.connect(profile / "audit/public.sqlite3") as db:
            rows = db.execute("SELECT response_json FROM exchanges WHERE scope_id=? AND id=?", (first["scope_id"], original)).fetchall()
            assert len(rows) == 1 and json.loads(rows[0][0]) == first, rows
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
