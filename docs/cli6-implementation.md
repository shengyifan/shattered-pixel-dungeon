# CLI 6 implementation and validation

Release contract: CLI.6.0.0, protocol 6, audit schema 9, base game 3.3.8.
The authoritative interface is [cli-help.md](cli-help.md). The default CLI v6
profile is independent; previous profiles are refused before writes.

## Implemented behavior

- Packaged `control --machine` owns one native-recorded `run --machine` child,
  validates complete frames, allocates short requests and binds actions to the
  exact displayed revision. Initial activity is visible before explicit settling;
  original outcome, persistence and fresh observation stay separate.
- Typed persistent profile-local handles retain canonical identity/epoch and are
  allocated durably in paired audit transactions before publication. Frozen wire
  history and user/source text are not rewritten.
- Play observations use same-frame UI operation/shape dictionaries, exact item
  label inheritance, verified empty-slot pruning, passive leaf identities,
  repeated character descriptors and scoped binding defaults. Protected metadata
  falls back to addressable objects; full/source history remains expanded.
- Viewer bodies are normal weight. Only numbered SEND/RECV headings are bold
  bright cyan; both directions have identical heading style and ERROR is normal red.

## Validation record

Validation completed locally on 2026-09-20. The no-cache macOS gate rebuilt all
production modules; the final sidebar-capacity guard and bundled help were then
recompiled/retested and the ARM64 package regenerated. Final JUnit XML records
**517 passing tests**, with no failures, errors or skips: 18 protocol, 321 game
control and 178 desktop control. This includes **34 controller tests**, paired
identity persistence/rollback, restart-first-request error scopes, late responses
around a scope-changing action, and protected mixed UI node/row diagnostics.

The complete Python discovery suite passed **142 tests**, including **40 native
transport/viewer tests**. The final visual-fixture adapter resolves short handles
only for post-execution assertions through an explicitly isolated fixture's
read-only public registry; it does not provide game choices or read personal data.

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

The static UI inventory required two line-number updates for the new decoration
hook. Its callback hashes, 1,910 records and zero unmapped routes are unchanged;
that inventory is static evidence, not runtime or full-game coverage.

## Actual package verification

The emitted version is `CLI.6.0.0 (protocol 6, game 3.3.8)`. The final catalog build
ID is `b8d1381dd00f51a527f5fd2a41495b5f39cb39ffdc4b9c1bffb8a1a965403ab7`.
The executable's help, the single bundled help resource and the source document
are byte-identical: **39,381 bytes**, SHA-256
`1ac1929b03cbfb9f6eaa7fcf9ffb8901fcb7d867d72ad980eaa5ae3e92c7d1e5`.
Native `spdctl`, `spdctl-jvm` and embedded `libjvm.dylib` are ARM64;
`codesign --verify --deep --strict` passes. The controller production class is
packaged and test controller/replay classes are absent. This is local ad-hoc
signing, not notarization or publication.

The actual bundled controller ran with no host Java/classpath and restricted PATH,
using a new profile path containing spaces and Chinese text. Through advertised
public controls it created a Warrior, saved, quit, restarted the same fixture,
continued, saved and quit again. The two sessions used prefixes `t1/t2`, issued
8/7 actual wire requests, and retained four distinct save receipts. Saved hero,
position and inventory matched after restart; an old process revision was rejected
locally without sending it to the child. Each controller process created exactly
one child trace. Exposed frames match the original recorded identities and bodies;
no state query followed successful quit. No real `in_progress` occurred in these
menu/start/save paths; interruption and uncertain recovery have separate unit cases.

The actual packaged viewer rendered eight SEND and eight RECV headings in shared
bold bright cyan, with normal-weight payloads. Interpreted ANSI state confirms only
those headings are bold; stripping ANSI gives the plain output, and source trace
hashes remain unchanged. Native tests separately exercise automatic independent
viewer launches, arbitrary chunks, interleaved/continued records and invalid bytes.
No claim is made of visually inspecting the macOS Terminal windows in this batch.

The final package rejected each disposable schema 1–8 profile before writes;
all fixture file bytes, directory paths and timestamps were unchanged.

Local artifacts (ignored, not required to build or run unit tests):

- `desktop-control/build/cli6-package-validation.json`
- `desktop-control/build/fixtures/cli6-controller-e37ed3bdc7944002b7fbd765adc19da7/result.json`
- `desktop-control/build/fixtures/packaging6.0/old-schema-2bbaad2dca644f628e082fdb7436c37b/result.json`

## Decision information and render fixtures

Unknown item knowledge, zero counts, water/strength displays, target-bound rendered
health bars, hazards, dynamic operations and visual cues remain covered by the
projection regressions. Protected nodes stay expanded at their existing indexes;
safe siblings can still use the response's operation/shape tables. Ancestor-owned
diagnostics preserve their addressed subtrees.

Empty-slot pruning is deliberately limited to the exact fixed-grid InventoryPane
parent. Dynamic WndBag slots expose capacity through their count; those and unknown
or customized parents remain. Tests cover this distinction and retain active slots,
unknown-item icons, custom decorations and informative disabled controls.

Four isolated actual-engine visual scenarios verify Tengu bomb smoke/countdown
and Dwarf King summoning styles, both visible and hidden, including final-action
warnings, modal/offscreen suppression, natural fade and immutable event history.
All four were repeated successfully on the final build above after the last
controller and sidebar-capacity changes. Final aggregates are
`desktop-control/build/fixtures/runtime-7a76c592fe4240ea87505435cf611dc3/visual-cue-results.json`
and `desktop-control/build/king-visual-validation.json`.
These fixtures are distinct from the fresh packaged Warrior smoke and do not
establish a full playthrough or all-class/late-game coverage.

## Production corpus replay

The supplied 297 complete protocol-5 request/response pairs were replayed through
the production `PublicHandles`, paired `AuditStore` and `CompactProtocol` encoders
without launching a game. All play/full semantic comparisons passed. The input
SEND/RECV hashes and bytes were unchanged; empty-slot or child-ownership hints
absent from historical wire data were not invented.

| Complete NDJSON reference tokens | Recorded v5 | Production v6 replay |
| --- | ---: | ---: |
| Requests total | 20,867 | 8,897 |
| Responses total | 698,425 | 538,710 |
| Response median | 2,850 | 2,203 |
| Response p95 | 3,681 | 2,854 |
| Response maximum | 3,795 | 2,937 |
| First info response | 1,408 | 1,554 |

Response tokens decreased **22.8679%**; requests plus responses decreased
**23.8686%**. The expanded production schema, persistent-handle capability and
session request prefix are counted. The first info is the only larger response.
The reference is `tiktoken 0.12.0 / o200k_base`, including LF. These are format
measurements, not actual model billing or end-to-end agent-token measurements.

Reproduction and separate map/inventory/log evaluation are in
[the incrementality report](cli6-incrementality-evaluation.md). The generated
`desktop-control/build/cli6-token-replay/report.json` and per-frame files retain
the measured results. No cross-response delta behavior was deployed.
