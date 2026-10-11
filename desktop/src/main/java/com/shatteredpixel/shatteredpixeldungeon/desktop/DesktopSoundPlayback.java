package com.shatteredpixel.shatteredpixeldungeon.desktop;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALLwjgl3Audio;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALSound;
import org.lwjgl.openal.AL10;

/** Read-only confirmation of a native playback source, never a request to play or resume it. */
final class DesktopSoundPlayback {
    private DesktopSoundPlayback() { }

    interface Queries {
        int sourceId(long playbackId);
        boolean isSource(int sourceId);
        int state(int sourceId);
        float sourceGain(int sourceId);
        float listenerGain();
    }

    static boolean isActive(Sound sound, long playbackId) {
        Audio backend = Gdx.audio;
        if (!(sound instanceof OpenALSound) || !(backend instanceof OpenALLwjgl3Audio)
                || playbackId < 0) return false;
        OpenALLwjgl3Audio audio = (OpenALLwjgl3Audio) backend;
        return isActive(playbackId, new Queries() {
            @Override public int sourceId(long id) { return audio.getSourceId(id); }
            @Override public boolean isSource(int source) { return AL10.alIsSource(source); }
            @Override public int state(int source) { return AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE); }
            @Override public float sourceGain(int source) { return AL10.alGetSourcef(source, AL10.AL_GAIN); }
            @Override public float listenerGain() { return AL10.alGetListenerf(AL10.AL_GAIN); }
        });
    }

    static boolean isActive(long playbackId, Queries queries) {
        if (playbackId < 0 || queries == null) return false;
        try {
            // libGDX also returns playback ID zero without a device. A mapped live source,
            // rather than a positive playback ID, distinguishes that from a valid first sound.
            int source = queries.sourceId(playbackId);
            if (source <= 0 || !queries.isSource(source) || queries.state(source) != AL10.AL_PLAYING)
                return false;
            float sourceGain = queries.sourceGain(source);
            float listenerGain = queries.listenerGain();
            return Float.isFinite(sourceGain) && sourceGain > 0
                    && Float.isFinite(listenerGain) && listenerGain > 0;
        } catch (RuntimeException | LinkageError unavailable) {
            // Failed backend initialization can leave getSourceId's lookup uninitialized.
            // Missing/destroyed native contexts cannot establish successful playback either.
            return false;
        }
    }
}
