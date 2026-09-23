package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.*;
import com.watabou.noosa.particles.Emitter;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameplayVisualTraversalTest {
    @Test void particlesOfOneNativeEffectShareMeaningWhileIndependentEffectsRemainSeparate() throws Exception {
        Fixture f=new Fixture();Visual first=new Visual(32,32,2,2),second=new Visual(34,34,2,2),third=new Visual(33,33,2,2);
        Group effect=new Group(){@Override public void observeGameplayVisuals(){
            f.collector.movingVisualDrawn(first,new VisualCue("magic_fire_cone",22));
            f.collector.movingVisualDrawn(second,new VisualCue("magic_fire_cone",22));
        }};
        effect.add(first);effect.add(second);f.scene.add(effect);
        f.traverse();method("finishMovingDraws").invoke(f.collector);
        assertEquals(Collections.singletonList(new VisualCue("magic_fire_cone",22)),f.cues());
        Group independent=new Group(){@Override public void observeGameplayVisuals(){
            f.collector.movingVisualDrawn(third,new VisualCue("magic_fire_cone",22));
        }};
        independent.add(third);f.scene.add(independent);f.clear();
        ((List<?>)get(f.collector,"movingObservations")).clear();f.traverse();method("finishMovingDraws").invoke(f.collector);
        assertEquals(2,f.cues().size());assertEquals(f.cues().get(0),f.cues().get(1));
        assertNull(f.cues().get(0).appearance,"Individual coordinates and vectors are not serialized");
    }
    @Test void independentSameCellWarningsKeepMultiplicityButRepeatedSourceCallbacksDoNot() throws Exception {
        Fixture f=new Fixture();Visual one=new Visual(32,32,4,4),two=new Visual(32,32,4,4);
        f.scene.add(one);f.scene.add(two);VisualCue warning=new VisualCue("red_target",22);
        f.collector.cellVisualDrawn(one,warning);f.collector.cellVisualDrawn(one,warning);assertEquals(1,f.cues().size());
        f.collector.cellVisualDrawn(two,warning);assertEquals(Arrays.asList(warning,warning),f.cues());
        f.clear();one.kill();f.collector.cellVisualDrawn(one,warning);f.collector.cellVisualDrawn(two,warning);
        assertEquals(Collections.singletonList(warning),f.cues());
    }
    @Test void onlyReviewedPersistentMeaningsInvalidateAnIntent() {
        for(String kind:Arrays.asList("black_goo_droplets","red_target","bomb_countdown_2","lotus_range","summoning_bones","character_aura"))
            assertTrue(GameplayVisualKinds.affectsIntent(kind));
        for(String kind:Arrays.asList("sacrificial_flames","loot_flare","surprise_mark","wound_mark","spell_icon","missile_projectile","unknown"))
            assertFalse(GameplayVisualKinds.affectsIntent(kind));
    }
    @Test void existingOffscreenSourcesAreVisitedWithoutDrawUpdateCameraCacheOrOcclusion() throws Exception {
        Camera previous=Camera.main;Camera.main=new Camera(0,0,16,16,1);
        try {
            Fixture f=new Fixture(); Group layer=new Group();f.scene.add(layer);
            Visual source=new Visual(64,64,4,4){
                @Override public boolean isVisible(){throw new AssertionError("Camera visibility must not be consulted");}
                @Override public void draw(){fail("Observation cannot draw");}
                @Override public void update(){fail("Observation cannot update");}
                @Override public void observeGameplayVisuals(){f.collector.cellVisualDrawn(this,new VisualCue("red_target",44));}
            };
            layer.add(source);f.traverse();
            assertEquals(Collections.singletonList(new VisualCue("red_target",44)),f.cues());
            assertNull(source.camera);assertNull(layer.camera);
            Camera.main.scroll.set(700,500);Camera.main.zoom=4;Camera.main.visible=false;
            f.clear();f.traverse();assertEquals(1,f.cues().size(),"Infinite window semantics do not depend on camera visibility");
            f.level.heroFOV[44]=false;f.clear();f.traverse();assertTrue(f.cues().isEmpty());
            f.level.heroFOV[44]=true;source.visible=false;f.clear();f.traverse();assertTrue(f.cues().isEmpty());
            source.visible=true;source.kill();f.clear();f.traverse();assertTrue(f.cues().isEmpty());
            source.revive();layer.erase(source);f.clear();f.collector.cellVisualDrawn(source,new VisualCue("red_target",44));
            assertTrue(f.cues().isEmpty(),"Detached or prior-scene sources cannot leak");
        } finally {Camera.main=previous;}
    }
    @Test void endpointInFogBecomesOnlyKnownPhysicalFragments() throws Exception {
        Fixture f=new Fixture();Visual beam=new Visual(16,34,80,2);f.scene.add(beam);
        Arrays.fill(f.level.heroFOV,false);f.level.heroFOV[22]=true;f.level.heroFOV[23]=true;
        f.collector.cellVisualDrawn(beam,new VisualCue("death_ray",26,21,null));
        assertEquals(1,f.cues().size());VisualCue fragment=f.cues().get(0);
        assertEquals(22,fragment.cell);assertNull(fragment.sourceCell);assertNull(fragment.direction);
        assertEquals(Arrays.asList(22,23),fragment.appearance.get("cells"));assertEquals(true,fragment.appearance.get("partial"));
        assertNull(fragment.color);assertNull(fragment.opacity);
    }
    @Test void rotatedQuadFootprintDoesNotInventCellsInItsBoundingBox() {
        Visual beam=new Visual(16,16,64,1);beam.angle=45;
        List<Integer> cells=VisualCueProjection.worldFootprint(beam,10,100,16);
        assertTrue(cells.contains(22));assertFalse(cells.contains(14));assertFalse(cells.contains(41));
    }
    @Test void semanticEmitterReadIsIndependentOfNativeDrawAndDoesNotEmit() {
        Emitter emitter=new Emitter();int[] reads={0};
        emitter.observeDraw(source->{reads[0]++;assertEquals(0,source.countLiving());});
        Emitter.Factory factory=new Emitter.Factory(){@Override public void emit(Emitter source,int index,float x,float y){fail("Inspection emitted");}};
        emitter.startDelayed(factory,1,0,1);emitter.observeGameplayVisuals();emitter.observeGameplayVisuals();
        assertEquals(2,reads[0]);assertEquals(0,emitter.countLiving());assertTrue(emitter.isEmitting(factory));
        emitter.revive();emitter.observeGameplayVisuals();assertEquals(2,reads[0],"Provider cannot survive pool reuse");
    }
    @Test void onlyReviewedSacrificialDensityCountsDistinctKnownLiveContributors() throws Exception {
        Fixture f=new Fixture();Visual flame=new Visual(32,32,3,3);f.scene.add(flame);
        f.collector.particleMetricDrawn(flame,"cell_particles",22);assertTrue(f.densities().isEmpty());
        f.collector.particleMetricDrawn(flame,"sacrificial_flames",22);
        f.collector.particleMetricDrawn(flame,"sacrificial_flames",22);
        assertEquals(1,f.densities().get("22").size());
        Visual hidden=new Visual(48,32,3,3);f.scene.add(hidden);f.level.heroFOV[23]=false;
        f.collector.particleMetricDrawn(hidden,"sacrificial_flames",22);assertEquals(1,f.densities().get("22").size());
        flame.kill();f.collector.particleMetricDrawn(flame,"sacrificial_flames",22);assertEquals(1,f.densities().get("22").size());
    }
    static class Fixture {
        final GameScene scene=new GameScene();final TestLevel level=new TestLevel();final VisualCueCollector collector=new VisualCueCollector(scene);
        Fixture() throws Exception {set(collector,"collecting",true);set(collector,"level",level);}
        void traverse() throws Exception {method("collectSources",Gizmo.class).invoke(collector,scene);}
        void clear()throws Exception{((Map<?,?>)get(collector,"offered")).clear();}
        @SuppressWarnings("unchecked") List<VisualCue> cues()throws Exception{return new ArrayList<>(((Map<String,VisualCue>)get(collector,"offered")).values());}
        @SuppressWarnings("unchecked") Map<String,Set<Visual>> densities()throws Exception{return (Map<String,Set<Visual>>)get(collector,"densities");}
    }
    static final class TestLevel extends Level {
        TestLevel(){width=height=10;length=100;heroFOV=new boolean[length];Arrays.fill(heroFOV,true);}
        @Override protected boolean build(){return false;}@Override protected void createMobs(){}@Override protected void createItems(){}
        @Override public String tilesTex(){return "";}@Override public String waterTex(){return "";}
    }
    static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static Method method(String name,Class<?>...types)throws Exception{Method m=VisualCueCollector.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;}
}
