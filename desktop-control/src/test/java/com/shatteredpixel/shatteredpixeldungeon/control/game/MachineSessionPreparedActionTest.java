package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Exercises real paired SQLite audit around a controlled preparation boundary. */
public class MachineSessionPreparedActionTest {
    private static final String SCOPE="run:prepared";
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void ordinaryActionAuditsThePreparedBoundaryWithoutASeparateObservation() throws Exception {
        try(Harness h=harness()) {
            h.game.cached=state(SCOPE,"previous:1","old cached observation");
            h.game.boundary=state(SCOPE,"current:2","prepared boundary");
            h.game.beforeCommit=prepared->{
                assertEquals(0,h.game.observations);
                assertEquals("current:2",prepared.version);
                assertEquals(h.game.boundary.publicState,h.store.getRequest(SCOPE,"act").get("before_snapshot"));
                assertPairedExecuting(h.store,"act");
            };
            h.action("act","current:2");
            assertFalse(h.last().containsKey("err"));
            assertEquals(1,h.game.preparations);
            assertEquals(1,h.game.commits);
            assertEquals(1,h.game.callbacks);
            assertEquals(0,h.game.observations);
            assertEquals(0,h.game.revocations);
        }
    }

    @Test public void pairedBeforeAuditFailureRevokesPreparationWithoutEnteringAnAction() throws Exception {
        try(Harness h=harness()) {
            try(Connection db=connect(h.store.internalDatabase());Statement sql=db.createStatement()) {
                sql.execute("CREATE TRIGGER reject_prepared_intent BEFORE UPDATE ON requests "
                        +"WHEN NEW.status='EXECUTING' BEGIN SELECT RAISE(ABORT,'fixture intent failure'); END");
            }
            h.action("audit-fails","current:1");
            assertEquals("AUDIT_UNAVAILABLE",h.last().get("err"));
            assertEquals(0,h.game.commits);
            assertEquals(0,h.game.callbacks);
            assertEquals(1,h.game.revocations);
            assertTrue(h.session.failed());
            for(Path database:List.of(h.store.publicDatabase(),h.store.internalDatabase())) {
                try(Connection db=connect(database);Statement sql=db.createStatement();
                    ResultSet row=sql.executeQuery("SELECT status,before_snapshot FROM requests WHERE id='audit-fails'")) {
                    assertTrue(row.next());
                    assertEquals("RECEIVED",row.getString("status"));
                    assertNull(row.getString("before_snapshot"));
                }
            }
        }
    }

    @Test public void preparationDeadlineRevokesTheTokenAndLateReadinessCannotCommitIt() throws Exception {
        try(Harness h=harness()) {
            h.game.delayPreparation=true;
            h.action("preparation-deadline","current:1");
            assertEquals("STATE_UNAVAILABLE",h.last().get("err"));
            assertEquals("REJECTED",h.store.getRequest(SCOPE,"preparation-deadline").get("status"));
            GameController.ActionPreparation late=h.game.lastPreparation;
            assertEquals(1,h.game.revocations);
            assertEquals(0,h.game.commits);
            assertTrue(late.preparedBefore.complete(h.game.boundary));
            assertEquals(0,h.game.callbacks);
            h.game.delayPreparation=false;
            h.action("fresh-after-deadline","current:1");
            assertFalse(h.last().containsKey("err"));
            assertEquals(1,h.game.callbacks);
            assertEquals(1,h.game.commits);
            assertEquals("REJECTED",h.store.getRequest(SCOPE,"preparation-deadline").get("status"));
        }
    }

    @Test public void aChangedCommitGuardIsRejectedWithoutAnActionAndDoesNotBlockTheNextDecision() throws Exception {
        try(Harness h=harness()) {
            h.game.guardFailure=new GameController.NotExecuted("STALE_STATE",null);
            h.game.beforeCommit=prepared->assertPairedExecuting(h.store,prepared.requestId);
            h.action("invalidated","current:1");
            assertEquals("STALE_STATE",h.last().get("err"));
            assertEquals("REJECTED",h.store.getRequest(SCOPE,"invalidated").get("status"));
            assertEquals(0,h.game.callbacks);
            assertEquals(1,h.game.revocations);
            h.game.guardFailure=null;
            h.game.boundary=state(SCOPE,"current:2","new decision");
            h.action("fresh","current:2");
            assertFalse(h.last().containsKey("err"));
            assertEquals(1,h.game.callbacks);
            assertFalse(h.session.failed());
        }
    }

