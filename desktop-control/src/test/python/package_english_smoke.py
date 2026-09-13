#!/usr/bin/env python3
"""Actual ARM64 .app only: English protocol, Chinese windowed GUI, original game callbacks.

No source/test Java classpath, javaagent, screen capture, OS key/mouse input or formal
profile is used. Python helpers only send public NDJSON to Contents/MacOS/spdctl.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import shutil
import sqlite3
import subprocess
import time
import uuid
import zipfile

from english_protocol_smoke import activate, assert_english, checked_state, english_inventory, execute, public_events, stop
from machine_smoke import Client, reach_game, finish_tutorial
from language_matrix_smoke import validate as validate_sources
from package_smoke import emergency_snapshot, receive
from test_ui import configure_test_ui


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def environment():
    env = dict(os.environ)
    for key in ("JAVA_HOME", "JDK_HOME", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    env["PATH"] = "/usr/bin:/bin"
    return env


def new_profile(output, name):
    profile = output / name
    profile.mkdir()
    configure_test_ui(profile)
    write_json(profile / "test_fixture.json", {"test_fixture": True, "counts_as_win": False,
               "fixture": "package-english", "setup": "Only Chinese/windowed preferences; no game state or save injection"})
    return profile


def display(state):
    value = state["observation"]["ui"]["display"]
    assert value["language"] == "zh", value
    assert value["fullscreen"] is False, value
    return value


def verify_bundle(bundle, expected_cli):
    binaries = [bundle / "Contents/MacOS" / name for name in ("spdctl", "spdctl-jvm", "Shattered Pixel Dungeon")]
    binaries.append(bundle / "Contents/runtime/Contents/Home/lib/server/libjvm.dylib")
    architecture = {}
    for executable in binaries:
        description = subprocess.check_output(["/usr/bin/file", str(executable)], text=True).strip()
        assert "arm64" in description, description
        architecture[str(executable.relative_to(bundle))] = description.split(": ", 1)[-1]
    subprocess.run(["/usr/bin/codesign", "--verify", "--deep", "--strict", str(bundle)], check=True)
    plist = plistlib.loads((bundle / "Contents/Info.plist").read_bytes())
    assert plist["CFBundleExecutable"] == "Shattered Pixel Dungeon", plist
    catalogs = []
    forbidden = ("DisplayedTextEnglish", "PublicDialogSignatures", "FixtureLauncher", "UiSceneAssertions", "EnglishCorpusProbe", "TransitionScenarioFixtures",
                 "PerformanceLauncher", "EngineBoundaryAgent", "GameLogFixtureAgent", "ResurrectionResumeAgent")
    for jar in (bundle / "Contents/app").rglob("*.jar"):
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                basename = name.rsplit("/", 1)[-1]
                assert not any(basename == cls + ".class" or basename.startswith(cls + "$") for cls in forbidden), name
            if "control-build.json" in archive.namelist():
                catalogs.append(json.loads(archive.read("control-build.json")))
    assert len(catalogs) == 1 and catalogs[0]["cli_version"] == expected_cli, catalogs
    return {"architecture": architecture, "build_id": catalogs[0]["build_id"],
            "cli_version": expected_cli, "code_signature_verified": True, "test_classes_absent": True}


def loaded_jvm(process, bundle):
    expected = bundle / "Contents/runtime/Contents/Home/lib/server/libjvm.dylib"
    # The native relay owns the pipes; its direct child owns the packaged JVM.
    children = subprocess.check_output(["/usr/bin/pgrep", "-P", str(process.pid)], text=True).split()
    loaded = []
    for child in children:
        result = subprocess.run(["/usr/sbin/lsof", "-n", "-P", "-p", child, "-Fn"], capture_output=True, text=True)
        candidates = [line[1:] for line in result.stdout.splitlines()
                      if line.startswith("n") and line.endswith("libjvm.dylib")]
        if candidates:
            loaded.append((int(child), result.stdout, candidates))
    assert len(loaded) == 1, {"relay_pid": process.pid, "children": children, "loaded_jvms": loaded}
    child_pid, output, candidates = loaded[0]
    assert candidates and all(os.path.samefile(path, expected) for path in candidates), candidates
    sqlite = [line[1:] for line in output.splitlines() if line.startswith("n") and "libsqlitejdbc" in line]
    assert sqlite, "The package must have loaded its native SQLite JDBC library"
    return {"relay_pid": process.pid, "pid": child_pid, "loaded_libjvm": candidates[0], "loaded_sqlite_jni": sqlite}


def audit_health(profile):
    facts = []
    for side in ("public", "internal"):
        with sqlite3.connect((profile / "audit" / (side + ".sqlite3")).as_uri() + "?mode=ro", uri=True) as db:
            assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            version = db.execute("SELECT sqlite_version()").fetchone()[0]
            # This is Python's reader version, not proof of the package's loaded JNI version.
            facts.append({"side": side, "integrity": "ok", "inspection_sqlite_version": version})
            if side == "public":
                tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                assert "exceptions" not in tables and "logs" not in tables
    return facts


def raw_pipe_case(cli, bundle, output, env, expected_build, expected_cli):
    profile = new_profile(output, "原始管道 中文目录 with spaces")
    command = [str(cli), "run", "--machine", "--data-dir", str(profile),
               "--no-terminal", "--trace-dir", str(output / "transport")]
    frames = []
    stderr = (profile / "native-stderr.log").open("ab")
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, env=env)
    try:
        def exchange(frame):
            frames.append(frame)
            process.stdin.write(frame)
            process.stdin.flush()
            result = receive(process)
            assert_english(result)
            return result
        hello = exchange(b'{"protocol_version":2,"id":"package-info","op":"protocol.info"}\r\n')
        assert hello["ok"] and hello["result"]["build_id"] == expected_build
        assert hello["result"]["cli_version"] == expected_cli
        scope = hello["result"]["scope_id"]
        invalid = exchange(b'{"protocol_version":2,"id":"package-invalid-encoding","op":"state.get","x":"\xff"}\n')
        assert invalid["error"]["code"] == "INVALID_ENCODING", invalid
        query = json.dumps({"protocol_version": 2, "id": "package-state", "scope_id": scope, "op": "state.get"}).encode() + b"\n"
        observed = exchange(query)
        assert observed["ok"], observed
        mode = display(observed["result"])
        jvm = loaded_jvm(process, bundle)
        repeated = exchange(query)
        assert repeated["error"]["code"] == "DUPLICATE_REQUEST_ID", repeated
        emergency_before = emergency_snapshot(profile)
        conflict = subprocess.run(command, input=b"", capture_output=True, env=env, timeout=20)
        assert conflict.returncode != 0 and conflict.stdout == b"" and b"STARTUP_FAILED" in conflict.stderr
        assert emergency_snapshot(profile) == emergency_before, "A rejected competing process changed emergency diagnostics"
        process.stdin.close()
        process.wait(timeout=25)
        assert process.returncode == 0 and process.stdout.read() == b"", "EOF must not push a response"
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        stderr.close()
    final = json.dumps({"protocol_version": 2, "id": "package-final-frame", "scope_id": scope, "op": "state.get"}).encode()
    restarted = subprocess.run(command, input=final, capture_output=True, env=env, timeout=45)
    lines = restarted.stdout.splitlines()
    assert restarted.returncode == 0 and len(lines) == 1, (restarted.returncode, restarted.stderr, lines)
    response = json.loads(lines[0])
    assert response["ok"]
    assert_english(response)
    display(response["result"])
    frames.append(final)
    for side in ("public", "internal"):
        with sqlite3.connect((profile / "audit" / (side + ".sqlite3")).as_uri() + "?mode=ro", uri=True) as db:
            wire = db.execute("SELECT raw_bytes,raw_format FROM exchanges ORDER BY sequence").fetchall()
            assert [row[0] for row in wire] == frames
            assert [row[1] for row in wire] == ["utf8-lf", "invalid-utf8", "utf8-lf", "utf8-lf", "utf8-eof"]
            if side == "internal":
                runtime = json.loads(db.execute("SELECT text FROM logs WHERE channel='runtime.environment' ORDER BY sequence LIMIT 1").fetchone()[0])
                assert runtime["os_arch"] == "aarch64" and runtime["build_id"] == expected_build, runtime
                assert runtime["error_file"] == str(profile / "audit/emergency/hs_err_pid%p.log")
                assert db.execute("SELECT count(*) FROM logs WHERE channel='recovered_emergency_base64'").fetchone()[0] == 0, \
                    "A rejected locked-profile launch must not produce recoverable diagnostics"
    result = {"case_id": "package.english_raw_pipe", "verified": True, "profile": str(profile),
            "gui_display": mode, "jvm": jvm, "exact_frames_checked": len(frames), "runtime": runtime,
            "profile_lock_rejects_second_process": True, "malformed_utf8_recovery": True,
            "rejected_profile_emergency_unchanged": True, "spurious_emergency_recovery_absent": True,
            "duplicate_id_rejected": True, "eof_has_no_push": True, "final_frame_without_newline": True,
            "database_checks": audit_health(profile)}
    write_json(profile / "raw-pipe-result.json", result)
    return result


class PackageClient(Client):
    def __init__(self, cli, profile, env):
        # Deliberately launch only the native packaged CLI; no host Java executable or classpath.
        self.profile = profile
        self.responses = []
        self.uncertain = False
        self.last_state = None
        self.trace = (profile / "public-trace.jsonl").open("a")
        self.stderr = (profile / "native-stderr.log").open("ab")
        self.process = subprocess.Popen([str(cli), "run", "--machine", "--data-dir", str(profile),
                                         "--no-terminal", "--trace-dir", str(profile.parent / "transport")],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr, env=env)
        self.scope = self.version = None
        self.buffer = b""
        self.counter = 0
        self.prefix = "package-" + uuid.uuid4().hex[:12]

    def request(self, op, args=None, **kwargs):
        response = super().request(op, args, **kwargs)
        if response.get("error", {}).get("code") in {"EXECUTION_UNKNOWN", "EXECUTION_UNCERTAIN"}:
            self.uncertain = True
        self.trace.write(json.dumps({"test_fixture": True, "op": op, "args": args, "response": response}, ensure_ascii=False) + "\n")
        self.trace.flush()
        assert_english(response)
        if response.get("ok"):
            failures=validate_sources(response.get("result"))
            assert not failures, {"source_failures":failures[:20],"op":op}
        self.responses.append((op, response))
        state = response.get("result", {})
        if response.get("ok") and isinstance(state, dict) and "observation" in state:
            self.last_state = state
            display(state)
        return response


def settings_confirmation(client):
    activate(client, "Menu")
    opened = activate(client, "Settings")["result"]
    # Icon tabs are unlabeled original controls. Discover the actual displayed panel,
    # without assuming a hidden tab index or using an OS pointer coordinate.
    candidates = [a["control"] for a in opened["actions"] if a["action"] == "ui.activate" and not a.get("label")]
    for control in [None] + candidates:
        state = checked_state(client)
        controls = state["observation"]["ui"]["controls"]
        checks = [node for node in controls if (node.get("label") or node.get("text", "")).casefold() == "fullscreen" and "checked" in node]
        if checks:
            assert len(checks) == 1 and checks[0]["checked"] is False
            confirmation = {"original_fullscreen_checkbox_checked": False, "display": display(state)}
            execute(client, "ui.back")
            return confirmation
        if control is not None and any(a.get("control") == control for a in state["actions"]):
            execute(client, "ui.activate", control=control)
    raise AssertionError("The original Display Settings fullscreen checkbox was not observed")


def original_food_pair(profile, english_event, scope):
    with sqlite3.connect((profile / "audit/internal.sqlite3").as_uri() + "?mode=ro", uri=True) as db:
        original = []
        for (text,) in db.execute("SELECT text FROM logs WHERE channel='displayed_text_original'"):
            value = json.loads(text)
            if value["scope_id"] == scope and value["kind"] == "game.log" \
                    and value.get("event_sequence") == english_event["sequence"] \
                    and value["original_display"]["occurred_at"] == english_event["data"]["occurred_at"]:
                original.append(value["original_display"])
    assert len(original) == 1, "Match the exact event sequence and display time, not unrelated Chinese logs"
    assert any("吃起来不错！" in entry["text"] for entry in original[0]["entries"])
    assert len(original[0]["entries"]) == len(english_event["data"]["entries"])
    return {"scope_id": scope, "public_event_sequence": english_event["sequence"],
            "occurred_at": english_event["data"]["occurred_at"],
            "english_food_message": "That food tasted delicious!", "original_displayed_food_message": "吃起来不错！"}


def game_case(cli, bundle, output, env, expected_build, expected_cli):
    profile = new_profile(output, "实际游戏 中文目录 with spaces")
    client = PackageClient(cli, profile, env)
    checks = []
    try:
        hello = client.request("protocol.info")
        assert hello["ok"] and hello["result"]["build_id"] == expected_build
        assert hello["result"]["cli_version"] == expected_cli
        reach_game(client)
        # Complete any normal first-run tutorial through its original public controls.
        finish_tutorial(client)
        state = checked_state(client)
        items = english_inventory(state)
        scope = state["scope_id"]
        jvm = loaded_jvm(client.process, bundle)
        checks.append("packaged_start_and_original_tutorial_to_ready")
        settings = settings_confirmation(client)
        checks.append("actual_selected_chinese_and_windowed_settings_confirmed")
        stone = next(item for item in items if item["name"].casefold() == "throwing stone")
        execute(client, "inventory.open", locator=stone["locator"])
        aiming = activate(client, "THROW")["result"]
        assert any(a["action"] == "cell.cancel" for a in aiming["actions"])
        cancelled = execute(client, "cell.cancel")["result"]
        assert next(item["quantity"] for item in cancelled["observation"]["inventory"] if item["name"].casefold() == "throwing stone") == stone["quantity"]
        checks.append("original_inventory_throw_cancel_preserves_quantity")
        food = next(item for item in cancelled["observation"]["inventory"] if item["name"].casefold() == "ration of food")
        execute(client, "inventory.open", locator=food["locator"])
        eaten = activate(client, "EAT")
        deadline = time.monotonic() + 10
        food_event = None
        while time.monotonic() < deadline:
            checked_state(client)
            for event in public_events(client, scope):
                if event["kind"] == "game.log" and any("That food tasted delicious!" in entry["text"] for entry in event["data"]["entries"]):
                    food_event = event
                    break
            if food_event:
                break
            time.sleep(.05)
        assert food_event, "The package must expose its actual drawn food message in English"
        record = client.request("request.get", {"target_id": eaten["id"]}, scope=scope)
        assert record["ok"] and record["result"]["response"] == eaten
        assert client.request("actions.list")["ok"]
        saved = execute(client, "game.save")["result"]
        assert any(receipt["success"] for receipt in saved["persistence"]["saves_during_request"])
        before_hero = {key: saved["observation"]["hero"][key] for key in ("class", "cell", "depth", "hp", "level", "gold")}
        before_inventory = [(item["locator"], item["name"], item["quantity"], item["equipped"])
                            for item in saved["observation"]["inventory"]]
        checks.append("original_drawn_english_log_and_explicit_save_receipt")
        stop(client)
        pair = original_food_pair(profile, food_event, scope)
        resumed = PackageClient(cli, profile, env)
        try:
            assert resumed.request("protocol.info")["result"]["build_id"] == expected_build
            after = reach_game(resumed, resume=True)
            assert after["scope_id"] == scope
            assert {key: after["observation"]["hero"][key] for key in before_hero} == before_hero
            english_inventory(after)
            assert [(item["locator"], item["name"], item["quantity"], item["equipped"])
                    for item in after["observation"]["inventory"]] == before_inventory
            restarted_jvm = loaded_jvm(resumed.process, bundle)
            record = resumed.request("request.get", {"target_id": eaten["id"]}, scope=scope)
            assert record["ok"] and record["result"]["response"] == eaten
            assert any(event["sequence"] == food_event["sequence"] for event in public_events(resumed, scope))
            stop(resumed)
        finally:
            if resumed.process.poll() is None:
                stop(resumed, failure_cleanup=True)
        checks.append("second_packaged_jvm_restores_same_run_and_original_history")
        result = {"case_id": "package.english_game_and_restart", "verified": True, "profile": str(profile),
                "scope_id": scope, "jvm": jvm, "restarted_jvm": restarted_jvm, "settings": settings,
                "original_draw_pair": pair, "checks": checks, "database_checks": audit_health(profile)}
        write_json(profile / "game-result.json", result)
        return result
    except Exception as error:
        write_json(profile / "failure.json", {"verified": False, "checks": checks, "error": str(error)[:4000]})
        raise
    finally:
        if client.process.poll() is None:
            stop(client, failure_cleanup=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--expected-cli", required=True, help="Exact CLI version expected in the application bundle")
    parser.add_argument("--reuse-raw-result", type=Path,
                        help="Reuse an unchanged-build successful raw case; preserves its original profile and report")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    output = root / "desktop-control/build/fixtures/packaging2.1" / ("english-" + uuid.uuid4().hex)
    output.mkdir(parents=True)
    bundle = output / "中文 应用目录 with spaces" / args.bundle.name
    shutil.copytree(args.bundle.resolve(), bundle, symlinks=True)
    cli = bundle / "Contents/MacOS/spdctl"
    binary = verify_bundle(bundle, args.expected_cli)
    env = environment()
    try:
        if args.reuse_raw_result:
            source = args.reuse_raw_result.resolve()
            assert source.is_relative_to((root / "desktop-control/build/fixtures/packaging2.1").resolve())
            raw = json.loads(source.read_text())
            assert raw["verified"] is True and raw["case_id"] == "package.english_raw_pipe"
            assert raw["runtime"]["build_id"] == binary["build_id"], "A different production build must rerun the raw case"
        else:
            raw = raw_pipe_case(cli, bundle, output, env, binary["build_id"], args.expected_cli)
        game = game_case(cli, bundle, output, env, binary["build_id"], args.expected_cli)
        subprocess.run(["/usr/bin/codesign", "--verify", "--deep", "--strict", str(bundle)], check=True)
        report = {"verified": True, "test_fixture": True, "counts_as_win": False, "bundle": str(bundle),
                  "source_bundle": str(args.bundle.resolve()), "binary": binary, "cases": [raw, game],
                  "reused_raw_report": str(args.reuse_raw_result.resolve()) if args.reuse_raw_result else None,
                  "injected_java_classes_or_agents": False, "external_java_home_or_classpath": False,
                  "not_tested": ["real_Intel_hardware", "Gatekeeper_notarization", "all_game_scenarios"]}
        write_json(output / "result.json", report)
        print(json.dumps(report, ensure_ascii=False), flush=True)
    except Exception as error:
        write_json(output / "failure.json", {"verified": False, "test_fixture": True, "counts_as_win": False,
                   "bundle": str(bundle), "binary": binary, "error": str(error)[:4000]})
        raise


if __name__ == "__main__":
    main()
