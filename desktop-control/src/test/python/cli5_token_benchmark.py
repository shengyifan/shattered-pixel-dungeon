#!/usr/bin/env python3
"""Replay explicit protocol-4 public NDJSON through the production protocol-5 encoder.

This program reads only send.raw/recv.raw from the supplied transport directory.
It does not import a gameplay controller or open profiles, saves or audit databases.
"""
import argparse
import copy
import hashlib
import importlib.metadata
import importlib.util
import json
import math
from pathlib import Path
import re
import statistics
import subprocess

OPAQUE = {"raw", "reply", "schema", "original_payload", "raw_bytes", "raw_request", "request_json", "response_json"}


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def frame_bytes(value):
    return (compact(value) + "\n").encode("utf-8")


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def protect_legacy_opaque(adapter):
    """Correct known assertion-only opaque/cue ambiguities without editing the frozen adapter."""
    decode = adapter._value
    def value(raw):
        if not isinstance(raw, dict):
            return decode(raw)
        preserved = {key: copy.deepcopy(child) for key, child in raw.items() if key in OPAQUE}
        result = decode({key: child for key, child in raw.items() if key not in OPAQUE})
        result.update(preserved)
        if isinstance(raw.get("cues"), list):
            # v4 wire also uses cues for both the outer snapshot map and its inner list.
            # The old test adapter renamed both, although the runtime wire was correct.
            assert "visual_cues" not in raw, "Ambiguous historical cue fixture"
            result["cues"] = result.pop("visual_cues")
        return result
    adapter._value = value


def decode(adapter, reply, live):
    data = copy.deepcopy(reply.get("data", {}))
    return adapter.state(data, reply.get("s"), reply.get("rev")) if live else adapter._value(data)


def source_properties(value, policy):
    """Match the production policy exported by the same Java encoder invocation."""
    protected = False
    origins = set()
    def visit(node):
        nonlocal protected
        if isinstance(node, list):
            for child in node:
                visit(child)
        elif isinstance(node, dict):
            kind, origin = node.get("kind"), node.get("origin")
            if kind is not None and kind not in policy["ordinary_source_kinds"] and kind not in {"user", "external"}:
                protected = True
            if origin is not None and origin not in policy["ordinary_source_origins"] and origin not in {"user", "external"}:
                protected = True
            origins.update(item for item in (kind, origin) if item in {"user", "external"})
            if (node.get("clipped") is True or any(node.get(key) == "partial" for key in
                    ("visibility", "translation_status", "status", "st")) or "diagnostic" in node or "diagnostics" in node):
                protected = True
            for child in node.values():
                visit(child)
    visit(value)
    return protected, origins


def semantic_sources(value, policy):
    """Discard only ordinary construction AST; retain text, origins and protected AST."""
    if isinstance(value, list):
        return [semantic_sources(child, policy) for child in value]
    if not isinstance(value, dict):
        return value
    result = {key: copy.deepcopy(child) if key in OPAQUE else semantic_sources(child, policy)
              for key, child in value.items() if key != "text_sources"}
    retained = {}
    for field, source in value.get("text_sources", {}).items():
        protected, origins = source_properties(source, policy)
        if protected:
            retained[field] = copy.deepcopy(source)
        if origins:
            existing = set(result.setdefault("text_origins", {}).get(field, []))
            result["text_origins"][field] = sorted(existing | origins)
    if retained:
        result["text_sources"] = retained
    if "text_origins" in result:
        result["text_origins"] = {key: sorted(origins) for key, origins in result["text_origins"].items()}
    return result


