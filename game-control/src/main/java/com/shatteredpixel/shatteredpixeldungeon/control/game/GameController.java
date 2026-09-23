package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Berserk;
import com.shatteredpixel.shatteredpixeldungeon.scenes.*;
import com.shatteredpixel.shatteredpixeldungeon.journal.Journal;
import com.shatteredpixel.shatteredpixeldungeon.Badges;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ProtocolException;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.VisualMetric;
import com.watabou.noosa.ScreenEffect;
import com.watabou.noosa.Camera;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Bridges the render/actor handoff to frozen observations. Never owns a transport or database. */
public final class GameController implements RuntimeObserver {
    /** Proven rejection before any game callback was invoked. */
    public static final class NotExecuted extends RuntimeException {
        public final String code;
        NotExecuted(String code,Throwable cause){super(code,cause);this.code=code;}
    }
    public static final class State {
        public final String scopeId, version, phase;
        public final Map<String,Object> publicState, internalState;
        public final List<Map<String,Object>> actions;
        public final Map<String,Object> runOutcome;
        State(String scopeId, String version, String phase, Map<String,Object> publicState,
              Map<String,Object> internalState, List<Map<String,Object>> actions) {
            this(scopeId,version,phase,publicState,internalState,actions,null);
        }
        State(String scopeId,String version,String phase,Map<String,Object> publicState,
              Map<String,Object> internalState,List<Map<String,Object>> actions,Map<String,Object> runOutcome){
            this.scopeId=scopeId; this.version=version; this.phase=phase;
            this.publicState=PublicEnglishProjection.freeze(publicState); this.internalState=internalState; this.actions=PublicEnglishProjection.freeze(actions);
            this.runOutcome=runOutcome;
        }
        public Map<String,Object> result() {
            Map<String,Object> result=map("scope_id",scopeId,"state_version",version,"phase",phase,
                    "observation",publicState,"actions",actions);
            if(runOutcome!=null)result.put("run_outcome",runOutcome);
            return PublicEnglishProjection.copy(result);
        }
    }
    public static final class SaveResult {
        public final String receiptId,runId,occurredAt,originScopeId,originRequestId;
        public final int slot; public final Throwable error;
        public SaveResult(String runId,int slot,Throwable error){
            this(UUID.randomUUID().toString(),runId,slot,error,Instant.now().toString(),null,null);
        }
        public SaveResult(String receiptId,String runId,int slot,Throwable error,String occurredAt,String originScopeId,String originRequestId){
            this.receiptId=receiptId;this.runId=runId;this.slot=slot;this.error=error;
            this.occurredAt=occurredAt;this.originScopeId=originScopeId;this.originRequestId=originRequestId;
        }
    }
    public static final class RunOutcome {
        public final String runId;
        public final boolean won;
        public RunOutcome(String runId,boolean won){this.runId=runId;this.won=won;}
        public Map<String,Object> data(){return map("scope_id","run:"+runId,"result",won?"won":"lost");}
    }
    public static final class GameLogSnapshot {
        public final String scopeId,occurredAt,guiLanguage;
        public final List<RuntimeObserver.LogEntry> entries;
        private final Map<String,Object> frozen;
        public GameLogSnapshot(String scopeId,String occurredAt,List<RuntimeObserver.LogEntry> entries){
            this(scopeId,occurredAt,entries,null);
        }
        public GameLogSnapshot(String scopeId,String occurredAt,List<RuntimeObserver.LogEntry> entries,String guiLanguage){
            this.scopeId=scopeId;this.occurredAt=occurredAt;this.guiLanguage=guiLanguage;
            this.entries=Collections.unmodifiableList(new ArrayList<>(entries));
            List<Map<String,Object>> lines=new ArrayList<>();
            for(RuntimeObserver.LogEntry entry:entries)lines.add(Collections.unmodifiableMap(map(
                    "text",TextProvenance.INSTANCE.capture(entry,entry.text,entry.clipped),"color",entry.color,"clipped",entry.clipped)));
            this.frozen=Collections.unmodifiableMap(map("format","display_snapshot_v2","occurred_at",occurredAt,"gui_language",guiLanguage,
                    "entries",Collections.unmodifiableList(lines)));
        }
        public Map<String,Object> data(){return frozen;}
        public Map<String,Object> originalData(){
            List<Map<String,Object>> lines=new ArrayList<>();
            for(RuntimeObserver.LogEntry entry:entries)lines.add(map("text",entry.text,"color",entry.color,"clipped",entry.clipped));
            return map("format","display_snapshot_v2","occurred_at",occurredAt,"gui_language",guiLanguage,"entries",lines);
        }
    }
    public static final class BannerSnapshot {
        public final String scopeId;
        private final Map<String,Object> frozen;
        BannerSnapshot(String runId,String kind,Map<String,Object> appearance){
            scopeId="run:"+runId;
            frozen=PublicEnglishProjection.freeze(map("format","display_occurrence_v1","occurred_at",Instant.now().toString(),
                    "kind",kind,"appearance",appearance));
        }
        public Map<String,Object> data(){return frozen;}
    }
    public static final class FloatingSnapshot {
        public final String scopeId;
        private final Map<String,Object> original,frozen;
        public FloatingSnapshot(String runId,int depth,String mapContext,String text,boolean clipped,Map<String,Object> appearance) {
            scopeId="run:"+runId;
            Map<String,Object> entry=new LinkedHashMap<>(appearance);
            entry.put("text",text);entry.put("clipped",clipped);
            original=Collections.unmodifiableMap(map("format","display_snapshot_v1","occurred_at",Instant.now().toString(),
                    "depth",depth,"map_context",mapContext,"entries",Collections.singletonList(Collections.unmodifiableMap(entry))));
            frozen=PublicEnglishProjection.freeze(original);
        }
        public Map<String,Object> data(){return frozen;}
        public Map<String,Object> originalData(){return original;}
    }
    public static final class VisualSnapshot {
        public final String runId,scopeId,mapContext,occurredAt;
        public final int depth;
        public final long generation;
        private final Object levelIdentity;
        private final boolean presentationReady;
        public final List<VisualCue> cues;
        public VisualSnapshot(String runId,Object levelIdentity,int depth,String mapContext,long generation,String occurredAt,List<VisualCue> cues){
            this(runId,levelIdentity,depth,mapContext,generation,occurredAt,cues,true);
        }
        public VisualSnapshot(String runId,Object levelIdentity,int depth,String mapContext,long generation,String occurredAt,List<VisualCue> cues,boolean presentationReady){
            this.runId=runId;this.scopeId="run:"+runId;this.levelIdentity=levelIdentity;this.depth=depth;
            this.mapContext=mapContext;this.generation=generation;this.occurredAt=occurredAt;
            this.presentationReady=presentationReady;
            this.cues=Collections.unmodifiableList(new ArrayList<>(cues));
        }
        private List<Map<String,Object>> cueData(){
            List<Map<String,Object>> data=new ArrayList<>();
            for(VisualCue cue:cues){
                Map<String,Object> value=map("kind",cue.kind,"cell",cue.cell);
                if(cue.sourceCell!=null)value.put("source_cell",cue.sourceCell);
                if(cue.direction!=null)value.put("direction",cue.direction);
                if(cue.color!=null)value.put("color",cue.color);
                if(cue.opacity!=null)value.put("opacity",cue.opacity);
                if(cue.appearance!=null)value.put("appearance",cue.appearance);
                data.add(Collections.unmodifiableMap(value));
            }
            return Collections.unmodifiableList(data);
        }
        public Map<String,Object> data(){return Collections.unmodifiableMap(map("format","display_snapshot_v1","depth",depth,
                "map_context",mapContext,"occurred_at",occurredAt,"cues",cueData()));}
        public Map<String,Object> stateData(){return map("status","last_rendered","depth",depth,"map_context",mapContext,"cues",cueData());}
    }
    /** A sampled quantitative draw, kept separate from unsampled discrete visual events. */
    public static final class VisualMetricSnapshot {
        public static final int SAMPLE_PERIOD_MS=250;
        public final String runId,scopeId,mapContext,occurredAt;
        public final int depth;
        private final Object levelIdentity;
        private final long generation;
        private final Set<Object> visibleEpisodes;
        public final List<VisualMetric> metrics;
        VisualMetricSnapshot(VisualSnapshot visual,String occurredAt,List<VisualMetric> metrics,Set<Object> episodes){
            runId=visual.runId;scopeId=visual.scopeId;mapContext=visual.mapContext;depth=visual.depth;
            levelIdentity=visual.levelIdentity;generation=visual.generation;this.occurredAt=occurredAt;
            this.metrics=Collections.unmodifiableList(new ArrayList<>(metrics));
            Set<Object> identities=Collections.newSetFromMap(new IdentityHashMap<>());identities.addAll(episodes);
            visibleEpisodes=Collections.unmodifiableSet(identities);
        }
        public List<Map<String,Object>> metricData(){
            List<Map<String,Object>> values=new ArrayList<>();
            for(VisualMetric metric:metrics){
                Map<String,Object> value=map("kind",metric.kind,"cell",metric.cell,"rendered_particles",metric.renderedParticles);
                if(!metric.appearance.isEmpty())value.put("appearance",metric.appearance);
                values.add(Collections.unmodifiableMap(value));
            }
            return Collections.unmodifiableList(values);
        }
        public Map<String,Object> data(){return Collections.unmodifiableMap(map("format","sampled_display_snapshot_v1",
                "sample_period_ms",SAMPLE_PERIOD_MS,"depth",depth,"map_context",mapContext,"occurred_at",occurredAt,"metrics",metricData()));}
    }
    /** Exact current screen pixels with independently sampled public history. */
    private static final class ScreenSnapshot {
        static final int SAMPLE_PERIOD_MS=250;
        private static final Object NO_SURFACE=new Object();
        final VisualSnapshot visual;
        final WeakReference<Object> sceneIdentity,cameraIdentity;
        final String occurredAt;
        final List<Map<String,Object>> effects;
        final Set<Object> visibleEpisodes;
        ScreenSnapshot(VisualSnapshot visual,String occurredAt,List<ScreenEffect> observations){
            this.visual=visual;this.occurredAt=occurredAt;
            // The last public sample must not retain a destroyed scene graph or its camera.
            sceneIdentity=new WeakReference<>(surface(Game.instance==null?null:Game.scene()));
            cameraIdentity=new WeakReference<>(surface(Camera.main));
            List<Map<String,Object>> values=new ArrayList<>();
            Set<Object> episodes=Collections.newSetFromMap(new IdentityHashMap<>());
            for(ScreenEffect effect:observations){values.add(effect.publicData());episodes.add(effect.episode);}
            // Equal measured frames have a stable representation independent of callback iteration order.
            values.sort(Comparator.comparing(value->JsonCodec.encode(value)));
            effects=PublicEnglishProjection.freeze(values);
            visibleEpisodes=Collections.unmodifiableSet(episodes);
        }
        boolean sameSurface(ScreenSnapshot other){
            return other!=null&&visual.mapContext.equals(other.visual.mapContext)
                    &&sceneIdentity.get()!=null&&cameraIdentity.get()!=null
                    &&sceneIdentity.get()==other.sceneIdentity.get()&&cameraIdentity.get()==other.cameraIdentity.get();
        }
        boolean current(VisualSnapshot rendered){
            return rendered!=null&&visual.generation==rendered.generation
                    &&visual.levelIdentity==rendered.levelIdentity&&visual.depth==rendered.depth
                    &&visual.runId.equals(rendered.runId)&&visual.mapContext.equals(rendered.mapContext)
                    &&sceneIdentity.get()==surface(Game.instance==null?null:Game.scene())&&cameraIdentity.get()==surface(Camera.main);
        }
        private static Object surface(Object value){return value==null?NO_SURFACE:value;}
        Map<String,Object> data(){return map("format","sampled_display_snapshot_v1","sample_period_ms",SAMPLE_PERIOD_MS,
                "depth",visual.depth,"map_context",visual.mapContext,"occurred_at",occurredAt,"screen_effects",effects);}
    }
    /** An immutable occurrence in the single render-callback FIFO. Type adapters retain existing test APIs. */
    public static final class DisplayEvent {
        public final String scopeId,kind;
        private final Object snapshot;
        private final Map<String,Object> publicData,originalData;
        private DisplayEvent(String scopeId,String kind,Object snapshot){
            this.scopeId=scopeId;this.kind=kind;this.snapshot=snapshot;
            publicData=freezeLiteral(snapshotData(snapshot));originalData=freezeLiteral(snapshotOriginal(snapshot));
        }
        /** New display surfaces use the same persistence path without adding another parallel queue. */
        public DisplayEvent(String scopeId,String kind,Map<String,Object> data,Map<String,Object> original){
            if(scopeId==null||!scopeId.startsWith("run:")||kind==null||!kind.startsWith("game."))
                throw new IllegalArgumentException("Display events require a run scope and game event kind");
            this.scopeId=scopeId;this.kind=kind;snapshot=null;
            publicData=freezeLiteral(PublicEnglishProjection.freeze(data));originalData=freezeLiteral(original);
        }
        public static DisplayEvent from(Object snapshot){
            if(snapshot instanceof GameLogSnapshot)return new DisplayEvent(((GameLogSnapshot)snapshot).scopeId,"game.log",snapshot);
            if(snapshot instanceof FloatingSnapshot)return new DisplayEvent(((FloatingSnapshot)snapshot).scopeId,"game.floating_text",snapshot);
            if(snapshot instanceof VisualSnapshot)return new DisplayEvent(((VisualSnapshot)snapshot).scopeId,"game.visual",snapshot);
            if(snapshot instanceof VisualMetricSnapshot)return new DisplayEvent(((VisualMetricSnapshot)snapshot).scopeId,"game.visual_metrics",snapshot);
            if(snapshot instanceof BannerSnapshot)return new DisplayEvent(((BannerSnapshot)snapshot).scopeId,"game.banner",snapshot);
            throw new IllegalArgumentException("Unsupported display snapshot");
        }
        private static Map<String,Object> snapshotData(Object snapshot){
            if(snapshot instanceof GameLogSnapshot)return ((GameLogSnapshot)snapshot).data();
            if(snapshot instanceof FloatingSnapshot)return ((FloatingSnapshot)snapshot).data();
            if(snapshot instanceof VisualSnapshot)return ((VisualSnapshot)snapshot).data();
            if(snapshot instanceof VisualMetricSnapshot)return ((VisualMetricSnapshot)snapshot).data();
            if(snapshot instanceof BannerSnapshot)return ((BannerSnapshot)snapshot).data();
            throw new IllegalArgumentException("Unsupported display snapshot");
        }
        private static Map<String,Object> snapshotOriginal(Object snapshot){
            if(snapshot instanceof GameLogSnapshot)return ((GameLogSnapshot)snapshot).originalData();
            if(snapshot instanceof FloatingSnapshot)return ((FloatingSnapshot)snapshot).originalData();
            return null;
        }
        public Map<String,Object> data(){return publicData;}
        public Map<String,Object> originalData(){return originalData;}
        @SuppressWarnings("unchecked") private static <T> T freezeLiteral(T value){
            if(value instanceof Map){Map<String,Object> copy=new LinkedHashMap<>();((Map<String,Object>)value).forEach((key,item)->copy.put(key,freezeLiteral(item)));return (T)Collections.unmodifiableMap(copy);}
            if(value instanceof List){List<Object> copy=new ArrayList<>();for(Object item:(List<?>)value)copy.add(freezeLiteral(item));return (T)Collections.unmodifiableList(copy);}
            return value;
        }
    }
    /** A wire response may become ready while the original continuous action still runs. */
    public static final class Execution {
        public final CompletableFuture<State> firstResponse;
        public final CompletableFuture<State> completion;
        public volatile boolean interrupted;
        private volatile boolean acknowledged;
        public Execution(CompletableFuture<State> firstResponse,CompletableFuture<State> completion){
            this.firstResponse=firstResponse;this.completion=completion;
        }
        public void acknowledge(){acknowledged=true;}
        public boolean acknowledged(){return acknowledged;}
    }
    private static final class Work {
        final String version; final Map<String,Object> args;
        final String requestId;
        String originScopeId;
        final Execution execution=new Execution(new CompletableFuture<>(),new CompletableFuture<>());
        final CompletableFuture<State> result=execution.completion;
        String activityVersion;
        String activityKind;
        int startCell=-1;
        boolean startedInRun;
        boolean movementObserved;
        Work(String version,Map<String,Object> args,String requestId){
            this.version=version;this.args=args;this.requestId=requestId;
            result.whenComplete((state,error)->{if(error==null)execution.firstResponse.complete(state);else execution.firstResponse.completeExceptionally(error);});
        }
    }
    private static final class CancelControl {
        final String kind,version,target;
        final CompletableFuture<State> result=new CompletableFuture<>();
        CancelControl(String kind,String version,String target){this.kind=kind;this.version=version;this.target=target;}
    }
    private final String menuScope, epoch=UUID.randomUUID().toString();
    private final GameSnapshotter snapshotter;
    private final UiBridge ui=new UiBridge();
    private final ConcurrentLinkedQueue<Work> queue=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SaveResult> saves=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RunOutcome> outcomes=new ConcurrentLinkedQueue<>();
    private final ArrayDeque<DisplayEvent> displayEvents=new ArrayDeque<>();
    private Runnable displaySignal=()->{};
    private boolean displayCaptureClosed;
    private final Map<String,Object> lastGameLogByRun=new HashMap<>();
    private final ConcurrentLinkedQueue<CancelControl> cancellations=new ConcurrentLinkedQueue<>();
    private final Consumer<Throwable> errors;
    private final ArrayList<Runnable> afterHandoff=new ArrayList<>();
    private volatile State latest;
    private volatile VisualSnapshot renderedVisual;
    private volatile VisualMetricSnapshot renderedMetrics;
    private VisualMetricSnapshot publishedMetrics;
    private long lastMetricSampleNanos;
    private volatile ScreenSnapshot renderedScreen;
    private ScreenSnapshot publishedScreen;
    private long lastScreenSampleNanos;
    private volatile boolean disposed, exiting;
    private volatile Throwable closureReason;
    private volatile String plannedRun;
    private volatile Map<String,Object> runOutcome;
    // Published before perform: native saves can complete synchronously or on the Interlevel worker.
    private volatile Work executing;
    private CancelControl cancellationLease;
    private CompletableFuture<State> cancellationCompletion;
    private boolean failedExecutionHold;
    private long frame, executedFrame, revision;
    private long activityGeneration;
    private long drawGeneration,stableAfterDraw=-1,stableInputGeneration;
    private Object stableLevel, stableMenuScene;
    private long stableMenuFrame, stableMenuInput;
    private String stableRun;
    private int stableDepth;
    private boolean stableBlocked;
    private String signature;

