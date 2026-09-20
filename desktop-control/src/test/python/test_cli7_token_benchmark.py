import copy
import unittest

from cli7_token_benchmark import expand_legacy_records, normalized_frame


class Cli7TokenBenchmarkTest(unittest.TestCase):
    def test_legacy_structure_expansion_preserves_unrelated_values(self):
        frame = {"v": 6, "id": "t1.9", "data": {
            "inv": [{"loc": "backpack.0", "name": "Dart"}],
            "ui": {"node_shapes": [["id", "label", "loc", "ops", "future"]],
                   "op_defs": [[{"op": "click", "future": [None, False, 0]}]],
                   "nodes": [[0, "c1", 0, "backpack.0", 0, None]]},
            "unknown": {"ui": {"nodes": [[0, "not-a-node"]]}}
        }}
        original = copy.deepcopy(frame)
        result = expand_legacy_records(frame)
        self.assertEqual(original, frame)
        self.assertEqual(7, result["v"])
        self.assertEqual({"id": "c1", "label": 0, "loc": "backpack.0",
                          "ops": [{"op": "click", "future": [None, False, 0]}],
                          "future": None}, result["data"]["ui"]["nodes"][0])
        self.assertEqual(original["data"]["unknown"], result["data"]["unknown"])

    def test_history_tables_are_local_and_raw_evidence_is_opaque(self):
        snapshot = {"ui": {"node_shapes": [["id", "text"]], "nodes": [[0, "c1", "before"]]}}
        frame = {"v": 6, "data": {"before": snapshot, "raw": snapshot, "reply": snapshot}}
        result = expand_legacy_records(frame)
        self.assertEqual({"id": "c1", "text": "before"}, result["data"]["before"]["ui"]["nodes"][0])
        self.assertEqual(snapshot, result["data"]["raw"])
        self.assertEqual(snapshot, result["data"]["reply"])

    def test_normalization_only_removes_representation_tables(self):
        frame = {"v": 6, "data": {"acts": [{"op": "wait"}], "act_templates": [],
                                  "inv_templates": [], "ui": {"node_templates": [], "nodes": []},
                                  "raw": {"act_templates": [False]}, "future": None}}
        result = normalized_frame(frame)
        self.assertEqual({"v": 7, "data": {"acts": [{"op": "wait"}], "ui": {"nodes": []},
                                           "raw": {"act_templates": [False]}, "future": None}}, result)
        self.assertIn("act_templates", frame["data"])


if __name__ == "__main__":
    unittest.main()
