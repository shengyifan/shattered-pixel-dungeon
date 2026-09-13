package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class JsonCodecTest {
    @Test public void keepsLargeIntegersAndDecimalsExactly() {
        Map<String, Object> value = JsonCodec.decode("{\"seed\":9223372036854775808,\"number\":1.234567890123456789,\"text\":\"中文\\n😀\",\"nested\":[true,null,[]]}");
        assertEquals(new BigInteger("9223372036854775808"), value.get("seed"));
        assertEquals(new BigDecimal("1.234567890123456789"), value.get("number"));
        assertEquals(value, JsonCodec.decode(JsonCodec.encode(value)));
    }

    @Test public void rejectsAmbiguousOrNonJsonInput() {
        for (String json : new String[]{"{\"id\":null,\"id\":\"b\"}", "[]", "{\"x\":01}", "{\"x\":NaN}",
                "{\"x\":1.}", "{\"x\":1e}", "{\"x\":true,}", "{\"x\":[1,]}", "{} trailing", "{\"x\":\"\n\"}", "{\"x\":\"\\u０００１\"}"}) {
            assertThrows("Should reject " + json, ProtocolException.class, () -> JsonCodec.decode(json));
        }
    }

    @Test public void refusesGameObjectsAndCyclesWithoutInvokingToString() {
        Object secret = new Object() {
            @Override public String toString() { fail("Must not stringify a game object"); return "secret"; }
        };
        assertThrows(ProtocolException.class, () -> JsonCodec.encode(Values.map("object", secret)));
        assertThrows(ProtocolException.class, () -> JsonCodec.encode(Values.map("number", Double.NaN)));
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        assertThrows(ProtocolException.class, () -> JsonCodec.encode(cyclic));
        assertThrows(ProtocolException.class, () -> JsonCodec.encode(Values.list(new int[]{1, 2})));
    }

    @Test public void serializesSharedAcyclicObjects() {
        Map<String, Object> shared = Values.map("visible", true);
        assertEquals("[{\"visible\":true},{\"visible\":true}]", JsonCodec.encode(Values.list(shared, shared)));
    }

    @Test public void parsesOnlyTheRequestEnvelopeWithoutDemandingActiveScope() {
        ControlRequest request = ControlRequest.parse("{\"v\":3,\"id\":\"query-1\",\"op\":\"info\"}");
        assertEquals("query-1", request.id);
        assertNull(request.scopeId);
        assertNull(request.stateVersion);
        assertTrue(request.args.isEmpty());
        ControlRequest move = ControlRequest.parse("{\"v\":3,\"s\":\"run:uuid\",\"id\":\"a\",\"op\":\"move\",\"rev\":\"boot:7\",\"dir\":\"N\"}");
        assertEquals("boot:7", move.stateVersion);
        assertEquals("north", move.args.get("direction"));
        assertThrows(ProtocolException.class, () -> ControlRequest.parse("{\"v\":3,\"op\":\"state\"}"));
        assertThrows(ProtocolException.class, () -> ControlRequest.parse("{\"v\":3,\"id\":\"a\",\"op\":\"move\",\"args\":[]}"));
    }
}
