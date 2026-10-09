# CLI.9.0.2 expression corrections

This batch addresses the four reproducible findings from the expression audit
of `3d9ef4b197c5a2f5591e2e51a79192b9c4fc3a61` (CLI.9.0.1). The release is
**CLI.9.0.2 / protocol 9 / schema 12 / game 4.0.2, code 922**. Native game,
rendering, actor, collision and save rules remain unchanged.

## Corrected behavior

### Re-observed warnings with missing source identity

Native target markers persist position and time, but not exact attack ownership.
Recomputing an owner from private targets or paths cannot establish historical
lineage, especially for pooled same-cell attacks. The user explicitly selected
no additional saved metadata and conservative, explicit unknown-lifecycle cues.

After the collector actually observes an attached marker in current FOV, an
unbound source contributes `red_target` with `appearance.lifecycle:"unknown"`.
Previously unseen saved cells are not admitted. Unknown cells survive native
image fade and same-level scene rebuilds, clipped to current FOV, until a true
level/run/depth/branch scope end. They do not assert that an attack is still
pending or has completed. This record is process-local; a further restart must
establish knowledge again from its own public display, never from an added save
record or old private target list.

Existing raw/public audit history still records responses normally; it is not
used to restore this live observer ledger.

Bound sources still finish/cancel under their native attack callbacks. Unknown
takes precedence over known when sources share a cell; owner multiplicity is not
reported. Native creation/reset is separated from observation classification so
an unknown source cannot be erased by a bound refresh before the first frame.
Completed bound image tails do not resurrect as unknown, while a genuinely new
unbound presentation on the same pooled cell is admitted.

### Native UI tabs and talent points

All native tabs expose direct node `selected:true|false`. Icon tabs preserve the
semantic identity of the actual source icon before native Image.copy removes its
specialized source identity. Native layout cropping keeps the binding; replacing,
hiding or detaching the icon cannot reuse stale meaning. The protocol does not
guess a page from control order or invent a title from private business state.

Talent buttons expose direct node `talent_points:{spent,max}` from inputs used by
their native laid-out fill. Zero points is explicit, and no current hero is
required, so ranking windows retain their allocated points without opening each
talent separately. Native clicks, selections, rendering and game rules are intact.

### Public environment collision semantics

Public map effects retain complete descriptions and gain source-reviewed partial
`collision` overrides. LightWall/KeyWall set passable/avoid false and solid true;
EternalFire sets only passable/avoid false; Web sets only solid true. Omission does
not mean false and does not erase the public base terrain's other properties.
Visibility and known-cell gates are the existing native public inspection gates.
No combined private collision arrays or prose keyword rules are consulted.

The ray helper combines these explicit facts before the original Ballistica
previous-cell, solid, character and target decisions. Current native overrides
commute. Missing required facts for a recognized barrier or conflicting facts
produce uncertainty rather than a false affirmative reach result. Ordinary water
and source-bound Smithy decorations retain the CLI.9.0.1 distinction.

The Python decoder validates and preserves `selected`, `talent_points`, partial
collision booleans and unknown warning lifecycle in all views and frozen/event
records. The test adapter also retains full `pres` evidence alongside its older
convenience aliases, including extra diagnostic fields and duplicate paths.

## Validation

The final offline gate ran with `--no-build-cache --rerun-tasks --no-daemon`:
all 39 Gradle tasks executed, including the three Java suites, ARM64 packaging,
runtime/test classpath generation and the separate crash-agent test artifact.
Python suites ran after classpath generation.

| Validation | Executed result |
| --- | --- |
| `control-protocol` Java | 20 passed |
| `game-control` Java | 628 passed |
| `desktop-control` Java | 235 passed |
| Total Java | **883 passed; zero failures, errors or skipped tests** |
| Desktop Python | 368 passed |
| Source-inventory Python | 8 passed |
| Project-local helper Python | 35 passed |
| Native environment collision comparisons | 1,728 passed through public map, play/full/src and Python |
| Original dynamic-barrier counterexamples | LightWall, KeyWall and EternalFire all match native collision cell 43 |
| Actual-package profile refusals | 22/22 rejected with paths, bytes and mtimes unchanged; fresh schema 12 reopened |

