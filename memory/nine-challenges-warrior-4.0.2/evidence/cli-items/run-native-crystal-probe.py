"""Compile the adjacent native fixture into a temporary directory and decode its output."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.path.insert(0, str(REPO / "desktop-control/client"))
from spdctl_client import DecodeError, decode_wire_response  # noqa: E402

classpath = [str(REPO / path) for path in (
    "game-control/build/classes/java/test", "game-control/build/resources/test",
    "game-control/build/classes/java/main", "core/build/classes/java/main",
    "SPD-classes/build/classes/java/main", "control-protocol/build/classes/java/main")]
classpath += (REPO / "desktop-control/build/test-runtime-classpath.txt").read_text().strip().split(":")
with tempfile.TemporaryDirectory(prefix="spd-native-crystal-fixture-", dir=HERE) as compiled:
    cp = ":".join(classpath)
    command = ["javac", "-cp", cp, "-d", compiled, str(HERE / "NativeCrystalGlowProbe.java")]
    compilation = subprocess.run(command, cwd=REPO / "game-control", text=True, capture_output=True)
    (HERE / "native-crystal-compile.txt").write_text(compilation.stdout + compilation.stderr)
    if compilation.returncode:
        raise SystemExit("Native fixture compilation failed; see native-crystal-compile.txt")
    execution = subprocess.run(["java", "-cp", compiled + ":" + cp,
                                "com.shatteredpixel.shatteredpixeldungeon.control.game.NativeCrystalGlowProbe"],
                               cwd=REPO / "game-control", text=True, capture_output=True)
    (HERE / "native-crystal-stderr.txt").write_text(execution.stderr)
    if execution.returncode:
        raise SystemExit("Native fixture execution failed; see native-crystal-stderr.txt")
    native = json.loads(execution.stdout)

native["baseline"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
native["fixture_sha256"] = hashlib.sha256((HERE / "NativeCrystalGlowProbe.java").read_bytes()).hexdigest()
results = []
for fixture in native["cases"]:
    for response in fixture["responses"]:
        wire = response["wire"]
        before = copy.deepcopy(wire)
        result = {"stage": fixture["stage"], "view": response["view"], "wire_id": wire["id"],
                  "expected": "accepted_with_native_crystal_glow_preserved"}
        try:
            decoded = decode_wire_response(wire)
            glow = decoded.data["inv"][0]["shown"]["glow"]
            result.update(actual="accepted", native_fact_preserved=glow == fixture["native_glow"],
                          raw_preserved=decoded.raw == before)
        except DecodeError as error:
            result.update(actual="DecodeError", error=str(error), raw_preserved=error.raw == before)
        result["input_unchanged"] = wire == before
        results.append(result)
native["decoder_results"] = results
native["summary"] = {"native_stages": len(native["cases"]), "wire_responses": len(results),
                     "unexpected_rejections": sum(r["actual"] == "DecodeError" for r in results),
                     "input_mutations": sum(not r["input_unchanged"] for r in results),
                     "raw_preservation_failures": sum(not r["raw_preserved"] for r in results)}
(HERE / "native-crystal-probe.json").write_text(json.dumps(native, indent=2, ensure_ascii=True) + "\n")
print(json.dumps(native["summary"], sort_keys=True))
