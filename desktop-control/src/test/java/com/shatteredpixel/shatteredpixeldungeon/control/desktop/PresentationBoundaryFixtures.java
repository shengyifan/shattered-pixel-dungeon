package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Eldritch;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Longsword;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SentryRoom;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.SentrySprite;

import java.util.Arrays;

/** Test-only native presentation sources; each transition is driven by a public action. */
final class PresentationBoundaryFixtures {
    static boolean supports(String name) {
        return Arrays.asList("giant-move", "growing-move", "chilled-first", "sentry-tail", "chest-glow").contains(name);
    }

    static void prepare(String name, Hero hero) {
        if (!supports(name)) throw new IllegalArgumentException("Unknown presentation fixture");
        int fps = Integer.parseInt(System.getenv().getOrDefault("SPDCTL_TEST_PRESENTATION_FPS", "60"));
        if (fps != 12 && fps != 60) throw new IllegalArgumentException("Presentation fixture FPS must be 12 or 60");
        Gdx.graphics.setForegroundFPS(fps);
        ContainerScenarioFixtures.quietPocket(hero);
        if (name.equals("chest-glow")) {
            Longsword sword = new Longsword();
            sword.enchant(new Eldritch());
            Heap heap = Dungeon.level.drop(sword, hero.pos + 1);
            heap.type = Heap.Type.CHEST;
            heap.seen = true;
            heap.sprite.link(heap);
        } else if (name.equals("sentry-tail")) {
            SentryRoom.Sentry sentry = new SentryRoom.Sentry();
            sentry.pos = hero.pos + 2;
            GameScene.add(sentry);
            // Begin with an actually drawn charge episode. The next ordinary Sentry.act
            // sees the hero on EMPTY and calls native idle(), leaving its finite tail.
            ((SentrySprite) sentry.sprite).charge();
        } else {
            Rat target = new Rat();
            target.pos = hero.pos + 2;
            target.state = target.PASSIVE;
            GameScene.add(target);
            if (name.equals("giant-move")) Buff.affect(target, ChampionEnemy.Giant.class);
            if (name.equals("growing-move")) Buff.affect(target, ChampionEnemy.Growing.class);
            // A one-shot test actor removes RNG/AI routing from this presentation test.
            // Its native position/buff changes happen only when the public wait resumes
            // Actor; the real sprite queue, tween, aura and particles then settle normally.
            Actor.add(new Actor() {
                { actPriority = MOB_PRIO - 1; }
                @Override protected boolean act() {
                    Actor.remove(this);
                    if (name.equals("chilled-first")) {
                        Buff.prolong(target, Chill.class, 20f);
                    } else {
                        int from = target.pos;
                        target.move(from - 1);
                        target.sprite.move(from, target.pos);
                    }
                    return true;
                }
            });
        }
        Dungeon.observe();
        hero.checkVisibleMobs();
    }
}
