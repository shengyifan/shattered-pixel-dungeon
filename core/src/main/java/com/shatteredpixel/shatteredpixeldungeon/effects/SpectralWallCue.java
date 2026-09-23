package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.SpectralWallParticle;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.particles.Emitter;

/** Explicit KeyWall presentation source. Reads neither the owning blob nor any configured duration. */
public final class SpectralWallCue implements Emitter.DrawObserver {
    private final Emitter source;
    private final long lifetime;
    private final Object episode;
    public SpectralWallCue(Emitter source){
        this.source=source;this.lifetime=source.observationLifetime();this.episode=source.observedDrawEpisode();
    }
    @Override public void observeState(Emitter emitter){
        if(!Game.observer.observesVisualCues()||Dungeon.level==null||emitter!=source
                ||emitter.observationLifetime()!=lifetime||emitter.observedDrawEpisode()!=episode
                ||!emitter.isEmitting(SpectralWallParticle.FACTORY))return;
        for(Gizmo child:emitter.childrenSnapshot())if(child instanceof SpectralWallParticle){
            SpectralWallParticle particle=(SpectralWallParticle)child;
            int cell=particle.observedWallCell(Dungeon.level.width(),Dungeon.level.length());
            if(cell>=0)GameScene.observeSpectralWall(emitter,particle,cell);
        }
    }
}
