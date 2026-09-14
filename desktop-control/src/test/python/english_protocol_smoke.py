#!/usr/bin/env python3
"""Draft real-engine check: Chinese windowed GUI, English public game prose.

No game choices use the private UiSceneAssertions or audit database. Private data
from isolated profiles is read only after a chosen operation, for assertions.
CLI 4 uses fresh schema-7 profiles; old protocol/audit migration is unsupported.
"""
import argparse
import json
from pathlib import Path
import re
import time
import unicodedata
import uuid
from protocol4 import pages
from client_result import settle_action

from fixture_smoke import FixtureClient, freeze_runtime, GAME_PROSE_FIELDS, RAW_FIELDS, game_prose_values
from legacy_save_smoke import launch_command, metadata, safe_profile, write_json


CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
PROSE = GAME_PROSE_FIELDS
RAW = RAW_FIELDS


def prose_values(value, path=(), prose=False):
    """Share protocol-4 source-aware prose rules with every fixture client."""
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
        if response.get("error", {}).get("code") in {"EXECUTION_UNKNOWN", "EXECUTION_UNCERTAIN"}:
            self.uncertain = True
        assert_english(response)
        self.responses.append((op, response))
        return response


def checked_state(client):
    response = client.request("state.get")
    if response.get("error", {}).get("code") == "SCOPE_MISMATCH":
        assert client.request("protocol.info")["ok"]
        response = client.request("state.get")
    assert response["ok"], response
    return response["result"]


def execute(client, action, request_id=None, **args):
    for _ in range(6):
        response = client.request("action.execute", {"action": action, **args}, request_id=request_id)
        if response.get("error", {}).get("code") == "STALE_STATE":
            checked_state(client)
            request_id = None
            continue
        return settle_action(client, response, action).require_success()

    raise AssertionError("English fixture could not obtain a fresh action version")


def activate(client, *labels):
    desired = {label.casefold() for label in labels}
    state = client.last_state or checked_state(client)
    for _ in range(6):
        candidates = [a for a in state["actions"] if a["action"] == "ui.activate"
                      and a.get("label", "").casefold() in desired]
        assert len(candidates) == 1, {"expected_english_labels": labels,
                                      "observed": [a.get("label") for a in state["actions"]]}
        response = client.request("action.execute", {"action": "ui.activate", "control": candidates[0]["control"]})
        if response.get("error", {}).get("code") == "STALE_STATE":
            state = checked_state(client)
            continue
        return settle_action(client, response, "ui.activate").require_success()
    raise AssertionError("English control repeatedly changed before dispatch")


def start_or_continue(client, resume=False):
    seen = []
    for _ in range(30):
        state = checked_state(client)
        ui = state["observation"]["ui"]
        seen.append(ui["scene"])
        if state["observation"].get("scene") == "game":
            return state, seen
        if not resume and "TitleScene" not in seen and ui["scene"] in {"HeroSelectScene", "StartScene"}:
            # Welcome's original Enter control goes straight to hero selection. Reach
            # Title explicitly through original Back controls instead of inventing a route.
            execute(client, "ui.back")
            continue
        actions = [a for a in state["actions"] if a["action"] == "ui.activate"]
        if ui.get("modal") and ui["scene"] == "HeroSelectScene":
            execute(client, "ui.back")
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
        if choice is None and any(a["action"] == "ui.reveal" for a in state["actions"]):
            execute(client, "ui.reveal")
            continue
        assert choice, {"no_english_start_control": ui["scene"], "actions": actions}
        response = client.request("action.execute", {"action": "ui.activate", "control": choice["control"]})
        if response.get("error", {}).get("code") == "STALE_STATE":
            continue
        assert response["ok"] and response.get("status") != "in_progress", response
        observed = response.get("result", {}).get("observation", {}).get("ui", {}).get("scene")
        if observed:
            seen.append(observed)
    raise AssertionError("Original English controls did not reach a game")


