package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.effects.CircleArc;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Image;
import com.watabou.noosa.Scene;
import com.watabou.noosa.Group;
import com.watabou.noosa.ui.Component;
import com.watabou.noosa.Visual;
import com.watabou.utils.RectF;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Collections;

/** Reads existing visual properties without resolving camera caches or invoking model getters. */
public final class RenderedAppearance {
    private RenderedAppearance() {}
    private static final Set<String> OFFICIAL_ATLASES=officialAtlases();

    private static Set<String> officialAtlases() {
        Set<String> result=new TreeSet<>();
        // Only compile-time public resource constants, never a model or arbitrary cache key.
        for(Class<?> family:new Class<?>[]{Assets.Interfaces.class,Assets.Sprites.class,Assets.Effects.class})
            for(java.lang.reflect.Field field:family.getFields())if(field.getType()==String.class
                    &&java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {result.add((String)field.get(null));}
                catch(IllegalAccessException impossible){throw new IllegalStateException(impossible);}
            }
        return Collections.unmodifiableSet(result);
    }

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

    public static Map<String,Object> icon(Visual source,String atlas,int index) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("atlas",atlas);result.put("index",index);
        if(source.rm!=1||source.gm!=1||source.bm!=1||source.ra!=0||source.ga!=0||source.ba!=0) {
            Map<String,Object> tint=new LinkedHashMap<>();
            tint.put("multiply",Arrays.asList(source.rm,source.gm,source.bm));
            tint.put("add",Arrays.asList(source.ra,source.ga,source.ba));
            result.put("tint",tint);
        }
        return result;
    }

    /** Current pixels of an existing image; no callback, item getter, or actor identity lookup. */
    public static Map<String,Object> image(Visual source) {
        if(!(source instanceof Image) || !imageFullyVisible(source))return Collections.emptyMap();
        Image image=(Image)source;
        if(image.texture==null)return Collections.emptyMap();
        RectF frame=image.frame();
        if(!Float.isFinite(frame.left+frame.top+frame.right+frame.bottom))return Collections.emptyMap();
        Map<String,Object> result=new LinkedHashMap<>();
        String atlas=TextureCache.cachedAssetKey(image.texture,OFFICIAL_ATLASES);
        if(atlas==null) {
            result.put("asset_unknown",true);
            Map<String,Object> diagnostic=new LinkedHashMap<>();diagnostic.put("field","asset_unknown");diagnostic.put("code","asset_unknown");
            Map<String,Object> presentation=new LinkedHashMap<>();presentation.put("status","partial");presentation.put("diagnostics",Collections.singletonList(diagnostic));
            result.put("presentation",presentation);
        } else result.put("atlas",atlas);
        String symbol=Icons.displayedSymbol(image);
        if(symbol!=null)result.put("symbol",symbol);
        result.put("frame_pixels",Arrays.asList(frame.left*image.texture.width,frame.top*image.texture.height,
                frame.right*image.texture.width,frame.bottom*image.texture.height));
        result.put("texture_size",Arrays.asList(image.texture.width,image.texture.height));
        result.put("flip_horizontal",image.flipHorizontal);result.put("flip_vertical",image.flipVertical);
        result.put("angle",source.angle);result.put("scale",Arrays.asList(source.scale.x,source.scale.y));
        color(result,source);
        return result;
    }

    public static Map<String,Object> arc(CircleArc source) {
        if(source==null || source.origin.x!=0 || source.origin.y!=0)return Collections.emptyMap();
        float radius=source.radius()*Math.max(Math.abs(source.scale.x),Math.abs(source.scale.y));
        if(!visibleBounds(source,source.x-radius,source.y-radius,source.x+radius,source.y+radius))return Collections.emptyMap();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("sweep",source.getSweep());result.put("angle",source.angle);
        color(result,source);return result;
    }

    /** Stable meaning of an existing icon, distinct from its exact animation sample. */
    public static Map<String,Object> intentImage(Visual source,boolean frameMatters,boolean tintMatters) {
        Map<String,Object> result=new LinkedHashMap<>(image(source));
        for(String field:new String[]{"alpha","angle","scale","flip_horizontal","flip_vertical"})result.remove(field);
        if(!frameMatters)result.remove("frame_pixels");
        if(!tintMatters)result.remove("tint");
        return result;
    }

    private static void color(Map<String,Object> result,Visual source) {
        if(!Float.isFinite(source.rm+source.gm+source.bm+source.ra+source.ga+source.ba)) {
            result.put("color_unknown",true);return;
        }
        Map<String,Object> tint=new LinkedHashMap<>();
        tint.put("multiply",Arrays.asList(source.rm,source.gm,source.bm));
        tint.put("add",Arrays.asList(source.ra,source.ga,source.ba));
        result.put("tint",tint);result.put("alpha",source.alpha());
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
