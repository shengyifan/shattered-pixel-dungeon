# spdctl control interface

Current release: **CLI.5.0.0 / protocol 5 / audit schema 8**, base game **3.3.8**.
The authoritative interface and complete request examples are in [the English CLI help](cli-help.md), which is bundled verbatim as `spdctl --help`. See [CLI 5 implementation and validation](cli5-implementation.md) and the repository [agent working agreements](../AGENTS.md).

## Start an independent v5 profile

```sh
./bin/spdctl run --machine
"desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl" run --machine
```

The default profile is `~/Library/Application Support/Shattered Pixel Dungeon CLI v5/`. Earlier profiles remain untouched; schema 1–7 is rejected before writes. There is no automatic migration or old-wire fallback. Only absent/empty profiles receive the existing windowed Chinese/new-profile defaults.

The game and controller share one JVM and one serial NDJSON connection. Every request requires `v:5` and a fresh id; actions additionally bind the actual current scope and revision. Use only currently advertised `ops/acts`. Do not inspect saves or private audit to choose actions.

## Decode the current observation

Default `play` is complete and self-contained. Expand its own `entity_defs`, `map.effect_defs`, uniform row visibility and scoped defaults as specified in help. Preserve unknown gaps, distinct terrain descriptors, knowledge nulls, danger descriptions, health bars, rendered counts/estimates, available actions and visual cues. UI item `loc/display` comes from the same rendered capture, not hidden item properties. Full/src views retain expanded diagnostic detail.

Synchronous success already supplies the current observation. Expose an interruptible `in_progress` response before polling its small `req` receipt; after successful terminal receipt obtain one fresh state (and first discover scope for finite resolving/cancelling). Preserve original outcomes/save receipts separately. Never replay completed, pending or uncertain actions. After successful quit await exit without another query.

## Two passive Terminal windows

```sh
spdctl trace open --session /absolute/session
spdctl trace open --session /absolute/session --stream send
spdctl trace open --session /absolute/session --stream recv
spdctl trace view --session /absolute/session --stream all --color auto
```

Run and `trace open` default to two independent windows, SEND and RECV + ERROR. Direct `trace view` defaults to a combined display. Both dedicated windows use bold bright syntax highlighting while preserving Terminal's background. `--no-terminal` disables automatic opening only. Closing/reopening either view does not affect the other, the game, or byte-exact recording.

One trace directory retains `send.raw`, `recv.raw`, `stderr.raw`, `events.tsv`, `open-send.command`, and `open-recv.command`. Trace format remains 1. Color/control escaping belongs only to presentation; no message body is truncated or reformatted. Viewer launch failures report a channel and reopen command without retrying uncertain opens or interrupting protocol forwarding.

## Validation boundary

Use current v5 tests and the rebuilt executable. Historical CLI 2/3/4 reports describe their own versions; their test counts are not v5 evidence. Test profiles and generated JSON live in ignored build directories. Actual token measurements, package checks and any remaining real-window acceptance limits are recorded in the v5 implementation report.
