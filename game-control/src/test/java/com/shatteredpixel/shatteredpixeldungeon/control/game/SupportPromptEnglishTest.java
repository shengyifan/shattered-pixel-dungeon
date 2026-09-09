package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class SupportPromptEnglishTest {
    private static String resource(String key, boolean chinese) throws Exception {
        String file = key.startsWith("windows.") ? "windows" : "scenes";
        Properties values = new Properties();
        String path = "messages/" + file + "/" + file + (chinese ? "_zh" : "") + ".properties";
        try (InputStreamReader reader = new InputStreamReader(java.util.Objects.requireNonNull(
                SupportPromptEnglishTest.class.getClassLoader().getResourceAsStream(path)), StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        return java.util.Objects.requireNonNull(values.getProperty(key));
    }

    private static String body(boolean chinese, boolean notice) throws Exception {
        return resource("windows.wndsupportprompt.intro", chinese) + "\n\n"
                + resource("scenes.supporterscene.patreon_msg", chinese)
                + (notice ? "\n" + resource("scenes.supporterscene.patreon_english", chinese) : "") + "\n- Evan";
    }

    private static Map<String, Object> ui(boolean chinese, boolean notice) throws Exception {
        String link = resource("scenes.supporterscene.supporter_link", chinese);
        String close = resource("windows.wndsupportprompt.close", chinese);
        return map("scene", "GameScene", "modal", true, "controls", new ArrayList<>(List.of(
                map("id", "root", "role", "window"),
                map("id", "title", "role", "text", "parent", "root", "text", resource("windows.wndsupportprompt.title", chinese)),
                map("id", "body", "role", "text", "parent", "root", "text", body(chinese, notice)),
                map("id", "link", "role", "button", "parent", "root", "enabled", true, "text", link),
                map("id", "link-text", "role", "text", "parent", "link", "text", link),
                map("id", "close", "role", "button", "parent", "root", "enabled", true, "text", close),
                map("id", "close-text", "role", "text", "parent", "close", "text", close))));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> ui) { return (List<Map<String, Object>>) ui.get("controls"); }

    private static void rejected(Map<String, Object> ui) {
        assertFalse(PublicDialogSignatures.identify(ui).get("support_prompt"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                () -> PublicEnglishProjection.copyWithUi(map("action", "ui.activate", "control", "close", "label", "关闭"), ui));
    }

    @Test void completeOriginalChinesePromptTranslatesAndPreservesExtraEnglishNotice() throws Exception {
        Map<String, Object> source = ui(true, true);
        String before = source.toString();
        assertTrue(PublicDialogSignatures.identify(source).get("support_prompt"));
        Map<String, Object> english = PublicEnglishProjection.copy(source);
        assertEquals("Close", nodes(english).get(5).get("text"));
        assertEquals("Close", nodes(english).get(6).get("text"));
        assertEquals(body(false, true), nodes(english).get(2).get("text"));
        assertEquals(before, source.toString());
        assertTrue(PublicDialogSignatures.identify(english).get("support_prompt"));
    }

    @Test void nativeEnglishPromptOmitsNoticeButChinesePromptCannotOmitIt() throws Exception {
        assertTrue(PublicDialogSignatures.identify(ui(false, false)).get("support_prompt"));
        assertEquals(body(false, false), nodes(PublicEnglishProjection.copy(ui(false, false))).get(2).get("text"));
        rejected(ui(true, false));
    }

    @Test void everyOriginalVisibleSignaturePartIsRequiredWithoutUnknownSuffix() throws Exception {
        for (int index : List.of(1, 2, 3, 5)) {
            Map<String, Object> source = ui(true, true);
            nodes(source).get(index).put("text", nodes(source).get(index).get("text") + "未知尾文");
            rejected(source);
            source = ui(true, true); nodes(source).remove(index); rejected(source);
        }
        Map<String, Object> source = ui(true, true);
        nodes(source).get(2).put("text", body(true, true).replace("\n- Evan", "")); rejected(source);
    }

    @Test void wrongSceneClippingWrongRolesAndOtherWindowShapesAreRejected() throws Exception {
        Map<String, Object> source = ui(true, true); source.put("scene", "TitleScene"); rejected(source);
        source = ui(true, true); source.put("modal", false); rejected(source);
        for (int index = 0; index < 7; index++) {
            source = ui(true, true); nodes(source).get(index).put("clipped", true); rejected(source);
        }
        for (String role : List.of("text_input", "slider", "window", "button")) {
            source = ui(true, true);
            nodes(source).add(map("id", "extra", "role", role, "parent", "root", "text", "关闭", "enabled", true)); rejected(source);
        }
        source = ui(true, true); nodes(source).get(5).put("enabled", false); rejected(source);
        source = ui(true, true); nodes(source).get(2).put("parent", "absent"); rejected(source);
    }

    @Test void onlyCurrentCloseButtonAndItsOwnRenderedLabelReceiveThePolicy() throws Exception {
        Map<String, Object> source = ui(true, true);
        assertEquals("Close", PublicEnglishProjection.copyWithUi(map("action", "ui.activate", "control", "close", "label", "关闭"), source).get("label"));
        assertEquals("Close", PublicEnglishProjection.copyWithUi(nodes(source).get(6), source).get("text"));
        for (Map<String, Object> value : List.of(map("text", "关闭"), map("control", "missing", "label", "关闭"),
                map("control", "link", "label", "关闭"),
                map("id", "missing", "role", "button", "text", "关闭"), map("id", "body", "role", "text", "parent", "root", "text", "关闭"))) {
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                    () -> PublicEnglishProjection.copyWithUi(value, source));
        }
        PublicEnglishProjection.copy(source);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                () -> PublicEnglishProjection.copy(map("text", "关闭")));
    }

    @Test void hiddenSupportPreferenceAndItemMetadataNeverGrantTheSignature() throws Exception {
        Map<String, Object> source = ui(true, true);
        Map<String, Boolean> expected = PublicDialogSignatures.identify(source);
        source.put("supportNagged", true); source.put("hidden_key_class", "WornKey");
        assertEquals(expected, PublicDialogSignatures.identify(source));
        source.put("controls", List.of()); rejected(source);
    }
}
