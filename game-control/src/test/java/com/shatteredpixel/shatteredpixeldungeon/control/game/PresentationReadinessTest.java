package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.effects.Flare;
import com.shatteredpixel.shatteredpixeldungeon.effects.VisualCueCollector;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.SnowParticle;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import com.watabou.noosa.particles.Emitter;
import com.watabou.noosa.tweeners.PosTweener;
import com.watabou.utils.PointF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Native renderer continuations, synthetic textures, no game launch, actor turn, profile or GPU. */
class PresentationReadinessTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void nativeMotionAndAuraFollowMustFinishWithoutSubstitutingAnActorPosition() throws Exception {
        try(Fixture f=new Fixture()){
            CharSprite sprite=f.sprite();Flare aura=f.aura(sprite);assertTrue(f.frame());
            assertNull(sprite.ch,"The source boundary must not require a backing actor");
            sprite.move(22,23);assertFalse(f.frame());
            PosTweener motion=(PosTweener)SnapshotFields.read(sprite,"motion");
            PointF before=sprite.point();assertFalse(f.frame());assertEquals(before,sprite.point(),"Observation does not advance motion");
            Arrays.fill(f.level.heroFOV,false);assertTrue(f.frame(),"Hidden motion cannot hold input");
            Arrays.fill(f.level.heroFOV,true);assertFalse(f.frame(),"Camera clipping does not hide known motion");
            sprite.active=false;assertTrue(f.frame());sprite.active=true;
            f.mobs.erase(motion);assertTrue(f.frame(),"A detached motion cannot progress");f.mobs.add(motion);
            Game.elapsed=.2f;motion.update();assertFalse(sprite.isMoving);
            assertFalse(f.frame(),"Motion can finish after the sprite's aura update in the same native frame");
            assertEquals(22,f.cue("character_aura").cell,"The public cue keeps its actual old renderer anchor");
            sprite.update();assertTrue(f.frame());assertEquals(23,f.cue("character_aura").cell);
            aura.point(new PointF(aura.x-16,aura.y));aura.active=false;assertTrue(f.frame());aura.active=true;
            f.mobs.erase(aura);assertTrue(f.frame(),"Detached stale aura bindings never hold the sprite");
            f.mobs.add(aura);sprite.revive();assertTrue(f.frame(),"A prior sprite lifetime cannot own this aura's pending state");
        }
    }

    @Test void queuedChillUsesTheNativeFirstParticleAndFiniteTailBoundary() throws Exception {
        try(Fixture f=new Fixture()){
            CharSprite sprite=f.sprite();sprite.add(CharSprite.State.CHILLED);
            assertFalse(f.frame(),"A queued native visual addition has not been installed yet");
            sprite.update();Emitter emitter=(Emitter)SnapshotFields.read(sprite,"chilled");
            assertNotNull(emitter);assertNotNull(SnapshotFields.read(emitter,"drawObserver"));
            assertFalse(f.frame());assertNull(f.cue("character_chilled"));assertEquals(0,emitter.countLiving());
            SnowParticle particle=new SnowParticle();particle.reset(40,40);particle.am=1;emitter.add(particle);
            assertTrue(f.frame());assertEquals(22,f.cue("character_chilled").cell);
            particle.kill();assertTrue(f.frame());assertNotNull(f.cue("character_chilled"),"An observed active episode survives a natural particle gap");
            particle.revive();particle.am=1;
            sprite.remove(CharSprite.State.CHILLED);assertFalse(f.frame());sprite.update();
            assertNull(SnapshotFields.read(sprite,"chilled"));assertFalse(emitter.on);
            assertFalse(f.frame());assertNotNull(f.cue("character_chilled"),"Already visible residual snow remains public until it retires");
            particle.kill();assertTrue(f.frame());assertNull(f.cue("character_chilled"));
            emitter.revive();assertTrue(f.frame(),"Pool reuse cannot inherit the old particle episode");
        }
    }

    @Test void nativeItemDropKeepsGlowAbsentUntilLandingAndNeverGatesHiddenOrReusedSources() throws Exception {
        try(Fixture f=new Fixture()){
            ItemSprite sprite=new ItemSprite(ItemSpriteSheet.SOMETHING,new ItemSprite.Glowing(0x8800FF));
            f.scene.add(sprite);Heap heap=new Heap();heap.pos=22;heap.seen=true;heap.items.add(new Sword());
            sprite.heap=heap;sprite.place(22);assertTrue(f.frame());assertNotNull(f.cue("item_glow"));
            sprite.drop();assertFalse(f.frame());assertNull(f.cue("item_glow"));
            Arrays.fill(f.level.heroFOV,false);assertTrue(f.frame());Arrays.fill(f.level.heroFOV,true);
            sprite.active=false;assertTrue(f.frame());sprite.active=true;
            f.scene.erase(sprite);assertTrue(f.frame());f.scene.add(sprite);
            // The native update completes the drop. Suppress unrelated fixture audio at landing.
            heap.seen=false;Game.elapsed=.5f;sprite.update();heap.seen=true;sprite.visible=true;
            assertTrue(f.frame());assertEquals(22,f.cue("item_glow").cell);
            sprite.drop();assertFalse(f.frame());sprite.revive();assertTrue(f.frame());assertNull(f.cue("item_glow"));
        }
    }

    @Test void knownNativeDropKeepsItsObservedAnchorAcrossAnUpwardFovGap() throws Exception {
        try(Fixture f=new Fixture()){
            Arrays.fill(f.level.heroFOV,false);f.level.heroFOV[22]=true;
            ItemSprite sprite=new ItemSprite(ItemSpriteSheet.BLAND_CHUNKS,new ItemSprite.Glowing(0x8800FF));
            f.scene.add(sprite);Heap heap=new Heap();heap.pos=22;heap.seen=true;heap.items.add(new Sword());
            sprite.heap=heap;sprite.place(22);sprite.drop();assertFalse(f.frame());
            Object episode=sprite.gameplayDropEpisode();
            Game.elapsed=.01f;for(int i=0;i<19;i++)sprite.update();
            assertTrue(sprite.y+sprite.height()<=32,"The native small-item arc is now wholly above its known cell");
            assertFalse(f.frame(),"The same observed drop is still settling despite its transient upward footprint");
            assertNull(f.cue("item_glow"),"Retaining a readiness anchor cannot invent a landing cue");
            sprite.visible=false;assertTrue(f.frame());sprite.visible=true;assertTrue(f.frame(),"A hidden source loses its prior readiness anchor");
            sprite.drop();assertFalse(f.frame());for(int i=0;i<19;i++)sprite.update();assertFalse(f.frame());
            f.level.heroFOV[22]=false;assertTrue(f.frame(),"An anchor that leaves current FOV cannot hold input");
            f.level.heroFOV[22]=true;assertTrue(f.frame(),"Once scope was lost, hidden geometry cannot recreate an old readiness anchor");
            heap.items.add(new Sword());sprite.drop();assertNotSame(episode,sprite.gameplayDropEpisode());
            assertTrue(f.frame(),"A repeated native drop cannot borrow the prior episode's observed anchor");
            sprite.revive();assertTrue(f.frame());
        }
    }

    private static final class Grid extends Level {
        Grid(){width=height=10;length=100;heroFOV=new boolean[length];water=new boolean[length];map=new int[length];Arrays.fill(heroFOV,true);}
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }
    private static final class Fixture implements AutoCloseable {
        final Level previousLevel=Dungeon.level;final Camera previousCamera=Camera.main;
        final RuntimeObserver previousObserver=Game.observer;final float previousElapsed=Game.elapsed;
        final Object previousScene=SnapshotFields.read(GameScene.class,"scene");
        final Grid level=new Grid();final GameScene scene=new GameScene();final Group mobs=new Group(),emitters=new Group();
        final VisualCueCollector collector=new VisualCueCollector(scene);
        final Map<Object,SmartTexture> cache;final Map<String,SmartTexture> previous=new LinkedHashMap<>();
        final SmartTexture texture;
        @SuppressWarnings("unchecked") Fixture()throws Exception{
            Dungeon.level=level;Camera.main=new Camera(0,0,8,8,1);Camera.main.visible=false;
            Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}};
            set(null,GameScene.class,"scene",scene);set(scene,GameScene.class,"visualCueCollector",collector);
            set(scene,GameScene.class,"mobs",mobs);set(scene,GameScene.class,"emitters",emitters);scene.add(mobs);scene.add(emitters);
            set(collector,VisualCueCollector.class,"level",level);
            cache=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");
            texture=install(Assets.Sprites.ITEMS,256,512);install("1x1:-1",1,1);
        }
        SmartTexture install(String key,int width,int height)throws Exception{
            SmartTexture value=FloatingAppearanceTest.allocate(SmartTexture.class);value.width=width;value.height=height;
            previous.put(key,cache.put(key,value));return value;
        }
        CharSprite sprite(){CharSprite sprite=new CharSprite();sprite.texture=texture;sprite.width=sprite.height=16;mobs.add(sprite);sprite.place(22);return sprite;}
        Flare aura(CharSprite sprite)throws Exception{
            Flare aura=FloatingAppearanceTest.allocate(Flare.class);
            aura.exists=aura.alive=aura.active=aura.visible=true;aura.scale=new PointF(1,1);aura.origin=new PointF();aura.resetColor();
            set(aura,Flare.class,"nRays",5);set(aura,Flare.class,"visualRadius",20f);set(aura,Flare.class,"texture",texture);
            aura.observeCharacterAura().color(0x0088FF,true).show(sprite,0);set(sprite,CharSprite.class,"aura",aura);return aura;
        }
        boolean frame()throws Exception{
            ((Map<?,?>)SnapshotFields.read(collector,"offered")).clear();
            ((List<?>)SnapshotFields.read(collector,"particleObservations")).clear();
            set(collector,VisualCueCollector.class,"collecting",true);set(collector,VisualCueCollector.class,"sourcePresentationsReady",true);
            invoke("pruneObservedDrops",new Class<?>[0]);
            invoke("collectSources",new Class<?>[]{Gizmo.class},scene);
            return (Boolean)invoke("finishParticleDraws",new Class<?>[0])&&(Boolean)SnapshotFields.read(collector,"sourcePresentationsReady");
        }
        VisualCue cue(String kind){for(Object value:((Map<?,?>)SnapshotFields.read(collector,"offered")).values())if(kind.equals(((VisualCue)value).kind))return (VisualCue)value;return null;}
        Object invoke(String name,Class<?>[] types,Object...args)throws Exception{Method m=VisualCueCollector.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(collector,args);}
        @Override public void close()throws Exception{
            Dungeon.level=previousLevel;Camera.main=previousCamera;Game.observer=previousObserver;Game.elapsed=previousElapsed;
            set(null,GameScene.class,"scene",previousScene);
            for(Map.Entry<String,SmartTexture> entry:previous.entrySet())if(entry.getValue()==null)cache.remove(entry.getKey());else cache.put(entry.getKey(),entry.getValue());
        }
    }
    private static void set(Object value,Class<?> owner,String name,Object data)throws Exception{FloatingAppearanceTest.field(value,owner,name,data);}
}
