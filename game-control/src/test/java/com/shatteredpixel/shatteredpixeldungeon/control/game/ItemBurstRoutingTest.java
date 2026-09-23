package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.EnergyParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.ShadowParticle;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRecharging;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.particles.Emitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Calls original static item producers; verifies their emitter scheduling and source lifetimes. */
class ItemBurstRoutingTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void twoNativeUpgradeCallsKeepTwoTimedSourcesWithoutAnExtraStart() {
        RuntimeObserver previous=Game.observer;
        Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}};
        try {
            Hero hero=new Hero();RecordingSprite sprite=new RecordingSprite();hero.sprite=sprite;
            ScrollOfUpgrade.upgrade(hero);ScrollOfUpgrade.upgrade(hero);
            assertEquals(2,sprite.emitters.size());
            for(RecordingEmitter emitter:sprite.emitters) {
                assertEquals(1,emitter.starts);assertEquals(0,emitter.bursts);
                assertSame(Speck.factory(Speck.UP),emitter.lastFactory);
                assertEquals(.2f,emitter.lastInterval);assertEquals(3,emitter.lastQuantity);
                assertNotNull(SnapshotFields.read(emitter,"drawObserver"),"The exact original source receives its semantic observer");
            }
            Object first=SnapshotFields.read(sprite.emitters.get(0),"drawObserver");
            assertNotSame(first,SnapshotFields.read(sprite.emitters.get(1),"drawObserver"),"Two occurrences must not become one request-wide cached effect");
            sprite.emitters.get(0).revive();assertNull(SnapshotFields.read(sprite.emitters.get(0),"drawObserver"));
        } finally {Game.observer=previous;}
    }

    @Test void chargingRetainsNativeNullBranchesAndStartsOneOriginalEnergyBurst() {
        RuntimeObserver previous=Game.observer;
        Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}};
        try {
            Hero hero=new Hero();ScrollOfRecharging.charge(hero);
            RecordingSprite sprite=new RecordingSprite();hero.sprite=sprite;sprite.centerAvailable=false;
            ScrollOfRecharging.charge(hero);assertTrue(sprite.emitters.isEmpty());
            sprite.centerAvailable=true;ScrollOfRecharging.charge(hero);
            assertEquals(1,sprite.emitters.size());RecordingEmitter emitter=sprite.emitters.get(0);
            assertEquals(1,emitter.bursts);assertEquals(1,emitter.starts);
            assertSame(EnergyParticle.FACTORY,emitter.lastFactory);assertEquals(0f,emitter.lastInterval);assertEquals(15,emitter.lastQuantity);
            assertNotNull(SnapshotFields.read(emitter,"drawObserver"));
        } finally {Game.observer=previous;}
    }

    @Test void fixedShadowFeedbackDoesNotRequireALogWidgetOrAnyCurrentBuff() {
        RuntimeObserver previous=Game.observer;
        Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}};
        try {
            Hero hero=new Hero();RecordingSprite sprite=new RecordingSprite();hero.sprite=sprite;
            assertTrue(hero.buffs().isEmpty());
            ScrollOfUpgrade.weakenCurse(hero);
            assertTrue(hero.buffs().isEmpty(),"The visual occurrence must not be reconstructed from a newly applied Buff");
            assertEquals(1,sprite.emitters.size());RecordingEmitter emitter=sprite.emitters.get(0);
            assertEquals(1,emitter.starts);assertEquals(0,emitter.bursts);
            assertSame(ShadowParticle.UP,emitter.lastFactory);assertEquals(.05f,emitter.lastInterval);assertEquals(5,emitter.lastQuantity);
            assertNotNull(SnapshotFields.read(emitter,"drawObserver"),"No GameLog widget or rendered message was installed by this fixture");
        } finally {Game.observer=previous;}
    }

    private static final class RecordingSprite extends CharSprite {
        final List<RecordingEmitter> emitters=new ArrayList<>();boolean centerAvailable=true;
        @Override public Emitter emitter(){RecordingEmitter emitter=new RecordingEmitter();emitters.add(emitter);return emitter;}
        @Override public Emitter centerEmitter(){return centerAvailable?emitter():null;}
    }
    private static final class RecordingEmitter extends Emitter {
        int starts,bursts,lastQuantity;float lastInterval;Factory lastFactory;
        @Override public void burst(Factory factory,int quantity){bursts++;super.burst(factory,quantity);}
        @Override public void start(Factory factory,float interval,int quantity){
            starts++;lastFactory=factory;lastInterval=interval;lastQuantity=quantity;
            // Deterministic native start setup; this fixture never advances particles or RNG.
            super.startDelayed(factory,interval,quantity,0);
        }
    }
}
