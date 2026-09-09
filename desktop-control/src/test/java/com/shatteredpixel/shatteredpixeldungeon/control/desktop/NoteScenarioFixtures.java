package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** TEST ONLY. Normal Warrior setup; these values are postconditions, never action inputs. */
final class NoteScenarioFixtures {
    static boolean supports(String name) {
        return Arrays.asList("text", "floor", "inventory", "item-type", "item-shortcut").contains(name);
    }

    static Map<String, Object> assertions() throws ReflectiveOperationException {
        List<Object> records = new ArrayList<>();
        for (Notes.CustomRecord record : Notes.getRecords(Notes.CustomRecord.class)) {
            records.add(map("id", record.ID(), "type", read(record, Notes.CustomRecord.class, "type").toString(),
                    "depth", record.depth(), "title", record.title(), "body", record.desc()));
        }
        Map<String, Object> items = new LinkedHashMap<>();
        Hero hero = Dungeon.hero;
        if (hero != null && hero.belongings != null) {
            for (String slot : Arrays.asList("weapon", "armor", "artifact", "misc", "ring", "secondWep")) {
                Field field = hero.belongings.getClass().getField(slot);
                Item item = (Item) field.get(hero.belongings);
                if (item != null) items.put("equipment." + slot, item.customNoteID);
            }
            collect(hero.belongings.backpack, "backpack", items);
        }
        return map("records", records, "inventory_note_ids", items,
                "limit", Notes.customRecordLimit(), "next_custom_id", read(null, Notes.class, "nextCustomID"));
    }

    private static void collect(Bag bag, String path, Map<String, Object> items) {
        for (int i = 0; i < bag.items.size(); i++) {
            Item item = bag.items.get(i);
            String locator = path + "." + i;
            items.put(locator, item.customNoteID);
            if (item instanceof Bag) collect((Bag) item, locator, items);
        }
    }

    private static Object read(Object object, Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
