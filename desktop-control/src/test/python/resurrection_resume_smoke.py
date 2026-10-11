#!/usr/bin/env python3
"""Original Poison death -> ApplicationListener.pause -> fresh JVM Continue, isolated only.

The test agent invokes the original platform lifecycle at the end of a real render
frame. It neither changes a save nor restores game fields. Private save bytes and
scheduler proof are read only for assertions, never for gameplay choices.
"""
import argparse
from fixture_identity import fixture_canonical_identity
import json
from protocol9 import frame_data
from pathlib import Path
import sqlite3
import time
import zipfile
from fixture_smoke import FixtureClient, freeze_runtime, reach_game, act
from fixture_support import launch_command, metadata, safe_profile, save_file, save_json, stop, write_json


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
        hello=client.request('info');assert ("err" not in hello)
        alive=reach_game(client,"WARRIOR")
        assert alive["hero"]["hp"]==1
        dead=act(client,"wait")
        assert dead["hero"]["hp"]==0,dead
        assert any(a.get("label")=="Preserve these items" for a in dead['acts']),dead['acts']
        assert all(a['op'] not in {'quit','save'} for a in dead['acts'])
        scope=dead['s']
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
        canonical_scope=fixture_canonical_identity(profile,"scope",scope)
        assert "run:"+saved["run_uuid"]==canonical_scope
        with sqlite3.connect((profile/"audit/public.sqlite3").as_uri()+"?mode=ro",uri=True) as db:
            receipts=db.execute("SELECT receipt_id,success FROM save_checkpoints WHERE scope_id=?",(canonical_scope,)).fetchall()
            assert any(success for _,success in receipts),receipts
        (profile/"native-pause.armed").unlink()
        return {'s':scope,"build_id":hello["data"]["build_id"],"original_pause":proof,
                "saved_hp":hero["HP"],"saved_unblessed_ankhs":len(objects),"save_receipts":len(receipts)}
    finally:
        if client.process.poll() is None:client.process.terminate();client.process.wait(timeout=10)
        client.stderr.close();client.trace.close()


def continue_public(client, profile):
    for _ in range(20):
        state=client.state()
        if state.get("scene")=="game":return state,None
        choices=[a for a in state['acts'] if a['op']=='click' and a.get("label")]
        choice=None
        for label in ["continue","enter","play","warrior"]:
            choice=next((a for a in choices if label in a["label"].lower()),None)
            if choice:break
        if choice is None and any(a['op']=='reveal' for a in state['acts']):
            act(client,'reveal');continue
        assert choice,{"scene":state.get("scene"),"choices":choices}
        start=time.monotonic()
        result=client.request_action({'op':'click','ctl':choice['ctl']})
        if result.get("err")=="STALE_STATE":continue
        assert "err" not in result,result
        if result.get("st")=="in_progress":
            proof=json.loads((profile/"resume-observed.json").read_text())
            record=client.request('req',{'rid':result["id"]},scope=result['s'])
            assert ("err" not in record),record
            return None,{"response_seconds":round(time.monotonic()-start,3),"initial_status":result["st"],
                         "logical_status":record["data"]["st"],"request_id":result["id"],"scheduler":proof}
        if result["data"].get("scene")=="game":return frame_data(result),None
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
        hello=client.request('info');assert ("err" not in hello)
        assert hello["data"]["build_id"]==prepared["build_id"]
        state,blocked=continue_public(client,profile)
        if args.expect=="blocked":
            assert state is None and blocked["logical_status"]=="EXECUTING",blocked
            assert blocked["scheduler"]["actor_thread"] is None and not blocked["scheduler"]["actor_yielded"],blocked
            assert blocked["scheduler"]["hp"]==0 and blocked["scheduler"]["window"]=="WndResurrect",blocked
            result.update(verified=True,reproduced_blocked=True,blocked=blocked)
        else:
            assert state is not None and blocked is None,blocked
            assert state['s']==prepared['s'] and state["phase"]=="awaiting_input",state
            assert state["hero"]["hp"]==0
            assert any(a.get("label")=="Preserve these items" for a in state['acts']),state['acts']
            proof=json.loads((profile/"resume-observed.json").read_text())
            assert not proof["actor_alive"] and proof["actor_thread"] is None,proof
            again=client.state();assert again['rev']==state['rev']
            for action in ['quit','save',"wait"]:
                assert all(a['op']!=action for a in again['acts'])
                rejected=client.request_action({'op':action})
                assert rejected.get("err")=="ACTION_UNAVAILABLE",rejected
            unchanged=act(client,'back')
            assert any(a.get("label")=="Preserve these items" for a in unchanged['acts'])
            item=next(a for a in unchanged['acts'] if a['op']=='click' and "shortsword" in a.get("label","").lower())
            selected=act(client,'click',ctl=item['ctl'])
            assert selected["phase"]=="awaiting_input"
            back=act(client,'back')
            confirm=next(a for a in back['acts'] if a.get("label")=="Preserve these items")
            revived=act(client,'click',ctl=confirm['ctl'])
            assert revived['s']==prepared['s'] and revived["hero"]["hp"]>0,revived
            assert all("ankh" not in item["name"].lower() for item in revived['inv'])
            saved=act(client,'save')
            assert saved["persistence"]['saves']
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
