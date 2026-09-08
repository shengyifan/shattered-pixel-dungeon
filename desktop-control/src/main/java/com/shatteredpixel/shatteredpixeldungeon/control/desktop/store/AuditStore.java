package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.Identifiers;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Single-writer, paired SQLite audit files. Every write transaction spans both attached databases.
 * Public reads use a different read-only connection that has never attached the internal database.
 * This transaction boundary deliberately does not claim atomicity with the game's save files.
 * The caller must hold the profile's exclusive process lock for this store's entire lifetime.
 */
public final class AuditStore implements AutoCloseable {
    private final Path publicPath;
    private final Path internalPath;
    private Connection writer;
    private Connection publicReader;
    private String menuScope;
    private boolean closed;
    private boolean poisoned;

    public static final class Attempt {
        public final long exchangeId;
        public final String scopeId;
        public final String id;
        public final boolean duplicate;
        public final boolean registered;
        private final AuditStore owner;

        private Attempt(AuditStore owner, long exchangeId, String scopeId, String id,
                        boolean duplicate, boolean registered) {
            this.owner = owner;
            this.exchangeId = exchangeId;
            this.scopeId = scopeId;
            this.id = id;
            this.duplicate = duplicate;
            this.registered = registered;
        }
    }

    public AuditStore(Path root) {
        Path directory = root.toAbsolutePath().normalize();
        publicPath = directory.resolve("public.sqlite3");
        internalPath = directory.resolve("internal.sqlite3");
        try {
            Files.createDirectories(directory);
            // Refuse a half-restored pair; creating the missing half would destroy the audit boundary.
            if (Files.exists(publicPath) != Files.exists(internalPath)) {
                throw new AuditException("AUDIT_PAIR_MISSING", "The audit database pair is incomplete", null);
            }
            Class.forName("org.sqlite.JDBC");
            writer = DriverManager.getConnection("jdbc:sqlite:" + publicPath);
            try (PreparedStatement attach = writer.prepareStatement("ATTACH DATABASE ? AS internal")) {
                attach.setString(1, internalPath.toString());
                attach.execute();
            }
            configure();
            initialize();
            publicReader = DriverManager.getConnection("jdbc:sqlite:" + publicPath.toUri().toASCIIString() + "?mode=ro");
            try (Statement statement = publicReader.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                statement.execute("PRAGMA busy_timeout=5000");
            }
        } catch (Exception failure) {
            closeQuietly(publicReader);
            closeQuietly(writer);
            if (failure instanceof AuditException) throw (AuditException) failure;
            throw failure("AUDIT_OPEN_FAILED", failure);
        }
    }

    public synchronized String menuScope() { requireOpen(); return menuScope; }
    public Path publicDatabase() { return publicPath; }
    public Path internalDatabase() { return internalPath; }

    private void configure() throws SQLException {
        try (Statement s = writer.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA fullfsync=ON");
            s.execute("PRAGMA foreign_keys=ON");
            for (String schema : schemas()) {
                s.execute("PRAGMA " + schema + ".journal_mode=DELETE");
                s.execute("PRAGMA " + schema + ".synchronous=EXTRA");
                if (!"delete".equalsIgnoreCase(pragma(schema + ".journal_mode"))
                        || !"3".equals(pragma(schema + ".synchronous"))) {
                    throw new SQLException("Required paired-database durability configuration was not applied");
                }
            }
            if (!"1".equals(pragma("fullfsync"))) throw new SQLException("fullfsync was not applied");
        }
    }

    private String pragma(String name) throws SQLException {
        try (Statement s = writer.createStatement(); ResultSet rs = s.executeQuery("PRAGMA " + name)) {
            if (!rs.next()) throw new SQLException("Missing pragma result");
            return rs.getString(1);
        }
    }

