package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.Input;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.watabou.input.InputHandler;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated menu and explicit draw boundaries; never starts an Actor, renderer or personal profile. */
class ActionPreparationTest {
    @TempDir Path profile;
    private Game previousGame;
    private Object previousSceneClass,previousInput;
    private FixtureScene scene;
    private GameController controller;
    private final List<Throwable> reported=new ArrayList<>();

    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup()throws Exception{
        previousGame=Game.instance;previousSceneClass=field(Game.class,"sceneClass").get(null);previousInput=Game.inputHandler;
        Game.inputHandler=null;scene=new FixtureScene();new FixtureGame(scene);
        controller=new GameController(profile,"menu:preparation-fixture",reported::add);
    }
    @AfterEach void restore()throws Exception{
        controller.onDispose();Game.instance=previousGame;
        field(Game.class,"sceneClass").set(null,previousSceneClass);field(Game.class,"inputHandler").set(null,previousInput);
    }

    @Test void preparationFreezesBeforeWithoutInvokingAndCommitDispatchesExactlyOnce()throws Exception{
        GameController.State observed=observe();
        GameController.ActionPreparation preparation=prepare(observed,null);
        tick();GameController.State before=preparation.preparedBefore.join();
        assertEquals(observed.version,before.version);assertEquals(0,scene.backs);
        assertFalse(controller.beforeActorResume(),"Audit waiting holds only the Actor boundary");
        tick();assertEquals(0,scene.backs,"Rendering continues without dispatching an unaudited action");
        assertThrows(UnsupportedOperationException.class,()->before.internalState.put("changed",true));
        assertSame(preparation.execution,controller.commitPreparedAction(preparation));
        controller.abortPreparedAction(preparation); // Runtime owns it after commit.
        controller.commitPreparedAction(preparation);tick();
        assertEquals(1,scene.backs);assertSame(before,controller.latest(),"Commit must not publish another before observation");
        assertFalse(preparation.execution.completion.isDone());
        tick();tick();assertTrue(preparation.execution.completion.isDone());
        controller.commitPreparedAction(preparation);tick();assertEquals(1,scene.backs);
        assertTrue(reported.isEmpty(),reported.toString());
    }

    @Test void abortBeforePreparationCannotBecomeALateLease()throws Exception{
        GameController.State observed=observe();
        GameController.ActionPreparation preparation=prepare(observed,"must-not-leak");
        controller.abortPreparedAction(preparation);
        tick();tick();assertNotExecuted(preparation.preparedBefore,"ACTION_ABORTED");
        assertNotExecuted(preparation.execution.completion,"ACTION_ABORTED");
        assertEquals(0,scene.backs);assertTrue(controller.beforeActorResume());assertNull(controller.newRunIdentity());
        assertSame(preparation.execution,controller.commitPreparedAction(preparation));
        tick();assertEquals(0,scene.backs);
    }

    @Test void abortAfterPreparationReleasesHoldAndLeavesNewRunIdentityUnused()throws Exception{
        GameController.ActionPreparation preparation=prepare(observe(),"unused-run");tick();
        assertFalse(controller.beforeActorResume());controller.abortPreparedAction(preparation);
        assertTrue(controller.beforeActorResume());assertNotExecuted(preparation.execution.completion,"ACTION_ABORTED");
        assertNull(controller.newRunIdentity());assertEquals(0,scene.backs);
    }

    @Test void nativeInputInvalidatesBeforeTheNextStableCheckAndCannotDeadlockActor()throws Exception{
        Game.inputHandler=new InputHandler((Input)Proxy.newProxyInstance(Input.class.getClassLoader(),new Class<?>[]{Input.class},
                (proxy,method,args)->method.getReturnType()==boolean.class?false:method.getReturnType()==int.class?0:null));
        GameController.ActionPreparation preparation=prepare(observe(),null);tick();
        assertFalse(controller.beforeActorResume());
        ((java.util.concurrent.atomic.AtomicLong)field(InputHandler.class,"interactions").get(Game.inputHandler)).incrementAndGet();
        assertTrue(controller.beforeActorResume(),"Input invalidation releases the hold without needing stable()");
        controller.commitPreparedAction(preparation);tick();assertNotExecuted(preparation.execution.completion,"STALE_STATE");
        assertEquals(0,scene.backs);
    }

