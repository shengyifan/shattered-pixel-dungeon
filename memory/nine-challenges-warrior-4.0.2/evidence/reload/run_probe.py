#!/usr/bin/env python3
"""Run isolated native Bundle research fixtures. No game/profile/audit access.

Uses an existing build classpath, compiles only this fixture, and writes outputs
under the ignored repository build/ directory. Read docs/cli-help.md and refresh
the normal build separately if testing a different source revision.
"""
from pathlib import Path
import hashlib
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[4]
CP_FILE = ROOT / "desktop-control/build/test-runtime-classpath.txt"
HERE = Path(__file__).resolve().parent
OUT = ROOT / "build/research-reload-fixtures"
SOURCES = [
    "actors/Actor.java", "actors/Char.java", "actors/mobs/Mob.java",
    "actors/hero/abilities/Ratmogrify.java", "items/weapon/missiles/HeavyBoomerang.java",
    "actors/mobs/DM201.java", "actors/mobs/GreatCrab.java", "actors/mobs/MobSpawner.java",
    "actors/mobs/Goo.java", "actors/mobs/GnollTrickster.java", "actors/mobs/Eye.java",
    "actors/mobs/DM300.java", "actors/mobs/Tengu.java", "actors/buffs/ChampionEnemy.java",
    "items/BrokenSeal.java", "actors/buffs/Berserk.java", "actors/buffs/Corruption.java",
    "Dungeon.java", "levels/Level.java", "windows/WndGame.java", "scenes/InterlevelScene.java",
]

def main():
    if not CP_FILE.is_file():
        raise SystemExit("Missing existing desktop-control/build/test-runtime-classpath.txt; prepare the project's normal build first.")
    cp = CP_FILE.read_text().strip()
    print("SOURCE_HEAD=" + subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(), flush=True)
    print("EVIDENCE_SCOPE=synthetic in-memory Bundle serialization and Corruption.act arithmetic, not a live playthrough", flush=True)
    for rel in SOURCES:
        file = ROOT / "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon" / rel
        print("SOURCE_SHA256 " + hashlib.sha256(file.read_bytes()).hexdigest() + " " + str(file.relative_to(ROOT)), flush=True)
    for name in ("core", "SPD-classes"):
        jar = ROOT / name / "build/libs" / (name + "-4.0.2.jar")
        if jar.is_file():
            print("RUNTIME_SHA256 " + hashlib.sha256(jar.read_bytes()).hexdigest() + " " + str(jar.relative_to(ROOT)), flush=True)
    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "-cp", cp, "-d", str(OUT), str(HERE / "ReloadSerializationProbe.java")], check=True, cwd=ROOT)
    return subprocess.run(["java", "-cp", str(OUT) + ":" + cp, "ReloadSerializationProbe"], cwd=ROOT).returncode

if __name__ == "__main__":
    sys.exit(main())
