package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.particles.Emitter;

/** Measures only already drawn particle motion. It never follows an attraction target or an actor. */
public final class ParticleMotionCue implements Emitter.DrawObserver {
    /** Position where this particular particle began its visible motion, cleared on reuse. */
    public interface AnchoredMotion {
        float observedOriginX();
        float observedOriginY();
    }
    private final String kind;
    private final Class<? extends Visual> particleType;

    public ParticleMotionCue(String kind, Class<? extends Visual> particleType) {
        this.kind = kind;
        this.particleType = particleType;
    }

    @Override public void afterDraw(Emitter source) {
        if (!Game.observer.observesVisualCues() || Dungeon.level == null) return;
        for (Gizmo child : source.childrenSnapshot()) {
            if (!particleType.isInstance(child)) continue;
            Visual particle = (Visual) child;
            if (!(particle instanceof AnchoredMotion)) continue;
            AnchoredMotion anchor = (AnchoredMotion)particle;
            float originX = anchor.observedOriginX(), originY = anchor.observedOriginY();
            if (!Float.isFinite(originX) || !Float.isFinite(originY)
                    || originX < 0 || originY < 0) continue;
            int column = (int)(originX / DungeonTilemap.SIZE);
            int row = (int)(originY / DungeonTilemap.SIZE);
            if (column >= Dungeon.level.width() || row >= Dungeon.level.height()) continue;
            GameScene.observeMovingVisualDraw(particle,
                    new VisualCue(kind, row * Dungeon.level.width() + column));
        }
    }

    /** Screen/world y increases southwards; no endpoint or off-screen destination is returned. */
    public static String direction(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || x == 0 && y == 0) return null;
        String[] directions = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
        int sector = (int)Math.round(Math.atan2(y, x) / (Math.PI / 4));
        return directions[(sector + 8) % 8];
    }
}
