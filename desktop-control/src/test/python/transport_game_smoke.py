#!/usr/bin/env python3
"""Production CLI 2.1: fresh defaults, passive viewers, exact transport and saved restart.

All game input uses the packaged public protocol. Test metadata and captures live
outside the profile, so an absent/empty profile stays fresh until Java opens it.
No fixture launcher, save inspection, game-state injection or host JVM is used.
"""
import argparse
import json
import os
from pathlib import Path
import select
import subprocess
import tempfile
import time
import uuid

from english_protocol_smoke import (assert_english, checked_state, english_inventory,
                                    execute, start_or_continue, stop)
from language_matrix_smoke import validate as validate_sources
from machine_smoke import Client
from package_english_smoke import display, environment, loaded_jvm, verify_bundle, write_json


def wait_for(check, description, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(.02)
    raise AssertionError("Timed out: " + description)


def trace_session(root):
    sessions = sorted(root.glob("session-*")) if root.exists() else []
    assert len(sessions) <= 1, sessions
    return sessions[0] if sessions else None


def verify_trace(session, sent, received):
    assert not (session / ".incomplete").exists(), "A successful session must finalize its trace"
    rows = (session / "events.tsv").read_text().splitlines()
    assert rows and rows[0] == "SPDCTL_TRACE\t1", rows[:1]
    offsets = {kind: 0 for kind in ("SEND", "RECV", "STDERR", "DELIVERED")}
    status_count = 0
    for number, row in enumerate(rows[1:], 1):
        fields = row.split("\t", 5)
        assert len(fields) == 6, fields
        sequence, timestamp, kind, offset, length, detail = fields
        assert int(sequence) == number and int(timestamp) > 0, fields
        assert kind in {*offsets, "STATUS"}, fields
        if kind in offsets:
            assert int(offset) == offsets[kind] and int(length) > 0, fields
            offsets[kind] += int(length)
        else:
            status_count += 1
    assert status_count, "Lifecycle status must be indexed"
    assert (session / "send.raw").read_bytes() == sent
    assert (session / "recv.raw").read_bytes() == received
    assert offsets["SEND"] == len(sent)
    assert offsets["RECV"] == offsets["DELIVERED"] == len(received)
    assert offsets["STDERR"] == (session / "stderr.raw").stat().st_size
    return {"session": str(session), "transport_version": 1, "index_events": len(rows) - 1,
            "send_bytes": len(sent), "receive_bytes": len(received),
            "delivery_bytes": offsets["DELIVERED"], "stderr_bytes": offsets["STDERR"],
            "complete": True, "exact_bytes": True}


class CapturedClient(Client):
    def __init__(self, cli, profile, output, env, terminal=False):
        output.mkdir()
        self.profile, self.output = profile, output
        self.trace_root = output / "transport"
        self.trace = (output / "public-responses.jsonl").open("w")
        self.stderr = (output / "native-stderr.log").open("wb")
        command = [str(cli), "run", "--machine", "--data-dir", str(profile),
                   "--trace-dir", str(self.trace_root)]
        if not terminal:
            command.append("--no-terminal")
        self.process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr, env=env)
        self.scope = self.version = self.last_state = None
        self.buffer = b""
        self.counter = self.source_checks = self.early_send_checks = 0
        self.prefix = "transport-" + uuid.uuid4().hex[:12]
        self.sent, self.received = bytearray(), bytearray()
        self.responses = []
        self.uncertain = False

    def request(self, op, args=None, request_id=None, scope=None, version=None):
        self.counter += 1
        request = {"protocol_version": 2, "id": request_id or f"{self.prefix}-{self.counter}", "op": op}
        if scope is not None or self.scope is not None:
            request["scope_id"] = scope or self.scope
        if args is not None:
            request["args"] = args
        if op == "action.execute":
            request["state_version"] = version or self.version
        wire = (json.dumps(request, ensure_ascii=False) + "\n").encode("utf-8")
        assert self.process.stdin.write(wire) == len(wire)
        self.process.stdin.flush()
        self.sent.extend(wire)
        session = wait_for(lambda: trace_session(self.trace_root), "trace session creation")
        wait_for(lambda: (session / "send.raw").exists()
                 and (session / "send.raw").stat().st_size == len(self.sent),
                 "SEND recorded before the client reads a response")
        self.early_send_checks += 1
        deadline = time.monotonic() + 45
        while b"\n" not in self.buffer:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"No response for {op}; profile={self.profile}")
            if select.select([self.process.stdout], [], [], 1)[0]:
                chunk = os.read(self.process.stdout.fileno(), 65536)
                if not chunk:
                    raise AssertionError(("Unexpected EOF", self.process.poll(), op))
                self.received.extend(chunk)
                self.buffer += chunk
        line, self.buffer = self.buffer.split(b"\n", 1)
        response = json.loads(line)
        assert response.get("id") == request["id"], (request, response)
        assert_english(response)
        if response.get("ok"):
            failures = validate_sources(response.get("result"))
            assert not failures, {"op": op, "source_failures": failures[:20]}
            self.source_checks += 1
        if response.get("error", {}).get("code") in {"EXECUTION_UNKNOWN", "EXECUTION_UNCERTAIN"}:
            self.uncertain = True
        state = response.get("result", {})
        if isinstance(state, dict) and response.get("ok"):
            if op in {"protocol.info", "state.get", "actions.list", "action.execute"}:
                self.scope = state.get("scope_id", self.scope)
                self.version = state.get("state_version") or self.version
            if "observation" in state:
                self.last_state = state
                display(state)
        self.trace.write(json.dumps({"op": op, "response": response}, ensure_ascii=False) + "\n")
        self.trace.flush()
        self.responses.append((op, response))
        return response

    def final_trace(self):
        assert self.process.poll() == 0, self.process.returncode
        tail = self.process.stdout.read()
        self.received.extend(tail)
        assert not tail and not self.buffer, "No unsolicited protocol output at normal exit"
        (self.output / "controller-send.raw").write_bytes(self.sent)
        (self.output / "controller-recv.raw").write_bytes(self.received)
        result = verify_trace(trace_session(self.trace_root), bytes(self.sent), bytes(self.received))
        result.update(requests=self.counter, source_checks=self.source_checks,
                      sends_visible_before_response_read=self.early_send_checks)
        return result