    @Test public void committedResponseDeadlineKeepsTheOriginalExecutionUntilItsReceiptCompletes() throws Exception {
        try(Harness h=harness()) {
            h.game.delayCompletion=true;
            h.action("committed","current:1");
            assertEquals("in_progress",h.last().get("st"));
            assertEquals(1,h.game.commits);
            assertEquals(1,h.game.callbacks);
            assertEquals(0,h.game.revocations);
            GameController.ActionPreparation original=h.game.lastPreparation;
            h.action("must-not-dispatch","current:1");
            assertEquals("BUSY",h.last().get("err"));
            assertEquals(1,h.game.preparations);
            h.game.cached=state(SCOPE,"current:2","completed original action");
            original.execution.firstResponse.complete(h.game.cached);
            original.execution.completion.complete(h.game.cached);
            h.query("receipt","request.get",map("target_id","committed"));
            assertEquals("COMPLETED",((Map<?,?>)h.last().get("data")).get("st"));
            assertEquals("COMPLETED",h.store.getRequest(SCOPE,"committed").get("status"));
            for(Path database:List.of(h.store.publicDatabase(),h.store.internalDatabase())) {
                try(Connection db=connect(database);Statement sql=db.createStatement();
                    ResultSet row=sql.executeQuery("SELECT response_json FROM exchanges WHERE id='committed'")) {
                    assertTrue(row.next());assertEquals("in_progress",JsonCodec.decode(row.getString(1)).get("st"));
                }
            }
            assertEquals(1,h.game.commits);
            assertEquals(1,h.game.callbacks);
            assertEquals(0,h.game.revocations);
        }
    }

    @Test public void runtimeScopeAndRevisionRejectionNeverEnterTheAuditExecutingPhase() throws Exception {
        for(String rejection:List.of("SCOPE_MISMATCH","STALE_STATE")) {
            try(Harness h=harness()) {
                h.game.preparationFailure=new GameController.NotExecuted(rejection,null);
                h.action("rejected","current:1");
                assertEquals(rejection,h.last().get("err"));
                assertEquals("REJECTED",h.store.getRequest(SCOPE,"rejected").get("status"));
                assertEquals(0,h.game.commits);
                assertEquals(0,h.game.callbacks);
                assertEquals(0,h.game.observations);
                for(Path database:List.of(h.store.publicDatabase(),h.store.internalDatabase())) {
                    try(Connection db=connect(database);Statement sql=db.createStatement();
                        ResultSet row=sql.executeQuery("SELECT started_at FROM requests WHERE id='rejected'")) {
                        assertTrue(row.next());assertNull(row.getString(1));
                    }
                }
            }
        }
    }

