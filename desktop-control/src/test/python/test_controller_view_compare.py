"""Offline orchestration checks for same-frozen-frame packaged codec replay."""
import copy
import unittest
from unittest.mock import patch

from controller_package_smoke import lossless_views_check, normalized_public_frame


def frame():
    return {"v": 8, "id": "t1.7", "s": "s1", "rev": "r1", "st": "completed", "data": {
        "hero": {"talents": [{"name": "Unspent", "points": 0, "desc": "Full description"}],
                 "turn_progress": {"sweep": 0.25}, "buffs": [
                     {"name": "Haste", "shown": {"counter": "0"}}]},
        "inv": [{"loc": "bag.0", "name": "Known item", "desc": "A public warning",
                 "shown": {"status": "1", "flags": ["equipped"]}}],
        "entities": [{"kind": "character", "cell": 6, "name": "Rat",
                      "health_estimate": {"samples": [{"total": 16, "filled": 3,
                                                        "with_shield": 4, "basis": "displayed"}]}}],
        "acts": [{"op": "wait"}], "ui": {"nodes": [{"id": "c1", "text": "1",
            "subject": {"kind": "item", "loc": "bag.0"},
            "icon": {"symbol": "potion"}, "future": [None, False, 0]}],
            "feedback": [{"kind": "log", "text": "A warning", "tone": "warning"},
                         {"kind": "floating", "text": "1", "cell": 0,
                          "id": "f2", "occurred_at": "2026-09-23T00:00:00.123456789Z"}]},
        "cues": {"status": "last_observed", "map_context": "m1", "cues": [
            {"kind": "sprite_state", "cell": 6,
             "appearance": {"style": "golden_glow", "paused": True}}]}}}


class FakeClient:
    def __init__(self, source):
        self.source = source
        self.intents = []
        self.installed = []

    def request(self, intent):
        self.intents.append(copy.deepcopy(intent))
        return self.source

    @staticmethod
    def unwrap(value):
        return value

    def install(self, reply, operation):
        self.installed.append((reply, operation))


def replay_fixture(source):
    return {mode: copy.deepcopy(source) for mode in ("src", "full", "play")}


