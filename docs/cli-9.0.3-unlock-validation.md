# CLI.9.0.3 new-profile defaults

This release implements the user-selected default for genuinely new desktop
profiles. Identity: **CLI.9.0.3 / protocol 9 / audit schema 12 / game 4.0.2,
code 922 / trace format 1**. The default CLI directory remains
`Shattered Pixel Dungeon CLI v9`.

## Clean rebuild 2026-10-10

After the implementation record below, the user requested removal of all prior
project build products, a fresh macOS CLI build and Git push. Runtime source
remains `46b634bdeaad643ea11e3de8d6d42d966284966a`; CLI, protocol, schema and game
identities are unchanged.

Fourteen ignored generated directories and generated `ios/robovm.properties`
were removed after verifying containment, no tracked inputs and no symlinked
cleanup roots: **25,923 regular files / 3,647,304,963 logical bytes**. This removed
old app images, stale 3.3.8 JARs, fixtures, frozen runtimes, validation artifacts,
the project-local Gradle cache and Python bytecode caches. Nested symlinks were
not followed. Personal profiles, external logs, tracked build inputs and global
dependency/JDK caches were excluded. Gradle regenerated the iOS properties file
during configuration; no mobile build was executed.

The offline full gate used `--no-build-cache --rerun-tasks --no-daemon`: all
**39 tasks executed**, and **897 Java tests** passed (20 protocol, 636 game-control,
241 desktop-control), with no failures/errors/skips. After runtime/test classpath
generation, sequential Python suites passed **368 desktop / 8 source / 35 helper**
tests. Existing SQLite ResourceWarning diagnostics did not fail tests.

The standard ARM64 app again reports `CLI.9.0.3 (protocol 9, game 4.0.2)`.
Production JAR, build ID and help hashes exactly match the implementation package
recorded below. The catalog SHA-256 is
`a95426cb7a09e59c603b12373b128132fe42b595acad792f4cc3652f8301bd02`.
All 3,469 catalog entries, manifest identity, source/bundled/printed help,
snapshot resources, ARM64-only binaries and strict deep ad-hoc signature pass.
All 472 compiled test classes, the instrumentation agent and retired types are
absent from the production JAR. Only the new standard app output is retained.

New actual-package checks passed: all nine unlock/menu/class/daily/duplicate-seed
cases across 11 ordinary bundled-controller/JVM launches; 22 unchanged-content
profile refusals plus fresh schema-12 reopen; ordinary controller lifecycle and
save/reopen; and the separately instrumented slow-quit check. The successful
ordinary controller run had two sessions, 29+7 requests and four save receipts,
with old-revision rejection, matching saved state and complete raw/viewer checks.

The first ordinary controller run's restart returned a TitleScene observation
already marked `phase:"closing"` and exited normally before the next click was
registered. The native exit trigger was not captured. Its failed fixture and raw
records remain preserved; a separate fresh fixture run serially passed all checks
without source/package changes. No causal claim about concurrent launches or OS
input is made.

Current generated evidence is under
`desktop-control/build/clean-rebuild-20261010/`: `cleanup.json`, `full-gate.log`,
`java-test-totals.json`, `python-schema-summary.json`, `package-identity.json`,
`unlock-all-summary.json`, `unlock-suite-evidence.json`, `controller-package.log`,
`controller-package-serial-retry.log` and `slow-quit-package.log`. Earlier generated
paths cited below were deleted and remain historical references. GUI automation
was not repeated; the preceding GUI acceptance belongs to the byte-identical
production JAR. This round used only explicitly isolated CLI fixtures, without
GUI tools or personal profile access, and does not count as a real playthrough.

The user authorized pushing `feature/mac-cli` to `origin` after this verification.
Master remains the upstream v4.0.2 commit; tags and publication are not included.

## Availability contract

Both ordinary desktop GUI and spdctl initialize an absent or completely empty
profile with `unlock_start_options=true`, `intro=false` and the current game
version. CLI additionally retains its windowed Simplified-Chinese defaults;
ordinary GUI retains native display and language defaults. Only initialization
changes: mobile launchers do not enable these defaults.

