"""Isolated protocol-9 decoder evidence; no game, profile, or transport is used.

Run from any directory with Python 3. The fixture invokes the production
decode_wire_response entrypoint, records expected/actual results and input
immutability, and writes its adjacent JSON evidence file. An expected acceptance
that is rejected is a reproduced contract gap, not a fixture-run failure.
"""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO / "desktop-control/client"))
from spdctl_client import DecodeError, decode_wire_response  # noqa: E402


def wire(identifier, data):
    return {"v": 9, "id": identifier, "s": "isolated_fixture_scope",
            "rev": "isolated_fixture_revision", "st": "completed", "data": data}


def case(name, data, read_fact, expected_fact):
    frame = wire(name, data)
    before = copy.deepcopy(frame)
    evidence = {"name": name, "expected": "accepted_with_fact_preserved",
                "input": before, "expected_fact": expected_fact}
    try:
        result = decode_wire_response(frame)
        actual_fact = read_fact(result.data)
        evidence.update(actual="accepted", actual_fact=actual_fact,
                        fact_preserved=actual_fact == expected_fact,
                        raw_preserved=result.raw == before)
    except DecodeError as error:
        evidence.update(actual="DecodeError", error=str(error),
                        raw_preserved=error.raw == before)
    evidence["input_unchanged"] = frame == before
    return evidence


def main():
    cases = []
    for stage in ("intact", "flawed", "cracked"):
        glow = {"variant": "crystal_blue", "kind": "crystal_condition", "stage": stage}
        cases.append(case(f"crystal_{stage}_inventory_shown",
                          {"inv": [{"loc": "backpack.0", "name": "fixture sword", "shown": {"glow": glow}}]},
                          lambda d: d["inv"][0]["shown"]["glow"], glow))
        cases.append(case(f"crystal_{stage}_packed_ui_shown",
                          {"ui": {"node_templates": [{"common": {}, "fields": ["id", "role", "shown"]}],
                                  "nodes": [[0, "fixture-control", "item", {"glow": glow}]]}},
                          lambda d: d["ui"]["nodes"][0]["shown"]["glow"], glow))
        cases.append(case(f"crystal_{stage}_world_item_glow",
                          {"cues": {"status": "last_observed", "cues": [
                              {"kind": "item_glow", "cell": 7, "appearance": glow}]}},
                          lambda d: d["cues"]["cues"][0]["appearance"], glow))
        # Native icon embedding uses another public representation of the same fact.
        cases.append(case(f"crystal_{stage}_nested_item_icon",
                          {"ui": {"nodes": [{"id": "fixture-control", "role": "item",
                                             "item_icon": {"symbol": "item_sword", "glow": glow}}]}},
                          lambda d: d["ui"]["nodes"][0]["item_icon"]["glow"], glow))

    for stage in ("cool", "warm", "hot"):
        glow = {"variant": "black", "kind": "explosive_heat", "stage": stage}
        cases.append(case(f"explosive_{stage}_inventory_control",
                          {"inv": [{"loc": "backpack.0", "name": "fixture sword", "shown": {"glow": glow}}]},
                          lambda d: d["inv"][0]["shown"]["glow"], glow))

    for field in ("broken_seal", "lit_candle", "nature_powered"):
        fact = {field: True}
        cases.append(case(f"{field}_world_item_status_control",
                          {"cues": {"status": "last_observed", "cues": [
                              {"kind": "item_status", "cell": 7, "appearance": fact}]}},
                          lambda d: d["cues"]["cues"][0]["appearance"], fact))

    sources = ["desktop-control/client/spdctl_client.py",
               "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/GameplayGlow.java",
               "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ItemSprite.java",
               "game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/Cli9ItemObservationTest.java"]
    result = {
        "evidence_kind": "isolated_offline_fixture",
        "scope": "Synthetic public responses only; no game launch, gameplay, save, or private audit access.",
        "baseline": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
        "sources": {name: hashlib.sha256((REPO / name).read_bytes()).hexdigest() for name in sources},
        "producer": "GameplayGlow.selected:64-65; ItemSprite.gameplayGlow:268-271",
        "existing_producer_tests": "Cli9ItemObservationTest:55-101 (read, not rerun by this fixture)",
        "decoder": "spdctl_client._glow:294-304; _shown:261-262; world item_glow:835-836",
        "cases": cases,
        "summary": {
            "total": len(cases),
            "accepted": sum(c["actual"] == "accepted" for c in cases),
            "unexpected_rejections": sum(c["actual"] == "DecodeError" for c in cases),
            "input_mutations": sum(not c["input_unchanged"] for c in cases),
            "raw_preservation_failures": sum(not c["raw_preserved"] for c in cases),
        },
    }
    output = Path(__file__).with_suffix(".json")
    output.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n")
    print(json.dumps(result["summary"], sort_keys=True))


if __name__ == "__main__":
    main()
