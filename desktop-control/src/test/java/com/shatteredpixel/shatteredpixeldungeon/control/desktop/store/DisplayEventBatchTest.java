package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

import static org.junit.Assert.*;

public class DisplayEventBatchTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void mixedKindsKeepExactJsonOrderAndPrivateOriginalLinksInOneCommit() throws Exception {
        Path root = temporary.newFolder("display-batch-fixture").toPath();
        String privateText = "PRIVATE_DISPLAY_SENTINEL: \"original\"\\line\n";
        try (AuditStore store = new AuditStore(root)) {
            String session = store.beginSession("batch-boot", "batch-fixture", "CLI.7.0.1", 7);
            long after = Long.parseLong(scalar(store.publicDatabase(), "SELECT max(sequence) FROM events"));
            BigInteger before = generation(store.publicDatabase());
            assertDurability(store);
            String[] kinds = {"game.log", "game.floating_text", "game.visual", "game.visual_metrics",
                    "game.banner", "game.screen", "game.visual"};
            List<AuditStore.DisplayEventWrite> batch = new ArrayList<>();
            List<String> expectedJson = new ArrayList<>();
            List<Map<String, Object>> originals = new ArrayList<>();
            for (int i = 0; i < kinds.length; i++) {
                Map<String, Object> data = Values.map("text", "Visible \"text\"\\line\n", "number", i,
                        "nested", Values.list(Values.map("cell", 17, "color", 0xff8800), null, false));
                if (i == 4) data.put("presentation", Values.map("status", "partial",
                        "diagnostics", Values.list(Values.map("path", "text", "reason", "fixture diagnostic"))));
                Map<String, Object> original = i == 0 || i == 1 || i == 4
                        ? Values.map("text", privateText, "tokens", Values.list("first", null, "last")) : null;
                expectedJson.add(JsonCodec.encode(data));
                originals.add(original);
                batch.add(new AuditStore.DisplayEventWrite(i % 2 == 0 ? "run:alpha" : "run:beta", kinds[i], data, original));
            }
            // Repeated identical writes remain independent display events.
            batch.add(batch.get(2));
            expectedJson.add(expectedJson.get(2));
            originals.add(null);
            store.displayEvents(batch);

            List<Map<String, Object>> events = eventRows(store.publicDatabase(), after);
            assertEquals(batch.size(), events.size());
            assertEquals(events, eventRows(store.internalDatabase(), after));
            List<Map<String, Object>> logs = originalRows(store.internalDatabase());
            assertEquals(3, logs.size());
            int originalIndex = 0;
            for (int i = 0; i < batch.size(); i++) {
                Map<String, Object> event = events.get(i);
                assertEquals(after + i + 1, event.get("sequence"));
                assertEquals(batch.get(i).scopeId, event.get("scope_id"));
                assertEquals(batch.get(i).kind, event.get("kind"));
                assertEquals(expectedJson.get(i), event.get("data_json"));
                assertEquals(session, event.get("session_id"));
                assertEquals(i == 4 ? "partial" : "complete", event.get("presentation_status"));
                assertNotNull(event.get("created_at"));
                if (originals.get(i) != null) {
                    Map<String, Object> log = logs.get(originalIndex++);
                    assertEquals(session, log.get("session_id"));
                    assertEquals(JsonCodec.encode(Values.map("event_sequence", event.get("sequence"),
                            "scope_id", batch.get(i).scopeId, "kind", batch.get(i).kind,
                            "original_display", originals.get(i))), log.get("text"));
                }
            }
            assertFalse(JsonCodec.encode(events).contains("PRIVATE_DISPLAY_SENTINEL"));
            assertFalse(JsonCodec.encode(store.events("run:alpha", 0, 100)).contains("PRIVATE_DISPLAY_SENTINEL"));
            assertFalse(JsonCodec.encode(store.events("run:beta", 0, 100)).contains("PRIVATE_DISPLAY_SENTINEL"));
            assertEquals("0", scalar(store.publicDatabase(), "SELECT count(*) FROM sqlite_master WHERE name='logs'"));
            assertGeneration(store, before.add(BigInteger.ONE));
            assertDurability(store);
        }
        assertFalse(new String(Files.readAllBytes(root.resolve("public.sqlite3")), StandardCharsets.ISO_8859_1)
                .contains("PRIVATE_DISPLAY_SENTINEL"));
    }

    @Test public void constructionFreezesNestedPublicAndOriginalData() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder("immutable-display-fixture").toPath())) {
            Map<String, Object> nested = Values.map("color", 123, "cell", 42);
            List<Object> positions = new ArrayList<>(Arrays.asList(nested, null));
            Map<String, Object> data = Values.map("text", "Before", "positions", positions);
            Map<String, Object> originalNested = Values.map("text", "PRIVATE_BEFORE");
            List<Object> originalParts = new ArrayList<>(Arrays.asList(originalNested, "second"));
            Map<String, Object> original = Values.map("parts", originalParts);
            String dataJson = JsonCodec.encode(data), originalJson = JsonCodec.encode(original);
            AuditStore.DisplayEventWrite event = new AuditStore.DisplayEventWrite("run:frozen", "game.log", data, original);
            data.put("text", "After");
            nested.put("cell", 999);
            positions.clear();
            originalNested.put("text", "PRIVATE_AFTER");
            originalParts.clear();
            original.put("extra", true);

            store.displayEvents(Collections.singletonList(event));

            List<Map<String, Object>> events = eventRows(store.publicDatabase(), 0);
            assertEquals(1, events.size());
            assertEquals(dataJson, events.get(0).get("data_json"));
            assertEquals(events, eventRows(store.internalDatabase(), 0));
            Map<String, Object> diagnostic = JsonCodec.decode((String) originalRows(store.internalDatabase()).get(0).get("text"));
            assertEquals(originalJson, JsonCodec.encode(diagnostic.get("original_display")));
            assertEquals(events.get(0).get("sequence"), diagnostic.get("event_sequence"));
        }
    }

    @Test public void privateLogFailureRollsBackTheEntireBatchAndBothGenerations() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder("rollback-display-fixture").toPath())) {
            store.event("run:existing", "fixture.baseline", Values.map("baseline", true));
            List<Map<String, Object>> baseline = eventRows(store.publicDatabase(), 0);
            BigInteger before = generation(store.publicDatabase());
            try (Connection db = connect(store.internalDatabase()); Statement statement = db.createStatement()) {
                statement.execute("CREATE TRIGGER reject_display_original BEFORE INSERT ON logs "
                        + "WHEN NEW.text LIKE '%\"kind\":\"game.banner\"%' "
                        + "BEGIN SELECT RAISE(ABORT,'injected display log failure'); END");
            }
            List<AuditStore.DisplayEventWrite> batch = Arrays.asList(
                    new AuditStore.DisplayEventWrite("run:new-one", "game.visual", Values.map("step", 1), null),
                    new AuditStore.DisplayEventWrite("run:new-two", "game.log", Values.map("step", 2), Values.map("text", "PRIVATE_LOG")),
                    new AuditStore.DisplayEventWrite("run:new-one", "game.banner", Values.map("step", 3), Values.map("text", "PRIVATE_BANNER")));

            assertThrows(AuditException.class, () -> store.displayEvents(batch));

            assertEquals(baseline, eventRows(store.publicDatabase(), 0));
            assertEquals(baseline, eventRows(store.internalDatabase(), 0));
            assertTrue(originalRows(store.internalDatabase()).isEmpty());
            assertFalse(store.hasScope("run:new-one"));
            assertFalse(store.hasScope("run:new-two"));
            for (Path database : Arrays.asList(store.publicDatabase(), store.internalDatabase())) {
                assertEquals("0", scalar(database, "SELECT count(*) FROM scopes WHERE scope_id IN ('run:new-one','run:new-two')"));
                assertEquals("0", scalar(database, "SELECT count(*) FROM runs WHERE scope_id IN ('run:new-one','run:new-two')"));
            }
            assertGeneration(store, before);
            assertDurability(store);
            try (Connection db = connect(store.internalDatabase()); Statement statement = db.createStatement()) {
                statement.execute("DROP TRIGGER reject_display_original");
            }

            store.displayEvents(batch);

            assertGeneration(store, before.add(BigInteger.ONE));
            assertEquals(baseline.size() + batch.size(), eventRows(store.publicDatabase(), 0).size());
            assertEquals(eventRows(store.publicDatabase(), 0), eventRows(store.internalDatabase(), 0));
            assertEquals(2, originalRows(store.internalDatabase()).size());
            assertDurability(store);
        }
    }

    @Test public void emptyAndInvalidBatchesDoNotStartTransactions() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder("empty-display-fixture").toPath())) {
            BigInteger before = generation(store.publicDatabase());
            store.displayEvents(Collections.emptyList());
            assertThrows(IllegalArgumentException.class, () -> store.displayEvents(null));
            AuditStore.DisplayEventWrite first = new AuditStore.DisplayEventWrite("run:untouched", "game.log", Values.map("text", "Visible"), null);
            assertThrows(IllegalArgumentException.class, () -> store.displayEvents(Arrays.asList(first, null)));
            assertGeneration(store, before);
            assertFalse(store.hasScope("run:untouched"));
            assertTrue(eventRows(store.publicDatabase(), 0).isEmpty());
            assertTrue(eventRows(store.internalDatabase(), 0).isEmpty());
            assertTrue(originalRows(store.internalDatabase()).isEmpty());
            assertDurability(store);
        }
    }

    private static List<Map<String, Object>> eventRows(Path database, long after) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection db = connect(database); Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery("SELECT sequence,scope_id,kind,data_json,session_id,presentation_status,created_at "
                     + "FROM events WHERE sequence>" + after + " ORDER BY sequence")) {
            while (rows.next()) result.add(Values.map("sequence", rows.getLong(1), "scope_id", rows.getString(2),
                    "kind", rows.getString(3), "data_json", rows.getString(4), "session_id", rows.getString(5),
                    "presentation_status", rows.getString(6), "created_at", rows.getString(7)));
        }
        return result;
    }

    private static List<Map<String, Object>> originalRows(Path database) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection db = connect(database); Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery("SELECT text,session_id FROM logs WHERE channel='displayed_text_original' ORDER BY sequence")) {
            while (rows.next()) result.add(Values.map("text", rows.getString(1), "session_id", rows.getString(2)));
        }
        return result;
    }

    private static BigInteger generation(Path database) throws Exception {
        return new BigInteger(scalar(database, "SELECT value FROM metadata WHERE key='pair_generation'"));
    }

    private static void assertGeneration(AuditStore store, BigInteger expected) throws Exception {
        assertEquals(expected, generation(store.publicDatabase()));
        assertEquals(expected, generation(store.internalDatabase()));
    }

    private static String scalar(Path database, String sql) throws Exception {
        try (Connection db = connect(database)) { return scalar(db, sql); }
    }

    private static String scalar(Connection db, String sql) throws Exception {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static Connection connect(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static void assertDurability(AuditStore store) throws Exception {
        // synchronous and fullfsync are connection-local: inspecting a new reader would prove nothing about the writer.
        Field field = AuditStore.class.getDeclaredField("writer");
        field.setAccessible(true);
        Connection writer = (Connection) field.get(store);
        for (String schema : Arrays.asList("main", "internal")) {
            assertEquals("delete", scalar(writer, "PRAGMA " + schema + ".journal_mode"));
            assertEquals("3", scalar(writer, "PRAGMA " + schema + ".synchronous"));
        }
        assertEquals("1", scalar(writer, "PRAGMA fullfsync"));
        assertTrue(writer.getAutoCommit());
    }
}
