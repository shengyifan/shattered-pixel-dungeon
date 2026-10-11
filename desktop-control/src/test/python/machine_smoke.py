#!/usr/bin/env python3
"""Real GUI runtime, pipe-only smoke test. Uses a new, isolated profile each time."""
import argparse
import json
import os
from pathlib import Path
import select
import sqlite3
import subprocess
import time
import uuid
import protocol9
from client_result import settle_action
from fixture_support import artifact_directory


class Client:
    def __init__(self, command, profile):
        self.profile = profile
        self.artifact_dir = artifact_directory(profile)
        self.stderr = (self.artifact_dir / "native-stderr.log").open("ab")
        self.process = subprocess.Popen(command + ["run", "--machine", "--data-dir", str(profile)],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr)
        self.buffer = b""
        self.scope = None
        self.version = None
        self.counter = 0
        self.prefix = uuid.uuid4().hex[:12]

    def request(self, op, args=None, request_id=None, scope=None, version=None):
        self.counter += 1
        req = protocol9.request(op, args, request_id or f"{self.prefix}.{self.counter:d}",
                                self.scope if scope is None else scope, self.version if version is None else version)
        self.last_wire_request = req
        self.last_send_bytes = protocol9.wire_bytes(req)
        assert self.process.stdin.write(self.last_send_bytes) == len(self.last_send_bytes), "Incomplete request write"
        self.process.stdin.flush()
        deadline = time.monotonic() + 45
        self.buffer = bytearray(self.buffer)
        while b"\n" not in self.buffer:
            if time.monotonic() > deadline:
                raise TimeoutError(f"No response for {op}; profile={self.profile}")
            ready, _, _ = select.select([self.process.stdout], [], [], 1)
            if ready:
                chunk = os.read(self.process.stdout.fileno(), 65536)
                if not chunk:
                    raise RuntimeError(f"Process ended {self.process.poll()}; profile={self.profile}")
                self.buffer.extend(chunk)
        line, self.buffer = self.buffer.split(b"\n", 1)
        self.last_recv_bytes = bytes(line) + b"\n"
        self.last_wire_response = json.loads(line)
        assert self.last_wire_response.get("id") == req["id"], (req, self.last_wire_response)
        result = protocol9.response(self.last_wire_response, op)
        data = result.get('data', {})
        if op == 'info' and ("err" not in result) and isinstance(data, dict) and isinstance(data.get("request_prefix"), str):
            self.prefix = data["request_prefix"]
        if isinstance(data, dict) and ("err" not in result) and protocol9.is_live_operation(op):
            self.scope = result.get('s', self.scope)
            self.version = result.get('rev')
        return result

    def request_action(self, intent, **kwargs):
        """Send one advertised current flat intent with an explicit fresh identity."""
        fields = dict(intent)
        op = fields.pop("op")
        return self.request(op, fields, **kwargs)

    def act(self, action, **args):
        result = self.request_action({'op': action, **args})
        return settle_action(self, result, action).require_success()

    def state(self, source=False, view=None):
        args = {"src": True} if source else {}
        if view is not None:
            args["view"] = view
        result = self.request('state', args or None)
        assert ("err" not in result), result
        return protocol9.frame_data(result)

    def finish(self):
        try:
            if self.process.poll() is None:
                for _ in range(5):
                    self.state()
                    response = self.request_action({'op': 'quit'})
                    if ("err" not in response):
                        settled = settle_action(self, response, 'quit').require_success()
                        assert settled.status == "completed", settled
                        break
                    if response.get("err") != "STALE_STATE":
                        raise AssertionError(response)
                else:
                    raise AssertionError("Quit did not obtain a current revision")
                self.process.wait(timeout=20)
        finally:
            if self.process.poll() is None:
                self.process.terminate()
                self.process.wait(timeout=10)
            self.stderr.close()


