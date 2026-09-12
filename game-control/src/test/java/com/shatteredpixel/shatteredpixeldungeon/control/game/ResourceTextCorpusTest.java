package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.utils.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Exhaustive bundled-key checks, with materialized examples rather than game object getters. */
class ResourceTextCorpusTest {
    private static final String[] DOMAINS = {"actors", "items", "journal", "levels", "misc", "plants", "scenes", "ui", "windows"};
    // Space flags are excluded when locating placeholders: "25% more" is ordinary
    // resource prose. String.format still validates the entire actual template below.
    private static final Pattern FORMAT = Pattern.compile("%(?:([0-9]+)\\$)?([-#+0,(<]*)([0-9]*)(?:\\.([0-9]+))?([tT])?([a-zA-Z%])");

    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void allOfficialKeysAndRegisteredLanguagesHaveTraceableEnglishFormatting() throws Exception {
        Map<String, String> english = new TreeMap<>(), domainByKey = new LinkedHashMap<>();
        Map<String, Integer> domainSizes = new LinkedHashMap<>();
        for (String domain : DOMAINS) {
            String path = "messages/" + domain + "/" + domain + ".properties";
            Properties properties = new Properties();
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input, path);
                properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
            domainSizes.put(domain, properties.size());
            for (String key : properties.stringPropertyNames()) {
                assertNull(english.put(key, properties.getProperty(key)), "Duplicate official resource key " + key);
                domainByKey.put(key, domain);
            }
        }
        assertTrue(english.size() > 1000, "The complete official corpus must be present");
        RuntimeObserver previous = Game.observer;
        Languages selected = Messages.selectedLanguage();
        byte[] random = Random.exportState();
        List<Map<String, Object>> defects = new ArrayList<>(), warnings = new ArrayList<>(), visibilityPartials = new ArrayList<>();
        Map<String, Object> languageReports = new LinkedHashMap<>();
        long comparisons = 0;
        TextProvenance texts = new TextProvenance(english::get);
        try {
            Game.observer = RuntimeObserver.NONE;
            for (Languages language : Languages.values()) {
                Map<String, Object> domains = new LinkedHashMap<>();
                for (String domain : DOMAINS) domains.put(domain, counts());
                Locale locale = Messages.withLanguage(language, Messages::locale);
                for (Map.Entry<String, String> entry : english.entrySet()) {
                    String key = entry.getKey(), template = entry.getValue(), domain = domainByKey.get(key);
                    @SuppressWarnings("unchecked") Map<String, Integer> counts = (Map<String, Integer>) domains.get(domain);
                    increment(counts, "keys_visited"); comparisons++;
                    String localized = Messages.withLanguage(language, () -> Messages.get(key));
                    if (Messages.NO_TEXT_FOUND.equals(localized)) {
                        defect(defects, language, domain, key, "key_missing_after_fallback", null, null); increment(counts, "failed"); continue;
                    }
                    Spec reference;
                    Object[] args;
                    String expected;
                    try {
                        reference = Spec.parse(template);
                        args = reference.arguments(texts);
                        expected = args.length == 0 ? template : String.format(Locale.ENGLISH, template, args);
                    } catch (RuntimeException failure) {
                        defect(defects, language, domain, key, "english_format_spec", failure, null); increment(counts, "failed"); continue;
                    }
                    String gui;
                    Spec translated;
                    try {
                        // Native Messages.get does not invoke Formatter at all with
                        // zero args. Percent suffixes such as Hungarian "50%-kal"
                        // are ordinary literal prose, not invalid conversions.
                        translated = args.length == 0 ? new Spec() : Spec.parse(localized);
                        if (!reference.types.keySet().equals(translated.types.keySet())) {
                            Map<String,Object> item = issue(language, domain, key, "argument_usage_difference");
                            item.put("english_argument_indices", new ArrayList<>(reference.types.keySet()));
                            item.put("gui_argument_indices", new ArrayList<>(translated.types.keySet()));
                            warnings.add(item); increment(counts, "argument_usage_warnings");
                        }
                        if (!reference.types.equals(translated.types)) {
                            Map<String,Object> item = issue(language, domain, key, "argument_conversion_difference");
                            item.put("english_conversions", reference.description()); item.put("gui_conversions", translated.description());
                            warnings.add(item); increment(counts, "argument_conversion_warnings");
                        }
                        gui = args.length == 0 ? localized : String.format(locale, localized, args);
                    } catch (RuntimeException failure) {
                        defect(defects, language, domain, key, "gui_format_for_sampled_arguments", failure, args);
                        increment(counts, "failed"); continue;
                    }
                    String recorded = texts.onTextResource(gui, key, language.code(), args, localized);
                    Map<String,Object> token = texts.capture(null, recorded, false);
                    Map<String,Object> rendered = texts.render(JsonCodec.decode(JsonCodec.encode(token)));
                    if (!translated.types.keySet().containsAll(reference.types.keySet())) {
                        Object[] changed = args.clone();
                        for (Integer index : reference.types.keySet()) if (!translated.types.containsKey(index))
                            changed[index - 1] = texts.onTextOperation("user", "UNDISPLAYED_ARGUMENT_SENTINEL", "UNDISPLAYED_ARGUMENT_SENTINEL");
                        String alternateGui = String.format(locale, localized, changed);
                        String alternateRecorded = texts.onTextResource(alternateGui, key, language.code(), changed, localized);
                        Map<String,Object> alternateToken = texts.capture(null, alternateRecorded, false);
                        if (!gui.equals(alternateGui) || !token.equals(alternateToken)
                                || !"partial".equals(rendered.get("translation_status"))
                                || !"argument_not_displayed".equals(rendered.get("diagnostic"))
                                || !Map.of("kind", "resource", "key", key, "args", List.of(), "visibility", "partial").equals(rendered.get("source"))
                                || !rendered.get("text").equals("[key: " + key + "]") || JsonCodec.encode(token).contains("template")
                                || JsonCodec.encode(alternateToken).contains("UNDISPLAYED_ARGUMENT_SENTINEL")) {
                            defect(defects, language, domain, key, "undisplayed_argument_visibility_failure", null, args);
                            increment(counts, "failed"); continue;
                        }
                        Map<String,Object> partial = issue(language, domain, key, "argument_not_displayed");
                        partial.put("english_argument_indices", new ArrayList<>(reference.types.keySet()));
                        partial.put("gui_argument_indices", new ArrayList<>(translated.types.keySet()));
                        partial.put("dual_world_token_and_wire_identical", true);
                        visibilityPartials.add(partial); increment(counts, "visibility_partials"); increment(counts, "passed"); continue;
                    }
                    if (!expected.equals(rendered.get("text")) || !"complete".equals(rendered.get("translation_status"))) {
                        Map<String,Object> item = issue(language, domain, key, "english_projection_mismatch");
                        item.put("expected", expected); item.put("rendered", rendered); defects.add(item); increment(counts, "failed"); continue;
                    }
                    Map<?,?> source = (Map<?,?>) rendered.get("source");
                    if (source == null || !key.equals(source.get("key")) || source.containsKey("template")) {
                        defect(defects, language, domain, key, "public_source_mismatch", null, args); increment(counts, "failed"); continue;
                    }
                    increment(counts, "passed"); increment(counts, "english_complete");
                }
                languageReports.put(language.code(), domains);
                texts.clear();
            }
        } finally {
            Game.observer = previous;
        }
        assertEquals(selected, Messages.selectedLanguage());
        assertArrayEquals(random, Random.exportState());
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("format", "resource_corpus_v1"); report.put("official_keys", english.size());
        report.put("registered_languages", Languages.values().length); report.put("key_language_comparisons", comparisons);
        report.put("domain_sizes", domainSizes); report.put("languages", languageReports);
        report.put("defect_count", defects.size()); report.put("defects", defects); report.put("warnings", warnings);
        report.put("visibility_partial_count", visibilityPartials.size()); report.put("visibility_partials", visibilityPartials);
        report.put("selected_gui_language_unchanged", true); report.put("random_unchanged", true);
        report.put("limits", Arrays.asList("Printf arguments are materialized representatives of English conversion domains, not live game objects.",
                "GUI format failures describe these exact sampled argument types; they do not alone prove that a current gameplay call passes those types.",
                "Argument usage and conversion differences are reported even when both representative renderings succeed.",
                "English parameters omitted by the actual GUI produce explicit partial output, with dual-world hidden-argument checks; these are not complete English translations.",
                "No GUI, real profile, gameplay callback, or resource edit is used."));
        Path output = Path.of("build/text-provenance/resource-corpus.json");
        Files.createDirectories(output.getParent()); Files.writeString(output, JsonCodec.encode(report) + "\n");
        assertEquals((long) english.size() * Languages.values().length, comparisons);
        assertTrue(defects.isEmpty(), "Resource corpus found " + defects.size() + " precise failures; see " + output.toAbsolutePath());
    }

    private static Map<String,Integer> counts() {
        Map<String,Integer> values = new LinkedHashMap<>();
        for (String key : List.of("keys_visited", "passed", "failed", "english_complete", "visibility_partials", "argument_usage_warnings", "argument_conversion_warnings")) values.put(key, 0);
        return values;
    }
    private static void increment(Map<String,Integer> counts, String key) { counts.put(key, counts.get(key) + 1); }
    private static Map<String,Object> issue(Languages language, String domain, String key, String phase) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("language", language.code()); value.put("domain", domain); value.put("key", key); value.put("phase", phase); return value;
    }
    private static void defect(List<Map<String,Object>> defects, Languages language, String domain, String key, String phase, RuntimeException error, Object[] args) {
        Map<String,Object> item = issue(language, domain, key, phase);
        if (error != null) { item.put("error_type", error.getClass().getSimpleName()); item.put("error", error.getMessage()); }
        if (args != null) { List<String> types = new ArrayList<>(); for (Object arg : args) types.add(arg == null ? "null" : arg.getClass().getSimpleName()); item.put("sample_argument_types", types); }
        defects.add(item);
    }

    private enum Kind { TEXT, INTEGER, FLOAT, CHARACTER, DATE, BOOLEAN }
    private static final class Spec {
        final Map<Integer, EnumSet<Kind>> types = new TreeMap<>();
        static Spec parse(String template) {
            Spec spec = new Spec(); Matcher matcher = FORMAT.matcher(template); int implicit = 1, previous = 0;
            while (matcher.find()) {
                char conversion = Character.toLowerCase(matcher.group(6).charAt(0));
                if (matcher.group(5) == null && (conversion == '%' || conversion == 'n')) continue;
                int index;
                if (matcher.group(2).contains("<")) index = previous;
                else if (matcher.group(1) != null) index = Integer.parseInt(matcher.group(1));
                else index = implicit++;
                if (index < 1 || index > 128) throw new IllegalArgumentException("Invalid printf argument index");
                previous = index;
                Kind kind;
                if (matcher.group(5) != null) kind = Kind.DATE;
                else if (conversion == 's' || conversion == 'h') kind = Kind.TEXT;
                else if (conversion == 'd' || conversion == 'o' || conversion == 'x') kind = Kind.INTEGER;
                else if (conversion == 'a' || conversion == 'e' || conversion == 'f' || conversion == 'g') kind = Kind.FLOAT;
                else if (conversion == 'c') kind = Kind.CHARACTER;
                else if (conversion == 'b') kind = Kind.BOOLEAN;
                else throw new IllegalArgumentException("Unsupported printf conversion " + conversion);
                spec.types.computeIfAbsent(index, ignored -> EnumSet.noneOf(Kind.class)).add(kind);
            }
            return spec;
        }
        Object[] arguments(TextProvenance texts) {
            int size = types.isEmpty() ? 0 : types.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            Object[] args = new Object[size];
            for (int index = 1; index <= size; index++) {
                Set<Kind> kinds = types.getOrDefault(index, EnumSet.of(Kind.TEXT));
                if (kinds.contains(Kind.FLOAT) && (kinds.contains(Kind.INTEGER) || kinds.contains(Kind.CHARACTER) || kinds.contains(Kind.DATE))
                        || kinds.contains(Kind.DATE) && kinds.contains(Kind.CHARACTER)) throw new IllegalArgumentException("Incompatible shared printf argument conversions");
                if (kinds.contains(Kind.DATE)) args[index - 1] = 1234560000000L;
                else if (kinds.contains(Kind.FLOAT)) args[index - 1] = 1.25d;
                else if (kinds.contains(Kind.INTEGER)) args[index - 1] = kinds.contains(Kind.CHARACTER) ? 65 : 17;
                else if (kinds.contains(Kind.CHARACTER)) args[index - 1] = 'A';
                else if (kinds.contains(Kind.BOOLEAN) && !kinds.contains(Kind.TEXT)) args[index - 1] = true;
                else { String sample = "sample-" + index; args[index - 1] = texts.onTextOperation("literal", sample, sample); }
            }
            return args;
        }
        Map<String,Object> description() {
            Map<String,Object> result = new LinkedHashMap<>();
            for (Map.Entry<Integer, EnumSet<Kind>> entry : types.entrySet()) {
                List<String> kinds = new ArrayList<>(); for (Kind kind : entry.getValue()) kinds.add(kind.name());
                result.put(entry.getKey().toString(), kinds);
            }
            return result;
        }
    }
}
