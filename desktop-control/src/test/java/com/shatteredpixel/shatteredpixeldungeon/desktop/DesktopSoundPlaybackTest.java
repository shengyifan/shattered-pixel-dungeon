package com.shatteredpixel.shatteredpixeldungeon.desktop;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALSound;
import org.junit.Test;
import org.lwjgl.openal.AL10;

import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

public class DesktopSoundPlaybackTest {
    @Test public void validFirstPlaybackIdZeroRequiresAnActiveMappedSource() {
        Probe probe = new Probe();
        assertTrue(DesktopSoundPlayback.isActive(0, probe));
        assertEquals(0, probe.requestedPlaybackId);
        assertEquals(7, probe.queriedSource);
    }

    @Test public void failedPlaybackDoesNotInspectNativeState() {
        Probe probe = new Probe();
        assertFalse(DesktopSoundPlayback.isActive(-1, probe));
        assertEquals(0, probe.calls);
    }

    @Test public void noDeviceZeroCannotBeMistakenForValidPlayback() {
        Probe probe = new Probe() {
            @Override public int sourceId(long id) {
                // OpenALLwjgl3Audio initializes this lookup only after device/context setup.
                throw new NullPointerException("Fixture: unavailable source lookup");
            }
        };
        assertFalse(DesktopSoundPlayback.isActive(0, probe));
    }

    @Test public void missingOrDeletedSourceCannotConfirmPlayback() {
        Probe probe = new Probe();
        probe.source = -1;
        assertFalse(DesktopSoundPlayback.isActive(0, probe));
        probe.source = 0;
        assertFalse(DesktopSoundPlayback.isActive(0, probe));
        probe.source = 7;
        probe.exists = false;
        assertFalse(DesktopSoundPlayback.isActive(0, probe));
    }

    @Test public void pausedStoppedAndUnstartedSourcesAreNotActive() {
        Probe probe = new Probe();
        for (int state : new int[]{AL10.AL_INITIAL, AL10.AL_PAUSED, AL10.AL_STOPPED}) {
            probe.state = state;
            assertFalse("Source state " + state, DesktopSoundPlayback.isActive(0, probe));
        }
    }

    @Test public void sourceAndListenerMustBothHaveFinitePositiveGain() {
        Probe probe = new Probe();
        for (float gain : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            probe.sourceGain = gain;
            probe.listenerGain = 1;
            assertFalse("Source gain " + gain, DesktopSoundPlayback.isActive(0, probe));
            probe.sourceGain = 1;
            probe.listenerGain = gain;
            assertFalse("Listener gain " + gain, DesktopSoundPlayback.isActive(0, probe));
        }
        probe.sourceGain = 0.1f;
        probe.listenerGain = 0.2f;
        assertTrue(DesktopSoundPlayback.isActive(0, probe));
    }

    @Test public void unavailableContextOrNativeLibraryDoesNotEscapeObservation() {
        assertFalse(DesktopSoundPlayback.isActive(0, new Probe() {
            @Override public boolean isSource(int source) {
                throw new IllegalStateException("Fixture: no current context");
            }
        }));
        assertFalse(DesktopSoundPlayback.isActive(0, new Probe() {
            @Override public boolean isSource(int source) {
                throw new UnsatisfiedLinkError("Fixture: no native library");
            }
        }));
    }

    @Test public void unsupportedSoundOrBackendIsRejectedWithoutNativeCalls() {
        Audio previous = Gdx.audio;
        try {
            Gdx.audio = null;
            assertFalse(DesktopSoundPlayback.isActive(new OpenALSound(null), 0));
            Sound foreign = (Sound) Proxy.newProxyInstance(Sound.class.getClassLoader(),
                    new Class<?>[]{Sound.class}, (proxy, method, args) -> {
                        throw new AssertionError("Observation must not invoke the sound");
                    });
            assertFalse(DesktopSoundPlayback.isActive(foreign, 0));
            assertFalse(DesktopSoundPlayback.isActive((Sound) null, 0));
        } finally {
            Gdx.audio = previous;
        }
    }

    private static class Probe implements DesktopSoundPlayback.Queries {
        int source = 7, state = AL10.AL_PLAYING, calls, queriedSource = -1;
        boolean exists = true;
        float sourceGain = 1, listenerGain = 1;
        long requestedPlaybackId = -1;
        @Override public int sourceId(long id) { calls++; requestedPlaybackId = id; return source; }
        @Override public boolean isSource(int id) { calls++; queriedSource = id; return exists; }
        @Override public int state(int id) { calls++; queriedSource = id; return state; }
        @Override public float sourceGain(int id) { calls++; queriedSource = id; return sourceGain; }
        @Override public float listenerGain() { calls++; return listenerGain; }
    }
}
