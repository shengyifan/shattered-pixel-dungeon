package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class HeroParagraphEnglishTest {
    @Test void everyActualHeroInfoParagraphTranslatesOnlyItsDisplayedUnit() throws Exception {
        Properties zh=new Properties(),en=new Properties();ClassLoader loader=getClass().getClassLoader();
        try(InputStreamReader r=new InputStreamReader(loader.getResourceAsStream("messages/actors/actors_zh.properties"),StandardCharsets.UTF_8)){zh.load(r);}
        try(InputStreamReader r=new InputStreamReader(loader.getResourceAsStream("messages/actors/actors.properties"),StandardCharsets.UTF_8)){en.load(r);}
        DisplayedTextEnglish t=new DisplayedTextEnglish();int count=0;
        for(String hero:new String[]{"warrior","mage","rogue","huntress","duelist","cleric"}) {
            String key="actors.hero.heroclass."+hero+"_desc";
            String[] sources=zh.getProperty(key).split("\n\n"),targets=en.getProperty(key).split("\n\n");
            for(int i=0;i<sources.length;i++) {assertEquals(targets[i],t.translate(sources[i]));count++;}
        }
        assertEquals(22,count);assertEquals(count,t.statistics().get("hero_description_paragraphs"));
    }
    @Test void unknownSuffixOrPrefixNeverReconstructsTheRemainder() {
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(
                Map.of("actors.hero.heroclass.warrior_desc","独有第一段内容。\n\n第二段隐藏内容。"),
                Map.of("actors.hero.heroclass.warrior_desc","First displayed paragraph.\n\nSecond unseen paragraph."));
        assertEquals("First displayed paragraph.",t.translate("独有第一段内容。"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("独有第一段内容。未知尾文"));
        assertEquals("Partially displayed text",t.translateVisible("独有第一段",true).text);
    }
    @Test void MismatchedCountsArgumentsOrUnregisteredResourcesDoNotCreateParagraphAliases() {
        for(String[] pair:new String[][]{
                {"actors.hero.heroclass.warrior_desc","独有首段。\n\n后段。","Only one paragraph."},
                {"actors.hero.heroclass.mage_desc","独有首段。\n\n数字%d。","First paragraph.\n\nNumber %d."},
                {"items.example.description","独有首段。\n\n后段。","First paragraph.\n\nLast paragraph."}}) {
            DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(Map.of(pair[0],pair[1]),Map.of(pair[0],pair[2]));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("独有首段。"));
        }
    }
    @Test void IdenticalChineseWithDifferentEnglishRetainsBothCandidates() {
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(
                Map.of("actors.hero.heroclass.warrior_desc","重叠文字。\n\n战士第二段。","actors.hero.heroclass.mage_desc","重叠文字。\n\n法师第二段。"),
                Map.of("actors.hero.heroclass.warrior_desc","First warrior paragraph.\n\nSecond warrior paragraph.","actors.hero.heroclass.mage_desc","First mage paragraph.\n\nSecond mage paragraph."));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("重叠文字。"));
    }
}
