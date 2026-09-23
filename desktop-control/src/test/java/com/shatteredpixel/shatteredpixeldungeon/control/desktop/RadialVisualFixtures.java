package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SuperNovaTracker;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfBlastWave;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.utils.PointF;

import java.util.Arrays;

/** Native draw fixtures only; no synthetic visual events or personal profile access. */
final class RadialVisualFixtures {
    static boolean supports(String name) {
        return Arrays.asList("supernova-halo", "supernova-halo-edge", "blast-radius-1", "blast-radius-3", "blast-radius-6").contains(name);
    }

    static void prepare(String name, Hero hero) {
        if (name.startsWith("blast-radius-")) {
            WandOfBlastWave.BlastWave.blast(hero.pos + 1, Float.parseFloat(name.substring("blast-radius-".length())));
            return;
        }
        SuperNovaTracker tracker = new SuperNovaTracker();
        tracker.pos = hero.pos + 1;
        if (name.equals("supernova-halo")) {
            // One native tick creates the halo, temporary countdown text and targeted cells.
            tracker.attachTo(hero);
            tracker.act();
        } else if (name.equals("supernova-halo-edge")) {
            // Explicit renderer-boundary fixture, not a claimed ordinary countdown state.
            SuperNovaTracker.NovaVFX halo = tracker.new NovaVFX() {
                @Override public void update() { super.update(); am = -1; aa = 1; }
            };
            halo.radius(12); halo.hardlight(1, 1, 0); halo.am = -1; halo.aa = 1;
            int edgeAlpha = halo.texture.getPixel(255, 128) >>> 24;
            if (edgeAlpha <= 0 || edgeAlpha >= 255 || !halo.renderedHaloContributes())
                throw new IllegalStateException("Native halo gradient must retain a visible inverted-alpha edge");
            PointF center = DungeonTilemap.raisedTileCenterToWorld(tracker.pos);
            halo.point(center.x, center.y);
            GameScene.effect(halo);
        } else throw new IllegalArgumentException("Unknown radial visual fixture");
    }
}
