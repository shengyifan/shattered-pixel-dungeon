#!/usr/bin/env python3
"""Verify this frozen public record offline; never connect, replay, or read a profile."""
import gzip
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "desktop-control/client"))
from spdctl_client import decode_client_response, map_cell, validate_intent


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def visible_goo_hp(data):
    """Only use text attached to this frame's explicitly bound, visible Goo."""
    nodes = data.get("ui", {}).get("nodes", [])
    entities = data.get("entities", [])
    values = []
    for node in nodes:
        subject = node.get("subject", {})
        if node.get("role") != "status" or subject.get("kind") != "entity":
            continue
        entity = entities[subject["index"]]
        if entity.get("name") != "Goo" or entity.get("kind") != "character":
            continue
        values.extend(child["text"] for child in nodes
                      if child.get("parent") == node["id"]
                      and re.fullmatch(r"[0-9]+/[0-9]+", child.get("text", "")))
    assert len(set(values)) <= 1
    return values[0] if values else None


manifest = json.loads((HERE / "manifest.json").read_text())
payloads = {}
for name, meta in manifest["files"].items():
    packed = (HERE / meta["archive"]).read_bytes()
    assert len(packed) == meta["archive_bytes"] and sha(packed) == meta["archive_sha256"], name
    raw = gzip.decompress(packed)
    assert len(raw) == meta["bytes"] and sha(raw) == meta["sha256"], name
    assert len(raw.splitlines()) == meta["lines"] and raw.endswith(b"\n"), name
    assert not any(token in raw for token in (b"/Users/", b"/tmp/", b"Application Support")), name
    payloads[name] = raw

requests = [json.loads(x) for x in payloads["send.raw"].splitlines()]
responses = [json.loads(x) for x in payloads["recv.raw"].splitlines()]
assert len(requests) == len(responses) == manifest["wire"]["pairs"] == 1214
assert len({q["id"] for q in requests}) == 1214
assert [q["id"] for q in requests[1:]] == [f"t2.{n}" for n in range(1, 1214)]
assert requests[0]["op"] == "info"
assert responses[0]["v"] == manifest["wire"]["protocol"] == 9
identity = manifest["wire"]["identity"]
assert {key: responses[0]["data"][key] for key in identity} == identity
assert (identity["cli_version"], identity["game_version"], identity["game_version_code"],
        identity["audit_schema_version"]) == ("CLI.9.0.6", "4.0.2", 922, 12)
assert Counter(q["op"] for q in requests) == manifest["wire"]["request_operations"]
assert Counter(r.get("st", r.get("err")) for r in responses) == manifest["wire"]["response_statuses"]

frames, problems = {}, []
for q, r in zip(requests, responses):
    assert q["id"] == r["id"] and q["v"] == r["v"] == 9
    decoded = decode_client_response(r)
    for p in decoded.problems:
        assert (p.stage, p.code) == ("response", "STALE_STATE")
        problems.append({"id": r["id"], "stage": p.stage, "code": p.code})
    if decoded.current_frame:
        frames[r["id"]] = decoded.current_frame
assert len(frames) == manifest["wire"]["frames"] == 987
assert sum("hero" in f.data for f in frames.values()) == manifest["wire"]["hero_frames"] == 961
assert problems == manifest["wire"]["server_problems"]
assert [p["id"] for p in problems] == ["t2.1048", "t2.1053"]
by_id = {r["id"]: r for r in responses}
wrappers = [json.loads(x) for x in payloads["controller-responses.ndjson"].splitlines()]
intents = [json.loads(x) for x in payloads["controller-intents.ndjson"].splitlines()]
assert len(wrappers) == len(intents) == manifest["controller"]["records"] == 990
assert wrappers[0]["id"] == "t2.2" and wrappers[-1]["id"] == "t2.1213"
assert Counter(x.get("controller", "wire") for x in wrappers) == {"wire": 766, "settle": 224}
assert Counter(x["st"] for x in wrappers if x.get("controller") == "settle") == {"completed": 222, "error": 2}
assert Counter(x["op"] for x in intents) == manifest["controller"]["intent_operations"]
current = frames["t2.1"]
pending = None
wrapper_problems = Counter()
for intent, wrapper in zip(intents, wrappers):
    validate_intent(current, intent)
    if pending:
        assert intent["op"] == "settle"
        assert wrapper["rid"] == pending
    decoded = decode_client_response(wrapper)
    wrapper_problems.update(f"{p.stage}/{p.code}" for p in decoded.problems)
    if wrapper.get("controller") == "settle":
        assert intent["op"] == "settle"
        assert wrapper["outcome"]["data"]["id"] == wrapper["rid"]
        for field in ("initial_error", "outcome", "observation"):
            if field in wrapper:
                assert wrapper[field] == by_id[wrapper[field]["id"]]
        pending = None
    else:
        assert wrapper == by_id[wrapper["id"]]
        if wrapper.get("st") == "in_progress":
            pending = wrapper["id"]
    if decoded.current_frame:
        current = decoded.current_frame
