# CLI.9.0.0 integration with upstream game 4.0.2

This batch merges upstream `57a4e06a4caf162446d1c28caa7983f0493fecf0`
into `feature/mac-cli`, whose pre-merge head is
`ff05ece426fa2762be7d8a7bf744f9ab984cfae9`. The common ancestor is
`7b8b845a76fe76c6b7c031ae9e570852411f56db`. The upstream interval contains
364 commits and 532 changed paths; 143 paths also changed on the CLI branch,
and the merge reported 30 textual conflicts.

## Scope and native behavior

The new identity is **CLI.9.0.0 / protocol 9 / audit schema 12 / game 4.0.2
(code 922)**. Upstream native game behavior is the authority. The retained
differences attach control, source-bound public observations, English text
provenance and audit callbacks. They do not change combat, AI, RNG, pathfinding,
generation, turn timing, native UI choices or native save rules.

The [complete upstream review index](cli-9.0.0-upstream-review.json) records every
changed upstream path, its source identity, overlap and review disposition.
Static review, declared test coverage, executed fixtures, packaged smoke tests
and real playthrough progress are distinct evidence categories.

Native upstream questions recorded during review are intentionally unchanged:
`Level.invalidHeroPos` terrain-flag logic, `GridBuilder`'s x bound using
`maxHeight`, and Crystal durability subtraction relative to its comment.
These are source-review questions, not CLI fixes or established runtime outcomes.

## Contract and integration changes

- One release configuration generates Java identity and the native default profile
  name. Game version/name and code come from upstream Gradle properties. Catalog,
  launcher, handshake, JVM game identity and fixture launchers share that input.
- New profiles use `Shattered Pixel Dungeon CLI v9`. Both schema-12 audit stores
  must identify game 4.0.2/code 922. Earlier, ambiguous or mismatched profiles are
  rejected before writes; personal data is never migrated or deleted.
- Protocol 9 health samples require `incoming_dot`, the displayed DOT overlay
  width. Hero, ordinary character and boss samples remain display estimates;
  collection does not call mutable native DOT getters.
- Public OBJECT rows retain native known-cell visibility and operations. Their
  source-bound projectile occupancy is separate from creature identity and from
  the set of legal hypothetical ray origins.
- `public_world_cues` includes explicit native movement-shadow evidence without
  inventing terrain or a live enemy at the reported location. Other public
  objects, static terrain and always-visible environments follow their own
  reviewed native knowledge gates.
- Native pooled target/check markers keep upstream timing and persistence.
  Independent observer records retain only disclosed pending attack cells until
  native completion/cancellation. Marker reuse cannot overwrite another attack's
  public episode; no private warning cells are reconstructed from save state.
- Vault boss forms and native hazard emitters, Sentry variants, new item effects,
  Crystal display stages and revised terrain sources have explicit semantic
  adapters. Exact hidden HP, damage prediction, durability, AI and timers remain
  outside the public contract.
- Vault transitions and reward flows use existing native controls and callbacks.
  Current inventory/control bindings are reacquired after transitions and native
  item replacement. Map base terrain stays query-pure; custom descriptions use
  the original cell-examine UI.
- Current Python/research adapters target protocol 9. Historical pinned CLI7
  tools retain their historical identity. Autonomous play policy is unavailable
  pending a separate game-4.0.2 policy review; public interactive intents remain
  available. Transport trace format remains 1.

## Validation record

