package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Large frames cross many transport-sized buffers; the reader and codec do not cap message bytes. */
public class LargeNdjsonTest {
    @Test public void sixteenMiBUnicodeRequestSurvivesArbitraryInputSplits() throws Exception { verify(16*1024*1024,true,true); }
    @Test public void sixtyFourMiBRequestWithoutFinalNewlineIsComplete() throws Exception { verify(64*1024*1024,false,false); }

    private static void verify(int bytes,boolean unicode,boolean newline) throws Exception {
        byte[] prefix="{\"v\":7,\"id\":\"large\",\"op\":\"text\",\"text\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix=((unicode?"中文🎮":"")+"\"}"+(newline?"\n":"")).getBytes(StandardCharsets.UTF_8);
        int padding=bytes-prefix.length-suffix.length;
        MessageDigest expected=MessageDigest.getInstance("SHA-256");
        expected.update(prefix);byte[] chunk=new byte[65521];Arrays.fill(chunk,(byte)'x');
        for(int remaining=padding;remaining>0;remaining-=Math.min(remaining,chunk.length))expected.update(chunk,0,Math.min(remaining,chunk.length));
        expected.update(suffix);
        NdjsonReader reader=new NdjsonReader(new InputStream() {
            int at;
            @Override public int read() {
                if(at>=bytes)return -1;
                int index=at++;
                if(index<prefix.length)return prefix[index]&255;
                if(index<prefix.length+padding)return 'x';
                return suffix[index-prefix.length-padding]&255;
            }
            @Override public int read(byte[] target,int offset,int length) {
                if(at>=bytes)return -1;
                // Incommensurate with the reader's own buffer and with UTF-8 sequences.
                int count=Math.min(Math.min(length,4093),bytes-at);
                for(int i=0;i<count;i++)target[offset+i]=(byte)read();
                return count;
            }
        });
        NdjsonReader.Frame frame=reader.next();assertNull(frame.error);assertEquals(bytes,frame.bytes.length);
        assertArrayEquals(expected.digest(),MessageDigest.getInstance("SHA-256").digest(frame.bytes));
        assertEquals(newline?"utf8-lf":"utf8-eof",frame.format);assertNull(reader.next());
        ControlRequest request=ControlRequest.parse(frame.text);
        String content=(String)request.args.get("text");assertEquals(padding+(unicode?4:0),content.length());
        assertEquals('x',content.charAt(padding-1));assertTrue(content.endsWith(unicode?"中文🎮":"x"));
        assertEquals("ui.text",request.args.get("action"));
    }
}
