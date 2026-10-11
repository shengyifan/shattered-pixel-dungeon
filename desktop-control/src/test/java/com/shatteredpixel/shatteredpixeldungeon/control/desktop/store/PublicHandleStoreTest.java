package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;

public class PublicHandleStoreTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void handlesAreTypedPairedDurableAndNeverReassigned()throws Exception{
        Path root=temporary.newFolder().toPath();String scope,oldRevision,session;
        try(AuditStore store=new AuditStore(root)){
            scope=store.publicHandle("scope","run:one");oldRevision=store.publicHandle("revision","epoch-one:1");
            session=store.publicHandle("session","session-one");
            assertEquals("s1",scope);assertEquals("r1",oldRevision);assertEquals("t1",session);
            assertEquals(scope,store.publicHandle("scope","run:one"));
            assertEquals("a1",store.publicHandle("activity","activity:epoch-one:1"));
            assertNull(store.resolveHandle("revision","a1"));assertNull(store.resolveHandle("scope","run:one"));
            assertNull(store.resolveHandle("scope","s0"));assertNull(store.resolveHandle("scope","s01"));
        }
        try(AuditStore store=new AuditStore(root)){
            assertEquals("run:one",store.resolveHandle("scope",scope));
            assertEquals("epoch-one:1",store.resolveHandle("revision",oldRevision));
            assertEquals("r2",store.publicHandle("revision","epoch-two:1"));
            assertEquals("t2",store.publicHandle("session","session-two"));
            assertEquals("p1",store.publicHandle("save","save-one"));
            assertEquals("p2",store.publicHandle("save","save-two"));
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+store.publicDatabase());Statement s=c.createStatement()){
                s.execute("ATTACH DATABASE '"+store.internalDatabase()+"' AS paired");
                try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM (SELECT * FROM public_handles EXCEPT SELECT * FROM paired.public_handles)")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
            }
        }
    }
    @Test public void failedPairedAllocationLeavesNoPublishedHalfMapping()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+store.internalDatabase());Statement s=c.createStatement()){
                s.execute("CREATE TRIGGER fail_alias BEFORE INSERT ON public_handles BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
            }
            assertThrows(AuditException.class,()->store.publicHandle("map","unpublished-map"));
            assertNull(store.resolveHandle("map","m1"));
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+store.internalDatabase());Statement s=c.createStatement()){s.execute("DROP TRIGGER fail_alias");}
            assertEquals("m1",store.publicHandle("map","published-map"));
            assertEquals("published-map",store.resolveHandle("map","m1"));
        }
    }
    @Test public void distinctProfilesMayUseSameShortNumbers()throws Exception{
        try(AuditStore first=new AuditStore(temporary.newFolder().toPath());AuditStore second=new AuditStore(temporary.newFolder().toPath())){
            assertEquals("s1",first.publicHandle("scope","run:first"));
            assertEquals("s1",second.publicHandle("scope","run:second"));
            assertEquals("run:first",first.resolveHandle("scope","s1"));
            assertEquals("run:second",second.resolveHandle("scope","s1"));
        }
    }

    @Test public void revisionAndActivitySuffixesAreDecimalAndContinueAcrossRestart()throws Exception{
        Path root=temporary.newFolder().toPath();
        try(AuditStore store=new AuditStore(root)){
            Map<String,Set<String>> requested=new LinkedHashMap<>();
            for(String kind:Arrays.asList("revision","activity","scope","session","save","map")){
                Set<String> identities=new LinkedHashSet<>();
                for(int number=1;number<=101;number++)identities.add(kind+":epoch-one:"+number);
                requested.put(kind,identities);
            }
            Map<String,String> allocated=store.publicHandles(requested);
            for(String kind:requested.keySet()){
                String prefix=prefix(kind);
                boolean revision="revision".equals(kind)||"activity".equals(kind);
                for(int number=1;number<=101;number++){
                    String canonical=kind+":epoch-one:"+number;
                    String expected=prefix+Integer.toString(number,revision?10:36);
                    assertEquals(expected,allocated.get(AuditStore.handleKey(kind,canonical)));
                    assertEquals(canonical,store.resolveHandle(kind,expected));
                }
                assertCounter(store,kind,102);
            }
            assertPaired(store);
        }
        try(AuditStore store=new AuditStore(root)){
            for(String kind:Arrays.asList("revision","activity")){
                assertEquals(prefix(kind)+"100",store.publicHandle(kind,kind+":epoch-one:100"));
                assertEquals(prefix(kind)+"102",store.publicHandle(kind,kind+":epoch-two:1"));
                assertCounter(store,kind,103);
            }
            assertPaired(store);
        }
    }

    @Test public void decimalAllocationContinuesLegacyBase36CountersWithoutRewritingMappings()throws Exception{
        Path root=temporary.newFolder().toPath();
        try(AuditStore store=new AuditStore(root)){
            for(String kind:Arrays.asList("revision","activity"))seedLegacyHandles(store,kind,46);
        }
        try(AuditStore store=new AuditStore(root)){
            for(String kind:Arrays.asList("revision","activity")){
                for(int number=1;number<=46;number++){
                    String canonical=kind+":legacy:"+number;
                    String oldHandle=prefix(kind)+Integer.toString(number,36);
                    assertEquals(oldHandle,store.publicHandle(kind,canonical));
                    assertEquals(canonical,store.resolveHandle(kind,oldHandle));
                }
                assertEquals(prefix(kind)+"47",store.publicHandle(kind,kind+":current:1"));
                assertEquals(kind+":legacy:36",store.resolveHandle(kind,prefix(kind)+"10"));
                assertCounter(store,kind,48);
            }
            assertPaired(store);
        }
    }

    @Test public void failedDecimalBatchRollsBackBothKindsAndTheirCounters()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            for(String kind:Arrays.asList("revision","activity"))seedLegacyHandles(store,kind,9);
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+store.internalDatabase());Statement s=c.createStatement()){
                s.execute("CREATE TRIGGER fail_activity BEFORE INSERT ON public_handles WHEN NEW.kind='activity' BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
            }
            Map<String,Set<String>> requested=new LinkedHashMap<>();
            requested.put("revision",Collections.singleton("revision:current:1"));
            requested.put("activity",Collections.singleton("activity:current:1"));
            assertThrows(AuditException.class,()->store.publicHandles(requested));
            for(String kind:requested.keySet()){
                assertNull(store.resolveHandle(kind,prefix(kind)+"10"));
                assertCounter(store,kind,10);
            }
            assertPaired(store);
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+store.internalDatabase());Statement s=c.createStatement()){
                s.execute("DROP TRIGGER fail_activity");
            }
            Map<String,String> allocated=store.publicHandles(requested);
            for(String kind:requested.keySet()){
                assertEquals(prefix(kind)+"10",allocated.get(AuditStore.handleKey(kind,kind+":current:1")));
                assertCounter(store,kind,11);
            }
            assertPaired(store);
        }
    }

    @Test public void exhaustedDecimalCounterDoesNotWrapOrPublishAnEarlierBatchAllocation()throws Exception{
        for(String kind:Arrays.asList("revision","activity")){
            try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
                setCounter(store,kind,Long.MAX_VALUE-1);
                Set<String> identities=new LinkedHashSet<>(Arrays.asList(kind+":last",kind+":overflow"));
                assertThrows(AuditException.class,()->store.publicHandles(Collections.singletonMap(kind,identities)));
                assertNull(store.resolveHandle(kind,prefix(kind)+(Long.MAX_VALUE-1)));
                assertCounter(store,kind,Long.MAX_VALUE-1);
                assertEquals(prefix(kind)+(Long.MAX_VALUE-1),store.publicHandle(kind,kind+":last"));
                assertCounter(store,kind,Long.MAX_VALUE);
                assertThrows(AuditException.class,()->store.publicHandle(kind,kind+":overflow"));
                assertNull(store.resolveHandle(kind,prefix(kind)+Long.MAX_VALUE));
                assertCounter(store,kind,Long.MAX_VALUE);
                assertPaired(store);
            }
        }
    }

    private static String prefix(String kind){
        switch(kind){
            case "revision":return "r";case "activity":return "a";case "scope":return "s";
            case "session":return "t";case "save":return "p";case "map":return "m";
            default:throw new IllegalArgumentException(kind);
        }
    }

    private static void seedLegacyHandles(AuditStore store,String kind,int count)throws SQLException{
        for(Path database:Arrays.asList(store.publicDatabase(),store.internalDatabase())){
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+database);
                PreparedStatement insert=c.prepareStatement("INSERT INTO public_handles(kind,canonical,handle) VALUES(?,?,?)")){
                c.setAutoCommit(false);
                for(int number=1;number<=count;number++){
                    insert.setString(1,kind);insert.setString(2,kind+":legacy:"+number);
                    insert.setString(3,prefix(kind)+Integer.toString(number,36));insert.addBatch();
                }
                insert.executeBatch();c.commit();
            }
        }
        setCounter(store,kind,count+1);
    }

    private static void setCounter(AuditStore store,String kind,long next)throws SQLException{
        for(Path database:Arrays.asList(store.publicDatabase(),store.internalDatabase())){
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+database);
                PreparedStatement update=c.prepareStatement("INSERT INTO handle_counters(kind,next_value) VALUES(?,?) ON CONFLICT(kind) DO UPDATE SET next_value=excluded.next_value")){
                update.setString(1,kind);update.setLong(2,next);update.executeUpdate();
            }
        }
    }

    private static void assertCounter(AuditStore store,String kind,long expected)throws SQLException{
        for(Path database:Arrays.asList(store.publicDatabase(),store.internalDatabase())){
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+database);
                PreparedStatement query=c.prepareStatement("SELECT next_value FROM handle_counters WHERE kind=?")){
                query.setString(1,kind);
                try(ResultSet row=query.executeQuery()){assertTrue(row.next());assertEquals(expected,row.getLong(1));}
            }
        }
    }

    private static void assertPaired(AuditStore store)throws SQLException{
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+store.publicDatabase());
            PreparedStatement attach=c.prepareStatement("ATTACH DATABASE ? AS paired")){
            attach.setString(1,store.internalDatabase().toString());attach.execute();
            try(Statement s=c.createStatement()){
                for(String table:Arrays.asList("public_handles","handle_counters")){
                    for(String difference:Arrays.asList("SELECT * FROM main."+table+" EXCEPT SELECT * FROM paired."+table,
                            "SELECT * FROM paired."+table+" EXCEPT SELECT * FROM main."+table)){
                        try(ResultSet rows=s.executeQuery("SELECT COUNT(*) FROM ("+difference+")")){
                            assertTrue(rows.next());assertEquals(0,rows.getLong(1));
                        }
                    }
                }
            }
        }
    }
}
