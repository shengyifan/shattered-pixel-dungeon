# CLI.8.0.2 maintained code and documentation cleanup

CLI.8.0.2 keeps protocol 8, audit schema 11, the independent v8 profile and game
3.3.8. This batch repairs maintained test/research entrypoints, removes retired
CLI7 observation plumbing and aligns the current source-review evidence. Native
combat, game rules and public protocol meanings are unchanged.

## Clean rebuild 2026-10-09

After the implementation validation below, the user explicitly requested removal
of all earlier project build products and application backups, followed by a fresh
macOS CLI build and Git push. The runtime source remains commit
`7cf7cf234cc0f301a13a454064de8b19ce3d6c5c`; this rebuild does not change CLI,
protocol, schema or game versions.

Before rebuilding, 14 ignored generated directories and the generated
`ios/robovm.properties` file were deleted after checking that no cleanup root was
a symlink and no tracked input was inside it. They contained 12,059 regular files
and 1,637,452,281 logical bytes. This includes every previous app image/backup,
fixture, frozen runtime and validation attachment under the project build trees,
the project-local `.gradle` cache and Python bytecode caches. Nested links were
unlinked without following them. Personal profiles, external transport logs,
tracked source and build-tool inputs, and global dependency/JDK installations
were not removed. Gradle regenerates the iOS properties file during configuration.

The complete Java/package gate ran with `--no-build-cache --rerun-tasks --offline
--no-daemon --console=plain`: all 38 actionable tasks executed, and all 819 Java
tests passed without failures or skips. Python discovery passed 354 desktop tests,
8 source-inventory tests and 21 gameplay-helper tests. The first concurrent desktop
Python run reached a source-inventory test before Gradle wrote its required test
classpath; rerunning after that writer completed passed the entire suite. No
runtime source fix or test exclusion was needed.

