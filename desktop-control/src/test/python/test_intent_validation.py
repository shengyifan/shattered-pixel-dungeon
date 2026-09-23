"""Static Java/Python parity and current-public-capability validation; no game I/O."""

import copy
import json
import math
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "desktop-control/client"))
from spdctl_client import (DecodeError, IntentError, _PARAMETERS, _validate_intent_syntax,
                           decode_client_response, decode_wire_response, validate_intent)


def frame(op, *, descriptor=None, node=None):
    action = {"op": op, **(descriptor or {})}
    data = {"phase": "player_ready", "acts": [action]}
    if node is not None:
        action["ctl"] = "c1"
        data["ui"] = {"nodes": [{"id": "c1", **node, "ops": [0]}]}
    return decode_wire_response({"v": 8, "id": "t1.2", "s": "s1", "rev": "r1",
                                 "st": "completed", "data": data})


class IntentValidationTest(unittest.TestCase):
    def test_shared_static_corpus_covers_every_current_operation_without_mutation(self):
        corpus = json.loads((ROOT / "control-protocol/src/test/resources/intent-validation-cases.json").read_text())
        self.assertEqual(set(_PARAMETERS), set(corpus["operations"]))
        for case in corpus["cases"]:
            with self.subTest(case=case["name"]):
                before = copy.deepcopy(case["intent"])
                if case["valid"]:
                    _validate_intent_syntax(case["intent"])
                else:
                    with self.assertRaises(IntentError):
                        _validate_intent_syntax(case["intent"])
                self.assertEqual(before, case["intent"])

    def test_non_json_numbers_and_controller_identity_are_rejected(self):
        for value in (math.nan, math.inf, -math.inf, 10**400):
            with self.subTest(value=str(value)), self.assertRaises(IntentError):
                validate_intent(None, {"op": "pan", "x": value})
        for intent in ({"op": "state", "v": 8}, {"op": "settle", "id": "mine"}, None,
                       {"op": "unknown"}, {"op": []}):
            with self.subTest(intent=intent), self.assertRaises(IntentError):
                validate_intent(None, intent)

    def test_choice_and_slider_use_their_current_complete_descriptors(self):
        choice = frame("choose", descriptor={"options": ["one", "two"]}, node={"options": ["one", "two"]})
        validate_intent(choice, {"op": "choose", "ctl": "c1", "rev": "r1", "opt": 1, "alt": False})
        with self.assertRaises(IntentError):
            validate_intent(choice, {"op": "choose", "ctl": "c1", "rev": "r1", "opt": 2})
        slider = frame("value", descriptor={"range": [0, 10]}, node={"min": 0, "max": 10})
        for value in (0, 10):
            validate_intent(slider, {"op": "value", "ctl": "c1", "rev": "r1", "value": value})
        for value in (-1, 11, True, 1.0, "5"):
            with self.subTest(value=value), self.assertRaises(IntentError):
                validate_intent(slider, {"op": "value", "ctl": "c1", "rev": "r1", "value": value})
        wire = copy.deepcopy(slider.raw)
        wire["data"]["acts"] = [{"op": "value", "ctl": "c1", "range": [0, 1]},
                                  {"op": "value", "ctl": "c1", "range": [5, 6]}]
        wire["data"]["ui"]["nodes"][0]["ops"] = [0, 1]
        alternatives = decode_wire_response(wire)
        with self.assertRaises(IntentError):
            validate_intent(alternatives, {"op": "value", "ctl": "c1", "rev": "r1", "value": 3})
        self.assertEqual(1, len(validate_intent(alternatives, {"op": "value", "ctl": "c1", "rev": "r1", "value": 6}).advertised))

    def test_text_length_matches_utf16_and_submit_uses_current_input(self):
        single = frame("text", descriptor={"submit_supported": True}, node={"max_length": 2, "multiline": False})
        for text in ("", "ab", "😀"):
            validate_intent(single, {"op": "text", "ctl": "c1", "rev": "r1", "text": text, "submit": True})
        for text in ("abc", "😀a", "\n", "\r"):
            with self.subTest(text=text), self.assertRaises(IntentError):
                validate_intent(single, {"op": "text", "ctl": "c1", "rev": "r1", "text": text})
        multi = frame("text", descriptor={"submit_supported": False}, node={"max_length": 0, "multiline": True})
        validate_intent(multi, {"op": "text", "ctl": "c1", "rev": "r1", "text": "first\nsecond"})
        with self.assertRaises(IntentError):
            validate_intent(multi, {"op": "text", "ctl": "c1", "rev": "r1", "text": "", "submit": True})

    def test_zoom_slots_key_input_and_malformed_constraints(self):
        zoom = frame("zoom", descriptor={"min": -1, "max": 3})
        validate_intent(zoom, {"op": "zoom", "rev": "r1", "zoom": -1})
        with self.assertRaises(IntentError):
            validate_intent(zoom, {"op": "zoom", "rev": "r1", "zoom": 4})
        slots = frame("bind_slot", descriptor={"slots": [1, 3]}, node={"binding_slots": [1, 2, 3]})
        validate_intent(slots, {"op": "bind_slot", "rev": "r1", "ctl": "c1", "slot": 3})
        with self.assertRaises(IntentError):
            validate_intent(slots, {"op": "bind_slot", "rev": "r1", "ctl": "c1", "slot": 2})
        binding = frame("bind_key", node={"binding_input": True})
        # Exact keyboard/controller membership is a game-only constraint.
        validate_intent(binding, {"op": "bind_key", "rev": "r1", "ctl": "c1", "keycode": 12345})
        for op, args, descriptor, node in (
            ("choose", {"opt": 0}, {}, {}), ("value", {"value": 1}, {"range": [0, 2]}, {"min": 2, "max": 3}),
            ("text", {"text": ""}, {"submit_supported": True}, {"max_length": 0, "multiline": True}),
            ("bind_slot", {"slot": 1}, {"slots": [1]}, {"binding_slots": [2]}),
            ("bind_key", {"keycode": 1}, {}, {"binding_input": False}),
        ):
            with self.subTest(op=op), self.assertRaises(IntentError):
                validate_intent(frame(op, descriptor=descriptor, node=node), {"op": op, "rev": "r1", "ctl": "c1", **args})

    def test_scroll_and_pan_preserve_original_defaults_and_native_clamping(self):
        scroll = frame("scroll", descriptor={"arguments": ["x", "y"]}, node={"scroll_x": 12, "scroll_y": 15})
        for args in ({}, {"x": -1000}, {"y": 100000}, {"x": 0.5, "y": -0.5}):
            intent = {"op": "scroll", "ctl": "c1", "rev": "r1", **args}
            self.assertEqual(intent, validate_intent(scroll, intent).intent)
        pan = frame("pan", descriptor={"units": "map_view", "arguments": ["x", "y"]})
        intent = {"op": "pan", "rev": "r1"}
        self.assertEqual(intent, validate_intent(pan, intent).intent)

    def test_cell_mode_uses_current_modes_without_inventing_unknown_cell_rules(self):
        source = frame("cell", descriptor={"modes": ["examine"]}).raw
        source["data"]["map"] = {"w": 2, "h": 2, "types": [], "rows": []}
        current = decode_wire_response(source)
        self.assertIsNone(validate_intent(current, {"op": "cell", "rev": "r1", "cell": 3, "mode": "examine"}).target_cell)
        with self.assertRaises(IntentError):
            validate_intent(current, {"op": "cell", "rev": "r1", "cell": 3})


