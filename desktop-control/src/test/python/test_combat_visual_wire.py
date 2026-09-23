"""Synthetic public CLI.7.0.1 wire values; no GUI, saves, or transport side effects."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "client"))
from spdctl_client import DecodeError, decode_client_response, decode_wire_response, expand_structures


def appearance():
    return {"atlas": "sprites/items.png", "frame_pixels": [0, 16, 16, 32],
            "texture_size": [256, 256], "tint": {"multiply": [1.0, 0.5, 0.0], "add": [0.0, 0.0, 0.0]},
            "alpha": 0.75, "angle": 0, "scale": [1, 1], "flip_horizontal": False, "flip_vertical": False,
            "layers": [{"order": 0, "atlas": "sprites/items.png"}, {"order": 1, "color": 0}]}


def snapshot(prefix="c", context="m1"):
    """Own local tables deliberately use the same index 0 in every snapshot."""
    return {
        "act_templates": [{"common": {"op": "click", "gestures": ["click", "long"]},
                           "fields": ["ctl", "label"]}],
        "acts": [[0, prefix + "0", "Visible choice"]],
        "inv_templates": [{"common": {"name": "visible item", "desc": "Exact public description"},
                           "fields": ["loc"]}],
        "inv": [[0, "bag.0"]],
        "ui": {"node_templates": [{"common": {"role": "button", "color": 0, "icon": appearance(),
                                               "icon_overlay": {"axis": "vertical", "covered_pixels": 0,
                                                                "total_pixels": 16, "measurement": "rendered_pixels"},
                                               "display": {"status": "1", "extra": "14?", "level": "+0"}},
                                    "fields": ["id", "parent", "loc", "label", "ops", "future"]}],
               "nodes": [[0, prefix + "0", "root", "bag.0", 1, [0], [None, False, 0]],
                         {"id": prefix + "1", "parent": prefix + "0", "role": "text", "text": "1",
                          "presentation": "floating_text", "color": 0, "cell": 0,
                          "icon": {"atlas": "floating_text", "index": 0}}]},
        "cues": {"st": "last_rendered", "map_context": context,
                 "cues": [{"kind": "pylon_lightning", "cell": 9, "source_cell": 0, "dir": "NE", "color": 0,
                           "opacity": 0.5, "appearance": appearance()}],
                 "metrics_at": "2026-09-23T00:00:00.123Z",
                 "metrics": [{"kind": "cell_particles", "cell": 0, "rendered_particles": 2,
                              "appearance": {"shape": "pixel", "color": 0, "alpha": 0.5, "size": [1, 2]}}]},
    }


def frame(data, identifier="q", revision="r1"):
    return {"v": 7, "id": identifier, "s": "s1", "rev": revision, "st": "completed", "data": data}


class CombatVisualWireTest(unittest.TestCase):
    def test_new_visual_values_survive_templates_and_play_full_src_defaults(self):
        for view in ("play", "full", "src"):
            data = snapshot()
            if view != "play":
                data["inv_templates"][0]["common"].update(qty=1, equipped=False, available=True)
                data["ui"]["node_templates"][0]["common"].update(enabled=True, dimmed=False)
            if view == "src":
                data["ui"]["nodes"][1]["text_sources"] = {
                    "text": {"kind": "literal", "origin": "external", "value": "1"}}
            source = frame(data)
            untouched = copy.deepcopy(source)
            with self.subTest(view=view):
                decoded = decode_wire_response(source)
                node = decoded.data["ui"]["nodes"][0]
                self.assertEqual(data["cues"], decoded.data["cues"])
                self.assertEqual(appearance(), node["icon"])
                self.assertEqual({"status": "1", "extra": "14?", "level": "+0"}, node["display"])
                self.assertEqual(0, node["icon_overlay"]["covered_pixels"])
                self.assertEqual(16, node["icon_overlay"]["total_pixels"])
                self.assertIs(type(node["color"]), int)
                self.assertEqual([None, False, 0], node["future"])
                self.assertEqual("Visible Item", node["label"])
                self.assertEqual([{"op": "click", "gestures": ["click", "long"], "label": "Visible choice"}], node["ops"])
                self.assertEqual("c0", decoded.data["ui"]["nodes"][1]["parent"])
                self.assertEqual({"atlas": "floating_text", "index": 0}, decoded.data["ui"]["nodes"][1]["icon"])
                self.assertEqual(untouched, source)
                self.assertEqual(untouched, decoded.raw)
                node["icon"]["tint"]["multiply"][0] = 999
                self.assertEqual(untouched, source)
                self.assertEqual(untouched, decoded.raw)

    def test_style_sources_partial_paths_and_unknown_extensions_remain_opaque(self):
        ast = {"kind": "literal", "origin": "external", "value": "旅人 🗡",
               "future": {"inv_templates": False, "ui": {"node_templates": None, "nodes": [[999]]}}}
        styles = [{"text": "旅人 🗡", "color": 0, "text_sources": {"text": ast}},
                  {"text": "Text unavailable", "color": 0xFF8800, "clipped": True,
                   "pres": {"st": "partial", "diag": [{"field": "text", "code": "clipped_text"}]}}]
        data = snapshot()
        data["ui"]["nodes"][1]["styles"] = styles
        data["ui"]["nodes"][1]["future_extension"] = ast["future"]
        source = frame(data)
        source["pres"] = {"st": "partial", "diag": [
            {"field": "$.data.ui.nodes[1].styles[1].text", "code": "clipped_text", "detail": [None, False, 0]}]}
        original = copy.deepcopy(source)
        result = decode_wire_response(source)
        self.assertEqual(styles, result.data["ui"]["nodes"][1]["styles"])
        self.assertEqual(ast["future"], result.data["ui"]["nodes"][1]["future_extension"])
        self.assertEqual(source["pres"], result.frame["pres"])
        self.assertEqual(original, source)
        self.assertEqual(original, result.raw)

    def test_frozen_tables_are_local_and_raw_reply_are_never_interpreted(self):
        before, after = snapshot("b", "m2"), snapshot("a", "m3")
        before.update(s="s2", rev="r2")
        after.update(s="s3", rev="r3")
        after["inv_templates"][0]["common"]["name"] = "different item"
        after["ui"]["node_templates"][0]["common"]["icon"]["alpha"] = 0.25
        after["cues"]["cues"][0]["color"] = 0xFF8800
        poison = {"v": 7, "inv_templates": False, "acts": [[999]], "ui": {"node_templates": None},
                  "styles": [{"text": "原始", "color": 0}], "direction": "north"}
        source = frame({"before": before, "after": after, "raw": poison, "reply": poison}, revision="r99")
        original = copy.deepcopy(source)
        result = decode_client_response(source)
        self.assertIsNone(result.current_frame)
        data = result.response.data
        self.assertEqual("b0", data["before"]["ui"]["nodes"][0]["id"])
        self.assertEqual("a0", data["after"]["ui"]["nodes"][0]["id"])
        self.assertEqual("Visible Item", data["before"]["ui"]["nodes"][0]["label"])
        self.assertEqual("Different Item", data["after"]["ui"]["nodes"][0]["label"])
        self.assertEqual(("r2", "r3"), (data["before"]["rev"], data["after"]["rev"]))
        self.assertEqual(before["cues"], data["before"]["cues"])
        self.assertEqual(after["cues"], data["after"]["cues"])
        self.assertEqual(poison, data["raw"])
        self.assertEqual(poison, data["reply"])
        data["after"]["ui"]["nodes"][0]["icon"]["alpha"] = 0.125
        self.assertEqual(0.75, data["before"]["ui"]["nodes"][0]["icon"]["alpha"])
        self.assertEqual(original, source)
        self.assertEqual(original, result.response.raw)

    def test_snapshot_cannot_borrow_tables_or_current_visuals_when_its_binding_is_invalid(self):
        source = snapshot("parent")
        nested = snapshot("child", "m2")
        del nested["act_templates"]
        del nested["acts"]
        source["before"] = nested
        original = frame(source)
        with self.assertRaisesRegex(DecodeError, r"before.*ops") as caught:
            decode_wire_response(original)
        self.assertEqual(original, caught.exception.raw)
        self.assertEqual({"id": "q", "s": "s1"}, caught.exception.identity)

    def test_event_order_repeated_occurrences_and_empty_clearing_samples_survive(self):
        floating = {"format": "display_snapshot_v1", "at": "2026-09-23T00:00:00Z",
                    "depth": 5, "map_context": "m1", "entries": [{"text": "1", "color": 0,
                    "cell": 0, "clipped": False, "icon": {"atlas": "floating_text", "index": 0}}]}
        events = [
            {"kind": "game.floating_text", "sequence": 1, "data": floating},
            {"kind": "game.floating_text", "sequence": 2, "data": floating},
            {"kind": "game.visual", "sequence": 3, "data": {"cues": []}},
            {"kind": "game.visual_metrics", "sequence": 4, "data": {"format": "sampled_display_snapshot_v1",
                "sample_period_ms": 250, "at": "2026-09-23T00:00:00.250Z", "metrics": []}},
            {"kind": "game.banner", "sequence": 5, "data": {"format": "display_occurrence_v1",
                "kind": "boss_slain", "appearance": appearance()}},
        ]
        source = frame({"items": events})
        original = copy.deepcopy(source)
        result = decode_client_response(source)
        self.assertIsNone(result.current_frame)
        self.assertEqual(events, result.response.data["items"])
        self.assertEqual(original, source)
        self.assertEqual(original, expand_structures(source))
        result.response.data["items"][0]["data"]["entries"][0]["color"] = 123
        self.assertEqual(original, source)
        self.assertEqual(original, result.response.raw)


if __name__ == "__main__":
    unittest.main()
