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


def actions(state, kind="click"):
    return [a for a in state["acts"] if a["op"] == kind]


def nodes(state):
    return state["ui"]["nodes"]


def choose(client, label):
    return click(client, lambda value: value.lower() == label.lower())


def slider(state, title):
    return next((a for a in actions(state, "value") if title.lower() in a.get("label", "").lower()), None)


def slider_value(state, control):
    return next(n["value"] for n in nodes(state) if n.get("id") == control)


def open_interface_settings(client):
    state = client.state()
    if not any("interface settings" in n.get("text", "").lower() for n in nodes(state)):
        if not state["ui"]["modal"]:
            choose(client, "menu")
            choose(client, "settings")
        state = client.state()
        # The original IconTabs have no text labels. Discover their panels by
        # activating current exposed controls and reading the resulting UI.
        candidates = [a["ctl"] for a in actions(state) if not a.get("label")]
        for control in candidates:
            state = client.state()
            if any("interface settings" in n.get("text", "").lower() for n in nodes(state)):
                break
            assert any(a.get("ctl") == control for a in actions(state))
            act(client, "click", ctl=control)
        state = client.state()
    assert any("interface settings" in n.get("text", "").lower() for n in nodes(state)), state["acts"]
    return state


def assert_old_intent_rejected(client, old_state, old_control, old_value):
    current = client.state()
    response = client.request_action({"op": "value", "ctl": old_control, "value": old_value},
                              version=old_state["rev"], request_id="old-version-" + uuid.uuid4().hex)
    assert response.get("err") == "STALE_STATE", response
    assert "data" not in response, response
    fresh = client.state()
    assert not any(a.get("ctl") == old_control for a in fresh["acts"]), "Scene reset must retire the actual old control"
    retired_id = "retired-control-" + uuid.uuid4().hex
    retired = client.request_action({"op": "value", "ctl": old_control, "value": old_value},
                             version=fresh["rev"], request_id=retired_id)
    assert retired.get("err") in {"INVALID_ARGUMENT", "ACTION_UNAVAILABLE"}, retired
    assert "data" not in retired, retired
    duplicate = client.request_action({"op": "value", "ctl": old_control, "value": old_value}, request_id=retired_id)
    assert duplicate.get("err") == "DUPLICATE_REQUEST_ID", duplicate
    after = client.state()
    assert after["s"] == current["s"]
    for key in ("hero", "inv", "map", "entities"):
        assert after[key] == current[key], {"rejected_layout_input_changed_game": key}
    return {"old_version_rejected": True, "retired_control_rejected_with_current_version": True, "rejected_id_burned": True}


def change_slider(client, title, value):
    before = open_interface_settings(client)
    item = slider(before, title)
    assert item, {"actual_settings_do_not_expose_requested_slider": title, "acts": before["acts"]}
    old_value = slider_value(before, item["ctl"])
    if old_value == value:
        return {"unchanged": True, "value": value}
    changed = act(client, "value", ctl=item["ctl"], value=value)
    assert changed["s"] == before["s"]
    after = open_interface_settings(client)
    replacement = slider(after, title)
    assert replacement and replacement["ctl"] != item["ctl"]
    assert slider_value(after, replacement["ctl"]) == value
    evidence = assert_old_intent_rejected(client, before, item["ctl"], old_value)
    return dict(title=title, previous=old_value, value=value, controls_rebuilt=True, **evidence)


def inventory_counts(state):
    return [(i["name"], i["qty"]) for i in state["inv"]]


