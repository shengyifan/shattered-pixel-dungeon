package com.watabou.noosa;

import java.util.Objects;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;

/** Immutable semantic annotation of a grid visual that was drawn, not a simulation prediction. */
public final class VisualCue {
    public final String kind;
    public final int cell;
    /** Optional second, independently visible endpoint. Never a hidden target prediction. */
    public final Integer sourceCell;
    /** Optional motion direction measured from the drawn visual, not its AI destination. */
    public final String direction;
    /** Optional RGB of a drawn monochrome visual after its color transform, excluding opacity. */
    public final Integer color;
    public final Float opacity;
    /** Optional current atlas/frame/color values, frozen independently of the mutable drawable. */
    public final Map<String,Object> appearance;

    public VisualCue(String kind, int cell) {
        this(kind, cell, null, null);
    }

    public VisualCue(String kind, int cell, Integer sourceCell, String direction) {
        this(kind, cell, sourceCell, direction, null);
    }

    public VisualCue(String kind, int cell, Integer sourceCell, String direction, Integer color) {
        this(kind, cell, sourceCell, direction, color, null);
    }

    public VisualCue(String kind, int cell, Integer sourceCell, String direction, Integer color, Float opacity) {
        this(kind,cell,sourceCell,direction,color,opacity,null);
    }

    public VisualCue(String kind, int cell, Integer sourceCell, String direction, Integer color, Float opacity,
                     Map<String,Object> appearance) {
        this.kind = Objects.requireNonNull(kind);
        this.cell = cell;
        this.sourceCell = sourceCell;
        this.direction = direction;
        this.color = color;
        this.opacity = opacity;
        if(opacity!=null&&!Float.isFinite(opacity))throw new IllegalArgumentException("Non-finite cue opacity");
        @SuppressWarnings("unchecked") Map<String,Object> frozen=appearance==null?null:(Map<String,Object>)freeze(appearance);
        this.appearance=frozen;
    }

    @Override public boolean equals(Object other) {
        return other instanceof VisualCue && cell == ((VisualCue) other).cell && kind.equals(((VisualCue) other).kind)
                && Objects.equals(sourceCell, ((VisualCue) other).sourceCell)
                && Objects.equals(direction, ((VisualCue) other).direction)
                && Objects.equals(color, ((VisualCue) other).color)
                && Objects.equals(opacity, ((VisualCue) other).opacity)
                && Objects.equals(appearance, ((VisualCue) other).appearance);
    }

    @Override public int hashCode() { return Objects.hash(kind, cell, sourceCell, direction, color, opacity, appearance); }

    private static Object freeze(Object value) {
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Byte
                ||value instanceof Short||value instanceof Integer||value instanceof Long)return value;
        if(value instanceof Float||value instanceof Double) {
            if(!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Non-finite cue appearance");
            return value;
        }
        if(value instanceof Map) {
            Map<String,Object> copy=new LinkedHashMap<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
                if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("Cue appearance keys must be strings");
                copy.put((String)entry.getKey(),freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if(value instanceof List) {
            List<Object> copy=new ArrayList<>();for(Object child:(List<?>)value)copy.add(freeze(child));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Cue appearance must contain only JSON values");
    }
}
