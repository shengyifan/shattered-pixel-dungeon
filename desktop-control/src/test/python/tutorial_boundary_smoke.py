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


def act(client, action, **args):
    # Client.act only polls this request's terminal result; it never retries STALE_STATE.
    response = client.act(action, **args)
    assert response.get("ok") and response.get("status") in ("completed", "awaiting_input"), response
    state = response["result"]
    client.last_state = state
    return state


def start_warrior(client):
    selected = False
    state = client.state()
    for _ in range(25):
        observation = state["observation"]
        ui = observation.get("ui", {})
        if observation.get("scene") == "game":
            assert observation["hero"]["class"] == "warrior", observation["hero"]
            return state
        options = [a for a in state["actions"] if a.get("action") == "ui.activate"]
        if ui.get("scene") == "HeroSelectScene" and not selected:
            choice = next(a for a in options if a.get("label", "").lower() == "warrior")
            selected = True
        elif ui.get("scene") == "HeroSelectScene" and ui.get("modal"):
            state = act(client, "ui.back")
            continue
        else:
            choice = next((a for label in ("Continue", "Enter the Dungeon", "Play", "New Game", "Start")
                           for a in options if a.get("label") == label), None)
            assert choice, {"cannot_start": ui, "actions": state["actions"]}
        state = act(client, "ui.activate", control=choice["control"])
    raise AssertionError("The original warrior menu flow did not reach the tutorial")


def adjacent_cells(state):
    observation = state["observation"]
    position = observation["hero"]["cell"]
    width = observation["map"]["width"]
    occupied = {e["cell"] for e in observation["visible_entities"] if e["kind"] == "character"}
    return [cell for cell in observation["map"]["cells"]
            if cell.get("visibility") == "visible" and cell["cell"] != position and cell["cell"] not in occupied
            and max(abs(cell["x"] - position % width), abs(cell["y"] - position // width)) == 1
            and any(word in cell["name"].lower() for word in ("floor", "grass", "water", "door"))
            and not any(word in cell["name"].lower() for word in ("wall", "chasm", "locked"))
            and not cell.get("environment")]


def open_intro(client, state):
    visits = {}
    for _ in range(80):
        assert not any(a["action"] == "inventory.open" for a in state["actions"]), "The fresh tutorial must still hide inventory"
        observation = state["observation"]
        book = next((e for e in observation["visible_entities"]
                     if any(word in e.get("item", {}).get("name", "").lower()
                            for word in ("tome of dungeon mastery", "guidebook"))), None)
        if book:
            state = act(client, "cell.select", cell=book["cell"], mode="act")
            journal = next(node for node in state["observation"]["ui"]["controls"]
                           if node.get("shortcut_action") == "journal" and node.get("enabled"))
            state = act(client, "ui.activate", control=journal["id"])
            assert state["observation"]["ui"]["modal"], state
            return state
        position = observation["hero"]["cell"]
        visits[position] = visits.get(position, 0) + 1
        choices = adjacent_cells(state)
        assert choices, "The public tutorial observation has no safe exploration step"
        target = min(choices, key=lambda cell: (visits.get(cell["cell"], 0), cell["cell"]))
        state = act(client, "cell.select", cell=target["cell"], mode="act")
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
    position = state["observation"]["hero"]["cell"]
    width = state["observation"]["map"]["width"]
    offset = (destination % width - position % width, destination // width - position // width)
    return {(-1, -1): "northwest", (0, -1): "north", (1, -1): "northeast",
            (-1, 0): "west", (1, 0): "east", (-1, 1): "southwest", (0, 1): "south", (1, 1): "southeast"}[offset]


def run_one(root, classpath, runtime_id, interface_size):
    profile = root / "desktop-control/build/fixtures" / ("tutorial-boundary-" + str(interface_size) + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    preferences(profile, interface_size)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"]
    client = FixtureClient(command, profile)
    result = {"test_fixture": True, "counts_as_win": False, "fixture": "tutorial-boundary",
              "runtime_id": runtime_id, "interface_size": interface_size, "profile": str(profile.relative_to(root))}
    try:
        hello = client.request("protocol.info")
        assert hello.get("ok"), hello
        result["build_id"] = hello["result"]["build_id"]
        state = open_intro(client, start_warrior(client))
        started = time.monotonic()
        boundary = act(client, "ui.back")
        elapsed = time.monotonic() - started
        assert boundary["phase"] == "player_ready", boundary
        assert boundary["observation"]["ui"]["display"] == {"language": "zh", "fullscreen": False}
        assert any(a["action"] == "inventory.open" for a in boundary["actions"]), "ui.back returned before revealing inventory"
        assert any(a["action"] == "wait" for a in boundary["actions"]), "ui.back returned before revealing the toolbar"
        controls = boundary["observation"]["ui"]["controls"]
        choices = adjacent_cells(boundary)
        assert choices, "A public adjacent movement target is required"
        destination = choices[0]["cell"]
        direction = direction_to(boundary, destination)
        # An adversarial delay crosses the complete original two-second reveal.
        # The assertions above happen immediately; waiting cannot repair an early response.
        time.sleep(2.2)
        later = client.state()
        assert later["state_version"] == boundary["state_version"], (boundary["state_version"], later["state_version"])
        assert later["observation"]["ui"]["controls"] == controls, "Controls appeared after the returned boundary"
        response = client.request("action.execute", {"action": "move.step", "direction": direction}, version=boundary["state_version"])
        assert response.get("ok") and response.get("status") == "completed", response
        moved = response["result"]
        assert moved["observation"]["hero"]["cell"] == destination, moved["observation"]["hero"]
        assert moved["state_version"] != boundary["state_version"]
        rejected = client.request("action.execute", {"action": "move.step", "direction": direction}, version=boundary["state_version"])
        assert not rejected.get("ok") and rejected["error"]["code"] == "STALE_STATE", rejected
        record = client.request("request.get", {"target_id": rejected["id"]})
        assert record.get("ok") and record["result"]["status"] == "REJECTED", record
        after_rejection = client.state()
        assert after_rejection["observation"]["hero"]["cell"] == destination
        result.update(ok=True, evidence={"ui_back_elapsed_seconds": elapsed, "controls_at_completion": len(controls),
                      "completion_version": boundary["state_version"], "version_unchanged_after_reveal_window": True,
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
