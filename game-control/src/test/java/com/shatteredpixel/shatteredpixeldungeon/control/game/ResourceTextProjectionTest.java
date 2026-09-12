package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.*;
import java.util.stream.Stream;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

/** Replaces language-dependent reverse signatures with resource identity and immutable source tests. */
class ResourceTextProjectionTest {
    private static final List<String> LABELS=List.of(
            "windows.wndupgrade.blocking","items.stones.stoneofaugmentation$wndaugment.defense",
            "actors.char.def_verb","items.stones.stoneofaugmentation$wndaugment.evasion",
            "actors.hero.abilities.rogue.deathmark.name","actors.hero.abilities.rogue.deathmark$deathmarktracker.name",
            "items.artifacts.cloakofshadows.ac_stealth","items.weapon.melee.dagger.ability_name",
            "windows.wndsupportprompt.close","windows.wndsettings$displaytab.off",
            "windows.wndupgrade.back","windows.wndgameinprogress.erase","windows.wndgame.settings",
            "windows.wndkeybindings.back","windows.wndkeybindings.none","windows.wndkeybindings.confirm",
            "windows.wndkeybindings$wndchangebinding.unbind","ui.customnotebutton$customnotewindow.confirm",
            "windows.wndresurrect.warn_yes","windows.wndresurrect.warn_no",
            "windows.wndtradeitem.steal_warn_yes","windows.wndtradeitem.steal_warn_no",
            "windows.wndsadghost.confirm","windows.wndsadghost.cancel",
            "items.scrolls.inventoryscroll.yes","items.scrolls.inventoryscroll.no",
            "scenes.heroselectscene.start","scenes.titlescene.play","scenes.titlescene.news",
            "scenes.newsscene.title","items.journal.guidebook.hint_status",
            "windows.wndvictorycongrats.title","actors.hero.talent.life_link.title");

    @TestFactory Stream<DynamicTest> everyGuiLanguageUsesTheSameEnglishResourceAcrossAllNineDomains() {
        Set<String> keys=new TreeSet<>(LABELS);
        for(String group:List.of("actors","items","journal","levels","misc","plants","scenes","ui","windows"))
            ResourceTextFixture.properties(group,Languages.ENGLISH).stringPropertyNames().stream().sorted()
                    .filter(key->!ResourceTextFixture.resource(Languages.ENGLISH,key).contains("%"))
                    .limit(3).forEach(keys::add);
        for(String hero:List.of("warrior","mage","rogue","huntress","duelist","cleric"))keys.add("actors.hero.heroclass."+hero+"_desc");
        return Arrays.stream(Languages.values()).map(language->DynamicTest.dynamicTest(language.code(),()->{
            for(String key:keys) {
                String shown=ResourceTextFixture.source(language,key);
                Map<String,Object> rendered=PublicEnglishProjection.copy(map("role","text","text",shown));
                assertEquals(ResourceTextFixture.resource(Languages.ENGLISH,key),rendered.get("text"),language.code()+":"+key);
                assertEquals("complete",PublicEnglishProjection.presentation(rendered).get("status"),key);
                assertEquals(rendered,PublicEnglishProjection.copy(rendered),"Rendering already-rendered DTOs is idempotent");
            }
        }));
    }

    @Test void equalChineseLabelsKeepDistinctOriginsWithoutAnyWindowSignature() {
        String blocking=ResourceTextFixture.source("windows.wndupgrade.blocking");
        String defense=ResourceTextFixture.source("items.stones.stoneofaugmentation$wndaugment.defense");
        assertEquals(blocking,defense); assertNotSame(blocking,defense);
        assertEquals("Blocking",ResourceTextFixture.english(blocking));
        assertEquals("Defense",ResourceTextFixture.english(defense));
        for(String scene:List.of("GameScene","TitleScene","HeroSelectScene"))
            assertEquals("Blocking",PublicEnglishProjection.copyInScene(map("text",blocking),scene).get("text"));
    }

    @Test void aCopiedStringCannotBorrowItsNeighborsSceneButtonOrFormerSource() {
        String complete=ResourceTextFixture.source("windows.wndsupportprompt.close");
        assertEquals("Close",ResourceTextFixture.english(complete));
        Map<String,Object> unknown=map("text",new String(complete),"role","button","presentation","floating_text");
        Map<String,Object> ui=map("scene","GameScene","modal",true,"controls",List.of(map("role","button","text",complete)));
        assertEquals("partial",PublicEnglishProjection.presentation(PublicEnglishProjection.copyWithUi(unknown,ui)).get("status"));
        assertEquals("partial",ResourceTextFixture.status(complete+" unknown tail"));
        assertEquals("partial",ResourceTextFixture.status("unknown prefix "+complete));
    }

    @Test void equalVisibleFragmentsCannotExposeDifferentHiddenKeysOrArguments() {
        TextProvenance p=new TextProvenance(key->key.equals("hidden.a")?"First hidden %d":"Second hidden %d");
        String a=p.onTextResource("同一前缀 隐藏甲17","hidden.a","zh",new Object[]{17});
        String b=p.onTextResource("同一前缀 隐藏乙29","hidden.b","zh",new Object[]{29});
        Map<String,Object> first=p.render(p.capture(null,a,true)),second=p.render(p.capture(null,b,true));
        assertEquals(first,second); assertEquals("partial",first.get("translation_status"));
        assertNull(first.get("source"));
        String publicJson=JsonCodec.encode(first); assertFalse(publicJson.contains("hidden"));
        assertFalse(publicJson.contains("17")); assertFalse(publicJson.contains("29"));
    }

    @Test void frozenHistoryNeverConsultsTheCurrentResourceCatalogOrLiveObjects() {
        Map<String,String> catalog=new HashMap<>();catalog.put("fixture.message","Recorded %d");
        TextProvenance p=new TextProvenance(catalog::get);
        String shown=p.onTextResource("当时17","fixture.message","zh",new Object[]{17});
        Map<String,Object> frozen=JsonCodec.decode(JsonCodec.encode(p.capture(null,shown,false)));
        catalog.put("fixture.message","Replaced %d");
        TextProvenance later=new TextProvenance(key->{throw new AssertionError("History may not reload templates");});
        assertEquals("Recorded 17",later.render(frozen).get("text"));
    }

    @Test void sourceFailureIsFieldLocalAndCannotRewriteRawRequestsOrRecordedResponses() {
        Map<String,Object> raw=map("response",map("text","original wire text"),"raw_request","用户原始请求",
                "name",ResourceTextFixture.source("windows.wndupgrade.blocking"),"description","未登记的新文案");
        Map<String,Object> rendered=PublicEnglishProjection.copy(raw);
        assertEquals("Blocking",rendered.get("name"));
        assertEquals(raw.get("response"),rendered.get("response"));assertEquals(raw.get("raw_request"),rendered.get("raw_request"));
        Map<String,Object> presentation=PublicEnglishProjection.presentation(rendered);
        assertEquals("partial",presentation.get("status"));
        assertEquals(1,((List<?>)presentation.get("diagnostics")).size());
        assertEquals(rendered,PublicEnglishProjection.copy(rendered));
    }
}
