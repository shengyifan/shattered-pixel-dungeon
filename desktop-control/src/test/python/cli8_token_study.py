#!/usr/bin/env python3
"""Offline CLI 7 corpus to CLI 8 semantic-projection size study.

Reads only the explicitly named public SEND/RECV transport files. It never starts
the game or reads a save/profile/audit database. A frozen Git v7 helper decodes
historical bytes in the ignored analysis directory; production CLI 8 Java code
projects the resulting independent canonical frames. Replay cannot recover native
draw identities or source annotations absent from those old public bytes.

Run: uv run --with tiktoken==0.14.0 python desktop-control/src/test/python/cli8_token_study.py --session /path/to/session
"""

from __future__ import annotations

import argparse
import collections
import copy
import hashlib
import importlib.util
import json
import math
import os
import statistics
import subprocess
import sys
from pathlib import Path

import tiktoken


ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / "desktop-control/build/cli8-token-study-20260924"
BASELINE = ROOT / "desktop-control/build/cli-token-audit-session-t1/metrics.json"
OLD_COMMIT = "38c21149d84f166db6630a7c7fa220e5851cfd41"
HISTORICAL = {
    "historical_v7_decoder.py": "desktop-control/client/spdctl_client.py",
    "historical_protocol7.py": "desktop-control/src/test/python/protocol7.py",
}
ENCODER = tiktoken.get_encoding("o200k_base")
DRAWING = {"atlas", "frame_pixels", "texture_size", "flip_horizontal", "flip_vertical",
           "angle", "scale", "tint", "alpha", "opacity", "color", "screen_rect",
           "offset_pixels", "viewport_pixels", "rendered_particles"}
IMAGE_FIELDS = {"icon", "item_icon", "item_badge", "hero_portrait", "boss_icon",
                "target_icon", "spell_icon", "primary_icon", "secondary_icon"}
KNOWN_TEXT_COLORS = {0xFFFFFF, 0x000000, 0x00FF00, 0xFF0000, 0xFF8800,
                     0xFFFF00, 0xFFFF44, 0xAAAAAA}


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def frozen_helpers():
    OUT.mkdir(parents=True, exist_ok=True)
    for target, source in HISTORICAL.items():
        path = OUT / target
        original = subprocess.run(["git", "show", f"{OLD_COMMIT}:{source}"],
                                  cwd=ROOT, capture_output=True, check=True).stdout
        if path.exists():
            assert path.read_bytes() == original, f"Frozen historical helper drifted: {path}"
        else:
            path.write_bytes(original)
    old_decoder = load("spdctl_client", OUT / "historical_v7_decoder.py")
    old_helper = load("historical_protocol7", OUT / "historical_protocol7.py")
    current_decoder = load("spdctl_v8", ROOT / "desktop-control/client/spdctl_client.py")
    # The historical helper already bound its frozen decoder. Current test
    # projections import only the current strict decoder from this point on.
    sys.modules["spdctl_client"] = current_decoder
    current_helper = load("protocol8_offline_projection",
                          ROOT / "desktop-control/src/test/python/protocol8.py")
    return old_decoder, old_helper, current_decoder, current_helper


def wire_lines(path: Path):
    raw = path.read_bytes()
    assert raw.endswith(b"\n"), f"Incomplete transport final frame: {path.name}"
    lines = raw.decode("utf-8").splitlines()
    assert all(line for line in lines), f"Empty transport frame: {path.name}"
    return raw, lines


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def token_count(value: str) -> int:
    return len(ENCODER.encode(value))


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * fraction)]


def distribution(values):
    return {"count": len(values), "total": sum(values),
            "mean": round(statistics.mean(values), 2),
            "median": statistics.median(values), "p95": percentile(values, .95),
            "max": max(values)}