def gui_assertions(profile, required_scenes, required_version=None):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        file = profile / "ui-assertions.jsonl"
        rows = [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []
        seen = {row["scene"].split(".")[-1] for row in rows if row.get("scene")}
        if required_scenes <= seen and (required_version is None or any(row["state_version"] == required_version for row in rows)):
            assert rows and all(row["language"] == "CHI_SMPL" and row["fullscreen"] is False for row in rows)
            return {"language": "CHI_SMPL", "fullscreen": False, "scenes": sorted(seen), "checkpoints": len(rows)}
        time.sleep(.03)
    raise AssertionError({"missing_private_gui_scene_evidence": sorted(required_scenes - seen)})


def english_inventory(state):
    hero = state["observation"]["hero"]
    assert hero["class_name"].casefold() == "warrior", hero["class_name"]
    items = state["observation"]["inventory"]
    for locator, expected in [("equipment.weapon", "worn shortsword"), ("equipment.armor", "cloth armor")]:
        item = next(item for item in items if item["locator"] == locator)
        assert item["name"].casefold() == expected, item
    return items


def public_events(client, scope):
    found = []
    for rows in pages(client, "events.read", scope=scope):
        found.extend(rows)
    return found


def stop(client, failure_cleanup=False):
    try:
        if client.process.poll() is None:
            if failure_cleanup or client.uncertain:
                client.process.stdin.close()
                client.process.wait(timeout=35)
            else:
                execute(client, "app.quit")
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
        hello = client.request("protocol.info")
        assert hello["ok"]
        state, seen = start_or_continue(client)
        assert {"WelcomeScene", "TitleScene", "HeroSelectScene", "GameScene"} <= set(seen), seen
        items = english_inventory(state)
        checks.append("welcome_title_hero_game_english")
        scope = state["scope_id"]
        sword = execute(client, "inventory.open", locator="equipment.weapon")
        text = " ".join(value for _, value in prose_values(sword.observation))
        assert "worn shortsword" in text.casefold() and "quite short sword" in text.casefold(), text
        checks.append("original_sword_description_english")
        execute(client, "ui.back")
        stone = next(item for item in items if item["name"].casefold() == "throwing stone")
        execute(client, "inventory.open", locator=stone["locator"])
        aiming = activate(client, "THROW").observation
        assert any(a["action"] == "cell.cancel" for a in aiming["actions"])
        cancelled = execute(client, "cell.cancel").observation
        unchanged = next(item for item in cancelled["observation"]["inventory"] if item["name"].casefold() == "throwing stone")
        assert unchanged["quantity"] == stone["quantity"], "Cancelling must not consume the item"
        checks.append("original_throw_cancel_preserves_quantity")
        food = next(item for item in cancelled["observation"]["inventory"] if item["name"].casefold() == "ration of food")
        execute(client, "inventory.open", locator=food["locator"])
        eaten = activate(client, "EAT").initial_response
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
        actions = client.request("actions.list")
        assert actions["ok"] and any(a.get("label", "").casefold() == "wait" for a in actions["result"]["actions"])
        history = client.request("history.list", {"after": 0, "limit": 100}, scope=scope)
        assert history["ok"] and any(row["id"] == food_id for row in history["result"])
        record = client.request("request.get", {"target_id": food_id, "get": ["reply"]}, scope=scope)
        assert record["ok"] and record["result"]["response"] == eaten
        checks.append("actions_history_and_original_request_response_english")
        gui = gui_assertions(profile, {"WelcomeScene", "TitleScene", "HeroSelectScene", "GameScene"})
        assert any(row["window_classes"] and any(name.endswith(".WndUseItem") for name in row["window_classes"])
                   for row in map(json.loads, (profile / "ui-assertions.jsonl").read_text().splitlines()))
        checks.append("actual_chinese_windowed_gui_and_item_window")
        execute(client, "game.save")
        stop(client)
        checks.append("normal_save_and_exit")
        restarted = EnglishClient(command, profile)
        try:
            assert restarted.request("protocol.info")["ok"]
            resumed, _ = start_or_continue(restarted, resume=True)
            assert resumed["scope_id"] == scope
            checks.append("same_scope_resume")
            english_inventory(resumed)
            historical = restarted.request("request.get", {"target_id": food_id, "get": ["reply"]}, scope=scope)
            assert historical["ok"] and historical["result"]["response"] == eaten
            old_events = public_events(restarted, scope)
            assert any("That food tasted delicious!" in entry["text"] for event in old_events if event["kind"] == "game.log"
                       for entry in event["data"]["entries"])
            gui_assertions(profile, {"GameScene"}, resumed["state_version"])
            checks.append("old_response_and_events_english_after_restart_with_chinese_gui")
            stop(restarted)
        finally:
            if restarted.process.poll() is None:
                stop(restarted, failure_cleanup=True)
        report = {"case_id": "language.english_protocol_chinese_gui", "verified": True, "test_fixture": True,
                  "counts_as_win": False, "profile": str(profile.relative_to(root)), "runtime_id": runtime_id,
                  "build_id": hello["result"]["build_id"], "gui": gui, "scope_id": scope,
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
