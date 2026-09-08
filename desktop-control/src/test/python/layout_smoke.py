#!/usr/bin/env python3
"""Real settings/layout UI through public NDJSON; no pixels, keys, mouse, or private reads.

The test-only initial bootstrap starts an ordinary Warrior and saves an isolated
profile with the tutorial preference completed. The measured source/package flow
then resumes that profile through the production launcher and uses only public UI.
"""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, close_choices, freeze_runtime, reach_game as start_fixture
from low_frequency_smoke import act, click
from machine_smoke import reach_game as resume_game


def actions(state, kind="ui.activate"):
    return [a for a in state["actions"] if a["action"] == kind]


def nodes(state):
    return state["observation"]["ui"]["controls"]


def choose(client, label):
    return click(client, lambda value: value.lower() == label.lower())


def slider(state, title):
    return next((a for a in actions(state, "ui.value") if title.lower() in a.get("label", "").lower()), None)


def slider_value(state, control):
    return next(n["value"] for n in nodes(state) if n["id"] == control)


def open_interface_settings(client):
    state = client.state()
    if not any("interface settings" in n.get("text", "").lower() for n in nodes(state)):
        if not state["observation"]["ui"]["modal"]:
            choose(client, "menu")
            choose(client, "settings")
        state = client.state()
        # The original IconTabs have no text labels. Discover their panels by
        # activating current exposed controls and reading the resulting UI.
        candidates = [a["control"] for a in actions(state) if not a.get("label")]
        for control in candidates:
            state = client.state()
            if any("interface settings" in n.get("text", "").lower() for n in nodes(state)):
                break
            assert any(a.get("control") == control for a in actions(state))
            act(client, "ui.activate", control=control)
        state = client.state()
    assert any("interface settings" in n.get("text", "").lower() for n in nodes(state)), state["actions"]
    return state


def assert_old_intent_rejected(client, old_state, old_control, old_value):
    current = client.state()
    response = client.request("action.execute", {"action": "ui.value", "control": old_control, "value": old_value},
                              version=old_state["state_version"], request_id="old-version-" + uuid.uuid4().hex)
    assert response.get("error", {}).get("code") == "STALE_STATE", response
    assert "result" not in response, response
    fresh = client.state()
    assert not any(a.get("control") == old_control for a in fresh["actions"]), "Scene reset must retire the actual old control"
    retired_id = "retired-control-" + uuid.uuid4().hex
    retired = client.request("action.execute", {"action": "ui.value", "control": old_control, "value": old_value},
                             version=fresh["state_version"], request_id=retired_id)
    assert retired.get("error", {}).get("code") in {"INVALID_ARGUMENT", "ACTION_UNAVAILABLE"}, retired
    assert "result" not in retired, retired
    duplicate = client.request("action.execute", {"action": "ui.value", "control": old_control, "value": old_value}, request_id=retired_id)
    assert duplicate.get("error", {}).get("code") == "DUPLICATE_REQUEST_ID", duplicate
    after = client.state()
    assert after["scope_id"] == current["scope_id"]
    for key in ("hero", "inventory", "map", "visible_entities"):
        assert after["observation"][key] == current["observation"][key], {"rejected_layout_input_changed_game": key}
    return {"old_version_rejected": True, "retired_control_rejected_with_current_version": True, "rejected_id_burned": True}


def change_slider(client, title, value):
    before = open_interface_settings(client)
    item = slider(before, title)
    assert item, {"actual_settings_do_not_expose_requested_slider": title, "actions": before["actions"]}
    old_value = slider_value(before, item["control"])
    if old_value == value:
        return {"unchanged": True, "value": value}
    changed = act(client, "ui.value", control=item["control"], value=value)
    assert changed["scope_id"] == before["scope_id"]
    after = open_interface_settings(client)
    replacement = slider(after, title)
    assert replacement and replacement["control"] != item["control"]
    assert slider_value(after, replacement["control"]) == value
    evidence = assert_old_intent_rejected(client, before, item["control"], old_value)
    return dict(title=title, previous=old_value, value=value, controls_rebuilt=True, **evidence)


def inventory_counts(state):
    return [(i["name"], i["quantity"]) for i in state["observation"]["inventory"]]


def item_window_and_cancel(client, mode):
    initial = client.state()
    assert not initial["observation"]["ui"]["modal"]
    has_food = lambda s: any("ration of food" == a.get("label", "").lower() for a in actions(s))
    if mode == 2:
        # Full mode uses the actual Inventory toolbar callback to toggle its pane.
        original_visibility = has_food(initial)
        toggled = choose(client, "inventory")
        assert not toggled["observation"]["ui"]["modal"]
        assert has_food(toggled) != original_visibility
        if not has_food(toggled):
            toggled = choose(client, "inventory")
        assert has_food(toggled)
        presentation = "InventoryPane"
    else:
        opened = choose(client, "inventory")
        assert opened["observation"]["ui"]["modal"] and has_food(opened)
        act(client, "ui.back")
        assert not client.state()["observation"]["ui"]["modal"]
        choose(client, "inventory")
        presentation = "WndBag"
    choose(client, "ration of food")
    targeting = choose(client, "throw")
    assert any(a["action"] == "cell.cancel" for a in targeting["actions"])
    cancelled = act(client, "cell.cancel")
    assert inventory_counts(cancelled) == inventory_counts(initial)
    assert cancelled["observation"]["hero"]["cell"] == initial["observation"]["hero"]["cell"]
    close_choices(client)
    return {"interface_mode": mode, "presentation": presentation, "current_controls_opened_item": True,
            "throw_selector_cancelled": True, "inventory_unchanged": True}


