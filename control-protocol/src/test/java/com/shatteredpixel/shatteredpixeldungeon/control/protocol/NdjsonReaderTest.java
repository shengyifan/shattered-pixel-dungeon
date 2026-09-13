package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class NdjsonReaderTest {
    @Test public void preservesFramingAndUnicodeExactly()throws Exception{
        byte[] raw="{\"id\":\"中文🎮\"}\r\n{}".getBytes(StandardCharsets.UTF_8);
        NdjsonReader reader=new NdjsonReader(new ByteArrayInputStream(raw));
        NdjsonReader.Frame first=reader.next();
        assertArrayEquals("{\"id\":\"中文🎮\"}\r\n".getBytes(StandardCharsets.UTF_8),first.bytes);
        assertEquals("中文🎮",JsonCodec.decode(first.text).get("id"));assertNull(first.error);
        assertEquals("utf8-eof",reader.next().format);assertNull(reader.next());
    }
    @Test public void invalidUtf8IsRetainedAndDoesNotPoisonTheNextFrame()throws Exception{
        byte[] raw={123,34,120,34,58,34,(byte)0xff,34,125,10,123,125,10};
        NdjsonReader reader=new NdjsonReader(new ByteArrayInputStream(raw));
        NdjsonReader.Frame bad=reader.next();
        assertEquals("INVALID_ENCODING",bad.error.code);assertEquals(10,bad.bytes.length);
        assertEquals((byte)0xff,bad.bytes[6]);assertEquals("{}",reader.next().text);
    }
    @Test public void rejectsIdentifiersThatWouldBecomeQuestionMarksInUtf8(){
        assertFalse(Identifiers.valid("\ud800",128));assertFalse(Identifiers.valid("\udfff",128));
        assertTrue(Identifiers.valid("玩家🎮",128));assertTrue(Identifiers.valid("?",128));
        assertThrows(ProtocolException.class,()->ControlRequest.parse("{\"v\":3,\"id\":\"\\ud800\",\"op\":\"state\"}"));
    }
}
