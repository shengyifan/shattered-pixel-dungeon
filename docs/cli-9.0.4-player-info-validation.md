# CLI.9.0.4 player-information fixes

This batch implements the approved follow-up to the 2026-10-11 player-information
audit. Baseline checkout: `bbae9a3a6`. The release identity is **CLI.9.0.4 /
protocol 9 / audit schema 12 / game 4.0.2 / code 922 / trace format 1**. The
profile directory remains `Shattered Pixel Dungeon CLI v9`; no migration or
gameplay, map-generation or save-rule change is introduced.

## Clean rebuild 2026-10-11

After the implementation below, the user requested removal of all previous
project build products, a fresh macOS CLI build and Git push. Runtime source
remains `2e6f9aaf49dedc6a63b21e4db087ecf1ceea109a`; CLI, protocol, audit schema,
game and profile identities are unchanged.

Preflight confirmed no running game/controller or Gradle process used the old
outputs. Cleanup removed **14 generated directories and `ios/robovm.properties`**:
**8,809 regular files / 970,119,182 logical bytes**. Every root was project-contained,
ignored, untracked and not symlinked; 36 nested JRE legal symlinks were removed
without following them. This includes old package/runtime/fixture outputs,
project-local Gradle state and Python bytecode caches. Tracked wrapper/launcher
inputs, committed research evidence, personal profiles, external logs and global
dependencies were preserved. Gradle regenerated the iOS properties file during
configuration; no mobile build was run.

The fresh offline gate used `--no-build-cache --rerun-tasks --no-daemon` and
executed all **39 tasks**. **935 Java tests** passed (20 protocol, 665 game-control,
250 desktop-control), with zero failures/errors/skips. After runtime/test classpath
generation, sequential Python suites passed **386 desktop / 8 source / 35 public
helper** tests. Existing SQLite ResourceWarning diagnostics were non-failing.
Combat/visual, native UI route and text-source gates also passed.

The standard ARM64 bundle reports `CLI.9.0.4 (protocol 9, game 4.0.2)`.
Freshly calculated production JAR, launcher, help, catalog and build-ID hashes
match the implementation package below. All **3,475 catalog entries** match their
current inputs, with **3,470 corresponding packaged entries** checked byte-for-byte
and five native/build source inputs verified separately. Both snapshot manifest
resources match. All **507 compiled test classes and 20 agent classes** are absent
from the production JAR. All **35 Mach-O files** are ARM64-only; plist validation
and strict deep ad-hoc signature verification passed. Only the new standard app
image remains in the generated package outputs.

New ordinary controller acceptance passed two bundled-controller/JVM sessions,
**29 + 7 requests and four save receipts**, old-revision rejection, save/reopen
matching, and exact raw/viewer checks. The separately instrumented expression
probe passed all five cases: ritual, journal, recipes, Crystal and actual native
audio. It exercised nine alchemy pages, four catalog categories, four recipe
modes, all three Crystal stages and alarm/disabled/zero-volume/alarm event counts
**1 / 0 / 0 / 1**. Its controller exited normally and both recorded controller/game
PIDs were confirmed absent. The original feature-coverage limits below still
apply; these isolated fixtures are not a real playthrough.

The separate slow-quit package fixture also passed: initial `in_progress` was
exposed while the native callback was held, bounded settle returned ordinary
`action_pending`, and the original request reached `COMPLETED` before process
exit code zero. No unsolicited reply or post-quit state query occurred. A final
process check found no remaining game/controller or Gradle processes, and the
production JAR hash remained unchanged after all three package probes.

Current generated evidence is under
`desktop-control/build/clean-rebuild-20261011/`: `cleanup.json`, `full-gate.log`,
`java-test-totals.json`, `test-summary.json`, `python-desktop.log`,
`python-source.log`, `python-helpers.log`, `package-identity.json`,
`controller-package.log`, `expression-package.log`, `expression-summary.json`,
`slow-quit-package.log` and `final-process-and-quit-check.json`.
New runtime artifacts are `desktop-control/build/fixtures/cli9-controller-0772e9fad7b14662923f7cbde4fce07d`
and `desktop-control/build/fixtures/cli904-expression-9018571c82034b6fbb3478f7c1252fc4`,
plus `desktop-control/build/fixtures/slow-quit-package-639ae360183d4510b2c05cbb0ba70c87`.
Earlier fixture and validation directories cited in the implementation record
below were deleted by this requested cleanup and are historical references;
the standard package path now holds the fresh image. The maintained tests, source
review and committed acceptance report preserve the preceding results' meaning.

