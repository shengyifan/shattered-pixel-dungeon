#!/usr/bin/env python3
"""Construct an isolated pre-summon state; exercise the original Buff through public CLI."""
import json
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, assert_gui_environment, close_choices, freeze_runtime, reach_game
from low_frequency_smoke import act
from visual_cue_smoke import read_visual_events, visual

KINDS = {"summoning_bones", "summoning_shadows", "summoning_green_flames", "summoning_sparks"}
MINION_NAMES = {"dwarven ghoul", "dwarf warlock", "dwarf monk", "golem"}


def cues(state):
    return [cue for cue in visual(state)["cues"] if cue["kind"] in KINDS]


def test_visible(client):
    before = client.state()
    waiting = act(client, "wait")
    shown = cues(waiting)
    assert {cue["kind"] for cue in shown} == KINDS, visual(waiting)
    assert len({cue["cell"] for cue in shown}) == 4
    assert all({"kind", "cell"} <= set(cue) <= {"kind", "cell", "appearance"} for cue in shown)
    menu = next(node for node in waiting["observation"]["ui"]["controls"]
                if str(node.get("shortcut_action", "")).lower() == "back")
    covered = act(client, "ui.activate", control=menu["id"])
    assert covered["observation"]["ui"]["modal"] and {cue["kind"] for cue in cues(covered)} == KINDS
    restored = act(client, "ui.back")
    assert {cue["kind"] for cue in cues(restored)} == KINDS
    old_entities = {e.get("name") for e in before["observation"]["visible_entities"]}
    # Three remaining original Buff turns are sufficient even if setup was observed
    # before its first scheduled tick. Each next action follows its real response.
    spawned = restored
    for _ in range(3):
        spawned = act(client, "wait")
        entities = {e.get("name") for e in spawned["observation"]["visible_entities"]}
        if MINION_NAMES <= entities - old_entities:
            break
    assert MINION_NAMES <= entities - old_entities, spawned["observation"]["visible_entities"]
    tail = cues(spawned)
    time.sleep(2.5)  # Render time only; do not advance the original summoning timers.
    cleared = client.state()
    assert not cues(cleared), visual(cleared)
    historical = read_visual_events(client)
    assert KINDS <= {cue["kind"] for event in historical for cue in event["data"]["cues"]}
    assert cleared["observation"]["hero"] == spawned["observation"]["hero"]
    return {"all_four_semantic_warnings_in_final_wait": shown,
            "modal_keeps_known_warnings_and_original_back": True,
            "four_original_minions_spawned": sorted(entities - old_entities),
            "actual_tail_at_spawn_response": tail, "natural_tail_disappearance": True,
            "shown_styles_preserved_in_history": True, "private_timer_or_summon_type_published": False}


def test_hidden(client):
    start = time.monotonic()
    state = act(client, "wait")
    elapsed = time.monotonic() - start
    assert elapsed < 10 and not cues(state), visual(state)
    assert not any(cue["kind"] in KINDS for event in read_visual_events(client) for cue in event["data"]["cues"])
    return {"hidden_four_sources": True, "no_warning_or_history_leak": True,
            "response_seconds": elapsed, "hidden_emitters_do_not_delay_input": True}


def run_case(root, classpath, runtime_id, name):
    profile = root / "desktop-control/build/fixtures" / (name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher",
               "--fixture", "lowfreq:" + name]
    client = FixtureClient(command, profile)
    report = {"test_fixture": True, "counts_as_win": False, "fixture": name,
              "profile": str(profile.relative_to(root)), "runtime_id": runtime_id}
    try:
        hello = client.request("protocol.info")
        assert hello["ok"], hello
        report["build_id"] = hello["result"]["build_id"]
        started = reach_game(client, "WARRIOR")
        assert started["observation"]["hero"]["class_name"] == "warrior"
        report["gui_environment"] = assert_gui_environment(profile, started)
        report["cli_language"] = "en"
        setup = json.loads((profile / "test_fixture.json").read_text())
        assert setup["fullscreen"] is False and setup["language"] == "CHI_SMPL"
        report.update(language="zh", window_mode="windowed")
        report["evidence"] = (test_hidden if name.endswith("hidden") else test_visible)(client)
        report["ok"] = True
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        try:
            close_choices(client)
            client.finish()
        except Exception as error:
            report.update(ok=False, cleanup_error=repr(error))
        finally:
            if client.process.poll() is None:
                client.process.terminate(); client.process.wait(timeout=10)
            client.stderr.close(); client.trace.close()
        (profile / "king-visual-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    root = Path(__file__).resolve().parents[4]
    classpath = (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip()
    classpath, runtime_id = freeze_runtime(root, classpath)
    reports = [run_case(root, classpath, runtime_id, name) for name in ("visual-king", "visual-king-hidden")]
    target = root / "desktop-control/build/king-visual-validation.json"
    target.write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n")
    if not all(report["ok"] for report in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