The final gate on 2026-10-09 passed with **39 of 39 actionable Gradle tasks
executed**, with the build cache disabled and every task rerun. Both source
checks were enabled; diagnostic runs that temporarily omitted them are not the
release gate. The command was:

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath :desktop-control:crashAgentJar \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
```

Gradle 9.5.0 and the upstream AGP 9.2.0 dependencies were first fetched online;
the final gate ran offline. The JVM was ARM64 Temurin 25.0.4 with Java 11
source/target compatibility. Dependent Python suites ran after the classpath
writers, and were rerun against the final rebuilt output.

| Suite | Passed | Failures / errors / skipped |
| --- | ---: | --- |
| control-protocol Java | 20 | 0 / 0 / 0 |
| game-control Java | 603 | 0 / 0 / 0 |
| desktop-control Java | 235 | 0 / 0 / 0 |
| Total Java | 858 | 0 / 0 / 0 |
| Desktop Python | 361 | 0 failures or errors |
| Source-inventory Python | 8 | 0 failures or errors |
| Project-local public helpers | 27 | 0 failures or errors |

The Java total includes 41 warning/lifecycle cases and the new object, DOT,
shadow, door energy, item-effect and query-purity regressions. Two SQLite
`ResourceWarning` diagnostics occurred in Python; they were recorded and did not
fail tests. Earlier CLI.8.0.2 totals and package hashes are historical.

### Source and interface review gates

- The upstream index covers exactly **532 distinct paths**, including **143
  same-path overlaps** with the former CLI branch. **375** final paths match
  upstream bytes or upstream deletion exactly; **156** retain reviewed CLI
  control, observation or text-source attachments. One additional path removes
  an upstream trailing tab on an empty line to satisfy the required staged
  whitespace check; native tokens and line numbers are unchanged.
- The [exact semantic source migration](cli-9.0.0-source-review.json) covers
  **1,340 source files, 13,498 sites and 4,827 review groups**. Of these, 10,910
  site identities and enclosing bodies are unchanged; 2,588 have explicit new
  or revalidated source decisions. All 1,181 newly retired sites have baseline
  identity, method and retirement reasons. Unreviewed, unmatched, stale-body and
  outstanding counts are zero. The added TargetedCell outer-class fingerprint
  strengthens the boundary check; no checker was weakened.
- UI coverage has **2,875 parsed types, 1,917 records and 858 input methods**,
  with no unmapped route. Compared with the old baseline, 25 records were added,
  18 removed, 28 substantively changed and 233 only moved lines. Source review
  and generated routes remain explicitly `static_only`.
- Text attribution covers **1,238 core sources, 789 fixed-catalog constructors
  and 206 reviewed String operations**, with no unreviewed or stale exclusion.
- Metadata-only class inspection updates snapshot scope to **343 roots and
  1,679 fields** without initializing classes or reading static field values.

The source inventories do not claim that every producer has a separate runtime
test. Current tests and native fixtures are identified independently.

Final helper review found that native `CUSTOM_DECO_WTR` is solid but shares the
public WATER base appearance. The public-only ray helper now stops with
`public_appearance_collision_ambiguous` rather than predicting passage through
that appearance. An explicit public occupant can still prove a same-cell
`STOP_CHARS` collision. Three new regressions cover all modes, known occupants,
and shadow/disguise non-promotion; the final helper total is 27. This deliberately
makes genuine water predictions conservative too. It does not read hidden terrain,
change the public protocol or alter the already verified Java package.

### Official text corpus and preserved upstream defects

The corpus covers **4,977 official keys in 23 languages: 114,471 comparisons**.
There are **114,469 passing comparisons**, including three explicit visibility
partials, **zero CLI projection defects**, and **two separately recorded native
resource defects**. Both native defects are pinned to their exact source
key/language/template fingerprints; an unexpected defect still fails the gate.

- Indonesian lacks the Vault injury confirmation key in its native id/in bundle
  path. The observed missing result remains partial; no English display is
  invented.
- Greek PowerOfMany contains the native malformed `-25%%%` format sequence.
  Its native failure is preserved; projection returns the same partial result
  without leaking either of two hidden argument sentinels.

The newly legitimate `corpsedust&dustwraith` resource key exposed a CLI key
validator gap. Allowing the native `&` character fixes that gap while tests keep
rejecting unsafe key characters. No upstream resource text was edited to make
the corpus pass.

### Native package and isolated scenario evidence

The final packaged controller and bundled JVM pass ordinary startup, a new
Warrior fixture, save, quit, reopen and strict old-revision rejection: two
sessions, **36 wire requests and four save receipts**. Recorded bytes and viewer
output are checked independently; only SEND/RECV headings are bold bright cyan.

The separately instrumented slow-quit package fixture passes with the bundled
`java.instrument` runtime. It exposes `in_progress`, preserves the original quit
receipt through pending settlement, receives terminal success and exits without
a post-quit state request. The test-only agent is supplied externally and is not
part of the production app.

The actual final native launcher passes **22 incompatible-profile refusal
cases**. Rejected fixture paths, bytes and modification times remain unchanged;
a fresh schema-12 profile is also reopened successfully. Cases include schema
1 through 11, missing or mismatched game metadata in either store, incomplete
audit pairs and ambiguous nonempty data.

The final packaged production JAR passes **27 of 27 native Vault/item scenarios**
and **five of five small-layout selector/modal cases**. These use the actual
packaged production classes, test-only scenario setup and a recorded host JVM;
they are distinct from the bundled-launcher smoke above. Both matrices recheck
the final on-disk JAR/catalog hashes, preserve exact wire bytes and perform no
action retries or private checkpoint reads.

The scenarios cover all six classes entering the Vault and using its mirror;
token counts 0/9/10; same-floor beacon; forced injury modal; early, consumable,
equipment, partial-score, victory, staff-to-wand, seal and full-backpack exits;
empty-staff imbue; and both augmentation cancel/reopen routes. They verify native
item/currency/quickslot restoration, old-revision rejection and native Imp reward
overflow. The five small-layout cases repeat reward selection, full-backpack
return, imbue, augmentation and forced injury UI. They do not claim an Android
or iOS binary test.

The preliminary source/runtime and incremental-package attempts remain separately
recorded, including corrected driver-only assumptions about disabled-item labels,
modal display evidence and exact item names. They are not counted as extra final
successes. No fixture outcome is a real playthrough or a win.

All runtime evidence must use fresh explicitly labeled isolated profiles.
No personal saves, private gameplay audit or existing game session may be used
to choose actions. Android/iOS changes receive source/configuration review;
unexecuted mobile builds and a full real playthrough are not claimed.

## Delivery

The delivered target is the standard generated macOS ARM64 app under
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`.
Its actual executable prints `CLI.9.0.0 (protocol 9, game 4.0.2)`.

