#!/usr/bin/env python3
"""Save-failure checks in closed-and-copied current schema-12 fixture profiles.

Game choices use public NDJSON. An empty .spdtmp directory injects each IO failure.
Private fixture audit reads are post-execution assertions, never action inputs.
"""
import argparse
import json
from protocol9 import response as decode_response
from pathlib import Path
import sqlite3
import uuid

from client_result import settle_action
from fixture_identity import fixture_canonical_identity
from fixture_smoke import FixtureClient, freeze_runtime, reach_game as start_fixture
from fixture_support import (audit_schema, copy_audited_profile, launch_command, metadata,
                             safe_profile, save_file, save_json, stop, write_json)
from machine_smoke import reach_game as resume_game


def execute(client, action, request_id=None):
    """Explicit save/failure audit verification, including original reply details."""
    for attempt in range(8):
        state = client.state()
        assert any(a.get('op') == action for a in state['acts']), state['acts']
        chosen_id = request_id or ("save-failure-" + uuid.uuid4().hex)
        response = client.request_action({'op': action}, request_id=chosen_id)
        if response.get("err") == "STALE_STATE":
            request_id = None
            continue
        settled = settle_action(client, response, action)
        if response.get("st") == "in_progress":
            # These scenarios assert exact historical save/failure payloads.
            # General clients use settled.outcome plus settled.observation instead.
            terminal_failure = (settled.receipt_response is not None
                                and "err" not in settled.receipt_response
                                and settled.outcome.get("st") in {"REJECTED", "UNKNOWN"})
            if not settled.ok and not terminal_failure:
                settled.require_success()
            record = client.request('req', {'rid': chosen_id, "get": ["reply"]}, scope=settled.scope_id)
            assert ("err" not in record), record
            response = decode_response(record["data"]["reply"])
            if terminal_failure:
                assert ("err" in response), {"terminal_failure_changed_to_success": settled, "response": response}
        return state, chosen_id, response
    raise AssertionError("Save action never reached a fresh stable version")


def create_template(root, classpath, runtime_id):
    profile = safe_profile(root, "save-failure-template")
    client = FixtureClient(launch_command(classpath, fixture=True), profile)
    try:
        assert ("err" not in client.request('info'))
        state = start_fixture(client, "WARRIOR")
        _, _, response = execute(client, 'save')
        assert ("err" not in response), response
        initial_scope = state['s']
        assert fixture_canonical_identity(profile,"scope",initial_scope) == "run:" + save_json(save_file(profile))["run_uuid"]
    finally:
        stop(client)
    metadata(profile, "save-failure:template", runtime_id, game_state_modified_by_fixture=False,
             setup="Fresh class:WARRIOR; test preferences/unlocks only; public start and save")
    audit_schema(profile)
    return profile


def copy_template(root, template, case, runtime_id):
    profile = safe_profile(root, "save-failure-" + case)
    copy_audited_profile(template, profile)
    assert save_file(profile).is_file()
    metadata(profile, "save-failure:" + case, runtime_id, source_profile=str(template.relative_to(root)))
    return profile


