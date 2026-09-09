package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Ghost;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Wandmaker;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Amulet;
import com.shatteredpixel.shatteredpixeldungeon.items.Ankh;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.PlateArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.MasterThievesArmband;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.DriedRose;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Embers;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.DarkGold;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Pickaxe;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFireblast;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Legal, isolated starting conditions only. All actual UI choices are made over public NDJSON. */
final class LowFrequencyFixtures {
    static boolean supports(String name) {
        return DwarfVisualFixtures.supports(name) || VisualCueFixtures.supports(name) || Arrays.asList("shop-trade", "shop-steal", "shop-steal-warning", "shop-stack", "shop-steal-failure",
                "ghost-reward", "wandmaker-reward", "blacksmith-cashout", "blacksmith-pickaxe", "blacksmith-reforge",
                "blacksmith-harden", "blacksmith-upgrade", "blacksmith-smith", "companion", "companion-attack",
                "companion-resummon", "resurrect", "blessed-ankh", "alchemy-energy", "amulet-stay", "amulet-end", "amulet-pickup").contains(name);
    }

    static void prepare(String name, Hero hero) throws Exception {
        arena(hero);
        if (DwarfVisualFixtures.supports(name)) {
            DwarfVisualFixtures.prepare(name, hero);
            Item.updateQuickslot(); Dungeon.observe(); hero.checkVisibleMobs();
            return;
        }
        if (VisualCueFixtures.supports(name)) {
            VisualCueFixtures.prepare(name, hero);
            Item.updateQuickslot(); Dungeon.observe(); hero.checkVisibleMobs();
            return;
        }
        switch (name) {
            case "shop-trade":
            case "shop-steal":
            case "shop-steal-warning":
            case "shop-stack":
            case "shop-steal-failure": {
                Dungeon.gold = 1000;
                spawn(new Shopkeeper(), hero.pos + 1);
                Item stock = new PotionOfHealing().identify(false);
                if (name.equals("shop-steal-failure")) {
                    stock = new PlateArmor(); stock.level(3); stock.identify(false);
                }
                Heap heap = Dungeon.level.drop(stock, hero.pos - 1);
                heap.type = Heap.Type.FOR_SALE;
                heap.sprite.link(heap);
                if (name.equals("shop-stack")) new PotionOfHealing().identify(false).quantity(3).collect();
                if (name.startsWith("shop-steal")) {
                    MasterThievesArmband armband = new MasterThievesArmband();
                    armband.identify(false);
                    set(armband, "charge", name.equals("shop-steal") ? 5 : 1);
                    hero.belongings.artifact = armband;
                    armband.activate(hero);
                }
                break;
            }
            case "blacksmith-cashout":
            case "blacksmith-pickaxe":
            case "blacksmith-reforge":
            case "blacksmith-harden":
            case "blacksmith-upgrade":
            case "blacksmith-smith": {
                Blacksmith.Quest.reset();
                set(Blacksmith.Quest.class, "spawned", true);
                set(Blacksmith.Quest.class, "given", true);
                set(Blacksmith.Quest.class, "type", Blacksmith.Quest.CRYSTAL);
                Blacksmith.Quest.start();
                if (!name.equals("blacksmith-pickaxe")) Blacksmith.Quest.beatBoss();
                new DarkGold().quantity(40).collect();
                new Pickaxe().identify(false).collect();
                Blacksmith.Quest.complete(); // 2000 favor without boss, otherwise 3000 and the native free-pickaxe flag
                Sword sword = new Sword(); sword.level(1); sword.identify(false); sword.collect();
                new Sword().identify(false).collect();
                spawn(new Blacksmith(), hero.pos + 1);
                break;
            }
            case "companion":
            case "companion-attack":
            case "companion-resummon": {
                Ghost.Quest.reset();
                set(Ghost.Quest.class, "spawned", true);
                set(Ghost.Quest.class, "given", true);
                set(Ghost.Quest.class, "processed", true);
                DriedRose rose = new DriedRose(); rose.identify(false);
                hero.belongings.artifact = rose; rose.activate(hero);
                Sword sword = new Sword(); sword.level(1); sword.identify(false); sword.collect();
                new LeatherArmor().identify(false).collect();
                if (name.equals("companion-attack")) {
                    Rat rat = new Rat(); rat.HT = rat.HP = 10000;
                    spawn(rat, hero.pos + 3); Buff.prolong(rat, Paralysis.class, 1000f);
                } else if (name.equals("companion-resummon")) {
                    set(rose, "firstSummon", true);
                    set(rose, "charge", 99);
                    set(rose, "partialCharge", 0.9f);
                }
                break;
            }
            case "resurrect":
            case "blessed-ankh": {
                Ankh ankh = new Ankh();
                if (name.equals("blessed-ankh")) ankh.bless();
                ankh.collect();
                new ScrollOfIdentify().identify(false).collect();
                hero.HP = 1;
                Buff.affect(hero, Poison.class).set(30f);
                break;
            }
            case "alchemy-energy": {
                Dungeon.energy = 20;
                new PotionOfHealing().identify(false).quantity(2).collect();
                Level.set(hero.pos + 1, Terrain.ALCHEMY);
                GameScene.updateMap(hero.pos + 1);
                break;
            }
            case "amulet-stay":
            case "amulet-end": {
                new Amulet().collect();
                Statistics.amuletObtained = true;
                break;
            }
            case "amulet-pickup": {
                Statistics.amuletObtained = false;
                Dungeon.level.drop(new Amulet(), hero.pos + 1);
                break;
            }
            case "ghost-reward": {
                Ghost.Quest.reset();
                set(Ghost.Quest.class, "spawned", true);
                set(Ghost.Quest.class, "given", true);
                set(Ghost.Quest.class, "processed", true);
                set(Ghost.Quest.class, "type", 1);
                set(Ghost.Quest.class, "depth", Dungeon.depth);
                Ghost.Quest.weapon = new Sword();
                Ghost.Quest.weapon.level(1);
                Ghost.Quest.armor = new LeatherArmor();
                Ghost.Quest.armor.level(1);
                spawn(new Ghost(), hero.pos + 1);
                break;
            }
            case "wandmaker-reward": {
                Wandmaker.Quest.reset();
                set(Wandmaker.Quest.class, "spawned", true);
                set(Wandmaker.Quest.class, "given", true);
                set(Wandmaker.Quest.class, "type", 2);
                Wandmaker.Quest.wand1 = new WandOfMagicMissile();
                Wandmaker.Quest.wand1.level(1);
                Wandmaker.Quest.wand2 = new WandOfFireblast();
                Wandmaker.Quest.wand2.level(1);
                new Embers().collect();
                spawn(new Wandmaker(), hero.pos + 1);
                break;
            }
            default: throw new IllegalArgumentException("Unknown low-frequency fixture");
        }
        Item.updateQuickslot();
        Dungeon.observe();
        hero.checkVisibleMobs();
    }

