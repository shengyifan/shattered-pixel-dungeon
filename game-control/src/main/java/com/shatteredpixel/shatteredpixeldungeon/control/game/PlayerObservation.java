package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ShieldBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Inferno;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.SacrificialFire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.VaultFlameTraps;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.CorrosiveGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ConfusionGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ParalyticGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.StenchGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.SmokeScreen;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.StormCloud;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Freezing;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blizzard;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Electricity;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Web;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Foliage;
import com.shatteredpixel.shatteredpixeldungeon.effects.EmoIcon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.AscendedForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.DivineIntervention;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.EbonyMimic;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.Artifact;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Pasty;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.Trinket;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.utils.Holiday;
import com.watabou.noosa.Gizmo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;

/**
 * The only game-model-to-public-data boundary. Never serialize the backing objects.
 * Unknown and unsupported details are omitted rather than inferred from private data.
 */
public final class PlayerObservation {
    private PlayerObservation() {}

    public static Map<String, Object> capture(Hero hero, Level level, String scene) {
        Map<String, Object> out = map("scene", scene,
                "coverage", map("status", "observation_with_inspection",
                        "details_via", map("equipment_stats_and_buff_durations", "ui.activate",
                                "journal_and_catalogue", "ui.activate",
                                "custom_terrain_descriptions", map("action", "cell.select", "mode", "examine")),
                        "inspection_policy", "Use current ui controls and their action descriptors; details are read from displayed windows"));
        if (hero == null || !"game".equals(scene)) return out;
        // Preserve the GUI's selected display branch. Frozen sources are rendered later.
        {
            out.put("hero", hero(hero));
            out.put("inventory", inventory(hero));
            if (level != null && level.map != null) {
                out.put("map", terrain(level));
                out.put("visible_entities", entities(hero, level));
            }
            return out;
        }
    }

    private static Map<String, Object> hero(Hero hero) {
        List<Object> talents = new ArrayList<>();
        List<Integer> availableTalentPoints = new ArrayList<>();
        for (int tier = 0; tier < hero.talents.size(); tier++) {
            // Use the same read-only rule as the talent window, including bonuses and gates.
            availableTalentPoints.add(hero.talentPointsAvailable(tier + 1));
            for (Map.Entry<Talent, Integer> talent : hero.talents.get(tier).entrySet()) {
                talents.add(map("tier", tier + 1, "name", talent.getKey().title(),
                        "points", talent.getValue()));
            }
        }
        return map("cell", hero.pos, "class", hero.heroClass.name().toLowerCase(java.util.Locale.ROOT),
                "class_name", hero.heroClass.title(), "subclass", hero.subClass == null ? "none" : hero.subClass.name().toLowerCase(java.util.Locale.ROOT),
                "subclass_name", hero.subClass == null || hero.subClass == HeroSubClass.NONE ? null : hero.subClass.title(), "level", hero.lvl, "experience", hero.exp,
                "max_experience", hero.maxExp(), "hp", hero.HP, "max_hp", hero.HT,
                "shield", displayedShield(hero), "base_strength", hero.STR, "strength", hero.STR(), "ready", hero.ready,
                "gold", Dungeon.gold, "energy", Dungeon.energy, "depth", Dungeon.depth,
                "talents", talents, "talent_points_available", availableTalentPoints, "buffs", buffs(hero));
    }

    /** Pure equivalent of the displayed shield total, including subclass validity conditions. */
    public static int displayedShield(Char character) {
        if (!character.needsShieldUpdate) return SnapshotFields.integer(character, "cachedShield");
        int value = 0;
        // Char.shielding() updates a cache. Read the backing shield fields instead.
        for (Buff buff : character.buffs()) {
            if (buff instanceof DivineIntervention.DivineShield
                    && (Dungeon.hero == null || Dungeon.hero.buff(AscendedForm.AscendBuff.class) == null)) continue;
            if (buff instanceof ShieldBuff) value += SnapshotFields.integer(buff, "shielding");
        }
        return value;
    }

    private static List<Object> buffs(Char character) {
        List<Object> result = new ArrayList<>();
        for (Buff buff : character.buffs()) {
            if (buff.icon() != BuffIndicator.NONE) {
                result.add(map("name", buff.name(), "icon", buff.icon(), "details_via", "ui.activate"));
            }
        }
        return result;
    }

