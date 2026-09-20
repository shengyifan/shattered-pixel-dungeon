"""Strict, reusable decoders for ``spdctl`` protocol-6 client output.

This module is deliberately transport- and policy-free.  It does not launch the
game, allocate request IDs, retry operations, replace revisions, or decide which
game action to take.  It only:

* validates and expands one protocol-6 wire response using that response's own
  dictionaries and scoped defaults;
* separates controller wrappers into their response, outcome, observation,
  discovery, diagnostic, and late-response parts; and
* validates a proposed controller intent against one decoded current frame
  without changing the intent.

The original input is retained on every returned object.  Unknown fields,
explicit ``None``/``False``/``0`` values, text/source metadata, and literal labels
are copied rather than normalized away.
"""

from __future__ import annotations

import copy
import re
import unicodedata
from dataclasses import dataclass
from typing import Any, Dict, Mapping, Optional, Sequence, Tuple


PROTOCOL_VERSION = 6
TILE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
WIRE_STATUSES = {"completed", "awaiting_input", "in_progress", "interrupted"}
OBSERVATION_FIELDS = {
    "phase", "scene", "hero", "inv", "map", "entities", "ui", "acts",
    "activity", "cues", "run_outcome",
}
NODE_OPERATIONS = {
    "click", "choose", "select", "text", "value", "scroll",
    "bind_slot", "bind_key",
}
GLOBAL_OPERATIONS = {
    "move", "cell", "item", "wait", "rest", "search", "save", "quit",
    "cancel", "back", "reveal", "zoom", "pan", "untarget",
}
QUERY_OPERATIONS = {"info", "state", "actions", "req", "history", "events", "settle"}
DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"}

# These values are evidence or nested historical payloads, not another live
# compact frame to merge into the containing response.
_OPAQUE_FIELDS = {
    "raw", "reply", "before", "after", "schema", "original_payload",
    "raw_request", "raw_bytes", "request_json", "response_json",
    "text_sources", "text_origins", "text_diagnostics", "pres",
    "preserved_cells",
}
_NO_TITLE_CAPS = {"a", "an", "and", "of", "by", "to", "the", "x", "for"}


class DecodeError(ValueError):
    """A public response is structurally invalid or internally inconsistent."""


class IntentError(ValueError):
    """A proposed intent is not mechanically bound to the supplied frame."""


class ClientResponseError(RuntimeError):
    """Raised only when a caller explicitly requires an error-free result."""

    def __init__(self, problems: Sequence["ClientProblem"]):
        self.problems = tuple(problems)
        super().__init__("; ".join(f"{p.stage}: {p.code}" for p in self.problems))


def _copy(value: Any) -> Any:
    return copy.deepcopy(value)


