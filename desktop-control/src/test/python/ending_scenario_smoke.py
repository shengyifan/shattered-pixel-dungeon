#!/usr/bin/env python3
"""Original death/restart and Amulet/Ascension callbacks; no terminal scene injection."""
import argparse
import json
import os
from pathlib import Path
import time

from fixture_smoke import FixtureClient, freeze_runtime, reach_game, checkpoint
from english_protocol_smoke import activate, checked_state, execute, public_events
from fixture_support import launch_command, metadata, safe_profile, write_json, stop
from fixture_identity import fixture_context_matches, fixture_identity_matches


def ui_assertion(profile,state,window=None):
    deadline=time.monotonic()+5
    while time.monotonic()<deadline:
        rows=[json.loads(line) for line in (profile/"ui-assertions.jsonl").read_text().splitlines()]
        row=next((row for row in reversed(rows) if fixture_identity_matches(profile,"revision",row['state_version'],state['rev'])),None)
        if row is not None:
            display=state["ui"]["display"]
            assert fixture_context_matches(profile,row,state),row
            assert row["language_code"]==display["language"]==os.environ.get("SPDCTL_TEST_LANGUAGE","zh"),row
            assert row["fullscreen"] is False and display["fullscreen"] is False,row
            if window:assert window in row["window_classes"],row
            return row
        time.sleep(.02)
    raise AssertionError("No actual UI checkpoint for the returned state version")


def original_death(client,profile):
    initial=reach_game(client,"WARRIOR")
    assert initial["hero"]["hp"]==1
    assert all("ankh" not in item["name"].lower() for item in initial['inv'])
    outcome=execute(client,"wait",request_id="death-trigger")
    dead=outcome.observation
    assert dead["phase"]=="ended" and dead["hero"]["hp"]==0,dead
    assert dead['s']==initial['s']
    assert dead["run_outcome"]=={'s':initial['s'],"result":"lost"},dead["run_outcome"]
    events=public_events(client,initial['s'])
    ended=[event for event in events if event["kind"]=="run.ended"]
    assert len(ended)==1 and ended[0]["data"]["result"]=="lost",ended
    private=checkpoint(profile,dead['rev'])["ending"]
    assert private["ankh_count"]==0 and private["ranking_records"]==1 and private["games_won"]==0,private
    # This scenario specifically verifies historical ending persistence.
    recorded = client.request('req', {'rid': outcome.request_id, "get": ["reply"]}, scope=outcome.scope_id)
    assert ("err" not in recorded), recorded
    return initial,dead,recorded["data"]["reply"],ended[0]


def death_menu(client,profile):
    state=checked_state(client)
    labels={action.get("label") for action in state['acts'] if action['op']=='click'}
    label="Game Menu" if "Game Menu" in labels else "Menu"
    assert label in labels,state['acts']
    # Death leaves both the ordinary HUD menu and the new death-banner menu usable.
    # Either is an original visible entry to WndGame; select one advertised control,
    # then verify the dead-only contents instead of assuming labels are globally unique.
    ctl=next(action['ctl'] for action in state['acts'] if action['op']=='click' and action.get("label")==label)
    menu=execute(client,'click',ctl=ctl).observation
    ui_assertion(profile,menu,"com.shatteredpixel.shatteredpixeldungeon.windows.WndGame")
    assert {"Settings","Start New Game","Rankings","Main Menu"}<={action.get("label") for action in menu['acts']}
    return menu


def ranking_case(client,profile,dead):
    death_menu(client,profile)
    ranked=activate(client,"Rankings").observation
    assert ranked["ui"]["scene"]=="RankingsScene"
    assert ranked['s'] and ranked['s']!=dead['s'] and "hero" not in ranked
    rows=[action for action in ranked['acts'] if action['op']=='click'
          and "succumbed to poison" in action.get("label","").lower()]
    assert len(rows)==1,ranked['acts']
    detail=execute(client,'click',ctl=rows[0]['ctl']).observation
    proof=ui_assertion(profile,detail,"com.shatteredpixel.shatteredpixeldungeon.windows.WndRanking")
    texts=" ".join(str(node.get("text","")) for node in detail["ui"]['nodes'])
    assert "Maximum Depth" in texts and "Strength" in texts and "Game Duration" in texts,texts
    assert "Unable to load additional information" not in texts,texts
    back=execute(client,'back').observation
    assert back["ui"]["scene"]=="RankingsScene" and not back["ui"]["modal"]
    title=execute(client,'back').observation
    assert title["ui"]["scene"]=="TitleScene"
    assert title['s']==ranked['s']
    return {"case_id":"ending.permanent_death","original_death_menu":True,
            "original_ranking_row_description":"Succumbed to Poison","ranking_detail_actual_window":proof["window_classes"],
            "ranking_stats_loaded":True,"original_detail_back_and_title_return":True}


