"""Strict public display contracts across complete protocol-9 responses; no game access."""

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "client"))
from spdctl_client import DecodeError, decode_client_response, decode_wire_response


def frame(data, identifier="display-query", revision="display-revision"):
    return {"v": 9, "id": identifier, "s": "display-scope", "rev": revision,
            "st": "completed", "data": data}


def sample(**changes):
    result = {"total": 21, "filled": 4, "with_shield": 22, "incoming_dot": 0,
              "basis": "displayed"}
    result.update(changes)
    return result


def packed_node(**facts):
    return {"ui": {"node_templates": [{"common": {"role": "entry", **facts},
                                      "fields": ["id"]}],
                   "nodes": [[0, "display"]]}}


def glow_surfaces(glow):
    """Independent native semantic owners; no previous-frame tables are available."""
    item = {"loc": "backpack.0", "name": "Visible item", "shown": {"glow": glow}}
    icon = {"symbol": "item_sword", "glow": glow}
    surfaces = [
        ("inventory", {"inv": [item]}),
        ("inventory_template", {"inv_templates": [{"common": {"shown": {"glow": glow}},
                                                   "fields": ["loc"]}],
                                "inv": [[0, "backpack.0"]]}),
        ("floor_item", {"entities": [{"kind": "item", "cell": 2, "item": item}]}),
        ("ui_shown", packed_node(shown={"glow": glow})),
        ("standalone_actions", {"ui": {"nodes": [{"id": "item", "ops": [],
                                                    "subject_data": item}]}}),
        ("badge", {"inv": [{"loc": "backpack.0", "shown": {"badge": icon}}]}),
        ("world_glow", {"cues": {"status": "last_observed", "cues": [
            {"kind": "item_glow", "cell": 2, "appearance": glow}]}}),
        ("world_icon", {"cues": {"status": "last_observed", "cues": [
            {"kind": "missile_projectile", "cell": 2, "appearance": icon}]}}),
        ("feedback_icon", {"ui": {"feedback": [{"kind": "floating", "icon": icon}]}}),
        ("preview_icon", packed_node(preview_icons=[None, icon])),
    ]
    for key in ("icon", "primary_icon", "secondary_icon", "target_icon", "spell_icon"):
        surfaces.append((key, packed_node(**{key: icon})))
    return surfaces


def display_surfaces(facts):
    return [
        ("node", {"ui": {"nodes": [{"id": "display", **facts}]}}),
        ("template", packed_node(**facts)),
        ("actions", {"ui": {"nodes": [{"id": "display", "ops": [], **facts}]}}),
        ("before", {"before": {"s": "old-scope", "rev": "old-revision",
                                **packed_node(**facts)}}),
        ("after", {"after": {"s": "new-scope", "rev": "new-revision",
                              **packed_node(**facts)}}),
    ]


