# CLI.8.0.0 semantic observation implementation

Historical implementation acceptance: the generated attachments referenced below
were removed from the workspace in the user-requested
[2026-09-24 clean rebuild](cli-rebuild-8.0.0-clean-20260924.md). The dated results
remain an implementation record; use that rebuild report for the current package.

Status on 2026-09-24: the full Python, Java, static source-review and clean
ARM64 build gates passed on flow build
`35742561a7b6ae17c8db504c9ce58d7f6ba838ef65f33a1df8ea1549cfffae7c`.
Earlier package candidate `d113…` passed version/help/contents/signature
readback and several native scopes. The final flow build has one changed
production class and passed its own package readback, basic
`info`→`state`→`quit` lifecycle and targeted electricity-flow native retest.
The earlier candidate's instrumented slow quit passed. All 63 logical native
scenario identities now have passing evidence across the two builds; this is
neither a single-build full sweep nor a real playthrough.
This document keeps those evidence scopes separate.

## Version and profile boundary

- CLI version: `CLI.8.0.0`; wire protocol: integer `v:8`; audit schema: 11;
  game version: 3.3.8. The transport trace format remains 1.
- New default profile:
  `~/Library/Application Support/Shattered Pixel Dungeon CLI v8/`. The v7
  profile and its audit/raw records are left untouched.
- `control --machine` and `run --machine` preflight the selected profile before
  locks, defaults, child creation or emergency writes. A paired audit database
  with an older schema is `AUDIT_SCHEMA_UNSUPPORTED`; a nonempty profile without
  a valid audit pair is `CLI_PROFILE_UNSUPPORTED`. An absent/empty profile or an
  intact schema-11 pair is eligible. There is no migration or old-wire fallback.
- `--help` and `--version` are read-only. Successful package validation must
  compare the emitted help with `docs/cli-help.md` and check the rebuilt
  executable's reported version and contents; source constants alone are not
  sufficient evidence.
- `info.capabilities` advertises `gameplay_facts`, `semantic_ui`,
  `semantic_feedback_events` and `world_cues_fov` in place of
  `rendered_combat_visuals`, `rendered_ui_appearance`,
  `floating_text_events`, `visual_metrics` and `banner_events`.

## Semantic public observation

Every live play/full/src observation and independently frozen before/after
snapshot is complete for its own scope and revision. Hero, inventory, map,
entities, UI, persistence and the complete ordered `acts` table remain public
as applicable to that scene. The protocol-7 same-observation encoding rules
carry forward: action/inventory/UI node templates, node `ops` indexes into the
same frame's ordered actions, item labels, map rows and local dictionaries must
be expanded using that response only. Full/src use the same structural encoding;
they add explicit defaults/diagnostics or public text provenance, not ordinary
rendering detail. Protected fields and diagnostics remain addressed at their
actual paths. Missing, null, false and zero have distinct documented meanings.

| Field | Public meaning |
| --- | --- |
| `inv[].shown` | Current public status/extra/level/symbol/variant/counter strings, strength estimate, flags or semantic badge; uncertainty is retained. |
| `buffs[].shown` | Current public status/extra/level/symbol/variant/counter strings and `progress:{covered,total,basis:"displayed"}` when observed. |
| `health_estimate.samples` | Distinct visible `{total,filled,with_shield,basis:"displayed"}` bar samples, not exact hidden HP. |
| `hero.turn_progress` | `{sweep}` for the current public turn/progress indicator. |
| `ui.feedback` | Current semantic floating/log/banner feedback list, separate from queried event history. |
| `ui.nodes[].subject` | Explicit current-frame hero, item, hero-buff, entity or entity-buff reference. |

An item `shown.strength` is `{value,estimated,insufficient?,mastered?}`;
`estimated:true` must remain an estimate. `shown.flags` is an ordered list of
public semantic flags. Source-bound `shown.broken_seal:true` and
`shown.lit_candle:true` preserve visible armor-seal and ceremonial-candle
indicators. An `item_status` world cue can carry these flags for a visible
heap item. `shown.glow` carries only reviewed named variants, such as
`explosive_heat` stage or `resin_fortified` hint; pulse timing, hidden
durability and resin count are absent. Optional
`shown.status_kind:"quantity"` comes only from a bound native `Item.status`
or `MissileWeapon.status` quantity declaration, and only while the exact
captured text matches that declaration. The merger may omit duplicate
`status`/`status_kind` only when this role and current `qty` agree; special,
ambiguous or unknown status text remains explicit. A numeric artifact charge
or cooldown is not coalesced with a quantity of the same text. Warrior Shield's selected
native branch supplies `shown.counter_kind` and, for its small bar,
`shown.progress_kind`, each `shield` or `cooldown`; the merger does not infer
meaning from the numeral. The same displayed `"2"` can name either branch;
a displayed `"-2"` remains `cooldown`. This exposes no invisible exact counter.
`ui.feedback` entries use `kind:"floating"`,
`kind:"log"`, or `kind:"banner"`, with public text/cell/symbol or protected
diagnostics when applicable. Icon-only floating feedback retains a symbol even
without text. Its entries are current feedback; an event query
returns historical occurrences independently.

