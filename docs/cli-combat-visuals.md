# CLI.9.0.0 semantic combat observations

Protocol 9 and audit schema 12 publish player-facing combat facts from existing
game state and presentation sources. The observation is a complete public snapshot
for its own revision. It does not encode an atlas, frame, texture, transform, RGB
value, particle histogram, camera movement, screen effect or display timing as a
substitute for the fact a player needs to decide an action. Source responsibilities
and verification boundaries are documented in the [current architecture](cli-architecture.md).

## Current-frame facts

The normal `play` view retains the public map, hero, inventory, entities, buffs,
actions and UI. `full` makes documented defaults and diagnostic detail explicit;
`src` adds the corresponding public text-source provenance. Neither view adds
ordinary rendering internals. A frozen `before` or `after` snapshot has its own
complete fact set, template tables and bindings; historical `raw/reply` bytes are
never rewritten or interpreted as a new live revision.

`inv[].shown` and `buffs[].shown` carry the current public presentation of
`status`, `extra`, `level`, `symbol`, `variant` or `counter` strings when observed.
A buff can include `progress:{covered,total,basis:"displayed"}`. An item can
include `strength:{value,estimated,insufficient?,mastered?}`, `flags`, or a
semantic `badge`. Source-bound item status can include `shown.broken_seal:true`
or `shown.lit_candle:true`; an `item_status` world cue can carry the same
FOV-visible heap flag. `shown.glow` contains only reviewed named variants,
including `explosive_heat` stage or `resin_fortified` hint, never glow pulse
timing, hidden durability or resin count. Preserve unknown or partial information as such: a displayed
estimate is not an exact hidden quantity, and an absent field does not authorize
an inferred value. Hero and entity `health_estimate.samples` contain distinct
`{total,filled,with_shield,incoming_dot,basis:"displayed"}` bar samples, not hidden exact HP.
Every sample requires an integer `incoming_dot` between zero and `with_shield`.
It measures the native darkened DOT overlay after layout. Missing bars do not
produce zero-valued samples, and observation never calls `Char.incomingDOT()`.
The existing one-segment foreground rounding tolerance remains unchanged.
An item `shown.status_kind:"quantity"` comes only from an exact source-bound
native quantity display with matching captured text. An artifact charge or
cooldown that happens to show the same numeral remains a distinct status;
unknown or special status text is retained. Warrior Shield buff `shown.counter_kind` and
`shown.progress_kind` use `shield` or `cooldown` from its selected native
display branch, so the visible numeral or small bar is not guessed by text.
The hero's `turn_progress:{sweep}` describes the current public action-progress
indicator. `ui.feedback` is a current list of semantic floating, log or banner
feedback, separate from queried event history. An icon-only floating occurrence
retains its semantic symbol even when it has no text.

Each `ui.nodes[].subject` is an explicit reference to one public subject:
`{kind:"item",loc}`, `{kind:"hero"}`, `{kind:"hero_buff",index}`,
`{kind:"entity",index}`, or `{kind:"entity_buff",entity,index}`. Indexes refer
to the same observation and remain valid only there. A label, icon, screen
position or color must never be used to guess a subject or an action. Keep
`ui.nodes[].ops` as references to the complete ordered `acts` table or inline
operations, and retain every current operation constraint.

The standalone `actions` query has no hero, inventory or entity tables. Its
bound nodes use direct `subject_data` containing that current public target's
facts, without a kind wrapper or an unresolved table reference; they omit
`subject`. If the owner is ambiguous, `subject_data:null` and local partial
diagnostics report `unresolved_subject`, while label, locator and operations
remain. Native node gestures are omitted only when complete advertised `ops`
already express them.

## World cues and feedback events

