package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.noosa.VisualMetric;
import com.watabou.noosa.VisualCue;
import org.junit.jupiter.api.*;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

class VisualMetricSnapshotTest {
    private Level previousLevel,level;private String previousRun;private int previousDepth;
    private GameController game;
    private final Object episode=new Object();
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup(){
        previousLevel=Dungeon.level;previousRun=Dungeon.runId;previousDepth=Dungeon.depth;
        level=new TestLevel();Dungeon.level=level;Dungeon.runId="metric-run";Dungeon.depth=1;
        game=new GameController(null,"menu:metric",error->{throw new AssertionError(error);});
    }
    @AfterEach void restore(){Dungeon.level=previousLevel;Dungeon.runId=previousRun;Dungeon.depth=previousDepth;}

    @Test void everyCurrentDrawIsExactWhileHistorySamplesCountsAndImmediatelyRecordsDisappearance()throws Exception{
        draw(5,0);GameController.VisualMetricSnapshot first=game.pollVisualMetrics();assertNotNull(first);
        assertEquals("sampled_display_snapshot_v1",first.data().get("format"));assertEquals(250,first.data().get("sample_period_ms"));
        assertEquals(Set.of("kind","cell","rendered_particles"),first.metricData().get(0).keySet());
        assertNotNull(game.pollVisual());
        draw(8,50);assertEquals(8,count());assertNull(game.pollVisualMetrics());
        draw(9,249);assertEquals(9,count());assertNull(game.pollVisualMetrics());
        draw(10,250);assertEquals(10,count());assertEquals(10,game.pollVisualMetrics().metrics.get(0).renderedParticles);
        assertNull(game.pollVisual(),"Quantitative changes must not produce discrete game.visual events");
        draw(0,251);assertFalse(state().containsKey("metrics"));
        assertTrue(game.pollVisualMetrics().metrics.isEmpty(),"Disappearance must not wait for the sampling period");
        draw(2,252);assertEquals(2,game.pollVisualMetrics().metrics.get(0).renderedParticles);
        assertEquals(5,first.metrics.get(0).renderedParticles,"Historical snapshots remain immutable");
    }

    @Test void histogramMotionIsSampledButNewVisibleEpisodesAreImmediateAndOldMapsAreNeverReused()throws Exception{
        draw(2,0);String first=game.pollVisualMetrics().mapContext;
        game.onVisualCues("metric-run",level,1,List.of());
        assertFalse(state().containsKey("metrics"),"A new draw cannot reuse the previous draw's metrics");
        game.recordVisualMetrics("metric-run",level,1,List.of(new VisualMetric("flames",23,2)),Set.of(episode),1_000_000L,"cell-change");
        assertNull(game.pollVisualMetrics(),"Moving histogram bins cannot bypass the sampling interval");
        game.recordVisualMetrics("metric-run",level,1,List.of(new VisualMetric("flames",23,2)),Set.of(new Object()),2_000_000L,"new-episode");
        assertEquals(23,game.pollVisualMetrics().metrics.get(0).cell);
        Level replacement=new TestLevel();Dungeon.level=replacement;
        assertEquals("not_rendered",state().get("status"));
        game.onVisualCues("metric-run",replacement,1,List.of());assertFalse(state().containsKey("metrics"));
        game.recordVisualMetrics("metric-run",level,1,List.of(new VisualMetric("flames",22,8)),Set.of(episode),3_000_000L,"wrong-map");
        assertFalse(state().containsKey("metrics"));
        game.recordVisualMetrics("metric-run",replacement,1,List.of(new VisualMetric("flames",22,3)),Set.of(new Object()),4_000_000L,"new-map");
        assertNotEquals(first,game.pollVisualMetrics().mapContext);assertEquals(3,count());
    }

    @Test void currentSpriteTintIsExactButOnlyCosmeticCyclePhaseIsOmittedFromDiscreteEventChanges()throws Exception{
        VisualCue first=new VisualCue("sprite_state_appearance",22,null,null,null,null,
                map("tint_style","golden_glow","tint",map("multiply",List.of(.6f,.6f,.6f))));
        VisualCue second=new VisualCue("sprite_state_appearance",22,null,null,null,null,
                map("tint_style","golden_glow","tint",map("multiply",List.of(.4f,.4f,.4f))));
        game.onVisualCues("metric-run",level,1,List.of(first));assertNotNull(game.pollVisual());
        game.onVisualCues("metric-run",level,1,List.of(second));assertNull(game.pollVisual());
        Map<?,?> rendered=(Map<?,?>)((List<?>)state().get("cues")).get(0);
        assertEquals(second.appearance,rendered.get("appearance"));
        VisualCue paused=new VisualCue("sprite_state_appearance",22,null,null,null,.5f,
                map("tint_style","golden_glow","paused",true,"tint",map("multiply",List.of(.4f,.4f,.4f))));
        game.onVisualCues("metric-run",level,1,List.of(paused));assertNotNull(game.pollVisual(),"Pause/opacity are meaningful and must not be deduplicated");
    }

    private void draw(int count,long millis){
        game.onVisualCues("metric-run",level,1,List.of());
        game.recordVisualMetrics("metric-run",level,1,count==0?List.of():List.of(new VisualMetric("flames",22,count)),
                count==0?Set.of():Set.of(episode),millis*1_000_000L,"draw-"+millis);
    }
    private int count()throws Exception{return (Integer)((Map<?,?>)((List<?>)state().get("metrics")).get(0)).get("rendered_particles");}
    @SuppressWarnings("unchecked") private Map<String,Object> state()throws Exception{Method method=GameController.class.getDeclaredMethod("visualState");method.setAccessible(true);return (Map<String,Object>)method.invoke(game);}
    private static final class TestLevel extends Level{
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }
}
