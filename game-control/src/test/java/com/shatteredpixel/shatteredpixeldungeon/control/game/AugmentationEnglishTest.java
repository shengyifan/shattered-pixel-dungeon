package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class AugmentationEnglishTest {
    private static Map<String,Object> ui(String first,String second) {
        return map("scene","GameScene","modal",true,"controls",new ArrayList<>(List.of(
                map("id","question","role","text","text","你想强化哪个属性？"),
                map("id","first","role","button","text",first),map("id","second","role","button","text",second),
                map("id","cancel","role","button","text","算了"))));
    }
    @Test void initialAndAlreadyAugmentedMenusUseTheirOriginalLabels() {
        for(String[] options:new String[][]{{"速度","伤害"},{"伤害","移除强化"},{"闪避","防御"},{"防御","移除强化"}}) {
            Map<String,Object> translated=PublicEnglishProjection.copy(ui(options[0],options[1]));
            assertTrue(translated.toString().contains("Never mind"));
            if(options[0].equals("闪避"))assertTrue(translated.toString().contains("Evasion"));
        }
        assertEquals("Never mind",PublicEnglishProjection.copyWithUi(map("action","ui.activate","control","cancel","label","算了"),ui("速度","伤害")).get("label"));
    }
    @Test void missingPromptOptionOrCancelDoesNotGrantAnAugmentationContext() {
        for(int index=0;index<4;index++) {
            Map<String,Object> ui=ui("闪避","防御");((List<?>)ui.get("controls")).remove(index);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                    ()->PublicEnglishProjection.copyWithUi(map("id","cancel","role","button","text","算了"),ui));
        }
        for(Map<String,Object> replacement:List.of(map("scene","HeroSelectScene"),map("modal",false))) {
            Map<String,Object> ui=ui("闪避","防御");ui.putAll(replacement);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                    ()->PublicEnglishProjection.copyWithUi(map("control","first","label","闪避"),ui));
        }
    }
    @Test void unrelatedCombatTextAndUnknownTailsCannotBorrowThePropertyLabel() {
        Map<String,Object> ui=ui("闪避","防御");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->PublicEnglishProjection.copyWithUi(map("id","floating","role","text","text","闪避"),ui));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->PublicEnglishProjection.copyWithUi(map("control","cancel","label","算了未知尾文"),ui));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(map("text","算了")));
    }
    @Test void hiddenWeaponStateAndEarlierWindowsDoNotAffectTheSamePublicView() {
        Map<String,Object> a=ui("闪避","防御"),b=ui("闪避","防御");a.put("hidden_augment","NONE");b.put("hidden_augment","DEFENSE");
        Map<String,Object> translatedA=PublicEnglishProjection.copy(a),translatedB=PublicEnglishProjection.copy(b);
        assertEquals(translatedA.get("controls"),translatedB.get("controls"));
        assertEquals("NONE",translatedA.get("hidden_augment"));assertEquals("DEFENSE",translatedB.get("hidden_augment"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(
                map("control","first","label","闪避"),map("scene","GameScene","modal",true,"controls",List.of())));
    }
}
