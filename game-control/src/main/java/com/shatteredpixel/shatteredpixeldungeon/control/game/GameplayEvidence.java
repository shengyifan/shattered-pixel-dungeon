package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.ui.GameplayIcons;
import com.watabou.noosa.VisualCue;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Boundary for semantic producer callbacks, before public snapshots or events are persisted. */
final class GameplayEvidence {
    private static final Set<String> DRAWING_FIELDS = new HashSet<>(Arrays.asList(
            "atlas", "frame_pixels", "texture_size", "flip_horizontal", "flip_vertical",
            "angle", "scale", "tint", "alpha", "opacity", "color", "screen_rect",
            "offset_pixels", "viewport_pixels", "rendered_particles", "size", "shape", "blend"));
    private GameplayEvidence() { }

    static Map<String,Object> cue(VisualCue cue) {
        Map<String,Object> value=map("kind",cue.kind,"cell",cue.cell);
        if(cue.sourceCell!=null)value.put("source_cell",cue.sourceCell);
        if(cue.direction!=null)value.put("direction",cue.direction);
        if(cue.appearance!=null&&!cue.appearance.isEmpty()){
            Map<String,Object> appearance=new LinkedHashMap<>(cue.appearance);
            Object rangeShape=appearance.get("shape");
            boolean reviewedRange=("blast_wave".equals(cue.kind)&&"ring".equals(rangeShape)
                    ||"supernova_halo".equals(cue.kind)&&"halo".equals(rangeShape))
                    &&"visual_extent".equals(appearance.get("coverage"))&&appearance.get("cells") instanceof List;
            if(reviewedRange)appearance.remove("shape");
            appearance=semantic(appearance);
            if(reviewedRange)appearance.put("shape",rangeShape);
            value.put("appearance",appearance);
        }
        if(cue.color!=null||cue.opacity!=null)partial(value,"appearance");
        return value;
    }

    /** Unknown producer output remains explicit partial evidence, never a drawing-data escape hatch. */
    static Map<String,Object> semantic(Map<String,Object> input) {
        Map<String,Object> result=new LinkedHashMap<>();
        boolean unmapped=false;
        for(Map.Entry<String,Object> field:input.entrySet()) {
            if(DRAWING_FIELDS.contains(field.getKey())){unmapped=true;continue;}
            result.put(field.getKey(),Arrays.asList("text_sources","text_origins","text_diagnostics","presentation","pres")
                    .contains(field.getKey())?protectedCopy(field.getValue()):copy(field.getValue()));
        }
        if(unmapped)partial(result,"indicator");
        return result;
    }

    @SuppressWarnings("unchecked") private static Object copy(Object value) {
        if(value instanceof Map)return semantic((Map<String,Object>)value);
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(copy(child));return result;}
        return value;
    }
    private static Object protectedCopy(Object value) {
        if(value instanceof Map){Map<Object,Object> result=new LinkedHashMap<>();
            for(Map.Entry<?,?> field:((Map<?,?>)value).entrySet())result.put(field.getKey(),protectedCopy(field.getValue()));
            return result;}
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(protectedCopy(child));return result;}
        return value;
    }

    @SuppressWarnings("unchecked") private static void partial(Map<String,Object> value,String field) {
        value.put("unmapped_indicator",true);
        Map<String,Object> presentation=value.get("presentation") instanceof Map
                ?new LinkedHashMap<>((Map<String,Object>)value.get("presentation")):new LinkedHashMap<>();
        List<Object> diagnostics=presentation.get("diagnostics") instanceof List
                ?new ArrayList<>((List<?>)presentation.get("diagnostics")):new ArrayList<>();
        Map<String,Object> diagnostic=map("field","unmapped_indicator","code","unmapped_indicator");
        if(!diagnostics.contains(diagnostic))diagnostics.add(diagnostic);
        presentation.put("status","partial");presentation.put("diagnostics",diagnostics);
        value.put("presentation",presentation);
    }

    static Map<String,Object> logLine(Object text,int color,boolean clipped) {
        Map<String,Object> value=new LinkedHashMap<>(GameplayIcons.logTone(color));
        value.put("text",text);
        if(clipped)value.put("clipped",true);
        return value;
    }
}
