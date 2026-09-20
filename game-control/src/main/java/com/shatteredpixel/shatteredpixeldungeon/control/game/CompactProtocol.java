package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.WireNames;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.PublicTextSources;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Pure protocol 7 projection of already rendered public values. Never observes the engine. */
public final class CompactProtocol {
    private static final Set<String> OPAQUE = new HashSet<>(Arrays.asList(
            "raw", "reply", "schema", "raw_request", "raw_bytes", "request_json", "response_json", "original_payload"));
    private static final java.util.regex.Pattern NESTED_ANNOTATION=java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)(?:\\[(\\d{1,9})\\])?\\.(.+)");
    public static final String TILE_ALPHABET="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_";
    private CompactProtocol() { }

    public static Map<String,Object> success(String id,String scope,String status,Object canonicalResult,boolean live,boolean sources) {
        return success(id,scope,status,canonicalResult,live,sources,false);
    }

    public static Map<String,Object> success(String id,String scope,String status,Object canonicalResult,boolean live,boolean sources,boolean full) {
        Map<String,Object> response=map("v",7,"id",id);
        Map<?,?> original=canonicalResult instanceof Map?(Map<?,?>)canonicalResult:Collections.emptyMap();
        Object effectiveScope=live && original.get("scope_id")!=null?original.get("scope_id"):scope;
        if(effectiveScope!=null)response.put("s",effectiveScope);
        if(live && original.get("state_version")!=null)response.put("rev",original.get("state_version"));
        response.put("st",status);
        Object projected=projectExpanded(canonicalResult,sources,full);
        if(projected instanceof Map && live) {
            Map<String,Object> data=new LinkedHashMap<>(cast(projected));
            data.remove("s"); data.remove("rev"); projected=data;
        }
        response.put("data",projected);
        if(live && !sources && !full)CompactStructures.compactBindings(response);
        CompactStructures.compact(projected);
        List<Object> diagnostics=new ArrayList<>();
        collectDiagnostics(projected,"$.data",diagnostics);
        if(!diagnostics.isEmpty())response.put("pres",map("st","partial","diag",diagnostics));
        return response;
    }

    public static Map<String,Object> failure(String id,String scope,String code) {
        Map<String,Object> response=map("v",7,"id",id);
        if(scope!=null)response.put("s",scope);
        response.put("err",code);
        return response;
    }

    public static Object project(Object canonical,boolean sources) { return project(canonical,sources,false); }

    public static Object project(Object canonical,boolean sources,boolean full) {
        Object projected=projectExpanded(canonical,sources,full);
        CompactStructures.compact(projected);
        return projected;
    }

    /** Field projection is independent of the reversible representation used by every view. */
    static Object projectExpanded(Object canonical,boolean sources,boolean full) {
        Object projected=projectValue(canonical,sources,sources||full,"");
        normalizeUi(projected,sources||full,Collections.emptyMap());
        return projected;
    }

    /** Decode only same-frame structure/inheritance, without canonical aliases or stateful lookups. */
    public static Object expandStructures(Object projected) { return CompactStructures.expand(projected); }

    private static Object projectValue(Object canonical,boolean sources,boolean full,String context) {
        if(canonical instanceof List) {
            List<Object> result=new ArrayList<>();
            for(Object value:(List<?>)canonical)result.add(projectValue(value,sources,full,context));
            return result;
        }
        if(!(canonical instanceof Map))return canonical;
        Map<String,Object> input=cast(canonical);
        full=full || unpairedDiagnostics(input);
        if(input.containsKey("width") && input.containsKey("height") && input.get("cells") instanceof List)
            return terrain(input,sources,full);
        Map<String,Object> flattened=new LinkedHashMap<>();
        if(input.get("observation") instanceof Map) {
            flattened.putAll(cast(input.get("observation")));
            for(Map.Entry<String,Object> entry:flattened.entrySet())
                if(input.containsKey(entry.getKey()) && !Objects.equals(input.get(entry.getKey()),entry.getValue()))
                    throw new IllegalArgumentException("Conflicting public observation field: " + entry.getKey());
        }
        flattened.putAll(input);
        if(input.get("observation") instanceof Map)flattened.remove("observation");
        flattened=localizePresentation(flattened);
        flattened=localizeNestedAnnotations(flattened);
        flattened=simplifyFields(flattened,full,context);
        for(Map.Entry<String,Object> entry:flattened.entrySet()) {
            String key=entry.getKey(),wire="action".equals(key)?"op":WireNames.field(key);
            if(!key.equals(wire) && flattened.containsKey(wire) && !Objects.equals(entry.getValue(),flattened.get(wire)))
                throw new IllegalArgumentException("Conflicting public wire field: " + wire);
        }
        Map<String,Object> result=new LinkedHashMap<>();
        for(Map.Entry<String,Object> entry:flattened.entrySet()) {
            String key=entry.getKey();Object value=entry.getValue();
            if("translation_status".equals(key) && "partial".equals(value))continue;
            if("text_diagnostics".equals(key) && value instanceof Map && !((Map<?,?>)value).isEmpty())continue;
            if("text_sources".equals(key)) {
                if(sources) result.put(key,sourceFields(value));
                else {
                    Map<String,Object> origins=origins(value);
                    if(!origins.isEmpty())mergeOrigins(result,origins);
                    Map<String,Object> unusual=unusualSources(value);
                    if(!unusual.isEmpty())result.put("text_sources",unusual);
                }
                continue;
            }
            if("text_origins".equals(key)) { mergeOrigins(result,sourceFields(value));continue; }
            if("presentation".equals(key) && value instanceof Map) {
                Map<String,Object> presentation=projectPresentation((Map<?,?>)value,input);
                // A bare successful status is the documented default. Additional fields are
                // public evidence, even when the status is complete or diagnostics relocate.
                boolean completeDefault="complete".equals(((Map<?,?>)value).get("status"))
                        && ((Map<?,?>)value).keySet().stream().allMatch(field -> Arrays.asList("status","diagnostics").contains(field))
                        && ((Map<?,?>)value).get("diagnostics") instanceof List
                        && ((List<?>)((Map<?,?>)value).get("diagnostics")).isEmpty();
                if(!completeDefault)result.put("pres",presentation);
                continue;
            }
            if("actions".equals(key) && value instanceof List) {
                List<Object> actions=new ArrayList<>();
                boolean protectedActions=protectedField(flattened,key) || sources && annotationReferences(flattened,key);
                for(Object item:(List<?>)value)actions.add(item instanceof Map?action(cast(item),sources,full,protectedActions):projectValue(item,sources,full || protectedActions,"actions"));
                result.put("acts",actions);continue;
            }
            if("details_via".equals(key) && value instanceof String) value=WireNames.operation((String)value);
            if("action".equals(key) && value instanceof String) {
                result.put("op",WireNames.operation((String)value));continue;
            }
            if("direction".equals(key))value=WireNames.direction(value);
            boolean protectedChildren=(value instanceof Map || value instanceof List) && protectedField(flattened,key);
            result.put(WireNames.field(key),OPAQUE.contains(key)?value:projectValue(value,sources,full || protectedChildren || "before".equals(key) || "after".equals(key),key));
        }
        if(flattened.get("text_diagnostics") instanceof Map && !((Map<?,?>)flattened.get("text_diagnostics")).isEmpty()) {
            List<Object> diagnostics=new ArrayList<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)flattened.get("text_diagnostics")).entrySet())
                diagnostics.add(map("field",compactPath(String.valueOf(entry.getKey())),"code",entry.getValue()));
            mergePresentation(result,diagnostics);
        } else if("partial".equals(flattened.get("translation_status"))) {
            mergePresentation(result,Collections.emptyList());
        }
        attachActions(result);
        if(!full && !protectedField(flattened,"visible_entities"))compactEntities(result);
        return UiProjectionHints.preserve(input,result);
    }

    private static Map<String,Object> simplifyFields(Map<String,Object> input,boolean full,String context) {
        Map<String,Object> result=new LinkedHashMap<>(input);
        boolean item=input.containsKey("locator") && (input.containsKey("quantity") || input.containsKey("level_known") || input.containsKey("curse_known"));
        if(item) {
            for(String[] pair:new String[][]{{"level_known","level"},{"curse_known","cursed"}}) {
                if(result.containsKey(pair[0]) && !protectedField(result,pair[0]) && !protectedField(result,pair[1])
                        && !(full && (annotationReferences(result,pair[0]) || annotationReferences(result,pair[1])))) {
                    Object known=result.get(pair[0]);
                    if(known==null)removeField(result,pair[1]);
                    else if(Boolean.FALSE.equals(known))result.put(pair[1],null);
                    removeField(result,pair[0]);
                }
            }
            if(!full) {
                omitDefault(result,"quantity",1);omitDefault(result,"equipped",false);
                omitDefault(result,"available",true);omitDefault(result,"type_known",true);
                Object via=result.get("details_via");
                if(via instanceof String && "click".equals(WireNames.operation((String)via)) && !protectedField(result,"details_via"))removeField(result,"details_via");
            }
        }
        if(!full && "ui".equals(context)) {
            omitDefault(result,"modal",false);omitDefault(result,"inspected_item",null);
        }
        if(!full && "controls".equals(context)) {
            omitDefault(result,"enabled",true);omitDefault(result,"dimmed",false);
        }
        return result;
    }

    /** Keep presentation-only evidence local to the object whose structure it describes. */
    private static Map<String,Object> localizePresentation(Map<String,Object> original) {
        Object raw=original.get("presentation");
        if(!(raw instanceof Map) || !(((Map<?,?>)raw).get("diagnostics") instanceof List))return original;
        Map<String,Object> result=original;List<Object> remaining=new ArrayList<>();
        for(Object item:(List<?>)((Map<?,?>)raw).get("diagnostics")) {
            if(!(item instanceof Map) || !(((Map<?,?>)item).get("field") instanceof String)){remaining.add(item);continue;}
            Map<String,Object> diagnostic=cast(item);String path=relativePath((String)diagnostic.get("field"));
            if(path.startsWith("observation."))path=path.substring("observation.".length());
            java.util.regex.Matcher match=NESTED_ANNOTATION.matcher(path);
            if(!match.matches() || OPAQUE.contains(match.group(1)) || "preserved_cells".equals(match.group(1))){remaining.add(item);continue;}
            Object child=result.get(match.group(1));
            if(match.group(2)!=null) {
                int index=Integer.parseInt(match.group(2));
                if(!(child instanceof List) || index>=((List<?>)child).size()){remaining.add(item);continue;}
                child=((List<?>)child).get(index);
            }
            if(!(child instanceof Map)){remaining.add(item);continue;}
            if(result==original)result=cast(publicCopy(original));
            child=result.get(match.group(1));
            if(match.group(2)!=null)child=((List<?>)child).get(Integer.parseInt(match.group(2)));
            Map<String,Object> presentation=cast(child).get("presentation") instanceof Map
                    ?new LinkedHashMap<>(cast(cast(child).get("presentation"))):new LinkedHashMap<>();
            List<Object> diagnostics=presentation.get("diagnostics") instanceof List
                    ?new ArrayList<>((List<?>)presentation.get("diagnostics")):new ArrayList<>();
            Map<String,Object> relocated=new LinkedHashMap<>(cast(publicCopy(diagnostic)));relocated.put("field","$."+match.group(3));
            if(!diagnostics.contains(relocated))diagnostics.add(relocated);
            presentation.put("status","partial");presentation.put("diagnostics",diagnostics);cast(child).put("presentation",presentation);
        }
        if(result!=original) {
            Map<String,Object> presentation=new LinkedHashMap<>(cast(result.get("presentation")));presentation.put("diagnostics",remaining);
            if(remaining.isEmpty() && presentation.size()==2)result.remove("presentation");else result.put("presentation",presentation);
        }
        return result;
    }

    private static void mergePresentation(Map<String,Object> result,List<Object> added) {
        Map<String,Object> presentation=result.get("pres") instanceof Map?new LinkedHashMap<>(cast(result.get("pres"))):new LinkedHashMap<>();
        List<Object> diagnostics=presentation.get("diag") instanceof List?new ArrayList<>((List<?>)presentation.get("diag")):new ArrayList<>();
        for(Object diagnostic:added)if(!diagnostics.contains(diagnostic))diagnostics.add(diagnostic);
        presentation.put("st","partial");presentation.put("diag",diagnostics);result.put("pres",presentation);
    }

    /** Rehome parent-owned paths before a child changes its array indexes or dictionary representation. */
    private static Map<String,Object> localizeNestedAnnotations(Map<String,Object> original) {
        Map<String,Object> result=original;
        for(String metadata:Arrays.asList("text_sources","text_origins","text_diagnostics")) {
            Object raw=result.get(metadata);if(!(raw instanceof Map) || ((Map<?,?>)raw).isEmpty())continue;
            for(Map.Entry<String,Object> entry:new ArrayList<>(cast(raw).entrySet())) {
                String path=relativePath(entry.getKey());
                if(path.startsWith("observation."))path=path.substring("observation.".length());
                java.util.regex.Matcher match=NESTED_ANNOTATION.matcher(path);
                if(!match.matches())continue;
                Object child=result.get(match.group(1));
                if(match.group(2)!=null) {
                    int index=Integer.parseInt(match.group(2));
                    if(!(child instanceof List) || index>=((List<?>)child).size())continue;
                    child=((List<?>)child).get(index);
                }
                if(!(child instanceof Map) || OPAQUE.contains(match.group(1)) || "preserved_cells".equals(match.group(1)))continue;
                String field=match.group(3);
                Object existing=((Map<?,?>)child).get(metadata);
                if(existing instanceof Map && ((Map<?,?>)existing).containsKey(field)
                        && !Objects.equals(((Map<?,?>)existing).get(field),entry.getValue()))continue;
                if(result==original)result=cast(publicCopy(original));
                child=result.get(match.group(1));
                if(match.group(2)!=null)child=((List<?>)child).get(Integer.parseInt(match.group(2)));
                @SuppressWarnings("unchecked") Map<String,Object> local=(Map<String,Object>)cast(child).computeIfAbsent(metadata,ignored->new LinkedHashMap<>());
                local.put(field,publicCopy(entry.getValue()));
                cast(result.get(metadata)).remove(entry.getKey());
            }
            if(result.get(metadata) instanceof Map && ((Map<?,?>)result.get(metadata)).isEmpty())result.remove(metadata);
        }
        return result;
    }

    private static void omitDefault(Map<String,Object> result,String field,Object expected) {
        Object actual=result.get(field);
        boolean same=expected instanceof Number && actual instanceof Number
                ? ((Number)expected).doubleValue()==((Number)actual).doubleValue() : Objects.equals(actual,expected);
        if(same && !protectedField(result,field))removeField(result,field);
    }

    /** Unknown provenance and partial displays must remain addressable even in play view. */
    private static boolean protectedField(Map<String,Object> input,String field) {
        if(Boolean.TRUE.equals(input.get("clipped")) || "partial".equals(input.get("translation_status")))return true;
        for(String presentation:Arrays.asList("presentation","pres")) {
            Object raw=input.get(presentation);
            if(raw instanceof Map && (!"complete".equals(((Map<?,?>)raw).get("status"))
                    && !"complete".equals(((Map<?,?>)raw).get("st"))))return true;
        }
        for(String metadata:Arrays.asList("text_diagnostics","text_origins")) {
            Object raw=input.get(metadata);
            if(raw instanceof Map)for(Object key:((Map<?,?>)raw).keySet())if(key instanceof String && references(relativePath((String)key),field))return true;
        }
        Object raw=input.get("text_sources");
        if(raw instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)raw).entrySet())
            if(entry.getKey() instanceof String && references(relativePath((String)entry.getKey()),field) && PublicTextSources.protectsField(entry.getValue()))return true;
        return false;
    }

    private static boolean unpairedDiagnostics(Map<String,Object> input) {
        Object presentation=input.get("presentation");
        if(!(presentation instanceof Map) || !(((Map<?,?>)presentation).get("diagnostics") instanceof List))return false;
        for(Object diagnostic:(List<?>)((Map<?,?>)presentation).get("diagnostics"))
            if(diagnostic instanceof Map && (!simpleDiagnostic(cast(diagnostic)) || !canonicalDiagnosticExists(input,"$",cast(diagnostic))))return true;
        return false;
    }

    private static boolean simpleDiagnostic(Map<String,Object> diagnostic) {
        return diagnostic.size()==2 && diagnostic.containsKey("field") && diagnostic.containsKey("code");
    }

    private static Map<String,Object> unusualSources(Object value) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet())
            if(PublicTextSources.requiresSource(entry.getValue()))result.put(compactPath(String.valueOf(entry.getKey())),sourceFieldValue(entry.getValue()));
        return result;
    }

    private static void removeField(Map<String,Object> object,String field) {
        object.remove(field);
        for(String metadata:Arrays.asList("text_sources","text_origins","text_diagnostics")) {
            Object raw=object.get(metadata);
            if(!(raw instanceof Map))continue;
            Map<String,Object> remaining=new LinkedHashMap<>(cast(raw));
            remaining.keySet().removeIf(key -> references(key,field));
            if(remaining.isEmpty())object.remove(metadata);else object.put(metadata,remaining);
        }
    }

    /** Run after action attachment; capture hints are never serialized or part of canonical freshness. */
    private static void normalizeUi(Object value,boolean full,Map<String,Map<String,Object>> inventory) {
        if(value instanceof List){for(Object child:(List<?>)value)normalizeUi(child,full,inventory);return;}
        if(!(value instanceof Map))return;
        Map<String,Object> current=cast(value);
        if(current.get("inv") instanceof List) {
            inventory=new LinkedHashMap<>();Set<String> ambiguous=new HashSet<>();
            for(Object item:(List<?>)current.get("inv"))if(item instanceof Map && ((Map<?,?>)item).get("loc") instanceof String) {
                String locator=(String)((Map<?,?>)item).get("loc");
                if(inventory.put(locator,cast(item))!=null)ambiguous.add(locator);
            }
            for(String locator:ambiguous)inventory.remove(locator);
        }
        // Keep unresolved aggregate array paths valid; local metadata is safely moved with each node.
        if(indexedUiAnnotations(current))full=true;
        if(current.get("nodes") instanceof List) {
            List<?> nodes=(List<?>)current.get("nodes");Set<Object> parents=new HashSet<>();
            Map<Object,Map<String,Object>> byId=new LinkedHashMap<>();
            for(Object raw:nodes)if(raw instanceof Map) {
                Map<String,Object> node=cast(raw);byId.put(node.get("id"),node);
                if(node.get("parent")!=null)parents.add(node.get("parent"));
            }
            UiProjectionHints hints=UiProjectionHints.get(current);
            for(Map.Entry<String,UiProjectionHints.Node> entry:hints.nodes.entrySet()) {
                Map<String,Object> node=byId.get(entry.getKey());if(node==null)continue;
                UiProjectionHints.Node hint=entry.getValue();
                if(hint.locator!=null && (!node.containsKey("loc") || Objects.equals(node.get("loc"),hint.locator))) {
                    node.put("loc",hint.locator);
                    Map<String,Object> item=inventory.get(hint.locator);
                    if(!full && item!=null && node.get("label") instanceof String && item.get("name") instanceof String
                            && !protectedField(node,"label") && !protectedField(item,"name")
                            && fieldMetadataContained(node,"label",item,"name")) {
                        String name=(String)item.get("name"),label=(String)node.get("label");
                        if(label.equals(name)) {removeField(node,"label");node.put("label",0);}
                        else if(label.equals(CompactStructures.titleCase(name))) {removeField(node,"label");node.put("label",1);}
                    }
                }
                if(!node.containsKey("display"))for(Map.Entry<String,String> display:hint.displayChildren.entrySet()) {
                    Map<String,Object> child=byId.get(display.getValue());
                    if(child==null || parents.contains(child.get("id")) || !Objects.equals(node.get("id"),child.get("parent"))
                            || !passiveText(child,true) || !sameNodeState(child,node) || !(child.get("text") instanceof String))continue;
                    @SuppressWarnings("unchecked") Map<String,Object> fields=(Map<String,Object>)node.computeIfAbsent("display",ignored->new LinkedHashMap<>());
                    copyTextField(child,fields,display.getKey());
                }
            }
            // Hints add semantic bindings, never erase captured nodes, identities, text or parentage.
            // Empty slots convey layout/capacity, and repeated child text remains independently addressable.
        }
        for(Map.Entry<String,Object> entry:current.entrySet())
            if(!OPAQUE.contains(entry.getKey()) && !Arrays.asList("text_sources","text_origins","pres").contains(entry.getKey()))
                normalizeUi(entry.getValue(),full || "before".equals(entry.getKey()) || "after".equals(entry.getKey()),inventory);
    }

    private static boolean indexedUiAnnotations(Map<String,Object> value) {
        if(annotationReferences(value,"ui") || annotationReferences(value,"nodes"))return true;
        for(String metadata:Arrays.asList("text_sources","text_origins"))
            if(value.get(metadata) instanceof Map)for(Object key:((Map<?,?>)value.get(metadata)).keySet())
                if(String.valueOf(key).contains("nodes[") || "nodes".equals(key))return true;
        if(value.get("pres") instanceof Map && ((Map<?,?>)value.get("pres")).get("diag") instanceof List)
            for(Object raw:(List<?>)((Map<?,?>)value.get("pres")).get("diag"))
                if(raw instanceof Map && String.valueOf(((Map<?,?>)raw).get("field")).contains("nodes["))return true;
        return false;
    }

    private static boolean sameNodeState(Map<String,Object> first,Map<String,Object> second) {
        return Objects.equals(first.getOrDefault("enabled",true),second.getOrDefault("enabled",true))
                && Objects.equals(first.getOrDefault("dimmed",false),second.getOrDefault("dimmed",false));
    }

    private static boolean passiveText(Map<String,Object> node,boolean movableMetadata) {
        if(!"text".equals(node.get("role")) || Boolean.TRUE.equals(node.get("dimmed")))return false;
        for(String key:node.keySet()) {
            if(Arrays.asList("id","role","parent","text","enabled","dimmed").contains(key))continue;
            if(!movableMetadata || !Arrays.asList("text_sources","text_origins","pres").contains(key))return false;
            Object metadata=node.get(key);
            if("pres".equals(key)) {
                if(!(metadata instanceof Map) || !(((Map<?,?>)metadata).get("diag") instanceof List))return false;
                List<?> diagnostics=(List<?>)((Map<?,?>)metadata).get("diag");if(diagnostics.isEmpty())return false;
                for(Object raw:diagnostics)if(!(raw instanceof Map) || !"text".equals(((Map<?,?>)raw).get("field")))return false;
            } else {
                if(!(metadata instanceof Map))return false;
                for(Object field:((Map<?,?>)metadata).keySet())if(!"text".equals(field))return false;
            }
        }
        return true;
    }

    /** Add a semantic display field while retaining its original node and metadata. */
    private static void copyTextField(Map<String,Object> source,Map<String,Object> target,String field) {
        target.put(field,source.get("text"));
        for(String metadata:Arrays.asList("text_sources","text_origins"))if(source.get(metadata) instanceof Map) {
            @SuppressWarnings("unchecked") Map<String,Object> fields=(Map<String,Object>)target.computeIfAbsent(metadata,ignored->new LinkedHashMap<>());
            fields.put(field,((Map<?,?>)source.get(metadata)).get("text"));
        }
        if(source.get("pres") instanceof Map) {
            @SuppressWarnings("unchecked") Map<String,Object> pres=(Map<String,Object>)target.computeIfAbsent("pres",ignored->map("st","partial","diag",new ArrayList<>()));
            @SuppressWarnings("unchecked") List<Object> diagnostics=(List<Object>)pres.get("diag");
            for(Object raw:(List<?>)((Map<?,?>)source.get("pres")).get("diag")) {
                Map<String,Object> diagnostic=new LinkedHashMap<>(cast(raw));diagnostic.put("field",field);diagnostics.add(diagnostic);
            }
        }
    }

    /** Static rules are discoverable once, instead of duplicated in every state. */
    public static Map<String,Object> info() {
        Map<String,Object> commands=new LinkedHashMap<>();
        for(String op:WireNames.operations())commands.put(op,new ArrayList<>(WireNames.parameters(op)));
        return map("commands",commands,"dirs",WireNames.DIRECTIONS,"aliases",WireNames.fields(),"aliases_direction","wire field to canonical concept; enum strings and source AST keys are unchanged",
                "map",map("rows",Arrays.asList("y","x_start","tiles","visibility"),"tiles","characters index this message's types; all rows use integer arrays when types exceeds 64",
                        "alphabet",TILE_ALPHABET,"vis",map("v","visible","s","visited","m","mapped"),"unknown","omitted; rows split at unknown gaps",
                        "visibility","one v/s/m repeats to tiles length in play; otherwise exactly one visibility per tile; empty rows are invalid; full always uses per-cell visibility",
                        "coordinates","cell=y*w+x_start+offset","env","complete cell-indexed visible effects; omitted means empty; array is inline, integer indexes this map's effect_defs",
                        "effect_defs","each definition is a complete ordered effect list; indexes are zero-based and valid only in this observation"),
                "entities",map("inline","ordinary entity object","reference",map("cell","cell binding","def","zero-based index into this observation's entity_defs"),
                        "entity_defs","complete trap/container/character descriptor except cell; duplicates must be structurally identical including metadata",
                        "dictionary_policy","play only, repeated values only, emitted only when minified UTF-8 bytes decrease; full/src/before/after remain inline; missing or out-of-range references are invalid"),
                "defaults",map("view","play","item",map("qty",1,"equipped",false,"available",true,"type_known",true,"via","click"),
                        "ui_node",map("enabled",true,"dimmed",false),"ui",map("modal",false,"item_info",null),"item_knowledge","level/cursed: absent=not applicable; null=unknown; value=known",
                        "click","ops is the only executable capability; omitted gestures means click"),
                "text_sources",map("ordinary_kinds",new ArrayList<>(PublicTextSources.ORDINARY_KINDS),
                        "ordinary_origins",new ArrayList<>(PublicTextSources.ORDINARY_ORIGINS),
                        "retention","recursive user/external origins remain; unknown/unavailable/partial/clipped public evidence remains protected; src includes full rendered source trees without exposing undisplayed arguments"),
                "record_templates",map("tables",map("acts","act_templates","inv","inv_templates","ui.nodes","ui.node_templates"),
                        "definition","each template has common fixed fields and fields listing variable field names; records are objects or [template index, variable values...]",
                        "validation","integer indexes, exact row width, unique string fields disjoint from common; missing, null, false and zero remain distinct",
                        "policy","all views including full/src and newly projected before/after; same ordered key set, at least two records, exact values only, emitted only when complete minified UTF-8 fragment becomes smaller; protected metadata remains inline",
                        "scope","each observation or frozen snapshot owns its tables; no inherited or cross-frame tables"),
                "ui_projection",map("nodes","current controls; ops contains inline operation objects or integer indexes into this observation's acts",
                        "node_templates","optional local common/fields templates; preserve node count, order, identities and parents; protected nodes remain objects",
                        "ops","integer element copies acts[index] without ctl, which must exactly match this node id; explicit ctl or protected metadata remains inline; missing or invalid references are errors",
                        "label","integer 0 inherits name from the unique current inv item at loc; 1 applies the English item title rule; strings are literal and explicit null remains null",
                        "identity","all captured node identities and parent bindings are retained and remain current",
                        "actions","acts retains every original operation in order and may use act_templates; decode acts before resolving node ops; no capability is inferred from labels",
                        "display","optional status/extra/level are exact already displayed ItemSlot text; loc binds the same captured item by identity",
                        "deduplication","only reversible same-frame operation sharing, record templates, dictionaries, labels and documented defaults; all captured nodes, text, descriptions, talents and action constraints remain",
                        "full","explicit default fields and literal labels; src additionally retains source ASTs; all use the same structural encoding; raw/reply stay opaque"),
                "same_frame",map("activity","omitted activity.rev inherits envelope rev; cancel rev/rid inherit current activity rev/rid",
                        "persistence","omitted receipt s inherits envelope s; omitted src_s inherits that receipt s; integer saved indexes persistence.saves",
                        "boundaries","only current live response bindings; full/src/before/after retain explicit bindings; each snapshot independently decodes its structure and never borrows an outer scope or revision"),
                "rules",map("action","s and rev required; use a fresh id", "cell",map("mode",Arrays.asList("act","examine","context"),"default","act"),
                        "click",map("g",Arrays.asList("click","right","middle","long"),"default","click"),
                        "choose",map("opt","zero-based option index","alt","optional boolean"),
                        "bind_slot",map("slot",Arrays.asList(1,2,3)),
                        "pan","x/y in map_view units", "req",map("get",Arrays.asList("raw","reply","before","after","meta")),
                        "state",map("view",Arrays.asList("play","full"),"default","play","src","include complete frozen text source trees and force full view"),
                        "actions",map("view",Arrays.asList("play","full"),"default","play"),
                        "pagination",map("after","exclusive sequence cursor; default 0","until","inclusive fixed upper sequence; omit on the first page",
                                "limit","1 to 100; default 50","continue","send next as after and keep the returned until",
                                "end","true when the fixed range is exhausted")),
                "coverage",map("status","observation_with_inspection",
                        "details_via",map("equipment_stats_and_buff_durations","click","journal_and_catalogue","click","custom_terrain_descriptions",map("op","cell","mode","examine")),
                        "inspection_policy","Use current nodes and their ops; details are read from displayed windows"));
    }

    private static Map<String,Object> terrain(Map<String,Object> input,boolean sources,boolean full) {
        input=normalizeTerrainMetadata(input);
        List<Object> types=new ArrayList<>(),rows=new ArrayList<>();
        Map<Map<String,Object>,Integer> indexes=new LinkedHashMap<>();
        Map<String,Object> environment=new LinkedHashMap<>();
        List<Map<String,Object>> cells=new ArrayList<>();
        for(Object item:(List<?>)input.get("cells"))cells.add(cast(item));
        cells.sort(Comparator.comparingInt(c -> ((Number)c.get("cell")).intValue()));
        List<Integer> terrainIndexes=new ArrayList<>();
        for(Map<String,Object> cell:cells) {
            // Every public descriptor field is retained; coordinates and effects live outside the dictionary.
            Map<String,Object> descriptor=new LinkedHashMap<>(cell);
            for(String field:Arrays.asList("cell","x","y","visibility","environment"))descriptor.remove(field);
            descriptor=cast(projectValue(descriptor,sources,full,"terrain"));
            Integer index=indexes.get(descriptor);
            if(index==null){index=types.size();indexes.put(descriptor,index);types.add(descriptor);}
            terrainIndexes.add(index);
            Object effects=cell.get("environment");
            if(effects instanceof List && !((List<?>)effects).isEmpty())
                environment.put(String.valueOf(cell.get("cell")),projectValue(effects,sources,full,"environment"));
        }
        int width=((Number)input.get("width")).intValue();
        boolean strings=types.size()<=TILE_ALPHABET.length();
        int previous=-2,y=-1,x=0;
        List<Integer> run=new ArrayList<>();StringBuilder visibility=new StringBuilder();
        for(int i=0;i<cells.size();i++) {
            Map<String,Object> cell=cells.get(i);int position=((Number)cell.get("cell")).intValue();
            if(!run.isEmpty() && (position!=previous+1 || position/width!=y)) {
                rows.add(row(y,x,run,visibility,strings,full));run=new ArrayList<>();visibility=new StringBuilder();
            }
            if(run.isEmpty()){y=position/width;x=position%width;}
            run.add(terrainIndexes.get(i));Object vis=cell.get("visibility");
            visibility.append("visible".equals(vis)?"v":"visited".equals(vis)?"s":"mapped".equals(vis)?"m":String.valueOf(vis));
            previous=position;
        }
        if(!run.isEmpty())rows.add(row(y,x,run,visibility,strings,full));
        Map<String,Object> result=map("w",input.get("width"),"h",input.get("height"),"types",types,"rows",rows);
        if(full || !environment.isEmpty())result.put("env",environment);
        // Process map-level provenance with the ordinary metadata boundary, never as a source AST payload.
        Map<String,Object> extra=new LinkedHashMap<>(input);
        for(String field:Arrays.asList("width","height","cells","unknown"))extra.remove(field);
        result.putAll(cast(projectValue(extra,sources,full,"terrain_metadata")));
        if(!full && !protectedField(result,"env"))compactEffects(result);
        return result;
    }

    private static final Set<String> CELL_STRUCTURAL=new HashSet<>(Arrays.asList("cell","x","y","visibility","environment"));

    /** Definitions are local to one observation and contain every field other than its cell binding. */
    private static void compactEntities(Map<String,Object> result) {
        if(!(result.get("entities") instanceof List) || result.containsKey("entity_defs") || annotationReferences(result,"entities"))return;
        List<?> entities=(List<?>)result.get("entities");
        Map<Map<String,Object>,Integer> counts=new LinkedHashMap<>();
        for(Object raw:entities) {
            Map<String,Object> descriptor=entityDescriptor(raw);
            if(descriptor!=null)counts.put(descriptor,counts.getOrDefault(descriptor,0)+1);
        }
        List<Object> definitions=new ArrayList<>(),references=new ArrayList<>();
        Map<Map<String,Object>,Integer> indexes=new LinkedHashMap<>();
        for(Object raw:entities) {
            Map<String,Object> descriptor=entityDescriptor(raw);
            if(descriptor==null || counts.get(descriptor)<2){references.add(raw);continue;}
            Integer index=indexes.get(descriptor);
            if(index==null){index=definitions.size();indexes.put(descriptor,index);definitions.add(descriptor);}
            references.add(map("cell",cast(raw).get("cell"),"def",index));
        }
        if(definitions.isEmpty())return;
        Map<String,Object> candidate=new LinkedHashMap<>(result);
        candidate.put("entity_defs",definitions);candidate.put("entities",references);
        if(encodedBytes(candidate)<encodedBytes(result)){result.put("entity_defs",definitions);result.put("entities",references);}
    }

    private static Map<String,Object> entityDescriptor(Object raw) {
        if(!(raw instanceof Map))return null;
        Map<String,Object> entity=cast(raw);
        if(!Arrays.asList("trap","container","character").contains(entity.get("kind")) || !entity.containsKey("cell")
                || annotationReferences(entity,"cell"))return null;
        Map<String,Object> descriptor=new LinkedHashMap<>(entity);descriptor.remove("cell");
        return descriptor;
    }

    /** A definition is the complete ordered effect list, including repeated effects and public metadata. */
    private static void compactEffects(Map<String,Object> result) {
        if(!(result.get("env") instanceof Map) || result.containsKey("effect_defs") || annotationReferences(result,"env"))return;
        Map<String,Object> environment=cast(result.get("env"));
        Map<List<?>,Integer> counts=new LinkedHashMap<>();
        for(Object value:environment.values())if(value instanceof List)counts.put((List<?>)value,counts.getOrDefault(value,0)+1);
        Map<List<?>,Integer> indexes=new LinkedHashMap<>();
        List<Object> definitions=new ArrayList<>();Map<String,Object> references=new LinkedHashMap<>();
        for(Map.Entry<String,Object> entry:environment.entrySet()) {
            Object value=entry.getValue();
            if(!(value instanceof List) || counts.get(value)<2){references.put(entry.getKey(),value);continue;}
            List<?> effects=(List<?>)value;Integer index=indexes.get(effects);
            if(index==null){index=definitions.size();indexes.put(effects,index);definitions.add(effects);}
            references.put(entry.getKey(),index);
        }
        if(definitions.isEmpty())return;
        Map<String,Object> candidate=new LinkedHashMap<>(result);candidate.put("effect_defs",definitions);candidate.put("env",references);
        if(encodedBytes(candidate)<encodedBytes(result)){result.put("effect_defs",definitions);result.put("env",references);}
    }

    private static int encodedBytes(Object value) { return JsonCodec.encode(value).getBytes(StandardCharsets.UTF_8).length; }

    /** An unresolved annotation keeps its addressed structure inline; local annotations move with their value. */
    private static boolean annotationReferences(Map<String,Object> input,String field) {
        for(String metadata:Arrays.asList("text_sources","text_origins","text_diagnostics")) {
            Object raw=input.get(metadata);
            if(raw instanceof Map)for(Object key:((Map<?,?>)raw).keySet())
                if(key instanceof String && references(relativePath((String)key),field))return true;
        }
        Object presentation=input.get("pres");
        if(presentation instanceof Map && ((Map<?,?>)presentation).get("diag") instanceof List)
            for(Object raw:(List<?>)((Map<?,?>)presentation).get("diag"))
                if(raw instanceof Map && ((Map<?,?>)raw).get("field") instanceof String
                        && references(relativePath((String)((Map<?,?>)raw).get("field")),field))return true;
        return false;
    }

    /** Rehome array-indexed public text annotations before sorting/deduplicating descriptors. */
    private static Map<String,Object> normalizeTerrainMetadata(Map<String,Object> original) {
        Map<String,Object> input=cast(publicCopy(original));
        List<?> cells=(List<?>)input.get("cells");boolean retained=false;
        for(String metadata:Arrays.asList("text_sources","text_origins","text_diagnostics")) {
            Object raw=input.get(metadata);if(!(raw instanceof Map))continue;
            Map<String,Object> remaining=new LinkedHashMap<>();
            for(Map.Entry<String,Object> entry:cast(raw).entrySet()) {
                String path=entry.getKey();
                if(cellPath(path)) {
                    if(!distributeMapAnnotation(cells,metadata,path,entry.getValue())) {
                        remaining.put(retainedCellPath(path),entry.getValue());retained=true;
                    }
                } else remaining.put(mapDimensionPath(path),entry.getValue());
            }
            if(remaining.isEmpty())input.remove(metadata);else input.put(metadata,remaining);
        }
        Object presentation=input.get("presentation");
        if(presentation instanceof Map && ((Map<?,?>)presentation).get("diagnostics") instanceof List) {
            List<Object> remaining=new ArrayList<>();
            for(Object raw:(List<?>)((Map<?,?>)presentation).get("diagnostics")) {
                if(!(raw instanceof Map)){remaining.add(raw);continue;}
                Map<String,Object> diagnostic=cast(raw);Object path=diagnostic.get("field");
                if(path instanceof String && cellPath((String)path)) {
                    if(!simpleDiagnostic(diagnostic) || !distributeMapAnnotation(cells,"text_diagnostics",(String)path,diagnostic.get("code"))) {
                        diagnostic.put("field",retainedCellPath((String)path));remaining.add(diagnostic);retained=true;
                    }
                } else {if(path instanceof String)diagnostic.put("field",mapDimensionPath((String)path));remaining.add(diagnostic);}
            }
            if(remaining.isEmpty())input.remove("presentation");else cast(presentation).put("diagnostics",remaining);
        }
        // Cell-local annotations of calculated coordinates or whole effects cannot truthfully describe a dictionary entry.
        for(int index=0;index<cells.size();index++) {
            Map<String,Object> cell=cast(cells.get(index));
            for(String metadata:Arrays.asList("text_sources","text_origins","text_diagnostics")) {
                if(!(cell.get(metadata) instanceof Map))continue;
                Map<String,Object> remaining=new LinkedHashMap<>();
                for(Map.Entry<String,Object> entry:cast(cell.get(metadata)).entrySet()) {
                    String field=entry.getKey();
                    if(structuralCellPath(field)) {
                        if(!distributeMapAnnotation(cells,metadata,"cells["+index+"]."+field,entry.getValue())) {
                            @SuppressWarnings("unchecked") Map<String,Object> mapMetadata=(Map<String,Object>)input.computeIfAbsent(metadata,ignored->new LinkedHashMap<>());
                            mapMetadata.put("preserved_cells["+index+"]."+field,entry.getValue());retained=true;
                        }
                    } else remaining.put(field,entry.getValue());
                }
                if(remaining.isEmpty())cell.remove(metadata);else cell.put(metadata,remaining);
            }
            Object localPresentation=cell.get("presentation");
            if(localPresentation instanceof Map && ((Map<?,?>)localPresentation).get("diagnostics") instanceof List) {
                List<Object> remaining=new ArrayList<>();
                for(Object raw:(List<?>)((Map<?,?>)localPresentation).get("diagnostics")) {
                    if(!(raw instanceof Map)){remaining.add(raw);continue;}
                    Map<String,Object> diagnostic=cast(raw);Object path=diagnostic.get("field");
                    String relative=path instanceof String?relativePath((String)path):"";
                    if(structuralCellPath(relative)) {
                        if(!simpleDiagnostic(diagnostic)) {
                            @SuppressWarnings("unchecked") Map<String,Object> aggregate=(Map<String,Object>)input.computeIfAbsent("presentation",ignored->map("status","partial","diagnostics",new ArrayList<>()));
                            aggregate.put("status","partial");
                            @SuppressWarnings("unchecked") List<Object> diagnostics=(List<Object>)aggregate.computeIfAbsent("diagnostics",ignored->new ArrayList<>());
                            Map<String,Object> copy=new LinkedHashMap<>(diagnostic);copy.put("field","$.preserved_cells["+index+"]."+relative);
                            diagnostics.add(copy);retained=true;
                        } else if(!distributeMapAnnotation(cells,"text_diagnostics","cells["+index+"]."+relative,diagnostic.get("code"))) {
                            @SuppressWarnings("unchecked") Map<String,Object> diagnostics=(Map<String,Object>)input.computeIfAbsent("text_diagnostics",ignored->new LinkedHashMap<>());
                            diagnostics.put("preserved_cells["+index+"]."+relative,diagnostic.get("code"));retained=true;
                        }
                    } else remaining.add(raw);
                }
                if(remaining.isEmpty())cell.remove("presentation");else cast(localPresentation).put("diagnostics",remaining);
            }
        }
        // Rare diagnostic evidence only, never a second gameplay map or a protocol-3 compatibility format.
        if(retained)input.put("preserved_cells",publicCopy(original.get("cells")));
        return input;
    }

    private static boolean distributeMapAnnotation(List<?> cells,String metadata,String rawPath,Object value) {
        String path=relativePath(rawPath);
        if("cells".equals(path) && value instanceof List) {
            List<?> annotations=(List<?>)value;if(annotations.size()>cells.size())return false;
            boolean complete=true;
            for(int index=0;index<annotations.size();index++) {
                Object annotation=annotations.get(index);if(annotation==null)continue;
                if(!(annotation instanceof Map) || ((Map<?,?>)annotation).containsKey("kind")){complete=false;continue;}
                for(Map.Entry<String,Object> entry:cast(annotation).entrySet())
                    complete &= distributeMapAnnotation(cells,metadata,"cells["+index+"]."+entry.getKey(),entry.getValue());
            }
            return complete;
        }
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("cells\\[(\\d+)\\]\\.(.+)").matcher(path);
        if(!match.matches())return false;
        int index=Integer.parseInt(match.group(1));if(index>=cells.size())return false;
        Map<String,Object> target=cast(cells.get(index));String field=match.group(2);
        java.util.regex.Matcher effect=java.util.regex.Pattern.compile("environment\\[(\\d+)\\]\\.(.+)").matcher(field);
        if(effect.matches()) {
            Object environment=target.get("environment");int effectIndex=Integer.parseInt(effect.group(1));
            if(!(environment instanceof List) || effectIndex>=((List<?>)environment).size() || !(((List<?>)environment).get(effectIndex) instanceof Map))return false;
            target=cast(((List<?>)environment).get(effectIndex));field=effect.group(2);
        } else if(structuralCellPath(field))return false;
        if(field.contains(".") || field.contains("[") || !target.containsKey(field))return false;
        @SuppressWarnings("unchecked") Map<String,Object> fields=(Map<String,Object>)target.computeIfAbsent(metadata,ignored->new LinkedHashMap<>());
        if(fields.containsKey(field) && !Objects.equals(fields.get(field),value))return false;
        fields.put(field,publicCopy(value));return true;
    }

    private static boolean cellPath(String path) {String relative=relativePath(path);return "cells".equals(relative) || relative.startsWith("cells[");}
    private static String retainedCellPath(String path) {return path.replaceFirst("(^|\\$\\.)cells", "$1preserved_cells");}
    private static String relativePath(String path) {return path.startsWith("$.")?path.substring(2):path;}
    private static String mapDimensionPath(String path) {
        String relative=relativePath(path);String prefix=path.startsWith("$.")?"$.":"";
        return prefix+("width".equals(relative)?"w":"height".equals(relative)?"h":relative);
    }
    private static boolean structuralCellPath(String path) {
        String field=relativePath(path).split("[.\\[]",2)[0];return CELL_STRUCTURAL.contains(field);
    }
    private static Object publicCopy(Object value) {
        if(value instanceof Map){Map<String,Object> result=new LinkedHashMap<>();for(Map.Entry<String,Object> entry:cast(value).entrySet())result.put(entry.getKey(),OPAQUE.contains(entry.getKey())?entry.getValue():publicCopy(entry.getValue()));return UiProjectionHints.preserve(value,result);}
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(publicCopy(child));return result;}
        return value;
    }

    private static List<Object> row(int y,int x,List<Integer> indexes,StringBuilder visibility,boolean strings,boolean full) {
        Object tiles=indexes;
        if(strings){StringBuilder chars=new StringBuilder();for(int index:indexes)chars.append(TILE_ALPHABET.charAt(index));tiles=chars.toString();}
        String vis=visibility.toString();
        if(!full && !vis.isEmpty() && vis.chars().allMatch(character -> character==visibility.charAt(0)))vis=vis.substring(0,1);
        return Arrays.asList(y,x,tiles,vis);
    }

    private static Map<String,Object> action(Map<String,Object> input,boolean sources,boolean full,boolean preserveFields) {
        // Availability and argument constraints belong to this observation, even when a similar
        // static rule exists in info.schema. A future or dynamic value cannot be reconstructed there.
        return cast(projectValue(input,sources,full || preserveFields,"actions"));
    }

    private static void attachActions(Map<String,Object> result) {
        if(!(result.get("acts") instanceof List))return;
        Object ui=result.get("ui");
        Object nodes=ui instanceof Map?((Map<?,?>)ui).get("nodes"):result.get("nodes");
        if(!(nodes instanceof List))return;
        Map<Object,Map<String,Object>> index=new LinkedHashMap<>();
        for(Object node:(List<?>)nodes)if(node instanceof Map)index.put(((Map<?,?>)node).get("id"),cast(node));
        for(Object value:(List<?>)result.get("acts")) {
            if(!(value instanceof Map))continue;
            Map<String,Object> descriptor=cast(value),node=index.get(descriptor.get("ctl"));
            if(node==null)continue;
            Map<String,Object> operation=new LinkedHashMap<>(descriptor);
            if(!annotationReferences(operation,"ctl"))operation.remove("ctl");
            // Only the enclosing node's exact control binding is inherited. Keep every other
            // operation field, including labels, bounds, modes and their independent metadata.
            @SuppressWarnings("unchecked") List<Object> operations=(List<Object>)node.computeIfAbsent("ops",ignored->new ArrayList<>());
            operations.add(operation);
        }
        // The original list also remains complete: moving entries to separate nodes loses its
        // relative ordering and prevents an exact reconstruction of the advertised capabilities.
    }

    private static boolean fieldMetadataContained(Map<String,Object> operation,String field,Map<String,Object> node,String nodeField) {
        for(String metadata:Arrays.asList("text_sources","text_origins")) {
            Object raw=operation.get(metadata),nodeRaw=node.get(metadata);
            if(raw instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)raw).entrySet())
                if(references(entry.getKey(),field)) {
                    String target=nodeField+String.valueOf(entry.getKey()).substring(field.length());
                    if(!(nodeRaw instanceof Map) || !Objects.equals(entry.getValue(),((Map<?,?>)nodeRaw).get(target)))return false;
                }
        }
        Object pres=operation.get("pres"),nodePres=node.get("pres");
        if(pres instanceof Map && ((Map<?,?>)pres).get("diag") instanceof List)
            for(Object diagnostic:(List<?>)((Map<?,?>)pres).get("diag")) {
                if(!(diagnostic instanceof Map) || !references(((Map<?,?>)diagnostic).get("field"),field))continue;
                String target=nodeField+String.valueOf(((Map<?,?>)diagnostic).get("field")).substring(field.length());
                boolean found=false;
                if(nodePres instanceof Map && ((Map<?,?>)nodePres).get("diag") instanceof List)
                    for(Object other:(List<?>)((Map<?,?>)nodePres).get("diag"))
                        if(other instanceof Map && target.equals(((Map<?,?>)other).get("field"))
                                && Objects.equals(((Map<?,?>)diagnostic).get("code"),((Map<?,?>)other).get("code")))found=true;
                if(!found)return false;
            }
        return true;
    }
    private static boolean references(Object path,String field) {
        return path instanceof String && (path.equals(field) || ((String)path).startsWith(field+"[") || ((String)path).startsWith(field+"."));
    }

    private static Object sourceFields(Object value) {
        if(!(value instanceof Map))return value;
        Map<String,Object> result=new LinkedHashMap<>();boolean renamed=false;
        for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
            String field=String.valueOf(entry.getKey()),wire=compactPath(field);
            Object child=sourceFieldValue(entry.getValue());
            renamed|=!wire.equals(field) || child!=entry.getValue();result.put(wire,child);
        }
        return renamed?result:value;
    }
    private static Object sourceFieldValue(Object value) {
        if(value instanceof Map && !((Map<?,?>)value).containsKey("kind"))return sourceFields(value);
        if(value instanceof List) {
            List<Object> result=new ArrayList<>();boolean changed=false;
            for(Object child:(List<?>)value){Object projected=sourceFieldValue(child);result.add(projected);changed|=projected!=child;}
            return changed?result:value;
        }
        return value;
    }
    private static Map<String,Object> origins(Object sources) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(sources instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)sources).entrySet()) {
            Set<String> kinds=PublicTextSources.origins(entry.getValue());
            if(!kinds.isEmpty())result.put(compactPath(String.valueOf(entry.getKey())),new ArrayList<>(kinds));
        }
        return result;
    }
    private static void mergeOrigins(Map<String,Object> output,Object value) {
        if(!(value instanceof Map)){output.put("text_origins",value);return;}
        Map<String,Object> combined=new LinkedHashMap<>();
        if(output.get("text_origins") instanceof Map)combined.putAll(cast(output.get("text_origins")));
        for(Map.Entry<String,Object> entry:cast(value).entrySet()) {
            Object previous=combined.get(entry.getKey());
            if(previous instanceof List && entry.getValue() instanceof List) {
                Set<Object> origins=new LinkedHashSet<>((List<?>)previous);origins.addAll((List<?>)entry.getValue());
                combined.put(entry.getKey(),new ArrayList<>(origins));
            } else combined.put(entry.getKey(),entry.getValue());
        }
        output.put("text_origins",combined);
    }
    private static Map<String,Object> projectPresentation(Map<?,?> value,Map<String,Object> canonical) {
        List<Object> diagnostics=new ArrayList<>();
        if(value.get("diagnostics") instanceof List)for(Object diagnostic:(List<?>)value.get("diagnostics")) {
            if(diagnostic instanceof Map) {
                Map<String,Object> copy=new LinkedHashMap<>(cast(diagnostic));
                // Aggregate canonical presentation entries are duplicated by local text diagnostics.
                // Recreate those from projected fields so table/control relocation produces real wire paths.
                if(simpleDiagnostic(copy) && canonicalDiagnosticExists(canonical,"$",copy))continue;
                if(copy.get("field") instanceof String) {
                    String path=compactPath((String)copy.get("field"));
                    if(path.startsWith("$."))path=path.substring(2);
                    else if("$".equals(path))path="";
                    copy.put("field",path);
                }
                diagnostics.add(copy);
            } else diagnostics.add(diagnostic);
        }
        Map<String,Object> result=new LinkedHashMap<>(cast(value));result.remove("status");result.remove("diagnostics");
        result.put("st",value.get("status"));result.put("diag",diagnostics);return result;
    }
    private static boolean canonicalDiagnosticExists(Object value,String path,Map<String,Object> diagnostic) {
        if(value instanceof Map) {
            Map<?,?> current=(Map<?,?>)value;
            if(current.get("text_diagnostics") instanceof Map)
                for(Map.Entry<?,?> entry:((Map<?,?>)current.get("text_diagnostics")).entrySet())
                    if((path+"."+entry.getKey()).equals(diagnostic.get("field")) && Objects.equals(entry.getValue(),diagnostic.get("code")))return true;
            for(Map.Entry<?,?> entry:current.entrySet())
                if(!OPAQUE.contains(entry.getKey()) && !Arrays.asList("presentation","text_sources").contains(entry.getKey())
                        && canonicalDiagnosticExists(entry.getValue(),path+"."+entry.getKey(),diagnostic))return true;
        } else if(value instanceof List) {
            int index=0;for(Object child:(List<?>)value)if(canonicalDiagnosticExists(child,path+"["+index+++ "]",diagnostic))return true;
        }
        return false;
    }
    private static String compactPath(String path) {
        String[] parts=path.split("\\.",-1);StringBuilder result=new StringBuilder();
        for(String part:parts) {
            if("observation".equals(part))continue;
            int bracket=part.indexOf('[');
            String name=bracket<0?part:part.substring(0,bracket);
            if(result.length()>0)result.append('.');
            result.append("action".equals(name)?"op":WireNames.field(name));if(bracket>=0)result.append(part.substring(bracket));
        }
        return result.toString();
    }
    private static void collectDiagnostics(Object value,String path,List<Object> diagnostics) {
        if(value instanceof Map) {
            Map<?,?> current=(Map<?,?>)value;
            Object pres=current.get("pres");
            if(pres instanceof Map && ((Map<?,?>)pres).get("diag") instanceof List)
                for(Object item:(List<?>)((Map<?,?>)pres).get("diag")) {
                    Map<String,Object> entry=new LinkedHashMap<>(cast(item));
                    Object field=entry.get("field");
                    String relative=String.valueOf(field);
                    if(relative.startsWith("$."))relative=relative.substring(2);
                    else if("$".equals(relative))relative="";
                    entry.put("field",path+(relative.isEmpty()?"":"."+relative));
                    if(!diagnostics.contains(entry))diagnostics.add(entry);
                }
            for(Map.Entry<?,?> entry:current.entrySet())
                if(!OPAQUE.contains(entry.getKey()) && !Arrays.asList("pres","text_sources").contains(entry.getKey()))
                    collectDiagnostics(entry.getValue(),path+"."+entry.getKey(),diagnostics);
        } else if(value instanceof List) {
            int index=0;for(Object item:(List<?>)value)collectDiagnostics(item,path+"["+index+++ "]",diagnostics);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object value) { return (Map<String,Object>)value; }
}
