# Shattered Pixel Dungeon 项目记忆摘录

仅保留与本项目相关的记忆段落，供复盘本次通关和本地构建使用。这是面向公开仓库整理的项目摘录，不是完整个人记忆文件。历史状态和版本信息应按文中条件重新核实。

# Task Group: <project-root> / v3.3.8 read-only Warrior coaching, source-first tactics, and repeat-run protocol

scope: Coach a standard/no-challenge Warrior clear without modifying game data: fresh-save inspection, single-action controls, transition safety, source-grounded combat, inventory/alchemy, and Dwarf King/Yog preparation.
applies_to: cwd=<project-root> plus `~/Library/Application Support/Shattered Pixel Dungeon/`; reuse_rule=mechanics are pinned chiefly to local v3.3.8 commit `7b8b845a76`; inspect the live save and confirm checkout/version before applying any stateful tactic. Source/save access remains read-only unless the user explicitly authorizes another scope.

## Task 1: Convert the failed floor-20 Warrior/Gladiator run into a strict one-action, fresh-checkpoint protocol

### rollout_summary_files

- extensions/ad_hoc/notes/20260824T191454+0800-spd-multi-run-best-score-lessons.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T191454+0800-spd-multi-run-best-score-lessons.md, updated_at=2026-08-24T19:14:54+08:00, thread_id=None, authoritative repeat-run lessons and acceptance criteria)
- extensions/ad_hoc/notes/20260824T141808+0800-spd-warrior-floor20-postmortem-single-step.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T141808+0800-spd-warrior-floor20-postmortem-single-step.md, updated_at=2026-08-24T14:18:08+08:00, thread_id=None, confirmed Dwarf King loss and strict single-step protocol)
- extensions/ad_hoc/notes/20260824T183919+0800-spd-save-primary-numpad-control-protocol.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T183919+0800-spd-save-primary-numpad-control-protocol.md, updated_at=2026-08-24T18:39:19+08:00, thread_id=None, save-primary/control evidence)
- extensions/ad_hoc/notes/20260824T193329+0800-spd-v338-readonly-inspector.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T193329+0800-spd-v338-readonly-inspector.md, updated_at=2026-08-24T19:33:29+08:00, thread_id=None, persistent read-only inspector and legacy-helper replacement)

### keywords

- DwarfKing$DKWarlock, `game.dat`, `depthN.dat`, `.spdtmp`, `GameScene.onPause()`, `Dungeon.saveAll()`, `KP_7`, `KP_9`, `Tab`, `Q`, `spd-v338-readonly-inspector.md`, `--wait-new`, `selftest`, one-action, lifecycle checkpoint, rankings.dat, Gladiator Parry

## Task 2: Prevent accidental stairs and inefficient routes while collecting safe dew/seeds and rationing food

### rollout_summary_files

- extensions/ad_hoc/notes/20260824T145011+0800-spd-dew-route-efficiency.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T145011+0800-spd-dew-route-efficiency.md, updated_at=2026-08-24T14:50:11+08:00, thread_id=None, route, dew, seed, detour, and exit protocol)
- extensions/ad_hoc/notes/20260824T150116+0800-spd-hunger-states.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T150116+0800-spd-hunger-states.md, updated_at=2026-08-24T15:01:16+08:00, thread_id=None, precise Hungry/Starving correction)
- extensions/ad_hoc/notes/20260824T143505+0800-spd-transition-stair-safety.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T143505+0800-spd-transition-stair-safety.md, updated_at=2026-08-24T14:35:05+08:00, thread_id=None, source-confirmed `LevelTransition` safety)
- extensions/ad_hoc/notes/20260824T183415+0800-spd-v338-floor-clear-net-value-guide.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T183415+0800-spd-v338-floor-clear-net-value-guide.md, updated_at=2026-08-24T18:34:15+08:00, thread_id=None, high-coverage/net-value clearing)

### keywords

- `Hero.handle(cell)`, `HeroAction.LvlTransition`, `actTransition`, `Level.activateTransition`, Resume, HIGH_GRASS, Waterskin, Dewdrop, Hungry 300, Starving 450, respawner cooldown, floor-clear net value

## Task 3: Trace exact enemy, Boss, terrain, chasm, status, item-range, and Warrior-build behavior before committing a turn

### rollout_summary_files

- extensions/ad_hoc/notes/20260824T164025+0800-spd-v338-enemy-boss-warrior-guide.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T164025+0800-spd-v338-enemy-boss-warrior-guide.md, updated_at=2026-08-24T16:40:25+08:00, thread_id=None, enemy/Boss priorities)
- extensions/ad_hoc/notes/20260824T174222+0800-spd-v338-warrior-talents-build-guide.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T174222+0800-spd-v338-warrior-talents-build-guide.md, updated_at=2026-08-24T17:42:22+08:00, thread_id=None, Warrior talent/build audit)
- extensions/ad_hoc/notes/20260824T171251+0800-spd-v338-terrain-traps-resource-combat-guide.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T171251+0800-spd-v338-terrain-traps-resource-combat-guide.md, updated_at=2026-08-24T17:12:51+08:00, thread_id=None, terrain/trap/resource tactics)
- extensions/ad_hoc/notes/20260824T172542+0800-spd-warrior-chasm-knockback-methods.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T172542+0800-spd-warrior-chasm-knockback-methods.md, updated_at=2026-08-24T17:25:42+08:00, thread_id=None, chasm knockback methods)
- extensions/ad_hoc/notes/20260824T172713+0800-spd-chasm-knockback-landing-cell-correction.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T172713+0800-spd-chasm-knockback-landing-cell-correction.md, updated_at=2026-08-24T17:27:13+08:00, thread_id=None, landing-cell correction)
- extensions/ad_hoc/notes/20260824T180254+0800-spd-v338-debuff-removal-guide.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T180254+0800-spd-v338-debuff-removal-guide.md, updated_at=2026-08-24T18:02:54+08:00, thread_id=None, exact removal/protection sets)
- extensions/ad_hoc/notes/20260824T182207+0800-spd-v338-item-range-duration-aftermath-protocol.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T182207+0800-spd-v338-item-range-duration-aftermath-protocol.md, updated_at=2026-08-24T18:22:07+08:00, thread_id=None, item timing/range/aftermath checks)
- extensions/ad_hoc/notes/20260824T142702+0800-spd-source-first-encounter-protocol.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T142702+0800-spd-source-first-encounter-protocol.md, updated_at=2026-08-24T14:27:02+08:00, thread_id=None, mandatory source-first encounter procedure)

### keywords

- `__className`, `DwarfKing$DKWarlock`, Ballistica, Actor, `GhoulLifeLink.left`, PotionOfCleansing, PotionOfPurity, Degrade, Corrosion, Vertigo, knockback, landing cell, range, duration, aftermath

## Task 4: Audit shop capacity, ordinary versus ranged duplicates, artifacts/rings/trinkets, and alchemy before leaving a checkpoint

