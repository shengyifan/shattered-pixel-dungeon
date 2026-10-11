#!/usr/bin/env python3
"""Verify frozen public evidence offline; never connect to a game or read a profile."""
import gzip
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "desktop-control/client"))
from spdctl_client import decode_client_response


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


manifest = json.loads((HERE / "manifest.json").read_text())
payloads = {}
for name, metadata in manifest["files"].items():
    packed = (HERE / metadata["archive"]).read_bytes()
    assert len(packed) == metadata["archive_bytes"], name
    assert digest(packed) == metadata["archive_sha256"], name
    raw = gzip.decompress(packed)
    assert len(raw) == metadata["bytes"], name
    assert digest(raw) == metadata["sha256"], name
    assert len(raw.splitlines()) == metadata["lines"], name
    assert raw.endswith(b"\n"), name
    assert not any(token in raw for token in (b"/Users/", b"/tmp/", b"Application Support")), name
    payloads[name] = raw

requests = [json.loads(line) for line in payloads["send.raw"].splitlines()]
responses = [json.loads(line) for line in payloads["recv.raw"].splitlines()]
assert len(requests) == len(responses) == manifest["wire"]["pairs"] == 488
assert len({request["id"] for request in requests}) == len(requests)
assert [request["id"] for request in requests[1:]] == [f"t2.{n}" for n in range(1, 488)]
assert requests[0]["op"] == "info"
assert {key: responses[0]["data"][key] for key in manifest["wire"]["identity"]} == manifest["wire"]["identity"]
assert responses[0]["v"] == 9
assert manifest["wire"]["identity"]["cli_version"] == "CLI.9.0.4"
assert manifest["wire"]["identity"]["game_version"] == "4.0.2"
assert manifest["wire"]["identity"]["game_version_code"] == 922
assert manifest["wire"]["identity"]["audit_schema_version"] == 12
assert Counter(request["op"] for request in requests) == manifest["wire"]["request_operations"]

frames = {}
server_errors = []
for request, response in zip(requests, responses):
    assert request["v"] == response["v"] == 9
    assert request["id"] == response["id"]
    decoded = decode_client_response(response)
    for problem in decoded.problems:
        # An expected server rejection is not an observation decoding failure.
        assert (problem.stage, problem.code) == ("response", "STALE_STATE")
        server_errors.append(response["id"])
    if decoded.current_frame:
        frames[response["id"]] = decoded.current_frame.data
assert len(frames) == 393
assert sum("hero" in data for data in frames.values()) == 385
assert server_errors == [row["id"] for row in manifest["wire"]["server_errors"]]
assert len(server_errors) == 11
assert Counter(response.get("st", response.get("err")) for response in responses) == manifest["wire"]["response_statuses"]

request_by_id = {request["id"]: request for request in requests}
response_by_id = {response["id"]: response for response in responses}
wrappers = [json.loads(line) for line in payloads["controller-responses.ndjson"].splitlines()]
assert len(wrappers) == 414
assert wrappers[0]["id"] == "t2.5" and wrappers[-1]["id"] == "t2.487"
assert Counter(wrapper.get("controller", "wire") for wrapper in wrappers) == {"wire": 328, "settle": 86}
wrapper_problems = []
for wrapper in wrappers:
    decoded = decode_client_response(wrapper)
    for problem in decoded.problems:
        wrapper_problems.append((problem.stage, problem.code))
    if wrapper.get("controller") == "settle":
        assert wrapper["outcome"]["data"]["id"] == wrapper["rid"]
        for field in ("initial_error", "outcome", "observation"):
            if field in wrapper:
                nested = wrapper[field]
                assert nested == response_by_id[nested["id"]]
        if "pending_request" in wrapper:
            assert wrapper["pending_request"] == request_by_id[wrapper["pending_request"]["id"]]
    else:
        assert wrapper == response_by_id[wrapper["id"]]
assert Counter(wrapper_problems) == {
    ("response", "STALE_STATE"): 11,
    ("initial_error", "STALE_STATE"): 11,
    ("settle", "ACTION_REJECTED"): 11,
}
settles = [wrapper for wrapper in wrappers if wrapper.get("controller") == "settle"]
assert Counter(wrapper["st"] for wrapper in settles) == {"completed": 72, "error": 11, "pending": 3}
for position, wrapper in enumerate(wrappers):
    if wrapper.get("controller") == "settle" and wrapper["st"] == "pending":
        resumed = wrappers[position + 1]
        assert resumed["controller"] == "settle" and resumed["rid"] == wrapper["rid"]
        assert resumed["st"] == "completed"
        if "pending_request" in wrapper:
            assert resumed["observation"]["id"] == wrapper["pending_request"]["id"]

for index, response in enumerate(responses):
    if response.get("err") != "STALE_STATE":
        continue
    receipt_request, fresh_request, next_request = requests[index + 1:index + 4]
    receipt, fresh = responses[index + 1:index + 3]
    assert receipt_request["op"] == "req" and receipt_request["rid"] == response["id"]
    assert receipt["data"]["id"] == response["id"]
    assert receipt["data"]["st"] == "REJECTED" and receipt["data"]["err"] == "STALE_STATE"
    assert fresh_request["op"] == "state"
    assert next_request["s"] == fresh["s"] and next_request["rev"] == fresh["rev"]

