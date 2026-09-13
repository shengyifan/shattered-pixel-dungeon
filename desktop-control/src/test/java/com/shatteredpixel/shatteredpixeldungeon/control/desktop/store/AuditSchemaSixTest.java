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

public class AuditSchemaSixTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void everyOlderSchemaIsRejectedBeforeAnyDatabaseOrSidecarChanges() throws Exception {
        for (int version = 1; version <= 5; version++) {
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

    @Test public void freshSchemaSixSeparatesRequestExecutionFromWirePresentation() throws Exception {
        Path root = temporary.newFolder().toPath();
        try (AuditStore store = new AuditStore(root)) {
            String scope = store.menuScope();
            AuditStore.Attempt attempt = store.begin(scope, "partial", "action.execute", "{}");
            store.markExecuting(attempt, "v1", map("before", true), map("private", "before"));
            Map<String,Object> partial = map("v", 3L, "st", "completed", "pres", map("st", "partial"));
            store.complete(attempt, "COMPLETED", partial, map("canonical", "unrendered"), map("private", "after"), null);
            store.event(scope, "game.log", map("entries", java.util.Collections.emptyList(), "presentation", map("status", "partial")));
            assertEquals("COMPLETED", store.getRequest(scope, "partial").get("status"));
            assertEquals("partial", store.getRequest(scope, "partial").get("presentation_status"));
            assertEquals(partial, store.getRequest(scope, "partial").get("response"));
            assertEquals(map("canonical", "unrendered"), store.getRequest(scope, "partial").get("after_snapshot"));
            for (Path file : new Path[]{store.publicDatabase(), store.internalDatabase()}) {
                try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file); Statement statement = db.createStatement()) {
                    try (ResultSet rows = statement.executeQuery("SELECT value FROM metadata WHERE key='schema_version'")) {
                        assertTrue(rows.next()); assertEquals("6", rows.getString(1));
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

    @Test public void aSchemaSixMarkerCannotRecreateMissingAuditTables() throws Exception {
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
            Map<String,Object> first = map("v", 3L, "st", "in_progress", "pres", map("st", "partial"));
            store.respondPending(attempt, first, map(), map());
            Map<String,Object> last = map("v", 3L, "st", "completed");
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
        Map<String,byte[]> before = files(root);
        AuditException preflight = assertThrows(AuditException.class, () -> AuditStore.preflight(root));
        assertEquals("AUDIT_SCHEMA_UNSUPPORTED", preflight.code);
        AuditException open = assertThrows(AuditException.class, () -> new AuditStore(root));
        assertEquals("AUDIT_SCHEMA_UNSUPPORTED", open.code);
        Map<String,byte[]> after = files(root);
        assertEquals(before.keySet(), after.keySet());
        for (String file : before.keySet()) assertArrayEquals(file, before.get(file), after.get(file));
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
