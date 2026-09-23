#!/usr/bin/env python3
"""Isolated actual-render combat fixtures; no personal profiles or synthetic public replies."""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, act, close_choices, freeze_runtime, reach_game
from protocol8 import pages


EXPECTED = {
    "eye": "evil_eye_charging", "ghoul": "downed_ghoul", "guardian": "downed_crystal_guardian",
    "necromancer": "summoning_bones", "spectral": "summoning_shadow", "rocks": "falling_rock_warning",
    "checked": "checked_cell", "pylon": "pylon_lightning", "sentry": "sentry_charge_particles",
    "flow": "electricity_flow",
    "arcane-bomb": "arcane_bomb_warning", "challenge": "challenge_arena", "beacon": "warp_beacon",
    "golem": "golem_teleport_particles", "ring": "blast_wave",
    "beam": "death_ray", "magic": "magic_fire",
    "surprise": "surprise_mark", "wound": "wound_mark", "flare": None, "spell": "spell_icon",
    "dm300": "dm300_charging", "chains": "ethereal_chain_link",
    "ripper": "ripper_leap_preparation", "spire": "crystal_spire_state",
}


def cues(state):
    return state["observation"]["visual_cues"]["cues"]


def visual_events(client):
    return [event for page in pages(client, "events.read") for event in page if event["kind"] == "game.visual"]


def test_case(client, name):
    initial = client.last_state
    if name == "eye-hidden":
        final = act(client, "wait")
        events = visual_events(client)
        assert not any(cue["kind"] == "evil_eye_charging" for cue in cues(final))
        assert not any(cue["kind"] == "evil_eye_charging" for event in events for cue in event["data"]["cues"])
        return {"hidden_sprite_not_published": True}

    if name == "flare":
        observation = initial["observation"]
        assert observation["map"]["cells"] and initial["actions"]
        before = visual_events(client)
        assert not any(cue["kind"] == "flare" for cue in cues(initial))
        assert not any(cue["kind"] == "flare" for event in before for cue in event["data"]["cues"])
        time.sleep(2.2)
        faded = client.state()
        assert faded["state_version"] == initial["state_version"], "An ordinary flare's fade must not change an action binding"
        assert faded["observation"]["hero"]["cell"] == observation["hero"]["cell"]
        assert faded["observation"]["hero"]["hp"] == observation["hero"]["hp"]
        retained = visual_events(client)
        assert all(event in retained for event in before)
        assert not any(cue["kind"] == "flare" for cue in cues(faded))
        assert not any(cue["kind"] == "flare" for event in retained for cue in event["data"]["cues"])
        return {"native_unclassified_flare": True, "decorative_flare_output_absent": True,
                "action_binding_stable": True, "evidence_kind": "prepared_native_render_source"}

    expected = EXPECTED[name]
    if name in {"ghoul", "guardian"}:
        hero = initial["observation"]["hero"]["cell"]
        target = next(entity["cell"] for entity in initial["observation"]["visible_entities"]
                      if entity["cell"] == hero + 1 and entity.get("kind") == "character")
        for _ in range(8):
            final = act(client, "cell.select", cell=target)
            if any(cue["kind"] == expected for cue in cues(final)):
                break
        else:
            raise AssertionError({"native_attack_did_not_show_downed_posture": cues(final)})
        if name == "ghoul":
            assert not any(entity["cell"] == target and entity.get("kind") == "character"
                           for entity in final["observation"]["visible_entities"]), "Downed body must be observed outside level.mobs"
    elif name in {"rocks", "challenge", "beacon", "golem", "ring", "beam", "magic", "surprise", "wound", "flare", "spell", "dm300", "chains", "ripper", "spire"}:
        final = initial
    else:
        final = act(client, "wait")

    events = visual_events(client)
    current = [cue for cue in cues(final) if cue["kind"] == expected]
    history = [cue for event in events for cue in event["data"]["cues"] if cue["kind"] == expected]
    if name in {"checked", "pylon", "ring", "beam", "magic", "surprise", "wound", "flare", "spell", "chains"}:
        assert history, {"transient_actual_draw_missing_from_public_history": name, "cues": cues(final)}
    else:
        assert current, {"warning_missing_from_own_final_response": name, "cues": cues(final)}
        assert history, {"draw_missing_from_public_history": name}

    visible = {cell["cell"] for cell in final["observation"]["map"]["cells"] if cell["visibility"] == "visible"}
    for cue in cues(final):
        assert cue["cell"] in visible, cue
        if "source_cell" in cue:
            assert cue["source_cell"] in visible, cue
        assert set(cue) <= {"kind", "cell", "source_cell", "direction", "appearance"}, cue
        assert not {"color", "opacity"} & set(cue), cue
    if name == "pylon":
        assert all("source_cell" in cue for cue in history), history
    if name == "ring":
        assert all(cue.get("appearance", {}).get("shape") == "ring"
                   and cue.get("appearance", {}).get("coverage") == "visual_extent"
                   and cue.get("appearance", {}).get("cells") for cue in history), history
    if name == "spire":
        assert all(cue.get("appearance", {}).get("stage") == "damaged" for cue in current), current
        assert all("crystal_spire_appearance" != cue["kind"] for cue in cues(final)), cues(final)
    if name == "flow":
        assert any("direction" in cue for cue in current), current
        assert all(cue.get("direction") in {None, "east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"}
                   and "source_cell" not in cue for cue in current), current

    if name in {"eye", "rocks", "flow", "sentry"}:
        before_revision = final["state_version"]
        # Passive rendering must not manufacture a fresh gameplay decision binding.
        later = client.state()
        assert later["state_version"] == before_revision, {"render_only_changed_revision": name}
        menu = next(node for node in later["observation"]["ui"]["controls"]
                    if str(node.get("shortcut_action", "")).lower() == "back")
        modal = act(client, "ui.activate", control=menu["id"])
        assert modal["observation"]["ui"]["modal"] and any(cue["kind"] == expected for cue in cues(modal))
        restored = act(client, "ui.back")
        assert any(cue["kind"] == expected for cue in cues(restored)), cues(restored)
    if name in {"checked", "pylon", "ring", "beam", "magic", "surprise", "wound", "flare", "spell", "chains"}:
        time.sleep(2.2 if name == "flare" else 1.6)
        faded = client.state()
        assert not any(cue["kind"] == expected for cue in cues(faded)), cues(faded)
        retained = visual_events(client)
        assert all(event in retained for event in events), "Observed transient draws must remain in history"
    return {"kind": expected, "current": current, "history_count": len(history),
            "evidence_kind": "prepared_native_render_source" if name in {"rocks", "challenge", "beacon", "golem", "ring", "beam", "magic", "surprise", "wound", "flare", "spell", "dm300", "chains", "ripper", "spire"}
                             else "native_action_and_renderer",
            "late_state_not_used_to_repair_missing_initial_warning": True}


