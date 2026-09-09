#!/usr/bin/env python3
"""Original welcome/update/temporary-file notifications over the production protocol."""
import json
import argparse
from pathlib import Path
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

from fixture_smoke import FixtureClient, freeze_runtime
from low_frequency_smoke import act
from menu_scenario_smoke import choose, ui
from test_ui import configure_test_ui


def initial_preferences(profile, version):
    configure_test_ui(profile)
    path=profile/"settings.xml"
    root=ET.parse(path).getroot()
    for key,value in {"version":str(version),"intro":"false","news":"false","updates":"false"}.items():
        entry=next((n for n in root if n.get("key")==key),None)
        if entry is None:entry=ET.SubElement(root,"entry",key=key)
        entry.text=value
    path.write_text('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">\n'+ET.tostring(root,encoding="unicode"))


def text(state):
    return "\n".join(n.get("text","") for n in ui(state)["controls"])


def run_one(root,classpath,runtime_id,name,version):
    profile=root/"desktop-control/build/fixtures"/("welcome-"+name+"-"+uuid.uuid4().hex)
    profile.mkdir(parents=True);initial_preferences(profile,version)
    if name=="interrupted":
        (profile/"game1").mkdir()
        (profile/"game1/game.dat.spdtmp").write_bytes(b"invalid isolated temporary save")
    command=["java","-XstartOnFirstThread","--enable-native-access=ALL-UNNAMED","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","-cp",classpath,
             "com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"]
    client=FixtureClient(command,profile)
    result={"test_fixture":True,"counts_as_win":False,"fixture":"welcome."+name,"profile":str(profile.relative_to(root)),
            "runtime_id":runtime_id,"cli_language":"en","gui_language":"zh","initial_saved_version":version}
    try:
        hello=client.request("protocol.info");assert hello["ok"],hello
        result["build_id"]=hello["result"]["build_id"]
        state=client.state()
        assert ui(state)["scene"]=="WelcomeScene"
        assert ui(state)["display"]=={"language":"zh","fullscreen":False}
        if name=="interrupted":
            assert "while saving" in text(state) or "save" in text(state).lower(),text(state)
            assert ui(state)["modal"]
            early=[n for n in ui(state)["controls"] if n.get("role")=="button" and "Continue" in n.get("text","")]
            assert early and not early[0]["enabled"]
            after_back=act(client,"ui.back")
            assert ui(after_back)["modal"]
            deadline=time.monotonic()+8
            while time.monotonic()<deadline:
                state=client.state()
                if any(a.get("label")=="Continue" for a in state["actions"]):break
                time.sleep(0.2)
            else:raise AssertionError("Original notification countdown did not enable Continue")
            choose(client,"Continue")
            state=client.state()
            assert ui(state)["scene"]=="TitleScene"
            assert not (profile/"game1/game.dat.spdtmp").exists()
            result["evidence"]={"original_temporary_file_cleanup":True,"back_did_not_bypass_countdown":True,"continue_after_native_timer":True}
        else:
            expected={"update":"Shattered Pixel Dungeon has been updated!","patch":"Shattered Pixel Dungeon has been patched!",
                      "future":"future version of Shattered Pixel Dungeon"}[name]
            assert expected in text(state),text(state)
            destination="ChangesScene" if name=="update" else "TitleScene"
            changed=choose(client,"Changes" if name=="update" else "Continue")
            assert ui(changed)["scene"]==destination
            result["evidence"]={"original_version_message":expected,"original_destination":destination}
        result["ok"]=True
    except Exception as error:result.update(ok=False,error=repr(error),traceback=traceback.format_exc())
    finally:
        try:
            if result.get("ok"):
                client.finish();assert client.process.returncode==0
            elif client.process.poll() is None:
                client.process.stdin.close();client.process.wait(timeout=35)
        except Exception as error:result.update(ok=False,cleanup_error=repr(error))
        finally:
            if client.process.poll() is None:client.process.terminate();client.process.wait(timeout=10)
            client.stderr.close();client.trace.close()
        (profile/"welcome-scenario-result.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps(result,ensure_ascii=False),flush=True)
    return result


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--cases",default="update,patch,future,interrupted")
    names=parser.parse_args().cases.split(",")
    versions={"update":882,"patch":895,"future":897,"interrupted":896}
    assert all(name in versions for name in names)
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports=[run_one(root,classpath,runtime_id,name,versions[name]) for name in names]
    (root/"desktop-control/build/welcome-scenarios-validation.json").write_text(json.dumps(reports,ensure_ascii=False,indent=2)+"\n")
    if not all(r.get("ok") for r in reports):raise SystemExit(1)


if __name__=="__main__":main()
