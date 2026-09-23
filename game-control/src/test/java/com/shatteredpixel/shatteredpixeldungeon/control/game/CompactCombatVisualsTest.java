package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Synthetic semantic facts: every new view preserves gameplay data and same-frame structures. */
public class CompactCombatVisualsTest {
    @Test public void everyViewPreservesGameplayFactsAndQuantizedEstimates() {
        Map<String,Object> input=capture("c","m1");String original=JsonCodec.encode(input);
        for(boolean full:Arrays.asList(false,true))for(boolean src:Arrays.asList(false,true)) {
            Map<String,Object> wire=CompactProtocol.success("q","s1","completed",input,true,src,full);
            assertEquals(8,wire.get("v"));Map<String,Object> packed=object(wire.get("data"));
            assertTrue(object(packed.get("ui")).containsKey("node_templates"));
            assertTrue(packed.containsKey("act_templates"));assertTrue(packed.containsKey("inv_templates"));
            Map<String,Object> data=object(object(CompactProtocol.expandStructures(wire)).get("data"));
            Map<String,Object> cues=object(data.get("cues"));assertEquals("m1",cues.get("map_context"));
            Map<String,Object> cue=object(((List<?>)cues.get("cues")).get(0));
            assertEquals(0,cue.get("source_cell"));assertEquals("NE",cue.get("dir"));
            assertEquals(map("cells",Arrays.asList(0,9),"partial",true),cue.get("appearance"));
            assertFalse(cues.containsKey("metrics"));assertFalse(cues.containsKey("screen_effects"));
            assertEquals(map("symbol","free_cast"),node(data,0).get("icon"));
            assertEquals(map("strength",map("value",14,"estimated",true),"status","1/20"),node(data,0).get("shown"));
            assertEquals(Arrays.asList(null,false,0),node(data,0).get("future"));
            assertEquals("c0",node(data,0).get("id"));assertEquals("root",node(data,0).get("parent"));
            assertEquals(Arrays.asList("click","long"),object(((List<?>)node(data,0).get("ops")).get(0)).get("gestures"));
            assertEquals(object(input.get("hero")).get("turn_progress"),object(data.get("hero")).get("turn_progress"));
            assertFalse(JsonCodec.encode(data).contains("texture_size"));
        }
        assertEquals(original,JsonCodec.encode(input));
    }

    @Test public void semanticStylesKeepProvenanceAndPartialDiagnosticPaths() {
        Map<String,Object> source=map("kind","literal","origin","external","value","旅人 🗡");
        Map<String,Object> first=map("text","旅人 🗡","tone","normal","text_sources",map("text",source));
        Map<String,Object> clipped=map("text","Text unavailable","tone","warning","clipped",true,
                "text_diagnostics",map("text","clipped_text"));
        Map<String,Object> input=map("ui",map("controls",Arrays.asList(map("id","message","role","text",
                "cell",0,"styles",Arrays.asList(first,clipped),"icon",map("symbol","damage")))));
        String original=JsonCodec.encode(input);
        for(boolean full:Arrays.asList(false,true))for(boolean src:Arrays.asList(false,true)) {
            Map<String,Object> wire=CompactProtocol.success("q","s1","completed",input,true,src,full);
            Map<String,Object> text=node(object(object(CompactProtocol.expandStructures(wire)).get("data")),0);
            List<?> styles=(List<?>)text.get("styles");assertEquals(2,styles.size());
            assertEquals("旅人 🗡",object(styles.get(0)).get("text"));assertEquals("normal",object(styles.get(0)).get("tone"));
            assertEquals(src?map("text",source):map("text",Arrays.asList("external")),
                    object(styles.get(0)).get(src?"text_sources":"text_origins"));
            assertEquals(true,object(styles.get(1)).get("clipped"));assertEquals("warning",object(styles.get(1)).get("tone"));
            assertEquals(Arrays.asList(map("field","$.data.ui.nodes[0].styles[1].text","code","clipped_text")),
                    object(wire.get("pres")).get("diag"));
        }
        assertEquals(original,JsonCodec.encode(input));
    }

