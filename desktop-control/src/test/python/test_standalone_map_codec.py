"""Boundary tests for self-contained lossless map-format experiments."""

import copy
import unittest

from standalone_map_codec import CodecError, decode, encode


def response(dungeon_map=None):
    data = {"phase": "player_ready", "scene": "game", "future": None}
    if dungeon_map is not None:
        data["map"] = dungeon_map
    return {"v": 6, "id": "q", "s": "s2", "rev": "r7", "st": "completed", "data": data}


class StandaloneMapCodecTest(unittest.TestCase):

    def test_ascii_is_readable_and_keeps_walls_distinct_from_unknown_gaps(self):
        frame = response({
            "w": 8, "h": 3,
            "types": [{"terrain": 4, "name": "Wall"}, {"terrain": 1, "name": "Floor"}],
            "rows": [[0, 1, "0110", "v"], [0, 6, "1", "s"], [2, 3, "010", "m"]],
            "unknown": "omitted",
        })
        packet = encode(frame, "ascii")
        body = packet["maps"][0]["body"]
        self.assertEqual("#.", body["symbols"])
        self.assertIn("0,1|#..#|v", body["grid"])
        self.assertIn("0,6|.|s", body["grid"])
        self.assertNotIn("0,5", body["grid"], "unknown gap cells must not be synthesized")
        self.assertEqual(frame, decode(packet))

    def test_ascii_preserves_uniform_and_explicit_visibility_exactly(self):
        frame = response({
            "w": 5, "h": 2,
            "types": [{"terrain": 1, "name": "Floor"}],
            "rows": [[0, 0, "000", "v"], [1, 1, "000", "vsm"]],
        })
        packet = encode(frame, "ascii")
        grid = packet["maps"][0]["body"]["grid"]
        self.assertIn("0,0|...|v", grid)
        self.assertIn("1,1|...|vsm", grid)
        self.assertEqual(frame, decode(packet))

    def test_rle_preserves_runs_visibility_mode_and_all_map_metadata(self):
        effect = [{"type": "gas", "desc": "Visible gas", "future": False}]
        frame = response({
            "w": 12, "h": 2,
            "types": [{"terrain": 4, "name": "Wall"}, {"terrain": 1, "name": "Floor"}],
            "rows": [[0, 1, "0000111100", "v"], [1, 2, "11110000", "vvssmmmm"]],
            "effect_defs": [effect], "env": {"5": 0},
            "preserved_cells": [{"cell": 11, "desc": None}],
            "future_map_metadata": {"zero": 0, "null": None},
        })
        packet = encode(frame, "rle")
        grid = packet["maps"][0]["body"]["grid"]
        self.assertIn("0,1|0*4.1*4.0*2|uv", grid)
        self.assertIn("1,2|1*4.0*4|ev*2.s*2.m*4", grid)
        self.assertEqual(frame, decode(packet))

    def test_more_than_64_types_restore_integer_rows_in_both_styles(self):
        types = [{"terrain": index, "name": f"Type {index}"} for index in range(96)]
        frame = response({"w": 4, "h": 1, "types": types,
                          "rows": [[0, 0, [0, 64, 95, 64], "vsmv"]]})
        ascii_packet = encode(frame, "ascii")
        self.assertEqual("tokens", ascii_packet["maps"][0]["body"]["mode"])
        self.assertEqual(frame, decode(ascii_packet))
        rle_packet = encode(frame, "rle")
        self.assertIn("1s*1", rle_packet["maps"][0]["body"]["grid"])
        self.assertEqual(frame, decode(rle_packet))

    def test_many_descriptors_with_one_terrain_still_get_a_bijective_ascii_table(self):
        types = [{"terrain": 4, "name": f"Wall variant {index}"} for index in range(80)]
        frame = response({"w": 4, "h": 1, "types": types,
                          "rows": [[0, 0, [0, 79, 1, 78], "vsmv"]]})
        packet = encode(frame, "ascii")
        body = packet["maps"][0]["body"]
        self.assertEqual("chars", body["mode"])
        self.assertEqual(80, len(body["symbols"]))
        self.assertEqual(80, len(set(body["symbols"])))
        self.assertEqual("#", body["symbols"][0])
        self.assertEqual(frame, decode(packet))

    def test_original_reserved_looking_keys_are_nested_and_restored_not_overwritten(self):
        dungeon_map = {
            "w": 2, "h": 1, "types": [{"terrain": 1}], "rows": [[0, 0, "00", "v"]],
            "v": "original-map-v", "style": "original-style", "mode": None,
            "symbols": False, "grid": 0, "extra": {"$standalone_map": 9},
            "$standalone_map": "ordinary metadata",
        }
        frame = {"codec": "original-codec", "style": "original-root-style",
                 "frame": {"maps": "ordinary-root-data"}, "maps": [None],
                 "data": {"phase": "player_ready", "map": dungeon_map}}
        original = copy.deepcopy(frame)
        packet = encode(frame, "rle")
        self.assertEqual(original, decode(packet))
        self.assertEqual(original, frame)

    def test_grid_bbox_keeps_two_segments_and_unknown_space(self):
        frame = response({
            "w": 10, "h": 4,
            "types": [{"terrain": 4, "name": "Wall"}, {"terrain": 1, "name": "Floor"}],
            "rows": [[1, 1, "01", "v"], [1, 6, "110", "sss"], [3, 4, "1", "m"]],
            "env": {"16": [{"type": "gas"}]}, "future": None,
        })
        packet = encode(frame, "grid")
        body = packet["maps"][0]["body"]
        self.assertEqual([1, 1, 8, 3], body["bbox"])
        lines = body["grid"].split("\n")
        self.assertEqual("01   110", lines[0])
        self.assertEqual("        ", lines[1])
        self.assertEqual("   1    ", lines[2])
        self.assertEqual("s", body["vis_default"])
        self.assertEqual(frame, decode(packet))

    def test_grid_empty_map_has_no_invented_bbox_or_visibility(self):
        frame = response({"w": 5, "h": 5, "types": [], "rows": [],
                          "env": {}, "future": {"null": None}})
        packet = encode(frame, "grid")
        body = packet["maps"][0]["body"]
        self.assertEqual([], body["bbox"])
        self.assertEqual("", body["grid"])
        self.assertIsNone(body["vis_default"])
        self.assertEqual(frame, decode(packet))

    def test_grid_records_exceptional_adjacent_original_spans(self):
        frame = response({
            "w": 6, "h": 1,
            "types": [{"terrain": 1}, {"terrain": 4}],
            "rows": [[0, 0, "00", "v"], [0, 2, "110", "s"]],
        })
        packet = encode(frame, "grid")
        body = packet["maps"][0]["body"]
        self.assertEqual([[0, 0, 2], [0, 2, 3]], body["spans"])
        self.assertEqual(frame, decode(packet))

    def test_grid_marks_only_expanded_but_uniform_visibility_rows(self):
        frame = response({
            "w": 7, "h": 2, "types": [{"terrain": 1}],
            "rows": [[0, 0, "000", "vvv"], [1, 1, "000", "vsm"]],
            "future_map_metadata": {"false": False, "zero": 0, "null": None},
        })
        packet = encode(frame, "grid")
        body = packet["maps"][0]["body"]
        self.assertEqual([0], body["expanded_rows"])
        self.assertEqual(frame, decode(packet))

    def test_grid_more_than_64_types_uses_exact_raw_fallback(self):
        types = [{"terrain": index, "name": f"Type {index}"} for index in range(65)]
        dungeon_map = {"w": 3, "h": 1, "types": types,
                       "rows": [[0, 0, [0, 64, 2], "vsm"]], "future": None}
        frame = response(dungeon_map)
        packet = encode(frame, "grid")
        self.assertEqual(dungeon_map, packet["maps"][0]["body"]["raw"])
        self.assertEqual(frame, decode(packet))

    def test_controller_current_maps_transform_but_opaque_history_does_not(self):
        live_map = {"w": 2, "h": 1, "types": [{"terrain": 1, "name": "Floor"}],
                    "rows": [[0, 0, "00", "v"]]}
        historical_map = {"w": 2, "h": 1, "types": [{"terrain": 4, "name": "Wall"}],
                          "rows": [[0, 0, "00", "s"]]}
        frame = {"controller": "response", "response": response(live_map),
                 "late_responses": [{"request": {"id": "old"},
                                      "response": {"v": 6, "id": "old", "st": "completed",
                                                   "data": {"phase": "player_ready",
                                                            "map": historical_map}}}],
                 "history": {"map": historical_map},
                 "text_sources": {"map": historical_map}}
        packet = encode(frame, "ascii")
        self.assertEqual(1, len(packet["maps"]))
        self.assertEqual(historical_map, packet["frame"]["history"]["map"])
        self.assertEqual(historical_map, packet["frame"]["text_sources"]["map"])
        self.assertEqual(historical_map,
                         packet["frame"]["late_responses"][0]["response"]["data"]["map"])
        self.assertEqual(frame, decode(packet))

    def test_error_and_non_map_payloads_pay_wrapper_overhead_but_round_trip(self):
        for frame in (
            {"v": 6, "id": "bad", "s": "s2", "err": "STALE_STATE"},
            {"v": 6, "id": "q", "st": "completed",
             "data": {"map": {"future": "not a protocol map"}}},
        ):
            with self.subTest(frame=frame):
                original = copy.deepcopy(frame)
                packet = encode(frame, "rle")
                self.assertEqual([], packet["maps"])
                self.assertEqual(frame, decode(packet))
                self.assertEqual(original, frame)

    def test_invalid_input_rows_fail_closed_instead_of_filling_cells(self):
        invalid_maps = [
            {"w": 2, "h": 1, "types": [{}], "rows": [[0, 0, "1", "v"]]},
            {"w": 2, "h": 1, "types": [{}], "rows": [[0, 0, "00", None]]},
            {"w": 2, "h": 1, "types": [{}], "rows": [[0, 0, "00", "vv"], [0, 1, "0", "v"]]},
            {"w": True, "h": 1, "types": [{}], "rows": []},
        ]
        for dungeon_map in invalid_maps:
            with self.subTest(dungeon_map=dungeon_map), self.assertRaises(CodecError):
                encode(response(dungeon_map), "ascii")

    def test_tampered_packet_paths_symbols_runs_and_visibility_are_rejected(self):
        frame = response({"w": 3, "h": 1, "types": [{"terrain": 1}],
                          "rows": [[0, 0, "000", "v"]]})
        cases = []
        duplicate_path = encode(frame, "ascii")
        duplicate_path["maps"].append(copy.deepcopy(duplicate_path["maps"][0]))
        cases.append(duplicate_path)
        bad_symbol = encode(frame, "ascii")
        bad_symbol["maps"][0]["body"]["grid"] = "0,0|???|v"
        cases.append(bad_symbol)
        bad_count = encode(frame, "rle")
        bad_count["maps"][0]["body"]["grid"] = "0,0|0*0|uv"
        cases.append(bad_count)
        bad_visibility = encode(frame, "rle")
        bad_visibility["maps"][0]["body"]["grid"] = "0,0|0*3|ev*2"
        cases.append(bad_visibility)
        wrong_target = encode(frame, "ascii")
        wrong_target["maps"][0]["path"][-1] = "phase"
        cases.append(wrong_target)
        unknown_body_field = encode(frame, "ascii")
        unknown_body_field["maps"][0]["body"]["future"] = 1
        cases.append(unknown_body_field)
        oversized_run = encode(frame, "rle")
        oversized_run["maps"][0]["body"]["grid"] = "0,0|0*zzzzzz|uv"
        cases.append(oversized_run)
        for packet in cases:
            with self.subTest(packet=packet), self.assertRaises(CodecError):
                decode(packet)


if __name__ == "__main__":
    unittest.main()