assert pending is None
assert wrapper_problems == manifest["controller"]["problems"] == {
    "response/STALE_STATE": 2, "initial_error/STALE_STATE": 2, "settle/ACTION_REJECTED": 2}
for i, r in enumerate(responses):
    if r.get("err") != "STALE_STATE":
        continue
    receipt_q, fresh_q, next_q = requests[i + 1:i + 4]
    receipt, fresh = responses[i + 1:i + 3]
    assert receipt_q["op"] == "req" and receipt_q["rid"] == r["id"]
    assert receipt["data"]["id"] == r["id"] and receipt["data"]["st"] == "REJECTED"
    assert fresh_q["op"] == "state"
    assert next_q["s"] == fresh["s"] and next_q["rev"] == fresh["rev"]

index_bytes = (HERE / "public-keyframes.json").read_bytes()
assert sha(index_bytes) == manifest["keyframes_sha256"]
for row in json.loads(index_bytes)["frames"]:
    data = frames[row["id"]].data
    r = by_id[row["id"]]
    assert row["request"] == requests[row["wire_line"] - 1]
    assert row["id"] == responses[row["wire_line"] - 1]["id"]
    for key in ("id", "s", "rev", "st"):
        assert row[key] == r.get(key)
    for key, source, default in (("hero", "hero", None), ("inventory", "inv", []),
                                 ("entities", "entities", []), ("phase", "phase", None)):
        assert row[key] == data.get(source, default)
    assert row["feedback"] == data.get("ui", {}).get("feedback", [])
    nodes = data.get("ui", {}).get("nodes", [])
    for status in row["status_nodes"]:
        assert status["node"] in nodes
        assert status["text_children"] == [n for n in nodes
            if n.get("parent") == status["node"]["id"] and n.get("text") is not None]
    if "ui" in row:
        assert row["ui"] == data["ui"]
    if "run_outcome" in row:
        assert row["run_outcome"] == data["run_outcome"]


def data(n):
    return frames[f"t2.{n}"].data


def light(n):
    return int(next(b["shown"]["counter"] for b in data(n)["hero"]["buffs"]
                    if b["name"] == "illuminated"))


checked = [n["text"] for n in data(6)["ui"]["nodes"] if n.get("checked") is True]
assert checked == ["Hostile Champions", "Badder Bosses", "On Diet", "Faith Is My Armor",
                   "Pharmacophobia", "Barren Land", "Swarm Intelligence", "Into Darkness", "Forbidden Runes"]
assert data(9)["hero"]["class"] == "warrior" and data(9)["hero"]["level"] == 1
assert data(1011)["hero"]["hp"] == data(1011)["hero"]["ht"] == 40
assert data(1011)["hero"]["strength"] == 12 and data(1011)["hero"]["depth"] == 5
assert next(i for i in data(1011)["inv"] if i["loc"] == "equipment.weapon")["level"] == 2
assert data(900)["hero"]["hp"] == 25 and data(903)["hero"]["hp"] == 40
assert light(900) - light(903) == 95
for n, hp in [(1038, 120), (1103, 1), (1113, 2), (1134, 20), (1166, 45),
              (1186, 66), (1190, 78), (1194, 51), (1210, 25), (1213, 26)]:
    assert visible_goo_hp(data(n)) == f"{hp}/120", n
assert visible_goo_hp(data(1106)) is None and visible_goo_hp(data(1108)) is None
assert map_cell(frames["t2.1106"], 1066)["terrain"] == 10
assert data(1106)["hero"]["hp"] == 11 and data(1108)["hero"]["hp"] == 18
assert light(1106) - light(1108) == 3
assert light(1103) - light(1190) == 97

# Count only the fresh action's floating damage at the attacked Goo cell.
melee = [1046, 1051, 1058, 1059, 1060, 1067, 1068, 1075, 1076, 1077,
         1078, 1084, 1099, 1192, 1193, 1194, 1205, 1209, 1210]
misses = {1059, 1078, 1084}
request_by_id = {q["id"]: q for q in requests}
melee_damage = 0
for n in melee:
    target = request_by_id[f"t2.{n}"]["cell"]
    floats = [x for x in data(n)["ui"]["feedback"]
              if x.get("kind") == "floating" and x.get("cell") == target]
    if n in misses:
        assert any(x.get("text") == "dodged" for x in floats)
    else:
        values = [int(x["text"]) for x in floats
                  if x.get("icon", {}).get("symbol") in {"physical_damage", "hit_surprise"}]
        assert len(values) == 1
        melee_damage += values[0]
