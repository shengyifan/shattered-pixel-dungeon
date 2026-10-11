#!/usr/bin/env python3
"""Native presentation completion, isolated fixtures, exact wire capture, no action retries."""
import argparse
import json
from pathlib import Path
import re
import time
import traceback
import uuid

from client_result import settle_action
from fixture_smoke import FixtureClient, fixture_java_prefix, freeze_fixture_runtime
from tutorial_boundary_smoke import act, adjacent_cells, direction_to, preferences, start_warrior


CASES = ("giant-move", "growing-move", "chilled-first", "sentry-tail", "chest-glow")


class RawFixtureClient(FixtureClient):
    """Retain wire bytes even when semantic decoding/assertions reject a response."""
    def __init__(self, command, profile):
        self.raw_send = (profile / "send.raw").open("wb")
        self.raw_recv = (profile / "recv.raw").open("wb")
        super().__init__(command, profile)

    def request(self, *args, **kwargs):
        self.last_send_bytes = self.last_recv_bytes = None
        try:
            return super().request(*args, **kwargs)
        finally:
            for stream, data in ((self.raw_send, self.last_send_bytes), (self.raw_recv, self.last_recv_bytes)):
                if data is not None:
                    stream.write(data)
                    stream.flush()


def cues(state, kind):
    return [cue for cue in state["cues"]["cues"] if cue["kind"] == kind]


def entities(state, kind):
    return [entity for entity in state["entities"] if entity["kind"] == kind]


def prepare_boundary(client, name, initial):
    if name == "chest-glow":
        boxes = entities(initial, "container")
        assert len(boxes) == 1 and boxes[0]["name"].lower() == "chest", boxes
        assert not cues(initial, "item_glow"), initial["cues"]
        target = boxes[0]["cell"]
        boundary = act(client, "cell", cell=target, mode="act")
        assert any(item["cell"] == target for item in entities(boundary, "item")), "Native chest did not open"
        complete = any(cue["cell"] == target for cue in cues(boundary, "item_glow"))
    else:
        characters = entities(initial, "character")
        assert len(characters) == 1, characters
        original_cell = characters[0]["cell"]
        if name == "sentry-tail":
            assert any(cue["cell"] == original_cell for cue in cues(initial, "sentry_charge_particles")), "Initial native charge must actually be observed"
        elif name.endswith("-move"):
            assert any(cue["cell"] == original_cell for cue in cues(initial, "character_aura")), "Initial native champion aura must actually be observed"
        else:
            assert not cues(initial, "character_chilled")
        boundary = act(client, "wait")
        current = entities(boundary, "character")
        assert len(current) == 1, current
        if name.endswith("-move"):
            assert current[0]["cell"] != original_cell, "Native actor position did not move"
            complete = {cue["cell"] for cue in cues(boundary, "character_aura")} == {current[0]["cell"]}
        elif name == "chilled-first":
            assert any(buff.get("name", "").lower() == "chilled" for buff in current[0]["buffs"]), current
            complete = any(cue["cell"] == current[0]["cell"] for cue in cues(boundary, "character_chilled"))
        else:
            complete = not cues(boundary, "sentry_charge_particles")
    assert boundary["phase"] == "player_ready", boundary["phase"]
    return boundary, complete


