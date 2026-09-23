package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.RightClickMenu;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedAppearance;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Image;
import com.watabou.noosa.Tilemap;
import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.VisualMetric;
import com.watabou.noosa.PseudoPixel;
import com.watabou.noosa.particles.Emitter;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;

/** Collects only whitelisted visuals submitted by their actual draw paths. No AI fields are inspected. */
public final class VisualCueCollector {
    private final GameScene scene;
    private Level level;
    private String runId;
    private int depth;
    private boolean collecting;
    private Camera frameCamera;
    private com.watabou.noosa.RuntimeObserver frameObserver;
    private final Map<String, VisualCue> offered = new LinkedHashMap<>();
    private final List<ParticleObservation> particleObservations = new ArrayList<>();
    private final Map<String, VisualCueProjection.Rect> textBounds = new LinkedHashMap<>();
    private final Map<String, List<List<VisualCueProjection.Rect>>> cueBounds = new LinkedHashMap<>();
    private final List<MovingObservation> movingObservations = new ArrayList<>();
    private Map<Visual,MotionSample> previousMotion = new IdentityHashMap<>();
    private Level previousMotionLevel;

    private static final class MotionSample {
        final long lifetime;
        final float x, y;
        MotionSample(Visual source, float x, float y) { this.lifetime=source.renderedLifetime(); this.x=x; this.y=y; }
    }

    private static final class MovingObservation {
        final Visual source;
        final VisualCue cue;
        final VisualCueProjection.Rect bounds;
        final MotionSample sample;
        MovingObservation(Visual source, VisualCue cue, VisualCueProjection.Rect bounds, MotionSample sample) {
            this.source=source; this.cue=cue; this.bounds=bounds; this.sample=sample;
        }
    }
    private final List<FloatingText> drawnTexts = new ArrayList<>();
    private List<VisualCue> lastPublished = Collections.emptyList();
    private final Map<Visual,Map<String,ParticleMetric>> drawnMetrics = new IdentityHashMap<>();
    private Set<Object> visibleMetricEpisodes = Collections.emptySet();
    private static final class ParticleMetric {
        final String kind;final int cell;final VisualCueProjection.Rect bounds;
        final Object episode;final Map<String,Object> appearance;
        ParticleMetric(String kind,int cell,VisualCueProjection.Rect bounds,Object episode,Map<String,Object> appearance){
            this.kind=kind;this.cell=cell;this.bounds=bounds;this.episode=episode;this.appearance=appearance;
        }
    }

    private static final class ParticleObservation {
        final Emitter source;
        final int cell;
        final CellParticleCue observation;
        final List<ParticleContributor> contributors;
        ParticleObservation(Emitter source, int cell, CellParticleCue observation, List<ParticleContributor> contributors) {
            this.source = source; this.cell = cell; this.observation = observation; this.contributors = contributors;
        }
    }
    private static final class ParticleContributor {
        final Visual source;
        final VisualCueProjection.Rect bounds;
        final boolean drawn;
        ParticleContributor(Visual source, VisualCueProjection.Rect bounds, boolean drawn) {
            this.source = source; this.bounds = bounds; this.drawn = drawn;
        }
    }

    public VisualCueCollector(GameScene scene) { this.scene = scene; }

    public void beginDraw() {
        for(FloatingText previous:drawnTexts)previous.clearDisplayedText();
        drawnTexts.clear();
        offered.clear();
        particleObservations.clear();
        textBounds.clear();
        cueBounds.clear();
        movingObservations.clear();
        drawnMetrics.clear();
        level = Dungeon.level; runId = Dungeon.runId; depth = Dungeon.depth;
        frameCamera=Camera.main;frameObserver=Game.observer;
        collecting = Game.scene() == scene && level != null && runId != null && Game.observer.observesVisualCues();
        if(!collecting)previousMotion=Collections.emptyMap();
    }

    public void targetDrawn(Visual source, int cell) { offer(source, "red_target", cell); }

    /** Semantic annotations of an actual draw; geometry and later overlays still decide visibility. */
    public void cellVisualDrawn(Visual source, VisualCue cue) {
        if (!collecting || !drawable(source) || resolvedCamera(source) != Camera.main) return;
        String key = cueKey(cue);
        offered.put(key, cue);
        cueBounds.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(Collections.singletonList(screenBounds(source, Camera.main)));
    }

