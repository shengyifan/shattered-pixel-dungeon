package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Whip;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.utils.Random;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Resource-only original helpers and basic widget lifetimes; no native window or profile. */
class TextProvenanceHooksTest {
    private RuntimeObserver previous;
    private TextProvenance texts;
    private final List<Object> released = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();

    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }
    @BeforeEach void setup() {
        previous = Game.observer; texts = new TextProvenance(); Messages.setup(Languages.CHI_SMPL);
        Game.observer = new RuntimeObserver() {
            @Override public String onTextResource(String value, String key, String language, Object[] args) { return texts.onTextResource(value, key, language, args); }
            @Override public String onTextResource(String value, String key, String language, Object[] args, String guiTemplate) { return texts.onTextResource(value, key, language, args, guiTemplate); }
            @Override public String onTextOperation(String op, String value, Object... args) { return texts.onTextOperation(op, value, args); }
            @Override public void onTextBound(Object owner, String value) { texts.onTextBound(owner, value); }
            @Override public void onTextReleased(Object owner) { released.add(owner); texts.onTextReleased(owner); }
            @Override public void onException(Throwable error) { errors.add(error); }
        };
    }
    @AfterEach void restore() { Game.observer = previous; Messages.setup(Languages.ENGLISH); texts.clear(); }
    private Map<String,Object> render(Object owner, String value) { return texts.render(texts.capture(owner, value, false)); }

    @Test void resourceHookRecordsTheFinalInheritedKey() {
        String actual = Messages.get(UpgradeFixture.class, "blocking");
        assertEquals("防御", actual);
        Map<String,Object> result = render(null, actual);
        assertEquals("Blocking", result.get("text"));
        assertEquals("windows.wndupgrade.blocking", ((Map<?,?>)result.get("source")).get("key"));
        assertFalse(JsonCodec.encode(texts.capture(null, actual, false)).contains("UpgradeFixture"));
    }

    @Test void languageSwitchRebuildsNewTextWithoutChangingFrozenOldSources() {
        String chinese = Messages.get(WndUpgrade.class, "blocking");
        Map<String,Object> token = texts.capture(null, chinese, false);
        Messages.setup(Languages.JAPANESE);
        String japanese = Messages.get(WndUpgrade.class, "blocking");
        assertNotEquals(chinese, japanese);
        assertEquals(token, texts.capture(null, japanese, false));
        assertEquals("Blocking", texts.render(token).get("text"));
        assertEquals(Languages.JAPANESE, Messages.selectedLanguage());
    }

    @Test void everyCurrentLanguageNameUsesFrozenEnglishLocaleMetadata() {
        for (Languages language : Languages.values()) {
            String nativeName = language.nativeName();
            Map<String,Object> result = render(null, nativeName);
            assertEquals(Locale.forLanguageTag(language.code()).getDisplayName(Locale.ENGLISH), result.get("text"));
            assertEquals(Map.of("kind", "language", "code", language.code()), result.get("source"));
            assertEquals("complete", result.get("translation_status"));
        }
    }

    @Test void bitmapConstructorAndEqualTextReplacementKeepTheLatestSource() {
        String first = texts.onTextResource("防御", "windows.wndupgrade.blocking", "zh", new Object[0]);
        String second = texts.onTextResource("防御", "items.stones.stoneofaugmentation$wndaugment.defense", "zh", new Object[0]);
        BitmapText widget = new BitmapText(first, null);
        assertEquals("Blocking", render(widget, widget.text()).get("text"));
        widget.text(second);
        assertSame(second, widget.text());
        assertEquals("Defense", render(widget, widget.text()).get("text"));
        widget.kill(); widget.destroy();
        assertTrue(released.contains(widget));
        assertNull(widget.parent);
    }

    @Test void disabledObserverPreservesOriginalEqualTextStringIdentity() {
        Game.observer = RuntimeObserver.NONE;
        String first = new String("same"), second = new String("same");
        BitmapText widget = new BitmapText(first, null);
        widget.text(second);
        assertSame(first, widget.text());
    }

    @Test void gizmoKillAndDestroyReleaseBindingsWithoutChangingOriginalFlags() {
        Gizmo owner = new Gizmo();
        owner.kill();
        assertFalse(owner.alive); assertFalse(owner.exists); assertEquals(List.of(owner), released);
        owner.revive(); assertTrue(owner.alive); assertTrue(owner.exists);
        owner.destroy(); assertEquals(List.of(owner, owner), released);
    }

    @Test void helpersDoNotPerformAdditionalObjectConversionsForEnglishOutput() {
        AtomicInteger calls = new AtomicInteger();
        Object object = new Object() { @Override public String toString() { return "value" + calls.incrementAndGet(); } };
        String format = Messages.literal("%1$s / %1$s");
        String baseline = String.format(Messages.locale(), format, object);
        int expectedCalls = calls.get(); calls.set(0);
        String actual = Messages.format(format, object);
        assertEquals(baseline, actual); assertEquals(expectedCalls, calls.get());
        assertEquals("partial", render(null, actual).get("translation_status"));
        assertEquals(expectedCalls, calls.get());
        calls.set(0);
        String concatenated = Messages.concat(Messages.literal("prefix "), object);
        assertEquals("prefix value1", concatenated); assertEquals(1, calls.get());
        render(null, concatenated); assertEquals(1, calls.get());
    }

    @Test void originalFormattingFailuresAndObjectExceptionsRemainOriginal() {
        String format = Messages.literal("%d");
        String failed = Messages.format(format, "wrong type");
        assertEquals(format, failed);
        assertEquals("original_format_failed", render(null, failed).get("diagnostic"));
        assertEquals(1, errors.size());
        assertInstanceOf(java.util.IllegalFormatException.class, errors.get(0).getCause());
        IllegalStateException failure = new IllegalStateException("original conversion failure");
        Object object = new Object() { @Override public String toString() { throw failure; } };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> Messages.format(Messages.literal("%s"), object)));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> Messages.concat("", object)));
    }

    @Test void numericAndFormattingHelpersKeepGuiResultsAndLocale() {
        Messages.setup(Languages.FRENCH);
        String decimal = Messages.decimalFormat("#.##x", 1.25d);
        assertEquals("1,25x", decimal);
        assertEquals("1.25x", render(null, decimal).get("text"));
        assertEquals(Languages.FRENCH, Messages.selectedLanguage());
        String numeric = Messages.concat(Messages.concat(20, "/"), 30);
        assertEquals("20/30", render(null, numeric).get("text"));
    }

    @Test void zeroArgumentFormatStillAppliesPercentAndNewlineConversions() {
        String template = Messages.literal("100%%%n");
        String result = Messages.format(template);
        assertEquals("100%" + System.lineSeparator(), result);
        assertEquals(result, render(null, result).get("text"));
    }

    @Test void explicitNestedHelpersPreserveOriginalEvaluationOrderNumericGroupingAndRandom() {
        byte[] random = Random.exportState();
        List<String> nativeTrace = new ArrayList<>(), helperTrace = new ArrayList<>();
        Object nativeObject = traced(nativeTrace), helperObject = traced(helperTrace);
        String expected = "prefix " + nativeObject + suffix(nativeTrace) + (1 + 2);
        String actual = Messages.concat(Messages.concat(Messages.concat(Messages.literal("prefix "), helperObject), suffix(helperTrace)), 1 + 2);
        assertEquals(expected, actual); assertEquals(nativeTrace, helperTrace);
        render(null, actual); assertEquals(nativeTrace, helperTrace);
        RuntimeObserver observer = Game.observer;
        Game.observer = RuntimeObserver.NONE;
        try {
            helperTrace.clear();
            assertEquals(expected, Messages.concat(Messages.concat(Messages.concat("prefix ", traced(helperTrace)), suffix(helperTrace)), 1 + 2));
            assertEquals(nativeTrace, helperTrace);
        } finally { Game.observer = observer; }
        assertArrayEquals(random, Random.exportState());
    }

    @Test void nativeTemplateOmissionCannotRevealWhipDamageInAnotherLanguage() {
        Messages.setup(Languages.CHI_TRAD);
        String first = Messages.get(Whip.class, "ability_desc", 10, 20);
        String second = Messages.get(Whip.class, "ability_desc", 99, 300);
        assertEquals(first, second);
        Map<String,Object> a = texts.capture(null, first, false), b = texts.capture(null, second, false);
        assertEquals(a, b); assertEquals(texts.render(a), texts.render(b));
        assertEquals("argument_not_displayed", texts.render(a).get("diagnostic"));
        assertEquals("[key: items.weapon.melee.whip.ability_desc]", texts.render(a).get("text"));
        assertFalse(JsonCodec.encode(a).contains("300"));
        assertFalse(JsonCodec.encode(a).contains("template"));
    }

    @Test void originalUnusedObjectArgumentIsIgnoredWithoutConversionOrIdentityDisclosure() {
        Object unused = new Object() { @Override public String toString() { throw new AssertionError("Original Formatter ignores this argument"); } };
        String value = Messages.get(WndUpgrade.class, "back", unused);
        assertEquals("返回", value);
        assertEquals("Back", render(null, value).get("text"));
        assertEquals("complete", render(null, value).get("translation_status"));
    }

    @Test void originalDecimalAndPrintfHelpersDiscardUndisplayedPrecisionAndSuffixes() {
        byte[] random = Random.exportState();
        String first = Messages.decimalFormat("#.0", 1.234567d), second = Messages.decimalFormat("#.0", 1.249999d);
        assertEquals(first, second); assertEquals(texts.capture(null, first, false), texts.capture(null, second, false));
        String format = Messages.literal("Prefix %.2s");
        String a = Messages.format(format, Messages.userText("用户第一秘密"));
        String b = Messages.format(format, Messages.userText("用户第二秘密"));
        assertEquals("Prefix 用户", a); assertEquals(a, b);
        Map<String,Object> token = texts.capture(null, a, false);
        assertEquals(token, texts.capture(null, b, false));
        assertEquals("Prefix 用户", texts.render(token).get("text"));
        assertFalse(JsonCodec.encode(token).contains("秘密"));
        assertArrayEquals(random, Random.exportState());
    }

    private static Object traced(List<String> trace) {
        return new Object() { @Override public String toString() { trace.add("toString"); return "object"; } };
    }
    private static String suffix(List<String> trace) { trace.add("suffix"); return " / "; }

    private static final class UpgradeFixture extends WndUpgrade { private UpgradeFixture() { super(null, null, false); } }
}
