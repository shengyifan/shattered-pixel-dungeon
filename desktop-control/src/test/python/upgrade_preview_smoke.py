#!/usr/bin/env python3
"""Original armor upgrade previews in four isolated GUI configurations.

All action choices come from current public state/actions. Fixture checkpoints are
postconditions only. A STALE_STATE or EXECUTION_UNKNOWN fails immediately; no
request or callback is retried. Prepared runs do not count as victory evidence.
"""
import argparse
import json
import os
from pathlib import Path
import re
import traceback
import uuid
import xml.etree.ElementTree as ET

from fixture_smoke import FixtureClient, checkpoint, freeze_runtime
from ending_scenario_smoke import ui_assertion
from test_ui import configure_test_ui


CASES = ("known-pane", "known-bag", "unknown-pane", "unknown-bag")
WND_BAG = "com.shatteredpixel.shatteredpixeldungeon.windows.WndBag"
WND_UPGRADE = "com.shatteredpixel.shatteredpixeldungeon.windows.WndUpgrade"
WND_CANCEL = "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.InventoryScroll$WndConfirmCancel"


def ui(state):
    return state["observation"]["ui"]


def current(client):
    state = client.state()
    assert state.get("state_version") and state.get("observation"), state
    assert state["phase"] != "execution_unknown", state
    return state


def execute(client, state, action, **args):
    advertised = [entry for entry in state["actions"] if entry["action"] == action]
    if "control" in args:
        advertised = [entry for entry in advertised if entry.get("control") == args["control"]]
    assert advertised, {"action_not_advertised": action, "args": args, "actions": state["actions"]}
    if action == "ui.activate":
        assert "click" in advertised[0].get("gestures", ["click"]), advertised
        args["gesture"] = "click"
    response = client.request("action.execute", {"action": action, **args},
                              scope=state["scope_id"], version=state["state_version"])
    assert response.get("ok"), response
    # These original menu/selection/read/save callbacks are bounded actions.
    assert response.get("status") in {"completed", "awaiting_input"}, response
    result = response["result"]
    assert result.get("state_version") and result.get("observation"), result
    client.last_state = result
    return result, response


def action(client, name, **args):
    return execute(client, current(client), name, **args)[0]


def choose(client, label):
    state = current(client)
    candidates = [entry for entry in state["actions"] if entry["action"] == "ui.activate"
                  and entry.get("label", "").casefold() == label.casefold()]
    assert len(candidates) == 1, {"expected_label": label, "candidates": candidates, "actions": state["actions"]}
    return execute(client, state, "ui.activate", control=candidates[0]["control"])[0]


def reach_game(client):
    selected = False
    for _ in range(35):
        state = current(client)
        if state["observation"].get("scene") == "game":
            result = current(client)
            assert result["observation"]["hero"]["class"] == "warrior"
            return result
        controls = [entry for entry in state["actions"] if entry["action"] == "ui.activate"]
        if ui(state)["scene"] == "HeroSelectScene" and not selected and not ui(state)["modal"]:
            candidates = [entry for entry in controls if entry.get("label", "").casefold() == "warrior"]
            assert len(candidates) == 1, controls
            execute(client, state, "ui.activate", control=candidates[0]["control"])
            selected = True
        elif ui(state)["scene"] == "HeroSelectScene" and selected and ui(state)["modal"]:
            execute(client, state, "ui.back")
        else:
            candidate = next((entry for label in ("Continue", "Enter the Dungeon", "Play", "New Game", "Start")
                              for entry in controls if entry.get("label") == label), None)
            if candidate:
                execute(client, state, "ui.activate", control=candidate["control"])
            else:
                execute(client, state, "ui.reveal")
    raise AssertionError("Original menus did not reach the Warrior fixture")


def armor(state):
    items = [item for item in state["observation"]["inventory"] if item["locator"] == "equipment.armor"]
    assert len(items) == 1 and items[0]["name"].casefold().startswith("cloth armor"), items
    return items[0]


def scrolls(state):
    return [item for item in state["observation"]["inventory"] if "scroll" in item["name"].casefold()]


def verify(client, state, level, count, known, mode, identify_count=0):
    assert state["observation"]["hero"]["level"] == 1
    assert armor(state)["level_known"] and armor(state)["level"] == level, armor(state)
    assert sum(item["quantity"] for item in scrolls(state)) == count + identify_count, scrolls(state)
    helpers = [item for item in scrolls(state) if item.get("type_known") is True and item["name"].casefold() == "scroll of identify"]
    assert sum(item["quantity"] for item in helpers) == identify_count, helpers
    observed = checkpoint(client.profile, state["state_version"])["item_window"]["upgrade"]
    assert observed == {"armor_level": level, "seal_level": level, "scroll_count": count,
                        "scroll_known": known, "upgrades_used": level, "interface_size": mode}, observed
    assert state["actions"], state


