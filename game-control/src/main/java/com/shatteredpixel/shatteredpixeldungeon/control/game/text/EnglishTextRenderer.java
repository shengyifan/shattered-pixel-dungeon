package com.shatteredpixel.shatteredpixeldungeon.control.game.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure rendering of a frozen token; never reads current resources, language, or game state. */
public final class EnglishTextRenderer {
    private static final int MAX_DEPTH = 64, MAX_NODES = 8192, MAX_TEXT = 131072;
    private static final Set<String> NO_CAPS = new HashSet<>(Arrays.asList("a", "an", "and", "of", "by", "to", "the", "x", "for"));
    private static final Pattern FORMAT_SIZE = Pattern.compile("%(?:[0-9]+\\$)?[-#+ 0,(<]*([0-9]*)(?:\\.([0-9]+))?[a-zA-Z%]");
    private EnglishTextRenderer() { }

    public static Map<String, Object> render(Map<String, Object> token) {
        Result result;
        try {
            result = TextProvenance.isToken(token) ? node(token.get("node"), new Budget(), 0) : unavailable("invalid_source_token");
        } catch (RuntimeException failure) {
            result = unavailable("invalid_source_token");
        }
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("text", result.text);
        rendered.put("source", result.source);
        rendered.put("translation_status", result.reason == null ? "complete" : "partial");
        if (result.reason != null) rendered.put("diagnostic", result.reason);
        return Collections.unmodifiableMap(rendered);
    }

