package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultMirror;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultTokenDoor;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.DwarfToken;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.EscapeCrystal;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.ImpStatue;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.VaultBeacon;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfAugmentation;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFireblast;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Mace;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.RoundShield;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Spear;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.VaultLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Isolated starting conditions only. Every measured interaction uses the native public CLI. */
final class Cli9VaultScenarioFixtures {
    private static String scenario;

    static boolean supports(String name) {
        if (List.of("exit-early", "exit-consumable", "exit-equipment", "exit-partial", "exit-victory",
                "exit-staff", "exit-seal", "exit-full", "tokens-0", "tokens-9", "tokens-10", "beacon", "injury",
                "staff-imbue", "augmentation").contains(name)) return true;
        for (HeroClass cls : HeroClass.values())
            if (name.equals("entry-" + cls.name()) || name.equals("mirror-" + cls.name())) return true;
        return false;
    }

    static HeroClass heroClass(String name) {
        if (name.startsWith("entry-") || name.startsWith("mirror-"))
            return HeroClass.valueOf(name.substring(name.indexOf('-') + 1));
        return List.of("exit-staff", "staff-imbue").contains(name) ? HeroClass.MAGE : HeroClass.WARRIOR;
    }

    static void prepare(String name, Hero hero) {
        if (!supports(name)) throw new IllegalArgumentException("Unknown vault fixture");
        scenario = name;
        // Destroy the original scene/scheduler first. No fixture mutation occurs after create.
        Game.switchScene(GameScene.class, new Game.SceneChangeCallback() {
            @Override public void beforeCreate() {
                try { source(name, hero); }
                catch (Exception error) { throw new IllegalStateException("Vault fixture source failed", error); }
            }
            @Override public void afterCreate() { }
        });
    }

