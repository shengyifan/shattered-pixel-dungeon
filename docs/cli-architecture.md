# CLI.9.0.5 architecture and verification

The maintained contract is **CLI.9.0.5 / protocol 9 / audit schema 12**, with game
**4.0.2** and trace format **1**. The [English help](cli-help.md) defines public
commands and schemas. [Semantic observations](cli-combat-visuals.md),
[client guidance](cli-playthrough-client.md) and [cancellation](cli-runtime-cancellation.md)
explain the relevant boundaries. This document maps those contracts to source and
verification entrypoints; test counts and package identity belong in the
[current validation report](cli-9.0.5-revision-validation.md).

New desktop profiles persist `unlock_start_options=true` and skip introduction/tutorial.
The GUI and CLI share startup initialization before consuming display settings;
CLI adds its own language/window defaults after validating profile compatibility.
Availability gates use that setting without awarding earned achievements or
changing in-run progression. Existing profiles are never migrated to the new default.

## Source responsibilities

| Area | Source and responsibility |
| --- | --- |
| Wire framing | [control-protocol](../control-protocol/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/protocol) parses serial NDJSON, validates version and request arguments, and defines compact wire names. |
| Bundled controller | [StableController](../desktop-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/StableController.java) owns one child connection, allocates fresh IDs and binds explicit displayed revisions. It retains uncertain exchanges and separates settle budgets from response deadlines. |
| Session and persistence | [MachineSession](../desktop-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/MachineSession.java) dispatches validated requests and manages receipt delivery. [AuditStore](../desktop-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/store/AuditStore.java) records request outcomes, immutable exchanges and native save receipts. |
| Native input boundary | [GameController](../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameController.java) coordinates existing game callbacks at stable input boundaries; [UiBridge](../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java) describes and activates current native controls. |
| Public observation | [PlayerObservation](../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java) captures player knowledge. [GameplayObservation](../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservation.java) projects semantic facts; [CompactProtocol](../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/CompactProtocol.java) encodes each complete response. |
| World cues | [VisualCueCollector](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/effects/VisualCueCollector.java) accepts reviewed public sources under map/FOV rules. [TargetedCell](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/effects/TargetedCell.java) retains already revealed pending warnings until native completion or cancellation. |
| Public English text | [TextProvenance](../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/text/TextProvenance.java) and [EnglishTextRenderer](../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/text/EnglishTextRenderer.java) retain source-bound public text independently of the GUI language. Unknown sources remain diagnostic; labels do not identify hidden objects. |
| Optional external decoder | [spdctl_client.py](../desktop-control/client/spdctl_client.py) validates complete v9 replies, wrappers and proposed intents without transport or gameplay policy. |
| Native alarm events | The no-op runtime sound observer is called outside the Sample monitor after desktop playback verification. Only native ALERT maps to an immutable `game.audio` alarm occurrence in the existing ordered display-event FIFO; paired persistence and event queries preserve it without a new table or unsolicited output. |
| Packaging and trace transport | [desktop-control/build.gradle](../desktop-control/build.gradle) creates the catalog and ARM64 app. [Native bootstrap](../desktop-control/src/main/native/spdctl-bootstrap.c) preserves child wire bytes and keeps diagnostics separate. |

## Observations, revisions and outcomes

Each live play/full/src observation and each frozen before/after snapshot owns its
template tables and local references. Use a live frame's own scope/revision;
missing historical bindings do not inherit a current live revision. Expand action/inventory
records, restore applicable activity/cancel bindings, expand UI nodes and resolve
operations, labels, semantic subjects and defaults against that frame alone.
Full/src add explicit defaults or public source provenance; they do not provide an
uncompressed bypass. Missing, null, false and zero keep their documented meanings.
Historical raw/reply bytes remain immutable and are never current action bindings.

