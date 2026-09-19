package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import static org.junit.Assert.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

public class WireAuditTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void keepsExactWireBytesInBothDatabasesIncludingInvalidUtf8()throws Exception{
        Path root=temporary.newFolder("中文 profile").toPath();
        byte[] valid="  {\"id\":\"w1\"}\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] invalid={123,34,120,34,58,34,(byte)255,34,125,10};
        try(AuditStore store=new AuditStore(root)){
            AuditStore.Attempt first=store.begin(store.menuScope(),"w1","state.get","  {\"id\":\"w1\"}\r",valid,"utf8-lf");
            store.complete(first,"COMPLETED",map("id","w1","ok",true),map(),map(),null);
            AuditStore.Attempt bad=store.begin(null,null,null,"",invalid,"invalid-utf8");
            store.complete(bad,"REJECTED",map("id",null,"ok",false),null,null,null);
            for(String file:new String[]{"public.sqlite3","internal.sqlite3"})try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve(file));Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT raw_bytes,raw_format FROM exchanges ORDER BY sequence")){
                assertTrue(r.next());assertArrayEquals(valid,r.getBytes(1));assertEquals("utf8-lf",r.getString(2));
                assertTrue(r.next());assertArrayEquals(invalid,r.getBytes(1));assertEquals("invalid-utf8",r.getString(2));
            }
        }
    }
    @Test public void invalidSurrogateCannotReserveTheLiteralQuestionMarkId()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            AuditStore.Attempt invalid=store.begin(store.menuScope(),"\ud800","state.get","{\"id\":\"\\ud800\"}");
            assertFalse(invalid.registered);
            AuditStore.Attempt valid=store.begin(store.menuScope(),"?","state.get","{\"id\":\"?\"}");
            assertTrue(valid.registered);assertFalse(valid.duplicate);
        }
    }
    @Test public void rejectsLegacyTextRecordsWithoutChangingTheirWireProvenance()throws Exception{
        Path root=temporary.newFolder().toPath();String scope;
        try(AuditStore store=new AuditStore(root)){
            scope=store.menuScope();AuditStore.Attempt a=store.begin(scope,"old","state.get","{\"id\":\"old\"}");
            store.complete(a,"COMPLETED",map("id","old","ok",true),map(),map(),null);
        }
        for(String name:new String[]{"public.sqlite3","internal.sqlite3"})try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve(name));Statement s=c.createStatement()){
            s.execute("ALTER TABLE exchanges DROP COLUMN raw_bytes");s.execute("ALTER TABLE exchanges DROP COLUMN raw_format");
            s.execute("UPDATE metadata SET value='2' WHERE key='schema_version'");
        }
        AuditSchemaNineTest.assertRejectedWithoutChanges(root);
    }
}
