package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.particles.Emitter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CellParticleContributorTest {
    @Test void fallingWarningNeedsAnUncoveredCompleteContributorAndHiddenDrawDoesNotLatchReadiness() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertFalse(fixture.frame(), "An eligible new emitter waits for its first actual particle");
            // DelayedRockFall raises its emission box above an otherwise visible anchor tile.
            // Rotation makes native Visual.isVisible permissive even when the quad is above the viewport.
            Visual outside = fixture.particle(35, 29); outside.angle = 20;
            assertTrue(outside.isVisible());
            assertTrue(fixture.frame(), "An entirely clipped source must not hold input indefinitely");
            assertTrue(fixture.cues().isEmpty());
            outside.kill();
            assertFalse(fixture.frame(), "A clipped particle was not recorded as the first visible draw");
            Visual inside = fixture.particle(35, 36);
            assertTrue(fixture.frame());
            assertEquals(Collections.singletonList(new VisualCue("falling_rock_warning", 22)), fixture.cues());
            inside.kill();
            assertTrue(fixture.frame(), "Natural particle flicker cannot re-arm the initial wait");
        }
    }

    @Test void laterVisibleContributorWinsAfterClippedCoveredOrFoggedCandidates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Visual outside = fixture.particle(35, 29); outside.angle = 20;
            Visual covered = fixture.particle(35, 51);
            List<VisualCueProjection.Rect> blockers = Collections.singletonList(new VisualCueProjection.Rect(34, 18, 38, 22));
            assertTrue(fixture.frame(blockers));
            assertTrue(fixture.cues().isEmpty(), "Cover outside the anchor tile still hides the particle");
            fixture.level.heroFOV[32] = false;
            assertTrue(fixture.frame());
            assertTrue(fixture.cues().isEmpty(), "An anchor in FOV cannot expose a contributor in a fogged neighboring tile");
            Visual inside = fixture.particle(36, 36);
            assertTrue(fixture.frame(blockers));
            assertEquals(1, fixture.cues().size(), "Do not stop at the first hidden contributor");
            inside.visible = false;
            assertTrue(fixture.frame(blockers));
            assertTrue(fixture.cues().isEmpty());
            covered.kill(); outside.kill();
        }
    }

    @Test void hiddenStoppedInactiveAndFrozenSourcesNeverWaitForUnavailablePixels() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.level.heroFOV[22] = false;
            assertTrue(fixture.frame());
            fixture.level.heroFOV[22] = true;
            fixture.emitter.on = false; assertTrue(fixture.frame());
            fixture.emitter.on = true; fixture.emitter.active = false; assertTrue(fixture.frame());
            fixture.emitter.active = true; fixture.emitter.frozen = true; assertTrue(fixture.frame());
            assertTrue(fixture.cues().isEmpty());
        }
    }

    private static final class ObservedEmitter extends Emitter {
        boolean frozen;
        @Override protected boolean isFrozen() { return frozen; }
    }

    private static final class Fixture implements AutoCloseable {
        final Camera previous = Camera.main;
        final GameScene scene = new GameScene();
        final VisualCueCollector collector = new VisualCueCollector(scene);
        final TestLevel level = new TestLevel();
        final ObservedEmitter emitter = new ObservedEmitter();
        final CellParticleCue observation;
        final VisualCueProjection.Viewport viewport = new VisualCueProjection.Viewport(0,32,160,128,0,0,1);
        Fixture() throws Exception {
            Camera.main = new Camera(0,0,160,128,1); Camera.main.scroll.set(0,32);
            Emitter.Factory factory = new Emitter.Factory() {
                @Override public void emit(Emitter emitter, int index, float x, float y) { fail("Observation must not emit particles"); }
            };
            emitter.startDelayed(factory, .1f, 0, .1f); scene.add(emitter);
            observation = new CellParticleCue("falling_rock_warning",22,factory,Visual.class);
            set(collector,"collecting",true); set(collector,"level",level);
        }
        Visual particle(float x, float y) {
            Visual particle = new Visual(x,y,1,1); emitter.add(particle); return particle;
        }
        boolean frame() throws Exception { return frame(Collections.emptyList()); }
        boolean frame(List<VisualCueProjection.Rect> blockers) throws Exception {
            ((Map<?,?>)get(collector,"offered")).clear(); ((Map<?,?>)get(collector,"cueBounds")).clear();
            ((List<?>)get(collector,"particleObservations")).clear();
            collector.particleEmitterDrawn(emitter,observation);
            Method finish = VisualCueCollector.class.getDeclaredMethod("finishParticleDraws",VisualCueProjection.Viewport.class,List.class);
            finish.setAccessible(true); return (Boolean)finish.invoke(collector,viewport,blockers);
        }
        @SuppressWarnings("unchecked") List<VisualCue> cues() throws Exception {
            return new ArrayList<>(((Map<String,VisualCue>)get(collector,"offered")).values());
        }
        @Override public void close() { Camera.main = previous; }
    }

    private static final class TestLevel extends Level {
        TestLevel() { width=height=10;length=100;heroFOV=new boolean[length];Arrays.fill(heroFOV,true); }
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
        @Override public String tilesTex(){return "";}
        @Override public String waterTex(){return "";}
    }
    private static Object get(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
