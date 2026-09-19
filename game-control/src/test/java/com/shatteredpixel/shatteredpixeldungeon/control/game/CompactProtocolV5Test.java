package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.WireNames;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Protocol 5 self-contained decoding and metadata invariants, using public fixture values only. */
public class CompactProtocolV5Test {
    @Test public void aliasesApplyToFieldsAndMetadataButNeverEnumsAstOrOpaqueValues() {
        Map<String,Object> input=new LinkedHashMap<>();
        String[][] aliases={{"max_experience","mxp"},{"max_hp","ht"},{"subclass_name","sub_name"},{"talent_points_available","tp"},
                {"details_via","via"},{"shortcut_action","shortcut"},{"cell_prompt","prompt"},{"continuous_activity","activity"},
                {"snapshot_status","snap"},{"inspected_item","item_info"},{"saves_during_request","saves"},{"last_save","saved"},
                {"receipt_id","sid"},{"origin_scope_id","src_s"},{"origin_request_id","src_id"},{"occurred_at","at"}};
        for(String[] pair:aliases){input.put(pair[0],pair[0]);assertEquals(pair[1],WireNames.field(pair[0]));}
        Map<String,Object> ast=map("kind","future","max_hp",20,"description","AST data");
        input.put("text_sources",map("continuous_activity.max_hp",ast));
        input.put("text_origins",map("inspected_item.max_hp",Arrays.asList("external")));
        input.put("text_diagnostics",map("subclass_name","display_warning"));
        input.put("phase","continuous_activity");
        for(String field:Arrays.asList("schema","raw","reply","original_payload"))input.put(field,map("max_hp",20));
        Map<String,Object> output=object(CompactProtocol.project(input,false));
        for(String[] pair:aliases){assertEquals(pair[0],output.get(pair[1]));assertFalse(output.containsKey(pair[0]));}
        assertEquals("continuous_activity",output.get("phase"));
        assertEquals(ast,object(output.get("text_sources")).get("activity.ht"));
        assertEquals(Arrays.asList("external"),object(output.get("text_origins")).get("item_info.ht"));
        assertEquals(Arrays.asList(map("field","sub_name","code","display_warning")),object(output.get("pres")).get("diag"));
        for(String field:Arrays.asList("schema","raw","reply","original_payload"))assertSame(input.get(field),output.get(field));
        assertEquals(6,CompactProtocol.failure("q","s","FAILURE").get("v"));
        assertEquals(WireNames.fields(),CompactProtocol.info().get("aliases"));
    }

    @Test public void ordinaryRenderedTreesDisappearAndRecursiveExceptionsRemain() {
        Map<String,Object> ordinary=map("kind","displayed","value",map("kind","strip_prefix","prefix","!", "value",
                map("kind","concat","parts",Arrays.asList(map("kind","literal","origin","literal","value","!"),
                        map("kind","decimal","formatted","2.5"),map("kind","language","code","en")))));
        Map<String,Object> partial=map("kind","resource","visibility","partial","args",Collections.emptyList());
        Map<String,Object> external=map("kind","format","args",Arrays.asList(map("kind","literal","origin","external","value","Name")));
        Map<String,Object> input=map("text","2.5","options",Arrays.asList("Name","Partial"),"text_sources",map("text",ordinary,"options",Arrays.asList(external,partial)));
        Map<String,Object> output=object(CompactProtocol.project(input,false));
        assertEquals(Collections.singleton("options"),object(output.get("text_sources")).keySet());
        assertEquals(map("options",Arrays.asList("external")),output.get("text_origins"));
        assertEquals(input.get("text_sources"),object(CompactProtocol.project(input,true)).get("text_sources"));
        input.put("text_origins",map("options",Arrays.asList("user"),"text",Arrays.asList("external")));
        assertEquals(map("options",Arrays.asList("external","user"),"text",Arrays.asList("external")),
                object(CompactProtocol.project(input,false)).get("text_origins"));
    }

