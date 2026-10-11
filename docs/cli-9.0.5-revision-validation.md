# CLI.9.0.5 revision validation

## Status and evidence boundary

Validated on **2026-10-12 (Asia/Shanghai)**. Implementation, the full Java/Python
gates, package identity checks, all 20 rendered presentation fixtures, ordinary
packaged-controller/raw-protocol smoke and the separately instrumented slow-quit
probe passed. The sections below distinguish source review, isolated rendered
fixtures, ordinary bundled execution and instrumented lifecycle evidence.

The release identity is **CLI.9.0.5 / protocol 9 / audit schema 12 / game 4.0.2 /
code 922 / trace format 1**. Baseline checkout:
`c393b58fa6510f40f6bef3b5708ab60feabb7787`. The profile directory remains
`Shattered Pixel Dungeon CLI v9`. Native combat, AI, map generation and save rules
remain unchanged. Tests use new, explicitly marked isolated profiles; personal
saves/audit stores and the already ended real playthrough are not test inputs.

## Incident and implemented correction

The committed [2026-10-11 run evidence](../memory/evidence/2026-10-11-warrior-nine-challenges-gnoll-loss/)
contains 11 `STALE_STATE` rejections. Each rejected action used the immediately
preceding completed/player-ready observation's exact scope and revision with no
intervening wire request. Hero, map, entities, inventory, advertised actions and
UI nodes remained unchanged in the compared public observations. The changing
intent-relevant cues were eight character-aura positions, three chilled onsets
(two overlapping aura cases), one sentry-particle disappearance and one item-glow
appearance. These source/presentation mismatches do not establish a revision TTL
or authorize client-side rebinding.

The correction keeps those public semantic facts and meaningful warning checks.
Finite completion waits for eligible, attached public sources to reach a rendered
semantic boundary: aura follow, chilled first presentation, finite sentry tails
and semantic item landing. The same observed continuous particle episode retains
its meaning across natural empty intervals. Hidden, detached, frozen and
out-of-scope sources must not block forever. Pending work retains the existing
`in_progress`/settle contract.

Ordinary actions prepare one frozen public/internal before snapshot at the game
boundary. The session persists its paired audit outside the scheduler lock and
then commits against the same public decision projection. Commit does not produce
a second internal audit snapshot or refresh an old revision. Input dispatch is
at most once and happens only after successful audit persistence. Queued/prepared
work can be aborted; submitted work retains the original execution outcome.

New normal/activity revisions use their existing persistent counters directly in
decimal (`r10`, `a10`). Other kinds retain their spelling. Existing registry
mappings and raw/reply history are immutable; there is no migration or
older-launcher rollback allocator. Revisions remain opaque and process-bound.

## Rendered reproduction and completion checks

`presentation_boundary_smoke.py` first reproduced the defect against the retained
CLI.9.0.4 packaged production JAR, SHA-256
`636c7d800eeae29ac909b54a4bcdc7f8d6359892ff1df3aed66cb46779b14fcf`.
The sentry-tail case returned `player_ready` at `r6` while its observed charge
particles remained. Later observations changed to `r7` without a game turn, and
the action using the original `r6` was rejected. The fixture confirms that the
rejected action did not move the hero. This is a deliberately failing old-runtime
regression case, recorded as successful reproduction in `presentation-baseline.json`.

The fixed development runtime passed all five cases at interface size 2 and
60 FPS. The rebuilt CLI.9.0.5 production JAR then passed **20/20 rendered cases**:
five scenarios (`giant-move`, `growing-move`, `chilled-first`, `sentry-tail`,
`chest-glow`) × interface sizes 2 and 0 × foreground limits 60 and 12 FPS.
Each case verified the intended semantic cue at final completion, four unchanged
revision samples taken 0.6 seconds apart, and acceptance of an action using the
original final revision after that 2.4-second observation period. After the real
action changed state, reuse of the previous revision returned `STALE_STATE`; its
receipt was `REJECTED`, before/after snapshots matched and the hero did not move.
There were **zero action retries**.

These are native rendered fixtures using the actual packaged production JAR
first on the classpath, an explicitly test-only `FixtureLauncher`, and the **host
JVM**, not the ordinary `spdctl` executable or bundled JVM. Frozen runtime records
identify the exact production JAR, test classes, host JVM and excluded development
production entries. Fixture setup is separate from gameplay evidence. Every run
uses a new marked profile, records exact send/receive bytes and requires normal
process exit; no fixture counts as a win.

