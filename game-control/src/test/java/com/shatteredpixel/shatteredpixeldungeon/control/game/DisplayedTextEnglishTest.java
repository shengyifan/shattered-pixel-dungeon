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

    @Test void ambiguityInsideOnePublicSceneStillFailsAndScopeCannotMatchOnlyAPrefix() {
        Map<String,String> zh=Map.of("scenes.titlescene.a","同文","scenes.titlescene.b","同文");
        Map<String,String> en=Map.of("scenes.titlescene.a","A meaning","scenes.titlescene.b","A different meaning");
        DisplayedTextEnglish t=DisplayedTextEnglish.fromResources(zh,en);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("同文","TitleScene"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translateInScene("同文隐藏尾部","TitleScene"));
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
            int resolved=0,rejected=0;
            for(Map.Entry<String,TreeSet<String>> entry:values.entrySet()) {
                TreeSet<String> folded=new TreeSet<>();for(String value:entry.getValue())folded.add(value.toLowerCase(Locale.ROOT));
                if(folded.size()==1) {assertEquals(folded.first(),t.translate(entry.getKey()).toLowerCase(Locale.ROOT));resolved++;}
                else {assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->t.translate(entry.getKey()));rejected++;}
            }
            System.out.println("DISPLAYED_TEXT_RESOURCE_STATS "+t.statistics()+" resolved_complete="+resolved+" ambiguous_complete="+rejected);
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
            System.out.println("DISPLAYED_TEXT_FORMATTED_STATS cases="+cases+" resolved="+resolved+" rejected="+rejected.size()
                    +" construction_ms="+constructionNanos/1_000_000.0+" rejected_keys="+rejected);
        }
    }

    private static Properties read(ClassLoader loader,String path)throws Exception {
        Properties p=new Properties();try(InputStreamReader r=new InputStreamReader(loader.getResourceAsStream(path),StandardCharsets.UTF_8)){p.load(r);}return p;
    }
}