def _dict(value: Any, path: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise DecodeError(f"{path} must be an object")
    return value


def _list(value: Any, path: str) -> Sequence[Any]:
    if not isinstance(value, list):
        raise DecodeError(f"{path} must be an array")
    return value


def _integer(value: Any, path: str) -> int:
    if type(value) is not int:
        raise DecodeError(f"{path} must be an integer")
    return value


def _definition(table: Any, index: Any, path: str) -> Any:
    index = _integer(index, path)
    if not isinstance(table, list) or index < 0 or index >= len(table):
        raise DecodeError(f"{path} references a missing same-frame definition")
    return _copy(table[index])


def _generic(value: Any, path: str = "$") -> Any:
    if isinstance(value, list):
        return [_generic(child, f"{path}[{index}]") for index, child in enumerate(value)]
    if not isinstance(value, Mapping):
        return _copy(value)
    result: Dict[str, Any] = {}
    for key, child in value.items():
        if key in _OPAQUE_FIELDS:
            result[key] = _copy(child)
        else:
            result[key] = _generic(child, f"{path}.{key}")
    return result


def _title_case(text: str) -> str:
    """Match the game's ordinary resource title casing for label:1 only."""
    words, start = [], 0
    for index, char in enumerate(text):
        if unicodedata.category(char) == "Zs":
            words.append(text[start:index + 1])
            start = index + 1
    if start < len(text):
        words.append(text[start:])

    def capitalize(word: str) -> str:
        return word[:1].upper() + word[1:]

    value = "".join(
        word
        if re.sub(r":|[0-9]", "", word.strip("".join(map(chr, range(33)))).lower())
        in _NO_TITLE_CAPS
        else capitalize(word)
        for word in words
    )
    return capitalize(value)


def _item(value: Any, path: str) -> Dict[str, Any]:
    item = dict(_generic(_dict(value, path), path))
    for field, default in (
        ("qty", 1), ("equipped", False), ("available", True),
        ("type_known", True), ("via", "click"),
    ):
        if field not in item:
            item[field] = _copy(default)
    return item


def _inventory(value: Any, path: str) -> Tuple[list, Dict[str, Mapping[str, Any]], set]:
    decoded = [_item(item, f"{path}[{index}]")
               for index, item in enumerate(_list(value, path))]
    by_locator: Dict[str, Mapping[str, Any]] = {}
    ambiguous = set()
    for item in decoded:
        locator = item.get("loc")
        if not isinstance(locator, str):
            continue
        if locator in by_locator:
            ambiguous.add(locator)
        by_locator[locator] = item
    for locator in ambiguous:
        by_locator.pop(locator, None)
    return decoded, by_locator, ambiguous


def _decode_operations(value: Any, definitions: Any, path: str) -> Any:
    if value is None:
        return None
    if type(value) is int:
        value = _definition(definitions, value, path)
    if not isinstance(value, list):
        raise DecodeError(f"{path} must be an operation array or definition index")
    result = []
    for index, operation in enumerate(value):
        operation = dict(_generic(_dict(operation, f"{path}[{index}]"), f"{path}[{index}]"))
        if not isinstance(operation.get("op"), str) or not operation["op"]:
            raise DecodeError(f"{path}[{index}].op must be a non-empty string")
        result.append(operation)
    return result


def _ui(value: Any, inventory: Mapping[str, Mapping[str, Any]], ambiguous: set,
        path: str) -> Dict[str, Any]:
    source = _dict(value, path)
    result = dict(_generic(source, path))
    shapes = source.get("node_shapes")
    definitions = source.get("op_defs")
    if "nodes" in source:
        nodes = []
        for row_index, raw in enumerate(_list(source["nodes"], f"{path}.nodes")):
            row_path = f"{path}.nodes[{row_index}]"
            if isinstance(raw, list):
                if not raw:
                    raise DecodeError(f"{row_path} is an empty packed row")
                shape = _definition(shapes, raw[0], f"{row_path}[0]")
                if not isinstance(shape, list) or not all(isinstance(field, str) for field in shape):
                    raise DecodeError(f"{row_path} shape must contain field names")
                if len(set(shape)) != len(shape):
                    raise DecodeError(f"{row_path} shape contains duplicate fields")
                if len(raw) != len(shape) + 1:
                    raise DecodeError(f"{row_path} has the wrong number of values")
                # raw[0] is exclusively a shape-table index.  It is never a ctl/id.
                node = dict(zip(shape, _copy(raw[1:])))
            elif isinstance(raw, Mapping):
                node = dict(_generic(raw, row_path))
            else:
                raise DecodeError(f"{row_path} must be an object or packed row")

            if "ops" in node:
                node["ops"] = _decode_operations(node["ops"], definitions, f"{row_path}.ops")

            if "label" in node:
                label = node["label"]
                if type(label) is int:
                    if label not in (0, 1):
                        raise DecodeError(f"{row_path}.label has an invalid item-label mode")
                    locator = node.get("loc")
                    if not isinstance(locator, str):
                        raise DecodeError(f"{row_path}.label has no string loc binding")
                    if locator in ambiguous or locator not in inventory:
                        raise DecodeError(f"{row_path}.label has no unique same-frame loc binding")
                    name = inventory[locator].get("name")
                    if not isinstance(name, str):
                        raise DecodeError(f"{row_path}.label binding has no string item name")
                    node["label"] = name if label == 0 else _title_case(name)
                elif label is not None and not isinstance(label, str):
                    raise DecodeError(f"{row_path}.label must be literal text, null, or 0/1")

            if "enabled" not in node:
                node["enabled"] = True
            if "dimmed" not in node:
                node["dimmed"] = False
            nodes.append(node)
        result["nodes"] = nodes

    if "modal" not in result:
        result["modal"] = False
    if "item_info" not in result:
        result["item_info"] = None
    return result


def _environment(value: Any, effect_defs: Any, path: str) -> list:
    if type(value) is int:
        value = _definition(effect_defs, value, path)
    if not isinstance(value, list):
        raise DecodeError(f"{path} must be an effect array or definition index")
    return [_generic(effect, f"{path}[{index}]") for index, effect in enumerate(value)]


def _map(value: Any, path: str) -> Dict[str, Any]:
    source = _dict(value, path)
    width = _integer(source.get("w"), f"{path}.w")
    height = _integer(source.get("h"), f"{path}.h")
    if width <= 0 or height <= 0:
        raise DecodeError(f"{path} dimensions must be positive")
    types = _list(source.get("types"), f"{path}.types")
    rows = _list(source.get("rows"), f"{path}.rows")
    effect_defs = source.get("effect_defs", [])
    if not isinstance(effect_defs, list):
        raise DecodeError(f"{path}.effect_defs must be an array")
    env = source.get("env", {})
    if not isinstance(env, Mapping):
        raise DecodeError(f"{path}.env must be an object")
    decoded_environment: Dict[int, list] = {}
    for raw_cell, effects in env.items():
        if not isinstance(raw_cell, str) or not raw_cell.isdigit():
            raise DecodeError(f"{path}.env keys must be decimal cell strings")
        cell = int(raw_cell)
        if not 0 <= cell < width * height:
            raise DecodeError(f"{path}.env contains an out-of-bounds cell")
        decoded_environment[cell] = _environment(effects, effect_defs, f"{path}.env.{raw_cell}")

    integer_rows = len(types) > len(TILE_ALPHABET)
    cells = []
    seen_cells = set()
    previous = -1
    for row_index, raw_row in enumerate(rows):
        row_path = f"{path}.rows[{row_index}]"
        if not isinstance(raw_row, list) or len(raw_row) != 4:
            raise DecodeError(f"{row_path} must be [y,x_start,tiles,visibility]")
        y = _integer(raw_row[0], f"{row_path}[0]")
        start = _integer(raw_row[1], f"{row_path}[1]")
        encoded, visibility = raw_row[2], raw_row[3]
        if not 0 <= y < height or not 0 <= start < width:
            raise DecodeError(f"{row_path} starts outside the map")
        if integer_rows:
            if not isinstance(encoded, list):
                raise DecodeError(f"{row_path} must use integer tiles with more than 64 types")
            indices = [_integer(index, f"{row_path}[2]") for index in encoded]
        else:
            if not isinstance(encoded, str):
                raise DecodeError(f"{row_path} must use alphabet tiles with at most 64 types")
            try:
                indices = [TILE_ALPHABET.index(character) for character in encoded]
            except ValueError as error:
                raise DecodeError(f"{row_path} contains an invalid tile character") from error
        if not indices:
            raise DecodeError(f"{row_path} must not be empty")
        if start + len(indices) > width:
            raise DecodeError(f"{row_path} crosses the map width")
        if not isinstance(visibility, str):
            raise DecodeError(f"{row_path} visibility must be a string")
        if len(visibility) == 1:
            visibility = visibility * len(indices)
        if len(visibility) != len(indices) or any(flag not in "vsm" for flag in visibility):
            raise DecodeError(f"{row_path} visibility does not match its tiles")

        for offset, (type_index, flag) in enumerate(zip(indices, visibility)):
            if not 0 <= type_index < len(types):
                raise DecodeError(f"{row_path} references a missing same-frame terrain type")
            cell = y * width + start + offset
            if cell <= previous:
                raise DecodeError(f"{row_path} overlaps or is not in cell order")
            previous = cell
            descriptor = dict(_generic(_dict(types[type_index], f"{path}.types[{type_index}]"),
                                       f"{path}.types[{type_index}]"))
            if any(field in descriptor for field in ("cell", "x", "y", "visibility", "environment")):
                raise DecodeError(f"{path}.types[{type_index}] contains structural cell fields")
            descriptor.update({
                "cell": cell,
                "x": cell % width,
                "y": cell // width,
                "visibility": {"v": "visible", "s": "visited", "m": "mapped"}[flag],
                "environment": _copy(decoded_environment.get(cell, [])),
            })
            cells.append(descriptor)
            seen_cells.add(cell)

    unknown_environment = set(decoded_environment) - seen_cells
    if unknown_environment:
        raise DecodeError(f"{path}.env refers to cells omitted from rows: {sorted(unknown_environment)}")

    result = dict(_generic(source, path))
    # ``cells`` is not a protocol-6 compact field today.  Preserve it if a
    # future frame supplies that unknown field instead of overwriting it.
    result["decoded_cells" if "cells" in source else "cells"] = cells
    return result


def _entities(data: Mapping[str, Any], path: str) -> list:
    definitions = data.get("entity_defs", [])
    result = []
    for index, raw in enumerate(_list(data.get("entities", []), path)):
        item_path = f"{path}[{index}]"
        item = _dict(raw, item_path)
        if "def" in item:
            if set(item) != {"cell", "def"}:
                raise DecodeError(f"{item_path} reference must contain exactly cell and def")
            descriptor = _definition(definitions, item["def"], f"{item_path}.def")
            descriptor = dict(_generic(_dict(descriptor, f"{item_path}.def"), f"{item_path}.def"))
            kind = descriptor.get("kind")
            if "cell" in descriptor or not isinstance(kind, str) or kind not in {"trap", "container", "character"}:
                raise DecodeError(f"{item_path}.def is not a shareable entity descriptor")
            expanded = {"cell": _integer(item.get("cell"), f"{item_path}.cell"), **descriptor}
        else:
            expanded = dict(_generic(item, item_path))
        if isinstance(expanded.get("item"), Mapping):
            expanded["item"] = _item(expanded["item"], f"{item_path}.item")
        result.append(expanded)
    return result


def _receipt_defaults(receipt: Any, scope: Optional[str], path: str) -> Any:
    if not isinstance(receipt, Mapping):
        return receipt
    result = dict(_generic(receipt, path))
    if "sid" not in result:
        return result
    if "s" not in result and scope is not None:
        result["s"] = scope
    if "src_s" not in result and "s" in result:
        result["src_s"] = result["s"]
    return result


def decode_data(value: Mapping[str, Any], scope: Optional[str] = None,
                revision: Optional[str] = None) -> Dict[str, Any]:
    """Expand one response body without consulting any earlier response."""
    source = _dict(value, "$.data")
    decoded_inventory, inventory_by_locator, ambiguous = [], {}, set()
    if "inv" in source:
        decoded_inventory, inventory_by_locator, ambiguous = _inventory(source["inv"], "$.data.inv")

    result: Dict[str, Any] = {}
    for key, child in source.items():
        path = f"$.data.{key}"
        if key == "inv":
            result[key] = decoded_inventory
        elif key == "ui":
            result[key] = _ui(child, inventory_by_locator, ambiguous, path)
        elif key == "map":
            result[key] = _map(child, path)
        elif key == "entities":
            result[key] = _entities(source, path)
        elif key in _OPAQUE_FIELDS:
            result[key] = _copy(child)
        else:
            result[key] = _generic(child, path)

    activity = result.get("activity")
    if isinstance(activity, dict):
        if "rev" not in activity and revision is not None:
            activity["rev"] = revision
        for action in result.get("acts", []):
            if isinstance(action, dict) and action.get("op") == "cancel":
                for field in ("rev", "rid"):
                    if field not in action and field in activity:
                        action[field] = _copy(activity[field])

    if "saved" in result:
        result["saved"] = _receipt_defaults(result["saved"], scope, "$.data.saved")
    persistence = result.get("persistence")
    if isinstance(persistence, dict):
        saves = persistence.get("saves", [])
        if not isinstance(saves, list):
            raise DecodeError("$.data.persistence.saves must be an array")
        persistence["saves"] = [
            _receipt_defaults(receipt, scope, f"$.data.persistence.saves[{index}]")
            for index, receipt in enumerate(saves)
        ]
        if "saved" in persistence:
            saved = persistence["saved"]
            if type(saved) is int:
                persistence["saved"] = _definition(
                    persistence["saves"], saved, "$.data.persistence.saved")
            else:
                persistence["saved"] = _receipt_defaults(
                    saved, scope, "$.data.persistence.saved")
    return result


@dataclass(frozen=True)
class WireResponse:
    """One validated protocol-6 response and its independently decoded copy."""

    raw: Mapping[str, Any]
    frame: Mapping[str, Any]

    @property
    def status(self) -> Optional[str]:
        return self.frame.get("st")

    @property
    def error_code(self) -> Optional[str]:
        return self.frame.get("err")

    @property
    def is_error(self) -> bool:
        return self.error_code is not None

    @property
    def data(self) -> Optional[Mapping[str, Any]]:
        value = self.frame.get("data")
        return value if isinstance(value, Mapping) else None

    @property
    def is_observation(self) -> bool:
        return not self.is_error and self.data is not None and bool(OBSERVATION_FIELDS & set(self.data))


def decode_wire_response(value: Mapping[str, Any]) -> WireResponse:
    """Validate and decode exactly one direct child wire response."""
    source = _dict(value, "$")
    if type(source.get("v")) is not int or source.get("v") != PROTOCOL_VERSION:
        raise DecodeError("$.v must be integer protocol version 6")
    if not isinstance(source.get("id"), str) or not source["id"]:
        raise DecodeError("$.id must be a non-empty string")
    has_status, has_error = "st" in source, "err" in source
    if has_status == has_error:
        raise DecodeError("response must contain exactly one of st or err")
    if has_status and (not isinstance(source["st"], str) or source["st"] not in WIRE_STATUSES):
        raise DecodeError("$.st is not a protocol-6 response status")
    if has_error and (not isinstance(source["err"], str) or not source["err"]):
        raise DecodeError("$.err must be a non-empty string")

    decoded = dict(_generic(source))
    if "data" in source:
        decoded["data"] = decode_data(_dict(source["data"], "$.data"),
                                      source.get("s"), source.get("rev"))
    return WireResponse(raw=_copy(source), frame=decoded)


@dataclass(frozen=True)
class ClientProblem:
    stage: str
    code: str
    payload: Any


@dataclass(frozen=True)
class LateResponse:
    request: Any
    response: WireResponse


@dataclass(frozen=True)
class ClientResponse:
    """A controller/direct result with outcome and live observation separated."""

    raw: Mapping[str, Any]
    controller: Optional[str] = None
    response: Optional[WireResponse] = None
    outcome: Optional[WireResponse] = None
    observation: Optional[WireResponse] = None
    discovery: Optional[WireResponse] = None
    receipt: Optional[WireResponse] = None
    original_response: Optional[WireResponse] = None
    late_responses: Tuple[LateResponse, ...] = ()
    problems: Tuple[ClientProblem, ...] = ()

    @property
    def current_frame(self) -> Optional[WireResponse]:
        # A settle wrapper's receipt/outcome and discovery are never the current
        # gameplay observation.  Only its explicit observation can be installed.
        if self.controller == "settle":
            return (self.observation if self.observation and self.observation.is_observation
                    and not self.observation.is_error else None)
        if self.controller == "response":
            return (self.response if self.response and self.response.is_observation
                    and not self.response.is_error else None)
        if self.controller is None:
            return (self.response if self.response and self.response.is_observation
                    and not self.response.is_error else None)
        return None

    @property
    def ok(self) -> bool:
        if self.problems:
            return False
        if self.controller == "settle":
            return self.raw.get("st") == "completed"
        if self.controller == "error":
            return False
        return self.response is None or not self.response.is_error

    def require_success(self) -> "ClientResponse":
        if not self.ok:
            problems = self.problems or (
                ClientProblem("controller", str(self.raw.get("err", "NOT_COMPLETED")), self.raw),
            )
            raise ClientResponseError(problems)
        return self


def _wire_stage(value: Any, stage: str,
                problems: list) -> Optional[WireResponse]:
    if value is None:
        return None
    if isinstance(value, Mapping) and value.get("controller") == "error":
        code = value.get("err")
        if not isinstance(code, str) or not code:
            raise DecodeError(f"{stage} controller error has no code")
        problems.append(ClientProblem(stage, code, _copy(value)))
        return None
    response = decode_wire_response(_dict(value, stage))
    if response.is_error:
        problems.append(ClientProblem(stage, response.error_code, response.raw))
    return response


def _late(value: Any, problems: list) -> Tuple[LateResponse, ...]:
    if value is None:
        return ()
    result = []
    for index, item in enumerate(_list(value, "$.late_responses")):
        item = _dict(item, f"$.late_responses[{index}]")
        if "response" not in item:
            raise DecodeError(f"$.late_responses[{index}] has no response")
        response = _wire_stage(item["response"], f"late_responses[{index}]", problems)
        if response is None:
            raise DecodeError(f"$.late_responses[{index}] is not a wire response")
        result.append(LateResponse(request=_copy(item.get("request")), response=response))
    return tuple(result)


def decode_client_response(value: Mapping[str, Any]) -> ClientResponse:
    """Decode a direct response or one StableController output object."""
    source = _dict(value, "$")
    if "controller" not in source:
        response = decode_wire_response(source)
        problems = () if not response.is_error else (
            ClientProblem("response", response.error_code, response.raw),
        )
        return ClientResponse(raw=_copy(source), response=response, problems=problems)

    controller = source.get("controller")
    if not isinstance(controller, str):
        raise DecodeError("$.controller must be a string")
    if controller not in {"response", "error", "settle", "exit"}:
        raise DecodeError("$.controller is unknown")
    problems = []

    if controller == "error":
        code = source.get("err")
        if not isinstance(code, str) or not code:
            raise DecodeError("controller:error requires a non-empty err")
        problems.append(ClientProblem("controller", code, _copy(source)))
        late = _late(source.get("late_responses"), problems)
        return ClientResponse(raw=_copy(source), controller=controller,
                              late_responses=late, problems=tuple(problems))

    response = outcome = observation = discovery = receipt = original = None
    nested_late: Tuple[LateResponse, ...] = ()
    if controller == "response":
        response = _wire_stage(source.get("response"), "response", problems)
        if response is None:
            raise DecodeError("controller:response has no wire response")
    elif controller == "settle":
        outcome = _wire_stage(source.get("outcome"), "outcome", problems)
        observation = _wire_stage(source.get("observation"), "observation", problems)
        discovery = _wire_stage(source.get("discovery"), "discovery", problems)
        receipt = _wire_stage(source.get("receipt"), "receipt", problems)
        original = _wire_stage(source.get("original_response"), "original_response", problems)
        _wire_stage(source.get("initial_error"), "initial_error", problems)
        transport = source.get("transport_error")
        if transport is not None:
            if not isinstance(transport, Mapping) or transport.get("controller") != "error":
                raise DecodeError("transport_error must be a controller:error object")
            code = transport.get("err")
            if not isinstance(code, str) or not code:
                raise DecodeError("transport_error has no code")
            problems.append(ClientProblem("transport_error", code, _copy(transport)))
        settle_status = source.get("st")
        if settle_status == "error":
            code = source.get("err")
            if not isinstance(code, str) or not code:
                raise DecodeError("failed settle response has no err")
            problems.append(ClientProblem("settle", code, _copy(source)))
        elif not isinstance(settle_status, str) or settle_status not in {"pending", "completed"}:
            raise DecodeError("settle st must be pending, completed, or error")
    else:  # controller:exit
        nested = source.get("outcome")
        if nested is not None:
            if not isinstance(nested, Mapping):
                raise DecodeError("controller:exit outcome must be an object")
            if "controller" in nested:
                nested_result = decode_client_response(nested)
                outcome = nested_result.outcome or nested_result.response
                observation, discovery = nested_result.observation, nested_result.discovery
                receipt, original = nested_result.receipt, nested_result.original_response
                nested_late = nested_result.late_responses
                problems.extend(nested_result.problems)
            else:
                outcome = _wire_stage(nested, "outcome", problems)
        exit_status = source.get("st")
        if not isinstance(exit_status, str) or exit_status not in {"pending", "completed", "error"}:
            raise DecodeError("exit st must be pending, completed, or error")
        if "err" in source:
            code = source.get("err")
            if not isinstance(code, str) or not code:
                raise DecodeError("controller:exit err must be a non-empty string")
            problems.append(ClientProblem("exit", code, _copy(source)))

    late = nested_late + _late(source.get("late_responses"), problems)
    return ClientResponse(
        raw=_copy(source), controller=controller, response=response,
        outcome=outcome, observation=observation, discovery=discovery,
        receipt=receipt, original_response=original,
        late_responses=late, problems=tuple(problems),
    )


def map_cell(frame: WireResponse, cell: int) -> Optional[Mapping[str, Any]]:
    """Return one known decoded cell; unknown map gaps remain ``None``."""
    if type(cell) is not int:
        raise IntentError("cell must be an integer")
    data = frame.data
    dungeon_map = data.get("map") if isinstance(data, Mapping) else None
    if not isinstance(dungeon_map, Mapping):
        return None
    cells = dungeon_map.get("decoded_cells", dungeon_map.get("cells", []))
    for candidate in cells:
        if candidate.get("cell") == cell:
            return _copy(candidate)
    return None


@dataclass(frozen=True)
class IntentValidation:
    intent: Mapping[str, Any]
    advertised: Tuple[Mapping[str, Any], ...] = ()
    node: Optional[Mapping[str, Any]] = None
    item: Optional[Mapping[str, Any]] = None
    target_cell: Optional[Mapping[str, Any]] = None


def _current_wire(value: Any) -> WireResponse:
    if isinstance(value, ClientResponse):
        if value.problems:
            raise IntentError("client response contains unresolved errors or transport diagnostics")
        value = value.current_frame
    if not isinstance(value, WireResponse):
        raise IntentError("current_frame must be a decoded current WireResponse")
    if value.is_error or not value.is_observation:
        raise IntentError("current_frame is not an actionable live observation")
    return value


def validate_intent(current_frame: Any, intent: Mapping[str, Any]) -> IntentValidation:
    """Validate one controller action against exactly one displayed frame.

    This function never changes or sends the intent.  A decoded map cell is
    returned as evidence only; terrain is not interpreted as passable, safe, or a
    promise that the hero will move.  Read-only queries and local ``settle`` do
    not need a current frame; they are returned unchanged after rejecting the
    controller-owned ``v``/``id`` fields.
    """
    source = _dict(intent, "intent")
    if "v" in source or "id" in source:
        raise IntentError("controller intents must not contain v or id")
    op = source.get("op")
    if not isinstance(op, str) or not op:
        raise IntentError("intent.op must be a non-empty string")
    if op in QUERY_OPERATIONS:
        return IntentValidation(intent=_copy(source))
    if op not in NODE_OPERATIONS | GLOBAL_OPERATIONS:
        raise IntentError("intent.op is not a known controller operation")

    frame = _current_wire(current_frame)

    revision = frame.frame.get("rev")
    if not isinstance(revision, str) or not revision:
        raise IntentError("current frame has no actionable rev")
    if source.get("rev") != revision:
        raise IntentError("intent.rev must exactly equal the displayed current rev")
    scope = frame.frame.get("s")
    if "s" in source and source["s"] != scope:
        raise IntentError("intent.s does not match the displayed current scope")

    data = frame.data or {}
    advertised = []
    node = item = target = None
    if op in NODE_OPERATIONS:
        control = source.get("ctl")
        if not isinstance(control, str) or not control:
            raise IntentError(f"{op} requires a current ctl")
        ui = data.get("ui")
        nodes = ui.get("nodes", []) if isinstance(ui, Mapping) else []
        matches = [candidate for candidate in nodes
                   if isinstance(candidate, Mapping) and candidate.get("id") == control]
        if len(matches) != 1:
            raise IntentError("ctl is missing or ambiguous in this frame")
        node = matches[0]
        if node.get("enabled") is not True:
            raise IntentError("ctl is not enabled in this frame")
        operations = node.get("ops")
        if not isinstance(operations, list):
            raise IntentError("ctl advertises no operations")
        advertised = [candidate for candidate in operations
                      if isinstance(candidate, Mapping) and candidate.get("op") == op]
        if not advertised:
            raise IntentError("ctl does not advertise the requested operation")
        if op == "click":
            gesture = source.get("g", "click")
            if not isinstance(gesture, str):
                raise IntentError("click.g must be a string gesture")
            supported = set()
            for descriptor in advertised:
                gestures = descriptor.get("gestures", ["click"])
                if not isinstance(gestures, list) or not all(isinstance(value, str) for value in gestures):
                    raise IntentError("advertised click gestures are invalid")
                supported.update(gestures)
            if gesture not in supported:
                raise IntentError("ctl does not advertise the requested gesture")
    else:
        acts = data.get("acts", [])
        if not isinstance(acts, list):
            raise IntentError("current frame has no global action list")
        advertised = [candidate for candidate in acts
                      if isinstance(candidate, Mapping) and candidate.get("op") == op]
        if not advertised:
            raise IntentError("operation is not advertised in this frame")

    if op == "cancel":
        advertised = [descriptor for descriptor in advertised
                      if descriptor.get("rid") == source.get("rid")
                      and descriptor.get("rev") == source.get("rev")]
        if not advertised:
            raise IntentError("cancel must use the advertised activity rid and rev")

    if op == "move":
        direction = source.get("dir")
        if not isinstance(direction, str) or direction not in DIRECTIONS:
            raise IntentError("move.dir must be one of N, NE, E, SE, S, SW, W, NW")
    if op == "item":
        locator = source.get("loc")
        if not isinstance(locator, str) or not locator:
            raise IntentError("item.loc must be a current locator")
        items = [candidate for candidate in data.get("inv", [])
                 if isinstance(candidate, Mapping) and candidate.get("loc") == locator]
        if len(items) != 1 or items[0].get("available") is not True:
            raise IntentError("item.loc is missing, ambiguous, or unavailable in this frame")
        item = items[0]
    if op == "cell":
        cell = source.get("cell")
        if type(cell) is not int:
            raise IntentError("cell.cell must be an integer")
        dungeon_map = data.get("map")
        if not isinstance(dungeon_map, Mapping):
            raise IntentError("current frame has no map")
        width, height = dungeon_map.get("w"), dungeon_map.get("h")
        if type(width) is not int or type(height) is not int or not 0 <= cell < width * height:
            raise IntentError("cell.cell is outside the current map")
        target = map_cell(frame, cell)

    return IntentValidation(
        intent=_copy(source), advertised=tuple(_copy(advertised)),
        node=_copy(node), item=_copy(item), target_cell=target,
    )