    static Map<String, Object> assertions() throws Exception {
        MasterThievesArmband armband = Dungeon.hero.belongings.getItem(MasterThievesArmband.class);
        DriedRose rose = Dungeon.hero.belongings.getItem(DriedRose.class);
        Object ghost = rose == null ? null : get(rose, "ghost");
        Char enemy = ghost == null ? null : (Char) get(ghost, "enemy");
        Rat target = null;
        for (Mob mob : Dungeon.level.mobs) if (mob instanceof Rat) { target = (Rat) mob; break; }
        ArrayList<Integer> swordLevels = new ArrayList<>();
        int hardenedSwords = 0;
        for (Sword sword : Dungeon.hero.belongings.getAllItems(Sword.class)) {
            swordLevels.add(sword.level());
            if (sword.enchantHardened) hardenedSwords++;
        }
        return map("ghost_quest_complete", Ghost.Quest.completed(),
                "wandmaker_rewards_claimed", Wandmaker.Quest.wand1 == null && Wandmaker.Quest.wand2 == null,
                "blacksmith_favor", Blacksmith.Quest.favor,
                "blacksmith_smiths", Blacksmith.Quest.smiths,
                "blacksmith_rewards_pending", Blacksmith.Quest.smithRewards != null,
                "sword_levels", swordLevels, "hardened_swords", hardenedSwords,
                "dungeon_energy", Dungeon.energy,
                "armband_charge", armband == null ? null : get(armband, "charge"),
                "rose_charge", rose == null ? null : get(rose, "charge"),
                "rose_weapon", rose == null || get(rose, "weapon") == null ? null : get(rose, "weapon").getClass().getSimpleName(),
                "rose_armor", rose == null || get(rose, "armor") == null ? null : get(rose, "armor").getClass().getSimpleName(),
                "ghost_defending_cell", ghost == null ? null : get(ghost, "defendingPos"),
                "ghost_enemy_cell", enemy == null ? null : enemy.pos,
                "fixture_rat_hp", target == null ? null : target.HP,
                "rose_first_summon", rose == null ? null : get(rose, "firstSummon"),
                "ankhs_used", Statistics.ankhsUsed);
    }

    private static void arena(Hero hero) {
        for (Mob mob : new ArrayList<>(Dungeon.level.mobs)) {
            for (Buff buff : mob.buffs()) Actor.remove(buff);
            Actor.remove(mob);
            if (mob.sprite != null) mob.sprite.killAndErase();
        }
        Dungeon.level.mobs.clear();
        for (Actor actor : Actor.all()) if (actor instanceof MobSpawner) Actor.remove(actor);
        for (com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob : Dungeon.level.blobs.values()) Actor.remove(blob);
        Dungeon.level.blobs.clear();
        for (Heap heap : Dungeon.level.heaps.valueList()) if (heap.sprite != null) heap.sprite.killAndErase();
        Dungeon.level.heaps.clear(); Dungeon.level.plants.clear(); Dungeon.level.traps.clear();
        int width = Dungeon.level.width();
        int x = Math.max(4, Math.min(width - 5, hero.pos % width));
        int y = Math.max(4, Math.min(Dungeon.level.height() - 5, hero.pos / width));
        for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) {
            int cell = (y + dy) * width + x + dx;
            Level.set(cell, Terrain.EMPTY);
            GameScene.updateMap(cell);
        }
        hero.pos = y * width + x;
        hero.sprite.place(hero.pos);
    }

    private static void spawn(Mob mob, int position) { mob.pos = position; GameScene.add(mob); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target instanceof Class ? null : target, value); }
    private static Object get(Object target, String name) throws Exception { return field(target, name).get(target instanceof Class ? null : target); }
    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
