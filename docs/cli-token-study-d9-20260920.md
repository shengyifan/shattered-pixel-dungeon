# Depth-9 public CLI corpus: model-token study and decimal request IDs

> Updated requirement: every response must remain independently complete. The
> stateful cache/delta candidates below are historical experiments, not the current
> recommendation. See the [complete single-frame study](cli-complete-frame-token-study-20260920.md)
> for measured alternatives that do not depend on another response or lookup.

## Status and measurement boundary

The format experiments in this document are **offline proposals, not deployed
wire changes**. The implemented runtime change in CLI.6.1.1 is decimal generated
request-ID counters. Protocol 6, schema 9 and self-contained play/full responses
remain in production. A future protocol may break compatibility; the experiments
therefore do not optimize around retaining old syntax.

The source is the completed Warrior connection recorded by CLI.6.0.1. Its complete
session has 3,887 matched SEND/RECV pairs. This study analyzes the first **3,880
pairs**, from handshake through `t1.2zs`, the depth-9 death. It excludes the seven
later exchanges that returned to the menu and started/quit another Warrior.

Only explicitly selected public `send.raw` and `recv.raw` files are read. No game,
save or private audit database is opened. Full-file hashes before and after match:

- SEND: `dbc8c87b5d53a822f18638b51ec8e5d139fa569be7259e1842db2cd807b58747`
- RECV: `9ec5211224ed8ae09deab183d207effc84d362059c40a6caf308968e7b2e35c6`

Tokenizer: **tiktoken 0.12.0 / o200k_base**, each complete UTF-8 NDJSON frame including
LF. Minified reserialization and the original wire produce identical baseline
counts for this prefix. These are reference format tokens, not actual model bills:
controller wrappers, generated tool code, display clipping, context reuse and
cache pricing are outside this metric. The controller's internal receipt polling
is not necessarily another model-authored request.

Crucially, this is not the current CLI.6.1 response baseline. CLI.6.0.1 had already
removed ordinary item descriptions and some UI nodes; CLI.6.1.0 restored them.
The old records cannot reveal their missing original values. Every experiment
preserves everything present in its input; no missing capacity, item type,
targeting state or UI node is invented.

## Where the tokens went

The prefix contains **118,587 request tokens** and **9,924,866 response tokens**.
Requests are only about 1.18% of the combined total. A typical response is much
larger: median 2,794, p95 3,447, maximum 4,094 tokens.

| Response section | Standalone fragment tokens | Share of fragment sum |
| --- | ---: | ---: |
| UI | 4,333,789 | 44.07% |
| Map | 2,410,775 | 24.51% |
| Inventory | 1,440,876 | 14.65% |
| Hero | 588,180 | 5.98% |
| Visible entities | 520,717 | 5.30% |
| Actions | 178,127 | 1.81% |
| Persistence | 159,327 | 1.62% |

These are separately tokenized `{field:value}` fragments, so their percentages
are not an additive allocation of the complete-message count. Nevertheless the
dominant repeated material is clear: UI, map and inventory together account for
about 83% of fragment tokens.

There are 1,547 directional moves, 770 cell inputs, 411 clicks, 176 item openings,
464 state queries and 339 receipt queries. The 3,529 inventory observations contain
70,735 item records and at most 31 records in one frame. Nested bag contents occur
in 3,406 frames. Only **two** item records still carry an inline description in
this old corpus, so it cannot fairly measure the full benefit of caching the
descriptions restored in CLI.6.1.0.

| Section | Frames containing it | Equal to its preceding value in the same context | Distinct complete values |
| --- | ---: | ---: | ---: |
| Inventory | 3,529 | 3,168 (89.77%) | 295 |
| Map | 3,529 | 1,716 (48.63%) | 1,629 |
| Visible entities | 3,529 | 2,416 (68.46%) | 894 |
| UI | 3,534 | 960 (27.16%) | 2,446 |
| Actions | 3,534 | 2,251 (63.70%) | 7 |

Context includes scope, scene, depth, map identity and UI scene. Equality is exact
JSON-value equality, including null/false/zero, not a label-based heuristic.

## Measured alternative formats

Each full-frame candidate was encoded and decoded across **all 3,880 pairs** and
compared to its source JSON values. Error replies and receipts remain separate;
none of these operations was sent to the game. Definitions, references, sequence,
base and refresh overhead are included in the relevant candidate counts.

