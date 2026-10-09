package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Badges;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.Ratmogrify;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.PowerOfMany;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Stasis;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.RipperDemon;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.PendingWarning;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.journal.Bestiary;
import com.shatteredpixel.shatteredpixeldungeon.journal.Journal;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RipperSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Callback;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import com.watabou.utils.SparseArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.get;
import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Native temporary removal/release paths; preparation and public cells come from a real Ripper leap. */
class WarningSuspensionTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void ratRoundTripPreservesTheSamePublishedLeapUntilItsNativeLanding() throws Exception {
        try (Harness h = new Harness()) {
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            h.fadeMarkers();
            RestoringRat rat = h.transform(original);
            assertSame(rat, get(warning, PendingWarning.class, "owner"));
            assertTrue(warning.isPending());
            assertEquals(1, h.redAt(66), "Temporary Actor.remove must keep the already observed cell");

            set(rat, Ratmogrify.TransmogRat.class, "timeLeft", 0f);
            assertTrue(rat.restore());
            assertTrue(Actor.chars().contains(original));
            assertFalse(Actor.chars().contains(rat));
            assertSame(warning, h.warning(original));
            assertSame(original, get(warning, PendingWarning.class, "owner"));
            assertEquals(1, h.redAt(66));

            h.finishLeap(original, warning);
            assertEquals(66, original.pos);
            assertEquals(0, h.redAt(66));
        }
    }

    @Test void actualRatDeathAndPermanentAllianceCancelTheSuspendedNativeAttack() throws Exception {
        try (Harness h = new Harness()) {
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            RestoringRat rat = h.transform(original);
            assertEquals(1, h.redAt(66));
            rat.HP = 0;
            rat.die(Object.class);
            assertFalse(Actor.chars().contains(rat));
            assertFalse(warning.isPending());
            assertEquals(0, h.redAt(66));
        }
        try (Harness h = new Harness()) {
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            RestoringRat rat = h.transform(original);
            assertEquals(1, h.redAt(66));
            rat.makeAlly();
            assertEquals(Char.Alignment.ALLY, rat.alignment);
            assertTrue(Actor.chars().contains(rat));
            assertFalse(warning.isPending(), "Permanent replacement must cancel the original preparation");
            assertEquals(0, h.redAt(66));
        }
    }

    @Test void nativeStasisCastAndReleaseHelperTransferTheSameEpisodeBackToTheSameMob() throws Exception {
        try (Harness h = new Harness()) {
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            h.fadeMarkers();
            Stasis.StasisBuff holder = h.castStasis(original);
            assertSame(holder, get(warning, PendingWarning.class, "owner"));
            assertFalse(Actor.chars().contains(original));
            assertNull(original.sprite);
            assertEquals(1, h.redAt(66));

            assertSame(original, holder.releaseAlly(), "The Beaming Ray release entry point returns the held mob");
            assertSame(original, get(warning, PendingWarning.class, "owner"));
            holder.detach();
            assertTrue(warning.isPending(), "Detaching the old holder cannot cancel the released attack");
            GameScene.add(original);
            assertSame(warning, h.warning(original));
            h.finishLeap(original, warning);
            assertEquals(0, h.redAt(66));
        }
    }

    @Test void nativeStasisExpiryReleasesBeforeItsNormalDetachButEarlyDetachCancels() throws Exception {
        try (Harness h = new Harness()) {
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            Stasis.StasisBuff holder = h.castStasis(original);
            assertTrue(holder.act(), "The real expiry path selects a cell, adds the mob and detaches");
            assertNull(Dungeon.hero.buff(Stasis.StasisBuff.class));
            assertTrue(Actor.chars().contains(original));
            assertSame(original, get(warning, PendingWarning.class, "owner"));
            assertTrue(warning.isPending());
            assertEquals(1, h.redAt(66));
            h.withoutMapRenderer(() -> Actor.remove(original));
            assertFalse(warning.isPending(), "After release the mob again owns cancellation");
        }
        try (Harness h = new Harness()) {
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            Stasis.StasisBuff holder = h.castStasis(original);
            holder.detach();
            assertFalse(warning.isPending());
            assertEquals(0, h.redAt(66));
        }
    }

    @Test void rejectedStasisAttachmentAndAbandonedHandoffCancelInsteadOfLeaking() throws Exception {
        try (Harness h = new Harness()) {
            Dungeon.hero = new Hero() {
                @Override public boolean add(Buff buff) {
                    return !(buff instanceof Stasis.StasisBuff) && super.add(buff);
                }
            };
            h.placeHero();
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            h.castStasisWithoutCheckingHolder(original);
            assertNull(Dungeon.hero.buff(Stasis.StasisBuff.class));
            assertFalse(warning.isPending(), "A rejected holder leaves the suspension untransferred for close()");
            assertEquals(0, h.redAt(66));
        }
        try (Harness h = new Harness()) {
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            assertThrows(IllegalStateException.class, () -> {
                try (PendingWarning.Suspension ignored = PendingWarning.suspendOwnedBy(original)) {
                    Actor.remove(original);
                    assertTrue(warning.isPending());
                    assertEquals(1, h.redAt(66));
                    throw new IllegalStateException("Isolated failure before assigning a native holder");
                }
            });
            assertFalse(warning.isPending(), "An aborted temporary handoff cannot leave unowned danger");
            assertEquals(0, h.redAt(66));
        }
    }

    @Test void committedCallbacksDoNotTransferAndTrueScopeChangesStillInvalidateHeldKnowledge() throws Exception {
        try (Harness h = new Harness()) {
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            CallbackRipperSprite sprite = (CallbackRipperSprite) original.sprite;
            assertFalse(original.HUNTING.act(true, false));
            assertNotNull(sprite.landing);
            Actor holder = new Actor() { @Override protected boolean act() { return true; } };
            PendingWarning.transferOwnedBy(original, holder);
            try (PendingWarning.Suspension suspension = PendingWarning.suspendOwnedBy(original)) {
                suspension.transferTo(holder);
            }
            Actor.remove(holder); Actor.remove(original);
            assertTrue(warning.isPending(), "Only the already launched callback owns this episode");
            assertNull(get(warning, PendingWarning.class, "owner"));
            sprite.landing.call();
            assertFalse(warning.isPending());
        }
        try (Harness h = new Harness()) {
            PreparedRipper original = h.prepareRipper();
            PendingWarning warning = h.warning(original);
            h.fadeMarkers();
            RestoringRat rat = h.transform(original);
            Dungeon.branch++;
            assertEquals(0, h.redAt(66));
            assertFalse(warning.isPending());
            set(rat, Ratmogrify.TransmogRat.class, "timeLeft", 0f);
            rat.restore();
            assertSame(warning, h.warning(original));
            assertFalse(warning.isPending(), "Returning a held mob cannot resurrect knowledge from a prior scope");
            assertEquals(0, h.redAt(66));
        }
    }

    private static final class PreparedRipper extends RipperDemon {
        @Override public CharSprite sprite() { return new CallbackRipperSprite(); }
    }
    private static final class RestoringRat extends Ratmogrify.TransmogRat {
        boolean restore() { return super.act(); }
    }
    private static class PlainSprite extends CharSprite {
        PlainSprite() { texture(Assets.Interfaces.ICONS); frame(0, 0, 16, 16); }
        @Override public void link(Char owner) { ch = owner; owner.sprite = this; place(owner.pos); }
        @Override public void zap(int cell) { }
        @Override public void idle() { }
        @Override public void die() { }
        @Override public void add(State state) { }
        @Override public void remove(State state) { }
    }
    private static final class CallbackRipperSprite extends RipperSprite {
        Callback landing;
        @Override public void link(Char owner) { ch = owner; owner.sprite = this; place(owner.pos); }
        @Override public void jump(int from, int to, Callback callback) { landing = callback; }
        @Override public void add(State state) { }
        @Override public void remove(State state) { }
    }
    @FunctionalInterface private interface NativeCall { void run() throws Exception; }

    private static final class Harness implements AutoCloseable {
        final PendingWarningTest.Fixture f = new PendingWarningTest.Fixture();
        final Map<Field, Object> globals = new LinkedHashMap<>();
        final Map<Map<Object, Object>, Map<Object, Object>> bestiaryState = new java.util.IdentityHashMap<>();
        final Group sprites = new Group();
        final Files previousFiles = Gdx.files;
        final boolean soundEnabled = Sample.INSTANCE.isEnabled();
        final AtomicInteger queued = (AtomicInteger) get(null, Game.class, "queuedCallbacks");
        final int previousQueued = queued.get();

        @SuppressWarnings("unchecked") Harness() throws Exception {
            // The shared fixture supplies and restores the v4.0.2 runtime identity.
            for (Field field : PathFinder.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) preserve(field);
            }
            for (String name : List.of("all", "chars", "ids", "nextID", "now")) preserve(Actor.class.getDeclaredField(name));
            for (String name : List.of("heroMindFov", "modifiableBlocking")) preserve(Level.class.getDeclaredField(name));
            for (String name : List.of("global", "local", "saveNeeded")) preserve(Badges.class.getDeclaredField(name));
            preserve(Dungeon.class.getDeclaredField("challenges"));
            preserve(GameScene.class.getDeclaredField("cellSelector"));
            preserve(BuffIndicator.class.getDeclaredField("heroInstance"));
            preserve(Journal.class.getDeclaredField("saveNeeded"));
            preserve(Statistics.class.getDeclaredField("enemiesSlain"));
            preserve(Statistics.class.getDeclaredField("qualifiedForNoKilling"));
            for (Bestiary category : Bestiary.values()) {
                for (String name : List.of("seen", "encounterCount")) {
                    Map<Object, Object> contents = (Map<Object, Object>) get(category, Bestiary.class, name);
                    bestiaryState.put(contents, new LinkedHashMap<>(contents));
                }
            }
            set(null, Actor.class, "all", new HashSet<Actor>()); set(null, Actor.class, "chars", new HashSet<Char>());
            set(null, Actor.class, "ids", new SparseArray<Actor>()); set(null, Actor.class, "nextID", 1); set(null, Actor.class, "now", 0f);
            set(null, Level.class, "heroMindFov", null); set(null, Level.class, "modifiableBlocking", null);
            set(null, Badges.class, "global", new HashSet<>(Arrays.asList(Badges.Badge.values())));
            set(null, Badges.class, "local", new HashSet<>(Arrays.asList(Badges.Badge.values())));
            set(null, BuffIndicator.class, "heroInstance", null);
            Statistics.enemiesSlain = 0; Dungeon.challenges = 0;
            set(null, GameScene.class, "cellSelector", FloatingAppearanceTest.allocate(CellSelector.class));
            Sample.INSTANCE.enable(false); Random.pushGenerator(0x51A515L); PathFinder.setMapSize(10, 10);
            Gdx.files = (Files) Proxy.newProxyInstance(Files.class.getClassLoader(), new Class<?>[]{Files.class},
                    (proxy, method, args) -> {
                        if (method.getReturnType() == FileHandle.class && !method.getName().equals("internal")) {
                            throw new AssertionError("Suspension fixture attempted non-asset file access: " + method.getName());
                        }
                        return method.invoke(previousFiles, args);
                    });
            f.level.mobs = new HashSet<>(); f.level.heaps = new SparseArray<>(); f.level.blobs = new HashMap<>();
            f.level.plants = new SparseArray<>(); f.level.traps = new SparseArray<>(); f.level.transitions = new ArrayList<>();
            f.level.customTiles = new ArrayList<>(); f.level.customWalls = new ArrayList<>();
            for (String name : List.of("passable", "losBlocking", "flamable", "secret", "solid", "avoid", "water", "pit", "openSpace", "discoverable")) {
                set(f.level, Level.class, name, new boolean[100]);
            }
            Arrays.fill(f.level.passable, true); Arrays.fill(f.level.openSpace, true); Arrays.fill(f.level.discoverable, true);
            for (int cell = 0; cell < 100; cell++) {
                if (cell / 10 == 0 || cell / 10 == 9 || cell % 10 == 0 || cell % 10 == 9) {
                    f.level.map[cell] = Terrain.WALL; f.level.passable[cell] = false;
                    f.level.losBlocking[cell] = f.level.solid[cell] = true;
                }
            }
            f.install(Assets.Sprites.RIPPER, 512, 512); f.install(Assets.Effects.TEXT_ICONS, 512, 512);
            for (String name : List.of("emitters", "effects", "gases")) {
                Group group = new Group(); f.scene.add(group); set(f.scene, GameScene.class, name, group);
            }
            f.scene.add(sprites); set(f.scene, GameScene.class, "mobs", sprites); placeHero();
        }
        void preserve(Field field) throws Exception { field.setAccessible(true); globals.put(field, field.get(null)); }
        void placeHero() {
            Dungeon.hero.pos = 66; Dungeon.hero.ready = true;
            PlainSprite sprite = new PlainSprite(); sprites.add(sprite); sprite.link(Dungeon.hero);
        }
        PreparedRipper prepareRipper() throws Exception {
            PreparedRipper ripper = new PreparedRipper(); ripper.pos = 33; ripper.state = ripper.HUNTING;
            ripper.fieldOfView = new boolean[100]; Arrays.fill(ripper.fieldOfView, true);
            set(ripper, Mob.class, "enemy", Dungeon.hero); set(ripper, RipperDemon.class, "lastEnemyPos", 66);
            CallbackRipperSprite sprite = new CallbackRipperSprite(); sprites.add(sprite); sprite.link(ripper);
            Actor.add(ripper); f.level.mobs.add(ripper);
            assertTrue(ripper.HUNTING.act(true, false));
            assertNotNull(warning(ripper)); assertEquals(1, redAt(66));
            return ripper;
        }
        PendingWarning warning(RipperDemon ripper) throws Exception {
            return (PendingWarning) get(ripper, RipperDemon.class, "leapWarning");
        }
        RestoringRat transform(PreparedRipper original) {
            RestoringRat rat = new RestoringRat(); rat.setup(original); rat.pos = original.pos;
            Actor.remove(original); original.sprite.killAndErase(); f.level.mobs.remove(original);
            PlainSprite sprite = new PlainSprite(); sprites.add(sprite); sprite.link(rat);
            Actor.add(rat); f.level.mobs.add(rat);
            return rat;
        }
        void finishLeap(PreparedRipper ripper, PendingWarning warning) throws Exception {
            CallbackRipperSprite sprite = (CallbackRipperSprite) ripper.sprite;
            assertFalse(ripper.HUNTING.act(true, false)); assertNotNull(sprite.landing);
            assertTrue(warning.isPending()); sprite.landing.call(); assertFalse(warning.isPending());
        }
        Stasis.StasisBuff castStasis(PreparedRipper original) throws Exception {
            castStasisWithoutCheckingHolder(original);
            Stasis.StasisBuff holder = Dungeon.hero.buff(Stasis.StasisBuff.class);
            assertNotNull(holder); assertSame(original, get(holder, Stasis.StasisBuff.class, "stasisAlly"));
            return holder;
        }
        void castStasisWithoutCheckingHolder(PreparedRipper original) throws Exception {
            Buff.append(original, PowerOfMany.PowerBuff.class);
            Stasis spell = new Stasis() {
                @Override public void onSpellCast(HolyTome tome, Hero hero) { /* Charge/talent accounting is outside this fixture. */ }
            };
            withoutMapRenderer(() -> spell.onCast(null, Dungeon.hero));
        }
        long redAt(int cell) throws Exception {
            return f.frame().stream().filter(cue -> cue.kind.equals("red_target") && cue.cell == cell).count();
        }
        void fadeMarkers() {
            f.fadeMarkers();
        }
        void withoutMapRenderer(NativeCall call) throws Exception {
            set(null, GameScene.class, "scene", null);
            try { call.run(); } finally { set(null, GameScene.class, "scene", f.scene); }
        }
        @Override public void close() throws Exception {
            Gdx.files = previousFiles; queued.set(previousQueued); Random.popGenerator(); Sample.INSTANCE.enable(soundEnabled);
            try { f.close(); }
            finally {
                for (Map.Entry<Map<Object, Object>, Map<Object, Object>> entry : bestiaryState.entrySet()) {
                    entry.getKey().clear(); entry.getKey().putAll(entry.getValue());
                }
                for (Map.Entry<Field, Object> entry : globals.entrySet()) entry.getKey().set(null, entry.getValue());
            }
        }
    }
}
