package com.watabou.noosa;

import java.util.*;

/** Count of independently visible particle draws, never an estimate of simulation quantities. */
public final class VisualMetric {
    public final String kind;
    public final int cell,renderedParticles;
    public final Map<String,Object> appearance;
    public VisualMetric(String kind,int cell,int renderedParticles){
        this(kind,cell,renderedParticles,Collections.emptyMap());
    }
    public VisualMetric(String kind,int cell,int renderedParticles,Map<String,Object> appearance){
        this.kind=Objects.requireNonNull(kind);this.cell=cell;this.renderedParticles=renderedParticles;
        this.appearance=freeze(appearance);
        if(cell<0||renderedParticles<1)throw new IllegalArgumentException("A visible particle measurement requires a cell and positive count");
    }
    @Override public boolean equals(Object other){
        return other instanceof VisualMetric&&cell==((VisualMetric)other).cell
                &&renderedParticles==((VisualMetric)other).renderedParticles&&kind.equals(((VisualMetric)other).kind)
                &&appearance.equals(((VisualMetric)other).appearance);
    }
    @Override public int hashCode(){return Objects.hash(kind,cell,renderedParticles,appearance);}
    @SuppressWarnings("unchecked") private static <T> T freeze(T value){
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Byte
                ||value instanceof Short||value instanceof Integer||value instanceof Long)return value;
        if(value instanceof Float||value instanceof Double){
            if(!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Non-finite metric appearance");
            return value;
        }
        if(value instanceof Map){
            Map<String,Object> copy=new LinkedHashMap<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){
                if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("Metric appearance keys must be strings");
                copy.put((String)entry.getKey(),freeze(entry.getValue()));
            }
            return (T)Collections.unmodifiableMap(copy);
        }
        if(value instanceof List){List<Object> copy=new ArrayList<>();for(Object item:(List<?>)value)copy.add(freeze(item));return (T)Collections.unmodifiableList(copy);}
        throw new IllegalArgumentException("Metric appearance must contain only JSON values");
    }
}
