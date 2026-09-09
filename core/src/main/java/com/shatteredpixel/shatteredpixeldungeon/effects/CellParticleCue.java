package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Visual;
import com.watabou.noosa.particles.Emitter;

/** A visual-source annotation; the renderer still determines whether a particle was actually drawn. */
public class CellParticleCue implements Emitter.DrawObserver {
    public final String kind;
    public final int cell;
    private final Emitter.Factory factory;
    private final Class<? extends Visual> particleType;
    private boolean observed;

    public CellParticleCue(String kind, int cell, Emitter.Factory factory, Class<? extends Visual> particleType) {
        this.kind = kind; this.cell = cell; this.factory = factory; this.particleType = particleType;
    }

    public boolean emitting(Emitter source) { return source.isEmitting(factory); }
    public boolean matches(Gizmo child) { return particleType.isInstance(child); }
    @Override public void resetObservation() { observed = false; }
    public boolean recordVisibleFrame(boolean eligible, boolean hasDrawable) {
        if (!eligible) return true;
        if (hasDrawable) observed = true;
        return observed;
    }
    @Override public void afterDraw(Emitter emitter) {
        if (Game.observer.observesVisualCues()) GameScene.observeCellParticleDraw(emitter, this);
    }
}
