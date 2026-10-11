"""Build/run the in-memory world audit using an existing classpath; no Gradle or game launch."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[4]
cp_file = root / 'game-control/build/combat-visual/source-classpath.txt'
cp = cp_file.read_text().strip()
out = root / 'game-control/build/research-cli-world-audit'
out.mkdir(parents=True, exist_ok=True)
source = Path(__file__).with_name('WorldObservationProbe.java')
subprocess.run(['javac', '-cp', cp, '-d', str(out), str(source)], check=True)
p = subprocess.run(['java', '-cp', str(out) + ':' + cp,
                    'com.shatteredpixel.shatteredpixeldungeon.control.game.WorldObservationProbe', str(root)],
                   text=True, capture_output=True)
Path(__file__).with_name('result.txt').write_text(p.stdout + p.stderr)
print(p.stdout + p.stderr)
p.check_returncode()
