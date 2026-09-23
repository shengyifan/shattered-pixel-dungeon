package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Synthetic public rendering values only; these tests make no engine-visibility claim. */
public class CompactCombatVisualsTest {
    @Test public void everyViewPreservesExactAppearanceAndQuantitativeDraws() {
        Map<String,Object> input=capture("c", "m1");String original=JsonCodec.encode(input);
        for(boolean full:Arrays.asList(false,true))for(boolean src:Arrays.asList(false,true)) {
            Map<String,Object> wire=CompactProtocol.success("q","s1","completed",input,true,src,full);
            Map<String,Object> packed=object(wire.get("data"));
            assertTrue("The regression must exercise packed UI rows",object(packed.get("ui")).containsKey("node_templates"));
            assertTrue(packed.containsKey("act_templates"));assertTrue(packed.containsKey("inv_templates"));
            Map<String,Object> data=object(object(CompactProtocol.expandStructures(wire)).get("data"));
            Map<String,Object> cues=object(data.get("cues"));
            assertEquals("m1",cues.get("map_context"));
            assertEquals("2026-09-23T00:00:00.123Z",cues.get("metrics_at"));
            Map<String,Object> cue=object(((List<?>)cues.get("cues")).get(0));
            assertEquals(0,cue.get("source_cell"));assertEquals("NE",cue.get("dir"));
            assertEquals(0,cue.get("color"));assertEquals(0.5,cue.get("opacity"));
            assertEquals(appearance(),cue.get("appearance"));
            assertEquals(object(input.get("visual_cues")).get("metrics"),cues.get("metrics"));
            assertEquals(appearance(),node(data,0).get("icon"));
            assertEquals(overlay(),node(data,0).get("icon_overlay"));
            assertEquals(map("status","1","extra","14?","level","+0"),node(data,0).get("display"));
            assertEquals(Arrays.asList(null,false,0),node(data,0).get("future"));
            assertEquals("c0",node(data,0).get("id"));
            assertEquals("root",node(data,0).get("parent"));
            assertEquals(0,node(data,0).get("color"));
            assertEquals(Arrays.asList("click","long"),object(((List<?>)node(data,0).get("ops")).get(0)).get("gestures"));
        }
        assertEquals("Projection cannot rewrite the frozen source",original,JsonCodec.encode(input));
    }

    @Test public void multilingualStyleRunsKeepProvenanceAndAddressablePartialDiagnostics() {
        Map<String,Object> source=map("kind","literal","origin","external","value","旅人 🗡");
        Map<String,Object> first=map("text","旅人 🗡","color",0,"text_sources",map("text",source));
        Map<String,Object> clipped=map("text","Text unavailable","color",0xFF8800,"clipped",true,
                "text_diagnostics",map("text","clipped_text"));
        Map<String,Object> input=map("ui",map("controls",Arrays.asList(map("id","floating","role","text",
                "presentation","floating_text","cell",0,"styles",Arrays.asList(first,clipped),
                "icon",map("atlas","floating_text","index",0)))));
        String original=JsonCodec.encode(input);
        for(boolean full:Arrays.asList(false,true))for(boolean src:Arrays.asList(false,true)) {
            Map<String,Object> wire=CompactProtocol.success("q","s1","completed",input,true,src,full);
            Map<String,Object> text=node(object(object(CompactProtocol.expandStructures(wire)).get("data")),0);
            List<?> styles=(List<?>)text.get("styles");assertEquals(2,styles.size());
            assertEquals("旅人 🗡",object(styles.get(0)).get("text"));assertEquals(0,object(styles.get(0)).get("color"));
            assertEquals(src?map("text",source):map("text",Arrays.asList("external")),
                    object(styles.get(0)).get(src?"text_sources":"text_origins"));
            assertEquals(true,object(styles.get(1)).get("clipped"));
            assertEquals(0xFF8800,object(styles.get(1)).get("color"));
            assertEquals(Arrays.asList(map("field","$.data.ui.nodes[0].styles[1].text","code","clipped_text")),
                    object(wire.get("pres")).get("diag"));
            assertEquals("floating_text",text.get("presentation"));assertEquals(0,text.get("cell"));
        }
        assertEquals(original,JsonCodec.encode(input));
    }

