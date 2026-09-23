package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import com.watabou.noosa.ScreenEffect;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic completed-draw callbacks: no renderer, actor work, settings, or profile writes. */
class ScreenEffectSnapshotTest {
    private Level previousLevel,level;
    private String previousRun;
    private int previousDepth;
    private Game previousGame;
    private Camera previousCamera;
    private Object previousSceneClass;
    private GameController controller;
    private final Object flashEpisode=new Object(),shakeEpisode=new Object();

    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup()throws Exception{
        previousLevel=Dungeon.level;previousRun=Dungeon.runId;previousDepth=Dungeon.depth;
        previousGame=Game.instance;previousCamera=Camera.main;previousSceneClass=field(Game.class,"sceneClass").get(null);
        level=new TestLevel();Dungeon.level=level;Dungeon.runId="screen-run";Dungeon.depth=1;
        new TestGame(new GameScene());Camera.main=new Camera(0,0,100,80,2);
        controller=new GameController(null,"menu:screen",error->{throw new AssertionError(error);});
    }
    @AfterEach void restore()throws Exception{
        Dungeon.level=previousLevel;Dungeon.runId=previousRun;Dungeon.depth=previousDepth;
        Game.instance=previousGame;Camera.main=previousCamera;field(Game.class,"sceneClass").set(null,previousSceneClass);
    }

    @Test void currentDrawsAreExactWhileQuantitiesAreSampledAndVisibleEndIsImmediate()throws Exception{
        draw(List.of(flash(flashEpisode,.8f)),0);
        GameController.DisplayEvent first=takeScreenEvents().get(0);
        assertEquals("sampled_display_snapshot_v1",first.data().get("format"));
        assertEquals(250,first.data().get("sample_period_ms"));
        assertEquals("run:screen-run",first.scopeId);assertNull(first.originalData());
        draw(List.of(flash(flashEpisode,.7f)),50);assertEquals(.7f,effects().get(0).get("opacity"));assertTrue(takeScreenEvents().isEmpty());
        assertEquals("draw-50",state().get("screen_effects_at"));
        draw(List.of(flash(flashEpisode,.6f)),249);assertTrue(takeScreenEvents().isEmpty());
        draw(List.of(flash(flashEpisode,.5f)),250);assertEquals(.5f,eventEffects(takeScreenEvents().get(0)).get(0).get("opacity"));
        draw(List.of(),251);assertEquals(List.of(),effects());assertEquals(List.of(),eventEffects(takeScreenEvents().get(0)));
        draw(List.of(),252);assertTrue(takeScreenEvents().isEmpty());
        draw(List.of(flash(flashEpisode,.4f)),253);assertEquals(.4f,eventEffects(takeScreenEvents().get(0)).get(0).get("opacity"));
        assertEquals(.8f,eventEffects(first).get(0).get("opacity"),"Queued history cannot change with a later draw");
    }

    @Test void replacementEpisodePublishesEvenWhenAllMeasuredFieldsAreIdentical()throws Exception{
        draw(List.of(flash(flashEpisode,.5f)),0);takeScreenEvents();
        Object replacement=new Object();
        draw(List.of(flash(replacement,.5f)),1);
        assertEquals(1,takeScreenEvents().size(),"Equal pixels from a newly observed episode still preserve its onset");
        draw(List.of(flash(replacement,.5f),shake(shakeEpisode,1)),2);assertEquals(1,takeScreenEvents().size());
        draw(List.of(shake(shakeEpisode,1)),3);
        assertEquals(1,eventEffects(takeScreenEvents().get(0)).size(),"Partial removal must not wait for the sample period");
        assertEquals("camera_displacement",effects().get(0).get("kind"));
    }

    @Test void initialEmptyDrawsAreExplicitCurrentEvidenceWithoutRepeatedHistory()throws Exception{
        assertFalse(state().containsKey("screen_effects"));
        draw(List.of(),0);assertEquals(List.of(),effects());assertTrue(takeScreenEvents().isEmpty());
        draw(List.of(),10_000);assertEquals("draw-10000",state().get("screen_effects_at"));assertTrue(takeScreenEvents().isEmpty());
        draw(List.of(flash(flashEpisode,.5f),shake(shakeEpisode,1)),10_001);takeScreenEvents();
        draw(List.of(shake(shakeEpisode,1),flash(flashEpisode,.5f)),10_501);
        assertTrue(takeScreenEvents().isEmpty(),"Input iteration order is not a visual change");
    }

    @Test void newDrawRequiresItsOwnScreenCallbackAndMismatchedScopeNeverBindsOldValues()throws Exception{
        draw(List.of(flash(flashEpisode,.5f)),0);GameController.DisplayEvent old=takeScreenEvents().get(0);
        controller.onVisualCues("screen-run",level,1,List.of());
        assertFalse(state().containsKey("screen_effects"),"A new cue generation cannot carry the preceding screen sample");
        controller.recordScreenEffects("wrong-run",level,1,List.of(shake(shakeEpisode,8)),1_000_000L,"wrong-run");
        controller.recordScreenEffects("screen-run",new TestLevel(),1,List.of(shake(shakeEpisode,8)),1_000_000L,"wrong-level");
        controller.recordScreenEffects("screen-run",level,2,List.of(shake(shakeEpisode,8)),1_000_000L,"wrong-depth");
        assertFalse(state().containsKey("screen_effects"));assertTrue(takeScreenEvents().isEmpty());
        Dungeon.depth=2;assertEquals("not_rendered",state().get("status"));
        controller.onVisualCues("screen-run",level,2,List.of());
        controller.recordScreenEffects("screen-run",level,2,List.of(shake(shakeEpisode,2)),2_000_000L,"new-depth");
        GameController.DisplayEvent next=takeScreenEvents().get(0);
        assertEquals(2,next.data().get("depth"));assertNotEquals(old.data().get("map_context"),next.data().get("map_context"));
        Dungeon.runId="other-run";assertFalse(state().containsKey("screen_effects"));
        Dungeon.runId="screen-run";Dungeon.level=new TestLevel();assertFalse(state().containsKey("screen_effects"));
        assertEquals("run:screen-run",old.scopeId);assertEquals(1,old.data().get("depth"));
    }

