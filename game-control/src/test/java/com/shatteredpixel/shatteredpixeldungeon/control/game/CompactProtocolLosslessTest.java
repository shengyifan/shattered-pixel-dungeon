package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Public synthetic captures only: compression must not discard the content a caller supplied. */
public class CompactProtocolLosslessTest {
    @Test public void everyViewRetainsPublicDescriptionsTalentsCoverageAndActionConstraints() {
        Map<String,Object> coverage=map("status","observation_with_inspection","custom",Arrays.asList(null,false,0));
        Map<String,Object> item=map("locator","bag.0","name","wand","quantity",1,
                "level_known",false,"level",null,"curse_known",true,"cursed",false,
                "description","Charges, targeting limits, and a complete public warning.");
        List<Object> talents=Arrays.asList(map("name","Unspent","tier",2,"points",0,"description","A future choice"),
                map("name","Invested","tier",1,"points",2));
        Map<String,Object> operation=map("action","ui.value","control","c1","label","Amount","range",Arrays.asList(0,3),
                "parameters",map("minimum",0,"maximum",3,"nullable",null,"enabled",false),
                "arguments",Arrays.asList("value"),"modes",Arrays.asList("exact","remaining"),"units","charges",
                "text_sources",map("units",map("kind","literal","origin","external","value","charges")));
        Map<String,Object> capture=map("inventory",Arrays.asList(item),"hero",map("talents",talents),"coverage",coverage,
                "ui",map("controls",Arrays.asList(map("id","c1","role","slider","minimum",0,"maximum",3,"label","Amount"))),
                "actions",Arrays.asList(operation,map("action","view.pan","parameters",map("x",0,"y",0),"units","map_view")));
        String untouched=JsonCodec.encode(capture);
        for(boolean full:Arrays.asList(false,true))for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> output=object(CompactProtocol.expandStructures(CompactProtocol.project(capture,sources,full)));
            Map<String,Object> projectedItem=object(((List<?>)output.get("inv")).get(0));
            assertEquals(item.get("description"),projectedItem.get("desc"));
            assertTrue(projectedItem.containsKey("level"));assertNull(projectedItem.get("level"));
            assertEquals(false,projectedItem.get("cursed"));
            assertEquals(coverage,output.get("coverage"));
            assertEquals(Arrays.asList(map("name","Unspent","tier",2,"points",0,"desc","A future choice"),talents.get(1)),
                    object(output.get("hero")).get("talents"));
            Map<String,Object> op=object(((List<?>)node(output,0).get("ops")).get(0));
            assertEquals("Amount",op.get("label"));assertEquals(Arrays.asList(0,3),op.get("range"));
            assertEquals(map("min",0,"max",3,"nullable",null,"enabled",false),op.get("parameters"));
            assertEquals(operation.get("arguments"),op.get("arguments"));assertEquals(operation.get("modes"),op.get("modes"));
            assertEquals("charges",op.get("units"));
            assertEquals(sources?operation.get("text_sources"):map("units",Arrays.asList("external")),op.get(sources?"text_sources":"text_origins"));
            List<?> actions=(List<?>)output.get("acts");assertEquals(2,actions.size());
            assertEquals("c1",object(actions.get(0)).get("ctl"));
            assertEquals(map("op","pan","parameters",map("x",0,"y",0),"units","map_view"),actions.get(1));
        }
        assertEquals(untouched,JsonCodec.encode(capture));
    }

    @Test public void hintsNeverDeleteEmptyNullDuplicateOrOwnedNodesInAnyView() {
        List<Object> nodes=Arrays.asList(map("id","c0","role","button","enabled",false),
                map("id","c1","role","button","label","Waterskin","text","0/20\n14?","gestures",Arrays.asList("click","long")),
                map("id","c2","parent","c1","role","text","text","0/20"),
                map("id","c3","parent","c1","role","text","text","14?"),
                map("id","c4","role","text","text",null),map("id","c5","role","text","text",""),
                map("id","c6","role","text"),map("id","c7","parent","c1","role","text","text","0/20\n14?"));
        Map<String,UiProjectionHints.Node> hints=new LinkedHashMap<>();
        hints.put("c0",new UiProjectionHints.Node(true,Collections.emptyList(),null,Collections.emptyMap()));
        Map<String,String> display=new LinkedHashMap<>();display.put("status","c2");display.put("extra","c3");
        hints.put("c1",new UiProjectionHints.Node(false,Arrays.asList("c2","c3"),"bag.0",display));
        Map<String,Object> input=map("inventory",Arrays.asList(map("locator","bag.0","name","waterskin","quantity",1)),
                "ui",new UiProjectionHints(hints).attach(map("controls",nodes)));
        String untouched=JsonCodec.encode(input);
        for(boolean full:Arrays.asList(false,true))for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> output=object(CompactProtocol.expandStructures(CompactProtocol.project(input,sources,full)));
            List<?> actual=(List<?>)object(output.get("ui")).get("nodes");assertEquals(nodes.size(),actual.size());
            for(int i=0;i<nodes.size();i++)for(Map.Entry<String,Object> field:object(nodes.get(i)).entrySet()) {
                assertTrue("Lost "+field.getKey()+" on node "+i,object(actual.get(i)).containsKey(field.getKey()));
                assertEquals(field.getValue(),object(actual.get(i)).get(field.getKey()));
            }
            assertEquals(map("status","0/20","extra","14?"),node(output,1).get("display"));
            assertEquals("bag.0",node(output,1).get("loc"));
        }
        assertEquals(untouched,JsonCodec.encode(input));
    }

    @Test public void exactLabelEncodingNeverChangesCustomCaseWhitespaceOrExistingLocator() {
        for(String label:Arrays.asList("scroll of upgrade","Scroll of Upgrade","SCROLL OF UPGRADE","Scroll  of Upgrade","ß blade","Custom label")) {
            Map<String,Object> input=map("inventory",Arrays.asList(map("locator","bag.0","name","scroll of upgrade","quantity",1)),
                    "ui",new UiProjectionHints(Collections.singletonMap("c1",new UiProjectionHints.Node(false,Collections.emptyList(),"bag.0",Collections.emptyMap())))
                            .attach(map("controls",Arrays.asList(map("id","c1","role","button","label",label)))));
            assertEquals(label,node(object(CompactProtocol.expandStructures(CompactProtocol.project(input,false))),0).get("label"));
            object(((List<?>)object(input.get("ui")).get("controls")).get(0)).put("locator","bag.other");
            Map<String,Object> output=object(CompactProtocol.expandStructures(CompactProtocol.project(input,false)));
            assertEquals("bag.other",node(output,0).get("loc"));assertEquals(label,node(output,0).get("label"));
        }
    }

    @Test public void unusualMetadataIsPreservedAndConflictingFlatteningFailsClosed() {
        Map<String,Object> input=map("coverage",map("unobserved",false),"translation_status","pending",
                "text_diagnostics",Collections.emptyMap(),"child",map("text_diagnostics",null,"translation_status",null));
        for(boolean sources:Arrays.asList(false,true))assertEquals(input,CompactProtocol.project(input,sources));
        Map<String,Object> hero=map("hp",10);
        assertEquals(map("hero",hero),CompactProtocol.project(map("hero",hero,"observation",map("hero",hero)),false));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->CompactProtocol.project(
                map("hero",hero,"observation",map("hero",map("hp",9))),false));
        assertEquals("Conflicting public observation field: hero",error.getMessage());
        assertThrows(IllegalArgumentException.class,()->CompactProtocol.project(map("max_hp",10,"ht",9),false));
    }

    @Test public void completePresentationExtensionsAndSourceKnowledgeAnnotationsArePreserved() {
        Map<String,Object> diagnostic=map("field","$.text","code","note","detail",map("reason","Keep every field"));
        Map<String,Object> input=map("text","Rendered","presentation",map("status","complete","diagnostics",Collections.emptyList(),
                "future",Arrays.asList(null,false,0)));
        for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> output=object(CompactProtocol.project(input,sources));
            assertEquals(map("st","complete","diag",Collections.emptyList(),"future",Arrays.asList(null,false,0)),output.get("pres"));
        }
        input.put("presentation",map("status","partial","diagnostics",Arrays.asList(diagnostic),"future",false));
        Map<String,Object> reply=CompactProtocol.success("q","s1","completed",input,true,false);
        assertEquals(false,object(object(reply.get("data")).get("pres")).get("future"));
        assertEquals(map("reason","Keep every field"),object(((List<?>)object(reply.get("pres")).get("diag")).get(0)).get("detail"));
        Map<String,Object> source=map("kind","literal","origin","catalog","value","unknown");
        Map<String,Object> item=map("locator","bag.0","quantity",1,"level_known",false,"level",null,"text_sources",map("level_known",source));
        Map<String,Object> detailed=object(CompactProtocol.project(item,true));
        assertEquals(false,detailed.get("level_known"));assertTrue(detailed.containsKey("level"));
        assertEquals(map("level_known",source),detailed.get("text_sources"));
    }

    @Test public void completeActionOrderAndMixedPackedNullLabelsRoundTrip() {
        List<Object> nodes=new ArrayList<>();
        for(int i=0;i<12;i++)nodes.add(map("id","c"+i,"role","button","label",i==3?null:"Choice "+i));
        Map<String,Object> capture=map("ui",map("controls",nodes),"actions",Arrays.asList(
                map("action","ui.activate","control","c9","gestures",Arrays.asList("long")),map("action","wait"),
                map("action","ui.activate","control","c1","gestures",Arrays.asList("click")),
                map("action","ui.activate","control","c9","gestures",Arrays.asList("click"))));
        Map<String,Object> full=object(CompactProtocol.expandStructures(CompactProtocol.project(capture,false,true)));
        Map<String,Object> play=object(CompactProtocol.project(capture,false));
        assertTrue(object(play.get("ui")).containsKey("node_templates"));
        assertEquals(full,CompactProtocol.expandStructures(play));
        play=object(CompactProtocol.expandStructures(play));
        assertEquals(Arrays.asList("c9",null,"c1","c9"),Arrays.asList(
                object(((List<?>)play.get("acts")).get(0)).get("ctl"),object(((List<?>)play.get("acts")).get(1)).get("ctl"),
                object(((List<?>)play.get("acts")).get(2)).get("ctl"),object(((List<?>)play.get("acts")).get(3)).get("ctl")));
    }

    private static Map<String,Object> node(Map<String,Object> data,int index) {return object(((List<?>)object(data.get("ui")).get("nodes")).get(index));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
}
