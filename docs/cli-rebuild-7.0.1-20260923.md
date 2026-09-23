# CLI.7.0.1 implementation and macOS acceptance, 2026-09-23

This batch adds public evidence for combat visuals, repairs asynchronous quit
completion, removes UI identity-table object retention, and tightens Java and
Python validation. It retains protocol 7, audit schema 10, the independent CLI v7
profile and base game 3.3.8. No old profile migration or history rewrite is part of
this change.

The source review is complete at the snapshot recorded below. **All 64 native
fixture scenarios pass on one frozen runtime matching the final production build**,
including the 54 new scenarios and ten existing visual regressions. This is not
a claim that every producer lifecycle was executed or that a complete career/
playthrough was finished. Every run used an explicitly marked isolated test
profile with `test_fixture:true` and `counts_as_win:false`; personal profiles,
personal saves and private gameplay audit data were not used.

## Implementation

The public boundary and field details are documented in
[rendered combat observations](cli-combat-visuals.md). The main changes are:

- Charge/downed/facing evidence, summons, delayed rocks, pylon electricity and
  flow, beams/projectiles/chains, trap and arena/beacon markers, loot flares,
  character tint/opacity/paused appearances, and actual radial halo/ring geometry
  now enter the existing public observation or display history through their
  native rendering paths. The downed-ghoul path does not depend on membership in
  `level.mobs`. The persistent SuperNova halo is represented after transient
  countdown text has disappeared.
- Floating text retains independently visible text, color, icon and explicit cell
  anchors. Repeated equal text remains separate occurrences. UI projections retain
  warning colors, translated color runs, item/badge/Buff icons, actual Buff overlay
  pixels, spell brightness, action/attack portraits, boss warnings, quickslot
  markers/previews and displayed banners. Item status/count fields retain their
  existing string types.
- Current particle counts come from actual drawn children, not requested emission
  quantities. Current screen overlays and camera displacement come from submitted
  draw evidence, including native texture alpha, blending and pixel alignment.
  Quantitative history is explicitly labeled as a 250 ms sample; onset/new
  emission episodes/disappearance remain immediate. Current observations are not
  reconstructed from those sampled histories.
- Full transformed bounds, physical FOV, scene/map attachment, complete viewport,
  conservative UI occlusion and the camera transform actually used for drawing
  govern visibility. Particle warnings require an eligible actual contributor;
  hidden, stopped or frozen effects cannot block action completion indefinitely.
  Motion needs consecutive eligible draws in one visual lifetime. Camera shake,
  a hidden prior frame and object-pool reuse cannot manufacture direction.
- Display event types share one immutable FIFO. Bounded paired-SQLite batches are
  acknowledged only after commit; failed batches remain queued. Background work
  coalesces for 50 ms and yields after at most 64 events; request-boundary and
  finite final-tail drains remain immediate. The render thread performs no SQL,
  and the worker emits no unsolicited protocol reply. Existing durability settings
  and exact raw transport records remain intact.
- A timed-out quit remains queryable. The original scope/request owns completion;
  asynchronous completion persists a result without an unsolicited second reply.
  Only successful delivery of that quit's terminal response/receipt closes the
  normal session. Unrelated queries, rejection, UNKNOWN and output failures keep
  their separate meanings. New actions after successful quit pending delivery
  fail `SESSION_CLOSING`; EOF cannot replay an already dispatched quit.
- UI objects and callbacks use weak identity keys while current-frame execution
  references remain strong. Handles remain monotonic and are never recycled.
  Java/controller static validation and Python advertised-constraint checks cover
  explicit invalid `settle.rid`, dynamic UI arguments and UTF-16 text limits.
  Malformed environment keys and saved-receipt references retain the failing
  frame's decode identity and the enclosing original-action context.
- UI paint, text/style/provenance and bar pixels are frozen between native draw
  and update. The final review found that sampling after update could otherwise
  expose the next portrait tint, icon frame or Buff mask early. Scene, camera,
  child replacement, detach/reattach and pool lifetimes invalidate old evidence.
  Meaningful changes wait for their next draw in ordinary, continuous-handoff and
  cancellation paths. Explicit Title notice color-pulse annotations preserve the
  public colors while exempting only decorative phase from readiness and intent;
  ordinary warning colors remain protected.

