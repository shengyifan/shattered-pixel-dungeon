#!/usr/bin/env python3
"""Check attributed core String operations against reviewed non-display/implementation sites.

Requires a JDK and an existing runtime classpath file (or --classpath-file). All core
sources are attributed afresh, with source preferred over old dependency classes.
The scan does not invoke Gradle, generate game classes, or initialize the game.
"""
import argparse
import base64
from collections import Counter
import json
from pathlib import Path
import subprocess
import tempfile

FIELDS = (
    "file", "line", "kind", "start", "end", "left_start", "left_end", "right_start", "right_end",
    "left_type", "right_type", "method", "owner", "context", "source", "parent", "category",
    "left_kind", "right_kind", "expression",
)
NUMBERS = ("line", "start", "end", "left_start", "left_end", "right_start", "right_end")
ENCODED = ("method", "owner", "context", "source", "expression")


def scan(root, classpath_file):
    helper = root / "game-control/src/test/source-text/TextCompositionInventory.java"
    classpath = classpath_file.read_text().strip()
    if not classpath:
        raise ValueError("The existing runtime classpath must not be empty")
    with tempfile.TemporaryDirectory(prefix="spdctl-text-coverage-") as output:
        subprocess.run(["javac", "--release", "11", "-encoding", "UTF-8", "-d", output, str(helper)], check=True)
        process = subprocess.run(
            ["java", "-cp", output, "TextCompositionInventory", str(root), classpath],
            capture_output=True, text=True, check=False,
        )
    if process.returncode:
        raise RuntimeError("Current source attribution failed; coverage is not certified:\n" + process.stderr)
    findings = []
    for line in process.stdout.splitlines():
        fields = line.split("\t")
        if len(fields) != len(FIELDS):
            raise ValueError("Unexpected inventory record")
        finding = dict(zip(FIELDS, fields))
        for field in ENCODED:
            finding[field] = base64.b64decode(finding[field]).decode("utf-8")
        for field in NUMBERS:
            finding[field] = int(finding[field])
        findings.append(finding)
    counts = {line.split("\t")[0]: int(line.split("\t")[1]) for line in process.stderr.splitlines() if line.startswith(("CATALOG_CONSTRUCTORS\t", "SOURCE_FILES\t"))}
    return findings, counts


def key(finding):
    return finding["file"], finding["method"], finding["kind"], finding["expression"]


def check(findings, exclusions):
    # No file-wide or method-wide exemptions. Each retained operation has an exact,
    # javac-rendered expression and an occurrence count so newly added loss points fail.
    expected = {key(entry): entry for entry in exclusions["entries"]}
    if len(expected) != len(exclusions["entries"]):
        raise ValueError("Duplicate reviewed exclusion")
    observed = Counter(key(finding) for finding in findings)
    unreviewed = [finding for finding in findings if key(finding) not in expected]
    mismatches = []
    for identity, entry in expected.items():
        count = observed[identity]
        if count != entry["count"]:
            mismatches.append({**entry, "observed_count": count})
    classified = Counter()
    for finding in findings:
        entry = expected.get(key(finding))
        if entry:
            classified[entry["reason"]] += 1
    return {
        "source": "javac parse and analyze of current core sources",
        "checked": not unreviewed and not mismatches,
        "operations": len(findings),
        "kinds": dict(Counter(finding["kind"] for finding in findings)),
        "classifications": dict(classified),
        "unreviewed": unreviewed,
        "changed_or_removed_exclusions": mismatches,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[4])
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--output", type=Path, help="Optional full JSON inventory artifact")
    parser.add_argument("--inventory-only", action="store_true", help="Report all operations without applying the reviewed exclusion ledger")
    args = parser.parse_args()
    root = args.root.resolve()
    classpath_file = args.classpath_file or root / "desktop-control/build/runtime-classpath.txt"
    findings, counts = scan(root, classpath_file)
    if args.inventory_only:
        report = {"source": "javac parse and analyze of current core sources", "operations": len(findings), "findings": findings}
    else:
        exclusions = json.loads((root / "game-control/src/test/resources/text-composition-exclusions.json").read_text())
        report = check(findings, exclusions)
    report["source_files"] = counts.get("SOURCE_FILES")
    report["verified_catalog_constructors"] = counts.get("CATALOG_CONSTRUCTORS")
    if args.output:
        args.output.write_text(json.dumps({**report, "findings": findings}, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if not args.inventory_only and not report["checked"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
