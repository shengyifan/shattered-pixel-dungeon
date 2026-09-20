#!/usr/bin/env python3
"""Offline, same-frame structural codec experiment for public CLI responses.

This is deliberately not a protocol implementation.  Every packet contains all
of its definitions, and ``decode(encode(frame))`` recreates the input JSON value
without consulting another response.  Raw/history payloads and public source
ASTs are carried as opaque values.

The experiment targets structure that remains after protocol-6 compaction:

* packed ``data.ui.nodes`` rows are grouped by their existing ``node_shapes``
  index, with columns that are identical inside a group stored once;
* lists of object records use local, ordered field-name shapes;
* ``data.acts`` entries may refer to an exact operation in this frame's
  ``data.ui.op_defs`` (including the exact position/value of an added ``ctl``);
* long item labels and child text may use exact, checked same-frame references;
* repeated compound values may use a frame-local value table.  Plain strings
  are intentionally not dictionary encoded here so a separate string-dictionary
  experiment can be measured independently.

No field is filtered, inferred from words, or filled from a prior frame.
"""

import copy
import json
from collections import Counter
from dataclasses import dataclass


CODEC = "standalone-ui-v1"
MARKER = "$ui"

# These values can contain original wire replies or public provenance ASTs.
# They remain under their original field names and are copied without traversal,
# which also lets another composed codec recognize the same opaque boundary.
OPAQUE_KEYS = {
    "history",
    "events",
    "outcome",
    "late_responses",
    "original_response",
    "source",
    "sources",
    "text_diagnostics",
    "raw",
    "reply",
    "schema",
    "raw_request",
    "raw_bytes",
    "request_json",
    "response_json",
    "original",
    "original_payload",
    "text_sources",
    "text_origins",
    "text_diagnostics",
    "pres",
    "presentation",
    "preserved_cells",
    "before",
    "after",
}

STRUCTURAL_KEYS = {"nodes", "node_shapes", "op_defs", "acts", "inv"}


class CodecError(ValueError):
    """The experimental packet is malformed or has an unbound local reference."""


@dataclass(frozen=True)
class _InventoryName:
    index: int


@dataclass(frozen=True)
class _ParentText:
    parent_index: int
    start: int
    length: int


@dataclass(frozen=True)
class _Operation:
    definition: int
    operation: int
    ctl_position: object = None
    ctl_value: object = None


@dataclass(frozen=True)
class _NodeOperations:
    entries: object


def _json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def _fingerprint(value):
    """Identity used by the codec, including object insertion order and number text."""
    return _json(value)


def _validate_json(value, path="$"):
    if value is None or isinstance(value, (str, bool, int)):
        return
    if isinstance(value, float):
        # json.dumps is the authoritative finite-number check.
        try:
            _json(value)
        except ValueError as error:
            raise CodecError("non-finite number at " + path) from error
        return
    if isinstance(value, list):
        for index, child in enumerate(value):
            _validate_json(child, "%s[%d]" % (path, index))
        return
    if isinstance(value, dict):
        for key, child in value.items():
            if not isinstance(key, str):
                raise CodecError("non-string object key at " + path)
            _validate_json(child, path + "." + key)
        return
    raise CodecError("non-JSON value at " + path)


def _marker(tag, *parts):
    return {MARKER: [tag, *parts]}


def _is_marker(value):
    return isinstance(value, dict) and list(value) == [MARKER] and isinstance(value[MARKER], list)


def _ordered_without(mapping, field):
    return [(key, value) for key, value in mapping.items() if key != field]


def _contains_opaque_key(value):
    if isinstance(value, dict):
        return any(key in OPAQUE_KEYS or _contains_opaque_key(child)
                   for key, child in value.items())
    if isinstance(value, list):
        return any(_contains_opaque_key(child) for child in value)
    return False


