package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class PublicEnglishProjectionTest {
    @Test void legacyPresentationTranslatesProseWithoutRewritingSourceOrIdentity() {
        Map<String,Object> original = map("id", "旧请求id", "raw_request", "{\"id\":\"旧请求id\"}",
                "response", map("result", map("observation", map("hero", map("class_name", "战士")),
                        "actions", Arrays.asList(map("action", "ui.activate", "label", "继续")))));
        Map<String,Object> english = PublicEnglishProjection.copy(original);
        assertNotSame(original, english);
        assertEquals(original.get("id"), english.get("id"));
        assertEquals(original.get("raw_request"), english.get("raw_request"));
        String output = english.get("response").toString().toLowerCase(java.util.Locale.ROOT);
        assertTrue(output.contains("warrior")); assertTrue(output.contains("continue"));
        assertFalse(output.contains("战士")); assertFalse(output.contains("继续"));
        assertTrue(original.get("response").toString().contains("战士"));
    }

    @Test void clippingNeverRestoresUnseenText() {
        Map<String,Object> clipped = map("text", "无资源对应的测试片段甲乙丙", "clipped", true);
        Map<String,Object> translated = PublicEnglishProjection.copy(clipped);
        assertEquals("Partially displayed text", translated.get("text"));
        assertEquals("partial", translated.get("translation_status"));
        assertEquals(true, translated.get("clipped"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                () -> PublicEnglishProjection.copy(map("text", clipped.get("text"), "clipped", false)));
    }

    @Test void plainEnglishMetadataAndOpaqueRawValuesRemainExact() {
        Map<String,Object> english = map("name", "worn shortsword", "options", Arrays.asList("Throw", "Cancel"),
                "state_version", "v:01", "raw_request", "用户自定义原始字节表示", "value", "literal input");
        assertEquals(english, PublicEnglishProjection.copy(english));
    }

    @Test void saveDeletionRequiresTheCompletePublicSaveDetailsSignature() {
        Map<String,Object> ui=map("scene","StartScene","modal",true,"controls",Arrays.asList(
                map("id","continue","role","button","text","继续"),
                map("id","erase","role","button","text","删除"),
                map("role","text","text","力量"),map("role","text","text","生命"),
                map("role","text","text","金币收集数"),map("role","text","text","最高层数")));
        Map<String,Object> action=map("action","ui.activate","control","erase","label","删除");
        assertEquals("Erase",PublicEnglishProjection.copyWithUi(action,ui).get("label"));
        ui.put("modal",false);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action,ui));
        assertEquals("删除",action.get("label"));
    }

    @Test void sliderAndNavigationContextFollowOnlyPublicNodeRelations() {
        Map<String,Object> ui=map("scene","GameScene","modal",true,"controls",Arrays.asList(
                map("id","slider","role","slider","value",0,"minimum",-1,"maximum",2),
                map("id","off","role","text","parent","slider","text","关闭"),
                map("id","back","role","button","shortcut_action","back","label","返回")));
        Map<String,Object> off=map("id","off","role","text","parent","slider","text","关闭");
        assertEquals("Off",PublicEnglishProjection.copyWithUi(off,ui).get("text"));
        Map<String,Object> back=map("action","ui.activate","control","back","label","返回");
        assertEquals("Back",PublicEnglishProjection.copyWithUi(back,ui).get("label"));
        off.put("parent","unknown-parent");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(off,ui));
    }

    @Test void bindingContextFollowsPublishedSlotsAndPublicControlOrParentLinks() {
        Map<String,Object> row=map("id","binding-row","role","component","binding_slots",Arrays.asList(1L,2L,3L));
        Map<String,Object> label=map("id","binding-label","role","text","parent","binding-row","text","选择快捷栏\nNone\nNone\nNone");
        Map<String,Object> ui=map("scene","GameScene","modal",true,"controls",Arrays.asList(row,label));
        assertEquals("Quickslot Selector\nNone\nNone\nNone",PublicEnglishProjection.copyWithUi(label,ui).get("text"));
        Map<String,Object> action=map("action","ui.binding_slot","control","binding-row","slots",Arrays.asList(1,2,3),"label","选择快捷栏");
        assertEquals("Quickslot Selector",PublicEnglishProjection.copyWithUi(action,ui).get("label"));
        assertEquals("选择快捷栏",action.get("label"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(action));
        label.put("parent","missing-parent");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(label,ui));
    }

    @Test void missingOrMalformedBindingSlotsCannotBorrowAPreviousResponseContext() {
        Map<String,Object> row=map("id","row","role","component","binding_slots",Arrays.asList(1,2,3));
        Map<String,Object> ui=map("scene","GameScene","modal",true,"controls",Arrays.asList(row));
        Map<String,Object> action=map("action","ui.binding_slot","control","row","label","选择快捷栏");
        assertEquals("Quickslot Selector",PublicEnglishProjection.copyWithUi(action,ui).get("label"));
        for(Object bad:Arrays.asList(Arrays.asList(1,2,4),Arrays.asList(1.2,2,3),Arrays.asList("1","2","3"))) {
            row.put("binding_slots",bad);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action,ui));
        }
        row.remove("binding_slots");row.put("hidden_class","BindingRow");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action,ui));
    }

    private static java.util.List<Map<String,Object>> bindingPanel() {
        return new java.util.ArrayList<>(Arrays.asList(
                map("id","row","role","entry","binding_slots",Arrays.asList(1,2,3)),
                map("id","action-head","role","text","text","行动"),map("id","key1-head","role","text","text","按键1"),
                map("id","key2-head","role","text","text","按键2"),map("id","key3-head","role","text","text","按键3"),
                map("id","defaults","role","button","text","恢复默认键位"),map("id","confirm","role","button","text","确定")));
    }

    @Test void fullBindingPanelSignatureTranslatesConfirmationAndEveryRequiredPublicFactIsNecessary() {
        java.util.List<Map<String,Object>> nodes=bindingPanel();
        Map<String,Object> ui=map("scene","GameScene","modal",true,"controls",nodes);
        Map<String,Object> action=map("action","ui.activate","control","confirm","label","确定");
        assertEquals("Confirm",PublicEnglishProjection.copyWithUi(action,ui).get("label"));
        for(int index=0;index<6;index++) {
            Map<String,Object> removed=nodes.remove(index);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action,ui),"Missing public signature component "+index);
            nodes.add(index,removed);
        }
        assertEquals("确定",action.get("label"));
    }

    @Test void aNewResponseCannotBorrowThePreviousKeyBindingPanelSignature() {
        Map<String,Object> action=map("action","ui.activate","control","confirm","label","确定");
        Map<String,Object> full=map("scene","GameScene","modal",true,"controls",bindingPanel());
        assertEquals("Confirm",PublicEnglishProjection.copyWithUi(action,full).get("label"));
        Map<String,Object> next=map("scene","GameScene","modal",true,"controls",Arrays.asList(map("id","confirm","role","button","text","确定")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action,next));
    }
}
