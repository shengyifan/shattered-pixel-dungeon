#!/usr/bin/env python3
"""Fresh original tutorial over the production protocol; no action refresh/retry or save reads."""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

from fixture_smoke import FixtureClient, freeze_runtime
from test_ui import configure_test_ui
from fixture_support import initialize_current_profile
from protocol9 import frame_data


def act(client, action, **args):
    # Client.act only polls this request's terminal result; it never retries STALE_STATE.
    response = client.act(action, **args)
    assert response.ok and response.status in ("completed", "awaiting_input"), response
    state = response.observation
    client.last_state = state
    return state


def start_warrior(client):
    selected = False
    state = client.state()
    for _ in range(25):
        observation = state
        ui = observation.get("ui", {})
        if observation.get("scene") == "game":
            assert observation["hero"]["class"] == "warrior", observation["hero"]
            return state
        options = [a for a in state["acts"] if a.get("op") == "click"]
        if ui.get("scene") == "HeroSelectScene" and not selected:
            choice = next(a for a in options if a.get("label", "").lower() == "warrior")
            selected = True
        elif ui.get("scene") == "HeroSelectScene" and ui.get("modal"):
            state = act(client, "back")
            continue
        else:
            choice = next((a for label in ("Continue", "Enter the Dungeon", "Play", "New Game", "Start")
                           for a in options if a.get("label") == label), None)
            assert choice, {"cannot_start": ui, "acts": state["acts"]}
        state = act(client, "click", ctl=choice["ctl"])
    raise AssertionError("The original warrior menu flow did not reach the tutorial")


def adjacent_cells(state):
    observation = state
    position = observation["hero"]["cell"]
    width = observation["map"]["w"]
    occupied = {e["cell"] for e in observation["entities"] if e["kind"] == "character"}
    return [cell for cell in observation["map"]["cells"]
            if cell.get("visibility") == "visible" and cell["cell"] != position and cell["cell"] not in occupied
            and max(abs(cell["x"] - position % width), abs(cell["y"] - position // width)) == 1
            and any(word in cell["name"].lower() for word in ("floor", "grass", "water", "door"))
            and not any(word in cell["name"].lower() for word in ("wall", "chasm", "locked"))
            and not cell.get("environment")]


def open_intro(client, state):
    visits = {}
    for _ in range(80):
        assert not any(a["op"] == "item" for a in state["acts"]), "The fresh tutorial must still hide inventory"
        observation = state
        book = next((e for e in observation["entities"]
                     if any(word in e.get("item", {}).get("name", "").lower()
                            for word in ("tome of dungeon mastery", "guidebook"))), None)
        if book:
            state = act(client, "cell", cell=book["cell"], mode="act")
            journal = next(node for node in state["ui"]["nodes"]
                           if node.get("shortcut") == "journal" and node.get("enabled"))
            state = act(client, "click", ctl=journal["id"])
            assert state["ui"]["modal"], state
            return state
        position = observation["hero"]["cell"]
        visits[position] = visits.get(position, 0) + 1
        choices = adjacent_cells(state)
        assert choices, "The public tutorial observation has no safe exploration step"
        target = min(choices, key=lambda cell: (visits.get(cell["cell"], 0), cell["cell"]))
        state = act(client, "cell", cell=target["cell"], mode="act")
    raise AssertionError("The tutorial guide was not found through public observations")


def preferences(profile, interface_size):
    configure_test_ui(profile)
    target = profile / "settings.xml"
    document = ET.parse(target).getroot()
    for key, value in {"intro": "true", "full_ui": str(interface_size), "news": "false", "updates": "false"}.items():
        entry = next((node for node in document if node.get("key") == key), None)
        if entry is None:
            entry = ET.SubElement(document, "entry", key=key)
        entry.text = value
    header = '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">\n'
    target.write_text(header + ET.tostring(document, encoding="unicode"))


def direction_to(state, destination):
    position = state["hero"]["cell"]
    width = state["map"]["w"]
    offset = (destination % width - position % width, destination // width - position // width)
    return {(-1, -1): "NW", (0, -1): "N", (1, -1): "NE",
            (-1, 0): "W", (1, 0): "E", (-1, 1): "SW", (0, 1): "S", (1, 1): "SE"}[offset]


def run_one(root, classpath, runtime_id, interface_size):
    profile = root / "desktop-control/build/fixtures" / ("tutorial-boundary-" + str(interface_size) + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"]
    initialize_current_profile(command, profile)
    preferences(profile, interface_size)
    client = FixtureClient(command, profile)
    result = {"test_fixture": True, "counts_as_win": False, "fixture": "tutorial-boundary",
              "runtime_id": runtime_id, "interface_size": interface_size, "profile": str(profile.relative_to(root))}
    try:
        hello = client.request("info")
        assert ("err" not in hello), hello
        result["build_id"] = hello["data"]["build_id"]
        state = open_intro(client, start_warrior(client))
        started = time.monotonic()
        boundary = act(client, "back")
        elapsed = time.monotonic() - started
        assert boundary["phase"] == "player_ready", boundary
        assert boundary["ui"]["display"] == {"language": "zh", "fullscreen": False}
        assert any(a["op"] == "item" for a in boundary["acts"]), "ui.back returned before revealing inventory"
        assert any(a["op"] == "wait" for a in boundary["acts"]), "ui.back returned before revealing the toolbar"
        controls = boundary["ui"]["nodes"]
        choices = adjacent_cells(boundary)
        assert choices, "A public adjacent movement target is required"
        destination = choices[0]["cell"]
        direction = direction_to(boundary, destination)
        # An adversarial delay crosses the complete original two-second reveal.
        # The assertions above happen immediately; waiting cannot repair an early response.
        time.sleep(2.2)
        later = client.state()
        assert later["rev"] == boundary["rev"], (boundary["rev"], later["rev"])
        assert later["ui"]["nodes"] == controls, "Controls appeared after the returned boundary"
        response = client.request_action({"op": "move", "dir": direction}, version=boundary["rev"])
        assert ("err" not in response) and response.get("st") == "completed", response
        moved = frame_data(response)
        assert moved["hero"]["cell"] == destination, moved["hero"]
        assert moved["rev"] != boundary["rev"]
        rejected = client.request_action({"op": "move", "dir": direction}, version=boundary["rev"])
        assert "err" in rejected and rejected["err"] == "STALE_STATE", rejected
        record = client.request("req", {"rid": rejected["id"]})
        assert ("err" not in record) and record["data"]["st"] == "REJECTED", record
        after_rejection = client.state()
        assert after_rejection["hero"]["cell"] == destination
        result.update(ok=True, evidence={"ui_back_elapsed_seconds": elapsed, "controls_at_completion": len(controls),
                      "completion_version": boundary["rev"], "version_unchanged_after_reveal_window": True,
                      "next_move_accepted_without_retry": True, "real_stale_version_rejected_without_movement": True})
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
        (profile / "tutorial-boundary-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--interfaces", default="2,0", help="Original interface sizes; 2 has the inventory pane, 0 omits it")
    sizes = [int(value) for value in parser.parse_args().interfaces.split(",")]
    assert all(size in (0, 1, 2) for size in sizes)
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports = [run_one(root, classpath, runtime_id, size) for size in sizes]
    (root / "desktop-control/build/tutorial-boundary-validation.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n")
    if not all(report.get("ok") for report in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
