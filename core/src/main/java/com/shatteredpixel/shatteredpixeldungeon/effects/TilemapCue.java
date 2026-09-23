package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.Tilemap;

/** Marks an existing visual layer; actual nonzero quads, clipping and attachment decide publication. */
public final class TilemapCue implements Tilemap.DrawObserver {
    private final String kind;
    public TilemapCue(String kind) { this.kind = kind; }
    @Override public void observeState(Tilemap source) {
        if (Game.observer.observesVisualCues()) GameScene.observeTilemapDraw(source, kind);
    }
}