def restart_case(client,profile,initial,outcome):
    death_menu(client,profile)
    selected=activate(client,"Start New Game").observation
    assert selected["ui"]["scene"]=="HeroSelectScene"
    # The original dead-run menu already selects this hero class. Selecting the
    # class again opens the separate Hero Info window; Start is the actual next step.
    assert not selected["ui"]["modal"]
    assert any(action.get("label")=="Start" for action in selected['acts'])
    new=activate(client,"Start").observation
    for _ in range(5):
        if new["scene"]=="game":break
        assert new["ui"]["scene"]=="InterlevelScene",new
        assert any(action.get("label")=="Continue" for action in new['acts']),new['acts']
        new=activate(client,"Continue").observation
    assert new["scene"]=="game",new
    assert new['s'] and new['s']!=initial['s'] and new["ui"]["scene"]=="GameScene"
    assert new["hero"]["hp"]==new["hero"]['ht']>0
    assert new["hero"]["level"]==1
    assert not new.get("run_outcome"),new.get("run_outcome")
    assert not any("poison" in buff.get("name","").lower() for buff in new["hero"]["buffs"])
    # A query may use the old action ID in the new run; the old run's original action stays terminal.
    fresh_id=client.request('state',request_id="death-trigger")
    assert ("err" not in fresh_id) and fresh_id['s']==new['s'],fresh_id
    old=client.request('req',{'rid':"death-trigger", "get": ["reply"]},scope=initial['s'])
    assert ("err" not in old) and old["data"]["reply"]==outcome,old
    old_events=[event for event in public_events(client,initial['s']) if event["kind"]=="run.ended"]
    assert len(old_events)==1 and old_events[0]["data"]["result"]=="lost"
    assert not [event for event in public_events(client,new['s']) if event["kind"]=="run.ended"]
    return {"case_id":"ending.restart_after_death","original_dead_menu_start_new_game":True,
            "new_scope_id":new['s'],"original_new_hero_healthy_without_fixture_reinjection":True,
            "old_request_id_allowed_in_new_scope":True,"old_death_response_and_event_unchanged":True,
            "old_outcome_does_not_leak_into_new_run":True}


