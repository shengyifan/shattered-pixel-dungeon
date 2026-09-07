# D12 read-only scout, 2026-09-06

Initial snapshot6d291f7b9444: Hero(29,19) D12/b0, map36x48, Gladiator L10 exp7, HP67/67, STR14+Might0 effective15, gold66, hunger23, turn1828. This initial transition snapshot still has game.dropped12 queue; root is operating UI, so reread after each action. No UI/source/save modifications by scout.

## Priority and armor decision

- No armor anywhere in D12 heaps, chests, or mob equipment. No Mimic hidden equipment: all8mobs expanded.
- Strength is INSIDE ordinary chest(24,38), not a ground-potion S marker. Upgrade ground(22,40), Invisibility(24,40), all in southeast ordinary room with no traps/mobs initially.
- Food(18,18), EnchantmentStone(18,19), Gold190(19,19), IronKey(18,20) in entry-west small room.
- CrystalKey(21,32), Recharging(20,34) near south Bat(21,33).
- Keep D11 SoU1 and D12 SoU1 for higher armor. After Strength base15+Might0=16: a future uncursed Plate0 + Seal1 +2SoU becomes Plate3, STR16, DR3–25. No such plate has been found yet; don't write this as completed gear.
- Crystal vault door(16,40) uses IronKey; only1CrystalKey for one of2chests. **Recommend TimekeepersHourglass0 uncursed charge5 at(15,44), rather than cursed RingSharpshooting0 at(17,42).** Ring requires RemoveCurse and only buffs current Crossbow3 Dart7–21→8–23 (mean+1.5), no crossbow melee buff. Tomahawk0 goes6–16→7–19, bleeding3–6→3.5–7, baseline uses5→6. Hourglass supplies emergency repositioning/escape that armor2 currently lacks; further exact freeze boundaries appended below.

## All initial heaps (16)

(14,3) StoneAggression1
(13,16) Terror1
(11,17) Teleportation1
(18,18) Food1
(18,19) StoneEnchantment1
(19,19) Gold190
(18,20) IronKey D12
(10,21) Transmutation1
(21,32) CrystalKey D12
(20,34) Recharging1
(24,38) CHEST Strength1
(22,40) Upgrade1
(24,40) Invisibility1
(17,42) CRYSTAL_CHEST SharpshootingRing+0 cursed, role unknown
(5,43) CHEST RoundShield+1 uncursed, behind3Piranhas; optional, not armor
(15,44) CRYSTAL_CHEST TimekeepersHourglass+0 uncursed charge5, sandbags0

SecretLibraryRoom x9..16/y14..22 contains3scrolls, entry SECRET(10,22). Interior bookshelves block selected approaches. It is by Spinner11,23, not a free immediate detour. Never fire through ordinary scrolls.

## The lost Dart

Initial game.dat includes `dropped12[0]` Dart0 quantity1 durability100; initial level12 heaps do not yet include it. This proves it is queued for D12, not lost permanently and not tied to old D11(19,33).
Dungeon.dropToChasm sets destination depth+1. GameScene.create lines564–579 chooses randomRespawnCell for each queued item, drops it, and removes droppedItems[D12]. Thus after full scene entry and next actual minimize/save, root should read new heaps for actual Dart coordinate. No new coordinate is asserted until that save. Do not fall/jump to chase it.

## All initial enemies

Bat(8,7)30HP sleeping, Bat(21,33)30HP sleeping. Speed2 movement, flying over chasms, attack5–18 DR0–4, attackdelay1, heals excess over4damage dealt. Do not estimate pursuit by land-path distance.
BlueShaman(12,7)35HP sleeping. Ranged6–15 magic with50% Vulnerable debuff, adjacent5–10 physical DR0–6. Use door20,7 to cut line from eastern approach; exact lure must follow actual FOV/state.
Spinner(11,23)50HP sleeping, melee10–20 DR0–6, attack22, defense17. 50% poison7–8 on hit; then flees; web aimed in direction hero movement or between hero/spider if stationary. Web20volume, cooldown10, shooting interrupts hero. Spider resists poison and immuneweb. Prefer avoid this west group for now.
Brute(8,29)40HP sleeping. Normal5–25 DR0–8; rage24shield drains4/tick, rage15–40. Retreat after enrage; no need grind shield.
Piranha(4,38),(6,39),(6,40), each70HP sleeping. They guard RoundShield1, skip room unless deliberate shore/ranged or invisibility plan.

## All traps, doors, stairs

Confusion(23,20) hiddenactive; Frost(13,25) hiddenactive; PoisonDart(16,43) visibleactive. No initial blobs/plants.
Secrets(28,23),(22,28),(10,22).
Doors(27,16),(20,7),(22,19),(19,22),(7,24),(3,12),(8,35),(18,35),(21,35).
LockedDoor(16,40). Entrance(29,19), exitD13(7,6).

## Route plan (static terrain, inspect every threat/action)

