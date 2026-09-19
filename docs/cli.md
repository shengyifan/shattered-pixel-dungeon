# spdctl control interface

Current release: **CLI.6.0.0 / protocol 6 / audit schema 9**, base game **3.3.8**.
The authoritative interface and complete request examples are in [the English CLI help](cli-help.md), which is bundled verbatim as `spdctl --help`. See [CLI 6 implementation and validation](cli6-implementation.md) and the repository [agent working agreements](../AGENTS.md).

## Start an independent v6 profile

```sh
./bin/spdctl control --machine
"desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl" control --machine
```

The default profile is `~/Library/Application Support/Shattered Pixel Dungeon CLI v6/`. Earlier profiles remain untouched; schema 1–8 is rejected before writes. There is no automatic migration or old-wire fallback. Only absent/empty profiles receive the existing windowed Chinese/new-profile defaults.

The packaged controller owns one native-recorded game child using the bundled JVM. It emits initial info and accepts short intents; actions bind an explicit already displayed revision. It supplies `v:6`, a fresh request ID and that observation's scope. Direct `run --machine` remains available and requires these fields explicitly. Use only advertised `ops/acts`. Short durable identities are profile-local; separate directories can reuse numbers. Do not inspect saves or private audit to choose actions.

## Decode the current observation

Default `play` is complete and self-contained. Expand its own `ui.node_shapes`, `ui.op_defs`, inherited item labels, `entity_defs`, `map.effect_defs`, uniform row visibility and scoped bindings/defaults as specified in help. Preserve unknown gaps, distinct terrain descriptors, knowledge nulls, danger descriptions, health bars, rendered counts/estimates, available actions and visual cues. UI item `loc/display` comes from the same rendered capture, not hidden item properties. Full/src views retain expanded diagnostic detail.

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

Use current v6 tests and the rebuilt executable. Historical CLI 2–5 reports describe their own versions; their test counts are not v6 evidence. Test profiles and generated JSON live in ignored build directories. Actual token measurements, package checks and any remaining real-window acceptance limits are recorded in the v6 implementation report. Map/inventory/log deltas are evaluated separately and are not part of the default protocol.