keyframes = json.loads((HERE / "public-keyframes.json").read_text())
for row in keyframes["frames"]:
    data = frames[row["id"]]
    response = response_by_id[row["id"]]
    assert row["request"] == requests[row["wire_line"] - 1]
    for field in ("id", "s", "rev", "st"):
        assert row[field] == response.get(field)
    assert row["phase"] == data.get("phase")
    assert row["hero"] == data.get("hero")
    assert row["inventory"] == data.get("inv", [])
    assert row["entities"] == data.get("entities", [])
    assert row["feedback"] == data.get("ui", {}).get("feedback", [])
    if "ui" in row:
        assert row["ui"] == data.get("ui", {})
    if "run_outcome" in row:
        assert row["run_outcome"] == data["run_outcome"]

checked = [node["text"] for node in frames["t2.6"]["ui"]["nodes"] if node.get("checked") is True]
assert checked == ["Hostile Champions", "Badder Bosses", "On Diet", "Faith Is My Armor",
                   "Pharmacophobia", "Barren Land", "Swarm Intelligence", "Into Darkness", "Forbidden Runes"]
assert frames["t2.9"]["hero"]["class"] == "warrior"
assert frames["t2.9"]["hero"]["level"] == 1 and frames["t2.9"]["hero"]["experience"] == 0
assert not any(request["op"] in {"rest", "quit", "save"} for request in requests)
assert sum(request["op"] == "wait" for request in requests) == 19
assert not any(buff["name"] == "starving" for data in frames.values()
               for buff in data.get("hero", {}).get("buffs", []))


def frame(number):
    return frames[f"t2.{number}"]


def light(number):
    return int(next(buff["shown"]["counter"] for buff in frame(number)["hero"]["buffs"]
                    if buff["name"] == "illuminated"))


def quantity(number, name):
    return sum(item["qty"] for item in frame(number)["inv"] if item["name"] == name)


for number, depth, hp, reserve in [(241, 2, 25, 106), (293, 1, 10, 66), (311, 1, 13, 43),
                                  (314, 2, 13, 42), (347, 3, 9, 13)]:
    assert (frame(number)["hero"]["depth"], frame(number)["hero"]["hp"], light(number)) == (depth, hp, reserve)
assert light(119) == 250 and light(349) == 262
assert light(387) - light(389) == 3
assert frame(387)["hero"]["hp"] == frame(389)["hero"]["hp"] == 11
assert quantity(387, "ration of food") == 2 and quantity(389, "ration of food") == 1
assert quantity(416, "turquoise potion") == 2
assert quantity(419, "potion of frost") == 1 and quantity(437, "potion of frost") == 0
assert any(entity.get("item", {}).get("name") == "frozen carpaccio" and entity["cell"] == 1127
           for entity in frame(422)["entities"])
assert any(entity["name"] == "gnoll scout" and any(buff["name"] == "chilled" for buff in entity.get("buffs", []))
           for entity in frame(437)["entities"] if entity.get("kind") == "character")
assert quantity(486, "ration of food") == 1 and light(486) == 170
assert frame(486)["hero"]["hp"] == 7
assert frame(486)["hero"]["experience"] == 6
assert next(item for item in frame(486)["inv"] if item["name"] == "whip")["level"] is None
assert next(item for item in frame(487)["inv"] if item["name"] == "sacrificial whip")["level"] == 1

final = frame(487)
assert responses[-1]["id"] == requests[-1]["id"] == "t2.487"
assert responses[-1]["s"] == "s3" and responses[-1]["rev"] == "rkd"
assert final["phase"] == "ended" and final["run_outcome"]["result"] == "lost"
assert final["hero"]["hp"] == 0 and final["hero"]["depth"] == 3
assert any(row.get("text") == "Gnoll scout killed you..." for row in final["ui"]["feedback"])
assert not any(data.get("phase") == "ended" for identity, data in frames.items() if identity != "t2.487")

event_lines = payloads["events.tsv"].decode().splitlines()
assert event_lines[0] == "SPDCTL_TRACE\t1"
events = [line.split("\t") for line in event_lines[1:]]
assert [int(row[0]) for row in events] == list(range(1, len(events) + 1))
for direction, payload in [("SEND", "send.raw"), ("RECV", "recv.raw"), ("DELIVERED", "recv.raw")]:
    cursor = 0
    for row in events:
        if row[2] == direction:
            assert int(row[3]) == cursor
            cursor += int(row[4])
    assert cursor == len(payloads[payload])
assert events[-1][2:] == ["STATUS", "0", "0", "EXITED:0"]
assert int(events[-1][1]) > max(int(row[1]) for row in events if row[2] in {"SEND", "RECV"})

print("PASS: exact-byte hashes and offsets; 488 unique request/response pairs; 385 hero frames.")
print("PASS: 414 controller records; 72 completed, 11 rejected, and 3 resumed pending settles.")
print("PASS: 11 STALE_STATE outcomes established before fresh decisions; no observation decoding failures.")
print("PASS: nine checked challenges, Warrior start, selected-field index, resources, and final loss.")
print("PASS: no requests after death; no quit request; later trace STATUS EXITED:0 preserved without causal inference.")
print("Scope: frozen public evidence only; no profile reads, game connection, replay, or counterfactual victory test.")