    private static List<Object> inventory(Hero hero) {
        List<Object> result = new ArrayList<>();
        if (hero.belongings == null) return result;
        for (String slot : Arrays.asList("weapon", "armor", "artifact", "misc", "ring", "secondWep")) {
            Item item = (Item) SnapshotFields.read(hero.belongings, slot);
            if (item != null) result.add(inventoryItem(hero, item, "equipment." + slot, true));
        }
        Set<Item> traversed = Collections.newSetFromMap(new IdentityHashMap<>());
        bag(hero, hero.belongings.backpack, "backpack", result, traversed);
        return result;
    }

    /** Same LostInventory gate as the visible InventorySlot, for a resolved current inventory item. */
    public static boolean inventoryItemAvailable(Hero hero, Item item) {
        return hero != null && hero.belongings != null && item != null
                && (!hero.belongings.lostInventory() || item.keptThroughLostInventory());
    }

    private static Map<String,Object> inventoryItem(Hero hero, Item item, String locator, boolean equipped) {
        Map<String,Object> result = item(item, locator, equipped);
        result.put("available", inventoryItemAvailable(hero, item));
        return result;
    }

    /** Resolve only a current player inventory slot, never a class name or internal actor ID. */
    public static Item resolveItem(Hero hero, String locator) {
        if (hero == null || hero.belongings == null || locator == null) return null;
        if (locator.startsWith("equipment.")) {
            String slot = locator.substring("equipment.".length());
            if (!Arrays.asList("weapon", "armor", "artifact", "misc", "ring", "secondWep").contains(slot)) return null;
            return (Item) SnapshotFields.read(hero.belongings, slot);
        }
        if (!locator.matches("backpack(?:\\.[0-9]+)+")) return null;
        Item current = hero.belongings.backpack;
        for (String part : locator.substring("backpack.".length()).split("\\.")) {
            if (!(current instanceof Bag)) return null;
            int index;
            try { index = Integer.parseInt(part); }
            catch (NumberFormatException invalid) { return null; }
            List<Item> items = ((Bag) current).items;
            if (index < 0 || index >= items.size()) return null;
            current = items.get(index);
        }
        return current;
    }

    private static void bag(Hero hero, Bag bag, String path, List<Object> items, Set<Item> traversed) {
        if (bag == null || !traversed.add(bag)) return;
        for (int i = 0; i < bag.items.size(); i++) {
            Item item = bag.items.get(i);
            String locator = path + "." + i;
            items.add(inventoryItem(hero, item, locator, false));
            if (item instanceof Bag) bag(hero, (Bag) item, locator, items, traversed);
        }
    }

    public static Map<String, Object> item(Item item, String locator, boolean equipped) {
        return itemInCurrentLanguage(item, locator, equipped);
    }

    /** Minimal knowledge about the description already composed by an item-info window. */
    public static Map<String,Object> inspectedItemKnowledge(Item item,Boolean levelKnownWhenRendered) {
        if(item==null)return null;
        return map("level_known",levelKnowledgeApplicable(item)?levelKnownWhenRendered:null);
    }

    private static boolean levelKnowledgeApplicable(Item item) {
        return item instanceof EquipableItem || item instanceof Wand || item instanceof Trinket || item instanceof BrokenSeal;
    }

    private static Map<String, Object> itemInCurrentLanguage(Item item, String locator, boolean equipped) {
        boolean typeKnown = !(item instanceof Potion || item instanceof Scroll || item instanceof Ring)
                || (item instanceof Potion && ((Potion) item).isKnown())
                || (item instanceof Scroll && ((Scroll) item).isKnown())
                || (item instanceof Ring && ((Ring) item).isKnown());
        boolean curseApplicable = item instanceof EquipableItem || item instanceof Wand;
        boolean levelApplicable = levelKnowledgeApplicable(item);
        Map<String, Object> result = map("locator", locator, "name", displayItemName(item),
                "quantity", item.quantity(), "equipped", equipped,
                "type_known", typeKnown, "level_known", levelApplicable ? item.levelKnown : null,
                "curse_known", curseApplicable ? item.cursedKnown : null,
                "level", levelApplicable && item.levelKnown ? displayedLevel(item) : null,
                "cursed", curseApplicable && item.cursedKnown ? item.cursed : null, "details_via", "ui.activate");
        // These three families implement display descriptions with the per-run identity gate.
        if (item instanceof Potion || item instanceof Scroll || item instanceof Ring) {
            result.put("description", item.desc());
        }
        if (item instanceof Wand && item.levelKnown) {
            boolean known = (Boolean) SnapshotFields.read(item, "curChargeKnown");
            result.put("charges", map("known", known,
                    "current", known ? SnapshotFields.integer(item, "curCharges") : null,
                    "maximum", SnapshotFields.integer(item, "maxCharges")));
        }
        return result;
    }

