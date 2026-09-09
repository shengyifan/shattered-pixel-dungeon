#!/usr/bin/env python3
"""Trigger-before intermediate scenes, original CLI transitions, Chinese/windowed fixtures only."""
import argparse
import json
from pathlib import Path
import time
from fixture_smoke import FixtureClient, freeze_runtime, reach_game, act, checkpoint
from legacy_save_smoke import launch_command, metadata, safe_profile, stop, write_json


def choose(client,label):
    state=client.state()
    choice=next((a for a in state["actions"] if a["action"]=="ui.activate" and a.get("label")==label),None)
    assert choice,{"missing":label,"actions":state["actions"]}
    return act(client,"ui.activate",control=choice["control"])


def readback(profile,state):
    return checkpoint(profile,state["state_version"])["transition_scenario"]


def original_story(client,state):
    if state["observation"]["ui"]["scene"]!="InterlevelScene":return state,False
    assert state["phase"]=="awaiting_input",state
    assert any(a.get("label")=="继续" for a in state["actions"]),state["actions"]
    assert len(" ".join(str(c.get("text","")) for c in state["observation"]["ui"]["controls"]))>40
    after=choose(client,"继续")
    assert after["observation"]["scene"]=="game",after
    return after,True


def falling(client,profile,initial):
    observation=initial["observation"];position=observation["hero"]["cell"];width=observation["map"]["width"]
    chasms=[c for c in observation["map"]["cells"] if "深渊" in c["name"]
            and max(abs(c["cell"]%width-position%width),abs(c["cell"]//width-position//width))==1]
    assert chasms,observation["map"]
    before_hp=observation["hero"]["hp"];before_depth=observation["hero"]["depth"]
    prompt=act(client,"cell.select",cell=chasms[0]["cell"])
    assert prompt["phase"]=="awaiting_input"
    time.sleep(.25)  # The original jump prompt deliberately rejects inputs for 0.2 seconds.
    cancelled=choose(client,"不，我改主意了")
    assert cancelled["observation"]["hero"]["hp"]==before_hp and cancelled["observation"]["hero"]["cell"]==position
    act(client,"cell.select",cell=chasms[0]["cell"]);time.sleep(.25)
    landed=choose(client,"是的，我知道我在做什么")
    assert landed["observation"]["scene"]=="game" and landed["phase"]=="player_ready",landed
    hero=landed["observation"]["hero"]
    assert hero["depth"]==before_depth+1 and 0<hero["hp"]<before_hp,hero
    evidence=checkpoint(profile,landed["state_version"])
    assert any(name.endswith("Bleeding") for name in evidence["effect_buffs"]),evidence
    assert any(name.endswith("Cripple") for name in evidence["effect_buffs"]),evidence
    assert not any(name.endswith("Falling") for name in evidence["effect_buffs"]),evidence
    assert landed["scope_id"]==initial["scope_id"]
    return {"case_id":"transition.fall","original_jump_cancel":True,"original_jump_confirm":True,
            "landing_finished_in_final_response":True,"hp_before":before_hp,"hp_after":hero["hp"],
            "depth_after":hero["depth"],"bleeding_and_cripple_applied":True,"same_scope":True}


def branch(client,profile,initial):
    before=readback(profile,initial);assert before["depth"]==14 and before["branch"]==0 and before["level_class"]=="CavesLevel",before
    prompt=act(client,"cell.select",cell=initial["observation"]["hero"]["cell"])
    assert any(a.get("label")=="我准备好了" for a in prompt["actions"]),prompt["actions"]
    cancel=choose(client,"还没有");assert readback(profile,cancel)["branch"]==0
    act(client,"cell.select",cell=cancel["observation"]["hero"]["cell"])
    inside=choose(client,"我准备好了")
    assert inside["observation"]["scene"]=="game" and inside["phase"]=="player_ready",inside
    actual=readback(profile,inside);assert actual["branch"]==1 and actual["depth"]==14 and actual["level_class"]=="MiningLevel",actual
    prompt=act(client,"cell.select",cell=inside["observation"]["hero"]["cell"])
    assert any(a.get("label")=="做完了" for a in prompt["actions"]),prompt["actions"]
    cancel=choose(client,"还没有");assert readback(profile,cancel)["branch"]==1
    act(client,"cell.select",cell=cancel["observation"]["hero"]["cell"])
    outside=choose(client,"做完了")
    actual=readback(profile,outside)
    assert outside["observation"]["scene"]=="game" and actual["depth"]==14 and actual["branch"]==0 and actual["level_class"]=="CavesLevel",actual
    assert outside["scope_id"]==initial["scope_id"]
    return {"case_id":"transition.branch_roundtrip","original_enter_cancel_and_confirm":True,
            "original_mining_generation_completed":True,"original_exit_warning_cancel_and_confirm":True,
            "returned_original_caves_floor":True,"same_scope":True}


def story(client,profile,initial,name):
    target={"story-prison":6,"story-caves":11,"story-city":16,"story-halls":21}[name]
    assert initial["observation"]["hero"]["depth"]==target-1
    displayed=act(client,"cell.select",cell=initial["observation"]["hero"]["cell"])
    arrived,seen=original_story(client,displayed)
    assert seen,"The region story must actually require its original Continue input"
    assert arrived["phase"]=="player_ready" and arrived["observation"]["hero"]["depth"]==target
    assert arrived["scope_id"]==initial["scope_id"]
    return {"case_id":"transition."+name.replace("-","_"),"story_actually_displayed":True,
            "original_continue_used":True,"next_stable_floor":target,"same_scope":True}


def run_one(root,classpath,runtime_id,name):
    profile=safe_profile(root,"scenario-"+name);metadata(profile,"scenario:"+name,runtime_id)
    command=launch_command(classpath,fixture=True);command[-1]="scenario:"+name
    client=FixtureClient(command,profile)
    try:
        hello=client.request("protocol.info");assert hello["ok"]
        initial=reach_game(client,"WARRIOR")
        preferences=json.loads((profile/"test_fixture.json").read_text())
        assert preferences["language"]=="CHI_SMPL" and not preferences["fullscreen"]
        evidence=falling(client,profile,initial) if name=="fall" else branch(client,profile,initial) if name=="branch" else story(client,profile,initial,name)
        result={"verified":True,"test_fixture":True,"counts_as_win":False,"runtime_id":runtime_id,
                "profile":str(profile.relative_to(root)),"build_id":hello["result"]["build_id"],
                "language":"CHI_SMPL","fullscreen":False,**evidence}
        stop(client);write_json(profile/"transition-scenario-result.json",result);print(json.dumps(result,ensure_ascii=False),flush=True)
        return result
    finally:
        if client.process.poll() is None:stop(client,uncertain=True)
        client.stderr.close();client.trace.close()


def main():
    # story-halls is an explicit pending fixture: source preparation has not yet passed.
    parser=argparse.ArgumentParser();parser.add_argument("--cases",default="fall,branch,story-prison,story-caves,story-city");args=parser.parse_args()
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text())
    results=[run_one(root,classpath,runtime_id,name) for name in args.cases.split(",")]
    write_json(root/"desktop-control/build/fixtures"/runtime_id/"transition-scenario-results.json",results)


if __name__=="__main__":main()
