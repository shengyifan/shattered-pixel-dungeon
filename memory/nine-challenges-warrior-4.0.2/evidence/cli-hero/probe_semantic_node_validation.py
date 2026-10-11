#!/usr/bin/env python3
"""Offline synthetic transport probe. No JVM, profile, saves, or game inputs."""
import copy
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "desktop-control/client"))
from spdctl_client import DecodeError, decode_wire_response

def probe(name, body):
    wire = {"v": 9, "id": "audit.1", "s": "s1", "rev": "r1",
            "st": "completed", "data": body}
    original = copy.deepcopy(wire)
    try:
        decoded = decode_wire_response(wire)
        result = {"case": name, "accepted": True, "decoded": decoded.data}
    except DecodeError as error:
        result = {"case": name, "accepted": False, "error": str(error),
                  "raw_preserved": error.raw == original}
    result["input_unchanged"] = wire == original
    return result

bad = {"samples": [{"total": 16, "filled": 8, "with_shield": 8,
                    "basis": "displayed"}]}
valid = {"samples": [{"total": 21, "filled": 22, "with_shield": 22,
                      "incoming_dot": 0, "basis": "displayed"}]}
rows = [
    probe("hero_missing_incoming_dot", {"hero": {"health_estimate": bad}}),
    probe("node_missing_incoming_dot", {"ui": {"nodes": [
        {"id": "c1", "role": "health_bar", "health_estimate": bad}]}}),
    probe("hero_invalid_turn_sweep", {"hero": {"turn_progress": {"sweep": 2}}}),
    probe("node_invalid_turn_sweep", {"ui": {"nodes": [
        {"id": "c1", "turn_progress": {"sweep": 2}}]}}),
    probe("valid_values_and_unknown_metadata_preserved", {"hero": {
        "hp": 4, "ht": 20, "shield": 0, "talents": [{"tier": 1,
        "name": "Hearty Meal", "points": 0}], "tp": [0, 0, 0, 0],
        "buffs": [{"name": "Warrior Shield", "shown": {
            "counter": "-2", "counter_kind": "cooldown",
            "future_public_annotation": {"value": None}}}],
        "health_estimate": valid, "turn_progress": {"sweep": 0}},
        "ui": {"nodes": [{"id": "c1", "selected": False,
            "talent_points": {"spent": 0, "max": 2},
            "subject": {"kind": "hero_buff", "index": 0}}]}}),
]
assert rows[0]["accepted"] is False and rows[0]["raw_preserved"]
assert rows[1]["accepted"] is True
assert rows[2]["accepted"] is False and rows[2]["raw_preserved"]
assert rows[3]["accepted"] is True
assert rows[4]["accepted"] is True
assert all(row["input_unchanged"] for row in rows)
print(json.dumps({"baseline": "d04c8f862", "evidence_kind": "synthetic_offline_decoder",
                  "cases": rows}, indent=2, ensure_ascii=False))
