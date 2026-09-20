# CLI.7.0.0 macOS clean rebuild — 2026-09-21

This batch updates protocol documentation, removes previous project-generated
artifacts and runtime issue JSON from the workspace, rebuilds the current macOS
ARM64 CLI, and pushes the verified branch. Source baseline: `18816c6bd` on
`feature/mac-cli`. CLI.7.0.0, protocol 7, schema 10 and game 3.3.8 stay unchanged;
there is no gameplay or runtime protocol modification in this batch.

## Documentation

The authoritative help and `AGENTS.md` now explicitly restore activity/cancel
bindings after action/inventory templates and before copying referenced node
operations. The detailed protocol documentation enumerates strict template types,
index validation, independent snapshots and opaque source/history boundaries.
The client guide distinguishes a failing state query's identity from the original
action identity retained in the controller context. Current entrypoints no longer
present CLI 5/6 validation as the latest package evidence. Historical results remain
dated facts; removed generated attachments are not represented as existing files.
The repository uses `AGENTS.md`; no duplicate `AGENT.md` is introduced.

## Previous artifacts removed from the workspace

All targets were checked before moving: project-contained, Git-ignored, no tracked
files, target itself not a symlink. There were no running spdctl/game/Gradle processes
using these outputs. Internal symlinks were moved as links, not traversed.

Fourteen targets were moved into a dedicated recoverable Trash directory:

| Target | Regular files | Logical bytes |
| --- | ---: | ---: |
| `.gradle` | 14 | 9,619,199 |
| `SPD-classes/build` | 119 | 511,335 |
| `build` | 1 | 143,693 |
| `control-protocol/build` | 35 | 129,652 |
| `core/build` | 2,707 | 14,800,525 |
| `desktop-control/build` | 5,115 | 1,131,887,910 |
| `desktop-control/client/__pycache__` | 1 | 45,577 |
| `desktop-control/src/test/python/__pycache__` | 6 | 209,092 |
| `desktop/build` | 471 | 70,655,855 |
| `game-control/build` | 298 | 3,001,981 |
| `ios/robovm.properties` | 1 | 349 |
| `services/build` | 10 | 10,757 |
| `services/news/shatteredNews/build` | 6 | 21,757 |
| `services/updates/githubUpdates/build` | 6 | 22,768 |
| **Total** | **8,790** | **1,231,060,450** |

The targets also contain 66 symlinks and **365 JSON/JSONL/NDJSON files**, including
old test/issue evidence, replay outputs and build metadata. This is not a claim
that all 365 are fault records. `docs/cli-issues` contains no remaining standalone
issue JSON. The six tracked JSON files outside build are application resources or
static test inputs and remain intact.

Recovery location: `/Users/shengyifan/.Trash/spdctl-cli7-clean-20260921-LC3ojL/`,
with numbered names preserving each source path. Trash was not emptied; logical
sizes above are not a measurement of freed disk space. No old generated output is
available at its former workspace path for the rebuild to reuse. Personal
Application Support profiles, external raw transport logs, global Gradle dependency
caches, historical Markdown, source assets and the pre-existing stash are untouched.

## Fresh build and verification

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

The clean Gradle gate completed in **59 seconds**, with **34 tasks executed** and
no old project output or task build cache reused. Existing global dependency caches
were retained for the offline build.

| Test module | Tests passed | Failures/errors/skips |
| --- | ---: | ---: |
| control-protocol | 18 | 0 |
| game-control | 348 | 0 |
| desktop-control | 192 | 0 |
| **Java total** | **558** | **0** |
| Python discovery | **246** | **0** |

After the final documentation edits, the eight CLI documentation tests were also
rerun directly against the freshly compiled test runtime and passed. They are a
repeat subset of the Java total, not eight additional distinct tests.

The actual executable reports `CLI.7.0.0 (protocol 7, game 3.3.8)`.
Source help, the package's unique help resource and actual stdout match exactly:
**45,710 bytes**, SHA-256
`5060e8a9eddd634dd49f3046987ba83fa5ced878cc68551fdee26629f249a301`.

The new build ID is:

```text
88e2972f0d3ca771235273cb6250986ca2b3826c3407fa2beeedcb916c91afd6
```

All **3,315 build-catalog entries** match current files and hashes; re-encoding the
catalog entries reproduces the build ID. All **2,850 production class files** match
their packaged bytes, **228 test classes** are absent from the application JAR,
and the packaged JAR equals the new release JAR. All **34 Mach-O files** are ARM64.
Deep strict codesign verification and plist validation pass. This is local signing,
not Developer ID notarization, Gatekeeper acceptance or Intel-hardware validation.

Actual-package checks used only new, explicitly isolated profiles:

- Controller sessions `t1/t2`, **24 + 7 wire requests**, a new Warrior on D1,
  inventory/item-window inspection, play/full/source views, four independent save
  receipts, normal quit and matching saved restart. Old revision rejection occurs
  locally before sending to the child. Inspection preserves hero/inventory resources.
- Viewer plain text and raw recording bytes remain unchanged; only numbered
  SEND/RECV headings are bold, with the same bright-cyan foreground.
- A Unicode/spaced bundle/profile copy passes five raw frames, invalid UTF-8,
  duplicate identity, profile-lock contention, EOF, final frame without LF,
  bundled JVM/SQLite and isolated audit integrity checks.
- The actual launcher rejects all nine schemas 1–9 before profile writes,
  preserving file content, directory entries and timestamps.

Existing compiler deprecation/unchecked warnings and the intermittent Python
SQLite fixture ResourceWarning remain visible in logs; no failures are hidden.
These are fixture results, not a complete playthrough. The short controller run
did not produce continuous activity; interruption/settle behavior remains covered
by regression fixtures, not new live-travel evidence. Actual transport-loss injection
and independent Terminal-window UI interaction were not performed.

The earlier corpus token measurements were not rerun using removed fixture inputs
and are not counted as new validation. This batch changes help content and therefore
the build identity, but not CLI/protocol/schema versions or runtime behavior.

## Git and retained artifacts

The retained official application is
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`, alongside the
new frozen development runtime and current compilation/test outputs. Logs, cleanup
manifest, package verification and copied acceptance summaries are retained under
`desktop-control/build/rebuild-7.0.0-20260921/`.

After copying those summaries, the newly created `desktop-control/build/fixtures`
(382 regular files, 163,826,112 logical bytes, 41 structured data files) and the
regenerated iOS properties file (349 bytes) were moved to numbered entries 15/16 in
the same Trash directory. Thus extra test application copies and disposable profiles
do not remain in the working tree. Paths inside the copied summaries identify the
test inputs at execution time, not currently active profiles. Both cleanup stages
are recoverable until Trash is emptied.

The existing implementation commit `18816c6bd` and this documentation/cleanup batch
are included in the requested normal, non-force push to `origin/feature/mac-cli`.
The documentation batch is locally GPG-signed after staged-diff validation; remote
SHA and ahead/behind are checked after pushing. The pre-existing stash remains
`525386260635ceefc31c633be7772f9698e23d07`, unapplied and untouched. No tag, GitHub
release, notarization or application-attachment publication is included.