class GraphicLedger:
    def __init__(self):
        self.rows = collections.defaultdict(lambda: {"count": 0, "examples": []})

    def add(self, classification, source, frame_id, count=1):
        row = self.rows[(classification, source)]
        row["count"] += count
        if frame_id not in row["examples"] and len(row["examples"]) < 3:
            row["examples"].append(frame_id)

    def inspect(self, result, frame_id):
        observation = result.get("observation")
        if not isinstance(observation, dict):
            return
        ui = observation.get("ui", {})
        for node in ui.get("controls", []):
            if not isinstance(node, dict):
                continue
            for key in IMAGE_FIELDS:
                if isinstance(node.get(key), dict):
                    self.add("unclassifiable_from_old_pixels", f"ui.{key}", frame_id)
            for icon in node.get("preview_icons", []) or []:
                if isinstance(icon, dict):
                    self.add("unclassifiable_from_old_pixels", "ui.preview_icons[]", frame_id)
            if isinstance(node.get("color"), int) and not isinstance(node["color"], bool):
                category = ("translated_to_named_tone" if node["color"] in KNOWN_TEXT_COLORS
                            else "unclassifiable_from_old_pixels")
                self.add(category, "ui.text_color", frame_id)
            for style in node.get("styles", []) or []:
                if isinstance(style, dict) and isinstance(style.get("color"), int):
                    category = ("translated_to_named_tone" if style["color"] in KNOWN_TEXT_COLORS
                                else "unclassifiable_from_old_pixels")
                    self.add(category, "ui.style_run", frame_id)
            if isinstance(node.get("turn_progress"), dict):
                turn = node["turn_progress"]
                if type(turn.get("sweep")) in (int, float) and math.isfinite(turn["sweep"]):
                    self.add("retained_inline_without_native_owner", "ui.turn_progress.sweep", frame_id)
            if isinstance(node.get("icon_overlay"), dict):
                overlay = node["icon_overlay"]
                good = (overlay.get("measurement") == "rendered_pixels"
                        and type(overlay.get("covered_pixels")) is int
                        and type(overlay.get("total_pixels")) is int
                        and 0 < overlay["total_pixels"]
                        and 0 <= overlay["covered_pixels"] <= overlay["total_pixels"])
                self.add("retained_inline_without_native_owner" if good else "unclassifiable_from_old_pixels",
                         "ui.icon_overlay", frame_id)
            if isinstance(node.get("display"), dict):
                self.add("retained_same_frame_text", "ui.display", frame_id)
        cues = observation.get("visual_cues", {})
        if not isinstance(cues, dict):
            return
        for cue in cues.get("cues", []):
            if not isinstance(cue, dict):
                continue
            self.add("retained_same_frame_signal", "cue." + str(cue.get("kind", "unknown")), frame_id)
            for key in DRAWING & set(cue):
                self.add("unclassifiable_from_old_pixels", "cue." + str(cue.get("kind")) + "." + key, frame_id)
            appearance = cue.get("appearance")
            if isinstance(appearance, dict):
                if isinstance(appearance.get("tint_style"), str):
                    self.add("translated_to_named_state", "cue.sprite_state.tint_style", frame_id)
                for key in DRAWING & set(appearance):
                    self.add("unclassifiable_from_old_pixels",
                             "cue." + str(cue.get("kind")) + ".appearance." + key, frame_id)
        for metric in cues.get("metrics", []) or []:
            if isinstance(metric, dict):
                self.add("native_semantics_not_verified_by_replay",
                         "cue.metrics." + str(metric.get("kind", "unknown")), frame_id)
        for effect in cues.get("screen_effects", []) or []:
            if isinstance(effect, dict):
                self.add("excluded_decorative_screen_effect", "screen." + str(effect.get("kind", "unknown")), frame_id)

    def output(self):
        return [{"classification": classification, "source": source, **row}
                for (classification, source), row in sorted(self.rows.items())]