Subject forms are `{kind:"item",loc}`, `{kind:"hero"}`, `{kind:"hero_buff",index}`,
`{kind:"entity",index}`, and `{kind:"entity_buff",entity,index}`. Indexes
refer to the current observation's relevant list. Unknown or ambiguous owners
remain unbound; a label, icon or position is never used to infer one. A native
icon may have a reviewed semantic `symbol` and optional `variant`, but no atlas
or pixel-frame identity. Operation availability still comes solely from
advertised `acts` and node `ops`, with all constraints preserved.

The standalone `actions` query omits hero/inventory/entity tables. A bound UI
node therefore materializes the direct same-frame target fact object as
`subject_data`, with no kind wrapper, and omits `subject`. An unresolved or
ambiguous target is `subject_data:null` plus local partial diagnostics with
`unresolved_subject`; its captured label, locator and operations survive.
The query must not reference a table it omitted. Node `gestures` may be omitted
only when complete native advertised `ops` already carry their meaning.

`data.cues.cues` is the current public world-cue list. It is sampled from
existing source facts under current map/FOV visibility, independent of camera,
viewport and UI occlusion. `data.cues.status` is `last_observed` or
`not_observed`; last observed semantic evidence is not a live action binding.
The optional `appearance` contains only reviewed cue qualifiers such as
`style`, `shape`, `paused`, `stage`, `cells`, `partial`, `count` or `symbol`.
Radial `shape` (`ring` or `halo`) and `coverage:"visual_extent"` are observed
indicators, not predicted damage. A beam
whose endpoint is hidden retains known cells and `partial:true`, without a
hidden source or endpoint. An aggregate visible flame `count` is semantic
evidence, not a generic particle histogram. `ward_state` can report a displayed
Ward `tier` and, for tiers 1–3, a `charge` fraction with
`basis:"displayed_brightness"`; it does not report hidden exact uses.
The selected brightness is rounded to an 8-bit display channel before
reporting. Kinetic buff `shown.charge` follows the selected
white-to-yellow-to-red gradient with `basis:"displayed_gradient"`, after its
relevant display channel is rounded to 8 bits. Neither field leaks a hidden
higher-precision meter.
`statue_armor` reports the armor tier displayed by the current statue frame.
`crystal_spire_state` reports the selected Blue/Green/Red spire `stage` as
`intact`, `cracked`, `damaged`, `heavily_damaged` or `destroyed`, without
reading exact spawner HP.
The current Spirit Arrow projectile appearance may carry
`nature_powered:true` when its source-bound leaf-trail branch is displayed;
ordinary and sniper-special arrows omit the flag. This is sampled from the
native selected visual source, not reconstructed from a hidden attack mode.
Reviewed `GameplayBurst` kinds can carry
`appearance:{count,basis:"observed_particles"}` from actual visible native
contributors, never the requested emission count. The special
`sacrificial_flames` density cue retains its exact current observed `count`.
`game.visual` history samples quantity-only changes for all counted kinds at
250 ms and marks them with `sampled_quantities:{<kind>:{sample_period_ms:250}}`
when present. Discrete warnings, onset and disappearance remain immediate. There is
no generic `game.visual_metrics` event stream. Unknown indicators stay partial.
No cue may expose a hidden AI target, hidden map cell, remaining timer or
future outcome.

An unmappable native indicator retains `unmapped_indicator:true` and canonical
partial presentation with a diagnostic `field`, `code:"unmapped_indicator"`
and public source `indicator` variant. The fallback merges with existing
diagnostics; a missing Ward/statue tier is not guessed from private actor state.

New cue/floating event bodies use `gameplay_snapshot_v1`, banners use
`gameplay_occurrence_v1`, and gameplay logs use
`gameplay_log_snapshot_v1`. Event history and current `ui.feedback` are distinct.
Event draining remains a finite read operation, with no unsolicited pipe
response. Historical event records are not current revisions or targets.
New play/full/src responses, events and frozen snapshots omit atlas/frame,
texture/transform data, RGB/tint/opacity, generic particle histograms, camera/screen
effects and other ordinary rendering parameters. Historical `raw/reply` bytes
and the earlier CLI.7.0.1 record are immutable.

