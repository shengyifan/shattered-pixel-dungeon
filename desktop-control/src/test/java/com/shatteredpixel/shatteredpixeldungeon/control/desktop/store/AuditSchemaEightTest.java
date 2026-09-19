package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class AuditSchemaEightTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void everyOlderSchemaIsRejectedBeforeAnyDatabaseOrSidecarChanges() throws Exception {
        for (int version = 1; version <= 7; version++) {
            Path root = temporary.newFolder("schema-" + version).toPath();
            for (String name : new String[]{"public", "internal"}) {
                try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve(name + ".sqlite3"));
                     Statement statement = db.createStatement()) {
                    statement.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
                    statement.execute("INSERT INTO metadata VALUES('profile_id','old-profile'),('schema_version','" + version + "')");
                    statement.execute("CREATE TABLE old_evidence(value TEXT)");
                    statement.execute("INSERT INTO old_evidence VALUES('must remain byte-for-byte unchanged')");
                }
            }
            assertRejectedWithoutChanges(root);
        }
    }

    @Test public void completeSchemaSevenPairIsRejectedWithoutRecoveringItsPendingRequests() throws Exception {
        Path root = temporary.newFolder("complete-schema-seven").toPath();
        try (AuditStore store = new AuditStore(root)) {
            String scope = store.menuScope();
            AuditStore.Attempt pending = store.begin(scope, "pending-old-action", "wait", "old-wire-evidence");
            store.markExecuting(pending, "old-revision", map("public", "before"), map("private", "before"));
        }
        // Schema 7 has the same physical tables; only its version establishes the protocol boundary.
        for (String name : new String[]{"public", "internal"}) {
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve(name + ".sqlite3"));
                 Statement statement = db.createStatement()) {
                statement.execute("UPDATE metadata SET value='7' WHERE key='schema_version'");
            }
        }
        assertRejectedWithoutChanges(root);
        for (String name : new String[]{"public", "internal"}) {
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve(name + ".sqlite3").toUri().toASCIIString() + "?mode=ro&immutable=1");
                 Statement statement = db.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT status,response_json,raw_request FROM requests")) {
                assertTrue(rows.next()); assertEquals("EXECUTING", rows.getString(1));
                assertNull(rows.getString(2)); assertEquals("old-wire-evidence", rows.getString(3));
            }
        }
    }

    @Test public void currentSchemaPairChecksPreserveIncompleteAndMismatchedFiles() throws Exception {
        Path one = temporary.newFolder("first-current-pair").toPath();
        Path two = temporary.newFolder("second-current-pair").toPath();
        try (AuditStore ignored = new AuditStore(one); AuditStore another = new AuditStore(two)) { }
        Files.copy(two.resolve("internal.sqlite3"), one.resolve("internal.sqlite3"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertRejectedWithoutChanges(one, "AUDIT_PAIR_MISMATCH");
        Files.delete(two.resolve("internal.sqlite3"));
        assertRejectedWithoutChanges(two, "AUDIT_PAIR_MISSING");
    }

    @Test public void freshSchemaEightSeparatesRequestExecutionFromWirePresentation() throws Exception {
        Path root = temporary.newFolder().toPath();
        try (AuditStore store = new AuditStore(root)) {
            String scope = store.menuScope();
            AuditStore.Attempt attempt = store.begin(scope, "partial", "action.execute", "{}");
            store.markExecuting(attempt, "v1", map("before", true), map("private", "before"));
            Map<String,Object> partial = map("v", 5L, "st", "completed", "pres", map("st", "partial"));
            store.complete(attempt, "COMPLETED", partial, map("canonical", "unrendered"), map("private", "after"), null);
            store.event(scope, "game.log", map("entries", java.util.Collections.emptyList(), "presentation", map("status", "partial")));
            assertEquals("COMPLETED", store.getRequest(scope, "partial").get("status"));
            assertEquals("partial", store.getRequest(scope, "partial").get("presentation_status"));
            assertEquals(partial, store.getRequest(scope, "partial").get("response"));
            assertEquals(map("canonical", "unrendered"), store.getRequest(scope, "partial").get("after_snapshot"));
            for (Path file : new Path[]{store.publicDatabase(), store.internalDatabase()}) {
                try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file); Statement statement = db.createStatement()) {
                    try (ResultSet rows = statement.executeQuery("SELECT value FROM metadata WHERE key='schema_version'")) {
                        assertTrue(rows.next()); assertEquals("8", rows.getString(1));
                    }
                    for (String table : new String[]{"requests", "exchanges", "events"}) {
                        try (ResultSet rows = statement.executeQuery("SELECT presentation_status FROM " + table)) {
                            assertTrue(rows.next()); assertEquals("partial", rows.getString(1));
                        }
                    }
                }
            }
        }
        try (AuditStore reopened = new AuditStore(root)) {
            assertEquals("partial", reopened.getRequest(reopened.menuScope(), "partial").get("presentation_status"));
        }
    }

    @Test public void aSchemaEightMarkerCannotRecreateMissingAuditTables() throws Exception {
        Path root=temporary.newFolder().toPath();
        try(AuditStore ignored=new AuditStore(root)) { }
        for(String name:new String[]{"public","internal"})
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve(name+".sqlite3"));Statement statement=db.createStatement()) {
                statement.execute("DROP TABLE requests");
            }
        assertRejectedWithoutChanges(root);
    }

    @Test public void pendingWireAndTerminalRequestKeepTheirOwnPresentationStatus() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder().toPath())) {
            String scope = store.menuScope();
            AuditStore.Attempt attempt = store.begin(scope, "pending", "action.execute", "{}");
            store.markExecuting(attempt, "v1", map(), map());
            Map<String,Object> first = map("v", 5L, "st", "in_progress", "pres", map("st", "partial"));
            store.respondPending(attempt, first, map(), map());
            Map<String,Object> last = map("v", 5L, "st", "completed");
            store.settle(attempt, "COMPLETED", last, map(), map(), null);
            assertEquals(last, store.getRequest(scope, "pending").get("response"));
            assertEquals("complete", store.getRequest(scope, "pending").get("presentation_status"));
            assertEquals("partial", store.history(scope, 0, 10).get(0).get("presentation_status"));
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + store.publicDatabase()); Statement statement = db.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT response_json FROM exchanges")) {
                assertTrue(rows.next()); assertEquals(com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec.encode(first), rows.getString(1));
            }
        }
    }

    static void assertRejectedWithoutChanges(Path root) throws Exception {
        assertRejectedWithoutChanges(root, "AUDIT_SCHEMA_UNSUPPORTED");
    }

    private static void assertRejectedWithoutChanges(Path root, String code) throws Exception {
        Map<String,byte[]> before = files(root);
        Map<String,java.nio.file.attribute.FileTime> times = modifiedTimes(root);
        AuditException preflight = assertThrows(AuditException.class, () -> AuditStore.preflight(root));
        assertEquals(code, preflight.code);
        AuditException open = assertThrows(AuditException.class, () -> new AuditStore(root));
        assertEquals(code, open.code);
        Map<String,byte[]> after = files(root);
        assertEquals(before.keySet(), after.keySet());
        for (String file : before.keySet()) assertArrayEquals(file, before.get(file), after.get(file));
        assertEquals("Rejected prior-generation profiles must not change file or directory timestamps", times, modifiedTimes(root));
    }

    private static Map<String,java.nio.file.attribute.FileTime> modifiedTimes(Path root) throws Exception {
        Map<String,java.nio.file.attribute.FileTime> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : (Iterable<Path>) paths.sorted()::iterator)
                result.put(root.relativize(file).toString(), Files.getLastModifiedTime(file));
        }
        return result;
    }

    private static Map<String,byte[]> files(Path root) throws Exception {
        Map<String,byte[]> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile).sorted()::iterator)
                result.put(root.relativize(file).toString(), Files.readAllBytes(file));
        }
        return result;
    }
}