    private static Result node(Object raw, Budget budget, int depth) {
        if (!(raw instanceof Map) || depth > MAX_DEPTH || ++budget.nodes > MAX_NODES) return unavailable("source_limit");
        Map<?, ?> value = (Map<?, ?>) raw;
        String kind = string(value.get("kind"));
        switch (kind) {
            case "unavailable": return unavailable(reason(value.get("reason")));
            case "scalar": {
                Object scalar = value.get("value");
                if ("Character".equals(value.get("scalar_type")) && scalar instanceof String && ((String) scalar).length() == 1)
                    return complete((String) scalar, ((String) scalar).charAt(0), map("kind", kind, "value", scalar));
                if (!(scalar == null || scalar instanceof Boolean || scalar instanceof Byte || scalar instanceof Short
                        || scalar instanceof Integer || scalar instanceof Long || scalar instanceof Float || scalar instanceof Double
                        || scalar instanceof java.math.BigDecimal || scalar instanceof java.math.BigInteger))
                    return unavailable("invalid_scalar");
                if (scalar instanceof Number && !Double.isFinite(((Number) scalar).doubleValue())) return unavailable("invalid_scalar");
                if (scalar instanceof Number) {
                    Number number = (Number) scalar;
                    switch (string(value.get("scalar_type"))) {
                        case "Byte": scalar = number.byteValue(); break;
                        case "Short": scalar = number.shortValue(); break;
                        case "Integer": scalar = number.intValue(); break;
                        case "Long": scalar = number.longValue(); break;
                        case "Float": scalar = Boolean.TRUE.equals(value.get("negative_zero")) ? -0f : number.floatValue(); break;
                        case "Double": scalar = Boolean.TRUE.equals(value.get("negative_zero")) ? -0d : number.doubleValue(); break;
                    }
                }
                return complete(String.valueOf(scalar), scalar, map("kind", kind, "value", scalar));
            }
            case "literal": {
                String origin = string(value.get("origin")), text = string(value.get("value"));
                if (!Arrays.asList("literal", "symbol", "user", "external", "catalog").contains(origin) || text.length() > MAX_TEXT)
                    return unavailable("invalid_literal");
                if ((origin.equals("literal") || origin.equals("catalog")) && !TextProvenance.systemEnglish(text))
                    return unavailable("system_literal_not_english");
                return complete(text, text, map("kind", kind, "origin", origin, "value", text));
            }
            case "language": {
                String code = string(value.get("code")), text = string(value.get("english"));
                if (!code.matches("[A-Za-z0-9-]{1,60}") || text.isEmpty() || text.length() > MAX_TEXT)
                    return unavailable("invalid_language_code");
                return complete(text, text, map("kind", kind, "code", code));
            }
            case "resource": {
                String key = string(value.get("key"));
                if (!TextProvenance.validKey(key)) return unavailable("invalid_resource_key");
                List<Result> arguments = children(value.get("arguments"), budget, depth);
                Result unsafe = unsafe(arguments);
                if (unsafe != null) return unavailable(unsafe.reason);
                Map<String, Object> source = map("kind", kind, "key", key, "args", sources(arguments));
                if (!(value.get("template") instanceof String))
                    return new Result("[key: " + key + "]", "[key: " + key + "]", source, "english_template_missing", false);
                if (value.get("formatted_text") instanceof String) return cachedFormatted(value, source, firstReason(arguments));
                String template = (String) value.get("template");
                return formatted(template, arguments, source, Boolean.TRUE.equals(value.get("formatted")));
            }
            case "resource_reference": {
                String key = string(value.get("key"));
                String diagnostic = string(value.get("reason"));
                if (!TextProvenance.validKey(key) || !("argument_not_displayed".equals(diagnostic) || "format_precision_not_displayed".equals(diagnostic)))
                    return unavailable("invalid_resource_reference");
                String text = "[key: " + key + "]";
                return new Result(text, text, map("kind", "resource", "key", key, "args", Collections.emptyList(), "visibility", "partial"),
                        diagnostic, false);
            }
            case "concat": {
                List<Result> parts = children(value.get("parts"), budget, depth);
                Result unsafe = unsafe(parts);
                if (unsafe != null) return unavailable(unsafe.reason);
                StringBuilder text = new StringBuilder();
                for (Result part : parts) { text.append(part.text); if (text.length() > MAX_TEXT) return unavailable("source_limit"); }
                return new Result(text.toString(), text.toString(), map("kind", kind, "parts", sources(parts)), firstReason(parts), false);
            }
            case "format": {
                Result format = node(value.get("format"), budget, depth + 1);
                List<Result> arguments = children(value.get("arguments"), budget, depth);
                Result unsafe = unsafe(arguments);
                if (format.unsafe || unsafe != null) return unavailable(format.unsafe ? format.reason : unsafe.reason);
                Map<String, Object> source = map("kind", kind, "format", format.source, "args", sources(arguments));
                if (value.get("formatted_text") instanceof String) return cachedFormatted(value, source, format.reason == null ? firstReason(arguments) : format.reason);
                Result result = formatted(format.text, arguments, source, true);
                return result.reason == null && format.reason != null
                        ? new Result(result.text, result.value, result.source, format.reason, false) : result;
            }
            case "decimal": {
                String pattern = string(value.get("pattern"));
                if (!(value.get("formatted") instanceof String) || pattern.length() > 256) return unavailable("invalid_decimal_operation");
                String text = (String) value.get("formatted");
                if (text.length() > MAX_TEXT) return unavailable("source_limit");
                return complete(text, text, map("kind", kind, "pattern", pattern, "formatted", text));
            }
            case "formatted_fragment": {
                String text = string(value.get("text")), specifier = string(value.get("specifier"));
                if (text.length() > MAX_TEXT || specifier.length() > 256) return unavailable("source_limit");
                return complete(text, text, map("kind", kind, "specifier", specifier, "text", text));
            }
            case "formatted_argument": {
                List<Result> fragments = children(value.get("fragments"), budget, depth);
                Result unsafe = unsafe(fragments);
                if (unsafe != null || fragments.isEmpty()) return unavailable("invalid_formatted_argument");
                String text = fragments.get(0).text;
                return complete(text, text, map("kind", kind, "origin", string(value.get("origin")), "fragments", sources(fragments)));
            }
            case "case": {
                Result input = node(value.get("value"), budget, depth + 1);
                if (input.unsafe) return input;
                String operation = string(value.get("operation")), text;
                switch (operation) {
                    case "capitalize": text = capitalize(input.text); break;
                    case "title_case": text = titleCase(input.text); break;
                    case "upper_case": text = input.text.toUpperCase(Locale.ENGLISH); break;
                    case "lower_case": text = input.text.toLowerCase(Locale.ENGLISH); break;
                    default: return unavailable("invalid_case_operation");
                }
                return new Result(text, text, map("kind", kind, "operation", operation, "value", input.source), input.reason, false);
            }
            case "strip_prefix": {
                Result input = node(value.get("value"), budget, depth + 1);
                String prefix = string(value.get("prefix"));
                if (input.unsafe) return input;
                if (!input.text.startsWith(prefix)) return unavailable("prefix_not_preserved");
                String text = input.text.substring(prefix.length());
                return new Result(text, text, map("kind", kind, "value", input.source, "prefix", prefix), input.reason, false);
            }
            case "displayed": {
                Result input = node(value.get("value"), budget, depth + 1);
                if (input.unsafe) return input;
                String text = Boolean.TRUE.equals(value.get("markup")) ? input.text.replace("**", "").replace("_", "") : input.text;
                return new Result(text, text, map("kind", kind, "value", input.source, "markup", value.get("markup")), input.reason, false);
            }
            case "markup_segment": {
                Result input = node(value.get("value"), budget, depth + 1);
                if (input.unsafe) return input;
                if (!(value.get("index") instanceof Integer || value.get("index") instanceof Long)
                        || !(value.get("count") instanceof Integer || value.get("count") instanceof Long))
                    return unavailable("invalid_markup_segment");
                long index = ((Number)value.get("index")).longValue(), count = ((Number)value.get("count")).longValue();
                String[] parts = input.text.split("\\*\\*|_", -1);
                if (count != parts.length || index < 0 || index >= count)
                    return unavailable("translated_markup_shape_changed");
                String text = parts[(int)index].trim();
                return new Result(text, text, map("kind", kind, "value", input.source, "index", index, "count", count), input.reason, false);
            }
            case "replace": case "slice": {
                Result input = node(value.get("value"), budget, depth + 1);
                if (input.unsafe) return input;
                String text;
                Map<String, Object> source;
                if (kind.equals("replace")) {
                    String oldValue = string(value.get("old")), newValue = string(value.get("new"));
                    text = input.text.replace(oldValue, newValue);
                    source = map("kind", kind, "value", input.source, "old", oldValue, "new", newValue);
                } else {
                    if (!(value.get("begin") instanceof Number) || !(value.get("end") instanceof Number)) return unavailable("invalid_slice_operation");
                    int begin = ((Number) value.get("begin")).intValue(), end = ((Number) value.get("end")).intValue();
                    text = input.text.substring(begin, end);
                    source = map("kind", kind, "value", input.source, "begin", begin, "end", end);
                }
                return new Result(text, text, source, input.reason, false);
            }
            default: return unavailable("unsupported_source_kind");
        }
    }

