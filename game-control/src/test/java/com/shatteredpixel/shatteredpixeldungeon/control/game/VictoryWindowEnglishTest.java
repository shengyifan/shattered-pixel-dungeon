package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class VictoryWindowEnglishTest {
    private static Map<String,Object> ui() {
        return map("scene","RankingsScene","modal",true,"controls",new ArrayList<>(List.of(
                map("id","title","role","text","text","获胜！"),
                map("id","body","role","text","text","恭喜您征服了这座地牢！新的游戏选项已经解锁，你可以在选择英雄时查看并设置："),
                map("id","support","role","button","text","赞助"),
                map("id","close","role","button","text","关闭"))));
    }
    private static Map<String,Object> action() {return map("action","ui.activate","control","close","label","关闭");}
    @Test void completePublicVictoryWindowResolvesOnlyTheCloseButton() {
        assertEquals("Close",PublicEnglishProjection.copyWithUi(action(),ui()).get("label"));
        assertFalse(PublicEnglishProjection.copy(ui()).toString().contains("关闭"));
        assertEquals("关闭",action().get("label"));
    }
    @Test void everyRequiredPublicSignaturePartMustExist() {
        for(int index=0;index<4;index++) {
            Map<String,Object> ui=ui();((List<?>)ui.get("controls")).remove(index);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action(),ui));
        }
        for(Map<String,Object> replacement:List.of(map("scene","GameScene"),map("modal",false))) {
            Map<String,Object> ui=ui();ui.putAll(replacement);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action(),ui));
        }
    }
    @Test void incompleteOrUnrelatedTextCannotBorrowTheButtonTranslation() {
        for(Map<String,Object> value:List.of(map("text","关闭"),map("control","missing","label","关闭"),
                map("control","close","label","关闭未知尾文")))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(value,ui()));
        Map<String,Object> clipped=map("id","partial","role","text","parent","close","text","关","clipped",true);
        assertEquals("Partially displayed text",PublicEnglishProjection.copyWithUi(clipped,ui()).get("text"));
    }
    @Test void hiddenDataAndPreviousUiCannotSelectAResource() {
        Map<String,Object> a=ui(),b=ui();a.put("hidden_win",false);b.put("hidden_win",true);
        assertEquals(PublicEnglishProjection.copyWithUi(action(),a),PublicEnglishProjection.copyWithUi(action(),b));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(action()));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->PublicEnglishProjection.copyWithUi(action(),map("scene","RankingsScene","modal",true,"controls",List.of())));
    }
}
