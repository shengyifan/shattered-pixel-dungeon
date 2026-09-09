package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Pickaxe;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.CavesBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import java.lang.reflect.Field;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Test-only source states. Every measured transition is subsequently invoked through public CLI. */
final class TransitionScenarioFixtures {
    static boolean supports(String name){return Arrays.asList("fall","branch","story-prison","story-caves","story-city","story-halls").contains(name);}
    static void prepare(String name,Hero hero){
        // Stop the old scene's scheduler through its original destroy path before preparing
        // a source level. No test setup runs once the next GameScene has been created.
        Game.switchScene(GameScene.class,new Game.SceneChangeCallback(){
            @Override public void beforeCreate(){
                try{source(name,hero);}catch(Exception error){throw new IllegalStateException("Transition fixture source setup failed",error);}
            }
            @Override public void afterCreate(){}
        });
    }
    private static void source(String name,Hero hero)throws Exception{
        int depth=name.equals("branch")?14:name.equals("story-prison")?5:name.equals("story-caves")?10
                :name.equals("story-city")?15:name.equals("story-halls")?20:1;
        Dungeon.depth=depth;Dungeon.branch=0;
        if(name.equals("branch"))Blacksmith.Quest.reset();
        Level level=Dungeon.newLevel();Dungeon.level=level;
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
        Dungeon.switchLevel(level,position);
        if(hero.pos!=position)throw new IllegalStateException("Prepared source position was rejected by the original level");
        Item.updateQuickslot();
    }
    static Map<String,Object> assertions(){
        return map("depth",Dungeon.depth,"branch",Dungeon.branch,"level_class",Dungeon.level==null?null:Dungeon.level.getClass().getSimpleName(),
                "run_id",Dungeon.runId,"slot",GamesInProgress.curSlot,"blacksmith_started",Blacksmith.Quest.started(),
                "blacksmith_completed",Blacksmith.Quest.completed());
    }
}