def reach_game(client, resume=False):
    """Navigate only controls that are explicitly described by the public protocol."""
    state = client.state()
    for _ in range(25):
        if state.get("scene") == "game":
            return state
        candidates = [a for a in state['acts'] if a.get('op') == 'click' and a.get("label")]
        chosen = None
        desired = ["continue", "enter the dungeon", "play"]
        desired += ["warrior", "new game", "start"] if resume else ["new game", "start", "warrior"]
        for text in desired:
            def matches(action):
                label = action["label"]
                if resume and state.get("ui", {}).get("scene") == "StartScene":
                    label = label.split("\n")[0]
                return text == label.lower()
            matching = [action for action in candidates if matches(action)]
            assert len(matching) <= 1, {"ambiguous_start_control": matching}
            chosen = matching[0] if matching else None
            if chosen:
                break
        if not chosen:
            raise AssertionError({"no_start_control": candidates, "scene": state.get("scene")})
        response = client.request_action({'op': 'click', 'ctl': chosen['ctl'], 'g': "click"})
        if response.get("err") == "STALE_STATE":
            state = client.state()
            continue  # Choose again from the new observation with a fresh request ID.
        settled = settle_action(client, response, "click").require_success()
        state = settled.observation
        assert state is not None, settled
    raise AssertionError("Game did not start within the expected menu flow")


