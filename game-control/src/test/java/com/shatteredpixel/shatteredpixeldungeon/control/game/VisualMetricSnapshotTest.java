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

/** CLI 8 retains reviewed gameplay measurements, not generic particle-history samples. */
class VisualMetricSnapshotTest {
    private Level previousLevel,level;private String previousRun;private int previousDepth;
    private GameController game;
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup(){
        previousLevel=Dungeon.level;previousRun=Dungeon.runId;previousDepth=Dungeon.depth;
        level=new TestLevel();Dungeon.level=level;Dungeon.runId="metric-run";Dungeon.depth=1;
        game=new GameController(null,"menu:metric",error->{throw new AssertionError(error);});
    }
    @AfterEach void restore(){Dungeon.level=previousLevel;Dungeon.runId=previousRun;Dungeon.depth=previousDepth;}

    @Test void genericParticleCallbacksCannotCreateCurrentFactsOrHistory()throws Exception{
        game.onVisualCues("metric-run",level,1,List.of());assertNotNull(game.pollVisual());
        for(int count:new int[]{1,8,100})game.onVisualMetrics("metric-run",level,1,
                List.of(new VisualMetric("cell_particles",22,count,map("color",123,"alpha",.5))),Set.of(new Object()));
        assertFalse(state().containsKey("metrics"));assertFalse(state().containsKey("metrics_at"));
        assertFalse(game.hasDisplayEvents());assertEquals("last_observed",state().get("status"));
    }

    @Test void reviewedDensityIsImmutableSemanticEvidence()throws Exception{
        Map<String,Object> measured=map("count",8);
        VisualCue density=new VisualCue("sacrificial_flames",22,null,null,null,null,measured);
        game.onVisualCues("metric-run",level,1,List.of(density));
        GameController.VisualSnapshot first=game.pollVisual();assertNotNull(first);
        measured.put("count",999);
        assertEquals(map("count",8),cue(first.stateData(),0).get("appearance"));
        game.onVisualCues("metric-run",level,1,List.of(density));assertNull(game.pollVisual());
        game.onVisualCues("metric-run",level,1,List.of());assertNotNull(game.pollVisual());
        assertEquals(List.of(),state().get("cues"));
        assertEquals(map("count",8),cue(first.stateData(),0).get("appearance"));
    }

    @Test void equalIndependentIndicatorsRetainMultiplicityAndOldMapsNeverBind()throws Exception{
        VisualCue bomb=new VisualCue("bomb_countdown_2",22);
        game.onVisualCues("metric-run",level,1,List.of(bomb,bomb));
        GameController.VisualSnapshot first=game.pollVisual();assertEquals(2,first.cues.size());
        game.onVisualCues("metric-run",level,1,List.of(bomb));assertNotNull(game.pollVisual());
        Level replacement=new TestLevel();Dungeon.level=replacement;
        assertEquals("not_observed",state().get("status"));
        game.onVisualCues("metric-run",replacement,1,List.of(bomb));
        assertNotEquals(first.mapContext,game.pollVisual().mapContext);
    }

    @Test void densityHistoryIsBoundedWhileLiveCountsAndDiscreteWarningsStayCurrent()throws Exception{
        VisualCue five=new VisualCue("sacrificial_flames",22,null,null,null,null,map("count",5));
        VisualCue eight=new VisualCue("sacrificial_flames",22,null,null,null,null,map("count",8));
        game.recordVisualCues("metric-run",level,1,List.of(five),true,0);
        assertEquals(map("sacrificial_flames",map("sample_period_ms",250)),game.pollVisual().data().get("sampled_quantities"));
        game.recordVisualCues("metric-run",level,1,List.of(eight),true,50_000_000L);
        assertNull(game.pollVisual());assertEquals(map("count",8),cue(state(),0).get("appearance"));
        game.recordVisualCues("metric-run",level,1,List.of(eight),true,250_000_000L);
        assertNotNull(game.pollVisual());
        game.recordVisualCues("metric-run",level,1,List.of(eight,new VisualCue("red_target",23)),true,251_000_000L);
        assertNotNull(game.pollVisual(),"New danger bypasses quantity sampling");
        game.recordVisualCues("metric-run",level,1,List.of(new VisualCue("red_target",23)),true,252_000_000L);
        assertNotNull(game.pollVisual(),"A visible density source disappearing is immediate");
    }

