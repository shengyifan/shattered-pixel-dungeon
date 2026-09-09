package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class DescribedNameEnglishTest {
    private static final String DEATH="盗贼标记选中的敌人，使其受到额外25%的伤害。标记不耗费时间且持续5回合。\n\n被标记的敌人受到额外伤害，但并不会在标记期间死亡。当标记结束时，生命值为0的敌人会立即死亡。";
    private static final String SHORT="盗贼向选中的敌人施加_夺命印记_。被标记的敌人将受到额外伤害，但不会在标记期间死亡。";
    private static Map<String,Object> ui(String body){return map("scene","HeroSelectScene","modal",true,"controls",List.of(
            map("id","window","role","window"),map("id","title","role","text","parent","window","text","夺命印记"),
            map("id","body","role","text","parent","window","text",body)));}
    private static Map<String,Object> title(){return map("id","title","role","text","parent","window","text","夺命印记");}
    @Test void fullPairedAbilityDescriptionAndItsKnownCostResolveTheTitle() {
        assertEquals("death mark",PublicEnglishProjection.copyWithUi(title(),ui(DEATH+"\n\n充能消耗：_25_")).get("text"));
        assertEquals("death mark",PublicEnglishProjection.copyWithUi(title(),ui(DEATH)).get("text"));
    }
    @Test void completeShortDescriptionInsideClassArmorProseCanResolveTheSameAbilityName() {
        String armor="裹着这身与黑暗融为一体的斗篷时，盗贼能够施展一项特殊技能。\n\n"+SHORT+" 现在使用该能力将消耗_15_的充能。";
        Map<String,Object> ui=ui(armor);ui.put("scene","GameScene");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(title(),ui),"A short description alone cannot impersonate a full ability detail window");
        java.util.ArrayList<Map<String,Object>> controls=new java.util.ArrayList<>();
        controls.add(map("id","window","role","window"));controls.add(map("id","item-title","role","text","parent","window","text","英雄风衣"));
        controls.add(map("id","body","role","text","parent","window","text",armor));
        for(String label:List.of("放下","扔出","取下","夺命印记"))controls.add(map("id",label,"parent","window","role","button","text",label));
        ui.put("controls",controls);ui.put("inspected_item",map("control","window","level_known",true));
        assertEquals("death mark",PublicEnglishProjection.copyWithUi(map("control","夺命印记","label","夺命印记"),ui).get("label"));
    }
    @Test void partialResourceUnknownTailAndAnUnrelatedDescriptionCannotSelectTheName() {
        for(String body:List.of(DEATH.split("\n\n")[0],DEATH+"未知尾文",DEATH+"\n\n未知尾文",SHORT+" 现在使用该能力将消耗_15_的充能。未知尾文","只是一个无关描述"))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(title(),ui(body)));
    }
    @SuppressWarnings("unchecked")
    @Test void clippedWrongRootAndMissingCurrentUiCannotSupplyADescription() {
        Map<String,Object> ui=ui(DEATH);Map<String,Object> body=(Map<String,Object>)((List<?>)ui.get("controls")).get(2);
        body.put("clipped",true);assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(title(),ui));
        body.remove("clipped");body.put("parent","another-window");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(title(),ui));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(title()));
    }
    @Test void identicalPublicDescriptionCannotDependOnAnUnpublishedAbilityObject() {
        Map<String,Object> a=ui(DEATH),b=ui(DEATH);a.put("hidden_ability","deathmark");b.put("hidden_ability","tracker");
        assertEquals(PublicEnglishProjection.copyWithUi(title(),a),PublicEnglishProjection.copyWithUi(title(),b));
    }
    @Test void twoDifferentCompleteDescriptionsForOneNameRemainAmbiguous() {
        DisplayedTextEnglish text=DisplayedTextEnglish.fromResources(Map.of("actors.hero.abilities.test.first.name","同名","actors.hero.abilities.test.first.desc","说明甲。",
                "actors.hero.abilities.test.second.name","同名","actors.hero.abilities.test.second.desc","说明乙。"),
                Map.of("actors.hero.abilities.test.first.name","First","actors.hero.abilities.test.first.desc","Description A.",
                        "actors.hero.abilities.test.second.name","Second","actors.hero.abilities.test.second.desc","Description B."));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translateInContext("同名",map("scene","HeroSelectScene","modal",true,"visible_window_texts",List.of("说明甲。","说明乙。"))));
    }
    @Test void theCompleteDisplayedTalentDescriptionDistinguishesLifeLinkFromItsSpellAndBuffNames() throws Exception {
        java.util.Properties resources=new java.util.Properties();
        try(java.io.Reader reader=new java.io.InputStreamReader(getClass().getClassLoader().getResourceAsStream("messages/actors/actors_zh.properties"),java.nio.charset.StandardCharsets.UTF_8)) {resources.load(reader);}
        Map<String,Object> ui=ui(resources.getProperty("actors.hero.talent.life_link.desc"));
        Map<String,Object> title=map("id","title","role","text","parent","window","text","血色羁绊");
        assertEquals("life link",PublicEnglishProjection.copyWithUi(title,ui).get("text"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(title));
    }
}
