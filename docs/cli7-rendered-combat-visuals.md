# CLI.7.0.1 rendered combat observations

Historical CLI.7.0.1 record. CLI.8.0.0 replaces these rendered fields with the
semantic contract in [current combat observations](cli-combat-visuals.md).

Protocol 7 and audit schema 10 remain unchanged. This batch adds public drawing
evidence and fixes quit delivery, UI identity lifetime and argument validation.
The source ledger is a review of the current source version, not a claim of a
complete gameplay run or a pixel-identical GUI recording.

## Observation boundary

Only existing, actually drawn visuals can create map cues or display events.
The collector checks the current scene/map, attachment, drawable geometry,
current FOV, complete viewport and conservative UI occlusion. Direction requires
two eligible completed draws in the same visual lifetime; a first, hidden,
reused or camera-only sample must not invent motion. No AI target, cooldown,
revival timer, requested particle count or blob amount is used as public evidence.

These gates intentionally omit partial viewport fragments, hints behind modal
windows and through-fog effects. Vault movement rings and search checks do not
receive a special permission to bypass FOV. Such sources are reviewed as
information-bearing but visibility-limited, not mislabeled cosmetic.

Synchronous observations, full/src views and each frozen before/after snapshot
retain their own appearance data. Historical events never become a live scope or
revision. Existing raw/reply records are immutable. All new fields pass through
the same reversible protocol projection and strict client expansion.

## Public fields and events

`cues.cues` retains `kind/cell`. Optional `source_cell` identifies a second
independently visible endpoint. Optional `dir` uses the existing eight wire
directions. RGB `color`, rendered `opacity` and immutable `appearance` describe
drawn values. The appearance may contain a known public asset atlas, frame pixels,
texture size, transforms, tint, or a native icon symbol. It contains no Java
object/class identity, private target, planned projectile endpoint or arbitrary
filesystem/cache path. Unrecognized assets remain explicit partial evidence.

Known discrete routes include charge/posture warnings, summon/rock/bomb/arena/
beacon particles, blue checks, pylon/lightning arcs, beams, drawn projectile and
magic particle locations, chain links, flares, wound/surprise marks, spell icons,
map targeting crosses and special paused/downed appearances. Rendering names do
not promise damage, an attack outcome or a particular hidden state.

Text nodes expose a single `color`, or ordered `styles` carrying independently
translated visible text/color runs. Markup segments are bound to the original
displayed source; incompatible English markup structure becomes a diagnostic,
not source-language offsets applied to English text. Item status strings stay
strings and retain their original nodes. Black/zero colors remain explicit.

Native UI status fields preserve item/badge/spell icons, action/attack portraits,
boss warnings, quickslot targeting and the already rendered turn-progress arc.
Buff `icon_overlay` reports actual covered/total pixel heights. Neither that
overlay nor the arc is labeled as an exact hidden timer. A target portrait does
not reveal which of several identical unseen mobs owns it.

| Event | Evidence and lifetime |
| --- | --- |
| `game.visual` | Discrete completed-draw cue snapshots, including disappearance. |
| `game.floating_text` | Each actual floating display occurrence, including colors, independently visible icon and permitted cell anchor. Pool reuse creates a new occurrence even when the numeric value repeats. |
| `game.banner` | A known `boss_slain` or `game_over` graphic actually drawn unobstructed. One occurrence per show; fade updates are not duplicate announcements. |
| `game.visual_metrics` | Explicitly sampled particle histograms: `sampled_display_snapshot_v1`, `sample_period_ms:250`, actual sample timestamp and measured visible counts/appearance. |
| `game.screen_visual` | Visible screen-overlay color/effective opacity/blend and the camera displacement actually submitted to drawing. Quantitative changes are explicitly sampled at 250 ms; visible episode start/end is immediate. |

The optional current `cues.metrics` and `metrics_at` refer to the same completed
draw as the current cue snapshot and are not sampled. Historical quantitative
metrics are sampled at most four times per second for ordinary histogram changes;
new visible emission episodes and disappearance are captured immediately. Internal
episode identities never leave the process. Position/color-bin movement alone
does not masquerade as a new emission episode. This preserves short-lived bursts
without calling a sampled history a complete frame recording.

