package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** The restored-death exception must not accept a living or terminated scheduler. */
class UnstartedActorBoundaryTest {
    private final Map<Field,Object> saved=new LinkedHashMap<>();
    private Game oldGame;private Hero oldHero;private GameController controller;
    @BeforeAll static void assets(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup()throws Exception{
        oldGame=Game.instance;oldHero=Dungeon.hero;
        remember(GameScene.class,"actorThread",null);remember(GameScene.class,"scene",null);
        remember(GameScene.class,"cellSelector",null);remember(Game.class,"sceneClass",null);
        remember(Actor.class,"current",null);remember(Actor.class,"yielded",false);
        GameScene scene=new GameScene();field(GameScene.class,"scene").set(null,scene);new TestGame(scene);
        Dungeon.hero=new Hero();Dungeon.hero.HP=0;Dungeon.hero.ready=false;
        controller=new GameController(null,"menu:test",error->{throw new AssertionError(error);});
    }
    @AfterEach void restore()throws Exception{
        for(Map.Entry<Field,Object> entry:saved.entrySet())entry.getKey().set(null,entry.getValue());
        Game.instance=oldGame;Dungeon.hero=oldHero;
    }

    @Test void restoredDeathHasAReadOnlyRenderBoundaryWithoutPretendingActorYielded()throws Exception{
        AtomicInteger observed=new AtomicInteger();
        assertTrue(GameScene.actorThreadNotStarted());
        assertTrue(GameScene.atActorHandoff(()->{
            observed.incrementAndGet();assertFalse(Actor.isYielded());assertFalse(Actor.processing());
        }));
        assertEquals(1,observed.get());assertTrue(stable());
        assertFalse(Actor.isYielded());assertFalse(Actor.processing());assertEquals(0,Dungeon.hero.HP);
    }

    @Test void livingHeroStillRequiresTheNormalFirstActorHandoff()throws Exception{
        Dungeon.hero.HP=20;Dungeon.hero.ready=true;
        assertTrue(GameScene.actorThreadNotStarted());assertFalse(stable());
        assertFalse(Actor.isYielded());assertNull(field(GameScene.class,"actorThread").get(null));
    }

    @Test void currentActorDisqualifiesTheNeverCreatedWorkerBoundary()throws Exception{
        field(Actor.class,"current").set(null,Dungeon.hero);
        assertFalse(GameScene.actorThreadNotStarted());assertFalse(stable());
        assertFalse(GameScene.atActorHandoff(()->fail("Current actor is not quiescent")));
    }

    @Test void aWorkerObjectNotYetStartedDoesNotQualifyAsNeverCreated()throws Exception{
        field(GameScene.class,"actorThread").set(null,new Thread(()->{}));
        assertFalse(GameScene.actorThreadNotStarted());assertFalse(stable());
        assertFalse(GameScene.atActorHandoff(()->fail("Scheduler creation must finish normally")));
    }

    @Test void terminatedWorkerIsNotMistakenForTheRestoredDeathException()throws Exception{
        Thread worker=new Thread(()->{},"test-terminated-worker");worker.start();worker.join(2000);
        assertEquals(Thread.State.TERMINATED,worker.getState());
        field(GameScene.class,"actorThread").set(null,worker);
        assertFalse(GameScene.actorThreadNotStarted());assertFalse(stable());
        assertFalse(GameScene.atActorHandoff(()->fail("A stopped worker may have failed during an action")));
    }

    private boolean stable()throws Exception{Method method=GameController.class.getDeclaredMethod("stable");method.setAccessible(true);return (Boolean)method.invoke(controller);}
    private void remember(Class<?> type,String name,Object value)throws Exception{Field f=field(type,name);saved.put(f,f.get(null));f.set(null,value);}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static final class TestGame extends Game{TestGame(Scene current){super(Scene.class,Game.platform);scene=current;requestedReset=false;}}
}