    public GameController(Path profile,String menuScope,Consumer<Throwable> errors) {
        this.menuScope=menuScope; this.snapshotter=new GameSnapshotter(profile); this.errors=errors;
    }
    public CompletableFuture<State> observe() {
        Work work=new Work(null,null,null); enqueue(work); return work.result;
    }
    public CompletableFuture<State> execute(String version,Map<String,Object> args) {
        return start(version,args,null).completion;
    }
    public Execution start(String version,Map<String,Object> args,String requestId){
        Work work=new Work(version,args,requestId);enqueue(work);return work.execution;
    }
    private synchronized void enqueue(Work work) {
        if(disposed)work.result.completeExceptionally(work.args==null?closureReason:new NotExecuted("ENGINE_ERROR",closureReason));
        else queue.add(work);
    }
    private synchronized void enqueue(CancelControl control) {
        if(disposed)control.result.completeExceptionally("commit".equals(control.kind)?new NotExecuted("ENGINE_ERROR",closureReason):closureReason);
        else cancellations.add(control);
    }
    public CompletableFuture<State> prepareCancellation(String version,String target){
        CancelControl control=new CancelControl("prepare",version,target);enqueue(control);return control.result;
    }
    public CompletableFuture<State> cancelPrepared(String version,String target){
        CancelControl control=new CancelControl("commit",version,target);enqueue(control);return control.result;
    }
    public void abortCancellation(String version){enqueue(new CancelControl("abort",version,null));}
    public State latest(){return latest;}
    public boolean exiting(){return exiting;}
    public boolean disposed(){return disposed;}
    public SaveResult pollSave(){return saves.poll();}
    public RunOutcome pollRunOutcome(){return outcomes.poll();}
    public synchronized GameLogSnapshot pollGameLog(){return pollDisplayType(GameLogSnapshot.class);}
    public synchronized FloatingSnapshot pollFloatingText(){return pollDisplayType(FloatingSnapshot.class);}
    public synchronized BannerSnapshot pollBanner(){return pollDisplayType(BannerSnapshot.class);}
    public synchronized VisualSnapshot pollVisual(){return pollDisplayType(VisualSnapshot.class);}
    public synchronized VisualMetricSnapshot pollVisualMetrics(){return pollDisplayType(VisualMetricSnapshot.class);}
    public synchronized List<VisualSnapshot> takeVisuals(){return takeDisplayType(VisualSnapshot.class);}
    public synchronized List<VisualMetricSnapshot> takeVisualMetrics(){return takeDisplayType(VisualMetricSnapshot.class);}
    public synchronized List<FloatingSnapshot> takeFloatingTexts(){return takeDisplayType(FloatingSnapshot.class);}
    public synchronized List<BannerSnapshot> takeBanners(){return takeDisplayType(BannerSnapshot.class);}
    public synchronized List<GameLogSnapshot> takeGameLogs(){return takeDisplayType(GameLogSnapshot.class);}
    private <T> T pollDisplayType(Class<T> type){
        for(Iterator<DisplayEvent> iterator=displayEvents.iterator();iterator.hasNext();){
            Object value=iterator.next().snapshot;if(type.isInstance(value)){iterator.remove();return type.cast(value);}
        }
        return null;
    }
    private <T> List<T> takeDisplayType(Class<T> type){
        List<T> batch=new ArrayList<>();T value;while((value=pollDisplayType(type))!=null)batch.add(value);return batch;
    }
    /** Called under the render callback lock; the listener may only schedule nonblocking work. */
    public synchronized void enqueueDisplayEvent(DisplayEvent event){
        if(displayCaptureClosed)return;
        displayEvents.addLast(Objects.requireNonNull(event));displaySignal.run();
    }
    public synchronized void setDisplaySignal(Runnable signal){
        displaySignal=signal==null?()->{}:signal;
        if(!displayCaptureClosed&&!displayEvents.isEmpty())displaySignal.run();
    }
    public synchronized boolean hasDisplayEvents(){return !displayEvents.isEmpty();}
    /** Peek then acknowledge only after the whole paired database transaction commits. */
    public synchronized List<DisplayEvent> peekDisplayEvents(int limit){
        if(limit<1)throw new IllegalArgumentException("A positive display batch limit is required");
        List<DisplayEvent> batch=new ArrayList<>();
        for(DisplayEvent event:displayEvents){if(batch.size()==limit)break;batch.add(event);}
        return Collections.unmodifiableList(batch);
    }
    public synchronized void acknowledgeDisplayEvents(List<DisplayEvent> batch){
        Iterator<DisplayEvent> queued=displayEvents.iterator();
        for(DisplayEvent event:batch)if(!queued.hasNext()||queued.next()!=event)throw new IllegalStateException("Display batch is not the queued prefix");
        for(int i=0;i<batch.size();i++)displayEvents.removeFirst();
    }
    /** Session teardown freezes a finite final cut; already captured events remain queued until committed. */
    public synchronized void freezeDisplayEvents(){displayCaptureClosed=true;displaySignal=()->{};}
    public void prepareRun(String id){plannedRun=id;}
    public void exitNow(){if(Gdx.app!=null) Gdx.app.postRunnable(()->Gdx.app.exit());}

