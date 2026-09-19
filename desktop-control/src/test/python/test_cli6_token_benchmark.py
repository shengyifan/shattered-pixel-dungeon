import copy
import unittest
import cli6_token_benchmark as benchmark


class Cli6TokenBenchmarkTest(unittest.TestCase):
    def test_duplicate_request_keeps_the_same_replacement_and_text_is_not_scanned(self):
        maps = benchmark.identity_maps([{"id": "hello"}, {"id": "long-action"}, {"id": "long-action"}],
                                       [{"data": {"ui": {"nodes": [{"id": "ui-35", "text": "ui-99"}]}}}], "f" * 32)
        self.assertEqual({"hello": "f" * 32, "long-action": "t1.1"}, maps["requests"])
        self.assertEqual({"ui-35": "cz"}, maps["controls"])

    def test_semantic_comparison_allows_only_unreferenced_passive_identity(self):
        before = {"observation": {"ui": {"controls": [{"id": "c1", "role": "text", "text": "HP 8/25"}]}}, "actions": []}
        after = copy.deepcopy(before)
        after["observation"]["ui"]["controls"][0].pop("id")
        benchmark.normalize_expected_ui(before, after)
        self.assertEqual(before, after)
        for changed in ({"role": "text", "text": "HP 25/25"}, {"role": "text"}):
            original = {"observation": {"ui": {"controls": [{"id": "c1", "role": "text", "text": "HP 8/25"}]}}, "actions": []}
            with self.assertRaises(AssertionError):
                benchmark.normalize_expected_ui(original, {"observation": {"ui": {"controls": [changed]}}})

    def test_referenced_control_cannot_lose_identity(self):
        before = {"observation": {"ui": {"controls": [{"id": "c1", "role": "text", "text": "Next"}]}}, "actions": [{"control": "c1"}]}
        after = {"observation": {"ui": {"controls": [{"role": "text", "text": "Next"}]}}, "actions": [{"control": "c1"}]}
        with self.assertRaises(AssertionError):
            benchmark.normalize_expected_ui(before, after)

    def test_incremental_reference_cannot_cross_context_and_logs_are_not_events(self):
        replies = [{"s": "s1", "data": {"scene": "game", "hero": {"depth": depth}, "map": {"w": 4}}} for depth in (1, 1, 2)]
        result = benchmark.incrementality(replies, lambda value: len(benchmark.wire_bytes(value)))
        self.assertEqual(1, result["map"]["unchanged_previous_same_context"])
        self.assertIn("not proof", result["text_evidence"]["classification"])


if __name__ == "__main__":
    unittest.main()