## Package boundary

The first isolated ARM64 package report was written to the ignored local
`desktop-control/build/validation-8.0.0-20260924/release-boundary/initial-package.json`.
That executable emitted `CLI.8.0.0 (protocol 8, game 3.3.8)` and advertised audit
schema 11. Its emitted help matched the bundled resource and build catalog;
ARM64 contents and a deep strict ad-hoc signature check passed. The report's
`source_help_equals_resource` was `false` because this source manual was edited
after that bundle was built. The final package must be rebuilt and checked again
against the final help bytes. Isolated startup checks rejected schema 1–10 and
non-audited nonempty profiles through both `run` and `control` without modifying
them. These initial checks do not establish native
gameplay or quit-lifecycle success.

The final flow-build package readback is in the ignored local
`desktop-control/build/validation-8.0.0-20260924/release-boundary/final-package.json`.
Its build ID is
`35742561a7b6ae17c8db504c9ce58d7f6ba838ef65f33a1df8ea1549cfffae7c`
and bundled JAR SHA-256 is
`5749cacae45b7cad041d229cda58593911e9507400aedc2f4fdc54fd895348b6`.
The executable emitted `CLI.8.0.0 (protocol 8, game 3.3.8)` and schema 11.
Source, bundled resource, emitted help and catalog agreed byte-for-byte on
SHA-256 `a39452cef37c841dc1eb8167cafe46e97f04d153795089a8a4eef0a036d571a9`.
The three executable paths and bundled JVM library were ARM64 Mach-O; no test
classes were present. Deep strict code-signature verification passed for the
local ad-hoc signature. On this new build, a direct `info`→`state`→`quit`
fixture passed three exact protocol-8 wire pairs and exited 0 without a
post-quit state query. It is recorded at
`desktop-control/build/fixtures/cli8-final-basic-9fe297d1f938457ea37b82039a3ea6a3/result.json`.
Across 5,487 packaged production classes, the only difference from candidate
`d113…` was `GameplayVisualKinds.class`; the other 5,486 class hashes were
identical. That class removes `electricity_flow` from intent-sensitive cue
kinds. The earlier candidate's full controller, raw, English, restart,
slow-quit and 12 profile-refusal fixture results remain evidence on `d113…`;
the class diff is the stated basis for reusing those unaffected lifecycle
conclusions. The targeted electricity-flow native retest is a separate gate.
This is local package evidence, not notarized distribution or a real clear.

## Validation ledger

The final clean 37-task Gradle/ARM64 gate for `3574…` completed in 1 minute
28 seconds. Its Java XML reports 19 `control-protocol` tests in 5 suites, 538
`game-control` tests in 86 suites, and 217 `desktop-control` tests in 29
suites: **774 tests in 120 suites, with zero failures, errors or skips**.
Local evidence is in the ignored
`desktop-control/build/validation-8.0.0-20260924/final-gradle.log` and
`final-java-tests.json` in that directory.
The same full rerun's static source-review gate reports **13,517 sites, 4,707 review
groups, 1,309 source files and 218 retired sites**. It is `checked:true` with
zero unreviewed, removed, changed or unresolved sites, and
`runtime_verified_by_inventory:false`. This is source inventory evidence, not
native gameplay coverage.
The catalog diff against `d113…` contains one changed packaged production
class: `GameplayVisualKinds.class`. Its evidence is in the ignored local
`desktop-control/build/validation-8.0.0-20260924/flow-catalog-delta.json`;
earlier native fixture results remain scoped to the older build.

The UI coverage inventory contains 1,910 static records. The source-text gate
covered 207 operations, 1,205 files and 769 catalog constructors, with zero
unreviewed entries. These are source checks, separate from the native fixtures.

The final full Python harness completed **286 tests in 34.031 seconds, all OK**
after the new status-kind, counter-kind and fallback checks. Its local log is
`desktop-control/build/validation-8.0.0-20260924/final-python.log`.

