package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import org.junit.Test;

import static org.junit.Assert.*;

public class ControlRequestTest {
    @Test public void everyRequestRequiresAnExplicitProtocolVersion() {
        for (String op : new String[]{"protocol.info", "state.get", "action.execute"}) {
            assertRejected("PROTOCOL_VERSION_REQUIRED", "{\"id\":\"request\",\"op\":\"" + op + "\"}");
            assertRejected("PROTOCOL_VERSION_REQUIRED", request("null", op));
            ControlRequest request = ControlRequest.parse(request("2", op));
            assertEquals(ControlRequest.PROTOCOL_VERSION, request.protocolVersion);
            assertEquals(2L, request.raw.get("protocol_version"));
            assertEquals(op, request.op);
        }
    }

    @Test public void rejectsOldFutureAndOversizedIntegerVersionsWithoutTruncation() {
        for (String version : new String[]{"1", "0", "-2", "3", "4294967298", "18446744073709551618"}) {
            assertRejected("UNSUPPORTED_PROTOCOL", request(version, "protocol.info"));
        }
    }

    @Test public void rejectsStringsBooleansContainersAndDecimalVersionLiterals() {
        for (String version : new String[]{"\"2\"", "true", "false", "[]", "{}", "2.0", "2e0", "2.1"}) {
            assertRejected("INVALID_PROTOCOL_VERSION", request(version, "protocol.info"));
        }
    }

    @Test public void validatesVersionBeforeOperationAndArgumentFields() {
        assertRejected("PROTOCOL_VERSION_REQUIRED", "{\"args\":[]}");
        assertRejected("UNSUPPORTED_PROTOCOL", "{\"protocol_version\":1,\"id\":false,\"op\":[],\"args\":[]}");
        assertRejected("INVALID_REQUEST", "{\"protocol_version\":2,\"id\":false,\"op\":[],\"args\":[]}");
    }

    private static String request(String version, String op) {
        return "{\"protocol_version\":" + version + ",\"id\":\"request\",\"op\":\"" + op + "\"}";
    }

    private static void assertRejected(String code, String raw) {
        ProtocolException failure = assertThrows(ProtocolException.class, () -> ControlRequest.parse(raw));
        assertEquals(code, failure.code);
    }
}
