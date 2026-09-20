package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Same-frame protocol-6 structures. This class has no engine, profile or cross-frame state. */
final class CompactStructures {
    private static final Set<String> OPAQUE = new HashSet<>(Arrays.asList(
            "raw", "reply", "schema", "raw_request", "raw_bytes", "request_json", "response_json",
            "original_payload", "text_sources", "text_origins", "pres", "preserved_cells"));
    private static final Set<String> NO_CAPS = new HashSet<>(Arrays.asList("a", "an", "and", "of", "by", "to", "the", "x", "for"));
    private CompactStructures() { }

    static void compact(Object value) { compact(value, false); }

    private static void compact(Object value, boolean expanded) {
        if (value instanceof List) {for (Object child : (List<?>)value) compact(child, expanded); return;}
        if (!(value instanceof Map)) return;
        Map<String,Object> current=object(value);
        // An ancestor-owned path can address nodes by index. Keep its whole subtree addressable.
        expanded |= metadata(current);
        if (!expanded && current.get("nodes") instanceof List) compactUi(current);
        for (Map.Entry<String,Object> entry : current.entrySet())
            if (!OPAQUE.contains(entry.getKey())) compact(entry.getValue(), expanded || "before".equals(entry.getKey()) || "after".equals(entry.getKey()));
    }

    private static void compactUi(Map<String,Object> ui) {
        if (ui.containsKey("op_defs") || ui.containsKey("node_shapes")) return;
        List<Object> nodes=new ArrayList<>();
        for (Object raw:(List<?>)ui.get("nodes")) {
            if (!(raw instanceof Map)) return;
            Map<String,Object> node=new LinkedHashMap<>(object(raw));
            nodes.add(node);
        }
        ui.put("nodes",nodes);

        Map<List<?>,Integer> counts=new LinkedHashMap<>();
        for (Object raw:nodes) {
            Object ops=object(raw).get("ops");
            if (ops instanceof List && !containsMetadata(raw)) counts.put((List<?>)ops,counts.getOrDefault(ops,0)+1);
        }
        List<Object> definitions=new ArrayList<>();
        for(Map.Entry<List<?>,Integer> entry:counts.entrySet()) {
            if(entry.getValue()<2)continue;
            List<Object> candidateNodes=copyNodes(nodes),candidateDefinitions=new ArrayList<>(definitions);
            int index=definitions.size();candidateDefinitions.add(entry.getKey());
            for(Object raw:candidateNodes)if(!containsMetadata(raw) && Objects.equals(object(raw).get("ops"),entry.getKey()))object(raw).put("ops",index);
            Map<String,Object> candidate=new LinkedHashMap<>(ui);
            candidate.put("nodes",candidateNodes);candidate.put("op_defs",candidateDefinitions);
            // Each accepted definition must independently pay for itself, including table overhead.
            if(bytes(candidate)<bytes(ui)) {
                ui.put("nodes",candidateNodes);ui.put("op_defs",candidateDefinitions);
                nodes=candidateNodes;definitions=candidateDefinitions;
            }
        }

        List<Object> shapes=new ArrayList<>(),rows=new ArrayList<>();Map<List<String>,Integer> shapeIndexes=new LinkedHashMap<>();
        for(Object raw:nodes) {
            if(containsMetadata(raw)){rows.add(raw);continue;}
            Map<String,Object> node=object(raw);List<String> shape=new ArrayList<>(node.keySet());
            Integer index=shapeIndexes.get(shape);
            if(index==null){index=shapes.size();shapeIndexes.put(shape,index);shapes.add(shape);}
            List<Object> row=new ArrayList<>();row.add(index);row.addAll(node.values());rows.add(row);
        }
        Map<String,Object> candidate=new LinkedHashMap<>(ui);candidate.put("nodes",rows);candidate.put("node_shapes",shapes);
        if(!rows.isEmpty() && bytes(candidate)<bytes(ui)) {ui.put("nodes",rows);ui.put("node_shapes",shapes);}
    }

    private static List<Object> copyNodes(List<?> nodes) {
        List<Object> copy=new ArrayList<>();for(Object node:nodes)copy.add(new LinkedHashMap<>(object(node)));return copy;
    }

    private static boolean metadata(Map<String,Object> value) {
        for(String key:Arrays.asList("text_sources","text_origins","text_diagnostics","pres","presentation"))
            if(value.get(key) instanceof Map && !((Map<?,?>)value.get(key)).isEmpty())return true;
        return Boolean.TRUE.equals(value.get("clipped"));
    }

