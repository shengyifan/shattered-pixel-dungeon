package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.AttackIndicator;

/** Test-only initial enemies. Kills, movement and version checks use the public protocol. */
final class AttackBoundaryFixtures {
    static boolean supports(String name) {
        return name.equals("attack-last") || name.equals("attack-remaining");
    }

    static void prepare(String name, Hero hero) {
        ContainerScenarioFixtures.quietPocket(hero);
        addRat(hero.pos - 1);
        if (name.equals("attack-remaining")) addRat(hero.pos + 1);
        Dungeon.observe();
        hero.checkVisibleMobs();
        AttackIndicator.updateState();
    }

    private static void addRat(int cell) {
        Rat rat = new Rat();
        rat.pos = cell;
        rat.HP = 1;
        GameScene.add(rat);
        Buff.prolong(rat, Paralysis.class, 100f);
    }
}
