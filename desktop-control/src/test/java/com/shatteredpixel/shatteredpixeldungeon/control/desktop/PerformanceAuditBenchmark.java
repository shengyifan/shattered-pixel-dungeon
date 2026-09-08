package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.SnapshotCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Explicit test-owned fixture files only. Never expose this benchmark as a shipped CLI operation. */
public final class PerformanceAuditBenchmark {
    private static volatile Object sink;
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Isolated fixture profile and synthetic request count required");
        Path profile=Path.of(args[0]).toRealPath();
        Path allowed=Path.of("desktop-control/build/fixtures").toRealPath();
        if(!profile.startsWith(allowed)||!Files.exists(profile.resolve("test_fixture.json")))throw new IllegalArgumentException("Not a test fixture");
        int count=Integer.parseInt(args[1]);if(count<10||count>10000)throw new IllegalArgumentException("Invalid synthetic history size");
        Map<String,Object> pub=JsonCodec.decode(Files.readString(profile.resolve("benchmark-public.json")));
        Map<String,Object> internal=JsonCodec.decode(Files.readString(profile.resolve("benchmark-internal.json")));
        byte[] publicJson=JsonCodec.encode(pub).getBytes(StandardCharsets.UTF_8),internalJson=JsonCodec.encode(internal).getBytes(StandardCharsets.UTF_8);
        SnapshotCodec.Encoded encoded=SnapshotCodec.encodePair(pub,internal);
        String restored=JsonCodec.encode(SnapshotCodec.decodeInternal(encoded.internalBody,encoded.internalBlocks::get));
        if(!restored.equals(JsonCodec.encode(internal))||!JsonCodec.encode(SnapshotCodec.decode(encoded.publicBody)).equals(JsonCodec.encode(pub)))
            throw new AssertionError("Full snapshot round trip changed a field");
        Map<String,Object> report=map("test_fixture",true,"counts_as_win",false,"environment",environment(),"warmup",5,"samples",25,
                "synthetic_history_requests",count,"synthetic_history_model","Repeated complete state.get records from one real fixture checkpoint, not a long game or evolving history",
                "lossless_roundtrip_verified",true,
                "sizes",map("public_json_bytes",publicJson.length,"internal_json_bytes",internalJson.length,"public_gzip_bytes",encoded.publicBody.length,
                        "internal_root_gzip_bytes",encoded.internalBody.length,"internal_block_count",encoded.internalBlocks.size(),
                        "internal_unique_blocks_gzip_bytes",encoded.internalBlocks.values().stream().mapToLong(b->b.length).sum()),
                "json_encode_public_ms",measure(()->JsonCodec.encode(pub)),"json_encode_internal_ms",measure(()->JsonCodec.encode(internal)),
                "gzip_preencoded_pair_ms",measure(()->Arrays.asList(gzip(publicJson),gzip(internalJson))),
                "production_encode_pair_ms",measure(()->SnapshotCodec.encodePair(pub,internal)),
                "internal_restore_in_memory_ms",measure(()->SnapshotCodec.decodeInternal(encoded.internalBody,encoded.internalBlocks::get)));
        Path audit=profile.resolve("synthetic-audit");
        if(Files.exists(audit))throw new IllegalArgumentException("Never append to an old benchmark audit");
        List<Double> beginTimes=new ArrayList<>(),completeTimes=new ArrayList<>(),outputTimes=new ArrayList<>(),commits=new ArrayList<>();
        String scope="benchmark:"+UUID.randomUUID();
        try(AuditStore store=new AuditStore(audit)){
            store.ensureScope(scope,"benchmark",null);
            Field writerField=AuditStore.class.getDeclaredField("writer");writerField.setAccessible(true);Connection original=(Connection)writerField.get(store);
            report.put("sqlite_live_writer_configuration",map("main_journal_mode",scalar(original,"PRAGMA main.journal_mode"),
                    "internal_journal_mode",scalar(original,"PRAGMA internal.journal_mode"),"main_synchronous",scalar(original,"PRAGMA main.synchronous"),
                    "internal_synchronous",scalar(original,"PRAGMA internal.synchronous"),"fullfsync",scalar(original,"PRAGMA fullfsync")));
            // Test-only timing wrapper: every SQL operation still delegates to the unmodified production connection.
            writerField.set(store,Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,arguments)->{
                long start=System.nanoTime();
                try{return method.invoke(original,arguments);}catch(InvocationTargetException failure){throw failure.getCause();}
                finally{if(method.getName().equals("commit"))commits.add((System.nanoTime()-start)/1_000_000.0);}
            }));
            for(int i=0;i<count;i++){
                String id="synthetic-"+i;String raw=JsonCodec.encode(map("id",id,"scope_id",scope,"op","state.get"));
                long start=System.nanoTime();AuditStore.Attempt attempt=store.begin(scope,id,"state.get",raw);beginTimes.add(ms(start));
                Map<String,Object> response=map("id",id,"ok",true,"scope_id",scope,"result",map("observation",pub));
                start=System.nanoTime();store.complete(attempt,"COMPLETED",response,pub,internal,null);completeTimes.add(ms(start));
                start=System.nanoTime();store.markOutputAttempt(attempt,true);outputTimes.add(ms(start));
            }
            report.put("begin_including_paired_commit_ms",beginTimes);report.put("complete_encode_write_commit_ms",completeTimes);
            report.put("output_mark_including_paired_commit_ms",outputTimes);report.put("jdbc_paired_commit_ms",commits);
            report.put("history_first_100_ms",measure(()->store.history(scope,0,100)));
            report.put("history_last_100_ms",measure(()->store.history(scope,Math.max(0,count-100),100)));
            report.put("history_up_to_1000_ms",measure(()->store.history(scope,0,1000)));
            report.put("public_request_get_with_snapshots_ms",measure(()->store.getRequest(scope,"synthetic-"+(count-1))));
            report.put("history_first_100_json_bytes",JsonCodec.encode(store.history(scope,0,100)).getBytes(StandardCharsets.UTF_8).length);
        }
        try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+audit.resolve("internal.sqlite3"));
            PreparedStatement body=db.prepareStatement("SELECT body FROM snapshot_blobs WHERE content_id=?")){
            Supplier<Object> restoreFromDatabase=()->SnapshotCodec.decodeInternal(encoded.internalBody,key->{
                try{body.setString(1,key);try(ResultSet rows=body.executeQuery()){if(!rows.next())throw new AssertionError("Missing block");return rows.getBytes(1);}}
                catch(SQLException failure){throw new IllegalStateException(failure);}
            });
            report.put("internal_restore_database_blocks_ms",measure(restoreFromDatabase));
            report.put("internal_database_bytes",Files.size(audit.resolve("internal.sqlite3")));
            report.put("public_database_bytes",Files.size(audit.resolve("public.sqlite3")));
            report.put("sqlite_configuration",map("journal_mode",scalar(db,"PRAGMA journal_mode"),"synchronous_contract","EXTRA on live writer","fullfsync_contract","ON on live writer"));
            report.put("internal_snapshot_occurrences",scalar(db,"SELECT COUNT(*) FROM snapshots"));
            report.put("internal_stored_unique_blobs",scalar(db,"SELECT COUNT(*) FROM snapshot_blobs"));
            report.put("internal_stored_blob_bytes",scalar(db,"SELECT SUM(length(body)) FROM snapshot_blobs"));
        }
        Files.writeString(profile.resolve("benchmark-audit.json"),JsonCodec.encode(report),StandardCharsets.UTF_8);
        System.out.println(JsonCodec.encode(map("test_fixture",true,"profile",profile.toString(),"synthetic_history_requests",count,"lossless_roundtrip_verified",true)));
    }
    public static Map<String,Object> environment(){return map("os",System.getProperty("os.name"),"os_version",System.getProperty("os.version"),"architecture",System.getProperty("os.arch"),
            "java_version",System.getProperty("java.version"),"java_vendor",System.getProperty("java.vendor"),"processors",Runtime.getRuntime().availableProcessors(),
            "jvm_arguments",ManagementFactory.getRuntimeMXBean().getInputArguments(),"sqlite_jdbc","3.53.4.0");}
    private static Object scalar(Connection db,String sql)throws SQLException{try(Statement s=db.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getObject(1);}}
    private static double ms(long start){return(System.nanoTime()-start)/1_000_000.0;}
    private static List<Double> measure(Supplier<?> task){for(int i=0;i<5;i++)sink=task.get();List<Double> result=new ArrayList<>();for(int i=0;i<25;i++){long start=System.nanoTime();sink=task.get();result.add(ms(start));}return result;}
    private static byte[] gzip(byte[] value){try{ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(out)){gzip.write(value);}return out.toByteArray();}catch(IOException impossible){throw new IllegalStateException(impossible);}}
}
