#!/usr/bin/env python3
"""Read-only replay of explicit v5 public transport through production CLI 6.

The Java encoder opens only a fresh, labeled isolated handle registry beneath the
chosen artifact directory. It never opens the source profile, saves or audit data.
"""
import argparse
import copy
import hashlib
import importlib.metadata
import importlib.util
import json
from pathlib import Path
import re
import subprocess
from collections import Counter

import protocol6
from cli5_token_benchmark import distribution, first_difference, typed_equal

OPAQUE = {"raw", "reply", "schema", "original_payload", "text_sources", "text_origins", "pres", "text_diagnostics", "presentation"}


def wire_bytes(value):
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def base36(number):
    chars, result = "0123456789abcdefghijklmnopqrstuvwxyz", ""
    while number:
        number, digit = divmod(number, 36)
        result = chars[digit] + result
    return result or "0"


def identity_maps(requests, replies, first_id):
    request_ids, controls = {}, {}
    for request in requests:
        if request["id"] not in request_ids:
            request_ids[request["id"]] = first_id if not request_ids else "t1." + base36(len(request_ids))
    def visit(value):
        if isinstance(value, list):
            for child in value:
                visit(child)
        elif isinstance(value, dict):
            for key, child in value.items():
                if key in OPAQUE:
                    continue
                if key in {"id", "parent", "ctl"} and isinstance(child, str) and re.fullmatch(r"ui-[0-9]+", child):
                    controls.setdefault(child, "c" + base36(int(child[3:])))
                visit(child)
    visit(replies)
    return {"requests": request_ids, "controls": controls}


def decode(adapter, reply, live):
    data = adapter.expand_structures(reply).get("data", {}) if hasattr(adapter, "expand_structures") else reply.get("data", {})
    return adapter.state(data, reply.get("s"), reply.get("rev")) if live else adapter._value(data)


def normalize_expected_ui(before, after):
    """Require exact decoded UI content, node identity, order and parentage.

    The replay encoder can use reversible dictionaries and shapes. It must not
    make a benchmark pass by deleting old IDs, empty slots or repeated text.
    Capture-only bindings are not invented for this historical public replay.
    """
    if not isinstance(before, dict) or not isinstance(after, dict):
        return
    old_ui = before.get("observation", {}).get("ui")
    new_ui = after.get("observation", {}).get("ui")
    if not isinstance(old_ui, dict) or not isinstance(new_ui, dict):
        return
    assert typed_equal(old_ui, new_ui), {"lost_or_changed_ui": first_difference(old_ui, new_ui)}


def assert_semantics(expected, reply, live):
    if expected is None:
        return
    before, after = copy.deepcopy(expected), decode(protocol6, reply, live)
    normalize_expected_ui(before, after)
    assert typed_equal(before, after), first_difference(before, after)


def lossless_view(reply):
    """Expand documented wire defaults without canonicalizing away diagnostic fields.

    Unlike the legacy scenario assertion adapter, this comparison keeps all
    presentation metadata and every original UI node and action in order.
    """
    def expand(value, context=None):
        if isinstance(value, list):
            return [expand(child, context) for child in value]
        if not isinstance(value, dict):
            return value
        result = {key: copy.deepcopy(child) if key in OPAQUE else expand(child, key)
                  for key, child in value.items()}
        if isinstance(value.get("entities"), list):
            entities = []
            for entity in value["entities"]:
                if "def" in entity:
                    assert set(entity) == {"cell", "def"}, entity
                    descriptor = protocol6._definition(value.get("entity_defs"), entity["def"])
                    assert isinstance(descriptor, dict) and "cell" not in descriptor, descriptor
                    entity = {"cell": entity["cell"], **descriptor}
                entities.append(expand(entity))
            result["entities"] = entities
            result.pop("entity_defs", None)
        if {"w", "h", "types", "rows"} <= value.keys():
            rows = []
            for y, start, tiles, visibility in value["rows"]:
                assert tiles and isinstance(visibility, str)
                if len(visibility) == 1:
                    visibility *= len(tiles)
                assert len(visibility) == len(tiles)
                rows.append([y, start, copy.deepcopy(tiles), visibility])
            result["rows"] = rows
            environment = {}
            for cell, effects in value.get("env", {}).items():
                if type(effects) is int:
                    effects = protocol6._definition(value.get("effect_defs"), effects)
                assert isinstance(effects, list), effects
                environment[cell] = expand(effects)
            result["env"] = environment
            result.pop("effect_defs", None)
        if context in {"inv", "item"} and "loc" in result:
            for key, default in (("qty", 1), ("equipped", False), ("available", True), ("type_known", True), ("via", "click")):
                result.setdefault(key, default)
        if context == "ui":
            result.setdefault("modal", False)
            result.setdefault("item_info", None)
        if context == "nodes":
            result.setdefault("enabled", True)
            result.setdefault("dimmed", False)
        return result
    return expand(protocol6.expand_structures(reply))