    @Test void sceneReplacementRejectsEvenWhenTheReplacementHasIdenticalPublicFacts()throws Exception{
        GameController.ActionPreparation preparation=prepare(observe(),null);tick();
        FixtureScene replacement=new FixtureScene();new FixtureGame(replacement);
        assertTrue(controller.beforeActorResume());controller.commitPreparedAction(preparation);
        tick();assertNotExecuted(preparation.execution.completion,"STALE_STATE");
        assertEquals(0,scene.backs);assertEquals(0,replacement.backs);
    }

    @Test void publicControlChangeAfterPreparationFailsFinalGuardWithoutPublishingOrDispatching()throws Exception{
        FixtureButton button=new FixtureButton();scene.add(button);
        GameController.ActionPreparation preparation=prepare(observe(),null);tick();
        GameController.State before=preparation.preparedBefore.join();
        button.active=false;
        controller.commitPreparedAction(preparation);tick();
        assertNotExecuted(preparation.execution.completion,"STALE_STATE");assertEquals(0,scene.backs);
        assertSame(before,controller.latest(),"A rejected final guard is not a newly certified observation");
    }

    @Test void finalGuardNeverCapturesInternalProfileAgain()throws Exception{
        Files.writeString(profile.resolve("settings.json"),"fixture before");
        GameController.ActionPreparation preparation=prepare(observe(),null);tick();
        GameController.State before=preparation.preparedBefore.join();
        String frozen=before.internalState.toString();
        Files.delete(profile.resolve("settings.json"));Files.delete(profile);
        controller.commitPreparedAction(preparation);tick();
        assertEquals(1,scene.backs,"The already audited before is retained; commit uses public facts only");
        assertSame(before,controller.latest());assertEquals(frozen,before.internalState.toString());
        Files.createDirectory(profile);
    }

    @Test void newRunIdentityBelongsOnlyToTheCurrentlyExecutingRequest()throws Exception{
        GameController.ActionPreparation preparation=prepare(observe(),"owned-run");tick();
        assertNull(controller.newRunIdentity());
        controller.commitPreparedAction(preparation);tick();
        assertEquals("owned-run",controller.newRunIdentity());assertNull(controller.newRunIdentity());
        tick();tick();assertNull(controller.newRunIdentity());
        GameController.ActionPreparation aborted=prepare(observe(),"later-run");controller.abortPreparedAction(aborted);
        assertNull(controller.newRunIdentity());
    }

    @Test void callbackFailureAfterDispatchStaysUncertain()throws Exception{
        scene.throwOnBack=true;
        GameController.ActionPreparation preparation=prepare(observe(),null);tick();
        controller.commitPreparedAction(preparation);tick();
        Throwable error=failure(preparation.execution.completion);
        assertFalse(error instanceof GameController.NotExecuted);assertEquals(1,scene.backs);
        assertTrue(controller.beforeActorResume());
    }

    @Test void runtimeClosureReleasesQueuedPreparedAndCommittedButUndispatchedRequests()throws Exception{
        GameController.ActionPreparation prepared=prepare(observe(),null);tick();
        controller.commitPreparedAction(prepared);
        GameController.ActionPreparation queued=prepare(prepared.preparedBefore.join(),null);
        controller.runtimeFailed(new IllegalStateException("fixture shutdown"));
        assertNotExecuted(prepared.execution.completion,"ENGINE_ERROR");assertNotExecuted(queued.preparedBefore,"ENGINE_ERROR");
        assertTrue(controller.beforeActorResume());tick();assertEquals(0,scene.backs);
    }

