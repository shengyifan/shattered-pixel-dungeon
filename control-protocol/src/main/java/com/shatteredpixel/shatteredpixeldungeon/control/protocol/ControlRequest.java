package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import java.math.BigInteger;
import java.util.*;

/** Strict protocol 3 envelope parsing; gameplay availability and argument semantics belong to the coordinator. */
public final class ControlRequest {
    public static final int PROTOCOL_VERSION = 3;
    private static final Set<String> ENVELOPE = new HashSet<>(Arrays.asList("v","id","s","rev","op"));
    private static final Set<String> DETAILS = new HashSet<>(Arrays.asList("raw","reply","before","after","meta"));

    public final int protocolVersion;
    public final String id;
    public final String scopeId;
    /** Canonical engine operation; actions use action.execute internally. */
    public final String op;
    public final String wireOp;
    public final String stateVersion;
    public final Map<String, Object> args;
    /** Original, unmodified protocol 3 request for durable audit. */
    public final Map<String, Object> raw;
    public final boolean sources;
    public final Set<String> details;

    private ControlRequest(Map<String, Object> raw) {
        protocolVersion = protocolVersion(raw);
        this.raw = Collections.unmodifiableMap(raw);
        id = string(raw, "id", true, 128);
        scopeId = string(raw, "s", false, 256);
        wireOp = string(raw, "op", true, 128);
        stateVersion = string(raw, "rev", false, 256);
        String canonical = WireNames.canonicalOperation(wireOp);
        if(canonical==null) throw new ProtocolException("UNKNOWN_OPERATION", "Unknown protocol 3 operation: " + wireOp);
        op = WireNames.isQuery(wireOp) ? canonical : "action.execute";
        Map<String,Object> arguments = new LinkedHashMap<>();
        if(!WireNames.isQuery(wireOp)) arguments.put("action", canonical);
        for(Map.Entry<String,Object> entry:raw.entrySet()) {
            String key=entry.getKey();
            if(ENVELOPE.contains(key)) continue;
            if(!WireNames.parameters(wireOp).contains(key))
                throw new ProtocolException("INVALID_REQUEST", "Unsupported field for " + wireOp + ": " + key);
            if(!"src".equals(key) && !"get".equals(key))
                arguments.put(WireNames.canonicalField(key),"dir".equals(key)?WireNames.canonicalDirection(entry.getValue()):entry.getValue());
        }
        if(raw.containsKey("src") && !(raw.get("src") instanceof Boolean))
            throw new ProtocolException("INVALID_REQUEST", "src must be a boolean");
        sources=Boolean.TRUE.equals(raw.get("src"));
        Set<String> requestedDetails=new LinkedHashSet<>();
        if(raw.containsKey("get")) {
            if(!(raw.get("get") instanceof List)) throw new ProtocolException("INVALID_REQUEST", "get must be an array");
            for(Object detail:(List<?>)raw.get("get")) {
                if(!(detail instanceof String) || !DETAILS.contains(detail))
                    throw new ProtocolException("INVALID_REQUEST", "Unknown request detail");
                if(!requestedDetails.add((String)detail)) throw new ProtocolException("INVALID_REQUEST", "Duplicate request detail");
            }
        }
        details=Collections.unmodifiableSet(requestedDetails);
        args = Collections.unmodifiableMap(arguments);
    }

    public static ControlRequest parse(String line) { return new ControlRequest(JsonCodec.decode(line)); }

    private static int protocolVersion(Map<String, Object> raw) {
        Object value = raw.get("v");
        if (value == null) throw new ProtocolException("PROTOCOL_VERSION_REQUIRED", "v is required for every request");
        if (!(value instanceof Long) && !(value instanceof BigInteger))
            throw new ProtocolException("INVALID_PROTOCOL_VERSION", "v must be a JSON integer");
        if (!Long.valueOf(PROTOCOL_VERSION).equals(value))
            throw new ProtocolException("UNSUPPORTED_PROTOCOL", "Only v 3 is supported");
        return PROTOCOL_VERSION;
    }

    private static String string(Map<String, Object> map, String key, boolean required, int maximum) {
        Object value = map.get(key);
        if (value == null && !required) return null;
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > maximum)
            throw new ProtocolException("INVALID_REQUEST", key + " must be a non-empty string of at most " + maximum + " characters");
        String text = (String) value;
        if(!Identifiers.valid(text,maximum))throw new ProtocolException("INVALID_REQUEST",key+" is not a valid identifier");
        return text;
    }
}