The persistent setting defaults to false when absent. Existing directories,
including ones without settings or containing only an audit/lock entry, are not
migrated or backfilled. A new game slot in an old profile keeps that profile's
earned progression. Startup captures freshness before creating its own files,
applies defaults after locking and loading preferences, and preserves already
populated preferences. CLI rejection still precedes defaults and profile writes.

The setting opens six classes, all nine challenge choices, custom seeds, daily
runs, full randomization, class/subclass/armor-ability and all talent-tier
information previews, plus ranking seed-copy eligibility. Challenge mask starts
at zero and custom seed is empty. Returning to hero selection preserves choices.

Badges, victories, rankings and guidebook progress are not fabricated; debug mode
is not enabled. Real victories still control victory congratulations. Actual
talent upgrades, subclass/armor acquisition, daily/replay and duplicate-seed
validation keep their native rules. Information-preview availability does not
grant playable skills or points.

## Implementation and validation

The shared desktop initializer captures freshness before lock/audit creation and
receives the exact loaded Preferences object. Its empty-map guard also preserves
settings introduced after the initial filesystem check, including unflushed
in-memory preferences. The existing GUI launch overloads remain available;
internally the settings-ready callback now accepts Preferences directly.

`StartOptions` centralizes original native eligibility plus the explicit profile
override. The four advanced-option callbacks, hero selection reset, ranking seed
copy and information-preview gates use it; the original gameplay callbacks are
retained. INFO talent tier limits are separate from in-run UPGRADE limits.

| Executed check | Result |
| --- | --- |
| Focused initialization/policy tests | 22 passed |
| Native ranking StatsTab button tests | 2 passed |
| Full control-protocol Java | 20 passed |
| Full game-control Java | 636 passed |
| Full desktop-control Java | 241 passed |
| Full Java total | **897 passed; zero failures, errors or skips** |
| Desktop Python | 368 passed |
| Source-inventory Python | 8 passed |
| Public helper Python | 35 passed |
| Packaged profile refusal | 22/22; bytes, paths and mtimes unchanged |
| Fresh schema-12 profile | Created and reopened |

The final offline Gradle command used `--no-build-cache --rerun-tasks --no-daemon`:
all 39 tasks executed successfully, including package creation, runtime/test
classpath generation and the separate test-only instrumentation agent. Python
suites ran sequentially after classpath generation. Existing SQLite
ResourceWarning diagnostics did not fail the desktop Python tests.

The ranking tests invoke the real native StatsTab constructor with in-memory
record/hero data and a no-GL UI fixture. The seed-copy button is present for the
enabled setting without VICTORY, absent when disabled, and available with a real
VICTORY badge under native eligibility. Daily, invalid seed and missing hero still
hide it. Initial harness compilation/graphics-provider issues were corrected in
test code before the successful full gate.

The [exact source review](cli-9.0.3-unlock-source-review.json) covers 171 changed
visual sites and four replaced source identities in 23 existing groups, preserving
their classifications. The gate passes 13,530 sites / 4,851 groups without open
obligations. UI coverage has 1,917 records / 2,877 types and zero unmapped inputs:
four existing onClick eligibility bodies and 19 line offsets changed, with no
new/removed routes. Text coverage passes 206 operations / 1,239 files / 789
constructors. Snapshot static manifests remain unchanged. These inventory counts
are source evidence, not native runtime coverage claims.

## Actual package and runtime acceptance

The standard app reports `CLI.9.0.3 (protocol 9, game 4.0.2)`.

| Artifact | SHA-256 / identity |
| --- | --- |
| Production JAR | `099755c9028a48931048781273ad28e75998115968d96449c884b008c164ddc4` |
| Build ID | `1541fcc9d4c7ba61745178511e8ed10fa07a8bb1a950aec64ee55e41c5d751a7` |
| Help | `69db6cb373eaa060077fffb83ffffe78ea411889fba4756c27917bfaa5545b39` |

