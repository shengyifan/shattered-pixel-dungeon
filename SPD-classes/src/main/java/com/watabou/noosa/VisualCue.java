package com.watabou.noosa;

import java.util.Objects;

/** Immutable semantic annotation of a grid visual that was drawn, not a simulation prediction. */
public final class VisualCue {
    public final String kind;
    public final int cell;

    public VisualCue(String kind, int cell) {
        this.kind = Objects.requireNonNull(kind);
        this.cell = cell;
    }

    @Override public boolean equals(Object other) {
        return other instanceof VisualCue && cell == ((VisualCue) other).cell && kind.equals(((VisualCue) other).kind);
    }

    @Override public int hashCode() { return 31 * kind.hashCode() + cell; }
}
