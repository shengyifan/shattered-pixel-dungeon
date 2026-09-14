"""Known-note structural selection never restores clipped text or reads private widget classes."""
import copy
import unittest
from unittest.mock import Mock

import notes_scenario_smoke as notes
from client_result import ActionResult


class NotesStructuralChoiceTest(unittest.TestCase):
    def state(self, body="Add Text"):
        def source(key):
            return {"kind": "displayed", "value": {"kind": "resource", "key": "ui.customnotebutton$customnotewindow." + key, "args": []}}
        controls = [{"id": "window", "role": "window", "enabled": True},
                    {"id": "title", "role": "text", "parent": "window", "text": "Known note",
                     "text_sources": {"text": {"kind": "literal", "origin": "user", "value": "Known note"}}},
                    {"id": "clipped", "role": "button", "parent": "window", "enabled": True, "clipped": True,
                     "text": "Partially displayed text", "text_sources": {"text": None}, "text_diagnostics": {"text": "clipped_text"}},
                    {"id": "body", "role": "button", "parent": "window", "enabled": True, "text": body},
                    {"id": "delete", "role": "button", "parent": "window", "enabled": True, "text": "Delete"}]
        actions = [{"action": "ui.activate", "control": "clipped", "label": "Partially displayed text", "gestures": ["click"],
                    "text_sources": {"label": None}, "text_diagnostics": {"label": "clipped_text"}},
                   {"action": "ui.activate", "control": "body", "label": body,
                    "text_sources": {"label": source("add_text" if body == "Add Text" else "edit_text")}},
                   {"action": "ui.activate", "control": "delete", "label": "Delete", "text_sources": {"label": source("delete")}},
                   {"action": "ui.back"}]
        return {"scope_id": "run:test", "state_version": "v1", "phase": "awaiting_input", "actions": actions,
                "observation": {"ui": {"scene": "GameScene", "modal": True, "controls": controls}}}

    def test_complete_known_note_structure_identifies_the_one_remaining_clipped_choice(self):
        for body in ("Add Text", "Edit Text"):
            state = self.state(body)
            original = copy.deepcopy(state)
            action, evidence = notes.note_edit_title_action(state, "Known note")
            self.assertEqual("clipped", action["control"])
            self.assertIsNone(action["text_sources"]["label"])
            self.assertTrue(evidence["clipped_source_remains_absent"])
            self.assertEqual(original, state)

    def test_incomplete_ambiguous_or_source_leaking_contexts_cannot_choose_a_button(self):
        def extra(state):
            state["actions"].append({"action": "ui.activate", "control": "extra", "label": "Unknown"})
        def wrong_parent(state):
            state["observation"]["ui"]["controls"][3]["parent"] = "another-window"
        def wrong_title(state):
            state["observation"]["ui"]["controls"][1]["text"] = "Different note"
        def wrong_reason(state):
            state["actions"][0]["text_diagnostics"]["label"] = "unclassified_string"
        def leaked_key(state):
            state["actions"][0]["text_sources"]["label"] = {"kind": "resource", "key": "ui.customnotebutton$customnotewindow.edit_title"}
        def wrong_named_source(state):
            state["actions"][1]["text_sources"]["label"]["value"]["key"] = "unrelated.add_text"
        def another_interaction(state):
            state["actions"].append({"action": "ui.text", "control": "editor"})
        for mutate in (extra, wrong_parent, wrong_title, wrong_reason, leaked_key, wrong_named_source, another_interaction):
            state = self.state()
            mutate(state)
            with self.subTest(mutation=mutate.__name__), self.assertRaises(AssertionError):
                notes.note_edit_title_action(state, "Known note")

    def test_activation_keeps_the_current_handle_and_requires_the_original_title_editor(self):
        state = self.state()
        editor = {"observation": {"ui": {"controls": [{"role": "text_input", "max_length": 50, "multiline": False, "value": "Known note"}]}}}
        client = Mock(scope="run:test", version="v1")
        client.state.return_value = state
        reply = {"ok": True, "status": "awaiting_input", "result": editor}
        client.act.return_value = ActionResult(reply, observation_response=reply)
        client.clipped_note_title_choices = []
        self.assertEqual(editor, notes.choose_edit_title(client, "Known note"))
        client.act.assert_called_once_with("ui.activate", control="clipped")
        self.assertEqual("v1", client.clipped_note_title_choices[0]["state_version"])
        editor["observation"]["ui"]["controls"][0]["max_length"] = 500
        with self.assertRaises(AssertionError):
            notes.choose_edit_title(client, "Known note")


if __name__ == "__main__":
    unittest.main()