def entrance(client,expected_depth):
    state=checked_state(client)
    hero=state["hero"]
    assert hero["depth"]==expected_depth,hero
    width=state["map"]['w']
    choices=[cell for cell in state["map"]["cells"] if cell["name"]=="Depth entrance"]
    assert choices,state["map"]
    target=min(choices,key=lambda cell:max(abs(cell["cell"]%width-hero["cell"]%width),abs(cell["cell"]//width-hero["cell"]//width)))["cell"]
    result=execute(client,'cell',cell=target,mode="act").observation
    # If the native input only walked onto the stairs, the next original click activates them.
    if result.get("scene")=="game" and result["hero"]["depth"]==expected_depth and result["phase"]=="player_ready":
        assert result["hero"]["cell"]==target,result
        result=execute(client,'cell',cell=target,mode="act").observation
    return result


def amulet_surface(client,profile,initial):
    scope=initial['s']
    assert initial["hero"]["depth"]==2
    assert not any(item["name"].lower()=="amulet of yendor" for item in initial['inv'])
    heap=next(entity for entity in initial['entities']
              if entity.get("item",{}).get("name","").lower()=="amulet of yendor")
    acquired=execute(client,'cell',cell=heap["cell"],mode="act").observation
    assert acquired["ui"]["scene"]=="AmuletScene",acquired
    ui_assertion(profile,acquired)
    text=" ".join(str(node.get("text","")) for node in acquired["ui"]['nodes'])
    assert "You finally hold it in your hands, the Amulet of Yendor!" in text,text
    assert {"Let's call it a day","I'm not done yet"}<={action.get("label") for action in acquired['acts']}
    assert not [event for event in public_events(client,scope) if event["kind"]=="run.ended"]
    stayed=activate(client,"I'm not done yet").observation
    assert stayed['s']==scope and stayed["scene"]=="game" and stayed["hero"]["depth"]==2
    assert any(item["name"].lower()=="amulet of yendor" for item in stayed['inv'])
    first=entrance(client,2)
    assert first['s']==scope and first["scene"]=="game" and first["hero"]["depth"]==1,first
    assert not checkpoint(profile,first['rev'])["ending"]["ascended"]
    surface=entrance(client,1)
    assert surface["ui"]["scene"]=="SurfaceScene",surface
    surface_proof=ui_assertion(profile,surface)
    assert surface["run_outcome"]=={'s':scope,"result":"won"}
    private=checkpoint(profile,surface['rev'])["ending"]
    assert private["ascended"] and private["amulet_obtained"] and private["games_won"]==1,private
    ended=[event for event in public_events(client,scope) if event["kind"]=="run.ended"]
    assert len(ended)==1 and ended[0]["data"]["result"]=="won",ended
    ranked=activate(client,"Game Over").observation
    assert ranked["ui"]["scene"]=="RankingsScene"
    victory=ui_assertion(profile,ranked,"com.shatteredpixel.shatteredpixeldungeon.windows.WndVictoryCongrats")
    blocked=execute(client,'back').observation
    ui_assertion(profile,blocked,"com.shatteredpixel.shatteredpixeldungeon.windows.WndVictoryCongrats")
    closed=activate(client,"Close").observation
    assert closed["ui"]["scene"]=="RankingsScene" and not closed["ui"]["modal"]
    return {"case_id":"ending.amulet_pickup_surface","source_scope_id":scope,
            "original_ground_pickup_and_acquisition_story":True,"original_stay_keeps_amulet_and_scope":True,
            "original_two_to_one_transition":True,"actual_surface_scene":surface_proof["scene"],
            "original_surface_win_event":ended[0],"victory_window":victory["window_classes"],
            "mandatory_victory_back_does_not_skip":True,"original_victory_close":True,
            "not_full_26_to_1_return":True,"ascension_challenge_start_not_claimed":True}


def ascension_start(client,profile,initial):
    scope=initial['s']
    before=checkpoint(profile,initial['rev'])["ending"]
    assert initial["hero"]["depth"]==25 and before["level_class"]=="HallsBossLevel"
    assert not before["ascension_active"] and not before["amulet_obtained"]
    heap=next(entity for entity in initial['entities']
              if entity.get("item",{}).get("name","").lower()=="amulet of yendor")
    acquired=execute(client,'cell',cell=heap["cell"],mode="act").observation
    assert acquired["ui"]["scene"]=="AmuletScene"
    stayed=activate(client,"I'm not done yet").observation
    assert stayed['s']==scope and stayed["hero"]["depth"]==25
    prompt=entrance(client,25)
    assert prompt["phase"]=="awaiting_input" and {"Continue!","Stop for Now"}<={action.get("label") for action in prompt['acts']}
    shown=" ".join(str(node.get("text","")) for node in prompt["ui"]['nodes'])
    assert "You begin to feel Yog-Dzewa's great and terrible power radiating from the amulet." in shown
    prompt_proof=ui_assertion(profile,prompt)
    assert any(name.startswith("com.shatteredpixel.shatteredpixeldungeon.levels.HallsBossLevel$") for name in prompt_proof["window_classes"])
    cancelled=activate(client,"Stop for Now").observation
    assert cancelled["hero"]["depth"]==25 and not checkpoint(profile,cancelled['rev'])["ending"]["ascension_active"]
    entrance(client,25)
    ascended=activate(client,"Continue!").observation
    assert ascended['s']==scope and ascended["phase"]=="player_ready" and ascended["hero"]["depth"]==24,ascended
    after=checkpoint(profile,ascended['rev'])["ending"]
    assert after["level_class"]=="HallsLevel" and after["highest_ascent"]==24 and after["ascension_active"]
    assert any(buff["name"].lower()=="amulet's curse" for buff in ascended["hero"]["buffs"])
    events=public_events(client,scope)
    assert any("The amulet begins calling out to distant enemies." in entry["text"]
               for event in events if event["kind"]=="game.log" for entry in event["data"]["entries"]),events
    assert not [event for event in events if event["kind"]=="run.ended"]
    return {"case_id":"ending.ascension_start","source_scope_id":scope,
            "actual_source_level":"HallsBossLevel","original_ground_pickup_and_stay":True,
            "original_confirmation_window":prompt_proof["window_classes"],"cancel_does_not_attach_challenge":True,
            "original_confirm_attaches_challenge":True,"stable_destination_depth":24,
            "real_buff_and_drawn_feedback":True,"no_ending_claimed":True,"not_full_26_to_1_return":True}


def run_one(root,classpath,runtime_id,name):
    profile=safe_profile(root,"ending-"+name);metadata(profile,"ending:"+name,runtime_id)
    command=launch_command(classpath,fixture=True);command[-1]="ending:"+name
    client=FixtureClient(command,profile,verify_gui=True)
    try:
        hello=client.request('info');assert ("err" not in hello)
        if name in {"amulet-surface","ascension-start"}:
            initial=reach_game(client,"WARRIOR")
            details=amulet_surface(client,profile,initial) if name=="amulet-surface" else ascension_start(client,profile,initial)
        else:
            initial,dead,outcome,event=original_death(client,profile)
            details=ranking_case(client,profile,dead) if name=="death-ranking" else restart_case(client,profile,initial,outcome)
            details.update(original_poison_death=True,run_ended_event=event)
        result={"verified":True,"test_fixture":True,"counts_as_win":False,"runtime_id":runtime_id,
                "profile":str(profile.relative_to(root)),"build_id":hello["data"]["build_id"],
                "gui_language":os.environ.get("SPDCTL_TEST_LANGUAGE","zh"),"cli_language":"en","fullscreen":False,
                "old_scope_id":initial['s'],
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