def java_projection(input_path, output_path):
    classpath_file = ROOT / "desktop-control/build/test-runtime-classpath.txt"
    recorded_classpath = classpath_file.read_text().strip()
    # The recorded fixture classpath puts several module jars before their
    # freshly compiled classes. Explicitly prefer the current production class
    # directories so a stale jar cannot silently make this a prior-build study.
    current_modules = ("game-control", "core", "SPD-classes", "control-protocol",
                       "desktop-control", "desktop")
    current_classes = [ROOT / module / "build/classes/java/main" for module in current_modules]
    assert all(path.is_dir() for path in current_classes), "Current production classes are missing"
    classpath = os.pathsep.join(str(path) for path in current_classes) + os.pathsep + recorded_classpath
    for name in ("CompactProtocol", "GameplayObservation", "GameplayEvidence"):
        source = (ROOT / "game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/"
                  f"control/game/{name}.java")
        compiled = (ROOT / "game-control/build/classes/java/main/com/shatteredpixel/shatteredpixeldungeon/"
                    f"control/game/{name}.class")
        assert compiled.is_file() and compiled.stat().st_mtime_ns >= source.stat().st_mtime_ns, (
            f"Production {name} class is stale; compile current sources before the offline study")
    probe_source = (ROOT / "desktop-control/src/test/java/com/shatteredpixel/"
                    "shatteredpixeldungeon/control/game/Cli8CorpusProbe.java")
    classes = OUT / "classes"
    classes.mkdir(exist_ok=True)
    compiled = subprocess.run(["javac", "-cp", classpath, "-d", str(classes), str(probe_source)],
                              cwd=ROOT, capture_output=True, text=True)
    assert compiled.returncode == 0, compiled.stderr
    process = subprocess.run(["java", "-cp", str(classes) + os.pathsep + classpath,
                              "com.shatteredpixel.shatteredpixeldungeon.control.game.Cli8CorpusProbe",
                              str(input_path), str(output_path)],
                             cwd=ROOT, capture_output=True, text=True)
    assert process.returncode == 0, process.stderr[:2000]
    origin_lines = [line.removeprefix("PRODUCTION_CLASS_SOURCES ")
                    for line in process.stderr.splitlines()
                    if line.startswith("PRODUCTION_CLASS_SOURCES ")]
    assert len(origin_lines) == 1, "Probe did not identify loaded production classes"
    loaded_sources = json.loads(origin_lines[0])
    expected_origin = str((ROOT / "game-control/build/classes/java/main").resolve())
    assert set(loaded_sources.values()) == {expected_origin}, (
        f"A stale jar shadowed current production classes: {loaded_sources}")
    return {"probe_source_sha256": digest(probe_source.read_bytes()),
            "production_classpath_preference": [str(path) for path in current_classes],
            "loaded_production_class_sources": loaded_sources,
            "production_sources_sha256": {
                name: digest((ROOT / "game-control/src/main/java/com/shatteredpixel/"
                              f"shatteredpixeldungeon/control/game/{name}.java").read_bytes())
                for name in ("CompactProtocol", "GameplayObservation", "GameplayEvidence")},
            "projection_process": process.stderr.strip()}


def assert_preserved(old, new, old_wire, new_wire, frame_id):
    old_result, new_result = old["result"], new["result"]
    old_observation, new_observation = old_result.get("observation"), new_result.get("observation")
    checks = collections.Counter()
    if isinstance(old_observation, dict):
        assert isinstance(new_observation, dict), f"{frame_id}: observation disappeared"
        for section in ("hero", "inventory", "map", "visible_entities"):
            if section in old_observation:
                assert old_observation[section] == new_observation.get(section), f"{frame_id}: {section} changed"
                checks[section] += 1
        for section in ("phase", "activity", "persistence", "run_outcome"):
            if section in old_result:
                assert old_result[section] == new_result.get(section), f"{frame_id}: {section} changed"
                checks[section] += 1
        assert old_result.get("actions", []) == new_result.get("actions", []), f"{frame_id}: actions changed"
        checks["complete_actions"] += 1
        old_nodes = old_wire.get("ui", {}).get("nodes", [])
        new_nodes = new_wire.get("ui", {}).get("nodes", [])
        before = {node["id"]: node.get("ops") for node in old_nodes if isinstance(node, dict) and "ops" in node}
        after = {node["id"]: node.get("ops") for node in new_nodes if isinstance(node, dict) and "ops" in node}
        assert before == after, f"{frame_id}: current node operation constraints changed"
        checks["node_operation_constraints"] += 1
    return checks


