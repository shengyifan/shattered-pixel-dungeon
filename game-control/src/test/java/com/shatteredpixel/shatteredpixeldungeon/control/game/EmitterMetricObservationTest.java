package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.Visual;
import com.watabou.noosa.particles.Emitter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EmitterMetricObservationTest {
    @Test void semanticObservationRunsSeparatelyAndEpisodesChangeOnlyOnNewEmissionOrReuse(){
        RuntimeObserver previous=Game.observer;
        List<String> order=new ArrayList<>();
        Game.observer=new RuntimeObserver(){
            @Override public boolean observesVisualCues(){return true;}
        };
        try{
            Emitter source=new Emitter();
            source.add(new Visual(0,0,1,1){@Override public boolean isVisible(){return true;}@Override public void draw(){order.add("native");}});
            source.observeDraw(emitter->order.add("cue"));
            Emitter.Factory factory=new Emitter.Factory(){@Override public void emit(Emitter emitter,int index,float x,float y){throw new AssertionError("Observation emitted a particle");}};
            source.startDelayed(factory,1f,2,1f);
            Object first=source.observedDrawEpisode();assertNotNull(first);
            source.draw();source.draw();assertSame(first,source.observedDrawEpisode());
            assertEquals(Arrays.asList("native","native"),order);
            source.observeGameplayVisuals();assertEquals(Arrays.asList("native","native","cue"),order);
            assertSame(first,source.observedDrawEpisode(),"Read-only observation does not create an emission episode");
            source.startDelayed(factory,1f,2,1f);Object second=source.observedDrawEpisode();assertNotSame(first,second);
            source.revive();assertNotSame(second,source.observedDrawEpisode());
            source.observeGameplayVisuals();assertEquals(3,order.size(),"Reuse clears the previous semantic observer");

        }finally{Game.observer=previous;}
    }
}
