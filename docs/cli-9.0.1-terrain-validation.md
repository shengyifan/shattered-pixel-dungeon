# CLI.9.0.1 public custom-terrain correction

This patch corrects the default-map representation introduced during the game
4.0.2 integration. CLI.9.0.0 exposed both ordinary water and the solid Smithy
basin as WATER, then made the read-only ray helper stop at every water cell.
The limitation belonged at the public observation boundary; conservative helper
behavior prevented a wrong answer but did not restore the missing public fact.

CLI.9.0.1 retains **protocol 9, audit schema 12, game 4.0.2/code 922 and the
independent v9 profile**. It changes no native game rule, rendering operation,
collision algorithm, actor action or save behavior. The baseline commit is
`5c4494b4aace3e9d81d7f726fc86635e02f0649a`.

## Corrected public descriptor

The existing native Smithy presentation provides a reviewed, opt-in immutable
terrain descriptor. Its already selected basin presentation maps to terrain 39;
the other solid Smithy pieces map to terrain 23. Name and description use the
same official resource identity as the native inspection window. Ordinary water
remains terrain 29. No atlas/frame number, hidden model flag or texture geometry
is added to the public protocol.

Descriptors are selected only for visible, visited or mapped cells from an
existing source attached to the current game scene. Source lifetime, nonzero
alpha, native tile anchor, finite/default transform and selected-frame validity
are checked without creating an Image or invoking create/image/needsRender or
arbitrary custom name/description getters. Camera pan, viewport and modal UI do
not remove known world facts. Layer priority is conservative; an unreviewed
presented overlay is not guessed from its label or the NPC standing nearby.

Equal public presentation with unavailable custom sources produces equal map
descriptors even when a test changes hidden backing terrain between 29 and 39.
Unseen cells remain omitted. The map remains a public observation, not a dump of
all native collision flags.

The complete descriptor survives map.types/rows in play/full/src and separate
frozen before/after maps. Python map/cell helpers retain the descriptor; the ray
helper now permits ordinary water and applies native Ballistica solid rules to
the explicitly public decoration. Unknown gaps and unreported blockers remain
uncertainties. Merely naming a target proves neither execution nor a hit.

## Verification

The final runtime/package gate passed on 2026-10-09 with all **39 actionable
Gradle tasks executed**, using `--no-build-cache --rerun-tasks --offline
--no-daemon --console=plain`. Both source gates were enabled. Runtime/test
classpath writers completed before the dependent Python suites.

| Check | Result |
| --- | ---: |
| control-protocol Java | 20 passed |
| game-control Java | 611 passed |
| desktop-control Java | 235 passed |
| Total Java | 866 passed; zero failures, errors or skipped |
| Desktop Python | 362 passed |
| Source-inventory Python | 8 passed |
| Public helpers | 31 passed |
| Actual packaged-production terrain scenarios | 2 passed (interface sizes 2 and 0) |
| Actual launcher profile refusal cases | 22 passed |

An initial release-version test attempted to rewrite a session version to its
existing value after the version bump. The no-op correctly did not throw. The
fixture now attempts a distinct version, preserving the intended immutability
test; no production audit logic was changed. Final Python output retained two
non-failing SQLite connection ResourceWarnings.

The focused source regressions cover normal water versus solid decoration,
native selected frames and collision, known/unknown scope, absent/detached/hidden
or malformed presentation, source geometry, private-map independence, layer
priority, repeated-query purity, RNG/render-buffer stability and English
play/full/src/frozen descriptor retention.

The two native scenarios use the actual packaged production JAR, isolated test
setup and a recorded host Temurin 25.0.4 JVM. Before opening inspection, the
default map contains a Smithy cell with terrain 39, native English name and full
description, and an ordinary water cell with terrain 29. The original native
inspection window subsequently confirms both names and the decoration text.
The public helper and native Ballistica agree that the water target is reachable
and the Smithy target is blocked at the preceding cell. Play/full/src, modal UI
and large camera pans retain those same known descriptors in both layouts.

These injected scenarios are separate from the bundled-launcher smoke, which
also passed using the package's own JVM: new profile, normal controls, four save
receipts across 36 wire requests, successful save/reopen and stale-revision
rejection. A separately instrumented asynchronous-quit package check passed,
including the original terminal receipt and no post-quit state query. All 22
rejected profile fixtures retained identical paths, bytes and modification times;
a fresh schema-12 fixture reopened successfully.

The exact source gate passes **13,504 sites and 4,828 review groups**, with zero
unreviewed, missing, stale-body or outstanding sites. Text attribution remains
206 operations / 1,238 source files / 789 fixed-catalog constructors. The only UI
inventory change is one new immutable descriptor type: 2,876 parsed types, with
all 1,917 route records unchanged. Snapshot static declarations are unchanged.

The [scoped source review](cli-9.0.1-terrain-source-review.json) records exact
new and changed boundaries. CLI.9.0.0's [integration report](cli-9.0.0-v4.0.2-validation.md)
and its path/source indexes retain their historical identity; they are not
rewritten to claim this correction existed in the preceding build.

All runtime checks use fresh isolated fixtures. The actual package is delivered
under `desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`.
No personal profile is read, migrated or modified, and this work does not control
an existing game. A locally signed scoped commit follows validation; no push,
tag or publication is performed.

## Actual delivered package

The executable reports `CLI.9.0.1 (protocol 9, game 4.0.2)`.

- Build ID: `590811bbb425c55a7b26c16b146c99197a160704b76a285642c7d8a1e1c7d871`
- Application JAR SHA-256: `005867fb3f2cb385d207099d4e972cda1125edd48522a2bae6e98c19b0e74d39`
- Catalog SHA-256: `94964dfb54f3141a14bd3d5738610c780d35c0b6692f9ffa9e2be03283e9db45`
- All 3,466 catalog entries match their build inputs; 3,461 packaged class,
  asset and help entries match actual JAR bytes. The other five identify native,
  launcher and build/release configuration inputs.
- Source, bundled and printed help are byte-identical. Snapshot resources match.
  All native launchers and libjvm are ARM64; strict deep ad-hoc signature
  verification passes. Test fixture classes are absent from the production JAR.

Generated validation logs and package identity are under
`desktop-control/build/cli9.0.1-validation/`, including `full-gate-final.log`,
`java-test-totals.json`, `package-identity.json`, `python-verification-summary.json`,
`controller-package.log` and `slow-quit-package.log`. Actual packaged scenario
evidence is `desktop-control/build/terrain-map-package-validation.json`; the
earlier source-runtime scenarios are recorded separately. These are isolated
fixture results, not real-playthrough progress.
