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

/** Protocol-2 failures are separated at dispatch, certified completion and pure presentation boundaries. */
public class Cli2SessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void missingAndOldProtocolCannotObserveOrDispatchEvenTheHandshake() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            store.ensureScope("run:a", "run", "a");
            FakeGame game = new FakeGame(); ByteArrayOutputStream wire = new ByteArrayOutputStream();
            try (MachineSession session = new MachineSession(store, game, new PrintStream(wire, true, "UTF-8"), 1000)) {
                send(session, map("id", "missing", "op", "protocol.info"));
                assertEquals("PROTOCOL_VERSION_REQUIRED", error(last(wire)));
                send(session, map("protocol_version", 1, "id", "old", "op", "protocol.info"));
                assertEquals("UNSUPPORTED_PROTOCOL", error(last(wire)));
                send(session, map("protocol_version", 1, "scope_id", "run:a", "id", "old-action", "op", "action.execute",
                        "state_version", "v1", "args", map("action", "wait")));
                assertEquals("UNSUPPORTED_PROTOCOL", error(last(wire)));
                assertEquals(0, game.observations); assertEquals(0, game.executions);
                send(session, map("protocol_version", 2, "id", "new", "op", "protocol.info"));
                assertEquals(Boolean.TRUE, last(wire).get("ok"));
                Map<?,?> hello = (Map<?,?>) last(wire).get("result");
                assertEquals(2L, hello.get("protocol_version")); assertEquals(5L, hello.get("audit_schema_version"));
                assertEquals("CLI.2.0.0", hello.get("cli_version"));
            }
        }
    }

    @Test public void certifiedActionWithMissingTextKeepsItsSaveLogsAndAllowsTheNextAction() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            store.ensureScope("run:a", "run", "a"); store.beginSession("cli2-presentation","fixture-build","CLI.2.0.0",2);
            FakeGame game = new FakeGame(); ByteArrayOutputStream wire = new ByteArrayOutputStream();
            try (MachineSession session = new MachineSession(store, game, new PrintStream(wire, true, "UTF-8"), 1000)) {
                send(session, request("act", "action.execute", "v1", map("action", "wait")));
                Map<String,Object> response = last(wire);
                assertEquals(Boolean.TRUE, response.get("ok")); assertEquals("completed", response.get("status"));
                assertEquals("partial", ((Map<?,?>)response.get("presentation")).get("status"));
                Map<?,?> result = (Map<?,?>)response.get("result");
                assertEquals("v2", result.get("state_version"));
                Map<?,?> persistence = (Map<?,?>)result.get("persistence");
                assertEquals(1, ((List<?>)persistence.get("saves_during_request")).size());
                Map<String,Object> recorded = store.getRequest("run:a", "act");
                assertEquals("COMPLETED", recorded.get("status")); assertEquals("partial", recorded.get("presentation_status"));
                assertEquals(response, recorded.get("response")); assertNotNull(recorded.get("after_snapshot"));
                assertEquals(1, store.events("run:a", 0, 100).stream().filter(event -> "game.log".equals(event.get("kind"))).count());
                assertFalse(wire.toString("UTF-8").contains("未标记")); assertFalse(session.failed()); assertEquals(0, game.exits);
                send(session, request("observe", "state.get", null, null));
                assertEquals(Boolean.TRUE, last(wire).get("ok"));
                send(session, request("next", "action.execute", "v2", map("action", "wait")));
                assertEquals(Boolean.TRUE, last(wire).get("ok")); assertEquals(2, game.executions);
                assertEquals("COMPLETED", store.getRequest("run:a", "next").get("status"));
            }
        }
    }

    @Test public void historyDoesNotObserveTheLiveEngineOrRewriteAnAlreadyRecordedWireResponse() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            store.ensureScope("run:a", "run", "a");
            Map<String,Object> recorded = map("protocol_version", 2, "ok", true, "status", "completed",
                    "presentation", map("status", "partial", "diagnostics", List.of(map("path", "/observation/name", "reason", "source_missing"))),
                    "result", map("name", "already rendered fallback", "text_sources", map("name", map("status", "unavailable"))));
            AuditStore.Attempt original = store.begin("run:a", "old", "action.execute", "{\"protocol_version\":2}");
            store.complete(original, "COMPLETED", recorded, map("scene", "game"), map("private", true), null);
            Map<String,Object> expected = (Map<String,Object>)store.getRequest("run:a", "old").get("response");
            FakeGame game = new FakeGame(); game.failObservation = true; ByteArrayOutputStream wire = new ByteArrayOutputStream();
            try (MachineSession session = new MachineSession(store, game, new PrintStream(wire, true, "UTF-8"), 1000)) {
                send(session, request("record", "request.get", null, map("target_id", "old")));
                assertEquals(Boolean.TRUE, last(wire).get("ok"));
                assertEquals(expected, ((Map<?,?>)last(wire).get("result")).get("response"));
                send(session, request("history", "history.list", null, null)); assertEquals(Boolean.TRUE, last(wire).get("ok"));
                send(session, request("events", "events.read", null, null)); assertEquals(Boolean.TRUE, last(wire).get("ok"));
                assertEquals(0, game.observations); assertEquals(0, game.executions); assertFalse(session.failed());
                assertEquals(expected, store.getRequest("run:a", "old").get("response"));
            }
        }
    }

    @Test public void reopeningHistoryPreservesEventTextSourcesDiagnosticsAndCapturedGuiLanguage() throws Exception {
        java.nio.file.Path profile=temporary.newFolder().toPath();
        Map<String,Object> recorded;
        try(AuditStore store=new AuditStore(profile)) {
            store.ensureScope("run:a","run","a");store.beginSession("event-boot","event-build","CLI.2.0.0",2);
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
            store.beginSession("new-boot","new-build","CLI.2.1.0",2);
            FakeGame game=new FakeGame();game.failObservation=true;
            ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,"UTF-8"),1000)) {
                send(session,request("event-history","events.read",null,null));
                assertEquals(Boolean.TRUE,last(wire).get("ok"));
                assertEquals("partial",((Map<?,?>)last(wire).get("presentation")).get("status"));
                List<?> events=(List<?>)last(wire).get("result");assertEquals(1,events.size());
                assertEquals(recorded,((Map<?,?>)events.get(0)).get("data"));
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
                send(session,request("partial","action.execute","v1",map("action","wait")));
                assertEquals(Boolean.TRUE,last(wire).get("ok"));
                game.failExecution=true;
                send(session,request("broken","action.execute","v2",map("action","wait")));
                assertEquals("EXECUTION_UNKNOWN",error(last(wire)));
                assertEquals("UNKNOWN",store.getRequest("run:a","broken").get("status"));
                assertNull(store.getRequest("run:a","broken").get("after_snapshot"));
                send(session,request("unknown-observation","state.get",null,null));
                assertEquals("execution_unknown",((Map<?,?>)last(wire).get("result")).get("phase"));
                send(session,request("blocked","action.execute","v2",map("action","wait")));
                assertEquals("EXECUTION_UNCERTAIN",error(last(wire)));assertEquals(2,game.executions);
            }
        }
    }

    private static Map<String,Object> request(String id, String op, String version, Map<String,Object> args) {
        Map<String,Object> request = map("protocol_version", 2, "scope_id", "run:a", "id", id, "op", op);
        if (version != null) request.put("state_version", version);
        if (args != null) request.put("args", args);
        return request;
    }
    private static void send(MachineSession session, Map<String,Object> request) throws Exception {
        session.accept(JsonCodec.encode(request)).get(5, TimeUnit.SECONDS);
    }
    private static String error(Map<String,Object> response) { return (String)((Map<?,?>)response.get("error")).get("code"); }
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
