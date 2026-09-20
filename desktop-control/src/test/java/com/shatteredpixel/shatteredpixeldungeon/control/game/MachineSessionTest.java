package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Uses real paired SQLite transactions and controlled runtime futures, never a graphical application. */
public class MachineSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void malformedArgumentsConsumeTheIdBeforeAnyRuntimeCall() throws Exception {
        try (Harness h = harness()) {
            h.accept("{\"v\":7,\"s\":\"" + h.store.publicHandle("scope", "run:a") + "\",\"id\":\"bad\",\"op\":\"wait\",\"args\":[]}");
            assertEquals("INVALID_REQUEST", error(h.last()));
            assertEquals("REJECTED", h.store.getRequest("run:a", "bad").get("status"));
            int observations = h.game.observations;
            h.send("bad", "state.get", map());
            assertEquals("DUPLICATE_REQUEST_ID", error(h.last()));
            assertEquals(observations, h.game.observations);
            assertEquals(0, h.game.executions);
            assertEquals(2, h.store.history("run:a", 0, 10).size());
        }
    }

    @Test public void invalidTargetIdentifierCannotAliasARealQuestionMarkRequest() throws Exception {
        try(Harness h=harness()){
            h.send("?","state.get",map());
            h.send("bad-lookup","request.get",map("target_id","\ud800"));
            assertEquals("INVALID_ARGUMENT",error(h.last()));
            assertEquals("COMPLETED",h.store.getRequest("run:a","?").get("status"));
            h.send("bad-lookup","request.get",map("target_id","?"));
            assertEquals("DUPLICATE_REQUEST_ID",error(h.last()));
        }
    }

    @Test public void runtimeAndRunPreparationOnlyHappenAfterDurableIntent() throws Exception {
        try (Harness h = harness()) {
            String menu = h.store.menuScope();
            h.game.state = state(menu, "v1", "menu_ready", "main menu");
            List<String> ordering = new ArrayList<>();
            h.game.preparing = ignored -> {
                Map<String, Object> request = h.store.getRequest(menu, "start");
                ordering.add(String.valueOf(request.get("status")));
                assertNotNull(request.get("target_scope"));
            };
            h.game.executing = ignored -> ordering.add(String.valueOf(h.store.getRequest(menu, "start").get("status")));
            h.send("start", "action.execute", map("action", "ui.activate", "control", "start"));
            assertEquals(java.util.Arrays.asList("EXECUTING", "EXECUTING"), ordering);
            assertFalse(h.last().containsKey("err"));
        }
    }

    @Test public void pendingQueriesAreExplicitlyLastStableAndSettlementNeverPushesAnotherResponse() throws Exception {
        try (Harness h = harness()) {
            CompletableFuture<GameController.State> action = new CompletableFuture<>();
            h.game.next = action;
            h.send("slow", "action.execute", map("action", "wait"));
            assertEquals("in_progress", h.last().get("st"));
            h.send("during", "state.get", map());
            Map<?, ?> result = (Map<?, ?>) h.last().get("data");
            assertEquals("resolving", result.get("phase"));
            assertEquals("last_stable", result.get("snap"));
            assertNull(h.last().get("rev"));
            assertEquals(Collections.emptyList(), result.get("acts"));
            h.send("blocked", "action.execute", map("action", "wait"));
            assertEquals("BUSY", error(h.last()));
            assertEquals(1, h.game.executions);
            GameController.State after = state("run:a", "v2", "player_ready", "after");
            h.game.state = after;
            action.complete(after);
            h.send("result", "request.get", map("target_id", "slow"));
            assertEquals(4, h.responses().size());
            assertEquals("COMPLETED", ((Map<?, ?>) h.last().get("data")).get("st"));
            assertEquals("in_progress", recordedResponse(h.store, 1).get("st"));
        }
    }

    @Test public void synchronousRuntimeFailureHasUnknownOutcomeAndNoInventedAfterSnapshot() throws Exception {
        try (Harness h = harness()) {
            h.game.synchronousFailure = new IllegalStateException("PRIVATE_ENGINE_DETAIL");
            h.send("broken", "action.execute", map("action", "wait"));
            assertEquals("EXECUTION_UNKNOWN", error(h.last()));
            Map<String, Object> record = h.store.getRequest("run:a", "broken");
            assertEquals("UNKNOWN", record.get("status"));
            assertNotNull(record.get("before_snapshot"));
            assertNull(record.get("after_snapshot"));
            assertFalse(h.output.toString("UTF-8").contains("PRIVATE_ENGINE_DETAIL"));
            h.game.synchronousFailure = null;
            h.send("observe", "state.get", map());
            assertEquals("execution_unknown", ((Map<?, ?>) h.last().get("data")).get("phase"));
            h.send("new-action", "action.execute", map("action", "wait"));
            assertEquals("EXECUTION_UNCERTAIN", error(h.last()));
            assertEquals(1, h.game.executions);
        }
    }

    @Test public void lateFailureSettlesUnknownWithoutReplacingPendingWireResponse() throws Exception {
        try (Harness h = harness()) {
            CompletableFuture<GameController.State> action = new CompletableFuture<>();
            h.game.next = action;
            h.send("late", "action.execute", map("action", "wait"));
            action.completeExceptionally(new IllegalStateException("PRIVATE_LATE_ERROR"));
            h.send("result", "request.get", map("target_id", "late"));
            assertEquals("UNKNOWN", ((Map<?, ?>) h.last().get("data")).get("st"));
            assertNull(h.store.getRequest("run:a", "late").get("after_snapshot"));
            assertEquals(2, h.responses().size());
            assertEquals("in_progress", recordedResponse(h.store, 1).get("st"));
        }
    }

    @Test public void historicalScopeAuditDoesNotCopyAnotherRunsPublicObservation() throws Exception {
        try (Harness h = harness()) {
            h.store.ensureScope("run:old", "run", "old");
            AuditStore.Attempt old = h.store.begin("run:old", "old", "state.get", "{}");
            h.store.complete(old, "COMPLETED", map("old", true), map("known", "old run"), map("private", "old"), null);
            h.game.state = state("run:a", "v1", "player_ready", "OTHER_RUN_PUBLIC_SENTINEL");
            h.accept(V7Requests.encode(h.store, map("protocol_version", 7, "scope_id", "run:old", "id", "history", "op", "history.list")));
            assertFalse(h.output.toString("UTF-8").contains("OTHER_RUN_PUBLIC_SENTINEL"));
            String audit = JsonCodec.encode(h.store.getRequest("run:old", "history"));
            assertFalse(audit.contains("OTHER_RUN_PUBLIC_SENTINEL"));
            assertTrue(audit.contains("scope_not_active"));
        }
    }

    @Test public void certifiedPreconditionFailureDoesNotLockFutureActions() throws Exception {
        try (Harness h = harness()) {
            h.game.synchronousFailure = new GameController.NotExecuted("STALE_STATE", new IllegalStateException("before callback"));
            h.send("stale", "action.execute", map("action", "wait"));
            assertEquals("STALE_STATE", error(h.last()));
            assertEquals("REJECTED", h.store.getRequest("run:a", "stale").get("status"));
            h.game.synchronousFailure = null;
            h.send("fresh", "action.execute", map("action", "wait"));
            assertFalse(h.last().containsKey("err"));
            assertEquals(2, h.game.executions);
        }
    }

    @Test public void repeatedHistoryQueriesReturnIndexesWithoutRecursivePayloads() throws Exception {
        try (Harness h = harness()) {
            for (int i = 0; i < 8; i++) h.send("history-" + i, "history.list", map());
            Map<?,?> page = (Map<?,?>)h.last().get("data");
            assertEquals(true, page.get("end")); assertNull(page.get("next"));
            List<?> result = (List<?>) page.get("items");
            assertEquals(8, result.size());
            for (Object entry : result) {
                Map<?, ?> row = (Map<?, ?>) entry;
                assertFalse(row.containsKey("response"));
                assertFalse(row.containsKey("before_snapshot"));
                assertFalse(row.containsKey("raw_request"));
            }
            assertTrue(JsonCodec.encode(h.last()).length() < 10_000);
        }
    }

    @Test public void eofIsAuditedSystemLifecycleWithNoCallerRequestOrStdout() throws Exception {
        try (Harness h = harness()) {
            h.game.executing = args -> assertTrue(h.store.events("run:a", 0, 20).stream().anyMatch(e -> "shutdown.dispatch".equals(e.get("kind"))));
            h.session.read(new ByteArrayInputStream(new byte[0]));
            assertTrue(h.responses().isEmpty());
            assertTrue(h.store.history("run:a", 0, 10).isEmpty());
            assertEquals(1, h.game.executions);
            assertEquals("app.quit", h.game.lastAction);
            assertTrue(h.store.events("run:a", 0, 20).stream().anyMatch(e -> "shutdown.completed".equals(e.get("kind"))));
        }
    }

    @Test public void failedRequestStillDrainsSaveFailureEvents() throws Exception {
        try (Harness h = harness()) {
            h.game.saves.add(new GameController.SaveResult("a", 1, new IOException("PRIVATE_SAVE_ERROR")));
            h.send("bad-op", "does.not.exist", map());
            assertEquals("UNKNOWN_OPERATION", error(h.last()));
            List<Map<String, Object>> events = h.store.events("run:a", 0, 10);
            assertEquals(1, events.size());
            assertEquals(Boolean.FALSE, ((Map<?, ?>) events.get(0).get("data")).get("success"));
            assertFalse(JsonCodec.encode(events).contains("PRIVATE_SAVE_ERROR"));
        }
    }

    @Test public void authoritativeRunOutcomeIsPublicAndPersistedWithoutCauseDetails() throws Exception {
        try (Harness h = harness()) {
            GameController.RunOutcome ended = new GameController.RunOutcome("a", true);
            h.game.outcomes.add(ended);
            h.game.state = new GameController.State("run:a", "won-version", "ended", map("scene", "surface"),
                    map("private", "cause-not-public"), Collections.emptyList(), ended.data());
            h.send("won", "state.get", map());
            assertEquals("won", ((Map<?, ?>) ((Map<?, ?>) h.last().get("data")).get("run_outcome")).get("result"));
            Map<String, Object> event = h.store.events("run:a", 0, 10).get(0);
            assertEquals("run.ended", event.get("kind"));
            assertEquals(ended.data(), event.get("data"));
            assertFalse(JsonCodec.encode(event).contains("cause-not-public"));
            h.send("again", "state.get", map());
            assertEquals(1, h.store.events("run:a", 0, 10).size());
        }
    }

    @Test public void aLedgerInsertFailureNeverCallsTheRuntime() throws Exception {
        try (Harness h = harness()) {
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + h.store.internalDatabase()); Statement s = db.createStatement()) {
                s.execute("CREATE TRIGGER reject_requests BEFORE INSERT ON requests BEGIN SELECT RAISE(ABORT,'injected'); END");
            }
            h.send("not-dispatched", "action.execute", map("action", "wait"));
            assertEquals("AUDIT_UNAVAILABLE", error(h.last()));
            assertEquals(0, h.game.executions);
            assertNull(h.store.getRequest("run:a", "not-dispatched"));
        }
    }

    @Test public void failedPendingSettlementStopsNewActionsEvenWhenSqlRollbackSucceeded() throws Exception {
        try (Harness h = harness()) {
            CompletableFuture<GameController.State> future = new CompletableFuture<>();
            h.game.next = future;
            h.send("slow", "action.execute", map("action", "wait"));
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + h.store.internalDatabase()); Statement s = db.createStatement()) {
                s.execute("CREATE TRIGGER fail_settlement BEFORE UPDATE ON requests WHEN OLD.id='slow' AND NEW.status='COMPLETED' BEGIN SELECT RAISE(ABORT,'injected settlement failure'); END");
            }
            future.complete(state("run:a", "v2", "player_ready", "after"));
            h.game.exitSignal.get(5, TimeUnit.SECONDS);
            assertEquals(1, h.responses().size());
            assertEquals("EXECUTING", h.store.getRequest("run:a", "slow").get("status"));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> h.session.accept(V7Requests.encode(h.store, map("protocol_version", 7, "scope_id", "run:a", "id", "next", "op", "action.execute", "args", map("action", "wait")))).get());
            assertEquals(1, h.game.executions);
        }
    }

    @Test public void outputFailureDoesNotProduceASecondJsonResponse() throws Exception {
        Path root = temporary.newFolder().toPath();
        int[] attempts = {0};
        OutputStream broken = new OutputStream() {
            public void write(int value) throws IOException { throw new IOException("broken pipe"); }
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (length > 0 && bytes[offset] == '{') attempts[0]++;
                throw new IOException("broken pipe");
            }
        };
        try (AuditStore store = new AuditStore(root)) {
            store.ensureScope("run:a", "run", "a");
            FakeGame game = new FakeGame();
            try (MachineSession session = new MachineSession(store, game, new PrintStream(broken, true, "UTF-8"), 30)) {
                session.accept(V7Requests.encode(store, map("protocol_version", 7, "scope_id", "run:a", "id", "query", "op", "state.get"))).get(5, TimeUnit.SECONDS);
                assertEquals(1, attempts[0]);
                assertEquals("COMPLETED", store.getRequest("run:a", "query").get("status"));
                assertEquals(Boolean.FALSE, store.history("run:a", 0, 10).get(0).get("output_succeeded"));
                assertTrue(game.exited);
            }
        }
    }

    private Harness harness() throws Exception { return new Harness(temporary.newFolder().toPath()); }
    private static String error(Map<String, Object> response) { return String.valueOf(response.get("err")); }
    private static Map<String, Object> recordedResponse(AuditStore store, long sequence) throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + store.publicDatabase()); Statement statement = db.createStatement();
             java.sql.ResultSet rs = statement.executeQuery("SELECT response_json FROM exchanges WHERE sequence=" + sequence)) {
            assertTrue(rs.next()); return JsonCodec.decode(rs.getString(1));
        }
    }
    private static GameController.State state(String scope, String version, String phase, String marker) {
        return new GameController.State(scope, version, phase, map("marker", marker), map("marker", marker, "private", "FULL_INTERNAL"),
                Collections.singletonList(map("action", "wait")));
    }
    private static final class FakeGame implements MachineSession.GamePort {
        volatile GameController.State state = state("run:a", "v1", "player_ready", "before");
        CompletableFuture<GameController.State> next;
        RuntimeException synchronousFailure;
        Consumer<Map<String, Object>> executing = ignored -> { };
        Consumer<String> preparing = ignored -> { };
        final ConcurrentLinkedQueue<GameController.SaveResult> saves = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<GameController.RunOutcome> outcomes = new ConcurrentLinkedQueue<>();
        final CompletableFuture<Void> exitSignal = new CompletableFuture<>();
        int executions, observations;
        boolean exiting, exited;
        String lastAction;
        public GameController.State latest() { return state; }
        public CompletableFuture<GameController.State> observe() { observations++; return CompletableFuture.completedFuture(state); }
        public CompletableFuture<GameController.State> execute(String version, Map<String, Object> args) {
            executions++; lastAction = String.valueOf(args.get("action")); executing.accept(args);
            if (synchronousFailure != null) throw synchronousFailure;
            if ("app.quit".equals(lastAction)) exiting = true;
            CompletableFuture<GameController.State> result = next; next = null;
            return result == null ? CompletableFuture.completedFuture(state) : result;
        }
        public void prepareRun(String id) { preparing.accept(id); }
        public GameController.SaveResult pollSave() { return saves.poll(); }
        public GameController.RunOutcome pollRunOutcome() { return outcomes.poll(); }
        public boolean exiting() { return exiting; }
        public boolean disposed() { return exited; }
        public void exitNow() { exited = true; exitSignal.complete(null); }
    }
    private static final class Harness implements AutoCloseable {
        final AuditStore store;
        final FakeGame game = new FakeGame();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final MachineSession session;
        Harness(Path path) throws Exception {
            store = new AuditStore(path); store.ensureScope("run:a", "run", "a");
            session = new MachineSession(store, game, new PrintStream(output, true, "UTF-8"), 30);
        }
        void accept(String raw) throws Exception { session.accept(raw).get(5, TimeUnit.SECONDS); }
        void send(String id, String op, Map<String, Object> args) throws Exception {
            accept(V7Requests.encode(store, map("protocol_version", 7, "scope_id", game.state.scopeId, "id", id, "op", op, "state_version", game.state.version, "args", args)));
        }
        List<Map<String, Object>> responses() throws Exception {
            List<Map<String, Object>> result = new ArrayList<>();
            for (String line : output.toString("UTF-8").split("\\R")) if (!line.isEmpty()) result.add(JsonCodec.decode(line));
            return result;
        }
        Map<String, Object> last() throws Exception { List<Map<String, Object>> responses = responses(); return responses.get(responses.size() - 1); }
        public void close() { session.close(); store.close(); }
    }
}
