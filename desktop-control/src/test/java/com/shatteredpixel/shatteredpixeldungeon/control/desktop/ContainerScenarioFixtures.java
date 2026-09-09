package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ScaleArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.CrystalKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.GoldenKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.Key;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFireblast;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Longsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingStone;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** TEST ONLY initial conditions. Subsequent opening, keys, purchases and pickup use original CLI actions. */
final class ContainerScenarioFixtures {
    private static final List<Integer> targets = new ArrayList<>();
    private static final List<Item> payloads = new ArrayList<>();

    static boolean supports(String name) {
        return Arrays.asList("heap-multi", "chest-hidden", "locked-chest", "crystal-chest", "tomb",
                "skeleton", "remains", "for-sale", "iron-door", "crystal-door").contains(name);
    }

    static void prepare(String name, Hero hero) {
        quietPocket(hero);
        targets.clear(); payloads.clear();
        int width = Dungeon.level.width();
        int first = hero.pos + width + 1; // Diagonal avoids the tomb's initial cardinal wraith spawns.
        int second = hero.pos - width + 1;
        switch (name) {
            case "heap-multi":
                place(new Food().quantity(2), first, Heap.Type.HEAP);
                place(new Gold().quantity(17), first, Heap.Type.HEAP);
                place(new ThrowingStone().quantity(3), first, Heap.Type.HEAP);
                targets.add(first);
                break;
            case "chest-hidden": {
                Item sword = new Longsword(); sword.level(2);
                Item armor = new ScaleArmor(); armor.level(4);
                place(sword, first, Heap.Type.CHEST);
                place(armor, second, Heap.Type.CHEST);
                targets.add(first); targets.add(second);
                break;
            }
            case "locked-chest": {
                Item sword = new Longsword(); sword.level(2);
                place(sword, first, Heap.Type.LOCKED_CHEST);
                place(new GoldenKey(Dungeon.depth), hero.pos - 1, Heap.Type.HEAP);
                targets.add(first);
                break;
            }
            case "crystal-chest": {
                Item firstWand = new WandOfMagicMissile(); firstWand.level(2);
                Item secondWand = new WandOfFireblast(); secondWand.level(4);
                place(firstWand, first, Heap.Type.CRYSTAL_CHEST);
                place(secondWand, second, Heap.Type.CRYSTAL_CHEST);
                place(new CrystalKey(Dungeon.depth).quantity(2), hero.pos - 1, Heap.Type.HEAP);
                targets.add(first); targets.add(second);
                break;
            }
            case "tomb": case "skeleton": case "remains": {
                Item sword = new Longsword(); sword.level(2);
                Heap.Type type = name.equals("tomb") ? Heap.Type.TOMB : name.equals("skeleton") ? Heap.Type.SKELETON : Heap.Type.REMAINS;
                place(sword, first, type);
                Dungeon.level.heaps.get(first).setHauntedIfCursed(); // Uncursed baseline, no invented haunt result.
                targets.add(first);
                break;
            }
            case "for-sale": {
                Dungeon.gold = 1000;
                Shopkeeper shopkeeper = new Shopkeeper(); shopkeeper.pos = hero.pos - width;
                GameScene.add(shopkeeper);
                place(new Food(), first, Heap.Type.FOR_SALE);
                targets.add(first);
                break;
            }
            case "iron-door": case "crystal-door":
                Level.set(first, name.equals("iron-door") ? Terrain.LOCKED_DOOR : Terrain.CRYSTAL_DOOR);
                place(name.equals("iron-door") ? new IronKey(Dungeon.depth) : new CrystalKey(Dungeon.depth), hero.pos - 1, Heap.Type.HEAP);
                targets.add(first);
                GameScene.updateMap(first);
                break;
            default: throw new IllegalArgumentException("Unknown container fixture");
        }
        Item.updateQuickslot();
        Dungeon.observe();
        hero.checkVisibleMobs();
    }

    private static void place(Item item, int cell, Heap.Type type) {
        Heap heap = Dungeon.level.drop(item, cell);
        heap.type = type;
        heap.seen = true;
        heap.sprite.link(heap);
        payloads.add(item);
    }

    static Map<String, Object> assertions() throws ReflectiveOperationException {
        Map<String, Object> heaps = new LinkedHashMap<>();
        Map<String, Object> terrain = new LinkedHashMap<>();
        List<Object> hidden = new ArrayList<>();
        for (int cell : targets) {
            terrain.put(Integer.toString(cell), Dungeon.level.map[cell]);
            Heap heap = Dungeon.level.heaps.get(cell);
            if (heap == null) heaps.put(Integer.toString(cell), null);
            else {
                List<Object> items = new ArrayList<>();
                for (Item item : heap.items) items.add(map("class", item.getClass().getSimpleName(),
                        "quantity", item.quantity(), "level", read(item, Item.class, "level"),
                        "level_known", item.levelKnown, "curse_known", item.cursedKnown));
                heaps.put(Integer.toString(cell), map("type", heap.type.name(), "haunted", heap.haunted, "items", items));
                if (heap.type != Heap.Type.HEAP && heap.type != Heap.Type.FOR_SALE)
                    for (Item item : heap.items) hidden.add(Messages.withLanguage(Languages.ENGLISH, item::trueName));
            }
        }
        Map<String, Object> keys = new LinkedHashMap<>();
        for (Notes.KeyRecord record : Notes.getRecords(Notes.KeyRecord.class)) {
            Key key = (Key) read(record, Notes.KeyRecord.class, "key");
            keys.put(key.getClass().getSimpleName() + ":" + key.depth, key.quantity());
        }
        return map("heaps", heaps, "terrain", terrain, "keys", keys, "unopened_payload_names", hidden,
                "payload_classes_different", payloads.stream().map(item -> item.getClass().getName()).distinct().count() > 1,
                "hero_clock", Statistics.duration + ((Number) read(Dungeon.hero, Actor.class, "time")).floatValue(),
                "wraith_count", Dungeon.level.mobs.stream().filter(mob -> mob instanceof Wraith).count());
    }

    private static Object read(Object object, Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private static void quietPocket(Hero hero) {
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
        Dungeon.level.customTiles.clear(); Dungeon.level.customWalls.clear();
        int width = Dungeon.level.width();
        int x = Math.max(4, Math.min(width - 5, hero.pos % width));
        int y = Math.max(4, Math.min(Dungeon.level.height() - 5, hero.pos / width));
        for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) {
            int cell = (y + dy) * width + x + dx;
            Level.set(cell, Math.abs(dx) == 3 || Math.abs(dy) == 3 ? Terrain.WALL : Terrain.EMPTY);
            GameScene.updateMap(cell);
        }
        hero.pos = y * width + x;
        hero.sprite.place(hero.pos);
    }
}
