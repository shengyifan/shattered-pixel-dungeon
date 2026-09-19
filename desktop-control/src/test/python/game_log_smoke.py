#!/usr/bin/env python3
"""Known text fixture, actual renderer, public event assertions. Not victory evidence."""
import json
from pathlib import Path
import time
import uuid
from protocol5 import pages
import zipfile
from fixture_smoke import FixtureClient, freeze_runtime, reach_game, act
from legacy_save_smoke import launch_command, metadata, safe_profile, stop, write_json


def make_agent(root, runtime):
    jars=list((root/"desktop-control/build/libs").glob("test-only-engine-boundaries-*.jar"));assert len(jars)==1
    classes=list((root/"desktop-control/build/classes/java/crashAgent").glob("GameLogFixtureAgent*.class"));assert classes
    output=runtime/"test-only-game-log.jar"
    with zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED) as out:
        out.writestr("META-INF/MANIFEST.MF","Manifest-Version: 1.0\nPremain-Class: GameLogFixtureAgent\n\n")
        with zipfile.ZipFile(jars[0]) as source:
            for name in source.namelist():
                if name.startswith("org/objectweb/asm/") and not name.endswith("/"):out.writestr(name,source.read(name))
        for path in classes:out.write(path,path.name)
    return output


def stage(client, profile, mode):
    token=uuid.uuid4().hex+"|"+mode
    (profile/"log-fixture.command").write_text(token)
    until=time.monotonic()+30
    while time.monotonic()<until:
        if client.process.poll() is not None:raise AssertionError("Fixture exited")
        result=profile/"log-fixture.done.json"
        if result.exists():
            data=json.loads(result.read_text())
            if data.get("command")==token:
                client.state()
                return data
        time.sleep(.03)
    raise TimeoutError("No completed drawn log stage: "+mode)


def events(client, scope):
    found=[]
    for rows in pages(client,"events.read",scope=scope):
        found.extend(e for e in rows if e["kind"]=="game.log")
    return found


def rendered(events):
    return [line for event in events for line in event["data"]["entries"]]


def new_game_in_same_process(client):
    act(client,"ui.back")
    state=client.state()
    back=next(a for a in state["actions"] if a.get("action")=="ui.activate" and any(label in a.get("label","").lower() for label in ["main menu","主菜单"]))
    act(client,"ui.activate",control=back["control"])
    for _ in range(12):
        state=client.state();scene=state["observation"]["scene"]
        if scene=="hero_select":return reach_game(client,"WARRIOR")
        actions=[a for a in state["actions"] if a.get("action")=="ui.activate"]
        choice=next((a for a in actions if any(label in a.get("label","").lower() for label in ["new game","新游戏"])),None)
        if choice is None:choice=next((a for a in actions if a.get("label","").lower() in {"play","enter the dungeon","进入地牢","开始游戏"}),None)
        if choice is None and any(a["action"]=="ui.reveal" for a in state["actions"]):
            act(client,"ui.reveal");continue
        assert choice,state
        act(client,"ui.activate",control=choice["control"])
    raise AssertionError("Could not enter the next new game")


def main():
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text())
    runtime=root/"desktop-control/build/fixtures"/runtime_id
    agent=make_agent(root,runtime);profile=safe_profile(root,"game-log");metadata(profile,"class:WARRIOR",runtime_id)
    command=launch_command(classpath,fixture=True);command.insert(1,"-javaagent:"+str(agent)+"="+str(profile))
    client=FixtureClient(command,profile)
    try:
        hello=client.request("protocol.info");assert hello["ok"] and hello["result"]["build_id"]
        first=reach_game(client,"WARRIOR");scope=first["scope_id"]
        fixture=json.loads((profile/"test_fixture.json").read_text());assert fixture["language"]=="CHI_SMPL" and fixture["fullscreen"] is False,fixture
        before=events(client,scope);last=before[-1]["sequence"] if before else 0
        stage(client,profile,"burst")
        burst=[e for e in events(client,scope) if e["sequence"]>last]
        text=json.dumps(rendered(burst));assert "BURST_17" in text and "BURST_00" not in text,text
        assert all(e["data"]["format"]=="display_snapshot_v1" for e in burst)

        long=stage(client,profile,"long")
        assert any("TOP_NEVER_VISIBLE" in value and "BOTTOM_VISIBLE" in value for value in long["raw_control_texts"])
        log=rendered(events(client,scope))
        assert any("BOTTOM_VISIBLE" in line["text"] and line["clipped"] for line in log)
        assert not any("TOP_NEVER_VISIBLE" in line["text"] for line in log)
        state=client.state();assert "TOP_NEVER_VISIBLE" not in json.dumps(state["observation"]["ui"])
        assert "BOTTOM_VISIBLE" in json.dumps(state["observation"]["ui"])
        assert "BURST_17" in json.dumps(log),"The older displayed snapshot must remain in public history"

        stage(client,profile,"same");a=events(client,scope)
        assert sum("REPEATED_VISIBLE_TEXT" in line["text"] for line in rendered(a))==1
        rebuilt=stage(client,profile,"rebuild");assert rebuilt["rebuilt"]
        assert len(events(client,scope))==len(a),"An unchanged rebuilt UI must not duplicate snapshots"
        stage(client,profile,"console")
        assert "PRIVATE_CONSOLE_LOG_FIXTURE" not in json.dumps(events(client,scope))
        assert len(events(client,scope))==len(a)
        second=new_game_in_same_process(client);new_scope=second["scope_id"];assert new_scope!=scope
        stage(client,profile,"same")
        b=events(client,new_scope)
        assert sum("REPEATED_VISIBLE_TEXT" in line["text"] for line in rendered(b))==1
        assert all(e["scope_id"]==new_scope for e in b)
        assert all("TOP_NEVER_VISIBLE" not in line["text"] and "BURST_17" not in line["text"] for line in rendered(b))
        result={"verified":True,"test_fixture":True,"counts_as_win":False,"runtime_id":runtime_id,
                "profile":str(profile.relative_to(root)),"build_id":hello["result"]["build_id"],"language":"CHI_SMPL","fullscreen":False,
                "first_scope":scope,"second_scope":new_scope,"burst_trim_verified":True,
                "long_raw_text_exists_but_top_not_exported":True,"clipped_tail_visible":True,
                "older_display_retained_in_history":True,"same_run_ui_rebuild_deduplicated":True,
                "new_run_same_text_recorded":True,"console_not_exported":True}
        stop(client);write_json(profile/"game-log-result.json",result);print(json.dumps(result,ensure_ascii=False))
    finally:
        if client.process.poll() is None:stop(client)
        client.stderr.close();client.trace.close()


if __name__=="__main__":main()
