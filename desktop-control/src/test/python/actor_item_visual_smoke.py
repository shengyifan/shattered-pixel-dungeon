#!/usr/bin/env python3
"""Actual-render noisemaker, prismatic fade and sacrificial flame fixtures."""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid
from fixture_smoke import FixtureClient, act, close_choices, freeze_runtime, reach_game
from protocol8 import pages


def visual(state):
    return state["observation"]["visual_cues"]


def events(client, kind):
    return [row for page in pages(client, "events.read") for row in page if row["kind"] == kind]


def check(client, name):
    if name == "spectator":
        state = client.last_state
        target = state["observation"]["hero"]["cell"] + 2
        paused = [cue for cue in visual(state)["cues"] if cue["cell"] == target
                  and cue["kind"] == "sprite_state" and cue.get("appearance", {}).get("paused") is True]
        assert paused, visual(state)
        assert all("tint" not in cue["appearance"] and "opacity" not in cue for cue in paused)
        entity = next(row for row in state["observation"]["visible_entities"] if row["cell"] == target)
        assert not entity.get("buffs"), "Fixture must prove the icon-NONE route"
        return {"icon_none_pause_semantic_state": paused}
    if name == "particle-counts":
        state = client.last_state
        assert state["observation"]["hero"]["cell"] >= 0 and state["observation"]["map"]["cells"]
        assert not events(client, "game.visual_metrics"), "Generic particle density is retired"
        assert "metrics" not in visual(state) and "screen_effects" not in visual(state), visual(state)
        return {"valid_gameplay_observation": True, "generic_particle_metrics_retired": True}
    if name == "noisemaker":
        for _ in range(5):
            state = act(client, "wait")
            history = events(client, "game.visual")
            alarms = [cue for row in history for cue in row["data"]["cues"] if cue["kind"] == "noisemaker_alarm"]
            if alarms:
                assert all("rendered_particles" not in cue for cue in alarms)
                return {"native_alarm_semantic_warning": True, "alarm_cells": sorted({cue["cell"] for cue in alarms})}
        raise AssertionError("Native noisemaker alarm never appeared in cue history")
    if name == "prismatic":
        first = [cue for cue in visual(client.last_state)["cues"] if cue["kind"] == "prismatic_image_paused"]
        assert first, visual(client.last_state)
        state = act(client, "wait")
        second = [cue for cue in visual(state)["cues"] if cue["kind"] == "prismatic_image_paused"]
        assert second and all(type(cue["appearance"]["fading"]) is bool for cue in first + second), (first, second)
        assert all("opacity" not in cue and "deathTimer" not in cue and "turns" not in cue
                   for cue in first + second)
        history = events(client, "game.visual")
        assert any(cue["kind"] == "prismatic_image_paused" for row in history for cue in row["data"]["cues"])
        return {"native_prismatic_pause_and_fading_state": True, "first": first, "second": second}
    state = act(client, "wait")
    history = events(client, "game.visual")
    marks = [cue for row in history for cue in row["data"]["cues"] if cue["kind"] == "sacrificial_mark_particles"]
    assert marks, "Native sacrificial mark warning is missing"
    for _ in range(8):
        flames = [cue for cue in visual(state)["cues"] if cue["kind"] == "sacrificial_flames"]
        if flames:
            break
        time.sleep(.1)
        state = client.state()
    assert flames, "No visible sacrificial flame density"
    assert all(type(cue["appearance"]["count"]) is int and cue["appearance"]["count"] > 0
               and "rendered_particles" not in cue for cue in flames), flames
    assert not events(client, "game.visual_metrics"), "Retired metric stream must remain absent"
    revision = state["state_version"]
    time.sleep(.3)
    later = client.state()
    assert later["state_version"] == revision, "Passive flame density must not invalidate a decision"
    menu = next(node for node in later["observation"]["ui"]["controls"]
                if str(node.get("shortcut_action", "")).lower() == "back")
    modal = act(client, "ui.activate", control=menu["id"])
    assert modal["observation"]["ui"]["modal"] and any(
        cue["kind"] == "sacrificial_flames" for cue in visual(modal)["cues"]), visual(modal)
    act(client, "ui.back")
    return {"native_mark_warning": True, "flame_density": flames,
            "modal_keeps_known_world_fact": True, "passive_density_keeps_revision": True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="noisemaker,prismatic,sacrificial,particle-counts,spectator")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    classpath, runtime = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports = []
    for name in args.cases.split(","):
        assert name in {"noisemaker", "prismatic", "sacrificial", "particle-counts", "spectator"}
        profile = root / "desktop-control/build/fixtures" / ("actor-visual-" + name + "-" + uuid.uuid4().hex)
        profile.mkdir(parents=True)
        command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
                   "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
                   "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "combatvisual:" + name]
        client = FixtureClient(command, profile, verify_gui=True)
        report = {"fixture": name, "test_fixture": True, "counts_as_win": False, "runtime_id": runtime, "profile": str(profile)}
        try:
            hello = client.request("protocol.info"); assert hello["ok"], hello
            report["build_id"] = hello["result"]["build_id"]
            reach_game(client, "WARRIOR")
            report.update(ok=True, evidence=check(client, name))
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
            (profile / "result.json").write_text(json.dumps(report, indent=2) + "\n")
        reports.append(report)
        print(json.dumps(report), flush=True)
    target = root / "desktop-control/build/fixtures" / runtime / "actor-item-visual-results.json"
    target.write_text(json.dumps({"results": reports, "passed": sum(r["ok"] for r in reports), "total": len(reports)}, indent=2) + "\n")
    if not all(row["ok"] for row in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
