package com.shatteredpixel.shatteredpixeldungeon.effects;

import java.util.List;

/** Pure conservative geometry used by the draw observer; it never discovers a map or predicts an attack. */
final class VisualCueProjection {
    static final class Rect {
        final float left, top, right, bottom;
        Rect(float left, float top, float right, float bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        boolean overlaps(Rect other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
    }

    static final class Viewport {
        final float left, top, width, height, screenX, screenY, zoom;
        Viewport(float left, float top, float width, float height, float screenX, float screenY, float zoom) {
            this.left = left; this.top = top; this.width = width; this.height = height;
            this.screenX = screenX; this.screenY = screenY; this.zoom = zoom;
        }
        Rect screenRect(float x, float y, float w, float h) {
            return new Rect(screenX + (x-left)*zoom, screenY + (y-top)*zoom,
                    screenX + (x+w-left)*zoom, screenY + (y+h-top)*zoom);
        }
    }

    static boolean permits(int cell, int gridWidth, int gridLength, boolean[] visible,
                           float tileSize, Viewport camera, List<Rect> blockers) {
        if (cell < 0 || cell >= gridLength || gridWidth <= 0 || visible == null
                || cell >= visible.length || !visible[cell] || camera == null
                || camera.zoom <= 0 || camera.width <= 0 || camera.height <= 0
                || !Float.isFinite(camera.left) || !Float.isFinite(camera.top)
                || !Float.isFinite(camera.width) || !Float.isFinite(camera.height)
                || !Float.isFinite(camera.screenX) || !Float.isFinite(camera.screenY)
                || !Float.isFinite(camera.zoom)) return false;
        float left = (cell % gridWidth)*tileSize, top = (cell/gridWidth)*tileSize;
        // A partially clipped tile is intentionally omitted. No hidden portion is reconstructed.
        if (left < camera.left || top < camera.top || left+tileSize > camera.left+camera.width
                || top+tileSize > camera.top+camera.height) return false;
        Rect cellScreen = camera.screenRect(left, top, tileSize, tileSize);
        for (Rect blocker : blockers) if (cellScreen.overlaps(blocker)) return false;
        return true;
    }
}