The authorized delivery target is `origin/feature/mac-cli`; tags and remote
release publication are outside this rebuild request.

## Implemented behavior

- The Python decoder accepts source-selected Crystal stages in every glow
  location, including nested semantic icons, standalone actions and frozen
  snapshots. Explicit native unmapped partial evidence survives without accepting
  arbitrary missing variants or unknown stage/type values. UI-node health bars
  and turn progress reuse the existing strict displayed-value validators.
  A present glow stage must also match its native kind: explosive heat accepts
  cool/warm/hot and Crystal condition accepts intact/flawed/cracked. Resin or an
  orphan stage is rejected; a missing stage is still neither required nor filled.
- StyledButton/GridItem preserve native icon ownership, page selection and
  catalog/read state; GridItem keeps its original entry/select behavior. Unknown
  catalog identities remain placeholders. Image copy/crop bindings are checked
  against the displayed instance and invalidated on replacement.
- QuickRecipe records its existing per-material and aggregate material check,
  separately from enabled/ops. No hero means no material-check fact. Observation
  neither reruns recipes nor exposes unidentified inventory types.
- RitualMarker contributes its original displayed name/description for the inner
  nine known cells. Optional terrain override leaves the already public base
  terrain unchanged; existing Smithy 23/39 overrides remain intact. No arbitrary
  custom getter/image creation or private ritual-coordinate lookup is used.
- Native ALERT playback becomes a location-free `game.audio` occurrence only
  after desktop source/state/gain verification. The returned native ID, arguments,
  playback order and native exceptions are preserved. Sample and delayed-SFX
  monitors are released before optional observation callbacks. Each playback is
  independent; scheduling a delayed sound alone emits nothing. No private
  source, asset, position or gameplay timer is serialized.
- Audio uses the existing ordered event FIFO, paired transaction and explicit
  event cursor. It neither changes the decision revision independently nor
  produces unsolicited NDJSON. Native playback does not establish physical
  audibility at the user's speakers.

## Verification record

The execution evidence is stored under `desktop-control/build/cli904-validation/`.
This is fixture evidence, not a nine-challenge playthrough. Tests use new,
explicitly marked isolated profiles and never read a personal save/audit pair.
Historical audit probes and their failure results in `memory/` remain unchanged;
the fixes are checked by maintained regression tests instead of rewriting those
old results.

Focused regular Gradle tests passed **37 game-control + 9 desktop-control**
checks before the final full gate. Separate native producer-to-Python fixtures
exercise **30 complete Java-generated responses**, including Crystal and unmapped
glow, all three views, independent actions and frozen snapshots.

| Executed gate | Result |
| --- | --- |
| Full control-protocol Java | 20 passed |
| Full game-control Java | 665 passed |
| Full desktop-control Java | 250 passed |
| Full Java total | **935 passed; zero failures, errors or skips** |
| Desktop Python, final decoder | 386 passed |
| Source-inventory Python | 8 passed |
| Public observation helper Python | 35 passed |
| Combat/visual source review | 1,343 files / 13,595 sites / 4,920 groups; no open, changed or unreviewed obligations |
| Native UI route inventory | 2,880 types / 1,917 records; no unmapped routes |
| Text-source review | 210 operations / 1,239 files / 789 catalog constructors; no open obligations |

The final offline Gradle gate used `--no-build-cache --rerun-tasks --no-daemon`:
all **39 tasks executed** successfully, including package creation, runtime/test
classpath generation and the separate test-only instrumentation agent. Python
suites ran sequentially after classpath generation. Existing SQLite
ResourceWarning diagnostics did not fail the desktop Python tests. Subsequent
package-driver corrections changed test fixtures only; those test classes were
recompiled and the final package probe passed with the same production JAR.
Final decoder review added kind/stage pairing and two regression methods covering
18 malformed combinations across 15 surfaces and live/before/after frames. The
desktop Python gate was then rerun in full. All **93 original received responses**
from the successful actual-package probe also decode with this final client;
original transport bytes and parsed inputs remain unchanged. This is offline
decoding of captured bytes and sends no game action. Java/package content did
not change during this final Python-only check.

