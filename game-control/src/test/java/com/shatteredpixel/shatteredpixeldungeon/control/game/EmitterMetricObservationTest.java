package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.Visual;
import com.watabou.noosa.particles.Emitter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EmitterMetricObservationTest {
    @Test void metricsRunAfterNativeChildrenAndEpisodeChangesOnlyOnNewEmissionOrReuse(){
        RuntimeObserver previous=Game.observer;
        List<String> order=new ArrayList<>();List<Object> episodes=new ArrayList<>();
        Game.observer=new RuntimeObserver(){
            @Override public boolean observesVisualCues(){return true;}
            @Override public void onEmitterDraw(Emitter source,Object episode){order.add("metric");episodes.add(episode);}
        };
        try{
            Emitter source=new Emitter();
            source.add(new Visual(0,0,1,1){@Override public boolean isVisible(){return true;}@Override public void draw(){order.add("native");}});
            source.observeDraw(emitter->order.add("cue"));
            Emitter.Factory factory=new Emitter.Factory(){@Override public void emit(Emitter emitter,int index,float x,float y){throw new AssertionError("Observation emitted a particle");}};
            source.startDelayed(factory,1f,2,1f);
            source.draw();source.draw();
            assertEquals(Arrays.asList("native","cue","metric","native","cue","metric"),order);
            assertSame(episodes.get(0),episodes.get(1));
            source.startDelayed(factory,1f,2,1f);source.draw();assertNotSame(episodes.get(0),episodes.get(2));
            source.revive();source.draw();assertNotSame(episodes.get(2),episodes.get(3));
            Game.observer=RuntimeObserver.NONE;source.draw();assertEquals(4,episodes.size());
        }finally{Game.observer=previous;}
    }
}
