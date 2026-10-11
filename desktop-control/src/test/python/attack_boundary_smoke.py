#!/usr/bin/env python3
"""Real melee completion boundaries; no STALE_STATE retries or save/database reads."""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, freeze_runtime
from tutorial_boundary_smoke import act, adjacent_cells, direction_to, preferences, start_warrior


def attack_control(state):
    return next(node for node in state["ui"]["nodes"]
                if node.get("shortcut") == "tag_attack")


def advertised(state, control):
    return any(action.get("op") == "click" and action.get("ctl") == control
               for action in state["acts"])


def run_one(root, classpath, runtime_id, interface_size, remaining, expect_regression):
    name = "attack-remaining" if remaining else "attack-last"
    profile = root / "desktop-control/build/fixtures" / (name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    preferences(profile, interface_size)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher",
               "--fixture", "boundary:" + name]
    client = FixtureClient(command, profile)
    result = {"test_fixture": True, "counts_as_win": False, "fixture": name,
              "runtime_id": runtime_id, "interface_size": interface_size,
              "expect_regression": expect_regression, "profile": str(profile.relative_to(root))}
    try:
        hello = client.request("info")
        assert "err" not in hello, hello
        result.update(build_id=hello["data"]["build_id"], cli_version=hello["data"]["cli_version"])
        initial = start_warrior(client)
        enemies = [e for e in initial["entities"]
                   if e.get("kind") == "character" and e.get("context_action") == "attack"]
        assert len(enemies) == (2 if remaining else 1), enemies
        target = min(enemies, key=lambda e: e["cell"])["cell"]
        assert attack_control(initial)["enabled"], attack_control(initial)
        started = time.monotonic()
        boundary = act(client, "cell", cell=target, mode="act")
        elapsed = time.monotonic() - started
        assert boundary["phase"] == "player_ready", boundary["phase"]
        assert boundary["ui"]["display"] == {"language": "zh", "fullscreen": False}
        assert not any(e.get("kind") == "character" and e["cell"] == target
                       for e in boundary["entities"]), "The native melee kill did not complete"
        control = attack_control(boundary)
        assert advertised(boundary, control["id"]) == control["enabled"]
        result["evidence"] = {"kill_elapsed_seconds": elapsed, "completion_version": boundary["rev"],
                              "attack_enabled_at_completion": control["enabled"]}
        if not expect_regression:
            assert control["enabled"] == remaining, "Completion preceded the finite attack-indicator update"
        destination = next(cell["cell"] for cell in adjacent_cells(boundary)
                           if cell["y"] < boundary["hero"]["cell"] // boundary["map"]["w"])
        direction = direction_to(boundary, destination)
        # Immediate assertions above must pass before crossing the original 0.75-second delay.
        time.sleep(1.1)
        later = client.state()
        assert later["hero"] == boundary["hero"]
        assert attack_control(later)["enabled"] == remaining
        response = client.request("move", {"dir": direction},
                                  version=boundary["rev"])
        if expect_regression:
            assert control["enabled"] and not remaining
            assert later["rev"] != boundary["rev"]
            assert response.get("err") == "STALE_STATE", response
            rejected = response
            expected_cell = boundary["hero"]["cell"]
        else:
            assert later["rev"] == boundary["rev"], (boundary["rev"], later["rev"])
            assert "err" not in response and response.get("st") == "completed", response
            assert response["data"]["hero"]["cell"] == destination
            rejected = client.request("move", {"dir": direction},
                                      version=boundary["rev"])
            assert rejected.get("err") == "STALE_STATE", rejected
            expected_cell = destination
        record = client.request("req", {"rid": rejected["id"], "get": ["before", "after"]})
        assert "err" not in record and record["data"]["st"] == "REJECTED", record
        assert record["data"]["before"] == record["data"]["after"]
        assert client.state()["hero"]["cell"] == expected_cell
        result["evidence"].update(later_version=later["rev"],
                                  original_version_accepted="err" not in response,
                                  rejected_action_did_not_move=True, action_retries=0)
        result["ok"] = True
    except Exception as error:
        result.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        try:
            if client.process.poll() is None:
                client.process.stdin.close()
                client.process.wait(timeout=35)
            assert client.process.returncode == 0, client.process.returncode
        except Exception as error:
            result.update(ok=False, cleanup_error=repr(error))
        finally:
            if client.process.poll() is None:
                client.process.terminate()
                client.process.wait(timeout=10)
            client.stderr.close()
            client.trace.close()
        (profile / "attack-boundary-result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--interfaces", default="2,0")
    parser.add_argument("--expect-regression", action="store_true", help="Verify the unfixed last-target failure")
    options = parser.parse_args()
    sizes = [int(value) for value in options.interfaces.split(",")]
    assert all(size in (0, 1, 2) for size in sizes)
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports = [run_one(root, classpath, runtime_id, size, remaining, options.expect_regression)
               for size in sizes for remaining in ([False] if options.expect_regression else [False, True])]
    suffix = "baseline" if options.expect_regression else "validation"
    output = root / ("desktop-control/build/attack-boundary-" + suffix + ".json")
    output.write_text(json.dumps(reports, indent=2) + "\n")
    if not all(report.get("ok") for report in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
