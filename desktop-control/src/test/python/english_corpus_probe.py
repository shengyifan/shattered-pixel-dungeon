#!/usr/bin/env python3
"""Offline English projection diagnostics from closed fixtures' PUBLIC response JSON only.

Never opens an engine, UI, SQLite database, save, hidden assertion stream or formal profile.
The Java test tool revalidates every trace, excludes active profiles and summarizes every
unavailable text leaf instead of stopping at the first failure in a response.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import uuid

from fixture_smoke import freeze_runtime


def no_symlinks(root, candidate):
    current = root
    for part in candidate.relative_to(root).parts:
        current /= part
        if current.is_symlink():
            raise ValueError("Symbolic links are not accepted")


def checked_trace(root, value):
    path = Path(os.path.abspath(root / value))
    fixtures = root / "desktop-control/build/fixtures"
    if not path.is_relative_to(fixtures) or path.name != "public-trace.jsonl":
        raise ValueError("Only build/fixtures public-trace.jsonl inputs are accepted")
    no_symlinks(root, path)
    if not path.is_file():
        raise ValueError("Public trace does not exist")
    if not any(path.parent.glob("*-result.json")):
        raise ValueError("Fixture has no completed test report")
    return path


def collect(root, extra_traces=()):
    # Select only explicitly supplied current traces. Historical docs reports are
    # not a data source and no unrelated fixture directory is scanned.
    candidates = set(extra_traces)
    skipped = []
    traces = []
    for value in sorted(candidates):
        try:
            traces.append(str(checked_trace(root, value).relative_to(root)))
        except ValueError as rejected:
            skipped.append({"candidate": value, "reason": str(rejected)})
    return sorted(set(traces)), skipped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-build", action="store_true", help="Use the current compiled test classpath without rebuilding")
    parser.add_argument("--trace", action="append", default=[], help="Explicit closed build/fixtures/.../public-trace.jsonl; repeat to select a corpus")
    parser.add_argument("--baseline-inputs", help="Reuse an earlier build/english-corpus/.../inputs.json corpus exactly; never overwrite that report")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    baseline = None
    if args.baseline_inputs:
        baseline = Path(os.path.abspath(root / args.baseline_inputs))
        if not baseline.is_relative_to(root / "desktop-control/build/english-corpus") or baseline.name != "inputs.json":
            raise ValueError("Baseline must be a build/english-corpus inputs.json")
        no_symlinks(root, baseline)
        selected = json.loads(baseline.read_text())
        traces = [str(checked_trace(root, trace).relative_to(root)) for trace in selected["traces"]]
        skipped = []
        if args.trace:
            raise ValueError("An exact baseline cannot be combined with new traces")
    else:
        traces, skipped = collect(root, args.trace)
    if not traces:
        raise SystemExit("No eligible closed fixture public traces")
    if not args.no_build:
        subprocess.run([str(root / "gradlew"), ":desktop-control:writeTestRuntimeClasspath", "--console=plain"], cwd=root, check=True)
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    output_dir = root / "desktop-control/build/english-corpus" / uuid.uuid4().hex
    output_dir.mkdir(parents=True)
    manifest = {"test_only": True, "input_kind": "public_fixture_responses", "runtime_id": runtime_id,
                "traces": traces, "selection_skipped": skipped,
                "baseline_inputs": str(baseline.relative_to(root)) if baseline else None,
                "total_input_bytes": sum((root / trace).stat().st_size for trace in traces)}
    manifest_path = output_dir / "inputs.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    output = output_dir / "report.json"
    # This main inspects protocol-5 source/diagnostic sidecars, never a launcher or old-language reverse dictionary.
    subprocess.run(["java", "-Xmx1g", "-cp", classpath,
                    "com.shatteredpixel.shatteredpixeldungeon.control.desktop.EnglishCorpusProbe",
                    str(root), str(manifest_path), str(output)], cwd=root, check=True)


if __name__ == "__main__":
    main()
