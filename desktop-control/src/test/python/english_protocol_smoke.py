#!/usr/bin/env python3
"""Draft real-engine check: Chinese windowed GUI, English public game prose.

No game choices use the private UiSceneAssertions or audit database. Private data
from isolated profiles is read only after a chosen operation, for assertions. Old
audit coverage copies an actual closed Chinese fixture and never edits its rows.
"""
import argparse
import fcntl
import json
from pathlib import Path
import re
import shutil
import sqlite3
import time
import unicodedata
import uuid

from fixture_smoke import FixtureClient, freeze_runtime
from legacy_save_smoke import launch_command, metadata, safe_profile, write_json


CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
PROSE = {"name", "class_name", "subclass_name", "label", "text", "description",
         "prompt", "cell_prompt", "item_prompt", "options", "message", "title", "hint", "tooltip", "disabled_reason"}
RAW = {"raw_request", "raw_response", "raw_bytes", "raw_format", "raw_json"}
KNOWN_TRANSLATIONS = {"进入地牢": "enter the dungeon", "继续": "continue", "关于": "about",
                      "改动": "changes", "日志": "journal", "排行榜": "rankings",
                      "战士": "warrior", "破旧的短剑": "worn shortsword", "布甲": "cloth armor"}


def prose_values(value, path=(), prose=False):
    """Only game prose; raw requests, identifiers, filenames and user data are not translation targets."""
    if isinstance(value, dict):
        for key, child in value.items():
            if key not in RAW:
                yield from prose_values(child, path + (key,), key in PROSE)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from prose_values(child, path + (index,), prose)
    elif prose and isinstance(value, str):
        yield path, value


def assert_english(value):
    bad = [(path, text) for path, text in prose_values(value)
           if any(char.isalpha() and "LATIN" not in unicodedata.name(char, "") for char in text)]
    assert not bad, {"non_english_game_prose": bad[:10]}


def at_path(value, path):
    for part in path:
        value = value[part]
    return value


def shape_preserved(before, after, path=()):
    """Presentation may add metadata; it must not remove old fields, entries, or text blocks."""
    if isinstance(before, dict):
        assert isinstance(after, dict) and before.keys() <= after.keys(), {"lost_fields": path}
        for key, value in before.items():
            if key not in RAW:
                shape_preserved(value, after[key], path + (key,))
            else:
                assert value == after[key], {"raw_audit_value_changed": path + (key,)}
    elif isinstance(before, list):
        assert isinstance(after, list) and len(before) == len(after), {"changed_array_shape": path}
        for index, value in enumerate(before):
            shape_preserved(value, after[index], path + (index,))
    elif before is not None:
        assert type(before) is type(after), {"changed_value_type": path}
        field = next((part for part in reversed(path) if isinstance(part, str)), None)
        if field not in PROSE:
            assert before == after, {"non_prose_value_changed": path}
    else:
        assert after is None, {"null_value_filled_by_translation": path}


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
        checked_state(client)
        response = client.request("action.execute", {"action": action, **args}, request_id=request_id)
        if response.get("error", {}).get("code") == "STALE_STATE":
            request_id = None
            continue
        assert response["ok"], response
        if response.get("status") == "in_progress":
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                lookup = client.request("request.get", {"target_id": response["id"]}, scope=response["scope_id"])
                assert lookup["ok"], lookup
                if lookup["result"]["status"] not in {"RECEIVED", "EXECUTING"}:
                    response = lookup["result"]["response"]
                    assert response["ok"], response
                    break
                time.sleep(.05)
            else:
                raise TimeoutError("English fixture action did not settle")
        result = response["result"]
        if "observation" in result:
            client.scope = result["scope_id"]
            client.version = result["state_version"]
            client.last_state = result
        return response
    raise AssertionError("English fixture could not obtain a fresh action version")


