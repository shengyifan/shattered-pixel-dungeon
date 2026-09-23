# spdctl: compact game control (protocol 8)

This manual is printed by `spdctl --help` and bundled with the application.
`spdctl --version` reports CLI.8.0.0, protocol 8, and base game 3.3.8.

## 1. Start and keep the connection open

```sh
spdctl control --machine
spdctl control --machine --data-dir "/absolute/path/to/profile"
spdctl control --machine --no-terminal --trace-dir "/absolute/path/to/records"
spdctl run --machine
spdctl run --machine --data-dir "/absolute/path/to/profile"
spdctl run --machine --no-terminal --trace-dir "/absolute/path/to/records"
```

The packaged macOS executable is
`Shattered Pixel Dungeon.app/Contents/MacOS/spdctl`. Quote paths containing spaces.
The package includes its JVM and SQLite library. The source checkout also supplies
`./bin/spdctl`, which builds and freezes a development runtime before launching it.

`control --machine` is the recommended bundled controller. It uses the packaged
runtime and owns one `run --machine` child; it needs no external Python or network
endpoint. The child alone records the actual machine bytes and opens the viewers.
Normal terminal/PTY startup needs no stdout-file workaround. The child's stderr
uses its own pipe; a separate byte forwarder sends diagnostics to controller stderr
without changing the controller's terminal flags. Keep stdout and stderr separate
when parsing NDJSON: intentionally merging them on one terminal can mix diagnostics
with displayed JSON. Child traces remain separate and byte-exact. Diagnostic display
is best-effort; after child exit its drain wait is bounded to 5 seconds.
The controller emits its initial `info` reply, then accepts flat JSON intents:

```json
{"op":"state"}
{"op":"move","rev":"r7","dir":"N"}
{"op":"click","rev":"r8","ctl":"c3"}
{"op":"settle","rid":"t2.7","timeout_ms":5000}
{"op":"cancel","rev":"a3","rid":"t2.7"}
```

These are **controller intents**, not direct wire requests. Omit `v/id`; the
controller supplies them and binds each action's explicit `rev` to the exact scope
of that already displayed observation. Example handles are illustrative. It never
refreshes a stale action onto a new observation, chooses targets, or replays work.
Queries default to the current scope; history queries can name an explicit known
`s`. Historical replies never update the controller's live revision/scope binding.

