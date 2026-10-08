package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.GameplayVisualKinds;
import com.shatteredpixel.shatteredpixeldungeon.effects.ShieldHalo;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.PendingWarning;
import com.shatteredpixel.shatteredpixeldungeon.effects.VisualCueCollector;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.AlchemyScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Native warning and shield lifetimes with no renderer, actor step, profile or saved targeting state. */
class PendingWarningTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void markerFadeKeepsKnownPendingCellsAndRevisionUntilResolution() throws Exception {
        try (Fixture f = new Fixture()) {
            GameController.State before = f.capture();
            PendingWarning warning = PendingWarning.begin(new Owner());
            TargetedCell marker = f.marker(22, warning);
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            GameController.State shown = f.capture();
            assertNotEquals(before.version, shown.version, "New public danger must invalidate an old decision");
            Game.elapsed = 2.3f;
            marker.update();
            assertFalse(marker.alive, "The actual native image has faded completely");
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            assertEquals(shown.version, f.capture().version, "A wall-clock fade cannot end an unresolved warning");
            warning.commit();
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            assertEquals(shown.version, f.capture().version, "Launching is not completion");
            warning.complete();
            assertTrue(f.frame().isEmpty());
            assertNotEquals(shown.version, f.capture().version, "Actual resolution still invalidates intent");
            warning.complete();
            assertTrue(f.frame().isEmpty(), "Terminal callbacks are idempotent");
        }
    }

    @Test void onlyObservedCurrentFovCellsSurviveAndCompletedMarkersCannotReturn() throws Exception {
        try (Fixture f = new Fixture()) {
            PendingWarning warning = PendingWarning.begin(new Owner());
            TargetedCell seen = f.marker(22, warning), hidden = f.marker(23, warning);
            f.level.heroFOV[23] = false;
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            Game.elapsed = 2.3f; seen.update(); hidden.update();
            f.level.heroFOV[22] = false; f.level.heroFOV[23] = true;
            assertTrue(f.frame().isEmpty(), "A target never publicly observed cannot be reconstructed after its image fades");
            f.level.heroFOV[22] = true;
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            TargetedCell later = f.marker(24, warning);
            assertEquals(List.of(new VisualCue("red_target", 22), new VisualCue("red_target", 24)), f.frame());
            warning.cancel();
            assertTrue(later.alive);
            assertTrue(f.frame().isEmpty(), "A cancelled episode's still-visible marker cannot resurrect it");
            assertFalse(warning.isPending());
        }
    }

    @Test void independentEpisodesKeepMultiplicityAndOwnerRemovalRespectsCommittedCallbacks() throws Exception {
        try (Fixture f = new Fixture()) {
            Owner owner = new Owner();
            PendingWarning first = PendingWarning.begin(owner), second = PendingWarning.begin(owner);
            f.marker(22, first); f.marker(22, first); f.marker(22, second);
            assertEquals(List.of(new VisualCue("red_target", 22), new VisualCue("red_target", 22)), f.frame(),
                    "Repeated images share one attack, independent attacks remain separate");
            second.commit();
            Actor.remove(owner);
            assertFalse(first.isPending()); assertTrue(second.isPending());
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            second.complete();
            assertTrue(f.frame().isEmpty());
            PendingWarning next = PendingWarning.begin(new Owner());
            f.marker(22, next);
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            first.complete();
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame(), "An old callback cannot close a new episode");
        }
    }

    @Test void scopeAndRunBoundariesClearKnowledgeButSameLevelSceneRebuildDoesNot() throws Exception {
        try (Fixture f = new Fixture()) {
            PendingWarning warning = PendingWarning.begin(new Owner());
            f.marker(22, warning); f.frame();
            f.replaceScene();
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame(), "A scene/UI rebuild is not a new level");
            Dungeon.level = new Grid();
            assertTrue(f.frame().isEmpty()); assertFalse(warning.isPending());
            Dungeon.level = f.level;
            assertTrue(f.frame().isEmpty(), "Returning to a level cannot reconstruct old private targeting state");
            warning = PendingWarning.begin(new Owner()); f.marker(22, warning); f.frame();
            Dungeon.branch++;
            assertTrue(f.frame().isEmpty()); assertFalse(warning.isPending());
            warning = PendingWarning.begin(new Owner()); f.marker(22, warning); f.frame();
            Dungeon.runId = "new-warning-run";
            assertTrue(f.frame().isEmpty()); assertFalse(warning.isPending());
            warning = PendingWarning.begin(new Owner()); f.marker(22, warning); f.frame();
            PendingWarning.endRun();
            assertTrue(f.frame().isEmpty()); assertFalse(warning.isPending());
            assertFalse(PendingWarning.begin(new Owner()).isPending(), "Remaining death callbacks cannot publish new danger");
            PendingWarning.clear();
            assertTrue(f.frame().isEmpty(), "New/load process knowledge starts empty even if old images remain");
        }
    }

    @Test void shieldTailKeepsDrawingWithoutReintroducingRemovedSemanticState() throws Exception {
        try (Fixture f = new Fixture()) {
            ShieldSprite sprite = new ShieldSprite(); f.scene.add(sprite); sprite.place(22);
            ShieldHalo halo = new ShieldHalo(sprite); f.scene.add(halo); sprite.install(halo);
            Game.elapsed = 0; halo.update();
            assertEquals(List.of(new VisualCue("character_shielded", 22)), f.frame());
            GameController.State shielded = f.capture();
            sprite.removeShield();
            assertTrue(halo.alive, "The native GUI tail remains alive");
            assertTrue(f.frame().isEmpty());
            GameController.State removed = f.capture();
            assertNotEquals(shielded.version, removed.version, "Real shielding loss remains decision relevant");
            Game.elapsed = .5f; halo.update();
            assertTrue(halo.alive); assertTrue(f.frame().isEmpty());
            assertEquals(removed.version, f.capture().version);
            Game.elapsed = .6f; halo.update();
            assertFalse(halo.alive); assertTrue(f.frame().isEmpty());
            assertEquals(removed.version, f.capture().version, "Completing the GUI fade cannot stale the returned revision");
            assertTrue(GameplayVisualKinds.affectsIntent("character_shielded"));
            assertTrue(GameplayVisualKinds.affectsIntent("red_target"));
        }
    }

    @Test void menuScopeClearsWarningsWhileAlchemyAndSameLevelSceneKeepThem() throws Exception {
        try (Fixture f = new Fixture()) {
            PendingWarning warning = PendingWarning.begin(new Owner());
            f.marker(22, warning); f.frame();
            GamesInProgress.curSlot = 1;
            set(Game.instance, Game.class, "scene", new AlchemyScene());
            f.capture();
            assertTrue(warning.isPending(), "Alchemy remains inside the same active run");
            set(Game.instance, Game.class, "scene", f.scene);
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            f.replaceScene();
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            set(Game.instance, Game.class, "scene", new Scene());
            f.capture();
            assertFalse(warning.isPending(), "A menu scope cannot keep references to the old run's actors");
            set(Game.instance, Game.class, "scene", f.scene);
            assertTrue(f.frame().isEmpty(), "Returning with the same Level object cannot revive menu-cleared knowledge");
        }
    }

    static final class Owner extends Actor { @Override protected boolean act() { return true; } }
    private static final class ShieldSprite extends CharSprite {
        ShieldSprite() { texture(Assets.Interfaces.ICONS); frame(0, 0, 16, 16); }
        void install(ShieldHalo halo) { shield = halo; }
        void removeShield() { processStateRemoval(State.SHIELDED); }
    }
    static final class Grid extends Level {
        Grid() { width = height = 10; length = 100; map = new int[length]; Arrays.fill(map, Terrain.EMPTY);
            heroFOV = new boolean[length]; Arrays.fill(heroFOV, true); visited = new boolean[length]; mapped = new boolean[length]; }
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() { }
        @Override protected void createItems() { }
    }
    static final class Fixture implements AutoCloseable {
        final Game previousGame = Game.instance; final RuntimeObserver previousObserver = Game.observer;
        final Camera previousCamera = Camera.main; final Level previousLevel = Dungeon.level;
        final Hero previousHero = Dungeon.hero; final String previousRun = Dungeon.runId;
        final int previousDepth = Dungeon.depth, previousBranch = Dungeon.branch, previousSlot = GamesInProgress.curSlot;
        final float previousElapsed = Game.elapsed; final Object previousScene, previousActor, previousActorThread, previousInput;
        final Grid level = new Grid(); final GameController controller;
        final Map<Object, SmartTexture> textures; final Map<Object, SmartTexture> previousTextures = new LinkedHashMap<>();
        GameScene scene; VisualCueCollector collector;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            PendingWarning.clear();
            previousScene = get(null, GameScene.class, "scene"); previousActor = get(null, Actor.class, "current");
            previousActorThread = get(null, GameScene.class, "actorThread"); previousInput = Game.inputHandler;
            set(null, Actor.class, "current", null); set(null, GameScene.class, "actorThread", null); Game.inputHandler = null;
            textures = (Map<Object, SmartTexture>) get(null, TextureCache.class, "all");
            install(Assets.Interfaces.ICONS, 512, 512); install(Halo.class, 257, 257);
            Camera.main = new Camera(0, 0, 8, 8, 1); Camera.main.visible = false;
            Dungeon.level = level; Dungeon.runId = "warning-unit-fixture"; Dungeon.depth = 1; Dungeon.branch = 0;
            Dungeon.hero = new Hero(); Dungeon.hero.ready = true; Dungeon.hero.pos = 22; GamesInProgress.curSlot = 0;
            controller = new GameController(null, "menu:warning-fixture", error -> { throw new AssertionError(error); });
            Game.observer = controller; replaceScene();
        }
        void install(Object key, int width, int height) throws Exception {
            SmartTexture texture = FloatingAppearanceTest.allocate(SmartTexture.class); texture.width = width; texture.height = height;
            previousTextures.put(key, textures.put(key, texture));
        }
        void replaceScene() throws Exception {
            scene = new GameScene(); collector = new VisualCueCollector(scene);
            Game.instance = FloatingAppearanceTest.allocate(Game.class); set(Game.instance, Game.class, "scene", scene);
            set(null, GameScene.class, "scene", scene); set(scene, GameScene.class, "visualCueCollector", collector);
        }
        TargetedCell marker(int cell, PendingWarning warning) {
            TargetedCell marker = new TargetedCell(cell, 0xFF0000, warning); scene.add(marker); return marker;
        }
        List<VisualCue> frame() throws Exception {
            collector.beginDraw(); collector.finishDraw();
            return ((GameController.VisualSnapshot) get(controller, GameController.class, "renderedVisual")).cues;
        }
        GameController.State capture() throws Exception {
            if (get(controller, GameController.class, "renderedVisual") == null) frame();
            Method capture = GameController.class.getDeclaredMethod("capture", boolean.class); capture.setAccessible(true);
            return (GameController.State) capture.invoke(controller, false);
        }
        @Override public void close() throws Exception {
            PendingWarning.clear(); Game.instance = previousGame; Game.observer = previousObserver; Camera.main = previousCamera;
            Dungeon.level = previousLevel; Dungeon.hero = previousHero; Dungeon.runId = previousRun;
            Dungeon.depth = previousDepth; Dungeon.branch = previousBranch; GamesInProgress.curSlot = previousSlot; Game.elapsed = previousElapsed;
            set(null, GameScene.class, "scene", previousScene); set(null, Actor.class, "current", previousActor);
            set(null, GameScene.class, "actorThread", previousActorThread); set(null, Game.class, "inputHandler", previousInput);
            for (Map.Entry<Object, SmartTexture> entry : previousTextures.entrySet()) {
                if (entry.getValue() == null) textures.remove(entry.getKey()); else textures.put(entry.getKey(), entry.getValue());
            }
        }
    }
    static Object get(Object target, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
