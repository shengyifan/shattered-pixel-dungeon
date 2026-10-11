#!/usr/bin/env python3
"""Original native screen flash/shake fixtures; all assertions read public protocol output only."""
import argparse
import json
from pathlib import Path
import time
import combat_visual_smoke as runner
from fixture_smoke import freeze_runtime
from protocol9 import pages


def events(client):
    return [event for page in pages(client, "events") for event in page if event["kind"] == "game.screen_visual"]


def test_case(client, name):
    initial = client.last_state
    observation = initial
    assert observation["hero"]["cell"] >= 0 and observation["map"]["cells"]
    assert initial["acts"], "The fixture must still advertise real gameplay operations"
    if name in {"screen-flash-below-modal", "screen-flash-above-modal"}:
        assert observation["ui"]["modal"], observation["ui"]
        assert any("Screen-effect occlusion fixture" in str(node.get("text", ""))
                   for node in observation["ui"]["nodes"]), observation["ui"]
    before = events(client)
    assert before == [], "Decorative flash/shake event output is retired"
    assert "screen_effects" not in observation["cues"]
    assert "metrics" not in observation["cues"]
    time.sleep(1.25)
    final = client.state()
    assert final["rev"] == initial["rev"], "Decorative lifetime changed action binding"
    assert final["hero"]["cell"] == observation["hero"]["cell"]
    assert final["hero"]["hp"] == observation["hero"]["hp"]
    assert not events(client), "Decorative animation leaked into protocol events"
    assert "screen_effects" not in final["cues"]
    return {"native_scene": name, "gameplay_frame_complete": True,
            "decorative_screen_output_absent": True,
            "action_binding_stable": True, "initial_revision": initial["rev"]}


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
