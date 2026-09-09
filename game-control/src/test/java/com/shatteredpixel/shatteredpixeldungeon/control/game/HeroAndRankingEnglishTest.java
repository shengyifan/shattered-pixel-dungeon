package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class HeroAndRankingEnglishTest {
    private static Map<String,Object> heroUi(){return map("scene","HeroSelectScene","modal",true,"controls",new ArrayList<>(List.of(
            map("id","title","role","text","text","专精"),
            map("id","description","role","text","text","击杀第二个Boss后可以选择一种职业专精。"))));}
    private static Map<String,Object> row(){return map("id","record","role","button","text","1\n获得Yendor护符\n1");}
    private static Map<String,Object> rankingUi(){return map("scene","RankingsScene","modal",false,"controls",List.of(row()));}

    @Test void subclassPageRequiresTheActualPublicSceneTitleAndFullExplanation() {
        assertTrue(PublicEnglishProjection.copy(heroUi()).toString().contains("subclasses"));
        for(int i=0;i<2;i++) {
            Map<String,Object> ui=heroUi();((List<?>)ui.get("controls")).remove(i);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                    ()->PublicEnglishProjection.copyWithUi(map("text","专精"),ui));
        }
        for(Map<String,Object> replacement:List.of(map("scene","GameScene"),map("modal",false))) {
            Map<String,Object> ui=heroUi();ui.putAll(replacement);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                    ()->PublicEnglishProjection.copyWithUi(map("text","专精"),ui));
        }
    }
    @Test void rankingRowAndItsPublicChildKeepDisplayedNumbersAndOriginalTense() {
        assertEquals("1\nObtained the Amulet of Yendor\n1",PublicEnglishProjection.copyWithUi(row(),rankingUi()).get("text"));
        assertEquals("Obtained the Amulet of Yendor",PublicEnglishProjection.copyWithUi(
                map("id","description","parent","record","role","text","text","获得Yendor护符"),rankingUi()).get("text"));
        assertEquals("1\nObtained the Amulet of Yendor\n1",PublicEnglishProjection.copyWithUi(
                map("action","ui.activate","control","record","label","1\n获得Yendor护符\n1"),rankingUi()).get("label"));
    }
    @Test void rankingContextCannotBeGrantedByHiddenRecordOrAnUnrelatedButton() {
        for(Map<String,Object> replacement:List.of(map("scene","JournalScene"),map("modal",true),
                map("controls",List.of(map("id","record","role","text","text","1\n获得Yendor护符\n1"))),
                map("controls",List.of(map("id","record","role","button","text","获得Yendor护符"))))) {
            Map<String,Object> ui=rankingUi();ui.putAll(replacement);ui.put("hidden_win",true);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(
                    map("id","child","role","text","parent","record","text","获得Yendor护符"),ui));
        }
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->PublicEnglishProjection.copyWithUi(map("text","获得Yendor护符"),rankingUi()));
    }
    @Test void unknownSuffixMissingParentAndPriorResponsesDoNotRestoreAResult() {
        for(Map<String,Object> value:List.of(map("text","获得Yendor护符"),
                map("control","missing","label","获得Yendor护符"),map("control","record","label","获得Yendor护符未知尾文")))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(value,rankingUi()));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(row()));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(map("text","专精")));
    }
}
