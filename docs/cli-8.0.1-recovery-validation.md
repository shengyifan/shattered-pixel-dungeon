# CLI.8.0.1 recovery and warning lifecycle

> Historical implementation evidence. The later user-requested
> [CLI.8.0.2 clean rebuild](cli-8.0.2-cleanup-validation.md#clean-rebuild-2026-10-09)
> removed the generated attachments and old application backups named below.
> Their paths remain historical citations, not current files or runtime dependencies.

CLI.8.0.1 keeps protocol 8, audit schema 11, the independent v8 profile and game
3.3.8. This patch changes controller recovery, semantic observation lifetime and
the optional Python client's diagnostics; it does not change native combat,
pathfinding, rest rules or action policy.

## Changes

- A settle call budget is distinct from a sent request's non-renewable response
  deadline. Soft exhaustion yields `pending`; it does not invent a transport
  failure. Resuming an existing receipt query and receiving `EXECUTING/RECEIVED`
  returns control before further polling. Its query is never replayed.
- Pending wrappers expose `pending_reason`, `stage` and, only while an internal
  response remains outstanding, its exact `pending_request`. Original outcomes,
  saves, real errors and late replies remain separate from a current observation.
- Native attack episodes retain only red warning cells actually made public,
  clipped to current FOV, until native completion/cancellation. All 11 producers
  are connected, including asynchronous projectiles and multi-wave attacks.
  Warning identity is process-local and never restored from saves or private AI.
  Temporary native transformations/stasis preserve that knowledge by ownership
  transfer; they are not mistaken for permanent actor removal or cancellation.
- A shield-state removal releases its semantic reference immediately while the
  GUI halo finishes its ordinary fade. Passive marker/halo fade does not add a
  spurious revision; true warning and shield changes still invalidate old input.
- The transport-free Python client adds safe frame access, map argument checks,
  structured intent errors and explicit pending metadata. It does not normalize
  directions, fill bindings, replace revisions or send/retry any request.
- The prior bounded health-bar fix is retained: positive integer total,
  `0 <= filled <= with_shield <= total + 1`, and displayed basis. Native fractional
  layout/export is checked through Java protocol encoding and Python decoding.

## Validation scope

The complete Java gate passed with no failed or skipped tests:

| Suite | Tests |
| --- | ---: |
| `control-protocol` | 19 |
| `game-control` | 569 |
| `desktop-control` | 232 |
| Total | 820 |

The final Python suite passed 324 tests. Its existing SQLite `ResourceWarning`
diagnostics remain non-failing. Client tests cover frame selection, pending
metadata, evidence copies, intent diagnostics, old wrappers, independent
snapshots and bounded health rounding. The final packaging guard also passed
10 checks: eight invalid/internal names rejected at configuration and two real
task checks preserving symlink targets and regular-file sentinels.

The gate includes the visual and text source checks, with no exclusions skipped.
The combat inventory contains 1,309 files, 13,617 sites and 4,713 review groups;
unreviewed/changed/removed/outstanding counts are zero. The UI inventory retains
1,910 static routes with no unmapped inputs. These inventories are source-review
evidence, not runtime coverage or a claim of another playthrough.

The Java deadline tests use a controllable monotonic clock and delayed transport,
including the 410 ms receipt RTT that exposed the old five-second-tail failure.
They distinguish soft yield, true hard timeout, partial bytes, late replies,
scope discovery, cancellation and quit. Visual tests cover pending episodes,
native resolution/cancellation and FOV; renderer fixtures check wall-clock fade
without advancing the attack.

The 30 warning tests cover all 11 producers, public FOV, independent overlapping
episodes, rooted delays, native landing/damage callbacks, multi-wave preparation,
scope changes, shield removal and temporary owner transfers. Rat restoration and
Stasis cast/expiry/cancellation execute their native paths in isolated in-memory
fixtures. Beaming Ray's shared release entry is exercised; its full spell cast
has source-review coverage. The health contract test passes 12 actual native
bar-export/protocol frames through the external Python decoder without clamping.

The first full-gate attempt exposed an outdated static UI inventory and a missing
`Game.version` in the new Rat test harness. The inventory diff was reviewed and
the fixture now preserves/sets/restores that field. The six suspension tests and
the complete gate passed in fresh JVMs after those test-only corrections.

All runtime tests use newly created, explicitly labeled fixture profiles. The
already completed personal game and its controller are not queried, closed or
restarted. No personal save or private audit is used for implementation tests.

## Package output and the existing process

`packageMacArm64` accepts `-PmacArm64OutputSubdir=<name>`. Names are restricted to
`app-macos-arm64` or `app-macos-arm64-<safe suffix>`, at most 128 characters,
directly inside `desktop-control/build`. Arbitrary paths, traversal, internal
build directories and symbolic-link destinations are refused before packaging
cleanup. This prevents collisions with staging, native binaries or frozen
runtime images used by another JVM. The default remains `app-macos-arm64`.

Path-traversal names, symlink destinations and existing regular files have been
checked to fail before cleanup, preserving the fixture sentinel content.

The initial build and isolated validation use `app-macos-arm64-cli-8.0.1`.
The user's subsequent instruction changes the final delivery directory back to
`app-macos-arm64`: the validated image replaces that directory, with the old
image retained as a separate backup. Launcher, JAR and runtime-release hashes
verify preservation of the old image. No command queries, quits or restarts the
existing game/controller; new Java fixes take effect on the next launch, without
claiming that the open process has been hot-updated.

The actual packaged launcher reports `CLI.8.0.1 (protocol 8, game 3.3.8)`.
Its printed help, bundled help and `docs/cli-help.md` are byte-identical. All
3,362 catalog entries match their build inputs, including 3,359 packaged class,
asset and help entries; the remaining three identify transport/build inputs.
The native CLI, JVM launcher, GUI launcher and JVM library are ARM64. Strict
deep signature verification passes with a local ad-hoc signature, and fixture
classes/agents are absent from the distributed JAR.

- Build ID: `05916489057c7e50c0e0acaa056cee54525df10c9beb60afa50bee21180a3edc`
- App JAR SHA-256: `acb47c52cdc6ebcf2ab5f80f901214231928765486744c88860cfcdf38994807`
- Delivered bundle: `desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`
- Previous-image backup: `desktop-control/build/app-macos-arm64-backup-before-8.0.1-20261008-7a020fcb/Shattered Pixel Dungeon.app`

After the final source-review correction restricted the packaging namespace,
the package was rebuilt and its complete identity checks and isolated runtime
fixtures were rerun. Delivery renames the original image to the backup and moves
the validated image to the original directory. The old launcher's, old JAR's and
old runtime descriptor's hashes remain identical in the backup; the new JAR's
hash is identical before and after delivery. Version/help/architecture/signature
checks and the ordinary controller fixture also run at the final original path.

The ordinary packaged controller fixture passed with 36 wire requests across
two sessions, four save receipts, a locally rejected old revision and a matching
saved/reopened Warrior fixture. It uses the native packaged CLI and bundled JVM,
with no engine fixture injection or save/audit reads. The separate instrumented
slow-quit fixture exposed initial `in_progress`, returned a normal
`pending_reason:"action_pending"` for a one-second settle while the original
receipt was `EXECUTING`, then received the original `COMPLETED` receipt and exited
with code zero. It issued no post-quit state query.

The final visual fixture results are 6/6 (`red`, `goo`, `hidden`, `frozen`, `bomb`,
`bomb-hidden`) and 5/5 (`supernova-halo`, `supernova-halo-edge`, and blast radii
1/3/6). Yog red cells and revision persist after 2.3 seconds of native marker
fade, then clear/change on actual attack resolution. SuperNova retains its
pending red cells and revision through marker fade and halo pulsation. Hidden
sources remain unpublished, and old event evidence stays immutable.

These visual tests explicitly use the final package's production JAR, verified
and frozen by SHA-256, plus test-only fixture classes on a recorded host JVM.
They inject isolated native scenarios and are not ordinary `spdctl` launches or
bundled-JVM evidence. The ordinary controller and instrumented slow-quit tests
above supply those separate launcher/runtime checks. No test fixture counts as
real playthrough progress or a new win.

Local verification evidence is retained under
`desktop-control/build/validation-cli-8.0.1-20261008/`:

- `full-gate-final.log`, `java-test-totals.json`, `python-suite-final.log`
- `package-output-guard-final.json`, `final-package.log`
- `package-identity-final.json`, `package-identity-delivered.json`, `delivery.json`
- `controller-package-final.log`, `controller-package-delivered.log`, `slow-quit-package-final.log`
- `visual-cue-package-final.log`, `radial-visual-package-final.log`

Each runtime log names its independent fixture report/profile and retained raw
transport. The final visual runtimes are `runtime-26c3290f68e64d4f85cb36c35db9afce`
and `runtime-3dcaafd3c1ae490cbfa4008c7ad81d84`; their reports retain the exact
package/JAR/host-Java identities and the isolated fixture boundaries.

The acceptance gate is:

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath :desktop-control:crashAgentJar \
  -PmacArm64OutputSubdir=app-macos-arm64-cli-8.0.1 \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

Package verification includes actual version/help output, build catalog/content,
ARM64 binaries, local ad-hoc signature, ordinary controller operation and a
separately identified instrumented slow-quit fixture. An ad-hoc signature is local
testing evidence, not notarized distribution. No push, tag or publication is part
of this batch.