    @Test void sceneAndCameraReplacementInvalidateCurrentSamplesWithoutChangingMapIdentity()throws Exception{
        draw(List.of(shake(shakeEpisode,1)),0);String context=(String)state().get("map_context");takeScreenEvents();
        new TestGame(new GameScene());assertFalse(state().containsKey("screen_effects"));
        draw(List.of(shake(shakeEpisode,1)),1);
        assertEquals(context,state().get("map_context"));assertEquals(1,takeScreenEvents().size());
        Camera.main=new Camera(0,0,100,80,2);assertFalse(state().containsKey("screen_effects"));
        draw(List.of(shake(shakeEpisode,1)),2);
        assertEquals(context,state().get("map_context"));assertEquals(1,takeScreenEvents().size());
    }

    @Test void valuesAreFrozenAndOpaqueEpisodeIdentitiesNeverEnterThePublicData()throws Exception{
        Map<String,Object> fields=map("opacity",.5f,"color",0xFFFFFF,"blend","normal",
                "screen_rect",new ArrayList<>(List.of(0,0,200,160)));
        List<ScreenEffect> input=new ArrayList<>(List.of(new ScreenEffect("screen_overlay",flashEpisode,fields)));
        draw(input,0);input.clear();fields.put("opacity",.9f);
        assertEquals(.5f,effects().get(0).get("opacity"));
        GameController.DisplayEvent captured=takeScreenEvents().get(0);
        assertEquals(Set.of("format","sample_period_ms","depth","map_context","occurred_at","screen_effects"),captured.data().keySet());
        assertEquals(Set.of("kind","opacity","color","blend","screen_rect"),eventEffects(captured).get(0).keySet());
        assertThrows(UnsupportedOperationException.class,()->eventEffects(captured).get(0).put("opacity",1f));
        assertThrows(UnsupportedOperationException.class,()->effects().clear());
    }

    @Test void historyUsesTheSharedFifoAndRetainsItsOriginalScopeAcrossTransitions(){
        controller.onVisualCues("screen-run",level,1,List.of());
        controller.enqueueDisplayEvent(new GameController.DisplayEvent("run:screen-run","game.banner",map("kind","boss_slain"),null));
        controller.recordScreenEffects("screen-run",level,1,List.of(flash(flashEpisode,.5f)),0,"first");
        Dungeon.runId="other-run";
        List<GameController.DisplayEvent> batch=controller.peekDisplayEvents(20);
        assertEquals(List.of("game.visual","game.banner","game.screen_visual"),kinds(batch));
        assertTrue(batch.stream().allMatch(event->event.scopeId.equals("run:screen-run")));
        controller.freezeDisplayEvents();controller.acknowledgeDisplayEvents(batch);assertFalse(controller.hasDisplayEvents());
    }

    private void draw(List<ScreenEffect> effects,long millis){
        controller.onVisualCues("screen-run",level,1,List.of());
        controller.recordScreenEffects("screen-run",level,1,effects,millis*1_000_000L,"draw-"+millis);
    }
    private List<GameController.DisplayEvent> takeScreenEvents(){
        List<GameController.DisplayEvent> batch=controller.peekDisplayEvents(1000),screens=new ArrayList<>();
        for(GameController.DisplayEvent event:batch)if("game.screen_visual".equals(event.kind))screens.add(event);
        controller.acknowledgeDisplayEvents(batch);return screens;
    }
    private static ScreenEffect flash(Object episode,float opacity){return new ScreenEffect("screen_overlay",episode,
            map("color",0xFFFFFF,"opacity",opacity,"blend","additive","screen_rect",List.of(0,0,200,160)));}
    private static ScreenEffect shake(Object episode,int offset){return new ScreenEffect("camera_displacement",episode,
            map("offset_pixels",List.of(offset,-offset),"viewport_pixels",List.of(0,0,200,160)));}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> effects()throws Exception{return (List<Map<String,Object>>)state().get("screen_effects");}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> eventEffects(GameController.DisplayEvent event){return (List<Map<String,Object>>)event.data().get("screen_effects");}
    @SuppressWarnings("unchecked") private Map<String,Object> state()throws Exception{
        Method method=GameController.class.getDeclaredMethod("visualState");method.setAccessible(true);return (Map<String,Object>)method.invoke(controller);
    }
    private static List<String> kinds(List<GameController.DisplayEvent> events){List<String> kinds=new ArrayList<>();for(GameController.DisplayEvent event:events)kinds.add(event.kind);return kinds;}
    private static Field field(Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    private static final class TestGame extends Game {TestGame(Scene current){super(Scene.class,Game.platform);scene=current;requestedReset=false;}}
    private static final class TestLevel extends Level {
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }
}
