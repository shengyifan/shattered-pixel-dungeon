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
    @Test void stoppedSentryTailMustFinishBeforeTheWarningBoundaryIsReady() throws Exception {
        Fixture f=new Fixture("sentry_charge_particles",true);Visual particle=f.particle(35,35);
        assertTrue(f.frame());assertEquals(Collections.singletonList(new VisualCue("sentry_charge_particles",22)),f.cues());
        f.emitter.on=false;
        assertFalse(f.frame(),"A stopped source still has a visible warning tail which can disappear on the next native update");
        assertFalse(f.frame(),"Observation alone cannot advance or consume the remaining particle");
        assertEquals(1,f.emitter.countLiving());
        assertEquals(Collections.singletonList(new VisualCue("sentry_charge_particles",22)),f.cues(),"Pending presentation retains its public warning");
        particle.kill();
        assertTrue(f.frame());assertTrue(f.cues().isEmpty(),"The settled boundary must already show the cleared warning");
    }
    @Test void stoppedSentryTailCannotWaitForFrozenOrInactiveNativeUpdates() throws Exception {
        Fixture f=new Fixture("sentry_charge_particles",true);Visual particle=f.particle(35,35);assertTrue(f.frame());f.emitter.on=false;
        f.emitter.frozen=true;
        assertTrue(f.frame(),"Frozen particle updates cannot finish a pending tail");
        assertEquals(1,f.cues().size(),"Readiness must not erase a still-visible frozen warning");
        f.emitter.frozen=false;f.emitter.active=false;
        assertTrue(f.frame(),"An inactive source cannot make progress");assertEquals(1,f.cues().size());
        f.emitter.active=true;f.scene.active=false;
        assertTrue(f.frame(),"An inactive ancestor also prevents particle progress");assertEquals(1,f.cues().size());
        f.scene.active=true;
        assertFalse(f.frame(),"Resuming normal updates restores the finite tail boundary");
        particle.active=false;
        assertTrue(f.frame(),"A visible but inactive contributor cannot finish its tail");
        assertEquals(1,f.cues().size(),"An inactive contributor still preserves its public warning");
        particle.active=true;
        assertFalse(f.frame(),"Only a contributor able to update may hold the retirement boundary");
    }
    @Test void stoppedSentryTailOnlyWaitsForPublicContributors() throws Exception {
        Fixture f=new Fixture("sentry_charge_particles",true);Visual particle=f.particle(35,35);assertTrue(f.frame());f.emitter.on=false;
        f.level.heroFOV[22]=false;
        assertTrue(f.frame());assertTrue(f.cues().isEmpty(),"A hidden anchor cannot reveal a tail or hold input");
        f.level.heroFOV[22]=true;particle.y=51;f.level.heroFOV[32]=false;
        assertTrue(f.frame());assertTrue(f.cues().isEmpty(),"Unseen particles cannot delay an otherwise public boundary");
        f.level.heroFOV[32]=true;
        assertFalse(f.frame());assertEquals(1,f.cues().size(),"A public tail still matters outside the anchor cell");
        particle.visible=false;
        assertTrue(f.frame());assertTrue(f.cues().isEmpty(),"Native invisible particles do not contribute a public tail");
    }
    @Test void sentryFirstParticleAndActiveEpisodeGapsKeepSeparateReadinessRules() throws Exception {
        Fixture f=new Fixture("sentry_charge_particles",true);assertFalse(f.frame());assertEquals(0,f.emitter.countLiving());
        Visual particle=f.particle(35,35);assertTrue(f.frame());particle.kill();
        assertTrue(f.frame(),"An active episode already observed once must not wait on each emission gap");
        assertEquals(Collections.singletonList(new VisualCue("sentry_charge_particles",22)),f.cues());
        f.emitter.on=false;assertTrue(f.frame());assertTrue(f.cues().isEmpty());
        f.emitter.revive();f.emitter.observeDraw(f.observation);f.emitter.startDelayed(f.factory,.1f,0,.1f);
        assertFalse(f.frame(),"A reused source must establish its own first visible contributor");
        assertEquals(0,f.emitter.countLiving(),"Readiness sampling cannot emit the missing first particle");
    }
    @Test void retirementWaitRequiresTheNativeSourceOptIn() throws Exception {
        Fixture unreviewed=new Fixture("sentry_charge_particles",false);unreviewed.particle(35,35);
        assertTrue(unreviewed.frame());unreviewed.emitter.on=false;
        assertTrue(unreviewed.frame(),"The same cue kind does not authorize a retirement wait for another source");
        assertEquals(1,unreviewed.cues().size(),"An unmarked source remains publicly observable");
        Fixture chilled=new Fixture("character_chilled",true);Visual snow=chilled.particle(35,35);
        assertTrue(chilled.frame());chilled.emitter.on=false;
        assertFalse(chilled.frame(),"A reviewed chilled source follows the same finite retirement contract");
        assertEquals(Collections.singletonList(new VisualCue("character_chilled",22)),chilled.cues());
        snow.kill();assertTrue(chilled.frame());assertTrue(chilled.cues().isEmpty());
    }
    @Test void chilledFirstKnownPresentationWaitIsSourceOptInAndResetsOnReuse() throws Exception {
        Fixture ordinary=new Fixture("character_chilled",false);ordinary.level.heroFOV[12]=false;ordinary.particle(35,19);
        assertTrue(ordinary.frame(),"A cue kind alone cannot make hidden contributors hold input");
        assertTrue(ordinary.cues().isEmpty());

        Fixture chilled=new Fixture("character_chilled",true);chilled.observation.awaitFirstKnownPresentation();
        chilled.level.heroFOV[12]=false;Visual snow=chilled.particle(35,19);
        assertFalse(chilled.frame(),"A reviewed source with a known anchor waits while its first snow is still above the known cells");
        assertTrue(chilled.cues().isEmpty(),"Waiting must not publish the hidden snow as an observed cue");
        chilled.level.heroFOV[22]=false;
        assertTrue(chilled.frame(),"The first-presentation wait cannot disclose an unknown anchor");
        assertTrue(chilled.cues().isEmpty());
        chilled.level.heroFOV[22]=true;chilled.emitter.frozen=true;
        assertTrue(chilled.frame(),"Frozen snow cannot progress into a known cell");
        chilled.emitter.frozen=false;
        assertFalse(chilled.frame(),"Neither a hidden anchor nor frozen updates establish a first presentation");
        assertEquals(1,chilled.emitter.countLiving(),"Observation cannot emit a visible replacement particle");
        snow.y=35;
        assertTrue(chilled.frame());assertEquals(Collections.singletonList(new VisualCue("character_chilled",22)),chilled.cues());
        snow.kill();assertTrue(chilled.frame(),"An observed active episode still survives ordinary particle gaps");

        chilled.emitter.revive();chilled.emitter.observeDraw(chilled.observation);
        chilled.emitter.startDelayed(chilled.factory,.1f,0,.1f);Visual reusedSnow=chilled.particle(35,19);
        assertFalse(chilled.frame(),"A reused source cannot inherit the previous episode's first known presentation");
        assertTrue(chilled.cues().isEmpty());
        reusedSnow.y=35;assertTrue(chilled.frame());assertEquals(1,chilled.cues().size());
    }
    private static class ObservedEmitter extends Emitter { boolean frozen;@Override protected boolean isFrozen(){return frozen;} }
    private static class Fixture extends GameplayVisualTraversalTest.Fixture {
        final ObservedEmitter emitter=new ObservedEmitter();
        final Emitter.Factory factory=new Emitter.Factory(){@Override public void emit(Emitter emitter,int index,float x,float y){fail("Observation cannot emit");}};
        final CellParticleCue observation;
        Fixture()throws Exception{this("falling_rock_warning",false);}
        Fixture(String kind,boolean awaitTail)throws Exception{
            observation=new CellParticleCue(kind,22,factory,Visual.class);
            if(awaitTail)observation.awaitRetirement();
            scene.add(emitter);emitter.observeDraw(observation);emitter.startDelayed(factory,.1f,0,.1f);
        }
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
