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

class PublicDialogSignaturesTest {
    private static String resource(String file, String key, boolean chinese) throws Exception {
        String path = "messages/" + file + "/" + file + (chinese ? "_zh" : "") + ".properties";
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
                java.util.Objects.requireNonNull(PublicDialogSignaturesTest.class.getClassLoader().getResourceAsStream(path)), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return java.util.Objects.requireNonNull(properties.getProperty(key));
    }

    private static Map<String, Object> dialog(String title, String body, String yes, String no, boolean inspected) {
        Map<String, Object> ui = map("scene", "GameScene", "modal", true, "controls", new ArrayList<>(List.of(
                map("id", "root", "role", "window"),
                map("id", "title", "role", "text", "parent", "root", "text", title),
                map("id", "body", "role", "text", "parent", "root", "text", body),
                map("id", "yes", "role", "button", "parent", "root", "enabled", true, "text", yes),
                map("id", "yes-label", "role", "text", "parent", "yes", "text", yes),
                map("id", "no", "role", "button", "parent", "root", "enabled", true, "text", no),
                map("id", "no-label", "role", "text", "parent", "no", "text", no))));
        if (inspected) ui.put("inspected_item", map("control", "root", "level_known", true));
        return ui;
    }

    private static Map<String, Object> warning(String type, boolean chinese) throws Exception {
        boolean steal = type.equals("steal");
        String prefix = steal ? "windows.wndtradeitem.steal_warn" : "windows.wndresurrect.warn";
        String title = steal ? resource("items", "items.artifacts.masterthievesarmband.name", chinese)
                : resource("windows", prefix + "_title", chinese);
        if (steal && !chinese) title = "Master Thieves' Armband";
        return dialog(title, resource("windows", prefix + (steal ? "" : "_body"), chinese),
                resource("windows", prefix + "_yes", chinese), resource("windows", prefix + "_no", chinese), false);
    }

    private static Map<String, Object> reward() throws Exception {
        return dialog(resource("items", "items.weapon.melee.sword.name", true),
                resource("items", "items.weapon.melee.sword.desc", true),
                resource("windows", "windows.wndsadghost.confirm", true),
                resource("windows", "windows.wndsadghost.cancel", true), true);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> ui) { return (List<Map<String, Object>>) ui.get("controls"); }
    private static void none(Map<?, ?> ui) { assertFalse(PublicDialogSignatures.identify(ui).containsValue(true)); }

    @Test void fullNativeResourceWarningsAreSeparateInBothLanguages() throws Exception {
        for (boolean chinese : new boolean[]{true, false}) {
            assertEquals(map("steal_warning", true, "resurrection_warning", false, "reward_confirmation", false),
                    PublicDialogSignatures.identify(warning("steal", chinese)));
            assertEquals(map("steal_warning", false, "resurrection_warning", true, "reward_confirmation", false),
                    PublicDialogSignatures.identify(warning("resurrect", chinese)));
        }
    }

    @Test void eachWarningRequiresExactTitleBodyAndBothButtons() throws Exception {
        for (String type : List.of("steal", "resurrect")) {
            for (int index : List.of(1, 2, 3, 5)) {
                Map<String, Object> ui = warning(type, true);
                nodes(ui).get(index).put("text", nodes(ui).get(index).get("text") + "未知尾文");
                none(ui);
                ui = warning(type, true);
                nodes(ui).remove(index);
                none(ui);
            }
            Map<String, Object> ui = warning(type, true);
            nodes(ui).get(2).put("text", nodes(ui).get(2).get("text").toString().substring(0, 20));
            none(ui);
        }
    }