- Build ID: `89295bb99ae798fc4401753af22b92b849adebeda1d82316cfc50772ca54d0b6`
- Application JAR SHA-256: `bb486c632d1f499fcdcaf826d82e51750b61f26556a35c6f055a1a74aa63647a`
- Catalog SHA-256: `cc57808935b3eeb702cc7208510243374bbc5216aa7ea4bc87a9d90614ae8083`
- Source, bundled and printed help are byte-identical.
- All **3,465 catalog entries** match build inputs; **3,460 packaged class,
  asset and help entries** also match the actual JAR. The other five entries
  identify launcher/native/build/release configuration inputs. Snapshot resources
  match their source bytes.
- Native CLI, JVM app launcher, ordinary GUI launcher and `libjvm` are ARM64.
  Strict deep local ad-hoc signature verification passes. Test launchers/agents
  and retired renderer types are absent from the production JAR.

The preliminary incremental-build JAR had a different hash. Final package tests
are rerun against the final hash above; preliminary results are retained as
development evidence and do not substitute for final-byte verification.
An exhaustive classfile comparison finds identical execution instructions,
field/method descriptors and runtime structural attributes in all 281 changed
classes. Differences are limited to local-variable names, four source-line
tables and two reflectable constructor-parameter flag records, plus the catalog
hashes. The packages are not described as byte-identical or universally
semantically equivalent. Compiler source visibility during incremental versus
full compilation is a consistent explanation, not a separately reproduced cause.
The exact comparison is retained in `bytecode-semantic-diff.json` alongside the
read-only fail-closed parser.

Primary generated records are in `desktop-control/build/cli9-validation/`:
`full-gate.log`, `java-test-totals.json`, `package-identity.json`,
`controller-package-final.log`, `slow-quit-package-final.log` and
`clean-rebuild-diff.json`. Final profile/Python evidence is under
`desktop-control/build/fixtures/packaging9.0/profile-refusal-b8ff8dd1562b45899f57448249cd65dc/`.
Final native scenario aggregates are
`desktop-control/build/cli9-vault-package-validation.json` and
`desktop-control/build/cli9-vault-package-mobile-validation.json`; each identifies
the exact frozen runtime, driver and production JAR.
The source-review JSON files above are committed evidence indexes; generated
profiles, raw transport and binaries remain ignored build artifacts.

After validation this batch is recorded by a local GPG-signed merge commit.
No push, tag or publication is performed, and `master` remains the upstream
commit recorded at the start of this report.
