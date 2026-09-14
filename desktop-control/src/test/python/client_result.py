"""Derived client outcomes; these objects are never protocol replies or raw logs."""
from dataclasses import dataclass
import time


PENDING = {"RECEIVED", "EXECUTING"}
SETTLED = {"COMPLETED", "AWAITING_INPUT", "INTERRUPTED"}


@dataclass
class ActionResult:
    initial_response: dict
    request_scope: str = None
    receipt_response: dict = None
    discovery_response: dict = None
    observation_response: dict = None
    failure: object = None

    @property
    def request_id(self):
        return self.initial_response.get("id")

    @property
    def scope_id(self):
        return self.request_scope or self.initial_response.get("scope_id")

    @property
    def outcome(self):
        return (self.receipt_response.get("result", {}) if self.receipt_response is not None
                else self.initial_response)

    @property
    def status(self):
        return self.outcome.get("status", "").lower()

    @property
    def ok(self):
        if self.failure is not None or not self.initial_response.get("ok"):
            return False
        if self.receipt_response is not None:
            return (self.receipt_response.get("ok", False)
                    and self.outcome.get("status") in SETTLED
                    and not self.outcome.get("error") and not self.outcome.get("err"))
        return self.status in {"completed", "awaiting_input", "interrupted"}

    @property
    def observation(self):
        return None if self.observation_response is None else self.observation_response.get("result")

    def require_success(self):
        if isinstance(self.failure, BaseException):
            raise self.failure
        assert self.ok, self
        return self


def settle_action(client, initial, action, timeout=40, poll_interval=.05, on_pending=None):
    """Resolve one dispatched action without replay or historical full replies.

    on_pending receives this derived result and may observe/cancel the original
    continuous activity. It is not called for uncertified finite resolving or
    cancelling states, and must never replay the originating action.
    """
    sent = getattr(client, "last_wire_request", None)
    origin = sent.get("s") if isinstance(sent, dict) and sent.get("id") == initial.get("id") else None
    result = ActionResult(initial, request_scope=origin)
    client.last_action_result = result
    if not initial.get("ok"):
        return result
    if initial.get("status") != "in_progress":
        result.observation_response = initial
        return result
    initial_state = initial.get("result") or {}
    # Finite operations that exceed the first-response timeout return the last
    # scope without a certified revision. Their terminal state may be in another
    # scope. Continuous activity already supplies a current scope/activity token.
    discover_scope = (initial_state.get("phase") in {"resolving", "cancelling"}
                      and not initial_state.get("state_version"))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            receipt = client.request("request.get", {"target_id": result.request_id}, scope=result.scope_id)
        except Exception as error:
            result.failure = error
            return result
        result.receipt_response = receipt
        if not receipt.get("ok"):
            result.failure = receipt
            return result
        outcome = result.outcome
        if outcome.get("error") or outcome.get("err"):
            result.failure = receipt
            return result
        status = outcome.get("status")
        if status in SETTLED:
            # A successful quit terminates the stream. Preserve the receipt and
            # let the caller wait for the process instead of querying it again.
            if action not in {"app.quit", "quit"}:
                if discover_scope:
                    try:
                        discovered = client.request("protocol.info")
                    except Exception as error:
                        result.failure = error
                        return result
                    result.discovery_response = discovered
                    if not discovered.get("ok"):
                        result.failure = discovered
                        return result
                try:
                    observed = client.request("state.get")
                except Exception as error:
                    result.failure = error
                    return result
                result.observation_response = observed
                if not observed.get("ok"):
                    result.failure = observed
            return result
        if status not in PENDING:
            result.failure = receipt
            return result
        if on_pending is not None and not discover_scope:
            try:
                on_pending(result)
            except Exception as error:
                result.failure = error
                return result
        if poll_interval:
            time.sleep(poll_interval)
    result.failure = TimeoutError("Action did not settle: " + str(result.request_id))
    return result
