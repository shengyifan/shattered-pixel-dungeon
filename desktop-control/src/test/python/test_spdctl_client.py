"""Offline regressions for the reusable public protocol-7 client helpers."""

import copy
import sys
import unittest
from pathlib import Path


CLIENT_DIR = Path(__file__).resolve().parents[3] / "client"
sys.path.insert(0, str(CLIENT_DIR))

from spdctl_client import (  # noqa: E402
    ClientResponseError,
    DecodeError,
    IntentError,
    decode_client_response,
    decode_wire_response,
    map_cell,
    validate_intent,
)


def wire(identifier="q1", *, status="completed", scope="s2", revision="r7", data=None):
    result = {"v": 7, "id": identifier, "s": scope, "rev": revision, "st": status}
    if data is not None:
        result["data"] = data
    return result


class SpdctlClientDecoderTest(unittest.TestCase):

    def test_packed_shape_index_is_not_a_control_and_all_metadata_survives(self):
        frame = wire(data={
            "inv": [{"loc": "backpack.0", "name": "scroll of upgrade"}],
            "acts": [{"op": "click", "ctl": "c9", "gestures": ["click", "long"], "future": 7}],
            "ui": {
                "node_templates": [{"common": {}, "fields": ["id", "role", "loc", "label", "ops", "longlabel", "unknown"]}],
                "nodes": [[0, "c9", "button", "backpack.0", 1, [0],
                           "A literal long label that must not be title-cased", [None, False, 0]]],
                "future_ui": {"kept": True},
            },
            "future_top": {"kept": 0},
        })
        original = copy.deepcopy(frame)
        decoded = decode_wire_response(frame)
        node = decoded.data["ui"]["nodes"][0]
        self.assertEqual("c9", node["id"])
        self.assertNotEqual(0, node["id"], "shapeIndex must never become ctl/id")
        self.assertEqual("Scroll of Upgrade", node["label"])
        self.assertEqual("A literal long label that must not be title-cased", node["longlabel"])
        self.assertEqual([None, False, 0], node["unknown"])
        self.assertEqual(7, node["ops"][0]["future"])
        self.assertTrue(node["enabled"])
        self.assertFalse(node["dimmed"])
        self.assertNotIn("node_templates", decoded.data["ui"])
        self.assertEqual({"kept": True}, decoded.data["ui"]["future_ui"])
        self.assertEqual({"kept": 0}, decoded.data["future_top"])
        self.assertEqual(original, frame)
        self.assertEqual(original, decoded.raw)

    def test_labels_and_dictionaries_are_resolved_from_each_frame_only(self):
        ui = {"nodes": [{"id": "c1", "loc": "bag.0", "label": 0, "ops": []}]}
        first = decode_wire_response(wire("a", data={"inv": [{"loc": "bag.0", "name": "One"}], "ui": ui}))
        second = decode_wire_response(wire("b", data={"inv": [{"loc": "bag.0", "name": "Two"}], "ui": ui}))
        self.assertEqual("One", first.data["ui"]["nodes"][0]["label"])
        self.assertEqual("Two", second.data["ui"]["nodes"][0]["label"])
        with self.assertRaises(DecodeError):
            decode_wire_response(wire("bad", data={"ui": {"nodes": [[0, "button"]]}}))

    def test_map_uses_own_types_cell_formula_visibility_and_effect_defs(self):
        first = decode_wire_response(wire("a", data={"phase": "player_ready", "map": {
            "w": 43, "h": 47,
            "types": [{"terrain": 4, "name": "Wall"}, {"terrain": 1, "name": "Floor"}],
            "rows": [[11, 22, "01", "v"], [12, 23, "0", "s"]],
            "effect_defs": [[{"type": "gas", "desc": "Visible gas", "future": None}]],
            "env": {"496": 0},
            "future_map": False,
        }}))
        self.assertEqual((22, 11, "Wall"),
                         (map_cell(first, 495)["x"], map_cell(first, 495)["y"], map_cell(first, 495)["name"]))
        self.assertEqual("Floor", map_cell(first, 496)["name"])
        self.assertEqual("Visible gas", map_cell(first, 496)["environment"][0]["desc"])
        self.assertEqual("visited", map_cell(first, 539)["visibility"])
        self.assertIsNone(map_cell(first, 497), "unknown row gaps must stay unknown")
        self.assertIs(False, first.data["map"]["future_map"])

        with_future_cells = decode_wire_response(wire("future", data={"phase": "player_ready", "map": {
            "w": 2, "h": 1, "types": [{"terrain": 1, "name": "Floor"}],
            "rows": [[0, 0, "0", "v"]], "cells": {"future": "opaque"},
        }}))
        self.assertEqual({"future": "opaque"}, with_future_cells.data["map"]["cells"])
        self.assertEqual("Floor", map_cell(with_future_cells, 0)["name"])
        self.assertIn("decoded_cells", with_future_cells.data["map"])

        # The same tile symbol has the opposite meaning in the next frame.
        second = decode_wire_response(wire("b", data={"phase": "player_ready", "map": {
            "w": 43, "h": 47,
            "types": [{"terrain": 1, "name": "Floor"}, {"terrain": 4, "name": "Wall"}],
            "rows": [[11, 22, "01", "vv"]],
        }}))
        self.assertEqual("Floor", map_cell(second, 495)["name"])
        self.assertEqual("Wall", map_cell(second, 496)["name"])
        self.assertEqual("Wall", map_cell(first, 495)["name"], "later dictionaries must not mutate an old frame")

    def test_invalid_map_dictionary_rows_and_environment_fail_closed(self):
        invalid_maps = [
            {"w": 2, "h": 1, "types": [{}], "rows": [[0, 0, "1", "v"]]},
            {"w": 2, "h": 1, "types": [{}], "rows": [[0, 0, "00", ""]]},
            {"w": 2, "h": 1, "types": [{}], "rows": [[0, 0, "00", "vv"], [0, 1, "0", "v"]]},
            {"w": 2, "h": 1, "types": [{}], "rows": [[0, 0, "0", "v"]], "env": {"1": []}},
            {"w": True, "h": 1, "types": [{}], "rows": []},
        ]
        for dungeon_map in invalid_maps:
            with self.subTest(dungeon_map=dungeon_map), self.assertRaises(DecodeError):
                decode_wire_response(wire(data={"map": dungeon_map}))

    def test_error_frames_without_data_are_explicit_not_type_errors(self):
        raw = {"v": 7, "id": "a", "s": "s2", "err": "STALE_STATE"}
        decoded = decode_wire_response(raw)
        self.assertTrue(decoded.is_error)
        self.assertEqual("STALE_STATE", decoded.error_code)
        self.assertIsNone(decoded.data)
        result = decode_client_response(raw)
        self.assertFalse(result.ok)
        self.assertIsNone(result.current_frame)
        self.assertEqual("STALE_STATE", result.problems[0].code)
        with self.assertRaises(ClientResponseError):
            result.require_success()

        with_data = {"v": 7, "id": "b", "s": "s2", "err": "EXECUTION_UNKNOWN", "data": {
            "persistence": {"saves": [{"sid": "p1"}], "saved": 0},
            "future_error_detail": 0,
        }}
        decoded = decode_wire_response(with_data)
        self.assertEqual("p1", decoded.data["persistence"]["saved"]["sid"])
        self.assertEqual("s2", decoded.data["persistence"]["saved"]["s"])
        self.assertEqual(0, decoded.data["future_error_detail"])

    def test_controller_error_is_never_treated_as_a_wire_or_observation(self):
        late = wire("late", revision="r4", data={"phase": "player_ready", "hero": {"cell": 4}})
        raw = {"controller": "error", "err": "UNOBSERVED_REVISION", "message": "No binding",
               "future": {"kept": True},
               "late_responses": [{"request": {"id": "old"}, "response": late}]}
        decoded = decode_client_response(raw)
        self.assertEqual("error", decoded.controller)
        self.assertFalse(decoded.ok)
        self.assertIsNone(decoded.response)
        self.assertIsNone(decoded.current_frame)
        self.assertEqual("UNOBSERVED_REVISION", decoded.problems[0].code)
        self.assertEqual("r4", decoded.late_responses[0].response.frame["rev"])
        self.assertEqual(raw, decoded.raw)

    def test_response_wrapper_current_frame_never_uses_late_response(self):
        current = wire("current", revision="r9", data={"phase": "player_ready", "hero": {"cell": 9}})
        old = wire("old", revision="r2", data={"phase": "player_ready", "hero": {"cell": 2}})
        result = decode_client_response({
            "controller": "response", "response": current,
            "late_responses": [{"request": {"id": "old"}, "response": old}],
        })
        self.assertEqual("r9", result.current_frame.frame["rev"])
        self.assertEqual(9, result.current_frame.data["hero"]["cell"])
        self.assertEqual("r2", result.late_responses[0].response.frame["rev"])
        self.assertEqual(2, result.late_responses[0].response.data["hero"]["cell"])

        # Wrapping a current receipt query does not turn its historical summary
        # into a live gameplay observation.
        old_reply = wire("action", revision="old-rev", data={"phase": "player_ready", "hero": {"cell": 1}})
        receipt = wire("q", revision=None, data={"id": "action", "op": "cell", "st": "COMPLETED",
                                                     "reply": old_reply})
        receipt.pop("rev")
        wrapped = decode_client_response({"controller": "response", "response": receipt})
        self.assertIsNone(wrapped.current_frame)
        self.assertEqual("old-rev", wrapped.response.data["reply"]["rev"])

    def test_settle_keeps_outcome_discovery_and_observation_separate(self):
        outcome = wire("lookup", revision=None, data={"id": "action", "op": "cell", "st": "COMPLETED",
                                                             "save": [{"sid": "p1"}]})
        outcome.pop("rev")
        discovery = wire("info", revision="r8", data={"cli_version": "CLI.7.0.0"})
        observation = wire("state", revision="r9", data={"phase": "player_ready", "hero": {"cell": 42}})
        result = decode_client_response({
            "controller": "settle", "rid": "action", "s": "s2", "st": "completed",
            "outcome": outcome, "discovery": discovery, "observation": observation,
        })
        self.assertTrue(result.ok)
        self.assertEqual("COMPLETED", result.outcome.data["st"])
        self.assertEqual("r8", result.discovery.frame["rev"])
        self.assertEqual("r9", result.observation.frame["rev"])
        self.assertIs(result.observation, result.current_frame)
        self.assertNotEqual(result.outcome.frame["id"], result.current_frame.frame["id"])

        pending = decode_client_response({
            "controller": "settle", "rid": "action", "s": "s2", "st": "pending",
            "outcome": wire("lookup2", revision=None, data={"id": "action", "op": "cell", "st": "EXECUTING"}),
        })
        self.assertIsNone(pending.current_frame)
        self.assertFalse(pending.ok)

    def test_settle_transport_and_stage_errors_remain_visible(self):
        failed = decode_client_response({
            "controller": "settle", "rid": "a", "s": "s2", "st": "error", "err": "RECOVERY_FAILED",
            "transport_error": {"controller": "error", "err": "RESPONSE_TIMEOUT", "message": "timed out"},
            "observation": {"v": 7, "id": "state", "s": "s2", "err": "SCOPE_MISMATCH"},
        })
        self.assertFalse(failed.ok)
        self.assertIsNone(failed.current_frame)
        self.assertEqual({"SCOPE_MISMATCH", "RESPONSE_TIMEOUT", "RECOVERY_FAILED"},
                         {problem.code for problem in failed.problems})

    def test_exit_preserves_bare_quit_outcome_and_pending_error(self):
        quit_response = wire("quit", revision="r10", data={
            "phase": "closing", "persistence": {"saves": [{"sid": "p9"}], "saved": 0},
        })
        exited = decode_client_response({
            "controller": "exit", "st": "completed", "exit_code": 0, "outcome": quit_response,
        })
        self.assertTrue(exited.ok)
        self.assertEqual("quit", exited.outcome.frame["id"])
        self.assertEqual("p9", exited.outcome.data["persistence"]["saved"]["sid"])
        self.assertIsNone(exited.current_frame, "an exit outcome is not a new actionable frame")

        pending = decode_client_response({
            "controller": "exit", "st": "pending", "err": "CHILD_EXIT_TIMEOUT",
            "outcome": quit_response,
        })
        self.assertFalse(pending.ok)
        self.assertEqual("CHILD_EXIT_TIMEOUT", pending.problems[0].code)
        self.assertEqual("quit", pending.outcome.frame["id"])

    def test_non_string_statuses_are_decode_errors_not_type_errors(self):
        for malformed in (None, 0, [], {}):
            frame = {"v": 7, "id": "bad", "st": malformed}
            original = copy.deepcopy(frame)
            with self.subTest(status=malformed), self.assertRaises(DecodeError):
                decode_wire_response(frame)
            self.assertEqual(original, frame)
        for wrapper in (
            {"controller": "settle", "st": []},
            {"controller": "exit", "st": None},
            {"controller": [], "st": "completed"},
        ):
            with self.subTest(wrapper=wrapper), self.assertRaises(DecodeError):
                decode_client_response(wrapper)

    def test_explicit_null_false_zero_and_item_defaults_are_not_conflated(self):
        decoded = decode_wire_response(wire(data={
            "inv": [
                {"loc": "b.0", "name": "zero", "qty": 0, "available": False,
                 "level": None, "cursed": None, "future": 0},
                {"loc": "b.1", "name": "defaulted"},
            ],
            "ui": {"modal": None, "item_info": False, "nodes": [
                {"id": "n", "label": None, "enabled": None, "dimmed": False,
                 "longlabel": "already literal", "ops": None},
            ]},
        }))
        first, second = decoded.data["inv"]
        self.assertEqual(0, first["qty"])
        self.assertIs(False, first["available"])
        self.assertIsNone(first["level"])
        self.assertIsNone(first["cursed"])
        self.assertEqual(0, first["future"])
        self.assertEqual(1, second["qty"])
        self.assertIs(True, second["available"])
        node = decoded.data["ui"]["nodes"][0]
        self.assertIsNone(node["label"])
        self.assertIsNone(node["enabled"])
        self.assertIsNone(node["ops"])
        self.assertEqual("already literal", node["longlabel"])
        self.assertIsNone(decoded.data["ui"]["modal"])
        self.assertIs(False, decoded.data["ui"]["item_info"])


