"""Language-switch harness checks without starting a game, JVM, or build."""
import copy
import unittest
from unittest.mock import Mock

import language_switch_smoke as switch


class LanguageSwitchSmokeTest(unittest.TestCase):
    def state(self, language="zh", version="v1"):
        return {"scope_id": "run:test", "state_version": version, "phase": "awaiting_input",
                "observation": {"hero": {"hp": 20, "cell": 100, "name": "warrior"}, "inventory": [],
                                "ui": {"display": {"language": language, "fullscreen": False}}}, "actions": []}

    def test_language_selection_uses_nested_code_and_never_native_or_english_labels(self):
        state = self.state()
        state["actions"] = [{"action": "ui.activate", "control": "correct", "label": "Any displayed label",
                             "text_sources": {"label": {"kind": "case", "value": {"kind": "language", "code": "ko"}}}},
                            {"action": "ui.activate", "control": "wrong", "label": "Korean"}]
        self.assertEqual("correct", switch.language_action(state, "ko")["control"])
        state["actions"] = state["actions"][1:]
        with self.assertRaises(AssertionError):
            switch.language_action(state, "ko")

    def test_semantic_invariants_keep_names_numbers_and_inventory_knowledge(self):
        state = self.state()
        baseline = switch.hero_inventory(state)
        state["observation"]["hero"]["text_sources"] = {"name": {"kind": "resource", "key": "hero"}}
        self.assertEqual(baseline, switch.hero_inventory(state))
        state["observation"]["hero"]["hp"] = 19
        self.assertNotEqual(baseline, switch.hero_inventory(state))
        state["observation"]["hero"]["hp"] = 20
        state["observation"]["inventory"] = [{"name": "potion", "type_known": False}]
        self.assertNotEqual(baseline, switch.hero_inventory(state))

    def test_expected_stale_negative_has_one_old_action_and_no_replay(self):
        before = self.state("zh", "v1")
        after = self.state("en", "v2")
        client = Mock()
        client.request.side_effect = [
            {"id": "expected-stale", "ok": False, "error": {"code": "STALE_STATE"}},
            {"ok": True, "result": {"status": "REJECTED", "before_snapshot": {"hp": 20}, "after_snapshot": {"hp": 20}}}]
        client.state.return_value = after
        result = switch.reject_stale(client, before, "old-handle", after, "en", switch.hero_inventory(before))
        self.assertTrue(result["rejected_before_action"])
        self.assertEqual(["action.execute", "request.get"], [call.args[0] for call in client.request.call_args_list])
        self.assertEqual("v1", client.request.call_args_list[0].kwargs["version"])
        self.assertEqual("old-handle", client.request.call_args_list[0].args[1]["control"])

    def test_historical_response_comparison_keeps_the_old_gui_language_and_payload(self):
        original = {"id": "old", "scope_id": "run:test", "ok": True, "result": {"ui": {"display": {"language": "zh"}}}}
        client = Mock()
        client.request.return_value = {"ok": True, "result": {"response": copy.deepcopy(original)}}
        self.assertEqual("old", switch.assert_recorded_response(client, original))
        client.request.return_value["result"]["response"]["result"]["ui"]["display"]["language"] = "en"
        with self.assertRaises(AssertionError):
            switch.assert_recorded_response(client, original)


if __name__ == "__main__":
    unittest.main()