def hero_inventory(state):
    hero = state["observation"]["hero"]
    return {"hero": {key: hero[key] for key in ("class", "cell", "depth", "hp", "level", "gold")},
            "inventory": [(item["locator"], item["name"], item["quantity"], item["equipped"])
                          for item in state["observation"]["inventory"]]}


def viewer_roundtrip(cli, client, env):
    session = trace_session(client.trace_root)
    before = hero_inventory(checked_state(client))
    views = []
    for number in range(2):
        target = client.output / f"viewer-{number}.log"
        with target.open("wb") as stream:
            viewer = subprocess.Popen([str(cli), "trace", "view", "--session", str(session)],
                                      stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, env=env)
            try:
                previous_id = client.responses[0][1]["id"].encode()
                wait_for(lambda: previous_id in target.read_bytes(), "viewer replays previous raw records")
                current_id = "passive-viewer-" + str(number)
                state = client.request("state.get", request_id=current_id)
                assert state["ok"] and hero_inventory(state["result"]) == before
                wait_for(lambda: current_id.encode() in target.read_bytes(), "viewer follows new raw records")
                assert viewer.poll() is None, "An active trace viewer must keep following"
            finally:
                if viewer.poll() is None:
                    viewer.terminate()
                viewer.wait(timeout=10)
        assert client.process.poll() is None, "Closing a viewer must not stop the game or relay"
        assert hero_inventory(checked_state(client)) == before
        views.append({"viewer_pid": viewer.pid, "log": str(target), "closed_without_game_change": True})
    return views