def run_one(root, classpath, runtime_id, name):
    profile = root / "desktop-control/build/fixtures" / ("combatvisual-" + name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher",
               "--fixture", "combatvisual:" + name]
    client = FixtureClient(command, profile, verify_gui=True)
    report = {"test_fixture": True, "counts_as_win": False, "fixture": name,
              "runtime_id": runtime_id, "profile": str(profile.relative_to(root))}
    try:
        hello = client.request("protocol.info"); assert hello["ok"], hello
        report.update(build_id=hello["result"]["build_id"], cli_version=hello["result"]["cli_version"])
        reach_game(client, "WARRIOR")
        report["evidence"] = test_case(client, name)
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
        client.stderr.close(); client.trace.close()
        report.update(gui_postconditions_checked=client.gui_postconditions_checked, exit_code=client.process.poll())
        (profile / "combat-visual-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default=",".join(EXPECTED) + ",eye-hidden")
    args = parser.parse_args()
    cases = args.cases.split(",")
    assert all(name in EXPECTED or name == "eye-hidden" for name in cases)
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    results = [run_one(root, classpath, runtime_id, name) for name in cases]
    report = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id,
              "total": len(results), "passed": sum(result["ok"] for result in results), "results": results}
    (root / "desktop-control/build/fixtures" / runtime_id / "combat-visual-results.json").write_text(json.dumps(report, indent=2) + "\n")
    if not all(result["ok"] for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
