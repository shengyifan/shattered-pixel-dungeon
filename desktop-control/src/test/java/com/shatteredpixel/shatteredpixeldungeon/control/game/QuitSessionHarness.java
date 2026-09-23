package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Real session reader and paired audit, with only the engine completion controlled by the test. */
public final class QuitSessionHarness implements AutoCloseable {
    public final AuditStore store;
    public final Game game=new Game();
    public final Wire wire=new Wire();
    public final MachineSession session;
    public final Thread reader;
    private final PipedOutputStream input=new PipedOutputStream();

    public QuitSessionHarness(Path directory)throws Exception{
        store=new AuditStore(directory);store.ensureScope("run:quit","run","quit");
        store.beginSession("quit-session-fixture","test-build","CLI.7.0.0",7);
        session=new MachineSession(store,game,new PrintStream(wire,true,StandardCharsets.UTF_8),50);
        PipedInputStream source=new PipedInputStream(input,65536);
        reader=new Thread(()->session.read(source),"Test machine reader");reader.setDaemon(true);reader.start();
    }
    public void sendRaw(String line)throws IOException{input.write(line.getBytes(StandardCharsets.UTF_8));input.flush();}
    public void send(String id,String op,Map<String,Object> args)throws Exception{
        sendRaw(V7Requests.encode(store,map("protocol_version",7,"scope_id","run:quit","id",id,"op",op,
                "state_version",game.state.version,"args",args))+"\n");
    }
    public Map<String,Object> receive()throws Exception{
        String frame=wire.frames.poll(5,TimeUnit.SECONDS);
        if(frame==null)throw new AssertionError("Session did not return a complete response");
        return JsonCodec.decode(frame);
    }
    public Map<String,Object> request(String id,String op,Map<String,Object> args)throws Exception{
        send(id,op,args);return receive();
    }
    public void endInput()throws IOException{input.close();}
    public void awaitReader()throws Exception{
        reader.join(5000);if(reader.isAlive())throw new AssertionError("Session reader did not exit");
    }
    @Override public void close()throws Exception{
        input.close();reader.join(5000);session.close();store.close();
    }
    public static final class Wire extends OutputStream {
        public final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        public final List<String> recorded=Collections.synchronizedList(new ArrayList<>());
        private final ByteArrayOutputStream current=new ByteArrayOutputStream();
        public volatile boolean fail;
        @Override public synchronized void write(int value)throws IOException{
            if(fail)throw new IOException("Fixture output closed");
            if(value=='\n'){
                String frame=current.toString(StandardCharsets.UTF_8);current.reset();
                recorded.add(frame);frames.add(frame);
            }else current.write(value);
        }
    }
    public static final class Game implements MachineSession.GamePort {
        public volatile GameController.State state=state("v1","player_ready");
        public final CompletableFuture<GameController.State> completion=new CompletableFuture<>();
        public final CompletableFuture<Void> exited=new CompletableFuture<>();
        public final ConcurrentLinkedQueue<GameController.SaveResult> saves=new ConcurrentLinkedQueue<>();
        public final AtomicInteger executions=new AtomicInteger(),exits=new AtomicInteger(),observations=new AtomicInteger();
        public volatile boolean exiting,immediate,delayWait;
        public volatile Throwable synchronousFailure;
        public volatile String originalId;
        public Runnable beforeExit=()->{};
        public GameController.State latest(){return state;}
        public CompletableFuture<GameController.State> observe(){observations.incrementAndGet();return CompletableFuture.completedFuture(state);}
        public GameController.Execution start(String version,Map<String,Object> args,String id){
            originalId=id;CompletableFuture<GameController.State> future=execute(version,args);
            return new GameController.Execution(future,future);
        }
        public CompletableFuture<GameController.State> execute(String version,Map<String,Object> args){
            executions.incrementAndGet();
            if(synchronousFailure!=null)return CompletableFuture.failedFuture(synchronousFailure);
            if(!"app.quit".equals(args.get("action")))return delayWait?completion:CompletableFuture.completedFuture(state);
            exiting=true;if(immediate)completeQuit();return completion;
        }
        public void completeQuit(){
            saves.add(new GameController.SaveResult("quit-save","quit",1,null,"2026-09-21T00:00:00Z","run:quit",originalId));
            state=state("v2","closing");completion.complete(state);
        }
        public void rejectQuit(){exiting=false;completion.completeExceptionally(rejection());}
        public void failQuit(){completion.completeExceptionally(new IllegalStateException("PRIVATE_QUIT_FAILURE"));}
        public static Throwable rejection(){return new GameController.NotExecuted("STALE_STATE",null);}
        public void prepareRun(String id){}
        public GameController.SaveResult pollSave(){return saves.poll();}
        public boolean exiting(){return exiting;}
        public boolean disposed(){return exits.get()>0;}
        public void exitNow(){beforeExit.run();exits.incrementAndGet();exited.complete(null);}
        private static GameController.State state(String version,String phase){
            return new GameController.State("run:quit",version,phase,map("fixture","quit"),map("fixture",true),
                    Arrays.asList(map("action","app.quit"),map("action","wait")));
        }
    }
}
