package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.badlogic.gdx.Preferences;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.EarthParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.FlameParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.PoisonParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.ShaftParticle;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.plants.*;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.GameSettings;
import com.watabou.utils.PointF;
import com.watabou.utils.Random;
import com.watabou.utils.SparseArray;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlantActivationCueTest {
    private static final Object[][] SOURCES = {
            {Earthroot.class, EarthParticle.FACTORY, EarthParticle.class, .05f, 8},
            {Blindweed.class, Speck.factory(Speck.LIGHT), Speck.class, 0f, 4},
            {Sorrowmoss.class, PoisonParticle.SPLASH, PoisonParticle.class, 0f, 3},
            {Sungrass.class, ShaftParticle.FACTORY, ShaftParticle.class, .2f, 3},
            {Fadeleaf.class, Speck.factory(Speck.LIGHT), Speck.class, .2f, 3},
            {Firebloom.class, FlameParticle.FACTORY, FlameParticle.class, 0f, 5}
    };

    @Test void allSixNativeEffectsReportOnlyActivationCellWithNoKnownBeneficiary() throws Exception {
        for (Object[] source : SOURCES) {
            for (boolean hiddenTrigger : new boolean[]{false, true}) {
                try (Fixture f = new Fixture(true)) {
                    Plant plant = plant(source);
                    HiddenChar hidden = new HiddenChar();
                    hidden.pos = plant.pos;
                    hidden.invisible = 1;
                    plant.activate(hiddenTrigger ? hidden : null);
                    assertNull(hidden.sprite);
                    assertTrue(hidden.buffs().isEmpty());

                    Emitter effect = f.emitter();
                    assertEmission(effect, source);
                    CellParticleCue cue = signal(effect);
                    assertEquals("plant_activation", cue.kind);
                    assertEquals(22, cue.observedCell());
                    f.contributor(effect, source);
                    assertEquals(Collections.singletonList(new VisualCue("plant_activation", 22)), f.frame(effect));
                    assertFalse(GameplayVisualKinds.affectsIntent(cue.kind));

                    f.level.heroFOV[22] = false;
                    assertTrue(f.frame(effect).isEmpty(), "A known plant cell does not retain live feedback in fog");
                }
            }
        }
    }

    @Test void offFovActivationDoesNotCreateAPresentationSourceOrExposeAHiddenTrigger() throws Exception {
        for (Object[] source : SOURCES) {
            try (Fixture f = new Fixture(true)) {
                f.level.heroFOV[22] = false;
                plant(source).activate(null);
                assertTrue(f.worldEmitters.childrenSnapshot().isEmpty());
            }
        }
    }

    @Test void observerTogglePreservesNativeEmitterParametersAndRngSequence() throws Exception {
        for (Object[] source : SOURCES) {
            Long nativeNext = null;
            for (boolean observing : new boolean[]{false, true}) {
                try (Fixture f = new Fixture(observing)) {
                    plant(source).activate(null);
                    Emitter emitter = f.emitter();
                    assertEmission(emitter, source);
                    assertEquals(observing, signal(emitter) != null);
                    long next = Random.Long();
                    if (nativeNext == null) nativeNext = next;
                    else assertEquals(nativeNext.longValue(), next,
                            "Adding an annotation must not advance the native RNG sequence");
                }
            }
        }
    }

    private static Plant plant(Object[] source) throws Exception {
        Plant plant = (Plant) ((Class<?>) source[0]).getDeclaredConstructor().newInstance();
        plant.pos = 22;
        return plant;
    }

    private static void assertEmission(Emitter emitter, Object[] source) throws Exception {
        assertTrue(emitter.isEmitting((Emitter.Factory) source[1]));
        assertEquals((Float) source[3], field(Emitter.class, "interval").get(emitter));
        assertEquals((Integer) source[4], field(Emitter.class, "quantity").get(emitter));
        assertEquals(0, emitter.countLiving(), "Source annotation must not force a first particle");
    }

    private static CellParticleCue signal(Emitter emitter) throws Exception {
        return (CellParticleCue) field(Emitter.class, "drawObserver").get(emitter);
    }

    private static class HiddenChar extends Char {
        @Override public synchronized boolean add(Buff buff) { return false; }
        @Override public boolean isImmune(Class effect) { return false; }
    }

    private static class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final Level previousLevel = Dungeon.level;
        final Hero previousHero = Dungeon.hero;
        final RuntimeObserver previousObserver = Game.observer;
        final Camera previousCamera = Camera.main;
        final Object previousScene = field(GameScene.class, "scene").get(null);
        final Object previousPrefs = field(GameSettings.class, "prefs").get(null);
        final Map<String, Object> previousActors = new LinkedHashMap<>();
        final Group worldEmitters = new Group();

        Fixture(boolean observe) throws Exception {
            Dungeon.level = level;
            Dungeon.hero = null;
            level.blobs = new HashMap<>();
            Game.observer = observe ? new RuntimeObserver() {
                @Override public boolean observesVisualCues() { return true; }
            } : RuntimeObserver.NONE;
            Camera.main = new Camera(0, 0, 16, 16, 1);
            // PixelScene.shake consults preferences; this fixture must never open a personal profile.
            GameSettings.set((Preferences) Proxy.newProxyInstance(Preferences.class.getClassLoader(),
                    new Class<?>[]{Preferences.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getInteger") && args.length == 2) return args[1];
                        throw new AssertionError("Unexpected preference access: " + method.getName());
                    }));
            for (String name : new String[]{"all", "ids", "nextID", "current"}) {
                previousActors.put(name, field(Actor.class, name).get(null));
            }
            field(Actor.class, "all").set(null, new HashSet<Actor>());
            field(Actor.class, "ids").set(null, new SparseArray<Actor>());
            field(Actor.class, "nextID").setInt(null, 1);
            field(Actor.class, "current").set(null, null);
            field(GameScene.class, "scene").set(null, scene);
            field(GameScene.class, "emitters").set(scene, worldEmitters);
            field(GameScene.class, "gases").set(scene, new Group());
            scene.add(worldEmitters);
            Random.pushGenerator(800);
        }

        Emitter emitter() {
            assertEquals(1, worldEmitters.childrenSnapshot().size());
            return (Emitter) worldEmitters.childrenSnapshot().get(0);
        }

        void contributor(Emitter emitter, Object[] source) throws Exception {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Object allocator = field(unsafe, "theUnsafe").get(null);
            Visual particle = (Visual) unsafe.getMethod("allocateInstance", Class.class).invoke(allocator, source[2]);
            particle.exists = particle.alive = particle.active = particle.visible = true;
            particle.x = particle.y = 35;
            particle.width = particle.height = 1;
            particle.scale = new PointF(1, 1);
            particle.origin = new PointF();
            particle.resetColor();
            ((Image) particle).texture = (SmartTexture) unsafe.getMethod("allocateInstance", Class.class)
                    .invoke(allocator, SmartTexture.class);
            emitter.add(particle);
        }

        List<VisualCue> frame(Emitter emitter) throws Exception {
            clear();
            ((List<?>) GameplayVisualTraversalTest.get(collector, "particleObservations")).clear();
            collector.particleEmitterDrawn(emitter, signal(emitter));
            assertTrue((Boolean) GameplayVisualTraversalTest.method("finishParticleDraws").invoke(collector));
            return cues();
        }

        @Override public void close() throws Exception {
            Random.popGenerator();
            for (Map.Entry<String, Object> entry : previousActors.entrySet()) {
                field(Actor.class, entry.getKey()).set(null, entry.getValue());
            }
            field(GameScene.class, "scene").set(null, previousScene);
            field(GameSettings.class, "prefs").set(null, previousPrefs);
            Dungeon.level = previousLevel;
            Dungeon.hero = previousHero;
            Game.observer = previousObserver;
            Camera.main = previousCamera;
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