| Candidate | Response tokens | Reduction versus source | Median response |
| --- | ---: | ---: | ---: |
| Original complete protocol-6 frames | 9,924,866 | — | 2,794 |
| Readable section-per-line framing | 9,964,952 | **-0.40%** | 2,806 |
| Inventory column/row tables | 9,742,759 | 1.83% | 2,745 |
| Inventory tree with positional current locators | 9,464,470 | 4.64% | 2,674 |
| Exact public item definition catalog | 9,749,836 | 1.76% | 2,746 |
| Item catalog, reset every 25 item frames | 9,781,988 | 1.44% | 2,753 |
| Explicit whole-section replacement deltas | 5,484,091 | 44.74% | 1,510 |
| Recursive JSON-value deltas | 3,564,611 | 64.08% | 766 |
| Recursive deltas, full refresh every 25 observations | 3,823,164 | 61.48% | 1,008 |

The inventory tree removes repeated `backpack.N.M` prefixes, preserves item order
and every field, and reconstructs the exact locators for that frame. It does not
make them persistent identities and does not infer capacities. Unfamiliar layouts
fall back unchanged.

The catalog interns only exact already-disclosed `name`, `desc` and `type_known`
tuples; instance fields and metadata remain inline. New public content means a new
definition. It never reads the true class of an unidentified item. Its modest
historical result should not be generalized to the restored description payload.

Deltas use explicit sequence/base bindings and complete snapshots on context
changes. Field absence in a delta has a different, explicitly tagged meaning from
field absence in today's self-contained observation; deletions are explicit. Array
order and null positions are preserved. The recursive experiment chooses smaller
UTF-8 edit lists versus replacement as a deterministic heuristic; it is not a
proof of globally minimal tokens. It also produces paths that are less pleasant
for a model to read than named whole-section updates.

The refresh experiment is sensitivity analysis, not an observed frequency of
model-context loss. Additional lookup, missing-definition recovery, negotiation
and user-level explanation costs are not measured. Candidate percentages cannot
be added together.

## 1. Fixed item information

There is reusable content, but an item name is not an item identity or a guarantee
of a fixed description. The full recorded session has 64 distinct rendered names;
87 of its 122 locator strings held more than one name, and 53 names appeared at
multiple locators. Those figures include the small trailing restart sequence.

Concrete transitions include unknown `scroll of BERKANAN` becoming `scroll of
upgrade`, a fishing spear gaining the displayed polarized curse, armor gaining a
glyph name, and wand charges changing 1 → 0 → 2 → 3. Item-window text can depend on
identification, equipment, level, hero strength, curse, remaining durability or
previous use. `desc()` is not universally a static catalog string.

A useful future split is:

- Build/text-format/language-bound public definitions: already revealed name,
  category, displayed description, supported ordinary actions and static rules.
- Current per-instance values: locator or epoch-bound visible handle, quantity,
  equipment/availability, known level/curse, charges, rendered requirements/counts,
  and any text that changed in this observation.
- Original item-window inspection for contextual detail not present in the normal
  public capture. Batch `lookup` can resend missing immutable definitions without
  taking a game turn.

Definitions must be invalidated or newly allocated when their public content
changes. A new run must not inherit the old run's appearance-to-type knowledge.
Never send an unidentified item's hidden class as a convenient catalog key.
For readability, retaining a short public name beside a reference may be worth
more tokens than forcing the model to remember a bare number.

## 2. Bags, all contents, and empty space

The current `inv` is a **flat recursive listing**, not just the visible bag tab.
`PlayerObservation.inventory()` emits the nonempty equipment slots and recursively
walks every bag. A bag item itself and all its children are present, with paths
such as `backpack.16.3`. LostInventory changes `available`, not membership. Ground
items belong to `entities`; zero-quantity quickslot placeholders and transient
thrown/ability objects are not ordinary stored inventory.

Sources: `PlayerObservation.java:140-193`, `Bag.java:84-132`,
`Belongings.java:54-70`, `Item.java:218-278` in the current checkout.

At request `t1.237` (source RECV line 2708), the 31 inventory records comprise:

| Container | Actual entries | Capacity from source and disclosed equipment | Free entries |
| --- | ---: | ---: | ---: |
| Equipment | 2 | Separate equipment slots | — |
| Root backpack, including its two bags | 18 | 22 | 4 |
| Velvet pouch | 7 | 19 | 12 |
| Magical holster | 4 | 19 | 15 |