No hidden AI target, revival relationship, cooldown, configured future blast
radius, private damage value or reconstructed item level is added as a visual
fact. Public before/after frames own independent data and templates; raw/reply
records and source/provenance diagnostics remain immutable.

## Exact source review

The javac inventory parses and attributes source without loading game classes.
The final reviewed snapshot covers **1,281 source files, 13,024 sites and 4,617
review groups**. The seven combat classifications are shown below; noncombat
surfaces use the separate `scope_exclusion` disposition.

| Classification | Sites | Meaning |
| --- | ---: | --- |
| `covered_current` | 4,697 | Current public rendering/observation retains the relevant evidence. |
| `covered_inspection` | 1,589 | An advertised native operation obtains equivalent information in the same state. |
| `covered_history` | 673 | A transient actual display is retained by public history. |
| `cosmetic_reviewed` | 484 | Exact source-specific decoration exclusion with a recorded reason. |
| `not_player_visible` | 1,609 | No independent pixels at this site, including audio-only and declaration/dispatch sites. |
| `required_uncovered` | 0 | No identified required combat visual remains unaddressed in this source snapshot. |
| `unresolved` | 0 | No unclassified current candidate remains. |
| `scope_exclusion` | 3,972 | Explicit noncombat menus/catalogues/documentation surfaces; not a claim that their pixels convey no information. |

The result is `checked:true`, with empty `unreviewed`, `removed`, `changed` and
`outstanding` arrays. Evidence is in
[coverage.json](../game-control/build/combat-visual/coverage.json)
and the checked-in
[exact review ledger](../game-control/src/test/resources/combat-visual-reviews.json).
Every site has an identity and enclosing-body digest; new, removed or changed
sites fail the gate. Regenerating an inventory does not regenerate an approval or
silently classify a missing route as covered.

Final review additionally attributes Game/Gizmo/Group frame and attachment
boundaries, RenderedTextBlock, rendered/displayed getters and explicit decorative
color annotations. It also corrected review wording/classification for 19 EmoIcon
sites, 41 FloatingText sites and 10 unattached Effects image-factory sites. EmoIcon
uses the existing entity/FOV/ancestor-visibility observation path; its entries do
not claim a new completed-draw history path. The correction deltas retained their
old group/site/digest preconditions and were reviewed before merging.

**`runtime_verified_by_inventory` remains `false`.** A source disposition and a
shared-renderer test reference do not assert that the corresponding actor, item,
room or Boss phase was executed. Equivalent inspection was accepted only for the
same displayed information, not for a generic description that omits a current
warning. Custom user-note icons are explicitly excluded as noncombat rather than
assuming their editable title is equivalent to the chosen icon.

## Final native fixture validation

**64/64 scenarios passed** sequentially on the single frozen runtime
`runtime-417b882b9dc54c6fa5dc7850d2b3ee8f`, matching final build
`a6c3a5b12548e38bd7626a7dfc7a0dd09b023caf00cd4e210ca6a717fd597749`.
The [aggregate report](../desktop-control/build/fixtures/runtime-417b882b9dc54c6fa5dc7850d2b3ee8f/native-validation-results.json)
records every original case result, exit status and retained profile. All 64
fixture JVMs and the coordinator exited 0. There were **zero automatic retries**,
64 isolated profiles and no source edits or rebuilds during this 11 minute
18 second run. The continuous-flow persistence case and complete paginated bomb
countdown history both pass on this final build.

| Final native family | Passed |
| --- | ---: |
| Combat cues and lifecycle | 26/26 |
| Text, item, Buff, HUD and projectile appearance | 13/13 |
| Actor/item states and drawn particle counts | 5/5 |
| Native halo/radial effects | 5/5 |
| Screen overlays, submitted camera and occlusion | 5/5 |
| Existing red/Goo/hidden/frozen/bomb gates | 6/6 |
| Dwarf King visible/hidden gates | 2/2 |
| Tengu trap visible/hidden gates | 2/2 |

