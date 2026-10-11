"""Language-switch harness checks without starting a game, JVM, or build."""
import copy
import unittest
from unittest.mock import Mock

import language_switch_smoke as switch


class LanguageSwitchSmokeTest(unittest.TestCase):
    def state(self, language="zh", version="v1"):
        return {'s': "run:test", 'rev': version, "phase": "awaiting_input",
                "hero": {"hp": 20, "cell": 100, "name": "warrior"}, 'inv': [],
                "ui": {"display": {"language": language, "fullscreen": False}}, 'acts': []}

    def test_language_selection_uses_nested_code_and_never_native_or_english_labels(self):
        state = self.state()
        state['acts'] = [{'op': 'click', 'ctl': "correct", "label": "Any displayed label",
                             "text_sources": {"label": {"kind": "case", "value": {"kind": "language", "code": "ko"}}}},
                            {'op': 'click', 'ctl': "wrong", "label": "Korean"}]
        self.assertEqual("correct", switch.language_action(state, "ko")['ctl'])
        state['acts'] = state['acts'][1:]
        with self.assertRaises(AssertionError):
            switch.language_action(state, "ko")

    def test_semantic_invariants_keep_names_numbers_and_inventory_knowledge(self):
        state = self.state()
        baseline = switch.hero_inventory(state)
        state["hero"]["text_sources"] = {"name": {"kind": "resource", "key": "hero"}}
        self.assertEqual(baseline, switch.hero_inventory(state))
        state["hero"]["hp"] = 19
        self.assertNotEqual(baseline, switch.hero_inventory(state))
        state["hero"]["hp"] = 20
        state['inv'] = [{"name": "potion", "type_known": False}]
        self.assertNotEqual(baseline, switch.hero_inventory(state))

    def test_expected_stale_negative_has_one_old_action_and_no_replay(self):
        before = self.state("zh", "v1")
        after = self.state("en", "v2")
        client = Mock()
        client.request_action.return_value = {"v": 9, "id": "expected-stale", "err": "STALE_STATE"}
        client.request.return_value = {"v": 9, "id": "receipt", "st": "completed",
                                       "data": {"st": "REJECTED", 'before': {"hp": 20}, 'after': {"hp": 20}}}
        client.state.return_value = after
        result = switch.reject_stale(client, before, "old-handle", after, "en", switch.hero_inventory(before))
        self.assertTrue(result["rejected_before_action"])
        client.request_action.assert_called_once_with({"op": "click", "ctl": "old-handle"},
                                                      scope="run:test", version="v1")
        self.assertEqual(['req'], [call.args[0] for call in client.request.call_args_list])

    def test_historical_response_comparison_keeps_the_old_gui_language_and_payload(self):
        original = {"v": 9, "id": "old", 's': "run:test", "st": "completed", "data": {"ui": {"display": {"language": "zh"}}}}
        original = switch.decode_response(original)
        client = Mock()
        client.request.return_value = {"v": 9, "id": "receipt", "st": "completed", "data": {"reply": copy.deepcopy(original)}}
        self.assertEqual("old", switch.assert_recorded_response(client, original))
        client.request.return_value["data"]["reply"]["data"]["ui"]["display"]["language"] = "en"
        with self.assertRaises(AssertionError):
            switch.assert_recorded_response(client, original)


if __name__ == "__main__":
    unittest.main()
