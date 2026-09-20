package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class CompactProtocolV6Test {
    @Test public void shapesAndOperationDefinitionsRoundTripEveryValueAndFieldOrder() {
        List<Object> nodes=new ArrayList<>();
        for(int i=0;i<20;i++)nodes.add(map("id","c"+i,"role","button","label","Choice "+i,"enabled",i!=1,
                "unknown",Arrays.asList(null,false,0),"ops",Arrays.asList(map("op","click","gestures",Arrays.asList("click","right","middle","long")))));
        Map<String,Object> input=map("ui",map("nodes",nodes));String before=JsonCodec.encode(input);
        CompactStructures.compact(input);
        assertTrue(object(input.get("ui")).containsKey("op_defs"));
        assertTrue(object(input.get("ui")).containsKey("node_shapes"));
        assertEquals(before,JsonCodec.encode(CompactProtocol.expandStructures(input)));
    }

    @Test public void singleUseOperationsAndSmallStructuresRemainInline() {
        Map<String,Object> input=map("ui",map("nodes",Arrays.asList(map("id","c1","ops",Arrays.asList(map("op","back"))))));
        String before=JsonCodec.encode(input);CompactStructures.compact(input);
        assertEquals(before,JsonCodec.encode(input));
    }

    @Test public void metadataKeepsActualPathsExpandedAndOpaqueHistoryUntouched() {
        Map<String,Object> ui=largeUi();object(((List<?>)ui.get("nodes")).get(3)).put("pres",map("st","partial","diag",Arrays.asList(map("field","label","code","clipped_text"))));
        Map<String,Object> input=map("ui",ui,"before",map("ui",largeUi()),"after",map("ui",largeUi()),"reply",map("ui",largeUi()),"raw",map("ui",largeUi()));
        String before=JsonCodec.encode(input);CompactStructures.compact(input);
        assertTrue(object(input.get("ui")).containsKey("node_shapes"));
        assertTrue(((List<?>)object(input.get("ui")).get("nodes")).get(3) instanceof Map);
        assertEquals(before,JsonCodec.encode(CompactProtocol.expandStructures(input)));
    }

    @Test public void passiveIdsRemainEvenWithoutReferencesOrIndependentFields() {
        Map<String,Object> input=map("ui",map("nodes",Arrays.asList(
                map("id","c1","role","text","text","Independent text"),
                map("id","c2","role","text","text","Parent"),
                map("id","c3","role","button","parent","c2","ops",Arrays.asList(map("op","click"))),
                map("id","c4","role","text","text","Referenced by action"),
                map("id","c5","role","text","text","State","extra",0))),"acts",Arrays.asList(map("op","select","ctl","c4")));
        CompactStructures.compact(input);List<?> nodes=(List<?>)object(object(CompactProtocol.expandStructures(input)).get("ui")).get("nodes");
        for(int i=0;i<nodes.size();i++)assertEquals("c"+(i+1),object(nodes.get(i)).get("id"));
    }

    @Test public void mixedRowsKeepProtectedNodeIndexesAndWireDiagnosticPaths() {
        List<Object> nodes=new ArrayList<>();
        for(int i=0;i<16;i++)nodes.add(map("id","c"+i,"role","button","label","Visible "+i,"unknown",Arrays.asList(null,false,0)));
        Map<String,Object> protectedNode=object(nodes.get(3));
        protectedNode.put("text_sources",map("label",map("kind","future_source","description","Source AST stays unchanged")));
        protectedNode.put("text_origins",map("label",Arrays.asList("external")));
        protectedNode.put("text_diagnostics",map("label","clipped_text"));
        Map<String,Object> snapshot=map("ui",map("controls",nodes));
        Map<String,Object> reply=CompactProtocol.success("q","s1","completed",snapshot,true,false);
        Map<String,Object> ui=object(object(reply.get("data")).get("ui"));
        assertTrue(ui.containsKey("node_shapes"));
        List<?> wireNodes=(List<?>)ui.get("nodes");
        assertTrue(wireNodes.get(0) instanceof List);assertTrue(wireNodes.get(3) instanceof Map);
        assertEquals("c3",object(wireNodes.get(3)).get("id"));
        assertEquals(map("label",Arrays.asList("external")),object(wireNodes.get(3)).get("text_origins"));
        assertEquals(protectedNode.get("text_sources"),object(wireNodes.get(3)).get("text_sources"));
        assertEquals(Arrays.asList(map("field","$.data.ui.nodes[3].label","code","clipped_text")),object(reply.get("pres")).get("diag"));
        assertEquals("Visible 3",object(wireNodes.get(3)).get("label"));
        assertEquals(CompactProtocol.success("q","s1","completed",snapshot,true,false,true),CompactProtocol.expandStructures(reply));
    }

    @Test public void labelsUseCurrentCapturedIdentityAndOfficialEnglishTitleRule() {
        Map<String,Object> inventory=map("locator","bag.0","name","scroll of upgrade","quantity",1);
        Map<String,Object> ui=new UiProjectionHints(Collections.singletonMap("c1",new UiProjectionHints.Node(false,Collections.emptyList(),"bag.0",Collections.emptyMap())))
                .attach(map("controls",Arrays.asList(map("id","c1","role","button","label","Scroll of Upgrade"))));
        Map<String,Object> input=map("inventory",Arrays.asList(inventory),"ui",ui);
        Map<String,Object> play=object(CompactProtocol.project(input,false));
        assertEquals(1,node(play).get("label"));
        assertEquals("Scroll of Upgrade",node(object(CompactProtocol.expandStructures(play))).get("label"));
        inventory.put("name","Scroll of Upgrade");assertEquals(0,node(object(CompactProtocol.project(input,false))).get("label"));
        inventory.put("name","Unknown Scroll");assertEquals("Scroll of Upgrade",node(object(CompactProtocol.project(input,false))).get("label"));
        assertEquals("Scroll of Upgrade",node(object(CompactProtocol.project(map("ui",ui),false))).get("label"));
        assertEquals("Scroll of Upgrade",node(object(CompactProtocol.project(input,false,true))).get("label"));
    }

    @Test public void savedIndexAndBindingInheritanceKeepDistinctSavesAndCrossScopeOrigins() {
        Map<String,Object> first=map("receipt_id","p1","scope_id","s2","origin_scope_id","s1","origin_request_id","t1.4");
        Map<String,Object> second=map("receipt_id","p2","scope_id","s2","origin_scope_id","s2","origin_request_id","t1.4");
        Map<String,Object> input=map("scope_id","s2","state_version","a1","continuous_activity",map("kind","rest","target_id","t1.4","state_version","a1"),
                "actions",Arrays.asList(map("action","action.cancel","target_id","t1.4","state_version","a1")),
                "persistence",map("last_save",second,"saves_during_request",Arrays.asList(first,second)));
        Map<String,Object> reply=CompactProtocol.success("t1.4","s1","in_progress",input,true,false);
        Map<String,Object> data=object(reply.get("data"));assertFalse(object(data.get("activity")).containsKey("rev"));
        assertEquals(map("op","cancel"),((List<?>)data.get("acts")).get(0));
        Map<String,Object> persistence=object(data.get("persistence"));assertEquals(1,persistence.get("saved"));
        assertEquals("s1",object(((List<?>)persistence.get("saves")).get(0)).get("src_s"));
        assertFalse(object(((List<?>)persistence.get("saves")).get(1)).containsKey("src_s"));
        Map<String,Object> expanded=object(CompactProtocol.expandStructures(reply));
        Map<String,Object> full=CompactProtocol.success("t1.4","s1","in_progress",input,true,false,true);
        assertEquals(full,expanded);
    }

    @Test public void charactersShareOnlyTheirEntireExactDescriptor() {
        Map<String,Object> first=map("cell",1,"kind","character","name","Wraith","description","A long rendered warning for this exact creature state.","buffs",Arrays.asList(map("name","Poisoned","value",0)));
        Map<String,Object> second=new LinkedHashMap<>(first);second.put("cell",2);
        Map<String,Object> third=new LinkedHashMap<>(first);third.put("cell",3);third.put("alignment","ally");
        Map<String,Object> play=object(CompactProtocol.project(map("visible_entities",Arrays.asList(first,second,third)),false));
        assertEquals(1,((List<?>)play.get("entity_defs")).size());
        assertEquals(map("cell",1,"def",0),((List<?>)play.get("entities")).get(0));
        assertTrue(object(((List<?>)play.get("entities")).get(2)).containsKey("alignment"));
    }

    @Test public void malformedDefinitionsAndAmbiguousItemBindingsFailClosed() {
        for(Object invalid:Arrays.asList(-1,2,0.5,true,null)) {
            Map<String,Object> ui=map("node_shapes",Arrays.asList(Arrays.asList("role")),"nodes",Arrays.asList(Arrays.asList(invalid,"button")));
            assertThrows(IllegalArgumentException.class,()->CompactProtocol.expandStructures(map("ui",ui)));
        }
        assertThrows(IllegalArgumentException.class,()->CompactProtocol.expandStructures(map("ui",map("node_shapes",Arrays.asList(Arrays.asList("x","x")),"nodes",Arrays.asList(Arrays.asList(0,1,2))))));
        assertThrows(IllegalArgumentException.class,()->CompactProtocol.expandStructures(map("ui",map("nodes",Arrays.asList(map("loc","bag.0","label",1))))));
        assertThrows(IllegalArgumentException.class,()->CompactProtocol.expandStructures(map("inv",Arrays.asList(map("loc","bag.0","name","One"),map("loc","bag.0","name","Two")),"ui",map("nodes",Arrays.asList(map("loc","bag.0","label",1))))));
    }

    private static Map<String,Object> largeUi() {List<Object> nodes=new ArrayList<>();for(int i=0;i<12;i++)nodes.add(map("id","c"+i,"role","button","label","Choice","ops",Arrays.asList(map("op","click"))));return map("nodes",nodes);}
    private static Map<String,Object> node(Map<String,Object> data) {return object(((List<?>)object(data.get("ui")).get("nodes")).get(0));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
}
