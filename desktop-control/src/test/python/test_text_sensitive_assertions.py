"""Public fixture assertion checks only; no Java, GUI, model getters or save reads."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import ending_scenario_smoke as ending
import inspected_item_smoke as inspection
import upgrade_preview_smoke as upgrade


class TextSensitiveAssertionsTest(unittest.TestCase):
    def test_ui_checkpoint_uses_actual_language_code_without_guessing_an_enum_name(self):
        state = {"scope_id": "run:test", "state_version": "v1", "observation": {"ui": {
            "display": {"language": "en", "fullscreen": False}}}}
        row = {"scope_id": "run:test", "state_version": "v1", "language": "ENUM_NAME_IS_OPAQUE",
               "language_code": "en", "fullscreen": False, "window_classes": ["ExpectedWindow"]}
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory)
            (profile / "ui-assertions.jsonl").write_text(json.dumps(row) + "\n")
            with patch.dict(ending.os.environ, {"SPDCTL_TEST_LANGUAGE": "en"}):
                self.assertEqual(row, ending.ui_assertion(profile, state, "ExpectedWindow"))

    def test_ui_checkpoint_keeps_scope_language_window_and_fullscreen_postconditions(self):
        state = {"scope_id": "run:test", "state_version": "v1", "observation": {"ui": {
            "display": {"language": "en", "fullscreen": False}}}}
        base = {"scope_id": "run:test", "state_version": "v1", "language": "ENGLISH", "language_code": "en",
                "fullscreen": False, "window_classes": ["ExpectedWindow"]}
        with tempfile.TemporaryDirectory() as directory, patch.dict(ending.os.environ, {"SPDCTL_TEST_LANGUAGE": "en"}):
            profile = Path(directory)
            for replacement in ({"scope_id": "run:other"}, {"language_code": "zh"}, {"fullscreen": True}, {"window_classes": []}):
                with self.subTest(replacement=replacement):
                    (profile / "ui-assertions.jsonl").write_text(json.dumps({**base, **replacement}) + "\n")
                    with self.assertRaises(AssertionError):
                        ending.ui_assertion(profile, state, "ExpectedWindow")

    def state(self, known):
        text = ("The Duelist can use the tip of a spear to spike an enemy that is in range but not adjacent. "
                + ("This deals " if known else "This typically deals ")
                + "11-29 damage, knocks the enemy back, and is guaranteed to hit.")
        source = {"kind": "displayed", "markup": True, "value": {"kind": "resource",
                  "key": "items.weapon.melee.spear." + ("ability_desc" if known else "typical_ability_desc"),
                  "args": [{"kind": "scalar", "value": 11}, {"kind": "scalar", "value": 29}]}}
        return {"observation": {"ui": {"inspected_item": {"control": "window", "level_known": known},
                "controls": [{"id": "window", "role": "window", "text": text, "text_sources": {"text": source}}]}}}

    def test_inspection_uses_drawn_text_and_source_numbers_for_both_knowledge_branches(self):
        for known in (False, True):
            state = self.state(known)
            before = copy.deepcopy(state)
            knowledge, text = inspection.inspected(state, known)
            self.assertIs(known, knowledge["level_known"])
            self.assertIn("11-29 damage", text)
            self.assertEqual(before, state)
            state["observation"]["inventory"] = [{"locator": "equipment.weapon", "level_known": True}]
            self.assertEqual((knowledge, text), inspection.inspected(state, known))

    def test_inspection_still_rejects_wrong_stats_wrong_resource_and_regenerated_body(self):
        for mutate in ("number", "key", "knowledge"):
            state = self.state(False)
            owner = state["observation"]["ui"]["controls"][0]
            if mutate == "number":
                owner["text"] = owner["text"].replace("11-29", "12-29")
            elif mutate == "key":
                owner["text_sources"]["text"]["value"]["key"] = "items.weapon.melee.spear.ability_desc"
            else:
                state["observation"]["ui"]["inspected_item"]["level_known"] = True
            with self.subTest(mutate=mutate), self.assertRaises(AssertionError):
                inspection.inspected(state, False)

    def preview(self, language):
        delimiter = "~" if language in {"zh", "zh-hant"} else "-"
        values = ["Blocking", "0" + delimiter + "2", "1" + delimiter + "3", "Weight", "10", "9"]
        nodes = [{"role": "window", "text": "Introduction\n" + "\n".join(values)}]
        for value in values:
            if value in {"Blocking", "Weight"}:
                source = {"kind": "resource", "key": "windows.wndupgrade." + value.lower(), "args": []}
            elif value in {"10", "9"}:
                source = {"kind": "literal", "origin": "symbol", "value": value}
            else:
                left, right = value.split(delimiter)
                source = {"kind": "concat", "parts": [{"kind": "scalar", "value": int(left)},
                          {"kind": "literal", "origin": "literal", "value": "-"}, {"kind": "scalar", "value": int(right)}]}
                if delimiter == "~":
                    source = {"kind": "replace", "value": source, "old": "-", "new": "~"}
            nodes.append({"role": "text", "text": value, "text_sources": {"text": source}})
        return {"observation": {"ui": {"display": {"language": language}, "controls": nodes}}}

    def test_upgrade_range_punctuation_follows_actual_gui_language_and_keeps_frozen_values(self):
        for language in ("en", "zh", "zh-hant", "ja", "ko", "ru", "fr", "de", "tr"):
            state = self.preview(language)
            self.assertEqual("~" if language in {"zh", "zh-hant"} else "-", upgrade.assert_preview_fields(state))
            state["observation"]["ui"]["controls"][2]["text_sources"]["text"] = {"kind": "scalar", "value": 42}
            with self.subTest(language=language), self.assertRaises(AssertionError):
                upgrade.assert_preview_fields(state)

    def test_upgrade_chinese_materialized_numeric_sources_do_not_require_removed_scalars(self):
        # Matches the final privacy-safe sources in the actual zh upgrade preview.
        state = self.preview("zh")
        numeric_nodes = [node for node in state["observation"]["ui"]["controls"] if node.get("text") in {"0~2", "1~3", "10", "9"}]
        for node in numeric_nodes:
            node["text_sources"]["text"] = {"kind": "displayed", "value": {
                "kind": "literal", "origin": "symbol", "value": node["text"]}, "markup": True}
        self.assertEqual("~", upgrade.assert_preview_fields(state))
        original = copy.deepcopy(state)
        numeric_nodes[0]["text_sources"]["text"]["value"] = {"kind": "formatted_fragment", "specifier": "%s", "text": "0~2"}
        self.assertEqual("~", upgrade.assert_preview_fields(state))
        for bad in ({"kind": "literal", "origin": "symbol", "value": "0~3"},
                    {"kind": "literal", "origin": "user", "value": "0~2"},
                    {"kind": "formatted_fragment", "specifier": "%s", "text": "hidden0~2"}, None):
            state = copy.deepcopy(original)
            state["observation"]["ui"]["controls"][2]["text_sources"]["text"] = bad
            with self.subTest(source=bad), self.assertRaises(AssertionError):
                upgrade.assert_preview_fields(state)


if __name__ == "__main__":
    unittest.main()
