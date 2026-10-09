"""Offline regressions using synthetic public responses only; never start a game."""

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import ray  # noqa: E402
from public_snapshot import decode_client_response  # noqa: E402


def observation():
    return {"v": 9, "id": "t7.3", "s": "s2", "rev": "r11", "st": "completed",
            "data": {"phase": "player_ready", "hero": {"cell": 15, "depth": 4},
                     "map": {"w": 7, "h": 5,
                             "types": [{"terrain": 1, "name": "Floor", "desc": "Known floor"},
                                       {"terrain": 4, "name": "Wall"},
                                       {"terrain": 5, "name": "Closed door"}],
                             "rows": [[y, 0, "0000000", "v"] for y in range(5)],
                             "env": {"17": [{"type": "fire", "desc": "Visible fire"}]}},
                     "entities": [{"kind": "container", "cell": 17, "name": "Tombstone", "desc": "Grave"},
                                  {"kind": "character", "cell": 19, "name": "Skeleton"},
                                  {"kind": "plant", "cell": 17, "name": "Firebloom"}],
                     "cues": {"status": "last_observed", "map_context": "m2", "cues": [{"kind": "red_target", "cell": 19}]},
                     "ui": {"feedback": [{"kind": "log", "text": "Public feedback"}]}}}


def receipt():
    return {"v": 9, "id": "t7.2", "s": "s2", "st": "completed",
            "data": {"id": "t7.1", "st": "COMPLETED", "save": [{"sid": "p4"}]}}