    @Override public boolean exitRequested(){exiting=true;return false;}
    @Override public void onException(Throwable error){errors.accept(error);}
    @Override public void onSave(String runId,int slot,Throwable error){
        Work owner=executing;
        String requestId=owner==null?null:owner.requestId;
        saves.add(new SaveResult(UUID.randomUUID().toString(),runId,slot,error,Instant.now().toString(),
                requestId==null?null:owner.originScopeId,requestId));
    }
    @Override public void onRunEnded(String runId,boolean won){
        if(runId==null)return;
        RunOutcome outcome=new RunOutcome(runId,won);runOutcome=Collections.unmodifiableMap(outcome.data());outcomes.add(outcome);
    }
    @Override public synchronized void onGameLog(String runId,List<RuntimeObserver.LogEntry> entries){
        if(runId==null)return;
        List<RuntimeObserver.LogEntry> copy=Collections.unmodifiableList(new ArrayList<>(entries));
        com.shatteredpixel.shatteredpixeldungeon.messages.Languages language=
                Boolean.TRUE.equals(new ClassInitializationProbe().initialized(com.shatteredpixel.shatteredpixeldungeon.messages.Messages.class))
                        ? com.shatteredpixel.shatteredpixeldungeon.messages.Messages.selectedLanguage() : null;
        GameLogSnapshot snapshot=new GameLogSnapshot("run:"+runId,Instant.now().toString(),copy,language==null?null:language.code());
        Object capturedEntries=map("gui_language",snapshot.guiLanguage,"entries",snapshot.data().get("entries"));
        if(capturedEntries.equals(lastGameLogByRun.get(runId)))return;
        lastGameLogByRun.put(runId,capturedEntries);
        enqueueDisplayEvent(DisplayEvent.from(snapshot));
    }
    @Override public boolean observesVisualCues(){return true;}
    @Override public synchronized void onBanner(String runId,String kind,Map<String,Object> appearance){
        if(runId==null||!Arrays.asList("boss_slain","game_over").contains(kind))return;
        enqueueDisplayEvent(DisplayEvent.from(new BannerSnapshot(runId,kind,appearance)));
    }
    @Override public synchronized void onFloatingText(String runId,Object levelIdentity,int depth,
            String text,boolean clipped,Map<String,Object> appearance) {
        VisualSnapshot rendered=renderedVisual;
        if(rendered==null||!rendered.runId.equals(runId)||rendered.levelIdentity!=levelIdentity||rendered.depth!=depth)return;
        enqueueDisplayEvent(DisplayEvent.from(new FloatingSnapshot(runId,depth,rendered.mapContext,text,clipped,appearance)));
    }
    @Override public synchronized void onVisualCues(String runId,Object levelIdentity,int depth,List<VisualCue> cues,boolean presentationReady){
        if(runId==null||levelIdentity==null)return;
        TreeSet<VisualCue> normalized=new TreeSet<>(Comparator.comparingInt((VisualCue cue)->cue.cell)
                .thenComparing(cue->cue.kind)
                .thenComparing(cue->cue.sourceCell,Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(cue->cue.direction,Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(cue->cue.color,Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(cue->cue.opacity,Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(cue->cue.appearance==null?"":JsonCodec.encode(cue.appearance)));
        normalized.addAll(cues);
        List<VisualCue> copy=new ArrayList<>(normalized);
        VisualSnapshot previous=renderedVisual;
        boolean sameMap=previous!=null&&previous.levelIdentity==levelIdentity&&previous.depth==depth&&previous.runId.equals(runId);
        String context=sameMap?previous.mapContext:UUID.randomUUID().toString();
        boolean changed=!sameMap||!visualEventMeaning(copy).equals(visualEventMeaning(previous.cues));
        VisualSnapshot snapshot=new VisualSnapshot(runId,levelIdentity,depth,context,++drawGeneration,
                changed?Instant.now().toString():previous.occurredAt,copy,presentationReady);
        renderedVisual=snapshot;
        if(changed)enqueueDisplayEvent(DisplayEvent.from(snapshot));
    }
    /** Current frames retain exact tints; only documented decorative color cycles are event-deduplicated. */
    private static List<Map<String,Object>> visualEventMeaning(List<VisualCue> cues){
        List<Map<String,Object>> meaning=new ArrayList<>();
        for(VisualCue cue:cues){
            Map<String,Object> appearance=cue.appearance;
            if("sprite_state_appearance".equals(cue.kind)&&appearance!=null
                    &&Arrays.asList("golden_glow","icy","prismatic_cycle").contains(appearance.get("tint_style"))){
                appearance=new LinkedHashMap<>(appearance);appearance.remove("tint");
            }
            meaning.add(map("kind",cue.kind,"cell",cue.cell,"source_cell",cue.sourceCell,"direction",cue.direction,
                    "color",cue.color,"opacity",cue.opacity,"appearance",appearance));
        }
        return meaning;
    }
    @Override public void onEmitterDraw(com.watabou.noosa.particles.Emitter source,Object episode){
        GameScene.observeEmitterMetricDraw(source,episode);
    }
    @Override public synchronized void onVisualMetrics(String runId,Object levelIdentity,int depth,List<VisualMetric> metrics,Set<Object> episodes){
        recordVisualMetrics(runId,levelIdentity,depth,metrics,episodes,System.nanoTime(),Instant.now().toString());
    }
    synchronized void recordVisualMetrics(String runId,Object levelIdentity,int depth,List<VisualMetric> metrics,Set<Object> episodes,long now,String occurredAt){
        VisualSnapshot visual=renderedVisual;
        if(visual==null||visual.levelIdentity!=levelIdentity||visual.depth!=depth||!Objects.equals(visual.runId,runId))return;
        List<VisualMetric> copy=new ArrayList<>(metrics);
        copy.sort(Comparator.comparing((VisualMetric value)->value.kind).thenComparingInt(value->value.cell).thenComparing(value->value.appearance.toString()));
        VisualMetricSnapshot previous=renderedMetrics;
        boolean sameMap=previous!=null&&previous.mapContext.equals(visual.mapContext);
        VisualMetricSnapshot snapshot=new VisualMetricSnapshot(visual,occurredAt,copy,episodes);renderedMetrics=snapshot;
        boolean presenceChanged=sameMap&&!previous.visibleEpisodes.equals(snapshot.visibleEpisodes);
        boolean changed=publishedMetrics==null||!snapshot.mapContext.equals(publishedMetrics.mapContext)||!copy.equals(publishedMetrics.metrics);
        boolean publish=!sameMap?(!copy.isEmpty()||previous!=null&&!previous.metrics.isEmpty())
                :changed&&(presenceChanged||now-lastMetricSampleNanos>=VisualMetricSnapshot.SAMPLE_PERIOD_MS*1_000_000L);
        if(publish){enqueueDisplayEvent(DisplayEvent.from(snapshot));publishedMetrics=snapshot;lastMetricSampleNanos=now;}
    }
    @Override public synchronized void onScreenEffects(String runId,Object levelIdentity,int depth,List<ScreenEffect> effects){
        recordScreenEffects(runId,levelIdentity,depth,effects,System.nanoTime(),Instant.now().toString());
    }
    synchronized void recordScreenEffects(String runId,Object levelIdentity,int depth,List<ScreenEffect> effects,long now,String occurredAt){
        VisualSnapshot visual=renderedVisual;
        if(visual==null||visual.levelIdentity!=levelIdentity||visual.depth!=depth||!Objects.equals(visual.runId,runId))return;
        ScreenSnapshot previous=renderedScreen;
        ScreenSnapshot snapshot=new ScreenSnapshot(visual,occurredAt,effects);
        renderedScreen=snapshot;
        boolean sameSurface=snapshot.sameSurface(previous);
        boolean presenceChanged=sameSurface&&!previous.visibleEpisodes.equals(snapshot.visibleEpisodes);
        boolean changed=publishedScreen==null?!snapshot.effects.isEmpty()
                :!snapshot.sameSurface(publishedScreen)||!snapshot.effects.equals(publishedScreen.effects);
        boolean publish=!sameSurface?(!snapshot.effects.isEmpty()||previous!=null&&!previous.effects.isEmpty())
                :presenceChanged||changed&&now-lastScreenSampleNanos>=ScreenSnapshot.SAMPLE_PERIOD_MS*1_000_000L;
        if(publish){
            enqueueDisplayEvent(new DisplayEvent(visual.scopeId,"game.screen_visual",snapshot.data(),null));
            publishedScreen=snapshot;lastScreenSampleNanos=now;
        }
    }
    @Override public String onTextResource(String text,String key,String language,Object[] arguments) {
        return TextProvenance.INSTANCE.onTextResource(text,key,language,arguments);
    }
    @Override public String onTextResource(String text,String key,String language,Object[] arguments,String guiTemplate) {
        return TextProvenance.INSTANCE.onTextResource(text,key,language,arguments,guiTemplate);
    }
    @Override public String onTextOperation(String operation,String text,Object... operands) {
        return TextProvenance.INSTANCE.onTextOperation(operation,text,operands);
    }
    @Override public void onTextBound(Object owner,String text) { TextProvenance.INSTANCE.onTextBound(owner,text); }
    @Override public void onTextReleased(Object owner) { TextProvenance.INSTANCE.onTextReleased(owner); }
    /** A launch/runtime failure must release waiting requests before transport teardown. */
    public void runtimeFailed(Throwable error) { closeWith(Objects.requireNonNull(error)); }
    @Override public void onDispose(){ closeWith(new ProtocolException("SESSION_CLOSED","Game session closed")); }
    private synchronized void closeWith(Throwable error){
        if(disposed)return;
        closureReason=error;
        disposed=true;
        TextProvenance.INSTANCE.clear();
        if(executing!=null)executing.result.completeExceptionally(error);
        Work work;
        while((work=queue.poll())!=null)work.result.completeExceptionally(work.args==null?error:new NotExecuted("ENGINE_ERROR",error));
        CancelControl control;
        while((control=cancellations.poll())!=null)control.result.completeExceptionally("commit".equals(control.kind)?new NotExecuted("ENGINE_ERROR",error):error);
        if(cancellationCompletion!=null)cancellationCompletion.completeExceptionally(error);
    }

    private boolean runActive(){
        return (Game.scene() instanceof GameScene || Game.scene() instanceof AmuletScene
                || Game.scene() instanceof SurfaceScene || Game.scene() instanceof InterlevelScene
                || Game.scene() instanceof AlchemyScene)
                && Dungeon.hero!=null && GamesInProgress.curSlot>0;
    }
    private boolean stable(){
        if(Game.instance==null||Game.scene()==null||Game.switchingScene()||Game.hasPendingCallbacks()||hasPendingEffects(Game.scene()))return false;
        if(Game.scene() instanceof InterlevelScene)return ((InterlevelScene)Game.scene()).awaitingUserInput();
        if(!(Game.scene() instanceof GameScene))return true;
        Hero h=Dungeon.hero;
        if(h==null)return false;
        // A saved death choice can be rendered before the scheduler has ever existed.
        // Living heroes still need the original Actor handoff; stopped workers are excluded.
        if(!Actor.isYielded()&&!(GameScene.actorThreadNotStarted()&&!aliveAtBoundary(h)))return false;
        if(h.sprite!=null&&h.sprite.isMoving)return false;
        return GameScene.interfaceBlockingHero()||!aliveAtBoundary(h)||(h.ready&&h.curAction==null&&!h.resting);
    }
    private boolean activityStillRunning(){
        if(!(Game.scene() instanceof GameScene)||executing==null||executing.requestId==null||!executing.startedInRun)return false;
        Hero hero=Dungeon.hero;
        if(hero==null||!aliveAtBoundary(hero))return false;
        if(hero.resting)return true;
        if(hero.curAction==null||"move.step".equals(executing.args.get("action")))return false;
        if(hero.pos!=executing.startCell)executing.movementObserved=true;
        return executing.movementObserved;
    }
    private boolean continuousHandoff(){
        if(!activityStillRunning())return false;
        Hero hero=Dungeon.hero;
        if(hero.resting)return Actor.yieldedBy(hero)&&(hero.sprite==null||!hero.sprite.isMoving);
        if(hero.sprite!=null&&!hero.sprite.looping())return false;
        return Actor.isMotionHandoff()||(Actor.yieldedBy(hero)&&(hero.sprite==null||!hero.sprite.isMoving));
    }
    @Override public boolean beforeActorResume(){
        if(failedExecutionHold||cancellationLease!=null)return false;
        if(executing!=null&&executing.activityVersion!=null&&!executing.execution.acknowledged())return false;
        if(continuousHandoff()){
            if(executing.activityVersion==null)return false;
            for(CancelControl control:cancellations)if(control.kind.equals("prepare"))return false;
            Work query=queue.peek();if(query!=null&&query.args==null)return false;
        }
        return true;
    }
    @Override public void beforeSceneUpdate(){
        if(Game.scene() instanceof GameScene)GameScene.atActorHandoff(this::refreshMotionLease);
    }
    private void refreshMotionLease(){
        if(!Actor.isMotionHandoff())return;
        boolean hold=failedExecutionHold||cancellationLease!=null
                ||(executing!=null&&executing.activityVersion!=null&&!executing.execution.acknowledged());
        if(continuousHandoff()){
            hold|=executing.activityVersion==null;
            for(CancelControl control:cancellations)if(control.kind.equals("prepare"))hold=true;
            Work query=queue.peek();if(query!=null&&query.args==null)hold=true;
        }
        Actor.holdMotionHandoff(hold);
    }
    @Override public void afterDraw(){ui.captureDrawnEvidence();}
    @Override public void afterFrame(){
        frame++;
        try{
            if(Game.scene() instanceof GameScene)GameScene.atActorHandoff(()->{
                try{atBoundary();}finally{refreshMotionLease();}
            });
            else atBoundary();
        }finally{
            ArrayList<Runnable> ready=new ArrayList<>(afterHandoff);afterHandoff.clear();
            for(Runnable notification:ready)notification.run();
        }
    }
    private void atBoundary(){
        try {
            processCancellation();
            if(Game.instance==null||Game.scene()==null||Game.switchingScene()||Game.hasPendingCallbacks()||hasPendingEffects(Game.scene())){resetRenderedBoundary();return;}
            if(continuousHandoff()){
                resetRenderedBoundary();
                if(!ui.drawnIntentReady())return;
                if(executing.activityVersion==null){
                    executing.activityVersion="activity:"+epoch+":"+(++activityGeneration);
                    executing.activityKind=Dungeon.hero.resting?"rest":"travel";
                    State boundary=captureContinuous();
                    if(!auditReady(boundary.internalState))throw new ProtocolException("SNAPSHOT_INCOMPLETE","Continuous activity snapshot unavailable");
                    deliver(executing.execution.firstResponse,boundary);
                }
                if(cancellationLease!=null)return;
                Work query=queue.peek();
                if(query!=null&&query.args==null){queue.poll();deliver(query.result,captureContinuous());}
                return;
            }
            if(!stable()){resetRenderedBoundary();return;}
            if(!renderedBoundaryReady())return;
            if((executing!=null||!queue.isEmpty())&&!ui.drawnIntentReady())return;
            if(runActive() && Dungeon.runIdentityNeedsSave) Dungeon.saveAll();
            if(executing!=null && frame>executedFrame){
                State state=capture(true); Work done=executing;executing=null;deliver(done.result,state);
                if(cancellationCompletion!=null){deliver(cancellationCompletion,state);cancellationCompletion=null;}
            }
            if(executing!=null)return;
            Work work=queue.poll();
            if(work==null)return;
            boolean enteredGame=false;
            try {
                State before=capture(false);
                if(work.args==null){deliver(work.result,before);return;}
                if(!Objects.equals(work.version,before.version))
                    throw new ProtocolException("STALE_STATE","Observe the current state before acting");
                if(!auditReady(before.internalState))
                    throw new ProtocolException("SNAPSHOT_INCOMPLETE","Required audit snapshot is unavailable");
                validateAction(work.args);
                work.startedInRun=runActive();
                work.startCell=Dungeon.hero==null?-1:Dungeon.hero.pos;
                work.originScopeId=before.scopeId;
                executing=work;executedFrame=frame;
                resetRenderedBoundary();
                enteredGame=true;
                perform(work.args);
            }catch(Throwable error){
                reportAfterHandoff(error);
                if(enteredGame && Dungeon.hero!=null&&Dungeon.hero.resting)failedExecutionHold=true;
                if(executing==work)executing=null;
                reject(work.result,!enteredGame && work.args!=null
                        ?new NotExecuted(error instanceof ProtocolException?((ProtocolException)error).code:"INVALID_ARGUMENT",error):error);
            }
        }catch(Throwable error){
            reportAfterHandoff(error);
            if(executing!=null){failedExecutionHold=true;reject(executing.result,error);executing=null;}
            if(cancellationCompletion!=null){reject(cancellationCompletion,error);cancellationCompletion=null;}
            cancellationLease=null;
            Work work=queue.poll();if(work!=null)reject(work.result,error);
        }
    }
    private void processCancellation(){
        CancelControl control=cancellations.peek();
        if(control==null)return;
        if(control.kind.equals("abort")){
            cancellations.poll();
            if(cancellationLease!=null&&Objects.equals(control.version,cancellationLease.version))cancellationLease=null;
            deliver(control.result,null);return;
        }
        if(executing==null||!Objects.equals(control.target,executing.requestId)
                ||!Objects.equals(control.version,executing.activityVersion)||!(Game.scene() instanceof GameScene)){
            cancellations.poll();cancellationLease=null;
            reject(control.result,new NotExecuted("ACTIVITY_EXPIRED",null));return;
        }
        if(Game.switchingScene()||Game.hasPendingCallbacks()||hasPendingEffects(Game.scene()))return;
        if(!ui.drawnIntentReady())return;
        if(!continuousHandoff()){
            if(!activityStillRunning()){
                cancellations.poll();cancellationLease=null;
                reject(control.result,new NotExecuted("ACTIVITY_EXPIRED",null));
            }
            return;
        }
        cancellations.poll();
        boolean invoked=false;
        try {
            if(control.kind.equals("prepare")){
                if(cancellationLease!=null||cancellationCompletion!=null)throw new ProtocolException("CANCEL_IN_PROGRESS","Cancellation is already prepared");
                State before=captureContinuous();
                if(!auditReady(before.internalState))throw new ProtocolException("SNAPSHOT_INCOMPLETE","Cancellation snapshot unavailable");
                cancellationLease=control;
                deliver(control.result,before);
            }else{
                if(cancellationLease==null||!Objects.equals(cancellationLease.version,control.version)
                        ||!Objects.equals(cancellationLease.target,control.target))throw new ProtocolException("CANCEL_NOT_PREPARED","Cancellation has no valid preparation");
                // Caller has durably recorded the before-snapshot while no thread monitor was held.
                invoked=true;
                if(!GameScene.cancel())throw new ProtocolException("ACTIVITY_EXPIRED","Activity has already stopped");
                executing.execution.interrupted=true;
                cancellationCompletion=control.result;
                cancellationLease=null;
            }
        }catch(Throwable error){
            cancellationLease=null;
            if(invoked){
                failedExecutionHold=true;
                if(executing!=null){reject(executing.result,error);executing=null;}
                reject(control.result,error);
            }else reject(control.result,new NotExecuted(error instanceof ProtocolException?((ProtocolException)error).code:"CANCEL_UNAVAILABLE",error));
        }
    }
    private <T> void deliver(CompletableFuture<T> future,T value){afterHandoff.add(()->future.complete(value));}
    private void reject(CompletableFuture<?> future,Throwable error){afterHandoff.add(()->future.completeExceptionally(error));}
    private void reportAfterHandoff(Throwable error){afterHandoff.add(()->errors.accept(error));}
    private void resetRenderedBoundary(){stableAfterDraw=-1;stableLevel=null;stableRun=null;stableMenuScene=null;}
    /** Game.render draws before step: the first stable post-step state must survive a later full draw. */
    private boolean renderedBoundaryReady(){
        if(!(Game.scene() instanceof GameScene)){
            stableAfterDraw=-1;stableLevel=null;stableRun=null;
            Object scene=Game.scene();
            long input=Game.inputHandler==null?0:Game.inputHandler.interactionGeneration();
            if(stableMenuScene!=scene||stableMenuInput!=input){
                stableMenuScene=scene;stableMenuFrame=frame;stableMenuInput=input;return false;
            }
            // Game.render draws before step. The scene first seen after a switch has
            // not drawn yet; a later frame is required before reporting its controls.
            return scene!=null&&frame>stableMenuFrame;
        }
        stableMenuScene=null;
        VisualSnapshot rendered=renderedVisual;
        if(!matchesCurrentVisual(rendered)){resetRenderedBoundary();return false;}
        long input=Game.inputHandler==null?0:Game.inputHandler.interactionGeneration();
        boolean blocked=GameScene.interfaceBlockingHero()||GameScene.isSelectingCell();
        if(stableAfterDraw<0||stableLevel!=Dungeon.level||!Objects.equals(stableRun,Dungeon.runId)
                ||stableDepth!=Dungeon.depth||stableInputGeneration!=input||stableBlocked!=blocked){
            stableAfterDraw=rendered.generation;stableLevel=Dungeon.level;stableRun=Dungeon.runId;
            stableDepth=Dungeon.depth;stableInputGeneration=input;stableBlocked=blocked;return false;
        }
        return rendered.generation>stableAfterDraw&&rendered.presentationReady;
    }
    private boolean matchesCurrentVisual(VisualSnapshot rendered){
        return rendered!=null&&rendered.levelIdentity==Dungeon.level&&rendered.depth==Dungeon.depth&&Objects.equals(rendered.runId,Dungeon.runId);
    }
    private Map<String,Object> visualState(){
        VisualSnapshot rendered=renderedVisual;
        if(!matchesCurrentVisual(rendered))return map("status","not_rendered","map_context",null,"cues",Collections.emptyList());
        Map<String,Object> state=rendered.stateData();
        VisualMetricSnapshot metrics=renderedMetrics;
        if(metrics!=null&&metrics.levelIdentity==rendered.levelIdentity&&metrics.generation==rendered.generation
                &&metrics.mapContext.equals(rendered.mapContext)&&!metrics.metrics.isEmpty()){
            state.put("metrics",metrics.metricData());state.put("metrics_at",metrics.occurredAt);
        }
        ScreenSnapshot screen=renderedScreen;
        if(screen!=null&&screen.current(rendered)){
            state.put("screen_effects",screen.effects);state.put("screen_effects_at",screen.occurredAt);
        }
        return state;
    }
    private State captureContinuous(){
        State ordinary=capture(false);
        Map<String,Object> activity=map("kind",executing.activityKind,"target_id",executing.requestId,"state_version",executing.activityVersion);
        Map<String,Object> pub=new LinkedHashMap<>(ordinary.publicState);pub.put("continuous_activity",activity);
        Map<String,Object> internal=new LinkedHashMap<>(ordinary.internalState);internal.put("continuous_activity",activity);
        List<Map<String,Object>> actions=Collections.singletonList(map("action","action.cancel","target_id",executing.requestId,"state_version",executing.activityVersion));
        latest=new State(ordinary.scopeId,executing.activityVersion,"continuous_activity",pub,internal,actions,ordinary.runOutcome);
        return latest;
    }
    /** Rendering continues normally until finite gameplay continuations finish; callbacks are never forced. */
    static boolean hasPendingEffects(com.watabou.noosa.Gizmo node){
        if(node==null||!node.exists||!node.active)return false;
        if(node.hasPendingCallback())return true;
        if(node instanceof com.watabou.noosa.Group)
            for(com.watabou.noosa.Gizmo child:((com.watabou.noosa.Group)node).childrenSnapshot())
                if(hasPendingEffects(child))return true;
        return false;
    }
    private State capture(boolean forceNewVersion){
        GameSnapshotter.Capture captured=snapshotter.capture();
        Map<String,Object> pub=new LinkedHashMap<>(captured.publicState);
        boolean active=runActive();
        Map<String,Object> uiState=ui.frozenUi(active?captured.publicState:Collections.emptyMap());
        pub.put("ui",uiState);
        if(Game.scene() instanceof GameScene)pub.put("visual_cues",visualState());
        if(!active){pub.remove("hero");pub.remove("map");pub.remove("inventory");pub.remove("visible_entities");}
        String scope=active && Dungeon.runId!=null?"run:"+Dungeon.runId:menuScope;
        Map<String,Object> outcome=runOutcome;
        if(active&&outcome!=null&&!scope.equals(outcome.get("scope_id"))){runOutcome=null;outcome=null;}
        if(outcome!=null)pub.put("run_outcome",outcome);
        List<Map<String,Object>> actions=new ArrayList<>(ui.frozenActions());
        if(Game.scene() instanceof GameScene && Dungeon.hero!=null && Dungeon.hero.ready
                && !GameScene.interfaceBlockingHero() && !GameScene.isSelectingCell()){
            actions.add(map("action","move.step","parameters",map("direction",Arrays.asList("north","northeast","east","southeast","south","southwest","west","northwest"))));
            if(shortcut("wait")!=null)actions.add(map("action","wait"));
            if(shortcut("rest")!=null)actions.add(map("action","rest"));
            if(shortcut("examine")!=null)actions.add(map("action","search"));
            actions.add(map("action","game.save"));
            if(shortcut("inventory")!=null)actions.add(map("action","inventory.open","parameters",map("locator","current inventory locator")));
        }
        if(canQuit())actions.add(map("action","app.quit"));
        boolean choice=Boolean.TRUE.equals(uiState.get("modal"))||uiState.containsKey("item_prompt")
                ||(Game.scene() instanceof GameScene&&(GameScene.interfaceBlockingHero()||GameScene.isSelectingCell()));
        String phase=exiting?"closing":choice?"awaiting_input":Game.scene() instanceof GameScene
                ?(aliveAtBoundary(Dungeon.hero)?"player_ready":"ended")
                :Game.scene() instanceof InterlevelScene?"awaiting_input":"menu_ready";
        Map<String,Object> decisionState=new LinkedHashMap<>(pub);
        decisionState.remove("ui");
        // A marker fading or a particle flickering is display evidence, not another world action.
        decisionState.remove("visual_cues");
        long inputGeneration=Game.inputHandler==null?0:Game.inputHandler.interactionGeneration();
        String nextSignature=scope+"|"+phase+"|"+ui.intentSignature()+"|"+inputGeneration+"|"+JsonCodec.encode(PublicEnglishProjection.semantics(decisionState));
        if(forceNewVersion||!nextSignature.equals(signature)){revision++;signature=nextSignature;}
        String version=epoch+":"+revision;
        Map<String,Object> internal=new LinkedHashMap<>(captured.internalState);
        internal.put("interaction",uiState);
        if(outcome!=null)internal.put("run_outcome",outcome);
        latest=new State(scope,version,phase,pub,internal,actions,outcome);
        return latest;
    }
    private void requireHero(){
        if(!(Game.scene() instanceof GameScene)||Dungeon.hero==null||!Dungeon.hero.ready
                ||GameScene.interfaceBlockingHero()||GameScene.isSelectingCell())throw new ProtocolException("ACTION_UNAVAILABLE","Action unavailable in this context");
    }
    /** The same existing native quit gate is used for discovery, validation and execution. */
    private static boolean canQuit(){
        return !(Game.scene() instanceof GameScene)
                ||(!GameScene.interfaceBlockingHero()&&!GameScene.isSelectingCell());
    }
    private static void requireQuit(){
        if(!canQuit())throw new ProtocolException("ACTION_UNAVAILABLE","Close the current choice first");
    }
    static boolean aliveAtBoundary(Hero hero){
        if(hero.HP>0)return true;
        Berserk berserk=hero.buff(Berserk.class);
        Object state=berserk==null?null:SnapshotFields.read(berserk,"state");
        return state instanceof Enum && ((Enum<?>)state).name().equals("BERSERK")
                && PlayerObservation.displayedShield(hero)>0;
    }
    private String shortcut(String name){
        Object controls=ui.frozenUi().get("controls");
        if(controls instanceof List)for(Object value:(List<?>)controls){
            if(!(value instanceof Map))continue;
            Map<?,?> control=(Map<?,?>)value;
            if(Boolean.TRUE.equals(control.get("enabled")) && name.equals(control.get("shortcut_action")))
                return (String)control.get("id");
        }
        return null;
    }
    private static boolean auditReady(Map<String,Object> state){
        Object coverage=state.get("coverage"), files=state.get("profile_files"), random=state.get("random");
        if(coverage instanceof Map && "incomplete".equals(((Map<?,?>)coverage).get("status")))return false;
        if(files instanceof Map && !"captured_declared_scope".equals(((Map<?,?>)files).get("status")))return false;
        return !(random instanceof Map) || "captured".equals(((Map<?,?>)random).get("status"))
                || "not_initialized".equals(((Map<?,?>)random).get("reason"));
    }
    private void validateAction(Map<String,Object> args){
        Object name=args.get("action");
        if(!(name instanceof String))throw new ProtocolException("INVALID_ARGUMENT","action is required");
        String action=(String)name;
        if(action.equals("app.quit")){
            requireQuit();
            return;
        }
        if(Arrays.asList("move.step","wait","rest","search","game.save","inventory.open").contains(action)){
            requireHero();
            String key=action.equals("search")?"examine":action.equals("inventory.open")?"inventory":action;
            if(Arrays.asList("wait","rest","search","inventory.open").contains(action) && shortcut(key)==null)
                throw new ProtocolException("ACTION_UNAVAILABLE","The corresponding user control is unavailable");
            if(action.equals("move.step") && !Arrays.asList("north","northeast","east","southeast","south","southwest","west","northwest").contains(args.get("direction")))
                throw new ProtocolException("INVALID_ARGUMENT","Unknown direction");
            if(action.equals("inventory.open"))requireAvailableInventoryItem(args.get("locator"));
            return;
        }
        ui.validate(action,args);
    }
    private com.shatteredpixel.shatteredpixeldungeon.items.Item requireAvailableInventoryItem(Object locator){
        com.shatteredpixel.shatteredpixeldungeon.items.Item item=locator instanceof String?snapshotter.resolveItem((String)locator):null;
        if(!PlayerObservation.inventoryItemAvailable(Dungeon.hero,item))
            throw new ProtocolException("ACTION_UNAVAILABLE","Item is not available in the current inventory");
        return item;
    }
    private void perform(Map<String,Object> args)throws Exception{
        if(plannedRun!=null){Dungeon.prepareRunIdentity(plannedRun);plannedRun=null;}
        Object name=args.get("action");
        if(!(name instanceof String))throw new ProtocolException("INVALID_ARGUMENT","action is required");
        String action=(String)name;
        if(action.equals("app.quit")){
            requireQuit();
            if(Game.scene() instanceof GameScene){
                Dungeon.saveAll();Badges.saveGlobal();Journal.saveGlobal();
            }
            exiting=true;return;
        }
        if(action.equals("game.save")){requireHero();Dungeon.saveAll();return;}
        if(action.equals("inventory.open")){
            requireHero();
            com.shatteredpixel.shatteredpixeldungeon.items.Item item=requireAvailableInventoryItem(args.get("locator"));
            GameScene.show(new com.shatteredpixel.shatteredpixeldungeon.windows.WndUseItem(null,item));return;
        }
        if(action.equals("move.step")){
            requireHero();
            String direction=String.valueOf(args.get("direction"));
            int i=Arrays.asList("north","northeast","east","southeast","south","southwest","west","northwest").indexOf(direction);
            if(i<0)throw new ProtocolException("INVALID_ARGUMENT","Unknown direction");
            int[] dx={0,1,1,1,0,-1,-1,-1},dy={-1,-1,0,1,1,1,0,-1};
            Hero h=Dungeon.hero;int w=Dungeon.level.width();int x=h.pos%w+dx[i],y=h.pos/w+dy[i];
            if(x<0||x>=w||y<0||y>=Dungeon.level.height())throw new ProtocolException("INVALID_ARGUMENT","Cell outside map");
            if(!GameScene.moveStep(dx[i],dy[i]))throw new ProtocolException("ACTION_UNAVAILABLE","Direction input is unavailable");
            return;
        }
        if(action.equals("wait")||action.equals("rest")){
            ui.execute("ui.activate",map("control",shortcut(action),"gesture","click"));return;
        }
        if(action.equals("search")){
            ui.execute("ui.activate",map("control",shortcut("examine"),"gesture","long"));return;
        }
        ui.execute(action,args);
    }
}