### rollout_summary_files

- extensions/ad_hoc/notes/20260824T161625+0800-spd-v338-complete-alchemy-guide.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T161625+0800-spd-v338-complete-alchemy-guide.md, updated_at=2026-08-24T16:16:25+08:00, thread_id=None, full active-recipe and proactive-use audit)
- extensions/ad_hoc/notes/20260824T155140+0800-spd-v338-all-artifacts-trinkets-source-guide.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T155140+0800-spd-v338-all-artifacts-trinkets-source-guide.md, updated_at=2026-08-24T15:51:40+08:00, thread_id=None, artifact/trinket classes, costs, and hazards)
- extensions/ad_hoc/notes/20260824T152908+0800-spd-artifact-ring-trinket-usage-protocol.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T152908+0800-spd-artifact-ring-trinket-usage-protocol.md, updated_at=2026-08-24T15:29:08+08:00, thread_id=None, pickup/equip/charge protocol)
- extensions/ad_hoc/notes/20260824T151929+0800-spd-food-and-ranged-duplicates-correction.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T151929+0800-spd-food-and-ranged-duplicates-correction.md, updated_at=2026-08-24T15:19:29+08:00, thread_id=None, correction to generic sell-now rule)
- extensions/ad_hoc/notes/20260824T142354+0800-spd-shop-inventory-economy.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T142354+0800-spd-shop-inventory-economy.md, updated_at=2026-08-24T14:23:54+08:00, thread_id=None, capacity/equipment liquidation workflow)

### keywords

- Velvet Pouch, Scroll Holder, Potion Bandolier, Magical Holster, MissileWeapon, `curCharges`, Arcane Resin, Liquid Metal, Alchemist's Toolkit, Timekeeper's Hourglass, Trinket Catalyst, `Recipe.java`, quickslot, reserve

## Task 5: Fill remaining Warrior-completion gaps and set Dwarf King/Yog stop gates for the next run

### rollout_summary_files

- extensions/ad_hoc/notes/20260824T185239+0800-spd-v338-warrior-completion-remaining-skills.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T185239+0800-spd-v338-warrior-completion-remaining-skills.md, updated_at=2026-08-24T18:52:39+08:00, thread_id=None, combat forecast, upgrades, quests, Ankh, and Yog completion)

### keywords

- Warrior, Veteran's Intuition, Broken Seal, Upgrade Scroll, Ankh, Imp, Demon Spawner, Yog, normal victory, ascension, combat forecast, accuracy, DR

## Task 6: Harden the read-only inspector and live-control loop with v3.3.8 disposable-save tests

### rollout_summary_files

- extensions/ad_hoc/notes/20260825T010000+0800-spd-keyboard-focus-gate.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260825T010000+0800-spd-keyboard-focus-gate.md, updated_at=2026-08-25T01:00:00+08:00, thread_id=None, mandatory fresh-focus/UI-layer gate before every key)
- extensions/ad_hoc/notes/20260825T005700+0800-spd-v338-levelup-buff-debuff-test-addendum.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260825T005700+0800-spd-v338-levelup-buff-debuff-test-addendum.md, updated_at=2026-08-25T00:57:00+08:00, thread_id=None, live buff, Bleeding, level-up, and Iron Will evidence)
- extensions/ad_hoc/notes/20260825T001600+0800-spd-v338-live-test-addendum.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260825T001600+0800-spd-v338-live-test-addendum.md, updated_at=2026-08-25T00:16:00+08:00, thread_id=None, adjacent-heap multi-time and control-semantics correction)
- extensions/ad_hoc/notes/20260824T193329+0800-spd-v338-readonly-inspector.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260824T193329+0800-spd-v338-readonly-inspector.md, updated_at=2026-08-24T19:33:29+08:00, thread_id=None, revised inspector semantics for blobs, custom tiles, statistics, and save-event limits)

### keywords

- `ENTER_HEAP_MAY_CHAIN_AUTO_PICKUP`, `ONE_KEY_CAN_ADVANCE_TWO_HERO_TIME_UNITS`, `PICKUP_CAN_BE_CANCELLED_BY_NEW_VISIBLE_MOB_OR_ANY_ATTACK_ATTEMPT_INCLUDING_MISS`, `BUFF_TIME_ZERO_NOT_PROOF_DETACHED`, `HeroAction.PickUp`, `duration`, Hunger, `Levitation.time`, Bleeding, Albino, Iron Will, `initial_shield`, Raise, focused app, `WeakFloorRoom$WellID`, `customTiles`, `customWalls`

## Task 7: Continue the observed Codex-assisted local Warrior run from Tengu through Caves floor 14

### rollout_summary_files

- extensions/skysight/resources/2026-09-05T12-00-00-Lumc-6h-memory-summary.md (cwd=<project-root>, rollout_path=extensions/skysight/resources/2026-09-05T12-00-00-Lumc-6h-memory-summary.md, updated_at=2026-09-05T12:00:00Z, thread_id=None, observed D7–D10 route/pre-boss arc; final Tengu outcome not captured)
- extensions/skysight/resources/2026-09-06T00-00-00-mNow-6h-memory-summary.md (cwd=<project-root>, rollout_path=extensions/skysight/resources/2026-09-06T00-00-00-mNow-6h-memory-summary.md, updated_at=2026-09-06T00:00:00Z, thread_id=None, observed Tengu-to-D14 continuation; historical state only)
- extensions/skysight/resources/2026-09-05T18-00-00-kLsR-6h-memory-summary.md (cwd=<project-root>, rollout_path=extensions/skysight/resources/2026-09-05T18-00-00-kLsR-6h-memory-summary.md, updated_at=2026-09-05T18:00:00Z, thread_id=None, observed Tengu/D11 shop/post-shop route; historical state only)
- extensions/skysight/resources/2026-09-05T06-00-00-nxRx-6h-memory-summary.md (cwd=<project-root>, rollout_path=extensions/skysight/resources/2026-09-05T06-00-00-nxRx-6h-memory-summary.md, updated_at=2026-09-05T06:00:00Z, thread_id=None, observed D4–D6 route, shop, Goo, and thief-recovery arc)
- extensions/skysight/resources/2026-09-05T00-00-00-PPvF-6h-memory-summary.md (cwd=<project-root>, rollout_path=extensions/skysight/resources/2026-09-05T00-00-00-PPvF-6h-memory-summary.md, updated_at=2026-09-05T00:00:00Z, thread_id=None, observed fresh-run setup through early D2; historical continuity only)

### keywords

- `用战士通关本地游戏`, `/tmp/spd-run-20260905/checkpoint.py`, `run-notes.md`, `caves-notes.md`, Chilling Crossbow, Wand of Frost, Timekeeper's Hourglass, Tengu, D11, D14, Shaman.java, numeric keypad

## Task 8: Observe the same Warrior/Gladiator run through D15, City/Dwarf King planning, and the floor-22 water-route encounter