    private static boolean containsMetadata(Object value) {
        if(value instanceof Map) {
            if(metadata(object(value)))return true;
            for(Object child:object(value).values())if(containsMetadata(child))return true;
        } else if(value instanceof List)for(Object child:(List<?>)value)if(containsMetadata(child))return true;
        return false;
    }

    /** Only the live response envelope establishes these defaults; history remains expanded. */
    static void compactBindings(Map<String,Object> response) {
        if(!(response.get("data") instanceof Map))return;
        Map<String,Object> data=object(response.get("data"));
        if(metadata(data))return;
        if(data.get("activity") instanceof Map && !containsMetadata(data.get("activity"))) {
            Map<String,Object> activity=object(data.get("activity"));
            Object revision=activity.get("rev");
            if(revision!=null && Objects.equals(revision,response.get("rev")))activity.remove("rev");
            if(data.get("acts") instanceof List)for(Object raw:(List<?>)data.get("acts")) {
                if(!(raw instanceof Map) || containsMetadata(raw))continue;
                Map<String,Object> action=object(raw);
                if(!"cancel".equals(action.get("op")))continue;
                if(revision!=null && Objects.equals(revision,action.get("rev")))action.remove("rev");
                if(activity.get("rid")!=null && Objects.equals(activity.get("rid"),action.get("rid")))action.remove("rid");
            }
        }
        compactSaveScopes(data.get("saved"),response.get("s"));
        if(!(data.get("persistence") instanceof Map) || containsMetadata(data.get("persistence")))return;
        Map<String,Object> persistence=object(data.get("persistence"));
        Object saved=persistence.get("saved"),saves=persistence.get("saves");
        int index=saves instanceof List ? ((List<?>)saves).indexOf(saved) : -1;
        if(saved instanceof Map && index>=0)persistence.put("saved",index);
        for(String field:Arrays.asList("saves","saved"))compactSaveScopes(persistence.get(field),response.get("s"));
    }

    private static void compactSaveScopes(Object value,Object scope) {
        if(value instanceof List){for(Object child:(List<?>)value)compactSaveScopes(child,scope);return;}
        if(!(value instanceof Map))return;
        Map<String,Object> receipt=object(value);
        if(!receipt.containsKey("sid") || containsMetadata(receipt))return;
        Object actualScope=receipt.get("s");
        if(actualScope!=null && Objects.equals(actualScope,receipt.get("src_s")))receipt.remove("src_s");
        if(scope!=null && Objects.equals(actualScope,scope))receipt.remove("s");
    }

    static Object expand(Object value) {return expand(value,Collections.emptyMap(),null,null);}