def removable_historical_node(node, nodes, actions):
    """Old wire contains no ownership/placeholder hints: allow only provable plain leaf cases."""
    if node.get("role") != "text" or set(node) - {"id", "role", "parent", "text", "enabled", "dimmed"}:
        return False
    if node.get("dimmed", False) or any(other.get("parent") == node["id"] for other in nodes.values()):
        return False
    if any(action.get("control") == node["id"] for action in actions):
        return False
    if not node.get("text") and node.get("enabled", True):
        return True
    parent = nodes.get(node.get("parent"), {})
    return (isinstance(node.get("text"), str) and node["text"] == parent.get("text")
            and node.get("enabled", True) == parent.get("enabled", True)
            and node.get("dimmed", False) == parent.get("dimmed", False)
            and not any(key in parent for key in ("clipped", "pres", "text_sources", "text_origins", "text_diagnostics")))


def assert_semantics(old, new, policy, context):
    before, after = semantic_sources(old, policy), semantic_sources(new, policy)
    # Version/schema regeneration is the only handshake change accepted here.
    if "cli_version" in before:
        for field in ("cli_version", "protocol_version", "audit_schema_version", "schema"):
            before.pop(field, None); after.pop(field, None)
    before_ui = before.get("observation", {}).get("ui")
    after_ui = after.get("observation", {}).get("ui")
    if isinstance(before_ui, dict) and isinstance(after_ui, dict):
        before_ui.setdefault("modal", False); before_ui.setdefault("inspected_item", None)
        before_nodes = {node["id"]: node for node in before_ui.get("controls", [])}
        after_ids = {node["id"] for node in after_ui.get("controls", [])}
        assert after_ids <= before_nodes.keys(), (context, "Invented historical UI node")
        for identifier, node in before_nodes.items():
            if identifier not in after_ids:
                assert removable_historical_node(node, before_nodes, before.get("actions", [])), (context, "Lost meaningful UI", node)
        before_ui["controls"] = [node for node in before_ui.get("controls", []) if node["id"] in after_ids]
    if not typed_equal(before, after):
        raise AssertionError({"context": context, "difference": first_difference(before, after)})


def typed_equal(old, new):
    if type(old) is not type(new):
        return False
    if isinstance(old, dict):
        return old.keys() == new.keys() and all(typed_equal(old[key], new[key]) for key in old)
    if isinstance(old, list):
        return len(old) == len(new) and all(typed_equal(left, right) for left, right in zip(old, new))
    return old == new


def first_difference(old, new, path="$"):
    if type(old) is not type(new):
        return {"path": path, "before": old, "after": new}
    if isinstance(old, dict):
        if old.keys() != new.keys():
            return {"path": path, "before_only": sorted(old.keys() - new.keys()), "after_only": sorted(new.keys() - old.keys())}
        for key in old:
            if not typed_equal(old[key], new[key]):
                return first_difference(old[key], new[key], path + "." + key)
    elif isinstance(old, list):
        if len(old) != len(new):
            return {"path": path, "before_length": len(old), "after_length": len(new)}
        for index, (left, right) in enumerate(zip(old, new)):
            if not typed_equal(left, right):
                return first_difference(left, right, f"{path}[{index}]")
    return {"path": path, "before": old, "after": new}


def diagnostic_evidence(reply, policy):
    """Every aggregated diagnostic must still name an actual wire value after relocation."""
    evidence = set()
    for diagnostic in reply.get("pres", {}).get("diag", []):
        path = diagnostic["field"]
        assert path.startswith("$"), path
        value = reply
        for name, index in re.findall(r"\.([^\.\[\]]+)|\[(\d+)\]", path[1:]):
            value = value[int(index)] if index else value[name]
        evidence.add(compact({"diagnostic": {key: child for key, child in diagnostic.items() if key != "field"},
                              "value": semantic_sources(value, policy)}))
    return evidence


def distribution(values):
    ordered = sorted(values)
    return {"count": len(values), "total": sum(values), "median": statistics.median(values) if values else 0,
            "p95": ordered[max(0, math.ceil(len(values) * .95) - 1)] if values else 0,
            "max": max(values, default=0)}


def group_metrics(rows, field):
    result = {}
    for name in sorted({row[field] for row in rows}):
        group = [row for row in rows if row[field] == name]
        result[name] = {measure: distribution([row[measure] for row in group])
                        for measure in ("before_tokens", "after_tokens", "full_tokens", "before_bytes", "after_bytes")}
        result[name]["regression_frames"] = sum(row["after_tokens"] > row["before_tokens"] for row in group)
    return result


