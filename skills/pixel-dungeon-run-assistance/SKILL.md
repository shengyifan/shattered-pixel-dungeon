---
name: pixel-dungeon-run-assistance
description: Plan and operate a Shattered Pixel Dungeon Warrior run through the project's public spdctl protocol, with source-backed tactics, resource budgets, boss preparation, and evidence-bounded postmortems.
---

# Pixel Dungeon run assistance

Use for an authorized playthrough, current-floor tactics, shop/alchemy planning,
or a review of recorded play. This project adaptation targets CLI.9.0.5,
protocol 9, audit schema 12, and game 4.0.2. Verify the actual connection's
versions and advertised capabilities; historical notes never establish live state.

## Read before acting

- Read [project agreements](../../AGENTS.md), the authoritative
  [protocol manual](../../docs/cli-help.md), and the
  [local memory index](../../memory/README.md). Current user instructions take
  precedence, including the chosen new/continued run, stopping policy, and ending.
- For Python decoding or intent validation, use the existing
  [client API](../../docs/cli-playthrough-client.md) and
  [module](../../desktop-control/client/spdctl_client.py). It has no transport or
  game strategy. Do not replace it with a partial decoder or a new process manager.
- Read the [Warrior playthrough retrospective](../../memory/2026-10-08-warrior-playthrough-retrospective.md)
  for demonstrated route, targeting, boss, and recovery mistakes. For talent
  choices, consult the [versioned Warrior notes](../../memory/references/warrior-talents-v3.3.8.md)
  only as a source-reading aid; verify current talent descriptions and equipment.
- Before a nine-challenge Warrior attempt, read the [decision card](../../memory/nine-challenges-warrior-4.0.2/00-run-plan.md)
  and [4.0.2 failed-run review](../../memory/2026-10-10-warrior-nine-challenges-retrospective.md).
  The review supplies resource costs and stop-loss lessons, not a winning route;
  historical coordinates, handles and potion colors are not new-run bindings.
- For analysis of one complete public JSON response, use [map.py](scripts/map.py)
  for the known map, [cells.py](scripts/cells.py) for exact cells, or
  [ray.py](scripts/ray.py) for conditional collision geometry. Each requires an
  explicit `--response PATH` or `--response -` for stdin; it does not connect to
  or operate the game. A file is a snapshot, not proof of current connection state.
  `ray.py` supports `projectile`, `magicbolt`, and `solidbeam`; it does not model
  Yog's `WONT_STOP`, all item overrides, or area damage. Verify the exact native
  mechanic before applying its result to a spending or movement decision.

## Connection and evidence discipline

Keep one authorized `spdctl control --machine` connection and one writer.
Read-only map/mechanics advisers may analyze its public observations and source;
they must not independently operate the game. Do not launch a second game against
the same profile. Do not read personal saves or private audit state for planning
or to compensate for missing observations. Use no GUI keys, mouse, focus probes,
window minimization, or filesystem edits as a control or save mechanism.

Preserve complete raw transport bytes separately from presentation. Decode each
observation independently, retaining its identity, complete known map, all entity
kinds, item descriptions, public cues, and semantic facts. The protocol manual
defines template expansion, missing/null/zero meanings, textless controls with
useful operation labels, and current bindings. Do not infer hidden item identities,
enemy HP, AI targets, or future rolls from source knowledge.

When `native_audio_events` is advertised and auditory information matters, read
the normal bounded `events` cursor for `game.audio` alarm occurrences. They are
verified native playback, not proof of physical audibility or a hidden source
location; absence is not proof that no alarm source exists. Event history is
separate from the live observation and does not independently rebind its revision.
Finish any outstanding response/settle bookkeeping before issuing another query,
and obey the user's stop policy before reading further events.

Choose only a currently advertised action, with the exact observed `rev` and
current locators. Read the complete reply before deciding again. A successful
synchronous action reply already supplies the new observation; do not append a
routine `state` query. Inspect actual hero position, depth, prompts, and resources:
`completed` does not prove that travel reached the intended destination.

Expose `in_progress` immediately. Use controller `settle` for the original action
and keep its outcome/save receipts separate from `observation` and late responses.
Soft budget exhaustion is ordinary pending. For `response_pending`, finish the
same outstanding query through `settle`; never resend it or substitute `state` to
clear controller bookkeeping. Cancellation uses the advertised activity `rev/rid`.
Follow the manual for exact receipt/discovery/observation and quit behavior.

