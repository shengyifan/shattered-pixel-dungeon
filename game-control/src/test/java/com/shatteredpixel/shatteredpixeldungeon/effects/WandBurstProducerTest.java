package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.RainbowParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.ShadowParticle;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfLivingEarth;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfPrismaticLight;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.CursedWand;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.PointF;
import com.watabou.utils.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Actual native wand branches with isolated display contributors; never a GUI or profile. */
class WandBurstProducerTest {
    private static Map<Object, SmartTexture> textures;
    private static SmartTexture previousIcons;
    private static boolean hadIcons;

    @BeforeAll @SuppressWarnings("unchecked") static void syntheticItemIcons() throws Exception {
        textures = (Map<Object, SmartTexture>) field(TextureCache.class, "all").get(null);
        hadIcons = textures.containsKey(Assets.Sprites.ITEM_ICONS);
        previousIcons = textures.get(Assets.Sprites.ITEM_ICONS);
        SmartTexture texture = allocate(SmartTexture.class);
        texture.width = 128; texture.height = 64;
        textures.put(Assets.Sprites.ITEM_ICONS, texture);
    }

    @AfterAll static void restoreItemIcons() {
        if (hadIcons) textures.put(Assets.Sprites.ITEM_ICONS, previousIcons);
        else textures.remove(Assets.Sprites.ITEM_ICONS);
    }

    @Test void livingEarthStaffHitKeepsNativeEmissionAndCountsOnlyVisibleContributors() throws Exception {
        try (Fixture f = new Fixture(true)) {
            WandOfLivingEarth wand = new WandOfLivingEarth() { @Override public int buffedLvl() { return 4; } };
            SignalChar attacker = f.character(false);
            wand.onHit(null, attacker, new SignalChar(), 12);
            Emitter burst = f.sprite.lastEmitter();
            assertEmission(burst, MagicMissile.EarthParticle.ATTRACT, 0, 10);
            assertNotNull(observer(burst));
            assertEquals(0, burst.countLiving(), "Observation setup must not emit the requested ten particles");

            f.contributor(burst, MagicMissile.EarthParticle.class, 22);
            f.contributor(burst, MagicMissile.EarthParticle.class, 22);
            attacker.pos = 59;
            assertNull(f.sprite.ch, "Capture cannot read the attacker or wand");
            List<VisualCue> visible = f.frame(burst);
            assertEquals(1, visible.size());
            assertEquals("earth_attraction", visible.get(0).kind);
            assertEquals(22, visible.get(0).cell);
            assertEquals(2, visible.get(0).appearance.get("count"));
            f.level.heroFOV[22] = false;
            assertTrue(f.frame(burst).isEmpty(), "An unknown effect cell must reveal neither presence nor count");
            f.level.heroFOV[22] = true;
            f.sprite.revive();
            assertTrue(f.frame(burst).isEmpty(), "A reused character sprite cannot retain the earlier source binding");
        }
    }

