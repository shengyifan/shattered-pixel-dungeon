package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import java.util.*;

/** Pure presentation of frozen, already player-visible text. No game getters or reverse lookup. */
public final class PublicEnglishProjection {
    private static final Set<String> TEXT_FIELDS = new HashSet<>(Arrays.asList(
            "name", "class_name", "subclass_name", "label", "text", "description", "prompt",
            "cell_prompt", "item_prompt", "title", "message", "disabled_reason", "options"));
    private static final Set<String> OPAQUE_FIELDS = new HashSet<>(Arrays.asList(
            "text_sources", "text_diagnostics", "presentation", "response", "response_json",
            "raw", "reply", "schema", "raw_request", "raw_bytes", "request_json", "original_payload"));
    private PublicEnglishProjection() { }

    /** Capture while String identities and their control bindings are still available. */
    @SuppressWarnings("unchecked")
    public static <T> T freeze(T source) { return (T) freezeValue(source, null, false); }

    private static Object freezeValue(Object source, String field, boolean clipped) {
        if (source instanceof Map) {
            Map<?, ?> input = (Map<?, ?>) source;
            if (TextProvenance.isToken(input)) return source;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : input.entrySet()) {
                String key = (String) entry.getKey();
                boolean rendered = input.get("text_sources") instanceof Map
                        && ((Map<?, ?>) input.get("text_sources")).containsKey(key);
                result.put(key, OPAQUE_FIELDS.contains(key) || rendered ? entry.getValue()
                        : freezeValue(entry.getValue(), key, "text".equals(key) && Boolean.TRUE.equals(input.get("clipped"))));
            }
            return Collections.unmodifiableMap(result);
        }
        if (source instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) source) result.add(freezeValue(item, field, clipped));
            return Collections.unmodifiableList(result);
        }
        if (source instanceof String && TEXT_FIELDS.contains(field))
            return TextProvenance.INSTANCE.capture(null, (String) source, clipped);
        return source;
    }

    public static String text(String value) {
        return (String) TextProvenance.INSTANCE.render(TextProvenance.INSTANCE.capture(null, value, false)).get("text");
    }
    @SuppressWarnings("unchecked")
    public static <T> T copy(T source) { return (T) renderValue(freeze(source)); }
    /** Retained source-level helpers; scene contents are no longer used to guess translations. */
    public static <T> T copyInScene(T source, String scene) { return copy(source); }
    public static <T> T copyWithUi(T source, Map<String,Object> ui) { return copy(source); }

    @SuppressWarnings("unchecked")
    private static Object renderValue(Object source) {
        if (source instanceof Map) {
            Map<String,Object> input = (Map<String,Object>) source;
            if (TextProvenance.isToken(input)) return TextProvenance.INSTANCE.render(input).get("text");
            Map<String,Object> result = new LinkedHashMap<>();
            Map<String,Object> sources = new LinkedHashMap<>();
            Map<String,Object> diagnostics = new LinkedHashMap<>();
            if(input.get("text_sources") instanceof Map)sources.putAll((Map<String,Object>)input.get("text_sources"));
            if(input.get("text_diagnostics") instanceof Map)diagnostics.putAll((Map<String,Object>)input.get("text_diagnostics"));
            for (Map.Entry<String,Object> entry : input.entrySet()) {
                String field = entry.getKey(); Object value = entry.getValue();
                if (value instanceof Map && TextProvenance.isToken((Map<?,?>) value)) {
                    Map<String,Object> rendered = TextProvenance.INSTANCE.render((Map<String,Object>) value);
                    result.put(field, rendered.get("text"));
                    sources.put(field, rendered.get("source"));
                    diagnostics.remove(field);
                    if (!"complete".equals(rendered.get("translation_status")))
                        diagnostics.put(field, rendered.get("diagnostic"));
                } else if ("options".equals(field) && value instanceof List) {
                    List<Object> texts = new ArrayList<>(), origins = new ArrayList<>();
                    int index = 0;
                    boolean sourceTokens=false;
                    for (Object option : (List<?>) value) {
                        if (option instanceof Map && TextProvenance.isToken((Map<?,?>)option)) {
                            sourceTokens=true;
                            Map<String,Object> rendered = TextProvenance.INSTANCE.render((Map<String,Object>)option);
                            texts.add(rendered.get("text")); origins.add(rendered.get("source"));
                            if (!"complete".equals(rendered.get("translation_status")))
                                diagnostics.put("options[" + index + "]", rendered.get("diagnostic"));
                        } else { texts.add(option); origins.add(null); }
                        index++;
                    }
                    result.put(field,texts);
                    if (sourceTokens) sources.put(field,origins);
                } else result.put(field, OPAQUE_FIELDS.contains(field) ? value : renderValue(value));
            }
            if (!sources.isEmpty()) result.put("text_sources", sources);
            if (!diagnostics.isEmpty()) {
                result.put("translation_status", "partial"); result.put("text_diagnostics", diagnostics);
            } else {
                result.remove("translation_status"); result.remove("text_diagnostics");
            }
            return result;
        }
        if (source instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>)source) result.add(renderValue(item));
            return result;
        }
        return source;
    }

    public static Map<String,Object> presentation(Object rendered) {
        List<Object> diagnostics = new ArrayList<>();
        collectDiagnostics(rendered, "$", diagnostics);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", diagnostics.isEmpty() ? "complete" : "partial");
        result.put("diagnostics", diagnostics);
        return result;
    }
    private static void collectDiagnostics(Object value, String path, List<Object> result) {
        if (value instanceof Map) {
            Map<?,?> map = (Map<?,?>)value;
            if (map.get("text_diagnostics") instanceof Map)
                for (Map.Entry<?,?> entry : ((Map<?,?>)map.get("text_diagnostics")).entrySet()) {
                    Map<String,Object> diagnostic = new LinkedHashMap<>();
                    diagnostic.put("field", path + "." + entry.getKey()); diagnostic.put("code", entry.getValue());
                    result.add(diagnostic);
                }
            for (Map.Entry<?,?> entry : map.entrySet())
                if (!OPAQUE_FIELDS.contains(entry.getKey())) collectDiagnostics(entry.getValue(),path + "." + entry.getKey(),result);
        } else if (value instanceof List) {
            int i = 0;
            for (Object item : (List<?>)value) collectDiagnostics(item,path + "[" + i++ + "]",result);
        }
    }

    /** Presentation cannot invalidate an otherwise unchanged action context. */
    public static Object semantics(Object value) {
        if (value instanceof Map) {
            Map<String,Object> result = new LinkedHashMap<>();
            for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) {
                String key = (String) entry.getKey();
                if (!OPAQUE_FIELDS.contains(key) && !"template".equals(key) && !"english".equals(key) && !"formatted_text".equals(key) && !"fixed_prefix".equals(key)
                        && !"translation_status".equals(key) && !"display".equals(key))
                    result.put(key,semantics(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>)value) result.add(semantics(item));
            return result;
        }
        return value;
    }
}
