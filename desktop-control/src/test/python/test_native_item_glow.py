"""Native item glow -> Java compact protocol -> production Python decoder.

These fixtures use synthetic textures and memory-only preferences, never a game
backend or profile. Build game-control test classes and the desktop-control test
runtime classpath before running this test; the test never invokes Gradle.
"""

import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import unquote, urlparse


ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "desktop-control/client"))
from spdctl_client import decode_wire_response  # noqa: E402


class NativeItemGlowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java, javac = shutil.which("java"), shutil.which("javac")
        if not java or not javac:
            raise unittest.SkipTest("A JDK is required for native item glow fixtures")
        classpath_file = ROOT / "desktop-control/build/test-runtime-classpath.txt"
        test_classes = ROOT / "game-control/build/classes/java/test"
        helper = test_classes / "com/shatteredpixel/shatteredpixeldungeon/control/game/GameSnapshotterTest.class"
        if not classpath_file.is_file() or not helper.is_file():
            raise AssertionError("Build :game-control:testClasses and :desktop-control:writeTestRuntimeClasspath first")

        cls.compiled = tempfile.TemporaryDirectory(prefix="spd-native-item-glow-")
        cls.addClassCleanup(cls.compiled.cleanup)
        prefixes = [ROOT / path for path in (
            "game-control/build/classes/java/test", "game-control/build/resources/test",
            "game-control/build/classes/java/main", "core/build/classes/java/main",
            "SPD-classes/build/classes/java/main", "control-protocol/build/classes/java/main")]
        classpath = os.pathsep.join([str(path) for path in prefixes] +
                                   classpath_file.read_text().strip().split(os.pathsep))
        source = ROOT / ("game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/"
                         "control/game/NativeItemGlowFixture.java")
        # Compile this small entrypoint against the current prebuilt test helpers.
        compilation = subprocess.run([javac, "-encoding", "UTF-8", "-cp", classpath,
                                      "-d", cls.compiled.name, str(source)],
                                     cwd=ROOT / "game-control", capture_output=True, text=True, timeout=30)
        if compilation.returncode:
            raise AssertionError("Native item fixture compilation failed:\n" + compilation.stderr)
        execution = subprocess.run([java, "-cp", cls.compiled.name + os.pathsep + classpath,
                                    "com.shatteredpixel.shatteredpixeldungeon.control.game.NativeItemGlowFixture"],
                                   cwd=ROOT / "game-control", capture_output=True, text=True, timeout=30)
        if execution.returncode:
            raise AssertionError("Native item fixture execution failed:\n" + execution.stderr)
        cls.fixture = json.loads(execution.stdout)

    @staticmethod
    def glow(stage, *, native=False):
        if stage == "unmapped":
            diagnostics = [{"field": "symbol", "code": "unmapped_indicator"}]
            if native:
                return {"symbol": "unmapped_indicator", "presentation": {
                    "status": "partial", "diagnostics": diagnostics}}
            return {"symbol": "unmapped_indicator", "pres": {
                "st": "partial", "diag": diagnostics}}
        return {"variant": "crystal_blue", "kind": "crystal_condition", "stage": stage}

    def decode_preserving_raw(self, wire):
        original = copy.deepcopy(wire)
        decoded = decode_wire_response(wire)
        self.assertEqual(original, wire, "Decoding must not mutate native fixture output")
        self.assertEqual(original, decoded.raw, "The native compact wire response must remain intact")
        if "pres" in original:
            self.assertEqual(original["pres"], decoded.frame["pres"], "Envelope diagnostics must survive decoding")
        return decoded.data

    def test_fixture_loads_current_production_classes_and_native_stages(self):
        self.assertEqual("native_item_glow", self.fixture["fixture"])
        for name, module in {"ItemSprite": "core", "ItemSlot": "core",
                             "PlayerObservation": "game-control", "GameplayObservation": "game-control",
                             "CompactProtocol": "game-control"}.items():
            with self.subTest(native_class=name):
                location = Path(unquote(urlparse(self.fixture["class_locations"][name]).path)).resolve()
                self.assertEqual((ROOT / module / "build/classes/java/main").resolve(), location)
        self.assertEqual(["intact", "flawed", "cracked", "unmapped"], [case["stage"] for case in self.fixture["cases"]])
        for case in self.fixture["cases"]:
            with self.subTest(stage=case["stage"]):
                expected = self.glow(case["stage"], native=True)
                self.assertEqual(expected, case["native_glow"])
                self.assertEqual({"shown": {"glow": expected}}, case["native_slot_status"])
                self.assertEqual({"kind": "item_glow", "cell": 22, "appearance": expected}, case["native_world_cue"])

    def test_native_inventory_and_world_glow_survive_all_three_views(self):
        for case in self.fixture["cases"]:
            self.assertEqual(["play", "full", "src"], [response["view"] for response in case["responses"]])
            for response in case["responses"]:
                with self.subTest(stage=case["stage"], view=response["view"]):
                    data = self.decode_preserving_raw(response["state"])
                    expected = self.glow(case["stage"])
                    self.assertEqual(expected, data["inv"][0]["shown"]["glow"])
                    self.assertEqual(expected, data["cues"]["cues"][0]["appearance"])
                    self.assertEqual({"kind": "item", "loc": "backpack.0"}, data["ui"]["nodes"][0]["subject"])

    def test_native_actions_subject_data_keeps_glow_without_inventory_table(self):
        for case in self.fixture["cases"]:
            for response in case["responses"]:
                with self.subTest(stage=case["stage"], view=response["view"]):
                    data = self.decode_preserving_raw(response["actions"])
                    self.assertNotIn("inv", data)
                    node = data["ui"]["nodes"][0]
                    self.assertNotIn("subject", node)
                    self.assertEqual(self.glow(case["stage"]), node["subject_data"]["shown"]["glow"])
                    self.assertEqual("backpack.0", node["subject_data"]["loc"])

    def test_frozen_snapshots_keep_their_independent_native_glow_stages(self):
        self.assertEqual(["play", "full", "src"] * 2, [case["view"] for case in self.fixture["frozen"]])
        for case in self.fixture["frozen"]:
            with self.subTest(view=case["view"], before=case["before_stage"], after=case["after_stage"]):
                data = self.decode_preserving_raw(case["wire"])
                for key, stage in (("before", case["before_stage"]), ("after", case["after_stage"])):
                    self.assertEqual(self.glow(stage), data[key]["inv"][0]["shown"]["glow"])
                    self.assertEqual(self.glow(stage), data[key]["cues"]["cues"][0]["appearance"])
                    self.assertEqual("backpack.0", data[key]["ui"]["nodes"][0]["subject"]["loc"])


if __name__ == "__main__":
    unittest.main()
