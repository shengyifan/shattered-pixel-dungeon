# Public CLI clients and per-frame decoding

## Reusable CLI.8.0.2 reference helper

`desktop-control/client/spdctl_client.py` is an optional, dependency-free Python
module for consumers of the packaged controller. It does not launch the game,
read profiles, perform I/O, choose actions, retry requests or replace revisions.
The packaged `spdctl control --machine` remains the normal game-control entry point
and does not require Python.

- `decode_wire_response(value)` validates one complete protocol-8 child response.
- `decode_client_response(value)` also understands controller response/error,
  settle and exit wrappers. Inspect `problems` before assuming success. Its
  `current_frame` is only a current observation, never a receipt, historical reply
  or late response; original evidence remains separately accessible and in `raw`.
- `result.require_current_frame()` returns a decoded current observation or raises
  `ClientResponseError`. Original errors take precedence; an otherwise successful
  result without an observation reports `NO_CURRENT_FRAME`. `require_success()`
  alone does not promise an observation, map, actionable revision or saved action.
- `validate_intent(current_frame, intent)` checks an explicit proposed controller
  intent against that frame's exact revision, scope and advertised operations. It
  does not modify or send the intent. This rejects a `select` for a click-only
  button and a `move` while only a modal choice is available.
- `map_cell(current_frame, cell)` returns the decoded known cell or `None` for an
  unknown gap. Coordinates use that frame's `w`, rows and terrain dictionary.
  It rejects absent/invalid frames, error replies and receipts with `IntentError`;
  a valid observation without a map still returns `None`. It does not decide
  whether a cell is passable or promise successful travel.

Read transport through LF and parse the complete JSON object before using these
functions. Preserve the transport bytes independently of any display limit. A
packed node starts with a template index, not a control ID; the helper first expands
the current observation's `act_templates` and `inv_templates`, restores applicable
activity/cancel bindings before copying referenced operations, then expands
`ui.node_templates` and resolves node operation references from `acts` and item
labels from same-frame inventory or floor entities. It expands entity/effect
dictionaries and scoped defaults before validating semantic `subject` references
against complete current hero/item/entity/buff facts. Ambiguous or missing subjects
are decoding errors. All views use this representation. Null, false, zero,
unknown fields, protected metadata and literal labels remain distinct.

CLI.8.0.2 validates static parameters for every action, query and local `settle`,
including required/extra fields and JSON integer versus boolean/float distinctions.
An omitted settle rid resumes the owner of an outstanding query first, otherwise
the latest pending action; an explicit invalid rid is never treated as omitted.
Node/action constraints additionally cover choices,
slider/zoom ranges, binding slots and text submission. Text length is measured in
UTF-16 code units, matching the game. A proposed operation must satisfy one complete
advertised descriptor; constraints from different descriptors are not combined.
Scroll retains native clamping and omitted-axis behavior. Unknown map cells remain
legal targets where the native operation permits them. Actual key acceptance,
collision, damage, costs and completion still belong to the game response.

`IntentError` remains a `ValueError` and additionally exposes `code`, `operation`,
`fields`, `expected` and an independent `raw_intent` copy. Diagnostics identify
invalid direction values, all unsupported fields, missing cancellation IDs and
binding mismatches. Directions are exactly `N NE E SE S SW W NW`; the helper never
corrects spelling, removes fields, fills IDs or rebinds revisions automatically.

New settle pending wrappers expose `pending_reason`, `stage` and, only when a
query response remains outstanding, `pending_request`. The decoder validates
these explicit fields and preserves their evidence without maintaining a
connection or cross-response state machine. Their absence in older wrappers
means unknown, not idle. An ordinary budget yield adds no `problems`, but a
pending result still does not satisfy `require_success()`. Queries and settle
remain syntactically valid without a current observation. A successful state
query never clears controller pending-action bookkeeping.

`inv[].shown`, `buffs[].shown`, `health_estimate`, `hero.turn_progress`, explicit
`ui.nodes[].subject` references and `ui.feedback` are public semantic facts, not
inferred operations. The current `data.cues.cues` is scoped to public world/FOV
evidence, independent of the camera and UI occlusion; semantic event history is
separate from the current frame. Invalid env cell conversion, duplicate decimal
cell identities and invalid saved references raise evidence-preserving DecodeError;
they cannot silently overwrite data. The helper must not infer subjects from names
or convert shown estimates into hidden exact values.

A standalone `actions` reply omits hero/inventory/entity tables. Its bound UI
nodes carry direct public target facts in `subject_data` and omit `subject`;
`subject_data:null` with local `unresolved_subject` partial diagnostics means
the owner was not uniquely established. A decoder must keep the node's label,
locator and `ops`, and must not resolve a subject against a previous frame.
The helper validates `subject`; it leaves the explicit reference in the decoded
node rather than replacing it with another copy of its owner. `subject_data` is
already inline. A textless node can carry its useful label in a resolved `ops`
entry, so selecting controls by `node.text` alone is incomplete. Preserve the
operation's constraints and use only its advertised gesture.

