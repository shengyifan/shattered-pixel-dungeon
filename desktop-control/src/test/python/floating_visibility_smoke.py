#!/usr/bin/env python3
"""Native anchored floating feedback remains visible across same-FOV camera and modal changes."""
import json
from pathlib import Path
import traceback
import uuid

from fixture_smoke import FixtureClient, act, freeze_runtime, reach_game
from protocol8 import pages

TARGETS = {"314159", "warrior", "dodged"}


def anchored_feedback(state, anchor):
    observation = state["observation"]
    visible = {cell["cell"] for cell in observation["map"]["cells"]
               if cell["visibility"] == "visible"}
    assert anchor in visible, "Fixture anchor must remain inside the current hero FOV"
    ui = observation["ui"]
    shown = [entry for entry in ui.get("feedback", []) if entry.get("kind") == "floating"
             and str(entry.get("text", "")).casefold() in TARGETS]
    assert len(shown) == 3 and {entry["text"].casefold() for entry in shown} == TARGETS, ui
    assert all(entry.get("cell") == anchor and not entry.get("clipped") for entry in shown), shown
    assert all(not {"atlas", "frame_pixels", "color", "opacity", "alpha"} & set(entry)
               for entry in shown), shown
    assert not any(node.get("presentation") == "floating_text" for node in ui["controls"]), ui["controls"]
    return shown


def floating_events(client):
    return [event for page in pages(client, "events.read") for event in page
            if event["kind"] == "game.floating_text"]


def main():
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    print(json.dumps({"started_runtime": runtime_id, "fixture": "floating:visibility"}), flush=True)
    profile = root / "desktop-control/build/fixtures" / ("floating-visibility-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "floating:visibility"]
    client = FixtureClient(command, profile, verify_gui=True)
    report = {"test_fixture": True, "counts_as_win": False, "fixture": "floating:visibility",
              "profile": str(profile.relative_to(root)), "runtime_id": runtime_id}
    try:
        hello = client.request("protocol.info"); assert hello["ok"], hello
        report.update(build_id=hello["result"]["build_id"], cli_version=hello["result"]["cli_version"])
        initial = reach_game(client, "WARRIOR")
        anchor = initial["observation"]["hero"]["cell"]
        first = act(client, "view.pan", x=5000, y=0)
        first_feedback = anchored_feedback(first, anchor)
        history = floating_events(client)
        occurrences = [entry for event in history for entry in event["data"]["entries"]
                       if str(entry.get("text", "")).casefold() in TARGETS]
        assert len(occurrences) == 3 and {entry["text"].casefold() for entry in occurrences} == TARGETS, history
        assert all(entry.get("cell") == anchor for entry in occurrences), occurrences
        inside = act(client, "view.pan", x=-5000, y=0)
        inside_feedback = anchored_feedback(inside, anchor)
        final = act(client, "view.pan", x=5000, y=0)
        final_feedback = anchored_feedback(final, anchor)
        for current in (inside_feedback, final_feedback):
            assert {entry["id"] for entry in current} == {entry["id"] for entry in first_feedback}, current
        menu = next(node for node in final["observation"]["ui"]["controls"]
                    if str(node.get("shortcut_action", "")).lower() == "back")
        modal = act(client, "ui.activate", control=menu["id"])
        assert modal["observation"]["ui"]["modal"]
        modal_feedback = anchored_feedback(modal, anchor)
        reopened = act(client, "ui.back")
        assert not reopened["observation"]["ui"]["modal"]
        assert {entry["id"] for entry in anchored_feedback(reopened, anchor)} == {entry["id"] for entry in first_feedback}
        retained = floating_events(client)
        indexed = {event["sequence"]: event for event in retained}
        assert all(indexed.get(event["sequence"]) == event for event in history), "Original occurrences changed"
        assert all(state["scope_id"] == initial["scope_id"] for state in (first, inside, final, modal, reopened))
        report.update(ok=True, original_pan_sequence=[[5000, 0], [-5000, 0], [5000, 0]],
                      final_response_versions=[state["state_version"] for state in (first, inside, final, modal)],
                      anchored_feedback_from_each_final_response=[first_feedback, inside_feedback,
                                                                  final_feedback, modal_feedback],
                      same_fov_offscreen_and_modal_facts=True, stable_source_ids=True,
                      original_occurrences_retained=True, no_followup_query_to_repair_pan_responses=True)
        client.finish(); assert client.process.returncode == 0
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        if client.process.poll() is None:
            try:
                client.process.stdin.close(); client.process.wait(timeout=25)
            except Exception as error:
                report.update(ok=False, cleanup_error=repr(error))
                client.process.terminate(); client.process.wait(timeout=10)
        client.stderr.close(); client.trace.close()
        report.update(gui_postconditions_checked=client.gui_postconditions_checked, exit_code=client.process.poll())
        (profile / "floating-visibility-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        (root / "desktop-control/build/fixtures" / runtime_id / "floating-visibility-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    raise SystemExit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
