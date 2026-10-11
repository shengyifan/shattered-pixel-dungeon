#!/usr/bin/env python3
"""Verify this frozen postmortem bundle; never connect to a game or read a profile."""
import gzip
import hashlib
import json
from pathlib import Path
import sys
from collections import Counter

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "desktop-control/client"))
from spdctl_client import decode_client_response

manifest = json.loads((HERE / "manifest.json").read_text())
payloads = {}
for name, meta in manifest["files"].items():
    packed = (HERE / meta["archive"]).read_bytes()
    assert hashlib.sha256(packed).hexdigest() == meta["archive_sha256"], name
    raw = gzip.decompress(packed)
    assert len(raw) == meta["bytes"], name
    assert hashlib.sha256(raw).hexdigest() == meta["sha256"], name
    assert len(raw.splitlines()) == meta["lines"], name
    payloads[name] = raw

requests = [json.loads(line) for line in payloads["send.raw"].splitlines()]
responses = [json.loads(line) for line in payloads["recv.raw"].splitlines()]
assert len(requests) == len(responses) == 678
assert len({r["id"] for r in requests}) == len(requests)
frames = {}
server_errors = []
for request, response in zip(requests, responses):
    assert request["id"] == response["id"]
    decoded = decode_client_response(response)
    # The client surfaces expected server rejections as response problems.
    # They are not malformed-frame/observation decoding failures.
    for problem in decoded.problems:
        assert (problem.stage, problem.code) == ("response", "STALE_STATE")
        server_errors.append(response["id"])
    if decoded.current_frame and decoded.current_frame.data.get("hero"):
        frames[response["id"]] = decoded.current_frame.data
assert len(frames) == 518
assert server_errors == [r["id"] for r in manifest["wire"]["server_errors"]]

wrappers = [json.loads(line) for line in payloads["controller-responses.ndjson"].splitlines()]
assert len(wrappers) == 552
wrapper_problems = []
for wrapper in wrappers:
    decoded = decode_client_response(wrapper)
    for problem in decoded.problems:
        assert (problem.stage, problem.code) in {
            ("response", "STALE_STATE"), ("initial_error", "STALE_STATE"),
            ("settle", "ACTION_REJECTED")}
        wrapper_problems.append((problem.stage, problem.code))
assert Counter(wrapper_problems) == {
    ("response", "STALE_STATE"): 9, ("initial_error", "STALE_STATE"): 9,
    ("settle", "ACTION_REJECTED"): 9}
for index, response in enumerate(responses):
    if response.get("err") == "STALE_STATE":
        receipt = responses[index + 1]["data"]
        assert receipt["id"] == response["id"] and receipt["st"] == "REJECTED"
assert not any(request["op"] == "rest" for request in requests)
assert not any(buff["name"] == "starving" for data in frames.values()
               for buff in data["hero"]["buffs"])

index = json.loads((HERE / "public-keyframes.json").read_text())
for row in index["frames"]:
    data = frames[row["id"]]
    assert row["hero"] == data["hero"]
    assert row["inventory"] == data.get("inv", [])
    assert row["feedback"] == data.get("ui", {}).get("feedback", [])
    assert row["request"] == requests[row["wire_line"] - 1]
    assert responses[row["wire_line"] - 1]["id"] == row["id"]

def light(number):
    return int(next(b["shown"]["counter"] for b in frames[f"t1.{number}"]["hero"]["buffs"]
                    if b["name"] == "illuminated"))

def hp(number):
    return frames[f"t1.{number}"]["hero"]["hp"]

for before, after, delta, health in [(411, 413, 3, (5, 8)), (419, 421, 3, (6, 10)),
                                    (628, 630, 3, (7, 8)), (636, 638, 3, (9, 10)),
                                    (656, 658, 24, (10, 17))]:
    assert light(before) - light(after) == delta
    assert (hp(before), hp(after)) == health
assert light(565) - light(593) == 27
assert light(484) - light(593) == 109
assert light(272) + 250 - light(484) == 185
final = frames["t1.677"]
assert responses[-1]["id"] == "t1.677"
assert final["phase"] == "ended" and final["run_outcome"]["result"] == "lost"
assert final["hero"]["hp"] == 0 and final["hero"]["depth"] == 4
assert any(f.get("text") == "Swarm of flies killed you..." for f in final["ui"]["feedback"])
native = json.loads((HERE / "native-postmortem-summary.json").read_text())
assert native["game_file_marker_hex"] == "01" and native["remaining_depth_files"] == []
assert native["record"]["win"] is False and native["game_data"]["challenges"] == 511
assert native["record"]["score"] == native["game_data"]["statistics"]["total_score"] == 13767
for key, native_key in [("hp", "HP"), ("ht", "HT"), ("strength", "STR"),
                        ("level", "lvl"), ("experience", "exp"), ("cell", "pos")]:
    assert final["hero"][key] == native["game_data"]["hero"][native_key]
assert final["hero"]["gold"] == native["game_data"]["statistics"]["score"] == 558
print("PASS: exact-byte hashes; 678 request/response pairs; 518 hero frames; 552 controller records.")
print("PASS: 9 expected STALE_STATE responses; no observation decoding failures.")
print("PASS: selected-field index, five meals, floor timing, public death and native summary consistency.")
print("Scope: frozen evidence only; no game requests, profile reads, replay or counterfactual victory test.")
