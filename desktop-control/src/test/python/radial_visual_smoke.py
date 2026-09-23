#!/usr/bin/env python3
"""Native Nova/BlastWave draws in fresh fixtures; public responses and history are the evidence.

The first game observation is retained from its own action/settlement response.
Later state queries check persistence or disappearance; they cannot repair a missing
initial halo. Synthetic renderer state is limited to the explicit halo-edge fixture.
"""
import argparse
import copy
import json
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, act, assert_gui_environment, close_choices, freeze_runtime, norm
from protocol8 import pages


CASES = ("supernova-halo", "supernova-halo-edge", "blast-radius-1", "blast-radius-3", "blast-radius-6")


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


def check_appearance(state, cue, shape):
    """The disclosed cells are the known visual extent, not an attack prediction."""
    assert {"kind", "cell", "appearance"} <= set(cue) <= {"kind", "cell", "appearance"}, cue
    appearance = cue["appearance"]
    assert appearance["shape"] == shape and appearance["coverage"] == "visual_extent", appearance
    cells = appearance["cells"]
    assert isinstance(cells, list) and cells and all(type(cell) is int for cell in cells), appearance
    assert cue["cell"] in cells and len(cells) == len(set(cells)), cue
    visible = {cell["cell"] for cell in state["observation"]["map"]["cells"]
               if cell["visibility"] == "visible"}
    assert set(cells) <= visible, {"unknown_visual_cells": sorted(set(cells) - visible)}
    assert not {"center_world", "size", "radius_world", "scale", "angle", "alpha_transform"} & set(appearance)
    if "partial" in appearance:
        assert appearance["partial"] is True
    return cells


def assert_retained(before, after):
    indexed = {event["sequence"]: event for event in after}
    assert all(indexed.get(event["sequence"]) == event for event in before), "Observed history changed after the cue faded"


def check_halo(client, name, initial):
    initial_halos = cues(initial, "supernova_halo")
    assert initial_halos, {"missing_from_entry_response": name, "cues": cues(initial)}
    known_sets = [check_appearance(initial, cue, "halo") for cue in initial_halos]
    # "-edge" prepares the native inverted-alpha gradient edge, not a fog boundary.
    # The Java fixture verifies that renderer condition; only observed FOV clipping sets partial.
    before = events(client)
    history = drawn_samples(before, "supernova_halo")
    assert history, "Initial native halo is absent from public history"
    if name == "supernova-halo":
        assert any(event["kind"] == "game.visual" and any(cue["kind"] == "red_target"
                   for cue in event["data"]["cues"]) for event in before), before
        countdown = [entry for event in before if event["kind"] == "game.floating_text"
                     for entry in event["data"]["entries"] if entry.get("text") == "10..."]
        assert countdown, "Native countdown occurrence missing"
    else:
        countdown = []
    time.sleep(2.3)
    persistent = client.state()
    surviving = cues(persistent, "supernova_halo")
    assert surviving, {"persistent_native_halo_missing": cues(persistent)}
    assert not cues(persistent, "red_target"), cues(persistent)
    assert not any(entry.get("kind") == "floating" and entry.get("text") == "10..."
                   for entry in persistent["observation"]["ui"].get("feedback", []))
    for cue in surviving:
        check_appearance(persistent, cue, "halo")
    # Once the initial target/text have faded, observe native halo pulsation without
    # advancing actors. Keep the original frames in public-trace.jsonl for diagnosis.
    passive = [persistent]
    passive_rows = [{"revision": persistent["state_version"], "halos": surviving}]
    for _ in range(8):
        time.sleep(.07)
        sample = client.state()
        passive.append(sample)
        passive_rows.append({"revision": sample["state_version"], "halos": cues(sample, "supernova_halo")})
    unchanged_world = all(sample["observation"][key] == persistent["observation"][key]
                          for sample in passive for key in ("hero", "map", "inventory", "visible_entities"))
    passive_report = {"test_fixture": True, "no_actions": True, "same_world": unchanged_world,
                      "revisions": [sample["state_version"] for sample in passive], "samples": passive_rows}
    (client.profile / "halo-passive-observations.json").write_text(json.dumps(passive_report, indent=2) + "\n")
    assert unchanged_world, {"read_only_halo_probe_changed_world": passive_report}
    assert len(set(passive_report["revisions"])) == 1, {"halo_pulse_changed_intent": passive_report}
    persistent = passive[-1]
    after = events(client); assert_retained(before, after)
    menu = next(node for node in persistent["observation"]["ui"]["controls"]
                if str(node.get("shortcut_action", "")).lower() == "back")
    modal = act(client, "ui.activate", control=menu["id"])
    assert modal["observation"]["ui"]["modal"] and cues(modal, "supernova_halo"), cues(modal)
    restored = act(client, "ui.back")
    assert cues(restored, "supernova_halo"), cues(restored)
    for cue in cues(restored, "supernova_halo"):
        check_appearance(restored, cue, "halo")
    return {"kind": "supernova_halo", "initial_known_cell_sets": known_sets,
            "persistent_response_revision": persistent["state_version"],
            "native_countdown_history": countdown, "initial_halo_history_count": len(history),
            "transient_history_retained": True, "modal_keeps_known_extent": True,
            "passive_halo_revisions": passive_report["revisions"],
            "inverted_alpha_edge_fixture": name.endswith("-edge"),
            "partial_known_extent": any(cue["appearance"].get("partial") is True for cue in initial_halos),
            "late_state_not_used_to_repair_missing_initial_warning": True}


def check_wave(client, name, initial):
    before = events(client)
    samples = drawn_samples(before, "blast_wave")
    assert samples, {"transient_native_draw_missing_from_history": name, "entry_cues": cues(initial)}
    # Event snapshots contain only currently known cells. The fixture's radius is
    # not copied into the wire and these cells are not a hidden blast area.
    event_cells = []
    visible = {cell["cell"] for cell in initial["observation"]["map"]["cells"]
               if cell["visibility"] == "visible"}
    for sample in samples:
        appearance = sample["cue"]["appearance"]
        assert appearance["shape"] == "ring" and appearance["coverage"] == "visual_extent", sample
        assert appearance["cells"] and set(appearance["cells"]) <= visible, sample
        assert not {"radius_world", "size", "scale", "alpha_transform", "configured_radius"} & set(appearance)
        event_cells.append(appearance["cells"])
    for cue in cues(initial, "blast_wave"):
        check_appearance(initial, cue, "ring")
    time.sleep(.3)
    faded = client.state()
    assert not cues(faded, "blast_wave"), cues(faded)
    after = events(client); assert_retained(before, after)
    return {"kind": "blast_wave", "known_visual_cells_by_occurrence": event_cells,
            "sample_count": len(samples), "configured_radius_not_published": True,
            "faded_from_current_state": True, "transient_history_retained": True,
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