### rollout_summary_files

- extensions/skysight/resources/2026-09-06T23-20-00-iKhd-10min-memory-summary.md (cwd=<project-root>, rollout_path=extensions/skysight/resources/2026-09-06T23-20-00-iKhd-10min-memory-summary.md, updated_at=2026-09-06T23:20:00Z, thread_id=None, observed water-ritual safe-key/Ripper encounter; stale chat-visible state only)
- extensions/skysight/resources/2026-09-06T12-00-00-GhLr-6h-memory-summary.md (cwd=<project-root>, rollout_path=extensions/skysight/resources/2026-09-06T12-00-00-GhLr-6h-memory-summary.md, updated_at=2026-09-06T12:00:00Z, thread_id=None, observed City/Dwarf King planning, Heroic Leap, floor-22 blood-nest route, and a Computer Use control failure; no final outcome captured)
- extensions/skysight/resources/2026-09-06T06-00-00-gzHL-6h-memory-summary.md (cwd=<project-root>, rollout_path=extensions/skysight/resources/2026-09-06T06-00-00-gzHL-6h-memory-summary.md, updated_at=2026-09-06T06:00:00Z, thread_id=None, observed D15 snapshot and later sparse direct-input continuation; no later checkpoint captured)

### keywords

- `resume-d15-notes.md`, `city19-notes.md`, `/tmp/spd-run-20260905/checkpoint.py --full`, DM300, Dwarf King, `英勇之跃`, `疾速戒指`, blood nest, `clickStep is not defined`, water ritual room, storm trap, Ripper Demon, Ring of Haste `+3`

## User preferences

- When live-playing a Warrior clear, the user’s standing boundary is “快速通关…不直接修改游戏数据” -> act as a read-only tactical coach; do not patch saves/source/settings, and optimize for stable standard-mode victory rather than speed or score. [Task 1]
- The user criticized movement that advanced too many turns and asked for lessons usable “以后新开聊天/新存档” -> checkpoint, inspect, and authorize only one next action; treat a new monster, room, HP/barrier/buff, item, trap, attack result, or phase boundary as an immediate stop/replan trigger. [Task 1][Task 2]
- When a Boss or notable enemy is about to be fought, the user asked that Codex “先查源码了解敌人行为，再规划” -> source-audit the exact current-version class/inheritance and fresh save before a conditional next-action plan. [Task 3]
- The user explicitly called out wasteful backtracking, safe on-route pickups, missed seeds/dew, capacity bags, and idle useful equipment -> make a forward resource plan and explicit shop/lab/magical-item audits instead of treating inventory/economy as housekeeping. [Task 2][Task 4]
- In the observed `用战士通关本地游戏` session, the user alternated Codex checkpoint/source analysis with direct numeric-key input -> retain the read-only, checkpoint-driven one-action workflow rather than treating the event chronology as sufficient live state. [Task 7] [skysight memory]

## Reusable knowledge

- A fresh persisted checkpoint requires minimizing the actual game window, matching writes of `game.dat` and current `depthN.dat`, and no `.spdtmp`; normal focus switching, Esc, movement, and attacks are not a proven save. Persisted state is a prerequisite for state-dependent advice, but remains distinct from unsaved UI/RNG. [Task 1]
- `extensions/ad_hoc/notes/20260824T193329+0800-spd-v338-readonly-inspector.md` is a valid Python read-only inspector, deliberately kept in the memory folder (not `/tmp`): it has `slots`, `status`, `map`, `entities`, one-next-key route commands, `--wait-new`, JSON output, and `selftest`. Its double-read/no-`.spdtmp` snapshot is evidence of stability, not mathematical proof that `game.dat` and `depthN.dat` came from one `saveAll` call; routes are static advisories and never enter transition rectangles or operate the game. [Task 1]
- Numpad is the primary movement path; arrows are a cardinal fallback; main-row `1..6` are quickslots. Screen/UI verifies focus, targeting, modals, boss telegraphs, and results, while the save is primary for hero/map/entities. Mouse is auxiliary for menus or explicitly calibrated targeting. [Task 1]
- Before every keyboard input, get fresh app state; if minimized, use `Raise`, read state again, and confirm both selected game and intended UI layer before sending exactly one key. A key API naming the app and a preceding successful key are not focus proof. After minimize/checkpoint, application switch, raise, or modal/UI-layer change, focus is invalid again; never spend a gameplay key as a focus probe. [Task 6]
- “One input” is not automatically one hero-time unit. Entering an adjacent ordinary heap with no visible enemy begins `HeroAction.PickUp`: move costs 1 and an automatic pickup can cost another 1. New visibility or any attack attempt, including a miss, can cancel phase 2. Treat heap entry as the separately authorized `ENTER_HEAP_MAY_CHAIN_AUTO_PICKUP` interaction; after it, compare hero cell, `duration`, Hunger, HP/shield/buffs, heap/inventory, and every mob cell/state/target. [Task 6]
- The revised inspector distinguishes known `WeakFloorRoom$WellID` landmark blobs (not hazardous/route-blocking) from unknown active Blob classes (conservatively route-blocked without asserting harm), exposes custom tiles/walls and respawner/statistics, and still cannot reconstruct actions, target attempts, hit/miss outcomes, or the last damage source from save snapshots. [Task 6]
- For non-neutral buffs, retain the route gate while the saved buff object exists: `Levitation.time=0` is not proof detachment or final landing effects have occurred. `PotionOfHealing` detaches Bleeding, but natural Bleeding is a stochastic decay rather than a fixed-turn countdown; forecast a range and checkpoint each turn when no removal resource is used. Level-up increases HP/HT by 5 and does not clear debuffs; report saved `BrokenSeal$WarriorShield.initial_shield` separately from source-derived Iron Will maximum shield. [Task 6]
- Preserve player-facing knowledge separately from serialized internals: an internally stored `PotionOfLevitation` can still be an unidentified “golden potion.” `Tab` changes only runtime visible-hostile target selection at 0 time; main-row `1` is two presses (0-time targeting, then a 1-time throw); `E` enters Examine at 0 then searches on its second press. Any teleport/Levitation landing must trigger a full destination audit because terrain, plants, traps, falling, or a newly hunting mob can resolve after relocation. [Task 6]
- Exact live/source anchors worth rechecking at v3.3.8: an Albino has 12 HP, 2 EXP, damage 1–4, and a positive-damage attack has a 50% chance to apply/refresh Bleeding strength 2–3; level-1 threshold is 10 EXP, so a level-1/EXP-8 save needs the Albino’s 2 EXP, not one ordinary Rat kill, to reach level 2. Talent-description selection is nonpersistent/0-time; only explicit “upgrade talent” writes `talents_tier_1.IRON_WILL = 1`. [Task 6]
- The 2026-09-05/06 chronological record captures a long Warrior/Gladiator arc from early floors through Tengu, D11 shop routing, and Caves floor 14. Chilling Crossbow was later +3; Wand of Frost, Fireblast, Haste, healing reserves, Thorns armor, Ring of Might, and Timekeeper's Hourglass were visible at different points. The observations do not establish current state or final outcome: start any continuation by reading a fresh checkpoint/save and current screen. [Task 7] [skysight memory]
- Later 2026-09-06 observations extend the same historical arc: a D15 snapshot recorded a level-11 Warrior at full health before DM300 planning; later Codex notes showed City/Dwarf King planning, Heroic Leap and Ring of Haste use near the floor-22 blood-nest route, and a water-ritual room where the left iron key was safe while the right key was on a storm trap. These are handoff clues only; the event stream did not establish a current save, final outcome, or complete combat history. Start from a fresh checkpoint and screen, not these notes. [Task 8] [skysight memory]
- A selected transition cell invokes persistent `HeroAction.LvlTransition`; merely crossing a non-transition Move path does not. Keep one cell outside every persisted transition rectangle, never remote-click stairs or resume a broken stair route, and after one intentional transition input wait, checkpoint/read the new floor before moving. [Task 2]
- High-coverage core clearing is the stable-clear default: collect guaranteed/high-value and safe on-route resources, perform at most one purposeful cleanup loop, audit keys/quests/shop/lab/region rewards before descent, and do not farm respawns, blindly search walls, retrample furrowed grass, or take dangerous long detours for remote marginal loot. [Task 2]
- Before unfamiliar/special enemy/Boss and every Boss threshold: begin with exact persisted `__className`, inspect concrete and behavior-bearing parent classes plus Buff/level/Ballistica/Actor rules, then separate source facts, live facts, random ranges, worst-case damage, priority/line-of-sight, reserves, next action, and stop conditions. Source mechanics never confirm future RNG or stale enemy state. [Task 3]
- Remedies have non-interchangeable scopes: Healing/Mageroyal cure nine statuses; Cleansing removes formal negative buffs (with exceptions) and gives five-turn prevention; Purity clears/protects harmful blobs but not an attached debuff. At a hazardous effect, verify the exact status, source cells/volume, action duration/range, and who acts next. [Task 3]
- At shops, sell obsolete ordinary melee weapon/armor after keeping at most one explicit near-term candidate; capacity bags usually precede speculative gear. Do not generalize this to thrown stacks (durability/ammunition, Liquid Metal) or duplicate wands (independent charge pools, tactical roles, possible Arcane Resin). [Task 4]
- Artifacts/rings normally require equipping; a true trinket is active while carried. For every active magical item assign slot state, curse/known state, charge/recharge, quickslot, proactive trigger, Boss reserve, and sell/brew/keep decision; near-cap active charges should create safe value rather than remain indefinitely idle. At each laboratory do an inventory-to-recipe audit, then quickslot the crafted result and define its threshold/reserve. [Task 4]
- Confirmed late-run postmortem: level-19 Gladiator with Scimitar +8, Scale Armor +7 Entanglement, Ring of Might +1 died to `DwarfKing$DKWarlock`; the decisive final-wave error was attacking an adjacent ghoul while a fresh warlock had clear ranged line. At King shielding 125, stop/prepare before crossing 100; unblocked warlock is the hard priority, then dangerous monk, linked ghouls, then King. Enter floor 20 with 3 healing minimum (4–5 preferred), food, movement/control, charged wands/Hourglass, and preferably blessed Ankh. [Task 1][Task 5]
- Related skill: skills/pixel-dungeon-run-assistance/SKILL.md. [Task 1][Task 2][Task 3][Task 4][Task 5]

