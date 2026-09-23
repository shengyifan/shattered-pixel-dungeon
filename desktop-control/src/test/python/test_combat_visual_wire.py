"""Offline Protocol 8 semantic-observation checks; no game or profile access."""

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "client"))
from spdctl_client import DecodeError, decode_client_response, decode_wire_response, expand_structures


def snapshot(prefix="c", *, item_name="visible item"):
    """Every snapshot owns its tables, entity order, and semantic bindings."""
    return {
        "act_templates": [{"common": {"op": "click", "gestures": ["click", "long"]},
                           "fields": ["ctl", "label"]}],
        "acts": [[0, prefix + str(index), "Choice " + str(index)] for index in range(5)],
        "inv_templates": [{"common": {"name": item_name, "desc": "Exact public description",
                                      "shown": {"status": "1", "extra": "14?", "level": "+0",
                                                "charge": {"fraction": 0.0, "basis": "displayed_gradient"},
                                                "future": [None, False, 0]}},
                           "fields": ["loc"]}],
        "inv": [[0, "bag.0"]],
        "hero": {"cell": 0, "turn_progress": {"sweep": 0.25},
                 "buffs": [{"name": "Haste", "shown": {"symbol": "haste", "variant": "active",
                                                        "counter": "3", "progress": {
                                                            "covered": 0, "total": 16, "basis": "displayed"}}}]},
        "entity_defs": [{"kind": "character", "name": "rat", "buffs": [
            {"name": "Poison", "shown": {"symbol": "poison", "counter": "0"}}],
            "health_estimate": {"samples": [{"total": 16, "filled": 0,
                                              "with_shield": 4, "basis": "displayed"}]}},
                        {"kind": "container", "item": {"loc": "ground.7", "name": "seed",
                                                         "shown": {"status": None, "future_flag": False}}}],
        "entities": [{"cell": 9, "def": 0}, {"cell": 7, "def": 1}],
        "ui": {
            "feedback": [{"kind": "log", "text": "The rat is poisoned.", "tone": "warning", "id": "f1"},
                         {"kind": "floating", "text": "0", "cell": 0, "id": "f2"},
                         {"kind": "banner", "text": "Victory", "id": "f3"}],
            "node_templates": [{"common": {"role": "button"},
                                "fields": ["id", "parent", "subject", "label", "ops"]}],
            "nodes": [[0, prefix + "0", "root", {"kind": "item", "loc": "bag.0"}, 1, [0]],
                      [0, prefix + "1", "root", {"kind": "hero_buff", "index": 0}, "Haste", [1]],
                      [0, prefix + "2", "root", {"kind": "entity", "index": 0}, "Rat", [2]],
                      [0, prefix + "3", "root", {"kind": "entity_buff", "entity": 0,
                                                       "index": 0}, "Poison", [3]],
                      {"id": prefix + "4", "role": "button", "label": "Standalone action",
                       "text": "Use", "spans": [{"text": "Use", "tone": "warning"}], "ops": [4]},
                      {"id": prefix + "5", "role": "entry",
                       "subject": {"kind": "item", "loc": "ground.7"},
                       "label": 0, "ops": None}],
        },
        "cues": {"status": "last_observed", "map_context": "m1", "cues": [
            {"kind": "pylon_lightning", "cell": 9, "appearance": {"style": "lightning",
                                                               "partial": False}}]},
    }


def frame(data, identifier="q", revision="r1"):
    return {"v": 8, "id": identifier, "s": "s1", "rev": revision,
            "st": "completed", "data": data}


