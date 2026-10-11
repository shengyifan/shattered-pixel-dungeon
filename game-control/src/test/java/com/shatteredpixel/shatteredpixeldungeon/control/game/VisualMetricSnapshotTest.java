package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.RuntimeObserver;
import org.junit.jupiter.api.*;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Current observations retain reviewed gameplay measurements without generic particle-history samples. */
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

    @Test void genericParticleFieldsCannotEnterCurrentFactsOrFrozenHistory()throws Exception{
        VisualCue unexpected=new VisualCue("fixture_indicator",22,null,null,null,null,
                map("rendered_particles",8,"color",123,"alpha",.5));
        game.onVisualCues("metric-run",level,1,List.of(unexpected), true, null);
        List<GameController.DisplayEvent> events=game.peekDisplayEvents(8);assertEquals(1,events.size());
        assertEquals("game.visual",events.get(0).kind);
        GameController.DisplayEvent snapshot=DisplayEventTestFixture.next(game, "game.visual");assertNotNull(snapshot);
        for(Map<String,Object> data:List.of(state(),snapshot.data(),events.get(0).data())){
            String encoded=com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec.encode(data);
            for(String field:List.of("rendered_particles","color","alpha","metrics","metrics_at"))
                assertFalse(encoded.contains("\""+field+"\":"),field);
            assertTrue(encoded.contains("unmapped_indicator"));
        }
    }

    @Test void reviewedDensityIsImmutableSemanticEvidence()throws Exception{
        Map<String,Object> measured=map("count",8);
        VisualCue density=new VisualCue("sacrificial_flames",22,null,null,null,null,measured);
        game.onVisualCues("metric-run",level,1,List.of(density), true, null);
        GameController.DisplayEvent first=DisplayEventTestFixture.next(game, "game.visual");assertNotNull(first);
        measured.put("count",999);
        assertEquals(map("count",8),cue(first.data(),0).get("appearance"));
        game.onVisualCues("metric-run",level,1,List.of(density), true, null);assertNull(DisplayEventTestFixture.next(game, "game.visual"));
        game.onVisualCues("metric-run",level,1,List.of(), true, null);assertNotNull(DisplayEventTestFixture.next(game, "game.visual"));
        assertEquals(List.of(),state().get("cues"));
        assertEquals(map("count",8),cue(first.data(),0).get("appearance"));
    }

    @Test void equalIndependentIndicatorsRetainMultiplicityAndOldMapsNeverBind()throws Exception{
        VisualCue bomb=new VisualCue("bomb_countdown_2",22);
        game.onVisualCues("metric-run",level,1,List.of(bomb,bomb), true, null);
        GameController.DisplayEvent first=DisplayEventTestFixture.next(game, "game.visual");assertEquals(2,((List<?>)first.data().get("cues")).size());
        game.onVisualCues("metric-run",level,1,List.of(bomb), true, null);assertNotNull(DisplayEventTestFixture.next(game, "game.visual"));
        Level replacement=new TestLevel();Dungeon.level=replacement;
        assertEquals("not_observed",state().get("status"));
        game.onVisualCues("metric-run",replacement,1,List.of(bomb), true, null);
        assertNotEquals(first.data().get("map_context"),DisplayEventTestFixture.next(game, "game.visual").data().get("map_context"));
    }

    @Test void densityHistoryIsBoundedWhileLiveCountsAndDiscreteWarningsStayCurrent()throws Exception{
        VisualCue five=new VisualCue("sacrificial_flames",22,null,null,null,null,map("count",5));
        VisualCue eight=new VisualCue("sacrificial_flames",22,null,null,null,null,map("count",8));
        game.recordVisualCues("metric-run",level,1,List.of(five),true,0, null);
        assertEquals(map("sacrificial_flames",map("sample_period_ms",250)),DisplayEventTestFixture.next(game, "game.visual").data().get("sampled_quantities"));
        game.recordVisualCues("metric-run",level,1,List.of(eight),true,50_000_000L, null);
        assertNull(DisplayEventTestFixture.next(game, "game.visual"));assertEquals(map("count",8),cue(state(),0).get("appearance"));
        game.recordVisualCues("metric-run",level,1,List.of(eight),true,250_000_000L, null);
        assertNotNull(DisplayEventTestFixture.next(game, "game.visual"));
        game.recordVisualCues("metric-run",level,1,List.of(eight,new VisualCue("red_target",23)),true,251_000_000L, null);
        assertNotNull(DisplayEventTestFixture.next(game, "game.visual"),"New danger bypasses quantity sampling");
        game.recordVisualCues("metric-run",level,1,List.of(new VisualCue("red_target",23)),true,252_000_000L, null);
        assertNotNull(DisplayEventTestFixture.next(game, "game.visual"),"A visible density source disappearing is immediate");
    }

    @Test void unexpectedDrawingFieldsBecomePartialAtProducerBoundary()throws Exception{
        VisualCue unexpected=new VisualCue("fixture_indicator",22,null,null,0,.5f,
                map("paused",true,"tint",map("multiply",List.of(1,1,1)),"atlas","private-path"));
        game.onVisualCues("metric-run",level,1,List.of(unexpected), true, null);
        Map<?,?> evidence=cue(state(),0);assertTrue((Boolean)evidence.get("unmapped_indicator"));
        Map<?,?> appearance=(Map<?,?>)evidence.get("appearance");assertEquals(true,appearance.get("paused"));
        String wire=com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec.encode(state());
        assertFalse(wire.contains("private-path"));assertFalse(wire.contains("multiply"));assertFalse(evidence.containsKey("color"));
    }
    @Test void reviewedRangeShapeSurvivesButGenericShapesRetainPartialDiagnostics()throws Exception{
        Map<String,Object> range=map("shape","ring","cells",List.of(22,23),"coverage","visual_extent","partial",true);
        game.onVisualCues("metric-run",level,1,List.of(new VisualCue("blast_wave",22,null,null,null,null,range)), true, null);
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
    @Test void currentObserverHookRetainsEqualOccurrencesWithoutPrivateIdentityLeak()throws Exception{
        VisualCue cue=new VisualCue("flame_burst",22,null,null,null,null,map("count",5,"basis","observed_particles"));
        Object firstEpisode=new Object(),secondEpisode=new Object();
        RuntimeObserver observer=game;
        observer.onVisualCues("metric-run",level,1,List.of(cue),true,firstEpisode);
        GameController.DisplayEvent first=DisplayEventTestFixture.next(game, "game.visual");assertNotNull(first);
        observer.onVisualCues("metric-run",level,1,List.of(cue),true,firstEpisode);
        assertNull(DisplayEventTestFixture.next(game, "game.visual"),"A query or draw of the same occurrence is not another event");
        observer.onVisualCues("metric-run",level,1,List.of(cue),true,secondEpisode);
        GameController.DisplayEvent second=DisplayEventTestFixture.next(game, "game.visual");assertNotNull(second,"An equal repeated occurrence bypasses quantity sampling");
        assertEquals(((List<?>)first.data().get("cues")),((List<?>)second.data().get("cues")));
        assertEquals(Set.of("kind","cell","appearance"),cue(second.data(),0).keySet());
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
