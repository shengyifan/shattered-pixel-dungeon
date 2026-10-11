#!/usr/bin/env python3
"""Kill the real game JVM at test-only bytecode barriers, then use public recovery APIs."""
import argparse
from fixture_identity import fixture_canonical_identity
import json
import os
from pathlib import Path
import shutil
import sqlite3
import threading
import time
import uuid
import protocol9
from fixture_smoke import FixtureClient, freeze_runtime, reach_game as fixture_start
from machine_smoke import reach_game
from fixture_support import copy_audited_profile, launch_command, metadata, safe_profile, stop, write_json

STAGES = ["before-register", "after-register", "before-intent", "after-intent", "before-callback",
          "after-callback", "after-settlement-before-result", "after-result-before-output",
          "between-save-files", "inside-dual-transaction", "before-new-run-init"]


def new_game_button(client):
    selected=False
    for _ in range(35):
        state=client.state()
        ui=state["ui"]
        options=[a for a in state["acts"] if a["op"]=="click" and a.get("label")]
        if state["scene"]=="hero_select":
            if ui.get("modal"):
                client.act("back");continue
            if not selected:
                warrior=next(a for a in options if a["label"].lower()=="warrior")
                client.act("click",ctl=warrior["ctl"]);selected=True;continue
            start=next(a for a in options if a["label"].lower() in {"start","开始"})
            return state,{"op":"click","ctl":start["ctl"]}
        choice=None
        for term in ["enter","continue","play","new game","start"]:
            choice=next((a for a in options if term in a["label"].lower()),None)
            if choice:break
        if choice:client.act("click",ctl=choice["ctl"])
        elif any(a["op"]=="reveal" for a in state["acts"]):client.act("reveal")
        else:raise AssertionError(state)
    raise AssertionError("No new game start control")


def database_record(profile, scope, request_id):
    rows=[]
    for name in ["public","internal"]:
        # Opens and lets SQLite recover hot rollback journals before application recovery.
        with sqlite3.connect(profile / "audit" / (name+".sqlite3")) as db:
            assert db.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
            canonical_scope=fixture_canonical_identity(profile,"scope",scope)
            row=db.execute("SELECT status,response_json,before_snapshot,after_snapshot,target_scope FROM requests WHERE scope_id=? AND id=?",(canonical_scope,request_id)).fetchone()
            rows.append((row,db.execute("SELECT value FROM metadata WHERE key='pair_generation'").fetchone()))
    assert rows[0]==rows[1],rows
    return rows[0][0]


