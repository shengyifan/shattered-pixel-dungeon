import copy
import contextlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import cli7_token_benchmark as benchmark
from cli7_token_benchmark import expand_legacy_records, normalized_frame


class Cli7TokenBenchmarkTest(unittest.TestCase):
    def test_legacy_structure_expansion_preserves_unrelated_values(self):
        frame = {"v": 6, "id": "t1.9", "data": {
            "inv": [{"loc": "backpack.0", "name": "Dart"}],
            "ui": {"node_shapes": [["id", "label", "loc", "ops", "future"]],
                   "op_defs": [[{"op": "click", "future": [None, False, 0]}]],
                   "nodes": [[0, "c1", 0, "backpack.0", 0, None]]},
            "unknown": {"ui": {"nodes": [[0, "not-a-node"]]}}
        }}
        original = copy.deepcopy(frame)
        result = expand_legacy_records(frame)
        self.assertEqual(original, frame)
        self.assertEqual(7, result["v"])
        self.assertEqual({"id": "c1", "label": 0, "loc": "backpack.0",
                          "ops": [{"op": "click", "future": [None, False, 0]}],
                          "future": None}, result["data"]["ui"]["nodes"][0])
        self.assertEqual(original["data"]["unknown"], result["data"]["unknown"])

    def test_history_tables_are_local_and_raw_evidence_is_opaque(self):
        snapshot = {"ui": {"node_shapes": [["id", "text"]], "nodes": [[0, "c1", "before"]]}}
        frame = {"v": 6, "data": {"before": snapshot, "raw": snapshot, "reply": snapshot}}
        result = expand_legacy_records(frame)
        self.assertEqual({"id": "c1", "text": "before"}, result["data"]["before"]["ui"]["nodes"][0])
        self.assertEqual(snapshot, result["data"]["raw"])
        self.assertEqual(snapshot, result["data"]["reply"])

    def test_normalization_only_removes_representation_tables(self):
        frame = {"v": 6, "data": {"acts": [{"op": "wait"}], "act_templates": [],
                                  "inv_templates": [], "ui": {"node_templates": [], "nodes": []},
                                  "raw": {"act_templates": [False]}, "future": None}}
        result = normalized_frame(frame)
        self.assertEqual({"v": 7, "data": {"acts": [{"op": "wait"}], "ui": {"nodes": []},
                                           "raw": {"act_templates": [False]}, "future": None}}, result)
        self.assertIn("act_templates", frame["data"])

    def test_entrypoint_decodes_v7_candidates_with_the_pinned_historical_decoder(self):
        metrics = self.run_synthetic_entrypoint()
        self.assertEqual(0, metrics["groups"]["synthetic"]["combined"]["reduction_percent"])

    def test_entrypoint_reports_growth_without_treating_it_as_a_codec_failure(self):
        metrics = self.run_synthetic_entrypoint(grow=True)
        self.assertLess(metrics["groups"]["synthetic"]["combined"]["reduction_percent"], 0)
        self.assertEqual(1, metrics["groups"]["synthetic"]["combined"]["frames_larger"])

    def test_explicit_performance_threshold_rejects_zero_savings_and_growth(self):
        for grow in (False, True):
            with self.subTest(grow=grow):
                self.run_synthetic_entrypoint(grow=grow, require_smaller=True)

    def run_synthetic_entrypoint(self, grow=False, require_smaller=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "public", root / "result"
            source.mkdir()
            output.mkdir()  # An explicitly selected empty output is supported.
            request = {"v": 6, "id": "t1.1", "op": "state", "s": "s1"}
            reply = {"v": 6, "id": "t1.1", "s": "s1", "rev": "r1", "st": "completed",
                     "data": {"phase": "player_ready"}}
            (source / "send.raw").write_text(benchmark.wire(request))
            (source / "recv.raw").write_text(benchmark.wire(reply))
            classpath = root / "classpath.txt"
            classpath.write_text("synthetic-classpath")
            versions = []
            def decode_v7(packet):
                versions.append(packet["v"])
                self.assertEqual(7, packet["v"])
                # The synthetic probe preserves this simple public payload.
                return benchmark.decode_v6({**packet, "v": 6})
            def loader(destination):
                (destination / "historical_v7_decoder.py").write_text("synthetic pinned v7 fixture")
                return SimpleNamespace(decode_wire_response=decode_v7)
            def probe(command, **kwargs):
                self.assertIn("Cli7StructureProbe", command[4])
                raw = Path(command[-2]).read_text()
                if grow:
                    candidate = json.loads(raw)
                    candidate["data"].update(act_templates=[], inv_templates=[])
                    raw = benchmark.wire(candidate)
                for mode in ("sharing", "templates", "combined"):
                    (output / (mode + ".jsonl")).write_text(raw)
            tokenizer = SimpleNamespace(get_encoding=lambda name: SimpleNamespace(
                encode=lambda text, **kwargs: list(text)))
            args = ["cli7_token_benchmark.py", "--d9-trace", str(source),
                    "--current-fixture", str(source), "--classpath-file", str(classpath),
                    "--output", str(output)]
            if require_smaller:
                args.append("--require-smaller")
            with patch("sys.argv", args), patch.dict("sys.modules", {"tiktoken": tokenizer}), \
                    patch.object(benchmark.importlib.metadata, "version", return_value="0.12.0"), \
                    patch.object(benchmark, "source_files", return_value=[("synthetic", source, None)]), \
                    patch.object(benchmark, "load_decoder", side_effect=loader), \
                    patch.object(benchmark.subprocess, "run", side_effect=probe), \
                    contextlib.redirect_stdout(io.StringIO()):
                if require_smaller:
                    with self.assertRaisesRegex(AssertionError, "Combined token total must be smaller"):
                        benchmark.main()
                else:
                    benchmark.main()
            self.assertEqual([7, 7, 7], versions)
            metrics = json.loads((output / "metrics.json").read_text())
            self.assertEqual(benchmark.OLD_COMMIT, metrics["historical_decoder_commit"])
            self.assertEqual(3, metrics["python_comparisons"])
            self.assertEqual(require_smaller, metrics["require_smaller"])
            return metrics

    def test_entrypoint_rejects_reused_symlink_or_source_outputs_before_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "public", root / "result"
            source.mkdir()
            (source / "send.raw").write_text("untouched public input")
            output.mkdir()
            (output / "metrics.json").write_text("previous research result")
            alias = root / "alias"
            alias.symlink_to(output, target_is_directory=True)
            for selected in (output, alias, source, source / "nested", root):
                with self.subTest(output=selected):
                    before = {path.relative_to(root): path.read_bytes() if path.is_file() else None
                              for path in root.rglob("*")}
                    with patch.object(benchmark, "load_decoder") as decoder, \
                            patch.object(benchmark.subprocess, "run") as process, \
                            patch.object(Path, "mkdir") as mkdir, \
                            contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                        benchmark.main(["--d9-trace", str(source), "--current-fixture", str(source),
                                        "--classpath-file", str(root / "classpath.txt"),
                                        "--output", str(selected)])
                    decoder.assert_not_called()
                    process.assert_not_called()
                    mkdir.assert_not_called()
                    after = {path.relative_to(root): path.read_bytes() if path.is_file() else None
                             for path in root.rglob("*")}
                    self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