    /** Nova's known gradient can retain an edge when its white-pixel alpha is zero. */
    public void haloVisualDrawn(com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SuperNovaTracker.NovaVFX source,
                                VisualCue cue) {
        if (!collecting || !attached(source) || !source.isVisible() || resolvedCamera(source) != Camera.main
                || !source.renderedHaloContributes()) return;
        VisualCueProjection.Rect world = VisualCueProjection.worldBounds(source);
        Camera.DrawnTransform transform = Camera.main.observedTransform();
        if (world == null || transform == null) return;
        VisualCueProjection.Rect bounds = new VisualCueProjection.Rect(transform.worldToScreenX(world.left),
                transform.worldToScreenY(world.top), transform.worldToScreenX(world.right), transform.worldToScreenY(world.bottom));
        String key = cueKey(cue); offered.put(key, cue);
        cueBounds.computeIfAbsent(key, ignored -> new ArrayList<>()).add(Collections.singletonList(bounds));
    }

    /** Motion is measured only after both this draw and its predecessor pass every visibility gate. */
    public void movingVisualDrawn(Visual source, VisualCue cue) {
        if (!collecting || !drawable(source) || resolvedCamera(source) != Camera.main) return;
        Camera camera=Camera.main;
        VisualCueProjection.Rect bounds=screenBounds(source,camera);
        VisualCueProjection.Rect world=VisualCueProjection.worldBounds(source);
        if(bounds==null||world==null)return;
        float x=(world.left+world.right)/2f;
        float y=(world.top+world.bottom)/2f;
        if(!Float.isFinite(x)||!Float.isFinite(y))return;
        movingObservations.add(new MovingObservation(source,cue,bounds,new MotionSample(source,x,y)));
    }

    private void finishMovingDraws(VisualCueProjection.Viewport viewport,List<VisualCueProjection.Rect> blockers) {
        Map<Visual,MotionSample> next=new IdentityHashMap<>();
        if(previousMotionLevel!=level)previousMotion=Collections.emptyMap();
        if(viewport!=null)for(MovingObservation observation:movingObservations) {
            VisualCue cue=observation.cue;
            if(!attached(observation.source)||!VisualCueProjection.permits(cue.cell,level.width(),level.length(),level.heroFOV,
                    DungeonTilemap.SIZE,viewport,blockers)
                    ||!VisualCueProjection.permitsScreenBounds(observation.bounds,viewport,blockers)
                    ||!VisualCueProjection.permitsVisibleFootprint(observation.bounds,viewport,DungeonTilemap.SIZE,
                        level.width(),level.length(),level.heroFOV)
                    ||cue.sourceCell!=null&&!VisualCueProjection.permits(cue.sourceCell,level.width(),level.length(),level.heroFOV,
                        DungeonTilemap.SIZE,viewport,blockers))continue;
            MotionSample before=previousMotion.get(observation.source), now=observation.sample;
            String direction=before!=null&&before.lifetime==now.lifetime
                    ?ParticleMotionCue.direction(now.x-before.x,now.y-before.y):null;
            VisualCue measured=new VisualCue(cue.kind,cue.cell,cue.sourceCell,direction,cue.color,cue.opacity,cue.appearance);
            String key=cueKey(measured);offered.put(key,measured);
            cueBounds.computeIfAbsent(key,ignored->new ArrayList<>()).add(Collections.singletonList(observation.bounds));
            next.put(observation.source,now);
        }
        previousMotion=next;previousMotionLevel=level;
    }

    /** Each source reaches this only after its own native draw, before later overlays are known. */
    public void particleMetricDrawn(Visual source,String kind,int cell) {
        if(!collecting||cell<0||!drawable(source)||resolvedCamera(source)!=Camera.main)return;
        Object episode=source.parent instanceof Emitter?((Emitter)source.parent).observedDrawEpisode():source;
        recordMetric(source,new ParticleMetric(kind,cell,screenBounds(source,Camera.main),episode,Collections.emptyMap()));
    }

