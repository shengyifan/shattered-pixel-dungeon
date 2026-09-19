package com.shatteredpixel.shatteredpixeldungeon.control.game.text;

import java.util.*;

/** Classification of the renderer's public output, never of an unrendered source token. */
public final class PublicTextSources {
    public static final Set<String> ORDINARY_KINDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "resource", "literal", "scalar", "concat", "format", "case", "slice", "replace",
            "formatted_argument", "formatted_fragment", "decimal", "language", "strip_prefix", "displayed")));
    public static final Set<String> ORDINARY_ORIGINS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "catalog", "literal", "symbol", "scalar")));

    private PublicTextSources() { }

    /** User/external origins need origin markers; unknown or partial trees also need the public AST. */
    public static boolean requiresSource(Object value) { return protectedTree(value, false); }

    /** Any origin or incomplete public evidence prevents omission of its displayed field. */
    public static boolean protectsField(Object value) { return protectedTree(value, true); }

    private static boolean protectedTree(Object value, boolean includeOrigins) {
        if (value instanceof List) {
            for (Object child : (List<?>) value) if (protectedTree(child, includeOrigins)) return true;
            return false;
        }
        if (!(value instanceof Map)) return false;
        Map<?, ?> source = (Map<?, ?>) value;
        Object kind = source.get("kind"), origin = source.get("origin");
        if (kind != null && !ORDINARY_KINDS.contains(kind) && !("user".equals(kind) || "external".equals(kind))) return true;
        if (origin != null && !ORDINARY_ORIGINS.contains(origin) && !("user".equals(origin) || "external".equals(origin))) return true;
        if (includeOrigins && ("user".equals(kind) || "external".equals(kind) || "user".equals(origin) || "external".equals(origin))) return true;
        if (Boolean.TRUE.equals(source.get("clipped")) || "partial".equals(source.get("visibility"))
                || "partial".equals(source.get("translation_status")) || "partial".equals(source.get("status"))
                || "partial".equals(source.get("st")) || source.containsKey("diagnostic") || source.containsKey("diagnostics")) return true;
        for (Object child : source.values()) if (protectedTree(child, includeOrigins)) return true;
        return false;
    }

    public static Set<String> origins(Object value) {
        Set<String> result = new LinkedHashSet<>();
        collectOrigins(value, result);
        return result;
    }

    private static void collectOrigins(Object value, Set<String> result) {
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            for (String field : Arrays.asList("kind", "origin")) {
                Object origin = source.get(field);
                if ("user".equals(origin) || "external".equals(origin)) result.add((String) origin);
            }
            for (Object child : source.values()) collectOrigins(child, result);
        } else if (value instanceof List) for (Object child : (List<?>) value) collectOrigins(child, result);
    }
}
