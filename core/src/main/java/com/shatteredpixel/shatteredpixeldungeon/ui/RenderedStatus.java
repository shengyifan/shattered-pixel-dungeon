package com.shatteredpixel.shatteredpixeldungeon.ui;

import java.util.Map;

/** Optional read-only semantic appearance of an existing, laid-out UI control. */
public interface RenderedStatus {
    Map<String,Object> renderedStatus();

    /** Values that change an input's meaning; exact decorative animation remains in renderedStatus. */
    default Map<String,Object> intentStatus() { return renderedStatus(); }
}
