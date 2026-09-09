package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DisplayedTextCompositionTest {
    @Test void completeMultiSentenceTemplateCanPrecedeAnotherCompleteTemplate() {
        DisplayedTextEnglish text=DisplayedTextEnglish.fromResources(Map.of("a","这是武器。造成%d伤害。","b","增加%d伤害。"),
                Map.of("a","A weapon. Deals %d damage.","b","Adds %d damage."));
        assertEquals("A weapon. Deals 7 damage.  Adds 8 damage.",text.translate("这是武器。造成7伤害。  增加8伤害。"));
    }
    @Test void fullMultiSentenceResourceCanPrecedeAResourceTemplate() {
        DisplayedTextEnglish text=DisplayedTextEnglish.fromResources(Map.of("a","施加标记。目标暂时不死。","b","消耗%d充能。"),
                Map.of("a","Applies a mark. The target cannot die yet.","b","Costs %d charge."));
        assertEquals("Applies a mark. The target cannot die yet. Costs 15 charge.",text.translate("施加标记。目标暂时不死。 消耗15充能。"));
    }
    @Test void unknownTailOrPartialFirstUnitCannotBeSilentlyDropped() {
        DisplayedTextEnglish text=DisplayedTextEnglish.fromResources(Map.of("a","这是武器。造成%d伤害。","b","增加%d伤害。"),
                Map.of("a","A weapon. Deals %d damage.","b","Adds %d damage."));
        for(String input:new String[]{"这是武器。造成7伤害。 增加8伤害。未知尾文","这是武器。 增加8伤害。"}) {
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translate(input));
            assertEquals("Partially displayed text",text.translateVisible(input,true).text);
        }
    }
    @Test void sameVisibleIncompleteFirstUnitCannotRevealDifferentResourceTails() {
        DisplayedTextEnglish first=DisplayedTextEnglish.fromResources(Map.of("a","这是武器。隐藏甲。","b","完成。"),Map.of("a","A weapon. Secret A.","b","Done."));
        DisplayedTextEnglish second=DisplayedTextEnglish.fromResources(Map.of("a","这是武器。隐藏乙。","b","完成。"),Map.of("a","A weapon. Secret B.","b","Done."));
        assertEquals("Partially displayed text",first.translateVisible("这是武器。 完成。",true).text);
        assertEquals(first.translateVisible("这是武器。 完成。",true).text,second.translateVisible("这是武器。 完成。",true).text);
    }
    @Test void ambiguousCompleteUnitCannotBecomeAChoiceThroughConcatenation() {
        DisplayedTextEnglish text=DisplayedTextEnglish.fromResources(Map.of("a","标记。","b","标记。","c","消耗%d充能。"),
                Map.of("a","Mark.","b","Sign.","c","Costs %d charge."));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translate("标记。 消耗3充能。"));
    }
    @Test void actualSpiritBowStatsAndExcessStrengthAreTranslatedWithoutGameGetters() {
        String displayed="这把弓不能直接升级，但是会随着你等级提升而逐渐增强。以你目前的等级，这把弓射出的每支箭可以造成_7~18点伤害_并且需要_7点力量_来正常使用。 你的额外力量会使你在使用这件武器时造成_0~93点额外伤害_。";
        String english=new DisplayedTextEnglish().translate(displayed);
        assertTrue(english.contains("7-18"));assertTrue(english.contains("0-93"));
    }
    @Test void actualClassArmorAbilityAndChargeAreTranslatedWithoutHiddenAbilityObjects() {
        String displayed="战士大力锤击地面，向一个锥形区域施以_震地冲击_。被冲击波击中的敌人会受到一定伤害并陷入残废。 现在使用该能力将消耗_21_的充能。";
        String english=new DisplayedTextEnglish().translate(displayed);
        assertTrue(english.contains("shockwave"));assertTrue(english.contains("_21_"));
    }
}
