package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.noosa.*;
import com.watabou.noosa.particles.Emitter;

import java.util.*;

/** Pure semantic projection of existing world presentation sources. Camera and UI never restrict knowledge. */
public final class VisualCueCollector {
    private final GameScene scene;
    private Level level;
    private String runId;
    private int depth;
    private boolean collecting, frameEligible;
    private RuntimeObserver frameObserver;
    private final Map<CueIdentity,VisualCue> offered = new LinkedHashMap<>();
    private Gizmo observingSource;
    /** Identity-only observer metadata. It contains no public payload, changing measurement, or clock. */
    private static final class SourceOccurrence {
        final Gizmo source; final long lifetime; final Object episode;
        SourceOccurrence(Gizmo source){
            this.source=source;this.lifetime=source.observationLifetime();
            this.episode=source instanceof Emitter?((Emitter)source).observedDrawEpisode():null;
        }
        @Override public int hashCode(){return 31*(31*System.identityHashCode(source)+Long.hashCode(lifetime))+System.identityHashCode(episode);}
        @Override public boolean equals(Object value){return value instanceof SourceOccurrence&&source==((SourceOccurrence)value).source
                &&lifetime==((SourceOccurrence)value).lifetime&&episode==((SourceOccurrence)value).episode;}
    }
    private static final class CueIdentity {
        final SourceOccurrence occurrence; final VisualCue cue;
        CueIdentity(Gizmo source,VisualCue cue){this.occurrence=new SourceOccurrence(source);this.cue=cue;}
        @Override public int hashCode(){return 31*occurrence.hashCode()+cue.hashCode();}
        @Override public boolean equals(Object value){return value instanceof CueIdentity
                &&occurrence.equals(((CueIdentity)value).occurrence)&&cue.equals(((CueIdentity)value).cue);}
    }
    private final List<ParticleObservation> particleObservations = new ArrayList<>();
    private final List<MovingObservation> movingObservations = new ArrayList<>();
    private final List<FloatingText> drawnTexts = new ArrayList<>();
    private final Map<String,Set<Visual>> densities = new LinkedHashMap<>();
    private Map<Visual,MotionSample> previousMotion = new IdentityHashMap<>();
    private Level previousMotionLevel;
    private List<VisualCue> lastPublished = Collections.emptyList();

    private static final class MotionSample {
        final long lifetime; final float x,y;
        MotionSample(Visual source,float x,float y){lifetime=source.observationLifetime();this.x=x;this.y=y;}
    }
    private static final class MovingObservation {
        final Visual source; final Gizmo owner; final VisualCue cue; final MotionSample sample;
        MovingObservation(Visual source,Gizmo owner,VisualCue cue,MotionSample sample){this.source=source;this.owner=owner;this.cue=cue;this.sample=sample;}
    }
    private static final class ParticleObservation {
        final Emitter source; final CellParticleCue observation; final List<Visual> contributors; final int cell;
        ParticleObservation(Emitter source,CellParticleCue observation,List<Visual> contributors){
            this.source=source;this.observation=observation;this.contributors=contributors;this.cell=observation.observedCell();
        }
    }

    public VisualCueCollector(GameScene scene){this.scene=scene;}
    public void beginDraw(){
        for(FloatingText text:drawnTexts)text.clearDisplayedText();
        drawnTexts.clear(); offered.clear(); particleObservations.clear(); movingObservations.clear(); densities.clear();
        level=Dungeon.level;runId=Dungeon.runId;depth=Dungeon.depth;frameObserver=Game.observer;
        collecting=false;
        frameEligible=Game.scene()==scene&&level!=null&&runId!=null&&frameObserver.observesVisualCues();
        if(!frameEligible)previousMotion=Collections.emptyMap();
    }

