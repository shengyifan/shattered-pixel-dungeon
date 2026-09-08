package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.sqlite.Function;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Test-source-only process. The parent SIGKILLs it at an observed durable barrier. */
public final class AuditBoundaryChild {
    private static Path root;
    private static String phase;

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]).toRealPath();
        phase = args[1];
        if (!Files.readString(root.resolve("test_fixture.json")).contains("\"test_fixture\":true"))
            throw new IllegalArgumentException("Only a parent-created test fixture is allowed");
        AuditStore store = new AuditStore(root);
        String scope = phase.equals("new-run-planned") ? store.menuScope() : "run:boundary";
        if (!phase.equals("new-run-planned")) store.ensureScope(scope, "run", "boundary");
        Files.writeString(root.resolve("scope.txt"), scope);
        if (phase.equals("before-register")) barrier(false);
        if (phase.equals("inside-register")) installBarrier(store, "BEFORE INSERT ON requests");
        AuditStore.Attempt attempt = store.begin(scope, "boundary", "action.execute", "{\"id\":\"boundary\"}");
        if (phase.equals("after-register")) barrier(false);
        if (phase.equals("new-run-planned")) {
            store.ensureScope("run:planned", "planned", "planned");
            store.linkTarget(attempt, "run:planned");
        }
        if (phase.equals("inside-intent")) installBarrier(store, "BEFORE INSERT ON snapshots");
        store.markExecuting(attempt, "test-epoch:1", map("position", 1), map("position", 1, "secret", "before"));
        if (phase.equals("after-intent") || phase.equals("new-run-planned")) barrier(false);

        // This marker is deliberately NOT a game callback or a real game-save mutation.
        Files.writeString(root.resolve("fake-effect.txt"), "performed-once");
        if (phase.equals("after-fake-effect")) barrier(false);
        if (phase.equals("inside-result")) installBarrier(store, "BEFORE INSERT ON snapshots");
        store.complete(attempt, "COMPLETED", map("id", "boundary", "ok", true),
                map("position", 2), map("position", 2, "secret", "after"), null);
        if (phase.equals("after-result")) barrier(false);

        System.out.println(JsonCodec.encode(map("id", "boundary", "ok", true)));
        System.out.flush();
        if (phase.equals("after-output")) barrier(false);
        if (phase.equals("inside-output-mark")) installBarrier(store, "BEFORE UPDATE OF output_attempted ON exchanges");
        store.markOutputAttempt(attempt, true);
        if (phase.equals("after-output-mark")) barrier(false);
        throw new IllegalArgumentException("Unknown boundary: " + phase);
    }

    private static void installBarrier(AuditStore store, String clause) throws Exception {
        // A connection-local SQL function and a test-only trigger let us stop *inside* the
        // actual production transaction, without adding a fault switch to production code.
        Field field = AuditStore.class.getDeclaredField("writer");
        field.setAccessible(true);
        Connection writer = (Connection) field.get(store);
        Function.create(writer, "fixture_barrier", new Function() {
            @Override protected void xFunc() throws java.sql.SQLException {
                try { barrier(true); }
                catch (Exception error) { throw new java.sql.SQLException(error); }
            }
        });
        try (Statement statement = writer.createStatement()) {
            statement.execute("CREATE TRIGGER internal.fixture_kill_barrier " + clause + " BEGIN SELECT fixture_barrier(); END");
        }
    }

    private static void barrier(boolean inTransaction) throws Exception {
        byte[] bytes = JsonCodec.encode(map("phase", phase, "inside_sql_transaction", inTransaction)).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(root.resolve("barrier.pending"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(root.resolve("barrier.pending"), root.resolve("barrier.json"));
        new CountDownLatch(1).await(); // Parent confirms the barrier, then SIGKILLs this process.
    }
}