The production classes are the verified final build; the native scenario runner
also loads explicit test fixture classes and prepared in-memory game objects.
These runs are separate from the unmodified packaged controller smoke. Their
`public-trace.jsonl` files preserve parsed complete responses, **not a claim of
byte-exact native SEND/RECV sidecars**. The packaged controller checks retain those
independent raw byte streams. The radial runner checks deep copies of original
frames after clean exit, as detailed below; it never substitutes later frames.

The maintained individual runners are reproducible after the classpath-writing
Gradle tasks above have completed, with one GUI runner at a time:

```sh
python3 desktop-control/src/test/python/combat_visual_smoke.py
python3 desktop-control/src/test/python/combat_appearance_smoke.py
python3 desktop-control/src/test/python/actor_item_visual_smoke.py
python3 desktop-control/src/test/python/radial_visual_smoke.py
python3 desktop-control/src/test/python/screen_visual_smoke.py
python3 desktop-control/src/test/python/visual_cue_smoke.py --cases red,goo,hidden,frozen,bomb,bomb-hidden
python3 desktop-control/src/test/python/king_visual_smoke.py
python3 desktop-control/src/test/python/tengu_traps_smoke.py
```

## Intermediate native fixture evidence

The following reports were read from isolated frozen test-runtime directories.
Each report records its full `build_id`, CLI version, profile, evidence and process
exit result. Build-ID prefixes below identify the differing intermediate builds;
the final package identity belongs to the final verification section.

| Family | Distinct passing scenarios | Passing evidence and intermediate build |
| --- | ---: | --- |
| Combat source/render scenarios | 26 | [25/26 report](../desktop-control/build/fixtures/runtime-2b7e37ae30b44d15b2662de692729daf/combat-visual-results.json), build `c71eedcbdffa…`; the failed `flow` case passed in its [1/1 retry](../desktop-control/build/fixtures/runtime-5cefc368b9bb47f0aabf215e9096a519/combat-visual-results.json), build `b2dd29310c32…`. |
| Native UI/display appearance | 13 | [12/13 report](../desktop-control/build/fixtures/runtime-d8f06f0f3d3e4d2b980018837cbadc59/combat-appearance-results.json), build `b2dd29310c32…`; `appearance-attackportrait` passed in its [1/1 retry](../desktop-control/build/fixtures/runtime-d38d9f4b73ed4ee8827a438ecab9af92/combat-appearance-results.json), build `996017af5aab…`. |
| Actor/item appearance and particle counts | 5 | [5/5 report](../desktop-control/build/fixtures/runtime-4a16b3a919a5458bb0ab8c67d5df6d8d/actor-item-visual-results.json), build `b2dd29310c32…`. |
| Radial halo/ring rendering | 5 | Three ring scenarios passed in the [3/5 report](../desktop-control/build/fixtures/runtime-3df1997cf511427787838458af332262/radial-visual-results.json), build `996017af5aab…`; both halo cases passed in the [2/2 retry](../desktop-control/build/fixtures/runtime-5e95e38d430d4a17b7ec0a7333da7f35/radial-visual-results.json), build `5834a76a393a…`. |
| Screen overlays and submitted camera displacement | 5 | [5/5 report](../desktop-control/build/fixtures/runtime-d7e58371feba4733913be76452b3d286/screen-visual-results.json), build `5834a76a393a…`. |
| **Union, deduplicated by scenario name** | **54** | Every listed scenario has at least one passing result; retries are not extra coverage. |

The 26 combat scenarios are `eye`, `ghoul`, `guardian`, `necromancer`, `spectral`,
`rocks`, `checked`, `pylon`, `sentry`, `flow`, `arcane-bomb`, `challenge`, `beacon`,
`golem`, `ring`, `beam`, `magic`, `surprise`, `wound`, `flare`, `spell`, `dm300`,
`chains`, `ripper`, `spire` and `eye-hidden`. The suite checks warning presence in
the original action's own response where applicable, history for transient draws,
visibility suppression, fade/clear behavior and selected modal restoration and
revision-stability cases. It does not repair a missing original warning with a
later state query.

