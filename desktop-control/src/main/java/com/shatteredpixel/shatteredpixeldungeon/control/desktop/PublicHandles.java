package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.game.UiProjectionHints;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ProtocolException;
import java.util.*;

/** Typed identity projection before structural encoding. Never rewrites text or immutable wire history. */
public final class PublicHandles {
    private static final Set<String> OPAQUE=new HashSet<>(Arrays.asList("raw","reply","schema","text_sources","text_origins","text_diagnostics","pres","presentation","original_payload","raw_request","raw_bytes","request_json","response_json"));
    private static final Set<String> SCOPES=new HashSet<>(Arrays.asList("scope_id","menu_scope_id","origin_scope_id","target_scope","requested_scope","captured_scope"));
    private final AuditStore store;
    private final Map<String,String> known=new LinkedHashMap<>();
    public PublicHandles(AuditStore store){this.store=Objects.requireNonNull(store);}

    public synchronized String scope(String canonical){return one("scope",canonical);}
    public synchronized String session(String canonical){return one("session",canonical);}
    public synchronized String existingScope(String canonical){
        if(canonical==null)return null;
        if(canonical.matches("s[1-9a-z][0-9a-z]*"))return canonical;
        return known.get(AuditStore.handleKey("scope",canonical));
    }
    private String one(String kind,String canonical){
        if(canonical==null)return null;
        String key=AuditStore.handleKey(kind,canonical),value=known.get(key);
        if(value==null){value=store.publicHandle(kind,canonical);known.put(key,value);}
        return value;
    }
    public synchronized String lookupScope(String handle){
        String canonical=store.resolveHandle("scope",handle);
        if(canonical!=null)known.put(AuditStore.handleKey("scope",canonical),handle);
        return canonical;
    }
    public String resolveScope(String handle){
        String canonical=lookupScope(handle);
        if(canonical==null)throw new ProtocolException("UNKNOWN_SCOPE","Unknown scope handle in this profile");
        return canonical;
    }
    public String resolveVersion(String handle){
        if(handle==null)return null;
        return resolve(handle.startsWith("a")?"activity":"revision",handle,"UNKNOWN_REVISION");
    }
    private String resolve(String kind,String handle,String error){
        String value=store.resolveHandle(kind,handle);
        if(value==null)throw new ProtocolException(error,"Unknown "+kind+" handle in this profile");
        return value;
    }
    public synchronized Object encode(Object canonical){
        Map<String,Set<String>> missing=new LinkedHashMap<>();collect(canonical,missing);
        if(!missing.isEmpty())known.putAll(store.publicHandles(missing));
        return rewrite(canonical);
    }
    private static String kind(String field,Object value){
        if(!(value instanceof String))return null;
        if(SCOPES.contains(field))return "scope";
        if("state_version".equals(field))return ((String)value).startsWith("activity:")?"activity":"revision";
        if("session_id".equals(field))return "session";
        if("receipt_id".equals(field))return "save";
        if("map_context".equals(field))return "map";
        return null;
    }
    private void collect(Object value,Map<String,Set<String>> missing){
        if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){
            String key=String.valueOf(entry.getKey());if(OPAQUE.contains(key))continue;
            String type=kind(key,entry.getValue());
            if(type!=null){String identity=(String)entry.getValue();if(!known.containsKey(AuditStore.handleKey(type,identity)))missing.computeIfAbsent(type,k->new LinkedHashSet<>()).add(identity);}
            else collect(entry.getValue(),missing);
        }
        else if(value instanceof List)for(Object child:(List<?>)value)collect(child,missing);
    }
    private Object rewrite(Object value){
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(rewrite(child));return result;}
        if(!(value instanceof Map))return value;
        Map<String,Object> result=new LinkedHashMap<>();
        for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){
            String key=String.valueOf(entry.getKey());Object child=entry.getValue();String type=kind(key,child);
            result.put(key,OPAQUE.contains(key)?child:type==null?rewrite(child):known.get(AuditStore.handleKey(type,(String)child)));
        }
        return UiProjectionHints.preserve(value,result);
    }
}
