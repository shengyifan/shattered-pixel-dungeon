package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GooSprite;
import com.watabou.noosa.particles.Emitter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmitterDrawObserverTest {
    @Test void observerRunsAfterActualVisibleChildrenAndIsClearedOnReuse() {
        Emitter emitter = new Emitter();
        List<String> order = new ArrayList<>();
        emitter.add(new Gizmo() { @Override public void draw() { order.add("drawn-child"); } });
        Gizmo hidden = new Gizmo() { @Override public void draw() { fail("Hidden child must not draw"); } };
        hidden.visible = false; emitter.add(hidden);
        emitter.observeDraw(e -> order.add("after-draw"));
        emitter.draw();
        assertEquals(Arrays.asList("drawn-child", "after-draw"), order);
        order.clear(); emitter.revive(); emitter.draw();
        assertEquals(Arrays.asList("drawn-child"), order);
    }

    @Test void emitterFactoryAndOnStateAreInspectedWithoutStartingParticles() {
        Emitter emitter = new Emitter();
        Emitter.Factory factory = new Emitter.Factory() {
            @Override public void emit(Emitter emitter, int index, float x, float y) { fail("Read must not emit particles"); }
        };
        assertFalse(emitter.isEmitting(factory));
        emitter.startDelayed(factory, 1, 0, 1);
        for (int i = 0; i < 10; i++) assertTrue(emitter.isEmitting(factory));
        emitter.on = false;
        assertFalse(emitter.isEmitting(factory));
        assertEquals(0, emitter.countLiving());
    }

    @Test void firstDrawWaitIsLimitedToEligibleVisibleSourcesAndResetOnBinding() {
        GooSprite.GooDrawObserver observation = new GooSprite.GooDrawObserver(22);
        assertTrue(observation.recordVisibleFrame(false, false), "Hidden source cannot hold a response");
        assertTrue(observation.recordVisibleFrame(false, true), "A particle behind an occluder is not a visible first draw");
        assertFalse(observation.recordVisibleFrame(true, false));
        assertTrue(observation.recordVisibleFrame(true, true));
        assertTrue(observation.recordVisibleFrame(true, false), "Later particle flicker must not restart waiting");
        Emitter emitter = new Emitter();
        emitter.observeDraw(observation);
        assertFalse(observation.recordVisibleFrame(true, false), "A new observed source resets the latch");
        assertTrue(observation.recordVisibleFrame(true, true));
        emitter.revive(); emitter.observeDraw(observation);
        assertFalse(observation.recordVisibleFrame(true, false), "Pool reuse cannot inherit an old visible draw");
    }

    @Test void frozenOrInactiveSourceWithoutParticlesCannotHoldAResponse() {
        float previousTime = Game.timeTotal;
        boolean previousFreeze = Emitter.freezeEmitters;
        try {
            Emitter emitter = new Emitter();
            Group parent = new Group(); parent.add(emitter);
            GooSprite.GooDrawObserver observation = new GooSprite.GooDrawObserver(22);
            emitter.observeDraw(observation);
            Game.timeTotal = 2;
            Emitter.freezeEmitters = true;
            for (int i = 0; i < 10; i++) {
                assertFalse(emitter.canProgress());
                assertTrue(observation.recordVisibleFrame(true, false) || !emitter.canProgress());
            }
            assertTrue(Emitter.freezeEmitters, "The observation must not thaw the game");
            assertEquals(2, Game.timeTotal);
            assertEquals(0, emitter.countLiving(), "The observation must not create a first particle");
            Game.timeTotal = 0.5f;
            assertTrue(emitter.canProgress(), "Scene startup retains its original one-second update grace");
            assertFalse(observation.recordVisibleFrame(true, false) || !emitter.canProgress());
            Game.timeTotal = 2;
            Emitter.freezeEmitters = false;
            emitter.active = false;
            assertFalse(emitter.canProgress());
            assertTrue(observation.recordVisibleFrame(true, false) || !emitter.canProgress());
            emitter.active = true; parent.active = false;
            assertFalse(emitter.canProgress(), "An inactive containing group does not update its child");
            assertTrue(observation.recordVisibleFrame(true, false) || !emitter.canProgress());
            parent.active = true;
            assertTrue(emitter.canProgress());
            assertFalse(observation.recordVisibleFrame(true, false) || !emitter.canProgress(), "Thawing does not invent a visible first draw");
            assertTrue(observation.recordVisibleFrame(true, true));
        } finally {
            Game.timeTotal = previousTime;
            Emitter.freezeEmitters = previousFreeze;
        }
    }

    @Test void progressReadRespectsNativeEmitterFreezeOverrides() {
        Emitter emitter = new Emitter() { @Override protected boolean isFrozen() { return true; } };
        assertFalse(emitter.canProgress());
    }
}
