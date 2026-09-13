package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class CompactProtocolTest {
    @Test public void scopeChangeUsesReturnedScopeAndVersionWithOneFlattenedDataLayer() {
        Map<String,Object> state=map("scope_id","run:new","state_version","run:new:1","phase","player_ready",
                "observation",map("scene","GameScene","hero",map("hp",20),"inventory",Collections.emptyList()),
                "actions",Arrays.asList(map("action","move.step","parameters",map("direction",Arrays.asList("north","east")))));
        Map<String,Object> reply=CompactProtocol.success("a","menu:old","completed",state,true,false);
        assertEquals("run:new",reply.get("s"));assertEquals("run:new:1",reply.get("rev"));assertEquals(3,reply.get("v"));
        Map<String,Object> data=object(reply.get("data"));assertEquals("player_ready",data.get("phase"));
        assertTrue(data.containsKey("hero"));assertTrue(data.containsKey("inv"));
        for(String redundant:Arrays.asList("s","rev","observation","scope_id","state_version"))assertFalse(data.containsKey(redundant));
        assertEquals(Arrays.asList(map("op","move")),data.get("acts"));assertFalse(reply.containsKey("ok"));assertFalse(reply.containsKey("pres"));
    }
    @Test public void compactMapPreservesTerrainVisibilityCoordinatesAndVisibleEnvironment() {
        Map<String,Object> original=map("width",4,"height",3,"unknown","omitted","cells",Arrays.asList(
                tile(1,"visible",4,"Wall",Collections.emptyList()),tile(2,"visited",4,"Wall",Collections.emptyList()),
                tile(6,"mapped",1,"Floor",Arrays.asList(map("type","fire","description","Flames")))));
        Map<String,Object> compact=object(CompactProtocol.project(original,false));
        assertEquals(Arrays.asList("cell","tile","vis"),compact.get("cols"));
        assertEquals(Arrays.asList(map("terrain",4,"name","Wall"),map("terrain",1,"name","Floor")),compact.get("types"));
        assertEquals(Arrays.asList(Arrays.asList(1,0,"v"),Arrays.asList(2,0,"s"),Arrays.asList(6,1,"m")),compact.get("cells"));
        assertEquals(map("6",Arrays.asList(map("type","fire","desc","Flames"))),compact.get("env"));
        assertEquals(4,compact.get("w"));assertEquals(3,compact.get("h"));
        assertEquals(2,((Number)((List<?>)((List<?>)compact.get("cells")).get(2)).get(0)).intValue()%4);
    }
    @Test public void eachControlOwnsOnlyItsActionsWithoutLosingTextParentsOrDimming() {
        Map<String,Object> original=map("observation",map("ui",map("controls",Arrays.asList(
                map("id","a","role","button","label","Apply","enabled",true,"dimmed",true,"gestures",Arrays.asList("click","long")),
                map("id","b","role","text","text","Apply","parent","a","enabled",true),
                map("id","c","role","slider","enabled",true,"minimum",1,"maximum",10),
                map("id","d","role","button","enabled",false)))),"actions",Arrays.asList(
                map("action","ui.activate","control","a","label","Apply","gestures",Arrays.asList("click","long")),
                map("action","ui.value","control","c","range",Arrays.asList(1,10)),map("action","ui.back")));
        Map<String,Object> result=object(CompactProtocol.project(original,false));
        List<?> nodes=(List<?>)object(result.get("ui")).get("nodes");assertEquals(4,nodes.size());
        assertEquals(Arrays.asList(map("op","click")),object(nodes.get(0)).get("ops"));
        assertEquals(true,object(nodes.get(0)).get("dimmed"));assertEquals("a",object(nodes.get(1)).get("parent"));
        assertEquals(Arrays.asList(map("op","value")),object(nodes.get(2)).get("ops"));
        assertEquals(false,object(nodes.get(3)).get("enabled"));assertFalse(object(nodes.get(3)).containsKey("ops"));
        assertEquals(Arrays.asList(map("op","back")),result.get("acts"));
        assertFalse(object(((List<?>)object(object(original.get("observation")).get("ui")).get("controls")).get(0)).containsKey("ops"));
    }
    @Test public void sourceTreesAreOptionalButNestedUntrustedOriginsAndClippingRemain() {
        Map<String,Object> source=map("name",map("kind","concat","parts",Arrays.asList(
                map("kind","literal","origin","catalog","value","Hello"),
                map("kind","case","value",map("kind","literal","origin","user","value","Player")),
                map("kind","external","value","Guest"))));
        Map<String,Object> original=map("name","Hello Player Guest","clipped",true,"text_sources",source,
                "text_diagnostics",map("name","clipped_text"),"translation_status","partial");
        Map<String,Object> compact=object(CompactProtocol.project(original,false));
        assertFalse(compact.containsKey("text_sources"));assertEquals(map("name",Arrays.asList("user","external")),compact.get("text_origins"));
        assertEquals(true,compact.get("clipped"));assertEquals("partial",object(compact.get("pres")).get("st"));
        assertSame(source,object(CompactProtocol.project(original,true)).get("text_sources"));
        Map<String,Object> response=CompactProtocol.success("q","run:1","completed",map("observation",map("inventory",Arrays.asList(original))),true,false);
        List<?> diagnostics=(List<?>)object(response.get("pres")).get("diag");
        assertEquals("$.data.inv[0].name",object(diagnostics.get(0)).get("field"));
    }
    @Test public void historyNeverPublishesCurrentRevisionAndLeavesOriginalReplyOpaque() {
        Map<String,Object> recorded=map("v",3,"s","run:past","rev","past:7","st","completed","data",map("name","Original untrusted text"));
        Map<String,Object> raw=map("request_json","{\"op\":\"move\"}");
        Map<String,Object> receipt=map("id","a","st","COMPLETED","reply",recorded,"raw",raw,
                "after",map("inventory",Arrays.asList(map("name","Sword","quantity",1,"text_sources",map("name",map("kind","literal","origin","catalog","value","Sword"))))));
        Map<String,Object> rendered=PublicEnglishProjection.copy(receipt);
        assertSame(recorded,rendered.get("reply"));assertSame(raw,rendered.get("raw"));
        assertSame(recorded,PublicEnglishProjection.copy(rendered).get("reply"));
        Map<String,Object> response=CompactProtocol.success("q","run:past","completed",rendered,false,false);
        assertFalse(response.containsKey("rev"));Map<String,Object> data=object(response.get("data"));
        assertSame(recorded,data.get("reply"));assertSame(raw,data.get("raw"));
        assertEquals(Arrays.asList(map("name","Sword","qty",1)),object(data.get("after")).get("inv"));
    }
    @Test public void unknownItemPropertiesAndActualErrorsRemainExplicit() {
        Map<String,Object> item=map("locator","bag:0","quantity",1,"level",map("known",false,"value",null),"cursed",null);
        Map<String,Object> compact=object(CompactProtocol.project(item,false));assertEquals("bag:0",compact.get("loc"));
        assertEquals(map("known",false,"value",null),compact.get("level"));assertTrue(compact.containsKey("cursed"));
        assertEquals(map("v",3,"id","bad","s","run:1","err","STALE_STATE"),CompactProtocol.failure("bad","run:1","STALE_STATE"));
        assertTrue(object(CompactProtocol.info().get("commands")).containsKey("untarget"));
        assertFalse(CompactProtocol.failure("bad","run:1","EXECUTION_UNKNOWN").containsKey("st"));
        Map<String,Object> schema=CompactProtocol.info();
        assertSame(schema,object(CompactProtocol.project(PublicEnglishProjection.copy(map("schema",schema)),false)).get("schema"));
        assertTrue(schema.containsKey("coverage"));
    }
    @Test public void deduplicatingLabelRetainsIndependentOptionOriginsAndDiagnostics() {
        Map<String,Object> labelSource=map("kind","literal","origin","user","value","Choose");
        Map<String,Object> optionSource=map("kind","literal","origin","external","value","Guest");
        Map<String,Object> node=map("id","a","text","Choose","text_sources",map("text",labelSource),
                "text_diagnostics",map("text","label_problem"));
        Map<String,Object> descriptor=map("action","ui.choose","control","a","label","Choose","options",Arrays.asList("Guest"),
                "text_sources",map("label",labelSource,"options",Arrays.asList(optionSource)),
                "text_diagnostics",map("label","label_problem","options[0]","option_problem"));
        Map<String,Object> original=map("ui",map("controls",Arrays.asList(node)),"actions",Arrays.asList(descriptor));
        for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> result=object(CompactProtocol.project(original,sources));
            Map<String,Object> op=object(((List<?>)object(((List<?>)object(result.get("ui")).get("nodes")).get(0)).get("ops")).get(0));
            Map<String,Object> origins=object(op.get(sources?"text_sources":"text_origins"));
            assertTrue(origins.containsKey("options"));assertFalse(origins.containsKey("label"));
            assertEquals(Arrays.asList(map("field","options[0]","code","option_problem")),object(op.get("pres")).get("diag"));
        }
    }
    @Test public void sourceFieldNamesMatchWireFieldsButSourceAstKeysStayCanonical() {
        Map<String,Object> ast=map("kind","resource","description","literal AST field","key","sample.description");
        Map<String,Object> result=object(CompactProtocol.project(map("description","Visible text","text_sources",map("description",ast)),true));
        assertEquals("Visible text",result.get("desc"));
        assertSame(ast,object(result.get("text_sources")).get("desc"));
        assertFalse(object(result.get("text_sources")).containsKey("description"));
    }
    @Test public void equalLabelsWithDifferentDiagnosticsRemainAddressable() {
        Map<String,Object> original=map("ui",map("controls",Arrays.asList(map("id","a","text","Choose"))),
                "actions",Arrays.asList(map("action","ui.choose","control","a","label","Choose", "text_diagnostics",map("label","unclassified_string"))));
        Map<String,Object> reply=CompactProtocol.success("q","run:1","completed",original,true,false);
        Map<String,Object> op=object(((List<?>)object(((List<?>)object(object(reply.get("data")).get("ui")).get("nodes")).get(0)).get("ops")).get(0));
        assertEquals("Choose",op.get("label"));
        assertEquals("$.data.ui.nodes[0].ops[0].label",object(((List<?>)object(reply.get("pres")).get("diag")).get(0)).get("field"));
    }
    @Test public void nestedEventDiagnosticsAreRebasedAndDeduplicated() {
        Map<String,Object> event=map("entries",Arrays.asList(map("text","Text unavailable","text_diagnostics",map("text","clipped_text"))),
                "presentation",map("status","partial","diagnostics",Arrays.asList(map("field","$.entries[0].text","code","clipped_text"))));
        Map<String,Object> reply=CompactProtocol.success("q","run:1","completed",map("items",Arrays.asList(map("kind","game.log","data",event))),false,false);
        assertEquals(Arrays.asList(map("field","$.data.items[0].data.entries[0].text","code","clipped_text")),object(reply.get("pres")).get("diag"));
    }
    @Test public void canonicalAggregatePathsFollowFlatteningAndTerrainDictionaryRelocation() {
        Map<String,Object> tile=tile(1,"visible",4,"Text unavailable",Collections.emptyList());
        tile.put("text_diagnostics",map("name","unclassified_string"));
        Map<String,Object> state=map("observation",map("map",map("width",4,"height",3,"cells",Arrays.asList(tile))),
                "presentation",map("status","partial","diagnostics",Arrays.asList(map("field","$.observation.map.cells[0].name","code","unclassified_string"))));
        Map<String,Object> reply=CompactProtocol.success("q","run:1","completed",state,true,false);
        assertEquals(Arrays.asList(map("field","$.data.map.types[0].name","code","unclassified_string")),object(reply.get("pres")).get("diag"));
    }
    @Test public void presentationOnlyMetadataUsesItsEnclosingPayloadAsThePathRoot() {
        Map<String,Object> event=map("description","Visible text", "presentation",map("status","partial","diagnostics",Arrays.asList(
                map("field","$.description","code","legacy_diagnostic"),map("field","$.description","code","legacy_diagnostic"))));
        Map<String,Object> reply=CompactProtocol.success("q","run:1","completed",map("items",Arrays.asList(map("data",event))),false,false);
        assertEquals(Arrays.asList(map("field","$.data.items[0].data.desc","code","legacy_diagnostic")),object(reply.get("pres")).get("diag"));
        for(String op:Arrays.asList("history","events")) {
            com.shatteredpixel.shatteredpixeldungeon.control.protocol.ControlRequest query=
                    com.shatteredpixel.shatteredpixeldungeon.control.protocol.ControlRequest.parse("{\"v\":3,\"id\":\"q\",\"s\":\"run:1\",\"op\":\""+op+"\",\"until\":9}");
            assertEquals(9L,query.args.get("until"));
        }
        assertTrue(object(object(CompactProtocol.info().get("rules")).get("pagination")).containsKey("until"));
    }
    private static Map<String,Object> tile(int cell,String visibility,int terrain,String name,List<?> environment) {
        return map("cell",cell,"x",cell%4,"y",cell/4,"visibility",visibility,"terrain",terrain,"name",name,"environment",environment);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
}
