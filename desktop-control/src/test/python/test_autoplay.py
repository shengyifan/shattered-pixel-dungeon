"""Current interactive-driver checks using fake clients; no game is launched."""

import contextlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import autoplay
from client_result import ActionResult


class InteractiveDriverTest(unittest.TestCase):
    def client(self, state):
        return SimpleNamespace(last_state=state, action_count=0,
                               last_action_outcome=None,
                               last_operation_response=None, log=Mock(),
                               bounded_action=Mock())

    def test_unknown_command_never_dispatches_an_action(self):
        state = {"phase": "player_ready", "s": "s1", "rev": "r1"}
        client = self.client(state)
        commands = io.StringIO('{"command":"unknown"}\n{"command":"stop"}\n')
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result, reason = autoplay.interactive_public_session(client, Path("unused"), state,
                                                                 commands=commands)
        self.assertIs(state, result)
        self.assertEqual("interactive_public_checkpoint", reason)
        self.assertIn("unknown_interactive_command", output.getvalue())
        client.bounded_action.assert_not_called()

    def test_unresolved_choice_blocks_stop_until_operator_supplies_a_new_state(self):
        pending = {"phase": "awaiting_input", "s": "s1", "rev": "r1"}
        ready = {**pending, "phase": "player_ready", "rev": "r2"}
        client = self.client(pending)
        client.state = Mock(return_value=ready)
        commands = io.StringIO('{"command":"stop"}\n{"command":"state"}\n{"command":"stop"}\n')
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result, _ = autoplay.interactive_public_session(client, Path("unused"), pending,
                                                            commands=commands)
        self.assertIs(ready, result)
        self.assertIn("resolve_current_visible_choice_before_stopping", output.getvalue())
        client.state.assert_called_once_with()
        client.bounded_action.assert_not_called()

    def test_display_preserves_complete_same_frame_public_state(self):
        state = {"s": "s1", "rev": "r8", "phase": "player_ready",
                 "map": {"w": 2, "h": 1, "cells": [{"cell": 0}]},
                 "cues": {"world": [{"kind": "red_target", "cells": [0]}]},
                 "ui": {"nodes": [{"ops": [{"op": "click", "ctl": "c1", "label": "Wait"}]}]},
                 "acts": [{"op": "wait"}]}
        client = self.client(state)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            autoplay.interactive_public_session(client, Path("unused"), state,
                                                 commands=io.StringIO('{"command":"stop"}\n'))
        shown = json.loads(output.getvalue().splitlines()[0])
        self.assertEqual(state, shown["state"])
        self.assertIsNone(shown["last_operation"])

    def test_action_uses_displayed_native_binding_and_original_sync_observation(self):
        state = {"s": "s1", "rev": "r2", "phase": "player_ready", "acts": [{"op": "wait"}]}
        reply = {"v": 9, "id": "t1.1", "s": "s1", "rev": "r3", "st": "completed",
                 "data": {"scene": "game", "phase": "player_ready", "acts": [{"op": "wait"}]}}
        client = SimpleNamespace(log=Mock(), request_action=Mock(return_value=reply),
                                 state=Mock(),
                                 last_wire_request={"id": "t1.1", "s": "s1"})
        result = autoplay.PublicClient.bounded_action(client, state, "reviewed_wait", "wait")
        client.request_action.assert_called_once_with({"op": "wait"}, scope="s1", version="r2")
        client.state.assert_not_called()
        self.assertEqual({**reply["data"], "s": "s1", "rev": "r3"}, result)
        self.assertIs(reply, client.last_action_outcome.initial_response)

    def test_action_without_displayed_revision_is_not_rebound(self):
        client = SimpleNamespace(log=Mock(), request_action=Mock(), scope="s1", version="r9")
        with self.assertRaisesRegex(autoplay.Checkpoint, "missing_current_public_action_binding"):
            autoplay.PublicClient.bounded_action(client, {"s": "s1", "acts": [{"op": "wait"}]},
                                                  "missing_observation_revision", "wait")
        client.request_action.assert_not_called()

    def test_checkpoint_stays_outside_the_profile(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile = Path(temporary) / "profile"
            profile.mkdir()
            artifacts = profile.with_name(profile.name + ".artifacts")
            artifacts.mkdir()
            client = SimpleNamespace(artifact_dir=artifacts, scope="s1", version="r1",
                                     action_count=1, log=Mock())
            state = {"scene": "game"}
            with contextlib.redirect_stdout(io.StringIO()):
                autoplay.checkpoint(client, profile, state, "operator_checkpoint")
            self.assertEqual([], list(profile.iterdir()))
            record = json.loads((artifacts / "checkpoint-public.json").read_text())
            self.assertEqual(state, record["state"])
            self.assertNotIn("strategy_state", record)
            self.assertEqual(record, json.loads((artifacts / "checkpoint-game-public.json").read_text()))

    def test_summary_preserves_save_receipts_without_reprinting_observation(self):
        reply = {"id": "save1", "v": 9, "st": "completed", "data": {
            "scene": "game", "hero": {"hp": 10},
            "persistence": {"saves": [{"sid": "p1"}]}}}
        operation = ActionResult(reply, observation_response=reply)
        summary = autoplay.operation_summary(operation)
        self.assertEqual("save1", summary["id"])
        self.assertEqual([{"sid": "p1"}], summary["save"])
        self.assertNotIn("observation", json.dumps(summary))

    def test_startup_artifacts_do_not_populate_the_new_profile(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            captured = {}

            def no_game(command, profile, log, maximum):
                captured["profile_entries"] = list(profile.iterdir())
                captured["log_path"] = Path(log.name)
                captured["profile"] = profile
                raise RuntimeError("Synthetic stop before game startup")

            with patch.object(autoplay, "PLAYTHROUGHS", root), \
                    patch.object(autoplay, "PublicClient", side_effect=no_game), \
                    patch("sys.argv", ["autoplay.py", "--interactive", "--launcher", "/unused/spdctl"]), \
                    contextlib.redirect_stdout(io.StringIO()):
                autoplay.main()
            self.assertEqual([], captured["profile_entries"])
            self.assertEqual(captured["profile"].with_name(captured["profile"].name + ".artifacts"),
                             captured["log_path"].parent)


if __name__ == "__main__":
    unittest.main()
