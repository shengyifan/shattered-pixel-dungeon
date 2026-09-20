"""Experimental stateless text dictionary; every packet carries its full table.

Only literal string values are shared. Occurrence count/order, keys, control
bindings, metadata and all JSON scalar types remain recoverable. This is not a
production protocol and never reads files, game state, or previous packets.
"""
import copy
import json
import re
from collections import Counter

FORMAT = "standalone-text-v1"
OPAQUE = {"raw", "reply", "before", "after", "schema", "original_payload",
          "history", "events", "outcome", "late_responses", "original_response", "source", "sources", "preserved_cells",
          "raw_request", "raw_bytes", "request_json", "response_json",
          "text_sources", "text_origins", "text_diagnostics", "pres", "presentation"}


def dumps(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def encode(frame, cost=None, minimum_tokens=8):
    """Pool only repeated strings whose estimated saving pays for a definition.

    ``cost`` may be the study's reference tokenizer. Without it, UTF-8 bytes are
    used solely as an offline selection heuristic. No table survives this call.
    """
    measure = cost or (lambda text: len(text.encode("utf-8")))
    counts = Counter()

    def collect(value):
        if isinstance(value, str):
            counts[value] += 1
        elif isinstance(value, list):
            for child in value:
                collect(child)
        elif isinstance(value, dict):
            for key, child in value.items():
                if key not in OPAQUE:
                    collect(child)
    collect(frame)
    candidates = []
    for text, frequency in counts.items():
        size = measure(dumps(text))
        if frequency >= 2 and size >= minimum_tokens:
            candidates.append((frequency * (size - measure(dumps("$0"))) - size - 1, text))
    candidates.sort(key=lambda entry: (-entry[0], entry[1]))
    definitions, references = [], {}
    for _, text in candidates:
        reference = "$" + str(len(definitions))
        old_size = measure(dumps(text))
        benefit = counts[text] * (old_size - measure(dumps(reference))) - old_size - 1
        if benefit > 0:
            references[text] = reference
            definitions.append(text)

    def transform(value):
        if isinstance(value, str):
            if value in references:
                return references[value]
            return "$" + value if value.startswith("$") else value
        if isinstance(value, list):
            return [transform(child) for child in value]
        if isinstance(value, dict):
            return {key: copy.deepcopy(child) if key in OPAQUE else transform(child)
                    for key, child in value.items()}
        return copy.deepcopy(value)
    return {"$codec": FORMAT, "strings": definitions, "frame": transform(frame)}


def decode(packet):
    if not isinstance(packet, dict) or packet.get("$codec") != FORMAT:
        raise ValueError("Unknown standalone text codec")
    if set(packet) != {"$codec", "strings", "frame"}:
        raise ValueError("Invalid standalone text packet fields")
    definitions = packet["strings"]
    if not isinstance(definitions, list) or not all(isinstance(value, str) for value in definitions):
        raise ValueError("Text definitions must be literal strings")

    def expand(value):
        if isinstance(value, str):
            if value.startswith("$$"):
                return value[1:]
            if value.startswith("$"):
                if not re.fullmatch(r"\$(0|[1-9][0-9]*)", value):
                    raise ValueError("Invalid same-frame text reference")
                index = int(value[1:])
                if index >= len(definitions):
                    raise ValueError("Missing same-frame text definition")
                return definitions[index]
            return value
        if isinstance(value, list):
            return [expand(child) for child in value]
        if isinstance(value, dict):
            return {key: copy.deepcopy(child) if key in OPAQUE else expand(child)
                    for key, child in value.items()}
        return copy.deepcopy(value)
    return expand(packet["frame"])
