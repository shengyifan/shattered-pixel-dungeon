package com.shatteredpixel.shatteredpixeldungeon.ui;

import java.util.Map;

/** Public gameplay meaning already selected by a native display branch. Capture is read-only. */
public interface GameplayStatus extends RenderedStatus {
    Map<String,Object> gameplayStatus();

    /** Feedback lifetimes may be excluded from input freshness without losing their occurrences. */
    default Map<String,Object> gameplayIntentStatus() { return gameplayStatus(); }

    /** Process-local identity for same-frame ownership. Never serialize this object. */
    default Object gameplaySubject() { return null; }

    /** Exact native child bindings for same-frame text/provenance ownership. */
    default Map<String,com.watabou.noosa.BitmapText> gameplayTextComponents() {
        return java.util.Collections.emptyMap();
    }

    /** Explicit unknown meaning, with a protocol-typed marker and a relative diagnostic path. */
    static Map<String,Object> unmappedIndicator(String field,String sourceCode) {
        Map<String,Object> diagnostic=new java.util.LinkedHashMap<>();
        diagnostic.put("field",field);diagnostic.put("code","unmapped_indicator");diagnostic.put("indicator",sourceCode);
        Map<String,Object> presentation=new java.util.LinkedHashMap<>();
        presentation.put("status","partial");
        presentation.put("diagnostics",java.util.Collections.singletonList(java.util.Collections.unmodifiableMap(diagnostic)));
        Map<String,Object> result=new java.util.LinkedHashMap<>();
        result.put("unmapped_indicator",true);result.put("presentation",java.util.Collections.unmodifiableMap(presentation));
        return java.util.Collections.unmodifiableMap(result);
    }

    /** An 8-bit display-channel sample, not the renderer's higher-precision parameter. */
    static float displayedChannel(float value) { return Math.round(value*255f)/255f; }

    @Override default Map<String,Object> renderedStatus() { return gameplayStatus(); }
    @Override default Map<String,Object> intentStatus() { return gameplayIntentStatus(); }
}