    @Test void unexpectedDrawingFieldsBecomePartialAtProducerBoundary()throws Exception{
        VisualCue unexpected=new VisualCue("fixture_indicator",22,null,null,0,.5f,
                map("paused",true,"tint",map("multiply",List.of(1,1,1)),"atlas","private-path"));
        game.onVisualCues("metric-run",level,1,List.of(unexpected));
        Map<?,?> evidence=cue(state(),0);assertTrue((Boolean)evidence.get("unmapped_indicator"));
        Map<?,?> appearance=(Map<?,?>)evidence.get("appearance");assertEquals(true,appearance.get("paused"));
        String wire=com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec.encode(state());
        assertFalse(wire.contains("private-path"));assertFalse(wire.contains("multiply"));assertFalse(evidence.containsKey("color"));
    }
    @Test void reviewedRangeShapeSurvivesButGenericShapesRetainPartialDiagnostics()throws Exception{
        Map<String,Object> range=map("shape","ring","cells",List.of(22,23),"coverage","visual_extent","partial",true);
        game.onVisualCues("metric-run",level,1,List.of(new VisualCue("blast_wave",22,null,null,null,null,range)));
        assertEquals(range,cue(state(),0).get("appearance"));
        Map<String,Object> original=map("field","unknown_count","code","unavailable");
        Map<String,Object> generic=map("shape","triangle","presentation",map("status","partial","diagnostics",List.of(original)));
        Map<String,Object> projected=GameplayEvidence.semantic(generic);
        assertFalse(projected.containsKey("shape"));
        assertEquals(List.of(original,map("field","unmapped_indicator","code","unmapped_indicator")),
                ((Map<?,?>)projected.get("presentation")).get("diagnostics"));
        assertEquals(List.of(original),((Map<?,?>)generic.get("presentation")).get("diagnostics"));
        Map<String,Object> provenance=map("text_sources",map("text",map("kind","literal","origin","external","value",map("shape","user text"))));
        assertEquals(provenance,GameplayEvidence.semantic(provenance),"Protected source ASTs stay opaque at the semantic boundary");
    }
    @Test void repeatedEqualOccurrencesAreRecordedWithoutSamplingOrPublicIdentityLeak()throws Exception{
        VisualCue cue=new VisualCue("flame_burst",22,null,null,null,null,map("count",5,"basis","observed_particles"));
        Object firstEpisode=new Object(),secondEpisode=new Object();
        game.recordVisualCues("metric-run",level,1,List.of(cue),true,0,firstEpisode);
        GameController.VisualSnapshot first=game.pollVisual();assertNotNull(first);
        game.recordVisualCues("metric-run",level,1,List.of(cue),true,1,firstEpisode);
        assertNull(game.pollVisual(),"A query or draw of the same occurrence is not another event");
        game.recordVisualCues("metric-run",level,1,List.of(cue),true,2,secondEpisode);
        GameController.VisualSnapshot second=game.pollVisual();assertNotNull(second,"An equal repeated occurrence bypasses quantity sampling");
        assertEquals(first.cues,second.cues);
        assertEquals(Set.of("kind","cell","appearance"),cue(second.stateData(),0).keySet());
    }
    private static Map<?,?> cue(Map<String,Object> value,int index){return (Map<?,?>)((List<?>)value.get("cues")).get(index);}
    @SuppressWarnings("unchecked") private Map<String,Object> state()throws Exception{
        Method m=GameController.class.getDeclaredMethod("visualState");m.setAccessible(true);return (Map<String,Object>)m.invoke(game);
    }
    private static final class TestLevel extends Level{
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }
}
