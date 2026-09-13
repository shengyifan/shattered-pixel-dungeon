package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.WireNames;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only request builder: canonical fake-engine arguments become actual V3 wire bytes.
 * Responses are deliberately never expanded: assertions inspect the production wire output. */
public final class V3Requests {
    private V3Requests() { }
    public static String encode(Map<String,Object> canonical) {
        if(canonical.containsKey("v")) return JsonCodec.encode(canonical);
        Map<String,Object> wire = new LinkedHashMap<>();
        for(String key : new String[]{"protocol_version", "id", "scope_id", "state_version"})
            if(canonical.containsKey(key)) wire.put("protocol_version".equals(key)?"v":WireNames.field(key), canonical.get(key));
        String operation = (String) canonical.get("op");
        Object args = canonical.get("args");
        if("action.execute".equals(operation) && args instanceof Map)
            operation = (String)((Map<?,?>)args).get("action");
        wire.put("op", WireNames.operation(operation));
        if(args instanceof Map) {
            for(Map.Entry<?,?> entry : ((Map<?,?>)args).entrySet()) {
                String key = (String)entry.getKey();
                if("action".equals(key)) continue;
                wire.put(WireNames.field(key), "direction".equals(key)?WireNames.direction(entry.getValue()):entry.getValue());
            }
        } else if(args != null) wire.put("args", args); // Preserve intentionally invalid fixtures.
        return JsonCodec.encode(wire);
    }
}
