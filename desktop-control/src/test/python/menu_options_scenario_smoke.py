#!/usr/bin/env python3
"""Construct prior profile progress, then exercise original opening-options callbacks."""
import json
import argparse
import re
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, assert_gui_environment, freeze_runtime, reach_game
from low_frequency_smoke import act
from menu_scenario_smoke import choose, return_to_title, ui
from fixture_identity import fixture_identity_matches


def controls(state, kind="ui.activate"):
    return [a for a in state["actions"] if a["action"] == kind]


def nodes(state):
    return ui(state)["controls"]


def prepared_menu(client):
    return_to_title(client)
    choose(client, "Enter the Dungeon")
    state=choose(client,"warrior")
    assert ui(state)["scene"] == "HeroSelectScene"
    state=choose(client,"Game Options")
    assert_gui_environment(client.profile,state)
    return state


def menu_assertion(client,state):
    deadline=time.monotonic()+5
    while time.monotonic()<deadline:
        path=client.profile/"menu-assertions.jsonl"
        if path.exists():
            for line in reversed(path.read_text().splitlines()):
                value=json.loads(line)
                if fixture_identity_matches(client.profile,"revision",value["state_version"],state["state_version"]):return value["menu"]
        time.sleep(0.02)
    raise AssertionError("Missing post-action menu assertion")


def locked(client, results):
    initial=prepared_menu(client)
    for label in ("Custom Seed","Daily Run","Challenges"):
        opened=choose(client,label)
        assert ui(opened)["modal"]
        assert any("must win at least one game" in n.get("text","") for n in nodes(opened)),ui(opened)
        assert not controls(opened,"ui.text")
        returned=act(client,"ui.back")
        assert any(a.get("label")=="Custom Seed" for a in controls(returned)) and returned["scope_id"]==initial["scope_id"]
        results.append({"case":label,"original_locked_explanation":True,"original_back":True})


def unlocked(client, results):
    prepared_menu(client)
    seed=choose(client,"Custom Seed")
    field=controls(seed,"ui.text")[0]["control"]
    original=next(n["value"] for n in nodes(seed) if n.get("id")==field)
    rejected=client.request("action.execute",{"action":"ui.text","control":field,"text":"A"*21})
    assert rejected.get("error",{}).get("code")=="INVALID_ARGUMENT",rejected
    unchanged=client.state()
    assert next(n["value"] for n in nodes(unchanged) if n.get("id")==field)==original
    act(client,"ui.text",control=field,text="ABC-DEF-GHI")
    selected=choose(client,"Set")
    assert menu_assertion(client,selected)["custom_seed"]=="ABC-DEF-GHI"
    reopened=choose(client,"Custom Seed")
    assert any(n.get("value")=="ABC-DEF-GHI" for n in nodes(reopened))
    cleared=choose(client,"Clear")
    assert menu_assertion(client,cleared)["custom_seed"]==""
    results.append({"case":"seed_set_clear","invalid_length_rejected_without_edit":True,"actual_setting_roundtrip":True})

    challenge=choose(client,"Challenges")
    check=next(n for n in nodes(challenge) if "checked" in n and n.get("enabled"))
    label=check.get("text",check.get("label"))
    enabled=act(client,"ui.activate",control=check["id"])
    assert next(n for n in nodes(enabled) if n.get("id")==check["id"])["checked"] is True
    saved=act(client,"ui.back")
    assert menu_assertion(client,saved)["challenges"]!=0
    again=choose(client,"Challenges")
    same=next(n for n in nodes(again) if n.get("text",n.get("label"))==label and "checked" in n)
    assert same["checked"] is True
    act(client,"ui.activate",control=same["id"])
    cleared=act(client,"ui.back")
    assert menu_assertion(client,cleared)["challenges"]==0
    results.append({"case":"challenge_toggle","challenge_label":label,"persisted_and_restored":True})

    randomize=choose(client,"Randomize")
    assert ui(randomize)["modal"]
    assert any(a.get("label")=="Randomize Challenges" for a in controls(randomize))
    cancelled=choose(client,"Cancel")
    assert any(a.get("label")=="Custom Seed" for a in controls(cancelled)) and menu_assertion(client,cancelled)["challenges"]==0
    results.append({"case":"randomize_cancel","native_window_and_cancel":True})

    daily=choose(client,"Daily Run")
    assert ui(daily)["modal"] and any("today's daily" in n.get("text","") for n in nodes(daily))
    cancelled=choose(client,"No")
    assert any(a.get("label")=="Custom Seed" for a in controls(cancelled)) and not menu_assertion(client,cancelled)["daily"]
    results.append({"case":"daily_decline","native_prompt_and_no":True,"game_created":False})

    seed=choose(client,"Custom Seed")
    field=controls(seed,"ui.text")[0]["control"]
    act(client,"ui.text",control=field,text="ABC-DEF-GHI")
    choose(client,"Set")
    choose(client,"Start")
    game=reach_game(client,"WARRIOR")
    applied=menu_assertion(client,game)
    assert applied["game_custom_seed"]=="ABC-DEF-GHI" and not applied["daily"]
    assert game["scope_id"] and game["observation"]["scene"]=="game" and ui(game)["scene"]=="GameScene" and game["observation"]["hero"]["level"]==1
    assert_gui_environment(client.profile,game)
    results.append({"case":"seeded_game_creation","original_start_created_run":True,"original_seed_applied":True,"hero_level":1})


