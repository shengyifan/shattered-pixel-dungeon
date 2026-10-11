"""Fresh production profiles stay empty until launcher acceptance; clones retain audit identity."""
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import Mock, patch

import fixture_support
from fixture_smoke import FixtureClient
from machine_smoke import Client


class FixturePreparationTest(unittest.TestCase):
    def test_clients_and_metadata_do_not_poison_a_fresh_profile(self):
        for client_type in (Client, FixtureClient):
            with self.subTest(client=client_type.__name__), tempfile.TemporaryDirectory() as directory:
                profile = Path(directory) / "profile"
                profile.mkdir()
                fixture_support.metadata(profile, "fresh-production", "test-runtime")

                def launch(command, **kwargs):
                    self.assertEqual([], list(profile.iterdir()))
                    self.assertIn(str(profile), command)
                    self.assertFalse(Path(kwargs["stderr"].name).is_relative_to(profile))
                    return Mock()

                with patch("machine_smoke.subprocess.Popen", side_effect=launch) as popen:
                    client = client_type(["packaged-spdctl"], profile)
                self.assertEqual(1, popen.call_count)
                self.assertEqual([], list(profile.iterdir()))
                client.stderr.close()
                if isinstance(client, FixtureClient):
                    client.trace.close()
                marker = json.loads((client.artifact_dir / "test_fixture.json").read_text())
                self.assertTrue(marker["test_fixture"])
                self.assertFalse(marker["counts_as_win"])

    def test_current_profile_copy_retains_both_audits_and_handle_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            template = fixture_support.safe_profile(root, "source")
            destination = fixture_support.safe_profile(root, "copy")
            (template / "audit").mkdir()
            for side in ("public", "internal"):
                with closing(sqlite3.connect(template / "audit" / (side + ".sqlite3"))) as db, db:
                    db.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT)")
                    db.executemany("INSERT INTO metadata VALUES (?, ?)", [
                        ("schema_version", "12"), ("game_version", "4.0.2"),
                        ("game_version_code", "922"), ("pair_generation", "fixture-pair"),
                    ])
                    db.execute("CREATE TABLE public_handles (kind TEXT, handle TEXT, canonical TEXT)")
                    db.execute("INSERT INTO public_handles VALUES ('scope', 's2', 'run:fixture-current')")
            (template / "game1").mkdir()
            (template / "game1/game.dat").write_bytes(b"fixture save bytes")
            (template / "settings.xml").write_text("fixture preferences")
            (template / "public-trace.jsonl").write_text("fixture trace is separate evidence")
            fake_module = root / "desktop-control/src/test/python/fixture_support.py"
            with patch.object(fixture_support, "__file__", str(fake_module)):
                fixture_support.copy_audited_profile(template, destination)
            self.assertEqual(b"fixture save bytes", (destination / "game1/game.dat").read_bytes())
            self.assertFalse((destination / "public-trace.jsonl").exists())
            for side in ("public", "internal"):
                with closing(sqlite3.connect(destination / "audit" / (side + ".sqlite3"))) as db:
                    self.assertEqual(("fixture-pair",), db.execute(
                        "SELECT value FROM metadata WHERE key='pair_generation'").fetchone())
                    self.assertEqual(("run:fixture-current",), db.execute(
                        "SELECT canonical FROM public_handles WHERE handle='s2'").fetchone())


if __name__ == "__main__":
    unittest.main()
