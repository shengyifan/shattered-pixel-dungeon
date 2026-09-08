#!/usr/bin/env python3
"""Kill the real game JVM at test-only bytecode barriers, then use public recovery APIs."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sqlite3
import threading
import time
import uuid
from fixture_smoke import FixtureClient, freeze_runtime, reach_game as fixture_start
from machine_smoke import reach_game
from legacy_save_smoke import launch_command, metadata, safe_profile, stop, write_json

STAGES = ["before-register", "after-register", "before-intent", "after-intent", "before-callback",
          "after-callback", "after-settlement-before-result", "after-result-before-output",
          "between-save-files", "inside-dual-transaction", "before-new-run-init"]


def new_game_button(client):
    selected=False
    for _ in range(35):
        state=client.state()
        ui=state["observation"]["ui"]
        options=[a for a in state["actions"] if a["action"]=="ui.activate" and a.get("label")]
        if state["observation"]["scene"]=="hero_select":
            if ui.get("modal"):
                client.act("ui.back");continue
            if not selected:
                warrior=next(a for a in options if a["label"].lower()=="warrior")
                client.act("ui.activate",control=warrior["control"]);selected=True;continue
            start=next(a for a in options if a["label"].lower() in {"start","开始"})
            return state,{"action":"ui.activate","control":start["control"]}
        choice=None
        for term in ["enter","continue","play","new game","start"]:
            choice=next((a for a in options if term in a["label"].lower()),None)
            if choice:break
        if choice:client.act("ui.activate",control=choice["control"])
        elif any(a["action"]=="ui.reveal" for a in state["actions"]):client.act("ui.reveal")
        else:raise AssertionError(state)
    raise AssertionError("No new game start control")


def database_record(profile, scope, request_id):
    rows=[]
    for name in ["public","internal"]:
        # Opens and lets SQLite recover hot rollback journals before application recovery.
        with sqlite3.connect(profile / "audit" / (name+".sqlite3")) as db:
            assert db.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
            row=db.execute("SELECT status,response_json,before_snapshot,after_snapshot,target_scope FROM requests WHERE scope_id=? AND id=?",(scope,request_id)).fetchone()
            rows.append((row,db.execute("SELECT value FROM metadata WHERE key='pair_generation'").fetchone()))
    assert rows[0]==rows[1],rows
    return rows[0][0]


def run_case(root,classpath,agent,template,runtime_id,stage):
    profile=safe_profile(root,"engine-crash-"+stage)
    if stage!="before-new-run-init":
        for entry in template.iterdir():
            if entry.name=="settings.xml" or entry.name.startswith("game"):
                if entry.is_dir():shutil.copytree(entry,profile/entry.name)
                else:shutil.copy2(entry,profile/entry.name)
    metadata(profile,"engine-crash:"+stage,runtime_id,barrier=stage)
    command=launch_command(classpath)
    command.insert(1,"-javaagent:"+str(agent)+"="+stage+";"+str(profile))
    client=FixtureClient(command,profile)
    try:
        assert client.request("protocol.info")["ok"]
        if stage=="before-new-run-init":
            state,action=new_game_button(client)
        else:
            reach_game(client,resume=True)
            state=client.state()
            action={"action":"game.save" if stage=="between-save-files" else "wait"}
            assert any(a["action"]==action["action"] for a in state["actions"]),state["actions"]
            if stage=="between-save-files":
                client.act("wait")
                state=client.state()
        scope=client.scope
        request_id="crash-"+uuid.uuid4().hex
        before_files={str(p.relative_to(profile)):p.read_bytes() for p in profile.glob("game*/*.dat")}
        request={"id":request_id,"scope_id":scope,"op":"action.execute","state_version":state["state_version"],"args":action}
        write_json(profile/"target-request.json",request)
        (profile/"barrier.armed").write_text(request_id)
        target_result={}
        def send():
            try:target_result["response"]=client.request("action.execute",action,request_id=request_id,version=state["state_version"])
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
            assert restarted.request("protocol.info")["ok"]
            history=restarted.request("request.get",{"target_id":request_id},scope=scope)
            if absent:
                assert not history["ok"] and history["error"]["code"]=="REQUEST_NOT_FOUND",history
            else:
                assert history["ok"],history
                terminal="NOT_EXECUTED" if received else "COMPLETED" if completed else "UNKNOWN"
                assert history["result"]["status"]==terminal,history
                duplicate=restarted.request("request.get",{"target_id":request_id},request_id=request_id,scope=scope)
                assert duplicate["error"]["code"]=="DUPLICATE_REQUEST_ID",duplicate
                if completed:assert history["result"]["response"]["ok"]
            if stage=="before-new-run-init":
                assert not list(profile.glob("game*/game.dat")),"An interrupted planned run must not be auto-created"
                with sqlite3.connect(profile/"audit/public.sqlite3") as db:
                    assert db.execute("SELECT kind FROM scopes WHERE scope_id=?",(raw[4],)).fetchone()==("planned",)
        finally:stop(restarted)
        result={"stage":stage,"result":"passed","test_fixture":True,"counts_as_win":False,
                "profile":str(profile.relative_to(root)),"runtime_id":runtime_id,"scope_id":scope,"request_id":request_id,
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
        assert client.request("protocol.info")["ok"]
        fixture_start(client,"WARRIOR")
        client.act("game.save")
    finally:stop(client)
    results=[]
    for stage in args.stages.split(","):
        assert stage in STAGES,stage
        result=run_case(root,classpath,agent,template,runtime_id,stage)
        results.append(result);write_json(runtime/"engine-crash-results.json",results)
        print(json.dumps(result,ensure_ascii=False),flush=True)


if __name__=="__main__":main()
