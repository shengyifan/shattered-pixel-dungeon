package com.shatteredpixel.shatteredpixeldungeon.control.game.text;

import com.shatteredpixel.shatteredpixeldungeon.control.game.util.WeakIdentityRegistry;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Locale;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.function.Function;
import java.util.function.Supplier;

/** Session-local provenance for the unchanged String presentation API. Never reverse-matches text. */
public final class TextProvenance {
    public static final TextProvenance INSTANCE = new TextProvenance();
    private final WeakIdentityRegistry<TextSource> strings = new WeakIdentityRegistry<>();
    private final WeakIdentityRegistry<Binding> owners = new WeakIdentityRegistry<>();
    private final ThreadLocal<Integer> suppression = ThreadLocal.withInitial(() -> 0);
    private final Function<String, String> englishTemplates;

    public TextProvenance() { this(key -> Templates.VALUES.get(key)); }
    /** Allows isolated resource and frozen-history tests without a running GUI. */
    public TextProvenance(Function<String, String> englishTemplates) { this.englishTemplates = englishTemplates; }

    public String onTextResource(String rendered, String resolvedKey, String language, Object[] arguments) {
        return resource(rendered, resolvedKey, arguments, null, true);
    }

    public String onTextResource(String rendered, String resolvedKey, String language, Object[] arguments, String guiTemplate) {
        return resource(rendered, resolvedKey, arguments, guiTemplate, false);
    }

    /** Compatibility for tests supplying already-certified visible values; native Messages uses the template overload. */
    private String resource(String rendered, String resolvedKey, Object[] arguments, String guiTemplate, boolean certified) {
        if (rendered == null || suppression.get() != 0) return rendered;
        TextSource source;
        try {
            TextSource previous = strings.get(rendered);
            if (previous != null && "original_format_failed".equals(previous.value.get("reason"))) {
                source = previous;
            } else if (!validKey(resolvedKey)) {
                source = TextSource.unknown("invalid_resource_key");
            } else {
                String template = withoutTracking(() -> englishTemplates.apply(resolvedKey));
                boolean formatted = arguments != null && arguments.length > 0;
                if (certified && template == null) source = TextSource.of("resource", "key", resolvedKey, "template", null,
                        "arguments", arguments(arguments), "formatted", formatted);
                else {
                    String sourceTemplate = certified ? template : guiTemplate;
                    Set<Integer> visible = FormatArgumentUsage.indices(sourceTemplate, formatted);
                    Set<Integer> wanted = template == null ? java.util.Collections.emptySet() : FormatArgumentUsage.indices(template, formatted);
                    String fixedPrefix = structuralPrefix(sourceTemplate);
                    if (formatted && fixedPrefix.indexOf('%') >= 0) fixedPrefix = fixedPrefix.substring(0, fixedPrefix.indexOf('%'));
                    if (!visible.containsAll(wanted)) source = reference(resolvedKey, "argument_not_displayed");
                    else {
                        PrecisionCapture precision = precision(arguments, sourceTemplate, template, formatted, visibleArguments(arguments, visible));
                        if (precision.reason != null) source = precision.safeReference ? reference(resolvedKey, precision.reason) : TextSource.unknown(precision.reason);
                        else if (precision.changed && template != null) source = TextSource.of("resource", "key", resolvedKey, "template", template,
                                "arguments", precision.arguments, "formatted", formatted, "formatted_text", precision.english, "fixed_prefix", fixedPrefix);
                        else source = TextSource.of("resource", "key", resolvedKey, "template", template,
                                "arguments", precision.arguments, "formatted", formatted, "fixed_prefix", fixedPrefix);
                    }
                }
            }
        } catch (RuntimeException failure) {
            source = TextSource.unknown("resource_capture_failed");
        }
        return register(rendered, source);
    }