The focused warning run passed 43 cases, including native SHOCK Vault boss and
Paralysis behavior after actor/marker Bundle roundtrip, pooling, both pre-frame
overlap orders, bound completion/cancellation, fading tails, FOV, same-level
rebuilds and actual scope ends. An independent review caught a pre-frame ancestry
loss in the initial patch; the implementation and regression now retain it. These
are isolated native/headless fixtures, not a live-game save inspection.

Native UI verification used the final packaged production JAR in both routes:

- The ordinary packaged controller and bundled JVM verified all 14 tabs across
  Hero, Journal and Settings, semantic icon variants, selected-state changes,
  revision changes and four explicit zero-of-two hero talent fills.
- An isolated ranking fixture used that same production JAR with test-only
  fixture classes and the host JVM. After native Poison death, the ranking
  window exposed spent/max values 1/2, 2/2, 0/2 and 0/2 without a hero projection.
  This route is separately identified as instrumented fixture execution.

The ordinary packaged controller lifecycle smoke passed across two sessions
with 29 and 7 requests, four save receipts, stale-revision rejection, save/reopen
matching and complete raw/viewer checks. The separate slow-quit smoke used the
bundled JVM and an external test agent; the original request reached its terminal
receipt and process exit without a post-quit state query. Desktop Python emitted
existing SQLite ResourceWarning diagnostics but no test failure.

The [scoped exact source review](cli-9.0.2-expression-source-review.json) records
source-specific decisions and test references. Static source/route coverage is
not claimed as complete native runtime coverage. Historical CLI.9.0.0 and
CLI.9.0.1 reports keep their original results and artifacts.

The combat source gate contains 13,530 sites in 4,851 groups, with zero unresolved
or removed-source drift; this batch reviews 29 new, 30 changed and 3 retired
sites. The UI inventory has 1,917 records and zero unmapped entries; its 22
changed records update line locations without changing input-method digests or
routes. Text coverage retains 206 operations, 789 constructors and 1,238 files;
snapshot static fields are unchanged. These are source-review counts, not
additional runtime test totals.

All execution uses isolated test profiles or pure in-memory fixtures. No personal
profile is read or changed, no existing game is operated, and no fixture counts
as a real playthrough. The delivery path remains
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`. Validation
is followed by a scoped local GPG-signed commit; no push/tag/publication.

## Actual delivered package

The standard ARM64 application emits
`CLI.9.0.2 (protocol 9, game 4.0.2)`. Its catalog retains game code 922,
audit schema 12, trace format 1 and `Shattered Pixel Dungeon CLI v9`.

| Artifact | SHA-256 / identity |
| --- | --- |
| Production JAR | `0ddcb2bd69909b42513ecff245c2e0cabc6b6a9af18c531b18855d8c8a0e1b6b` |
| Build ID | `674df3d6501627d0722edc7cc81e8e8fa635397bdfc878a9fc6b87f16391bd12` |
| Embedded catalog | `6ae926fb4ca75cc250b3a757da31e63aa65e145e4d3269a85c0bba96927fbe18` |
| Help | `b971b47a4de8c9d7e1ae7a3f72c5c7dc75dcae5761123d03fb568afd81da6fe4` |

All 3,466 catalog entries were verified: 3,461 packaged entries and five
native/build inputs. Source, bundled and printed help are byte-identical, as are
snapshot resources. Test-only and retired classes are absent from the production
JAR. All three native launchers and the JVM library are ARM64; strict deep ad-hoc
signature verification passed. This is local signing, not notarized distribution.

Local generated evidence is under
`desktop-control/build/cli9.0.2-validation/`:

- `full-gate.log`, `java-test-totals.json`, `focused-warnings-retry.log`;
- `python-desktop-final.log`, `python-source-final.log`, `python-helper-final.log`;
- `python-schema-map-final-summary.json`, `schema-refusal-final.log`,
  `map-regression-final.log`, `map/hazard-comparison.json`;
- `packaged-tab-talent-summary.json`, `packaged-tab-talent-expression.log`;
- `controller-package.log`, `slow-quit-package.log`, `package-identity.json`.

Unknown warning retention is intentionally conservative and process-local. No
additional warning provenance is saved, and no test result is represented as a
complete real playthrough. Android and iOS builds were not run for this batch.
