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

    private static Map<String,Object> noteInput(String title,int maxLength,boolean multiline) {
        return map("scene","GameScene","modal",true,"controls",new java.util.ArrayList<>(Arrays.asList(
                map("id","title","role","text","text",title),
                map("id","input","role","text_input","value","Original English user text","max_length",maxLength,"multiline",multiline),
                map("id","confirm","role","button","text","确定"),map("id","cancel","role","button","text","取消"))));
    }

    @Test void noteInputRequiresTheMatchingStandardTitleAndPublishedInputShape() {
        Map<String,Object> confirm=map("action","ui.activate","control","confirm","label","确定");
        for(String title:Arrays.asList("New Text Note","新建地牢楼层备注","New Inventory Item Note","新建物品类别备注","Edit Title"))
            assertEquals("Confirm",PublicEnglishProjection.copyWithUi(confirm,noteInput(title,50,false)).get("label"));
        for(String title:Arrays.asList("Add Text","编辑文本"))
            assertEquals("Confirm",PublicEnglishProjection.copyWithUi(confirm,noteInput(title,500,true)).get("label"));
        for(Map<String,Object> ui:Arrays.asList(noteInput("New Text Note",500,true),noteInput("Add Text",50,false),noteInput("New Text Note",51,false)))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(confirm,ui));
    }

    @Test void userTypedTitleCannotImpersonateANoteDialogAndEachInputSignaturePartIsRequired() {
        Map<String,Object> confirm=map("action","ui.activate","control","confirm","label","确定");
        Map<String,Object> wrong=noteInput("An unrelated dialog",50,false);
        @SuppressWarnings("unchecked") java.util.List<Map<String,Object>> wrongNodes=(java.util.List<Map<String,Object>>)wrong.get("controls");
        wrongNodes.get(1).put("value","New Text Note");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(confirm,wrong));
        for(int missing=0;missing<4;missing++) {
            Map<String,Object> ui=noteInput("New Text Note",50,false);
            @SuppressWarnings("unchecked") java.util.List<Map<String,Object>> nodes=(java.util.List<Map<String,Object>>)ui.get("controls");
            nodes.remove(missing);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(confirm,ui));
        }
        Map<String,Object> ui=noteInput("New Text Note",50,false);ui.put("modal",false);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(confirm,ui));
        ui.put("modal",true);ui.put("scene","StartScene");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(confirm,ui));
    }

    @Test void noteViewDeletionUsesOnlyTheThreePublishedButtonsAndNotAUserTitle() {
        Map<String,Object> deletion=map("action","ui.activate","control","delete","label","删除");
        java.util.List<Map<String,Object>> nodes=new java.util.ArrayList<>(Arrays.asList(
                map("id","title","role","text","text","A user's arbitrary title"),
                map("id","edit","role","button","text","编辑标题"),map("id","body","role","button","text","添加文本"),
                map("id","delete","role","button","text","删除")));
        Map<String,Object> ui=map("scene","GameScene","modal",true,"controls",nodes);
        assertEquals("Delete",PublicEnglishProjection.copyWithUi(deletion,ui).get("label"));
        nodes.get(0).put("text","A different user title and hidden item association");nodes.get(2).put("text","Edit Text");
        assertEquals("Delete",PublicEnglishProjection.copyWithUi(deletion,ui).get("label"));
        for(int index=1;index<4;index++) {
            Map<String,Object> removed=nodes.remove(index);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(deletion,ui));
            nodes.add(index,removed);
        }
    }

    @Test void noteDeleteConfirmationNeedsTheWholePublicQuestionAndBothChoices() {
        Map<String,Object> confirm=map("action","ui.activate","control","confirm","label","确定");
        java.util.List<Map<String,Object>> nodes=new java.util.ArrayList<>(Arrays.asList(
                map("id","question","role","text","text","你确定要删除这个备注吗？"),
                map("id","confirm","role","button","text","确定"),map("id","cancel","role","button","text","取消")));
        Map<String,Object> ui=map("scene","GameScene","modal",true,"controls",nodes);
        assertEquals("Confirm",PublicEnglishProjection.copyWithUi(confirm,ui).get("label"));
        nodes.get(0).put("text","Are you sure you want to delete this custom note?");
        assertEquals("Confirm",PublicEnglishProjection.copyWithUi(confirm,ui).get("label"));
        nodes.get(0).put("text","Are you sure you want to delete this custom note? Hidden extra text");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(confirm,ui));
        nodes.get(0).put("text","Are you sure you want to delete this custom note?");nodes.remove(2);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(confirm,ui));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(confirm));
    }

    @Test void bindingInputPublicMarkerAndButtonAncestorsDistinguishNoneFromUnbindKey() {
        Map<String,Object> window=map("id","input-window","role","window","binding_input",true);
        Map<String,Object> button=map("id","unbind","role","button","parent","input-window","label","无按键");
        Map<String,Object> child=map("id","unbind-label","role","text","parent","unbind","text","无按键");
        Map<String,Object> current=map("id","current","role","text","parent","input-window","text","当前键位：_无按键_");
        Map<String,Object> ui=map("scene","GameScene","modal",true,"controls",Arrays.asList(window,button,child,current));
        assertEquals("Unbind Key",PublicEnglishProjection.copyWithUi(child,ui).get("text"));
        assertEquals("Unbind Key",PublicEnglishProjection.copyWithUi(map("action","ui.activate","control","unbind","label","无按键"),ui).get("label"));
        assertEquals("Current binding: _None_",PublicEnglishProjection.copyWithUi(current,ui).get("text"));
        child.put("parent","input-window");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(child,ui));
    }

    @Test void editingInputCannotBorrowOldPanelStateOrUnpublishedWindowClass() {
        Map<String,Object> button=map("id","unbind","role","button","label","无按键");
        Map<String,Object> window=map("id","input-window","role","window","binding_input",true);
        Map<String,Object> ui=map("scene","GameScene","modal",true,"controls",Arrays.asList(window,button));
        assertEquals("Unbind Key",PublicEnglishProjection.copyWithUi(button,ui).get("label"));
        window.remove("binding_input");window.put("hidden_window","WndChangeBinding");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(button,ui));
        window.put("binding_input","true");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(button,ui));
        Map<String,Object> oldPanel=map("scene","GameScene","modal",true,"controls",bindingPanel());
        assertEquals("Confirm",PublicEnglishProjection.copyWithUi(map("action","ui.activate","control","confirm","label","确定"),oldPanel).get("label"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(button,ui));
    }
}
