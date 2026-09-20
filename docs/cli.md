# spdctl control interface

Current release: **CLI.7.0.0 / protocol 7 / audit schema 10**, base game **3.3.8**.
The authoritative interface and complete request examples are in [the English CLI help](cli-help.md), which is bundled verbatim as `spdctl --help`. See [CLI 7 implementation and validation](cli7-implementation.md) and the repository [agent working agreements](../AGENTS.md).
Protocol 7 introduces same-frame action sharing and record templates without removing public information. The [complete-frame study](cli-complete-frame-token-study-20260920.md), [depth-9 token study](cli-token-study-d9-20260920.md), [CLI.6.1.0 readability audit](cli-issues/2026-09-20-cli-6.1.0-readability-lossless.md), and CLI 6 validation reports remain historical evidence, not current protocol instructions.

## Start an independent v7 profile

```sh
./bin/spdctl control --machine
"desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl" control --machine
```

The default profile is `~/Library/Application Support/Shattered Pixel Dungeon CLI v7/`. Earlier profiles remain untouched; schema 1–9 is rejected before writes. There is no automatic migration or old-wire fallback. Only absent/empty profiles receive the existing windowed Chinese/new-profile defaults.

The packaged controller owns one native-recorded game child using the bundled JVM. It emits initial info and accepts short intents; actions bind an explicit already displayed revision. It supplies `v:7`, a fresh request ID and that observation's scope. Direct `run --machine` remains available and requires these fields explicitly. Use only advertised `ops/acts`. Short durable identities are profile-local; separate directories can reuse numbers. Do not inspect saves or private audit to choose actions.

Generated request counters are decimal: `t1.9` is followed by `t1.10`, not `t1.a`.
The server-provided prefix remains opaque; failed allocations consume numbers,
and original IDs in historical records are never renamed.

Normal terminal/PTY launch works without redirecting stdout to a file. Child stderr has a dedicated pipe and a separate byte forwarder, so the native relay cannot change the controller terminal's blocking flags through an inherited descriptor. When consuming NDJSON, keep stdout and stderr separate; deliberately merging them on a terminal may interleave diagnostic text with JSON. The child's raw SEND/RECV/ERROR records remain separate and unchanged. Diagnostic forwarding is best-effort, with a bounded final drain after child exit.

Controller startup and stream failures report `CONTROLLER_CHILD_START_FAILED`, `CONTROLLER_OUTPUT_FAILED`, `CONTROLLER_INPUT_FAILED`, or `CONTROLLER_FAILED` on stderr with an exception class only. A diagnostic-pipe read failure reports `CONTROLLER_DIAGNOSTIC_READ_FAILED`. These messages are local transport evidence, not a game action's result; retain the original request identity and child trace. The controller does not create profile logs for these failures.

## Decode the current observation

Every view is complete and self-contained. Expand the current observation's `act_templates` and `inv_templates`, restore applicable activity/cancel bindings before copying referenced operations, then expand `ui.node_templates` and resolve ordered node `ops` and item labels. Apply remaining entity/effect dictionaries and scoped defaults independently. Preserve unknown gaps, knowledge nulls, descriptions, bars, counts, estimates, actions and cues. UI `loc/display` comes from the same rendered capture. Full/src retain more explicit fields or sources but use the same structural encoding. Protected records remain inline with their real diagnostic paths. Frozen before/after snapshots use their own tables and bindings; raw/reply stay immutable.

Since CLI.6.1.0, both views retain all captured item descriptions, talent entries including zero points, UI nodes and their identities/order/parents/text, and every operation constraint. Only reversible encoding is permitted for public observation content. `src` remains the explicit full-provenance interface. Earlier token-saving measurements included reductions now removed and do not describe the current lossless response. See the [reference client guidance](cli-playthrough-client.md) for decoding and validating intents without guessing array positions or suppressing errors.

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

Use current v7 tests and the rebuilt executable. Historical reports describe their own versions; their test counts are not evidence for a later patch. Test profiles and generated JSON live in ignored build directories. Current package validation is in the [2026-09-21 clean rebuild](cli-rebuild-7.0.0-20260921.md); protocol implementation and original corpus measurements are in [CLI 7 implementation](cli7-implementation.md). There are no cross-frame dictionaries, map/inventory/log deltas, short scalar encodings or GUI semantic pruning in this release.
