import com.shatteredpixel.shatteredpixeldungeon.control.game.CompactProtocol;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.WireNames;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Offline test adapter only. Links to the supplied packaged jar, never a game or profile. */
public final class PackagedViewReplay {
    private static final Set<String> OPAQUE = new HashSet<>(Arrays.asList("raw","reply","schema","raw_request",
            "raw_bytes","request_json","response_json","original_payload","preserved_cells"));
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");

    public static void main(String[] args) throws Exception {
        Map<String,Object> frame=JsonCodec.decode(Files.readString(Paths.get(args[0]),StandardCharsets.UTF_8));
        String original=JsonCodec.encode(frame);
        @SuppressWarnings("unchecked") Map<String,Object> canonical=(Map<String,Object>)canonical(frame.get("data"),"");
        canonical.put("scope_id",frame.get("s"));canonical.put("state_version",frame.get("rev"));
        Map<String,Object> results=new LinkedHashMap<>();
        results.put("src",CompactProtocol.success((String)frame.get("id"),(String)frame.get("s"),(String)frame.get("st"),canonical,true,true,true));
        results.put("full",CompactProtocol.success((String)frame.get("id"),(String)frame.get("s"),(String)frame.get("st"),canonical,true,false,true));
        results.put("play",CompactProtocol.success((String)frame.get("id"),(String)frame.get("s"),(String)frame.get("st"),canonical,true,false,false));
        if(!original.equals(JsonCodec.encode(frame)))throw new AssertionError("Adapter changed the frozen input");
        Files.writeString(Paths.get(args[1]),JsonCodec.encode(results)+"\n",StandardCharsets.UTF_8);
    }

    private static Object canonical(Object value,String context) {
        if(value instanceof List) {
            List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(canonical(child,context));return result;
        }
        if(!(value instanceof Map))return value;
        Map<?,?> source=(Map<?,?>)value;Map<String,Object> result=new LinkedHashMap<>();
        for(Map.Entry<?,?> entry:source.entrySet()) {
            String key=(String)entry.getKey();Object child=entry.getValue();
            // These are derived by CompactProtocol from the complete global
            // action list. Reattaching a second copy would duplicate every op;
            // the caller requires exact full reconstruction before accepting it.
            if(context.equals("nodes")&&key.equals("ops"))continue;
            if(OPAQUE.contains(key)||key.equals("pres")){result.put(key,child);continue;}
            if(Arrays.asList("text_sources","text_origins","text_diagnostics").contains(key)) {
                if(child instanceof Map) {
                    Map<String,Object> metadata=new LinkedHashMap<>();
                    for(Map.Entry<?,?> field:((Map<?,?>)child).entrySet())metadata.put(path((String)field.getKey()),field.getValue());
                    result.put(key,metadata);
                }else result.put(key,child);
                continue;
            }
            if(context.equals("map")&&(key.equals("w")||key.equals("h"))) {
                result.put(key.equals("w")?"width":"height",child);continue;
            }
            if(key.equals("op")&&child instanceof String&&WireNames.canonicalOperation((String)child)!=null) {
                result.put("action",WireNames.canonicalOperation((String)child));continue;
            }
            String name=key.equals("cues")&&child instanceof List?key:WireNames.canonicalField(key);
            if(key.equals("dir")&&child instanceof String&&WireNames.DIRECTIONS.contains(child))child=WireNames.canonicalDirection(child);
            if(key.equals("via")&&child instanceof String&&WireNames.canonicalOperation((String)child)!=null)child=WireNames.canonicalOperation((String)child);
            Object prior=result.put(name,canonical(child,key));
            if(prior!=null)throw new AssertionError("Canonical field collision: "+name);
        }
        return result;
    }

    private static String path(String value) {
        Matcher matcher=FIELD.matcher(value);StringBuffer result=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(result,Matcher.quoteReplacement(WireNames.canonicalField(matcher.group())));
        matcher.appendTail(result);return result.toString();
    }
}
