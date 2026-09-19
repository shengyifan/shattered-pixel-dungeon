#!/usr/bin/env python3
"""Switch the original GUI language repeatedly in one protocol-6 fixture JVM.

Targets come from public language/code sources, never native-word matching. Only
same-version UI postconditions read the isolated assertion stream; no save reads.
"""
import argparse
from fixture_identity import fixture_context_matches
import copy
import json
import os
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import freeze_runtime
from language_matrix_smoke import MatrixClient, validate
from tutorial_boundary_smoke import start_warrior


LANGUAGES = ("en", "zh", "zh-hant", "ja", "ko", "ru", "fr", "de", "tr")
PRESENTATION_FIELDS = {"text_sources", "text_diagnostics", "translation_status", "presentation"}


def ui(state):
    return state["observation"]["ui"]


def semantic(value):
    if isinstance(value, dict):
        return {key: semantic(child) for key, child in value.items() if key not in PRESENTATION_FIELDS}
    if isinstance(value, list):
        return [semantic(child) for child in value]
    return value


def hero_inventory(state):
    observation = state["observation"]
    return {"scope_id": state["scope_id"], "hero": semantic(observation["hero"]),
            "inventory": semantic(observation["inventory"])}


def language_codes(source):
    if isinstance(source, dict):
        if source.get("kind") == "language" and isinstance(source.get("code"), str):
            yield source["code"]
        for child in source.values():
            yield from language_codes(child)
    elif isinstance(source, list):
        for child in source:
            yield from language_codes(child)


def language_actions(state):
    result = []
    for action in state["actions"]:
        if action["action"] == "ui.activate":
            codes = set(language_codes(action.get("text_sources", {}).get("label")))
            if len(codes) == 1:
                result.append((action, next(iter(codes))))
    return result


def language_action(state, code):
    candidates = [action for action, actual in language_actions(state) if actual == code]
    assert len(candidates) == 1, {"requested_language": code, "public_language_codes": [actual for _, actual in language_actions(state)]}
    return candidates[0]


class SwitchClient(MatrixClient):
    def __init__(self, *args, **kwargs):
        self.source_checks = 0
        super().__init__(*args, **kwargs)

    def request(self, op, args=None, **kwargs):
        response = super().request(op, args, **kwargs)
        assert response.get("protocol_version") == 6, response
        if response.get("ok") and op == "state.get":
            failures = validate(response.get("result"))
            assert not failures, {"op": op, "source_failures": failures[:30]}
            self.source_checks += 1
        return response


def execute(client, state, action, baseline, **args):
    advertised = [entry for entry in state["actions"] if entry["action"] == action
                  and ("control" not in args or entry.get("control") == args["control"])]
    assert advertised, {"unadvertised_action": action, "args": args}
    assert client.scope == state["scope_id"] and client.version == state["state_version"], "Use the latest returned context"
    response = client.act(action, **args)
    assert response.ok and response.status in ("completed", "awaiting_input"), response
    after = client.state(source=True)
    assert after["state_version"] == response.observation["state_version"], "Source inspection changed the settled action state"
    assert hero_inventory(after) == baseline, {"pure_view_changed_hero_or_inventory": semantic(after["observation"])}
    return after, response.initial_response


def open_language_panel(client, baseline):
    state = client.state()
    for _ in range(4):
        assert hero_inventory(state) == baseline
        if language_actions(state):
            return state
        options = [action for action in state["actions"] if action["action"] == "ui.activate"]
        if not ui(state)["modal"]:
            menu = [action for action in options if action.get("label", "").casefold() in ("menu", "game menu")]
            assert menu, {"no_game_menu": options}
            state, _ = execute(client, state, "ui.activate", baseline, control=menu[0]["control"])
            continue
        settings = [action for action in options if action.get("label") == "Settings"]
        if settings:
            assert len(settings) == 1, settings
            state, _ = execute(client, state, "ui.activate", baseline, control=settings[0]["control"])
            continue
        tabs = [action for action in options if not action.get("label")]
        assert len(tabs) == 6, {"expected_six_original_settings_tabs": tabs, "actions": options}
        state, _ = execute(client, state, "ui.activate", baseline, control=tabs[-1]["control"])
    raise AssertionError("The original language tab did not become available")


def assert_gui_checkpoint(profile, state, code):
    assert ui(state)["display"] == {"language": code, "fullscreen": False}, ui(state).get("display")
    deadline = time.monotonic() + 5
    path = profile / "ui-assertions.jsonl"
    while time.monotonic() < deadline:
        if path.exists():
            for line in reversed(path.read_text().splitlines()):
                row = json.loads(line)
                if fixture_context_matches(profile, row, state):
                    assert row["language_code"] == code and row["fullscreen"] is False, row
                    return {"state_version": state["state_version"], "language_code": row["language_code"],
                            "language_enum": row["language"], "fullscreen": row["fullscreen"]}
        time.sleep(0.02)
    raise AssertionError("Missing same-version actual GUI-language checkpoint")


