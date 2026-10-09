"""Synthetic study entrypoints; no JVM, tokenizer download or personal corpus."""
import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import cli8_token_study as study


class Cli8TokenStudyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.session = self.root / "public"
        self.session.mkdir()
        self.baseline = self.root / "baseline.json"
        self.output = self.root / "chosen-output"

    def corpus(self, operations=("info", "state", "state")):
        requests = [{"v": 7, "id": f"t1.{i}", "op": op, "s": "s1"}
                    for i, op in enumerate(operations, 1)]
        responses = [{"v": 7, "id": request["id"], "s": "s1", "rev": "r1", "st": "completed",
                      "data": {"phase": "player_ready", "hero": {"hp": 20}}}
                     for request in requests]
        for name, frames in (("send.raw", requests), ("recv.raw", responses)):
            (self.session / name).write_text("".join(study.compact(frame) + "\n" for frame in frames))
        self.baseline.write_text(json.dumps({
            "send_sha256": study.digest((self.session / "send.raw").read_bytes()),
            "recv_sha256": study.digest((self.session / "recv.raw").read_bytes()),
            "response_count": len(responses),
            "response_tokens": {"total": sum(len(study.compact(frame)) for frame in responses)},
        }))

    def frozen(self, output):
        for name in study.HISTORICAL:
            (output / name).write_text("synthetic helper fixture")
        def decoder(version):
            def decode(frame):
                if frame["v"] != version:
                    raise ValueError("synthetic decoder protocol mismatch")
                return SimpleNamespace(data=copy.deepcopy(frame["data"]))
            return SimpleNamespace(decode_wire_response=decode)
        projection = SimpleNamespace(response=lambda frame, op: {
            "result": {"observation": copy.deepcopy(frame["data"])}})
        return decoder(7), projection, decoder(8), projection

    def project(self, input_path, output_path):
        rows = [json.loads(line) for line in input_path.read_text().splitlines()]
        frames = [{"v": 8, "id": row["id"], "s": row["s"], "rev": row["rev"], "st": row["st"],
                   "data": row["result"]["observation"]} for row in rows]
        output_path.write_text("".join(study.compact(frame) + "\n" for frame in frames))
        return {"synthetic_projection": True}

    def invoke(self, **kwargs):
        with patch.object(study, "frozen_helpers", side_effect=self.frozen), \
                patch.object(study, "java_projection", side_effect=self.project) as projection, \
                patch.object(study, "token_count", side_effect=len), contextlib.redirect_stdout(io.StringIO()):
            study.main(["--session", str(self.session), "--baseline", str(self.baseline),
                        "--output", str(kwargs.get("output", self.output))])
            return projection

    def test_explicit_baseline_and_output_support_a_three_frame_corpus(self):
        self.corpus()
        original = {path: path.read_bytes() for path in [self.baseline, *self.session.iterdir()]}
        self.invoke()
        metrics = json.loads((self.output / "metrics.json").read_text())
        self.assertEqual(3, metrics["frames"])
        self.assertEqual(2, metrics["comparable_frames"])
        self.assertEqual(2, metrics["default_play_frames"])
        self.assertEqual(str(self.baseline.resolve()), metrics["baseline_file"])
        self.assertEqual(study.digest(original[self.baseline]), metrics["baseline_sha256"])
        report = (self.output / "report.md").read_text()
        self.assertIn("of 3 CLI 7 transport frames", report)
        self.assertIn("2 comparable non-info frames", report)
        self.assertNotIn("695", report)
        self.assertEqual(original, {path: path.read_bytes() for path in original})

    def test_corpus_without_default_play_or_comparable_frames_reports_absence(self):
        self.corpus(("info",))
        self.invoke()
        metrics = json.loads((self.output / "metrics.json").read_text())
        self.assertEqual(0, metrics["default_play_frames"])
        self.assertIsNone(metrics["default_play_change_percent"])
        self.assertIsNone(metrics["comparable_change_percent"])
        self.assertIn("N/A", (self.output / "report.md").read_text())

    def test_explicit_baseline_count_and_hash_are_enforced_before_projection(self):
        for field, value in (("response_count", 4), ("recv_sha256", "wrong"),
                             ("response_count", True), ("response_count", 0)):
            with self.subTest(field=field, value=value):
                self.corpus()
                baseline = json.loads(self.baseline.read_text())
                baseline[field] = value
                self.baseline.write_text(json.dumps(baseline))
                with self.assertRaises((AssertionError, ValueError)):
                    self.invoke()
                self.assertFalse(self.output.exists())

    def test_output_cannot_overwrite_source_or_baseline(self):
        self.corpus()
        original = self.baseline.read_bytes()
        for output in (self.session, self.root):
            if output == self.root:
                self.baseline = self.root / "metrics.json"
                self.baseline.write_bytes(original)
            with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
                self.invoke(output=output)
            self.assertEqual(original, self.baseline.read_bytes())

    def test_custom_baseline_names_inside_output_are_rejected_before_any_generation(self):
        self.corpus()
        original_baseline = self.baseline.read_bytes()
        self.output.mkdir()
        for filename in ("sidecar.json", "report.md", "canonical-input.jsonl", "nested/other.json"):
            with self.subTest(filename=filename):
                baseline = self.output / filename
                baseline.parent.mkdir(parents=True, exist_ok=True)
                baseline.write_bytes(original_baseline)
                self.assert_rejected_without_writes(self.output, baseline)

    def test_equal_and_nested_source_output_directories_are_rejected_before_generation(self):
        self.corpus()
        for output in (self.session, self.session / "analysis", self.root):
            with self.subTest(output=output):
                self.assert_rejected_without_writes(output, self.baseline)
        self.assertFalse((self.session / "analysis").exists())

    def test_existing_empty_output_is_accepted(self):
        self.corpus()
        self.output.mkdir()
        self.invoke()
        self.assertEqual(3, json.loads((self.output / "metrics.json").read_text())["frames"])

    def test_existing_reports_and_symlink_output_are_rejected_without_generation(self):
        self.corpus()
        self.output.mkdir()
        (self.output / "report.md").write_text("Previous research result must survive")
        self.assert_rejected_without_writes(self.output, self.baseline)
        alias = self.root / "output-link"
        alias.symlink_to(self.output, target_is_directory=True)
        self.assert_rejected_without_writes(alias, self.baseline)

    def test_report_symlink_cannot_overwrite_an_external_baseline(self):
        self.corpus()
        self.output.mkdir()
        (self.output / "metrics.json").symlink_to(self.baseline)
        self.assert_rejected_without_writes(self.output, self.baseline)

    def test_offscreen_input_and_its_manifest_cannot_live_in_output(self):
        self.corpus()
        self.output.mkdir()
        wire = self.output / "report.md"
        wire.write_text('{"v":8,"id":"fixture","st":"completed","data":{}}\n')
        (self.output / "visual-cue-result.json").write_text(json.dumps({
            "ok": True, "fixture": "visual-bomb", "evidence": {"offscreen_pan_wire": str(wire)}}))
        self.assert_rejected_without_writes(self.output, self.baseline,
                                           ["--offscreen-wire", str(wire)])

    def test_offscreen_manifest_resolved_path_is_checked_before_output_creation(self):
        self.corpus()
        fixture = self.root / "fixture"
        fixture.mkdir()
        wire = fixture / "wire.json"
        wire.write_text("synthetic public frame")
        (fixture / "visual-cue-result.json").symlink_to(self.output / "report.md")
        self.assert_rejected_without_writes(self.output, self.baseline,
                                           ["--offscreen-wire", str(wire)])
        self.assertFalse(self.output.exists())

    def assert_rejected_without_writes(self, output, baseline, extra_args=()):
        before = {path.relative_to(self.root): path.read_bytes() if path.is_file() else None
                  for path in self.root.rglob("*")}
        with patch.object(study, "frozen_helpers") as freeze, \
                patch.object(study, "java_projection") as projection, \
                patch.object(Path, "mkdir") as mkdir, \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            study.main(["--session", str(self.session), "--baseline", str(baseline),
                        "--output", str(output), *extra_args])
        freeze.assert_not_called()
        projection.assert_not_called()
        mkdir.assert_not_called()
        after = {path.relative_to(self.root): path.read_bytes() if path.is_file() else None
                 for path in self.root.rglob("*")}
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
