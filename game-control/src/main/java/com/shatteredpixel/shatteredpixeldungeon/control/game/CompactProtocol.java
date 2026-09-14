package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.WireNames;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Pure protocol 4 projection of already rendered public values. Never observes the engine. */
public final class CompactProtocol {
    private static final Set<String> OPAQUE = new HashSet<>(Arrays.asList(
            "raw", "reply", "schema", "raw_request", "raw_bytes", "request_json", "response_json", "original_payload"));
    private static final Set<String> STATIC_ACTION = new HashSet<>(Arrays.asList("parameters", "arguments", "modes", "units"));
    public static final String TILE_ALPHABET="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_";
    private CompactProtocol() { }

    public static Map<String,Object> success(String id,String scope,String status,Object canonicalResult,boolean live,boolean sources) {
        return success(id,scope,status,canonicalResult,live,sources,false);
    }

    public static Map<String,Object> success(String id,String scope,String status,Object canonicalResult,boolean live,boolean sources,boolean full) {
        Map<String,Object> response=map("v",4,"id",id);
        Map<?,?> original=canonicalResult instanceof Map?(Map<?,?>)canonicalResult:Collections.emptyMap();
        Object effectiveScope=live && original.get("scope_id")!=null?original.get("scope_id"):scope;
        if(effectiveScope!=null)response.put("s",effectiveScope);
        if(live && original.get("state_version")!=null)response.put("rev",original.get("state_version"));
        response.put("st",status);
        Object projected=project(canonicalResult,sources,full);
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
        Map<String,Object> response=map("v",4,"id",id);
        if(scope!=null)response.put("s",scope);
        response.put("err",code);
        return response;
    }

    public static Object project(Object canonical,boolean sources) { return project(canonical,sources,false); }

