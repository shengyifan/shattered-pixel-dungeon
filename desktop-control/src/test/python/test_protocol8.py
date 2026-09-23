"""Offline protocol-8 records/action sharing checks; no game or profile I/O."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "client"))
from spdctl_client import DecodeError, decode_client_response, decode_wire_response, expand_structures
import protocol8
from historical_v6_client import decode_wire_response as decode_historical_v6


def frame(data, identifier="q", **extra):
    return {"v": 8, "id": identifier, "s": "s2", "rev": "r7", "st": "completed", "data": data, **extra}


def sample():
    return {
        "ui": {"node_templates": [{"common": {"role": "button", "enabled": True},
                                    "fields": ["id", "parent", "loc", "label", "ops", "unknown"]}],
               "nodes": [[0, "c1", "root", "bag.0", 1, [1, 0, 1], [None, False, 0]],
                         {"id": "root", "text": "Same text", "ops": []}]},
        "act_templates": [{"common": {"op": "click", "ctl": "c1"},
                           "fields": ["gestures", "label", "extra"]}],
        "acts": [[0, ["click"], "Click", {"range": [0, 1], "future": None}],
                 [0, ["long", "click"], "Long", {"range": [0, 1], "future": False}],
                 {"op": "wait"}],
        "inv_templates": [{"common": {"name": "scroll of upgrade", "desc": "Complete description"},
                           "fields": ["loc", "qty", "level", "cursed"]}],
        "inv": [[0, "bag.0", 0, None, False], {"loc": "bag.1", "name": "Other"}],
    }


class Protocol8StructuresTest(unittest.TestCase):
    def test_activity_is_bound_before_copying_referenced_operations(self):
        data = {"activity": {"rid": "original"}, "acts": [{"op": "cancel", "ctl": "c1"}],
                "ui": {"nodes": [{"id": "c1", "ops": [0]}]}}
        original = frame(data, rev="a1")
        pure = expand_structures(original)
        self.assertEqual({"op": "cancel"}, pure["data"]["ui"]["nodes"][0]["ops"][0])
        for decoded in (decode_wire_response(original).data, protocol8.expand_structures(original)["data"]):
            self.assertEqual({"op": "cancel", "rev": "a1", "rid": "original"},
                             decoded["ui"]["nodes"][0]["ops"][0])
            self.assertEqual("a1", decoded["acts"][0]["rev"])
        historic = decode_wire_response(frame({"before": data}, rev="a9")).data["before"]
        self.assertNotIn("rev", historic["ui"]["nodes"][0]["ops"][0])
        self.assertEqual("original", historic["ui"]["nodes"][0]["ops"][0]["rid"])
        self.assertEqual(data, original["data"])

    def test_decode_failure_retains_a_copied_wire_identity_and_exact_original_content(self):
        invalid = frame({"act_templates": [{"common": {}, "fields": ["op"]}], "acts": [[2, "wait"]]}, "broken")
        expected = copy.deepcopy(invalid)
        with self.assertRaises(DecodeError) as caught:
            decode_wire_response(invalid)
        error = caught.exception
        self.assertEqual(expected, error.raw)
        self.assertEqual({"id": "broken", "s": "s2"}, error.identity)
        self.assertEqual(("broken", "s2"), (error.response_id, error.scope))
        self.assertIn("$.data.acts[0][0]", str(error))
        invalid["data"]["acts"][0][1] = "changed after failure"
        self.assertEqual(expected, error.raw)

    def test_decode_failure_preserves_response_and_settle_wrapper_contexts(self):
        invalid = frame({"ui": {"node_templates": [], "nodes": [[0]]}}, "broken")
        wrappers = [
            {"controller": "response", "response": invalid, "late_responses": []},
            {"controller": "settle", "st": "completed", "rid": "original-action", "s": "s1",
             "outcome": frame({"id": "original-action", "st": "COMPLETED"}, "receipt"),
             "observation": invalid, "request": {"id": "original-action", "op": "rest"}},
        ]
        for wrapper, stage in zip(wrappers, ("response", "observation")):
            expected = copy.deepcopy(wrapper)
            with self.subTest(stage=stage), self.assertRaises(DecodeError) as caught:
                decode_client_response(wrapper)
            error = caught.exception
            self.assertEqual(invalid, error.raw)
            self.assertEqual("broken", error.response_id)
            self.assertEqual(stage, error.stage)
            self.assertEqual(expected, error.context["raw"])
            wrapper["new_mutation"] = True
            self.assertEqual(expected, error.context["raw"])

    def test_all_templates_and_action_refs_expand_without_input_key_order_dependency(self):
        source = sample()
        original = copy.deepcopy(source)
        decoded = decode_wire_response(frame(source)).data
        node = decoded["ui"]["nodes"][0]
        self.assertEqual(("c1", "root", "button", "Scroll of Upgrade"),
                         (node["id"], node["parent"], node["role"], node["label"]))
        self.assertEqual(["Long", "Click", "Long"], [op["label"] for op in node["ops"]])
        self.assertEqual([None, False, 0], node["unknown"])
        self.assertEqual(["click", "click", "wait"], [op["op"] for op in decoded["acts"]])
        self.assertTrue(all("ctl" not in op for op in node["ops"]))
        self.assertIsNone(decoded["inv"][0]["level"])
        self.assertIs(False, decoded["inv"][0]["cursed"])
        self.assertEqual(0, decoded["inv"][0]["qty"])
        self.assertNotIn("level", decoded["inv"][1])
        self.assertEqual("Complete description", decoded["inv"][0]["desc"])
        self.assertEqual(original, source)
        self.assertNotIn("act_templates", decoded)
        self.assertNotIn("inv_templates", decoded)
        self.assertNotIn("node_templates", decoded["ui"])

    def test_pure_structure_does_not_add_defaults_and_copies_every_occurrence(self):
        for value in (None, False, 0, {"unknown": "value"}):
            source = {"inv": value, "acts": value, "ui": value}
            self.assertEqual(source, expand_structures(source))
        result = expand_structures(sample())
        self.assertNotIn("equipped", result["inv"][1])
        self.assertNotIn("enabled", result["ui"]["nodes"][1])
        node = result["ui"]["nodes"][0]
        node["ops"][0]["extra"]["range"][0] = 999
        self.assertEqual(0, node["ops"][2]["extra"]["range"][0])
        self.assertEqual(0, result["acts"][1]["extra"]["range"][0])

    def test_inline_operations_protected_origins_and_sources_stay_complete(self):
        operation = {"op": "click", "ctl": "c9", "label": "External", "unknown": [None, False, 0],
                     "text_origins": {"ctl": ["external"]}, "text_sources": {"label": {"kind": "external", "value": "Raw"}}}
        source = {"acts": [operation], "ui": {"nodes": [{"id": "c9", "ops": [operation],
                  "text_diagnostics": {"ops[0].label": "clipped"}, "parent": None}]},
                  "pres": {"st": "partial", "diag": [{"field": "ui.nodes[0].ops[0].label", "code": "clipped"}]}}
        self.assertEqual(source, expand_structures(source))

    def test_frozen_snapshots_are_independent_and_never_a_current_observation(self):
        before, after = sample(), sample()
        after["inv_templates"][0]["common"]["name"] = "different item"
        before["activity"] = {"rid": "before-action"}
        after["activity"] = {"rid": "after-action"}
        result = decode_client_response(frame({"before": before, "after": after, "id": "old", "st": "COMPLETED"}))
        self.assertIsNone(result.current_frame)
        self.assertEqual("Scroll of Upgrade", result.response.data["before"]["ui"]["nodes"][0]["label"])
        self.assertEqual("Different Item", result.response.data["after"]["ui"]["nodes"][0]["label"])
        self.assertNotIn("rev", result.response.data["before"]["activity"])
        self.assertNotIn("rev", result.response.data["after"]["activity"])
        del before["acts"]
        del before["act_templates"]
        with self.assertRaisesRegex(DecodeError, "before.*ops"):
            decode_wire_response(frame({"acts": [{"op": "click", "ctl": "c1"}], "before": before}))

    def test_frozen_snapshot_cannot_borrow_an_inventory_from_enclosing_response(self):
        source = sample()
        historical = {"ui": {"nodes": [{"id": "c1", "loc": "bag.0", "label": 0}]}}
        source["before"] = historical
        with self.assertRaisesRegex(DecodeError, "before.*label"):
            decode_wire_response(frame(source))

    def test_late_response_uses_its_own_tables_without_rebinding_current_frame(self):
        first, second = sample(), sample()
        second["inv_templates"][0]["common"]["name"] = "different item"
        result = decode_client_response({"controller": "response", "response": frame(second, "now", rev="r9"),
                                        "late_responses": [{"request": {"id": "old"}, "response": frame(first, "old")}]})
        self.assertEqual("r9", result.current_frame.frame["rev"])
        self.assertEqual("Different Item", result.current_frame.data["ui"]["nodes"][0]["label"])
        self.assertEqual("Scroll of Upgrade", result.late_responses[0].response.data["ui"]["nodes"][0]["label"])

    def test_unknown_extensions_raw_reply_and_sources_are_not_template_contexts(self):
        poison = {"inv_templates": False, "acts": [[999]], "ui": {"node_shapes": None}}
        for key in ("unknown", "raw", "reply", "schema", "text_sources", "text_origins", "text_diagnostics", "preserved_cells"):
            with self.subTest(key=key):
                source = {key: poison}
                self.assertEqual(source, expand_structures(source))

    def test_invalid_templates_are_rejected_even_if_unreferenced(self):
        invalid = [None, False, [], {}, {"common": {}}, {"fields": []},
                   {"common": {}, "fields": [], "unknown": 0},
                   {"common": [], "fields": []}, {"common": {}, "fields": None},
                   {"common": {}, "fields": ["id", "id"]},
                   {"common": {}, "fields": [0]}, {"common": {"id": None}, "fields": ["id"]}]
        for template in invalid:
            with self.subTest(template=template), self.assertRaises(DecodeError):
                expand_structures({"inv_templates": [template], "inv": []})
        for table in (None, False, {}, "0"):
            with self.subTest(table=table), self.assertRaises(DecodeError):
                expand_structures({"act_templates": table, "acts": []})
        with self.assertRaises(DecodeError):
            expand_structures({"inv_templates": []})

    def test_invalid_rows_fail_at_each_supported_list(self):
        for key, table in (("inv", "inv_templates"), ("acts", "act_templates"), ("nodes", "node_templates")):
            for row in ([], [True, "x"], [0.0, "x"], [-1, "x"], [1, "x"], [0], [0, "x", None], None, False, 0):
                source = {table: [{"common": {}, "fields": ["id"]}], key: [row]}
                if key == "nodes":
                    source = {"ui": source}
                with self.subTest(key=key, row=row), self.assertRaises(DecodeError):
                    expand_structures(source)

    def test_reference_missing_mismatched_or_invalid_never_falls_back(self):
        for reference in (-1, 1, True, False, 0.0, "0", [], {"act": 0}):
            with self.subTest(reference=reference), self.assertRaises(DecodeError):
                expand_structures({"acts": [{"op": "click", "ctl": "c1"}],
                                   "ui": {"nodes": [{"id": "c1", "ops": [reference]}]}})
        for action in ({"op": "click"}, {"op": "click", "ctl": "c2"}, {"op": None, "ctl": "c1"}):
            with self.subTest(action=action), self.assertRaises(DecodeError):
                expand_structures({"acts": [action], "ui": {"nodes": [{"id": "c1", "ops": [0]}]}})
        with self.assertRaises(DecodeError):
            expand_structures({"ui": {"nodes": [{"id": "c1", "ops": [0]}]}})
        protected = {"op": "click", "ctl": "c1", "text_origins": {"ctl": ["external"]}}
        with self.assertRaisesRegex(DecodeError, "protected action"):
            expand_structures({"acts": [protected], "ui": {"nodes": [{"id": "c1", "ops": [0]}]}})

    def test_old_wire_and_old_structures_are_rejected_not_compatibly_decoded(self):
        with self.assertRaises(DecodeError):
            decode_wire_response({"v": 7, "id": "old", "st": "completed"})
        for key in ("node_shapes", "op_defs"):
            with self.subTest(key=key), self.assertRaises(DecodeError):
                decode_wire_response(frame({"ui": {key: [], "nodes": []}}))
        # Historical research is deliberately isolated from the production client.
        self.assertEqual(6, decode_historical_v6({"v": 6, "id": "old", "st": "completed"}).frame["v"])

    def test_canonical_scenario_helper_keeps_complete_actions_and_does_not_invent(self):
        expected = ["Click", "Long", None]
        result = protocol8.response(frame(sample()))["result"]
        self.assertEqual(expected, [a.get("label") for a in result["actions"]])
        no_actions = {"ui": {"nodes": [{"id": "c1", "ops": [{"op": "click"}]}]}}
        self.assertEqual([], protocol8.response(frame(no_actions))["result"]["actions"])
        self.assertEqual({"v": 8, "id": "t1.10", "op": "move", "s": "s1", "rev": "r1", "dir": "N"},
                         protocol8.request("action.execute", {"action": "move.step", "direction": "north"}, "t1.10", "s1", "r1"))


if __name__ == "__main__":
    unittest.main()
