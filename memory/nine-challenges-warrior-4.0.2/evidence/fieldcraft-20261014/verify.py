#!/usr/bin/env python3
"""Check the 2026-10-14 research supplement without opening a game or profile.

Checks all research-library local links/line anchors, and fingerprints only the
five reviewed supplement chapters and their directly cited source files. The
older research manifest remains immutable. A valid citation is not proof of a
claim; semantic source review is reported separately in README.md.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
LIBRARY = HERE.parents[1]
BASELINE = "bb0c91fb7c6fa8e33819c554427f81b6cf93f2f9"
MANIFEST = HERE / "source-manifest.json"
CHAPTER_NAMES = (
    "17-artifact-acquisition-and-use.md",
    "18-proactive-identification.md",
    "19-pitfalls-and-room-payoffs.md",
    "20-resource-conversion-fieldcraft.md",
    "21-active-wand-play.md",
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true",
                        help="Record reviewed supplement fingerprints at the pinned source commit")
    args = parser.parse_args()
    chapters = {LIBRARY / name for name in CHAPTER_NAMES}
    heading_docs = chapters | {LIBRARY / "00-run-plan.md", LIBRARY / "README.md",
                               ROOT / "memory/README.md", HERE / "README.md"}
    docs = sorted(set(LIBRARY.rglob("*.md")) | {ROOT / "memory/README.md"})
    errors, sources = [], set()
    links = anchors = heading_links = 0
    for chapter in chapters:
        if not chapter.is_file():
            errors.append(f"Missing supplement chapter: {chapter.name}")
    for doc in docs:
        for match in re.finditer(r"\[[^\]]*\]\(([^)]+)\)", doc.read_text(encoding="utf-8")):
            target = match.group(1)
            if "://" in target:
                continue
            path, _, fragment = unquote(target).partition("#")
            resolved = (doc.parent / path).resolve() if path else doc
            label = f"{doc.relative_to(ROOT)}: {target}"
            links += 1
            if args.record and resolved == MANIFEST:
                continue
            if not resolved.is_file():
                errors.append("Missing local link: " + label)
                continue
            line = re.fullmatch(r"L(\d+)(?:-L?(\d+))?", fragment)
            if line:
                anchors += 1
                count = len(resolved.read_text(encoding="utf-8").splitlines())
                start, end = int(line[1]), int(line[2] or line[1])
                if not 1 <= start <= end <= count:
                    errors.append("Invalid line anchor: " + label)
            elif fragment and doc in heading_docs and resolved.suffix == ".md":
                # Supplement headings contain plain Chinese/English text and numbering.
                titles = re.findall(r"^#{1,6}\s+(.+)$", resolved.read_text(encoding="utf-8"), re.M)
                slugs = {re.sub(r"[^\w\- ]", "", title.lower()).replace(" ", "-")
                         for title in titles}
                heading_links += 1
                if fragment not in slugs:
                    errors.append("Invalid supplement heading link: " + label)
            if (doc in chapters and resolved.is_relative_to(ROOT)
                    and not resolved.is_relative_to(ROOT / "memory")):
                sources.add(resolved)
    records = []
    for source in sorted(sources):
        relative = source.relative_to(ROOT).as_posix()
        content = source.read_bytes()
        baseline = subprocess.run(["git", "show", f"{BASELINE}:{relative}"],
                                  cwd=ROOT, capture_output=True)
        if baseline.returncode or baseline.stdout != content:
            errors.append("Cited source differs from pinned baseline: " + relative)
        records.append({"path": relative, "sha256": digest(content)})
    documents = [{"path": p.relative_to(ROOT).as_posix(), "sha256": digest(p.read_bytes())}
                 for p in sorted(chapters) if p.is_file()]
    current = {"source_baseline": BASELINE, "research_date": "2026-10-14",
               "game_version": "4.0.2", "game_version_code": 922,
               "scope": "Five supplement chapters and directly cited sources; no runtime or playthrough validation",
               "documents": documents, "sources": records}
    if args.record and not errors:
        MANIFEST.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    elif not args.record:
        if not MANIFEST.is_file():
            errors.append("Missing supplement source manifest")
        elif json.loads(MANIFEST.read_text(encoding="utf-8")) != current:
            errors.append("Supplement document/source fingerprints or reference set changed")
    result = {"source_baseline": BASELINE, "markdown_files_checked": len(docs),
              "local_links": links, "line_anchors": anchors,
              "supplement_heading_links": heading_links,
              "supplement_chapters": len(documents), "cited_source_files": len(sources),
              "game_requests_sent": 0, "errors": errors}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main())