    private void initialize() {
        transaction(() -> {
            for (String schema : schemas()) {
                try (Statement s = writer.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".scopes (scope_id TEXT PRIMARY KEY, kind TEXT NOT NULL, run_id TEXT, created_at TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".snapshots (snapshot_id TEXT PRIMARY KEY, encoding TEXT NOT NULL, body BLOB NOT NULL, content_id TEXT, created_at TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".snapshot_blobs (content_id TEXT PRIMARY KEY, encoding TEXT NOT NULL, body BLOB NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".requests (scope_id TEXT NOT NULL, id TEXT NOT NULL, op TEXT, raw_request TEXT NOT NULL, status TEXT NOT NULL, state_version TEXT, target_scope TEXT, before_snapshot TEXT, after_snapshot TEXT, response_json TEXT, first_exchange INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(scope_id,id))");
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".exchanges (sequence INTEGER PRIMARY KEY AUTOINCREMENT, scope_id TEXT, id TEXT, raw_request TEXT NOT NULL, duplicate INTEGER NOT NULL, registered INTEGER NOT NULL, received_at TEXT NOT NULL, response_json TEXT, responded_at TEXT, before_snapshot TEXT, after_snapshot TEXT, output_attempted INTEGER NOT NULL DEFAULT 0, output_succeeded INTEGER, output_attempted_at TEXT)");
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, scope_id TEXT, kind TEXT NOT NULL, data_json TEXT NOT NULL, created_at TEXT NOT NULL)");
                    s.execute("CREATE INDEX IF NOT EXISTS " + schema + ".exchange_scope_sequence ON exchanges(scope_id,sequence)");
                }
            }
            try (Statement s = writer.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS internal.exceptions (sequence INTEGER PRIMARY KEY AUTOINCREMENT, exchange_id INTEGER, scope_id TEXT, id TEXT, exception_class TEXT NOT NULL, message TEXT, stack_trace TEXT NOT NULL, thread_name TEXT NOT NULL, created_at TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS internal.logs (sequence INTEGER PRIMARY KEY AUTOINCREMENT, channel TEXT NOT NULL, text TEXT NOT NULL, created_at TEXT NOT NULL)");
            }
            String publicProfile = metadata("main", "profile_id");
            String privateProfile = metadata("internal", "profile_id");
            if (publicProfile == null && privateProfile == null) {
                publicProfile = UUID.randomUUID().toString();
                for (String schema : schemas()) {
                    putMetadata(schema, "profile_id", publicProfile);
                    putMetadata(schema, "schema_version", "3");
                }
            } else if (publicProfile == null || !publicProfile.equals(privateProfile)) {
                throw new SQLException("The public and internal audit database identities differ");
            }
            String version = metadata("main", "schema_version");
            if (!java.util.Objects.equals(version, metadata("internal", "schema_version"))) throw new SQLException("Paired audit schema mismatch");
            if ("1".equals(version)) { migrateVersionOne();version="2"; }
            if ("2".equals(version)||"3".equals(version)) migrateWireBytes();
            else throw new SQLException("Unsupported audit schema");
            menuScope = "menu:" + publicProfile;
            insertScope(menuScope, "menu", null);
            return null;
        });
    }

    /** Migration is part of the same attached transaction: an interrupted upgrade retains both old files. */
    private void migrateWireBytes()throws SQLException{
        for(String schema:schemas()){
            addColumnIfMissing(schema,"exchanges","raw_bytes","BLOB");
            addColumnIfMissing(schema,"exchanges","raw_format","TEXT NOT NULL DEFAULT 'legacy-text'");
            try(PreparedStatement s=writer.prepareStatement("UPDATE "+schema+".metadata SET value='3' WHERE key='schema_version'")){s.executeUpdate();}
        }
    }

