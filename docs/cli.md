# spdctl control interface

Current contract: **CLI.8.0.0 / protocol 8 / audit schema 11**, base game **3.3.8**.
The authoritative interface and complete request examples are in [the English CLI help](cli-help.md), which is bundled verbatim as `spdctl --help`. See [CLI8 implementation](cli8-implementation.md) and the repository [agent working agreements](../AGENTS.md).
Protocol 8 keeps complete same-frame action sharing and record templates and changes
combat/UI reporting to [semantic public facts](cli-combat-visuals.md). The
[CLI.7.0.1 rendered contract](cli7-rendered-combat-visuals.md), [CLI 7
implementation](cli7-implementation.md), earlier token studies and validation
reports remain historical evidence, not current protocol instructions.

## Start an independent v8 profile

```sh
./bin/spdctl control --machine
"desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl" control --machine
```

The default profile is `~/Library/Application Support/Shattered Pixel Dungeon CLI v8/`. Earlier profiles remain untouched. A nonempty profile without a valid schema-11 audit pair, including an older schema, is rejected before writes. There is no automatic migration or old-wire fallback. Only absent/empty profiles receive the existing windowed Chinese/new-profile defaults.

The packaged controller owns one native-recorded game child using the bundled JVM. It emits initial info and accepts short intents; actions bind an explicit already displayed revision. It supplies `v:8`, a fresh request ID and that observation's scope. Direct `run --machine` remains available and requires these fields explicitly. Use only advertised `ops/acts`. Short durable identities are profile-local; separate directories can reuse numbers. Do not inspect saves or private audit to choose actions.

Generated request counters are decimal: `t1.9` is followed by `t1.10`, not `t1.a`.
The server-provided prefix remains opaque; failed allocations consume numbers,
and original IDs in historical records are never renamed.

Normal terminal/PTY launch works without redirecting stdout to a file. Child stderr has a dedicated pipe and a separate byte forwarder, so the native relay cannot change the controller terminal's blocking flags through an inherited descriptor. When consuming NDJSON, keep stdout and stderr separate; deliberately merging them on a terminal may interleave diagnostic text with JSON. The child's raw SEND/RECV/ERROR records remain separate and unchanged. Diagnostic forwarding is best-effort, with a bounded final drain after child exit.

Controller startup and stream failures report `CONTROLLER_CHILD_START_FAILED`, `CONTROLLER_OUTPUT_FAILED`, `CONTROLLER_INPUT_FAILED`, or `CONTROLLER_FAILED` on stderr with an exception class only. A diagnostic-pipe read failure reports `CONTROLLER_DIAGNOSTIC_READ_FAILED`. These messages are local transport evidence, not a game action's result; retain the original request identity and child trace. The controller does not create profile logs for these failures.

## Decode the current observation

Every view is complete and self-contained. Expand the current observation's `act_templates` and `inv_templates`, restore applicable activity/cancel bindings before copying referenced operations, then expand `ui.node_templates` and resolve ordered node `ops` and item labels. Apply remaining entity/effect dictionaries and scoped defaults independently. Preserve unknown gaps, knowledge nulls, descriptions, semantic health estimates, shown counts/progress/flags, actions and world cues. State `ui.nodes[].subject` names a hero, item, hero buff, entity or entity buff by an explicit same-frame reference; never infer its owner from a label. Standalone `actions` omits those state tables and carries direct target facts in `subject_data`, or null with local `unresolved_subject` partial diagnostics. `ui.feedback` and `hero.turn_progress` belong to the current state observation. Full/src retain more explicit defaults or source provenance but use the same structural encoding; they do not add ordinary rendering internals. Protected records remain inline with their real diagnostic paths. Frozen before/after snapshots use their own tables and bindings; raw/reply stay immutable.

Both views retain public item descriptions, talent entries including zero points, semantic UI facts and every operation constraint. Redundant passive text can move into the owning subject's `shown`, health or progress fields, or into `ui.feedback`; retained nodes keep their identities, order and parent relationships. Only reversible encoding is permitted for public observation content. `src` remains the explicit public source-provenance interface. CLI8 observations and new events omit atlas/frame/transform/RGB, generic particle histograms and screen effects; reviewed `GameplayBurst` kinds can carry actual visible contributor counts and `sacrificial_flames` remains a special density cue; quantity-only history for counted kinds is explicitly sampled at 250 ms. World cues use public map/FOV scope independent of camera and UI occlusion, without hidden AI/timers. See the [reference client guidance](cli-playthrough-client.md) for decoding and validating intents without guessing array positions or suppressing errors.

Synchronous success already supplies the current observation. The controller exposes interruptible `in_progress` before local `settle`; direct clients poll small `req` receipts and obtain one fresh state after successful settlement (first discover scope for finite resolving/cancelling). Preserve original outcomes/save receipts separately. Never replay completed, pending or uncertain actions. After successful quit await exit without another query.

## Two passive Terminal windows

```sh
spdctl trace open --session /absolute/session
spdctl trace open --session /absolute/session --stream send
spdctl trace open --session /absolute/session --stream recv
spdctl trace view --session /absolute/session --stream all --color auto
```

Run and `trace open` default to two independent windows, SEND and RECV + ERROR. Direct `trace view` defaults to a combined display. Only numbered SEND/RECV headings are bold bright cyan; both directions use the same color. JSON/body and red ERROR text use normal weight. Terminal's background is unchanged. `--no-terminal` disables automatic opening only. Closing/reopening either view does not affect the other, the game, or byte-exact recording. In controller mode only the child creates the trace/viewer pair.

One trace directory retains `send.raw`, `recv.raw`, `stderr.raw`, `events.tsv`, `open-send.command`, and `open-recv.command`. Trace format remains 1. Color/control escaping belongs only to presentation; no message body is truncated or reformatted. Viewer launch failures report a channel and reopen command without retrying uncertain opens or interrupting protocol forwarding.

## Validation boundary

Use current v8 tests and the rebuilt executable. Historical reports describe their own versions; their test counts are not evidence for this change. Test profiles and generated JSON belong in ignored build directories. The integrated Java and static source-review results, with native/package readback boundaries, are in [CLI8 implementation](cli8-implementation.md). Protocol 8 has no cross-frame dictionaries or map/inventory/log deltas; each response remains independently complete. A static source inventory and isolated fixtures do not prove a full game playthrough.
