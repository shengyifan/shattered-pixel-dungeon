#!/usr/bin/env python3
"""Draft real-engine check: Chinese windowed GUI, English public game prose.

No game choices use the private UiSceneAssertions or audit database. Private data
from isolated profiles is read only after a chosen operation, for assertions.
CLI 9 uses fresh schema-12 profiles; old protocol/audit migration is unsupported.
"""
import argparse
from fixture_identity import fixture_identity_matches
import json
from protocol9 import response as decode_response
from pathlib import Path
import re
import time
import unicodedata
import uuid
from protocol9 import pages, frame_data
from client_result import settle_action

from fixture_smoke import FixtureClient, freeze_runtime, GAME_PROSE_FIELDS, RAW_FIELDS, game_prose_values
from fixture_support import launch_command, metadata, safe_profile, write_json


CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
PROSE = GAME_PROSE_FIELDS
RAW = RAW_FIELDS


def prose_values(value, path=(), prose=False):
    """Share protocol-9 source-aware prose rules with every fixture client."""
    yield from game_prose_values(value, path, prose)


def assert_english(value):
    bad = [(path, text) for path, text in prose_values(value)
           if any(char.isalpha() and "LATIN" not in unicodedata.name(char, "") for char in text)]
    assert not bad, {"non_english_game_prose": bad[:10]}


class EnglishClient(FixtureClient):
    def __init__(self, command, profile):
        self.responses = []
        self.uncertain = False
        super().__init__(command, profile)

    def request(self, op, args=None, **kwargs):
        response = super().request(op, args, **kwargs)
        if response.get("err") in {"EXECUTION_UNKNOWN", "EXECUTION_UNCERTAIN"}:
            self.uncertain = True
        assert_english(response)
        self.responses.append((op, response))
        return response


def checked_state(client):
    response = client.request('state')
    if response.get("err") == "SCOPE_MISMATCH":
        assert ("err" not in client.request('info'))
        response = client.request('state')
    assert ("err" not in response), response
    return frame_data(response)


def execute(client, action, request_id=None, **args):
    for _ in range(6):
        response = client.request_action({'op': action, **args}, request_id=request_id)
        if response.get("err") == "STALE_STATE":
            checked_state(client)
            request_id = None
            continue
        return settle_action(client, response, action).require_success()

    raise AssertionError("English fixture could not obtain a fresh action version")


def activate(client, *labels):
    desired = {label.casefold() for label in labels}
    state = client.last_state or checked_state(client)
    for _ in range(6):
        candidates = [a for a in state['acts'] if a['op'] == 'click'
                      and a.get("label", "").casefold() in desired]
        assert len(candidates) == 1, {"expected_english_labels": labels,
                                      "observed": [a.get("label") for a in state['acts']]}
        response = client.request_action({'op': 'click', 'ctl': candidates[0]['ctl']})
        if response.get("err") == "STALE_STATE":
            state = checked_state(client)
            continue
        return settle_action(client, response, 'click').require_success()
    raise AssertionError("English control repeatedly changed before dispatch")


def start_or_continue(client, resume=False):
    seen = []
    for _ in range(30):
        state = checked_state(client)
        ui = state["ui"]
        seen.append(ui["scene"])
        if state.get("scene") == "game":
            return state, seen
        if not resume and "TitleScene" not in seen and ui["scene"] in {"HeroSelectScene", "StartScene"}:
            # Welcome's original Enter control goes straight to hero selection. Reach
            # Title explicitly through original Back controls instead of inventing a route.
            execute(client, 'back')
            continue
        actions = [a for a in state['acts'] if a['op'] == 'click']
        if ui.get("modal") and ui["scene"] == "HeroSelectScene":
            execute(client, 'back')
            continue
        choice = None
        if resume and ui["scene"] == "StartScene":
            choice = next((a for a in actions if a.get("label", "").split("\n")[0].casefold() == "warrior"), None)
        # Only labels actually present in the English public action catalog select an input.
        priorities = ["continue", "enter the dungeon", "play", "new game", "start"]
        for label in priorities:
            if choice:
                break
            choice = next((a for a in actions if a.get("label", "").casefold() == label), None)
            if choice:
                break
        if choice is None and resume and ui["scene"] == "StartScene":
            choice = next((a for a in actions if a.get("label", "").split("\n")[0].casefold() == "warrior"), None)
        if choice is None:
            choice = next((a for a in actions if a.get("label", "").casefold() == "warrior"), None)
        if choice is None and any(a['op'] == 'reveal' for a in state['acts']):
            execute(client, 'reveal')
            continue
        assert choice, {"no_english_start_control": ui["scene"], 'acts': actions}
        response = client.request_action({'op': 'click', 'ctl': choice['ctl']})
        if response.get("err") == "STALE_STATE":
            continue
        assert ("err" not in response) and response.get("st") != "in_progress", response
        observed = response.get("data", {}).get("ui", {}).get("scene")
        if observed:
            seen.append(observed)
    raise AssertionError("Original English controls did not reach a game")


