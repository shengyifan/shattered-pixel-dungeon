"""Offline CLI 9 knowledge and observation contracts; synthetic public frames only."""

import copy
import json
import unittest

from spdctl_client import DecodeError, decode_wire_response, map_cell
from test_actions_subject_data import actions_frame
from test_combat_visual_wire import frame
from test_health_estimate_rounding import sample


class Protocol9FactsTest(unittest.TestCase):
    def test_unknown_warning_lifecycle_survives_views_events_and_frozen_frames(self):
        cue = {"kind": "red_target", "cell": 4, "appearance": {"lifecycle": "unknown"}}
        known = {"kind": "red_target", "cell": 5}
        cues = {"status": "last_observed", "cues": [cue, known]}
        for view in ("play", "full", "src"):
            source = frame({"cues": cues, "before": {"cues": cues}})
            original = copy.deepcopy(source)
            decoded = decode_wire_response(source)
            with self.subTest(view=view):
                self.assertEqual(cues, decoded.data["cues"])
                self.assertEqual(cues, decoded.data["before"]["cues"])
                self.assertEqual(original, source)
        event = {"seq": 1, "kind": "visual.cues", "data": cues}
        source = frame({"items": [event], "next": None, "end": True})
        decoded = decode_wire_response(source)
        self.assertEqual([event], decoded.data["items"])
        self.assertFalse(decoded.is_observation)
        for value in (False, 0, 1.0, None, "complete", "", [], {}):
            invalid = copy.deepcopy(cues)
            invalid["cues"][0]["appearance"]["lifecycle"] = value
            for bad in (frame({"cues": invalid}), frame({"before": {"cues": invalid}})):
                with self.subTest(value=value), self.assertRaisesRegex(DecodeError, "lifecycle"):
                    decode_wire_response(bad)

    def test_decoder_retains_complete_protected_diagnostics(self):
        diagnostics = {"st": "partial", "diag": [
            {"field": "appearance.lifecycle", "code": "first", "source": "public_marker", "detail": False},
            {"field": "appearance.lifecycle", "code": "second", "details": {"extra": 0}}]}
        node = {"id": "partial", "ops": [], "pres": diagnostics}
        source = frame({"ui": {"nodes": [node]}})
        decoded = decode_wire_response(source)
        self.assertEqual(diagnostics, decoded.data["ui"]["nodes"][0]["pres"])
        self.assertEqual(diagnostics, source["data"]["ui"]["nodes"][0]["pres"])

    def test_collision_overrides_survive_effect_tables_and_independent_frozen_frames(self):
        effects = [{"type": "light_wall", "desc": "Public light", "collision": {
                       "passable": False, "avoid": False, "solid": True}},
                   {"type": "eternal_fire", "desc": "Public fire", "collision": {
                       "passable": False, "avoid": False}},
                   {"type": "web", "desc": "Public web", "collision": {"solid": True}}]
        for view in ("play", "full", "src"):
            public_map = {"w": 3, "h": 1, "types": [{"terrain": 1}], "rows": [[0, 0, "000", "v"]],
                          "effect_defs": [[effect] for effect in effects], "env": {"0": 0, "1": 1, "2": 2}}
            if view == "src":
                for definition in public_map["effect_defs"]:
                    definition[0]["text_sources"] = {"desc": {"kind": "literal", "origin": "external", "value": definition[0]["desc"]}}
            frozen = copy.deepcopy(public_map)
            frozen["effect_defs"] = list(reversed(frozen["effect_defs"]))
            source = frame({"map": public_map, "before": {"s": "old", "rev": "old", "map": frozen}})
            original = copy.deepcopy(source)
            with self.subTest(view=view):
                decoded = decode_wire_response(source)
                for cell, expected in enumerate(effects):
                    self.assertEqual(expected, map_cell(decoded, cell)["environment"][0])
                    self.assertEqual(effects[2 - cell], decoded.data["before"]["map"]["cells"][cell]["environment"][0])
                self.assertEqual(original, source)
                self.assertEqual(original, decoded.raw)

    def test_environment_collision_override_types_are_strict_without_filling_omitted_flags(self):
        base = {"w": 1, "h": 1, "types": [{"terrain": 1}], "rows": [[0, 0, "0", "v"]]}
        for value in (None, [], {}, {"solid": 1}, {"solid": 1.0}, {"solid": "true"},
                      {"solid": None}, {"passable": False, "solid": True, "hidden_flag": False}):
            current = {**base, "env": {"0": [{"type": "web", "collision": value}]}}
            for source in (frame({"map": current}), frame({"before": {"map": current}})):
                original = copy.deepcopy(source)
                with self.subTest(value=value), self.assertRaisesRegex(DecodeError, "collision") as raised:
                    decode_wire_response(source)
                self.assertEqual(original, source)
                self.assertEqual(original, raised.exception.raw)

    def test_tab_selection_and_talent_points_survive_templates_actions_and_frozen_frames(self):
        for spent in (0, 1, 2):
            ui = {"node_templates": [{"common": {"role": "button", "ops": [], "icon": {"symbol": "talent"}},
                                      "fields": ["id", "selected", "talent_points"]}],
                  "nodes": [[0, "tab", False, {"spent": spent, "max": 2}]]}
            before = copy.deepcopy(ui)
            before["nodes"][0][2] = True
            before["nodes"][0][3]["spent"] = 2 - spent
            sources = [frame({"ui": ui, "before": {"ui": before}}),
                       actions_frame([{"id": "talent", "ops": [], "selected": False,
                                       "talent_points": {"spent": spent, "max": 2}}])]
            for source in sources:
                original = copy.deepcopy(source)
                decoded = decode_wire_response(source)
                node = decoded.data["ui"]["nodes"][0]
                self.assertIs(node["selected"], False)
                self.assertEqual({"spent": spent, "max": 2}, node["talent_points"])
                if "before" in decoded.data:
                    old = decoded.data["before"]["ui"]["nodes"][0]
                    self.assertIs(old["selected"], True)
                    self.assertEqual(2 - spent, old["talent_points"]["spent"])
                self.assertEqual(original, source)
                self.assertEqual(original, decoded.raw)

    def test_tab_selection_and_talent_points_reject_ambiguous_values(self):
        invalid = [{"selected": value} for value in (None, 0, 1, 0.0, "false")]
        invalid += [{"talent_points": value} for value in (None, {}, {"spent": True, "max": 2},
            {"spent": 1.0, "max": 2}, {"spent": 1, "max": True}, {"spent": -1, "max": 2},
            {"spent": 3, "max": 2}, {"spent": 0, "max": 0}, {"spent": 1, "max": 2.0})]
        for facts in invalid:
            node = {"id": "talent", "ops": [], **facts}
            for source in (frame({"ui": {"nodes": [node]}}),
                           frame({"before": {"ui": {"nodes": [node]}}}), actions_frame([node])):
                with self.subTest(facts=facts), self.assertRaises(DecodeError):
                    decode_wire_response(source)

    def test_custom_terrain_descriptors_survive_views_and_independent_frozen_maps(self):
        water = {"terrain": 29, "name": "Water", "desc": "Ordinary visible water."}
        basin = {"terrain": 39, "name": "Water basin", "desc": "Visible solid basin."}
        anvil = {"terrain": 23, "name": "Anvil", "desc": "Visible solid anvil."}
        for view in ("play", "full", "src"):
            descriptors = copy.deepcopy([water, basin, anvil])
            if view == "src":
                for descriptor in descriptors:
                    descriptor["text_sources"] = {key: {"kind": "literal", "origin": "external", "value": descriptor[key]}
                                                   for key in ("name", "desc")}
            current = {"w": 5, "h": 1, "types": descriptors,
                       "rows": [[0, 0, "012", "v"]]}
            # The exact same row indexes deliberately mean different terrain
            # in the before snapshot; neither map can inherit the other's types.
            before = {"s": "s0", "rev": "r0", "map": {"w": 5, "h": 1,
                      "types": list(reversed(descriptors)), "rows": [[0, 0, "012", "s"]]}}
            after = {"s": "s2", "rev": "r2", "map": {"w": 5, "h": 1,
                     "types": [descriptors[1]], "rows": [[0, 4, "0", "m"]]}}
            source = frame({"phase": "player_ready", "map": current,
                            "before": before, "after": after})
            original = copy.deepcopy(source)
            with self.subTest(view=view):
                decoded = decode_wire_response(json.loads(json.dumps(source)))
                for cell, descriptor in enumerate(descriptors):
                    self.assertEqual(descriptor, {key: map_cell(decoded, cell)[key] for key in descriptor})
                self.assertIsNone(map_cell(decoded, 3), "Unknown map gaps remain unknown")
                before_cells = decoded.data["before"]["map"]["cells"]
                after_cell, = decoded.data["after"]["map"]["cells"]
                self.assertEqual([23, 39, 29], [cell["terrain"] for cell in before_cells])
                self.assertTrue(all(cell["visibility"] == "visited" for cell in before_cells))
                self.assertEqual((4, "mapped"), (after_cell["cell"], after_cell["visibility"]))
                self.assertEqual(descriptors[1], {key: after_cell[key] for key in descriptors[1]})
                self.assertEqual(original, source)
                self.assertEqual(original, decoded.raw)

    def test_protocol8_and_noninteger_version_fail_without_rewriting_original(self):
        for version in (8, 7, 9.0, True, None, "9"):
            source = frame({"hero": {"cell": 1}})
            source["v"] = version
            original = copy.deepcopy(source)
            with self.subTest(version=version), self.assertRaises(DecodeError) as raised:
                decode_wire_response(source)
            self.assertEqual(source, original)
            self.assertEqual(raised.exception.raw, original)

    def test_dot_preserved_across_hero_entities_actions_and_independent_snapshots(self):
        bars = [sample(16, 16, 16, 0), sample(16, 16, 16, 16),
                sample(21, 4, 22, 20), sample(16, 0, 0, 0)]
        before = {"s": "s2", "rev": "r2", "hero": {"cell": 1, "health_estimate": {"samples": bars}}}
        after = {"s": "s3", "rev": "r3", "hero": {"cell": 1, "health_estimate": {"samples": [sample(incoming_dot=1)]}}}
        entity = {"kind": "character", "cell": 2, "health_estimate": {"samples": bars}}
        source = frame({"hero": before["hero"], "entities": [entity], "before": before, "after": after})
        original = copy.deepcopy(source)
        decoded = decode_wire_response(source).data
        for owner in (decoded["hero"], decoded["entities"][0], decoded["before"]["hero"]):
            self.assertEqual(bars, owner["health_estimate"]["samples"])
        self.assertEqual(1, decoded["after"]["hero"]["health_estimate"]["samples"][0]["incoming_dot"])
        action = actions_frame([{"id": "bar", "ops": [], "subject_data": entity}])
        self.assertEqual(bars, decode_wire_response(action).data["ui"]["nodes"][0]["subject_data"]["health_estimate"]["samples"])
        self.assertEqual(original, source)

    def test_hero_dot_is_required_and_strict_including_nested_actions(self):
        valid = sample()
        cases = [{key: value for key, value in valid.items() if key != "incoming_dot"}]
        cases += [{**valid, "incoming_dot": value} for value in (None, True, False, 1.0, -1, 23, "1")]
        for bar in cases:
            owner = {"cell": 1, "health_estimate": {"samples": [bar]}}
            sources = [frame({"hero": owner}), frame({"before": {"hero": owner}}),
                       actions_frame([{"id": "hero", "ops": [], "subject_data": owner}])]
            for source in sources:
                with self.subTest(bar=bar, source=source), self.assertRaisesRegex(DecodeError, "health_estimate"):
                    decode_wire_response(source)
        unobserved = decode_wire_response(frame({"hero": {"cell": 1}})).data["hero"]
        self.assertNotIn("health_estimate", unobserved)

    def test_object_collision_uses_same_frame_template_binding_and_no_inferred_health(self):
        descriptor = {"kind": "object", "name": "token door", "projectile_collision": "character"}
        source = frame({"entity_defs": [descriptor], "entities": [{"cell": 4, "def": 0}],
                        "ui": {"nodes": [{"id": "door", "subject": {"kind": "entity", "index": 0}, "ops": []}]}})
        decoded = decode_wire_response(source).data
        self.assertEqual({"cell": 4, **descriptor}, decoded["entities"][0])
        self.assertNotIn("health_estimate", decoded["entities"][0])
        self.assertNotIn("buffs", decoded["entities"][0])
        for value in (None, "character"):
            entity = {"kind": "object", "cell": 4, "projectile_collision": value}
            self.assertEqual(entity, decode_wire_response(frame({"entities": [entity]})).data["entities"][0])
        for entity in ({"kind": "object", "projectile_collision": False},
                       {"kind": "object", "projectile_collision": []},
                       {"kind": "object", "projectile_collision": "solid"},
                       {"kind": "container", "projectile_collision": "character"}):
            for source in (frame({"entities": [{"cell": 4, **entity}]}),
                           actions_frame([{"id": "object", "ops": [], "subject_data": entity}])):
                with self.subTest(entity=entity), self.assertRaisesRegex(DecodeError, "projectile_collision"):
                    decode_wire_response(source)

    def test_movement_shadow_does_not_reveal_unknown_terrain_or_add_entity(self):
        cue = {"kind": "movement_shadow", "cell": 5, "dir": "NE", "appearance": {"symbol": "rat_portrait"}}
        data = {"hero": {"cell": 4}, "map": {"w": 3, "h": 3, "types": [{"terrain": 1}],
                  "rows": [[1, 1, "0", "v"]]}, "entities": [],
                "cues": {"status": "last_observed", "cues": [cue]}}
        decoded = decode_wire_response(frame(data))
        self.assertEqual([cue], decoded.data["cues"]["cues"])
        self.assertIsNone(map_cell(decoded, 5))
        self.assertEqual([], decoded.data["entities"])
        for direction in (None, "E", "NW"):
            data["cues"]["cues"][0]["dir"] = direction
            self.assertIs(direction, decode_wire_response(frame(data)).data["cues"]["cues"][0]["dir"])
        del data["cues"]["cues"][0]["dir"]
        self.assertNotIn("dir", decode_wire_response(frame(data)).data["cues"]["cues"][0])
        for direction in (False, 0, [], "east", "up"):
            data["cues"]["cues"][0]["dir"] = direction
            with self.subTest(direction=direction), self.assertRaisesRegex(DecodeError, "dir"):
                decode_wire_response(frame(data))

    def test_new_facts_survive_events_without_becoming_current_observation(self):
        event = {"seq": 1, "kind": "visual.cues", "data": {"cues": [
            {"kind": "movement_shadow", "cell": 5, "dir": "SW", "appearance": {"symbol": "rat_portrait"}},
            {"kind": "vault_elemental_form", "cell": 6, "appearance": {"form": "frost"}}]}}
        response = decode_wire_response(frame({"items": [event], "next": None, "end": True, "until": 1}))
        self.assertEqual([event], response.data["items"])
        self.assertFalse(response.is_observation)


if __name__ == "__main__":
    unittest.main()
