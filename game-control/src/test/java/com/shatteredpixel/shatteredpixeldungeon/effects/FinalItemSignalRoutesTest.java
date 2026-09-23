package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.ChallengeParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.ShadowParticle;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfChallenge;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.CurseInfusion;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.*;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.PointF;
import com.watabou.utils.Random;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinalItemSignalRoutesTest {
    @Test void nativeChallengeBoundaryAlreadyHasTypedKnownCellSignalsAndStopsWithItsSources() throws Exception {
        try(Fixture f=new Fixture()){
            ScrollOfChallenge.ChallengeArena arena=new ScrollOfChallenge.ChallengeArena();
            set(arena,ScrollOfChallenge.ChallengeArena.class,"arenaPositions",new ArrayList<>(Arrays.asList(22,23)));
            arena.fx(true);assertEquals(2,f.world.childrenSnapshot().size());
            Emitter known=(Emitter)f.world.childrenSnapshot().get(0),hidden=(Emitter)f.world.childrenSnapshot().get(1);
            assertEquals("challenge_arena",signal(known).kind);assertEquals(22,signal(known).observedCell());
            assertSame(ChallengeParticle.FACTORY,field(known,Emitter.class,"factory"));
            assertEquals(.05f,field(known,Emitter.class,"interval"));assertEquals(0,field(known,Emitter.class,"quantity"));
            Visual first=f.particle(known,ChallengeParticle.class,22);f.particle(hidden,ChallengeParticle.class,23);
            f.level.heroFOV[23]=false;assertEquals(Collections.singletonList(new VisualCue("challenge_arena",22)),f.frame(known,hidden));
            arena.fx(false);assertFalse(known.on);assertFalse(hidden.on);
            first.kill();assertTrue(f.frame(known,hidden).isEmpty());
            assertTrue(((List<?>)field(arena,ScrollOfChallenge.ChallengeArena.class,"arenaEmitters")).isEmpty());
            assertTrue(GameplayVisualKinds.affectsIntent("challenge_arena"));
        }
    }
    @Test void nativeInfusionShadowDoesNotDiscloseUnknownCurseOrItemOutcome() throws Exception {
        try(Fixture f=new Fixture()){
            Hero hero=allocate(Hero.class);hero.pos=22;set(null,Item.class,"curUser",hero);
            EquipableItem item=new EquipableItem(){@Override public boolean doEquip(Hero ignored){return true;}};
            item.levelKnown=false;item.cursedKnown=false;
            TestInfusion infusion=allocate(TestInfusion.class);assertTrue(infusion.accepts(item));infusion.apply(item);
            assertTrue(item.cursed,"The original native branch still performs its item mutation");assertFalse(item.cursedKnown);
            Emitter source=(Emitter)f.world.childrenSnapshot().get(0);
            assertSame(ShadowParticle.UP,field(source,Emitter.class,"factory"));assertEquals(5,field(source,Emitter.class,"quantity"));
            assertEquals(0f,field(source,Emitter.class,"interval"));f.particle(source,ShadowParticle.class,22);
            assertEquals(Collections.singletonList(new VisualCue("shadow_burst",22)),f.frame(source));
            assertNull(f.frame(source).get(0).appearance,"No item identity, outcome, level, or configured amount enters the signal");
            f.level.heroFOV[22]=false;assertTrue(f.frame(source).isEmpty());
            source.revive();assertNull(signal(source));assertFalse(GameplayVisualKinds.affectsIntent("shadow_burst"));
        }
    }
    private static class TestInfusion extends CurseInfusion {
        boolean accepts(Item item){return usableOnItem(item);}void apply(Item item){onItemSelected(item);}
    }
    private static final class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final RuntimeObserver previousObserver=Game.observer;final Level previousLevel=Dungeon.level;
        final Object previousScene,previousUser;final boolean previousDisplays=GameScene.updateItemDisplays;final Group world=new Group();
        Fixture()throws Exception{
            previousScene=field(null,GameScene.class,"scene");previousUser=field(null,Item.class,"curUser");
            set(null,GameScene.class,"scene",scene);set(scene,GameScene.class,"emitters",world);scene.add(world);
            Dungeon.level=level;Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}};
            Random.pushGenerator(503);
        }
        Visual particle(Emitter emitter,Class<? extends Image> type,int cell)throws Exception{
            Image p=allocate(type);p.exists=p.alive=p.active=p.visible=true;p.scale=new PointF(1,1);p.origin=new PointF();
            p.x=(cell%10)*16+3;p.y=(cell/10)*16+3;p.width=p.height=1;p.resetColor();p.texture=allocate(SmartTexture.class);emitter.add(p);return p;
        }
        List<VisualCue> frame(Emitter...sources)throws Exception{
            clear();((List<?>)GameplayVisualTraversalTest.get(collector,"particleObservations")).clear();
            for(Emitter source:sources){CellParticleCue cue=signal(source);if(cue!=null)collector.particleEmitterDrawn(source,cue);}
            GameplayVisualTraversalTest.method("finishParticleDraws").invoke(collector);return cues();
        }
        @Override public void close()throws Exception{
            Random.popGenerator();Game.observer=previousObserver;Dungeon.level=previousLevel;GameScene.updateItemDisplays=previousDisplays;
            set(null,GameScene.class,"scene",previousScene);set(null,Item.class,"curUser",previousUser);
        }
    }
    private static CellParticleCue signal(Emitter source)throws Exception{return (CellParticleCue)field(source,Emitter.class,"drawObserver");}
    private static Object field(Object target,Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,Class<?> owner,String name,Object value)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static <T>T allocate(Class<T> type)throws Exception{Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));}
}
