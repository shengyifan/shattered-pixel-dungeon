#!/usr/bin/env python3
"""Native Nova/BlastWave draws in fresh fixtures; public responses and history are the evidence.

The first game observation is retained from its own action/settlement response.
Later state queries check persistence or disappearance; they cannot repair a missing
initial halo. Synthetic renderer state is limited to the explicit halo-edge fixture.
"""
import argparse
import copy
import json
import math
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, act, assert_gui_environment, close_choices, freeze_runtime, norm
from protocol7 import pages


CASES = ("supernova-halo", "supernova-halo-edge", "blast-radius-1", "blast-radius-3", "blast-radius-6")
APPEARANCE_FIELDS = {"shape", "center_world", "size", "radius_world", "scale", "angle", "alpha_transform"}


class RadialFixtureClient(FixtureClient):
    """Check the same native GUI checkpoints after exit, outside the continuous pulse writer.

    Original response frames are frozen here. This changes neither the checkpoint
    predicate nor the observed revision, and never substitutes a later state.
    """
    def __init__(self, command, profile):
        self.gui_frames = []
        super().__init__(command, profile, verify_gui=False)

    def request(self, op, args=None, **kwargs):
        result = super().request(op, args, **kwargs)
        data = result.get("result")
        quitting = op == "action.execute" and isinstance(args, dict) and args.get("action") == "app.quit"
        if not quitting and result.get("ok") and isinstance(data, dict) and "observation" in data:
            self.gui_frames.append(copy.deepcopy(data))
        return result

    def verify_original_gui_frames(self):
        assert self.process.poll() == 0, "Deferred GUI assertions require a clean child exit"
        for state in self.gui_frames:
            assert_gui_environment(self.profile, state)
            self.gui_postconditions_checked += 1


def reach_game_response(client):
    """Retain the entry action's own final frame, without the legacy post-entry refresh."""
    state = client.state()
    selected = False
    for _ in range(35):
        observation = state["observation"]
        if observation.get("scene") == "game":
            assert observation["hero"]["class"] == "warrior", observation["hero"]
            return state
        ui = observation.get("ui", {})
        options = [action for action in state["actions"] if action["action"] == "ui.activate"]
        if ui.get("scene") == "HeroSelectScene" and not selected and not ui.get("modal"):
            choices = [action for action in options if norm(action.get("label", "")) == "warrior"]
            assert len(choices) == 1, options
            state = act(client, "ui.activate", control=choices[0]["control"])
            selected = True
        elif ui.get("scene") == "HeroSelectScene" and ui.get("modal") and selected:
            state = act(client, "ui.back")
        else:
            choice = next((action for label in ("Continue", "Enter the Dungeon", "Play", "New Game", "Start")
                           for action in options if action.get("label") == label), None)
            if choice is not None:
                state = act(client, "ui.activate", control=choice["control"])
            elif any(action["action"] == "ui.reveal" for action in state["actions"]):
                state = act(client, "ui.reveal")
            else:
                raise AssertionError({"cannot_start_radial_fixture": ui, "actions": state["actions"]})
    raise AssertionError("Did not reach a playable radial fixture")


def cues(state, kind=None):
    observed = state["observation"]["visual_cues"]["cues"]
    return observed if kind is None else [cue for cue in observed if cue["kind"] == kind]


def events(client):
    return [event for page in pages(client, "events.read") for event in page]


def drawn_samples(history, kind):
    return [{"event_sequence": event["sequence"], "cue": cue}
            for event in history if event["kind"] == "game.visual"
            for cue in event["data"]["cues"] if cue["kind"] == kind]


def pair(value):
    assert isinstance(value, list) and len(value) == 2, value
    assert all(type(number) in (int, float) and math.isfinite(number) for number in value), value
    return value


def close(actual, expected):
    assert math.isclose(actual, expected, rel_tol=2e-5, abs_tol=2e-5), (actual, expected)


