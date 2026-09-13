package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.WireNames;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Pure protocol 3 projection of already rendered public values. Never observes the engine. */
public final class CompactProtocol {
    private static final Set<String> OPAQUE = new HashSet<>(Arrays.asList(
            "raw", "reply", "schema", "raw_request", "raw_bytes", "request_json", "response_json", "original_payload"));
    private static final Set<String> STATIC_ACTION = new HashSet<>(Arrays.asList("parameters", "arguments", "modes", "units"));
    private CompactProtocol() { }

    public static Map<String,Object> success(String id,String scope,String status,Object canonicalResult,boolean live,boolean sources) {
        Map<String,Object> response=map("v",3,"id",id);
        Map<?,?> original=canonicalResult instanceof Map?(Map<?,?>)canonicalResult:Collections.emptyMap();
        Object effectiveScope=live && original.get("scope_id")!=null?original.get("scope_id"):scope;
        if(effectiveScope!=null)response.put("s",effectiveScope);
        if(live && original.get("state_version")!=null)response.put("rev",original.get("state_version"));
        response.put("st",status);
        Object projected=project(canonicalResult,sources);
        if(projected instanceof Map && live) {
            Map<String,Object> data=new LinkedHashMap<>(cast(projected));
            data.remove("s"); data.remove("rev"); projected=data;
        }
        response.put("data",projected);
        List<Object> diagnostics=new ArrayList<>();
        collectDiagnostics(projected,"$.data",diagnostics);
        if(!diagnostics.isEmpty())response.put("pres",map("st","partial","diag",diagnostics));
        return response;
    }

    public static Map<String,Object> failure(String id,String scope,String code) {
        Map<String,Object> response=map("v",3,"id",id);
        if(scope!=null)response.put("s",scope);
        response.put("err",code);
        return response;
    }

