/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.watabou.noosa.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.utils.PlatformSupport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.List;

public enum Sample {

	INSTANCE;

	protected HashMap<Object, Sound> ids = new HashMap<>();

	private boolean enabled = true;
	private float globalVolume = 1f;

	public synchronized void reset() {

		for (Sound sound : ids.values()){
			sound.dispose();
		}
		
		ids.clear();
		delayedSFX.clear();

	}

	public synchronized void pause() {
		for (Sound sound : ids.values()) {
			sound.pause();
		}
	}

	public synchronized void resume() {
		for (Sound sound : ids.values()) {
			sound.resume();
		}
	}

	public synchronized void load( final String asset){
		if (asset != null) {
			try {
				Sound newSound = Gdx.audio.newSound(Gdx.files.internal(asset));
				ids.put(asset, newSound);
			} catch (Exception e){
				Game.reportException(e);
			}
		}
	}

	private static final LinkedList<String> loadingQueue = new LinkedList<>();

	//queues multiple assets for loading, which happens in update()
	// this prevents blocking while we load many assets
	public void load( final String[] assets ) {
		synchronized (loadingQueue) {
			for (String asset : assets) {
				if (!ids.containsKey(asset) && !loadingQueue.contains(asset)) {
					loadingQueue.add(asset);
				}
			}
		}
	}

	public synchronized void unload( Object src ) {
		if (ids.containsKey( src )) {
			ids.get( src ).dispose();
			ids.remove( src );
		}
	}

	public long play( Object id ) {
		return play( id, 1 );
	}

	public long play( Object id, float volume ) {
		return play( id, volume, volume, 1 );
	}
	
	public long play( Object id, float volume, float pitch ) {
		return play( id, volume, volume, pitch );
	}
	
	public long play( Object id, float leftVolume, float rightVolume, float pitch ) {
		return play(id, leftVolume, rightVolume, pitch, null);
	}

	private long play(Object id, float leftVolume, float rightVolume, float pitch,
			List<PlaybackObservation> deferredObservations) {
		final Sound sound;
		final long playbackId;
		final float effectiveGain;
		final RuntimeObserver observer;
		final PlatformSupport platform;
		synchronized (this) {
			float volume = Math.max(leftVolume, rightVolume);
			float pan = rightVolume - leftVolume;
			if (!enabled || !ids.containsKey(id)) return -1;
			sound = ids.get(id);
			effectiveGain = globalVolume * volume;
			// Preserve the native call, arguments, result and exception behavior exactly.
			playbackId = sound.play(effectiveGain, pitch, pan);
			observer = Game.observer;
			platform = Game.platform;
		}
		if (playbackId >= 0 && Float.isFinite(effectiveGain) && effectiveGain > 0) {
			PlaybackObservation observation = new PlaybackObservation(id, sound, playbackId, observer, platform);
			if (deferredObservations == null) observation.publish();
			else deferredObservations.add(observation);
		}
		return playbackId;
	}

	/** A local playback occurrence, never retained beyond its play/update call. */
	private static final class PlaybackObservation {
		private final Object asset;
		private final Sound sound;
		private final long playbackId;
		private final RuntimeObserver observer;
		private final PlatformSupport platform;
		PlaybackObservation(Object asset, Sound sound, long playbackId, RuntimeObserver observer, PlatformSupport platform) {
			this.asset=asset; this.sound=sound; this.playbackId=playbackId; this.observer=observer; this.platform=platform;
		}
		void publish() {
			// No Sample or delayedSFX monitor is held during verification or observer callbacks.
			// Observation failure must not change the native return value or exception behavior.
			try {
				if (observer != null && observer == Game.observer && observer.observesSoundPlayback()
						&& platform != null && platform.isSoundPlaybackActive(sound, playbackId)) {
					observer.onSoundPlayback(asset);
				}
			} catch (Throwable ignored) {
				// Do not log through the game or re-enter audio from an observation failure.
			}
		}
	}

	private class DelayedSoundEffect{
		Object id;
		float delay;

		float leftVol;
		float rightVol;
		float pitch;
	}

	private static final HashSet<DelayedSoundEffect> delayedSFX = new HashSet<>();

	public void playDelayed( Object id, float delay ){
		playDelayed( id, delay, 1 );
	}

	public void playDelayed( Object id, float delay, float volume ) {
		playDelayed( id, delay, volume, volume, 1 );
	}

	public void playDelayed( Object id, float delay, float volume, float pitch ) {
		playDelayed( id, delay, volume, volume, pitch );
	}

	public void playDelayed( Object id, float delay, float leftVolume, float rightVolume, float pitch ) {
		if (delay <= 0) {
			play(id, leftVolume, rightVolume, pitch);
			return;
		}
		DelayedSoundEffect sfx = new DelayedSoundEffect();
		sfx.id = id;
		sfx.delay = delay;
		sfx.leftVol = leftVolume;
		sfx.rightVol = rightVolume;
		sfx.pitch = pitch;
		synchronized (delayedSFX) {
			delayedSFX.add(sfx);
		}
	}

	public void update(){
		synchronized (loadingQueue) {
			if (!loadingQueue.isEmpty()) {
				load(loadingQueue.poll());
			}
		}

		List<PlaybackObservation> observations = new ArrayList<>();
		try {
			synchronized (delayedSFX) {
				if (delayedSFX.isEmpty()) return;
				for (DelayedSoundEffect sfx : delayedSFX.toArray(new DelayedSoundEffect[0])) {
					sfx.delay -= Game.elapsed;
					if (sfx.delay <= 0) {
						delayedSFX.remove(sfx);
						// Keep native playback in its original order and lock scope; only observation waits.
						play(sfx.id, sfx.leftVol, sfx.rightVol, sfx.pitch, observations);
					}
				}
			}
		} finally {
			// Successful earlier sounds remain observable even if a later native play throws.
			for (PlaybackObservation observation : observations) observation.publish();
		}
	}

	public void enable( boolean value ) {
		enabled = value;
	}

	public void volume( float value ) {
		globalVolume = value;
	}

	public boolean isEnabled() {
		return enabled;
	}
	
}
