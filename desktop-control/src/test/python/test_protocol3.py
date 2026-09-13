"""Offline compact-wire contract checks. No game, profile or runtime audit reads."""
import copy
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import Mock

from machine_smoke import Client
import protocol3


class Protocol3Test(unittest.TestCase):
    def test_actions_are_flat_and_directions_use_wire_enum(self):
        wire = protocol3.request("action.execute", {"action": "move.step", "direction": "northeast"}, "a", "run:1", "r7")
        self.assertEqual({"v": 3, "id": "a", "s": "run:1", "rev": "r7", "op": "move", "dir": "NE"}, wire)
        self.assertNotIn("args", wire)
        self.assertNotIn("action", wire)
        self.assertEqual("click", protocol3.request("action.execute", {"action": "ui.activate", "control": "n1"})["op"])

    def test_direct_short_action_preserves_revision_and_updates_live_context(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "run:1", "r7", 0, "direct"
        client.buffer = protocol3.wire_bytes({"v": 3, "id": "a", "s": "run:1", "rev": "r8", "st": "completed",
                                            "data": {"scene": "game", "phase": "player_ready"}})
        response = client.request("move", {"dir": "N"}, request_id="a", version="r7")
        self.assertEqual({"v": 3, "id": "a", "s": "run:1", "rev": "r7", "op": "move", "dir": "N"},
                         json.loads(client.process.stdin.getvalue()))
        self.assertEqual("r8", client.version)
        self.assertEqual("r8", response["result"]["state_version"])
        self.assertFalse(protocol3.is_live_operation("req"))
        self.assertFalse(protocol3.is_live_operation("events"))

    def test_info_version_comes_from_envelope_without_inventing_data_field(self):
        wire = {"v": 3, "id": "hello", "s": "menu:1", "rev": "r1", "st": "completed",
                "data": {"cli_version": "CLI.3.0.0", "audit_schema_version": 6}}
        response = protocol3.response(wire, "info")
        self.assertEqual(3, response["protocol_version"])
        self.assertNotIn("protocol_version", response["result"])

    def test_uncertain_error_keeps_confirmed_saves_without_advancing_live_context(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "run:1", "r7", 0, "uncertain"
        receipt = {"success": True, "s": "run:1", "receipt_id": "saved-before-failure"}
        wire = {"v": 3, "id": "a", "s": "run:1", "rev": "r8", "err": "EXECUTION_UNKNOWN",
                "data": {"persistence": {"saves_during_request": [receipt]}}}
        client.buffer = protocol3.wire_bytes(wire)
        response = client.request("save", request_id="a")
        self.assertFalse(response["ok"])
        self.assertEqual("EXECUTION_UNKNOWN", response["error"]["code"])
        self.assertEqual([{"success": True, "scope_id": "run:1", "receipt_id": "saved-before-failure"}],
                         response["result"]["persistence"]["saves_during_request"])
        self.assertEqual("r7", client.version)
        self.assertEqual(wire, client.last_wire_response)

    def test_source_and_request_details_are_opt_in(self):
        self.assertEqual({"v": 3, "id": "q", "op": "state"}, protocol3.request("state.get", request_id="q"))
        self.assertTrue(protocol3.request("state.get", {"src": True})["src"])
        compact = protocol3.request("request.get", {"target_id": "a"})
        self.assertEqual("a", compact["rid"])
        self.assertNotIn("get", compact)
        detail = protocol3.request("request.get", {"target_id": "a", "get": ["reply"]})
        self.assertEqual(["reply"], detail["get"])

    def test_map_and_node_projection_reconstructs_only_public_values(self):
        wire = {"v": 3, "id": "q", "s": "run:1", "rev": "r7", "st": "completed", "data": {
            "scene": "game", "phase": "player_ready", "hero": {"hp": 20}, "acts": [{"op": "wait"}],
            "map": {"w": 4, "h": 4, "types": [{"terrain": 4, "name": "wall"}], "cols": ["cell", "tile", "vis"],
                    "cells": [[7, 0, "v"], [8, 0, "s"]], "env": {"7": [{"name": "fire"}]}},
            "ui": {"nodes": [{"id": "n1", "text": "Drink", "gestures": ["click"], "ops": [{"op": "click"}]}]}}}
        original = copy.deepcopy(wire)
        decoded = protocol3.response(wire, "state.get")["result"]
        self.assertEqual(("run:1", "r7"), (decoded["scope_id"], decoded["state_version"]))
        cells = decoded["observation"]["map"]["cells"]
        self.assertEqual([7, 8], [row["cell"] for row in cells])
        self.assertEqual((3, 1, "visible"), (cells[0]["x"], cells[0]["y"], cells[0]["visibility"]))
        self.assertEqual("visited", cells[1]["visibility"])
        self.assertEqual([{"name": "fire"}], cells[0]["environment"])
        self.assertEqual({"action": "ui.activate", "control": "n1", "label": "Drink", "gestures": ["click"]}, decoded["actions"][1])
        self.assertNotIn("text_sources", decoded["actions"][1])
        self.assertEqual(original, wire)

    def test_activity_and_disclosed_run_outcome_keep_canonical_observation_locations(self):
        activity = {"kind": "travel", "rid": "a"}
        wire = {"v": 3, "id": "a", "s": "run:1", "rev": "activity:r1", "st": "in_progress",
                "data": {"scene": "game", "phase": "continuous_activity", "continuous_activity": activity}}
        state = protocol3.response(wire, "cell")["result"]
        self.assertEqual({"kind": "travel", "target_id": "a"}, state["observation"]["continuous_activity"])
        self.assertNotIn("run_outcome", state)
        wire["data"]["run_outcome"] = {"s": "run:1", "result": "lost"}
        state = protocol3.response(wire, "state")["result"]
        self.assertEqual({"scope_id": "run:1", "result": "lost"}, state["run_outcome"])
        self.assertEqual(state["run_outcome"], state["observation"]["run_outcome"])

    def test_sources_and_partial_diagnostics_are_not_fabricated(self):
        source = {"kind": "resource", "key": "items.test", "args": []}
        wire = {"v": 3, "data": {"scene": "game", "ui": {"nodes": [{"id": "n", "text": "A",
                "text_sources": {"text": source}, "pres": {"st": "partial", "diag": [{"field": "text", "code": "clipped_text"}]},
                "ops": [{"op": "click"}]}]}}}
        action = protocol3.response(wire)["result"]["actions"][0]
        self.assertEqual(source, action["text_sources"]["label"])
        self.assertEqual("clipped_text", action["text_diagnostics"]["label"])
        with self.assertRaises(AssertionError):
            protocol3.response({"protocol_version": 2, "ok": True})

    def test_node_label_precedes_visible_numeric_item_text(self):
        wire = {"v": 3, "data": {"scene": "game", "ui": {"nodes": [
            {"id": "n", "label": "Waterskin", "text": "1/20", "ops": [{"op": "click"}],
             "text_sources": {"label": {"kind": "resource", "key": "waterskin.name"}}}]}}}
        action = protocol3.response(wire)["result"]["actions"][0]
        self.assertEqual("Waterskin", action["label"])
        self.assertEqual("waterskin.name", action["text_sources"]["label"]["key"])

    def test_request_summary_does_not_invent_reply_and_details_decode_exact_reply(self):
        summary = {"v": 3, "id": "lookup", "data": {"id": "a", "op": "wait", "st": "COMPLETED", "has": ["reply", "before"]}}
        self.assertNotIn("response", protocol3.response(summary, "request.get")["result"])
        reply = {"v": 3, "id": "a", "s": "run:1", "rev": "r8", "st": "completed", "data": {"scene": "game", "phase": "player_ready"}}
        summary["data"]["reply"] = reply
        self.assertEqual(protocol3.response(reply), protocol3.response(summary, "request.get")["result"]["response"])

    def test_history_metadata_keeps_actual_operation_instead_of_becoming_an_action(self):
        wire = {"v": 3, "id": "h", "data": {"items": [{"sequence": 2, "id": "a", "op": "move", "status": "COMPLETED"}],
                "next": None, "end": True, "until": 2}}
        row = protocol3.response(wire, "history.list")["result"][0]
        self.assertEqual("move", row["op"])
        self.assertNotIn("action", row)

    def test_pending_action_polls_summary_then_fetches_original_reply(self):
        client = object.__new__(Client)
        final = {"ok": True, "status": "completed", "result": {"scope_id": "run:1", "state_version": "r8"}}
        client.scope, client.version = "run:1", "r7"
        client.request = Mock(side_effect=[
            {"ok": True, "id": "a", "scope_id": "run:1", "status": "in_progress"},
            {"ok": True, "result": {"status": "COMPLETED"}},
            {"ok": True, "result": {"status": "COMPLETED", "response": final}}])
        self.assertEqual(final, client.act("wait"))
        calls = client.request.call_args_list
        self.assertEqual(["action.execute", "request.get", "request.get"], [call.args[0] for call in calls])
        self.assertNotIn("get", calls[1].args[1])
        self.assertEqual(["reply"], calls[2].args[1]["get"])
        self.assertEqual("r8", client.version)

    def test_limit_one_self_auditing_history_stops_at_initial_bound(self):
        class GrowingHistory:
            def __init__(self):
                self.count = 2
                self.requests = []

            def request(self, op, args, scope=None):
                self.requests.append(dict(args))
                self.count += 1  # The history query itself creates another audit row.
                upper = args.get("until", self.count)
                sequence = args["after"] + 1
                more = sequence < upper
                wire = {"v": 3, "id": "page", "data": {"items": [{"sequence": sequence}],
                        "until": upper, "next": sequence if more else None, "end": not more}}
                return protocol3.response(wire, op)

        client = GrowingHistory()
        rows = [row for batch in protocol3.pages(client, "history.list", limit=1) for row in batch]
        self.assertEqual([1, 2, 3], [row["sequence"] for row in rows])
        self.assertEqual(3, len(client.requests))
        self.assertNotIn("until", client.requests[0])
        self.assertEqual([3, 3], [request["until"] for request in client.requests[1:]])
        first_count = len(client.requests)
        rows = [row for batch in protocol3.pages(client, "history.list", after=3, limit=1) for row in batch]
        self.assertEqual([4, 5, 6], [row["sequence"] for row in rows])
        self.assertNotIn("until", client.requests[first_count], "A new polling cycle must capture a fresh upper bound")

    def test_large_single_request_and_response_are_collected_without_truncation(self):
        engine = 'import json,sys; r=json.loads(sys.stdin.buffer.readline()); print(json.dumps({"v":3,"id":r["id"],"st":"completed","data":{"echo":r["text"]}},ensure_ascii=False),flush=True)'
        process = subprocess.Popen([sys.executable, "-c", engine], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        client = object.__new__(Client)
        client.process, client.profile = process, Path("unused-offline-wire-fixture")
        client.scope, client.version, client.counter, client.prefix, client.buffer = None, None, 0, "long", b""
        payload = "x" * (9 * 1024 * 1024) + "中文🐈"
        try:
            result = client.request("text", {"text": payload})
            self.assertEqual(payload, result["result"]["echo"])
            self.assertGreater(len(client.last_send_bytes), 9 * 1024 * 1024)
            self.assertGreater(len(client.last_recv_bytes), 9 * 1024 * 1024)
            self.assertEqual(3, json.loads(client.last_send_bytes)["v"])
            self.assertEqual(payload, json.loads(client.last_recv_bytes)["data"]["echo"])
            process.wait(timeout=5)
            self.assertEqual(0, process.returncode)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()


if __name__ == "__main__":
    unittest.main()