    /** Generic particle histogram: native draw geometry and appearance only, never requested emission counts. */
    public void emitterMetricDrawn(Emitter emitter,Object episode) {
        if(!collecting||!attached(emitter)||resolvedCamera(emitter)!=Camera.main)return;
        Camera camera=Camera.main;
        for(Gizmo child:emitter.childrenSnapshot()){
            if(!(child instanceof Visual))continue;
            Visual source=(Visual)child;
            if(!drawable(source)||resolvedCamera(source)!=camera||!Float.isFinite(source.alpha()))continue;
            VisualCueProjection.Rect bounds=screenBounds(source,camera);
            if(bounds==null||!Float.isFinite(bounds.left+bounds.top+bounds.right+bounds.bottom))continue;
            Camera.DrawnTransform transform=camera.observedTransform();
            float x=transform.screenToWorldX((bounds.left+bounds.right)/2f),y=transform.screenToWorldY((bounds.top+bounds.bottom)/2f);
            if(!Float.isFinite(x+y)||x<0||y<0)continue;
            int column=(int)(x/DungeonTilemap.SIZE),row=(int)(y/DungeonTilemap.SIZE);
            if(column>=level.width()||row>=level.height())continue;
            Map<String,Object> appearance;
            if(source instanceof PseudoPixel){
                appearance=new LinkedHashMap<>();appearance.put("shape","pixel");
                if(Float.isFinite(source.rm+source.gm+source.bm+source.ra+source.ga+source.ba))appearance.put("color",source.displayedTextColor());
                else appearance.put("color_unknown",true);
                appearance.put("alpha",source.alpha());appearance.put("size",java.util.Arrays.asList(source.width(),source.height()));
            }else appearance=RenderedAppearance.image(source);
            if(appearance.isEmpty())continue;
            recordMetric(source,new ParticleMetric("cell_particles",row*level.width()+column,bounds,episode,appearance));
        }
    }

    private void recordMetric(Visual source,ParticleMetric metric){
        drawnMetrics.computeIfAbsent(source,ignored->new LinkedHashMap<>()).put(metric.kind,metric);
    }

    private List<VisualMetric> visibleMetrics(VisualCueProjection.Viewport viewport,List<VisualCueProjection.Rect> blockers) {
        Map<VisualMetric,Integer> counts=new LinkedHashMap<>();
        Set<Object> episodes=Collections.newSetFromMap(new IdentityHashMap<>());
        if(viewport!=null)for(Map.Entry<Visual,Map<String,ParticleMetric>> entry:drawnMetrics.entrySet()){
            for(ParticleMetric metric:entry.getValue().values()){
                if(!attached(entry.getKey())||!VisualCueProjection.permits(metric.cell,level.width(),level.length(),level.heroFOV,
                        DungeonTilemap.SIZE,viewport,blockers)||!VisualCueProjection.permitsScreenBounds(metric.bounds,viewport,blockers)
                        ||!VisualCueProjection.permitsVisibleFootprint(metric.bounds,viewport,DungeonTilemap.SIZE,
                            level.width(),level.length(),level.heroFOV))continue;
                VisualMetric key=new VisualMetric(metric.kind,metric.cell,1,metric.appearance);
                counts.put(key,counts.getOrDefault(key,0)+1);
                if(metric.episode!=null)episodes.add(metric.episode);
            }
        }
        visibleMetricEpisodes=Collections.unmodifiableSet(episodes);
        List<VisualMetric> result=new ArrayList<>();
        counts.forEach((key,count)->result.add(new VisualMetric(key.kind,key.cell,count,key.appearance)));
        result.sort(Comparator.comparing((VisualMetric value)->value.kind).thenComparingInt(value->value.cell).thenComparing(value->value.appearance.toString()));
        return Collections.unmodifiableList(result);
    }

    public void linkedVisualDrawn(Visual first, Visual second, VisualCue cue) {
        if (!collecting || !drawable(first) || !drawable(second)
                || resolvedCamera(first) != Camera.main || resolvedCamera(second) != Camera.main) return;
        String key = cueKey(cue);
        offered.put(key, cue);
        List<VisualCueProjection.Rect> bounds = new ArrayList<>();
        bounds.add(screenBounds(first, Camera.main));
        bounds.add(screenBounds(second, Camera.main));
        cueBounds.computeIfAbsent(key, ignored -> new ArrayList<>()).add(bounds);
    }

    /** Flare's triangle fan has no Image width/height; use its actual rendered radial extent. */
    public void radialVisualDrawn(Visual source, VisualCue cue, float radius) {
        if (!collecting || !attached(source) || !source.alive || source.am + source.aa <= 0
                || resolvedCamera(source) != Camera.main || !Float.isFinite(radius) || radius <= 0
                || !Float.isFinite(source.scale.x) || !Float.isFinite(source.scale.y)) return;
        float xRadius = radius * Math.abs(source.scale.x), yRadius = radius * Math.abs(source.scale.y);
        if (xRadius <= 0 || yRadius <= 0) return;
        Camera camera = Camera.main;
        if (camera == null) return;
        Camera.DrawnTransform transform=camera.observedTransform();
        VisualCueProjection.Rect bound = new VisualCueProjection.Rect(
                transform.worldToScreenX(source.x-xRadius),transform.worldToScreenY(source.y-yRadius),
                transform.worldToScreenX(source.x+xRadius),transform.worldToScreenY(source.y+yRadius));
        String key = cueKey(cue); offered.put(key, cue);
        cueBounds.computeIfAbsent(key, ignored -> new ArrayList<>()).add(Collections.singletonList(bound));
    }

