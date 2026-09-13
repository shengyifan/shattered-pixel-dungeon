package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditException;
import com.shatteredpixel.shatteredpixeldungeon.control.game.GameController;
import com.shatteredpixel.shatteredpixeldungeon.control.game.CompactProtocol;
import com.shatteredpixel.shatteredpixeldungeon.control.game.PublicEnglishProjection;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Serial protocol processing and audit writes, with a separate logical lifetime for pending actions. */
public final class MachineSession implements AutoCloseable {
    /** Testing seam: no fake needs to initialize LibGDX or a game save. */
    public interface GamePort {
        GameController.State latest();
        CompletableFuture<GameController.State> observe();
        CompletableFuture<GameController.State> execute(String version, Map<String,Object> args);
        default GameController.Execution start(String version,Map<String,Object> args,String requestId){
            CompletableFuture<GameController.State> completion=execute(version,args);
            return new GameController.Execution(completion,completion);
        }
        default CompletableFuture<GameController.State> prepareCancellation(String version,String target){
            return CompletableFuture.failedFuture(new ProtocolException("CANCEL_UNAVAILABLE","Runtime does not support cancellation"));
        }
        default CompletableFuture<GameController.State> cancelPrepared(String version,String target){
            return CompletableFuture.failedFuture(new ProtocolException("CANCEL_UNAVAILABLE","Runtime does not support cancellation"));
        }
        default void abortCancellation(String version){}
        void prepareRun(String id);
        GameController.SaveResult pollSave();
        default GameController.RunOutcome pollRunOutcome(){return null;}
        default GameController.GameLogSnapshot pollGameLog(){return null;}
        default GameController.VisualSnapshot pollVisual(){return null;}
        boolean exiting();
        boolean disposed();
        void exitNow();
    }
    private static final List<String> HISTORY=Arrays.asList("request.get","history.list","events.read");
    private final AuditStore store;
    private final GamePort game;
    private final PrintStream output;
    private final long timeoutMillis;
    private final ExecutorService serial=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"SPD Audit and Protocol");t.setDaemon(true);return t;});
    private volatile boolean closed;
    private volatile boolean fatalFailure;
    private boolean executionUncertain;
    private Pending pending;
    private Pending cancelling;
    private static final class Pending {
        final AuditStore.Attempt attempt;
        final GameController.State before;
        CompletableFuture<GameController.State> future;
        GameController.Execution execution;
        GameController.State activity;
        Pending(AuditStore.Attempt attempt,GameController.State before){this.attempt=attempt;this.before=before;}
    }
    private static final class ResponseStage { boolean committed,written; }

    public MachineSession(AuditStore store,GameController game,PrintStream output){
        this(store,new GamePort(){
            public GameController.State latest(){return game.latest();}
            public CompletableFuture<GameController.State> observe(){return game.observe();}
            public CompletableFuture<GameController.State> execute(String v,Map<String,Object> a){return game.execute(v,a);}
            public GameController.Execution start(String v,Map<String,Object> a,String id){return game.start(v,a,id);}
            public CompletableFuture<GameController.State> prepareCancellation(String v,String target){return game.prepareCancellation(v,target);}
            public CompletableFuture<GameController.State> cancelPrepared(String v,String target){return game.cancelPrepared(v,target);}
            public void abortCancellation(String v){game.abortCancellation(v);}
            public void prepareRun(String id){game.prepareRun(id);}
            public GameController.SaveResult pollSave(){return game.pollSave();}
            public GameController.RunOutcome pollRunOutcome(){return game.pollRunOutcome();}
            public GameController.GameLogSnapshot pollGameLog(){return game.pollGameLog();}
            public GameController.VisualSnapshot pollVisual(){return game.pollVisual();}
            public boolean exiting(){return game.exiting();}
            public boolean disposed(){return game.disposed();}
            public void exitNow(){game.exitNow();}
        },output,30_000);
    }
    public MachineSession(AuditStore store,GamePort game,PrintStream output,long timeoutMillis){
        if(timeoutMillis<1)throw new IllegalArgumentException("A positive timeout is required");
        this.store=store;this.game=game;this.output=output;this.timeoutMillis=timeoutMillis;
    }
    /** Lifecycle status only: ordinary rejected requests do not fail the process session. */
    public boolean failed(){return fatalFailure;}
    /** Accept one complete NDJSON line; completion means its sole response has been handled. */
    public CompletableFuture<Void> accept(String raw){
        return accept(NdjsonReader.Frame.logical(raw));
    }
    public CompletableFuture<Void> accept(NdjsonReader.Frame frame){
        CompletableFuture<Void> done=new CompletableFuture<>();
        if(closed){done.completeExceptionally(new ProtocolException("SESSION_CLOSED","Session is closed"));return done;}
        try{serial.execute(()->{try{process(frame);done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});}
        catch(RejectedExecutionException e){done.completeExceptionally(e);}
        return done;
    }
    public void read(InputStream input){
        try(InputStream source=input){
            NdjsonReader reader=new NdjsonReader(source);
            NdjsonReader.Frame frame;
            while(!closed&&(frame=reader.next())!=null){accept(frame).get();if(game.exiting())return;}
            if(!closed)serial.submit(()->endInput("stdin_eof")).get();
        }catch(Throwable error){
            try{serial.submit(()->{recordExceptionNow(unwrap(error));endInput("input_failure");}).get();}
            catch(Throwable ignored){game.exitNow();}
        }
    }
    private void process(NdjsonReader.Frame frame){
        String processingStartedAt=Instant.now().toString();
        String raw=frame.text;
        ResponseStage responseStage=new ResponseStage();
        AuditStore.Attempt attempt=null;
        String id=null,scope=null,op=null;
        GameController.State state=game.latest();
        boolean currentCertified=false,dispatchAttempted=false,cancelRequest=false;
        String preparedLease=null;
        try{
            if(frame.error!=null)throw frame.error;
            Map<String,Object> envelope=JsonCodec.decode(raw);
            id=identity(envelope.get("id"),128);scope=identity(envelope.get("s"),256);op=identity(envelope.get("op"),128);
            if(envelope.get("s")==null&&"info".equals(op))scope=store.menuScope();
            attempt=store.begin(scope,id,op,raw,frame.bytes,frame.format,frame.receivedAt,processingStartedAt);
            if(attempt.duplicate)throw new ProtocolException("DUPLICATE_REQUEST_ID","Request ID already appeared in this scope");
            // Consume identity before validating args, operation or state_version.
            ControlRequest request=ControlRequest.parse(raw);
            op=request.op;
            if(scope==null)throw new ProtocolException("SCOPE_REQUIRED","s is required");
            if(!store.hasScope(scope))throw new ProtocolException("UNKNOWN_SCOPE","Unknown scope");
            settleReady();
            refreshActivity();
            cancelRequest="action.execute".equals(op)&&"action.cancel".equals(request.args.get("action"));
            boolean busy=pending!=null||cancelling!=null;
            if("action.execute".equals(op)&&!cancelRequest&&(busy||executionUncertain))
                throw new ProtocolException(executionUncertain?"EXECUTION_UNCERTAIN":"BUSY","No new action can be dispatched");
            if(cancelRequest&&executionUncertain)throw new ProtocolException("EXECUTION_UNCERTAIN","Execution outcome is uncertain");
            if(busy&&!executionUncertain&&cancelling==null&&pending!=null&&pending.activity!=null
                    &&Arrays.asList("state.get","actions.list").contains(op)){
                state=game.observe().get(timeoutMillis,TimeUnit.MILLISECONDS);currentCertified=true;
                if("continuous_activity".equals(state.phase))pending.activity=state;
                settleReady();
            }
            // History is independent of the live engine's observation and presentation health.
            if(!busy&&!executionUncertain&&!"protocol.info".equals(op)&&!HISTORY.contains(op)){
                state=game.observe().get(timeoutMillis,TimeUnit.MILLISECONDS);currentCertified=true;
            }
            if(state!=null&&state.scopeId.startsWith("run:"))store.ensureScope(state.scopeId,"run",state.scopeId.substring(4));
            if(!"protocol.info".equals(op)&&!HISTORY.contains(op)&&(state==null||!scope.equals(state.scopeId)))
                throw new ProtocolException("SCOPE_MISMATCH","Request scope is not active");
            Object result;String status="completed";
            switch(op){
                case "protocol.info":
                    result=map("cli_version","CLI.3.0.0","game_version","3.3.8",
                            "build_id",com.shatteredpixel.shatteredpixeldungeon.control.game.BuildCatalog.current().get("build_id"),
                            "session_id",store.sessionId(),"audit_schema_version",AuditStore.SCHEMA_VERSION,"text_language","en","text_format","resource-v1",
                            "scope_id",state==null?store.menuScope():state.scopeId,"menu_scope_id",store.menuScope(),
                            "state_version",busy||executionUncertain||state==null?null:state.version,
                            "capabilities",Arrays.asList("serial","request_ids","duplicate_rejection","player_observation","paired_audit","source_text","partial_presentation"),
                            "schema",CompactProtocol.info());break;
                case "state.get":result=publicStateResult(state);break;
                case "actions.list":result=pending!=null||cancelling!=null||executionUncertain?publicStateResult(state):actionState(state);break;
                case "request.get":
                    result=store.getRequest(scope,requiredIdentifier(request.args,"target_id"),request.details);
                    if(result==null)throw new ProtocolException("REQUEST_NOT_FOUND","Request not found");break;
                case "history.list":{
                    long until=number(request.args,"until",store.historyWatermark(scope));
                    result=page(store.history(scope,number(request.args,"after",0),limit(request.args)+1,until),limit(request.args),until);break;
                }
                case "events.read":{
                    drainSaves();long until=number(request.args,"until",store.eventsWatermark(scope));
                    result=page(store.events(scope,number(request.args,"after",0),limit(request.args)+1,until),limit(request.args),until);break;
                }
                case "action.execute":{
                    if(request.stateVersion==null)throw new ProtocolException("STATE_VERSION_REQUIRED","rev is required");
                    if(cancelRequest){
                        String target=requiredIdentifier(request.args,"target_id");
                        if(pending==null||pending.activity==null||!target.equals(pending.attempt.id)||!scope.equals(pending.attempt.scopeId))
                            throw new ProtocolException("ACTIVITY_NOT_ACTIVE","Target is not the active continuous request");
                        if(!request.stateVersion.equals(pending.activity.version))throw new ProtocolException("STALE_ACTIVITY","Activity token has expired");
                        if(cancelling!=null)throw new ProtocolException("CANCEL_IN_PROGRESS","Cancellation is already resolving");
                        preparedLease=request.stateVersion;
                        state=game.prepareCancellation(request.stateVersion,target).get(timeoutMillis,TimeUnit.MILLISECONDS);
                        currentCertified=true;
                        if(!scope.equals(state.scopeId)||!request.stateVersion.equals(state.version))throw new ProtocolException("STALE_ACTIVITY","Prepared boundary does not match the activity");
                        store.markExecuting(attempt,request.stateVersion,state.publicState,state.internalState);
                        dispatchAttempted=true;
                        CompletableFuture<GameController.State> cancelled=game.cancelPrepared(request.stateVersion,target);
                        try{state=cancelled.get(timeoutMillis,TimeUnit.MILLISECONDS);}
                        catch(TimeoutException waiting){
                            cancelling=new Pending(attempt,state);cancelling.future=cancelled;
                            drainSaves();
                            Map<String,Object> response=success(id,scope,"in_progress",withPersistence(attempt,
                                    map("phase","cancelling","state_version",null,"actions",Collections.emptyList()),state.scopeId));
                            Map<String,Object>[] snapshots=auditSnapshots(scope,state,false);
                            store.respondPending(attempt,response,snapshots[0],snapshots[1]);responseStage.committed=true;
                            send(attempt,response,responseStage);cancelled.whenComplete((after,error)->scheduleSettlement());return;
                        }
                        settleReady();
                        result=state.result();break;
                    }
                    if(!request.stateVersion.equals(state.version))throw new ProtocolException("STALE_STATE","Observe before acting");
                    requiredString(request.args,"action");String planned=null;
                    if(scope.equals(store.menuScope())){
                        planned=UUID.randomUUID().toString();store.ensureScope("run:"+planned,"planned",planned);store.linkTarget(attempt,"run:"+planned);
                    }
                    store.markExecuting(attempt,state.version,state.publicState,state.internalState);
                    // A synchronous throw after entering runtime code can also mean a partial operation.
                    dispatchAttempted=true;pending=new Pending(attempt,state);
                    if(planned!=null)game.prepareRun(planned);
                    pending.execution=game.start(request.stateVersion,request.args,id);
                    pending.future=pending.execution.completion;
                    try{
                        state=pending.execution.firstResponse.get(timeoutMillis,TimeUnit.MILLISECONDS);
                        if(!pending.future.isDone()&&"continuous_activity".equals(state.phase)){
                            pending.activity=state;
                            drainSaves();
                            Map<String,Object> response=success(id,scope,"in_progress",withPersistence(attempt,publicStateResult(state),state.scopeId));
                            store.respondPending(attempt,response,state.publicState,state.internalState);responseStage.committed=true;
                            send(attempt,response,responseStage);pending.execution.acknowledge();
                            pending.future.whenComplete((after,error)->scheduleSettlement());return;
                        }
                        state=pending.future.get(timeoutMillis,TimeUnit.MILLISECONDS);
                    }
                    catch(TimeoutException waiting){
                        drainSaves();
                        Map<String,Object> response=success(id,scope,"in_progress",withPersistence(attempt,publicStateResult(state),state==null?scope:state.scopeId));
                        Map<String,Object>[] snapshots=auditSnapshots(scope,state,false);
                        store.respondPending(attempt,response,snapshots[0],snapshots[1]);responseStage.committed=true;
                        send(attempt,response,responseStage);
                        pending.execution.acknowledge();
                        pending.future.whenComplete((after,error)->scheduleSettlement());return;
                    }
                    pending=null;currentCertified=true;
                    if(state.scopeId.startsWith("run:"))store.ensureScope(state.scopeId,"run",state.scopeId.substring(4));
                    status="awaiting_input".equals(state.phase)?"awaiting_input":"completed";result=state.result();break;
                }
                default:throw new ProtocolException("UNKNOWN_OPERATION","Unknown operation");
            }
            // Save callback receipts must be durable before the response that reports them.
            drainSaves();
            if("action.execute".equals(op))result=withPersistence(attempt,result,state==null?scope:state.scopeId);
            else if("state.get".equals(op)||"actions.list".equals(op))result=withLastSave(result,scope);
            Map<String,Object> response=CompactProtocol.success(id,scope,status,PublicEnglishProjection.copy(result),!HISTORY.contains(op),request.sources);
            Map<String,Object>[] snapshots=dispatchAttempted&&currentCertified?directSnapshots(state):auditSnapshots(scope,state,currentCertified);
            store.complete(attempt,status.toUpperCase(Locale.ROOT),response,snapshots[0],snapshots[1],null,currentCertified&&state!=null?state.scopeId:null);responseStage.committed=true;
            send(attempt,response,responseStage);if(game.exiting())game.exitNow();
        }catch(Throwable thrown){
            Throwable error=unwrap(thrown);
            boolean definitelyNotExecuted=error instanceof GameController.NotExecuted;
            if(dispatchAttempted){if(!definitelyNotExecuted)executionUncertain=true;if(!cancelRequest)pending=null;}
            if(responseStage.committed||responseStage.written){fatal(error);return;}
            if(error instanceof AuditException){
                responseStage.written=true;output.println(JsonCodec.encode(failure(id,scope,"AUDIT_UNAVAILABLE")));output.flush();
                fatal(error);return;
            }
            try{
                if(attempt==null)attempt=store.begin(scope,id,op,raw,frame.bytes,frame.format,frame.receivedAt,processingStartedAt);
                // Receipts must remain durable, but another queued display failure
                // must not replace this request's original error with an audit error.
                drainSaveReceipts();
                Map<String,Object> response=failure(id,scope,dispatchAttempted&&!definitelyNotExecuted?"EXECUTION_UNKNOWN":code(error));
                if(dispatchAttempted&&!definitelyNotExecuted&&!attempt.duplicate)
                    response.put("data",CompactProtocol.project(withPersistence(attempt,Collections.emptyMap(),scope),false));
                // Never substitute a pre-action snapshot for an unknown post-action state.
                Map<String,Object>[] snapshots=dispatchAttempted?emptySnapshots():auditSnapshots(scope,state,currentCertified);
                store.complete(attempt,dispatchAttempted&&!definitelyNotExecuted?"UNKNOWN":"REJECTED",response,snapshots[0],snapshots[1],error);responseStage.committed=true;
                send(attempt,response,responseStage);
            }catch(Throwable auditFailure){
                if(!responseStage.written&&!responseStage.committed){responseStage.written=true;output.println(JsonCodec.encode(failure(id,scope,"AUDIT_UNAVAILABLE")));output.flush();}
                fatal(auditFailure);
            }
        }finally{
            if(preparedLease!=null)game.abortCancellation(preparedLease);
            try{drainSaves();}catch(Throwable error){fatal(error);}
        }
    }
    private Object publicStateResult(GameController.State state){
        refreshActivity();
        if(cancelling!=null)return map("phase","cancelling","state_version",null,"actions",Collections.emptyList(),"snapshot_status","last_stable");
        if(pending!=null&&pending.activity!=null&&!executionUncertain){
            Map<String,Object> result=new LinkedHashMap<>(pending.activity.result());
            result.put("snapshot_status","last_interruptible_boundary");return result;
        }
        if(pending!=null||executionUncertain)return map("phase",executionUncertain?"execution_unknown":"resolving","state_version",null,
                "actions",Collections.emptyList(),"snapshot_status","last_stable","last_stable_state",state==null?null:state.result());
        return state==null?map("phase","starting","actions",Collections.emptyList()):state.result();
    }
    private void refreshActivity(){
        if(pending==null||pending.execution==null)return;
        CompletableFuture<GameController.State> first=pending.execution.firstResponse;
        if(first.isDone()&&!first.isCompletedExceptionally()){
            GameController.State boundary=first.getNow(null);
            if(pending.activity==null&&boundary!=null&&"continuous_activity".equals(boundary.phase))pending.activity=boundary;
        }
        GameController.State latest=game.latest();
        if(pending.activity!=null&&latest!=null&&"continuous_activity".equals(latest.phase)
                &&latest.version.equals(pending.activity.version)&&latest.scopeId.equals(pending.activity.scopeId))pending.activity=latest;
    }
    @SuppressWarnings("unchecked") private Map<String,Object>[] emptySnapshots(){return new Map[]{null,null};}
    @SuppressWarnings("unchecked") private Map<String,Object>[] directSnapshots(GameController.State state){return new Map[]{state.publicState,state.internalState};}
    @SuppressWarnings("unchecked") private Map<String,Object>[] auditSnapshots(String requestedScope,GameController.State state,boolean currentCertified){
        if(state==null)return emptySnapshots();
        if(currentCertified&&Objects.equals(requestedScope,state.scopeId))return new Map[]{state.publicState,state.internalState};
        Map<String,Object> context=map("requested_scope",requestedScope,"captured_scope",state.scopeId,"state_version",state.version,
                "snapshot_status",Objects.equals(requestedScope,state.scopeId)?"last_stable":"scope_not_active");
        Map<String,Object> publicSnapshot=map("audit_context",context);
        if(Objects.equals(requestedScope,state.scopeId))publicSnapshot.put("last_stable_observation",state.publicState);
        return new Map[]{publicSnapshot,map("audit_context",context,"last_stable_internal",state.internalState)};
    }
    private void scheduleSettlement(){
        try{serial.execute(()->{try{settleReady();}catch(Throwable error){fatal(error);}});}
        catch(RejectedExecutionException ignored){/* Existing intent is recovered as UNKNOWN after restart. */}
    }
    private void settleReady(){
        if(pending!=null&&persistReady(pending))pending=null;
        if(cancelling!=null&&persistReady(cancelling))cancelling=null;
    }
    private boolean persistReady(Pending completed){
        if(completed.future==null||!completed.future.isDone())return false;
        GameController.State after;
        try{after=completed.future.join();}
        catch(Throwable error){
            Throwable actual=unwrap(error);boolean rejected=actual instanceof GameController.NotExecuted;
            if(!rejected)executionUncertain=true;
            drainSaves();
            Map<String,Object> response=failure(completed.attempt.id,completed.attempt.scopeId,rejected?code(actual):"EXECUTION_UNKNOWN");
            if(!rejected)response.put("data",CompactProtocol.project(withPersistence(completed.attempt,Collections.emptyMap(),completed.attempt.scopeId),false));
            store.settle(completed.attempt,rejected?"REJECTED":"UNKNOWN",response,null,null,actual);
            return true;
        }
        if(after.scopeId.startsWith("run:"))store.ensureScope(after.scopeId,"run",after.scopeId.substring(4));
        String status=completed.execution!=null&&completed.execution.interrupted?"interrupted":"awaiting_input".equals(after.phase)?"awaiting_input":"completed";
        drainSaves();
        store.settle(completed.attempt,status.toUpperCase(Locale.ROOT),success(completed.attempt.id,completed.attempt.scopeId,status,
                withPersistence(completed.attempt,after.result(),after.scopeId)),after.publicState,after.internalState,null,after.scopeId);
        return true;
    }
    private void send(AuditStore.Attempt attempt,Map<String,Object> response,ResponseStage stage){
        String encoded=JsonCodec.encode(response);stage.written=true;output.println(encoded);output.flush();
        boolean success=!output.checkError();store.markOutputAttempt(attempt,success);
        if(!success)throw new IllegalStateException("OUTPUT_CLOSED");
    }
    private void drainSaveReceipts(){
        GameController.SaveResult save;
        while((save=game.pollSave())!=null)store.recordSave(save.receiptId,save.runId==null?store.menuScope():"run:"+save.runId,
                save.slot,save.error==null,save.occurredAt,save.originScopeId,save.originRequestId,save.error);
        GameController.RunOutcome outcome;
        while((outcome=game.pollRunOutcome())!=null){
            store.ensureScope("run:"+outcome.runId,"run",outcome.runId);
            store.event("run:"+outcome.runId,"run.ended",outcome.data());
        }
    }
    private void drainSaves(){
        drainSaveReceipts();
        GameController.GameLogSnapshot log;
        while((log=game.pollGameLog())!=null){
            store.ensureScope(log.scopeId,"run",log.scopeId.substring(4));
            Map<String,Object> original=log.data();
            Map<String,Object> english=PublicEnglishProjection.copy(original);
            english.put("text_language","en");
            english.put("presentation",PublicEnglishProjection.presentation(english));
            store.eventWithOriginalText(log.scopeId,"game.log",english,log.originalData());
        }
        GameController.VisualSnapshot visual;
        while((visual=game.pollVisual())!=null){
            store.ensureScope(visual.scopeId,"run",visual.runId);
            store.event(visual.scopeId,"game.visual",visual.data());
        }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> withLastSave(Object result,String scope){
        Map<String,Object> value=new LinkedHashMap<>((Map<String,Object>)result);
        value.put("last_save",scope==null?null:store.latestSave(scope));return value;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> withPersistence(AuditStore.Attempt attempt,Object result,String observedScope){
        Map<String,Object> value=new LinkedHashMap<>((Map<String,Object>)result);
        value.put("persistence",map("last_save",observedScope==null?null:store.latestSave(observedScope),
                "saves_during_request",store.requestSaves(attempt.scopeId,attempt.id)));
        return value;
    }
    /** EOF is a system lifecycle event, not an invented caller request or an unsolicited response. */
    private void endInput(String reason){
        if(game.disposed()){drainSaves();closed=true;return;}
        String scope=game.latest()==null?store.menuScope():game.latest().scopeId;
        try{
            store.event(scope,"shutdown.requested",map("reason",reason,"source","system"));
            settleReady();refreshActivity();
            if(pending!=null&&pending.activity!=null&&cancelling==null&&!executionUncertain){
                String token=pending.activity.version,target=pending.attempt.id;
                try{
                    GameController.State before=game.prepareCancellation(token,target).get(timeoutMillis,TimeUnit.MILLISECONDS);
                    store.event(scope,"shutdown.cancel_prepared",map("source","system","target_id",target,"state_version",token,"before",before.result()));
                    store.recordLog("shutdown_private_before",JsonCodec.encode(before.internalState));
                    game.cancelPrepared(token,target).get(timeoutMillis,TimeUnit.MILLISECONDS);
                    settleReady();
                }finally{game.abortCancellation(token);}
            }
            if(pending!=null&&pending.future!=null){
                try{pending.future.get(timeoutMillis,TimeUnit.MILLISECONDS);}catch(Throwable error){recordExceptionNow(unwrap(error));}
                settleReady();
            }
            if(pending==null&&!executionUncertain&&!game.exiting()&&!game.disposed()){
                quitAfterEof(scope);
                drainSaves();
                store.event(scope,"shutdown.completed",map("source","system"));
            }else store.event(scope,"shutdown.unsettled",map("source","system","execution_unknown",executionUncertain));
        }catch(Throwable error){
            recordExceptionNow(unwrap(error));try{store.event(scope,"shutdown.failed",map("source","system","code",code(error)));}catch(Throwable ignored){}
        }finally{
            try{drainSaves();}catch(Throwable error){recordExceptionNow(error);}
            closed=true;if(!game.disposed())game.exitNow();
        }
    }
    private void quitAfterEof(String scope)throws Exception{
        for(int attempt=0;attempt<5;attempt++){
            GameController.State current=game.observe().get(timeoutMillis,TimeUnit.MILLISECONDS);
            store.event(scope,"shutdown.dispatch",map("source","system","action","app.quit","state_version",current.version,"attempt",attempt+1));
            try{
                game.execute(current.version,map("action","app.quit")).get(timeoutMillis,TimeUnit.MILLISECONDS);return;
            }catch(Throwable failure){
                Throwable actual=unwrap(failure);
                // A known pre-dispatch rejection is safe to retry. Never replay an uncertain quit/save.
                if(!(actual instanceof GameController.NotExecuted)||!"STALE_STATE".equals(((GameController.NotExecuted)actual).code)||attempt==4){
                    if(actual instanceof Exception)throw (Exception)actual;
                    if(actual instanceof Error)throw (Error)actual;
                    throw new IllegalStateException(actual);
                }
                store.event(scope,"shutdown.retry",map("source","system","code","STALE_STATE","attempt",attempt+1));
            }
        }
    }
    private void fatal(Throwable error){fatalFailure=true;recordExceptionNow(unwrap(error));closed=true;game.exitNow();}
    private void recordExceptionNow(Throwable error){try{store.recordException(error);}catch(Throwable ignored){}}
    /** The launcher must retain the original failure before close waits on the protocol worker. */
    public void recordRuntimeFailure(Throwable error){
        fatalFailure=true;
        recordExceptionNow(unwrap(Objects.requireNonNull(error,"Runtime failure is required")));
    }
    public void recordException(Throwable error){try{serial.execute(()->recordExceptionNow(unwrap(error)));}catch(RejectedExecutionException ignored){}}
    public void recordLog(String channel,String text){
        try{serial.execute(()->{try{store.recordLog(channel,text);}catch(Throwable error){fatal(error);}});}catch(RejectedExecutionException ignored){}
    }
    private static Map<String,Object> success(String id,String scope,String status,Object result){
        return CompactProtocol.success(id,scope,status,PublicEnglishProjection.copy(result),true,false);
    }
    private static Map<String,Object> failure(String id,String scope,String code){
        return CompactProtocol.failure(id,scope,code);
    }
    private static Map<String,Object> actionState(GameController.State state){
        return map("scope_id",state.scopeId,"state_version",state.version,"phase",state.phase,
                "observation",map("ui",state.publicState.get("ui")),"actions",state.actions);
    }
    private static Map<String,Object> page(List<Map<String,Object>> rows,int limit,long until){
        boolean more=rows.size()>limit;
        List<Map<String,Object>> items=new ArrayList<>(rows.subList(0,Math.min(rows.size(),limit)));
        Object next=more?items.get(items.size()-1).get("sequence"):null;
        return map("items",items,"next",next,"end",!more,"until",until);
    }
    private static Throwable unwrap(Throwable e){while((e instanceof ExecutionException||e instanceof CompletionException)&&e.getCause()!=null)e=e.getCause();return e;}
    private static String code(Throwable e){e=unwrap(e);return e instanceof GameController.NotExecuted?((GameController.NotExecuted)e).code:e instanceof ProtocolException?((ProtocolException)e).code:e instanceof IllegalArgumentException?"INVALID_ARGUMENT":e instanceof TimeoutException?"STATE_UNAVAILABLE":"ENGINE_ERROR";}
    private static String validText(Object v){return v instanceof String&&!((String)v).isEmpty()?(String)v:null;}
    private static String identity(Object v,int maximum){return v instanceof String&&Identifiers.valid((String)v,maximum)?(String)v:null;}
    private static String requiredString(Map<String,Object> args,String key){String s=validText(args.get(key));if(s==null)throw new ProtocolException("INVALID_ARGUMENT",key+" is required");return s;}
    private static String requiredIdentifier(Map<String,Object> args,String key){String s=requiredString(args,key);if(!Identifiers.valid(s,128))throw new ProtocolException("INVALID_ARGUMENT",key+" must be a valid request ID");return s;}
    private static long number(Map<String,Object> args,String key,long fallback){
        if(!args.containsKey(key))return fallback;Object value=args.get(key);
        if(!(value instanceof Number))throw new ProtocolException("INVALID_ARGUMENT",key+" must be an integer");
        try{long n=new BigDecimal(value.toString()).longValueExact();if(n<0)throw new ArithmeticException();return n;}
        catch(ArithmeticException e){throw new ProtocolException("INVALID_ARGUMENT",key+" must be a nonnegative integer");}
    }
    private static int limit(Map<String,Object> args){long n=number(args,"limit",50);if(n<1||n>100)throw new ProtocolException("INVALID_ARGUMENT","limit must be between 1 and 100");return (int)n;}
    @Override public void close(){
        closed=true;
        try{serial.submit(()->{try{settleReady();drainSaves();}catch(Throwable error){recordExceptionNow(error);}});}catch(RejectedExecutionException ignored){}
        serial.shutdown();try{if(!serial.awaitTermination(35,TimeUnit.SECONDS))serial.shutdownNow();}
        catch(InterruptedException e){Thread.currentThread().interrupt();serial.shutdownNow();}
    }
}
