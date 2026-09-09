#!/usr/bin/env python3
"""Observe real window focus in a test fixture while operating only through NDJSON."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
from fixture_smoke import FixtureClient, checkpoint, freeze_runtime, reach_game, visible_target
from legacy_save_smoke import launch_command, metadata, safe_profile, stop, write_json
from low_frequency_smoke import act, click, quantity


def focus_history(profile):
    path=profile/"fixture-focus.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--gui-launcher",type=Path,required=True);args=parser.parse_args()
    root=Path(__file__).resolve().parents[4]
    classpath,runtime_id=freeze_runtime(root,(root/"desktop-control/build/test-runtime-classpath.txt").read_text())
    profile=safe_profile(root,"background-control");metadata(profile,"ui:travel",runtime_id)
    command=launch_command(classpath,fixture=True);command[-1]="ui:travel"
    client=FixtureClient(command,profile)
    gui_profile=safe_profile(root,"ordinary-gui-focus");metadata(gui_profile,"ordinary-gui-menu",runtime_id)
    from test_ui import configure_test_ui
    configure_test_ui(gui_profile)
    gui=None
    with (gui_profile/"ordinary-gui.log").open("wb") as gui_log:
        try:
            assert client.request("protocol.info")["ok"]
            reach_game(client,"WARRIOR")
            # Starting another normal app window changes focus naturally; no focusWindow/input simulation is used.
            gui=subprocess.Popen([str(args.gui_launcher.resolve()),"--data-dir",str(gui_profile)],stdout=gui_log,stderr=gui_log)
            deadline=time.monotonic()+15
            while time.monotonic()<deadline:
                history=focus_history(profile)
                if history and history[-1]["focused"] is False:break
                assert gui.poll() is None,gui.poll()
                time.sleep(0.05)
            else:raise AssertionError("The ordinary GUI did not leave the controlled window unfocused")
            first_focus_count=len(history)
            state=client.state()
            item=next(i for i in state["observation"]["inventory"] if any(name in i["name"].lower() for name in ["throwing stone","投石"]))
            item_name=item["name"];before=quantity(state,item_name)
            state=act(client,"inventory.open",locator=item["locator"])
            assert checkpoint(profile,state["state_version"])["window_focused"] is False
            state=click(client,lambda label:label.lower() in {"throw","投掷","扔出"})
            target=visible_target(state,floor=True)
            state=act(client,"cell.select",cell=target,mode="act")
            assert quantity(state,item_name)==before-1
            assert checkpoint(profile,state["state_version"])["window_focused"] is False
            history=focus_history(profile)
            assert all(row["focused"] is False for row in history[first_focus_count-1:]),history
            assert gui.poll() is None
            gui_log.flush()
            gui_output=(gui_profile/"ordinary-gui.log").read_text(errors="replace")
            assert "added manager for application" in gui_output,gui_output
            assert not (gui_profile/"audit").exists(),"Ordinary GUI must not load the control/audit module"
            assert (gui_profile/".instance.lock").stat().st_size>0
            competing=subprocess.run([str(args.gui_launcher.resolve().with_name("spdctl")),"run","--machine","--data-dir",str(gui_profile)],
                                     input=b"",capture_output=True,timeout=20)
            assert competing.returncode!=0 and competing.stdout==b"",competing
            assert b"STARTUP_FAILED" in competing.stderr,competing.stderr
            assert not (gui_profile/"audit/public.sqlite3").exists(),"Rejected CLI must not open the GUI profile database"
            result={"result":"passed","test_fixture":True,"counts_as_win":False,"runtime_id":runtime_id,
                    "control_profile":str(profile.relative_to(root)),"ordinary_gui_profile":str(gui_profile.relative_to(root)),
                    "ordinary_gui_launcher":str(args.gui_launcher.resolve()),"window_focused_during_inventory_and_throw":False,
                    "throwing_stones_before":before,"throwing_stones_after":before-1,
                    "ordinary_gui_excluded_competing_cli":True,
                    "input_method":"public NDJSON only; no screenshots, OS keyboard, mouse, or focus injection",
                    "ordinary_gui_exit":"SIGTERM of the owned menu-only test process after assertions"}
            write_json(profile/"background-result.json",result)
            print(json.dumps(result,ensure_ascii=False),flush=True)
        finally:
            try:
                stop(client)
            finally:
                # A failed CLI cleanup must not strand the independent ordinary GUI.
                if gui is not None and gui.poll() is None:
                    gui.terminate();gui.wait(timeout=15)


if __name__=="__main__":main()
