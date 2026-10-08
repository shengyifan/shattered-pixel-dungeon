#!/usr/bin/env python3
"""Look up public cells in one independently decoded current response. Read-only."""
import argparse
import json
import re
import sys

sys.dont_write_bytecode = True
from public_snapshot import (SnapshotError, add_response_argument,
                             load_snapshot, map_cell, report_error)


def emit(value):
    print(json.dumps(value, ensure_ascii=False, indent=2))


def cue_covers(cue, cell):
    appearance = cue.get("appearance") or {}
    return (cue.get("cell") == cell or cell in (cue.get("cells") or [])
            or cell in (appearance.get("cells") or []))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cells", nargs="+", metavar="CELL_OR_X,Y",
                        help="One or more decimal cell numbers or zero-based x,y coordinates")
    add_response_argument(parser)
    args = parser.parse_args()
    snapshot = None
    try:
        snapshot = load_snapshot(args.response)
        current = snapshot.frame
        data = current.data
        game_map = data.get("map")
        hero = data.get("hero")
        cue_context = data.get("cues")
        metadata = snapshot.context()
        metadata.update({"depth": hero.get("depth") if isinstance(hero, dict) else None,
                         "hero_cell": hero.get("cell") if isinstance(hero, dict) else None,
                         "map_context": cue_context.get("map_context") if isinstance(cue_context, dict) else None})
        if not isinstance(game_map, dict):
            snapshot.fail("analysis", "NO_MAP", "No public map in this current observation")
        width, height = game_map["w"], game_map["h"]
        entities = data.get("entities", [])
        cues = cue_context.get("cues") if isinstance(cue_context, dict) else None
        results = []
        for query in args.cells:
            if re.fullmatch(r"[0-9]+", query):
                cell = int(query)
                x, y = cell % width, cell // width
            elif re.fullmatch(r"[0-9]+,[0-9]+", query):
                x, y = map(int, query.split(","))
                cell = y * width + x
            else:
                results.append({"query": query, "error": "Expected CELL or zero-based X,Y"})
                continue
            if not (0 <= x < width and 0 <= y < height):
                results.append({"query": query, "error": "Outside this frame's map",
                                "cell": cell, "x": x, "y": y})
                continue
            known = map_cell(current, cell)
            results.append({
                "query": query, "cell": cell, "x": x, "y": y,
                "known": known is not None,
                "terrain": known.get("terrain") if known else None,
                "name": known.get("name") if known else None,
                "visibility": known.get("visibility") if known else None,
                "environment": known.get("environment") if known else None,
                "public_cell": known,
                "entities": [entity for entity in entities if entity.get("cell") == cell],
                "cues": [cue for cue in cues if cue_covers(cue, cell)] if cues is not None else None,
            })
        emit({**metadata, "w": width, "h": height,
              "cue_context": ({key: value for key, value in cue_context.items() if key != "cues"}
                              if isinstance(cue_context, dict) else None),
              "unlocated_cues": [cue for cue in cues if "cell" not in cue and "cells" not in cue
                                 and not (cue.get("appearance") or {}).get("cells")] if cues is not None else None,
              "note": "This is one frozen public observation, not an action binding for a later frame. "
                      "Unknown terrain and absent entities do not establish safety. "
                      "A trap entity can remain after its terrain becomes Triggered trap. "
                      "Use map.py for the complete map and all public cues.",
              "cells": results})
        return 2 if any("error" in result for result in results) else 0
    except (SnapshotError, OSError, ValueError, TypeError, KeyError) as error:
        return report_error(error, snapshot)



if __name__ == "__main__":
    raise SystemExit(main())
