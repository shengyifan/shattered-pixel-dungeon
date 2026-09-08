package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Real subprocess + SQLite tests; the effect is a marker file, never a real engine action. */
@RunWith(Parameterized.class)
public class AuditBoundaryProcessTest {
    @Parameterized.Parameters(name = "{0}") public static Collection<Object[]> phases() {
        return Arrays.asList(new Object[][]{
                {"before-register"}, {"inside-register"}, {"after-register"},
                {"inside-intent"}, {"after-intent"}, {"after-fake-effect"},
                {"inside-result"}, {"after-result"}, {"after-output"},
                {"inside-output-mark"}, {"after-output-mark"}, {"new-run-planned"}});
    }

    private final String phase;
    public AuditBoundaryProcessTest(String phase) { this.phase = phase; }

    @Test public void sigkillAtObservedBoundaryPreservesOnlyCommittedFacts() throws Exception {
        Path root = fixtureRoot().resolve("ledger-" + phase + "-" + UUID.randomUUID());
        Files.createDirectories(root);
        Files.writeString(root.resolve("test_fixture.json"), JsonCodec.encode(map("test_fixture", true,
                "counts_as_win", false, "fixture", "p8:ledger:" + phase, "real_game_engine", false)));
        Process child = new ProcessBuilder(java(), "-cp", childClasspath(), AuditBoundaryChild.class.getName(), root.toString(), phase)
                .redirectError(root.resolve("child-stderr.log").toFile()).start();
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        Thread stdoutReader = new Thread(() -> {
            try { child.getInputStream().transferTo(captured); }
            catch (java.io.IOException closedByDestroy) { /* The parent may close the pipe while killing. */ }
        }, "fixture-stdout-reader");
        stdoutReader.setDaemon(true);
        stdoutReader.start();
        Map<String, Object> evidence = map("test_fixture", true, "counts_as_win", false,
                "phase", phase, "real_game_engine", false, "termination", "SIGKILL");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            Path barrier = root.resolve("barrier.json");
            while (!Files.exists(barrier) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue("Child must reach the exact barrier before any kill; " + root, Files.exists(barrier));
            Map<String, Object> observed = JsonCodec.decode(Files.readString(barrier));
            assertEquals(phase, observed.get("phase"));
            boolean outputSeen = Arrays.asList("after-output", "inside-output-mark", "after-output-mark").contains(phase);
            while (outputSeen && captured.size() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            if (outputSeen) assertTrue("The parent must receive response bytes before killing", captured.size() > 0);
            child.destroyForcibly();
            assertTrue("SIGKILL must terminate child", child.waitFor(10, TimeUnit.SECONDS));
            assertEquals("Unix SIGKILL exit status", 137, child.exitValue());
            stdoutReader.join(5000);
            String stdout = captured.toString(StandardCharsets.UTF_8);
            Files.writeString(root.resolve("captured-stdout.txt"), stdout);
            assertEquals(outputSeen, !stdout.isEmpty());
            if (outputSeen) assertEquals(map("id", "boundary", "ok", true), JsonCodec.decode(stdout.trim()));
            evidence.put("barrier", observed);
            evidence.put("parent_received_response_bytes", outputSeen);

            // Opening SQLite performs its hot-journal rollback first. Remove the durable
            // fixture trigger before normal code can invoke its now-absent SQL function.
            try (Connection internal = connect(root.resolve("internal.sqlite3")); Statement statement = internal.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS fixture_kill_barrier");
            }
            String scope = Files.readString(root.resolve("scope.txt"));
            boolean noRegistration = phase.equals("before-register") || phase.equals("inside-register");
            boolean noIntent = phase.equals("after-register") || phase.equals("inside-intent");
            boolean completed = Arrays.asList("after-result", "after-output", "inside-output-mark", "after-output-mark").contains(phase);
            String expectedBeforeRecovery = noRegistration ? null : noIntent ? "RECEIVED" : completed ? "COMPLETED" : "EXECUTING";
            String expectedAfterRecovery = noRegistration ? null : noIntent ? "NOT_EXECUTED" : completed ? "COMPLETED" : "UNKNOWN";
            int snapshots = noRegistration || noIntent ? 0 : completed ? 2 : 1;
            String pairGeneration = null;
            for (String file : new String[]{"public.sqlite3", "internal.sqlite3"}) {
                try (Connection db = connect(root.resolve(file))) {
                    assertEquals("ok", scalar(db, "PRAGMA integrity_check"));
                    assertEquals(expectedBeforeRecovery, scalar(db, "SELECT status FROM requests WHERE id='boundary'"));
                    assertEquals(String.valueOf(snapshots), scalar(db, "SELECT COUNT(*) FROM snapshots"));
                    assertEquals(noRegistration ? "0" : "1", scalar(db, "SELECT COUNT(*) FROM exchanges"));
                    String generation = scalar(db, "SELECT value FROM metadata WHERE key='pair_generation'");
                    if (pairGeneration == null) pairGeneration = generation;
                    else assertEquals("Paired commit generation must agree after SQLite recovery", pairGeneration, generation);
                }
            }
            evidence.put("sqlite_status_before_semantic_recovery", expectedBeforeRecovery);
            evidence.put("paired_snapshot_count", snapshots);
            evidence.put("paired_generation_equal", true);
            try (AuditStore store = new AuditStore(root)) {
                assertEquals(noRegistration || completed ? 0 : 1, store.recoverInterrupted());
                assertEquals(0, store.recoverInterrupted());
                Map<String, Object> request = store.getRequest(scope, "boundary");
                if (noRegistration) assertNull(request);
                else {
                    assertEquals(expectedAfterRecovery, request.get("status"));
                    assertEquals(completed, request.get("after_snapshot") != null);
                    assertEquals(!noIntent, request.get("before_snapshot") != null);
                    assertEquals(phase.equals("after-output-mark"), store.history(scope, 0, 10).get(0).get("output_attempted"));
                    assertEquals(phase.equals("after-output-mark") ? Boolean.TRUE : null,
                            store.history(scope, 0, 10).get(0).get("output_succeeded"));
                }
                if (phase.equals("new-run-planned")) {
                    assertEquals("run:planned", request.get("target_scope"));
                    try (Connection db = connect(root.resolve("public.sqlite3"))) {
                        assertEquals("planned", scalar(db, "SELECT kind FROM scopes WHERE scope_id='run:planned'"));
                    }
                }
                AuditStore.Attempt repeated = store.begin(scope, "boundary", "action.execute", "{}");
                assertEquals(!noRegistration, repeated.duplicate);
                store.complete(repeated, "REJECTED", map("id", "boundary", "ok", false), null, null, null);
            }
            boolean fakeEffect = !noRegistration && !noIntent && !phase.equals("after-intent") && !phase.equals("new-run-planned");
            assertEquals(fakeEffect, Files.exists(root.resolve("fake-effect.txt")));
            if (fakeEffect) assertEquals("performed-once", Files.readString(root.resolve("fake-effect.txt")));
            evidence.put("request_status_after_semantic_recovery", expectedAfterRecovery);
            evidence.put("id_occupied", !noRegistration);
            evidence.put("fake_effect_exists", fakeEffect);
            evidence.put("output_attempt_recorded", phase.equals("after-output-mark"));
            evidence.put("ok", true);
        } catch (Throwable failure) {
            evidence.put("ok", false);
            evidence.put("error", failure.toString());
            throw failure;
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
            Files.writeString(root.resolve("ledger-boundary-result.json"), JsonCodec.encode(evidence));
        }
    }

    private static Path fixtureRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isDirectory(path.resolve("desktop-control")) && Files.isDirectory(path.resolve("core")))
                return path.resolve("desktop-control/build/fixtures");
        }
        throw new IllegalStateException("Cannot locate repository fixture directory");
    }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private static String childClasspath() throws Exception {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Class<?> type : new Class<?>[]{AuditBoundaryChild.class, AuditStore.class, JsonCodec.class, org.sqlite.JDBC.class})
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return String.join(java.io.File.pathSeparator, paths);
    }
    private static Connection connect(Path path) throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + path); }
    private static String scalar(Connection db, String sql) throws Exception {
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}