The new maintained regressions cover:

- Crystal stages through native item/UI/world producers, Java compression,
  Python expansion, all views, standalone actions and independently frozen
  snapshots. Illegal stages/kinds, malformed icons, health samples without valid
  integer `incoming_dot`, and out-of-range turn progress fail with original
  response identity/data preserved.
- Across the native regressions and package probe: all nine alchemy pages, four catalog categories, primary/secondary icons,
  key counts, native selected false, catalog/document states, image replacement
  and cropping, and GridItem entry/select behavior.
- Recipes missing a kind or quantity, unidentified ingredients, sufficient
  ingredients outside alchemy, actual native fill in alchemy, no-hero previews
  and reconstructed windows. Material truth and action availability are separate.
- Ritual inner/outer cells, visible/visited/mapped/unknown scope, overlay order,
  detach/visibility, unchanged base terrain and immunity to private ritual-field
  mutation.
- Disabled, zero-gain, unloaded, failed and unverifiable audio; no-device ID zero
  versus mapped live ID zero; delayed playback, repeated alarms, observer failure,
  lock release, FIFO ordering, paired audit storage and close-time draining.
  Events contain no position/source/asset and do not change `rev` or trigger an
  unsolicited response.

Test sources are `NativeJournalRecipeSemanticsTest`,
`RitualMarkerObservationTest`, `NativeItemGlowFixture`,
`SoundPlaybackObservationTest`, `AudioOccurrenceTest`,
`DesktopSoundPlaybackTest`, `AudioEventsSessionTest`,
`test_cli904_semantics.py` and `test_native_item_glow.py`.

Initial test setup problems and failed attempts remain in the generated evidence.
The test helper initially referenced nonexistent `Sample.isLoaded`; one Gradle
invocation misplaced `--tests`; and an existing version assertion still expected
CLI.9.0.3. These were corrected before the successful final full gate. Package
probe corrections addressed viewport-clipped catalog icons, the public menu/run
scope needed after an explicitly injected scene switch, an initially selected
Crystal window without a semantic icon producer, and a blind inventory toggle
that closed the already-visible native sidebar. The recipe knowledge fixture
also switched from Warrior-known Healing to Purity. Each failed run stopped and
retained its original wire evidence; the successful probe used a new isolated
profile. None of these corrections changed production gameplay or relaxed the
decoder to accept missing facts.

## Source review and public boundary

The exact source-review record is [cli-9.0.4-source-review.json](cli-9.0.4-source-review.json).
UI routes remain a static inventory, not runtime acceptance. The semantic review
separates the four reviewed ALERT call sites from other audio-only playback;
adding alarm events does not declare every sound source observable or identify
the caller of an alarm. Updated boundaries cover the optional callback and the
read-only desktop playback verifier.

The native-window and audio package probe is explicitly instrumented: a separate
test-only agent uses a unique marked fixture directory and one-shot commands at
the actor boundary. Production classes and JVM come from the actual ARM64 app;
the test helper/agent never enters the production JAR. Ordinary controller
acceptance is reported separately.

## Actual ARM64 package

Preflight found no process using the standard image; the selected generated
output was project-contained, ignored, untracked and not symlinked. The standard
bundle was rebuilt at:

`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`

Its actual `Contents/MacOS/spdctl --version` reports
`CLI.9.0.4 (protocol 9, game 4.0.2)`.

| Artifact | SHA-256 / identity |
| --- | --- |
| Production JAR | `636c7d800eeae29ac909b54a4bcdc7f8d6359892ff1df3aed66cb46779b14fcf` |
| Build ID | `b0c78afe63b226c7611aea525a3a88d7e0f34cb5624a3cca486b889089eb0627` |
| Help | `fc6e33d77fa255799bc0684c77f06a521beeb7fb40cbdb7e3ed000f94ab17f63` |
| Build catalog | `38c2b22b5677681fd2e6ddffc6100d123888a2d9df62612f118a19980365a692` |

All **3,475 catalog entries** match their inputs: **3,470 packaged entries**
plus five native/build inputs. Source, bundled and printed help are byte-identical.
All **527 compiled test classes** and the separate fixture agent are absent from
the production JAR. All **35 Mach-O files** in the image are ARM64-only; property
list validation and strict deep ad-hoc signature verification passed. Ad-hoc
signing is local validation, not notarization or remote publication.

