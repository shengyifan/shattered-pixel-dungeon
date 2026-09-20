"""In-memory allocator checks: no launcher, files, profiles or game state."""
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from autoplay import PublicClient
from machine_smoke import Client
from transport_game_smoke import CapturedClient


class RequestIdTest(unittest.TestCase):
    def client(self, kind, prefix="tz"):
        client = kind.__new__(kind)
        client.scope, client.version, client.counter, client.prefix = "s1", "r1", 0, prefix
        client.process = SimpleNamespace(stdin=io.BytesIO(), stdout=None)
        client.buffer = b""
        client.profile = Path("unused-memory-fixture")
        if kind is PublicClient:
            client.public_log = io.StringIO()
            client.action_count, client.maximum, client.cleanup = 0, 1000, False
        if kind is CapturedClient:
            client.sent, client.received = bytearray(), bytearray()
            client.trace = io.StringIO()
            client.trace_root = Path("unused-memory-transport")
            client.source_checks = client.early_send_checks = 0
            client.responses, client.uncertain = [], False
        return client

    def respond(self, client, identifier, error=None):
        frame = {"v": 7, "id": identifier, "s": "s1"}
        if error:
            frame["err"] = error
        else:
            frame.update(st="completed", data={"items": [], "next": None, "end": True, "until": 0})
        client.buffer = (json.dumps(frame) + "\n").encode()

    def request(self, client, request_id=None):
        # CapturedClient's recorder-observation waits are irrelevant to this
        # in-memory test. The callbacks are never evaluated and no paths open.
        with patch("transport_game_smoke.wait_for", return_value=None):
            return client.request("events.read", request_id=request_id)

    def test_all_active_python_allocators_use_decimal_boundaries_and_keep_explicit_ids(self):
        for kind in (Client, PublicClient, CapturedClient):
            with self.subTest(client=kind.__name__):
                client = self.client(kind)
                for number in range(1, 101):
                    self.respond(client, f"tz-{number}")
                    self.assertTrue(self.request(client)["ok"])
                    self.assertEqual(f"tz-{number}", client.last_wire_request["id"])
                    self.assertRegex(client.last_wire_request["id"], r"^tz-[0-9]+$")
                sent = [json.loads(line) for line in client.process.stdin.getvalue().splitlines()]
                for number in (9, 10, 35, 36, 99, 100):
                    self.assertEqual(f"tz-{number}", sent[number - 1]["id"])
                self.respond(client, "custom.alpha-id")
                self.request(client, "custom.alpha-id")
                self.assertEqual("custom.alpha-id", client.last_wire_request["id"])
                self.assertEqual(101, client.counter)

    def test_rejected_requests_consume_decimal_ids_and_new_client_prefixes_do_not_collide(self):
        for kind in (Client, PublicClient, CapturedClient):
            with self.subTest(client=kind.__name__):
                client = self.client(kind)
                client.counter = 8
                self.respond(client, "tz-9", "INVALID_ARGUMENT")
                self.assertFalse(self.request(client)["ok"])
                self.respond(client, "tz-10")
                self.assertTrue(self.request(client)["ok"])
                self.assertEqual(10, client.counter)
                restarted = self.client(kind, "t10")
                self.respond(restarted, "t10-1")
                self.assertTrue(self.request(restarted)["ok"])
                self.assertEqual("t10-1", restarted.last_wire_request["id"])
                self.assertNotEqual(client.last_wire_request["id"], restarted.last_wire_request["id"])

    def test_write_failure_does_not_reuse_the_allocated_suffix(self):
        class FailedWrite(io.BytesIO):
            def write(self, value):
                raise OSError("Synthetic write failure")
        for kind in (Client, PublicClient, CapturedClient):
            with self.subTest(client=kind.__name__):
                client = self.client(kind)
                client.counter = 98
                client.process.stdin = FailedWrite()
                with self.assertRaises(OSError):
                    self.request(client)
                self.assertEqual(99, client.counter)
                self.assertEqual("tz-99", client.last_wire_request["id"])
                client.process.stdin = io.BytesIO()
                self.respond(client, "tz-100")
                self.assertTrue(self.request(client)["ok"])
                self.assertEqual("tz-100", client.last_wire_request["id"])


if __name__ == "__main__":
    unittest.main()
