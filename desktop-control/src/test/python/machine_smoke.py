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
import protocol8
from client_result import settle_action


class Client:
    def __init__(self, command, profile):
        self.profile = profile
        self.stderr = (profile / "native-stderr.log").open("ab")
        self.process = subprocess.Popen(command + ["run", "--machine", "--data-dir", str(profile)],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr)
        self.buffer = b""
        self.scope = None
        self.version = None
        self.counter = 0
        self.prefix = uuid.uuid4().hex[:12]

    def request(self, op, args=None, request_id=None, scope=None, version=None):
        self.counter += 1
        req = protocol8.request(op, args, request_id or f"{self.prefix}-{self.counter:d}",
                                scope or self.scope, version or self.version)
        self.last_wire_request = req
        self.last_send_bytes = protocol8.wire_bytes(req)
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
        result = protocol8.response(self.last_wire_response, op)
        data = result.get("result", {})
        if op == "protocol.info" and result.get("ok") and isinstance(data, dict) and isinstance(data.get("request_prefix"), str):
            self.prefix = data["request_prefix"]
        if isinstance(data, dict) and result.get("ok") and protocol8.is_live_operation(op):
            self.scope = data.get("scope_id", self.scope)
            self.version = data.get("state_version") or self.version
        return result

    def act(self, action, **args):
        result = self.request("action.execute", {"action": action, **args})
        return settle_action(self, result, action).require_success()

    def state(self, source=False, view=None):
        args = {"src": True} if source else {}
        if view is not None:
            args["view"] = view
        result = self.request("state.get", args or None)
        assert result["ok"], result
        return result["result"]

    def finish(self):
        try:
            if self.process.poll() is None:
                for _ in range(5):
                    self.state()
                    response = self.request("action.execute", {"action": "app.quit"})
                    if response.get("ok"):
                        break
                    if response.get("error", {}).get("code") != "STALE_STATE":
                        raise AssertionError(response)
                self.process.wait(timeout=20)
        finally:
            if self.process.poll() is None:
                self.process.terminate()
                self.process.wait(timeout=10)
            self.stderr.close()


def reach_game(client, resume=False):
    """Navigate only controls that are explicitly described by the public protocol."""
    for _ in range(25):
        state = client.state()
        if state["observation"].get("scene") == "game":
            return state
        candidates = [a for a in state["actions"] if a.get("action") == "ui.activate" and a.get("label")]
        chosen = None
        desired = ["继续", "continue", "进入地牢", "enter", "开始游戏", "play"]
        desired += ["战士", "warrior", "new game", "新游戏", "开始", "start"] if resume else ["new game", "新游戏", "开始", "start", "战士", "warrior"]
        for text in desired:
            chosen = next((a for a in candidates if text in a["label"].lower()), None)
            if chosen:
                break
        if not chosen:
            raise AssertionError({"no_start_control": candidates, "scene": state["observation"].get("scene")})
        response = client.request("action.execute", {"action": "ui.activate", "control": chosen["control"], "gesture": "click"})
        if response.get("error", {}).get("code") == "STALE_STATE":
            continue  # Get a fresh observation and choose again; never reuse the old request ID.
        assert response.get("ok"), response
    raise AssertionError("Game did not start within the expected menu flow")


