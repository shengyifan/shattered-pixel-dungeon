import copy
import json
import unittest

from standalone_ui_codec import CodecError, decode, encode


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def marker_tags(value):
    tags = []
    if isinstance(value, dict):
        if list(value) == ["$ui"] and isinstance(value["$ui"], list) and value["$ui"]:
            tags.append(value["$ui"][0])
        for child in value.values():
            tags.extend(marker_tags(child))
    elif isinstance(value, list):
        for child in value:
            tags.extend(marker_tags(child))
    return tags


class StandaloneUiCodecTest(unittest.TestCase):
    def assert_round_trip(self, frame):
        original = copy.deepcopy(frame)
        packet = encode(frame)
        self.assertEqual(original, frame, "encoding mutated the input")
        restored = decode(packet)
        self.assertEqual(original, restored)
        self.assertEqual(compact(original), compact(restored), "object/row order changed")
        return packet

    def test_late_and_outcome_trees_remain_opaque_in_the_encoded_packet(self):
        past={"data":{"ui":{"nodes":[{"id":"c1","role":"button","ops":[{"op":"click"}]}]},
                      "marker":{"$ui":["not-a-codec-marker"]}}}
        frame={key:past for key in ("history","events","outcome","late_responses","original_response","source")}
        packet=self.assert_round_trip(frame)
        self.assertEqual(frame,packet["frame"])

    def test_packed_nodes_factor_common_columns_without_changing_shapes(self):
        shapes = [["id", "role", "parent", "visible", "enabled", "ops", "display"]]
        rows = [[0, "c%d" % index, "button", "root", True, True, 0,
                 {"status": "ready", "future": None}] for index in range(40)]
        frame = {"v": 6, "id": "t1.9", "data": {"phase": "player_ready", "ui": {
            "node_shapes": shapes,
            "op_defs": [[{"op": "click", "gestures": ["click", "long"]}]],
            "nodes": rows,
        }}}
        packet = self.assert_round_trip(frame)
        self.assertIn("packed_nodes", marker_tags(packet))
        self.assertEqual(shapes, decode(packet)["data"]["ui"]["node_shapes"])
        self.assertLess(len(compact(packet)), len(compact(frame)))

    def test_object_rows_keep_id_parent_order_null_false_zero_and_metadata(self):
        frame = {"data": {"ui": {"nodes": [
            {"id": "a", "role": "group", "parent": None, "enabled": False,
             "count": 0, "metadata": {"kind": "resource", "args": [None, False, 0]}},
            {"id": "b", "role": "group", "parent": "a", "enabled": False,
             "count": 0, "metadata": {"kind": "resource", "args": [None, False, 0]}},
            {"id": "c", "role": "group", "parent": "a", "enabled": False,
             "count": 0, "metadata": {"kind": "resource", "args": [None, False, 0]}},
        ]}}}
        packet = self.assert_round_trip(frame)
        self.assertIn("rows", marker_tags(packet))
        nodes = decode(packet)["data"]["ui"]["nodes"]
        self.assertEqual(["a", "b", "c"], [node["id"] for node in nodes])
        self.assertEqual(list(frame["data"]["ui"]["nodes"][0]), list(nodes[0]))

    def test_mixed_packed_object_and_invalid_rows_keep_exact_positions(self):
        frame = {"data": {"ui": {
            "node_shapes": [["id", "role", "label"]],
            "nodes": [
                [0, "a", "button", None],
                {"id": "metadata", "role": "text", "presentation": {"status": "partial"}},
                [0, "b", "button", False],
                [99, "unknown", 0, None],
                [0, "c", "button", 0],
            ],
        }}}
        self.assert_round_trip(frame)

    def test_actions_reference_exact_op_defs_and_restore_ctl_insertion_order(self):
        operations, actions = [], []
        for index in range(12):
            operation = {"op": "click",
                         "label": "Apply a deliberately long operation label %d" % index,
                         "gestures": ["click", "long"],
                         "parameters": {"mode": ["inspect", "execute"], "future": index}}
            operations.append(operation)
            items = list(operation.items())
            items.insert(1, ("ctl", "control-%d" % index))
            actions.append(dict(items))
        nodes = [{"id": "control-%d" % index, "role": "button", "ops": [operations[index]]}
                 for index in range(12)]
        frame = {"data": {"acts": actions, "ui": {"op_defs": [operations], "nodes": nodes}}}
        packet = self.assert_round_trip(frame)
        self.assertIn("node_ops", marker_tags(packet))
        restored = decode(packet)["data"]["acts"]
        self.assertEqual(list(actions[0]), list(restored[0]))
        self.assertEqual("control-11", restored[-1]["ctl"])

    def test_inventory_name_reference_requires_unique_exact_locator(self):
        long_name = "a very long independently disclosed inventory item name " * 2
        unique = {"data": {
            "inv": [{"loc": "backpack.0", "name": long_name}],
            "ui": {"nodes": [{"id": "slot", "loc": "backpack.0", "label": long_name}]},
        }}
        packet = self.assert_round_trip(unique)
        self.assertIn("inventory_name", marker_tags(packet))

        ambiguous = copy.deepcopy(unique)
        ambiguous["data"]["inv"].append({"loc": "backpack.0", "name": long_name})
        packet = self.assert_round_trip(ambiguous)
        self.assertNotIn("inventory_name", marker_tags(packet))

    def test_child_text_uses_only_exact_direct_parent_slice(self):
        repeated = "The exact visible warning is repeated here. " * 5
        frame = {"data": {"ui": {"nodes": [
            {"id": "parent", "role": "group", "text": "prefix::" + repeated + "::suffix"},
            {"id": "child", "role": "text", "parent": "parent", "text": repeated},
        ]}}}
        packet = self.assert_round_trip(frame)
        self.assertIn("parent_text", marker_tags(packet))

    def test_raw_reply_and_source_asts_are_opaque_even_with_marker_lookalikes(self):
        payload = {"$ui": ["value", 999], "nested": [{"$ui": ["parent_text", 1, 2, 3]}]}
        frame = {"id": "q", "raw": payload, "reply": payload,
                 "text_sources": {"hero.hp": payload}, "text_origins": payload,
                 "data": {"unknown": None}}
        packet = self.assert_round_trip(frame)
        encoded = packet["frame"]
        self.assertEqual(payload, encoded["raw"])
        self.assertEqual(payload, encoded["reply"])
        self.assertEqual({"hero.hp": payload}, encoded["text_sources"])

    def test_reserved_marker_keys_and_unknown_fields_round_trip_without_collision(self):
        frame = {"$ui": ["actions", [["same", 0, 0]]],
                 "future": {"$ui": ["value", 17], "zero": 0},
                 "unknown_list": [{"$ui": "literal"}, None, False, 0],
                 "data": {"map": {"w": 2, "h": 1,
                                   "types": [{"terrain": 1, "name": "Floor"}],
                                   "rows": [[0, 0, "00", "vv"]],
                                   "$ui": ["literal", "map value"],
                                   "future": {"$ui": ["value", 999]}}}}
        packet = self.assert_round_trip(frame)
        self.assertIn("object", marker_tags(packet))
        map_frame = {"data": {"map": copy.deepcopy(frame["data"]["map"])}}
        map_packet = self.assert_round_trip(map_frame)
        self.assertEqual(map_frame["data"]["map"], map_packet["frame"]["data"]["map"])

    def test_compound_value_defs_are_local_and_packets_decode_independently(self):
        shared_a = {"status": "a" * 80, "extra": [None, False, 0]}
        first = {"id": "first", "data": {"ui": {"nodes": [
            {"id": "a", "display": shared_a}, {"id": "b", "display": shared_a},
            {"id": "c", "display": shared_a},
        ]}}}
        shared_b = {"status": "b" * 80, "extra": [0, False, None]}
        second = {"id": "second", "data": {"ui": {"nodes": [
            {"id": "x", "display": shared_b}, {"id": "y", "display": shared_b},
            {"id": "z", "display": shared_b},
        ]}}}
        packet_a, packet_b = encode(first), encode(second)
        self.assertTrue(packet_a["defs"]["values"])
        self.assertTrue(packet_b["defs"]["values"])
        self.assertEqual(second, decode(packet_b))
        self.assertEqual(first, decode(packet_a))
        self.assertNotEqual(packet_a["defs"]["values"], packet_b["defs"]["values"])
        measured = encode(first, measure=lambda text: len(text))
        self.assertEqual(first, decode(measured))

    def test_malformed_or_cross_packet_references_fail_closed(self):
        frame = {"data": {"inv": [{"loc": "b.0", "name": "n" * 100}],
                          "ui": {"nodes": [{"id": "n", "loc": "b.0", "label": "n" * 100}]}}}
        packet = encode(frame)
        broken = copy.deepcopy(packet)
        broken["frame"]["data"]["ui"]["nodes"][0]["label"]["$ui"][1] = 7
        with self.assertRaises(CodecError):
            decode(broken)

        with self.assertRaises(CodecError):
            decode({"$codec": "standalone-ui-v1", "defs": {"values": []},
                    "frame": {"$ui": ["value", 0]}})
        with self.assertRaises(CodecError):
            decode({"$codec": "standalone-ui-v1", "defs": {"values": []},
                    "frame": {"$ui": ["unknown"]}})
        with self.assertRaises(CodecError):
            encode(frame, measure=lambda _text: -1)


if __name__ == "__main__":
    unittest.main()
