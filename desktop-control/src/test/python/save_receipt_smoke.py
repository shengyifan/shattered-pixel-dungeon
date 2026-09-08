#!/usr/bin/env python3
"""Real native save callbacks and immutable public receipts using only NDJSON in a fresh profile."""
import json
import os
from pathlib import Path
import time
import uuid

from fixture_smoke import FixtureClient, act, freeze_runtime, reach_game


def main():
    root = Path.cwd()
    profile = root / "desktop-control/build/fixtures" / ("save-receipts-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    command = [java, "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"]
    client = FixtureClient(command, profile)
    try:
        hello = client.request("protocol.info")
        assert hello["ok"], hello
        menu = hello["result"]["menu_scope_id"]
        ready = reach_game(client, "WARRIOR")
        # A fresh standard game can still be showing its introductory story; close it via its
        # normal described back control before asking to open inventory or save.
        for _ in range(5):
            if ready["phase"] != "awaiting_input":
                break
            ready = act(client, "ui.back")
        assert ready["phase"] == "player_ready", ready
        run = ready["scope_id"]
        initial = ready.get("last_save")
        if initial is None:
            initial = client.state().get("last_save")
        assert initial and initial["success"], initial
        assert initial["scope_id"] == run
        assert initial["origin_scope_id"] == menu and initial["origin_request_id"], initial
        initial_record = client.request("request.get", {"target_id": initial["origin_request_id"]}, scope=menu)
        initial_receipts = initial_record["result"]["response"]["result"]["persistence"]["saves_during_request"]
        assert any(r["receipt_id"] == initial["receipt_id"] for r in initial_receipts), initial_record

        client.state()
        saved = client.request("action.execute", {"action": "game.save"}, request_id="explicit-save")
        assert saved.get("ok"), saved
        receipt = saved["result"]["persistence"]["last_save"]
        during = saved["result"]["persistence"]["saves_during_request"]
        assert len(during) == 1 and receipt == during[0], saved
        assert receipt["origin_scope_id"] == run and receipt["origin_request_id"] == "explicit-save"
        assert receipt["success"] is True and receipt["receipt_id"] != initial["receipt_id"]
        assert "error" not in receipt and "message" not in receipt
        state = client.state()
        version = state["state_version"]
        assert state["last_save"] == receipt
        actions = client.request("actions.list")
        assert actions["result"]["last_save"] == receipt
        assert actions["result"]["state_version"] == version
        if any(a["action"] == "inventory.open" for a in state["actions"]):
            item = next(item for item in state["observation"]["inventory"] if item.get("available", True))
            unsaved_action = {"action": "inventory.open", "locator": item["locator"]}
        else:
            # The untouched first-run tutorial may disable inventory. Use another actually
            # described UI operation instead of bypassing that native input gate.
            zoom = next(a for a in state["actions"] if a["action"] == "view.zoom")
            unsaved_action = {"action": "view.zoom", "zoom": zoom["minimum"]}
        opened = client.request("action.execute", unsaved_action, request_id="unsaved-ui")
        assert opened.get("ok"), opened
        assert opened["result"]["persistence"] == {"last_save": receipt, "saves_during_request": []}
        if unsaved_action["action"] == "inventory.open":
            act(client, "ui.back")
        second = client.request("action.execute", {"action": "game.save"}, request_id="later-save")
        assert second.get("ok"), second
        assert second["result"]["persistence"]["last_save"]["receipt_id"] != receipt["receipt_id"]
        original = client.request("request.get", {"target_id": "explicit-save"})
        assert original["result"]["response"] == saved, original
        unchanged = client.request("request.get", {"target_id": "unsaved-ui"})
        assert unchanged["result"]["response"] == opened, unchanged
        before_eof = client.state()["last_save"]
        client.process.stdin.close()
        client.process.wait(timeout=40)
        assert client.process.returncode == 0, client.process.returncode
        # The second connection reads public history only; it cannot make EOF look saved retroactively.
        restarted = FixtureClient(command, profile)
        try:
            assert restarted.request("protocol.info")["ok"]
            events = restarted.request("events.read", {"after": 0, "limit": 100}, scope=run)
            eof_saves = [e["data"] for e in events["result"] if e["kind"] == "save"
                         and e["data"]["receipt_id"] != before_eof["receipt_id"]
                         and e["data"]["origin_request_id"] is None and e["data"]["success"]]
            assert eof_saves, events
            record = restarted.request("request.get", {"target_id": "explicit-save"}, scope=run)
            assert record["result"]["response"] == saved, record
            restarted.finish()
        finally:
            if restarted.process.poll() is None:
                restarted.process.terminate(); restarted.process.wait(timeout=10)
            restarted.stderr.close(); restarted.trace.close()
        report = {"verified": True, "counts_as_win": False, "isolated_profile": str(profile), "runtime_id": runtime_id,
                  "initial_menu_origin_verified": True, "explicit_save_receipt": receipt,
                  "unsaved_operation": unsaved_action["action"], "unsaved_operation_empty_receipts": True, "query_did_not_change_version": True,
                  "history_receipts_immutable_across_later_save_and_restart": True, "eof_native_save_verified": True}
        (profile / "save-receipt-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False))
    finally:
        if client.process.poll() is None:
            try:
                client.process.stdin.close(); client.process.wait(timeout=40)
            except Exception:
                client.process.terminate(); client.process.wait(timeout=10)
        client.stderr.close(); client.trace.close()


if __name__ == "__main__":
    main()
