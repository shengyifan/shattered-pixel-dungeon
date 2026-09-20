"""Experimental exact lexical encoding of same-frame control identifiers.

Canonical c<base36> strings become readable decimal integers only in control
binding positions. This is reversible spelling, not a new binding, truncation,
renumbering, or a lookup into a previous frame. All other identifiers stay intact.
"""
import copy
import re

CODEC = "decimal-controls-v1"
LITERAL = "$control_literal"
MAX_SAFE_INTEGER = 2**53 - 1
OPAQUE = {"raw", "reply", "before", "after", "history", "events", "schema",
          "outcome", "late_responses", "original_response", "source", "sources", "preserved_cells",
          "original_payload", "raw_request", "raw_bytes", "request_json", "response_json",
          "text_sources", "text_origins", "text_diagnostics", "pres", "presentation"}


def base36(number):
    if number == 0:
        return "0"
    output = ""
    while number:
        number, digit = divmod(number, 36)
        output = "0123456789abcdefghijklmnopqrstuvwxyz"[digit] + output
    return output


def _binding(value, decoding):
    if decoding:
        if type(value) is int:
            if not 0 <= value <= MAX_SAFE_INTEGER:
                raise ValueError("Invalid decimal control encoding")
            return "c" + base36(value)
        if isinstance(value, dict) and set(value) == {LITERAL}:
            return copy.deepcopy(value[LITERAL])
        return copy.deepcopy(value)
    if isinstance(value, str) and re.fullmatch(r"c(?:0|[1-9a-z][0-9a-z]*)", value):
        number = int(value[1:], 36)
        if number <= MAX_SAFE_INTEGER and "c" + base36(number) == value:
            return number
    if type(value) is int or isinstance(value, dict) and set(value) == {LITERAL}:
        return {LITERAL:copy.deepcopy(value)}
    return copy.deepcopy(value)


def _walk(value, decoding=False, node=False):
    if isinstance(value, list):
        return [_walk(child, decoding) for child in value]
    if not isinstance(value, dict):
        return copy.deepcopy(value)
    output = {}
    for key, child in value.items():
        if key in OPAQUE:
            output[key] = copy.deepcopy(child)
        elif key == "ctl" or node and key in {"id", "parent"}:
            output[key] = _binding(child, decoding)
        elif key == "nodes" and isinstance(child, list):
            nodes = []
            shapes = value.get("node_shapes")
            for raw in child:
                if isinstance(raw, dict):
                    nodes.append(_walk(raw, decoding, node=True))
                elif (isinstance(raw, list) and raw and type(raw[0]) is int
                      and isinstance(shapes, list) and 0 <= raw[0] < len(shapes)
                      and isinstance(shapes[raw[0]], list)
                      and len(shapes[raw[0]]) == len(raw)-1):
                    row = [raw[0]]
                    for field, item in zip(shapes[raw[0]], raw[1:]):
                        row.append(_binding(item, decoding) if field in {"id", "parent", "ctl"}
                                   else copy.deepcopy(item) if field in OPAQUE else _walk(item, decoding))
                    nodes.append(row)
                else:
                    nodes.append(copy.deepcopy(raw))
            output[key] = nodes
        else:
            output[key] = _walk(child, decoding)
    return output


def encode(frame):
    return {"$codec":CODEC,"frame":_walk(frame)}


def decode(packet):
    if not isinstance(packet, dict) or packet.get("$codec") != CODEC or set(packet) != {"$codec","frame"}:
        raise ValueError("Invalid decimal control packet")
    return _walk(packet["frame"], decoding=True)
