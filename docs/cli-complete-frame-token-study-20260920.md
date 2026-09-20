# Complete single-frame CLI responses: token-format study

> Historical offline experiment, before CLI 7. CLI 7 implements only the selected
> action-sharing/record-template subset; its measurements are reported separately.
> Generated build artifacts were removed from the workspace during the
> [2026-09-21 cleanup](cli-rebuild-7.0.0-20260921.md). Paths and version statements
> below describe this experiment's original execution, not the current package.

## Current requirement and status

**Every response must remain complete and independently interpretable.** This
supersedes the cross-frame catalog/cache/delta recommendations in the earlier
[depth-9 study](cli-token-study-d9-20260920.md). Those earlier measurements remain
historical experiments; their 44–64% reductions do not satisfy this requirement.

This study uses no previous game frame, baseline, cross-frame dictionary, deferred
lookup, or later request to recover current information. All variable definitions
are in the same packet. A decoder may know its fixed versioned grammar, just as
the current protocol decoder knows field aliases and the tile alphabet; the
protocol manual is not retransmitted on every response.

The primary candidates preserve **all input JSON values**, including array order,
duplicate occurrences, node identities, parent links, controls, descriptions,
unknown/null/false/zero distinctions, presentation evidence and save outcomes.
This is stronger than merely retaining selected tactical fields. Object-key
formatting is not a gameplay fact; the original transport bytes remain unchanged
in the source files. Errors and receipt replies retain their complete original
contents rather than acquiring an invented world snapshot.

All new code is in offline research/test scripts. **Production remains
CLI.6.1.1, protocol 6, schema 9. No response-format change is deployed.** No game,
personal save or private audit database was opened for this study.

## Corpora and method

The two corpora are evaluated separately:

| Corpus | Replies | Scope | Original response tokens |
| --- | ---: | --- | ---: |
| Historical CLI.6.0.1 Warrior | 3,880 | Handshake through depth-9 death `t1.2zs` | 9,924,866 |
| Current CLI.6.1.1 public package acceptance | 20 | First session 13 + restart 7; menus/D1 only | 51,873 |

The historical source already omitted information later restored in CLI.6.1.0.
Missing descriptions/nodes cannot be reconstructed, so no values are guessed.
The current sample proves behavior on current real output but is too small and
narrow to establish a current full-dungeon saving rate. Neither corpus is mixed
into the other's baseline.

Counts use **tiktoken 0.12.0 / o200k_base**, complete minified UTF-8 JSON packets
including LF. Every local dictionary, table header, escape and codec wrapper is
counted. These are reference representation tokens, not model billing. The model's
tool scaffolding, context reuse and ability to read a proposed format are separate
questions. Byte count is not used as a substitute for token count.

Each of 23 strict candidates is encoded and freshly decoded for every frame:
**89,700 complete frame round-trips** over 3,900 replies. Equality is type-sensitive
JSON-value equality; in particular `false`, `0`, `null`, absent fields and array
positions are not normalized together. Input SEND/RECV pairs and hashes are checked,
and source files are rehashed after reading. No original record is rewritten.

The token-cost function caches only computed token lengths to accelerate the
benchmark. No decoder, dictionary definition or game-state dependency is cached
across packets.

## Results

Positive percentages mean fewer tokens; negative percentages mean more.

