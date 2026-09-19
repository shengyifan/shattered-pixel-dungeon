package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.watabou.noosa.RuntimeObserver;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Protocol-6 failures are separated at dispatch, certified completion and pure presentation boundaries. */
public class Cli6SessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void missingAndOldProtocolCannotObserveOrDispatchEvenTheHandshake() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            store.ensureScope("run:a", "run", "a");
            FakeGame game = new FakeGame(); ByteArrayOutputStream wire = new ByteArrayOutputStream();
            try (MachineSession session = new MachineSession(store, game, new PrintStream(wire, true, "UTF-8"), 1000)) {
                send(store, session, map("id", "missing", "op", "protocol.info"));
                assertEquals("PROTOCOL_VERSION_REQUIRED", error(last(wire)));
                send(store, session, map("protocol_version", 1, "id", "old", "op", "protocol.info"));
                assertEquals("UNSUPPORTED_PROTOCOL", error(last(wire)));
                send(store, session, map("protocol_version", 1, "scope_id", "run:a", "id", "old-action", "op", "action.execute",
                        "state_version", "v1", "args", map("action", "wait")));
                assertEquals("UNSUPPORTED_PROTOCOL", error(last(wire)));
                send(store, session, map("v", 2, "id", "version-two", "op", "info"));
                assertEquals("UNSUPPORTED_PROTOCOL", error(last(wire)));
                send(store, session, map("v", 3, "id", "version-three", "op", "info"));
                assertEquals("UNSUPPORTED_PROTOCOL", error(last(wire)));
                send(store, session, map("v", 4, "id", "version-four", "op", "info"));
                assertEquals("UNSUPPORTED_PROTOCOL", error(last(wire)));
                send(store, session, map("v", 5, "id", "version-five", "op", "info"));
                assertEquals("UNSUPPORTED_PROTOCOL", error(last(wire)));
                session.accept(JsonCodec.encode(map("protocol_version", 2, "id", "legacy-envelope", "op", "protocol.info"))).get(5, TimeUnit.SECONDS);
                assertEquals("PROTOCOL_VERSION_REQUIRED", error(last(wire)));
                send(store, session, map("v", 6, "id", "legacy-args", "s", "run:a", "op", "wait", "rev", "v1", "args", map("action", "wait")));
                assertEquals("INVALID_REQUEST", error(last(wire)));
                assertEquals(0, game.observations); assertEquals(0, game.executions);
                send(store, session, map("protocol_version", 6, "id", "new", "op", "protocol.info"));
                assertFalse(last(wire).containsKey("err"));
                Map<?,?> hello = (Map<?,?>) last(wire).get("data");
                assertEquals(6L, last(wire).get("v")); assertEquals(9L, hello.get("audit_schema_version"));
                assertEquals("CLI.6.0.1", hello.get("cli_version"));
            }
        }
    }

    @Test public void firstRejectedRequestAfterRestartKeepsItsPersistedWireScopeWithoutAHandshake() throws Exception {
        for(String expected : List.of("INVALID_REQUEST","UNKNOWN_REVISION","STALE_STATE")) {
            java.nio.file.Path profile=temporary.newFolder().toPath();
            String scope,stale;
            try(AuditStore original=new AuditStore(profile)) {
                original.ensureScope("run:a","run","a");
                scope=original.publicHandle("scope","run:a");
                stale=original.publicHandle("revision","previous-process:1");
            }
            try(AuditStore store=new AuditStore(profile)) {
                FakeGame game=new FakeGame(); ByteArrayOutputStream wire=new ByteArrayOutputStream();
                Map<String,Object> request=map("v",6,"id","first-rejected","s",scope,"op","wait",
                        "rev","UNKNOWN_REVISION".equals(expected)?"rzz":stale);
                if("INVALID_REQUEST".equals(expected))request.put("args",map("action","wait"));
                String raw=JsonCodec.encode(request);
                try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,"UTF-8"),1000)) {
                    session.accept(raw).get(5,TimeUnit.SECONDS);
                    Map<String,Object> response=last(wire);
                    assertEquals(expected,error(response)); assertEquals(scope,response.get("s"));
                    assertEquals("first-rejected",response.get("id")); assertEquals(0,game.executions);
                    Map<String,Object> ledger=store.getRequest("run:a","first-rejected");
                    assertNotNull(ledger); assertEquals("run:a",ledger.get("scope_id"));
                    assertEquals("REJECTED",ledger.get("status")); assertEquals(raw,ledger.get("raw_request"));
                    assertEquals(response,ledger.get("response"));
                }
            }
        }
    }

    @Test public void certifiedActionWithMissingTextKeepsItsSaveLogsAndAllowsTheNextAction() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            store.ensureScope("run:a", "run", "a"); store.beginSession("cli4-presentation","fixture-build","CLI.6.0.0",6);
            FakeGame game = new FakeGame(); ByteArrayOutputStream wire = new ByteArrayOutputStream();
            try (MachineSession session = new MachineSession(store, game, new PrintStream(wire, true, "UTF-8"), 1000)) {
                send(store, session, request("act", "action.execute", "v1", map("action", "wait")));
                Map<String,Object> response = last(wire);
                assertFalse(response.containsKey("err")); assertEquals("completed", response.get("st"));
                assertEquals("partial", ((Map<?,?>)response.get("pres")).get("st"));
                Map<?,?> result = (Map<?,?>)response.get("data");
                assertEquals("v2", store.resolveHandle("revision", (String)response.get("rev")));
                Map<?,?> persistence = (Map<?,?>)result.get("persistence");
                assertEquals(1, ((List<?>)persistence.get("saves")).size());
                Map<String,Object> recorded = store.getRequest("run:a", "act");
                assertEquals("COMPLETED", recorded.get("status")); assertEquals("partial", recorded.get("presentation_status"));
                assertEquals(response, recorded.get("response")); assertNotNull(recorded.get("after_snapshot"));
                assertEquals(1, store.events("run:a", 0, 100).stream().filter(event -> "game.log".equals(event.get("kind"))).count());
                assertFalse(wire.toString("UTF-8").contains("未标记")); assertFalse(session.failed()); assertEquals(0, game.exits);
                send(store, session, request("observe", "state.get", null, null));
                assertFalse(last(wire).containsKey("err"));
                send(store, session, request("next", "action.execute", "v2", map("action", "wait")));
                assertFalse(last(wire).containsKey("err")); assertEquals(2, game.executions);
                assertEquals("COMPLETED", store.getRequest("run:a", "next").get("status"));
            }
        }
    }

    @Test public void historyDoesNotObserveTheLiveEngineOrRewriteAnAlreadyRecordedWireResponse() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            store.ensureScope("run:a", "run", "a");
            Map<String,Object> recorded = map("v", 5, "id", "old", "st", "completed",
                    "pres", map("st", "partial", "diag", List.of(map("field", "$.data.name", "code", "source_missing"))),
                    "data", map("name", "already rendered fallback", "text_sources", map("name", map("status", "unavailable"))));
            AuditStore.Attempt original = store.begin("run:a", "old", "wait", "{\"v\":5,\"id\":\"old\",\"op\":\"wait\"}");
            store.complete(original, "COMPLETED", recorded, map("scene", "game"), map("private", true), null);
            Map<String,Object> expected = (Map<String,Object>)store.getRequest("run:a", "old").get("response");
            FakeGame game = new FakeGame(); game.failObservation = true; ByteArrayOutputStream wire = new ByteArrayOutputStream();
            try (MachineSession session = new MachineSession(store, game, new PrintStream(wire, true, "UTF-8"), 1000)) {
                send(store, session, request("record", "request.get", null, map("target_id", "old", "get", List.of("reply"))));
                assertFalse(last(wire).containsKey("err"));
                assertEquals(expected, ((Map<?,?>)last(wire).get("data")).get("reply"));
                send(store, session, request("history", "history.list", null, null)); assertFalse(last(wire).containsKey("err"));
                send(store, session, request("events", "events.read", null, null)); assertFalse(last(wire).containsKey("err"));
                assertEquals(0, game.observations); assertEquals(0, game.executions); assertFalse(session.failed());
                assertEquals(expected, store.getRequest("run:a", "old").get("response"));
            }
        }
    }

    @Test public void reopeningHistoryPreservesEventTextSourcesDiagnosticsAndCapturedGuiLanguage() throws Exception {
        java.nio.file.Path profile=temporary.newFolder().toPath();
        Map<String,Object> recorded;
        try(AuditStore store=new AuditStore(profile)) {
            store.ensureScope("run:a","run","a");store.beginSession("event-boot","event-build","CLI.6.0.0",6);
            com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance provenance=
                    new com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance(key->"Recorded %d");
            String source=provenance.onTextResource("当时显示17","fixture.recorded","zh",new Object[]{17});
            Map<String,Object> data=PublicEnglishProjection.copy(map("gui_language","zh","text_language","en",
                    "entries",List.of(map("text",provenance.capture(null,source,false)),map("text","未标记的另一行"))));
            data.put("presentation",PublicEnglishProjection.presentation(data));
            recorded=JsonCodec.decode(JsonCodec.encode(data));
            store.eventWithOriginalText("run:a","game.log",data,map("gui_language","zh","text","private original display"));
            store.endSession("CLOSED","finished");
        }
        try(AuditStore store=new AuditStore(profile)) {
            store.beginSession("new-boot","new-build","CLI.6.0.0",6);
            FakeGame game=new FakeGame();game.failObservation=true;
            ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,"UTF-8"),1000)) {
                send(store, session,request("event-history","events.read",null,null));
                assertFalse(last(wire).containsKey("err"));
                assertEquals("partial",((Map<?,?>)last(wire).get("pres")).get("st"));
                List<?> events=(List<?>)((Map<?,?>)last(wire).get("data")).get("items");assertEquals(1,events.size());
                Map<?,?> eventData=(Map<?,?>)((Map<?,?>)events.get(0)).get("data");
                assertEquals(recorded.get("gui_language"),eventData.get("gui_language"));
                List<?> entries=(List<?>)eventData.get("entries");
                assertEquals(2,entries.size());
                List<?> frozenEntries=(List<?>)recorded.get("entries");
                for(int i=0;i<entries.size();i++)
                    assertEquals(((Map<?,?>)frozenEntries.get(i)).get("text"),((Map<?,?>)entries.get(i)).get("text"));
                Map<?,?> partialEntry=(Map<?,?>)entries.get(1);
                assertEquals("partial",((Map<?,?>)partialEntry.get("pres")).get("st"));
                Object diagnostic=((Map<?,?>)((Map<?,?>)frozenEntries.get(1)).get("text_diagnostics")).get("text");
                assertNotNull(diagnostic);
                assertEquals(List.of(map("field","text","code",diagnostic)),((Map<?,?>)partialEntry.get("pres")).get("diag"));
                assertEquals(List.of(map("field","$.data.items[0].data.entries[1].text","code",diagnostic)),
                        ((Map<?,?>)last(wire).get("pres")).get("diag"));
                assertFalse(JsonCodec.encode(eventData).contains("text_sources"));
                assertFalse(JsonCodec.encode(eventData).contains("private original display"));
                assertEquals("partial",((Map<?,?>)events.get(0)).get("presentation_status"));
                assertEquals(recorded,store.events("run:a",0,100).get(0).get("data"));
                assertEquals(0,game.observations);assertEquals(0,game.executions);
            }
        }
    }

    @Test public void aRealRuntimeFailureAfterAPartialResponseStillLocksUnknownExecution() throws Exception {
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())) {
            store.ensureScope("run:a","run","a");
            FakeGame game=new FakeGame();ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,"UTF-8"),1000)) {
                send(store, session,request("partial","action.execute","v1",map("action","wait")));
                assertFalse(last(wire).containsKey("err"));
                game.failExecution=true;
                send(store, session,request("broken","action.execute","v2",map("action","wait")));
                assertEquals("EXECUTION_UNKNOWN",error(last(wire)));
                assertEquals("UNKNOWN",store.getRequest("run:a","broken").get("status"));
                assertNull(store.getRequest("run:a","broken").get("after_snapshot"));
                send(store, session,request("unknown-observation","state.get",null,null));
                assertEquals("execution_unknown",((Map<?,?>)last(wire).get("data")).get("phase"));
                send(store, session,request("blocked","action.execute","v2",map("action","wait")));
                assertEquals("EXECUTION_UNCERTAIN",error(last(wire)));assertEquals(2,game.executions);
            }
        }
    }

    private static Map<String,Object> request(String id, String op, String version, Map<String,Object> args) {
        Map<String,Object> request = map("protocol_version", 6, "scope_id", "run:a", "id", id, "op", op);
        if (version != null) request.put("state_version", version);
        if (args != null) request.put("args", args);
        return request;
    }
    private static void send(AuditStore store, MachineSession session, Map<String,Object> request) throws Exception {
        session.accept(V6Requests.encode(store, request)).get(5, TimeUnit.SECONDS);
    }
    private static String error(Map<String,Object> response) { return (String)response.get("err"); }
    private static Map<String,Object> last(ByteArrayOutputStream wire) {
        String[] lines = wire.toString(StandardCharsets.UTF_8).trim().split("\n");
        return JsonCodec.decode(lines[lines.length - 1]);
    }
    private static final class FakeGame implements MachineSession.GamePort {
        int observations, executions, exits; boolean failObservation,failExecution;
        GameController.State state = state("v1", false);
        Queue<GameController.SaveResult> saves = new ArrayDeque<>();
        Queue<GameController.GameLogSnapshot> logs = new ArrayDeque<>();
        public GameController.State latest() { return state; }
        public CompletableFuture<GameController.State> observe() {
            observations++;
            return failObservation ? CompletableFuture.failedFuture(new IllegalStateException("live observation unavailable"))
                    : CompletableFuture.completedFuture(state);
        }
        public CompletableFuture<GameController.State> execute(String version, Map<String,Object> args) {
            executions++;
            if(failExecution)throw new IllegalStateException("PRIVATE_RUNTIME_FAILURE");
            state = state("v" + (executions + 1), true);
            if (executions == 1) {
                saves.add(new GameController.SaveResult("saved", "a", 1, null, "2026-09-13T00:00:00Z", "run:a", "act"));
                logs.add(new GameController.GameLogSnapshot("run:a", "2026-09-13T00:00:00Z",
                        List.of(new RuntimeObserver.LogEntry("未标记的实际日志", 1, false))));
            }
            return CompletableFuture.completedFuture(state);
        }
        public void prepareRun(String id) { throw new AssertionError("No menu action in this fixture"); }
        public GameController.SaveResult pollSave() { return saves.poll(); }
        public GameController.GameLogSnapshot pollGameLog() { return logs.poll(); }
        public boolean exiting() { return false; }
        public boolean disposed() { return false; }
        public void exitNow() { exits++; }
        private static GameController.State state(String version, boolean partial) {
            return new GameController.State("run:a", version, "player_ready",
                    partial ? map("scene", "game", "name", "未标记的实际标签") : map("scene", "game"),
                    map("private", true), Collections.singletonList(map("action", "wait")));
        }
    }
}