The freshly built default bundle is
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`, with **no old
application backups retained**. It reports `CLI.8.0.2 (protocol 8, game 3.3.8)`.
Its 3,358 catalog entries, snapshot resources, ARM64 binaries, exact help bytes
and strict deep ad-hoc signature checks pass. The three retired production types
and test launchers/agents remain absent. Both build ID and application JAR bytes
are identical to the implementation build recorded below:

- Build ID: `3764bd99c7b1ac37437ceb731c082f8dcf0ef74b19df86aace38ece55dbbe999`
- App JAR SHA-256: `b6edfbdf928e534c1e4a36eeb1fef36781b7107ee75beaa17e19ffd0d8ff3bd9`

Fresh isolated ordinary-controller and instrumented slow-quit package checks
passed using the native CLI and bundled JVM. The ordinary case covered two
sessions, 36 wire requests, four save receipts, old-revision rejection and matching
saved/reopened fixture state. The slow-quit case retained the original receipt
lifecycle and issued no post-quit state query. The earlier 21-case native visual
validation below belongs to the same JAR bytes; it was not rerun or counted as a
new visual sweep during this build-only task.

Current generated evidence is under `desktop-control/build/clean-rebuild-20261009/`:
`cleanup.json`, `full-gate.log`, `java-test-totals.json`, `python-suite-final.log`,
`source-gate-python-suite.log`, `gameplay-helper-suite.log`, `package-identity.json`,
`controller-package.log` and `slow-quit-package.log`. Those runtime logs identify
only fresh labeled fixture profiles and raw transport. Earlier generated evidence
paths and backup paths in the implementation record below have been removed;
they are retained as historical citations, not claims that the files still exist.

## Changes and scope

- The shared smoke client resolves the original successful quit receipt before
  waiting for exit. Initial `in_progress` is not mistaken for a completed quit.
- Historical token studies use a pinned protocol-7 decoder without changing the
  production v8 decoder or leaving a historical import in its module binding.
  CLI8 study inputs, baseline and output are explicit; frame counts come from the
  supplied baseline. Generated output must preserve all source inputs.
- Retired screen-effect and generic particle-metric production types/callbacks
  are removed. Current semantic world cues, reviewed particle counts and the
  sacrificial-fire density remain. HUD visibility checks and turn sweep no longer
  construct unused renderer payloads.
- The internal visual-review ledger uses v3 typed evidence and validates current
  test references. Routes and visibility point to the group's reviewed semantic
  contract; source-only evidence is not producer-specific runtime coverage.
  Actual source removals are explicitly retired rather than silently forgotten.
- Obsolete per-version implementation, rebuild, incident and one-off acceptance
  reports are removed from `docs`. Current guides are maintained in place; the
  [architecture](cli-architecture.md) explains the live source and test structure.
  The recent [8.0.1 recovery record](cli-8.0.1-recovery-validation.md), upstream
  build guides and user-requested project-local gameplay memory/skills remain.

## Initial implementation validation

The complete Java/ARM64 gate passed, including all source-review checks, without
failed or skipped tests:

| Suite | Tests |
| --- | ---: |
| `control-protocol` Java | 19 |
| `game-control` Java | 568 |
| `desktop-control` Java | 232 |
| Total Java | 819 |
| Desktop Python discovery | 354 |
| Source-inventory Python discovery | 8 |
| Project-local gameplay helper tests | 21 |

Python discovery passed; existing SQLite `ResourceWarning` diagnostics remain
non-failing. The source ledger has 1,306 files, 13,578 live sites, 4,680 groups and
99 current semantic contracts. Unreviewed, removed, changed and outstanding counts
are zero. Its 2,041 group method references and 204 contract method references
all resolve. The 44 newly removed sites join 295 explicit retired sites; one
contract is explicitly retired. All 13,573 surviving prior sites retain their
group, classification and semantic mapping. This remains static evidence, not
runtime execution of every producer.

The UI inventory retains all 1,910 routes and their method bodies; only 14 source
line positions and the parsed-type total (2,785 to 2,781) change. Snapshot static
manifests drop the removed `RenderedAppearance.OFFICIAL_ATLASES` field and its now
stateless root, leaving 325 roots and 1,363 fields; declaration checks pass.

The first focused run exposed an ineffective test setup: `Image.frame()` returns
a copy, so mutating its return did not set a non-finite native frame. The fixture
now uses the real setter and keeps finite geometry to exercise the frame gate;
all 36 focused tests passed. The first full gate required the reviewed UI inventory
line update; the final complete gate then passed. No source check was disabled.

Both repaired research entrypoints were also executed with real Java probes,
the pinned Git decoder and their actual tokenizers on explicit synthetic public
transports: CLI7 replayed three frames across three structural modes using
tiktoken 0.12.0; CLI8 projected one frame using tiktoken 0.14.0 and a supplied
baseline. No real historical corpus was remeasured. Zero savings/growth remains
a valid measurement unless `--require-smaller` is explicitly selected. Output
symlink, overlap and optional-wire protection are separately covered by offline
entrypoint regressions.

## Initial implementation package and native fixtures

The emitted and delivered executable reports
`CLI.8.0.2 (protocol 8, game 3.3.8)`. Printed help, bundled help and the source manual
are byte-identical. All 3,358 catalog entries match their build inputs, including
3,355 packaged class/asset/help entries; the remaining three identify build and
transport sources. Both snapshot manifest resources also match their source bytes.
The CLI, JVM launcher, GUI launcher and JVM library are ARM64; strict deep local
ad-hoc signature verification passes. Retired `ScreenEffectCollector` (including
its inner class), `ScreenEffect` and `VisualMetric` are absent from the JAR, as
are test launchers/agents. Ad-hoc signing is not notarized distribution.

- Build ID: `3764bd99c7b1ac37437ceb731c082f8dcf0ef74b19df86aace38ece55dbbe999`
- App JAR SHA-256: `b6edfbdf928e534c1e4a36eeb1fef36781b7107ee75beaa17e19ffd0d8ff3bd9`
- Delivered bundle: `desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`
- Previous-image backup at initial delivery (subsequently removed by the clean rebuild above): `desktop-control/build/app-macos-arm64-backup-before-8.0.2-20261009-1a36287a/Shattered Pixel Dungeon.app`

The ordinary packaged controller smoke passes both before and after delivery:
two isolated sessions, 36 wire requests, four save receipts, old-revision rejection
and matching saved/reopened Warrior state. The separately instrumented slow-quit
case exposes initial `in_progress`, returns normal bounded pending while the
original receipt is `EXECUTING`, obtains its `COMPLETED` receipt and exits without
another state query. These use the actual native CLI and bundled JVM.

The exact packaged production JAR also passes 21 native visual scenarios with
293 original GUI postcondition checks: five flash/shake cases, 13 HUD/appearance
cases, one pending Yog warning and two SuperNova cases. Screen decoration remains
absent from wire/events without staling input; current HUD meanings and pending
warning lifetimes remain intact. These cases use the frozen packaged production
JAR plus test-only scenario classes on an explicitly recorded host JVM. They are
distinct from ordinary packaged-launcher checks and do not count as a playthrough.
The frozen runtime identity is `runtime-bdddb0a1fe644e628cd163e14ae9f352`.

All native runtime checks use newly created, explicitly labeled isolated profiles.
The existing personal game/controller is not queried, closed or restarted. This
batch does not read personal saves or private audit databases.

The initial delivery used `app-macos-arm64-cli-8.0.2` staging, then placed the
validated image at `desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`
and retained the previous default as a backup. The later clean rebuild above
supersedes that backup-retention decision. New Java code applies on the next
launch, not as a hot update to an existing process.

At initial delivery, the previous launcher's, JAR's and runtime descriptor's hashes
were unchanged in the backup. The new JAR hash matched before/after delivery, and
version/help/signature checks plus an isolated controller smoke passed at the
original path. Older backups were retained then and removed by the later explicit
clean-build request; personal game data was not cleaned.

## Maintained documentation and evidence files

Exactly 73 obsolete Markdown reports were deleted (7,769 lines, 628,856 bytes),
without creating an archive directory. The tracked docs tree goes from 87 files
to 16, including the upstream guides, maintained protocol/client/coverage guides,
the new architecture and this report, plus the preceding 8.0.1 repair record.
Maintained repository links resolve. Upstream build-guide links outside this CLI
cleanup retain their existing upstream conventions.

Initial generated evidence was under the following directory, subsequently removed
by the clean rebuild above: `desktop-control/build/validation-cli-8.0.2-20261009/`:

- `full-gate-final.log`, `java-test-totals.json`, `python-suite.log`,
  `source-gate-python-suite.log`, `gameplay-helper-suite.log`
- `ui-inventory-diff.json`, `focused-java-final.log`, `focused-java-totals.json`
- `research-v7-final.log`, `research-v8.log` and their labeled synthetic inputs
- `package-identity.json`, `package-identity-delivered.json`, `delivery.json`
- `controller-package.log`, `controller-package-delivered.log`, `slow-quit-package.log`
- `packaged-visual-results.json`, `packaged-visuals.log`, `run_packaged_visuals.py`

Initial source inventory comparison and retirement details were also in
`game-control/build/combat-visual/cleanup-802/checked.json` and `review-summary.json`.
These earlier generated files have been removed; current source checks are freshly
generated under `game-control/build/combat-visual/` by the new gate.

## History and reproducibility

Removed Markdown remains available in Git at pre-cleanup commit
`bbb7d21de5fe503a125c3719e1c53976a2730dbc`, for example:

```sh
git show bbb7d21de5fe503a125c3719e1c53976a2730dbc:docs/cli8-implementation.md
```

Changelog links to removed reports are pinned to that historical revision. Exact
source copies in `memory/references` retain their original historical path text;
those citations are not current filesystem dependencies or operating instructions.
