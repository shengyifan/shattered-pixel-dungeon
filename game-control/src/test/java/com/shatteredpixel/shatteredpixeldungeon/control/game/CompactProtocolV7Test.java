package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Complete synthetic public captures: every table and operation binding is local to one snapshot. */
public class CompactProtocolV7Test {
    @Test public void sharesExactOperationsWithoutReorderingOrMergingOccurrences() {
        Map<String,Object> click=map("op","click","ctl","c1","label","Choose","gestures",Arrays.asList("click","long"),"future",Arrays.asList(null,false,0));
        Map<String,Object> inherited=new LinkedHashMap<>(click);inherited.remove("ctl");
        Map<String,Object> input=map("acts",Arrays.asList(click,map("op","wait"),click),
                "ui",map("nodes",Arrays.asList(map("id","c1","ops",Arrays.asList(inherited,inherited)))));
        Object expected=CompactStructures.expandPure(input);
        CompactStructures.compact(input,true,false);
        assertEquals(Arrays.asList(0,0),node(input,0).get("ops"));
        assertEquals(3,((List<?>)input.get("acts")).size());
        assertEquals(expected,CompactStructures.expandPure(input));
        assertFalse(object(input.get("ui")).containsKey("op_defs"));
    }

    @Test public void matchingRequiresAllFieldsAndTheCurrentControlNotJustName() {
        Map<String,Object> input=map("acts",Arrays.asList(map("op","value","ctl","c1","range",Arrays.asList(0,9)),
                map("op","click","ctl","c2","label","Same")),"ui",map("nodes",Arrays.asList(
                map("id","c1","ops",Arrays.asList(map("op","value","range",Arrays.asList(0,8)),map("op","click","label","Same"))),
                map("id","c2","ops",Arrays.asList(map("op","click","ctl","c2","label","Same"))))));
        Object expected=CompactStructures.expandPure(input);
        CompactStructures.compact(input,true,false);
        assertEquals(expected,input);
    }

    @Test public void allThreeTablesPreserveNullFalseZeroMissingAndRecordOrder() {
        Map<String,Object> input=records("c");
        Object before=CompactStructures.expandPure(input);
        int size=bytes(input);CompactStructures.compact(input);
        assertTrue(input.containsKey("act_templates"));assertTrue(input.containsKey("inv_templates"));
        assertTrue(object(input.get("ui")).containsKey("node_templates"));
        assertEquals(before,CompactStructures.expandPure(input));assertTrue(bytes(input)<size);
        Map<String,Object> template=object(((List<?>)input.get("inv_templates")).get(0));
        assertEquals(Arrays.asList(null,false,0),object(template.get("common")).get("future"));
        assertFalse(object(template.get("common")).containsKey("level"));
    }

    @Test public void singletonAndUnprofitableGroupsStayObjectsAndAcceptedGroupsAreDeterministic() {
        Map<String,Object> tiny=map("inv",Arrays.asList(map("loc","a"),map("loc","b")));
        Object original=CompactStructures.expandPure(tiny);CompactStructures.compact(tiny);
        assertEquals(original,tiny);
        Map<String,Object> first=records("c"),second=records("c");
        CompactStructures.compact(first);CompactStructures.compact(second);
        assertEquals(JsonCodec.encode(first),JsonCodec.encode(second));
    }

    @Test public void groupingUsesOrderedKeySetsAndNeverFillsMissingValues() {
        List<Object> items=new ArrayList<>();
        for(int index=0;index<12;index++)items.add(map("loc","bag."+index,"name","Repeated long public name","level",null));
        items.add(map("loc","bag.missing","name","Repeated long public name"));
        items.add(map("name","Repeated long public name","loc","bag.reordered","level",null));
        Map<String,Object> input=map("inv",items);Object expected=CompactStructures.expandPure(input);
        CompactStructures.compact(input);
        assertTrue(((List<?>)input.get("inv")).get(0) instanceof List);
        assertTrue(((List<?>)input.get("inv")).get(12) instanceof Map);
        assertTrue(((List<?>)input.get("inv")).get(13) instanceof Map);
        assertEquals(expected,CompactStructures.expandPure(input));
    }

    @Test public void equalityIsJsonTypeSensitiveForBooleansAndFloatingPoint() {
        List<Object> records=new ArrayList<>();
        for(int index=0;index<18;index++)records.add(map("loc","bag."+index,"description","Long public description retained exactly",
                "value",index%3==0?Boolean.FALSE:index%3==1?Integer.valueOf(0):Double.valueOf(0.0)));
        Map<String,Object> input=map("inv",records);Object expected=CompactStructures.expandPure(input);
        CompactStructures.compact(input);
        Map<String,Object> template=object(((List<?>)input.get("inv_templates")).get(0));
        assertFalse(object(template.get("common")).containsKey("value"));
        assertEquals(expected,CompactStructures.expandPure(input));
    }