The maintained Java fixtures additionally cover FOV edges, hidden/frozen/detached
sources, source reuse, queued chills, finite particle tails and an observed item
drop temporarily rising above the current footprint's FOV edge. The drop keeps
only its own previously public anchor, with run/level/lifetime/episode checks and
pruning; it does not read a hidden heap destination. These unit/source checks are
separate from the 20 rendered combinations above.

## Transaction, identity and full-suite verification

`ActionPreparationTest` verifies immutable preparation, no callback before commit,
at-most-once dispatch, abort before and after preparation, native-input and scene
invalidation, a changed public control, run-identity ownership, commit/abort races,
close cleanup and uncertain failure after dispatch. Its final-guard case removes
the isolated profile after preparation to prove commit does not recapture the
internal profile. `MachineSessionPreparedActionTest` verifies no extra observe,
the exact audited before state, paired-audit failure, preparation timeout and late
readiness, changed final guard, runtime scope/revision rejection, and submitted
execution retained after the response deadline. Existing continuous activity,
cancellation, receipt, save and quit suites remain part of the full gate.

`PublicHandleStoreTest` covers decimal `9→10` and `99→100`, restart continuity,
forward allocation from legal legacy base36 mappings without rewriting them,
paired rollback and counter exhaustion. `PublicHandlesTest` covers current and
activity bindings while preserving opaque historical raw/reply content. Existing
wrong-kind and profile-local identity checks remain enabled.

The final offline Gradle gate used `--no-build-cache --rerun-tasks --no-daemon`
and executed **39 tasks**. Runtime and test classpaths were generated before the
dependent Python suites.

| Executed gate | Result |
| --- | --- |
| Full control-protocol Java | 20 passed |
| Full game-control Java | 688 passed |
| Full desktop-control Java | 261 passed |
| Full Java total | **969 passed; zero failures, errors or skips** |
| Desktop Python | 386 passed |
| Source-inventory Python | 8 passed |
| Public gameplay-helper Python | 35 passed |
| Rendered fixtures using the rebuilt production JAR and host JVM | 20/20 passed |
| Ordinary bundled controller | Passed; two sessions, 29 + 7 requests, four save receipts |
| Ordinary bundled raw-protocol package smoke | Passed; six wire frames plus isolated boundary probes |
| Separately instrumented slow-quit package probe | Passed; initial pending, original completed receipt and exit 0 |

The first full gate is retained as `full-gate-initial.log`: it failed 12
game-control tests. Ten preparation failures were caused by a preceding
`ActorHandoffTest` leaving the static `Actor.current` reference behind. That test
now restores its previous value in cleanup; production snapshot checks were not
relaxed. The remaining failures identified the obsolete `Dungeon.nextRunId`
static-manifest entry after request-owned run identity replaced it, and the UI
inventory's parsed-type count after adding private `DropObservation`. The exact
manifest and reviewed type count were updated; the final full rerun passed. These
repairs preserve the audit and inventory assertions instead of bypassing them.
Existing non-failing Python SQLite `ResourceWarning` diagnostics and Gradle
deprecation notes remain visible in the logs.

## Source-review evidence

The checked combat/visual inventory covers **1,343 source files, 13,624 sites,
4,925 review groups and 1,488 retired sites**, with no unresolved, changed,
removed or unreviewed current entries. The reviewed baseline delta is 30 new
sites, 82 changed sites and one retired site; no existing semantic classification
was downgraded. The scanner now fingerprints the `CharSprite` source boundary so
its pure readiness helper cannot evade future review. The source canary remains
enabled. Detailed evidence is in
`game-control/build/combat-visual/cli905-reviewed-delta.json` and
`cli905-reviewed-coverage.json` in that directory.

The text gate remains clean at **210 operations, 1,239 core sources and 789
verified catalog constructors**. UI coverage parses **2,881 types** and preserves
all **1,917 existing records**: no input route, method body or recorded line
position changed, and `unmapped_inputs=0`. The sole added type is the private
readiness-only `VisualCueCollector.DropObservation`; the reviewed delta is in
`game-control/build/ui-coverage-cli905-review.json`. These inventories explicitly
remain static evidence, not runtime coverage.

## Rebuilt ARM64 package

