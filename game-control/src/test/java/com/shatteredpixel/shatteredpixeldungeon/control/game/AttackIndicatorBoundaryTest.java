package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.ui.AttackIndicator;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.NinePatch;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the original indicator update without a renderer, Actor turn, or save access. */
class AttackIndicatorBoundaryTest {
    private Hero previousHero;
    private Object previousIndicator;
    private float previousDelay, previousElapsed;
    private TestIndicator indicator;
    private Scene scene;

    @org.junit.jupiter.api.BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @BeforeEach void setup() throws Exception {
        previousHero = Dungeon.hero;
        previousIndicator = field(AttackIndicator.class, "instance").get(null);
        previousDelay = field(AttackIndicator.class, "delay").getFloat(null);
        previousElapsed = Game.elapsed;
        field(AttackIndicator.class, "delay").setFloat(null, 0f);
        Dungeon.hero = new Hero();
        Dungeon.hero.ready = true;
        scene = new Scene();
        indicator = new TestIndicator();
        scene.add(indicator);
    }

    @AfterEach void restore() throws Exception {
        Dungeon.hero = previousHero;
        Game.elapsed = previousElapsed;
        field(AttackIndicator.class, "instance").set(null, previousIndicator);
        field(AttackIndicator.class, "delay").setFloat(null, previousDelay);
    }

    @Test void disappearingAttackControlBlocksUntilItsOriginalDelayFinishes() throws Exception {
        indicator.background(true);
        advance(0f);
        assertEquals(0.75f, delay());
        assertFalse(GameController.hasPendingEffects(scene), "An available attack must not block all commands");
        indicator.background(false);
        assertTrue(indicator.active, "The native indicator stays active during its retirement delay");
        assertTrue(GameController.hasPendingEffects(scene));
        assertTrue(GameController.hasPendingEffects(scene));
        assertEquals(0.75f, delay(), "Readiness checks must not advance the native animation");
        advance(0.5f);
        assertTrue(GameController.hasPendingEffects(scene));
        assertEquals(0.25f, delay());
        advance(0.25f);
        assertFalse(indicator.active);
        assertFalse(indicator.hasPendingCallback());
        assertFalse(GameController.hasPendingEffects(scene));
    }

    @Test void aRemainingTargetKeepsTheIndicatorAvailableWithoutBlocking() throws Exception {
        indicator.background(true);
        advance(0f);
        indicator.background(false);
        advance(0.5f);
        assertTrue(GameController.hasPendingEffects(scene));
        indicator.background(true);
        advance(0.5f);
        assertTrue(indicator.active);
        assertEquals(0.75f, delay());
        assertFalse(GameController.hasPendingEffects(scene));
    }

    @Test void zeroDelayNeedsItsFinalUpdateButInactiveOrRemovedIndicatorsDoNotHang() {
        assertTrue(indicator.active);
        assertTrue(GameController.hasPendingEffects(scene), "The initial hidden indicator still has to retire");
        advance(0f);
        assertFalse(indicator.active);
        assertFalse(GameController.hasPendingEffects(scene));
        indicator.active = true;
        assertTrue(GameController.hasPendingEffects(scene));
        indicator.active = false;
        assertFalse(indicator.hasPendingCallback());
        indicator.active = true;
        indicator.killAndErase();
        assertFalse(indicator.hasPendingCallback());
        assertFalse(GameController.hasPendingEffects(scene));
    }

    private void advance(float elapsed) { Game.elapsed = elapsed; indicator.update(); }
    private static float delay() throws Exception { return field(AttackIndicator.class, "delay").getFloat(null); }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static <T extends Gizmo> T unrendered(Class<T> type) {
        try {
            Class<?> unsafe = Class.forName("jdk.internal.misc.Unsafe");
            T value = type.cast(unsafe.getMethod("allocateInstance", Class.class)
                    .invoke(field(unsafe, "theUnsafe").get(null), type));
            value.exists = value.alive = value.active = value.visible = true;
            return value;
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static final class TestIndicator extends AttackIndicator {
        @Override protected void createChildren() {
            bg = unrendered(NinePatch.class);
            hotArea = unrendered(PointerArea.class);
        }
        @Override protected void layout() { }
        void background(boolean visible) { bg.visible = visible; }
    }
}