def daily_start(client,results):
    initial=prepared_menu(client)
    choose(client,"Daily Run")
    choose(client,"Yes")
    game=reach_game(client,"WARRIOR")
    applied=menu_assertion(client,game)
    assert applied["daily"] is True and applied["daily_replay"] is False
    assert applied["game_custom_seed"] and game["scope_id"]!=initial["scope_id"]
    assert game["observation"]["hero"]["level"]==1
    assert_gui_environment(client.profile,game)
    results.append({"case":"daily_game_creation","original_yes_created_run":True,"original_daily_flags_applied":True,"hero_level":1})


def random_confirm(client,results):
    prepared_menu(client)
    state=choose(client,"Randomize")
    for label in ("Randomize Hero","Randomize Challenges"):
        check=next(n for n in nodes(state) if n.get("text")==label and "checked" in n)
        if not check["checked"]:state=act(client,"ui.activate",control=check["id"])
    slider=controls(state,"ui.value")[0]
    act(client,"ui.value",control=slider["control"],value=2)
    confirmed=choose(client,"Confirm")
    checks=[n for n in nodes(confirmed) if "checked" in n]
    assert sum(bool(n["checked"]) for n in checks)==2
    assert all(not n["enabled"] for n in checks)
    actual=menu_assertion(client,confirmed)
    assert int(actual["challenges"]).bit_count()==2
    assert actual["selected_class"] in {"WARRIOR","MAGE","ROGUE","HUNTRESS","DUELIST","CLERIC"}
    assert actual["class_randomized"] is True
    returned=act(client,"ui.back")
    assert ui(returned)["scene"]=="HeroSelectScene"
    results.append({"case":"randomize_confirm","exact_challenge_count":2,
                    "original_read_only_result_window":True,"original_random_class_selected":True,
                    "selected_class":actual["selected_class"]})


def daily_control(client):
    state=client.state()
    choices=[a for a in controls(state) if a.get("label")=="Daily Run" or re.fullmatch(r"[0-9]{2}:[0-9]{2}:[0-9]{2}\+?",a.get("label") or "")]
    assert len(choices)==1,choices
    return act(client,"ui.activate",control=choices[0]["control"])


def daily_cycle(client,results):
    daily_start(client,results)
    original_run=client.scope
    act(client,"game.save")
    act(client,"ui.back")
    choose(client,"Main Menu")
    choose(client,"Enter the Dungeon")
    choose(client,"New Game")
    choose(client,"warrior")
    choose(client,"Game Options")
    rejected=daily_control(client)
    assert any("You already have a daily run in progress." in n.get("text","") for n in nodes(rejected))
    results.append({"case":"existing_daily_rejected","original_explanation":True,"new_game_created":False})
    act(client,"ui.back")
    return_to_title(client)
    start=choose(client,"Enter the Dungeon")
    saved=[a for a in controls(start) if "warrior" in a.get("label","").lower()]
    assert len(saved)==1,saved
    act(client,"ui.activate",control=saved[0]["control"])
    choose(client,"Erase")
    cancelled=choose(client,"No, I want to continue")
    assert any(a.get("label")=="Continue" for a in controls(cancelled))
    results.append({"case":"save_erase_cancel","original_no_preserved_save_details":True})
    choose(client,"Erase")
    choose(client,"Yes, delete this save")
    after=client.state()
    if ui(after)["scene"]=="StartScene":choose(client,"New Game")
    else:
        return_to_title(client);choose(client,"Enter the Dungeon")
    choose(client,"warrior")
    choose(client,"Game Options")
    repeat=daily_control(client)
    assert any("You have already played today's daily." in n.get("text","") for n in nodes(repeat))
    choose(client,"Yes")
    game=reach_game(client,"WARRIOR")
    actual=menu_assertion(client,game)
    assert actual["daily"] is True and actual["daily_replay"] is True
    assert game["scope_id"]!=original_run
    results.append({"case":"daily_replay_after_original_erase","original_erase_and_repeat_prompt":True,
                    "fresh_scope":True,"original_daily_replay_flag":True,"system_clock_changed":False})