`data.cues.cues` describes public world cues. `data.cues.status` is
`last_observed` or `not_observed`. After an ordinary draw, the game observes its
existing scene sources without advancing actors. Source attachment/lifetime,
current run/map and source-specific gates remain; camera, viewport and UI occlusion
do not filter world knowledge. Ordinary live cues use FOV; reviewed static terrain,
native objects and always-visible environments retain their native known-cell rules.
`movement_shadow` is an explicit native public-shadow exception: its observed cell,
optional motion direction and reviewed appearance symbol can exist outside FOV and
without known terrain at that location. It never creates an enemy record or a map
cell, and carries no hidden mob identity, HP, target, patrol route or timer.
Motion/fade is presentation evidence and does not independently stale an action.
A cue has `kind/cell`, optional independently known `source_cell` and direction,
and optional `appearance` containing reviewed semantic `style`, `shape`,
`paused`, `stage`, `cells`, `partial`, `count` or `symbol`. A radial `shape`
such as `ring` or `halo` is an observed indicator, not a damage prediction.
`ward_state` can contain the
displayed Ward tier and, for tiers 1–3, a `charge` fraction with
`basis:"displayed_brightness"`; it does not reveal the hidden exact use count.
The selected brightness channel is rounded to 8-bit display precision before
that fraction is reported. Kinetic buff `shown.charge` follows its selected
white-to-yellow-to-red gradient with `basis:"displayed_gradient"`, also
rounding the display channel to 8 bits before computing the fraction. Neither
field reports a hidden higher-precision meter.
`statue_armor` contains the armor tier shown by the statue's current frame.
`crystal_spire_state` reports the selected Blue, Green or Red form as
`intact`, `cracked`, `damaged`, `heavily_damaged` or `destroyed`; it does not
report exact hidden HP.
The Spirit Arrow projectile can carry `nature_powered:true` only when its
current native leaf-trail branch is displayed; ordinary and sniper-special
arrows omit that flag. The cue does not reconstruct this from a hidden attack
mode or an earlier buff snapshot.
A radial visual extent can use
`coverage:"visual_extent"` and partial known cells; it is not a predicted
damage area. A beam with a hidden endpoint reports only known cells and
`partial:true`, without revealing its hidden origin or endpoint. Reviewed
`GameplayBurst` cue kinds may carry
`appearance:{count,basis:"observed_particles"}`: `count` is the actual visible
contributor total after the native emission, never its requested count or a
generic particle histogram. The special `sacrificial_flames` density cue also
retains its exact current observed count. In `game.visual` history, quantity-only
changes for any such counted kind are sampled at 250 ms and marked by
`sampled_quantities:{<kind>:{sample_period_ms:250}}` when present; discrete
warnings, onset and disappearance are retained immediately. There is no
`game.visual_metrics` event stream. No cue may
reveal a hidden AI target, cooldown, remaining timer or unobserved location. A
native movement shadow can reveal its displayed location without revealing the
underlying terrain. Cue
names identify observable conditions, not a promise that an attack will hit
or deal a particular amount of damage.

### Pending red warnings

`red_target` retains an already publicly observed warning until its native attack
completes or is cancelled. Marker alpha/scale fade is not the attack's lifecycle.
Each native attack episode owns its exposed cells; repeat drawing of one cell
within that episode is coalesced. Independent owners remain separate internally,
but public warning cells are deduplicated to match the native pooled marker;
hidden attack multiplicity is not reported. Only cells that passed the ordinary
source/FOV checks are remembered,
and each new snapshot emits their intersection with current FOV. A warning never
revealed to the player is not discovered by looking up a private target list.

Episodes follow native completion/cancellation, including rooted Yog delays,
asynchronous landing callbacks, separate crystal waves, individual rocks, delayed
rock falls, vault scans, nova expansion and gas release. A fired asynchronous
attack remains pending after its original owner disappears until its callback
resolves it. Native temporary transformation/stasis transfers existing warning
knowledge to its holder and back when the original actor returns; permanently
abandoning that preparation cancels it. This does not reconstruct target cells.
Run/level/branch changes and terminal run outcomes clear the old
scope. A same-level scene rebuild preserves it; a new process starts empty and
does not read stored game or audit data to rebuild warnings. Episode identities,
owner state and timers are not wire fields or save fields.

True warning onset/termination still invalidates decisions; passive marker fade
does not. Likewise, `character_shielded` ends with native shield-state removal,
while the GUI halo may finish fading. The later visual tail alone does not change
the revision. Strict revision checks and the complete immutable raw/history
representations remain in force.

Unknown native indicators retain `unmapped_indicator:true` with canonical
partial presentation and a diagnostic carrying `field`,
`code:"unmapped_indicator"` and a public source `indicator` variant. Existing
diagnostics remain alongside the fallback. A missing Ward/statue tier is never
guessed from an underlying actor value.

New cue and floating events use `gameplay_snapshot_v1`; banner occurrences use
`gameplay_occurrence_v1`; gameplay log snapshots use
`gameplay_log_snapshot_v1`. Existing event kinds `game.visual`,
`game.floating_text`, `game.banner` and `game.log` now carry semantic public
evidence. A historical event
does not become a current target, action binding or revision. Event query/drain
semantics, original action outcomes and save receipts remain separate. Protocol
8 does not emit generic rendered particle metrics, screen visual samples or raw display
appearance in current observations or new events.

The source inventory records reviewed routes and source contracts; it does not
prove producer-specific runtime coverage. Current maintenance and test entrypoints
are in the [architecture](cli-architecture.md); executed checks and package evidence
are recorded separately in the [CLI.9.0.0 validation report](cli-9.0.0-v4.0.2-validation.md).
