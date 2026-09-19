package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import java.util.*;

/** Protocol 5 names live only at the wire boundary. Engine and audit names remain canonical. */
public final class WireNames {
    private static final Map<String,String> OPERATIONS;
    private static final Map<String,String> FIELDS;
    private static final Map<String,Set<String>> PARAMETERS;
    public static final List<String> DIRECTIONS = Collections.unmodifiableList(Arrays.asList("N","NE","E","SE","S","SW","W","NW"));
    private static final List<String> ENGINE_DIRECTIONS = Arrays.asList("north","northeast","east","southeast","south","southwest","west","northwest");
    static {
        Map<String,String> operations = new LinkedHashMap<>();
        pairs(operations, "info","protocol.info", "state","state.get", "actions","actions.list", "req","request.get",
                "history","history.list", "events","events.read", "move","move.step", "cell","cell.select",
                "item","inventory.open", "wait","wait", "rest","rest", "search","search", "save","game.save",
                "quit","app.quit", "cancel","action.cancel", "click","ui.activate", "choose","ui.choose",
                "select","ui.select", "text","ui.text", "value","ui.value", "scroll","ui.scroll",
                "bind_slot","ui.binding_slot", "bind_key","ui.binding_key", "back","ui.back", "reveal","ui.reveal",
                "zoom","view.zoom", "pan","view.pan", "untarget","cell.cancel");
        OPERATIONS = Collections.unmodifiableMap(operations);
        Map<String,String> fields = new LinkedHashMap<>();
        pairs(fields, "ctl","control", "dir","direction", "g","gesture", "loc","locator", "rid","target_id",
                "opt","option", "alt","alternate", "inv","inventory", "entities","visible_entities", "cues","visual_cues",
                "nodes","controls", "acts","actions", "desc","description", "qty","quantity", "min","minimum", "max","maximum",
                "s","scope_id", "rev","state_version",
                "mxp","max_experience", "ht","max_hp", "sub_name","subclass_name", "tp","talent_points_available",
                "via","details_via", "shortcut","shortcut_action", "prompt","cell_prompt", "activity","continuous_activity",
                "snap","snapshot_status", "item_info","inspected_item", "saves","saves_during_request", "saved","last_save",
                "sid","receipt_id", "src_s","origin_scope_id", "src_id","origin_request_id", "at","occurred_at");
        FIELDS = Collections.unmodifiableMap(fields);
        Map<String,Set<String>> parameters = new LinkedHashMap<>();
        for(String op:operations.keySet()) parameters.put(op, Collections.emptySet());
        parameters.put("state", set("src","view")); parameters.put("actions",set("view")); parameters.put("req", set("rid","get","src"));
        parameters.put("history", set("after","limit","until")); parameters.put("events",set("after","limit","until"));
        parameters.put("move",set("dir")); parameters.put("cell",set("cell","mode")); parameters.put("item",set("loc"));
        parameters.put("cancel",set("rid")); parameters.put("click",set("ctl","g")); parameters.put("choose",set("ctl","opt","alt"));
        parameters.put("select",set("ctl")); parameters.put("text",set("ctl","text","submit"));
        parameters.put("value",set("ctl","value")); parameters.put("scroll",set("ctl","x","y"));
        parameters.put("bind_slot",set("ctl","slot")); parameters.put("bind_key",set("ctl","keycode"));
        parameters.put("zoom",set("zoom")); parameters.put("pan",set("x","y"));
        PARAMETERS=Collections.unmodifiableMap(parameters);
    }
    private WireNames() { }
    private static void pairs(Map<String,String> target,String... pairs) {
        for(int i=0;i<pairs.length;i+=2) target.put(pairs[i],pairs[i+1]);
    }
    private static Set<String> set(String... values) { return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values))); }
    public static String canonicalOperation(String wire) { return OPERATIONS.get(wire); }
    public static String operation(String canonical) {
        for(Map.Entry<String,String> entry:OPERATIONS.entrySet()) if(entry.getValue().equals(canonical)) return entry.getKey();
        return canonical;
    }
    public static boolean isQuery(String wire) { return Arrays.asList("info","state","actions","req","history","events").contains(wire); }
    public static Set<String> parameters(String operation) { return PARAMETERS.get(operation); }
    public static Set<String> operations() { return OPERATIONS.keySet(); }
    public static Map<String,String> fields() { return FIELDS; }
    public static String canonicalField(String wire) { return FIELDS.getOrDefault(wire,wire); }
    public static String field(String canonical) {
        for(Map.Entry<String,String> entry:FIELDS.entrySet()) if(entry.getValue().equals(canonical)) return entry.getKey();
        return canonical;
    }
    public static String canonicalDirection(Object wire) {
        int index=DIRECTIONS.indexOf(wire);
        if(index<0) throw new ProtocolException("INVALID_ARGUMENT","dir must be one of N, NE, E, SE, S, SW, W, NW");
        return ENGINE_DIRECTIONS.get(index);
    }
    public static Object direction(Object canonical) {
        int index=ENGINE_DIRECTIONS.indexOf(canonical);
        return index<0?canonical:DIRECTIONS.get(index);
    }
}
