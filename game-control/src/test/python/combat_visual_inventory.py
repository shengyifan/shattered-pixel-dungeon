#!/usr/bin/env python3
"""Attribute every visual source, then require exact, reviewed source-site dispositions."""
import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess
import tempfile

CLASSIFICATIONS = {
    "covered_current", "covered_inspection", "covered_history", "required_uncovered",
    "cosmetic_reviewed", "not_player_visible", "unresolved", "scope_exclusion",
}


def scan(root, classpath_file, additional=()):
    classpath = classpath_file.read_text().strip()
    source = root / "game-control/src/test/source-text/CombatVisualInventory.java"
    with tempfile.TemporaryDirectory(prefix="spdctl-combat-source-") as output:
        subprocess.run(["javac", "--release", "11", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", output, str(source)], check=True)
        result = subprocess.run(["java", "-Xmx2g", "-cp", output + ":" + classpath,
                                 "CombatVisualInventory", str(root), classpath, *map(str, additional)],
                                capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError("Unresolved combat source attribution:\n" + result.stderr)
    return json.loads(result.stdout)


def check(inventory, reviews):
    observed = {entry["id"]: entry for entry in inventory["entries"]}
    if len(observed) != len(inventory["entries"]):
        raise ValueError("Duplicate attributed source identity")
    expected = {}
    classifications = Counter()
    for group in reviews["groups"]:
        status = group["classification"]
        if status not in CLASSIFICATIONS:
            raise ValueError("Unknown visual classification")
        if status == "scope_exclusion" and group.get("scope") != "noncombat":
            raise ValueError("Scope exclusions must explicitly identify a noncombat surface")
        for key in ("id", "reason", "trigger", "visible_evidence", "visibility_gate", "lifecycle", "route", "tests"):
            if not group.get(key):
                raise ValueError("Missing reviewed visual evidence: " + key)
        if not group.get("sites"):
            raise ValueError("Empty review group")
        for site in group["sites"]:
            identity = site["id"]
            if identity in expected:
                raise ValueError("Source site classified more than once")
            expected[identity] = (site, group)
            classifications[status] += 1
    added = sorted(set(observed) - set(expected))
    removed = sorted(set(expected) - set(observed))
    changed = [identity for identity in sorted(set(expected) & set(observed))
               if expected[identity][0]["body_digest"] != observed[identity]["body_digest"]]
    outstanding = [identity for identity, (_, group) in expected.items()
                   if group["classification"] in {"required_uncovered", "unresolved"}]
    return {
        "format": "combat_visual_review_result_v1", "source_files": inventory["source_files"],
        "sites": len(observed), "review_groups": len(reviews["groups"]),
        "runtime_verified_by_inventory": False,
        "checked": not (added or removed or changed or outstanding),
        "classifications": dict(classifications), "unreviewed": added,
        "removed": removed, "changed": changed, "outstanding": outstanding,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[4])
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--inventory-only", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    inventory = scan(root, args.classpath_file or root / "desktop-control/build/test-runtime-classpath.txt")
    if args.inventory_only:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(inventory, indent=2) + "\n")
        print(json.dumps({"source_files": inventory["source_files"], "sites": len(inventory["entries"]), "runtime_verified_by_inventory": False}))
        return
    reviews = json.loads((root / "game-control/src/test/resources/combat-visual-reviews.json").read_text())
    report = check(inventory, reviews)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({**report, "entries": inventory["entries"]}, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if not report["checked"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