    @Test void racingCommitAndAbortHaveOneOwnerAndAtMostOneCallback()throws Exception{
        ExecutorService contenders=Executors.newFixedThreadPool(2);
        try{
            for(int i=0;i<12;i++){
                int callbacks=scene.backs;
                GameController.ActionPreparation preparation=prepare(observe(),null);tick();
                assertTrue(preparation.preparedBefore.isDone());
                CountDownLatch start=new CountDownLatch(1);
                Future<?> commit=contenders.submit(()->{await(start);controller.commitPreparedAction(preparation);});
                Future<?> abort=contenders.submit(()->{await(start);controller.abortPreparedAction(preparation);});
                start.countDown();commit.get(2,TimeUnit.SECONDS);abort.get(2,TimeUnit.SECONDS);
                tick();tick();tick();assertTrue(preparation.execution.completion.isDone());
                if(preparation.execution.completion.isCompletedExceptionally()){
                    assertNotExecuted(preparation.execution.completion,"ACTION_ABORTED");assertEquals(callbacks,scene.backs);
                }else assertEquals(callbacks+1,scene.backs);
                controller.commitPreparedAction(preparation);tick();
                assertTrue(scene.backs==callbacks||scene.backs==callbacks+1);
            }
        }finally{contenders.shutdownNow();}
    }

    private static void await(CountDownLatch start){
        try{assertTrue(start.await(2,TimeUnit.SECONDS));}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
    }

    @Test void incorrectScopeAndRevisionCannotEnterTheGame()throws Exception{
        GameController.State observed=observe();
        GameController.ActionPreparation scope=controller.prepareAction("menu:wrong",observed.version,Map.of("action","ui.back"),"scope",null);
        tick();assertNotExecuted(scope.preparedBefore,"SCOPE_MISMATCH");
        GameController.ActionPreparation revision=controller.prepareAction(observed.scopeId,"previous-epoch:1",Map.of("action","ui.back"),"revision",null);
        tick();assertNotExecuted(revision.preparedBefore,"STALE_STATE");assertEquals(0,scene.backs);
    }

    @Test void requestTextIsLiteralAndDeeplyImmutable()throws Exception{
        Map<String,Object> nested=new LinkedHashMap<>();nested.put("label","Literal 用户 label");
        List<Object> nestedList=new ArrayList<>();nestedList.add(nested);
        Map<String,Object> args=new LinkedHashMap<>();args.put("action","ui.text");args.put("text","Exact 用户 text");args.put("fixture",nestedList);
        GameController.ActionPreparation preparation=controller.prepareAction("scope","revision",args,"request","run");
        nested.put("label","changed");nestedList.clear();args.put("text","changed");
        assertEquals("Exact 用户 text",preparation.args.get("text"));
        assertEquals(List.of(Map.of("label","Literal 用户 label")),preparation.args.get("fixture"));
        assertThrows(UnsupportedOperationException.class,()->preparation.args.put("text","mutated"));
        controller.abortPreparedAction(preparation);
    }

    private GameController.State observe(){
        CompletableFuture<GameController.State> result=controller.observe();tick();tick();assertTrue(result.isDone(),reported.toString());
        GameController.State state=result.join();
        Map<?,?> coverage=(Map<?,?>)state.internalState.get("coverage");
        assertEquals("captured_declared_scope",coverage.get("status"),()->"Fixture audit gaps: "+coverage.get("unavailable"));
        return state;
    }
    private GameController.ActionPreparation prepare(GameController.State before,String planned){
        return controller.prepareAction(before.scopeId,before.version,Map.of("action","ui.back"),"fixture-request",planned);
    }
    private void tick(){controller.afterDraw();controller.afterFrame();}
    private static Throwable failure(CompletableFuture<?> future){assertTrue(future.isDone());return assertThrows(CompletionException.class,future::join).getCause();}
    private static void assertNotExecuted(CompletableFuture<?> future,String code){
        Throwable error=failure(future);assertInstanceOf(GameController.NotExecuted.class,error);assertEquals(code,((GameController.NotExecuted)error).code);
    }
    private static Field field(Class<?> type,String name)throws Exception{Field value=type.getDeclaredField(name);value.setAccessible(true);return value;}
    private static class FixtureScene extends Scene {
        int backs;boolean throwOnBack;
        @Override protected void onBackPressed(){backs++;if(throwOnBack)throw new IllegalStateException("fixture callback failed");}
    }
    private static class FixtureButton extends Button {
        @Override protected void createChildren(){}
        @Override protected String hoverText(){return ResourceTextFixture.literal("Existing choice");}
    }
    private static class FixtureGame extends Game {FixtureGame(Scene scene){super(Scene.class,Game.platform);this.scene=scene;requestedReset=false;}}
}
