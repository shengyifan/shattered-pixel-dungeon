"""Protocol 6 wire client and explicit test-only canonical assertion projection.

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
    """Encode a scenario request as an actual flat protocol-6 request."""
    params = dict(args or {})
    action = params.pop("action", None) if op == "action.execute" else None
    wire_op = TO_OP.get(action if action is not None else op, action or op)
    wire = {"v": 6, "id": request_id, "op": wire_op}
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
    """Expand documented v6 defaults, retaining unknown versus inapplicable."""
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
    actions = result.setdefault("actions", [])
    explicit_actions = list(actions)
    if "ui" in observation:
        observation["ui"].setdefault("modal", False)
        observation["ui"].setdefault("inspected_item", None)
    for node in observation.get("ui", {}).get("controls", []):
        node.setdefault("enabled", True)
        node.setdefault("dimmed", False)
        gestures_explicit = "gestures" in node
        for descriptor in node.pop("ops", []):
            # CLI 6.1 retains the complete original action list and also attaches
            # node capabilities. Older v6 frames may contain only the latter.
            if any(action.get("control") == node.get("id") and action.get("action") == descriptor.get("action")
                   for action in explicit_actions):
                continue
            entry = {key: copy.deepcopy(child) for key, child in node.items()
                     if key in {"gestures", "options", "minimum", "maximum", "step", "max_length", "multiline", "slots", "keys", "binding"}}
            entry.update(descriptor)
            if entry.get("action") == "ui.activate":
                entry.setdefault("gestures", ["click"])
                if not gestures_explicit:
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
                        entry.setdefault(field, {}).setdefault("label", copy.deepcopy(node[field][label_field]))
            actions.append(entry)
    result["observation"] = observation
    return result


def response(wire, op=None):
    """Decode real v6 bytes into a test-only assertion view, never a wire log."""
    if wire.get("v") != 6:
        raise AssertionError({"expected_protocol_6": wire})
    wire = expand_structures(wire)
    result = {"protocol_version": 6, "id": wire.get("id"), "scope_id": wire.get("s"),
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

# The structural decoder operates on one wire frame, before canonical test aliases.
# It keeps raw/reply/source ASTs opaque and never consults an earlier response.
_OPAQUE = {"raw", "reply", "schema", "raw_request", "raw_bytes", "request_json",
           "response_json", "original_payload", "text_sources", "text_origins",
           "pres", "preserved_cells"}
_NO_CAPS = {"a", "an", "and", "of", "by", "to", "the", "x", "for"}


def _title_case(text):
    import unicodedata
    words, start = [], 0
    for index, char in enumerate(text):
        if unicodedata.category(char) == "Zs":
            words.append(text[start:index + 1])
            start = index + 1
    if start < len(text):
        words.append(text[start:])
    capitalize = lambda word: word[:1].upper() + word[1:]
    value = "".join(word if re.sub(r":|[0-9]", "", word.strip("".join(map(chr, range(33)))).lower()) in _NO_CAPS
                    else capitalize(word) for word in words)
    return capitalize(value)


def expand_structures(value, scope=None, version=None, _inventory=None):
    """Expand v6 same-frame UI tables, item labels and binding inheritance."""
    inventory = {} if _inventory is None else _inventory
    if isinstance(value, list):
        return [expand_structures(child, scope, version, inventory) for child in value]
    if not isinstance(value, dict):
        return value
    scope, version = value.get("s", scope), value.get("rev", version)
    if isinstance(value.get("inv"), list):
        inventory, ambiguous = {}, set()
        for item in value["inv"]:
            if not isinstance(item, dict) or not isinstance(item.get("loc"), str):
                continue
            if item["loc"] in inventory:
                ambiguous.add(item["loc"])
            inventory[item["loc"]] = item
        for locator in ambiguous:
            del inventory[locator]
    result = {}
    for key, child in value.items():
        if isinstance(value.get("nodes"), list) and key in {"node_shapes", "op_defs"}:
            continue
        if key == "nodes" and isinstance(child, list):
            nodes = []
            for raw in child:
                if isinstance(raw, list):
                    assert raw, "Empty UI row"
                    shape = _definition(value.get("node_shapes"), raw[0])
                    assert isinstance(shape, list) and len(shape) == len(raw) - 1
                    assert all(isinstance(field, str) for field in shape) and len(set(shape)) == len(shape)
                    node = dict(zip(shape, raw[1:]))
                else:
                    assert isinstance(raw, dict), raw
                    node = copy.deepcopy(raw)
                if type(node.get("ops")) is int:
                    node["ops"] = _definition(value.get("op_defs"), node["ops"])
                    assert isinstance(node["ops"], list), node
                elif "ops" in node:
                    assert isinstance(node["ops"], list), node
                if type(node.get("label")) is int:
                    name = inventory.get(node.get("loc"), {}).get("name")
                    assert isinstance(name, str) and node["label"] in (0, 1), node
                    node["label"] = name if node["label"] == 0 else _title_case(name)
                elif "label" in node:
                    assert node["label"] is None or isinstance(node["label"], str), node
                nodes.append(expand_structures(node, scope, version, inventory))
            result[key] = nodes
        elif key in _OPAQUE:
            result[key] = copy.deepcopy(child)
        else:
            result[key] = expand_structures(child, scope, version, inventory)
    activity = result.get("activity")
    if isinstance(activity, dict):
        if version is not None:
            activity.setdefault("rev", version)
        for action in result.get("acts", []):
            if isinstance(action, dict) and action.get("op") == "cancel":
                for field in ("rev", "rid"):
                    if field in activity:
                        action.setdefault(field, activity[field])
    def receipt_defaults(receipt):
        if not isinstance(receipt, dict) or "sid" not in receipt:
            return
        if scope is not None:
            receipt.setdefault("s", scope)
        if "s" in receipt:
            receipt.setdefault("src_s", receipt["s"])
    receipt_defaults(result.get("saved"))
    persistence = result.get("persistence")
    if isinstance(persistence, dict):
        for receipt in persistence.get("saves", []):
            receipt_defaults(receipt)
        saved = persistence.get("saved")
        if type(saved) is int:
            persistence["saved"] = _definition(persistence.get("saves"), saved)
        else:
            receipt_defaults(saved)
    return result
