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
import protocol7


class Protocol7BaseTest(unittest.TestCase):
    def test_rendered_cue_lists_keep_their_inner_key_in_live_states_and_events(self):
        cues = [{"kind": "bomb_smoke", "cell": 6}, {"kind": "bomb_countdown_3", "cell": 6}]
        snapshot = {"status": "last_rendered", "depth": 10, "map_context": "fixture:tengu", "cues": cues}
        wire = {"v": 7, "id": "state", "st": "completed", "data": {"scene": "game", "cues": snapshot}}
        before = copy.deepcopy(wire)
        observation = protocol7.response(wire, "state")["result"]["observation"]
        self.assertEqual(snapshot, observation["visual_cues"])
        self.assertNotIn("visual_cues", observation["visual_cues"])
        self.assertEqual(before, wire)
        for visible in (cues, []):
            event_data = {"format": "display_snapshot_v1", "depth": 10, "map_context": "fixture:tengu", "cues": visible}
            event = {"v": 7, "id": "event", "st": "completed", "data": {
                "items": [{"kind": "game.visual", "data": event_data}], "next": None, "end": True, "until": 1}}
            decoded = protocol7.response(event, "events")["result"][0]
            self.assertEqual(event_data, decoded["data"])

    def test_v5_aliases_defaults_and_source_ast_are_separate(self):
        ast = {"kind": "external", "max_hp": 99, "value": "unmodified source keys"}
        wire = {"v": 7, "id": "q", "s": "run:1", "rev": "r5", "st": "completed", "data": {
            "scene": "game", "hero": {"hp": 24, "ht": 30, "experience": 10, "mxp": 20, "tp": [0, 0, 0, 0]},
            "inv": [{"loc": "backpack.0", "name": "unknown armor", "level": None, "cursed": None}],
            "ui": {"nodes": [{"id": "slot", "loc": "backpack.0", "display": {"extra": "14?"},
                               "text_sources": {"display.extra": ast}, "ops": [{"op": "click"}]}]},
            "persistence": {"saves": [{"sid": "s1", "src_s": "run:1", "src_id": "a1", "at": "time", "success": True}]}}}
        before = copy.deepcopy(wire)
        state = protocol7.response(wire)["result"]
        obs = state["observation"]
        self.assertEqual(30, obs["hero"]["max_hp"])
        self.assertEqual(20, obs["hero"]["max_experience"])
        self.assertEqual([0, 0, 0, 0], obs["hero"]["talent_points_available"])
        self.assertEqual("ui.activate", obs["inventory"][0]["details_via"])
        self.assertIsNone(obs["inventory"][0]["level"])
        self.assertIsNone(obs["inventory"][0]["cursed"])
        self.assertFalse(obs["ui"]["modal"])
        self.assertIsNone(obs["ui"]["inspected_item"])
        self.assertEqual(ast, obs["ui"]["controls"][0]["text_sources"]["display.extra"])
        self.assertEqual("14?", obs["ui"]["controls"][0]["display"]["extra"])
        self.assertEqual("s1", state["persistence"]["saves_during_request"][0]["receipt_id"])
        self.assertEqual(before, wire)
        with self.assertRaises(AssertionError):
            protocol7.response({"v": 4, "st": "completed"})

    def test_per_frame_entity_and_effect_dictionaries_expand_without_history(self):
        hazard = {"kind": "trap", "name": "Trap", "desc": "full warning", "text_origins": {"desc": ["external"]}}
        effects = [{"type": "visible_effect", "desc": "fire"}, {"type": "visible_effect", "desc": "gas"}]
        data = {"scene": "game", "entity_defs": [hazard], "entities": [{"cell": 1, "def": 0}, {"cell": 2, "def": 0}],
                "map": {"w": 4, "h": 1, "types": [{"terrain": 1, "name": "Floor"}],
                        "rows": [[0, 1, "000", "v"]], "effect_defs": [effects], "env": {"1": 0, "2": 0, "3": []}}}
        before = copy.deepcopy(data)
        obs = protocol7.state(data)["observation"]
        self.assertNotIn("entity_defs", obs)
        self.assertEqual([1, 2], [e["cell"] for e in obs["visible_entities"]])
        self.assertEqual("full warning", obs["visible_entities"][0]["description"])
        self.assertEqual(["external"], obs["visible_entities"][1]["text_origins"]["description"])
        self.assertEqual(["visible"] * 3, [c["visibility"] for c in obs["map"]["cells"]])
        self.assertEqual(["fire", "gas"], [e["description"] for e in obs["map"]["cells"][0]["environment"]])
        self.assertEqual([], obs["map"]["cells"][2]["environment"])
        second = {"scene": "game", "entities": [], "map": {"w": 4, "h": 1, "types": [{"terrain": 4, "name": "Wall"}], "rows": [[0, 2, "0", "s"]]}}
        new = protocol7.state(second)["observation"]
        self.assertEqual([], new["visible_entities"])
        self.assertEqual("Wall", new["map"]["cells"][0]["name"])
        self.assertEqual([], new["map"]["cells"][0]["environment"])
        self.assertEqual(before, data)

    def test_missing_invalid_or_overriding_dictionary_references_fail(self):
        for bad in (-1, 1, True, 0.0, "0"):
            with self.subTest(ref=bad), self.assertRaises(AssertionError):
                protocol7.state({"scene": "game", "entity_defs": [{"kind": "trap"}], "entities": [{"cell": 1, "def": bad}]})
        with self.assertRaises(AssertionError):
            protocol7.state({"scene": "game", "entities": [{"cell": 1, "def": 0}]})
        with self.assertRaises(AssertionError):
            protocol7.state({"scene": "game", "entity_defs": [{"kind": "trap"}], "entities": [{"cell": 1, "def": 0, "name": "override"}]})
        for value in (0, True, "0"):
            with self.subTest(effect=value), self.assertRaises(AssertionError):
                protocol7.state({"scene": "game", "map": {"w": 1, "h": 1, "types": [{"terrain": 1}], "rows": [[0, 0, "0", "v"]], "env": {"0": value}}})

    def test_uniform_visibility_integer_tiles_and_invalid_lengths(self):
        types = [{"terrain": i} for i in range(65)]
        obs = protocol7.state({"scene": "game", "map": {"w": 5, "h": 1, "types": types, "rows": [[0, 1, [64, 0, 5], "m"]]}})["observation"]
        self.assertEqual(["mapped"] * 3, [c["visibility"] for c in obs["map"]["cells"]])
        for tiles, vis in (("", "v"), ("000", "vs"), ("00", "")):
            with self.assertRaises(AssertionError):
                protocol7.state({"scene": "game", "map": {"w": 5, "h": 1, "types": [{}], "rows": [[0, 0, tiles, vis]]}})

    def test_actions_are_flat_and_directions_use_wire_enum(self):
        wire = protocol7.request("action.execute", {"action": "move.step", "direction": "northeast"}, "a", "run:1", "r7")
        self.assertEqual({"v": 7, "id": "a", "s": "run:1", "rev": "r7", "op": "move", "dir": "NE"}, wire)
        self.assertNotIn("args", wire)
        self.assertNotIn("action", wire)
        self.assertEqual("click", protocol7.request("action.execute", {"action": "ui.activate", "control": "n1"})["op"])

    def test_direct_short_action_preserves_revision_and_updates_live_context(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "run:1", "r7", 0, "direct"
        client.buffer = protocol7.wire_bytes({"v": 7, "id": "a", "s": "run:1", "rev": "r8", "st": "completed",
                                            "data": {"scene": "game", "phase": "player_ready"}})
        response = client.request("move", {"dir": "N"}, request_id="a", version="r7")
        self.assertEqual({"v": 7, "id": "a", "s": "run:1", "rev": "r7", "op": "move", "dir": "N"},
                         json.loads(client.process.stdin.getvalue()))
        self.assertEqual("r8", client.version)
        self.assertEqual("r8", response["result"]["state_version"])
        self.assertFalse(protocol7.is_live_operation("req"))
        self.assertFalse(protocol7.is_live_operation("events"))

    def test_info_version_comes_from_envelope_without_inventing_data_field(self):
        wire = {"v": 7, "id": "hello", "s": "menu:1", "rev": "r1", "st": "completed",
                "data": {"cli_version": "CLI.7.0.0", "audit_schema_version": 10}}
        response = protocol7.response(wire, "info")
        self.assertEqual(7, response["protocol_version"])
        self.assertNotIn("protocol_version", response["result"])

    def test_uncertain_error_keeps_confirmed_saves_without_advancing_live_context(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "run:1", "r7", 0, "uncertain"
        receipt = {"success": True, "s": "run:1", "receipt_id": "saved-before-failure"}
        wire = {"v": 7, "id": "a", "s": "run:1", "rev": "r8", "err": "EXECUTION_UNKNOWN",
                "data": {"persistence": {"saves_during_request": [receipt]}}}
        client.buffer = protocol7.wire_bytes(wire)
        response = client.request("save", request_id="a")
        self.assertFalse(response["ok"])
        self.assertEqual("EXECUTION_UNKNOWN", response["error"]["code"])
        self.assertEqual([{"success": True, "scope_id": "run:1", "receipt_id": "saved-before-failure"}],
                         response["result"]["persistence"]["saves_during_request"])
        self.assertEqual("r7", client.version)
        self.assertEqual(wire, client.last_wire_response)

    def test_source_and_request_details_are_opt_in(self):
        self.assertEqual({"v": 7, "id": "q", "op": "state"}, protocol7.request("state.get", request_id="q"))
        self.assertTrue(protocol7.request("state.get", {"src": True})["src"])
        compact = protocol7.request("request.get", {"target_id": "a"})
        self.assertEqual("a", compact["rid"])
        self.assertNotIn("get", compact)
        detail = protocol7.request("request.get", {"target_id": "a", "get": ["reply"]})
        self.assertEqual(["reply"], detail["get"])

    def test_map_and_node_projection_reconstructs_only_public_values(self):
        wire = {"v": 7, "id": "q", "s": "run:1", "rev": "r7", "st": "completed", "data": {
            "scene": "game", "phase": "player_ready", "hero": {"hp": 20},
            "acts": [{"op": "wait"}, {"op": "click", "ctl": "n1", "label": "Drink", "gestures": ["click"]}],
            "map": {"w": 4, "h": 4, "types": [{"terrain": 4, "name": "wall"}], "rows": [[1, 3, "0", "v"], [2, 0, "0", "s"]], "env": {"7": [{"name": "fire"}]}},
            "ui": {"nodes": [{"id": "n1", "text": "Drink", "ops": [{"op": "click"}]}]}}}
        original = copy.deepcopy(wire)
        decoded = protocol7.response(wire, "state.get")["result"]
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
        wire = {"v": 7, "id": "a", "s": "run:1", "rev": "activity:r1", "st": "in_progress",
                "data": {"scene": "game", "phase": "continuous_activity", "continuous_activity": activity}}
        state = protocol7.response(wire, "cell")["result"]
        self.assertEqual({"kind": "travel", "target_id": "a"}, state["observation"]["continuous_activity"])
        self.assertNotIn("run_outcome", state)
        wire["data"]["run_outcome"] = {"s": "run:1", "result": "lost"}
        state = protocol7.response(wire, "state")["result"]
        self.assertEqual({"scope_id": "run:1", "result": "lost"}, state["run_outcome"])
        self.assertEqual(state["run_outcome"], state["observation"]["run_outcome"])

    def test_sources_and_partial_diagnostics_are_not_fabricated(self):
        source = {"kind": "resource", "key": "items.test", "args": []}
        wire = {"v": 7, "data": {"scene": "game", "ui": {"nodes": [{"id": "n", "text": "A",
                "text_sources": {"text": source}, "pres": {"st": "partial", "diag": [{"field": "text", "code": "clipped_text"}]},
                "ops": [{"op": "click"}]}]}}}
        wire["data"]["acts"] = [{"op": "click", "ctl": "n", "label": "A", "text_sources": {"label": source},
                                 "pres": {"st": "partial", "diag": [{"field": "label", "code": "clipped_text"}]}}]
        action = protocol7.response(wire)["result"]["actions"][0]
        self.assertEqual(source, action["text_sources"]["label"])
        self.assertEqual("clipped_text", action["text_diagnostics"]["label"])
        with self.assertRaises(AssertionError):
            protocol7.response({"protocol_version": 2, "ok": True})
        with self.assertRaises(AssertionError):
            protocol7.response({"v": 3, "st": "completed"})

    def test_map_integer_overflow_sparse_unknown_and_environment_removal(self):
        types = [{"terrain": i, "name": str(i)} for i in range(65)]
        types[64]["clipped"] = True
        types[64]["text_origins"] = {"name": ["external"]}
        data = {"scene": "game", "map": {"w": 5, "h": 2, "types": types,
                "rows": [[0, 1, [64, 1], "mv"], [1, 4, [2], "s"]],
                "env": {"2": [{"name": "gas"}]}}}
        first = protocol7.state(data)["observation"]["map"]
        self.assertEqual([1, 2, 9], [cell["cell"] for cell in first["cells"]])
        self.assertEqual("mapped", first["cells"][0]["visibility"])
        self.assertTrue(first["cells"][0]["clipped"])
        self.assertEqual(["external"], first["cells"][0]["text_origins"]["name"])
        self.assertEqual([{"name": "gas"}], first["cells"][1]["environment"])
        del data["map"]["env"]
        second = protocol7.state(data)["observation"]["map"]
        self.assertTrue(all(not cell["environment"] for cell in second["cells"]))

    def test_item_three_states_and_documented_defaults_preserve_known_zero_false(self):
        data = {"scene": "game", "inv": [
            {"loc": "backpack.1", "name": "food"},
            {"loc": "backpack.2", "name": "unknown armor", "level": None, "cursed": None},
            {"loc": "equipment.armor", "name": "known armor", "level": 0, "cursed": False, "equipped": True},
            {"loc": "backpack.3", "name": "weapon", "qty": 0, "available": False, "type_known": False}]}
        items = protocol7.state(data)["observation"]["inventory"]
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
        decoded = protocol7.state({"scene": "game", "inv": items})["observation"]["inventory"]
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
        decoded = protocol7.state(data)["observation"]["map"]
        self.assertEqual([1], [cell["cell"] for cell in decoded["cells"]])
        self.assertEqual(preserved, decoded["preserved_cells"])
        self.assertIsNot(preserved, decoded["preserved_cells"])
        data["map"]["rows"] = []
        empty = protocol7.state(data)["observation"]["map"]
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
        decoded = protocol7.state(data)
        self.assertEqual(["icon", "long", "water"], [action["control"] for action in decoded["actions"]])
        self.assertEqual(["click"], decoded["actions"][0]["gestures"])
        self.assertEqual(["click", "long"], decoded["actions"][1]["gestures"])
        controls = decoded["observation"]["ui"]["controls"]
        self.assertFalse(controls[0]["enabled"])
        self.assertTrue(controls[1]["enabled"])
        self.assertFalse(controls[1]["dimmed"])
        self.assertEqual("4/20", controls[3]["text"])
        self.assertEqual("Waterskin", decoded["actions"][2]["label"])

    def test_action_label_is_independent_of_visible_numeric_item_text(self):
        wire = {"v": 7, "data": {"scene": "game", "ui": {"nodes": [
            {"id": "n", "label": "Waterskin", "text": "1/20", "ops": [{"op": "click"}],
             "text_sources": {"label": {"kind": "resource", "key": "waterskin.name"}}}]}}}
        wire["data"]["acts"] = [{"op": "click", "ctl": "n", "label": "Waterskin",
                                 "text_sources": {"label": {"kind": "resource", "key": "waterskin.name"}}}]
        action = protocol7.response(wire)["result"]["actions"][0]
        self.assertEqual("Waterskin", action["label"])
        self.assertEqual("waterskin.name", action["text_sources"]["label"]["key"])

    def test_request_summary_does_not_invent_reply_and_details_decode_exact_reply(self):
        summary = {"v": 7, "id": "lookup", "data": {"id": "a", "op": "wait", "st": "COMPLETED", "has": ["reply", "before"]}}
        self.assertNotIn("response", protocol7.response(summary, "request.get")["result"])
        reply = {"v": 7, "id": "a", "s": "run:1", "rev": "r8", "st": "completed", "data": {"scene": "game", "phase": "player_ready"}}
        summary["data"]["reply"] = reply
        self.assertEqual(protocol7.response(reply), protocol7.response(summary, "request.get")["result"]["response"])

    def test_history_metadata_keeps_actual_operation_instead_of_becoming_an_action(self):
        wire = {"v": 7, "id": "h", "data": {"items": [{"sequence": 2, "id": "a", "op": "move", "status": "COMPLETED"}],
                "next": None, "end": True, "until": 2}}
        row = protocol7.response(wire, "history.list")["result"][0]
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
                wire = {"v": 7, "id": "page", "data": {"items": [{"sequence": sequence}],
                        "until": upper, "next": sequence if more else None, "end": not more}}
                return protocol7.response(wire, op)

        client = GrowingHistory()
        rows = [row for batch in protocol7.pages(client, "history.list", limit=1) for row in batch]
        self.assertEqual([1, 2, 3], [row["sequence"] for row in rows])
        self.assertEqual(3, len(client.requests))
        self.assertNotIn("until", client.requests[0])
        self.assertEqual([3, 3], [request["until"] for request in client.requests[1:]])
        first_count = len(client.requests)
        rows = [row for batch in protocol7.pages(client, "history.list", after=3, limit=1) for row in batch]
        self.assertEqual([4, 5, 6], [row["sequence"] for row in rows])
        self.assertNotIn("until", client.requests[first_count], "A new polling cycle must capture a fresh upper bound")

    def test_large_single_request_and_response_are_collected_without_truncation(self):
        engine = 'import json,sys; r=json.loads(sys.stdin.buffer.readline()); print(json.dumps({"v":7,"id":r["id"],"st":"completed","data":{"echo":r["text"]}},ensure_ascii=False),flush=True)'
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
            self.assertEqual(7, json.loads(client.last_send_bytes)["v"])
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
