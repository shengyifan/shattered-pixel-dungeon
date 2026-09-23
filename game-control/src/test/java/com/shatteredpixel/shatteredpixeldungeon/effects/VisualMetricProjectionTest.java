package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualMetric;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VisualMetricProjectionTest {
    @Test void countsOnlyIndependentlyVisibleDrawsAndNeverCountsTwiceOrThroughOverlays()throws Exception{
        Camera previous=Camera.main;Camera.main=new Camera(0,0,160,160,1);
        try{
            GameScene scene=new GameScene();VisualCueCollector collector=new VisualCueCollector(scene);
            TestLevel level=new TestLevel();set(collector,"collecting",true);set(collector,"level",level);
            // Rising particles can extend above their fully visible emission-anchor tile.
            // Those actual upper cells must also be visible to count the complete quads.
            level.heroFOV[2]=level.heroFOV[12]=true;
            Visual one=visual(34,14),two=visual(38,18),hidden=visual(52,34),offscreen=visual(158,34),transparent=visual(34,34);
            scene.add(one);scene.add(two);scene.add(hidden);scene.add(offscreen);scene.add(transparent);transparent.am=0;
            for(Visual particle:Arrays.asList(one,two,offscreen,transparent))collector.particleMetricDrawn(particle,"flames",22);
            collector.particleMetricDrawn(one,"flames",22);
            collector.particleMetricDrawn(hidden,"flames",23);
            VisualCueProjection.Viewport viewport=new VisualCueProjection.Viewport(0,0,160,160,0,0,1);
            assertEquals(List.of(new VisualMetric("flames",22,2)),measure(collector,viewport,List.of()));
            assertEquals(List.of(new VisualMetric("flames",22,1)),measure(collector,viewport,
                    List.of(new VisualCueProjection.Rect(41,19,44,23))));
            assertTrue(measure(collector,null,List.of()).isEmpty(),"A modal/no viewport publishes no hidden count");
            level.heroFOV[22]=false;assertTrue(measure(collector,viewport,List.of()).isEmpty());
            level.heroFOV[22]=true;scene.erase(one);scene.erase(two);
            assertTrue(measure(collector,viewport,List.of()).isEmpty());
        }finally{Camera.main=previous;}
    }

    @Test void densityAndEpisodePresenceExcludeParticlesRisenIntoFogDespiteVisibleFireAnchor()throws Exception{
        Camera previous=Camera.main;Camera.main=new Camera(0,0,160,160,1);
        try{
            GameScene scene=new GameScene();VisualCueCollector collector=new VisualCueCollector(scene);
            TestLevel level=new TestLevel();set(collector,"collecting",true);set(collector,"level",level);
            Visual inside=visual(34,34),risen=visual(34,18),partial=visual(34,30);
            for(Visual particle:Arrays.asList(inside,risen,partial)){
                scene.add(particle);collector.particleMetricDrawn(particle,"sacrificial_flames",22);
            }
            VisualCueProjection.Viewport viewport=new VisualCueProjection.Viewport(0,0,160,160,0,0,1);
            assertEquals(List.of(new VisualMetric("sacrificial_flames",22,1)),measure(collector,viewport,List.of()),
                    "The visible birth cell must not authorize quads fully or partly covered by upper-cell fog");
            Field episodes=VisualCueCollector.class.getDeclaredField("visibleMetricEpisodes");episodes.setAccessible(true);
            assertEquals(Set.of(inside),episodes.get(collector),"Hidden sources must not create visible episode onsets");
            level.heroFOV[12]=true;
            assertEquals(List.of(new VisualMetric("sacrificial_flames",22,3)),measure(collector,viewport,List.of()));
            level.heroFOV[22]=false;
            assertTrue(measure(collector,viewport,List.of()).isEmpty(),"Both semantic anchor and drawn footprint remain required");
            assertTrue(((Set<?>)episodes.get(collector)).isEmpty());
        }finally{Camera.main=previous;}
    }
    private static Visual visual(float x,float y){return new Visual(x,y,4,4){@Override public boolean isVisible(){return visible;}};}
    @SuppressWarnings("unchecked") private static List<VisualMetric> measure(VisualCueCollector collector,VisualCueProjection.Viewport viewport,List<VisualCueProjection.Rect> blockers)throws Exception{
        Method method=VisualCueCollector.class.getDeclaredMethod("visibleMetrics",VisualCueProjection.Viewport.class,List.class);method.setAccessible(true);
        return (List<VisualMetric>)method.invoke(collector,viewport,blockers);
    }
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static final class TestLevel extends Level{
        TestLevel(){width=height=10;length=100;heroFOV=new boolean[100];heroFOV[22]=true;}
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }
}
