---
name: shattered-pixel-dungeon-cli-release
description: Diagnose, implement, validate, version, and locally commit requested spdctl controller, protocol, trace-viewer, or macOS ARM64 package changes in this repository.
---

# Shattered Pixel Dungeon CLI maintenance

This is the repository-local adaptation of the maintenance skill used during the
Warrior run. Use it for a requested diagnosis or implementation task, including a
gameplay blocker; a playthrough alone does not authorize rebuilding or restarting
the game. Read [AGENTS.md](../../AGENTS.md), [current help](../../docs/cli-help.md),
[client guidance](../../docs/cli-playthrough-client.md), and the relevant source.
The [import record](../../memory/IMPORTS.md) identifies the original material.

## Establish the boundary

- Inspect the checkout, branch, worktree and actual launcher version. The present
  baseline is CLI.9.0.3 / protocol 9 / schema 12 / game 4.0.2; verify it rather than
  treating this line or historical test totals as current package evidence.
- Preserve the existing game/controller and personal profiles. Never inspect
  personal saves or private audit databases to diagnose gameplay decisions. Use
  the user's public wire capture, current authorized protocol observations, source,
  and new explicitly labeled isolated fixtures.
- Preserve the original request ID and bytes on a failure. Separate action outcome,
  save receipts, current observation, semantic decode errors and late responses.
  Never replay uncertain work, silently refresh its revision or widen the client's
  health-bar tolerance to make an unrelated error disappear.
- Honor a Python-only repair or pause boundary. An external decoder can be repaired
  without restarting a JVM; Java/package changes affect a subsequent launch. A
  successful state query is not evidence that unsaved combat has been persisted.

New-profile availability is a user-authorized exception to the original pre-run
progression gates: both desktop entrypoints initialize only absent/empty profiles,
with no fake badges, debug mode or old-profile migration. Validate fresh production
launchers directly; the legacy fixture launcher grants unlock badges and cannot
prove this default. Actual in-run progression and daily/seed checks remain native.

For ordinary GUI acceptance, launch the exact packaged executable with an explicit
isolated `--data-dir` and identify its process before binding a GUI tool. After
quitting, verify exit by that PID; do not ask the GUI tool to observe the exited
app, because it can automatically relaunch the default entrypoint. Exclude any
such unintended default-profile window from fixture evidence and report it.

## Diagnose with the correct evidence

For controller errors, distinguish local `CONTROLLER_*` diagnostics from child
wire replies. Exact SEND/RECV bytes come from the native relay; a wrapper or SQLite
reconstruction is not the original transport. Trace viewers remain passive.
Keep stderr separate from NDJSON. A 64 KiB streaming buffer does not establish
frame truncation; check full byte delivery before changing buffer sizes.

For `STALE_STATE`, compare the same-frame decision facts and actual lifecycle.
Retain strict revision validation. Fix an incorrect readiness/warning lifecycle
only after source analysis and a reproducible fixture; do not exclude all warnings
or strip public facts. For CLI.8.0.1 settle recovery, a call budget is soft but each
sent request's response deadline is not renewed. Outstanding internal queries are
resumed through `settle`, not resent or implicitly drained by `state/cancel`.

Useful local evidence routes:

- [CLI.9.0.3 integration and validation](../../docs/cli-9.0.3-unlock-validation.md)
  and [semantic observations](../../docs/cli-combat-visuals.md) for the current batch;
  [CLI.8.0.1 recovery/warning validation](../../docs/cli-8.0.1-recovery-validation.md)
  records the preceding lifecycle fixes.
- [Health rounding and pause history](../../memory/references/health-rounding-and-pause-history.md)
  for the producer/decoder boundary and process-preserving repair example.
- [CLI6 PTY incident history](../../memory/references/cli6-controller-pty-history.md)
  for shared descriptor diagnosis. Its old versions, permissions and workaround
  are historical; normal current PTY startup needs no output-file workaround.

## Implement and verify the authorized change

Keep the patch scoped, with English protocol/help/diagnostics/tests. Fix behavior
without changing native combat, rest or pathfinding rules unless the user asks.
Add regression coverage for the actual failure and run focused checks first.
For a runtime CLI fix, increment the CLI version and synchronize launcher metadata, MachineSession, build catalog,
current help/docs, AGENTS and CHANGELOG; change protocol/schema only when their
contracts require it. A documentation/skill import does not itself change the CLI.

For a runtime/package batch, the repository gate is:

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

Packaging replaces its selected generated app directory. Follow the user's chosen
delivery path; if protecting an existing image requires a separate staging output,
use `-PmacArm64OutputSubdir=app-macos-arm64-<safe suffix>` within the build namespace.
Do not run broad cleanup or overwrite a running image without the applicable
authorization. Inspect individual generated paths and symlinks before any cleanup.

Verify the emitted executable's `--version`, byte-identical `--help`, catalog and
class/resource hashes, ARM64 contents and signature. Use new isolated profiles for
package smoke tests. Local ad-hoc signing is not notarized distribution.
For visual compaction compare one frozen observation or packaged-codec replay,
not two adjacent live frames. For async quit verify initial `in_progress`, the
original terminal receipt and process exit, with no subsequent state query.
An instrumented slow-quit test requires the package's `java.instrument` module and
native library; report instrumentation separately from ordinary package smoke.

Report source review, fixtures, package evidence and real gameplay separately.
Store useful incident conclusions in project documentation without copying a
personal profile or turning old coordinates into a reusable route. After applicable
validation, inspect staging, run `git diff --cached --check`, make the project's
scoped local GPG-signed commit, verify its signature and worktree. Push, tags and
publication require the user's separate request.