Some cases prepare the native rendering source directly: `rocks`, `challenge`,
`beacon`, `golem`, `ring`, `beam`, `magic`, `surprise`, `wound`, `flare`, `spell`,
`dm300`, `chains`, `ripper` and `spire`. Those prove the prepared native source and
collector route, not the entire gameplay trigger leading to that source. Other
cases exercise native actor actions in the controlled fixture. Earlier report
labels did not consistently distinguish these two setups; the current runner and
this report do.

The 13 appearance cases are `appearance-floating`, `appearance-item`,
`appearance-buff`, `appearance-markup`, `appearance-freecast`,
`appearance-paidcast`, `appearance-quicktarget`, `appearance-attackportrait`,
`appearance-bosswarning`, `appearance-actionicons`, `appearance-projectile`,
`appearance-quickslot-preview` and `appearance-banner-boss`. They retain equal
floating values with different colors/icons, equal item counts with different
warning colors, actual Buff overlay pixels, translated style runs, ability
brightness, native portraits/markers, ordered preview slots, projectile history
and a single banner occurrence rather than one event per fade frame.

The five actor/item cases are `noisemaker`, `prismatic`, `sacrificial`,
`particle-counts` and `spectator`. Their reports distinguish the native alarm,
paused image fade, sacrifice mark/density, actual count differences and icon-less
pause/dark tint. Particle measurements are stochastic visible counts, not exact
hidden strength or sacrifice progress.

The radial cases are `supernova-halo`, `supernova-halo-edge` and
`blast-radius-1/3/6`. The normal halo case exercises a native tracker tick and its
persistent rendered halo. The edge case explicitly injects a renderer alpha
boundary and checks the native gradient sample; it does not claim normal gameplay
necessarily produces that alpha combination. Ring assertions measure current
rendered size and fade, not a configured future maximum. The two halo retries
report `gui_assertion_timing:"original_response_frames_after_clean_child_exit"`:
GUI state checkpoints are deep copies of the original response frames, checked
against the isolated audit only after a clean child exit. The original request
and frame identity are retained. No later observation is used to fill a missing
initial warning.

The five screen cases are `screen-flash-additive`, `screen-flash-normal`,
`screen-shake`, `screen-flash-below-modal` and `screen-flash-above-modal`. They check
normal/additive rendering, actual submitted displacement, draw-order occlusion,
history and the end of observed effects. Ending an observed episode due to
occlusion does not imply that its underlying animation ended.

The fixture JSON is retained under the ignored build directories. It is local
validation evidence, not a shipped public profile or a gameplay achievement.

## Retained failures and corrections

Failures remain in their original reports; successful retries are additional
records rather than edits that turn an earlier failure into a pass.

| Earlier evidence | Observed failure | Correction and later evidence |
| --- | --- | --- |
| [0/11 early combat report](../desktop-control/build/fixtures/runtime-9e73d4c31f3f457397ac75eb3e2b3455/combat-visual-results.json), CLI.7.0.0 | The acceptance reader treated a page list as an event object (`TypeError`); `flow` also encountered a SQLite lock. | The runner expands event pages before examining rows. This report is historical harness evidence and is not counted as current success. |
| [9/22 combat report](../desktop-control/build/fixtures/runtime-b6d8d785c6314efa82a2a5a45c605912/combat-visual-results.json) | Warnings or transient history were absent from the expected original frame while the native full-screen scene fader still covered the prepared fixture. | The test launcher defers fixture injection/after-frame setup until the native fader ends and snaps the camera to the hero. Production visibility gates were not relaxed. The subsequent 26-case report and flow retry provide the passing evidence. |
| [7/10 appearance report](../desktop-control/build/fixtures/runtime-91ca0c1c5b0141ea9b6b61e921c76625/combat-appearance-results.json) | Item-count, markup and quick-target fixture assertions failed. | Item validation compares the actual equal displayed count (native stone quantity is 3), rather than hardcoding 1. The markup fixture uses a native Degrade Buff description with actual highlights; ToxicImbue has none. The later 12/13 appearance report contains passing results for all three scenarios. |
| [12/13 appearance report](../desktop-control/build/fixtures/runtime-d8f06f0f3d3e4d2b980018837cbadc59/combat-appearance-results.json) | The injected attack target had no native attack portrait. | Fixture setup now invokes the native `AttackIndicator.updateState` sequence after target injection. The separate attack-portrait retry passed; this was a fixture setup correction. |
| Combat `flow` and the first two halo cases in the reports above | SQLite identity checks encountered `database is locked` during continuous display persistence. | Display persistence was changed to bounded immutable FIFO batches with 50 ms background coalescing and request fairness. The flow and halo retries passed. Radial GUI checkpoint reads additionally occur after clean exit against deep-copied original frames, as described above. This timing change is disclosed rather than presented as a live-read assertion. |

