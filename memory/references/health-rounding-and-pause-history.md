> **Historical source copy; not live instructions.** Imported 2026-10-08. Historical CLI.8.0.0 repair and floor-22 pause record, ending before the eventual win. Coordinates, handles, resources and temporary paths belong to that run. This does not authorize resumption, profile access or another repair.
>
> Current [project agreements](../../AGENTS.md), [protocol manual](../../docs/cli-help.md) and user instructions take precedence. Never read personal saves/private audit or use GUI input based on this record. External paths below are historical citations, not dependencies. See the [import record](../IMPORTS.md).

<!-- BEGIN IMPORTED SOURCE -->
thread_id: 01a0e0b8-7b50-7020-a108-279c9e5cdedc
updated_at: 2026-10-06T08:33:53+00:00
rollout_path: /Users/shengyifan/.codex/sessions/2026/09/27/rollout-2026-09-27T10-36-21-01a0e0b8-7b50-7020-a108-279c9e5cdedc.jsonl
cwd: /Users/shengyifan/Workspace/shattered-pixel-dungeon
git_branch: feature/mac-cli

# Continued a read-only, spdctl-only Warrior run, repaired the external Python decoder, resumed successfully through the Dwarf King, and paused safely on floor 22

Rollout context: Working directory `/Users/shengyifan/Workspace/shattered-pixel-dungeon`. The user required all gameplay operations to use the packaged `spdctl` controller, permitted source inspection, prohibited direct save-file reads, and required stopping on an unrecoverable anomaly or game end. The user later explicitly asked to pause without exiting the game. The rollout used the existing packaged ARM64 controller session and a single serialized relay for gameplay intents.

## Task 1: Launch and inspect the packaged controller

Outcome: success

Key steps:

- Read `docs/cli-help.md` before operation and confirmed the recommended command is the packaged controller with flat protocol-8 intents.
- Verified the packaged executable with `--version`: `CLI.8.0.0 (protocol 8, game 3.3.8)`.
- Launched the requested ARM64 executable through:
  `./desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl control --machine`
- Confirmed the live handshake and title state: protocol 8, audit schema 11, Chinese windowed UI, `menu_ready`.
- Created/used a single relay around the existing controller so every intent was validated against the current frame and every long action was settled through the exact original `rid`.

Reusable knowledge:

- `spdctl control --machine` is the bundled controller and does not require Python or a network endpoint. It owns one machine child and emits controller wrappers around opaque protocol responses.
- Keep stdout and stderr separate. Controller diagnostics such as LWJGL `sun.misc.Unsafe::objectFieldOffset` warnings are not game responses or evidence of failure.
- The controller validates outer protocol structure, request IDs, current scope/revision bindings, pending-action bookkeeping, and `settle`/cancel lifecycle, but does not recursively validate semantic fields such as `health_estimate`.

## Task 2: Start a new Warrior game and progress through the early dungeon

Outcome: success / partial overall gameplay progress

Preference signals:

- The user explicitly said gameplay must use only `spdctl`, allowed source reading, prohibited direct save reads, and required stopping on anomalies or game end -> future gameplay should preserve this strict protocol-only/read-only boundary.
- The user’s requested workflow and the rollout’s repeated commentary followed a one-action/reobserve/settle loop rather than batched actions -> continue using one current advertised intent at a time, especially around combat, transitions, doors, traps, and prompts.

Key verified progress:

- Started a fresh Warrior save entirely through current advertised controls.
- Entered floor 1 as a level-1 Warrior at 20/20 HP with the initial shortsword, cloth armor with Broken Seal, throwing stones, food, waterskin and velvet pouch.
- Used source-grounded tactics: doors/terrain for surprise attacks, cautious movement, no blind use of unidentified potions, and explicit re-observation after combat.
- Collected early resources including healing potion, food, keys, mirror-image scroll, Strength potion, upgrade scroll, fear scroll, seeds, gold and a Ring of Evasion +1.
- Used the green fire-room’s Frost potion safely; later identified `NAUDIZ` as an upgrade scroll and upgraded the sealed cloth armor to +1.
- Reached level 2, then level 3, and invested in Veteran’s Intuition to 2 points. Strength reached 11. The upgraded armor and ring were kept equipped; the unidentified artifact from the crystal vault was initially kept unequipped.
- A `STALE_STATE` occurred during an attempted floor transition (`t1.199`). The action was not replayed. Its result was settled as `ACTION_REJECTED`, then a fresh state was obtained and the transition was resent only with the new current revision. The transition subsequently succeeded with save receipts.
- Reached floor 2, explored it conservatively, handled rats/snakes/gnoll scouts using door/terrain tactics, opened the iron-key door, identified/used resources, and reached level 3.
- Entered floor 3 after a successful transition and save receipts. The run later continued far beyond the early-game handoff.

