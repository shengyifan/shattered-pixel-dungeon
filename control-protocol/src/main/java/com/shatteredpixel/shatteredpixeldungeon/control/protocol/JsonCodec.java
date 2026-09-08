package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict JSON only: no reflection, polymorphic types, object constructors or game-class serialization. */
public final class JsonCodec {
    private static final int MAX_DEPTH = 64;

    private JsonCodec() {}

    public static Map<String, Object> decode(String text) {
        if (text == null) throw invalid("Missing JSON");
        Parser parser = new Parser(text);
        Object value = parser.value(0);
        parser.space();
        if (parser.at != text.length()) throw invalid("Trailing JSON content");
        if (!(value instanceof Map)) throw invalid("Request must be a JSON object");
        @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, new IdentityHashMap<>(), 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, IdentityHashMap<Object, Boolean> active, int depth) {
        if (depth > MAX_DEPTH) throw invalid("JSON nesting is too deep");
        if (value == null) { out.append("null"); return; }
        if (value instanceof String) { quote((String) value, out); return; }
        if (value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal) { out.append(value); return; }
        if (value instanceof Float || value instanceof Double) {
            if (!Double.isFinite(((Number) value).doubleValue())) throw invalid("Non-finite JSON number");
            out.append(value); return;
        }
        if (!(value instanceof Map) && !(value instanceof List)) throw invalid("Only JSON values, maps and lists may be encoded");
        if (active.put(value, Boolean.TRUE) != null) throw invalid("Cyclic JSON value");
        try {
            boolean first = true;
            if (value instanceof Map) {
                out.append('{');
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw invalid("JSON object keys must be strings");
                    if (!first) out.append(',');
                    first = false;
                    quote((String) entry.getKey(), out);
                    out.append(':');
                    write(entry.getValue(), out, active, depth + 1);
                }
                out.append('}');
            } else {
                out.append('[');
                for (Object item : (List<?>) value) {
                    if (!first) out.append(',');
                    first = false;
                    write(item, out, active, depth + 1);
                }
                out.append(']');
            }
        } finally { active.remove(value); }
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                        out.append(hex);
                    } else out.append(c);
            }
        }
        out.append('"');
    }

    private static ProtocolException invalid(String message) { return new ProtocolException("INVALID_JSON", message); }

    private static final class Parser {
        final String text;
        int at;
        Parser(String text) { this.text = text; }
        void space() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
                at++;
            }
        }
        Object value(int depth) {
            if (depth > MAX_DEPTH) throw invalid("JSON nesting is too deep");
            space();
            if (at >= text.length()) throw invalid("Incomplete JSON value");
            char c = text.charAt(at);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (c == 't') return literal("true", Boolean.TRUE);
            if (c == 'f') return literal("false", Boolean.FALSE);
            if (c == 'n') return literal("null", null);
            if (c == '-' || digit(c)) return number();
            throw invalid("Unexpected JSON token");
        }
        Object literal(String token, Object value) {
            if (!text.startsWith(token, at)) throw invalid("Invalid JSON literal");
            at += token.length(); return value;
        }
        Map<String, Object> object(int depth) {
            at++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            do {
                space();
                if (at >= text.length() || text.charAt(at) != '"') throw invalid("Expected JSON object key");
                String key = string();
                if (result.containsKey(key)) throw invalid("Duplicate JSON object key");
                space(); require(':');
                result.put(key, value(depth + 1));
                space();
                if (take('}')) return result;
                require(',');
            } while (true);
        }
        List<Object> array(int depth) {
            at++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            do {
                result.add(value(depth + 1));
                space();
                if (take(']')) return result;
                require(',');
            } while (true);
        }
        String string() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw invalid("Unescaped control character");
                if (c != '\\') { result.append(c); continue; }
                if (at >= text.length()) throw invalid("Incomplete JSON escape");
                switch (text.charAt(at++)) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        if (at + 4 > text.length()) throw invalid("Incomplete Unicode escape");
                        int cp = 0;
                        for (int i = 0; i < 4; i++) {
                            char hexCharacter = text.charAt(at++);
                            int hex = hexCharacter >= '0' && hexCharacter <= '9' ? hexCharacter - '0'
                                    : hexCharacter >= 'a' && hexCharacter <= 'f' ? hexCharacter - 'a' + 10
                                    : hexCharacter >= 'A' && hexCharacter <= 'F' ? hexCharacter - 'A' + 10 : -1;
                            if (hex < 0) throw invalid("Invalid Unicode escape");
                            cp = (cp << 4) | hex;
                        }
                        result.append((char) cp); break;
                    default: throw invalid("Invalid JSON escape");
                }
            }
            throw invalid("Unterminated JSON string");
        }
        Number number() {
            int start = at;
            take('-');
            if (!take('0')) {
                if (at >= text.length() || !digit(text.charAt(at))) throw invalid("Invalid JSON number");
                while (at < text.length() && digit(text.charAt(at))) at++;
            }
            boolean decimal = false;
            if (take('.')) { decimal = true; digits(); }
            if (take('e') || take('E')) {
                decimal = true;
                if (!take('+')) take('-');
                digits();
            }
            String token = text.substring(start, at);
            try {
                if (decimal) return new BigDecimal(token);
                try { return Long.valueOf(token); }
                catch (NumberFormatException large) { return new BigInteger(token); }
            } catch (NumberFormatException bad) { throw invalid("Invalid JSON number"); }
        }
        void digits() {
            int start = at;
            while (at < text.length() && digit(text.charAt(at))) at++;
            if (at == start) throw invalid("Invalid JSON number");
        }
        boolean take(char c) {
            if (at < text.length() && text.charAt(at) == c) { at++; return true; }
            return false;
        }
        void require(char c) { if (!take(c)) throw invalid("Expected JSON punctuation"); }
        boolean digit(char c) { return c >= '0' && c <= '9'; }
    }
}