| Gate | Evidence to record | Current status |
| --- | --- | --- |
| Version/profile boundary | Direct and controller old-protocol/old-schema/nonempty-profile rejection before mutation; accepted empty/schema-11 profiles. | Final `3574…` Java/package version gate passed; candidate `d113…` passed 12 isolated refusals, with 5,486 unaffected production classes identical on final build. |
| Java projection | Semantic facts in play/full/src and independently frozen snapshots; no ordinary renderer fields; public FOV and partial gates. | Flow build `3574…` clean Java gate passed: 774/774 tests in 120 suites. |
| Standalone actions | `subject_data` carries current target facts without omitted-table references; ambiguous owners retain local partial diagnostics; complete `ops` preserve gestures. | Flow build `3574…` Java gate passed; older candidate controller independently decoded play/full actions (8 inline subjects, 54 facts) and executed with that reply's revision. |
| Python/client expansion | Strict protocol-8 decode, same-frame templates/refs, failure identity and wrapper outcome separation. | Final full harness passed: 286/286 in 34.031 s, all OK. |
| Event history | Semantic snapshot/occurrence shape, bounded draining, immutable historical raw/reply. | Flow build `3574…` Java and targeted flow fixture passed; older candidate raw/viewer/lifecycle fixtures passed. |
| Source review | Source-site identity/digest and explicit semantic dispositions, including retired sites. | Flow build `3574…` static gate passed: 13,517 sites / 4,707 groups / 1,309 files / 218 retired; checked true, all outstanding categories zero, runtime verification false. |
| Native scenarios | Isolated GUI/CLI semantic parity for relevant cues, bars, counts, bindings and feedback, with explicit exclusions. | 63 logical scenarios passed across builds: 62 on `d113…`, targeted flow on `3574…`. World: combat 26, visual 6, actor/item 5, radial 5, King 2, screen 5 = 49; appearance 13 and anchored floating 1. No all-63 final-build sweep. |
| ARM64 package | Clean build; packaged `--version`/`--help`, catalog, Mach-O contents, signature scope and isolated profile startup. | Final `3574…` readback passed exact source/resource/emitted help, catalog, ARM64/signature/no-test-class checks and actual info→state→quit (3 wire pairs, exit 0). Older `d113…` full lifecycle proof is reused through one-class catalog diff; targeted flow native retest passed on final build. |
| Live playthrough | Actual user game progression or victory, separate from fixtures. | No CLI8 claim. |

On candidate build `d113…`, the native appearance sweep passed **13/13 scenarios**, all with
exit code 0, and **183 GUI checkpoint checks**. It used build
`d113bb83ee42aafc55a301c8f1f4ec44c1eef975451088aea076812d2ea85666`;
the ignored local result is
`desktop-control/build/validation-8.0.0-20260924/combat-appearance-results.json`.
It covers floating values/icons/occurrence behavior, item/buff display, markup,
free/paid casting, targeting, portraits, boss warning/banner, action icons,
projectiles and preview. Other native suites and lifecycle are reported below.

The same candidate's anchored-floating fixture passed 17 GUI checks: floating
value/icon occurrences stayed stable through offscreen, onscreen, offscreen
and modal states at the same FOV cell. Its packaged controller fixture passed
36 wire request/response pairs and four save receipts, covering restart,
current-revision ownership, cancellation, full/src, standalone actions and
unchanged raw viewer transport; local result:
`desktop-control/build/fixtures/cli8-controller-0f527fc2237649bcaaea7bbbc9f52be1/result.json`.
The English/raw packaged game fixture passed six raw checks, including
protocol-7 refusal, in an isolated Chinese path with spaces; local result:
`desktop-control/build/fixtures/packaging8.0/english-7d880e867c9448a59ec5109573a50f8f/result.json`.
It also confirmed the windowed Simplified-Chinese GUI, English public game log,
save receipt and restart of the same run. The controller's standalone actions
play/full replies were independently decoded with eight inline subjects and
54 compared public facts; a current advertised action was executed using its
own revision. Passive viewer byte/header checks passed; independent Terminal
window focus and layout were not tested. All 12 isolated old-schema/non-audit
profile refusals passed without profile changes. These fixtures do not count
as a real playthrough or victory. Consolidated candidate evidence is in
`desktop-control/build/validation-8.0.0-20260924/release-boundary/verified-d113-runtime.json`.

The same candidate's instrumented slow-quit fixture passed: 14 child wire
pairs and two controller `settle` calls exposed an initial `in_progress`, a
blocked `EXECUTING` receipt, then the exact `COMPLETED` receipt with successful
save `p2`; the child exited 0 with no unsolicited reply and no state query
after quit. This proves that isolated lifecycle case on `d113…`. The final
package's 5,487-class comparison and basic quit readback are described above.

