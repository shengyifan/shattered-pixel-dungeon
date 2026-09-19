package com.shatteredpixel.shatteredpixeldungeon.control.game.text;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class PublicTextSourcesTest {
    @Test public void everyRendererOutputKindAndOrdinaryOriginIsOrdinary() {
        assertEquals(14,PublicTextSources.ORDINARY_KINDS.size());
        for(String kind:PublicTextSources.ORDINARY_KINDS)for(String origin:PublicTextSources.ORDINARY_ORIGINS) {
            Object source=map("kind",kind,"origin",origin,"value",map("kind","literal","origin","literal","value","Safe"));
            assertFalse(kind+"/"+origin,PublicTextSources.requiresSource(source));
            assertFalse(kind+"/"+origin,PublicTextSources.protectsField(source));
        }
    }
    @Test public void realRenderedFrozenTokensCoverEveryOrdinaryOutputKind() {
        Map<String,Object> literal=map("kind","literal","origin","literal","value","Safe");
        Map<String,Object> scalar=map("kind","scalar","scalar_type","Integer","value",7);
        Map<String,Object> fragment=map("kind","formatted_fragment","specifier","%.1f","text","1.2");
        List<Object[]> fixtures=Arrays.asList(
                new Object[]{"resource",map("kind","resource","key","test.count","template","Count %d",
                        "arguments",Arrays.asList(scalar),"formatted",true),"Count 7"},
                new Object[]{"literal",literal,"Safe"},
                new Object[]{"scalar",scalar,"7"},
                new Object[]{"concat",map("kind","concat","parts",Arrays.asList(literal,
                        map("kind","literal","origin","literal","value"," text"))),"Safe text"},
                new Object[]{"format",map("kind","format","format",map("kind","literal","origin","literal","value","Count %d"),
                        "arguments",Arrays.asList(scalar)),"Count 7"},
                new Object[]{"case",map("kind","case","operation","upper_case","value",literal),"SAFE"},
                new Object[]{"slice",map("kind","slice","value",literal,"begin",1,"end",3),"af"},
                new Object[]{"replace",map("kind","replace","value",literal,"old","Safe","new","Clear"),"Clear"},
                new Object[]{"formatted_argument",map("kind","formatted_argument","origin","scalar","fragments",Arrays.asList(fragment)),"1.2"},
                new Object[]{"formatted_fragment",fragment,"1.2"},
                new Object[]{"decimal",map("kind","decimal","pattern","0.0","formatted","1.2"),"1.2"},
                new Object[]{"language",map("kind","language","code","en","english","English"),"English"},
                new Object[]{"strip_prefix",map("kind","strip_prefix","prefix","@","value",
                        map("kind","literal","origin","literal","value","@Safe")),"Safe"},
                new Object[]{"displayed",map("kind","displayed","markup",true,"value",
                        map("kind","literal","origin","literal","value","**Safe**")),"Safe"});
        Set<String> emittedKinds=new LinkedHashSet<>();
        for(Object[] fixture:fixtures) {
            String kind=(String)fixture[0];
            // Use the real frozen-token envelope and its JSON round trip, not
            // fabricated output ASTs shaped only to match the classifier.
            Map<String,Object> token=JsonCodec.decode(JsonCodec.encode(map("$text_source",1,"node",fixture[1])));
            assertTrue(kind,TextProvenance.isToken(token));
            Map<String,Object> rendered=EnglishTextRenderer.render(token);
            assertEquals(kind,fixture[2],rendered.get("text"));
            assertEquals(kind,"complete",rendered.get("translation_status"));
            assertFalse(kind,rendered.containsKey("diagnostic"));
            assertTrue(kind,rendered.get("source") instanceof Map);
            Map<?,?> source=(Map<?,?>)rendered.get("source");
            assertEquals(kind,kind,source.get("kind"));
            assertFalse(kind,PublicTextSources.requiresSource(source));
            assertFalse(kind,PublicTextSources.protectsField(source));
            emittedKinds.add((String)source.get("kind"));
        }
        assertEquals(14,emittedKinds.size());
        assertEquals(PublicTextSources.ORDINARY_KINDS,emittedKinds);
    }
    @Test public void renderedResourceReferencesRemainProtectedPartialResourceSources() {
        for(String reason:Arrays.asList("argument_not_displayed","format_precision_not_displayed")) {
            Map<String,Object> token=JsonCodec.decode(JsonCodec.encode(map("$text_source",1,"node",
                    map("kind","resource_reference","key","test.hidden_argument","reason",reason))));
            assertTrue(TextProvenance.isToken(token));
            Map<String,Object> rendered=EnglishTextRenderer.render(token);
            assertEquals("[key: test.hidden_argument]",rendered.get("text"));
            assertEquals("partial",rendered.get("translation_status"));
            assertEquals(reason,rendered.get("diagnostic"));
            assertEquals(map("kind","resource","key","test.hidden_argument","args",Collections.emptyList(),"visibility","partial"),rendered.get("source"));
            assertTrue(PublicTextSources.requiresSource(rendered.get("source")));
            assertTrue(PublicTextSources.protectsField(rendered.get("source")));
        }
    }
    @Test public void nestedOriginsProtectTheDisplayWithoutRequiringOrdinaryAst() {
        Object source=map("kind","concat","parts",Arrays.asList(map("kind","literal","origin","user","value","Note"),
                map("kind","formatted_argument","origin","external","fragments",Arrays.asList(map("kind","formatted_fragment","text","Name")))));
        assertEquals(new LinkedHashSet<>(Arrays.asList("user","external")),PublicTextSources.origins(source));
        assertTrue(PublicTextSources.protectsField(source));assertFalse(PublicTextSources.requiresSource(source));
    }
    @Test public void unknownUnavailableAndIncompleteEvidenceIsAlwaysProtectedRecursively() {
        for(Object exceptional:Arrays.asList(map("kind","unavailable","reason","missing_source"),map("kind","join"),map("kind","plural"),
                map("kind","future"),map("kind","literal","origin","future"),map("kind","resource","visibility","partial"),
                map("kind","displayed","clipped",true),map("kind","resource","diagnostic","missing_template"))) {
            Object outer=map("kind","resource","args",Arrays.asList(map("kind","concat","parts",Arrays.asList(exceptional))));
            assertTrue(String.valueOf(exceptional),PublicTextSources.requiresSource(outer));
            assertTrue(String.valueOf(exceptional),PublicTextSources.protectsField(outer));
        }
    }
}
