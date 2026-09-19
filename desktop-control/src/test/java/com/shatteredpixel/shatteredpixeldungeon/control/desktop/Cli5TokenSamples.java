package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.game.CompactProtocol;
import com.shatteredpixel.shatteredpixeldungeon.control.game.UiProjectionHints;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.PublicTextSources;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Offline projection of explicitly supplied public observations, never a save/profile reader. */
public final class Cli5TokenSamples {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if(args.length != 2) throw new IllegalArgumentException("Usage: Cli5TokenSamples <public-input.jsonl> <output-directory>");
        Path destination = Path.of(args[1]); Files.createDirectories(destination);
        int count = 0;
        try(BufferedReader input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
            BufferedWriter play = Files.newBufferedWriter(destination.resolve("projected-play.jsonl"), StandardCharsets.UTF_8);
            BufferedWriter full = Files.newBufferedWriter(destination.resolve("projected-full.jsonl"), StandardCharsets.UTF_8)) {
            for(String line; (line = input.readLine()) != null;) {
                Map<String,Object> row = JsonCodec.decode(line);
                Map<String,Object> canonical = (Map<String,Object>) row.get("canonical");
                if(canonical != null && canonical.containsKey("cli_version")) {
                    canonical = new LinkedHashMap<>(canonical);
                    canonical.put("cli_version", "CLI.6.0.0");
                    canonical.put("protocol_version", 6);
                    canonical.put("audit_schema_version", 9);
                    canonical.put("schema", CompactProtocol.info());
                }
                for(boolean expanded : Arrays.asList(false,true)) {
                    Map<String,Object> projected = row.get("err") != null
                            ? CompactProtocol.failure((String)row.get("id"),(String)row.get("s"),(String)row.get("err"))
                            : CompactProtocol.success((String)row.get("id"),(String)row.get("s"),(String)row.get("st"),
                                    canonical,Boolean.TRUE.equals(row.get("live")),false,expanded);
                    if(row.get("err") != null && canonical != null)
                        projected.put("data",CompactProtocol.project(canonical,false,expanded));
                    BufferedWriter output = expanded ? full : play;
                    output.write(JsonCodec.encode(projected)); output.write('\n');
                }
                count++;
            }
        }
        Files.writeString(destination.resolve("encoder-policy.json"), JsonCodec.encode(map(
                "encoder","production CompactProtocol", "protocol",6,
                "ordinary_source_kinds",new ArrayList<>(PublicTextSources.ORDINARY_KINDS),
                "ordinary_source_origins",new ArrayList<>(PublicTextSources.ORDINARY_ORIGINS),
                "historical_capture_hints",false,
                "full_limit","Full projects only supplied public evidence; omitted historical descriptions and talents cannot be reconstructed"))+"\n");
        Files.writeString(destination.resolve("synthetic-fixtures.json"),JsonCodec.encode(fixtures())+"\n");
        System.out.println("Projected " + count + " recorded public replies with production CLI 6 play/full views; historical capture hints are absent");
    }

    /** Explicit synthetic public records exercise capture hints that old wire frames cannot establish. */
    private static List<Object> fixtures() {
        List<Object> fixtures = new ArrayList<>();
        Map<String,Object> slot = map("id","slot","role","button","text","4/20\n14?\n+2","label","Visible item","enabled",true);
        List<Object> nodes = Arrays.asList(slot,
                map("id","status","role","text","parent","slot","text","4/20","enabled",true),
                map("id","extra","role","text","parent","slot","text","14?","enabled",true),
                map("id","level","role","text","parent","slot","text","+2","enabled",true),
                map("id","bar","role","health_bar","cell",145,"total_pixels",48,"health_pixels",23,"health_and_shield_pixels",30,"measurement","rendered_pixels"));
        UiProjectionHints hints = new UiProjectionHints(Collections.singletonMap("slot",new UiProjectionHints.Node(false,
                Arrays.asList("status","extra","level"),"backpack.0",stringMap("status","status","extra","extra","level","level"))));
        Map<String,Object> ui = hints.attach(map("controls",nodes,"modal",false,"inspected_item",null));
        Map<String,Object> item = map("locator","backpack.0","name","Visible item","quantity",1,"equipped",false,
                "type_known",true,"available",true,"level_known",false,"level",null,"curse_known",false,"cursed",null,"details_via","ui.activate");
        fixtures.add(fixture("rendered_item_and_bar",map("scene","game","inventory",Arrays.asList(item),"ui",ui,
                "actions",Arrays.asList(map("action","ui.activate","control","slot","gestures",Arrays.asList("click","right"))))));

        List<Object> cells = new ArrayList<>();
        for(int cell=0;cell<3;cell++)cells.add(map("cell",cell,"x",cell,"y",0,"terrain",1,"name","Floor","visibility","visible",
                "environment",Arrays.asList(map("type","fire","description","Visible fire warning"),map("type","gas","description","Visible gas warning"))));
        fixtures.add(fixture("repeated_hazard_descriptors",map("scene","game","map",map("width",3,"height",1,"cells",cells),
                "visible_entities",Arrays.asList(map("cell",0,"kind","trap","name","Trap","description","Complete trap warning"),
                        map("cell",2,"kind","trap","name","Trap","description","Complete trap warning")))));
        List<Object> overflow = new ArrayList<>();
        for(int cell=0;cell<65;cell++)overflow.add(map("cell",cell,"x",cell,"y",0,"terrain",cell,"name","Terrain "+cell,"visibility","mapped","environment",Collections.emptyList()));
        fixtures.add(fixture("integer_map_rows",map("scene","game","map",map("width",65,"height",1,"cells",overflow))));
        fixtures.add(fixture("protected_public_sources",map("scene","menu","ui",map("controls",Arrays.asList(
                map("id","external","role","text","text","External warning","text_sources",map("text",map("kind","literal","origin","external","value","External warning"))),
                map("id","future","role","text","text","Future warning","text_sources",map("text",map("kind","future_kind","description","Literal AST key"))),
                map("id","partial","role","text","text","Visible fragment","clipped",true,"text_diagnostics",map("text","clipped_text")))))));
        // Representative shapes from VisualSnapshot.stateData and the existing public boss
        // smoke assertions. These are fabricated public records, never engine-render evidence.
        Map<String,Object> bomb = bossWarning("fixture:tengu",10,Arrays.asList(
                map("kind","bomb_smoke","cell",5),map("kind","bomb_smoke","cell",6),
                map("kind","bomb_countdown_3","cell",6)));
        bomb.put("ui",map("controls",Collections.singletonList(map("id","countdown","role","text",
                "text","3...","presentation","floating_text","enabled",true))));
        fixtures.add(fixture("synthetic_boss_bomb_warning",bomb));
        fixtures.add(fixture("synthetic_boss_summoning_warning",bossWarning("fixture:king",20,Arrays.asList(
                map("kind","summoning_bones","cell",5),map("kind","summoning_shadows","cell",6),
                map("kind","summoning_green_flames","cell",9),map("kind","summoning_sparks","cell",10)))));
        return fixtures;
    }

    private static Map<String,Object> bossWarning(String mapContext, int depth, List<Object> cues) {
        List<Object> cells = new ArrayList<>();
        for(int cell : Arrays.asList(5,6,9,10))cells.add(map("cell",cell,"x",cell%4,"y",cell/4,"terrain",1,
                "name","Floor","visibility","visible","environment",Collections.emptyList()));
        return map("scene","game","phase","player_ready","map",map("width",4,"height",3,"cells",cells),
                "visual_cues",map("status","last_rendered","depth",depth,"map_context",mapContext,"cues",cues));
    }

    private static Map<String,String> stringMap(String... pairs) {
        Map<String,String> result = new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],pairs[i+1]);
        return result;
    }

    private static Map<String,Object> fixture(String name, Map<String,Object> canonical) {
        return map("name",name,"evidence","synthetic public fixture; not a gameplay observation","canonical",canonical,
                "play",CompactProtocol.success(name,"fixture", "completed",canonical,true,false,false),
                "full",CompactProtocol.success(name,"fixture", "completed",canonical,true,false,true));
    }
}
