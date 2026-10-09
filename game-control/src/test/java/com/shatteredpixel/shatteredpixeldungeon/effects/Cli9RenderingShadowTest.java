package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native hearing shadows are public drawn evidence, with no live enemy or unseen terrain added. */
class Cli9RenderingShadowTest {
    @Test void nativeShadowPublishesOnlyObservedArtworkAndMotionThroughUnknownFog() throws Exception {
        try (Fixture f=new Fixture()) {
            f.shadow.observeMovementShadow();f.sample();
            assertEquals(1,f.cues().size());VisualCue first=f.cues().get(0);
            assertEquals("movement_shadow",first.kind);assertEquals(22,first.cell);
            assertEquals(Collections.singletonMap("symbol","rat_portrait"),first.appearance);
            assertNull(first.sourceCell);assertNull(first.direction);assertNull(first.color);assertNull(first.opacity);
            f.shadow.x+=4;f.sample();assertEquals("east",f.cues().get(0).direction);
            assertNull(f.shadow.ch);assertFalse(f.level.heroFOV[22]);
            assertNull(f.level.visited,"Observation does not synthesize knowledge of terrain");
            assertFalse(GameplayVisualKinds.affectsIntent("movement_shadow"));
            f.shadow.alpha(.1f);f.sample();assertEquals(1,f.cues().size());
            f.shadow.alpha(0);f.sample();assertTrue(f.cues().isEmpty());
        }
    }

    @Test void ordinaryHiddenSpritesAndReusedOrDetachedShadowsDoNotGainTheHearingException() throws Exception {
        try(Fixture f=new Fixture()) {
            f.sample();assertTrue(f.cues().isEmpty());
            f.shadow.observeMovementShadow();f.sample();assertEquals(1,f.cues().size());
            f.shadow.visible=false;f.sample();assertTrue(f.cues().isEmpty());
            f.shadow.visible=true;f.shadow.revive();f.sample();assertTrue(f.cues().isEmpty());
            assertFalse(f.shadow.isObservedMovementShadow(),"A pooled lifetime requires a fresh native source tag");
            f.shadow.observeMovementShadow();f.scene.erase(f.shadow);f.sample();assertTrue(f.cues().isEmpty());
        }
    }

    private static final class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final CharSprite shadow=new CharSprite();
        final Level oldLevel=Dungeon.level;final RuntimeObserver oldObserver=Game.observer;
        final Object oldScene;final Map<Object,SmartTexture> cache;final SmartTexture oldTexture;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            oldScene=get(null,GameScene.class,"scene");set(null,GameScene.class,"scene",scene);
            set(scene,GameScene.class,"visualCueCollector",collector);Dungeon.level=level;
            Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}};
            Arrays.fill(level.heroFOV,false);
            cache=(Map<Object,SmartTexture>)get(null,TextureCache.class,"all");oldTexture=cache.get(Assets.Sprites.RAT);
            SmartTexture texture=allocate(SmartTexture.class);texture.width=texture.height=16;cache.put(Assets.Sprites.RAT,texture);
            shadow.texture=texture;shadow.frame(new RectF(0,0,1,1));shadow.x=32;shadow.y=26;
            scene.add(shadow);
        }
        void sample()throws Exception {
            clear();((List<?>)GameplayVisualTraversalTest.get(collector,"movingObservations")).clear();
            traverse();GameplayVisualTraversalTest.method("finishMovingDraws").invoke(collector);
        }
        public void close()throws Exception {
            Dungeon.level=oldLevel;Game.observer=oldObserver;set(null,GameScene.class,"scene",oldScene);
            if(oldTexture==null)cache.remove(Assets.Sprites.RAT);else cache.put(Assets.Sprites.RAT,oldTexture);
        }
    }
    private static Object get(Object o,Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,Class<?> c,String n,Object v)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    private static <T>T allocate(Class<T> c)throws Exception{Class<?> u=Class.forName("sun.misc.Unsafe");Field f=u.getDeclaredField("theUnsafe");f.setAccessible(true);return c.cast(u.getMethod("allocateInstance",Class.class).invoke(f.get(null),c));}
}
