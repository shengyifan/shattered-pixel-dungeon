# spdctl: playing Shattered Pixel Dungeon through the CLI

This manual is printed by `spdctl --help` and is bundled with the application.
Run `spdctl --version` for the CLI, protocol, and base game versions.

## 1. Start a machine session

```sh
spdctl --help
spdctl --version
spdctl run --machine
spdctl run --machine --data-dir "/absolute/path/to/profile"
```

In a packaged macOS application, the executable is
`Shattered Pixel Dungeon.app/Contents/MacOS/spdctl`. Quote paths containing spaces:

```sh
"/absolute/path/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl" run --machine --data-dir "/absolute/path/to/profile"
```

The source checkout also provides `./bin/spdctl`, which builds the development
runtime before launching it. The packaged executable includes its own JVM and
SQLite library; it does not require a separately installed Java runtime.

Machine mode starts the game window and a persistent stdin/stdout connection in
one process. Gameplay control uses JSON messages over that connection. A controller
does not need screenshots, window focus, OS keyboard input, or mouse simulation.
The GUI can use any registered language while public CLI system descriptions and
errors use official English. Text fields carry parallel `text_sources` entries with
resource keys and frozen arguments, or an explicit user/external origin. Keys aid
interpretation; actions still use current controls, locators and state versions.

CLI.2.0.0 uses protocol 2 and audit schema 5. Its default profile is
`~/Library/Application Support/Shattered Pixel Dungeon CLI v2/`. An existing older
audit schema is rejected before profile writes; it is never migrated or deleted.
Select a new directory to start a v2 session.

Send one complete UTF-8 JSON object per line and flush stdin. Read exactly one JSON
response line before sending the next request. Keep this same process and pipe open
throughout play. There is no background response stream and no network endpoint.
The normal application launcher starts an ordinary GUI instance; machine mode
does not attach to a separately running instance.

`--help` and `--version` print information and exit without starting a game or
creating a profile. Their output is not the machine-session NDJSON protocol.

## 2. Request identity and responses

| Request field | Meaning |
| --- | --- |
| `protocol_version` | Required integer `2` on every request, including `protocol.info`. |
| `id` | Required for every query and action. Generate a new UUID for each request. |
| `scope_id` | The current menu or game identity. Required except for initial `protocol.info`. |
| `op` | One of the seven protocol operations listed below. |
| `state_version` | Required for `action.execute`; copy the current observation version exactly. |
| `args` | Operation arguments, such as an action name, control, target, or page cursor. |

Examples below are templates. Replace `<SCOPE>`, `<STATE_VERSION>`, and other
angle-bracket placeholders with current response values. Generate new IDs when
repeating any example. Numeric example targets must also be replaced with cells
or options chosen from the current observation.

A request ID is consumed once registered, even if its parameters are invalid or
its state version is stale. Reusing an ID in the same scope returns
`DUPLICATE_REQUEST_ID`, with no repeat execution or replay of the old response.
Use a fresh ID and `request.get` to retrieve the original result.

Successful responses contain `protocol_version`, `id`, `scope_id`, `ok: true`,
`status`, `presentation`, and `result`. `presentation.status` is `complete` or
`partial`, with field diagnostics. A safe missing resource renders as a key; unsafe
or clipped text renders an English placeholder without hidden keys or arguments.
Rounded or truncated arguments expose only their displayed precision or fragments,
not hidden original values. Presentation is independent of action completion: do not replay a completed action
because text is partial. Callback, stable-boundary, snapshot and audit failures still
retain the execution-uncertainty protections below. Historical responses are returned
as recorded, without translating them again or requiring a fresh live observation.
Rejected or failed responses contain `ok: false` and `error.code`. Inspect `ok`
before using the result; a process that is still running does not imply success.

The outer response `scope_id` identifies the submitted request. After starting or
resuming a run, update the active scope from the live `result.scope_id`, which can
differ from that outer value. Do not replace current state with snapshots returned
by historical queries. `actions.list` at a stable boundary returns
`state_version` and `actions` without repeating the scope.

Menu scopes have the form `menu:<uuid>`; runs use `run:<uuid>`. A new game gets a
new scope. Continuing the same saved game keeps its scope and request-ID history.
Restarting the process invalidates all old state versions.

## 3. The seven protocol operations

