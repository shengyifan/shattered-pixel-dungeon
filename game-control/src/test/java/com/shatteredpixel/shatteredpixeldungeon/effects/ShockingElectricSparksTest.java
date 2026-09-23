package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.SparkParticle;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Shocking;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.PathFinder;
import com.watabou.utils.PointF;
import com.watabou.utils.Random;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShockingElectricSparksTest {
    @Test void initialSparkBurstRemainsKnownWithoutAnyChainArc() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.arcWithoutNextTarget();
            f.assertNativeEmission();
            CellParticleCue signal = f.signal();
            assertEquals("electric_sparks", signal.kind);
            assertFalse(GameplayVisualKinds.affectsIntent(signal.kind));
            assertEquals(22, signal.observedCell());
            assertNull(f.sprite.ch, "Capture has no actor or enchantment binding");

            Visual particle = f.contributor(22);
            assertEquals(Collections.singletonList(new VisualCue("electric_sparks", 22)), f.frame());
            assertNull(f.cues().get(0).appearance, "Only the public cell and generic effect meaning survive");
            assertNull(f.cues().get(0).sourceCell, "No attacker or chain endpoint is exposed");
            f.level.heroFOV[22] = false;
            assertTrue(f.frame().isEmpty(), "A source in fog cannot retain a visible electric reaction");
            f.level.heroFOV[22] = true;
            f.sprite.cell = 23;
            particle.x += 16;
            assertEquals(Collections.singletonList(new VisualCue("electric_sparks", 23)), f.frame());
            f.sprite.revive();
            assertTrue(f.frame().isEmpty(), "Sprite reuse ends the old source binding");
        }
    }

    @Test void disabledObservationLeavesTheSameNativeBurstWithoutAHook() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.arcWithoutNextTarget();
            f.assertNativeEmission();
            assertNull(f.signal());
        }
    }

    private static final class SignalSprite extends CharSprite {
        final Group owner;
        int cell = 22;
        Emitter sparks;
        SignalSprite(Group owner) { this.owner = owner; }
        @Override public Emitter centerEmitter() {
            sparks = new Emitter();
            owner.add(sparks);
            return sparks;
        }
        @Override public int renderedCell() { return cell; }
    }

    private static final class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final Level previousLevel = Dungeon.level;
        final RuntimeObserver previousObserver = Game.observer;
        final Map<Field, Object> pathFinderState = new LinkedHashMap<>();
        final SignalSprite sprite = new SignalSprite(scene);

        Fixture(boolean observe) throws Exception {
            for (Field field : PathFinder.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    field.setAccessible(true);
                    pathFinderState.put(field, field.get(null));
                }
            }
            PathFinder.setMapSize(level.width(), level.height());
            Dungeon.level = level;
            level.solid = new boolean[level.length()];
            Arrays.fill(level.solid, true);
            level.solid[22] = false;
            Game.observer = observe ? new RuntimeObserver() {
                @Override public boolean observesVisualCues() { return true; }
            } : RuntimeObserver.NONE;
            scene.add(sprite);
            Random.pushGenerator(1801);
        }

        void arcWithoutNextTarget() {
            Char defender = new Char() {};
            defender.pos = 22;
            defender.sprite = sprite;
            ArrayList<Char> affected = new ArrayList<>(Actor.chars());
            ArrayList<Lightning.Arc> arcs = new ArrayList<>();
            Shocking.arc(new Char() {}, defender, 2, affected, arcs);
            assertTrue(arcs.isEmpty(), "The initial emission must not require any Lightning.Arc");
        }

        void assertNativeEmission() throws Exception {
            assertTrue(sprite.sparks.isEmitting(SparkParticle.FACTORY));
            assertEquals(0f, field(Emitter.class, "interval").getFloat(sprite.sparks));
            assertEquals(3, field(Emitter.class, "quantity").getInt(sprite.sparks));
            assertEquals(0, sprite.sparks.countLiving(), "Attaching the observer must not emit particles");
        }

        CellParticleCue signal() throws Exception {
            return (CellParticleCue) field(Emitter.class, "drawObserver").get(sprite.sparks);
        }

        Visual contributor(int cell) throws Exception {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Object allocator = field(unsafe, "theUnsafe").get(null);
            Visual particle = (Visual) unsafe.getMethod("allocateInstance", Class.class).invoke(allocator, SparkParticle.class);
            particle.exists = particle.alive = particle.active = particle.visible = true;
            particle.x = (cell % level.width()) * 16 + 3;
            particle.y = (cell / level.width()) * 16 + 3;
            particle.width = particle.height = 1;
            particle.scale = new PointF(1, 1);
            particle.origin = new PointF();
            particle.resetColor();
            ((Image) particle).texture = (SmartTexture) unsafe.getMethod("allocateInstance", Class.class)
                    .invoke(allocator, SmartTexture.class);
            sprite.sparks.add(particle);
            return particle;
        }

        List<VisualCue> frame() throws Exception {
            clear();
            ((List<?>) GameplayVisualTraversalTest.get(collector, "particleObservations")).clear();
            collector.particleEmitterDrawn(sprite.sparks, signal());
            assertTrue((Boolean) GameplayVisualTraversalTest.method("finishParticleDraws").invoke(collector));
            return cues();
        }

        @Override public void close() throws Exception {
            Random.popGenerator();
            Dungeon.level = previousLevel;
            Game.observer = previousObserver;
            for (Map.Entry<Field, Object> entry : pathFinderState.entrySet()) entry.getKey().set(null, entry.getValue());
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
