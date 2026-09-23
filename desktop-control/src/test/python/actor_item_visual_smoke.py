#!/usr/bin/env python3
"""Actual-render noisemaker, prismatic fade and sacrificial flame fixtures."""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid
from fixture_smoke import FixtureClient, act, close_choices, freeze_runtime, reach_game
from protocol7 import pages


def visual(state):
    return state["observation"]["visual_cues"]


def events(client, kind):
    return [row for page in pages(client, "events.read") for row in page if row["kind"] == kind]


def check(client, name):
    if name == "spectator":
        state = client.last_state
        target = state["observation"]["hero"]["cell"] + 2
        frozen = [cue for cue in visual(state)["cues"] if cue["cell"] == target
                  and cue["kind"] == "sprite_state_appearance" and cue.get("appearance", {}).get("paused") is True]
        assert frozen, visual(state)
        tint = frozen[0]["appearance"]["tint"]["multiply"]
        assert all(abs(channel - .4) < .0001 for channel in tint), frozen
        entity = next(row for row in state["observation"]["visible_entities"] if row["cell"] == target)
        assert not entity.get("buffs"), "Fixture must prove the icon-NONE route"
        return {"icon_none_pause_and_dark_tint": frozen}
    if name == "particle-counts":
        hero = client.last_state["observation"]["hero"]["cell"]
        samples = events(client, "game.visual_metrics")
        found = []
        for sample in samples:
            counts = {}
            for row in sample["data"]["metrics"]:
                if row["kind"] == "cell_particles" and row.get("appearance", {}).get("color") == 0xEE7722:
                    counts[row["cell"]] = counts.get(row["cell"], 0) + row["rendered_particles"]
            if counts.get(hero - 2) == 3 and counts.get(hero + 2) == 7:
                found.append(counts)
        assert found, {"different_native_bursts_not_retained": samples}
        return {"actual_draw_counts_distinguished": found, "emission_arguments_not_reported": True}
    if name == "noisemaker":
        for _ in range(5):
            state = act(client, "wait")
            history = events(client, "game.visual")
            alarms = [cue for row in history for cue in row["data"]["cues"] if cue["kind"] == "noisemaker_alarm"]
            if alarms:
                assert all("rendered_particles" not in cue for cue in alarms)
                return {"native_alarm_draw_recorded": True, "alarm_cells": sorted({cue["cell"] for cue in alarms})}
        raise AssertionError("Native noisemaker alarm never appeared in draw history")
    if name == "prismatic":
        first = [cue for cue in visual(client.last_state)["cues"] if cue["kind"] == "prismatic_image_paused"]
        assert first, visual(client.last_state)
        state = act(client, "wait")
        second = [cue for cue in visual(state)["cues"] if cue["kind"] == "prismatic_image_paused"]
        assert second and 0 < second[0]["opacity"] < first[0]["opacity"] <= 1, (first, second)
        assert all("deathTimer" not in cue and "turns" not in cue for cue in first + second)
        history = events(client, "game.visual")
        assert any(cue["kind"] == "prismatic_image_paused" for row in history for cue in row["data"]["cues"])
        return {"native_actor_fade_observed": True, "first": first, "second": second}
    state = act(client, "wait")
    history = events(client, "game.visual")
    marks = [cue for row in history for cue in row["data"]["cues"] if cue["kind"] == "sacrificial_mark_particles"]
    assert marks, "Native sacrificial mark particle burst is missing"
    for _ in range(8):
        measured = [row for row in visual(state).get("metrics", []) if row["kind"] == "sacrificial_flames"]
        if measured:
            break
        time.sleep(.1)
        state = client.state()
    assert measured, "No actual visible sacrificial flame particles measured"
    assert all(set(row) == {"kind", "cell", "rendered_particles"} and row["kind"] == "sacrificial_flames"
               and isinstance(row["rendered_particles"], int) and row["rendered_particles"] > 0 for row in measured), measured
    assert visual(state).get("metrics_at"), state
    samples = events(client, "game.visual_metrics")
    assert samples and all(row["data"]["format"] == "sampled_display_snapshot_v1"
                           and row["data"]["sample_period_ms"] == 250 for row in samples), samples
    revision = state["state_version"]
    time.sleep(.3)
    later = client.state()
    assert later["state_version"] == revision, "Particle density noise must not invalidate a game decision"
    menu = next(node for node in later["observation"]["ui"]["controls"]
                if str(node.get("shortcut_action", "")).lower() == "back")
    modal = act(client, "ui.activate", control=menu["id"])
    assert "metrics" not in visual(modal), visual(modal)
    assert any(row["data"]["metrics"] == [] for row in events(client, "game.visual_metrics")), "Occlusion disappearance missing"
    act(client, "ui.back")
    return {"native_mark_draw_recorded": True, "metrics": measured, "sample_count": len(samples),
            "occlusion_clears_metrics": True, "passive_density_keeps_revision": True}


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
