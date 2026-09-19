import copy
import unittest
from protocol6 import expand_structures, request, response


class Protocol6StructuresTest(unittest.TestCase):
    def test_request_requires_current_revision_and_has_only_v6_envelope(self):
        self.assertEqual({"v": 6, "id": "t2.4", "op": "move", "s": "s2", "rev": "r7", "dir": "N"},
                         request("action.execute", {"action": "move.step", "direction": "north"}, "t2.4", "s2", "r7"))
        with self.assertRaises(AssertionError):
            response({"v": 5, "id": "old", "st": "completed"})

    def test_each_frame_decodes_shapes_ops_labels_and_resource_values(self):
        wire = {"v": 6, "id": "t2.4", "s": "s2", "rev": "r7", "st": "completed", "data": {
            "inv": [{"loc": "bag.0", "name": "scroll of upgrade"}],
            "ui": {"node_shapes": [["id", "role", "loc", "label", "ops", "display", "unknown"]],
                   "op_defs": [[{"op": "click", "gestures": ["click", "long"]}]],
                   "nodes": [[0, "c1", "button", "bag.0", 1, 0, {"status": "0/20", "extra": "14?"}, [None, False, 0]]]},
            "cues": {"map_context": "m2", "cues": [{"kind": "boss_warning", "cell": 12}]}}}
        original = copy.deepcopy(wire)
        expanded = expand_structures(wire)
        node = expanded["data"]["ui"]["nodes"][0]
        self.assertEqual("Scroll of Upgrade", node["label"])
        self.assertEqual([{"op": "click", "gestures": ["click", "long"]}], node["ops"])
        self.assertEqual([None, False, 0], node["unknown"])
        self.assertEqual({"status": "0/20", "extra": "14?"}, node["display"])
        self.assertEqual(wire["data"]["cues"], expanded["data"]["cues"])
        self.assertEqual(original, wire)
        decoded = response(wire)
        self.assertEqual("c1", decoded["result"]["actions"][0]["control"])
        self.assertEqual("Scroll of Upgrade", decoded["result"]["actions"][0]["label"])
        with self.assertRaises(AssertionError):
            expand_structures({"ui": wire["data"]["ui"]})

    def test_own_frame_inventory_order_and_unique_binding(self):
        ui = {"nodes": [{"id": "c1", "loc": "b.0", "label": 0}]}
        self.assertEqual("One", expand_structures({"inv": [{"loc": "b.0", "name": "One"}], "ui": ui})["ui"]["nodes"][0]["label"])
        self.assertEqual("Two", expand_structures({"inv": [{"loc": "b.0", "name": "Two"}], "ui": ui})["ui"]["nodes"][0]["label"])
        with self.assertRaises(AssertionError):
            expand_structures({"inv": [{"loc": "b.0", "name": "One"}, {"loc": "b.0", "name": "Two"}], "ui": ui})

    def test_complete_character_descriptors_expand_with_own_dictionary(self):
        wire = {"v": 6, "id": "q", "s": "s1", "rev": "r1", "st": "completed", "data": {
            "entity_defs": [{"kind": "character", "name": "Wraith", "alignment": "enemy", "buffs": [], "desc": "A warning"}],
            "entities": [{"cell": 2, "def": 0}, {"cell": 3, "def": 0}]}}
        entities = response(wire)["result"]["observation"]["visible_entities"]
        self.assertEqual([2, 3], [entity["cell"] for entity in entities])
        self.assertEqual("A warning", entities[0]["description"])
        self.assertEqual("enemy", entities[1]["alignment"])

    def test_activity_and_save_defaults_do_not_conflate_origin_or_receipt_identity(self):
        wire = {"s": "s2", "rev": "a3", "data": {"activity": {"kind": "rest", "rid": "t2.1"},
                "acts": [{"op": "cancel"}], "persistence": {"saves": [
                    {"sid": "p1", "src_s": "s1", "src_id": "t2.1"},
                    {"sid": "p2", "src_id": "t2.1"}], "saved": 1}}}
        expanded = expand_structures(wire)["data"]
        self.assertEqual({"op": "cancel", "rid": "t2.1", "rev": "a3"}, expanded["acts"][0])
        saves = expanded["persistence"]["saves"]
        self.assertEqual("s1", saves[0]["src_s"])
        self.assertEqual("s2", saves[1]["src_s"])
        self.assertEqual("s2", saves[0]["s"])
        self.assertEqual(saves[1], expanded["persistence"]["saved"])
        self.assertIsNot(saves[1], expanded["persistence"]["saved"])

    def test_opaque_original_payloads_and_source_paths_are_unchanged(self):
        original = {"ui": {"node_shapes": [["role"]], "nodes": [[0, "button"]]}}
        frame = {key: original for key in ("raw", "reply", "schema", "text_sources", "text_origins", "pres")}
        self.assertEqual(frame, expand_structures(frame))
        self.assertEqual({"unknown": {"node_shapes": 0, "op_defs": False}},
                         expand_structures({"unknown": {"node_shapes": 0, "op_defs": False}}))

    def test_bad_shapes_indexes_ops_labels_and_saved_fail_closed(self):
        invalid = [
            {"ui": {"nodes": [[0, "button"]]}},
            {"ui": {"node_shapes": [["role"]], "nodes": [[False, "button"]]}},
            {"ui": {"node_shapes": [["role"]], "nodes": [[0]]}},
            {"ui": {"node_shapes": [["role", "role"]], "nodes": [[0, 1, 2]]}},
            {"ui": {"op_defs": [[{"op": "click"}]], "nodes": [{"ops": -1}]}},
            {"ui": {"op_defs": [[{"op": "click"}]], "nodes": [{"ops": False}]}},
            {"ui": {"nodes": [{"label": 1, "loc": "missing"}]}},
            {"persistence": {"saves": [], "saved": 0}},
        ]
        for frame in invalid:
            with self.subTest(frame=frame), self.assertRaises(AssertionError):
                expand_structures(frame)


if __name__ == "__main__":
    unittest.main()
