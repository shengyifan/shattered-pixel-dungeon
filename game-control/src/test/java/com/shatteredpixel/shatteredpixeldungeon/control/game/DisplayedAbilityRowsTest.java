package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisplayedAbilityRowsTest {
    private final DisplayedTextEnglish text=new DisplayedTextEnglish();
    private static final String CLOB="_冲击 (2连击):_ 将一名敌人_击退3格，施加眩晕，并且可以将其击落深渊_，但不造成伤害。提升1点连击数。";
    private static final String FLURRY="_空振 (1内力):_ 两次不耗回合的攻击，每次造成2~138点伤害，_可触发武器上的附魔_并且忽略护甲。如果武僧刚刚成功命中过敌人，这门武功不能被重复使用。";
    @Test void completeComboRowUsesOnlyItsDisplayedNameCostAndDescription() {
        assertEquals("_clobber (2 combo):_ Knocks an enemy back _3 tiles, inflicts vertigo, and can knock into pits,_ but deals no damage. Increments combo by 1.",text.translate(CLOB));
    }
    @Test void completeMonkRowPreservesDisplayedCostAndDamageArguments() {
        assertEquals("_flurry of blows (1 energy):_ Two instant strikes that deal 2-138 damage, ignore armor, _and use your weapon's enchantment._ This ability cannot be used repeatedly.",text.translate(FLURRY));
    }
    @Test void costFamilyDescriptionAndNameMustMatchOneOriginalResourceRow() {
        for(String bad:new String[]{CLOB.replace("2连击","2内力"),CLOB.replace("冲击","空振"),FLURRY.replace("空振","盘龙"),CLOB+"未知尾文",CLOB.substring(0,CLOB.length()-3)})
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translate(bad),bad);
    }
    @Test void ambiguousFuryNameRequiresItsEntireMatchingComboRow() {
        String displayed="_暴雨 (10连击):_ 你每有1点连击数便对一个敌人攻击一次，每次攻击造成60%伤害，并可触发武器附魔。使用后重置连击数。";
        assertTrue(text.translate(displayed).startsWith("_fury (10 combo):_"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translate("暴雨"));
    }
    @Test void clippedRowCannotReconstructAnUndisplayedBodyOrTail() {
        String visible="_空振 (1内力):_ 两次不耗回合的攻击";
        assertEquals("Partially displayed text",text.translateVisible(visible,true).text);
        String name="actors.buffs.monkenergy$monkability$flurry.name",desc="actors.buffs.monkenergy$monkability$flurry.desc",cost="windows.wndmonkabilities.energycost";
        DisplayedTextEnglish first=DisplayedTextEnglish.fromResources(java.util.Map.of(name,"空振",cost,"(%d内力)",desc,"两次不耗回合的攻击，未见甲。"),
                java.util.Map.of(name,"flurry",cost,"(%d energy)",desc,"Two instant strikes, unseen A."));
        DisplayedTextEnglish second=DisplayedTextEnglish.fromResources(java.util.Map.of(name,"空振",cost,"(%d内力)",desc,"两次不耗回合的攻击，未见乙。"),
                java.util.Map.of(name,"flurry",cost,"(%d energy)",desc,"Two instant strikes, unseen B."));
        assertEquals("Partially displayed text",first.translateVisible(visible,true).text);
        assertEquals(first.translateVisible(visible,true).text,second.translateVisible(visible,true).text);
    }
}
