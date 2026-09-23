package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.noosa.Image;
import com.watabou.noosa.VisualCue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Current radial image geometry only. Its caller must have submitted the native draw. */
public final class RadialVisualCue {
    private RadialVisualCue() {}

    public static VisualCue capture(Image source, String kind, String shape, float textureRadius) {
        if (Dungeon.level == null || source == null || !Float.isFinite(textureRadius) || textureRadius <= 0
                || !Float.isFinite(source.rm + source.gm + source.bm + source.ra + source.ga + source.ba)
                || !Float.isFinite(source.am) || !Float.isFinite(source.aa)) return null;
        VisualCueProjection.Rect bounds = VisualCueProjection.worldBounds(source);
        if (bounds == null) return null;
        // Geometry is already in world coordinates, so this identity projection checks
        // every painted cell without consulting an attack's configured coverage.
        VisualCueProjection.Viewport world = new VisualCueProjection.Viewport(0, 0,
                Dungeon.level.width() * DungeonTilemap.SIZE, Dungeon.level.height() * DungeonTilemap.SIZE, 0, 0, 1);
        if (!VisualCueProjection.permitsVisibleFootprint(bounds, world, DungeonTilemap.SIZE,
                Dungeon.level.width(), Dungeon.level.length(), Dungeon.level.heroFOV)) return null;
        float centerX = (bounds.left + bounds.right) / 2f, centerY = (bounds.top + bounds.bottom) / 2f;
        if (!Float.isFinite(centerX) || !Float.isFinite(centerY) || centerX < 0 || centerY < 0) return null;
        int column = (int)(centerX / DungeonTilemap.SIZE), row = (int)(centerY / DungeonTilemap.SIZE);
        if (column >= Dungeon.level.width() || row >= Dungeon.level.height()) return null;
        float width = Math.abs(source.width * source.scale.x), height = Math.abs(source.height * source.scale.y);
        float radiusX = textureRadius * Math.abs(source.scale.x), radiusY = textureRadius * Math.abs(source.scale.y);
        if (!Float.isFinite(width + height + radiusX + radiusY) || width <= 0 || height <= 0) return null;
        Map<String,Object> appearance = new LinkedHashMap<>();
        appearance.put("shape", shape);
        appearance.put("center_world", Arrays.asList(centerX, centerY));
        appearance.put("size", Arrays.asList(width, height));
        appearance.put("radius_world", Arrays.asList(radiusX, radiusY));
        appearance.put("scale", Arrays.asList(source.scale.x, source.scale.y));
        appearance.put("angle", source.angle);
        // Texture alpha * multiply + add is the actual shader expression. A single
        // white-pixel alpha loses visible halo edges when multiply is negative.
        appearance.put("alpha_transform", Arrays.asList(source.am, source.aa));
        return new VisualCue(kind, row * Dungeon.level.width() + column, null, null,
                source.displayedTextColor(), null, appearance);
    }
}
