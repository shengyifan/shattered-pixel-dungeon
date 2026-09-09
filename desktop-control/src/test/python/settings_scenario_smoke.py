#!/usr/bin/env python3
"""Settings callbacks in an isolated profile: English protocol, Chinese windowed GUI."""
import json
from pathlib import Path
import traceback
import uuid

from fixture_smoke import FixtureClient, assert_gui_environment, freeze_runtime
from low_frequency_smoke import act
from menu_scenario_smoke import choose, return_to_title, ui


TITLES = ["Display Settings", "Interface Settings", "Input Settings", "Connectivity Settings", "Audio Settings", "Language Settings"]


def nodes(state):
    return ui(state)["controls"]


def controls(state, kind):
    return [a for a in state["actions"] if a["action"] == kind]


def panel_title(state):
    return next((title for title in TITLES if any(n.get("text") == title for n in nodes(state))), None)


def slider_roundtrip(client, state, needle):
    action = next(a for a in controls(state, "ui.value") if needle in a.get("label", ""))
    node = next(n for n in nodes(state) if n["id"] == action["control"])
    original = node["value"]
    target = node["minimum"] if original != node["minimum"] else node["maximum"]
    changed = act(client, "ui.value", control=node["id"], value=target)
    current = next(n for n in nodes(changed) if n["id"] == node["id"])
    assert current["value"] == target
    restored = act(client, "ui.value", control=node["id"], value=original)
    assert next(n for n in nodes(restored) if n["id"] == node["id"])["value"] == original
    return restored, {"label": needle, "old": original, "tested": target, "restored": True}


def test_settings(client, result):
    return_to_title(client)
    state = choose(client, "Settings")
    assert ui(state)["modal"]
    tabs = [a["control"] for a in controls(state, "ui.activate") if not a.get("label")]
    assert len(tabs) == 6, tabs
    for index, control in enumerate(tabs):
        state = act(client, "ui.activate", control=control)
        assert panel_title(state) == TITLES[index], {"index": index, "ui": ui(state)}
        assert ui(state)["display"] == {"language": "zh", "fullscreen": False}
        evidence = {"title": TITLES[index], "gui": assert_gui_environment(client.profile, state)}
        if index == 0:
            fullscreen = next(n for n in nodes(state) if n.get("text") == "Fullscreen" and "checked" in n)
            assert fullscreen["checked"] is False
            state, evidence["brightness"] = slider_roundtrip(client, state, "Brightness")
            state, evidence["grid"] = slider_roundtrip(client, state, "Visual Grid")
        elif index == 2:
            state = choose(client, "Key Bindings")
            assert ui(state)["modal"] and controls(state, "ui.binding_slot"), ui(state)
            evidence["binding_rows"] = len(controls(state, "ui.binding_slot"))
            state = act(client, "ui.back")
            assert controls(state,"ui.binding_slot"), "The original binding editor ignores Back to protect edits"
            evidence["binding_back_preserved_editor"] = True
            state = choose(client,"Cancel")
            assert panel_title(state) == TITLES[index]
        elif index == 4:
            state, evidence["music_volume"] = slider_roundtrip(client, state, "Music Volume")
        elif index == 5:
            assert any(a.get("label") == "Simplified Chinese" for a in controls(state, "ui.activate"))
            evidence["current_language_available_in_english"] = True
        result.append(evidence)
    act(client, "ui.back")
    assert ui(client.state())["scene"] == "TitleScene"
    return result


def main():
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    profile = root / "desktop-control/build/fixtures" / ("settings-scenes-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "class:WARRIOR"]
    client = FixtureClient(command, profile)
    report = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id,
              "profile": str(profile.relative_to(root)), "cli_language": "en", "gui_language": "zh", "cases": []}
    try:
        hello = client.request("protocol.info"); assert hello["ok"], hello
        report["build_id"] = hello["result"]["build_id"]
        test_settings(client, report["cases"])
        report["ok"] = True
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        try:
            if report.get("ok"):
                return_to_title(client); client.finish()
                assert client.process.returncode == 0
            elif client.process.poll() is None:
                client.process.stdin.close(); client.process.wait(timeout=35)
        except Exception as error:
            report.update(ok=False, cleanup_error=repr(error))
        finally:
            if client.process.poll() is None:
                client.process.terminate(); client.process.wait(timeout=10)
            client.stderr.close(); client.trace.close()
        output = root / "desktop-control/build/settings-scenario-validation.json"
        encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        output.write_text(encoded); (profile / "settings-scenario-result.json").write_text(encoded)
    print(json.dumps(report, ensure_ascii=False), flush=True)
    if not report.get("ok"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