    @Test public void everyViewUsesTemplatesButKeepsItsOwnFieldProjectionAndSources() {
        Map<String,Object> canonical=canonicalRecords();String original=JsonCodec.encode(canonical);
        for(boolean full:Arrays.asList(false,true))for(boolean sources:Arrays.asList(false,true)) {
            Object expected=CompactProtocol.projectExpanded(canonical,sources,full);
            Map<String,Object> encoded=object(CompactProtocol.project(canonical,sources,full));
            assertTrue(encoded.containsKey("inv_templates"));assertTrue(encoded.containsKey("act_templates"));
            assertTrue(object(encoded.get("ui")).containsKey("node_templates"));
            assertEquals(expected,CompactStructures.expandPure(encoded));
            Map<String,Object> item=object(((List<?>)object(CompactStructures.expandPure(encoded)).get("inv")).get(0));
            assertEquals(sources||full,item.containsKey("qty"));
        }
        assertEquals(original,JsonCodec.encode(canonical));
    }

    @Test public void protectedNodesOperationsAndAncestorsKeepAddressablePaths() {
        Map<String,Object> canonical=canonicalRecords();
        Map<String,Object> action=object(((List<?>)canonical.get("actions")).get(3));
        action.put("text_diagnostics",map("control","binding_warning","label","label_warning"));
        action.put("text_sources",map("control",map("kind","future","value","c3")));
        Map<String,Object> reply=CompactProtocol.success("q","s1","completed",canonical,true,false);
        Map<String,Object> data=object(reply.get("data"));
        assertTrue(((List<?>)object(data.get("ui")).get("nodes")).get(3) instanceof Map);
        Map<String,Object> retained=node(data,3);
        assertEquals("c3",object(((List<?>)retained.get("ops")).get(0)).get("ctl"));
        assertEquals("$.data.ui.nodes[3].ops[0].ctl",object(((List<?>)object(reply.get("pres")).get("diag")).get(0)).get("field"));
        assertTrue(((List<?>)data.get("acts")).get(3) instanceof Map);
        Map<String,Object> ancestor=records("c");ancestor.put("pres",map("st","partial","diag",Arrays.asList(map("field","ui.nodes[0].label","code","warning"))));
        Object expected=CompactStructures.expandPure(ancestor);CompactStructures.compact(ancestor);
        assertEquals(expected,ancestor);
    }

    @Test public void historySnapshotsHaveIndependentTablesAndNeverInheritLiveBindings() {
        Map<String,Object> before=records("a"),after=records("b");
        before.put("activity",map("rid","old"));before.put("saved",map("sid","p1"));
        Map<String,Object> opaque=map("ui",map("node_shapes",Arrays.asList(Arrays.asList("id")),"nodes",Arrays.asList(Arrays.asList(0,"opaque"))));
        Map<String,Object> frame=map("v",8,"s","s-live","rev","r-live","data",map("before",before,"after",after,"raw",opaque,"reply",opaque));
        Object expected=CompactStructures.expandPure(frame);CompactStructures.compact(frame);
        Map<String,Object> data=object(frame.get("data"));
        assertTrue(object(data.get("before")).containsKey("act_templates"));
        assertTrue(object(data.get("after")).containsKey("act_templates"));
        assertSame(opaque,data.get("raw"));assertSame(opaque,data.get("reply"));
        assertEquals(expected,CompactStructures.expandPure(frame));
        Map<String,Object> expandedBefore=object(object(object(CompactStructures.expand(frame)).get("data")).get("before"));
        assertFalse(object(expandedBefore.get("activity")).containsKey("rev"));
        assertFalse(object(expandedBefore.get("saved")).containsKey("s"));
        assertEquals("a0",node(expandedBefore,0).get("id"));
    }

    @Test public void missingSnapshotActionTableCannotBorrowItsParents() {
        Map<String,Object> nested=map("ui",map("nodes",Arrays.asList(map("id","c1","ops",Arrays.asList(0)))));
        Map<String,Object> input=map("acts",Arrays.asList(map("op","click","ctl","c1")),"before",nested);
        assertThrows(IllegalArgumentException.class,()->CompactStructures.expandPure(input));
    }

    @Test public void extensionObjectsAndSourceAstsAreNeverInterpretedAsObservations() {
        Map<String,Object> extension=map("inv",Arrays.asList(Arrays.asList(-1)),"acts",Arrays.asList(false),
                "ui",map("node_shapes",Arrays.asList(false),"nodes",Arrays.asList(Arrays.asList(-1))));
        Map<String,Object> input=records("c");input.put("extension",extension);input.put("text_ast",extension);
        Object expected=CompactStructures.expandPure(input);CompactStructures.compact(input);
        assertEquals(extension,input.get("extension"));assertEquals(expected,CompactStructures.expandPure(input));
    }

