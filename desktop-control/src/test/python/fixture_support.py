"""Setup and post-execution assertions for disposable current-protocol fixtures."""
from contextlib import closing
import gzip
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import uuid


def artifact_directory(profile):
    """Keep caller-created evidence outside a profile before launcher acceptance."""
    profile = Path(profile)
    directory = profile.with_name(profile.name + ".artifacts")
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def safe_profile(root, name):
    directory = root / "desktop-control/build/fixtures" / (name + "-" + uuid.uuid4().hex)
    directory.mkdir(parents=True)
    assert directory.resolve().is_relative_to((root / "desktop-control/build/fixtures").resolve())
    return directory.resolve()


def metadata(profile, fixture, runtime_id, **extra):
    write_json(artifact_directory(profile) / "test_fixture.json", dict(test_fixture=True, counts_as_win=False,
               fixture=fixture, runtime_id=runtime_id, **extra))


def launch_command(classpath, fixture=False):
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    command = [java, "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath]
    return command + (["com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher",
                       "--fixture", "class:WARRIOR"] if fixture else
                      ["com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"])


def stop(client, uncertain=False):
    """Use normal app.quit when possible; EOF is the protocol's shutdown path after UNKNOWN."""
    try:
        if client.process.poll() is None:
            if uncertain:
                client.process.stdin.close()
                client.process.wait(timeout=40)
            else:
                client.finish()
    finally:
        if client.process.poll() is None:
            client.process.terminate()
            client.process.wait(timeout=10)
        client.stderr.close()
        client.trace.close()


def save_file(profile):
    files = list(profile.glob("game*/game.dat"))
    assert len(files) == 1, {"expected_one_test_save": [str(p) for p in files]}
    return files[0]


def save_json(path):
    encoded = path.read_bytes()
    assert encoded[:2] == b"\x1f\x8b", "Fixture game save must use the current gzip JSON format"
    return json.loads(gzip.decompress(encoded))


def audit_schema(profile):
    audit = profile / "audit"
    if audit.is_symlink() or not audit.is_dir():
        raise ValueError("Fixture audit pair must be a local directory")
    for name in ("public", "internal"):
        database = audit / (name + ".sqlite3")
        if database.is_symlink() or not database.is_file():
            raise ValueError("Fixture audit pair must contain both regular database files")
        with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as db:
            assert db.execute("SELECT value FROM metadata WHERE key='schema_version'").fetchone() == ("12",)
            metadata = dict(db.execute("SELECT key,value FROM metadata WHERE key IN ('game_version','game_version_code')"))
            assert metadata == {"game_version": "4.0.2", "game_version_code": "922"}, metadata
            assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)


def copy_audited_profile(template, profile):
    """Copy a closed isolated fixture's current save and complete audit identity pair."""
    fixture_root = Path(__file__).resolve().parents[4] / "desktop-control/build/fixtures"
    for candidate in (template, profile):
        candidate = Path(candidate)
        if (candidate.is_symlink() or candidate.resolve() == fixture_root.resolve()
                or not candidate.resolve().is_relative_to(fixture_root.resolve())):
            raise ValueError("Profile copying is restricted to isolated fixture directories")
    audit_schema(template)
    for source in template.iterdir():
        if source.is_symlink():
            raise ValueError("Fixture profile copy does not accept symbolic links")
        if source.is_dir() and (source.name == "audit" or source.name.startswith("game")):
            if any(entry.is_symlink() for entry in source.rglob("*")):
                raise ValueError("Fixture profile copy does not accept nested symbolic links")
            shutil.copytree(source, profile / source.name)
        elif source.is_file() and source.name in {"settings.xml", "badges.dat", "journal.dat", "rankings.dat"}:
            shutil.copy2(source, profile / source.name)
    audit_schema(profile)


def initialize_current_profile(command, profile):
    """Initialize a new disposable profile before a production-launch fixture edits preferences."""
    fixture_root = Path(__file__).resolve().parents[4] / "desktop-control/build/fixtures"
    profile = Path(profile)
    if (profile.is_symlink() or profile.resolve() == fixture_root.resolve()
            or not profile.resolve().is_relative_to(fixture_root.resolve())):
        raise ValueError("Profile initialization is restricted to isolated fixture directories")
    profile.mkdir(parents=True, exist_ok=True)
    if any(profile.iterdir()):
        raise ValueError("Current profile initialization requires an empty fixture directory")
    request_id = "fixture-bootstrap-" + uuid.uuid4().hex
    frame = json.dumps({"v": 9, "id": request_id, "op": "info"}).encode() + b"\n"
    result = subprocess.run(command + ["run", "--machine", "--data-dir", str(profile)],
                            input=frame, capture_output=True, timeout=45)
    artifacts = artifact_directory(profile)
    (artifacts / "bootstrap-stdout.ndjson").write_bytes(result.stdout)
    (artifacts / "bootstrap-stderr.log").write_bytes(result.stderr)
    assert result.returncode == 0, {"bootstrap_exit": result.returncode, "artifacts": str(artifacts)}
    replies = [json.loads(line) for line in result.stdout.splitlines()]
    assert len(replies) == 1 and replies[0].get("v") == 9 and replies[0].get("id") == request_id, replies
    assert replies[0].get("st") == "completed" and "err" not in replies[0], replies
    audit_schema(profile)
    for side in ("public", "internal"):
        with closing(sqlite3.connect((profile / "audit" / (side + ".sqlite3")).as_uri() + "?mode=ro", uri=True)) as db:
            sessions = db.execute("SELECT status FROM sessions").fetchall()
            assert sessions == [("CLOSED",)], sessions
