package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ProtocolException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Fake engine futures with real isolated SQLite ledgers: no native backend or user data. */
public class ContinuousCancellationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SCOPE = "run:cancellation-fixture";
    private static final String ACTIVITY = "activity:cancellation-fixture:1";

    @Test
    public void continuousFirstResponseReturnsBeforeCompletionAndBeforeTheOrdinaryTimeout() throws Exception {
        try (Harness h = harness()) {
            h.start();
            assertEquals(1, h.game.starts);
            assertEquals(0, h.game.directExecutions);
            assertFalse(h.game.completion.isDone());
            assertEquals("in_progress", h.last().get("st"));
            Map<?, ?> result = (Map<?, ?>) h.last().get("data");
            assertTrue(JsonCodec.encode(result).contains("continuous_activity"));
            assertEquals(ACTIVITY, h.store.resolveHandle("activity", (String)h.last().get("rev")));
            assertFalse(JsonCodec.encode(result).contains(ACTIVITY));
            assertTrue(JsonCodec.encode(result).contains("\"op\":\"cancel\""));
            assertTrue(h.game.execution.acknowledged());
            assertEquals("EXECUTING", h.store.getRequest(SCOPE, "rest-1").get("status"));
        }
    }

    @Test
    public void cancellationRunsOnlyAfterItsOwnDurableIntentAndPreservesTheOriginalWireResponse() throws Exception {
        try (Harness h = harness()) {
            h.start();
            String originalWire = h.wireFor("rest-1");
            String originalExchange = h.exchangeResponse("rest-1");
            h.cancel("cancel-1", ACTIVITY, "rest-1");
            assertFalse(h.last().containsKey("err"));
            assertEquals(1, h.game.preparations);
            assertEquals(1, h.game.cancellations);
            assertEquals(Collections.singletonList("EXECUTING"), h.game.ledgerAtCancellation);
            assertTrue(h.game.execution.interrupted);
            assertFalse(h.game.leaseHeld);
            h.query("lookup-rest", "request.get", map("target_id", "rest-1"));
            Map<String, Object> original = h.store.getRequest(SCOPE, "rest-1");
            assertEquals("INTERRUPTED", original.get("status"));
            assertEquals("interrupted", ((Map<?, ?>) original.get("response")).get("st"));
            assertEquals("COMPLETED", h.store.getRequest(SCOPE, "cancel-1").get("status"));
            assertEquals(originalWire, h.wireFor("rest-1"));
            assertEquals(originalExchange, h.exchangeResponse("rest-1"));
            assertEquals(1, h.wireCount("rest-1"));
            assertEquals(1, h.wireCount("cancel-1"));
            h.cancel("cancel-1", ACTIVITY, "rest-1");
            assertEquals("DUPLICATE_REQUEST_ID", error(h.last()));
            assertEquals(1, h.game.cancellations);
        }
    }

    @Test
    public void incorrectTargetAndActivityGenerationAreRejectedAndConsumeTheirRequestIds() throws Exception {
        try (Harness h = harness()) {
            h.start();
            h.cancel("wrong-target", ACTIVITY, "some-other-request");
            assertTrue(h.last().containsKey("err"));
            assertEquals("REJECTED", h.store.getRequest(SCOPE, "wrong-target").get("status"));
            assertEquals(0, h.game.cancellations);
            h.cancel("wrong-target", ACTIVITY, "rest-1");
            assertEquals("DUPLICATE_REQUEST_ID", error(h.last()));
            h.cancel("wrong-token", "activity:previous-generation", "rest-1");
            assertTrue(h.last().containsKey("err"));
            assertEquals("REJECTED", h.store.getRequest(SCOPE, "wrong-token").get("status"));
            h.cancel("wrong-token", ACTIVITY, "rest-1");
            assertEquals("DUPLICATE_REQUEST_ID", error(h.last()));
            assertEquals(0, h.game.cancellations);
            assertEquals(1, h.game.starts);
        }
    }

    @Test
    public void naturalCompletionRemainsCompletedAndDoesNotPushAnotherResponse() throws Exception {
        try (Harness h = harness()) {
            h.start();
            String originalWire = h.wireFor("rest-1");
            h.game.latest = state("ordinary:2", "player_ready");
            h.game.completion.complete(h.game.latest);
            h.query("lookup-natural", "request.get", map("target_id", "rest-1"));
            assertEquals("COMPLETED", h.store.getRequest(SCOPE, "rest-1").get("status"));
            assertEquals(originalWire, h.wireFor("rest-1"));
            assertEquals(1, h.wireCount("rest-1"));
            assertEquals(0, h.game.preparations);
            assertEquals(0, h.game.cancellations);
            assertFalse(h.game.execution.interrupted);
        }
    }

    @Test
    public void otherMutationsRemainBusyAndDoNotRestartOrAdvanceTheRunningActivity() throws Exception {
        try (Harness h = harness()) {
            h.start();
            h.action("wait-while-resting", ACTIVITY, map("action", "wait"));
            assertEquals("BUSY", error(h.last()));
            assertEquals("REJECTED", h.store.getRequest(SCOPE, "wait-while-resting").get("status"));
            assertEquals(1, h.game.starts);
            assertEquals(0, h.game.directExecutions);
            assertEquals(0, h.game.cancellations);
            assertFalse(h.game.completion.isDone());
        }
    }

    @Test
    public void failedCancellationIntentReleasesPreparationWithoutInvokingTheGameCancelCallback() throws Exception {
        try (Harness h = harness()) {
            h.start();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + h.store.publicDatabase());
                 Statement sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER fail_cancel_intent BEFORE UPDATE OF status ON requests "
                        + "WHEN NEW.id='cancel-write-fails' AND NEW.status='EXECUTING' "
                        + "BEGIN SELECT RAISE(ABORT,'fixture audit failure'); END");
            }
            h.cancel("cancel-write-fails", ACTIVITY, "rest-1");
            assertEquals("AUDIT_UNAVAILABLE", error(h.last()));
            assertEquals(1, h.game.preparations);
            assertEquals(1, h.game.aborts);
            assertEquals(0, h.game.cancellations);
            assertFalse(h.game.leaseHeld);
            assertFalse(h.game.completion.isDone());
            assertFalse(h.game.execution.interrupted);
        }
    }

    private Harness harness() throws Exception { return new Harness(temporary.newFolder().toPath()); }

    private static String error(Map<String, Object> response) {
        return String.valueOf(response.get("err"));
    }

    private static GameController.State state(String version, String phase) {
        List<Map<String, Object>> actions = phase.equals("continuous_activity")
                ? Collections.singletonList(map("action", "action.cancel", "target_id", "rest-1", "state_version", ACTIVITY))
                : Collections.singletonList(map("action", "rest"));
        return new GameController.State(SCOPE, version, phase,
                map("scene", "game", "phase", phase), map("fixture", "complete-internal-state"), actions);
    }

    private static final class FakeGame implements MachineSession.GamePort {
        final AuditStore store;
        final CompletableFuture<GameController.State> completion = new CompletableFuture<>();
        GameController.State latest = state("ordinary:1", "player_ready");
        GameController.Execution execution;
        int starts, directExecutions, preparations, cancellations, aborts;
        boolean leaseHeld, exited;
        String cancellationId;
        final List<String> ledgerAtCancellation = new ArrayList<>();

        FakeGame(AuditStore store) { this.store = store; }
        @Override public GameController.State latest() { return latest; }
        @Override public CompletableFuture<GameController.State> observe() { return CompletableFuture.completedFuture(latest); }
        @Override public CompletableFuture<GameController.State> execute(String version, Map<String, Object> args) {
            directExecutions++;
            return CompletableFuture.completedFuture(latest);
        }
        @Override public GameController.Execution start(String version, Map<String, Object> args, String requestId) {
            starts++;
            assertEquals("rest", args.get("action"));
            assertEquals("EXECUTING", store.getRequest(SCOPE, requestId).get("status"));
            latest = state(ACTIVITY, "continuous_activity");
            execution = new GameController.Execution(CompletableFuture.completedFuture(latest), completion);
            return execution;
        }
        @Override public CompletableFuture<GameController.State> prepareCancellation(String version, String targetId) {
            if (!ACTIVITY.equals(version)) return failed(new ProtocolException("STALE_STATE", "Wrong activity generation"));
            if (!"rest-1".equals(targetId)) return failed(new ProtocolException("INVALID_CANCEL_TARGET", "Wrong activity target"));
            preparations++;
            leaseHeld = true;
            return CompletableFuture.completedFuture(latest);
        }
        @Override public CompletableFuture<GameController.State> cancelPrepared(String version, String targetId) {
            assertTrue(leaseHeld);
            assertEquals(ACTIVITY, version);
            assertEquals("rest-1", targetId);
            cancellations++;
            ledgerAtCancellation.add(String.valueOf(store.getRequest(SCOPE, cancellationId).get("status")));
            assertNotNull(store.getRequest(SCOPE, cancellationId).get("before_snapshot"));
            execution.interrupted = true;
            leaseHeld = false;
            latest = state("ordinary:2", "player_ready");
            completion.complete(latest);
            return CompletableFuture.completedFuture(latest);
        }
        @Override public void abortCancellation(String version) { aborts++; leaseHeld = false; }
        @Override public void prepareRun(String id) { throw new AssertionError("Already in a run"); }
        @Override public GameController.SaveResult pollSave() { return null; }
        @Override public boolean exiting() { return false; }
        @Override public boolean disposed() { return exited; }
        @Override public void exitNow() { exited = true; }

        private static <T> CompletableFuture<T> failed(Throwable error) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(error);
            return future;
        }
    }

    private static final class Harness implements AutoCloseable {
        final AuditStore store;
        final FakeGame game;
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final MachineSession session;

        Harness(Path profile) throws Exception {
            store = new AuditStore(profile);
            store.ensureScope(SCOPE, "run", "cancellation-fixture");
            game = new FakeGame(store);
            // A first activity boundary must return immediately rather than wait this ordinary timeout.
            session = new MachineSession(store, game, new PrintStream(wire, true, "UTF-8"), 10_000);
        }
        void start() throws Exception { action("rest-1", "ordinary:1", map("action", "rest")); }
        void cancel(String id, String version, String target) throws Exception {
            game.cancellationId = id;
            action(id, version, map("action", "action.cancel", "target_id", target));
        }
        void action(String id, String version, Map<String, Object> args) throws Exception {
            accept(map("protocol_version", 6, "id", id, "scope_id", SCOPE, "op", "action.execute", "state_version", version, "args", args));
        }
        void query(String id, String op, Map<String, Object> args) throws Exception {
            accept(map("protocol_version", 6, "id", id, "scope_id", SCOPE, "op", op, "args", args));
        }
        void accept(Map<String, Object> request) throws Exception {
            session.accept(V6Requests.encode(store, request)).get(2, TimeUnit.SECONDS);
        }
        List<String> lines() throws Exception {
            List<String> result = new ArrayList<>();
            for (String line : wire.toString("UTF-8").split("\\R")) if (!line.isEmpty()) result.add(line);
            return result;
        }
        Map<String, Object> last() throws Exception {
            List<String> lines = lines();
            return JsonCodec.decode(lines.get(lines.size() - 1));
        }
        String wireFor(String id) throws Exception {
            for (String line : lines()) if (id.equals(JsonCodec.decode(line).get("id"))) return line;
            throw new AssertionError("Missing wire response");
        }
        int wireCount(String id) throws Exception {
            int count = 0;
            for (String line : lines()) if (id.equals(JsonCodec.decode(line).get("id"))) count++;
            return count;
        }
        String exchangeResponse(String id) throws Exception {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + store.publicDatabase());
                 java.sql.PreparedStatement sql = connection.prepareStatement("SELECT response_json FROM exchanges WHERE scope_id=? AND id=? ORDER BY sequence LIMIT 1")) {
                sql.setString(1, SCOPE); sql.setString(2, id);
                try (ResultSet result = sql.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
            }
        }
        @Override public void close() { session.close(); store.close(); }
    }
}
