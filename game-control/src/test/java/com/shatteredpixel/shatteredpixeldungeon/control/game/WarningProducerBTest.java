package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SuperNovaTracker;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM300;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DelayedRockFall;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollGeomancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollSapper;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.PendingWarning;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.ChaoticCenser;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.MissileSprite;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Callback;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import com.watabou.utils.SparseArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.get;
import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Calls native preparation, act, detach and impact paths using isolated objects and no game/profile. */
class WarningProducerBTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void dm300NativePreparationPersistsThroughFadeAndDetachCancelsTheActualBuff() throws Exception {
        try (Fixture f = new Fixture()) {
            DM300 source = f.source(new DM300(), 22);
            source.dropRocks(Dungeon.hero);
            DM300.FallingRockBuff buff = source.buff(DM300.FallingRockBuff.class);
            assertNotNull(buff);
            PendingWarning warning = buff.warning();
            assertTrue(f.markers().size() > 0);
            assertTrue(f.markers().stream().allMatch(marker -> binding(marker) == warning));
            List<VisualCue> prepared = f.red();
            assertFalse(prepared.isEmpty());
            Game.elapsed = 2.3f;
            for (TargetedCell marker : f.markers()) marker.update();
            assertEquals(prepared, f.red(), "The real falling-rock buff owns the faded warning");
            buff.detach();
            assertFalse(warning.isPending());
            assertTrue(f.red().isEmpty());
        }
    }

    @Test void nativeDelayedRockFallCompletesAfterApplyingDamageAndRejectsUnattachedWarnings() throws Exception {
        try (Fixture f = new Fixture()) {
            DM300 source = f.source(new DM300(), 22);
            DamageProbe victim = new DamageProbe(); victim.pos = Dungeon.hero.pos;
            Actor.add(victim);
            source.dropRocks(Dungeon.hero);
            DM300.FallingRockBuff buff = source.buff(DM300.FallingRockBuff.class);
            victim.warning = buff.warning();
            assertFalse(f.red().isEmpty());
            assertTrue(buff.act());
            assertTrue(victim.damage > 0, "The native FallingRockBuff.affectChar path must execute");
            assertTrue(victim.pendingDuringDamage, "A resolving batch cannot end before its damage");
            assertFalse(victim.warning.isPending());
            assertNull(source.buff(DM300.FallingRockBuff.class));
            assertTrue(f.red().isEmpty());
        }
        try (Fixture f = new Fixture()) {
            DM300 rejecting = f.source(new DM300() {
                @Override public boolean add(Buff buff) { return false; }
            }, 22);
            rejecting.dropRocks(Dungeon.hero);
            assertFalse(f.markers().isEmpty(), "Native preparation still creates its GUI images");
            assertTrue(f.markers().stream().allMatch(marker -> !binding(marker).isPending()));
            assertTrue(f.red().isEmpty(), "An unattached attack has no future act to resolve it");
        }
    }

    @Test void geomancerCreatesIndependentRockEpisodesThatFinishWithTheirOwnImpacts() throws Exception {
        try (Fixture f = new Fixture()) {
            Geomancer source = f.prepareGeomancer();
            PendingWarning[] warnings = (PendingWarning[]) get(source, GnollGeomancer.class, "throwingWarnings");
            assertEquals(3, new HashSet<>(Arrays.asList(warnings)).size());
            assertEquals(3, f.redAt(66), "All three real warning paths overlap the target cell");
            f.withoutMapRenderer(source::fire);
            assertEquals(3, f.sprites.impacts.size());
            Actor.remove(source);
            assertTrue(Arrays.stream(warnings).allMatch(PendingWarning::isPending),
                    "Launched missiles outlive their preparing actor");
            for (int i = 0; i < 3; i++) {
                final int impact = i;
                f.withoutMapRenderer(() -> f.sprites.impacts.get(impact).call());
                assertFalse(warnings[i].isPending());
                assertEquals(2 - i, f.redAt(66), "One impact must not clear another projectile's episode");
            }
            assertTrue(f.red().isEmpty());
        }
    }

    @Test void missingGeomancerRockAndSapperPreparationHaveNativeCancellationPaths() throws Exception {
        try (Fixture f = new Fixture()) {
            Geomancer source = f.prepareGeomancer();
            PendingWarning[] warnings = (PendingWarning[]) get(source, GnollGeomancer.class, "throwingWarnings");
            int[] rocks = (int[]) get(source, GnollGeomancer.class, "throwingRocksFromPos");
            Level.set(rocks[0], Terrain.EMPTY);
            f.withoutMapRenderer(source::fire);
            assertFalse(warnings[0].isPending());
            assertEquals(2, f.sprites.impacts.size());
            assertEquals(2, f.redAt(66));
            for (Callback impact : f.sprites.impacts) f.withoutMapRenderer(impact::call);
            assertTrue(f.red().isEmpty());
        }
        try (Fixture f = new Fixture()) {
            Sapper source = f.source(new Sapper(), 22);
            f.prepareSapper(source);
            PendingWarning missing = (PendingWarning) get(source, GnollSapper.class, "throwingWarning");
            Level.set(source.throwingRockFromPos, Terrain.EMPTY);
            assertTrue(source.fire(), "A missing rock does not launch an animation");
            assertFalse(missing.isPending());
            assertTrue(f.red().isEmpty());
            f.prepareSapper(source);
            PendingWarning removed = (PendingWarning) get(source, GnollSapper.class, "throwingWarning");
            assertFalse(f.red().isEmpty());
            Actor.remove(source);
            assertFalse(removed.isPending());
            assertTrue(f.red().isEmpty(), "Removing a preparing sapper cancels its unfired rock");
        }
        try (Fixture f = new Fixture()) {
            Sapper source = f.source(new Sapper(), 22);
            f.prepareSapper(source);
            PendingWarning launched = (PendingWarning) get(source, GnollSapper.class, "throwingWarning");
            assertEquals(1, f.redAt(66));
            f.withoutMapRenderer(source::fire);
            assertEquals(1, f.sprites.impacts.size());
            Actor.remove(source);
            assertTrue(launched.isPending(), "The sapper's own fire path must transfer lifetime to its missile");
            f.withoutMapRenderer(f.sprites.impacts.get(0)::call);
            assertFalse(launched.isPending());
            assertTrue(f.red().isEmpty());
        }
    }

    @Test void gnollNativeRockFallUsesItsAttachedBuffRatherThanTheThrowingEpisode() throws Exception {
        try (Fixture f = new Fixture()) {
            GnollSapper source = f.source(new GnollSapper(), 22);
            assertTrue(GnollGeomancer.prepRockFallAttack(Dungeon.hero, source, 1, false));
            GnollGeomancer.GnollRockFall buff = source.buff(GnollGeomancer.GnollRockFall.class);
            assertNotNull(buff);
            PendingWarning warning = buff.warning();
            assertFalse(f.markers().isEmpty());
            assertTrue(f.markers().stream().allMatch(marker -> binding(marker) == warning));
            assertFalse(f.red().isEmpty());
            // Keep native affectCell calls while choosing its no-new-boulder terrain branch.
            for (int cell = 0; cell < 100; cell++) {
                if (!f.base.level.solid[cell]) f.base.level.map[cell] = Terrain.EMPTY_SP;
            }
            assertTrue(buff.act());
            assertFalse(warning.isPending());
            assertTrue(f.red().isEmpty());

            assertTrue(GnollGeomancer.prepRockFallAttack(Dungeon.hero, source, 1, false));
            PendingWarning nextWarning = source.buff(GnollGeomancer.GnollRockFall.class).warning();
            assertFalse(f.red().isEmpty());
            Actor.remove(source);
            assertFalse(nextWarning.isPending());
            assertTrue(f.red().isEmpty(), "Source removal detaches and cancels the actual rock-fall buff");
        }
    }

    @Test void novaNativeGrowthKeepsOneEpisodeAndNormalDetachDoesNotEndItBeforeExplosion() throws Exception {
        try (Fixture f = new Fixture()) {
            ProbeNova nova = new ProbeNova(); nova.pos = 55;
            assertTrue(nova.attachTo(Dungeon.hero));
            assertTrue(nova.act());
            PendingWarning warning = (PendingWarning) get(nova, SuperNovaTracker.class, "pendingWarning");
            int firstSize = f.red().size();
            assertTrue(firstSize > 0);
            Game.elapsed = 2.3f;
            for (TargetedCell marker : f.markers()) marker.update();
            assertEquals(firstSize, f.red().size());
            assertTrue(nova.act());
            assertSame(warning, get(nova, SuperNovaTracker.class, "pendingWarning"));
            assertTrue(f.red().size() > firstSize, "Native next-turn markers enlarge the same episode");

            set(nova, SuperNovaTracker.class, "turnsLeft", 0);
            // Exercise the real explosion branch and detach ordering, with no eligible blast cells.
            // Damage/effect rendering is deliberately outside this lifecycle fixture.
            Arrays.fill(f.base.level.solid, true);
            f.withoutMapRenderer(nova::act);
            assertTrue(nova.pendingAfterDetach, "Normal detachment must retain the committed episode");
            assertFalse(warning.isPending(), "The native explosion branch must close its episode at its end");
            assertTrue(f.red().isEmpty());
        }
        try (Fixture f = new Fixture()) {
            SuperNovaTracker nova = new SuperNovaTracker(); nova.pos = 55;
            assertTrue(nova.attachTo(Dungeon.hero)); nova.act();
            PendingWarning warning = (PendingWarning) get(nova, SuperNovaTracker.class, "pendingWarning");
            assertFalse(f.red().isEmpty());
            nova.detach();
            assertFalse(warning.isPending());
            assertTrue(f.red().isEmpty(), "Early native detach cancels rather than commits the nova");
        }
    }

    @Test void chaoticCenserCreatesTheGasWarningAndCrossDepthActCancelsWithoutSeeding() throws Exception {
        try (Fixture f = new Fixture()) {
            Dungeon.hero.belongings.backpack.items.add(new ChaoticCenser());
            Method produce = ChaoticCenser.class.getDeclaredMethod("produceGas", Char.class);
            produce.setAccessible(true);
            assertEquals(true, produce.invoke(null, Dungeon.hero));
            ChaoticCenser.GasSpewer spewer = Dungeon.hero.buff(ChaoticCenser.GasSpewer.class);
            assertNotNull(spewer);
            PendingWarning warning = (PendingWarning) get(spewer, ChaoticCenser.GasSpewer.class, "pendingWarning");
            assertFalse(f.red().isEmpty());
            assertTrue(f.markers().stream().allMatch(marker -> binding(marker) == warning));
            Dungeon.depth++;
            assertTrue((boolean) get(warning, PendingWarning.class, "active"));
            assertTrue(spewer.act());
            // Check the native cancellation itself before a collector/isPending scope check can run.
            assertFalse((boolean) get(warning, PendingWarning.class, "active"));
            assertTrue(f.base.level.blobs.isEmpty());
            assertNull(Dungeon.hero.buff(ChaoticCenser.GasSpewer.class));
            assertTrue(f.red().isEmpty());
        }
        try (Fixture f = new Fixture()) {
            Dungeon.hero.belongings.backpack.items.add(new ChaoticCenser());
            Method produce = ChaoticCenser.class.getDeclaredMethod("produceGas", Char.class);
            produce.setAccessible(true);
            assertEquals(true, produce.invoke(null, Dungeon.hero));
            ChaoticCenser.GasSpewer spewer = Dungeon.hero.buff(ChaoticCenser.GasSpewer.class);
            assertNotNull(spewer);
            PendingWarning warning = (PendingWarning) get(spewer, ChaoticCenser.GasSpewer.class, "pendingWarning");
            int cell = (int) get(spewer, ChaoticCenser.GasSpewer.class, "targetCell");
            int quantity = (int) get(spewer, ChaoticCenser.GasSpewer.class, "gasQuantity");
            Class<?> gasType = (Class<?>) get(spewer, ChaoticCenser.GasSpewer.class, "gasType");
            assertFalse(f.red().isEmpty());
            // Native Blob.seed/add and MagicMissile run; only GameScene's blob renderer is absent.
            f.withoutMapRenderer(spewer::act);
            Blob spawned = f.base.level.blobs.get(gasType);
            assertNotNull(spawned);
            assertEquals(quantity, spawned.cur[cell], "The selected native gas must actually be seeded");
            assertFalse(warning.isPending());
            assertNull(Dungeon.hero.buff(ChaoticCenser.GasSpewer.class));
            assertTrue(f.red().isEmpty(), "A surviving marker cannot restore the seeded gas warning");
        }
    }

    private static PendingWarning binding(TargetedCell marker) {
        try { return (PendingWarning) get(marker, TargetedCell.class, "warning"); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static final class Geomancer extends GnollGeomancer { boolean fire() { return super.act(); } }
    private static final class Sapper extends GnollSapper { boolean fire() { return super.act(); } }
    private static final class DamageProbe extends Char {
        PendingWarning warning; int damage; boolean pendingDuringDamage;
        DamageProbe() { HP = HT = 1000; }
        @Override public void damage(int amount, Object source) {
            damage += amount; pendingDuringDamage = warning.isPending();
        }
    }
    private static final class ProbeNova extends SuperNovaTracker {
        boolean pendingAfterDetach;
        @Override public void detach() {
            super.detach();
            try {
                PendingWarning warning = (PendingWarning) get(this, SuperNovaTracker.class, "pendingWarning");
                pendingAfterDetach = warning != null && warning.isPending();
            } catch (Exception error) { throw new AssertionError(error); }
        }
    }
    private static final class IdleSprite extends CharSprite {
        IdleSprite() { texture(Assets.Interfaces.ICONS); frame(0, 0, 16, 16); }
        @Override public void attack(int cell, Callback callback) { callback.call(); }
        @Override public void idle() { }
    }
    private static final class CapturedMissile extends MissileSprite {
        List<Callback> impacts;
        @Override public void reset(int from, int to, Item item, Callback callback) { impacts.add(callback); }
    }
    private static final class Sprites extends Group {
        final List<Callback> impacts = new ArrayList<>();
        @Override public synchronized Gizmo recycle(Class<? extends Gizmo> type) {
            if (type == MissileSprite.class) {
                try {
                    CapturedMissile missile = FloatingAppearanceTest.allocate(CapturedMissile.class);
                    missile.impacts = impacts; return missile;
                } catch (Exception error) { throw new AssertionError(error); }
            }
            return super.recycle(type);
        }
    }

    @FunctionalInterface private interface NativeCall { void run() throws Exception; }

    private static final class Fixture implements AutoCloseable {
        final PendingWarningTest.Fixture base = new PendingWarningTest.Fixture();
        final Sprites sprites = new Sprites();
        final Map<String, Object> actorState = new LinkedHashMap<>();
        final Map<Field, Object> pathfinderState = new LinkedHashMap<>();
        final Object selector = get(null, GameScene.class, "cellSelector");
        final Object previousRocksInFlight = get(null, GnollGeomancer.class, "rocksInFlight");
        final Object previousKnockedChars = get(null, GnollGeomancer.class, "knockedChars");
        final int previousChallenges = Dungeon.challenges;
        final boolean soundEnabled = Sample.INSTANCE.isEnabled();
        final AtomicInteger queuedCallbacks = (AtomicInteger) get(null, Game.class, "queuedCallbacks");
        final int previousQueuedCallbacks = queuedCallbacks.get();
        final Set<Object> sounds;
        final Set<Object> previousSounds;

        @SuppressWarnings("unchecked") Fixture() throws Exception {
            for (String name : List.of("all", "chars", "ids", "nextID", "now")) actorState.put(name, get(null, Actor.class, name));
            set(null, Actor.class, "all", new HashSet<Actor>());
            set(null, Actor.class, "chars", new HashSet<Char>());
            set(null, Actor.class, "ids", new SparseArray<Actor>());
            set(null, Actor.class, "nextID", 1); set(null, Actor.class, "now", 0f);
            for (Field field : PathFinder.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    field.setAccessible(true); pathfinderState.put(field, field.get(null));
                }
            }
            PathFinder.setMapSize(10, 10);
            Random.pushGenerator(3719L);
            Dungeon.challenges = 0;
            Sample.INSTANCE.enable(false);
            sounds = (Set<Object>) get(null, Sample.class, "delayedSFX");
            previousSounds = new HashSet<>(sounds);
            set(null, GameScene.class, "cellSelector", FloatingAppearanceTest.allocate(CellSelector.class));
            for (String name : List.of("emitters", "effects")) {
                Group group = new Group(); base.scene.add(group); set(base.scene, GameScene.class, name, group);
            }
            base.scene.add(sprites);
            base.install(Assets.Effects.TEXT_ICONS, 512, 512);
            base.level.mobs = new HashSet<>(); base.level.heaps = new SparseArray<>();
            base.level.blobs = new HashMap<>(); base.level.plants = new SparseArray<>();
            base.level.traps = new SparseArray<>(); base.level.transitions = new ArrayList<>();
            base.level.customTiles = new ArrayList<>(); base.level.customWalls = new ArrayList<>();
            base.level.passable = new boolean[100]; Arrays.fill(base.level.passable, true);
            base.level.solid = new boolean[100]; base.level.avoid = new boolean[100];
            base.level.losBlocking = new boolean[100]; base.level.flamable = new boolean[100];
            base.level.secret = new boolean[100]; base.level.pit = new boolean[100];
            base.level.water = new boolean[100]; base.level.openSpace = new boolean[100];
            base.level.discoverable = new boolean[100]; Arrays.fill(base.level.discoverable, true);
            Arrays.fill(base.level.openSpace, true);
            for (int cell = 0; cell < 100; cell++) {
                if (cell / 10 == 0 || cell / 10 == 9 || cell % 10 == 0 || cell % 10 == 9) {
                    base.level.map[cell] = Terrain.WALL; base.level.solid[cell] = true;
                    base.level.losBlocking[cell] = true; base.level.passable[cell] = false;
                }
            }
            Dungeon.hero.pos = 66; sprite(Dungeon.hero);
            set(null, GnollGeomancer.class, "rocksInFlight", 0);
            set(null, GnollGeomancer.class, "knockedChars", new ArrayList<Char>());
        }
        void sprite(Char owner) {
            owner.sprite = new IdleSprite(); owner.sprite.ch = owner; sprites.add(owner.sprite);
        }
        <T extends Mob> T source(T source, int cell) {
            source.pos = cell; source.fieldOfView = new boolean[100]; Arrays.fill(source.fieldOfView, true);
            sprite(source); Actor.add(source); return source;
        }
        Geomancer prepareGeomancer() throws Exception {
            Geomancer source = source(new Geomancer(), 22); source.HP = 1;
            set(source, Mob.class, "enemy", Dungeon.hero);
            set(source, GnollGeomancer.class, "abilityCooldown", 0);
            set(source, GnollGeomancer.class, "lastAbilityWasRockfall", true);
            set(source, GnollGeomancer.class, "sapperSpawns", new int[]{-1, -1, -1});
            for (int cell : new int[]{33, 36, 63}) Level.set(cell, Terrain.MINE_BOULDER);
            assertTrue(source.HUNTING.act(true, false));
            return source;
        }
        void prepareSapper(Sapper source) throws Exception {
            Level.set(33, Terrain.MINE_BOULDER);
            set(source, Mob.class, "enemy", Dungeon.hero);
            set(source, GnollSapper.class, "abilityCooldown", 0);
            set(source, GnollSapper.class, "lastAbilityWasRockfall", true);
            assertTrue(source.HUNTING.act(true, false));
        }
        List<TargetedCell> markers() {
            return sprites.childrenSnapshot().stream().filter(TargetedCell.class::isInstance)
                    .map(TargetedCell.class::cast).collect(Collectors.toList());
        }
        List<VisualCue> red() throws Exception {
            return base.frame().stream().filter(cue -> cue.kind.equals("red_target")).collect(Collectors.toList());
        }
        long redAt(int cell) throws Exception { return red().stream().filter(cue -> cue.cell == cell).count(); }
        void withoutMapRenderer(NativeCall call) throws Exception {
            set(null, GameScene.class, "scene", null);
            try { call.run(); } finally { set(null, GameScene.class, "scene", base.scene); }
        }
        @Override public void close() throws Exception {
            for (Map.Entry<String, Object> entry : actorState.entrySet()) set(null, Actor.class, entry.getKey(), entry.getValue());
            for (Map.Entry<Field, Object> entry : pathfinderState.entrySet()) entry.getKey().set(null, entry.getValue());
            sounds.clear(); sounds.addAll(previousSounds);
            Sample.INSTANCE.enable(soundEnabled); Dungeon.challenges = previousChallenges;
            queuedCallbacks.set(previousQueuedCallbacks);
            set(null, GnollGeomancer.class, "rocksInFlight", previousRocksInFlight);
            set(null, GnollGeomancer.class, "knockedChars", previousKnockedChars);
            Random.popGenerator(); set(null, GameScene.class, "cellSelector", selector);
            base.close();
        }
    }
}