def run_case(root,classpath,agent,template,runtime_id,stage):
    profile=safe_profile(root,"engine-crash-"+stage)
    if stage!="before-new-run-init":
        copy_audited_profile(template, profile)
    metadata(profile,"engine-crash:"+stage,runtime_id,barrier=stage)
    command=launch_command(classpath)
    command.insert(1,"-javaagent:"+str(agent)+"="+stage+";"+str(profile))
    client=FixtureClient(command,profile)
    try:
        assert "err" not in client.request("info")
        if stage=="before-new-run-init":
            state,action=new_game_button(client)
        else:
            reach_game(client,resume=True)
            state=client.state()
            action={"op":"save" if stage=="between-save-files" else "wait"}
            assert any(a["op"]==action["op"] for a in state["acts"]),state["acts"]
            if stage=="between-save-files":
                client.act("wait")
                state=client.state()
        scope=client.scope
        request_id="crash-"+uuid.uuid4().hex
        before_files={str(p.relative_to(profile)):p.read_bytes() for p in profile.glob("game*/*.dat")}
        arguments={key:value for key,value in action.items() if key!="op"}
        request=protocol9.request(action["op"], arguments, request_id, scope, state["rev"])
        write_json(profile/"target-request.json",request)
        (profile/"barrier.armed").write_text(request_id)
        target_result={}
        def send():
            try:target_result["response"]=client.request(action["op"],arguments,request_id=request_id,version=state["rev"])
            except Exception as error:target_result["pipe_error"]=type(error).__name__
        worker=threading.Thread(target=send,daemon=True);worker.start()
        deadline=time.monotonic()+40
        marker=profile/"barrier.reached"
        while not marker.exists() and time.monotonic()<deadline and worker.is_alive():time.sleep(0.025)
        assert marker.exists(),{"barrier_missing":stage,"target":target_result,"profile":str(profile)}
        assert marker.read_text().splitlines()[0]==stage
        client.process.kill();client.process.wait(timeout=10);worker.join(timeout=5)
        assert client.process.returncode==-9
        assert "response" not in target_result,target_result
        client.stderr.close();client.trace.close()
        (profile/"barrier.armed").unlink()
        raw=database_record(profile,scope,request_id)
        absent=stage in {"before-register","inside-dual-transaction"}
        received=stage in {"after-register","before-intent"}
        completed=stage=="after-result-before-output"
        expected=None if absent else "RECEIVED" if received else "COMPLETED" if completed else "EXECUTING"
        assert (raw[0] if raw else None)==expected,(stage,raw)
        if raw and not completed:assert raw[1] is None and raw[3] is None,raw
        changed=[name for name,body in before_files.items() if (profile/name).read_bytes()!=body]
        if stage=="between-save-files":
            assert any(name.endswith("/game.dat") for name in changed),changed
            assert all(name.endswith("/game.dat") for name in changed),changed
        restarted=FixtureClient(launch_command(classpath),profile)
        try:
            assert "err" not in restarted.request("info")
            history=restarted.request("req",{"rid":request_id, "get": ["reply"]},scope=scope)
            if absent:
                assert history["err"]=="REQUEST_NOT_FOUND",history
            else:
                assert ("err" not in history),history
                terminal="NOT_EXECUTED" if received else "COMPLETED" if completed else "UNKNOWN"
                assert history["data"]["st"]==terminal,history
                duplicate=restarted.request("req",{"rid":request_id, "get": ["reply"]},request_id=request_id,scope=scope)
                assert duplicate["err"]=="DUPLICATE_REQUEST_ID",duplicate
                if completed:assert "err" not in history["data"]["reply"]
            if stage=="before-new-run-init":
                assert not list(profile.glob("game*/game.dat")),"An interrupted planned run must not be auto-created"
                with sqlite3.connect(profile/"audit/public.sqlite3") as db:
                    assert db.execute("SELECT kind FROM scopes WHERE scope_id=?",(raw[4],)).fetchone()==("planned",)
        finally:stop(restarted)
        result={"stage":stage,"result":"passed","test_fixture":True,"counts_as_win":False,
                "profile":str(profile.relative_to(root)),"runtime_id":runtime_id,"s":scope,"request_id":request_id,
                "raw_status":expected,"recovered_status":None if absent else terminal,"received_response":False,
                "changed_game_files":changed,"barrier_stack":marker.read_text().splitlines()}
        write_json(profile/"engine-crash-result.json",result)
        return result
    finally:
        if client.process.poll() is None:client.process.kill();client.process.wait(timeout=10)
        client.stderr.close();client.trace.close()


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--stages",default=",".join(STAGES));args=parser.parse_args()
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text())
    runtime=root/"desktop-control/build/fixtures"/runtime_id
    agent=runtime/"engine-barriers.jar"
    jars=list((root/"desktop-control/build/libs").glob("test-only-engine-boundaries-*.jar"));assert len(jars)==1,jars
    shutil.copy2(jars[0],agent)
    template=safe_profile(root,"engine-crash-template")
    metadata(template,"class:WARRIOR",runtime_id)
    client=FixtureClient(launch_command(classpath,fixture=True),template)
    try:
        assert "err" not in client.request("info")
        fixture_start(client,"WARRIOR")
        client.act("save")
    finally:stop(client)
    results=[]
    for stage in args.stages.split(","):
        assert stage in STAGES,stage
        result=run_case(root,classpath,agent,template,runtime_id,stage)
        results.append(result);write_json(runtime/"engine-crash-results.json",results)
        print(json.dumps(result,ensure_ascii=False),flush=True)


if __name__=="__main__":main()
