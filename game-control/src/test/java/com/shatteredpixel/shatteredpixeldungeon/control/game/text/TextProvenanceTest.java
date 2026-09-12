package com.shatteredpixel.shatteredpixeldungeon.control.game.text;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.watabou.noosa.RuntimeObserver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TextProvenanceTest {
    private static String resource(TextProvenance texts, String rendered, String key, Object... args) {
        return texts.onTextResource(rendered, key, "zh", args);
    }
    private static Map<String, Object> rendered(TextProvenance texts, String text) { return texts.render(texts.capture(null, text, false)); }

    @Test void sameDisplayedValueKeepsDistinctResourceKeysByIdentity() {
        TextProvenance texts = new TextProvenance(Map.of("window.blocking", "Blocking", "stone.defense", "Defense")::get);
        String first = resource(texts, "防御", "window.blocking"), second = resource(texts, "防御", "stone.defense");
        assertEquals(first, second); assertNotSame(first, second);
        assertEquals("Blocking", rendered(texts, first).get("text"));
        assertEquals("Defense", rendered(texts, second).get("text"));
        assertEquals("partial", rendered(texts, new String(first)).get("translation_status"));
    }

    @Test void canonicalSourcesDoNotDependOnGuiLanguageOrRenderedValue() {
        TextProvenance texts = new TextProvenance(key -> "Blocking");
        Map<String, Object> reference = texts.capture(null, resource(texts, "防御", "window.blocking"), false);
        for (String language : List.of("fr", "ja", "ru", "en")) {
            String value = texts.onTextResource("Different GUI text " + language, "window.blocking", language, new Object[0]);
            assertEquals(reference, texts.capture(null, value, false));
        }
    }

    @Test void frozenTemplatesAndArgumentsSurviveResourceChangesAndJsonHistory() {
        Map<String, String> resources = new HashMap<>(); resources.put("test.remaining", "%d items remain.");
        TextProvenance texts = new TextProvenance(resources::get);
        Object[] arguments = {3};
        String value = texts.onTextResource("剩余三个", "test.remaining", "zh", arguments);
        Map<String, Object> token = texts.capture(null, value, false);
        arguments[0] = 99; resources.put("test.remaining", "Changed resource"); texts.clear();
        Map<String, Object> history = JsonCodec.decode(JsonCodec.encode(token));
        assertEquals("3 items remain.", texts.render(history).get("text"));
        assertFalse(JsonCodec.encode(texts.render(history).get("source")).contains("template"));
        assertTrue(JsonCodec.encode(texts.render(history).get("source")).contains("\"args\""));
        assertThrows(UnsupportedOperationException.class, () -> token.put("other", true));
    }

    @Test void unsafeArgumentsRedactTheWholeCanonicalResourceAndSiblingsWithoutToString() {
        TextProvenance texts = new TextProvenance(key -> "Private template %s %s");
        AtomicInteger calls = new AtomicInteger();
        Object unsafe = new Object() { @Override public String toString() { calls.incrementAndGet(); return "private-object"; } };
        String sibling = texts.onTextOperation("user", "private-sibling", "private-sibling");
        String value = resource(texts, "private-original", "secret.actual_type", unsafe, sibling);
        Map<String, Object> token = texts.capture(null, value, false);
        String serialized = JsonCodec.encode(token);
        for (String hidden : List.of("private", "secret", "template", "sibling")) assertFalse(serialized.contains(hidden), serialized);
        assertEquals(0, calls.get());
        assertNull(texts.render(token).get("source"));
        assertEquals("unsupported_object_argument", texts.render(token).get("diagnostic"));
    }

    @Test void unsafeDescendantRedactsOuterCompositionEvenWithSafeResourceSiblings() {
        TextProvenance texts = new TextProvenance(key -> "Safe but not independently published");
        String known = resource(texts, "known", "sensitive.name");
        String joined = texts.onTextOperation("concat", "never disclose", known, "unknown prose");
        assertFalse(JsonCodec.encode(texts.capture(null, joined, false)).contains("sensitive"));
        assertNull(rendered(texts, joined).get("source"));
    }

    @Test void explicitUserAndExternalContentRetainTheirOriginalTextAndOrigin() {
        TextProvenance texts = new TextProvenance(key -> null);
        for (String origin : List.of("user", "external")) {
            String original = "用户文字 " + origin;
            String value = texts.onTextOperation(origin, original, original);
            Map<String, Object> result = rendered(texts, value);
            assertEquals(original, result.get("text")); assertEquals("complete", result.get("translation_status"));
            assertEquals(origin, ((Map<?, ?>) result.get("source")).get("origin"));
        }
        assertEquals("Text unavailable", rendered(texts, "Unclassified English text").get("text"));
        for (String origin : List.of("literal", "system_catalog")) {
            String rejected = texts.onTextOperation(origin, "未分类系统文案", "未分类系统文案");
            assertEquals("system_literal_not_english", rendered(texts, rejected).get("diagnostic"));
            assertFalse(JsonCodec.encode(texts.capture(null, rejected, false)).contains("未分类"));
        }
    }

    @Test void clippingNeverPublishesAKeyTemplateArgumentOrOriginalFragment() {
        TextProvenance texts = new TextProvenance(key -> "The entire hidden message.");
        String value = resource(texts, "秘密", "private.message");
        Map<String, Object> token = texts.capture(null, value, true);
        String serialized = JsonCodec.encode(token);
        assertFalse(serialized.contains("private")); assertFalse(serialized.contains("hidden")); assertFalse(serialized.contains("秘密"));
        assertEquals("Partially displayed text", texts.render(token).get("text")); assertNull(texts.render(token).get("source"));
    }

    @Test void missingEnglishTemplateKeepsOnlySafeKeyFallback() {
        TextProvenance texts = new TextProvenance(key -> null);
        String value = resource(texts, "Do not expose source language", "test.missing", 4);
        Map<String, Object> result = rendered(texts, value);
        assertEquals("[key: test.missing]", result.get("text"));
        assertEquals("english_template_missing", result.get("diagnostic"));
        assertNotNull(result.get("source"));
        assertFalse(JsonCodec.encode(texts.capture(null, value, false)).contains("Do not expose"));
    }

    @Test void concatFlattensLongDescriptionChainsAndPreservesNumbers() {
        TextProvenance texts = new TextProvenance(key -> "word");
        String word = resource(texts, "词", "test.word"), current = "";
        for (int i = 0; i < 250; i++) current = texts.onTextOperation("concat", current + "词", current, word);
        Map<String, Object> result = texts.render(JsonCodec.decode(JsonCodec.encode(texts.capture(null, current, false))));
        assertEquals("word".repeat(250), result.get("text")); assertEquals("complete", result.get("translation_status"));
        String numbers = texts.onTextOperation("concat", "20/20 true", 20, "/", 20, " ", true);
        assertEquals("20/20 true", rendered(texts, numbers).get("text"));
    }

    @Test void compositionCaseNumericAndPrintfRenderingArePure() {
        TextProvenance texts = new TextProvenance(Map.of("test.item", "scroll of upgrade", "test.count", "%s: %d")::get);
        String item = resource(texts, "升级卷轴", "test.item");
        String title = texts.onTextOperation("title_case", "升级卷轴", item);
        String count = resource(texts, "升级卷轴：3", "test.count", title, 3);
        assertEquals("Scroll of Upgrade: 3", rendered(texts, count).get("text"));
        String decimal = texts.onTextOperation("decimal_format", "1,25x", "#.##x", 1.25d);
        assertEquals("1.25x", texts.render(JsonCodec.decode(JsonCodec.encode(texts.capture(null, decimal, false)))).get("text"));
        String template = texts.onTextOperation("literal", "%s %+d", "%s %+d");
        String formatted = texts.onTextOperation("format", "GUI", template, new Object[]{item, 1});
        assertEquals("scroll of upgrade +1", rendered(texts, formatted).get("text"));
    }

    @Test void primitiveTypesAndNegativeZeroSurviveJsonReplay() {
        TextProvenance texts = new TextProvenance(key -> "%s / %.1f");
        String value = resource(texts, "source", "test.numbers", 1e-4f, -0d);
        Map<String, Object> token = texts.capture(null, value, false);
        assertEquals(texts.render(token), texts.render(JsonCodec.decode(JsonCodec.encode(token))));
        assertEquals("1.0E-4 / -0.0", texts.render(token).get("text"));
    }

    @Test void originalMarkupAndLogPrefixesAreExplicitOperations() {
        TextProvenance texts = new TextProvenance(key -> "_Strong_ **armor**");
        String value = resource(texts, "_强_ **护甲**", "test.armor");
        String shown = texts.onTextOperation("displayed", "强 护甲", value, true);
        String prefixed = texts.onTextOperation("concat", "++ 强 护甲", "++ ", shown);
        String body = texts.onTextOperation("strip_prefix", "强 护甲", prefixed, "++ ");
        assertEquals("Strong armor", rendered(texts, body).get("text"));
    }

    @Test void resourceSlicesCannotBorrowOffsetsButUserSlicesRemainLiteral() {
        TextProvenance texts = new TextProvenance(key -> "English description");
        String value = resource(texts, "原文说明", "test.description");
        String cut = texts.onTextOperation("slice", "原文", value, 0, 2);
        assertEquals("partial", rendered(texts, cut).get("translation_status"));
        assertFalse(JsonCodec.encode(texts.capture(null, cut, false)).contains("test.description"));
        String user = texts.onTextOperation("user", "用户文字", "用户文字");
        assertEquals("用户", rendered(texts, texts.onTextOperation("slice", "用户", user, 0, 2)).get("text"));
    }

    @Test void bindingsCrossThreadsAndReleasedOwnersCannotRetainStaleValues() throws Exception {
        TextProvenance texts = new TextProvenance(key -> "Bound resource");
        Object owner = new Object();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> value = executor.submit(() -> {
                String result = resource(texts, "线程文案", "test.bound"); texts.onTextBound(owner, result); return result;
            });
            String first = value.get();
            Map<String, Object> token = texts.capture(owner, first, false);
            assertEquals("Bound resource", texts.render(token).get("text"));
            texts.onTextReleased(owner);
            assertEquals("partial", texts.render(texts.capture(owner, "未登记新值", false)).get("translation_status"));
            texts.clear();
            assertEquals("Bound resource", texts.render(token).get("text"));
            assertEquals("partial", rendered(texts, first).get("translation_status"));
        } finally { executor.shutdownNow(); }
    }

    @Test void suppressionAndDisabledObserverPreserveStringIdentity() {
        TextProvenance texts = new TextProvenance(key -> "Resource");
        String value = new String("unchanged");
        assertSame(value, texts.withoutTracking(() -> resource(texts, value, "test.resource")));
        assertSame(value, RuntimeObserver.NONE.onTextResource(value, "test.resource", "zh", new Object[0]));
        assertSame(value, RuntimeObserver.NONE.onTextOperation("literal", value, value));
        assertEquals("partial", rendered(texts, value).get("translation_status"));
    }

    @Test void registryUsesIdentityAndFrozenValuesNeverRetainTheTrackedStringObject() {
        WeakIdentityRegistry<String> registry = new WeakIdentityRegistry<>();
        String first = new String("equal"), second = new String("equal");
        registry.put(first, "first"); registry.put(second, "second");
        assertEquals("first", registry.get(first)); assertEquals("second", registry.get(second));
        registry.remove(first); assertNull(registry.get(first)); assertEquals("second", registry.get(second));
        TextProvenance texts = new TextProvenance(key -> null);
        String tracked = texts.onTextOperation("user", first, first);
        Object owner = new Object(); texts.onTextBound(owner, tracked);
        Map<?,?> node = (Map<?,?>) texts.capture(owner, tracked, false).get("node");
        assertEquals(tracked, node.get("value")); assertNotSame(tracked, node.get("value"));
        assertNotSame(first, node.get("value"));
    }

    @Test void clearedWeakKeysRemainDistinctAndQueueRemovalKeepsOtherEntries() throws Exception {
        WeakIdentityRegistry<String> registry = new WeakIdentityRegistry<>();
        Object first = new Object(), second = new Object(), live = new Object();
        registry.put(first, "first"); registry.put(second, "second"); registry.put(live, "live");
        java.lang.reflect.Field field = WeakIdentityRegistry.class.getDeclaredField("values"); field.setAccessible(true);
        Map<?,?> entries = (Map<?,?>) field.get(registry);
        java.lang.ref.Reference<?> a = (java.lang.ref.Reference<?>) entries.keySet().stream()
                .filter(key -> ((java.lang.ref.Reference<?>) key).get() == first).findFirst().orElseThrow();
        java.lang.ref.Reference<?> b = (java.lang.ref.Reference<?>) entries.keySet().stream()
                .filter(key -> ((java.lang.ref.Reference<?>) key).get() == second).findFirst().orElseThrow();
        // Simulate cleared referents deterministically instead of relying on GC timing.
        a.clear(); b.clear(); assertEquals(a, a); assertNotEquals(a, b); assertNotEquals(b, a);
        assertTrue(a.enqueue()); assertTrue(b.enqueue());
        assertEquals("live", registry.get(live)); assertEquals(1, entries.size());
        assertNull(registry.get(first)); assertNull(registry.get(second));
    }

    @Test void languageMetadataIsFrozenInCanonicalHistory() {
        TextProvenance texts = new TextProvenance(key -> null);
        String value = texts.onTextOperation("language_name", "自称名称", "ru");
        Map<String,Object> token = texts.capture(null, value, false);
        assertFalse(JsonCodec.encode(token).contains("自称名称"));
        assertEquals("Russian", texts.render(JsonCodec.decode(JsonCodec.encode(token))).get("text"));
        assertEquals(Map.of("kind", "language", "code", "ru"), texts.render(token).get("source"));
    }

    @Test void explicitZeroArgumentFormatDiffersFromUnformattedResourceLookup() {
        TextProvenance texts = new TextProvenance(key -> "100%%%n");
        String resource = resource(texts, "100%%%n", "test.percent");
        assertEquals("100%%%n", rendered(texts, resource).get("text"));
        String formatted = texts.onTextOperation("format", "100%\n", resource, new Object[0]);
        assertEquals("100%" + System.lineSeparator(), rendered(texts, formatted).get("text"));
        String invalid = texts.onTextOperation("literal", "%d", "%d");
        String invalidFormatted = texts.onTextOperation("format", "%d", invalid, new Object[0]);
        assertEquals("missing_displayed_argument", rendered(texts, invalidFormatted).get("diagnostic"));
    }

    @Test void trustedCodeCatalogKeepsExistingOriginsAndClassifiesOnlyItsOwnUntrackedInput() {
        TextProvenance texts = new TextProvenance(key -> "Blocking");
        String resource = resource(texts, "防御", "test.blocking");
        assertEquals("Blocking", rendered(texts, texts.onTextOperation("system_catalog", resource, resource)).get("text"));
        String catalog = texts.onTextOperation("system_catalog", "Fixed release note", "Fixed release note");
        assertEquals("catalog", ((Map<?,?>)rendered(texts, catalog).get("source")).get("origin"));
        String unknown = texts.onTextOperation("concat", "Do not reclassify", "unclassified");
        assertEquals("partial", rendered(texts, texts.onTextOperation("system_catalog", unknown, unknown)).get("translation_status"));
    }

    @Test void argumentsOmittedByGuiAreRedactedBeforeCanonicalPersistence() {
        TextProvenance texts = new TextProvenance(key -> "Damage %1$d-%2$d");
        String first = texts.onTextResource("Visible item", "item.hidden_damage", "zh-hant", new Object[]{1, 999}, "Visible item");
        String second = texts.onTextResource("Visible item", "item.hidden_damage", "zh-hant", new Object[]{17, 2000}, "Visible item");
        Map<String,Object> a = texts.capture(null, first, false), b = texts.capture(null, second, false);
        assertEquals(a, b); assertEquals(texts.render(a), texts.render(b));
        String json = JsonCodec.encode(a);
        for (String secret : List.of("Damage", "999", "2000", "template", "arguments")) assertFalse(json.contains(secret), json);
        assertEquals("argument_not_displayed", texts.render(a).get("diagnostic"));
        assertEquals("[key: item.hidden_damage]", texts.render(a).get("text"));
        assertEquals(Map.of("kind", "resource", "key", "item.hidden_damage", "args", List.of(), "visibility", "partial"), texts.render(a).get("source"));
    }

    @Test void indexedAndRelativeArgumentsOnlyFreezeActuallyConsumedSlots() {
        TextProvenance texts = new TextProvenance(key -> "%2$s / %<s");
        Object unused = new Object() { @Override public String toString() { throw new AssertionError("Unused argument must not be read"); } };
        String visible = texts.onTextOperation("user", "visible", "visible");
        String first = texts.onTextResource("visible / visible", "test.reuse", "fr", new Object[]{unused, visible}, "%2$s / %<s");
        String second = texts.onTextResource("visible / visible", "test.reuse", "fr", new Object[]{"different hidden value", visible}, "%2$s / %<s");
        assertEquals(texts.capture(null, first, false), texts.capture(null, second, false));
        assertEquals("visible / visible", rendered(texts, first).get("text"));
        assertEquals("complete", rendered(texts, first).get("translation_status"));
    }

    @Test void explicitFormatAlsoRejectsEnglishOnlyParameters() {
        TextProvenance texts = new TextProvenance(key -> "%s %d");
        String guiFormat = texts.onTextResource("%s", "test.format", "pl", new Object[0], "%s");
        String visible = texts.onTextOperation("user", "shown", "shown");
        String first = texts.onTextOperation("format", "shown", guiFormat, new Object[]{visible, 999});
        String second = texts.onTextOperation("format", "shown", guiFormat, new Object[]{visible, 22});
        assertEquals(texts.capture(null, first, false), texts.capture(null, second, false));
        assertEquals("argument_not_displayed", rendered(texts, first).get("diagnostic"));
        assertEquals("[key: test.format]", rendered(texts, first).get("text"));
        assertFalse(JsonCodec.encode(texts.capture(null, first, false)).contains("999"));
    }

    @Test void unusedParametersDoNotPreventOriginalPercentFormatting() {
        TextProvenance texts = new TextProvenance(key -> "100%%");
        Object unused = new Object() { @Override public String toString() { throw new AssertionError("No conversion expected"); } };
        String value = texts.onTextResource("100%", "test.percent", "en", new Object[]{unused}, "100%%");
        assertEquals("100%", rendered(texts, value).get("text"));
        assertEquals("complete", rendered(texts, value).get("translation_status"));
    }

    @Test void officialPunctuationResourceKeysRemainTraceable() {
        TextProvenance texts = new TextProvenance(Map.of("actors.mobs.goo.!!!", "!!!", "actors.mobs.sheep.baa!", "Baa!", "actors.mobs.sheep.baa?", "Baa?")::get);
        for (String key : List.of("actors.mobs.goo.!!!", "actors.mobs.sheep.baa!", "actors.mobs.sheep.baa?")) {
            String value = texts.onTextResource("source", key, "en", new Object[0], "source");
            Map<String,Object> result = rendered(texts, value);
            assertEquals("complete", result.get("translation_status"));
            assertEquals(key, ((Map<?,?>) result.get("source")).get("key"));
        }
    }

    @Test void safeKeyOnlyReferencesComposeButDoNotExposeHiddenParameters() {
        TextProvenance texts = new TextProvenance(key -> "Damage %d");
        String reference = texts.onTextResource("Visible", "item.known", "zh", new Object[]{999}, "Visible");
        String prefix = texts.onTextOperation("literal", "Preview: ", "Preview: ");
        String combined = texts.onTextOperation("concat", "Preview: Visible", prefix, reference);
        Map<String,Object> result = rendered(texts, combined);
        assertEquals("Preview: [key: item.known]", result.get("text"));
        assertEquals("argument_not_displayed", result.get("diagnostic"));
        assertFalse(JsonCodec.encode(texts.capture(null, combined, false)).contains("999"));
        String unsafe = texts.onTextOperation("concat", "ignored", reference, new Object());
        assertNull(rendered(texts, unsafe).get("source"));
        assertFalse(JsonCodec.encode(texts.capture(null, unsafe, false)).contains("item.known"));
    }

    @Test void decimalFormattingFreezesOnlyTheDisplayedPrecisionIncludingNestedArguments() {
        TextProvenance texts = new TextProvenance(key -> "%s turns");
        String first = texts.onTextOperation("decimal_format", "1,2", "#.0", 1.234567d);
        String second = texts.onTextOperation("decimal_format", "1,2", "#.0", 1.249999d);
        Map<String,Object> a = texts.capture(null, first, false), b = texts.capture(null, second, false);
        assertEquals(a, b); assertEquals("1.2", texts.render(a).get("text"));
        assertEquals(Map.of("kind", "decimal", "pattern", "#.0", "formatted", "1.2"), a.get("node"));
        assertFalse(JsonCodec.encode(texts.render(a)).contains("1.234567"));
        String outerA = texts.onTextResource("1,2 tours", "test.turns", "fr", new Object[]{first}, "%s tours");
        String outerB = texts.onTextResource("1,2 tours", "test.turns", "fr", new Object[]{second}, "%s tours");
        assertEquals(texts.capture(null, outerA, false), texts.capture(null, outerB, false));
        assertEquals("1.2 turns", rendered(texts, outerA).get("text"));
    }

    @Test void roundedPrintfValuesKeepVisibleFragmentsAndPreserveOtherLosslessScalars() {
        TextProvenance texts = new TextProvenance(key -> "Value %.1f; count %d");
        String gui = "Valeur %.1f; nombre %d";
        String first = texts.onTextResource("Valeur 1,2; nombre 7", "test.value", "fr", new Object[]{1.234567d, 7}, gui);
        String second = texts.onTextResource("Valeur 1,2; nombre 7", "test.value", "fr", new Object[]{1.249999d, 7}, gui);
        Map<String,Object> a = texts.capture(null, first, false), b = texts.capture(null, second, false);
        assertEquals(a, b); assertEquals(texts.render(a), texts.render(b));
        assertEquals("Value 1.2; count 7", texts.render(a).get("text"));
        String json = JsonCodec.encode(a);
        assertFalse(json.contains("1.234567")); assertFalse(json.contains("1.249999"));
        List<?> args = (List<?>) ((Map<?,?>) texts.render(a).get("source")).get("args");
        assertEquals("formatted_argument", ((Map<?,?>) args.get(0)).get("kind"));
        assertEquals(Map.of("kind", "scalar", "value", 7), args.get(1));
        assertEquals(texts.render(a), texts.render(JsonCodec.decode(json)));
    }

    @Test void truncatedUserStringsNeverRetainTheirInvisibleSuffix() {
        TextProvenance texts = new TextProvenance(key -> "Prefix %.3s");
        String one = texts.onTextOperation("user", "abcFIRST_SECRET", "abcFIRST_SECRET");
        String two = texts.onTextOperation("user", "abcSECOND_SECRET", "abcSECOND_SECRET");
        String first = texts.onTextResource("Prefix abc", "test.prefix", "en", new Object[]{one}, "Prefix %.3s");
        String second = texts.onTextResource("Prefix abc", "test.prefix", "en", new Object[]{two}, "Prefix %.3s");
        Map<String,Object> a = texts.capture(null, first, false), b = texts.capture(null, second, false);
        assertEquals(a, b); assertEquals("Prefix abc", texts.render(a).get("text"));
        assertFalse(JsonCodec.encode(a).contains("SECRET")); assertFalse(JsonCodec.encode(texts.render(a)).contains("SECRET"));
        Map<?,?> arg = (Map<?,?>) ((List<?>) ((Map<?,?>) texts.render(a).get("source")).get("args")).get(0);
        assertEquals("user", arg.get("origin"));
    }

    @Test void EnglishCannotAskForPrecisionOrSuffixesOmittedByTheGui() {
        TextProvenance numbers = new TextProvenance(key -> "%.4f");
        String one = numbers.onTextResource("1.2", "test.number", "en", new Object[]{1.21d}, "%.1f");
        String two = numbers.onTextResource("1.2", "test.number", "en", new Object[]{1.24d}, "%.1f");
        assertEquals(numbers.capture(null, one, false), numbers.capture(null, two, false));
        assertEquals("[key: test.number]", rendered(numbers, one).get("text"));
        assertEquals("format_precision_not_displayed", rendered(numbers, one).get("diagnostic"));
        TextProvenance strings = new TextProvenance(key -> "%s");
        String shortValue = strings.onTextOperation("user", "abc", "abc");
        String longValue = strings.onTextOperation("user", "abcUNSEEN", "abcUNSEEN");
        String shortShown = strings.onTextResource("abc", "test.string", "en", new Object[]{shortValue}, "%.3s");
        String longShown = strings.onTextResource("abc", "test.string", "en", new Object[]{longValue}, "%.3s");
        assertEquals(strings.capture(null, shortShown, false), strings.capture(null, longShown, false));
        assertEquals("format_precision_not_displayed", rendered(strings, shortShown).get("diagnostic"));
    }

    @Test void localizedResourceStringTruncationCannotApplyGuiIndicesToEnglishNames() {
        TextProvenance texts = new TextProvenance(Map.of("test.outer", "Item %.2s", "item.private_name", "secret item")::get);
        String inner = texts.onTextResource("私密物品", "item.private_name", "zh", new Object[0], "私密物品");
        String shown = texts.onTextResource("物品 私密", "test.outer", "zh", new Object[]{inner}, "物品 %.2s");
        Map<String,Object> token = texts.capture(null, shown, false);
        assertEquals("[key: test.outer]", texts.render(token).get("text"));
        assertFalse(JsonCodec.encode(token).contains("item.private_name"));
        assertFalse(JsonCodec.encode(token).contains("secret item"));
    }

    @Test void repeatedLossyFormatsKeepEachDisplayedFragmentWithoutTheirOriginalNumber() {
        TextProvenance texts = new TextProvenance(key -> "%1$.1f / %1$.2f");
        String one = texts.onTextResource("1.2 / 1.23", "test.repeated", "en", new Object[]{1.234567d}, "%1$.1f / %1$.2f");
        String two = texts.onTextResource("1.2 / 1.23", "test.repeated", "en", new Object[]{1.234599d}, "%1$.1f / %1$.2f");
        Map<String,Object> token = texts.capture(null, one, false);
        assertEquals(token, texts.capture(null, two, false));
        assertEquals("1.2 / 1.23", texts.render(token).get("text"));
        assertFalse(JsonCodec.encode(token).contains("1.234"));
        texts.clear(); assertEquals("1.2 / 1.23", texts.render(JsonCodec.decode(JsonCodec.encode(token))).get("text"));
    }

    @Test void booleanFormattingRetainsOnlyTruthIncludingNullVersusFalse() {
        TextProvenance texts = new TextProvenance(key -> "%b");
        String one = texts.onTextResource("true", "test.truth", "en", new Object[]{123}, "%b");
        String two = texts.onTextResource("true", "test.truth", "en", new Object[]{999}, "%b");
        assertEquals(texts.capture(null, one, false), texts.capture(null, two, false));
        assertEquals("true", rendered(texts, one).get("text"));
        assertFalse(JsonCodec.encode(texts.capture(null, one, false)).contains("123"));
        String absent = texts.onTextResource("false", "test.truth", "en", new Object[]{null}, "%b");
        String negative = texts.onTextResource("false", "test.truth", "en", new Object[]{false}, "%b");
        assertEquals(texts.capture(null, absent, false), texts.capture(null, negative, false));
    }

    @Test void hashFormattingDoesNotRevealRawLongBitsOrLocalizedResourceNames() {
        TextProvenance texts = new TextProvenance(Map.of("test.hash", "%h", "item.one", "First hidden English name", "item.two", "Other hidden English name")::get);
        String first = texts.onTextResource("0", "test.hash", "en", new Object[]{0L}, "%h");
        String second = texts.onTextResource("0", "test.hash", "en", new Object[]{4294967297L}, "%h");
        assertEquals(texts.capture(null, first, false), texts.capture(null, second, false));
        assertFalse(JsonCodec.encode(texts.capture(null, second, false)).contains("4294967297"));
        String nameOne = texts.onTextResource("Aa", "item.one", "fr", new Object[0], "Aa");
        String nameTwo = texts.onTextResource("BB", "item.two", "fr", new Object[0], "BB");
        assertEquals(nameOne.hashCode(), nameTwo.hashCode());
        String hashOne = texts.onTextResource("840", "test.hash", "fr", new Object[]{nameOne}, "%h");
        String hashTwo = texts.onTextResource("840", "test.hash", "fr", new Object[]{nameTwo}, "%h");
        Map<String,Object> token = texts.capture(null, hashOne, false);
        assertEquals(token, texts.capture(null, hashTwo, false));
        assertEquals("840", texts.render(token).get("text"));
        assertFalse(JsonCodec.encode(token).contains("item.one")); assertFalse(JsonCodec.encode(token).contains("hidden English"));
    }

    @Test void literalSlicesAndContentReplacementsKeepOnlyMaterializedVisibleText() {
        TextProvenance texts = new TextProvenance(key -> null);
        for (String origin : List.of("user", "external", "system_catalog")) {
            String original = texts.onTextOperation(origin, "visible SECRET", "visible SECRET");
            String sliced = texts.onTextOperation("slice", "visible", original, 0, 7);
            String replaced = texts.onTextOperation("replace", "visible", original, " SECRET", "");
            String stripped = texts.onTextOperation("strip_prefix", "SECRET", original, "visible ");
            for (String result : List.of(sliced, replaced)) {
                Map<String,Object> token = texts.capture(null, result, false);
                assertEquals("visible", texts.render(token).get("text"));
                assertFalse(JsonCodec.encode(token).contains("SECRET")); assertFalse(JsonCodec.encode(texts.render(token)).contains("SECRET"));
                assertEquals(origin.equals("system_catalog") ? "catalog" : origin, ((Map<?,?>) token.get("node")).get("origin"));
            }
            assertEquals("SECRET", rendered(texts, stripped).get("text"));
            assertFalse(JsonCodec.encode(texts.capture(null, stripped, false)).contains("visible"));
        }
    }

    @Test void resourceDataRemovalCannotRetainDigitsSignsOrOriginalArguments() {
        TextProvenance texts = new TextProvenance(Map.of("test.number", "%d", "test.prefix", "++ %s")::get);
        String number = texts.onTextResource("987654", "test.number", "en", new Object[]{987654}, "%d");
        String replaced = texts.onTextOperation("replace", "***", number, "987654", "***");
        String prefixed = texts.onTextOperation("strip_prefix", "654", number, "987");
        String negative = texts.onTextResource("-123", "test.number", "en", new Object[]{-123}, "%d");
        String removedSign = texts.onTextOperation("strip_prefix", "123", negative, "-");
        String user = texts.onTextOperation("user", "++ visible", "++ visible");
        String localized = texts.onTextResource("++ visible", "test.prefix", "fr", new Object[]{user}, "%s");
        String notFixedByGui = texts.onTextOperation("strip_prefix", "visible", localized, "++ ");
        for (String value : List.of(replaced, prefixed, removedSign, notFixedByGui)) {
            Map<String,Object> token = texts.capture(null, value, false);
            assertNull(texts.render(token).get("source")); assertEquals("partial", texts.render(token).get("translation_status"));
            for (String secret : List.of("987654", "-123", "test.number", "test.prefix", "++ visible")) assertFalse(JsonCodec.encode(token).contains(secret));
        }
        String range = texts.onTextOperation("concat", "0-2", 0, "-", 2);
        String changed = texts.onTextOperation("replace", "0~2", range, "-", "~");
        assertEquals("0~2", rendered(texts, changed).get("text")); assertEquals("complete", rendered(texts, changed).get("translation_status"));
    }

    @Test void lossyEnglishFormattingChecksWidthAndPrecisionBeforeAllocating() {
        for (String template : List.of("%1000000000.1f", "%.1000000000f")) {
            TextProvenance texts = new TextProvenance(key -> template);
            String value = texts.onTextResource("1.2", "test.budget", "en", new Object[]{1.234567d}, "%.1f");
            Map<String,Object> token = texts.capture(null, value, false);
            assertEquals("source_limit", texts.render(token).get("diagnostic")); assertNull(texts.render(token).get("source"));
            assertFalse(JsonCodec.encode(token).contains("1.234567")); assertFalse(JsonCodec.encode(token).contains("1000000000"));
        }
    }

    @Test void aLosslessOccurrenceStillAllowsTheAlreadyVisibleFullNumber() {
        TextProvenance texts = new TextProvenance(key -> "%1$s / %1$.1f");
        String value = texts.onTextResource("1.234567 / 1.2", "test.full", "en", new Object[]{1.234567d}, "%1$s / %1$.1f");
        Map<String,Object> result = rendered(texts, value);
        assertEquals("1.234567 / 1.2", result.get("text"));
        assertEquals("scalar", ((Map<?,?>) ((List<?>) ((Map<?,?>) result.get("source")).get("args")).get(0)).get("kind"));
    }
}
