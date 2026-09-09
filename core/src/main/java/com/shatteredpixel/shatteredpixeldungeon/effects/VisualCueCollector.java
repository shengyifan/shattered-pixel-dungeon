package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GooSprite;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.RightClickMenu;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Image;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.particles.Emitter;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects only whitelisted visuals submitted by their actual draw paths. No AI fields are inspected. */
public final class VisualCueCollector {
    private final GameScene scene;
    private Level level;
    private String runId;
    private int depth;
    private boolean collecting;
    private final Map<String, VisualCue> offered = new LinkedHashMap<>();
    private final List<GooObservation> gooObservations = new ArrayList<>();
    private List<VisualCue> lastPublished = Collections.emptyList();

    private static final class GooObservation {
        final Emitter source;
        final int cell;
        final GooSprite.GooDrawObserver observation;
        final boolean drawable;
        GooObservation(Emitter source, int cell, GooSprite.GooDrawObserver observation, boolean drawable) {
            this.source = source; this.cell = cell; this.observation = observation; this.drawable = drawable;
        }
    }

    public VisualCueCollector(GameScene scene) { this.scene = scene; }

    public void beginDraw() {
        offered.clear();
        gooObservations.clear();
        level = Dungeon.level; runId = Dungeon.runId; depth = Dungeon.depth;
        collecting = Game.scene() == scene && level != null && runId != null && Game.observer.observesVisualCues();
    }

    public void targetDrawn(Visual source, int cell) { offer(source, "red_target", cell); }

    public void gooEmitterDrawn(Emitter emitter, int cell, GooSprite.GooDrawObserver observation) {
        if (!collecting || !emitter.isEmitting(GooSprite.GooParticle.FACTORY) || !attached(emitter)) return;
        // on/factory alone are not evidence that a particle has appeared. Group.draw has
        // already applied the same exists/isVisible gate to these actual children.
        for (Gizmo child : emitter.childrenSnapshot()) {
            if (child instanceof GooSprite.GooParticle && drawable((Visual) child)) {
                offer((Visual) child, "black_goo_droplets", cell);
                gooObservations.add(new GooObservation(emitter, cell, observation, true));
                return;
            }
        }
        gooObservations.add(new GooObservation(emitter, cell, observation, false));
    }

    private void offer(Visual source, String kind, int cell) {
        if (!collecting || !drawable(source) || resolvedCamera(source) != Camera.main) return;
        String key = kind + ":" + cell;
        if (!offered.containsKey(key)) offered.put(key, new VisualCue(kind, cell));
    }

    private boolean drawable(Visual source) {
        return source.exists && source.alive && source.visible && source.isVisible() && attached(source)
                && source.am + source.aa > 0 && source.width() > 0 && source.height() > 0
                && (!(source instanceof Image) || ((Image) source).texture != null);
    }

    private boolean attached(Gizmo source) {
        for (Gizmo current = source; current != null; current = current.parent) {
            if (!current.exists || !current.alive || !current.visible) return false;
            if (current == scene) return true;
        }
        return false;
    }

    public void finishDraw() {
        if (!collecting) return;
        collecting = false;
        if (Game.scene() != scene || Dungeon.level != level || !runId.equals(Dungeon.runId)
                || !Game.observer.observesVisualCues()) return;
        List<VisualCue> visible = new ArrayList<>();
        List<VisualCueProjection.Rect> blockers = new ArrayList<>();
        boolean modal = false;
        for (Gizmo child : scene.childrenSnapshot()) {
            if (child == null || !child.exists || !child.visible || !child.isVisible()) continue;
            if (child instanceof Window || child instanceof RightClickMenu) { modal = true; break; }
            Camera uiCamera = resolvedCamera(child);
            if (uiCamera != PixelScene.uiCamera) continue;
            VisualCueProjection.Rect rectangle = screenBounds(child, uiCamera);
            if (rectangle != null) blockers.add(rectangle);
        }
        Camera camera = Camera.main;
        VisualCueProjection.Viewport viewport = null;
        if (!modal && camera != null && camera.visible) {
            viewport = new VisualCueProjection.Viewport(camera.scroll.x, camera.scroll.y,
                    camera.width, camera.height, camera.x, camera.y, camera.zoom);
            for (VisualCue cue : offered.values()) {
                if (VisualCueProjection.permits(cue.cell, level.width(), level.length(), level.heroFOV,
                        DungeonTilemap.SIZE, viewport, blockers)) visible.add(cue);
            }
        }
        boolean initialVisualsReady = true;
        for (GooObservation observed : gooObservations) {
            boolean eligible = viewport != null && attached(observed.source)
                    && observed.source.isEmitting(GooSprite.GooParticle.FACTORY)
                    && resolvedCamera(observed.source) == Camera.main
                    && VisualCueProjection.permits(observed.cell, level.width(), level.length(), level.heroFOV,
                            DungeonTilemap.SIZE, viewport, blockers);
            // Hidden, occluded, stopped, inactive or frozen sources cannot postpone an input.
            // Once the first actual visible particle was drawn, normal particle flicker never waits again.
            initialVisualsReady &= observed.observation.recordVisibleFrame(eligible, observed.drawable)
                    || !observed.source.canProgress();
        }
        visible.sort(Comparator.comparing((VisualCue cue) -> cue.kind).thenComparingInt(cue -> cue.cell));
        if (!visible.equals(lastPublished)) lastPublished = Collections.unmodifiableList(visible);
        // Empty and unchanged complete draws still acknowledge a render generation. The
        // observer handles public event deduplication and never receives filtered counts.
        Game.observer.onVisualCues(runId, level, depth, lastPublished, initialVisualsReady);
    }

    private static Camera resolvedCamera(Gizmo gizmo) {
        for (Gizmo current = gizmo; current != null; current = current.parent) if (current.camera != null) return current.camera;
        return null;
    }

    private static VisualCueProjection.Rect screenBounds(Gizmo gizmo, Camera camera) {
        float left, top, right, bottom;
        if (gizmo instanceof Component) {
            Component component = (Component) gizmo;
            left = component.left(); top = component.top();
            right = component.right(); bottom = component.bottom();
        } else if (gizmo instanceof Visual) {
            Visual visual = (Visual) gizmo;
            if (visual.am + visual.aa <= 0) return null;
            float cosine = (float) Math.cos(Math.toRadians(visual.angle));
            float sine = (float) Math.sin(Math.toRadians(visual.angle));
            left = top = Float.POSITIVE_INFINITY; right = bottom = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                float x = ((corner & 1) == 0 ? 0 : visual.width) - visual.origin.x;
                float y = ((corner & 2) == 0 ? 0 : visual.height) - visual.origin.y;
                x *= visual.scale.x; y *= visual.scale.y;
                float transformedX = visual.x + visual.origin.x + x*cosine - y*sine;
                float transformedY = visual.y + visual.origin.y + x*sine + y*cosine;
                left = Math.min(left, transformedX); top = Math.min(top, transformedY);
                right = Math.max(right, transformedX); bottom = Math.max(bottom, transformedY);
            }
        } else return null;
        if (!Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right) || !Float.isFinite(bottom))
            return new VisualCueProjection.Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        if (right <= left || bottom <= top) return null;
        return new VisualCueProjection.Rect(camera.x + (left-camera.scroll.x)*camera.zoom,
                camera.y + (top-camera.scroll.y)*camera.zoom, camera.x + (right-camera.scroll.x)*camera.zoom,
                camera.y + (bottom-camera.scroll.y)*camera.zoom);
    }
}