The `d113…` world-visual sweep passed 6/6 and actor/item sweep passed 5/5.
Its initial world-combat sweep passed 25 of 26; electricity flow exposed
action-revision churn from direction being treated as intent-changing. On
final build `3574…`, the targeted electricity-flow fixture passed with 17 GUI
postcondition checks and exit code 0. It confirmed successful waiting,
preserved current direction, stable revision under passive flow changes, flow
knowledge through modal/restored states and successful quit. Local evidence:
`desktop-control/build/fixtures/combatvisual-flow-cbc9e4c0852e42da9f3f176665228b39/combat-visual-result.json`.
The one earlier failure is superseded by that final-build targeted result;
the 26 combat scenario identities have passing evidence split across two
builds, not a full 26-case sweep on `3574…`. On `d113…`, radial passed 5/5,
King 2/2 and screen 5/5. Together with world visual 6/6 and actor/item 5/5,
the world suite passed **49/49 unique logical cases**: 48 on `d113…` and the
targeted flow case on `3574…`. With 13 appearance scenarios and one
anchored-floating scenario, **63 logical native scenario identities passed**
across those builds, not in a single-build full sweep. Two nine-frame passive
Supernova halo probes kept the same revision in each world. The edge halo
harness assertion was corrected to match the native inverted-alpha display
contract, then passed on the same build. An earlier GLFW null-monitor attempt
while the macOS display slept is retained as an excluded environment failure;
it did not reach gameplay. The aggregate world result and these dispositions
are in the ignored local
`desktop-control/build/world-native-final-20260924-frozen/world-validation-summary.json`.
No fixture JVM from this sweep remained running; personal profiles were not
accessed. These fixtures are not a full game clear.

## Offline token comparison

The ignored local study at `desktop-control/build/cli8-token-study-20260924/`
replays **695 complete CLI7 public RECV frames** through a source-constrained
offline CLI8 candidate. It counts each complete JSON line without LF using
`tiktoken 0.14.0 / o200k_base` reference tokens. It did not open a game, save
or profile. Original transport hashes remained unchanged:

- SEND SHA-256: `ac74bdf60390b5d7bcc838e4e4692d1043f1531641eacecb73ff4e44abfdc58d`
- RECV SHA-256: `1c184c5176b0b55586300309725eed01ba9c4f229007b817f3bfd544d04ebd42`

| Scope | Frames | Raw CLI7 reference tokens | Offline CLI8 candidate | Candidate change |
| --- | ---: | ---: | ---: | ---: |
| Default play responses | 554 | 3,760,068 | 3,263,730 | −496,338 (−13.200%) |
| All complete responses | 695 | 3,786,164 | 3,289,842 | −496,322 (−13.109%) |

For default play, mean tokens/frame were 6,787.13→5,891.21, P95
8,679→7,549 and maximum 15,647→7,852. For all frames, mean was
5,447.72→4,733.59, P95 8,608→7,474 and maximum 15,647→8,345.
The replay checked 552 matching hero/inventory/map/entity frames, 556
matching action/operation-constraint and phase frames, and 376 matching
persistence frames. It loaded current compiled `CompactProtocol`,
`GameplayObservation` and `GameplayEvidence` classes ahead of older fixture
JARs. The map cases came from depths 1–3; the one info frame used an explicit
offline identity marker and was excluded from field-equivalence checks.

Old pixels cannot recover new native subject identity, sampled bars, source
annotations or all semantic icon/feedback facts. The study is an **offline
candidate**, not validation of shipped wire equivalence, native gameplay,
runtime token use or model billing. It gives no defensible numeric bound on
shipped CLI8 savings. One native cost example follows; a matched native
CLI7/CLI8 run was not measured by this study and is outside its conclusion.

### Native actual-wire cost example

The candidate `d113…` isolated `visual-bomb` fixture provides one actual CLI8
wire frame, recorded in the ignored
`desktop-control/build/cli8-token-study-20260924/offscreen-cue-cost.json`.
After a `pan` request with offset 5000, response `t1-19` contained 25
`bomb_smoke` cells in the same public FOV although they were offscreen. The
complete response was **8,131 bytes and 2,720 `o200k_base` reference tokens**
without LF; raw frame SHA-256 was
`06457068dd9a4aa86c9d17771cc23e8ee0eb74b4d0d7d8c6790a6129a87454f7`.
Reserializing that same frame without the 25 cue rows gives 2,469 tokens
(251 fewer); removing the entire `cues` block gives 2,445 (275 fewer).
Both ablations remove public information and are **not** proposed response
formats. This is the marginal cost of additional offscreen, FOV-known facts in
one CLI8 frame, not a matched CLI7 comparison or an estimate of overall
runtime/model-billing savings.

The [CLI.7.0.1 rendered observation record](cli7-rendered-combat-visuals.md)
and its tests prove only their own version and evidence scope. Current user-facing
operation instructions are in [CLI help](cli-help.md); the current semantic
boundary is in [semantic combat observations](cli-combat-visuals.md).
