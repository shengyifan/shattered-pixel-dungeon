package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CrystalGuardianSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.EyeSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GhoulSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.NecromancerSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.SpectralNecromancerSprite;
import com.watabou.noosa.Camera;
import com.watabou.noosa.MovieClip;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CombatVisualCueTest {
    @Test void cueIdentityPreservesDifferentEndpointsAndObservedDirections() {
        assertEquals(new VisualCue("charge", 1), new VisualCue("charge", 1, null, null));
        assertEquals(4, new HashSet<>(Arrays.asList(new VisualCue("arc", 2),
                new VisualCue("arc", 2, 1, null), new VisualCue("arc", 2, 3, null),
                new VisualCue("arc", 2, 1, "east"))).size());
    }

    @Test void particleDirectionsUseOnlyFiniteVisibleMotionNotAttractionTargets() {
        assertEquals("east", ParticleMotionCue.direction(3, 0));
        assertEquals("southeast", ParticleMotionCue.direction(3, 3));
        assertEquals("south", ParticleMotionCue.direction(0, 3));
        assertEquals("southwest", ParticleMotionCue.direction(-3, 3));
        assertEquals("west", ParticleMotionCue.direction(-3, 0));
        assertEquals("northwest", ParticleMotionCue.direction(-3, -3));
        assertEquals("north", ParticleMotionCue.direction(0, -3));
        assertEquals("northeast", ParticleMotionCue.direction(3, -3));
        assertNull(ParticleMotionCue.direction(0, 0));
        assertNull(ParticleMotionCue.direction(Float.NaN, 1));
        assertNull(ParticleMotionCue.direction(1, Float.POSITIVE_INFINITY));
    }

    @Test void missileNamesDescribeRenderingBranchesAndPreserveConeAndSpeckDifferences() {
        assertEquals("magic_fire", MagicMissile.appearance(MagicMissile.FIRE));
        assertEquals("magic_fire_cone", MagicMissile.appearance(MagicMissile.FIRE_CONE));
        assertEquals("magic_red", MagicMissile.appearance(MagicMissile.SHAMAN_RED));
        assertEquals("magic_speck_7", MagicMissile.appearance(MagicMissile.SPECK + 7));
        assertEquals("magic_white", MagicMissile.appearance(-1), "Must match the original switch default");
    }

    @Test void chargingAndDownedPosturesAreSelectedWithoutAnyBackingActor() throws Exception {
        posture(EyeSprite.class, EyeSprite.class, "charging", "evil_eye_charging");
        posture(GhoulSprite.class, GhoulSprite.class, "crumple", "downed_ghoul");
        posture(CrystalGuardianSprite.Blue.class, CrystalGuardianSprite.class, "crumple", "downed_crystal_guardian");
        posture(NecromancerSprite.class, NecromancerSprite.class, "charging", "necromancer_charging");
        posture(SpectralNecromancerSprite.class, SpectralNecromancerSprite.class, "charging", "spectral_necromancer_charging");
    }

    private static void posture(Class<? extends CharSprite> concrete, Class<?> owner, String name, String expected) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        CharSprite sprite = (CharSprite)unsafeClass.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), concrete);
        MovieClip.Animation posture = new MovieClip.Animation(1, true);
        Field animation = owner.getDeclaredField(name); animation.setAccessible(true); animation.set(sprite, posture);
        Field current = MovieClip.class.getDeclaredField("curAnim"); current.setAccessible(true);
        Method cue = CharSprite.class.getDeclaredMethod("renderedStateCue"); cue.setAccessible(true);
        assertNull(sprite.ch, "No actor model is constructed or inspected by this projection");
        assertNull(cue.invoke(sprite));
        current.set(sprite, posture); assertEquals(expected, cue.invoke(sprite));
        current.set(sprite, new MovieClip.Animation(1, true)); assertNull(cue.invoke(sprite));
    }

    @Test void onlyAttachedNontransparentDrawnSourcesEnterTheCollector() throws Exception {
        Camera previous = Camera.main;
        Camera.main = new Camera(0, 0, 160, 160, 1);
        try {
            GameScene scene = new GameScene();
            VisualCueCollector collector = new VisualCueCollector(scene);
            set(collector, "collecting", true);
            Visual source = new Visual(32, 32, 4, 4) { @Override public boolean isVisible() { return visible; } };
            VisualCue cue = new VisualCue("test_draw", 22, null, "east");
            collector.cellVisualDrawn(source, cue);
            assertTrue(((Map<?,?>)get(collector, "offered")).isEmpty());
            scene.add(source); source.am = 0;
            collector.cellVisualDrawn(source, cue);
            assertTrue(((Map<?,?>)get(collector, "offered")).isEmpty());
            source.am = 1;
            collector.cellVisualDrawn(source, cue);
            assertEquals(Collections.singletonList(cue), new java.util.ArrayList<>(((Map<?,?>)get(collector, "offered")).values()));
            assertNull(source.camera, "An observation must not fill camera caches");
        } finally { Camera.main = previous; }
    }

    @Test void oneFullyVisibleContributorSufficesButEverySegmentOfAnArcMustFit() throws Exception {
        Method permits = VisualCueCollector.class.getDeclaredMethod("permitsCueBounds", List.class,
                VisualCueProjection.Viewport.class, List.class); permits.setAccessible(true);
        VisualCueProjection.Viewport viewport = new VisualCueProjection.Viewport(0, 0, 100, 100, 0, 0, 1);
        VisualCueProjection.Rect visible = new VisualCueProjection.Rect(20,20,30,30);
        VisualCueProjection.Rect outside = new VisualCueProjection.Rect(95,20,105,30);
        assertEquals(false, permits.invoke(null, Collections.singletonList(Arrays.asList(visible, outside)), viewport, Collections.emptyList()));
        assertEquals(true, permits.invoke(null, Arrays.asList(Collections.singletonList(outside), Collections.singletonList(visible)), viewport, Collections.emptyList()));
        assertEquals(false, permits.invoke(null, Collections.singletonList(Collections.singletonList(visible)), viewport,
                Collections.singletonList(new VisualCueProjection.Rect(29,29,40,40))));
    }

    @Test void motionRequiresConsecutiveEligibleDrawsAndCannotCrossPoolLifetimes() throws Exception {
        Camera previous = Camera.main; Camera.main = new Camera(0,0,160,160,1);
        try {
            GameScene scene=new GameScene(); VisualCueCollector collector=new VisualCueCollector(scene);
            set(collector,"collecting",true);set(collector,"level",new MotionLevel());
            Visual source=new Visual(32,32,4,4){@Override public boolean isVisible(){return visible;}};scene.add(source);
            VisualCueProjection.Viewport view=new VisualCueProjection.Viewport(0,0,160,160,0,0,1);
            assertNull(motionFrame(collector,source,view,Collections.emptyList()).direction);
            source.x+=2;assertEquals("east",motionFrame(collector,source,view,Collections.emptyList()).direction);
            source.x+=2;assertNull(motionFrame(collector,source,view,Collections.singletonList(new VisualCueProjection.Rect(30,30,60,60))));
            source.x+=2;assertNull(motionFrame(collector,source,view,Collections.emptyList()).direction,
                    "A hidden prior position cannot disclose motion after reappearing");
            source.revive();source.x+=2;assertNull(motionFrame(collector,source,view,Collections.emptyList()).direction,
                    "Pool reuse is a new visual, not motion from the previous particle");
            Camera.main.scroll.x=16;
            view=new VisualCueProjection.Viewport(16,0,160,160,0,0,1);
            assertNull(motionFrame(collector,source,view,Collections.emptyList()).direction,
                    "Camera motion alone is not particle motion");
        } finally { Camera.main=previous; }
    }

    @Test void visibleBirthCellCannotAuthorizeParticlesInsideOrPartlyBehindFog() throws Exception {
        Camera previous=Camera.main;Camera.main=new Camera(0,0,160,160,1);
        try {
            GameScene scene=new GameScene();VisualCueCollector collector=new VisualCueCollector(scene);
            MotionLevel level=new MotionLevel();level.heroFOV[23]=false;
            set(collector,"collecting",true);set(collector,"level",level);
            Visual particle=new Visual(36,32,4,4){@Override public boolean isVisible(){return visible;}};scene.add(particle);
            VisualCueProjection.Viewport view=new VisualCueProjection.Viewport(0,0,160,160,0,0,1);
            assertNull(motionFrame(collector,particle,view,Collections.emptyList()).direction);
            particle.x=40;assertEquals("east",motionFrame(collector,particle,view,Collections.emptyList()).direction);
            particle.x=49;
            assertNull(motionFrame(collector,particle,view,Collections.emptyList()),
                    "The visible anchor remains cell22 while the actual particle is wholly behind fog in cell23");
            assertTrue(((Map<?,?>)get(collector,"previousMotion")).isEmpty(),"Hidden positions cannot seed a future direction");
            particle.x=44;
            assertNull(motionFrame(collector,particle,view,Collections.emptyList()).direction,
                    "Reappearing must start a new eligible observation sequence");
            particle.x=46;
            assertNull(motionFrame(collector,particle,view,Collections.emptyList()),
                    "A footprint partly crossing into the hidden cell is conservatively omitted");
        } finally {Camera.main=previous;}
    }

    @SuppressWarnings("unchecked")
    private static VisualCue motionFrame(VisualCueCollector collector,Visual source,VisualCueProjection.Viewport view,
                                         List<VisualCueProjection.Rect> blockers) throws Exception {
        ((Map<?,?>)get(collector,"offered")).clear();((Map<?,?>)get(collector,"cueBounds")).clear();
        ((List<?>)get(collector,"movingObservations")).clear();
        collector.movingVisualDrawn(source,new VisualCue("motion",22));
        Method finish=VisualCueCollector.class.getDeclaredMethod("finishMovingDraws",VisualCueProjection.Viewport.class,List.class);
        finish.setAccessible(true);finish.invoke(collector,view,blockers);
        Map<String,VisualCue> offered=(Map<String,VisualCue>)get(collector,"offered");
        return offered.isEmpty()?null:offered.values().iterator().next();
    }

    private static final class MotionLevel extends com.shatteredpixel.shatteredpixeldungeon.levels.Level {
        MotionLevel(){width=height=10;length=100;heroFOV=new boolean[length];Arrays.fill(heroFOV,true);}
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
        @Override public String tilesTex(){return "";}
        @Override public String waterTex(){return "";}
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
