#!/usr/bin/env python3
"""Replay explicit public v6 transports through the production v7 structure codec.

Only UI row/operation dictionaries are expanded before replay. Existing map,
label and binding compression is retained. No game, profile, save or audit is
opened. Generated files belong in an ignored artifact directory.
"""
import argparse
import copy
import hashlib
import importlib.metadata
import json
from pathlib import Path
import subprocess
import sys
from collections import defaultdict

from d9_token_study import equal, distribution, wire
from standalone_token_study import source_files

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "client"))
from spdctl_client import decode_wire_response
from historical_v6_client import decode_wire_response as decode_v6


def expand_legacy_records(frame):
    """Expand only known v6 UI structures, never arbitrary unknown subtrees."""
    result = copy.deepcopy(frame)

    def observation(value):
        if not isinstance(value, dict):
            return
        ui = value.get("ui")
        if isinstance(ui, dict) and isinstance(ui.get("nodes"), list):
            nodes = []
            for raw in ui["nodes"]:
                if isinstance(raw, list):
                    shape = ui["node_shapes"][raw[0]]
                    if len(raw) != len(shape) + 1 or len(set(shape)) != len(shape):
                        raise ValueError("Invalid historical node shape")
                    node = dict(zip(shape, copy.deepcopy(raw[1:])))
                else:
                    node = copy.deepcopy(raw)
                if type(node.get("ops")) is int:
                    node["ops"] = copy.deepcopy(ui["op_defs"][node["ops"]])
                nodes.append(node)
            ui["nodes"] = nodes
            ui.pop("node_shapes", None)
            ui.pop("op_defs", None)
        for key in ("before", "after"):
            if key in value:
                value[key] = copy.deepcopy(value[key])
                observation(value[key])

    observation(result.get("data"))
    # This is a v7 representation of historical content, not a new observation.
    result["v"] = 7
    return result


def normalized_frame(frame):
    """Only version/table representation differs; no public values are removed."""
    result = copy.deepcopy(frame)
    result["v"] = 7
    data = result.get("data")
    if isinstance(data, dict):
        def clear_tables(value):
            if not isinstance(value, dict):
                return
            value.pop("act_templates", None)
            value.pop("inv_templates", None)
            ui = value.get("ui")
            if isinstance(ui, dict):
                for key in ("node_templates", "node_shapes", "op_defs"):
                    ui.pop(key, None)
            for key in ("before", "after"):
                clear_tables(value.get(key))
        clear_tables(data)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--d9-trace", type=Path, required=True)
    parser.add_argument("--current-fixture", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()
    import tiktoken
    if importlib.metadata.version("tiktoken") != "0.12.0":
        raise RuntimeError("The reference benchmark requires tiktoken==0.12.0")
    tokenizer = tiktoken.get_encoding("o200k_base")
    def tokens(frame):
        return len(tokenizer.encode(wire(frame), disallowed_special=()))

    args.output.mkdir(parents=True, exist_ok=True)
    records, hashes = [], {}
    for group, root, stop in source_files(args.d9_trace, args.current_fixture):
        send_path, recv_path = root / "send.raw", root / "recv.raw"
        send, recv = send_path.read_bytes(), recv_path.read_bytes()
        if not send.endswith(b"\n") or not recv.endswith(b"\n"):
            raise ValueError("Incomplete public transport")
        hashes[str(root)] = {"send.raw": hashlib.sha256(send).hexdigest(),
                             "recv.raw": hashlib.sha256(recv).hexdigest()}
        requests = [json.loads(line) for line in send.splitlines()]
        replies = [json.loads(line) for line in recv.splitlines()]
        if len(requests) != len(replies) or any(a["id"] != b["id"] for a,b in zip(requests,replies)):
            raise ValueError("Public request/response pairing mismatch")
        if stop:
            replies = replies[:next(i+1 for i,r in enumerate(replies) if r["id"] == stop)]
        if args.limit:
            replies = replies[:args.limit]
        records.extend((group, frame) for frame in replies)

    replay = args.output / "expanded-public.jsonl"
    with replay.open("w", encoding="utf-8") as output:
        for _, frame in records:
            output.write(wire(expand_legacy_records(frame)))
    subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-cp",
                    args.classpath_file.read_text().strip(),
                    "com.shatteredpixel.shatteredpixeldungeon.control.game.Cli7StructureProbe",
                    str(replay), str(args.output)], check=True)

    candidates = {}
    for mode in ("sharing", "templates", "combined"):
        candidates[mode] = [json.loads(line) for line in (args.output / (mode + ".jsonl")).read_text().splitlines()]
        if len(candidates[mode]) != len(records):
            raise ValueError("Production replay output count mismatch")
    totals = defaultdict(lambda: defaultdict(list))
    rows, examples, checked = [], {}, 0
    for index, (group, original) in enumerate(records):
        counts = {"baseline": tokens(original)}
        expected = normalized_frame(decode_v6(original).frame)
        for mode, frames in candidates.items():
            packet = frames[index]
            decoded = normalized_frame(decode_wire_response(packet).frame)
            if not equal(expected, decoded):
                raise AssertionError((group, original["id"], mode, "Python decoded public value mismatch"))
            counts[mode] = tokens(packet)
            checked += 1
        for mode, count in counts.items():
            totals[group][mode].append(count)
        rows.append({"group": group, "id": original["id"], **counts})
        if group not in examples and original.get("data", {}).get("hero"):
            examples[group] = {"original": original, "combined": candidates["combined"][index]}
        if (index + 1) % 500 == 0:
            print(json.dumps({"verified_and_measured": index + 1, "frames": len(records)}), flush=True)
    report = {"encoder": "production CompactStructures", "protocol": 7,
              "measurement": "tiktoken 0.12.0 / o200k_base, complete minified JSON + LF; not billing",
              "boundary": "Historical public content re-encoded structurally, not a new game session; source omissions cannot be reconstructed",
              "frames": len(records), "java_roundtrips": checked, "python_comparisons": checked,
              "sources": hashes, "groups": {}}
    for group, modes in totals.items():
        baseline = sum(modes["baseline"])
        summary = {}
        for mode, values in modes.items():
            summary[mode] = {**distribution(values), "total": sum(values),
                             "reduction_percent": 100*(baseline-sum(values))/baseline,
                             "frames_larger": sum(a>b for a,b in zip(values,modes["baseline"])),
                             "frames_smaller": sum(a<b for a,b in zip(values,modes["baseline"]))}
        report["groups"][group] = summary
    for root, names in hashes.items():
        for name, expected_hash in names.items():
            if hashlib.sha256((Path(root)/name).read_bytes()).hexdigest() != expected_hash:
                raise AssertionError("Source transport changed")
    for name, value in (("metrics.json", report), ("per-frame.json", rows), ("examples.json", examples)):
        (args.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report["groups"], indent=2))
    if not args.limit and any(sum(modes["combined"]) >= sum(modes["baseline"]) for modes in totals.values()):
        raise AssertionError("Combined token total must be smaller for each corpus")


if __name__ == "__main__":
    main()