## Failures and how to do differently

- Symptom: “one keyboard step” or a click unexpectedly changes floors. Cause: the selected target/adjacent destination was a `LevelTransition`, not ordinary Move; a larger rectangle, hero-cell click, queued action, or Resume can preserve/restart it. Fix: use a hard no-touch transition boundary and a single deliberate, audited transition input only. [Task 2]
- Symptom: old positions or a long route continue after a dynamic event. Cause: a precomputed route was mistaken for current state and intermediate states were not actually inspected. Fix: one action → screenshot/state inspection → recompute; attacks and waits are single actions too. [Task 1]
- Symptom: a supposedly single safe direction changes time/resources twice or leaves a heap unexpectedly uncollected. Cause: adjacent ordinary-heap entry can queue `HeroAction.PickUp` after its move; new visible enemy or any attack attempt can interrupt it. Fix: label it `ENTER_HEAP_MAY_CHAIN_AUTO_PICKUP`, approve it separately, check UI/combat-log evidence, and diff all specified hero/heap/mob fields afterward. [Task 6]
- Symptom: a key reaches the wrong application or UI layer. Cause: implicit activation, stale focus after minimization/switch/modal, or treating a key API call as selection proof. Fix: fresh app state → `Raise` if needed → fresh state → visible selected app/layer confirmation → one key → fresh readback; if selection cannot be established, report the blocker and send no key. [Task 6]
- Symptom: a Computer Use sequence fails with `clickStep is not defined` or stream disconnection near an active route. Cause: the controller/integration failed, so planned steps are not evidence that the game action occurred. Fix: stop automated progression, reacquire application focus and fresh screen/save state, then authorize at most one next action; never continue from the intended route or infer the missing result. [Task 8] [skysight memory]
- Symptom: a buff is called finished at `time=0`, a precise Bleeding end turn is promised, or an Iron Will shield result is inferred from `initial_shield`. Cause: saved scalar fields were mistaken for runtime detachment/dynamic maximum. Fix: require fresh absence of the buff for completion, forecast stochastic bleed with turn checkpoints, and separately label saved versus source-derived shield values. [Task 6]
- Symptom: a source conclusion or generic item rule produces a bad live decision. Cause: subclass/buff/version/current-state overrides were skipped, or generic sell/cleanse assumptions were applied. Fix: exact-class/source-plus-save audit; distinguish ordinary gear from wands/thrown stacks and Healing/Cleansing/Purity before acting. [Task 3][Task 4]
- Symptom: a run runs out of resources despite good equipment. Cause: capacity, shops, alchemy, active artifacts, food, and boss reserves were deferred until tactical danger. Fix: use mandatory shop/lab/pickup audits; budget hunger and detours; reserve key consumables before the boss, not after entering lethal state. [Task 2][Task 4][Task 5]

# Task Group: Shattered Pixel Dungeon on macOS / shared save-data path and non-destructive gameplay support

scope: Explain why source/debug/release and the downloaded macOS app can share Shattered Pixel Dungeon user data; use before game-run assistance, and keep save data unmodified.
applies_to: cwd=<project-root> and macOS user data; reuse_rule=the verified path is tied to the desktop title `Shattered Pixel Dungeon` and the observed v3.3.8 packaging. Recheck the launcher title and current filesystem before altering, backing up, or relying on data; deletion of `source-code/` was reported on 2026-08-21 but a 2026-08-22 observation showed it again, so do not assume its presence or absence.