class _Encoder:
    def __init__(self, frame, measure=None):
        self.frame = frame
        if measure is not None and not callable(measure):
            raise CodecError("measure must be callable")
        self.measure = measure
        self.value_candidates = []
        self.value_indexes = {}
        self.inventory = self._inventory_index(frame)
        self.operation_definitions = self._operation_definitions(frame)
        self._collect_value_definitions()

    def _cost(self, value):
        text = _json(value)
        cost = self.measure(text) if self.measure is not None else len(text.encode("utf-8"))
        if isinstance(cost, bool) or not isinstance(cost, (int, float)) or cost < 0:
            raise CodecError("measure must return a non-negative number")
        return cost

    @staticmethod
    def _data(frame):
        return frame.get("data") if isinstance(frame, dict) and isinstance(frame.get("data"), dict) else {}

    def _inventory_index(self, frame):
        items = self._data(frame).get("inv")
        if not isinstance(items, list):
            return {}
        found, ambiguous = {}, set()
        for index, item in enumerate(items):
            if not isinstance(item, dict) or not isinstance(item.get("loc"), str):
                continue
            locator = item["loc"]
            if locator in found:
                ambiguous.add(locator)
            found[locator] = index
        for locator in ambiguous:
            found.pop(locator, None)
        return found

    def _operation_definitions(self, frame):
        ui = self._data(frame).get("ui")
        definitions = ui.get("op_defs") if isinstance(ui, dict) else None
        return definitions if isinstance(definitions, list) else []

    def _collect_value_definitions(self):
        counts = Counter()
        first = {}

        def visit(value, path):
            if isinstance(value, dict):
                for key, child in value.items():
                    child_path = path + (key,)
                    if (key in OPAQUE_KEYS or key in {"node_shapes", "op_defs"}
                            or path == ("data",) and key == "map"):
                        continue
                    consider(child, child_path)
            elif isinstance(value, list):
                for index, child in enumerate(value):
                    consider(child, path + (index,))

        def consider(value, path):
            if (isinstance(value, (dict, list)) and path and path[-1] not in STRUCTURAL_KEYS
                    and not _contains_opaque_key(value)):
                encoded = _fingerprint(value)
                # Intern only compound values large enough to beat an explicit
                # local reference.  Plain text is left to the string experiment.
                if len(encoded.encode("utf-8")) >= 24:
                    counts[encoded] += 1
                    first.setdefault(encoded, copy.deepcopy(value))
            visit(value, path)

        visit(self.frame, ())
        for identity, count in counts.items():
            value = first[identity]
            index = len(self.value_candidates)
            reference = _marker("value", index)
            raw_cost = count * self._cost(value)
            definition_cost = self._cost([value]) - self._cost([])
            encoded_cost = definition_cost + count * self._cost(reference)
            if count >= 2 and encoded_cost < raw_cost:
                self.value_indexes[identity] = len(self.value_candidates)
                self.value_candidates.append(value)

    def encode(self):
        encoded = self._encode(self.frame, ())
        encoded, values = self._prune_values(encoded)
        return {"$codec": CODEC, "defs": {"values": values}, "frame": encoded}

    def _value_reference(self, value, path):
        if not isinstance(value, (dict, list)) or not path or path[-1] in STRUCTURAL_KEYS:
            return None
        identity = _fingerprint(value)
        index = self.value_indexes.get(identity)
        if index is None:
            return None
        return _marker("value", index)

    def _encode(self, value, path):
        reference = self._value_reference(value, path)
        if reference is not None:
            return reference
        if isinstance(value, list):
            if path == ("data", "acts"):
                return self._encode_actions(value, path)
            if path == ("data", "inv") and len(value) >= 2 \
                    and all(isinstance(item, dict) for item in value):
                return self._encode_records(value, path)
            return [self._encode(child, path + (index,)) for index, child in enumerate(value)]
        if isinstance(value, dict):
            return self._encode_mapping(value, path)
        return copy.deepcopy(value)

    def _encode_mapping(self, value, path):
        pairs = []
        for key, child in value.items():
            child_path = path + (key,)
            if path == ("data",) and key == "map":
                # A separately measured map codec runs after this one.  Keep
                # the map object visible and structurally untouched so it can
                # still recognize w/h/types/rows.  Our decoder applies the same
                # literal boundary instead of interpreting marker-like map data.
                encoded = copy.deepcopy(child)
            elif key in OPAQUE_KEYS:
                encoded = copy.deepcopy(child)
            elif path == ("data", "ui") and key in {"node_shapes", "op_defs"}:
                # Keep the protocol's dictionaries literal; only nodes/actions
                # refer to them in this experiment.
                encoded = self._escape_markers(child)
            elif path == ("data", "ui") and key == "nodes" and isinstance(child, list):
                encoded = self._encode_nodes(child, value.get("node_shapes"), child_path)
            else:
                encoded = self._encode(child, child_path)
            pairs.append((key, encoded))
        return self._object_from_pairs(pairs)

    def _escape_markers(self, value):
        """Protect reserved-key collisions without otherwise restructuring a value."""
        if isinstance(value, list):
            return [self._escape_markers(child) for child in value]
        if isinstance(value, dict):
            pairs = [(key, self._escape_markers(child)) for key, child in value.items()]
            return self._object_from_pairs(pairs)
        return copy.deepcopy(value)

    @staticmethod
    def _object_from_pairs(pairs):
        if any(key == MARKER for key, _ in pairs):
            return _marker("object", [[key, value] for key, value in pairs])
        return {key: value for key, value in pairs}

    def _encode_records(self, records, path, field_encoder=None):
        encoded_records, shapes, shape_indexes, rows = [], [], {}, []
        for index, record in enumerate(records):
            keys = tuple(record.keys())
            shape = shape_indexes.get(keys)
            if shape is None:
                shape = len(shapes)
                shape_indexes[keys] = shape
                shapes.append(list(keys))
            values = []
            for key, child in record.items():
                child_path = path + (index, key)
                values.append(field_encoder(index, record, key, child, child_path)
                              if field_encoder else self._encode(child, child_path))
            encoded_records.append(self._object_from_pairs(list(zip(keys, values))))
            rows.append([shape, *values])
        if any(any(key in OPAQUE_KEYS for key in record) for record in records):
            return encoded_records
        candidate = _marker("rows", shapes, rows)
        return candidate if self._cost(candidate) < self._cost(encoded_records) else encoded_records

    def _match_operation(self, action):
        if not isinstance(action, dict):
            return None
        action_items = list(action.items())
        for definition_index, definition in enumerate(self.operation_definitions):
            if not isinstance(definition, list):
                continue
            for operation_index, operation in enumerate(definition):
                if not isinstance(operation, dict):
                    continue
                operation_items = list(operation.items())
                if action_items == operation_items:
                    return ("same", definition_index, operation_index)
                if "ctl" in action and "ctl" not in operation:
                    without = _ordered_without(action, "ctl")
                    if without == operation_items:
                        position = list(action).index("ctl")
                        return ("add_ctl", definition_index, operation_index,
                                position, action["ctl"])
        return None

    def _encode_actions(self, actions, path):
        if len(actions) < 1 or not all(isinstance(action, dict) for action in actions):
            return [self._encode(child, path + (index,)) for index, child in enumerate(actions)]
        baseline = self._encode_records(actions, path)
        entries, references = [], 0
        for index, action in enumerate(actions):
            match = self._match_operation(action)
            if match is None:
                entries.append(["raw", self._encode_mapping(action, path + (index,))])
            elif match[0] == "same":
                references += 1
                entries.append(["same", match[1], match[2]])
            else:
                references += 1
                entries.append(["add_ctl", match[1], match[2], match[3],
                                self._encode(match[4], path + (index, "ctl"))])
        candidate = _marker("actions", entries)
        return candidate if references and self._cost(candidate) < self._cost(baseline) else baseline

    @staticmethod
    def _node_views(nodes, shapes):
        views = []
        for node in nodes:
            if isinstance(node, dict):
                views.append(node)
                continue
            if (isinstance(node, list) and node and type(node[0]) is int
                    and isinstance(shapes, list) and 0 <= node[0] < len(shapes)):
                shape = shapes[node[0]]
                if (isinstance(shape, list) and len(shape) == len(node) - 1
                        and all(isinstance(field, str) for field in shape)
                        and len(set(shape)) == len(shape)):
                    views.append(dict(zip(shape, node[1:])))
                    continue
            views.append(None)
        return views

    def _node_field_encoder(self, nodes, shapes):
        views = self._node_views(nodes, shapes)
        identifiers, ambiguous = {}, set()
        for index, view in enumerate(views):
            if not isinstance(view, dict) or not isinstance(view.get("id"), str):
                continue
            identifier = view["id"]
            if identifier in identifiers:
                ambiguous.add(identifier)
            identifiers[identifier] = index
        for identifier in ambiguous:
            identifiers.pop(identifier, None)

        data = self._data(self.frame)
        inventory = data.get("inv") if isinstance(data.get("inv"), list) else []
        actions = data.get("acts") if isinstance(data.get("acts"), list) else []

        def operation_reference(node, operations, path):
            if not isinstance(operations, list) or not all(isinstance(op, dict) for op in operations):
                return None
            entries = []
            for operation in operations:
                operation_items = list(operation.items())
                match = None
                for action_index, action in enumerate(actions):
                    if not isinstance(action, dict):
                        continue
                    if list(action.items()) == operation_items:
                        match = [action_index, 0]
                        break
                    if ("ctl" in action and action.get("ctl") == node.get("id")
                            and _ordered_without(action, "ctl") == operation_items):
                        match = [action_index, 1]
                        break
                if match is None:
                    return None
                entries.append(match)
            candidate = _marker("node_ops", entries)
            baseline = self._encode(operations, path)
            return candidate if self._cost(candidate) < self._cost(baseline) else baseline

        def encode(index, _record, field, value, path):
            view = views[index]
            if field == "ops" and isinstance(view, dict):
                candidate = operation_reference(view, value, path)
                if candidate is not None:
                    return candidate
            if field == "label" and isinstance(value, str) and isinstance(view, dict):
                locator = view.get("loc")
                item_index = self.inventory.get(locator)
                if item_index is not None and item_index < len(inventory):
                    item = inventory[item_index]
                    if isinstance(item, dict) and item.get("name") == value:
                        candidate = _marker("inventory_name", item_index)
                        if self._cost(candidate) < self._cost(value):
                            return candidate
            if field == "text" and isinstance(value, str) and isinstance(view, dict):
                parent_index = identifiers.get(view.get("parent"))
                if parent_index is not None and parent_index != index:
                    parent = views[parent_index]
                    parent_text = parent.get("text") if isinstance(parent, dict) else None
                    if isinstance(parent_text, str):
                        start = parent_text.find(value)
                        if start >= 0:
                            candidate = _marker("parent_text", parent_index, start, len(value))
                            if self._cost(candidate) < self._cost(value):
                                return candidate
            return self._encode(value, path)

        return encode, views

    def _encode_nodes(self, nodes, shapes, path):
        field_encoder, views = self._node_field_encoder(nodes, shapes)
        if len(nodes) >= 2 and all(isinstance(node, dict) for node in nodes):
            return self._encode_records(nodes, path, field_encoder)

        baseline, packed = [], []
        groups = {}
        for index, node in enumerate(nodes):
            view = views[index]
            if isinstance(node, dict):
                pairs = [(key, field_encoder(index, node, key, value,
                                             path + (index, key)))
                         for key, value in node.items()]
                encoded = self._object_from_pairs(pairs)
                baseline.append(encoded)
                packed.append(None)
                continue
            valid = (isinstance(node, list) and view is not None and node
                     and type(node[0]) is int)
            if not valid:
                encoded = self._encode(node, path + (index,))
                baseline.append(encoded)
                packed.append(None)
                continue
            shape = shapes[node[0]]
            encoded = [copy.deepcopy(node[0])]
            for offset, (field, value) in enumerate(zip(shape, node[1:]), 1):
                encoded.append(field_encoder(index, view, field, value,
                                             path + (index, offset)))
            baseline.append(encoded)
            packed.append(encoded)
            groups.setdefault(node[0], []).append(index)

        definitions, definition_by_shape, variable_columns = [], {}, []
        for shape_index, indexes in groups.items():
            if len(indexes) < 2:
                continue
            width = len(packed[indexes[0]])
            if any(len(packed[index]) != width for index in indexes):
                continue
            common, variables = [], []
            for position in range(1, width):
                values = [packed[index][position] for index in indexes]
                if all(_fingerprint(value) == _fingerprint(values[0]) for value in values[1:]):
                    common.append([position, values[0]])
                else:
                    variables.append(position)
            definition_by_shape[shape_index] = len(definitions)
            # Positions and values alternate.  The variable count plus the
            # common count recovers the exact original width, so neither the
            # width nor the complementary position list must be repeated.
            flat_common = []
            for position, value in common:
                flat_common.extend([position, value])
            definitions.append([shape_index, len(variables), flat_common])
            variable_columns.append(variables)

        if not definitions:
            return baseline
        entries = []
        for index, encoded in enumerate(packed):
            if encoded is None or encoded[0] not in definition_by_shape:
                entries.append(["raw", baseline[index]])
                continue
            definition_index = definition_by_shape[encoded[0]]
            variables = variable_columns[definition_index]
            entries.append([definition_index, *[encoded[position] for position in variables]])
        candidate = _marker("packed_nodes", definitions, entries)
        return candidate if self._cost(candidate) < self._cost(baseline) else baseline

    def _prune_values(self, encoded):
        referenced = set()

        def scan(value, path=()):
            if path == ("data", "map"):
                return
            if _is_marker(value):
                parts = value[MARKER]
                if parts and parts[0] == "value" and len(parts) == 2 \
                        and type(parts[1]) is int:
                    referenced.add(parts[1])
                    return
                if parts and parts[0] == "object" and len(parts) == 2 \
                        and isinstance(parts[1], list):
                    for pair in parts[1]:
                        if isinstance(pair, list) and len(pair) == 2 and isinstance(pair[0], str):
                            if (pair[0] not in OPAQUE_KEYS
                                    and not (path == ("data", "ui")
                                             and pair[0] in {"node_shapes", "op_defs"})):
                                scan(pair[1], path + (pair[0],))
                    return
            if isinstance(value, list):
                for index, child in enumerate(value):
                    scan(child, path + (index,))
            elif isinstance(value, dict):
                for key, child in value.items():
                    if (key not in OPAQUE_KEYS
                            and not (path == ("data", "ui")
                                     and key in {"node_shapes", "op_defs"})):
                        scan(child, path + (key,))

        scan(encoded)
        used = sorted(referenced)
        remap = {old: new for new, old in enumerate(used)}

        def rewrite(value, path=()):
            if path == ("data", "map"):
                return copy.deepcopy(value)
            if _is_marker(value):
                parts = value[MARKER]
                if parts and parts[0] == "value":
                    old = parts[1]
                    if old not in remap:
                        raise CodecError("internal unused value reference")
                    return _marker("value", remap[old])
                if parts and parts[0] == "object" and len(parts) == 2 \
                        and isinstance(parts[1], list):
                    return _marker("object", [[pair[0], (copy.deepcopy(pair[1])
                                                        if pair[0] in OPAQUE_KEYS
                                                        or path == ("data", "ui")
                                                        and pair[0] in {"node_shapes", "op_defs"}
                                                        else rewrite(pair[1], path + (pair[0],)))]
                                               for pair in parts[1]])
            if isinstance(value, list):
                return [rewrite(child, path + (index,)) for index, child in enumerate(value)]
            if isinstance(value, dict):
                return {key: (copy.deepcopy(child) if key in OPAQUE_KEYS
                              or path == ("data", "ui")
                              and key in {"node_shapes", "op_defs"}
                              else rewrite(child, path + (key,)))
                        for key, child in value.items()}
            return copy.deepcopy(value)

        return rewrite(encoded), [copy.deepcopy(self.value_candidates[index]) for index in used]