def finish_tutorial(client):
    visits = {}
    book = None
    for _ in range(60):
        state = client.state()
        if any(a['op'] == 'item' for a in state['acts']):
            return
        observation = state
        book = next((e for e in observation['entities']
                     if any(word in e.get("item", {}).get("name", "").lower()
                            for word in ["tome of dungeon mastery", "guidebook"])), None)
        if book:
            break
        # High grass can hide the guide in a legitimate fresh dungeon. Explore observed
        # adjacent terrain instead of fixing the seed or reading a hidden location.
        position = observation["hero"]["cell"]
        width = observation["map"]['w']
        visits[position] = visits.get(position, 0) + 1
        cells = {c["cell"]: c for c in observation["map"]["cells"]}
        adjacent = [c for c in cells.values() if c["cell"] != position
                    and max(abs(c["x"] - position % width), abs(c["y"] - position // width)) == 1
                    and not any(word in c["name"].lower() for word in ["wall", "chasm", "exit", "entrance"])
                    and not c.get("environment")]
        assert adjacent, "No publicly observed tutorial exploration step"
        target = min(adjacent, key=lambda c: (visits.get(c["cell"], 0), c["cell"]))
        result = client.request_action({'op': 'cell', "cell": target["cell"], "mode": "act"})
        if result.get("err") == "STALE_STATE":
            continue
        assert ("err" not in result), result
    assert book, "The tutorial guide must be selected from an actual public observation"
    client.act('cell', cell=book["cell"], mode="act")
    state = client.state()
    journal = next(c for c in state["ui"]['nodes']
                   if c.get('shortcut')=="journal" and c.get("enabled"))
    client.act('click', ctl=journal["id"])
    client.act('back')
    for _ in range(50):
        state = client.state()
        if any(a['op'] == 'item' for a in state['acts']):
            return
        time.sleep(0.05)
    raise AssertionError("Tutorial UI did not become available")


def ui_intent(client, choose):
    """Re-plan a free UI choice after a legitimate concurrent presentation change."""
    for _ in range(8):
        state = client.state()
        args = choose(state)
        response = client.request_action(args)
        if response.get("err") == "STALE_STATE":
            continue
        settled = settle_action(client, response, args["op"]).require_success()
        assert settled.observation is not None, settled
        return state, settled.observation
    raise AssertionError("UI choice never reached a stable current context")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--launcher")
    parser.add_argument("--menu-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    profile = root / "desktop-control" / "build" / "smoke" / str(uuid.uuid4())
    profile.mkdir(parents=True)
    if args.launcher:
        command = [args.launcher]
    else:
        classpath = (root / "desktop-control" / "build" / "runtime-classpath.txt").read_text()
        command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
                   "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
                   "com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"]
    client = Client(command, profile)
    try:
        hello = client.request('info', request_id="first")
        assert ("err" not in hello), hello
        menu_scope = hello['data']["menu_scope_id"]
        repeated = client.request('info', request_id="first", scope=menu_scope)
        assert repeated["err"] == "DUPLICATE_REQUEST_ID", repeated
        state = client.state()
        print(json.dumps({"profile": str(profile), "phase": state["phase"], "ui": state.get("ui"), 'acts': state['acts']}, ensure_ascii=False), flush=True)
        query_id = "query-once"
        assert "err" not in client.request('state', request_id=query_id)
        assert client.request('state', request_id=query_id)["err"] == "DUPLICATE_REQUEST_ID"
        old = client.request('req', {'rid': query_id})
        assert ("err" not in old), old
        if not args.menu_only:
            state = reach_game(client)
            finish_tutorial(client)
            state = client.state()
            assert state.get("scene") == "game", state
            before, opened = ui_intent(client, lambda s: {'op': 'item', 'loc': next(
                i['loc'] for i in s['inv'] if i['loc'].startswith("backpack."))})
            before_items = [(i['loc'], i["name"], i['qty']) for i in before['inv']]
            assert opened["phase"] == "awaiting_input", opened
            _, targeting = ui_intent(client, lambda s: {'op': 'click', 'ctl': next(
                a['ctl'] for a in s['acts'] if a.get('op') == 'click'
                and a.get("label", "").lower() in {"throw"})})
            assert targeting["phase"] == "awaiting_input", targeting
            assert any(a['op'] == 'untarget' for a in targeting['acts']), targeting
            ui_intent(client, lambda s: {'op': 'untarget'} if any(
                a['op'] == 'untarget' for a in s['acts']) else {'op': 'back'})
            after = client.state()
            assert before_items == [(i['loc'], i["name"], i['qty']) for i in after['inv']]
            before_version = client.version
            first = client.request_action({'op': "wait"}, request_id="wait-once")
            assert ("err" not in first), first
            duplicate = client.request_action({'op': "wait"}, request_id="wait-once", version=before_version)
            assert duplicate["err"] == "DUPLICATE_REQUEST_ID", duplicate
            stale = client.request_action({'op': "wait"}, version=before_version)
            assert stale["err"] == "STALE_STATE", stale
            client.state()
            run_scope = client.scope
        client.finish()
        if not args.menu_only:
            client = Client(command, profile)
            resumed_hello = client.request('info')
            assert resumed_hello['data']["menu_scope_id"] == menu_scope
            archived = client.request('req', {'rid': "wait-once"}, scope=run_scope)
            assert ("err" not in archived), archived
            restored = reach_game(client, resume=True)
            assert client.scope == run_scope, (client.scope, run_scope)
            assert client.version != before_version
            duplicate = client.request_action({'op': "wait"}, request_id="wait-once", version=before_version)
            assert duplicate["err"] == "DUPLICATE_REQUEST_ID", duplicate
            assert restored["hero"]["class"] == "warrior"
            client.finish()
        with sqlite3.connect(f"file:{profile / 'audit' / 'public.sqlite3'}?mode=ro", uri=True) as public:
            assert public.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            assert public.execute("SELECT COUNT(*) FROM exchanges WHERE duplicate=1").fetchone()[0] >= 2
            assert public.execute("SELECT COUNT(*) FROM exchanges WHERE response_json IS NULL").fetchone()[0] == 0
        with sqlite3.connect(f"file:{profile / 'audit' / 'internal.sqlite3'}?mode=ro", uri=True) as internal:
            assert internal.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            assert internal.execute("SELECT COUNT(*) FROM snapshots").fetchone()[0] > 0
        print(json.dumps({"result": "passed", "profile": str(profile)}, ensure_ascii=False))
    finally:
        if client.process.poll() is None:
            client.finish()


if __name__ == "__main__":
    main()