def fresh_case(cli, bundle, output, env, initial_kind, expected_build, expected_cli):
    profile = output / (initial_kind + "-profile")
    if initial_kind == "empty":
        profile.mkdir()
        assert list(profile.iterdir()) == []
    else:
        assert not profile.exists()
    client = CapturedClient(cli, profile, output / (initial_kind + "-first"), env)
    result = {"initial_profile": initial_kind, "profile": str(profile)}
    try:
        hello = client.request("protocol.info")
        assert hello["ok"] and hello["result"]["build_id"] == expected_build
        assert hello["result"]["cli_version"] == expected_cli
        title = checked_state(client)
        assert title["observation"]["ui"]["scene"] == "TitleScene", title["observation"]["ui"]
        result.update(default_display=display(title), initial_scene="TitleScene", jvm=loaded_jvm(client.process, bundle))
        game, scenes = start_or_continue(client)
        assert "WelcomeScene" not in scenes, scenes
        assert game["observation"]["hero"]["class"] == "warrior"
        english_inventory(game)
        assert any(action["action"] == "inventory.open" for action in game["actions"]), \
            "A fresh Warrior must have inventory available before any tutorial/game action"
        execute(client, "inventory.open", locator="equipment.armor")
        execute(client, "ui.back")
        result.update(menu_scenes=scenes, tutorial_completion_actions=0, initial_inventory_available=True)
        if initial_kind == "missing":
            result["passive_viewers"] = viewer_roundtrip(cli, client, env)
        saved = execute(client, "game.save")
        assert any(receipt["success"] for receipt in saved["result"]["persistence"]["saves_during_request"])
        scope = saved["result"]["scope_id"]
        before = hero_inventory(saved["result"])
        stop(client)
        result["first_transport"] = client.final_trace()
    finally:
        if client.process.poll() is None:
            stop(client, failure_cleanup=True)
    resumed = CapturedClient(cli, profile, output / (initial_kind + "-restart"), env)
    try:
        assert resumed.request("protocol.info")["result"]["build_id"] == expected_build
        after, scenes = start_or_continue(resumed, resume=True)
        assert after["scope_id"] == scope and hero_inventory(after) == before
        history = resumed.request("request.get", {"target_id": saved["id"]}, scope=scope)
        assert history["ok"] and history["result"]["response"] == saved
        result.update(restarted_jvm=loaded_jvm(resumed.process, bundle), saved_scope_id=scope,
                      save_request_id=saved["id"], same_run_and_history=True)
        stop(resumed)
        result["restart_transport"] = resumed.final_trace()
    finally:
        if resumed.process.poll() is None:
            stop(resumed, failure_cleanup=True)
    result["verified"] = True
    return result


def self_test():
    with tempfile.TemporaryDirectory(prefix="spdctl-trace-validation-") as temporary:
        session = Path(temporary)
        sent, received = b'{"text":"\xe4\xb8\xad\xe6\x96\x87"}\r\n\xff', b'{ "ok" : true }\n'
        (session / "send.raw").write_bytes(sent)
        (session / "recv.raw").write_bytes(received)
        (session / "stderr.raw").write_bytes(b"diagnostic\n")
        rows = ["SPDCTL_TRACE\t1", "1\t1\tSTATUS\t0\t0\tstarted",
                f"2\t2\tSEND\t0\t{len(sent)}\t", f"3\t3\tRECV\t0\t{len(received)}\t",
                f"4\t4\tDELIVERED\t0\t{len(received)}\t", "5\t5\tSTDERR\t0\t11\t",
                "6\t6\tSTATUS\t0\t0\tended"]
        (session / "events.tsv").write_text("\n".join(rows) + "\n")
        assert verify_trace(session, sent, received)["exact_bytes"]
        (session / "recv.raw").write_bytes(received + b"unexpected")
        try:
            verify_trace(session, sent, received)
        except AssertionError:
            pass
        else:
            raise AssertionError("Raw corruption must be rejected")
    print(json.dumps({"self_test": "passed", "cases": ["format_v1_exact_bytes", "raw_corruption_rejected"]}))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--expected-cli", default="CLI.2.1.0")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.bundle is None:
        parser.error("--bundle is required for the actual package test")
    root = Path(__file__).resolve().parents[4]
    output = root / "desktop-control/build/fixtures/packaging2.1" / ("transport-defaults-" + uuid.uuid4().hex)
    output.mkdir(parents=True)
    write_json(output / "test_fixture.json", {"test_fixture": True, "counts_as_win": False,
               "setup": "Production CLI only; profile settings and game state are not prewritten"})
    bundle = args.bundle.resolve()
    cli = bundle / "Contents/MacOS/spdctl"
    binary = verify_bundle(bundle, args.expected_cli)
    report = {"test_fixture": True, "counts_as_win": False, "bundle": str(bundle), "binary": binary, "cases": []}
    try:
        for initial_kind in ("missing", "empty"):
            case = fresh_case(cli, bundle, output, environment(), initial_kind, binary["build_id"], args.expected_cli)
            report["cases"].append(case)
            write_json(output / "progress.json", report)
        report.update(verified=True, gui_input="public CLI protocol only", source_checks=True,
                      original_save_reads=False, terminal_window_open_test="separate manual/native UI acceptance")
        write_json(output / "result.json", report)
        print(json.dumps(report, ensure_ascii=False), flush=True)
    except Exception as failure:
        report.update(verified=False, error=str(failure)[:4000])
        write_json(output / "failure.json", report)
        raise


if __name__ == "__main__":
    main()