    private static Result cachedFormatted(Map<?,?> value, Map<String,Object> source, String reason) {
        String text = (String) value.get("formatted_text");
        return text.length() > MAX_TEXT ? unavailable("source_limit") : new Result(text, text, source, reason, false);
    }

    private static Result formatted(String template, List<Result> arguments, Map<String, Object> source, boolean forceFormat) {
        if (!formatWithinBudget(template)) return unavailable("source_limit");
        try {
            Object[] values = new Object[arguments.size()];
            for (int i = 0; i < values.length; i++) values[i] = arguments.get(i).value;
            String text = !forceFormat && values.length == 0 ? template : String.format(Locale.ENGLISH, template, values);
            if (text.length() > MAX_TEXT) return unavailable("source_limit");
            return new Result(text, text, source, firstReason(arguments), false);
        } catch (java.util.IllegalFormatException invalid) {
            return new Result("Text formatting unavailable", "Text formatting unavailable", source, "english_format_unavailable", false);
        }
    }

    static boolean formatWithinBudget(String template) {
        if (template == null || template.length() > MAX_TEXT) return false;
        Matcher sizes = FORMAT_SIZE.matcher(template);
        while (sizes.find()) for (int group = 1; group <= 2; group++) {
            String size = sizes.group(group);
            if (size != null && !size.isEmpty() && (size.length() > 6 || Integer.parseInt(size) > MAX_TEXT))
                return false;
        }
        return true;
    }

    private static List<Result> children(Object raw, Budget budget, int depth) {
        if (!(raw instanceof List) || ((List<?>) raw).size() > MAX_NODES) return Collections.singletonList(unavailable("invalid_source_children"));
        List<Result> result = new ArrayList<>();
        for (Object child : (List<?>) raw) result.add(node(child, budget, depth + 1));
        return result;
    }
    private static Result unsafe(List<Result> values) { for (Result value : values) if (value.unsafe) return value; return null; }
    private static String firstReason(List<Result> values) { for (Result value : values) if (value.reason != null) return value.reason; return null; }
    private static List<Object> sources(List<Result> values) {
        List<Object> result = new ArrayList<>();
        for (Result value : values) result.add(value.source);
        return Collections.unmodifiableList(result);
    }
    private static String string(Object value) { return value instanceof String ? (String) value : ""; }
    private static String reason(Object value) {
        String reason = string(value);
        return reason.matches("[a-z_]{1,80}") ? reason : "text_source_unavailable";
    }
    private static String capitalize(String text) { return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ENGLISH) + text.substring(1); }
    private static String titleCase(String text) {
        StringBuilder result = new StringBuilder();
        for (String word : text.split("(?<=\\p{Zs})")) {
            if (NO_CAPS.contains(word.trim().toLowerCase(Locale.ENGLISH).replaceAll(":|[0-9]", ""))) result.append(word);
            else result.append(capitalize(word));
        }
        return capitalize(result.toString());
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(result);
    }
    private static Result complete(String text, Object value, Map<String, Object> source) { return new Result(text, value, source, null, false); }
    private static Result unavailable(String reason) {
        String text = "clipped_text".equals(reason) ? "Partially displayed text" : "Text unavailable";
        return new Result(text, text, null, reason, true);
    }
    private static final class Budget { int nodes; }
    private static final class Result {
        final String text, reason;
        final Object value;
        final Map<String, Object> source;
        final boolean unsafe;
        Result(String text, Object value, Map<String, Object> source, String reason, boolean unsafe) {
            this.text = text; this.value = value; this.source = source; this.reason = reason; this.unsafe = unsafe;
        }
    }
}
