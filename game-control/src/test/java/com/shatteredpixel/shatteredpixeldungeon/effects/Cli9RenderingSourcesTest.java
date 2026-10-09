package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultBossElemental;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.VaultBossElementalSprite;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import com.watabou.utils.PointF;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Cli9RenderingSourcesTest {
    @Test void doorEnergyComesFromItsActualEmitterWithoutRequiringAVisibleBoss() throws Exception {
        try(Fixture f=new Fixture()){
            VaultBossElementalSprite sprite=allocate(VaultBossElementalSprite.class);
            java.lang.reflect.Method bind=VaultBossElementalSprite.class.getDeclaredMethod("observeDoorEnergy",
                    com.watabou.noosa.particles.Emitter.class,int.class);bind.setAccessible(true);
            com.watabou.noosa.particles.Emitter emitter=new com.watabou.noosa.particles.Emitter();
            f.scene.add(emitter);
            com.watabou.noosa.Visual particle=new com.watabou.noosa.Visual(32,32,2,2);emitter.add(particle);
            com.watabou.noosa.particles.Emitter.Factory[] factories={
                    com.shatteredpixel.shatteredpixeldungeon.effects.particles.FlameParticle.FACTORY,
                    MagicMissile.MagicParticle.FACTORY,
                    com.shatteredpixel.shatteredpixeldungeon.effects.particles.SparkParticle.STATIC};
            String[] stages={"fire","frost","shock"};
            for(int i=0;i<factories.length;i++){
                emitter.pour(factories[i],.1f);bind.invoke(sprite,emitter,22);
                f.clear();emitter.observeGameplayVisuals();
                GameplayVisualTraversalTest.method("finishParticleDraws").invoke(f.collector);
                assertEquals(Collections.singletonList(new VisualCue("vault_door_energy",22,null,null,null,null,
                        Collections.singletonMap("stage",stages[i]))),f.cues());
                assertNull(sprite.ch,"Door energy is selected by the emitter, never by a hidden boss");
                ((java.util.List<?>)GameplayVisualTraversalTest.get(f.collector,"particleObservations")).clear();
            }
            f.level.heroFOV[22]=false;f.clear();emitter.observeGameplayVisuals();
            GameplayVisualTraversalTest.method("finishParticleDraws").invoke(f.collector);
            assertTrue(f.cues().isEmpty());
            assertTrue(GameplayVisualKinds.affectsIntent("vault_door_energy"));
        }
    }

    @Test void versionFourMissilesRetainTheirSelectedRenderingBranches() {
        assertEquals("magic_shaman_elmo", MagicMissile.appearance(MagicMissile.SHAMAN_ELMO));
        assertEquals("magic_poison_cone", MagicMissile.appearance(MagicMissile.POISON_CONE));
        assertEquals("magic_elmo", MagicMissile.appearance(MagicMissile.ELMO));
        assertEquals("magic_poison", MagicMissile.appearance(MagicMissile.POISON));
        assertEquals("magic_light", MagicMissile.appearance(MagicMissile.LIGHT_MISSILE));
    }

    @Test void checkedCellPoolResetRebindsPositionAndNeverReusesAnOldLifetime() throws Exception {
        try (Fixture f = new Fixture()) {
            CheckedCell marker = allocate(CheckedCell.class);
            initialize(marker, 1, 1);
            f.scene.add(marker);
            marker.reset(22, 22);
            marker.update();
            assertEquals(Collections.singletonList(new VisualCue("checked_cell", 22)), f.sample(marker));
            long firstLifetime = marker.observationLifetime();
            marker.kill();
            assertTrue(f.sample(marker).isEmpty());
            marker.reset(35, 35);
            marker.update();
            assertTrue(marker.observationLifetime() > firstLifetime);
            assertEquals(Collections.singletonList(new VisualCue("checked_cell", 35)), f.sample(marker));
            marker.revive();
            assertTrue(f.sample(marker).isEmpty(), "Revive without native reset cannot retain the old cell binding");
            marker.reset(44, 44);
            marker.update();
            f.level.heroFOV[44] = false;
            assertTrue(f.sample(marker).isEmpty(), "A scanned cell is not evidence outside current FOV");
        }
    }

    @Test void checkedCellNativeDelayAndExpiryGateItsPublicPresentation() throws Exception {
        try (Fixture f = new Fixture()) {
            CheckedCell marker = allocate(CheckedCell.class);
            initialize(marker, 1, 1);
            f.scene.add(marker);
            marker.reset(44, 11);
            assertTrue(f.sample(marker).isEmpty(), "The native propagation delay keeps the marker transparent");
            Game.elapsed = 0.3f;
            marker.update();
            assertEquals(Collections.singletonList(new VisualCue("checked_cell", 44)), f.sample(marker));
            Game.elapsed = 1f;
            marker.update();
            assertFalse(marker.alive);
            assertTrue(f.sample(marker).isEmpty());
        }
    }

    @Test void bossFormsComeFromTheSelectedSpriteAndKeepGenericAppearanceIndependent() throws Exception {
        try (Fixture f = new Fixture()) {
            VaultBossElementalSprite sprite = allocate(VaultBossElementalSprite.class);
            initialize(sprite, 16, 16);
            sprite.x = sprite.y = 32;
            sprite.paused = true;
            f.scene.add(sprite);
            assertNull(sprite.ch, "The fixture provides no backing boss or private actor state");
            for (VaultBossElemental.ElementalForm form : VaultBossElemental.ElementalForm.values()) {
                set(sprite, VaultBossElementalSprite.class, "form", form);
                List<VisualCue> cues = f.sample(sprite);
                String expected = form == VaultBossElemental.ElementalForm.FIRE ? "fire"
                        : form == VaultBossElemental.ElementalForm.FROST ? "frost" : "shock";
                assertTrue(cues.contains(new VisualCue("vault_elemental_form", 22, null, null,
                        null, null, Collections.singletonMap("form", expected))));
                assertTrue(cues.contains(new VisualCue("sprite_state", 22, null, null,
                        null, null, Collections.singletonMap("paused", true))),
                        "The dedicated form cue cannot replace inherited sprite appearance");
                assertEquals(2, cues.size());
            }
            f.level.heroFOV[22] = false;
            assertTrue(f.sample(sprite).isEmpty());
            f.level.heroFOV[22] = true;
            sprite.alpha(0);
            assertTrue(f.sample(sprite).isEmpty());
            sprite.alpha(1);
            f.scene.erase(sprite);
            assertTrue(f.sample(sprite).isEmpty());
        }
    }

    private static final class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final Level previousLevel = Dungeon.level;
        final RuntimeObserver previousObserver = Game.observer;
        final float previousElapsed = Game.elapsed;
        final Object previousScene;
        Fixture() throws Exception {
            previousScene = field(null, GameScene.class, "scene");
            set(null, GameScene.class, "scene", scene);
            set(scene, GameScene.class, "visualCueCollector", collector);
            Dungeon.level = level;
            Game.elapsed = 0;
            Game.observer = new RuntimeObserver() {
                @Override public boolean observesVisualCues() { return true; }
            };
        }
        List<VisualCue> sample(Visual source) throws Exception {
            clear();
            source.observeGameplayVisuals();
            return cues();
        }
        @Override public void close() throws Exception {
            Dungeon.level = previousLevel;
            Game.observer = previousObserver;
            Game.elapsed = previousElapsed;
            set(null, GameScene.class, "scene", previousScene);
        }
    }
    private static void initialize(Image source, float width, float height) throws Exception {
        source.exists = source.alive = source.visible = source.active = true;
        source.scale = new PointF(1, 1);
        source.origin = new PointF();
        source.width = width;
        source.height = height;
        source.texture = allocate(SmartTexture.class);
        source.resetColor();
    }
    private static Object field(Object target, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }
}