| `op` | Purpose and arguments |
| --- | --- |
| `protocol.info` | Discover versions, capabilities, session identity, and active/menu scopes. No arguments. |
| `state.get` | Read the current player observation, UI, phase, actions, and state version. No arguments. |
| `actions.list` | Discover the actions available in the current context and their parameters. No arguments. |
| `action.execute` | Invoke one action. `args.action` is required; other arguments depend on that action. |
| `request.get` | Read an earlier logical request and its response. Requires `args.target_id`. |
| `events.read` | Read public events with optional `args.after` and `args.limit`. |
| `history.list` | Read public exchange metadata with optional `args.after` and `args.limit`. |

Start with a handshake:

```json
{"protocol_version":2,"id":"q-info","op":"protocol.info"}
```

Save `result.scope_id`, then request the current state:

```json
{"protocol_version":2,"id":"q-state","scope_id":"<SCOPE>","op":"state.get"}
```

For a stable state, inspect these fields under `result`:

- `scope_id`, `state_version`, and `phase`: the context for the next action.
- `observation.scene`: the active scene.
- `observation.ui.controls`: currently described controls and their visible text.
- `actions`: currently advertised semantic operations and parameter choices.
- In the dungeon, `observation.hero`, `map`, `inventory`, and `visible_entities`.
- `observation.ui.display`: the GUI language and fullscreen setting.

You can request just the action list separately:

```json
{"protocol_version":2,"id":"q-actions","scope_id":"<SCOPE>","op":"actions.list"}
```

Actions are discovered at runtime. Do not invent control IDs, object references,
item locators, or class-specific commands. Reacquire the state after each action,
window change, floor transition, or stale-version error.

## 4. Start or continue a game from the main menu

1. Request `state.get` and read the visible controls and advertised actions.
2. On a welcome screen, activate its displayed `Continue` control. If `ui.reveal`
   is advertised for a faded title or hero-selection screen, use it and observe
   again before choosing a control.
3. Activate the title screen's `Enter the Dungeon` control. If there are saved
   games, choose `New Game` to create a run, or select a displayed save slot and
   then its `Continue` button to resume that run.
4. For a new run, select an available hero such as `Warrior`. Inspect the current
   start control: it can be labelled `Start` or with the selected class name,
   depending on the layout. Activate that advertised control.
5. Handle any visible choices or confirmations using their new controls. Follow
   the run scope returned in the resulting live state.
6. Wait for a current dungeon observation. If a tutorial is active, follow its
   public prompts: find and collect the visible guidebook, then open and close the
   Journal when requested. Explore normally if the book is not yet visible.
   Inventory controls may remain unavailable until the tutorial is complete.

To click a menu or window button, copy its `control` from an advertised
`ui.activate` action and send:

```json
{"protocol_version":2,"id":"a-menu","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"ui.activate","control":"<CONTROL>","gesture":"click"}}
```

Selecting a hero and pressing Start are separate requests. Reusing the first
button's state version or control after it changes the scene is not valid.
Class unlocks, daily runs, custom seeds, challenges, and save deletion use the
actual displayed menu options and confirmation dialogs.

## 5. Observe and move through the dungeon

The public map contains cells the player can legitimately observe or remember.
Its `visibility` values include `visible`, `visited`, and `mapped`.
Unknown cells are omitted. Read each cell's knowledge and appearance rather than
treating omitted cells as safe floor. Visible entities identify publicly known
characters, items, containers, plants, and traps. Unknown item properties remain
unknown until normal gameplay reveals them.

Cells use integer map indices. For map width `w`, a coordinate `(x, y)` has index
`y * w + x`. Prefer copying `cell` directly from the current public observation.

Use `move.step` for a single directional input:

```json
{"protocol_version":2,"id":"a-step","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"move.step","direction":"north"}}
```

Directions are `north`, `northeast`, `east`, `southeast`, `south`, `southwest`,
`west`, and `northwest`. This follows the game's original directional behavior:
it can move, attack an adjacent creature, pick something up, open a door, or
trigger a stair interaction. It is not a teleport or a guaranteed one-turn move.

To interact with a map cell, including a creature, item, container, NPC, door, or
stairs, use `cell.select`. Here `123` is an illustrative target, not a fixed game
location:

```json
{"protocol_version":2,"id":"a-cell","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"cell.select","cell":123,"mode":"act"}}
```

`act` follows the current original cell-selection behavior. It can start travel
to the cell or answer an active targeting prompt. `examine` opens the available
player inspection; `context` opens the original context menu. Inspection is an
action because opening a window or marking information as read can change UI
state. It does not reveal information beyond the game's normal inspection.