Generic emitter measurements cover count variations from weapon procs, darts,
wands, abilities and artifacts through the actual shared draw path. Sacrificial
flame density has a separately named observed measurement. Counts are stochastic
render evidence; they are not converted to item levels, remaining sacrifices,
damage or turns. Purely decorative sources can also contribute to a visible
histogram; that does not grant them hidden gameplay meaning.

Display events share one immutable cross-kind FIFO. The protocol worker writes
bounded batches in paired transactions with the existing DELETE journal, EXTRA
synchronization and fullfsync settings. Queue acknowledgement follows commit;
failure retains the unacknowledged batch. Background-only 50 ms coalescing groups
nearby display signals; each task handles at most 64 FIFO events and yields between
batches to queued requests. Request-boundary and final-tail drains remain immediate.
This writes no unsolicited wire response and never performs
SQL on the render thread. Final shutdown drains its finite frozen tail. Actual
capture order across event types and each event's actual timestamp are retained.

Current `screen_effects` and `screen_effects_at` belong to the same completed draw.
They include no future duration, configured shake magnitude or gameplay cause.
Overlay opacity includes the original solid texture's ARGB alpha; additive and
normal blending stay distinct. The camera sample comes from a matrix used for a
nonempty draw, including PixelCamera alignment, not a queued shake request.
Empty screen history means no effect is currently observed; occlusion can end
an observed episode without ending its underlying animation. Map-cue visibility
uses the same submitted camera transform as the actual pixels.

## Source inventory and review

`CombatVisualInventory` parses and attributes all current core and SPD-classes
sources with javac, without generating/loading game classes. It inventories
visual constructors/types, attachment and appearance calls, direct field writes,
vector mutations, draw/update/effect callbacks, anonymous types and inherited
rendering sinks. Each site has an exact identity and enclosing-body digest.

The reviewed ledger assigns explicit routes, triggers, visible evidence,
visibility limits, lifecycle and test references. Combat categories are
`covered_current`, `covered_inspection`, `covered_history`, `required_uncovered`,
`cosmetic_reviewed`, `not_player_visible` and `unresolved`. Noncombat title,
account-independent menus, credits and other scoped-out surfaces are listed
separately as `scope_exclusion`; they are not called universally decorative.

New, removed or changed sites fail the source check. Regenerating an inventory
does not create a review or convert an uncovered source to covered. An actual
inspection route must expose the same information in the same game state; static
help cannot substitute for a live warning. The inventory always reports
`runtime_verified_by_inventory:false`. Shared-sink tests and source attribution
are distinguished from producer-specific engine fixtures.

## Reliability changes

An explicit protocol quit owns its original request/scope throughout execution
and delivery. A timed-out quit remains queryable. Asynchronous completion writes
the terminal audit record without an unsolicited second response. Normal exit
follows successful terminal receipt delivery for that exact quit; unrelated
queries do not close the process. UNKNOWN/rejection/output failure retain their
own meanings, and EOF does not dispatch a second quit. Further game actions after
success awaiting delivery fail `SESSION_CLOSING`.

UI control and callback registries now use weak identity keys. Live-frame
execution references stay strong, IDs stay monotonic, and reclaimed objects never
make old handles valid again. Rendered appearance is kept separate from native
intent appearance: idle animation, glow phase, avatar pulses and passive banner/
floating lifetimes remain visible without independently staling a decision.

UI display evidence is frozen after the native scene draw and before the next
update. Text, translated style runs, provenance, icon appearance, Buff overlays
and bar pixels come from that completed draw together. Post-draw changes cannot
pair a new label with an old color or expose the next animation frame early.
Weak identities and lifecycle/attachment bindings reject evidence after scene,
child or camera replacement and object reuse. Live operation checks remain
separate; meaningful display changes must receive a matching subsequent draw,
while hidden or optional absent pixels do not hold completion indefinitely.

Java direct requests and the controller share static argument rules; the Python
helper also checks current advertised dynamic constraints. Invalid explicit rid,
null/boolean/float integer substitutes and malformed fields are rejected. Text
limits use UTF-16 units. Scroll clamping and legitimate unknown-cell targeting
remain native behavior. Env conversion failures and invalid saved references
retain the failed response's DecodeError identity and enclosing action context.
