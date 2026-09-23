package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class RequestArgumentsTest {
    @Test public void allOperationsFollowTheSharedStaticIntentCorpusWithoutMutation() throws Exception {
        Map<String,Object> corpus;
        try (InputStream input=getClass().getResourceAsStream("/intent-validation-cases.json")) {
            assertNotNull(input);
            corpus=JsonCodec.decode(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        Set<String> operations=new LinkedHashSet<>(WireNames.operations());operations.add("settle");
        assertEquals(operations,new LinkedHashSet<>((List<?>)corpus.get("operations")));
        for(Object raw:(List<?>)corpus.get("cases")) {
            @SuppressWarnings("unchecked") Map<String,Object> example=(Map<String,Object>)raw;
            @SuppressWarnings("unchecked") Map<String,Object> intent=(Map<String,Object>)example.get("intent");
            String op=(String)intent.get("op"),name=(String)example.get("name");
            String before=JsonCodec.encode(intent);
            if(Boolean.TRUE.equals(example.get("valid"))) RequestArguments.validate(op,intent);
            else assertThrows(name,ProtocolException.class,()->RequestArguments.validate(op,intent));
            assertEquals(name,before,JsonCodec.encode(intent));
            if(!"settle".equals(op)) {
                Map<String,Object> wire=new LinkedHashMap<>(intent);wire.put("v",8);wire.put("id","fixture.1");
                if(Boolean.TRUE.equals(example.get("valid")))ControlRequest.parse(JsonCodec.encode(wire));
                else assertThrows(name,ProtocolException.class,()->ControlRequest.parse(JsonCodec.encode(wire)));
            }
        }
    }
}
