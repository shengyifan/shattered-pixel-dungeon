package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class QuitLifecycleTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void pendingQuitKeepsReaderOpenAndExitsOnlyAfterItsDurableTerminalReceipt()throws Exception{
        try(QuitSessionHarness h=harness()){
            Map<String,Object> initial=h.request("quit","action.execute",map("action","app.quit"));
            assertEquals("in_progress",initial.get("st"));assertTrue(h.reader.isAlive());
            assertEquals("EXECUTING",body(h.request("poll","request.get",map("target_id","quit"))).get("st"));
            assertEquals(0,h.game.exits.get());
            h.game.completeQuit();
            assertNull(h.wire.frames.poll(100,TimeUnit.MILLISECONDS));
            h.game.beforeExit=()->{
                Map<String,Object> exchange=h.store.history("run:quit",0,100).get(2);
                assertEquals(Boolean.TRUE,exchange.get("output_succeeded"));
                assertEquals("COMPLETED",h.store.getRequest("run:quit","quit").get("status"));
                assertEquals(3,h.wire.recorded.size());
            };
            Map<String,Object> terminal=h.request("terminal","request.get",map("target_id","quit"));
            assertEquals("COMPLETED",body(terminal).get("st"));
            assertEquals(1,((List<?>)body(terminal).get("save")).size());
            h.awaitReader();assertEquals(1,h.game.exits.get());assertEquals(1,h.game.executions.get());
            Map<String,Object> historical=h.store.getRequest("run:quit","quit",Collections.singleton("raw"));
            assertEquals("in_progress",JsonCodec.decode((String)((Map<?,?>)historical.get("raw")).get("response")).get("st"));
            assertEquals(3,h.wire.recorded.size());assertFalse(h.session.failed());
        }
    }

    @Test public void unrelatedQueriesAndDuplicateQuitCannotConsumeTheQuitDelivery()throws Exception{
        try(QuitSessionHarness h=harness()){
            h.request("other","state.get",map());
            h.request("quit","action.execute",map("action","app.quit"));h.game.completeQuit();
            for(String op:Arrays.asList("protocol.info","state.get","history.list","events.read")){
                assertFalse(h.request(op,op,map()).containsKey("err"));assertEquals(0,h.game.exits.get());
            }
            assertEquals("COMPLETED",body(h.request("other-receipt","request.get",map("target_id","other"))).get("st"));
            assertEquals("DUPLICATE_REQUEST_ID",h.request("quit","action.execute",map("action","app.quit")).get("err"));
            assertEquals("SESSION_CLOSING",h.request("new-quit","action.execute",map("action","app.quit")).get("err"));
            h.sendRaw("{invalid}\n");assertTrue(h.receive().containsKey("err"));assertEquals(0,h.game.exits.get());
            h.store.ensureScope("run:other","run","other");
            AuditStore.Attempt unrelated=h.store.begin("run:other","quit","action.execute","{}");
            h.store.complete(unrelated,"COMPLETED",map("id","quit","st","completed"),null,null,null);
            h.sendRaw(V7Requests.encode(h.store,map("protocol_version",7,"scope_id","run:other","id","wrong-scope",
                    "op","request.get","args",map("target_id","quit")))+"\n");
            assertEquals("COMPLETED",body(h.receive()).get("st"));assertEquals(0,h.game.exits.get());
            assertEquals("COMPLETED",body(h.request("final","request.get",map("target_id","quit","get",Arrays.asList("reply","raw")))).get("st"));
            h.awaitReader();assertEquals(1,h.game.executions.get());assertEquals(1,h.game.exits.get());
        }
    }

    @Test public void synchronousQuitFlushesOnceAndNeedsNoReceipt()throws Exception{
        try(QuitSessionHarness h=harness()){
            h.game.immediate=true;
            assertEquals("completed",h.request("quit","action.execute",map("action","app.quit")).get("st"));
            h.awaitReader();assertEquals(1,h.game.exits.get());assertEquals(1,h.wire.recorded.size());
        }
    }

    @Test public void delayedUnknownStaysQueryableAndNeverLooksLikeSuccessfulShutdown()throws Exception{
        try(QuitSessionHarness h=harness()){
            h.request("quit","action.execute",map("action","app.quit"));h.game.failQuit();
            Map<String,Object> receipt=h.request("unknown","request.get",map("target_id","quit"));
            assertEquals("UNKNOWN",body(receipt).get("st"));assertEquals("EXECUTION_UNKNOWN",body(receipt).get("err"));
            assertEquals(0,h.game.exits.get());
            assertEquals("execution_unknown",body(h.request("state","state.get",map())).get("phase"));
            assertEquals("EXECUTION_UNCERTAIN",h.request("again","action.execute",map("action","app.quit")).get("err"));
            h.endInput();h.awaitReader();
            assertEquals(1,h.game.executions.get());assertEquals(1,h.game.exits.get());
            assertTrue(events(h).contains("shutdown.unsettled"));assertFalse(events(h).contains("shutdown.completed"));
        }
    }

    @Test public void delayedRejectionReleasesQuitOwnershipWithoutExiting()throws Exception{
        try(QuitSessionHarness h=harness()){
            h.request("quit","action.execute",map("action","app.quit"));h.game.rejectQuit();
            assertEquals("REJECTED",body(h.request("rejected","request.get",map("target_id","quit"))).get("st"));
            assertEquals("completed",h.request("wait","action.execute",map("action","wait")).get("st"));
            assertEquals(0,h.game.exits.get());assertEquals(2,h.game.executions.get());
            h.game.synchronousFailure=QuitSessionHarness.Game.rejection();
        }
    }

    @Test public void synchronousRejectionDoesNotReserveQuitOwnership()throws Exception{
        try(QuitSessionHarness h=harness()){
            h.game.synchronousFailure=QuitSessionHarness.Game.rejection();
            assertEquals("STALE_STATE",h.request("quit","action.execute",map("action","app.quit")).get("err"));
            h.game.synchronousFailure=null;
            assertEquals("completed",h.request("wait","action.execute",map("action","wait")).get("st"));
            assertEquals(0,h.game.exits.get());h.game.synchronousFailure=QuitSessionHarness.Game.rejection();
        }
    }

    @Test public void failedInitialAndTerminalOutputCloseOnceWithoutChangingDurableOutcome()throws Exception{
        for(boolean asynchronous:Arrays.asList(false,true))try(QuitSessionHarness h=harness()){
            if(asynchronous){h.request("quit","action.execute",map("action","app.quit"));h.game.completeQuit();}
            else h.game.immediate=true;
            h.wire.fail=true;
            h.send(asynchronous?"receipt":"quit",asynchronous?"request.get":"action.execute",
                    asynchronous?map("target_id","quit"):map("action","app.quit"));
            h.awaitReader();assertTrue(h.session.failed());assertEquals(1,h.game.exits.get());
            assertEquals("COMPLETED",h.store.getRequest("run:quit","quit").get("status"));
            assertEquals(asynchronous?1:0,h.wire.recorded.size());assertEquals(1,h.game.executions.get());
        }
    }

    @Test public void failedReceiptDeliveryAuditFailsSessionWithoutRewritingSuccessfulQuit()throws Exception{
        try(QuitSessionHarness h=harness()){
            h.request("quit","action.execute",map("action","app.quit"));h.game.completeQuit();
            // Fence async settlement before changing this fixture's SQLite schema.
            // Otherwise the external DDL can fault an unrelated in-flight settlement.
            assertFalse(h.request("settled-fence","protocol.info",map()).containsKey("err"));
            assertEquals("COMPLETED",h.store.getRequest("run:quit","quit").get("status"));
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+h.store.internalDatabase());Statement statement=db.createStatement()){
                statement.execute("CREATE TRIGGER fail_output BEFORE UPDATE OF output_attempted ON exchanges "
                        +"WHEN NEW.id='receipt' BEGIN SELECT RAISE(ABORT,'fixture output audit failure'); END");
            }
            assertEquals("COMPLETED",body(h.request("receipt","request.get",map("target_id","quit"))).get("st"));
            h.awaitReader();assertTrue(h.session.failed());assertEquals(1,h.game.exits.get());
            assertEquals("COMPLETED",h.store.getRequest("run:quit","quit").get("status"));
            assertEquals(3,h.wire.recorded.size());assertEquals(1,h.game.executions.get());
        }
    }

    @Test public void externalCloseCannotTerminateAnUnrelatedPendingOrUnknownAction()throws Exception{
        try(QuitSessionHarness h=harness()){
            h.game.delayWait=true;h.game.exiting=true;
            assertEquals("in_progress",h.request("wait","action.execute",map("action","wait")).get("st"));
            assertEquals("EXECUTING",body(h.request("pending","request.get",map("target_id","wait"))).get("st"));
            assertEquals(0,h.game.exits.get());
            h.game.failQuit();
            assertEquals("UNKNOWN",body(h.request("unknown","request.get",map("target_id","wait"))).get("st"));
            assertEquals(0,h.game.exits.get());
            h.endInput();h.awaitReader();assertEquals(1,h.game.executions.get());assertEquals(1,h.game.exits.get());
        }
    }

    @Test public void eofAfterCompletionBeforeReceiptDisposesWithoutAnotherQuitOrResponse()throws Exception{
        try(QuitSessionHarness h=harness()){
            h.request("quit","action.execute",map("action","app.quit"));h.game.completeQuit();
            h.endInput();h.awaitReader();
            assertEquals(1,h.game.executions.get());assertEquals(1,h.game.exits.get());assertEquals(1,h.wire.recorded.size());
            assertEquals("COMPLETED",h.store.getRequest("run:quit","quit").get("status"));
            assertTrue(events(h).contains("shutdown.completed"));assertFalse(events(h).contains("shutdown.dispatch"));
        }
    }

    @Test public void eofWhileQuitIsUnsettledNeverDispatchesItAgain()throws Exception{
        try(QuitSessionHarness h=harness()){
            h.request("quit","action.execute",map("action","app.quit"));h.endInput();h.awaitReader();
            assertEquals("EXECUTING",h.store.getRequest("run:quit","quit").get("status"));
            assertEquals(1,h.game.executions.get());assertEquals(1,h.game.exits.get());assertEquals(1,h.wire.recorded.size());
            assertTrue(events(h).contains("shutdown.unsettled"));assertFalse(events(h).contains("shutdown.dispatch"));
        }
    }

    private QuitSessionHarness harness()throws Exception{return new QuitSessionHarness(temporary.newFolder().toPath());}
    @SuppressWarnings("unchecked") private static Map<String,Object> body(Map<String,Object> response){return (Map<String,Object>)response.get("data");}
    private static List<String> events(QuitSessionHarness h){
        List<String> kinds=new ArrayList<>();for(Map<String,Object> row:h.store.events("run:quit",0,100))kinds.add((String)row.get("kind"));return kinds;
    }
}