Revision validation follows facts that can change the meaning of an input:
hero/world state, native controls, targeting and meaningful warnings. Decorative
refreshes and completed-action feedback may change an observation without changing
its revision. Native red warnings remain until the attack resolves; fading the
marker does not end the warning. Removing a native shield state ends its semantic
reference while the GUI can fade naturally. Do not disable warning checks or
invent a replacement revision to work around a stale decision.

Finite completion also waits for reviewed public presentation: attached visible
characters finish moving and their auras finish following, chilled effects reach
their first actual presentation, finite sentry tails finish, and semantic floor
item effects finish landing. A naturally empty particle interval within the same
already observed continuous episode does not repeatedly remove its semantic fact.
Only eligible public sources that can advance contribute to readiness; hidden,
detached, frozen or out-of-scope sources cannot hold completion indefinitely.
While this boundary is pending, execution remains `in_progress`; it does not
publish a premature final `player_ready` binding.

New ordinary and activity revisions use their existing durable per-kind counters
in decimal (`r10`, `a10`). Other kinds retain base36 spelling. Allocation remains
one paired transaction with uniqueness, overflow and rollback protection. Existing
registry mappings and raw/reply bytes are immutable, including historical base36
revisions. There is no counter conversion, profile migration or older-launcher
rollback allocator. Clients continue to treat the complete handle as opaque;
process epochs still invalidate old action bindings.

Public world cues use current FOV and reviewed knowledge scope independently of
camera/viewport/UI occlusion. Known-map export retains the complete explored floor.
Current observations and new events report semantic facts, not retired screen
samples, atlas/frame/transform data or generic particle histograms. Reviewed
contributor counts and sacrificial-fire density remain public facts. Source
attachment and visibility checks still matter after unused rendering payloads
are removed. Internal [snapshot coverage](../game-control/SNAPSHOT_FIELDS.md) is a
separate diagnostic contract and must not become a gameplay knowledge source.

One request receives one initial wire response. Synchronous success already
contains the current observation. An `in_progress` response exposes an interruptible
boundary before settling; it does not prove completion. The controller's local
`settle` preserves the original action receipt, optional scope discovery and fresh
observation separately. Soft call-budget exhaustion is pending; the sent request's
hard response deadline is not renewed. Pending queries are resumed, never resent.
For direct clients, use the receipt/state sequence in the help. Quit must deliver
its original successful terminal receipt before waiting for exit; do not query
state afterward.

Native save receipts describe actual save callbacks, not all in-memory state.
A successful action or state query does not prove that the original action saved.
Original outcomes, save receipts, late replies and current observations remain
separate even during recovery. Unknown execution, malformed responses and loss
must retain the original identity and bytes; never automatically replay them.

## Ordinary action preparation and commit

Ordinary actions use `prepareAction` to bind the request identity, scope, revision,
arguments and optional new-run identity. At a stable game boundary it validates
the request and freezes one public/internal `before` snapshot. The session no
longer performs a separate ordinary-action `observe` and revision check before
asking the game to prepare. Queries and the separate cancellation lease retain
their own existing boundaries.

`MachineSession` writes the paired `markExecuting` audit from that exact snapshot
outside the game scheduler lock. Only successful audit persistence permits
`commitPreparedAction`. Commit rechecks the shared public decision projection,
including input generation, controls, scope and meaningful cues, without producing
another internal audit snapshot. A change rejects the old decision rather than
rebinding it. The common final validation/dispatch path executes input at most once.

Preparation owns a short Actor hold, not a GUI input lock. Input or scene/scope
changes invalidate and release it before stable-state checks. `abortPreparedAction`
can cancel queued or prepared work before commit, so late preparation after a
timeout cannot strand the hold. Once submitted for execution, the original
execution futures own its outcome; a wait timeout uses `in_progress`/settle and
cannot abort, replay or prepare the action again. Failures before input dispatch
remain definitely not executed; uncertain failures after dispatch remain `UNKNOWN`.
The optional new-run identity belongs to that preparation and is discarded on
rejection or abort. Continuous-activity ACK, cancellation, save-receipt ownership
and quit-receipt delivery keep their existing public contracts.