class Cli904SemanticContractTest(unittest.TestCase):
    def assertAcceptedUnchanged(self, data):
        source = frame(data)
        original = copy.deepcopy(source)
        decoded = decode_wire_response(source)
        self.assertEqual(original, source)
        self.assertEqual(original, decoded.raw)
        return decoded.data

    def assertRejectedWithEvidence(self, data, pattern):
        source = frame(data, identifier="failed-display-query")
        original = copy.deepcopy(source)
        with self.assertRaisesRegex(DecodeError, pattern) as raised:
            decode_wire_response(source)
        error = raised.exception
        self.assertEqual("failed-display-query", error.response_id)
        self.assertEqual("display-scope", error.scope)
        self.assertEqual(original, error.raw)
        self.assertEqual(original, source)

    def test_crystal_stages_survive_every_semantic_surface_and_frozen_snapshot(self):
        for stage in ("intact", "flawed", "cracked"):
            glow = {"variant": "crystal_blue", "kind": "crystal_condition", "stage": stage,
                    "future": [None, False, 0]}
            for name, data in glow_surfaces(glow):
                with self.subTest(stage=stage, surface=name):
                    decoded = self.assertAcceptedUnchanged(data)
                    frozen = self.assertAcceptedUnchanged({"before": {"s": "old", "rev": "r0", **data},
                                                           "after": {"s": "new", "rev": "r2", **data}})
                    self.assertEqual(decoded, {key: value for key, value in frozen["before"].items()
                                               if key not in {"s", "rev"}})
                    self.assertEqual("r2", frozen["after"]["rev"])
                    frozen["after"]["rev"] = "changed-copy"
                    self.assertEqual("r0", frozen["before"]["rev"])

    def test_existing_glow_forms_and_explicit_null_remain_valid_without_filling_fields(self):
        glows = [{"variant": "blue"}, {"variant": "blue", "kind": "resin_fortified"},
                 {"variant": "black", "kind": "explosive_heat"},
                 {"variant": "crystal_blue", "kind": "crystal_condition"}]
        glows.extend({"variant": "orange", "kind": "explosive_heat", "stage": stage}
                     for stage in ("cool", "warm", "hot"))
        for glow in glows:
            with self.subTest(glow=glow):
                decoded = self.assertAcceptedUnchanged(packed_node(icon={"symbol": "item_sword", "glow": glow}))
                self.assertEqual(glow, decoded["ui"]["nodes"][0]["icon"]["glow"])
        decoded = self.assertAcceptedUnchanged(packed_node(
            icon={"symbol": "item_sword", "glow": None}, shown={"glow": None}))
        self.assertIsNone(decoded["ui"]["nodes"][0]["icon"]["glow"])
        self.assertIsNone(decoded["ui"]["nodes"][0]["shown"]["glow"])
        decoded = self.assertAcceptedUnchanged(packed_node(icon={"symbol": "item_sword"}, shown={}))
        self.assertNotIn("glow", decoded["ui"]["nodes"][0]["icon"])
        self.assertNotIn("glow", decoded["ui"]["nodes"][0]["shown"])

    def test_malformed_nested_glow_is_rejected_on_the_same_surfaces_as_direct_glow(self):
        base = {"variant": "crystal_blue", "kind": "crystal_condition", "stage": "intact"}
        invalid = [{**base, field: value} for field in ("variant", "kind", "stage")
                   for value in (None, False, 1, 1.0, [], {}, "")]
        invalid += [{**base, "kind": "hidden_durability"}, {**base, "stage": "remaining_10"},
                    {**base, "rgb": [0, 1, 1]}]
        for glow in invalid:
            for name, data in glow_surfaces(glow):
                with self.subTest(glow=glow, surface=name):
                    self.assertRejectedWithEvidence(data, "glow|appearance")

    def test_glow_stages_require_their_native_kind_on_all_surfaces_and_frozen_frames(self):
        invalid = [
            {"variant": "crystal_blue", "kind": "crystal_condition", "stage": stage}
            for stage in ("cool", "warm", "hot")
        ] + [
            {"variant": "black", "kind": "explosive_heat", "stage": stage}
            for stage in ("intact", "flawed", "cracked")
        ]
        for stage in ("cool", "warm", "hot", "intact", "flawed", "cracked"):
            invalid.append({"variant": "white", "kind": "resin_fortified", "stage": stage})
            invalid.append({"variant": "blue", "stage": stage})
        for glow in invalid:
            for name, data in glow_surfaces(glow):
                for owner, envelope in (("live", data), ("before", {"before": data}),
                                        ("after", {"after": data})):
                    with self.subTest(glow=glow, surface=name, owner=owner):
                        self.assertRejectedWithEvidence(envelope, "stage must match")

    def test_mismatched_glow_keeps_diagnostics_and_original_controller_action_evidence(self):
        diagnostics = {"st": "partial", "diag": [{"field": "stage", "code": "captured_native_mismatch"}]}
        glow = {"variant": "crystal_blue", "kind": "crystal_condition", "stage": "hot",
                "pres": diagnostics, "future": [None, False, 0]}
        observation = frame(packed_node(icon={"symbol": "item_sword", "glow": glow}),
                            identifier="fresh-state-query")
        outcome = {"v": 9, "id": "receipt-query", "s": "display-scope", "st": "completed",
                   "data": {"id": "original-action", "op": "click", "st": "COMPLETED",
                            "save": [{"sid": "original-save"}]}}
        source = {"controller": "settle", "st": "completed", "rid": "original-action",
                  "request": {"op": "click", "rev": "old-decision", "ctl": "c7"},
                  "outcome": outcome, "observation": observation}
        original = copy.deepcopy(source)
        with self.assertRaisesRegex(DecodeError, "icon.glow.stage must match") as raised:
            decode_client_response(source)
        error = raised.exception
        self.assertEqual("fresh-state-query", error.response_id)
        self.assertEqual("display-scope", error.scope)
        self.assertEqual("observation", error.stage)
        self.assertEqual(observation, error.raw)
        self.assertEqual(diagnostics, error.raw["data"]["ui"]["node_templates"][0]["common"]["icon"]["glow"]["pres"])
        self.assertEqual(original, error.context["raw"])
        self.assertEqual("original-action", error.context["raw"]["rid"])
        self.assertEqual(outcome, error.context["raw"]["outcome"])
        self.assertEqual(source["request"], error.context["raw"]["request"])
        self.assertEqual(original, source)

    def test_native_unmapped_glow_diagnostics_survive_all_paths_and_frozen_snapshots(self):
        diagnostic = {"field": "symbol", "code": "unmapped_indicator"}
        for metadata in ({"pres": {"st": "partial", "diag": [diagnostic]}},
                         {"presentation": {"status": "partial", "diagnostics": [diagnostic]}}):
            glow = {"symbol": "unmapped_indicator", **metadata,
                    "future": [None, False, 0]}
            for name, data in glow_surfaces(glow):
                with self.subTest(surface=name, metadata=metadata):
                    decoded = self.assertAcceptedUnchanged(data)
                    frozen = self.assertAcceptedUnchanged({"before": data, "after": data})
                    self.assertEqual(decoded, frozen["before"])
                    self.assertEqual(decoded, frozen["after"])

    def test_unmapped_glow_requires_the_explicit_native_marker_and_cannot_bypass_invalid_fields(self):
        valid = {"symbol": "unmapped_indicator", "pres": {"st": "partial", "diag": [
            {"field": "symbol", "code": "unmapped_indicator"}]}}
        invalid = [{}, {"symbol": "unmapped_indicator"}, {"pres": valid["pres"]},
                   {**valid, "symbol": True}, {**valid, "symbol": "item_sword"},
                   {**valid, "pres": None}, {**valid, "pres": {"st": "complete", "diag": valid["pres"]["diag"]}},
                   {**valid, "pres": {"st": "partial", "diag": []}},
                   {**valid, "pres": {"st": "partial", "diag": {"symbol": "unmapped_indicator"}}},
                   {**valid, "pres": {"st": "partial", "diag": [{"field": "variant", "code": "unmapped_indicator"}]}},
                   {**valid, "pres": {"st": "partial", "diag": [{"field": "symbol", "code": "other"}]}},
                   {**valid, "presentation": {"status": "complete", "diagnostics": []}},
                   {**valid, "rgb": [0.1, 0.2, 0.3]},
                   {**valid, "unmapped_indicator": False}, {**valid, "unmapped_indicator": 1}]
        invalid.extend({**valid, field: value}
                       for field, values in (("variant", (None, "", 1)),
                                             ("kind", (None, "crystal_condition", "private_kind")),
                                             ("stage", (None, "intact", "private_stage")))
                       for value in values)
        for glow in invalid:
            for name, data in glow_surfaces(glow):
                with self.subTest(glow=glow, surface=name):
                    self.assertRejectedWithEvidence(data, "glow|appearance")
        # A real named variant does not make unreviewed kind/stage legal just
        # because an unrelated partial diagnostic is present alongside it.
        for field, value in (("kind", "private_kind"), ("stage", "private_stage")):
            self.assertRejectedWithEvidence(packed_node(shown={"glow": {
                **valid, "variant": "crystal_blue", field: value}}), field)

    def test_catalog_and_ingredient_states_keep_false_unknown_metadata_and_separate_snapshots(self):
        for state in ("undiscovered", "discovered", "unread", "read"):
            shown = {"catalog_state": state, "ingredient_sufficient": False,
                     "ingredients_sufficient": True, "future": [None, False, 0]}
            data = packed_node(shown=shown)
            data["before"] = packed_node(shown={"catalog_state": "unread",
                                               "ingredients_sufficient": False})
            data["before"].update(s="old-scope", rev="old-revision")
            with self.subTest(state=state):
                decoded = self.assertAcceptedUnchanged(data)
                self.assertEqual(shown, decoded["ui"]["nodes"][0]["shown"])
                self.assertIs(decoded["before"]["ui"]["nodes"][0]["shown"]["ingredients_sufficient"], False)
        decoded = self.assertAcceptedUnchanged(packed_node(shown={"future": None}))
        self.assertEqual({"future": None}, decoded["ui"]["nodes"][0]["shown"])

    def test_catalog_and_ingredient_types_are_strict_in_nodes_inventory_actions_and_history(self):
        invalid = [("catalog_state", value) for value in (None, False, 0, 1.0, [], {}, "", "known", "Read")]
        invalid += [(field, value) for field in ("ingredient_sufficient", "ingredients_sufficient")
                    for value in (None, 0, 1, 0.0, 1.0, "false", [], {})]
        for field, value in invalid:
            shown = {field: value}
            surfaces = display_surfaces({"shown": shown}) + [
                ("inventory", {"inv": [{"loc": "backpack.0", "shown": shown}]}),
                ("subject_data", {"ui": {"nodes": [{"id": "item", "subject_data": {
                    "loc": "backpack.0", "shown": shown}}]}})]
            for name, data in surfaces:
                with self.subTest(field=field, value=value, surface=name):
                    self.assertRejectedWithEvidence(data, field)

    def test_ui_health_and_turn_progress_use_the_existing_display_tolerance(self):
        facts = {"health_estimate": {"samples": [sample(), sample(filled=22, with_shield=22,
                                                                  incoming_dot=22)]},
                 "turn_progress": {"sweep": 0.0, "future": None}}
        for name, data in display_surfaces(facts):
            with self.subTest(surface=name):
                self.assertAcceptedUnchanged(data)
        for value in (0, 0.5, 1):
            decoded = self.assertAcceptedUnchanged(packed_node(turn_progress={"sweep": value}))
            self.assertEqual(value, decoded["ui"]["nodes"][0]["turn_progress"]["sweep"])
        decoded = self.assertAcceptedUnchanged(packed_node(health_estimate=None, turn_progress=None))
        self.assertIsNone(decoded["ui"]["nodes"][0]["health_estimate"])
        self.assertIsNone(decoded["ui"]["nodes"][0]["turn_progress"])
        decoded = self.assertAcceptedUnchanged(packed_node())
        self.assertNotIn("health_estimate", decoded["ui"]["nodes"][0])
        self.assertNotIn("turn_progress", decoded["ui"]["nodes"][0])

    def test_ui_health_rejects_missing_dot_wrong_types_order_and_excess_overhang(self):
        invalid = [sample(**{key: value}) for key in ("total", "filled", "with_shield", "incoming_dot")
                   for value in (None, False, 0.0, "0", -1)]
        missing = sample()
        del missing["incoming_dot"]
        invalid += [missing, sample(total=0), sample(filled=23), sample(with_shield=23),
                    sample(incoming_dot=23), sample(basis="hidden")]
        for bar in invalid:
            for name, data in display_surfaces({"health_estimate": {"samples": [bar]}}):
                with self.subTest(bar=bar, surface=name):
                    self.assertRejectedWithEvidence(data, "health_estimate")

    def test_ui_turn_progress_rejects_nonfinite_out_of_range_and_boolean_values(self):
        for value in (None, False, -0.01, 1.01, float("nan"), float("inf"), "0", [], {}):
            for name, data in display_surfaces({"turn_progress": {"sweep": value}}):
                with self.subTest(value=value, surface=name):
                    if isinstance(value, float) and value != value:
                        with self.assertRaisesRegex(DecodeError, "turn_progress") as raised:
                            decode_wire_response(frame(data))
                        self.assertEqual("display-query", raised.exception.response_id)
                    else:
                        self.assertRejectedWithEvidence(data, "turn_progress")

    def test_complete_controller_frame_retains_original_action_and_save_outcome(self):
        glow = {"variant": "crystal_blue", "kind": "crystal_condition", "stage": "cracked"}
        observation = frame({"inv": [{"loc": "backpack.0", "shown": {"glow": glow}}],
                             **packed_node(health_estimate={"samples": [sample()]},
                                           turn_progress={"sweep": 0.5})}, identifier="fresh-state")
        outcome = {"v": 9, "id": "receipt-query", "s": "display-scope", "st": "completed",
                   "data": {"id": "original-action", "op": "click", "st": "COMPLETED",
                            "save": [{"sid": "original-save"}]}}
        source = {"controller": "settle", "st": "completed", "rid": "original-action",
                  "request": {"op": "click", "rev": "old-decision", "ctl": "c7"},
                  "outcome": outcome, "observation": observation,
                  "late_responses": [{"request": {"id": "late-query"},
                                       "response": frame({"ui": {"nodes": []}}, "late-query", "old-live")}]}
        original = copy.deepcopy(source)
        decoded = decode_client_response(source)
        self.assertEqual("fresh-state", decoded.current_frame.frame["id"])
        self.assertEqual("display-revision", decoded.current_frame.frame["rev"])
        self.assertEqual("original-action", decoded.outcome.data["id"])
        self.assertEqual("COMPLETED", decoded.outcome.data["st"])
        self.assertEqual([{"sid": "original-save"}], decoded.outcome.data["save"])
        self.assertEqual(glow, decoded.current_frame.data["inv"][0]["shown"]["glow"])
        self.assertEqual("old-live", decoded.late_responses[0].response.frame["rev"])
        self.assertEqual(original, decoded.raw)
        self.assertEqual(original, source)

        source["observation"]["data"]["ui"]["node_templates"][0]["common"]["health_estimate"]["samples"][0]["incoming_dot"] = True
        with self.assertRaisesRegex(DecodeError, "health_estimate") as raised:
            decode_client_response(source)
        self.assertEqual("fresh-state", raised.exception.response_id)
        self.assertEqual("observation", raised.exception.stage)
        self.assertEqual(source["observation"], raised.exception.raw)
        self.assertEqual(source, raised.exception.context["raw"])
        self.assertEqual("original-action", raised.exception.context["raw"]["rid"])
        self.assertEqual(outcome, raised.exception.context["raw"]["outcome"])
        self.assertEqual(source["request"], raised.exception.context["raw"]["request"])

    def test_opaque_transport_and_source_asts_are_not_interpreted_as_live_facts(self):
        invalid = {"ui": {"nodes": [{"shown": {"catalog_state": "private"},
                                      "health_estimate": {"samples": [sample(incoming_dot=True)]},
                                      "icon": {"symbol": "item_sword", "glow": {"stage": "private"}}}]}}
        data = {"raw": invalid, "reply": invalid, "text_sources": {"text": invalid},
                **packed_node(shown={"catalog_state": "read"})}
        decoded = self.assertAcceptedUnchanged(data)
        for key in ("raw", "reply"):
            self.assertEqual(invalid, decoded[key])
        self.assertEqual({"text": invalid}, decoded["text_sources"])


if __name__ == "__main__":
    unittest.main()
