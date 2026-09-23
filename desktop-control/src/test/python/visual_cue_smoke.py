#!/usr/bin/env python3
"""Actual Boss render paths, public CLI only for control, strict final-response warning checks."""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, assert_gui_environment, close_choices, freeze_runtime, reach_game
from low_frequency_smoke import act
from protocol8 import pages


def visual(state):
    return state["observation"]["visual_cues"]


def kind_cells(state, kind):
    return {cue["cell"] for cue in visual(state)["cues"] if cue["kind"] == kind}


def wait_result(client, kind, fixture):
    result = act(client, "wait")
    assert visual(result)["status"] == "last_observed", visual(result)
    if not kind_cells(result, kind):
        # Diagnostic only. A later query must never make this test pass.
        immediate = visual(result)
        time.sleep(0.2)
        late = client.state()
        raise AssertionError({"new_warning_missing_from_final_action_response": fixture,
                              "final_response_version": result["state_version"], "immediate": immediate,
                              "later_diagnostic_only": visual(late)})
    visible = {tile["cell"] for tile in result["observation"]["map"]["cells"] if tile["visibility"] == "visible"}
    assert all(cue["cell"] in visible for cue in visual(result)["cues"])
    assert all({"kind", "cell"} <= set(cue) <= {"kind", "cell", "source_cell", "direction", "appearance"}
               for cue in visual(result)["cues"]), visual(result)
    assert all("color" not in cue and "opacity" not in cue for cue in visual(result)["cues"])
    return result


def read_visual_events(client, after=0):
    return [event for batch in pages(client, "events.read", after=after, limit=100)
            for event in batch if event["kind"] == "game.visual"]


def test_red(client):
    before = client.state()
    assert not kind_cells(before, "red_target")
    warning = wait_result(client, "red_target", "Yog beam")
    cells = kind_cells(warning, "red_target")
    assert warning["observation"]["hero"]["cell"] in cells, "The actual beam must visually warn the hero's cell"
    events = read_visual_events(client)
    seen = [event for event in events if any(cue["kind"] == "red_target" for cue in event["data"]["cues"])]
    assert seen, events
    historical = seen[-1]
    # Native TargetedCell fades in real render time. No second game action is issued.
    time.sleep(2.3)
    faded = client.state()
    assert not kind_cells(faded, "red_target"), visual(faded)
    assert faded["observation"]["hero"] == warning["observation"]["hero"]
    later_events = read_visual_events(client)
    assert historical in later_events, "A displayed warning must remain in public event history"
    assert any(event["sequence"] > historical["sequence"]
               and not any(cue["kind"] == "red_target" for cue in event["data"]["cues"]) for event in later_events)
    assert visual(faded)["map_context"] == visual(warning)["map_context"]
    return {"native_boss": "YogDzewa", "red_cells_in_final_wait_response": sorted(cells),
            "render_fade_does_not_reconstruct_ai_target_list": True,
            "seen_warning_retained_in_public_history": True, "red_target_absent_in_later_draw_event": True,
            "game_action_after_warning_before_fade": False}


