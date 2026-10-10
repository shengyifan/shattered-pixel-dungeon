#!/usr/bin/env python3
"""Validate research links and source fingerprints; never opens a game/profile.

--write-manifest records this reviewed source baseline. Without it, source hashes
are checked against the saved manifest. Markdown facts still require code review;
a valid link is not proof that its associated claim is correct.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[3]
RESEARCH = Path(__file__).resolve().parents[1]
MANIFEST = RESEARCH / "evidence/source-manifest.json"
BASELINE = "8405ae82f206e965d14b4a3b9d4fb282f2230e47"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-manifest", action="store_true")
    args = parser.parse_args()
    errors, sources = [], set()
    links = anchors = 0
    docs = sorted(RESEARCH.rglob("*.md"))
    for doc in docs:
        content = doc.read_text(encoding="utf-8")
        for match in re.finditer(r"\[[^\]]*\]\(([^)]+)\)", content):
            target = match.group(1)
            if "://" in target or target.startswith("#"):
                continue
            path, _, fragment = unquote(target).partition("#")
            resolved = (doc.parent / path).resolve()
            label = f"{doc.relative_to(ROOT)}: {target}"
            links += 1
            if args.write_manifest and resolved == MANIFEST:
                continue
            if not resolved.is_file():
                errors.append("Missing link: " + label)
                continue
            line_match = re.fullmatch(r"L(\d+)(?:-L?(\d+))?", fragment)
            if line_match:
                anchors += 1
                count = len(resolved.read_text(encoding="utf-8").splitlines())
                start = int(line_match.group(1))
                end = int(line_match.group(2) or start)
                if not 1 <= start <= end <= count:
                    errors.append("Invalid line anchor: " + label)
            if resolved.is_relative_to(ROOT) and not resolved.is_relative_to(ROOT / "memory"):
                sources.add(resolved)
    records = [dict(path=str(p.relative_to(ROOT)), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
               for p in sorted(sources)]
    if args.write_manifest and not errors:
        # Exclude documentation-only commits from the stated research baseline.
        changed = subprocess.check_output(
            ["git", "diff", BASELINE, "--name-only", "--", "core", "SPD-classes", "build.gradle"],
            cwd=ROOT, text=True).strip()
        if changed:
            errors.append("Gameplay source differs from the reviewed baseline: " + changed)
        else:
            MANIFEST.write_text(json.dumps(dict(
                source_baseline=BASELINE, game_version="4.0.2", game_version_code=922,
                research_date="2026-10-10", scope="Files linked by research documents; not exhaustive source coverage",
                sources=records), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    elif not args.write_manifest:
        if not MANIFEST.is_file():
            errors.append("Missing source manifest")
        else:
            saved = json.loads(MANIFEST.read_text(encoding="utf-8"))
            expected = {r["path"]: r["sha256"] for r in saved["sources"]}
            actual = {r["path"]: r["sha256"] for r in records}
            for path in sorted(set(expected) | set(actual)):
                if expected.get(path) != actual.get(path):
                    errors.append("Source fingerprint changed or reference set changed: " + path)
    result = dict(markdown_files=len(docs), local_links=links, line_anchors=anchors,
                  source_files=len(sources), errors=errors)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
