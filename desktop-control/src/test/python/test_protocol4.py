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
import protocol4


class Protocol4Test(unittest.TestCase):
    def test_actions_are_flat_and_directions_use_wire_enum(self):
        wire = protocol4.request("action.execute", {"action": "move.step", "direction": "northeast"}, "a", "run:1", "r7")
        self.assertEqual({"v": 4, "id": "a", "s": "run:1", "rev": "r7", "op": "move", "dir": "NE"}, wire)
        self.assertNotIn("args", wire)
        self.assertNotIn("action", wire)
        self.assertEqual("click", protocol4.request("action.execute", {"action": "ui.activate", "control": "n1"})["op"])

    def test_direct_short_action_preserves_revision_and_updates_live_context(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "run:1", "r7", 0, "direct"
        client.buffer = protocol4.wire_bytes({"v": 4, "id": "a", "s": "run:1", "rev": "r8", "st": "completed",
                                            "data": {"scene": "game", "phase": "player_ready"}})
        response = client.request("move", {"dir": "N"}, request_id="a", version="r7")
        self.assertEqual({"v": 4, "id": "a", "s": "run:1", "rev": "r7", "op": "move", "dir": "N"},
                         json.loads(client.process.stdin.getvalue()))
        self.assertEqual("r8", client.version)
        self.assertEqual("r8", response["result"]["state_version"])
        self.assertFalse(protocol4.is_live_operation("req"))
        self.assertFalse(protocol4.is_live_operation("events"))

    def test_info_version_comes_from_envelope_without_inventing_data_field(self):
        wire = {"v": 4, "id": "hello", "s": "menu:1", "rev": "r1", "st": "completed",
                "data": {"cli_version": "CLI.4.0.0", "audit_schema_version": 7}}
        response = protocol4.response(wire, "info")
        self.assertEqual(4, response["protocol_version"])
        self.assertNotIn("protocol_version", response["result"])

    def test_uncertain_error_keeps_confirmed_saves_without_advancing_live_context(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "run:1", "r7", 0, "uncertain"
        receipt = {"success": True, "s": "run:1", "receipt_id": "saved-before-failure"}
        wire = {"v": 4, "id": "a", "s": "run:1", "rev": "r8", "err": "EXECUTION_UNKNOWN",
                "data": {"persistence": {"saves_during_request": [receipt]}}}
        client.buffer = protocol4.wire_bytes(wire)
        response = client.request("save", request_id="a")
        self.assertFalse(response["ok"])
        self.assertEqual("EXECUTION_UNKNOWN", response["error"]["code"])
        self.assertEqual([{"success": True, "scope_id": "run:1", "receipt_id": "saved-before-failure"}],
                         response["result"]["persistence"]["saves_during_request"])
        self.assertEqual("r7", client.version)
        self.assertEqual(wire, client.last_wire_response)

    def test_source_and_request_details_are_opt_in(self):
        self.assertEqual({"v": 4, "id": "q", "op": "state"}, protocol4.request("state.get", request_id="q"))
        self.assertTrue(protocol4.request("state.get", {"src": True})["src"])
        compact = protocol4.request("request.get", {"target_id": "a"})
        self.assertEqual("a", compact["rid"])
        self.assertNotIn("get", compact)
        detail = protocol4.request("request.get", {"target_id": "a", "get": ["reply"]})
        self.assertEqual(["reply"], detail["get"])

    def test_map_and_node_projection_reconstructs_only_public_values(self):
        wire = {"v": 4, "id": "q", "s": "run:1", "rev": "r7", "st": "completed", "data": {
            "scene": "game", "phase": "player_ready", "hero": {"hp": 20}, "acts": [{"op": "wait"}],
            "map": {"w": 4, "h": 4, "types": [{"terrain": 4, "name": "wall"}], "rows": [[1, 3, "0", "v"], [2, 0, "0", "s"]], "env": {"7": [{"name": "fire"}]}},
            "ui": {"nodes": [{"id": "n1", "text": "Drink", "ops": [{"op": "click"}]}]}}}
        original = copy.deepcopy(wire)
        decoded = protocol4.response(wire, "state.get")["result"]
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
        wire = {"v": 4, "id": "a", "s": "run:1", "rev": "activity:r1", "st": "in_progress",
                "data": {"scene": "game", "phase": "continuous_activity", "continuous_activity": activity}}
        state = protocol4.response(wire, "cell")["result"]
        self.assertEqual({"kind": "travel", "target_id": "a"}, state["observation"]["continuous_activity"])
        self.assertNotIn("run_outcome", state)
        wire["data"]["run_outcome"] = {"s": "run:1", "result": "lost"}
        state = protocol4.response(wire, "state")["result"]
        self.assertEqual({"scope_id": "run:1", "result": "lost"}, state["run_outcome"])
        self.assertEqual(state["run_outcome"], state["observation"]["run_outcome"])

    def test_sources_and_partial_diagnostics_are_not_fabricated(self):
        source = {"kind": "resource", "key": "items.test", "args": []}
        wire = {"v": 4, "data": {"scene": "game", "ui": {"nodes": [{"id": "n", "text": "A",
                "text_sources": {"text": source}, "pres": {"st": "partial", "diag": [{"field": "text", "code": "clipped_text"}]},
                "ops": [{"op": "click"}]}]}}}
        action = protocol4.response(wire)["result"]["actions"][0]
        self.assertEqual(source, action["text_sources"]["label"])
        self.assertEqual("clipped_text", action["text_diagnostics"]["label"])
        with self.assertRaises(AssertionError):
            protocol4.response({"protocol_version": 2, "ok": True})
        with self.assertRaises(AssertionError):
            protocol4.response({"v": 3, "st": "completed"})

    def test_map_integer_overflow_sparse_unknown_and_environment_removal(self):
        types = [{"terrain": i, "name": str(i)} for i in range(65)]
        types[64]["clipped"] = True
        types[64]["text_origins"] = {"name": ["external"]}
        data = {"scene": "game", "map": {"w": 5, "h": 2, "types": types,
                "rows": [[0, 1, [64, 1], "mv"], [1, 4, [2], "s"]],
                "env": {"2": [{"name": "gas"}]}}}
        first = protocol4.state(data)["observation"]["map"]
        self.assertEqual([1, 2, 9], [cell["cell"] for cell in first["cells"]])
        self.assertEqual("mapped", first["cells"][0]["visibility"])
        self.assertTrue(first["cells"][0]["clipped"])
        self.assertEqual(["external"], first["cells"][0]["text_origins"]["name"])
        self.assertEqual([{"name": "gas"}], first["cells"][1]["environment"])
        del data["map"]["env"]
        second = protocol4.state(data)["observation"]["map"]
        self.assertTrue(all(not cell["environment"] for cell in second["cells"]))

    def test_item_three_states_and_documented_defaults_preserve_known_zero_false(self):
        data = {"scene": "game", "inv": [
            {"loc": "backpack.1", "name": "food"},
            {"loc": "backpack.2", "name": "unknown armor", "level": None, "cursed": None},
            {"loc": "equipment.armor", "name": "known armor", "level": 0, "cursed": False, "equipped": True},
            {"loc": "backpack.3", "name": "weapon", "qty": 0, "available": False, "type_known": False}]}
        items = protocol4.state(data)["observation"]["inventory"]
        self.assertNotIn("level", items[0])
        self.assertIsNone(items[0]["level_known"])
        self.assertIsNone(items[0]["curse_known"])
        self.assertFalse(items[1]["level_known"])
        self.assertIsNone(items[1]["level"])
        self.assertTrue(items[2]["level_known"])
        self.assertEqual(0, items[2]["level"])
        self.assertTrue(items[2]["curse_known"])
        self.assertIs(False, items[2]["cursed"])
        self.assertEqual(1, items[0]["quantity"])
        self.assertEqual(0, items[3]["quantity"])
        self.assertFalse(items[3]["available"])
        self.assertFalse(items[3]["type_known"])

    def test_protected_item_knowledge_pairs_are_not_reinferred_from_values(self):
        source = {"kind": "literal", "origin": "external", "value": "Observed metadata"}
        items = [
            {"name": "inapplicable", "level": None, "level_known": None,
             "cursed": None, "curse_known": None, "text_sources": {"level_known": source}},
            {"name": "unknown", "level": None, "level_known": False,
             "cursed": None, "curse_known": False, "pres": {"st": "partial", "diag": []}},
            {"name": "known", "level": 0, "level_known": True,
             "cursed": False, "curse_known": True, "text_sources": {"cursed": source}},
            {"name": "protected partial", "level": 0, "level_known": False,
             "cursed": False, "curse_known": False, "clipped": True}]
        original = copy.deepcopy(items)
        decoded = protocol4.state({"scene": "game", "inv": items})["observation"]["inventory"]
        for expected, item in zip(original, decoded):
            for field in ("level", "level_known", "cursed", "curse_known"):
                self.assertIs(expected[field], item[field], field)
        self.assertEqual(source, decoded[0]["text_sources"]["level_known"])
        self.assertEqual(original, items)

    def test_preserved_cells_stay_opaque_diagnostics_and_never_supply_live_cells(self):
        preserved = [{"cell": 12, "terrain": 9, "visibility": "mapped",
                      "text_sources": {"cell": {"kind": "literal", "origin": "external", "value": "12"}},
                      "desc": "Preserved public annotation", "clipped": True}]
        data = {"scene": "game", "map": {"w": 4, "h": 4,
                "types": [{"terrain": 1, "name": "Floor"}], "rows": [[0, 1, "0", "v"]],
                "preserved_cells": preserved}}
        original = copy.deepcopy(data)
        decoded = protocol4.state(data)["observation"]["map"]
        self.assertEqual([1], [cell["cell"] for cell in decoded["cells"]])
        self.assertEqual(preserved, decoded["preserved_cells"])
        self.assertIsNot(preserved, decoded["preserved_cells"])
        data["map"]["rows"] = []
        empty = protocol4.state(data)["observation"]["map"]
        self.assertEqual([], empty["cells"])
        self.assertEqual(preserved, empty["preserved_cells"])
        self.assertEqual(original["map"]["preserved_cells"], preserved)

    def test_only_ops_advertise_click_and_multigesture_capabilities(self):
        data = {"scene": "game", "ui": {"nodes": [
            {"id": "disabled", "role": "button", "enabled": False, "text": "Unavailable"},
            {"id": "icon", "role": "button", "ops": [{"op": "click"}]},
            {"id": "long", "role": "button", "ops": [{"op": "click", "gestures": ["click", "long"]}]},
            {"id": "water", "text": "4/20", "label": "Waterskin", "ops": [{"op": "click"}]}]}}
        decoded = protocol4.state(data)
        self.assertEqual(["icon", "long", "water"], [action["control"] for action in decoded["actions"]])
        self.assertEqual(["click"], decoded["actions"][0]["gestures"])
        self.assertEqual(["click", "long"], decoded["actions"][1]["gestures"])
        controls = decoded["observation"]["ui"]["controls"]
        self.assertFalse(controls[0]["enabled"])
        self.assertTrue(controls[1]["enabled"])
        self.assertFalse(controls[1]["dimmed"])
        self.assertEqual("4/20", controls[3]["text"])
        self.assertEqual("Waterskin", decoded["actions"][2]["label"])

    def test_node_label_precedes_visible_numeric_item_text(self):
        wire = {"v": 4, "data": {"scene": "game", "ui": {"nodes": [
            {"id": "n", "label": "Waterskin", "text": "1/20", "ops": [{"op": "click"}],
             "text_sources": {"label": {"kind": "resource", "key": "waterskin.name"}}}]}}}
        action = protocol4.response(wire)["result"]["actions"][0]
        self.assertEqual("Waterskin", action["label"])
        self.assertEqual("waterskin.name", action["text_sources"]["label"]["key"])

    def test_request_summary_does_not_invent_reply_and_details_decode_exact_reply(self):
        summary = {"v": 4, "id": "lookup", "data": {"id": "a", "op": "wait", "st": "COMPLETED", "has": ["reply", "before"]}}
        self.assertNotIn("response", protocol4.response(summary, "request.get")["result"])
        reply = {"v": 4, "id": "a", "s": "run:1", "rev": "r8", "st": "completed", "data": {"scene": "game", "phase": "player_ready"}}
        summary["data"]["reply"] = reply
        self.assertEqual(protocol4.response(reply), protocol4.response(summary, "request.get")["result"]["response"])

    def test_history_metadata_keeps_actual_operation_instead_of_becoming_an_action(self):
        wire = {"v": 4, "id": "h", "data": {"items": [{"sequence": 2, "id": "a", "op": "move", "status": "COMPLETED"}],
                "next": None, "end": True, "until": 2}}
        row = protocol4.response(wire, "history.list")["result"][0]
        self.assertEqual("move", row["op"])
        self.assertNotIn("action", row)

    def test_pending_action_polls_summary_then_fetches_live_observation(self):
        client = object.__new__(Client)
        final = {"ok": True, "status": "completed", "result": {"scope_id": "run:1", "state_version": "r9"}}
        client.scope, client.version = "run:1", "r7"
        client.request = Mock(side_effect=[
            {"ok": True, "id": "a", "scope_id": "run:1", "status": "in_progress"},
            {"ok": True, "result": {"status": "COMPLETED", "after_rev": "r8"}}, final])
        settled = client.act("wait")
        self.assertEqual("completed", settled.status)
        self.assertEqual("r8", settled.outcome["after_rev"])
        self.assertEqual(final["result"], settled.observation)
        calls = client.request.call_args_list
        self.assertEqual(["action.execute", "request.get", "state.get"], [call.args[0] for call in calls])
        self.assertNotIn("get", calls[1].args[1])

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
                wire = {"v": 4, "id": "page", "data": {"items": [{"sequence": sequence}],
                        "until": upper, "next": sequence if more else None, "end": not more}}
                return protocol4.response(wire, op)

        client = GrowingHistory()
        rows = [row for batch in protocol4.pages(client, "history.list", limit=1) for row in batch]
        self.assertEqual([1, 2, 3], [row["sequence"] for row in rows])
        self.assertEqual(3, len(client.requests))
        self.assertNotIn("until", client.requests[0])
        self.assertEqual([3, 3], [request["until"] for request in client.requests[1:]])
        first_count = len(client.requests)
        rows = [row for batch in protocol4.pages(client, "history.list", after=3, limit=1) for row in batch]
        self.assertEqual([4, 5, 6], [row["sequence"] for row in rows])
        self.assertNotIn("until", client.requests[first_count], "A new polling cycle must capture a fresh upper bound")

    def test_large_single_request_and_response_are_collected_without_truncation(self):
        engine = 'import json,sys; r=json.loads(sys.stdin.buffer.readline()); print(json.dumps({"v":4,"id":r["id"],"st":"completed","data":{"echo":r["text"]}},ensure_ascii=False),flush=True)'
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
            self.assertEqual(4, json.loads(client.last_send_bytes)["v"])
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
