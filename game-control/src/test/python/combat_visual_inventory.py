#!/usr/bin/env python3
"""Attribute every visual source, then require exact, reviewed source-site dispositions."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import tempfile

CLASSIFICATIONS = {
    "covered_current", "covered_inspection", "covered_history", "required_uncovered",
    "cosmetic_reviewed", "not_player_visible", "unresolved", "scope_exclusion",
}
SEMANTIC_CLASSIFICATIONS = {
    "semantic_required", "redundant", "decorative", "nonvisual", "out_of_scope", "unresolved",
}
SEMANTIC_FIELDS = ("owner", "key", "allowed_fields")


def _nonempty_string(value):
    return isinstance(value, str) and bool(value.strip())


def _reviewed_outputs(outputs, label):
    if not isinstance(outputs, list) or not outputs:
        raise ValueError(label + " needs at least one explicit semantic output")
    for output in outputs:
        if not isinstance(output, dict) or any(not output.get(field) for field in SEMANTIC_FIELDS):
            raise ValueError(label + " needs owner, key, and allowed_fields")
        if not _nonempty_string(output["owner"]) or not _nonempty_string(output["key"]):
            raise ValueError(label + " has an invalid owner or key")
        fields = output["allowed_fields"]
        if (not isinstance(fields, list) or not fields
                or any(not _nonempty_string(field) or field == "*" for field in fields)
                or len(fields) != len(set(fields))):
            raise ValueError(label + " needs explicit, unique allowed fields")


def _reviewed_tests(tests, label, root):
    if not isinstance(tests, list) or not tests:
        raise ValueError(label + " needs focused test references")
    for reference in tests:
        if not _nonempty_string(reference) or not re.fullmatch(r"[A-Za-z_$][\w$]*#[A-Za-z_$][\w$]*", reference):
            raise ValueError(label + " needs ClassName#method test references")
        if root is not None:
            class_name, method = reference.split("#")
            candidates = list((root / "game-control/src/test").rglob(class_name + ".java"))
            candidates += list((root / "desktop-control/src/test").rglob(class_name + ".java"))
            python_tests = list((root / "game-control/src/test/python").glob("test_*.py"))
            python_tests += list((root / "desktop-control/src/test/python").glob("test_*.py"))
            found_java = any(re.search(r"\b" + re.escape(method) + r"\s*\(", candidate.read_text())
                             for candidate in candidates)
            found_python = any(re.search(r"\bclass\s+" + re.escape(class_name) + r"\b", candidate.read_text())
                               and re.search(r"\bdef\s+" + re.escape(method) + r"\s*\(", candidate.read_text())
                               for candidate in python_tests)
            if not (found_java or found_python):
                raise ValueError(label + " has an unresolved test reference: " + reference)


def _semantic_contracts(reviews, root=None):
    if reviews.get("format") != "combat_visual_reviews_v2":
        raise ValueError("Semantic review ledger must use combat_visual_reviews_v2")
    if reviews.get("runtime_verified_by_inventory") is not False:
        raise ValueError("A source inventory cannot assert runtime verification")
    contracts = reviews.get("semantic_contracts")
    if not isinstance(contracts, list):
        raise ValueError("Missing semantic review contracts")
    indexed = {}
    for contract in contracts:
        if not isinstance(contract, dict) or not _nonempty_string(contract.get("id")):
            raise ValueError("Semantic contract needs an ID")
        identity = contract["id"]
        if identity in indexed:
            raise ValueError("Duplicate semantic contract: " + identity)
        status = contract.get("classification")
        if status not in SEMANTIC_CLASSIFICATIONS or status == "unresolved":
            raise ValueError("Contract cannot silently approve an unresolved semantic classification")
        for field in ("source_producer", "reason"):
            if not _nonempty_string(contract.get(field)):
                raise ValueError(identity + " needs reviewed " + field)
        if status == "semantic_required":
            _reviewed_outputs(contract.get("outputs"), identity)
            if not _nonempty_string(contract.get("visibility_gate")):
                raise ValueError(identity + " needs a visibility gate")
            _reviewed_tests(contract.get("tests"), identity, root)
        elif status == "redundant":
            replacement = contract.get("replacement")
            if not isinstance(replacement, dict) or replacement.get("same_frame") is not True:
                raise ValueError(identity + " needs an explicit same-frame replacement")
            _reviewed_outputs(replacement.get("outputs"), identity + " replacement")
            if not _nonempty_string(replacement.get("visibility_gate")):
                raise ValueError(identity + " replacement needs a visibility gate")
            _reviewed_tests(replacement.get("tests"), identity + " replacement", root)
        elif status == "out_of_scope" and contract.get("scope") != "noncombat":
            raise ValueError(identity + " must identify its noncombat scope")
        indexed[identity] = contract
    return indexed


def _semantic_reviews(reviews, contracts):
    batches = reviews.get("semantic_reviews")
    if not isinstance(batches, list) or not batches:
        raise ValueError("Missing explicit semantic review batches")
    assigned = {}
    used_contracts = set()
    for batch in batches:
        if not isinstance(batch, dict) or not {"classification", "contract", "groups"}.issubset(batch) or set(batch) - {"classification", "contract", "groups", "evidence"}:
            raise ValueError("Semantic batch needs classification, contract, and exact group IDs")
        status, contract_id = batch["classification"], batch["contract"]
        if status not in SEMANTIC_CLASSIFICATIONS:
            raise ValueError("Unknown semantic classification")
        if status == "unresolved":
            if contract_id is not None:
                raise ValueError("Unresolved semantic reviews cannot reference an approved contract")
        elif contract_id not in contracts or contracts[contract_id]["classification"] != status:
            raise ValueError("Semantic classification has no matching reviewed contract: " + str(contract_id))
        else:
            used_contracts.add(contract_id)
        if not isinstance(batch["groups"], list) or not batch["groups"]:
            raise ValueError("Empty semantic review batch")
        evidence = batch.get("evidence", {})
        if not isinstance(evidence, dict) or set(evidence) - set(batch["groups"]):
            raise ValueError("Semantic evidence must refer only to exact groups in its batch")
        for group_id in batch["groups"]:
            if not _nonempty_string(group_id) or group_id in assigned:
                raise ValueError("Semantic group missing an ID or classified more than once: " + str(group_id))
            assigned[group_id] = (status, contract_id, evidence.get(group_id))
    unused = sorted(set(contracts) - used_contracts)
    if unused:
        raise ValueError("Unused semantic contracts: " + ", ".join(unused))
    return assigned


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


def check(inventory, reviews, root=None):
    contracts = _semantic_contracts(reviews, root)
    semantic_reviews = _semantic_reviews(reviews, contracts)
    observed = {entry["id"]: entry for entry in inventory["entries"]}
    if len(observed) != len(inventory["entries"]):
        raise ValueError("Duplicate attributed source identity")
    expected = {}
    classifications = Counter()
    semantic_classifications = Counter()
    seen_groups = set()
    for group in reviews["groups"]:
        group_id = group.get("id")
        if not _nonempty_string(group_id) or group_id in seen_groups:
            raise ValueError("Review group missing an ID or duplicated: " + str(group_id))
        seen_groups.add(group_id)
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
        if group_id not in semantic_reviews:
            raise ValueError("Review group needs an exact semantic assignment: " + group_id)
        semantic_status, _, semantic_evidence = semantic_reviews[group_id]
        if status in {"covered_current", "covered_history", "covered_inspection"} and semantic_status in {"redundant", "decorative", "nonvisual", "out_of_scope"}:
            if not _nonempty_string(semantic_evidence):
                raise ValueError("Downgraded visual group needs source-specific semantic evidence: " + group_id)
        if semantic_status == "out_of_scope" and group.get("scope") != "noncombat":
            raise ValueError("Out-of-scope group must explicitly name its noncombat scope")
        for site in group["sites"]:
            identity = site["id"]
            if identity in expected:
                raise ValueError("Source site classified more than once")
            expected[identity] = (site, group)
            classifications[status] += 1
            semantic_classifications[semantic_status] += 1
    extras = sorted(set(semantic_reviews) - seen_groups)
    if extras:
        raise ValueError("Semantic assignments reference unknown groups: " + ", ".join(extras))
    retired = reviews.get("retired_sites", [])
    if not isinstance(retired, list):
        raise ValueError("Retired source sites must be an exact reviewed list")
    retired_ids = set()
    for entry in retired:
        if not isinstance(entry, dict) or any(not _nonempty_string(entry.get(field))
                                               for field in ("id", "old_group", "file", "method", "reason")):
            raise ValueError("Retired site needs identity, old group, source method, and review reason")
        identity = entry["id"]
        if identity in retired_ids or identity in observed or identity in expected:
            raise ValueError("Retired source site duplicated or still live: " + identity)
        retired_ids.add(identity)
    added = sorted(set(observed) - set(expected))
    removed = sorted(set(expected) - set(observed))
    changed = [identity for identity in sorted(set(expected) & set(observed))
               if expected[identity][0]["body_digest"] != observed[identity]["body_digest"]]
    outstanding = [identity for identity, (_, group) in expected.items()
                   if group["classification"] in {"required_uncovered", "unresolved"}
                   or semantic_reviews[group["id"]][0] == "unresolved"]
    outstanding_groups = sorted({expected[identity][1]["id"] for identity in outstanding})
    return {
        "format": "combat_visual_review_result_v2", "source_files": inventory["source_files"],
        "sites": len(observed), "review_groups": len(reviews["groups"]),
        "retired_sites": len(retired_ids),
        "runtime_verified_by_inventory": False,
        "checked": not (added or removed or changed or outstanding),
        "classifications": dict(classifications),
        "semantic_classifications": dict(semantic_classifications), "unreviewed": added,
        "removed": removed, "changed": changed, "outstanding": outstanding,
        "outstanding_groups": outstanding_groups,
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
    report = check(inventory, reviews, root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({**report, "entries": inventory["entries"]}, indent=2) + "\n")
    concise = {key: value for key, value in report.items()
               if key not in ("unreviewed", "removed", "changed", "outstanding", "outstanding_groups")}
    for key in ("unreviewed", "removed", "changed", "outstanding", "outstanding_groups"):
        concise[key + "_count"] = len(report[key])
        concise[key + "_sample"] = report[key][:12]
    print(json.dumps(concise, indent=2))
    if not report["checked"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
