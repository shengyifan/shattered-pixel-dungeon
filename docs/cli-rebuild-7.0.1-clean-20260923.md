# CLI.7.0.1 clean macOS rebuild and push — 2026-09-23

This user-requested batch removes previous project-generated artifacts and runtime
issue JSON, rebuilds the current macOS ARM64 CLI, and pushes the verified branch.
Source baseline is `934be538a5c11a7158953fa6f8297f80cf198140` on `feature/mac-cli`.
CLI.7.0.1, protocol 7, schema 10 and game 3.3.8 remain unchanged. No runtime or
protocol implementation changes are part of this cleanup/documentation batch.

## Cleanup

All targets were checked before moving: project-contained, Git-ignored, no tracked
files, target itself not a symlink, and no running spdctl/game/Gradle processes.
Internal symlinks were moved as links rather than traversed.

| Removed target | Regular files | Logical bytes | JSON/JSONL/NDJSON files |
| --- | ---: | ---: | ---: |
| `.gradle` | 14 | 17,954,290 | 0 |
| `build` | 175 | 1,003,508 | 6 |
| `core/build` | 5,323 | 24,518,463 | 0 |
| `SPD-classes/build` | 202 | 777,372 | 0 |
| `control-protocol/build` | 43 | 199,507 | 1 |
| `desktop/build` | 475 | 70,677,062 | 1 |
| `desktop-control/build` | 16,249 | 2,990,486,692 | 1561 |
| `desktop-control/client/__pycache__` | 1 | 83,021 | 0 |
| `desktop-control/src/test/python/__pycache__` | 25 | 588,817 | 0 |
| `services/build` | 10 | 10,757 | 0 |
| `services/updates/githubUpdates/build` | 8 | 31,363 | 0 |
| `services/news/shatteredNews/build` | 8 | 29,781 | 0 |
| `game-control/build` | 492 | 127,426,673 | 51 |
| `game-control/src/test/python/__pycache__` | 1 | 7,347 | 0 |
| `ios/robovm.properties` | 1 | 349 | 0 |

Totals: **23,027 regular files, 3,233,795,002 logical bytes, 108 symlinks and
1,620 structured data files**. These structured files include old runtime issue
records, test profiles, traces/reports, inventories and build metadata; they are
not all failure reports. All prior fixture runtimes and application copies under
these build directories were removed from their old workspace paths.

Recovery directory: `/Users/shengyifan/.Trash/spdctl-cli7_0_1-clean-20260923-3r7u6cya/`.
The cleanup manifest records each original path and numbered destination. Trash
was not emptied, so the byte count above is not claimed as freed disk space.

The eight tracked JSON files are maintained test/review ledgers and application
assets; all remain intact. There was no additional runtime JSON/JSONL/NDJSON
outside these generated directories, and `docs/cli-issues` contained only Markdown.
Personal Application Support profiles, personal saves, external transport logs,
global dependency caches, source assets and the existing stash were not modified.

The preceding [implementation acceptance report](cli-rebuild-7.0.1-20260923.md)
retains its dated conclusions. Its old generated attachment paths were removed by
this cleanup and are not presented as current package evidence.

## Fresh validation

The following offline gate completed in **1 minute 10 seconds**, with **38 tasks
executed** and no prior project task output/build-cache reuse:

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath :desktop-control:crashAgentJar \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

| Fresh regression suite | Passed | Failures/errors/skips |
| --- | ---: | ---: |
| control-protocol | 19 | 0 |
| game-control | 442 | 0 |
| desktop-control | 216 | 0 |
| **Java total** | **677** | **0** |
| Python full discovery | 273 | 0 |

The combat source gate independently reattributes 1,281 source files, 13,024 sites
and 4,617 reviewed groups; there are no unknown, removed, changed or outstanding
entries. Text composition checks cover 1,197 source files, 212 operations and 769
catalog constructors, without unknown or stale exclusions. Existing compiler
warnings and the intermittent Python SQLite fixture ResourceWarning remain in
the logs, not suppressed as successes. The eight documentation tests were rerun
after final report edits; they are a repeat subset of the Java count.

### Actual package