def open_item(client, predicate):
    state = current(client)
    items = [item for item in state["observation"]["inventory"] if predicate(item)]
    assert len(items) == 1, {"matching_items": items}
    return execute(client, state, "inventory.open", locator=items[0]["locator"])[0]


def identify_with_intuition(client, mode):
    open_item(client, lambda item: item["name"].casefold() == "stone of intuition")
    choose(client, "USE")
    state = current(client)
    unknown = [item for item in scrolls(state) if not item["type_known"]]
    assert len(unknown) == 1, unknown
    choose(client, unknown[0]["name"])
    visited = set()
    for _ in range(20):
        state = current(client)
        confirmation = [entry for entry in state["actions"] if entry["action"] == "ui.activate"
                        and entry.get("label", "").casefold() == "scroll of upgrade"]
        if confirmation:
            assert len(confirmation) == 1, confirmation
            identified = execute(client, state, "ui.activate", control=confirmation[0]["control"])[0]
            verify(client, identified, 0, 1, True, mode)
            assert scrolls(identified)[0]["name"].casefold() == "scroll of upgrade"
            return len(visited)
        # Original icon candidates have no public names until preselected. Inspect
        # each advertised candidate once; never infer identity from an icon/index.
        candidates = [entry for entry in state["actions"] if entry["action"] == "ui.activate"
                      and not entry.get("label") and entry["control"] not in visited]
        assert candidates, {"no_unvisited_public_guess": state["actions"]}
        candidate = candidates[0]
        visited.add(candidate["control"])
        execute(client, state, "ui.activate", control=candidate["control"])
    raise AssertionError("Intuition did not expose an upgrade-scroll confirmation")


def identify_with_identify_scroll(client, mode):
    open_item(client, lambda item: item.get("type_known") is True and item["name"].casefold() == "scroll of identify")
    selected = choose(client, "READ")
    unknown = [item for item in scrolls(selected) if item.get("type_known") is False]
    assert len(unknown) == 1, unknown
    verify(client, selected, 0, 1, False, mode, identify_count=1)
    identified = choose(client, unknown[0]["name"])
    verify(client, identified, 0, 1, True, mode)
    assert len(scrolls(identified)) == 1 and scrolls(identified)[0]["name"].casefold() == "scroll of upgrade"
    return {"source": "known Scroll of Identify", "original_read_and_public_unknown_selection": True,
            "identify_scroll_consumed_once": True, "upgrade_scroll_preserved_before_upgrade": True}


def verify_selector(client, state, mode):
    assert state["phase"] == "awaiting_input", state
    if mode == 2:
        assert not ui(state)["modal"] and ui(state).get("item_prompt", "").casefold() == "upgrade an item", ui(state)
        assert WND_BAG not in ui_assertion(client.profile, state)["window_classes"]
    else:
        assert ui(state)["modal"], ui(state)
        ui_assertion(client.profile, state, WND_BAG)
        assert any("upgrade an item" in node.get("text", "").casefold() for node in ui(state)["controls"])
    assert any(entry["action"] == "ui.activate" and entry.get("label", "").casefold() == "cloth armor"
               for entry in state["actions"]), state["actions"]


def read_scroll(client, mode, known):
    open_item(client, lambda item: "scroll" in item["name"].casefold())
    selected = choose(client, "READ")
    verify_selector(client, selected, mode)
    verify(client, selected, 0, 1 if known else 0, True, mode)
    return selected


