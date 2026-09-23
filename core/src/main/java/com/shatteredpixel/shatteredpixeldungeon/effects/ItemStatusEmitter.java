package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Visual;
import com.watabou.noosa.particles.Emitter;
import java.util.Collections;
import java.util.Map;

/** A semantic annotation of an already selected native item emitter, not a query of its item. */
public final class ItemStatusEmitter extends Emitter {
    private Factory selectedFactory;
    private Object selectedEpisode;
    private long selectedLifetime;
    private String selectedMeaning;

    /** Called only by Armor's existing red-light selection branch, after starting that emitter. */
    public void observeBrokenSeal() { bind("broken_seal"); }

    /** Called only by the candle's existing lit-flame branch, after its native pour. */
    public void observeLitCandle() { bind("lit_candle"); }

    /** Called only after SpiritArrow selects its native Nature's Power leaf trail. */
    public void observeNaturePowered() { bind("nature_powered"); }

    private void bind(String meaning) {
        selectedFactory=factory;selectedEpisode=observedDrawEpisode();selectedLifetime=observationLifetime();selectedMeaning=meaning;
    }

    public Map<String,Object> gameplayStatus(Visual owner) {
        if(selectedMeaning==null||owner==null||target!=owner||!exists||!visible||!on||parent==null||owner.parent==null
                ||!isEmitting(selectedFactory)||selectedEpisode!=observedDrawEpisode()||selectedLifetime!=observationLifetime())
            return Collections.emptyMap();
        Gizmo emitterRoot=this,ownerRoot=owner;
        for(Gizmo node=this;node!=null;node=node.parent)if(!node.exists||!node.visible)return Collections.emptyMap();
        for(Gizmo node=owner;node!=null;node=node.parent)if(!node.exists||!node.visible)return Collections.emptyMap();
        while(emitterRoot.parent!=null)emitterRoot=emitterRoot.parent;
        while(ownerRoot.parent!=null)ownerRoot=ownerRoot.parent;
        return emitterRoot==ownerRoot?Collections.singletonMap(selectedMeaning,true):Collections.emptyMap();
    }

    @Override public void revive() {
        selectedMeaning=null;selectedFactory=null;selectedEpisode=null;
        super.revive();
    }
}