def reject_stale(client, before, old_control, current, code, baseline):
    assert before["state_version"] != current["state_version"], "A native scene reset must invalidate the old context"
    rejected = client.request("action.execute", {"action": "ui.activate", "control": old_control},
                              scope=before["scope_id"], version=before["state_version"])
    assert not rejected.get("ok") and rejected["error"]["code"] == "STALE_STATE", rejected
    history = client.request("request.get", {"target_id": rejected["id"], "get": ["before", "after"]}, scope=before["scope_id"])
    assert history.get("ok") and history["result"]["status"] == "REJECTED", history
    assert history["result"]["before_snapshot"] == history["result"]["after_snapshot"], history
    observed = client.state()
    assert observed["state_version"] == current["state_version"]
    assert ui(observed)["display"] == {"language": code, "fullscreen": False}
    assert hero_inventory(observed) == baseline
    return {"request_id": rejected["id"], "error": "STALE_STATE", "old_state_version": before["state_version"],
            "current_state_version": observed["state_version"], "rejected_before_action": True}


def assert_recorded_response(client, original):
    record = client.request("request.get", {"target_id": original["id"], "get": ["reply"]}, scope=original["scope_id"])
    assert record.get("ok"), record
    assert record["result"]["response"] == original, {"historical_response_rewritten": original["id"]}
    # The default original response intentionally omitted source trees. Fetching
    # history must preserve that exact compact reply, rather than enrich it later.
    return original["id"]


def run(root, classpath, runtime_id, languages):
    profile = root / "desktop-control/build/fixtures" / ("language-switch-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "class:WARRIOR"]
    # This test launches one JVM. Only its initial bootstrap uses the test environment;
    # every later language change goes through the original in-game Settings callback.
    previous = os.environ.get("SPDCTL_TEST_LANGUAGE")
    os.environ["SPDCTL_TEST_LANGUAGE"] = "zh"
    try:
        client = SwitchClient(command, profile, verify_gui=False)
    finally:
        if previous is None:
            os.environ.pop("SPDCTL_TEST_LANGUAGE", None)
        else:
            os.environ["SPDCTL_TEST_LANGUAGE"] = previous
    report = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id,
              "profile": str(profile.relative_to(root)), "initial_gui_language": "zh", "languages": languages,
              "single_game_jvm": True, "automatic_stale_retries": 0, "switches": []}
    try:
        hello = client.request("protocol.info")
        assert hello.get("ok"), hello
        metadata = hello["result"]
        assert hello["protocol_version"] == 6 and metadata["audit_schema_version"] == 9 and metadata["text_language"] == "en", metadata
        report.update(build_id=metadata["build_id"], cli_version=metadata["cli_version"], session_id=metadata["session_id"])
        initial = start_warrior(client)
        assert_gui_checkpoint(profile, initial, "zh")
        baseline = hero_inventory(initial)
        original_responses = []
        for code in languages:
            before = open_language_panel(client, baseline)
            selected = language_action(before, code)
            after, response = execute(client, before, "ui.activate", baseline, control=selected["control"])
            proof = assert_gui_checkpoint(profile, after, code)
            original_responses.append(copy.deepcopy(response))
            stale = reject_stale(client, before, selected["control"], after, code, baseline)
            report["switches"].append({"from": ui(before)["display"]["language"], "to": code,
                                      "request_id": response["id"], "gui": proof, "stale_guard": stale,
                                      "hero_inventory_unchanged": True})
        report["historical_responses_unchanged"] = [assert_recorded_response(client, response) for response in original_responses]
        state = client.state()
        for _ in range(4):
            assert hero_inventory(state) == baseline
            if not ui(state)["modal"]:
                break
            state, _ = execute(client, state, "ui.back", baseline)
        assert not ui(state)["modal"] and state["phase"] == "player_ready", state
        report["final_gui"] = assert_gui_checkpoint(profile, state, languages[-1])
        final_hello = client.request("protocol.info")
        assert final_hello.get("ok"), final_hello
        assert final_hello["protocol_version"] == hello["protocol_version"], "Runtime protocol changed"
        for key in ("session_id", "build_id", "cli_version", "audit_schema_version"):
            assert final_hello["result"][key] == metadata[key], {"runtime_changed": key}
        report["ok"] = True
    except Exception as error:
        report.update(ok=False, error_type=type(error).__name__, error=str(error)[:5000], traceback=traceback.format_exc())
    finally:
        try:
            if client.process.poll() is None:
                client.process.stdin.close()
                client.process.wait(timeout=45)
            assert client.process.returncode == 0, client.process.returncode
        except Exception as error:
            report.update(ok=False, cleanup_error=repr(error))
            if client.process.poll() is None:
                client.process.terminate()
                client.process.wait(timeout=10)
        client.stderr.close()
        client.trace.close()
        report.update(source_checks=client.source_checks, exit_code=client.process.poll())
        (profile / "language-switch-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--languages", default=",".join(LANGUAGES), help="The ordered language switches; initial GUI is zh")
    args = parser.parse_args()
    languages = args.languages.split(",")
    if not languages or any(code not in LANGUAGES for code in languages):
        parser.error("Select registered deep-test codes: " + ",".join(LANGUAGES))
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    result = run(root, classpath, runtime_id, languages)
    target = root / "desktop-control/build/fixtures" / runtime_id / "language-switch-result.json"
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    if not result.get("ok"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
