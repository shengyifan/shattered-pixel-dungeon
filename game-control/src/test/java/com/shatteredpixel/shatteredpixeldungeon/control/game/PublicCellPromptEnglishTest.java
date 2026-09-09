package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class PublicCellPromptEnglishTest {
    private final DisplayedTextEnglish text=new DisplayedTextEnglish();
    private static Map<String,Object> context(String prompt){return map("scene","GameScene","modal",false,"cell_input",true,"cell_prompt",prompt);}
    private static Map<String,Object> ui(String prompt){Map<String,Object> ui=context(prompt);ui.put("controls",List.of(map("id","prompt-text","role","text","text","选择一个目标")));return ui;}

    @Test void sameChineseIsResolvedOnlyToTheExactAlreadyPublishedEnglishPrompt() {
        for(String candidate:List.of("Choose a target","Select a Target"))
            assertEquals(candidate,text.translateInContext("选择一个目标",context(candidate)));
    }
    @Test void missingInputModalOrEnglishPromptCannotGrantAChoice() {
        for(String key:List.of("cell_input","modal","cell_prompt")) {
            Map<String,Object> context=context("Choose a target");context.remove(key);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translateInContext("选择一个目标",context));
        }
        for(Object value:List.of(false,"true",1)) {
            Map<String,Object> context=context("Choose a target");context.put("cell_input",value);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translateInContext("选择一个目标",context));
        }
        Map<String,Object> modal=context("Choose a target");modal.put("modal",true);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translateInContext("选择一个目标",modal));
    }
    @Test void unknownEnglishOrChinesePromptCannotBeUsedAsADictionaryOverride() {
        for(String prompt:List.of("Choose any target please","Select a target","选择一个目标",""))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translateInContext("选择一个目标",context(prompt)));
    }
    @Test void fullVisibleTemplateArgumentsMustReproduceTheEntirePublicPrompt() {
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(Map.of("a","选择第%d个目标","b","选择第%d个目标"),
                Map.of("a","Choose target %d","b","Select target %d"));
        assertEquals("Select target 7",t.translateInContext("选择第7个目标",context("Select target 7")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("选择第7个目标",context("Select target 8")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("选择第7个目标未知尾文",context("Select target 7")));
    }
    @Test void clippedPrefixCannotRestoreTheHiddenRestOfAPrompt() {
        Map<String,Object> a=context("Choose a target"),b=context("Select a Target");
        assertEquals("Partially displayed text",text.translateVisibleInContext("选择一个目",true,a).text);
        assertEquals(text.translateVisibleInContext("选择一个目",true,a).text,text.translateVisibleInContext("选择一个目",true,b).text);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translateInContext("选择一个目标未知尾文",a));
    }
    @Test void hiddenObjectsCannotChangeAnIdenticalPublicPromptResult() {
        Map<String,Object> a=context("Choose a target"),b=context("Choose a target");
        a.put("hidden_selector","Spear");b.put("hidden_selector","SpiritBow");
        assertEquals(text.translateInContext("选择一个目标",a),text.translateInContext("选择一个目标",b));
    }
    @Test void projectionUsesOnlyTheCurrentUiAndNeverBorrowsAPreviousPrompt() {
        assertTrue(PublicEnglishProjection.copy(ui("Choose a target")).toString().contains("Choose a target"));
        Map<String,Object> next=ui("Select a Target");next.remove("cell_prompt");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(next));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(map("text","选择一个目标")));
    }
    @Test void aSceneChangeWithoutItsOwnUiCannotCarryThePromptToAnotherScene() {
        Map<String,Object> next=map("scene","TitleScene","text","选择一个目标");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(next,ui("Choose a target")));
    }
}
