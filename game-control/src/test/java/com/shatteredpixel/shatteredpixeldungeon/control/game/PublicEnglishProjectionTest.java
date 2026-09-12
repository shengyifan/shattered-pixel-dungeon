package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class PublicEnglishProjectionTest {
    private static String resource(String text,String key,Object... args) {
        return TextProvenance.INSTANCE.onTextResource(text,key,"zh",args);
    }
    @Test void sameChineseTextUsesItsActualResourceIdentity() {
        String blocking=resource("防御","windows.wndupgrade.blocking");
        String defense=resource("防御","items.stones.stoneofaugmentation$wndaugment.defense");
        Map<String,Object> projected=PublicEnglishProjection.copy(map("text",blocking,"label",defense));
        assertEquals("Blocking",projected.get("text"));
        assertEquals("Defense",projected.get("label"));
        assertTrue(projected.get("text_sources").toString().contains("windows.wndupgrade.blocking"));
        assertFalse(projected.toString().contains("template="));
        assertEquals("complete",PublicEnglishProjection.presentation(projected).get("status"));
    }
    @Test void unclassifiedTextNeverBorrowsEqualRegisteredText() {
        resource("防御","windows.wndupgrade.blocking");
        Map<String,Object> projected=PublicEnglishProjection.copy(map("text",new String("防御")));
        assertEquals("partial",PublicEnglishProjection.presentation(projected).get("status"));
        assertFalse(projected.toString().contains("windows.wndupgrade.blocking"));
        assertFalse(projected.toString().contains("防御"));
    }
    @Test void clippingRemovesKeysAndFrozenArgumentsBeforeSerialization() {
        String full=resource("完整文案","windows.wndupgrade.blocking",123456789);
        Map<String,Object> frozen=PublicEnglishProjection.freeze(map("text",full,"clipped",true));
        Map<String,Object> projected=PublicEnglishProjection.copy(frozen);
        for(Object result:Arrays.asList(frozen,projected)) {
            assertFalse(result.toString().contains("windows.wndupgrade.blocking"));
            assertFalse(result.toString().contains("123456789"));
        }
        assertEquals("partial",PublicEnglishProjection.presentation(projected).get("status"));
    }
    @Test void explicitUserAndExternalInputsRemainOriginalEvenIfTheyImpersonateSystemText() {
        String user=TextProvenance.INSTANCE.onTextOperation("user","防御");
        String external=TextProvenance.INSTANCE.onTextOperation("external","新语言の外部消息");
        Map<String,Object> projected=PublicEnglishProjection.copy(map("text",user,"label",external));
        assertEquals("防御",projected.get("text")); assertEquals("新语言の外部消息",projected.get("label"));
        assertEquals("complete",PublicEnglishProjection.presentation(projected).get("status"));
        assertTrue(projected.get("text_sources").toString().contains("origin=user"));
    }
    @Test void historicalWireAndOpaqueRequestBytesAreNeverReinterpreted() {
        Map<String,Object> response=map("text","historical wire", "presentation",map("status","partial"));
        Map<String,Object> original=map("response",response,"raw_request","原始字节", "id","原始id");
        assertEquals(original,PublicEnglishProjection.copy(original));
        assertSame(response,PublicEnglishProjection.copy(original).get("response"));
    }
    @Test void frozenTokensSurviveRegistryResetAndRepeatedRenderingIsIdempotent() {
        Map<String,Object> frozen=PublicEnglishProjection.freeze(map("text",resource("防御","windows.wndupgrade.blocking")));
        TextProvenance.INSTANCE.clear();
        Map<String,Object> once=PublicEnglishProjection.copy(frozen);
        assertEquals("Blocking",once.get("text"));
        assertEquals(once,PublicEnglishProjection.copy(once));
        assertEquals(PublicEnglishProjection.presentation(once),PublicEnglishProjection.presentation(PublicEnglishProjection.copy(once)));
    }
    @Test void presentationFailureDoesNotChangeTheSemanticActionVersionInput() {
        Map<String,Object> node=map("kind","resource","key","windows.wndupgrade.blocking","arguments",Collections.emptyList(),"template","Blocking");
        Map<String,Object> missing=new LinkedHashMap<>(node);missing.put("template",null);
        Map<String,Object> complete=map("id","ui-1","enabled",true,"text",map("$text_source",1,"node",node));
        Map<String,Object> partial=map("id","ui-1","enabled",true,"text",map("$text_source",1,"node",missing));
        assertEquals(PublicEnglishProjection.semantics(complete),PublicEnglishProjection.semantics(partial));
        partial.put("id","ui-2");
        assertNotEquals(PublicEnglishProjection.semantics(complete),PublicEnglishProjection.semantics(partial));
        partial.put("id","ui-1");partial.put("enabled",false);
        assertNotEquals(PublicEnglishProjection.semantics(complete),PublicEnglishProjection.semantics(partial));
    }
    @Test void actionLabelsAndListOptionsCarryParallelSources() {
        Map<String,Object> result=PublicEnglishProjection.copy(map("options",Arrays.asList(
                resource("防御","windows.wndupgrade.blocking"),resource("防御","items.stones.stoneofaugmentation$wndaugment.defense"))));
        assertEquals(Arrays.asList("Blocking","Defense"),result.get("options"));
        assertEquals(result,PublicEnglishProjection.copy(result));
        assertTrue(result.get("text_sources").toString().contains("options"));
    }
    @Test void whollyPartialOptionsKeepTheirOriginalReasonsAcrossCopies() {
        Map<String,Object> clipped=TextProvenance.INSTANCE.capture(null,"unseen data",true);
        Map<String,Object> unknown=TextProvenance.INSTANCE.capture(null,"untracked data",false);
        Map<String,Object> first=PublicEnglishProjection.copy(map("options",Arrays.asList(clipped,unknown)));
        assertEquals(first,PublicEnglishProjection.copy(first));
        assertEquals(Arrays.asList(null,null),((Map<?,?>)first.get("text_sources")).get("options"));
        assertEquals("clipped_text",((Map<?,?>)first.get("text_diagnostics")).get("options[0]"));
    }

    @Test void addingAFieldDoesNotDiscardAlreadyRenderedFieldSources() {
        Map<String,Object> first=PublicEnglishProjection.copy(map("text",resource("防御","windows.wndupgrade.blocking")));
        first.put("label",resource("防御","items.stones.stoneofaugmentation$wndaugment.defense"));
        Map<String,Object> mixed=PublicEnglishProjection.copy(first);
        assertEquals(2,((Map<?,?>)mixed.get("text_sources")).size());
        assertEquals(mixed,PublicEnglishProjection.copy(mixed));
    }

    @Test void languageNameRenderingIsNotPartOfItsSemanticCode() {
        Map<String,Object> source=map("kind","language","code","fr","english","French");
        Map<String,Object> failed=new LinkedHashMap<>(source);failed.put("english",null);
        assertEquals(PublicEnglishProjection.semantics(source),PublicEnglishProjection.semantics(failed));
    }

}