def check_appearance(cue, shape):
    assert set(cue) <= {"kind", "cell", "color", "appearance"}, cue
    appearance = cue["appearance"]
    assert set(appearance) == APPEARANCE_FIELDS, appearance
    assert appearance["shape"] == shape and appearance["angle"] == 0, appearance
    pair(appearance["center_world"])
    width, height = pair(appearance["size"])
    radius_x, radius_y = pair(appearance["radius_world"])
    scale_x, scale_y = pair(appearance["scale"])
    multiply, add = pair(appearance["alpha_transform"])
    assert min(width, height, radius_x, radius_y, scale_x, scale_y) > 0, appearance
    extent, radius = (257, 128) if shape == "halo" else (16, 8)
    close(width, extent * scale_x); close(height, extent * scale_y)
    close(radius_x, radius * scale_x); close(radius_y, radius * scale_y)
    return appearance, multiply, add


def check_current_footprint(state, cue):
    """Use this frame's public map, never another revision or a fixture's hidden level."""
    grid = state["observation"]["map"]
    visible = {cell["cell"] for cell in grid["cells"] if cell["visibility"] == "visible"}
    appearance = cue["appearance"]
    x, y = appearance["center_world"]; width, height = appearance["size"]
    assert cue["cell"] == math.floor(y / 16) * grid["width"] + math.floor(x / 16), cue
    for row in range(math.floor((y - height / 2) / 16), math.ceil((y + height / 2) / 16)):
        for column in range(math.floor((x - width / 2) / 16), math.ceil((x + width / 2) / 16)):
            assert row * grid["width"] + column in visible, {"unseen_draw_footprint": cue, "column": column, "row": row}


def assert_retained(before, after):
    indexed = {event["sequence"]: event for event in after}
    assert all(indexed.get(event["sequence"]) == event for event in before), "Observed history changed after the draw faded"


def check_halo(client, name, initial):
    initial_halos = cues(initial, "supernova_halo")
    assert initial_halos, {"missing_from_entry_response": name, "cues": cues(initial)}
    for cue in initial_halos:
        appearance, multiply, add = check_appearance(cue, "halo")
        check_current_footprint(initial, cue)
        if name.endswith("-edge"):
            assert (multiply, add) == (-1, 1), appearance
        else:
            assert multiply > 0 and add == 0, appearance
    before = events(client)
    history = drawn_samples(before, "supernova_halo")
    assert history, "Initial native halo draw is absent from public history"

    if name == "supernova-halo":
        assert any(event["kind"] == "game.visual" and any(cue["kind"] == "red_target" for cue in event["data"]["cues"])
                   for event in before), "The native tick's temporary target markers were not observed"
        countdown = [entry for event in before if event["kind"] == "game.floating_text"
                     for entry in event["data"]["entries"] if entry.get("text") == "10..."]
        assert countdown, "The native tick's temporary countdown text was not observed"
    else:
        countdown = []

    # TargetedCell fades in two seconds; FloatingText fades in one. No game turn is advanced.
    time.sleep(2.3)
    persistent = client.state()
    surviving = cues(persistent, "supernova_halo")
    assert surviving, {"persistent_native_halo_missing": cues(persistent)}
    assert not cues(persistent, "red_target"), cues(persistent)
    assert not any(node.get("presentation") == "floating_text" and node.get("text") == "10..."
                   for node in persistent["observation"]["ui"]["controls"]), persistent["observation"]["ui"]
    for cue in surviving:
        _, multiply, add = check_appearance(cue, "halo")
        check_current_footprint(persistent, cue)
        if name.endswith("-edge"):
            assert (multiply, add) == (-1, 1), cue
    after = events(client); assert_retained(before, after)

    menu = next(node for node in persistent["observation"]["ui"]["controls"]
                if str(node.get("shortcut_action", "")).lower() == "back")
    modal = act(client, "ui.activate", control=menu["id"])
    assert modal["observation"]["ui"]["modal"] and cues(modal) == [], cues(modal)
    restored = act(client, "ui.back")
    assert cues(restored, "supernova_halo"), {"missing_from_restore_action_response": cues(restored)}
    for cue in cues(restored, "supernova_halo"):
        check_current_footprint(restored, cue)

    return {"kind": "supernova_halo", "initial_response_cues": initial_halos,
            "persistent_response_revision": persistent["state_version"], "persistent_response_cues": surviving,
            "native_countdown_history": countdown, "initial_halo_history_count": len(history),
            "transient_draw_history_retained": True, "modal_suppresses_current_cues": True,
            "restored_in_action_response": True, "late_state_not_used_to_repair_missing_initial_warning": True,
            "native_gradient_sample_checked_by_fixture": name.endswith("-edge"),
            "evidence_kind": "explicit_native_renderer_alpha_boundary" if name.endswith("-edge") else "native_tracker_tick_and_renderer"}