These corrections have different meanings: some repair fixture assumptions or
initialization, while display persistence changes address a real worker/SQLite
contention path. Their results are not collapsed into a claim that all early
failures were game defects or that all were harmless harness issues.

## Final regression and package verification

The final coordinated build ran without task/build-cache reuse and passed in
**1 minute 11 seconds**, with **38 tasks executed**:

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath :desktop-control:crashAgentJar \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

The full build includes both source gates: text composition checks cover 1,197
core files, 212 operations and 769 catalog constructors, with no unknown or stale
exclusions; the combat review gate matches the exact snapshot above. Final Java
module counts are **19 control-protocol + 442 game-control + 216 desktop-control
= 677 passed**, with zero failures, errors or skipped tests. Logs and counts are
retained in `desktop-control/build/validation-7.0.1-20260923/`.
Final Python discovery passes **273 tests**. Existing compiler deprecation/
unchecked warnings and the intermittent Python SQLite fixture ResourceWarning
remain visible; failures are not suppressed. The 133 directly compiled UI and
draw-boundary tests are a targeted repeat subset, not additional distinct Java
tests to add to 677.

Two preceding attempts remain recorded. One exposed a missing `markup_segment`
test-matrix entry and a real inherited-partial-provenance loss, now fixed with a
failing-before/passing-after regression. The other detected the new report link
before the report file had been written; the final full build verifies the link.
No assertion was disabled to obtain the passing gate.

| Final gate | Result |
| --- | --- |
| Java `control-protocol`, `game-control`, `desktop-control` suites | 677 passed; zero failures, errors or skips. |
| Python full discovery | 273 passed. |
| macOS ARM64 rebuild and build catalog | 3,341 entries match current sizes/hashes and reproduce the final build ID. All 2,876 catalog production classes and 13 runtime service classes match packaged bytes; 322 test/crash-agent classes are absent. Release and packaged JARs are byte-identical. |
| Actual packaged `spdctl --version` | `CLI.7.0.1 (protocol 7, game 3.3.8)`; schema 10. |
| Packaged `--help` byte identity | Source, unique JAR resource and actual stdout are identical: 49,657 bytes, SHA-256 `08a3adb7f28df2337848c3a42e31955e5541236057b8777503ad11704ad8bfa2`. |
| ARM64 runtime/native contents and code signature | 35 Mach-O files, all ARM64; deep strict codesign and plist validation pass. Ad-hoc local signing, not notarization. Bundled Java 25.0.4. |
| Packaged controller start/normal quit | Passed: two sessions, 22 + 7 wire requests, four successful save receipts, native inventory/item inspection, normal quit and matching saved restart. Old revision rejected locally before sending. |
| Packaged slow-quit component integration with test injection | Passed: 13 wire requests, one initial `in_progress`, two settle calls, an `EXECUTING` receipt while held, then the original quit's `COMPLETED` receipt and exit code 0. No second unsolicited reply, forced termination or state query after quit. |

