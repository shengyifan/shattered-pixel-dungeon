package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.game.QuitSessionHarness;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** The controller talks to the real session reader, not prearranged receipt replies. */
public class QuitControllerSessionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void asyncQuitExposesPendingThenSettlesActualReceiptAndWaitsForExit()throws Exception{
        try(QuitSessionHarness session=new QuitSessionHarness(temporary.newFolder().toPath())){
            SessionTransport transport=new SessionTransport(session);
            StableController controller=new StableController(transport,1000);
            Map<String,Object> hello=controller.handshake();
            Map<String,Object> initial=controller.accept(map("op","quit","rev",hello.get("rev")));
            assertEquals("in_progress",initial.get("st"));assertTrue(session.reader.isAlive());
            Map<String,Object> pending=controller.accept(map("op","settle","timeout_ms",1000));
            // A bounded settle may have sent its next receipt just before the deadline.
            // That query stays in flight and must resume after completion, without replay.
            if(!"pending".equals(pending.get("st")))assertEquals(pending.toString(),"RESPONSE_TIMEOUT",pending.get("err"));
            assertEquals("EXECUTING",body(object(pending.get("outcome"))).get("st"));
            assertEquals(0,transport.exitWaits);
            session.game.completeQuit();
            Map<String,Object> complete=controller.accept(map("op","settle"));
            assertEquals("completed",complete.get("st"));assertEquals(0,complete.get("exit_code"));
            assertEquals("COMPLETED",body(object(complete.get("outcome"))).get("st"));
            assertEquals(1,((List<?>)body(object(complete.get("outcome"))).get("save")).size());
            assertFalse(complete.containsKey("observation"));assertFalse(complete.containsKey("discovery"));
            assertEquals("CHILD_EXIT_PENDING",controller.accept(map("op","state")).get("err"));
            assertEquals(1,session.game.executions.get());assertEquals(1,session.game.exits.get());
            assertEquals(1,transport.exitWaits);session.awaitReader();
            for(Map<String,Object> request:transport.sent.subList(2,transport.sent.size())){
                assertEquals("req",request.get("op"));assertEquals(initial.get("id"),request.get("rid"));
            }
            assertEquals(transport.sent.size(),session.wire.recorded.size());
        }
    }

    @Test public void actualUnknownQuitReceiptPreservesOriginalOutcomeAndBlocksReplay()throws Exception{
        try(QuitSessionHarness session=new QuitSessionHarness(temporary.newFolder().toPath())){
            SessionTransport transport=new SessionTransport(session);
            StableController controller=new StableController(transport,1000);
            Map<String,Object> hello=controller.handshake();
            Map<String,Object> initial=controller.accept(map("op","quit","rev",hello.get("rev")));
            session.game.failQuit();
            Map<String,Object> unknown=controller.accept(map("op","settle","rid",initial.get("id")));
            assertEquals("ACTION_UNKNOWN",unknown.get("err"));
            assertEquals("UNKNOWN",body(object(unknown.get("outcome"))).get("st"));
            assertEquals(0,transport.exitWaits);assertEquals(0,session.game.exits.get());
            assertEquals("OUTCOME_PENDING",controller.accept(map("op","quit","rev",hello.get("rev"))).get("err"));
            assertEquals(1,session.game.executions.get());assertTrue(session.reader.isAlive());
        }
    }

    @Test public void synchronousQuitAlsoUsesActualReaderWithoutPostQuitQuery()throws Exception{
        try(QuitSessionHarness session=new QuitSessionHarness(temporary.newFolder().toPath())){
            SessionTransport transport=new SessionTransport(session);session.game.immediate=true;
            StableController controller=new StableController(transport,1000);
            Map<String,Object> hello=controller.handshake();
            assertEquals("completed",controller.accept(map("op","quit","rev",hello.get("rev"))).get("st"));
            assertEquals(2,transport.sent.size());assertEquals(1,transport.exitWaits);session.awaitReader();
        }
    }

    private static final class SessionTransport implements StableController.Transport {
        final QuitSessionHarness session;
        final List<Map<String,Object>> sent=new ArrayList<>();int exitWaits;
        SessionTransport(QuitSessionHarness session){this.session=session;}
        public void send(String frame)throws IOException{sent.add(JsonCodec.decode(frame.trim()));session.sendRaw(frame);}
        public StableController.Frame receive(long timeout)throws InterruptedException{
            String frame=session.wire.frames.poll(timeout,TimeUnit.MILLISECONDS);
            return frame==null?null:new StableController.Frame(frame,null,false);
        }
        public Integer awaitExit(long timeout)throws InterruptedException{
            exitWaits++;
            try{session.game.exited.get(timeout,TimeUnit.MILLISECONDS);return session.session.failed()?1:0;}
            catch(TimeoutException waiting){return null;}
            catch(ExecutionException impossible){throw new AssertionError(impossible);}
        }
        public void closeInput()throws IOException{session.endInput();}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    private static Map<String,Object> body(Map<String,Object> response){return object(response.get("data"));}
}