class _Decoder:
    def __init__(self, packet):
        if not isinstance(packet, dict) or set(packet) != {"$codec", "defs", "frame"}:
            raise CodecError("invalid codec envelope")
        if packet.get("$codec") != CODEC:
            raise CodecError("unsupported codec")
        definitions = packet.get("defs")
        if not isinstance(definitions, dict) or set(definitions) != {"values"}:
            raise CodecError("invalid definitions")
        if not isinstance(definitions["values"], list):
            raise CodecError("invalid value definitions")
        self.values = definitions["values"]

    @staticmethod
    def _index(value, limit, what):
        if type(value) is not int or value < 0 or value >= limit:
            raise CodecError("invalid %s index" % what)
        return value

    def decode(self, encoded):
        value = self._decode(encoded, ())
        self._resolve(value)
        if self._has_pending(value):
            raise CodecError("unbound semantic reference")
        return value

    def _decode(self, value, path=()):
        if isinstance(value, list):
            return [self._decode(child, path + (index,)) for index, child in enumerate(value)]
        if not isinstance(value, dict):
            return copy.deepcopy(value)
        if not _is_marker(value):
            return {key: (copy.deepcopy(child) if key in OPAQUE_KEYS
                          or path == ("data",) and key == "map"
                          else self._decode(child, path + (key,)))
                    for key, child in value.items()}
        parts = value[MARKER]
        if not parts or not isinstance(parts[0], str):
            raise CodecError("invalid marker")
        tag = parts[0]
        if tag == "value":
            if len(parts) != 2:
                raise CodecError("invalid value marker")
            return copy.deepcopy(self.values[self._index(parts[1], len(self.values), "value")])
        if tag == "object":
            if len(parts) != 2 or not isinstance(parts[1], list):
                raise CodecError("invalid object marker")
            result = {}
            for pair in parts[1]:
                if (not isinstance(pair, list) or len(pair) != 2
                        or not isinstance(pair[0], str) or pair[0] in result):
                    raise CodecError("invalid object member")
                result[pair[0]] = (copy.deepcopy(pair[1])
                                   if pair[0] in OPAQUE_KEYS
                                   or path == ("data",) and pair[0] == "map"
                                   else self._decode(pair[1], path + (pair[0],)))
            return result
        if tag == "rows":
            if len(parts) != 3:
                raise CodecError("invalid row marker")
            return self._decode_rows(parts[1], parts[2])
        if tag == "packed_nodes":
            if len(parts) != 3:
                raise CodecError("invalid packed-node marker")
            return self._decode_packed_nodes(parts[1], parts[2])
        if tag == "actions":
            if len(parts) != 2 or not isinstance(parts[1], list):
                raise CodecError("invalid actions marker")
            return [self._decode_action(entry, path + (index,))
                    for index, entry in enumerate(parts[1])]
        if tag == "inventory_name":
            if len(parts) != 2 or type(parts[1]) is not int or parts[1] < 0:
                raise CodecError("invalid inventory-name marker")
            return _InventoryName(parts[1])
        if tag == "parent_text":
            if (len(parts) != 4 or any(type(item) is not int for item in parts[1:])
                    or any(item < 0 for item in parts[1:])):
                raise CodecError("invalid parent-text marker")
            return _ParentText(parts[1], parts[2], parts[3])
        if tag == "node_ops":
            if len(parts) != 2 or not isinstance(parts[1], list):
                raise CodecError("invalid node-operations marker")
            entries = []
            for entry in parts[1]:
                if (not isinstance(entry, list) or len(entry) != 2
                        or type(entry[0]) is not int or entry[0] < 0
                        or entry[1] not in (0, 1) or type(entry[1]) is not int):
                    raise CodecError("invalid node-operation reference")
                entries.append((entry[0], entry[1]))
            return _NodeOperations(tuple(entries))
        raise CodecError("unknown marker tag: " + tag)

    def _decode_rows(self, shapes, rows):
        if not isinstance(shapes, list) or not isinstance(rows, list):
            raise CodecError("invalid row table")
        checked = []
        for shape in shapes:
            if (not isinstance(shape, list) or not all(isinstance(field, str) for field in shape)
                    or len(set(shape)) != len(shape)):
                raise CodecError("invalid record shape")
            checked.append(shape)
        result = []
        for row in rows:
            if not isinstance(row, list) or not row:
                raise CodecError("invalid record row")
            shape = checked[self._index(row[0], len(checked), "shape")]
            if len(row) != len(shape) + 1:
                raise CodecError("record row width mismatch")
            result.append({field: self._decode(child) for field, child in zip(shape, row[1:])})
        return result

    def _decode_packed_nodes(self, definitions, entries):
        if not isinstance(definitions, list) or not isinstance(entries, list):
            raise CodecError("invalid packed-node table")
        checked = []
        for definition in definitions:
            if not isinstance(definition, list) or len(definition) != 3:
                raise CodecError("invalid packed-node definition")
            shape, variable_count, common = definition
            if (type(shape) is not int or type(variable_count) is not int
                    or variable_count < 0):
                raise CodecError("invalid packed-node shape")
            if not isinstance(common, list) or len(common) % 2:
                raise CodecError("invalid packed-node columns")
            common_values, positions = {}, set()
            width = 1 + variable_count + len(common) // 2
            for offset in range(0, len(common), 2):
                position, child = common[offset], common[offset + 1]
                if (type(position) is not int or position < 1
                        or position >= width or position in positions):
                    raise CodecError("invalid common packed-node column")
                positions.add(position)
                common_values[position] = self._decode(child)
            variables = [position for position in range(1, width) if position not in positions]
            if len(variables) != variable_count:
                raise CodecError("invalid variable packed-node columns")
            checked.append((shape, width, common_values, variables))
        result = []
        for entry in entries:
            if not isinstance(entry, list) or not entry:
                raise CodecError("invalid packed-node entry")
            if entry[0] == "raw":
                if len(entry) != 2:
                    raise CodecError("invalid raw packed-node entry")
                result.append(self._decode(entry[1]))
                continue
            definition = checked[self._index(entry[0], len(checked), "packed-node definition")]
            shape, width, common, variables = definition
            if len(entry) != len(variables) + 1:
                raise CodecError("packed-node row width mismatch")
            row = [None] * width
            row[0] = shape
            for position, child in common.items():
                row[position] = copy.deepcopy(child)
            for position, child in zip(variables, entry[1:]):
                row[position] = self._decode(child)
            result.append(row)
        return result

    def _decode_action(self, entry, path=()):
        if not isinstance(entry, list) or not entry:
            raise CodecError("invalid action entry")
        if entry[0] == "raw":
            if len(entry) != 2:
                raise CodecError("invalid raw action")
            return self._decode(entry[1], path)
        if entry[0] == "same":
            if len(entry) != 3 or type(entry[1]) is not int or type(entry[2]) is not int:
                raise CodecError("invalid operation reference")
            return _Operation(entry[1], entry[2])
        if entry[0] == "add_ctl":
            if (len(entry) != 5 or type(entry[1]) is not int or type(entry[2]) is not int
                    or type(entry[3]) is not int or entry[3] < 0):
                raise CodecError("invalid ctl operation reference")
            return _Operation(entry[1], entry[2], entry[3], self._decode(entry[4], path + ("ctl",)))
        raise CodecError("unknown action entry")

    def _resolve(self, frame):
        if not isinstance(frame, dict):
            return
        data = frame.get("data")
        if not isinstance(data, dict):
            return
        ui = data.get("ui")
        self._resolve_actions(data, ui)
        self._resolve_nodes(data, ui)

    def _operation(self, ui, reference):
        definitions = ui.get("op_defs") if isinstance(ui, dict) else None
        if not isinstance(definitions, list):
            raise CodecError("operation reference without op_defs")
        definition_index = self._index(reference.definition, len(definitions), "operation definition")
        definition = definitions[definition_index]
        if not isinstance(definition, list):
            raise CodecError("invalid operation definition")
        operation_index = self._index(reference.operation, len(definition), "operation")
        operation = definition[operation_index]
        if not isinstance(operation, dict):
            raise CodecError("referenced operation is not an object")
        return operation

    def _resolve_actions(self, data, ui):
        actions = data.get("acts")
        if not isinstance(actions, list):
            return
        for index, action in enumerate(actions):
            if not isinstance(action, _Operation):
                continue
            base = self._operation(ui, action)
            if action.ctl_position is None:
                actions[index] = copy.deepcopy(base)
                continue
            if "ctl" in base or action.ctl_position > len(base):
                raise CodecError("invalid ctl insertion")
            items = list(base.items())
            items.insert(action.ctl_position, ("ctl", copy.deepcopy(action.ctl_value)))
            actions[index] = dict(items)

    @staticmethod
    def _resolved_node_views(nodes, shapes):
        views = []
        for node in nodes:
            if isinstance(node, dict):
                views.append((node, None))
                continue
            if (isinstance(node, list) and node and type(node[0]) is int
                    and isinstance(shapes, list) and 0 <= node[0] < len(shapes)):
                shape = shapes[node[0]]
                if (isinstance(shape, list) and len(shape) == len(node) - 1
                        and all(isinstance(field, str) for field in shape)
                        and len(set(shape)) == len(shape)):
                    views.append((dict(zip(shape, node[1:])), {field: offset + 1
                                                              for offset, field in enumerate(shape)}))
                    continue
            views.append((None, None))
        return views

    @staticmethod
    def _set_node_field(nodes, views, index, field, value):
        view, positions = views[index]
        if positions is None:
            view[field] = value
        else:
            nodes[index][positions[field]] = value
            view[field] = value

    def _resolve_nodes(self, data, ui):
        if not isinstance(ui, dict) or not isinstance(ui.get("nodes"), list):
            return
        nodes, shapes = ui["nodes"], ui.get("node_shapes")
        views = self._resolved_node_views(nodes, shapes)
        inventory = data.get("inv") if isinstance(data.get("inv"), list) else []
        actions = data.get("acts") if isinstance(data.get("acts"), list) else []

        for index, (view, _positions) in enumerate(views):
            if not isinstance(view, dict):
                continue
            operations = view.get("ops")
            if isinstance(operations, _NodeOperations):
                restored = []
                for action_index, mode in operations.entries:
                    action_index = self._index(action_index, len(actions), "node operation action")
                    action = actions[action_index]
                    if not isinstance(action, dict):
                        raise CodecError("node operation references a non-object action")
                    if mode == 0:
                        restored.append(copy.deepcopy(action))
                    else:
                        if "ctl" not in action or action.get("ctl") != view.get("id"):
                            raise CodecError("node operation has an unbound ctl")
                        restored.append(dict(_ordered_without(action, "ctl")))
                self._set_node_field(nodes, views, index, "ops", restored)
            label = view.get("label")
            if isinstance(label, _InventoryName):
                item_index = self._index(label.index, len(inventory), "inventory")
                item = inventory[item_index]
                if (not isinstance(item, dict) or not isinstance(item.get("name"), str)
                        or view.get("loc") != item.get("loc")):
                    raise CodecError("unbound inventory-name reference")
                self._set_node_field(nodes, views, index, "label", item["name"])

        resolving = set()

        def resolve_text(index):
            view, _positions = views[index]
            if not isinstance(view, dict):
                return None
            value = view.get("text")
            if not isinstance(value, _ParentText):
                return value
            if index in resolving:
                raise CodecError("cyclic parent-text reference")
            parent_index = self._index(value.parent_index, len(views), "parent node")
            parent, _ = views[parent_index]
            if (not isinstance(parent, dict) or view.get("parent") != parent.get("id")):
                raise CodecError("unbound parent-text reference")
            resolving.add(index)
            parent_text = resolve_text(parent_index)
            resolving.remove(index)
            if not isinstance(parent_text, str) or value.start + value.length > len(parent_text):
                raise CodecError("invalid parent-text slice")
            restored = parent_text[value.start:value.start + value.length]
            self._set_node_field(nodes, views, index, "text", restored)
            return restored

        for index in range(len(views)):
            resolve_text(index)

    def _has_pending(self, value):
        if isinstance(value, (_InventoryName, _ParentText, _Operation, _NodeOperations)):
            return True
        if isinstance(value, list):
            return any(self._has_pending(child) for child in value)
        if isinstance(value, dict):
            return any(self._has_pending(child) for child in value.values())
        return False


def encode(frame, measure=None):
    """Return a standalone packet; ``measure(minified_json)`` selects candidates.

    The default measure is UTF-8 bytes.  A token study can pass its tokenizer's
    length function without making this codec depend on that tokenizer.
    """
    _validate_json(frame)
    if not isinstance(frame, dict):
        raise CodecError("a response frame must be an object")
    return _Encoder(copy.deepcopy(frame), measure=measure).encode()


def decode(packet):
    """Decode one packet without any cross-frame state or external dictionary."""
    _validate_json(packet)
    decoder = _Decoder(copy.deepcopy(packet))
    return decoder.decode(packet["frame"])


__all__ = ["CODEC", "CodecError", "decode", "encode"]
