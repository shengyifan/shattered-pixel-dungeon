package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Same-frame protocol-7 structures. This class has no engine, profile or cross-frame state. */
final class CompactStructures {
    private static final Set<String> OPAQUE = new HashSet<>(Arrays.asList(
            "raw", "reply", "schema", "raw_request", "raw_bytes", "request_json", "response_json",
            "original_payload", "text_sources", "text_origins", "pres", "preserved_cells"));
    private static final Set<String> NO_CAPS = new HashSet<>(Arrays.asList("a", "an", "and", "of", "by", "to", "the", "x", "for"));
    private CompactStructures() { }

    static void compact(Object value) { compact(value, true, true); }

    /** Independent switches exist only for deterministic offline measurements of the production codec. */
    static void compact(Object value, boolean shareOps, boolean templates) {
        // Validate every owned snapshot before any mutation. Existing reserved keys are public
        // input conflicts, never an invitation to overwrite data or reinterpret an encoded frame.
        validateEncodingRoot(value);
        compactRoot(value, false, shareOps, templates);
    }

    private static void validateEncodingRoot(Object value) {
        if(!(value instanceof Map))return;
        Map<String,Object> root=object(value);
        if(root.containsKey("v") && root.get("data") instanceof Map) {
            validateEncodingRoot(root.get("data"));return;
        }
        for(String reserved:Arrays.asList("act_templates","inv_templates"))
            if(root.containsKey(reserved))throw new IllegalArgumentException("Reserved protocol 8 structure field: "+reserved);
        if(root.get("ui") instanceof Map && object(root.get("ui")).containsKey("node_templates"))
            throw new IllegalArgumentException("Reserved protocol 8 structure field: ui.node_templates");
        for(String snapshot:Arrays.asList("before","after"))validateEncodingRoot(root.get(snapshot));
    }

    private static void compactRoot(Object value, boolean protectedAncestor, boolean shareOps, boolean templates) {
        if (!(value instanceof Map)) return;
        Map<String,Object> current=object(value);
        boolean protectedRoot=protectedAncestor || metadata(current);
        if (current.containsKey("v") && current.get("data") instanceof Map) {
            Map<String,Object> data=new LinkedHashMap<>(object(current.get("data")));current.put("data",data);
            compactRoot(data,protectedRoot,shareOps,templates);
            return;
        }
        // Only documented observation roots own these records. Unknown extension objects are opaque.
        if (!protectedRoot) {
            if(current.get("ui") instanceof Map)current.put("ui",new LinkedHashMap<>(object(current.get("ui"))));
            if (shareOps) shareOperations(current);
            if (templates) {
                compactRecords(current,"acts","act_templates");
                compactRecords(current,"inv","inv_templates");
                if (current.get("ui") instanceof Map && !metadata(object(current.get("ui"))))
                    compactRecords(object(current.get("ui")),"nodes","node_templates");
            }
        }
        for (String snapshot:Arrays.asList("before","after")) if(current.get(snapshot) instanceof Map) {
            // Detached public snapshots may share Java object identities with immutable raw evidence.
            Map<String,Object> child=new LinkedHashMap<>(object(current.get(snapshot)));current.put(snapshot,child);
            compactRoot(child,protectedRoot,shareOps,templates);
        }
    }