## Task 1: Trace the shared macOS save/settings directory used by local builds and the downloaded app

### rollout_summary_files

- rollout_summaries/2026-08-19T16-12-45-kWWo-shattered_pixel_dungeon_desktop_build_data_path_and_computer.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/20/rollout-2026-08-20T00-12-45-01a01acb-e78f-7271-8750-1a3d82a599ac.jsonl, updated_at=2026-08-21T13:04:51+00:00, thread_id=01a01acb-e78f-7271-8750-1a3d82a599ac, source/package/filesystem verification; former checkout must be rechecked)

### keywords

- Shattered Pixel Dungeon, DesktopLauncher.java, FileUtils.java, GameSettings.java, Application Support, settings.xml, rankings.dat, game1/game.dat, `~/Library/Application Support/Shattered Pixel Dungeon/`, `com.shatteredpixel.shatteredpixeldungeon.apple`, source-code, non-destructive

## Task 2: Prepare Warrior gameplay assistance while explicitly avoiding direct game-data modification

### rollout_summary_files

- rollout_summaries/2026-08-19T16-12-45-kWWo-shattered_pixel_dungeon_desktop_build_data_path_and_computer.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/20/rollout-2026-08-20T00-12-45-01a01acb-e78f-7271-8750-1a3d82a599ac.jsonl, updated_at=2026-08-21T13:04:51+00:00, thread_id=01a01acb-e78f-7271-8750-1a3d82a599ac, explicit no-game-data-modification boundary and macOS support context)

### keywords

- Warrior, quickly complete, single-player game, 不直接修改游戏数据, Shattered Pixel Dungeon

## User preferences

- For the active Warrior-run request, the user asked for help “快速通关…不直接修改游戏数据” -> provide decision/gameplay assistance and observational tooling, but do not edit saves, settings, or game data. [Task 2] [skysight memory]

## Reusable knowledge

- The local `desktop:debug`, release build, and downloaded `.app` shared `~/Library/Application Support/Shattered Pixel Dungeon/` in the observed v3.3.8 setup: `DesktopLauncher.java:158-173` derives the macOS user-data folder from the shared application title `Shattered Pixel Dungeon`, not from the build directory or downloaded app Bundle ID `com.shatteredpixel.shatteredpixeldungeon.apple`. Observed files included `settings.xml`, `rankings.dat`, `game1/game.dat`, `badges.dat`, `journal.dat`, and `bones.dat`. [Task 1] [skysight memory]
- Moving the downloaded app, deleting `desktop/build/`, switching `desktop:debug`/`desktop:release`, or downloading the same desktop version does not itself create a separate save set; the app was not observed in `~/Library/Containers/com.shatteredpixel.shatteredpixeldungeon.apple/`. Treat the shared directory as user data and inspect metadata/paths without opening or modifying save contents unless separately authorized. [Task 1] [skysight memory]

## Failures and how to do differently

- Symptom: a local build appears to “read the downloaded app’s data,” leading to an incorrect search inside `build/` or a Bundle-ID container. Cause: desktop storage is title-derived external user data. Fix: trace `DesktopLauncher`/file-backend logic, compare the packaged manifest title, and inspect the Application Support directory metadata before drawing conclusions; never inspect or change save contents merely to prove the path. [Task 1] [skysight memory]

# Task Group: <project-root> / user-fork setup, Java `spdctl` CLI-control design, and phase-commit policy

scope: Prepare the user’s fork in the project root, then resume design or implementation of a machine-controllable CLI/control layer that launches the desktop GUI in the same Java JVM; apply the project-specific Git/changelog policy to verified CLI phases.
applies_to: cwd=<project-root>; reuse_rule=the clone/remote facts were verified at the recorded checkout state; the Java/LibGDX pipe-and-render-thread design is an observed direction, not an implemented control protocol. Recheck the current fork, Gradle/JDK setup, game version, and live interaction boundary before coding or sending commands.

## Task 1: Clone the user fork into the current project root

### rollout_summary_files

- rollout_summaries/2026-08-23T07-36-55-LJMM-spd_fork_clone_cli_changelog_policy_and_control_design.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/28/rollout-2026-08-28T17-32-49-01a02d8d-13ff-7400-a308-6435ab8a972c_01a047b6-fd40-78e0-8b57-d44770b2d48e.jsonl, updated_at=2026-08-28T10:09:52+00:00, thread_id=01a02d8d-13ff-7400-a308-6435ab8a972c, successful user-fork clone and connectivity verification)

### keywords

- `git clone --origin origin`, `shengyifan/shattered-pixel-dungeon`, `origin/master`, `7b8b845a76fe76c6b7c031ae9e570852411f56db`, `git fsck --no-progress --connectivity-only`, `.DS_Store`

## Task 2: Design same-JVM `spdctl` machine control with GUI and process pipes

### rollout_summary_files

- rollout_summaries/2026-08-23T07-36-55-LJMM-spd_fork_clone_cli_changelog_policy_and_control_design.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/28/rollout-2026-08-28T17-32-49-01a02d8d-13ff-7400-a308-6435ab8a972c_01a047b6-fd40-78e0-8b57-d44770b2d48e.jsonl, updated_at=2026-08-28T10:09:52+00:00, thread_id=01a02d8d-13ff-7400-a308-6435ab8a972c, design discussion only; no control source was implemented)

### keywords

- `spdctl`, `spdctl run --machine`, Java 11, JDK 17, LibGDX, `JsonReader`, `JsonWriter`, stdin, stdout, stderr, `Game.runOnRenderThread(...)`, DTO, same JVM, render thread

## Task 3: Apply the Shattered Pixel Dungeon CLI stage-commit and Chinese changelog convention

### rollout_summary_files

- extensions/ad_hoc/notes/20260828T174325+0800-spd-cli-git-changelog-policy.md (cwd=<project-root>, rollout_path=extensions/ad_hoc/notes/20260828T174325+0800-spd-cli-git-changelog-policy.md, updated_at=2026-08-28T17:43:25+08:00, thread_id=None, authoritative project-specific CLI commit/changelog note)
- rollout_summaries/2026-08-23T07-36-55-LJMM-spd_fork_clone_cli_changelog_policy_and_control_design.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/28/rollout-2026-08-28T17-32-49-01a02d8d-13ff-7400-a308-6435ab8a972c_01a047b6-fd40-78e0-8b57-d44770b2d48e.jsonl, updated_at=2026-08-28T10:09:52+00:00, thread_id=01a02d8d-13ff-7400-a308-6435ab8a972c, initial policy commit verified)

### keywords

- `CHANGELOG.md`, `CLI.0.0.0`, `CLI.主版本.次版本.修订版本`, `4a272100bdc07c01affb19b538c02d1063441701`, `git diff --cached --check`, 中文 commit, 阶段提交, `shengyifan/shattered-pixel-dungeon`