On a rejected, unknown, lost, malformed, or timed-out response, retain the original
request and evidence. Establish its outcome before another decision, within the
user's stopping policy; do not replay an uncertain action or silently replace its
revision. A stale rejection permits a new decision only when recovery is allowed
and a fresh observation supports it. A decode failure is not proof the game action
failed; `full/src` are not uncompressed decoding bypasses.

## Floor and encounter planning

Before each floor, inspect current HP/shield, hunger, equipment and strength,
inventory/charges, buffs, known routes, enemies, hazards, quests, shops, laboratories,
and exits. Give a short plan for exploration, upgrades, threat handling, and a
resource reserve. Re-plan after material observations instead of finishing a route
whose assumptions changed.

At a boss, phase change, unfamiliar enemy, or mechanics mismatch, identify the
source implementation from public evidence and verify the checkout version. Trace
the concrete implementation, relevant parents, buffs, level rules, collision, and
scheduling. Keep confirmed source rules, current public facts, inferences, and RNG
uncertainty separate. Source code explains a mechanic; it does not reveal the
current hidden instance state.

Before the next risky action, forecast threat priority, line of sight, plausible
incoming damage, worst-case resource cost, escape route, and recheck conditions.
Make one decision at a time. Native travel/rest can advance multiple turns; retain
its interruptible observations and stop at risk boundaries. Use it only when the
route or termination condition has been checked.

- Compute cells from the current map width. Validate the whole route and every
  entity kind, including tombs, containers, plants, heaps, and traps. Visibility,
  walkability, and projectile reach are different properties. Check the particular
  item's collision rules before spending a limited projectile or charge.
- An adjacent ordinary heap can chain movement and automatic pickup. Treat that
  input as potentially more than one hero-time unit and compare inventory, heap,
  hunger, HP/shield, buffs, and enemies afterward. An interrupted pickup is not
  evidence that the item was collected.
- Treat stairs and branch entrances as boundaries. Inspect the destination and
  confirmation, audit remaining floor resources, and make the transition deliberate.
  Do not casually resume a route interrupted at a transition. Re-observe the new
  floor before planning from it.
- Budget temporary invisibility, haste, levitation, and light against the actual
  connected route, including corners and interactions, with an exit margin. After
  teleportation, forced movement, levitation expiry, summons, or regrown terrain,
  reassess the destination and all affected firing lines.
- A displayed zero counter does not establish that a buff detached or its landing
  effect resolved. Natural Bleeding has stochastic decay; forecast a range and
  recheck each action. Warrior Shield cooldown, current shield, and maximum shield
  are different facts. A visible health bar is an estimate, not hidden exact HP.

## Resources, shops, and alchemy

Choose exploration coverage from the user's objective and remaining resource budgets.
For an all-nine-challenge win attempt, prioritize key resources and a connected exit
route; optional coverage and quests need food, light, incoming-damage and escape
allowances. Collect safe, valuable resources on the route; justify a cleanup loop
against hunger, spawning, remaining charges and escape cost. Do not turn exploration
into respawn farming or repeated grass traversal without a concrete benefit.
Starvation prevents normal regeneration and can damage HP through shields; verify
current hunger and regeneration rules before promising a recovery budget.

At a shop, list free slots, missing bags, equipped gear, realistic future gear,
sell-now items, purchase priorities, and the cash reserve. Usually sell obsolete
ordinary weapons/armor before useful consumables. Do not extend that shortcut to
thrown stacks or duplicate wands: durability, ammunition, independent charge pools,
Liquid Metal, Arcane Resin, and bag capacity can change their value.

At a laboratory or after acquiring a magical item, decide whether to keep, craft,
equip, identify, cleanse, charge, or actively use it. Check the native recipe and
costs, curse knowledge, quickslot usefulness, next trigger, and emergency reserve.
Carried trinkets and equipped artifacts/rings have different activation rules.
An active artifact near capacity needs a use plan, not automatic hoarding.
Healing, Purity, Cleansing, and Mageroyal are not interchangeable; check the exact
effect that must be removed and whether the danger is an attached buff or a blob.

For boss preparation, budget food, healing, mobility/control, charged tools, and
revival separately. Recheck phase gates and reinforcement priorities. During Dwarf
King's waves, evaluate exposed warlock firing lines before committing to another
target. During Tengu's intermission, verify whether rest can actually finish and
what movement advances the phase. For Yog, use the retrospective's source-backed
checks for fist protection, delayed damage, grass, blindness, and wall-piercing
death rays; do not treat a fading marker as a cancelled attack. Current
`red_target` semantics are defined by the protocol manual.

## Nine-challenge stop-loss decisions

