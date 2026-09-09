#!/usr/bin/env python3
"""Native item-window callbacks with isolated prepared inventory and public-only choices."""
import argparse
import json
from pathlib import Path
import traceback
import uuid
from fixture_smoke import FixtureClient, act, checkpoint, freeze_runtime, reach_game, select_inventory_item
from menu_scenario_smoke import choose, ui


def stone_count(state):
    return sum(item["quantity"] for item in state["observation"]["inventory"] if item["name"].casefold()=="stone of augmentation")


def open_augmentation(client,name):
    state=client.state()
    stone=next(item for item in state["observation"]["inventory"] if item["name"].casefold()=="stone of augmentation")
    act(client,"inventory.open",locator=stone["locator"]);choose(client,"USE")
    selected=select_inventory_item(client,lambda label:label.casefold().startswith(name))
    assert ui(selected)["modal"]
    assert any("What would you like to enhance?" in node.get("text","") for node in ui(selected)["controls"])
    return selected


def augmentation(client,name,report):
    initial=reach_game(client,"WARRIOR")
    assert initial["observation"]["hero"]["level"]==1 and stone_count(initial)==3
    target="worn shortsword" if name.endswith("weapon") else "cloth armor"
    field="weapon_augment" if name.endswith("weapon") else "armor_augment"
    public_choice="Speed" if name.endswith("weapon") else "Evasion"
    expected="SPEED" if name.endswith("weapon") else "EVASION"
    assert checkpoint(client.profile,initial["state_version"])["item_window"][field]=="NONE"
    opened=open_augmentation(client,target)
    cancelled=choose(client,"Never mind")
    assert stone_count(cancelled)==3 and checkpoint(client.profile,cancelled["state_version"])["item_window"][field]=="NONE"
    open_augmentation(client,target)
    backed=act(client,"ui.back")
    assert stone_count(backed)==3 and checkpoint(client.profile,backed["state_version"])["item_window"][field]=="NONE"
    open_augmentation(client,target)
    enhanced=choose(client,public_choice)
    assert stone_count(enhanced)==2 and checkpoint(client.profile,enhanced["state_version"])["item_window"][field]==expected
    choices=open_augmentation(client,target)
    labels={a.get("label") for a in choices["actions"]}
    assert "Remove Augmentation" in labels and public_choice not in labels
    removed=choose(client,"Remove Augmentation")
    assert stone_count(removed)==1 and checkpoint(client.profile,removed["state_version"])["item_window"][field]=="NONE"
    report.update(original_cancel=True,original_back=True,original_augmentation=expected,
                  original_remove_augmentation=True,current_option_not_advertised=True,stones_consumed=2)


def run_case(root,classpath,runtime_id,name):
    profile=root/"desktop-control/build/fixtures"/("item-window-"+name+"-"+uuid.uuid4().hex);profile.mkdir(parents=True)
    command=["java","-XstartOnFirstThread","--enable-native-access=ALL-UNNAMED","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
             "-cp",classpath,"com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher","--fixture","itemui:"+name]
    client=FixtureClient(command,profile,verify_gui=True)
    report={"test_fixture":True,"counts_as_win":False,"fixture":"itemui:"+name,"profile":str(profile.relative_to(root)),
            "runtime_id":runtime_id,"cli_language":"en","gui_language":"zh"}
    try:
        hello=client.request("protocol.info");assert hello["ok"],hello;report["build_id"]=hello["result"]["build_id"]
        augmentation(client,name,report)
        client.finish();assert client.process.returncode==0
        report["ok"]=True
    except Exception as error:report.update(ok=False,error=repr(error),traceback=traceback.format_exc())
    finally:
        if client.process.poll() is None:
            try:
                client.process.stdin.close();client.process.wait(timeout=35)
            except Exception as error:
                report.update(ok=False,cleanup_error=repr(error));client.process.terminate();client.process.wait(timeout=10)
        client.stderr.close();client.trace.close()
        report["gui_postconditions_checked"]=client.gui_postconditions_checked
        (profile/"item-window-result.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps(report,ensure_ascii=False),flush=True);return report


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--cases",default="augment-weapon,augment-armor")
    names=parser.parse_args().cases.split(",");assert all(n in {"augment-weapon","augment-armor"} for n in names)
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports=[run_case(root,classpath,runtime_id,name) for name in names]
    (root/"desktop-control/build/fixtures"/runtime_id/"item-window-results.json").write_text(json.dumps(reports,ensure_ascii=False,indent=2)+"\n")
    if not all(r.get("ok") for r in reports):raise SystemExit(1)


if __name__=="__main__":main()