def verify_fixtures(fixtures, current, tokens):
    by_name = {fixture["name"]: fixture for fixture in fixtures}
    sample = by_name["rendered_item_and_bar"]
    nodes = {node["id"]: node for node in sample["play"]["data"]["ui"]["nodes"]}
    assert nodes["slot"]["loc"] == "backpack.0"
    assert nodes["slot"]["display"] == {"status": "4/20", "extra": "14?", "level": "+2"}
    assert not {"status", "extra", "level"} & nodes.keys()
    assert nodes["bar"]["health_pixels"] == 23 and nodes["bar"]["health_and_shield_pixels"] == 30
    assert nodes["bar"]["total_pixels"] == 48 and nodes["bar"]["cell"] == 145
    assert nodes["slot"]["ops"] == [{"op": "click", "gestures": ["click", "right"]}]
    assert len(sample["full"]["data"]["ui"]["nodes"]) == 5
    item = sample["play"]["data"]["inv"][0]
    assert "level" in item and item["level"] is None and "cursed" in item and item["cursed"] is None
    hazards = by_name["repeated_hazard_descriptors"]
    assert hazards["play"]["data"]["entity_defs"] and hazards["play"]["data"]["map"]["effect_defs"]
    assert current._value(hazards["play"]["data"])["map"] == current._value(hazards["full"]["data"])["map"]
    assert current._value(hazards["play"]["data"])["visible_entities"] == current._value(hazards["full"]["data"])["visible_entities"]
    overflow = by_name["integer_map_rows"]
    assert isinstance(overflow["play"]["data"]["map"]["rows"][0][2], list)
    assert current._value(overflow["play"]["data"])["map"] == current._value(overflow["full"]["data"])["map"]
    protected = by_name["protected_public_sources"]["play"]
    protected_nodes = {node["id"]: node for node in protected["data"]["ui"]["nodes"]}
    assert protected_nodes["external"]["text_origins"] == {"text": ["external"]}
    assert protected_nodes["future"]["text_sources"]["text"]["description"] == "Literal AST key"
    assert protected_nodes["partial"]["clipped"] is True and protected["pres"]["st"] == "partial"
    for name, expected_kinds in (
            ("synthetic_boss_bomb_warning", {"bomb_smoke", "bomb_countdown_3"}),
            ("synthetic_boss_summoning_warning", {"summoning_bones", "summoning_shadows", "summoning_green_flames", "summoning_sparks"})):
        fixture = by_name[name]
        for view in ("play", "full"):
            data = fixture[view]["data"]
            # Validate original public wire shape, independently of assertion adapter aliases.
            assert typed_equal(data["cues"], fixture["canonical"]["visual_cues"])
            assert data["cues"]["status"] == "last_rendered"
            assert {cue["kind"] for cue in data["cues"]["cues"]} == expected_kinds
            assert all(set(cue) == {"kind", "cell"} for cue in data["cues"]["cues"])
            visible = {tile["cell"] for tile in current._map(data["map"])["cells"] if tile["visibility"] == "visible"}
            assert all(cue["cell"] in visible for cue in data["cues"]["cues"])
        if name == "synthetic_boss_bomb_warning":
            assert any(node.get("text") == "3..." for node in fixture["play"]["data"]["ui"]["nodes"])
    metrics = [{"name": fixture["name"], "play_tokens": tokens(fixture["play"]), "full_tokens": tokens(fixture["full"]),
                "play_bytes": len(frame_bytes(fixture["play"])), "full_bytes": len(frame_bytes(fixture["full"]))}
               for fixture in fixtures]
    return {"count": len(fixtures), "names": list(by_name), "status": "passed",
            "evidence": "Synthetic public fixtures, separate from historical replay; not actual boss render or gameplay coverage",
            "metrics": metrics, "totals": {key: sum(row[key] for row in metrics)
                                               for key in ("play_tokens", "full_tokens", "play_bytes", "full_bytes")},
            "comparison": "Synthetic production play/full outputs only; not a protocol-4 comparison and not added to the 729 historical totals"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace-dir", type=Path, required=True)
    parser.add_argument("--legacy-adapter", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    import tiktoken
    assert importlib.metadata.version("tiktoken") == "0.12.0", "Benchmark requires tiktoken==0.12.0"
    args.output.mkdir(parents=True, exist_ok=True)
    legacy = load_module("explicit_protocol4_public_fixture", args.legacy_adapter)
    protect_legacy_opaque(legacy)
    current = load_module("current_protocol5_assertion_adapter", Path(__file__).with_name("protocol5.py"))
    encoder = tiktoken.get_encoding("o200k_base")
    def tokens(value):
        return len(encoder.encode(frame_bytes(value).decode("utf-8"), disallowed_special=()))
    source = {name: (args.trace_dir / name).read_bytes() for name in ("send.raw", "recv.raw")}
    for name, content in source.items():
        assert content.endswith(b"\n"), (name, "Incomplete NDJSON frame")
    requests = [json.loads(line) for line in source["send.raw"].splitlines()]
    replies = [json.loads(line) for line in source["recv.raw"].splitlines()]
    assert len(requests) == len(replies) and requests, "Unpaired or empty public transport"
    assert all(request["id"] == reply["id"] for request, reply in zip(requests, replies)), "Serial request/reply ID mismatch"
    assert all(request["v"] == reply["v"] == 4 for request, reply in zip(requests, replies))
    canonical = []
    for request, reply in zip(requests, replies):
        live = legacy.is_live_operation(request["op"])
        row = {key: reply[key] for key in ("id", "s", "st", "err") if key in reply}
        row["live"] = live
        if "err" not in reply or "data" in reply:
            row["canonical"] = decode(legacy, reply, live and "err" not in reply)
        canonical.append(row)
    input_file = args.output / "canonical-public.jsonl"
    input_file.write_bytes(b"".join(frame_bytes(row) for row in canonical))
    subprocess.run(["java", "-cp", args.classpath_file.read_text().strip(),
                    "com.shatteredpixel.shatteredpixeldungeon.control.desktop.Cli5TokenSamples",
                    str(input_file), str(args.output)], check=True)
    play_bytes = (args.output / "projected-play.jsonl").read_bytes()
    full_bytes = (args.output / "projected-full.jsonl").read_bytes()
    projected = [json.loads(line) for line in play_bytes.splitlines()]
    full = [json.loads(line) for line in full_bytes.splitlines()]
    assert play_bytes == b"".join(frame_bytes(reply) for reply in projected), "Production play serialization differs from measured minified frames"
    assert full_bytes == b"".join(frame_bytes(reply) for reply in full), "Production full serialization differs from measured minified frames"
    policy = json.loads((args.output / "encoder-policy.json").read_text())
    assert len(projected) == len(full) == len(replies)
    rows, maps, unknowns = [], 0, {"level": 0, "cursed": 0}
    for index, (request, old, new, expanded, row) in enumerate(zip(requests, replies, projected, full, canonical), 1):
        assert new["v"] == expanded["v"] == 5
        for key in ("id", "s", "rev", "st", "err"):
            assert (key in old, old.get(key)) == (key in new, new.get(key)), (index, key, old.get(key), new.get(key))
            assert (key in old, old.get(key)) == (key in expanded, expanded.get(key)), (index, "full", key)
        if "canonical" in row:
            assert_semantics(row["canonical"], decode(current, new, row["live"] and "err" not in old), policy, {"frame": index, "id": old["id"]})
            assert_semantics(row["canonical"], decode(current, expanded, row["live"] and "err" not in old), policy,
                             {"frame": index, "id": old["id"], "view": "full"})
        if "pres" in old:
            assert old["pres"].get("st") == new.get("pres", {}).get("st"), (index, "Lost presentation status")
            assert old["pres"].get("st") == expanded.get("pres", {}).get("st"), (index, "Lost full presentation status")
        assert diagnostic_evidence(old, policy) <= diagnostic_evidence(new, policy), (index, "Lost diagnostic evidence")
        assert diagnostic_evidence(old, policy) <= diagnostic_evidence(expanded, policy), (index, "Lost full diagnostic evidence")
        data = old.get("data", {})
        maps += "map" in data
        for item in data.get("inv", []):
            for field in unknowns:
                unknowns[field] += field in item and item[field] is None
        rows.append({"frame": index, "id": old["id"], "op": request["op"], "scene": str(data.get("scene", "none")),
                     "phase": str(data.get("phase", "none")), "before_tokens": tokens(old), "after_tokens": tokens(new),
                     "full_tokens": tokens(expanded), "before_bytes": len(frame_bytes(old)), "after_bytes": len(frame_bytes(new))})
    original_tokens = sum(row["before_tokens"] for row in rows)
    new_tokens = sum(row["after_tokens"] for row in rows)
    regressions = [row for row in rows if row["after_tokens"] > row["before_tokens"]]
    fixtures = verify_fixtures(json.loads((args.output / "synthetic-fixtures.json").read_text()), current, tokens)
    result = {"encoder": policy["encoder"], "tokenizer": "tiktoken 0.12.0 / o200k_base", "measurement": "Each complete minified UTF-8 NDJSON frame including LF",
              "frames": len(rows), "invariants_verified": len(rows), "views_verified": ["play", "full"], "map_frames_verified": maps, "unknown_inventory_fields_verified": unknowns,
              "original_tokens": original_tokens, "production_play_tokens": new_tokens,
              "requests": {"raw_tokens": sum(len(encoder.encode(line.decode("utf-8"), disallowed_special=()))
                                              for line in source["send.raw"].splitlines(keepends=True)),
                           "minified_tokens": sum(tokens(request) for request in requests),
                           "raw_bytes": len(source["send.raw"]), "minified_bytes": sum(len(frame_bytes(request)) for request in requests)},
              "reduction_percent": 100 * (1 - new_tokens / original_tokens),
              "bytes": {"original_raw": len(source["recv.raw"]), "original_minified": sum(row["before_bytes"] for row in rows),
                        "production_play": len(play_bytes), "production_full": len(full_bytes)},
              "original_is_minified": source["recv.raw"] == b"".join(frame_bytes(reply) for reply in replies),
              "overall": {measure: distribution([row[measure] for row in rows]) for measure in
                          ("before_tokens", "after_tokens", "full_tokens", "before_bytes", "after_bytes")},
              "by_operation": group_metrics(rows, "op"), "by_scene": group_metrics(rows, "scene"), "by_phase": group_metrics(rows, "phase"),
              "regression_frames": len(regressions), "regressions": regressions,
              "comparison_modes": {"play": "Production projection with no capture hints invented from historical wire",
                                   "full": policy["full_limit"], "ablation": "No isolated component switch is exposed; play/full comparison changes multiple rules and is not an isolated ablation"},
              "synthetic_fixtures": fixtures,
              "source_sha256": {name: hashlib.sha256(content).hexdigest() for name, content in source.items()},
              "scope": "Offline public protocol representation. Not actual model billing, a new playthrough, or full-game coverage. No allowance or omitted receipt/replay frames."}
    for name, content in source.items():
        assert (args.trace_dir / name).read_bytes() == content, (name, "Source changed during benchmark")
    result["source_unchanged_verified"] = True
    (args.output / "report.json").write_text(json.dumps(result, indent=2) + "\n")
    (args.output / "per-message.json").write_text(json.dumps(rows, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("frames", "invariants_verified", "original_tokens", "production_play_tokens", "reduction_percent", "regression_frames", "source_unchanged_verified")}), flush=True)
    assert new_tokens < original_tokens, "Production v5 total reference token count must decrease"


if __name__ == "__main__":
    main()