1. Entrance south: (29,19)→(29,20)→(29,21)→(28,22); search SECRET(28,23), then enter. No initial nearby enemies/traps.
2. Food room, avoiding Confusion23,20: (28,23)→(27,24)→(26,24)→(25,24)→(24,23)→(24,22)→(23,21)→(24,20)→(23,19)→door(22,19)→(21,19)→(20,19)→Gold(19,19)→Food(18,18)→Stone(18,19)→Key(18,20). Alternative safe east bend from24,23 via25,22/25,21 also avoids trap; NEVER step23,20.
3. Prefer leave via same east door22,19 and east ring corridor to south platform, instead of opening19,22 toward Spinner11,23 (distance8 and chasm doesn't block FOV). (18,20)→(19,20)→(20,20)→(21,20)→(22,19)→(23,19)→(24,20)→(23,21)→(23,22)→(24,23)→(24,24)→(23,25)→(23,26)→(23,27)→(23,28); search SECRET(22,28), then(21,28).
4. South key/one Bat: (21,28)→(20,29)→(20,30)→(20,31)→CrystalKey(21,32). Initial Bat21,33 may wake early and fly adjacent; handle it before looting. Do not mindlessly path onto21,33 or fire darts when Bat is above chasm unless accepted recovery risk.
5. Southeast Strength+Upgrade: after Bat handled→(21,33)→(21,34)→door(21,35)→(22,36)→(23,37)→CHEST(24,38); open then pick and drink as separate verified actions. →(23,39)→Upgrade(22,40); optionally Invisibility(24,40). No traps in this room, no Mimic in initial mobs.
6. Crystal vault: back(22,39)→(22,38)→(22,37)→(22,36)→(21,35)→Recharging(20,34)→(19,34)→door(18,35)→(17,36)→(16,37)→(16,38)→(16,39); unlock(16,40). To Hourglass safely:(16,41)→(15,42)→(15,43)→CHEST(15,44), open using crystal key then pickup. **Avoid PoisonDart(16,43)**. Return via15,43→15,42→15,41→16,40.
7. After southern prizes, reverse east route to entrance, then north route to D13 instead of traversing Spider/Brute western half: (23,28)→(23,27)→(24,26)→(25,25)→(26,24)→(27,24)→(28,23)→(28,22)→(28,21)→(28,20)→(28,19)→(28,18)→(29,17)→(28,16)→door(27,16)→(26,16)→(25,16)→(24,15)→(24,14)→(23,13)→(22,13)→(21,12)→(21,11)→(21,10)→(21,9)→(21,8) stop outside door20,7.
8. North BlueShaman: open door20,7, inspect; retreat21,8 to close door if it sees hero. Lure to door and melee if feasible. Do not stand at17,7 in clear ranged line to Shaman12,7. After actual enemies handled, terrain route20,7→19,7→18,7→17,7→16,6→15,6→14,6→13,6→12,7→11,8→10,9→9,10→8,10→7,9→7,8→7,7→exit7,6. Bat8,7 in exit core must be handled first, exact encounter may join Shaman.

## Source checks

Dungeon.java dropToChasm destination+1; GameScene.java564–579 queued drops.
Armor.java STRReq, DRMin/DRMax, seal affix.
Dart.java min/max; RingOfSharpshooting.java levelDamageBonus; TimekeepersHourglass.java activation/timeFreeze.
Spinner.java attackProc/webPos/shootWeb; Bat.java; Shaman.java; Brute.java.

## Hourglass exact operating limits (independent source cross-check)

- Pick **uncursed Hourglass(15,44)**. It does not consume scarce armor upgrades. +0 Sharpshooting adds only1.5mean Dart damage while cursed version would give -2 effective missile levels until cleansed.
- Freeze surrounding time: activation spends1charge,2standard action turns percharge,5fullcharge→up to10hero action turns. Moving/opening doors/picking up/eating/drinking do not dispel. Normal melee, enemy projectile attacks including misses, wand casts, and scroll reading call Invisibility.dispel and end freeze; attack/cast still spends normal time. Do not plan ten free attacks.
- Healing buff does not tick while external time is frozen. Drinking safely during freeze prepares healing for after unfreeze, it does not instantly heal67HP. Damage itself is not a universal freeze-cancel trigger.
- Self-stasis spends min(charge,2),5worldturns percharge, up to10worldturns: no hero actions; Hero.damage returns before damage; new negative buffs rejected; existing buffs including Healing continue. Therefore drink Healing then self-stasis can provide protected heal ticks. Existing poison durations also tick, damage is blocked.
- Stepped traps/plants are DELAYED, not disarmed by ordinary freeze; they fire on thaw. Leave range/ray before thaw. Don't walk over PoisonDart16,43 for convenience.
- At +0 charge regeneration is1/(90-(chargeCap-charge)*3) each worldturn while regeneration is enabled: roughly75–87turns percharge,~405from0to5. Use as tactical resource. Future ungenerated D16 shop can stock sand; existing D11 shop doesn't refresh.
- Key source locations: TimekeepersHourglass.java timeFreeze around351, self-stasis284; Invisibility.java101 dispels freeze; Hero.java1580 damage immunity; Healing.java51 tick.

## Actual Dart landing confirmed after lifecycle save

Snapshot5f5b294f8174: H(28,22) HP67/67, hunger33, turn1834. The dropped Dart is now a real HEAP at **(11,6)** on D12, close to BlueShaman(12,7). Pick it during north-exit encounter after southern upgrades. Initial pending-queue text above is historical transition evidence, not a missing-item issue.