    public String onTextOperation(String operation, String rendered, Object... operands) {
        if (rendered == null || suppression.get() != 0) return rendered;
        TextSource source;
        try {
            switch (operation) {
                case "literal": case "user": case "external":
                    source = operation.equals("literal") && !systemEnglish(rendered) ? TextSource.unknown("system_literal_not_english")
                            : TextSource.of("literal", "origin", operation, "value", rendered); break;
                case "system_catalog":
                    source = strings.get(rendered);
                    if (source == null) source = systemEnglish(rendered) ? TextSource.of("literal", "origin", "catalog", "value", rendered)
                            : TextSource.unknown("system_literal_not_english");
                    break;
                case "language_name":
                    source = operands.length == 1 && operands[0] instanceof String && ((String) operands[0]).matches("[A-Za-z0-9-]{1,60}")
                            ? TextSource.of("language", "code", operands[0], "english",
                            java.util.Locale.forLanguageTag((String) operands[0]).getDisplayName(java.util.Locale.ENGLISH))
                            : TextSource.unknown("invalid_language_code"); break;
                case "concat":
                    source = TextSource.of("concat", "parts", concatParts(operands)); break;
                case "format":
                    source = formatSource(operands); break;
                case "format_failed": source = TextSource.unknown("original_format_failed"); break;
                case "decimal_format":
                    source = operands.length == 2 && operands[0] instanceof String && safeNumber(operands[1])
                            ? TextSource.of("decimal", "pattern", operands[0], "formatted",
                            new DecimalFormat((String) operands[0], DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(operands[1]))
                            : TextSource.unknown("invalid_decimal_operation"); break;
                case "capitalize": case "title_case": case "upper_case": case "lower_case":
                    source = operands.length == 1 ? TextSource.of("case", "operation", operation, "value", argument(operands[0]))
                            : TextSource.unknown("invalid_case_operation"); break;
                case "strip_prefix":
                    source = stripPrefixSource(rendered, operands); break;
                case "displayed":
                    source = operands.length == 2 && operands[1] instanceof Boolean
                            ? TextSource.of("displayed", "value", argument(operands[0]), "markup", operands[1])
                            : TextSource.unknown("invalid_display_operation"); break;
                case "markup_segment": {
                    if (operands.length != 3 || !(operands[0] instanceof String)
                            || !(operands[1] instanceof Integer) || !(operands[2] instanceof Integer)) {
                        source = TextSource.unknown("invalid_markup_segment"); break;
                    }
                    String[] parts = ((String)operands[0]).split("\\*\\*|_", -1);
                    int index = (Integer)operands[1], count = (Integer)operands[2];
                    source = count == parts.length && index >= 0 && index < count
                            && parts[index].trim().equals(rendered.trim())
                            ? TextSource.of("markup_segment", "value", argument(operands[0]), "index", index, "count", count)
                            : TextSource.unknown("partial_markup_segment");
                    break;
                }
                case "replace": {
                    source = replacementSource(rendered, operands); break;
                }
                case "slice": {
                    TextSource original = operands.length == 3 ? argument(operands[0]) : TextSource.unknown("invalid_slice_operation");
                    source = "literal".equals(original.value.get("kind")) && operands[1] instanceof Integer && operands[2] instanceof Integer
                            ? materializedLiteral(original, rendered)
                            : TextSource.unknown("partial_resource_text"); break;
                }
                default: source = TextSource.unknown("unsupported_text_operation");
            }
        } catch (RuntimeException failure) {
            source = TextSource.unknown("operation_capture_failed");
        }
        return register(rendered, source);
    }

    public void onTextBound(Object owner, String rendered) {
        if (suppression.get() != 0) return;
        if (rendered == null) owners.remove(owner);
        else owners.put(owner, new Binding(rendered, argument(rendered)));
    }

    public void onTextReleased(Object owner) { owners.remove(owner); }

    /** Capture after the caller has applied its existing visible/drawn/knowledge gates. */
    public Map<String, Object> capture(Object owner, String displayed, boolean clipped) {
        TextSource source;
        if (clipped) source = TextSource.unknown("clipped_text");
        else {
            Binding binding = owners.get(owner);
            source = binding != null && binding.text.get() == displayed ? binding.source : argument(displayed);
        }
        // Canonical tokens themselves can be persisted publicly. An unsafe descendant
        // must hide its containing key, template, and otherwise-safe sibling arguments.
        String unsafe = unsafeReason(source.value, 0);
        if (unsafe != null) source = TextSource.unknown(unsafe);
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("$text_source", 1);
        token.put("node", source.value);
        return Collections.unmodifiableMap(token);
    }

    public Map<String, Object> render(Map<String, Object> token) { return EnglishTextRenderer.render(token); }

    public static boolean isToken(Map<?, ?> token) {
        return token != null && token.get("$text_source") instanceof Number
                && ((Number) token.get("$text_source")).doubleValue() == 1d && token.get("node") instanceof Map;
    }

    public <T> T withoutTracking(Supplier<T> action) {
        int previous = suppression.get();
        suppression.set(previous + 1);
        try { return action.get(); }
        finally { if (previous == 0) suppression.remove(); else suppression.set(previous); }
    }

    /** Begin/end a machine session; existing captured tokens remain independently renderable. */
    public void clear() { strings.clear(); owners.clear(); suppression.remove(); }

    private String register(String rendered, TextSource source) {
        String identity = new String(rendered);
        strings.put(identity, source);
        return identity;
    }

    private List<TextSource> arguments(Object[] values) {
        List<TextSource> result = new ArrayList<>();
        if (values != null) for (Object value : values) result.add(argument(value));
        return result;
    }

    private TextSource formatSource(Object[] operands) {
        if (operands.length != 2 || !(operands[0] instanceof String) || !(operands[1] instanceof Object[]))
            return TextSource.unknown("invalid_format_operation");
        TextSource format = argument(operands[0]);
        Set<Integer> visible = FormatArgumentUsage.indices((String) operands[0], true);
        Map<String,Object> token = new LinkedHashMap<>(); token.put("$text_source", 1); token.put("node", format.value);
        Map<String,Object> english = EnglishTextRenderer.render(token);
        if ("complete".equals(english.get("translation_status"))
                && !visible.containsAll(FormatArgumentUsage.indices((String) english.get("text"), true))) {
            if ("resource".equals(format.value.get("kind")) && validKey((String) format.value.get("key")))
                return TextSource.of("resource_reference", "key", format.value.get("key"), "reason", "argument_not_displayed");
            return TextSource.unknown("argument_not_displayed");
        }
        String template = "complete".equals(english.get("translation_status")) ? (String) english.get("text") : null;
        PrecisionCapture precision = precision((Object[]) operands[1], (String) operands[0], template, true, visibleArguments((Object[]) operands[1], visible));
        if (precision.reason != null) {
            if (precision.safeReference && "resource".equals(format.value.get("kind")))
                return reference((String) format.value.get("key"), precision.reason);
            return TextSource.unknown(precision.reason);
        }
        return precision.changed && template != null
                ? TextSource.of("format", "format", format, "arguments", precision.arguments, "formatted_text", precision.english)
                : TextSource.of("format", "format", format, "arguments", precision.arguments);
    }

    private static TextSource reference(String key, String reason) { return TextSource.of("resource_reference", "key", key, "reason", reason); }

    /** Preserve ordinary lossless args; rounded/truncated args retain only their displayed fragments. */
    private PrecisionCapture precision(Object[] values, String gui, String english, boolean formatted, List<TextSource> plain) {
        if (!formatted) return PrecisionCapture.unchanged(plain);
        if (!EnglishTextRenderer.formatWithinBudget(gui) || english != null && !EnglishTextRenderer.formatWithinBudget(english))
            return PrecisionCapture.failure("source_limit", false);
        List<FormatArgumentUsage.Specifier> guiSpecs = FormatArgumentUsage.specifiers(gui);
        List<FormatArgumentUsage.Specifier> targetSpecs = english == null ? guiSpecs : FormatArgumentUsage.specifiers(english);
        Set<Integer> reduced = new java.util.LinkedHashSet<>();
        for (Integer index : FormatArgumentUsage.indices(gui, true)) {
            if (values == null || index >= values.length) continue;
            boolean lossless = false;
            for (FormatArgumentUsage.Specifier spec : guiSpecs)
                if (spec.index == index && !spec.potentiallyLossy(values[index])) lossless = true;
            if (!lossless) reduced.add(index);
        }
        if (reduced.isEmpty()) return PrecisionCapture.unchanged(plain);
        for (TextSource source : plain) {
            String unsafe = unsafeReason(source.value, 0);
            if (unsafe != null) return PrecisionCapture.failure(unsafe, false);
        }
        for (Integer index : reduced) {
            TextSource source = plain.get(index);
            for (FormatArgumentUsage.Specifier target : targetSpecs) if (target.index == index) {
                boolean matching = false;
                for (FormatArgumentUsage.Specifier original : guiSpecs)
                    if (original.index == index && original.informationShape().equals(target.informationShape())) matching = true;
                if (!matching) return PrecisionCapture.failure("format_precision_not_displayed", true);
                char conversion = Character.toLowerCase(target.conversion);
                if (conversion == 's' && (!literalOrScalar(source) || target.conversion == 'S'))
                    return PrecisionCapture.failure("format_precision_not_displayed", true);
            }
        }
        List<TextSource> safe = new ArrayList<>(plain);
        for (Integer index : reduced) {
            List<TextSource> fragments = new ArrayList<>();
            boolean targetUses = false;
            for (FormatArgumentUsage.Specifier target : targetSpecs) if (target.index == index) {
                targetUses = true;
                fragments.add(TextSource.of("formatted_fragment", "specifier", target.normalized,
                        "text", formattedFragment(target, values, plain, reduced)));
            }
            if (!targetUses) for (FormatArgumentUsage.Specifier original : guiSpecs) if (original.index == index) {
                if (Character.toLowerCase(original.conversion) == 's' && !literalOrScalar(plain.get(index)))
                    return PrecisionCapture.failure("format_precision_not_displayed", true);
                fragments.add(TextSource.of("formatted_fragment", "specifier", original.normalized,
                        "text", formattedFragment(original, values, plain, reduced)));
            }
            Object origin = plain.get(index).value.get("origin");
            safe.set(index, TextSource.of("formatted_argument", "origin", origin == null ? "scalar" : origin, "fragments", fragments));
        }
        if (english == null) return new PrecisionCapture(safe, null, null, false, true);
        StringBuilder rendered = new StringBuilder(); int offset = 0;
        for (FormatArgumentUsage.Specifier target : targetSpecs) {
            rendered.append(english, offset, target.start);
            rendered.append(formattedFragment(target, values, plain, reduced));
            offset = target.end;
        }
        rendered.append(english, offset, english.length());
        return new PrecisionCapture(safe, rendered.toString(), null, false, true);
    }

    private String formattedFragment(FormatArgumentUsage.Specifier spec, Object[] values, List<TextSource> sources, Set<Integer> reduced) {
        if (spec.index < 0) return String.format(Locale.ENGLISH, spec.normalized);
        Object value = values[spec.index];
        char conversion = Character.toLowerCase(spec.conversion);
        if (value instanceof String && !(reduced.contains(spec.index) && (conversion == 'h' || conversion == 'b'))) {
            Map<String,Object> token = new LinkedHashMap<>(); token.put("$text_source", 1); token.put("node", sources.get(spec.index).value);
            Map<String,Object> rendered = EnglishTextRenderer.render(token);
            if (!"complete".equals(rendered.get("translation_status"))) throw new IllegalArgumentException("Incomplete formatted argument");
            value = rendered.get("text");
        }
        return String.format(Locale.ENGLISH, spec.normalized, value);
    }

    private static boolean literalOrScalar(TextSource source) {
        return "literal".equals(source.value.get("kind")) || "scalar".equals(source.value.get("kind"));
    }

    private TextSource stripPrefixSource(String rendered, Object[] operands) {
        if (operands.length != 2 || !(operands[1] instanceof String)) return TextSource.unknown("invalid_prefix_operation");
        TextSource original = argument(operands[0]);
        if ("literal".equals(original.value.get("kind"))) return materializedLiteral(original, rendered);
        String prefix = (String) operands[1];
        return structural(prefix) && fixedPrefix(original.value).startsWith(prefix)
                ? TextSource.of("strip_prefix", "value", original, "prefix", prefix)
                : TextSource.unknown("invalid_prefix_operation");
    }

    private TextSource replacementSource(String rendered, Object[] operands) {
        if (operands.length != 3 || !(operands[1] instanceof String) || !(operands[2] instanceof String))
            return TextSource.unknown("invalid_replace_operation");
        TextSource original = argument(operands[0]);
        if ("literal".equals(original.value.get("kind"))) return materializedLiteral(original, rendered);
        String oldValue = (String) operands[1], newValue = (String) operands[2];
        if (!structural(oldValue) || !structural(newValue)) return TextSource.unknown("unsupported_resource_replacement");
        // Numeric range punctuation is already visible as a whole. Collapse its
        // transformed presentation, instead of retaining pre-replacement scalars.
        if (numericExpression(original.value)) {
            Map<String,Object> token = new LinkedHashMap<>(); token.put("$text_source", 1); token.put("node", original.value);
            Map<String,Object> english = EnglishTextRenderer.render(token);
            if ("complete".equals(english.get("translation_status"))
                    && (english.get("text").equals(operands[0]) || oldValue.equals("-") && newValue.equals("~")))
                return TextSource.of("literal", "origin", "symbol", "value", ((String) english.get("text")).replace(oldValue, newValue));
        }
        if (fixedResource(original.value)) return TextSource.of("replace", "value", original, "old", oldValue, "new", newValue);
        return TextSource.unknown("unsupported_resource_replacement");
    }

    private static TextSource materializedLiteral(TextSource original, String rendered) {
        return TextSource.of("literal", "origin", original.value.get("origin"), "value", rendered);
    }

    private static boolean structural(String value) {
        for (int i = 0; i < value.length();) {
            int code = value.codePointAt(i), type = Character.getType(code);
            if (!(Character.isWhitespace(code) || type >= Character.DASH_PUNCTUATION && type <= Character.OTHER_PUNCTUATION
                    || type >= Character.MATH_SYMBOL && type <= Character.OTHER_SYMBOL
                    || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION)) return false;
            i += Character.charCount(code);
        }
        return true;
    }

    private static boolean fixedResource(Map<?,?> source) {
        return "resource".equals(source.get("kind")) && source.get("template") instanceof String
                && source.get("arguments") instanceof List && ((List<?>) source.get("arguments")).isEmpty();
    }

    private static boolean numericExpression(Map<?,?> source) {
        String kind = (String) source.get("kind");
        if (kind.equals("scalar")) return source.get("value") instanceof Number;
        if (kind.equals("decimal")) return source.get("formatted") instanceof String;
        if (kind.equals("literal")) return "symbol".equals(source.get("origin"))
                || source.get("value") instanceof String && neutral((String) source.get("value"));
        if (kind.equals("concat")) {
            for (Object child : (List<?>) source.get("parts")) if (!(child instanceof Map) || !numericExpression((Map<?,?>) child)) return false;
            return true;
        }
        return false;
    }

    private static String fixedPrefix(Map<?,?> source) {
        String kind = (String) source.get("kind");
        if (kind.equals("literal")) {
            String origin = (String) source.get("origin"), value = (String) source.get("value");
            if (origin.equals("user") || origin.equals("external") || origin.equals("symbol") && !structural(value)) return "";
            return structuralPrefix(value);
        }
        if (kind.equals("resource")) return source.get("fixed_prefix") instanceof String ? (String) source.get("fixed_prefix") : "";
        if (kind.equals("format")) {
            String value = fixedPrefix((Map<?,?>) source.get("format"));
            return value.indexOf('%') >= 0 ? value.substring(0, value.indexOf('%')) : value;
        }
        if (kind.equals("concat")) {
            StringBuilder prefix = new StringBuilder();
            for (Object child : (List<?>) source.get("parts")) {
                Map<?,?> part = (Map<?,?>) child; String fragment = fixedPrefix(part); prefix.append(fragment);
                if (!"literal".equals(part.get("kind")) || !fragment.equals(part.get("value"))) break;
            }
            return prefix.toString();
        }
        return "";
    }

    private static String structuralPrefix(String value) {
        int end = 0;
        while (end < value.length()) {
            int next = end + Character.charCount(value.codePointAt(end));
            if (!structural(value.substring(end, next))) break;
            end = next;
        }
        return value.substring(0, end);
    }

    private static final class PrecisionCapture {
        final List<TextSource> arguments;
        final String english, reason;
        final boolean safeReference, changed;
        PrecisionCapture(List<TextSource> arguments, String english, String reason, boolean safeReference, boolean changed) {
            this.arguments = arguments; this.english = english; this.reason = reason; this.safeReference = safeReference; this.changed = changed;
        }
        static PrecisionCapture unchanged(List<TextSource> arguments) { return new PrecisionCapture(arguments, null, null, false, false); }
        static PrecisionCapture failure(String reason, boolean safeReference) { return new PrecisionCapture(null, null, reason, safeReference, true); }
    }

    private List<TextSource> visibleArguments(Object[] values, Set<Integer> visible) {
        int size = 0; for (Integer index : visible) size = Math.max(size, index + 1);
        List<TextSource> result = new ArrayList<>();
        for (int i = 0; i < size; i++) result.add(!visible.contains(i) ? argument(null)
                : values != null && i < values.length ? argument(values[i]) : TextSource.unknown("missing_displayed_argument"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<TextSource> concatParts(Object[] values) {
        List<TextSource> result = new ArrayList<>();
        for (TextSource source : arguments(values)) {
            if ("concat".equals(source.value.get("kind")))
                for (Object part : (List<?>) source.value.get("parts")) result.add(TextSource.fromFrozen((Map<String, Object>) part));
            else result.add(source);
        }
        return result;
    }

    private TextSource argument(Object value) {
        if (value == null) return TextSource.of("scalar", "value", null);
        if (safeNumber(value) || value instanceof Boolean) return TextSource.of("scalar", "value", value,
                "scalar_type", value.getClass().getSimpleName(),
                "negative_zero", value instanceof Float && Float.floatToRawIntBits((Float) value) == 0x80000000
                        || value instanceof Double && Double.doubleToRawLongBits((Double) value) == 0x8000000000000000L);
        if (value instanceof Character) return TextSource.of("scalar", "scalar_type", "Character", "value", String.valueOf(value));
        if (value instanceof String) {
            TextSource found = strings.get(value);
            if (found != null) return found;
            if (neutral((String) value)) return TextSource.of("literal", "origin", "symbol", "value", value);
            return TextSource.unknown("unclassified_string");
        }
        // Do not call toString, Number conversion methods, suppliers, or model getters here.
        return TextSource.unknown("unsupported_object_argument");
    }

    private static boolean safeNumber(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return true;
        return value instanceof Float && Float.isFinite((Float) value)
                || value instanceof Double && Double.isFinite((Double) value);
    }

    private static boolean neutral(String value) {
        for (int i = 0; i < value.length();) {
            int code = value.codePointAt(i);
            if (Character.isLetter(code)) return false;
            i += Character.charCount(code);
        }
        return true;
    }

    /** Sanity check at an explicit code-owned boundary, never automatic English classification. */
    static boolean systemEnglish(String value) {
        for (int i = 0; i < value.length();) {
            int code = value.codePointAt(i);
            if (Character.isLetter(code) && Character.UnicodeScript.of(code) != Character.UnicodeScript.LATIN) return false;
            i += Character.charCount(code);
        }
        return true;
    }

    static boolean validKey(String key) { return key != null && key.matches("[a-z0-9_.$!?-]{1,512}"); }

    private static String unsafeReason(Object value, int depth) {
        if (depth > 64) return "source_limit";
        if (value instanceof Map<?, ?>) {
            Map<?, ?> node = (Map<?, ?>) value;
            if ("unavailable".equals(node.get("kind"))) return (String) node.get("reason");
            for (Object child : node.values()) {
                String reason = unsafeReason(child, depth + 1);
                if (reason != null) return reason;
            }
        } else if (value instanceof List<?>) {
            for (Object child : (List<?>) value) {
                String reason = unsafeReason(child, depth + 1);
                if (reason != null) return reason;
            }
        }
        return null;
    }

    private static final class Binding {
        final WeakReference<String> text;
        final TextSource source;
        Binding(String text, TextSource source) { this.text = new WeakReference<>(text); this.source = source; }
    }

    private static final class Templates {
        static final Map<String, String> VALUES = load();
        private static Map<String, String> load() {
            Map<String, String> values = new LinkedHashMap<>();
            for (String group : new String[]{"actors", "items", "journal", "levels", "misc", "plants", "scenes", "ui", "windows"}) {
                String path = "messages/" + group + "/" + group + ".properties";
                try (InputStream input = TextProvenance.class.getClassLoader().getResourceAsStream(path)) {
                    if (input == null) continue;
                    Properties properties = new Properties();
                    properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
                    for (String key : properties.stringPropertyNames()) values.put(key, properties.getProperty(key));
                } catch (java.io.IOException failure) { throw new IllegalStateException("English resources unavailable", failure); }
            }
            return Collections.unmodifiableMap(values);
        }
    }
}
