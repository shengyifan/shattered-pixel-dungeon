package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Goo;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Tengu;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.YogDzewa;
import com.shatteredpixel.shatteredpixeldungeon.effects.CellEmitter;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Swiftthistle;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GooSprite;
import com.watabou.noosa.particles.Emitter;

import java.lang.reflect.Field;
import java.util.Arrays;

/** Test-source-only initial states. Actions and warning reads after setup use the public protocol. */
final class VisualCueFixtures {
    static boolean supports(String name) { return Arrays.asList("visual-red", "visual-goo", "visual-hidden", "visual-frozen", "visual-bomb", "visual-bomb-hidden", "visual-traps", "visual-traps-hidden").contains(name); }

    static void prepare(String name, Hero hero) throws Exception {
        if (name.equals("visual-traps") || name.equals("visual-traps-hidden")) {
            TenguTrapFixtures.prepare(name.endsWith("-hidden"), hero);
        } else if (name.equals("visual-red")) {
            YogDzewa boss = new YogDzewa();
            set(boss, "phase", 1);
            set(boss, "abilityCooldown", 0f);
            set(boss, "summonCooldown", 20f);
            boss.state = boss.HUNTING; boss.pos = hero.pos+3;
            GameScene.add(boss);
        } else if (name.equals("visual-bomb")) {
            Tengu boss = new Tengu();
            boss.HP = boss.HT/2;
            set(boss, "abilityCooldown", 1);
            boss.state = boss.HUNTING; boss.pos = hero.pos+3;
            GameScene.add(boss);
        } else if (name.equals("visual-goo") || name.equals("visual-frozen")) {
            Goo boss = new Goo();
            set(boss, "pumpedUp", 1); // First legitimate charging stage; native next attack expands the warning.
            boss.state = boss.HUNTING; boss.pos = hero.pos+1;
            GameScene.add(boss);
            if (name.equals("visual-frozen")) Buff.affect(hero, Swiftthistle.TimeBubble.class).reset(6);
        } else if (name.equals("visual-hidden") || name.equals("visual-bomb-hidden")) {
            int width = Dungeon.level.width();
            for (int y = 1; y < Dungeon.level.height()-1; y++) {
                int cell = y*width + hero.pos%width+1;
                Level.set(cell, Terrain.WALL); GameScene.updateMap(cell);
            }
            Dungeon.observe();
            int hidden = hero.pos+3;
            if (Dungeon.level.heroFOV[hidden]) throw new IllegalStateException("Fixture did not block the hidden emitter's FOV");
            if (name.equals("visual-bomb-hidden")) {
                Buff.append(hero, Tengu.BombAbility.class).bombPos = hidden;
                return;
            }
            Emitter emitter = CellEmitter.get(hidden);
            emitter.startDelayed(GooSprite.GooParticle.FACTORY, 0.04f, 0, 60f);
            emitter.observeDraw(new GooSprite.GooDrawObserver(hidden));
        } else throw new IllegalArgumentException("Unknown visual fixture");
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
}
