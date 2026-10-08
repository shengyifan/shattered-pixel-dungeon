#!/usr/bin/env python3
"""Print a complete supplied public map; never connect to or operate a game."""
import argparse
import json
import sys

sys.dont_write_bytecode = True
from public_snapshot import (SnapshotError, add_response_argument, decoded_cells,
                             load_snapshot, report_error)

SYMBOLS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+,-./:;<=>[]^_`{|}~"


def emit(label, value):
    print(label, json.dumps(value, ensure_ascii=False, separators=(",", ":")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="Print the complete decoded observation as JSON")
    add_response_argument(parser)
    args = parser.parse_args()
    snapshot = None
    try:
        snapshot = load_snapshot(args.response)
        frame = snapshot.frame
        context = snapshot.context()
        if args.json:
            print(json.dumps({**context, "frame": frame.frame}, ensure_ascii=False,
                             separators=(",", ":")))
            return 0
        data = frame.data
        map_data = data.get("map")
        hero = data.get("hero")
        cues = data.get("cues")
        emit("Snapshot", {**context, "id": frame.frame.get("id"),
             "s": frame.frame.get("s"), "rev": frame.frame.get("rev"),
             "st": frame.status, "scene": data.get("scene"),
             "map_context": cues.get("map_context") if isinstance(cues, dict) else None,
             "note": "Supplemental complete known map, not an action or a route. "
                     "Unknown cells are not traversability promises. All small branches remain visible. "
                     "An exit terrain label does not establish the transition destination."})
        emit("Hero", data.get("hero"))
        emit("Presentation diagnostics", frame.frame.get("pres"))
        if not isinstance(map_data, dict):
            emit("No map in this observation", {"ui": data.get("ui"), "cues": data.get("cues")})
            emit("All public entities", data.get("entities", []))
            return 0
        width, height = map_data["w"], map_data["h"]
        cells = decoded_cells(frame)
        by_cell = {c["cell"]: c for c in cells}
        legend, lookup = [], {}
        for cell in cells:
            descriptor = {k: v for k, v in cell.items()
                          if k not in ("cell", "x", "y", "visibility", "environment")}
            key = json.dumps(descriptor, sort_keys=True, ensure_ascii=False)
            if key not in lookup:
                index = len(legend)
                symbol = SYMBOLS[index] if index < len(SYMBOLS) else "T" + str(index)
                lookup[key] = symbol
                legend.append({"symbol": symbol, **descriptor})
        token_width = max([1] + [len(x["symbol"]) for x in legend])
        emit("Legend", legend)
        emit("Grid", {"w": width, "h": height, "unknown": "?", "hero": "@",
                      "token_width": token_width, "known_cells": len(cells)})
        print("    " + "".join(str(x // 10 or " ").rjust(token_width)
                              if x % 10 == 0 else " " * token_width for x in range(width)))
        print("    " + "".join(str(x % 10).rjust(token_width) for x in range(width)))
        hero_cell = hero.get("cell") if isinstance(hero, dict) else None
        for y in range(height):
            row = []
            for x in range(width):
                q = y * width + x
                cell = by_cell.get(q)
                if q == hero_cell:
                    symbol = "@"
                elif cell is None:
                    symbol = "?"
                else:
                    descriptor = {k: v for k, v in cell.items()
                                  if k not in ("cell", "x", "y", "visibility", "environment")}
                    symbol = lookup[json.dumps(descriptor, sort_keys=True, ensure_ascii=False)]
                row.append(symbol.rjust(token_width))
            print(str(y).zfill(2) + "  " + "".join(row))
        # No descriptions, diagnostics, effects or cues are stripped from these records.
        emit("Complete map metadata and visibility rows", {
            k: v for k, v in map_data.items() if k not in ("cells", "decoded_cells")})
        emit("Hero underlying cell", by_cell.get(hero_cell))
        emit("Complete cell environments", [c for c in cells if c.get("environment")])
        emit("All public entities", data.get("entities", []))
        emit("All public cues", data.get("cues"))
        emit("All public feedback", data.get("ui", {}).get("feedback"))
        return 0
    except (SnapshotError, OSError, ValueError, TypeError, KeyError) as error:
        return report_error(error, snapshot)



if __name__ == "__main__":
    raise SystemExit(main())
