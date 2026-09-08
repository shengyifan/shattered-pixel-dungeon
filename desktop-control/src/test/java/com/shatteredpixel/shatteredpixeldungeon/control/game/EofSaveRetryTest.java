package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** EOF has no caller request and must only retry proven pre-dispatch stale rejections. */
public class EofSaveRetryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void oneGuiIntentChangeIsReobservedThenNativeSaveReceiptIsDurable()throws Exception{
        FakeGame game=new FakeGame();game.firstFailure=new GameController.NotExecuted("STALE_STATE",null);
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            store.ensureScope("run:a","run","a");store.beginSession("test-eof");
            ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire),1000)){
                session.read(new ByteArrayInputStream(new byte[0]));
                assertFalse(session.failed());
            }
            assertEquals(Arrays.asList("v1","v2"),game.versions);
            assertEquals(2,game.observations);assertEquals(0,wire.size());assertTrue(game.exited);
            Map<String,Object> saved=store.latestSave("run:a");
            assertNotNull(saved);assertEquals("eof-native-save",saved.get("receipt_id"));
            assertEquals(Boolean.TRUE,saved.get("success"));assertNull(saved.get("origin_request_id"));
            assertTrue(store.history("run:a",0,100).isEmpty());
            List<String> kinds=kinds(store);
            assertEquals(2,Collections.frequency(kinds,"shutdown.dispatch"));
            assertEquals(1,Collections.frequency(kinds,"shutdown.retry"));
            assertTrue(kinds.indexOf("save")<kinds.indexOf("shutdown.completed"));
        }
    }

    @Test public void unknownQuitExecutionIsNeverReplayedEvenIfOneSaveAlreadySucceeded()throws Exception{
        FakeGame game=new FakeGame();game.firstFailure=new IllegalStateException("PRIVATE_PARTIAL_QUIT");game.saveBeforeFailure=true;
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            store.ensureScope("run:a","run","a");
            ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire),1000)){
                session.read(new ByteArrayInputStream(new byte[0]));
            }
            assertEquals(1,game.versions.size());assertEquals(0,wire.size());
            assertEquals(Boolean.TRUE,store.latestSave("run:a").get("success"));
            assertFalse(kinds(store).contains("shutdown.retry"));assertFalse(kinds(store).contains("shutdown.completed"));
            assertTrue(kinds(store).contains("shutdown.failed"));
        }
    }

    @Test public void modalRejectionDoesNotCancelOrConfirmThePrompt()throws Exception{
        FakeGame game=new FakeGame();game.firstFailure=new GameController.NotExecuted("ACTION_UNAVAILABLE",null);
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            store.ensureScope("run:a","run","a");
            ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire),1000)){
                session.read(new ByteArrayInputStream(new byte[0]));
            }
            assertEquals(1,game.versions.size());assertEquals(0,wire.size());assertNull(store.latestSave("run:a"));
            assertFalse(kinds(store).contains("shutdown.retry"));assertTrue(kinds(store).contains("shutdown.failed"));
        }
    }

    private static List<String> kinds(AuditStore store){List<String> result=new ArrayList<>();for(Map<String,Object> event:store.events("run:a",0,100))result.add((String)event.get("kind"));return result;}
    private static final class FakeGame implements MachineSession.GamePort{
        GameController.State state=new GameController.State("run:a","v1","player_ready",map("hero","public"),map("internal",true),Collections.singletonList(map("action","app.quit")));
        final Queue<GameController.SaveResult> saves=new ArrayDeque<>();final List<String> versions=new ArrayList<>();
        Throwable firstFailure;boolean saveBeforeFailure,exiting,exited;int observations;
        public GameController.State latest(){return state;}
        public CompletableFuture<GameController.State> observe(){observations++;return CompletableFuture.completedFuture(state);}
        public CompletableFuture<GameController.State> execute(String version,Map<String,Object> args){
            assertEquals("app.quit",args.get("action"));versions.add(version);
            if(versions.size()==1&&firstFailure!=null){
                state=new GameController.State("run:a","v2","player_ready",state.publicState,state.internalState,state.actions);
                if(saveBeforeFailure)save();
                return CompletableFuture.failedFuture(firstFailure);
            }
            save();exiting=true;return CompletableFuture.completedFuture(state);
        }
        void save(){saves.add(new GameController.SaveResult("eof-native-save","a",1,null,"2026-09-09T00:00:00Z",null,null));}
        public void prepareRun(String id){}
        public GameController.SaveResult pollSave(){return saves.poll();}
        public boolean exiting(){return exiting;}
        public boolean disposed(){return exited;}
        public void exitNow(){exited=true;}
    }
}
