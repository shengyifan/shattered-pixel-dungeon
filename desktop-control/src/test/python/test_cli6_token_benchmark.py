import copy
import unittest
import cli6_token_benchmark as benchmark


class Cli6TokenBenchmarkTest(unittest.TestCase):
    def test_duplicate_request_keeps_the_same_replacement_and_text_is_not_scanned(self):
        maps = benchmark.identity_maps([{"id": "hello"}, {"id": "long-action"}, {"id": "long-action"}],
                                       [{"data": {"ui": {"nodes": [{"id": "ui-35", "text": "ui-99"}]}}}], "f" * 32)
        self.assertEqual({"hello": "f" * 32, "long-action": "t1.1"}, maps["requests"])
        self.assertEqual({"ui-35": "cz"}, maps["controls"])

    def test_semantic_comparison_rejects_lost_unreferenced_passive_identity(self):
        before = {"observation": {"ui": {"controls": [{"id": "c1", "role": "text", "text": "HP 8/25"}]}}, "actions": []}
        after = copy.deepcopy(before)
        after["observation"]["ui"]["controls"][0].pop("id")
        with self.assertRaises(AssertionError):
            benchmark.normalize_expected_ui(before, after)
        for changed in ({"role": "text", "text": "HP 25/25"}, {"role": "text"}):
            original = {"observation": {"ui": {"controls": [{"id": "c1", "role": "text", "text": "HP 8/25"}]}}, "actions": []}
            with self.assertRaises(AssertionError):
                benchmark.normalize_expected_ui(original, {"observation": {"ui": {"controls": [changed]}}})

    def test_empty_and_duplicate_children_cannot_be_deleted_reordered_or_reparented(self):
        nodes = [{"id": "c1", "role": "button", "text": "Apply"},
                 {"id": "c2", "role": "text", "text": "Apply", "parent": "c1"},
                 {"id": "c3", "role": "text", "text": ""},
                 {"id": "c4", "role": "text", "text": None}]
        before = {"observation": {"ui": {"controls": nodes}}}
        original = copy.deepcopy(before)
        benchmark.normalize_expected_ui(before, copy.deepcopy(before))
        for removed in range(len(nodes)):
            with self.subTest(removed=removed), self.assertRaises(AssertionError):
                benchmark.normalize_expected_ui(before, {"observation": {"ui": {
                    "controls": nodes[:removed] + nodes[removed + 1:]}}})
        for changed in (list(reversed(nodes)), nodes[:1] + [{**nodes[1], "parent": "c3"}] + nodes[2:]):
            with self.assertRaises(AssertionError):
                benchmark.normalize_expected_ui(before, {"observation": {"ui": {"controls": changed}}})
        self.assertEqual(original, before)

    def test_referenced_control_cannot_lose_identity(self):
        before = {"observation": {"ui": {"controls": [{"id": "c1", "role": "text", "text": "Next"}]}}, "actions": [{"control": "c1"}]}
        after = {"observation": {"ui": {"controls": [{"role": "text", "text": "Next"}]}}, "actions": [{"control": "c1"}]}
        with self.assertRaises(AssertionError):
            benchmark.normalize_expected_ui(before, after)

    def test_play_full_comparison_only_expands_reversible_representation(self):
        full = {"v": 6, "id": "q", "st": "completed", "data": {
            "inv": [{"loc": "b.0", "name": "Wand", "qty": 1, "equipped": False, "available": True,
                     "type_known": True, "via": "click", "desc": "Cannot shoot through walls", "level": None}],
            "hero": {"talents": [{"name": "Unspent", "points": 0}]},
            "acts": [{"op": "click", "ctl": "c1", "units": "charges"}, {"op": "wait"}],
            "ui": {"modal": False, "item_info": None, "nodes": [
                {"id": "c1", "role": "button", "label": "Wand", "enabled": True, "dimmed": False},
                {"id": "c2", "role": "text", "text": "", "enabled": True, "dimmed": False}]},
            "map": {"w": 2, "h": 1, "types": [{"name": "Floor"}], "rows": [[0, 0, "00", "vv"]], "env": {}},
            "pres": {"st": "partial", "diag": [{"field": "inv[0].desc", "code": "warning", "detail": "Keep this"}]}}}
        play = copy.deepcopy(full)
        item = play["data"]["inv"][0]
        for key in ("qty", "equipped", "available", "type_known", "via"):
            item.pop(key)
        play["data"]["ui"] = {"node_shapes": [["id", "role", "label"], ["id", "role", "text"]],
                                 "nodes": [[0, "c1", "button", "Wand"], [1, "c2", "text", ""]]}
        play["data"]["map"]["rows"][0][3] = "v"
        play["data"]["map"].pop("env")
        benchmark.assert_lossless_views(full, play)
        for path in (("inv", 0, "desc"), ("hero", "talents"), ("acts", 0, "units"), ("pres", "diag", 0, "detail")):
            changed = copy.deepcopy(play)
            target = changed["data"]
            for key in path[:-1]:
                target = target[key]
            target.pop(path[-1])
            with self.subTest(path=path), self.assertRaises(AssertionError):
                benchmark.assert_lossless_views(full, changed)
        changed = copy.deepcopy(play)
        changed["data"]["acts"].reverse()
        with self.assertRaises(AssertionError):
            benchmark.assert_lossless_views(full, changed)

    def test_incremental_reference_cannot_cross_context_and_logs_are_not_events(self):
        replies = [{"s": "s1", "data": {"scene": "game", "hero": {"depth": depth}, "map": {"w": 4}}} for depth in (1, 1, 2)]
        result = benchmark.incrementality(replies, lambda value: len(benchmark.wire_bytes(value)))
        self.assertEqual(1, result["map"]["unchanged_previous_same_context"])
        self.assertIn("not proof", result["text_evidence"]["classification"])


if __name__ == "__main__":
    unittest.main()
