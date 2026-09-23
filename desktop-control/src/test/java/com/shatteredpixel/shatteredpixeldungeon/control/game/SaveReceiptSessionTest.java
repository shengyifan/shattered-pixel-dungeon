package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Real paired SQLite + MachineSession, controlled futures; no claim of real-engine save or crash coverage. */
public class SaveReceiptSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SCOPE = "run:receipt-fixture";
    private static final String TIME = "2026-09-09T12:00:00Z";
    private static final String PRIVATE = "PRIVATE_SAVE_RECEIPT_FAILURE";

    @Test public void successfulActionAndQueriesDoNotInventPersistenceWithoutAReceipt() throws Exception {
        try (Harness h = harness()) {
            h.action("ordinary", "wait");
            assertFalse(h.last().containsKey("err"));
            assertNull(persistence(h.last()).get("saved"));
            assertEquals(Collections.emptyList(), persistence(h.last()).get("saves"));
            assertNull(h.store.latestSave(SCOPE));
            for (String op : Arrays.asList("state.get", "actions.list")) {
                h.query(op, op, map());
                assertTrue(result(h.last()).containsKey("saved"));
                assertNull(result(h.last()).get("saved"));
            }
        }
    }

    @Test public void synchronousReceiptIsDurableInBothDatabasesBeforeTheFirstResponseByte() throws Exception {
        try (Harness h = harness()) {
            h.game.onStart = id -> h.game.saves.add(receipt("sync-receipt", SCOPE, id, null));
            AtomicReference<List<String>> durable = new AtomicReference<>();
            h.output.beforeFirstWrite = () -> {
                try {
                    List<String> values = new ArrayList<>();
                    for (Path db : Arrays.asList(h.store.publicDatabase(), h.store.internalDatabase())) {
                        try (Connection connection = connect(db)) {
                            values.add(scalar(connection, "SELECT receipt_id FROM save_checkpoints WHERE receipt_id='sync-receipt'"));
                        }
                    }
                    durable.set(values);
                } catch (Exception error) { throw new IllegalStateException(error); }
            };
            h.action("save-now", "game.save");
            assertNull(h.output.probeFailure);
            assertEquals(Arrays.asList("sync-receipt", "sync-receipt"), durable.get());
            Map<String,Object> compactPersistence=object(object(h.last().get("data")).get("persistence"));
            assertEquals(0L,compactPersistence.get("saved"));
            Map<String,Object> compactSave=object(((List<?>)compactPersistence.get("saves")).get(0));
            assertFalse(compactSave.containsKey("s")); assertFalse(compactSave.containsKey("src_s"));
            assertReceipt(h.store, lastSave(h.last()), "sync-receipt", SCOPE, SCOPE, "save-now", true);
            assertEquals(Collections.singletonList("sync-receipt"), receiptIds(h.store, saves(h.last())));
            h.query("state-after-save", "state.get", map());
            assertReceipt(h.store, object(result(h.last()).get("saved")), "sync-receipt", SCOPE, SCOPE, "save-now", true);
            h.query("actions-after-save", "actions.list", map());
            assertReceipt(h.store, object(result(h.last()).get("saved")), "sync-receipt", SCOPE, SCOPE, "save-now", true);
        }
    }

    @Test public void failedSaveKeepsEarlierSuccessButReportsTheLatestFailureWithoutPrivateDetails() throws Exception {
        try (Harness h = harness()) {
            h.game.onStart = id -> h.game.saves.add(receipt("earlier-success", SCOPE, id, null));
            h.action("good-save", "game.save");
            h.game.onStart = id -> h.game.saves.add(receipt("latest-failure", SCOPE, id, new IOException(PRIVATE)));
            h.game.failure = new IllegalStateException(PRIVATE);
            h.action("bad-save", "game.save");
            assertEquals("EXECUTION_UNKNOWN", error(h.last()));
            assertReceipt(h.store, lastSave(h.last()), "latest-failure", SCOPE, SCOPE, "bad-save", false);
            assertEquals(Collections.singletonList("latest-failure"), receiptIds(h.store, saves(h.last())));
            assertEquals(Collections.singletonList("earlier-success"), canonicalReceiptIds(h.store.requestSaves(SCOPE, "good-save")));
            assertNull(h.store.getRequest(SCOPE, "bad-save").get("after_snapshot"));
            assertFalse(h.output.text().contains(PRIVATE));
            assertFalse(JsonCodec.encode(h.store.events(SCOPE, 0, 100)).contains(PRIVATE));
            try (Connection privateDb = connect(h.store.internalDatabase())) {
                assertTrue(Integer.parseInt(scalar(privateDb, "SELECT COUNT(*) FROM exceptions WHERE stack_trace LIKE '%" + PRIVATE + "%'")) > 0);
            }
            h.query("state-after-failure", "state.get", map());
            assertEquals("execution_unknown", result(h.last()).get("phase"));
            assertReceipt(h.store, object(result(h.last()).get("saved")), "latest-failure", SCOPE, SCOPE, "bad-save", false);
        }
    }

    @Test public void multipleReceiptsRemainOrderedAndActionSuccessDoesNotTurnAFailedSaveIntoSuccess() throws Exception {
        try (Harness h = harness()) {
            h.game.onStart = id -> {
                h.game.saves.add(receipt("first-ok", SCOPE, id, null));
                h.game.saves.add(receipt("second-failed", SCOPE, id, new IOException(PRIVATE)));
            };
            h.action("two-save-callbacks", "wait");
            assertFalse(h.last().containsKey("err"));
            assertEquals(Arrays.asList("first-ok", "second-failed"), receiptIds(h.store, saves(h.last())));
            assertEquals(Boolean.TRUE, saves(h.last()).get(0).get("success"));
            assertEquals(Boolean.FALSE, saves(h.last()).get(1).get("success"));
            assertEquals("second-failed", h.store.resolveHandle("save", (String)lastSave(h.last()).get("sid")));
        }
    }

    @Test public void lateSuccessfulReceiptUpdatesOnlyTheLogicalResultAndNeverEarlierWireOrHistoryResponses() throws Exception {
        try (Harness h = harness()) {
            CompletableFuture<GameController.State> completion = new CompletableFuture<>();
            h.game.next = completion;
            h.action("slow-save", "game.save");
            String initialWire = h.firstWireFor("slow-save");
            String initialExchange = h.exchangeResponse(SCOPE, "slow-save");
            assertEquals("in_progress", h.last().get("st"));
            assertEquals(Collections.emptyList(), saves(h.last()));
            h.game.saves.add(receipt("late-success", SCOPE, "slow-save", null));
            h.game.state = state(SCOPE, "v2", "player_ready");
            completion.complete(h.game.state);
            h.query("lookup-once", "request.get", map("target_id", "slow-save", "get", Arrays.asList("reply")));
            Map<String, Object> logical = result(h.last());
            assertEquals("COMPLETED", logical.get("st"));
            assertEquals(Collections.singletonList("late-success"), receiptIds(h.store, saves(object(logical.get("reply")))));
            assertEquals(initialWire, h.firstWireFor("slow-save"));
            assertEquals(initialExchange, h.exchangeResponse(SCOPE, "slow-save"));
            assertEquals(1, h.wireCount("slow-save"));
            String recordedLookup = JsonCodec.encode(h.store.getRequest(SCOPE, "lookup-once").get("response"));

            h.game.onStart = id -> h.game.saves.add(receipt("unrelated-new-save", SCOPE, id, null));
            h.action("another-save", "game.save");
            int observations = h.game.observations;
            h.query("lookup-once", "request.get", map("target_id", "slow-save", "get", Arrays.asList("reply")));
            assertEquals("DUPLICATE_REQUEST_ID", error(h.last()));
            assertFalse(h.last().containsKey("data"));
            assertEquals(observations, h.game.observations);
            assertEquals(recordedLookup, JsonCodec.encode(h.store.getRequest(SCOPE, "lookup-once").get("response")));
            h.query("lookup-fresh", "request.get", map("target_id", "slow-save", "get", Arrays.asList("reply")));
            Map<String, Object> oldResponse = object(result(h.last()).get("reply"));
            assertEquals(Collections.singletonList("late-success"), receiptIds(h.store, saves(oldResponse)));
            assertEquals("late-success", h.store.resolveHandle("save", (String)lastSave(oldResponse).get("sid")));
        }
    }

    @Test public void lateFailureReceiptIsVisibleInUnknownResultWithoutReplacingPendingWire() throws Exception {
        try (Harness h = harness()) {
            CompletableFuture<GameController.State> completion = new CompletableFuture<>();
            h.game.next = completion;
            h.action("slow-failure", "game.save");
            String initialWire = h.firstWireFor("slow-failure");
            h.game.saves.add(receipt("late-failure", SCOPE, "slow-failure", new IOException(PRIVATE)));
            completion.completeExceptionally(new IllegalStateException(PRIVATE));
            h.query("read-failure", "request.get", map("target_id", "slow-failure", "get", Arrays.asList("reply", "after")));
            Map<String, Object> logical = result(h.last());
            assertEquals("UNKNOWN", logical.get("st"));
            Map<String, Object> failedResponse = object(logical.get("reply"));
            assertEquals("EXECUTION_UNKNOWN", error(failedResponse));
            assertEquals(Collections.singletonList("late-failure"), receiptIds(h.store, saves(failedResponse)));
            assertEquals(Boolean.FALSE, lastSave(failedResponse).get("success"));
            assertNull(logical.get("after"));
            assertEquals(initialWire, h.firstWireFor("slow-failure"));
            assertEquals(1, h.wireCount("slow-failure"));
            assertFalse(h.output.text().contains(PRIVATE));
        }
    }

    @Test public void sameRequestIdInAnotherScopeCannotReceiveADelayedOldScopeReceipt() throws Exception {
        try (Harness h = harness()) {
            String oldScope = "run:old-receipt-fixture";
            h.store.ensureScope(oldScope, "run", "old-receipt-fixture");
            h.game.state = state(oldScope, "old-v1", "player_ready");
            h.game.onStart = id -> h.game.saves.add(receipt("old-save", oldScope, id, null));
            h.action("same-id", "game.save");
            h.game.state = state(SCOPE, "v1", "player_ready");
            h.game.onStart = id -> {
                h.game.saves.add(receipt("new-save", SCOPE, id, null));
                h.game.saves.add(receipt("delayed-old-save", oldScope, id, null));
            };
            h.action("same-id", "game.save");
            assertEquals(Collections.singletonList("new-save"), receiptIds(h.store, saves(h.last())));
            assertEquals("new-save", h.store.resolveHandle("save", (String)lastSave(h.last()).get("sid")));
            assertEquals(Arrays.asList("old-save", "delayed-old-save"), canonicalReceiptIds(h.store.requestSaves(oldScope, "same-id")));
            assertEquals(Collections.singletonList("new-save"), canonicalReceiptIds(h.store.requestSaves(SCOPE, "same-id")));
        }
    }

    @Test public void menuStartReceiptUsesTheNewRunForStorageAndOriginalMenuForRequestAttribution() throws Exception {
        try (Harness h = harness()) {
            String menu = h.store.menuScope();
            h.game.state = new GameController.State(menu, "menu-v1", "menu_ready",
                    map("scene", "title", "ui", map("controls", Collections.singletonList(
                            map("id", "c1", "role", "button", "label", "Start", "enabled", true)))),
                    map("test_fixture", true), Collections.singletonList(
                            map("action", "ui.activate", "control", "c1", "gestures", Collections.singletonList("click"))));
            h.game.onStart = id -> {
                String runScope = "run:" + h.game.plannedRun;
                h.game.state = state(runScope, "run-v1", "player_ready");
                h.game.saves.add(new GameController.SaveResult("new-run-save", h.game.plannedRun, 1, null, TIME, menu, id));
            };
            h.query("start", "action.execute", map("action", "ui.activate", "control", "c1"));
            assertFalse(h.last().containsKey("err"));
            assertNotNull(h.game.plannedRun);
            String runScope = "run:" + h.game.plannedRun;
            assertEquals(runScope, h.store.resolveHandle("scope", (String)h.last().get("s")));
            assertReceipt(h.store, lastSave(h.last()), "new-run-save", runScope, menu, "start", true);
            assertEquals(Collections.singletonList("new-run-save"), receiptIds(h.store, saves(h.last())));
            assertEquals(Collections.singletonList("new-run-save"), canonicalReceiptIds(h.store.requestSaves(menu, "start")));
            assertEquals(Collections.emptyList(), h.store.requestSaves(runScope, "start"));
            assertNull(h.store.latestSave(menu));
            assertEquals(runScope, h.store.getRequest(menu, "start").get("target_scope"));
        }
    }

    @Test public void duplicateMalformedAndCertifiedStaleRejectionsStayErrorOnlyDespiteExistingSaveState() throws Exception {
        try (Harness h = harness()) {
            h.game.onStart = id -> h.game.saves.add(receipt("known-save", SCOPE, id, null));
            h.action("saved", "game.save");
            int starts = h.game.starts, observations = h.game.observations;
            h.action("saved", "game.save");
            assertEquals("DUPLICATE_REQUEST_ID", error(h.last()));
            assertFalse(h.last().containsKey("data"));
            assertEquals(starts, h.game.starts);
            assertEquals(observations, h.game.observations);
            h.send(map("v", 7, "id", "bad-args", "s", SCOPE, "op", "wait", "rev", "v1", "args", Collections.emptyList()));
            assertEquals("INVALID_REQUEST", error(h.last()));
            assertFalse(h.last().containsKey("data"));
            h.send(map("protocol_version", 7, "id", "stale-before-dispatch", "scope_id", SCOPE, "op", "action.execute", "state_version", "old-version", "args", map("action", "wait")));
            assertEquals("STALE_STATE", error(h.last()));
            assertFalse(h.last().containsKey("data"));
            h.game.onStart = ignored -> {};
            h.game.failure = new GameController.NotExecuted("STALE_STATE", new IllegalStateException("private preflight"));
            h.action("certified-stale", "wait");
            assertEquals("STALE_STATE", error(h.last()));
            assertFalse(h.last().containsKey("data"));
        }
    }

    @Test public void currentSessionAttributionDoesNotPullPriorSessionReceiptsIntoANewAction() throws Exception {
        Path profile = temporary.newFolder().toPath();
        try (AuditStore old = new AuditStore(profile)) {
            old.beginSession(UUID.randomUUID().toString(),"fixture-build","CLI.7.0.0",7);
            old.ensureScope(SCOPE, "run", "receipt-fixture");
            old.recordSave("previous-session-save", SCOPE, 1, true, TIME, SCOPE, "future-id", null);
            old.endSession("CLOSED", "fixture complete");
        }
        try (Harness h = new Harness(profile)) {
            h.action("future-id", "wait");
            assertEquals("previous-session-save", h.store.resolveHandle("save", (String)lastSave(h.last()).get("sid")));
            assertEquals(Collections.emptyList(), saves(h.last()));
            assertEquals(Collections.emptyList(), h.store.requestSaves(SCOPE, "future-id"));
        }
    }

    @Test public void receiptWriteFailureCannotPublishASuccessfulSaveResponse() throws Exception {
        try (Harness h = harness()) {
            try (Connection privateDb = connect(h.store.internalDatabase()); Statement statement = privateDb.createStatement()) {
                statement.execute("CREATE TRIGGER reject_receipt BEFORE INSERT ON save_checkpoints BEGIN SELECT RAISE(ABORT,'fixture receipt failure'); END");
            }
            h.game.onStart = id -> h.game.saves.add(receipt("uncommitted-receipt", SCOPE, id, null));
            h.action("save-ledger-fails", "game.save");
            assertEquals("AUDIT_UNAVAILABLE", error(h.last()));
            assertFalse(h.last().containsKey("data"));
            assertTrue(h.game.exited);
            assertNull(h.store.latestSave(SCOPE));
            assertNotEquals("COMPLETED", h.store.getRequest(SCOPE, "save-ledger-fails").get("status"));
            for (Path db : Arrays.asList(h.store.publicDatabase(), h.store.internalDatabase())) {
                try (Connection connection = connect(db)) {
                    assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM save_checkpoints"));
                }
            }
        }
    }

    private Harness harness() throws Exception { return new Harness(temporary.newFolder().toPath()); }
    private static GameController.SaveResult receipt(String receiptId, String scope, String request, Throwable error) {
        return new GameController.SaveResult(receiptId, scope.substring(4), 1, error, TIME, scope, request);
    }
    private static GameController.State state(String scope, String version, String phase) {
        return new GameController.State(scope, version, phase, map("scene", phase.equals("menu_ready") ? "title" : "game"),
                map("test_fixture", true), Collections.singletonList(map("action", "wait")));
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { assertTrue(value instanceof Map); return (Map<String, Object>) value; }
    private static Map<String, Object> result(Map<String, Object> response) { return object(object(CompactProtocol.expandStructures(response)).get("data")); }
    private static Map<String, Object> persistence(Map<String, Object> response) { return object(result(response).get("persistence")); }
    private static Map<String, Object> lastSave(Map<String, Object> response) { return object(persistence(response).get("saved")); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> saves(Map<String, Object> response) { return (List<Map<String, Object>>) persistence(response).get("saves"); }
    private static String error(Map<String, Object> response) { return String.valueOf(response.get("err")); }
    private static List<String> receiptIds(AuditStore store, List<Map<String, Object>> receipts) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> receipt : receipts) {
            String canonical=store.resolveHandle("save", (String)receipt.get("sid"));
            assertNotNull("A wire save identity must resolve in this profile",canonical); ids.add(canonical);
        }
        return ids;
    }
    // Store DTOs retain canonical names; wire receipts are asserted separately above.
    private static List<String> canonicalReceiptIds(List<Map<String, Object>> receipts) {
        List<String> ids = new ArrayList<>(); for (Map<String, Object> receipt : receipts) ids.add((String) receipt.get("receipt_id")); return ids;
    }
    private static void assertReceipt(AuditStore store, Map<String, Object> receipt, String id, String scope, String originScope, String originId, boolean success) {
        assertTrue(((String)receipt.get("sid")).matches("p[1-9a-z][0-9a-z]*"));
        assertEquals(id, store.resolveHandle("save", (String)receipt.get("sid"))); assertEquals(scope, store.resolveHandle("scope", (String)receipt.get("s")));
        assertEquals(originScope, store.resolveHandle("scope", (String)receipt.get("src_s"))); assertEquals(originId, receipt.get("src_id"));
        assertEquals(success, receipt.get("success")); assertEquals(TIME, receipt.get("at"));
        assertEquals(1, ((Number) receipt.get("slot")).intValue());
        assertEquals(7, receipt.size());
    }
    private static Connection connect(Path path) throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + path); }
    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) { return rows.next() ? rows.getString(1) : null; }
    }

    private static final class FakeGame implements MachineSession.GamePort {
        GameController.State state = state(SCOPE, "v1", "player_ready");
        final ConcurrentLinkedQueue<GameController.SaveResult> saves = new ConcurrentLinkedQueue<>();
        Consumer<String> onStart = ignored -> {};
        CompletableFuture<GameController.State> next;
        RuntimeException failure;
        String plannedRun;
        int starts, observations;
        boolean exited;
        @Override public GameController.State latest() { return state; }
        @Override public CompletableFuture<GameController.State> observe() { observations++; return CompletableFuture.completedFuture(state); }
        @Override public CompletableFuture<GameController.State> execute(String version, Map<String, Object> args) { return CompletableFuture.completedFuture(state); }
        @Override public GameController.Execution start(String version, Map<String, Object> args, String requestId) {
            starts++; onStart.accept(requestId);
            if (failure != null) throw failure;
            CompletableFuture<GameController.State> future = next == null ? CompletableFuture.completedFuture(state) : next;
            next = null;
            return new GameController.Execution(future, future);
        }
        @Override public void prepareRun(String id) { plannedRun = id; }
        @Override public GameController.SaveResult pollSave() { return saves.poll(); }
        @Override public boolean exiting() { return false; }
        @Override public boolean disposed() { return exited; }
        @Override public void exitNow() { exited = true; }
    }

    private static final class InspectingOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Runnable beforeFirstWrite;
        Throwable probeFailure;
        private void inspect() {
            Runnable probe = beforeFirstWrite; beforeFirstWrite = null;
            if (probe != null) try { probe.run(); } catch (Throwable failure) { probeFailure = failure; }
        }
        @Override public synchronized void write(int value) { inspect(); bytes.write(value); }
        @Override public synchronized void write(byte[] buffer, int offset, int count) { inspect(); bytes.write(buffer, offset, count); }
        synchronized String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }

    private static final class Harness implements AutoCloseable {
        final AuditStore store;
        final FakeGame game = new FakeGame();
        final InspectingOutput output = new InspectingOutput();
        final MachineSession session;
        Harness(Path path) throws Exception {
            store = new AuditStore(path);
            store.beginSession(UUID.randomUUID().toString(),"fixture-build","CLI.7.0.0",7);
            store.ensureScope(SCOPE, "run", "receipt-fixture");
            session = new MachineSession(store, game, new PrintStream(output, true, "UTF-8"), 30);
        }
        void action(String id, String action) throws Exception { query(id, "action.execute", map("action", action)); }
        void query(String id, String op, Map<String, Object> args) throws Exception {
            send(map("protocol_version", 7, "scope_id", game.state.scopeId, "id", id, "op", op, "state_version", game.state.version, "args", args));
        }
        void send(Map<String, Object> request) throws Exception { session.accept(V7Requests.encode(store, request)).get(5, TimeUnit.SECONDS); }
        List<String> lines() { List<String> lines = new ArrayList<>(); for (String line : output.text().split("\\R")) if (!line.isEmpty()) lines.add(line); return lines; }
        Map<String, Object> last() { List<String> lines = lines(); assertFalse(lines.isEmpty()); return JsonCodec.decode(lines.get(lines.size() - 1)); }
        String firstWireFor(String id) { for (String line : lines()) if (id.equals(JsonCodec.decode(line).get("id"))) return line; throw new AssertionError("No response for " + id); }
        int wireCount(String id) { int count = 0; for (String line : lines()) if (id.equals(JsonCodec.decode(line).get("id"))) count++; return count; }
        String exchangeResponse(String scope, String id) throws Exception {
            try (Connection db = connect(store.publicDatabase()); java.sql.PreparedStatement statement = db.prepareStatement("SELECT response_json FROM exchanges WHERE scope_id=? AND id=? ORDER BY sequence LIMIT 1")) {
                statement.setString(1, scope); statement.setString(2, id);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
            }
        }
        @Override public void close() { session.close(); store.endSession("CLOSED", "fixture complete"); store.close(); }
    }
}