    public static Object project(Object canonical,boolean sources,boolean full) {
        Object projected=projectValue(canonical,sources,sources||full,"");
        normalizeUi(projected,sources||full);
        return projected;
    }

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
        if(input.get("observation") instanceof Map)flattened.putAll(cast(input.get("observation")));
        flattened.putAll(input);flattened.remove("observation");
        flattened=simplifyFields(flattened,full,context);
        Map<String,Object> result=new LinkedHashMap<>();
        for(Map.Entry<String,Object> entry:flattened.entrySet()) {
            String key=entry.getKey();Object value=entry.getValue();
            if("coverage".equals(key) || "translation_status".equals(key) || "text_diagnostics".equals(key))continue;
            if("text_sources".equals(key)) {
                if(sources) result.put(key,sourceFields(value));
                else {
                    Map<String,Object> origins=origins(value);
                    if(!origins.isEmpty())result.put("text_origins",origins);
                    Map<String,Object> unusual=unusualSources(value);
                    if(!unusual.isEmpty())result.put("text_sources",unusual);
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
                for(Object item:(List<?>)value)actions.add(item instanceof Map?action(cast(item),sources,full):projectValue(item,sources,full,"actions"));
                result.put("acts",actions);continue;
            }
            if("details_via".equals(key) && value instanceof String) value=WireNames.operation((String)value);
            if("action".equals(key) && value instanceof String) {
                result.put("op",WireNames.operation((String)value));continue;
            }
            if("direction".equals(key))value=WireNames.direction(value);
            result.put(WireNames.field(key),OPAQUE.contains(key)?value:projectValue(value,sources,full || "before".equals(key) || "after".equals(key),key));
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

    private static Map<String,Object> simplifyFields(Map<String,Object> input,boolean full,String context) {
        Map<String,Object> result=new LinkedHashMap<>(input);
        boolean item=input.containsKey("locator") && (input.containsKey("quantity") || input.containsKey("level_known") || input.containsKey("curse_known"));
        if(item) {
            for(String[] pair:new String[][]{{"level_known","level"},{"curse_known","cursed"}}) {
                if(result.containsKey(pair[0]) && !protectedField(result,pair[0]) && !protectedField(result,pair[1])) {
                    Object known=result.get(pair[0]);
                    if(known==null)removeField(result,pair[1]);
                    else if(Boolean.FALSE.equals(known))result.put(pair[1],null);
                    removeField(result,pair[0]);
                }
            }
            if(!full) {
                omitDefault(result,"quantity",1);omitDefault(result,"equipped",false);
                omitDefault(result,"available",true);omitDefault(result,"type_known",true);
                // Only player item records defer ordinary explanations. Containers and hazards are separate records.
                if(("inventory".equals(context) || "item".equals(context)) && !protectedField(result,"description"))removeField(result,"description");
            }
        }
        if(!full && "hero".equals(context) && result.get("talents") instanceof List && !protectedField(result,"talents")) {
            List<Object> invested=new ArrayList<>();
            for(Object talent:(List<?>)result.get("talents")) {
                if(!(talent instanceof Map)){invested.add(talent);continue;}
                Object points=((Map<?,?>)talent).get("points");
                if(!(points instanceof Number) || ((Number)points).intValue()!=0 || protectedRecord(cast(talent)))invested.add(talent);
            }
            result.put("talents",invested);
        }
        if(!full && "controls".equals(context)) {
            omitDefault(result,"enabled",true);omitDefault(result,"dimmed",false);
        }
        return result;
    }

    private static boolean protectedRecord(Map<String,Object> input) {
        for(String key:input.keySet())if(protectedField(input,key))return true;
        return false;
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
            if(raw instanceof Map)for(Object key:((Map<?,?>)raw).keySet())if(references(key,field))return true;
        }
        Object raw=input.get("text_sources");
        return raw instanceof Map && specialSource(((Map<?,?>)raw).get(field));
    }

    private static boolean unpairedDiagnostics(Map<String,Object> input) {
        Object presentation=input.get("presentation");
        if(!(presentation instanceof Map) || !(((Map<?,?>)presentation).get("diagnostics") instanceof List))return false;
        for(Object diagnostic:(List<?>)((Map<?,?>)presentation).get("diagnostics"))
            if(diagnostic instanceof Map && !canonicalDiagnosticExists(input,"$",cast(diagnostic)))return true;
        return false;
    }

    private static Map<String,Object> unusualSources(Object value) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet())
            if(unusualSource(entry.getValue()))result.put(compactPath(String.valueOf(entry.getKey())),sourceFieldValue(entry.getValue()));
        return result;
    }

    private static boolean unusualSource(Object value) {
        if(value instanceof List){for(Object child:(List<?>)value)if(unusualSource(child))return true;return false;}
        if(!(value instanceof Map))return false;
        Map<?,?> source=(Map<?,?>)value;Object kind=source.get("kind"),origin=source.get("origin");
        if(kind!=null && !Arrays.asList("resource","literal","scalar","concat","format","case","slice","replace","formatted_argument","join","plural","user","external").contains(kind))return true;
        if(origin!=null && !Arrays.asList("catalog","symbol","scalar","user","external").contains(origin))return true;
        for(Object child:source.values())if(unusualSource(child))return true;
        return false;
    }

    private static boolean specialSource(Object value) {
        if(value instanceof List){for(Object child:(List<?>)value)if(specialSource(child))return true;return false;}
        if(!(value instanceof Map))return false;
        Map<?,?> source=(Map<?,?>)value;
        Object kind=source.get("kind"),origin=source.get("origin");
        if(kind!=null && !Arrays.asList("resource","literal","scalar","concat","format","case","slice","replace","formatted_argument","join","plural").contains(kind))return true;
        if(origin!=null && !Arrays.asList("catalog","symbol","scalar").contains(origin))return true;
        for(Object child:source.values())if(specialSource(child))return true;
        return false;
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

    /** Run after attachment so a blank node with an operation, children or extra state cannot disappear. */
    private static void normalizeUi(Object value,boolean full) {
        if(value instanceof List){for(Object child:(List<?>)value)normalizeUi(child,full);return;}
        if(!(value instanceof Map))return;
        Map<String,Object> current=cast(value);
        // Presentation-only aggregate diagnostics use array indexes; retain that enclosing structure.
        if(current.get("pres") instanceof Map && ((Map<?,?>)current.get("pres")).get("diag") instanceof List)
            for(Object diagnostic:(List<?>)((Map<?,?>)current.get("pres")).get("diag"))
                if(diagnostic instanceof Map && String.valueOf(((Map<?,?>)diagnostic).get("field")).contains("nodes["))full=true;
        if(current.get("nodes") instanceof List) {
            List<?> nodes=(List<?>)current.get("nodes");Set<Object> parents=new HashSet<>();
            for(Object node:nodes)if(node instanceof Map && ((Map<?,?>)node).get("parent")!=null)parents.add(((Map<?,?>)node).get("parent"));
            List<Object> retained=new ArrayList<>();
            for(Object valueNode:nodes) {
                if(!(valueNode instanceof Map)){retained.add(valueNode);continue;}
                Map<String,Object> node=cast(valueNode);
                if(!protectedField(node,"gestures"))removeField(node,"gestures");
                if(node.get("ops") instanceof List)for(Object raw:(List<?>)node.get("ops")) {
                    Map<String,Object> operation=cast(raw);
                    if("click".equals(operation.get("op")) && Collections.singletonList("click").equals(operation.get("gestures"))
                            && !protectedField(operation,"gestures"))removeField(operation,"gestures");
                }
                boolean empty=!full && "text".equals(node.get("role")) && !parents.contains(node.get("id"))
                        && (!node.containsKey("text") || node.get("text")==null || "".equals(node.get("text")))
                        && !Boolean.FALSE.equals(node.get("enabled")) && !Boolean.TRUE.equals(node.get("dimmed"));
                if(empty)for(String key:node.keySet())
                    if(!Arrays.asList("id","role","parent","text","enabled","dimmed").contains(key)){empty=false;break;}
                if(!empty)retained.add(node);
            }
            current.put("nodes",retained);
        }
        for(Map.Entry<String,Object> entry:current.entrySet())
            if(!OPAQUE.contains(entry.getKey()) && !Arrays.asList("text_sources","pres").contains(entry.getKey()))
                normalizeUi(entry.getValue(),full || "before".equals(entry.getKey()) || "after".equals(entry.getKey()));
    }

    /** Static rules are discoverable once, instead of duplicated in every state. */
    public static Map<String,Object> info() {
        Map<String,Object> commands=new LinkedHashMap<>();
        for(String op:WireNames.operations())commands.put(op,new ArrayList<>(WireNames.parameters(op)));
        return map("commands",commands,"dirs",WireNames.DIRECTIONS,
                "map",map("rows",Arrays.asList("y","x_start","tiles","visibility"),"tiles","characters index this message's types; all rows use integer arrays when types exceeds 64",
                        "alphabet",TILE_ALPHABET,"vis",map("v","visible","s","visited","m","mapped"),"unknown","omitted; rows split at unknown gaps",
                        "coordinates","cell=y*w+x_start+offset","env","complete cell-indexed visible effects; omitted means empty"),
                "defaults",map("view","play","item",map("qty",1,"equipped",false,"available",true,"type_known",true),
                        "ui",map("enabled",true,"dimmed",false),"item_knowledge","level/cursed: absent=not applicable; null=unknown; value=known",
                        "click","ops is the only executable capability; omitted gestures means click"),
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
                rows.add(row(y,x,run,visibility,strings));run=new ArrayList<>();visibility=new StringBuilder();
            }
            if(run.isEmpty()){y=position/width;x=position%width;}
            run.add(terrainIndexes.get(i));Object vis=cell.get("visibility");
            visibility.append("visible".equals(vis)?"v":"visited".equals(vis)?"s":"mapped".equals(vis)?"m":String.valueOf(vis));
            previous=position;
        }
        if(!run.isEmpty())rows.add(row(y,x,run,visibility,strings));
        Map<String,Object> result=map("w",input.get("width"),"h",input.get("height"),"types",types,"rows",rows);
        if(full || !environment.isEmpty())result.put("env",environment);
        // Process map-level provenance with the ordinary metadata boundary, never as a source AST payload.
        Map<String,Object> extra=new LinkedHashMap<>(input);
        for(String field:Arrays.asList("width","height","cells","unknown"))extra.remove(field);
        result.putAll(cast(projectValue(extra,sources,full,"terrain_metadata")));
        return result;
    }

    private static final Set<String> CELL_STRUCTURAL=new HashSet<>(Arrays.asList("cell","x","y","visibility","environment"));

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
                    if(!distributeMapAnnotation(cells,"text_diagnostics",(String)path,diagnostic.get("code"))) {
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
                        if(!distributeMapAnnotation(cells,"text_diagnostics","cells["+index+"]."+relative,diagnostic.get("code"))) {
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
        if(value instanceof Map){Map<String,Object> result=new LinkedHashMap<>();for(Map.Entry<String,Object> entry:cast(value).entrySet())result.put(entry.getKey(),publicCopy(entry.getValue()));return result;}
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(publicCopy(child));return result;}
        return value;
    }

    private static List<Object> row(int y,int x,List<Integer> indexes,StringBuilder visibility,boolean strings) {
        Object tiles=indexes;
        if(strings){StringBuilder chars=new StringBuilder();for(int index:indexes)chars.append(TILE_ALPHABET.charAt(index));tiles=chars.toString();}
        return Arrays.asList(y,x,tiles,visibility.toString());
    }

    private static Map<String,Object> action(Map<String,Object> input,boolean sources,boolean full) {
        Map<String,Object> compact=new LinkedHashMap<>(input);
        for(String key:STATIC_ACTION)compact.remove(key);
        return cast(projectValue(compact,sources,full,"actions"));
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
                if(!Arrays.asList("op","gestures","text_sources","text_origins","pres").contains(key)
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
        return path instanceof String && (path.equals(field) || ((String)path).startsWith(field+"[") || ((String)path).startsWith(field+"."));
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
            Set<String> kinds=new LinkedHashSet<>();collectOrigins(entry.getValue(),kinds);
            if(!kinds.isEmpty())result.put(compactPath(String.valueOf(entry.getKey())),new ArrayList<>(kinds));
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
