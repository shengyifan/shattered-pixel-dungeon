#!/usr/bin/env python3
"""Original native screen flash/shake fixtures; all assertions read public protocol output only."""
import argparse
import json
from pathlib import Path
import time
import combat_visual_smoke as runner
from fixture_smoke import freeze_runtime
from protocol7 import pages


def events(client):
    return [event for page in pages(client, "events.read") for event in page if event["kind"] == "game.screen_visual"]


def test_case(client, name):
    initial = client.last_state
    before = events(client)
    values = [effect for event in before for effect in event["data"]["screen_effects"]]
    expected_color = {"screen-flash-additive": 0xFF8040, "screen-flash-normal": 0x904020,
                      "screen-flash-above-modal": 0xFFFFFF, "screen-flash-below-modal": 0xFFFFFF}.get(name)
    if name == "screen-flash-below-modal":
        assert not any(effect.get("color") == expected_color for effect in values), values
    elif name == "screen-shake":
        matches = [effect for effect in values if effect["kind"] == "camera_displacement"]
        assert matches, before
        assert all(any(value != 0 for value in effect["offset_pixels"]) for effect in matches), matches
        assert all("visible_reference_cell" in effect for effect in matches), matches
    else:
        matches = [effect for effect in values if effect["kind"] == "screen_overlay" and effect.get("color") == expected_color]
        assert matches, before
        blend = "normal" if name == "screen-flash-normal" else "additive"
        assert all(effect["blend"] == blend and 0 < effect["opacity"] <= 128 / 255 + 0.0001 for effect in matches), matches
    for event in before:
        data = event["data"]
        assert data["format"] == "sampled_display_snapshot_v1" and data["sample_period_ms"] == 250, data
        for effect in data["screen_effects"]:
            assert not {"duration", "magnitude", "cause", "episode"} & set(effect), effect
    time.sleep(1.25)
    final = client.state()
    current = final["observation"]["visual_cues"]
    assert current.get("screen_effects") == [] and current.get("screen_effects_at"), current
    after = events(client)
    by_sequence = {event["sequence"]: event for event in after}
    assert all(by_sequence.get(event["sequence"]) == event for event in before), "Historical screen samples must not be rewritten"
    if name != "screen-flash-below-modal":
        assert any(event["data"]["screen_effects"] == [] for event in after), after
    return {"native_scene": name, "screen_history": after, "ended_as_no_longer_observed": True,
            "initial_revision": initial["state_version"], "final_revision": final["state_version"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="screen-flash-additive,screen-flash-normal,screen-shake,screen-flash-below-modal,screen-flash-above-modal")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    classpath, runtime = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    runner.test_case = test_case
    results = [runner.run_one(root, classpath, runtime, case) for case in args.cases.split(",")]
    report = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime, "total": len(results),
              "passed": sum(row["ok"] for row in results), "results": results}
    (root / "desktop-control/build/fixtures" / runtime / "screen-visual-results.json").write_text(json.dumps(report, indent=2) + "\n")
    if not all(row["ok"] for row in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
