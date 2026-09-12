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
    public static final int SCHEMA_VERSION = 5;
    private final Path publicPath;
    private final Path internalPath;
    private Connection writer;
    private Connection publicReader;
    private String menuScope;
    private String activeSession;
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
            preflight(directory);
            Files.createDirectories(directory);
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

    /** Checks an existing pair before locks, journals, migration or emergency logs can write to it. */
    public static void preflight(Path root) {
        Path directory = root.toAbsolutePath().normalize();
        Path publicFile = directory.resolve("public.sqlite3"), internalFile = directory.resolve("internal.sqlite3");
        if (Files.exists(publicFile) != Files.exists(internalFile))
            throw new AuditException("AUDIT_PAIR_MISSING", "The audit database pair is incomplete", null);
        if (!Files.exists(publicFile)) return;
        try {
            Class.forName("org.sqlite.JDBC");
            String publicProfile = inspectSchema(publicFile), internalProfile = inspectSchema(internalFile);
            if (!publicProfile.equals(internalProfile))
                throw new AuditException("AUDIT_PAIR_MISMATCH", "The audit database identities differ", null);
        } catch (AuditException error) { throw error; }
        catch (Exception error) {
            throw new AuditException("AUDIT_SCHEMA_UNSUPPORTED", "CLI 2 requires an intact schema-5 audit pair; use a new profile", error);
        }
    }

    private static String inspectSchema(Path file) throws SQLException {
        // Immutable read-only SQLite never opens recovery journals or creates WAL sidecars.
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + file.toUri().toASCIIString() + "?mode=ro&immutable=1");
             Statement statement = reader.createStatement();
             ResultSet rows = statement.executeQuery("SELECT key,value FROM metadata WHERE key IN ('schema_version','profile_id')")) {
            String version = null, profile = null;
            while (rows.next()) {
                if ("schema_version".equals(rows.getString(1))) version = rows.getString(2);
                if ("profile_id".equals(rows.getString(1))) profile = rows.getString(2);
            }
            if (!Integer.toString(SCHEMA_VERSION).equals(version) || profile == null || profile.isEmpty())
                throw new AuditException("AUDIT_SCHEMA_UNSUPPORTED", "CLI 2 requires schema 5 and never migrates older audit databases; use a new profile", null);
            // A version marker cannot authorize silently rebuilding missing audit tables.
            try (Statement shape = reader.createStatement()) {
                for (String sql : new String[]{
                        "SELECT presentation_status,session_id,started_at,settled_at FROM requests LIMIT 0",
                        "SELECT presentation_status,raw_bytes,raw_format,session_id,processing_started_at FROM exchanges LIMIT 0",
                        "SELECT presentation_status,session_id FROM events LIMIT 0",
                        "SELECT content_id FROM snapshots LIMIT 0", "SELECT content_id FROM snapshot_blobs LIMIT 0",
                        "SELECT session_id,build_id,cli_version,protocol_version FROM sessions LIMIT 0", "SELECT scope_id FROM runs LIMIT 0",
                        "SELECT scope_id FROM run_slots LIMIT 0", "SELECT receipt_id FROM save_checkpoints LIMIT 0"}) {
                    try (ResultSet ignored = shape.executeQuery(sql)) { }
                }
            }
            return profile;
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
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".requests (scope_id TEXT NOT NULL, id TEXT NOT NULL, op TEXT, raw_request TEXT NOT NULL, status TEXT NOT NULL, presentation_status TEXT, session_id TEXT, started_at TEXT, settled_at TEXT, state_version TEXT, target_scope TEXT, before_snapshot TEXT, after_snapshot TEXT, response_json TEXT, first_exchange INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(scope_id,id))");
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".exchanges (sequence INTEGER PRIMARY KEY AUTOINCREMENT, scope_id TEXT, id TEXT, raw_request TEXT NOT NULL, raw_bytes BLOB, raw_format TEXT NOT NULL DEFAULT 'logical-utf8', session_id TEXT, processing_started_at TEXT, presentation_status TEXT, duplicate INTEGER NOT NULL, registered INTEGER NOT NULL, received_at TEXT NOT NULL, response_json TEXT, responded_at TEXT, before_snapshot TEXT, after_snapshot TEXT, output_attempted INTEGER NOT NULL DEFAULT 0, output_succeeded INTEGER, output_attempted_at TEXT)");
                    s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, scope_id TEXT, kind TEXT NOT NULL, data_json TEXT NOT NULL, presentation_status TEXT NOT NULL, session_id TEXT, created_at TEXT NOT NULL)");
                    s.execute("CREATE INDEX IF NOT EXISTS " + schema + ".exchange_scope_sequence ON exchanges(scope_id,sequence)");
                }
            }
            try (Statement s = writer.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS internal.exceptions (sequence INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, save_receipt_id TEXT, exchange_id INTEGER, scope_id TEXT, id TEXT, exception_class TEXT NOT NULL, message TEXT, stack_trace TEXT NOT NULL, thread_name TEXT NOT NULL, created_at TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS internal.logs (sequence INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, channel TEXT NOT NULL, text TEXT NOT NULL, created_at TEXT NOT NULL)");
            }
            String publicProfile = metadata("main", "profile_id");
            String privateProfile = metadata("internal", "profile_id");
            if (publicProfile == null && privateProfile == null) {
                publicProfile = UUID.randomUUID().toString();
                for (String schema : schemas()) {
                    putMetadata(schema, "profile_id", publicProfile);
                    putMetadata(schema, "schema_version", Integer.toString(SCHEMA_VERSION));
                }
            } else if (publicProfile == null || !publicProfile.equals(privateProfile)) {
                throw new SQLException("The public and internal audit database identities differ");
            }
            String version = metadata("main", "schema_version");
            if (!java.util.Objects.equals(version, metadata("internal", "schema_version"))) throw new SQLException("Paired audit schema mismatch");
            if (!Integer.toString(SCHEMA_VERSION).equals(version)) throw new SQLException("Unsupported audit schema");
            initializeLifecycle();
            menuScope = "menu:" + publicProfile;
            insertScope(menuScope, "menu", null);
            return null;
        });
    }

    private void initializeLifecycle()throws SQLException {
        for(String schema:schemas()){
            try(Statement s=writer.createStatement()){
                s.execute("CREATE TABLE IF NOT EXISTS "+schema+".sessions(session_id TEXT PRIMARY KEY,profile_id TEXT NOT NULL,boot_id TEXT,build_id TEXT NOT NULL,cli_version TEXT NOT NULL,protocol_version INTEGER NOT NULL,started_at TEXT NOT NULL,ended_at TEXT,recovered_at TEXT,status TEXT NOT NULL,end_reason TEXT)");
                s.execute("CREATE TRIGGER IF NOT EXISTS "+schema+".immutable_session_build BEFORE UPDATE OF build_id,cli_version,protocol_version ON sessions "
                        +"WHEN OLD.build_id IS NOT NEW.build_id OR OLD.cli_version IS NOT NEW.cli_version OR OLD.protocol_version IS NOT NEW.protocol_version "
                        +"BEGIN SELECT RAISE(ABORT,'Session build metadata is immutable'); END");
                s.execute("CREATE TABLE IF NOT EXISTS "+schema+".runs(scope_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,hero_class TEXT,first_observed_at TEXT NOT NULL,ended_at TEXT,outcome TEXT,lifecycle TEXT NOT NULL,last_slot INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS "+schema+".run_slots(scope_id TEXT NOT NULL,slot INTEGER NOT NULL,first_observed_at TEXT NOT NULL,last_saved_at TEXT,PRIMARY KEY(scope_id,slot))");
                s.execute("CREATE TABLE IF NOT EXISTS "+schema+".save_checkpoints(sequence INTEGER PRIMARY KEY AUTOINCREMENT,receipt_id TEXT NOT NULL UNIQUE,session_id TEXT,scope_id TEXT NOT NULL,slot INTEGER NOT NULL,success INTEGER NOT NULL,occurred_at TEXT NOT NULL,recorded_at TEXT NOT NULL,origin_scope_id TEXT,origin_request_id TEXT,following_snapshot TEXT)");
                s.execute("CREATE INDEX IF NOT EXISTS "+schema+".save_scope_sequence ON save_checkpoints(scope_id,sequence)");
                s.execute("CREATE INDEX IF NOT EXISTS "+schema+".save_origin ON save_checkpoints(session_id,origin_scope_id,origin_request_id)");
            }
        }
    }

    public synchronized String beginSession(String bootId,String buildId,String cliVersion,int protocolVersion){
        if(activeSession!=null)throw new IllegalStateException("A session is already active");
        if(!Identifiers.valid(buildId,256)||!Identifiers.valid(cliVersion,64)||protocolVersion!=2)
            throw new IllegalArgumentException("Session build, CLI version and protocol 2 metadata are required");
        String id=UUID.randomUUID().toString(),started=now();
        transaction(()->{
            for(String schema:schemas()){
                try(PreparedStatement s=writer.prepareStatement("UPDATE "+schema+".sessions SET status='INTERRUPTED',recovered_at=?,end_reason='previous_process_did_not_close' WHERE status='ACTIVE'")){
                    s.setString(1,started);s.executeUpdate();
                }
                try(PreparedStatement s=writer.prepareStatement("INSERT INTO "+schema+".sessions(session_id,profile_id,boot_id,started_at,build_id,cli_version,protocol_version,status) VALUES(?,?,?,?,?,?,?,'ACTIVE')")){
                    s.setString(1,id);s.setString(2,menuScope.substring(5));s.setString(3,bootId);s.setString(4,started);
                    s.setString(5,buildId);s.setString(6,cliVersion);s.setInt(7,protocolVersion);s.executeUpdate();
                }
            }
            insertEvent(menuScope,"session.started",JsonCodec.encode(Values.map("session_id",id,"started_at",started,"build_id",buildId,"cli_version",cliVersion,"protocol_version",protocolVersion)),id);
            return null;
        });
        activeSession=id;return id;
    }

    public synchronized void endSession(String status,String reason){
        if(activeSession==null)return;
        if(!"CLOSED".equals(status)&&!"FAILED".equals(status))throw new IllegalArgumentException("Invalid session terminal status");
        String id=activeSession,ended=now();
        transaction(()->{
            for(String schema:schemas())try(PreparedStatement s=writer.prepareStatement("UPDATE "+schema+".sessions SET status=?,ended_at=?,end_reason=? WHERE session_id=? AND status='ACTIVE'")){
                s.setString(1,status);s.setString(2,ended);s.setString(3,reason);s.setString(4,id);
                if(s.executeUpdate()!=1)throw new SQLException("Session is not active");
            }
            insertEvent(menuScope,"session.ended",JsonCodec.encode(Values.map("session_id",id,"status",status,"ended_at",ended,"reason",reason)));
            return null;
        });
        activeSession=null;
    }

    public synchronized String sessionId(){return activeSession;}

    public synchronized void ensureScope(String scopeId, String kind, String runId) {
        if (scopeId == null || kind == null) throw new IllegalArgumentException("Scope and kind are required");
        transaction(() -> { insertScope(scopeId, kind, runId); return null; });
    }

    /** Persisted before starting a new run, so a lost start response still has its original target. */
    public synchronized void linkTarget(Attempt attempt, String targetScopeId) {
        requireAttempt(attempt);
        if (!attempt.registered || targetScopeId == null) throw new IllegalArgumentException("A registered request and target are required");
        String linked=now();
        transaction(() -> {
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET target_scope=?,updated_at=? WHERE scope_id=? AND id=? AND status='RECEIVED' AND target_scope IS NULL")) {
                    s.setString(1, targetScopeId); s.setString(2, linked); s.setString(3, attempt.scopeId); s.setString(4, attempt.id);
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
        transaction(() -> {
            insertEvent(scopeId, kind, json);
            if("run.ended".equals(kind)&&("won".equals(data.get("result"))||"lost".equals(data.get("result")))){
                String ended=now();
                for(String schema:schemas())try(PreparedStatement s=writer.prepareStatement("UPDATE "+schema+".runs SET lifecycle='ended',outcome=?,ended_at=? WHERE scope_id=?")){
                    s.setString(1,(String)data.get("result"));s.setString(2,ended);s.setString(3,scopeId);s.executeUpdate();
                }
            }
            return null;
        });
    }

    /** Public English text and original displayed wording share one durable commit. */
    public synchronized void eventWithOriginalText(String scopeId,String kind,Map<String,Object> data,Map<String,Object> original) {
        if(kind==null)throw new IllegalArgumentException("Event kind is required");
        String english=JsonCodec.encode(data);
        transaction(() -> {
            long eventSequence=insertEvent(scopeId,kind,english);
            String diagnostic=JsonCodec.encode(Values.map("event_sequence",eventSequence,"scope_id",scopeId,"kind",kind,"original_display",original));
            try(PreparedStatement s=writer.prepareStatement("INSERT INTO internal.logs(channel,text,created_at,session_id) VALUES(?,?,?,?)")) {
                s.setString(1,"displayed_text_original");s.setString(2,diagnostic);s.setString(3,now());s.setString(4,activeSession);s.executeUpdate();
            }
            return null;
        });
    }

    public synchronized void recordSave(String scopeId, int slot, boolean success, Throwable error) {
        recordSave(UUID.randomUUID().toString(),scopeId,slot,success,now(),null,null,error);
    }

    public synchronized void recordSave(String receiptId,String scopeId,int slot,boolean success,String occurredAt,
                                        String originScope,String originRequest,Throwable error){
        if(!Identifiers.valid(receiptId,128)||scopeId==null||occurredAt==null)throw new IllegalArgumentException("A save receipt requires identity and time");
        Map<String,Object> receipt=Values.map("receipt_id",receiptId,"scope_id",scopeId,"slot",slot,"success",success,
                "occurred_at",occurredAt,"origin_scope_id",originScope,"origin_request_id",originRequest);
        transaction(() -> {
            if(scopeId.startsWith("run:"))insertScope(scopeId,"run",scopeId.substring(4));
            String recorded=now();
            long sequence=0;
            for(String schema:schemas()){
                try(PreparedStatement s=writer.prepareStatement("INSERT INTO "+schema+".save_checkpoints(receipt_id,session_id,scope_id,slot,success,occurred_at,recorded_at,origin_scope_id,origin_request_id"+(schema.equals("internal")?",sequence":"")+") VALUES(?,?,?,?,?,?,?,?,?"+(schema.equals("internal")?",?":"")+")")){
                    s.setString(1,receiptId);s.setString(2,activeSession);s.setString(3,scopeId);s.setInt(4,slot);s.setInt(5,success?1:0);
                    s.setString(6,occurredAt);s.setString(7,recorded);s.setString(8,originScope);s.setString(9,originRequest);
                    if(schema.equals("internal"))s.setLong(10,sequence);s.executeUpdate();
                }
                if(schema.equals("main"))try(Statement s=writer.createStatement();ResultSet row=s.executeQuery("SELECT last_insert_rowid()")){row.next();sequence=row.getLong(1);}
                if(scopeId.startsWith("run:")){
                    try(PreparedStatement s=writer.prepareStatement("INSERT INTO "+schema+".run_slots(scope_id,slot,first_observed_at,last_saved_at) VALUES(?,?,?,?) ON CONFLICT(scope_id,slot) DO UPDATE SET last_saved_at=CASE WHEN excluded.last_saved_at IS NOT NULL THEN excluded.last_saved_at ELSE last_saved_at END")){
                        s.setString(1,scopeId);s.setInt(2,slot);s.setString(3,occurredAt);s.setString(4,success?occurredAt:null);s.executeUpdate();
                    }
                    try(PreparedStatement s=writer.prepareStatement("UPDATE "+schema+".runs SET last_slot=? WHERE scope_id=?")){s.setInt(1,slot);s.setString(2,scopeId);s.executeUpdate();}
                }
            }
            insertEvent(scopeId, "save", JsonCodec.encode(receipt));
            if (error != null) {
                insertException(null, error);
                try(PreparedStatement s=writer.prepareStatement("UPDATE internal.exceptions SET save_receipt_id=?,scope_id=?,id=? WHERE sequence=last_insert_rowid()")){
                    s.setString(1,receiptId);s.setString(2,originScope);s.setString(3,originRequest);s.executeUpdate();
                }
            }
            return null;
        });
    }

    public synchronized Map<String,Object> latestSave(String scopeId){
        requireOpen();
        try(PreparedStatement s=publicReader.prepareStatement("SELECT * FROM save_checkpoints WHERE scope_id=? ORDER BY sequence DESC LIMIT 1")){
            s.setString(1,scopeId);try(ResultSet rows=s.executeQuery()){return rows.next()?saveReceipt(rows):null;}
        }catch(SQLException error){throw failure("AUDIT_READ_FAILED",error);}
    }

    public synchronized List<Map<String,Object>> requestSaves(String originScope,String originRequest){
        requireOpen();List<Map<String,Object>> result=new ArrayList<>();
        try(PreparedStatement s=publicReader.prepareStatement("SELECT * FROM save_checkpoints WHERE session_id IS ? AND origin_scope_id=? AND origin_request_id=? ORDER BY sequence")){
            s.setString(1,activeSession);s.setString(2,originScope);s.setString(3,originRequest);
            try(ResultSet rows=s.executeQuery()){while(rows.next())result.add(saveReceipt(rows));}
            return result;
        }catch(SQLException error){throw failure("AUDIT_READ_FAILED",error);}
    }

    private Map<String,Object> saveReceipt(ResultSet rows)throws SQLException{
        return Values.map("receipt_id",rows.getString("receipt_id"),"scope_id",rows.getString("scope_id"),"slot",rows.getInt("slot"),
                "success",rows.getInt("success")!=0,"occurred_at",rows.getString("occurred_at"),
                "origin_scope_id",rows.getString("origin_scope_id"),"origin_request_id",rows.getString("origin_request_id"));
    }

    private long insertEvent(String scopeId, String kind, String json) throws SQLException {
        return insertEvent(scopeId,kind,json,activeSession);
    }

    private long insertEvent(String scopeId,String kind,String json,String sessionId)throws SQLException{
        String created = now();
        long sequence;
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO main.events(scope_id,kind,data_json,created_at,session_id,presentation_status) VALUES(?,?,?,?,?,?)")) {
            s.setString(1, scopeId); s.setString(2, kind); s.setString(3, json); s.setString(4, created);s.setString(5,sessionId);s.setString(6,presentationStatus(JsonCodec.decode(json))); s.executeUpdate();
        }
        try (Statement s = writer.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
            if (!rs.next()) throw new SQLException("Missing event identity");
            sequence = rs.getLong(1);
        }
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO internal.events(sequence,scope_id,kind,data_json,created_at,session_id,presentation_status) VALUES(?,?,?,?,?,?,?)")) {
            s.setLong(1, sequence); s.setString(2, scopeId); s.setString(3, kind); s.setString(4, json); s.setString(5, created);s.setString(6,sessionId);s.setString(7,presentationStatus(JsonCodec.decode(json))); s.executeUpdate();
        }
        return sequence;
    }

    public synchronized boolean hasScope(String scopeId) {
        requireOpen();
        try (PreparedStatement s = publicReader.prepareStatement("SELECT 1 FROM scopes WHERE scope_id=?")) {
            s.setString(1, scopeId);
            try (ResultSet rs = s.executeQuery()) { return rs.next(); }
        } catch (SQLException error) { throw failure("AUDIT_READ_FAILED", error); }
    }

    private void insertScope(String scopeId, String kind, String runId) throws SQLException {
        String observed=now();
        for (String schema : schemas()) {
            try (PreparedStatement s = writer.prepareStatement("INSERT OR IGNORE INTO " + schema + ".scopes(scope_id,kind,run_id,created_at) VALUES(?,?,?,?)")) {
                s.setString(1, scopeId); s.setString(2, kind); s.setString(3, runId); s.setString(4, observed); s.executeUpdate();
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
            if("run".equals(kind))try(PreparedStatement s=writer.prepareStatement("INSERT OR IGNORE INTO "+schema+".runs(scope_id,run_id,first_observed_at,lifecycle) VALUES(?,?,?,'active')")){
                s.setString(1,scopeId);s.setString(2,runId);s.setString(3,observed);s.executeUpdate();
            }
        }
    }

    /** A parseable identity is consumed even when later request validation fails. */
    public synchronized Attempt begin(String scopeId, String id, String op, String rawRequest) {
        return begin(scopeId,id,op,rawRequest,rawRequest==null?null:rawRequest.getBytes(StandardCharsets.UTF_8),"logical-text");
    }

    public synchronized Attempt begin(String scopeId,String id,String op,String rawRequest,byte[] wireBytes,String wireFormat){
        return begin(scopeId,id,op,rawRequest,wireBytes,wireFormat,null,null);
    }

    public synchronized Attempt begin(String scopeId,String id,String op,String rawRequest,byte[] wireBytes,String wireFormat,String receivedAt,String processingStartedAt){
        if (rawRequest == null) throw new IllegalArgumentException("The original request is required");
        return transaction(() -> {
            boolean identifiable = Identifiers.valid(scopeId,256) && Identifiers.valid(id,128);
            boolean duplicate = identifiable && requestExists(scopeId, id);
            boolean registered = identifiable && !duplicate;
            String received = receivedAt==null?now():receivedAt;
            String processing=processingStartedAt==null?now():processingStartedAt;
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
            for(String schema:schemas())try(PreparedStatement s=writer.prepareStatement("UPDATE "+schema+".exchanges SET raw_bytes=?,raw_format=?,session_id=?,processing_started_at=? WHERE sequence=?")){
                s.setBytes(1,wireBytes);s.setString(2,wireFormat);s.setString(3,activeSession);s.setString(4,processing);s.setLong(5,sequence);s.executeUpdate();
            }
            if (registered) {
                for (String schema : schemas()) {
                    try (PreparedStatement s = writer.prepareStatement("INSERT INTO " + schema + ".requests(scope_id,id,op,raw_request,status,first_exchange,created_at,updated_at,session_id) VALUES(?,?,?,?,'RECEIVED',?,?,?,?)")) {
                        s.setString(1, scopeId); s.setString(2, id); s.setString(3, op); s.setString(4, rawRequest);
                        s.setLong(5, sequence); s.setString(6, received); s.setString(7, received); s.setString(8,activeSession);s.executeUpdate();
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
        String started=now();
        SnapshotCodec.Encoded snapshots = snapshotBytes(publicBefore, internalBefore);
        transaction(() -> {
            String snapshotId = insertSnapshots(snapshots);
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET status='EXECUTING',state_version=?,before_snapshot=?,updated_at=?,started_at=? WHERE scope_id=? AND id=? AND status='RECEIVED'")) {
                    s.setString(1, stateVersion); s.setString(2, snapshotId); s.setString(3, started);
                    s.setString(4,started);s.setString(5, attempt.scopeId); s.setString(6, attempt.id);
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
        String settled=now();
        String encodedResponse = JsonCodec.encode(response);
        SnapshotCodec.Encoded snapshots = snapshotBytes(publicAfter, internalAfter);
        transaction(() -> {
            String afterId = insertSnapshots(snapshots);
            String completed = now();
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".exchanges SET response_json=?,responded_at=?,after_snapshot=?,before_snapshot=COALESCE(before_snapshot,?),presentation_status=? WHERE sequence=? AND response_json IS NULL")) {
                    s.setString(1, encodedResponse); s.setString(2, completed); s.setString(3, afterId); s.setString(4, afterId);
                    s.setString(5, presentationStatus(response)); s.setLong(6, attempt.exchangeId);
                    if (s.executeUpdate() != 1) throw new SQLException("Exchange already has a response");
                }
                if (attempt.registered) {
                    try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET status=?,response_json=?,after_snapshot=?,before_snapshot=COALESCE(before_snapshot,?),updated_at=?,settled_at=?,presentation_status=? WHERE scope_id=? AND id=? AND status IN ('RECEIVED','EXECUTING')")) {
                        s.setString(1, terminal); s.setString(2, encodedResponse); s.setString(3, afterId); s.setString(4, afterId);
                        s.setString(5, completed);s.setString(6,settled); s.setString(7,presentationStatus(response)); s.setString(8, attempt.scopeId); s.setString(9, attempt.id);
                        if (s.executeUpdate() != 1) throw new SQLException("Request already has a terminal result");
                    }
                }
            }
            linkSaveObservations(attempt,afterId);
            describeObservedRun(response);
            if (error != null) insertException(attempt, error);
            return null;
        });
    }

    private void linkSaveObservations(Attempt attempt,String snapshot)throws SQLException {
        if(!attempt.registered||snapshot==null)return;
        for(String schema:schemas())try(PreparedStatement s=writer.prepareStatement("UPDATE "+schema+".save_checkpoints SET following_snapshot=COALESCE(following_snapshot,?) WHERE session_id IS ? AND origin_scope_id=? AND origin_request_id=?")){
            s.setString(1,snapshot);s.setString(2,activeSession);s.setString(3,attempt.scopeId);s.setString(4,attempt.id);s.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked") private void describeObservedRun(Map<String,Object> response)throws SQLException {
        Object result=response.get("result");if(!(result instanceof Map))return;
        Map<String,Object> data=(Map<String,Object>)result;
        Object scope=data.get("scope_id"),observation=data.get("observation");
        if(!(scope instanceof String)||!((String)scope).startsWith("run:")||!(observation instanceof Map))return;
        Object hero=((Map<?,?>)observation).get("hero");if(!(hero instanceof Map))return;
        Object heroClass=((Map<?,?>)hero).get("class");if(!(heroClass instanceof String))return;
        for(String schema:schemas())try(PreparedStatement s=writer.prepareStatement("UPDATE "+schema+".runs SET hero_class=COALESCE(hero_class,?) WHERE scope_id=?")){
            s.setString(1,(String)heroClass);s.setString(2,(String)scope);s.executeUpdate();
        }
    }

    /** The sole wire response is pending; the logical request remains EXECUTING until settle(). */
    public synchronized void respondPending(Attempt attempt, Map<String, Object> response,
                                            Map<String, Object> publicCurrent, Map<String, Object> internalCurrent) {
        requireAttempt(attempt);
        if (!attempt.registered || response == null) throw new IllegalArgumentException("A registered request and response are required");
        String json = JsonCodec.encode(response);
        String prepared=now();
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
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".exchanges SET response_json=?,responded_at=?,after_snapshot=?,presentation_status=? WHERE sequence=? AND response_json IS NULL")) {
                    s.setString(1, json); s.setString(2, prepared); s.setString(3, snapshotId); s.setString(4,presentationStatus(response)); s.setLong(5, attempt.exchangeId);
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
        String settled=now();
        SnapshotCodec.Encoded snapshots = snapshotBytes(publicAfter, internalAfter);
        transaction(() -> {
            String snapshotId = insertSnapshots(snapshots);
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET status=?,response_json=?,after_snapshot=?,updated_at=?,settled_at=?,presentation_status=? WHERE scope_id=? AND id=? AND status='EXECUTING'")) {
                    s.setString(1, status.toUpperCase(Locale.ROOT)); s.setString(2, json); s.setString(3, snapshotId); s.setString(4, settled);
                    s.setString(5,settled);s.setString(6,presentationStatus(finalResult));s.setString(7, attempt.scopeId); s.setString(8, attempt.id);
                    if (s.executeUpdate() != 1) throw new SQLException("Only an executing request may settle");
                }
            }
            linkSaveObservations(attempt,snapshotId);
            describeObservedRun(finalResult);
            if (error != null) insertException(attempt, error);
            return null;
        });
    }

    /** Records what the writer observed; success does not prove receipt by the client. */
    public synchronized void markOutputAttempt(Attempt attempt, boolean success) {
        requireAttempt(attempt);
        String attempted=now();
        transaction(() -> {
            for (String schema : schemas()) {
                try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".exchanges SET output_attempted=1,output_succeeded=?,output_attempted_at=? WHERE sequence=? AND response_json IS NOT NULL AND output_attempted=0")) {
                    s.setInt(1, success ? 1 : 0); s.setString(2, attempted); s.setLong(3, attempt.exchangeId);
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
            try (PreparedStatement s = writer.prepareStatement("INSERT INTO internal.logs(channel,text,created_at,session_id) VALUES(?,?,?,?)")) {
                s.setString(1, channel); s.setString(2, text); s.setString(3, now());s.setString(4,activeSession); s.executeUpdate();
            }
            return null;
        });
    }

    private void insertException(Attempt attempt, Throwable error) throws SQLException {
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO internal.exceptions(exchange_id,scope_id,id,exception_class,message,stack_trace,thread_name,created_at,session_id) VALUES(?,?,?,?,?,?,?,?,?)")) {
            if (attempt == null) s.setNull(1, java.sql.Types.INTEGER); else s.setLong(1, attempt.exchangeId);
            s.setString(2, attempt == null ? null : attempt.scopeId); s.setString(3, attempt == null ? null : attempt.id);
            s.setString(4, error.getClass().getName()); s.setString(5, error.getMessage()); s.setString(6, stack.toString());
            s.setString(7, Thread.currentThread().getName()); s.setString(8, now());s.setString(9,activeSession); s.executeUpdate();
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
                String recovered=now();
                String status = "EXECUTING".equals(request[2]) ? "UNKNOWN" : "NOT_EXECUTED";
                String response = JsonCodec.encode(Values.map("protocol_version", 2, "id", request[1], "scope_id", request[0], "ok", false,
                        "presentation", Values.map("status", "complete", "diagnostics", java.util.Collections.emptyList()), "error", Values.map("code", status, "message", "UNKNOWN".equals(status)
                                ? "The process stopped before the execution result was recorded; this request will not be replayed"
                                : "The process stopped before dispatch; this request will not be replayed")));
                for (String schema : schemas()) {
                    try (PreparedStatement s = writer.prepareStatement("UPDATE " + schema + ".requests SET status=?,response_json=?,updated_at=?,presentation_status='complete' WHERE scope_id=? AND id=? AND status IN ('RECEIVED','EXECUTING')")) {
                        s.setString(1, status); s.setString(2, response); s.setString(3, recovered);
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
                        "status", rs.getString("status"), "presentation_status",rs.getString("presentation_status"), "state_version", rs.getString("state_version"), "target_scope", rs.getString("target_scope"),"session_id",rs.getString("session_id"),"session_build",sessionBuild(rs.getString("session_id")),
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
        try (PreparedStatement s = publicReader.prepareStatement("SELECT e.sequence,e.scope_id,e.id,e.duplicate,e.registered,e.received_at,e.responded_at,e.output_attempted,e.output_succeeded,e.session_id,e.presentation_status,r.op,r.status FROM exchanges e LEFT JOIN requests r ON r.scope_id=e.scope_id AND r.id=e.id WHERE e.scope_id=? AND e.sequence>? ORDER BY e.sequence LIMIT ?")) {
            s.setString(1, scopeId); s.setLong(2, afterSequence); s.setInt(3, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) result.add(Values.map("sequence", rs.getLong("sequence"), "scope_id", rs.getString("scope_id"),
                        "id", rs.getString("id"), "duplicate", rs.getInt("duplicate") != 0, "registered", rs.getInt("registered") != 0,
                        "op", rs.getString("op"), "request_status", rs.getString("status"),"presentation_status",rs.getString("presentation_status"),"session_id",rs.getString("session_id"),
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
        try (PreparedStatement s = publicReader.prepareStatement("SELECT sequence,scope_id,kind,data_json,created_at,session_id,presentation_status FROM events WHERE scope_id=? AND sequence>? ORDER BY sequence LIMIT ?")) {
            s.setString(1, scopeId); s.setLong(2, afterSequence); s.setInt(3, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) result.add(Values.map("sequence", rs.getLong(1), "scope_id", rs.getString(2), "kind", rs.getString(3),
                        "data", JsonCodec.decode(rs.getString(4)), "created_at", rs.getString(5),"session_id",rs.getString(6),"presentation_status",rs.getString(7)));
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

    private Map<String,Object> sessionBuild(String sessionId) throws SQLException {
        if(sessionId==null)return null;
        try(PreparedStatement query=publicReader.prepareStatement("SELECT build_id,cli_version,protocol_version FROM sessions WHERE session_id=?")) {
            query.setString(1,sessionId);
            try(ResultSet row=query.executeQuery()) {
                if(!row.next())throw new SQLException("Recorded session metadata is missing");
                return Values.map("build_id",row.getString(1),"cli_version",row.getString(2),"protocol_version",row.getInt(3));
            }
        }
    }

    private static String presentationStatus(Map<String, Object> value) {
        Object presentation = value.get("presentation");
        Object status = presentation instanceof Map ? ((Map<?,?>) presentation).get("status") : null;
        if (status == null) return "complete";
        if (!"complete".equals(status) && !"partial".equals(status)) throw new IllegalArgumentException("Invalid presentation status");
        return (String) status;
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
