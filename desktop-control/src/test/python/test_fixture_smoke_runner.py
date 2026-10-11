#!/usr/bin/env python3
"""Offline runner regressions. Fake transport/processes only; no JVM or game launch."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from machine_smoke import Client, reach_game

import fixture_smoke as runner


class FixtureSmokeRunnerTest(unittest.TestCase):
    def test_resume_matches_unique_multiline_current_save_slot_and_uses_action_observation(self):
        from unittest.mock import Mock
        menu = {"s": "s1", "rev": "r1", "scene": "menu", "ui": {"scene": "StartScene"},
                "acts": [{"op": "click", "ctl": "slot", "label": "Warrior\nDepth 2"}]}
        reply = {"v": 9, "id": "enter", "s": "s2", "rev": "r2", "st": "completed",
                 "data": {"scene": "game"}}
        client = Mock(state=Mock(return_value=menu), request_action=Mock(return_value=reply))
        self.assertEqual({"s": "s2", "rev": "r2", "scene": "game"}, reach_game(client, resume=True))
        client.state.assert_called_once()
        client.request_action.assert_called_once_with({"op": "click", "ctl": "slot", "g": "click"})
        client.request.assert_not_called()

    def test_in_progress_waits_for_the_original_result_without_another_game_action(self):
        from unittest.mock import Mock
        final={"s":"run:original","rev":"settled","scene":"game"}
        client=Mock(last_state=None,verify_gui=False)
        client.request.side_effect=[
            {"id":"original-action","s":"run:original","v":9,"st":"in_progress","data":{}},
            {"v":9,"id":"receipt1","st":"completed","data":{"id":"original-action","st":"EXECUTING"}},
            {"v":9,"id":"receipt2","st":"completed","data":{"id":"original-action","st":"COMPLETED"}},
            {"v":9,"id":"current","s":final["s"],"rev":final["rev"],"st":"completed","data":{"scene":"game"}}]
        with patch.object(runner.time,"sleep"):
            self.assertEqual(final,runner.act(client,"wait"))
        self.assertEqual(["wait","req","req","state"],[call.args[0] for call in client.request.call_args_list])
        for call in client.request.call_args_list[1:3]:
            self.assertEqual({"rid":"original-action"},call.args[1])
            self.assertEqual("run:original",call.kwargs["scope"])
        self.assertEqual("state",client.request.call_args_list[-1].args[0])
        client.state.assert_not_called()
        self.assertEqual(final,client.last_state)

    def test_failed_original_result_cannot_become_a_successful_later_observation(self):
        from unittest.mock import Mock
        client=Mock(last_state=None,verify_gui=False)
        client.request.side_effect=[
            {"id":"original-action","s":"run:original","v":9,"st":"in_progress","data":{}},
            {"v":9,"id":"receipt1","st":"completed","data":{"id":"original-action","st":"UNKNOWN"}},
            {"v":9,"data":{"st":"UNKNOWN","response":{"v":9,"err":"EXECUTION_UNKNOWN"}}}]
        with self.assertRaises(AssertionError):runner.act(client,"wait")
        client.state.assert_not_called()
        self.assertEqual(2,client.request.call_count)

    def test_prose_policy_matches_english_protocol_and_preserves_raw_identity(self):
        import english_protocol_smoke as protocol
        self.assertEqual(protocol.PROSE, runner.GAME_PROSE_FIELDS)
        self.assertEqual(protocol.RAW, runner.RAW_FIELDS)
        value = {"id": "原始标识", "path": "/原始目录", "value": "用户原文",
                 "raw_request": {"text": "不得翻译"}, "data": {"label": "Café", "options": ["Back", "3..."]}}
        before = json.dumps(value, ensure_ascii=False)
        runner.assert_game_prose_english(value)
        self.assertEqual(before, json.dumps(value, ensure_ascii=False))
        for text in ["法杖", "Текст", "かな", "한글"]:
            with self.subTest(text=text), self.assertRaises(AssertionError):
                runner.assert_game_prose_english({"data": {"response": {"data": {"desc": text}}}})

    @staticmethod
    def client(profile, verify_gui=False):
        client = object.__new__(runner.FixtureClient)
        client.profile = profile
        client.trace = io.StringIO()
        client.last_state = None
        client.verify_gui = verify_gui
        client.gui_postconditions_checked = 0
        return client

    def test_failed_translation_is_recorded_before_response_is_rejected(self):
        client = self.client(Path("unused-fixture"))
        response = {"id": "q1", "v": 9, "st": "completed", "data": {"items": [{"text": "未翻译日志"}], "next": None, "end": True, "until": 1}}
        with patch.object(runner.Client, "request", return_value=response), self.assertRaises(AssertionError):
            client.request("events")
        self.assertEqual(response, json.loads(client.trace.getvalue())["response"])

    def test_gui_postcondition_is_actual_same_version_language_and_window_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory)
            state = {"s": "run:test", "rev": "v1", "scene": "game", "ui": {"display": {"language": "zh", "fullscreen": False}}}
            response = {"id": "q2", "v": 9, "s": state["s"], "rev": state["rev"], "st": "completed", "data": {key: value for key, value in state.items() if key not in {"s", "rev"}}}
            for language, code, fullscreen, accepted in [("CHI_SMPL", "zh", False, True), ("ENGLISH", "en", False, False), ("CHI_SMPL", "zh", True, False)]:
                with self.subTest(language=language, fullscreen=fullscreen):
                    (profile / "ui-assertions.jsonl").write_text(json.dumps({"scope_id": "run:test", "state_version": "v1",
                                                                         "language": language, "language_code": code, "fullscreen": fullscreen}) + "\n")
                    client = self.client(profile, verify_gui=True)
                    with patch.dict(runner.os.environ, {"SPDCTL_TEST_LANGUAGE": "zh"}), patch.object(runner.Client, "request", return_value=response):
                        if accepted:
                            self.assertEqual(response, client.request("state"))
                            self.assertEqual(1, client.gui_postconditions_checked)
                        else:
                            with self.assertRaises(AssertionError): client.request("state")
                            self.assertEqual(0, client.gui_postconditions_checked)

    def test_other_transports_and_final_quit_do_not_require_live_gui_assertion_stream(self):
        state = {"s": "run:test", "rev": "v1", "scene": "game"}
        response = {"id": "q3", "v": 9, "s": state["s"], "rev": state["rev"], "st": "completed", "data": {"scene": "game"}}
        for verify_gui, op, args in [(False, "state", None), (True, "quit", None)]:
            client = self.client(Path("unused-fixture"), verify_gui)
            with patch.object(runner.Client, "request", return_value=response), patch.object(runner, "assert_gui_environment") as gui:
                self.assertEqual(response, client.request(op, args))
                gui.assert_not_called()

    def test_cleanup_failure_turns_successful_case_into_persisted_failure(self):
        class Process:
            def poll(self): return None
            def terminate(self): self.terminated = True
            def wait(self, timeout): return 0
        class Client:
            def __init__(self, command, profile, verify_gui=False):
                self.process = Process()
                self.trace = io.StringIO()
                self.gui_postconditions_checked = 2
                self.verify_gui = verify_gui
            def request(self, *args, **kwargs): return {"v":9,"st":"completed"}
            def finish(self): raise RuntimeError("native cleanup failed")
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            with patch.object(runner, "FixtureClient", Client), patch.object(runner, "reach_game", return_value={}), \
                    patch.object(runner, "assert_gui_environment", return_value={"gui_language": "CHI_SMPL", "fullscreen": False}), \
                    patch.object(runner, "initial_item_test", return_value={"opened": True}), patch.object(runner, "close_choices"):
                report = runner.run_one(Path(directory), "unused-classpath", "class:WARRIOR", "offline-test")
            self.assertFalse(report["ok"])
            self.assertEqual("native cleanup failed", report["cleanup_error"])
            self.assertEqual(report, json.loads((Path(report["profile"]) / "fixture-result.json").read_text()))

    def test_nonzero_exit_cannot_count_as_success_even_when_finish_does_not_raise(self):
        class Process:
            def poll(self): return 1
        class Client:
            def __init__(self, command, profile, verify_gui=False):
                self.process = Process()
                self.trace = io.StringIO()
                self.gui_postconditions_checked = 2
            def request(self, *args, **kwargs): return {"v":9,"st":"completed"}
            def finish(self): return None
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            with patch.object(runner, "FixtureClient", Client), patch.object(runner, "reach_game", return_value={}), \
                    patch.object(runner, "assert_gui_environment", return_value={}), \
                    patch.object(runner, "initial_item_test", return_value={"opened": True}), patch.object(runner, "close_choices"):
                report = runner.run_one(Path(directory), "unused-classpath", "class:WARRIOR", "offline-test")
            self.assertFalse(report["ok"])
            self.assertIn("fixture_exit_code", report["cleanup_error"])


if __name__ == "__main__":
    unittest.main()