class ControllerViewCompareTest(unittest.TestCase):
    def check(self, source=None, replayed=None, side_effect=None):
        source = frame() if source is None else source
        client = FakeClient(source)
        output = replay_fixture(source) if replayed is None else replayed
        with patch("controller_package_smoke.replay_packaged_views", side_effect=side_effect,
                   return_value=(output, {"classification": "offline packaged codec component replay; host JVM; no game process"})) as replay:
            result = lossless_views_check(client)
        return result, client, replay

    def test_exactly_one_live_source_capture_is_replayed_without_waiting_for_animation(self):
        source = frame()
        original = copy.deepcopy(source)
        result, client, replay = self.check(source)
        self.assertEqual([{"op": "state", "view": "full", "src": True}], client.intents)
        self.assertEqual([(source, "state")], client.installed)
        self.assertEqual(1, replay.call_count)
        self.assertEqual(source["data"]["cues"], replay.call_args.args[1]["data"]["cues"])
        self.assertTrue(result["same_frozen_input"])
        self.assertTrue(result["exact_src_full_reconstruction"])
        self.assertTrue(result["semantic_facts_and_occurrences_preserved"])
        self.assertEqual("t1.7", result["source_request_id"])
        self.assertIn("host JVM", result["component_replay"]["classification"])
        self.assertEqual(original, source)

    def test_exact_source_reconstruction_precedes_any_play_full_equality_claim(self):
        source = frame()
        replayed = replay_fixture(source)
        for mode in replayed:
            replayed[mode]["data"]["cues"]["cues"][0]["appearance"]["style"] = "icy"
        with self.assertRaisesRegex(AssertionError, "Frozen src/full reconstruction differs"):
            self.check(source, replayed)

    def test_semantic_and_occurrence_differences_are_never_normalized_away(self):
        changes = [(("cues", "cues", 0, "appearance", "style"), "icy"),
                   (("cues", "cues", 0, "cell"), 7),
                   (("hero", "turn_progress", "sweep"), 0.5),
                   (("entities", 0, "health_estimate", "samples", 0, "with_shield"), 5),
                   (("ui", "feedback", 1, "occurred_at"), "2026-09-23T00:00:01Z"),
                   (("ui", "nodes", 0, "icon", "symbol"), "scroll"),
                   (("inv", 0, "shown", "status"), "2")]
        for path, replacement in changes:
            source = frame()
            replayed = replay_fixture(source)
            target = replayed["play"]["data"]
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = replacement
            with self.subTest(path=path), self.assertRaisesRegex(AssertionError, "Same frozen packaged play/full"):
                self.check(source, replayed)

    def test_descriptions_protected_origins_and_unknown_values_remain_mandatory(self):
        source = frame()
        source["data"]["ui"]["nodes"][0]["text_origins"] = {"text": ["external"]}
        paths = [("inv", 0, "desc"), ("hero", "talents", 0, "desc"),
                 ("ui", "nodes", 0, "text_origins"), ("ui", "nodes", 0, "future")]
        for path in paths:
            replayed = replay_fixture(source)
            target = replayed["play"]["data"]
            for key in path[:-1]:
                target = target[key]
            del target[path[-1]]
            with self.subTest(path=path), self.assertRaisesRegex(AssertionError, "Same frozen packaged play/full"):
                self.check(source, replayed)

    def test_complete_source_asts_and_partial_diagnostics_must_reconstruct_exactly(self):
        source = frame()
        source["data"]["ui"]["nodes"][0]["text_sources"] = {
            "text": {"kind": "literal", "origin": "external", "value": "旅人 🗡", "future": [None, False, 0]}}
        source["pres"] = {"st": "partial", "diag": [{"field": "$.data.ui.nodes[0].text", "code": "clipped_text", "detail": "keep"}]}
        for branch in ("source", "diagnostic"):
            replayed = replay_fixture(source)
            if branch == "source":
                replayed["src"]["data"]["ui"]["nodes"][0]["text_sources"]["text"].pop("future")
            else:
                replayed["src"]["pres"]["diag"][0].pop("detail")
            with self.subTest(branch=branch), self.assertRaisesRegex(AssertionError, "Frozen src/full reconstruction differs"):
                self.check(source, replayed)

    def test_numeric_types_and_action_order_are_part_of_comparison(self):
        source = frame()
        source["data"]["acts"] = [{"op": "wait"}, {"op": "save"}]
        for changed in ("bool", "order"):
            replayed = replay_fixture(source)
            if changed == "bool":
                replayed["play"]["data"]["ui"]["nodes"][0]["future"][1] = 0
            else:
                replayed["play"]["data"]["acts"].reverse()
            with self.subTest(changed=changed), self.assertRaisesRegex(AssertionError, "Same frozen packaged play/full"):
                self.check(source, replayed)

    def test_mutating_adapter_is_rejected_before_comparison(self):
        def mutate(client, frozen):
            frozen["data"]["cues"]["cues"][0]["appearance"]["style"] = "changed"
            return replay_fixture(frozen), {}
        with self.assertRaisesRegex(AssertionError, "adapter changed its frozen input"):
            self.check(side_effect=mutate)

    def test_only_redundant_map_encoding_is_removed_from_canonical_public_copy(self):
        source = frame()
        source["data"]["map"] = {"w": 2, "h": 1, "types": [{"terrain": 1, "name": "Floor", "desc": "Full text"}],
                                 "rows": [[0, 0, "00", "v"]], "env": {}, "future": {"metrics_at": "keep exactly"}}
        original = copy.deepcopy(source)
        decoded = normalized_public_frame(source)
        dungeon = decoded["data"]["map"]
        self.assertNotIn("rows", dungeon)
        self.assertEqual(2, len(dungeon["cells"]))
        self.assertEqual("Full text", dungeon["cells"][0]["desc"])
        self.assertEqual({"metrics_at": "keep exactly"}, dungeon["future"])
        self.assertEqual(original, source)


if __name__ == "__main__":
    unittest.main()
