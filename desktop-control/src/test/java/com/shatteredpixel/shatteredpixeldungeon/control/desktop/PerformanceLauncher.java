package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.*;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.game.GameController;
import com.shatteredpixel.shatteredpixeldungeon.control.game.GameSnapshotter;
import com.shatteredpixel.shatteredpixeldungeon.control.game.PlayerObservation;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher;
import com.shatteredpixel.shatteredpixeldungeon.desktop.ProfileLock;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.DriedRose;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Test runtime only: sampled snapshots and artificial scenes are never victory evidence. */
public final class PerformanceLauncher {
    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        String scenario="simple"; Path profile=null;
        for(int i=0;i<args.length;i++) {
            if(args[i].equals("--scenario"))scenario=args[++i];
            else if(args[i].equals("--data-dir"))profile=Path.of(args[++i]);
            else if(!args[i].equals("run")&&!args[i].equals("--machine"))throw new IllegalArgumentException("Unknown benchmark argument");
        }
        if(!Arrays.asList("menu","simple","dense").contains(scenario)||profile==null)throw new IllegalArgumentException("Scenario and isolated profile required");
        Path allowed=Path.of("desktop-control/build/fixtures").toAbsolutePath().normalize();Files.createDirectories(allowed);
        profile=profile.toAbsolutePath().normalize();
        if(!profile.startsWith(allowed)||profile.equals(allowed))throw new IllegalArgumentException("Use an isolated fixture profile");
        AuditStore.preflight(profile.resolve("audit"));
        Files.createDirectories(profile);profile=profile.toRealPath();
        if(!profile.startsWith(allowed.toRealPath()))throw new IllegalArgumentException("Profile resolves outside fixture root");
        write(profile.resolve("test_fixture.json"),map("test_fixture",true,"counts_as_win",false,"scenario",scenario));
        PrintStream wire=new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8);
        System.setProperty("Specification-Title","Shattered Pixel Dungeon");
        System.setProperty("Implementation-Title","com.shatteredpixel.shatteredpixeldungeon");
        System.setProperty("Specification-Version","3.3.8");System.setProperty("Implementation-Version","896");
        try(ProfileLock ignored=new ProfileLock(profile);AuditStore store=new AuditStore(profile.resolve("audit"))) {
            store.recoverInterrupted();
            store.beginSession(java.util.UUID.randomUUID().toString(),
                    (String)com.shatteredpixel.shatteredpixeldungeon.control.game.BuildCatalog.current().get("build_id"),
                    (String)com.shatteredpixel.shatteredpixeldungeon.control.game.BuildCatalog.current().get("cli_version"),
                    com.shatteredpixel.shatteredpixeldungeon.control.protocol.ControlRequest.PROTOCOL_VERSION);
            java.util.concurrent.atomic.AtomicBoolean runtimeFailed=new java.util.concurrent.atomic.AtomicBoolean();
            AtomicReference<MachineSession> sessionRef=new AtomicReference<>();
            GameController game=new GameController(profile,store.menuScope(),error->{if(sessionRef.get()!=null)sessionRef.get().recordException(error);});
            try(MachineSession session=new MachineSession(store,game,wire)) {
                sessionRef.set(session);
                Probe probe=new Probe(game,profile,scenario);
                Game.observer=(RuntimeObserver)Proxy.newProxyInstance(RuntimeObserver.class.getClassLoader(),new Class<?>[]{RuntimeObserver.class},(proxy,method,arguments)->{
                    if(method.getName().equals("afterFrame")){probe.afterFrame();return null;}
                    try{return method.invoke(game,arguments);}catch(InvocationTargetException failure){throw failure.getCause();}
                });
                System.setOut(new PrintStream(new DiagnosticOutput(session,"benchmark_stdout"),true,StandardCharsets.UTF_8));
                System.setErr(new PrintStream(new DiagnosticOutput(session,"benchmark_stderr"),true,StandardCharsets.UTF_8));
                Thread input=new Thread(()->session.read(System.in),"Benchmark Machine Input");input.setDaemon(true);input.start();
                try{DesktopLauncher.launch(new String[0],profile,(thread,error)->{runtimeFailed.set(true);session.recordException(error);game.exitNow();},true);}
                catch(Throwable error){
                    runtimeFailed.set(true);session.recordRuntimeFailure(error);game.runtimeFailed(error);
                    throw error;
                }
                finally{Game.observer=RuntimeObserver.NONE;}
            } finally {
                MachineSession completed=sessionRef.get();
                store.endSession(completed==null||completed.failed()||runtimeFailed.get()?"FAILED":"CLOSED","benchmark_launcher_finished");
            }
        }
    }

    private static final class Probe {
        final GameController game;final Path profile;final String scenario;final GameSnapshotter collector;
        boolean prepared,menuPrepared,sampled;Map<String,Object> measured;GameSnapshotter.Capture snapshot;
        Probe(GameController game,Path profile,String scenario){this.game=game;this.profile=profile;this.scenario=scenario;collector=new GameSnapshotter(profile);}
        void afterFrame(){
            try{
                if(!menuPrepared&&Game.scene()!=null&&!Game.switchingScene()){
                    SPDSettings.language(Languages.CHI_SMPL);Messages.setup(Languages.CHI_SMPL);SPDSettings.fullscreen(false);SPDSettings.intro(false);menuPrepared=true;
                }
                if(!prepared&&Game.scene() instanceof GameScene)GameScene.atActorHandoff(()->{
                    if(ready()){
                        if(scenario.equals("dense"))prepareDense();
                        prepared=true;
                    }
                });
                game.afterFrame();
                if(!sampled&&Files.isRegularFile(profile.resolve("benchmark.arm"))){
                    if(Game.scene() instanceof GameScene)GameScene.atActorHandoff(()->{if(ready())sample();});
                    else if(!Game.switchingScene()&&!Game.hasPendingCallbacks()&&game.latest()!=null)sample();
                    // Encoding and writing the detached probe result occur after releasing the actor monitor.
                    if(sampled){
                        write(profile.resolve("benchmark-public.json"),snapshot.publicState);
                        write(profile.resolve("benchmark-internal.json"),snapshot.internalState);
                        write(profile.resolve("benchmark-capture.json"),measured);
                    }
                }
            }catch(Throwable failure){game.onException(failure);game.exitNow();}
        }
        boolean ready(){Hero h=Dungeon.hero;return h!=null&&h.ready&&h.curAction==null&&!h.resting&&Actor.isYielded()&&(h.sprite==null||!h.sprite.isMoving)&&!GameScene.interfaceBlockingHero();}
        void sample(){
            GameController.State checkpoint=game.latest();
            snapshot=new GameSnapshotter.Capture(checkpoint.publicState,checkpoint.internalState);
            String randomBefore=JsonCodec.encode(collector.capture().internalState.get("random"));
            int hpBefore=Dungeon.hero==null?0:Dungeon.hero.HP,cellBefore=Dungeon.hero==null?0:Dungeon.hero.pos;
            measured=map("test_fixture",true,"counts_as_win",false,"scenario",scenario,"warmup",5,"samples",25,
                    "capture_full_ms",measure(collector::capture),
                    "capture_profile_files_ms",measure(collector::captureProfileFiles),
                    "capture_public_projection_ms",measure(()->PlayerObservation.capture(scenario.equals("menu")?null:Dungeon.hero,scenario.equals("menu")?null:Dungeon.level,scenario.equals("menu")?"title":"game")),
                    "actor_count",Actor.all().size(),"mob_count",Dungeon.level==null?0:Dungeon.level.mobs.size(),
                    "ally_count",Dungeon.level==null?0:Dungeon.level.mobs.stream().filter(m->m.alignment==com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ALLY).count(),
                    "blob_types",Dungeon.level==null?0:Dungeon.level.blobs.size(),
                    "boundary","render thread; exact Actor handoff monitor in GameScene; no SQLite transaction in probe",
                    "capture_scope","GameSnapshotter.capture including public projection, graph/static roots, RNG, declared profile files, build catalog; excludes UiBridge and protocol",
                    "exported_snapshot_scope","Exact complete GameController state from the last settled CLI observation, including UiBridge; codec and audit benchmarks use these unmodified maps",
                    "exported_state_version",checkpoint.version,
                    "environment",PerformanceAuditBenchmark.environment());
            if(!randomBefore.equals(JsonCodec.encode(collector.capture().internalState.get("random")))
                    ||Dungeon.hero!=null&&(Dungeon.hero.HP!=hpBefore||Dungeon.hero.pos!=cellBefore))
                throw new AssertionError("Read probe changed RNG or hero state");
            measured.put("probe_preserved_rng_hp_cell",true);
            sampled=true;
        }
    }

    private static List<Double> measure(Supplier<?> task){
        for(int i=0;i<5;i++)sink=task.get();
        List<Double> times=new ArrayList<>();
        for(int i=0;i<25;i++){long start=System.nanoTime();sink=task.get();times.add((System.nanoTime()-start)/1_000_000.0);}
        return times;
    }

    /** Artificial load, never a playable run: native objects and collector paths remain intact. */
    private static void prepareDense(){
        Hero hero=Dungeon.hero;Level level=Dungeon.level;
        for(Mob mob:new ArrayList<>(level.mobs)){Actor.remove(mob);if(mob.sprite!=null)mob.sprite.killAndErase();}level.mobs.clear();
        for(Actor actor:new ArrayList<>(Actor.all()))if(actor instanceof MobSpawner)Actor.remove(actor);
        for(Blob blob:level.blobs.values())Actor.remove(blob);level.blobs.clear();
        level.heaps.clear();level.plants.clear();level.traps.clear();
        int width=level.width(),height=level.height(),cx=width/2,cy=height/2;
        for(int y=cy-6;y<=cy+6;y++)for(int x=cx-6;x<=cx+6;x++){
            int cell=y*width+x;Level.set(cell,Terrain.EMPTY);level.mapped[cell]=true;GameScene.updateMap(cell);
        }
        hero.pos=cy*width+cx;hero.sprite.place(hero.pos);hero.HT=hero.HP=10000;
        List<Integer> cells=new ArrayList<>();
        for(int radius=2;radius<=5;radius++)for(int dy=-radius;dy<=radius;dy++)for(int dx=-radius;dx<=radius;dx++)
            if(Math.max(Math.abs(dx),Math.abs(dy))==radius)cells.add((cy+dy)*width+cx+dx);
        for(int i=0;i<40;i++){Mob mob=i%2==0?new Rat():new Gnoll();mob.pos=cells.get(i);mob.state=mob.SLEEPING;GameScene.add(mob);}
        for(int i=0;i<8;i++){DriedRose.GhostHero ally=new DriedRose.GhostHero(new DriedRose());ally.pos=cells.get(40+i);GameScene.add(ally);}
        GameScene.add(Blob.seed(hero.pos-5*width-5,100,ToxicGas.class));
        GameScene.add(Blob.seed(hero.pos-5*width+5,100,ParalyticGas.class));
        GameScene.add(Blob.seed(hero.pos+5*width-5,100,ConfusionGas.class));
        GameScene.add(Blob.seed(hero.pos+5*width+5,100,CorrosiveGas.class));
        Dungeon.observe();hero.checkVisibleMobs();
    }
    private static void write(Path path,Object value)throws IOException{Files.writeString(path,JsonCodec.encode(value),StandardCharsets.UTF_8);}
    private static final class DiagnosticOutput extends OutputStream {
        final MachineSession session;final String channel;final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DiagnosticOutput(MachineSession session,String channel){this.session=session;this.channel=channel;}
        @Override public synchronized void write(int value){bytes.write(value);if(value=='\n')flush();}
        @Override public synchronized void flush(){if(bytes.size()>0){session.recordLog(channel,bytes.toString(StandardCharsets.UTF_8));bytes.reset();}}
    }
}