An initial `in_progress` is emitted immediately. Local `settle` (not the gameplay
`wait`) polls only small receipts at 100 ms intervals, up to 5 seconds by default;
`timeout_ms` is 1–5000. It defaults to the latest pending action when `rid` is omitted.
Its `controller:"settle"` wrapper keeps `outcome` (the original action's receipt),
optional `discovery`, and `observation` (fresh current state) separate. `st:"pending"`
means more settling or cancellation may follow, not permission to repeat the action.
Use the nested observation's own `s/rev`, not the wrapper's original request scope.
The bundled controller must finish pending actions through `settle`; manually
querying `req` or `state` does not clear its pending-action bookkeeping. An explicit
`rid` must identify an action submitted by this controller.
Local failures have `controller:"error"`, `err`, and the original request when known.
Malformed control/locator bindings and cell/direction types are rejected locally as
`INVALID_INTENT` before sending a game action. Use `ctl`, never `id`, for a control;
a packed node's leading integer is a template index, never its control ID. Always
check `err` and the controller wrapper before accessing a normal reply's `data`.
Lost/invalid replies block new game actions until the original outcome is established.
After complete-frame response loss, exceptional recovery may query the original
receipt; partial frames must first finish. A late original reply is retained under
`late_responses:[{request,response}]`, never substituted for the current observation.
If it accompanies a normal response, the controller uses
`{controller:"response",response:<current>,late_responses:[...]}`. Settling preserves
the original `transport_error` and any `initial_error` alongside recovery results.
The compact child data, source markers and immutable history are not expanded or
rewritten for model display. Unwrap `controller:"response"` before decoding its
current `response`; `late_responses` are historical evidence, not live bindings.
Successful quit waits for the child to exit; `controller:"exit"` reports an exit
wait or failure without creating a new game observation. Transport files contain
the actual child requests/replies, not controller intents or local wrappers.
An asynchronous quit keeps the request channel open until its exact successful
terminal receipt is delivered. The controller obtains that receipt with `settle`,
then only waits for exit. Unrelated queries cannot complete this shutdown. While
a successful quit awaits receipt delivery, new game actions fail `SESSION_CLOSING`.

The remaining request examples describe the direct `run --machine` interface.
That mode starts the GUI and serial NDJSON connection in one game JVM. Keep the
same process and stdin/stdout pipes open throughout play. Send one UTF-8 JSON object
per line, flush, and read its complete response before sending another request.
There is no network endpoint, background response stream, or attachment to another
Finder-launched instance. Screenshots, window focus and simulated OS input are not
needed to operate this connection.

Protocol 8 accepts only the short, flat format below. Older request envelopes,
operation names and `args` are rejected. The default profile is now:

```text
~/Library/Application Support/Shattered Pixel Dungeon CLI v8/
```

CLI 8 requires an intact schema-11 audit pair. A nonempty profile without a valid
schema-11 pair, including any older schema, is rejected before profile writes; it
is not migrated or deleted. Use a new profile.
Only genuinely absent or empty profiles receive the windowed Simplified-Chinese,
skip-introduction/tutorial defaults. Existing settings and ordinary GUI defaults
remain unchanged; class unlocks, achievements and guidebook progress are not granted.
`--help` and `--version` exit without starting a game or creating a profile.

## 2. Discover the state

Start with a unique request ID:

```json
{"v":8,"id":"q1","op":"info"}
```

`info` reports versions, build identity, current scope/revision, capabilities,
`request_prefix` (the persistent session handle), and a
static `schema` of commands, directions, row-map encoding and inspection rules. Copy the
returned `s`, then query the current state:

Protocol 8 advertises `gameplay_facts`, `semantic_ui`,
`semantic_feedback_events` and `world_cues_fov`. The protocol-7 rendered
appearance, floating-text, particle-metric and screen-visual capabilities are
not part of this version.

```json
{"v":8,"id":"q2","s":"<S>","op":"state"}
```

Replace placeholders and illustrative IDs with values for your own connection.
IDs may be short strings, for example a fresh client prefix plus a counter. They
must remain unique within their scope, including across restarts of the same run.
After the first handshake, use `request_prefix` plus a decimal counter allocated
before sending, such as `t2.9`, `t2.10`, `t2.11`; failures also consume IDs. The
counter suffix uses ASCII 0-9 only, never base36 letters. The bundled controller
does this. Opaque handle prefixes and explicitly supplied request IDs are not
rewritten, and existing history keeps its original identities.

| Field | Meaning |
| --- | --- |
| `v` | Required integer `8` on every request. |
| `id` | Required, caller-generated request identity. Queries also consume IDs. |
| `s` | Required scope, except `info` discovery requests. |
| `rev` | Required for game actions; copy the latest live revision exactly. |
| `op` | Query or action name. Action parameters are at the same object level. |
| `st` | Successful response status: `completed`, `awaiting_input`, `in_progress`, or terminal `interrupted`. |
| `data` | Response content. Live state has no extra observation wrapper. |
| `err` | Error code when a request fails. Success has no `err`; there is no `ok` field. |
| `pres` | Present when public presentation is partial, with `st` and field diagnostics `diag`. |

A live response uses the effective scope of its resulting state. Starting or
resuming a run can change `s`; update from that response. Scope/revision strings are
opaque: do not generate, shorten or reinterpret them. Restarting invalidates all
old revisions. Historical queries never return a top-level live `rev`.

Protocol 8 allocates durable typed base36 handles in the selected profile: `s` for
scope, `r` for normal revision, `a` for activity revision, `t` for session, `p` for
save receipt and `m` for map context (for example `s2`, `r7`, `a3`). They represent
complete canonical identities, including process epochs, and are never recycled in
that profile. Different profile directories may use the same short numbers; keep
the connection bound to the selected profile. UI controls use current `c` handles,
which are valid only for their observed binding. Old UUID wire tokens are rejected.
Request IDs and `rid` are caller identities, not registry handles. Multiple save
receipts can belong to one request, so `sid` must not be replaced by `src_id`.

The execution status `st` is separate from `data.phase`. A state query can complete
while the game is resolving, awaiting a choice, or holding an uncertain outcome.
Never treat a completed query as proof that the original action completed.

## 3. Read self-contained play and full observations

The default `play` response is self-contained, never a delta or a reference to an
older map. It includes applicable hero, inventory, map, entities, semantic UI,
world cues, phase, actions and persistence. Decode each reply independently.
Fields may be absent outside the dungeon; an alchemy/transition response need not
contain hero, inventory or map. Do not carry missing scene fields forward as live.

`state` and `actions` accept `view:"play"` (default) or `view:"full"`:

```json
{"v":8,"id":"q3","s":"<S>","op":"state","view":"full"}
{"v":8,"id":"q4","s":"<S>","op":"actions","view":"play"}
```

Both views retain item descriptions, the complete talent directory, semantic UI
facts and all operation constraints. Retained UI nodes keep their identity, order
and parent relationship; redundant passive display text can move into its public
subject's `shown`, `health_estimate` or `turn_progress`, or into `ui.feedback`.
All views use same-frame action sharing and record templates. `full` retains
explicit UI/item defaults and literal labels where a node needs its own label;
it is not an uncompressed wire format or a source of ordinary rendering internals.
Both still use protocol-8 row maps and knowledge semantics. Normal action replies use `play`.
`state src:true` automatically selects `full`, even if `view:"play"` is supplied.
Historical `before/after` details use full content and their own independent templates;
`raw/reply` are immutable. Expand compact records in the client, not the controller.

Names, prompts and descriptions use official English while the GUI may use any
registered language. Player/external text retains its original content and origin
markers. Fields with partial/clipped or protected source metadata are retained
conservatively rather than silently discarded by a default-value rule.

The field aliases remain:

| Canonical concept | Wire name |
| --- | --- |
| scope / state version | `s` / `rev` |
| control / direction / gesture | `ctl` / `dir` / `g` |
| item locator / original request ID | `loc` / `rid` |
| option / alternate | `opt` / `alt` |
| inventory / visible entities / world cues | `inv` / `entities` / `cues` |
| controls / global actions | `nodes` / `acts` |
| description / quantity / minimum / maximum | `desc` / `qty` / `min` / `max` |

The compact field aliases below do not change enum values, source AST
keys, or immutable raw/reply payloads.

| Canonical field | Wire field |
| --- | --- |
| `max_experience` | `mxp` |
| `max_hp` | `ht` |
| `subclass_name` | `sub_name` |
| `talent_points_available` | `tp` |
| `details_via` | `via` |
| `shortcut_action` | `shortcut` |
| `cell_prompt` | `prompt` |
| `continuous_activity` | `activity` |
| `snapshot_status` | `snap` |
| `inspected_item` | `item_info` |
| `saves_during_request` | `saves` |
| `last_save` | `saved` |
| `receipt_id` | `sid` |
| `origin_scope_id` | `src_s` |
| `origin_request_id` | `src_id` |
| `occurred_at` | `at` |

Unlisted fields keep their names. Actual game text and errors are not abbreviated.
In particular, the value `phase:"continuous_activity"` remains unchanged.

### Map

Every map has `w`, `h`, `types` and `rows`; absent `env` means no current environment
effects. Each row is `[y,x_start,tiles,visibility]`, one contiguous known segment.
Rows are sorted by y/x. Unknown gaps are omitted and must never be filled as walls
or safe floor. Multiple segments can occur on the same row.

For at most 64 types, `tiles` is a string indexing this message's `types` with:

```text
0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_
```

When the map contains more than 64 types, every row's `tiles` is an integer array.
In play, one visibility character repeats for the entire nonempty segment; otherwise
the visibility string length must equal the tile count. Full view expands visibility
per cell. Visibility characters have these meanings:

| Character | Meaning |
| --- | --- |
| `v` | `visible` |
| `s` | `visited` |
| `m` | `mapped` |

For offset i, the exact cell is
`y*w+x_start+i`. Types retain each complete public terrain descriptor and text
markers. The dictionary is local to this reply and may be reordered next time.

Example segment (the enclosing map supplies dimensions and its complete legend):

```json
[25,11,"0111122220","v"]
```

`env` is cell-indexed and complete for this reply, including fire, gas and other
visible effects. Its value is either an inline effect list or an integer index
into this map's `effect_defs`, whose entries are complete ordered effect lists. A missing entry means the effect is not currently observed.
No map baselines, references or patches are used. The encoder receives only the
existing public map projection, never hidden level terrain.
Exceptional whole-cell/coordinate source annotations that cannot be faithfully
relocated to rows or the type legend retain their original public evidence in
`map.preserved_cells`, with diagnostic paths rebased there. This is a diagnostic
block, not another map or a baseline: only `rows` defines the current known cells.

### Per-observation description dictionaries

Play can share repeated complete trap/container/character records in `data.entity_defs`.
A reference entity is exactly `{"cell":123,"def":0}`: expand entry 0 from the
current response's table, then attach that cell. The definition contains every
original field except `cell`, including source/partial metadata. Other entities
remain inline. Names alone never establish equality.

`map.effect_defs` entries are complete ordered effect lists. An integer env value
references one such list; an array remains inline. A cell can have several effects.
Both dictionaries use first-occurrence order and appear only when repetition makes
the encoded fragment smaller. Full/src and historical before/after keep descriptions
inline. An absent table cannot satisfy a reference; invalid indexes are errors.
Tables are local to this observation, not persistent identities or delta baselines.

```json
{"entity_defs":[{"kind":"trap","name":"Trap","desc":"Complete public warning"}],"entities":[{"cell":1,"def":0},{"cell":2,"def":0}],"map":{"w":4,"h":1,"types":[{"terrain":1,"name":"Floor"}],"rows":[[0,1,"000","v"]],"effect_defs":[[{"type":"visible_effect","desc":"Complete public effect"}]],"env":{"1":0,"2":0}}}
```

### Item knowledge and scoped defaults

For item records in `inv` or visible floor entities:

| Field | Absent | null | Explicit value |
| --- | --- | --- | --- |
| `level` | Not applicable | Player does not know | Known integer, including 0 |
| `cursed` | Not applicable | Player does not know | Known boolean; false means known uncursed |

The redundant item `level_known/curse_known` flags are removed. The independent
`ui.item_info.level_known` flag remains: it describes the already displayed
item-info branch and does not reveal an otherwise unknown numeric level.
If a knowledge pair itself has protected source/partial metadata, its original
value and explicit knowledge flag are retained together as a diagnostic exception.
Honor that explicit flag instead of inferring knowledge from the value alone.
Wand charge `current:null` remains unknown; an explicit 0 remains known empty.
Never delete null positions from a fixed-column array.

These defaults apply only to the listed play records, independently on each reply.
Protected source/partial fields remain explicit. Full view includes default fields.

| Record scope | Field | Default when absent |
| --- | --- | --- |
| `item` | `qty` | `1` |
| `item` | `equipped` | `false` |
| `item` | `available` | `true` |
| `item` | `type_known` | `true` |
| `item` | `via` | `click` |
| `ui_node` | `enabled` | `true` |
| `ui_node` | `dimmed` | `false` |
| `ui` | `modal` | `false` |
| `ui` | `item_info` | `null` |

The item `via` default describes inspection only; it does not advertise a node
operation. Missing `map.env` means no observed environment effects. These are not
global null/false/zero omission rules.
The explicit false/zero values that convey knowledge or availability are retained.

Play retains inventory/floor-item descriptions as well as container/mimic warnings,
trap/environment descriptions and current window text. Open the original item or
cell examination window for additional details only exposed there by the game.
No risk is inferred from description keywords and no descriptions are dropped.

`hero.talents` in both views retains all captured entries, including zero points. `tp`
contains current available points for tiers 1 through the hero's tier count in
order, normally four integers. This uses the original GUI rule, including bonus
points and subclass/armor gates. The original talent window shows its current choices.

### Controls and action discovery

In every view, `acts` retains the complete ordered operation list. A node's `ops`
array contains complete inline operation objects or zero-based integer references
into this observation's `acts`. A reference requires that action's
`ctl` to equal the node's `id`; expansion copies the action and removes the inherited
`ctl`. Explicit-control or independently protected operations stay inline. Order,
duplicate occurrences, labels and every dynamic constraint survive.
Absent, null or empty `ops` does not advertise a node action.

Three optional same-frame template tables compress records:

| Table | Record list |
| --- | --- |
| `data.act_templates` | `data.acts` |
| `data.inv_templates` | `data.inv` |
| `data.ui.node_templates` | `data.ui.nodes` |

A template is `{"common":{...},"fields":[...]}`. A packed record is
`[templateIndex, ...values]`; its values correspond exactly to `fields` and combine
with `common`. The two field sets must not overlap. Complete objects can remain at
their original indexes alongside rows, especially when source/diagnostic paths
protect them. A row's first integer is a template index, never a control or item ID.
Missing, null, false and zero remain distinct; no trailing values may be omitted.
Templates use deterministic first occurrence and are emitted only when the complete
fragment, including the table, has fewer UTF-8 JSON bytes. Token savings are measured
separately. JSON object member order is not semantic; array order always is.

Expand `acts` and `inv` templates first, then restore applicable activity/cancel
`rev/rid` bindings before copying referenced node operations. Expand UI node templates,
resolve ordered operation references and item labels, then apply remaining scoped
defaults. This order does not depend on JSON field order. References cannot borrow another frame or snapshot's
tables. Invalid indexes, duplicate fields, overlapping fields, wrong row widths and
control mismatches are decoding errors, never partial observations. Protocol 6's
`op_defs`, `node_shapes` and whole-operation-list indexes are unsupported.
Boolean and floating-point indexes are invalid. Detailed template validation and
snapshot boundaries are specified in `docs/cli8-implementation.md`.

```json
{"acts":[{"op":"click","ctl":"c3"},{"op":"click","ctl":"c4"}],"ui":{"node_templates":[{"common":{"role":"button"},"fields":["id","text","ops"]}],"nodes":[[0,"c3","Drink",[0]],[0,"c4","Throw",[1]]]}}
```

A current item-bound node may use `label:0` for its inventory item's exact `name`,
or `label:1` for the game's standard title-case rendering. Resolve using that node's
current `loc` and this observation's `inv`. Custom, unbound or protected labels stay
literal. Retained passive text leaves keep their captured identities and parent links.
Where labels remain, full/src and frozen before/after retain literal labels and
use the same record templates and action references. Each frozen snapshot is
decoded independently,
without inheriting a live envelope's scope/revision. Diagnostic/source-bearing nodes stay
expanded at unchanged indexes; ancestor-owned diagnostics keep their addressed UI
subtree expanded. Bars and other game evidence remain.

Same-response binding defaults apply independently to each live play reply:
omitted `activity.rev` inherits the envelope `rev`; a cancel capability's omitted
`rev/rid` inherits the current activity binding. The activity's original `rid`
remains explicit even when the envelope ID is a query. In save receipts, omitted
`s` inherits the envelope scope and omitted `src_s` inherits that receipt's scope;
different origins and explicit null remain explicit. Integer `persistence.saved`
indexes that response's `persistence.saves`, preserving every distinct receipt.
`data.saved` uses the same scope defaults. Full/source and historical details do
not need these omissions. Empty saves never prove a new save, and a recent saved
receipt does not mean the current action caused it.

Only `ops` advertises executable node actions. `{"op":"click"}` means the single
click gesture; multiple gestures are listed on that operation. Node-level fields do not
make a node actionable without an actual op. `acts` retains the complete advertised
action list in its original order, including node-bound operations. Node `ops`
reference the same capabilities, not additional actions to execute. Preserve list
order and all operation metadata when decoding either form.

Absent `enabled` on a play UI node means true; absent `dimmed` means false.
Disabled options with informative text, actionable controls without text, item
water counts/strength estimates, health/status information and
clipped/origin/partial markers are retained. Retained nodes preserve their
parents, including needed passive ancestors. Empty placeholders or duplicate
passive text may be omitted when their public facts are preserved in a subject
or feedback; protected source/diagnostic nodes stay. Fixed inventory and dynamic
bag slots remain because their structure/count is public information. The
projection does not use an English-label whitelist. Operation parameters,
arguments, modes, units, ranges, labels and unknown extra fields survive both views.

An item-bound node may have a current `loc` and a
`subject:{"kind":"item","loc":"..."}` only when the public item binding is
unambiguous. Other subject forms are `{"kind":"hero"}`,
`{"kind":"hero_buff","index":0}`,
`{"kind":"entity","index":0}` and
`{"kind":"entity_buff","entity":0,"index":0}`. Indexes refer only to this
observation. Virtual or ambiguous subjects remain unbound; labels, icons and
screen positions are never used to guess a subject. Already public item status,
extra and level text may appear in `inv[].shown`; preserve question marks and
unknown values. `actions` remains independently readable without an inventory
list. `health_estimate` reports visible bar samples, not hidden exact HP. Full view
retains semantic UI facts through reversible templates. Do not use
unmarked map crops or drop danger descriptions, `acts`, `cues`, shown counts,
estimates or bars when presenting an observation to a model.

Do not treat a dimmed control as disabled, infer hidden actions, or identify an
item permanently by its slot/control ID. Locators and node bindings can change
after a purchase, pickup, sort or UI transition. Use the current observation.
`actions` returns current UI/actions without repeating the world map. It has no
hero, inventory or entity table to resolve a `subject` reference. Instead, a
bound node contains direct current public target facts in `subject_data` (the
fact object, without a `kind` wrapper), and omits `subject`. If an owner is
unknown or ambiguous, `subject_data:null` and local
`pres:{st:"partial",diag:[...unresolved_subject...]}` retain the uncertainty;
captured label, `loc`, `ops` and other semantic fields remain. Node-level
`gestures` may be omitted only when its complete native `ops` already advertises
them. Parameter rules are in `info.schema`; current ops supply availability and
dynamic constraints.

For example, this standalone fragment needs no inventory table:

```json
{"acts":[{"op":"click","ctl":"c3"}],"ui":{"nodes":[{"id":"c3","subject_data":{"loc":"equipment.armor","name":"Armor"},"ops":[0]}]}}
```

### Semantic combat and UI information

CLI.8.0.0 reports public gameplay facts rather than renderer internals. An
inventory item or buff can carry `shown` with already public status, extra,
level, symbol, variant or counter strings. A buff may contain
`progress:{covered,total,basis:"displayed"}`; an item may contain a strength
estimate, flags or a semantic badge. Source-bound item status may include
`shown.broken_seal:true`, `shown.lit_candle:true`, or a named `shown.glow`
variant; FOV-visible heap items may expose the corresponding `item_status`
world cue. Neither glow pulse timing nor hidden durability/resin quantities
are reported. `shown.status_kind:"quantity"` marks only an exact, source-bound
native item quantity display. Matching numeric text alone cannot turn an
artifact charge or cooldown into a stack count; special or unknown status text
is retained. Warrior
Shield buff `shown.counter_kind` and `shown.progress_kind` distinguish
`shield` from `cooldown` using the selected native display branch. Hero or entity
`health_estimate.samples` contain visible bar samples
`{total,filled,with_shield,basis:"displayed"}`; these are not hidden exact HP.
The hero can carry `turn_progress:{sweep}`. `ui.feedback` is a current list of
semantic floating, log or banner feedback, separate from event history; an
icon-only floating occurrence retains its symbol without invented text. Missing
or partial indicators remain unknown where the game does not provide a sound
public value. A current `ui.nodes[].subject` links a hero, item, hero buff,
entity or entity buff only through explicit same-observation references above;
do not infer a subject from a label or decoration.

`data.cues.cues` contains public world cues sampled from existing game sources and
current map/FOV knowledge. Its `status` is `last_observed` or `not_observed`.
World-cue scope is independent of camera position, viewport and UI occlusion. A
cue cannot expose an unseen cell, hidden AI target, future action or remaining
timer. A cue's optional `appearance` contains reviewed semantic `style`,
`shape`, `paused`, `stage`, `cells`, `partial`, `count` or `symbol`; drawing
parameters do not. A radial `shape` such as `ring` or `halo` and
`coverage:"visual_extent"` describe a visible indicator, not damage
range; a beam with a hidden endpoint reports only known cells and `partial:true`.
`ward_state` may report the displayed Ward `tier` and, for tiers 1–3, a
`charge` fraction with `basis:"displayed_brightness"`; this is not the hidden
exact use count. Kinetic buff `shown.charge` uses
`basis:"displayed_gradient"`; both fractions quantize their displayed channel
to 8 bits before reporting, never a hidden float. `statue_armor` reports the
displayed armor tier. `crystal_spire_state` reports the selected Blue/Green/Red
stage as `intact`, `cracked`, `damaged`, `heavily_damaged` or `destroyed`. A current
Spirit Arrow projectile may carry `nature_powered:true` when its native
leaf-trail branch is displayed. Reviewed `GameplayBurst` kinds may carry
`appearance:{count,basis:"observed_particles"}` from actual visible
contributors, not a requested emission count. The special
`sacrificial_flames` density cue retains its exact current count too. In
`game.visual` history, quantity-only changes for counted kinds are sampled at
250 ms with `sampled_quantities:{<kind>:{sample_period_ms:250}}` when present;
discrete warnings, onset and disappearance remain immediate. There is no
`game.visual_metrics` event stream.
New cue/floating event bodies use `gameplay_snapshot_v1`, banners use
`gameplay_occurrence_v1`, and gameplay logs use `gameplay_log_snapshot_v1`.
These are semantic feedback, not visual frame samples. Read
them through the normal `events` query rather than unsolicited pipe replies.
`ui.feedback` is current feedback, separate from queried event history. A past
event is never a current action binding or target.

An unknown native indicator keeps `unmapped_indicator:true` and local partial
`pres` diagnostics with `field`, `code:"unmapped_indicator"` and a public
`indicator` variant. Never guess a missing Ward/statue tier or replace existing
diagnostics with the fallback.

No new play/full/src observation, event or frozen snapshot includes atlas/frame,
texture or transform data, RGB/tint/opacity, generic particle histograms, camera shake
or screen effects. `full` makes defaults/diagnostics explicit and `src` adds public
source provenance; neither requests ordinary rendered detail. Historical raw
transport and replies remain byte-for-byte immutable. Preserve public item
descriptions, text, constraints, semantic bars/counts/estimates and warning facts
when presenting a response to a model.

See `docs/cli-combat-visuals.md` for the semantic boundary and
`docs/cli8-implementation.md` for implementation and validation status. The
CLI.7.0.1 renderer contract is preserved separately as historical documentation.

The optional repository helper `desktop-control/client/spdctl_client.py` provides
strict per-frame decoding and intent validation without I/O or gameplay policy.
It separates controller errors, original outcomes, current observations and late
replies. See `docs/cli-playthrough-client.md` for its API; the packaged controller
does not require Python to launch or operate.

### Text sources and presentation limits

Full text-source trees are omitted by default. Request them explicitly:

```json
{"v":8,"id":"q4","s":"<S>","op":"state","src":true}
```

This returns the sources for that observation's own `rev`, not for an earlier state.
`text_sources` keys correspond to compact field names; their source AST is unchanged.
Default observations still retain partial-presentation diagnostics, clipping and
recursive user/external-origin markers. Source details do not reveal clipped or
otherwise undisplayed text/arguments. Presentation quality is independent of action
completion: never replay an action because its text is partial.

## 4. Flat action commands

Every action requires `v`, a fresh `id`, the current `s` and `rev`. All parameters
are top-level. Only execute currently advertised operations and targets.

| `op` | Additional parameters and original behavior |
| --- | --- |
| `move` | `dir`: `N`, `NE`, `E`, `SE`, `S`, `SW`, `W`, `NW`. One original directional input. |
| `cell` | Integer `cell`; optional `mode`: `act` (default), `examine`, `context`. |
| `item` | `loc` from current inventory; opens the original item menu. |
| `wait` | Original wait input. |
| `rest` | Original extended rest, which can remain in progress. |
| `search` | Original search, with its time cost. |
| `save` | Original save; inspect actual persistence receipts. |
| `quit` | Original quit, when currently available. |
| `cancel` | `rid` of the ongoing travel/rest request and its current activity `rev`. |
| `click` | `ctl`; optional `g`: `click` (default), `right`, `middle`, `long`, when advertised. |
| `choose` | `ctl`, zero-based integer `opt`; optional boolean `alt`, default false. |
| `select` | `ctl` of a described list entry. |
| `text` | `ctl`, `text`; optional boolean `submit`, default false. Replaces input text. |
| `value` | `ctl`, integer `value` within current bounds. |
| `scroll` | `ctl`; optional absolute content offsets `x` and `y`. |
| `bind_slot` | `ctl`, integer `slot` equal to 1, 2 or 3. |
| `bind_key` | `ctl`, integer `keycode` accepted by the current binding input. |
| `back` | Original Back/cancel behavior; may open another confirmation. |
| `reveal` | Reveal a faded title/hero-selection screen when advertised. |
| `zoom` | Integer `zoom` within current bounds. |
| `pan` | Optional relative `x` and `y`, default zero, in map-view units. |
| `untarget` | Cancel the current cell-target prompt. |

Examples:

```json
{"v":8,"id":"a1","s":"<S>","rev":"<REV>","op":"move","dir":"N"}
{"v":8,"id":"a2","s":"<S>","rev":"<REV>","op":"click","ctl":"<CONTROL>"}
{"v":8,"id":"a3","s":"<S>","rev":"<REV>","op":"cell","cell":123,"mode":"act"}
{"v":8,"id":"a4","s":"<S>","rev":"<REV>","op":"item","loc":"<LOCATOR>"}
{"v":8,"id":"a5","s":"<S>","rev":"<REV>","op":"text","ctl":"<CONTROL>","text":"example note"}
```

`123` is illustrative, not a predetermined destination. Movement can attack,
pick up, open doors or use stairs under the original rules; it is not teleportation
or a guarantee of exactly one turn. A remote cell can start native path travel.
Opening an inspection window is an action, and can mark information as read.
`completed` means the original input finished, not that travel reached its target:
walls, occupants, stairs and game rules still determine the actual result. Recheck
the returned hero cell/depth and prompt before the next decision. After a blocked
wand shot the target prompt closes; another `cell` is then ordinary map input.

To start a new game, select the title's Enter the Dungeon control, then New Game
if a save selector appears, choose an available class, and activate its Start
control. Selecting a class and starting are separate actions. Handle Continue and
other choices through the newly observed controls. If an existing profile has its
tutorial enabled, collect the displayed guidebook and follow its Journal prompt.

To use an item, open its current `loc`, select the displayed equip/read/drink/eat/
throw/ability control, then follow its actual item or cell prompt. Upgrading,
identifying, rewards and resurrection can require multiple choices. Class abilities,
talents, companions, shops, alchemy and endings use these same original controls.
There are no commands that grant equipment, skip class requirements or force a win.

## 5. Long actions, errors and recovery

An `in_progress` response is the sole response to that request on the pipe. Do not
repeat it. Poll its receipt with fresh query IDs:

```json
{"v":8,"id":"q5","s":"<ORIGINAL_SCOPE>","op":"req","rid":"<ORIGINAL_ID>"}
```

Inspect `data.st`, not the query's outer `st`. `RECEIVED` and `EXECUTING` mean the
original action remains pending. Normal terminal success is `COMPLETED`,
`AWAITING_INPUT` or `INTERRUPTED`. Retain that receipt, its original ID/scope and
its `save` receipts, then obtain one fresh live state:

```json
{"v":8,"id":"q6","s":"<CURRENT_SCOPE>","op":"state"}
```

Do not request historical `get:["reply"]` on the normal successful path. Reserve
it for explicit diagnostics, uncertain outcomes and immutable-history tests.
Keep the original action outcome and current observation separate: never fabricate
an original action reply from the fresh state query, or let history replace live
scope/revision. `REJECTED`, `UNKNOWN`, `err`, timeout and lost/invalid replies are
not success and cannot be cleared by a subsequent successful state query.
For a finite operation initially reported as `resolving` or `cancelling`, the old
scope may no longer be current after completion. After its successful terminal
receipt, discover the current scope with `info` once before requesting state.
Any discovery error remains an error. Ordinary `continuous_activity` needs no
extra discovery query, and neither branch obtains historical reply text.

A synchronous action reply is itself a current observation; it does not require an
extra state query. After a successful `quit`, await process exit without another
state request. During resolving/unknown execution, missing live `rev` and
`last_stable_state` indicate that no new actionable boundary was certified.
Never act using the revision inside that old snapshot.

At an interruptible travel/rest boundary, `data.phase` is `continuous_activity`
and `cancel` is advertised. Use its actual activity revision and original `rid`.
This differs from `untarget` and from closing a dialog. Other game actions are
rejected as `BUSY` while execution is pending.

| Error | Required interpretation |
| --- | --- |
| `DUPLICATE_REQUEST_ID` | The ID was already consumed. Query the original result; no replay occurs. |
| `STALE_STATE`, `STALE_ACTIVITY` | The revision is no longer current; reobserve and reconsider. |
| `SCOPE_MISMATCH`, `UNKNOWN_SCOPE` | Discover the active scope with `info`; keep past scopes for history only. |
| `ACTION_UNAVAILABLE`, `INVALID_ARGUMENT`, `INVALID_REQUEST` | Inspect current availability and parameter rules. |
| `PROTOCOL_VERSION_REQUIRED`, `INVALID_PROTOCOL_VERSION`, `UNSUPPORTED_PROTOCOL` | Every direct frame requires integer `v:8`; old envelopes are unsupported. |
| `UNKNOWN_REVISION` | The revision handle is unknown or has the wrong identity kind; obtain a current observation. |
| `EXECUTION_UNKNOWN`, `EXECUTION_UNCERTAIN` | Do not assume failure or replay; preserve uncertainty and inspect the original result. |
| `AUDIT_UNAVAILABLE` | The audit store failed; new game operations stop. |
| `SESSION_CLOSING` | A successful quit awaits its original receipt delivery; settle it and wait for exit. |

Before a machine connection exists, the launcher can report
`AUDIT_SCHEMA_UNSUPPORTED` on stderr for an older paired audit, or
`CLI_PROFILE_UNSUPPORTED` for a nonempty profile without a valid schema-11
audit pair. These are startup rejections, not replies to a game request; they
occur before profile writes. Choose a new CLI v8 profile.

Controller launch/stream failures also emit a stage-specific stderr diagnostic:
`CONTROLLER_CHILD_START_FAILED`, `CONTROLLER_OUTPUT_FAILED`,
`CONTROLLER_INPUT_FAILED`, or fallback `CONTROLLER_FAILED`, with an exception class
only, not arbitrary exception text or paths. `CONTROLLER_DIAGNOSTIC_READ_FAILED`
reports a diagnostic-pipe read failure. These are local diagnostics, not game
responses or proof of an action outcome. The controller creates no profile log for
them; retain child transport records and caller-captured output when diagnosing.

A controller may make a bounded new attempt after a definite stale rejection only
when its operating policy allows recovery. Obtain fresh state; verify scope, phase,
target, prompt, resources and advertised action; reacquire IDs/locators; then make a
new decision with a new ID and current revision. A matching label alone is not proof
of the same target. Stop if context changed or a small explicit attempt limit is
exhausted. If a response was lost, first establish the original outcome with `req`.
The server never substitutes a revision or silently replays an action. Completed,
pending and uncertain actions are not stale rejections. If the task requires stopping
on error, preserve evidence and stop game actions.

## 6. Selected history, saving and restart

`req` defaults to a small receipt: original `id/op/st`, applicable `err`, `save`
receipts and `has` detail selectors. It does not expand snapshots or the full reply.
For explicit diagnosis or verification, use `get` to select `raw`, `reply`,
`before`, `after`, or `meta`; `src:true` may be
used with frozen snapshot details. Example:

```json
{"v":8,"id":"q7","s":"<S>","op":"req","rid":"<ID>","get":["before","after","meta"],"src":true}
```

`before/after` are frozen public snapshots, not a fresh engine observation. `reply`
is the stored final logical result. `raw` describes the first exchange's original
request text, base64 frame bytes/framing, prepared response text and output-attempt
metadata; prepared or written does not prove consumption by the caller. No original
wire response or old snapshot is rewritten to match the current game.

Read paginated metadata and public events:

```json
{"v":8,"id":"q8","s":"<S>","op":"history","after":0,"limit":50}
{"v":8,"id":"q9","s":"<S>","op":"events","after":0,"limit":50}
```

`data` contains `items`, `next` (next `after`, or null), `end`, and a fixed `until`
upper sequence. Continue a page traversal by passing both `after: next` and the same
`until`. This finishes even with limit 1 while the paging queries themselves are
being audited. Omit `until` to start a new traversal or live-poll cycle. Cursors are
exclusive; history and events have separate sequences. Limits are 1–100, default 50.
Pagination is not byte truncation.

At a ready boundary use `save`; inspect `data.persistence.saves` for
actual successful save receipts. An empty list does not confirm a save. Live queries
report `saved` when available. In-memory action completion and persistence are
separate facts. Close prompts before `quit`; read its response and await process exit.
EOF requests ordinary lifecycle shutdown but cannot accept unresolved choices.

Restart with the same v8 profile, perform a new handshake and continue the displayed
saved game. Scope and request-ID history persist, but old process revisions do not.
Do not replay successful actions to make a restored save catch up with history.
Audit transactions do not include game save files. Public and internal SQLite stores
remain separate; the protocol does not expose general SQL or private snapshots.

## 7. Two independent Terminal viewers and complete transport

Machine mode records exact SEND/RECV bytes and stderr once, then automatically
opens two independent Terminal windows: **SEND** and **RECV + ERROR**. They retain
Terminal's current background and do not modify its global settings. Titles include
the selected channel and session identity. `--no-terminal` disables both automatic
windows, not recording. `--trace-dir` chooses an absolute root that must not overlap
the profile, including symlinks. The default root remains:

```text
~/Library/Logs/Shattered Pixel Dungeon CLI/transport/
```

Each session contains `send.raw`, `recv.raw`, `stderr.raw`, `events.tsv`,
`open-send.command` and `open-recv.command`. Transport format is still version 1.
The two command files directly view their own channel and do not create an extra
launcher window. `trace open` always generates commands from the current trusted
executable; it never executes a script loaded from the supplied session directory.

```sh
spdctl trace open --session "/absolute/path/to/session"
spdctl trace open --session "/absolute/path/to/session" --stream send
spdctl trace open --session "/absolute/path/to/session" --stream recv
spdctl trace view --session "/absolute/path/to/session" --stream all --color auto
spdctl trace view --session "/absolute/path/to/session" --stream send --color never
spdctl trace view --session "/absolute/path/to/session" --stream recv --color always
```

`--stream all|send|recv` defaults to `all`. For `trace open`, all opens two windows;
for direct `trace view`, all displays the combined stream. Send shows only SEND
transport records; recv shows RECV and ERROR (stderr, failing lifecycle status and
incomplete records). Viewer-local failures still produce diagnostics. DELIVERED
and successful lifecycle events remain recorded but hidden. Every index row and
raw range is validated even when its stream is not displayed.

Both views use normal-weight bright JSON colors: keys blue, strings green, numbers
yellow, booleans/null magenta; ordinary text and punctuation use the current
foreground without bold or dim. Only numbered SEND and RECV first-line headings
(including continued headings) are bold bright cyan, with the same color for both
directions. ERROR headings and bodies are normal-weight bright red. Introduction,
paths and ending prompts are not bold. Heading weight is reset before the body.
Dedicated windows force `--color always`.
Direct auto enables color for a TTY unless TERM is dumb or NO_COLOR is set; explicit
always/never override that rule. The viewer resets ANSI state when it exits.
Colors never enter machine output or raw files. Payload control bytes are safely
escaped, never executed. UTF-8, JSON strings and escapes can span arbitrary chunks;
original layout and complete content are retained without pretty-printing.

Opening is detached from protocol forwarding and bounded. A denied, failed or
uncertain Terminal launch reports its channel and reopen command; it is not blindly
retried. If Automation is denied, manually run `open-send.command` or
`open-recv.command`, or use direct `trace view` in an existing terminal. After an
uncertain launch, inspect existing windows before reopening to avoid duplicates.
Closing, signaling or reopening either viewer never closes the other,
stops the game/recorder or resends a request. A finished interactive viewer retains
its transcript and waits for Enter, as before.

Transport and viewing have no configured whole-message byte limit. The 64 KiB
buffers implement streaming/backpressure, not truncation; practical capacity still
depends on memory, disk and runtime resources. Outer tools may limit displayed text:
collect and decode the complete response through LF before presenting it. Never
parse a clipped excerpt as a complete response or replay an action because of it.
If recording fails, TRACE_IO_FAILED stops new requests and requests normal EOF
shutdown; already triggered responses are forwarded when possible. Raw records are
not removed automatically by the CLI. An incomplete marker means finalization was
not confirmed.

## 8. Minimal complete-response client

Prefer the bundled controller above. This direct-client Python example buffers the
complete response independently of any tool display limit, validates the response
envelope, and only prints selected state fields. The child records all raw bytes.
Adapt the loop while keeping the same process and pipes open; exceptions preserve
the original request identity and never authorize replay.

```python
import json
import re
import subprocess
import time
import uuid

launcher = "/absolute/path/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl"
profile = "/absolute/path/to/new-v8-profile"
process = subprocess.Popen(
    [launcher, "run", "--machine", "--data-dir", profile],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE,
)
prefix = "h" + uuid.uuid4().hex  # One random bootstrap prefix before info.
counter = 0

def request(op, *, scope=None, rev=None, **params):
    global counter
    counter += 1
    message = {"v": 8, "id": f"{prefix}.{counter}", "op": op, **params}
    if scope is not None:
        message["s"] = scope
    if rev is not None:
        message["rev"] = rev
    process.stdin.write(json.dumps(message, ensure_ascii=False).encode("utf-8") + b"\n")
    process.stdin.flush()
    line = process.stdout.readline()  # No application byte limit; reads through LF.
    if not line:
        raise RuntimeError(("Machine session ended before its response", message["id"]))
    if not line.endswith(b"\n"):
        raise RuntimeError(("Incomplete response; preserve request identity", message["id"], line))
    try:
        response = json.loads(line.decode("utf-8", errors="strict"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError(("Invalid complete response; preserve request identity", message["id"])) from error
    if not isinstance(response, dict) or type(response.get("v")) is not int or response["v"] != 8 or response.get("id") != message["id"]:
        raise RuntimeError(("Unexpected response identity/version", message["id"], response))
    if ("st" in response) == ("err" in response):
        raise RuntimeError(("Expected exactly one of st or err", message["id"], response))
    if "err" in response:
        if not isinstance(response["err"], str) or not response["err"]:
            raise RuntimeError(("Invalid error response", message["id"], response))
    elif response["st"] not in ("completed", "awaiting_input", "in_progress", "interrupted"):
        raise RuntimeError(("Invalid response status", message["id"], response))
    return response

def observe_after_action(initial, original_scope, action_name):
    # Consume incrementally: expose this complete initial reply before polling.
    # Do not wrap this iterator in list() or buffer it until the action finishes.
    yield ("initial", initial)
    if "err" in initial:
        raise RuntimeError(initial)  # Preserve original ID/outcome; never replay.
    if initial.get("st") != "in_progress":
        if action_name == "quit":
            process.wait(timeout=40)
        return  # The synchronous reply itself is the current observation.
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        receipt = request("req", scope=original_scope, rid=initial["id"])
        if "err" in receipt or "err" in receipt.get("data", {}):
            yield ("receipt", receipt)
            raise RuntimeError(receipt)
        status = receipt["data"]["st"]
        if status in ("COMPLETED", "AWAITING_INPUT", "INTERRUPTED"):
            yield ("receipt", receipt)  # Original outcome/save receipts stay separate.
            if action_name == "quit":
                process.wait(timeout=40)
                return
            scope = initial["s"]
            if initial.get("data", {}).get("phase") in ("resolving", "cancelling"):
                discovery = request("info")
                yield ("discovery", discovery)
                if "err" in discovery:
                    raise RuntimeError(discovery)
                scope = discovery["s"]
            current = request("state", scope=scope)
            yield ("observation", current)
            if "err" in current:
                raise RuntimeError(current)
            return
        if status not in ("RECEIVED", "EXECUTING"):
            yield ("receipt", receipt)
            raise RuntimeError(receipt)
        time.sleep(0.1)
    raise TimeoutError(initial)  # Preserve this request ID; establish its outcome.


try:
    hello = request("info")
    if "err" in hello:
        raise RuntimeError(hello)
    prefix = hello["data"].get("request_prefix")
    if not isinstance(prefix, str) or not re.fullmatch(r"t[1-9a-z][0-9a-z]*", prefix):
        raise RuntimeError(("Invalid request prefix", hello))
    counter = 0  # This newly allocated session prefix has not been used yet.
    state = request("state", scope=hello["s"])
    if "err" in state:
        raise RuntimeError(state)
    print(json.dumps({"s": state["s"], "rev": state.get("rev"),
                      "phase": state["data"].get("phase"),
                      "hero": state["data"].get("hero")}, ensure_ascii=False))
    # Keep the process and connection open in your controller loop.
    # Each reply is self-contained: expand its acts/inv templates, restore current
    # activity/cancel bindings, then resolve node templates/ops/labels and other
    # scoped defaults/visibility before choosing a new advertised action.
    # original_scope = state["s"]
    # initial = request(action_name, scope=original_scope, rev=state["rev"], **args)
    # stream = observe_after_action(initial, original_scope, action_name)
    # kind, initial = next(stream)  # Return this to the decision maker immediately.
    # At continuous_activity, inspect initial data/activity/acts and expose cancel.
    # A cancellation is its own request with a fresh ID and the advertised binding;
    # keep resolving the original request receipt rather than replaying its action.
    # Resume the iterator only when the decision maker elects to wait.
    # for kind, reply in stream: publish_complete_reply(kind, reply)
    # Never attach a new response header to a body loaded from an older cache.

finally:
    process.stdin.close()
    process.wait(timeout=40)
```
