"""Offline assertions for the packaged slow-quit verifier; no JVM or game starts."""
import copy
import unittest

from slow_quit_package_smoke import assert_pending_quit, settle_finished


RID = "t3.1"


def receipt(status):
    return {"v": 9, "id": "t3.2", "s": "s1", "st": "completed",
            "data": {"id": RID, "op": "quit", "st": status, "save": [{"sid": "p9"}]}}


def settled():
    return {"controller": "settle", "rid": RID, "s": "s1", "st": "completed",
            "outcome": receipt("COMPLETED")}


def pending(reason="action_pending"):
    result = {"controller": "settle", "rid": RID, "s": "s1", "st": "pending",
              "pending_reason": reason, "stage": "receipt", "outcome": receipt("EXECUTING")}
    if reason == "response_pending":
        result["pending_request"] = {"v": 9, "id": "t3.3", "op": "req", "s": "s1", "rid": RID}
    return result


class FakeClient:
    def __init__(self, results):
        self.results = iter(results)
        self.requests = []
        self.settle_calls = 0

    def request(self, intent):
        self.requests.append(intent)
        return next(self.results)


class SlowQuitPackageAssertionsTest(unittest.TestCase):
    def test_all_soft_pending_reasons_and_exit_wait_preserve_the_completed_receipt(self):
        complete = settled()
        results = [pending(reason) for reason in ("action_pending", "budget_exhausted", "response_pending")]
        results += [{"controller": "exit", "st": "pending", "err": "CHILD_EXIT_TIMEOUT", "outcome": complete},
                    {"controller": "exit", "st": "completed", "exit_code": 0, "outcome": complete}]
        before = copy.deepcopy(results)
        client = FakeClient(results)
        self.assertIs(complete, settle_finished(client, RID))
        self.assertEqual(5, client.settle_calls)
        self.assertEqual(before, results)
        self.assertTrue(all(intent == {"op": "settle", "rid": RID, "timeout_ms": 5000}
                            for intent in client.requests))

    def test_new_response_timeout_is_not_silently_retried_as_a_soft_budget(self):
        for result in ({"controller": "settle", "st": "error", "err": "RESPONSE_TIMEOUT"},
                       {**pending(), "err": "RESPONSE_TIMEOUT"}):
            with self.subTest(result=result):
                client = FakeClient([result, settled()])
                with self.assertRaises(AssertionError):
                    settle_finished(client, RID)
                self.assertEqual(1, len(client.requests))

    def test_historical_initial_timeout_evidence_remains_allowed(self):
        result = pending("response_pending")
        result["transport_error"] = {"controller": "error", "err": "RESPONSE_TIMEOUT",
                                     "request": {"v": 9, "id": RID, "op": "quit", "s": "s1"}}
        before = copy.deepcopy(result)
        assert_pending_quit(result, RID)
        self.assertEqual(before, result)

    def test_incomplete_exit_pending_nested_settle_is_rejected(self):
        incomplete = settled()
        del incomplete["st"]
        with self.assertRaises(AssertionError):
            assert_pending_quit({"controller": "exit", "st": "pending", "err": "CHILD_EXIT_TIMEOUT",
                                 "outcome": incomplete}, RID)

    def test_pending_query_identity_and_stage_are_checked(self):
        for field, value in (("op", "state"), ("rid", "t3.9"), ("s", "s2"), ("id", RID)):
            with self.subTest(field=field):
                result = pending("response_pending")
                result["pending_request"][field] = value
                with self.assertRaises(AssertionError):
                    assert_pending_quit(result, RID)
        wrong_stage = pending()
        wrong_stage["stage"] = "observation"
        with self.assertRaises(AssertionError):
            assert_pending_quit(wrong_stage, RID)


if __name__ == "__main__":
    unittest.main()
