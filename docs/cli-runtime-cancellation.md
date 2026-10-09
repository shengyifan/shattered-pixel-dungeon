# CLI.9.0.1 continuous actions and cancellation

Protocol 9 exposes native extended rest and path travel as interruptible activity.
A directional `move` remains one original directional input; a remote `cell` may
start the game's continuous path. Neither arrival nor a particular number of
turns is guaranteed. The CLI starts the original action once and never advances
it by replaying requests or synthesizing extra waits.

## Observe before cancelling

The first response is `st:"in_progress"`. At a natural interruptible boundary,
`data.phase` is `continuous_activity` and the current actions advertise `cancel`
with the activity revision and the original action's `rid`. Read that actual
binding. It is valid for cancellation of that activity, not for arbitrary inputs
based on an old HP/map snapshot. Other game actions remain `BUSY` while the
activity is pending. Stopped activities invalidate their old cancellation binding.

With `spdctl control --machine`, these are illustrative **controller intents**:

```json
{"op":"settle","rid":"t2.7","timeout_ms":5000}
{"op":"cancel","rev":"a3","rid":"t2.7"}
```

The displayed handles are examples, not reusable values. The controller supplies
a fresh request ID, protocol version and the scope of the displayed revision.
`settle` is local receipt recovery; gameplay `wait` advances the game and cannot
replace it. Inspect `pending_reason` before sending another operation:

- `action_pending`: a complete nonterminal receipt is known, with no outstanding
  internal response; the current advertised cancellation may be used.
- `budget_exhausted`: no response is outstanding, but a settlement stage still
  needs to run. Continue settling the original action.
- `response_pending`: receive the exact outstanding `pending_request` through
  `settle` before any query or cancellation. Never resend it.

A soft settle budget is separate from the immutable response deadline. A resumed
nonterminal receipt yields control; it does not begin another hidden poll. Actual
transport failures remain errors. Preserve the original outcome, error and raw
bytes rather than treating a successful later query as proof of action success.

A direct client keeps its one connection and sends a fresh protocol-9 request:

```json
{"v":8,"id":"<FRESH_ID>","s":"<CURRENT_SCOPE>","rev":"<ACTIVITY_REV>","op":"cancel","rid":"<ORIGINAL_ID>"}
```

Use the handshake's opaque request prefix plus a newly allocated decimal counter.
Rejected IDs are consumed too. Cancellation is distinct from `untarget` (closing
a cell-target prompt) and `back` (the current native dialog/back behavior).

## Native execution and receipt ordering

At a natural boundary, `prepareCancellation` validates the target and activity,
captures the before observation and holds a preparation lease. It uses the actual
Hero/Actor waiting boundary or sprite monitor; it does not interrupt the actor
thread or skip a native callback. The current movement step can finish normally.

The session first persists the cancellation request's executing intent. Only after
that succeeds does `cancelPrepared` invoke native `GameScene.cancel()`. On failure,
`abortCancellation` releases the lease without invoking cancellation. At the
normal ready boundary, the original request becomes `INTERRUPTED`, while the
cancellation request completes separately. Request IDs, original response bytes,
before/after snapshots and save receipts keep their own identities.

There is no second unsolicited response on the original action's pipe request.
The controller finishes both pending lifecycles through local `settle`; manually
querying `req`/`state` does not clear its pending bookkeeping. Direct clients query
the original receipt with `op:"req"` and a fresh ID, preserving its original
scope and `rid`. After terminal success, obtain one live observation. For a finite
operation initially marked `resolving` or `cancelling`, discover scope with `info`
before that state query. Ordinary continuous activity needs no extra discovery.
Use the new observation's own `s/rev`, never historical reply bindings.

Cancellation does not independently force a save. A successful state query also
does not establish that the original action saved. After successful quit, resolve
its exact terminal receipt and wait for exit without requesting state. Full
error/receipt rules are in the [English help](cli-help.md).

## Verification

`ContinuousCancellationTest`, `ActorHandoffTest` and `InterlevelLoaderTest` cover
controlled execution, leases and callback boundaries. `StableControllerTest` and
`StableControllerDeadlineTest` cover controller settlement and response deadlines.
These tests are distinct from native rendered integration and real gameplay.
Isolated native cancellation fixtures can be run explicitly:

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/cancellation_smoke.py --mode rest
python3 desktop-control/src/test/python/cancellation_smoke.py --mode travel
python3 desktop-control/src/test/python/cancellation_smoke.py --mode travel-audit-fail
```

The failure mode injects a persistence failure only into its labeled test profile;
never use a personal profile. Fixtures are marked `counts_as_win=false`. Current
source organization and gate boundaries are in the [architecture](cli-architecture.md);
executed results belong in the [validation report](cli-8.0.2-cleanup-validation.md).
