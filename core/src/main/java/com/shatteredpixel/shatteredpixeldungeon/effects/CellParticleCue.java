package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Visual;
import com.watabou.noosa.particles.Emitter;
import java.util.IdentityHashMap;
import java.util.Map;

/** A visual-source annotation; the renderer still determines whether a particle was actually drawn. */
public class CellParticleCue implements Emitter.DrawObserver {
    public final String kind;
    public final int cell;
    private final Emitter.Factory factory;
    private final Class<? extends Visual> particleType;
    private boolean observed;
    private Object episode;
    private Emitter observedSource;
    private long lifetime;
    private boolean finiteSignal, measuredCount;
    private Emitter boundSource;
    private Object boundEpisode;
    private long boundLifetime;
    private Map<Gizmo,Long> precedingContributors;

    public CellParticleCue(String kind, int cell, Emitter.Factory factory, Class<? extends Visual> particleType) {
        this.kind = kind; this.cell = cell; this.factory = factory; this.particleType = particleType;
    }

    /** A moving signal follows its already-present sprite, without consulting the sprite's actor. */
    public static CellParticleCue forCharacter(String kind,
            com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite sprite,
            Emitter.Factory factory, Class<? extends Visual> particleType) {
        final long spriteLifetime = sprite == null ? -1 : sprite.observationLifetime();
        return new CellParticleCue(kind, -1, factory, particleType) {
            @Override public int observedCell() {
                return sprite == null || !sprite.exists || !sprite.alive || !sprite.visible
                        || sprite.observationLifetime() != spriteLifetime ? -1 : sprite.renderedCell();
            }
        };
    }
    public int observedCell() { return cell; }

    static Map<Gizmo,Long> existingContributors(Emitter source) {
        Map<Gizmo,Long> result=new IdentityHashMap<>();
        for(Gizmo child:source.childrenSnapshot())if(child!=null&&child.exists&&child.alive)
            result.put(child,child.observationLifetime());
        return result;
    }
    static CellParticleCue observedSignal(String kind,int cell,
            com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite sprite,Emitter source,
            Emitter.Factory factory,boolean measured,Map<Gizmo,Long> preceding) {
        CellParticleCue result=sprite==null?new CellParticleCue(kind,cell,factory,Visual.class)
                :forCharacter(kind,sprite,factory,Visual.class);
        result.finiteSignal=true;result.measuredCount=measured;result.boundSource=source;
        result.boundEpisode=source.observedDrawEpisode();result.boundLifetime=source.observationLifetime();
        result.precedingContributors=new IdentityHashMap<>(preceding);
        return result;
    }
    boolean acceptsSource(Emitter source) {
        return !finiteSignal||(source==boundSource&&source.observationLifetime()==boundLifetime
                &&source.observedDrawEpisode()==boundEpisode);
    }
    boolean measuredCount() { return measuredCount; }
    boolean retainActiveEpisode() { return !finiteSignal; }

    public boolean emitting(Emitter source) { return source.isEmitting(factory); }
    public boolean matches(Gizmo child) {
        if(!particleType.isInstance(child))return false;
        Long previous=precedingContributors==null?null:precedingContributors.get(child);
        return previous==null||previous.longValue()!=child.observationLifetime();
    }
    @Override public void resetObservation() { observed = false; episode=null; observedSource=null; }
    public void beginObservation(Emitter source) {
        if(observedSource!=source||lifetime!=source.observationLifetime()||episode!=source.observedDrawEpisode()){
            observed=false;observedSource=source;lifetime=source.observationLifetime();episode=source.observedDrawEpisode();
        }
    }
    public boolean hasObservedPresentation() { return observed; }
    public boolean recordVisibleFrame(boolean eligible, boolean hasDrawable) {
        if (!eligible) return true;
        if (hasDrawable) observed = true;
        return observed;
    }
    @Override public void observeState(Emitter emitter) {
        if (Game.observer.observesVisualCues()) GameScene.observeCellParticleDraw(emitter, this);
    }
}
