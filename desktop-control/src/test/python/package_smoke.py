#!/usr/bin/env python3
"""Validate the actual ARM64 bundle and raw pipes in an isolated Unicode profile."""
import argparse
import json
import os
from pathlib import Path
import select
import shutil
import sqlite3
import subprocess
import time
import uuid


def receive(process, timeout=40):
    # This test deliberately sends only one frame at a time.
    data = bytearray()
    deadline = time.monotonic() + timeout
    while not data.endswith(b"\n"):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("packaged protocol response")
        if select.select([process.stdout], [], [], min(remaining, 1))[0]:
            chunk = os.read(process.stdout.fileno(), 1)
            if not chunk:
                raise AssertionError(("unexpected EOF", process.poll(), bytes(data)))
            data.extend(chunk)
    return json.loads(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    output = root / "desktop-control/build/package-check" / uuid.uuid4().hex
    output.mkdir(parents=True)
    bundle = output / "中文 应用目录" / args.bundle.name
    shutil.copytree(args.bundle, bundle, symlinks=True)
    profile = output / "中文 玩家目录 with spaces"
    profile.mkdir()
    cli = bundle / "Contents/MacOS/spdctl"
    checks = {}
    for executable in (cli, cli.with_name("spdctl-jvm"), cli.with_name("Shattered Pixel Dungeon"),
                       bundle / "Contents/runtime/Contents/Home/lib/server/libjvm.dylib"):
        description = subprocess.check_output(["/usr/bin/file", str(executable)], text=True).strip()
        assert "arm64" in description, description
        checks[executable.relative_to(bundle).as_posix()] = description.split(": ", 1)[1]
    subprocess.run(["/usr/bin/codesign", "--verify", "--deep", "--strict", str(bundle)], check=True)
    subprocess.run(["/usr/bin/plutil", "-lint", str(bundle / "Contents/Info.plist")], check=True,
                   stdout=subprocess.DEVNULL)
    env = dict(os.environ)
    env.pop("JAVA_HOME", None)
    # No external Java/SQLite/Python executable is needed by the bundled app.
    env["PATH"] = "/usr/bin:/bin"
    command = [str(cli), "run", "--machine", "--data-dir", str(profile)]
    stderr = (output / "native-stderr.log").open("wb")
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, env=env)
    frames = []
    try:
        def exchange(frame):
            frames.append(frame)
            process.stdin.write(frame)
            process.stdin.flush()
            return receive(process)
        first = exchange(b'{"id":"package-info","op":"protocol.info"}\r\n')
        assert first["ok"], first
        scope = first["result"]["scope_id"]
        invalid = exchange(b'{"id":"invalid-encoding","op":"state.get","x":"\xff"}\n')
        assert invalid["error"]["code"] == "INVALID_ENCODING", invalid
        query = json.dumps({"id": "中文-query", "scope_id": scope, "op": "state.get"}, ensure_ascii=False).encode() + b"\n"
        observed = exchange(query)
        assert observed["ok"], observed
        duplicate = exchange(query)
        assert duplicate["error"]["code"] == "DUPLICATE_REQUEST_ID", duplicate
        conflict = subprocess.run(command, input=b"", capture_output=True, env=env, timeout=20)
        assert conflict.returncode != 0 and conflict.stdout == b"", conflict
        assert b"STARTUP_FAILED" in conflict.stderr, conflict.stderr
        # EOF itself is lifecycle input. It must not create an unsolicited response.
        process.stdin.close()
        process.wait(timeout=25)
        assert process.returncode == 0, process.returncode
        assert process.stdout.read() == b"", "unsolicited output after EOF"
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        stderr.close()
    final_frame=json.dumps({"id":"package-eof-frame","scope_id":scope,"op":"state.get"}).encode()
    restarted=subprocess.run(command,input=final_frame,capture_output=True,env=env,timeout=40)
    assert restarted.returncode==0,(restarted.returncode,restarted.stderr)
    delivered=restarted.stdout.splitlines()
    assert len(delivered)==1 and json.loads(delivered[0])["ok"],delivered
    frames.append(final_frame)
    with sqlite3.connect(profile / "audit/public.sqlite3") as db:
        wire = db.execute("SELECT raw_bytes,raw_format FROM exchanges ORDER BY sequence").fetchall()
        assert [row[0] for row in wire] == frames, [(len(row[0]), row[1]) for row in wire]
        assert [row[1] for row in wire] == ["utf8-lf", "invalid-utf8", "utf8-lf", "utf8-lf", "utf8-eof"], wire
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        public_tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert "exceptions" not in public_tables and "logs" not in public_tables
    with sqlite3.connect(profile / "audit/internal.sqlite3") as db:
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        runtime = json.loads(db.execute("SELECT text FROM logs WHERE channel='runtime.environment' ORDER BY sequence LIMIT 1").fetchone()[0])
        assert runtime["os_arch"] == "aarch64"
        assert runtime["error_file"] == str(profile / "audit/emergency/hs_err_pid%p.log"), runtime
        assert runtime["profile"] == str(profile)
        recovered=db.execute("SELECT count(*) FROM logs WHERE channel='recovered_emergency_base64'").fetchone()[0]
        assert recovered>=1,"Failed competing launch diagnostics should be imported on restart"
    result = {"result": "passed", "bundle": str(bundle), "profile": str(profile), "architecture": checks,
              "runtime": runtime, "frames": len(frames), "checks": ["unicode_bundle_and_profile", "bundled_jvm",
              "native_sqlite", "exact_wire_bytes", "invalid_utf8_recovery", "duplicate_query", "profile_lock",
              "EOF_without_push", "final_frame_without_newline", "emergency_import", "codesign", "plist", "database_integrity", "profile_jvm_crash_path"],
              "not_tested": ["forced_JVM_native_crash", "real_Intel_hardware", "Gatekeeper_notarization"]}
    (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