def open_saved(classpath, profile):
    client = FixtureClient(launch_command(classpath), profile)
    assert ("err" not in client.request('info'))
    return client


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
        assert state["hero"]["depth"] == 1
        # An actual public turn makes the later first-file write observably different.
        _, _, waited = execute(client, "wait")
        assert ("err" not in waited), waited
        events_before = client.request('events', {"limit": 100})
        assert ("err" not in events_before), events_before
        after_sequence = max((event["sequence"] for event in events_before["data"]["items"]), default=0)
        game_before, depth_before = game.read_bytes(), depth.read_bytes()
        blocked.mkdir()
        metadata(profile, "save-failure:" + case, runtime_id, source_profile=str(template.relative_to(root)),
                 save_mutation="Empty directory blocks existing file temp write", blocked_path=str(blocked.relative_to(profile)))
        state, failed_id, response = execute(client, 'save', "save-fails-" + uuid.uuid4().hex)
        uncertain = True
        assert ("err" in response), {"save_was_falsely_reported_successful": response}
        assert response.get("err") == "EXECUTION_UNKNOWN", response
        duplicate = client.request_action({'op': 'save'}, request_id=failed_id, version=state['rev'])
        assert duplicate.get("err") == "DUPLICATE_REQUEST_ID", duplicate
        recorded = client.request('req', {'rid': failed_id, "get": ["reply"]})
        assert ("err" not in recorded) and recorded["data"]["st"] == "UNKNOWN", recorded
        assert "err" in recorded["data"]["reply"], recorded
        stopped = client.state()
        assert stopped["phase"] == "execution_unknown" and stopped['acts'] == [], stopped
        refused = client.request_action({'op': "wait"}, version=state['rev'])
        assert refused.get("err") == "EXECUTION_UNCERTAIN", refused
        events = client.request('events', {"after": after_sequence, "limit": 100})
        assert ("err" not in events), events
        failed_events = [event for event in events["data"]["items"] if event["kind"] in {"save", "save.failed"} and event["data"].get("success") is False]
        assert failed_events, {"no_public_save_failure": events}
        assert not any(event["kind"] in {"save", "save.completed"} and event["data"].get("success") is True for event in events["data"]["items"])
        with sqlite3.connect(f"file:{profile / 'audit/internal.sqlite3'}?mode=ro", uri=True) as db:
            canonical_scope = fixture_canonical_identity(profile, "scope", state['s'])
            exceptions = db.execute("SELECT exception_class,stack_trace FROM exceptions WHERE scope_id=? AND id=?", (canonical_scope, failed_id)).fetchall()
            assert exceptions and any(blocked.name in stack and "IOException" in stack for _, stack in exceptions)
        for event in failed_events:
            assert "stack_trace" not in event["data"] and "message" not in event["data"]
        game_changed = game.read_bytes() != game_before
        depth_changed = depth.read_bytes() != depth_before
        assert not depth_changed, "Blocked or unattempted second file must remain the previous bytes"
        assert game_changed == second_file, {"first_file_changed": game_changed, "second_file_blocked": second_file}
        audit_schema(profile)
        report = dict(case=case, ok=True, profile=str(profile.relative_to(root)),
                      error_code=response["err"], request_status="UNKNOWN", duplicate_rejected=True,
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
        assert restored['s'] == state['s']
        assert restored['rev'] != state['rev']
        historic = client.request('req', {'rid': failed_id, "get": ["reply"]})
        assert historic["data"]["st"] == "UNKNOWN", historic
        duplicate = client.request_action({'op': 'save'}, request_id=failed_id)
        assert duplicate.get("err") == "DUPLICATE_REQUEST_ID", duplicate
        _, recovered_id, recovered = execute(client, 'save')
        assert ("err" not in recovered) and recovered_id != failed_id, recovered
        recovery_record = client.request('req', {'rid': recovered_id})
        assert recovery_record["data"]["st"] == "COMPLETED", recovery_record
        report.update(restart_scope_unchanged=True, historic_failed_id_remains_unknown=True,
                      historic_failed_id_still_occupied=True, new_id_save_succeeds_after_restart=True)
    finally:
        stop(client)
    audit_schema(profile)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="save-game,save-depth")
    args = parser.parse_args()
    cases = args.cases.split(",")
    assert all(case in {"save-game", "save-depth"} for case in cases)
    root = Path(__file__).resolve().parents[4]
    classpath = (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip()
    classpath, runtime_id = freeze_runtime(root, classpath)
    template = create_template(root, classpath, runtime_id)
    results = []
    for case in cases:
        profile = copy_template(root, template, case, runtime_id)
        try:
            result = save_failure_case(root, template, profile, classpath, runtime_id, case == "save-depth")
        except Exception as error:
            result = dict(case=case, ok=False, profile=str(profile.relative_to(root)), error=str(error))
        results.append(result)
        write_json(profile / "save-failure-result.json", dict(test_fixture=True, counts_as_win=False, runtime_id=runtime_id, **result))
        print(json.dumps(dict(test_fixture=True, counts_as_win=False, runtime_id=runtime_id, **result), ensure_ascii=False), flush=True)
    summary = dict(test_fixture=True, counts_as_win=False, runtime_id=runtime_id,
                   template=str(template.relative_to(root)), total=len(results), passed=sum(result["ok"] for result in results), results=results)
    write_json(root / "desktop-control/build/fixtures" / runtime_id / "save-failure-results.json", summary)
    if not all(result["ok"] for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
