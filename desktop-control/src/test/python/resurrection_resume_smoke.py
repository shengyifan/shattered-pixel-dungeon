#!/usr/bin/env python3
"""Original Poison death -> ApplicationListener.pause -> fresh JVM Continue, isolated only.

The test agent invokes the original platform lifecycle at the end of a real render
frame. It neither changes a save nor restores game fields. Private save bytes and
scheduler proof are read only for assertions, never for gameplay choices.
"""
import argparse
import json
from pathlib import Path
import sqlite3
import time
import zipfile
from fixture_smoke import FixtureClient, freeze_runtime, reach_game, act
from legacy_save_smoke import launch_command, metadata, safe_profile, save_file, save_json, stop, write_json


def make_agent(root, runtime):
    dependencies=list((root/"desktop-control/build/libs").glob("test-only-engine-boundaries-*.jar"))
    classes=list((root/"desktop-control/build/classes/java/crashAgent").glob("ResurrectionResumeAgent*.class"))
    assert len(dependencies)==1 and classes
    output=runtime/"test-only-resurrection-resume.jar"
    with zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED) as target:
        target.writestr("META-INF/MANIFEST.MF","Manifest-Version: 1.0\nPremain-Class: ResurrectionResumeAgent\n\n")
        with zipfile.ZipFile(dependencies[0]) as source:
            for name in source.namelist():
                if name.startswith("org/objectweb/asm/") and not name.endswith("/"):
                    target.writestr(name,source.read(name))
        for path in classes:target.write(path,path.name)
    return output


def original_pause_fixture(classpath, agent, profile):
    command=launch_command(classpath,fixture=True)
    command[-1]="lowfreq:resurrect"
    command.insert(1,"-javaagent:"+str(agent)+"="+str(profile))
    client=FixtureClient(command,profile)
    try:
        hello=client.request("protocol.info");assert hello["ok"]
        alive=reach_game(client,"WARRIOR")
        assert alive["observation"]["hero"]["hp"]==1
        dead=act(client,"wait")
        assert dead["observation"]["hero"]["hp"]==0,dead
        assert any(a.get("label")=="保留这些物品" for a in dead["actions"]),dead["actions"]
        assert all(a["action"] not in {"app.quit","game.save"} for a in dead["actions"])
        scope=dead["scope_id"]
        (profile/"native-pause.armed").write_text("original ApplicationListener.pause at render-frame end")
        client.process.wait(timeout=30)
        assert client.process.returncode==0
        proof=json.loads((profile/"native-pause-completed.json").read_text())
        assert proof["original_pause_returned"] and proof["hp"]==0 and proof["window"]=="WndResurrect",proof
        assert proof["actor_alive"] and proof["actor_yielded"] and not proof["fullscreen"],proof
        assert proof["language"]=="简体中文" or proof["language"]=="CHI_SMPL",proof
        saved=save_json(save_file(profile))
        hero=saved["hero"]
        assert hero["HP"]==0,hero.keys()
        objects=[]
        def visit(value):
            if isinstance(value,dict):
                if str(value.get("__className","")).endswith(".Ankh"):objects.append(value)
                for child in value.values():visit(child)
            elif isinstance(value,list):
                for child in value:visit(child)
        visit(hero)
        assert len(objects)==1 and not objects[0].get("blessed",False),objects
        assert "run:"+saved["run_uuid"]==scope
        with sqlite3.connect((profile/"audit/public.sqlite3").as_uri()+"?mode=ro",uri=True) as db:
            receipts=db.execute("SELECT receipt_id,success FROM save_checkpoints WHERE scope_id=?",(scope,)).fetchall()
            assert any(success for _,success in receipts),receipts
        (profile/"native-pause.armed").unlink()
        return {"scope_id":scope,"build_id":hello["result"]["build_id"],"original_pause":proof,
                "saved_hp":hero["HP"],"saved_unblessed_ankhs":len(objects),"save_receipts":len(receipts)}
    finally:
        if client.process.poll() is None:client.process.terminate();client.process.wait(timeout=10)
        client.stderr.close();client.trace.close()