def check_view_commands(client):
    initial = client.state()
    zoom = next(a for a in initial["actions"] if a["action"] == "view.zoom")
    tested = []
    for value in dict.fromkeys((zoom["minimum"], zoom["maximum"])):
        after = act(client, "view.zoom", zoom=value)
        assert after["observation"]["hero"]["cell"] == initial["observation"]["hero"]["cell"]
        tested.append(value)
    for x, y in ((8, -6), (-8, 6)):
        after = act(client, "view.pan", x=x, y=y)
        assert after["observation"]["hero"]["cell"] == initial["observation"]["hero"]["cell"]
    return {"accepted_zoom_values_from_public_range": tested, "pan_units": "map_view",
            "pan_deltas": [[8, -6], [-8, 6]], "hero_did_not_move": True,
            "camera_value_not_exposed_by_current_public_observation": True}


def use_item_after_rebuild(client):
    initial = client.state()
    if not any(a.get("label", "").lower() == "ration of food" for a in actions(initial)):
        choose(client, "inventory")
    choose(client, "ration of food")
    consumed = choose(client, "eat")
    before = sum(i["quantity"] for i in initial["observation"]["inventory"] if i["name"] == "ration of food")
    after = sum(i["quantity"] for i in consumed["observation"]["inventory"] if i["name"] == "ration of food")
    assert after == before - 1
    assert consumed["observation"]["hero"]["hp"] > 0
    return {"actual_food_use_via_current_controls": True, "quantity_before": before, "quantity_after": after}


def bootstrap(classpath, profile):
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "class:WARRIOR"]
    client = FixtureClient(command, profile)
    try:
        assert client.request("protocol.info")["ok"]
        start_fixture(client, "WARRIOR")
        client.finish()
    finally:
        if client.process.poll() is None:
            client.process.terminate(); client.process.wait(timeout=10)
        client.stderr.close(); client.trace.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--launcher", help="Optional real packaged spdctl executable for the measured flow")
    parser.add_argument("--expected-cli", help="Require this CLI version in the measured flow")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    profile = root / "desktop-control/build/fixtures" / ("layout-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    bootstrap(classpath, profile)
    report = dict(test_fixture=True, counts_as_win=False, profile=str(profile.relative_to(root)), runtime_id=runtime_id,
                  runtime_id_role="bootstrap_source_classpath" if args.launcher else "measured_source_classpath",
                  measured_launcher="packaged" if args.launcher else "source", mode_changes=[], inventory_checks=[])
    if args.launcher:
        report["launcher_path"] = str(Path(args.launcher).resolve())
    (profile / "test_fixture.json").write_text(json.dumps(dict(report, setup="Normal saved Warrior; test-only menu preferences/unlocks, no run stat edits"), indent=2))
    command = [str(Path(args.launcher).resolve())] if args.launcher else ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"]
    client = FixtureClient(command, profile)
    try:
        hello = client.request("protocol.info")
        assert hello["ok"], hello
        report["cli_version"] = hello["result"]["cli_version"]
        if args.expected_cli:
            assert report["cli_version"] == args.expected_cli, hello
        initial = resume_game(client, resume=True)
        report["scope_id"] = initial["scope_id"]
        interface = open_interface_settings(client)
        mode = slider(interface, "interface mode")
        assert mode, "No Interface Mode option on this runtime/window; layout modes are not verified"
        original_mode = slider_value(interface, mode["control"])
        report["original_interface_mode"] = original_mode
        for value in (0, 1, 2):
            report["mode_changes"].append(change_slider(client, "interface mode", value))
            act(client, "ui.back")
            report["inventory_checks"].append(item_window_and_cancel(client, value))
        interface = open_interface_settings(client)
        scale = slider(interface, "interface scale")
        if scale and scale["range"][0] < scale["range"][1]:
            original_scale = slider_value(interface, scale["control"])
            alternate = scale["range"][0] if original_scale != scale["range"][0] else scale["range"][1]
            report["scale_changes"] = [change_slider(client, "interface scale", alternate), change_slider(client, "interface scale", original_scale)]
        else:
            report["scale_changes"] = []
            report["scale_not_offered_by_this_runtime"] = True
        act(client, "ui.back")
        report["view"] = check_view_commands(client)
        report["inventory_checks"].append(item_window_and_cancel(client, 2))
        report["use_item"] = use_item_after_rebuild(client)
        assert client.scope == report["scope_id"]
        report["no_separate_backpack_layout_setting"] = "Existing Interface Mode selects persistent pane versus bag window"
        report["unlabelled_settings_tabs_discovered_via_public_panels"] = True
        report["ok"] = True
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        try:
            close_choices(client)
            client.finish()
        except Exception as error:
            report.update(ok=False, cleanup_error=repr(error))
            if client.process.poll() is None:
                client.process.terminate(); client.process.wait(timeout=10)
        client.stderr.close(); client.trace.close()
        (profile / "layout-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        (root / "desktop-control/build/fixtures" / runtime_id / "layout-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    if not report["ok"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
