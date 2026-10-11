package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.PlatformSupport;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Native-call boundary with fake Sound only: no audio backend, device, game or profile. */
class SoundPlaybackObservationTest {
    private RuntimeObserver previousObserver;
    private PlatformSupport previousPlatform;
    private Object previousSounds, previousEnabled, previousVolume;
    private float previousElapsed;
    private Set<Object> delayed;
    private Set<Object> previousDelayed;
    private LinkedList<Object> loading;
    private List<Object> previousLoading;
    private Map<Object,Sound> sounds;
    private final List<Object> observed = new ArrayList<>();
    private final List<float[]> nativeArguments = new ArrayList<>();
    private FixturePlatform platform;
    private long result;
    private RuntimeException nativeFailure;
    private int nativeFailureOnCall;
    private Sound sound;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        previousObserver=Game.observer; previousPlatform=Game.platform; previousElapsed=Game.elapsed;
        previousSounds=field("ids").get(Sample.INSTANCE);
        previousEnabled=field("enabled").get(Sample.INSTANCE); previousVolume=field("globalVolume").get(Sample.INSTANCE);
        delayed=(Set<Object>)field("delayedSFX").get(null); previousDelayed=new HashSet<>(delayed); delayed.clear();
        loading=(LinkedList<Object>)field("loadingQueue").get(null); previousLoading=new ArrayList<>(loading); loading.clear();
        sounds=new HashMap<>(); field("ids").set(Sample.INSTANCE,sounds);
        Sample.INSTANCE.enable(true); Sample.INSTANCE.volume(1);
        platform=new FixturePlatform(); platform.queueMonitor=delayed; Game.platform=platform;
        result=0;
        sound=(Sound) Proxy.newProxyInstance(Sound.class.getClassLoader(),new Class<?>[]{Sound.class},(proxy,method,args)->{
            if(method.getName().equals("play")&&args!=null&&args.length==3){
                assertTrue(Thread.holdsLock(Sample.INSTANCE),"The native call keeps its original synchronization");
                nativeArguments.add(new float[]{(Float)args[0],(Float)args[1],(Float)args[2]});
                if(nativeFailure!=null&&(nativeFailureOnCall==0||nativeArguments.size()>=nativeFailureOnCall))throw nativeFailure;
                return result;
            }
            if(method.getReturnType()==long.class)return -1L;
            return null;
        });
        sounds.put("alarm",sound);
        Game.observer=new RuntimeObserver(){
            @Override public boolean observesSoundPlayback(){
                assertFalse(Thread.holdsLock(Sample.INSTANCE)); assertFalse(Thread.holdsLock(delayed)); return true;
            }
            @Override public void onSoundPlayback(Object asset){
                assertFalse(Thread.holdsLock(Sample.INSTANCE)); assertFalse(Thread.holdsLock(delayed)); observed.add(asset);
            }
        };
    }

    @AfterEach void restore() throws Exception {
        field("ids").set(Sample.INSTANCE,previousSounds);
        field("enabled").set(Sample.INSTANCE,previousEnabled); field("globalVolume").set(Sample.INSTANCE,previousVolume);
        delayed.clear(); delayed.addAll(previousDelayed); loading.clear(); loading.addAll(previousLoading);
        Game.observer=previousObserver; Game.platform=previousPlatform; Game.elapsed=previousElapsed;
    }

    @Test void verifiedIdZeroPreservesNativeArgumentsAndPublishesAfterReleasingMonitor(){
        Sample.INSTANCE.volume(0.5f);
        assertEquals(0,Sample.INSTANCE.play("alarm",0.3f,0.8f,1.2f));
        assertArrayEquals(new float[]{0.4f,1.2f,0.5f},nativeArguments.get(0),0.00001f);
        assertEquals(List.of("alarm"),observed); assertSame(sound,platform.sound); assertEquals(0,platform.id);
        assertEquals(1,platform.calls);
    }

    @Test void disabledAndUnloadedSamplesNeverCallNativePlayback(){
        Sample.INSTANCE.enable(false);
        assertEquals(-1,Sample.INSTANCE.play("alarm"));
        Sample.INSTANCE.enable(true);
        assertEquals(-1,Sample.INSTANCE.play("unloaded"));
        assertTrue(nativeArguments.isEmpty()); assertTrue(observed.isEmpty()); assertEquals(0,platform.calls);
    }

    @Test void zeroNegativeAndNonfiniteGainKeepNativeCallsButNeverClaimPlayback(){
        result=17;
        for(float volume:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}){
            Sample.INSTANCE.volume(volume);
            assertEquals(17,Sample.INSTANCE.play("alarm"));
        }
        assertEquals(4,nativeArguments.size()); assertTrue(observed.isEmpty()); assertEquals(0,platform.calls);
    }

    @Test void unsuccessfulUnsupportedAndUnverifiedPlaybackAreNotEvents(){
        result=-1; assertEquals(-1,Sample.INSTANCE.play("alarm")); assertEquals(0,platform.calls);
        result=0; platform.active=false;
        assertEquals(0,Sample.INSTANCE.play("alarm"));
        Game.platform=new UnsupportedPlatform(); assertEquals(0,Sample.INSTANCE.play("alarm"));
        Game.platform=null; assertEquals(0,Sample.INSTANCE.play("alarm"));
        assertTrue(observed.isEmpty()); assertEquals(4,nativeArguments.size());
    }

    @Test void optOutAvoidsVerificationAndObserverFailuresCannotAlterNativeResults(){
        result=91; Game.observer=RuntimeObserver.NONE;
        assertEquals(91,Sample.INSTANCE.play("alarm")); assertEquals(0,platform.calls);
        Game.observer=new RuntimeObserver(){@Override public boolean observesSoundPlayback(){throw new AssertionError("fixture");}};
        assertEquals(91,Sample.INSTANCE.play("alarm"));
        Game.observer=new RuntimeObserver(){
            @Override public boolean observesSoundPlayback(){return true;}
            @Override public void onSoundPlayback(Object asset){throw new AssertionError("fixture");}
        };
        assertEquals(91,Sample.INSTANCE.play("alarm"));
        platform.failure=new AssertionError("verification fixture");
        assertEquals(91,Sample.INSTANCE.play("alarm"));
        assertEquals(4,nativeArguments.size());
    }

    @Test void nativeExceptionsStillPropagateWithoutVerificationOrCallbacks(){
        nativeFailure=new IllegalStateException("native fixture failure");
        assertSame(nativeFailure,assertThrows(IllegalStateException.class,()->Sample.INSTANCE.play("alarm")));
        assertEquals(0,platform.calls); assertTrue(observed.isEmpty());
    }

    @Test void delayedSoundsAreReportedOnlyAtPlaybackAndRepeatedSoundsStayDistinct(){
        Sample.INSTANCE.playDelayed("alarm",1f);
        assertTrue(nativeArguments.isEmpty()); assertTrue(observed.isEmpty());
        Game.elapsed=0.5f; Sample.INSTANCE.update();
        assertTrue(observed.isEmpty());
        Sample.INSTANCE.update();
        assertEquals(List.of("alarm"),observed);
        Sample.INSTANCE.play("alarm"); Sample.INSTANCE.playDelayed("alarm",0);
        assertEquals(List.of("alarm","alarm","alarm"),observed);
        Sample.INSTANCE.update(); assertEquals(3,observed.size());
    }

    @Test void delayedSoundUsesPlaybackTimeMuteState(){
        Sample.INSTANCE.playDelayed("alarm",1f);
        Sample.INSTANCE.enable(false); Game.elapsed=1; Sample.INSTANCE.update();
        assertTrue(nativeArguments.isEmpty()); assertTrue(observed.isEmpty());
        Sample.INSTANCE.enable(true); Sample.INSTANCE.update();
        assertTrue(observed.isEmpty(),"A rejected scheduled playback is not retried by observation");
    }

    @Test void earlierDelayedPlaybackIsReportedOutsideLocksWhenLaterNativePlaybackThrows(){
        Sample.INSTANCE.playDelayed("alarm",1);Sample.INSTANCE.playDelayed("alarm",1);Game.elapsed=1;
        nativeFailure=new IllegalStateException("second native playback failed");nativeFailureOnCall=2;
        assertSame(nativeFailure,assertThrows(IllegalStateException.class,()->Sample.INSTANCE.update()));
        assertEquals(2,nativeArguments.size());assertEquals(List.of("alarm"),observed);
    }

    @Test void delayedCallbackCannotInvertTheControllerAndDelayedQueueLocks()throws Exception{
        Object controllerMonitor=new Object();
        CountDownLatch controllerHeld=new CountDownLatch(1),verificationStarted=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Game.platform=new UnsupportedPlatform(){
            @Override public boolean isSoundPlaybackActive(Sound sound,long id){verificationStarted.countDown();return true;}
        };
        Game.observer=new RuntimeObserver(){
            @Override public boolean observesSoundPlayback(){return true;}
            @Override public void onSoundPlayback(Object asset){synchronized(controllerMonitor){observed.add(asset);}}
        };
        Sample.INSTANCE.playDelayed("alarm",1f); Game.elapsed=1;
        Thread control=new Thread(()->{
            try{synchronized(controllerMonitor){
                controllerHeld.countDown();
                if(!verificationStarted.await(2,TimeUnit.SECONDS))throw new AssertionError("Playback verification did not start");
                Sample.INSTANCE.playDelayed("alarm",99);
            }}catch(Throwable error){failure.set(error);}
        },"audio-controller-lock-fixture");
        Thread update=new Thread(()->{try{Sample.INSTANCE.update();}catch(Throwable error){failure.set(error);}},"audio-delayed-update-fixture");
        control.setDaemon(true);update.setDaemon(true);control.start();
        assertTrue(controllerHeld.await(2,TimeUnit.SECONDS));update.start();
        control.join(2000);update.join(2000);
        assertFalse(control.isAlive(),"A controller holder must be able to schedule sound while callback waits for it");
        assertFalse(update.isAlive(),"Delayed observation must release its queue before acquiring the controller lock");
        assertNull(failure.get());assertEquals(List.of("alarm"),observed);
    }

    private static Field field(String name)throws Exception{Field f=Sample.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static class UnsupportedPlatform extends PlatformSupport {
        @Override public void updateDisplaySize(){}
        @Override public void updateSystemUI(){}
        @Override public boolean connectedToUnmeteredNetwork(){return false;}
        @Override public boolean supportsVibration(){return false;}
        @Override public void setupFontGenerators(int size,boolean systemFont){}
        @Override protected FreeTypeFontGenerator getGeneratorForString(String value){return null;}
        @Override public String[] splitforTextBlock(String text,boolean multiline){return new String[]{text};}
    }
    private static final class FixturePlatform extends UnsupportedPlatform {
        boolean active=true; int calls; Sound sound; long id; AssertionError failure; Object queueMonitor;
        @Override public boolean isSoundPlaybackActive(Sound sound,long id){
            assertFalse(Thread.holdsLock(Sample.INSTANCE),"Verification must not retain the Sample monitor");
            assertFalse(Thread.holdsLock(queueMonitor),"Verification must not retain the delayed queue monitor");
            this.sound=sound;this.id=id;calls++;
            if(failure!=null)throw failure;
            return active;
        }
    }
}