## Profile and verification boundaries

The v9 profile is independent of earlier profiles. Nonempty profiles without a
valid schema-12 audit pair, including older schemas, are rejected before writes;
both metadata stores must also match game 4.0.2 and game version code 922.
There is no automatic migration, deletion or old-wire fallback. Implementation
and regressions use newly created, explicitly labeled isolated profiles. They do
not inspect or operate a personal game. Editing source or rebuilding an app does
not hot-update an existing JVM.

[UI coverage](cli-ui-coverage.md) records static input routes. The visual inventory
is generated by [CombatVisualInventory](../game-control/src/test/source-text/CombatVisualInventory.java)
and checked against [combat-visual-reviews.json](../game-control/src/test/resources/combat-visual-reviews.json)
using the [ledger validator](../game-control/src/test/python/combat_visual_inventory.py).
Typed evidence distinguishes current test references and source-only review.
A reviewed group's route/visibility contract does not imply that every producer
has its own runtime test. Removed source sites remain explicitly retired in the
ledger; do not relabel missing or obsolete evidence as a passing fixture.

Run focused checks for the change, then the applicable full Java and Python gates:

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s game-control/src/test/python -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s skills/pixel-dungeon-run-assistance/tests -p 'test_*.py'
```

For a release, use `:desktop-control:packageMacArm64` plus the runtime-classpath
writers described in the [maintenance skill](../skills/shattered-pixel-dungeon-cli-release/SKILL.md).
Protect the authorized output path and current running image. Verify the actual
executable's version, byte-identical source/bundled/emitted help, catalog,
architecture and signature. A local ad-hoc signature is not notarization.
`controller_package_smoke.py` and `slow_quit_package_smoke.py` under
`desktop-control/src/test/python/` provide isolated package lifecycle cases; the
latter needs the test-only crash agent and must be labeled instrumented evidence.
Read each script's `--help` for explicit paths and supported options.

A passing static inventory, synthetic projection, fixture, package smoke and real
playthrough are different kinds of evidence. Report exactly which ran on which
build, along with failures and exclusions. Generated traces, JSON, profiles and
runtime artifacts belong in ignored build directories, not `docs/`.

## Offline token research

The retained token tools are explicit offline studies, not gameplay clients.
They read caller-supplied public traces and do not launch or inspect a personal
profile. Historical CLI7 decoding comes from the fixed Git revision and verified
source hashes in [historical_v7_helpers.py](../desktop-control/src/test/python/historical_v7_helpers.py),
loaded without replacing the current protocol-9 decoder. The required Git objects
must be available; there is no silent fallback to current code.

- [cli7_token_benchmark.py](../desktop-control/src/test/python/cli7_token_benchmark.py)
  requires the historical v7 context, explicit corpus/fixture/classpath/output,
  and `tiktoken==0.12.0`. Zero savings or growth is a valid measured result;
  `--require-smaller` opts into a separate strict performance gate.
- [cli9_token_study.py](../desktop-control/src/test/python/cli9_token_study.py)
  requires explicit session, baseline and output paths and `tiktoken==0.14.0`.
  Frame counts come from the supplied baseline, not a fixed historical corpus size.

Both require a fresh, empty output directory and preserve source inputs. Consult
`python3 <script> --help` for arguments. Do not mix their token totals without
accounting for tokenizer/version, corpus, encoding and newline conventions.
Reference-token measurements and synthetic projection equivalence are not model
billing, native field equivalence, package gameplay or an autonomous clear.

## Historical records

The [8.0.1 recovery record](cli-8.0.1-recovery-validation.md) is the preceding
patch's dated evidence. Older per-version reports were removed from the active
checkout and remain in Git; [CHANGELOG](../CHANGELOG.md) pins historical links to
the pre-cleanup revision. User-requested [gameplay memory](../memory/README.md)
retains lessons and clearly marked original source copies. Their old coordinates,
paths, versions and permissions do not authorize current actions.
