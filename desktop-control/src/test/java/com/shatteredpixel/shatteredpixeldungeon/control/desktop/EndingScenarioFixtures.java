package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.Rankings;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AscensionChallenge;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner;
import com.shatteredpixel.shatteredpixeldungeon.items.Ankh;
import com.shatteredpixel.shatteredpixeldungeon.items.Amulet;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.SewerLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.HallsBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** TEST ONLY: prepare before the trigger; never call die/fail/win or construct an ending window. */
final class EndingScenarioFixtures {
    static boolean supports(String name){return Arrays.asList("death-ranking","death-restart","amulet-surface","ascension-start").contains(name);}

    static void prepare(String name,Hero hero){
        if(!supports(name))throw new IllegalArgumentException("Unknown ending fixture");
        if(name.equals("amulet-surface")||name.equals("ascension-start")){
            Game.switchScene(GameScene.class,new Game.SceneChangeCallback(){
                @Override public void beforeCreate(){
                    try{
                        if(name.equals("amulet-surface"))prepareSurfaceSource(hero);
                        else prepareAscensionSource(hero);
                    }catch(Exception error){throw new IllegalStateException("Amulet source preparation failed",error);}
                }
                @Override public void afterCreate(){}
            });
            return;
        }
        if(!hero.belongings.getAllItems(Ankh.class).isEmpty())throw new IllegalStateException("The source must naturally start without an Ankh");
        // Remove competing damage sources from the initial fixture only, so a public
        // wait reaches the original Poison tick rather than an arbitrary enemy hit.
        for(Mob mob:new ArrayList<>(Dungeon.level.mobs)){
            for(Buff buff:mob.buffs())Actor.remove(buff);
            Actor.remove(mob);
            if(mob.sprite!=null)mob.sprite.killAndErase();
        }
        Dungeon.level.mobs.clear();
        for(Actor actor:Actor.all())if(actor instanceof MobSpawner)Actor.remove(actor);
        hero.HP=1;
        Buff.affect(hero,Poison.class).set(30f);
        Dungeon.observe();hero.checkVisibleMobs();
    }

    private static void prepareSurfaceSource(Hero hero)throws Exception{
        if(Dungeon.depth!=1||Dungeon.branch!=0||!(Dungeon.level instanceof SewerLevel)
                ||Statistics.amuletObtained||hero.belongings.getItem(Amulet.class)!=null)
            throw new IllegalStateException("Amulet source requires the fresh first floor before acquisition");
        // Prepare both source floors before any tested pickup. Compact only the layout:
        // the original transition objects/types/destinations still perform 2 -> 1 -> surface.
        compactSewer(Dungeon.level);
        Dungeon.saveLevel(GamesInProgress.curSlot);
        Dungeon.depth=2;
        Level source=Dungeon.newLevel();Dungeon.level=source;
        if(!(source instanceof SewerLevel))throw new IllegalStateException("Expected the original second-floor SewerLevel");
        int entrance=compactSewer(source);
        source.drop(new Amulet(),entrance+source.width());
        Dungeon.switchLevel(source,entrance);
    }

    private static int compactSewer(Level level){
        level.mobs.clear();level.heaps.clear();level.blobs.clear();level.traps.clear();level.plants.clear();level.locked=false;
        LevelTransition entrance=level.getTransition(Dungeon.depth==1?LevelTransition.Type.SURFACE:LevelTransition.Type.REGULAR_ENTRANCE);
        LevelTransition exit=level.getTransition(LevelTransition.Type.REGULAR_EXIT);
        if(entrance==null||exit==null)throw new IllegalStateException("Missing original sewer transitions");
        int width=level.width();
        int x=Math.max(4,Math.min(width-5,entrance.cell()%width));
        int y=Math.max(4,Math.min(level.height()-5,entrance.cell()/width));
        level.map[entrance.cell()]=level.map[exit.cell()]=Terrain.EMPTY;
        for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++){
            int cell=(y+dy)*width+x+dx;level.map[cell]=Terrain.EMPTY;level.mapped[cell]=true;
        }
        entrance.centerCell=y*width+x;entrance.set(x,y,x,y);
        exit.centerCell=y*width+x+2;exit.set(x+2,y,x+2,y);
        level.map[entrance.cell()]=Terrain.ENTRANCE;level.map[exit.cell()]=Terrain.EXIT;
        level.buildFlagMaps();
        return entrance.cell();
    }

    private static void prepareAscensionSource(Hero hero){
        if(Statistics.amuletObtained||hero.belongings.getItem(Amulet.class)!=null||hero.buff(AscensionChallenge.class)!=null)
            throw new IllegalStateException("Ascension source must precede acquisition and the challenge");
        Dungeon.depth=25;Dungeon.branch=0;
        Level source=Dungeon.newLevel();Dungeon.level=source;
        if(!(source instanceof HallsBossLevel))throw new IllegalStateException("Expected the original HallsBossLevel");
        source.mobs.clear();source.heaps.clear();source.blobs.clear();source.traps.clear();source.plants.clear();source.locked=false;
        int entrance=source.getTransition(LevelTransition.Type.REGULAR_ENTRANCE).cell();
        int exit=source.getTransition(LevelTransition.Type.REGULAR_EXIT).cell();
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
            int cell=entrance+dy*source.width()+dx;
            if(source.insideMap(cell))source.map[cell]=Terrain.EMPTY;
        }
        // Match the original cleared-boss map gates; the original entrance object and
        // HallsBossLevel.activateTransition will still ask and attach the real challenge.
        source.map[entrance]=Terrain.ENTRANCE;source.map[exit]=Terrain.EXIT;source.buildFlagMaps();
        source.drop(new Amulet(),entrance-source.width());
        Dungeon.switchLevel(source,entrance);
        if(hero.pos!=entrance)throw new IllegalStateException("Original level rejected the prepared entrance");
    }

    static Map<String,Object> assertions(){
        return map("run_id",Dungeon.runId,"slot",GamesInProgress.curSlot,
                "ankh_count",Dungeon.hero==null?null:Dungeon.hero.belongings.getAllItems(Ankh.class).size(),
                "ranking_records",Rankings.INSTANCE.records==null?null:Rankings.INSTANCE.records.size(),"games_played",Rankings.INSTANCE.totalNumber,
                "games_won",Rankings.INSTANCE.wonNumber,"amulet_obtained",Statistics.amuletObtained,
                "ascended",Statistics.ascended,"highest_ascent",Statistics.highestAscent,
                "ascension_active",Dungeon.hero!=null&&Dungeon.hero.buff(AscensionChallenge.class)!=null,
                "level_class",Dungeon.level==null?null:Dungeon.level.getClass().getSimpleName());
    }
}
