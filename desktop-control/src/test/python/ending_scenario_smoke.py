#!/usr/bin/env python3
"""Original Poison death, menus and new-game callbacks; no terminal scene injection."""
import argparse
import json
from pathlib import Path
import time

from fixture_smoke import FixtureClient, freeze_runtime, reach_game, checkpoint
from english_protocol_smoke import activate, checked_state, execute, public_events
from legacy_save_smoke import launch_command, metadata, safe_profile, write_json, stop


def ui_assertion(profile,state,window=None):
    deadline=time.monotonic()+5
    while time.monotonic()<deadline:
        rows=[json.loads(line) for line in (profile/"ui-assertions.jsonl").read_text().splitlines()]
        row=next((row for row in reversed(rows) if row["state_version"]==state["state_version"]),None)
        if row is not None:
            assert row["language"]=="CHI_SMPL" and row["fullscreen"] is False,row
            if window:assert window in row["window_classes"],row
            return row
        time.sleep(.02)
    raise AssertionError("No actual UI checkpoint for the returned state version")


def original_death(client,profile):
    initial=reach_game(client,"WARRIOR")
    assert initial["observation"]["hero"]["hp"]==1
    assert all("ankh" not in item["name"].lower() for item in initial["observation"]["inventory"])
    outcome=execute(client,"wait",request_id="death-trigger")
    dead=outcome["result"]
    assert dead["phase"]=="ended" and dead["observation"]["hero"]["hp"]==0,dead
    assert dead["scope_id"]==initial["scope_id"]
    assert dead["run_outcome"]=={"scope_id":initial["scope_id"],"result":"lost"},dead["run_outcome"]
    events=public_events(client,initial["scope_id"])
    ended=[event for event in events if event["kind"]=="run.ended"]
    assert len(ended)==1 and ended[0]["data"]["result"]=="lost",ended
    private=checkpoint(profile,dead["state_version"])["ending"]
    assert private["ankh_count"]==0 and private["ranking_records"]==1 and private["games_won"]==0,private
    return initial,dead,outcome,ended[0]


def death_menu(client,profile):
    state=checked_state(client)
    labels={action.get("label") for action in state["actions"] if action["action"]=="ui.activate"}
    label="Game Menu" if "Game Menu" in labels else "Menu"
    assert label in labels,state["actions"]
    # Death leaves both the ordinary HUD menu and the new death-banner menu usable.
    # Either is an original visible entry to WndGame; select one advertised control,
    # then verify the dead-only contents instead of assuming labels are globally unique.
    control=next(action["control"] for action in state["actions"] if action["action"]=="ui.activate" and action.get("label")==label)
    menu=execute(client,"ui.activate",control=control)["result"]
    ui_assertion(profile,menu,"com.shatteredpixel.shatteredpixeldungeon.windows.WndGame")
    assert {"Settings","Start New Game","Rankings","Main Menu"}<={action.get("label") for action in menu["actions"]}
    return menu


def ranking_case(client,profile,dead):
    death_menu(client,profile)
    ranked=activate(client,"Rankings")["result"]
    assert ranked["observation"]["ui"]["scene"]=="RankingsScene"
    assert ranked["scope_id"].startswith("menu:")
    rows=[action for action in ranked["actions"] if action["action"]=="ui.activate"
          and "succumbed to poison" in action.get("label","").lower()]
    assert len(rows)==1,ranked["actions"]
    detail=execute(client,"ui.activate",control=rows[0]["control"])["result"]
    proof=ui_assertion(profile,detail,"com.shatteredpixel.shatteredpixeldungeon.windows.WndRanking")
    texts=" ".join(str(node.get("text","")) for node in detail["observation"]["ui"]["controls"])
    assert "Maximum Depth" in texts and "Strength" in texts and "Game Duration" in texts,texts
    assert "Unable to load additional information" not in texts,texts
    back=execute(client,"ui.back")["result"]
    assert back["observation"]["ui"]["scene"]=="RankingsScene" and not back["observation"]["ui"]["modal"]
    title=execute(client,"ui.back")["result"]
    assert title["observation"]["ui"]["scene"]=="TitleScene"
    assert title["scope_id"]==ranked["scope_id"]
    return {"case_id":"ending.permanent_death","original_death_menu":True,
            "original_ranking_row_description":"Succumbed to Poison","ranking_detail_actual_window":proof["window_classes"],
            "ranking_stats_loaded":True,"original_detail_back_and_title_return":True}


