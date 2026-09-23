package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import java.math.BigInteger;
import java.util.*;

/** Static wire argument validation only; advertised availability and game rules remain live checks. */
public final class RequestArguments {
    private static final Set<String> ENVELOPE = new HashSet<>(Arrays.asList("v", "id", "s", "rev", "op"));
    private RequestArguments() { }

    public static void validate(String op, Map<String,Object> request) {
        if ("settle".equals(op)) {
            for (String key : request.keySet())
                if (!Arrays.asList("op", "rid", "timeout_ms").contains(key)) fail("settle accepts only rid and timeout_ms");
            if (request.containsKey("rid")) identifier(request.get("rid"), "rid", 128);
            if (request.containsKey("timeout_ms")) integer(request.get("timeout_ms"), "timeout_ms", 1, 5000);
            return;
        }
        Set<String> parameters = WireNames.parameters(op);
        if (parameters == null) fail("Unknown operation");
        for (String key : request.keySet())
            if (!ENVELOPE.contains(key) && !parameters.contains(key)) fail("Unsupported field for " + op + ": " + key);
        for (String key : Arrays.asList("s", "rev"))
            if (request.containsKey(key)) identifier(request.get(key), key, 256);
        if (parameters.contains("ctl")) identifier(request.get("ctl"), "ctl", Integer.MAX_VALUE);
        if (request.containsKey("src")) bool(request.get("src"), "src");
        if (request.containsKey("view")) choice(request.get("view"), "view", "play", "full");
        switch (op) {
            case "move": choice(request.get("dir"), "dir", WireNames.DIRECTIONS.toArray(new String[0])); break;
            case "cell":
                integer(request.get("cell"), "cell", 0, Integer.MAX_VALUE);
                if (request.containsKey("mode")) choice(request.get("mode"), "mode", "act", "examine", "context");
                break;
            case "item": identifier(request.get("loc"), "loc", Integer.MAX_VALUE); break;
            case "cancel": case "req": identifier(request.get("rid"), "rid", 128); break;
            case "click": if (request.containsKey("g")) choice(request.get("g"), "g", "click", "right", "middle", "long"); break;
            case "choose":
                integer(request.get("opt"), "opt", 0, Integer.MAX_VALUE);
                if (request.containsKey("alt")) bool(request.get("alt"), "alt");
                break;
            case "text":
                if (!(request.get("text") instanceof String)) fail("text must be a string");
                if (request.containsKey("submit")) bool(request.get("submit"), "submit");
                break;
            case "value": integer(request.get("value"), "value", Integer.MIN_VALUE, Integer.MAX_VALUE); break;
            case "zoom": integer(request.get("zoom"), "zoom", Integer.MIN_VALUE, Integer.MAX_VALUE); break;
            case "bind_slot": integer(request.get("slot"), "slot", 1, 3); break;
            case "bind_key": integer(request.get("keycode"), "keycode", 1, Integer.MAX_VALUE); break;
            case "pan": case "scroll":
                for (String axis : Arrays.asList("x", "y")) if (request.containsKey(axis)) finiteFloat(request.get(axis), axis);
                break;
            case "history": case "events":
                for (String cursor : Arrays.asList("after", "until"))
                    if (request.containsKey(cursor)) integer(request.get(cursor), cursor, 0, Long.MAX_VALUE);
                if (request.containsKey("limit")) integer(request.get("limit"), "limit", 1, 100);
                break;
            default: break;
        }
        if (request.containsKey("get")) {
            Object value = request.get("get");
            if (!(value instanceof List)) fail("get must be an array");
            Set<Object> found = new HashSet<>();
            for (Object detail : (List<?>) value) {
                choice(detail, "get entry", "raw", "reply", "before", "after", "meta");
                if (!found.add(detail)) fail("get must not contain duplicates");
            }
        }
    }

    private static void identifier(Object value, String field, int maximum) {
        if (!(value instanceof String) || !Identifiers.valid((String)value, maximum)) {
            if("ctl".equals(field))fail("ctl must be the current control ID string, not a node index or template index");
            fail(field + " must be a valid non-empty identifier");
        }
    }

    private static void bool(Object value, String field) {
        if (!(value instanceof Boolean)) fail(field + " must be a boolean");
    }

    private static void choice(Object value, String field, String... choices) {
        if (!(value instanceof String) || !Arrays.asList(choices).contains(value)) fail("Invalid " + field);
    }

    private static void integer(Object value, String field, long minimum, long maximum) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger))
            fail(field + " must be a JSON integer");
        BigInteger number = value instanceof BigInteger ? (BigInteger)value : BigInteger.valueOf(((Number)value).longValue());
        if (number.compareTo(BigInteger.valueOf(minimum)) < 0 || number.compareTo(BigInteger.valueOf(maximum)) > 0)
            fail(field + " is out of range");
    }

    private static void finiteFloat(Object value, String field) {
        if (!(value instanceof Number) || !Float.isFinite(((Number)value).floatValue()))
            fail(field + " must be a finite number representable in map-view coordinates");
    }

    private static void fail(String message) { throw new ProtocolException("INVALID_ARGUMENT", message); }
}
