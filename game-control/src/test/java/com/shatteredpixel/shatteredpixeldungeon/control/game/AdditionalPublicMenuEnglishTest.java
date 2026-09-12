package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class AdditionalPublicMenuEnglishTest {
    private static Map<String,Object> node(String id,String role,String text){return map("id",id,"role",role,"parent","window","text",text);}
    private static Map<String,Object> ui(List<Map<String,Object>> nodes){List<Map<String,Object>> all=new ArrayList<>();all.add(map("id","window","role","window"));all.addAll(nodes);return map("scene","GameScene","modal",true,"controls",all);}
    private static Map<String,Object> itemUi(String title,String action,String body) {
        Map<String,Object> ui=ui(List.of(node("title","text",title),node("drop","button","放下"),node("throw","button","扔出"),
                node("unequip","button","取下"),node("choice","button",action),node("body","text",body)));
        ui.put("inspected_item",map("control","window","level_known",true));return ui;
    }
    private static String action(Map<String,Object> ui,String label){return (String)PublicEnglishProjection.copyWithUi(map("action","ui.activate","control","choice","label",label),ui).get("label");}
    @Test void threePublishedComboWeaponsResolveTheirButtonButOtherKnownItemsDoNot() {
        for(String title:List.of("魔岩拳套","镶钉手套 +3","双钗","Stone Gauntlet +1","Studded Gloves","sai"))
            assertEquals("combo strike",action(itemUi(title,"连击",""),"连击"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->action(itemUi("spear","连击",""),"连击"));
    }
    @Test void shadowCloneRequiresPublicArmorTitleAndBothCompleteDescriptionUnits() {
        String armor="裹着这身与黑暗融为一体的斗篷时，盗贼能够施展一项特殊技能。";
        String ability="盗贼召唤一个_暗影映像_，并能使唤其帮助自己战斗。 现在使用该能力将消耗_50_的充能。";
        assertEquals("shadow clone",action(itemUi("英雄风衣","暗影映像",armor+"\n\n"+ability),"暗影映像"));
        for(String incomplete:List.of(armor,ability,armor+"\n\n"+ability+"未知尾文"))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->action(itemUi("英雄风衣","暗影映像",incomplete),"暗影映像"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->action(itemUi("暗影斗篷","暗影映像",armor+"\n\n"+ability),"暗影映像"));
    }
    @Test void theSameShadowClonePublicSignatureAlsoWorksAfterEnglishProjection() {
        String body="While wearing this dark garb, the Rogue can perform a special ability.\n\nThe Rogue summons a _Shadow Clone_, which can be directed to aid him in combat. Using the ability right now will consume _50_ charge.";
        assertEquals("shadow clone",action(itemUi("Hero's Garb +2","暗影映像",body),"暗影映像"));
    }
    private static Map<String,Object> upgrade(){return ui(List.of(node("title","text","升级一件物品"),node("description","text","升级这件物品会永久提升其如下属性："),node("upgrade","button","升级"),node("choice","button","返回")));}
    @Test void upgradeBackRequiresTitleCompleteDescriptionAndBothOriginalButtons() {
        assertEquals("Back",action(upgrade(),"返回"));
        for(int i=1;i<5;i++) {
            Map<String,Object> ui=upgrade();((List<?>)ui.get("controls")).remove(i);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->action(ui,"返回"));
        }
    }
    @SuppressWarnings("unchecked")
    @Test void upgradeRemainingCountMayBeShownButUnknownTailDoesNotCreateTheSignature() {
        Map<String,Object> ui=upgrade();Map<String,Object> description=(Map<String,Object>)((List<?>)ui.get("controls")).get(2);
        description.put("text","升级这件物品会永久提升其如下属性：\n你还剩有_7个_升级用物品。");assertEquals("Back",action(ui,"返回"));
        description.put("text",description.get("text")+"未知尾文");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->action(ui,"返回"));
    }
    @SuppressWarnings("unchecked")
    @Test void armorUpgradeTranslatesBothTheBlockingStatAndTheCompleteWindowText() {
        Map<String,Object> ui=upgrade();List<Map<String,Object>> nodes=(List<Map<String,Object>>)ui.get("controls");
        String displayed="升级这件物品会永久提升其如下属性：\n+1\n防御\n0~2\n1~3\n重量\n10\n9";
        nodes.get(0).put("text",displayed);
        nodes.add(node("blocking","text","防御"));
        nodes.add(node("weight","text","重量"));
        Map<String,Object> translated=PublicEnglishProjection.copy(ui);
        List<Map<String,Object>> english=(List<Map<String,Object>>)translated.get("controls");
        assertEquals("Upgrading an item permanently improves it:\n+1\nBlocking\n0~2\n1~3\nWeight\n10\n9",english.get(0).get("text"));
        assertEquals("Blocking",english.get(5).get("text"));
        assertEquals("Weight",english.get(6).get("text"));
        assertEquals(displayed,nodes.get(0).get("text"));
        assertEquals("防御",nodes.get(5).get("text"));
        assertEquals(translated,PublicEnglishProjection.copy(translated));
    }
    @SuppressWarnings("unchecked")
    @Test void blockingRequiresTheCompleteUpgradeSignatureAndAWindowOrTextNode() {
        Map<String,Object> label=node("blocking","text","防御");
        for(int i=1;i<5;i++) {
            Map<String,Object> ui=upgrade();((List<?>)ui.get("controls")).remove(i);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                    ()->PublicEnglishProjection.copyWithUi(label,ui));
        }
        Map<String,Object> ui=upgrade();ui.put("modal",false);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->PublicEnglishProjection.copyWithUi(label,ui));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->PublicEnglishProjection.copyWithUi(node("blocking","button","防御"),upgrade()));
        for(String unsafe:List.of("防御未知尾文","防御\n未知尾文","未知前文\n防御"))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                    ()->PublicEnglishProjection.copyWithUi(node("blocking","text",unsafe),upgrade()));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->PublicEnglishProjection.copy(label));
    }
    @Test void armorAugmentationKeepsDefenseAndCannotBorrowUpgradeBlockingContext() {
        Map<String,Object> augmentation=ui(List.of(node("prompt","text","你想强化哪个属性？"),
                node("evasion","button","闪避"),node("choice","button","防御"),node("cancel","button","算了")));
        assertEquals("Defense",action(augmentation,"防御"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->PublicEnglishProjection.copyWithUi(node("blocking","text","防御"),augmentation));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->PublicEnglishProjection.copyWithUi(node("blocking","text","防御"),ui(List.of())));
    }
    private static Map<String,Object> scroll(){return ui(List.of(node("warning","text","你真的想终止这张卷轴的施放？这张卷轴之前未被鉴定，因此它仍会被消耗掉。"),node("choice","button","是的，我确定"),node("no","button","不，我改变主意了")));}
    @Test void inventoryScrollCancelRequiresTheExactWarningAndBothChoices() {
        assertEquals("Yes, I'm positive",action(scroll(),"是的，我确定"));
        for(int i=1;i<4;i++) {
            Map<String,Object> ui=scroll();((List<?>)ui.get("controls")).remove(i);
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->action(ui,"是的，我确定"));
        }
    }
    @Test void noPreviousDialogOrMissingModalityMayBeBorrowedForTheNextRequest() {
        assertEquals("Yes, I'm positive",action(scroll(),"是的，我确定"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->action(ui(List.of(node("choice","button","是的，我确定"))),"是的，我确定"));
        Map<String,Object> ui=scroll();ui.put("modal",false);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->action(ui,"是的，我确定"));
    }
}
