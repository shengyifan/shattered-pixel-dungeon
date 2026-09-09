package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DwarfKing;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;

import java.lang.reflect.Field;
import java.util.Arrays;

/** Isolated setup only; the original Summoning Buff owns effects, countdown and spawning. */
final class DwarfVisualFixtures {
    static boolean supports(String name) { return Arrays.asList("visual-king", "visual-king-hidden").contains(name); }

    static void prepare(String name, Hero hero) throws Exception {
        DwarfKing boss = new DwarfKing();
        set(boss, "summonCooldown", 1000f);
        set(boss, "abilityCooldown", 1000f);
        boss.pos = hero.pos + Dungeon.level.width() * 3;
        GameScene.add(boss);
        // Isolate the existing Summoning sequence from additional unrelated boss attacks.
        Buff.prolong(boss, Paralysis.class, 1000f);
        Class<?>[] types = {DwarfKing.DKGhoul.class, DwarfKing.DKWarlock.class,
                DwarfKing.DKMonk.class, DwarfKing.DKGolem.class};
        int[] cells = {hero.pos - 2, hero.pos + 2, hero.pos - Dungeon.level.width()*2,
                hero.pos - Dungeon.level.width() + 2};
        if (name.endsWith("-hidden")) {
            for (int y = 1; y < Dungeon.level.height()-1; y++) {
                int cell = y*Dungeon.level.width() + hero.pos%Dungeon.level.width()+1;
                Level.set(cell, Terrain.WALL); GameScene.updateMap(cell);
            }
            Dungeon.observe();
            Arrays.fill(cells, hero.pos + 3);
            if (Dungeon.level.heroFOV[cells[0]]) throw new IllegalStateException("Hidden fixture is visible");
        }
        for (int i=0; i<types.length; i++) {
            DwarfKing.Summoning summon = new DwarfKing.Summoning();
            set(summon, "delay", name.endsWith("-hidden") ? 60 : 3);
            set(summon, "pos", cells[i]); set(summon, "summon", types[i]);
            summon.attachTo(boss);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
}