    /** Called after the ordinary draw. Never manufactures a draw, emission, update, or actor turn. */
    public void finishDraw(){
        if(!frameEligible)return;
        GameScene.atActorHandoff(()->{
            if(Game.scene()!=scene||Dungeon.level!=level||Dungeon.depth!=depth||!Objects.equals(runId,Dungeon.runId)
                    ||Game.observer!=frameObserver||!frameObserver.observesVisualCues()){
                previousMotion=Collections.emptyMap();return;
            }
            collecting=true;
            try { collectSources(scene); } finally { collecting=false; }
            finishMovingDraws();
            boolean ready=finishParticleDraws();
            for(Map.Entry<String,Set<Visual>> entry:densities.entrySet()){
                int cell=Integer.parseInt(entry.getKey());
                add(scene,new VisualCue("sacrificial_flames",cell,null,null,null,null,
                        Collections.singletonMap("count",entry.getValue().size())));
            }
            List<VisualCue> cues=new ArrayList<>(offered.values());
            cues.sort(Comparator.comparing((VisualCue c)->c.kind).thenComparingInt(c->c.cell)
                    .thenComparing(c->c.sourceCell,Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(c->c.direction,Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(c->c.appearance==null?"":c.appearance.toString()));
            lastPublished=Collections.unmodifiableList(cues);
            // A complete empty frame is still a generation; it clears disappeared warnings.
            frameObserver.onVisualCues(runId,level,depth,lastPublished,ready,occurrenceKey());
            for(FloatingText text:drawnTexts)if(floatingAttached(text)&&known(text.observationCell())){
                text.recordDisplayedAppearance(word->floatingPart(text,word),true);
                text.publishDisplayedText(runId,level,depth);
            }
        });
    }

    /** The scene hierarchy is traversed regardless of native draw culling. Reads never cache cameras. */
    private void collectSources(Gizmo source){
        if(source==null||!source.exists||!source.alive||!source.visible)return;
        // Native floating feedback has an explicit world anchor even if its presentation uses a UI camera.
        // Traversal of a UI group does not authorize ordinary UI producers or reveal its labels.
        boolean eligible=source instanceof FloatingText?floatingAttached((FloatingText)source):attached(source);
        if(eligible){
            Gizmo previous=observingSource;observingSource=source;
            try{source.observeGameplayVisuals();}finally{observingSource=previous;}
        }
        if(source instanceof Group)for(Gizmo child:((Group)source).childrenSnapshot())collectSources(child);
    }
    private boolean known(int cell){return level!=null&&VisualCueProjection.knownCell(cell,level.length(),level.heroFOV);}
    private boolean knownTerrain(int cell){
        return level!=null&&(known(cell)||VisualCueProjection.knownCell(cell,level.length(),level.visited)
                ||VisualCueProjection.knownCell(cell,level.length(),level.mapped));
    }
    private void add(Gizmo source,VisualCue cue){offered.put(new CueIdentity(source,cue),cue);}
    private Object occurrenceKey(){
        Set<SourceOccurrence> occurrences=new LinkedHashSet<>();
        for(CueIdentity identity:offered.keySet())occurrences.add(identity.occurrence);
        return Collections.unmodifiableSet(occurrences);
    }
    private VisualCue semantic(VisualCue cue){
        return new VisualCue(cue.kind,cue.cell,cue.sourceCell,cue.direction,null,null,cue.appearance);
    }
    public void targetDrawn(Visual source,int cell){cellVisualDrawn(source,new VisualCue("red_target",cell));}
    public void cellVisualDrawn(Visual source,VisualCue cue){
        if(!collecting||!drawable(source))return;
        if(cue.sourceCell!=null){linkedFragments(Collections.singletonList(source),cue);return;}
        if(!known(cue.cell))return;
        add(source,semantic(cue));
    }
    public void haloVisualDrawn(com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SuperNovaTracker.NovaVFX source,VisualCue cue){
        if(collecting&&attached(source)&&source.renderedHaloContributes()&&known(cue.cell))add(source,semantic(cue));
    }
    public void linkedVisualDrawn(Visual first,Visual second,VisualCue cue){
        if(collecting)linkedFragments(Arrays.asList(first,second),cue);
    }
    private void linkedFragments(List<Visual> parts,VisualCue cue){
        TreeSet<Integer> cells=new TreeSet<>();boolean partial=false;
        for(Visual part:parts){
            if(!drawable(part))continue;
            List<Integer> footprint=VisualCueProjection.worldFootprint(part,level.width(),level.length(),DungeonTilemap.SIZE);
            for(int cell:footprint){if(known(cell))cells.add(cell);else partial=true;}
        }
        if(cells.isEmpty())return;
        Gizmo source=observingSource!=null?observingSource:parts.get(0);
        if(known(cue.cell)&&cue.sourceCell!=null&&known(cue.sourceCell)&&!partial){add(source,semantic(cue));return;}
        Map<String,Object> fact=new LinkedHashMap<>();fact.put("cells",new ArrayList<>(cells));fact.put("partial",true);
        // Never encode the hidden endpoint, full extent, or hidden source direction.
        add(source,new VisualCue(cue.kind,cells.first(),null,null,null,null,fact));
    }
    public void radialVisualDrawn(Visual source,VisualCue cue,float radius){
        if(!collecting||!("loot_flare".equals(cue.kind)||"character_aura".equals(cue.kind))||!attached(source)||!known(cue.cell)
                ||!Float.isFinite(radius)||radius<=0||!Float.isFinite(source.am+source.aa)
                ||source.am+source.aa<=0||!Float.isFinite(source.scale.x+source.scale.y)
                ||source.scale.x==0||source.scale.y==0)return;
        add(source,semantic(cue));
    }
    /** Already installed character visuals; no backing Buff, AI or status enum is inspected. */
    public void characterVisualDrawn(Gizmo source,String kind,int cell){
        if(!collecting||source==null||!known(cell)||!attached(source))return;
        boolean contributes=false;
        if(source instanceof Emitter){
            for(Gizmo child:((Emitter)source).childrenSnapshot())if(child instanceof Visual&&drawable((Visual)child)&&hasKnownFootprint((Visual)child)){
                contributes=true;break;
            }
        }else if(source instanceof Halo){
            Halo halo=(Halo)source;
            // Known halo gradient has both transparent and nontransparent samples; alpha-add may show only its rim.
            contributes=halo.texture!=null&&Float.isFinite(halo.am+halo.aa)
                    &&Math.max(halo.am,0)+halo.aa>0&&halo.width()>0&&halo.height()>0&&hasKnownFootprint(halo);
        }else if(source instanceof Visual)contributes=drawable((Visual)source)&&hasKnownFootprint((Visual)source);
        if(contributes)add(source,new VisualCue(kind,cell));
    }
    public void movingVisualDrawn(Visual source,VisualCue cue){
        if(!collecting||!drawable(source)||!known(cue.cell)||!hasKnownFootprint(source))return;
        VisualCueProjection.Rect bounds=VisualCueProjection.worldBounds(source);if(bounds==null)return;
        boolean full=true;
        for(int cell:VisualCueProjection.worldFootprint(source,level.width(),level.length(),DungeonTilemap.SIZE))full&=known(cell);
        movingObservations.add(new MovingObservation(source,observingSource!=null?observingSource:source,semantic(cue),
                full?new MotionSample(source,(bounds.left+bounds.right)/2f,(bounds.top+bounds.bottom)/2f):null));
    }
    private void finishMovingDraws(){
        Map<Visual,MotionSample> next=new IdentityHashMap<>();
        if(previousMotionLevel!=level)previousMotion=Collections.emptyMap();
        for(MovingObservation value:movingObservations){
            if(!attached(value.source)||!known(value.cue.cell))continue;
            MotionSample before=previousMotion.get(value.source),now=value.sample;
            String direction=before!=null&&now!=null&&before.lifetime==now.lifetime?ParticleMotionCue.direction(now.x-before.x,now.y-before.y):null;
            add(value.owner,new VisualCue(value.cue.kind,value.cue.cell,null,direction,null,null,value.cue.appearance));
            if(now!=null)next.put(value.source,now);
        }
        previousMotion=next;previousMotionLevel=level;
    }
    public void particleEmitterDrawn(Emitter emitter,CellParticleCue observation){
        if(!collecting||!attached(emitter)||!observation.acceptsSource(emitter))return;
        List<Visual> contributors=new ArrayList<>();
        for(Gizmo child:emitter.childrenSnapshot())if(observation.matches(child)&&child instanceof Visual&&child.exists&&child.alive)
            contributors.add((Visual)child);
        observation.beginObservation(emitter);
        particleObservations.add(new ParticleObservation(emitter,observation,contributors));
    }
    private boolean finishParticleDraws(){
        boolean ready=true;
        for(ParticleObservation value:particleObservations){
            boolean anchor=attached(value.source)&&value.observation.acceptsSource(value.source)&&known(value.cell);
            Set<Visual> visible=Collections.newSetFromMap(new IdentityHashMap<>());
            if(anchor)for(Visual child:value.contributors)if(drawable(child)&&hasKnownFootprint(child))visible.add(child);
            boolean contributes=!visible.isEmpty();
            if(contributes){
                Map<String,Object> measure=null;
                if(value.observation.measuredCount()){
                    measure=new LinkedHashMap<>();measure.put("count",visible.size());measure.put("basis","observed_particles");
                }
                add(value.source,new VisualCue(value.observation.kind,value.cell,null,null,null,null,measure));
            }else if(value.observation.retainActiveEpisode()&&anchor&&value.observation.emitting(value.source)&&value.observation.hasObservedPresentation())
                add(value.source,new VisualCue(value.observation.kind,value.cell));
            boolean eligible=anchor&&value.observation.emitting(value.source)
                    &&(value.contributors.isEmpty()||contributes);
            ready&=value.observation.recordVisibleFrame(eligible,contributes)||!value.source.canProgress();
        }
        return ready;
    }
    /** The one reviewed density signal: count existing known flame contributors, never blob amounts. */
    public void particleMetricDrawn(Visual source,String kind,int cell){
        if(!collecting||!"sacrificial_flames".equals(kind)||!known(cell)||!drawable(source)||!hasKnownFootprint(source))return;
        densities.computeIfAbsent(Integer.toString(cell),ignored->Collections.newSetFromMap(new IdentityHashMap<>())).add(source);
    }
    public void emitterMetricDrawn(Emitter source,Object episode){} // no generic particle histograms in CLI 8
    public void tilemapDrawn(Tilemap source,String kind){
        if(!collecting||!drawable(source)||!source.hasPresentationTexture()||source.angle!=0||source.scale.x!=1||source.scale.y!=1
                ||source.origin.x!=0||source.origin.y!=0)return;
        source.visitPresentationTiles((left,top,right,bottom)->{
            int cell=VisualCueProjection.gridCell(source.x+left,source.y+top,right-left,bottom-top,
                    DungeonTilemap.SIZE,level.width(),level.length());
            if(known(cell))add(source,new VisualCue(kind,cell));
        });
    }
    /** Custom terrain stays drawn beneath translucent visited/mapped fog, just like base terrain. */
    public void terrainVisualObserved(Tilemap source,VisualCue cue){
        if(!collecting||!drawable(source)||!source.hasPresentationTexture()||source.angle!=0||source.scale.x!=1||source.scale.y!=1
                ||source.origin.x!=0||source.origin.y!=0||level==null)return;
        int cell=cue.cell;
        if(cell<0||cell>=level.length())return;
        if(knownTerrain(cell))add(source,semantic(cue));
    }
    /** The KeyWall layer is below fog; visited/mapped fog still shows its actual brick contributors. */
    public void spectralWallObserved(Emitter source,com.shatteredpixel.shatteredpixeldungeon.effects.particles.SpectralWallParticle particle,int cell){
        if(!collecting||!attached(source)||particle.parent!=source||!drawable(particle)||!knownTerrain(cell))return;
        boolean visibleFragment=false;
        for(int touched:VisualCueProjection.worldFootprint(particle,level.width(),level.length(),DungeonTilemap.SIZE))
            if(knownTerrain(touched)){visibleFragment=true;break;}
        if(visibleFragment)add(scene,new VisualCue("spectral_wall",cell));
    }
    public void floatingTextDrawn(FloatingText source,int cell){
        if(!collecting||!floatingAttached(source)||!known(cell))return;
        if(!drawnTexts.contains(source))drawnTexts.add(source);
        String kind=countdownKind(source.visibleCueText(true));if(kind!=null)add(source,new VisualCue(kind,cell));
    }
    static String countdownKind(String text){
        if("3...".equals(text))return "bomb_countdown_3";
        if("2...".equals(text))return "bomb_countdown_2";
        if("1...".equals(text))return "bomb_countdown_1";
        return null;
    }
    private boolean hasKnownFootprint(Visual source){
        for(int cell:VisualCueProjection.worldFootprint(source,level.width(),level.length(),DungeonTilemap.SIZE))if(known(cell))return true;
        return false;
    }
    private boolean drawable(Visual source){
        return attached(source)&&Float.isFinite(source.am+source.aa)&&source.am+source.aa>0
                &&source.width()>0&&source.height()>0&&(!(source instanceof Image)||((Image)source).texture!=null);
    }
    private boolean floatingAttached(FloatingText source){
        for(Gizmo value=source;value!=null;value=value.parent){
            if(!value.exists||!value.alive||!value.visible)return false;
            if(value==scene)return true;
        }
        return false;
    }
    /** The text's known cell owns its feedback. Glyph layout, drift and camera framing do not reveal new world cells. */
    private boolean floatingPart(FloatingText owner,Visual part){
        if(!floatingAttached(owner)||!known(owner.observationCell())||part==null
                ||!Float.isFinite(part.am+part.aa)||part.am+part.aa<=0
                ||!Float.isFinite(part.width()+part.height())||part.width()<=0||part.height()<=0)return false;
        boolean belongs=false;
        for(Gizmo node=part;node!=null;node=node.parent){
            if(!node.exists||!node.alive||!node.visible)return false;
            if(node==owner){belongs=true;break;}
        }
        if(!belongs)return false;
        // RenderedText uses its BitmapFont and normally has no Image.texture.
        return part instanceof RenderedText?((RenderedText)part).hasRenderableText()
                :part instanceof Image&&((Image)part).texture!=null;
    }
    private boolean attached(Gizmo source){
        for(Gizmo value=source;value!=null;value=value.parent){
            if(!value.exists||!value.alive||!value.visible)return false;
            if(value!=scene&&value.camera!=null&&value.camera!=Camera.main)return false;
            if(value==scene)return true;
        }
        return false;
    }
    private static Camera resolvedCamera(Gizmo source){
        for(Gizmo value=source;value!=null;value=value.parent){
            if(value instanceof com.watabou.noosa.Scene)return Camera.main;
            if(value.camera!=null)return value.camera;
        }
        return null;
    }
}
