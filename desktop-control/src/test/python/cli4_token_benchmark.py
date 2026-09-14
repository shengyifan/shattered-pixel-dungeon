#!/usr/bin/env python3
"""Measure production CLI 4 projection of explicit, immutable CLI 3 public evidence.

The historical decoder is an explicit test fixture argument, not a runtime protocol
compatibility layer. Never opens a game profile, save, or audit database.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tiktoken


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def map_semantics(value):
    if "cells" in value:
        cells = value["cells"]
    else:
        alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        cells = []
        last = (-1, -1)
        for y, start, tiles, visibility in value["rows"]:
            assert (y, start) > last
            last = (y, start)
            assert len(tiles) == len(visibility)
            assert 0 <= y < value["h"] and 0 <= start < value["w"]
            assert start + len(tiles) <= value["w"]
            for offset, (tile, vis) in enumerate(zip(tiles, visibility)):
                index = alphabet.index(tile) if isinstance(tiles, str) else tile
                assert vis in ("v", "s", "m")
                cells.append([y * value["w"] + start + offset, index, vis])
    result = {cell: [value["types"][tile], vis] for cell, tile, vis in cells}
    assert len(result) == len(cells)
    return {"w": value["w"], "h": value["h"], "cells": result, "env": value.get("env", {})}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace-dir", type=Path, required=True)
    parser.add_argument("--legacy-adapter", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location("explicit_historical_fixture", args.legacy_adapter)
    legacy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(legacy)
    enc = tiktoken.get_encoding("o200k_base")
    def tokens(value): return len(enc.encode(compact(value), disallowed_special=()))
    source = {name: (args.trace_dir / name).read_bytes() for name in ("send.raw", "recv.raw")}
    requests = {r["id"]: r for r in map(json.loads, source["send.raw"].splitlines())}
    replies = list(map(json.loads, source["recv.raw"].splitlines()))
    assert len(requests) == len(replies)
    before = sum(tokens(r) for r in replies)
    input_file = args.output / "canonical-public.jsonl"
    with input_file.open("w") as output:
        for reply in replies:
            req = requests[reply["id"]]
            live = legacy.is_live_operation(req["op"])
            row = {k: reply[k] for k in ("id", "s", "st", "err") if k in reply}
            row["live"] = live
            if "err" not in reply:
                row["canonical"] = (legacy.response(reply, req["op"])["result"]
                                    if live else reply.get("data", {}))
            output.write(compact(row) + "\n")
    projected_file = args.output / "projected-play.jsonl"
    subprocess.run(["java", "-cp", args.classpath_file.read_text().strip(),
                    "com.shatteredpixel.shatteredpixeldungeon.control.desktop.Cli4TokenSamples",
                    str(input_file), str(projected_file)], check=True)
    projected = list(map(json.loads, projected_file.read_text().splitlines()))
    assert len(projected) == len(replies)
    map_before = map_after = map_count = total = selected = skipped = hero_frames = 0
    rows = []
    for old, new in zip(replies, projected):
        assert old["id"] == new["id"] and new["v"] == 4
        req = requests[old["id"]]
        budget = tokens(new)
        total += budget
        omit = req["op"] == "req" and req.get("get") == ["reply"]
        if omit:
            # These are historical diagnostic payloads, never live protocol-4 observations.
            assert new["data"]["reply"] == old["data"]["reply"]
            skipped += 1
        else:
            selected += budget
        if "map" in old.get("data", {}):
            a, b = old["data"]["map"], new["data"]["map"]
            assert map_semantics(a) == map_semantics(b), old["id"]
            map_before += tokens(a); map_after += tokens(b); map_count += 1
        if "hero" in old.get("data", {}) and not omit:
            # Available talent points are newly exposed in CLI 4. Old evidence cannot
            # establish them, so budget an explicit allowance instead of inventing values.
            hero_frames += 1
        rows.append({"id": old["id"], "op": req["op"], "before_tokens": tokens(old),
                     "after_tokens": budget, "omitted_normal_history_replay": omit})
    allowance = 32 * hero_frames
    combined = selected + allowance
    result = {
        "encoder": "production CompactProtocol", "encoding": "o200k_base",
        "frames": len(replies), "map_frames_verified": map_count,
        "original_tokens": before, "new_projection_tokens_including_immutable_history": total,
        "history_replays_omitted": skipped, "new_normal_flow_tokens": selected,
        "new_talent_field_allowance": {"tokens_per_hero": 32, "hero_frames": hero_frames, "tokens": allowance,
                                       "reason": "Old public evidence has no native available-point field; no values are inferred"},
        "combined_conservative_tokens": combined,
        "combined_reduction_percent": round(100 * (1 - combined / before), 3),
        "maps": {"before_tokens": map_before, "after_tokens": map_after,
                 "reduction_percent": round(100 * (1 - map_after / map_before), 3)},
        "source_sha256": {k: hashlib.sha256(v).hexdigest() for k, v in source.items()},
        "scope": "Offline public-protocol representation, not model billing or a new playthrough. Historical raw/reply remain immutable.",
    }
    for name, content in source.items():
        assert (args.trace_dir / name).read_bytes() == content
    (args.output / "report.json").write_text(json.dumps(result, indent=2) + "\n")
    (args.output / "per-message.json").write_text(json.dumps(rows, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    assert result["maps"]["reduction_percent"] >= 70, result
    assert result["combined_reduction_percent"] >= 50, result


if __name__ == "__main__":
    main()
