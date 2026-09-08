package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.watabou.input.PointerEvent;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.Visual;

/** A labelled action attached to an existing visual or transparent hit area. */
public abstract class ActionArea extends PointerArea {
    private final String label;

    public ActionArea(Visual target, String label) {
        super(target);
        this.label = label;
    }

    public ActionArea(float x, float y, float width, float height, String label) {
        super(x, y, width, height);
        this.label = label;
    }

    public final String accessibleLabel() { return label; }

    public final boolean semanticVisible() {
        return exists && parent != null && parent.isVisible()
                && (target == this || target != null && target.isVisible());
    }

    public final void activate() {
        if (!semanticVisible() || !isActive()) throw new IllegalStateException("The action is not available");
        onActivate();
    }

    @Override protected final void onClick(PointerEvent event) { onActivate(); }

    protected abstract void onActivate();
}