| Complete-packet candidate | Historical tokens | Historical reduction | Current tokens | Current reduction |
| --- | ---: | ---: | ---: | ---: |
| Original complete JSON | 9,924,866 | — | 51,873 | — |
| Nested inventory tree, including standalone wrapper | 9,515,800 | 4.12% | 51,829 | 0.08% |
| Same-frame long-string dictionary | 9,956,746 | -0.32% | 52,144 | -0.52% |
| Same-frame UI/action templates, token-selected | 9,690,097 | 2.37% | 45,295 | 12.68% |
| Reversible decimal control spelling | 9,731,803 | 1.95% | 48,730 | 6.06% |
| Readable ASCII row map | 10,140,161 | -2.17% | 52,688 | -1.57% |
| Map run-length encoding | 12,391,639 | -24.85% | 54,042 | -4.18% |
| Bounded full map grid + visibility intervals | 9,556,301 | 3.71% | 52,013 | -0.27% |
| Compact gesture symbols | 9,965,680 | -0.41% | 47,219 | 8.97% |
| Human-readable hero scalar line | 9,863,433 | 0.62% | 51,801 | 0.14% |
| Hero/gestures + decimal controls + UI templates | 9,362,103 | 5.67% | 39,246 | 24.34% |
| Hero/gestures + decimal controls + inventory tree + grid | 8,872,671 | 10.60% | 43,746 | 15.67% |
| Select the smallest tested complete packet for each frame | **8,847,573** | **10.85%** | **39,114** | **24.60%** |

The final row chooses among complete, tagged packets and the original complete
JSON. It does not inspect future frames or reuse prior data. This is the best of
the tested candidates, not a proof of globally optimal encoding. Its median
response is 2,507 tokens in the historical corpus (original 2,794), and 2,845 in
the current corpus (original 3,943).

The current fixed combination already yields 24.34%, versus 24.60% for per-packet
selection. A predictable single grammar can therefore be preferable to exposing
many codec modes for that small extra gain. The historical large-map corpus favors
the tree/grid combination; the current early-game corpus favors UI sharing.

Component percentages cannot be added. Definitions can overlap, a short message
may lose to wrapper overhead, and the two corpora have different information and
scene distributions. Unlike the previous unwrapped inventory-only experiment,
the tree row above also pays its standalone packet wrapper.

## Most useful changes in representation

### One action definition, several same-frame bindings

Current production retains complete ordered `acts` and convenient copies in node
`ops`. In the current sample's 11 game-state replies, all 297 node operations have
an exact corresponding action in the same frame after accounting for their node
control binding. The source constructs these copies in `UiBridge.addAction` and
`CompactProtocol.attachActions`; this is positive evidence of shared content.

The experiment shares exact descriptors, with ordered references for each node.
It preserves separate controls, operation order, repeated entries, every constraint
and independent metadata. Merely having the same `op`, label or inventory locator
is insufficient. A quickslot button and a backpack button for the same item can
have different native callbacks and must remain distinct.

Common-field templates also apply to records with the same shape. Fields constant
inside one group are written once, while each row retains every varying value.
Empty-node IDs are still present; this is not the old empty-node pruning rule.
Both already packed node rows and expanded objects are supported. Unknown or
protected structures fall back to their original values.

### Compact, readable scalar notation

The four native mouse gestures have a fixed reversible spelling:

```text
C = click    R = right    M = middle    L = long
CRML = [click, right, middle, long]
LCL  = [long, click, long]
```

Order and repeated occurrences remain. Unknown gestures, original strings,
nulls and reserved-looking objects are escaped or retained literally. The 8.97%
current-corpus result differs from the historical loss because CLI.6.1 restored
many independent action/node fields that now repeat these arrays.

Hero scalar fields can be presented as a named line instead of repeating many
object keys:

```text
HP43/65+0 XP7/55 STR12/12 @466 D7 L10 G284 E5 ready class=warrior sub=none/null
```

Here STR is current/base strength, and `ready` represents `hero.ready`, not the
outer phase or permission to move through a modal window. Outer `phase`, actions,
request status and errors remain intact. Talents, buffs, available talent points,
different names and all unknown/unsupported extra fields remain in the same
packet's `rest` object. The line alone is not the whole hero record.

### Control handles as decimal spelling, not new identities

Canonical `c<base36>` values can be represented as decimal integers at known
control-binding positions, with a fixed inverse spelling rule. For example,
`c10` is represented as 36, and reconstructed as exactly `c10`.

