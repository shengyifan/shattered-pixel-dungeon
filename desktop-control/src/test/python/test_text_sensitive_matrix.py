"""Orchestrator checks only: never starts Java, builds, or touches a game profile."""
import unittest
from pathlib import Path
from unittest.mock import patch

import text_sensitive_matrix_smoke as matrix


class TextSensitiveMatrixTest(unittest.TestCase):
    def test_default_cases_cover_the_high_risk_paths_and_all_keeps_container_pairs(self):
        self.assertEqual(("en", "zh", "zh-hant", "ja", "ko", "ru", "fr", "de", "tr"), matrix.LANGUAGES)
        self.assertEqual(matrix.DEFAULT_CASES, matrix.selected(None, matrix.CASES, matrix.DEFAULT_CASES))
        self.assertEqual(10, len(matrix.selected("all", matrix.CASES, matrix.DEFAULT_CASES)))
        matrix.validate_case_selection(matrix.CASES)
        with self.assertRaises(ValueError):
            matrix.validate_case_selection(("inspect:containers-a",))
        with self.assertRaises(ValueError):
            matrix.selected("en,en", matrix.LANGUAGES, matrix.LANGUAGES)

    def test_workers_receive_independent_environment_without_mutating_the_parent(self):
        parent = {"SPDCTL_TEST_LANGUAGE": "zh", "UNCHANGED": "value"}
        first = matrix.worker_environment("ko", parent)
        second = matrix.worker_environment("fr", parent)
        self.assertEqual("zh", parent["SPDCTL_TEST_LANGUAGE"])
        self.assertEqual("ko", first["SPDCTL_TEST_LANGUAGE"])
        self.assertEqual("fr", second["SPDCTL_TEST_LANGUAGE"])
        self.assertEqual("value", first["UNCHANGED"])

    def fake_client(self):
        evidence = matrix.Evidence("ko")
        cls = matrix.client_type(evidence)
        return cls.__new__(cls), evidence

    def test_first_protocol_error_stops_later_requests_before_the_transport(self):
        client, evidence = self.fake_client()
        failed = {"protocol_version": 4, "ok": False, "error": {"code": "STALE_STATE"}}
        with patch.object(matrix.MatrixClient, "request", return_value=failed) as transport:
            with self.assertRaises(AssertionError):
                client.request("action.execute", {"action": "wait"})
            with self.assertRaises(AssertionError):
                client.request("state.get")
            self.assertEqual(1, transport.call_count)
        self.assertEqual("action.execute", evidence.failure["op"])
        self.assertEqual(1, evidence.actions)

    def test_explicit_source_states_receive_validation_and_clipped_text_is_allowed(self):
        client, evidence = self.fake_client()
        missing = {"protocol_version": 4, "ok": True, "result": [{"text": "Unclassified engine text"}]}
        with patch.object(matrix.MatrixClient, "request", return_value=missing), self.assertRaises(AssertionError):
            client.request("state.get", {"src": True})
        self.assertIsNotNone(evidence.failure)
        client, evidence = self.fake_client()
        clipped = {"protocol_version": 4, "ok": True, "result": [{"text": "Partially displayed text", "clipped": True,
                    "text_sources": {"text": None}, "text_diagnostics": {"text": "clipped_text"}}]}
        with patch.object(matrix.MatrixClient, "request", return_value=clipped):
            self.assertEqual(clipped, client.request("state.get", {"src": True}))
        self.assertIsNone(evidence.failure)
        self.assertEqual(1, evidence.source_checks)

    def test_default_event_prose_does_not_require_omitted_source_trees(self):
        client, evidence = self.fake_client()
        response = {"protocol_version": 4, "ok": True, "result": [{"text": "An event"}]}
        with patch.object(matrix.MatrixClient, "request", return_value=response):
            self.assertEqual(response, client.request("events.read"))
        self.assertEqual(0, evidence.source_checks)

    def test_actual_gui_language_must_match_the_worker(self):
        client, evidence = self.fake_client()
        wrong = {"protocol_version": 4, "ok": True, "result": {"observation": {"ui": {
            "display": {"language": "zh", "fullscreen": False}}}}}
        with patch.object(matrix.MatrixClient, "request", return_value=wrong), self.assertRaises(AssertionError):
            client.request("state.get")
        self.assertIsNotNone(evidence.failure)
        self.assertEqual(set(), evidence.gui_languages)

    def test_worker_rejects_external_job_paths_before_reading_them(self):
        with patch.object(Path, "read_text", side_effect=AssertionError("Must not read external files")) as read:
            with self.assertRaises(AssertionError):
                matrix.run_worker(Path("/not-a-test-profile/job.json"))
        read.assert_not_called()


if __name__ == "__main__":
    unittest.main()
