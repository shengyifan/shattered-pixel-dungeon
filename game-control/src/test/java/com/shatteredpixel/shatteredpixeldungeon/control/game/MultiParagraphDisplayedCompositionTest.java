package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MultiParagraphDisplayedCompositionTest {
    @Test void nativeShockwaveDetailsKeepTheWholeTwoParagraphResourceBeforeItsDisplayedCost() {
        String displayed="战士大力锤击地面产生冲击波，震击前方60度扇形范围5格距离内的区域。\n\n被冲击波击中的敌人会陷入5回合残废并受到5~10点伤害。战士每点超过10的力量会使得该伤害增加1~2点。\n\n充能消耗：_35_";
        String english=new DisplayedTextEnglish().translate(displayed);
        assertTrue(english.startsWith("The Warrior slams the ground"));
        assertTrue(english.contains("5-10 damage"));assertTrue(english.endsWith("_35_"));
    }
    @Test void completeMultilineUnitAndNextTemplatePreserveTheProvidedSeparatorExactly() {
        DisplayedTextEnglish text=DisplayedTextEnglish.fromResources(Map.of("a","第一段。\n\n第二段。","b","消耗%d。"),
                Map.of("a","First paragraph.\n\nSecond paragraph.","b","Costs %d."));
        for(String delimiter:new String[]{"\n\n","\n","\r\n","\t"})
            assertEquals("First paragraph.\n\nSecond paragraph."+delimiter+"Costs 35.",text.translate("第一段。\n\n第二段。"+delimiter+"消耗35。"));
    }
    @Test void aVisiblePartialParagraphCannotBeCompletedFromEitherUnseenResourceTail() {
        DisplayedTextEnglish a=DisplayedTextEnglish.fromResources(Map.of("a","同一首段。\n\n隐藏甲。","b","消耗%d。"),Map.of("a","Shared first.\n\nHidden A.","b","Costs %d."));
        DisplayedTextEnglish b=DisplayedTextEnglish.fromResources(Map.of("a","同一首段。\n\n隐藏乙。","b","消耗%d。"),Map.of("a","Shared first.\n\nHidden B.","b","Costs %d."));
        String shown="同一首段。\n\n消耗35。";
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->a.translate(shown));
        assertEquals("Partially displayed text",a.translateVisible(shown,true).text);
        assertEquals(a.translateVisible(shown,true).text,b.translateVisible(shown,true).text);
    }
}