def assert_lossless_views(full, play):
    before, after = lossless_view(full), lossless_view(play)
    assert typed_equal(before, after), {"lossy_play_projection": first_difference(before, after)}


def incrementality(replies, token_count):
    """Conservative exact-equality reference experiment, plus explicitly non-event text evidence."""
    result = {}
    for field in ("map", "inv"):
        frames = [reply for reply in replies if field in reply.get("data", {})]
        seen, previous, context, unchanged, full_cost, referenced_cost = set(), None, None, 0, 0, 0
        base_number, baseline = 0, None
        periodic_cost = {10: 0, 25: 0, 50: 0}
        for frame_index, reply in enumerate(frames):
            data = reply["data"]
            value = data[field]
            signature = wire_bytes(value)
            seen.add(signature)
            current_context = (reply.get("s"), data.get("scene"), data.get("hero", {}).get("depth"), data.get("cues", {}).get("map_context"))
            cost = token_count(value)
            full_cost += cost
            if previous is not None and current_context == context and signature == previous:
                unchanged += 1
                referenced_cost += token_count({"ref": baseline})
                for interval in periodic_cost:
                    periodic_cost[interval] += token_count({"base": baseline, "value": value} if frame_index % interval == 0 else {"ref": baseline})
            else:
                base_number += 1
                baseline = "b" + base36(base_number)
                referenced_cost += token_count({"base": baseline, "value": value})
                for interval in periodic_cost:
                    periodic_cost[interval] += token_count({"base": baseline, "value": value})
            previous, context = signature, current_context
        result[field] = {"frames": len(frames), "unique_exact_values": len(seen), "unchanged_previous_same_context": unchanged,
                         "standalone_field_tokens": full_cost, "theoretical_explicit_baseline_tokens": referenced_cost,
                         "potential_saved_tokens": full_cost - referenced_cost,
                         "periodic_full_refresh_tokens": periodic_cost,
                         "caveat": "Offline exact-equality transport reference only; includes base/reference fields but not losses, replay, or model context reloads"}
    texts = Counter(node.get("text") for reply in replies for node in protocol6.expand_structures(reply).get("data", {}).get("ui", {}).get("nodes", [])
                    if isinstance(node.get("text"), str) and node["text"])
    result["text_evidence"] = {"top_repeated_text": texts.most_common(12), "classification": "No log/event identity in recorded UI; repeated text is not proof of the same event. No safe log-delta saving claimed."}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace-dir", required=True, type=Path)
    parser.add_argument("--classpath-file", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    import tiktoken
    assert importlib.metadata.version("tiktoken") == "0.12.0"
    encoder = tiktoken.get_encoding("o200k_base")
    def count(value):
        return len(encoder.encode(wire_bytes(value).decode(), disallowed_special=()))
    source = {name: (args.trace_dir / name).read_bytes() for name in ("send.raw", "recv.raw")}
    assert all(raw.endswith(b"\n") for raw in source.values())
    requests, replies = ([json.loads(line) for line in source[name].splitlines()] for name in ("send.raw", "recv.raw"))
    assert requests and len(requests) == len(replies)
    assert all(left["id"] == right["id"] for left, right in zip(requests, replies))
    assert all(reply["v"] == 5 for reply in replies)
    fixture = Path(__file__).with_name("fixtures") / "protocol5_public_adapter.py"
    spec = importlib.util.spec_from_file_location("frozen_v5", fixture)
    legacy = importlib.util.module_from_spec(spec); spec.loader.exec_module(legacy)
    maps = identity_maps(requests, replies, hashlib.sha256(source["send.raw"]).hexdigest()[:32])
    rows = []
    for request, reply in zip(requests, replies):
        live = legacy.is_live_operation(request["op"]) and "err" not in reply
        row = {key: reply[key] for key in ("id", "s", "st", "err") if key in reply}
        row.update(live=live, request=request)
        if "data" in reply:
            row["canonical"] = decode(legacy, reply, live)
        rows.append(row)
    args.output.mkdir(parents=True, exist_ok=True)
    canonical_path = args.output / "canonical-public.jsonl"
    canonical_path.write_bytes(b"".join(wire_bytes(row) for row in rows))
    maps_path = args.output / "identity-maps.json"
    maps_path.write_bytes(wire_bytes(maps))
    subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-cp", args.classpath_file.read_text().strip(),
                    "com.shatteredpixel.shatteredpixeldungeon.control.desktop.Cli6TokenSamples",
                    str(canonical_path), str(maps_path), str(args.output)], check=True)
    def lines(name):
        return [json.loads(line) for line in (args.output / name).read_bytes().splitlines()]
    play, full, expected, short_requests = (lines(name) for name in ("production-play.jsonl", "production-full.jsonl", "mapped-canonical.jsonl", "production-requests.jsonl"))
    assert len(play) == len(full) == len(expected) == len(short_requests) == len(rows)
    per_message = []
    for index, (row, old, new, expanded, canonical) in enumerate(zip(rows, replies, play, full, expected), 1):
        try:
            assert new["v"] == expanded["v"] == 6
            for field in ("st", "err"):
                assert (field in new, new.get(field)) == (field in old, old.get(field))
            assert_semantics(canonical["canonical"], new, row["live"])
            assert_semantics(canonical["canonical"], expanded, row["live"])
            assert_lossless_views(expanded, new)
        except Exception as error:
            raise AssertionError({"frame": index, "id": old["id"], "detail": str(error)}) from error
        per_message.append({"frame": index, "id": old["id"], "op": row["request"]["op"], "before_tokens": count(old), "after_tokens": count(new), "full_tokens": count(expanded)})
    baseline_requests = [len(encoder.encode(line.decode(), disallowed_special=())) for line in source["send.raw"].splitlines(keepends=True)]
    baseline_responses = [len(encoder.encode(line.decode(), disallowed_special=())) for line in source["recv.raw"].splitlines(keepends=True)]
    after_requests, after_responses = [count(row) for row in short_requests], [count(row) for row in play]
    report = {"frames": len(rows), "semantic_equivalence_verified": len(rows), "views": ["play", "full"],
              "measurement": "tiktoken 0.12.0 / o200k_base, complete NDJSON including LF; raw reference tokens, not actual billing",
              "before": {"requests": distribution(baseline_requests), "responses": distribution(baseline_responses)},
              "after": {"requests": distribution(after_requests), "responses": distribution(after_responses)},
              "response_reduction_percent": 100 * (1 - sum(after_responses) / sum(baseline_responses)),
              "combined_reduction_percent": 100 * (1 - (sum(after_requests) + sum(after_responses)) / (sum(baseline_requests) + sum(baseline_responses))),
              "byte_totals": {"before_requests": len(source["send.raw"]), "before_responses": len(source["recv.raw"]),
                              "after_requests": sum(len(wire_bytes(row)) for row in short_requests), "after_responses": sum(len(wire_bytes(row)) for row in play)},
              "handshake": {"before_tokens": count(replies[0]), "after_tokens": count(play[0]),
                            "note": "Actual production CompactProtocol.info schema and persistent_handles capability/request_prefix included in the first recorded info; startup controller-local greeting is reported separately by package smoke"},
              "short_request_ids": {"distinct": len(maps["requests"]), "duplicate_frames": len(requests) - len(maps["requests"]),
                                    "note": "One representative 32-hex handshake ID; later t1.base36 IDs. Repeated original IDs retain the same replacement; malformed historical frames remain malformed."},
              "invented_empty_slot_hints": False, "source_sha256": {name: hashlib.sha256(raw).hexdigest() for name, raw in source.items()},
              "regression_frames": [row for row in per_message if row["after_tokens"] > row["before_tokens"]],
              "incrementality": incrementality(play, count), "limits": "This is public v5 wire replay through production v6 encoders, not live end-to-end billing or gameplay. Capture-only empty-slot and owned-child evidence is unavailable, so no such hint is invented."}
    for name, raw in source.items():
        assert (args.trace_dir / name).read_bytes() == raw
    report["source_unchanged_verified"] = True
    (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (args.output / "per-message.json").write_text(json.dumps(per_message, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("frames", "semantic_equivalence_verified", "response_reduction_percent", "combined_reduction_percent", "source_unchanged_verified")}), flush=True)


if __name__ == "__main__":
    main()
