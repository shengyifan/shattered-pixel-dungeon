package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.Visual;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.PointF;
import com.watabou.utils.Random;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Cli9ItemEffectsTest {
    @Test void eldritchAuraUsesTheActualFiveRayRedDisplayWithoutDisclosingVictimsOrDuration() throws Exception {
        Flare flare = allocate(Flare.class);
        field(flare, Flare.class, "nRays", 5);
        flare.scale = new PointF(1, 1);
        flare.resetColor();
        flare.color(0xFF0000, true).observeCharacterAura();
        assertEquals(Map.of("variant", "eldritch"), flare.auraFact());
        flare.angle = 174;
        flare.angularSpeed = 330;
        flare.scale.set(.25f);
        flare.alpha(.6f);
        assertEquals(Map.of("variant", "eldritch"), flare.auraFact());
        field(flare, Flare.class, "nRays", 4);
        assertTrue(flare.auraFact().containsKey("unmapped_indicator"));
    }

    @Test void crystalCellSplashPreservesNativeFactoryPositionQuantityRngAndScope() throws Exception {
        RuntimeObserver priorObserver = Game.observer;
        Level priorLevel = Dungeon.level;
        Object priorScene = read(null, GameScene.class, "scene");
        try {
            GameplayVisualTraversalTest.Fixture fixture = new GameplayVisualTraversalTest.Fixture();
            Dungeon.level = fixture.level;
            Group emitters = new Group();
            fixture.scene.add(emitters);
            field(fixture.scene, GameScene.class, "emitters", emitters);
            field(null, GameScene.class, "scene", fixture.scene);
            Game.observer = new RuntimeObserver() {
                @Override public boolean observesVisualCues() { return true; }
            };
            Random.pushGenerator(490);
            long tagged;
            try {
                GameplayBurst.splash(22, 0x0088FF, 15, "crystal_shatter", false);
                tagged = Random.Long();
            } finally { Random.popGenerator(); }
            Emitter observed = (Emitter) emitters.childrenSnapshot().get(0);
            assertNotNull(read(observed, Emitter.class, "drawObserver"));
            assertEquals(15, read(observed, Emitter.class, "quantity"));
            assertEquals(40f, observed.x);
            assertEquals(40f, observed.y);
            assertEquals("SplashFactory", read(observed, Emitter.class, "factory").getClass().getSimpleName());

            Random.pushGenerator(490);
            try { Splash.at(22, 0x0088FF, 15); assertEquals(tagged, Random.Long()); }
            finally { Random.popGenerator(); }
            Emitter ordinary = (Emitter) emitters.childrenSnapshot().get(1);
            assertNull(read(ordinary, Emitter.class, "drawObserver"), "The scoped declaration cannot contaminate another Splash");
            CellParticleCue cue = (CellParticleCue) read(observed, Emitter.class, "drawObserver");
            observed.add(new Visual(34, 34, 2, 2));
            fixture.collector.particleEmitterDrawn(observed, cue);
            GameplayVisualTraversalTest.method("finishParticleDraws").invoke(fixture.collector);
            assertEquals("crystal_shatter", fixture.cues().get(0).kind);
            assertEquals(22, fixture.cues().get(0).cell);
            assertNull(fixture.cues().get(0).appearance, "Fixed native quantity is not published as an observed particle count");
            fixture.clear();
            ((List<?>) GameplayVisualTraversalTest.get(fixture.collector, "particleObservations")).clear();
            fixture.level.heroFOV[22] = false;
            fixture.collector.particleEmitterDrawn(observed, cue);
            GameplayVisualTraversalTest.method("finishParticleDraws").invoke(fixture.collector);
            assertTrue(fixture.cues().isEmpty());
        } finally {
            Game.observer = priorObserver;
            Dungeon.level = priorLevel;
            field(null, GameScene.class, "scene", priorScene);
        }
    }

    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }
    private static Object read(Object target, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