The standard bundle is
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`.
Its actual executable reports `CLI.9.0.5 (protocol 9, game 4.0.2)`.
Source, bundled and emitted help match byte for byte. The catalog has **3,479
entries**: **3,474 packaged entries** match their current inputs, with five
native/build inputs verified separately. Both snapshot manifest resources match.
All **541 compiled test and instrumentation classes** are absent from the
production JAR. All **35 Mach-O files** are ARM64-only; plist validation and strict deep ad-hoc signature
verification passed. This signature is local packaging evidence, not notarization.

| Identity | SHA-256 / value |
| --- | --- |
| Build ID | `c157c2a1c6f1cea7d104a4121b666031e0125675f178a772889b52e566d5a096` |
| Production JAR | `06bb0a7261c31bfba2172712b02b3c34e32030c1e2fc78ecd74a85bbd828cd65` |
| Launcher | `1916b38ff7d7c083a68dd471bd38169b97793358b135fe5abbecebbca1ae3bb2` |
| Help | `855323f85be7527c00213beb5b2d6fc1a5038bbf05b1a365c329742465a0ef91` |
| Catalog | `56fcc03d90eaca5bb3f6f81449fdbabc58fc0e4d6b30ede62a8ea4c6138f8fff` |

The ordinary `control --machine` smoke used the actual packaged launcher and
bundled JVM in two sessions, with **29 + 7 requests and four save receipts**.
Fresh defaults, inventory/targeting operations, public save/reopen matching,
local rejection of an old displayed revision and exact raw/viewer records passed.
No state query followed quit. The lossless view checks within that harness were
separate offline replays of frozen inputs through the packaged codec on the host
JVM; they were not comparisons of adjacent live frames. Results are in
`desktop-control/build/fixtures/cli9-controller-b17577f12cd14246b879c062046f3738/result.json`.

The ordinary raw-protocol package smoke copied the bundle and selected a fresh
profile under paths containing Chinese characters and spaces. Its six-frame run
and boundary probes passed bundled-JVM/native-SQLite loading, exact wire bytes,
protocol-8 rejection, invalid-UTF-8 recovery, duplicate-query handling, profile
locking, EOF without unsolicited output, a final frame without a newline,
lock-conflict emergency-tree preservation, database integrity, profile-local JVM
crash-path configuration, codesign and plist checks. Results are in
`desktop-control/build/fixtures/packaging9.0/raw-24d42d17653144948154fa1d82b58251/result.json`.
This does not test a forced JVM native crash, Intel hardware or notarization.

The separately instrumented slow-quit package probe passed using the packaged
launcher and bundled JVM with its `java.instrument` module and native library.
It held the original native quit callback, exposed initial `in_progress`, observed
an `EXECUTING` receipt and bounded settle `action_pending`, then delivered the
original `COMPLETED` receipt before process exit code 0. There was no unsolicited
reply or post-quit state query. This run recorded 14 requests, two settle calls
and one successful save receipt, without save/game-state injection. Its report is
`desktop-control/build/fixtures/slow-quit-package-059ddae0c0b449cabfd5c1b66c1c85bc/result.json`.
This is instrumented lifecycle evidence, separate from ordinary package smoke,
host-JVM presentation fixtures and real gameplay. The final process check found
no remaining game, controller, fixture or Gradle processes.

## Artifacts and delivery boundary

Generated verification evidence is under `desktop-control/build/cli905-validation/`:
`full-gate-initial.log`, `full-gate.log`, `java-test-totals.json`, the three
`python-*.log` files, focused/transaction/FOV test logs, `presentation-baseline.json`,
`presentation-dev.json`, `presentation-package.log`, `presentation-summary.json`,
`package-identity.json`, `package-identity.log`, `controller-package.log`,
`package-smoke.log` and `slow-quit-package.log`. Rendered fixture profiles and frozen runtimes are under
`desktop-control/build/fixtures/`; each result records its exact profile and
runtime identity. Generated profiles, raw traces and package artifacts remain
ignored build outputs, rather than committed gameplay data.

This batch authorizes one scoped GPG-signed local commit containing this report
after validation and staged-diff checks. Its signature and clean worktree are
verified separately after creation; a report cannot establish its own future
commit identity. No push, tag or publication is part of this delivery. These
fixtures do not establish a real playthrough or a full-game clear, and the ended
personal playthrough was not resumed.
