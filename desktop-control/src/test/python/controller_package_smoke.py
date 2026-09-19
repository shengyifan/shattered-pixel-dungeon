#!/usr/bin/env python3
"""Exercise the packaged controller with public controls and disposable profiles.

The profile starts absent. No fixture engine, game-state injection, save reads,
SQLite reads, host JVM, or gameplay policy is used. Exact model-facing bytes and
the native child's independent transport trace are retained in build/fixtures.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import select
import signal
import subprocess
import time
import uuid
import zlib

import protocol6


SUCCESS = {"COMPLETED", "AWAITING_INPUT", "INTERRUPTED"}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class StaleAction(Exception):
    """Definite, receipt-verified rejection; a caller must choose again."""


class ControllerClient:
    def __init__(self, cli, profile, output, environment):
        output.mkdir()
        self.output, self.trace_root = output, output / "transport"
        self.send_file = (output / "intent-send.raw").open("wb")
        self.recv_file = (output / "controller-recv.raw").open("wb")
        self.events = (output / "action-events.jsonl").open("w", encoding="utf-8")
        self.stderr = (output / "launcher-stderr.log").open("wb")
        self.process = subprocess.Popen(
            [str(cli), "control", "--machine", "--data-dir", str(profile),
             "--trace-dir", str(self.trace_root), "--no-terminal"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr, env=environment,
            start_new_session=True)
        # This isolated process group belongs only to this test and its native/game descendants.
        self.process_group = self.process.pid
        assert os.getpgid(self.process.pid) == self.process_group
        self.buffer = bytearray()
        self.wire_frames, self.exchanges = [], []
        self.initial_pending, self.settle_calls, self.stale_rejections = 0, 0, 0
        self.current = self.state_view = None
        self.scope = self.revision = None
        try:
            self.hello = self.receive()
            self.remember_wire(self.hello)
            assert self.hello.get("st") == "completed" and "err" not in self.hello, self.hello
            self.prefix = self.hello["data"]["request_prefix"]
            assert re.fullmatch(r"t[0-9a-z]+", self.prefix), self.hello
            assert self.hello["data"]["cli_version"] == "CLI.6.0.0", self.hello
            assert self.hello["data"]["audit_schema_version"] == 9, self.hello
            self.install(self.hello, "info")
        except Exception:
            self.cleanup()
            raise

    def receive(self, timeout=45):
        deadline = time.monotonic() + timeout
        while b"\n" not in self.buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"Controller response timed out; artifacts={self.output}")
            if select.select([self.process.stdout], [], [], min(remaining, 1))[0]:
                chunk = os.read(self.process.stdout.fileno(), 65536)
                assert chunk, ("Unexpected controller EOF", self.process.poll(), bytes(self.buffer))
                self.recv_file.write(chunk)
                self.recv_file.flush()
                self.buffer.extend(chunk)
        line, self.buffer = self.buffer.split(b"\n", 1)
        return json.loads(line.decode("utf-8", errors="strict"))

    def request(self, intent):
        encoded = protocol6.wire_bytes(intent)
        self.send_file.write(encoded)
        self.send_file.flush()
        assert self.process.stdin.write(encoded) == len(encoded)
        self.process.stdin.flush()
        result = self.receive()
        self.exchanges.append((intent, result))
        self.remember_wire(result)
        self.events.write(json.dumps({"intent": intent, "response": result}, ensure_ascii=False) + "\n")
        self.events.flush()
        return result

    def remember_wire(self, value):
        if not isinstance(value, dict):
            return
        if value.get("v") == 6 and "id" in value and ("st" in value or "err" in value):
            self.wire_frames.append(value)
            return
        for field in ("response", "outcome", "observation", "discovery", "initial_error", "original_response", "receipt"):
            self.remember_wire(value.get(field))
        for late in value.get("late_responses", []):
            self.remember_wire(late.get("response"))

    @staticmethod
    def unwrap(result):
        return result["response"] if result.get("controller") == "response" else result

    def install(self, result, op):
        assert result.get("v") == 6 and "err" not in result, result
        view = protocol6.response(result, op)
        assert view["ok"], view
        self.scope = result.get("s", self.scope)
        self.revision = result.get("rev", self.revision)
        if "observation" in view.get("result", {}):
            self.current, self.state_view = result, view["result"]
        return self.state_view

    def state(self):
        response = self.unwrap(self.request({"op": "state"}))
        return self.install(response, "state")

    def settle(self, original):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            self.settle_calls += 1
            result = self.request({"op": "settle", "rid": original["id"]})
            assert result.get("controller") == "settle", result
            assert result["rid"] == original["id"], result
            if "outcome" in result:
                receipt = result["outcome"]
                assert receipt["id"] != original["id"], result
                assert receipt["data"]["id"] == original["id"], result
            if result.get("st") == "pending":
                continue
            return result
        raise TimeoutError(f"Action did not settle: {original['id']}")

    def action(self, op, **arguments):
        assert self.revision, self.hello
        original_scope = self.scope
        response = self.unwrap(self.request({"op": op, "rev": self.revision, **arguments}))
        if response.get("err") == "STALE_STATE":
            settled = self.settle(response)
            assert settled.get("err") == "ACTION_REJECTED", settled
            assert settled["outcome"]["data"]["st"] == "REJECTED", settled
            self.stale_rejections += 1
            self.state()
            raise StaleAction(op)
        assert response.get("v") == 6 and "err" not in response, response
        assert response["id"].startswith(self.prefix + "."), response
        if response["st"] == "in_progress":
            # This frame was already written and flushed to action-events before any settle command.
            self.initial_pending += 1
            self.events.write(json.dumps({"initial_pending_exposed": response["id"]}) + "\n")
            self.events.flush()
            settled = self.settle(response)
            assert settled.get("st") == "completed", settled
            assert settled["s"] == original_scope, settled
            assert settled["outcome"]["data"]["st"] in SUCCESS, settled
            if op != "quit":
                assert "observation" in settled, settled
                self.install(settled["observation"], "state")
            else:
                assert "observation" not in settled and "discovery" not in settled, settled
            return {"initial": response, "settled": settled, "request_scope": original_scope}
        assert response["st"].upper() in SUCCESS, response
        self.install(response, op)
        return {"initial": response, "request_scope": original_scope}

    def trace_session(self):
        sessions = list(self.trace_root.glob("session-*"))
        assert len(sessions) == 1, {"child_only_trace_sessions": [str(path) for path in sessions]}
        return sessions[0]

    def finish_exit(self):
        self.process.wait(timeout=40)
        assert self.process.returncode == 0, self.process.returncode
        tail = self.process.stdout.read()
        self.recv_file.write(tail)
        self.recv_file.flush()
        assert not self.buffer and not tail, "Unsolicited controller output after successful quit"

    def cleanup(self):
        try:
            if not self.process.stdin.closed:
                try:
                    self.process.stdin.close()
                except BrokenPipeError:
                    pass
            if self.process.poll() is None:
                try:
                    self.process.wait(timeout=35)
                except subprocess.TimeoutExpired:
                    self.stop_fixture_group()
            # A failed controller may have exited before its recorder/game child.
            if self.group_alive():
                self.stop_fixture_group()
        finally:
            for stream in (self.process.stdout, self.send_file, self.recv_file, self.events, self.stderr):
                stream.close()

    def group_alive(self):
        try:
            os.killpg(self.process_group, 0)
            return True
        except ProcessLookupError:
            return False

    def stop_fixture_group(self):
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(self.process_group, sig)
            except ProcessLookupError:
                break
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                self.process.poll()  # Reap the owned parent before checking its remaining descendants.
                if not self.group_alive():
                    break
                time.sleep(.05)
            if not self.group_alive():
                break
        self.process.wait(timeout=5)
        assert not self.group_alive(), "Disposable fixture process group remains after cleanup"


def available(state, canonical):
    return [action for action in state.get("actions", []) if action.get("action") == canonical]


def reach_warrior(client, resume=False):
    seen, selected = [], False
    state = client.state_view or client.state()
    for _ in range(35):
        observation = state.get("observation", {})
        ui = observation.get("ui", {})
        scene = ui.get("scene")
        seen.append(scene)
        if observation.get("scene") == "game":
            assert observation["hero"]["class"] == "warrior", observation["hero"]
            if ui.get("modal") and available(state, "ui.back"):
                try:
                    client.action("back")
                except StaleAction:
                    pass
                state = client.state_view
                continue
            return state, seen
        actions = available(state, "ui.activate")
        choice = None
        if resume and scene == "StartScene":
            choice = next((action for action in actions if action.get("label", "").split("\n")[0].casefold() == "warrior"), None)
        if not resume and scene == "HeroSelectScene" and not selected:
            choice = next((action for action in actions if action.get("label", "").casefold() == "warrior"), None)
        if choice is None:
            for label in ("continue", "enter the dungeon", "play", "new game", "start"):
                choice = next((action for action in actions if action.get("label", "").casefold() == label), None)
                if choice:
                    break
        try:
            if choice:
                chosen_warrior = choice.get("label", "").casefold() == "warrior"
                client.action("click", ctl=choice["control"])
                if chosen_warrior:
                    selected = True
            elif available(state, "ui.reveal"):
                client.action("reveal")
            elif ui.get("modal") and available(state, "ui.back"):
                client.action("back")
            else:
                raise AssertionError({"no_advertised_menu_control": scene, "actions": actions})
        except StaleAction:
            pass  # A new loop reacquires the actual node from the newly displayed state.
        state = client.state_view
    raise AssertionError({"menu_flow_did_not_finish": seen})


def snapshot(state):
    observation = state["observation"]
    return {"hero": {field: observation["hero"].get(field) for field in
                     ("class", "cell", "depth", "hp", "level", "gold", "strength")},
            "inventory": [(item["locator"], item["name"], item["quantity"], item["equipped"])
                          for item in observation.get("inventory", [])]}


def native_operation(client, operation):
    canonical = {"save": "game.save", "quit": "app.quit"}[operation]
    for _ in range(6):
        assert available(client.state_view, canonical), {"unavailable": operation, "state": client.state_view}
        try:
            return client.action(operation)
        except StaleAction:
            continue
    raise AssertionError(f"Unstable {operation} availability")


def save_receipts(action):
    if "settled" in action:
        frame = action["settled"]["outcome"]
        rows = protocol6.expand_structures(frame)["data"].get("save", [])
    else:
        frame = protocol6.expand_structures(action["initial"])
        rows = frame["data"].get("persistence", {}).get("saves", [])
    assert rows, action
    for receipt in rows:
        assert receipt["success"] is True and re.fullmatch(r"p[0-9a-z]+", receipt["sid"]), receipt
        assert receipt["src_id"] == action["initial"]["id"], (receipt, action)
        assert receipt.get("src_s", receipt.get("s", action["request_scope"])) == action["request_scope"], receipt
    return rows


def validate_trace(client):
    session = client.trace_session()
    assert not (session / ".incomplete").exists(), session
    raw = {name: (session / name).read_bytes() for name in ("send.raw", "recv.raw", "stderr.raw")}
    for name in ("send.raw", "recv.raw"):
        assert b"\x1b" not in raw[name] and raw[name].endswith(b"\n"), name
    sent = [json.loads(line) for line in raw["send.raw"].splitlines()]
    received = [json.loads(line) for line in raw["recv.raw"].splitlines()]
    assert len(sent) == len(received), (len(sent), len(received))
    assert sent[0]["op"] == "info" and re.fullmatch(r"h[0-9a-f]{32}", sent[0]["id"]), sent[0]
    previous = 0
    by_id = {}
    for index, (request, response) in enumerate(zip(sent, received)):
        assert request["v"] == response["v"] == 6 and request["id"] == response["id"], (request, response)
        assert request["id"] not in by_id, request
        by_id[request["id"]] = response
        if index:
            prefix, count = request["id"].split(".")
            assert prefix == client.prefix and int(count, 36) > previous, request
            previous = int(count, 36)
        if request["op"] == "req":
            assert request["id"] != request["rid"] and "get" not in request, request
    for wire in client.wire_frames:
        assert by_id.get(wire["id"]) == wire, {"controller_frame_differs_from_child": wire}
    quit_index = max(i for i, request in enumerate(sent) if request["op"] == "quit")
    after_quit = sent[quit_index + 1:]
    assert all(request["op"] == "req" and request["rid"] == sent[quit_index]["id"] for request in after_quit), after_quit
    rows = (session / "events.tsv").read_text().splitlines()
    assert rows[0] == "SPDCTL_TRACE\t1", rows[:1]
    offsets = {"SEND": 0, "RECV": 0, "STDERR": 0, "DELIVERED": 0}
    for number, row in enumerate(rows[1:], 1):
        sequence, timestamp, kind, offset, length, detail = row.split("\t", 5)
        assert int(sequence) == number and int(timestamp) > 0
        if kind in offsets:
            assert int(offset) == offsets[kind] and int(length) > 0, row
            offsets[kind] += int(length)
        else:
            assert kind == "STATUS", row
    assert offsets["SEND"] == len(raw["send.raw"])
    assert offsets["RECV"] == offsets["DELIVERED"] == len(raw["recv.raw"])
    assert offsets["STDERR"] == len(raw["stderr.raw"])
    return {"trace": str(session), "child_trace_count": 1, "requests": len(sent),
            "model_wire_frames_verified": len(client.wire_frames), "in_progress_exposed": client.initial_pending,
            "settle_calls": client.settle_calls, "definite_stale_rejections": client.stale_rejections,
            "raw": {name: {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                           "crc32": f"{zlib.crc32(data):08x}"} for name, data in raw.items()},
            "no_state_after_quit": True, "exact_id_body_matches": True}


def verify_styles(rendered):
    plain, styles = bytearray(), []
    bold, color, position = False, 39, 0
    for escape in re.finditer(rb"\x1b\[([0-9;]*)m", rendered):
        segment = rendered[position:escape.start()]
        plain.extend(segment)
        styles.extend([(bold, color)] * len(segment))
        for value in escape[1].split(b";"):
            code = int(value or b"0")
            if code == 0:
                bold, color = False, 39
            elif code == 1:
                bold = True
            elif code == 22:
                bold = False
            elif code == 39 or 30 <= code <= 37 or 90 <= code <= 97:
                color = code
            else:
                raise AssertionError(("Unexpected SGR", code))
        position = escape.end()
    segment = rendered[position:]
    plain.extend(segment)
    styles.extend([(bold, color)] * len(segment))
    offset, headings = 0, {"SEND": 0, "RECV": 0}
    for line in bytes(plain).splitlines(keepends=True):
        content = line.rstrip(b"\r\n")
        heading = re.fullmatch(rb"\[\d+\.\d+\] #\d+ (SEND|RECV)(?: \(continued\))?", content)
        values = styles[offset:offset + len(content)]
        if heading:
            assert all(style == (True, 96) for style in values), content
            headings[heading[1].decode()] += 1
        else:
            assert all(not style[0] for style in values), content
        assert all(not style[0] for style in styles[offset + len(content):offset + len(line)]), content
        offset += len(line)
    assert all(headings.values()) and (bold, color) == (False, 39), (headings, bold, color)
    return bytes(plain), headings


def viewer_check(cli, client, environment):
    session = client.trace_session()
    before = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
              for path in session.iterdir() if path.is_file()}
    outputs = {}
    for mode in ("always", "never"):
        result = subprocess.run([str(cli), "trace", "view", "--session", str(session),
                                 "--stream", "all", "--color", mode], stdin=subprocess.DEVNULL,
                                capture_output=True, env=environment, timeout=30)
        assert result.returncode == 0, result.stderr.decode(errors="replace")
        outputs[mode] = result.stdout
        (client.output / f"viewer-{mode}.raw").write_bytes(result.stdout)
        (client.output / f"viewer-{mode}-stderr.raw").write_bytes(result.stderr)
    stripped, headings = verify_styles(outputs["always"])
    assert stripped == outputs["never"], "Colored viewer changed transcript text"
    after = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
             for path in session.iterdir() if path.is_file()}
    assert before == after, "Passive viewer modified the raw trace"
    return {"header_only_bold": True, "send_recv_foreground": 96, "headings": headings,
            "plain_text_identical": True, "raw_files_unchanged": True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    output = root / "desktop-control/build/fixtures" / ("cli6-controller-" + uuid.uuid4().hex)
    output.mkdir(parents=True)
    profile = output / "fresh 中文 profile"
    assert not profile.exists()
    cli = args.bundle.resolve() / "Contents/MacOS/spdctl"
    assert cli.is_file(), cli
    environment = dict(os.environ)
    environment.pop("JAVA_HOME", None)
    environment["PATH"] = "/usr/bin:/bin"
    version = subprocess.check_output([str(cli), "--version"], env=environment, text=True).strip()
    assert version == "CLI.6.0.0 (protocol 6, game 3.3.8)", version
    clients, report = [], {"result": "running", "counts_as_win": False, "bundle": str(args.bundle.resolve()),
                           "artifacts": str(output), "isolated_profile": str(profile), "version": version,
                           "fresh_defaults": True, "personal_profile_or_audit_reads": False}
    try:
        first = ControllerClient(cli, profile, output / "first", environment)
        clients.append(first)
        first.state()
        initial, first_scenes = reach_warrior(first)
        before = snapshot(initial)
        assert before["hero"]["depth"] == 1 and before["hero"]["level"] == 1, before
        saved = native_operation(first, "save")
        first_receipts = save_receipts(saved)
        old_revision = first.revision
        run_scope = first.scope
        quit_first = native_operation(first, "quit")
        first_quit_receipts = save_receipts(quit_first)
        first.finish_exit()
        first_trace = validate_trace(first)
        styles = viewer_check(cli, first, environment)

        restarted = ControllerClient(cli, profile, output / "restarted", environment)
        clients.append(restarted)
        assert restarted.prefix != first.prefix and restarted.revision != old_revision, (first.hello, restarted.hello)
        trace_send = restarted.trace_session() / "send.raw"
        before_rejection = trace_send.read_bytes()
        rejected = restarted.request({"op": "save", "rev": old_revision})
        assert rejected.get("controller") == "error" and rejected.get("err") == "UNOBSERVED_REVISION", rejected
        assert trace_send.read_bytes() == before_rejection, "Rejected old revision reached the child"
        restarted.state()
        resumed, resumed_scenes = reach_warrior(restarted, resume=True)
        assert restarted.scope == run_scope, (run_scope, restarted.scope)
        assert snapshot(resumed) == before, {"before": before, "resumed": snapshot(resumed)}
        second_receipts = save_receipts(native_operation(restarted, "save"))
        second_quit_receipts = save_receipts(native_operation(restarted, "quit"))
        restarted.finish_exit()
        second_trace = validate_trace(restarted)
        all_receipts = first_receipts + first_quit_receipts + second_receipts + second_quit_receipts
        assert len({receipt["sid"] for receipt in all_receipts}) == len(all_receipts), all_receipts
        report.update(result="passed", session_prefixes=[first.prefix, restarted.prefix], run_scope=run_scope,
                      scenes=[first_scenes, resumed_scenes], hero_inventory=before,
                      old_revision_rejected_locally=True, saved_restart_matches=True,
                      save_receipts=all_receipts, transport=[first_trace, second_trace], viewer=styles,
                      not_tested=["full_playthrough", "forced_transport_loss_in_real_process", "independent_terminal_window_UI"])
    except Exception as error:
        report.update(result="failed", failure_type=type(error).__name__, failure=str(error))
    finally:
        for client in clients:
            try:
                client.cleanup()
            except Exception as cleanup_error:
                report.setdefault("cleanup_errors", []).append(str(cleanup_error))
                report["result"] = "failed"
        write_json(output / "result.json", report)
        summary = {"result": report["result"], "artifacts": str(output), "version": version,
                   "session_prefixes": report.get("session_prefixes"),
                   "wire_requests": [item["requests"] for item in report.get("transport", [])],
                   "in_progress_exposed": sum(item["in_progress_exposed"] for item in report.get("transport", [])),
                   "save_receipts": len(report.get("save_receipts", [])),
                   "old_revision_rejected_locally": report.get("old_revision_rejected_locally", False),
                   "saved_restart_matches": report.get("saved_restart_matches", False),
                   "viewer": report.get("viewer")}
        if "failure" in report:
            summary["failure_type"] = report["failure_type"]
            summary["failure_preview"] = report["failure"][:400]
        print(json.dumps(summary, ensure_ascii=False), flush=True)
    if report["result"] != "passed":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
