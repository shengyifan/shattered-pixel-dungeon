package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.CurrencyIndicator;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import com.watabou.noosa.VisualCue;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Frame acknowledgement and scope tests without an Actor step, renderer, or file save. */
class RenderedBoundaryTest {
    private Game previousGame;private Hero previousHero;private Level previousLevel;private String previousRun;
    private int previousDepth;private Object previousScene,previousSelector,previousSceneClass,previousInput;
    private GameController controller;private Level level;
    @BeforeAll static void assets(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup()throws Exception{
        previousGame=Game.instance;previousHero=Dungeon.hero;previousLevel=Dungeon.level;previousRun=Dungeon.runId;previousDepth=Dungeon.depth;
        previousScene=field(GameScene.class,"scene").get(null);previousSelector=field(GameScene.class,"cellSelector").get(null);
        previousSceneClass=field(Game.class,"sceneClass").get(null);previousInput=Game.inputHandler;Game.inputHandler=null;
        GameScene scene=new GameScene();field(GameScene.class,"scene").set(null,scene);field(GameScene.class,"cellSelector").set(null,null);
        new TestGame(scene);Dungeon.hero=new Hero();Dungeon.hero.ready=true;Dungeon.runId="a";Dungeon.depth=1;
        level=new IdentityLevel();Dungeon.level=level;
        controller=new GameController(null,"menu:test",error->{throw new AssertionError(error);});
    }
    @AfterEach void restore()throws Exception{
        Game.instance=previousGame;Dungeon.hero=previousHero;Dungeon.level=previousLevel;Dungeon.runId=previousRun;Dungeon.depth=previousDepth;
        field(GameScene.class,"scene").set(null,previousScene);field(GameScene.class,"cellSelector").set(null,previousSelector);
        field(Game.class,"sceneClass").set(null,previousSceneClass);field(Game.class,"inputHandler").set(null,previousInput);
    }

    @Test void aStableStateMustSurviveALaterCompleteDrawBeforeItIsReady()throws Exception{
        assertFalse(ready());
        draw(List.of(new VisualCue("red_target",10)));
        assertFalse(ready());assertFalse(ready(),"Repeated processing is not another draw");
        draw(List.of(new VisualCue("red_target",10)));
        assertTrue(ready());
        assertNotNull(controller.pollVisual());assertNull(controller.pollVisual(),"Identical frames acknowledge rendering without duplicate events");
        invoke("resetRenderedBoundary");assertFalse(ready());draw(List.of());assertTrue(ready());
        assertTrue(controller.pollVisual().cues.isEmpty(),"Clearing a marker is a retained display event");
    }

    @Test void oldRunOrMapFramesCannotAcknowledgeANewMapAndUiRebuildKeepsContext()throws Exception{
        draw(List.of(new VisualCue("red_target",10)));String first=controller.pollVisual().mapContext;
        assertFalse(ready());draw(List.of(new VisualCue("red_target",10)));assertTrue(ready());
        GameScene replacement=new GameScene();field(GameScene.class,"scene").set(null,replacement);new TestGame(replacement);
        draw(List.of(new VisualCue("red_target",10)));assertNull(controller.pollVisual());
        assertEquals(first,visualState().get("map_context"));
        Level second=new IdentityLevel();Dungeon.level=second;
        assertFalse(ready());assertEquals("not_rendered",visualState().get("status"));
        draw(List.of(new VisualCue("red_target",10)));assertFalse(ready()); // still the old level token
        controller.onVisualCues("a",second,1,List.of());assertFalse(ready());
        String next=controller.pollVisual().mapContext;assertNotEquals(first,next);
        controller.onVisualCues("a",second,1,List.of());assertTrue(ready());
        Dungeon.runId="b";assertFalse(ready());assertEquals("not_rendered",visualState().get("status"));
    }

    @Test void purelyRenderedChangesDoNotInvalidateWorldIntentVersion()throws Exception{
        // No map arrays are needed: these callbacks annotate existing draws, never inspect a level.
        draw(List.of(new VisualCue("red_target",10)));
        GameController.State first=capture();
        draw(List.of());
        GameController.State second=capture();
        assertEquals(first.version,second.version);
        assertNotEquals(first.publicState.get("visual_cues"),second.publicState.get("visual_cues"));
        assertNull(controller.latest().publicState.get("occurred_at"));
        assertEquals(20,Dungeon.hero.HP);
    }

    @Test void currencyFadePreservesRevisionButActualGoldAndEnergyStillInvalidateIt()throws Exception{
        int previousSlot=GamesInProgress.curSlot,previousGold=Dungeon.gold,previousEnergy=Dungeon.energy;
        float previousElapsed=Game.elapsed;
        boolean previousShowGold=CurrencyIndicator.showGold;
        try(TextObservationFixture ignored=new TextObservationFixture()) {
            GamesInProgress.curSlot=1;
            Dungeon.gold=478;Dungeon.energy=3;CurrencyIndicator.showGold=false;
            CurrencyIndicatorIntentTest.NativeIndicator indicator=new CurrencyIndicatorIntentTest.NativeIndicator();
            Game.scene().add(indicator);
            Game.elapsed=0;indicator.update();
            GameController.State shown=capture();
            assertEquals(478,((Map<?,?>)shown.publicState.get("hero")).get("gold"));
            assertEquals(3,((Map<?,?>)shown.publicState.get("hero")).get("energy"));

            Game.elapsed=2f;indicator.update();
            GameController.State faded=capture();
            assertNotEquals(shown.publicState.get("ui"),faded.publicState.get("ui"));
            assertEquals(shown.version,faded.version,"A passive currency fade is not a new player decision");

            Dungeon.gold++;
            GameController.State goldChanged=capture();
            assertNotEquals(faded.version,goldChanged.version,"World gold changes remain part of the revision");
            assertEquals(479,((Map<?,?>)goldChanged.publicState.get("hero")).get("gold"));
            Dungeon.energy++;
            GameController.State energyChanged=capture();
            assertNotEquals(goldChanged.version,energyChanged.version,"World energy changes remain part of the revision");
            assertEquals(4,((Map<?,?>)energyChanged.publicState.get("hero")).get("energy"));
        } finally {
            GamesInProgress.curSlot=previousSlot;Dungeon.gold=previousGold;Dungeon.energy=previousEnergy;
            Game.elapsed=previousElapsed;CurrencyIndicator.showGold=previousShowGold;
        }
    }

    @Test void emptyTextInitializationPreservesRevisionButVisibleTextChangesStillInvalidateIt()throws Exception{
        try(TextObservationFixture ignored=new TextObservationFixture()) {
            BitmapText text=new BitmapText((String)null,null);
            text.camera=new Camera(0,0,100,100,1);text.x=10;text.y=10;text.width=70;text.height=10;
            Game.scene().add(text);
            GameController.State absent=capture();
            text.text("");
            GameController.State empty=capture();
            assertEquals(absent.publicState.get("ui"),empty.publicState.get("ui"));
            assertEquals(absent.version,empty.version,"An unchanged empty label cannot invalidate a returned revision");

            text.text(ResourceTextFixture.literal("Visible notice"));
            GameController.State shown=capture();
            assertNotEquals(empty.version,shown.version);
            text.text("");
            GameController.State cleared=capture();
            assertNotEquals(shown.version,cleared.version);
            text.text("未绑定的第一条提示");
            GameController.State firstUnbound=capture();
            assertNotEquals(cleared.version,firstUnbound.version);
            text.text("未绑定的第二条提示");
            assertNotEquals(firstUnbound.version,capture().version,"Untranslated visible changes still invalidate intent");
        }
    }

    @Test void semanticCueSnapshotsAreCanonicalAndDetachedFromMutableInput(){
        List<VisualCue> cues=new ArrayList<>(List.of(new VisualCue("red_target",20),new VisualCue("red_target",10),new VisualCue("red_target",20)));
        draw(cues);cues.clear();GameController.VisualSnapshot snapshot=controller.pollVisual();
        assertEquals(2,snapshot.cues.size());assertEquals(10,snapshot.cues.get(0).cell);
        assertThrows(UnsupportedOperationException.class,()->snapshot.cues.clear());
        draw(List.of(new VisualCue("red_target",10),new VisualCue("red_target",20)));assertNull(controller.pollVisual());
        assertFalse(snapshot.data().containsKey("generation"));assertFalse(snapshot.data().containsKey("levelIdentity"));
    }

    @Test void delayedFirstDrawableMustBeAcknowledgedButItsInternalReadinessNeverEscapes()throws Exception{
        controller.onVisualCues("a",level,1,List.of(),false);assertFalse(ready());
        controller.onVisualCues("a",level,1,List.of(),false);assertFalse(ready());
        assertFalse(visualState().containsKey("presentationReady"));
        assertFalse(controller.pollVisual().data().containsKey("presentationReady"));
        controller.onVisualCues("a",level,1,List.of(new VisualCue("black_goo_droplets",10)),true);
        assertTrue(ready(),"The first genuinely drawn cue can now complete the already-stable action");
        controller.onVisualCues("a",level,1,List.of(),true);
        assertTrue(ready(),"A later natural flicker is not a new initial-presentation wait");
    }

    @Test void newlyCreatedMenusNeedASubsequentFrameBeforeTheirControlsAreCertified()throws Exception {
        new TestGame(new Scene());
        field(GameController.class,"frame").setLong(controller,10);
        assertFalse(ready());assertFalse(ready(),"A second query is not another completed draw");
        field(GameController.class,"frame").setLong(controller,11);
        assertTrue(ready());
        new TestGame(new Scene());
        assertFalse(ready(),"The previous menu draw cannot certify a replacement scene");
        field(GameController.class,"frame").setLong(controller,12);
        assertTrue(ready());
        invoke("resetRenderedBoundary");assertFalse(ready());
        field(GameController.class,"frame").setLong(controller,13);assertTrue(ready());
    }

    private void draw(List<VisualCue> cues){controller.onVisualCues("a",level,1,cues);}
    private boolean ready()throws Exception{return (Boolean)invoke("renderedBoundaryReady");}
    @SuppressWarnings("unchecked") private Map<String,Object> visualState()throws Exception{return (Map<String,Object>)invoke("visualState");}
    private Object invoke(String name)throws Exception{Method m=GameController.class.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(controller);}
    private GameController.State capture()throws Exception{Method m=GameController.class.getDeclaredMethod("capture",boolean.class);m.setAccessible(true);return (GameController.State)m.invoke(controller,false);}
    private static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    private static final class IdentityLevel extends Level{
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }
    private static final class TestGame extends Game{TestGame(Scene current){super(Scene.class,Game.platform);scene=current;requestedReset=false;}}
}
