"""Offline Protocol 8 actions-only subject facts: no earlier frame is consulted."""

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "client"))
from spdctl_client import DecodeError, decode_wire_response


def actions_frame(nodes, *, identifier="actions", revision="r9"):
    return {"v": 8, "id": identifier, "s": "s2", "rev": revision, "st": "completed", "data": {
        "phase": "player_ready",
        "acts": [{"op": "click", "ctl": "item", "gestures": ["click"], "label": "Use"}],
        "ui": {"nodes": nodes},
    }}


def owners():
    return [
        {"id": "item", "loc": "backpack.4", "label": "Use", "ops": [0],
         "subject_data": {"loc": "backpack.4", "name": "worn dart", "qty": 0,
                          "available": False, "level": None, "cursed": None,
                          "shown": {"status": "0", "flags": ["last_use"]},
                          "text_sources": {"name": {"kind": "resource", "key": "items.weapon.dart.name"}},
                          "text_origins": {"name": ["resource"]},
                          "pres": {"st": "partial", "diag": [{"field": "name",
                                                              "code": "fixture_provenance"}]} }},
        {"id": "hero", "label": "Hero", "ops": [], "subject_data": {
            "cell": 12, "class": "warrior", "hp": 9, "ht": 20,
            "turn_progress": {"sweep": 0.0},
            "buffs": [{"name": "Haste", "shown": {"symbol": "buff_haste",
                                                 "counter": "0"}}]}},
        {"id": "hero-buff", "label": "Haste", "ops": [], "subject_data": {
            "name": "Haste", "icon": 12, "shown": {"symbol": "buff_haste", "progress": {
                "covered": 0, "total": 16, "basis": "displayed"}}}},
        {"id": "entity", "label": "Rat", "ops": [], "subject_data": {
            "kind": "character", "cell": 13, "name": "rat",
            "health_estimate": {"samples": [{"total": 16, "filled": 0,
                                              "with_shield": 4, "basis": "displayed"}]},
            "buffs": [{"name": "Poison", "shown": {"symbol": "buff_poison"}}]}},
        {"id": "entity-buff", "label": "Poison", "ops": [], "subject_data": {
            "name": "Poison", "icon": 55, "shown": {"symbol": "buff_poison",
                                                  "counter": "1"}}},
    ]


class ActionsSubjectDataTest(unittest.TestCase):
    def test_all_five_owner_types_are_inline_and_independent(self):
        raw = actions_frame(owners())
        original = copy.deepcopy(raw)
        decoded = decode_wire_response(raw)
        data = decoded.data
        self.assertNotIn("hero", data)
        self.assertNotIn("inv", data)
        self.assertNotIn("entities", data)
        nodes = data["ui"]["nodes"]
        self.assertEqual(5, len(nodes))
        self.assertTrue(all("subject" not in node for node in nodes))
        self.assertEqual("backpack.4", nodes[0]["subject_data"]["loc"])
        self.assertEqual(0, nodes[0]["subject_data"]["qty"])
        self.assertIs(False, nodes[0]["subject_data"]["available"])
        self.assertIsNone(nodes[0]["subject_data"]["cursed"])
        self.assertEqual("resource", nodes[0]["subject_data"]["text_sources"]["name"]["kind"])
        self.assertEqual(["resource"], nodes[0]["subject_data"]["text_origins"]["name"])
        self.assertEqual("fixture_provenance", nodes[0]["subject_data"]["pres"]["diag"][0]["code"])
        self.assertEqual(0.0, nodes[1]["subject_data"]["turn_progress"]["sweep"])
        self.assertEqual(0, nodes[2]["subject_data"]["shown"]["progress"]["covered"])
        self.assertEqual(4, nodes[3]["subject_data"]["health_estimate"]["samples"][0]["with_shield"])
        self.assertEqual(55, nodes[4]["subject_data"]["icon"], "Native public buff icon index is allowed")
        self.assertEqual([{"op": "click", "gestures": ["click"], "label": "Use"}], nodes[0]["ops"])
        self.assertEqual(original, raw)
        self.assertEqual(original, decoded.raw)

    def test_same_node_identity_uses_each_actions_frame_own_facts(self):
        first = decode_wire_response(actions_frame(owners(), identifier="first"))
        changed = owners()
        changed[0]["subject_data"]["name"] = "new item"
        changed[0]["subject_data"]["qty"] = 2
        changed[3]["subject_data"]["health_estimate"]["samples"][0]["filled"] = 3
        second = decode_wire_response(actions_frame(changed, identifier="second"))
        self.assertEqual("worn dart", first.data["ui"]["nodes"][0]["subject_data"]["name"])
        self.assertEqual("new item", second.data["ui"]["nodes"][0]["subject_data"]["name"])
        self.assertEqual(0, first.data["ui"]["nodes"][0]["subject_data"]["qty"])
        self.assertEqual(2, second.data["ui"]["nodes"][0]["subject_data"]["qty"])
        self.assertEqual(0, first.data["ui"]["nodes"][3]["subject_data"]["health_estimate"]["samples"][0]["filled"])

    def test_unresolved_null_requires_local_partial_and_preserves_inline_facts(self):
        unresolved = {"id": "item", "loc": "backpack.4", "label": "Captured choice",
                      "shown": {"counter": "0"}, "ops": [0], "subject_data": None,
                      "pres": {"st": "partial", "diag": [{"field": "subject_data",
                                                        "code": "unresolved_subject"}]}}
        decoded = decode_wire_response(actions_frame([unresolved])).data["ui"]["nodes"][0]
        self.assertIsNone(decoded["subject_data"])
        self.assertEqual("Captured choice", decoded["label"])
        self.assertEqual("0", decoded["shown"]["counter"])
        self.assertEqual("backpack.4", decoded["loc"])
        self.assertEqual("unresolved_subject", decoded["pres"]["diag"][0]["code"])

        for bad in (
            {**unresolved, "pres": None},
            {**unresolved, "pres": {"st": "partial", "diag": []}},
            {**unresolved, "pres": {"st": "complete", "diag": [{"field": "subject_data",
                                                                   "code": "unresolved_subject"}]}},
            {**unresolved, "subject_data": False},
        ):
            with self.subTest(bad=bad), self.assertRaises(DecodeError):
                decode_wire_response(actions_frame([bad]))

    def test_references_numeric_labels_and_malformed_nested_facts_fail(self):
        cases = [
            {**owners()[0], "subject": {"kind": "item", "loc": "backpack.4"}},
            {**owners()[0], "loc": "backpack.5"},
            {**owners()[0], "label": 0},
            {**owners()[0], "subject_data": {"loc": "backpack.4", "shown": {"progress": {
                "covered": 4, "total": 0, "basis": "displayed"}}}},
            {**owners()[3], "subject_data": {"kind": "character", "health_estimate": {
                "samples": [{"total": 16, "filled": 8, "with_shield": 4, "basis": "displayed"}]}}},
        ]
        for bad in cases:
            with self.subTest(id=bad["id"], keys=list(bad)), self.assertRaises(DecodeError):
                decode_wire_response(actions_frame([bad]))


if __name__ == "__main__":
    unittest.main()