def activate(client, *labels):
    desired = {label.casefold() for label in labels}
    for _ in range(6):
        state = checked_state(client)
        candidates = [a for a in state["actions"] if a["action"] == "ui.activate"
                      and a.get("label", "").casefold() in desired]
        assert len(candidates) == 1, {"expected_english_labels": labels,
                                      "observed": [a.get("label") for a in state["actions"]]}
        response = client.request("action.execute", {"action": "ui.activate", "control": candidates[0]["control"]})
        if response.get("error", {}).get("code") == "STALE_STATE":
            continue
        assert response["ok"] and response.get("status") != "in_progress", response
        return response
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
    cursor = 0
    found = []
    while True:
        response = client.request("events.read", {"after": cursor, "limit": 100}, scope=scope)
        assert response["ok"], response
        rows = response["result"]
        found.extend(rows)
        if len(rows) < 100:
            return found
        cursor = rows[-1]["sequence"]


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
        text = " ".join(value for _, value in prose_values(sword))
        assert "worn shortsword" in text.casefold() and "quite short sword" in text.casefold(), text
        checks.append("original_sword_description_english")
        execute(client, "ui.back")
        stone = next(item for item in items if item["name"].casefold() == "throwing stone")
        execute(client, "inventory.open", locator=stone["locator"])
        aiming = activate(client, "THROW")["result"]
        assert any(a["action"] == "cell.cancel" for a in aiming["actions"])
        cancelled = execute(client, "cell.cancel")["result"]
        unchanged = next(item for item in cancelled["observation"]["inventory"] if item["name"].casefold() == "throwing stone")
        assert unchanged["quantity"] == stone["quantity"], "Cancelling must not consume the item"
        checks.append("original_throw_cancel_preserves_quantity")
        food = next(item for item in cancelled["observation"]["inventory"] if item["name"].casefold() == "ration of food")
        execute(client, "inventory.open", locator=food["locator"])
        eaten = activate(client, "EAT")
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
        record = client.request("request.get", {"target_id": food_id}, scope=scope)
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
            historical = restarted.request("request.get", {"target_id": food_id}, scope=scope)
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


def original_rows(profile):
    result = {}
    for side in ["public", "internal"]:
        with sqlite3.connect((profile / "audit" / (side + ".sqlite3")).as_uri() + "?mode=ro", uri=True) as db:
            result[side] = {}
            for table in ["requests", "exchanges", "events", "snapshots", "snapshot_blobs"]:
                query = db.execute("SELECT * FROM " + table)
                columns = [column[0] for column in query.description]
                result[side][table] = [dict(zip(columns, row)) for row in query.fetchall()]
    return result


def immutable_original_rows(profile, before):
    after = original_rows(profile)
    for side, tables in before.items():
        for table, original in tables.items():
            # New rows/columns may be added; all original columns, including raw blobs,
            # snapshots, timestamps and output receipts, must retain their exact values.
            keys = {"requests": ("scope_id", "id"), "exchanges": ("sequence",), "events": ("sequence",),
                    "snapshots": ("snapshot_id",), "snapshot_blobs": ("content_id",)}[table]
            index = {tuple(row[key] for key in keys): row for row in after[side][table]}
            for row in original:
                key = tuple(row[field] for field in keys)
                for column, value in row.items():
                    assert index[key][column] == value, {"historical_row_rewritten": [side, table, key, column]}


def dungeon_intro_pair(root):
    key = "journal.document.intros.dungeon.body="
    directory = root / "core/src/main/assets/messages/journal"
    values = []
    for filename in ["journal_zh.properties", "journal.properties"]:
        line = next(line for line in (directory / filename).read_text().splitlines() if line.startswith(key))
        value = line[len(key):]
        assert "%" not in value, "This exact static-prose assertion must not invent formatting arguments"
        values.append(value.replace(r"\n", "\n").replace(r"\t", "\t"))
    return tuple(values)


