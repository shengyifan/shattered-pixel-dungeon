#!/usr/bin/env python3
"""Open native Hero Info panels from an isolated profile with previously unlocked tabs."""
import argparse
import json
from pathlib import Path
import traceback
import uuid
from fixture_smoke import FixtureClient, act, freeze_runtime
from menu_scenario_smoke import choose, return_to_title, ui
from fixture_identity import fixture_context_matches


def actual_window(client,state,name):
    rows=[json.loads(line) for line in (client.profile/"ui-assertions.jsonl").read_text().splitlines()]
    row=next(row for row in reversed(rows) if fixture_context_matches(client.profile,row,state))
    assert any(value.endswith("."+name) for value in row["window_classes"]),row


def details_case(client,panel,tabs,index):
    choices=[a["control"] for a in panel["actions"] if a["action"]=="ui.activate" and a["control"] not in tabs]
    assert len(choices)==(2 if index==2 else 3),choices
    reports=[]
    for control in choices:
        detail=act(client,"ui.activate",control=control)
        window="WndInfoSubclass" if index==2 else "WndInfoArmorAbility"
        actual_window(client,detail,window)
        title=next(n["text"] for n in ui(detail)["controls"] if n.get("text"))
        talents=[a for a in detail["actions"] if a["action"]=="ui.activate"]
        opened=[]
        for talent in talents:
            explanation=act(client,"ui.activate",control=talent["control"])
            actual_window(client,explanation,"WndInfoTalent")
            opened.append({"label":talent.get("label"),"state_version":explanation["state_version"]})
            back=act(client,"ui.back");actual_window(client,back,window)
        restored=act(client,"ui.back");actual_window(client,restored,"WndHeroInfo")
        reports.append({"title":title,"window":window,"talents":opened,"original_back":True})
    return reports


def run_case(root,classpath,runtime_id,hero,details=False):
    profile=root/"desktop-control/build/fixtures"/("hero-info-"+hero+"-"+uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command=["java","-XstartOnFirstThread","--enable-native-access=ALL-UNNAMED","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
             "-cp",classpath,"com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher","--fixture","menu:hero-info"]
    client=FixtureClient(command,profile,verify_gui=True)
    result={"test_fixture":True,"counts_as_win":False,"fixture":"hero_info."+hero,"runtime_id":runtime_id,
            "profile":str(profile.relative_to(root)),"cli_language":"en","gui_language":"zh","tabs":[],"details_requested":details}
    try:
        hello=client.request("protocol.info");assert hello["ok"],hello
        result["build_id"]=hello["result"]["build_id"]
        return_to_title(client);choose(client,"Enter the Dungeon")
        state=choose(client,hero)
        if not ui(state)["modal"]:state=choose(client,hero)
        assert ui(state)["scene"]=="HeroSelectScene" and ui(state)["modal"]
        tabs=[a["control"] for a in state["actions"] if a["action"]=="ui.activate"]
        assert len(tabs)==4,tabs
        for index,tab in enumerate(tabs):
            panel=act(client,"ui.activate",control=tab)
            texts=[n["text"] for n in ui(panel)["controls"] if n.get("text")]
            expected=[hero,"Talents","Subclasses","Armor Abilities"][index]
            assert expected.casefold() in {text.casefold() for text in texts},{"expected":expected,"texts":texts}
            result["tabs"].append({"index":index,"title":expected,"state_version":panel["state_version"],"displayed_texts":texts})
            if details and index>=2:result["tabs"][-1]["details"]=details_case(client,panel,tabs,index)
        returned=act(client,"ui.back")
        assert ui(returned)["scene"]=="HeroSelectScene" and not ui(returned)["modal"]
        assert returned["scope_id"] and returned["scope_id"] == panel["scope_id"] and "hero" not in returned["observation"]
        result.update(ok=True,original_back=True,game_created=False)
    except Exception as error:result.update(ok=False,error=repr(error),traceback=traceback.format_exc())
    finally:
        try:
            if result.get("ok"):
                return_to_title(client);client.finish();assert client.process.returncode==0
            elif client.process.poll() is None:client.process.stdin.close();client.process.wait(timeout=35)
        except Exception as error:result.update(ok=False,cleanup_error=repr(error))
        finally:
            if client.process.poll() is None:client.process.terminate();client.process.wait(timeout=10)
            client.stderr.close();client.trace.close()
        result["gui_postconditions_checked"]=client.gui_postconditions_checked
        (profile/"hero-info-result.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps({k:v for k,v in result.items() if k!="tabs"},ensure_ascii=False),flush=True)
    return result


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--heroes",default="warrior,mage,rogue,huntress,duelist,cleric")
    parser.add_argument("--details",action="store_true",help="Also open native subclass, armor ability, and nested talent details")
    args=parser.parse_args();heroes=args.heroes.split(",")
    assert all(hero in {"warrior","mage","rogue","huntress","duelist","cleric"} for hero in heroes)
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports=[run_case(root,classpath,runtime_id,hero,args.details) for hero in heroes]
    output=root/"desktop-control/build/fixtures"/runtime_id/"hero-info-results.json"
    output.write_text(json.dumps(reports,ensure_ascii=False,indent=2)+"\n")
    if not all(r.get("ok") for r in reports):raise SystemExit(1)


if __name__=="__main__":main()
