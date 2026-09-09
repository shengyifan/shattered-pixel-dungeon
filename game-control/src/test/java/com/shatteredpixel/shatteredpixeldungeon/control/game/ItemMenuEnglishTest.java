package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class ItemMenuEnglishTest {
    private static Map<String,Object> ui(String title){return map("scene","GameScene","modal",true,"inspected_item",map("control","window","level_known",true),
            "controls",new ArrayList<>(List.of(map("id","window","role","window"),map("id","title","parent","window","role","text","text",title),
                    map("id","drop","parent","window","role","button","text","放下"),map("id","throw","parent","window","role","button","text","扔出"),
                    map("id","unequip","parent","window","role","button","text","取下"),map("id","stealth","parent","window","role","button","text","潜行"),
                    map("id","button-label","parent","stealth","role","text","text","潜行"))));}
    private static Map<String,Object> action(){return map("action","ui.activate","control","stealth","label","潜行");}
    @Test void cloakAndKnownSneakWeaponTitlesChooseTheirOriginalVisibleMenuVerb() {
        for(String title:List.of("暗影斗篷","Cloak of Shadows +3","暗影斗篷 +4 x2"))
            assertEquals("STEALTH",PublicEnglishProjection.copyWithUi(action(),ui(title)).get("label"));
        for(String title:List.of("匕首","长匕首","暗杀之刃","Assassin's Blade +2","dagger"))
            assertEquals("sneak",PublicEnglishProjection.copyWithUi(action(),ui(title)).get("label"));
    }
    @Test void childTextAndActionFollowOnlyTheCurrentInspectedWindow() {
        Map<String,Object> ui=ui("暗影斗篷");
        assertTrue(PublicEnglishProjection.copy(ui).toString().contains("STEALTH"));
        ui.remove("inspected_item");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action(),ui));
    }
    @SuppressWarnings("unchecked")
    @Test void everyPublicMenuSignaturePieceIsRequiredAndUnknownTitleTailsAreNotIgnored() {
        for(int index=1;index<5;index++) {
            Map<String,Object> ui=ui("暗影斗篷");((List<?>)ui.get("controls")).remove(index);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action(),ui));
        }
        for(String title:List.of("暗影斗篷未知尾文","User note about Cloak of Shadows","暗影斗篷 +secret"))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action(),ui(title)));
    }
    @SuppressWarnings("unchecked")
    @Test void aTitleOrButtonInAnotherPublicTreeCannotLendItsContext() {
        Map<String,Object> ui=ui("暗影斗篷");List<Map<String,Object>> nodes=(List<Map<String,Object>>)ui.get("controls");
        nodes.get(1).put("parent","different-window");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action(),ui));
        nodes.get(1).put("parent","window");nodes.get(5).put("parent","different-window");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(action(),ui));
    }
    @Test void samePublicItemMenuIgnoresUnpublishedObjectKinds() {
        Map<String,Object> a=ui("暗影斗篷"),b=ui("暗影斗篷");a.put("hidden_item","cloak");b.put("hidden_item","dagger");
        assertEquals(PublicEnglishProjection.copyWithUi(action(),a),PublicEnglishProjection.copyWithUi(action(),b));
    }
}
