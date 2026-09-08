#!/usr/bin/env python3
"""An actual Actor-thread uncaught exception must fail the session even when its GUI loop returns."""
import json
from pathlib import Path
import shutil
import sqlite3
import threading
import time
import uuid
import zipfile

from fixture_smoke import FixtureClient, freeze_runtime, reach_game as fixture_start
from legacy_save_smoke import launch_command, metadata, safe_profile, stop, write_json
from machine_smoke import reach_game


def build_agent(root, runtime):
    """Assemble only test agent classes and pinned ASM from the existing test artifact."""
    dependencies = list((root / "desktop-control/build/libs").glob("test-only-engine-boundaries-*.jar"))
    assert len(dependencies) == 1, dependencies
    classes = list((root / "desktop-control/build/classes/java/crashAgent").glob("UncaughtRuntimeAgent*.class"))
    assert classes, "Run :desktop-control:crashAgentClasses first"
    destination = runtime / "test-only-uncaught-runtime.jar"
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as output:
        output.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: UncaughtRuntimeAgent\n\n")
        with zipfile.ZipFile(dependencies[0]) as dependency:
            for name in dependency.namelist():
                if name.startswith("org/objectweb/asm/") and not name.endswith("/"):
                    output.writestr(name, dependency.read(name))
        for source in classes:
            output.write(source, source.name)
    return destination


def main():
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text())
    runtime = root / "desktop-control/build/fixtures" / runtime_id
    agent = build_agent(root, runtime)
    template = safe_profile(root, "uncaught-template")
    metadata(template, "class:WARRIOR", runtime_id)
    seed = FixtureClient(launch_command(classpath, fixture=True), template)
    try:
        assert seed.request("protocol.info")["ok"]
        fixture_start(seed, "WARRIOR")
        seed.act("game.save")
    finally:
        stop(seed)
    profile = safe_profile(root, "uncaught-runtime")
    metadata(profile, "uncaught-runtime", runtime_id)
    for entry in template.iterdir():
        if entry.name == "settings.xml" or entry.name.startswith("game"):
            if entry.is_dir():
                shutil.copytree(entry, profile / entry.name)
            else:
                shutil.copy2(entry, profile / entry.name)
    command = launch_command(classpath)
    command.insert(1, "-javaagent:" + str(agent) + "=" + str(profile))
    client = FixtureClient(command, profile)
    try:
        assert client.request("protocol.info")["ok"]
        reach_game(client, resume=True)
        before = client.state()
        assert any(a["action"] == "wait" for a in before["actions"]), before["actions"]
        scope, request_id = before["scope_id"], "uncaught-" + uuid.uuid4().hex
        (profile / "uncaught.armed").write_text(request_id)
        received = {}
        def send():
            try:
                received["response"] = client.request("action.execute", {"action": "wait"}, request_id=request_id)
            except Exception as error:
                received["pipe_error"] = type(error).__name__
        worker = threading.Thread(target=send, daemon=True)
        worker.start()
        client.process.wait(timeout=45)
        worker.join(timeout=5)
        assert not worker.is_alive(), received
        assert client.process.returncode == 1, {"fatal_session_exit_code": client.process.returncode, "profile": str(profile)}
        marker = (profile / "uncaught.reached").read_text()
        assert "Actor.process" in marker, marker
        assert "Actor" in marker.splitlines()[0], marker
        remainder = client.buffer + client.process.stdout.read()
        extra = [json.loads(line) for line in remainder.splitlines() if line.strip()]
        wire_count = int("response" in received) + len(extra)
        assert wire_count <= 1, {"received": received, "extra": extra}
        assert "PRIVATE_UNCAUGHT_ACTOR_FIXTURE" not in json.dumps(received)
        paired = []
        for side in ["public", "internal"]:
            with sqlite3.connect((profile / "audit" / (side + ".sqlite3")).as_uri() + "?mode=ro", uri=True) as db:
                assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
                session = db.execute("SELECT session_id,status,end_reason FROM sessions ORDER BY started_at DESC LIMIT 1").fetchone()
                assert session[1] == "FAILED", {"side": side, "session": session}
                paired.append(session)
                exchanges = db.execute("SELECT COUNT(*),SUM(response_json IS NOT NULL) FROM exchanges WHERE scope_id=? AND id=?", (scope, request_id)).fetchone()
                assert exchanges[0] == 1 and exchanges[1] <= 1, exchanges
                if side == "internal":
                    rows = db.execute("SELECT exception_class,message,stack_trace,session_id FROM exceptions WHERE message='PRIVATE_UNCAUGHT_ACTOR_FIXTURE'").fetchall()
                    assert rows and all(r[0] == "java.lang.RuntimeException" and "Actor.process" in r[2] and r[3] == session[0] for r in rows), rows
                else:
                    assert db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name='exceptions'").fetchone()[0] == 0
                    for table, column in [("exchanges", "response_json"), ("requests", "response_json"), ("events", "data_json")]:
                        assert db.execute("SELECT COUNT(*) FROM " + table + " WHERE " + column + " LIKE '%PRIVATE_UNCAUGHT_ACTOR_FIXTURE%'").fetchone()[0] == 0
        assert paired[0] == paired[1], paired
        report = {"verified": True, "test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id,
                  "profile": str(profile.relative_to(root)), "session_status": paired[0][1], "end_reason": paired[0][2],
                  "process_exit_code": client.process.returncode, "target_wire_responses": wire_count,
                  "original_exception_internal_only": True, "scope_id": scope, "request_id": request_id,
                  "injected_stack": marker.splitlines()}
        write_json(profile / "uncaught-runtime-result.json", report)
        print(json.dumps(report, ensure_ascii=False))
    finally:
        if client.process.poll() is None:
            client.process.terminate(); client.process.wait(timeout=10)
        client.stderr.close(); client.trace.close()


if __name__ == "__main__":
    main()