def daily_future(client,results):
    initial=prepared_menu(client)
    unavailable=daily_control(client)
    assert any("It seems you've started a daily that's in the future!" in n.get("text","") for n in nodes(unavailable))
    returned=act(client,"ui.back")
    assert returned["scope_id"]==initial["scope_id"]
    results.append({"case":"daily_future_warning","initial_last_daily_in_future":True,
                    "original_warning_and_back":True,"system_clock_changed":False})


def seed_duplicate(client,results):
    prepared_menu(client)
    choose(client,"Start")
    first=reach_game(client,"WARRIOR")
    assert not menu_assertion(client,first)["game_custom_seed"]
    act(client,"game.save")
    act(client,"ui.back");choose(client,"Main Menu")
    start=choose(client,"Enter the Dungeon")
    saved=[a for a in controls(start) if "warrior" in a.get("label","").lower()]
    assert len(saved)==1
    details=act(client,"ui.activate",control=saved[0]["control"])
    codes=[n["text"] for n in nodes(details) if re.fullmatch(r"[A-Z]{3}-[A-Z]{3}-[A-Z]{3}",n.get("text", ""))]
    assert len(codes)==1,ui(details)
    observed_seed=codes[0]
    act(client,"ui.back")
    choose(client,"New Game");choose(client,"warrior");choose(client,"Game Options")
    seed=choose(client,"Custom Seed")
    field=controls(seed,"ui.text")[0]["control"]
    act(client,"ui.text",control=field,text=observed_seed)
    rejected=choose(client,"Set")
    assert any("You already have a regular game in progress with that seed." in n.get("text","") for n in nodes(rejected))
    assert menu_assertion(client,rejected)["custom_seed"]==""
    act(client,"ui.back")
    results.append({"case":"duplicate_regular_seed_rejected","seed_source":"original_public_save_details", "seed":observed_seed,
                    "original_duplicate_warning":True,"actual_setting_cleared":True})


def run_case(root,classpath,runtime_id,name):
    profile=root/"desktop-control/build/fixtures"/("menu-options-"+name+"-"+uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command=["java","-XstartOnFirstThread","--enable-native-access=ALL-UNNAMED","--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED","-cp",classpath,
             "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher","--fixture","menu:"+name]
    client=FixtureClient(command,profile)
    result={"test_fixture":True,"counts_as_win":False,"fixture":"menu:"+name,"runtime_id":runtime_id,
            "profile":str(profile.relative_to(root)),"cli_language":"en","gui_language":"zh","cases":[]}
    try:
        hello=client.request("protocol.info");assert hello["ok"],hello
        result["build_id"]=hello["result"]["build_id"]
        {"locked":locked,"unlocked":unlocked,"daily":daily_start,"random-confirm":random_confirm,"daily-cycle":daily_cycle,
         "daily-future":daily_future,"seed-duplicate":seed_duplicate}[name](client,result["cases"])
        result["ok"]=True
    except Exception as error:
        result.update(ok=False,error=repr(error),traceback=traceback.format_exc())
    finally:
        try:
            if result.get("ok"):
                current=client.state()
                if ui(current)["scene"]=="GameScene":
                    assert current["phase"]=="player_ready"
                    act(client,"game.save")
                else:return_to_title(client)
                client.finish();assert client.process.returncode==0
            elif client.process.poll() is None:
                client.process.stdin.close();client.process.wait(timeout=35)
        except Exception as error:result.update(ok=False,cleanup_error=repr(error))
        finally:
            if client.process.poll() is None:client.process.terminate();client.process.wait(timeout=10)
            client.stderr.close();client.trace.close()
        (profile/"menu-options-result.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps(result,ensure_ascii=False),flush=True)
    return result


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--cases",default="locked,unlocked,daily,random-confirm,daily-cycle,daily-future,seed-duplicate")
    names=parser.parse_args().cases.split(",")
    assert all(name in {"locked","unlocked","daily","random-confirm","daily-cycle","daily-future","seed-duplicate"} for name in names)
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports=[run_case(root,classpath,runtime_id,name) for name in names]
    (root/"desktop-control/build/menu-options-validation.json").write_text(json.dumps(reports,ensure_ascii=False,indent=2)+"\n")
    if not all(r.get("ok") for r in reports):raise SystemExit(1)


if __name__=="__main__":main()
