package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.game.CompactProtocol;
import com.shatteredpixel.shatteredpixeldungeon.control.game.UiProjectionHints;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Replays public transport only; its fresh isolated audit pair contains handle mappings, never saves. */
public final class Cli6TokenSamples {
    private static final Set<String> OPAQUE=new HashSet<>(Arrays.asList("raw","reply","schema","original_payload","text_sources","text_origins","pres","text_diagnostics","presentation"));
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Usage: Cli6TokenSamples <canonical-public.jsonl> <identity-maps.json> <output-directory>");
        Path output=Path.of(args[2]);Files.createDirectories(output);
        Map<String,Object> maps=JsonCodec.decode(Files.readString(Path.of(args[1])));
        Path auditPath=Files.createTempDirectory(output,"isolated-handles-");
        int count=0;
        try(AuditStore audit=new AuditStore(auditPath);
            BufferedReader reader=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8);
            BufferedWriter play=Files.newBufferedWriter(output.resolve("production-play.jsonl"),StandardCharsets.UTF_8);
            BufferedWriter full=Files.newBufferedWriter(output.resolve("production-full.jsonl"),StandardCharsets.UTF_8);
            BufferedWriter expected=Files.newBufferedWriter(output.resolve("mapped-canonical.jsonl"),StandardCharsets.UTF_8);
            BufferedWriter requests=Files.newBufferedWriter(output.resolve("production-requests.jsonl"),StandardCharsets.UTF_8)) {
            PublicHandles handles=new PublicHandles(audit);
            for(String line;(line=reader.readLine())!=null;) {
                Map<String,Object> row=JsonCodec.decode(line);
                String scope=row.get("s") instanceof String?handles.scope((String)row.get("s")):null;
                Map<String,Object> canonical=row.get("canonical") instanceof Map?object(identities(row.get("canonical"),maps)):null;
                if(canonical!=null) {
                    bindDisclosedLocators(canonical);
                    if(canonical.containsKey("cli_version")) {
                        canonical.put("cli_version",com.shatteredpixel.shatteredpixeldungeon.control.game.BuildCatalog.current().get("cli_version"));canonical.put("audit_schema_version",9);
                        canonical.put("schema",CompactProtocol.info());
                        canonical.put("request_prefix",handles.session((String)canonical.get("session_id")));
                        canonical.put("capabilities",Arrays.asList("serial","request_ids","duplicate_rejection","player_observation","paired_audit","source_text","partial_presentation","persistent_handles"));
                    }
                    canonical=object(handles.encode(canonical));
                }
                String id=(String)object(maps.get("requests")).get(row.get("id"));
                for(boolean expanded:Arrays.asList(false,true)) {
                    Map<String,Object> reply=row.get("err")!=null?CompactProtocol.failure(id,scope,(String)row.get("err"))
                            :CompactProtocol.success(id,scope,(String)row.get("st"),canonical,Boolean.TRUE.equals(row.get("live")),false,expanded);
                    if(row.get("err")!=null && canonical!=null)reply.put("data",CompactProtocol.project(canonical,false,expanded));
                    write(expanded?full:play,reply);
                }
                write(expected,map("id",id,"canonical",canonical,"live",row.get("live")));
                Map<String,Object> request=object(identities(row.get("request"),maps));
                // Decode only known protocol identity fields; this is representation comparison,
                // not execution or correction of the historically invalid request.
                if(request.get("s") instanceof String)request.put("s",handles.scope((String)request.get("s")));
                if(request.get("rev") instanceof String)request.put("rev",object(handles.encode(map("state_version",request.get("rev")))).get("state_version"));
                if(request.containsKey("v"))request.put("v",6);
                write(requests,request);count++;
            }
        }
        Files.writeString(output.resolve("encoder-policy.json"),JsonCodec.encode(map("encoder","production CompactProtocol + PublicHandles + AuditStore", "protocol",6,
                "frames",count,"isolated_handle_store",auditPath.toString(),"invented_empty_slot_hints",false,
                "binding_evidence","Only explicit loc values already emitted in the original UI nodes; no item-name guessing",
                "full_limit","Only supplied public values can be expanded; absent source descriptions and capture-only placeholders cannot be reconstructed"))+"\n");
        System.out.println("Projected "+count+" public replies through production CLI 6 with isolated persistent handles");
    }

    private static Object identities(Object value,Map<String,Object> maps) {
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(identities(child,maps));return result;}
        if(!(value instanceof Map))return value;
        Map<String,Object> result=new LinkedHashMap<>();
        for(Map.Entry<String,Object> entry:object(value).entrySet()) {
            String key=entry.getKey();Object child=entry.getValue();
            if(OPAQUE.contains(key)){result.put(key,child);continue;}
            boolean requestField=Arrays.asList("id","target_id","origin_request_id","rid","src_id").contains(key);
            boolean controlField=Arrays.asList("id","parent","control","ctl").contains(key);
            if(requestField && object(maps.get("requests")).containsKey(child))child=object(maps.get("requests")).get(child);
            else if(controlField && object(maps.get("controls")).containsKey(child))child=object(maps.get("controls")).get(child);
            else child=identities(child,maps);
            result.put(key,child);
        }
        return result;
    }

    /** The old protocol documented loc as capture-local identity evidence, not a text match. */
    private static void bindDisclosedLocators(Map<String,Object> canonical) {
        Map<String,Object> observation=canonical.get("observation") instanceof Map?object(canonical.get("observation")):canonical;
        if(!(observation.get("inventory") instanceof List) || !(observation.get("ui") instanceof Map))return;
        Map<String,Object> ui=object(observation.get("ui"));if(!(ui.get("controls") instanceof List))return;
        Map<String,UiProjectionHints.Node> hints=new LinkedHashMap<>();
        for(Object raw:(List<?>)ui.get("controls")) {
            if(!(raw instanceof Map))continue;Map<String,Object> node=object(raw);
            if(node.get("id") instanceof String && node.get("locator") instanceof String)
                hints.put((String)node.get("id"),new UiProjectionHints.Node(false,Collections.emptyList(),(String)node.get("locator"),Collections.emptyMap()));
        }
        observation.put("ui",new UiProjectionHints(hints).attach(ui));
    }

    private static void write(BufferedWriter output,Object value) throws IOException {output.write(JsonCodec.encode(value));output.newLine();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
}
