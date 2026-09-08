package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.Group;
import com.watabou.utils.Callback;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class GameplayContinuationTest {
    private static final class Sprite extends CharSprite {
        void queue(Callback callback) { animCallback = callback; }
        void finishAnimation() { onComplete((Animation) null); }
    }

    @Test public void anIdleLookingSpriteStillBlocksCompletionUntilItsGameplayCallbackRuns() {
        Group scene = new Group();
        Sprite sprite = new Sprite(); scene.add(sprite);
        AtomicInteger effects = new AtomicInteger();
        sprite.queue(effects::incrementAndGet);
        assertFalse(sprite.isMoving);
        assertTrue(GameController.hasPendingEffects(scene));
        assertTrue(GameController.hasPendingEffects(scene));
        assertEquals("Reading readiness must not invoke the callback", 0, effects.get());
        sprite.finishAnimation();
        assertEquals(1, effects.get());
        assertFalse(GameController.hasPendingEffects(scene));
    }

    @Test public void aContinuationThatSchedulesAnotherContinuationIsStillPending() {
        Group scene = new Group();
        Sprite sprite = new Sprite(); scene.add(sprite);
        AtomicInteger effects = new AtomicInteger();
        sprite.queue(() -> { effects.incrementAndGet(); sprite.queue(effects::incrementAndGet); });
        sprite.finishAnimation();
        assertTrue(GameController.hasPendingEffects(scene));
        assertEquals(1, effects.get());
        sprite.finishAnimation();
        assertFalse(GameController.hasPendingEffects(scene));
        assertEquals(2, effects.get());
    }
}
