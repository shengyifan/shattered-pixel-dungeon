package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Simulates a native launch exception while the protocol worker awaits its first frame. */
public class StartupFailureSessionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void originalLaunchFailureIsDurableBeforeCloseAndNeverBecomesAStateTimeout()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            store.beginSession("startup-test","fixture-build","CLI.2.0.0",2);
            WaitingGame game=new WaitingGame();ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,"UTF-8"),30_000)){
                CompletableFuture<Void> request=session.accept(JsonCodec.encode(map("protocol_version",2,"id","first-state",
                        "op","state.get","scope_id",store.menuScope())));
                game.observed.get(2,TimeUnit.SECONDS);
                NullPointerException startup=new NullPointerException("PRIVATE_STARTUP_SENTINEL");
                session.recordRuntimeFailure(startup);
                assertTrue(session.failed());assertFalse(request.isDone());
                try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+store.internalDatabase().toUri()+"?mode=ro");
                    Statement statement=db.createStatement();
                    ResultSet rows=statement.executeQuery("SELECT exception_class,message FROM exceptions")){
                    assertTrue(rows.next());assertEquals(NullPointerException.class.getName(),rows.getString(1));
                    assertEquals("PRIVATE_STARTUP_SENTINEL",rows.getString(2));assertFalse(rows.next());
                }
                game.disposed=true;game.observation.completeExceptionally(startup);
                request.get(2,TimeUnit.SECONDS);
                Map<String,Object> response=JsonCodec.decode(wire.toString("UTF-8").trim());
                assertEquals("ENGINE_ERROR",((Map<?,?>)response.get("error")).get("code"));
                assertFalse(wire.toString("UTF-8").contains("PRIVATE_STARTUP_SENTINEL"));
                assertFalse(wire.toString("UTF-8").contains("STATE_UNAVAILABLE"));
                assertEquals("REJECTED",store.getRequest(store.menuScope(),"first-state").get("status"));
                assertEquals(0,game.executions);
            }
        }
    }

    private static final class WaitingGame implements MachineSession.GamePort{
        final CompletableFuture<Void> observed=new CompletableFuture<>();
        final CompletableFuture<GameController.State> observation=new CompletableFuture<>();
        boolean disposed;int executions;
        public GameController.State latest(){return null;}
        public CompletableFuture<GameController.State> observe(){observed.complete(null);return observation;}
        public CompletableFuture<GameController.State> execute(String version,Map<String,Object> args){executions++;throw new AssertionError("No startup action may run");}
        public void prepareRun(String id){throw new AssertionError("No startup run may be prepared");}
        public GameController.SaveResult pollSave(){return null;}
        public boolean exiting(){return false;}
        public boolean disposed(){return disposed;}
        public void exitNow(){}
    }
}