class SpdctlIntentValidationTest(unittest.TestCase):

    @staticmethod
    def current(*, acts=None, modal=False):
        return decode_wire_response(wire(data={
            "phase": "player_ready",
            "inv": [{"loc": "backpack.0", "name": "Potion", "available": True}],
            "map": {"w": 3, "h": 3,
                    "types": [{"terrain": 1, "name": "Floor"}, {"terrain": 4, "name": "Wall"}],
                    "rows": [[1, 0, "010", "v"]]},
            "ui": {"modal": modal, "nodes": [
                {"id": "click-only", "role": "button", "ops": [{"op": "click"}]},
                {"id": "list-entry", "role": "entry", "ops": [{"op": "select"}]},
                {"id": "gestures", "role": "button",
                 "ops": [{"op": "click", "gestures": ["click", "long"]}]},
            ]},
            "acts": acts if acts is not None else [
                {"op": "move"}, {"op": "cell"}, {"op": "item"}, {"op": "back"},
            ],
        }))

    def test_exact_rev_scope_and_controller_owned_identity_are_required(self):
        frame = self.current()
        validation = validate_intent(frame, {"op": "move", "rev": "r7", "dir": "SE"})
        self.assertEqual("move", validation.advertised[0]["op"])
        for bad in (
            {"op": "move", "rev": "old", "dir": "SE"},
            {"op": "move", "rev": "r7", "s": "other", "dir": "SE"},
            {"op": "move", "rev": "r7", "dir": "southeast"},
            {"op": "move", "rev": "r7", "dir": []},
            {"op": "move", "rev": "r7", "dir": "SE", "id": "caller"},
            {"op": "move", "rev": "r7", "dir": "SE", "v": 7},
        ):
            with self.subTest(intent=bad), self.assertRaises(IntentError):
                validate_intent(frame, bad)

    def test_node_operation_and_gesture_must_be_currently_advertised(self):
        frame = self.current()
        clicked = validate_intent(frame, {"op": "click", "rev": "r7", "ctl": "click-only"})
        self.assertEqual("click-only", clicked.node["id"])
        selected = validate_intent(frame, {"op": "select", "rev": "r7", "ctl": "list-entry"})
        self.assertEqual("select", selected.advertised[0]["op"])
        validate_intent(frame, {"op": "click", "rev": "r7", "ctl": "gestures", "g": "long"})
        for bad in (
            {"op": "select", "rev": "r7", "ctl": "click-only"},
            {"op": "click", "rev": "r7", "ctl": "list-entry"},
            {"op": "click", "rev": "r7", "ctl": "gestures", "g": "middle"},
            {"op": "click", "rev": "r7", "ctl": "gestures", "g": []},
            {"op": "click", "rev": "r7", "ctl": "missing"},
        ):
            with self.subTest(intent=bad), self.assertRaises(IntentError):
                validate_intent(frame, bad)

    def test_modal_global_actions_item_binding_and_cell_terrain_are_mechanical(self):
        frame = self.current()
        item = validate_intent(frame, {"op": "item", "rev": "r7", "loc": "backpack.0"})
        self.assertEqual("Potion", item.item["name"])
        wall = validate_intent(frame, {"op": "cell", "rev": "r7", "cell": 4})
        self.assertEqual("Wall", wall.target_cell["name"])
        self.assertEqual((1, 1), (wall.target_cell["x"], wall.target_cell["y"]))
        # The validator reports Wall but intentionally does not decide that a
        # native cell action must fail, move, or consume a turn.
        unknown = validate_intent(frame, {"op": "cell", "rev": "r7", "cell": 0})
        self.assertIsNone(unknown.target_cell)
        with self.assertRaises(IntentError):
            validate_intent(frame, {"op": "item", "rev": "r7", "loc": "backpack.missing"})

        modal = self.current(acts=[{"op": "back"}], modal=True)
        validate_intent(modal, {"op": "back", "rev": "r7"})
        with self.assertRaises(IntentError):
            validate_intent(modal, {"op": "move", "rev": "r7", "dir": "N"})

    def test_cancel_uses_exact_advertised_activity_binding(self):
        frame = decode_wire_response(wire(revision="a3", status="in_progress", data={
            "phase": "continuous_activity",
            "activity": {"kind": "travel", "rid": "action-1"},
            "acts": [{"op": "cancel"}],
        }))
        valid = validate_intent(frame, {"op": "cancel", "rev": "a3", "rid": "action-1"})
        self.assertEqual(("a3", "action-1"),
                         (valid.advertised[0]["rev"], valid.advertised[0]["rid"]))
        for bad in (
            {"op": "cancel", "rev": "a3", "rid": "other"},
            {"op": "cancel", "rev": "a3"},
        ):
            with self.subTest(intent=bad), self.assertRaises(IntentError):
                validate_intent(frame, bad)

    def test_unresolved_wrapper_problems_require_explicit_observation_choice(self):
        observation = wire("state", revision="r9", data={
            "phase": "player_ready", "acts": [{"op": "move"}],
        })
        wrapped = decode_client_response({
            "controller": "settle", "rid": "a", "s": "s2", "st": "completed",
            "outcome": wire("receipt", revision=None, data={"id": "a", "op": "move", "st": "COMPLETED"}),
            "observation": observation,
            "transport_error": {"controller": "error", "err": "RESPONSE_TIMEOUT", "message": "late"},
        })
        self.assertIsNotNone(wrapped.current_frame)
        with self.assertRaises(IntentError):
            validate_intent(wrapped, {"op": "move", "rev": "r9", "dir": "N"})
        # A caller may explicitly acknowledge the diagnostic and choose only the
        # separately exposed fresh observation.
        validate_intent(wrapped.observation, {"op": "move", "rev": "r9", "dir": "N"})

    def test_queries_do_not_require_or_mutate_a_current_frame(self):
        query = {"op": "state", "s": "s2"}
        validation = validate_intent(None, query)
        self.assertEqual(query, validation.intent)
        with self.assertRaises(IntentError):
            validate_intent(None, {"op": "state", "id": "caller-owned"})


if __name__ == "__main__":
    unittest.main()
