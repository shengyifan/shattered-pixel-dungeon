"""Offline displayed-bar compatibility regressions; no game or profile access."""

import copy
import unittest

from test_actions_subject_data import actions_frame, owners
from test_combat_visual_wire import frame, snapshot
from spdctl_client import DecodeError, decode_client_response, decode_wire_response


def sample(total=21, filled=4, with_shield=22):
    return {"total": total, "filled": filled, "with_shield": with_shield,
            "basis": "displayed"}


def bar_snapshot(samples, prefix="c"):
    data = snapshot(prefix)
    data["entity_defs"][0]["health_estimate"] = {"samples": copy.deepcopy(samples)}
    return data


def samples_from(data):
    return data["entities"][0]["health_estimate"]["samples"]


class DisplayedHealthRoundingTest(unittest.TestCase):
    def test_integer_and_one_segment_overhang_samples_are_preserved(self):
        # Synthetic public facts only. 21/4/22 reproduces the reported bar shape;
        # 28/29/29 covers a full-health foreground with the same rounding gap.
        accepted = [sample(16, 0, 0), sample(16, 0, 4), sample(16, 16, 16),
                    sample(16, 4, 17), sample(21, 4, 22), sample(28, 29, 29)]
        for expanded in (False, True):
            data = bar_snapshot(accepted)
            if expanded:
                entity = copy.deepcopy(data["entity_defs"][0])
                entity["cell"] = data["entities"][0]["cell"]
                data["entities"][0] = entity
            source = frame(data, identifier="rounded-state", revision="r9")
            original = copy.deepcopy(source)
            with self.subTest(expanded=expanded):
                decoded = decode_wire_response(source)
                self.assertEqual(accepted, samples_from(decoded.data))
                self.assertEqual(("rounded-state", "s1", "r9"),
                                 tuple(decoded.frame[key] for key in ("id", "s", "rev")))
                self.assertEqual(original, source)
                self.assertEqual(original, decoded.raw)
                samples_from(decoded.data)[-1]["filled"] = 1
                self.assertEqual(original, source)
                self.assertEqual(original, decoded.raw)

    def test_type_order_basis_and_more_than_one_segment_errors_still_fail(self):
        invalid = []
        for key in ("total", "filled", "with_shield"):
            for value in (True, False, 4.0, -1, None, "4"):
                invalid.append({**sample(), key: value})
            missing = sample()
            del missing[key]
            invalid.append(missing)
        invalid.extend([sample(0, 0, 0), sample(21, 5, 4), sample(21, 4, 23),
                        sample(28, 30, 30), sample(21, 23, 22)])
        for basis in (None, True, 0, "hidden", "displayed_gradient"):
            invalid.append({**sample(), "basis": basis})
        missing_basis = sample()
        del missing_basis["basis"]
        invalid.append(missing_basis)
        for index, invalid_sample in enumerate(invalid):
            source = frame(bar_snapshot([sample(), invalid_sample]),
                           identifier="invalid-state", revision="r10")
            original = copy.deepcopy(source)
            with self.subTest(case=index, sample=invalid_sample):
                with self.assertRaisesRegex(DecodeError, r"health_estimate.*samples\[1\]") as raised:
                    decode_wire_response(source)
                self.assertEqual("invalid-state", raised.exception.response_id)
                self.assertEqual("s1", raised.exception.scope)
                self.assertEqual(original, raised.exception.raw)
                self.assertEqual(original, source)

    def test_frozen_snapshots_decode_independently_and_history_remains_opaque(self):
        before = bar_snapshot([sample()], "b")
        after = bar_snapshot([sample(28, 29, 29)], "a")
        nested_before = bar_snapshot([sample(16, 4, 17)], "n")
        before.update(s="s2", rev="r2")
        after.update(s="s3", rev="r3", before=nested_before)
        nested_before.update(s="s4", rev="r4")
        opaque = frame(bar_snapshot([sample(21, 4, 99)]), identifier="historical-reply")
        source = frame({"before": before, "after": after, "raw": opaque, "reply": opaque},
                       identifier="history-query", revision="r99")
        original = copy.deepcopy(source)
        decoded = decode_client_response(source)
        self.assertIsNone(decoded.current_frame)
        body = decoded.response.data
        for key, expected in (("before", sample()), ("after", sample(28, 29, 29))):
            self.assertEqual([expected], samples_from(body[key]))
        self.assertEqual([sample(16, 4, 17)], samples_from(body["after"]["before"]))
        self.assertEqual(("r2", "r3", "r4"), (body["before"]["rev"], body["after"]["rev"],
                                                 body["after"]["before"]["rev"]))
        self.assertEqual(opaque, body["raw"])
        self.assertEqual(opaque, body["reply"])
        samples_from(body["after"])[0]["filled"] = 1
        self.assertEqual([sample()], samples_from(body["before"]))
        self.assertEqual([sample(16, 4, 17)], samples_from(body["after"]["before"]))
        self.assertEqual(original, source)
        self.assertEqual(original, decoded.response.raw)

        # A valid sibling cannot hide malformed segments in a nested snapshot.
        for key in ("before", "after"):
            broken = copy.deepcopy(source)
            broken["data"][key]["entity_defs"][0]["health_estimate"]["samples"] = [sample(21, 4, 23)]
            with self.subTest(snapshot=key), self.assertRaisesRegex(DecodeError, "health_estimate"):
                decode_wire_response(broken)

    def test_standalone_actions_subject_data_uses_the_same_bounded_tolerance(self):
        nodes = owners()
        nodes[3]["subject_data"]["health_estimate"]["samples"] = [sample(), sample(28, 29, 29)]
        source = actions_frame(nodes, identifier="rounded-actions", revision="r11")
        original = copy.deepcopy(source)
        decoded = decode_wire_response(source)
        facts = decoded.data["ui"]["nodes"][3]["subject_data"]
        self.assertEqual([sample(), sample(28, 29, 29)], facts["health_estimate"]["samples"])
        self.assertNotIn("entities", decoded.data)
        self.assertEqual(original, source)
        self.assertEqual(original, decoded.raw)
        for invalid_sample in (sample(21, 4, 23), sample(21, 23, 22),
                               {**sample(), "filled": True}):
            broken = copy.deepcopy(source)
            broken["data"]["ui"]["nodes"][3]["subject_data"]["health_estimate"]["samples"] = [invalid_sample]
            with self.subTest(sample=invalid_sample), self.assertRaisesRegex(DecodeError, "subject_data.*health_estimate"):
                decode_wire_response(broken)

    def test_controller_observation_keeps_its_own_identity_and_outcome(self):
        observation = frame(bar_snapshot([sample()], "o"), identifier="fresh-state", revision="r12")
        old = frame(bar_snapshot([sample(28, 29, 29)], "p"), identifier="prior-action", revision="r4")
        outcome = frame({"id": "original-action", "op": "cell", "st": "COMPLETED", "save": []},
                        identifier="receipt-query")
        outcome.pop("rev")
        late = [{"request": {"id": "prior-action"}, "response": old}]
        for controller in ("response", "settle"):
            source = {"controller": controller, "s": "wrapper-scope", "rev": "wrapper-revision",
                      "late_responses": late}
            if controller == "response":
                source["response"] = observation
            else:
                source.update(st="completed", rid="original-action", outcome=outcome,
                              observation=observation)
            original = copy.deepcopy(source)
            with self.subTest(controller=controller):
                decoded = decode_client_response(source)
                self.assertTrue(decoded.ok)
                current = decoded.current_frame
                self.assertEqual(("fresh-state", "s1", "r12"),
                                 tuple(current.frame[key] for key in ("id", "s", "rev")))
                self.assertEqual([sample()], samples_from(current.data))
                historical = decoded.late_responses[0].response
                self.assertEqual("r4", historical.frame["rev"])
                self.assertEqual([sample(28, 29, 29)], samples_from(historical.data))
                if controller == "settle":
                    self.assertIs(decoded.observation, current)
                    self.assertEqual("original-action", decoded.outcome.data["id"])
                    self.assertEqual("COMPLETED", decoded.outcome.data["st"])
                    self.assertEqual([], decoded.outcome.data["save"])
                    self.assertNotIn("rev", decoded.outcome.frame)
                self.assertEqual(original, source)
                self.assertEqual(original, decoded.raw)

    def test_invalid_observation_preserves_query_identity_and_completed_action_context(self):
        observation = frame(bar_snapshot([sample(21, 4, 23)]), identifier="failed-state", revision="r13")
        outcome = frame({"id": "original-action", "op": "cell", "st": "COMPLETED", "save": []},
                        identifier="receipt-query")
        outcome.pop("rev")
        source = {"controller": "settle", "st": "completed", "rid": "original-action",
                  "outcome": outcome, "observation": observation}
        original = copy.deepcopy(source)
        with self.assertRaisesRegex(DecodeError, "health_estimate") as raised:
            decode_client_response(source)
        error = raised.exception
        self.assertEqual("failed-state", error.response_id)
        self.assertEqual("s1", error.scope)
        self.assertEqual("observation", error.stage)
        self.assertEqual(observation, error.raw)
        self.assertEqual(original, error.context["raw"])
        self.assertEqual("original-action", error.context["raw"]["rid"])
        self.assertEqual("COMPLETED", error.context["raw"]["outcome"]["data"]["st"])
        self.assertEqual(original, source)


if __name__ == "__main__":
    unittest.main()
