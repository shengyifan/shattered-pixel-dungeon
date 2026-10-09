"""Offline loader tests; mocked Git bytes never launch a game or need history."""
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import historical_v7_helpers as helpers


DECODER = b'''PROTOCOL_VERSION = 7
def decode_wire_response(frame):
    if frame.get("v") != PROTOCOL_VERSION:
        raise ValueError("historical protocol mismatch")
    return frame
'''
PROJECTION = b'''import sys
from spdctl_client import decode_wire_response
sys.path.insert(0, "synthetic-old-client-path")
'''


class HistoricalV7HelpersTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name)
        for name in ("spdctl_research_v7_decoder", "spdctl_research_v7_projection"):
            old = sys.modules.get(name)
            self.addCleanup(self.restore_module, name, old)

    @staticmethod
    def restore_module(name, previous):
        if previous is None:
            sys.modules.pop(name, None)
        else:
            sys.modules[name] = previous

    def git(self, command, **kwargs):
        self.assertEqual(["git", "show"], command[:2])
        self.assertTrue(command[2].startswith(helpers.OLD_COMMIT + ":"))
        return SimpleNamespace(returncode=0,
                               stdout=PROJECTION if command[2].endswith("protocol7.py") else DECODER)

    def test_frozen_v7_decoder_and_projection_leave_active_client_imports_unchanged(self):
        active = SimpleNamespace(PROTOCOL_VERSION=9)
        original_path = sys.path[:]
        with patch.dict(sys.modules, {"spdctl_client": active}), \
                patch.object(helpers.subprocess, "run", side_effect=self.git):
            decoder = helpers.load_decoder(self.output)
            projection = helpers.load_projection(self.output, decoder)
            self.assertIs(active, sys.modules["spdctl_client"])
            self.assertEqual(original_path, sys.path)
            self.assertEqual(7, decoder.PROTOCOL_VERSION)
            self.assertIs(decoder.decode_wire_response, projection.decode_wire_response)
            self.assertEqual({"v": 7}, projection.decode_wire_response({"v": 7}))
            with self.assertRaises(ValueError):
                decoder.decode_wire_response({"v": 9})
            self.assertEqual(DECODER, (self.output / "historical_v7_decoder.py").read_bytes())

    def test_existing_frozen_source_must_equal_the_pinned_git_bytes(self):
        path = self.output / "historical_v7_decoder.py"
        path.write_bytes(b"modified historical source")
        with patch.object(helpers.subprocess, "run", side_effect=self.git), \
                self.assertRaisesRegex(ValueError, "drifted"):
            helpers.load_decoder(self.output)
        self.assertEqual(b"modified historical source", path.read_bytes())

    def test_low_level_loader_reuses_identical_frozen_source_without_rewriting_it(self):
        path = self.output / "historical_v7_decoder.py"
        path.write_bytes(DECODER)
        modified = path.stat().st_mtime_ns
        with patch.object(helpers.subprocess, "run", side_effect=self.git):
            decoder = helpers.load_decoder(self.output)
        self.assertEqual({"v": 7}, decoder.decode_wire_response({"v": 7}))
        self.assertEqual(DECODER, path.read_bytes())
        self.assertEqual(modified, path.stat().st_mtime_ns)

    def test_missing_git_history_has_explicit_dependency_error(self):
        with patch.object(helpers.subprocess, "run", return_value=SimpleNamespace(returncode=128)), \
                self.assertRaisesRegex(RuntimeError, helpers.OLD_COMMIT):
            helpers.load_decoder(self.output)
        self.assertEqual([], list(self.output.iterdir()))

    def test_projection_import_failure_restores_bindings_and_search_path(self):
        original_path = sys.path[:]
        active = SimpleNamespace(PROTOCOL_VERSION=9)
        with patch.dict(sys.modules, {"spdctl_client": active}):
            with self.assertRaisesRegex(RuntimeError, "synthetic"):
                with helpers.decoder_import_binding(SimpleNamespace(PROTOCOL_VERSION=7)):
                    sys.path.insert(0, "synthetic-path")
                    raise RuntimeError("synthetic failure")
            self.assertIs(active, sys.modules["spdctl_client"])
            self.assertEqual(original_path, sys.path)


if __name__ == "__main__":
    unittest.main()
