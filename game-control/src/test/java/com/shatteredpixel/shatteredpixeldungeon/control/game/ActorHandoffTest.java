package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real actor scheduler, no renderer or game save; a notification alone must not bypass a held handoff. */
public class ActorHandoffTest {
    @Test public void nativeMotionCompletionResumesNormallyWithoutAControlLease() throws Exception { motionScenario(false); }

    @Test public void motionCanFinishWhileAReleasedMonitorLeaseStillPreventsTheNextActorStep() throws Exception { motionScenario(true); }

    private void motionScenario(boolean hold) throws Exception {
        Game previousGame=Game.instance;
        com.watabou.utils.PlatformSupport previousPlatform=Game.platform;
        Hero previousHero=Dungeon.hero;
        Set<Actor> previousActors=Actor.all();
        boolean previousKeepAlive=Actor.keepActorThreadAlive;
        AtomicInteger steps=new AtomicInteger();
        Char actor=new Char(){@Override protected boolean act(){steps.incrementAndGet();return false;}};
        CharSprite sprite=new CharSprite();actor.sprite=sprite;sprite.isMoving=true;
        Thread thread=new Thread(Actor::process,"Test native motion handoff");
        try{
            new Game(Scene.class,null){{requestedReset=false;}};
            Dungeon.hero=null;Actor.clear();Actor.add(actor);Actor.keepActorThreadAlive=true;thread.start();
            long deadline=System.nanoTime()+2_000_000_000L;
            while(!Actor.isMotionHandoff()&&System.nanoTime()<deadline)Thread.sleep(1);
            assertTrue(Actor.atHandoff(thread,()->{
                assertTrue(Thread.holdsLock(sprite));assertFalse(Thread.holdsLock(thread));
                assertSame(actor,Actor.motionHandoffActor());
                if(hold)Actor.holdMotionHandoff(true);
            }));
            assertFalse(Actor.atHandoff(new Thread(),()->fail("Wrong scheduler identity must not gain access")));
            assertEquals(0,steps.get());
            // The ordinary animation completion still runs and releases its own monitor.
            synchronized(sprite){sprite.isMoving=false;sprite.notifyAll();}
            if(hold){
                Thread.sleep(80);
                assertEquals("No actor action may run while its before-snapshot is being persisted",0,steps.get());
                assertFalse(sprite.isMoving);
                assertTrue(Actor.atHandoff(thread,()->Actor.holdMotionHandoff(false)));
            }
            awaitYield(thread,steps,1);
            assertFalse(Actor.isMotionHandoff());
            assertThrows(IllegalStateException.class,()->{synchronized(sprite){Actor.holdMotionHandoff(true);}});
        }finally{
            Actor.keepActorThreadAlive=false;
            synchronized(sprite){sprite.isMoving=false;sprite.notifyAll();}
            synchronized(thread){thread.notifyAll();}thread.join(2000);
            assertFalse(thread.isAlive());
            Actor.clear();for(Actor previous:previousActors)Actor.add(previous);
            Actor.keepActorThreadAlive=previousKeepAlive;Dungeon.hero=previousHero;
            Game.instance=previousGame;Game.platform=previousPlatform;
        }
    }

    @Test public void onlyAnExplicitResumeRunsTheNextActorStep() throws Exception {
        Game previousGame = Game.instance;
        com.watabou.utils.PlatformSupport previousPlatform = Game.platform;
        Hero previousHero = Dungeon.hero;
        Set<Actor> previousActors = Actor.all();
        boolean previousKeepAlive = Actor.keepActorThreadAlive;
        AtomicInteger steps = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Actor actor = new Actor() {
            @Override protected boolean act() { steps.incrementAndGet(); return false; }
        };
        Thread thread = new Thread(Actor::process, "Test actor handoff");
        thread.setUncaughtExceptionHandler((ignored, error) -> failure.set(error));
        try {
            new Game(Scene.class, null) {{ requestedReset = false; }};
            Dungeon.hero = null;
            Actor.clear(); Actor.add(actor); Actor.keepActorThreadAlive = true;
            thread.start();
            awaitYield(thread, steps, 1);
            assertTrue(Actor.yieldedBy(actor));
            synchronized (thread) { thread.notifyAll(); thread.wait(100); }
            assertEquals("A spurious notification must not advance game time", 1, steps.get());
            assertTrue(Actor.yieldedBy(actor));
            actor.next();
            synchronized (thread) { Actor.markResuming(); thread.notify(); }
            awaitYield(thread, steps, 2);
            assertEquals(2, steps.get());
            assertNull(failure.get());
        } finally {
            Actor.keepActorThreadAlive = false;
            synchronized (thread) { thread.notifyAll(); }
            thread.join(2000);
            assertFalse("Test actor must exit without interrupting a game thread", thread.isAlive());
            Actor.clear();
            for (Actor previous : previousActors) Actor.add(previous);
            Actor.keepActorThreadAlive = previousKeepAlive;
            Dungeon.hero = previousHero;
            Game.instance = previousGame;
            Game.platform = previousPlatform;
        }
    }

    private void awaitYield(Thread thread, AtomicInteger steps, int count) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        synchronized (thread) {
            while ((steps.get() < count || !Actor.isYielded()) && thread.isAlive()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) fail("Actor did not reach its natural handoff");
                thread.wait(Math.max(1, left / 1_000_000));
            }
            assertEquals(count, steps.get());
            assertTrue(Actor.isYielded());
        }
    }
}