Reusable knowledge:

- A `STALE_STATE` response means the submitted revision is no longer current; it is not permission to replay. Settle the exact original action, obtain a fresh current state, and make a new decision with the new revision.
- Long `cell` travel can return `in_progress`/`continuous_activity`; settle the original `rid`, preserve the outcome separately from the fresh observation, and do not use a query result as proof that the original action succeeded.
- The current frame’s map, entities, UI nodes, action descriptors, locators and revisions are authoritative only for that frame. Do not reuse old control IDs, map positions or planned routes after combat or UI transitions.
- Hungry still permits natural regeneration; Starving disables it. Avoid long rests merely to recover when a safer route or food is available.

## Task 3: Diagnose and repair the external Python decoder’s health-bar validation

Outcome: success

Preference signals:

- When the health-bar anomaly occurred, the user required stopping gameplay, then specifically requested: “先只修改外部 Python 模块” and “不要退出游戏” -> isolate the external decoder change, preserve the live game/controller, and do not modify Java/game-side code or restart.
- The user asked whether the current `spdctl` could continue or whether it needed repair -> distinguish the packaged Java controller from the optional Python semantic decoder before choosing a recovery path.

Failure and diagnosis:

- At the Dwarf King transition, the Python decoder rejected a public response with:
  `$.data.entities[0].health_estimate.samples[0] has inconsistent displayed segments`
- The failing public sample was `{"total":21,"filled":4,"with_shield":22,"basis":"displayed"}`.
- Source inspection showed the producer’s health-bar foreground uses `ceil` while the exported background width uses `round`; a fractional displayed width can therefore produce a one-segment foreground overhang. The bundled Java controller itself only validates outer JSON/protocol structure and does not depend on Python.
- The original action `t3.1258` was independently settled through the same controller as `COMPLETED`; it was never replayed. The raw failed response, receipt and stop metadata were preserved under `/tmp/spd-warrior-20261005-relay/`.

Implemented change:

- Modified only `desktop-control/client/spdctl_client.py`.
- Changed `_health_estimate()` to allow `with_shield <= total + 1` while retaining strict integer, positive-total, `filled <= with_shield`, and `basis == "displayed"` checks.
- Preserved raw values exactly; no clamping, rewriting, or inference of exact health was introduced.
- Updated the old semantic regression sample in `desktop-control/src/test/python/test_combat_visual_wire.py` so its intentionally invalid case is more than the permitted one-segment overhang.
- Added `desktop-control/src/test/python/test_health_estimate_rounding.py` covering accepted one-segment cases, rejection of larger/invalid/order-reversed values, entity definitions, nested before/after snapshots, standalone `subject_data`, controller outcome/current-frame identity separation, raw immutability, and error identity preservation.

Validation:

- Focused tests passed: `test_health_estimate_rounding.py` (6 tests) and `test_combat_visual_wire.py` (11 tests).
- Full Python suite passed: `Ran 292 tests ... OK`; only unrelated SQLite `ResourceWarning` messages appeared.
- Offline replay of the original failed response succeeded with zero problems; `21/4/22` was preserved and the capture SHA-256 remained `7a4a70d88fee87ef6576ff167e4780eb2c9670d083775d21f460c4d907179f21`.
- A live, read-only `state` query through the still-open controller succeeded as `t3.1261 / s2 / r38e`, with the live Warrior still on floor 20 at 112/115 and the same `21/4/22` sample accepted. No save, quit, restart, zoom change, or gameplay action was sent during this verification.
- Created verified local signed commit `cf5b4f701 fix(cli): tolerate one-pixel health bar rounding in Python client`; `git verify-commit HEAD` reported a good signature. No push/tag/release was performed.

Important limitation:

- This is a narrow external-client compatibility tolerance, not a fix to the game-side producer. The game process did not need to restart for this Python-only change. A future Java/HealthBar producer fix would require rebuilding/repackaging and restarting the game/controller child; the current user-approved change intentionally did not do that.

