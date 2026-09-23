"""Strict, reusable decoders for ``spdctl`` protocol-8 client output.

This module is deliberately transport- and policy-free.  It does not launch the
game, allocate request IDs, retry operations, replace revisions, or decide which
game action to take.  It only:

* validates and expands one protocol-8 wire response using that response's own
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
import math
import re
import struct
import unicodedata
from dataclasses import dataclass
from typing import Any, Dict, Mapping, Optional, Sequence, Tuple


PROTOCOL_VERSION = 8
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
    """Invalid public content with immutable-by-copy evidence, never a retry signal."""

    def __init__(self, message: str):
        super().__init__(message)
        self.raw = None
        self.identity: Dict[str, str] = {}
        self.stage: Optional[str] = None
        self.contexts = []
        self._raw_attached = False

    def preserve_raw(self, value: Any) -> None:
        if self._raw_attached:
            return
        self.raw = copy.deepcopy(value)
        self._raw_attached = True
        if isinstance(value, Mapping):
            self.identity = {key: value[key] for key in ("id", "s")
                             if isinstance(value.get(key), str) and value[key]}

    @property
    def response_id(self) -> Optional[str]:
        return self.identity.get("id")

    @property
    def scope(self) -> Optional[str]:
        return self.identity.get("s")

    @property
    def context(self) -> Optional[Mapping[str, Any]]:
        return self.contexts[-1] if self.contexts else None


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
    _shown(item.get("shown"), f"{path}.shown") if "shown" in item else None
    for field, default in (
        ("qty", 1), ("equipped", False), ("available", True),
        ("type_known", True), ("via", "click"),
    ):
        if field not in item:
            item[field] = _copy(default)
    return item


def _shown(value: Any, path: str) -> None:
    """Check known displayed facts without discarding unknown semantic metadata."""
    if value is None:
        return
    shown = _dict(value, path)
    _reject_drawing(shown, path)
    for key in ("status", "extra", "level", "symbol", "variant", "counter"):
        if key in shown and shown[key] is not None and not isinstance(shown[key], str):
            raise DecodeError(f"{path}.{key} must be text or null")
    for key in ("counter_kind", "progress_kind"):
        if key in shown and (not isinstance(shown[key], str)
                             or shown[key] not in {"shield", "cooldown"}):
            raise DecodeError(f"{path}.{key} must be shield or cooldown")
    if "status_kind" in shown and (not isinstance(shown["status_kind"], str)
                                   or shown["status_kind"] != "quantity"):
        raise DecodeError(f"{path}.status_kind must be quantity")
    if "progress" in shown and shown["progress"] is not None:
        progress = _dict(shown["progress"], f"{path}.progress")
        for key in ("covered", "total"):
            if key not in progress or type(progress[key]) is not int or progress[key] < 0:
                raise DecodeError(f"{path}.progress.{key} must be a nonnegative integer")
        if progress["total"] == 0:
            raise DecodeError(f"{path}.progress.total must be positive")
        if progress["covered"] > progress["total"]:
            raise DecodeError(f"{path}.progress.covered exceeds total")
        if progress.get("basis") != "displayed":
            raise DecodeError(f"{path}.progress.basis must be displayed")
    if "strength" in shown and shown["strength"] is not None:
        strength = _dict(shown["strength"], f"{path}.strength")
        if type(strength.get("value")) is not int or type(strength.get("estimated")) is not bool:
            raise DecodeError(f"{path}.strength needs integer value and boolean estimated")
        for key in ("insufficient", "mastered"):
            if key in strength and type(strength[key]) is not bool:
                raise DecodeError(f"{path}.strength.{key} must be boolean")
    if "flags" in shown and shown["flags"] is not None:
        for index, flag in enumerate(_list(shown["flags"], f"{path}.flags")):
            if not isinstance(flag, str):
                raise DecodeError(f"{path}.flags[{index}] must be text")
    if "badge" in shown and shown["badge"] is not None:
        _semantic_icon(shown["badge"], f"{path}.badge")
    if "charge" in shown and shown["charge"] is not None:
        charge = _dict(shown["charge"], f"{path}.charge")
        fraction = charge.get("fraction")
        if type(fraction) not in (int, float) or not math.isfinite(fraction) or not 0 <= fraction <= 1:
            raise DecodeError(f"{path}.charge.fraction must be a finite number from 0 to 1")
        if charge.get("basis") != "displayed_gradient":
            raise DecodeError(f"{path}.charge.basis must be displayed_gradient")
    if "boss_warning" in shown and type(shown["boss_warning"]) is not bool:
        raise DecodeError(f"{path}.boss_warning must be boolean")
    for key in ("broken_seal", "lit_candle", "nature_powered"):
        if key in shown and type(shown[key]) is not bool:
            raise DecodeError(f"{path}.{key} must be boolean")
    if "glow" in shown and shown["glow"] is not None:
        _glow(shown["glow"], f"{path}.glow")


_RETIRED_DRAWING_FIELDS = {
    "atlas", "frame_pixels", "texture_size", "flip_horizontal", "flip_vertical",
    "angle", "scale", "tint", "alpha", "opacity", "rgb",
}


def _reject_drawing(value: Mapping[str, Any], path: str) -> None:
    for key in _RETIRED_DRAWING_FIELDS & set(value):
        raise DecodeError(f"{path}.{key} is retired drawing data")
    if type(value.get("color")) is int:
        raise DecodeError(f"{path}.color is retired RGB data")


def _semantic_icon(value: Any, path: str) -> None:
    if value is None:
        return
    icon = _dict(value, path)
    _reject_drawing(icon, path)
    if not isinstance(icon.get("symbol"), str) or not icon["symbol"]:
        raise DecodeError(f"{path}.symbol must be a non-empty semantic name")
    if "variant" in icon and icon["variant"] is not None and not isinstance(icon["variant"], str):
        raise DecodeError(f"{path}.variant must be text or null")
    if "placeholder" in icon and type(icon["placeholder"]) is not bool:
        raise DecodeError(f"{path}.placeholder must be boolean")
    for key in ("broken_seal", "lit_candle", "nature_powered"):
        if key in icon and type(icon[key]) is not bool:
            raise DecodeError(f"{path}.{key} must be boolean")


def _glow(value: Any, path: str) -> None:
    glow = _dict(value, path)
    _reject_drawing(glow, path)
    if not isinstance(glow.get("variant"), str) or not glow["variant"]:
        raise DecodeError(f"{path}.variant must be a named glow")
    if "kind" in glow and (not isinstance(glow["kind"], str)
                           or glow["kind"] not in {"explosive_heat", "resin_fortified"}):
        raise DecodeError(f"{path}.kind is not a reviewed glow meaning")
    if "stage" in glow and (not isinstance(glow["stage"], str)
                            or glow["stage"] not in {"cool", "warm", "hot"}):
        raise DecodeError(f"{path}.stage is not a reviewed glow stage")


def _text_semantics(value: Mapping[str, Any], path: str) -> None:
    if "styles" in value:
        raise DecodeError(f"{path}.styles is retired drawing data")
    if "tone" in value and value["tone"] is not None and not isinstance(value["tone"], str):
        raise DecodeError(f"{path}.tone must be a semantic name or null")
    if "unmapped_indicator" in value and type(value["unmapped_indicator"]) is not bool:
        raise DecodeError(f"{path}.unmapped_indicator must be boolean")
    if "spans" in value and value["spans"] is not None:
        for index, raw in enumerate(_list(value["spans"], f"{path}.spans")):
            here = f"{path}.spans[{index}]"
            span = _dict(raw, here)
            _reject_drawing(span, here)
            if not isinstance(span.get("text"), str):
                raise DecodeError(f"{here}.text must be text")
            if "tone" in span and span["tone"] is not None and not isinstance(span["tone"], str):
                raise DecodeError(f"{here}.tone must be a semantic name or null")
            if "clipped" in span and type(span["clipped"]) is not bool:
                raise DecodeError(f"{here}.clipped must be boolean")


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


def _records(source: Mapping[str, Any], key: str, table_key: str, path: str) -> list:
    """Expand one explicitly supported list; no defaults or inferred fields."""
    table = source.get(table_key, [])
    if table_key in source:
        _list(table, f"{path}.{table_key}")
        if key not in source:
            raise DecodeError(f"{path}.{table_key} has no {key} list")
    definitions = []
    for index, raw in enumerate(table):
        here = f"{path}.{table_key}[{index}]"
        template = _dict(raw, here)
        if set(template) != {"common", "fields"}:
            raise DecodeError(f"{here} must contain exactly common and fields")
        common = _dict(template["common"], f"{here}.common")
        fields = _list(template["fields"], f"{here}.fields")
        if not all(isinstance(field, str) for field in fields) or len(set(fields)) != len(fields):
            raise DecodeError(f"{here}.fields must contain unique field names")
        if not all(isinstance(field, str) for field in common):
            raise DecodeError(f"{here}.common must have string field names")
        if set(common) & set(fields):
            raise DecodeError(f"{here} common and fields overlap")
        definitions.append((common, fields))
    if key not in source:
        return []
    rows = _list(source[key], f"{path}.{key}")
    result = []
    for index, raw in enumerate(rows):
        here = f"{path}.{key}[{index}]"
        if isinstance(raw, Mapping):
            result.append(dict(_copy(raw)))
            continue
        row = _list(raw, here)
        if not row:
            raise DecodeError(f"{here} is an empty template row")
        template_index = _integer(row[0], f"{here}[0]")
        if template_index < 0 or template_index >= len(definitions):
            raise DecodeError(f"{here}[0] references a missing same-frame template")
        common, fields = definitions[template_index]
        if len(row) != len(fields) + 1:
            raise DecodeError(f"{here} has the wrong number of values")
        record = dict(_copy(common))
        record.update(zip(fields, _copy(row[1:])))
        result.append(record)
    return result


def _contains_metadata(value: Any) -> bool:
    if isinstance(value, Mapping):
        if value.get("clipped") is True or any(
                isinstance(value.get(key), Mapping) and bool(value[key])
                for key in ("text_sources", "text_origins", "text_diagnostics", "pres", "presentation")):
            return True
        return any(_contains_metadata(child) for child in value.values())
    if isinstance(value, list):
        return any(_contains_metadata(child) for child in value)
    return False


def _decode_operations(value: Any, actions: Any, control: Any, path: str) -> Any:
    if value is None:
        return None
    result = []
    for index, raw in enumerate(_list(value, path)):
        here = f"{path}[{index}]"
        if type(raw) is int:
            operation = dict(_dict(_definition(actions, raw, here), here))
            if _contains_metadata(operation):
                raise DecodeError(f"{here} protected action must remain inline")
            if not isinstance(control, str) or not control or operation.get("ctl") != control:
                raise DecodeError(f"{here} action ctl does not match this node id")
            operation.pop("ctl")
        else:
            operation = dict(_generic(_dict(raw, here), here))
        if not isinstance(operation.get("op"), str) or not operation["op"]:
            raise DecodeError(f"{here}.op must be a non-empty string")
        result.append(operation)
    return result


def _expand_observation(value: Mapping[str, Any], path: str,
                        revision: Optional[str] = None, bindings: bool = False) -> Dict[str, Any]:
    """Pure structure expansion at one snapshot root; unknown extensions stay opaque."""
    source = _dict(value, path)
    result = dict(_copy(source))
    if isinstance(source.get("inv"), list) or "inv_templates" in source:
        result["inv"] = _records(source, "inv", "inv_templates", path)
        result.pop("inv_templates", None)
    if isinstance(source.get("acts"), list) or "act_templates" in source:
        result["acts"] = _records(source, "acts", "act_templates", path)
        result.pop("act_templates", None)
    actions = result.get("acts", [])
    if bindings:
        # Bind before copying referenced operations, so both advertised copies
        # have identical current activity constraints. Frozen children reset rev.
        activity = result.get("activity")
        if isinstance(activity, dict):
            own_revision = source.get("rev", revision)
            if "rev" not in activity and own_revision is not None:
                activity["rev"] = own_revision
            for action in actions if isinstance(actions, list) else []:
                if isinstance(action, dict) and action.get("op") == "cancel":
                    for field in ("rev", "rid"):
                        if field not in action and field in activity:
                            action[field] = _copy(activity[field])
    inventory, ambiguous = {}, set()
    def add_item(item: Any) -> None:
        if not isinstance(item, Mapping):
            return
        locator = item.get("loc")
        if isinstance(locator, str):
            if locator in inventory:
                ambiguous.add(locator)
            inventory[locator] = item
    for item in result.get("inv", []) if isinstance(result.get("inv"), list) else []:
        add_item(item)
    # Public floor items can be referenced by a node even though they are in
    # expanded entities rather than inv. Only this frame's definitions qualify.
    entity_defs = source.get("entity_defs", [])
    for index, raw_entity in enumerate(source.get("entities", []) if isinstance(source.get("entities"), list) else []):
        entity = _dict(raw_entity, f"{path}.entities[{index}]")
        if "def" in entity:
            entity = _dict(_definition(entity_defs, entity["def"], f"{path}.entities[{index}].def"),
                           f"{path}.entities[{index}].def")
        add_item(entity.get("item"))
    if isinstance(source.get("ui"), Mapping):
        ui_source = _dict(source["ui"], f"{path}.ui")
        if "node_shapes" in ui_source or "op_defs" in ui_source:
            raise DecodeError(f"{path}.ui contains unsupported protocol-6 structures")
        ui = dict(_copy(ui_source))
        if "nodes" in ui_source or "node_templates" in ui_source:
            nodes = _records(ui_source, "nodes", "node_templates", f"{path}.ui")
            ui.pop("node_templates", None)
            for index, node in enumerate(nodes):
                here = f"{path}.ui.nodes[{index}]"
                if "ops" in node:
                    node["ops"] = _decode_operations(node["ops"], actions, node.get("id"), f"{here}.ops")
                if "label" in node:
                    label = node["label"]
                    if type(label) is int:
                        if label not in (0, 1):
                            raise DecodeError(f"{here}.label has an invalid item-label mode")
                        subject = node.get("subject")
                        locator = node.get("loc")
                        if isinstance(subject, Mapping) and subject.get("kind") == "item":
                            subject_locator = subject.get("loc")
                            if locator is not None and locator != subject_locator:
                                raise DecodeError(f"{here}.label contradicts the item subject loc")
                            locator = subject_locator
                        if not isinstance(locator, str) or locator in ambiguous or locator not in inventory:
                            raise DecodeError(f"{here}.label has no unique same-frame loc binding")
                        name = inventory[locator].get("name")
                        if not isinstance(name, str):
                            raise DecodeError(f"{here}.label binding has no string item name")
                        node["label"] = name if label == 0 else _title_case(name)
                    elif label is not None and not isinstance(label, str):
                        raise DecodeError(f"{here}.label must be literal text, null, or 0/1")
            ui["nodes"] = nodes
        result["ui"] = ui
    for key in ("before", "after"):
        if isinstance(source.get(key), Mapping):
            result[key] = _expand_observation(source[key], f"{path}.{key}", bindings=bindings)
    return result


def expand_structures(value: Mapping[str, Any], scope: Optional[str] = None,
                      revision: Optional[str] = None, *, bindings: bool = False) -> Dict[str, Any]:
    """Expand v8 templates/references only, without semantic defaults or aliases.

    By default no binding defaults are applied. Test adapters may explicitly bind
    current activity before resolving references; frozen children still reset
    their context. Raw/reply/source/unknown data is untouched in either mode.
    """
    source = _dict(value, "$")
    if "v" in source and isinstance(source.get("data"), Mapping):
        result = dict(_copy(source))
        result["data"] = _expand_observation(_dict(source["data"], "$.data"), "$.data",
                                             source.get("rev"), bindings)
        return result
    return _expand_observation(source, "$", revision, bindings)


def _ui(value: Any, inventory: Mapping[str, Mapping[str, Any]], ambiguous: set,
        path: str) -> Dict[str, Any]:
    # Structure and labels were expanded against this snapshot before defaults.
    result = dict(_generic(_dict(value, path), path))
    if "nodes" in result:
        for node in result["nodes"]:
            if "enabled" not in node:
                node["enabled"] = True
            if "dimmed" not in node:
                node["dimmed"] = False
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
        if not isinstance(raw_cell, str) or re.fullmatch(r"[0-9]+", raw_cell) is None:
            raise DecodeError(f"{path}.env keys must be decimal cell strings")
        try:
            cell = int(raw_cell)
        except ValueError as error:
            raise DecodeError(f"{path}.env cell is not a supported decimal integer") from error
        if not 0 <= cell < width * height:
            raise DecodeError(f"{path}.env contains an out-of-bounds cell")
        if cell in decoded_environment:
            raise DecodeError(f"{path}.env contains duplicate decimal cell identities")
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
    # ``cells`` is not a protocol-8 compact field today.  Preserve it if a
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


def _buffs(owner: Mapping[str, Any], path: str) -> None:
    if "buffs" not in owner or owner["buffs"] is None:
        return
    for index, raw in enumerate(_list(owner["buffs"], f"{path}.buffs")):
        here = f"{path}.buffs[{index}]"
        buff = _dict(raw, here)
        if "shown" in buff:
            _shown(buff["shown"], f"{here}.shown")


def _turn_progress(value: Any, path: str) -> None:
    if value is None:
        return
    progress = _dict(value, path)
    _reject_drawing(progress, path)
    sweep = progress.get("sweep")
    if type(sweep) not in (int, float) or not math.isfinite(sweep) or not 0 <= sweep <= 1:
        raise DecodeError(f"{path}.sweep must be a finite number from 0 to 1")


def _health_estimate(value: Any, path: str) -> None:
    if value is None:
        return
    estimate = _dict(value, path)
    for sample_index, sample in enumerate(_list(estimate.get("samples"), f"{path}.samples")):
        sample_path = f"{path}.samples[{sample_index}]"
        sample = _dict(sample, sample_path)
        for key in ("total", "filled", "with_shield"):
            if type(sample.get(key)) is not int:
                raise DecodeError(f"{sample_path}.{key} must be an integer")
        if not (0 < sample["total"] and 0 <= sample["filled"] <= sample["with_shield"] <= sample["total"]):
            raise DecodeError(f"{sample_path} has inconsistent displayed segments")
        if sample.get("basis") != "displayed":
            raise DecodeError(f"{sample_path}.basis must be displayed")


def _has_unresolved_subject_diagnostic(node: Mapping[str, Any]) -> bool:
    presentation = node.get("pres", node.get("presentation"))
    if not isinstance(presentation, Mapping):
        return False
    if presentation.get("st", presentation.get("status")) != "partial":
        return False
    diagnostics = presentation.get("diag", presentation.get("diagnostics"))
    return isinstance(diagnostics, list) and any(
        isinstance(entry, Mapping) and entry.get("field") == "subject_data"
        and entry.get("code") == "unresolved_subject" for entry in diagnostics)


def _has_unmapped_partial(value: Any, field: str, indicator: str) -> bool:
    if not isinstance(value, Mapping) or value.get("unmapped_indicator") is not True:
        return False
    presentation = value.get("pres", value.get("presentation"))
    if not isinstance(presentation, Mapping) or presentation.get("st", presentation.get("status")) != "partial":
        return False
    diagnostics = presentation.get("diag", presentation.get("diagnostics"))
    return isinstance(diagnostics, list) and any(
        isinstance(entry, Mapping) and entry.get("code") == "unmapped_indicator"
        and entry.get("indicator") == indicator and entry.get("field") == field
        for entry in diagnostics)


def _subject_data(node: Mapping[str, Any], path: str) -> Any:
    """Actions-only facts are complete here; never resolve them from an older frame."""
    if "subject" in node:
        raise DecodeError(f"{path} cannot combine subject_data with a world reference")
    raw = node["subject_data"]
    if raw is None:
        if not _has_unresolved_subject_diagnostic(node):
            raise DecodeError(f"{path}.subject_data null requires local unresolved_subject partial evidence")
        return None
    source = _dict(raw, f"{path}.subject_data")
    if "loc" in source:
        if not isinstance(source["loc"], str) or not source["loc"]:
            raise DecodeError(f"{path}.subject_data.loc must be a non-empty current locator")
        if "loc" in node and node["loc"] != source["loc"]:
            raise DecodeError(f"{path}.subject_data contradicts this node loc")
        facts = _item(source, f"{path}.subject_data")
    else:
        facts = dict(_generic(source, f"{path}.subject_data"))
    if "shown" in facts:
        _shown(facts["shown"], f"{path}.subject_data.shown")
    _buffs(facts, f"{path}.subject_data")
    if "turn_progress" in facts:
        _turn_progress(facts["turn_progress"], f"{path}.subject_data.turn_progress")
    if "health_estimate" in facts:
        _health_estimate(facts["health_estimate"], f"{path}.subject_data.health_estimate")
    if "item" in facts and facts["item"] is not None:
        facts["item"] = _item(facts["item"], f"{path}.subject_data.item")
    return facts


def _semantic_fields(result: Mapping[str, Any], path: str) -> None:
    """Validate current-frame semantic facts and node bindings after all tables expand."""
    cues = result.get("cues")
    if cues is not None:
        cues = _dict(cues, f"{path}.cues")
        for key in ("metrics", "metrics_at", "screen_effects", "screen_effects_at"):
            if key in cues:
                raise DecodeError(f"{path}.cues.{key} is retired drawing data")
        if not isinstance(cues.get("status"), str) or cues["status"] not in {"last_observed", "not_observed"}:
            raise DecodeError(f"{path}.cues.status is not a Protocol 8 observation status")
        for index, raw in enumerate(_list(cues.get("cues"), f"{path}.cues.cues")):
            here = f"{path}.cues.cues[{index}]"
            cue = _dict(raw, here)
            _reject_drawing(cue, here)
            if not isinstance(cue.get("kind"), str) or not cue["kind"]:
                raise DecodeError(f"{here}.kind must be a non-empty semantic name")
            if type(cue.get("cell")) is not int or cue["cell"] < 0:
                raise DecodeError(f"{here}.cell must be a nonnegative integer")
            if "source_cell" in cue and (type(cue["source_cell"]) is not int or cue["source_cell"] < 0):
                raise DecodeError(f"{here}.source_cell must be a nonnegative integer")
            if "unmapped_indicator" in cue and type(cue["unmapped_indicator"]) is not bool:
                raise DecodeError(f"{here}.unmapped_indicator must be boolean")
            appearance = None
            if "appearance" in cue and cue["appearance"] is not None:
                appearance_path = f"{here}.appearance"
                appearance = _dict(cue["appearance"], appearance_path)
                _reject_drawing(appearance, appearance_path)
                if "unmapped_indicator" in appearance and type(appearance["unmapped_indicator"]) is not bool:
                    raise DecodeError(f"{appearance_path}.unmapped_indicator must be boolean")
                if "nature_powered" in appearance and type(appearance["nature_powered"]) is not bool:
                    raise DecodeError(f"{appearance_path}.nature_powered must be boolean")
                if "cells" in appearance:
                    cells = _list(appearance["cells"], f"{appearance_path}.cells")
                    if any(type(cell) is not int or cell < 0 for cell in cells):
                        raise DecodeError(f"{appearance_path}.cells must contain cell indices")
                if "shape" in appearance:
                    if not isinstance(appearance["shape"], str) or appearance["shape"] not in {"ring", "halo"} or appearance.get("coverage") != "visual_extent":
                        raise DecodeError(f"{appearance_path} has invalid radial extent")
                    if "cells" not in appearance or not cells:
                        raise DecodeError(f"{appearance_path}.cells must contain known cell indices")
                if "count" in appearance and (type(appearance["count"]) is not int or appearance["count"] <= 0):
                    raise DecodeError(f"{appearance_path}.count must be positive")
                if "symbol" in appearance and (not isinstance(appearance["symbol"], str) or not appearance["symbol"]):
                    raise DecodeError(f"{appearance_path}.symbol must be a non-empty semantic name")
                if cue.get("kind") == "item_glow":
                    _glow(appearance, appearance_path)
                if cue.get("kind") == "item_status":
                    for key in ("broken_seal", "lit_candle"):
                        if key in appearance and type(appearance[key]) is not bool:
                            raise DecodeError(f"{appearance_path}.{key} must be boolean")
                if cue.get("kind") in {"ward_state", "statue_armor"} and "tier" in appearance:
                    if type(appearance["tier"]) is not int or appearance["tier"] < 0:
                        raise DecodeError(f"{appearance_path}.tier must be a nonnegative integer")
                if "charge" in appearance and appearance["charge"] is not None:
                    charge = _dict(appearance["charge"], f"{appearance_path}.charge")
                    fraction = charge.get("fraction")
                    if type(fraction) not in (int, float) or not math.isfinite(fraction) or not 0 <= fraction <= 1:
                        raise DecodeError(f"{appearance_path}.charge.fraction must be from 0 to 1")
                    if charge.get("basis") != "displayed_brightness":
                        raise DecodeError(f"{appearance_path}.charge.basis must be displayed_brightness")
                for key in ("paused", "translucent", "resized", "partial", "fading"):
                    if key in appearance and type(appearance[key]) is not bool:
                        raise DecodeError(f"{appearance_path}.{key} must be boolean")
            if cue.get("kind") in {"ward_state", "statue_armor"} and (
                    appearance is None or "tier" not in appearance):
                indicator = "ward_form" if cue["kind"] == "ward_state" else "statue_armor"
                if not (_has_unmapped_partial(appearance, "tier", indicator)
                        or _has_unmapped_partial(cue, "tier", indicator)):
                    raise DecodeError(f"{here}.appearance.tier missing without unmapped partial evidence")

    hero = result.get("hero")
    if hero is not None:
        hero = _dict(hero, f"{path}.hero")
        if "shown" in hero:
            _shown(hero["shown"], f"{path}.hero.shown")
        _buffs(hero, f"{path}.hero")
        if "turn_progress" in hero and hero["turn_progress"] is not None:
            _turn_progress(hero["turn_progress"], f"{path}.hero.turn_progress")

    entities = result.get("entities", [])
    if entities is None:
        entities = []
    for index, raw in enumerate(_list(entities, f"{path}.entities")):
        here = f"{path}.entities[{index}]"
        entity = _dict(raw, here)
        _reject_drawing(entity, here)
        if "shown" in entity:
            _shown(entity["shown"], f"{here}.shown")
        if "appearance" in entity and entity["appearance"] is not None:
            _reject_drawing(_dict(entity["appearance"], f"{here}.appearance"),
                            f"{here}.appearance")
        _buffs(entity, here)
        if "health_estimate" in entity:
            _health_estimate(entity["health_estimate"], f"{here}.health_estimate")

    ui = result.get("ui")
    if ui is None:
        return
    ui = _dict(ui, f"{path}.ui")
    if "feedback" in ui and ui["feedback"] is not None:
        for index, raw in enumerate(_list(ui["feedback"], f"{path}.ui.feedback")):
            here = f"{path}.ui.feedback[{index}]"
            entry = _dict(raw, here)
            _reject_drawing(entry, here)
            _text_semantics(entry, here)
            if not isinstance(entry.get("kind"), str) or entry["kind"] not in {"log", "floating", "banner"}:
                raise DecodeError(f"{here}.kind must identify log, floating, or banner feedback")
            if "text" in entry and entry["text"] is not None and not isinstance(entry["text"], str):
                raise DecodeError(f"{here}.text must be text or null")
            if "cell" in entry and entry["cell"] is not None:
                if type(entry["cell"]) is not int or entry["cell"] < 0:
                    raise DecodeError(f"{here}.cell must be a nonnegative integer or null")
            if "icon" in entry:
                _semantic_icon(entry["icon"], f"{here}.icon")

    # Locators are current observation bindings. A ground item can be carried
    # by an expanded entity, while backpack/equipment items live in inv.
    locators: Dict[str, int] = {}
    for item in result.get("inv", []) if isinstance(result.get("inv"), list) else []:
        loc = item.get("loc")
        if isinstance(loc, str):
            locators[loc] = locators.get(loc, 0) + 1
    for entity in entities:
        item = entity.get("item")
        if isinstance(item, Mapping) and isinstance(item.get("loc"), str):
            loc = item["loc"]
            locators[loc] = locators.get(loc, 0) + 1

    for index, raw in enumerate(_list(ui.get("nodes", []), f"{path}.ui.nodes")):
        here = f"{path}.ui.nodes[{index}]"
        node = _dict(raw, here)
        _reject_drawing(node, here)
        _text_semantics(node, here)
        if "subject_data" in node:
            node["subject_data"] = _subject_data(node, here)
        if "shown" in node:
            _shown(node["shown"], f"{here}.shown")
        for key in ("free_cast", "targeting_marker"):
            if key in node and type(node[key]) is not bool:
                raise DecodeError(f"{here}.{key} must be boolean")
        if "quickslot" in node and (type(node["quickslot"]) is not int or node["quickslot"] < 1):
            raise DecodeError(f"{here}.quickslot must be a positive integer")
        for icon_key in ("icon", "primary_icon", "secondary_icon", "target_icon", "spell_icon"):
            if icon_key in node:
                _semantic_icon(node[icon_key], f"{here}.{icon_key}")
        if "preview_icons" in node and node["preview_icons"] is not None:
            for preview_index, icon in enumerate(_list(node["preview_icons"], f"{here}.preview_icons")):
                if icon is not None:
                    _semantic_icon(icon, f"{here}.preview_icons[{preview_index}]")
        if "subject" not in node or node["subject"] is None:
            continue
        subject = _dict(node["subject"], f"{here}.subject")
        kind = subject.get("kind")
        if kind == "item":
            if set(subject) != {"kind", "loc"} or not isinstance(subject.get("loc"), str) or not subject["loc"]:
                raise DecodeError(f"{here}.subject requires an item loc")
            if "loc" in node and node["loc"] != subject["loc"]:
                raise DecodeError(f"{here}.subject contradicts this node loc")
            if locators.get(subject["loc"]) != 1:
                raise DecodeError(f"{here}.subject has no unique same-frame item binding")
        elif kind == "hero":
            if set(subject) != {"kind"} or hero is None:
                raise DecodeError(f"{here}.subject has no same-frame hero binding")
        elif kind == "hero_buff":
            if set(subject) != {"kind", "index"} or type(subject.get("index")) is not int:
                raise DecodeError(f"{here}.subject requires a hero buff index")
            buffs = hero.get("buffs") if hero is not None else None
            if not isinstance(buffs, list) or not 0 <= subject["index"] < len(buffs):
                raise DecodeError(f"{here}.subject has no same-frame hero buff binding")
        elif isinstance(kind, str) and kind in {"entity", "entity_buff"}:
            expected = {"kind", "index"} if kind == "entity" else {"kind", "entity", "index"}
            if set(subject) != expected:
                raise DecodeError(f"{here}.subject has invalid {kind} fields")
            entity_index = subject.get("index") if kind == "entity" else subject.get("entity")
            if type(entity_index) is not int or not 0 <= entity_index < len(entities):
                raise DecodeError(f"{here}.subject has no same-frame entity binding")
            if kind == "entity_buff":
                buff_index = subject.get("index")
                buffs = entities[entity_index].get("buffs")
                if type(buff_index) is not int or not isinstance(buffs, list) or not 0 <= buff_index < len(buffs):
                    raise DecodeError(f"{here}.subject has no same-frame entity buff binding")
        else:
            raise DecodeError(f"{here}.subject has an unknown kind")


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
    source = _expand_observation(_dict(value, "$.data"), "$.data", revision, bindings=True)
    if "acts" in source:
        for index, raw in enumerate(_list(source["acts"], "$.data.acts")):
            action = _dict(raw, f"$.data.acts[{index}]")
            if not isinstance(action.get("op"), str) or not action["op"]:
                raise DecodeError(f"$.data.acts[{index}].op must be a non-empty string")
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
        elif key in {"before", "after"} and isinstance(child, Mapping):
            result[key] = decode_data(child, child.get("s"), child.get("rev"))
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
        for index, receipt in enumerate(saves):
            if not isinstance(receipt, Mapping) or not isinstance(receipt.get("sid"), str) or not receipt["sid"]:
                raise DecodeError(f"$.data.persistence.saves[{index}] must be a save receipt")
        persistence["saves"] = [
            _receipt_defaults(receipt, scope, f"$.data.persistence.saves[{index}]")
            for index, receipt in enumerate(saves)
        ]
        if "saved" in persistence:
            saved = persistence["saved"]
            if type(saved) is int:
                persistence["saved"] = _definition(
                    persistence["saves"], saved, "$.data.persistence.saved")
            elif saved is None or isinstance(saved, Mapping):
                if isinstance(saved, Mapping) and (not isinstance(saved.get("sid"), str) or not saved["sid"]):
                    raise DecodeError("$.data.persistence.saved must be a save receipt")
                persistence["saved"] = _receipt_defaults(
                    saved, scope, "$.data.persistence.saved")
            else:
                raise DecodeError("$.data.persistence.saved must be a receipt, null, or integer index")
    _semantic_fields(result, "$.data")
    return result


@dataclass(frozen=True)
class WireResponse:
    """One validated protocol-8 response and its independently decoded copy."""

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
    try:
        return _decode_wire_response(value)
    except DecodeError as error:
        error.preserve_raw(value)
        raise


def _decode_wire_response(value: Mapping[str, Any]) -> WireResponse:
    source = _dict(value, "$")
    if type(source.get("v")) is not int or source.get("v") != PROTOCOL_VERSION:
        raise DecodeError("$.v must be integer protocol version 8")
    if not isinstance(source.get("id"), str) or not source["id"]:
        raise DecodeError("$.id must be a non-empty string")
    has_status, has_error = "st" in source, "err" in source
    if has_status == has_error:
        raise DecodeError("response must contain exactly one of st or err")
    if has_status and (not isinstance(source["st"], str) or source["st"] not in WIRE_STATUSES):
        raise DecodeError("$.st is not a protocol-8 response status")
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
    try:
        response = decode_wire_response(_dict(value, stage))
    except DecodeError as error:
        error.preserve_raw(value)
        if error.stage is None:
            error.stage = stage
        raise
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
    try:
        return _decode_client_response(value)
    except DecodeError as error:
        error.preserve_raw(value)
        if isinstance(value, Mapping) and "controller" in value:
            error.contexts.append({"controller": _copy(value.get("controller")),
                                   "stage": error.stage, "raw": _copy(value)})
        raise


def _decode_client_response(value: Mapping[str, Any]) -> ClientResponse:
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


_PARAMETERS = {
    "info": (), "state": ("src", "view"), "actions": ("view",),
    "req": ("rid", "get", "src"), "history": ("after", "limit", "until"),
    "events": ("after", "limit", "until"), "move": ("dir",),
    "cell": ("cell", "mode"), "item": ("loc",), "cancel": ("rid",),
    "click": ("ctl", "g"), "choose": ("ctl", "opt", "alt"), "select": ("ctl",),
    "text": ("ctl", "text", "submit"), "value": ("ctl", "value"),
    "scroll": ("ctl", "x", "y"), "bind_slot": ("ctl", "slot"),
    "bind_key": ("ctl", "keycode"), "zoom": ("zoom",), "pan": ("x", "y"),
    "wait": (), "rest": (), "search": (), "save": (), "quit": (),
    "back": (), "reveal": (), "untarget": (), "settle": ("rid", "timeout_ms"),
}


def _utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le", errors="surrogatepass")) // 2


def _intent_identifier(value: Any, field: str, maximum: int = 2**31 - 1) -> None:
    if not isinstance(value, str) or not value or _utf16_length(value) > maximum:
        raise IntentError(f"{field} must be a valid non-empty identifier")
    try:
        value.encode("utf-16-le")
    except UnicodeEncodeError as error:
        raise IntentError(f"{field} contains invalid Unicode") from error
    if any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value):
        raise IntentError(f"{field} contains a control character")


def _intent_integer(value: Any, field: str, minimum: int = -(2**31),
                    maximum: int = 2**31 - 1) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise IntentError(f"{field} must be a JSON integer from {minimum} to {maximum}")
    return value


def _intent_boolean(value: Any, field: str) -> None:
    if type(value) is not bool:
        raise IntentError(f"{field} must be a boolean")


def _intent_choice(value: Any, field: str, choices: Sequence[str]) -> None:
    if not isinstance(value, str) or value not in choices:
        raise IntentError(f"{field} has an unsupported value")


def _intent_number(value: Any, field: str) -> None:
    if type(value) not in (int, float):
        raise IntentError(f"{field} must be a finite number")
    try:
        valid = math.isfinite(value) and math.isfinite(struct.unpack("!f", struct.pack("!f", value))[0])
    except (OverflowError, struct.error):
        valid = False
    if not valid:
        raise IntentError(f"{field} must be finite in map-view coordinates")


def _validate_intent_syntax(source: Mapping[str, Any]) -> str:
    """Static peer of protocol RequestArguments; no current-frame or gameplay assumptions."""
    if "v" in source or "id" in source:
        raise IntentError("controller intents must not contain v or id")
    op = source.get("op")
    if not isinstance(op, str) or op not in _PARAMETERS:
        raise IntentError("intent.op is not a known controller operation")
    envelope = {"op"} if op == "settle" else {"op", "s", "rev"}
    if set(source) - envelope - set(_PARAMETERS[op]):
        raise IntentError(f"intent contains an unsupported field for {op}")
    for field in ("s", "rev"):
        if field in source:
            _intent_identifier(source[field], field, 256)
    if op == "settle":
        if "rid" in source:
            _intent_identifier(source["rid"], "rid", 128)
        if "timeout_ms" in source:
            _intent_integer(source["timeout_ms"], "timeout_ms", 1, 5000)
        return op
    if "ctl" in _PARAMETERS[op]:
        _intent_identifier(source.get("ctl"), "ctl")
    if "src" in source:
        _intent_boolean(source["src"], "src")
    if "view" in source:
        _intent_choice(source["view"], "view", ("play", "full"))
    if op == "move":
        _intent_choice(source.get("dir"), "dir", tuple(DIRECTIONS))
    elif op == "cell":
        _intent_integer(source.get("cell"), "cell", 0)
        if "mode" in source:
            _intent_choice(source["mode"], "mode", ("act", "examine", "context"))
    elif op == "item":
        _intent_identifier(source.get("loc"), "loc")
    elif op in ("cancel", "req"):
        _intent_identifier(source.get("rid"), "rid", 128)
    elif op == "click" and "g" in source:
        _intent_choice(source["g"], "g", ("click", "right", "middle", "long"))
    elif op == "choose":
        _intent_integer(source.get("opt"), "opt", 0)
        if "alt" in source:
            _intent_boolean(source["alt"], "alt")
    elif op == "text":
        if not isinstance(source.get("text"), str):
            raise IntentError("text must be a string")
        if "submit" in source:
            _intent_boolean(source["submit"], "submit")
    elif op in ("value", "zoom"):
        _intent_integer(source.get(op), op)
    elif op == "bind_slot":
        _intent_integer(source.get("slot"), "slot", 1, 3)
    elif op == "bind_key":
        _intent_integer(source.get("keycode"), "keycode", 1)
    elif op in ("pan", "scroll"):
        for axis in ("x", "y"):
            if axis in source:
                _intent_number(source[axis], axis)
    elif op in ("history", "events"):
        for cursor in ("after", "until"):
            if cursor in source:
                _intent_integer(source[cursor], cursor, 0, 2**63 - 1)
        if "limit" in source:
            _intent_integer(source["limit"], "limit", 1, 100)
    if "get" in source:
        details = source["get"]
        if not isinstance(details, list):
            raise IntentError("get must be an array")
        for detail in details:
            _intent_choice(detail, "get entry", ("raw", "reply", "before", "after", "meta"))
        if len(set(details)) != len(details):
            raise IntentError("get must not contain duplicates")
    return op


def _descriptor_accepts(op: str, descriptor: Mapping[str, Any], node: Optional[Mapping[str, Any]],
                        source: Mapping[str, Any]) -> bool:
    """Check a complete advertised alternative without merging separate capabilities."""
    if "ctl" in descriptor and descriptor["ctl"] != source.get("ctl"):
        return False
    if op == "click":
        gestures = descriptor.get("gestures", ["click"])
        if not isinstance(gestures, list) or not all(isinstance(value, str) for value in gestures):
            raise IntentError("advertised click gestures are invalid")
        return source.get("g", "click") in gestures
    if op == "cell":
        modes = descriptor.get("modes")
        if not isinstance(modes, list) or not all(isinstance(value, str) for value in modes):
            raise IntentError("advertised cell modes are invalid")
        return source.get("mode", "act") in modes
    if op == "choose":
        options = descriptor.get("options")
        if not isinstance(options, list) or not all(isinstance(value, str) for value in options):
            raise IntentError("advertised choice options are missing or invalid")
        if node is None or node.get("options") != options:
            raise IntentError("advertised choice options contradict this node")
        return source["opt"] < len(options)
    if op == "value":
        bounds = descriptor.get("range")
        if not isinstance(bounds, list) or len(bounds) != 2:
            raise IntentError("advertised slider range is missing or invalid")
        lower, upper = bounds
        if type(lower) is not int or type(upper) is not int or lower > upper:
            raise IntentError("advertised slider range is invalid")
        if node is None or type(node.get("min")) is not int or type(node.get("max")) is not int:
            raise IntentError("current slider bounds are missing or invalid")
        if node["min"] > node["max"] or lower < node["min"] or upper > node["max"]:
            raise IntentError("advertised slider range contradicts node bounds")
        return lower <= source["value"] <= upper
    if op == "zoom":
        lower, upper = descriptor.get("min"), descriptor.get("max")
        if type(lower) is not int or type(upper) is not int or lower > upper:
            raise IntentError("advertised zoom bounds are missing or invalid")
        return lower <= source["zoom"] <= upper
    if op == "bind_slot":
        slots = descriptor.get("slots")
        if not isinstance(slots, list) or not slots or any(type(value) is not int or value not in (1, 2, 3) for value in slots):
            raise IntentError("advertised binding slots are missing or invalid")
        node_slots = node.get("binding_slots") if node is not None else None
        if not isinstance(node_slots, list) or any(type(value) is not int or value not in (1, 2, 3) for value in node_slots):
            raise IntentError("current binding slots are missing or invalid")
        if not set(slots) <= set(node_slots):
            raise IntentError("advertised binding slots contradict this node")
        return source["slot"] in slots
    if op == "bind_key":
        if node is None or node.get("binding_input") is not True:
            raise IntentError("current node is not a binding input")
        # Keyboard/controller membership is intentionally not inferred from hidden state.
    if op == "text":
        maximum = node.get("max_length") if node is not None else None
        multiline = node.get("multiline") if node is not None else None
        submit_supported = descriptor.get("submit_supported")
        if type(maximum) is not int or type(multiline) is not bool or type(submit_supported) is not bool:
            raise IntentError("current text input constraints are missing or invalid")
        if multiline and submit_supported:
            raise IntentError("advertised submit support contradicts multiline input")
        text = source["text"]
        return ((maximum <= 0 or _utf16_length(text) <= maximum)
                and (multiline or not any(char in text for char in "\r\n"))
                and (not source.get("submit", False) or (submit_supported and not multiline)))
    return True


def validate_intent(current_frame: Any, intent: Mapping[str, Any]) -> IntentValidation:
    """Validate one controller action against exactly one displayed frame.

    This function never changes or sends the intent.  A decoded map cell is
    returned as evidence only; terrain is not interpreted as passable, safe, or a
    promise that the hero will move.  Read-only queries and local ``settle`` do
    not need a current frame; they are returned unchanged after rejecting the
    controller-owned ``v``/``id`` fields.
    """
    if not isinstance(intent, Mapping):
        raise IntentError("intent must be an object")
    source = intent
    op = _validate_intent_syntax(source)
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

    advertised = [descriptor for descriptor in advertised
                  if _descriptor_accepts(op, descriptor, node, source)]
    if not advertised:
        raise IntentError("intent does not satisfy a complete currently advertised operation")

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