def run_one(root, classpath, runtime_id, execution, name, interface_size, fps, expect_regression):
    profile = root / "desktop-control/build/fixtures" / ("presentation-" + name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    preferences(profile, interface_size)
    command = ["/usr/bin/env", "SPDCTL_TEST_PRESENTATION_FPS=" + str(fps)] + fixture_java_prefix(execution) + [
        "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
        "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher",
        "--fixture", "presentation:" + name]
    client = RawFixtureClient(command, profile)
    result = dict(test_fixture=True, counts_as_win=False, fixture=name, interface_size=interface_size,
                  foreground_fps=fps, runtime_id=runtime_id, execution=execution,
                  expect_regression=expect_regression, profile=str(profile.relative_to(root)), action_retries=0)
    try:
        hello = client.request("info")
        assert "err" not in hello, hello
        result.update(build_id=hello["data"]["build_id"], cli_version=hello["data"]["cli_version"])
        initial = start_warrior(client)
        started = time.monotonic()
        boundary, semantic_complete = prepare_boundary(client, name, initial)
        revision = boundary["rev"]
        assert boundary["ui"]["display"] == {"language": "zh", "fullscreen": False}
        destination = next(cell["cell"] for cell in adjacent_cells(boundary)
                           if cell["x"] < boundary["hero"]["cell"] % boundary["map"]["w"])
        direction = direction_to(boundary, destination)
        evidence = dict(completion_seconds=time.monotonic() - started, completion_revision=revision,
                        semantic_complete_at_return=semantic_complete,
                        completion_cues=boundary["cues"], sampled_revisions=[])
        result["evidence"] = evidence
        # Four real-time samples cross the complete native tween/drop/particle tail
        # and also exercise gaps between chilled particles. No game turn is spent.
        for _ in range(4):
            time.sleep(.6)
            later = client.state()
            assert later["hero"] == boundary["hero"], "Wall-clock wait changed hero facts"
            evidence["sampled_revisions"].append(later["rev"])
        evidence["later_cues"] = later["cues"]
        unchanged = all(value == revision for value in evidence["sampled_revisions"])
        response = client.request("move", {"dir": direction}, version=revision)
        evidence["original_revision_accepted"] = "err" not in response
        if expect_regression:
            assert not semantic_complete or not unchanged or "err" in response, "The old runtime did not reproduce a presentation boundary regression"
        else:
            assert re.fullmatch(r"r[0-9]+", revision), revision
            assert semantic_complete, {"early_completion": evidence}
            assert unchanged, {"late_revision_drift": evidence}
            assert "err" not in response, response
        if "err" not in response:
            moved = settle_action(client, response, "move").require_success().observation
            assert moved["hero"]["cell"] == destination, moved["hero"]
            assert moved["rev"] != revision
            rejected = client.request("move", {"dir": direction}, version=revision)
            expected_cell = destination
        else:
            rejected = response
            expected_cell = boundary["hero"]["cell"]
        assert rejected.get("err") == "STALE_STATE", rejected
        record = client.request("req", {"rid": rejected["id"], "get": ["before", "after"]})
        assert "err" not in record and record["data"]["st"] == "REJECTED", record
        assert record["data"]["before"] == record["data"]["after"], record
        assert client.state()["hero"]["cell"] == expected_cell
        evidence.update(revision_unchanged_through_tail=unchanged, rejected_action_did_not_move=True)
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
            for stream in (client.stderr, client.trace, client.raw_send, client.raw_recv):
                stream.close()
        (profile / "presentation-boundary-result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", default=",".join(CASES))
    parser.add_argument("--interfaces", default="2,0")
    parser.add_argument("--fps", default="60,12")
    parser.add_argument("--bundle", type=Path, help="Use packaged production JAR with the isolated test launcher and host JVM")
    parser.add_argument("--expect-regression", action="store_true", help="Require a reproduced boundary defect in the selected old runtime")
    options = parser.parse_args()
    names = options.cases.split(",")
    sizes = [int(value) for value in options.interfaces.split(",")]
    rates = [int(value) for value in options.fps.split(",")]
    assert names and all(name in CASES for name in names), names
    assert sizes and all(size in (0, 1, 2) for size in sizes), sizes
    assert rates and all(rate in (12, 60) for rate in rates), rates
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id, execution = freeze_fixture_runtime(
        root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip(), options.bundle)
    reports = [run_one(root, classpath, runtime_id, execution, name, size, fps, options.expect_regression)
               for size in sizes for fps in rates for name in names]
    suffix = "baseline" if options.expect_regression else "validation"
    (root / ("desktop-control/build/presentation-boundary-" + suffix + ".json")).write_text(json.dumps(reports, indent=2) + "\n")
    if not all(report.get("ok") for report in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
