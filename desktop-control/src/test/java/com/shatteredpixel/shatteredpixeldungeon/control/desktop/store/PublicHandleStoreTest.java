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
}
