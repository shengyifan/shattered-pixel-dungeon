"""Focused checks that the offline benchmark cannot silently forgive lost public facts."""
import unittest
from pathlib import Path
import cli5_token_benchmark as benchmark

POLICY = {"ordinary_source_kinds": ["literal", "scalar", "concat", "displayed"],
          "ordinary_source_origins": ["literal", "symbol", "scalar"]}


class TokenBenchmarkTests(unittest.TestCase):
    def test_frozen_adapter_shim_preserves_runtime_cue_shape_without_rewriting_source(self):
        path = Path(__file__).with_name("fixtures") / "protocol4_public_adapter.py"
        before = path.read_bytes()
        legacy = benchmark.load_module("cue_shape_fixture_adapter", path)
        benchmark.protect_legacy_opaque(legacy)
        snapshot = {"status": "last_rendered", "depth": 10, "map_context": "fixture:tengu",
                    "cues": [{"kind": "bomb_countdown_3", "cell": 6}]}
        decoded = legacy.state({"scene": "game", "cues": snapshot})
        self.assertEqual(snapshot, decoded["observation"]["visual_cues"])
        self.assertEqual({"cues": []}, legacy._value({"cues": []}))
        self.assertEqual(before, path.read_bytes())

    def test_complete_frame_includes_newline_and_real_utf8(self):
        self.assertEqual(b'{"text":"\xe6\xb0\xb4"}\n', benchmark.frame_bytes({"text": "水"}))

    def test_nearest_rank_percentile_and_real_regression_counts(self):
        self.assertEqual({"count": 20, "total": 210, "median": 10.5, "p95": 19, "max": 20},
                         benchmark.distribution(list(range(1, 21))))

    def test_unknown_and_external_sources_keep_semantic_protection(self):
        source = {"text": "Visible", "text_sources": {"text": {"kind": "future", "description": "AST literal"}}}
        self.assertEqual(source, benchmark.semantic_sources(source, POLICY))
        external = {"text": "Visible", "text_sources": {"text": {"kind": "literal", "origin": "external", "value": "Visible"}}}
        self.assertEqual({"text": "Visible", "text_origins": {"text": ["external"]}},
                         benchmark.semantic_sources(external, POLICY))
        partial = {"text": "Visible", "text_sources": {"text": {"kind": "literal", "origin": "literal", "visibility": "partial"}}}
        self.assertEqual(partial, benchmark.semantic_sources(partial, POLICY))

    def test_unknown_null_false_and_save_receipt_cannot_be_removed(self):
        for before, after in (({"level": None}, {}), ({"cursed": False}, {"cursed": None}), ({"cursed": False}, {"cursed": 0}),
                              ({"persistence": {"saves_during_request": [{"receipt_id": "original"}]}},
                               {"persistence": {"saves_during_request": []}})):
            with self.assertRaises(AssertionError):
                benchmark.assert_semantics(before, after, POLICY, "fixture")

    def test_only_plain_duplicate_text_is_removable_without_capture_hints(self):
        nodes = {"parent": {"id": "parent", "role": "button", "text": "Drink"},
                 "child": {"id": "child", "role": "text", "text": "Drink", "parent": "parent"},
                 "bar": {"id": "bar", "role": "health_bar", "health_pixels": 20},
                 "disabled": {"id": "disabled", "role": "text", "enabled": False}}
        self.assertTrue(benchmark.removable_historical_node(nodes["child"], nodes, []))
        self.assertFalse(benchmark.removable_historical_node(nodes["bar"], nodes, []))
        self.assertFalse(benchmark.removable_historical_node(nodes["disabled"], nodes, []))
        self.assertFalse(benchmark.removable_historical_node(nodes["child"], nodes, [{"control": "child", "action": "ui.activate"}]))
        nodes["child"]["text_origins"] = {"text": ["external"]}
        self.assertFalse(benchmark.removable_historical_node(nodes["child"], nodes, []))

    def test_diagnostic_paths_must_resolve_to_existing_wire_fields(self):
        reply = {"data": {"ui": {"nodes": [{"text": "Visible"}]}},
                 "pres": {"st": "partial", "diag": [{"field": "$.data.ui.nodes[0].text", "code": "clipped_text"}]}}
        self.assertEqual(1, len(benchmark.diagnostic_evidence(reply, POLICY)))
        reply["pres"]["diag"][0]["field"] = "$.data.ui.nodes[1].text"
        with self.assertRaises(IndexError):
            benchmark.diagnostic_evidence(reply, POLICY)


if __name__ == "__main__":
    unittest.main()
