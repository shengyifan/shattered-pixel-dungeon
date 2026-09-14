package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ControlRequestTest {
    @Test public void everyRequestRequiresAnExplicitProtocolVersion() {
        for (String op : new String[]{"info", "state", "move"}) {
            assertRejected("PROTOCOL_VERSION_REQUIRED", "{\"id\":\"request\",\"op\":\"" + op + "\"}");
            assertRejected("PROTOCOL_VERSION_REQUIRED", request("null", op));
            ControlRequest request = ControlRequest.parse(request("4", op));
            assertEquals(ControlRequest.PROTOCOL_VERSION, request.protocolVersion);
            assertEquals(4L, request.raw.get("v"));
            assertEquals(op, request.wireOp);
        }
    }
    @Test public void rejectsOldFutureAndOversizedIntegerVersionsWithoutTruncation() {
        for (String version : new String[]{"1", "2", "0", "-2", "3", "5", "4294967299", "18446744073709551619"})
            assertRejected("UNSUPPORTED_PROTOCOL", request(version, "info"));
    }
    @Test public void rejectsNonIntegerVersionLiterals() {
        for (String version : new String[]{"\"3\"", "true", "false", "[]", "{}", "3.0", "3e0", "3.1"})
            assertRejected("INVALID_PROTOCOL_VERSION", request(version, "info"));
    }
    @Test public void validatesVersionBeforeOtherFields() {
        assertRejected("PROTOCOL_VERSION_REQUIRED", "{\"args\":[]}");
        assertRejected("UNSUPPORTED_PROTOCOL", "{\"v\":2,\"id\":false,\"op\":[],\"args\":[]}");
        assertRejected("INVALID_REQUEST", "{\"v\":4,\"id\":false,\"op\":[]}");
    }
    @Test public void oldEnvelopesOperationsAndArgumentsAreNotAccepted() {
        assertRejected("PROTOCOL_VERSION_REQUIRED", "{\"protocol_version\":2,\"id\":\"x\",\"op\":\"protocol.info\"}");
        assertRejected("UNKNOWN_OPERATION", "{\"v\":4,\"id\":\"x\",\"op\":\"action.execute\"}");
        for(String field:new String[]{"protocol_version","scope_id","state_version","args","action","direction"})
            assertRejected("INVALID_REQUEST", "{\"v\":4,\"id\":\"x\",\"op\":\"move\",\""+field+"\":\"x\"}");
        assertRejected("INVALID_ARGUMENT", "{\"v\":4,\"id\":\"x\",\"op\":\"move\",\"dir\":\"north\"}");
    }
    @Test public void flatOperationsBecomeCanonicalEngineArgumentsWithoutChangingRaw() {
        ControlRequest move=ControlRequest.parse("{\"v\":4,\"id\":\"a\",\"s\":\"run:one\",\"rev\":\"one:9\",\"op\":\"move\",\"dir\":\"NE\"}");
        assertEquals("action.execute",move.op);assertEquals("move.step",move.args.get("action"));
        assertEquals("northeast",move.args.get("direction"));assertEquals("NE",move.raw.get("dir"));
        assertEquals("run:one",move.scopeId);assertEquals("one:9",move.stateVersion);
        for(String op:WireNames.operations()) {
            ControlRequest parsed=ControlRequest.parse(request("4",op));
            assertEquals(WireNames.isQuery(op)?WireNames.canonicalOperation(op):"action.execute",parsed.op);
        }
    }
    @Test public void optionalFrozenSourcesAndReceiptDetailsAreValidated() {
        ControlRequest query=ControlRequest.parse("{\"v\":4,\"id\":\"q\",\"s\":\"run:one\",\"op\":\"req\",\"rid\":\"a\",\"src\":true,\"get\":[\"reply\",\"after\"]}");
        assertTrue(query.sources);assertEquals(new LinkedHashSet<>(Arrays.asList("reply","after")),query.details);
        assertEquals(Collections.singletonMap("target_id","a"),query.args);
        assertRejected("INVALID_REQUEST","{\"v\":4,\"id\":\"q\",\"op\":\"state\",\"src\":1}");
        for(String get:new String[]{"null","true","{}","[1]","[\"internal\"]","[\"raw\",\"raw\"]"})
            assertRejected("INVALID_REQUEST","{\"v\":4,\"id\":\"q\",\"op\":\"req\",\"get\":"+get+"}");
        assertRejected("INVALID_REQUEST","{\"v\":4,\"id\":\"q\",\"op\":\"click\",\"src\":true}");
    }
    @Test public void viewsArePresentationOnlyAndSourcesForceFull() {
        for(String op:Arrays.asList("state","actions")) {
            ControlRequest defaults=ControlRequest.parse(request("4",op));
            assertFalse(defaults.fullView);assertTrue(defaults.args.isEmpty());
            for(String view:Arrays.asList("play","full")) {
                ControlRequest query=ControlRequest.parse("{\"v\":4,\"id\":\"q\",\"op\":\""+op+"\",\"view\":\""+view+"\"}");
                assertEquals("full".equals(view),query.fullView);assertTrue(query.args.isEmpty());
            }
            for(String view:Arrays.asList("null","true","1","[]","{}","\"compact\""))
                assertRejected("INVALID_REQUEST","{\"v\":4,\"id\":\"q\",\"op\":\""+op+"\",\"view\":"+view+"}");
        }
        ControlRequest sources=ControlRequest.parse("{\"v\":4,\"id\":\"q\",\"op\":\"state\",\"view\":\"play\",\"src\":true}");
        assertTrue(sources.fullView);assertTrue(sources.sources);assertTrue(sources.args.isEmpty());
        for(String op:Arrays.asList("req","move","info"))
            assertRejected("INVALID_REQUEST","{\"v\":4,\"id\":\"q\",\"op\":\""+op+"\",\"view\":\"full\"}");
    }
    private static String request(String version, String op) {
        return "{\"v\":" + version + ",\"id\":\"request\",\"op\":\"" + op + "\"}";
    }
    private static void assertRejected(String code, String raw) {
        ProtocolException failure = assertThrows(ProtocolException.class, () -> ControlRequest.parse(raw));
        assertEquals(code, failure.code);
    }
}
