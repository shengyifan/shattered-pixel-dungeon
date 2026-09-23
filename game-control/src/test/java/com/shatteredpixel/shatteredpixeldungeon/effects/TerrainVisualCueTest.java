package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.*;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TerrainVisualCueTest {
    @Test void exactSelectedFramesDistinguishInteractiveTerrainAndOmitArt() {
        TerrainVisualCue arena=TerrainVisualCue.cavesArena();
        assertEquals("exposed_wiring",arena.selectedFrame(37,22).kind);
        assertEquals(Collections.singletonMap("state","closed"),arena.selectedFrame(40,22).appearance);
        assertEquals(Collections.singletonMap("state","broken"),arena.selectedFrame(32,22).appearance);
        assertEquals(Collections.singletonMap("state","destroyed"),arena.selectedFrame(38,22).appearance);
        assertNull(arena.selectedFrame(0,22));assertNull(arena.selectedFrame(37,-1));
        assertNotNull(TerrainVisualCue.vaultEntrance().selectedFrame(18,22));
        assertNull(TerrainVisualCue.vaultEntrance().selectedFrame(0,22),"Outer entrance art is not an interactive marker");
        assertEquals("vault_barrier",TerrainVisualCue.vaultBarrier().selectedFrame(22,22).kind);
        assertEquals("mine_exit",TerrainVisualCue.mineExit().selectedFrame(17,22).kind);
        assertNull(TerrainVisualCue.mineExit().selectedFrame(8,22));
    }
    @Test void selectedPresentationSurvivesCameraAndKnownFogButNeverInventsHiddenCenters() throws Exception {
        try(Fixture f=new Fixture(new int[]{37,40,38,-1,55})){
            Arrays.fill(f.level.heroFOV,false);f.level.visited[22]=true;f.level.mapped[23]=true;f.level.heroFOV[26]=true;
            f.sample();assertEquals(Arrays.asList(22,23,26),cells(f.cues()));
            assertFalse(f.cues().stream().anyMatch(c->c.cell==24),"A visible platform edge cannot expose its hidden center");
            assertEquals("closed",f.cues().get(1).appearance.get("state"));
            f.level.heroFOV[24]=true;f.sample();assertEquals(Arrays.asList(22,23,24,26),cells(f.cues()));
            assertEquals("destroyed",f.cues().get(2).appearance.get("state"));
            assertNull(f.tilemap.camera,"Semantic reads never populate camera caches");
            assertNull(field(f.tilemap,Tilemap.class,"buffer"),"Offscreen selected frames need no synthetic GPU upload");
        }
    }
    @Test void changesAndDisappearancesUseCurrentSelectedFramesAndReadIsPure() throws Exception {
        int[] frames={40};
        try(Fixture f=new Fixture(frames)){
            f.sample();assertEquals("closed",f.cues().get(0).appearance.get("state"));
            frames[0]=32;f.sample();assertEquals("broken",f.cues().get(0).appearance.get("state"));
            assertEquals(1,f.cues().size());assertArrayEquals(new int[]{32},frames);
            frames[0]=-1;f.sample();assertTrue(f.cues().isEmpty());
            frames[0]=40;f.scene.erase(f.tilemap);f.sample();assertTrue(f.cues().isEmpty());
            f.scene.add(f.tilemap);f.tilemap.alpha(0);f.sample();assertTrue(f.cues().isEmpty());
            f.tilemap.alpha(1);set(f.tilemap,Tilemap.class,"texture",null);f.sample();assertTrue(f.cues().isEmpty());
        }
    }
    @Test void dynamicWarningsNeverUseRememberedTerrainVisibility() throws Exception {
        GameplayVisualTraversalTest.Fixture f=new GameplayVisualTraversalTest.Fixture();
        Arrays.fill(f.level.heroFOV,false);f.level.visited=new boolean[100];f.level.visited[22]=true;
        Visual warning=new Visual(32,32,4,4);f.scene.add(warning);
        f.collector.cellVisualDrawn(warning,new VisualCue("red_target",22));assertTrue(f.cues().isEmpty());
        assertTrue(GameplayVisualKinds.affectsIntent("metal_gate"));assertTrue(GameplayVisualKinds.affectsIntent("tengu_trap_spark"));
    }
    private static List<Integer> cells(List<VisualCue> cues){List<Integer> result=new ArrayList<>();for(VisualCue cue:cues)result.add(cue.cell);return result;}
    private static final class Fixture extends GameplayVisualTraversalTest.Fixture implements AutoCloseable {
        final Level previousLevel=Dungeon.level;final RuntimeObserver previousObserver=Game.observer;
        final Object previousScene;final Tilemap tilemap;
        Fixture(int[] frames)throws Exception{
            previousScene=field(null,GameScene.class,"scene");set(null,GameScene.class,"scene",scene);
            set(scene,GameScene.class,"visualCueCollector",collector);Dungeon.level=level;
            Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}};
            level.visited=new boolean[100];level.mapped=new boolean[100];
            tilemap=allocate(Tilemap.class);tilemap.exists=tilemap.alive=tilemap.visible=tilemap.active=true;
            tilemap.scale=new PointF(1,1);tilemap.origin=new PointF();tilemap.resetColor();tilemap.x=tilemap.y=32;
            tilemap.width=frames.length*16;tilemap.height=16;
            TextureFilm film=allocate(TextureFilm.class);HashMap<Object,RectF> atlas=new HashMap<>();
            for(int i=0;i<64;i++)atlas.put(i,new RectF(0,0,1,1));set(film,TextureFilm.class,"frames",atlas);
            set(tilemap,Tilemap.class,"tileset",film);set(tilemap,Tilemap.class,"texture",allocate(SmartTexture.class));
            set(tilemap,Tilemap.class,"data",frames);set(tilemap,Tilemap.class,"mapWidth",frames.length);
            set(tilemap,Tilemap.class,"size",frames.length);set(tilemap,Tilemap.class,"cellW",16f);set(tilemap,Tilemap.class,"cellH",16f);
            tilemap.observeDraw(TerrainVisualCue.cavesArena());scene.add(tilemap);
        }
        void sample()throws Exception{clear();tilemap.observeGameplayVisuals();}
        @Override public void close()throws Exception{Dungeon.level=previousLevel;Game.observer=previousObserver;set(null,GameScene.class,"scene",previousScene);}
    }
    private static Object field(Object target,Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,Class<?> owner,String name,Object value)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static <T>T allocate(Class<T> type)throws Exception{Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));}
}