    private static void source(String name, Hero hero) throws Exception {
        Dungeon.depth = 19;
        Dungeon.branch = 0;
        Imp.Quest.reset();
        Level city = Dungeon.newLevel();
        Dungeon.level = city;
        clear(city, Imp.class);
        LevelTransition entrance = city.getTransition(LevelTransition.Type.BRANCH_EXIT);
        if (entrance == null) throw new IllegalStateException("Native depth 19 generation omitted the imp entrance");

        hero.lvl = 20;
        hero.STR = 18;
        hero.live();
        hero.updateHT(false);
        hero.HP = hero.HT;
        hero.ready = true;
        hero.curAction = hero.lastAction = null;
        hero.resting = false;
        Item original = new RoundShield().upgrade(3).identify(false);
        original.collect();
        Dungeon.quickslot.setSlot(0, original);
        Dungeon.gold = 47;
        Dungeon.energy = 13;
        if (name.equals("exit-full")) {
            while (hero.belongings.backpack.items.size() < hero.belongings.backpack.capacity()) {
                if (!new Sword().identify(false).collect())
                    throw new IllegalStateException("Native backpack rejected its declared fixture capacity");
            }
        }

        if (name.startsWith("entry-")) {
            Dungeon.switchLevel(city, entrance.cell());
            Item.updateQuickslot();
            return;
        }

        // Seed a real return floor and use the native inventory custody mechanism.
        setQuest("given", true);
        Dungeon.saveLevel(GamesInProgress.curSlot);
        EscapeCrystal crystal = new EscapeCrystal();
        crystal.storeHeroBelongings(hero);
        crystal.collect();
        hero.belongings.armor = new ClothArmor();
        hero.belongings.armor.identify(false);
        Dungeon.branch = 1;
        VaultLevel vault = (VaultLevel) Dungeon.newLevel();
        Dungeon.level = vault;

        Class<? extends Mob> retained = name.startsWith("tokens-") ? VaultTokenDoor.class
                : name.startsWith("mirror-") ? VaultMirror.class
                : name.equals("exit-partial") || name.equals("beacon") ? null : VaultTokenDoor.class;
        clear(vault, retained);
        int position = vault.entrance();

        if (name.startsWith("tokens-")) {
            int amount = Integer.parseInt(name.substring("tokens-".length()));
            if (amount > 0) new DwarfToken().quantity(amount).collect();
            Mob door = vault.mobs.iterator().next();
            position = adjacentOpen(vault, door.pos);
        } else if (name.startsWith("mirror-")) {
            Mob mirror = vault.mobs.iterator().next();
            position = adjacentOpen(vault, mirror.pos);
            // Picked up only after taking the mirror, exercising native token discard.
            vault.drop(new DwarfToken(), position);
        } else if (name.equals("beacon")) {
            new VaultBeacon().quantity(2).collect();
            position = adjacentOpen(vault, vault.entrance());
        } else if (name.equals("injury")) {
            new VaultBeacon().collect();
            hero.HP = Math.max(1, hero.HT / 4);
            Statistics.vaultInjureWarned = false;
            SPDSettings.vaultInjureWarns(0);
        } else if (name.equals("staff-imbue")) {
            new MagesStaff().identify(false).collect();
            new WandOfFireblast().upgrade(2).identify(false).collect();
        } else if (name.equals("augmentation")) {
            new Sword().identify(false).collect();
            new StoneOfAugmentation().quantity(2).collect();
        } else {
            new PotionOfHealing().quantity(3).identify(false).collect();
            new Sword().identify(false).collect();
            new Spear().upgrade().identify(false).collect();
            new Mace().upgrade(2).identify(false).collect();
            if (name.equals("exit-staff")) new MagesStaff(new WandOfMagicMissile()).upgrade(3).identify(false).collect();
            if (name.equals("exit-seal")) {
                LeatherArmor armor = new LeatherArmor();
                BrokenSeal seal = new BrokenSeal();
                seal.upgrade();
                armor.affixSeal(seal);
                armor.identify(false).collect();
            }
            if (name.equals("exit-consumable")) new DwarfToken().quantity(5).collect();
            if (name.equals("exit-equipment")) new DwarfToken().quantity(10).collect();
            if (List.of("exit-equipment", "exit-partial").contains(name)) Arrays.fill(vault.visited, true);
            if (List.of("exit-victory", "exit-staff", "exit-seal", "exit-full").contains(name)) new ImpStatue().collect();
            if (name.equals("exit-early")) new DwarfToken().collect();
        }

        vault.buildFlagMaps();
        Dungeon.switchLevel(vault, position);
        if (hero.pos != position) throw new IllegalStateException("Native vault rejected the prepared hero position");
        Item.updateQuickslot();
    }

    private static void clear(Level level, Class<? extends Mob> retained) {
        for (Mob mob : new ArrayList<>(level.mobs)) {
            if (retained != null && retained.isInstance(mob)) continue;
            level.mobs.remove(mob);
            // A removed token door is an already opened source door, as in native interaction.
            if (mob instanceof VaultTokenDoor) level.map[mob.pos] = Terrain.DOOR;
        }
        level.heaps.clear();
        level.traps.clear();
        level.plants.clear();
        level.blobs.clear();
        level.locked = false;
    }

    private static int adjacentOpen(Level level, int cell) {
        for (int offset : new int[]{level.width(), 1, -1, -level.width()}) {
            int candidate = cell + offset;
            if (level.insideMap(candidate) && level.passable[candidate] && level.findMob(candidate) == null)
                return candidate;
        }
        throw new IllegalStateException("No native passable adjacent vault fixture cell");
    }

    private static void setQuest(String fieldName, boolean value) throws ReflectiveOperationException {
        Field field = Imp.Quest.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    static Map<String, Object> assertions() {
        List<String> items = new ArrayList<>();
        if (Dungeon.hero != null)
            for (Item item : Dungeon.hero.belongings) items.add(item.getClass().getSimpleName() + ":" + item.quantity());
        return map("scenario", scenario, "depth", Dungeon.depth, "branch", Dungeon.branch,
                "level_class", Dungeon.level == null ? null : Dungeon.level.getClass().getSimpleName(),
                "quest_given", Imp.Quest.given(), "quest_complete", Imp.Quest.isCompleted(),
                "mirror_used", Imp.Quest.mirrorUsed, "items", items);
    }
}
