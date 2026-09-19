package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.WireNames;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only request builder: canonical fake-engine arguments become actual V6 wire bytes.
 * Responses are deliberately never expanded: assertions inspect the production wire output. */
public final class V6Requests {
    private V6Requests() { }
    public static String encode(AuditStore store, Map<String,Object> canonical) {
        if(canonical.containsKey("v")) return JsonCodec.encode(handles(store, canonical));
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
        return JsonCodec.encode(handles(store, wire));
    }
    private static Map<String,Object> handles(AuditStore store, Map<String,Object> wire) {
        Map<String,Object> result = new LinkedHashMap<>(wire);
        Object scope = result.get("s"), revision = result.get("rev");
        if(scope instanceof String) result.put("s",store.publicHandle("scope",(String)scope));
        if(revision instanceof String) result.put("rev",store.publicHandle(
                ((String)revision).startsWith("activity:")?"activity":"revision",(String)revision));
        return result;
    }
}