class SemanticObservationWireTest(unittest.TestCase):
    def test_semantic_facts_and_subjects_survive_templates_and_local_defaults(self):
        for view in ("play", "full", "src"):
            data = snapshot()
            if view != "play":
                data["inv_templates"][0]["common"].update(qty=1, equipped=False,
                                                            available=True)
                data["ui"]["node_templates"][0]["common"].update(enabled=True, dimmed=False)
            if view == "src":
                data["ui"]["nodes"][4]["text_sources"] = {
                    "text": {"kind": "literal", "origin": "external", "value": "Use"}}
            source = frame(data)
            untouched = copy.deepcopy(source)
            with self.subTest(view=view):
                decoded = decode_wire_response(source)
                body = decoded.data
                self.assertEqual(0.25, body["hero"]["turn_progress"]["sweep"])
                self.assertEqual(0, body["hero"]["buffs"][0]["shown"]["progress"]["covered"])
                self.assertEqual([None, False, 0], body["inv"][0]["shown"]["future"])
                self.assertEqual(0.0, body["inv"][0]["shown"]["charge"]["fraction"])
                self.assertIsNone(body["entities"][1]["item"]["shown"]["status"])
                self.assertIs(False, body["entities"][1]["item"]["shown"]["future_flag"])
                self.assertEqual(4, body["entities"][0]["health_estimate"]["samples"][0]["with_shield"])
                self.assertEqual("last_observed", body["cues"]["status"])
                self.assertEqual(["log", "floating", "banner"],
                                 [entry["kind"] for entry in body["ui"]["feedback"]])
                nodes = body["ui"]["nodes"]
                self.assertEqual({"kind": "item", "loc": "bag.0"}, nodes[0]["subject"])
                self.assertEqual("Visible Item", nodes[0]["label"])
                self.assertEqual({"kind": "entity_buff", "entity": 0, "index": 0},
                                 nodes[3]["subject"])
                self.assertNotIn("subject", nodes[4])
                self.assertEqual("Standalone action", nodes[4]["label"])
                self.assertEqual("warning", nodes[4]["spans"][0]["tone"])
                self.assertEqual("Choice 4", nodes[4]["ops"][0]["label"])
                self.assertIsNone(nodes[5]["ops"])
                self.assertEqual("seed", nodes[5]["label"])
                self.assertEqual(untouched, source)
                self.assertEqual(untouched, decoded.raw)
                body["hero"]["turn_progress"]["sweep"] = 0.99
                self.assertEqual(untouched, decoded.raw)

    def test_missing_null_false_and_zero_are_distinct(self):
        data = snapshot()
        del data["hero"]["turn_progress"]
        data["hero"]["buffs"][0]["shown"] = None
        data["entities"][0] = {"cell": 9, "kind": "character", "buffs": [{"shown": None}],
                               "health_estimate": None}
        data["ui"]["feedback"] = [{"kind": "floating", "text": None, "cell": 0,
                                     "future_flag": False}]
        decoded = decode_wire_response(frame(data)).data
        self.assertNotIn("turn_progress", decoded["hero"])
        self.assertIsNone(decoded["hero"]["buffs"][0]["shown"])
        self.assertIsNone(decoded["entities"][0]["health_estimate"])
        self.assertIsNone(decoded["ui"]["feedback"][0]["text"])
        self.assertEqual(0, decoded["ui"]["feedback"][0]["cell"])
        self.assertIs(False, decoded["ui"]["feedback"][0]["future_flag"])

    def test_subjects_resolve_against_expanded_same_frame_arrays_only(self):
        source = snapshot()
        for wrong in (True, -1, 2, 0.0, None):
            data = copy.deepcopy(source)
            data["ui"]["nodes"][2][3] = {"kind": "entity", "index": wrong}
            with self.subTest(index=wrong), self.assertRaises(DecodeError):
                decode_wire_response(frame(data))
        for subject in ({"kind": "item", "loc": "elsewhere"},
                        {"kind": "item", "loc": False},
                        {"kind": "hero_buff", "index": 1},
                        {"kind": "entity_buff", "entity": 1, "index": 0},
                        {"kind": "unknown", "index": 0}):
            data = copy.deepcopy(source)
            data["ui"]["nodes"][0][3] = subject
            with self.subTest(subject=subject), self.assertRaises(DecodeError):
                decode_wire_response(frame(data))
        duplicate = copy.deepcopy(source)
        duplicate["entities"][1] = {"cell": 7, "kind": "container",
                                    "item": {"loc": "bag.0", "name": "duplicate"}}
        with self.assertRaisesRegex(DecodeError, "unique same-frame"):
            decode_wire_response(frame(duplicate))
        conflicting = copy.deepcopy(source)
        conflicting["ui"]["nodes"][4].update(loc="bag.0", subject={"kind": "item", "loc": "ground.7"})
        with self.assertRaisesRegex(DecodeError, "contradicts"):
            decode_wire_response(frame(conflicting))

    def test_health_progress_feedback_and_shown_types_fail_closed(self):
        mutations = [
            (lambda d: d["hero"].update(turn_progress={"sweep": True}), "turn_progress"),
            (lambda d: d["hero"].update(turn_progress={"sweep": 1.5}), "turn_progress"),
            (lambda d: d["hero"]["buffs"][0]["shown"]["progress"].update(total=0), "progress"),
            (lambda d: d["inv_templates"][0]["common"]["shown"]["charge"].update(fraction=False), "charge"),
            (lambda d: d["entity_defs"][0]["health_estimate"]["samples"][0].update(
                with_shield=17), "health_estimate"),
            (lambda d: d["ui"].update(feedback=[{"kind": "floating", "cell": False}]), "feedback"),
        ]
        for mutate, pattern in mutations:
            data = snapshot()
            mutate(data)
            with self.subTest(pattern=pattern), self.assertRaisesRegex(DecodeError, pattern):
                decode_wire_response(frame(data))

    def test_glow_and_world_status_are_named_semantic_facts(self):
        data = snapshot()
        data["inv_templates"][0]["common"]["shown"]["glow"] = {
            "variant": "orange", "kind": "explosive_heat", "stage": "warm"}
        data["inv_templates"][0]["common"]["shown"]["broken_seal"] = True
        data["inv_templates"][0]["common"]["shown"]["nature_powered"] = True
        data["cues"]["cues"].extend([
            {"kind": "item_glow", "cell": 7, "appearance": {"variant": "blue"}},
            {"kind": "ward_state", "cell": 5, "appearance": {
                "tier": 2, "charge": {"fraction": 0.0, "basis": "displayed_brightness"}}},
            {"kind": "statue_armor", "cell": 6, "appearance": {"tier": 1}},
            {"kind": "item_status", "cell": 7, "appearance": {"lit_candle": True}},
            {"kind": "missile_projectile", "cell": 8,
             "appearance": {"symbol": "item_spirit_arrow", "nature_powered": True}},
        ])
        decoded = decode_wire_response(frame(data)).data
        self.assertEqual("warm", decoded["inv"][0]["shown"]["glow"]["stage"])
        self.assertEqual(0.0, decoded["cues"]["cues"][2]["appearance"]["charge"]["fraction"])
        self.assertIs(True, decoded["inv"][0]["shown"]["broken_seal"])
        self.assertIs(True, decoded["inv"][0]["shown"]["nature_powered"])
        self.assertIs(True, decoded["cues"]["cues"][4]["appearance"]["lit_candle"])
        self.assertIs(True, decoded["cues"]["cues"][5]["appearance"]["nature_powered"])
        invalid = copy.deepcopy(data)
        invalid["cues"]["cues"][2]["appearance"]["charge"]["basis"] = "hidden_duration"
        with self.assertRaisesRegex(DecodeError, "displayed_brightness"):
            decode_wire_response(frame(invalid))
        invalid = copy.deepcopy(data)
        invalid["cues"]["cues"][4]["appearance"]["lit_candle"] = 1
        with self.assertRaisesRegex(DecodeError, "lit_candle"):
            decode_wire_response(frame(invalid))
        invalid = copy.deepcopy(data)
        invalid["cues"]["cues"][5]["appearance"]["nature_powered"] = 1
        with self.assertRaisesRegex(DecodeError, "nature_powered"):
            decode_wire_response(frame(invalid))

    def test_same_counter_text_keeps_shield_and_cooldown_meaning_distinct(self):
        data = snapshot()
        data["hero"]["buffs"] = [
            {"name": "Warrior Shield", "shown": {"counter": "2", "counter_kind": "shield"}},
            {"name": "Warrior Shield", "shown": {"counter": "2", "counter_kind": "cooldown"}},
            {"name": "Warrior Shield", "shown": {"progress_kind": "cooldown", "progress": {
                "covered": 0, "total": 16, "basis": "displayed"}}},
        ]
        decoded = decode_wire_response(frame(data)).data["hero"]["buffs"]
        self.assertEqual(["2", "2"], [buff["shown"]["counter"] for buff in decoded[:2]])
        self.assertEqual(["shield", "cooldown"],
                         [buff["shown"]["counter_kind"] for buff in decoded[:2]])
        self.assertNotIn("counter", decoded[2]["shown"])
        self.assertEqual("cooldown", decoded[2]["shown"]["progress_kind"])
        for wrong in ("unknown", [], 0):
            invalid = copy.deepcopy(data)
            invalid["hero"]["buffs"][0]["shown"]["counter_kind"] = wrong
            with self.subTest(wrong=wrong), self.assertRaisesRegex(DecodeError, "counter_kind"):
                decode_wire_response(frame(invalid))

    def test_numeric_special_status_is_not_inferred_from_equal_quantity(self):
        data = snapshot()
        item = data["inv_templates"][0]["common"]
        item["qty"] = 1
        item["shown"]["status"] = "1"
        item["shown"].pop("status_kind", None)  # An unclassified special status must survive.
        decoded = decode_wire_response(frame(data)).data["inv"][0]
        self.assertEqual(1, decoded["qty"])
        self.assertEqual("1", decoded["shown"]["status"])
        self.assertNotIn("status_kind", decoded["shown"])
        classified = copy.deepcopy(data)
        classified["inv_templates"][0]["common"]["shown"]["status_kind"] = "quantity"
        shown = decode_wire_response(frame(classified)).data["inv"][0]["shown"]
        self.assertEqual("quantity", shown["status_kind"])
        for wrong in (False, 1, [], "special"):
            malformed = copy.deepcopy(classified)
            malformed["inv_templates"][0]["common"]["shown"]["status_kind"] = wrong
            with self.subTest(wrong=wrong), self.assertRaisesRegex(DecodeError, "status_kind"):
                decode_wire_response(frame(malformed))

    def test_unknown_ward_or_statue_tier_requires_local_unmapped_partial(self):
        for kind in ("ward_state", "statue_armor"):
            data = snapshot()
            indicator = "ward_form" if kind == "ward_state" else "statue_armor"
            fallback = {"unmapped_indicator": True, "pres": {"st": "partial", "diag": [
                {"field": "tier", "code": "unmapped_indicator", "indicator": indicator}]}}
            data["cues"]["cues"].append({"kind": kind, "cell": 5, "appearance": fallback})
            decoded = decode_wire_response(frame(data)).data["cues"]["cues"][-1]
            self.assertEqual(fallback, decoded["appearance"])
            for wrong in ({}, {"unmapped_indicator": True},
                          {"pres": {"st": "partial", "diag": [{"field": "tier",
                                                                 "code": "unmapped_indicator", "indicator": indicator}]}},
                          {"unmapped_indicator": True, "pres": {"st": "partial", "diag": [
                              {"field": "tier", "code": "unmapped_indicator"}]}},
                          {"unmapped_indicator": True, "pres": {"st": "partial", "diag": [
                              {"field": "tier", "code": indicator}]}},
                          {**fallback, "tier": False},
                          {**fallback, "tier": -1}):
                bad = copy.deepcopy(data)
                bad["cues"]["cues"][-1]["appearance"] = wrong
                with self.subTest(kind=kind, wrong=wrong), self.assertRaises(DecodeError):
                    decode_wire_response(frame(bad))

    def test_frozen_snapshots_own_tables_and_raw_reply_stay_opaque(self):
        before, after = snapshot("b"), snapshot("a", item_name="different item")
        before.update(s="s2", rev="r2")
        after.update(s="s3", rev="r3")
        after["hero"]["turn_progress"] = {"sweep": 0.0}
        after["entity_defs"][0]["health_estimate"]["samples"][0]["filled"] = 2
        poison = {"v": 7, "inv_templates": False, "acts": [[999]],
                  "ui": {"node_templates": None}, "appearance": {"atlas": "old"}}
        source = frame({"before": before, "after": after, "raw": poison, "reply": poison},
                       revision="r99")
        original = copy.deepcopy(source)
        decoded = decode_client_response(source)
        self.assertIsNone(decoded.current_frame)
        body = decoded.response.data
        self.assertEqual("Visible Item", body["before"]["ui"]["nodes"][0]["label"])
        self.assertEqual("Different Item", body["after"]["ui"]["nodes"][0]["label"])
        self.assertEqual(("r2", "r3"), (body["before"]["rev"], body["after"]["rev"]))
        self.assertEqual((0.25, 0.0), (body["before"]["hero"]["turn_progress"]["sweep"],
                                       body["after"]["hero"]["turn_progress"]["sweep"]))
        self.assertEqual(poison, body["raw"])
        self.assertEqual(poison, body["reply"])
        body["after"]["entities"][0]["health_estimate"]["samples"][0]["filled"] = 8
        self.assertEqual(0, body["before"]["entities"][0]["health_estimate"]["samples"][0]["filled"])
        self.assertEqual(original, source)
        self.assertEqual(original, decoded.response.raw)

        broken = snapshot()
        nested = snapshot("child")
        del nested["act_templates"]
        del nested["acts"]
        broken["before"] = nested
        with self.assertRaisesRegex(DecodeError, r"before.*ops"):
            decode_wire_response(frame(broken))

    def test_source_asts_and_diagnostic_events_remain_opaque(self):
        ast = {"kind": "literal", "origin": "external", "value": "旅人 🗡",
               "future": {"inv_templates": False, "nodes": [[999]]}}
        source = frame(snapshot())
        source["data"]["ui"]["nodes"][4]["text_sources"] = {"text": ast}
        source["pres"] = {"st": "partial", "diag": [{"field": "$.data.ui.nodes[4].text",
                                                     "code": "clipped_text"}]}
        source["data"]["items"] = [
            {"kind": "game.feedback", "sequence": 1,
             "data": {"entries": [{"kind": "floating", "text": "1", "cell": 0}]}},
            {"kind": "game.feedback", "sequence": 2,
             "data": {"entries": [{"kind": "floating", "text": "1", "cell": 0}]}},
        ]
        original = copy.deepcopy(source)
        decoded = decode_wire_response(source)
        self.assertEqual(ast, decoded.data["ui"]["nodes"][4]["text_sources"]["text"])
        self.assertEqual(source["pres"], decoded.frame["pres"])
        self.assertEqual(source["data"]["items"], decoded.data["items"])
        self.assertEqual(original, source)
        self.assertEqual(ast, expand_structures(source)["data"]["ui"]["nodes"][4]["text_sources"]["text"])

    def test_retired_drawing_fields_fail_on_semantic_surfaces_but_not_opaque_history(self):
        mutations = [
            lambda data: data["cues"].update(metrics=[]),
            lambda data: data["cues"]["cues"][0]["appearance"].update(opacity=0.5),
            lambda data: data["ui"]["nodes"][4].update(color=0xFF8800),
            lambda data: data["ui"]["nodes"][4].update(icon={"atlas": "items", "frame_pixels": [0, 0, 1, 1]}),
            lambda data: data["ui"]["nodes"][4].update(styles=[{"text": "Use", "color": 0xFF8800}]),
            lambda data: data["ui"]["feedback"][0].update(alpha=0.5),
        ]
        for index, mutate in enumerate(mutations):
            data = snapshot()
            mutate(data)
            with self.subTest(case=index), self.assertRaisesRegex(DecodeError, "retired"):
                decode_wire_response(frame(data))


if __name__ == "__main__":
    unittest.main()
