# Frozen protocol-4 public transcript adapter; offline benchmark only. Never used by a running v5 client.
"""Protocol 4 wire client and explicit test-only canonical assertion projection.

The engine accepts only the compact protocol. Existing scenario assertions use
canonical names locally; this module never sends their old envelopes or invents
missing provenance, request details, or game observations.
"""
import copy
import json

OPS = dict(zip(
    "info state actions req history events move cell item wait rest search save quit cancel click choose select text value scroll bind_slot bind_key back reveal zoom pan untarget".split(),
    "protocol.info state.get actions.list request.get history.list events.read move.step cell.select inventory.open wait rest search game.save app.quit action.cancel ui.activate ui.choose ui.select ui.text ui.value ui.scroll ui.binding_slot ui.binding_key ui.back ui.reveal view.zoom view.pan cell.cancel".split()))
FIELDS = dict(zip(
    "ctl dir g loc rid opt alt inv entities cues nodes acts desc qty min max s rev".split(),
    "control direction gesture locator target_id option alternate inventory visible_entities visual_cues controls actions description quantity minimum maximum scope_id state_version".split()))
TO_OP = {value: key for key, value in OPS.items()}
TO_FIELD = {value: key for key, value in FIELDS.items()}
DIRECTIONS = dict(zip("N NE E SE S SW W NW".split(),
                      "north northeast east southeast south southwest west northwest".split()))
OBSERVATION_FIELDS = {"scene", "hero", "map", "inventory", "visible_entities", "visual_cues", "ui", "continuous_activity"}
QUERY_OPS = {"info", "state", "actions", "req", "history", "events"}


def is_live_operation(op):
    """Live replies may advance the client context; historical replies must not."""
    wire_op = TO_OP.get(op, op)
    return op == "action.execute" or wire_op in {"info", "state", "actions"} or wire_op in OPS.keys() - QUERY_OPS


def request(op, args=None, request_id=None, scope=None, version=None):
    """Encode a scenario request as an actual flat protocol-4 request."""
    params = dict(args or {})
    action = params.pop("action", None) if op == "action.execute" else None
    wire_op = TO_OP.get(action if action is not None else op, action or op)
    wire = {"v": 4, "id": request_id, "op": wire_op}
    if scope is not None:
        wire["s"] = scope
    if action is not None or wire_op in OPS.keys() - QUERY_OPS:
        wire["rev"] = version
    for key, value in params.items():
        if key == "direction":
            value = {v: k for k, v in DIRECTIONS.items()}.get(value, value)
        wire[TO_FIELD.get(key, key)] = value
    return wire


def wire_bytes(value):
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def _value(value):
    if isinstance(value, list):
        return [_value(item) for item in value]
    if not isinstance(value, dict):
        return value
    if "rows" in value and "types" in value and "w" in value:
        return _map(value)
    result = {}
    for key, child in value.items():
        if key in {"text_sources", "text_diagnostics"}:
            result[key] = {FIELDS.get(field, field): copy.deepcopy(details) for field, details in child.items()}
        elif key in {"raw", "preserved_cells"}:
            # Diagnostic cell annotations are disclosed evidence, not an
            # alternate row encoding or a baseline to merge into the live map.
            result[key] = copy.deepcopy(child)
        elif key == "text_origins":
            result[key] = {FIELDS.get(field, field): copy.deepcopy(origins) for field, origins in child.items()}
        elif key == "op":
            result["action"] = OPS.get(child, child)
        elif key == "pres":
            result["translation_status"] = child.get("st")
            result["text_diagnostics"] = {FIELDS.get(item["field"], item["field"]): item["code"]
                                          for item in child.get("diag", [])}
        elif key == "dir":
            result["direction"] = DIRECTIONS.get(child, child)
        elif key == "details_via" and isinstance(child, str):
            result[key] = OPS.get(child, child)
        else:
            result[FIELDS.get(key, key)] = _value(child)
    return result


def _map(value):
    if not isinstance(value, dict) or "rows" not in value:
        return value
    width, height = value["w"], value["h"]
    environment = value.get("env", {})
    cells = []
    alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
    integer_rows = len(value["types"]) > len(alphabet)
    previous = -1
    for y, start, encoded, visibility in value["rows"]:
        assert isinstance(encoded, list) if integer_rows else isinstance(encoded, str), value
        indices = encoded if integer_rows else [alphabet.index(char) for char in encoded]
        assert len(indices) == len(visibility) and 0 <= y < height and 0 <= start < width
        assert start + len(indices) <= width
        for offset, (index, vis) in enumerate(zip(indices, visibility)):
            cell = y * width + start + offset
            assert cell > previous and 0 <= index < len(value["types"]), value
            previous = cell
            tile = _value(value["types"][index])
            tile.update(cell=cell, x=cell % width, y=cell // width,
                        visibility={"v": "visible", "s": "visited", "m": "mapped"}[vis])
            tile["environment"] = _value(environment.get(str(cell), []))
            cells.append(tile)
    result = {"width": width, "height": height, "cells": cells, "unknown": "omitted"}
    result.update({key: copy.deepcopy(child) if key == "preserved_cells" else _value(child)
                   for key, child in value.items()
                   if key not in {"w", "h", "types", "rows", "env"}})
    return result


