#!/usr/bin/env python3
"""Measure complete, independently decodable responses; never a delta or cache.

Reads only explicitly supplied public transport files. Every strict candidate
must round-trip every frame with a fresh decoder. Generated examples/results are
offline artifacts, not a new runtime protocol. No game/save/audit access occurs.
"""
import argparse
import copy
import hashlib
import importlib.metadata
import json
from collections import Counter, defaultdict
from functools import lru_cache
from pathlib import Path

import standalone_map_codec as maps
import standalone_text_codec as text_codec
import standalone_ui_codec as ui_codec
import standalone_control_codec as controls
import standalone_scalar_codec as scalars
from d9_token_study import inventory_tree, equal, distribution, wire


def tree_packet(frame):
    data = frame.get("data")
    if not isinstance(data, dict):
        return {"$codec":"standalone-inventory-tree-v1", "frame":copy.deepcopy(frame)}
    if isinstance(data.get("inv"), dict):
        # An unfamiliar original object must not be mistaken for a tree this
        # encoder created from the protocol's flat array.
        return {"$codec":"standalone-inventory-literal-v1", "frame":copy.deepcopy(frame)}
    return {"$codec":"standalone-inventory-tree-v1", "frame":inventory_tree(frame)}


def decode_tree(packet):
    if packet.get("$codec") == "standalone-inventory-literal-v1":
        return copy.deepcopy(packet["frame"])
    if packet.get("$codec") != "standalone-inventory-tree-v1":
        raise ValueError("Not an inventory tree packet")
    result = copy.deepcopy(packet["frame"])
    data = result.get("data")
    if not isinstance(data, dict):
        return result
    inv = data.get("inv")
    if not isinstance(inv, dict):
        return result
    if set(inv) != {"equipment", "backpack"}:
        raise ValueError("Unrecognized inventory tree")
    flat = []
    def collect(item, locator):
        record = copy.deepcopy(item)
        children = record.pop("inside", [])
        flat.append({"loc":locator, **record})
        for index, child in enumerate(children):
            collect(child, f"{locator}.{index}")
    for slot, item in inv["equipment"].items():
        collect(item, "equipment." + slot)
    for index, item in enumerate(inv["backpack"]):
        collect(item, f"backpack.{index}")
    result["data"]["inv"] = flat
    return result


def display_metadata_projection(frame):
    """Explicitly NON-JSON-lossless metadata-removal sensitivity experiment.

    Never included in the strict adaptive winner. Unknown fields, errors,
    modal/settings screens and any partial presentation keep their metadata.
    No text-node matching, capability pruning or save-receipt removal occurs.
    """
    result = copy.deepcopy(frame)
    data = result.get("data")
    removed = []
    if "err" in result or "pres" in result or not isinstance(data, dict):
        return result, removed
    ui = data.get("ui")
    if (data.get("phase") != "player_ready" or not isinstance(ui, dict)
            or ui.get("scene") != "GameScene" or ui.get("modal", False)
            or any(key in data or key in ui for key in ("pres", "text_sources", "text_origins", "text_diagnostics"))):
        return result, removed
    display = ui.get("display")
    if (isinstance(display, dict) and set(display) <= {"language", "fullscreen"}
            and isinstance(display.get("language"), str)
            and type(display.get("fullscreen")) is bool):
        ui.pop("display")
        removed.append("data.ui.display")
    coverage = data.get("coverage")
    if (isinstance(coverage, dict) and coverage.get("status") == "observation_with_inspection"
            and coverage.get("inspection_policy") == "Use current ui controls and their action descriptors; details are read from displayed windows"
            and not any(key in coverage for key in ("pres", "text_sources", "text_origins", "text_diagnostics"))):
        coverage.pop("inspection_policy")
        removed.append("data.coverage.inspection_policy")
    return result, removed