    public static Object project(Object canonical,boolean sources) {
        if(canonical instanceof List) {
            List<Object> result=new ArrayList<>();
            for(Object value:(List<?>)canonical)result.add(project(value,sources));
            return result;
        }
        if(!(canonical instanceof Map))return canonical;
        Map<String,Object> input=cast(canonical);
        if(input.containsKey("width") && input.containsKey("height") && input.get("cells") instanceof List)
            return terrain(input,sources);
        Map<String,Object> flattened=new LinkedHashMap<>();
        if(input.get("observation") instanceof Map)flattened.putAll(cast(input.get("observation")));
        flattened.putAll(input);flattened.remove("observation");
        Map<String,Object> result=new LinkedHashMap<>();
        for(Map.Entry<String,Object> entry:flattened.entrySet()) {
            String key=entry.getKey();Object value=entry.getValue();
            if("coverage".equals(key) || "translation_status".equals(key) || "text_diagnostics".equals(key))continue;
            if("text_sources".equals(key)) {
                if(sources) result.put(key,sourceFields(value));
                else {
                    Map<String,Object> origins=origins(value);
                    if(!origins.isEmpty())result.put("text_origins",origins);
                }
                continue;
            }
            if("presentation".equals(key) && value instanceof Map) {
                if(!"complete".equals(((Map<?,?>)value).get("status"))) {
                    Map<String,Object> presentation=projectPresentation((Map<?,?>)value,input);
                    if(!((List<?>)presentation.get("diag")).isEmpty() || !hasTextDiagnostics(input))result.put("pres",presentation);
                }
                continue;
            }
            if("actions".equals(key) && value instanceof List) {
                List<Object> actions=new ArrayList<>();
                for(Object item:(List<?>)value)actions.add(item instanceof Map?action(cast(item),sources):project(item,sources));
                result.put("acts",actions);continue;
            }
            if("details_via".equals(key) && value instanceof String) value=WireNames.operation((String)value);
            if("action".equals(key) && value instanceof String) {
                result.put("op",WireNames.operation((String)value));continue;
            }
            if("direction".equals(key))value=WireNames.direction(value);
            result.put(WireNames.field(key),OPAQUE.contains(key)?value:project(value,sources));
        }
        if(flattened.get("text_diagnostics") instanceof Map && !((Map<?,?>)flattened.get("text_diagnostics")).isEmpty()) {
            List<Object> diagnostics=new ArrayList<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)flattened.get("text_diagnostics")).entrySet())
                diagnostics.add(map("field",compactPath(String.valueOf(entry.getKey())),"code",entry.getValue()));
            result.put("pres",map("st","partial","diag",diagnostics));
        } else if("partial".equals(flattened.get("translation_status"))) {
            result.put("pres",map("st","partial","diag",Collections.emptyList()));
        }
        attachActions(result);
        return result;
    }

    /** Static rules are discoverable once, instead of duplicated in every state. */
    public static Map<String,Object> info() {
        Map<String,Object> commands=new LinkedHashMap<>();
        for(String op:WireNames.operations())commands.put(op,new ArrayList<>(WireNames.parameters(op)));
        return map("commands",commands,"dirs",WireNames.DIRECTIONS,
                "map",map("cols",Arrays.asList("cell","tile","vis"),"tile","index into types",
                        "vis",map("v","visible","s","visited","m","mapped"),"unknown","omitted",
                        "coordinates","x=cell%w; y=cell/w (integer)","env","cell-indexed visible environment effects"),
                "rules",map("action","s and rev required; use a fresh id", "cell",map("mode",Arrays.asList("act","examine","context"),"default","act"),
                        "click",map("g",Arrays.asList("click","right","middle","long"),"default","click"),
                        "choose",map("opt","zero-based option index","alt","optional boolean"),
                        "bind_slot",map("slot",Arrays.asList(1,2,3)),
                        "pan","x/y in map_view units", "req",map("get",Arrays.asList("raw","reply","before","after","meta")),
                        "state",map("src","include complete frozen text source trees"),
                        "pagination",map("after","exclusive sequence cursor; default 0","until","inclusive fixed upper sequence; omit on the first page",
                                "limit","1 to 100; default 50","continue","send next as after and keep the returned until",
                                "end","true when the fixed range is exhausted")),
                "coverage",map("status","observation_with_inspection",
                        "details_via",map("equipment_stats_and_buff_durations","click","journal_and_catalogue","click","custom_terrain_descriptions",map("op","cell","mode","examine")),
                        "inspection_policy","Use current nodes and their ops; details are read from displayed windows"));
    }

    private static Map<String,Object> terrain(Map<String,Object> input,boolean sources) {
        List<Object> types=new ArrayList<>(),cells=new ArrayList<>();
        Map<Map<String,Object>,Integer> indexes=new LinkedHashMap<>();
        Map<String,Object> environment=new LinkedHashMap<>();
        for(Object item:(List<?>)input.get("cells")) {
            Map<String,Object> cell=cast(item),descriptor=new LinkedHashMap<>();
            descriptor.put("terrain",cell.get("terrain"));descriptor.put("name",cell.get("name"));
            for(String marker:Arrays.asList("text_sources","text_diagnostics","translation_status","clipped"))
                if(cell.containsKey(marker))descriptor.put(marker,cell.get(marker));
            descriptor=cast(project(descriptor,sources));
            Integer index=indexes.get(descriptor);
            if(index==null){index=types.size();indexes.put(descriptor,index);types.add(descriptor);}
            Object visibility=cell.get("visibility");
            if("visible".equals(visibility))visibility="v";
            else if("visited".equals(visibility))visibility="s";
            else if("mapped".equals(visibility))visibility="m";
            cells.add(Arrays.asList(cell.get("cell"),index,visibility));
            Object effects=cell.get("environment");
            if(effects instanceof List && !((List<?>)effects).isEmpty())
                environment.put(String.valueOf(cell.get("cell")),project(effects,sources));
        }
        return map("w",input.get("width"),"h",input.get("height"),"types",types,
                "cols",Arrays.asList("cell","tile","vis"),"cells",cells,"env",environment);
    }

    private static Map<String,Object> action(Map<String,Object> input,boolean sources) {
        Map<String,Object> compact=new LinkedHashMap<>(input);
        for(String key:STATIC_ACTION)compact.remove(key);
        return cast(project(compact,sources));
    }

    private static void attachActions(Map<String,Object> result) {
        if(!(result.get("acts") instanceof List))return;
        Object ui=result.get("ui");
        Object nodes=ui instanceof Map?((Map<?,?>)ui).get("nodes"):result.get("nodes");
        if(!(nodes instanceof List))return;
        Map<Object,Map<String,Object>> index=new LinkedHashMap<>();
        for(Object node:(List<?>)nodes)if(node instanceof Map)index.put(((Map<?,?>)node).get("id"),cast(node));
        List<Object> global=new ArrayList<>();
        for(Object value:(List<?>)result.get("acts")) {
            if(!(value instanceof Map)){global.add(value);continue;}
            Map<String,Object> descriptor=cast(value),node=index.get(descriptor.get("ctl"));
            if(node==null){global.add(value);continue;}
            Map<String,Object> operation=new LinkedHashMap<>(descriptor);operation.remove("ctl");
            for(String key:new ArrayList<>(operation.keySet())) {
                if(!Arrays.asList("op","text_sources","text_origins","pres").contains(key)
                        && Objects.equals(operation.get(key),node.get(key)) && fieldMetadataContained(operation,key,node,key)) {
                    operation.remove(key);deduplicateFieldMarkers(operation,node,key,key);
                }
            }
            if(operation.containsKey("label") && Objects.equals(operation.get("label"),node.get("text"))
                    && fieldMetadataContained(operation,"label",node,"text")) {
                operation.remove("label");deduplicateFieldMarkers(operation,node,"label","text");
            }
            if(Objects.equals(operation.get("slots"),node.get("binding_slots")))operation.remove("slots");
            if(Objects.equals(operation.get("range"),Arrays.asList(node.get("min"),node.get("max"))))operation.remove("range");
            @SuppressWarnings("unchecked") List<Object> operations=(List<Object>)node.computeIfAbsent("ops",ignored->new ArrayList<>());
            operations.add(operation);
        }
        result.put("acts",global);
    }

    private static boolean fieldMetadataContained(Map<String,Object> operation,String field,Map<String,Object> node,String nodeField) {
        for(String metadata:Arrays.asList("text_sources","text_origins")) {
            Object raw=operation.get(metadata),nodeRaw=node.get(metadata);
            if(raw instanceof Map && ((Map<?,?>)raw).containsKey(field)
                    && (!(nodeRaw instanceof Map) || !Objects.equals(((Map<?,?>)raw).get(field),((Map<?,?>)nodeRaw).get(nodeField))))return false;
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
        return path instanceof String && (path.equals(field) || ((String)path).startsWith(field+"["));
    }

    private static void deduplicateFieldMarkers(Map<String,Object> operation,Map<String,Object> node,String field,String nodeField) {
        for(String metadata:Arrays.asList("text_sources","text_origins")) {
            Object raw=operation.get(metadata),nodeRaw=node.get(metadata);
            if(raw instanceof Map && nodeRaw instanceof Map) {
                Map<String,Object> fields=new LinkedHashMap<>(cast(raw));
                if(fields.containsKey(field) && Objects.equals(fields.get(field),((Map<?,?>)nodeRaw).get(nodeField)))fields.remove(field);
                if(fields.isEmpty())operation.remove(metadata);else operation.put(metadata,fields);
            }
        }
        if(operation.get("pres") instanceof Map && node.get("pres") instanceof Map) {
            Map<String,Object> pres=new LinkedHashMap<>(cast(operation.get("pres")));
            Object raw=pres.get("diag"),nodeRaw=((Map<?,?>)node.get("pres")).get("diag");
            if(raw instanceof List && nodeRaw instanceof List) {
                List<Object> retained=new ArrayList<>();
                for(Object diagnostic:(List<?>)raw) {
                    boolean duplicated=false;
                    if(diagnostic instanceof Map && references(((Map<?,?>)diagnostic).get("field"),field))
                        for(Object nodeDiagnostic:(List<?>)nodeRaw)
                            if(nodeDiagnostic instanceof Map && (nodeField+String.valueOf(((Map<?,?>)diagnostic).get("field")).substring(field.length())).equals(((Map<?,?>)nodeDiagnostic).get("field"))
                                    && Objects.equals(((Map<?,?>)diagnostic).get("code"),((Map<?,?>)nodeDiagnostic).get("code")))duplicated=true;
                    if(!duplicated)retained.add(diagnostic);
                }
                if(retained.isEmpty())operation.remove("pres");else {pres.put("diag",retained);operation.put("pres",pres);}
            }
        }
    }

    private static Object sourceFields(Object value) {
        if(!(value instanceof Map))return value;
        Map<String,Object> result=new LinkedHashMap<>();boolean renamed=false;
        for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
            String field=String.valueOf(entry.getKey()),wire=WireNames.field(field);
            renamed|=!wire.equals(field);result.put(wire,entry.getValue());
        }
        return renamed?result:value;
    }
    private static Map<String,Object> origins(Object sources) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(sources instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)sources).entrySet()) {
            Set<String> kinds=new LinkedHashSet<>();collectOrigins(entry.getValue(),kinds);
            if(!kinds.isEmpty())result.put(WireNames.field(String.valueOf(entry.getKey())),new ArrayList<>(kinds));
        }
        return result;
    }
    private static void collectOrigins(Object value,Set<String> kinds) {
        if(value instanceof Map) {
            Map<?,?> source=(Map<?,?>)value;
            for(String field:Arrays.asList("kind","origin"))
                if("user".equals(source.get(field)) || "external".equals(source.get(field)))kinds.add((String)source.get(field));
            for(Object part:source.values())collectOrigins(part,kinds);
        } else if(value instanceof List)for(Object part:(List<?>)value)collectOrigins(part,kinds);
    }
    private static Map<String,Object> projectPresentation(Map<?,?> value,Map<String,Object> canonical) {
        List<Object> diagnostics=new ArrayList<>();
        if(value.get("diagnostics") instanceof List)for(Object diagnostic:(List<?>)value.get("diagnostics")) {
            if(diagnostic instanceof Map) {
                Map<String,Object> copy=new LinkedHashMap<>(cast(diagnostic));
                // Aggregate canonical presentation entries are duplicated by local text diagnostics.
                // Recreate those from projected fields so table/control relocation produces real wire paths.
                if(canonicalDiagnosticExists(canonical,"$",copy))continue;
                if(copy.get("field") instanceof String) {
                    String path=compactPath((String)copy.get("field"));
                    if(path.startsWith("$."))path=path.substring(2);
                    else if("$".equals(path))path="";
                    copy.put("field",path);
                }
                diagnostics.add(copy);
            } else diagnostics.add(diagnostic);
        }
        return map("st",value.get("status"),"diag",diagnostics);
    }
    private static boolean hasTextDiagnostics(Object value) {
        if(value instanceof Map) {
            Map<?,?> current=(Map<?,?>)value;
            if(current.get("text_diagnostics") instanceof Map && !((Map<?,?>)current.get("text_diagnostics")).isEmpty())return true;
            for(Map.Entry<?,?> entry:current.entrySet())
                if(!OPAQUE.contains(entry.getKey()) && !Arrays.asList("presentation","text_sources").contains(entry.getKey()) && hasTextDiagnostics(entry.getValue()))return true;
        } else if(value instanceof List)for(Object child:(List<?>)value)if(hasTextDiagnostics(child))return true;
        return false;
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
            result.append(WireNames.field(name));if(bracket>=0)result.append(part.substring(bracket));
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
