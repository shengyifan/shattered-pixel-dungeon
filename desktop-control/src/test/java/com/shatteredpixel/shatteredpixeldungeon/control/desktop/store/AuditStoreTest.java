package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class AuditStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void duplicateQueriesAndActionsConsumeOneIdentityButKeepEveryExchange() throws Exception {
        Path root = temporary.newFolder("中文 profile").toPath();
        String menu;
        try (AuditStore store = new AuditStore(root)) {
            menu = store.menuScope();
            AuditStore.Attempt original = store.begin(menu, "same-id", "state.get", "{ \"id\": \"same-id\" }");
            store.markExecuting(original, "boot:1", Values.map("hp", 20), Values.map("hp", 20, "hidden", "SECRET_SENTINEL"));
            store.complete(original, "COMPLETED", Values.map("id", "same-id", "ok", true),
                    Values.map("hp", 20), Values.map("hp", 20, "hidden", "SECRET_SENTINEL"), null);
            AuditStore.Attempt repeated = store.begin(menu, "same-id", "move", "{\"different\":true}");
            assertTrue(repeated.duplicate);
            assertFalse(repeated.registered);
            store.complete(repeated, "REJECTED", Values.map("id", "same-id", "error", "DUPLICATE_ID"),
                    Values.map("hp", 20), Values.map("hidden", "OTHER_PRIVATE_DATA"), null);
            Map<String, Object> request = store.getRequest(menu, "same-id");
            assertEquals("state.get", request.get("op"));
            assertEquals("COMPLETED", request.get("status"));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) request.get("response")).get("ok"));
            List<Map<String, Object>> history = store.history(menu, 0, 20);
            assertEquals(2, history.size());
            assertEquals("{ \"id\": \"same-id\" }", exchangeText(store, original.exchangeId, "raw_request"));
            assertFalse(history.get(0).containsKey("raw_request"));
            assertFalse(history.get(0).containsKey("response"));
            assertEquals(Boolean.TRUE, history.get(1).get("duplicate"));
            assertFalse(JsonCodec.encode(history).contains("SECRET_SENTINEL"));
            assertFalse(JsonCodec.encode(request).contains("PRIVATE"));
        }
        try (AuditStore reopened = new AuditStore(root)) {
            assertEquals(menu, reopened.menuScope());
            assertTrue(reopened.begin(menu, "same-id", "state.get", "{}").duplicate);
        }
    }

    @Test public void scopesAreExplicitAndDoNotFollowTheCurrentSlot() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            store.ensureScope("run:one", "run", "one");
            store.ensureScope("run:two", "run", "two");
            AuditStore.Attempt first = store.begin("run:one", "1", "move", "{}");
            store.complete(first, "REJECTED", Values.map("error", "STALE_STATE"), Values.map("scene", "one"), Values.map("full", true), null);
            assertTrue(store.begin("run:one", "1", "move", "{}").duplicate);
            assertFalse(store.begin("run:two", "1", "move", "{}").duplicate);
            assertFalse(store.hasScope("run:made-up"));
            store.begin("run:made-up", "invalid", "move", "{}");
            assertFalse("Recording a rejected identity must not create a scope", store.hasScope("run:made-up"));
        }
    }

    @Test public void restartRecoversUnknownWithoutReplayingOrInventingAfterSnapshots() throws Exception {
        Path root = temporary.newFolder().toPath();
        try (AuditStore store = new AuditStore(root)) {
            AuditStore.Attempt accepted = store.begin("run:a", "action", "move", "{}");
            store.markExecuting(accepted, "old-boot:1", Values.map("hp", 12), Values.map("rng", 123));
            store.begin("run:a", "queued", "state.get", "{}");
        }
        try (AuditStore store = new AuditStore(root)) {
            assertEquals(2, store.recoverInterrupted());
            assertEquals(0, store.recoverInterrupted());
            Map<String, Object> unknown = store.getRequest("run:a", "action");
            assertEquals("UNKNOWN", unknown.get("status"));
            String publicScope=store.publicHandle("scope","run:a");
            assertEquals("run:a",store.resolveHandle("scope",publicScope));
            assertEquals(Values.map("v", 6L, "id", "action", "s", publicScope, "err", "UNKNOWN"), unknown.get("response"));
            assertEquals(Values.map("v", 6L, "id", "queued", "s", publicScope, "err", "NOT_EXECUTED"),
                    store.getRequest("run:a", "queued").get("response"));
            assertNull(unknown.get("after_snapshot"));
            assertNotNull(unknown.get("before_snapshot"));
            assertEquals("NOT_EXECUTED", store.getRequest("run:a", "queued").get("status"));
            assertTrue(store.begin("run:a", "action", "move", "{}").duplicate);
            assertNull("No response was sent on the crashed connection", exchangeText(store, 1, "response_json"));
        }
    }

    @Test public void pairedWritesRollbackIfTheInternalInsertFails() throws Exception {
        Path root = temporary.newFolder().toPath();
        try (AuditStore store = new AuditStore(root)) {
            try (Connection internal = connect(root.resolve("internal.sqlite3")); Statement s = internal.createStatement()) {
                s.execute("CREATE TRIGGER reject_requests BEFORE INSERT ON requests BEGIN SELECT RAISE(ABORT,'injected private failure'); END");
            }
            assertThrows(AuditException.class, () -> store.begin("run:a", "a", "move", "ORIGINAL_INPUT"));
            assertNull(store.getRequest("run:a", "a"));
            assertTrue(store.history("run:a", 0, 10).isEmpty());
            try (Connection internal = connect(root.resolve("internal.sqlite3")); Statement s = internal.createStatement()) {
                s.execute("DROP TRIGGER reject_requests");
            }
            assertTrue(store.begin("run:a", "a", "move", "{}").registered);
        }
    }

    @Test public void fullSnapshotsAndRawExceptionsStayLosslessAndPrivate() throws Exception {
        Path root = temporary.newFolder().toPath();
        String privateMarker = "PRIVATE_SECRET_SENTINEL";
        Map<String, Object> privateSnapshot = Values.map("hidden", privateMarker, "large", new BigInteger("999999999999999999999999999999"),
                "nested", Values.list(Values.map("name", "未鉴定\\药水\n😀"), null));
        try (AuditStore store = new AuditStore(root)) {
            AuditStore.Attempt attempt = store.begin(store.menuScope(), "error", "inspect", "{}\n");
            store.markExecuting(attempt, "version", Values.map("text", "未知药水"), privateSnapshot);
            store.complete(attempt, "FAILED", Values.map("error", "GAME_ERROR"), Values.map("text", "未知药水"), privateSnapshot,
                    new IllegalStateException(privateMarker, new IllegalArgumentException("nested-private-cause")));
            assertFalse(JsonCodec.encode(store.getRequest(store.menuScope(), "error")).contains(privateMarker));
            try (Connection internal = connect(root.resolve("internal.sqlite3")); Statement s = internal.createStatement()) {
                try (ResultSet rs = s.executeQuery("SELECT b.body FROM snapshots s JOIN snapshot_blobs b ON b.content_id=s.content_id")) {
                    assertTrue(rs.next());
                    assertEquals(privateSnapshot, SnapshotCodec.decodeInternal(rs.getBytes(1), id -> internalBlock(internal, id)));
                }
                try (ResultSet rs = s.executeQuery("SELECT stack_trace FROM exceptions")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getString(1).contains(privateMarker));
                    assertTrue(rs.getString(1).contains("nested-private-cause"));
                }
            }
            try (Connection publicDb = connect(root.resolve("public.sqlite3")); Statement s = publicDb.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='exceptions'")) {
                assertTrue(rs.next()); assertEquals(0, rs.getInt(1));
            }
        }
        assertFalse(new String(Files.readAllBytes(root.resolve("public.sqlite3")), StandardCharsets.ISO_8859_1).contains(privateMarker));
    }

    @Test public void malformedInputIsRecordedWithoutAnInventedCallerIdentity() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            AuditStore.Attempt invalid = store.begin(store.menuScope(), null, null, "[bad JSON");
            assertFalse(invalid.registered);
            store.complete(invalid, "REJECTED", Values.map("id", null, "error", "INVALID_JSON"), null, null, null);
            assertEquals("[bad JSON", exchangeText(store, invalid.exchangeId, "raw_request"));
        }
    }

    @Test public void pendingResponseAndLaterResultAreDifferentRecords() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            AuditStore.Attempt attempt = store.begin("run:a", "slow", "move", "{}");
            store.markExecuting(attempt, "boot:1", Values.map("position", 1), Values.map("position", 1, "private", 3));
            store.respondPending(attempt, Values.map("id", "slow", "status", "in_progress"),
                    Values.map("position", 1), Values.map("position", 1, "private", 4));
            store.markOutputAttempt(attempt, true);
            assertEquals("EXECUTING", store.getRequest("run:a", "slow").get("status"));
            store.settle(attempt, "COMPLETED", Values.map("id", "slow", "status", "completed"),
                    Values.map("position", 2), Values.map("position", 2, "private", 5), null);
            assertEquals("COMPLETED", store.getRequest("run:a", "slow").get("status"));
            Map<String, Object> exchange = store.history("run:a", 0, 10).get(0);
            assertEquals("in_progress", JsonCodec.decode(exchangeText(store, attempt.exchangeId, "response_json")).get("status"));
            assertEquals(Boolean.TRUE, exchange.get("output_succeeded"));
            assertFalse(exchange.containsKey("after_snapshot"));
            assertEquals(2L, ((Map<?, ?>) store.getRequest("run:a", "slow").get("after_snapshot")).get("position"));
            assertThrows(AuditException.class, () -> store.settle(attempt, "COMPLETED", Values.map("status", "again"), null, null, null));
        }
    }

    @Test public void targetsPromotionEventsAndPrivateConsoleAreDurable() throws Exception {
        Path root = temporary.newFolder().toPath();
        try (AuditStore store = new AuditStore(root)) {
            String menu = store.menuScope();
            AuditStore.Attempt start = store.begin(menu, "start", "action.execute", "{}");
            store.ensureScope("run:new", "planned", "new");
            store.linkTarget(start, "run:new");
            store.markExecuting(start, "boot:1", Values.map("scene", "menu"), Values.map("planned_run_id", "new"));
            store.ensureScope("run:new", "run", "new");
            assertEquals("run:new", store.getRequest(menu, "start").get("target_scope"));
            assertThrows(AuditException.class, () -> store.ensureScope("run:new", "run", "different"));
            store.event("run:new", "loaded", Values.map("slot", 1));
            store.recordSave("run:new", 1, false, new IllegalStateException("PRIVATE_SAVE_ERROR"));
            store.recordLog("stderr", "PRIVATE_CONSOLE_LINE\n");
            List<Map<String, Object>> events = store.events("run:new", 0, 10);
            assertEquals(2, events.size());
            assertEquals(Boolean.FALSE, ((Map<?, ?>) events.get(1).get("data")).get("success"));
            assertFalse(JsonCodec.encode(events).contains("PRIVATE"));
            try (Connection db = connect(root.resolve("internal.sqlite3")); Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT text FROM logs")) {
                assertTrue(rs.next()); assertEquals("PRIVATE_CONSOLE_LINE\n", rs.getString(1));
            }
        }
    }

    @Test public void detectsRestoringOnlyOneOlderFileFromTheSameProfile() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path backup = temporary.getRoot().toPath().resolve("old-internal.sqlite3");
        try (AuditStore ignored = new AuditStore(root)) { }
        Files.copy(root.resolve("internal.sqlite3"), backup);
        try (AuditStore store = new AuditStore(root)) { store.event(store.menuScope(), "changed", Values.map("public", true)); }
        Files.copy(backup, root.resolve("internal.sqlite3"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(AuditException.class, () -> new AuditStore(root));
    }

    @Test public void snapshotsReuseCompressedBodiesWithoutExposingPrivateContentKeys() throws Exception {
        Path root = temporary.newFolder().toPath();
        Map<String, Object> visible = Values.map("visible", "known tile");
        Map<String, Object> secretOne = Values.map("tick", 1, "profile_files", Values.map("files", Values.list(Values.map("path", "game.dat", "body", "LONG_STABLE_FAKE_SAVE"))));
        Map<String, Object> secretTwo = Values.map("tick", 2, "profile_files", Values.map("files", Values.list(Values.map("path", "game.dat", "body", "LONG_STABLE_FAKE_SAVE"))));
        try (AuditStore store = new AuditStore(root)) {
            for (int i = 0; i < 2; i++) {
                AuditStore.Attempt attempt = store.begin("run:a", "snapshot-" + i, "state.get", "{}");
                store.complete(attempt, "COMPLETED", Values.map("ok", true), visible, i == 0 ? secretOne : secretTwo, null);
            }
            try (Connection publicDb = connect(store.publicDatabase()); Statement s = publicDb.createStatement()) {
                try (ResultSet rs = s.executeQuery("SELECT count(*),count(DISTINCT content_id) FROM snapshots")) {
                    assertTrue(rs.next()); assertEquals(2, rs.getInt(1)); assertEquals(1, rs.getInt(2));
                }
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM snapshot_blobs")) { assertTrue(rs.next()); assertEquals(1, rs.getInt(1)); }
            }
            try (Connection internal = connect(store.internalDatabase()); Statement s = internal.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM snapshot_blobs")) {
                assertTrue(rs.next()); assertEquals("Two different roots share one unchanged file block", 3, rs.getInt(1));
            }
            assertEquals(visible, store.getRequest("run:a", "snapshot-0").get("after_snapshot"));
        }
    }

    @Test public void rejectsVersionOneSnapshotsWithoutChangingEitherFile() throws Exception {
        Path root = temporary.newFolder().toPath();
        String id;
        try (AuditStore store = new AuditStore(root)) {
            AuditStore.Attempt attempt = store.begin("run:a", "old", "state.get", "{}");
            store.complete(attempt, "COMPLETED", Values.map("ok", true), Values.map("hp", 23), Values.map("secret", "legacy"), null);
            try (Connection db = connect(store.publicDatabase()); Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT after_snapshot FROM requests")) {
                assertTrue(rs.next()); id = rs.getString(1);
            }
        }
        for (String file : new String[]{"public.sqlite3", "internal.sqlite3"}) {
            try (Connection db = connect(root.resolve(file)); Statement s = db.createStatement()) {
                s.execute("DROP TABLE snapshots"); s.execute("DROP TABLE snapshot_blobs");
                s.execute("CREATE TABLE snapshots(snapshot_id TEXT PRIMARY KEY,encoding TEXT NOT NULL,body BLOB NOT NULL,created_at TEXT NOT NULL)");
                try (java.sql.PreparedStatement insert = db.prepareStatement("INSERT INTO snapshots VALUES(?,'json-utf8',?,'legacy')")) {
                    insert.setString(1, id); insert.setBytes(2, JsonCodec.encode(file.startsWith("public") ? Values.map("hp", 23) : Values.map("secret", "legacy")).getBytes(StandardCharsets.UTF_8)); insert.executeUpdate();
                }
                s.execute("UPDATE metadata SET value='1' WHERE key='schema_version'");
            }
        }
        AuditSchemaNineTest.assertRejectedWithoutChanges(root);
    }

    @Test public void realProcessCrashAfterAnExternalEffectIsUnknownAndNeverReplayable() throws Exception {
        Path root = temporary.newFolder().toPath();
        crashChild(root, "after-effect");
        try (AuditStore store = new AuditStore(root)) {
            assertEquals(1, store.recoverInterrupted());
            assertEquals("UNKNOWN", store.getRequest("run:a", "crash").get("status"));
            assertNull(store.getRequest("run:a", "crash").get("after_snapshot"));
            assertTrue(store.begin("run:a", "crash", "action.execute", "{}").duplicate);
            assertEquals("performed-once", Files.readString(root.resolve("fake-effect.txt")));
        }
    }

    @Test public void realProcessCrashAfterResultCommitPreservesResultWithoutInventingDelivery() throws Exception {
        Path root = temporary.newFolder().toPath();
        crashChild(root, "after-result");
        try (AuditStore store = new AuditStore(root)) {
            assertEquals(0, store.recoverInterrupted());
            assertEquals("COMPLETED", store.getRequest("run:a", "crash").get("status"));
            assertNotNull(store.getRequest("run:a", "crash").get("after_snapshot"));
            assertEquals(Boolean.FALSE, store.history("run:a", 0, 10).get(0).get("output_attempted"));
            assertTrue(store.begin("run:a", "crash", "action.execute", "{}").duplicate);
        }
    }

    @Test public void refusesMissingOrMismatchedDatabasePairs() throws Exception {
        Path one = temporary.newFolder("one").toPath();
        Path two = temporary.newFolder("two").toPath();
        try (AuditStore ignored = new AuditStore(one); AuditStore alsoIgnored = new AuditStore(two)) { }
        Files.copy(two.resolve("internal.sqlite3"), one.resolve("internal.sqlite3"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(AuditException.class, () -> new AuditStore(one));
        Files.delete(two.resolve("internal.sqlite3"));
        assertThrows(AuditException.class, () -> new AuditStore(two));
    }

    private Connection connect(Path path) throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + path); }

    private void crashChild(Path root, String phase) throws Exception {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        for (Class<?> type : new Class<?>[]{AuditCrashChild.class, AuditStore.class, JsonCodec.class, org.sqlite.JDBC.class}) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", String.join(java.io.File.pathSeparator, paths), AuditCrashChild.class.getName(), root.toString(), phase)
                .redirectErrorStream(true).start();
        if (!child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); fail("Crash fixture did not finish"); }
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 73, child.exitValue());
    }

    private byte[] internalBlock(Connection db, String contentId) {
        try (java.sql.PreparedStatement s = db.prepareStatement("SELECT body FROM snapshot_blobs WHERE content_id=?")) {
            s.setString(1, contentId);
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? rs.getBytes(1) : null; }
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private String exchangeText(AuditStore store, long sequence, String field) throws Exception {
        try (Connection db = connect(store.publicDatabase()); Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + field + " FROM exchanges WHERE sequence=" + sequence)) {
            assertTrue(rs.next()); return rs.getString(1);
        }
    }
}