This is a source-backed example, **not capacity fields currently emitted by the
CLI**. The root starts at 20, gains one capacity per contained bag and loses one
when a secondary weapon occupies a backpack slot. Specialized bags each have
their own accepted categories. Slot counts count item stacks/objects, not `qty`:
three rations in one stack use one entry. A full bag can still accept an item that
merges with a compatible stack, so `free_entries:0` must not mean “all pickups fail.”

Currently `inv` has no fake records for empty slots, but also no structured
`capacity/used/free_entries`. Empty-space repetition is in the UI tree. Current
CLI.6.1 retains those captured empty nodes; older CLI.6.0.1 removed some fixed-grid
placeholders. A selector-disabled item is not an empty slot, and one bag tab's
25-slot visual layout is not the total inventory capacity.

A future semantic bag view should return nested contents plus native
`capacity`, `used_entries`, `free_entries` and a category-rule reference. It can
replace unneeded empty UI records in a clearly specified model-facing view while
retaining original `full/src` inspection. Adding the summary alongside the entire
old tree would improve usability but would not save tokens.

## 3. Quickslots

The game has six logical quickslots. Screen layout can show fewer at once, so
binding, visibility and usability are separate. A filled slot is represented by
several UI layers: a wrapper, an assignment button and an item/use button, often
plus status text and the selector control.

In the studied prefix, 3,206 frames expose quickslot-related nodes: 60,580 nodes
in total, generally 19 per frame for six slots. Their existing packed-row fragments
cost **1,167,565 tokens**. This excludes shared `node_shapes/op_defs` table costs.
At `t1.237`, six slots occupy 19 of 70 UI nodes, although only the throwing stone
and waterskin are bound and slots 3–6 are empty.

A concise semantic representation could use six records containing the slot,
current item reference or placeholder description, use and assignment control
bindings/gestures, visibility, availability and rendered count/requirement data.
Empty slots can be listed by slot number or a six-bit occupancy mask; partially
visible slots cannot simply be deleted. Zero-quantity placeholders must not be
mistaken for empty slots.

The current wire does not separately expose the quickslot targeting cross images.
A future view should safely capture the visible targeting state, not guess it or
call `autoAim()` during observation. Original node identity and hierarchy must
remain available in a detailed view; dropping them silently would repeat the
lossy-compaction mistakes fixed in CLI.6.1.0.

An additional field-only experiment caches the **entire expanded node block**
without deleting any node fields, keyed by scope/depth/map identity. It sends an
immutable definition for a new block, a reference for an exact repeat, and a full
current block after at most 24 reference-only appearances. Exact source controls,
operations and display fields remain in the definition; no targeting data is
invented. This is measured separately from the full-frame candidates and is not an
additional percentage that can be added to their savings.

Across those 3,206 frames and 17 contexts, there are 158 new definitions, 2,965
references and 83 forced refreshes. The fully expanded field would cost 2,452,371
tokens; the candidate definition/reference field costs **201,673**. Compared with
the already packed source rows (1,167,565), this is an 82.73% reduction of that
subcomponent before accounting for interactions with the shared UI tables. It is
not an 82.73% reduction of the full response. All 3,206 blocks round-trip exactly.
A fresh model context still needs definitions; periodic refresh alone is not a
complete missing-definition recovery protocol.

## 4. Decimal generated request counters

Implemented in **CLI.6.1.1**: `t1.9 → t1.10 → t1.11`, and `t1.99 → t1.100`.
Only the generated counter suffix changes. Session prefixes and other opaque
handles retain their advertised spelling; bootstrap randomness and explicitly
supplied IDs are not counters. Failed requests still consume an allocation,
restarts use a new prefix, and historical IDs/raw records remain untouched.

An offline identity-only rewrite of this corpus changes request tokens from
118,587 to 118,615 (+28), and response tokens from 9,924,866 to 9,924,494 (-372).
The combined difference is only **344 tokens saved**, about 0.0034%. Decimal is a
readability improvement; string length alone does not predict tokenizer cost.

## 5. Recommended direction for a new model-facing protocol

The largest measured opportunity is reducing repeated observations. My preferred
first design is **semantic sections with explicit whole-section updates**, then
more selective deltas only where the model can read them reliably:

1. Negotiate version/build/text format and stream epoch once. Keep each response's
   request outcome, current revision, phase, errors and save receipts unambiguous.
