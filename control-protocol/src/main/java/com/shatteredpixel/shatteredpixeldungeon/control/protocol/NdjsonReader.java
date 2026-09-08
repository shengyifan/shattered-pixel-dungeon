package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Lossless line framing. Invalid UTF-8 is rejected, never silently replaced. */
public final class NdjsonReader {
    public static final class Frame {
        public final byte[] bytes;
        public final String text;
        public final String format;
        public final String receivedAt;
        public final ProtocolException error;
        private Frame(byte[] bytes,String text,String format,ProtocolException error){
            this.bytes=bytes;this.text=text;this.format=format;this.error=error;
            this.receivedAt=java.time.Instant.now().toString();
        }
        public static Frame logical(String text){
            return new Frame(text.getBytes(StandardCharsets.UTF_8),text,"logical-text",null);
        }
    }
    private final InputStream input;
    public NdjsonReader(InputStream input){this.input=input instanceof BufferedInputStream?input:new BufferedInputStream(input);}
    public Frame next()throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        int value;
        while((value=input.read())!=-1){bytes.write(value);if(value=='\n')break;}
        if(bytes.size()==0&&value==-1)return null;
        byte[] raw=bytes.toByteArray();
        int length=raw.length-(value=='\n'?1:0);
        String framing=value=='\n'?"utf8-lf":"utf8-eof";
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw,0,length)).toString();
            return new Frame(raw,text,framing,null);
        }catch(CharacterCodingException error){
            return new Frame(raw,"","invalid-utf8",new ProtocolException("INVALID_ENCODING","A request must use valid UTF-8"));
        }
    }
}