    private static String cueKey(VisualCue cue) {
        String key = cue.kind + ":" + cue.cell;
        return cue.sourceCell == null && cue.direction == null && cue.color == null && cue.opacity == null && cue.appearance == null ? key
                : key + ":" + cue.sourceCell + ":" + cue.direction + ":" + cue.color + ":" + cue.opacity + ":" + cue.appearance;
    }

    public void tilemapDrawn(Tilemap source, String kind) {
        if (!collecting || !drawable(source) || resolvedCamera(source) != Camera.main
                || source.angle != 0 || source.scale.x != 1 || source.scale.y != 1
                || source.origin.x != 0 || source.origin.y != 0) return;
        source.visitDrawnTiles((left, top, right, bottom) -> {
            int cell = VisualCueProjection.gridCell(source.x + left, source.y + top,
                    right-left, bottom-top, DungeonTilemap.SIZE, level.width(), level.length());
            if (cell >= 0) offer(source, kind, cell);
        });
    }

    public void particleEmitterDrawn(Emitter emitter, CellParticleCue observation) {
        if (!collecting || !attached(emitter)) return;
        List<ParticleContributor> contributors = new ArrayList<>();
        // Retain every live geometric candidate, including clipped ones. A hidden first
        // particle cannot hide a later visible contributor or hold the input indefinitely.
        for (Gizmo child : emitter.childrenSnapshot()) {
            if (!observation.matches(child) || !(child instanceof Visual) || !child.exists || !child.alive) continue;
            Visual particle = (Visual)child;
            if (!(particle.width() > 0) || !(particle.height() > 0)) continue;
            boolean drawn = Camera.main != null && drawable(particle) && resolvedCamera(particle) == Camera.main;
            contributors.add(new ParticleContributor(particle, Camera.main == null ? null : screenBounds(particle, Camera.main), drawn));
        }
        particleObservations.add(new ParticleObservation(emitter, observation.cell, observation, contributors));
    }

    private boolean finishParticleDraws(VisualCueProjection.Viewport viewport, List<VisualCueProjection.Rect> blockers) {
        boolean ready = true;
        for (ParticleObservation observed : particleObservations) {
            boolean anchorVisible = viewport != null && attached(observed.source)
                    && resolvedCamera(observed.source) == Camera.main
                    && VisualCueProjection.permits(observed.cell, level.width(), level.length(), level.heroFOV,
                            DungeonTilemap.SIZE, viewport, blockers);
            boolean visibleContributor = false;
            if (anchorVisible) for (ParticleContributor contributor : observed.contributors) {
                if (!contributor.drawn || !attached(contributor.source)
                        || !VisualCueProjection.permitsScreenBounds(contributor.bounds, viewport, blockers)
                        || !VisualCueProjection.permitsVisibleFootprint(contributor.bounds, viewport, DungeonTilemap.SIZE,
                            level.width(), level.length(), level.heroFOV)) continue;
                visibleContributor = true;
                VisualCue cue = new VisualCue(observed.observation.kind, observed.cell);
                String key = cueKey(cue); offered.put(key, cue);
                cueBounds.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(Collections.singletonList(contributor.bounds));
            }
            // An eligible source with no first particle still waits for its initial draw.
            // Existing but wholly hidden/partial contributors do not delay an input; nor
            // do stopped, inactive or frozen emitters. Only a fully visible draw latches readiness.
            boolean eligible = anchorVisible && observed.observation.emitting(observed.source)
                    && (observed.contributors.isEmpty() || visibleContributor);
            ready &= observed.observation.recordVisibleFrame(eligible, visibleContributor)
                    || !observed.source.canProgress();
        }
        return ready;
    }

    public void floatingTextDrawn(FloatingText source, int cell) {
        if (!collecting || !attached(source) || resolvedCamera(source) != Camera.main) return;
        drawnTexts.add(source);
        if(cell<0)return;
        String kind = countdownKind(source.visibleCueText());
        if (kind == null) return;
        VisualCueProjection.Rect bounds = screenBounds(source, Camera.main);
        if (bounds == null) return;
        String key = kind + ":" + cell;
        offered.put(key, new VisualCue(kind, cell));
        textBounds.put(key, bounds);
    }

