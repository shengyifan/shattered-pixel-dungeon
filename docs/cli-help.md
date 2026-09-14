# spdctl: compact game control (protocol 4)

This manual is printed by `spdctl --help` and bundled with the application.
`spdctl --version` reports CLI.4.0.0, protocol 4, and base game 3.3.8.

## 1. Start and keep the connection open

```sh
spdctl run --machine
spdctl run --machine --data-dir "/absolute/path/to/profile"
spdctl run --machine --no-terminal --trace-dir "/absolute/path/to/records"
```

The packaged macOS executable is
`Shattered Pixel Dungeon.app/Contents/MacOS/spdctl`. Quote paths containing spaces.
The package includes its JVM and SQLite library. The source checkout also supplies
`./bin/spdctl`, which builds and freezes a development runtime before launching it.

Machine mode starts the GUI and serial NDJSON connection in one game JVM. Keep the
same process and stdin/stdout pipes open throughout play. Send one UTF-8 JSON object
per line, flush, and read its complete response before sending another request.
There is no network endpoint, background response stream, or attachment to another
Finder-launched instance. Screenshots, window focus and simulated OS input are not
needed to operate this connection.

Protocol 4 accepts only the short, flat format below. Older request envelopes,
operation names and `args` are rejected. The default profile is now:

```text
~/Library/Application Support/Shattered Pixel Dungeon CLI v4/
```

CLI 4 requires an intact schema-7 audit pair. Existing older audit directories are
rejected before profile writes; they are not migrated or deleted. Use a new profile.
Only genuinely absent or empty profiles receive the windowed Simplified-Chinese,
skip-introduction/tutorial defaults. Existing settings and ordinary GUI defaults
remain unchanged; class unlocks, achievements and guidebook progress are not granted.
`--help` and `--version` exit without starting a game or creating a profile.

## 2. Discover the state

Start with a unique request ID:

```json
{"v":4,"id":"q1","op":"info"}
```

`info` reports versions, build identity, current scope/revision, capabilities and a
static `schema` of commands, directions, row-map encoding and inspection rules. Copy the
returned `s`, then query the current state:

```json
{"v":4,"id":"q2","s":"<S>","op":"state"}
```

Replace placeholders and illustrative IDs with values for your own connection.
IDs may be short strings, for example a fresh client prefix plus a counter. They
must remain unique within their scope, including across restarts of the same run.

| Field | Meaning |
| --- | --- |
| `v` | Required integer `4` on every request. |
| `id` | Required, caller-generated request identity. Queries also consume IDs. |
| `s` | Required scope, except initial `info`. |
| `rev` | Required for game actions; copy the latest live revision exactly. |
| `op` | Query or action name. Action parameters are at the same object level. |
| `st` | Successful response status: `completed`, `awaiting_input`, `in_progress`, or terminal `interrupted`. |
| `data` | Response content. Live state has no extra observation wrapper. |
| `err` | Error code when a request fails. Success has no `err`; there is no `ok` field. |
| `pres` | Present when rendering is partial, with `st` and field diagnostics `diag`. |

A live response uses the effective scope of its resulting state. Starting or
resuming a run can change `s`; update from that response. Scope/revision strings are
opaque: do not generate, shorten or reinterpret them. Restarting invalidates all
old revisions. Historical queries never return a top-level live `rev`.

The execution status `st` is separate from `data.phase`. A state query can complete
while the game is resolving, awaiting a choice, or holding an uncertain outcome.
Never treat a completed query as proof that the original action completed.

## 3. Read self-contained play and full observations

The default `play` response is self-contained, never a delta or a reference to an
older map. It includes applicable hero, inventory, map, entities, meaningful UI,
visual cues, phase, actions and persistence. Decode each reply independently.
Fields may be absent outside the dungeon; an alchemy/transition response need not
contain hero, inventory or map. Do not carry missing scene fields forward as live.

`state` and `actions` accept `view:"play"` (default) or `view:"full"`:

```json
{"v":4,"id":"q3","s":"<S>","op":"state","view":"full"}
{"v":4,"id":"q4","s":"<S>","op":"actions","view":"play"}
```

`full` returns default-valued fields, empty UI nodes, ordinary item descriptions and
the complete talent directory. It still uses protocol-4 row maps and knowledge
semantics; it is not an older protocol. Normal action replies use `play`.
`state src:true` automatically selects `full`, even if `view:"play"` is supplied.
Historical `before/after` details use full projection; `raw/reply` are immutable.

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
| inventory / visible entities / visual cues | `inv` / `entities` / `cues` |
| controls / global actions | `nodes` / `acts` |
| description / quantity / minimum / maximum | `desc` / `qty` / `min` / `max` |

