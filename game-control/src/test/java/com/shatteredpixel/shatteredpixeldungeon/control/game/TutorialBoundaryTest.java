package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameLog;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventoryPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.StatusPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Toolbar;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Scene;
import com.watabou.noosa.tweeners.Tweener;
import com.watabou.utils.Signal;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual endIntro tween with in-memory panes, preferences and level; no native renderer or saves. */
class TutorialBoundaryTest {
    private final Map<Field,Object> saved = new LinkedHashMap<>();
    private GameScene scene;
    private GameController controller;
    private Status status;
    private Tools toolbar;
    private Inventory inventory;

    @BeforeAll static void assets() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @BeforeEach void setup() throws Exception {
        remember(Game.class, "instance", Game.instance);
        remember(Game.class, "sceneClass", null);
        remember(Game.class, "elapsed", 0f);
        remember(Game.class, "inputHandler", null);
        remember(Game.class, "width", 0);
        remember(Game.class, "height", 0);
        remember(GameScene.class, "scene", null);
        remember(GameScene.class, "cellSelector", null);
        remember(Actor.class, "yielded", true);
        remember(Dungeon.class, "hero", new Hero());
        remember(Dungeon.class, "level", new EmptyLevel());
        remember(Dungeon.class, "runId", "tutorial-boundary-test");
        remember(Dungeon.class, "depth", 1);
        remember(GameLog.class, "entries", new ArrayList<>());
        remember(GameLog.class, "textsToAdd", new ArrayList<>());
        remember(GLog.class, "update", new Signal<String>());
        scene = new GameScene();
        field(GameScene.class, "scene").set(null, scene);
        new TestGame(scene);
        Dungeon.hero.ready = true;
        status = allocate(Status.class);
        toolbar = allocate(Tools.class);
        inventory = allocate(Inventory.class);
        field(GameScene.class, "status").set(scene, status);
        field(GameScene.class, "toolbar").set(scene, toolbar);
        field(GameScene.class, "inventory").set(scene, inventory);
        controller = new GameController(null, "menu:test", error -> { throw new AssertionError(error); });
    }

    @AfterEach void restore() throws Exception {
        for (Map.Entry<Field,Object> entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    @Test void tutorialCompletionWaitsForBothUiFadeStagesAndALaterDraw() throws Exception {
        assertTrue(stable());
        GameScene.endIntro();
        Tweener tween = introTween();
        assertEquals(2f, tween.interval);
        assertTrue(tween.hasPendingCallback());
        assertFalse(stable(), "Even the initial frame still owns future interaction changes");
        assertFalse(stable(), "Readiness probes must not advance the tutorial");
        assertEquals(0f, tween.elapsed);

        advance(tween, 0.5f);
        assertTrue(status.active && status.visible);
        assertEquals(0.5f, status.opacity);
        assertFalse(toolbar.active || toolbar.visible || inventory.active || inventory.visible);
        assertFalse(stable());

        advance(tween, 0.5f);
        assertEquals(1f, status.opacity);
        assertFalse(toolbar.active || toolbar.visible || inventory.active || inventory.visible);
        assertFalse(stable(), "The exact midpoint precedes toolbar activation");

        advance(tween, 0.5f);
        assertTrue(toolbar.active && toolbar.visible && inventory.active && inventory.visible);
        assertEquals(0.5f, toolbar.opacity);
        assertEquals(0.5f, inventory.opacity);
        assertFalse(stable(), "The finite reveal must finish before returning the action boundary");

        advance(tween, 0.5f);
        assertEquals(1f, toolbar.opacity);
        assertEquals(1f, inventory.opacity);
        assertFalse(tween.exists);
        assertFalse(tween.hasPendingCallback());
        assertFalse(GameController.hasPendingEffects(scene));
        assertTrue(stable());

        assertFalse(rendered());
        controller.onVisualCues(Dungeon.runId, Dungeon.level, Dungeon.depth, List.of());
        assertFalse(rendered(), "Finishing the tween must still pass the normal draw acknowledgement");
        controller.onVisualCues(Dungeon.runId, Dungeon.level, Dungeon.depth, List.of());
        assertTrue(rendered());
    }

    @Test void tutorialWithoutAnInventoryPaneHasTheSameFiniteBoundary() throws Exception {
        field(GameScene.class, "inventory").set(scene, null);
        GameScene.endIntro();
        Tweener tween = introTween();
        advance(tween, 0.5f);
        assertFalse(toolbar.active || toolbar.visible);
        assertFalse(stable());
        advance(tween, 1f);
        assertTrue(toolbar.active && toolbar.visible);
        assertEquals(0.5f, toolbar.opacity);
        assertFalse(stable());
        advance(tween, 0.5f);
        assertTrue(stable());
        assertFalse(tween.hasPendingCallback());
    }

    @Test void decorativeTweensStayNonblockingAndInactiveOrRemovedTutorialsDoNotHang() throws Exception {
        Tweener decoration = new Tweener(scene, 20f) {
            @Override protected void updateValues(float progress) {}
        };
        scene.add(decoration);
        assertTrue(stable(), "Ordinary visual fades are not interaction continuations");
        GameScene.endIntro();
        Tweener tutorial = introTween();
        assertFalse(stable());
        tutorial.active = false;
        assertFalse(tutorial.hasPendingCallback());
        assertTrue(stable());
        tutorial.active = true;
        assertFalse(stable());
        tutorial.killAndErase();
        assertTrue(stable());
        assertTrue(decoration.exists);
    }

    private Tweener introTween() {
        return scene.childrenSnapshot().stream().filter(node -> node instanceof Tweener)
                .map(node -> (Tweener) node).filter(node -> node.interval == 2f).findFirst().orElseThrow();
    }
    private static void advance(Tweener tween, float elapsed) { Game.elapsed = elapsed; tween.update(); }
    private boolean stable() throws Exception { return invoke("stable"); }
    private boolean rendered() throws Exception { return invoke("renderedBoundaryReady"); }
    private boolean invoke(String name) throws Exception {
        Method method = GameController.class.getDeclaredMethod(name); method.setAccessible(true);
        return (Boolean) method.invoke(controller);
    }
    private void remember(Class<?> type, String name, Object value) throws Exception {
        Field field = field(type, name); saved.put(field, field.get(null)); field.set(null, value);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static <T extends Gizmo> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("jdk.internal.misc.Unsafe");
        Field singleton = field(unsafe, "theUnsafe");
        T instance = type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
        instance.exists = instance.alive = true;
        return instance;
    }
    private static final class Status extends StatusPane {
        private Status() { super(false); }
        float opacity;
        @Override public void alpha(float value) { opacity = value; }
    }
    private static final class Tools extends Toolbar {
        float opacity;
        @Override public void alpha(float value) { opacity = value; }
    }
    private static final class Inventory extends InventoryPane {
        float opacity;
        @Override public void alpha(float value) { opacity = value; }
    }
    private static final class EmptyLevel extends Level {
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() {}
        @Override protected void createItems() {}
    }
    private static final class TestGame extends Game {
        TestGame(Scene current) { super(Scene.class, Game.platform); scene = current; requestedReset = false; }
    }
}