    @Test void rewardUsesThePublicInspectedRootAndCompleteOriginalTwoButtonShape() throws Exception {
        Map<String, Object> ui = reward();
        assertTrue(PublicDialogSignatures.identify(ui).get("reward_confirmation"));
        nodes(ui).get(1).put("text", "Wand of Magic Missile +1");
        nodes(ui).get(2).put("text", "Its already displayed item description.");
        assertTrue(PublicDialogSignatures.identify(ui).get("reward_confirmation"));
        // Recognition does not grant an English fallback for an unrecognized body.
        nodes(ui).get(2).put("text", "未知物品正文不是可翻译资源");
        assertTrue(PublicDialogSignatures.identify(ui).get("reward_confirmation"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                () -> new DisplayedTextEnglish().translate((String) nodes(ui).get(2).get("text")));
    }

    @Test void rewardRequiresItsOwnTwoNonButtonTextNodes() throws Exception {
        for (int index : List.of(1, 2, 3, 5)) {
            Map<String, Object> ui = reward(); nodes(ui).remove(index); none(ui);
        }
        Map<String, Object> ui = reward(); ui.remove("inspected_item"); none(ui);
        ui = reward(); ui.put("inspected_item", map("control", "other")); none(ui);
        ui = reward(); nodes(ui).get(2).put("parent", "yes"); none(ui);
        ui = reward(); nodes(ui).get(2).put("text", " "); none(ui);
    }

    @Test void wrongSceneMissingModalAndClippedNodesCannotSupplyContext() throws Exception {
        for (String type : List.of("steal", "resurrect", "reward")) {
            Map<String, Object> ui = type.equals("reward") ? reward() : warning(type, true);
            ui.put("scene", "TitleScene"); none(ui); ui.put("scene", "GameScene"); ui.put("modal", false); none(ui);
            ui.put("modal", true);
            for (Map<String, Object> node : nodes(ui)) {
                node.put("clipped", true); none(ui); node.remove("clipped");
                node.put("translation_status", "partial"); none(ui); node.remove("translation_status");
            }
        }
    }

    @Test void extraInteractionsOtherRootsBrokenParentsAndCyclesAreRejected() throws Exception {
        for (String role : List.of("button", "text_input", "slider", "scroll", "context_menu", "window")) {
            Map<String, Object> ui = reward();
            nodes(ui).add(map("id", "extra", "role", role, "parent", "root", "enabled", true, "text", "确定")); none(ui);
        }
        Map<String, Object> ui = reward(); nodes(ui).add(map("id", "other", "role", "window")); none(ui);
        ui = reward(); nodes(ui).get(2).put("parent", "absent"); none(ui);
        ui = reward(); nodes(ui).get(2).put("parent", "body"); none(ui);
        ui = reward(); nodes(ui).get(2).put("id", "title"); none(ui);
        ui = reward(); nodes(ui).get(3).put("enabled", false); none(ui);
        ui = reward(); nodes(ui).get(3).put("label", "some other operation"); none(ui);
        for (int index : List.of(1, 2, 3, 5)) {
            ui = reward(); nodes(ui).get(index).put("label", nodes(ui).get(index).remove("text")); none(ui);
        }
    }

    @Test void hiddenStateDoesNotChangeRecognitionAndPreviousUiCannotGrantContext() throws Exception {
        Map<String, Object> a = reward(), b = reward();
        a.put("hidden_quest", "ghost"); b.put("hidden_quest", "blacksmith");
        a.put("hidden_item_type", "Sword"); b.put("hidden_item_type", "Wand");
        a.put("inspected_item", map("control", "root", "level_known", false));
        b.put("inspected_item", map("control", "root", "level_known", true));
        String before = a.toString();
        assertEquals(PublicDialogSignatures.identify(a), PublicDialogSignatures.identify(b));
        assertEquals(before, a.toString());
        none(map("scene", "GameScene", "modal", true, "controls", List.of()));
        none(null);
    }

    @Test void projectionUsesOnlyOriginalButtonDomainsOnTheCurrentPublicTree() throws Exception {
        for (String type : List.of("steal", "resurrect", "reward")) {
            Map<String, Object> ui = type.equals("reward") ? reward() : warning(type, true);
            String source = (String) nodes(ui).get(3).get("text");
            String expected = type.equals("reward") ? "Confirm" : "Yes, I'm sure";
            assertEquals(expected, PublicEnglishProjection.copyWithUi(
                    map("action", "ui.activate", "control", "yes", "label", source), ui).get("label"));
            assertEquals(expected, PublicEnglishProjection.copyWithUi(nodes(ui).get(4), ui).get("text"));
            Map<String, Object> translated = PublicEnglishProjection.copy(ui);
            assertEquals(expected, nodes(translated).get(3).get("text"));
            String no = type.equals("reward") ? "Cancel" : type.equals("steal") ? "No, I changed my mind" : "No, let me reconsider";
            assertEquals(no, nodes(translated).get(5).get("text"));
        }
    }

    @Test void projectionCannotTranslateStandaloneOrOldButtonsAndNeverMasksUnknownBody() throws Exception {
        Map<String, Object> ui = reward();
        for (Map<String, Object> value : List.of(map("text", "确定"), map("control", "other", "label", "确定"))) {
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                    () -> PublicEnglishProjection.copyWithUi(value, ui));
        }
        PublicEnglishProjection.copy(ui);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                () -> PublicEnglishProjection.copy(map("text", "确定")));
        nodes(ui).get(2).put("text", "未知物品正文不是可翻译资源");
        assertTrue(PublicDialogSignatures.identify(ui).get("reward_confirmation"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class, () -> PublicEnglishProjection.copy(ui));
    }
}
