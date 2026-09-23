package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit draw-boundary simulation; no renderer, actor step, save, or live profile is used. */
class ContinuousUiDrawBoundaryTest {
    @TempDir Path profile;
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void continuousHandoffWaitsForMeaningfulUiChangesToActuallyReachTheNextDraw()throws Exception {
        Map<Field,Object> previous=new LinkedHashMap<>();
        Game previousGame=Game.instance;
        try(TextObservationFixture ignored=new TextObservationFixture()) {
            preserve(previous,Dungeon.class,"hero","level","runId","depth");
            preserve(previous,GamesInProgress.class,"curSlot");
            preserve(previous,GameScene.class,"scene","cellSelector","actorThread");
            preserve(previous,Actor.class,"current","yielded","yieldedActor","motionMonitor");
            preserve(previous,Game.class,"sceneClass","inputHandler");
            GameScene scene=new GameScene();new TestGame(scene);
            field(GameScene.class,"scene").set(null,scene);
            field(GameScene.class,"cellSelector").set(null,null);
            field(GameScene.class,"actorThread").set(null,null);
            field(Actor.class,"current").set(null,null);
            field(Actor.class,"motionMonitor").set(null,null);
            Game.inputHandler=null;GamesInProgress.curSlot=1;
            Dungeon.hero=new Hero();Dungeon.hero.ready=false;Dungeon.hero.resting=true;
            Dungeon.level=null;Dungeon.runId="draw-boundary-fixture";Dungeon.depth=1;
            field(Actor.class,"yielded").setBoolean(null,true);
            field(Actor.class,"yieldedActor").set(null,Dungeon.hero);
            BitmapText notice=new BitmapText(ResourceTextFixture.literal("Shown warning"),null);
            notice.camera=new Camera(0,0,100,100,1);notice.x=10;notice.y=10;notice.width=70;notice.height=10;
            scene.add(notice);
            List<Throwable> failures=new ArrayList<>();
            GameController controller=new GameController(profile,"menu:fixture",failures::add);
            Class<?> type=Class.forName(GameController.class.getName()+"$Work");
            Constructor<?> ctor=type.getDeclaredConstructor(String.class,Map.class,String.class);ctor.setAccessible(true);
            Object work=ctor.newInstance("fixture:1",Map.of("action","rest"),"f1");
            field(type,"startedInRun").setBoolean(work,true);
            field(GameController.class,"executing").set(controller,work);
            GameController.Execution execution=(GameController.Execution)field(type,"execution").get(work);

            controller.afterDraw(); // Simulates the completed first draw, before its native update.
            notice.hardlight(0xFF0000);
            controller.afterFrame();
            assertFalse(execution.firstResponse.isDone(),"A continuous handoff cannot publish an undrawn warning color");
            assertNull(field(type,"activityVersion").get(work));
            assertFalse(controller.beforeActorResume(),"The original activity remains held until its first display boundary");
            assertTrue(failures.isEmpty());

            controller.afterDraw(); // The second draw includes the changed warning color.
            controller.afterFrame();
            assertNotNull(field(type,"activityVersion").get(work),"The next actual draw releases the UI readiness gate");
            assertTrue(execution.firstResponse.isDone());
            assertFalse(execution.firstResponse.isCompletedExceptionally(),failures.toString());
            assertEquals("continuous_activity",execution.firstResponse.join().phase);
            assertFalse(execution.completion.isDone(),"An initial activity response does not complete or replay rest");
            assertTrue(failures.isEmpty());
        } finally {
            for(Map.Entry<Field,Object> entry:previous.entrySet())entry.getKey().set(null,entry.getValue());
            Game.instance=previousGame;
        }
    }

    private static void preserve(Map<Field,Object> values,Class<?> type,String... names)throws Exception{
        for(String name:names){Field f=field(type,name);values.put(f,f.get(null));}
    }
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static final class TestGame extends Game{TestGame(Scene value){super(Scene.class,Game.platform);scene=value;requestedReset=false;}}
}