## Task 4: Resume gameplay after the Python-only repair

Outcome: success, then paused by explicit user request

Key steps and verified progress:

- Resumed the existing open game without restarting it, using the repaired Python decoder and the same packaged `spdctl` connection.
- Continued from the Dwarf King phase rather than replaying the previously completed attack.
- Defeated the Dwarf King; the public feedback included the `King Conqueror` badge and the crown pickup. The game showed the crown transforming the armor into Heroic Leap.
- Reached the ambitious imp shop, bought three healing potions, torches and a map scroll, sold surplus gear, then entered floor 21.
- On floor 21, found and destroyed the demon spawner/blood nest, handled Ripper Demons and Evil Eyes, used the map scroll, collected an upgrade scroll and Remove Curse scroll, and maintained the one-action/settle flow. The repaired decoder accepted current frames throughout this segment.
- Continued to floor 22 with successful transition save receipts. At the time the user said “暂停游戏”, the final completed movement had already settled and the game was at `t3.1631`, revision `r3g1`, floor 22, cell 1218, level 20 Gladiator, 106/115 HP, no visible enemies, awaiting input.
- Immediately after the pause request, the tactical pilot was interrupted, `stop.json` was atomically written with the explicit pause reason, and no further gameplay action was sent. The game and controller remained open.

Final paused state:

- Floor 22, level-20 Gladiator, 106/115 HP.
- Main equipment: +7 Assassin’s Blade, +5 Heroic/converted Swift armor, +3 Ring of Tenacity; Heroic Leap selected.
- Three healing potions and three torches were available at the last reported state.
- Dwarf King defeated; floor-21 demon spawner destroyed; floor-22 transition had successful save receipts.
- No death or victory/ascension ending was observed yet. The user explicitly paused before the final-game completion.

Failures and how to do differently:

- Do not treat a successful outer Java-controller response as proof that a strict external semantic decoder has accepted the frame; keep raw wire evidence and decoder status separate.
- Do not “fix” the anomaly by clamping `with_shield`, deleting the field, changing zoom blindly, or modifying the game while the user has requested a Python-only patch.
- Do not restart before confirming a native save receipt if the current combat state is not known to be persisted. Here, a successful save `p2g` was explicitly obtained at floor 20 before the later resume; the final pause also preserved current relay evidence.
- On `STALE_STATE`, settle the exact action and use a fresh revision. Several stale actions occurred during the Dwarf King fight; none were replayed blindly.
- When the user says pause, stop all tactical agents and relay writes, preserve the current response/intent evidence, and leave the process open.

References:

- [1] Packaged launcher: `"./desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl" control --machine`
- [2] Protocol manual: `docs/cli-help.md`; bundled controller uses `settle`, not gameplay `wait`, to finish pending actions.
- [3] Decoder: `desktop-control/client/spdctl_client.py`, `_health_estimate()` around lines 666–685.
- [4] Added tests: `desktop-control/src/test/python/test_health_estimate_rounding.py`; updated `test_combat_visual_wire.py`.
- [5] Live repair verification: `t3.1261`, `s2`, `r38e`, phase `player_ready`, floor 20, hero 112/115; raw `21/4/22` preserved.
- [6] Signed commit: `cf5b4f701 fix(cli): tolerate one-pixel health bar rounding in Python client`.
- [7] Final pause response: `t3.1631`, `s2`, `r3g1`, phase `player_ready`, floor 22, hero cell 1218, 106/115 HP.
- [8] Pause artifact: `/tmp/spd-warrior-20261005-relay/pause-20261006-f22.json`.
- [9] Prior anomaly evidence: `/tmp/spd-warrior-20261005-relay/failure-t3.1258-response.json`, `/tmp/spd-warrior-20261005-relay/failure-t3.1258-receipt.json`, and archived stop record.

## Task 5: User-requested pause

Outcome: success

Preference signals:

- The user explicitly wrote “暂停游戏” -> stop all gameplay input immediately while leaving the existing game and `spdctl` controller open.

Key steps:

- Interrupted the sole gameplay pilot and source/map advisors.
- Wrote an atomic pause marker with the user’s reason.
- Confirmed no pending action remained and the last movement had completed.
- Did not send save, quit, restart, or any further game action after the pause request.



<!-- END IMPORTED SOURCE -->
