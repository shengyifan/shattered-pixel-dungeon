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
import com.watabou.noosa.VisualCue;
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
            this.publicState=publicState; this.internalState=internalState; this.actions=actions;
            this.runOutcome=runOutcome;
        }
        public Map<String,Object> result() {
            Map<String,Object> result=map("scope_id",scopeId,"state_version",version,"phase",phase,
                    "observation",publicState,"actions",actions);
            if(runOutcome!=null)result.put("run_outcome",runOutcome);
            return result;
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
        public final String scopeId,occurredAt;
        public final List<RuntimeObserver.LogEntry> entries;
        public GameLogSnapshot(String scopeId,String occurredAt,List<RuntimeObserver.LogEntry> entries){
            this.scopeId=scopeId;this.occurredAt=occurredAt;
            this.entries=Collections.unmodifiableList(new ArrayList<>(entries));
        }
        public Map<String,Object> data(){
            List<Map<String,Object>> lines=new ArrayList<>();
            for(RuntimeObserver.LogEntry entry:entries)lines.add(Collections.unmodifiableMap(map("text",entry.text,"color",entry.color,"clipped",entry.clipped)));
            return Collections.unmodifiableMap(map("format","display_snapshot_v1","occurred_at",occurredAt,
                    "entries",Collections.unmodifiableList(lines)));
        }
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
            for(VisualCue cue:cues)data.add(Collections.unmodifiableMap(map("kind",cue.kind,"cell",cue.cell)));
            return Collections.unmodifiableList(data);
        }
        public Map<String,Object> data(){return Collections.unmodifiableMap(map("format","display_snapshot_v1","depth",depth,
                "map_context",mapContext,"occurred_at",occurredAt,"cues",cueData()));}
        public Map<String,Object> stateData(){return map("status","last_rendered","depth",depth,"map_context",mapContext,"cues",cueData());}
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
    private final ConcurrentLinkedQueue<GameLogSnapshot> gameLogs=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<VisualSnapshot> visuals=new ConcurrentLinkedQueue<>();
    private final Map<String,List<RuntimeObserver.LogEntry>> lastGameLogByRun=new HashMap<>();
    private final ConcurrentLinkedQueue<CancelControl> cancellations=new ConcurrentLinkedQueue<>();
    private final Consumer<Throwable> errors;
    private final ArrayList<Runnable> afterHandoff=new ArrayList<>();
    private volatile State latest;
    private volatile VisualSnapshot renderedVisual;
    private volatile boolean disposed, exiting;
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
    private Object stableLevel;
    private String stableRun;
    private int stableDepth;
    private boolean stableBlocked;
    private String signature;

    public GameController(Path profile,String menuScope,Consumer<Throwable> errors) {
        this.menuScope=menuScope; this.snapshotter=new GameSnapshotter(profile); this.errors=errors;
    }
    public CompletableFuture<State> observe() {
        Work work=new Work(null,null,null); queue.add(work); return work.result;
    }
    public CompletableFuture<State> execute(String version,Map<String,Object> args) {
        return start(version,args,null).completion;
    }
    public Execution start(String version,Map<String,Object> args,String requestId){
        Work work=new Work(version,args,requestId);queue.add(work);return work.execution;
    }
    public CompletableFuture<State> prepareCancellation(String version,String target){
        CancelControl control=new CancelControl("prepare",version,target);cancellations.add(control);return control.result;
    }
    public CompletableFuture<State> cancelPrepared(String version,String target){
        CancelControl control=new CancelControl("commit",version,target);cancellations.add(control);return control.result;
    }
    public void abortCancellation(String version){cancellations.add(new CancelControl("abort",version,null));}
    public State latest(){return latest;}
    public boolean exiting(){return exiting;}
    public boolean disposed(){return disposed;}
    public SaveResult pollSave(){return saves.poll();}
    public RunOutcome pollRunOutcome(){return outcomes.poll();}
    public GameLogSnapshot pollGameLog(){return gameLogs.poll();}
    public VisualSnapshot pollVisual(){return visuals.poll();}
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
        if(copy.equals(lastGameLogByRun.get(runId)))return;
        lastGameLogByRun.put(runId,copy);
        gameLogs.add(new GameLogSnapshot("run:"+runId,Instant.now().toString(),copy));
    }
    @Override public boolean observesVisualCues(){return true;}
    @Override public synchronized void onVisualCues(String runId,Object levelIdentity,int depth,List<VisualCue> cues,boolean presentationReady){
        if(runId==null||levelIdentity==null)return;
        TreeSet<VisualCue> normalized=new TreeSet<>(Comparator.comparingInt((VisualCue cue)->cue.cell).thenComparing(cue->cue.kind));
        normalized.addAll(cues);
        List<VisualCue> copy=new ArrayList<>(normalized);
        VisualSnapshot previous=renderedVisual;
        boolean sameMap=previous!=null&&previous.levelIdentity==levelIdentity&&previous.depth==depth&&previous.runId.equals(runId);
        String context=sameMap?previous.mapContext:UUID.randomUUID().toString();
        boolean changed=!sameMap||!copy.equals(previous.cues);
        VisualSnapshot snapshot=new VisualSnapshot(runId,levelIdentity,depth,context,++drawGeneration,
                changed?Instant.now().toString():previous.occurredAt,copy,presentationReady);
        renderedVisual=snapshot;
        if(changed)visuals.add(snapshot);
    }
    @Override public void onDispose(){
        disposed=true;
        ProtocolException e=new ProtocolException("SESSION_CLOSED","Game session closed");
        if(executing!=null) executing.result.completeExceptionally(e);
        Work work; while((work=queue.poll())!=null)work.result.completeExceptionally(e);
        CancelControl control;while((control=cancellations.poll())!=null)control.result.completeExceptionally(e);
        if(cancellationCompletion!=null)cancellationCompletion.completeExceptionally(e);
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
    private void resetRenderedBoundary(){stableAfterDraw=-1;stableLevel=null;stableRun=null;}
    /** Game.render draws before step: the first stable post-step state must survive a later full draw. */
    private boolean renderedBoundaryReady(){
        if(!(Game.scene() instanceof GameScene)){resetRenderedBoundary();return true;}
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
        return matchesCurrentVisual(rendered)?rendered.stateData():map("status","not_rendered","map_context",null,"cues",Collections.emptyList());
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
        Map<String,Object> uiState=ui.describeUi();
        pub.put("ui",uiState);
        if(Game.scene() instanceof GameScene)pub.put("visual_cues",visualState());
        boolean active=runActive();
        if(!active){pub.remove("hero");pub.remove("map");pub.remove("inventory");pub.remove("visible_entities");}
        String scope=active && Dungeon.runId!=null?"run:"+Dungeon.runId:menuScope;
        Map<String,Object> outcome=runOutcome;
        if(active&&outcome!=null&&!scope.equals(outcome.get("scope_id"))){runOutcome=null;outcome=null;}
        if(outcome!=null)pub.put("run_outcome",outcome);
        List<Map<String,Object>> actions=new ArrayList<>(ui.describeActions());
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
        String nextSignature=scope+"|"+phase+"|"+ui.intentSignature()+"|"+inputGeneration+"|"+JsonCodec.encode(decisionState);
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
        Object controls=ui.describeUi().get("controls");
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
