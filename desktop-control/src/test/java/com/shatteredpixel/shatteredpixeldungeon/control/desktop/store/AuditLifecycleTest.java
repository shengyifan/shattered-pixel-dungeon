package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import static org.junit.Assert.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

public class AuditLifecycleTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String TIME="2026-09-09T01:00:00Z";
    private static Connection open(Path root,String name)throws Exception{return DriverManager.getConnection("jdbc:sqlite:"+root.resolve(name+".sqlite3"));}
    private static Object scalar(Connection c,String sql)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getObject(1);}}
    private static String sharedTable(Path root,String database,String table)throws Exception{
        java.util.List<Object> rows=new java.util.ArrayList<>();
        try(Connection c=open(root,database);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM "+table+" ORDER BY rowid")){
            int count=r.getMetaData().getColumnCount();
            while(r.next()){
                java.util.List<Object> row=new java.util.ArrayList<>();
                for(int i=1;i<=count;i++){Object value=r.getObject(i);row.add(value instanceof byte[]?java.util.Base64.getEncoder().encodeToString((byte[])value):value);}
                rows.add(row);
            }
        }
        return JsonCodec.encode(map("rows",rows));
    }

    @Test public void sessionHistoryDistinguishesCleanCloseInterruptionAndDuplicateArrival()throws Exception{
        Path root=temporary.newFolder().toPath();String first,second,scope;
        try(AuditStore store=new AuditStore(root)){
            scope=store.menuScope();first=store.beginSession("boot-1","fixture-build","CLI.4.0.0",4);
            AuditStore.Attempt a=store.begin(scope,"used","state.get","{}");store.complete(a,"COMPLETED",map("ok",true),map(),map(),null);
            store.endSession("CLOSED","normal_eof");second=store.beginSession("boot-2","fixture-build","CLI.4.0.0",4);
            assertTrue(store.begin(scope,"used","state.get","{}").duplicate);
            // Simulate a process that never wrote its normal session end.
        }
        try(AuditStore store=new AuditStore(root)){
            store.recoverInterrupted();String third=store.beginSession("boot-3","fixture-build","CLI.4.0.0",4);
            for(String name:new String[]{"public","internal"})try(Connection c=open(root,name)){
                assertEquals("CLOSED",scalar(c,"SELECT status FROM sessions WHERE session_id='"+first+"'"));
                assertEquals("INTERRUPTED",scalar(c,"SELECT status FROM sessions WHERE session_id='"+second+"'"));
                assertNull(scalar(c,"SELECT ended_at FROM sessions WHERE session_id='"+second+"'"));
                assertNotNull(scalar(c,"SELECT recovered_at FROM sessions WHERE session_id='"+second+"'"));
                assertEquals(first,scalar(c,"SELECT session_id FROM requests WHERE id='used'"));
                assertEquals(second,scalar(c,"SELECT session_id FROM exchanges WHERE duplicate=1"));
                assertEquals("ACTIVE",scalar(c,"SELECT status FROM sessions WHERE session_id='"+third+"'"));
            }
            store.endSession("CLOSED","done");
            for(String table:new String[]{"sessions","scopes","requests","exchanges","events"})
                assertEquals(table,sharedTable(root,"public",table),sharedTable(root,"internal",table));
        }
    }

    @Test public void everySessionFreezesItsBuildMetadataAcrossCloseRestartAndHistoryReads()throws Exception{
        Path root=temporary.newFolder().toPath();String first,scope;
        try(AuditStore store=new AuditStore(root)){
            scope=store.menuScope();first=store.beginSession("first-boot","build-first","CLI.4.0.0",4);
            AuditStore.Attempt attempt=store.begin(scope,"recorded","state.get","{}");
            store.complete(attempt,"COMPLETED",map("ok",true),map(),map(),null);
            store.endSession("CLOSED","finished");
        }
        try(AuditStore store=new AuditStore(root)){
            String second=store.beginSession("second-boot","build-second","CLI.4.0.0",4);
            assertEquals(map("build_id","build-first","cli_version","CLI.4.0.0","protocol_version",4),
                    store.getRequest(scope,"recorded").get("session_build"));
            for(String side:new String[]{"public","internal"})try(Connection db=open(root,side);Statement statement=db.createStatement()){
                assertEquals("build-first",scalar(db,"SELECT build_id FROM sessions WHERE session_id='"+first+"'"));
                assertEquals("build-second",scalar(db,"SELECT build_id FROM sessions WHERE session_id='"+second+"'"));
                assertEquals("CLI.4.0.0",scalar(db,"SELECT cli_version FROM sessions WHERE session_id='"+first+"'"));
                assertEquals(4,((Number)scalar(db,"SELECT protocol_version FROM sessions WHERE session_id='"+first+"'")).intValue());
                assertThrows(SQLException.class,()->statement.execute("UPDATE sessions SET build_id='rewritten' WHERE session_id='"+first+"'"));
                assertThrows(SQLException.class,()->statement.execute("UPDATE sessions SET cli_version='CLI.9.0.0' WHERE session_id='"+first+"'"));
                assertThrows(SQLException.class,()->statement.execute("UPDATE sessions SET protocol_version=9 WHERE session_id='"+first+"'"));
            }
            java.util.List<java.util.Map<String,Object>> events=store.events(scope,0,100);
            assertEquals("build-first",((java.util.Map<?,?>)events.get(0).get("data")).get("build_id"));
            store.endSession("CLOSED","finished");
            assertEquals(sharedTable(root,"public","sessions"),sharedTable(root,"internal","sessions"));
        }
    }

    @Test public void missingBuildMetadataCannotStartOrRecoverASession()throws Exception{
        Path root=temporary.newFolder().toPath();
        try(AuditStore store=new AuditStore(root)){
            assertThrows(IllegalArgumentException.class,()->store.beginSession("boot",null,"CLI.4.0.0",4));
            assertThrows(IllegalArgumentException.class,()->store.beginSession("boot","build",null,2));
            assertThrows(IllegalArgumentException.class,()->store.beginSession("boot","build","CLI.4.0.0",3));
            assertNull(store.sessionId());
            for(String side:new String[]{"public","internal"})try(Connection db=open(root,side)){
                assertEquals(0,((Number)scalar(db,"SELECT count(*) FROM sessions")).intValue());
                assertEquals(0,((Number)scalar(db,"SELECT count(*) FROM events")).intValue());
            }
        }
    }

    @Test public void saveReceiptsKeepSuccessFailureScopeAndFollowingObservationWithoutLeakingException()throws Exception{
        Path root=temporary.newFolder().toPath();
        try(AuditStore store=new AuditStore(root)){
            store.beginSession("boot","fixture-build","CLI.4.0.0",4);String menu=store.menuScope(),run="run:example";
            store.ensureScope(run,"planned","example");
            AuditStore.Attempt start=store.begin(menu,"same","action.execute","{}");store.markExecuting(start,"v1",map(),map());
            store.recordSave("save-ok",run,1,true,TIME,menu,"same",null);
            store.recordSave("save-bad",run,1,false,TIME,menu,"same",new IOException("private-path-secret"));
            store.recordSave("other-scope",run,1,true,TIME,run,"same",null);
            assertEquals(2,store.requestSaves(menu,"same").size());assertEquals(1,store.requestSaves(run,"same").size());
            assertEquals("other-scope",store.latestSave(run).get("receipt_id"));
            store.recordSave("last-failure",run,2,false,TIME,menu,"same",new IOException("private-path-secret"));
            assertEquals(Boolean.FALSE,store.latestSave(run).get("success"));
            assertFalse(JsonCodec.encode(store.latestSave(run)).contains("private-path-secret"));
            store.complete(start,"COMPLETED",map("v",4,"s",run,"st","completed","data",map("hero",map("class","warrior"))),map("scene","game","hero",map("class","warrior")),map("hidden",1),null,run);
            for(String name:new String[]{"public","internal"})try(Connection c=open(root,name)){
                assertEquals(3,((Number)scalar(c,"SELECT count(*) FROM save_checkpoints WHERE following_snapshot IS NOT NULL")).intValue());
                assertNull(scalar(c,"SELECT following_snapshot FROM save_checkpoints WHERE receipt_id='other-scope'"));
                assertEquals("warrior",scalar(c,"SELECT hero_class FROM runs WHERE scope_id='run:example'"));
                assertEquals(2,((Number)scalar(c,"SELECT count(*) FROM run_slots")).intValue());
            }
            try(Connection c=open(root,"internal")){
                assertEquals(menu,scalar(c,"SELECT scope_id FROM exceptions WHERE save_receipt_id='save-bad'"));
                assertEquals("same",scalar(c,"SELECT id FROM exceptions WHERE save_receipt_id='save-bad'"));
                assertNotNull(scalar(c,"SELECT session_id FROM exceptions WHERE save_receipt_id='save-bad'"));
            }
            for(String table:new String[]{"sessions","scopes","runs","run_slots","requests","exchanges","events","save_checkpoints"})
                assertEquals(table,sharedTable(root,"public",table),sharedTable(root,"internal",table));
        }
    }

    @Test public void endedRunIsNotReopenedByLaterHistoryOrSaveObservation()throws Exception{
        Path root=temporary.newFolder().toPath();
        try(AuditStore store=new AuditStore(root)){
            String run="run:ended";store.ensureScope(run,"planned","ended");
            try(Connection c=open(root,"public")){assertEquals(0,((Number)scalar(c,"SELECT count(*) FROM runs")).intValue());}
            store.ensureScope(run,"run","ended");store.event(run,"run.ended",map("result","won"));
            store.ensureScope(run,"run","ended");store.recordSave("receipt",run,3,true,TIME,null,null,null);
            for(String name:new String[]{"public","internal"})try(Connection c=open(root,name)){
                assertEquals("ended",scalar(c,"SELECT lifecycle FROM runs"));assertEquals("won",scalar(c,"SELECT outcome FROM runs"));
                assertNotNull(scalar(c,"SELECT ended_at FROM runs"));
            }
        }
    }

    @Test public void schemaThreeIsRejectedWithoutInventingSessionsOrChangingWireBytes()throws Exception{
        Path root=temporary.newFolder().toPath();String scope;
        try(AuditStore store=new AuditStore(root)){
            scope=store.menuScope();AuditStore.Attempt a=store.begin(scope,"old","state.get","{}",new byte[]{123,125,13,10},"utf8-lf");
            store.complete(a,"COMPLETED",map("ok",true),map(),map(),null);store.event(scope,"save",map("success",true));
        }
        for(String name:new String[]{"public","internal"})try(Connection c=open(root,name);Statement s=c.createStatement()){
            for(String table:new String[]{"save_checkpoints","run_slots","runs","sessions"})s.execute("DROP TABLE "+table);
            s.execute("ALTER TABLE requests DROP COLUMN session_id");s.execute("ALTER TABLE exchanges DROP COLUMN session_id");
            if(name.equals("internal")){s.execute("ALTER TABLE exceptions DROP COLUMN session_id");s.execute("ALTER TABLE exceptions DROP COLUMN save_receipt_id");s.execute("ALTER TABLE logs DROP COLUMN session_id");}
            s.execute("UPDATE metadata SET value='3' WHERE key='schema_version'");
        }
        AuditSchemaSevenTest.assertRejectedWithoutChanges(root);
    }

    @Test public void failedInternalReceiptInsertRollsBackPublicReceiptScopePromotionAndEvent()throws Exception{
        Path root=temporary.newFolder().toPath();
        try(AuditStore store=new AuditStore(root)){
            store.ensureScope("run:pending","planned","pending");
            try(Connection c=open(root,"internal");Statement s=c.createStatement()){
                s.execute("CREATE TRIGGER reject_receipt BEFORE INSERT ON save_checkpoints BEGIN SELECT RAISE(ABORT,'fixture only');END");
            }
            try{store.recordSave("not-committed","run:pending",1,true,TIME,null,null,null);fail("Expected paired failure");}
            catch(AuditException expected){ }
            for(String name:new String[]{"public","internal"})try(Connection c=open(root,name)){
                assertEquals(0,((Number)scalar(c,"SELECT count(*) FROM save_checkpoints")).intValue());
                assertEquals(0,((Number)scalar(c,"SELECT count(*) FROM runs")).intValue());
                assertEquals("planned",scalar(c,"SELECT kind FROM scopes WHERE scope_id='run:pending'"));
                assertEquals(0,((Number)scalar(c,"SELECT count(*) FROM events WHERE kind='save'")).intValue());
            }
        }
    }
}