    private void migrateVersionOne() throws SQLException {
        for (String schema : schemas()) {
            addColumnIfMissing(schema, "snapshots", "content_id", "TEXT");
            addColumnIfMissing(schema, "requests", "target_scope", "TEXT");
            addColumnIfMissing(schema, "exchanges", "output_attempted", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(schema, "exchanges", "output_succeeded", "INTEGER");
            addColumnIfMissing(schema, "exchanges", "output_attempted_at", "TEXT");
        }
        List<String> ids = new ArrayList<>();
        try (Statement s = writer.createStatement(); ResultSet rs = s.executeQuery("SELECT snapshot_id FROM main.snapshots WHERE content_id IS NULL")) {
            while (rs.next()) ids.add(rs.getString(1));
        }
        for (String id : ids) {
            Map<String, Object> publicValue = legacySnapshot("main", id);
            Map<String, Object> privateValue = legacySnapshot("internal", id);
            SnapshotCodec.Encoded encoded = SnapshotCodec.encodePair(publicValue, privateValue);
            insertEncodedBlocks(encoded);
            int side = 0;
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".snapshots SET encoding=?,body=?,content_id=? WHERE snapshot_id=?")) {
                    s.setString(1, side == 0 ? SnapshotCodec.ENCODING : SnapshotCodec.INTERNAL_ENCODING);
                    s.setBytes(2, new byte[0]); s.setString(3, side++ == 0 ? encoded.publicContentId : encoded.internalContentId); s.setString(4, id);
                    if (s.executeUpdate() != 1) throw new SQLException("Paired legacy snapshot is missing");
                }
            }
        }
        for (String schema : schemas()) {
            try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".metadata SET value='2' WHERE key='schema_version'")) { s.executeUpdate(); }
        }
    }

    private void addColumnIfMissing(String schema, String table, String name, String type) throws SQLException {
        try (Statement s = writer.createStatement(); ResultSet rs = s.executeQuery("PRAGMA " + schema + ".table_info(" + table + ")")) {
            while (rs.next()) if (name.equals(rs.getString("name"))) return;
        }
        try (Statement s = writer.createStatement()) { s.execute("ALTER TABLE " + schema + "." + table + " ADD COLUMN " + name + " " + type); }
    }

    private Map<String, Object> legacySnapshot(String schema, String id) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("SELECT encoding,body FROM " + schema + ".snapshots WHERE snapshot_id=?")) {
            s.setString(1, id);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next() || !"json-utf8".equals(rs.getString(1))) throw new SQLException("Unsupported legacy snapshot encoding");
                return JsonCodec.decode(new String(rs.getBytes(2), StandardCharsets.UTF_8));
            }
        }
    }

    public synchronized void ensureScope(String scopeId, String kind, String runId) {
        if (scopeId == null || kind == null) throw new IllegalArgumentException("Scope and kind are required");
        transaction(() -> { insertScope(scopeId, kind, runId); return null; });
    }

    /** Persisted before starting a new run, so a lost start response still has its original target. */
    public synchronized void linkTarget(Attempt attempt, String targetScopeId) {
        requireAttempt(attempt);
        if (!attempt.registered || targetScopeId == null) throw new IllegalArgumentException("A registered request and target are required");
        transaction(() -> {
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET target_scope=?,updated_at=? WHERE scope_id=? AND id=? AND status='RECEIVED' AND target_scope IS NULL")) {
                    s.setString(1, targetScopeId); s.setString(2, now()); s.setString(3, attempt.scopeId); s.setString(4, attempt.id);
                    if (s.executeUpdate() != 1) throw new SQLException("Run target must be fixed before dispatch");
                }
            }
            return null;
        });
    }

    /** data must already be a public projection; a private exception is recorded separately. */
    public synchronized void event(String scopeId, String kind, Map<String, Object> data) {
        if (kind == null) throw new IllegalArgumentException("Event kind is required");
        String json = JsonCodec.encode(data);
        transaction(() -> { insertEvent(scopeId, kind, json); return null; });
    }

    public synchronized void recordSave(String scopeId, int slot, boolean success, Throwable error) {
        String data = JsonCodec.encode(Values.map("slot", slot, "success", success));
        transaction(() -> {
            insertEvent(scopeId, "save", data);
            if (error != null) insertException(null, error);
            return null;
        });
    }

    private void insertEvent(String scopeId, String kind, String json) throws SQLException {
        String created = now();
        long sequence;
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO main.events(scope_id,kind,data_json,created_at) VALUES(?,?,?,?)")) {
            s.setString(1, scopeId); s.setString(2, kind); s.setString(3, json); s.setString(4, created); s.executeUpdate();
        }
        try (Statement s = writer.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
            if (!rs.next()) throw new SQLException("Missing event identity");
            sequence = rs.getLong(1);
        }
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO internal.events(sequence,scope_id,kind,data_json,created_at) VALUES(?,?,?,?,?)")) {
            s.setLong(1, sequence); s.setString(2, scopeId); s.setString(3, kind); s.setString(4, json); s.setString(5, created); s.executeUpdate();
        }
    }

    public synchronized boolean hasScope(String scopeId) {
        requireOpen();
        try (PreparedStatement s = publicReader.prepareStatement("SELECT 1 FROM scopes WHERE scope_id=?")) {
            s.setString(1, scopeId);
            try (ResultSet rs = s.executeQuery()) { return rs.next(); }
        } catch (SQLException error) { throw failure("AUDIT_READ_FAILED", error); }
    }

    private void insertScope(String scopeId, String kind, String runId) throws SQLException {
        for (String schema : schemas()) {
            try (PreparedStatement s = writer.prepareStatement("INSERT OR IGNORE INTO " + schema + ".scopes(scope_id,kind,run_id,created_at) VALUES(?,?,?,?)")) {
                s.setString(1, scopeId); s.setString(2, kind); s.setString(3, runId); s.setString(4, now()); s.executeUpdate();
            }
            try (PreparedStatement s = writer.prepareStatement("SELECT kind,run_id FROM " + schema + ".scopes WHERE scope_id=?")) {
                s.setString(1, scopeId);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next() || !java.util.Objects.equals(runId, rs.getString(2))) {
                        throw new SQLException("Scope identity conflict");
                    }
                    String originalKind = rs.getString(1);
                    if (!kind.equals(originalKind)) {
                        if (!"planned".equals(originalKind) || !"run".equals(kind)) throw new SQLException("Scope identity conflict");
                        try (PreparedStatement promote = writer.prepareStatement("UPDATE " + schema + ".scopes SET kind='run' WHERE scope_id=?")) {
                            promote.setString(1, scopeId); promote.executeUpdate();
                        }
                    }
                }
            }
        }
    }

    /** A parseable identity is consumed even when later request validation fails. */
    public synchronized Attempt begin(String scopeId, String id, String op, String rawRequest) {
        return begin(scopeId,id,op,rawRequest,rawRequest==null?null:rawRequest.getBytes(StandardCharsets.UTF_8),"logical-text");
    }

    public synchronized Attempt begin(String scopeId,String id,String op,String rawRequest,byte[] wireBytes,String wireFormat){
        if (rawRequest == null) throw new IllegalArgumentException("The original request is required");
        return transaction(() -> {
            boolean identifiable = Identifiers.valid(scopeId,256) && Identifiers.valid(id,128);
            boolean duplicate = identifiable && requestExists(scopeId, id);
            boolean registered = identifiable && !duplicate;
            String received = now();
            long sequence;
            try (PreparedStatement s = writer.prepareStatement("INSERT INTO main.exchanges(scope_id,id,raw_request,duplicate,registered,received_at) VALUES(?,?,?,?,?,?)")) {
                setExchange(s, scopeId, id, rawRequest, duplicate, registered, received); s.executeUpdate();
            }
            try (Statement s = writer.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                if (!rs.next()) throw new SQLException("Missing exchange identity");
                sequence = rs.getLong(1);
            }
            try (PreparedStatement s = writer.prepareStatement("INSERT INTO internal.exchanges(scope_id,id,raw_request,duplicate,registered,received_at,sequence) VALUES(?,?,?,?,?,?,?)")) {
                setExchange(s, scopeId, id, rawRequest, duplicate, registered, received); s.setLong(7, sequence); s.executeUpdate();
            }
            for(String schema:schemas())try(PreparedStatement s=writer.prepareStatement("UPDATE "+schema+".exchanges SET raw_bytes=?,raw_format=? WHERE sequence=?")){
                s.setBytes(1,wireBytes);s.setString(2,wireFormat);s.setLong(3,sequence);s.executeUpdate();
            }
            if (registered) {
                for (String schema : schemas()) {
                    try (PreparedStatement s = writer.prepareStatement("INSERT INTO " + schema + ".requests(scope_id,id,op,raw_request,status,first_exchange,created_at,updated_at) VALUES(?,?,?,?,'RECEIVED',?,?,?)")) {
                        s.setString(1, scopeId); s.setString(2, id); s.setString(3, op); s.setString(4, rawRequest);
                        s.setLong(5, sequence); s.setString(6, received); s.setString(7, received); s.executeUpdate();
                    }
                }
            }
            return new Attempt(this, sequence, scopeId, id, duplicate, registered);
        });
    }

    private void setExchange(PreparedStatement s, String scope, String id, String raw, boolean duplicate,
                             boolean registered, String time) throws SQLException {
        s.setString(1, scope); s.setString(2, id); s.setString(3, raw);
        s.setInt(4, duplicate ? 1 : 0); s.setInt(5, registered ? 1 : 0); s.setString(6, time);
    }

    /** Commits the paired before-snapshot and intent before the coordinator invokes any game handler. */
    public synchronized void markExecuting(Attempt attempt, String stateVersion, Map<String, Object> publicBefore,
                                            Map<String, Object> internalBefore) {
        requireAttempt(attempt);
        if (!attempt.registered) throw new IllegalArgumentException("Only a newly registered request may execute");
        SnapshotCodec.Encoded snapshots = snapshotBytes(publicBefore, internalBefore);
        transaction(() -> {
            String snapshotId = insertSnapshots(snapshots);
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET status='EXECUTING',state_version=?,before_snapshot=?,updated_at=? WHERE scope_id=? AND id=? AND status='RECEIVED'")) {
                    s.setString(1, stateVersion); s.setString(2, snapshotId); s.setString(3, now());
                    s.setString(4, attempt.scopeId); s.setString(5, attempt.id);
                    if (s.executeUpdate() != 1) throw new SQLException("Request cannot enter execution twice");
                }
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".exchanges SET before_snapshot=? WHERE sequence=? AND response_json IS NULL")) {
                    s.setString(1, snapshotId); s.setLong(2, attempt.exchangeId);
                    if (s.executeUpdate() != 1) throw new SQLException("Exchange already has a response");
                }
            }
            return null;
        });
    }

    /**
     * Persists the exact response representation before it is written to stdout. Duplicate attempts only
     * gain an exchange response; they never overwrite the original request or its snapshots.
     */
    public synchronized void complete(Attempt attempt, String status, Map<String, Object> response,
                                     Map<String, Object> publicAfter, Map<String, Object> internalAfter, Throwable error) {
        requireAttempt(attempt);
        if (response == null) throw new IllegalArgumentException("A response object is required");
        if (status == null || "RECEIVED".equalsIgnoreCase(status) || "EXECUTING".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("A terminal status is required");
        }
        String terminal = status.toUpperCase(Locale.ROOT);
        String encodedResponse = JsonCodec.encode(response);
        SnapshotCodec.Encoded snapshots = snapshotBytes(publicAfter, internalAfter);
        transaction(() -> {
            String afterId = insertSnapshots(snapshots);
            String completed = now();
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".exchanges SET response_json=?,responded_at=?,after_snapshot=?,before_snapshot=COALESCE(before_snapshot,?) WHERE sequence=? AND response_json IS NULL")) {
                    s.setString(1, encodedResponse); s.setString(2, completed); s.setString(3, afterId); s.setString(4, afterId);
                    s.setLong(5, attempt.exchangeId);
                    if (s.executeUpdate() != 1) throw new SQLException("Exchange already has a response");
                }
                if (attempt.registered) {
                    try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET status=?,response_json=?,after_snapshot=?,before_snapshot=COALESCE(before_snapshot,?),updated_at=? WHERE scope_id=? AND id=? AND status IN ('RECEIVED','EXECUTING')")) {
                        s.setString(1, terminal); s.setString(2, encodedResponse); s.setString(3, afterId); s.setString(4, afterId);
                        s.setString(5, completed); s.setString(6, attempt.scopeId); s.setString(7, attempt.id);
                        if (s.executeUpdate() != 1) throw new SQLException("Request already has a terminal result");
                    }
                }
            }
            if (error != null) insertException(attempt, error);
            return null;
        });
    }

    /** The sole wire response is pending; the logical request remains EXECUTING until settle(). */
    public synchronized void respondPending(Attempt attempt, Map<String, Object> response,
                                            Map<String, Object> publicCurrent, Map<String, Object> internalCurrent) {
        requireAttempt(attempt);
        if (!attempt.registered || response == null) throw new IllegalArgumentException("A registered request and response are required");
        String json = JsonCodec.encode(response);
        SnapshotCodec.Encoded snapshots = snapshotBytes(publicCurrent, internalCurrent);
        transaction(() -> {
            String snapshotId = insertSnapshots(snapshots);
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("SELECT status FROM " + schema + ".requests WHERE scope_id=? AND id=?")) {
                    s.setString(1, attempt.scopeId); s.setString(2, attempt.id);
                    try (ResultSet rs = s.executeQuery()) {
                        if (!rs.next() || !"EXECUTING".equals(rs.getString(1))) throw new SQLException("Only an executing request may respond pending");
                    }
                }
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".exchanges SET response_json=?,responded_at=?,after_snapshot=? WHERE sequence=? AND response_json IS NULL")) {
                    s.setString(1, json); s.setString(2, now()); s.setString(3, snapshotId); s.setLong(4, attempt.exchangeId);
                    if (s.executeUpdate() != 1) throw new SQLException("Exchange already has a response");
                }
            }
            return null;
        });
    }

    /** Finishes a pending request without fabricating another response on its old exchange. */
    public synchronized void settle(Attempt attempt, String status, Map<String, Object> finalResult,
                                    Map<String, Object> publicAfter, Map<String, Object> internalAfter, Throwable error) {
        requireAttempt(attempt);
        if (!attempt.registered || finalResult == null || status == null
                || "RECEIVED".equalsIgnoreCase(status) || "EXECUTING".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("A registered request and terminal result are required");
        }
        String json = JsonCodec.encode(finalResult);
        SnapshotCodec.Encoded snapshots = snapshotBytes(publicAfter, internalAfter);
        transaction(() -> {
            String snapshotId = insertSnapshots(snapshots);
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET status=?,response_json=?,after_snapshot=?,updated_at=? WHERE scope_id=? AND id=? AND status='EXECUTING'")) {
                    s.setString(1, status.toUpperCase(Locale.ROOT)); s.setString(2, json); s.setString(3, snapshotId); s.setString(4, now());
                    s.setString(5, attempt.scopeId); s.setString(6, attempt.id);
                    if (s.executeUpdate() != 1) throw new SQLException("Only an executing request may settle");
                }
            }
            if (error != null) insertException(attempt, error);
            return null;
        });
    }

    /** Records what the writer observed; success does not prove receipt by the client. */
    public synchronized void markOutputAttempt(Attempt attempt, boolean success) {
        requireAttempt(attempt);
        transaction(() -> {
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".exchanges SET output_attempted=1,output_succeeded=?,output_attempted_at=? WHERE sequence=? AND response_json IS NOT NULL AND output_attempted=0")) {
                    s.setInt(1, success ? 1 : 0); s.setString(2, now()); s.setLong(3, attempt.exchangeId);
                    if (s.executeUpdate() != 1) throw new SQLException("Output requires one previously recorded response");
                }
            }
            return null;
        });
    }

    /** Records an asynchronous JVM/transport failure privately, without exposing its stack to public history. */
    public synchronized void recordException(Attempt attempt, Throwable error) {
        if (attempt != null) requireAttempt(attempt);
        if (error == null) return;
        transaction(() -> { insertException(attempt, error); return null; });
    }

    public synchronized void recordException(Throwable error) { recordException(null, error); }

    /** Raw console content may contain engine internals, so it is never inserted into the public database. */
    public synchronized void recordLog(String channel, String text) {
        if (channel == null || text == null) throw new IllegalArgumentException("Log channel and text are required");
        transaction(() -> {
            try (PreparedStatement s = writer.prepareStatement("INSERT INTO internal.logs(channel,text,created_at) VALUES(?,?,?)")) {
                s.setString(1, channel); s.setString(2, text); s.setString(3, now()); s.executeUpdate();
            }
            return null;
        });
    }

    private void insertException(Attempt attempt, Throwable error) throws SQLException {
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO internal.exceptions(exchange_id,scope_id,id,exception_class,message,stack_trace,thread_name,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            if (attempt == null) s.setNull(1, java.sql.Types.INTEGER); else s.setLong(1, attempt.exchangeId);
            s.setString(2, attempt == null ? null : attempt.scopeId); s.setString(3, attempt == null ? null : attempt.id);
            s.setString(4, error.getClass().getName()); s.setString(5, error.getMessage()); s.setString(6, stack.toString());
            s.setString(7, Thread.currentThread().getName()); s.setString(8, now()); s.executeUpdate();
        }
    }

    /** Called once after taking the profile lock, before accepting any newly arrived requests. Never replays work. */
    public synchronized int recoverInterrupted() {
        return transaction(() -> {
            List<String[]> unfinished = new ArrayList<>();
            try (Statement s = writer.createStatement(); ResultSet rs = s.executeQuery("SELECT scope_id,id,status FROM main.requests WHERE status IN ('RECEIVED','EXECUTING')")) {
                while (rs.next()) unfinished.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            }
            for (String[] request : unfinished) {
                String status = "EXECUTING".equals(request[2]) ? "UNKNOWN" : "NOT_EXECUTED";
                String response = JsonCodec.encode(Values.map("id", request[1], "scope_id", request[0], "ok", false,
                        "error", Values.map("code", status, "message", "UNKNOWN".equals(status)
                                ? "The process stopped before the execution result was recorded; this request will not be replayed"
                                : "The process stopped before dispatch; this request will not be replayed")));
                for (String schema : schemas()) {
                    try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET status=?,response_json=?,updated_at=? WHERE scope_id=? AND id=? AND status IN ('RECEIVED','EXECUTING')")) {
                        s.setString(1, status); s.setString(2, response); s.setString(3, now());
                        s.setString(4, request[0]); s.setString(5, request[1]);
                        if (s.executeUpdate() != 1) throw new SQLException("Paired audit recovery mismatch");
                    }
                }
            }
            // No exchange response is invented here: no bytes were actually prepared for that old connection.
            return unfinished.size();
        });
    }

    /** Public data only. Looking up a result is not a retry of its original operation. */
    public synchronized Map<String, Object> getRequest(String scopeId, String id) {
        requireOpen();
        try (PreparedStatement s = publicReader.prepareStatement("SELECT * FROM requests WHERE scope_id=? AND id=?")) {
            s.setString(1, scopeId); s.setString(2, id);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return null;
                return Values.map("scope_id", rs.getString("scope_id"), "id", rs.getString("id"), "op", rs.getString("op"),
                        "status", rs.getString("status"), "state_version", rs.getString("state_version"), "target_scope", rs.getString("target_scope"),
                        "raw_request", rs.getString("raw_request"), "response", decodeNullable(rs.getString("response_json")),
                        "before_snapshot", readSnapshot(rs.getString("before_snapshot")),
                        "after_snapshot", readSnapshot(rs.getString("after_snapshot")),
                        "first_exchange", rs.getLong("first_exchange"), "created_at", rs.getString("created_at"), "updated_at", rs.getString("updated_at"));
            }
        } catch (SQLException error) { throw failure("AUDIT_READ_FAILED", error); }
    }

    /** Metadata only: embedding past responses here would recursively grow history-query responses. */
    public synchronized List<Map<String, Object>> history(String scopeId, long afterSequence, int limit) {
        requireOpen();
        if (afterSequence < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid history bounds");
        List<Map<String, Object>> result = new ArrayList<>();
        try (PreparedStatement s = publicReader.prepareStatement("SELECT e.sequence,e.scope_id,e.id,e.duplicate,e.registered,e.received_at,e.responded_at,e.output_attempted,e.output_succeeded,r.op,r.status FROM exchanges e LEFT JOIN requests r ON r.scope_id=e.scope_id AND r.id=e.id WHERE e.scope_id=? AND e.sequence>? ORDER BY e.sequence LIMIT ?")) {
            s.setString(1, scopeId); s.setLong(2, afterSequence); s.setInt(3, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) result.add(Values.map("sequence", rs.getLong("sequence"), "scope_id", rs.getString("scope_id"),
                        "id", rs.getString("id"), "duplicate", rs.getInt("duplicate") != 0, "registered", rs.getInt("registered") != 0,
                        "op", rs.getString("op"), "request_status", rs.getString("status"),
                        "received_at", rs.getString("received_at"), "response_recorded_at", rs.getString("responded_at"),
                        "output_attempted", rs.getInt("output_attempted") != 0,
                        "output_succeeded", rs.getObject("output_succeeded") == null ? null : rs.getInt("output_succeeded") != 0));
            }
            return result;
        } catch (SQLException error) { throw failure("AUDIT_READ_FAILED", error); }
    }

    public synchronized List<Map<String, Object>> events(String scopeId, long afterSequence, int limit) {
        requireOpen();
        if (afterSequence < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid event bounds");
        List<Map<String, Object>> result = new ArrayList<>();
        try (PreparedStatement s = publicReader.prepareStatement("SELECT sequence,scope_id,kind,data_json,created_at FROM events WHERE scope_id=? AND sequence>? ORDER BY sequence LIMIT ?")) {
            s.setString(1, scopeId); s.setLong(2, afterSequence); s.setInt(3, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) result.add(Values.map("sequence", rs.getLong(1), "scope_id", rs.getString(2), "kind", rs.getString(3),
                        "data", JsonCodec.decode(rs.getString(4)), "created_at", rs.getString(5)));
            }
            return result;
        } catch (SQLException error) { throw failure("AUDIT_READ_FAILED", error); }
    }

    private Map<String, Object> readSnapshot(String id) throws SQLException {
        if (id == null) return null;
        try (PreparedStatement s = publicReader.prepareStatement("SELECT s.encoding,s.content_id,b.body FROM snapshots s LEFT JOIN snapshot_blobs b ON b.content_id=s.content_id WHERE s.snapshot_id=?")) {
            s.setString(1, id);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next() || !SnapshotCodec.ENCODING.equals(rs.getString(1))) throw new SQLException("Missing public snapshot");
                byte[] body = rs.getBytes(3);
                if (body == null || !SnapshotCodec.contentId(body).equals(rs.getString(2))) throw new SQLException("Public snapshot content is missing or corrupted");
                return SnapshotCodec.decode(body);
            }
        }
    }

    private Map<String, Object> decodeNullable(String value) { return value == null ? null : JsonCodec.decode(value); }

    private SnapshotCodec.Encoded snapshotBytes(Map<String, Object> publicSnapshot, Map<String, Object> internalSnapshot) {
        if ((publicSnapshot == null) != (internalSnapshot == null)) throw new IllegalArgumentException("Snapshots must be paired");
        if (publicSnapshot == null) return null;
        return SnapshotCodec.encodePair(publicSnapshot, internalSnapshot);
    }

    private String insertSnapshots(SnapshotCodec.Encoded encoded) throws SQLException {
        if (encoded == null) return null;
        insertEncodedBlocks(encoded);
        // Occurrence identity never depends on any private or public content hash.
        String id = UUID.randomUUID().toString();
        String created = now();
        int side = 0;
        for (String schema : schemas()) {
            try (PreparedStatement s = writer.prepareStatement("INSERT INTO " + schema + ".snapshots(snapshot_id,encoding,body,content_id,created_at) VALUES(?,?,?,?,?)")) {
                s.setString(1, id); s.setString(2, side == 0 ? SnapshotCodec.ENCODING : SnapshotCodec.INTERNAL_ENCODING);
                s.setBytes(3, new byte[0]); s.setString(4, side++ == 0 ? encoded.publicContentId : encoded.internalContentId);
                s.setString(5, created); s.executeUpdate();
            }
        }
        return id;
    }

    private void insertEncodedBlocks(SnapshotCodec.Encoded encoded) throws SQLException {
        insertBlob("main", encoded.publicContentId, SnapshotCodec.ENCODING, encoded.publicBody);
        insertBlob("internal", encoded.internalContentId, SnapshotCodec.INTERNAL_ENCODING, encoded.internalBody);
        for (Map.Entry<String, byte[]> block : encoded.internalBlocks.entrySet()) {
            insertBlob("internal", block.getKey(), SnapshotCodec.ENCODING, block.getValue());
        }
    }

    private void insertBlob(String schema, String contentId, String encoding, byte[] body) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("INSERT OR IGNORE INTO " + schema + ".snapshot_blobs(content_id,encoding,body) VALUES(?,?,?)")) {
            s.setString(1, contentId); s.setString(2, encoding); s.setBytes(3, body); s.executeUpdate();
        }
    }

    private boolean requestExists(String scope, String id) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("SELECT 1 FROM main.requests WHERE scope_id=? AND id=?")) {
            s.setString(1, scope); s.setString(2, id);
            try (ResultSet rs = s.executeQuery()) { return rs.next(); }
        }
    }

    private String metadata(String schema, String key) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("SELECT value FROM " + schema + ".metadata WHERE key=?")) {
            s.setString(1, key);
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private void putMetadata(String schema, String key, String value) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO " + schema + ".metadata(key,value) VALUES(?,?)")) {
            s.setString(1, key); s.setString(2, value); s.executeUpdate();
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        requireOpen();
        boolean started = false;
        boolean commitAttempted = false;
        try {
            writer.setAutoCommit(false);
            started = true;
            T result = work.run();
            advancePairGeneration();
            commitAttempted = true;
            writer.commit();
            writer.setAutoCommit(true);
            return result;
        } catch (Exception error) {
            boolean rolledBack = false;
            if (started) {
                try { writer.rollback(); rolledBack = true; }
                catch (SQLException rollback) { error.addSuppressed(rollback); }
            }
            if (commitAttempted || !rolledBack) {
                // A failed commit may have become durable. Never continue on an ambiguous writer.
                poisoned = true;
                closeQuietly(publicReader);
                closeQuietly(writer);
            } else {
                try { writer.setAutoCommit(true); }
                catch (SQLException reset) {
                    error.addSuppressed(reset); poisoned = true;
                    closeQuietly(publicReader); closeQuietly(writer);
                }
            }
            if (error instanceof AuditException) throw (AuditException) error;
            throw failure("AUDIT_WRITE_FAILED", error);
        }
    }

    /** Detects restoring only one old file from the same profile before any further transaction commits. */
    private void advancePairGeneration() throws SQLException {
        String current = metadata("main", "pair_generation");
        if (!java.util.Objects.equals(current, metadata("internal", "pair_generation"))) {
            throw new SQLException("The paired audit databases were restored from different checkpoints");
        }
        String next = current == null ? "1" : new java.math.BigInteger(current).add(java.math.BigInteger.ONE).toString();
        for (String schema : schemas()) {
            try (PreparedStatement s = writer.prepareStatement("INSERT INTO " + schema + ".metadata(key,value) VALUES('pair_generation',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                s.setString(1, next); s.executeUpdate();
            }
        }
    }

    private void requireAttempt(Attempt attempt) {
        requireOpen();
        if (attempt == null || attempt.owner != this) throw new IllegalArgumentException("Attempt belongs to another store");
    }
    private void requireOpen() {
        if (closed) throw new IllegalStateException("Audit store is closed");
        if (poisoned) throw failure("AUDIT_UNAVAILABLE", null);
    }
    private static String[] schemas() { return new String[]{"main", "internal"}; }
    private static String now() { return Instant.now().toString(); }
    private static AuditException failure(String code, Throwable cause) { return new AuditException(code, "The local audit database operation failed", cause); }
    private static void closeQuietly(Connection connection) {
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
    }
    @FunctionalInterface private interface SqlWork<T> { T run() throws Exception; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        SQLException failure = null;
        try { if (publicReader != null) publicReader.close(); } catch (SQLException error) { failure = error; }
        try { if (writer != null) writer.close(); } catch (SQLException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw failure("AUDIT_CLOSE_FAILED", failure);
    }
}
