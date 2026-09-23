package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sqlite.Function;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Real paired persistence and worker scheduling; never a GUI, personal profile, or fake wire reply. */
public class DisplayPersistenceSessionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String SCOPE="run:display";

    @Test public void idleDisplaySignalPersistsEveryMixedEventWithoutStdoutOrRenderThreadSql()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            Port game=new Port();ByteArrayOutputStream wire=new ByteArrayOutputStream();
            List<String> sqlThreads=Collections.synchronizedList(new ArrayList<>());
            Field field=AuditStore.class.getDeclaredField("writer");field.setAccessible(true);Connection writer=(Connection)field.get(store);
            Function.create(writer,"fixture_sql_thread",new Function(){@Override protected void xFunc()throws SQLException{sqlThreads.add(Thread.currentThread().getName());result(1);}});
            try(Statement sql=writer.createStatement()){sql.execute("CREATE TEMP TRIGGER display_thread BEFORE INSERT ON main.events BEGIN SELECT fixture_sql_thread(); END");}
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,StandardCharsets.UTF_8),1000)){
                String[] kinds={"game.log","game.visual","game.floating_text","game.visual_metrics","game.banner","game.screen_visual"};
                Thread renderer=new Thread(()->{for(int i=0;i<90;i++)game.add(kinds[i%kinds.length],i);},"fixture-render-thread");
                renderer.start();renderer.join();awaitEmpty(game);
                List<Map<String,Object>> events=store.events(SCOPE,0,100);
                assertEquals(90,events.size());
                for(int i=0;i<90;i++){
                    assertEquals(kinds[i%kinds.length],events.get(i).get("kind"));
                    assertEquals((long)i,((Map<?,?>)events.get(i).get("data")).get("index"));
                }
                assertFalse(sqlThreads.isEmpty());assertTrue(sqlThreads.stream().allMatch("SPD Audit and Protocol"::equals));
                assertEquals(0,wire.size());assertFalse(session.failed());assertEquals(0,game.exits.get());
            }
        }
    }

    @Test public void boundedBackgroundBatchesYieldToQueuedRequestsWithoutLosingARefilledStream()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            store.ensureScope(SCOPE,"run","display");
            String query=V7Requests.encode(store,map("v",7,"id","query","s",SCOPE,"op","state"));
            Port game=new Port();game.gateFirst=true;game.refillBatches=8;
            ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,StandardCharsets.UTF_8),1000)){
                for(int i=0;i<64;i++)game.addNext();
                assertTrue(game.firstBatch.await(5,TimeUnit.SECONDS));
                CompletableFuture<Void> pendingQuery=session.accept(query);
                game.release.countDown();pendingQuery.get(10,TimeUnit.SECONDS);
                assertEquals("A continuation must queue behind the already pending request",1,game.observedAfterBatches);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while((game.acknowledgements.get()<8||game.hasDisplayEvents())&&System.nanoTime()<deadline)Thread.sleep(5);
                assertEquals(8,game.acknowledgements.get());assertEquals(512,game.produced.get());
                assertEquals(512,store.eventsWatermark(SCOPE));
                List<Map<String,Object>> rows=new ArrayList<>();long after=0;
                while(rows.size()<512){List<Map<String,Object>> page=store.events(SCOPE,after,100);assertFalse(page.isEmpty());rows.addAll(page);after=((Number)page.get(page.size()-1).get("sequence")).longValue();}
                for(int i=0;i<512;i++)assertEquals((long)i,((Map<?,?>)rows.get(i).get("data")).get("index"));
                String[] lines=wire.toString(StandardCharsets.UTF_8).strip().split("\\R");
                assertEquals(1,lines.length);assertEquals("query",JsonCodec.decode(lines[0]).get("id"));
                assertEquals("completed",JsonCodec.decode(lines[0]).get("st"));assertFalse(session.failed());
            }finally{game.release.countDown();}
        }
    }

    @Test public void adjacentSignalsCoalesceAndImmediateQueryBypassesTheBackgroundWindow()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            store.ensureScope(SCOPE,"run","display");
            String query=V7Requests.encode(store,map("v",7,"id","immediate","s",SCOPE,"op","state"));
            Port game=new Port();ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,StandardCharsets.UTF_8),1000)){
                game.addNext();game.addNext();game.addNext();
                session.accept(query).get(5,TimeUnit.SECONDS);
                assertEquals("A request submitted during coalescing must run before the delayed background batch",0,game.observedAfterBatches);
                assertEquals(1,game.acknowledgements.get());assertEquals(List.of(3),game.batchSizes);
                assertFalse(game.hasDisplayEvents());
                // Wait beyond the background window: its stale wake must not append or acknowledge again.
                Thread.sleep(100);
                assertEquals(1,game.acknowledgements.get());
                List<Map<String,Object>> events=store.events(SCOPE,0,10);assertEquals(3,events.size());
                for(int i=0;i<3;i++)assertEquals((long)i,((Map<?,?>)events.get(i).get("data")).get("index"));
                assertEquals(1,wire.toString(StandardCharsets.UTF_8).strip().split("\\R").length);
                assertFalse(session.failed());
            }
        }
    }

    @Test public void backgroundFailureRetainsUnacknowledgedBatchAndCompletedActionOutcome()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            store.ensureScope(SCOPE,"run","display");Port game=new Port();ByteArrayOutputStream wire=new ByteArrayOutputStream();
            try(MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,StandardCharsets.UTF_8),1000)){
                session.accept(V7Requests.encode(store,map("v",7,"id","action","s",SCOPE,"rev","v1","op","wait"))).get(5,TimeUnit.SECONDS);
                try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+store.internalDatabase());Statement statement=db.createStatement()){
                    statement.execute("CREATE TRIGGER reject_display BEFORE INSERT ON events WHEN NEW.kind='game.visual' BEGIN SELECT RAISE(ABORT,'fixture display failure'); END");
                }
                GameController.DisplayEvent event=game.add("game.visual",17);
                game.exited.get(5,TimeUnit.SECONDS);
                assertTrue(session.failed());assertEquals(1,game.exits.get());assertEquals(0,game.acknowledgements.get());
                assertSame(event,game.events.peekDisplayEvents(1).get(0));
                assertEquals("COMPLETED",store.getRequest(SCOPE,"action").get("status"));
                assertEquals(0,store.eventsWatermark(SCOPE));
                assertEquals(1,wire.toString(StandardCharsets.UTF_8).strip().split("\\R").length);
            }
            assertTrue("Failed frozen content must not be silently acknowledged or replayed at close",game.events.hasDisplayEvents());
        }
    }

    @Test public void teardownDrainsTheFrozenTailAcrossMultipleBatchesWithoutAnyResponse()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            Port game=new Port();ByteArrayOutputStream wire=new ByteArrayOutputStream();
            game.ignoreSignal=true;
            MachineSession session=new MachineSession(store,game,new PrintStream(wire,true,StandardCharsets.UTF_8),1000);
            for(int i=0;i<150;i++)game.addNext();
            session.close();
            assertFalse(game.events.hasDisplayEvents());assertEquals(150,store.events(SCOPE,0,200).size());
            assertEquals(3,game.acknowledgements.get());assertEquals(0,wire.size());assertFalse(session.failed());
        }
    }

    private static void awaitEmpty(Port port)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(port.hasDisplayEvents()&&System.nanoTime()<end)Thread.sleep(5);
        assertFalse("Display worker did not drain the finite producer",port.hasDisplayEvents());
    }
    private static final class Port implements MachineSession.GamePort {
        final GameController events=new GameController(null,"menu:display",error->{throw new AssertionError(error);});
        final GameController.State state=new GameController.State(SCOPE,"v1","player_ready",map("fixture",true),map("private_fixture",true),List.of(map("action","wait")));
        final AtomicInteger acknowledgements=new AtomicInteger(),produced=new AtomicInteger(),exits=new AtomicInteger();
        final List<Integer> batchSizes=Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch firstBatch=new CountDownLatch(1),release=new CountDownLatch(1);
        final CompletableFuture<Void> exited=new CompletableFuture<>();
        final AtomicBoolean first=new AtomicBoolean(true);
        boolean gateFirst,ignoreSignal;int refillBatches;volatile int observedAfterBatches=-1;
        GameController.DisplayEvent add(String kind,int index){GameController.DisplayEvent event=new GameController.DisplayEvent(SCOPE,kind,map("index",index),null);events.enqueueDisplayEvent(event);return event;}
        void addNext(){add("game.visual",produced.getAndIncrement());}
        public GameController.State latest(){return state;}
        public CompletableFuture<GameController.State> observe(){observedAfterBatches=acknowledgements.get();return CompletableFuture.completedFuture(state);}
        public CompletableFuture<GameController.State> execute(String version,Map<String,Object> args){return CompletableFuture.completedFuture(state);}
        public void prepareRun(String id){}
        public GameController.SaveResult pollSave(){return null;}
        public boolean exiting(){return false;}
        public boolean disposed(){return exits.get()>0;}
        public void exitNow(){exits.incrementAndGet();exited.complete(null);}
        public List<GameController.DisplayEvent> peekDisplayEvents(int limit){
            if(gateFirst&&first.compareAndSet(true,false)){firstBatch.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Fixture release missing");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
            return events.peekDisplayEvents(limit);
        }
        public void acknowledgeDisplayEvents(List<GameController.DisplayEvent> batch){
            events.acknowledgeDisplayEvents(batch);batchSizes.add(batch.size());int count=acknowledgements.incrementAndGet();
            if(count<refillBatches)for(int i=0;i<64;i++)addNext();
        }
        public boolean hasDisplayEvents(){return events.hasDisplayEvents();}
        public void setDisplaySignal(Runnable signal){if(!ignoreSignal)events.setDisplaySignal(signal);}
        public void freezeDisplayEvents(){events.freezeDisplayEvents();}
    }
}
