package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DisplayedTextEnglishTest {
    private static DisplayedTextEnglish translator(String... pairs) {
        Map<String,String> chinese=new LinkedHashMap<>(),english=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2){chinese.put("key"+i,pairs[i]);english.put("key"+i,pairs[i+1]);}
        return DisplayedTextEnglish.fromResources(chinese,english);
    }

    @Test void fullResourcesAndAlreadyEnglishTextArePreservedWithoutGameState() {
        DisplayedTextEnglish t=translator("战士","warrior","魔弹法杖","wand of magic missile");
        assertEquals("warrior",t.translate("战士"));
        String english="The visible wand has 3 charges.";
        assertSame(english,t.translate(english));assertNull(t.translate(null));assertEquals("",t.translate(""));
    }

    @Test void templatesTranslateOnlyCapturedVisibleArgumentsRecursively() {
        DisplayedTextEnglish t=translator("战士","warrior","魔弹法杖","wand of magic missile",
                "你发现了%s。","You found %s.","%s手中的%s","%s held by %s");
        assertEquals("You found wand of magic missile.",t.translate("你发现了魔弹法杖。"));
        assertEquals("You found warrior held by wand of magic missile.",t.translate("你发现了战士手中的魔弹法杖。"));
    }

    @Test void explicitArgumentOrderAndDisplayedNumericPrecisionAreRetained() {
        DisplayedTextEnglish t=translator("战士","warrior",
                "%2$s受到%1$d点伤害。","Dealt %1$d damage to %2$s.",
                "效果：%1$.2f%%，数量：%2$,d","Effect: %1$.2f%%; count: %2$,d");
        assertEquals("Dealt 17 damage to warrior.",t.translate("战士受到17点伤害。"));
        assertEquals("Effect: 1.20%; count: 1,234",t.translate("效果：1.20%，数量：1,234"));
    }

    @Test void sharedSlotMustMatchAndMissingTargetSlotsCannotInventArguments() {
        DisplayedTextEnglish t=translator("%1$d对%1$d","%1$d versus %1$d","值为%d","Value %1$d; hidden %2$d");
        assertEquals("3 versus 3",t.translate("3对3"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("3对4"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("值为3"));
        assertEquals(1,t.statistics().get("unsupported_template_pairs"));
    }

    @Test void paragraphsTitlesQuantitiesAndAdjacentNamesUseCompleteResourceUnits() {
        DisplayedTextEnglish t=translator("战士","warrior","魔弹法杖","wand of magic missile","烈焰","blazing");
        assertEquals("warrior +1 (2)\nwand of magic missile",t.translate("战士 +1 (2)\n魔弹法杖"));
        assertEquals("blazing wand of magic missile x3",t.translate("烈焰魔弹法杖x3"));
        assertEquals("_warrior_",t.translate("_战士_"));
    }

    @Test void concatenatedCompleteSentencesPreserveWhitespaceAndCannotDropAnUnknownTail() {
        DisplayedTextEnglish t=translator("造成%d点伤害。","Deals %d damage.","需要%d力量。","Requires %d strength.");
        assertEquals("  Deals 3 damage. Requires 9 strength.  ",t.translate("  造成3点伤害。 需要9力量。  "));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("造成3点伤害。 未知的后半句"));
        DisplayedTextEnglish.VisibleText clipped=t.translateVisible("造成3点伤害。 未知的后半句",true);
        assertEquals("Partially displayed text",clipped.text);assertTrue(clipped.partial);
    }

    @Test void aTemplatesOwnLeadingNewlinesAreMatchedBeforeWhitespaceComposition() {
        DisplayedTextEnglish t=translator("\n\n携带_%s_。","\n\nCarrying _%s_.","战士","warrior");
        assertEquals("\n\nCarrying _warrior_.",t.translate("\n\n携带_战士_。"));
    }

    @Test void symmetricGameHighlightWrappersTranslateOnlyTheirCompleteDisplayedInnerText() {
        DisplayedTextEnglish t=translator("第%d层","Floor %d","战士","warrior");
        assertEquals("_Floor 1_",t.translate("_第1层_"));
        assertEquals("**Floor 27**",t.translate("**第27层**"));
        assertEquals("**warrior**",t.translate("**战士**"));
        assertEquals("_Floor 9_",t.translate("_第9层_"),"The number comes from the provided text, not any current level");
    }

    @Test void incompleteWrappersDoNotInventClosingMarkersAndUnknownInnerOrTailTextIsRejected() {
        DisplayedTextEnglish t=translator("第%d层","Floor %d","战士","warrior");
        for(String text:java.util.Arrays.asList("_第1层","**第1层*","_第1层未知尾文_","_第1层_未知尾文")) {
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate(text));
            assertEquals("Partially displayed text",t.translateVisible(text,true).text);
        }
        assertEquals("_warrior",t.translate("_战士"),"Existing complete word composition preserves an open marker without fabricating its close");
    }

    @Test void identicalVisibleHighlightedHeadersCannotRevealDifferentUnseenParagraphs() {
        DisplayedTextEnglish first=translator("第%d层","Floor %d","_第7层_后面是安全房间","_Floor 7_ leads to a safe room");
        DisplayedTextEnglish second=translator("第%d层","Floor %d","_第7层_后面是致命陷阱","_Floor 7_ leads to a lethal trap");
        assertEquals("_Floor 7_",first.translateVisible("_第7层_",true).text);
        assertEquals(first.translateVisible("_第7层_",true).text,second.translateVisible("_第7层_",true).text);
        assertEquals("Partially displayed text",first.translateVisible("_第7",true).text);
        assertEquals(first.translateVisible("_第7",true).text,second.translateVisible("_第7",true).text);
    }

    @Test void realDisplayedThrowingStoneDescriptionUsesOnlyItsSuppliedKnownValues() throws Exception {
        String displayed="这些石头被人用砂纸打磨成趁手的形状，比普通石头更适合大力投向目标。\n\n"
                +"这组_1阶_的投掷武器能造成_2~5点伤害_并且需要_9点力量_来正常使用。 你的额外力量会使你在使用这件武器时造成_0~1点额外伤害_。\n\n"
                +"远程使用投掷武器更为精准，而近距离使用则反之。\n\n这组投掷武器再使用_5/5_次就会损坏一件。";
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of("../core/src/main/assets").toAbsolutePath().toUri().toURL()},null)) {
            DisplayedTextEnglish t=DisplayedTextEnglish.fromClassLoader(loader);
            Properties english=read(loader,"messages/items/items.properties");
            String expected=english.getProperty("items.weapon.missiles.throwingstone.desc")+"\n\n"
                    +String.format(Locale.ENGLISH,english.getProperty("items.weapon.missiles.missileweapon.stats_known"),1,2,5,9)+" "
                    +String.format(Locale.ENGLISH,english.getProperty("items.weapon.weapon.excess_str"),1)+"\n\n"
                    +english.getProperty("items.weapon.missiles.missileweapon.distance")+"\n\n"
                    +String.format(Locale.ENGLISH,english.getProperty("items.weapon.missiles.missileweapon.uses_left"),5,5);
            assertEquals(expected,t.translateInScene(displayed,"GameScene"));
            assertTrue(t.translateInScene(displayed.replace("5/5","4/5"),"GameScene").contains("4/5"));
        }
    }

    @Test void completeResourceUnitsMayBeginWithParenthesesOrMarkupButPartialUnitsAreNotExpanded() {
        DisplayedTextEnglish t=translator("介绍。","Introduction.","(仅提供英文内容)","(Available only in English.)",
                "_-功能：_描述。","_-Feature:_ Description.");
        assertEquals("Introduction.\n(Available only in English.)\n- Evan",t.translate("介绍。\n(仅提供英文内容)\n- Evan"));
        assertEquals("Introduction. _-Feature:_ Description.",t.translate("介绍。 _-功能：_描述。"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("介绍。\n(仅提供英文"));
        assertEquals("Partially displayed text",t.translateVisible("介绍。\n(仅提供英文",true).text);
    }

    @Test void actualSupporterParagraphCompositionKeepsAllDisplayedResourceUnitsAndSignature() throws Exception {
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of("../core/src/main/assets").toAbsolutePath().toUri().toURL()},null)) {
            DisplayedTextEnglish t=DisplayedTextEnglish.fromClassLoader(loader);
            Properties zh=read(loader,"messages/scenes/scenes_zh.properties"),en=read(loader,"messages/scenes/scenes.properties");
            String displayed=zh.getProperty("scenes.supporterscene.intro")+"\n\n"+zh.getProperty("scenes.supporterscene.patreon_msg")
                    +"\n"+zh.getProperty("scenes.supporterscene.patreon_english")+"\n\n- Evan";
            String expected=en.getProperty("scenes.supporterscene.intro")+"\n\n"+en.getProperty("scenes.supporterscene.patreon_msg")
                    +"\n"+en.getProperty("scenes.supporterscene.patreon_english")+"\n\n- Evan";
            assertEquals(expected,t.translateInScene(displayed,"SupporterScene"));
        }
    }

    @Test void literalPercentProseIsNotMistakenForAPrintfConversion() {
        DisplayedTextEnglish t=translator("额外25%伤害。","25% more damage.");
        assertEquals("25% more damage.",t.translate("额外25%伤害。"));
        assertEquals(0,t.statistics().get("source_template_pairs"));
    }

    @Test void ambiguousChineseNeverUsesLatestObjectOrInsertionOrder() {
        DisplayedTextEnglish first=translator("关闭","Off","关闭","Close");
        DisplayedTextEnglish reverse=translator("关闭","Close","关闭","Off");
        for(DisplayedTextEnglish t:new DisplayedTextEnglish[]{first,reverse}) {
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("关闭"));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("关闭 (2)"));
        }
        assertEquals("Cancel",translator("取消","Cancel","取消","cancel").translate("取消"));
    }

    @Test void oneOptionalTerminalPeriodNormalizesEquivalentResourceCandidatesOnly() {
        DisplayedTextEnglish t=translator("位置提示","Choose a location to zap.","位置提示","Choose a location to zap");
        DisplayedTextEnglish reverse=translator("位置提示","Choose a location to zap","位置提示","Choose a location to zap.");
        assertEquals("Choose a location to zap",t.translate("位置提示"));
        assertEquals(t.translate("位置提示"),reverse.translate("位置提示"));
        assertEquals("Choose a location to zap.",t.translate("Choose a location to zap."),"Already-English input keeps its own punctuation");
        assertEquals("Only one choice.",translator("唯一提示","Only one choice.").translate("唯一提示"));
        DisplayedTextEnglish scoped=DisplayedTextEnglish.fromResources(
                Map.of("scenes.gamescene.prompt","位置提示","scenes.titlescene.prompt","位置提示"),
                Map.of("scenes.gamescene.prompt","Choose a location to zap.","scenes.titlescene.prompt","Choose a location to zap"));
        assertEquals("Choose a location to zap",scoped.translateInContext("位置提示",Map.of("scene","GameScene","hidden_object","wand")));
        assertEquals("Choose a location to zap",scoped.translateInContext("位置提示",Map.of("scene","TitleScene","hidden_object","staff")));
    }

    @Test void optionalPeriodDoesNotCollapseEllipsesQuestionsOtherPunctuationOrTrailingWhitespace() {
        String[][] pairs={{"Choose...","Choose"},{"Choose..","Choose."},{"Choose…","Choose"},
                {"Choose?","Choose"},{"Choose!","Choose"},{"Choose?.","Choose?"},{"Choose,.","Choose,"},
                {"Choose. ","Choose"}};
        for(String[] pair:pairs)assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->translator("提示",pair[0],"提示",pair[1]).translate("提示"),java.util.Arrays.toString(pair));
    }

    @Test void conservativeFocusWordDoesNotInferAbilityOrBuffAcrossPublicScenesOrHiddenObjects() {
        Map<String,String> zh=Map.of("actors.someability.name","凝神","actors.somebuff.name","凝神",
                "scenes.gamescene.label","凝神","scenes.titlescene.label","凝神","status","状态：%s");
        Map<String,String> en=Map.of("actors.someability.name","focus","actors.somebuff.name","focused",
                "scenes.gamescene.label","focused","scenes.titlescene.label","focus","status","State: %s");
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(zh,en);
        Map<String,Object> first=Map.of("scene","GameScene","role","button","hidden_object","monk ability");
        Map<String,Object> second=Map.of("scene","TitleScene","role","entry","hidden_object","monster buff");
        assertEquals("Focus",t.translateInContext("凝神",first));
        assertEquals("Focus",t.translateInContext("凝神",second));
        assertEquals("Focus",t.translate("凝神"));
        assertEquals("State: Focus",t.translate("状态：凝神"));
        assertEquals("Focus (3)",t.translate("凝神 (3)"));
        assertEquals("focused",t.translate("focused"),"The existing pure English model getter is not renamed");
    }

    @Test void equivalentNormalizationsNeverSwallowAnUnknownSuffixOrExpandAClippedPrefix() {
        DisplayedTextEnglish t=translator("选择位置","Choose a location.","选择位置","Choose a location");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("凝神未知后文"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("选择位置未知后文"));
        assertEquals("Focus visible English suffix",t.translate("凝神 visible English suffix"));
        assertEquals("Partially displayed text",t.translateVisible("凝",true).text);
        assertEquals("Partially displayed text",t.translateVisible("选择位",true).text);
        assertEquals("Choose a location",t.translateVisible("选择位置",true).text);
    }

    @Test void bundledNormalizationKeepsTheOriginalAmbiguityCountAndNamesTheSupportedSubset() throws Exception {
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of("../core/src/main/assets").toAbsolutePath().toUri().toURL()},null)) {
            DisplayedTextEnglish t=DisplayedTextEnglish.fromClassLoader(loader);
            assertEquals("Choose a location to zap",t.translate("选择要释放魔法的位置"));
            assertEquals("I'll decide later",t.translate("我将稍后决定"));
            assertEquals("Focus",t.translate("凝神"));
            assertEquals(55,t.statistics().get("ambiguous_chinese_strings"));
            assertEquals(3,t.statistics().get("normalized_ambiguous_strings"));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("神圣战士"));
        }
    }

    @Test void matchingDifferentTemplatesMustAgreeOnTheEnglishResult() {
        DisplayedTextEnglish t=translator("值为%d","Value %d","值为%s","Amount %s");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("值为7"));
    }

    @Test void startButtonDisambiguationUsesOnlyTheSuppliedPublicSceneAndExactResource() {
        Map<String,String> zh=new TreeMap<>(),en=new TreeMap<>();
        zh.put("scenes.heroselectscene.start","开始");en.put("scenes.heroselectscene.start","Start");
        zh.put("scenes.titlescene.play","开始");en.put("scenes.titlescene.play","Play");
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(zh,en);
        assertEquals("Start",t.translateInScene("开始","HeroSelectScene"));
        assertEquals("Start",t.translateInScene("开始","hero_select"));
        assertEquals("Play",t.translateInScene("开始","TitleScene"));
        assertEquals("Play",t.translateInScene("开始","title"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("开始","GameScene"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("开始"));
        assertTrue(t.translateVisibleInScene("开始",true,"GameScene").partial);
        assertEquals("Start",t.translateVisibleInScene("开始",true,"HeroSelectScene").text);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("开始隐藏内容","HeroSelectScene"));
    }

    @Test void equalVisibleSceneAndTextIgnoreDifferentHiddenWorldObjectsAndCallHistory() {
        Map<String,String> zh=Map.of("scenes.heroselectscene.start","开始","scenes.titlescene.play","开始");
        Map<String,String> en=Map.of("scenes.heroselectscene.start","Start","scenes.titlescene.play","Play");
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(zh,en);
        class World {final Object hidden;final String scene="HeroSelectScene",text="开始";World(Object hidden){this.hidden=hidden;}}
        World first=new World(new Object()),second=new World(Collections.singletonMap("secret","a different item type"));
        String a=t.translateInScene(first.text,first.scene);
        assertEquals("Play",t.translateInScene("开始","TitleScene"));
        String b=t.translateInScene(second.text,second.scene);
        assertEquals("Start",a);assertEquals(a,b);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("开始"),"A previous scoped result cannot become a global latest mapping");
    }

    @Test void sceneScopeResolvesNewsButCannotSelectAnUnpublishedInternalClass() {
        Map<String,String> zh=Map.of("scenes.titlescene.news","游戏新闻","scenes.newsscene.title","游戏新闻",
                "scenes.secretmodel.title","游戏新闻");
        Map<String,String> en=Map.of("scenes.titlescene.news","News","scenes.newsscene.title","Game News",
                "scenes.secretmodel.title","Hidden news detail");
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(zh,en);
        assertEquals("News",t.translateInScene("游戏新闻","TitleScene"));
        assertEquals("News",t.translateInScene("游戏新闻","title"));
        assertEquals("Game News",t.translateInScene("游戏新闻","NewsScene"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("游戏新闻","SecretModel"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("游戏新闻","other"));
        assertTrue(t.translateVisibleInScene("游戏新闻",true,"other").partial);
    }

    @Test void guidebookHintUsesOnlyThePublicGameSceneAndDoesNotBorrowItemIdentityOrHiddenTails() {
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(
                Map.of("scenes.alchemyscene.guide","指南","items.journal.guidebook.hint_status","指南"),
                Map.of("scenes.alchemyscene.guide","Guide","items.journal.guidebook.hint_status","Guidebook"));
        for(String scene:java.util.Arrays.asList("GameScene","game")) {
            assertEquals("Guidebook",t.translateInScene("指南",scene));
            assertEquals("Guidebook",t.translateInContext("指南",Map.of("scene",scene,"role","text","hidden_item","guidebook")));
            assertEquals("Guidebook",t.translateInContext("指南",Map.of("scene",scene,"role","entry","hidden_item",new Object())));
        }
        assertEquals("Guide",t.translateInScene("指南","AlchemyScene"));
        assertEquals("Guide",t.translateInScene("指南","alchemy"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("指南"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("指南","other"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("指南",Map.of("hidden_scene","GameScene")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("指南隐藏尾文","GameScene"));
        assertEquals("Partially displayed text",t.translateVisibleInScene("指",true,"GameScene").text);
        assertEquals("Guide",t.translateInScene("Guide","GameScene"),"Already-English display text is unchanged");
        assertEquals("Guidebook",t.translateInScene("指南","GameScene"),"The intervening Alchemy translation is not a global latest mapping");
    }

    @Test void ambiguityInsideOnePublicSceneStillFailsAndScopeCannotMatchOnlyAPrefix() {
        Map<String,String> zh=Map.of("scenes.titlescene.a","同文","scenes.titlescene.b","同文");
        Map<String,String> en=Map.of("scenes.titlescene.a","A meaning","scenes.titlescene.b","A different meaning");
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(zh,en);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("同文","TitleScene"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("同文隐藏尾部","TitleScene"));
    }

    private static DisplayedTextEnglish contextTranslator() {
        Map<String,String> zh=new TreeMap<>(),en=new TreeMap<>();
        String[][] rows={{"windows.wndkeybindings.back","返回","Back"},{"other.return","返回","Return"},
                {"windows.wndsettings$displaytab.off","关闭","Off"},{"other.close","关闭","Close"},
                {"windows.wndgameinprogress.erase","删除","Erase"},{"other.delete","删除","Delete"},
                {"windows.wndgame.settings","设置","Settings"},{"other.set","设置","Set"},
                {"levels.features.chasm.no","不，我改主意了","No, I changed my mind"},{"other.no","不，我改主意了","Never mind"},
                {"other.shake","震屏","Screen Shake"},{"other.high","最高","High"}};
        for(String[] row:rows){zh.put(row[0],row[1]);en.put(row[0],row[2]);}
        return DisplayedTextEnglish.fromResources(zh,en);
    }

    @Test void backRequiresTheAlreadyPublicShortcutAndCannotUseHiddenWidgetTypes() {
        DisplayedTextEnglish t=contextTranslator();
        assertEquals("Back",t.translateInContext("返回",Map.of("scene","SupporterScene","shortcut_action","BACK")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("返回",Map.of("scene","SupporterScene","role","button")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("返回",Map.of("hidden_widget_class","ExitButton")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("返回隐藏尾部",Map.of("shortcut_action","back")));
    }

    @Test void offRequiresPublicSliderOrCheckboxEvidenceIncludingWholeMultilineSliderText() {
        DisplayedTextEnglish t=contextTranslator();
        assertEquals("Off",t.translateInContext("关闭",Map.of("slider",true)));
        assertEquals("Off",t.translateInContext("关闭",Map.of("checkbox",true)));
        assertEquals("Screen Shake\nOff\nHigh",t.translateInContext("震屏\n关闭\n最高",Map.of("scene","GameScene","slider",true)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("关闭",Map.of("slider",false,"checkbox",false)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("关闭",Map.of("slider","true")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("关闭隐藏设置",Map.of("slider",true)));
    }

    @Test void eraseRequiresStartSceneAndThePublicSaveDetailsSignatureFlag() {
        DisplayedTextEnglish t=contextTranslator();
        assertEquals("Erase",t.translateInContext("删除",Map.of("scene","StartScene","save_details",true)));
        assertEquals("Erase",t.translateInContext("删除",Map.of("scene","start","save_details",true)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("删除",Map.of("scene","StartScene","save_details",false)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("删除",Map.of("scene","GameScene","save_details",true)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("删除",Map.of("save_details",true)));
    }

    @Test void gameMenuAndChasmNeedTheirOwnPublicSignatureFlagsAndGameScene() {
        DisplayedTextEnglish t=contextTranslator();
        assertEquals("Settings",t.translateInContext("设置",Map.of("scene","GameScene","game_menu",true)));
        assertEquals("No, I changed my mind",t.translateInContext("不，我改主意了",Map.of("scene","game","chasm_prompt",true)));
        for(String flag:new String[]{"game_menu","chasm_prompt"}) {
            String text=flag.equals("game_menu")?"设置":"不，我改主意了";
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext(text,Map.of("scene","GameScene",flag,false)));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext(text,Map.of("scene","StartScene",flag,true)));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext(text,Map.of(flag,true)));
        }
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("不，我改主意了隐藏尾文",Map.of("scene","GameScene","chasm_prompt",true)));
    }

    @Test void journalHeadingsRequirePublicJournalSceneAndExactDisplayedCountSyntax() throws Exception {
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of("../core/src/main/assets").toAbsolutePath().toUri().toURL()},null)) {
            DisplayedTextEnglish t=DisplayedTextEnglish.fromClassLoader(loader);Map<String,Object> context=Map.of("scene","JournalScene");
            assertEquals("_Equipment_ (0/165)",t.translateInContext("_装备_ (0/165)",context));
            assertEquals("_thrown weapons_ (0/16):",t.translateInContext("_投掷武器_ (0/16):",context));
            assertEquals("_wands_ (0/13):",t.translateInContext("_法杖_ (0/13):",context));
            assertEquals("_trinkets_ (0/17):",t.translateInContext("_饰物_ (0/17):",context));
            assertEquals("_Equipment_ (12/165)",t.translateInContext("_装备_ (12/165)",context));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("_装备_ (0/165)",Map.of("scene","GameScene")));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("_装备_ (?/165)",context));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("_装备_ (0/165)隐藏尾文",context));
        }
    }

    @Test void identicalPublicContextIsIndependentOfHiddenExtrasAndClippedIncompleteTextStaysPartial() {
        DisplayedTextEnglish t=contextTranslator();
        Map<String,Object> first=new LinkedHashMap<>(Map.of("scene","StartScene","save_details",true));
        Map<String,Object> second=new LinkedHashMap<>(first);first.put("hidden_save",new Object());second.put("hidden_save","a different run");
        assertEquals(t.translateInContext("删除",first),t.translateInContext("删除",second));
        assertEquals("Erase",t.translateVisibleInContext("删除",true,first).text);
        DisplayedTextEnglish.VisibleText incomplete=t.translateVisibleInContext("删",true,first);
        assertEquals("Partially displayed text",incomplete.text);assertTrue(incomplete.partial);
        assertEquals("Partially displayed text",t.translateVisibleInContext("关闭",true,Collections.emptyMap()).text);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("删除",Collections.emptyMap()));
    }

    @Test void keyBindingContextUsesItsCompleteResourceDomainForRowsAndMultilineText() {
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(
                Map.of("windows.wndkeybindings.quickslot_selector","选择快捷栏","ui.toolbar.quickslot_select","选择快捷栏"),
                Map.of("windows.wndkeybindings.quickslot_selector","Quickslot Selector","ui.toolbar.quickslot_select","Select Quickslot"));
        assertEquals("Quickslot Selector",t.translateInContext("选择快捷栏",Map.of("key_binding",true)));
        assertEquals("Quickslot Selector\nNone\nNone\nNone",t.translateInContext("选择快捷栏\nNone\nNone\nNone",Map.of("key_binding",true)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("选择快捷栏",Map.of("key_binding",false)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("选择快捷栏",Map.of("key_binding","true","hidden_class","BindingRow")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("选择快捷栏"));
        assertEquals("Select Quickslot",t.translateInContext("Select Quickslot",Map.of("key_binding",true)));
    }

    @Test void keyBindingContextDoesNotExpandHiddenClassDomainsOrUnknownSuffixes() {
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(
                Map.of("windows.wndkeybindings.quickslot_selector","选择快捷栏","ui.toolbar.quickslot_select","选择快捷栏",
                        "windows.wndkeybindings$hidden.prompt","内部文案","other.prompt","内部文案"),
                Map.of("windows.wndkeybindings.quickslot_selector","Quickslot Selector","ui.toolbar.quickslot_select","Select Quickslot",
                        "windows.wndkeybindings$hidden.prompt","Hidden binding meaning","other.prompt","Other meaning"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("选择快捷栏未知尾文",Map.of("key_binding",true)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("内部文案",Map.of("key_binding",true)));
        assertEquals("Partially displayed text",t.translateVisibleInContext("选择快",true,Map.of("key_binding",true)).text);
    }

    @Test void keyBindingPanelConfirmationRequiresTheExplicitPublicSignatureFlag() {
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(
                Map.of("windows.wndkeybindings.confirm","确定","other.confirm","确定"),
                Map.of("windows.wndkeybindings.confirm","Confirm","other.confirm","Okay"));
        assertEquals("Confirm",t.translateInContext("确定",Map.of("key_binding_panel",true)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("确定",Map.of("key_binding_panel",false)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("确定",Map.of("key_binding_panel","true","hidden_window","WndKeyBindings")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("确定未知尾文",Map.of("key_binding_panel",true)));
        assertEquals("Partially displayed text",t.translateVisibleInContext("确",true,Map.of("key_binding_panel",true)).text);
    }

    @Test void customNoteFlagsResolveOnlyCompleteNoteResourceTextInThePublicGameScene() {
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(
                Map.of("ui.customnotebutton$customnotewindow.confirm","确定","other.confirm","确定",
                        "ui.customnotebutton$customnotewindow.delete","删除","other.delete","删除"),
                Map.of("ui.customnotebutton$customnotewindow.confirm","Confirm","other.confirm","Okay",
                        "ui.customnotebutton$customnotewindow.delete","Delete","other.delete","Erase"));
        for(String flag:java.util.Arrays.asList("custom_note_input","custom_note_view","custom_note_delete")) {
            assertEquals("Confirm",t.translateInContext("确定",Map.of("scene","GameScene",flag,true)));
            assertEquals("Delete",t.translateInContext("删除",Map.of("scene","game",flag,true)));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("确定",Map.of("scene","GameScene",flag,false)));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("确定",Map.of("scene","StartScene",flag,true)));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("确定",Map.of(flag,true)));
        }
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("删除未知尾文",Map.of("scene","GameScene","custom_note_view",true)));
        assertEquals("Partially displayed text",t.translateVisibleInContext("确",true,Map.of("scene","GameScene","custom_note_input",true)).text);
    }

    private static DisplayedTextEnglish bindingInputTranslator() {
        return DisplayedTextEnglish.fromResources(
                Map.of("windows.wndkeybindings.none","无按键","windows.wndkeybindings.wait","等待",
                        "windows.wndkeybindings.quickslot_selector","选择快捷栏","ui.toolbar.quickslot_select","选择快捷栏",
                        "windows.wndkeybindings$wndchangebinding.unbind","无按键",
                        "windows.wndkeybindings$wndchangebinding.desc_current","当前键位：_%s_",
                        "windows.wndkeybindings$wndchangebinding.desc_first","按下一个按键以替代_%s_的第一键位。",
                        "other.unrelated","额外语义"),
                Map.of("windows.wndkeybindings.none","None","windows.wndkeybindings.wait","Wait",
                        "windows.wndkeybindings.quickslot_selector","Quickslot Selector","ui.toolbar.quickslot_select","Select Quickslot",
                        "windows.wndkeybindings$wndchangebinding.unbind","Unbind Key",
                        "windows.wndkeybindings$wndchangebinding.desc_current","Current binding: _%s_",
                        "windows.wndkeybindings$wndchangebinding.desc_first","Press a key to change the first key binding for: _%s_.",
                        "other.unrelated","An unrelated global meaning"));
    }

    @Test void bindingInputButtonAndCompleteTemplateUseDifferentExplicitResourceDomains() {
        DisplayedTextEnglish t=bindingInputTranslator();
        Map<String,Object> input=Map.of("key_binding_input",true);
        assertEquals("Unbind Key",t.translateInContext("无按键",Map.of("key_binding_input",true,"button",true)));
        assertEquals("Current binding: _None_",t.translateInContext("当前键位：_无按键_",input));
        assertEquals("Press a key to change the first key binding for: _Quickslot Selector_.",
                t.translateInContext("按下一个按键以替代_选择快捷栏_的第一键位。",input));
        assertEquals("Press a key to change the first key binding for: _Wait_.\nCurrent binding: _None_",
                t.translateInContext("按下一个按键以替代_等待_的第一键位。\n当前键位：_无按键_",input));
        assertEquals("Current binding: _Space_",t.translateInContext("当前键位：_Space_",input));
    }

    @Test void bindingInputCannotGuessIsolatedWordsOrUseUnknownGlobalParameterMeanings() {
        DisplayedTextEnglish t=bindingInputTranslator();
        for(Map<String,Object> context:java.util.Arrays.<Map<String,Object>>asList(Map.of("key_binding_input",false,"button",true),
                Map.of("key_binding_input",true,"button",false),Map.of("key_binding_input",true,"key_binding_panel",true),
                Map.of("hidden_class","WndChangeBinding")))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("无按键",context));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("当前键位：_额外语义_",Map.of("key_binding_input",true)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInContext("当前键位：_无按键_未知尾文",Map.of("key_binding_input",true)));
        assertEquals("Partially displayed text",t.translateVisibleInContext("当前键位：_无",true,Map.of("key_binding_input",true)).text);
        String first=t.translateInContext("当前键位：_无按键_",Map.of("scene","GameScene","key_binding_input",true,"hidden_capture","object A"));
        String second=t.translateInContext("当前键位：_无按键_",Map.of("scene","GameScene","key_binding_input",true,"hidden_capture",new Object()));
        assertEquals(first,second);
    }

    @Test void unknownChineseHasStableEnglishErrorAndOriginalOnlyInDiagnosticAccessor() {
        DisplayedTextEnglish t=translator("战士","warrior");
        DisplayedTextEnglish.PublicTextUnavailableException error=assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,
                ()->t.translate("未收录的玩家原文"));
        assertEquals("PUBLIC_TEXT_UNAVAILABLE",error.code);
        assertEquals("Displayed text has no unambiguous English translation.",error.getMessage());
        assertEquals("未收录的玩家原文",error.diagnosticOriginalText());
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("战士未收录的后缀"));
    }

    @Test void clippedTwoWorldsWithIdenticalVisiblePrefixCannotRevealDifferentHiddenTails() {
        DisplayedTextEnglish safe=translator("可见的门后面是安全房间。","The visible door leads to a safe room.");
        DisplayedTextEnglish trap=translator("可见的门后面是致命陷阱。","The visible door leads to a deadly trap.");
        for(DisplayedTextEnglish t:new DisplayedTextEnglish[]{safe,trap}) {
            DisplayedTextEnglish.VisibleText result=t.translateVisible("可见的门",true);
            assertEquals("Partially displayed text",result.text);assertTrue(result.partial);
        }
    }

    @Test void independentlyCompleteVisibleFragmentCanTranslateWithoutAnyHiddenTail() {
        DisplayedTextEnglish t=translator("战士","warrior","战士发现了隐藏房间。","The warrior found a hidden room.");
        DisplayedTextEnglish.VisibleText result=t.translateVisible("战士",true);
        assertEquals("warrior",result.text);assertFalse(result.partial);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateVisible("战士发现",false));
    }

    @Test void allPublicLanguageNamesTranslateIncludingNonChineseScriptsAndLatinNames() {
        DisplayedTextEnglish t=translator();
        for(Languages language:Languages.values()) {
            String expected=language==Languages.CHI_SMPL?"Simplified Chinese":language==Languages.CHI_TRAD?"Traditional Chinese":
                    language.name().charAt(0)+language.name().substring(1).toLowerCase(Locale.ROOT).replace('_',' ');
            assertEquals(expected,t.translate(language.nativeName()));
            assertEquals(expected,t.translate(language.nativeName().toUpperCase(Locale.ROOT)));
        }
        assertEquals("French",t.translate("Français"));assertEquals("Russian",t.translate("Русский"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("Непереведённый текст"));
    }

    @Test void indicesAreImmutableAndQueriesDoNotAccumulateLatestTextMappings() {
        Map<String,String> zh=new LinkedHashMap<>(),en=new LinkedHashMap<>();zh.put("name","战士");en.put("name","warrior");
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(zh,en);zh.put("name","未知");en.put("name","unknown");
        Map<String,Integer> before=new LinkedHashMap<>(t.statistics());
        for(int i=0;i<20;i++)assertEquals("warrior",t.translate("战士"));
        assertEquals(before,t.statistics());assertThrows(UnsupportedOperationException.class,()->t.statistics().clear());
    }

    @Test void boundedCompositionFailsWithStableCodeInsteadOfOverflowingTheStack() {
        DisplayedTextEnglish t=translator("战士","warrior");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate("战士".repeat(500)));
    }

    @Test void actualBundledDictionaryReportsAmbiguityAndResolvesAllUnambiguousCompleteResources() throws Exception {
        Path assets=Path.of("../core/src/main/assets").toAbsolutePath();
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{assets.toUri().toURL()},null)) {
            DisplayedTextEnglish t=DisplayedTextEnglish.fromClassLoader(loader);
            assertTrue(t.statistics().get("resource_pairs")>4800);
            assertEquals(584,t.statistics().get("source_template_pairs"));
            Map<String,TreeSet<String>> values=new TreeMap<>();
            for(String group:new String[]{"actors","items","journal","levels","misc","plants","scenes","ui","windows"}) {
                Properties zh=read(loader,"messages/"+group+"/"+group+"_zh.properties");
                Properties en=read(loader,"messages/"+group+"/"+group+".properties");
                for(String key:zh.stringPropertyNames())if(en.containsKey(key)&&zh.getProperty(key).codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN))
                    values.computeIfAbsent(zh.getProperty(key),ignored->new TreeSet<>()).add(en.getProperty(key));
            }
            int resolved=0,rejected=0,normalized=0;
            for(Map.Entry<String,TreeSet<String>> entry:values.entrySet()) {
                TreeSet<String> folded=new TreeSet<>();for(String value:entry.getValue())folded.add(value.toLowerCase(Locale.ROOT));
                if(folded.size()==1) {assertEquals(folded.first(),t.translate(entry.getKey()).toLowerCase(Locale.ROOT));resolved++;}
                else if(entry.getKey().equals("凝神")||entry.getKey().equals("选择要释放魔法的位置")||entry.getKey().equals("我将稍后决定")) {
                    assertNotNull(t.translate(entry.getKey()));normalized++;
                } else {assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate(entry.getKey()));rejected++;}
            }
            assertEquals(3,normalized);assertEquals(52,rejected);
            System.out.println("DISPLAYED_TEXT_RESOURCE_STATS "+t.statistics()+" resolved_complete="+resolved+" normalized_ambiguous_complete="+normalized+" rejected_ambiguous_complete="+rejected);
        }
    }

    @Test void actualResourceTemplatesAreExercisedWithDisplayedNamesAndNumbers() throws Exception {
        Path assets=Path.of("../core/src/main/assets").toAbsolutePath();
        Pattern format=Pattern.compile("%(?:(\\d+)\\$)?[-#+0,(<]*(?:\\d+)?(?:\\.(\\d+))?([sdf])");
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{assets.toUri().toURL()},null)) {
            long started=System.nanoTime();
            DisplayedTextEnglish t=DisplayedTextEnglish.fromClassLoader(loader);
            long constructionNanos=System.nanoTime()-started;
            int cases=0,resolved=0;ArrayList<String> rejected=new ArrayList<>();
            for(String group:new String[]{"actors","items","journal","levels","misc","plants","scenes","ui","windows"}) {
                Properties zh=read(loader,"messages/"+group+"/"+group+"_zh.properties");
                Properties en=read(loader,"messages/"+group+"/"+group+".properties");
                for(String key:new TreeSet<>(zh.stringPropertyNames())) {
                    String source=zh.getProperty(key),target=en.getProperty(key);
                    if(target==null||!source.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN))continue;
                    Matcher matcher=format.matcher(source);Map<Integer,Character> conversions=new TreeMap<>();int implicit=0;
                    while(matcher.find())conversions.put(matcher.group(1)==null?implicit++:Integer.parseInt(matcher.group(1))-1,matcher.group(3).charAt(0));
                    if(conversions.isEmpty())continue;
                    int length=Collections.max(conversions.keySet())+1;Object[] visible=new Object[length],expected=new Object[length];
                    for(Map.Entry<Integer,Character> conversion:conversions.entrySet()) {
                        int slot=conversion.getKey();char type=conversion.getValue();
                        if(type=='s'){visible[slot]="战士";expected[slot]="warrior";}
                        else if(type=='f'){visible[slot]=expected[slot]=12.25+slot;}
                        else visible[slot]=expected[slot]=12+slot;
                    }
                    String actualVisible=String.format(Locale.CHINESE,source,visible);
                    String expectedEnglish=String.format(Locale.ENGLISH,target,expected);
                    cases++;
                    try {
                        assertEquals(expectedEnglish.toLowerCase(Locale.ROOT),t.translate(actualVisible).toLowerCase(Locale.ROOT),key);
                        resolved++;
                    }catch(DisplayedTextEnglish.PublicTextUnavailableException unavailable){rejected.add(key);}
                }
            }
            assertEquals(584,cases);assertTrue(resolved>500,"Most reviewed templates must support actual arguments");
            assertEquals(new TreeSet<>(java.util.Arrays.asList("actors.hero.spells.holyward.glyph_name","actors.hero.spells.holyweapon.ench_name",
                    "actors.mobs.mob.rankings_desc","items.item.rankings_desc","items.weapon.melee.spear.ability_desc",
                    "items.weapon.melee.spear.typical_ability_desc")),new TreeSet<>(rejected),"Only reviewed resource collisions may remain unsupported");
            System.out.println("DISPLAYED_TEXT_FORMATTED_STATS cases="+cases+" resolved="+resolved+" rejected="+rejected.size()
                    +" construction_ms="+constructionNanos/1_000_000.0+" rejected_keys="+rejected);
        }
    }

    private static Properties read(ClassLoader loader,String path)throws Exception {
        Properties p=new Properties();try(InputStreamReader r=new InputStreamReader(loader.getResourceAsStream(path),StandardCharsets.UTF_8)){p.load(r);}return p;
    }
}
