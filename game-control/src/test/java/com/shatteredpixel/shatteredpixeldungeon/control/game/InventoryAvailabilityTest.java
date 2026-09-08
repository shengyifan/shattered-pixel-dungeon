package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAvailabilityTest {
    @BeforeAll static void assets(){GameSnapshotterTest.resourceOnlyRuntime();}
    @AfterEach void clear(){Dungeon.hero=null;Dungeon.level=null;}

    @Test void lostItemsRemainVisibleButOnlyKeptItemsCanBeOpened(){
        Hero hero=new Hero();Dungeon.hero=hero;
        ClothArmor armor=new ClothArmor();hero.belongings.armor=armor;
        Food lost=new Food(),kept=new Food();kept.keptThoughLostInvent=true;
        Bag bag=new Bag();bag.items.add(lost);bag.items.add(kept);hero.belongings.backpack.items.add(bag);
        hero.belongings.lostInventory(true);

        List<?> inventory=(List<?>)PlayerObservation.capture(hero,null,"game").get("inventory");
        assertFalse(available(inventory,"equipment.armor"));
        assertFalse(available(inventory,"backpack.0.0"));
        assertTrue(available(inventory,"backpack.0.1"));
        assertSame(lost,PlayerObservation.resolveItem(hero,"backpack.0.0"));
        assertFalse(PlayerObservation.inventoryItemAvailable(hero,lost));
        assertTrue(PlayerObservation.inventoryItemAvailable(hero,kept));
        assertTrue(hero.belongings.lostInventory());
        assertFalse(lost.keptThoughLostInvent);
        assertTrue(kept.keptThoughLostInvent);
        assertEquals(2,bag.items.size());
    }

    @Test void recoveringBackpackRestoresAvailabilityWithoutChangingIdentity(){
        Hero hero=new Hero();Dungeon.hero=hero;
        Item item=new Food();hero.belongings.backpack.items.add(item);
        hero.belongings.lostInventory(true);
        assertFalse(PlayerObservation.inventoryItemAvailable(hero,item));
        hero.belongings.lostInventory(false);
        List<?> inventory=(List<?>)PlayerObservation.capture(hero,null,"game").get("inventory");
        assertTrue(available(inventory,"backpack.0"));
        assertSame(item,PlayerObservation.resolveItem(hero,"backpack.0"));
        assertTrue(PlayerObservation.inventoryItemAvailable(hero,item));
        assertFalse(PlayerObservation.inventoryItemAvailable(hero,null));
        assertFalse(PlayerObservation.inventoryItemAvailable(null,item));
    }

    private static boolean available(List<?> items,String locator){
        for(Object value:items){Map<?,?> item=(Map<?,?>)value;if(locator.equals(item.get("locator")))return Boolean.TRUE.equals(item.get("available"));}
        fail("Missing visible inventory item "+locator);return false;
    }
}