    @Test void prismaticNativeTargetBranchesKeepPublicParticleStyleWithoutInferringSusceptibility() throws Exception {
        for (boolean undead : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(true)) {
                SignalChar target = f.character(undead);
                affectPrismatic(target);
                Emitter burst = f.sprite.lastEmitter();
                String kind = undead ? "shadow_burst" : "rainbow_burst";
                assertEmission(burst, undead ? ShadowParticle.UP : RainbowParticle.BURST, undead ? .05f : 0, 14);
                f.contributor(burst, undead ? ShadowParticle.class : RainbowParticle.class, 22);
                target.clearProperties(); target.pos = 59;
                List<VisualCue> visible = f.frame(burst);
                assertEquals(1, visible.size());
                assertEquals(kind, visible.get(0).kind);
                assertEquals(1, visible.get(0).appearance.get("count"));
                assertFalse(visible.get(0).appearance.containsKey("level"));
                assertFalse(visible.get(0).appearance.containsKey("undead"));
                f.sprite.visible = false;
                assertTrue(f.frame(burst).isEmpty());
            }
        }
    }

    @Test void observationAddsNoRandomDrawsAndDisabledModeKeepsOriginalEmitterSetup() throws Exception {
        float disabled, enabled;
        try (Fixture f = new Fixture(false)) {
            affectPrismatic(f.character(true));
            assertEmission(f.sprite.lastEmitter(), ShadowParticle.UP, .05f, 14);
            assertNull(observer(f.sprite.lastEmitter()));
            disabled = Random.Float();
        }
        try (Fixture f = new Fixture(true)) {
            affectPrismatic(f.character(true));
            assertEmission(f.sprite.lastEmitter(), ShadowParticle.UP, .05f, 14);
            assertNotNull(observer(f.sprite.lastEmitter()));
            f.sprite.lastEmitter().observeGameplayVisuals();
            f.sprite.lastEmitter().observeGameplayVisuals();
            enabled = Random.Float();
            assertEquals(0, f.sprite.lastEmitter().countLiving());
        }
        assertEquals(disabled, enabled, "Attaching and querying semantic observers must not advance RNG");
    }

    @Test void nativeHealthTransferKeepsBothRandomDirectionsWithoutRequiringVisibleFloatingText() throws Exception {
        boolean healedUser = false, healedTarget = false;
        for (int seed = 0; seed < 16 && !(healedUser && healedTarget); seed++) {
            try (Fixture f = new Fixture(true)) {
                SignalChar user = f.character(false);
                SignalSprite targetSprite = new SignalSprite(f.scene, 23);
                f.scene.add(targetSprite);
                SignalChar target = new SignalChar(); target.pos = 23; target.sprite = targetSprite;
                user.HP = target.HP = 10; user.HT = target.HT = 20;
                f.register(target);
                Ballistica bolt = allocate(Ballistica.class); bolt.collisionPos = 23;
                Random.pushGenerator(seed * 0x9e3779b97f4a7c15L);
                try {
                    assertTrue(new CursedWand.HealthTransfer().effect(null, user, bolt, false));
                } finally { Random.popGenerator(); }
                assertEquals(1, f.sprite.emitted.size()); assertEquals(1, targetSprite.emitted.size());
                for (SignalSprite sprite : new SignalSprite[]{f.sprite, targetSprite}) {
                    Emitter emitter = sprite.lastEmitter();
                    boolean healing = emitter.isEmitting(Speck.factory(Speck.HEALING));
                    if (sprite == f.sprite) healedUser |= healing; else healedTarget |= healing;
                    assertEmission(emitter, healing ? Speck.factory(Speck.HEALING) : ShadowParticle.UP,
                            healing ? 0 : .05f, healing ? 3 : 10);
                    f.contributor(emitter, healing ? Speck.class : ShadowParticle.class, sprite.cell);
                    List<VisualCue> frame = f.frame(emitter);
                    assertEquals(1, frame.size());
                    assertEquals(healing ? "healing_specks" : "shadow_burst", frame.get(0).kind);
                    assertEquals(sprite.cell, frame.get(0).cell);
                    assertNull(frame.get(0).appearance, "Fixed bursts expose no requested quantity or damage result");
                }
                f.level.heroFOV[23] = false;
                assertTrue(f.frame(targetSprite.lastEmitter()).isEmpty());
            }
        }
        assertTrue(healedUser && healedTarget, "Both real random effect directions must have run");
    }

    @Test void missingHealthTransferTargetReturnsFalseWithoutCreatingAnyEmitter() throws Exception {
        try (Fixture f = new Fixture(true)) {
            SignalChar user = f.character(false);
            Ballistica bolt = allocate(Ballistica.class); bolt.collisionPos = 23;
            assertFalse(new CursedWand.HealthTransfer().effect(null, user, bolt, false));
            assertTrue(f.sprite.emitted.isEmpty());
        }
    }

    private static void affectPrismatic(Char target) throws Exception {
        WandOfPrismaticLight wand = new WandOfPrismaticLight() {
            @Override public int buffedLvl() { return 4; }
            @Override public int damageRoll() { return 9; }
        };
        Method affect = WandOfPrismaticLight.class.getDeclaredMethod("affectTarget", Char.class);
        affect.setAccessible(true); affect.invoke(wand, target);
    }

    private static void assertEmission(Emitter emitter, Emitter.Factory factory, float interval, int quantity) throws Exception {
        assertTrue(emitter.isEmitting(factory));
        assertEquals(interval, field(Emitter.class, "interval").getFloat(emitter));
        assertEquals(quantity, field(Emitter.class, "quantity").getInt(emitter));
    }

    private static Emitter.DrawObserver observer(Emitter emitter) throws Exception {
        return (Emitter.DrawObserver) field(Emitter.class, "drawObserver").get(emitter);
    }

    private static final class SignalChar extends Char {
        void setUndead() { properties.add(Property.UNDEAD); }
        void clearProperties() { properties.clear(); }
        @Override public synchronized boolean add(Buff buff) { return false; }
        @Override public boolean isImmune(Class effect) { return false; }
        @Override public void damage(int damage, Object source) { }
    }

    private static final class SignalSprite extends CharSprite {
        final Group owner;
        final int cell;
        final List<Emitter> emitted = new ArrayList<>();
        SignalSprite(Group owner) { this(owner, 22); }
        SignalSprite(Group owner, int cell) { this.owner = owner; this.cell = cell; }
        @Override public Emitter emitter() { Emitter e = new Emitter(); owner.add(e); emitted.add(e); return e; }
        @Override public Emitter centerEmitter() { return emitter(); }
        @Override public int renderedCell() { return cell; }
        @Override public void showStatusWithIcon(int color, String text, int icon, Object... args) { }
        Emitter lastEmitter() { return emitted.get(emitted.size()-1); }
    }

    private static final class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final Level previousLevel = Dungeon.level;
        final Hero previousHero = Dungeon.hero;
        final int previousDepth = Dungeon.depth;
        final RuntimeObserver previousObserver = Game.observer;
        final Object previousScene = field(GameScene.class, "scene").get(null);
        final Object previousChars = field(Actor.class, "chars").get(null);
        final HashSet<Char> chars = new HashSet<>();
        final SignalSprite sprite = new SignalSprite(scene);

        Fixture(boolean observe) throws Exception {
            Dungeon.level = level; Dungeon.hero = null; Dungeon.depth = 2;
            field(Actor.class, "chars").set(null, chars);
            level.blobs = new HashMap<>(); level.mobs = new HashSet<>();
            Game.observer = observe ? new RuntimeObserver() {
                @Override public boolean observesVisualCues() { return true; }
            } : RuntimeObserver.NONE;
            field(GameScene.class, "scene").set(null, scene);
            field(GameScene.class, "visualCueCollector").set(scene, collector);
            scene.add(sprite);
            Random.pushGenerator(1803);
        }

        SignalChar character(boolean undead) {
            SignalChar target = new SignalChar(); target.pos = 22; target.sprite = sprite;
            if (undead) target.setUndead();
            return target;
        }

        void register(Char character) { chars.add(character); }

        void contributor(Emitter emitter, Class<? extends Visual> type, int cell) throws Exception {
            Visual p = allocate(type);
            p.exists = p.alive = p.active = p.visible = true;
            p.x = (cell % level.width()) * 16 + 3;
            p.y = (cell / level.width()) * 16 + 3;
            p.width = p.height = 1; p.scale = new PointF(1, 1); p.origin = new PointF(); p.resetColor();
            if (p instanceof Image) ((Image)p).texture = allocate(SmartTexture.class);
            emitter.add(p);
        }

        List<VisualCue> frame(Emitter emitter) throws Exception {
            clear();
            ((List<?>) GameplayVisualTraversalTest.get(collector, "particleObservations")).clear();
            emitter.observeGameplayVisuals();
            GameplayVisualTraversalTest.method("finishParticleDraws").invoke(collector);
            return cues();
        }

        @Override public void close() throws Exception {
            Random.popGenerator();
            field(GameScene.class, "scene").set(null, previousScene);
            field(Actor.class, "chars").set(null, previousChars);
            Dungeon.level = previousLevel; Dungeon.hero = previousHero; Dungeon.depth = previousDepth; Game.observer = previousObserver;
        }
    }

    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return (T) unsafe.getMethod("allocateInstance", Class.class).invoke(field(unsafe, "theUnsafe").get(null), type);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