class PublicHelperTest(unittest.TestCase):
    def run_helper(self, name, record, *args, file_input=False):
        # Every CLI runs from a different cwd. No real gameplay record is read.
        with tempfile.TemporaryDirectory(prefix="spd-public-helper-test-") as directory:
            raw = json.dumps(record, ensure_ascii=False).encode()
            command = [sys.executable, "-B", str(SCRIPTS / name), "--response"]
            if file_input:
                source = Path(directory) / "synthetic-public-response.json"
                source.write_bytes(raw)
                command.append(str(source))
                incoming = None
            else:
                command.append("-")
                incoming = raw
            result = subprocess.run(command + list(args), input=incoming, cwd=directory,
                                    capture_output=True, timeout=10)
            if file_input:
                self.assertEqual(raw, source.read_bytes(), "The helper must not modify its input")
            self.assertEqual(b"", result.stderr)
            return result.returncode, result.stdout.decode(), raw

    def test_complete_grid_keeps_every_row_entity_cue_environment_and_description(self):
        source = observation()
        code, output, _ = self.run_helper("map.py", source)
        self.assertEqual(0, code, output)
        grid = [line for line in output.splitlines() if len(line) >= 4 and line[:2].isdigit() and line[2:4] == "  "]
        self.assertEqual(5, len(grid))
        self.assertTrue(all(len(line[4:]) == 7 for line in grid))
        for text in ("Tombstone", "Firebloom", "Skeleton", "red_target", "Known floor", "Visible fire", "Public feedback"):
            self.assertIn(text, output)

    def test_json_mode_preserves_the_complete_decoded_observation(self):
        source = observation()
        code, output, raw = self.run_helper("map.py", source, "--json", file_input=True)
        self.assertEqual(0, code, output)
        report = json.loads(output)
        self.assertEqual(decode_client_response(source).require_current_frame().frame, report["frame"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), report["source_sha256"])
        self.assertEqual("supplied_public_snapshot_not_live_binding", report["snapshot_mode"])

    def test_cells_keep_same_cell_tombstone_plant_and_all_public_cues(self):
        code, output, _ = self.run_helper("cells.py", observation(), "3,2", "19")
        self.assertEqual(0, code, output)
        report = json.loads(output)
        first, second = report["cells"]
        self.assertEqual(17, first["cell"])
        self.assertEqual(["Tombstone", "Firebloom"], [entity["name"] for entity in first["entities"]])
        self.assertEqual("Visible fire", first["environment"][0]["desc"])
        self.assertEqual("red_target", second["cues"][0]["kind"])

    def test_map_and_cells_preserve_water_and_custom_decoration_descriptors(self):
        source = observation()
        water = {"terrain": 29, "name": "Water", "desc": "Ordinary visible water."}
        decoration = {"terrain": 39, "name": "Water basin", "desc": "Visible solid basin."}
        source["data"]["map"]["types"].extend([water, decoration])
        source["data"]["map"]["rows"][2] = [2, 0, "0034000", "v"]
        code, output, _ = self.run_helper("map.py", source)
        self.assertEqual(0, code, output)
        legend = json.loads(next(line.removeprefix("Legend ") for line in output.splitlines()
                                 if line.startswith("Legend ")))
        water_entry = next(entry for entry in legend if entry["terrain"] == 29)
        deco_entry = next(entry for entry in legend if entry["terrain"] == 39)
        self.assertNotEqual(water_entry["symbol"], deco_entry["symbol"])
        self.assertEqual(water, {key: value for key, value in water_entry.items() if key != "symbol"})
        self.assertEqual(decoration, {key: value for key, value in deco_entry.items() if key != "symbol"})
        code, output, _ = self.run_helper("cells.py", source, "16", "17")
        self.assertEqual(0, code, output)
        for cell, descriptor in zip(json.loads(output)["cells"], (water, decoration)):
            self.assertEqual(descriptor, {key: cell["public_cell"][key] for key in descriptor})

    def test_cells_reject_outside_coordinates_without_wrapping_to_another_row(self):
        code, output, _ = self.run_helper("cells.py", observation(), "7,1", "35", "north")
        self.assertEqual(2, code)
        self.assertTrue(all("error" in cell for cell in json.loads(output)["cells"]))

    def test_unknown_cell_stays_unknown(self):
        source = observation()
        source["data"]["map"]["rows"] = [[2, 1, "0", "v"]]
        source["data"]["map"]["env"] = {}
        code, output, _ = self.run_helper("cells.py", source, "16")
        self.assertEqual(0, code, output)
        cell, = json.loads(output)["cells"]
        self.assertFalse(cell["known"])
        self.assertIsNone(cell["public_cell"])

    def test_sequenced_record_does_not_confuse_controller_response_with_wire(self):
        late = observation()
        late.update(id="t7.0", rev="r2")
        wrapper = {"controller": "response", "response": observation(),
                   "late_responses": [{"request": {"id": "t7.0"}, "response": late}]}
        code, output, _ = self.run_helper("map.py", {"seq": 12, "response": wrapper}, "--json")
        self.assertEqual(0, code, output)
        report = json.loads(output)
        self.assertEqual((12, "response", "r11"), (report["record_seq"], report["controller"], report["rev"]))
        self.assertEqual("r2", report["late_responses"][0]["response"]["rev"])

    def test_no_frame_errors_preserve_original_outcome_and_raw_wrapper(self):
        wrapper = {"controller": "settle", "st": "completed", "rid": "t7.1", "outcome": receipt()}
        for helper, args in (("map.py", []), ("cells.py", ["15"]),
                             ("ray.py", ["--target", "19", "--op", "projectile"])):
            with self.subTest(helper=helper):
                code, output, _ = self.run_helper(helper, wrapper, *args)
                self.assertEqual(2, code)
                report = json.loads(output)
                self.assertEqual("NO_CURRENT_FRAME", report["error"]["code"])
                self.assertEqual(wrapper, report["raw_response"])
                self.assertEqual("p4", report["outcome"]["data"]["save"][0]["sid"])

    def test_original_transport_error_wins_over_missing_frame(self):
        wrapper = {"controller": "settle", "st": "completed", "rid": "t7.1",
                   "outcome": receipt(), "observation": observation(),
                   "transport_error": {"controller": "error", "err": "RESPONSE_TIMEOUT"}}
        code, output, _ = self.run_helper("map.py", wrapper, "--json")
        self.assertEqual(2, code)
        report = json.loads(output)
        self.assertEqual("RESPONSE_TIMEOUT", report["error"]["code"])
        self.assertEqual(wrapper, report["raw_response"])
        self.assertNotIn("frame", report)

    def test_failed_decode_preserves_failed_wire_identity_and_action_wrapper(self):
        broken = observation()
        broken["data"]["map"]["rows"] = [[2, 1, "Z", "v"]]
        wrapper = {"controller": "settle", "st": "completed", "rid": "t7.1",
                   "outcome": receipt(), "observation": broken}
        code, output, _ = self.run_helper("map.py", wrapper, "--json")
        self.assertEqual(2, code)
        report = json.loads(output)
        self.assertEqual("DECODE_ERROR", report["error"]["code"])
        self.assertEqual("t7.3", report["error"]["identity"]["id"])
        self.assertEqual(wrapper, report["raw_response"])

    def test_ray_cli_runs_from_other_cwd_with_stdin_without_mutating_frame(self):
        source = observation()
        before = copy.deepcopy(source)
        code, output, _ = self.run_helper("ray.py", source, "--target", "19", "--op", "projectile")
        self.assertEqual(0, code, output)
        self.assertEqual(before, source)
        report = json.loads(output)
        self.assertEqual("read_only_geometry_prediction_not_game_reply", report["kind"])
        self.assertEqual([15, 16, 17, 18, 19], report["ray"]["conditional_travel_path_to_collision"])
        self.assertTrue(report["ray"]["conditional_collision_prediction"]["conditional"])

    def test_null_hero_preserves_unknown_position_and_ray_reports_missing_hero(self):
        source = observation()
        source["data"]["hero"] = None
        code, output, _ = self.run_helper("map.py", source)
        self.assertEqual(0, code, output)
        self.assertIn("Hero null", output)
        self.assertIn("Hero underlying cell null", output)
        grid = [line[4:] for line in output.splitlines() if len(line) >= 4 and line[:2].isdigit() and line[2:4] == "  "]
        self.assertFalse(any("@" in line for line in grid))
        code, output, _ = self.run_helper("cells.py", source, "17")
        self.assertEqual(0, code, output)
        self.assertIsNone(json.loads(output)["hero_cell"])
        code, output, _ = self.run_helper("ray.py", source, "--target", "19", "--op", "projectile")
        self.assertEqual(2, code)
        report = json.loads(output)
        self.assertEqual("NO_MAP_OR_HERO", report["error"]["code"])
        self.assertEqual(("t7.3", "r11", source), (report["id"], report["rev"], report["raw_response"]))

    def test_null_cues_remain_unknown_instead_of_an_empty_danger_list(self):
        source = observation()
        source["data"]["cues"] = None
        code, output, _ = self.run_helper("map.py", source)
        self.assertEqual(0, code, output)
        self.assertIn("All public cues null", output)
        code, output, _ = self.run_helper("cells.py", source, "17")
        self.assertEqual(0, code, output)
        report = json.loads(output)
        for key in ("map_context", "cue_context", "unlocated_cues"):
            self.assertIsNone(report[key])
        self.assertIsNone(report["cells"][0]["cues"])
        code, output, _ = self.run_helper("ray.py", source, "--target", "19", "--op", "projectile")
        self.assertEqual(0, code, output)

    def test_scene_without_hero_or_cues_has_controlled_no_map_diagnostics(self):
        source = observation()
        source["data"] = {"phase": "awaiting_input", "scene": "TitleScene", "ui": {"nodes": []}}
        for helper, args, expected in (("map.py", [], None), ("cells.py", ["17"], "NO_MAP"),
                                       ("ray.py", ["--target", "19", "--op", "projectile"], "NO_MAP_OR_HERO")):
            with self.subTest(helper=helper):
                code, output, _ = self.run_helper(helper, source, *args)
                if expected is None:
                    self.assertEqual(0, code, output)
                    self.assertIn("No map in this observation", output)
                else:
                    self.assertEqual(2, code)
                    report = json.loads(output)
                    self.assertEqual(expected, report["error"]["code"])
                    self.assertEqual(source, report["raw_response"])


class BallisticaPortTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = ray.source_model()

    def predict(self, source, target=19, mode="projectile"):
        before = copy.deepcopy(source)
        frame = decode_client_response(source).require_current_frame()
        result = ray.predict(frame.frame, target, mode, self.model)
        self.assertEqual(before, source)
        return result

    def test_unknown_terrain_stops_instead_of_becoming_clear_floor(self):
        source = observation()
        source["data"]["map"]["rows"] = [[2, 1, "0", "v"], [2, 4, "00", "v"]]
        source["data"]["map"]["env"] = {}
        result = self.predict(source)
        self.assertEqual(16, result["first_unknown_stops_prediction"]["cell"])
        self.assertIsNone(result["conditional_collision_prediction"])
        self.assertIsNone(result["predicted_reaches_target_under_assumptions"])

    def test_hard_wall_collides_before_wall_and_closed_door_on_itself(self):
        for symbol, expected, reason in (("1", 16, "hard_terrain_without_observed_collision_occupant_stops_at_previous"),
                                         ("2", 17, "solid_cell")):
            with self.subTest(symbol=symbol):
                source = observation()
                source["data"]["map"]["rows"][2] = [2, 0, "000" + symbol + "000", "v"]
                result = self.predict(source)
                collision = result["conditional_collision_prediction"]
                self.assertEqual((expected, 17, reason), (collision["cell"], collision["trigger_cell"], collision["reason"]))
                self.assertEqual([15, 16, 17, 18, 19], result["geometry_line_including_after_collision"])

    def test_public_web_sets_dynamic_solid_without_inventing_other_effect_flags(self):
        source = observation()
        source["data"]["map"]["env"]["17"] = [{"type": "web", "desc": "Public web", "collision": {"solid": True}}]
        self.assertEqual(17, self.predict(source)["conditional_collision_prediction"]["cell"])

    def test_public_walls_and_eternal_fire_stop_before_target_for_all_profiles(self):
        for kind in ("light_wall", "key_wall", "eternal_fire"):
            override = {"passable": False, "avoid": False}
            if kind != "eternal_fire":
                override["solid"] = True
            source = observation()
            source["data"]["map"]["env"]["17"] = [{"type": kind, "desc": "Exact public description",
                                                       "collision": override}]
            for target in (17, 19):
                for mode in ("projectile", "magicbolt", "solidbeam"):
                    with self.subTest(kind=kind, target=target, mode=mode):
                        result = self.predict(source, target, mode)
                        self.assertEqual(16, result["conditional_collision_prediction"]["cell"])
                        self.assertFalse(result["predicted_reaches_target_under_assumptions"])
                        row = result["evaluated_path_including_blocking_probe"][-1]
                        self.assertEqual(override, row["public_environment_collision_overrides"])
                        self.assertEqual(kind != "eternal_fire", row["flags"]["solid"])

    def test_partial_collision_overrides_preserve_base_flags_and_compose_without_order(self):
        source = observation()
        effects = [{"type": "eternal_fire", "collision": {"passable": False, "avoid": False}},
                   {"type": "web", "collision": {"solid": True}}]
        for env in (effects, list(reversed(effects))):
            source["data"]["map"]["env"]["17"] = env
            row = self.predict(source)["evaluated_path_including_blocking_probe"][-1]
            self.assertEqual({"passable": False, "avoid": False, "solid": True}, row["flags"])
        source["data"]["map"]["rows"][2] = [2, 0, "0002000", "v"]
        source["data"]["map"]["env"]["17"] = [effects[0]]
        row = self.predict(source)["evaluated_path_including_blocking_probe"][-1]
        self.assertTrue(row["flags"]["solid"], "Eternal fire cannot clear a solid door flag")

    def test_conflicting_and_missing_collision_facts_remain_unknown_even_on_target_or_occupant(self):
        cases = ([{"type": "web", "desc": "Web without a collision observation"}],
                 [{"type": "web", "collision": {"solid": True}},
                  {"type": "future_effect", "collision": {"solid": False}}])
        for effects in cases:
            for occupied in (False, True):
                source = observation()
                source["data"]["map"]["env"]["17"] = effects
                if occupied:
                    source["data"]["entities"].append({"kind": "character", "cell": 17, "name": "Rat"})
                with self.subTest(effects=effects, occupied=occupied):
                    result = self.predict(source, 17)
                    self.assertEqual(17, result["first_unknown_stops_prediction"]["cell"])
                    self.assertIsNone(result["conditional_collision_prediction"])
                    self.assertIsNone(result["predicted_reaches_target_under_assumptions"])

    def test_ordinary_nonblocking_effects_and_prose_do_not_invent_collision(self):
        source = observation()
        source["data"]["map"]["env"]["17"] = [
            {"type": "fire", "desc": "A wall of text does not make a solid wall."},
            {"type": "visible_effect", "desc": "Web LightWall KeyWall EternalFire"}]
        result = self.predict(source)
        self.assertTrue(result["predicted_reaches_target_under_assumptions"])
        self.assertIsNone(result["first_unknown_stops_prediction"])

    def test_equal_endpoint_preserves_native_northwest_geometry_and_stop_target(self):
        source = observation()
        source["data"]["hero"]["cell"] = 17
        for mode, expected in (("projectile", 17), ("magicbolt", 9), ("solidbeam", 9)):
            with self.subTest(mode=mode):
                result = self.predict(source, 17, mode)
                self.assertEqual([17, 9], result["geometry_line_including_after_collision"])
                self.assertEqual(expected, result["conditional_collision_prediction"]["cell"])

    def test_boundary_source_uses_native_constructor_fallback(self):
        source = observation()
        source["data"]["hero"]["cell"] = 0
        result = self.predict(source, 0)
        self.assertEqual([0], result["geometry_line_including_after_collision"])
        self.assertEqual([0], result["conditional_travel_path_to_collision"])
        self.assertEqual(0, result["conditional_collision_prediction"]["dist"])
        self.assertTrue(result["predicted_reaches_target_under_assumptions"])

    def test_native_half_error_tie_and_outer_boundary_are_preserved(self):
        line, _ = ray.create_line(15, 24, 7, 5)
        self.assertEqual([15, 23, 24], [step["cell"] for step in line])

    def test_public_native_object_collides_but_cannot_supply_an_attack_source(self):
        source = observation()
        source["data"]["entities"].append({"kind": "object", "cell": 17,
            "name": "token door", "projectile_collision": "character"})
        result = self.predict(source)
        self.assertEqual(17, result["conditional_collision_prediction"]["cell"])
        row = result["evaluated_path_including_blocking_probe"][-1]
        self.assertTrue(row["public_collision_occupant_present"])
        self.assertFalse(row["public_character_present"])
        frame = decode_client_response(source).require_current_frame()
        with self.assertRaisesRegex(ValueError, "explicitly observed character"):
            ray.predict(frame.frame, 19, "projectile", self.model, requested_source=17)

    def test_unclassified_objects_and_movement_shadows_do_not_invent_collision(self):
        for flag in (None, "absent"):
            source = observation()
            object_ = {"kind": "object", "cell": 17, "name": "visible object"}
            if flag is None:
                object_["projectile_collision"] = None
            source["data"]["entities"].append(object_)
            source["data"]["cues"]["cues"].append({"kind": "movement_shadow", "cell": 18,
                "dir": "E", "appearance": {"symbol": "rat_portrait"}})
            with self.subTest(flag=flag):
                result = self.predict(source)
                self.assertEqual(19, result["conditional_collision_prediction"]["cell"])
                rows = result["evaluated_path_including_blocking_probe"]
                self.assertFalse(next(r for r in rows if r["cell"] == 17)["public_collision_occupant_present"])
                self.assertFalse(next(r for r in rows if r["cell"] == 18)["public_collision_occupant_present"])

    def test_unknown_shadow_cell_keeps_map_unknown_and_stops_ray(self):
        source = observation()
        source["data"]["map"]["rows"] = [[2, 1, "0", "v"], [2, 3, "000", "v"]]
        source["data"]["cues"]["cues"] = [{"kind": "movement_shadow", "cell": 16,
            "appearance": {"symbol": "rat_portrait"}}]
        result = self.predict(source)
        self.assertEqual(16, result["first_unknown_stops_prediction"]["cell"])
        self.assertIsNone(result["conditional_collision_prediction"])
        code, output, _ = PublicHelperTest().run_helper("cells.py", source, "16")
        self.assertEqual(0, code, output)
        cell = json.loads(output)["cells"][0]
        self.assertFalse(cell["known"])
        self.assertIsNone(cell["terrain"])
        self.assertEqual([], cell["entities"])
        self.assertEqual("movement_shadow", cell["cues"][0]["kind"])

    def test_ordinary_public_water_allows_all_profiles_without_private_lookup(self):
        source = observation()
        source["data"]["map"]["types"].append({"terrain": 29, "name": "Water"})
        source["data"]["map"]["rows"][2] = [2, 0, "0003000", "v"]
        source["data"]["map"]["env"] = {}
        for mode in ("projectile", "magicbolt", "solidbeam"):
            with self.subTest(mode=mode), \
                    patch.object(Path, "read_bytes", side_effect=AssertionError("No private file lookup")), \
                    patch.object(Path, "read_text", side_effect=AssertionError("No private file lookup")):
                result = self.predict(source, mode=mode)
                self.assertIsNone(result["first_unknown_stops_prediction"])
                self.assertTrue(result["predicted_reaches_target_under_assumptions"])
                self.assertEqual([15, 16, 17, 18, 19], result["conditional_travel_path_to_collision"])
                self.assertTrue(result["conditional_collision_prediction"]["conditional"])
                self.assertIn(17, result["cells_assuming_no_unreported_collision_occupant"])
                row = next(row for row in result["evaluated_path_including_blocking_probe"] if row["cell"] == 17)
                self.assertEqual({"passable": True, "avoid": False, "solid": False}, row["flags"])
                self.assertNotIn("native_terrain", row)

    def test_public_solid_custom_decorations_stop_before_cell_without_occupant(self):
        for terrain in (23, 39):
            source = observation()
            source["data"]["map"]["types"].append({"terrain": terrain,
                "name": "Public decoration", "desc": "The visibly presented decoration."})
            source["data"]["map"]["rows"][2] = [2, 0, "0003000", "v"]
            for mode in ("projectile", "magicbolt", "solidbeam"):
                with self.subTest(terrain=terrain, mode=mode), \
                        patch.object(Path, "read_bytes", side_effect=AssertionError("No private file lookup")), \
                        patch.object(Path, "read_text", side_effect=AssertionError("No private file lookup")):
                    result = self.predict(source, mode=mode)
                    self.assertIsNone(result["first_unknown_stops_prediction"])
                    self.assertEqual([15, 16], result["conditional_travel_path_to_collision"])
                    collision = result["conditional_collision_prediction"]
                    self.assertEqual((16, 17, "hard_terrain_without_observed_collision_occupant_stops_at_previous"),
                                     (collision["cell"], collision["trigger_cell"], collision["reason"]))
                    self.assertFalse(result["predicted_reaches_target_under_assumptions"])
                    row = result["evaluated_path_including_blocking_probe"][-1]
                    self.assertEqual({"passable": False, "avoid": False, "solid": True}, row["flags"])

    def test_public_water_actor_or_object_stops_only_stop_chars_profiles(self):
        for occupant in ({"kind": "character", "cell": 17, "name": "rat"},
                         {"kind": "object", "cell": 17, "name": "token door",
                          "projectile_collision": "character"}):
            source = observation()
            source["data"]["map"]["types"].append({"terrain": 29, "name": "Water"})
            source["data"]["map"]["rows"][2] = [2, 0, "0003000", "v"]
            source["data"]["entities"].append(occupant)
            for mode in ("projectile", "magicbolt"):
                with self.subTest(occupant=occupant["kind"], mode=mode):
                    result = self.predict(source, mode=mode)
                    self.assertIsNone(result["first_unknown_stops_prediction"])
                    self.assertEqual(17, result["conditional_collision_prediction"]["cell"])
                    self.assertEqual("known_collision_occupant", result["conditional_collision_prediction"]["reason"])
                    self.assertFalse(result["predicted_reaches_target_under_assumptions"])
            result = self.predict(source, mode="solidbeam")
            self.assertIsNone(result["first_unknown_stops_prediction"])
            self.assertTrue(result["predicted_reaches_target_under_assumptions"])
            if occupant["kind"] == "object":
                decoded = decode_client_response(source).require_current_frame()
                with self.assertRaisesRegex(ValueError, "explicitly observed character"):
                    ray.predict(decoded.frame, 19, "projectile", self.model, requested_source=17)

    def test_public_solid_custom_decorations_with_object_stop_on_cell(self):
        source = observation()
        source["data"]["map"]["types"].append({"terrain": 39, "name": "Public decoration"})
        source["data"]["map"]["rows"][2] = [2, 0, "0003000", "v"]
        source["data"]["entities"].append({"kind": "object", "cell": 17,
            "name": "token door", "projectile_collision": "character"})
        for mode in ("projectile", "magicbolt", "solidbeam"):
            with self.subTest(mode=mode):
                result = self.predict(source, mode=mode)
                self.assertIsNone(result["first_unknown_stops_prediction"])
                self.assertEqual(17, result["conditional_collision_prediction"]["cell"])
                self.assertEqual("solid_cell", result["conditional_collision_prediction"]["reason"])

    def test_unknown_terrain_occupant_only_establishes_stop_chars_collision(self):
        source = observation()
        source["data"]["map"]["rows"] = [[2, 1, "00", "v"], [2, 4, "00", "v"]]
        source["data"]["map"]["env"] = {}
        source["data"]["entities"].append({"kind": "object", "cell": 17,
            "name": "token door", "projectile_collision": "character"})
        for mode in ("projectile", "magicbolt"):
            with self.subTest(mode=mode):
                result = self.predict(source, mode=mode)
                self.assertEqual(17, result["conditional_collision_prediction"]["cell"])
                self.assertEqual("known_collision_occupant_independent_of_unknown_flags",
                                 result["conditional_collision_prediction"]["reason"])
        result = self.predict(source, mode="solidbeam")
        self.assertEqual("unknown_or_unclassified_terrain", result["first_unknown_stops_prediction"]["reason"])
        self.assertIsNone(result["conditional_collision_prediction"])

    def test_water_does_not_promote_disguises_or_shadows_to_occupants(self):
        source = observation()
        source["data"]["map"]["types"].append({"terrain": 29, "name": "Water"})
        source["data"]["map"]["rows"][2] = [2, 0, "0003000", "v"]
        source["data"]["cues"]["cues"].append({"kind": "movement_shadow", "cell": 17,
            "appearance": {"symbol": "rat_portrait"}})
        source["data"]["entities"].append({"kind": "object", "cell": 17,
            "name": "Ebony chest", "projectile_collision": None})
        result = self.predict(source)
        self.assertIsNone(result["first_unknown_stops_prediction"])
        self.assertTrue(result["predicted_reaches_target_under_assumptions"])
        row = next(row for row in result["evaluated_path_including_blocking_probe"] if row["cell"] == 17)
        self.assertFalse(row["public_collision_occupant_present"])
        self.assertIn(17, result["cells_assuming_no_unreported_collision_occupant"])

    def test_sha_mismatch_requires_source_review(self):
        with patch.object(ray, "REVIEWED_BALLISTICA", "0" * 64):
            with self.assertRaisesRegex(ValueError, "changed; review this port"):
                ray.source_model()

    def test_hypothetical_source_must_be_an_observed_character(self):
        frame = decode_client_response(observation()).require_current_frame()
        with self.assertRaisesRegex(ValueError, "explicitly observed character"):
            ray.predict(frame.frame, 15, "projectile", self.model, requested_source=17)


if __name__ == "__main__":
    unittest.main()
