#!/usr/bin/env python3
"""Three original floating labels, observed only at each real CLI pan's own final response."""
import json
from pathlib import Path
import traceback
import uuid

from fixture_smoke import FixtureClient, act, checkpoint, freeze_runtime, reach_game

TARGETS = {"314159", "warrior", "dodged"}


def live_sources(profile, state):
    rows = checkpoint(profile, state["state_version"])["floating_visibility"]["sources"]
    assert len(rows) == 3 and {row["stored_text"] for row in rows} == {"314159", "战士", "闪避"}, rows
    assert all(row["alive"] and row["exists"] and row["active"] and row["time_left"] > 0 for row in rows), rows
    return rows


def outside(state):
    visible_ui = state["observation"]["ui"]
    nodes = visible_ui["controls"]
    assert not any(node.get("presentation") == "floating_text" for node in nodes), nodes
    def strings(value):
        if isinstance(value, str):
            yield value
        elif isinstance(value, dict):
            for child in value.values(): yield from strings(child)
        elif isinstance(value, list):
            for child in value: yield from strings(child)
    # Search the whole public UI, including any future cached/raw-text fields.
    assert not any(value.casefold() in TARGETS or value in {"战士", "闪避"} for value in strings(visible_ui)), nodes
    return {node["id"] for node in nodes}


def main():
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    print(json.dumps({"started_runtime": runtime_id, "fixture": "floating:visibility"}), flush=True)
    profile = root / "desktop-control/build/fixtures" / ("floating-visibility-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "floating:visibility"]
    client = FixtureClient(command, profile, verify_gui=True)
    report = {"test_fixture": True, "counts_as_win": False, "fixture": "floating:visibility",
              "profile": str(profile.relative_to(root)), "runtime_id": runtime_id}
    try:
        hello = client.request("protocol.info"); assert hello["ok"], hello
        report.update(build_id=hello["result"]["build_id"], cli_version=hello["result"]["cli_version"])
        initial = reach_game(client, "WARRIOR")
        first = act(client, "view.pan", x=5000, y=0)
        first_ids = outside(first)
        first_sources = live_sources(profile, first)
        inside = act(client, "view.pan", x=-5000, y=0)
        nodes = inside["observation"]["ui"]["controls"]
        shown = [node for node in nodes if node.get("presentation") == "floating_text"]
        assert {node.get("text", "").casefold() for node in shown} == TARGETS and len(shown) == 3, shown
        assert all(node["role"] == "text" and not node.get("clipped") for node in shown), shown
        inside_sources = live_sources(profile, inside)
        final = act(client, "view.pan", x=5000, y=0)
        final_ids = outside(final)
        final_sources = live_sources(profile, final)
        # Even a blank placeholder would retain the same original object's opaque ID.
        shown_ids = {node["id"] for node in shown}
        assert shown_ids.isdisjoint(first_ids) and shown_ids.isdisjoint(final_ids)
        assert all(state["scope_id"] == initial["scope_id"] for state in (first, inside, final))
        report.update(ok=True, original_pan_sequence=[[5000, 0], [-5000, 0], [5000, 0]],
                      final_response_versions=[state["state_version"] for state in (first, inside, final)],
                      full_visible_nodes=shown, no_empty_hidden_source_nodes=True,
                      hidden_models_remain_alive_and_retain_original_text=True,
                      each_pan_used_its_own_terminal_response=True, no_followup_query_to_wait_for_presentation=True,
                      original_source_lifetimes=[[source["time_left"] for source in sources]
                                                 for sources in (first_sources, inside_sources, final_sources)])
        client.finish(); assert client.process.returncode == 0
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        if client.process.poll() is None:
            try:
                client.process.stdin.close(); client.process.wait(timeout=25)
            except Exception as error:
                report.update(ok=False, cleanup_error=repr(error))
                client.process.terminate(); client.process.wait(timeout=10)
        client.stderr.close(); client.trace.close()
        report.update(gui_postconditions_checked=client.gui_postconditions_checked, exit_code=client.process.poll())
        (profile / "floating-visibility-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        (root / "desktop-control/build/fixtures" / runtime_id / "floating-visibility-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    raise SystemExit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
