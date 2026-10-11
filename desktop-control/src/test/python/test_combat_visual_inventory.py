import importlib.util
import copy
import re
from pathlib import Path
import unittest
import tempfile

SOURCE = Path(__file__).resolve().parents[4] / "game-control/src/test/python/combat_visual_inventory.py"
spec = importlib.util.spec_from_file_location("combat_visual_inventory", SOURCE)
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class CombatVisualReviewTest(unittest.TestCase):
    def setUp(self):
        self.current = {"source_files": 2, "entries": [{"id": "exact:1", "body_digest": "body1"}]}
        self.group = {key: "reviewed evidence" for key in
                      ("id", "reason", "trigger", "visible_evidence", "visibility_gate", "lifecycle", "route")}
        self.group.update(classification="covered_current",
                          sites=[{"id": "exact:1", "body_digest": "body1"}])
        self.group.update(route={"semantic_contract": "current-cell"},
                          visibility_gate="Current FOV only.",
                          tests=[{"kind": "source_review", "checks": inventory.SOURCE_CHECKS.copy()},
                                 {"kind": "semantic_contract", "id": "current-cell"}])
        self.reviews = {
            "format": "combat_visual_reviews_v3",
            "runtime_verified_by_inventory": False,
            "groups": [self.group],
            "semantic_contracts": [{
                "id": "current-cell",
                "classification": "semantic_required",
                "source_producer": "PlayerObservation.terrain",
                "reason": "Current visible cell meaning.",
                "outputs": [{"owner": "PlayerObservation / CompactProtocol", "key": "data.map",
                             "allowed_fields": ["rows", "types"]}],
                "visibility_gate": "Current FOV only.",
                "tests": ["GameSnapshotterTest#hiddenWorldChangesDoNotChangePublicObservation"],
            }],
            "semantic_reviews": [{"classification": "semantic_required",
                                  "contract": "current-cell", "groups": ["reviewed evidence"]}],
        }

    def test_exact_review_is_static_evidence_only(self):
        report = inventory.check(self.current, self.reviews, Path(__file__).resolve().parents[4])
        self.assertTrue(report["checked"])
        self.assertFalse(report["runtime_verified_by_inventory"])
        self.assertEqual(report["semantic_classifications"], {"semantic_required": 1})

    def test_new_changed_removed_or_unresolved_sources_fail(self):
        for mutation in ("added", "changed", "removed", "unresolved"):
            self.setUp()
            if mutation == "added": self.current["entries"].append({"id": "new:1", "body_digest": "new"})
            if mutation == "changed": self.current["entries"][0]["body_digest"] = "changed"
            if mutation == "removed": self.current["entries"].clear()
            if mutation == "unresolved":
                self.reviews["semantic_reviews"][0].update(classification="unresolved", contract=None)
                self.reviews["semantic_contracts"] = []
                self.group["route"] = {"semantic_contract": None}
                self.group["tests"] = [{"kind": "source_review", "checks": inventory.SOURCE_CHECKS.copy()}]
            if mutation == "unresolved":
                report = inventory.check(self.current, self.reviews)
                self.assertFalse(report["checked"])
                self.assertEqual(report["outstanding"], ["exact:1"])
            else:
                self.assertFalse(inventory.check(self.current, self.reviews)["checked"], mutation)

    def test_duplicate_or_unjustified_classification_fails(self):
        self.reviews["groups"] = [self.group, self.group]
        with self.assertRaises(ValueError): inventory.check(self.current, self.reviews)
        self.reviews["groups"] = [self.group]
        self.group["reason"] = ""
        with self.assertRaises(ValueError): inventory.check(self.current, self.reviews)

    def test_semantic_contract_and_test_reference_must_resolve(self):
        reviews = copy.deepcopy(self.reviews)
        reviews["semantic_contracts"][0]["outputs"][0]["allowed_fields"] = []
        with self.assertRaises(ValueError): inventory.check(self.current, reviews)
        reviews = copy.deepcopy(self.reviews)
        reviews["semantic_contracts"][0]["tests"] = ["GameSnapshotterTest#methodThatDoesNotExist"]
        with self.assertRaises(ValueError):
            inventory.check(self.current, reviews, Path(__file__).resolve().parents[4])

    def test_current_group_method_must_exist_even_when_the_shared_contract_resolves(self):
        self.group["tests"].append("GameSnapshotterTest#removedLegacyPixelMethod")
        with self.assertRaisesRegex(ValueError, "unresolved test reference"):
            inventory.check(self.current, self.reviews, Path(__file__).resolve().parents[4])

    def test_group_cannot_borrow_a_different_contract_or_a_legacy_route(self):
        for field, value in (
                ("route", "Current cues.metrics and game.visual_metrics"),
                ("route", {"semantic_contract": "other-cell"}),
                ("visibility_gate", "Only pixels inside the old viewport"),
                ("tests", [{"kind": "semantic_contract", "id": "other-cell"}])):
            reviews = copy.deepcopy(self.reviews)
            reviews["groups"][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                inventory.check(self.current, reviews)

    def test_source_review_is_exact_and_does_not_hide_source_drift(self):
        self.group["tests"] = [{"kind": "source_review", "checks": inventory.SOURCE_CHECKS.copy()}]
        changed = copy.deepcopy(self.current)
        changed["entries"][0]["body_digest"] = "changed"
        report = inventory.check(changed, self.reviews)
        self.assertEqual(report["changed"], ["exact:1"])
        self.assertFalse(report["checked"])
        for checks in ([], ["site_identity"], ["runtime_verified"], inventory.SOURCE_CHECKS + ["skip"]):
            self.group["tests"][0]["checks"] = checks
            with self.subTest(checks=checks), self.assertRaises(ValueError):
                inventory.check(self.current, self.reviews)

    def test_untyped_or_historical_evidence_is_not_a_current_test(self):
        for reference in (
                "Source review only; old tests used to pass",
                "GameSnapshotterTest",
                "GameSnapshotterTest.hiddenWorldChangesDoNotChangePublicObservation",
                {"kind": "history", "version": "CLI.7.0.1", "test": "RemovedTest#oldMethod"},
                {"kind": "source_review", "checks": inventory.SOURCE_CHECKS.copy(), "waive": True}):
            self.group["tests"] = [reference]
            with self.subTest(reference=reference), self.assertRaises(ValueError):
                inventory.check(self.current, self.reviews)

    def test_source_only_contract_cannot_be_claimed_as_runtime_test_evidence(self):
        self.reviews["semantic_contracts"][0]["classification"] = "decorative"
        self.reviews["semantic_reviews"][0].update(classification="decorative",
                                                   evidence={"reviewed evidence": "A fixed flourish only."})
        del self.reviews["semantic_contracts"][0]["tests"]
        with self.assertRaisesRegex(ValueError, "source-only contract"):
            inventory.check(self.current, self.reviews)

    def test_test_reference_is_a_declaration_in_the_named_class_and_is_not_cached_across_checks(self):
        with tempfile.TemporaryDirectory(prefix="combat-reference-") as folder:
            root = Path(folder)
            java = root / "game-control/src/test/java/ExampleTest.java"
            java.parent.mkdir(parents=True)
            java.write_text('class ExampleTest { void current() {} }')
            inventory._reviewed_tests(["ExampleTest#current"], "fixture", root)
            java.write_text('class ExampleTest { /* void current() {} */ void other() { current(); } }')
            with self.assertRaisesRegex(ValueError, "unresolved test reference"):
                inventory._reviewed_tests(["ExampleTest#current"], "fixture", root)
            python = root / "game-control/src/test/python/test_example.py"
            python.parent.mkdir(parents=True)
            python.write_text('class ExampleTest:\n    pass\nclass DifferentTest:\n    def current(self): pass\n')
            with self.assertRaisesRegex(ValueError, "unresolved test reference"):
                inventory._reviewed_tests(["ExampleTest#current"], "fixture", root)
            python.write_text('class ExampleTest:\n    def current(self): pass\n')
            inventory._reviewed_tests(["ExampleTest#current"], "fixture", root)

    def test_retired_contract_is_explicit_history_and_cannot_authorize_current_groups(self):
        retired = {"id": "old-screen-adapter", "classification": "nonvisual",
                   "source_producer": "DeletedScreenCollector", "reason": "Unused collector removed.",
                   "retired_in": "CLI.9.0.0"}
        self.reviews["retired_contracts"] = [retired]
        self.assertEqual(inventory.check(self.current, self.reviews)["retired_contracts"], 1)
        for field, value in (("id", "current-cell"), ("reason", ""), ("retired_in", "old")):
            reviews = copy.deepcopy(self.reviews)
            reviews["retired_contracts"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                inventory.check(self.current, reviews)
        self.reviews["semantic_reviews"][0]["contract"] = retired["id"]
        with self.assertRaisesRegex(ValueError, "no matching reviewed contract"):
            inventory.check(self.current, self.reviews)

    def test_redundant_needs_same_frame_and_source_evidence(self):
        reviews = copy.deepcopy(self.reviews)
        reviews["semantic_contracts"][0].update(classification="redundant", replacement={
            "same_frame": True,
            "outputs": [{"owner": "PlayerObservation / CompactProtocol", "key": "data.map",
                         "allowed_fields": ["rows", "types"]}],
            "visibility_gate": "Current FOV and same frame.",
            "tests": ["GameSnapshotterTest#hiddenWorldChangesDoNotChangePublicObservation"],
        })
        reviews["semantic_reviews"][0]["classification"] = "redundant"
        reviews["groups"][0]["visibility_gate"] = "Current FOV and same frame."
        with self.assertRaises(ValueError): inventory.check(self.current, reviews)
        reviews["semantic_reviews"][0]["evidence"] = {
            "reviewed evidence": "This exact source only draws the current terrain already in the map row."
        }
        self.assertTrue(inventory.check(self.current, reviews)["checked"])
        reviews["semantic_contracts"][0]["replacement"]["same_frame"] = False
        with self.assertRaises(ValueError): inventory.check(self.current, reviews)


class CombatSourceAttributionTest(unittest.TestCase):
    def test_mage_staff_name_covers_every_generated_wand_with_distinct_public_names(self):
        root = Path(__file__).resolve().parents[4]
        staff = (root / "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/MagesStaff.java").read_text()
        observation = (root / "game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java").read_text()
        generator = (root / "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java").read_text()
        generated_block = generator.split("WAND.classes = new Class<?>[]{", 1)[1].split("};", 1)[0]
        generated = {name.lower() for name in re.findall(r"WandOf([A-Za-z]+)\.class", generated_block)}
        self.assertIn('Messages.get(wand, "staff_name")', staff)
        self.assertIn('"name", displayItemName(item)', observation)
        self.assertIn('if (!(item instanceof Pasty)) return item.name()', observation)
        self.assertEqual(len(generated), 13)
        for catalog in ("items.properties", "items_zh.properties"):
            messages = (root / "core/src/main/assets/messages/items" / catalog).read_text()
            names = dict(re.findall(r"^items\.wands\.wandof([a-z]+)\.staff_name=(.+)$", messages, re.M))
            self.assertEqual(set(names), generated, catalog)
            self.assertEqual(len(set(names.values())), len(names), catalog)

    def test_new_visual_camera_and_vector_sites_are_discovered_without_class_generation(self):
        root = Path(__file__).resolve().parents[4]
        output = root / "game-control/build/combat-visual"
        output.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="canary-", dir=output) as folder:
            source = Path(folder) / "UninitializedVisualCanary.java"
            source.write_text('''import com.watabou.noosa.Visual;
import com.watabou.noosa.Camera;
class UninitializedVisualCanary extends Visual {
  UninitializedVisualCanary() { super(0,0,8,8); }
  static { if (System.nanoTime() != Long.MIN_VALUE) throw new AssertionError("Do not initialize inventoried visuals"); }
  void effect() { alpha(0.5f); scale.set(2f); visible=false; new Camera(0,0,10,10,1).shake(2f,1f); }
}
''')
            result = inventory.scan(root, root / "desktop-control/build/test-runtime-classpath.txt", [source])
            entries = [entry for entry in result["entries"] if entry["file"].endswith(source.name)]
            self.assertTrue({"visual_type", "appearance_write", "appearance_mutator", "construct", "call"}
                            <= {entry["kind"] for entry in entries}, entries)
            self.assertTrue(any(entry["sink"].endswith("Camera.shake") for entry in entries))
            self.assertFalse(source.with_suffix(".class").exists())
            # Readiness helpers need not draw or mutate anything, so visual-sink discovery
            # alone cannot protect the source gate that certifies a completed action.
            char_sprite = [entry for entry in result["entries"]
                           if entry["kind"] == "source_boundary"
                           and entry["owner"] == "com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite"]
            self.assertEqual(len(char_sprite), 1)
            self.assertRegex(char_sprite[0]["body_digest"], r"^[0-9a-f]{64}$")
