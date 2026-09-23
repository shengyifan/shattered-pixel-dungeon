package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.MnemonicPrayer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Thief;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.ElmoParticle;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MysteryMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RemainingEffectSignalsTest {
    private static Map<Object, SmartTexture> textures;
    private static SmartTexture previousItemIcons;
    private static boolean hadItemIcons;

    @BeforeAll @SuppressWarnings("unchecked") static void syntheticItemIcons() throws Exception {
        textures = (Map<Object, SmartTexture>) field(TextureCache.class, "all").get(null);
        hadItemIcons = textures.containsKey(Assets.Sprites.ITEM_ICONS);
        previousItemIcons = textures.get(Assets.Sprites.ITEM_ICONS);
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Object allocator = field(unsafe, "theUnsafe").get(null);
        SmartTexture texture = (SmartTexture) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(allocator, SmartTexture.class);
        // The immutable atlas is 128 x 64; no asset decoding, native GL or profile access is needed.
        texture.width = 128;
        texture.height = 64;
        textures.put(Assets.Sprites.ITEM_ICONS, texture);
    }

    @AfterAll static void restoreItemIcons() {
        if (hadItemIcons) textures.put(Assets.Sprites.ITEM_ICONS, previousItemIcons);
        else textures.remove(Assets.Sprites.ITEM_ICONS);
    }

    @Test void teleportDepartureKeepsOnlyTheKnownOldCellWithoutFollowingTheActor() throws Exception {
        try (Fixture f = new Fixture(true)) {
            SignalChar target = f.character(Char.Alignment.ENEMY);
            ScrollOfTeleportation.appear(target, 44);
            Emitter departure = (Emitter) f.worldEmitters.childrenSnapshot().get(0);
            assertEmission(departure, Speck.factory(Speck.LIGHT), .2f, 3);
            CellParticleCue cue = signal(departure);
            assertEquals("teleport_departure", cue.kind);
            assertEquals(22, cue.observedCell());
            assertEquals(44, target.pos);
            assertEquals("arrival_light",signal(f.sprite.lastEmitter).kind);
            assertEquals(44,signal(f.sprite.lastEmitter).observedCell());
            f.level.heroFOV[44]=false;
            f.contributor(f.sprite.lastEmitter,Speck.class,44);
            assertTrue(f.frame(f.sprite.lastEmitter).isEmpty(),"Arrival cannot disclose a hidden destination");
            f.level.heroFOV[44]=true;
            assertEquals(Collections.singletonList(new VisualCue("arrival_light",44)),f.frame(f.sprite.lastEmitter));

            f.contributor(departure, Speck.class, 22);
            assertEquals(Collections.singletonList(new VisualCue("teleport_departure", 22)), f.frame(departure));
            f.level.heroFOV[22] = false;
            assertTrue(f.frame(departure).isEmpty(), "A known destination cannot reveal departure light in fog");
        }
    }

    @Test void bothCarriedItemOutcomesProduceTheSameGenericVisibleReaction() throws Exception {
        for (boolean scroll : new boolean[]{true, false}) {
            try (Fixture f = new Fixture(true)) {
                SignalThief thief = new SignalThief();
                thief.pos = 22;
                thief.sprite = f.sprite;
                thief.item = scroll ? new ScrollOfIdentify() : new MysteryMeat();
                Burning burning = new Burning();
                burning.target = thief;
                burning.reignite(thief, 4);
                burning.act();
                Emitter reaction = f.sprite.lastEmitter;
                assertEmission(reaction, ElmoParticle.FACTORY, 0, 6);
                assertEquals("carried_item_reaction", signal(reaction).kind);
                if (scroll) assertNull(thief.item);
                else assertNotNull(thief.item);

                // The real branch outcomes differ, but capture has only the existing sprite and effect.
                thief.pos = 59;
                assertNull(f.sprite.ch);
                f.contributor(reaction, ElmoParticle.class, 22);
                assertEquals(Collections.singletonList(new VisualCue("carried_item_reaction", 22)), f.frame(reaction));
                f.sprite.visible = false;
                assertTrue(f.frame(reaction).isEmpty());
            }
        }
    }

    @Test void mnemonicArrowsDescribeTheSelectedEffectEvenWhenNoBuffCanBeExtended() throws Exception {
        for (boolean ally : new boolean[]{true, false}) {
            try (Fixture f = new Fixture(true)) {
                SignalChar target = f.character(ally ? Char.Alignment.ALLY : Char.Alignment.ENEMY);
                affectPrayer(target);
                assertTrue(target.buffs().isEmpty(), "The fixture rejects new buffs and has none to extend");
                Emitter prayer = f.sprite.lastEmitter;
                int speck = ally ? Speck.UP : Speck.DOWN;
                String kind = ally ? "mnemonic_prayer_up" : "mnemonic_prayer_down";
                assertEmission(prayer, Speck.factory(speck), .15f, 4);
                assertEquals(kind, signal(prayer).kind);

                target.alignment = Char.Alignment.NEUTRAL;
                target.pos = 59;
                assertNull(f.sprite.ch);
                f.contributor(prayer, Speck.class, 22);
                assertEquals(Collections.singletonList(new VisualCue(kind, 22)), f.frame(prayer));
                f.sprite.revive();
                assertTrue(f.frame(prayer).isEmpty(), "A reused sprite cannot inherit the prior prayer episode");
            }
        }
    }

    @Test void disabledObservationKeepsNativeEmissionWithoutAttachingSemanticHooks() throws Exception {
        try (Fixture f = new Fixture(false)) {
            SignalChar target = f.character(Char.Alignment.ALLY);
            affectPrayer(target);
            assertEmission(f.sprite.lastEmitter, Speck.factory(Speck.UP), .15f, 4);
            assertNull(signal(f.sprite.lastEmitter));
            ScrollOfTeleportation.appear(target, 44);
            Emitter departure = (Emitter) f.worldEmitters.childrenSnapshot().get(0);
            assertEmission(departure, Speck.factory(Speck.LIGHT), .2f, 3);
            assertNull(signal(departure));

            SignalThief thief = new SignalThief();
            thief.pos = 22;
            thief.sprite = f.sprite;
            thief.item = new MysteryMeat();
            Burning burning = new Burning();
            burning.target = thief;
            burning.reignite(thief, 4);
            burning.act();
            assertEmission(f.sprite.lastEmitter, ElmoParticle.FACTORY, 0, 6);
            assertNull(signal(f.sprite.lastEmitter));
        }
    }

    private static void affectPrayer(Char target) throws Exception {
        Method affect = MnemonicPrayer.class.getDeclaredMethod("affectChar", Char.class, float.class);
        affect.setAccessible(true);
        affect.invoke(MnemonicPrayer.INSTANCE, target, 4f);
    }

    private static void assertEmission(Emitter emitter, Emitter.Factory factory, float interval, int quantity) throws Exception {
        assertNotNull(emitter);
        assertTrue(emitter.isEmitting(factory));
        assertEquals(interval, field(Emitter.class, "interval").getFloat(emitter));
        assertEquals(quantity, field(Emitter.class, "quantity").getInt(emitter));
        assertEquals(0, emitter.countLiving(), "Source annotation must not emit the first particle");
    }

    private static CellParticleCue signal(Emitter emitter) throws Exception {
        return (CellParticleCue) field(Emitter.class, "drawObserver").get(emitter);
    }

    private static class SignalChar extends Char {
        @Override public void move(int step, boolean travelling) { pos = step; }
        @Override public synchronized boolean add(Buff buff) { return false; }
        @Override public boolean isImmune(Class effect) { return false; }
    }

    private static class SignalThief extends Thief {
        @Override public void damage(int damage, Object source) {}
        @Override public boolean isAlive() { return true; }
        @Override public boolean isImmune(Class effect) { return false; }
    }

    private static class SignalSprite extends CharSprite {
        final Group owner;
        int cell = 22;
        Emitter lastEmitter;
        SignalSprite(Group owner) { this.owner = owner; }
        @Override public Emitter emitter() { lastEmitter = new Emitter(); owner.add(lastEmitter); return lastEmitter; }
        @Override public void place(int cell) { this.cell = cell; }
        @Override public int renderedCell() { return cell; }
    }

    private static class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final Level previousLevel = Dungeon.level;
        final Hero previousHero = Dungeon.hero;
        final RuntimeObserver previousObserver = Game.observer;
        final Object previousScene = field(GameScene.class, "scene").get(null);
        final Group worldEmitters = new Group();
        final SignalSprite sprite = new SignalSprite(scene);

        Fixture(boolean observe) throws Exception {
            Dungeon.level = level;
            Dungeon.hero = null;
            level.water = new boolean[level.length()];
            level.flamable = new boolean[level.length()];
            Game.observer = observe ? new RuntimeObserver() {
                @Override public boolean observesVisualCues() { return true; }
            } : RuntimeObserver.NONE;
            field(GameScene.class, "scene").set(null, scene);
            field(GameScene.class, "emitters").set(scene, worldEmitters);
            scene.add(worldEmitters);
            scene.add(sprite);
            Random.pushGenerator(1800);
        }

        SignalChar character(Char.Alignment alignment) {
            SignalChar target = new SignalChar();
            target.pos = 22;
            target.invisible = 1;
            target.alignment = alignment;
            target.sprite = sprite;
            return target;
        }

        void contributor(Emitter emitter, Class<? extends Visual> type, int cell) throws Exception {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Object allocator = field(unsafe, "theUnsafe").get(null);
            Visual particle = (Visual) unsafe.getMethod("allocateInstance", Class.class).invoke(allocator, type);
            particle.exists = particle.alive = particle.active = particle.visible = true;
            particle.x = (cell % level.width()) * 16 + 3;
            particle.y = (cell / level.width()) * 16 + 3;
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
            field(GameScene.class, "scene").set(null, previousScene);
            Dungeon.level = previousLevel;
            Dungeon.hero = previousHero;
            Game.observer = previousObserver;
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
