"""Offline, self-contained scalar spelling experiments; never a production codec.

Each packet includes its codec version and style. C/R/M/L encode the fixed
click/right/middle/long grammar, not a dictionary learned from another frame.
Hero lines contain tagged integer groups; all unencoded public fields remain in
the same packet. Historical payloads and provenance are opaque original values.
"""
import copy
import re

CODEC = "standalone-scalar-v1"
STYLES = ("gestures", "hero", "all")
LITERAL = "$scalar_literal"
HERO = "$hero"
GESTURES = {"click": "C", "right": "R", "middle": "M", "long": "L"}
FROM_GESTURE = {value: key for key, value in GESTURES.items()}
OPAQUE = {"raw", "reply", "before", "after", "history", "events", "schema",
          "outcome", "late_responses", "original_response", "source", "sources", "text_sources",
          "text_origins", "text_diagnostics", "pres", "presentation", "preserved_cells",
          "original_payload", "raw_request", "raw_bytes", "request_json", "response_json"}
SINGLE = (("cell", "@"), ("depth", "D"), ("level", "L"), ("gold", "G"), ("energy", "E"))
INTEGER = r"-?(?:0|[1-9][0-9]*)"
NAME = r"[a-z][a-z0-9_]*"


def _literal(value):
    return isinstance(value, dict) and set(value) == {LITERAL}


def _gesture(value, decoding):
    if decoding:
        if isinstance(value, str):
            if any(char not in FROM_GESTURE for char in value):
                raise ValueError("Unknown gesture symbol")
            return [FROM_GESTURE[char] for char in value]
        return copy.deepcopy(value[LITERAL] if _literal(value) else value)
    if isinstance(value, list) and all(isinstance(item, str) and item in GESTURES for item in value):
        return "".join(GESTURES[item] for item in value)
    if isinstance(value, str) or _literal(value):
        return {LITERAL: copy.deepcopy(value)}
    return copy.deepcopy(value)


def _hero_encode(value):
    if _literal(value) or isinstance(value, dict) and HERO in value:
        return {LITERAL: copy.deepcopy(value)}
    if not isinstance(value, dict):
        return copy.deepcopy(value)
    rest, tokens = copy.deepcopy(value), []

    def integers(*keys):
        return all(key in rest and type(rest[key]) is int for key in keys)

    if integers("hp", "ht"):
        token = f"HP{rest.pop('hp')}/{rest.pop('ht')}"
        if integers("shield"):
            token += f"+{rest.pop('shield')}"
        tokens.append(token)
    for label, first, second in (("XP", "experience", "mxp"), ("STR", "strength", "base_strength")):
        if integers(first, second):
            tokens.append(f"{label}{rest.pop(first)}/{rest.pop(second)}")
    for key, label in SINGLE:
        if integers(key):
            tokens.append(f"{label}{rest.pop(key)}")
    if type(rest.get("ready")) is bool:
        tokens.append("ready" if rest.pop("ready") else "!ready")
    for tag, key, name in (("class", "class", "class_name"), ("sub", "subclass", "sub_name")):
        if isinstance(rest.get(key), str) and re.fullmatch(NAME, rest[key]) and rest.get(name) == rest[key]:
            tokens.append(f"{tag}={rest.pop(key)}")
            rest.pop(name)
        elif key == "subclass" and rest.get(key) == "none" and name in rest and rest[name] is None:
            tokens.append("sub=none/null")
            rest.pop(key)
            rest.pop(name)
    if not tokens:
        return copy.deepcopy(value)
    result = {HERO: " ".join(tokens)}
    if rest:
        result["rest"] = rest
    return result


def _hero_decode(value):
    if _literal(value):
        return copy.deepcopy(value[LITERAL])
    if not isinstance(value, dict) or HERO not in value:
        return copy.deepcopy(value)
    if (set(value) - {HERO, "rest"} or not isinstance(value[HERO], str)
            or not isinstance(value.get("rest", {}), dict)):
        raise ValueError("Invalid hero row")
    result = copy.deepcopy(value.get("rest", {}))

    def add(fields):
        if any(key in result for key in fields):
            raise ValueError("Overlapping hero fields")
        result.update(fields)

    for token in value[HERO].split(" "):
        match = re.fullmatch(f"HP({INTEGER})/({INTEGER})(?:\\+({INTEGER}))?", token)
        if match:
            fields = {"hp": int(match[1]), "ht": int(match[2])}
            if match[3] is not None:
                fields["shield"] = int(match[3])
            add(fields)
            continue
        match = re.fullmatch(f"(XP|STR)({INTEGER})/({INTEGER})", token)
        if match:
            keys = ("experience", "mxp") if match[1] == "XP" else ("strength", "base_strength")
            add(dict(zip(keys, (int(match[2]), int(match[3])))))
            continue
        match = re.fullmatch(f"([@DLGE])({INTEGER})", token)
        if match:
            add({dict((label, key) for key, label in SINGLE)[match[1]]: int(match[2])})
            continue
        if token in ("ready", "!ready"):
            add({"ready": token == "ready"})
            continue
        if token == "sub=none/null":
            add({"subclass": "none", "sub_name": None})
            continue
        match = re.fullmatch(f"(class|sub)=({NAME})", token)
        if match:
            keys = ("class", "class_name") if match[1] == "class" else ("subclass", "sub_name")
            add(dict.fromkeys(keys, match[2]))
            continue
        raise ValueError("Unknown hero row token")
    return result


def _walk(value, style, decoding=False):
    if isinstance(value, list):
        return [_walk(child, style, decoding) for child in value]
    if not isinstance(value, dict):
        return copy.deepcopy(value)

    def field(key, child):
        if key in OPAQUE:
            return copy.deepcopy(child)
        if key == "gestures" and style in ("gestures", "all"):
            return _gesture(child, decoding)
        if key == "hero" and style in ("hero", "all"):
            return _hero_decode(child) if decoding else _hero_encode(child)
        return _walk(child, style, decoding)

    result = {}
    for key, child in value.items():
        if key == "nodes" and isinstance(child, list):
            shapes, nodes = value.get("node_shapes"), []
            for raw in child:
                if (isinstance(raw, list) and raw and type(raw[0]) is int and isinstance(shapes, list)
                        and 0 <= raw[0] < len(shapes) and isinstance(shapes[raw[0]], list)
                        and all(isinstance(name, str) for name in shapes[raw[0]])
                        and len(shapes[raw[0]]) == len(raw) - 1):
                    nodes.append([raw[0]] + [field(name, item) for name, item in zip(shapes[raw[0]], raw[1:])])
                else:
                    nodes.append(_walk(raw, style, decoding))
            result[key] = nodes
        else:
            result[key] = field(key, child)
    return result


def encode(frame, style="gestures"):
    if style not in STYLES:
        raise ValueError("Unknown scalar codec style")
    return {"$codec": CODEC, "style": style, "frame": _walk(frame, style)}


def decode(packet):
    if (not isinstance(packet, dict) or set(packet) != {"$codec", "style", "frame"}
            or packet["$codec"] != CODEC or packet["style"] not in STYLES):
        raise ValueError("Invalid scalar codec packet")
    return _walk(packet["frame"], packet["style"], decoding=True)
