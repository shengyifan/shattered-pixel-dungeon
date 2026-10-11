#!/usr/bin/env python3
"""Real native save callbacks and immutable public receipts using only NDJSON in a fresh profile."""
import json
from protocol9 import response as decode_response
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
        hello = client.request('info')
        assert ("err" not in hello), hello
        menu = hello["data"]["menu_scope_id"]
        ready = reach_game(client, "WARRIOR")
        # A fresh standard game can still be showing its introductory story; close it via its
        # normal described back control before asking to open inventory or save.
        for _ in range(5):
            if ready["phase"] != "awaiting_input":
                break
            ready = act(client, 'back')
        assert ready["phase"] == "player_ready", ready
        run = ready['s']
        initial = ready.get('saved')
        if initial is None:
            initial = client.state().get('saved')
        assert initial and initial["success"], initial
        assert initial['s'] == run
        assert initial['src_s'] == menu and initial['src_id'], initial
        initial_record = client.request('req', {'rid': initial['src_id'], "get": ["reply"]}, scope=menu)
        initial_receipts = decode_response(initial_record["data"]["reply"])["data"]["persistence"]['saves']
        assert any(r['sid'] == initial['sid'] for r in initial_receipts), initial_record

        client.state()
        saved = client.request_action({'op': 'save'}, request_id="explicit-save")
        assert ("err" not in saved), saved
        receipt = saved["data"]["persistence"]['saved']
        during = saved["data"]["persistence"]['saves']
        assert len(during) == 1 and receipt == during[0], saved
        assert receipt['src_s'] == run and receipt['src_id'] == "explicit-save"
        assert receipt["success"] is True and receipt['sid'] != initial['sid']
        assert "error" not in receipt and "message" not in receipt
        state = client.state()
        version = state['rev']
        assert state['saved'] == receipt
        actions = client.request('actions')
        assert actions["data"]['saved'] == receipt
        assert actions['rev'] == version
        if any(a['op'] == 'item' for a in state['acts']):
            item = next(item for item in state['inv'] if item.get("available", True))
            unsaved_action = {'op': 'item', 'loc': item['loc']}
        else:
            # The untouched first-run tutorial may disable inventory. Use another actually
            # described UI operation instead of bypassing that native input gate.
            zoom = next(a for a in state['acts'] if a['op'] == 'zoom')
            unsaved_action = {'op': 'zoom', "zoom": zoom['min']}
        opened = client.request_action(unsaved_action, request_id="unsaved-ui")
        assert ("err" not in opened), opened
        assert opened["data"]["persistence"] == {'saved': receipt, 'saves': []}
        if unsaved_action['op'] == 'item':
            act(client, 'back')
        second = client.request_action({'op': 'save'}, request_id="later-save")
        assert ("err" not in second), second
        assert second["data"]["persistence"]['saved']['sid'] != receipt['sid']
        original = client.request('req', {'rid': "explicit-save", "get": ["reply"]})
        assert decode_response(original["data"]["reply"]) == saved, original
        unchanged = client.request('req', {'rid': "unsaved-ui", "get": ["reply"]})
        assert decode_response(unchanged["data"]["reply"]) == opened, unchanged
        before_eof = client.state()['saved']
        client.process.stdin.close()
        client.process.wait(timeout=40)
        assert client.process.returncode == 0, client.process.returncode
        # The second connection reads public history only; it cannot make EOF look saved retroactively.
        restarted = FixtureClient(command, profile)
        try:
            assert ("err" not in restarted.request('info'))
            events = restarted.request('events', {"after": 0, "limit": 100}, scope=run)
            eof_saves = [e["data"] for e in events["data"]["items"] if e["kind"] == "save"
                         and e["data"]['sid'] != before_eof['sid']
                         and e["data"]['src_id'] is None and e["data"]["success"]]
            assert eof_saves, events
            record = restarted.request('req', {'rid': "explicit-save", "get": ["reply"]}, scope=run)
            assert decode_response(record["data"]["reply"]) == saved, record
            restarted.finish()
        finally:
            if restarted.process.poll() is None:
                restarted.process.terminate(); restarted.process.wait(timeout=10)
            restarted.stderr.close(); restarted.trace.close()
        report = {"verified": True, "counts_as_win": False, "isolated_profile": str(profile), "runtime_id": runtime_id,
                  "initial_menu_origin_verified": True, "explicit_save_receipt": receipt,
                  "unsaved_operation": unsaved_action['op'], "unsaved_operation_empty_receipts": True, "query_did_not_change_version": True,
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