def item_window_and_cancel(client, mode):
    initial = client.state()
    assert not initial["ui"]["modal"]
    has_food = lambda s: any("ration of food" == a.get("label", "").lower() for a in actions(s))
    if mode == 2:
        # Full mode uses the actual Inventory toolbar callback to toggle its pane.
        original_visibility = has_food(initial)
        toggled = choose(client, "inventory")
        assert not toggled["ui"]["modal"]
        assert has_food(toggled) != original_visibility
        if not has_food(toggled):
            toggled = choose(client, "inventory")
        assert has_food(toggled)
        presentation = "InventoryPane"
    else:
        opened = choose(client, "inventory")
        assert opened["ui"]["modal"] and has_food(opened)
        act(client, "back")
        assert not client.state()["ui"]["modal"]
        choose(client, "inventory")
        presentation = "WndBag"
    choose(client, "ration of food")
    targeting = choose(client, "throw")
    assert any(a["op"] == "untarget" for a in targeting["acts"])
    cancelled = act(client, "untarget")
    assert inventory_counts(cancelled) == inventory_counts(initial)
    assert cancelled["hero"]["cell"] == initial["hero"]["cell"]
    close_choices(client)
    return {"interface_mode": mode, "presentation": presentation, "current_controls_opened_item": True,
            "throw_selector_cancelled": True, "inventory_unchanged": True}


def check_view_commands(client):
    initial = client.state()
    zoom = next(a for a in initial["acts"] if a["op"] == "zoom")
    tested = []
    for value in dict.fromkeys((zoom["min"], zoom["max"])):
        after = act(client, "zoom", zoom=value)
        assert after["hero"]["cell"] == initial["hero"]["cell"]
        tested.append(value)
    for x, y in ((8, -6), (-8, 6)):
        after = act(client, "pan", x=x, y=y)
        assert after["hero"]["cell"] == initial["hero"]["cell"]
    return {"accepted_zoom_values_from_public_range": tested, "pan_units": "map_view",
            "pan_deltas": [[8, -6], [-8, 6]], "hero_did_not_move": True,
            "camera_value_not_exposed_by_current_public_observation": True}


def use_item_after_rebuild(client):
    initial = client.state()
    if not any(a.get("label", "").lower() == "ration of food" for a in actions(initial)):
        choose(client, "inventory")
    choose(client, "ration of food")
    consumed = choose(client, "eat")
    before = sum(i["qty"] for i in initial["inv"] if i["name"] == "ration of food")
    after = sum(i["qty"] for i in consumed["inv"] if i["name"] == "ration of food")
    assert after == before - 1
    assert consumed["hero"]["hp"] > 0
    return {"actual_food_use_via_current_controls": True, "quantity_before": before, "quantity_after": after}


def bootstrap(classpath, profile):
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "class:WARRIOR"]
    client = FixtureClient(command, profile)
    try:
        assert ("err" not in client.request("info"))
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
        hello = client.request("info")
        assert ("err" not in hello), hello
        report["cli_version"] = hello["data"]["cli_version"]
        if args.expected_cli:
            assert report["cli_version"] == args.expected_cli, hello
        initial = resume_game(client, resume=True)
        report["s"] = initial["s"]
        interface = open_interface_settings(client)
        mode = slider(interface, "interface mode")
        assert mode, "No Interface Mode option on this runtime/window; layout modes are not verified"
        original_mode = slider_value(interface, mode["ctl"])
        report["original_interface_mode"] = original_mode
        for value in (0, 1, 2):
            report["mode_changes"].append(change_slider(client, "interface mode", value))
            act(client, "back")
            report["inventory_checks"].append(item_window_and_cancel(client, value))
        interface = open_interface_settings(client)
        scale = slider(interface, "interface scale")
        if scale and scale["range"][0] < scale["range"][1]:
            original_scale = slider_value(interface, scale["ctl"])
            alternate = scale["range"][0] if original_scale != scale["range"][0] else scale["range"][1]
            report["scale_changes"] = [change_slider(client, "interface scale", alternate), change_slider(client, "interface scale", original_scale)]
        else:
            report["scale_changes"] = []
            report["scale_not_offered_by_this_runtime"] = True
        act(client, "back")
        report["view"] = check_view_commands(client)
        report["inventory_checks"].append(item_window_and_cancel(client, 2))
        report["use_item"] = use_item_after_rebuild(client)
        assert client.scope == report["s"]
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
