package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class TextCompositionProjectionTest {
    @Test void actualUpgradeWindowComposesItsStatLabelsAndAlreadyDisplayedNumbers() {
        String shown=ResourceTextFixture.join(ResourceTextFixture.source("windows.wndupgrade.desc"),"\n+1\n",
                ResourceTextFixture.source("windows.wndupgrade.blocking"),"\n0~2\n1~3\n",
                ResourceTextFixture.source("windows.wndupgrade.weight"),"\n10\n9");
        assertEquals("Upgrading an item permanently improves it:\n+1\nBlocking\n0~2\n1~3\nWeight\n10\n9",ResourceTextFixture.english(shown));
        assertEquals("complete",ResourceTextFixture.status(shown));
    }

    @Test void supportPromptKeepsEachParagraphTheLanguageNoticeAndItsLiteralSignature() {
        for(boolean notice:List.of(false,true)) {
            String shown=ResourceTextFixture.join(ResourceTextFixture.source("windows.wndsupportprompt.intro"),"\n\n",
                    ResourceTextFixture.source("scenes.supporterscene.patreon_msg"),
                    notice?ResourceTextFixture.join("\n",ResourceTextFixture.source("scenes.supporterscene.patreon_english")):"",
                    ResourceTextFixture.literal("\n- Evan"));
            String expected=ResourceTextFixture.resource(Languages.ENGLISH,"windows.wndsupportprompt.intro")+"\n\n"
                    +ResourceTextFixture.resource(Languages.ENGLISH,"scenes.supporterscene.patreon_msg")
                    +(notice?"\n"+ResourceTextFixture.resource(Languages.ENGLISH,"scenes.supporterscene.patreon_english"):"")+"\n- Evan";
            assertEquals(expected,ResourceTextFixture.english(shown));
        }
    }

    @Test void knownEquipmentDescriptionComposesOnlyTheSuppliedStatResources() {
        String shown=ResourceTextFixture.join(ResourceTextFixture.source("items.weapon.missiles.throwingstone.desc"),"\n\n",
                ResourceTextFixture.source("items.weapon.missiles.missileweapon.stats_known",1,2,5,9)," ",
                ResourceTextFixture.source("items.weapon.weapon.excess_str",1),"\n\n",
                ResourceTextFixture.source("items.weapon.missiles.missileweapon.distance"),"\n\n",
                ResourceTextFixture.source("items.weapon.missiles.missileweapon.uses_left",4,5));
        String english=ResourceTextFixture.english(shown);
        assertEquals("complete",ResourceTextFixture.status(shown));
        assertTrue(english.contains("2-5"));assertTrue(english.contains("4/5"));
        assertFalse(english.contains("5/5"));
    }

    @Test void reorderedAndLocalizedNumericArgumentsRenderFromValuesWithoutParsingGuiDigits() {
        TextProvenance p=new TextProvenance(Map.of("fixture.name","warrior","fixture.damage","Dealt %1$d damage to %2$s.",
                "fixture.precision","Effect: %1$.2f%%; count: %2$,d")::get);
        String name=p.onTextResource("战士","fixture.name","zh",new Object[0]);
        String damage=p.onTextResource("战士受到17点伤害。","fixture.damage","zh",new Object[]{17,name});
        assertEquals("Dealt 17 damage to warrior.",p.render(p.capture(null,damage,false)).get("text"));
        String precision=p.onTextResource("Effekt: 1,20%; Anzahl: 1.234","fixture.precision","de",new Object[]{1.2,1234});
        assertEquals("Effect: 1.20%; count: 1,234",p.render(p.capture(null,precision,false)).get("text"));
    }

    @Test void explicitFormattingAndCaseTransformsPreserveTheOriginalResourceIdentity() {
        TextProvenance p=new TextProvenance(Map.of("fixture.item","wand of magic missile","fixture.found","You found %s.")::get);
        String item=p.onTextResource("魔弹法杖","fixture.item","zh",new Object[0]);
        String template=p.onTextResource("你发现了%s。","fixture.found","zh",new Object[0]);
        String formatted=p.onTextOperation("format","你发现了魔弹法杖。",template,new Object[]{item});
        assertEquals("You found wand of magic missile.",p.render(p.capture(null,formatted,false)).get("text"));
        String upper=p.onTextOperation("upper_case","魔弹法杖",item);
        assertEquals("WAND OF MAGIC MISSILE",p.render(p.capture(null,upper,false)).get("text"));
    }

    @Test void literalPercentAndOriginalSeparatorsDoNotBecomeTemplateArguments() {
        TextProvenance p=new TextProvenance(Map.of("fixture.prose","25% more damage.","fixture.paragraph","First.\n\nSecond.")::get);
        String prose=p.onTextResource("额外25%伤害。","fixture.prose","zh",new Object[0]);
        String paragraph=p.onTextResource("第一段。\n\n第二段。","fixture.paragraph","zh",new Object[0]);
        for(String separator:List.of("\n","\n\n","\r\n","\t","  ")) {
            String result=p.onTextOperation("concat",paragraph+separator+prose,paragraph,separator,prose);
            assertEquals("First.\n\nSecond."+separator+"25% more damage.",p.render(p.capture(null,result,false)).get("text"));
        }
    }

    @Test void unsafeArgumentsOrUnknownTailsCannotLeakTheContainingResourceKey() {
        TextProvenance p=new TextProvenance(Map.of("fixture.secret","You see %s.")::get);
        String shown=p.onTextResource("你看到了未登记对象。","fixture.secret","zh",new Object[]{"未登记对象"});
        Map<String,Object> frozen=p.capture(null,shown,false);
        assertFalse(JsonCodec.encode(frozen).contains("fixture.secret"));
        Map<String,Object> rendered=p.render(frozen);
        assertEquals("partial",rendered.get("translation_status"));assertNull(rendered.get("source"));
        Object unconverted=new Object(){@Override public String toString(){throw new AssertionError("Capturing provenance may not invoke model methods");}};
        String unsafe=p.onTextResource("已经显示","fixture.secret","zh",new Object[]{unconverted});
        assertEquals("partial",p.render(p.capture(null,unsafe,false)).get("translation_status"));
    }

    @Test void partialResourcesCannotRestoreHiddenParagraphsEvenWhenTheirPrefixWasProducedBySlicing() {
        TextProvenance p=new TextProvenance(key->"Visible paragraph.\n\nUnseen private paragraph.");
        String complete=p.onTextResource("可见段落。\n\n未显示段落。","fixture.paragraph","zh",new Object[0]);
        String partial=p.onTextOperation("slice","可见段落。",complete,0,5);
        Map<String,Object> result=p.render(p.capture(null,partial,false));
        assertEquals("partial",result.get("translation_status"));assertNull(result.get("source"));
        assertFalse(result.toString().contains("Unseen"));assertFalse(result.toString().contains("fixture.paragraph"));
    }

    @Test void explicitUserAndExternalLiteralsKeepTheirContentsWithoutMasqueradingAsResourceText() {
        TextProvenance p=new TextProvenance(key->{throw new AssertionError("Literal content has no resource lookup");});
        for(String origin:List.of("user","external")) {
            String shown=p.onTextOperation(origin,"用户文字 / original content","用户文字 / original content");
            Map<String,Object> result=p.render(p.capture(null,shown,false));
            assertEquals("用户文字 / original content",result.get("text"));assertEquals("complete",result.get("translation_status"));
            assertTrue(result.get("source").toString().contains(origin));
        }
    }
}
