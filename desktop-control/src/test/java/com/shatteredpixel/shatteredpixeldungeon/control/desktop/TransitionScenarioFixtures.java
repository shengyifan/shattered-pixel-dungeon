package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.control.game.GameController;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Pickaxe;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.CavesBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.CityBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Test-only source states. Every measured transition is subsequently invoked through public CLI. */
final class TransitionScenarioFixtures {
    private static Path diagnosticDirectory;
    private static volatile String stage="not_started";
    private static volatile boolean diagnosticFinished;
    private static volatile boolean sourceSceneCreated;
    private static int sourceDepth;
    static void diagnostics(Path profile)throws IOException{
        Path allowed=Path.of("desktop-control/build/fixtures").toAbsolutePath().toRealPath();
        profile=profile.toRealPath();
        if(!profile.startsWith(allowed)||profile.equals(allowed))throw new IOException("Scenario diagnostics require an isolated fixture");
        diagnosticDirectory=profile;
    }
    private static synchronized void stage(String value,Map<String,Object> facts){
        stage=value;
        if(diagnosticDirectory==null)throw new IllegalStateException("Scenario diagnostics were not configured");
        Map<String,Object> row=new LinkedHashMap<>(facts);
        row.put("test_fixture",true);row.put("internal_assertion_only",true);row.put("stage",value);
        row.put("occurred_at",Instant.now().toString());row.put("thread",Thread.currentThread().getName());
        try{Files.writeString(diagnosticDirectory.resolve("transition-source-stages.jsonl"),JsonCodec.encode(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(IOException error){throw new UncheckedIOException(error);}
    }
    private static synchronized void threads(String reason){
        StringBuilder text=new StringBuilder("TEST ONLY ").append(reason).append(" at ").append(Instant.now()).append(" stage=").append(stage).append('\n');
        Map<Thread,StackTraceElement[]> all=Thread.getAllStackTraces();
        List<Thread> ordered=new ArrayList<>(all.keySet());ordered.sort(Comparator.comparing(Thread::getName));
        for(Thread thread:ordered){
            text.append('\n').append('"').append(thread.getName()).append("\" ").append(thread.getState()).append('\n');
            for(StackTraceElement element:all.get(thread))text.append("  at ").append(element).append('\n');
        }
        try{Files.writeString(diagnosticDirectory.resolve("transition-source-threads.txt"),text.toString(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(IOException error){throw new UncheckedIOException(error);}
    }
    static void observed(GameController.State state){
        if(!sourceSceneCreated||diagnosticFinished||state==null||!"player_ready".equals(state.phase))return;
        Object hero=state.publicState.get("hero");
        if(hero instanceof Map&&Objects.equals(((Map<?,?>)hero).get("depth"),sourceDepth)){
            diagnosticFinished=true;stage("source_stable_observed",map("state_version",state.version,"scope_id",state.scopeId));
        }
    }
    static boolean supports(String name){return Arrays.asList("fall","branch","story-prison","story-caves","story-city","story-halls").contains(name);}
    static void prepare(String name,Hero hero){
        diagnosticFinished=false;sourceSceneCreated=false;
        stage("scene_rebuild_queued",map("scenario",name));
        Thread watchdog=new Thread(()->{
            try{
                for(int sample=1;sample<=3&&!diagnosticFinished;sample++){
                    Thread.sleep(8000);
                    if(!diagnosticFinished)threads("watchdog_sample_"+sample);
                }
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        },"Transition fixture diagnostic watchdog");
        watchdog.setDaemon(true);watchdog.start();
        // Stop the old scene's scheduler through its original destroy path before preparing
        // a source level. No test setup runs once the next GameScene has been created.
        Game.switchScene(GameScene.class,new Game.SceneChangeCallback(){
            @Override public void beforeCreate(){
                stage("source_before_create",map());
                try{source(name,hero);stage("source_ready_before_scene_create",map());}
                catch(Throwable error){
                    stage("source_setup_failed",map("error_class",error.getClass().getName(),"message",error.getMessage()));
                    threads("source_setup_failed");diagnosticFinished=true;
                    throw new IllegalStateException("Transition fixture source setup failed",error);
                }
            }
            @Override public void afterCreate(){sourceSceneCreated=true;stage("source_scene_created",map());}
        });
    }
    private static void source(String name,Hero hero)throws Exception{
        int depth=name.equals("branch")?14:name.equals("story-prison")?5:name.equals("story-caves")?10
                :name.equals("story-city")?15:name.equals("story-halls")?20:1;
        sourceDepth=depth;
        Dungeon.depth=depth;Dungeon.branch=0;
        if(name.equals("branch"))Blacksmith.Quest.reset();
        stage("before_original_level_generation",map("depth",depth));
        Level level=Dungeon.newLevel();Dungeon.level=level;
        stage("original_level_generated",map("level_class",level.getClass().getSimpleName()));
        // The fixture begins with the source floor cleared. Keep the actual blacksmith
        // needed by CavesLevel's original permission/confirmation path.
        level.mobs.removeIf(mob->!name.equals("branch")||!(mob instanceof Blacksmith));
        level.heaps.clear();level.traps.clear();level.plants.clear();level.blobs.clear();level.locked=false;
        if(level instanceof CavesBossLevel){
            // A cleared DM-300 source also has its physical gate open. Merely setting
            // locked=false would make invalidHeroPos correctly return the hero downstairs.
            for(int y=CavesBossLevel.gate.top;y<CavesBossLevel.gate.bottom;y++)
                for(int x=CavesBossLevel.gate.left;x<CavesBossLevel.gate.right;x++)level.map[y*level.width()+x]=Terrain.EMPTY_SP;
        }
        if(level instanceof CityBossLevel){
            // Match the cleared-floor state of CityBossLevel.unseal. Leaving the
            // top door locked correctly makes its original invalidHeroPos reject the exit.
            for(String nameOfDoor:Arrays.asList("topDoor","bottomDoor")){
                Field door=CityBossLevel.class.getDeclaredField(nameOfDoor);door.setAccessible(true);
                level.map[door.getInt(null)]=Terrain.DOOR;
            }
        }
        LevelTransition source;
        if(name.equals("branch")){
            source=level.getTransition(LevelTransition.Type.BRANCH_EXIT);
            if(source==null)throw new IllegalStateException("Original depth-14 generation did not create its mining entrance");
            Field given=Blacksmith.Quest.class.getDeclaredField("given");given.setAccessible(true);given.setBoolean(null,true);
            new Pickaxe().identify(false).collect();
        }else source=level.getTransition(name.equals("fall")?LevelTransition.Type.SURFACE:LevelTransition.Type.REGULAR_EXIT);
        if(source==null)throw new IllegalStateException("No original transition at the prepared source");
        int position=source.cell();
        if(name.equals("fall")){
            // Only the starting layout is constructed; Chasm.heroJump/FALL/heroLand remain original.
            int width=level.width();position=(level.height()/2)*width+width/2;
            for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)level.map[position+dy*width+dx]=Terrain.EMPTY;
            level.map[position+1]=Terrain.CHASM;
        }else if(name.startsWith("story-"))level.map[position]=Terrain.EXIT;
        level.buildFlagMaps();
        hero.HP=hero.HT=1000;hero.ready=true;hero.curAction=hero.lastAction=null;
        stage("before_original_switch_level",map("requested_position",position,"invalid_position",level.invalidHeroPos(position)));
        Dungeon.switchLevel(level,position);
        stage("original_switch_level_returned",map("requested_position",position,"actual_position",hero.pos));
        if(hero.pos!=position)throw new IllegalStateException("Prepared source position was rejected by the original level");
        Item.updateQuickslot();
    }
    static Map<String,Object> assertions(){
        return map("depth",Dungeon.depth,"branch",Dungeon.branch,"level_class",Dungeon.level==null?null:Dungeon.level.getClass().getSimpleName(),
                "run_id",Dungeon.runId,"slot",GamesInProgress.curSlot,"blacksmith_started",Blacksmith.Quest.started(),
                "blacksmith_completed",Blacksmith.Quest.completed());
    }
}
