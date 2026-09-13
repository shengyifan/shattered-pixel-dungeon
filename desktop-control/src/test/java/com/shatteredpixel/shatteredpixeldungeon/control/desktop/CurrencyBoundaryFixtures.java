package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.items.Ankh;
import com.shatteredpixel.shatteredpixeldungeon.items.EnergyCrystal;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;

/** Test-only starting conditions; currency updates and trading use the public protocol. */
final class CurrencyBoundaryFixtures {
    static boolean supports(String name) {
        return name.equals("entry-gold") || name.equals("entry-energy") || name.equals("shop-gold");
    }

    static void prepare(String name, Hero hero) {
        if (!supports(name)) throw new IllegalArgumentException("Unknown currency fixture");
        ContainerScenarioFixtures.quietPocket(hero);
        // The original GameScene owns the original CurrencyIndicator. Its first update
        // observes these nonzero counters and starts its normal finite display timer.
        Dungeon.gold = name.equals("entry-energy") ? 0 : 806;
        Dungeon.energy = name.equals("entry-energy") ? 3 : 0;
        place(new Gold(17), hero.pos - 1, Heap.Type.HEAP);
        place(new EnergyCrystal(2), hero.pos + 1, Heap.Type.HEAP);
        if (name.equals("shop-gold")) {
            Shopkeeper shopkeeper = new Shopkeeper();
            shopkeeper.pos = hero.pos - Dungeon.level.width();
            GameScene.add(shopkeeper);
            // Match the reported cancellation while standing on a for-sale Ankh.
            place(new Ankh(), hero.pos, Heap.Type.FOR_SALE);
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
    }
}
