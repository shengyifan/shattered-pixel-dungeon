package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.SacrificialFire;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.Challenge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.PrismaticImage;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.Noisemaker;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.effects.CellEmitter;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.FlameParticle;
import java.util.Arrays;

/** Test-only preparation; observations thereafter come from ordinary actor updates and real drawing. */
final class ActorItemVisualFixtures {
    static boolean supports(String name){return Arrays.asList("noisemaker","prismatic","sacrificial","particle-counts","spectator").contains(name);}
    static void prepare(String name,Hero hero){
        if(name.equals("noisemaker")){
            Noisemaker bomb=new Noisemaker();
            Dungeon.level.drop(bomb,hero.pos+2).sprite.drop();
            bomb.fuse=new Noisemaker.NoisemakerFuse().ignite(bomb);
            Actor.addDelayed(bomb.fuse,2);
        }else if(name.equals("prismatic")){
            PrismaticImage image=new PrismaticImage();image.duplicate(hero,1);
            image.pos=hero.pos+2;image.state=image.PASSIVE;GameScene.add(image);
            image.damage(10000,ActorItemVisualFixtures.class);
        }else if(name.equals("sacrificial")){
            GameScene.add(Blob.seed(hero.pos+1,10,SacrificialFire.class));
        }else if(name.equals("particle-counts")){
            CellEmitter.center(hero.pos-2).burst(FlameParticle.FACTORY,3);
            CellEmitter.center(hero.pos+2).burst(FlameParticle.FACTORY,7);
        }else if(name.equals("spectator")){
            Rat rat=new Rat();rat.pos=hero.pos+2;rat.state=rat.PASSIVE;GameScene.add(rat);
            Buff.prolong(rat,Challenge.SpectatorFreeze.class,1000f);
        }else throw new IllegalArgumentException("Unknown actor/item visual fixture");
    }
}