def source_files(d9, current):
    rows = [("historical_d9", d9, "t1.2zs")]
    for child in ("first", "restarted"):
        sessions = list((current/child/"transport").glob("session-*"))
        if len(sessions) != 1:
            raise ValueError(f"Expected one explicit fixture transport under {child}")
        rows.append(("current_6_1_1", sessions[0], None))
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--d9-trace", type=Path, required=True)
    parser.add_argument("--current-fixture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0, help="Development limit per source; zero analyzes the complete selected corpus")
    args = parser.parse_args()
    import tiktoken
    assert importlib.metadata.version("tiktoken") == "0.12.0"
    encoder = tiktoken.get_encoding("o200k_base")
    @lru_cache(maxsize=50000)
    def cost(value):
        return len(encoder.encode(value, disallowed_special=()))

    def encode_text(value, threshold=8):
        return text_codec.encode(value, cost=cost, minimum_tokens=threshold)

    def encode_ui(value):
        return ui_codec.encode(value, measure=cost)

    def encode_controls_ui(value):
        inner = controls.encode(value)["frame"]
        return {"$codec":"controls-ui-v1", "payload":encode_ui(inner)}

    def decode_controls_ui(packet):
        assert packet["$codec"] == "controls-ui-v1"
        return controls.decode({"$codec":controls.CODEC,"frame":ui_codec.decode(packet["payload"])})

    def encode_controls_tree(value):
        return {"$codec":"controls-tree-v1", "payload":tree_packet(controls.encode(value)["frame"])}

    def decode_controls_tree(packet):
        assert packet["$codec"] == "controls-tree-v1"
        return controls.decode({"$codec":controls.CODEC,"frame":decode_tree(packet["payload"])})

    def scalar_then(frame, encode, name):
        scalar_packet = scalars.encode(frame, style="all")
        return {"$codec":name,"payload":encode(scalar_packet["frame"])}

    def scalar_after(packet, decode, name):
        assert packet["$codec"] == name
        return scalars.decode({"$codec":scalars.CODEC,"style":"all","frame":decode(packet["payload"])})

    variants = {
        "inventory_tree": (tree_packet, decode_tree),
        "text_long": (lambda value: encode_text(value, 8), text_codec.decode),
        "text_aggressive": (lambda value: encode_text(value, 4), text_codec.decode),
        "ui_templates_bytes": (ui_codec.encode, ui_codec.decode),
        "ui_templates": (encode_ui, ui_codec.decode),
        "decimal_controls": (controls.encode, controls.decode),
        "map_ascii": (lambda value: maps.encode(value, style="ascii"), maps.decode),
        "map_rle": (lambda value: maps.encode(value, style="rle"), maps.decode),
        "map_grid": (lambda value: maps.encode(value, style="grid"), maps.decode),
        "ui_text": (lambda value: encode_text(encode_ui(value), 8),
                    lambda packet: ui_codec.decode(text_codec.decode(packet))),
        "ui_grid": (lambda value: maps.encode(encode_ui(value), style="grid"),
                    lambda packet: ui_codec.decode(maps.decode(packet))),
        "ui_grid_text": (lambda value: encode_text(maps.encode(encode_ui(value), style="grid"), 8),
                         lambda packet: ui_codec.decode(maps.decode(text_codec.decode(packet)))),
        "tree_text": (lambda value: encode_text(tree_packet(value), 8),
                      lambda packet: decode_tree(text_codec.decode(packet))),
        "controls_ui": (encode_controls_ui, decode_controls_ui),
        "controls_ui_grid": (lambda value: maps.encode(encode_controls_ui(value), style="grid"),
                             lambda packet: decode_controls_ui(maps.decode(packet))),
        "controls_tree": (encode_controls_tree, decode_controls_tree),
        "controls_tree_grid": (lambda value: maps.encode(encode_controls_tree(value), style="grid"),
                               lambda packet: decode_controls_tree(maps.decode(packet))),
        "gesture_symbols": (lambda value: scalars.encode(value, style="gestures"), scalars.decode),
        "hero_line": (lambda value: scalars.encode(value, style="hero"), scalars.decode),
        "hero_gestures": (lambda value: scalars.encode(value, style="all"), scalars.decode),
        "scalar_controls_ui": (lambda value: scalar_then(value, encode_controls_ui, "scalar-controls-ui-v1"),
                               lambda packet: scalar_after(packet, decode_controls_ui, "scalar-controls-ui-v1")),
        "scalar_controls_ui_grid": (
            lambda value: maps.encode(scalar_then(value, encode_controls_ui, "scalar-controls-ui-v1"), style="grid"),
            lambda packet: scalar_after(maps.decode(packet), decode_controls_ui, "scalar-controls-ui-v1")),
        "scalar_controls_tree_grid": (
            lambda value: maps.encode(scalar_then(value, encode_controls_tree, "scalar-controls-tree-v1"), style="grid"),
            lambda packet: scalar_after(maps.decode(packet), decode_controls_tree, "scalar-controls-tree-v1")),
    }
    stats = defaultdict(lambda: defaultdict(list))
    checked = defaultdict(Counter)
    choices = defaultdict(Counter)
    hashes = {}
    removals = defaultdict(Counter)
    total_removal_tokens = Counter()
    versions = defaultdict(set)
    examples = {}
    per_frame = []
    all_sources = source_files(args.d9_trace, args.current_fixture)
    for group, root, end_id in all_sources:
        raw_send, raw_recv = (root/"send.raw").read_bytes(), (root/"recv.raw").read_bytes()
        assert raw_send.endswith(b"\n") and raw_recv.endswith(b"\n")
        hashes[str(root)] = {"send.raw":hashlib.sha256(raw_send).hexdigest(), "recv.raw":hashlib.sha256(raw_recv).hexdigest()}
        requests = [json.loads(line) for line in raw_send.splitlines()]
        replies = [json.loads(line) for line in raw_recv.splitlines()]
        assert len(requests) == len(replies)
        assert all(request["id"] == reply["id"] for request,reply in zip(requests,replies))
        if end_id:
            endpoint = next(index+1 for index,reply in enumerate(replies) if reply["id"] == end_id)
            replies = replies[:endpoint]
        if args.limit:
            replies = replies[:args.limit]
        for number, frame in enumerate(replies, 1):
            if frame.get("data", {}).get("cli_version"):
                versions[group].add(frame["data"]["cli_version"])
            baseline = cost(wire(frame))
            stats[group]["baseline"].append(baseline)
            counts = {"baseline":baseline}
            packets = {}
            # Every encoder and decoder invocation is independent. No prior
            # frame, definition registry, request outcome or cache is passed.
            for name,(encode,decode) in variants.items():
                packet = encode(frame)
                restored = decode(copy.deepcopy(packet))
                if not equal(frame, restored):
                    raise AssertionError((group, frame["id"], name, "Round-trip mismatch"))
                checked[group][name] += 1
                counts[name] = cost(wire(packet))
                stats[group][name].append(counts[name])
                packets[name] = packet
            winner = min(counts, key=lambda name: counts[name])
            # Every tested packet has its own codec tag; baseline is the
            # original complete JSON response. This is per-frame selection,
            # not a previous-state dependency or a claimed deployed grammar.
            choices[group][winner] += 1
            stats[group]["adaptive_strict"].append(counts[winner])
            projected, removed = display_metadata_projection(frame)
            trimmed = cost(wire(projected))
            stats[group]["display_metadata_only"].append(trimmed)
            total_removal_tokens[group] += baseline-trimmed
            removals[group].update(removed)
            data = frame.get("data", {})
            hero = data.get("hero") or {}
            example_key = None
            if group == "current_6_1_1" and hero and data.get("phase") == "player_ready":
                example_key = "current-ready"
            elif group == "historical_d9" and hero.get("depth") in (1,7,9) and data.get("phase") == "player_ready":
                example_key = f"historical-depth-{hero['depth']}"
            elif group == "current_6_1_1" and data.get("ui", {}).get("modal"):
                example_key = "current-modal"
            if example_key and example_key not in examples:
                examples[example_key] = {"source":str(root),"id":frame["id"],"tokens":counts,
                    "winner":winner,"original":frame,"winning_packet":frame if winner=="baseline" else packets[winner],
                    "ui_templates":packets["ui_templates"],"map_grid":packets["map_grid"]}
            per_frame.append({"corpus":group,"source":root.name,"line":number,"id":frame["id"],
                              "depth":hero.get("depth"),"tokens":counts,"winner":winner})
            if number % 250 == 0:
                print(json.dumps({"corpus":group,"processed":number,"selected":len(replies)}),flush=True)
        for filename,digest in hashes[str(root)].items():
            assert hashlib.sha256((root/filename).read_bytes()).hexdigest() == digest

    result = {"status":"offline_self_contained_research_not_deployed",
              "tokenizer":"tiktoken 0.12.0 / o200k_base", "limit_per_source":args.limit,
              "measurement":"Complete UTF-8 JSON packets plus LF, including every local definition and codec wrapper; not billed model usage",
              "source_hashes":hashes, "corpora":{}}
    for group, candidates in stats.items():
        baseline = sum(candidates["baseline"])
        result["corpora"][group] = {"frames":len(candidates["baseline"]),"versions":sorted(versions[group]),
            "variants":{name:{**distribution(values),"response_reduction_percent":round(100*(1-sum(values)/baseline),4),
                              "frames_smaller":sum(value<original for value,original in zip(values,candidates["baseline"]))}
                        for name,values in candidates.items()},
            "strict_round_trips":dict(checked[group]),"adaptive_choices":dict(choices[group]),
            "metadata_removal_sensitivity":{"removed_fields":dict(removals[group]),"net_tokens":total_removal_tokens[group],
                "strict_json_lossless":False,"included_in_adaptive_strict":False}}
    result["boundaries"] = [
        "No baseline, cross-frame dictionary, delta, external lookup, omitted changed state, or binary/base64 compression is used.",
        "Historical CLI.6.0.1 data lacks fields restored in 6.1; they are neither guessed nor counted as recoverable.",
        "The current CLI.6.1.1 sample covers menu/D1 isolated package acceptance, not a current deep-floor playthrough.",
        "Adaptive selection uses this reference tokenizer and does not prove minimum tokens or model accuracy/readability.",
        "Metadata-removal sensitivity deliberately omits named non-game display prose/configuration; it is not exact JSON round-trip or a deployed rule.",
        "All protected evidence, original raw/history/source trees, action distinctions and repeated occurrences remain in strict candidates.",
    ]
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/"metrics.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    (args.output/"per-frame.jsonl").write_text("".join(wire(row) for row in per_frame))
    for name,value in examples.items():
        (args.output/(name+".json")).write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps({"result":"passed","output":str(args.output),"corpora":{
        group:{"frames":data["frames"],"baseline":data["variants"]["baseline"]["total"],
               "adaptive_strict":data["variants"]["adaptive_strict"],"choices":data["adaptive_choices"]}
        for group,data in result["corpora"].items()}},ensure_ascii=False),flush=True)


if __name__ == "__main__":
    main()
