package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.watabou.noosa.Visual;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** The former generic metrics path has exactly one reviewed gameplay density exception. */
class VisualMetricProjectionTest {
    @Test void densityUsesOnlyKnownActualFlamesAndNeverRequestedEmissionCounts() throws Exception {
        GameplayVisualTraversalTest.Fixture f=new GameplayVisualTraversalTest.Fixture();
        Arrays.fill(f.level.heroFOV,false);f.level.heroFOV[22]=true;
        Visual inside=new Visual(34,34,4,4),fogged=new Visual(34,18,4,4),partial=new Visual(34,30,4,4);
        for(Visual source:Arrays.asList(inside,fogged,partial))f.scene.add(source);
        for(Visual source:Arrays.asList(inside,fogged,partial))f.collector.particleMetricDrawn(source,"sacrificial_flames",22);
        assertEquals(2,f.densities().get("22").size(),"Known flame fragments count; wholly fogged flames do not");
        f.densities().clear();f.level.heroFOV[12]=true;
        for(Visual source:Arrays.asList(inside,fogged,partial))f.collector.particleMetricDrawn(source,"sacrificial_flames",22);
        assertEquals(3,f.densities().get("22").size());
        f.densities().clear();f.level.heroFOV[22]=false;
        for(Visual source:Arrays.asList(inside,fogged,partial))f.collector.particleMetricDrawn(source,"sacrificial_flames",22);
        assertTrue(f.densities().isEmpty(),"The flame's birth cell cannot be hidden");
    }
    @Test void unknownAndDecorativeMetricKindsAreNotCollected() throws Exception {
        GameplayVisualTraversalTest.Fixture f=new GameplayVisualTraversalTest.Fixture();
        Visual source=new Visual(34,34,4,4);f.scene.add(source);
        for(String kind:Arrays.asList("cell_particles","flames","spark","smoke"))f.collector.particleMetricDrawn(source,kind,22);
        f.collector.emitterMetricDrawn(null,new Object());assertTrue(f.densities().isEmpty());
    }
}
