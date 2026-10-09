"""Offline safe-access and explicit controller-pending diagnostics; no game I/O."""

import copy
import sys
import unittest
from dataclasses import FrozenInstanceError
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "client"))
from spdctl_client import (  # noqa: E402
    ClientResponseError, DecodeError, IntentError, _validate_intent_syntax,
    decode_client_response, decode_wire_response, map_cell, validate_intent,
)


def wire(identifier="t1.2", *, data=None, status="completed", revision="r1"):
    result = {"v": 9, "id": identifier, "s": "s1", "st": status}
    if revision is not None:
        result["rev"] = revision
    if data is not None:
        result["data"] = data
    return result


def observation():
    return wire(data={"phase": "player_ready", "acts": [{"op": "move"}]})


class CurrentFrameSafetyTest(unittest.TestCase):
    def test_required_frame_uses_only_the_explicit_current_observation(self):
        current = observation()
        historical = wire("t1.1", data={"phase": "player_ready"}, revision="r9")
        receipt = wire("t1.3", data={"id": "t1.1", "st": "COMPLETED",
                                           "save": [{"sid": "p1"}]}, revision=None)
        for source in (
            current,
            {"controller": "response", "response": current,
             "late_responses": [{"request": {"id": "t1.1"}, "response": historical}]},
            {"controller": "settle", "st": "completed", "rid": "t1.1", "outcome": receipt,
             "observation": current, "discovery": wire("t1.4", data={"cli_version": "CLI.9.0.1"}),
             "late_responses": [{"request": {"id": "t1.1"}, "response": historical}]},
        ):
            original = copy.deepcopy(source)
            with self.subTest(controller=source.get("controller")):
                result = decode_client_response(source)
                frame = result.require_current_frame()
                self.assertIs(frame, result.current_frame)
                self.assertEqual(("t1.2", "r1"), (frame.frame["id"], frame.frame["rev"]))
                self.assertEqual(original, source)
                self.assertEqual(original, result.raw)

    def test_success_does_not_promise_an_observation(self):
        for source in (
            wire(data={"cli_version": "CLI.9.0.1"}),
            wire(data={"id": "t1.1", "st": "COMPLETED"}, revision=None),
            wire(data={"items": [], "next": None, "end": True}, revision=None),
            {"controller": "settle", "st": "completed", "rid": "t1.1"},
            {"controller": "exit", "st": "completed", "exit_code": 0,
             "outcome": wire(data={"phase": "closing"})},
        ):
            with self.subTest(source=source):
                result = decode_client_response(source)
                self.assertIs(result, result.require_success())
                self.assertIsNone(result.current_frame)
                with self.assertRaises(ClientResponseError) as raised:
                    result.require_current_frame()
                problem, = raised.exception.problems
                self.assertEqual(("current_frame", "NO_CURRENT_FRAME"), (problem.stage, problem.code))
                self.assertEqual(source, problem.payload)

    def test_original_errors_and_pending_are_not_replaced_by_missing_frame(self):
        cases = (
            ({"v": 9, "id": "t1.2", "s": "s1", "err": "STALE_STATE"}, "STALE_STATE"),
            ({"controller": "error", "err": "INVALID_INTENT"}, "INVALID_INTENT"),
            ({"controller": "settle", "st": "pending", "rid": "t1.1"}, "NOT_COMPLETED"),
            ({"controller": "settle", "st": "completed", "rid": "t1.1",
              "observation": observation(),
              "transport_error": {"controller": "error", "err": "RESPONSE_TIMEOUT"}}, "RESPONSE_TIMEOUT"),
        )
        for source, expected in cases:
            with self.subTest(expected=expected):
                result = decode_client_response(source)
                with self.assertRaises(ClientResponseError) as raised:
                    result.require_current_frame()
                self.assertIn(expected, {problem.code for problem in raised.exception.problems})
                self.assertNotIn("NO_CURRENT_FRAME", {problem.code for problem in raised.exception.problems})

    def test_in_progress_observation_is_readable_without_claiming_actionability(self):
        source = wire(data={"phase": "resolving"}, status="in_progress", revision=None)
        result = decode_client_response(source)
        self.assertIs(result.current_frame, result.require_current_frame())
        with self.assertRaises(IntentError):
            validate_intent(result, {"op": "move", "rev": "r1", "dir": "N"})

    def test_map_access_rejects_invalid_frame_instead_of_returning_an_unknown_cell(self):
        error = decode_wire_response({"v": 9, "id": "t1.2", "err": "STALE_STATE"})
        receipt = decode_wire_response(wire(data={"id": "t1.1", "st": "COMPLETED"}))
        for frame in (None, observation(), decode_client_response(observation()), error, receipt):
            with self.subTest(frame=frame), self.assertRaises(IntentError) as raised:
                map_cell(frame, 0)
            self.assertEqual("NO_CURRENT_FRAME" if frame is None else "INVALID_FRAME", raised.exception.code)
            self.assertEqual(("current_frame",), raised.exception.fields)

    def test_map_absence_and_unknown_gaps_remain_none_without_needing_a_revision(self):
        self.assertIsNone(map_cell(decode_wire_response(observation()), 0))
        source = wire(revision=None, data={"phase": "resolving", "map": {
            "w": 2, "h": 1, "types": [{"name": "Floor"}], "rows": [[0, 0, "0", "v"]],
        }})
        frame = decode_wire_response(source)
        self.assertEqual("Floor", map_cell(frame, 0)["name"])
        self.assertIsNone(map_cell(frame, 1))
        with self.assertRaises(IntentError) as raised:
            map_cell(frame, True)
        self.assertEqual("INVALID_VALUE", raised.exception.code)


