import copy
import json
import unittest

import standalone_scalar_codec as codec


class StandaloneScalarCodecTest(unittest.TestCase):
    def check(self, frame, style):
        original = copy.deepcopy(frame)
        packet = codec.encode(frame, style)
        restored = codec.decode(json.loads(json.dumps(packet)))
        # JSON spelling distinguishes bool/number/null and preserves every list position.
        self.assertEqual(json.dumps(frame, sort_keys=True), json.dumps(restored, sort_keys=True))
        self.assertEqual(original, frame)
        return packet["frame"]

    def test_gesture_rows_keep_order_duplicates_empty_and_unknown_values(self):
        frame = {"id": "t1.100", "s": "s2", "rev": "r10", "data": {"ui": {
            "node_shapes": [["id", "role", "gestures"]],
            "nodes": [[0, "c1", "button", ["long", "click", "long"]],
                      {"id": "c2", "gestures": []}],
            "op_defs": [[{"op": "click", "gestures": ["click", "right", "middle", "long"]}]]}}}
        encoded = self.check(frame, "gestures")
        self.assertEqual("LCL", encoded["data"]["ui"]["nodes"][0][3])
        self.assertEqual("", encoded["data"]["ui"]["nodes"][1]["gestures"])
        self.assertEqual("CRML", encoded["data"]["ui"]["op_defs"][0][0]["gestures"])
        for value in (["click", "future"], [None, False, 0], "CRML", "raw string", None, False, 0,
                      {codec.LITERAL: "C"}, {"future": ["click"]}):
            with self.subTest(value=value):
                self.check({"gestures": value}, "gestures")

    def test_hero_line_keeps_every_extra_field_and_known_scalar(self):
        hero = {"hp": 43, "ht": 65, "shield": 0, "experience": 7, "mxp": 55,
                "strength": 12, "base_strength": 12, "cell": 466, "depth": 7, "level": 10,
                "gold": 284, "energy": 5, "ready": True, "class": "warrior", "class_name": "warrior",
                "subclass": "none", "sub_name": None, "talents": [{"name": "unused", "points": 0}],
                "buffs": [{"name": "unknown", "duration": None}], "tp": [0, 0, 0, 0],
                "future": [None, False, 0]}
        packed = self.check({"data": {"hero": hero}}, "hero")["data"]["hero"]
        self.assertEqual("HP43/65+0 XP7/55 STR12/12 @466 D7 L10 G284 E5 ready class=warrior sub=none/null", packed[codec.HERO])
        for key in ("talents", "buffs", "tp", "future"):
            self.assertEqual(hero[key], packed["rest"][key])

    def test_unknown_null_names_incomplete_pairs_and_reserved_objects_round_trip(self):
        for hero in ({"hp": None, "ht": 20, "shield": False, "strength": 9.5, "ready": False},
                     {"hp": -1, "ht": 0, "shield": -2, "class": "warrior", "class_name": "Custom Name"},
                     {"class": "rogue", "class_name": "rogue", "subclass": "assassin", "sub_name": "assassin"},
                     {"class": "mage"}, {"hp": True, "ht": 1}, {codec.HERO: "HP1/2"},
                     {codec.LITERAL: {codec.HERO: "HP1/2"}}, None, [1, None], "HP1/2"):
            with self.subTest(hero=hero):
                self.check({"data": {"hero": hero}}, "hero")

    def test_history_provenance_envelope_and_outcome_are_not_rewritten(self):
        hidden = {"hero": {"hp": 1, "ht": 2}, "gestures": ["click"]}
        frame = {key: hidden for key in codec.OPAQUE}
        frame.update(id="t1.100", rev="a3", st="in_progress", outcome={"id": "t1.99", "st": "COMPLETED", "data": hidden},
                     data={"hero": {"hp": 1, "ht": 2}, "gestures": ["click"]})
        for style in codec.STYLES:
            encoded = self.check(frame, style)
            for key in codec.OPAQUE:
                self.assertEqual(frame[key], encoded[key])
            for key in ("id", "rev", "st", "outcome"):
                self.assertEqual(frame[key], encoded[key])

    def test_packets_are_independent_and_invalid_tokens_fail_closed(self):
        first = {"data": {"hero": {"hp": 1, "ht": 2}, "gestures": ["long"]}}
        second = {"data": {"hero": {"hp": 10, "ht": 20}, "gestures": ["click"]}}
        a, b = codec.encode(first, "all"), codec.encode(second, "all")
        self.assertEqual(second, codec.decode(b))
        self.assertEqual(first, codec.decode(a))
        with self.assertRaises(ValueError):
            codec.decode({"$codec": codec.CODEC, "style": "gestures", "frame": {"gestures": "CX"}})
        with self.assertRaises(ValueError):
            codec.decode({"$codec": codec.CODEC, "style": "hero", "frame": {"hero": {codec.HERO: "HP1/2", "rest": {"hp": 9}}}})


if __name__ == "__main__":
    unittest.main()