class DecodeEvidenceBoundaryTest(unittest.TestCase):
    def test_environment_alias_cannot_overwrite_an_already_decoded_cell(self):
        bad = {"v": 8, "id": "t1.9", "s": "s1", "st": "completed", "data": {
            "map": {"w": 1, "h": 1, "types": [{}], "rows": [[0, 0, "0", "v"]],
                    "env": {"0": [{"desc": "first"}], "00": [{"desc": "second"}]}}}}
        with self.assertRaises(DecodeError) as raised:
            decode_wire_response(bad)
        self.assertEqual("t1.9", raised.exception.response_id)
        self.assertEqual(bad, raised.exception.raw)

    def test_malformed_environment_preserves_direct_and_settle_evidence(self):
        for cell in ("²", "١", "9" * 5000, "1"):
            bad = {"v": 8, "id": "t1.3", "s": "s1", "rev": "r1", "st": "completed", "data": {
                "map": {"w": 1, "h": 1, "types": [{}], "rows": [[0, 0, "0", "v"]], "env": {cell: []}}}}
            wrapper = {"controller": "settle", "st": "completed", "rid": "t1.1", "request": {"id": "t1.1", "op": "rest"},
                       "outcome": {"v": 8, "id": "t1.2", "s": "s1", "st": "completed", "data": {"id": "t1.1", "st": "COMPLETED"}},
                       "observation": bad}
            before = copy.deepcopy(wrapper)
            for decoder, value in ((decode_wire_response, bad), (decode_client_response, wrapper)):
                with self.subTest(cell=cell[:5], decoder=decoder.__name__), self.assertRaises(DecodeError) as raised:
                    decoder(value)
                error = raised.exception
                self.assertEqual(("t1.3", "s1"), (error.response_id, error.scope))
                self.assertEqual(bad, error.raw)
                if decoder is decode_client_response:
                    self.assertEqual("observation", error.stage)
                    self.assertEqual(before, error.context["raw"])
            self.assertEqual(before, wrapper)

    def test_save_index_types_and_receipts_remain_distinct(self):
        for invalid in (True, False, 0.0, -0.5, -1, 2, "0", [], {}):
            value = {"v": 8, "id": "t1.1", "s": "s1", "st": "completed", "data": {
                "persistence": {"saves": [{"sid": "p1"}, {"sid": "p2"}], "saved": invalid}}}
            with self.subTest(invalid=invalid), self.assertRaises(DecodeError) as raised:
                decode_wire_response(value)
            self.assertEqual("t1.1", raised.exception.response_id)
            for snapshot in ("before", "after"):
                historical = {**value, "data": {snapshot: copy.deepcopy(value["data"])}}
                with self.subTest(invalid=invalid, snapshot=snapshot), self.assertRaises(DecodeError) as frozen:
                    decode_wire_response(historical)
                self.assertEqual("t1.1", frozen.exception.response_id)
                self.assertEqual(historical, frozen.exception.raw)
        for saved in (0, 1, None, {"sid": "p3", "s": None, "src_s": None}):
            value["data"]["persistence"]["saved"] = saved
            result = decode_wire_response(value).data["persistence"]
            self.assertEqual(["p1", "p2"], [receipt["sid"] for receipt in result["saves"]])
            self.assertEqual(["p1", "p2"][saved] if type(saved) is int else saved,
                             result["saved"]["sid"] if type(saved) is int else result["saved"])
        del value["data"]["persistence"]["saved"]
        self.assertNotIn("saved", decode_wire_response(value).data["persistence"])


if __name__ == "__main__":
    unittest.main()
