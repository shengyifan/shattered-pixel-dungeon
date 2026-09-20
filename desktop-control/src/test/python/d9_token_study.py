#!/usr/bin/env python3
"""Offline, exact-round-trip format experiments on explicitly supplied public wire.

No game, save, audit database or network API is opened. Candidate packets are not
accepted by spdctl: these are measured protocol proposals, not deployed formats.
Install tiktoken==0.12.0 in a separate analysis environment to reproduce counts.
"""
import argparse
import copy
import hashlib
import importlib.metadata
import json
import math
from collections import Counter, defaultdict
from functools import lru_cache
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "desktop-control/client"))
from spdctl_client import decode_wire_response


def wire(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n"


def identity(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def equal(left, right):
    return identity(left) == identity(right)


def context(frame):
    data = frame.get("data", {})
    hero = data.get("hero") or {}
    cues = data.get("cues") or {}
    ui = data.get("ui") or {}
    return frame.get("s"), data.get("scene"), hero.get("depth"), cues.get("map_context"), ui.get("scene")


def observed(frame):
    return "err" not in frame and isinstance(frame.get("data"), dict) and "phase" in frame["data"]


def table_encode(items):
    columns, indexes, rows = [], {}, []
    for item in items:
        fields = tuple(item)
        if fields not in indexes:
            indexes[fields] = len(columns)
            columns.append(list(fields))
        rows.append([indexes[fields], *copy.deepcopy(list(item.values()))])
    return {"columns": columns, "rows": rows}


def table_decode(table):
    return [dict(zip(table["columns"][row[0]], copy.deepcopy(row[1:]))) for row in table["rows"]]


def inventory_rows(frame):
    packet = copy.deepcopy(frame)
    if isinstance(packet.get("data", {}).get("inv"), list):
        packet["data"]["inv"] = table_encode(packet["data"]["inv"])
    restored = copy.deepcopy(packet)
    if isinstance(restored.get("data", {}).get("inv"), dict):
        restored["data"]["inv"] = table_decode(restored["data"]["inv"])
    assert equal(restored, frame)
    return packet


def inventory_tree(frame):
    """Use the observed depth-first locator tree, never infer capacity or type.

    Array positions reconstruct only this frame's native locators. They are not
    persistent item identities. Fall back unchanged for any unfamiliar layout.
    """
    items = frame.get("data", {}).get("inv")
    if not isinstance(items, list):
        return copy.deepcopy(frame)
    equipment, backpack, bags = {}, [], {}
    try:
        for original in items:
            item = copy.deepcopy(original)
            locator = item.pop("loc")
            if "inside" in item:
                raise ValueError("Reserved experimental field")
            path = locator.split(".")
            if len(path) == 2 and path[0] == "equipment":
                if path[1] in equipment:
                    raise ValueError("Duplicate equipment slot")
                equipment[path[1]] = item
            elif path[0] == "backpack" and len(path) >= 2:
                parent = backpack if len(path) == 2 else bags[".".join(path[:-1])].setdefault("inside", [])
                if str(len(parent)) != path[-1]:
                    raise ValueError("Not a consecutive native index")
                parent.append(item)
                bags[locator] = item
            else:
                raise ValueError("Unknown locator grammar")
        restored = []
        def collect(item, locator):
            entry = copy.deepcopy(item)
            children = entry.pop("inside", [])
            restored.append({"loc": locator, **entry})
            for index, child in enumerate(children):
                collect(child, f"{locator}.{index}")
        for slot, item in equipment.items():
            collect(item, "equipment." + slot)
        for index, item in enumerate(backpack):
            collect(item, f"backpack.{index}")
        if not equal(restored, items):
            raise ValueError("Unexpected original order")
    except (KeyError, ValueError, AttributeError):
        return copy.deepcopy(frame)
    packet = copy.deepcopy(frame)
    packet["data"]["inv"] = {"equipment": equipment, "backpack": backpack}
    return packet


def section_lines(frame):
    """Readable framing comparison: full values, no field filtering."""
    if not isinstance(frame.get("data"), dict):
        return wire(frame)
    header = {key: value for key, value in frame.items() if key != "data"}
    lines = ["@" + wire(header).rstrip("\n")]
    for key, value in frame["data"].items():
        lines.append(json.dumps(key, ensure_ascii=False) + " " + wire(value).rstrip("\n"))
    decoded_header = json.loads(lines[0][1:])
    decoder = json.JSONDecoder()
    data = {}
    for line in lines[1:]:
        key, end = decoder.raw_decode(line)
        data[key] = json.loads(line[end:].lstrip())
    decoded_header["data"] = data
    assert equal(decoded_header, frame)
    return "\n".join(lines) + "\n"


class ItemCatalog:
    """Intern only exact already-disclosed name/description/knowledge tuples.

    Never guesses a hidden item class or assumes equal names imply equal stats.
    All instance fields and metadata remain in each current item record.
    """
    def __init__(self, refresh=0):
        self.scope = None
        self.encoder = {}
        self.decoder = {}
        self.epoch = 0
        self.frames = 0
        self.refresh = refresh

    def encode(self, frame):
        if not observed(frame) or not isinstance(frame.get("data", {}).get("inv"), list):
            return copy.deepcopy(frame)
        self.frames += 1
        reset = self.scope != frame.get("s") or (self.refresh and (self.frames-1) % self.refresh == 0)
        if reset:
            self.encoder.clear()
            self.decoder.clear()
            self.epoch += 1
            self.scope = frame.get("s")
        packet = copy.deepcopy(frame)
        packet["catalog_epoch"] = self.epoch
        if reset:
            packet["catalog_reset"] = True
        definitions = []
        for item in packet["data"]["inv"]:
            fixed = {key: item.pop(key) for key in ("name", "desc", "type_known") if key in item}
            key = identity(fixed)
            if key not in self.encoder:
                number = len(self.encoder) + 1
                self.encoder[key] = number
                definitions.append([number, fixed])
            item["template"] = self.encoder[key]
        if definitions:
            packet["item_definitions"] = definitions
        for number, value in definitions:
            assert number not in self.decoder
            self.decoder[number] = copy.deepcopy(value)
        restored = copy.deepcopy(packet)
        for item in restored["data"]["inv"]:
            definition = self.decoder[item.pop("template")]
            assert not set(item) & set(definition)
            item.update(copy.deepcopy(definition))
        for key in ("catalog_epoch", "catalog_reset", "item_definitions"):
            restored.pop(key, None)
        assert equal(restored, frame)
        return packet


def patch(old, new, path=(), recursive=True):
    """Prefer a smaller UTF-8 edit list over replacement; measure tokens later.

    Byte-size choice is a deterministic heuristic, not a claimed token optimum.
    Arrays of changed length are replaced atomically, preserving order and nulls.
    """
    if equal(old, new):
        return []
    replacement = [["set", list(path), copy.deepcopy(new)]]
    nested = []
    if isinstance(old, dict) and isinstance(new, dict):
        nested = [["del", [*path, key]] for key in old if key not in new]
        for key, value in new.items():
            if key not in old:
                nested.append(["set", [*path, key], copy.deepcopy(value)])
            elif recursive:
                nested.extend(patch(old[key], value, (*path, key), True))
            elif not equal(old[key], value):
                nested.append(["set", [*path, key], copy.deepcopy(value)])
    elif recursive and isinstance(old, list) and isinstance(new, list) and len(old) == len(new):
        for index, value in enumerate(new):
            nested.extend(patch(old[index], value, (*path, index), True))
    else:
        return replacement
    return nested if len(wire(nested).encode()) < len(wire(replacement).encode()) else replacement


def apply_patch(base, operations):
    result = copy.deepcopy(base)
    for operation in operations:
        verb, path = operation[:2]
        if not path:
            assert verb == "set"
            result = copy.deepcopy(operation[2])
            continue
        target = result
        for key in path[:-1]:
            target = target[key]
        if verb == "del":
            del target[path[-1]]
        else:
            assert verb == "set"
            target[path[-1]] = copy.deepcopy(operation[2])
    return result


class Delta:
    def __init__(self, recursive, refresh=0):
        self.recursive, self.refresh = recursive, refresh
        self.scope = None
        self.sequence = 0
        self.base_sequence = None
        self.base = None
        self.full_count = 0
        self.delta_count = 0

    def encode(self, frame):
        if not observed(frame):
            return copy.deepcopy(frame)
        self.sequence += 1
        current_context = context(frame)
        full = copy.deepcopy(frame)
        full["cache"] = {"seq": self.sequence, "full": True}
        packet = full
        reset = self.scope != current_context or self.base is None or (
            self.refresh and (self.sequence-1) % self.refresh == 0)
        if not reset:
            operations = patch(self.base, frame["data"], recursive=self.recursive)
            candidate = {key: copy.deepcopy(value) for key, value in frame.items() if key != "data"}
            candidate["cache"] = {"seq": self.sequence, "base": self.base_sequence}
            candidate["patch"] = operations
            if len(wire(candidate).encode()) < len(wire(full).encode()):
                packet = candidate
        if packet["cache"].get("full"):
            body = copy.deepcopy(packet["data"])
            self.full_count += 1
        else:
            assert packet["cache"]["base"] == self.base_sequence
            body = apply_patch(self.base, packet["patch"])
            self.delta_count += 1
        restored = {key: copy.deepcopy(value) for key, value in packet.items() if key not in {"cache", "patch", "data"}}
        restored["data"] = body
        assert equal(restored, frame)
        self.scope, self.base_sequence, self.base = current_context, self.sequence, body
        return packet


class QuickslotCache:
    """Exact expanded-node field experiment; enclosing frame carries context.

    Definitions are immutable within (scope, depth, map_context). A full value is
    retransmitted after at most 24 reference-only appearances in that context.
    This does not make a reference self-contained or simulate model cache loss.
    """
    def __init__(self):
        self.contexts = {}
        self.stats = Counter()

    def encode(self, nodes, key):
        state = self.contexts.setdefault(key, {"numbers":{}, "values":{}, "frames":0, "last_full":0})
        state["frames"] += 1
        fingerprint = identity(nodes)
        fresh = fingerprint not in state["numbers"]
        if fresh:
            number = len(state["numbers"]) + 1
            state["numbers"][fingerprint] = number
        number = state["numbers"][fingerprint]
        refresh = not fresh and state["frames"] - state["last_full"] >= 25
        if fresh or refresh:
            packet = {"quickslots":{"def":number,"value":copy.deepcopy(nodes)}}
            state["values"][number] = copy.deepcopy(nodes)
            state["last_full"] = state["frames"]
            self.stats["new_definitions" if fresh else "forced_refreshes"] += 1
        else:
            packet = {"quickslots":{"ref":number}}
            self.stats["references"] += 1
        assert equal(state["values"][number], nodes)
        return packet


def distribution(values):
    ordered = sorted(values)
    if not ordered:
        return {}
    return {"total": sum(values), "median": ordered[len(ordered)//2],
            "p95": ordered[max(0, math.ceil(len(ordered)*.95)-1)], "max": ordered[-1]}


def decimal_ids(value, mapping):
    if isinstance(value, list):
        return [decimal_ids(child, mapping) for child in value]
    if not isinstance(value, dict):
        return value
    result = {}
    for key, child in value.items():
        if key in {"raw", "reply", "before", "after", "text_sources", "text_origins", "schema"}:
            result[key] = copy.deepcopy(child)
        elif key in {"id", "rid", "src_id"} and isinstance(child, str) and child in mapping:
            result[key] = mapping[child]
        else:
            result[key] = decimal_ids(child, mapping)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--end-id", default="t1.2zs")
    args = parser.parse_args()
    import tiktoken
    assert importlib.metadata.version("tiktoken") == "0.12.0"
    tokenizer = tiktoken.get_encoding("o200k_base")

    @lru_cache(maxsize=24000)
    def count(text):
        return len(tokenizer.encode(text, disallowed_special=()))

    source = {name: (args.trace_dir/name).read_bytes() for name in ("send.raw", "recv.raw")}
    assert all(value.endswith(b"\n") for value in source.values())
    hashes = {name: hashlib.sha256(raw).hexdigest() for name, raw in source.items()}
    sends, receives = ([json.loads(line) for line in source[name].splitlines()] for name in ("send.raw", "recv.raw"))
    assert len(sends) == len(receives)
    assert all(left["id"] == right["id"] for left, right in zip(sends, receives))
    end = next(index+1 for index, reply in enumerate(receives) if reply["id"] == args.end_id)
    complete_count = len(receives)
    sends, receives = sends[:end], receives[:end]
    mapping = {request["id"]: request["id"].split(".")[0]+"."+str(int(request["id"].split(".")[1], 36))
               for request in sends if request["id"].startswith("t") and "." in request["id"]}
    variants = {"item_catalog": ItemCatalog(), "item_catalog_refresh25": ItemCatalog(25),
                "section_delta": Delta(False), "tree_delta": Delta(True), "tree_delta_refresh25": Delta(True, 25)}
    costs = defaultdict(list)
    fields, field_frames, operations, phases, floors = Counter(), Counter(), Counter(), Counter(), Counter()
    equality_counts, equality_frames, unique_values = Counter(), Counter(), defaultdict(set)
    previous = {}
    inventory = {"frames": 0, "nested_frames": 0, "maximum_items": 0, "records": 0, "inline_descriptions": 0}
    quickslots = {"frames": 0, "nodes": 0, "max_nodes": 0, "row_fragment_tokens": 0}
    quickslot_cache = QuickslotCache()
    quickslot_expanded_tokens = quickslot_cached_tokens = 0
    examples = {}
    per_frame = []
    repeated_state = {"same_revision_after_observation": 0, "identical_body": 0, "identical_body_response_tokens": 0}
    for index, (request, reply) in enumerate(zip(sends, receives), 1):
        costs["requests"].append(count(wire(request)))
        costs["request_intents_only"].append(count(wire({key:value for key,value in request.items() if key not in {"v","id","s"}})))
        costs["decimal_requests"].append(count(wire(decimal_ids(request, mapping))))
        costs["decimal_responses"].append(count(wire(decimal_ids(reply, mapping))))
        costs["responses"].append(count(wire(reply)))
        costs["readable_sections"].append(count(section_lines(reply)))
        costs["inventory_rows"].append(count(wire(inventory_rows(reply))))
        costs["inventory_tree"].append(count(wire(inventory_tree(reply))))
        packets = {}
        for name, codec in variants.items():
            packet = codec.encode(reply)
            costs[name].append(count(wire(packet)))
            packets[name] = packet
        operations[request["op"]] += 1
        if index > 1 and request["op"] == "state" and not request.get("src") and request.get("view", "play") == "play":
            preceding = receives[index-2]
            if observed(preceding) and preceding.get("st") != "in_progress" and preceding.get("rev") == reply.get("rev") and preceding.get("s") == reply.get("s"):
                repeated_state["same_revision_after_observation"] += 1
                if equal(preceding.get("data"), reply.get("data")):
                    repeated_state["identical_body"] += 1
                    repeated_state["identical_body_response_tokens"] += count(wire(reply))
        data = reply.get("data", {})
        if isinstance(data, dict):
            for field, value in data.items():
                fields[field] += count(wire({field:value}))
                field_frames[field] += 1
            if observed(reply):
                phases[data["phase"]] += 1
                depth = (data.get("hero") or {}).get("depth")
                if depth is not None:
                    floors[depth] += 1
                current_context = context(reply)
                for field in ("inv", "map", "hero", "ui", "entities", "acts"):
                    if field not in data:
                        continue
                    fingerprint = hashlib.sha256(identity(data[field]).encode()).hexdigest()
                    key = current_context, field
                    equality_frames[field] += 1
                    equality_counts[field] += previous.get(key) == fingerprint
                    previous[key] = fingerprint
                    unique_values[field].add(fingerprint)
                decoded = decode_wire_response(reply).data
                items = decoded.get("inv", [])
                if "inv" in decoded:
                    inventory["frames"] += 1
                    inventory["records"] += len(items)
                    inventory["inline_descriptions"] += sum("desc" in item for item in items)
                    inventory["nested_frames"] += any(str(item.get("loc", "")).startswith("backpack.") and str(item["loc"]).count(".") >= 2 for item in items)
                    inventory["maximum_items"] = max(inventory["maximum_items"], len(items))
                nodes = decoded.get("ui", {}).get("nodes", [])
                quick = {node.get("id") for node in nodes if str(node.get("shortcut", "")).startswith("quickslot_")}
                by_id = {node.get("id"):node for node in nodes if node.get("id")}
                while True:
                    parents = {by_id[identifier].get("parent") for identifier in quick if identifier in by_id}
                    parents = {parent for parent in parents if isinstance(parent,str) and parent in by_id}
                    if parents <= quick:
                        break
                    quick.update(parents)
                selected = [i for i,node in enumerate(nodes) if node.get("id") in quick]
                if selected:
                    quickslots["frames"] += 1
                    quickslots["nodes"] += len(selected)
                    quickslots["max_nodes"] = max(quickslots["max_nodes"], len(selected))
                    raw_nodes = data["ui"]["nodes"]
                    quickslots["row_fragment_tokens"] += count(wire([raw_nodes[i] for i in selected]))
                    block = [nodes[i] for i in selected]
                    block_context = (reply.get("s"),depth,(data.get("cues") or {}).get("map_context"))
                    quickslot_expanded_tokens += count(wire({"quickslots":block}))
                    quickslot_cached_tokens += count(wire(quickslot_cache.encode(block,block_context)))
                if depth in (1, 7, 9) and data["phase"] == "player_ready" and str(depth) not in examples:
                    examples[str(depth)] = {"request": request, "original": reply,
                                            "inventory_rows": inventory_rows(reply), "inventory_tree": inventory_tree(reply), **packets}
        per_frame.append({"line":index,"id":reply["id"],"op":request["op"],
                          "depth":data.get("hero",{}).get("depth") if isinstance(data,dict) else None,
                          "tokens":{name:values[-1] for name,values in costs.items()}})
        if index % 500 == 0:
            print(json.dumps({"analyzed":index,"of":end}), flush=True)
    denominator = sum(fields.values())
    baseline = sum(costs["responses"])
    results = {name:{**distribution(values),"relative_response_reduction_percent":round(100*(1-sum(values)/baseline),3)}
               for name,values in costs.items() if name not in {"requests","request_intents_only","decimal_requests"}}
    report = {"status":"offline_experiment_not_deployed","tokenizer":"tiktoken 0.12.0 / o200k_base",
              "measurement":"Complete minified UTF-8 NDJSON including LF; reference tokens, not billed model usage",
              "input_hashes":hashes,"complete_session_pairs":complete_count,"analyzed_prefix_pairs":end,
              "end_id":args.end_id,"cli_version":receives[0].get("data",{}).get("cli_version"),
              "request_ops":dict(operations),"phases":dict(phases),"floor_observations":dict(floors),
              "requests":distribution(costs["requests"]),"decimal_requests":distribution(costs["decimal_requests"]),
              "request_intents_only":distribution(costs["request_intents_only"]),"response_variants":results,
              "original_wire_tokens":{name:sum(count(line.decode("utf-8")+"\n") for line in raw.splitlines()[:end]) for name,raw in source.items()},
              "repeated_default_state_queries":repeated_state,
              "section_fragments":{key:{"tokens":value,"frames":field_frames[key],"share_of_fragment_sum_percent":round(value/denominator*100,3)}
                                   for key,value in fields.most_common()},
              "same_value_repetition":{key:{"frames":equality_frames[key],"equal_previous_in_same_context":equality_counts[key],"distinct_values":len(unique_values[key])}
                                       for key in equality_frames},
              "inventory":inventory,"quickslot_raw_row_fragments":quickslots,
              "quickslot_field_cache_experiment":{"expanded_field_tokens":quickslot_expanded_tokens,
                  "cached_field_tokens":quickslot_cached_tokens,"contexts":len(quickslot_cache.contexts),
                  **dict(quickslot_cache.stats),"round_trip_verified":quickslots["frames"],
                  "scope":"Field-only; do not add its saving to whole-frame delta savings. Existing shared UI tables remain outside this count."},
              "round_trip_verified":{name:end for name in ["inventory_rows","inventory_tree","readable_sections",*variants]},
              "delta_frames":{name:{"full":codec.full_count,"delta":codec.delta_count} for name,codec in variants.items() if isinstance(codec,Delta)},
              "limitations":["The source is historical CLI.6.0.1, not the restored CLI.6.1 response baseline.",
                "Previously omitted descriptions/UI nodes cannot be inferred or reconstructed from this corpus.",
                "Stateful candidates include definitions/base/reset overhead, but not extra human/model lookups or an implementation handshake.",
                "Every 25 observation/item frames refresh is a sensitivity experiment, not measured context-loss frequency.",
                "Bag capacities are not invented from item counts or disabled UI slots.",
                "Section-fragment token shares are not additive attribution of the complete-frame token total.",
                "Controller-local wrappers, model-generated tool code, context reuse and billing are outside this wire metric."]}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/"metrics.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    (args.output/"per-frame.jsonl").write_text("".join(wire(value) for value in per_frame))
    for depth,value in examples.items():
        (args.output/f"example-depth-{depth}.json").write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n")
    assert hashes == {name:hashlib.sha256((args.trace_dir/name).read_bytes()).hexdigest() for name in source}
    print(json.dumps({"result":"passed","frames":end,"response_variants":results,"output":str(args.output)},ensure_ascii=False),flush=True)


if __name__ == "__main__":
    main()