    @Test public void frozenSnapshotsOwnTablesAndNeverRewriteOpaqueOldEvidence() {
        Map<String,Object> before=capture("b","m2"),after=capture("a","m3");
        before.put("scope_id","s2");before.put("state_version","r2");
        after.put("scope_id","s3");after.put("state_version","r3");
        Map<String,Object> opaque=map("v",7,"atlas","old-record","ui",map("node_templates",false));
        Map<String,Object> input=map("before",before,"after",after,"raw",opaque,"reply",opaque);
        String original=JsonCodec.encode(input);
        for(boolean src:Arrays.asList(false,true)) {
            Map<String,Object> wire=CompactProtocol.success("history","s9","completed",input,false,src);
            assertFalse(wire.containsKey("rev"));Map<String,Object> packed=object(wire.get("data"));
            assertTrue(object(packed.get("before")).containsKey("act_templates"));
            assertTrue(object(packed.get("after")).containsKey("act_templates"));
            assertSame(opaque,packed.get("raw"));assertSame(opaque,packed.get("reply"));
            Map<String,Object> decoded=object(object(CompactProtocol.expandStructures(wire)).get("data"));
            Map<String,Object> b=object(decoded.get("before")),a=object(decoded.get("after"));
            assertEquals("b0",node(b,0).get("id"));assertEquals("a0",node(a,0).get("id"));
            assertEquals("m2",object(b.get("cues")).get("map_context"));assertEquals("m3",object(a.get("cues")).get("map_context"));
            assertEquals(opaque,decoded.get("raw"));assertEquals(opaque,decoded.get("reply"));
            assertEquals("r2",b.get("rev"));assertEquals("r3",a.get("rev"));
            object(node(a,0).get("icon")).put("symbol","charged");
            assertEquals("free_cast",object(node(b,0).get("icon")).get("symbol"));
        }
        assertEquals(original,JsonCodec.encode(input));
    }

    @Test public void equalFeedbackOccurrencesAndClearingSnapshotsStayOrdered() {
        Map<String,Object> feedback=map("format","gameplay_snapshot_v1","occurred_at","2026-09-24T00:00:00Z",
                "depth",5,"map_context","m1","entries",Arrays.asList(map("text","3","tone","negative","cell",0)));
        Map<String,Object> banner=map("format","gameplay_occurrence_v1","occurred_at","2026-09-24T00:00:01Z","kind","boss_slain");
        List<Object> events=Arrays.asList(map("kind","game.floating_text","sequence",1,"data",feedback),
                map("kind","game.floating_text","sequence",2,"data",feedback),
                map("kind","game.visual","sequence",3,"data",map("cues",Collections.emptyList())),
                map("kind","game.banner","sequence",4,"data",banner));
        Map<String,Object> input=map("items",events);String original=JsonCodec.encode(input);
        Map<String,Object> expected=JsonCodec.decode(original);
        for(int index:Arrays.asList(0,1,3)) {
            Map<String,Object> event=object(object(((List<?>)expected.get("items")).get(index)).get("data"));
            event.put("at",event.remove("occurred_at"));
        }
        for(boolean src:Arrays.asList(false,true)) {
            Map<String,Object> wire=CompactProtocol.success("events","s1","completed",input,false,src);
            assertEquals(expected,JsonCodec.decode(JsonCodec.encode(object(CompactProtocol.expandStructures(wire)).get("data"))));
        }
        assertEquals(original,JsonCodec.encode(input));
    }

    private static Map<String,Object> capture(String prefix,String context) {
        List<Object> controls=new ArrayList<>(),actions=new ArrayList<>(),items=new ArrayList<>();
        for(int i=0;i<12;i++) {
            controls.add(map("id",prefix+i,"parent","root","role","button","label","Visible choice "+i,
                    "enabled",true,"icon",map("symbol","free_cast"),
                    "shown",map("strength",map("value",14,"estimated",true),"status","1/20"),"future",Arrays.asList(null,false,0)));
            actions.add(map("action","ui.activate","control",prefix+i,"label","Visible choice "+i,"gestures",Arrays.asList("click","long")));
            items.add(map("locator","bag."+i,"name","Visible item "+i,"quantity",1,"description","Public functional description retained."));
        }
        Map<String,Object> cue=map("kind","pylon_lightning","cell",9,"source_cell",0,"direction","northeast",
                "appearance",map("cells",Arrays.asList(0,9),"partial",true));
        return map("scope_id","s1","state_version","r1","hero",map("turn_progress",map("sweep",0.5)),
                "ui",map("controls",controls),"actions",actions,"inventory",items,
                "visual_cues",map("status","last_observed","map_context",context,"cues",Arrays.asList(cue)));
    }
    private static Map<String,Object> node(Map<String,Object> data,int index){return object(((List<?>)object(data.get("ui")).get("nodes")).get(index));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
}
