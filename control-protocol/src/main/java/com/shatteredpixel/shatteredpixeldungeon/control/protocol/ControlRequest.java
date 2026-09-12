package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;

/** Parsing checks the envelope only; scope, operation and argument validation belongs to the coordinator. */
public final class ControlRequest {
    public static final int PROTOCOL_VERSION = 2;

    public final int protocolVersion;
    public final String id;
    public final String scopeId;
    public final String op;
    public final String stateVersion;
    public final Map<String, Object> args;
    public final Map<String, Object> raw;

    private ControlRequest(Map<String, Object> raw) {
        protocolVersion = protocolVersion(raw);
        this.raw = Collections.unmodifiableMap(raw);
        id = string(raw, "id", true, 128);
        scopeId = string(raw, "scope_id", false, 256);
        op = string(raw, "op", true, 128);
        stateVersion = string(raw, "state_version", false, 256);
        Object arguments = raw.get("args");
        if (arguments == null) args = Collections.emptyMap();
        else if (arguments instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> parsed = (Map<String, Object>) arguments;
            args = Collections.unmodifiableMap(parsed);
        } else throw new ProtocolException("INVALID_REQUEST", "args must be an object");
    }

    public static ControlRequest parse(String line) {
        return new ControlRequest(JsonCodec.decode(line));
    }

    private static int protocolVersion(Map<String, Object> raw) {
        Object value = raw.get("protocol_version");
        if (value == null) {
            throw new ProtocolException("PROTOCOL_VERSION_REQUIRED", "protocol_version is required for every request");
        }
        if (!(value instanceof Long) && !(value instanceof BigInteger)) {
            throw new ProtocolException("INVALID_PROTOCOL_VERSION", "protocol_version must be a JSON integer");
        }
        if (!Long.valueOf(PROTOCOL_VERSION).equals(value)) {
            throw new ProtocolException("UNSUPPORTED_PROTOCOL", "Only protocol_version 2 is supported");
        }
        return PROTOCOL_VERSION;
    }

    private static String string(Map<String, Object> map, String key, boolean required, int maximum) {
        Object value = map.get(key);
        if (value == null && !required) return null;
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > maximum) {
            throw new ProtocolException("INVALID_REQUEST", key + " must be a non-empty string of at most " + maximum + " characters");
        }
        String text = (String) value;
        if(!Identifiers.valid(text,maximum))throw new ProtocolException("INVALID_REQUEST",key+" is not a valid identifier");
        return text;
    }
}
