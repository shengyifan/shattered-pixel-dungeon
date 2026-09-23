package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.*;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.PointF;
import com.watabou.utils.Random;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameplayBurstTest {
    private static final Emitter.Factory FACTORY=new Emitter.Factory(){
        @Override public void emit(Emitter emitter,int index,float x,float y){fail("Observation must not manufacture particles");}
    };
    @Test void privateOccurrenceIdentityIsStableAcrossCountsButChangesForAnEqualNewBurst() throws Exception {
        try(Fixture f=new Fixture()){
            Emitter source=f.emitter();GameplayBurst.burst(source,FACTORY,20,"light_burst",22,true);
            f.particle(source,22);f.particle(source,22);List<VisualCue> first=f.frame(source);
            Object firstKey=GameplayVisualTraversalTest.method("occurrenceKey").invoke(f.collector);
            assertEquals(first,f.frame(source));
            assertEquals(firstKey,GameplayVisualTraversalTest.method("occurrenceKey").invoke(f.collector));
            f.particle(source,22);assertEquals(3,f.frame(source).get(0).appearance.get("count"));
            assertEquals(firstKey,GameplayVisualTraversalTest.method("occurrenceKey").invoke(f.collector),"A changed measurement is not a new occurrence");
            assertThrows(UnsupportedOperationException.class,()->((Set<?>)firstKey).clear());
            GameplayBurst.burst(source,FACTORY,20,"light_burst",22,true);
            f.particle(source,22);f.particle(source,22);assertEquals(first,f.frame(source),"Both public snapshots deliberately have the same content");
            Object nextKey=GameplayVisualTraversalTest.method("occurrenceKey").invoke(f.collector);
            assertNotEquals(firstKey,nextKey,"The same source's new native emitter episode must create its own event onset");
            assertEquals(Map.of("count",2,"basis","observed_particles"),f.cues().get(0).appearance);
            source.revive();assertTrue(f.frame(source).isEmpty());
            assertTrue(((Set<?>)GameplayVisualTraversalTest.method("occurrenceKey").invoke(f.collector)).isEmpty());
        }
    }
    @Test void newObserverOverloadStillNotifiesExistingFiveArgumentObservers() {
        int[] calls={0};RuntimeObserver observer=new RuntimeObserver(){
            @Override public void onVisualCues(String run,Object level,int depth,List<VisualCue> cues,boolean ready){calls[0]++;}
        };
        observer.onVisualCues("fixture",new Object(),1,Collections.emptyList(),true,Collections.emptySet());
        assertEquals(1,calls[0]);
    }
    @Test void measuredSignalCountsActualKnownContributorsNotRequestedQuantityOrOldTails() throws Exception {
        try(Fixture f=new Fixture()){
            Emitter emitter=f.emitter();Visual preceding=f.particle(emitter,22);
            GameplayBurst.burst(emitter,FACTORY,99,"light_burst",22,true);
            assertTrue(f.frame(emitter).isEmpty(),"Requested quantity is not a visible count");
            Visual one=f.particle(emitter,22),two=f.particle(emitter,22);
            f.level.heroFOV[23]=false;f.particle(emitter,23);
            Visual transparent=f.particle(emitter,22);transparent.alpha(0);
            Visual dead=f.particle(emitter,22);dead.kill();
            List<VisualCue> cues=f.frame(emitter);assertEquals(1,cues.size());
            assertEquals(Map.of("count",2,"basis","observed_particles"),cues.get(0).appearance);
            assertNull(cues.get(0).color);assertNull(cues.get(0).opacity);assertFalse(cues.toString().contains("99"));
            for(int i=0;i<5;i++)assertEquals(cues,f.frame(emitter),"Reading does not advance particles or consume input");
            preceding.kill();preceding.revive();assertEquals(3,f.frame(emitter).get(0).appearance.get("count"));
            one.kill();two.kill();preceding.kill();assertTrue(f.frame(emitter).isEmpty(),"Zero known contributors removes even an on-going finite burst");
        }
    }
    @Test void fixedSignalsCarryNoCountAndIndependentSourcesRetainMultiplicity() throws Exception {
        try(Fixture f=new Fixture()){
            Emitter first=f.emitter(),second=f.emitter();
            GameplayBurst.burst(first,FACTORY,3,"light_burst",22,false);
            GameplayBurst.burst(second,FACTORY,8,"light_burst",22,false);
            f.particle(first,22);f.particle(first,22);f.particle(second,22);
            List<VisualCue> cues=f.frame(first,second);assertEquals(2,cues.size());
            assertEquals(new VisualCue("light_burst",22),cues.get(0));assertEquals(cues.get(0),cues.get(1));
            f.level.heroFOV[22]=false;assertTrue(f.frame(first,second).isEmpty());
        }
    }
    @Test void explicitSourceEpisodeCannotAuthorizeUnreviewedRestartOrPooledSprite() throws Exception {
        try(Fixture f=new Fixture()){
            Emitter emitter=f.emitter();SignalSprite sprite=new SignalSprite();f.scene.add(sprite);
            GameplayBurst.burstForCharacter(emitter,FACTORY,4,"shadow_burst",sprite,true);
            f.particle(emitter,22);assertEquals(1,f.frame(emitter).get(0).appearance.get("count"));assertNull(sprite.ch);
            emitter.start(FACTORY,.1f,6);assertTrue(f.frame(emitter).isEmpty(),"A later unreviewed start cannot inherit an annotation");
            GameplayBurst.burstForCharacter(emitter,FACTORY,4,"shadow_burst",sprite,true);
            f.particle(emitter,22);assertEquals(1,f.frame(emitter).get(0).appearance.get("count"),"Old episode tails do not count twice");
            sprite.revive();assertTrue(f.frame(emitter).isEmpty());
            emitter.revive();assertNull(signal(emitter));
        }
    }
    @Test void timedStartAndBurstRetainExactlyTheirNativeCallsParametersAndRng() throws Exception {
        RuntimeObserver previous=Game.observer;
        try{
            Game.observer=observer(true);TrackingEmitter timed=new TrackingEmitter();
            Random.pushGenerator(719);GameplayBurst.start(timed,FACTORY,.3f,5,"notes",22,false);long tagged=Random.Long();Random.popGenerator();
            assertEquals(0,timed.bursts);assertEquals(1,timed.starts);
            assertEquals(.3f,field(timed,Emitter.class,"interval"));assertEquals(5,field(timed,Emitter.class,"quantity"));
            assertSame(FACTORY,field(timed,Emitter.class,"factory"));assertEquals(0,timed.countLiving());
            Game.observer=observer(false);TrackingEmitter nativeTimed=new TrackingEmitter();
            Random.pushGenerator(719);nativeTimed.start(FACTORY,.3f,5);long original=Random.Long();Random.popGenerator();
            assertEquals(original,tagged);
            TrackingEmitter burst=new TrackingEmitter();GameplayBurst.burst(burst,FACTORY,3,"light_burst",22,true);
            assertEquals(1,burst.bursts);assertEquals(1,burst.starts);assertEquals(0f,field(burst,Emitter.class,"interval"));
            assertEquals(3,field(burst,Emitter.class,"quantity"));assertNull(signal(burst),"Disabled observer attaches nothing");
        }finally{Game.observer=previous;}
    }
    @Test void nativeSpriteAndSplashGuardsDispatchFactoriesAndScopeArePreserved() throws Exception {
        try(Fixture f=new Fixture()){
            SignalSprite sprite=new SignalSprite();f.scene.add(sprite);sprite.visible=false;
            GameplayBurst.spriteBurst(sprite,0x123456,6,"colored_splash",true);
            assertEquals(1,sprite.bursts);assertEquals(0,f.world.countLiving());
            sprite.visible=true;GameplayBurst.spriteBurst(sprite,0x123456,6,"colored_splash",true);
            assertEquals(2,sprite.bursts);Emitter first=(Emitter)f.world.childrenSnapshot().get(0);
            assertEquals("SplashFactory",field(first,Emitter.class,"factory").getClass().getSimpleName());
            assertEquals(6,field(first,Emitter.class,"quantity"));assertNotNull(signal(first));
            f.particle(first,22);assertEquals(1,f.frame(first).get(0).appearance.get("count"));
            GameplayBurst.splash(new PointF(35,35),0x654321,7,"direct_splash",22,false);
            Emitter direct=(Emitter)f.world.childrenSnapshot().get(1);assertNotNull(signal(direct));assertEquals(7,field(direct,Emitter.class,"quantity"));
            Splash.at(new PointF(35,35),0x654321,7);
            Emitter ordinary=(Emitter)f.world.childrenSnapshot().get(2);assertNull(signal(ordinary),"A later generic Splash has no opt-in scope");
            SignalSprite broken=new SignalSprite(){@Override public void burst(int color,int count){throw new IllegalStateException("fixture failure");}};
            assertThrows(IllegalStateException.class,()->GameplayBurst.spriteBurst(broken,0,2,"failed",false));
            Splash.at(new PointF(35,35),0x654321,7);
            assertNull(signal((Emitter)f.world.childrenSnapshot().get(3)),"Finally clears failed scoped observation");
        }
    }
    private static RuntimeObserver observer(boolean enabled){return new RuntimeObserver(){@Override public boolean observesVisualCues(){return enabled;}};}
    private static class SignalSprite extends CharSprite {
        int bursts;SignalSprite(){x=y=32;width=height=8;}
        @Override public int renderedCell(){return 22;}
        @Override public void burst(int color,int count){bursts++;super.burst(color,count);}
    }
    private static final class TrackingEmitter extends Emitter {
        int bursts,starts;
        @Override public void burst(Factory factory,int quantity){bursts++;super.burst(factory,quantity);}
        @Override public void start(Factory factory,float interval,int quantity){starts++;super.start(factory,interval,quantity);}
    }
    private static final class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final RuntimeObserver previous=Game.observer;final Object previousScene;final Group world=new Group();
        Fixture()throws Exception{
            previousScene=field(null,GameScene.class,"scene");set(null,GameScene.class,"scene",scene);
            set(scene,GameScene.class,"emitters",world);scene.add(world);Game.observer=observer(true);
        }
        Emitter emitter(){Emitter result=new Emitter();world.add(result);return result;}
        Visual particle(Emitter emitter,int cell){Visual result=new Visual((cell%10)*16+2,(cell/10)*16+2,2,2);emitter.add(result);return result;}
        List<VisualCue> frame(Emitter...emitters)throws Exception{
            clear();((List<?>)GameplayVisualTraversalTest.get(collector,"particleObservations")).clear();
            for(Emitter emitter:emitters){CellParticleCue cue=signal(emitter);if(cue!=null)collector.particleEmitterDrawn(emitter,cue);}
            GameplayVisualTraversalTest.method("finishParticleDraws").invoke(collector);return cues();
        }
        @Override public void close()throws Exception{Game.observer=previous;set(null,GameScene.class,"scene",previousScene);}
    }
    private static CellParticleCue signal(Emitter emitter)throws Exception{return (CellParticleCue)field(emitter,Emitter.class,"drawObserver");}
    private static Object field(Object target,Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,Class<?> owner,String name,Object value)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
}