def continue_public(client, profile):
    for _ in range(20):
        state=client.state()
        if state["observation"].get("scene")=="game":return state,None
        choices=[a for a in state["actions"] if a["action"]=="ui.activate" and a.get("label")]
        choice=None
        for label in ["继续","continue","进入地牢","enter","开始游戏","play","战士","warrior"]:
            choice=next((a for a in choices if label in a["label"].lower()),None)
            if choice:break
        if choice is None and any(a["action"]=="ui.reveal" for a in state["actions"]):
            act(client,"ui.reveal");continue
        assert choice,{"scene":state["observation"].get("scene"),"choices":choices}
        start=time.monotonic()
        result=client.request("action.execute",{"action":"ui.activate","control":choice["control"]})
        if result.get("error",{}).get("code")=="STALE_STATE":continue
        assert result.get("ok"),result
        if result.get("status")=="in_progress":
            proof=json.loads((profile/"resume-observed.json").read_text())
            record=client.request("request.get",{"target_id":result["id"]},scope=result["scope_id"])
            assert record["ok"],record
            return None,{"response_seconds":round(time.monotonic()-start,3),"initial_status":result["status"],
                         "logical_status":record["result"]["status"],"request_id":result["id"],"scheduler":proof}
        if result["result"]["observation"].get("scene")=="game":return result["result"],None
    raise AssertionError("Could not select the original Continue control")


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--expect",choices=["blocked","ready"],required=True);args=parser.parse_args()
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text())
    runtime=root/"desktop-control/build/fixtures"/runtime_id
    agent=make_agent(root,runtime);profile=safe_profile(root,"resurrection-resume")
    metadata(profile,"lowfreq:resurrect-resume",runtime_id)
    prepared=original_pause_fixture(classpath,agent,profile)
    (profile/"resume-observe.armed").write_text("read-only scheduler assertions after real Continue")
    command=launch_command(classpath);command.insert(1,"-javaagent:"+str(agent)+"="+str(profile))
    client=FixtureClient(command,profile)
    result={"test_fixture":True,"counts_as_win":False,"runtime_id":runtime_id,"profile":str(profile.relative_to(root)),
            "language":"CHI_SMPL","fullscreen":False,"expected":args.expect,"prepared":prepared}
    try:
        hello=client.request("protocol.info");assert hello["ok"]
        assert hello["result"]["build_id"]==prepared["build_id"]
        state,blocked=continue_public(client,profile)
        if args.expect=="blocked":
            assert state is None and blocked["logical_status"]=="EXECUTING",blocked
            assert blocked["scheduler"]["actor_thread"] is None and not blocked["scheduler"]["actor_yielded"],blocked
            assert blocked["scheduler"]["hp"]==0 and blocked["scheduler"]["window"]=="WndResurrect",blocked
            result.update(verified=True,reproduced_blocked=True,blocked=blocked)
        else:
            assert state is not None and blocked is None,blocked
            assert state["scope_id"]==prepared["scope_id"] and state["phase"]=="awaiting_input",state
            assert state["observation"]["hero"]["hp"]==0
            assert any(a.get("label")=="保留这些物品" for a in state["actions"]),state["actions"]
            proof=json.loads((profile/"resume-observed.json").read_text())
            assert not proof["actor_alive"] and proof["actor_thread"] is None,proof
            again=client.state();assert again["state_version"]==state["state_version"]
            for action in ["app.quit","game.save","wait"]:
                assert all(a["action"]!=action for a in again["actions"])
                rejected=client.request("action.execute",{"action":action})
                assert rejected.get("error",{}).get("code")=="ACTION_UNAVAILABLE",rejected
            unchanged=act(client,"ui.back")
            assert any(a.get("label")=="保留这些物品" for a in unchanged["actions"])
            item=next(a for a in unchanged["actions"] if a["action"]=="ui.activate" and "短剑" in a.get("label",""))
            selected=act(client,"ui.activate",control=item["control"])
            assert selected["phase"]=="awaiting_input"
            back=act(client,"ui.back")
            confirm=next(a for a in back["actions"] if a.get("label")=="保留这些物品")
            revived=act(client,"ui.activate",control=confirm["control"])
            assert revived["scope_id"]==prepared["scope_id"] and revived["observation"]["hero"]["hp"]>0,revived
            assert all("重生十字架" not in item["name"] for item in revived["observation"]["inventory"])
            saved=act(client,"game.save")
            assert saved["persistence"]["saves_during_request"]
            result.update(verified=True,reproduced_blocked=False,restored_window=proof,
                          blocked_actions_still_rejected=True,mandatory_back_still_blocked=True,
                          item_selector_cancelled=True,same_scope_after_original_resurrection=True,
                          original_ankh_consumed=True,post_resurrection_save_confirmed=True)
            stop(client)
        write_json(profile/"resurrection-resume-result.json",result)
        print(json.dumps(result,ensure_ascii=False))
    finally:
        # A deliberately reproduced pending loop cannot normally quit; test cleanup only.
        if client.process.poll() is None:client.process.terminate();client.process.wait(timeout=10)
        client.stderr.close();client.trace.close()


if __name__=="__main__":main()
