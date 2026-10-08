"""Read one supplied public response through the official, transport-free decoder.

This module never locates profiles, polls files, writes requests, or starts a game.
The supplied record is a frozen observation, not proof of a live action binding.
"""

import hashlib
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "desktop-control" / "client"))
from spdctl_client import (  # noqa: E402
    ClientResponseError, DecodeError, decode_client_response, map_cell,
)


class SnapshotError(Exception):
    def __init__(self, report):
        self.report = report
        super().__init__(report["error"]["message"])


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")))


def add_response_argument(parser):
    parser.add_argument("--response", required=True, metavar="PATH_OR_-",
                        help="Read one complete public JSON response; '-' reads stdin. "
                             "Do not supply a profile, save, or private audit file.")


def problem_records(problems):
    return [{"stage": p.stage, "code": p.code, "payload": p.payload} for p in problems]


class Snapshot:
    def __init__(self, response, metadata):
        self.response = response
        self.metadata = metadata
        self.decoded = None
        self.frame = None

    def context(self):
        result = dict(self.metadata)
        if self.decoded is not None:
            d = self.decoded
            result.update(
                controller=d.controller, original_request=d.raw.get("request"),
                outcome=d.outcome.frame if d.outcome else None,
                receipt=d.receipt.frame if d.receipt else None,
                discovery=d.discovery.frame if d.discovery else None,
                original_response=d.original_response.frame if d.original_response else None,
                problems=problem_records(d.problems),
                late_responses=[{"request": late.request, "response": late.response.frame}
                                for late in d.late_responses],
                pending_reason=d.pending_reason, pending_stage=d.stage,
                pending_request=d.pending_request,
            )
        if self.frame is not None:
            result.update({key: self.frame.frame.get(key) for key in ("id", "s", "rev", "st")})
        return result

    def fail(self, stage, code, message, **details):
        raise SnapshotError({**self.context(), "kind": "error",
                             "error": {"stage": stage, "code": code,
                                       "message": message, **details},
                             "raw_response": self.response})


def load_snapshot(source):
    metadata = {"input_path": "<stdin>" if source == "-" else str(Path(source).resolve()),
                "snapshot_mode": "supplied_public_snapshot_not_live_binding"}
    snapshot = Snapshot(None, metadata)
    try:
        raw = sys.stdin.buffer.read() if source == "-" else Path(source).read_bytes()
        metadata["source_sha256"] = hashlib.sha256(raw).hexdigest()
        record = json.loads(raw)
    except (OSError, ValueError) as error:
        snapshot.fail("read", "INVALID_INPUT", str(error))
    if not isinstance(record, dict):
        snapshot.response = record
        snapshot.fail("read", "INVALID_INPUT", "Expected one JSON object")
    # Controller response wrappers own their nested response. Only the old,
    # explicitly sequenced public-record envelope is unwrapped here.
    if "seq" in record and "response" in record and not ({"v", "controller"} & record.keys()):
        metadata["record_seq"] = record["seq"]
        snapshot.response = record["response"]
    else:
        snapshot.response = record
    try:
        snapshot.decoded = decode_client_response(snapshot.response)
        snapshot.frame = snapshot.decoded.require_current_frame()
    except DecodeError as error:
        snapshot.fail("decode", "DECODE_ERROR", str(error), identity=error.identity,
                      decoder_stage=error.stage, decoder_contexts=error.contexts)
    except ClientResponseError as error:
        snapshot.fail("current_frame", error.problems[0].code, str(error),
                      problems=problem_records(error.problems))
    return snapshot


def decoded_cells(frame):
    game_map = frame.data.get("map")
    return [] if not isinstance(game_map, dict) else game_map.get("decoded_cells", game_map.get("cells", []))


def report_error(error, snapshot=None):
    if isinstance(error, SnapshotError):
        emit(error.report)
    elif snapshot is not None:
        try:
            snapshot.fail("analysis", "INVALID_INPUT", str(error))
        except SnapshotError as failure:
            emit(failure.report)
    else:
        emit({"kind": "error", "error": {"stage": "analysis", "code": "INVALID_INPUT",
                                           "message": str(error)}})
    return 2
