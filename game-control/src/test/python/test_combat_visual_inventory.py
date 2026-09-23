"""Small gate tests; these do not run the Java source scanner."""
from copy import deepcopy
import unittest

from combat_visual_inventory import check


class SemanticReviewGateTest(unittest.TestCase):
    def setUp(self):
        self.inventory = {
            "source_files": 1,
            "entries": [{"id": "site:1", "body_digest": "reviewed"}],
        }
        self.reviews = {
            "format": "combat_visual_reviews_v2",
            "runtime_verified_by_inventory": False,
            "semantic_contracts": [{
                "id": "visible-warning",
                "classification": "semantic_required",
                "source_producer": "TestWarning.draw",
                "reason": "The warning marks a visible hazard cell.",
                "outputs": [{"owner": "VisualCueCollector", "key": "data.cues",
                             "allowed_fields": ["cell", "kind"]}],
                "visibility_gate": "Current FOV and full viewport.",
                "tests": ["CombatVisualCueTest#visibleWarning"],
            }],
            "semantic_reviews": [{"classification": "semantic_required",
                                  "contract": "visible-warning", "groups": ["warning"]}],
            "groups": [{
                "id": "warning", "classification": "covered_current",
                "reason": "A warning mark is decision relevant.",
                "trigger": "Current warning draw.",
                "visible_evidence": "Native warning mark.",
                "visibility_gate": "Current FOV and full viewport.",
                "lifecycle": "It expires with the draw.",
                "route": "VisualCueCollector data.cues",
                "tests": ["CombatVisualCueTest#visibleWarning"],
                "sites": [{"id": "site:1", "body_digest": "reviewed"}],
            }],
        }

    def test_reviewed_site_passes_without_claiming_runtime_verification(self):
        result = check(self.inventory, self.reviews)
        self.assertTrue(result["checked"])
        self.assertIs(result["runtime_verified_by_inventory"], False)
        self.assertEqual(result["semantic_classifications"], {"semantic_required": 1})

    def test_new_and_changed_sites_block(self):
        added = deepcopy(self.inventory)
        added["entries"].append({"id": "new:1", "body_digest": "new"})
        self.assertEqual(check(added, self.reviews)["unreviewed"], ["new:1"])
        self.assertFalse(check(added, self.reviews)["checked"])
        changed = deepcopy(self.inventory)
        changed["entries"][0]["body_digest"] = "changed"
        self.assertEqual(check(changed, self.reviews)["changed"], ["site:1"])
        self.assertFalse(check(changed, self.reviews)["checked"])

    def test_required_review_needs_specific_fields_and_test(self):
        for field in ("owner", "key", "allowed_fields"):
            reviews = deepcopy(self.reviews)
            del reviews["semantic_contracts"][0]["outputs"][0][field]
            with self.subTest(field=field), self.assertRaises(ValueError):
                check(self.inventory, reviews)
        reviews = deepcopy(self.reviews)
        reviews["semantic_contracts"][0]["tests"] = []
        with self.assertRaises(ValueError):
            check(self.inventory, reviews)

    def test_redundant_needs_same_frame_replacement(self):
        reviews = deepcopy(self.reviews)
        contract = reviews["semantic_contracts"][0]
        contract["classification"] = "redundant"
        reviews["semantic_reviews"][0]["classification"] = "redundant"
        reviews["semantic_reviews"][0]["evidence"] = {
            "warning": "The same current entity row already carries the warning cell and kind."
        }
        del contract["outputs"]
        with self.assertRaises(ValueError):
            check(self.inventory, reviews)
        contract["replacement"] = {
            "same_frame": True,
            "outputs": [{"owner": "PlayerObservation", "key": "data.entities",
                         "allowed_fields": ["visible", "cell"]}],
            "visibility_gate": "Same frame and current FOV.",
            "tests": ["GameSnapshotterTest#visibleEntity"],
        }
        self.assertTrue(check(self.inventory, reviews)["checked"])

    def test_missing_or_duplicate_group_assignment_blocks(self):
        reviews = deepcopy(self.reviews)
        reviews["semantic_reviews"][0]["groups"] = ["different"]
        with self.assertRaises(ValueError):
            check(self.inventory, reviews)

    def test_retired_source_site_needs_reason_and_cannot_be_live(self):
        reviews = deepcopy(self.reviews)
        reviews["retired_sites"] = [{"id": "removed:1", "old_group": "old-warning",
                                     "file": "OldWarning.java", "method": "draw",
                                     "reason": "The source now publishes its named current cue in observeGameplayVisuals."}]
        self.assertEqual(check(self.inventory, reviews)["retired_sites"], 1)
        reviews["retired_sites"][0]["id"] = "site:1"
        with self.assertRaises(ValueError):
            check(self.inventory, reviews)
        reviews["retired_sites"][0]["id"] = "removed:1"
        reviews["retired_sites"][0]["reason"] = ""
        with self.assertRaises(ValueError):
            check(self.inventory, reviews)
        reviews = deepcopy(self.reviews)
        reviews["semantic_reviews"].append(deepcopy(reviews["semantic_reviews"][0]))
        with self.assertRaises(ValueError):
            check(self.inventory, reviews)

    def test_downgrade_requires_source_specific_evidence(self):
        reviews = deepcopy(self.reviews)
        reviews["semantic_contracts"][0]["classification"] = "decorative"
        reviews["semantic_reviews"][0]["classification"] = "decorative"
        with self.assertRaises(ValueError):
            check(self.inventory, reviews)
        reviews["semantic_reviews"][0]["evidence"] = {
            "warning": "The exact source renders a fixed flourish; the warning remains a named cue."
        }
        self.assertTrue(check(self.inventory, reviews)["checked"])

    def test_unresolved_never_passes(self):
        reviews = deepcopy(self.reviews)
        reviews["semantic_contracts"] = []
        reviews["semantic_reviews"][0] = {"classification": "unresolved", "contract": None,
                                          "groups": ["warning"]}
        result = check(self.inventory, reviews)
        self.assertFalse(result["checked"])
        self.assertEqual(result["outstanding"], ["site:1"])
        self.assertEqual(result["outstanding_groups"], ["warning"])


if __name__ == "__main__":
    unittest.main()