## User preferences

- When repository setup is requested, the user said “帮我直接clone下来当项目文件夹，接下来的改动在我fork的项目上进行” and corrected a remote-only check with “你先帮我clone 我的fork下来呀” -> confirm the target directory is safe, then perform the actual clone into the current root before design discussion. [Task 1]
- For future CLI instrumentation in this fork, the user’s recorded policy is: each “可验收、已验证的开发阶段” gets an independent Git commit; update root `CHANGELOG.md` first; use `CLI.主版本.次版本.修订版本` starting at `CLI.0.0.0`; write changelog, commit title, and commit body in Chinese; and push only when explicitly asked. [Task 3] [ad-hoc note]

## Reusable knowledge

- The fork was cloned directly into the cwd with `origin` fetch/push at `https://github.com/shengyifan/shattered-pixel-dungeon.git`, branch `master` tracking `origin/master`; at clone time HEAD was `7b8b845a76fe76c6b7c031ae9e570852411f56db`, the worktree was clean, and `git fsck --no-progress --connectivity-only` passed. [Task 1]
- The observed selected direction is Java core with Java 11 source compatibility, JDK 17 runtime, LibGDX JSON plus dedicated DTOs, and a same-JVM long-lived `spdctl run --machine`: stdin NDJSON request, stdout NDJSON response, stderr logs, with game work dispatched through `Game.runOnRenderThread(...)`. This is a design proposal, not proof of an available binary/API. [Task 2]
- Before every stage commit, inspect worktree and staged scope and run `git diff --cached --check`; the Chinese body must record implementation, verification, compatibility impact, and unfinished/out-of-scope items. The recorded initialization commit `4a272100bdc07c01affb19b538c02d1063441701` added only `CHANGELOG.md` at `CLI.0.0.0`. [Task 3] [ad-hoc note]

## Failures and how to do differently

- Symptom: only `git ls-remote` is run after the user asks to clone. Cause: remote validation was mistaken for the requested repository preparation. Fix: after a safe target-directory check, perform the clone and verify remote, branch, HEAD, status, and object connectivity. [Task 1]
- Symptom: same-process GUI/control design is treated as a reason to use a socket, HTTP server, or debugger by default. Cause: conflating in-process game coordination with operating-system process transport. Fix: keep the proposed stdin/stdout/stderr contract and render-thread handoff distinct; assess new transports only when the pipe design cannot meet a concrete requirement. [Task 2]
- Symptom: a large CLI change is committed as one undifferentiated patch or pushed with the local phase commit. Cause: the project-specific stage policy was skipped. Fix: cut a verified phase, update Chinese `CHANGELOG.md`, audit staged scope, make the Chinese commit, and treat push as separate explicit authorization. [Task 3] [ad-hoc note]

# Task Group: <project-root> / macOS Gradle desktop build and Computer Use boundary

scope: Run/package the local Shattered Pixel Dungeon desktop build on Apple Silicon, trace its macOS shared-data behavior, and assess raw-Java versus packaged-app Computer Use control.
applies_to: cwd=<project-root>; reuse_rule=repository task definitions, installed JDK/jpackage architecture, bundle behavior, data path, and Computer Use app discovery are checkout- and version-specific. Recheck the current checkout/toolchain before building; never alter `~/Library/Application Support/Shattered Pixel Dungeon/` without explicit authorization.

## Task 1: Audit, build, and run desktop debug/release paths

### rollout_summary_files

- rollout_summaries/2026-08-19T16-12-45-kWWo-shattered_pixel_dungeon_desktop_build_data_path_and_computer.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/20/rollout-2026-08-20T00-12-45-01a01acb-e78f-7271-8750-1a3d82a599ac.jsonl, updated_at=2026-08-21T13:04:51+00:00, thread_id=01a01acb-e78f-7271-8750-1a3d82a599ac, success; desktop build, task graph, raw-window boundary, shared-data path, and deletion boundary)

### keywords

- shattered-pixel-dungeon, source-code, gradlew, desktop:debug, desktop:release, Gradle-9.4.0, Temurin-25, mise, DesktopLauncher, `-XstartOnFirstThread`, ignoreExitValue, LWJGL, jpackageImage, `Invalid app`, Application Support, shared saves

## Task 2: Package an ARM64 macOS `.app` and verify reversible Computer Use control

### rollout_summary_files

- rollout_summaries/2026-09-05T00-58-27-efVf-shattered_pixel_dungeon_arm64_app_packaging_and_computer_use.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/09/05/rollout-2026-09-05T08-58-27-01a06f12-f286-7122-96ef-cc519a54eb99.jsonl, updated_at=2026-09-05T01:15:24+00:00, thread_id=01a06f12-f286-7122-96ef-cc519a54eb99, successful local ARM64 app image and menu-only Computer Use test)

### keywords

- `jpackage --type app-image`, `app-macos-arm64`, `Shattered Pixel Dungeon.app`, Temurin-25.0.4, ARM64, `com.shatteredpixel.shatteredpixeldungeon.apple`, `codesign --verify --deep --strict`, `Invalid app`, `getScreenshot`, `click([612, 548])`, `pressKey("Escape")`

## User preferences

- When preparing the environment, the user said “告诉我，我会自己去安装” -> audit and report missing tools before installing anything. [Task 1]
- When they clarified “我只需要在本机上运行”, use the smallest desktop path and do not front-load Android Studio, Android SDK, emulator, or global Gradle. [Task 1]
- When asking what `desktop:release` and `desktop:debug` “具体做了什么” and whether debug depends on release, explain from `desktop/build.gradle`, actual task graphs, inputs/outputs, and artifacts rather than generic Gradle descriptions. [Task 1]
- On the Computer Use question, preserve the distinction the user raised: screenshot/coordinate control can work in principle, but standard interaction first needs a valid app target. Keep capability probes read-only unless the user asks to operate the game. [Task 1]
- When the user says “告诉我步骤，不要自己打包”, provide configuration audit and steps only; execute build/package/UI tests only after separate explicit authorization, such as “帮我用你的方法打包并测试你能否操作游戏”. [Task 2]
- When macOS architecture matters, prefer the installed Apple-Silicon JDK/jpackage after checking the repository packaging config rather than blindly accepting an x64 downloaded runtime. [Task 2]

## Reusable knowledge

