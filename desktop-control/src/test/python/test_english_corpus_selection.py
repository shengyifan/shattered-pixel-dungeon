"""Corpus selection never depends on archived docs or scans unrelated profiles."""
import tempfile
import unittest
from pathlib import Path

from english_corpus_probe import collect


class EnglishCorpusSelectionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name).resolve()

    def tearDown(self):
        self.directory.cleanup()

    def trace(self, name):
        profile = self.root / "desktop-control/build/fixtures" / name
        profile.mkdir(parents=True)
        (profile / "fixture-result.json").write_text('{"test_fixture":true,"ok":true}')
        trace = profile / "public-trace.jsonl"
        trace.write_text('{"response":{"ok":true}}\n')
        return trace

    def test_no_implicit_selection_from_archived_docs_or_existing_menu_profiles(self):
        docs = self.root / "docs"
        docs.mkdir()
        (docs / "cli-menu-validation.json").write_text("Must not be parsed")
        self.trace("menu-scenes-existing")
        self.assertEqual(([], []), collect(self.root))

    def test_only_explicit_current_traces_are_sorted_and_deduplicated(self):
        a, b = self.trace("a"), self.trace("b")
        self.trace("unlisted")
        selected, skipped = collect(self.root, [str(b), str(a.relative_to(self.root)), str(b)])
        self.assertEqual([str(a.relative_to(self.root)), str(b.relative_to(self.root))], selected)
        self.assertEqual([], skipped)

    def test_formal_profile_and_missing_trace_are_explicitly_rejected(self):
        values = ["desktop-control/build/playthroughs/warrior/public-trace.jsonl",
                  "desktop-control/build/fixtures/missing/public-trace.jsonl"]
        selected, skipped = collect(self.root, values)
        self.assertEqual([], selected)
        self.assertEqual(set(values), {row["candidate"] for row in skipped})
        self.assertTrue(all(row["reason"] for row in skipped))

    def test_linked_public_trace_cannot_select_another_profile(self):
        source, linked = self.trace("source"), self.trace("linked")
        linked.unlink()
        linked.symlink_to(source)
        selected, skipped = collect(self.root, [str(linked)])
        self.assertEqual([], selected)
        self.assertIn("Symbolic links", skipped[0]["reason"])


if __name__ == "__main__":
    unittest.main()
