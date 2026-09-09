#!/usr/bin/env python3
"""Original menu callbacks in a Chinese windowed isolated profile. Never starts a formal run."""
import json
from pathlib import Path
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

from fixture_smoke import FixtureClient, freeze_runtime, assert_gui_environment
from low_frequency_smoke import act
from test_ui import configure_test_ui


def ui(state):
    return state["observation"]["ui"]


def choose(client, label):
    state = client.state()
    candidates = [a for a in state["actions"] if a["action"] == "ui.activate" and a.get("label") == label]
    assert candidates, {"missing_label": label, "scene": ui(state)["scene"],
                        "choices": [(a.get("action"), a.get("label")) for a in state["actions"]]}
    return act(client, "ui.activate", control=candidates[0]["control"])


def return_to_title(client):
    for _ in range(8):
        state = client.state()
        if ui(state)["scene"] == "TitleScene" and not ui(state)["modal"]:
            return state
        if ui(state)["scene"] == "WelcomeScene":
            label = "Continue" if any(a.get("label") == "Continue" for a in state["actions"]) else "Enter the Dungeon"
            choose(client, label)
        else:
            act(client, "ui.back")
    raise AssertionError("Original back did not return to title")


def scene_case(client, label, expected):
    start = return_to_title(client)
    opened = choose(client, label)
    assert ui(opened)["scene"] == expected, ui(opened)
    assert opened["scope_id"] == start["scope_id"] and opened["scope_id"].startswith("menu:")
    assert any(n.get("text") for n in ui(opened)["controls"]), "Scene must publish displayed text"
    result = {"label": label, "scene": expected, "same_menu_scope": True,
              "actions": [(a["action"], a.get("label")) for a in opened["actions"]],
              "displayed_text_samples": [n["text"][:180] for n in ui(opened)["controls"] if n.get("text")][:12]}
    if expected == "JournalScene":
        tabs = ["Badges", "Catalogs", "Dungeon Guide", "Alchemy Guide"]
        result["tab_candidates"] = [a.get("label") for a in opened["actions"] if a["action"] == "ui.activate"]
        result["verified_tabs"] = []
        for tab in tabs:
            panel = choose(client, tab)
            assert ui(panel)["scene"] == expected
            result["verified_tabs"].append(tab)
    returned = return_to_title(client)
    assert returned["scope_id"] == start["scope_id"]
    result["original_back_to_title"] = True
    return result


def main():
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    profile = root / "desktop-control/build/fixtures" / ("menu-scenes-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    configure_test_ui(profile)
    # Deterministic offline services only; no personal settings or account is touched.
    settings = profile / "settings.xml"
    tree = ET.parse(settings)
    for key in ("news", "updates"):
        element = next((e for e in tree.getroot() if e.get("key") == key), None)
        if element is None:
            element = ET.SubElement(tree.getroot(), "entry", key=key)
        element.text = "false"
    text = ET.tostring(tree.getroot(), encoding="unicode")
    settings.write_text('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">\n' + text, encoding="utf-8")
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "class:WARRIOR"]
    client = FixtureClient(command, profile)
    report = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id,
              "profile": str(profile.relative_to(root)), "language": "zh", "window_mode": "windowed", "cases": []}
    try:
        hello = client.request("protocol.info")
        assert hello["ok"], hello
        report["build_id"] = hello["result"]["build_id"]
        initial = client.state(); report["initial_scene"] = ui(initial)["scene"]
        title = return_to_title(client)
        report["gui_environment"] = assert_gui_environment(profile, title)
        report["cli_language"] = "en"
        assert any(a.get("label") == "Enter the Dungeon" for a in title["actions"])
        for label, expected in [("About", "AboutScene"), ("Changes", "ChangesScene"), ("Journal", "JournalScene"),
                                ("Rankings", "RankingsScene"), ("Support the Game", "SupporterScene"), ("News", "NewsScene")]:
            report["cases"].append(scene_case(client, label, expected))
        report["ok"] = True
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        try:
            return_to_title(client); client.finish()
        except Exception as error:
            report.update(ok=False, cleanup_error=repr(error))
        finally:
            if client.process.poll() is None:
                client.process.terminate(); client.process.wait(timeout=10)
            client.stderr.close(); client.trace.close()
        (profile / "menu-scenario-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        (root / "desktop-control/build/menu-scenario-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    if not report.get("ok"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