- Desktop quick setup uses the executable wrapper, not a global Gradle: `./gradlew desktop:debug`; `gradle-wrapper.properties` pins Gradle 9.4.0. For desktop-only work, JDK is required; Android Studio/SDK, adb, emulator, NDK, CMake, Node.js, and global Gradle are not. [Task 1]
- Mise-managed Temurin 25.0.4 LTS ARM64 worked with this wrapper even though `/usr/libexec/java_home -V` did not list it. Trust shell `JAVA_HOME`, `java -version`, and `./gradlew --version`; try project-documented Temurin 21 only if the observed JDK-25 native-access warnings turn into actual instability. [Task 1]
- `desktop:release` is a custom fat-JAR task; it does not launch, sign, publish, or build Android. `desktop:debug` is a `JavaExec` task using the runtime classpath and `DesktopLauncher`; it does not read the release JAR. Confirm this with `./gradlew desktop:debug --dry-run`, which must not list `:desktop:release`. [Task 1]
- `desktop:debug` uses `-XstartOnFirstThread` on macOS. `BUILD SUCCESSFUL` alone is insufficient because the task has `ignoreExitValue = true`; verify live JVM/process/window/log evidence. Direct release-JAR launch also needs `java -XstartOnFirstThread -jar desktop/build/libs/desktop-3.3.8.jar`. [Task 1]
- Standard `@oai/sky` screenshot/coordinate fallback still requires a valid `app`; the raw Java process returned `Invalid app` and was omitted from app discovery. The former untested package hypothesis is now verified for a local ARM64 `.app`, not for the repository's x64 `jpackageImage` route. [Task 1][Task 2]
- The shared data directory is `~/Library/Application Support/Shattered Pixel Dungeon/`: both debug properties and the downloaded JAR Manifest had `Specification-Title: Shattered Pixel Dungeon`, and the desktop launcher constructs macOS `External` storage from that title. The downloaded app's Bundle ID is not used and it was not sandboxed. Deleting project/app files does not establish that this user-data directory was deleted. [Task 1]
- The verified local ARM64 route is `./gradlew desktop:release --console=plain`, then local Temurin 25.0.4 `jpackage --type app-image` using the release JAR, `DesktopLauncher`, `mac.icns`, the repository runtime modules, `-XstartOnFirstThread`, and `--enable-native-access=ALL-UNNAMED`. It produced `desktop/build/app-macos-arm64/Shattered Pixel Dungeon.app`; `plutil -lint`, arm64 launcher/embedded-JVM inspection, and `codesign --verify --deep --strict` passed. Its ad-hoc signature/no Team ID is suitable for local testing, not distribution signing/notarization. [Task 2]
- The project `jpackageImage` macOS configuration downloads x64 Temurin 17.0.17+10. For Apple-Silicon local testing, using the local ARM64 jpackage avoided a Rosetta variable; do not claim the repository route is ARM64 without rechecking. [Task 2]
- Full-path app lookup of the packaged `.app` succeeded where raw Java lookup failed. Screenshot, coordinate click to About, fresh screenshot/state, Escape, and fresh main-menu screenshot verified app resolution, screenshot, coordinate click, and key input. Game controls were still absent from the semantic AX tree, so gameplay should use fresh screenshots/coordinates and the existing one-action protocol. [Task 2]

## Failures and how to do differently

- Symptom: desktop work is sent toward Android tooling before Gradle starts. Cause: requirements are inferred rather than checking the desktop docs/wrapper. Fix: first run `java -version` and `./gradlew --version`; the original blocker was `Unable to locate a Java Runtime.` [Task 1]
- Symptom: release is treated as a prerequisite for debug. Cause: shared compile/resource prerequisites are confused with a direct task dependency. Fix: use `--dry-run` and state the separate final outputs. [Task 1]
- Symptom: “OpenGL has no AX controls” becomes “Computer Use cannot operate it.” Cause: semantic accessibility and app-resolution/screenshot-coordinate capability are conflated. Fix: report the exact stage: the current raw JVM has no valid app target, so screenshot capture never starts. [Task 1]
- Symptom: `./gradlew` fails at `~/.gradle/wrapper/...gradle-9.4.0-bin.zip.lck` with `Operation not permitted`. Cause: Gradle-cache access, not a project source failure. Fix: obtain the required authorized cache access and rerun; do not alter the wrapper/JDK configuration as a first response. [Task 2]
- Symptom: the repository's default app-image route is treated as natively ARM64, or a successful ad-hoc local image is represented as shippable. Cause: its macOS branch downloads x64 Temurin 17, while local jpackage validation only proved a local ARM64/ad-hoc bundle. Fix: inspect launcher/runtime architecture and signature; separately perform Developer ID signing/notarization and target-machine testing for distribution. [Task 2]
- Symptom: stale coordinates/AX indexes are used after a game action. Cause: game internals are not semantic AX controls. Fix: capture fresh state/screenshot after every action and use the one-action control loop. [Task 2]

# Task Group: <project-root> / v3.3.8 source audit, classes, Bosses, and game-mode variants

scope: Route detailed, source-grounded Shattered Pixel Dungeon v3.3.8 gameplay questions, including class routes, combat/resource mechanics, Boss rules, Chilling Crossbow/Dart behavior, and the distinction between seeds, challenges, random selection, and ascension.
applies_to: cwd=<project-root> and historical `source-code`; reuse_rule=the statements are pinned to checkout `7b8b845a76fe76c6b7c031ae9e570852411f56db` / v3.3.8 and are not a live balance ranking. `source-code` was later absent and the project root reported `4a272100b`; check current HEAD, status, source, mode, and objective before applying numerical or live strategy advice.

## Task 1: Clone the specified repository into `source-code`

### rollout_summary_files

- rollout_summaries/2026-08-18T13-33-15-xy5W-shattered_pixel_dungeon_v338_source_analysis_modes_warrior_c.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/18/rollout-2026-08-18T21-33-15-01a01513-8531-7e13-a6e0-668c713609f0.jsonl, updated_at=2026-09-05T23:11:28+00:00, thread_id=01a01513-8531-7e13-a6e0-668c713609f0, successful historical clean clone; path later changed)

### keywords

- shattered-pixel-dungeon, source-code, git clone, 00-Evan, master, origin/master, 7b8b845a, v3.3.8

## Task 2: Analyze v3.3.8 mechanics, six classes, Bosses, and stable class routes from source

### rollout_summary_files

- rollout_summaries/2026-08-18T13-33-15-xy5W-shattered_pixel_dungeon_v338_source_analysis_modes_warrior_c.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/18/rollout-2026-08-18T21-33-15-01a01513-8531-7e13-a6e0-668c713609f0.jsonl, updated_at=2026-09-05T23:11:28+00:00, thread_id=01a01513-8531-7e13-a6e0-668c713609f0, read-only v3.3.8 source audit; later-root caveat added)

### keywords

- HeroClass, HeroSubClass, Talent, Actor, Char, Dungeon, RegularLevel, SpiritBow, Goo, Tengu, DM-300, Dwarf King, Yog-Dzewa, Heroic Leap, Elemental Blast, Smoke Bomb

## Task 3: Explain modes beyond standard mode without conflating entry variants and modifiers

### rollout_summary_files

- rollout_summaries/2026-08-18T13-33-15-xy5W-shattered_pixel_dungeon_v338_source_analysis_modes_warrior_c.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/18/rollout-2026-08-18T21-33-15-01a01513-8531-7e13-a6e0-668c713609f0.jsonl, updated_at=2026-09-05T23:11:28+00:00, thread_id=01a01513-8531-7e13-a6e0-668c713609f0, source-verified game-mode taxonomy)

