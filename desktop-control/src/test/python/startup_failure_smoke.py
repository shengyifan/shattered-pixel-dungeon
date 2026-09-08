#!/usr/bin/env python3
"""A known post-session/pre-game startup failure must close its audit session as FAILED."""
import argparse
import json
from pathlib import Path
import sqlite3
import subprocess
import uuid


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--launcher",type=Path,required=True);args=parser.parse_args()
    root=Path(__file__).resolve().parents[4]
    profile=root/"desktop-control/build/fixtures"/("startup-failure-"+uuid.uuid4().hex)
    emergency=profile/"audit/emergency";emergency.mkdir(parents=True)
    (profile/"test_fixture.json").write_text(json.dumps({"test_fixture":True,"counts_as_win":False},indent=2))
    # The importer must preserve an existing archived file, making Files.move fail before GameController exists.
    (emergency/"fixture.log").write_bytes(b"fixture diagnostic bytes\xff")
    (emergency/"fixture.log.imported").write_text("pre-existing archive must remain")
    process=subprocess.run([str(args.launcher.resolve()),"run","--machine","--data-dir",str(profile)],
                           input=b"",capture_output=True,timeout=30)
    assert process.returncode==1,(process.returncode,process.stderr)
    assert process.stdout==b"",process.stdout
    assert b"STARTUP_FAILED" in process.stderr
    assert (emergency/"fixture.log.imported").read_text()=="pre-existing archive must remain"
    sessions=[]
    for name in ["public","internal"]:
        with sqlite3.connect(profile/"audit"/(name+".sqlite3")) as db:
            assert db.execute("PRAGMA integrity_check").fetchone()==("ok",)
            session=db.execute("SELECT session_id,status,end_reason,ended_at FROM sessions").fetchall()
            assert len(session)==1 and session[0][1:3]==("FAILED","launcher_failure") and session[0][3],session
            assert db.execute("SELECT count(*) FROM requests").fetchone()==(0,)
            sessions.append(session)
            if name=="public":
                events=json.dumps(db.execute("SELECT kind,data_json FROM events").fetchall())
                assert "FileAlreadyExistsException" not in events and "fixture.log" not in events
            else:
                errors=db.execute("SELECT exception_class,message,session_id FROM exceptions").fetchall()
                assert any("FileAlreadyExistsException" in row[0] and row[2]==session[0][0] for row in errors),errors
    assert sessions[0]==sessions[1],sessions
    result={"result":"passed","test_fixture":True,"counts_as_win":False,"profile":str(profile.relative_to(root)),
            "launcher":str(args.launcher.resolve()),"exit_code":process.returncode,"stdout_messages":0,
            "session_status":"FAILED","session_reason":"launcher_failure","session_id":sessions[0][0][0],
            "point":"after beginSession, in importEmergencyReports, before GameController/MachineSession"}
    (profile/"startup-failure-result.json").write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False))


if __name__=="__main__":main()
