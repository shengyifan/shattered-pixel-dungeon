package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.CrystalSpire;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Elemental.NewbornFireElemental;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.RipperDemon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.YogDzewa;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultLaser;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultSentry;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.PendingWarning;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BossHealthBar;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import com.watabou.utils.SparseArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
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
import java.util.TreeSet;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.get;
import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Native producer branches in memory only: no renderer, scheduler, profile, save or playthrough. */
class WarningProducerATest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void yogRootedDelayKeepsEpisodeUntilNativeOwnerRemoval() throws Exception {
        try (Harness h = new Harness()) {
            YogDzewa yog = h.place(new YogDzewa(), 55);
            set(yog, YogDzewa.class, "phase", 1);
            set(yog, YogDzewa.class, "abilityCooldown", 10f);
            set(yog, YogDzewa.class, "summonCooldown", 10f);
            set(yog, YogDzewa.class, "targetedCells", new ArrayList<>(List.of(22)));
            PendingWarning warning = h.prepared(yog, YogDzewa.class, "beamWarning", 22);
            Dungeon.hero.rooted = true;
            h.fadeMarkers();

            assertTrue(act(yog, YogDzewa.class));
            assertTrue(warning.isPending(), "Rooting the hero defers native beam resolution");
            assertEquals(List.of(22), get(yog, YogDzewa.class, "targetedCells"));
            assertEquals(Set.of(22), h.redCells());

            Actor.remove(yog);
            assertFalse(warning.isPending(), "Removing the preparing owner cancels its native warning");
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void yogNativeBeamProcessingCompletesThePreparedEpisode() throws Exception {
        try (Harness h = new Harness()) {
            YogDzewa yog = h.place(new YogDzewa(), 55);
            set(yog, YogDzewa.class, "phase", 1);
            set(yog, YogDzewa.class, "abilityCooldown", 10f);
            set(yog, YogDzewa.class, "summonCooldown", 10f);
            set(yog, YogDzewa.class, "targetedCells", new ArrayList<>(List.of(22)));
            PendingWarning warning = h.prepared(yog, YogDzewa.class, "beamWarning", 22);

            assertTrue(act(yog, YogDzewa.class));
            assertFalse(warning.isPending());
            assertEquals(List.of(), get(yog, YogDzewa.class, "targetedCells"));
            assertNull(get(yog, YogDzewa.class, "beamWarning"));
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void ripperRootedCancellationClosesPreparedEpisode() throws Exception {
        try (Harness h = new Harness()) {
            RipperDemon ripper = h.place(new RipperDemon(), 33);
            set(ripper, RipperDemon.class, "leapPos", 45);
            PendingWarning warning = h.prepared(ripper, RipperDemon.class, "leapWarning", 45);
            ripper.rooted = true;

            assertTrue(ripper.HUNTING.act(true, false));
            assertFalse(warning.isPending());
            assertEquals(-1, get(ripper, RipperDemon.class, "leapPos"));
            assertNull(((CallbackSprite) ripper.sprite).jumpCallback);
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void ripperNoLandingCancellationClosesPreparedEpisode() throws Exception {
        try (Harness h = new Harness()) {
            RipperDemon ripper = h.place(new RipperDemon(), 43);
            Dungeon.hero.pos = 44;
            Actor.add(Dungeon.hero);
            Arrays.fill(h.f.level.passable, false);
            Arrays.fill(h.f.level.solid, true);
            h.f.level.passable[44] = true;
            h.f.level.solid[44] = false;
            set(ripper, RipperDemon.class, "leapPos", 44);
            PendingWarning warning = h.prepared(ripper, RipperDemon.class, "leapWarning", 44);

            assertTrue(ripper.HUNTING.act(true, false));
            assertFalse(warning.isPending());
            assertEquals(-1, get(ripper, RipperDemon.class, "leapPos"));
            assertNull(((CallbackSprite) ripper.sprite).jumpCallback);
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void ripperLandingCallbackOwnsCompletionAfterLaunch() throws Exception {
        try (Harness h = new Harness()) {
            RipperDemon ripper = h.place(new RipperDemon(), 33);
            set(ripper, RipperDemon.class, "leapPos", 45);
            PendingWarning warning = h.prepared(ripper, RipperDemon.class, "leapWarning", 45);
            CallbackSprite sprite = (CallbackSprite) ripper.sprite;

            assertFalse(ripper.HUNTING.act(true, false));
            assertNotNull(sprite.jumpCallback, "The actual hunting branch must schedule its jump callback");
            assertEquals(33, ripper.pos);
            assertTrue(warning.isPending());
            assertNull(get(ripper, RipperDemon.class, "leapWarning"));
            Actor.remove(ripper);
            assertTrue(warning.isPending(), "A launched jump retains its independent callback lifetime");
            assertEquals(Set.of(45), h.redCells());

            sprite.jumpCallback.call();
            assertEquals(45, ripper.pos);
            assertEquals(-1, get(ripper, RipperDemon.class, "leapPos"));
            assertTrue(sprite.idled, "Native landing calls idle before completing the episode");
            assertFalse(warning.isPending());
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void crystalSpireCompletesEachWaveBeforePublishingTheNext() throws Exception {
        try (Harness h = new Harness()) {
            CrystalSpire spire = h.place(new CrystalSpire(), 66);
            ArrayList<ArrayList<Integer>> waves = new ArrayList<>();
            waves.add(new ArrayList<>(List.of(43)));
            waves.add(new ArrayList<>(List.of(45)));
            set(spire, CrystalSpire.class, "targetedCells", waves);
            PendingWarning first = h.prepared(spire, CrystalSpire.class, "waveWarning", 43);
            assertEquals(Set.of(43), h.redCells(), "The queued second wave is still private");

            assertTrue(actWithoutMapRenderer(spire, CrystalSpire.class));
            PendingWarning second = (PendingWarning) get(spire, CrystalSpire.class, "waveWarning");
            assertFalse(first.isPending());
            assertNotNull(second);
            assertNotSame(first, second);
            assertTrue(second.isPending());
            assertEquals(Terrain.MINE_CRYSTAL, h.f.level.map[43]);
            assertEquals(Terrain.EMPTY, h.f.level.map[45]);
            assertEquals(Set.of(45), h.redCells());

            h.fadeMarkers();
            assertTrue(actWithoutMapRenderer(spire, CrystalSpire.class));
            assertFalse(second.isPending());
            assertNull(get(spire, CrystalSpire.class, "waveWarning"));
            assertEquals(Terrain.MINE_CRYSTAL, h.f.level.map[45]);
            assertTrue(waves.isEmpty());
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void newbornElementalCancelsPreparedFireballOutsideHunting() throws Exception {
        try (Harness h = new Harness()) {
            NewbornFireElemental elemental = h.place(new NewbornFireElemental(), 33);
            set(elemental, NewbornFireElemental.class, "targetingPos", 44);
            PendingWarning warning = h.prepared(elemental, NewbornFireElemental.class, "fireballWarning", 44);
            elemental.state = elemental.PASSIVE;
            elemental.paralysed = 1; // Stop the later generic Mob branch after the native cancellation.

            assertTrue(act(elemental, NewbornFireElemental.class));
            assertFalse(warning.isPending());
            assertEquals(-1, get(elemental, NewbornFireElemental.class, "targetingPos"));
            assertNull(get(elemental, NewbornFireElemental.class, "fireballWarning"));
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void newbornElementalLaunchRetainsWarningUntilNativeZapCompletes() throws Exception {
        try (Harness h = new Harness()) {
            NewbornFireElemental elemental = h.place(new NewbornFireElemental(), 22);
            elemental.state = elemental.HUNTING;
            set(elemental, NewbornFireElemental.class, "targetingPos", 44);
            PendingWarning warning = h.prepared(elemental, NewbornFireElemental.class, "fireballWarning", 44);
            CallbackSprite sprite = (CallbackSprite) elemental.sprite;
            h.f.level.water[33] = true;

            assertFalse(act(elemental, NewbornFireElemental.class));
            assertEquals(44, sprite.zapCell);
            assertTrue(warning.isPending());
            assertNull(h.f.level.blobs.get(Fire.class), "Animation start is not the native fire application");
            Actor.remove(elemental);
            assertTrue(warning.isPending(), "Owner removal cannot cancel an already launched fireball");
            assertEquals(Set.of(44), h.redCells());

            elemental.onZapComplete();
            Fire fire = (Fire) h.f.level.blobs.get(Fire.class);
            assertNotNull(fire);
            for (int offset : PathFinder.NEIGHBOURS9) {
                assertEquals(offset + 44 == 33 ? 2 : 8, fire.cur[offset + 44]);
            }
            assertFalse(warning.isPending());
            assertEquals(-1, get(elemental, NewbornFireElemental.class, "targetingPos"));
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void vaultLaserCompletesCurrentEpisodeBeforeWarningNextDirection() throws Exception {
        try (Harness h = new Harness()) {
            VaultLaser laser = h.place(new VaultLaser(), 44);
            laser.laserDirs = new int[]{45, 54};
            laser.curCooldown = 2;
            laser.afterShotCooldown = 1;
            assertTrue(act(laser, VaultLaser.class));
            PendingWarning first = (PendingWarning) get(laser, VaultLaser.class, "beamWarning");
            assertNotNull(first);
            Set<Integer> firstCells = h.redCells();
            assertTrue(firstCells.contains(45));
            assertFalse(firstCells.contains(54));
            h.fadeMarkers();

            assertTrue(act(laser, VaultLaser.class));
            PendingWarning second = (PendingWarning) get(laser, VaultLaser.class, "beamWarning");
            assertFalse(first.isPending());
            assertNotNull(second);
            assertNotSame(first, second);
            assertTrue(second.isPending());
            assertEquals(1, laser.laserDirIdx);
            assertTrue(h.redCells().contains(54));
            assertFalse(h.redCells().contains(45));

            laser.giveWarning = false;
            assertTrue(act(laser, VaultLaser.class));
            assertFalse(second.isPending());
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void vaultSentryCompletesCurrentEpisodeBeforeWarningNextScan() throws Exception {
        try (Harness h = new Harness()) {
            VaultSentry sentry = h.place(new VaultSentry(), 44);
            sentry.scanDirs = new int[][]{{45}, {54}};
            sentry.scanWidth = 0;
            sentry.scanLength = 3;
            sentry.curCooldown = 2;
            sentry.afterScanCooldown = 1;
            sentry.giveWarning = true;
            assertTrue(act(sentry, VaultSentry.class));
            PendingWarning first = (PendingWarning) get(sentry, VaultSentry.class, "scanWarning");
            assertNotNull(first);
            assertTrue(h.redCells().contains(45));
            assertFalse(h.redCells().contains(54));
            h.fadeMarkers();

            assertTrue(act(sentry, VaultSentry.class));
            PendingWarning second = (PendingWarning) get(sentry, VaultSentry.class, "scanWarning");
            assertFalse(first.isPending());
            assertNotNull(second);
            assertNotSame(first, second);
            assertTrue(second.isPending());
            assertEquals(1, sentry.scanDirIdx);
            assertTrue(h.redCells().contains(54));
            assertFalse(h.redCells().contains(45));

            sentry.giveWarning = false;
            assertTrue(act(sentry, VaultSentry.class));
            assertFalse(second.isPending());
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void restoringNativeTargetsDoesNotReconstructWarningEpisodes() throws Exception {
        try (Harness h = new Harness()) {
            YogDzewa yog = new YogDzewa();
            set(yog, YogDzewa.class, "phase", 1);
            set(yog, YogDzewa.class, "targetedCells", new ArrayList<>(List.of(22, 44)));
            h.prepared(yog, YogDzewa.class, "beamWarning", 22);
            RipperDemon ripper = new RipperDemon();
            set(ripper, RipperDemon.class, "leapPos", 45);
            h.prepared(ripper, RipperDemon.class, "leapWarning", 45);
            CrystalSpire spire = new CrystalSpire();
            ArrayList<ArrayList<Integer>> waves = new ArrayList<>();
            waves.add(new ArrayList<>(List.of(43)));
            waves.add(new ArrayList<>(List.of(45)));
            set(spire, CrystalSpire.class, "targetedCells", waves);
            h.prepared(spire, CrystalSpire.class, "waveWarning", 43);
            Bundle yogBundle = new Bundle(), ripperBundle = new Bundle(), spireBundle = new Bundle();
            yog.storeInBundle(yogBundle);
            ripper.storeInBundle(ripperBundle);
            spire.storeInBundle(spireBundle);
            assertFalse(yogBundle.contains("beamWarning"));
            assertFalse(ripperBundle.contains("leapWarning"));
            assertFalse(spireBundle.contains("waveWarning"));

            PendingWarning.clear(); // A fresh process has no previously observed warning cells.
            YogDzewa restoredYog = new YogDzewa();
            RipperDemon restoredRipper = new RipperDemon();
            CrystalSpire restoredSpire = new CrystalSpire();
            restoredYog.restoreFromBundle(yogBundle);
            restoredRipper.restoreFromBundle(ripperBundle);
            restoredSpire.restoreFromBundle(spireBundle);
            assertEquals(List.of(22, 44), get(restoredYog, YogDzewa.class, "targetedCells"));
            assertEquals(45, get(restoredRipper, RipperDemon.class, "leapPos"));
            assertEquals(waves, get(restoredSpire, CrystalSpire.class, "targetedCells"));
            assertNull(get(restoredYog, YogDzewa.class, "beamWarning"));
            assertNull(get(restoredRipper, RipperDemon.class, "leapWarning"));
            assertNull(get(restoredSpire, CrystalSpire.class, "waveWarning"));
            assertTrue(h.redCells().isEmpty(), "Restoring private native targets cannot disclose historical warnings");
        }
    }

    private static boolean act(Object actor, Class<?> owner) throws Exception {
        Method method = owner.getDeclaredMethod("act");
        method.setAccessible(true);
        try {
            return (boolean) method.invoke(actor);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            if (error.getCause() instanceof Error) throw (Error) error.getCause();
            throw error;
        }
    }

    private static boolean actWithoutMapRenderer(Object actor, Class<?> owner) throws Exception {
        Object scene = get(null, GameScene.class, "scene");
        set(null, GameScene.class, "scene", null);
        try { return act(actor, owner); }
        finally { set(null, GameScene.class, "scene", scene); }
    }

    private static final class CallbackSprite extends CharSprite {
        Callback jumpCallback;
        int zapCell = -1;
        boolean idled;
        CallbackSprite() { texture(Assets.Interfaces.ICONS); frame(0, 0, 16, 16); }
        @Override public void jump(int from, int to, Callback callback) { jumpCallback = callback; }
        @Override public void zap(int cell) { zapCell = cell; }
        @Override public void idle() { idled = true; }
    }

    /** Adds native map and actor support while preserving the shared fixture's profile-free boundary. */
    private static final class Harness implements AutoCloseable {
        final PendingWarningTest.Fixture f = new PendingWarningTest.Fixture();
        final Map<Field, Object> globals = new LinkedHashMap<>();
        final boolean soundEnabled = Sample.INSTANCE.isEnabled();
        Harness() throws Exception {
            for (Field field : PathFinder.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) preserve(field);
            }
            for (String field : List.of("all", "chars", "ids", "nextID", "now")) {
                preserve(Actor.class.getDeclaredField(field));
            }
            preserve(Dungeon.class.getDeclaredField("challenges"));
            preserve(VaultLaser.class.getDeclaredField("SFXLastPlayed"));
            preserve(VaultSentry.class.getDeclaredField("SFXLastPlayed"));
            preserve(GameScene.class.getDeclaredField("cellSelector"));
            for (String field : List.of("boss", "instance", "bleeding")) preserve(BossHealthBar.class.getDeclaredField(field));
            set(null, Actor.class, "all", new HashSet<Actor>());
            set(null, Actor.class, "chars", new HashSet<Char>());
            set(null, Actor.class, "ids", new SparseArray<Actor>());
            set(null, Actor.class, "nextID", 1);
            set(null, Actor.class, "now", 0f);
            Dungeon.challenges = 0;
            Sample.INSTANCE.enable(false);
            set(null, BossHealthBar.class, "instance", null);
            set(null, GameScene.class, "cellSelector", FloatingAppearanceTest.allocate(CellSelector.class));
            PathFinder.setMapSize(10, 10);
            Random.pushGenerator(0xA77AC);
            f.level.mobs = new HashSet<>();
            f.level.heaps = new SparseArray<>();
            f.level.blobs = new HashMap<>();
            f.level.plants = new SparseArray<>();
            f.level.traps = new SparseArray<>();
            f.level.customTiles = new ArrayList<>();
            f.level.customWalls = new ArrayList<>();
            for (String field : List.of("passable", "losBlocking", "flamable", "secret", "solid", "avoid",
                    "water", "pit", "openSpace", "discoverable")) {
                set(f.level, Level.class, field, new boolean[100]);
            }
            Arrays.fill(f.level.passable, true);
            Arrays.fill(f.level.discoverable, true);
            for (int cell = 0; cell < 100; cell++) {
                if (cell / 10 == 0 || cell / 10 == 9 || cell % 10 == 0 || cell % 10 == 9) {
                    f.level.map[cell] = Terrain.WALL;
                    f.level.passable[cell] = false;
                    f.level.losBlocking[cell] = f.level.solid[cell] = true;
                }
            }
            f.install(Assets.Effects.EFFECTS, 64, 64);
            f.install("1x1:" + 0xFF55AAFF, 1, 1);
            f.install("1x1:" + 0xFFFFFFFF, 1, 1);
            for (String field : List.of("emitters", "gases", "effects")) {
                Group group = new Group();
                f.scene.add(group);
                set(f.scene, GameScene.class, field, group);
            }
        }
        void preserve(Field field) throws Exception {
            field.setAccessible(true);
            globals.put(field, field.get(null));
        }
        <T extends Char> T place(T actor, int cell) {
            actor.pos = cell;
            actor.sprite = new CallbackSprite();
            actor.sprite.place(cell);
            f.scene.add(actor.sprite);
            return actor;
        }
        PendingWarning prepared(Actor actor, Class<?> owner, String field, int cell) throws Exception {
            PendingWarning warning = PendingWarning.begin(actor);
            set(actor, owner, field, warning);
            f.marker(cell, warning);
            assertTrue(redCells().contains(cell), "The isolated prepared warning must first be publicly observed");
            return warning;
        }
        Set<Integer> redCells() throws Exception {
            Set<Integer> result = new TreeSet<>();
            for (VisualCue cue : f.frame()) if (cue.kind.equals("red_target")) result.add(cue.cell);
            return result;
        }
        void fadeMarkers() {
            Game.elapsed = 2.3f;
            for (var member : f.scene.childrenSnapshot()) {
                if (member instanceof TargetedCell) ((TargetedCell) member).update();
            }
        }
        @Override public void close() throws Exception {
            Random.popGenerator();
            Sample.INSTANCE.enable(soundEnabled);
            try { f.close(); }
            finally { for (Map.Entry<Field, Object> entry : globals.entrySet()) entry.getKey().set(null, entry.getValue()); }
        }
    }
}
