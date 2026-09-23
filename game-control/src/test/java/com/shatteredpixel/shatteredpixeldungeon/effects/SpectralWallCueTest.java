package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.SpectralWallParticle;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.SkeletonKey;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.*;
import com.watabou.noosa.particles.PixelParticle;
import com.watabou.utils.PointF;
import com.watabou.utils.Random;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpectralWallCueTest {
    @Test void actualWallCellsRemainKnownThroughVisitedAndMappedFogWithoutHiddenCells() throws Exception {
        try(Fixture f=new Fixture()){
            f.particle(22);f.particle(22);f.particle(23);f.particle(24);f.particle(25);
            Arrays.fill(f.level.heroFOV,false);f.level.heroFOV[22]=true;f.level.visited[23]=true;f.level.mapped[24]=true;
            List<VisualCue> cues=f.sample();assertEquals(Arrays.asList(22,23,24),cells(cues));
            for(VisualCue cue:cues){assertEquals("spectral_wall",cue.kind);assertNull(cue.appearance);assertNull(cue.sourceCell);}
            assertTrue(GameplayVisualKinds.affectsIntent("spectral_wall"));
            Arrays.fill(f.level.heroFOV,false);Arrays.fill(f.level.visited,false);Arrays.fill(f.level.mapped,false);
            assertTrue(f.sample().isEmpty(),"Opaque unknown fog cannot expose actual hidden wall bricks");
        }
    }
    @Test void visibleRaisedFragmentDoesNotRevealAnUnknownWallOrigin() throws Exception {
        try(Fixture f=new Fixture()){
            SpectralWallParticle p=f.particle(22);p.y=18; // Native raised brick geometry can cross into a different tile.
            Arrays.fill(f.level.heroFOV,false);f.level.visited[12]=true;
            assertTrue(f.sample().isEmpty(),"A visible fragment must not disclose its unknown actual wall cell");
            f.level.visited[22]=true;assertEquals(Collections.singletonList(new VisualCue("spectral_wall",22)),f.sample());
            f.level.visited[12]=false;assertTrue(f.sample().isEmpty(),"Known origin alone does not invent an entirely hidden contributor");
        }
    }
    @Test void particleExpiryDetachAndSourceReuseClearTheWallWithoutAConfiguredTimer() throws Exception {
        try(Fixture f=new Fixture()){
            SpectralWallParticle p=f.particle(22);assertEquals(1,f.sample().size());
            p.kill();assertTrue(f.sample().isEmpty());p.revive();assertTrue(f.sample().isEmpty(),"Revive alone cannot reuse an old recorded wall origin");
            p.reset(35,39);assertEquals(1,f.sample().size());
            f.scene.erase(f.emitter);assertTrue(f.sample().isEmpty());
            f.scene.add(f.emitter);assertTrue(f.sample().isEmpty(),"Reattached native source has a new lifetime and requires a new explicit binding");
            f.blob.use(f.emitter);assertEquals(1,f.sample().size());
            f.emitter.on=false;assertTrue(f.sample().isEmpty());
            f.emitter.revive();assertNull(field(f.emitter,com.watabou.noosa.particles.Emitter.class,"drawObserver"));
        }
    }
    @Test void unchangedPixelsIgnoreBlobVolumeCoverageAndRequestedLifetime() throws Exception {
        try(Fixture f=new Fixture()){
            SpectralWallParticle p=f.particle(22);List<VisualCue> before=f.sample();
            assertEquals(1,before.size());assertEquals(0,f.blob.volume);assertNull(f.blob.cur);
            f.blob.volume=99999;f.blob.cur=new int[100];Arrays.fill(f.blob.cur,99999);
            float x=p.x,y=p.y;long life=p.observationLifetime();
            for(int i=0;i<4;i++)assertEquals(before,f.sample());
            assertEquals(x,p.x);assertEquals(y,p.y);assertEquals(life,p.observationLifetime());
            assertNull(before.get(0).appearance,"No volume, coverage, timer, RGB, size, or particle density is exported");
        }
    }
    private static List<Integer> cells(List<VisualCue> cues){List<Integer> cells=new ArrayList<>();for(VisualCue cue:cues)cells.add(cue.cell);return cells;}
    private static final class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final RuntimeObserver previousObserver=Game.observer;final Level previousLevel=Dungeon.level;final int previousDepth=Dungeon.depth;
        final Object previousScene;final SkeletonKey.KeyWall blob;final BlobEmitter emitter;
        Fixture()throws Exception{
            previousScene=field(null,GameScene.class,"scene");set(null,GameScene.class,"scene",scene);
            set(scene,GameScene.class,"visualCueCollector",collector);Dungeon.level=level;Dungeon.depth=1;
            level.visited=new boolean[100];level.mapped=new boolean[100];
            Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}};
            Random.pushGenerator(883);blob=new SkeletonKey.KeyWall();emitter=new BlobEmitter(blob);scene.add(emitter);
            assertSame(SpectralWallParticle.FACTORY,field(emitter,com.watabou.noosa.particles.Emitter.class,"factory"));
            assertEquals(.02f,field(emitter,com.watabou.noosa.particles.Emitter.class,"interval"));
        }
        SpectralWallParticle particle(int cell)throws Exception{
            SpectralWallParticle p=allocate(SpectralWallParticle.class);p.exists=p.alive=p.visible=p.active=true;
            p.scale=new PointF(1,1);p.origin=new PointF(.5f,.5f);p.width=p.height=1;p.resetColor();p.am=.6f;
            p.texture=allocate(SmartTexture.class);set(p,PixelParticle.class,"lifespan",4f);
            p.reset((cell%10)*16+3,(cell/10)*16+7);emitter.add(p);return p;
        }
        List<VisualCue> sample()throws Exception{clear();emitter.observeGameplayVisuals();return cues();}
        @Override public void close()throws Exception{
            Random.popGenerator();Dungeon.level=previousLevel;Dungeon.depth=previousDepth;Game.observer=previousObserver;set(null,GameScene.class,"scene",previousScene);
        }
    }
    private static Object field(Object target,Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,Class<?> owner,String name,Object value)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static <T>T allocate(Class<T> type)throws Exception{Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));}
}
