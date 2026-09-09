import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import sqlite3

from fixture_failure_diagnostics import collect


class FixtureFailureDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.base = self.root / "desktop-control/build/fixtures"
        self.runtime_id = "runtime-" + "a" * 32
        self.runtime = self.base / self.runtime_id
        self.runtime.mkdir(parents=True)
        (self.runtime / "test-runtime.json").write_text("{}")
        self.profile = self.base / "case-test"
        self.profile.mkdir()
        self.report = {"test_fixture": True, "counts_as_win": False, "runtime_id": self.runtime_id,
                       "profile": str(self.profile), "fixture": "test:fixture", "ok": True}
        (self.profile / "fixture-result.json").write_text(json.dumps(self.report))
        self.manifest = {"test_fixture": True, "counts_as_win": False, "runtime_id": self.runtime_id, "results": [self.report]}
        self.save_manifest()

    def tearDown(self):
        self.temp.cleanup()

    def save_manifest(self):
        (self.runtime / "results.json").write_text(json.dumps(self.manifest))

    def run_collect(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return collect(self.root, self.runtime_id)

    def test_unlisted_reports_are_never_read(self):
        unrelated = self.base / "unrelated-profile"
        unrelated.mkdir()
        (unrelated / "fixture-result.json").write_text("THIS IS NOT JSON AND MUST NEVER BE READ")
        self.assertEqual(1, self.run_collect()["passed"])

    def test_an_earlier_expected_protocol_error_does_not_hide_the_translation_failure(self):
        self.report["ok"] = False
        (self.profile / "fixture-result.json").write_text(json.dumps(self.report))
        audit=self.profile/"audit";audit.mkdir()
        with sqlite3.connect(audit/"internal.sqlite3") as db:
            db.execute("CREATE TABLE exceptions (sequence INTEGER,id TEXT,exception_class TEXT,stack_trace TEXT)")
            db.executemany("INSERT INTO exceptions VALUES (?,?,?,?)",[
                (1,"stale","ProtocolException","STALE_STATE"),
                (2,"failed","DisplayedTextEnglish$PublicTextUnavailableException",
                 "Private displayed text: 失败原文\nTranslation diagnostic: no_safe_resource_translation")])
        row=self.run_collect()["failures"][0]
        self.assertEqual("failed",row["request_id"])
        self.assertEqual("失败原文",row["displayed_text"])
        self.assertEqual("first_translation_failure_else_first_exception",row["exception_selection"])

    def test_incomplete_or_foreign_runtime_manifest_cannot_select_profiles(self):
        (self.runtime / "results.json").unlink()
        with self.assertRaises(FileNotFoundError):
            self.run_collect()
        self.manifest["results"][0]["runtime_id"] = "runtime-" + "b" * 32
        self.save_manifest()
        (self.profile / "fixture-result.json").write_text("MUST NOT READ A MISMATCHED ENTRY")
        with self.assertRaisesRegex(ValueError, "different runtime"):
            self.run_collect()

    def test_runtime_or_manifest_symlink_is_rejected(self):
        manifest = self.runtime / "results.json"
        outside = self.root / "outside-manifest.json"
        manifest.rename(outside)
        manifest.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            self.run_collect()

    def test_profile_and_report_symlinks_are_rejected(self):
        report = self.profile / "fixture-result.json"
        outside = self.root / "outside-report.json"
        report.rename(outside)
        report.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            self.run_collect()
        report.unlink()
        report.write_text(json.dumps(self.report))
        outside_profile = self.root / "outside-profile"
        self.profile.rename(outside_profile)
        self.profile.symlink_to(outside_profile, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            self.run_collect()

    def test_outside_profile_and_identifier_traversal_are_rejected(self):
        self.manifest["results"][0]["profile"] = str(self.root)
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "outside"):
            self.run_collect()
        with self.assertRaisesRegex(ValueError, "identifier"):
            collect(self.root, "../outside")

    def test_database_and_destination_symlinks_are_rejected_without_opening_targets(self):
        self.report["ok"] = False
        (self.profile / "fixture-result.json").write_text(json.dumps(self.report))
        audit = self.profile / "audit"
        audit.mkdir()
        outside = self.root / "not-a-sqlite-database"
        outside.write_text("DO NOT OPEN")
        (audit / "internal.sqlite3").symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            self.run_collect()
        (audit / "internal.sqlite3").unlink()
        (self.runtime / "failure-diagnostics.json").symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            self.run_collect()
        self.assertEqual("DO NOT OPEN", outside.read_text())

    def test_sqlite_sidecar_and_dangling_database_links_are_rejected(self):
        self.report["ok"] = False
        (self.profile / "fixture-result.json").write_text(json.dumps(self.report))
        audit = self.profile / "audit"
        audit.mkdir()
        database = audit / "internal.sqlite3"
        database.symlink_to(self.root / "missing-outside-database")
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            self.run_collect()
        database.unlink()
        database.write_text("The sidecar must be rejected before SQLite reads this")
        (audit / "internal.sqlite3-wal").symlink_to(self.root / "outside-wal")
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            self.run_collect()


if __name__ == "__main__":
    unittest.main()
