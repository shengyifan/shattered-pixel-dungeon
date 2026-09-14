#!/usr/bin/env python3
"""P8 legacy identity and save-failure checks in newly created fixture profiles.

All game choices use public NDJSON. The only save edits remove run_uuid from a
closed test save or create/remove an empty .spdtmp directory to inject IO failure.
Private audit reads are assertions after execution, never inputs to game choices.
"""
import argparse
import gzip
import json
import os
from pathlib import Path
from client_result import settle_action
import shutil
import sqlite3
import time
import uuid

from fixture_smoke import FixtureClient, freeze_runtime, reach_game as start_fixture
from machine_smoke import reach_game as resume_game


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def safe_profile(root, name):
    directory = root / "desktop-control/build/fixtures" / (name + "-" + uuid.uuid4().hex)
    directory.mkdir(parents=True)
    assert directory.resolve().is_relative_to((root / "desktop-control/build/fixtures").resolve())
    return directory.resolve()


def metadata(profile, fixture, runtime_id, **extra):
    write_json(profile / "test_fixture.json", dict(test_fixture=True, counts_as_win=False,
               fixture=fixture, runtime_id=runtime_id, **extra))


def launch_command(classpath, fixture=False):
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    command = [java, "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath]
    return command + (["com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher",
                       "--fixture", "class:WARRIOR"] if fixture else
                      ["com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"])


def stop(client, uncertain=False):
    """Use normal app.quit when possible; EOF is the protocol's shutdown path after UNKNOWN."""
    try:
        if client.process.poll() is None:
            if uncertain:
                client.process.stdin.close()
                client.process.wait(timeout=40)
            else:
                client.finish()
    finally:
        if client.process.poll() is None:
            client.process.terminate()
            client.process.wait(timeout=10)
        client.stderr.close()
        client.trace.close()


def save_file(profile):
    files = list(profile.glob("game*/game.dat"))
    assert len(files) == 1, {"expected_one_test_save": [str(p) for p in files]}
    return files[0]


def save_json(path):
    encoded = path.read_bytes()
    assert encoded[:2] == b"\x1f\x8b", "Fixture game save must use the current gzip JSON format"
    return json.loads(gzip.decompress(encoded))


def audit_schema(profile):
    for name in ("public", "internal"):
        with sqlite3.connect(f"file:{profile / 'audit' / (name + '.sqlite3')}?mode=ro", uri=True) as db:
            assert db.execute("SELECT value FROM metadata WHERE key='schema_version'").fetchone() == ("7",)
            assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)


def execute(client, action, request_id=None):
    """Explicit save/failure audit verification, including original reply details."""
    for attempt in range(8):
        state = client.state()
        assert any(a.get("action") == action for a in state["actions"]), state["actions"]
        chosen_id = request_id or ("p8-" + uuid.uuid4().hex)
        response = client.request("action.execute", {"action": action}, request_id=chosen_id)
        if response.get("error", {}).get("code") == "STALE_STATE":
            request_id = None
            continue
        settled = settle_action(client, response, action)
        if response.get("status") == "in_progress":
            # These scenarios assert exact historical save/failure payloads.
            # General clients use settled.outcome plus settled.observation instead.
            terminal_failure = (settled.receipt_response is not None
                                and settled.receipt_response.get("ok")
                                and settled.outcome.get("status") in {"REJECTED", "UNKNOWN"})
            if not settled.ok and not terminal_failure:
                settled.require_success()
            record = client.request("request.get", {"target_id": chosen_id, "get": ["reply"]}, scope=settled.scope_id)
            assert record["ok"], record
            response = record["result"]["response"]
            if terminal_failure:
                assert not response.get("ok"), {"terminal_failure_changed_to_success": settled, "response": response}
        return state, chosen_id, response
    raise AssertionError("P8 action never reached a fresh stable version")


def create_template(root, classpath, runtime_id):
    profile = safe_profile(root, "p8-template")
    client = FixtureClient(launch_command(classpath, fixture=True), profile)
    try:
        assert client.request("protocol.info")["ok"]
        state = start_fixture(client, "WARRIOR")
        _, _, response = execute(client, "game.save")
        assert response["ok"], response
        initial_scope = state["scope_id"]
        assert initial_scope == "run:" + save_json(save_file(profile))["run_uuid"]
    finally:
        stop(client)
    metadata(profile, "p8:template", runtime_id, game_state_modified_by_fixture=False,
             setup="Fresh class:WARRIOR; test preferences/unlocks only; public start and game.save")
    audit_schema(profile)
    return profile