    @Test public void sharedJavaIdentitiesCannotMutateOpaqueOrUnknownBranches() {
        Map<String,Object> snapshot=records("c");String untouched=JsonCodec.encode(snapshot);
        Map<String,Object> frame=map("before",snapshot,"after",snapshot,"raw",snapshot,"reply",snapshot,"extension",snapshot.get("ui"));
        CompactStructures.compact(frame);
        assertSame(snapshot,frame.get("raw"));assertSame(snapshot,frame.get("reply"));
        assertEquals(untouched,JsonCodec.encode(snapshot));
        assertSame(snapshot.get("ui"),frame.get("extension"));
        assertTrue(object(frame.get("before")).containsKey("act_templates"));
        assertTrue(object(frame.get("after")).containsKey("act_templates"));
        Map<String,Object> root=records("r");Object originalUi=root.get("ui");root.put("extension",originalUi);
        String originalText=JsonCodec.encode(originalUi);CompactStructures.compact(root);
        assertEquals(originalText,JsonCodec.encode(root.get("extension")));
    }

    @Test public void explicitNullsRemainDataNotInventedCapabilities() {
        Map<String,Object> input=map("acts",null,"inv",null,"ui",map("nodes",Arrays.asList(map("id","c1","ops",null,"label",null))));
        Object original=CompactStructures.expandPure(input);CompactStructures.compact(input);
        assertEquals(original,CompactStructures.expandPure(input));
        assertTrue(node(input,0).containsKey("ops"));assertNull(node(input,0).get("ops"));
    }

    @Test public void reservedTemplateCollisionsFailBeforeAnyMutationEvenWhenProtected() {
        for(String field:Arrays.asList("act_templates","inv_templates","node_templates"))
            for(Object collision:Arrays.asList(Collections.emptyList(),Collections.emptyMap(),
                    Arrays.asList(map("common",map("role","button"),"fields",Arrays.asList("id")))))
                for(boolean protectedRoot:Arrays.asList(false,true)) {
                    Map<String,Object> input=records("c");
                    if(field.equals("node_templates"))object(input.get("ui")).put(field,collision);
                    else input.put(field,collision);
                    if(protectedRoot)input.put("pres",map("st","partial","diag",Arrays.asList(map("field","ui.nodes[0].label","code","warning"))));
                    String before=JsonCodec.encode(input);
                    assertThrows(IllegalArgumentException.class,()->CompactStructures.compact(input));
                    assertEquals(before,JsonCodec.encode(input));
                }
        Map<String,Object> earlier=records("a"),later=records("b");later.put("inv_templates",Collections.emptyList());
        Map<String,Object> envelope=map("v",8,"data",map("before",earlier,"after",later));
        String before=JsonCodec.encode(envelope);
        assertThrows(IllegalArgumentException.class,()->CompactStructures.compact(envelope));
        assertEquals(before,JsonCodec.encode(envelope));
    }

    @Test public void reservedNamesInsideOpaqueAndUnknownObjectsAreUnrelatedPublicContent() {
        Map<String,Object> payload=map("act_templates",Collections.emptyList(),"inv_templates",map("unknown",true),
                "ui",map("node_templates",Arrays.asList(map("common",Collections.emptyMap(),"fields",Collections.emptyList()))));
        Map<String,Object> input=records("c");
        for(String field:Arrays.asList("raw","reply","extension"))input.put(field,payload);
        Object expected=CompactStructures.expandPure(input);CompactStructures.compact(input);
        assertTrue(input.containsKey("act_templates"));assertEquals(expected,CompactStructures.expandPure(input));
        Map<String,Object> sourced=map("text_sources",map("kind","future","payload",payload));
        String original=JsonCodec.encode(sourced);CompactStructures.compact(sourced);
        assertEquals(original,JsonCodec.encode(sourced));
    }

    @Test public void decoderRejectsInvalidTemplateDefinitionsIndexesAndRowWidths() {
        for(Object index:Arrays.asList(-1,1,0.0,true,null)) {
            Map<String,Object> input=map("inv_templates",Arrays.asList(map("common",map("qty",1),"fields",Arrays.asList("loc"))),"inv",Arrays.asList(Arrays.asList(index,"bag.0")));
            assertThrows(IllegalArgumentException.class,()->CompactStructures.expandPure(input));
        }
        for(Object template:Arrays.asList(map("common",map("loc","x"),"fields",Arrays.asList("loc")),
                map("common",Collections.emptyMap(),"fields",Arrays.asList("loc","loc")),
                map("common",Collections.emptyMap(),"fields",Arrays.asList(1)),
                map("common",Collections.emptyMap(),"fields",Arrays.asList("loc"),"extra",true),
                map("fields",Arrays.asList("loc")))) {
            assertThrows(IllegalArgumentException.class,()->CompactStructures.expandPure(map("inv_templates",Arrays.asList(template),"inv",Collections.emptyList())));
        }
        assertThrows(IllegalArgumentException.class,()->CompactStructures.expandPure(map("inv_templates",Arrays.asList(map("common",Collections.emptyMap(),"fields",Arrays.asList("loc"))),"inv",Arrays.asList(Arrays.asList(0)))));
        assertThrows(IllegalArgumentException.class,()->CompactStructures.expandPure(map("inv_templates",Collections.emptyList())));
        assertThrows(IllegalArgumentException.class,()->CompactStructures.expandPure(map("ui",map("node_shapes",Collections.emptyList(),"nodes",Collections.emptyList()))));
    }