Unlisted fields keep their names. Actual game text and errors are not abbreviated.

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
Its length equals the visibility string length. Visibility characters are `v`
(visible), `s` (visited) or `m` (mapped). For offset i, the exact cell is
`y*w+x_start+i`. Types retain each complete public terrain descriptor and text
markers. The dictionary is local to this reply and may be reordered next time.

Example segment (the enclosing map supplies dimensions and its complete legend):

```json
[25,11,"0111122220","vvvvvvvvvv"]
```

`env` is cell-indexed and complete for this reply, including fire, gas and other
visible effects. A missing entry means the effect is not currently observed.
No map baselines, references or patches are used. The encoder receives only the
existing public map projection, never hidden level terrain.
Exceptional whole-cell/coordinate source annotations that cannot be faithfully
relocated to rows or the type legend retain their original public evidence in
`map.preserved_cells`, with diagnostic paths rebased there. This is a diagnostic
block, not another map or a baseline: only `rows` defines the current known cells.

### Item knowledge and scoped defaults

For item records in `inv` or visible floor entities:

| Field | Absent | null | Explicit value |
| --- | --- | --- | --- |
| `level` | Not applicable | Player does not know | Known integer, including 0 |
| `cursed` | Not applicable | Player does not know | Known boolean; false means known uncursed |

The redundant item `level_known/curse_known` flags are removed. The independent
`ui.inspected_item.level_known` flag remains: it describes the already rendered
item-info branch and does not reveal an otherwise unknown numeric level.
If a knowledge pair itself has protected source/partial metadata, its original
value and explicit knowledge flag are retained together as a diagnostic exception.
Honor that explicit flag instead of inferring knowledge from the value alone.
Wand charge `current:null` remains unknown; an explicit 0 remains known empty.
Never delete null positions from a fixed-column array.

In play item records only, absent `qty` means 1, `equipped` means false,
`available` and `type_known` mean true. These defaults are not general rules for
unrelated JSON objects; use explicit inspection routes where they are advertised.
The explicit false/zero values that convey knowledge or availability are retained.

Play omits ordinary inventory/floor-item descriptions. Open the original item or
cell examination window, or request full state, when that detail is needed.
Container/mimic warnings, trap/environment descriptions and current window text
remain available automatically. No risk is inferred from description keywords.

`hero.talents` in play lists invested talents only. `talent_points_available`
contains current available points for tiers 1 through the hero's tier count in
order, normally four integers. This uses the original GUI rule, including bonus
points and subclass/armor gates. Full state retains zero-point talent entries;
the original talent window still shows its current choices in play mode.

### Controls and action discovery

Only `ops` advertises executable node actions. `{"op":"click"}` means the single
click gesture; multiple gestures are listed on that operation. Ordinary duplicate
node-level gestures are omitted. Retained metadata-bearing gesture fields do not
make a node actionable without an actual op. `acts` contains non-node operations.

Absent `enabled` on a play UI node means true; absent `dimmed` means false.
Disabled options with informative text, actionable icons without text, item water
counts/strength estimates, health/status information and clipped/origin/partial
markers are retained. Only empty, noninteractive text leaves without extra state
or metadata are removed. Meaningful parent relationships remain intact.

Do not treat a dimmed control as disabled, infer hidden actions, or identify an
item permanently by its slot/control ID. Locators and node bindings can change
after a purchase, pickup, sort or UI transition. Use the current observation.
`actions` returns current UI/actions without repeating the world map. Parameter
rules are in `info.schema`; current ops supply availability and dynamic constraints.

### Text sources and presentation limits

Full text-source trees are omitted by default. Request them explicitly:

```json
{"v":4,"id":"q4","s":"<S>","op":"state","src":true}
```

This returns the sources for that observation's own `rev`, not for an earlier state.
`text_sources` keys correspond to compact field names; their source AST is unchanged.
Default observations still retain partial-rendering diagnostics, clipping and
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
{"v":4,"id":"a1","s":"<S>","rev":"<REV>","op":"move","dir":"N"}
{"v":4,"id":"a2","s":"<S>","rev":"<REV>","op":"click","ctl":"<CONTROL>"}
{"v":4,"id":"a3","s":"<S>","rev":"<REV>","op":"cell","cell":123,"mode":"act"}
{"v":4,"id":"a4","s":"<S>","rev":"<REV>","op":"item","loc":"<LOCATOR>"}
{"v":4,"id":"a5","s":"<S>","rev":"<REV>","op":"text","ctl":"<CONTROL>","text":"example note"}
```

`123` is illustrative, not a predetermined destination. Movement can attack,
pick up, open doors or use stairs under the original rules; it is not teleportation
or a guarantee of exactly one turn. A remote cell can start native path travel.
Opening an inspection window is an action, and can mark information as read.

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
{"v":4,"id":"q5","s":"<ORIGINAL_SCOPE>","op":"req","rid":"<ORIGINAL_ID>"}
```