def source_entries(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from source_entries(child)
    elif isinstance(value, list):
        for child in value:
            yield from source_entries(child)


def numeric_source_text(source):
    """Use only the already-public numeric source, including privacy-reduced literal values."""
    assert isinstance(source, dict), {"missing_numeric_source": source}
    kind = source.get("kind")
    if kind == "displayed":
        return numeric_source_text(source.get("value"))
    if kind == "literal":
        assert source.get("origin") in {"symbol", "literal"} and isinstance(source.get("value"), str), source
        return source["value"]
    if kind == "scalar":
        value = source.get("value")
        assert isinstance(value, int) and not isinstance(value, bool), source
        return str(value)
    if kind == "concat":
        assert isinstance(source.get("parts"), list), source
        return "".join(numeric_source_text(part) for part in source["parts"])
    if kind == "replace":
        assert source.get("old") == "-" and source.get("new") == "~", source
        return numeric_source_text(source.get("value")).replace("-", "~")
    if kind == "formatted_fragment":
        assert isinstance(source.get("text"), str), source
        return source["text"]
    raise AssertionError({"unsupported_numeric_source": source})


def assert_preview_fields(state):
    controls = ui(state)["controls"]
    texts = [node.get("text", "") for node in controls]
    language = ui(state)["display"]["language"]
    # WndUpgrade.fillFields uses this original GUI-specific punctuation branch.
    delimiter = "~" if language in {"zh", "zh-hant"} else "-"
    expected = "\nBlocking\n0" + delimiter + "2\n1" + delimiter + "3\nWeight\n10\n9"
    assert "Blocking" in texts and "Weight" in texts, texts
    assert any(expected in text for text in texts), texts
    for label, key in (("Blocking", "windows.wndupgrade.blocking"), ("Weight", "windows.wndupgrade.weight")):
        nodes = [node for node in controls if node.get("text") == label and node.get("role") == "text"]
        assert len(nodes) == 1, nodes
        assert any(entry.get("kind") == "resource" and entry.get("key") == key
                   for entry in source_entries(nodes[0].get("text_sources", {}).get("text"))), nodes[0]
    for text, numbers in (("0" + delimiter + "2", [0, 2]), ("1" + delimiter + "3", [1, 3]), ("10", [10]), ("9", [9])):
        nodes = [node for node in controls if node.get("text") == text and node.get("role") == "text"]
        assert len(nodes) == 1, {"expected_displayed_number": text, "nodes": nodes}
        frozen_text = numeric_source_text(nodes[0].get("text_sources", {}).get("text"))
        assert frozen_text == text, {"displayed": text, "frozen_source_text": frozen_text}
        assert all(re.fullmatch(r"\d+", number) for number in frozen_text.split(delimiter)), frozen_text
        frozen = [int(number) for number in frozen_text.split(delimiter)]
        assert frozen == numbers, {"displayed": text, "frozen_source_numbers": frozen, "expected": numbers}
    return delimiter


def preview(client, mode, count):
    before = current(client)
    result = choose(client, "Cloth Armor")
    assert result["state_version"] != before["state_version"]
    assert result["phase"] == "awaiting_input" and ui(result)["modal"]
    ui_assertion(client.profile, result, WND_UPGRADE)
    detailed = client.state(source=True)
    assert detailed["state_version"] == result["state_version"], "Source inspection changed the preview boundary"
    assert_preview_fields(detailed)
    assert {"Upgrade", "Back"} <= {entry.get("label") for entry in result["actions"]}
    verify(client, result, 0, count, True, mode)
    observed = current(client)
    assert observed["state_version"] == result["state_version"], {"preview_changed_on_observation": observed}
    verify(client, observed, 0, count, True, mode)
    return result


def exercise(client, case, report):
    known = case.startswith("known-")
    mode = 2 if case.endswith("pane") else 1
    initial = reach_game(client)
    identify_scroll = known and report.get("known_preparation") == "identify-scroll"
    verify(client, initial, 0, 1, False, mode, identify_count=1 if identify_scroll else 0)
    if known:
        if identify_scroll:
            report["identification_preparation"] = identify_with_identify_scroll(client, mode)
        else:
            report["intuition_candidates_preselected"] = identify_with_intuition(client, mode)
    selected = read_scroll(client, mode, known)
    report["read_state_version"] = selected["state_version"]
    count = 1 if known else 0
    shown = preview(client, mode, count)
    report["preview_state_version"] = shown["state_version"]
    returned = choose(client, "Back")
    verify_selector(client, returned, mode)
    verify(client, returned, 0, count, True, mode)
    cancelled = action(client, "ui.back")
    if known:
        assert cancelled["phase"] == "player_ready" and not ui(cancelled)["modal"], cancelled
        verify(client, cancelled, 0, 1, True, mode)
        read_scroll(client, mode, True)
        report["known_selector_cancel_preserves_scroll"] = True
    else:
        assert cancelled["phase"] == "awaiting_input" and ui(cancelled)["modal"]
        ui_assertion(client.profile, cancelled, WND_CANCEL)
        assert {"Yes, I'm positive", "No, I changed my mind"} <= {entry.get("label") for entry in cancelled["actions"]}
        verify(client, cancelled, 0, 0, True, mode)
        retained = choose(client, "No, I changed my mind")
        verify_selector(client, retained, mode)
        verify(client, retained, 0, 0, True, mode)
        report["unknown_read_detaches_once"] = True
        report["unknown_cancel_warning_declined_returns_to_selector"] = True
    preview(client, mode, count)
    returned = action(client, "ui.back")
    verify_selector(client, returned, mode)
    verify(client, returned, 0, count, True, mode)
    preview(client, mode, count)
    upgraded = choose(client, "Upgrade")
    verify(client, upgraded, 1, 0, True, mode)
    assert upgraded["phase"] == "player_ready" and not ui(upgraded)["modal"], upgraded
    assert WND_UPGRADE not in ui_assertion(client.profile, upgraded)["window_classes"]
    verify(client, current(client), 1, 0, True, mode)
    waited = action(client, "wait")
    verify(client, waited, 1, 0, True, mode)
    saved, response = execute(client, current(client), "game.save")
    verify(client, saved, 1, 0, True, mode)
    receipts = saved["persistence"]["saves_during_request"]
    assert len(receipts) == 1, receipts
    receipt = receipts[0]
    assert receipt["success"] and receipt["scope_id"] == initial["scope_id"], receipt
    assert receipt["origin_request_id"] == response["id"] and receipt["origin_scope_id"] == initial["scope_id"], receipt
    observed = current(client)
    assert observed["last_save"]["receipt_id"] == receipt["receipt_id"]
    verify(client, observed, 1, 0, True, mode)
    report.update(original_preview_back=True, original_preview_escape=True, armor_upgraded_once=True,
                  seal_upgraded_once=True, single_scroll_consumed_once=True, responsive_after_confirmation=True,
                  save_receipt=receipt, upgrade_response_version=upgraded["state_version"])


def configure_mode(profile, mode):
    configure_test_ui(profile)
    target = profile / "settings.xml"
    document = ET.parse(target).getroot()
    # SPDSettings.KEY_UI_SIZE is full_ui: 2 = InventoryPane, 1 = mixed/WndBag.
    entry = next((item for item in document.findall("entry") if item.get("key") == "full_ui"), None)
    if entry is None:
        entry = ET.SubElement(document, "entry", {"key": "full_ui"})
    entry.text = str(mode)
    header = b'<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">\n'
    target.write_bytes(header + ET.tostring(document, encoding="utf-8"))


def run_case(root, classpath, runtime_id, case, preparation="intuition"):
    assert preparation in {"intuition", "identify-scroll"}, preparation
    profile = root / "desktop-control/build/fixtures" / ("upgrade-preview-" + case + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    configure_mode(profile, 2 if case.endswith("pane") else 1)
    fixture = "itemui:upgrade-known" if case.startswith("known-") else "itemui:upgrade-unknown"
    if case.startswith("known-") and preparation == "identify-scroll":
        fixture = "itemui:upgrade-known-identify"
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", fixture]
    client = FixtureClient(command, profile, verify_gui=True)
    report = {"test_fixture": True, "counts_as_win": False, "fixture": fixture, "case": case,
              "profile": str(profile.relative_to(root)), "runtime_id": runtime_id,
              "cli_language": "en", "gui_language": os.environ.get("SPDCTL_TEST_LANGUAGE", "zh"), "automatic_stale_retries": 0,
              "known_preparation": preparation if case.startswith("known-") else "not_applicable"}
    try:
        hello = client.request("protocol.info")
        assert hello.get("ok"), hello
        report.update(build_id=hello["result"]["build_id"], cli_version=hello["result"]["cli_version"])
        exercise(client, case, report)
        execute(client, current(client), "app.quit")
        client.process.wait(timeout=20)
        assert client.process.returncode == 0, client.process.returncode
        report["ok"] = True
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        if client.process.poll() is None:
            try:
                client.process.stdin.close()
                client.process.wait(timeout=35)
            except Exception as error:
                report.update(ok=False, cleanup_error=repr(error))
                client.process.terminate()
                client.process.wait(timeout=10)
        client.stderr.close()
        client.trace.close()
        report["gui_postconditions_checked"] = client.gui_postconditions_checked
        report["exit_code"] = client.process.poll()
        (profile / "upgrade-preview-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default=",".join(CASES))
    parser.add_argument("--known-preparation", choices=("intuition", "identify-scroll"), default="intuition")
    args = parser.parse_args()
    cases = args.cases.split(",")
    assert cases and all(case in CASES for case in cases), cases
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    print(json.dumps({"started_runtime": runtime_id, "cases": cases}), flush=True)
    reports = [run_case(root, classpath, runtime_id, case, preparation=args.known_preparation) for case in cases]
    target = root / "desktop-control/build/fixtures" / runtime_id / "upgrade-preview-results.json"
    target.write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n")
    if not all(report.get("ok") for report in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
