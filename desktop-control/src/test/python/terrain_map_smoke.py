#!/usr/bin/env python3
"""Public default-map terrain versus native smithy inspection and Ballistica.

Only isolated fixture setup changes game state. Targets come from public replies;
native oracle records are read after all choices, exclusively for assertions.
Bundle mode uses packaged production code plus a separate test-only launcher.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys
import time
import traceback
import uuid

from cli9_vault_smoke import VaultClient
from fixture_smoke import freeze_fixture_runtime, fixture_java_prefix
from tutorial_boundary_smoke import act, preferences, start_warrior


ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "skills/pixel-dungeon-run-assistance/scripts"))
from ray import predict, source_model  # noqa: E402
from spdctl_client import decode_wire_response  # noqa: E402


def cells(state):
    return state["map"]["cells"]


def descriptor(cell):
    return {key: cell[key] for key in ("terrain", "name", "desc") if key in cell}


def normalized(value):
    return " ".join(value.replace("_", "").casefold().split())


def same_descriptors(state, expected):
    current = {cell["cell"]: descriptor(cell) for cell in cells(state)}
    for position, original in expected.items():
        assert current.get(position) == original, {"cell": position, "expected": original, "actual": current.get(position)}


def check_scenario(client, profile):
    state = start_warrior(client)
    deadline = time.monotonic() + 25
    while state.get("hero", {}).get("depth") != 12:
        assert time.monotonic() < deadline, "Native smithy fixture was not observed"
        time.sleep(0.05)
        state = client.state()
    state = client.state(view="play")
    assert not state["ui"]["modal"], "Default map must not need an inspection window"
    initial_wire = copy.deepcopy(client.last_wire_response)
    (profile / "default-map-response.json").write_text(json.dumps(initial_wire, ensure_ascii=False) + "\n")
    model = source_model()
    terrain = model[1]
    decorations = [cell for cell in cells(state) if cell.get("terrain") == terrain["CUSTOM_DECO_WTR"]]
    waters = [cell for cell in cells(state) if cell.get("terrain") == terrain["WATER"]]
    assert len(decorations) == 1 and len(waters) == 1, {"decorations": decorations, "waters": waters}
    decoration, water = decorations[0], waters[0]
    assert decoration.get("desc") and normalized(decoration["name"]) != normalized(water["name"]), decoration
    expected = {cell["cell"]: descriptor(cell) for cell in (decoration, water)}
    frame = decode_wire_response(initial_wire).frame
    predictions = {name: predict(frame, cell["cell"], "projectile", model)
                   for name, cell in (("decoration", decoration), ("water", water))}
    for prediction in predictions.values():
        assert prediction["first_unknown_stops_prediction"] is None, prediction
    assert predictions["water"]["predicted_reaches_target_under_assumptions"] is True
    assert predictions["decoration"]["predicted_reaches_target_under_assumptions"] is False

    for view, source in (("full", False), ("full", True)):
        state = client.state(view=view, source=source)
        same_descriptors(state, expected)
    inspection = {}
    for name, cell in (("decoration", decoration), ("water", water)):
        state = act(client, "cell", cell=cell["cell"], mode="examine")
        assert state["ui"]["modal"], "Native cell examination did not open a window"
        text = "\n".join(node.get("text", "") for node in state["ui"]["nodes"])
        assert normalized(cell["name"]) in normalized(text), {"cell": cell, "native_window": text}
        if cell.get("desc"):
            assert normalized(cell["desc"]) in normalized(text), {"cell": cell, "native_window": text}
        same_descriptors(state, expected)
        inspection[name] = {"cell": cell["cell"], "native_window_text": text}
        state = act(client, "back")
        same_descriptors(state, expected)
    state = act(client, "pan", x=5000, y=5000)
    same_descriptors(state, expected)
    state = act(client, "pan", x=-5000, y=-5000)
    same_descriptors(state, expected)

    # No fixture checkpoint was read to select a target, item or control above.
    records = [json.loads(line) for line in (profile / "fixture-assertions.jsonl").read_text().splitlines()]
    native = next(record["public_terrain"] for record in reversed(records)
                  if record.get("public_terrain", {}).get("ready"))
    assert native["native_room_paint"] and native["source"] == frame["data"]["hero"]["cell"]
    for name, prediction in predictions.items():
        assert native[name]["target"] == prediction["target"]
        assert native[name]["collision"] == prediction["conditional_collision_prediction"]["cell"]
        assert native[name]["distance"] == prediction["conditional_collision_prediction"]["dist"]
    return {"default_map_without_inspection": expected, "inspection": inspection,
            "native_ballistica_oracle": native, "public_ray_predictions": predictions,
            "play_full_src_equal": True, "modal_preserved_map": True, "camera_pan_preserved_map": True,
            "native_source": "BlacksmithRoom.paint -> SmithyVisuals.create -> GameScene"}


def run_one(classpath, runtime_id, execution, interface_size):
    profile = ROOT / "desktop-control/build/fixtures" / ("terrain-map-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    preferences(profile, interface_size)
    command = fixture_java_prefix(execution) + ["-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "terrain:smithy"]
    client = VaultClient(command, profile)
    result = {"test_fixture": True, "counts_as_win": False, "fixture": "terrain:smithy",
              "runtime_id": runtime_id, "execution": execution, "interface_size": interface_size,
              "profile": str(profile.relative_to(ROOT)), "action_retries": 0,
              "private_checkpoint_reads_for_decisions": 0}
    try:
        info = client.request("info")
        assert ("err" not in info), info
        result["build_id"] = info["data"]["build_id"]
        result["evidence"] = check_scenario(client, profile)
        quit_result = client.act("quit")
        assert quit_result.ok and quit_result.status == "completed", quit_result
        client.process.wait(timeout=25)
        assert client.process.returncode == 0, client.process.returncode
        result["ok"] = True
    except Exception as error:
        result.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        if client.buffer:
            (profile / "unconsumed-recv.bin").write_bytes(bytes(client.buffer))
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
            client.exact_send.close()
            client.exact_recv.close()
        (profile / "terrain-map-result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("ok", "interface_size", "runtime_id", "build_id", "profile", "error")
                      if key in result}), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--interfaces", default="2,0")
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--bundle", type=Path, help="Packaged production JAR plus separate test-only fixture classes")
    options = parser.parse_args()
    sizes = [int(value) for value in options.interfaces.split(",")]
    assert sizes and all(size in (0, 1, 2) for size in sizes)
    classpath_file = options.classpath_file or ROOT / "desktop-control/build/test-runtime-classpath.txt"
    classpath, runtime_id, execution = freeze_fixture_runtime(ROOT, classpath_file.read_text().strip(), options.bundle)
    driver = Path(__file__).read_bytes()
    frozen_driver = ROOT / "desktop-control/build/fixtures" / runtime_id / "terrain_map_smoke.py"
    frozen_driver.write_bytes(driver)
    execution["driver"] = {"sha256": hashlib.sha256(driver).hexdigest(), "frozen_source": str(frozen_driver)}
    reports = [run_one(classpath, runtime_id, execution, size) for size in sizes]
    output = ROOT / "desktop-control/build/terrain-map-validation.json"
    output.write_text(json.dumps(reports, indent=2) + "\n")
    if not all(report.get("ok") for report in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