This does **not** reassign controls 1..N, truncate a handle, infer a missing ID,
change a callback, or require a mapping remembered from a prior response. Request
IDs, scope/revision handles, displayed text and opaque historical data are not
changed. Noncanonical spellings and values outside JSON's interoperable safe
integer range remain literal. Original numeric/null/boolean bindings are escaped
where necessary so their type is not confused with a transformed handle.

This is an offline response representation, not a change to the current CLI's
string `ctl` parameter. The prototype must not be sent to the existing controller.

### Full grids can beat repeated row coordinates

The grid candidate keeps the entire ordered terrain legend, width/height,
environment, effect definitions and extra metadata. An explicit bounding box
marks its origin and size. Inside it, a reserved blank means an **unknown gap**,
never floor or wall; outside the box the original unknown state remains.

The most common known-cell visibility is stated once, and other visible/visited/
mapped runs are explicit intervals. Original unusual segmentation and expanded
uniform visibility are preserved when needed, so even the original row values
can be restored. More than 64 terrain types can fall back to the original map.

This avoids repeated coordinates and visibility text while still returning every
known cell. Ordinary ASCII symbol replacement and run-length syntax were worse:
the existing one-character row representation is already compact, and extra
symbols, delimiters and counts are not necessarily cheap BPE tokens.

### Generic dictionaries are not automatically profitable

The original format already shares several kinds of content. Replacing a short
string with a reference can cost as many tokens as the string, while the dictionary
and marker add overhead. A frame-local dictionary cannot benefit from a string
that only repeats in later frames. This explains why the standalone long-text
dictionary lost on both complete corpora even though some individual frames gain.

Prefer source-backed action/field templates and exact local text reuse. A repeated
log string can have one dictionary entry and two references, but it must still
appear twice in its original positions.

## Removing non-game information: separate, small, deliberate projection

A separately labeled sensitivity experiment intentionally removes only:

- `ui.display.language/fullscreen` in a normal, nonmodal `GameScene` ready frame
  without relevant presentation/source diagnostics or unknown display fields;
- the exact known static `coverage.inspection_policy` prose in that context,
  while keeping coverage status and detail capabilities.

It does not change settings screens, partial observations, errors, nodes, layout
bounds, shortcuts, saving, descriptions or warnings. It is **not strict JSON
losslessness**, is not included in the best strict results above, and is not a
deployed policy.

| Metadata-only sensitivity | Tokens avoided | Reduction |
| --- | ---: | ---: |
| Historical corpus: display configuration in 2,738 ready frames | 27,380 | 0.276% |
| Current corpus: display configuration/static prose in 9 ready frames | 270 | 0.521% |

There may be other genuinely decorative nodes, such as a native build-version
watermark or a positively identified empty background. They require capture-side
classification. Current raw JSON does not reliably prove an arbitrary empty or
version-looking text node is decorative, so no such text whitelist was applied.
Adding a capacity summary alongside the entire UI tree would not itself save
tokens; replacing empty bag slots requires a complete source-backed bag contract.

Important counterexamples retained by every strict candidate:

- `t1.y` contains two bars for cell 691 with 14/21 and 20/32 rendered pixels. They
  have different rounding information; they are not one exact HP percentage.
- `t1.288` contains two identical self-target warnings for two distinct attempts.
  Text sharing may retain two references; event deletion may not.
- Empty slots can express capacity, while selector-disabled items are occupied
  slots. Neither an empty label nor `enabled:false` proves irrelevance.
- Scroll positions, content/viewport dimensions and shortcut names may be the
  only evidence for an available action or additional content.
- Goo droplets, target cells and bomb countdowns are gameplay cues. An empty cue
  list with `not_rendered` is not equivalent to observed absence of danger.
- A latest save receipt may belong to an earlier request while current `saves`
  is empty. Replacing persistence with a bare `saved:true` loses that distinction.
- IDs, scope, revision, execution status, current phase, errors and cancel/outcome
  bindings are operationally necessary even though they are not hero statistics.

## Proposed next protocol direction

