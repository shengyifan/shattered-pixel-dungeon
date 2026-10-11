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
    return ui(state)["nodes"]


def controls(state, kind):
    return [a for a in state["acts"] if a["op"] == kind]


def panel_title(state):
    return next((title for title in TITLES if any(n.get("text") == title for n in nodes(state))), None)


def slider_roundtrip(client, state, needle):
    action = next(a for a in controls(state, "value") if needle in a.get("label", ""))
    node = next(n for n in nodes(state) if n.get("id") == action["ctl"])
    original = node["value"]
    target = node["min"] if original != node["min"] else node["max"]
    changed = act(client, "value", ctl=node["id"], value=target)
    current = next(n for n in nodes(changed) if n.get("id") == node["id"])
    assert current["value"] == target
    restored = act(client, "value", ctl=node["id"], value=original)
    assert next(n for n in nodes(restored) if n.get("id") == node["id"])["value"] == original
    return restored, {"label": needle, "old": original, "tested": target, "restored": True}


def test_settings(client, result):
    return_to_title(client)
    state = choose(client, "Settings")
    assert ui(state)["modal"]
    tabs = [a["ctl"] for a in controls(state, "click") if not a.get("label")]
    assert len(tabs) == 6, tabs
    for index, control in enumerate(tabs):
        state = act(client, "click", ctl=control)
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
            assert ui(state)["modal"] and controls(state, "bind_slot"), ui(state)
            evidence["binding_rows"] = len(controls(state, "bind_slot"))
            row=next(a for a in controls(state,"bind_slot") if a.get("label","").split("\n",1)[0]=="Wait")
            editing=act(client,"bind_slot",ctl=row["ctl"],slot=1)
            key=controls(editing,"bind_key")[0]
            edited=act(client,"bind_key",ctl=key["ctl"],keycode=142)
            assert any("F12" in n.get("text","") for n in nodes(edited))
            state=choose(client,"Cancel")
            unchanged=next(a for a in controls(state,"bind_slot") if a.get("label","").split("\n",1)[0]=="Wait")
            assert "F12" not in unchanged.get("label","")
            evidence["binding_key_input_and_original_cancel"] = True
            editing=act(client,"bind_slot",ctl=unchanged["ctl"],slot=1)
            key=controls(editing,"bind_key")[0]
            act(client,"bind_key",ctl=key["ctl"],keycode=142)
            draft=choose(client,"Confirm")
            row=next(a for a in controls(draft,"bind_slot") if a.get("label","").split("\n",1)[0]=="Wait")
            assert "F12" in row["label"]
            duplicate=act(client,"bind_slot",ctl=row["ctl"],slot=2)
            key=controls(duplicate,"bind_key")[0]
            duplicate=act(client,"bind_key",ctl=key["ctl"],keycode=142)
            assert any("This key is already bound to this action." in n.get("text","") for n in nodes(duplicate))
            assert not any(a.get("label")=="Confirm" for a in controls(duplicate,"click"))
            choose(client,"Cancel")
            choose(client,"Confirm")
            state=choose(client,"Key Bindings")
            row=next(a for a in controls(state,"bind_slot") if a.get("label","").split("\n",1)[0]=="Wait")
            assert "F12" in row["label"], "The original parent Confirm must apply the actual binding"
            choose(client,"Default Bindings")
            choose(client,"Confirm")
            state=choose(client,"Key Bindings")
            row=next(a for a in controls(state,"bind_slot") if a.get("label","").split("\n",1)[0]=="Wait")
            assert "F12" not in row["label"]
            evidence["binding_confirm_duplicate_guard_default_restore"] = True
            state = act(client, "back")
            assert controls(state,"bind_slot"), "The original binding editor ignores Back to protect edits"
            evidence["binding_back_preserved_editor"] = True
            state = choose(client,"Cancel")
            assert panel_title(state) == TITLES[index]
        elif index == 4:
            state, evidence["music_volume"] = slider_roundtrip(client, state, "Music Volume")
        elif index == 5:
            assert any(a.get("label") == "Simplified Chinese" for a in controls(state, "click"))
            evidence["current_language_available_in_english"] = True
        result.append(evidence)
    act(client, "back")
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
        hello = client.request("info"); assert ("err" not in hello), hello
        report["build_id"] = hello["data"]["build_id"]
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