def test_goo(client):
    before = client.state()
    goo = next(entity for entity in before["observation"]["visible_entities"] if entity.get("context_action") == "attack")
    width = before["observation"]["map"]["width"]
    warning = wait_result(client, "black_goo_droplets", "Goo expanded charging particles")
    cells = kind_cells(warning, "black_goo_droplets")
    def distance(cell):
        return max(abs(cell % width - goo["cell"] % width), abs(cell // width - goo["cell"] // width))
    if not any(distance(cell) == 2 for cell in cells):
        time.sleep(0.2)
        raise AssertionError({"final_response_has_only_old_inner_warning": visual(warning),
                              "later_diagnostic_only": visual(client.state())})
    assert all(distance(cell) <= 2 for cell in cells)
    events = read_visual_events(client)
    assert any(any(cue["kind"] == "black_goo_droplets" for cue in event["data"]["cues"]) for event in events)
    # A modal changes available operations but not the known world warning.
    menu = next(node for node in warning["observation"]["ui"]["controls"]
                if str(node.get("shortcut_action", "")).lower() == "back")
    blocked = act(client, "ui.activate", control=menu["id"])
    assert blocked["observation"]["ui"]["modal"] and kind_cells(blocked, "black_goo_droplets")
    reopened = act(client, "ui.back")
    assert not reopened["observation"]["ui"]["modal"] and kind_cells(reopened, "black_goo_droplets")
    assert visual(reopened)["map_context"] == visual(warning)["map_context"]
    resolved = act(client, "wait")
    tail = kind_cells(resolved, "black_goo_droplets")
    time.sleep(1.2)
    faded = client.state()
    assert not kind_cells(faded, "black_goo_droplets"), visual(faded)
    return {"native_boss": "Goo", "displayed_boss_name": goo["name"], "expanded_particle_cells_in_final_wait_response": sorted(cells),
            "outer_ring_actually_drawn": True, "native_tail_cells_after_attack": sorted(tail),
            "warning_cleared_after_native_attack_and_natural_render_fade": True,
            "modal_keeps_known_warning": True,
            "no_pump_or_fov_refresh_from_observer": True}


def test_hidden(client):
    before = client.state()
    assert not kind_cells(before, "black_goo_droplets")
    start = time.monotonic()
    after = act(client, "wait")
    elapsed = time.monotonic() - start
    assert elapsed < 10, {"hidden_source_incorrectly_delayed_response": elapsed}
    assert visual(after)["status"] == "last_observed" and not kind_cells(after, "black_goo_droplets")
    assert after["observation"]["hero"]["cell"] == before["observation"]["hero"]["cell"]
    return {"synthetic_render_source_only": True, "hidden_source_initial_emission_delay_seconds": 60,
            "public_wait_response_seconds": elapsed, "hidden_no_drawable_source_did_not_wait": True,
            "no_hidden_cue_published": True}


def test_frozen(client):
    def frozen(state):
        return any(buff.get("name") == "time bubble" for buff in state["observation"]["hero"]["buffs"])
    initial = client.state()
    assert frozen(initial)
    # Let the normal startup grace expire; never assign Game.timeTotal in this live test.
    time.sleep(1.2)
    state = client.state()
    assert frozen(state)
    menu = next(node for node in state["observation"]["ui"]["controls"]
                if str(node.get("shortcut_action", "")).lower() == "back")
    act(client, "ui.activate", control=menu["id"])
    state = client.state()
    settings = next(a for a in state["actions"] if a.get("label") == "Settings")
    state = act(client, "ui.activate", control=settings["control"])
    candidates = [a["control"] for a in state["actions"] if a["action"] == "ui.activate" and not a.get("label")]
    for control in candidates:
        if any(a["action"] == "ui.value" and "Interface Mode" in a.get("label", "") for a in state["actions"]):
            break
        state = act(client, "ui.activate", control=control)
    mode = next(a for a in state["actions"] if a["action"] == "ui.value" and "Interface Mode" in a.get("label", ""))
    old = next(n["value"] for n in state["observation"]["ui"]["controls"] if n.get("id") == mode["control"])
    start = time.monotonic()
    rebuilt = act(client, "ui.value", control=mode["control"], value=0 if old != 0 else 2)
    elapsed = time.monotonic() - start
    assert elapsed < 10 and frozen(rebuilt)
    assert not any(a.get("control") == mode["control"] for a in rebuilt["actions"]), "Actual UI rebuild must retire the old control"
    close_choices(client)
    time.sleep(1.2)
    ready = client.state()
    assert frozen(ready) and visual(ready)["status"] == "last_observed"
    target = next(e for e in ready["observation"]["visible_entities"] if e.get("context_action") == "attack")
    resumed = act(client, "cell.select", cell=target["cell"])
    assert not frozen(resumed), "The native attack must end TimeBubble, without test-side thawing"
    return {"native_freeze": "Swiftthistle.TimeBubble", "normal_scene_startup_grace_preserved": True,
            "ui_rebuild_while_frozen_completed_seconds": elapsed, "controls_rebuilt": True,
            "native_public_attack_ended_freeze_in_final_response": True,
            "old_version_deadlock_reproduced": False, "game_time_or_particles_forced": False}


def test_bomb(client):
    first = wait_result(client, "bomb_smoke", "Tengu native bomb")
    anchor = kind_cells(first, "bomb_countdown_3")
    assert len(anchor) == 1, {"native_first_countdown_missing_in_final_response": visual(first)}
    assert any(n.get("kind") == "floating" and n.get("text") == "3..."
               for n in first["observation"]["ui"]["feedback"]), "Cue must match current feedback"
    smoke = kind_cells(first, "bomb_smoke")
    assert anchor <= smoke
    history = read_visual_events(client)
    shown_three = next(e for e in history if any(c["kind"] == "bomb_countdown_3" for c in e["data"]["cues"]))
    # Original floating text disappears with render time; a later query must not
    # refill it from the still-pending BombAbility timer.
    time.sleep(1.2)
    faded = client.state()
    assert not any(c["kind"].startswith("bomb_countdown_") for c in visual(faded)["cues"])
    assert kind_cells(faded, "bomb_smoke")
    assert shown_three in read_visual_events(client)
    menu = next(n for n in faded["observation"]["ui"]["controls"] if str(n.get("shortcut_action", "")).lower() == "back")
    modal = act(client, "ui.activate", control=menu["id"])
    assert modal["observation"]["ui"]["modal"] and kind_cells(modal, "bomb_smoke")
    restored = act(client, "ui.back")
    assert kind_cells(restored, "bomb_smoke")
    offscreen = act(client, "view.pan", x=5000, y=5000)
    assert kind_cells(offscreen, "bomb_smoke"), visual(offscreen)
    # Keep the exact child wire frame from this pan's own settled observation.
    # This isolated fixture artifact supports a within-frame token contribution
    # check; it is never used to choose another game action.
    pan_wire = bytes(client.last_recv_bytes)
    pan_request = dict(client.last_wire_request)
    pan_frame = json.loads(pan_wire)
    assert pan_frame["v"] == 8 and pan_frame["id"] == client.last_wire_response["id"]
    assert pan_request["id"] == pan_frame["id"] and pan_request["op"] in {"pan", "state"}
    assert pan_request.get("view", "play") == "play" and pan_request.get("src") is not True
    assert pan_frame.get("s") == offscreen["scope_id"] and pan_frame.get("rev") == offscreen["state_version"]
    assert any(cue["kind"] == "bomb_smoke" for cue in pan_frame["data"]["cues"]["cues"]), pan_frame["id"]
    pan_wire_path = client.profile / "offscreen-pan-response.raw"
    pan_wire_path.write_bytes(pan_wire)
    restored = act(client, "view.pan", x=-5000, y=-5000)
    assert kind_cells(restored, "bomb_smoke"), visual(restored)
    numbered = []
    for number in (2, 1):
        state = act(client, "wait")
        assert kind_cells(state, "bomb_countdown_" + str(number)) == anchor, visual(state)
        assert any(n.get("kind") == "floating" and n.get("text") == str(number) + "..."
                   for n in state["observation"]["ui"]["feedback"])
        numbered.append(number)
    exploded = act(client, "wait")
    # Native tail particles are permitted while they still draw, even after the
    # factory changes to BlastParticle. No gameplay action is used to clear them.
    tail = kind_cells(exploded, "bomb_smoke")
    time.sleep(1.2)
    clear = client.state()
    assert not any(c["kind"].startswith("bomb_") for c in visual(clear)["cues"]), visual(clear)
    retained = read_visual_events(client)
    assert all(any(any(c["kind"] == "bomb_countdown_" + str(n) for c in e["data"]["cues"]) for e in retained) for n in (3, 2, 1))
    return {"native_boss": "Tengu", "smoke_cells_in_final_throw_response": sorted(smoke),
            "countdown_anchor_from_draw": next(iter(anchor)), "native_countdowns_in_final_action_responses": [3] + numbered,
            "ui_literals_equal_cue_mapping": True, "faded_countdown_not_reconstructed": True,
            "modal_and_camera_keep_known_warning": True, "native_tail_cells_after_explosion": sorted(tail),
            "offscreen_pan_wire": str(pan_wire_path), "offscreen_pan_wire_request": pan_request,
            "cleared_after_natural_render_fade": True, "all_displayed_numbers_retained_in_history": True}


def test_bomb_hidden(client):
    start = time.monotonic()
    state = act(client, "wait")
    elapsed = time.monotonic() - start
    assert elapsed < 10
    assert not any(c["kind"].startswith("bomb_") for c in visual(state)["cues"]), visual(state)
    assert not any(any(c["kind"].startswith("bomb_") for c in e["data"]["cues"]) for e in read_visual_events(client))
    return {"native_bomb_buff_at_hidden_cell_test_only": True, "response_seconds": elapsed,
            "hidden_smoke_and_number_not_published": True, "hidden_no_drawable_did_not_block": True}


def run_one(root, classpath, runtime_id, name, test=None):
    profile = root / "desktop-control/build/fixtures" / ("visual-" + name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "lowfreq:visual-" + name]
    client = FixtureClient(command, profile)
    report = dict(test_fixture=True, counts_as_win=False, fixture="visual-" + name,
                  profile=str(profile.relative_to(root)), runtime_id=runtime_id,
                  completion_assertions="final_action_response")
    try:
        hello = client.request("protocol.info")
        assert hello["ok"], hello
        report["build_id"] = hello["result"].get("build_id")
        started = reach_game(client, "WARRIOR")
        assert started["observation"]["hero"]["class_name"] == "warrior", "Public descriptions must be English"
        report["gui_environment"] = assert_gui_environment(profile, started)
        report["cli_language"] = "en"
        report["language"] = "zh"
        setup = json.loads((profile / "test_fixture.json").read_text())
        assert setup["fullscreen"] is False, "Real fixture must remain windowed"
        assert setup["language"] == "CHI_SMPL"
        report["window_mode"] = "windowed"
        selected = test or {"red": test_red, "goo": test_goo, "hidden": test_hidden, "frozen": test_frozen,
                            "bomb": test_bomb, "bomb-hidden": test_bomb_hidden}[name]
        report["evidence"] = selected(client)
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
        (profile / "visual-cue-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="red,goo,hidden,frozen")
    args = parser.parse_args()
    cases = args.cases.split(",")
    assert all(case in {"red", "goo", "hidden", "frozen", "bomb", "bomb-hidden"} for case in cases)
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    results = [run_one(root, classpath, runtime_id, name) for name in cases]
    report = dict(test_fixture=True, counts_as_win=False, runtime_id=runtime_id,
                  total=len(results), passed=sum(row["ok"] for row in results), results=results)
    (root / "desktop-control/build/fixtures" / runtime_id / "visual-cue-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    if not all(row["ok"] for row in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
