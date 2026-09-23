package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedAppearance;
import com.shatteredpixel.shatteredpixeldungeon.ui.RightClickMenu;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.noosa.*;
import com.watabou.noosa.ui.Component;
import java.util.*;

/** Complete-frame measurements of drawn screen overlays and committed camera displacement. */
public final class ScreenEffectCollector {
    private final GameScene scene;
    private Level level;
    private String runId;
    private int depth;
    private Camera camera;
    private RuntimeObserver observer;
    private long cameraGeneration;
    private boolean collecting;
    private List<Gizmo> frameChildren=Collections.emptyList();
    private final List<Overlay> overlays=new ArrayList<>();
    private final Set<Image> invisibleOverlays=Collections.newSetFromMap(new IdentityHashMap<>());

    private static final class Overlay {
        final Image source;final VisualCueProjection.Rect bounds;final ScreenEffect effect;
        Overlay(Image source,VisualCueProjection.Rect bounds,ScreenEffect effect){this.source=source;this.bounds=bounds;this.effect=effect;}
    }

    public ScreenEffectCollector(GameScene scene){this.scene=scene;}

    public void beginDraw() {
        overlays.clear();invisibleOverlays.clear();level=Dungeon.level;runId=Dungeon.runId;depth=Dungeon.depth;
        camera=Camera.main;observer=Game.observer;frameChildren=scene.childrenSnapshot();
        cameraGeneration=camera==null?-1:camera.observedDrawGeneration();
        collecting=Game.instance!=null&&Game.scene()==scene&&level!=null&&runId!=null&&observer.observesVisualCues();
    }

    /** Invoked after the Fader's original draw, with its texture-bound color and actual blend mode. */
    public void overlayDrawn(Image source,Object episode,int solidArgb,boolean additive) {
        if(!collecting||!attached(source)||source.texture==null||source.alpha()<=0)return;
        Camera sourceCamera=RenderedAppearance.camera(source);
        if(sourceCamera==null||!sourceCamera.visible)return;
        Camera.DrawnTransform transform=sourceCamera.observedTransform();
        VisualCueProjection.Rect drawnBounds=screenBounds(source,transform);
        VisualCueProjection.Rect bounds=new VisualCueProjection.Rect(Math.max(0,transform.x),Math.max(0,transform.y),
                Math.min(Game.width,transform.x+transform.width*transform.zoom),Math.min(Game.height,transform.y+transform.height*transform.zoom));
        // PixelCamera may overdraw by a rounding pixel. The known solid must cover its whole clipped viewport.
        if(drawnBounds==null||!fullyOnSurface(bounds)||drawnBounds.left>bounds.left||drawnBounds.top>bounds.top
                ||drawnBounds.right<bounds.right||drawnBounds.bottom<bounds.bottom)return;
        Map<String,Object> fields=overlayFields(source,solidArgb,additive,bounds);
        if(fields==null) {
            if(Float.isFinite(source.rm+source.gm+source.bm+source.ra+source.ga+source.ba+source.am+source.aa))invisibleOverlays.add(source);
            return;
        }
        overlays.add(new Overlay(source,bounds,new ScreenEffect("screen_overlay",episode,fields)));
    }

    static Map<String,Object> overlayFields(Visual source,int solidArgb,boolean additive,VisualCueProjection.Rect bounds) {
        float alpha=clamp(((solidArgb>>>24)&255)/255f*source.am+source.aa);
        float red=clamp(((solidArgb>>>16)&255)/255f*source.rm+source.ra);
        float green=clamp(((solidArgb>>>8)&255)/255f*source.gm+source.ga);
        float blue=clamp((solidArgb&255)/255f*source.bm+source.ba);
        if(!Float.isFinite(alpha+red+green+blue)||alpha<=0||additive&&red==0&&green==0&&blue==0)return null;
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("color",Math.round(red*255)<<16|Math.round(green*255)<<8|Math.round(blue*255));
        fields.put("opacity",alpha);fields.put("blend",additive?"additive":"normal");
        fields.put("screen_rect",rectangle(bounds));return fields;
    }

    private static float clamp(float value){return Math.max(0,Math.min(1,value));}

