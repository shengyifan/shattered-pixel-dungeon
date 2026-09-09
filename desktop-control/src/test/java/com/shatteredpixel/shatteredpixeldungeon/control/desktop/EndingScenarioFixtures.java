package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.Rankings;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner;
import com.shatteredpixel.shatteredpixeldungeon.items.Ankh;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** TEST ONLY: prepare immediately before death; never call die/fail or construct an ending window. */
final class EndingScenarioFixtures {
    static boolean supports(String name){return Arrays.asList("death-ranking","death-restart").contains(name);}

    static void prepare(String name,Hero hero){
        if(!supports(name))throw new IllegalArgumentException("Unknown ending fixture");
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

    static Map<String,Object> assertions(){
        return map("run_id",Dungeon.runId,"slot",GamesInProgress.curSlot,
                "ankh_count",Dungeon.hero==null?null:Dungeon.hero.belongings.getAllItems(Ankh.class).size(),
                "ranking_records",Rankings.INSTANCE.records==null?null:Rankings.INSTANCE.records.size(),"games_played",Rankings.INSTANCE.totalNumber,
                "games_won",Rankings.INSTANCE.wonNumber);
    }
}
