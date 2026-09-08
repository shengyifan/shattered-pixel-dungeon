package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import java.nio.file.Files;
import java.nio.file.Path;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Deliberately halts without finally/close; only touches a test-owned directory. */
public final class AuditCrashChild {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        AuditStore store = new AuditStore(root);
        store.ensureScope("run:a", "run", "a");
        AuditStore.Attempt attempt = store.begin("run:a", "crash", "action.execute", "{\"id\":\"crash\"}");
        store.markExecuting(attempt, "before-crash", map("position", 1), map("position", 1, "private", "before"));
        Files.writeString(root.resolve("fake-effect.txt"), "performed-once");
        if ("after-result".equals(args[1])) {
            store.complete(attempt, "COMPLETED", map("id", "crash", "ok", true), map("position", 2), map("position", 2, "private", "after"), null);
        }
        Runtime.getRuntime().halt(73);
    }
}
