"""Protocol 8 wire client and explicit test-only canonical assertion projection.

The engine accepts only the compact protocol. Existing scenario assertions use
canonical names locally; this module never sends their old envelopes or invents
missing provenance, request details, or game observations.
"""
import copy
import json
import re

OPS = dict(zip(
    "info state actions req history events move cell item wait rest search save quit cancel click choose select text value scroll bind_slot bind_key back reveal zoom pan untarget".split(),
    "protocol.info state.get actions.list request.get history.list events.read move.step cell.select inventory.open wait rest search game.save app.quit action.cancel ui.activate ui.choose ui.select ui.text ui.value ui.scroll ui.binding_slot ui.binding_key ui.back ui.reveal view.zoom view.pan cell.cancel".split()))
FIELDS = dict(zip(
    "ctl dir g loc rid opt alt inv entities cues nodes acts desc qty min max s rev".split(),
    "control direction gesture locator target_id option alternate inventory visible_entities visual_cues controls actions description quantity minimum maximum scope_id state_version".split()))
FIELDS.update(dict(zip(
    "mxp ht sub_name tp via shortcut prompt activity snap item_info saves saved sid src_s src_id at".split(),
    "max_experience max_hp subclass_name talent_points_available details_via shortcut_action cell_prompt continuous_activity snapshot_status inspected_item saves_during_request last_save receipt_id origin_scope_id origin_request_id occurred_at".split())))
TO_OP = {value: key for key, value in OPS.items()}
TO_FIELD = {value: key for key, value in FIELDS.items()}
DIRECTIONS = dict(zip("N NE E SE S SW W NW".split(),
                      "north northeast east southeast south southwest west northwest".split()))
OBSERVATION_FIELDS = {"scene", "hero", "map", "inventory", "visible_entities", "visual_cues", "ui", "continuous_activity", "coverage"}
QUERY_OPS = {"info", "state", "actions", "req", "history", "events"}


def is_live_operation(op):
    """Live replies may advance the client context; historical replies must not."""
    wire_op = TO_OP.get(op, op)
    return op == "action.execute" or wire_op in {"info", "state", "actions"} or wire_op in OPS.keys() - QUERY_OPS


def request(op, args=None, request_id=None, scope=None, version=None):
    """Encode a scenario request as an actual flat protocol-8 request."""
    params = dict(args or {})
    action = params.pop("action", None) if op == "action.execute" else None
    wire_op = TO_OP.get(action if action is not None else op, action or op)
    wire = {"v": 8, "id": request_id, "op": wire_op}
    if scope is not None:
        wire["s"] = scope
    if action is not None or wire_op in OPS.keys() - QUERY_OPS:
        wire["rev"] = version
    for key, value in params.items():
        if key == "direction" and isinstance(value, str):
            value = {v: k for k, v in DIRECTIONS.items()}.get(value, value)
        wire[TO_FIELD.get(key, key)] = value
    return wire


def wire_bytes(value):
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def _path(path):
    return re.sub(r"[A-Za-z_][A-Za-z_0-9]*", lambda m: FIELDS.get(m.group(), m.group()), path)


def _definition(table, index):
    assert type(index) is int and isinstance(table, list) and 0 <= index < len(table), (table, index)
    return copy.deepcopy(table[index])


def _entities(value):
    table = value.get("entity_defs", [])
    result = []
    for item in value.get("entities", []):
        if "def" not in item:
            result.append(_value(item))
            continue
        assert set(item) == {"cell", "def"}, item
        descriptor = _definition(table, item["def"])
        assert isinstance(descriptor, dict) and "cell" not in descriptor and descriptor.get("kind") in {"trap", "container", "character"}, descriptor
        result.append(_value({"cell": item["cell"], **descriptor}))
    return result


def _value(value):
    if isinstance(value, list):
        return [_value(item) for item in value]
    if not isinstance(value, dict):
        return value
    if "rows" in value and "types" in value and "w" in value:
        return _map(value)
    result = {}
    for key, child in value.items():
        if key == "entity_defs":
            continue
        if key == "entities":
            result["visible_entities"] = _entities(value)
            continue
        if key == "cues" and isinstance(child, list):
            # The outer observation alias is a map; VisualSnapshot and event payloads
            # also use the canonical key cues for their list of rendered kind/cell pairs.
            result["cues"] = _value(child)
            continue
        if key in {"text_sources", "text_diagnostics"}:
            result[key] = ({_path(field): copy.deepcopy(details) for field, details in child.items()}
                           if isinstance(child, dict) else copy.deepcopy(child))
        elif key in {"raw", "reply", "schema", "original_payload", "preserved_cells"}:
            # Diagnostic cell annotations are disclosed evidence, not an
            # alternate row encoding or a baseline to merge into the live map.
            result[key] = copy.deepcopy(child)
        elif key == "text_origins":
            result[key] = ({_path(field): copy.deepcopy(origins) for field, origins in child.items()}
                           if isinstance(child, dict) else copy.deepcopy(child))
        elif key == "op":
            result["action"] = OPS.get(child, child) if isinstance(child, str) else _value(child)
        elif key == "pres":
            if isinstance(child, dict) and isinstance(child.get("diag", []), list):
                result["translation_status"] = child.get("st")
                result["text_diagnostics"] = {_path(item["field"]): item["code"]
                                              for item in child.get("diag", [])}
            else:
                result["pres"] = copy.deepcopy(child)
        elif key == "dir":
            # Dynamic capability constraints can contain a list/map here. Only a
            # scalar direction is an enum; preserve structured values recursively.
            result["direction"] = DIRECTIONS.get(child, child) if isinstance(child, str) else _value(child)
        elif FIELDS.get(key, key) == "details_via" and isinstance(child, str):
            result["details_via"] = OPS.get(child, child)
        else:
            result[FIELDS.get(key, key)] = _value(child)
    return result