    @Test public void localDefaultsStayScopedAndSourceProtectedNullsRemain() {
        Map<String,Object> ordinary=item("one");ordinary.put("details_via","ui.activate");
        Map<String,Object> unusual=item("two");unusual.put("details_via","cell.select");
        Map<String,Object> protectedItem=item("three");protectedItem.put("details_via","ui.activate");
        protectedItem.put("text_sources",map("details_via",map("kind","literal","origin","external","value","click")));
        Map<String,Object> input=map("inventory",Arrays.asList(ordinary,unusual,protectedItem),"ui",map("modal",false,"inspected_item",null),
                "unrelated",map("modal",false,"inspected_item",null,"details_via","ui.activate"),"slots",Arrays.asList(null,false,0));
        Map<String,Object> play=object(CompactProtocol.project(input,false));List<?> inventory=(List<?>)play.get("inv");
        assertFalse(object(inventory.get(0)).containsKey("via"));assertEquals("cell",object(inventory.get(1)).get("via"));
        assertEquals("click",object(inventory.get(2)).get("via"));assertEquals(map("via",Arrays.asList("external")),object(inventory.get(2)).get("text_origins"));
        assertEquals(Collections.emptyMap(),play.get("ui"));assertEquals(Arrays.asList(null,false,0),play.get("slots"));
        assertEquals(map("modal",false,"item_info",null,"via","click"),play.get("unrelated"));
        for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> full=object(CompactProtocol.project(input,sources,true));
            assertEquals(map("modal",false,"item_info",null),full.get("ui"));
            assertEquals("click",object(((List<?>)full.get("inv")).get(0)).get("via"));
        }
        Map<String,Object> ui=object(input.get("ui"));ui.put("text_sources",map("inspected_item",map("kind","unavailable")));
        assertTrue(object(object(CompactProtocol.project(input,false)).get("ui")).containsKey("item_info"));
    }

    @Test public void protectedArrayAnnotationsKeepTheirAddressedDefaultsAndUiIndexes() {
        Map<String,Object> external=map("kind","literal","origin","external","value","Public evidence");
        Map<String,Object> record=item("one");record.put("description","Keep this description");
        Map<String,Object> input=map("inventory",Arrays.asList(record),"ui",map("controls",Arrays.asList(
                map("id","empty","role","text","enabled",true),map("id","text","role","text","text","Public evidence"))),
                "text_sources",map("inventory",Arrays.asList(map("quantity",external)),"ui",map("controls",Arrays.asList(null,map("text",external)))));
        Map<String,Object> output=object(CompactProtocol.project(input,false));Map<String,Object> item=object(((List<?>)output.get("inv")).get(0));
        assertEquals(1,item.get("qty"));assertEquals("Keep this description",item.get("desc"));assertEquals(Arrays.asList("empty","text"),ids(output));
        assertEquals(true,node(output,"empty").get("enabled"));
        assertEquals(map("inv",Arrays.asList("external"),"ui",Arrays.asList("external")),output.get("text_origins"));
    }

    @Test public void entityDefinitionsRoundTripEveryDescriptorFieldAndLocalMetadata() {
        Map<String,Object> a=entity(3,"trap","A long dangerous trap description that repeats exactly and saves bytes.");
        a.put("extra",map("value",Arrays.asList(1,null,false)));a.put("text_diagnostics",map("description","clipped_text"));
        Map<String,Object> b=new LinkedHashMap<>(a);b.put("cell",7);
        Map<String,Object> c=entity(9,"container","A suspicious container with a long warning that remains publicly visible.");
        Map<String,Object> d=new LinkedHashMap<>(c);d.put("cell",13);
        Map<String,Object> unique=entity(19,"trap","Different description");
        Map<String,Object> input=map("visible_entities",Arrays.asList(a,c,b,d,unique));String before=JsonCodec.encode(input);
        Map<String,Object> play=object(CompactProtocol.project(input,false));
        assertEquals(2,((List<?>)play.get("entity_defs")).size());
        assertEquals(Arrays.asList(map("cell",3,"def",0),map("cell",9,"def",1),map("cell",7,"def",0),map("cell",13,"def",1),
                map("cell",19,"kind","trap","name","Public trap","desc","Different description")),play.get("entities"));
        assertEquals(object(CompactProtocol.project(input,false,true)).get("entities"),expandEntities(play));
        assertEquals(before,JsonCodec.encode(input));
        assertTrue(bytes(play)<bytes(object(CompactProtocol.project(input,false,true))));
        Map<String,Object> reply=CompactProtocol.success("q","scope","completed",input,true,false);
        assertEquals(Arrays.asList(map("field","$.data.entity_defs[0].desc","code","clipped_text")),object(reply.get("pres")).get("diag"));
        for(Object raw:(List<?>)play.get("entity_defs"))assertFalse(object(raw).containsKey("cell"));
    }

    @Test public void definitionsRequireCompleteEqualityAndActualByteSavings() {
        Map<String,Object> a=entity(1,"trap","A repeated long warning that is large enough to be worthwhile.");
        Map<String,Object> b=new LinkedHashMap<>(a);b.put("cell",2);b.put("extra",true);
        Map<String,Object> c=new LinkedHashMap<>(a);c.put("cell",3);c.put("text_sources",map("description",map("kind","literal","origin","external","value",a.get("description"))));
        Map<String,Object> d=new LinkedHashMap<>(a);d.put("cell",4);d.put("text_sources",map("description",map("kind","literal","origin","user","value",a.get("description"))));
        assertFalse(object(CompactProtocol.project(map("visible_entities",Arrays.asList(a,b,c,d)),false)).containsKey("entity_defs"));
        assertFalse(object(CompactProtocol.project(map("visible_entities",Arrays.asList(map("cell",1,"kind","trap"),map("cell",2,"kind","trap"))),false)).containsKey("entity_defs"));
        assertFalse(object(CompactProtocol.project(map("visible_entities",Arrays.asList(map("cell",1,"kind","mob","description","Long value"),map("cell",2,"kind","mob","description","Long value"))),false)).containsKey("entity_defs"));
    }

    @Test public void effectsUseCompleteOrderedListsAndFirstOccurrenceDefinitions() {
        Object fire=map("type","fire","description","A detailed description of flames spreading across the visible floor.");
        Object gas=map("type","gas","description","Visible dangerous gas and its exact public effects.","text_diagnostics",map("description","clipped_text"));
        List<Object> common=Arrays.asList(fire,gas,fire),reverse=Arrays.asList(gas,fire),unique=Arrays.asList(fire);
        List<Object> cells=Arrays.asList(tile(0,common),tile(1,reverse),tile(2,common),tile(3,reverse),tile(4,unique));
        Map<String,Object> input=map("width",5,"height",1,"cells",cells);
        Map<String,Object> play=object(CompactProtocol.project(input,false)),full=object(CompactProtocol.project(input,false,true));
        assertEquals(2,((List<?>)play.get("effect_defs")).size());
        assertEquals(0,object(play.get("env")).get("0"));assertEquals(1,object(play.get("env")).get("1"));
        assertTrue(object(play.get("env")).get("4") instanceof List);
        assertEquals(full.get("env"),expandEffects(play));assertTrue(bytes(play)<bytes(full));
        Map<String,Object> response=CompactProtocol.success("q","s","completed",map("map",input),true,false);
        assertEquals(Arrays.asList(map("field","$.data.map.effect_defs[0][1].desc","code","clipped_text"),
                map("field","$.data.map.effect_defs[1][0].desc","code","clipped_text")),object(response.get("pres")).get("diag"));
    }

    @Test public void unresolvedAggregateOrCellMetadataKeepsEntityStructureAddressable() {
        Map<String,Object> a=entity(1,"trap","A long repeated description that would otherwise use a definition.");
        Map<String,Object> b=new LinkedHashMap<>(a);b.put("cell",2);
        Map<String,Object> input=map("visible_entities",Arrays.asList(a,b),"text_sources",map("visible_entities[1].description",map("kind","future")));
        Map<String,Object> play=object(CompactProtocol.project(input,false));assertFalse(play.containsKey("entity_defs"));
        assertEquals(map("desc",map("kind","future")),object(((List<?>)play.get("entities")).get(1)).get("text_sources"));
        a.put("text_diagnostics",map("cell","coordinate_warning"));b.put("text_diagnostics",map("cell","coordinate_warning"));input.remove("text_sources");
        assertFalse(object(CompactProtocol.project(input,false)).containsKey("entity_defs"));
    }

    @Test public void parentOwnedMapAndUiAnnotationsAreLocalizedBeforeStructuralChanges() {
        Map<String,Object> ui=map("controls",Arrays.asList(map("id","empty","role","text"),
                map("id","warning","role","text","text","Warning")));
        Map<String,Object> input=map("ui",ui,"map",map("width",2,"height",1,"cells",Arrays.asList(tile(1,Collections.emptyList()))),
                "text_diagnostics",map("map.cells[0].name","map_warning","ui.controls[1].text","ui_warning"),
                "text_sources",map("ui.controls[1].text",map("kind","literal","origin","external","value","Warning")));
        Map<String,Object> reply=CompactProtocol.success("q","s","completed",input,true,false);
        assertEquals(Arrays.asList(map("field","$.data.ui.nodes[0].text","code","ui_warning"),
                map("field","$.data.map.types[0].name","code","map_warning")),object(reply.get("pres")).get("diag"));
        assertEquals(map("text",Arrays.asList("external")),node(object(reply.get("data")),"warning").get("text_origins"));
        assertFalse(object(input.get("map")).containsKey("text_diagnostics"));
    }

    @Test public void fullSourcesAndHistoricalSnapshotsAlwaysRemainInlineWithFullVisibility() {
        List<Object> entities=Arrays.asList(entity(0,"trap","Long shared trap description with enough repeated characters."),entity(1,"trap","Long shared trap description with enough repeated characters."));
        List<Object> effects=Arrays.asList(map("type","fire","description","Long shared environment description with enough repeated characters."));
        Map<String,Object> state=map("visible_entities",entities,"map",map("width",3,"height",1,"cells",Arrays.asList(tile(0,effects),tile(1,effects),tile(2,Collections.emptyList()))));
        Map<String,Object> play=object(CompactProtocol.project(state,false));assertTrue(play.containsKey("entity_defs"));
        assertEquals("v",rowVisibility(object(play.get("map"))));
        for(Map<String,Object> full:Arrays.asList(object(CompactProtocol.project(state,false,true)),object(CompactProtocol.project(state,true)),
                object(object(CompactProtocol.project(map("before",state),false)).get("before")),object(object(CompactProtocol.project(map("after",state),false)).get("after")))) {
            assertFalse(full.containsKey("entity_defs"));Map<String,Object> terrain=object(full.get("map"));assertFalse(terrain.containsKey("effect_defs"));
            assertEquals("vvv",rowVisibility(terrain));assertTrue(object(terrain.get("env")).get("0") instanceof List);
        }
        Map<String,Object> middle=tile(1,effects);middle.put("visibility","visited");
        assertEquals("vsv",rowVisibility(object(CompactProtocol.project(map("width",3,"height",1,"cells",Arrays.asList(tile(0,effects),middle,tile(2,effects))),false))));
    }

    @Test public void captureHintsRemoveOnlyEmptyPlaceholdersAndCoveredPassiveChildren() {
        List<Object> nodes=Arrays.asList(map("id","slot","role","button","enabled",false),map("id","plain","role","button","enabled",false),
                map("id","icon","role","button","enabled",false),map("id","info","role","button","enabled",false,"text","Unavailable"),
                map("id","parent","role","button","text","First\nSecond"),map("id","first","parent","parent","role","text","text","First"),
                map("id","second","parent","parent","role","text","text","Second"),map("id","independent","parent","parent","role","text","text","First","enabled",false),
                map("id","bar","role","health_bar","total_pixels",30,"health_pixels",20,"health_and_shield_pixels",25,"cell",8,"measurement","rendered_pixels"));
        Map<String,UiProjectionHints.Node> hints=new LinkedHashMap<>();
        hints.put("slot",hint(true,Collections.emptyList(),null,Collections.emptyMap()));
        hints.put("icon",hint(true,Collections.emptyList(),null,Collections.emptyMap()));
        hints.put("parent",hint(false,Arrays.asList("first","second"),null,Collections.emptyMap()));
        Map<String,Object> ui=new UiProjectionHints(hints).attach(map("controls",nodes));
        Map<String,Object> input=map("ui",ui,"actions",Arrays.asList(map("action","ui.activate","control","icon"),map("action","ui.back")));
        Map<String,Object> play=object(CompactProtocol.project(input,false));
        assertEquals(Arrays.asList("plain","icon","info","parent",null,"bar"),ids(play));
        assertEquals(Arrays.asList(map("op","back")),play.get("acts"));
        assertEquals(Collections.singletonList(map("op","click")),node(play,"icon").get("ops"));
        assertEquals(25,node(play,"bar").get("health_and_shield_pixels"));
        assertEquals(9,ids(object(CompactProtocol.project(input,false,true))).size());
    }

    @Test public void displayedItemFieldsRelocateTextOriginsDiagnosticsAndExactIdentityNames() {
        Map<String,Object> status=map("id","status","parent","item","role","text","text","4/20");
        Map<String,Object> strength=map("id","strength","parent","item","role","text","text","14?","text_sources",map("text",map("kind","literal","origin","external","value","14?")),"text_diagnostics",map("text","display_warning"));
        Map<String,Object> item=map("id","item","role","button","label","Waterskin","text","4/20\n14?");
        Map<String,String> display=new LinkedHashMap<>();display.put("status","status");display.put("extra","strength");
        Map<String,Object> ui=new UiProjectionHints(Collections.singletonMap("item",hint(false,Arrays.asList("status","strength"),"inventory:1",display)))
                .attach(map("controls",Arrays.asList(item,status,strength)));
        Map<String,Object> inventory=item("inventory:1");inventory.put("name","Waterskin");
        Map<String,Object> input=map("inventory",Arrays.asList(inventory),"ui",ui,"actions",Arrays.asList(map("action","ui.activate","control","item","gestures",Arrays.asList("click","long"))));
        Map<String,Object> reply=CompactProtocol.success("q","s","completed",input,true,false),play=object(reply.get("data"));
        assertEquals(Arrays.asList("item"),ids(play));Map<String,Object> projected=node(play,"item");
        assertEquals("inventory:1",projected.get("loc"));assertEquals("Waterskin",projected.get("label"));assertFalse(projected.containsKey("text"));
        Map<String,Object> fields=object(projected.get("display"));assertEquals("4/20",fields.get("status"));assertEquals("14?",fields.get("extra"));
        assertEquals(map("extra",Arrays.asList("external")),fields.get("text_origins"));
        assertEquals(Arrays.asList(map("field","$.data.ui.nodes[0].display.extra","code","display_warning")),object(reply.get("pres")).get("diag"));
        assertEquals(Arrays.asList(map("op","click","gestures",Arrays.asList("click","long"))),projected.get("ops"));
        Map<String,Object> actions=object(CompactProtocol.project(map("ui",ui),false));assertEquals("Waterskin",node(actions,"item").get("label"));
        inventory.put("name","waterskin");assertEquals("Waterskin",node(object(CompactProtocol.project(input,false)),"item").get("label"));
    }

    @Test public void fallbackDedupRequiresExactFullTextAndAllIndependentState() {
        Map<String,Object> input=map("ui",map("controls",Arrays.asList(map("id","p","role","button","text","Apply"),
                map("id","same","parent","p","role","text","text","Apply"),map("id","substring","parent","p","role","text","text","App"),
                map("id","disabled","parent","p","role","text","text","Apply","enabled",false),
                map("id","origin","parent","p","role","text","text","Apply","text_sources",map("text",map("kind","literal","origin","user","value","Apply"))),
                map("id","state","parent","p","role","text","text","Apply","extra",1))));
        assertEquals(Arrays.asList("p",null,null,"origin","state"),ids(object(CompactProtocol.project(input,false))));
    }

    @Test public void protectedOperationMetadataKeepsDynamicFieldsAndUsesActualWirePaths() {
        Map<String,Object> source=map("kind","future","description","Unchanged source AST");
        Map<String,Object> action=map("action","ui.value","control","slider","range",Arrays.asList(1,10),
                "parameters",map("value","integer"),"text_sources",map("parameters",source,"action",source),
                "text_diagnostics",map("range","range_warning","control","binding_warning"));
        Map<String,Object> input=map("ui",map("controls",Arrays.asList(map("id","slider","role","slider","minimum",1,"maximum",10))),
                "actions",Arrays.asList(action));
        Map<String,Object> reply=CompactProtocol.success("q","s","completed",input,true,false);
        Map<String,Object> operation=object(((List<?>)node(object(reply.get("data")),"slider").get("ops")).get(0));
        assertEquals("slider",operation.get("ctl"));assertEquals(Arrays.asList(1,10),operation.get("range"));
        assertTrue(operation.containsKey("parameters"));assertEquals(source,object(operation.get("text_sources")).get("op"));
        assertEquals(Arrays.asList(map("field","$.data.ui.nodes[0].ops[0].range","code","range_warning"),
                map("field","$.data.ui.nodes[0].ops[0].ctl","code","binding_warning")),object(reply.get("pres")).get("diag"));
    }

    @Test public void presentationOnlyRootActionDiagnosticsFollowAttachedOpsInAllViews() {
        Map<String,Object> canonical=map("ui",map("controls",Arrays.asList(map("id","button","role","button","text","Apply"))),
                "actions",Arrays.asList(map("action","ui.activate","control","button","text_diagnostics",map("control","local_warning"))),
                "presentation",map("status","partial","diagnostics",Arrays.asList(map("field","$.actions[0].control","code","root_warning","detail","Original binding evidence"))));
        String original=JsonCodec.encode(canonical);
        for(boolean full:Arrays.asList(false,true))for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> reply=CompactProtocol.success("q","s","completed",canonical,true,sources,full);
            Map<String,Object> operation=object(((List<?>)node(object(reply.get("data")),"button").get("ops")).get(0));
            assertEquals("button",operation.get("ctl"));assertEquals(Collections.emptyList(),object(reply.get("data")).get("acts"));
            assertEquals(Arrays.asList(map("field","$.data.ui.nodes[0].ops[0].ctl","code","root_warning","detail","Original binding evidence"),
                    map("field","$.data.ui.nodes[0].ops[0].ctl","code","local_warning")),object(reply.get("pres")).get("diag"));
        }
        assertEquals(original,JsonCodec.encode(canonical));
    }

    @Test public void presentationOnlyRootMapDiagnosticsFollowTerrainAndHistoricalPaths() {
        Map<String,Object> snapshot=map("map",map("width",2,"height",1,"cells",Arrays.asList(tile(1,Collections.emptyList()))),
                "presentation",map("status","partial","diagnostics",Arrays.asList(map("field","$.map.cells[0].name","code","root_warning"))));
        for(boolean full:Arrays.asList(false,true))for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> reply=CompactProtocol.success("q","s","completed",snapshot,true,sources,full);
            assertEquals(Arrays.asList(map("field","$.data.map.types[0].name","code","root_warning")),object(reply.get("pres")).get("diag"));
        }
        Map<String,Object> opaque=map("map",map("cells",Collections.singletonList(map("name","Frozen raw content"))));
        Map<String,Object> historical=map("before",snapshot,"after",snapshot,"raw",opaque,"reply",opaque,
                "presentation",map("status","partial","diagnostics",Arrays.asList(map("field","$.before.map.cells[0].terrain","code","historical_warning"))));
        Map<String,Object> reply=CompactProtocol.success("q","s","completed",historical,false,false);
        assertEquals(Arrays.asList(map("field","$.data.before.map.types[0].name","code","root_warning"),
                map("field","$.data.before.map.types[0].terrain","code","historical_warning"),
                map("field","$.data.after.map.types[0].name","code","root_warning")),object(reply.get("pres")).get("diag"));
        assertSame(opaque,object(reply.get("data")).get("raw"));assertSame(opaque,object(reply.get("data")).get("reply"));
    }

    @Test public void protectedWholeActionListKeepsDescriptorsIndexesAndNodeCapabilities() {
        Map<String,Object> source=map("kind","future_source","description","Original whole-list source AST");
        List<Object> actions=Arrays.asList(map("action","ui.activate","control","button","parameters",map("gesture","original constraint")),
                map("action","wait"),map("action","ui.activate","control","button","gestures",Arrays.asList("right")));
        Map<String,Object> snapshot=map("ui",map("controls",Arrays.asList(map("id","button","role","button","text","Apply"))),
                "actions",actions,"text_sources",map("actions",source));
        for(boolean full:Arrays.asList(false,true))for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> output=object(CompactProtocol.project(snapshot,sources,full));List<?> retained=(List<?>)output.get("acts");
            assertEquals(3,retained.size());assertEquals("button",object(retained.get(0)).get("ctl"));
            assertEquals(map("g","original constraint"),object(retained.get(0)).get("parameters"));
            assertEquals("wait",object(retained.get(1)).get("op"));assertEquals(Arrays.asList("right"),object(retained.get(2)).get("gestures"));
            assertEquals(source,object(output.get("text_sources")).get("acts"));assertEquals(2,((List<?>)node(output,"button").get("ops")).size());
        }
        Map<String,Object> history=object(CompactProtocol.project(map("before",snapshot,"after",snapshot),false));
        assertEquals(3,((List<?>)object(history.get("before")).get("acts")).size());
        assertEquals(3,((List<?>)object(history.get("after")).get("acts")).size());
    }

    @Test public void extraMapDiagnosticFieldsRemainOnPreservedPublicEvidence() {
        Map<String,Object> diagnostic=map("field","$.map.cells[0].name","code","root_warning","detail",map("description","Keep diagnostic metadata"));
        Map<String,Object> snapshot=map("map",map("width",2,"height",1,"cells",Arrays.asList(tile(1,Collections.emptyList()))),
                "text_diagnostics",map("map.cells[0].terrain","local_warning"),
                "presentation",map("status","partial","diagnostics",Arrays.asList(diagnostic)));
        Map<String,Object> reply=CompactProtocol.success("q","s","completed",snapshot,true,false);
        assertEquals("Floor",object(((List<?>)object(object(reply.get("data")).get("map")).get("preserved_cells")).get(0)).get("name"));
        assertEquals(Arrays.asList(map("field","$.data.map.preserved_cells[0].name","code","root_warning","detail",map("description","Keep diagnostic metadata")),
                map("field","$.data.map.types[0].terrain","code","local_warning")),object(reply.get("pres")).get("diag"));
        assertEquals("$.map.cells[0].name",diagnostic.get("field"));
    }

    private static UiProjectionHints.Node hint(boolean empty,List<String> children,String locator,Map<String,String> display) {return new UiProjectionHints.Node(empty,children,locator,display);}
    private static String rowVisibility(Map<String,Object> map) {return (String)((List<?>)((List<?>)map.get("rows")).get(0)).get(3);}
    private static List<Object> ids(Map<String,Object> data) {List<Object> ids=new ArrayList<>();for(Object node:(List<?>)object(object(CompactProtocol.expandStructures(data)).get("ui")).get("nodes"))ids.add(object(node).get("id"));return ids;}
    private static Map<String,Object> node(Map<String,Object> data,String id) {for(Object node:(List<?>)object(object(CompactProtocol.expandStructures(data)).get("ui")).get("nodes"))if(id.equals(object(node).get("id")))return object(node);throw new AssertionError(id);}
    private static int bytes(Object value) {return JsonCodec.encode(value).getBytes(StandardCharsets.UTF_8).length;}
    private static Map<String,Object> item(String loc) {return map("locator",loc,"name","Item","quantity",1,"equipped",false,"type_known",true);}
    private static Map<String,Object> entity(int cell,String kind,String description) {return map("cell",cell,"kind",kind,"name","Public "+kind,"description",description);}
    private static Map<String,Object> tile(int cell,List<?> effects) {return map("cell",cell,"x",cell,"y",0,"terrain",1,"name","Floor","visibility","visible","environment",effects);}
    private static List<Object> expandEntities(Map<String,Object> data) {
        List<Object> result=new ArrayList<>();List<?> defs=(List<?>)data.getOrDefault("entity_defs",Collections.emptyList());
        for(Object raw:(List<?>)data.get("entities")) {Map<String,Object> entity=object(raw);if(!entity.containsKey("def")){result.add(entity);continue;}
            Map<String,Object> expanded=new LinkedHashMap<>(object(defs.get(((Number)entity.get("def")).intValue())));expanded.put("cell",entity.get("cell"));result.add(expanded);}
        return result;
    }
    private static Map<String,Object> expandEffects(Map<String,Object> map) {
        Map<String,Object> result=new LinkedHashMap<>();List<?> defs=(List<?>)map.getOrDefault("effect_defs",Collections.emptyList());
        for(Map.Entry<String,Object> entry:object(map.get("env")).entrySet())result.put(entry.getKey(),entry.getValue() instanceof Number?defs.get(((Number)entry.getValue()).intValue()):entry.getValue());return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
}