The ordinary, uninstrumented controller smoke passed two sessions with **29 + 7
requests and four save receipts**, including stale-revision rejection,
save/reopen matching, and raw-transport/viewer checks. Both sessions quit cleanly.
Its isolated evidence is
`desktop-control/build/fixtures/cli9-controller-c6d9bda019984169bd07c395c203bf27`.

The separately instrumented native expression probe passed all five cases on the
actual bundled JVM and production classes:

- RitualMarker survives play/full/src with its native floor terrain intact.
- All nine alchemy page icons and four catalog categories retain selection.
  Three long catalogs scroll down and back; the 36 document entries fit without
  scrolling. Currently clipped icons are absent and newly
  displayed icons acquire their own bindings. Known/unknown catalog and read/unread
  document states are covered together with the native regressions: this package
  run observes `undiscovered`, `discovered` and `read`; `unread` is a deterministic
  GridItem regression. The package also preserves key counts and primary icons;
  three positive secondary icons occur in the consumables frames, while exact
  secondary-image binding is asserted by the native regression. Two visible Notes
  entries retain their entry role, and selecting one opens native details.
- Four recipe modes pass play/full/src: unknown ingredients, known ingredients
  outside alchemy, native alchemy and no-hero preview. The enabled native fill
  operation populates the actual alchemy input slots.
- Intact/flawed/cracked Crystal each passes play/full/src, with the visible
  InventoryPane item slot bound to the same frame's inventory item.
- Actual OpenAL playback produces alarm event counts **1, 0, 0, 1** for
  alarm/disabled/zero-volume/alarm. The two positive events have ordered sequences
  **11 and 12** and only format/kind/basis/time data. All four observations retain
  revision **r18**. Native playback IDs 3 and 5 are fixture-only evidence and are
  absent from the public events; ID-zero edge cases are deterministic desktop
  verifier regressions, not a claim about this hardware's first playback ID.

The final probe evidence is
`desktop-control/build/fixtures/cli904-expression-382665b26d1144e4916f12a97bb81c8b`.
Its result records `test_fixture:true`, `counts_as_win:false`,
`instrumented_actual_package:true`, and `personal_profile_or_private_audit_reads:false`.
The controller exited with code zero; recorded controller/game PIDs 25178 and
25180 were independently confirmed absent after cleanup. The production JAR hash
remained unchanged after both package probes. This is feature acceptance in
prepared fixtures, not complete native-UI coverage or a real game victory.
The Crystal package case uses the already-visible InventoryPane rather than a
modal bag. The alchemy case fills inputs without brewing an item. Of the 24
explicit ritual/recipe/Crystal play/full/src samples, 21 retain existing
`unmapped_text_tone` partial diagnostics on numeric text; the text and new semantic
facts survive and decode correctly. No claim of universally complete presentation
or mapped UI colors is made.

Key generated records under `desktop-control/build/cli904-validation/` are
`full-gate-final.log`, `java-test-totals.json`, `python-desktop.log`,
`python-source.log`, `python-helpers.log`, `package-identity.json`,
`controller-package.log`, `expression-package-success.log` and
`expression-final-summary.json`, with final-client checks in
`python-desktop-final.log` and `final-decoder-wire-replay.json`. These generated records are kept locally and
are not included as profile/database artifacts in the source commit. The
maintained test sources, this acceptance record and exact source review remain
in Git after generated output is cleaned.

## Reproduction

Use the [maintenance skill](../skills/shattered-pixel-dungeon-cli-release/SKILL.md)
for the full Java/Python/build gate and safe package output selection. Generate
runtime/test classpaths before dependent Python suites. Then use the actual bundle
with `controller_package_smoke.py` and `cli904_expression_package_smoke.py`;
the latter additionally requires the test-only `crashAgentJar` task and test classes.
The expression driver validates current advertised operations/revisions and does
not replay a failed or uncertain action.

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath :desktop-control:crashAgentJar \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s game-control/src/test/python -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s skills/pixel-dungeon-run-assistance/tests -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 desktop-control/src/test/python/controller_package_smoke.py \
  --bundle 'desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app'
PYTHONDONTWRITEBYTECODE=1 python3 desktop-control/src/test/python/cli904_expression_package_smoke.py \
  --bundle 'desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app'
```
