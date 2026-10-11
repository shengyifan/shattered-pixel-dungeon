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
import protocol9
from spdctl_client import DecodeError, decode_wire_response


def decoded_data(data):
    return decode_wire_response({"v": 9, "id": "fixture", "st": "completed", "data": data}).data


class Protocol9BaseTest(unittest.TestCase):
    def test_semantic_cue_lists_keep_their_inner_key_in_live_states_and_events(self):
        cues = [{"kind": "bomb_smoke", "cell": 6},
                {"kind": "blast_wave", "cell": 6, "appearance": {
                    "shape": "ring", "cells": [6, 7], "coverage": "visual_extent", "partial": True}}]
        snapshot = {"status": "last_observed", "depth": 10, "map_context": "fixture:tengu", "cues": cues}
        wire = {"v": 9, "id": "state", "st": "completed", "data": {"scene": "game", "cues": snapshot}}
        before = copy.deepcopy(wire)
        observation = decode_wire_response(wire).frame["data"]
        self.assertEqual(snapshot, observation["cues"])
        self.assertNotIn("visual_cues", observation["cues"])
        self.assertEqual(before, wire)
        for visible in (cues, []):
            event_data = {"format": "semantic_cue_snapshot_v1", "depth": 10,
                          "map_context": "fixture:tengu", "cues": visible}
            event = {"v": 9, "id": "event", "st": "completed", "data": {
                "items": [{"kind": "game.visual", "data": event_data}], "next": None, "end": True, "until": 1}}
            decoded = decode_wire_response(event).data["items"][0]
            self.assertEqual(event_data, decoded["data"])

    def test_defaults_semantic_item_display_and_source_ast_are_separate(self):
        ast = {"kind": "external", "max_hp": 99, "value": "unmodified source keys"}
        wire = {"v": 9, "id": "q", "s": "run:1", "rev": "r5", "st": "completed", "data": {
            "scene": "game", "hero": {"hp": 24, "ht": 30, "experience": 10, "mxp": 20, "tp": [0, 0, 0, 0]},
            "inv": [{"loc": "backpack.0", "name": "unknown armor", "level": None,
                     "cursed": None, "shown": {"extra": "14?"}}],
            "ui": {"nodes": [{"id": "slot", "subject": {"kind": "item", "loc": "backpack.0"},
                               "text": "14?", "text_sources": {"text": ast},
                               "ops": [{"op": "click"}]}]},
            "persistence": {"saves": [{"sid": "s1", "src_s": "run:1", "src_id": "a1", "at": "time", "success": True}]}}}
        before = copy.deepcopy(wire)
        state = decode_wire_response(wire).frame["data"]
        obs = state
        self.assertEqual(30, obs["hero"]["ht"])
        self.assertEqual(20, obs["hero"]["mxp"])
        self.assertEqual([0, 0, 0, 0], obs["hero"]["tp"])
        self.assertEqual("click", obs["inv"][0]["via"])
        self.assertIsNone(obs["inv"][0]["level"])
        self.assertIsNone(obs["inv"][0]["cursed"])
        self.assertFalse(obs["ui"]["modal"])
        self.assertIsNone(obs["ui"]["item_info"])
        self.assertEqual(ast, obs["ui"]["nodes"][0]["text_sources"]["text"])
        self.assertEqual("14?", obs["inv"][0]["shown"]["extra"])
        self.assertEqual("s1", state["persistence"]["saves"][0]["sid"])
        self.assertEqual(before, wire)
        with self.assertRaises(DecodeError):
            decode_wire_response({"v": 4, "st": "completed"}).frame

    def test_per_frame_entity_and_effect_dictionaries_expand_without_history(self):
        hazard = {"kind": "trap", "name": "Trap", "desc": "full warning", "text_origins": {"desc": ["external"]}}
        effects = [{"type": "visible_effect", "desc": "fire"}, {"type": "visible_effect", "desc": "gas"}]
        data = {"scene": "game", "entity_defs": [hazard], "entities": [{"cell": 1, "def": 0}, {"cell": 2, "def": 0}],
                "map": {"w": 4, "h": 1, "types": [{"terrain": 1, "name": "Floor"}],
                        "rows": [[0, 1, "000", "v"]], "effect_defs": [effects], "env": {"1": 0, "2": 0, "3": []}}}
        before = copy.deepcopy(data)
        obs = decoded_data(data)
        self.assertEqual([1, 2], [e["cell"] for e in obs["entities"]])
        self.assertEqual("full warning", obs["entities"][0]["desc"])
        self.assertEqual(["external"], obs["entities"][1]["text_origins"]["desc"])
        self.assertEqual(["visible"] * 3, [c["visibility"] for c in obs["map"]["cells"]])
        self.assertEqual(["fire", "gas"], [e["desc"] for e in obs["map"]["cells"][0]["environment"]])
        self.assertEqual([], obs["map"]["cells"][2]["environment"])
        second = {"scene": "game", "entities": [], "map": {"w": 4, "h": 1, "types": [{"terrain": 4, "name": "Wall"}], "rows": [[0, 2, "0", "s"]]}}
        new = decoded_data(second)
        self.assertEqual([], new["entities"])
        self.assertEqual("Wall", new["map"]["cells"][0]["name"])
        self.assertEqual([], new["map"]["cells"][0]["environment"])
        self.assertEqual(before, data)

    def test_missing_invalid_or_overriding_dictionary_references_fail(self):
        for bad in (-1, 1, True, 0.0, "0"):
            with self.subTest(ref=bad), self.assertRaises(DecodeError):
                decoded_data({"scene": "game", "entity_defs": [{"kind": "trap"}], "entities": [{"cell": 1, "def": bad}]})
        with self.assertRaises(DecodeError):
            decoded_data({"scene": "game", "entities": [{"cell": 1, "def": 0}]})
        with self.assertRaises(DecodeError):
            decoded_data({"scene": "game", "entity_defs": [{"kind": "trap"}], "entities": [{"cell": 1, "def": 0, "name": "override"}]})
        for value in (0, True, "0"):
            with self.subTest(effect=value), self.assertRaises(DecodeError):
                decoded_data({"scene": "game", "map": {"w": 1, "h": 1, "types": [{"terrain": 1}], "rows": [[0, 0, "0", "v"]], "env": {"0": value}}})

    def test_uniform_visibility_integer_tiles_and_invalid_lengths(self):
        types = [{"terrain": i} for i in range(65)]
        obs = decoded_data({"scene": "game", "map": {"w": 5, "h": 1, "types": types, "rows": [[0, 1, [64, 0, 5], "m"]]}})
        self.assertEqual(["mapped"] * 3, [c["visibility"] for c in obs["map"]["cells"]])
        for tiles, vis in (("", "v"), ("000", "vs"), ("00", "")):
            with self.assertRaises(DecodeError):
                decoded_data({"scene": "game", "map": {"w": 5, "h": 1, "types": [{}], "rows": [[0, 0, tiles, vis]]}})

    def test_direct_short_action_preserves_revision_and_updates_live_context(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "run:1", "r7", 0, "direct"
        client.buffer = protocol9.wire_bytes({"v": 9, "id": "a", "s": "run:1", "rev": "r8", "st": "completed",
                                            "data": {"scene": "game", "phase": "player_ready"}})
        response = client.request("move", {"dir": "N"}, request_id="a", version="r7")
        self.assertEqual({"v": 9, "id": "a", "s": "run:1", "rev": "r7", "op": "move", "dir": "N"},
                         json.loads(client.process.stdin.getvalue()))
        self.assertEqual("r8", client.version)
        self.assertEqual("r8", response["rev"])
        self.assertFalse(protocol9.is_live_operation("req"))
        self.assertFalse(protocol9.is_live_operation("events"))

    def test_info_version_comes_from_envelope_without_inventing_data_field(self):
        wire = {"v": 9, "id": "hello", "s": "menu:1", "rev": "r1", "st": "completed",
                "data": {"cli_version": "CLI.9.0.6", "audit_schema_version": 12}}
        response = decode_wire_response(wire).frame
        self.assertEqual(9, response["v"])
        self.assertNotIn("protocol_version", response["data"])

    def test_uncertain_error_keeps_confirmed_saves_without_advancing_live_context(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "run:1", "r7", 0, "uncertain"
        receipt = {"success": True, "s": "run:1", "sid": "saved-before-failure"}
        wire = {"v": 9, "id": "a", "s": "run:1", "rev": "r8", "err": "EXECUTION_UNKNOWN",
                "data": {"persistence": {"saves": [receipt]}}}
        client.buffer = protocol9.wire_bytes(wire)
        response = client.request("save", request_id="a")
        self.assertEqual("EXECUTION_UNKNOWN", response["err"])
        self.assertEqual([{"success": True, "s": "run:1", "src_s": "run:1", "sid": "saved-before-failure"}],
                         response["data"]["persistence"]["saves"])
        self.assertEqual("r7", client.version)
        self.assertEqual(wire, client.last_wire_response)

    def test_source_and_request_details_are_opt_in(self):
        self.assertEqual({"v": 9, "id": "q", "op": "state"}, protocol9.request("state", request_id="q"))
        self.assertTrue(protocol9.request("state", {"src": True})["src"])
        compact = protocol9.request("req", {"rid": "a"})
        self.assertEqual("a", compact["rid"])
        self.assertNotIn("get", compact)
        detail = protocol9.request("req", {"rid": "a", "get": ["reply"]})
        self.assertEqual(["reply"], detail["get"])

    def test_map_and_node_projection_reconstructs_only_public_values(self):
        wire = {"v": 9, "id": "q", "s": "run:1", "rev": "r7", "st": "completed", "data": {
            "scene": "game", "phase": "player_ready", "hero": {"hp": 20},
            "acts": [{"op": "wait"}, {"op": "click", "ctl": "n1", "label": "Drink", "gestures": ["click"]}],
            "map": {"w": 4, "h": 4, "types": [{"terrain": 4, "name": "wall"}], "rows": [[1, 3, "0", "v"], [2, 0, "0", "s"]], "env": {"7": [{"name": "fire"}]}},
            "ui": {"nodes": [{"id": "n1", "text": "Drink", "ops": [{"op": "click"}]}]}}}
        original = copy.deepcopy(wire)
        response = decode_wire_response(wire)
        decoded = response.data
        self.assertEqual(("run:1", "r7"), (response.frame["s"], response.frame["rev"]))
        cells = decoded["map"]["cells"]
        self.assertEqual([7, 8], [row["cell"] for row in cells])
        self.assertEqual((3, 1, "visible"), (cells[0]["x"], cells[0]["y"], cells[0]["visibility"]))
        self.assertEqual("visited", cells[1]["visibility"])
        self.assertEqual([{"name": "fire"}], cells[0]["environment"])
        self.assertEqual({"op": "click", "ctl": "n1", "label": "Drink", "gestures": ["click"]}, decoded["acts"][1])
        self.assertNotIn("text_sources", decoded["acts"][1])
        self.assertEqual(original, wire)

    def test_activity_binds_current_revision_and_retains_disclosed_run_outcome(self):
        activity = {"kind": "travel", "rid": "a"}
        wire = {"v": 9, "id": "a", "s": "run:1", "rev": "a1", "st": "in_progress",
                "data": {"scene": "game", "phase": "continuous_activity", "activity": activity}}
        state = decode_wire_response(wire).frame["data"]
        self.assertEqual({"kind": "travel", "rid": "a", "rev": "a1"}, state["activity"])
        self.assertNotIn("run_outcome", state)
        wire["data"]["run_outcome"] = {"s": "run:1", "result": "lost"}
        state = decode_wire_response(wire).frame["data"]
        self.assertEqual({"s": "run:1", "result": "lost"}, state["run_outcome"])

    def test_sources_and_partial_diagnostics_are_not_fabricated(self):
        source = {"kind": "resource", "key": "items.test", "args": []}
        wire = {"v": 9, "id": "q", "st": "completed", "data": {"scene": "game", "ui": {"nodes": [{"id": "n", "text": "A",
                "text_sources": {"text": source}, "pres": {"st": "partial", "diag": [{"field": "text", "code": "clipped_text"}]},
                "ops": [{"op": "click"}]}]}}}
        wire["data"]["acts"] = [{"op": "click", "ctl": "n", "label": "A", "text_sources": {"label": source},
                                 "pres": {"st": "partial", "diag": [{"field": "label", "code": "clipped_text"}]}}]
        action = decode_wire_response(wire).frame["data"]["acts"][0]
        self.assertEqual(source, action["text_sources"]["label"])
        self.assertEqual({"st": "partial", "diag": [{"field": "label", "code": "clipped_text"}]}, action["pres"])
        with self.assertRaises(DecodeError):
            decode_wire_response({"protocol_version": 2, "ok": True}).frame
        with self.assertRaises(DecodeError):
            decode_wire_response({"v": 3, "st": "completed"}).frame

    def test_map_integer_overflow_sparse_unknown_and_environment_removal(self):
        types = [{"terrain": i, "name": str(i)} for i in range(65)]
        types[64]["clipped"] = True
        types[64]["text_origins"] = {"name": ["external"]}
        data = {"scene": "game", "map": {"w": 5, "h": 2, "types": types,
                "rows": [[0, 1, [64, 1], "mv"], [1, 4, [2], "s"]],
                "env": {"2": [{"name": "gas"}]}}}
        first = decoded_data(data)["map"]
        self.assertEqual([1, 2, 9], [cell["cell"] for cell in first["cells"]])
        self.assertEqual("mapped", first["cells"][0]["visibility"])
        self.assertTrue(first["cells"][0]["clipped"])
        self.assertEqual(["external"], first["cells"][0]["text_origins"]["name"])
        self.assertEqual([{"name": "gas"}], first["cells"][1]["environment"])
        del data["map"]["env"]
        second = decoded_data(data)["map"]
        self.assertTrue(all(not cell["environment"] for cell in second["cells"]))

    def test_item_three_states_and_documented_defaults_preserve_known_zero_false(self):
        data = {"scene": "game", "inv": [
            {"loc": "backpack.1", "name": "food"},
            {"loc": "backpack.2", "name": "unknown armor", "level": None, "cursed": None},
            {"loc": "equipment.armor", "name": "known armor", "level": 0, "cursed": False, "equipped": True},
            {"loc": "backpack.3", "name": "weapon", "qty": 0, "available": False, "type_known": False}]}
        items = decoded_data(data)["inv"]
        self.assertNotIn("level", items[0])
        self.assertNotIn("cursed", items[0])
        self.assertIsNone(items[1]["level"])
        self.assertIsNone(items[1]["cursed"])
        self.assertEqual(0, items[2]["level"])
        self.assertIs(False, items[2]["cursed"])
        for item in items:
            self.assertNotIn("level_known", item)
            self.assertNotIn("curse_known", item)
        self.assertEqual(1, items[0]["qty"])
        self.assertEqual(0, items[3]["qty"])
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
        decoded = decoded_data({"scene": "game", "inv": items})["inv"]
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
        decoded = decoded_data(data)["map"]
        self.assertEqual([1], [cell["cell"] for cell in decoded["cells"]])
        self.assertEqual(preserved, decoded["preserved_cells"])
        self.assertIsNot(preserved, decoded["preserved_cells"])
        data["map"]["rows"] = []
        empty = decoded_data(data)["map"]
        self.assertEqual([], empty["cells"])
        self.assertEqual(preserved, empty["preserved_cells"])
        self.assertEqual(original["map"]["preserved_cells"], preserved)

    def test_complete_acts_advertise_click_and_multigesture_capabilities(self):
        data = {"scene": "game", "ui": {"nodes": [
            {"id": "disabled", "role": "button", "enabled": False, "text": "Unavailable"},
            {"id": "icon", "role": "button", "ops": [{"op": "click"}]},
            {"id": "long", "role": "button", "ops": [{"op": "click", "gestures": ["click", "long"]}]},
            {"id": "water", "text": "4/20", "label": "Waterskin", "ops": [{"op": "click"}]}]}}
        data["acts"] = [{"op": "click", "ctl": "icon", "gestures": ["click"]},
                        {"op": "click", "ctl": "long", "gestures": ["click", "long"]},
                        {"op": "click", "ctl": "water", "label": "Waterskin", "gestures": ["click"]}]
        decoded = decoded_data(data)
        self.assertEqual(["icon", "long", "water"], [action["ctl"] for action in decoded["acts"]])
        self.assertEqual(["click"], decoded["acts"][0]["gestures"])
        self.assertEqual(["click", "long"], decoded["acts"][1]["gestures"])
        controls = decoded["ui"]["nodes"]
        self.assertFalse(controls[0]["enabled"])
        self.assertTrue(controls[1]["enabled"])
        self.assertFalse(controls[1]["dimmed"])
        self.assertEqual("4/20", controls[3]["text"])
        self.assertEqual("Waterskin", decoded["acts"][2]["label"])

    def test_action_label_is_independent_of_visible_numeric_item_text(self):
        wire = {"v": 9, "id": "q", "st": "completed", "data": {"scene": "game", "ui": {"nodes": [
            {"id": "n", "label": "Waterskin", "text": "1/20", "ops": [{"op": "click"}],
             "text_sources": {"label": {"kind": "resource", "key": "waterskin.name"}}}]}}}
        wire["data"]["acts"] = [{"op": "click", "ctl": "n", "label": "Waterskin",
                                 "text_sources": {"label": {"kind": "resource", "key": "waterskin.name"}}}]
        action = decode_wire_response(wire).frame["data"]["acts"][0]
        self.assertEqual("Waterskin", action["label"])
        self.assertEqual("waterskin.name", action["text_sources"]["label"]["key"])

    def test_request_summary_does_not_invent_reply_and_details_keep_exact_wire_reply(self):
        summary = {"v": 9, "id": "lookup", "st": "completed", "data": {"id": "a", "op": "wait", "st": "COMPLETED", "has": ["reply", "before"]}}
        self.assertNotIn("reply", decode_wire_response(summary).data)
        reply = {"v": 9, "id": "a", "s": "run:1", "rev": "r8", "st": "completed", "data": {"scene": "game", "phase": "player_ready"}}
        summary["data"]["reply"] = reply
        self.assertEqual(reply, decode_wire_response(summary).data["reply"])

    def test_history_metadata_keeps_actual_operation_instead_of_becoming_an_action(self):
        wire = {"v": 9, "id": "h", "st": "completed", "data": {"items": [{"seq": 2, "id": "a", "op": "move", "st": "COMPLETED"}],
                "next": None, "end": True, "until": 2}}
        row = decode_wire_response(wire).data["items"][0]
        self.assertEqual("move", row["op"])
        self.assertNotIn("action", row)

    def test_pending_action_polls_summary_then_fetches_live_observation(self):
        client = object.__new__(Client)
        final = {"v": 9, "id": "state", "s": "run:1", "rev": "r9", "st": "completed", "data": {"scene": "game"}}
        client.scope, client.version = "run:1", "r7"
        client.request = Mock(side_effect=[
            {"v": 9, "id": "a", "s": "run:1", "st": "in_progress"},
            {"v": 9, "id": "receipt", "st": "completed", "data": {"st": "COMPLETED", "after_rev": "r8"}}, final])
        settled = client.act("wait")
        self.assertEqual("completed", settled.status)
        self.assertEqual("r8", settled.outcome["after_rev"])
        self.assertEqual({**final["data"], "s": "run:1", "rev": "r9"}, settled.observation)
        calls = client.request.call_args_list
        self.assertEqual(["wait", "req", "state"], [call.args[0] for call in calls])
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
                wire = {"v": 9, "id": "page", "st": "completed", "data": {"items": [{"seq": sequence}],
                        "until": upper, "next": sequence if more else None, "end": not more}}
                return decode_wire_response(wire).frame

        client = GrowingHistory()
        rows = [row for batch in protocol9.pages(client, "history", limit=1) for row in batch]
        self.assertEqual([1, 2, 3], [row["seq"] for row in rows])
        self.assertEqual(3, len(client.requests))
        self.assertNotIn("until", client.requests[0])
        self.assertEqual([3, 3], [request["until"] for request in client.requests[1:]])
        first_count = len(client.requests)
        rows = [row for batch in protocol9.pages(client, "history", after=3, limit=1) for row in batch]
        self.assertEqual([4, 5, 6], [row["seq"] for row in rows])
        self.assertNotIn("until", client.requests[first_count], "A new polling cycle must capture a fresh upper bound")

    def test_large_single_request_and_response_are_collected_without_truncation(self):
        engine = 'import json,sys; r=json.loads(sys.stdin.buffer.readline()); print(json.dumps({"v":9,"id":r["id"],"st":"completed","data":{"echo":r["text"]}},ensure_ascii=False),flush=True)'
        process = subprocess.Popen([sys.executable, "-c", engine], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        client = object.__new__(Client)
        client.process, client.profile = process, Path("unused-offline-wire-fixture")
        client.scope, client.version, client.counter, client.prefix, client.buffer = None, None, 0, "long", b""
        payload = "x" * (9 * 1024 * 1024) + "中文🐈"
        try:
            result = client.request("text", {"text": payload})
            self.assertEqual(payload, result["data"]["echo"])
            self.assertGreater(len(client.last_send_bytes), 9 * 1024 * 1024)
            self.assertGreater(len(client.last_recv_bytes), 9 * 1024 * 1024)
            self.assertEqual(9, json.loads(client.last_send_bytes)["v"])
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