def _map(value):
    if not isinstance(value, dict) or "rows" not in value:
        return value
    width, height = value["w"], value["h"]
    environment = {}
    for cell, effects in value.get("env", {}).items():
        if type(effects) is int:
            effects = _definition(value.get("effect_defs", []), effects)
        assert isinstance(effects, list), effects
        environment[cell] = effects
    cells = []
    alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
    integer_rows = len(value["types"]) > len(alphabet)
    previous = -1
    for y, start, encoded, visibility in value["rows"]:
        assert isinstance(encoded, list) if integer_rows else isinstance(encoded, str), value
        indices = encoded if integer_rows else [alphabet.index(char) for char in encoded]
        assert indices and isinstance(visibility, str), value
        if len(visibility) == 1:
            visibility *= len(indices)
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
                   if key not in {"w", "h", "types", "rows", "env", "effect_defs"}})
    return result


def _item_defaults(item):
    """Expand documented v8 defaults, retaining unknown versus inapplicable."""
    for field, default in (("quantity", 1), ("equipped", False), ("available", True), ("type_known", True)):
        item.setdefault(field, default)
    item.setdefault("details_via", "ui.activate")
    for field, known in (("level", "level_known"), ("cursed", "curse_known")):
        # The normal wire uses three states. Protected source/partial metadata
        # may retain the original property/knowledge pair as diagnostic evidence.
        item.setdefault(known, None if field not in item else item[field] is not None)


def state(value, scope=None, version=None):
    """Expand only data present in a compact public state for old assertions."""
    value = expand_structures(value, scope, version)
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
    result.setdefault("actions", [])
    if "ui" in observation:
        observation["ui"].setdefault("modal", False)
        observation["ui"].setdefault("inspected_item", None)
    for node in observation.get("ui", {}).get("controls", []):
        node.setdefault("enabled", True)
        node.setdefault("dimmed", False)
        # Keep each node's current operation references alongside the complete
        # global action list; never invent a capability from appearance.
    result["observation"] = observation
    return result


def response(wire, op=None):
    """Decode real v8 bytes into a test-only assertion view, never a wire log."""
    if wire.get("v") != 8:
        raise AssertionError({"expected_protocol_8": wire})
    wire = expand_structures(wire)
    result = {"protocol_version": 8, "id": wire.get("id"), "scope_id": wire.get("s"),
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

# Share the production structural decoder, not its display/default projection.
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "client"))
from spdctl_client import DecodeError, expand_structures as _expand_v8


def expand_structures(value, scope=None, version=None):
    """Expand one v8 frame, then restore only its own documented bindings."""
    try:
        result = _expand_v8(value, scope, version, bindings=True)
    except DecodeError as error:
        raise AssertionError(str(error)) from error

    def bindings(data, frame_scope=None, frame_version=None):
        if not isinstance(data, dict):
            return
        own_scope = data.get("s", frame_scope)
        own_version = data.get("rev", frame_version)
        activity = data.get("activity")
        if isinstance(activity, dict):
            if own_version is not None:
                activity.setdefault("rev", own_version)
            for action in data.get("acts", []):
                if action.get("op") == "cancel":
                    for field in ("rev", "rid"):
                        if field in activity:
                            action.setdefault(field, copy.deepcopy(activity[field]))
        def receipt(value):
            if not isinstance(value, dict) or "sid" not in value:
                return
            if own_scope is not None:
                value.setdefault("s", own_scope)
            if "s" in value:
                value.setdefault("src_s", value["s"])
        receipt(data.get("saved"))
        persistence = data.get("persistence")
        if isinstance(persistence, dict):
            for item in persistence.get("saves", []):
                receipt(item)
            if type(persistence.get("saved")) is int:
                persistence["saved"] = _definition(persistence.get("saves"), persistence["saved"])
            receipt(persistence.get("saved"))
        for key in ("before", "after"):
            if isinstance(data.get(key), dict):
                bindings(data[key])  # A frozen snapshot never inherits live context.
    if "v" in result and isinstance(result.get("data"), dict):
        bindings(result["data"], result.get("s"), result.get("rev"))
    else:
        bindings(result, scope, version)
    return result
