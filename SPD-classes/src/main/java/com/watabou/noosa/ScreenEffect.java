package com.watabou.noosa;

import java.util.*;

/** Immutable measurements of an effect actually displayed on a completed frame. */
public final class ScreenEffect {
    public final String kind;
    /** Process-local visible occurrence identity; never part of publicData or serialized history. */
    public final Object episode;
    private final Map<String,Object> data;

    public ScreenEffect(String kind,Object episode,Map<String,Object> fields) {
        this.kind=Objects.requireNonNull(kind);this.episode=Objects.requireNonNull(episode);
        if(!Arrays.asList("screen_overlay","camera_displacement").contains(kind)||fields.containsKey("kind"))
            throw new IllegalArgumentException("Invalid screen-effect kind");
        Map<String,Object> values=new LinkedHashMap<>();values.put("kind",kind);values.putAll(fields);
        data=freeze(values);
    }

    public Map<String,Object> publicData(){return data;}

    @SuppressWarnings("unchecked") private static <T>T freeze(T value) {
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Byte
                ||value instanceof Short||value instanceof Integer||value instanceof Long)return value;
        if(value instanceof Float||value instanceof Double) {
            if(!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Non-finite screen measurement");
            return value;
        }
        if(value instanceof Map) {
            Map<String,Object> copy=new LinkedHashMap<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
                if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("Screen measurement keys must be strings");
                copy.put((String)entry.getKey(),freeze(entry.getValue()));
            }
            return (T)Collections.unmodifiableMap(copy);
        }
        if(value instanceof List){List<Object> copy=new ArrayList<>();for(Object child:(List<?>)value)copy.add(freeze(child));return (T)Collections.unmodifiableList(copy);}
        throw new IllegalArgumentException("Screen measurements must contain JSON values only");
    }
}