def check_wave(client, name, initial):
    configured = int(name.rsplit("-", 1)[1])  # Fixture condition only; never read from a game observation.
    before = events(client)
    samples = drawn_samples(before, "blast_wave")
    assert samples, {"transient_native_draw_missing_from_history": name, "entry_cues": cues(initial)}
    for sample in samples:
        appearance, multiply, add = check_appearance(sample["cue"], "ring")
        assert 0 < multiply < 1 and add == 0, sample
        # Validate the actual native update's scale/alpha relationship. The report
        # retains only the sampled current dimensions, never a fabricated final frame.
        for scale in appearance["scale"]:
            close(scale, (1 - multiply) * configured)
    for cue in cues(initial, "blast_wave"):
        check_current_footprint(initial, cue)
    time.sleep(.3)
    faded = client.state()
    assert not cues(faded, "blast_wave"), cues(faded)
    after = events(client); assert_retained(before, after)
    return {"kind": "blast_wave", "native_draw_samples": samples,
            "actual_sample_count": len(samples), "drawn_width_range": [min(sample["cue"]["appearance"]["size"][0] for sample in samples),
                                                                       max(sample["cue"]["appearance"]["size"][0] for sample in samples)],
            "configured_maximum_absent_from_wire": True, "faded_from_current_state": True,
            "transient_draw_history_retained": True, "evidence_kind": "prepared_native_blast_and_renderer",
            "late_state_not_used_to_repair_missing_initial_warning": True}


def run_one(root, classpath, runtime_id, name):
    profile = root / "desktop-control/build/fixtures" / ("radialvisual-" + name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "combatvisual:" + name]
    client = RadialFixtureClient(command, profile)
    report = {"test_fixture": True, "counts_as_win": False, "fixture": name,
              "runtime_id": runtime_id, "profile": str(profile.relative_to(root))}
    try:
        hello = client.request("protocol.info"); assert hello["ok"], hello
        report.update(build_id=hello["result"]["build_id"], cli_version=hello["result"]["cli_version"])
        initial = reach_game_response(client)
        report["initial_frame_identity"] = {key: client.last_wire_response.get(key) for key in ("v", "id", "s", "rev", "st")}
        report["initial_frame_request"] = dict(client.last_wire_request)
        report["evidence"] = check_halo(client, name, initial) if name.startswith("supernova-") else check_wave(client, name, initial)
        report["ok"] = True
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        try:
            close_choices(client); client.finish()
        except Exception as error:
            report.update(ok=False, cleanup_error=repr(error))
            if client.process.poll() is None:
                client.process.terminate(); client.process.wait(timeout=10)
        if client.process.poll() == 0:
            try:
                client.verify_original_gui_frames()
            except Exception as error:
                report.update(ok=False, gui_postcondition_error=repr(error), gui_postcondition_traceback=traceback.format_exc())
        client.stderr.close(); client.trace.close()
        report.update(gui_postconditions_checked=client.gui_postconditions_checked, exit_code=client.process.poll(),
                      gui_assertion_timing="original_response_frames_after_clean_child_exit")
        (profile / "radial-visual-result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default=",".join(CASES))
    args = parser.parse_args()
    cases = args.cases.split(",")
    if not cases or any(name not in CASES for name in cases):
        parser.error("Unknown radial fixture case")
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    results = [run_one(root, classpath, runtime_id, name) for name in cases]
    report = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id,
              "total": len(results), "passed": sum(result["ok"] for result in results), "results": results}
    destination = root / "desktop-control/build/fixtures" / runtime_id / "radial-visual-results.json"
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"report": str(destination), "passed": report["passed"], "total": report["total"]}), flush=True)
    if not all(result["ok"] for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
