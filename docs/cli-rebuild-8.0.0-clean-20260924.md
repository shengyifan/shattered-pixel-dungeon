# CLI.8.0.0 clean macOS rebuild and push — 2026-09-24

This user-requested documentation and cleanup batch starts from
`828c7042effecd41700888fbd40a8483fc0bac68` on `feature/mac-cli`.
CLI.8.0.0, protocol 8, audit schema 11 and game 3.3.8 remain unchanged.
No runtime implementation changes are included.

## Current documentation

The bundled English [help](cli-help.md), [AGENTS.md](../AGENTS.md),
[interface guide](cli.md) and [reference-client guide](cli-playthrough-client.md)
now spell out the decoding dependencies for templates, activity bindings, base
facts, operations, item labels and semantic references. Ground item references
can resolve through `entities[].item`; standalone `actions` owns its inline
`subject_data`. A textless node can carry its label in a resolved operation.

The guidance also distinguishes the complete known floor from live FOV visibility,
documents reviewed static terrain indicators' visited/mapped scope, and separates
`electricity_flow` particle motion from the stable hazard in `map.env`. Clients
continue to use the actual displayed revision rather than predicting one.
The project working-agreement filename is `AGENTS.md`; there is no duplicate
`AGENT.md`. Earlier implementation results and removed generated attachment paths
remain explicitly historical.

## Removal of prior generated data

Before cleanup, no spdctl/game/Gradle process was running. Each target was checked
as project-contained, Git-ignored, free of tracked files and not itself a symlink.
Whole roots were moved without following internal symlinks.

| Removed target | Regular files | Logical bytes | JSON/JSONL/NDJSON |
| --- | ---: | ---: | ---: |
| `.gradle` | 14 | 14,910,344 | 0 |
| `build` | 13 | 206,234 | 2 |
| `core/build` | 5,231 | 24,469,859 | 0 |
| `SPD-classes/build` | 126 | 550,915 | 0 |
| `control-protocol/build` | 46 | 212,936 | 1 |
| `desktop/build` | 473 | 70,669,979 | 1 |
| `desktop-control/build` | 12,064 | 2,320,552,428 | 1,005 |
| `services/build` | 10 | 10,757 | 0 |
| `services/updates/githubUpdates/build` | 6 | 23,069 | 0 |
| `services/news/shatteredNews/build` | 6 | 22,058 | 0 |
| `game-control/build` | 541 | 23,679,415 | 6 |
| `desktop-control/client/__pycache__` | 1 | 115,629 | 0 |
| `desktop-control/src/test/python/__pycache__` | 101 | 2,268,386 | 0 |
| `desktop-control/src/test/python/fixtures/__pycache__` | 1 | 16,837 | 0 |
| `game-control/src/test/python/__pycache__` | 2 | 31,508 | 0 |
| `ios/robovm.properties` | 1 | 349 | 0 |

Totals: **16 targets, 18,636 regular files, 2,457,740,703 logical bytes,
1,015 structured data files and 144 symlinks**. The structured files include
old runtime issue records, fixture profiles, traces, test reports, analysis outputs
and build metadata; they are not all failure reports. All old application copies,
frozen runtimes and generated analysis under these roots were removed from their
workspace paths before the new build.

Recovery directory:
`/Users/shengyifan/.Trash/spdctl-cli8_0_0-clean-20260924-hhkhfruk/`.
Its cleanup manifest records checked paths, quantities and numbered destinations.
Trash was not emptied, so logical byte counts are not a claim of freed disk space.

There were no additional runtime-issue JSON files outside the generated roots;
`docs/cli-issues` contained nine Markdown reports. The eight tracked JSON files are
source resources and maintained test/review ledgers, and their hashes were checked
unchanged during cleanup. Personal Application Support profiles, external raw
transport logs, global dependency caches and the tracked Gradle wrapper remain
outside the cleanup scope. The old [CLI8 acceptance report](cli8-implementation.md)
retains its historical conclusions after removal of its generated attachments.

## Fresh verification

The clean offline build uses the project dependencies already installed globally,
with all previous project outputs removed and build-cache/task-output reuse disabled:

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath :desktop-control:crashAgentJar \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

The final gate completed in **1 minute 9 seconds**, with **38 tasks executed**.
An initial documentation-link check ran before this new report file existed;
after creating the link target, the entire gate was rerun successfully. No
production code or test assertion was changed to obtain that result.

| Fresh regression suite | Passed | Failures/errors/skips |
| --- | ---: | ---: |
| control-protocol | 19 | 0 |
| game-control | 538 | 0 |
| desktop-control | 217 | 0 |
| **Java total** | **774** | **0** |
| Python full discovery | 286 | 0 |

Python discovery completed in 34.914 seconds. Existing compiler/deprecation
warnings and Python SQLite fixture ResourceWarnings remain visible in the logs.