class IntentDiagnosticTest(unittest.TestCase):
    def test_direction_values_are_explicit_and_input_is_never_corrected(self):
        for direction in ("north", "n", 0, True, [], None):
            intent = {"op": "move", "rev": "r1", "dir": direction}
            before = copy.deepcopy(intent)
            with self.subTest(direction=direction), self.assertRaises(IntentError) as raised:
                validate_intent(decode_client_response(observation()), intent)
            error = raised.exception
            self.assertIsInstance(error, ValueError)
            self.assertEqual(("INVALID_VALUE", "move", ("dir",)),
                             (error.code, error.operation, error.fields))
            self.assertEqual(("N", "NE", "E", "SE", "S", "SW", "W", "NW"), error.expected)
            self.assertIn("N, NE, E, SE, S, SW, W, NW", str(error))
            self.assertEqual(before, error.raw_intent)
            self.assertEqual(before, intent)
            intent["dir"] = "E"
            self.assertEqual(before, error.raw_intent)

    def test_select_reports_all_extra_fields_in_stable_order(self):
        intent = {"op": "select", "ctl": "c1", "rev": "r1", "value": 1, "opt": 0}
        with self.assertRaises(IntentError) as raised:
            _validate_intent_syntax(intent)
        error = raised.exception
        self.assertEqual(("UNSUPPORTED_FIELDS", "select", ("opt", "value")),
                         (error.code, error.operation, error.fields))
        self.assertEqual(("op", "s", "rev", "ctl"), error.expected)
        self.assertIn("fields: opt, value", str(error))
        self.assertEqual(intent, error.raw_intent)

    def test_allowed_parameter_diagnostics_use_stable_sorted_order(self):
        intent = {"op": "choose", "ctl": "c1", "opt": 0, "rev": "r1", "extra": 1}
        for source in (intent, dict(reversed(tuple(intent.items())))):
            with self.assertRaises(IntentError) as raised:
                _validate_intent_syntax(source)
            self.assertEqual(("op", "s", "rev", "alt", "ctl", "opt"), raised.exception.expected)

    def test_controller_identity_and_other_extra_fields_are_reported_together(self):
        intent = {"op": "select", "ctl": "c1", "rev": "r1", "v": 9, "id": "mine", "junk": 0}
        before = copy.deepcopy(intent)
        with self.assertRaises(IntentError) as raised:
            validate_intent(None, intent)
        error = raised.exception
        self.assertEqual("UNSUPPORTED_FIELDS", error.code)
        self.assertEqual(("id", "junk", "v"), error.fields)
        self.assertEqual(("op", "s", "rev", "ctl"), error.expected)
        self.assertIn("controller owns v and id", str(error))
        self.assertEqual(before, error.raw_intent)
        self.assertEqual(before, intent)
        with self.assertRaisesRegex(IntentError, "not a known controller operation"):
            validate_intent(None, {"op": "unknown", "v": 9, "id": "mine", "junk": 0})

    def test_revision_and_scope_mismatch_expose_current_bindings_without_changing_intent(self):
        frame = decode_wire_response(observation())
        for intent, field, expected in (
            ({"op": "move", "dir": "N", "rev": "old"}, "rev", {"rev": "r1"}),
            ({"op": "move", "dir": "N", "rev": "r1", "s": "old"}, "s", {"s": "s1"}),
        ):
            before = copy.deepcopy(intent)
            with self.subTest(field=field), self.assertRaises(IntentError) as raised:
                validate_intent(frame, intent)
            error = raised.exception
            self.assertEqual(("BINDING_MISMATCH", (field,), expected),
                             (error.code, error.fields, error.expected))
            self.assertEqual(before, error.raw_intent)
            self.assertEqual(before, intent)

    def test_cancel_mismatch_retains_separate_candidate_bindings_by_copy(self):
        source = wire(status="in_progress", revision="a1", data={
            "phase": "continuous_activity", "activity": {"rid": "t1.1"},
            "acts": [{"op": "cancel"}, {"op": "cancel", "rid": "t1.2", "rev": "a1"}],
        })
        frame = decode_wire_response(source)
        intent = {"op": "cancel", "rev": "a1", "rid": "other"}
        before = copy.deepcopy(intent)
        with self.assertRaises(IntentError) as raised:
            validate_intent(frame, intent)
        error = raised.exception
        expected = ({"rid": "t1.1", "rev": "a1"}, {"rid": "t1.2", "rev": "a1"})
        self.assertEqual(expected, error.expected)
        self.assertEqual("BINDING_MISMATCH", error.code)
        self.assertEqual(("rid", "rev"), error.fields)
        self.assertEqual(before, error.raw_intent)
        self.assertEqual(before, intent)
        frame.data["acts"][0]["rid"] = "changed"
        self.assertEqual(expected, error.expected)

    def test_missing_cancel_id_differs_from_invalid_or_mismatched_id(self):
        source = wire(status="in_progress", revision="a1", data={
            "phase": "continuous_activity", "activity": {"rid": "t1.1"}, "acts": [{"op": "cancel"}],
        })
        frame = decode_wire_response(source)
        for extra, code in (({}, "MISSING_FIELD"), ({"rid": None}, "INVALID_VALUE"),
                            ({"rid": ""}, "INVALID_VALUE"), ({"rid": "other"}, "BINDING_MISMATCH")):
            intent = {"op": "cancel", "rev": "a1", **extra}
            with self.subTest(extra=extra), self.assertRaises(IntentError) as raised:
                validate_intent(frame, intent)
            self.assertEqual(code, raised.exception.code)
            self.assertEqual("cancel", raised.exception.operation)
            self.assertEqual(intent, raised.exception.raw_intent)
            self.assertIn("rid", raised.exception.fields)
        intent = {"op": "cancel", "rev": "a1", "rid": "t1.1"}
        self.assertEqual(intent, validate_intent(frame, intent).intent)

    def test_query_and_settle_validation_still_need_no_frame(self):
        for intent in ({"op": "info"}, {"op": "state"}, {"op": "req", "rid": "t1.1"},
                       {"op": "settle"}, {"op": "settle", "rid": "t1.1", "timeout_ms": 1}):
            self.assertEqual(intent, validate_intent(None, intent).intent)
        with self.assertRaises(IntentError) as raised:
            validate_intent(None, {"op": "move", "dir": "N", "rev": "r1"})
        self.assertEqual("NO_CURRENT_FRAME", raised.exception.code)
        self.assertEqual("move", raised.exception.operation)

    def test_invalid_object_and_field_name_have_controlled_errors(self):
        for intent in (None, {"op": "select", 0: True, "extra": 0}):
            with self.subTest(intent=intent), self.assertRaises(IntentError) as raised:
                validate_intent(None, intent)
            self.assertEqual(intent, raised.exception.raw_intent)