Useful turn and rest actions, when advertised:

| Action | Meaning |
| --- | --- |
| `wait` | Use the original wait control. |
| `rest` | Start the original extended-rest behavior. It may remain in progress. |
| `search` | Perform the original search action, with its normal time cost. |

For example:

```json
{"protocol_version":2,"id":"a-search","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"search"}}
```

Read the new observation and public events after every action. The absence of a
visible threat is not a guarantee that the internal world contains no threat.

## 6. Use inventory, equipment, and targeting

`observation.inventory` lists public item descriptions, opaque `locator` values,
and whether each item is currently available. To open one item's normal action
menu, copy that item's current locator:

```json
{"protocol_version":2,"id":"a-item","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"inventory.open","locator":"<ITEM_LOCATOR>"}}
```

`inventory.open` requires a locator and opens the selected item's use menu. To
open the general inventory UI, activate its currently advertised inventory
control instead. Do not derive locators from class names or reuse an old backpack
index after an item moves or disappears.

After opening the item menu:

1. Observe the new state and read the item's displayed buttons.
2. Activate an offered action such as equip, drink, read, eat, throw, or a weapon
   ability through `ui.activate`. Use the actual returned control and label.
3. If a target cell is requested, select a current target with `cell.select` in
   `act` mode. To abandon that targeting step, use advertised `cell.cancel`.
4. If an item selector or option list appears, choose its current control or
   advertised `ui.choose` option. Observe again before any further choice.
5. Resolve any additional confirmation using its actual buttons, or go back
   through the original cancellation controls.

For example, cancelling a current cell-target prompt is its own action:

```json
{"protocol_version":2,"id":"a-target-cancel","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"cell.cancel"}}
```

A potion, scroll, reward, spell, or resurrection may require several requests.
`status: awaiting_input` means read the new prompt and continue. Cancellation
calls the original game callbacks; any costs already incurred remain incurred.

## 7. Other UI controls and every hero class

Game windows expose semantic controls for options, text, values, scrolling,
navigation, and targeting. The current action list supplies their references and
allowed parameters. The same mechanism handles class abilities, talents,
subclasses, armor abilities, companions, shops, alchemy, quests, notes, settings,
death, resurrection, and ending choices.

Every row below is an `args.action` for `action.execute`. Supply a fresh request
ID, the active scope, and the latest top-level `state_version` as in the previous
examples. Read the descriptor for the current control before choosing parameters.

| Action | Arguments in `args` besides `action` |
| --- | --- |
| `ui.activate` | `control`; optional `gesture`, default `click`. Use only advertised gestures: `click`, `right`, `middle`, or `long`. |
| `ui.choose` | `control`, integer `option` (zero-based); optional boolean `alternate`, default `false`. Used for described radial options. |
| `ui.select` | `control`. Select a described scroll-list entry through its original callback. |
| `ui.text` | `control`, `text`; optional boolean `submit`, default `false`. Replaces the text instead of appending. |
| `ui.value` | `control`, integer `value` within the described minimum and maximum. |
| `ui.scroll` | `control`; optional numeric `x` and `y`, which are absolute content offsets. An omitted axis keeps its current offset. |
| `ui.binding_slot` | `control`, integer `slot` equal to 1, 2, or 3. Select a keybinding slot. |
| `ui.binding_key` | `control`, integer `keycode` accepted by that binding input's keyboard/controller domain. |
| `ui.back` | No additional arguments. Invoke the current original Back/cancel behavior. |
| `ui.reveal` | No additional arguments. Reveal faded title or hero-selection controls when advertised. |
| `view.zoom` | Integer `zoom` within the advertised minimum and maximum. |
| `view.pan` | Optional numeric `x` and `y`, defaulting to zero. Shift the map view relatively in advertised `map_view` units. |
| `cell.select` | Integer `cell`; optional `mode`: `act` (default), `examine`, or `context`. |
| `cell.cancel` | No additional arguments. Cancel the active cell-target selector. |

For example, replace the text of an advertised text field:

```json
{"protocol_version":2,"id":"a-text","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"ui.text","control":"<TEXT_CONTROL>","text":"example note","submit":false}}
```

Observe the updated form, then activate its actual confirmation control. Respect
`max_length` and `multiline`; `submit: true` only applies to single-line fields.
For a described radial menu, choose its current zero-based option index:

```json
{"protocol_version":2,"id":"a-option","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"ui.choose","control":"<RADIAL_CONTROL>","option":0,"alternate":false}}
```

Binding actions configure the game's own key bindings; they do not send OS key
events. `ui.scroll` uses absolute offsets while `view.pan` uses relative offsets.
Scrolling, zooming, or panning can change which controls or cues are actually
visible, so read the resulting state again before the next choice.

For all six classes (Warrior, Mage, Rogue, Huntress, Duelist, and Cleric), open the
relevant visible hero, item, ability, or spell control and follow the resulting
actions. Available choices reflect the actual class, unlocks, resources, and
current situation. There is no separate command that grants a class, casts an
undiscovered spell, or skips its normal targeting and costs.

To return through the current window's original Back behavior:

```json
{"protocol_version":2,"id":"a-back","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"ui.back"}}
```

Back can itself produce another confirmation. Read the returned prompt instead
of assuming that one Back request always closes the entire interaction.

## 8. Long actions, cancellation, and state versions

The usual action response waits for a stable boundary, with a maximum wait of
30 seconds. An action that has not finished can return `status: in_progress`.
This is not a request to retry the operation. Keep its original scope and ID.
Query it using a new request ID:

```json
{"protocol_version":2,"id":"q-progress","scope_id":"<ORIGINAL_SCOPE>","op":"request.get","args":{"target_id":"<ORIGINAL_ACTION_ID>"}}
```

The query's own outer status describes the query, not completion of the original
action. Inspect `result.status` in the request record. `RECEIVED` and `EXECUTING`
are pending states. `result.response` contains the recorded original response or
the eventual terminal result. No second response is pushed for the old request.
After settlement, obtain a fresh live observation before the next decision.

While ordinary execution is resolving, state may have `state_version: null`,
`snapshot_status: last_stable`, and `last_stable_state`. That nested observation
is a previous stable snapshot, not a new point at which actions can be issued.
Do not act using its old version.

At an interruptible travel or rest boundary, the live state can instead have
`phase: continuous_activity`. When `action.cancel` is advertised, use its current
activity version and `target_id`:

```json
{"protocol_version":2,"id":"a-stop","scope_id":"<SCOPE>","op":"action.execute","state_version":"<ACTIVITY_STATE_VERSION>","args":{"action":"action.cancel","target_id":"<ORIGINAL_ACTION_ID>"}}
```

This is different from cancelling a cell selector or closing a window. If no
cancel action is advertised, continue querying the original request. Other new
game mutations are rejected as `BUSY` during pending execution.

`STALE_STATE` or `STALE_ACTIVITY` means obtain a fresh observation and reconsider
the choice. Both queries and rejected actions consume their IDs; use a new ID
for the next request. A new ID does not make an old state version valid.

## 9. Events, history, and failures

Read new public events:

```json
{"protocol_version":2,"id":"q-events","scope_id":"<SCOPE>","op":"events.read","args":{"after":0,"limit":50}}
```

Read request/exchange metadata:

```json
{"protocol_version":2,"id":"q-history","scope_id":"<SCOPE>","op":"history.list","args":{"after":0,"limit":50}}
```

Both operations return an array in `result`. `after` is an exclusive sequence
cursor, initially zero. On the next page, use the last returned `sequence`.
`limit` defaults to 50 and must be between 1 and 100. Events and history use
separate cursors. History queries are themselves audited, so new history entries
can appear while you page through it.

Use `request.get` for an entry's original response and final logical result.
Known historical scopes remain queryable even when their run is no longer active.
Historical snapshots must not replace the current scope or state version.

| Error code | What to do |
| --- | --- |
| `DUPLICATE_REQUEST_ID` | Query the old ID with a fresh `request.get` ID; do not resend it. |
| `STALE_STATE`, `STALE_ACTIVITY` | Observe again and make a new decision. |
| `SCOPE_MISMATCH`, `UNKNOWN_SCOPE` | Discover the active scope with `protocol.info`; retain old scopes only for history. |
| `BUSY` | Query the pending request or use an advertised activity cancellation. |
| `ACTION_UNAVAILABLE`, `INVALID_ARGUMENT` | Check current actions, parameter types, and prompt context. |
| `PROTOCOL_VERSION_REQUIRED`, `INVALID_PROTOCOL_VERSION`, `UNSUPPORTED_PROTOCOL` | Send the explicit integer `protocol_version: 2`; older protocol envelopes are rejected before game observation or dispatch. |
| `EXECUTION_UNKNOWN`, `EXECUTION_UNCERTAIN` | Do not assume success or replay the action. Inspect its recorded result and reobserve after recovery. |
| `AUDIT_UNAVAILABLE` | The audit store failed; the session stops accepting game operations. |