Use the decision card's resource table for version-specific values and its linked
review for observed costs. Suggested waiting limits are adjustable tactical budgets,
not native action parameters or proven optimal policies.

- **Food:** budget nutrition, Hearty Meal's threshold-dependent healing, regeneration
  and the baseline three-time-unit eating cost without Iron Stomach separately.
  Avoid eating in melee without Iron Stomach; do not take damage deliberately to
  reach a talent threshold. After
  the last meal, prioritize known supply/key-resource/exit routes and stop low-value
  cleanup or pursuit. High-HP starvation can justify eating for regeneration when
  the resulting progression benefit is explicit.
- **Rest:** finite status removal, NPC/shield waiting and short ambushes use one
  `wait`, its complete response, then a new decision. Continuous `rest` requires
  a checked native termination condition, budget and retreat margin; it has no
  fixed-turn argument. Do not spend the last food/light window resting until hunger
  interrupts. Re-plan when the goal, waiting budget or safety margin is exhausted.
- **Light:** compare the displayed reserve with the actual connected route,
  interactions, expected combat and retreat. If the reserve cannot cover the plan
  and no usable spare torch is available, stop optional quests/pursuit/waiting and
  redirect to supply or an exit. Use short
  moves in dark ranged-enemy areas; damage, poison or forced movement invalidates
  the prior route. A visible torch still needs a safe approach past its guards.
- **Gnoll Trickster:** adjacency prevents its shot only for the checked action.
  Equal-speed two-cell pursuit needs an attainable attack endpoint. Budget a first
  door-side lure, for example 3–5 single waits. End it when the budget expires
  without a real attack opportunity, or earlier on damage, new threats or insufficient
  food/light. A new attack window or material change permits re-estimating the
  remaining cap; retain cumulative time and resources spent rather than resetting
  the budget. These limits govern further lure/pursuit; an already adjacent attack
  opportunity needs its own current damage and retreat assessment.
  Fleeing can sidestep or approach; wandering
  does not guarantee a return. Projectile blockage, either FOV and surprise are
  distinct; a heap can prevent a door closing.
- **Unknown potions:** retain identification exclusions without treating them as
  safety. Stop routine trial drinking without a survivable adverse-effect/escape
  budget; identify earlier while resources remain. At low HP, starving or cornered,
  compare known control/movement/combat before gambling on a bottle. A necessary
  gamble must account for self-centered gas, paralysis automatically advancing
  time without an actionable frame, and freezing/bottle chains; do not promise
  another chance to cancel. Death-revealed identities/maps are postmortem evidence.
- **Transitions:** distinguish actual shield from `counter_kind:cooldown`; hunger
  neither consumes shielding nor triggers the seal. Stop optional work before HP,
  food, light and escape options all run out. Unknown cells are not escape promises,
  and a giant blocked by a narrow door may still attack across two connected
  non-solid cells.

Stop-loss means changing the gameplay plan within the user's authorization. Settle
or cancel the original activity under the protocol's pending-request rules before
another decision; settle timeouts do not cap game turns. Never hide a batch of waits
or rebind an old intent. At the user's stop condition, stop inputs and queries;
stop-loss does not authorize quitting, restarting or starting another run.

## Pause, completion, and review

On a pause request, stop all game writes immediately, including further actions or
settling, and preserve the connection unless the user says otherwise. For an
already dispatched native activity, report any unresolved outcome; stopping input
does not prove that the simulation itself is frozen. For an
unrecoverable anomaly or a run-ending event, follow the user's stop policy. When
the requested endpoint is the normal win, defeating Yog and acquiring the Amulet
are separate milestones; use the public run outcome to confirm the selected ending.
Do not quit, restart, send extra queries, or begin another run after a policy that
requires stopping at the outcome.

For a handoff, retain the connection status, latest public observation identity,
pending original request/outcome, resource plan, and open questions. On resumption,
obtain fresh public state through the authorized connection before choosing an
action. Old coordinates, handles, resources, and plans remain historical evidence.

For a postmortem, separate executed mistakes, costly but defensible choices,
pre-execution corrections, random outcomes, and tool failures. Cite observed
before/after evidence and real costs; do not invent hidden causes or an untested
optimal alternative. Reuse static source findings, keep adviser tasks focused,
and reduce redundant queries/calls rather than deleting public facts to save tokens.

New desktop profiles default to open character/start-option and information-preview
entrances, without earned badges or victories. Existing profiles retain their
progression. Re-observe the actual native menu; availability is not evidence of a
win, talent points, a subclass or an armor ability. Challenges are off until chosen.
