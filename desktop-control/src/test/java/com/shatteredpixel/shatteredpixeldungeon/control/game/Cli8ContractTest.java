package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** End-to-end v7 wire and real audit receipts, with no GUI, user profile, or game save reads. */
public class Cli8ContractTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void compactLiveStateAndFlatMovesKeepCanonicalExecutionAndFrozenDetails() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            Map<String,Object> state = h.send(map("v",8,"id","state","s","run:test","op","state"));
            assertEquals(8L,state.get("v"));assertEquals("r1",h.store.resolveHandle("revision",(String)state.get("rev")));
            assertFalse(state.containsKey("ok"));assertFalse(state.containsKey("result"));
            Map<?,?> data = (Map<?,?>)state.get("data");
            assertFalse(data.containsKey("observation"));assertFalse(data.containsKey("rev"));
            assertFalse(JsonCodec.encode(state).contains("text_sources"));
            assertEquals(List.of(List.of(2L,0L,"0","v")),((Map<?,?>)data.get("map")).get("rows"));
            assertFalse(((Map<?,?>)data.get("map")).containsKey("cols"));
            assertFalse(((Map<?,?>)data.get("map")).containsKey("cells"));
            Map<?,?> expanded=(Map<?,?>)CompactProtocol.expandStructures(state);
            List<?> nodes=(List<?>)((Map<?,?>)((Map<?,?>)expanded.get("data")).get("ui")).get("nodes");
            assertEquals("click",((Map<?,?>)((List<?>)((Map<?,?>)nodes.get(0)).get("ops")).get(0)).get("op"));
            Map<String,Object> moved=h.send(map("v",8,"id","move","s","run:test","op","move","rev","r1","dir","N"));
            assertEquals(map("action","move.step","direction","north"),h.game.lastArgs);
            assertEquals("r2",h.store.resolveHandle("revision",(String)moved.get("rev")));
            h.game.rejectObservation=true;
            Map<String,Object> receipt=h.send(map("v",8,"id","receipt","s","run:test","op","req","rid","move"));
            assertFalse(receipt.containsKey("rev"));
            assertEquals("COMPLETED",((Map<?,?>)receipt.get("data")).get("st"));
            assertFalse(((Map<?,?>)receipt.get("data")).containsKey("reply"));
            assertFalse(((Map<?,?>)receipt.get("data")).containsKey("after"));
            Map<String,Object> detail=h.send(map("v",8,"id","detail","s","run:test","op","req","rid","move",
                    "get",List.of("reply","before","after","raw"),"src",true));
            Map<?,?> details=(Map<?,?>)detail.get("data");
            assertEquals(moved,details.get("reply"));
            assertTrue(JsonCodec.encode(details.get("before")).contains("text_sources"));
            assertTrue(JsonCodec.encode(details.get("after")).contains("After"));
            assertTrue(JsonCodec.encode(details.get("before")).contains("Before"));
            assertEquals(moved,JsonCodec.decode((String)((Map<?,?>)details.get("raw")).get("response")));
            assertEquals(2,h.game.observations);assertEquals(1,h.game.executions);
        }
    }

    @Test public void stateAndActionsViewsHaveIndependentDefaultsAndHistoryKeepsFullDiagnostics() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            Map<String,Object> play = h.send(map("v",8,"id","play","s","run:test","op","state"));
            Map<?,?> playData = (Map<?,?>)play.get("data");
            Map<?,?> playItem = (Map<?,?>)((List<?>)playData.get("inv")).get(0);
            assertFalse(playItem.containsKey("qty")); assertEquals("Before",playItem.get("desc"));
            assertFalse(playItem.containsKey("available")); assertFalse(playItem.containsKey("equipped"));
            assertFalse(playItem.containsKey("type_known")); assertFalse(playItem.containsKey("level_known"));
            assertFalse(playItem.containsKey("curse_known")); assertFalse(playItem.containsKey("cursed"));
            assertTrue(playItem.containsKey("level")); assertNull(playItem.get("level"));
            List<?> playNodes=(List<?>)((Map<?,?>)((Map<?,?>)CompactProtocol.expandStructures(playData)).get("ui")).get("nodes");
            assertEquals(2,playNodes.size());
            Map<?,?> emptyNode=(Map<?,?>)playNodes.get(1);
            assertNotNull(emptyNode.get("id"));assertEquals("text",emptyNode.get("role"));assertEquals("",emptyNode.get("text"));
            assertEquals(List.of("click","move"),((List<?>)playData.get("acts")).stream().map(action->((Map<?,?>)action).get("op")).collect(java.util.stream.Collectors.toList()));

            Map<String,Object> full = h.send(map("v",8,"id","full","s","run:test","op","state","view","full"));
            assertFullDetails((Map<?,?>)full.get("data"));
            Map<String,Object> explicitPlay = h.send(map("v",8,"id","explicit-play","s","run:test","op","state","view","play"));
            assertEquals(playData,explicitPlay.get("data"));
            Map<String,Object> sourced = h.send(map("v",8,"id","sourced","s","run:test","op","state","view","play","src",true));
            assertFullDetails((Map<?,?>)sourced.get("data"));
            assertTrue(JsonCodec.encode(sourced).contains("text_sources"));

            Map<String,Object> actions = h.send(map("v",8,"id","actions-play","s","run:test","op","actions"));
            List<?> actionNodes=(List<?>)((Map<?,?>)((Map<?,?>)CompactProtocol.expandStructures(actions.get("data"))).get("ui")).get("nodes");
            assertEquals(2,actionNodes.size());assertEquals(emptyNode,actionNodes.get(1));
            Map<String,Object> fullActions = h.send(map("v",8,"id","actions-full","s","run:test","op","actions","view","full"));
            assertEquals(2,((List<?>)((Map<?,?>)((Map<?,?>)fullActions.get("data")).get("ui")).get("nodes")).size());

            h.game.rejectObservation = true;
            Map<String,Object> historic = h.send(map("v",8,"id","historic","s","run:test","op","req","rid","play","get",List.of("after","raw","reply")));
            Map<?,?> details=(Map<?,?>)historic.get("data");
            assertFullDetails((Map<?,?>)details.get("after"));
            assertEquals(play,details.get("reply"));
            assertEquals(play,JsonCodec.decode((String)((Map<?,?>)details.get("raw")).get("response")));
            assertEquals(6,h.game.observations); assertEquals(0,h.game.executions);
        }
    }

    private static void assertFullDetails(Map<?,?> data) {
        data=(Map<?,?>)CompactProtocol.expandStructures(data);
        Map<?,?> item=(Map<?,?>)((List<?>)data.get("inv")).get(0);
        assertEquals(1L,item.get("qty")); assertEquals("Before",item.get("desc"));
        assertEquals(Boolean.TRUE,item.get("available")); assertEquals(Boolean.FALSE,item.get("equipped"));
        assertEquals(Boolean.TRUE,item.get("type_known")); assertTrue(item.containsKey("level"));
        assertNull(item.get("level")); assertFalse(item.containsKey("level_known"));
        assertFalse(item.containsKey("curse_known")); assertFalse(item.containsKey("cursed"));
        assertEquals(2,((List<?>)((Map<?,?>)data.get("ui")).get("nodes")).size());
        assertTrue(((Map<?,?>)data.get("map")).containsKey("rows"));
    }

    @Test public void everyViewAndFrozenSnapshotUsesItsOwnRecordTemplates() throws Exception {
        try(Harness h=new Harness(temporary.newFolder().toPath())) {
            List<Map<String,Object>> items=new java.util.ArrayList<>(),nodes=new java.util.ArrayList<>(),actions=new java.util.ArrayList<>();
            for(int i=0;i<20;i++) {
                items.add(map("locator","backpack."+i,"quantity",1,"equipped",false,"available",true,
                        "charges",map("current",2,"maximum",5,"known",true)));
                nodes.add(map("id","ui-"+i,"role","button","enabled",true,"parent","ui-root","ordinal",i));
                actions.add(map("action","ui.activate","control","ui-"+i,"gestures",List.of("click","long"),
                        "parameters",map("fixture_constraint",true)));
            }
            Map<String,Object> observation=map("scene","game","inventory",items,"ui",map("controls",nodes),"actions",actions);
            h.game.state=new GameController.State("run:test","r1","player_ready",observation,map("fixture",true),actions);
            for(String view:List.of("play","full","src")) {
                Map<String,Object> request=map("v",8,"id",view,"s","run:test","op","state");
                if(view.equals("src"))request.put("src",true);else request.put("view",view);
                Map<String,Object> response=h.send(request);
                assertFalse(response.toString(),response.containsKey("err"));
                assertThreeTemplateTables((Map<?,?>)response.get("data"));
                Map<?,?> decoded=(Map<?,?>)CompactProtocol.expandStructures(response);
                Map<?,?> data=(Map<?,?>)decoded.get("data");
                assertEquals(20,((List<?>)data.get("acts")).size());
                assertEquals(20,((List<?>)data.get("inv")).size());
                List<?> expandedNodes=(List<?>)((Map<?,?>)data.get("ui")).get("nodes");
                for(int i=0;i<20;i++) {
                    Map<?,?> node=(Map<?,?>)expandedNodes.get(i);
                    assertEquals("ui-"+i,node.get("id"));assertEquals("ui-root",node.get("parent"));
                    Map<String,Object> expected=new java.util.LinkedHashMap<>((Map<String,Object>)((List<?>)data.get("acts")).get(i));
                    assertEquals(node.get("id"),expected.remove("ctl"));
                    assertEquals(expected,((List<?>)node.get("ops")).get(0));
                }
            }
            h.game.rejectObservation=true;
            Map<String,Object> history=h.send(map("v",8,"id","history-templates","s","run:test","op","req","rid","play","get",List.of("after","reply")));
            Map<?,?> details=(Map<?,?>)history.get("data");
            assertThreeTemplateTables((Map<?,?>)details.get("after"));
            assertThreeTemplateTables((Map<?,?>)((Map<?,?>)details.get("reply")).get("data"));
            Map<?,?> expandedHistory=(Map<?,?>)CompactProtocol.expandStructures(history);
            assertEquals(details.get("reply"),((Map<?,?>)expandedHistory.get("data")).get("reply"));
            assertFalse(history.containsKey("rev"));
        }
    }

    private static void assertThreeTemplateTables(Map<?,?> data) {
        assertTrue(data.toString(),data.containsKey("act_templates"));
        assertTrue(data.toString(),data.containsKey("inv_templates"));
        assertTrue(data.toString(),((Map<?,?>)data.get("ui")).containsKey("node_templates"));
        assertTrue(((List<?>)data.get("acts")).get(0) instanceof List);
        assertTrue(((List<?>)data.get("inv")).get(0) instanceof List);
        assertTrue(((List<?>)((Map<?,?>)data.get("ui")).get("nodes")).get(0) instanceof List);
    }

    @Test public void defaultReceiptDoesNotLoadASnapshotOrObserveTheEngine() throws Exception {
        Path profile=temporary.newFolder().toPath();
        try(Harness h=new Harness(profile)) {
            h.send(map("v",8,"id","original","s","run:test","op","state"));
            h.game.rejectObservation=true;
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+profile.resolve("public.sqlite3"));
                var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE snapshot_blobs SET body=x'00'");
            }
            Map<String,Object> receipt=h.send(map("v",8,"id","small","s","run:test","op","req","rid","original"));
            assertFalse(receipt.containsKey("err"));
            assertEquals("COMPLETED",((Map<?,?>)receipt.get("data")).get("st"));
            assertTrue(JsonCodec.encode(receipt).length()<1000);
            assertEquals(1,h.game.observations);
            Map<String,Object> expanded=h.send(map("v",8,"id","snapshot","s","run:test","op","req","rid","original","get",List.of("after")));
            assertEquals("AUDIT_UNAVAILABLE",expanded.get("err"));
            assertEquals(1,h.game.observations);
        }
    }

    @Test public void scopeTransitionUsesEffectiveScopeAndHistoryHasExplicitPageBounds() throws Exception {
        try(Harness h=new Harness(temporary.newFolder().toPath())) {
            h.game.scope=h.store.menuScope();h.game.state=h.game.snapshot("r1","Before");
            Map<String,Object> result=h.send(map("v",8,"id","start","s",h.store.menuScope(),"rev","r1","op","click","ctl","ui-1"));
            assertTrue(((String)result.get("s")).matches("s[1-9a-z][0-9a-z]*"));
            assertEquals(h.game.scope,h.store.resolveHandle("scope",(String)result.get("s")));
            assertFalse(((Map<?,?>)result.get("data")).containsKey("s"));
            Map<String,Object> history=h.send(map("v",8,"id","history","s",h.store.menuScope(),"op","history","limit",1));
            assertFalse(history.containsKey("rev"));
            Map<?,?> page=(Map<?,?>)history.get("data");
            assertEquals(1,((List<?>)page.get("items")).size());assertEquals(false,page.get("end"));assertNotNull(page.get("next"));
        }
    }

    @Test public void aOneItemHistoryTraversalEndsDespiteAuditingEveryPage() throws Exception {
        try(Harness h=new Harness(temporary.newFolder().toPath())) {
            h.send(map("v",8,"id","seed","s","run:test","op","state"));
            Map<String,Object> response=h.send(map("v",8,"id","page0","s","run:test","op","history","limit",1));
            Map<?,?> page=(Map<?,?>)response.get("data");
            long until=((Number)page.get("until")).longValue();
            int pages=1;
            while(!Boolean.TRUE.equals(page.get("end"))) {
                assertTrue("Self-audited page queries must not extend this traversal",pages<5);
                response=h.send(map("v",8,"id","page"+pages++,"s","run:test","op","history","limit",1,
                        "after",page.get("next"),"until",until));
                page=(Map<?,?>)response.get("data");
                assertEquals(until,((Number)page.get("until")).longValue());
            }
            assertEquals(2,pages);
            assertNull(page.get("next"));
            assertTrue(h.store.historyWatermark("run:test")>until);
        }
    }

    private static final class Harness implements AutoCloseable {
        final AuditStore store;final Fake game=new Fake();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final MachineSession session;
        Harness(Path profile)throws Exception {
            store=new AuditStore(profile);store.ensureScope("run:test","run","test");
            store.beginSession("test","test-build","CLI.8.0.0",8);
            session=new MachineSession(store,game,new PrintStream(bytes,true,"UTF-8"),1000);
        }
        Map<String,Object> send(Map<String,Object> request)throws Exception {
            bytes.reset();session.accept(V8Requests.encode(store, request)).get(5,TimeUnit.SECONDS);
            return JsonCodec.decode(bytes.toString(StandardCharsets.UTF_8).trim());
        }
        public void close(){session.close();store.close();}
    }
    private static final class Fake implements MachineSession.GamePort {
        int observations,executions;boolean rejectObservation;String scope="run:test";Map<String,Object> lastArgs;
        GameController.State state=snapshot("r1","Before");
        public GameController.State latest(){return state;}
        public CompletableFuture<GameController.State> observe(){
            observations++;
            return rejectObservation?CompletableFuture.failedFuture(new AssertionError("History must not observe")):CompletableFuture.completedFuture(state);
        }
        public CompletableFuture<GameController.State> execute(String version,Map<String,Object> args){
            executions++;lastArgs=args;state=snapshot("r"+(executions+1),"After");return CompletableFuture.completedFuture(state);
        }
        public void prepareRun(String run){scope="run:"+run;}
        public GameController.SaveResult pollSave(){return null;}
        public boolean exiting(){return false;}
        public boolean disposed(){return false;}
        public void exitNow(){}
        GameController.State snapshot(String rev,String label){
            TextProvenance provenance=new TextProvenance(key->label);
            Object text=provenance.capture(null,provenance.onTextResource(label,"fixture.label","en",new Object[0]),false);
            return new GameController.State(scope,rev,"player_ready",map("scene","game","hero",map("class","warrior","hp",20,"cell",10),
                    "inventory",List.of(map("locator","backpack.0","name",text,"description",text,"quantity",1,
                            "equipped",false,"available",true,"type_known",true,"level_known",false,"level",null,"curse_known",null,"cursed",null)),
                    "map",map("width",5,"height",5,"cells",List.of(map("cell",10,"x",0,"y",2,"terrain",1,"name",text,"visibility","visible","environment",List.of()))),
                    "ui",map("controls",List.of(map("id","ui-1","role","button","enabled",true,"label",text,"gestures",List.of("click")),
                            map("id","ui-empty","role","text","enabled",true,"text","")))),
                    map("private",true),List.of(map("action","ui.activate","control","ui-1","label",text,"gestures",List.of("click")),
                            map("action","move.step","parameters",map("direction",List.of("north")))));
        }
    }
}