    public void finishDraw() {
        if(!collecting)return;collecting=false;
        if(Game.instance==null||Game.scene()!=scene||Dungeon.level!=level||Dungeon.depth!=depth
                ||!runId.equals(Dungeon.runId)||Camera.main!=camera||Game.observer!=observer
                ||!observer.observesVisualCues()||!frameChildren.equals(scene.childrenSnapshot()))return;
        List<ScreenEffect> effects=new ArrayList<>();
        for(Overlay overlay:overlays)if(attached(overlay.source)&&uncoveredAfter(overlay.source,overlay.bounds))effects.add(overlay.effect);
        if(camera!=null&&camera.exists&&camera.visible&&camera.observedDrawGeneration()>cameraGeneration) {
            Camera.DrawnTransform transform=camera.observedTransform();
            if(Float.isFinite(transform.offsetX+transform.offsetY)&&(transform.offsetX!=0||transform.offsetY!=0)) {
                List<VisualCueProjection.Rect> blockers=new ArrayList<>();boolean modal=false;
                for(Gizmo child:frameChildren)if(visible(child)) {
                    if(child instanceof Window||child instanceof RightClickMenu){modal=true;break;}
                    collectUiBounds(child,blockers);
                }
                int reference=modal?-1:visibleReference(transform,blockers);
                if(reference>=0) {
                    Map<String,Object> fields=new LinkedHashMap<>();
                    fields.put("offset_pixels",Arrays.asList(transform.offsetX,transform.offsetY));
                    fields.put("viewport_pixels",Arrays.asList(transform.x,transform.y,
                            transform.x+transform.width*transform.zoom,transform.y+transform.height*transform.zoom));
                    fields.put("visible_reference_cell",reference);
                    effects.add(new ScreenEffect("camera_displacement",transform.episode,fields));
                }
            }
        }
        observer.onScreenEffects(runId,level,depth,Collections.unmodifiableList(effects));
    }

    private int visibleReference(Camera.DrawnTransform transform,List<VisualCueProjection.Rect> blockers) {
        VisualCueProjection.Viewport viewport=new VisualCueProjection.Viewport(transform.scrollX,transform.scrollY,
                transform.width,transform.height,transform.x,transform.y,transform.zoom);
        if(level.heroFOV==null)return -1;
        for(int cell=0;cell<Math.min(level.length(),level.heroFOV.length);cell++)
            if(VisualCueProjection.permits(cell,level.width(),level.length(),level.heroFOV,DungeonTilemap.SIZE,viewport,blockers))return cell;
        return -1;
    }

    private boolean uncoveredAfter(Gizmo source,VisualCueProjection.Rect bounds) {
        Gizmo top=source;while(top.parent!=null&&top.parent!=scene)top=top.parent;
        int index=frameChildren.indexOf(top);if(index<0)return false;
        for(int i=index+1;i<frameChildren.size();i++)if(overlaps(frameChildren.get(i),bounds))return false;
        return true;
    }

    private boolean overlaps(Gizmo candidate,VisualCueProjection.Rect bounds) {
        if(!visible(candidate)||invisibleOverlays.contains(candidate))return false;
        if(candidate instanceof Window||candidate instanceof RightClickMenu)return true;
        Camera own=RenderedAppearance.camera(candidate);
        VisualCueProjection.Rect rectangle=own==null?null:screenBounds(candidate,own.observedTransform());
        if(rectangle!=null&&rectangle.overlaps(bounds))return true;
        if(candidate instanceof Group)for(Gizmo child:((Group)candidate).childrenSnapshot())if(overlaps(child,bounds))return true;
        return false;
    }

    private void collectUiBounds(Gizmo source,List<VisualCueProjection.Rect> rectangles) {
        if(!visible(source)||invisibleOverlays.contains(source))return;
        Camera own=RenderedAppearance.camera(source);
        if(own!=null&&own!=camera) {
            VisualCueProjection.Rect bounds=screenBounds(source,own.observedTransform());
            if(bounds!=null){rectangles.add(bounds);return;}
        }
        if(source instanceof Group)for(Gizmo child:((Group)source).childrenSnapshot())collectUiBounds(child,rectangles);
    }

    private boolean attached(Gizmo source) {
        for(Gizmo current=source;current!=null;current=current.parent){if(!visible(current))return false;if(current==scene)return true;}
        return false;
    }
    private static boolean visible(Gizmo source) {
        return source!=null&&source.exists&&source.alive&&source.visible
                &&(!(source instanceof Visual)||Float.isFinite(((Visual)source).alpha())&&((Visual)source).alpha()>0);
    }
    private static boolean fullyOnSurface(VisualCueProjection.Rect bounds) {
        return Float.isFinite(bounds.left+bounds.top+bounds.right+bounds.bottom)&&bounds.right>bounds.left&&bounds.bottom>bounds.top
                &&bounds.left>=0&&bounds.top>=0&&bounds.right<=Game.width&&bounds.bottom<=Game.height;
    }
    private static List<Float> rectangle(VisualCueProjection.Rect bounds){return Arrays.asList(bounds.left,bounds.top,bounds.right,bounds.bottom);}
    private static VisualCueProjection.Rect screenBounds(Gizmo source,Camera.DrawnTransform transform) {
        if(transform==null||!Float.isFinite(transform.zoom)||transform.zoom<=0)return null;
        VisualCueProjection.Rect world;
        if(source instanceof Visual){if(!visible(source))return null;world=VisualCueProjection.worldBounds((Visual)source);}
        else if(source instanceof Component){Component component=(Component)source;world=new VisualCueProjection.Rect(component.left(),component.top(),component.right(),component.bottom());}
        else return null;
        if(world==null)return null;
        return new VisualCueProjection.Rect(transform.worldToScreenX(world.left),transform.worldToScreenY(world.top),
                transform.worldToScreenX(world.right),transform.worldToScreenY(world.bottom));
    }
}