The actual executable reports `CLI.7.0.1 (protocol 7, game 3.3.8)`.
Authoritative source help, the unique package JAR resource and actual `--help`
stdout match exactly: **49,657 bytes**, SHA-256
`08a3adb7f28df2337848c3a42e31955e5541236057b8777503ad11704ad8bfa2`.

Build ID:

```text
a6c3a5b12548e38bd7626a7dfc7a0dd09b023caf00cd4e210ca6a717fd597749
```

Release JAR SHA-256:

```text
900b226fa01752dd9102c11df867f1441fa0828e14c2f6ff0dc462d8ad52f335
```

The clean compile reproduces the implementation build identity and release-JAR
bytes. All 3,341 catalog entries match current file sizes/hashes and reproduce the
build ID. All 2,876 catalog production classes and 13 runtime service classes
match their packaged bytes, while 322 compiled test/crash-agent classes are absent.
The package JAR equals the newly produced release JAR. All **35 Mach-O files are
ARM64**; deep strict codesign and plist validation pass. Bundled Java is 25.0.4,
including standard instrumentation support for the external test-only agent.
Signing is local ad-hoc, not Developer ID notarization or Gatekeeper certification.

[Fresh package verification](../build/rebuild-7.0.1-clean-20260923/package-verification/summary.json)
contains the detailed file, architecture and signature evidence.

### Isolated actual-package runs

- Ordinary controller sessions `t1/t2`: **22 + 7 actual wire requests**, four
  successful save receipts, native inventory/item inspection, normal quit and
  matching saved restart. Old revision rejection occurs locally before sending;
  no state query follows successful quit. Plain viewer output and exact raw bytes
  remain unchanged, with bold limited to SEND/RECV headings.
- Slow quit: the external test-only agent holds the actual native quit callback
  beyond its production 30-second deadline. **13 wire requests, one initial
  in_progress, two settle calls**, `EXECUTING` while held, then `COMPLETED` for
  original `s2/t1.7`, successful save `p2` and exit 0. There is no unsolicited second
  response or state query after quit. This is explicitly instrumented component
  integration; no save/game-state injection or production delay switch is used.
- A Unicode/spaced bundle/profile copy passes five exact raw frames, invalid
  UTF-8, duplicate identity, lock contention, EOF without unsolicited output,
  final frame without LF, bundled JVM/SQLite and isolated paired-audit integrity.
- The actual launcher refuses all nine earlier schemas 1–9 before profile writes,
  preserving fixture bytes, directory entries and timestamps.

All started game processes exited normally. The view-equivalence component check
replays one captured full/src frame through the exact package JAR using a host-JDK
test adapter, preserving colors, timestamps, source trees and diagnostics. It is
separate from live transport and is not installed as a new live observation.

The earlier **64 native battle/render scenarios were not rerun in this
cleanup-only batch**. Their prior acceptance remains dated evidence; the new
build has identical production catalog/JAR identity. This batch does not claim
fresh full-game, every-Boss or every-class gameplay coverage.

## Retained output and final fixture cleanup

The official application remains at
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`, with fresh
compilation/test outputs and the new frozen development runtime. Current logs,
cleanup manifests, four package-smoke summaries and **70 exact transport/viewer
files** are retained under `desktop-control/build/rebuild-7.0.1-clean-20260923/`.
The separate package verifier output is under
`build/rebuild-7.0.1-clean-20260923/package-verification/`.

After copying those current summaries and transport files, the new temporary
fixture profiles/application copy, generated iOS properties and Python bytecode
caches were moved into entries 16–19 of the same recovery directory. This second
stage contains **442 regular files, 169,415,351 logical bytes, 36 symlinks and 50
structured data files**. The extra test application copy and disposable profiles
therefore do not remain in the working tree. Paths inside copied summaries name
the original inputs at execution time, not active current profiles. Source JSON
and the current official package remain intact.

## Delivery

The requested normal, non-force push to `origin/feature/mac-cli` includes the
existing signed implementation commit `934be538a` and this scoped documentation/
cleanup record. The record is committed with GPG after staged-diff validation.
Delivery is verified by comparing remote/local HEAD and the ahead/behind counts;
commit/signature and remote evidence are saved with the current build report and
reported at handoff. No tag, release or notarized publication is included.
