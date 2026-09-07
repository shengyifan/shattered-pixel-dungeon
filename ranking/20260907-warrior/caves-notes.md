# D11 read-only scout, 2026-09-06 06:50 +0800

This is the initial D11 persisted snapshot 8209337106ef, H31,40 HP57/62 L9 exp29, duration1683, gold185. Root is actively playing; all enemy positions and loot ownership must be reread before acting. No game UI, save, or source writes were performed.

## Priorities

- No Strength potion or free armor anywhere on D11. One Upgrade at (14,20), inside Library locked door (16,17). IronKey (26,38) near entrance.
- Gold236 at (23,37). RunicBlade+0 (26,37) is cursed Sacrificial; never equip for trial. Unknown item sells80, known curse40.
- Free Healing(19,25), Haste(20,27).
- Buy BlindingDart x2 (28,49),225 gold, for Crossbow+3. Its tips blind and wear to regular reusable darts. Prefer this over shop Mail0.
- Shop Mail0 (33,45)900 gold -> after seal Mail1 Thorns DR1–9 vs currentLeather2 DR2–8: same mean5, only warrior shield improves8→10. Poor purchase at900.
- Existing spare Leather0 Potential and Leather1 Viscosity are both unknown level and sell60 each. HandAxe2 sells120. No need to identify by equip merely for sale; seal transfer prompts are consequential. If deliberate identify for higher sale, decline seal transfer and restore original armor; 2 extra equipment turns for +60 gold on Viscosity may not merit complexity.

## All initial non-shop heaps

(22,6) Food1
(12,16) Expedition lore page
(7,18) Stylus1
(28,18) CHEST ThrowingHammer+1 x3 uncursed, tier5 STR16, guarded Sentry room; optional and presently1STR too heavy
(16,19) RemoveCurse1
(14,20) Upgrade1
(19,25) Healing1
(20,27) Haste1
(22,31) Bolas0 x3 cursed Displacing
(23,37) Gold236
(26,37) RunicBlade0 cursed Sacrificial
(26,38) IronKey D11
No additional nested chests/artifacts in the saved mobs/heaps.

## Shop full list

Prices depend on current item identification; below initial state prices at D11 (Shopkeeper.sellPrice = item.value *15).

(30,43) Healing450
(31,43) MagicMapping unknown450 (known600)
(32,43) Identify450
(33,43) StoneAugmentation450
(28,44) Transmutation unknown450 (known750)
(33,44) ThrowingSpear0 x3 675
(28,45) SmallRation150
(33,45) MailArmor0 uncursed900
(28,46) MindVision unknown450
(33,46) Healing450
(28,47) RemoveCurse450
(33,47) Mace0 uncursed900
(28,48) Ankh750
(33,48) SmallRation150
(28,49) BlindingDart x2 225
(29,49) Bomb225
(30,49) Alchemize x3 105 (value truncates 20*3/8 to7)
(31,49) MagicalHolster900
(32,49) ParalyticGas unknown450
(33,49) RingForce0 uncursed1125

## All initial enemies

Bat(20,31), Bat(20,24), Bat(11,22), all30HP sleeping. Speed2 movement, can fly over chasms, attack5–18, DR0–4, attackSkill16, defense15. Heals by damage-4 on successful damage; do not calculate pursuit distance using floor-only path.
Brute(13,7)40HP sleeping, attack5–25 DR0–8; when0HP gets24shield and enrages15–40damage, shield loses4/turn. Retreat during rage; dies within6ticks unless unusual buffs/healing.
RedShaman(15,4)35HP sleeping, ranged6–15 magic50% Weakness; adjacent physical5–10, DR0–6. Lure through closed door if encountered.
Sentry(27,23)1HP passive special-room hazard; don't attack. Initial charge2.1, only charges when hero visible AND standing EMPTY_SP inside room. Safe floor resets charge. Chest optional; save Haste unless deliberate route.
Shopkeeper(31,46).

## All traps / special cells

Corrosion(12,15) active hidden; PoisonDart(10,19) active visible; Burning(13,22) active hidden. Avoid all3.
WaterOfAwareness well(6,9), optional long northwest detour.
SecretDoors(21,39),(18,17),(12,17),(11,4),(6,6).
Entrance(31,40), exitD12(7,23).

## Verified terrain routes (each segment requires live enemy readback)

Entrance to key: (31,40),(30,40),(29,39),(28,38),(27,38),(26,38).
Key to gold: (26,38),(25,38),(24,39),(23,38),(23,37).
Gold to south door: (23,37),(22,38),(21,39 SECRET search),(20,39),(19,38),(19,37),(19,36),(18,35),(18,34 DOOR).
South door into central platform: (18,34),(18,33),(18,32); Bat20,31 may approach across chasm, inspect each action.
Central first loot after bat handled: (18,32),(19,31),(19,30),(19,29),(19,28),(20,27 Haste).
Haste to Healing: (20,27),(19,27),(18,26),(19,25 Healing). Bat20,24 may awaken; do not path past it without fight/readback.
Healing to library: (19,25),(19,24),(19,23),(19,22),(19,21 DOOR),(19,20),(18,19),(18,18),(18,17 SECRET search),(17,16),(16,17 LOCKED unlock),(16,18),(16,19 RemoveCurse),(15,20),(14,20 Upgrade).
Return library via same door and southern secret to (19,21); no need to traverse northern outer ring with Brute/Shaman unless food needed.
From (19,21) to D12 via west central: (19,22),(18,23),(17,23),(16,23),(15,23),(14,24),(13,24),(12,23),(11,22 BAT),(10,21),(9,20),(8,19),(7,20),(7,21),(7,22),(7,23 EXIT). This avoids Burning13,22 and PoisonDart10,19. Fight Bat11,22 before pathing on its cell; all enemy positions are initial only.

## Sources checked

core/.../actors/mobs/npcs/Shopkeeper.java sellPrice
core/.../windows/WndTradeItem.java selling does not spend game time
core/.../items/armor/Armor.java DR, STRReq, value, doEquip, affixSeal
core/.../items/weapon/melee/MeleeWeapon.java value
core/.../items/weapon/missiles/MissileWeapon.java STRReq,value
core/.../items/weapon/missiles/darts/TippedDart.java value,durability
core/.../levels/rooms/special/SentryRoom.java safe-tile trigger, loot
core/.../actors/mobs/Bat.java,Brute.java,Shaman.java combat