Inspect `data.st`, not the query's outer `st`. `RECEIVED` and `EXECUTING` mean the
original action remains pending. Normal terminal success is `COMPLETED`,
`AWAITING_INPUT` or `INTERRUPTED`. Retain that receipt, its original ID/scope and
its `save` receipts, then obtain one fresh live state:

```json
{"v":4,"id":"q6","s":"<CURRENT_SCOPE>","op":"state"}
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
| `PROTOCOL_VERSION_REQUIRED`, `INVALID_PROTOCOL_VERSION`, `UNSUPPORTED_PROTOCOL` | Every frame requires integer `v:4`; old envelopes are unsupported. |
| `EXECUTION_UNKNOWN`, `EXECUTION_UNCERTAIN` | Do not assume failure or replay; preserve uncertainty and inspect the original result. |
| `AUDIT_UNAVAILABLE` | The audit store failed; new game operations stop. |

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
{"v":4,"id":"q7","s":"<S>","op":"req","rid":"<ID>","get":["before","after","meta"],"src":true}
```

`before/after` are frozen public snapshots, not a fresh engine observation. `reply`
is the stored final logical result. `raw` describes the first exchange's original
request text, base64 frame bytes/framing, prepared response text and output-attempt
metadata; prepared or written does not prove consumption by the caller. No original
wire response or old snapshot is rewritten to match the current game.

Read paginated metadata and public events:

```json
{"v":4,"id":"q8","s":"<S>","op":"history","after":0,"limit":50}
{"v":4,"id":"q9","s":"<S>","op":"events","after":0,"limit":50}
```

`data` contains `items`, `next` (next `after`, or null), `end`, and a fixed `until`
upper sequence. Continue a page traversal by passing both `after: next` and the same
`until`. This finishes even with limit 1 while the paging queries themselves are
being audited. Omit `until` to start a new traversal or live-poll cycle. Cursors are
exclusive; history and events have separate sequences. Limits are 1–100, default 50.
Pagination is not byte truncation.

At a ready boundary use `save`; inspect `data.persistence.saves_during_request` for
actual successful save receipts. An empty list does not confirm a save. Live queries
report `last_save` when available. In-memory action completion and persistence are
separate facts. Close prompts before `quit`; read its response and await process exit.
EOF requests ordinary lifecycle shutdown but cannot accept unresolved choices.

Restart with the same v4 profile, perform a new handshake and continue the displayed
saved game. Scope and request-ID history persist, but old process revisions do not.
Do not replay successful actions to make a restored save catch up with history.
Audit transactions do not include game save files. Public and internal SQLite stores
remain separate; the protocol does not expose general SQL or private snapshots.

## 7. Terminal viewer, color and complete transport

Machine mode records exact SEND/RECV bytes and stderr, and automatically opens an
independent Terminal viewer. `--no-terminal` disables only automatic opening.
`--trace-dir` selects an absolute recording root that must not overlap the profile,
including through symlinks. The default root is:

```text
~/Library/Logs/Shattered Pixel Dungeon CLI/transport/
```

Each launch creates a session with `send.raw`, `recv.raw`, `stderr.raw`, `events.tsv`
and an `open-viewer.command`. Closing/reopening the viewer does not close the game.

```sh
spdctl trace open --session "/absolute/path/to/session"
spdctl trace view --session "/absolute/path/to/session" --color auto
spdctl trace view --session "/absolute/path/to/session" --color never
```

The viewer shows SEND, RECV and ERROR. DELIVERED and normal lifecycle events remain
recorded but are not displayed. Stderr, failed exits/signals and incomplete records
are ERROR. Consecutive chunks of one message share a heading; direction switches
show continuation headings when necessary. Original JSON layout is retained.

`--color auto|always|never` applies to `trace view`. Auto enables ANSI colors for a
TTY unless TERM is dumb or NO_COLOR is set; explicit modes override the environment.
SEND is cyan, RECV green, ERROR red; JSON keys blue, string values green, numbers
yellow, booleans/null magenta and punctuation dim. Invalid JSON still displays safely.
UTF-8, strings and escapes can cross arbitrary chunks. Input escape/control bytes
are visibly escaped, never executed. Viewer colors never enter protocol or raw files.