def finish_tutorial(client):
    visits = {}
    book = None
    for _ in range(60):
        state = client.state()
        if any(a["action"] == "inventory.open" for a in state["actions"]):
            return
        observation = state["observation"]
        book = next((e for e in observation["visible_entities"]
                     if any(word in e.get("item", {}).get("name", "").lower()
                            for word in ["tome of dungeon mastery", "guidebook", "指南", "地牢宝典"])), None)
        if book:
            break
        # High grass can hide the guide in a legitimate fresh dungeon. Explore observed
        # adjacent terrain instead of fixing the seed or reading a hidden location.
        position = observation["hero"]["cell"]
        width = observation["map"]["width"]
        visits[position] = visits.get(position, 0) + 1
        cells = {c["cell"]: c for c in observation["map"]["cells"]}
        adjacent = [c for c in cells.values() if c["cell"] != position
                    and max(abs(c["x"] - position % width), abs(c["y"] - position // width)) == 1
                    and not any(word in c["name"].lower() for word in ["wall", "chasm", "exit", "entrance", "墙", "深渊", "楼梯"])
                    and not c.get("environment")]
        assert adjacent, "No publicly observed tutorial exploration step"
        target = min(adjacent, key=lambda c: (visits.get(c["cell"], 0), c["cell"]))
        result = client.request("action.execute", {"action": "cell.select", "cell": target["cell"], "mode": "act"})
        if result.get("error", {}).get("code") == "STALE_STATE":
            continue
        assert result.get("ok"), result
    assert book, "The tutorial guide must be selected from an actual public observation"
    client.act("cell.select", cell=book["cell"], mode="act")
    state = client.state()
    journal = next(c for c in state["observation"]["ui"]["controls"]
                   if c.get("shortcut_action")=="journal" and c.get("enabled"))
    client.act("ui.activate", control=journal["id"])
    client.act("ui.back")
    for _ in range(50):
        state = client.state()
        if any(a["action"] == "inventory.open" for a in state["actions"]):
            return
        time.sleep(0.05)
    raise AssertionError("Tutorial UI did not become available")


def ui_intent(client, choose):
    """Re-plan a free UI choice after a legitimate concurrent presentation change."""
    for _ in range(8):
        state = client.state()
        args = choose(state)
        response = client.request("action.execute", args)
        if response.get("error", {}).get("code") == "STALE_STATE":
            continue
        assert response.get("ok"), response
        return state, response["result"]
    raise AssertionError("UI choice never reached a stable current context")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--launcher")
    parser.add_argument("--menu-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    profile = root / "desktop-control" / "build" / "smoke" / str(uuid.uuid4())
    profile.mkdir(parents=True)
    from test_ui import configure_test_ui
    configure_test_ui(profile)
    if args.launcher:
        command = [args.launcher]
    else:
        classpath = (root / "desktop-control" / "build" / "runtime-classpath.txt").read_text()
        command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
                   "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
                   "com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"]
    client = Client(command, profile)
    try:
        hello = client.request("protocol.info", request_id="first")
        assert hello["ok"], hello
        menu_scope = hello["result"]["menu_scope_id"]
        repeated = client.request("protocol.info", request_id="first", scope=menu_scope)
        assert repeated["error"]["code"] == "DUPLICATE_REQUEST_ID", repeated
        state = client.state()
        print(json.dumps({"profile": str(profile), "phase": state["phase"], "ui": state["observation"].get("ui"), "actions": state["actions"]}, ensure_ascii=False), flush=True)
        query_id = "query-once"
        assert client.request("state.get", request_id=query_id)["ok"]
        assert client.request("state.get", request_id=query_id)["error"]["code"] == "DUPLICATE_REQUEST_ID"
        old = client.request("request.get", {"target_id": query_id})
        assert old["ok"], old
        if not args.menu_only:
            state = reach_game(client)
            finish_tutorial(client)
            state = client.state()
            assert state["observation"].get("scene") == "game", state
            before, opened = ui_intent(client, lambda s: {"action": "inventory.open", "locator": next(
                i["locator"] for i in s["observation"]["inventory"] if i["locator"].startswith("backpack."))})
            before_items = [(i["locator"], i["name"], i["quantity"]) for i in before["observation"]["inventory"]]
            assert opened["phase"] == "awaiting_input", opened
            _, targeting = ui_intent(client, lambda s: {"action": "ui.activate", "control": next(
                a["control"] for a in s["actions"] if a.get("action") == "ui.activate"
                and a.get("label", "").lower() in {"throw", "投掷", "扔出"})})
            assert targeting["phase"] == "awaiting_input", targeting
            assert any(a["action"] == "cell.cancel" for a in targeting["actions"]), targeting
            ui_intent(client, lambda s: {"action": "cell.cancel"} if any(
                a["action"] == "cell.cancel" for a in s["actions"]) else {"action": "ui.back"})
            after = client.state()
            assert before_items == [(i["locator"], i["name"], i["quantity"]) for i in after["observation"]["inventory"]]
            before_version = client.version
            first = client.request("action.execute", {"action": "wait"}, request_id="wait-once")
            assert first["ok"], first
            duplicate = client.request("action.execute", {"action": "wait"}, request_id="wait-once", version=before_version)
            assert duplicate["error"]["code"] == "DUPLICATE_REQUEST_ID", duplicate
            stale = client.request("action.execute", {"action": "wait"}, version=before_version)
            assert stale["error"]["code"] == "STALE_STATE", stale
            client.state()
            run_scope = client.scope
        client.finish()
        if not args.menu_only:
            client = Client(command, profile)
            resumed_hello = client.request("protocol.info")
            assert resumed_hello["result"]["menu_scope_id"] == menu_scope
            archived = client.request("request.get", {"target_id": "wait-once"}, scope=run_scope)
            assert archived["ok"], archived
            restored = reach_game(client, resume=True)
            assert client.scope == run_scope, (client.scope, run_scope)
            assert client.version != before_version
            duplicate = client.request("action.execute", {"action": "wait"}, request_id="wait-once", version=before_version)
            assert duplicate["error"]["code"] == "DUPLICATE_REQUEST_ID", duplicate
            assert restored["observation"]["hero"]["class"] == "warrior"
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