The combat source gate rechecks **1,309 source files, 13,517 sites, 4,707 review
groups and 218 retired sites**, with no unknown, removed, changed or unresolved
entries. This is static review, not runtime verification. Text composition checks
cover 1,205 source files, 207 operations and 769 catalog constructors, with no
unknown or stale exclusions. All eight tracked source JSON hashes still match
the pre-cleanup values.

Fresh results are stored under
`desktop-control/build/rebuild-8.0.0-clean-20260924/`.

### Actual package

The executable reports `CLI.8.0.0 (protocol 8, game 3.3.8)`; its live handshake
also reports protocol 8 and audit schema 11. Source help, generated resource,
packaged resource and actual `--help` are byte-identical: **54,506 bytes**,
SHA-256 `3deb22b73ed3e15e139b44a20e9380f1230f6b72b72e28547e2e6fcdf57c643c`.

Build ID:

```text
5c59964f9c87dae9bf0bfe711e938ae06e63b4b40d62b433c04306377c721819
```

Release JAR SHA-256:

```text
371f4a8fda88b766ddf5dbea0c1398273bd1415c5af2c8e71933e1cf1f412e3c
```

The independent package verifier passes **84/84 checks**. All 3,357 build-catalog
entries match current lengths/hashes and reproduce the build ID. Compared with
the pre-cleanup catalog, only `docs/cli-help.md` differs. All **2,905 freshly
compiled project production classes** match their packaged bytes; all **5,487
packaged classes** match the complete freshly frozen runtime, with no missing,
extra or different classes. Test fixtures and crash agents are absent.

All **35 on-disk Mach-O files are ARM64**. The fat JAR also retains the upstream
dependencies' cross-platform native resources: 12 ARM64 and 12 x86_64 Mach-O
entries, separate from the launchable application/JVM architecture. Plist and
deep strict codesign validation pass. Signing is local ad-hoc, not Developer ID
notarization. The verifier and detailed results are retained in
`package-verification/` under this rebuild's output directory.

### Isolated actual-package execution

- Controller sessions `t1/t2`: **29 + 7 exact wire pairs**, four successful
  save/quit receipts, unchanged saved hero/inventory on restart, local stale
  revision rejection, and no state query after successful quit.
- Standalone `actions`: play/full each decode independently and compare 54 known
  hero/item facts across eight inline subjects. Native throw cancellation uses
  that actions reply's own revision and leaves hero/inventory unchanged.
- Full/src/play comparison uses one captured frame and the actual package codec;
  semantic facts, descriptions, operations and provenance remain intact.
- Unicode/spaced bundle/profile copy: **six exact raw frames**, explicit protocol-7
  refusal, malformed UTF-8, duplicate IDs, profile locking, EOF and a final frame
  without LF. Actual bundled JVM and SQLite JNI loading are verified.
- The original GUI remains Chinese and windowed. A displayed food event's English
  projection and original Chinese text match by the same event sequence/time.
  A second packaged JVM resumes the saved run and its public history.
- Slow quit uses an external test-only agent to delay the existing quit callback.
  **14 wire requests, one exposed initial `in_progress`, two settle calls**:
  the original receipt is `EXECUTING` while held, then `COMPLETED` with successful
  save receipt `p2`, followed by exit 0. There is no unsolicited second response
  or state query after quit. This is explicitly instrumented lifecycle evidence.
- Schema 1–10: all ten old-profile fixtures are refused before writes, with
  file bytes, paths and modification times unchanged.
- Passive viewer text/heading styles and exact raw bytes pass. Independent
  Terminal window UI was not manually retested.

All launched game processes exited. This documentation/rebuild batch does not
repeat the earlier 63 native battle/visual scenarios or claim a real playthrough;
their dated scope remains in the implementation report. The clean production
classes are identical to that accepted source baseline.

## Retained output and temporary fixture cleanup

The current official application, compilation/test outputs, frozen development
runtime and current verification report remain in the workspace. Four new package
smoke summaries plus **72 exact transport/viewer files** are retained under this
rebuild's report directory, with lengths and hashes in
`retained-validation-evidence.json`.

After preserving those current public results, the disposable fixture profiles,
extra test application copy and generated iOS properties were moved to entries
17–18 of the same recovery directory: **568 regular files, 183,792,370 logical
bytes, 91 structured files and 36 symlinks**. Paths inside the saved summaries
identify their inputs at execution time, not active profiles. No test game JVM
remains, and the temporary display-sleep assertion was released.

## Delivery

The official output is
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`.
The requested normal push targets `origin/feature/mac-cli`, including the existing
CLI8 implementation commit and this scoped, GPG-signed documentation/cleanup record.
Commit signature, worktree and remote/local revision equality are checked at handoff.
No force push, tag, GitHub release or notarized publication is part of this batch.
