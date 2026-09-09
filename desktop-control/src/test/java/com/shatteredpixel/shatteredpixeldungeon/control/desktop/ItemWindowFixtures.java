package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfAugmentation;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Test-only source items; all selection, cancellation and consumption use the original UI. */
final class ItemWindowFixtures {
    static boolean supports(String name){return name.equals("augment-weapon")||name.equals("augment-armor");}
    static void prepare(String name,Hero hero) {
        if(!supports(name))throw new IllegalArgumentException("Unknown item window fixture");
        ContainerScenarioFixtures.quietPocket(hero);
        if(!new StoneOfAugmentation().quantity(3).collect(hero.belongings.backpack))
            throw new IllegalStateException("Fixture source inventory is full");
        Item.updateQuickslot();Dungeon.observe();hero.checkVisibleMobs();
    }
    static Map<String,Object> assertions() {
        Hero hero=Dungeon.hero;
        return map("weapon_augment",((Weapon)hero.belongings.weapon()).augment.name(),
                "armor_augment",hero.belongings.armor().augment.name(),
                "augmentation_stones",hero.belongings.getAllItems(StoneOfAugmentation.class).stream().mapToInt(Item::quantity).sum());
    }
}