The final application is
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`. Its build ID
is `a6c3a5b12548e38bd7626a7dfc7a0dd09b023caf00cd4e210ca6a717fd597749`;
release JAR SHA-256 is
`900b226fa01752dd9102c11df867f1441fa0828e14c2f6ff0dc462d8ad52f335`.
The [read-only package verification summary](../build/validation-7.0.1-20260923/package-verification-draw-cache/summary.json)
retains the complete comparisons and architecture manifest.

The [ordinary controller report](../desktop-control/build/fixtures/cli7-controller-0cf59bc37ad44d4fb839a02d77f00540/result.json)
retains independent exact child transport and controller wrapper bytes. Both
process sessions exit normally and no state query follows successful quit. Plain
viewer text and raw bytes remain unchanged; only numbered SEND/RECV headings are
bold bright cyan. This short package run did not generate continuous activity;
the separate slow-quit test below and controller/render-boundary regressions
provide their own evidence.

The package view check captures one actual full/src frame and reprojects that
same frozen content through the exact packaged JAR. A test-only adapter compiled
and run with the host JDK must first reconstruct the original full/src content
exactly, then prove play/full equality, retaining all colors, geometry, timestamps,
provenance and diagnostics. Adapter/JAR hashes, host runtime, frozen input and
outputs are saved alongside the capture. This is explicitly **offline packaged
codec component replay**, separate from real controller transport. Replayed
frames are never installed as live state. Two earlier package attempts remain
recorded: they compared distinct live frames and correctly detected native
overlay/particle color changes despite unchanged revision. No visual fields were
discarded to make that invalid cross-frame comparison pass.

The bundle includes the standard `java.instrument` runtime module so the separate
test-only agent can exercise this exact bundled JVM. The first injected attempt
on the previous runtime failed before application startup because that module's
native library was absent. No delay switch, barrier or test agent is shipped in
production classes. Ordinary startup does not load an agent.

The [injected slow-quit report](../desktop-control/build/fixtures/slow-quit-package-5b7e46382fb7461a95e8504a1579089d/result.json)
records the barrier in the real render-thread `GameController.perform` callback,
after native saving and setting exit intent. It crossed the production 30-second
deadline, kept the controller channel usable, retained successful save receipt
`p2` with origin `s2/t1.7`, and released the barrier only after observing the pending
receipt. This is explicitly **instrumented component integration** on the actual
packaged controller/native relay/bundled JVM, distinct from ordinary package
startup. The agent, barrier files and profile are test-only; no save or game-state
injection was used. Original child SEND/RECV/stderr bytes are retained independently
from controller wrapper bytes.

The [Unicode/spaced-path raw package check](../desktop-control/build/fixtures/packaging7.0/raw-f31d86c0b3144eee85a6ea1c379d64c6/result.json)
passed five exact input frames, invalid UTF-8, duplicate request identity, profile
lock contention, EOF without unsolicited output, a final frame without LF,
bundled JVM/SQLite, and isolated paired-database integrity. The
[old-schema launcher check](../desktop-control/build/fixtures/packaging7.0/old-schema-852caaad355f4e1ea4ecaf874f807af0/result.json)
rejected each of schemas 1–9 before profile writes, preserving fixture bytes,
directory entries and timestamps.

Focused regressions additionally cover strict cross-language validation, malformed
frame evidence, weak identity lifetime, immutable/frozen appearance projection,
current-versus-history independence, exact quit ownership, output/audit failures,
EOF, retained FIFO prefixes and submitted-render visibility. Recording-shader unit
tests execute native effect/Image draw methods with controlled textures; they are
not GPU/framebuffer proof. Native desktop fixture results above provide a separate
layer of actual-render evidence for the listed cases.

## Limits and release boundary

This batch closes the identified source-level combat-visual gaps in the reviewed
snapshot. It does not prove full pixel equivalence, all careers, every item roll,
every special room, all Boss phases, every restore path or every platform. Source
site counts, shared-sink tests and fixture scenario counts are three distinct
measures and must not be added together as gameplay coverage.

The accepted visibility policy deliberately omits partial viewport fragments and
information behind conservative modal/HUD blockers or outside current FOV.
Quantitative public history is sampled, not a complete frame recording. Assets
that cannot be safely identified remain explicit partial evidence. None of these
boundaries permits filling omitted values from private actor fields or an older
cached observation.

The scoped signed local commit, its verified signature and the final worktree
state are reported at handoff. Push, tags and publication require a separate user
request and are not part of this acceptance record.
