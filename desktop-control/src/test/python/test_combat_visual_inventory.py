import importlib.util
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
        self.group.update(classification="covered_current", tests=["fixture:visible"],
                          sites=[{"id": "exact:1", "body_digest": "body1"}])

    def test_exact_review_is_static_evidence_only(self):
        report = inventory.check(self.current, {"groups": [self.group]})
        self.assertTrue(report["checked"])
        self.assertFalse(report["runtime_verified_by_inventory"])

    def test_new_changed_removed_or_unresolved_sources_fail(self):
        for mutation in ("added", "changed", "removed", "unresolved"):
            self.setUp()
            if mutation == "added": self.current["entries"].append({"id": "new:1", "body_digest": "new"})
            if mutation == "changed": self.current["entries"][0]["body_digest"] = "changed"
            if mutation == "removed": self.current["entries"].clear()
            if mutation == "unresolved": self.group["classification"] = "unresolved"
            self.assertFalse(inventory.check(self.current, {"groups": [self.group]})["checked"], mutation)

    def test_duplicate_or_unjustified_classification_fails(self):
        with self.assertRaises(ValueError): inventory.check(self.current, {"groups": [self.group, self.group]})
        self.group["reason"] = ""
        with self.assertRaises(ValueError): inventory.check(self.current, {"groups": [self.group]})


class CombatSourceAttributionTest(unittest.TestCase):
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