    private static void shareOperations(Map<String,Object> observation) {
        if (!(observation.get("acts") instanceof List) || !(observation.get("ui") instanceof Map)) return;
        Map<String,Object> ui=object(observation.get("ui"));
        if (metadata(ui) || !(ui.get("nodes") instanceof List)) return;
        List<?> actions=(List<?>)observation.get("acts");
        List<Object> nodes=new ArrayList<>();
        for (Object raw:(List<?>)ui.get("nodes")) {
            if (!(raw instanceof Map) || containsMetadata(raw)) { nodes.add(raw); continue; }
            Map<String,Object> node=new LinkedHashMap<>(object(raw));
            nodes.add(node);
            if (!(node.get("id") instanceof String) || !(node.get("ops") instanceof List)) continue;
            List<Object> operations=new ArrayList<>();
            for (Object operation:(List<?>)node.get("ops")) {
                Object encoded=operation;
                if (operation instanceof Map && !object(operation).containsKey("ctl") && !containsMetadata(operation)) {
                    for (int index=0;index<actions.size();index++) {
                        Object rawAction=actions.get(index);
                        if (!(rawAction instanceof Map) || containsMetadata(rawAction)) continue;
                        Map<String,Object> action=object(rawAction);
                        if (!Objects.equals(node.get("id"),action.get("ctl"))) continue;
                        Map<String,Object> inherited=new LinkedHashMap<>(action);inherited.remove("ctl");
                        if (exact(operation,inherited)) { encoded=index; break; }
                    }
                }
                operations.add(encoded);
            }
            node.put("ops",operations);
        }
        ui.put("nodes",nodes);
    }

    private static void compactRecords(Map<String,Object> owner,String field,String tableField) {
        if (!(owner.get(field) instanceof List)) return;
        List<?> original=(List<?>)owner.get(field);
        Map<List<String>,List<Integer>> groups=new LinkedHashMap<>();
        for (int index=0;index<original.size();index++) {
            Object record=original.get(index);
            if (!(record instanceof Map) || containsMetadata(record)) continue;
            List<String> fields=new ArrayList<>(object(record).keySet());
            groups.computeIfAbsent(fields,ignored->new ArrayList<>()).add(index);
        }
        List<Object> records=new ArrayList<>(original),templates=new ArrayList<>();
        for (Map.Entry<List<String>,List<Integer>> group:groups.entrySet()) {
            if (group.getValue().size()<2) continue;
            Map<String,Object> first=object(original.get(group.getValue().get(0))),common=new LinkedHashMap<>();
            List<String> fields=new ArrayList<>();
            for (String key:group.getKey()) {
                boolean same=true;
                for (int index:group.getValue()) if (!exact(first.get(key),object(original.get(index)).get(key))) {same=false;break;}
                if (same) common.put(key,first.get(key)); else fields.add(key);
            }
            Map<String,Object> template=new LinkedHashMap<>();template.put("common",common);template.put("fields",fields);
            List<Object> candidateRecords=new ArrayList<>(records),candidateTemplates=new ArrayList<>(templates);
            int templateIndex=templates.size();candidateTemplates.add(template);
            for (int index:group.getValue()) {
                List<Object> row=new ArrayList<>();row.add(templateIndex);
                for (String key:fields) row.add(object(original.get(index)).get(key));
                candidateRecords.set(index,row);
            }
            Map<String,Object> candidate=new LinkedHashMap<>(owner);
            candidate.put(field,candidateRecords);candidate.put(tableField,candidateTemplates);
            // Include the complete records, table and wrapper overhead for every accepted group.
            if (bytes(candidate)<bytes(owner)) {
                records=candidateRecords;templates=candidateTemplates;
                owner.put(field,records);owner.put(tableField,templates);
            }
        }
    }