    private Harness harness() throws Exception {return new Harness(temporary.newFolder().toPath());}
    private static Connection connect(Path database) throws Exception {return DriverManager.getConnection("jdbc:sqlite:"+database);}
    private static void assertPairedExecuting(AuditStore store,String id) {
        List<String> snapshots=new ArrayList<>();
        for(Path database:List.of(store.publicDatabase(),store.internalDatabase())) {
            try(Connection db=connect(database);java.sql.PreparedStatement sql=db.prepareStatement(
                    "SELECT status,before_snapshot FROM requests WHERE scope_id=? AND id=?")) {
                sql.setString(1,SCOPE);sql.setString(2,id);
                try(ResultSet row=sql.executeQuery()) {
                    assertTrue(row.next());assertEquals("EXECUTING",row.getString("status"));
                    assertNotNull(row.getString("before_snapshot"));snapshots.add(row.getString("before_snapshot"));
                }
            } catch(Exception failure) {throw new AssertionError("Prepared intent was not durable before commit",failure);}
        }
        assertEquals(snapshots.get(0),snapshots.get(1));
    }
    private static GameController.State state(String scope,String version,String marker) {
        return new GameController.State(scope,version,"player_ready",map("fixture",marker),map("private_fixture",marker),
                Collections.singletonList(map("action","wait")));
    }
    private static final class Fake implements PreparedTestGamePort {
        GameController.State cached=state(SCOPE,"current:1","cached"),boundary=state(SCOPE,"current:1","prepared");
        GameController.ActionPreparation lastPreparation;
        final Map<GameController.ActionPreparation,Boolean> committed=new IdentityHashMap<>();
        boolean delayPreparation,delayCompletion,exited;
        GameController.NotExecuted preparationFailure,guardFailure;
        Consumer<GameController.ActionPreparation> beforeCommit=ignored->{};
        int observations,preparations,commits,callbacks,revocations;
        @Override public GameController.ActionPreparation prepareAction(String scope,String version,Map<String,Object> args,String id,String plannedRun) {
            preparations++;
            CompletableFuture<GameController.State> prepared=new CompletableFuture<>();
            GameController.Execution execution=new GameController.Execution(new CompletableFuture<>(),new CompletableFuture<>());
            lastPreparation=new GameController.ActionPreparation(scope,version,args,id,plannedRun,prepared,execution);
            committed.put(lastPreparation,false);
            if(preparationFailure!=null)prepared.completeExceptionally(preparationFailure);
            else if(!delayPreparation)prepared.complete(boundary);
            return lastPreparation;
        }
        @Override public GameController.Execution commitPreparedAction(GameController.ActionPreparation prepared) {
            if(!Boolean.FALSE.equals(committed.get(prepared)))throw new AssertionError("Preparation committed twice or after revocation");
            commits++;beforeCommit.accept(prepared);
            if(guardFailure!=null)throw guardFailure;
            committed.put(prepared,true);callbacks++;
            if(!delayCompletion) {
                cached=boundary;prepared.execution.firstResponse.complete(cached);prepared.execution.completion.complete(cached);
            }
            return prepared.execution;
        }
        @Override public void abortPreparedAction(GameController.ActionPreparation prepared) {
            if(Boolean.FALSE.equals(committed.get(prepared))) {committed.remove(prepared);revocations++;}
        }
        @Override public GameController.State latest(){return cached;}
        @Override public CompletableFuture<GameController.State> observe(){observations++;return CompletableFuture.completedFuture(cached);}
        @Override public CompletableFuture<GameController.State> execute(String version,Map<String,Object> args){throw new AssertionError("Session bypassed preparation");}
        @Override public void prepareRun(String id){throw new AssertionError("Run identity must belong to preparation");}
        @Override public GameController.SaveResult pollSave(){return null;}
        @Override public boolean exiting(){return false;}
        @Override public boolean disposed(){return exited;}
        @Override public void exitNow(){exited=true;}
    }
    private static final class Harness implements AutoCloseable {
        final AuditStore store;
        final Fake game=new Fake();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final MachineSession session;
        Harness(Path directory) throws Exception {
            store=new AuditStore(directory);store.ensureScope(SCOPE,"run","prepared");
            session=new MachineSession(store,game,new PrintStream(bytes,true,"UTF-8"),30);
        }
        void action(String id,String version) throws Exception {
            send(map("protocol_version",9,"scope_id",SCOPE,"state_version",version,"id",id,"op","action.execute","args",map("action","wait")));
        }
        void query(String id,String operation,Map<String,Object> args) throws Exception {
            send(map("protocol_version",9,"scope_id",SCOPE,"id",id,"op",operation,"args",args));
        }
        void send(Map<String,Object> request) throws Exception {session.accept(V9Requests.encode(store,request)).get(5,TimeUnit.SECONDS);}
        Map<String,Object> last() {
            String[] frames=bytes.toString(StandardCharsets.UTF_8).trim().split("\\R");
            return JsonCodec.decode(frames[frames.length-1]);
        }
        @Override public void close(){session.close();store.close();}
    }
}