def restart_case(client,profile,initial,outcome):
    death_menu(client,profile)
    selected=activate(client,"Start New Game")["result"]
    assert selected["observation"]["ui"]["scene"]=="HeroSelectScene"
    # The original dead-run menu already selects this hero class. Selecting the
    # class again opens the separate Hero Info window; Start is the actual next step.
    assert not selected["observation"]["ui"]["modal"]
    assert any(action.get("label")=="Start" for action in selected["actions"])
    new=activate(client,"Start")["result"]
    for _ in range(5):
        if new["observation"]["scene"]=="game":break
        assert new["observation"]["ui"]["scene"]=="InterlevelScene",new
        assert any(action.get("label")=="Continue" for action in new["actions"]),new["actions"]
        new=activate(client,"Continue")["result"]
    assert new["observation"]["scene"]=="game",new
    assert new["scope_id"].startswith("run:") and new["scope_id"]!=initial["scope_id"]
    assert new["observation"]["hero"]["hp"]==new["observation"]["hero"]["max_hp"]>0
    assert new["observation"]["hero"]["level"]==1
    assert not new.get("run_outcome"),new.get("run_outcome")
    assert not any("poison" in buff.get("name","").lower() for buff in new["observation"]["hero"]["buffs"])
    # A query may use the old action ID in the new run; the old run's original action stays terminal.
    fresh_id=client.request("state.get",request_id="death-trigger")
    assert fresh_id["ok"] and fresh_id["result"]["scope_id"]==new["scope_id"],fresh_id
    old=client.request("request.get",{"target_id":"death-trigger"},scope=initial["scope_id"])
    assert old["ok"] and old["result"]["response"]==outcome,old
    old_events=[event for event in public_events(client,initial["scope_id"]) if event["kind"]=="run.ended"]
    assert len(old_events)==1 and old_events[0]["data"]["result"]=="lost"
    assert not [event for event in public_events(client,new["scope_id"]) if event["kind"]=="run.ended"]
    return {"case_id":"ending.restart_after_death","original_dead_menu_start_new_game":True,
            "new_scope_id":new["scope_id"],"original_new_hero_healthy_without_fixture_reinjection":True,
            "old_request_id_allowed_in_new_scope":True,"old_death_response_and_event_unchanged":True,
            "old_outcome_does_not_leak_into_new_run":True}


def run_one(root,classpath,runtime_id,name):
    profile=safe_profile(root,"ending-"+name);metadata(profile,"ending:"+name,runtime_id)
    command=launch_command(classpath,fixture=True);command[-1]="ending:"+name
    client=FixtureClient(command,profile,verify_gui=True)
    try:
        hello=client.request("protocol.info");assert hello["ok"]
        initial,dead,outcome,event=original_death(client,profile)
        details=ranking_case(client,profile,dead) if name=="death-ranking" else restart_case(client,profile,initial,outcome)
        result={"verified":True,"test_fixture":True,"counts_as_win":False,"runtime_id":runtime_id,
                "profile":str(profile.relative_to(root)),"build_id":hello["result"]["build_id"],
                "gui_language":"CHI_SMPL","cli_language":"en","fullscreen":False,
                "old_scope_id":initial["scope_id"],"original_poison_death":True,"run_ended_event":event,
                "gui_postconditions_checked":client.gui_postconditions_checked,**details}
        stop(client)
        write_json(profile/"ending-scenario-result.json",result)
        print(json.dumps(result,ensure_ascii=False),flush=True)
        return result
    except Exception as error:
        write_json(profile/"ending-scenario-failure.json",{"verified":False,"test_fixture":True,"counts_as_win":False,
                   "scenario":name,"runtime_id":runtime_id,"profile":str(profile.relative_to(root)),"error":str(error)[:4000]})
        raise
    finally:
        if client.process.poll() is None:
            client.process.stdin.close()
            try:client.process.wait(timeout=12)
            except Exception:client.process.terminate();client.process.wait(timeout=10)
        client.stderr.close();client.trace.close()


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--cases",default="death-ranking,death-restart");args=parser.parse_args()
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text())
    results=[run_one(root,classpath,runtime_id,name) for name in args.cases.split(",")]
    write_json(root/"desktop-control/build/fixtures"/runtime_id/"ending-scenario-results.json",results)


if __name__=="__main__":main()
