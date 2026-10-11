package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Isolated paired-store fixtures; never opens a real game or personal profile. */
public class AudioEventsSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void duplicateAlarmOccurrencesKeepScopeOrderAndRevisionWithoutUnsolicitedReplies() throws Exception {
        try (AuditStore store = new AuditStore(temporary.newFolder("audio-session-fixture").toPath())) {
            store.ensureScope("run:audio", "run", "audio");
            store.beginSession("audio-fixture", "audio-build", "CLI.9.0.4", 9);
            FakeGame game = new FakeGame();
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            try (MachineSession session = new MachineSession(store, game, new PrintStream(wire, true, "UTF-8"), 1000)) {
                request(session, store, "hello", "protocol.info");
                assertTrue(((List<?>) data(wire).get("capabilities")).contains("native_audio_events"));
                request(session, store, "before", "state.get");
                Object revision = last(wire).get("rev");
                GameController.DisplayEvent event = alarm("2026-10-11T01:00:00Z");
                game.events.add(event);
                game.events.add(event);
                assertEquals(2, wire.toString("UTF-8").lines().count());
                request(session, store, "events", "events.read");
                List<?> events = (List<?>) data(wire).get("items");
                assertEquals(2, events.size());
                for (Object raw : events) {
                    Map<?, ?> row = (Map<?, ?>) raw;
                    assertEquals("game.audio", row.get("kind"));
                    Map<?, ?> body = (Map<?, ?>) row.get("data");
                    assertEquals("alarm", body.get("kind"));
                    assertEquals("native_playback", body.get("basis"));
                    assertEquals("gameplay_audio_occurrence_v1", body.get("format"));
                    assertFalse(body.containsKey("cell"));
                    assertFalse(body.containsKey("source"));
                    assertFalse(body.containsKey("depth"));
                }
                assertTrue(game.events.isEmpty());
                assertEquals(audioRows(store.publicDatabase()), audioRows(store.internalDatabase()));
                assertEquals(2, audioRows(store.publicDatabase()).size());
                request(session, store, "after", "state.get");
                assertEquals(revision, last(wire).get("rev"));
                assertEquals(4, wire.toString("UTF-8").lines().count());
                game.events.add(alarm("2026-10-11T01:00:01Z"));
            }
            assertTrue(game.frozen);
            assertEquals(3, audioRows(store.publicDatabase()).size());
            assertEquals(audioRows(store.publicDatabase()), audioRows(store.internalDatabase()));
            assertEquals(4, wire.toString("UTF-8").lines().count());
        }
    }

    private static GameController.DisplayEvent alarm(String time) {
        return new GameController.DisplayEvent("run:audio", "game.audio", map(
                "format", "gameplay_audio_occurrence_v1", "kind", "alarm",
                "basis", "native_playback", "occurred_at", time), null);
    }

    private static void request(MachineSession session, AuditStore store, String id, String operation) throws Exception {
        session.accept(V9Requests.encode(store, map("protocol_version", 9, "scope_id", "run:audio",
                "id", id, "op", operation))).get(5, TimeUnit.SECONDS);
    }

    private static Map<String, Object> last(ByteArrayOutputStream wire) throws Exception {
        String[] lines = wire.toString("UTF-8").strip().split("\\R");
        Map<String, Object> response = JsonCodec.decode(lines[lines.length - 1]);
        assertFalse(response.toString(), response.containsKey("err"));
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ByteArrayOutputStream wire) throws Exception {
        return (Map<String, Object>) last(wire).get("data");
    }

    private static List<String> audioRows(Path database) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = db.createStatement();
             ResultSet result = statement.executeQuery("SELECT sequence,scope_id,data_json FROM events WHERE kind='game.audio' ORDER BY sequence")) {
            while (result.next()) rows.add(result.getLong(1) + "|" + result.getString(2) + "|" + result.getString(3));
        }
        return rows;
    }

    private static final class FakeGame implements MachineSession.GamePort {
        final ArrayDeque<GameController.DisplayEvent> events = new ArrayDeque<>();
        final GameController.State state = new GameController.State("run:audio", "fixture:v1", "player_ready",
                map("visible", true), map("private", true), Collections.emptyList());
        boolean frozen;
        public GameController.State latest() { return state; }
        public CompletableFuture<GameController.State> observe() { return CompletableFuture.completedFuture(state); }
        public CompletableFuture<GameController.State> execute(String version, Map<String, Object> args) { throw new AssertionError("No game action"); }
        public void prepareRun(String id) { throw new AssertionError("No new run"); }
        public GameController.SaveResult pollSave() { return null; }
        public List<GameController.DisplayEvent> peekDisplayEvents(int limit) {
            List<GameController.DisplayEvent> batch = new ArrayList<>();
            for (GameController.DisplayEvent event : events) { if (batch.size() == limit) break; batch.add(event); }
            return batch;
        }
        public void acknowledgeDisplayEvents(List<GameController.DisplayEvent> batch) {
            for (GameController.DisplayEvent event : batch) assertSame(event, events.removeFirst());
        }
        public boolean hasDisplayEvents() { return !events.isEmpty(); }
        public void freezeDisplayEvents() { frozen = true; }
        public boolean exiting() { return false; }
        public boolean disposed() { return false; }
        public void exitNow() { throw new AssertionError("No runtime exit required"); }
    }
}
