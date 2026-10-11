#!/usr/bin/env python3
"""Compile/run only isolated native-widget research fixture, no game or profile."""
from pathlib import Path
import hashlib
import subprocess
import sys
ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
CP_FILE = ROOT / "desktop-control/build/test-runtime-classpath.txt"
OUT = ROOT / "build/research-cli-ui-fixtures"

def main():
    cp = str(ROOT / "game-control/build/classes/java/test") + ":" + CP_FILE.read_text().strip()
    print("SOURCE_HEAD="+subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip(),flush=True)
    print("SCOPE=isolated existing native widget capture with synthetic source-selected states; no GL draw or live game",flush=True)
    for rel in ["core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndJournal.java", "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/StyledButton.java", "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ScrollingGridPane.java", "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/QuickRecipe.java", "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ItemSlot.java", "game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java", "game-control/build/libs/game-control-4.0.2.jar", "core/build/libs/core-4.0.2.jar"]:
        print("SHA256 "+hashlib.sha256((ROOT/rel).read_bytes()).hexdigest()+" "+rel,flush=True)
    OUT.mkdir(parents=True,exist_ok=True)
    subprocess.run(["javac","-cp",cp,"-d",str(OUT),str(HERE/"UiAlchemyAuditProbe.java")],check=True)
    return subprocess.run(["java","-cp",str(OUT)+":"+cp,"com.shatteredpixel.shatteredpixeldungeon.control.game.UiAlchemyAuditProbe"],cwd=ROOT/"game-control").returncode
if __name__=="__main__":sys.exit(main())