    /** Shared with the UI bridge for item hover labels that must not populate game caches. */
    public static String displayItemName(Item item) {
        return itemNameInCurrentLanguage(item);
    }

    private static String itemNameInCurrentLanguage(Item item) {
        if (!(item instanceof Pasty)) return item.name();
        // Pasty.name() would fill Holiday.cached when empty. Compute its existing display
        // selection without changing that cache or any gameplay state.
        Holiday holiday = (Holiday) SnapshotFields.read(Holiday.class, "cached");
        if (holiday == null) holiday = Holiday.getHolidayForDate(new GregorianCalendar());
        String key;
        switch (holiday) {
            case LUNAR_NEW_YEAR: key = "fish_name"; break;
            case APRIL_FOOLS: key = "amulet_name"; break;
            case EASTER: key = "egg_name"; break;
            case PRIDE: key = "rainbow_name"; break;
            case SHATTEREDPD_BIRTHDAY: key = "shattered_name"; break;
            case HALLOWEEN: key = "pie_name"; break;
            case PD_BIRTHDAY: key = "vanilla_name"; break;
            case WINTER_HOLIDAYS: key = "cane_name"; break;
            case NEW_YEARS: key = "sparkling_name"; break;
            default: key = "name";
        }
        return Messages.get(item, key);
    }

    private static int displayedLevel(Item item) {
        int raw = SnapshotFields.integer(item, "level");
        if (item instanceof SpiritBow) raw = Dungeon.hero == null ? 0 : Dungeon.hero.lvl / 5;
        if (item instanceof Artifact) {
            return Math.round((raw * 10) / (float) SnapshotFields.integer(item, "levelCap"));
        }
        if (item instanceof Wand) {
            // Match the final displayed value without Wand.level()'s lazy mutation.
            if (item.cursed && (Boolean) SnapshotFields.read(item, "curseInfusionBonus")) raw += 1 + raw / 6;
            return raw + SnapshotFields.integer(item, "resinBonus");
        }
        if (item instanceof Weapon || item instanceof Armor) {
            if ((Boolean) SnapshotFields.read(item, "curseInfusionBonus")) raw += 1 + raw / 6;
        }
        return raw;
    }

    private static Map<String, Object> terrain(Level level) {
        List<Object> cells = new ArrayList<>();
        for (int cell = 0; cell < level.map.length; cell++) {
            boolean visible = flag(level.heroFOV, cell);
            boolean visited = flag(level.visited, cell);
            boolean mapped = flag(level.mapped, cell);
            if (!visible && !visited && !mapped) continue;
            int appearance = terrainAppearance(level.map[cell]);
            cells.add(map("cell", cell, "x", cell % level.width(), "y", cell / level.width(),
                    "visibility", visible ? "visible" : visited ? "visited" : "mapped",
                    "terrain", appearance, "name", level.tileName(appearance),
                    "environment", visibleEnvironment(level, cell)));
        }
        return map("width", level.width(), "height", level.height(), "unknown", "omitted",
                "cells", cells);
    }

    static int terrainAppearance(int tile) {
        if (tile == Terrain.SECRET_DOOR) return Terrain.WALL;
        if (tile == Terrain.SECRET_TRAP) return Terrain.EMPTY;
        if (tile == Terrain.HERO_LKD_DR) return Terrain.LOCKED_DOOR;
        if (tile == Terrain.CUSTOM_DECO_EMPTY) return Terrain.EMPTY;
        return tile;
    }

