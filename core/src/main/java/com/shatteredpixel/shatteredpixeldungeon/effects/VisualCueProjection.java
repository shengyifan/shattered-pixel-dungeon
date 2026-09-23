package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.watabou.noosa.Visual;

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

    static boolean permitsScreenBounds(Rect bounds, Viewport camera, List<Rect> blockers) {
        if (bounds == null || camera == null || !Float.isFinite(bounds.left) || !Float.isFinite(bounds.top)
                || !Float.isFinite(bounds.right) || !Float.isFinite(bounds.bottom)
                || bounds.right <= bounds.left || bounds.bottom <= bounds.top) return false;
        if (bounds.left < camera.screenX || bounds.top < camera.screenY
                || bounds.right > camera.screenX + camera.width*camera.zoom
                || bounds.bottom > camera.screenY + camera.height*camera.zoom) return false;
        for (Rect blocker : blockers) if (bounds.overlaps(blocker)) return false;
        return true;
    }

    /** Geometry of the actual transformed quad, independently of camera or model coordinates. */
    static Rect worldBounds(Visual source) {
        if (source == null || source.scale == null || source.origin == null
                || !Float.isFinite(source.x) || !Float.isFinite(source.y)
                || !Float.isFinite(source.width) || !Float.isFinite(source.height)
                || !Float.isFinite(source.angle) || !Float.isFinite(source.scale.x) || !Float.isFinite(source.scale.y)
                || !Float.isFinite(source.origin.x) || !Float.isFinite(source.origin.y)) return null;
        float cosine = (float)Math.cos(Math.toRadians(source.angle));
        float sine = (float)Math.sin(Math.toRadians(source.angle));
        float left=Float.POSITIVE_INFINITY,top=Float.POSITIVE_INFINITY;
        float right=Float.NEGATIVE_INFINITY,bottom=Float.NEGATIVE_INFINITY;
        for (int corner=0;corner<4;corner++) {
            float x=(((corner&1)==0?0:source.width)-source.origin.x)*source.scale.x;
            float y=(((corner&2)==0?0:source.height)-source.origin.y)*source.scale.y;
            float transformedX=source.x+source.origin.x+x*cosine-y*sine;
            float transformedY=source.y+source.origin.y+x*sine+y*cosine;
            left=Math.min(left,transformedX);right=Math.max(right,transformedX);
            top=Math.min(top,transformedY);bottom=Math.max(bottom,transformedY);
        }
        if (!Float.isFinite(left)||!Float.isFinite(top)||!Float.isFinite(right)||!Float.isFinite(bottom)
                || right<=left||bottom<=top) return null;
        return new Rect(left,top,right,bottom);
    }

    /** Inverse of this draw's screen transform, including its committed camera displacement. */
    static Rect screenToWorldBounds(Rect bounds,Viewport camera) {
        if(bounds==null||camera==null||!Float.isFinite(camera.zoom)||camera.zoom<=0
                ||!Float.isFinite(camera.left)||!Float.isFinite(camera.top)
                ||!Float.isFinite(camera.screenX)||!Float.isFinite(camera.screenY))return null;
        float left=camera.left+(bounds.left-camera.screenX)/camera.zoom;
        float top=camera.top+(bounds.top-camera.screenY)/camera.zoom;
        float right=camera.left+(bounds.right-camera.screenX)/camera.zoom;
        float bottom=camera.top+(bounds.bottom-camera.screenY)/camera.zoom;
        if(!Float.isFinite(left)||!Float.isFinite(top)||!Float.isFinite(right)||!Float.isFinite(bottom)
                ||right<=left||bottom<=top)return null;
        return new Rect(left,top,right,bottom);
    }

    /** Every cell touched by the current drawn quad must be visible, separately from its semantic anchor. */
    static boolean permitsVisibleFootprint(Rect screenBounds,Viewport camera,float tileSize,
                                           int gridWidth,int gridLength,boolean[] visible) {
        Rect bounds=screenToWorldBounds(screenBounds,camera);
        if(bounds==null||!Float.isFinite(tileSize)||tileSize<=0||gridWidth<=0||gridLength<=0
                ||gridLength%gridWidth!=0||visible==null||visible.length<gridLength
                ||bounds.left<0||bounds.top<0
                ||bounds.right>(double)gridWidth*tileSize
                ||bounds.bottom>(double)(gridLength/gridWidth)*tileSize)return false;
        int firstX=(int)Math.floor(bounds.left/tileSize),firstY=(int)Math.floor(bounds.top/tileSize);
        // Right and bottom edges are exclusive: a quad ending at x=48 does not paint the next tile.
        int lastX=(int)Math.ceil(bounds.right/tileSize)-1,lastY=(int)Math.ceil(bounds.bottom/tileSize)-1;
        for(int y=firstY;y<=lastY;y++)for(int x=firstX;x<=lastX;x++)
            if(!visible[y*gridWidth+x])return false;
        return true;
    }

    static int gridCell(float left, float top, float width, float height, int tileSize, int gridWidth, int gridLength) {
        if (!Float.isFinite(left) || !Float.isFinite(top) || tileSize <= 0 || gridWidth <= 0
                || width != tileSize || height != tileSize || left < 0 || top < 0
                || left % tileSize != 0 || top % tileSize != 0) return -1;
        int column = (int)(left/tileSize), row = (int)(top/tileSize);
        if (column >= gridWidth || row >= gridLength/gridWidth) return -1;
        return row*gridWidth + column;
    }
}
