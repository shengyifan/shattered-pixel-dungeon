"""One-action lifecycle regressions using fake public responses only."""
import io
import json
import unittest
from unittest.mock import Mock, patch

from client_result import ActionResult, settle_action
from machine_smoke import Client
import protocol6


def initial(status="in_progress", **fields):
    return {"ok": True, "id": "action", "scope_id": "run:new", "status": status, **fields}


def receipt(status, **fields):
    return {"ok": True, "id": "lookup", "result": {"id": "action", "status": status, **fields}}


def observed(revision="latest"):
    return {"ok": True, "id": "current", "status": "completed", "result": {
        "scope_id": "run:new", "state_version": revision, "observation": {"scene": "game"}}}


class ClientResultTest(unittest.TestCase):
    def client(self, replies):
        return Mock(request=Mock(side_effect=replies),
                    last_wire_request={"id": "action", "s": "menu:original"})

    def test_sync_uses_original_observation_and_preserves_save_receipt(self):
        reply = initial("completed", result={"persistence": {"saves_during_request": [{"receipt_id": "save-1"}]}})
        client = self.client([])
        result = settle_action(client, reply, "game.save").require_success()
        self.assertIs(reply, result.outcome)
        self.assertIs(reply["result"], result.observation)
        self.assertIs(reply, result.initial_response)
        client.request.assert_not_called()

    def test_async_pending_then_terminal_keeps_receipt_and_newer_observation_separate(self):
        saved = {"saves_during_request": [{"receipt_id": "save-2"}]}
        terminal = receipt("COMPLETED", persistence=saved, after_rev="older")
        current = observed()
        client = self.client([receipt("RECEIVED"), receipt("EXECUTING"), terminal, current])
        result = settle_action(client, initial(), "cell.select", poll_interval=0).require_success()
        self.assertIs(terminal["result"], result.outcome)
        self.assertEqual(saved, result.outcome["persistence"])
        self.assertIs(current["result"], result.observation)
        self.assertEqual("menu:original", result.scope_id)
        self.assertEqual("run:new", result.initial_response["scope_id"])
        self.assertEqual("latest", result.observation["state_version"])
        calls = client.request.call_args_list
        self.assertEqual(["request.get"] * 3 + ["state.get"], [call.args[0] for call in calls])
        for call in calls[:-1]:
            self.assertEqual({"target_id": "action"}, call.args[1])
            self.assertEqual("menu:original", call.kwargs["scope"])
        self.assertNotIsInstance(result, dict)

    def test_interrupted_and_awaiting_input_remain_original_action_status(self):
        for status in ("INTERRUPTED", "AWAITING_INPUT"):
            client = self.client([receipt(status), observed()])
            result = settle_action(client, initial(), "rest").require_success()
            self.assertEqual(status.lower(), result.status)
            self.assertEqual("completed", result.observation_response["status"])

    def test_finite_resolving_action_discovers_new_scope_before_fresh_state(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "menu:original", "r1", 0, "finite"
        replies = [
            {"v": 6, "id": "finite-1", "s": "menu:original", "st": "in_progress",
             "data": {"phase": "resolving", "snapshot_status": "last_stable"}},
            {"v": 6, "id": "finite-2", "s": "menu:original", "st": "completed",
             "data": {"id": "finite-1", "st": "COMPLETED", "save": [{"receipt_id": "saved"}]}},
            {"v": 6, "id": "finite-3", "s": "run:new", "rev": "r2", "st": "completed",
             "data": {"cli_version": "CLI.6.0.0"}},
            {"v": 6, "id": "finite-4", "s": "run:new", "rev": "r3", "st": "completed",
             "data": {"phase": "player_ready", "scene": "game"}}]
        client.buffer = b"".join(protocol6.wire_bytes(reply) for reply in replies)
        result = client.act("ui.activate", control="start")
        sent = [json.loads(line) for line in client.process.stdin.getvalue().splitlines()]
        self.assertEqual(["click", "req", "info", "state"], [request["op"] for request in sent])
        self.assertEqual("menu:original", sent[1]["s"])
        self.assertEqual("run:new", sent[-1]["s"])
        self.assertEqual(("run:new", "r3"), (client.scope, client.version))
        self.assertEqual("menu:original", result.scope_id)
        self.assertEqual("r2", result.discovery_response["result"]["state_version"])
        self.assertEqual("r3", result.observation["state_version"])
        self.assertEqual([{"receipt_id": "saved"}], result.outcome["save"])
        self.assertTrue(all("get" not in request for request in sent))

    def test_finite_scope_discovery_failure_stops_without_state_or_replay(self):
        for phase in ("resolving", "cancelling"):
            for failure in ({"ok": False, "error": {"code": "SCOPE_MISMATCH"}}, TimeoutError("info timeout")):
                client = self.client([receipt("COMPLETED"), failure, observed()])
                result = settle_action(client, initial(result={"phase": phase}), "wait")
                self.assertFalse(result.ok)
                self.assertIsNone(result.observation_response)
                self.assertIs(failure, result.failure)
                self.assertEqual(["request.get", "protocol.info"], [call.args[0] for call in client.request.call_args_list])
                if isinstance(failure, dict):
                    self.assertIs(failure, result.discovery_response)
                else:
                    self.assertIsNone(result.discovery_response)

    def test_continuous_activity_keeps_receipt_then_state_without_discovery(self):
        client = self.client([receipt("COMPLETED"), observed()])
        result = settle_action(client, initial(result={"phase": "continuous_activity", "state_version": "activity:r1"}), "rest")
        self.assertTrue(result.ok)
        self.assertIsNone(result.discovery_response)
        self.assertEqual(["request.get", "state.get"], [call.args[0] for call in client.request.call_args_list])

    def test_uncertified_finite_wait_does_not_call_continuous_observation_hook(self):
        for phase in ("resolving", "cancelling"):
            client = self.client([receipt("EXECUTING"), receipt("COMPLETED"), observed("discovered"), observed()])
            callback = Mock()
            result = settle_action(client, initial(result={"phase": phase}), "wait", poll_interval=0, on_pending=callback)
            self.assertTrue(result.ok)
            callback.assert_not_called()
            self.assertEqual(["request.get", "request.get", "protocol.info", "state.get"],
                             [call.args[0] for call in client.request.call_args_list])

    def test_errors_and_unknown_receipts_cannot_be_replaced_by_successful_state(self):
        for terminal in (receipt("UNKNOWN"), receipt("REJECTED", err="STALE_STATE"),
                         receipt("COMPLETED", error={"code": "AUDIT_UNAVAILABLE"}),
                         {"ok": False, "error": {"code": "AUDIT_UNAVAILABLE"}}, receipt("unexpected")):
            client = self.client([terminal, observed()])
            result = settle_action(client, initial(), "wait")
            self.assertFalse(result.ok)
            self.assertIsNone(result.observation)
            client.request.assert_called_once()
            with self.assertRaises(AssertionError):
                result.require_success()

    def test_initial_rejection_has_no_replay_or_query(self):
        reply = {"ok": False, "id": "action", "error": {"code": "STALE_STATE"}}
        client = self.client([])
        result = settle_action(client, reply, "wait")
        self.assertFalse(result.ok)
        self.assertIs(reply, result.initial_response)
        client.request.assert_not_called()

    def test_query_failure_or_timeout_preserves_original_identity_and_receipt(self):
        terminal = receipt("COMPLETED")
        for replies in ([TimeoutError("pipe")], [terminal, TimeoutError("state pipe")],
                        [terminal, {"ok": False, "error": {"code": "SCOPE_MISMATCH"}}]):
            result = settle_action(self.client(replies), initial(), "wait")
            self.assertFalse(result.ok)
            self.assertEqual("action", result.request_id)
            self.assertIsNotNone(result.failure)
        result = settle_action(self.client([]), initial(), "wait", timeout=0)
        with self.assertRaises(TimeoutError):
            result.require_success()

    def test_quit_never_queries_state_after_success(self):
        for status in ("completed", "in_progress"):
            client = self.client([receipt("COMPLETED")])
            result = settle_action(client, initial(status, result={"phase": "resolving"}), "app.quit").require_success()
            self.assertEqual("completed", result.status)
            self.assertTrue(all(call.args[0] == "request.get" for call in client.request.call_args_list))

    def test_pending_hook_can_observe_and_cancel_without_replaying_action(self):
        client = self.client([receipt("EXECUTING"), receipt("INTERRUPTED"), observed()])
        callback = Mock()
        result = settle_action(client, initial(), "rest", poll_interval=0, on_pending=callback)
        self.assertTrue(result.ok)
        callback.assert_called_once_with(result)
        self.assertNotIn("action.execute", [call.args[0] for call in client.request.call_args_list])

    def test_history_full_reply_never_overwrites_live_revision(self):
        client = object.__new__(Client)
        client.process = Mock(stdin=io.BytesIO())
        client.scope, client.version, client.counter, client.prefix = "run:new", "latest", 0, "history"
        old = {"v": 6, "id": "old", "s": "menu:old", "rev": "older", "st": "completed",
               "data": {"scene": "title"}}
        wire = {"v": 6, "id": "query", "s": "run:new", "st": "completed",
                "data": {"id": "old", "st": "COMPLETED", "reply": old}}
        client.buffer = protocol6.wire_bytes(wire)
        result = client.request("request.get", {"target_id": "old", "get": ["reply"]}, request_id="query")
        self.assertEqual("older", result["result"]["response"]["result"]["state_version"])
        self.assertEqual(("run:new", "latest"), (client.scope, client.version))


if __name__ == "__main__":
    unittest.main()