    @Test public void historicalVisualSnapshotsOwnTablesAndDoNotRewriteOpaqueEvidence() {
        Map<String,Object> before=capture("b","m2"),after=capture("a","m3");
        before.put("scope_id","s2");before.put("state_version","r2");
        after.put("scope_id","s3");after.put("state_version","r3");
        object(((List<?>)object(after.get("visual_cues")).get("cues")).get(0)).put("color",0xFF8800);
        Map<String,Object> opaque=map("v",7,"ui",map("node_templates",false,"nodes",Arrays.asList(Arrays.asList(-1))),
                "styles",Arrays.asList(map("text","原始","color",0)),"direction","north", "text_sources",map("raw","unchanged"));
        Map<String,Object> input=map("before",before,"after",after,"raw",opaque,"reply",opaque,
                "future_extension",map("act_templates",false,"ui",map("node_templates",null),"appearance",appearance()));
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
            assertEquals(0,object(((List<?>)object(b.get("cues")).get("cues")).get(0)).get("color"));
            assertEquals(0xFF8800,object(((List<?>)object(a.get("cues")).get("cues")).get(0)).get("color"));
            assertEquals(opaque,decoded.get("raw"));assertEquals(opaque,decoded.get("reply"));
            assertEquals(input.get("future_extension"),decoded.get("future_extension"));
            assertEquals("r2",b.get("rev"));assertEquals("r3",a.get("rev"));
            assertEquals(1,object(((List<?>)b.get("inv")).get(0)).get("qty"));
            assertEquals(1,object(((List<?>)a.get("inv")).get(0)).get("qty"));
            object(node(a,0).get("icon")).put("alpha",0.125);
            assertEquals(0.75,object(node(b,0).get("icon")).get("alpha"));
        }
        assertEquals(original,JsonCodec.encode(input));
    }

    @Test public void floatingBannerAndMetricHistoryRetainsOccurrenceOrderAndClearingSnapshots() {
        Map<String,Object> floating=map("format","display_snapshot_v1","occurred_at","2026-09-23T00:00:00Z",
                "depth",5,"map_context","m1","entries",Arrays.asList(map("text","1","color",0,"cell",0,
                        "clipped",false,"icon",map("atlas","floating_text","index",0))));
        Map<String,Object> banner=map("format","display_occurrence_v1","occurred_at","2026-09-23T00:00:01Z",
                "kind","boss_slain","appearance",appearance());
        Map<String,Object> metric=map("format","sampled_display_snapshot_v1","sample_period_ms",250,
                "occurred_at","2026-09-23T00:00:00.250Z","metrics",Collections.emptyList());
        List<Object> events=Arrays.asList(map("kind","game.floating_text","sequence",1,"data",floating),
                map("kind","game.floating_text","sequence",2,"data",floating),
                map("kind","game.visual","sequence",3,"data",map("cues",Collections.emptyList())),
                map("kind","game.visual_metrics","sequence",4,"data",metric),
                map("kind","game.banner","sequence",5,"data",banner));
        Map<String,Object> input=map("items",events);String original=JsonCodec.encode(input);
        Map<String,Object> expected=JsonCodec.decode(original);
        for(int index:Arrays.asList(0,1,3,4)) {
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
                    "enabled",true,"color",0,"icon",appearance(),"icon_overlay",overlay(),
                    "display",map("status","1","extra","14?","level","+0"),"future",Arrays.asList(null,false,0)));
            actions.add(map("action","ui.activate","control",prefix+i,"label","Visible choice "+i,"gestures",Arrays.asList("click","long")));
            items.add(map("locator","bag."+i,"name","Visible item "+i,"quantity",1,"description","Exact public description retained with rendered appearance."));
        }
        Map<String,Object> cue=map("kind","pylon_lightning","cell",9,"source_cell",0,"direction","northeast",
                "color",0,"opacity",0.5,"appearance",appearance());
        return map("scope_id","s1","state_version","r1","ui",map("controls",controls),"actions",actions,"inventory",items,
                "visual_cues",map("status","last_rendered","map_context",context,"cues",Arrays.asList(cue),
                        "metrics_at","2026-09-23T00:00:00.123Z","metrics",Arrays.asList(map("kind","cell_particles","cell",0,
                                "rendered_particles",2,"appearance",map("shape","pixel","color",0,"alpha",0.5,"size",Arrays.asList(1,2))))));
    }
    private static Map<String,Object> appearance() {
        return map("atlas","sprites/items.png","frame_pixels",Arrays.asList(0,16,16,32),"texture_size",Arrays.asList(256,256),
                "tint",map("multiply",Arrays.asList(1.0,0.5,0.0),"add",Arrays.asList(0.0,0.0,0.0)),
                "alpha",0.75,"angle",0,"scale",Arrays.asList(1,1),"flip_horizontal",false,"flip_vertical",false,
                "layers",Arrays.asList(map("order",0,"atlas","sprites/items.png"),map("order",1,"color",0)));
    }
    private static Map<String,Object> overlay() {return map("axis","vertical","total_pixels",16,"covered_pixels",0,"measurement","rendered_pixels");}
    private static Map<String,Object> node(Map<String,Object> data,int index) {return object(((List<?>)object(data.get("ui")).get("nodes")).get(index));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
}
