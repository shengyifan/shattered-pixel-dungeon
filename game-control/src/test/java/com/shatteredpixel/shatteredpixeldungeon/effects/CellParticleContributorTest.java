package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.watabou.noosa.Visual;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.particles.Emitter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CellParticleContributorTest {
    @Test void glyphAndAlarmSignalsReadOnlyExistingSpriteCellsAndKnownEpisodeContributors() throws Exception {
        for(String kind:Arrays.asList("glyph_swiftness_active","glyph_flow_active","glyph_bulk_active","cursed_alarm")){
            Fixture f=new Fixture();SignalSprite sprite=new SignalSprite();f.scene.add(sprite);
            assertNull(sprite.ch,"No character, hidden enemy, armor or glyph level is needed");
            CellParticleCue signal=CellParticleCue.forCharacter(kind,sprite,f.factory,Visual.class);
            f.emitter.observeDraw(signal);Visual particle=f.particle(35,35);
            assertTrue(f.frame(signal));assertEquals(Collections.singletonList(new VisualCue(kind,22)),f.cues());
            assertFalse(GameplayVisualKinds.affectsIntent(kind), "Finite activation feedback is not a persistent mechanic state");
            sprite.cell=23;particle.x=51;f.level.heroFOV[23]=false;
            assertTrue(f.frame(signal));assertTrue(f.cues().isEmpty(),"A visible old cell cannot reveal the moving source in fog");
            f.level.heroFOV[23]=true;assertTrue(f.frame(signal));assertEquals(23,f.cues().get(0).cell);
            f.emitter.on=false;particle.kill();assertTrue(f.frame(signal));assertTrue(f.cues().isEmpty());
            particle.revive();sprite.revive();assertTrue(f.frame(signal));assertTrue(f.cues().isEmpty(),"A reused sprite cannot inherit an older signal binding");
            assertEquals(1,f.emitter.countLiving(),"Sampling does not manufacture extra particles");
        }
    }
    private static final class SignalSprite extends com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite {
        int cell=22;@Override public int renderedCell(){return cell;}
    }
    @Test void firstKnownContributorWaitsNaturallyButOffscreenDoesNotHideIt() throws Exception {
        Fixture f=new Fixture();assertFalse(f.frame());
        Visual particle=f.particle(35,29); // Above old camera, still in FOV.
        assertTrue(f.frame());assertEquals(Collections.singletonList(new VisualCue("falling_rock_warning",22)),f.cues());
        particle.kill();assertTrue(f.frame(),"Later particle flicker does not rearm the initial wait");
        assertEquals(1,f.cues().size(),"An ongoing observed warning episode survives natural particle gaps");
        f.emitter.on=false;assertTrue(f.frame());assertTrue(f.cues().isEmpty(),"Stopped empty source clears the warning");
        f.emitter.revive();f.emitter.observeDraw(f.observation);f.emitter.startDelayed(f.factory,.1f,0,.1f);
        assertFalse(f.frame(),"Pool reuse must require a new known presentation");
    }
    @Test void hiddenContributorCannotRevealWarningOrLatchFirstKnownAppearance() throws Exception {
        Fixture f=new Fixture();Visual particle=f.particle(35,51);f.level.heroFOV[32]=false;
        assertTrue(f.frame(),"A source with only unseen contributors cannot indefinitely hold input");
        assertTrue(f.cues().isEmpty());particle.kill();assertFalse(f.frame());
        f.particle(35,36);assertTrue(f.frame());assertEquals(1,f.cues().size());
    }
    @Test void partialParticleCanContributeKnownFragmentButHiddenAnchorCannotLeak() throws Exception {
        Fixture f=new Fixture();Visual particle=f.particle(47,35);particle.width=3;f.level.heroFOV[23]=false;
        assertTrue(f.frame());assertEquals(1,f.cues().size(),"Known part remains visible under infinite window semantics");
        f.level.heroFOV[22]=false;assertTrue(f.frame());assertTrue(f.cues().isEmpty());
    }
    @Test void stoppedFrozenInactiveAndHiddenSourcesCannotBlock() throws Exception {
        Fixture f=new Fixture();f.level.heroFOV[22]=false;assertTrue(f.frame());
        f.level.heroFOV[22]=true;f.emitter.on=false;assertTrue(f.frame());
        f.emitter.on=true;f.emitter.active=false;assertTrue(f.frame());
        f.emitter.active=true;f.emitter.frozen=true;assertTrue(f.frame());assertEquals(0,f.emitter.countLiving());
    }
    private static class ObservedEmitter extends Emitter { boolean frozen;@Override protected boolean isFrozen(){return frozen;} }
    private static class Fixture extends GameplayVisualTraversalTest.Fixture {
        final ObservedEmitter emitter=new ObservedEmitter();
        final Emitter.Factory factory=new Emitter.Factory(){@Override public void emit(Emitter emitter,int index,float x,float y){fail("Observation cannot emit");}};
        final CellParticleCue observation=new CellParticleCue("falling_rock_warning",22,factory,Visual.class);
        Fixture()throws Exception{scene.add(emitter);emitter.observeDraw(observation);emitter.startDelayed(factory,.1f,0,.1f);}
        Visual particle(float x,float y){Visual p=new Visual(x,y,1,1);emitter.add(p);return p;}
        boolean frame()throws Exception{
            return frame(observation);
        }
        boolean frame(CellParticleCue current)throws Exception{
            clear();((List<?>)GameplayVisualTraversalTest.get(collector,"particleObservations")).clear();
            collector.particleEmitterDrawn(emitter,current);
            return (Boolean)GameplayVisualTraversalTest.method("finishParticleDraws").invoke(collector);
        }
    }
}
