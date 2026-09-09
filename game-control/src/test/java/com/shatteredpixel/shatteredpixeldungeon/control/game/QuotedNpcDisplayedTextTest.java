package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class QuotedNpcDisplayedTextTest {
    @Test void actualGhostGreetingPreservesTheDisplayedSpeakerQuotesAndTrailingSpace() {
        assertEquals("sad ghost: \"Hello again warrior.\" ",new DisplayedTextEnglish().translate("悲伤幽灵: \"再次向你问好，战士。\" "));
    }
    @Test void actualWandmakerFarewellTranslatesOnlyItsDisplayedClassArgument() {
        assertEquals("old wandmaker: \"Good luck in your quest, warrior!\" ",new DisplayedTextEnglish().translate("老杖匠: \"祝你在试炼中好运，战士！\" "));
    }
    @Test void twoCompleteDisplayedGreetingsCanBeConcatenatedWithoutLosingEitherSpeaker() {
        String first="悲伤幽灵: \"再次向你问好，战士。\" ",second="老杖匠: \"祝你在试炼中好运，战士！\" ";
        DisplayedTextEnglish text=new DisplayedTextEnglish();
        assertEquals(text.translate(first)+text.translate(second),text.translate(first+second));
    }
    @Test void unknownSpeakerUnknownSpeechOrUnknownTailCannotBeDropped() {
        DisplayedTextEnglish text=new DisplayedTextEnglish();
        for(String shown:new String[]{"隐藏的陌生人: \"再次向你问好，战士。\" ","悲伤幽灵: \"未知的对白。\" ","悲伤幽灵: \"再次向你问好，战士。\" 未知尾文"})
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translate(shown));
    }
    @Test void sameClippedSpeechPrefixCannotRevealDifferentUnseenDialogueTails() {
        String shown="幽灵: \"共同开头，";
        DisplayedTextEnglish a=DisplayedTextEnglish.fromResources(Map.of("speaker","幽灵","body","共同开头，隐藏甲。"),Map.of("speaker","ghost","body","Shared start, unseen A."));
        DisplayedTextEnglish b=DisplayedTextEnglish.fromResources(Map.of("speaker","幽灵","body","共同开头，隐藏乙。"),Map.of("speaker","ghost","body","Shared start, unseen B."));
        assertEquals("Partially displayed text",a.translateVisible(shown,true).text);
        assertEquals(a.translateVisible(shown,true).text,b.translateVisible(shown,true).text);
    }
    @Test void unknownSpeakerCannotBeInferredFromAPreviousKnownGreeting() {
        DisplayedTextEnglish text=new DisplayedTextEnglish();text.translate("悲伤幽灵: \"再次向你问好，战士。\" ");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translate("另一个名字: \"再次向你问好，战士。\" "));
    }
}