def _item_defaults(item):
    """Expand documented v4 defaults, retaining unknown versus inapplicable."""
    for field, default in (("quantity", 1), ("equipped", False), ("available", True), ("type_known", True)):
        item.setdefault(field, default)
    for field, known in (("level", "level_known"), ("cursed", "curse_known")):
        # The normal wire uses three states. Protected source/partial metadata
        # may retain the original property/knowledge pair as diagnostic evidence.
        item.setdefault(known, None if field not in item else item[field] is not None)


def state(value, scope=None, version=None):
    """Expand only data present in a compact public state for old assertions."""
    result = _value(value)
    if not isinstance(result, dict):
        return result
    if scope is not None:
        result["scope_id"] = scope
    if version is not None:
        result["state_version"] = version
    if not (OBSERVATION_FIELDS & result.keys()):
        return result
    observation = {key: result.pop(key) for key in list(result) if key in OBSERVATION_FIELDS}
    if "run_outcome" in result:
        # State.result() exposes the same public outcome at both levels. The
        # compact wire deduplicates it; both views derive from that disclosed value.
        observation["run_outcome"] = copy.deepcopy(result["run_outcome"])
    if "map" in observation:
        observation["map"] = _map(value["map"])
    for item in observation.get("inventory", []):
        _item_defaults(item)
    for entity in observation.get("visible_entities", []):
        if isinstance(entity.get("item"), dict):
            _item_defaults(entity["item"])
    actions = result.setdefault("actions", [])
    for node in observation.get("ui", {}).get("controls", []):
        node.setdefault("enabled", True)
        node.setdefault("dimmed", False)
        for descriptor in node.pop("ops", []):
            entry = {key: copy.deepcopy(child) for key, child in node.items()
                     if key in {"gestures", "options", "minimum", "maximum", "step", "max_length", "multiline", "slots", "keys", "binding"}}
            entry.update(descriptor)
            if entry.get("action") == "ui.activate":
                entry.setdefault("gestures", ["click"])
                node.setdefault("gestures", []).extend(entry["gestures"])
            entry["control"] = node["id"]
            if "binding_slots" in node:
                entry.setdefault("slots", copy.deepcopy(node["binding_slots"]))
            if "minimum" in node and "maximum" in node:
                entry.setdefault("range", [node["minimum"], node["maximum"]])
            label_field = "label" if "label" in node else "text"
            if label_field in node:
                entry.setdefault("label", node[label_field])
                for field in ("text_sources", "text_diagnostics", "text_origins"):
                    if label_field in node.get(field, {}):
                        entry.setdefault(field, {})["label"] = copy.deepcopy(node[field][label_field])
            actions.append(entry)
    result["observation"] = observation
    return result


def response(wire, op=None):
    """Decode real v4 bytes into a test-only assertion view, never a wire log."""
    if wire.get("v") != 4:
        raise AssertionError({"expected_protocol_4": wire})
    result = {"protocol_version": 4, "id": wire.get("id"), "scope_id": wire.get("s"),
              "ok": "err" not in wire}
    if "st" in wire:
        result["status"] = wire["st"]
    if "pres" in wire:
        result["presentation"] = copy.deepcopy(wire["pres"])
    if "err" in wire:
        result["error"] = {"code": wire["err"]} if isinstance(wire["err"], str) else copy.deepcopy(wire["err"])
        if "data" in wire:
            result["result"] = state(wire["data"], wire.get("s"), wire.get("rev"))
        return result
    data = wire.get("data", {})
    if op in {"history.list", "events.read", "history", "events"} and isinstance(data, dict) and "items" in data:
        result["page"] = {key: data[key] for key in ("next", "end", "until")}
        result["result"] = _value(data["items"])
        if op in {"history.list", "history"}:
            for item, decoded in zip(data["items"], result["result"]):
                if isinstance(item, dict) and "op" in item:
                    decoded["op"] = item["op"]
                    decoded.pop("action", None)
    elif op in {"request.get", "req"}:
        decoded = _value(data)
        if "op" in data:
            decoded["op"] = OPS.get(data["op"], data["op"])
            decoded.pop("action", None)
        if "st" in data:
            decoded["status"] = data["st"]
            decoded.pop("st", None)
        if "reply" in data:
            decoded["response"] = response(data["reply"])
            decoded.pop("reply", None)
        for field in ("before", "after"):
            if field in data:
                decoded[field + "_snapshot"] = _value(data[field])
                decoded.pop(field, None)
        result["result"] = decoded
    else:
        result["result"] = state(data, wire.get("s"), wire.get("rev"))
    return result


def pages(client, op, scope=None, after=0, limit=100):
    """Read one bounded history/events traversal; each invocation gets a fresh bound."""
    until = None
    while True:
        args = {"after": after, "limit": limit}
        if until is not None:
            args["until"] = until
        reply = client.request(op, args, scope=scope)
        assert reply.get("ok"), reply
        page = reply["page"]
        if until is None:
            until = page["until"]
        else:
            assert page["until"] == until, "A paginated traversal changed its fixed upper bound"
        yield reply["result"]
        if page["end"]:
            assert page["next"] is None, "Terminal page retained a continuation cursor"
            return
        next_cursor = page["next"]
        assert isinstance(next_cursor, int) and after < next_cursor <= until, "Pagination did not advance within its bound"
        after = next_cursor
