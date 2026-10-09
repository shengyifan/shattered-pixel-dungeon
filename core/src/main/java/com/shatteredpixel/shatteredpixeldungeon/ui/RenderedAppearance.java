package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.shatteredpixel.shatteredpixeldungeon.effects.CircleArc;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Image;
import com.watabou.noosa.Scene;
import com.watabou.noosa.Group;
import com.watabou.noosa.ui.Component;
import com.watabou.noosa.Visual;
import com.watabou.utils.RectF;

/** Reads existing visual properties without resolving camera caches or invoking model getters. */
public final class RenderedAppearance {
    private RenderedAppearance() {}
    public static Camera camera(Gizmo source) {
        for (Gizmo node=source; node!=null; node=node.parent) if(node.camera!=null) return node.camera;
        return null;
    }

    public static boolean fullyVisible(Visual source) {
        if (source==null || !Float.isFinite(source.alpha()) || source.alpha()<=0) return false;
        for (Gizmo node=source;node!=null;node=node.parent) if(!node.exists||!node.visible) return false;
        Camera camera=camera(source);
        Camera.DrawnTransform transform=camera==null?null:camera.observedTransform();
        float width=source.width(),height=source.height();
        return camera!=null&&camera.visible&&camera.scroll!=null&&width>0&&height>0
                &&Float.isFinite(source.x+source.y+width+height)&&source.angle==0
                &&source.origin.x==0&&source.origin.y==0
                &&transform!=null&&source.x>=transform.scrollX&&source.y>=transform.scrollY
                &&source.x+width<=transform.scrollX+transform.width&&source.y+height<=transform.scrollY+transform.height;
    }

    /** Whether an existing native image passes the complete public visibility gate. */
    public static boolean isImageVisible(Visual source) {
        if(!(source instanceof Image) || !imageFullyVisible(source))return false;
        Image image=(Image)source;
        if(image.texture==null)return false;
        RectF frame=image.frame();
        return Float.isFinite(frame.left+frame.top+frame.right+frame.bottom);
    }

    /** The currently visible native arc sweep; null means no complete public sample. */
    public static Float visibleSweep(CircleArc source) {
        if(source==null || source.origin.x!=0 || source.origin.y!=0)return null;
        float radius=source.radius()*Math.max(Math.abs(source.scale.x),Math.abs(source.scale.y));
        if(!visibleBounds(source,source.x-radius,source.y-radius,source.x+radius,source.y+radius))return null;
        return source.getSweep();
    }

    private static boolean imageFullyVisible(Visual source) {
        float[] bounds=visualBounds(source);
        return bounds!=null&&visibleBounds(source,bounds[0],bounds[1],bounds[2],bounds[3]);
    }

    private static float[] visualBounds(Visual source) {
        if(!Float.isFinite(source.angle+source.scale.x+source.scale.y+source.origin.x+source.origin.y))return null;
        double cosine=Math.cos(Math.toRadians(source.angle)),sine=Math.sin(Math.toRadians(source.angle));
        float left=Float.POSITIVE_INFINITY,top=Float.POSITIVE_INFINITY,right=Float.NEGATIVE_INFINITY,bottom=Float.NEGATIVE_INFINITY;
        for(float x:new float[]{0,source.width})for(float y:new float[]{0,source.height}) {
            float dx=(x-source.origin.x)*source.scale.x,dy=(y-source.origin.y)*source.scale.y;
            float px=source.x+source.origin.x+(float)(dx*cosine-dy*sine),py=source.y+source.origin.y+(float)(dx*sine+dy*cosine);
            left=Math.min(left,px);right=Math.max(right,px);top=Math.min(top,py);bottom=Math.max(bottom,py);
        }
        return new float[]{left,top,right,bottom};
    }

    /** Conservative GUI overlay gate for a native combat banner; the source never blocks itself. */
    public static boolean uncoveredInScene(Visual source,Scene scene) {
        if(scene==null||!imageFullyVisible(source))return false;
        boolean attached=false;for(Gizmo node=source;node!=null;node=node.parent)if(node==scene)attached=true;
        if(!attached)return false;
        float[] bounds=screenBounds(source);
        if(bounds==null)return false;
        for(Gizmo child:scene.childrenSnapshot())if(blocksBanner(child,source,bounds))return false;
        return true;
    }

    private static boolean blocksBanner(Gizmo candidate,Visual source,float[] sourceBounds) {
        if(candidate==source||candidate==null||!candidate.exists||!candidate.visible)return false;
        if(candidate instanceof Window||candidate instanceof RightClickMenu)return true;
        Camera camera=camera(candidate);
        if(camera!=null&&camera.visible&&camera!=Camera.main) {
            float[] bounds=screenBounds(candidate);
            if(bounds!=null&&bounds[0]<sourceBounds[2]&&bounds[2]>sourceBounds[0]
                    &&bounds[1]<sourceBounds[3]&&bounds[3]>sourceBounds[1])return true;
        }
        if(candidate instanceof Group)for(Gizmo child:((Group)candidate).childrenSnapshot())
            if(blocksBanner(child,source,sourceBounds))return true;
        return false;
    }

    private static float[] screenBounds(Gizmo source) {
        Camera camera=camera(source);if(camera==null||camera.scroll==null||!camera.visible)return null;
        float[] bounds;
        if(source instanceof Visual) {
            Visual visual=(Visual)source;if(!Float.isFinite(visual.alpha())||visual.alpha()<=0)return null;
            bounds=visualBounds(visual);
        } else if(source instanceof Component) {
            Component component=(Component)source;
            bounds=new float[]{component.left(),component.top(),component.right(),component.bottom()};
        } else return null;
        if(bounds==null||bounds[2]<=bounds[0]||bounds[3]<=bounds[1])return null;
        Camera.DrawnTransform transform=camera.observedTransform();
        for(int i=0;i<4;i++)bounds[i]=i%2==0?transform.worldToScreenX(bounds[i]):transform.worldToScreenY(bounds[i]);
        return bounds;
    }

    private static boolean visibleBounds(Visual source,float left,float top,float right,float bottom) {
        if(!Float.isFinite(source.alpha())||source.alpha()<=0||!Float.isFinite(left+top+right+bottom)||right<=left||bottom<=top)return false;
        for(Gizmo node=source;node!=null;node=node.parent)if(!node.exists||!node.visible)return false;
        Camera camera=camera(source);
        if(camera==null||!camera.visible||camera.scroll==null)return false;
        Camera.DrawnTransform transform=camera.observedTransform();
        return left>=transform.scrollX&&top>=transform.scrollY&&right<=transform.scrollX+transform.width&&bottom<=transform.scrollY+transform.height;
    }
}
