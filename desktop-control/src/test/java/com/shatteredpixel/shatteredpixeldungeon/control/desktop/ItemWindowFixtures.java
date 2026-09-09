package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfDivination;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfAugmentation;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.utils.Reflection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Test-only source items; all selection, cancellation and consumption use the original UI. */
final class ItemWindowFixtures {
    private static boolean divination;
    static boolean supports(String name){return name.equals("augment-weapon")||name.equals("augment-armor")
            ||name.equals("divination-unknown")||name.equals("divination-known");}
    static void prepare(String name,Hero hero) {
        if(!supports(name))throw new IllegalArgumentException("Unknown item window fixture");
        ContainerScenarioFixtures.quietPocket(hero);
        divination=name.startsWith("divination-");
        Item source;
        if(divination) {
            // Only starting knowledge is prepared. The original READ chooses its own
            // random four types, displays its own window/log, and consumes the scroll.
            if(name.equals("divination-known")) {
                for(Class<? extends Potion> type:Potion.getUnknown())Reflection.newInstance(type).setKnown();
                for(Class<? extends Scroll> type:Scroll.getUnknown())Reflection.newInstance(type).setKnown();
                for(Class<? extends Ring> type:Ring.getUnknown())Reflection.newInstance(type).setKnown();
            }
            ScrollOfDivination scroll=new ScrollOfDivination();scroll.setKnown();source=scroll.quantity(2);
            int unknown=unknownCount();
            if(name.equals("divination-known")?unknown!=0:unknown<4)
                throw new IllegalStateException("Divination starting knowledge does not match this scenario");
        } else source=new StoneOfAugmentation().quantity(3);
        if(!source.collect(hero.belongings.backpack))
            throw new IllegalStateException("Fixture source inventory is full");
        Item.updateQuickslot();Dungeon.observe();hero.checkVisibleMobs();
    }
    static Map<String,Object> assertions() {
        Hero hero=Dungeon.hero;
        Map<String,Object> result=map("weapon_augment",((Weapon)hero.belongings.weapon()).augment.name(),
                "armor_augment",hero.belongings.armor().augment.name(),
                "augmentation_stones",hero.belongings.getAllItems(StoneOfAugmentation.class).stream().mapToInt(Item::quantity).sum());
        if(divination)result.put("divination",map("scrolls",hero.belongings.getAllItems(ScrollOfDivination.class).stream().mapToInt(Item::quantity).sum(),
                "unknown_count",unknownCount(),"known_types",knownTypes()));
        return result;
    }
    private static int unknownCount(){return Potion.getUnknown().size()+Scroll.getUnknown().size()+Ring.getUnknown().size();}
    private static Map<String,String> knownTypes() {
        // Postcondition only: no unknown identities, future Random choice, or new Item
        // instances are read/created while the test observer records an existing state.
        List<Class<?>> known=new ArrayList<>();known.addAll(Potion.getKnown());known.addAll(Scroll.getKnown());known.addAll(Ring.getKnown());
        Map<String,String> names=new TreeMap<>();
        for(Class<?> type:known)names.put(type.getName(),Messages.withLanguage(Languages.ENGLISH,()->Messages.get(type,"name")));
        return names;
    }
}