def offscreen_cue_cost(path: Path, decoder):
    """Measure one actual native default frame and same-frame cue ablations."""
    path = path.resolve()
    fixture_report = json.loads((path.parent / "visual-cue-result.json").read_text())
    assert fixture_report.get("ok") is True and fixture_report.get("fixture") == "visual-bomb"
    evidence = fixture_report["evidence"]
    assert Path(evidence["offscreen_pan_wire"]).resolve() == path
    request = evidence["offscreen_pan_wire_request"]
    assert request["op"] in {"pan", "state"} and request.get("view", "play") == "play"
    assert request.get("src") is not True
    raw = path.read_bytes()
    assert raw.endswith(b"\n") and raw.count(b"\n") == 1, "Fixture wire must be one complete child frame"
    frame = json.loads(raw)
    assert frame["v"] == 8 and frame["id"] == request["id"] and frame["st"] == "completed"
    data = decoder.decode_wire_response(frame).data
    assert data is not None and "map" in data and "cues" in data
    cues = data["cues"]["cues"]
    bombs = [cue for cue in cues if cue["kind"] == "bomb_smoke"]
    assert bombs, "Actual offscreen pan has no retained bomb warning"
    visible = {cell["cell"] for cell in data["map"]["cells"] if cell["visibility"] == "visible"}
    assert all(cue["cell"] in visible for cue in bombs), "A bomb cue exceeds this frame's current FOV"
    full = compact(frame)
    without_bombs = copy.deepcopy(frame)
    without_bombs["data"]["cues"]["cues"] = [cue for cue in without_bombs["data"]["cues"]["cues"]
                                            if cue["kind"] != "bomb_smoke"]
    without_list = copy.deepcopy(frame)
    without_list["data"]["cues"]["cues"] = []
    without_block = copy.deepcopy(frame)
    without_block["data"].pop("cues")
    minified_tokens = token_count(full)
    result = {
        "classification": "native_default_frame_within_frame_counterfactual_not_v7_matched_run",
        "fixture": fixture_report["fixture"], "native_fixture_build_id": fixture_report.get("build_id"),
        "wire_sha256": digest(raw),
        "wire_id": frame["id"], "request_op": request["op"],
        "raw_frame_bytes_excluding_lf": len(raw) - 1,
        "raw_frame_tokens_excluding_lf": token_count(raw[:-1].decode("utf-8")),
        "minified_full_frame_tokens": minified_tokens,
        "bomb_smoke_count": len(bombs), "all_cue_count": len(cues),
        "bomb_smoke_cells": sorted({cue["cell"] for cue in bombs}),
        "without_bomb_smoke_tokens": token_count(compact(without_bombs)),
        "without_cue_list_tokens": token_count(compact(without_list)),
        "without_cues_block_tokens": token_count(compact(without_block)),
    }
    result["bomb_smoke_marginal_tokens"] = minified_tokens - result["without_bomb_smoke_tokens"]
    result["all_cue_rows_marginal_tokens"] = minified_tokens - result["without_cue_list_tokens"]
    result["whole_cues_block_marginal_tokens"] = minified_tokens - result["without_cues_block_tokens"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", required=True, type=Path,
                        help="Explicit existing public transport session directory")
    parser.add_argument("--offscreen-wire", type=Path,
                        help="Optional exact isolated visual-bomb fixture frame for native cue cost appendix")
    arguments = parser.parse_args()
    session = arguments.session.resolve()
    OUT.mkdir(parents=True, exist_ok=True)
    baseline = json.loads(BASELINE.read_text())
    send_raw, send_lines = wire_lines(session / "send.raw")
    recv_raw, recv_lines = wire_lines(session / "recv.raw")
    assert digest(send_raw) == baseline["send_sha256"]
    assert digest(recv_raw) == baseline["recv_sha256"]
    assert len(send_lines) == len(recv_lines) == baseline["response_count"] == 695
    requests = [json.loads(line) for line in send_lines]
    responses = [json.loads(line) for line in recv_lines]
    assert all(request["id"] == response["id"] for request, response in zip(requests, responses))
    old_decoder, old_helper, current_decoder, current_helper = frozen_helpers()
    ledger = GraphicLedger()
    prepared = []
    for request, response in zip(requests, responses):
        old_decoder.decode_wire_response(response)
        old = old_helper.response(response, request["op"])
        ledger.inspect(old["result"], response["id"])
        prepared.append({"id": response["id"], "s": response.get("s"), "rev": response.get("rev"),
                         "st": response["st"], "live": "rev" in response,
                         "full": request.get("view") == "full" or request.get("src") is True,
                         "sources": request.get("src") is True, "result": old["result"]})
    input_path, output_path = OUT / "canonical-input.jsonl", OUT / "candidate-v8.jsonl"
    input_path.write_text("".join(compact(row) + "\n" for row in prepared))
    provenance = java_projection(input_path, output_path)
    candidate_lines = output_path.read_text().splitlines()
    assert len(candidate_lines) == 695
    candidate = [json.loads(line) for line in candidate_lines]
    checks = collections.Counter()
    map_depths = collections.Counter()
    candidate_per_op = collections.defaultdict(list)
    for request, response, projected, line in zip(requests, responses, candidate, candidate_lines):
        frame_id = response["id"]
        assert projected["v"] == 8 and projected["id"] == frame_id
        old_wire = old_decoder.decode_wire_response(response).data or {}
        new_wire = current_decoder.decode_wire_response(projected).data or {}
        if request["op"] != "info":
            old = old_helper.response(response, request["op"])
            new = current_helper.response(projected, request["op"])
            checks.update(assert_preserved(old, new, old_wire, new_wire, frame_id))
            if isinstance(old["result"].get("observation"), dict) and "map" in old["result"]["observation"]:
                map_depths[old["result"]["observation"]["hero"]["depth"]] += 1
        candidate_per_op[request["op"]].append(token_count(line))
    raw_tokens = [token_count(line) for line in recv_lines]
    minified_tokens = [token_count(compact(response)) for response in responses]
    projected_tokens = [token_count(line) for line in candidate_lines]
    default_play = [index for index, request in enumerate(requests)
                    if request["op"] not in {"info", "req", "history", "events"}
                    and request.get("view", "play") == "play" and request.get("src") is not True]
    default_raw = [raw_tokens[index] for index in default_play]
    default_projected = [projected_tokens[index] for index in default_play]
    comparable = [index for index, request in enumerate(requests) if request["op"] != "info"]
    comparable_raw = [raw_tokens[index] for index in comparable]
    comparable_projected = [projected_tokens[index] for index in comparable]
    assert sum(raw_tokens) == baseline["response_tokens"]["total"], "Tokenizer baseline drift"
    assert digest((session / "send.raw").read_bytes()) == baseline["send_sha256"]
    assert digest((session / "recv.raw").read_bytes()) == baseline["recv_sha256"]
    metrics = {
        "classification": "offline_semantic_projection_not_native_capture_or_package",
        "session": str(session), "baseline_commit": OLD_COMMIT,
        "old_helper_sha256": {name: digest((OUT / name).read_bytes()) for name in HISTORICAL},
        "raw_send_sha256": digest(send_raw), "raw_recv_sha256": digest(recv_raw),
        "frames": len(responses), "tokenizer": "tiktoken 0.14.0 / o200k_base",
        "measurement": "Each complete JSON line excluding LF; reference tokens, not model billing",
        "raw_v7": distribution(raw_tokens),
        "reserialized_v7": distribution(minified_tokens),
        "offline_projected_v8": distribution(projected_tokens),
        "default_play_frames": len(default_play),
        "default_play_raw_v7": distribution(default_raw),
        "default_play_offline_projected_v8": distribution(default_projected),
        "default_play_change_tokens": sum(default_projected) - sum(default_raw),
        "default_play_change_percent": round((sum(default_projected) / sum(default_raw) - 1) * 100, 3),
        "comparable_694_raw_v7": distribution(comparable_raw),
        "comparable_694_offline_projected_v8": distribution(comparable_projected),
        "projected_change_tokens": sum(projected_tokens) - sum(raw_tokens),
        "projected_change_percent": round((sum(projected_tokens) / sum(raw_tokens) - 1) * 100, 3),
        "comparable_change_percent": round((sum(comparable_projected) / sum(comparable_raw) - 1) * 100, 3),
        "projection_checks": dict(checks), "map_observation_depths": dict(map_depths),
        "projected_tokens_by_op": {op: distribution(values) for op, values in candidate_per_op.items()},
        "unverified_by_replay": [
            "Native draw-to-subject identity for hero, buffs, entities and non-item controls",
            "New health_estimate samples and native shown item/buff flags absent from old pixel transport",
            "Native source annotations for semantic icons, particle meanings and feedback occurrences",
            "Standalone actions-only subject_data snapshots absent from the old request corpus",
            "Same-FOV camera/modal independent cue capture and decision-revision behavior",
            "Boss, mapped cells and later-depth source scenarios absent from this D1-D3 corpus",
        ],
        **provenance,
    }
    cue_appendix = offscreen_cue_cost(arguments.offscreen_wire, current_decoder) if arguments.offscreen_wire else None
    if cue_appendix is not None:
        metrics["native_offscreen_cue_cost"] = cue_appendix
    sidecar = {"classification": "source_specific_graphics_and_replay_limits",
               "rows": ledger.output(), "unverified_by_replay": metrics["unverified_by_replay"],
               "notes": ["Unclassifiable pixels use explicit unmapped_indicator partial placeholders in the projected frame.",
                         "Removing generic metrics or screen visuals from this replay is not proof that new native semantic producers cover those source mechanisms.",
                         "Candidate size is one source-constrained projection, not a shippable-equivalence bound or a live CLI 8 package measurement."]}
    (OUT / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n")
    (OUT / "sidecar.json").write_text(json.dumps(sidecar, ensure_ascii=False, indent=2) + "\n")
    default_set = set(default_play)
    per_frame = ["ordinal\tid\top\tview_class\traw_bytes\tprojected_bytes\traw_tokens\tprojected_tokens\tcore_check"]
    per_frame += ["\t".join(map(str, (index + 1, response["id"], request["op"],
                                    "default_play" if index in default_set else "query_or_explicit_full",
                                    len(recv_lines[index].encode("utf-8")),
                                    len(candidate_lines[index].encode("utf-8")),
                                    raw_tokens[index], projected_tokens[index],
                                    "not_comparable_info" if request["op"] == "info" else "passed")))
                  for index, (request, response) in enumerate(zip(requests, responses))]
    (OUT / "frames.tsv").write_text("\n".join(per_frame) + "\n")
    if cue_appendix is not None:
        (OUT / "offscreen-cue-cost.json").write_text(json.dumps(cue_appendix, ensure_ascii=False, indent=2) + "\n")
    top = sorted(sidecar["rows"], key=lambda row: row["count"], reverse=True)[:18]
    report = ["# CLI 8 offline semantic projection of 695 CLI 7 transport frames", "",
              "This is an offline, source-constrained replay of public transport bytes. No game, save or profile was opened.", "",
              "## Default play frames (primary comparison)", "",
              "These are action responses and state queries using the default `play` view, excluding `info`, `req`, history/events and explicit full/src requests.", "",
              "| Measure | Raw CLI 7 | Offline projected CLI 8 |", "| --- | ---: | ---: |",
              f"| Default play frames | {len(default_play)} | {len(default_play)} |",
              f"| Reference tokens, complete lines without LF | {sum(default_raw):,} | {sum(default_projected):,} |",
              f"| Mean per frame | {statistics.mean(default_raw):,.2f} | {statistics.mean(default_projected):,.2f} |",
              f"| P95 per frame | {percentile(default_raw,.95):,} | {percentile(default_projected,.95):,} |",
              f"| Maximum per frame | {max(default_raw):,} | {max(default_projected):,} |",
              "", f"Default-play projected change: {metrics['default_play_change_percent']:+.3f}% ({metrics['default_play_change_tokens']:+,} tokens).",
              "", "## All 695 paired transport frames (context)", "",
              "| Measure | Raw CLI 7 | Offline projected CLI 8 |", "| --- | ---: | ---: |",
              f"| Complete RECV frames | {len(responses)} | {len(candidate)} |",
              f"| Reference tokens, complete lines without LF | {sum(raw_tokens):,} | {sum(projected_tokens):,} |",
              f"| Mean per frame | {statistics.mean(raw_tokens):,.2f} | {statistics.mean(projected_tokens):,.2f} |",
              f"| P95 per frame | {percentile(raw_tokens,.95):,} | {percentile(projected_tokens,.95):,} |",
              f"| Maximum per frame | {max(raw_tokens):,} | {max(projected_tokens):,} |",
              "", f"All-pairs projected change: {metrics['projected_change_percent']:+.3f}% ({metrics['projected_change_tokens']:+,} tokens).",
              f"For the 694 comparable non-info frames: {metrics['comparable_change_percent']:+.3f}%.",
              "The same v7 objects reserialized in Python use " + f"{sum(minified_tokens):,} tokens; use that number when isolating serialization effects.",
              "The single info frame uses the current production static schema but an explicit offline build-identity marker. It is counted for total size and excluded from field equivalence checks.",
              "The probe verified at runtime that `CompactProtocol`, `GameplayObservation` and `GameplayEvidence` loaded from current `game-control/build/classes/java/main`, ahead of older fixture jars.",
              "Per-frame raw/projected byte and token counts are in `frames.tsv`.",
              "", "## Correctness checks on the replayable original information", "",
              "All checked fields match after independently decoding each candidate frame. Counts show frames in which each check applied:", "",
              "| Field group | Matching frames |", "| --- | ---: |"]
    report += [f"| {name} | {count} |" for name, count in sorted(checks.items())]
    report += ["", "The complete known map, including visited/mapped visibility and environment, is compared as a full decoded object. Action alternatives and constraints are compared both as ordered global actions and as current node operations.",
               "", "## Graphic source sidecar", "",
               "| Classification | Source | Occurrences |", "| --- | --- | ---: |"]
    report += [f"| {row['classification']} | {row['source']} | {row['count']:,} |" for row in top]
    report += ["", "The full source-specific, text-redacted classification list is in `sidecar.json`. Raw assets, colors and timestamps are not copied into this report.",
               "", "## Limits", ""]
    report += ["- " + text for text in metrics["unverified_by_replay"]]
    report += ["", "The projected token difference includes fields for which the old transport has no recoverable native semantic identity. It is **not** a validated saving or proof of semantic equivalence. There is no defensible numeric lower or upper bound on shipped CLI 8 token savings from this replay alone; new native facts may add data and remove placeholders. Native fixture and package validation are separate.",
               "", f"Raw RECV SHA-256: `{metrics['raw_recv_sha256']}`. Original SEND/RECV files were verified unchanged after the run.", ""]
    if cue_appendix is not None:
        report += ["## Native offscreen cue cost (separate fixture)", "",
                   "This is one actual default v8 child wire frame from the isolated Tengu bomb fixture after `view.pan x=5000,y=5000`. Its bomb warning cells remain in the same frame's FOV. No v7 matched run is implied.",
                   f"The native fixture build ID was `{cue_appendix['native_fixture_build_id']}`; this evidence remains tied to that exact build even if the offline corpus projection is rerun after another build.", "",
                   f"- Complete raw frame: {cue_appendix['raw_frame_tokens_excluding_lf']:,} reference tokens ({cue_appendix['raw_frame_bytes_excluding_lf']:,} bytes, excluding LF).",
                   f"- Removing only {cue_appendix['bomb_smoke_count']} `bomb_smoke` cue rows from a copy of this frame changes the minified count by {cue_appendix['bomb_smoke_marginal_tokens']:,} tokens.",
                   f"- Removing every cue row changes it by {cue_appendix['all_cue_rows_marginal_tokens']:,} tokens; removing the whole `data.cues` block changes it by {cue_appendix['whole_cues_block_marginal_tokens']:,} tokens.",
                   "The three ablations are within-frame counterfactual field contributions, not valid playable responses or an estimate of v7-to-v8 savings. Byte-exact fixture SHA-256 and counts are in `offscreen-cue-cost.json`.", ""]
    (OUT / "report.md").write_text("\n".join(report))
    print(json.dumps({"frames": len(responses), "raw_tokens": sum(raw_tokens),
                      "projected_tokens": sum(projected_tokens),
                      "change_percent": metrics["projected_change_percent"],
                      "checks": dict(checks), "output": str(OUT)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