There is no configured whole-message byte limit and no body truncation in transport
or the viewer. The 64 KiB native buffers are bounded streaming/backpressure buffers,
not message limits. Java accumulates a complete NDJSON frame. Actual capacity still
depends on memory, storage and OS/runtime resources. Identifier lengths, JSON nesting,
source-rendering safeguards, page sizes and game input rules retain their semantics.

An outer AI/shell tool may truncate what it displays. That tool's output budget is
not a spdctl limit: collect and decode the complete pipe response first, then present
selected information to the model. Do not parse a tool-truncated JSON excerpt as a
complete protocol response or resend a game action because its display was clipped.

If recording fails, TRACE_IO_FAILED stops new requests and requests normal EOF
shutdown; already triggered responses are forwarded when possible. No action is
retried or response fabricated. An incomplete marker means finalization was not
confirmed. Raw records remain until explicitly removed.

## 8. Minimal complete-response client

This Python example buffers the complete response independently of any tool display
limit, validates its ID, and only prints selected state fields. Adapt its controller
loop while keeping the same process and pipes open.

```python
import json
import subprocess
import time
import uuid

launcher = "/absolute/path/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl"
profile = "/absolute/path/to/new-v4-profile"
process = subprocess.Popen(
    [launcher, "run", "--machine", "--data-dir", profile],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE,
)
prefix = uuid.uuid4().hex[:12]
counter = 0

def request(op, *, scope=None, rev=None, **params):
    global counter
    counter += 1
    message = {"v": 4, "id": f"{prefix}-{counter}", "op": op, **params}
    if scope is not None:
        message["s"] = scope
    if rev is not None:
        message["rev"] = rev
    process.stdin.write(json.dumps(message, ensure_ascii=False).encode("utf-8") + b"\n")
    process.stdin.flush()
    line = process.stdout.readline()  # No application byte limit; reads through LF.
    if not line:
        raise RuntimeError("Machine session ended before its response")
    response = json.loads(line)
    if response.get("id") != message["id"]:
        raise RuntimeError("Unexpected response ID")
    return response

def observe_after_action(initial, original_scope, action_name):
    # This is a local derived result, never an original protocol response.
    result = {"initial": initial, "receipt": None, "discovery": None, "observation": None}
    if "err" in initial:
        raise RuntimeError(result)
    if initial.get("st") != "in_progress":
        result["observation"] = initial
        return result
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        receipt = request("req", scope=original_scope, rid=initial["id"])
        result["receipt"] = receipt
        if "err" in receipt:
            raise RuntimeError(result)
        outcome = receipt["data"]
        if "err" in outcome:
            raise RuntimeError(result)
        status = outcome["st"]
        if status in ("COMPLETED", "AWAITING_INPUT", "INTERRUPTED"):
            if action_name != "quit":
                live_scope = initial["s"]
                if initial.get("data", {}).get("phase") in ("resolving", "cancelling"):
                    discovery = request("info")
                    result["discovery"] = discovery
                    if "err" in discovery:
                        raise RuntimeError(result)
                    live_scope = discovery["s"]
                current = request("state", scope=live_scope)
                result["observation"] = current
                if "err" in current:
                    raise RuntimeError(result)
            return result
        if status not in ("RECEIVED", "EXECUTING"):
            raise RuntimeError(result)
        time.sleep(0.05)  # Only the pending-receipt poll cadence.
    raise TimeoutError(result)  # Preserve the ID; never replay the action.

try:
    hello = request("info")
    if "err" in hello:
        raise RuntimeError(hello)
    state = request("state", scope=hello["s"])
    if "err" in state:
        raise RuntimeError(state)
    print(json.dumps({"s": state["s"], "rev": state.get("rev"),
                      "phase": state["data"].get("phase"),
                      "hero": state["data"].get("hero")}, ensure_ascii=False))
    # Keep this connection open for observe -> decide -> action -> inspect result.
    # Capture original_scope before dispatch. A reply can carry a new live scope.
    # For an advertised action:
    # original_scope = state["s"]
    # initial = request(action_name, scope=original_scope, rev=state["rev"], **args)
    # derived = observe_after_action(initial, original_scope, action_name)
    # Sync outcome/save receipts are in initial; async ones are in receipt.
    # Use derived["observation"] for the next decision, never a historical reply.
finally:
    process.stdin.close()
    process.wait(timeout=40)
```
