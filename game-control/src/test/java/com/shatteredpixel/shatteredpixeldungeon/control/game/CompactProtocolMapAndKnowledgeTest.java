package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Schema and public-information invariants independent of the live engine. */
public class CompactProtocolMapAndKnowledgeTest {
    @Test public void mapsRoundTripUnknownGapsRowBoundariesAndEnvironmentRemoval() {
        List<Object> cells=Arrays.asList(tile(7,1,"visited"),tile(0,2,"mapped"),tile(1,1,"visible"),tile(3,1,"visible"),tile(4,1,"visible"));
        Map<String,Object> fire=object(cells.get(2));fire.put("environment",Arrays.asList(map("type","fire","description","Flames")));
        Map<String,Object> canonical=map("width",4,"height",2,"cells",cells,"clipped",true);
        for(boolean full:Arrays.asList(false,true)) {
            Map<String,Object> projected=object(CompactProtocol.project(canonical,false,full));
            assertEquals(Arrays.asList(Arrays.asList(0,0,"01","mv"),Arrays.asList(0,3,"1","v"),
                    Arrays.asList(1,0,"1","v"),Arrays.asList(1,3,"1","s")),projected.get("rows"));
            assertEquals(Arrays.asList(0,1,3,4,7),new ArrayList<>(decode(projected).keySet()));
            for(Object raw:cells) {
                Map<String,Object> cell=object(raw),decoded=decode(projected).get(cell.get("cell"));
                assertEquals(cell.get("terrain"),decoded.get("terrain"));
                assertEquals(cell.get("visibility"),decoded.get("visibility"));
            }
            assertEquals(true,projected.get("clipped"));
            assertEquals(map("1",Arrays.asList(map("type","fire","desc","Flames"))),projected.get("env"));
        }
        fire.put("environment",Collections.emptyList());
        assertFalse(object(CompactProtocol.project(canonical,false)).containsKey("env"));
        assertEquals(Collections.emptyMap(),object(CompactProtocol.project(canonical,false,true)).get("env"));
    }
    @Test public void mapDescriptorsAndMapLevelSourcesKeepLiteralAstKeys() {
        Map<String,Object> source=map("kind","future_source","description","literal AST field");
        Map<String,Object> tile=tile(1,4,"mapped");tile.put("description","Custom terrain");tile.put("text_sources",map("description",source));
        tile.put("clipped",true);
        Map<String,Object> canonical=map("width",4,"height",1,"cells",Arrays.asList(tile),"description","Custom map", "text_sources",map("description",source));
        for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> compact=object(CompactProtocol.project(canonical,sources));
            assertEquals("Custom map",compact.get("desc"));assertEquals(source,object(compact.get("text_sources")).get("desc"));
            Map<String,Object> descriptor=object(((List<?>)compact.get("types")).get(0));
            assertEquals("Custom terrain",descriptor.get("desc"));assertEquals(true,descriptor.get("clipped"));
            assertEquals(source,object(descriptor.get("text_sources")).get("desc"));
        }
    }
    @Test public void allRowsUseIntegerIndexesWhenDictionaryOverflowsAlphabet() {
        List<Object> cells=new ArrayList<>();for(int i=0;i<65;i++)cells.add(tile(i,i,"visible"));
        Map<String,Object> projected=object(CompactProtocol.project(map("width",64,"height",2,"cells",cells),false));
        for(Object row:(List<?>)projected.get("rows"))assertTrue(((List<?>)row).get(2) instanceof List);
        assertEquals(65,decode(projected).size());assertEquals(64,decode(projected).get(64).get("terrain"));
        cells.remove(64);projected=object(CompactProtocol.project(map("width",64,"height",2,"cells",cells),false));
        assertEquals(CompactProtocol.TILE_ALPHABET,((List<?>)((List<?>)projected.get("rows")).get(0)).get(2));
    }
    @Test public void itemKnowledgeHasThreeStatesInBothViewsWithoutChangingInspectedKnowledge() {
        List<Object> items=Arrays.asList(item("na",null,null),item("unknown",false,false),item("known",true,true));
        Map<String,Object> original=map("inventory",items,"ui",map("inspected_item",map("level_known",false)));
        for(boolean full:Arrays.asList(false,true)) {
            Map<String,Object> projected=object(CompactProtocol.expandStructures(CompactProtocol.project(original,false,full)));
            List<?> inventory=(List<?>)projected.get("inv");
            assertFalse(object(inventory.get(0)).containsKey("level"));assertFalse(object(inventory.get(0)).containsKey("cursed"));
            assertTrue(object(inventory.get(1)).containsKey("level"));assertNull(object(inventory.get(1)).get("level"));
            assertTrue(object(inventory.get(1)).containsKey("cursed"));assertNull(object(inventory.get(1)).get("cursed"));
            assertEquals(0,object(inventory.get(2)).get("level"));assertEquals(false,object(inventory.get(2)).get("cursed"));
            for(Object value:inventory){assertFalse(object(value).containsKey("level_known"));assertFalse(object(value).containsKey("curse_known"));}
            assertEquals(map("level_known",false),object(projected.get("ui")).get("item_info"));
        }
    }
    @Test public void scopedDefaultsPreserveNondefaultsZeroesNullSlotsAndVisibleItemDetails() {
        Map<String,Object> normal=item("item",true,true);
        normal.put("available",true);normal.put("charges",map("known",false,"current",null,"maximum",0));
        Map<String,Object> unusual=item("other",true,true);unusual.put("quantity",2);unusual.put("equipped",true);unusual.put("available",false);unusual.put("type_known",false);
        Map<String,Object> original=map("inventory",Arrays.asList(normal,unusual),"ui",map("controls",Arrays.asList(
                map("id","water","role","item","text","4/20","strength_requirement","14?","enabled",false,"dimmed",true))),
                "slots",Arrays.asList(null,0,false));
        Map<String,Object> play=object(CompactProtocol.project(original,false));Map<String,Object> compact=object(((List<?>)play.get("inv")).get(0));
        for(String key:Arrays.asList("qty","equipped","available","type_known"))assertFalse(compact.containsKey(key));
        assertEquals(map("known",false,"current",null,"max",0),compact.get("charges"));
        assertEquals(Arrays.asList(null,0,false),play.get("slots"));
        assertEquals(map("id","water","role","item","text","4/20","strength_requirement","14?","enabled",false,"dimmed",true),
                ((List<?>)object(play.get("ui")).get("nodes")).get(0));
        Map<String,Object> other=object(((List<?>)play.get("inv")).get(1));
        assertEquals(2,other.get("qty"));assertEquals(true,other.get("equipped"));assertEquals(false,other.get("available"));assertEquals(false,other.get("type_known"));
    }
    @Test public void blankLeavesKeepIdentitiesParentsOperationsMetadataAndDisabledState() {
        List<Object> controls=Arrays.asList(
                map("id","empty","role","text","enabled",true),
                map("id","parent","role","text","enabled",true),
                map("id","child","role","text","parent","parent","text","Visible"),
                map("id","clickable","role","text","enabled",true),
                map("id","disabled","role","text","enabled",false),
                map("id","clipped","role","text","clipped",true),
                map("id","special","role","text","extra_state",0),
                map("id","icon","role","button","enabled",true,"gestures",Collections.singletonList("click")));
        Map<String,Object> original=map("ui",map("controls",controls),"actions",Arrays.asList(
                map("action","ui.activate","control","clickable","gestures",Collections.singletonList("click")),
                map("action","ui.activate","control","icon","gestures",Collections.singletonList("click"))));
        Map<String,Object> play=object(CompactProtocol.expandStructures(CompactProtocol.project(original,false)));List<?> nodes=(List<?>)object(play.get("ui")).get("nodes");
        List<Object> ids=new ArrayList<>();for(Object node:nodes)ids.add(object(node).get("id"));
        assertEquals(Arrays.asList("empty","parent","child","clickable","disabled","clipped","special","icon"),ids);
        assertEquals(Collections.singletonList(map("op","click","gestures",Collections.singletonList("click"))),object(nodes.get(3)).get("ops"));
        assertEquals(Collections.singletonList("click"),object(nodes.get(7)).get("gestures"));
        assertEquals(8,((List<?>)object(object(CompactProtocol.project(original,false,true)).get("ui")).get("nodes")).size());
    }
    @Test public void allItemDescriptionsAndZeroPointTalentsRemainInEveryView() {
        Map<String,Object> potion=item("potion",null,null);potion.put("description","A normal potion.");
        Map<String,Object> external=item("external",null,null);external.put("description","External warning");
        external.put("text_sources",map("description",map("kind","literal","origin","external","value","External warning")));
        Map<String,Object> partial=item("partial",null,null);partial.put("description","Warning");partial.put("text_diagnostics",map("description","clipped_text"));
        Map<String,Object> unknownSource=item("unknown-source",null,null);unknownSource.put("description","Custom");
        unknownSource.put("text_sources",map("description",map("kind","future_source","value","Custom")));
        Map<String,Object> original=map("inventory",Arrays.asList(potion,external,partial,unknownSource),"visible_entities",Arrays.asList(
                map("kind","container","description","Something feels off"),map("kind","trap","description","A hidden danger"),
                map("kind","item","item",potion)),"hero",map("talents",Arrays.asList(
                map("name","Unused","points",0),map("name","Trained","points",2)),"talent_points_available",Arrays.asList(0,1,0,0)),
                "ui",map("controls",Arrays.asList(map("id","modal","role","text","text","All visible description text"))));
        Map<String,Object> play=object(CompactProtocol.project(original,false));List<?> inventory=(List<?>)play.get("inv");
        assertEquals("A normal potion.",object(inventory.get(0)).get("desc"));
        for(int i=1;i<4;i++)assertTrue(object(inventory.get(i)).containsKey("desc"));
        assertEquals(map("desc",Arrays.asList("external")),object(inventory.get(1)).get("text_origins"));
        assertEquals(map("desc",map("kind","future_source","value","Custom")),object(inventory.get(3)).get("text_sources"));
        List<?> entities=(List<?>)play.get("entities");assertEquals("Something feels off",object(entities.get(0)).get("desc"));
        assertEquals("A hidden danger",object(entities.get(1)).get("desc"));assertEquals("A normal potion.",object(object(entities.get(2)).get("item")).get("desc"));
        assertEquals(Arrays.asList(map("name","Unused","points",0),map("name","Trained","points",2)),object(play.get("hero")).get("talents"));
        assertEquals(Arrays.asList(0,1,0,0),object(play.get("hero")).get("tp"));
        for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> full=object(CompactProtocol.project(original,sources,true));
            assertEquals("A normal potion.",object(((List<?>)full.get("inv")).get(0)).get("desc"));
            assertEquals(2,((List<?>)object(full.get("hero")).get("talents")).size());
        }
    }
    @Test public void historySnapshotsStayFullWhileRawAndReplyRemainOpaque() {
        Map<String,Object> item=item("potion",null,null);item.put("description","Full details");
        Map<String,Object> snapshot=map("inventory",Collections.singletonList(item),"ui",map("controls",Collections.singletonList(map("id","empty","role","text","enabled",true))));
        Map<String,Object> recorded=map("v",4,"data",map("inv","opaque"));
        Map<String,Object> receipt=object(CompactProtocol.project(map("before",snapshot,"after",snapshot,"reply",recorded,"raw",recorded),false));
        for(String field:Arrays.asList("before","after")) {
            Map<String,Object> projected=object(receipt.get(field)),value=object(((List<?>)projected.get("inv")).get(0));
            assertEquals("Full details",value.get("desc"));assertEquals(1,value.get("qty"));
            assertEquals(1,((List<?>)object(projected.get("ui")).get("nodes")).size());
        }
        assertSame(recorded,receipt.get("reply"));assertSame(recorded,receipt.get("raw"));
    }
    @Test public void preservedNodesAndRelocationKeepDiagnosticsAtExistingWirePaths() {
        Map<String,Object> item=item("partial",null,null);item.put("description","Warning");item.put("text_diagnostics",map("description","clipped_text"));
        Map<String,Object> terrain=tile(1,4,"visible");terrain.put("text_diagnostics",map("name","clipped_text"));
        Map<String,Object> state=map("inventory",Arrays.asList(item),"map",map("width",4,"height",1,"cells",Arrays.asList(terrain)),
                "ui",map("controls",Arrays.asList(map("id","empty","role","text"),
                        map("id","warning","role","text","text","Warning","text_diagnostics",map("text","clipped_text")))));
        Map<String,Object> response=CompactProtocol.success("q","run:a","completed",state,true,false);
        List<?> diagnostics=(List<?>)object(response.get("pres")).get("diag");
        assertEquals(Arrays.asList(map("field","$.data.inv[0].desc","code","clipped_text"),
                map("field","$.data.map.types[0].name","code","clipped_text"),
                map("field","$.data.ui.nodes[1].text","code","clipped_text")),diagnostics);
    }
    @Test public void presentationOnlyDiagnosticKeepsItsOriginalNodeIndex() {
        Map<String,Object> state=map("ui",map("controls",Arrays.asList(map("id","empty","role","text"),
                map("id","diagnostic","role","text","text","Warning"))),"presentation",map("status","partial","diagnostics",Arrays.asList(
                map("field","$.ui.controls[1].text","code","legacy"))));
        Map<String,Object> reply=CompactProtocol.success("q","run:a","completed",state,true,false);
        assertEquals(2,((List<?>)object(object(reply.get("data")).get("ui")).get("nodes")).size());
        assertEquals(Arrays.asList(map("field","$.data.ui.nodes[1].text","code","legacy")),object(reply.get("pres")).get("diag"));
    }
    @Test public void protectedKnowledgePairsAndZeroTalentRecordsKeepTheirOwnMeaning() {
        Map<String,Object> unknown=item("protected",null,false);
        unknown.put("text_sources",map("level_known",map("kind","literal","origin","external","value","Not applicable")));
        unknown.put("text_diagnostics",map("cursed","clipped_text"));
        Map<String,Object> original=map("inventory",Arrays.asList(unknown),"hero",map("talents",Arrays.asList(
                map("name","Annotated","points",0,"text_sources",map("points",map("kind","future_source","value",0))),
                map("name","Plain","points",0))));
        for(boolean full:Arrays.asList(false,true)) {
            Map<String,Object> result=object(CompactProtocol.project(original,false,full));Map<String,Object> item=object(((List<?>)result.get("inv")).get(0));
            assertTrue(item.containsKey("level_known"));assertNull(item.get("level_known"));assertTrue(item.containsKey("level"));assertNull(item.get("level"));
            assertEquals(false,item.get("curse_known"));assertNull(item.get("cursed"));
            assertEquals(map("level_known",Arrays.asList("external")),item.get("text_origins"));
            assertEquals(2,((List<?>)object(result.get("hero")).get("talents")).size());
        }
    }
    @Test public void mapAggregateAnnotationsFollowUnsortedCellsAndDictionaryIdentity() {
        Map<String,Object> first=tile(7,1,"visible"),second=tile(1,1,"mapped"),third=tile(2,1,"visited");
        first.put("environment",Arrays.asList(map("type","fire","description","Flames")));
        Map<String,Object> external=map("kind","literal","origin","external","value","Tile 1");
        Map<String,Object> user=map("kind","literal","origin","user","value","Tile 1");
        Map<String,Object> canonical=map("width",4,"height",2,"cells",Arrays.asList(first,second,third),
                "text_sources",map("cells",Arrays.asList(map("name",external),map("name",user),null),
                        "cells[0].environment[0].description",external),
                "text_diagnostics",map("cells[1].name","clipped_text"),
                "presentation",map("status","partial","diagnostics",Arrays.asList(map("field","$.cells[0].name","code","source_warning"))));
        String before=com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec.encode(canonical);
        for(boolean sources:Arrays.asList(false,true)) {
            Map<String,Object> result=object(CompactProtocol.project(canonical,sources));
            assertEquals(3,((List<?>)result.get("types")).size());assertFalse(result.containsKey("preserved_cells"));
            Map<Integer,Map<String,Object>> decoded=decode(result);
            assertEquals(sources?map("name",user):map("name",Arrays.asList("user")),decoded.get(1).get(sources?"text_sources":"text_origins"));
            assertEquals(sources?map("name",external):map("name",Arrays.asList("external")),decoded.get(7).get(sources?"text_sources":"text_origins"));
            assertEquals(Arrays.asList(map("field","name","code","clipped_text")),object(decoded.get(1).get("pres")).get("diag"));
            Map<String,Object> effect=object(((List<?>)object(result.get("env")).get("7")).get(0));
            assertEquals(sources?map("desc",external):map("desc",Arrays.asList("external")),effect.get(sources?"text_sources":"text_origins"));
            assertEquals(before,com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec.encode(canonical));
        }
    }
    @Test public void unmappableMapAnnotationsRetainIndependentPublicEvidenceAndRebasePaths() {
        Map<String,Object> cell=tile(7,1,"visible");cell.put("description","Public tile details");
        Map<String,Object> source=map("kind","future_source","description","Literal AST data");
        Map<String,Object> canonical=map("width",4,"height",2,"cells",Arrays.asList(cell),
                "text_sources",map("cells",Arrays.asList(source),"cells[0].x",source),
                "text_diagnostics",map("cells[0].y","coordinate_warning"));
        Map<String,Object> response=CompactProtocol.success("q","run:a","completed",map("map",canonical),true,false);
        Map<String,Object> result=object(object(response.get("data")).get("map"));
        assertFalse(result.containsKey("cells"));assertEquals(1,((List<?>)result.get("rows")).size());
        List<?> retained=(List<?>)result.get("preserved_cells");Map<String,Object> copy=object(retained.get(0));
        assertEquals(7,copy.get("cell"));assertEquals("Public tile details",copy.get("desc"));
        assertEquals(source,object(result.get("text_sources")).get("preserved_cells[0].x"));
        assertEquals(Arrays.asList(source),object(result.get("text_sources")).get("preserved_cells"));
        assertEquals(Arrays.asList(map("field","$.data.map.preserved_cells[0].y","code","coordinate_warning")),object(response.get("pres")).get("diag"));
        cell.put("name","Mutated caller input");source.put("description","Mutated caller AST");
        assertEquals("Tile 1",copy.get("name"));
        assertEquals("Literal AST data",object(object(result.get("text_sources")).get("preserved_cells[0].x")).get("description"));
    }
    private static Map<Integer,Map<String,Object>> decode(Map<String,Object> map) {
        Map<Integer,Map<String,Object>> cells=new LinkedHashMap<>();int width=((Number)map.get("w")).intValue();
        List<?> types=(List<?>)map.get("types");
        for(Object raw:(List<?>)map.get("rows")) {
            List<?> row=(List<?>)raw;int cell=((Number)row.get(0)).intValue()*width+((Number)row.get(1)).intValue();
            Object tiles=row.get(2);String vis=(String)row.get(3);
            for(int i=0;i<(tiles instanceof String?((String)tiles).length():((List<?>)tiles).size());i++) {
                int index=tiles instanceof String?CompactProtocol.TILE_ALPHABET.indexOf(((String)tiles).charAt(i)):((Number)((List<?>)tiles).get(i)).intValue();
                Map<String,Object> descriptor=new LinkedHashMap<>(object(types.get(index)));
                descriptor.put("visibility",vis.charAt(vis.length()==1?0:i)=='v'?"visible":vis.charAt(vis.length()==1?0:i)=='s'?"visited":"mapped");cells.put(cell+i,descriptor);
            }
        }
        return cells;
    }
    private static Map<String,Object> tile(int cell,int terrain,String visibility) {
        return map("cell",cell,"x",cell%64,"y",cell/64,"terrain",terrain,"name","Tile "+terrain,"visibility",visibility,"environment",Collections.emptyList());
    }
    private static Map<String,Object> item(String locator,Boolean levelKnown,Boolean curseKnown) {
        return map("locator",locator,"name",locator,"quantity",1,"equipped",false,"type_known",true,
                "level_known",levelKnown,"curse_known",curseKnown,"level",Boolean.TRUE.equals(levelKnown)?0:null,"cursed",Boolean.TRUE.equals(curseKnown)?false:null);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
}
