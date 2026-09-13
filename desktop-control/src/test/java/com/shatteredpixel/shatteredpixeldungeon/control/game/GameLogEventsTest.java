package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.VisualCue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class GameLogEventsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void eventsReadDrainsDisplayedSnapshotsBeforeItsResponseAndConsoleRemainsPrivate()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            store.ensureScope("run:a","run","a");store.ensureScope("run:old","run","old");store.beginSession("log-test","fixture-build","CLI.3.0.0",3);
            FakeGame game=new FakeGame();ByteArrayOutputStream wire=new ByteArrayOutputStream();
            game.logs.add(snapshot("run:old",literal("old visible")));game.logs.add(snapshot("run:a",literal("current visible")));
            OutputStream checked=new OutputStream(){
                boolean first=true;
                @Override public void write(int value)throws IOException{
                    if(first){first=false;
                        for(java.nio.file.Path path:List.of(store.publicDatabase(),store.internalDatabase()))
                            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM events WHERE kind='game.log'")){
                                assertTrue(r.next());assertEquals(2,r.getInt(1));
                            }catch(SQLException error){throw new IOException(error);}
                    }wire.write(value);
                }
            };
            try(MachineSession session=new MachineSession(store,game,new PrintStream(checked,true,"UTF-8"),1000)){
                session.recordLog("stderr","PRIVATE_CONSOLE_SENTINEL");
                session.accept(V3Requests.encode(map("protocol_version", 3, "scope_id","run:a","id","logs","op","events.read"))).get(5,TimeUnit.SECONDS);
                Map<String,Object> response=last(wire);
                List<?> events=(List<?>)((Map<?,?>)response.get("data")).get("items");assertEquals(1,events.size());
                assertTrue(JsonCodec.encode(events).contains("current visible"));assertFalse(JsonCodec.encode(events).contains("old visible"));
                assertFalse(wire.toString("UTF-8").contains("PRIVATE_CONSOLE_SENTINEL"));
                session.accept(V3Requests.encode(map("protocol_version", 3, "scope_id","run:a","id","state","op","state.get"))).get(5,TimeUnit.SECONDS);
                assertEquals("v1",last(wire).get("rev"));
                session.accept(V3Requests.encode(map("protocol_version", 3, "scope_id","run:old","id","old","op","events.read"))).get(5,TimeUnit.SECONDS);
                assertTrue(JsonCodec.encode(last(wire).get("data")).contains("old visible"));
                assertEquals(3,wire.toString("UTF-8").lines().count());
                assertEquals(1,store.events("run:a",0,100).size());
                assertEquals(1,store.events("run:old",0,100).size());
            }
        }
    }

    @Test public void handshakePublishesBuildSessionAndAuditSchemaMetadata()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            String sessionId=store.beginSession("handshake-test","fixture-build","CLI.3.0.0",3);ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,new FakeGame(),new PrintStream(wire,true,"UTF-8"),1000)){
                session.accept(V3Requests.encode(map("protocol_version", 3, "id","hello","op","protocol.info"))).get(5,TimeUnit.SECONDS);
                Map<?,?> result=(Map<?,?>)last(wire).get("data");
                assertEquals(sessionId,result.get("session_id"));assertEquals(6,((Number)result.get("audit_schema_version")).intValue());
                assertEquals(BuildCatalog.current().get("build_id"),result.get("build_id"));assertNotNull(result.get("build_id"));
            }
        }
    }
    @Test public void handshakeKeepsAbsentSessionExplicitlyNull()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,new FakeGame(),new PrintStream(wire,true,"UTF-8"),1000)){
                session.accept(V3Requests.encode(map("protocol_version", 3, "id","hello","op","protocol.info"))).get(5,TimeUnit.SECONDS);
                Map<?,?> result=(Map<?,?>)last(wire).get("data");assertTrue(result.containsKey("session_id"));assertNull(result.get("session_id"));
            }
        }
    }
    @Test public void consecutiveSourcelessDisplaysRemainPartialEventsWithoutClosingTheSession()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())) {
            store.ensureScope("run:a","run","a");store.beginSession("translation-failure","fixture-build","CLI.3.0.0",3);
            FakeGame game=new FakeGame();ByteArrayOutputStream wire=new ByteArrayOutputStream();
            game.logs.add(snapshot("run:a","未收录的第一条实际显示甲"));
            game.logs.add(snapshot("run:a","未收录的第二条实际显示乙"));
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,"UTF-8"),1000)) {
                session.accept(V3Requests.encode(map("protocol_version", 3, "scope_id","run:a","id","display-query","op","events.read"))).get(5,TimeUnit.SECONDS);
                assertFalse(last(wire).containsKey("err"));
                assertEquals("partial",((Map<?,?>)last(wire).get("pres")).get("st"));
                assertEquals(1,wire.toString("UTF-8").lines().count());
                assertFalse(wire.toString("UTF-8").contains("未收录"));
                assertEquals("COMPLETED",store.getRequest("run:a","display-query").get("status"));
                assertEquals(0,game.exits);assertFalse(session.failed());
                assertEquals(2,store.events("run:a",0,100).size());
                session.accept(V3Requests.encode(map("protocol_version",3,"scope_id","run:a","id","after-display","op","state.get"))).get(5,TimeUnit.SECONDS);
                assertFalse(last(wire).containsKey("err"));
                try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+store.internalDatabase());Statement statement=db.createStatement()) {
                    try(ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM logs WHERE channel='displayed_text_original'")) {
                        assertTrue(rows.next());assertEquals(2,rows.getInt(1));
                    }
                    try(ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM exceptions")) {
                        assertTrue(rows.next());assertEquals(0,rows.getInt(1));
                    }
                }
            }
        }
    }
    @Test public void visualHistoryRetainsDisappearedCuesAndNeverSerializesTheLevelIdentity()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            store.ensureScope("run:a","run","a");FakeGame game=new FakeGame();ByteArrayOutputStream wire=new ByteArrayOutputStream();
            Object identity=new Object(){@Override public String toString(){throw new AssertionError("Private identity must never be serialized");}};
            game.visuals.add(new GameController.VisualSnapshot("a",identity,1,"opaque-map",1,"2026-09-09T01:00:00Z",List.of(new VisualCue("red_target",10))));
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,"UTF-8"),1000)){
                session.accept(V3Requests.encode(map("protocol_version", 3, "scope_id","run:a","id","first","op","events.read"))).get(5,TimeUnit.SECONDS);
                game.visuals.add(new GameController.VisualSnapshot("a",identity,1,"opaque-map",2,"2026-09-09T01:00:01Z",List.of()));
                session.accept(V3Requests.encode(map("protocol_version", 3, "scope_id","run:a","id","second","op","events.read"))).get(5,TimeUnit.SECONDS);
                List<?> events=(List<?>)((Map<?,?>)last(wire).get("data")).get("items");assertEquals(2,events.size());
                Map<?,?> first=(Map<?,?>)((Map<?,?>)events.get(0)).get("data"),second=(Map<?,?>)((Map<?,?>)events.get(1)).get("data");
                assertEquals(1,((List<?>)first.get("cues")).size());assertTrue(((List<?>)second.get("cues")).isEmpty());
                assertEquals("opaque-map",second.get("map_context"));assertFalse(second.containsKey("generation"));assertFalse(second.containsKey("presentationReady"));
                assertEquals(2,wire.toString("UTF-8").lines().count());
            }
        }
    }
    @Test public void logCallbackLanguageIsFrozenWithItsPublicAndOriginalDisplayData()throws Exception{
        String text=literal("Drawn log");
        List<RuntimeObserver.LogEntry> entries=List.of(new RuntimeObserver.LogEntry(text,0xffffff));
        GameController.GameLogSnapshot first=new GameController.GameLogSnapshot("run:a","2026-09-13T00:00:00Z",entries,"zh");
        GameController.GameLogSnapshot later=new GameController.GameLogSnapshot("run:a","2026-09-13T00:00:01Z",entries,"ko");
        assertEquals("zh",first.data().get("gui_language"));assertEquals("zh",first.originalData().get("gui_language"));
        assertEquals("ko",later.data().get("gui_language"));assertEquals("ko",later.originalData().get("gui_language"));
        assertEquals("zh",PublicEnglishProjection.copy(first.data()).get("gui_language"));
        assertThrows(UnsupportedOperationException.class,()->first.data().put("gui_language","changed"));
    }
    private static String literal(String text){return com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance.INSTANCE.onTextOperation("literal",text,text);}
    private static GameController.GameLogSnapshot snapshot(String scope,String text){return new GameController.GameLogSnapshot(scope,"2026-09-09T01:00:00Z",List.of(new RuntimeObserver.LogEntry(text,0xffffff)));}
    private static Map<String,Object> last(ByteArrayOutputStream wire)throws Exception{String[] rows=wire.toString("UTF-8").strip().split("\\R");return JsonCodec.decode(rows[rows.length-1]);}
    private static final class FakeGame implements MachineSession.GamePort{
        int exits;
        final Queue<GameController.GameLogSnapshot> logs=new ArrayDeque<>();
        final Queue<GameController.VisualSnapshot> visuals=new ArrayDeque<>();
        final GameController.State state=new GameController.State("run:a","v1","player_ready",map("visible",true),map("private",true),Collections.emptyList());
        public GameController.State latest(){return state;}
        public CompletableFuture<GameController.State> observe(){return CompletableFuture.completedFuture(state);}
        public CompletableFuture<GameController.State> execute(String version,Map<String,Object> args){throw new AssertionError("No action may be dispatched by a query");}
        public void prepareRun(String id){throw new AssertionError("No run may be prepared by a query");}
        public GameController.SaveResult pollSave(){return null;}
        public GameController.GameLogSnapshot pollGameLog(){return logs.poll();}
        public GameController.VisualSnapshot pollVisual(){return visuals.poll();}
        public boolean exiting(){return false;}
        public boolean disposed(){return false;}
        public void exitNow(){exits++;}
    }
}
