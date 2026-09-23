#!/usr/bin/env python3
"""Exercise the packaged controller with public controls and disposable profiles.

The profile starts absent. No fixture engine, game-state injection, save reads,
SQLite reads, or gameplay policy is used. The native controller uses its bundled
JVM; a separately labeled offline codec replay uses host javac/java against only
the packaged jar. Exact model-facing bytes and native transport remain retained.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import select
import signal
import shutil
import subprocess
import sys
import time
import uuid
import zlib

import protocol7
sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "desktop-control/client"))
from spdctl_client import decode_wire_response


SUCCESS = {"COMPLETED", "AWAITING_INPUT", "INTERRUPTED"}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class StaleAction(Exception):
    """Definite, receipt-verified rejection; a caller must choose again."""


class ControllerClient:
    def __init__(self, cli, profile, output, environment):
        output.mkdir()
        self.output, self.trace_root = output, output / "transport"
        self.codec_jar = cli.parent.parent / "app/desktop-control-3.3.8-all.jar"
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
            assert self.hello["data"]["cli_version"] == "CLI.7.0.1", self.hello
            assert self.hello["data"]["audit_schema_version"] == 10, self.hello
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
        encoded = protocol7.wire_bytes(intent)
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
        if value.get("v") == 7 and "id" in value and ("st" in value or "err" in value):
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
        assert result.get("v") == 7 and "err" not in result, result
        view = protocol7.response(result, op)
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
        assert response.get("v") == 7 and "err" not in response, response
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
        rows = protocol7.expand_structures(frame)["data"].get("save", [])
    else:
        frame = protocol7.expand_structures(action["initial"])
        rows = frame["data"].get("persistence", {}).get("saves", [])
    assert rows, action
    for receipt in rows:
        assert receipt["success"] is True and re.fullmatch(r"p[0-9a-z]+", receipt["sid"]), receipt
        assert receipt["src_id"] == action["initial"]["id"], (receipt, action)
        assert receipt.get("src_s", receipt.get("s", action["request_scope"])) == action["request_scope"], receipt
    return rows


def validate_trace(client, decimal_boundary=False):
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
        assert request["v"] == response["v"] == 7 and request["id"] == response["id"], (request, response)
        assert request["id"] not in by_id, request
        by_id[request["id"]] = response
        if index:
            prefix, count = request["id"].split(".")
            assert prefix == client.prefix and re.fullmatch(r"[1-9][0-9]*", count), request
            assert int(count) > previous, request
            previous = int(count)
        if request["op"] == "req":
            assert request["id"] != request["rid"] and "get" not in request, request
    if decimal_boundary:
        assert client.prefix + ".9" in by_id and client.prefix + ".10" in by_id, "Missing decimal request ID boundary in raw trace"
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


def public_view_differences(first, second, path="$", limit=20):
    """Report exact typed content differences, including unknown fields and order."""
    differences = []
    def compare(left, right, here):
        if len(differences) >= limit:
            return
        if type(left) is not type(right):
            differences.append(here + " (type)")
        elif isinstance(left, dict):
            for key in sorted(left.keys() | right.keys()):
                if key not in left or key not in right:
                    differences.append(here + "." + key + " (presence)")
                else:
                    compare(left[key], right[key], here + "." + key)
                if len(differences) >= limit:
                    break
        elif isinstance(left, list):
            if len(left) != len(right):
                differences.append(here + " (length)")
            for index, (a, b) in enumerate(zip(left, right)):
                compare(a, b, f"{here}[{index}]")
        elif left != right:
            differences.append(here)
    compare(first, second, path)
    return differences


def normalized_public_frame(frame):
    """Expand one frozen frame and remove only redundant map/entity encodings."""
    original = copy.deepcopy(frame)
    decoded = decode_wire_response(frame)
    assert not decoded.is_error and decoded.is_observation, frame
    assert frame == original and decoded.raw == original, "Decoder changed frozen wire evidence"
    result = copy.deepcopy(decoded.frame)
    data = result["data"]
    assert data.get("cues") == original["data"].get("cues"), "Decoder changed exact rendered visuals"
    assert "node_templates" not in data.get("ui", {}), "Decoder left packed UI rows"
    if "map" in data:
        for encoding in ("types", "rows", "env", "effect_defs"):
            data["map"].pop(encoding, None)
    data.pop("entity_defs", None)
    return result


def replay_packaged_views(client, frozen):
    """Offline component check: host JVM, actual packaged codec, one frozen input.

    This helper does not send game requests or inspect saves/audit databases.
    Exact source/full reconstruction is checked by its caller before trusting any
    play/full result from the reversible test-only alias reconstruction adapter.
    """
    jar = client.codec_jar.resolve()
    assert jar.is_file(), jar
    javac, java = shutil.which("javac"), shutil.which("java")
    assert javac and java, "Offline packaged-codec replay requires host javac and java"
    directory = client.output / ("frozen-view-" + frozen["id"])
    directory.mkdir()
    classes = directory / "adapter-classes"
    classes.mkdir()
    source = Path(__file__).resolve().parent.parent / "codec/PackagedViewReplay.java"
    environment = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(key, None)
    compile_result = subprocess.run([javac, "-cp", str(jar), "-d", str(classes), str(source)],
                                    capture_output=True, text=True, env=environment, timeout=45)
    (directory / "compile.stdout").write_text(compile_result.stdout)
    (directory / "compile.stderr").write_text(compile_result.stderr)
    assert compile_result.returncode == 0, compile_result.stderr
    write_json(directory / "frozen-input.json", frozen)
    result = subprocess.run([java, "-cp", str(classes) + os.pathsep + str(jar), "PackagedViewReplay",
                             str(directory / "frozen-input.json"), str(directory / "replayed-views.json")],
                            capture_output=True, text=True, env=environment, timeout=45)
    (directory / "replay.stdout").write_text(result.stdout)
    (directory / "replay.stderr").write_text(result.stderr)
    assert result.returncode == 0, result.stderr
    runtime = subprocess.run([java, "-version"], capture_output=True, text=True, env=environment, timeout=15)
    assert runtime.returncode == 0, runtime.stderr
    return json.loads((directory / "replayed-views.json").read_text()), {
        "classification": "offline packaged codec component replay; host JVM; no game process",
        "packaged_jar": str(jar), "packaged_jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "adapter_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "host_java": java, "host_javac": javac,
        "host_java_version": (runtime.stderr or runtime.stdout).splitlines()[0], "artifacts": str(directory)}


def lossless_views_check(client):
    """Project exactly one frozen source observation through packaged play/full.

    Successive GUI draws can differ without a decision revision change. This
    check never waits for random animation stability and never removes rendered
    colors, opacities, particle counts, timestamps, source ASTs or diagnostics.
    """
    source = client.unwrap(client.request({"op": "state", "view": "full", "src": True}))
    client.install(source, "state")
    original = copy.deepcopy(source)
    frozen = normalized_public_frame(source)
    frozen_original = copy.deepcopy(frozen)
    replayed, component = replay_packaged_views(client, frozen)
    assert frozen == frozen_original, "Codec adapter changed its frozen input"
    reconstructed = normalized_public_frame(replayed["src"])
    differences = public_view_differences(frozen, reconstructed)
    assert not differences, {"error": "Frozen src/full reconstruction differs", "fields": differences,
                             "source_id": source["id"], "artifacts": component.get("artifacts")}
    full, play = (normalized_public_frame(replayed[view]) for view in ("full", "play"))
    differences = public_view_differences(full, play)
    assert not differences, {"error": "Same frozen packaged play/full observations differ", "fields": differences,
                             "source_id": source["id"], "artifacts": component.get("artifacts")}
    assert source == original, "Codec replay changed the actual source wire response"
    data = play["data"]
    described = sum("desc" in item for item in data["inv"])
    assert any(talent["points"] == 0 for talent in data["hero"]["talents"]), "Play lost unspent talents"
    return {"decoded_public_content_equal": True, "same_frozen_input": True,
            "exact_src_full_reconstruction": True, "source_request_id": source["id"],
            "scope": source["s"], "rev": source["rev"], "nodes": len(data["ui"]["nodes"]),
            "ordered_actions": len(data["acts"]), "inventory_items": len(data["inv"]),
            "items_with_description": described, "talents": len(data["hero"]["talents"]),
            "rendered_visuals_and_timestamps_preserved": True, "component_replay": component,
            "source_bytes": len(protocol7.wire_bytes(source)),
            "play_bytes": len(protocol7.wire_bytes(replayed["play"])),
            "full_bytes": len(protocol7.wire_bytes(replayed["full"]))}


def decimal_request_ids_check(client):
    """Cross 9/10 with read-only observations in this disposable package fixture."""
    expected = [client.prefix + ".9", client.prefix + ".10"]
    for _ in range(10):
        latest = client.wire_frames[-1]["id"]
        prefix, count = latest.split(".")
        assert prefix == client.prefix and re.fullmatch(r"[1-9][0-9]*", count), latest
        if int(count) >= 10:
            # Internal receipt polls may not all be displayed by the controller.
            # validate_trace checks both exact boundary IDs in the original bytes.
            return {"decimal_suffixes": True, "boundary_ids": expected, "observed_through": latest}
        client.state()
    raise AssertionError("Packaged controller did not issue decimal request IDs across 9/10")


def source_view_check(client):
    """Request real source content and decode its own templates without changing it."""
    old_scope, old_revision = client.scope, client.revision
    reply = client.unwrap(client.request({"op": "state", "view": "play", "src": True}))
    original = copy.deepcopy(reply)
    client.install(reply, "state")
    decoded = decode_wire_response(reply)
    assert decoded.is_observation and not decoded.is_error, reply
    assert reply == original, "Decoding mutated the source wire response"
    assert (client.scope, client.revision) == (old_scope, old_revision), "Source query crossed a decision boundary"
    def sources(value):
        if isinstance(value, list):
            return sum(sources(child) for child in value)
        if not isinstance(value, dict):
            return 0
        own = len(value["text_sources"]) if isinstance(value.get("text_sources"), dict) else 0
        return own + sum(sources(child) for key, child in value.items() if key != "text_sources")
    count = sources(decoded.data)
    assert count > 0, "src:true returned no rendered source AST evidence"
    data = decoded.data
    assert "act_templates" not in data and "inv_templates" not in data
    assert "node_templates" not in data.get("ui", {})
    assert all(isinstance(row, dict) for row in data.get("inv", []))
    assert all(isinstance(row, dict) for row in data.get("acts", []))
    assert all(isinstance(row, dict) for row in data.get("ui", {}).get("nodes", []))
    raw = reply["data"]
    return {"src_true_decoded": True, "source_fields": count, "scope": reply["s"], "rev": reply["rev"],
            "source_bytes": len(protocol7.wire_bytes(reply)),
            "templates": {"acts": len(raw.get("act_templates", [])), "inv": len(raw.get("inv_templates", [])),
                          "nodes": len(raw.get("ui", {}).get("node_templates", []))}}


def inventory_round_trip_check(client):
    """Open native inventory, inspect one item, then close without executing item use.

    Toolbar.onClick opens WndBag on compact UI and toggles InventoryPane on large
    UI. WndBag's normal slot click opens WndUseItem; the explicit item command
    opens that same native item menu when the large sidebar is used. No quickbag,
    long click, equip, drink, eat, throw or other resource-changing action is sent.
    """
    before = snapshot(client.state_view)
    def current():
        return decode_wire_response(client.current).data
    def inventory_button():
        data = current()
        controls = {node["id"] for node in data["ui"]["nodes"]
                    if str(node.get("shortcut", "")).casefold() == "inventory"}
        choices = [action for action in data["acts"] if action.get("op") == "click"
                   and action.get("ctl") in controls and "click" in action.get("gestures", ["click"])]
        assert len(choices) == 1, {"inventory_button_matches": choices}
        return choices[0]["ctl"]
    client.action("click", ctl=inventory_button())
    opened = current()
    assert not opened["ui"].get("item_prompt"), "Unexpected item selector; do not select an item"
    modal_bag = bool(opened["ui"].get("modal"))
    views = lossless_views_check(client)
    source = source_view_check(client)
    if modal_bag:
        data = current()
        locators = {item["loc"] for item in data["inv"] if item.get("available", True)}
        choices = [node for node in data["ui"]["nodes"] if node.get("loc") in locators
                   and any(op.get("op") == "click" and "click" in op.get("gestures", ["click"])
                           for op in node.get("ops", []) or [])]
        assert choices, "Native inventory has no bound inspectable item control"
        selected = choices[0]["loc"]
        client.action("click", ctl=choices[0]["id"])
    else:
        # Restore the large sidebar's original toggle state, then use its exact
        # advertised inventory locator to inspect (never activate a quickslot).
        client.action("click", ctl=inventory_button())
        data = current()
        assert any(action.get("op") == "item" for action in data["acts"]), data["acts"]
        item = next(item for item in data["inv"] if item.get("available", True))
        selected = item["loc"]
        client.action("item", loc=selected)
    assert current()["ui"].get("modal"), "Selecting an item did not open its original item menu"
    item_views = lossless_views_check(client)
    item_source = source_view_check(client)
    closed = 0
    for _ in range(3):
        data = current()
        if not data["ui"].get("modal") and not data["ui"].get("item_prompt"):
            break
        assert any(action.get("op") == "back" for action in data["acts"]), data["acts"]
        client.action("back")
        closed += 1
    assert not current()["ui"].get("modal"), "Inventory/item windows did not close"
    assert snapshot(client.state_view) == before, "Inspecting inventory changed hero resources or items"
    return {"opened_inventory": True, "modal_bag": modal_bag, "selected_loc": selected,
            "item_menu_opened": True, "close_actions": closed, "hero_inventory_unchanged": True,
            "inventory_views": views, "inventory_source": source,
            "item_views": item_views, "item_source": item_source}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    output = root / "desktop-control/build/fixtures" / ("cli7-controller-" + uuid.uuid4().hex)
    output.mkdir(parents=True)
    profile = output / "fresh 中文 profile"
    assert not profile.exists()
    cli = args.bundle.resolve() / "Contents/MacOS/spdctl"
    assert cli.is_file(), cli
    environment = dict(os.environ)
    environment.pop("JAVA_HOME", None)
    environment["PATH"] = "/usr/bin:/bin"
    version = subprocess.check_output([str(cli), "--version"], env=environment, text=True).strip()
    assert version == "CLI.7.0.1 (protocol 7, game 3.3.8)", version
    clients, report = [], {"result": "running", "counts_as_win": False, "bundle": str(args.bundle.resolve()),
                           "artifacts": str(output), "isolated_profile": str(profile), "version": version,
                           "fresh_defaults": True, "personal_profile_or_audit_reads": False}
    try:
        first = ControllerClient(cli, profile, output / "first", environment)
        clients.append(first)
        first.state()
        initial, first_scenes = reach_warrior(first)
        report["lossless_views"] = lossless_views_check(first)
        report["decimal_request_ids"] = decimal_request_ids_check(first)
        report["source_view"] = source_view_check(first)
        report["inventory_round_trip"] = inventory_round_trip_check(first)
        before = snapshot(initial)
        assert before["hero"]["depth"] == 1 and before["hero"]["level"] == 1, before
        saved = native_operation(first, "save")
        first_receipts = save_receipts(saved)
        old_revision = first.revision
        run_scope = first.scope
        quit_first = native_operation(first, "quit")
        first_quit_receipts = save_receipts(quit_first)
        first.finish_exit()
        first_trace = validate_trace(first, decimal_boundary=True)
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
