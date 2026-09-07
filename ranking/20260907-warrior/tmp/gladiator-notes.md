# Gladiator combat support (local v3.3.8 source, 2026-09-06)

Read-only source work; no UI input or game/save changes. Current build context: D11 L9 Gladiator, STR15, Chilling Crossbow+3, Thorns Leather+2, Runic Transference2.

## Controls / counters

- `X` is default `SPDAction.TAG_ACTION`; `ActionIndicator` invokes `Combo.doAction`, opening `WndCombo`. Actual `keybinds.dat` absent in current app data root, so no saved custom override there.
- Open menu is free. Buttons are ordered 冲击(2), 撞击(4), 招架(6), 横扫(8), 暴雨(10). Disabled buttons reflect count and once-per-string flags. There is NO 3-hit Parry.
- For targeted moves, choose button then click visible attackable enemy. With current ordinary Crossbow this requires adjacency. 招架 applies immediately when clicked; no target click.
- Successful normal melee AND missile attacks add1. Hits refresh time to >=5; kills set15 without Cleave. Misses do not instantly clear count, but do not refresh clock. Saving/focus does not advance turns.
- Save Combo fields: count, combotime, clobber_used, parry_used. Use current saved values before choosing a move.

## Useful moves now (no Enhanced Combo talents)

| Count | Chinese / move | Time | Result / consumption |
|---|---|---|---|
|2|冲击 / Clobber|weapon attackDelay, current1|Base damage0; push up to2; adds1 hit; keeps string; once per string. Ordinary weapon enchant proc still runs.|
|4|撞击 / Slam|weapon attackDelay, current1|Normal weapon hit + round(hero DR roll × count/5); clears string.|
|6|招架 / Parry|1 turn total|First attack within next1 turn is evaded; free normal-damage riposte if enemy in reach; successful riposte adds1 and keeps string. No attack received -> clears entire string. Once per string.|
|8|横扫 / Crush|weapon attackDelay, current1|Main weapon damage ×count/4, plus half that to reachable enemies within3 cells from hero; clears string.|
|10|暴雨 / Fury|one attackDelay for entire chain|Up to count attacks at60% damage on one enemy, each can proc enchant; stops if target dead/out of reach; clears string.|

Accuracy: UI calls finishers guaranteed. Exact implementation passes accuracy multiplier1,000,000 to Char.attack; normal Bat/Brute evasion is negligible against this. Infinite evasion still wins. Parry defense actually returns INFINITE_EVASION. Do not describe special invulnerable targets as guaranteed damage.

Clobber restrictions: wall/occupied landing tile/rooted/immovable may reduce or cancel push. Normal version avoids dropping nonflying enemies into pits. It does NOT inflict collision damage or collision paralysis (`throwChar(... true, false, hero)`). Landing still calls occupyCell, and leaving an open door closes it. Check actual landing and enemy movement after use.

## Bat

- HP30, DR0–4, ordinary attack5–18; moves twice per normal hero turn but attacks once per turn. Dealt physical damage above4 heals Bat by damage−4.
- Do not ordinary-kite an adjacent unchilled Bat. A full Clobber2 can be spent flying back adjacent during that same hero action; follow actual save, not expected gap.
- At count4+, Slam is a reliable finisher rather than gambling a normal miss, especially if remaining Bat HP is low. At count2–3 Clobber is only a temporary spacing/chill-proc tool, not damage.
- At count6 and a Bat definitely ready to attack within1 turn, Parry denies that attack and gives a free counter. Chilling slows attack scheduling, so do not assume a chilled Bat will attack before Parry expires.

## Brute / rage

- HP40, DR0–8. Normal hit5–25; rage hit15–40. On first lethal HP transition: rage shield set24, Brute spends1 turn. Rage shield drains4 each ordinary tick (initial UI commonly20 because first tick already ran). Natural death when shield0; do not guess exact remaining turns, read shield.
- As soon as rage begins, retreat along verified clear cells. If still adjacent and Combo>=2, unused Clobber with a clear two-cell line is the safest spacing tool; then keep distance while shield drains. No need to keep attacking shield or build6.
- If only retreat is available, the initial rage self-delay provides a turn to create space. Read back each move; do not stand and wait next to it.
- Use Parry only if Combo>=6 and rage Brute is about to attack within1 turn. Initial rage delay, Chill, or travel to reach you may cause empty Parry and clear combo. Parry itself only blocks one attack, so an additional adjacent mob remains dangerous.
- Current Chilling Crossbow may slow Brute; ordinary burning is removed by Chill. Do not assume Fireblast burning persists after crossbow hit.

## Source pointers

All under `/Users/shengyifan/Workspace/shattered-pixel-dungeon/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/`:
- `actors/buffs/Combo.java`:90–99 hit/time;209–214 thresholds;281–310 availability/Parry;338–470 damage, throw, timings;listener near480 target validation.
- `actors/hero/Hero.java`:564–568 Parry defense/riposte;609–614 first-block detach;2323–2329 melee combo,478–479 missile combo.
- `items/wands/WandOfBlastWave.java`:119–202 throw restrictions/collision/occupyCell.
- `SPDAction.java`:139 X default;197 bindings file;`ui/ActionIndicator.java`:50–51 key action;`windows/WndCombo.java`:button order/selection.
- `actors/mobs/Bat.java`: baseSpeed2, HP30, attack/heal;`actors/mobs/Mob.java`:655 attackDelay1, movement spend1/speed.
- `actors/mobs/Brute.java`:damageRoll,triggerEnrage,isAlive,BruteRage.act.
- `actors/Char.java`:624–685 hit ordering/multiplier;1122–1138 Chill time scaling.