def copy_template(root, template, case, runtime_id):
    profile = safe_profile(root, "p8-" + case)
    for source in template.iterdir():
        if source.is_dir() and source.name.startswith("game"):
            shutil.copytree(source, profile / source.name)
        elif source.is_file() and source.name in {"settings.xml", "badges.dat"}:
            shutil.copy2(source, profile / source.name)
    assert save_file(profile).is_file()
    metadata(profile, "p8:" + case, runtime_id, source_profile=str(template.relative_to(root)))
    return profile


def open_saved(classpath, profile):
    client = FixtureClient(launch_command(classpath), profile)
    assert client.request("protocol.info")["ok"]
    return client


def legacy_case(root, template, profile, classpath, runtime_id):
    game = save_file(profile)
    before = save_json(game)
    old_uuid = before["run_uuid"]
    legacy = {key: value for key, value in before.items() if key != "run_uuid"}
    game.write_bytes(gzip.compress(json.dumps(legacy, ensure_ascii=False, separators=(",", ":")).encode(), mtime=0))
    assert save_json(game) == legacy and "run_uuid" not in save_json(game)
    metadata(profile, "p8:legacy", runtime_id, source_profile=str(template.relative_to(root)),
             save_mutation="Only removed top-level run_uuid from gzip JSON; all other parsed values identical")
    client = open_saved(classpath, profile)
    try:
        first = resume_game(client, resume=True)
        assigned = save_json(game).get("run_uuid")
        assert assigned and str(uuid.UUID(assigned)) == assigned and assigned != old_uuid
        assert first["scope_id"] == "run:" + assigned
        # Persistence is checked now, before explicit game.save or app.quit can mask it.
        first_scope = first["scope_id"]
        claim_id = "legacy-run-id-" + uuid.uuid4().hex
        assert client.request("state.get", request_id=claim_id)["ok"]
    finally:
        stop(client)
    client = open_saved(classpath, profile)
    try:
        second = resume_game(client, resume=True)
        assert second["scope_id"] == first_scope
        assert save_json(game)["run_uuid"] == assigned
        duplicate = client.request("state.get", request_id=claim_id)
        assert duplicate.get("error", {}).get("code") == "DUPLICATE_REQUEST_ID", duplicate
    finally:
        stop(client)
    audit_schema(profile)
    return dict(case="legacy", ok=True, profile=str(profile.relative_to(root)),
                assigned_before_explicit_save_or_quit=True, valid_new_uuid=True,
                restart_scope_unchanged=True, request_id_still_occupied_after_restart=True)


