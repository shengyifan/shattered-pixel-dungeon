package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class MachineSessionWireTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void invalidUtf8HasNoInventedIdAndDoesNotDesynchronizeTheNextRequest()throws Exception{
        byte[] invalid={(byte)0xc3,(byte)0x28,(byte)'\n'};
        byte[] valid="{\"v\":7,\"id\":\"wire-ok\",\"op\":\"info\"}\r\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream input=new ByteArrayOutputStream();input.write(invalid);input.write(valid);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            GameController.State state=new GameController.State(store.menuScope(),"test:1","menu_ready",map("scene","menu"),map("test_only",true),Collections.emptyList());
            MachineSession.GamePort game=new MachineSession.GamePort(){
                public GameController.State latest(){return state;}
                public CompletableFuture<GameController.State> observe(){return CompletableFuture.completedFuture(state);}
                public CompletableFuture<GameController.State> execute(String version,Map<String,Object> args){throw new AssertionError("No gameplay is needed for wire parsing");}
                public void prepareRun(String id){}
                public GameController.SaveResult pollSave(){return null;}
                public boolean exiting(){return false;}
                public boolean disposed(){return true;}
                public void exitNow(){}
            };
            try(MachineSession session=new MachineSession(store,game,new PrintStream(output,true,"UTF-8"),100)){
                session.read(new ByteArrayInputStream(input.toByteArray()));
            }
            String[] lines=output.toString("UTF-8").strip().split("\\R");assertEquals(2,lines.length);
            Map<String,Object> first=JsonCodec.decode(lines[0]);assertNull(first.get("id"));
            assertEquals("INVALID_ENCODING",first.get("err"));
            Map<String,Object> second=JsonCodec.decode(lines[1]);assertEquals("wire-ok",second.get("id"));assertFalse(second.containsKey("err"));
            for(java.nio.file.Path database:Arrays.asList(store.publicDatabase(),store.internalDatabase())){
                try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+database);Statement statement=db.createStatement();
                    ResultSet rows=statement.executeQuery("SELECT id,raw_bytes FROM exchanges ORDER BY sequence")){
                    assertTrue(rows.next());assertNull(rows.getString(1));assertArrayEquals(invalid,rows.getBytes(2));
                    assertTrue(rows.next());assertEquals("wire-ok",rows.getString(1));assertArrayEquals(valid,rows.getBytes(2));
                    assertFalse(rows.next());
                }
            }
        }
    }
}