    static String countdownKind(String actuallyDrawnText) {
        if ("3...".equals(actuallyDrawnText)) return "bomb_countdown_3";
        if ("2...".equals(actuallyDrawnText)) return "bomb_countdown_2";
        if ("1...".equals(actuallyDrawnText)) return "bomb_countdown_1";
        return null;
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
        if (Game.scene() != scene || Dungeon.level != level || Dungeon.depth!=depth || !runId.equals(Dungeon.runId)
                ||Camera.main!=frameCamera||Game.observer!=frameObserver
                || !Game.observer.observesVisualCues()) { previousMotion=Collections.emptyMap(); return; }
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
        boolean initialVisualsReady;
        if (!modal && camera != null && camera.visible) {
            Camera.DrawnTransform transform=camera.observedTransform();
            viewport = new VisualCueProjection.Viewport(transform.scrollX, transform.scrollY,
                    transform.width, transform.height, transform.x, transform.y, transform.zoom);
            finishMovingDraws(viewport, blockers);
            initialVisualsReady = finishParticleDraws(viewport, blockers);
            for (VisualCue cue : offered.values()) {
                if (VisualCueProjection.permits(cue.cell, level.width(), level.length(), level.heroFOV,
                        DungeonTilemap.SIZE, viewport, blockers)
                        && (cue.sourceCell == null || VisualCueProjection.permits(cue.sourceCell,
                            level.width(), level.length(), level.heroFOV, DungeonTilemap.SIZE, viewport, blockers))
                        && permitsCueBounds(cueBounds.get(cueKey(cue)), viewport, blockers)
                        && (!textBounds.containsKey(cue.kind + ":" + cue.cell)
                            || VisualCueProjection.permitsScreenBounds(textBounds.get(cue.kind + ":" + cue.cell), viewport, blockers)))
                    visible.add(cue);
            }
            final VisualCueProjection.Viewport textViewport=viewport;
            for(FloatingText text:drawnTexts)if(attached(text))text.recordDisplayedAppearance(word ->
                    resolvedCamera(word)==camera&&VisualCueProjection.permitsScreenBounds(screenBounds(word,camera),textViewport,blockers),
                    VisualCueProjection.permits(text.observationCell(),level.width(),level.length(),level.heroFOV,
                            DungeonTilemap.SIZE,viewport,blockers));
        } else {
            finishMovingDraws(null,blockers);
            initialVisualsReady = finishParticleDraws(null,blockers);
        }
        visible.sort(Comparator.comparing((VisualCue cue) -> cue.kind).thenComparingInt(cue -> cue.cell)
                .thenComparing(cue -> cue.sourceCell, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(cue -> cue.direction, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(cue -> cue.color, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(cue -> cue.opacity, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(cue -> cue.appearance == null ? "" : cue.appearance.toString()));
        if (!visible.equals(lastPublished)) lastPublished = Collections.unmodifiableList(visible);
        // Empty and unchanged complete draws still acknowledge a render generation. The
        // observer handles public event deduplication and never receives filtered counts.
        Game.observer.onVisualCues(runId, level, depth, lastPublished, initialVisualsReady);
        List<VisualMetric> metrics=visibleMetrics(viewport,blockers);
        Game.observer.onVisualMetrics(runId, level, depth, metrics,visibleMetricEpisodes);
        for(FloatingText text:drawnTexts) if(attached(text)) text.publishDisplayedText(runId,level,depth);
    }

    private static boolean permitsCueBounds(List<List<VisualCueProjection.Rect>> alternatives,
                                            VisualCueProjection.Viewport viewport,
                                            List<VisualCueProjection.Rect> blockers) {
        if (alternatives == null) return true;
        for (List<VisualCueProjection.Rect> bounds : alternatives) {
            boolean visible = true;
            for (VisualCueProjection.Rect bound : bounds)
                visible &= VisualCueProjection.permitsScreenBounds(bound, viewport, blockers);
            if (visible) return true;
        }
        return false;
    }

    private static Camera resolvedCamera(Gizmo gizmo) {
        for (Gizmo current = gizmo; current != null; current = current.parent) {
            // Scene.camera() supplies the main camera even before an emitter's first
            // particle lazily caches it. Read that fallback without populating caches.
            if (current instanceof com.watabou.noosa.Scene) return Camera.main;
            if (current.camera != null) return current.camera;
        }
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
        Camera.DrawnTransform transform=camera.observedTransform();
        return new VisualCueProjection.Rect(transform.worldToScreenX(left),transform.worldToScreenY(top),
                transform.worldToScreenX(right),transform.worldToScreenY(bottom));
    }
}