Protocol and public history expose only player-available information. Raw
exceptions, hidden world data, and diagnostic snapshots are not a public game
control interface. A failed or unsupported operation is not permission to infer
hidden information from internal files.

## 10. Save, quit, restart, and finish a run

At a ready dungeon boundary, save with `game.save`:

```json
{"protocol_version":2,"id":"a-save","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"game.save"}}
```

Use the action response's `result.persistence.saves_during_request` to inspect
actual save receipts. An empty list does not confirm a save. State queries also
report the latest known `last_save` when available. Completing an in-memory
action and saving its result to game files are separate facts.

To exit, first resolve open prompts through their original controls until
`app.quit` is available, then send:

```json
{"protocol_version":2,"id":"a-quit","scope_id":"<SCOPE>","op":"action.execute","state_version":"<STATE_VERSION>","args":{"action":"app.quit"}}
```

Read its response and wait for process exit. Closing stdin requests lifecycle
shutdown, but does not automatically accept a reward, destructive confirmation,
or other unresolved choice. Arbitrary open-window state is not guaranteed to
survive a restart.

Restart using the same `--data-dir`, perform a new handshake with a new ID, and
continue the displayed saved run. The run scope and duplicate-ID history remain;
the old process's state versions do not. Do not replay old successful actions to
make a restored save catch up with audit history.

For death, revival, the Amulet, ascent, and the surface ending, follow the actual
visible choices. Read the live outcome and ending UI; the CLI does not provide a
command to force a win.

## 11. Profile and audit locations

The macOS default profile is:

```text
~/Library/Application Support/Shattered Pixel Dungeon CLI/
```

With `--data-dir`, all profile data goes under the absolute directory you supply:

```text
PROFILE/
  audit/public.sqlite3
  audit/internal.sqlite3
  audit/emergency/
  ... settings, saves, and other game profile files ...
```

`public.sqlite3` records public requests, responses, observations, and events.
`internal.sqlite3` stores complete internal snapshots and diagnostic details.
Every framed machine request is audited when the store is writable, including
queries, duplicate requests, and malformed inputs. Responses are committed before
stdout output. A database failure or abrupt termination can leave explicit gaps;
an emergency `AUDIT_UNAVAILABLE` response cannot itself be guaranteed to persist.

The two databases persist across sessions of the same profile. Only one game
process may use a profile at a time. Use distinct directories for separate runs
of the application or disposable tests, and handle the audit files as a pair.

## 12. Minimal programmatic connection example

This optional Python example demonstrates the pipe protocol, discovers current
actions, and then closes stdin. Replace the launcher and profile paths first.
Python is only this example's client language; it is not required by the game.

```python
import json
import subprocess
import uuid

launcher = "/absolute/path/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl"
profile = "/absolute/path/to/profile"
process = subprocess.Popen(
    [launcher, "run", "--machine", "--data-dir", profile],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE,
    text=True, encoding="utf-8", bufsize=1,
)

def request(op, *, scope=None, version=None, args=None):
    message = {"protocol_version":2,"id": str(uuid.uuid4()), "op": op}
    if scope is not None:
        message["scope_id"] = scope
    if version is not None:
        message["state_version"] = version
    if args is not None:
        message["args"] = args
    process.stdin.write(json.dumps(message) + "\n")
    process.stdin.flush()
    line = process.stdout.readline()
    if not line:
        raise RuntimeError("Machine session ended before its response")
    response = json.loads(line)
    if response.get("id") != message["id"]:
        raise RuntimeError("Unexpected response ID")
    return response

try:
    hello = request("protocol.info")
    if not hello["ok"]:
        raise RuntimeError(hello)
    scope = hello["result"]["scope_id"]
    state = request("state.get", scope=scope)
    if not state["ok"]:
        raise RuntimeError(state)
    print(json.dumps(state["result"], indent=2))
    # Keep the pipe open here for a controller loop:
    # observe -> choose a CURRENT action -> action.execute -> inspect response.
    # Handle in_progress and awaiting_input as described above.
finally:
    process.stdin.close()
    process.wait(timeout=40)
```
