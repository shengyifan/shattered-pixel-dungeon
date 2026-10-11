package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Public occurrence tests only; no audio device, real game launch, actor turn or profile. */
class AudioOccurrenceTest {
    private Game previousGame; private Hero previousHero; private Level previousLevel;
    private String previousRun; private int previousDepth;
    private Object previousScene,previousSelector,previousSceneClass,previousInput;
    private GameController controller;

    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup()throws Exception{
        previousGame=Game.instance;previousHero=Dungeon.hero;previousLevel=Dungeon.level;
        previousRun=Dungeon.runId;previousDepth=Dungeon.depth;
        previousScene=field(GameScene.class,"scene").get(null);previousSelector=field(GameScene.class,"cellSelector").get(null);
        previousSceneClass=field(Game.class,"sceneClass").get(null);previousInput=Game.inputHandler;Game.inputHandler=null;
        GameScene scene=new GameScene();field(GameScene.class,"scene").set(null,scene);field(GameScene.class,"cellSelector").set(null,null);
        new TestGame(scene);Dungeon.hero=new Hero();Dungeon.hero.ready=true;Dungeon.runId="audio-fixture";Dungeon.depth=1;
        Dungeon.level=new FixtureLevel();
        controller=new GameController(null,"menu:audio-fixture",error->{throw new AssertionError(error);});
    }
    @AfterEach void restore()throws Exception{
        Game.instance=previousGame;Dungeon.hero=previousHero;Dungeon.level=previousLevel;Dungeon.runId=previousRun;Dungeon.depth=previousDepth;
        field(GameScene.class,"scene").set(null,previousScene);field(GameScene.class,"cellSelector").set(null,previousSelector);
        field(Game.class,"sceneClass").set(null,previousSceneClass);field(Game.class,"inputHandler").set(null,previousInput);
    }

    @Test void onlyExactAlarmInActualGameRunBecomesAnEvent(){
        controller.onSoundPlayback(Assets.Sounds.HIT);controller.onSoundPlayback(null);controller.onSoundPlayback("ALERT");
        assertFalse(controller.hasDisplayEvents());
        new TestGame(new Scene());controller.onSoundPlayback(Assets.Sounds.ALERT);assertFalse(controller.hasDisplayEvents());
        new TestGame(new GameScene());Dungeon.runId=null;controller.onSoundPlayback(Assets.Sounds.ALERT);assertFalse(controller.hasDisplayEvents());
        Dungeon.runId="old-retained-run";Game.instance=null;
        controller.onSoundPlayback(Assets.Sounds.ALERT);assertFalse(controller.hasDisplayEvents());
    }

    @Test void alarmPayloadHasNoLocationSourceOrHiddenFactsAndScopeIsFrozen(){
        // No FOV cells are initialized: a verified audible event does not depend on visual visibility.
        assertNull(Dungeon.level.heroFOV);
        controller.onSoundPlayback(new String(Assets.Sounds.ALERT));
        GameController.DisplayEvent event=controller.peekDisplayEvents(1).get(0);
        assertEquals("run:audio-fixture",event.scopeId);assertEquals("game.audio",event.kind);assertNull(event.originalData());
        assertEquals(Set.of("format","kind","basis","occurred_at"),event.data().keySet());
        assertEquals("gameplay_audio_occurrence_v1",event.data().get("format"));
        assertEquals("alarm",event.data().get("kind"));assertEquals("native_playback",event.data().get("basis"));
        assertDoesNotThrow(()->Instant.parse((String)event.data().get("occurred_at")));
        Dungeon.depth=25;Dungeon.runId="different-run";
        assertEquals("run:audio-fixture",event.scopeId);
        assertFalse(event.data().toString().contains(Assets.Sounds.ALERT));
        assertThrows(UnsupportedOperationException.class,()->event.data().put("cell",3));
    }

    @Test void duplicateOccurrencesShareFifoOrderAndTeardownKeepsOnlyCapturedCut(){
        controller.onSoundPlayback(Assets.Sounds.ALERT);
        GameController.DisplayEvent banner=new GameController.DisplayEvent("run:audio-fixture","game.banner",Map.of("kind","fixture"),null);
        controller.enqueueDisplayEvent(banner);controller.onSoundPlayback(Assets.Sounds.ALERT);
        List<GameController.DisplayEvent> events=controller.peekDisplayEvents(100);
        assertEquals(List.of("game.audio","game.banner","game.audio"),List.of(events.get(0).kind,events.get(1).kind,events.get(2).kind));
        assertNotSame(events.get(0),events.get(2));assertSame(banner,events.get(1));
        controller.freezeDisplayEvents();controller.onSoundPlayback(Assets.Sounds.ALERT);
        assertEquals(events,controller.peekDisplayEvents(100));
        controller.acknowledgeDisplayEvents(events);assertFalse(controller.hasDisplayEvents());
    }

    @Test void audioHistoryNeverChangesCurrentStateOrIntentRevision()throws Exception{
        GameController.State before=capture();
        controller.onSoundPlayback(Assets.Sounds.ALERT);controller.onSoundPlayback(Assets.Sounds.ALERT);
        GameController.State after=capture();
        assertEquals(before.version,after.version);assertEquals(before.publicState,after.publicState);
        List<GameController.DisplayEvent> batch=controller.peekDisplayEvents(100);
        assertEquals(2,batch.stream().filter(event->event.kind.equals("game.audio")).count());
        controller.acknowledgeDisplayEvents(batch);
        assertEquals(after.version,capture().version);
    }

    private GameController.State capture()throws Exception{
        controller.afterDraw();Method method=GameController.class.getDeclaredMethod("capture",boolean.class);method.setAccessible(true);
        return (GameController.State)method.invoke(controller,false);
    }
    private static Field field(Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    private static final class FixtureLevel extends Level{
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }
    private static final class TestGame extends Game{
        TestGame(Scene scene){super(Scene.class,Game.platform);this.scene=scene;requestedReset=false;}
    }
}
