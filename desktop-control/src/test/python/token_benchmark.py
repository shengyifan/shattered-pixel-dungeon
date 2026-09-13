#!/usr/bin/env python3
"""Measure explicit public fixture pairs with o200k_base; no runtime dependencies.

Input: JSON {"samples":[{"name":"...","before":<v2 object>,"after":<real v3 object>}]}.
Both sides are minified identically. Install tiktoken only in a disposable build
virtual environment. This tool never discovers or opens game profiles or audits.
"""
import argparse
import json
from pathlib import Path


def measure(pairs, encoding):
    results = []
    for pair in pairs:
        entry = {"name": pair["name"]}
        for side in ("before", "after"):
            text = json.dumps(pair[side], ensure_ascii=False, separators=(",", ":"))
            entry[side] = {"bytes": len(text.encode("utf-8")), "tokens": len(encoding.encode(text, disallowed_special=()))}
        before, after = entry["before"]["tokens"], entry["after"]["tokens"]
        entry["token_reduction_percent"] = round(100 * (before - after) / before, 2) if before else None
        results.append(entry)
    return {"encoding": "o200k_base", "format": "minified UTF-8 JSON on both sides", "pairs": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    import tiktoken
    source = json.loads(args.input.read_text(encoding="utf-8"))
    report = measure(source["samples"], tiktoken.get_encoding("o200k_base"))
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