def migration_case(root, classpath, runtime_id, legacy):
    allowed = (root / "desktop-control/build/fixtures").resolve()
    legacy = legacy.resolve()
    assert legacy.is_relative_to(allowed) and legacy != allowed
    marker = json.loads((legacy / "test_fixture.json").read_text())
    assert marker.get("test_fixture") is True and marker.get("counts_as_win") is False
    profile = safe_profile(root, "english-migration")
    for source in legacy.rglob("*"):
        assert not source.is_symlink(), "Legacy fixture copy must not follow external files"
    # Interoperate with Java FileChannel's POSIX record lock. Keep the source locked
    # during copying; opening/closing a second fd to that inode would release lockf,
    # so the transient instance lock itself is deliberately not copied.
    with (legacy / ".instance.lock").open("r+b") as guard:
        fcntl.lockf(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        shutil.copytree(legacy, profile, dirs_exist_ok=True,
                        ignore=lambda directory, names: {".instance.lock"} if Path(directory) == legacy else set())
    metadata(profile, "english-migration", runtime_id, copied_from=str(legacy.relative_to(root)))
    before = original_rows(profile)
    samples = []
    original_intro, english_intro = dungeon_intro_pair(root)
    intro_sample = None
    for row in before["public"]["requests"]:
        scope, request_id, raw_request, raw = (row[key] for key in ("scope_id", "id", "raw_request", "response_json"))
        if not raw:
            continue
        response = json.loads(raw)
        fields = [(path, text) for path, text in prose_values(response) if CJK.search(text)]
        if len(samples) < 8 and any(text in KNOWN_TRANSLATIONS for _, text in fields):
            samples.append((scope, request_id, raw_request, response, fields))
        if intro_sample is None and any(text == original_intro for _, text in fields):
            intro_sample = (scope, request_id, raw_request, response, fields)
    assert samples, "A real Chinese historical response is required; an empty database cannot pass"
    assert intro_sample is not None, "The closed baseline must contain its real full dungeon introduction"
    if not any(sample[:2] == intro_sample[:2] for sample in samples):
        samples.append(intro_sample)
    client = EnglishClient(launch_command(classpath, fixture=True), profile)
    translated = 0
    try:
        assert client.request("protocol.info")["ok"]
        # Capture fresh GUI evidence without using it to select a historical request.
        fresh = checked_state(client)
        fresh_scene = fresh["observation"]["ui"]["scene"]
        gui_assertions(profile, {fresh_scene}, fresh["state_version"])
        for scope, request_id, raw_request, original, fields in samples:
            response = client.request("request.get", {"target_id": request_id}, scope=scope)
            assert response["ok"], response
            assert response["result"]["raw_request"] == raw_request
            presented = response["result"]["response"]
            shape_preserved(original, presented)
            for path, text in fields:
                english = at_path(presented, path)
                assert english.strip() and re.search(r"[A-Za-z]", english), {"text_deleted_instead_of_translated": path}
                old_parent = at_path(original, path[:-1])
                new_parent = at_path(presented, path[:-1])
                clipped = isinstance(old_parent, dict) and old_parent.get("clipped") is True
                if not clipped:
                    assert english not in {"Partially displayed text", "Unavailable"}, {"full_text_replaced_by_placeholder": path}
                    assert not isinstance(new_parent, dict) or new_parent.get("translation_status") != "partial"
                if text in KNOWN_TRANSLATIONS:
                    assert english.casefold() == KNOWN_TRANSLATIONS[text], {"wrong_english_text": [path, text, english]}
                if text == original_intro:
                    assert english == english_intro, "The complete original introduction must match its actual English resource"
                translated += 1
            assert client.request("history.list", {"after": 0, "limit": 100}, scope=scope)["ok"]
            public_events(client, scope)
        stop(client)
        immutable_original_rows(profile, before)
        report = {"case_id": "language.legacy_chinese_audit_presentation", "verified": True, "test_fixture": True,
                  "counts_as_win": False, "profile": str(profile.relative_to(root)), "runtime_id": runtime_id,
                  "source_profile": str(legacy.relative_to(root)), "historical_requests_checked": len(samples),
                  "translated_game_prose_fields_checked": translated, "original_fields_and_arrays_preserved": True,
                  "public_and_internal_original_rows_and_snapshot_blobs_unchanged": True,
                  "full_static_intro_matches_original_english_resource": True,
                  "raw_request_is_not_a_translation_target": True}
        write_json(profile / "english-migration-result.json", report)
        return report
    except Exception as error:
        write_json(profile / "english-migration-failure.json", {"verified": False, "test_fixture": True,
                   "counts_as_win": False, "case_id": "language.legacy_chinese_audit_presentation",
                   "profile": str(profile.relative_to(root)), "runtime_id": runtime_id,
                   "error_type": type(error).__name__, "error": str(error)[:4000]})
        raise
    finally:
        if client.process.poll() is None:
            stop(client, failure_cleanup=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy-profile", default="desktop-control/build/fixtures/menu-scenes-d6a2a70396224dbd80ec09e5fb0cc0d1")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports = [live_case(root, classpath, runtime_id), migration_case(root, classpath, runtime_id, root / args.legacy_profile)]
    write_json(root / "desktop-control/build/fixtures" / runtime_id / "english-protocol-results.json", reports)
    print(json.dumps(reports, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