class SettlePendingDiagnosticsTest(unittest.TestCase):
    def test_old_pending_is_unknown_and_not_a_failure_problem(self):
        result = decode_client_response({"controller": "settle", "st": "pending", "rid": "t1.1"})
        self.assertIsNone(result.pending_reason)
        self.assertIsNone(result.stage)
        self.assertIsNone(result.pending_request)
        self.assertEqual((), result.problems)
        self.assertFalse(result.ok)
        with self.assertRaises(ClientResponseError):
            result.require_success()

    def test_all_explicit_pending_reasons_preserve_evidence_and_remain_pending(self):
        for reason in ("action_pending", "budget_exhausted", "response_pending"):
            for stage, op in (("receipt", "req"), ("discovery", "info"), ("observation", "state")):
                if reason == "action_pending" and stage != "receipt":
                    continue
                receipt_status = "EXECUTING" if reason == "action_pending" else "COMPLETED"
                source = {"controller": "settle", "st": "pending", "rid": "t1.1", "s": "s1",
                          "pending_reason": reason, "stage": stage,
                          "outcome": wire("t1.3", revision=None, data={"id": "t1.1", "st": receipt_status,
                                                                     "save": [{"sid": "p1"}]}),
                          "late_responses": [{"request": {"id": "t1.0"}, "response": observation()}]}
                if reason == "response_pending":
                    source["pending_request"] = {"v": 9, "id": "t1.4", "op": op,
                                                 "future": {"kept": True}}
                    if stage == "receipt":
                        source["pending_request"].update(rid="t1.1", s="s1")
                    elif stage == "observation":
                        source["pending_request"]["s"] = "s2"
                before = copy.deepcopy(source)
                with self.subTest(reason=reason, stage=stage):
                    result = decode_client_response(source)
                    self.assertEqual((reason, stage), (result.pending_reason, result.stage))
                    self.assertEqual(source.get("pending_request"), result.pending_request)
                    self.assertEqual(before, result.raw)
                    self.assertEqual(before, source)
                    self.assertEqual((), result.problems)
                    self.assertFalse(result.ok)
                    self.assertIsNone(result.current_frame)
                    self.assertEqual("p1", result.outcome.data["save"][0]["sid"])
                    self.assertEqual(1, len(result.late_responses))
                    if result.pending_request is not None:
                        result.pending_request["future"]["kept"] = False
                        self.assertEqual(before, result.raw)
                    with self.assertRaises(FrozenInstanceError):
                        result.pending_reason = "action_pending"

    def test_transport_error_and_initial_error_remain_independent_of_pending_reason(self):
        source = {"controller": "settle", "st": "pending", "rid": "t1.1",
                  "pending_reason": "budget_exhausted", "stage": "receipt",
                  "transport_error": {"controller": "error", "err": "RESPONSE_TIMEOUT"},
                  "initial_error": {"v": 9, "id": "t1.1", "err": "EXECUTION_UNKNOWN"}}
        result = decode_client_response(source)
        self.assertEqual("budget_exhausted", result.pending_reason)
        self.assertEqual({"RESPONSE_TIMEOUT", "EXECUTION_UNKNOWN"}, {p.code for p in result.problems})
        self.assertEqual(source, result.raw)

    def test_invalid_pending_fields_are_evidence_preserving_decode_errors(self):
        base = {"controller": "settle", "st": "pending", "rid": "t1.1", "s": "s1",
                "pending_reason": "response_pending", "stage": "receipt"}
        query = {"v": 9, "id": "t1.3", "op": "req", "rid": "t1.1", "s": "s1"}
        invalid = [
            {"pending_reason": value} for value in (None, True, [], {}, "unknown")
        ] + [{"stage": value} for value in (None, [], {}, "unknown")] + [
            {"pending_reason": "action_pending", "st": "completed"},
            {"stage": "receipt", "st": "error"},
            {"pending_reason": "response_pending"},
            {"pending_reason": "budget_exhausted", "pending_request": query},
            {"pending_reason": "response_pending", "pending_request": None},
            {"pending_reason": "response_pending", "pending_request": {**query, "v": True}},
            {"pending_reason": "response_pending", "pending_request": {**query, "id": ""}},
            {"pending_reason": "response_pending", "pending_request": {**query, "op": "move"}},
            {"pending_reason": "response_pending", "stage": "observation", "pending_request": query},
        ]
        for extra in invalid:
            source = {**base, **extra}
            before = copy.deepcopy(source)
            with self.subTest(extra=extra), self.assertRaises(DecodeError) as raised:
                decode_client_response(source)
            self.assertEqual(before, raised.exception.raw)
            self.assertEqual(before, raised.exception.context["raw"])
            self.assertEqual(before, source)

    def test_partial_pending_metadata_and_wrong_action_stage_are_rejected(self):
        base = {"controller": "settle", "st": "pending", "rid": "t1.1", "s": "s1"}
        for extra in (
            {"pending_reason": "action_pending"},
            {"stage": "receipt"},
            {"pending_request": {"v": 9, "id": "t1.2", "op": "req", "rid": "t1.1", "s": "s1"}},
            {"pending_reason": "action_pending", "stage": "discovery"},
            {"pending_reason": "action_pending", "stage": "observation"},
        ):
            source = {**base, **extra}
            before = copy.deepcopy(source)
            with self.subTest(extra=extra), self.assertRaises(DecodeError) as raised:
                decode_client_response(source)
            self.assertEqual(before, raised.exception.raw)
            self.assertEqual(before, raised.exception.context["raw"])
            self.assertEqual(before, source)

    def test_pending_receipt_query_binds_the_original_action_and_scope(self):
        query = {"v": 9, "id": "t1.3", "op": "req", "rid": "t1.1", "s": "s1"}
        base = {"controller": "settle", "st": "pending", "rid": "t1.1", "s": "s1",
                "pending_reason": "response_pending", "stage": "receipt", "pending_request": query}
        self.assertEqual(query, decode_client_response(base).pending_request)
        for field in ("rid", "s"):
            for value in (None, "", "other"):
                source = {**base, "pending_request": {**query, field: value}}
                with self.subTest(field=field, value=value), self.assertRaises(DecodeError) as raised:
                    decode_client_response(source)
                self.assertEqual(source, raised.exception.raw)
                self.assertEqual(source, raised.exception.context["raw"])
            for owner in ("wrapper", "query"):
                source = copy.deepcopy(base)
                del (source if owner == "wrapper" else source["pending_request"])[field]
                with self.subTest(field=field, owner=owner), self.assertRaises(DecodeError) as raised:
                    decode_client_response(source)
                self.assertEqual(source, raised.exception.raw)
                self.assertEqual(source, raised.exception.context["raw"])


if __name__ == "__main__":
    unittest.main()