def save_failure_case(root, template, profile, classpath, runtime_id, second_file):
    case = "save-depth" if second_file else "save-game"
    game = save_file(profile)
    depth = game.with_name("depth1.dat")
    assert depth.is_file(), "Template must have the actual first-floor file"
    blocked = (depth if second_file else game).with_name((depth if second_file else game).name + ".spdtmp")
    client = open_saved(classpath, profile)
    uncertain = False
    try:
        state = resume_game(client, resume=True)
        assert state["observation"]["hero"]["depth"] == 1
        # An actual public turn makes the later first-file write observably different.
        _, _, waited = execute(client, "wait")
        assert waited["ok"], waited
        events_before = client.request("events.read", {"limit": 100})
        assert events_before["ok"], events_before
        after_sequence = max((event["sequence"] for event in events_before["result"]), default=0)
        game_before, depth_before = game.read_bytes(), depth.read_bytes()
        blocked.mkdir()
        metadata(profile, "p8:" + case, runtime_id, source_profile=str(template.relative_to(root)),
                 save_mutation="Empty directory blocks existing file temp write", blocked_path=str(blocked.relative_to(profile)))
        state, failed_id, response = execute(client, "game.save", "save-fails-" + uuid.uuid4().hex)
        uncertain = True
        assert not response.get("ok"), {"save_was_falsely_reported_successful": response}
        assert response.get("error", {}).get("code") == "EXECUTION_UNKNOWN", response
        duplicate = client.request("action.execute", {"action": "game.save"}, request_id=failed_id, version=state["state_version"])
        assert duplicate.get("error", {}).get("code") == "DUPLICATE_REQUEST_ID", duplicate
        recorded = client.request("request.get", {"target_id": failed_id, "get": ["reply"]})
        assert recorded["ok"] and recorded["result"]["status"] == "UNKNOWN", recorded
        assert not recorded["result"]["response"].get("ok"), recorded
        stopped = client.state()
        assert stopped["phase"] == "execution_unknown" and stopped["actions"] == [], stopped
        refused = client.request("action.execute", {"action": "wait"}, version=state["state_version"])
        assert refused.get("error", {}).get("code") == "EXECUTION_UNCERTAIN", refused
        events = client.request("events.read", {"after": after_sequence, "limit": 100})
        assert events["ok"], events
        failed_events = [event for event in events["result"] if event["kind"] in {"save", "save.failed"} and event["data"].get("success") is False]
        assert failed_events, {"no_public_save_failure": events}
        assert not any(event["kind"] in {"save", "save.completed"} and event["data"].get("success") is True for event in events["result"])
        with sqlite3.connect(f"file:{profile / 'audit/internal.sqlite3'}?mode=ro", uri=True) as db:
            exceptions = db.execute("SELECT exception_class,stack_trace FROM exceptions WHERE scope_id=? AND id=?", (state["scope_id"], failed_id)).fetchall()
            assert exceptions and any(blocked.name in stack and "IOException" in stack for _, stack in exceptions)
        for event in failed_events:
            assert "stack_trace" not in event["data"] and "message" not in event["data"]
        game_changed = game.read_bytes() != game_before
        depth_changed = depth.read_bytes() != depth_before
        assert not depth_changed, "Blocked or unattempted second file must remain the previous bytes"
        assert game_changed == second_file, {"first_file_changed": game_changed, "second_file_blocked": second_file}
        audit_schema(profile)
        report = dict(case=case, ok=True, profile=str(profile.relative_to(root)),
                      error_code=response["error"]["code"], request_status="UNKNOWN", duplicate_rejected=True,
                      public_failure_event_kinds=[event["kind"] for event in failed_events], private_io_exception_recorded=True,
                      first_game_file_changed=game_changed, second_depth_file_changed=depth_changed,
                      further_controlled_action_rejected="EXECUTION_UNCERTAIN", two_file_atomicity_claimed=False)
    finally:
        if blocked.exists():
            blocked.rmdir()  # only the empty directory this test created
        stop(client, uncertain=uncertain)
    client = open_saved(classpath, profile)
    try:
        restored = resume_game(client, resume=True)
        assert restored["scope_id"] == state["scope_id"]
        assert restored["state_version"] != state["state_version"]
        historic = client.request("request.get", {"target_id": failed_id, "get": ["reply"]})
        assert historic["result"]["status"] == "UNKNOWN", historic
        duplicate = client.request("action.execute", {"action": "game.save"}, request_id=failed_id)
        assert duplicate.get("error", {}).get("code") == "DUPLICATE_REQUEST_ID", duplicate
        _, recovered_id, recovered = execute(client, "game.save")
        assert recovered["ok"] and recovered_id != failed_id, recovered
        recovery_record = client.request("request.get", {"target_id": recovered_id})
        assert recovery_record["result"]["status"] == "COMPLETED", recovery_record
        report.update(restart_scope_unchanged=True, historic_failed_id_remains_unknown=True,
                      historic_failed_id_still_occupied=True, new_id_save_succeeds_after_restart=True)
    finally:
        stop(client)
    audit_schema(profile)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="legacy,save-game,save-depth")
    args = parser.parse_args()
    cases = args.cases.split(",")
    assert all(case in {"legacy", "save-game", "save-depth"} for case in cases)
    root = Path(__file__).resolve().parents[4]
    classpath = (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip()
    classpath, runtime_id = freeze_runtime(root, classpath)
    template = create_template(root, classpath, runtime_id)
    results = []
    for case in cases:
        profile = copy_template(root, template, case, runtime_id)
        try:
            result = legacy_case(root, template, profile, classpath, runtime_id) if case == "legacy" else save_failure_case(root, template, profile, classpath, runtime_id, case == "save-depth")
        except Exception as error:
            result = dict(case=case, ok=False, profile=str(profile.relative_to(root)), error=str(error))
        results.append(result)
        write_json(profile / "legacy-save-result.json", dict(test_fixture=True, counts_as_win=False, runtime_id=runtime_id, **result))
        print(json.dumps(dict(test_fixture=True, counts_as_win=False, runtime_id=runtime_id, **result), ensure_ascii=False), flush=True)
    summary = dict(test_fixture=True, counts_as_win=False, runtime_id=runtime_id,
                   template=str(template.relative_to(root)), total=len(results), passed=sum(result["ok"] for result in results), results=results)
    write_json(root / "desktop-control/build/fixtures" / runtime_id / "legacy-save-results.json", summary)
    if not all(result["ok"] for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
