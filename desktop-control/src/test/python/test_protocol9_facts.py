"""Offline CLI 9 knowledge and observation contracts; synthetic public frames only."""

import copy
import json
import unittest

import protocol9
from autoplay import Checkpoint, require_autonomous_policy
from spdctl_client import DecodeError, decode_wire_response, map_cell
from test_actions_subject_data import actions_frame
from test_combat_visual_wire import frame
from test_health_estimate_rounding import sample


class Protocol9FactsTest(unittest.TestCase):
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
                adapter_cells = protocol9.response(source)["result"]["observation"]["map"]["cells"]
                self.assertEqual([29, 39, 23], [cell["terrain"] for cell in adapter_cells])
                self.assertEqual(basin["desc"], adapter_cells[1]["description"])
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
            with self.assertRaises(AssertionError):
                protocol9.response(source)

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
        self.assertEqual("object", protocol9.response(source)["result"]["observation"]["visible_entities"][0]["kind"])
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

    def test_autoplay_does_not_authorize_unreviewed_game4_policy(self):
        with self.assertRaisesRegex(Checkpoint, "policy_requires_review_for_game_4_0_2"):
            require_autonomous_policy()


if __name__ == "__main__":
    unittest.main()