assert len(melee) == 19 and melee_damage == 126
for n, damage, symbols in [(1045, 4, {"hit_surprise"}),
        (1080, 3, {"magic_damage"}), (1086, 3, {"magic_damage"}),
        (1097, 2, {"magic_damage"}), (1101, 6, {"magic_damage"}), (1103, 3, {"magic_damage"}),
        (1083, 11, {"physical_damage"}), (1092, 15, {"hit_surprise"}), (1095, 14, {"hit_surprise"})]:
    values = [int(x["text"]) for x in data(n)["ui"]["feedback"]
              if x.get("kind") == "floating" and x.get("icon", {}).get("symbol") in symbols]
    assert values == [damage], n
assert 126 + 4 + 17 + 40 == 187 and 120 + 93 - 187 == 26
assert request_by_id["t2.1089"]["cell"] == 1134
assert data(1086)["hero"]["hp"] == data(1089)["hero"]["hp"] == 16
for charge, escape in [(1046, 1047), (1051, 1052), (1060, 1061),
                       (1068, 1069), (1205, 1206), (1210, 1211)]:
    assert any(x.get("text") == "!!!" for x in data(charge)["ui"]["feedback"])
    assert request_by_id[f"t2.{escape}"]["op"] == "move"
    assert data(charge)["hero"]["hp"] == data(escape)["hero"]["hp"]

assert data(1212)["hero"]["hp"] == 12 and data(1212)["hero"]["cell"] == 553
assert requests[-1] == {"v": 9, "id": "t2.1213", "s": "s3", "op": "move", "rev": "r896", "dir": "W"}
final = data(1213)
assert final["hero"]["hp"] == 0 and final["hero"]["cell"] == 552
assert final["phase"] == "ended" and final["run_outcome"] == {"s": "s3", "result": "lost"}
assert by_id["t2.1213"]["rev"] == "r897"
floating = [(x.get("text"), x.get("icon", {}).get("symbol")) for x in final["ui"]["feedback"]]
assert ("11", "physical_damage") in floating and ("1", "ooze") in floating
assert any(x.get("text") == "You melt away..." for x in final["ui"]["feedback"])
assert all(q["op"] not in {"quit", "save"} for q in requests)
assert not any(f.data.get("phase") == "ended" for rid, f in frames.items() if rid != "t2.1213")
assert (HERE / "final-response.json").read_bytes() == payloads["recv.raw"].splitlines(keepends=True)[-1]
assert sha((HERE / "final-response.json").read_bytes()) == manifest["final_response_sha256"]

event_lines = payloads["events.tsv"].decode().splitlines()
assert event_lines[0] == "SPDCTL_TRACE\t1"
events = [x.split("\t") for x in event_lines[1:]]
assert [int(x[0]) for x in events] == list(range(1, len(events) + 1))
for direction, name in (("SEND", "send.raw"), ("RECV", "recv.raw"), ("DELIVERED", "recv.raw")):
    cursor = 0
    for event in events:
        if event[2] == direction:
            assert int(event[3]) == cursor
            cursor += int(event[4])
    assert cursor == len(payloads[name])
assert events[-1][2:] == ["STATUS", "0", "0", "EXITED:0"]
assert int(events[-1][1]) > max(int(e[1]) for e in events if e[2] in {"SEND", "RECV"})
assert sha((ROOT / manifest["decoder"]["path"]).read_bytes()) == manifest["decoder"]["sha256"]
for source in manifest.get("reviewed_sources", []):
    assert sha((ROOT / source["path"]).read_bytes()) == source["sha256"], source["path"]

print("PASS: exact-byte hashes/offsets; 1214 unique wire pairs; 987 frames, including 961 hero frames.")
print("PASS: 990 controller records and validated intents; 222 completed and 2 rejected settles.")
print("PASS: both STALE_STATE outcomes established before fresh decisions; no observation decoding failures.")
print("PASS: all nine challenges, Warrior start, full-health floor 5 entry, resource and ending claims.")
print("PASS: 19 melee attempts and 187 witnessed boss damage; six announced charges followed by unharmed escapes.")
print("PASS: boss HP requires a same-frame entity subject; unbound retained boss text is not current HP.")
print("PASS: no requests after death, no quit/save request; later EXITED:0 recorded without causal inference.")
print("Scope: frozen public evidence and source fingerprints; no profile reads, game connection, or counterfactual test.")