    private static Object expand(Object value,Map<String,Map<String,Object>> inventory,Object scope,Object revision) {
        if(value instanceof List){List<Object> list=new ArrayList<>();for(Object child:(List<?>)value)list.add(expand(child,inventory,scope,revision));return list;}
        if(!(value instanceof Map))return value;
        Map<String,Object> input=object(value),result=new LinkedHashMap<>();
        if(input.containsKey("s"))scope=input.get("s");
        if(input.containsKey("rev"))revision=input.get("rev");
        if(input.get("inv") instanceof List) {
            inventory=new LinkedHashMap<>();Set<String> ambiguous=new HashSet<>();
            for(Object raw:(List<?>)input.get("inv"))if(raw instanceof Map && object(raw).get("loc") instanceof String) {
                String locator=(String)object(raw).get("loc");if(inventory.put(locator,object(raw))!=null)ambiguous.add(locator);
            }
            for(String locator:ambiguous)inventory.remove(locator);
        }
        for(Map.Entry<String,Object> entry:input.entrySet()) {
            String key=entry.getKey();Object child=entry.getValue();
            if(input.get("nodes") instanceof List && Arrays.asList("op_defs","node_shapes").contains(key))continue;
            if("nodes".equals(key) && child instanceof List) {
                List<Object> nodes=new ArrayList<>();
                for(Object raw:(List<?>)child) {
                    Map<String,Object> node;
                    if(raw instanceof List) {
                        List<?> row=(List<?>)raw;if(row.isEmpty())throw new IllegalArgumentException("Empty UI node row");
                        Object shapeValue=definition(input.get("node_shapes"),row.get(0));
                        if(!(shapeValue instanceof List))throw new IllegalArgumentException("Invalid node shape");
                        List<?> shape=(List<?>)shapeValue;if(shape.size()!=row.size()-1)throw new IllegalArgumentException("UI row width mismatch");
                        node=new LinkedHashMap<>();
                        for(int i=0;i<shape.size();i++) {
                            if(!(shape.get(i) instanceof String) || node.containsKey(shape.get(i)))throw new IllegalArgumentException("Invalid node shape field");
                            node.put((String)shape.get(i),row.get(i+1));
                        }
                    } else if(raw instanceof Map)node=new LinkedHashMap<>(object(raw));
                    else throw new IllegalArgumentException("Invalid UI node");
                    if(node.get("ops") instanceof Number)node.put("ops",definition(input.get("op_defs"),node.get("ops")));
                    if(node.containsKey("ops") && !(node.get("ops") instanceof List))throw new IllegalArgumentException("Invalid operation list");
                    if(node.get("label") instanceof Number) {
                        Object name=inventory.containsKey(node.get("loc"))?inventory.get(node.get("loc")).get("name"):null;
                        int code=integer(node.get("label"));
                        if(!(name instanceof String) || code<0 || code>1)throw new IllegalArgumentException("Unbound inherited item label");
                        node.put("label",code==0?name:titleCase((String)name));
                    }
                    if(node.get("label")!=null && !(node.get("label") instanceof String))throw new IllegalArgumentException("Invalid item label");
                    nodes.add(expand(node,inventory,scope,revision));
                }
                result.put(key,nodes);
            } else result.put(key,OPAQUE.contains(key)?child:expand(child,inventory,scope,revision));
        }
        if(result.get("activity") instanceof Map) {
            Map<String,Object> activity=object(result.get("activity"));
            if(!activity.containsKey("rev") && revision!=null)activity.put("rev",revision);
            if(result.get("acts") instanceof List)for(Object raw:(List<?>)result.get("acts"))if(raw instanceof Map && "cancel".equals(object(raw).get("op"))) {
                Map<String,Object> cancel=object(raw);
                if(!cancel.containsKey("rev") && activity.containsKey("rev"))cancel.put("rev",activity.get("rev"));
                if(!cancel.containsKey("rid") && activity.containsKey("rid"))cancel.put("rid",activity.get("rid"));
            }
        }
        expandSaveScopes(result.get("saved"),scope);
        if(result.get("persistence") instanceof Map) {
            Map<String,Object> persistence=object(result.get("persistence"));
            for(String field:Arrays.asList("saves","saved"))expandSaveScopes(persistence.get(field),scope);
            if(persistence.get("saved") instanceof Number)persistence.put("saved",expand(definition(persistence.get("saves"),persistence.get("saved")),inventory,scope,revision));
        }
        return result;
    }

    private static void expandSaveScopes(Object value,Object scope) {
        if(value instanceof List){for(Object child:(List<?>)value)expandSaveScopes(child,scope);return;}
        if(!(value instanceof Map) || !object(value).containsKey("sid"))return;
        Map<String,Object> receipt=object(value);
        if(!receipt.containsKey("s") && scope!=null)receipt.put("s",scope);
        if(!receipt.containsKey("src_s") && receipt.containsKey("s"))receipt.put("src_s",receipt.get("s"));
    }

    private static Object definition(Object table,Object rawIndex) {
        int index=integer(rawIndex);
        if(!(table instanceof List) || index<0 || index>=((List<?>)table).size())throw new IllegalArgumentException("Invalid same-frame dictionary reference");
        return ((List<?>)table).get(index);
    }

    private static int integer(Object value) {
        if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number)value).longValue()>Integer.MAX_VALUE || ((Number)value).longValue()<Integer.MIN_VALUE)
            throw new IllegalArgumentException("Dictionary indexes must be integers");
        return ((Number)value).intValue();
    }

    /** Exact English item title display rule, independent of the GUI's current language. */
    static String titleCase(String text) {
        StringBuilder result=new StringBuilder();
        for(String word:text.split("(?<=\\p{Zs})")) {
            if(NO_CAPS.contains(word.trim().toLowerCase(Locale.ENGLISH).replaceAll(":|[0-9]","")))result.append(word);
            else result.append(capitalize(word));
        }
        return capitalize(result.toString());
    }

    private static String capitalize(String text) {return text.isEmpty()?text:text.substring(0,1).toUpperCase(Locale.ENGLISH)+text.substring(1);}
    private static int bytes(Object value) {return JsonCodec.encode(value).getBytes(StandardCharsets.UTF_8).length;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
}