### keywords

- customSeedText, Dungeon.daily, dailyReplay, Challenges, HeroSelectScene, AscensionChallenge, Amulet, daily challenge, custom seed, 1.25^挑战数量, 禁忌咒文

## Task 4: Give Warrior Chilling Crossbow guidance from source

### rollout_summary_files

- rollout_summaries/2026-08-18T13-33-15-xy5W-shattered_pixel_dungeon_v338_source_analysis_modes_warrior_c.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/18/rollout-2026-08-18T21-33-15-01a01513-8531-7e13-a6e0-668c713609f0.jsonl, updated_at=2026-09-05T23:11:28+00:00, thread_id=01a01513-8531-7e13-a6e0-668c713609f0, historical v3.3.8 mechanics; current checkout must be rechecked)

### keywords

- Crossbow.java, Dart.java, TippedDart.java, Chilling.java, Chill.java, Combo.java, MagicalInfusion, `手起刀落`, `不动如山`, Heroic Leap, frost crossbow, normal Dart

## Task 5: Distinguish ordinary-Dart and tipped-Dart durability

### rollout_summary_files

- rollout_summaries/2026-08-18T13-33-15-xy5W-shattered_pixel_dungeon_v338_source_analysis_modes_warrior_c.md (cwd=<project-root>, rollout_path=~/.codex/sessions/2026/08/18/rollout-2026-08-18T21-33-15-01a01513-8531-7e13-a6e0-668c713609f0.jsonl, updated_at=2026-09-05T23:11:28+00:00, thread_id=01a01513-8531-7e13-a6e0-668c713609f0, historical v3.3.8 mechanism boundary)

### keywords

- Dart.baseUses=1000, MissileWeapon.durabilityPerUse, TippedDart.baseUses=1, PinCushion, `飞镖有耐久吗`, ordinary Dart, tipped Dart

## User preferences

- When requesting “详细分析源码，最后给出各个职业的最佳行动方案指导”, the user wants evidence-first detail followed by executable per-class action loops, not generic gameplay advice. [Task 2]
- For “最佳”, set the optimization target first: this analysis used standard mode, no challenges, and stable win rate rather than speedrun or score. Keep source facts separate from strategy inference and random-drop contingencies. [Task 2]
- When asking “游戏除了标准模式还有什么模式？”, classify the actual source dimensions—entry/seed source, challenge modifiers, random selection, and post-amulet ascension—instead of presenting them as mutually exclusive named modes. [Task 3]
- For a concrete equipment question such as “战士使用寒霜十字弩的建议？” or “飞镖有耐久吗？”, explain the source linkage and boundary conditions, then give a short conditional combat conclusion rather than a generic weapon description. [Task 4][Task 5]

## Reusable knowledge

- Clone target was `<project-root>/source-code`; it tracked `origin/master` at clean commit `7b8b845a76fe76c6b7c031ae9e570852411f56db` (`v3.3.8: updated version for amended v3.3.8 release`). Check a pre-created target is empty before cloning. [Task 1]
- `core` owns game logic; `Actor` implements the continuous time scheduler and `Char` the hit/damage/shield pipeline. Hit rolls compare separate uniform accuracy/evasion rolls; attacking from a mob's unseen side is an infinite-accuracy surprise attack, so doors, corners, grass, and broken line of sight are deterministic combat tools. [Task 2]
- A standard run guarantees per five-floor chapter 2 Strength potions, 3 Upgrade Scrolls, and 1 Arcane Stylus; full-run baseline is 10 / 15 / 5 plus ordinary-floor food. Approximate T1–T5 Strength requirements are 10/12/14/16/18; long-term two-point under-strength is usually a trap because it penalizes accuracy/evasion and action time. [Task 2]
- Boss hard rules outrank damage: force Goo to move during charge; dodge Tengu's bomb/fire/electric phase; during DM-300 overload hit active pylons, not invulnerable Boss; kill Dwarf King minions in his invulnerable phase; at Yog lock thresholds stop attacking and pull fists at least five cells away. [Task 2]
- The source-derived stable routes are Warrior→Gladiator + Heroic Leap, Mage→Warlock + Elemental Blast, Rogue→Assassin + Smoke Bomb, Huntress→Warden + Nature's Power, Duelist→Champion + Challenge, Cleric→Paladin + Ascended Form. They are recommendations, not official rankings. [Task 2]
- Actual game-entry variants are ordinary random seed, custom seed, and daily challenge. Challenges are nine composable modifiers (score multiplier approximately `1.25^count`); Random only chooses hero/challenge setup; carrying Yendor's Amulet back is the same run's ascension phase, not a new-game menu mode. [Task 3]
- In v3.3.8, a Crossbow strengthens ordinary darts and lets them inherit its enchantment. A Chilling Crossbow therefore supports a Gladiator loop of safe normal-Dart opener, slowed approach, then crossbow melee/Combo or Heroic Leap reset; Champion-only `Charged Shot` is not available to Warrior. Do not equate a Chill proc with immediate freeze or guaranteed loss of the enemy's next action. [Task 4]
- Preserve a valuable Chilling enchantment: ordinary upgrades from `+4→+5` onward risk losing enchantment; `MagicalInfusion` costs one Upgrade Scroll plus 12 alchemical energy and upgrades while retaining enchantment/glyph/curse. Crossbow speed/damage augmentation changes crossbow melee, not Dart shots. [Task 4]
- Ordinary `Dart` uses are effectively unlimited in v3.3.8 (`baseUses=1000` and durability calculation returns zero at 100+); tipped darts have finite coating (`baseUses=1`) and yield/revert to ordinary darts on exhaustion. Ring/talent/holster/Lotus effects can alter tipped-Dart durability, so do not say every coating is absolutely one-hit. [Task 5]

## Failures and how to do differently

- Symptom: a guessed source path blocks analysis. Cause: `SpiritBow.java` was first sought under `items/weapon/missiles`; it is at `items/weapon/SpiritBow.java`. Fix: use `rg --files core/src/main/java | rg 'SpiritBow'` before reading a guessed path. [Task 2]
- Symptom: advice becomes an asserted in-game ranking, or modifiers are called standalone modes. Cause: source facts, inference, and UI taxonomy were flattened together. Fix: cite the source category, state the mode/goal assumption, and label recommendations as deductions. [Task 2][Task 3]
- Symptom: source commands fail at historical `source-code`, or old mechanics numbers are used for a live run. Cause: the checkout later moved to the project root (`4a272100b` was observed) and mechanics can change. Fix: begin with `pwd && ls -la && git rev-parse --short HEAD && git status --short`; use `rg --files` for actual source locations and re-evaluate current item implementation. [Task 1][Task 4][Task 5]