    @Test public void decoderRejectsInvalidReferencesAndDoesNotGuessOrReuseBindings() {
        for(Object reference:Arrays.asList(-1,1,0.0,true,null)) {
            Map<String,Object> frame=map("acts",Arrays.asList(map("op","click","ctl","c1")),"ui",map("nodes",Arrays.asList(map("id","c1","ops",Arrays.asList(reference)))));
            assertThrows(IllegalArgumentException.class,()->CompactStructures.expandPure(frame));
        }
        for(Map<String,Object> action:Arrays.asList(map("op","click","ctl","other"),map("op","click"),map("ctl","c1"),
                map("op","click","ctl","c1","text_origins",map("ctl",Arrays.asList("external"))))) {
            Map<String,Object> frame=map("acts",Arrays.asList(action),"ui",map("nodes",Arrays.asList(map("id","c1","ops",Arrays.asList(0)))));
            assertThrows(IllegalArgumentException.class,()->CompactStructures.expandPure(frame));
        }
        assertThrows(IllegalArgumentException.class,()->CompactStructures.expandPure(map("ui",map("nodes",Arrays.asList(map("id","c1","ops",0))))));
    }

    @Test public void bindingsAreCompactedBeforeTemplatesAndDecodedBeforeOperations() {
        Map<String,Object> canonical=canonicalRecords();canonical.put("scope_id","s1");canonical.put("state_version","a1");
        canonical.put("continuous_activity",map("kind","rest","state_version","a1","target_id","original"));
        List<Object> actions=new ArrayList<>((List<?>)canonical.get("actions"));actions.add(map("action","action.cancel","state_version","a1","target_id","original"));canonical.put("actions",actions);
        Map<String,Object> reply=CompactProtocol.success("query","s1","in_progress",canonical,true,false);
        Map<String,Object> raw=object(reply.get("data"));assertTrue(raw.containsKey("act_templates"));
        Map<String,Object> decoded=object(object(CompactStructures.expand(reply)).get("data"));
        Map<String,Object> cancel=object(((List<?>)decoded.get("acts")).get(actions.size()-1));
        assertEquals("a1",cancel.get("rev"));assertEquals("original",cancel.get("rid"));
        assertEquals("a1",object(decoded.get("activity")).get("rev"));
    }

    private static Map<String,Object> records(String prefix) {
        List<Object> actions=new ArrayList<>(),items=new ArrayList<>(),nodes=new ArrayList<>();
        for(int index=0;index<16;index++) {
            actions.add(map("op","click","ctl",prefix+index,"label","Choice "+index,"gestures",Arrays.asList("click","long")));
            items.add(map("loc","bag."+index,"name","Public item "+index,"desc","Complete long public warning shared only when identical","future",Arrays.asList(null,false,0)));
            nodes.add(map("id",prefix+index,"role","button","label","Choice "+index,"parent","container","enabled",true,
                    "ops",Arrays.asList(map("op","click","label","Choice "+index,"gestures",Arrays.asList("click","long")))));
        }
        return map("ui",map("nodes",nodes),"acts",actions,"inv",items);
    }

    private static Map<String,Object> canonicalRecords() {
        List<Object> actions=new ArrayList<>(),items=new ArrayList<>(),nodes=new ArrayList<>();
        for(int index=0;index<16;index++) {
            actions.add(map("action","ui.activate","control","c"+index,"label","Choice "+index,"gestures",Arrays.asList("click","long")));
            items.add(map("locator","bag."+index,"name","Public item "+index,"description","Complete public warning","quantity",1,"available",true));
            nodes.add(map("id","c"+index,"role","button","label","Choice "+index,"parent","container","enabled",true));
        }
        return map("ui",map("controls",nodes),"actions",actions,"inventory",items);
    }
    private static int bytes(Object value) {return JsonCodec.encode(value).getBytes(StandardCharsets.UTF_8).length;}
    private static Map<String,Object> node(Map<String,Object> value,int index) {return object(((List<?>)object(value.get("ui")).get("nodes")).get(index));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
}