2. Use item definitions, a nested bag view with explicit capacity, and a six-slot
   quickbar instead of requiring the model to interpret repeated UI plumbing.
3. Send a complete initial snapshot and explicit `base/seq` updates for changed
   sections. Use semantic map-cell patches if adopted, not blindly comparing
   per-frame terrain dictionary indexes. Include visibility/effect removals.
4. Supply an explicit full resynchronization and batched definition lookup.
   Unknown bases/definitions stop decisions; context compaction, reconnects,
   scope/scene/map changes and uncertain transport require deliberate recovery.
   Historical replies never install a live baseline.
5. Preserve original raw transport and full public inspection. The controller may
   hold complete data internally while showing the model named changes. Expanding
   every cached value back into the model's input would erase the token benefit.

The measured 44.74% whole-section reduction is a more readable starting point than
arbitrary deep paths such as a packed node's numeric array offset. The 64.08%
recursive result shows further potential, not that raw JSON patches are the best
model interface. The inventory tree alone is stateless and already saves 4.64%
on this older corpus, making it a useful low-complexity component.

Moving `v/s/id` out of a model intent can reduce this corpus's hypothetical request
strings from 118,587 to 53,650 tokens, but `control --machine` already does that
for model-authored intents. It does not avoid the dominant response cost. Binary
compression or base64 can save transport bytes without saving model tokens.
Merely changing JSON punctuation to readable section lines was slightly worse.

Interaction frequency is also relevant: native interruptible travel can avoid
many single-step requests, and synchronous success already supplies an observation.
There are 119 default `state` queries immediately after an observation with the
same scope/revision; only 18 also have an exactly identical body (49,271 response
tokens). These are candidates for a caller audit, not proof they were unnecessary:
the preceding frame might have been clipped or not consumed. Receipt settling,
fresh-state requirements and target/prompt checks must not be skipped.

## Reproduction and artifacts

```sh
python3 -m venv desktop-control/build/token-study-env
desktop-control/build/token-study-env/bin/python -m pip install 'tiktoken==0.12.0'
PYTHONDONTWRITEBYTECODE=1 desktop-control/build/token-study-env/bin/python \
  desktop-control/src/test/python/d9_token_study.py \
  --trace-dir '/absolute/path/to/the/recorded/session' \
  --end-id t1.2zs \
  --output desktop-control/build/token-study-d9-20260920
```

The executed run reused an already installed isolated tokenizer environment.
Generated `metrics.json`, per-frame counts and complete before/after examples at
depths 1, 7 and 9 live under the ignored output directory above. Personal raw data
and example frames are not added to git. The script verifies source hashes after
analysis and includes errors, definitions and refresh packets in its measurements.

## CLI.6.1.1 production validation

Validation completed locally on 2026-09-20. The no-cache Gradle gate executed all
34 tasks and passed **536 Java tests** (18 protocol, 330 game control, 188 desktop
control), with zero failures, errors or skips. Python discovery passed **182
tests**, including seven experiment-codec tests and three active allocator tests.
No game was started by those offline codec/allocator tests.

The actual rebuilt ARM64 package passed version, source/bundled/executable help
equality, packaged catalog identity, test-class exclusion and deep/strict ad-hoc
signature checks. It reports `CLI.6.1.1 (protocol 6, game 3.3.8)` with build ID
`5b7c8c04a18b52302f2ae7e705ac778b709f645fb50c67b9a6aedffe0c12c5b0`.
Help is 43,833 bytes, SHA-256
`5f380652cb6dffb357633283e9ebe049afe0b59c27db37f4c4ed572f54b941ef`.

Using a new disposable profile with only packaged public controls, the smoke test
created a Warrior, verified equal decoded play/full observations, saved, quit,
restarted, continued, saved and quit. Its two sessions issued 13 and 7 wire
requests and produced four distinct save receipts. Original raw bytes confirm
`t1.9` followed by `t1.10`, every generated suffix contains only decimal digits,
old revisions are rejected locally, saved state matches on restart, and no state
query follows successful quit. Both test sessions exited.

Actual package artifact:
`desktop-control/build/fixtures/cli6-controller-dec55f0671c14a4382b6c8cc0a029437/result.json`.
The test did not read or modify the user's profiles. This is fixture validation,
not a real clear or production deployment of the proposed cache/delta formats.