def gui_assertions(profile, required_scenes, required_version=None):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        file = profile / "ui-assertions.jsonl"
        rows = [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []
        seen = {row["scene"].split(".")[-1] for row in rows if row.get("scene")}
        if required_scenes <= seen and (required_version is None or any(fixture_identity_matches(profile,
                "activity" if str(row['state_version']).startswith("activity:") else "revision",
                row['state_version'],required_version) for row in rows)):
            assert rows and all(row["language"] == "CHI_SMPL" and row["fullscreen"] is False for row in rows)
            return {"language": "CHI_SMPL", "fullscreen": False, "scenes": sorted(seen), "checkpoints": len(rows)}
        time.sleep(.03)
    raise AssertionError({"missing_private_gui_scene_evidence": sorted(required_scenes - seen)})


def english_inventory(state):
    hero = state["hero"]
    assert hero["class_name"].casefold() == "warrior", hero["class_name"]
    items = state['inv']
    for locator, expected in [("equipment.weapon", "worn shortsword"), ("equipment.armor", "cloth armor")]:
        item = next(item for item in items if item['loc'] == locator)
        assert item["name"].casefold() == expected, item
    return items


def public_events(client, scope):
    found = []
    for rows in pages(client, 'events', scope=scope):
        found.extend(rows)
    return found


def stop(client, failure_cleanup=False):
    try:
        if client.process.poll() is None:
            if failure_cleanup or client.uncertain:
                client.process.stdin.close()
                client.process.wait(timeout=35)
            else:
                execute(client, 'quit')
                client.process.wait(timeout=25)
                assert client.process.returncode == 0
    finally:
        if client.process.poll() is None:
            client.process.stdin.close()
            try:
                client.process.wait(timeout=35)
            except Exception:
                client.process.terminate()
                client.process.wait(timeout=10)
        client.stderr.close()
        client.trace.close()


def live_case(root, classpath, runtime_id):
    profile = safe_profile(root, "english-protocol")
    metadata(profile, "class:WARRIOR", runtime_id)
    command = launch_command(classpath, fixture=True)
    client = EnglishClient(command, profile)
    checks = []
    try:
        hello = client.request('info')
        assert ("err" not in hello)
        state, seen = start_or_continue(client)
        assert {"WelcomeScene", "TitleScene", "HeroSelectScene", "GameScene"} <= set(seen), seen
        items = english_inventory(state)
        checks.append("welcome_title_hero_game_english")
        scope = state['s']
        sword = execute(client, 'item', loc="equipment.weapon")
        text = " ".join(value for _, value in prose_values(sword.observation))
        assert "worn shortsword" in text.casefold() and "quite short sword" in text.casefold(), text
        checks.append("original_sword_description_english")
        execute(client, 'back')
        stone = next(item for item in items if item["name"].casefold() == "throwing stone")
        execute(client, 'item', loc=stone['loc'])
        aiming = activate(client, "THROW").observation
        assert any(a['op'] == 'untarget' for a in aiming['acts'])
        cancelled = execute(client, 'untarget').observation
        unchanged = next(item for item in cancelled['inv'] if item["name"].casefold() == "throwing stone")
        assert unchanged['qty'] == stone['qty'], "Cancelling must not consume the item"
        checks.append("original_throw_cancel_preserves_quantity")
        food = next(item for item in cancelled['inv'] if item["name"].casefold() == "ration of food")
        execute(client, 'item', loc=food['loc'])
        eating = activate(client, "EAT")
        eaten = eating.initial_response
        food_id = eaten["id"]
        # Only a native displayed message proves event-language projection; no test text injection.
        deadline = time.monotonic() + 10
        events = []
        while time.monotonic() < deadline:
            checked_state(client)
            events = public_events(client, scope)
            if any("That food tasted delicious!" in entry["text"] for event in events if event["kind"] == "game.log"
                   for entry in event["data"]["entries"]):
                break
            time.sleep(.05)
        else:
            raise AssertionError("No original food message in English drawn game.log snapshots")
        checks.append("native_drawn_food_message_english")
        actions = client.request('actions')
        assert ("err" not in actions) and any(a.get("label", "").casefold() == "wait" for a in actions["data"]['acts'])
        history = client.request('history', {"after": 0, "limit": 100}, scope=scope)
        assert ("err" not in history) and any(row["id"] == food_id for row in history["data"]["items"])
        record = client.request('req', {'rid': food_id, "get": ["reply"]}, scope=scope)
        assert "err" not in record, record
        stored_reply = record["data"]["reply"]
        final_reply = decode_response(stored_reply)
        assert final_reply["id"] == food_id and "err" not in final_reply
        assert final_reply["st"] == eating.status, (final_reply, eating)
        assert_english(final_reply)
        if eaten["st"] != "in_progress":
            assert final_reply == eaten
        checks.append("actions_history_and_final_request_response_english")
        gui = gui_assertions(profile, {"WelcomeScene", "TitleScene", "HeroSelectScene", "GameScene"})
        assert any(row["window_classes"] and any(name.endswith(".WndUseItem") for name in row["window_classes"])
                   for row in map(json.loads, (profile / "ui-assertions.jsonl").read_text().splitlines()))
        checks.append("actual_chinese_windowed_gui_and_item_window")
        execute(client, 'save')
        stop(client)
        checks.append("normal_save_and_exit")
        restarted = EnglishClient(command, profile)
        try:
            assert ("err" not in restarted.request('info'))
            resumed, _ = start_or_continue(restarted, resume=True)
            assert resumed['s'] == scope
            checks.append("same_scope_resume")
            english_inventory(resumed)
            historical = restarted.request('req', {'rid': food_id, "get": ["reply"]}, scope=scope)
            assert "err" not in historical and historical["data"]["reply"] == stored_reply
            assert decode_response(historical["data"]["reply"]) == final_reply
            old_events = public_events(restarted, scope)
            assert any("That food tasted delicious!" in entry["text"] for event in old_events if event["kind"] == "game.log"
                       for entry in event["data"]["entries"])
            gui_assertions(profile, {"GameScene"}, resumed['rev'])
            checks.append("old_response_and_events_english_after_restart_with_chinese_gui")
            stop(restarted)
        finally:
            if restarted.process.poll() is None:
                stop(restarted, failure_cleanup=True)
        report = {"case_id": "language.english_protocol_chinese_gui", "verified": True, "test_fixture": True,
                  "counts_as_win": False, "profile": str(profile.relative_to(root)), "runtime_id": runtime_id,
                  "build_id": hello["data"]["build_id"], "gui": gui, "scope_id": scope,
                  "strict_english_item_names_and_description": True, "throw_cancel_preserves_quantity": True,
                  "native_drawn_food_log_english": True, "original_response_and_events_english_after_restart": True,
                  "passed_checks": checks}
        write_json(profile / "english-protocol-result.json", report)
        return report
    except Exception as error:
        write_json(profile / "english-protocol-failure.json", {"verified": False, "test_fixture": True,
                   "counts_as_win": False, "case_id": "language.english_protocol_chinese_gui",
                   "profile": str(profile.relative_to(root)), "runtime_id": runtime_id,
                   "passed_checks": checks, "error_type": type(error).__name__, "error": str(error)[:4000]})
        raise
    finally:
        if client.process.poll() is None:
            stop(client, failure_cleanup=True)


def main():
    parser = argparse.ArgumentParser()
    parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports = [live_case(root, classpath, runtime_id)]
    write_json(root / "desktop-control/build/fixtures" / runtime_id / "english-protocol-results.json", reports)
    print(json.dumps(reports, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