All 3,469 catalog entries were verified: 3,464 packaged entries plus five
native/build inputs. Source, bundled and printed help are byte-identical, as are
snapshot resources. Every compiled test-class path was checked absent from the
production JAR. All three launchers and libjvm are ARM64; strict deep ad-hoc
signature verification passed. This is local signing, not notarized distribution.

The ordinary controller smoke passed two sessions with 29 and 7 requests, four
save receipts, stale-revision rejection, save/reopen matching and complete raw
transport/viewer checks. The separately instrumented slow-quit smoke used the
bundled JVM and external test-only agent; original terminal receipt and process
exit completed without a post-quit query. A native future-daily fixture confirmed
the original warning and return action without changing the system clock; its
prepared progress is explicitly separate from fresh-default evidence.

The dedicated fresh-profile smoke passed **nine cases across 11 ordinary
production launches**, using the packaged controller and bundled JVM without
FixtureLauncher, prepared settings or badge injection:

- Each of the six classes started a separate new profile as a normal level-one
  hero with no subclass and the normal toolbar/backpack. Warrior saved/reopened.
- Each class exposed four Hero Info tabs and all nine base-class talent previews.
  Warrior's two subclass details each showed five talents; its three armor
  details each showed four talents.
- All nine challenges were initially off and the seed was empty. Every challenge
  was editable. Mask 511 and `ABC-DEF-GHI` survived scene reentry and process
  restart, then native controls cleared them. Full randomization confirmed two
  challenges; daily prompt/decline, start, existing-run refusal, erase cancellation
  and confirmed erase followed by replay all retained native behavior.
- A random seed observed through public save details triggered the native
  duplicate-regular-seed refusal and clearing behavior. Settings inspection after
  orderly exit was limited to each test's own isolated profile.

Three initial smoke assumptions were corrected without production edits: native
text-input Back is deliberately ignored, compact checkbox labels come from
resolved operations, and normal first-region chapter text still requires its
native Continue action. Original failed responses/logs are retained.

The ordinary GUI main executable was separately launched twice with an explicit
isolated `--data-dir`, without a controller or test agent. Screenshots verify the
normal title on first launch, selection of all six classes, nine initially empty
challenge choices, custom seed editing, daily prompt, full random dialog and
Cleric's four information tabs. Native English/fullscreen defaults were retained.
Hostile Champions and `ABC-DEF-GHI` remained selected after process restart;
the isolated settings contain unlock=true, intro=false, version=922, mask=128 and
the chosen seed, without language/fullscreen overrides. No run was started in
this GUI-only check; normal six-class starts are the separate CLI evidence above.

### GUI tool side effect

After the first isolated GUI quit, requesting another accessibility observation
unexpectedly launched the application's default entrypoint without `--data-dir`.
That extra process remained at its title screen; no game action was sent to it.
It was identified by its exact command/PID and closed. Its screenshot
`21-reopen-title.png` is explicitly excluded from acceptance evidence. The test
then rebound the sole remaining isolated process and completed restart checks.
No default-profile files were inspected, edited or restored by the validation
scripts; ordinary startup may have performed its own profile reads/writes, so
this run does not claim that the default profile was never opened. Further GUI
quits were verified by PID without post-exit app observation. All owned processes
exited. This exception was reported to the user and recorded in `gui-summary.json`.

All intentional validation cases use explicitly isolated profiles; none count as
a real playthrough. Local generated evidence is under
`desktop-control/build/cli9.0.3-validation/`: `full-gate.log`,
`java-test-totals.json`, `python-schema-daily-summary.json`,
`unlock-all-summary.json`, `gui-summary.json`, `controller-package.log`,
`slow-quit-package.log`, `package-identity.json` and their referenced raw fixtures.

The final delivery path is
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`.
Android/iOS builds are not part of this batch. Master remains the upstream
v4.0.2 commit. The initial implementation was committed locally without a push,
tag or publication; the later clean-rebuild push request is recorded above.