Keep a **complete compact frame** rather than a cache/delta stream:

1. Preserve the entire current observation and original outcome boundaries.
2. Use one complete ordered action table plus same-frame control references.
3. Use readable records, local common-field templates, compact gesture notation
   and reversible control spelling. Keep unknown fields and a literal fallback.
4. Use a nested inventory and a declared map encoding chosen for the current map;
   every definition and visibility/environment value remains in the same frame.
5. Limit non-game omissions to positively classified fields in an explicitly
   defined game-facing view. Do not substitute a future inspection for information
   already present in this response.

These ideas could live at a model-facing controller representation boundary while
the recorder retains exact child traffic. However, expanding the compact packet
back into the full old JSON **before presenting it to the model** would remove the
token benefit. A production design also needs an actual model-readability and
gameplay-accuracy check; mathematical reversibility alone does not prove fewer
targeting mistakes. The research prototypes are not that deployment.

### Further semantic compression, not yet counted

The GUI tree is not identical to the game state. A future complete **game-semantic**
view can replace positively identified GUI plumbing with named sections:

```text
hero + buffs/talents/resources
bags: full nested contents + native capacity/used/free-entry counts
quickslots: six bindings + use/assign capabilities + counts/visibility/targeting
map: all known cells + visibility + environmental effects
entities: all public entities + every rendered health/status measurement
dialogs: current title/body/options/selection/constraints
messages: ordered occurrences, including duplicates
controls: available operations and their exact current bindings
unclassified_ui: any meaningful original nodes not covered by a proven mapping
```

The fallback is in **this response**, not a promise to obtain missing information
with a later inspection. Known fixed decorative backgrounds can then be omitted;
unknown or informative disabled/icon-only controls remain. All game facts already
present in the original capture must map to an explicit field, capability, message
or the same-frame fallback. Raw diagnostics can separately retain the original UI
tree, but storing it somewhere else does not make an incomplete game response
complete.

This could reduce more than strict restoration of every original node ID and
parent link. It requires capture-side ownership/type evidence and equivalence
fixtures for each mapped component, so no percentage is claimed here. In
particular, the raw corpus cannot establish that every label-less disabled node
is decorative, nor can it reconstruct the quickslot cross that the current
observer does not export. Such source-level observation gaps must be fixed, not
hidden by a smaller representation.

## Validation, artifacts and reproduction

The 23 candidates pass 89,700 independently decoded complete-frame comparisons.
Unit tests cover packed/expanded nodes, missing versus null, false/zero, duplicate
events, parentage, operation constraints, marker collisions, Unicode, historical
opacity, safe numeric identifiers, unknown map gaps, expanded visibility,
exceptional row segmentation, and more than 64 terrain descriptors. The final
Python discovery suite passed **228 tests**, including 46 dedicated standalone
codec/study tests, with no failures. The full corpus was rechecked after adding
explicit opacity for late responses, original outcomes and historical trees;
the measured totals remained unchanged.

No runtime Java or packaged CLI behavior changed, so the CLI remains 6.1.1 and no
new game session or package rebuild is part of this research-only batch. Generated
metrics, per-frame costs and complete examples are under the ignored directory
`desktop-control/build/standalone-token-study-20260920/`; game data/examples are
not committed. The source and decoder tests are committed for reproducibility.

```sh
# Use a separate environment with tiktoken==0.12.0, never an application dependency.
PYTHONDONTWRITEBYTECODE=1 /path/to/tokenizer-env/bin/python \
  desktop-control/src/test/python/standalone_token_study.py \
  --d9-trace '/absolute/path/to/the/depth-9/transport/session' \
  --current-fixture desktop-control/build/fixtures/cli6-controller-dec55f0671c14a4382b6c8cc0a029437 \
  --output desktop-control/build/standalone-token-study-20260920

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

The saved `metrics.json` identifies all source hashes and separates strict
round-trip results from the deliberately lossy metadata sensitivity. Definitions
and decoded values never depend on any other game response.
