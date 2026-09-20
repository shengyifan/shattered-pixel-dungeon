"""Offline identity assertions with minimal SQLite registries; no game or save reads."""
import json
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import fixture_identity as identity
import fixture_smoke
from autoplay import outcome_of


class FixtureIdentityTest(unittest.TestCase):
    def setUp(self):
        root=Path(__file__).resolve().parents[4]/"desktop-control/build/fixtures"
        root.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix="identity-unit-",dir=root)
        self.profile=Path(self.temp.name)
        (self.profile/"audit").mkdir()
        self.database=self.profile/"audit/public.sqlite3"
        with closing(sqlite3.connect(self.database)) as db, db:
            db.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
            db.execute("INSERT INTO metadata VALUES('schema_version','10')")
            db.execute("CREATE TABLE public_handles(kind TEXT,canonical TEXT,handle TEXT)")
            db.executemany("INSERT INTO public_handles VALUES(?,?,?)",[
                ("scope","run:fixture","s2"),("revision","epoch:17","rh"),
                ("activity","activity:epoch:1","a1"),("save","save-uuid","p1")])

    def tearDown(self):
        self.temp.cleanup()

    def test_matching_requires_same_scope_and_complete_revision_and_is_read_only(self):
        before={p.name:p.read_bytes() for p in (self.profile/"audit").iterdir()}
        row={"scope_id":"run:fixture","state_version":"epoch:17"}
        state={"scope_id":"s2","state_version":"rh"}
        self.assertTrue(identity.fixture_context_matches(self.profile,row,state))
        self.assertFalse(identity.fixture_context_matches(self.profile,{**row,"scope_id":"run:other"},state))
        self.assertFalse(identity.fixture_context_matches(self.profile,{**row,"state_version":"other-epoch:17"},state))
        self.assertEqual("activity:epoch:1",identity.fixture_canonical_identity(self.profile,"activity","a1"))
        self.assertEqual("save-uuid",identity.fixture_canonical_identity(self.profile,"save","p1"))
        self.assertEqual(before,{p.name:p.read_bytes() for p in (self.profile/"audit").iterdir()})

    def test_guard_rejects_personal_or_unknown_registry_and_symlinks(self):
        with tempfile.TemporaryDirectory() as outside, patch.object(identity.sqlite3,"connect") as connect:
            with self.assertRaises(ValueError):identity.fixture_canonical_identity(Path(outside),"scope","s2")
            connect.assert_not_called()
        with self.assertRaises(AssertionError):identity.fixture_canonical_identity(self.profile,"revision","rzz")
        moved=self.database.with_name("original.sqlite3")
        self.database.rename(moved)
        self.database.symlink_to(moved)
        with self.assertRaises(ValueError):identity.fixture_canonical_identity(self.profile,"scope","s2")

    def test_canonical_mock_contexts_do_not_read_a_registry(self):
        row={"scope_id":"run:mock","state_version":"v1"}
        with patch.object(identity.sqlite3,"connect") as connect:
            self.assertTrue(identity.fixture_context_matches(Path("unused"),row,row))
            self.assertFalse(identity.fixture_context_matches(Path("unused"),{**row,"scope_id":"run:other"},row))
            self.assertFalse(identity.fixture_identity_matches(Path("unused"),"scope",None,None))
            self.assertFalse(identity.fixture_identity_matches(Path("unused"),"scope","",""))
            connect.assert_not_called()

    def test_gui_and_fixture_checkpoints_match_actual_short_response(self):
        row={"scope_id":"run:fixture","state_version":"epoch:17","language":"CHI_SMPL",
             "language_code":"zh","fullscreen":False,"assertion_only":"sentinel"}
        for name in ("ui-assertions.jsonl","fixture-assertions.jsonl"):
            (self.profile/name).write_text(json.dumps(row)+"\n")
        state={"scope_id":"s2","state_version":"rh","observation":{"ui":{"display":{"language":"zh","fullscreen":False}}}}
        with patch.dict(fixture_smoke.os.environ,{"SPDCTL_TEST_LANGUAGE":"zh"}):
            proof=fixture_smoke.assert_gui_environment(self.profile,state)
        self.assertEqual("epoch:17",proof["asserted_state_version"])
        self.assertEqual(row,fixture_smoke.checkpoint(self.profile,"rh"))
        (self.profile/"ui-assertions.jsonl").write_text(json.dumps({**row,"scope_id":"run:wrong"})+"\n")
        with self.assertRaises(AssertionError):fixture_smoke.assert_gui_environment(self.profile,state)

    def test_outcome_uses_opaque_scope_equality_and_only_explicit_terminal_result(self):
        for scope in ("s2","run:canonical-mock"):
            for result in ("won","lost"):
                self.assertEqual(result,outcome_of({"scope_id":scope,"run_outcome":{"scope_id":scope,"result":result}}))
        for scope,result,outer in (("","won",""),(None,"won",None),(42,"won",42),("s2","ended","s2"),("s2","won","s3")):
            self.assertIsNone(outcome_of({"scope_id":outer,"run_outcome":{"scope_id":scope,"result":result}}))


if __name__=="__main__":
    unittest.main()