    /** JSON type-sensitive equality: never collapse false/0, integer/float, missing/null or array order. */
    private static boolean exact(Object left,Object right) {
        if (left==null || right==null) return left==right;
        if (left instanceof Map && right instanceof Map) {
            Map<?,?> a=(Map<?,?>)left,b=(Map<?,?>)right;
            if (!a.keySet().equals(b.keySet())) return false;
            for (Object key:a.keySet()) if (!exact(a.get(key),b.get(key))) return false;
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<?> a=(List<?>)left,b=(List<?>)right;if(a.size()!=b.size())return false;
            for(int i=0;i<a.size();i++)if(!exact(a.get(i),b.get(i)))return false;
            return true;
        }
        if (integral(left) && integral(right)) return ((Number)left).longValue()==((Number)right).longValue();
        return left.getClass()==right.getClass() && left.equals(right);
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

    static Object expand(Object value) {return expandRoot(value,null,null,true);}

    /** Structure-only entry point for exact offline round-trip measurements. */
    static Object expandPure(Object value) {return expandRoot(value,null,null,false);}

    private static Object expandRoot(Object value,Object scope,Object revision,boolean bindings) {
        if (!(value instanceof Map)) return copy(value);
        Map<String,Object> input=object(value),result=object(copy(input));
        if (input.containsKey("s")) scope=input.get("s");
        if (input.containsKey("rev")) revision=input.get("rev");
        if (input.containsKey("v") && input.get("data") instanceof Map) {
            result.put("data",expandRoot(input.get("data"),scope,revision,bindings));
            return result;
        }
        expandRecords(result,"acts","act_templates");
        expandRecords(result,"inv","inv_templates");
        if (bindings) expandBindings(result,scope,revision);
        Map<String,Map<String,Object>> inventory=new LinkedHashMap<>();
        Set<String> ambiguous=new HashSet<>();
        if (result.get("inv") instanceof List) for(Object raw:(List<?>)result.get("inv")) {
            if (!(raw instanceof Map) || !(object(raw).get("loc") instanceof String)) continue;
            String locator=(String)object(raw).get("loc");
            if(inventory.put(locator,object(raw))!=null)ambiguous.add(locator);
        }
        for(String locator:ambiguous)inventory.remove(locator);
        if (result.get("ui") instanceof Map) {
            Map<String,Object> ui=object(result.get("ui"));
            if(ui.containsKey("node_shapes") || ui.containsKey("op_defs"))throw new IllegalArgumentException("Protocol 6 UI structures are unsupported");
            expandRecords(ui,"nodes","node_templates");
            if(ui.get("nodes") instanceof List)for(Object raw:(List<?>)ui.get("nodes")) {
                if(!(raw instanceof Map))throw new IllegalArgumentException("Invalid UI node");
                Map<String,Object> node=object(raw);
                if(node.get("ops")!=null) {
                    if(!(node.get("ops") instanceof List))throw new IllegalArgumentException("Invalid operation list");
                    List<Object> operations=new ArrayList<>();
                    for(Object operation:(List<?>)node.get("ops")) {
                        if(operation instanceof Map) {
                            if(!(object(operation).get("op") instanceof String) || ((String)object(operation).get("op")).isEmpty())
                                throw new IllegalArgumentException("Invalid inline operation");
                            operations.add(operation);
                        } else {
                            Object descriptor=definition(result.get("acts"),operation);
                            if(!(descriptor instanceof Map))throw new IllegalArgumentException("Invalid action reference");
                            Map<String,Object> action=object(descriptor);
                            if(!(action.get("op") instanceof String) || ((String)action.get("op")).isEmpty()
                                    || !(node.get("id") instanceof String) || !(action.get("ctl") instanceof String)
                                    || !Objects.equals(node.get("id"),action.get("ctl")) || containsMetadata(action))
                                throw new IllegalArgumentException("Action reference has no matching control binding");
                            Map<String,Object> inherited=object(copy(action));inherited.remove("ctl");operations.add(inherited);
                        }
                    }
                    node.put("ops",operations);
                }
                if(node.get("label") instanceof Number) {
                    Object name=inventory.containsKey(node.get("loc"))?inventory.get(node.get("loc")).get("name"):null;
                    int code=integer(node.get("label"));
                    if(!(name instanceof String) || code<0 || code>1)throw new IllegalArgumentException("Unbound inherited item label");
                    node.put("label",code==0?name:titleCase((String)name));
                }
                if(node.get("label")!=null && !(node.get("label") instanceof String))throw new IllegalArgumentException("Invalid item label");
            }
        }
        for(String snapshot:Arrays.asList("before","after")) if(input.containsKey(snapshot))
            result.put(snapshot,expandRoot(input.get(snapshot),null,null,bindings));
        return result;
    }

    private static void expandRecords(Map<String,Object> owner,String field,String tableField) {
        if(owner.containsKey(tableField) && !(owner.get(field) instanceof List))throw new IllegalArgumentException("Record table has no record list");
        if(!(owner.get(field) instanceof List))return;
        Object table=owner.get(tableField);
        if(owner.containsKey(tableField)) {
            if(!(table instanceof List))throw new IllegalArgumentException("Invalid record template table");
            for(Object entry:(List<?>)table)validateTemplate(entry);
        }
        List<Object> records=new ArrayList<>();
        for(Object raw:(List<?>)owner.get(field)) {
            if(raw instanceof Map) {records.add(raw);continue;}
            if(!(raw instanceof List) || ((List<?>)raw).isEmpty())throw new IllegalArgumentException("Invalid record row");
            List<?> row=(List<?>)raw;Map<String,Object> template=object(definition(table,row.get(0)));
            List<?> fields=(List<?>)template.get("fields");
            if(row.size()!=fields.size()+1)throw new IllegalArgumentException("Record row width mismatch");
            Map<String,Object> record=object(copy(template.get("common")));
            for(int index=0;index<fields.size();index++)record.put((String)fields.get(index),copy(row.get(index+1)));
            records.add(record);
        }
        owner.put(field,records);owner.remove(tableField);
    }

    private static void validateTemplate(Object value) {
        if(!(value instanceof Map))throw new IllegalArgumentException("Invalid record template");
        Map<String,Object> template=object(value);
        if(template.size()!=2 || !(template.get("common") instanceof Map) || !(template.get("fields") instanceof List))
            throw new IllegalArgumentException("Invalid record template fields");
        Set<Object> fields=new HashSet<>(((Map<?,?>)template.get("common")).keySet());
        for(Object field:fields)if(!(field instanceof String))throw new IllegalArgumentException("Invalid common field name");
        for(Object field:(List<?>)template.get("fields"))
            if(!(field instanceof String) || !fields.add(field))throw new IllegalArgumentException("Invalid or duplicate variable field");
    }

    private static void expandBindings(Map<String,Object> result,Object scope,Object revision) {
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
            if(persistence.containsKey("saves")) {
                if(!(persistence.get("saves") instanceof List))throw new IllegalArgumentException("Save receipts must be an array");
                for(Object receipt:(List<?>)persistence.get("saves"))validateReceipt(receipt);
            }
            for(String field:Arrays.asList("saves","saved"))expandSaveScopes(persistence.get(field),scope);
            Object saved=persistence.get("saved");
            if(integral(saved))persistence.put("saved",copy(definition(persistence.get("saves"),saved)));
            else if(saved!=null)validateReceipt(saved);
        }
    }

    private static void validateReceipt(Object value) {
        if(!(value instanceof Map) || !(object(value).get("sid") instanceof String)
                || ((String)object(value).get("sid")).isEmpty())
            throw new IllegalArgumentException("Save receipt must be an object with a non-empty sid");
    }

    private static Object copy(Object value) {
        if(value instanceof Map) {
            Map<String,Object> result=new LinkedHashMap<>();
            for(Map.Entry<String,Object> entry:object(value).entrySet())
                result.put(entry.getKey(),OPAQUE.contains(entry.getKey())?entry.getValue():copy(entry.getValue()));
            return result;
        }
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(copy(child));return result;}
        return value;
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
        if(!integral(value)
                || ((Number)value).longValue()>Integer.MAX_VALUE || ((Number)value).longValue()<Integer.MIN_VALUE)
            throw new IllegalArgumentException("Dictionary indexes must be integers");
        return ((Number)value).intValue();
    }

    private static boolean integral(Object value) {return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;}

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
