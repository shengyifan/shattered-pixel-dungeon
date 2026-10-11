"""Current protocol-9 fixture helpers backed by the production public decoder.

Requests and decoded replies retain their native wire field and operation names.
A frame view adds only that response's own scope/revision to its decoded body;
transport logs always retain the original bytes independently.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "client"))
from spdctl_client import (OBSERVATION_FIELDS, decode_wire_response,
                           expand_structures as _expand_structures)

QUERY_OPS = {"info", "state", "actions", "req", "history", "events"}
ACTION_OPS = {"move", "cell", "item", "wait", "rest", "search", "save", "quit",
              "cancel", "click", "choose", "select", "text", "value", "scroll",
              "bind_slot", "bind_key", "back", "reveal", "zoom", "pan", "untarget"}


def is_live_operation(op):
    """Historical replies must never advance a live fixture client binding."""
    return op in {"info", "state", "actions"} | ACTION_OPS


def request(op, args=None, request_id=None, scope=None, version=None):
    """Construct a flat current request without accepting historical aliases."""
    if op not in QUERY_OPS | ACTION_OPS:
        raise ValueError("Not a current protocol-9 operation: " + str(op))
    params = dict(args or {})
    if {"v", "id", "op", "s", "rev"} & params.keys():
        raise ValueError("Request identity and binding belong to explicit arguments")
    wire = {"v": 9, "id": request_id, "op": op}
    if scope is not None:
        wire["s"] = scope
    if op in ACTION_OPS:
        wire["rev"] = version
    wire.update(params)
    return wire


def wire_bytes(value):
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def response(wire, op=None):
    """Validate and decode a response using the exact production implementation."""
    return decode_wire_response(wire).frame


def frame_data(frame):
    """Return this decoded frame's body with only its own explicit bindings."""
    data = dict(frame.get("data") or {})
    for field in ("s", "rev"):
        if field in frame:
            data[field] = frame[field]
    return data


def expand_structures(value, scope=None, version=None):
    """Production structural expansion, including each frame's own bindings."""
    return _expand_structures(value, scope, version, bindings=True)


def pages(client, op, scope=None, after=0, limit=100):
    """Read one bounded current history/events traversal without rebinding it."""
    if op not in {"history", "events"}:
        raise ValueError("Only history and events are paginated")
    until = None
    while True:
        args = {"after": after, "limit": limit}
        if until is not None:
            args["until"] = until
        reply = client.request(op, args, scope=scope)
        assert "err" not in reply, reply
        page = reply["data"]
        if until is None:
            until = page["until"]
        else:
            assert page["until"] == until, "A paginated traversal changed its fixed upper bound"
        yield page["items"]
        if page["end"]:
            assert page["next"] is None, "Terminal page retained a continuation cursor"
            return
        next_cursor = page["next"]
        assert type(next_cursor) is int and after < next_cursor <= until, "Pagination did not advance within its bound"
        after = next_cursor