    private static List<Object> entities(Hero hero, Level level) {
        List<Object> entities = new ArrayList<>();
        List<Mob> mobs = level.mobs == null ? new ArrayList<>() : new ArrayList<>(level.mobs);
        mobs.sort(Comparator.comparingInt(mob -> mob.pos));
        for (Mob mob : mobs) {
            boolean rememberedDisguise = mob instanceof Mimic && mob.alignment == Char.Alignment.NEUTRAL && mob.state == mob.PASSIVE
                    && ((Mimic) mob).stealthy() && flag(level.visited, mob.pos);
            if (mob.pos == hero.pos || (!flag(level.heroFOV, mob.pos) && !rememberedDisguise)) continue;
            // FOV alone does not reveal a presentation deliberately hidden by its native producer
            // (for example the City's boss before its reveal fade). Camera clipping is unrelated.
            if (!rememberedDisguise && mob.sprite != null
                    && (!mob.sprite.exists || !mob.sprite.visible || !Float.isFinite(mob.sprite.am+mob.sprite.aa)
                    || mob.sprite.am+mob.sprite.aa <= 0)) continue;
            if (mob instanceof Mimic && mob.alignment == Char.Alignment.NEUTRAL) {
                // A disguised mob must have the same public shape as a displayed container.
                // Ebony's displayed outline does not disclose that it is a treasure container.
                entities.add(map("cell", mob.pos, "kind", mob instanceof EbonyMimic ? "object" : "container", "name", mob.name(),
                        "description", mob.description()));
            } else {
                entities.add(map("cell", mob.pos, "kind", "character", "name", mob.name(),
                        "buffs", buffs(mob), "context_action", mob.alignment == Char.Alignment.ENEMY ? "attack" : "interact",
                        "emotion", visibleEmotion(mob)));
            }
        }
        for (int cell = 0; cell < level.map.length; cell++) {
            if (!flag(level.visited, cell) && !flag(level.mapped, cell)) continue;
            Heap heap = level.heaps == null ? null : level.heaps.get(cell);
            if (heap != null && heap.seen && !heap.items.isEmpty()) {
                if (heap.type == Heap.Type.HEAP || heap.type == Heap.Type.FOR_SALE) {
                    entities.add(map("cell", cell, "kind", "item", "item",
                            item(heap.items.getFirst(), "floor." + cell, false)));
                } else {
                    entities.add(map("cell", cell, "kind", "container", "name", heap.title(),
                            "description", heap.info()));
                }
            }
            Trap trap = level.traps == null ? null : level.traps.get(cell);
            if (trap != null && trap.visible) entities.add(map("cell", cell, "kind", "trap",
                    "name", trap.name(), "description", trap.desc()));
            Plant plant = level.plants == null ? null : level.plants.get(cell);
            if (plant != null) entities.add(map("cell", cell, "kind", "plant", "name", plant.name()));
        }
        entities.sort(Comparator.comparingInt(value -> ((Number) ((Map<?, ?>) value).get("cell")).intValue()));
        return entities;
    }

    private static List<Object> visibleEnvironment(Level level, int cell) {
        List<Object> effects = new ArrayList<>();
        if (!flag(level.heroFOV, cell) || level.blobs == null) return effects;
        // Exactly WndInfoCell's inspection gate. Amounts and timers are intentionally absent.
        for (Blob blob : level.blobs.values()) {
            if (blob.volume <= 0 || blob.cur == null || cell >= blob.cur.length || blob.cur[cell] <= 0) continue;
            String description = blob.tileDesc();
            if (description != null) effects.add(map("type", environmentAppearance(blob), "description", description));
        }
        effects.sort(Comparator.comparing(effect -> ((Map<?, ?>) effect).get("type") + "\n"
                + ((Map<?, ?>) effect).get("description")));
        return effects;
    }

    private static String environmentAppearance(Blob blob) {
        if (blob instanceof Fire || blob instanceof Inferno || blob instanceof SacrificialFire || blob instanceof VaultFlameTraps) return "fire";
        if (blob instanceof ToxicGas || blob instanceof CorrosiveGas || blob instanceof ConfusionGas
                || blob instanceof ParalyticGas || blob instanceof StenchGas || blob instanceof SmokeScreen || blob instanceof StormCloud) return "gas";
        if (blob instanceof Freezing || blob instanceof Blizzard) return "cold";
        if (blob instanceof Electricity) return "electricity";
        if (blob instanceof Web) return "web";
        if (blob instanceof Foliage) return "growth";
        return "visible_effect";
    }

    private static String visibleEmotion(Mob mob) {
        if (mob.sprite == null || !shown(mob.sprite)) return null;
        Object current = SnapshotFields.read(mob.sprite, "emo");
        if (!(current instanceof Gizmo) || !shown((Gizmo) current)) return null;
        if (current instanceof EmoIcon.Sleep) return "sleeping";
        if (current instanceof EmoIcon.Alert) return "alert";
        if (current instanceof EmoIcon.Investigate) return "investigating";
        if (current instanceof EmoIcon.Lost) return "lost";
        return null;
    }

    private static boolean shown(Gizmo gizmo) {
        for (Gizmo current = gizmo; current != null; current = current.parent) {
            if (!current.exists || !current.alive || !current.visible) return false;
        }
        return true;
    }

    private static boolean flag(boolean[] values, int index) {
        return values != null && index >= 0 && index < values.length && values[index];
    }
}