Displayed health samples retain their original integers. Foreground ceil and
background round can create a one-segment overhang: `21/4/22` and a full bar such
as `21/22/22` are valid. The exact constraint is positive `total` and
`0 <= filled <= with_shield <= total + 1`, with `basis:"displayed"`. Larger
overhangs, booleans, floats and reversed ordering remain decoding errors. This
does not infer exact HP, clamp public values or relax other semantic validation.

If `decode_wire_response()` or `decode_client_response()` fails, `DecodeError.raw`
retains a deep copy of the parsed failing wire JSON, not the original transport
bytes. `identity` contains its valid `id/s`, with `response_id/scope` convenience
fields. This is the failed reply's identity: a bad settle observation may identify
the state query, not the original action. Keep the original action's `rid`, outcome
and request from the wrapper separately. `stage` is the failing stage or None;
`contexts` holds `{controller,stage,raw}` wrappers, and `context` returns the
outermost recorded one. Preserve actual transport bytes independently. This
evidence never authorizes retry.

`expand_structures()` is a structure-only research/test helper. Its default does
not restore activity bindings, item/UI defaults or map cells; it is not a substitute
for `decode_wire_response()` when choosing actions and does not attach envelope
identity to exceptions. Its optional `bindings=True` is for explicit test adapters,
not a promise of complete semantic decoding.

```python
# Run from the repository root; no game operation occurs in this example.
import json
import sys
sys.path.insert(0, "desktop-control/client")
from spdctl_client import decode_client_response, validate_intent

result = decode_client_response(json.loads(complete_response_line))
# Retain/display the original outcome and errors, not only selected data fields.
print(json.dumps(result.raw, ensure_ascii=False))
if result.problems:
    for problem in result.problems:
        print(problem.stage, problem.code, file=sys.stderr)
else:
    frame = result.current_frame
    if frame is not None:
        checked = validate_intent(frame, explicitly_chosen_intent)
        # A caller may now send checked.intent once on its existing connection.
```

An initial `in_progress` must be presented before explicit settling. A successful
state query does not clear controller pending bookkeeping. After any definite
game rejection, `settle` establishes its original outcome; `ACTION_REJECTED` is
that confirmation, not a second gameplay error. Reobserve and make a fresh
decision only after the original outcome is known. Never repeat an uncertain,
pending or completed action. A blocked wand target closes the targeting prompt,
so sending another cell without reopening ZAP can become ordinary movement.

`acts` retains its full original order, including duplicate occurrences. Node `ops`
references the same capabilities by index, or retains protected inline operations.
Do not execute both representations. Invalid references or control mismatches are
decoding errors, not a reason to infer another operation or retry an action.
Frozen `before/after` snapshots have independent tables and never become a live
frame. `raw/reply` and source ASTs remain untouched. Full/src keep their field/source
semantics but do not promise uncompressed records or ordinary renderer detail on
the wire.

Complete known-map rows and public world cues are independent of the viewport;
modal UI still limits available actions. Decision-relevant warning changes can
invalidate revisions, while decorative refreshes and completed-action feedback do
not. For example, `electricity_flow` reports particle motion; `map.env` identifies
the stable electrical hazard. Its animation alone is not a new decision binding.
Use the response's actual `rev` and reobserve after a definite stale rejection;
never infer a replacement revision from cue or event differences.

## Development gameplay driver

The packaged `spdctl control --machine` is the normal model-control entrypoint.
It owns one child, requires the already displayed revision and exposes explicit
settling; it does not contain a combat or exploration policy.

The optional [development driver](../desktop-control/src/test/python/autoplay.py)
uses protocol 8 and has bounded exploration and persistent JSON-intent modes.
It needs a new decision for unsupported enemies, choices or boss situations;
its limited policy is not an autonomous-clear guarantee. It uses public responses,
not game saves or private audit, to choose actions. The driver's current-version
adapters live in `protocol8.py`; legacy readers are isolated research utilities.

The shared `ActionResult` separates original outcome/receipt from the current
observation. Synchronous success uses its own reply; pending actions use small
receipts and one fresh observation after terminal success. Historical replies do
not overwrite live bindings. Successful asynchronous quit must deliver its original
terminal receipt before waiting for exit, with no subsequent state query.

The driver can write `current-state-public.json` as a frozen public debugging
observation. A saved file or older checkpoint does not certify the current game
revision or a native save; save confirmation still comes from public receipts.
The following runs offline policy checks without starting a game:

```sh
python3 desktop-control/src/test/python/autoplay.py --self-test
```

Source/test organization and explicit offline token-study entrypoints are in the
[current architecture](cli-architecture.md). Executed checks belong in the
[current validation report](cli-8.0.2-cleanup-validation.md), separately from
actual playthrough evidence in the [project memory](../memory/README.md).
Display each complete response using that same parsed object; do not filter by
fixed English labels, crop the map without marking it, remove descriptions, omit
public cues or combine an old body with a new revision.
