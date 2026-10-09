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

package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;
import com.watabou.utils.SparseArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class TargetedCell extends Image implements Bundlable {

	public int pos;
	public float time;

	private float alpha;

	public static SparseArray<TargetedCell> cells = new SparseArray<>();

	private final Set<PendingWarning> warningOwners = new LinkedHashSet<>();

	/** Attach observation ownership without changing the native pooled marker. */
	public void observeWarning(PendingWarning warning) {
		if (warning != null) {
			warningOwners.removeIf(previous -> !previous.isPending());
			warningOwners.add(warning);
		}
	}

	/** Called only after the collector validates this actual public marker and its cell. */
	public boolean rememberObservedWarnings(int cell) {
		for (PendingWarning warning : warningOwners) warning.remember(cell);
		return !warningOwners.isEmpty();
	}

	public void reset( int pos, float time ){
		// Reusing a marker for another cell/episode never transfers its old attack owners.
		if (this.pos != pos || cells.get(pos) != this) warningOwners.clear();
		copy(Icons.get(Icons.TARGET));
		origin.set( width/2f );
		camera = null;

		this.pos = pos;
		point( DungeonTilemap.tileToWorld( pos ) );

		hardlight(0xFF0000);

		alpha = 1f;
		this.time = time;

		alpha(1f);
		scale.set(1f);

		synchronized (cells) {
			cells.put(pos, this);
		}
	}

	/**
	 * A native warning episode, independent of its fading marker images. No targets or timers are
	 * supplied here: only the collector can remember a cell, after checking its public source and FOV.
	 * This process-local knowledge is never saved or reconstructed from an actor's targeting fields.
	 */
	public static final class PendingWarning {
		private static final Set<PendingWarning> pending = new LinkedHashSet<>();
		private static String scopeRun, endedRun;
		private static Level scopeLevel;
		private static int scopeDepth, scopeBranch;
		private Actor owner;
		private boolean active;
		private final Set<Integer> observedCells = new LinkedHashSet<>();

		private PendingWarning(Actor owner) { this.owner = owner; }

		public static synchronized PendingWarning begin(Actor owner) {
			synchronizeScope();
			PendingWarning warning = new PendingWarning(owner);
			warning.active = scopeRun != null && scopeLevel != null && !Objects.equals(scopeRun, endedRun);
			if (warning.active) pending.add(warning);
			return warning;
		}

		/** The native projectile/callback now owns completion, even if its preparing actor is removed. */
		public void commit() {
			synchronized (PendingWarning.class) { owner = null; }
		}

		public void complete() { cancel(); }

		/** Observation-only handoff to the actor already created by native logic. */
		public void transferTo(Actor nextOwner) {
			Objects.requireNonNull(nextOwner, "Next warning owner");
			synchronized (PendingWarning.class) { if (active) owner = nextOwner; }
		}

		/** Used only when a native producer is about to show a fresh warning, never to restore targets. */
		public boolean isPending() {
			synchronized (PendingWarning.class) {
				synchronizeScope();
				return active;
			}
		}

		public void cancel() {
			synchronized (PendingWarning.class) {
				active = false;
				owner = null;
				observedCells.clear();
				pending.remove(this);
			}
		}

		public static synchronized void cancelOwnedBy(Actor owner) {
			for (PendingWarning warning : new ArrayList<>(pending)) {
				if (warning.owner == owner) warning.cancel();
			}
		}

		/** Transfers only preparation ownership; committed callbacks never belong to an actor. */
		public static synchronized void transferOwnedBy(Actor from, Actor to) {
			Objects.requireNonNull(from, "Previous warning owner");
			Objects.requireNonNull(to, "Next warning owner");
			synchronizeScope();
			for (PendingWarning warning : pending) if (warning.owner == from) warning.owner = to;
		}

		/** Bridges a native temporary removal until its existing holder is attached, without reordering it. */
		public static synchronized Suspension suspendOwnedBy(Actor owner) {
			Objects.requireNonNull(owner, "Suspended warning owner");
			synchronizeScope();
			Suspension suspension = new Suspension();
			for (PendingWarning warning : pending) if (warning.owner == owner) {
				warning.owner = null;
				suspension.warnings.add(warning);
			}
			return suspension;
		}

		/** An uncompleted handoff cancels on close instead of leaving unowned sticky warnings. */
		public static final class Suspension implements AutoCloseable {
			private final List<PendingWarning> warnings = new ArrayList<>();
			private Suspension() { }

			public void transferTo(Actor holder) {
				Objects.requireNonNull(holder, "Suspended warning holder");
				synchronized (PendingWarning.class) {
					for (PendingWarning warning : warnings) if (warning.active) warning.owner = holder;
					warnings.clear();
				}
			}

			@Override public void close() {
				synchronized (PendingWarning.class) {
					for (PendingWarning warning : warnings) warning.cancel();
					warnings.clear();
				}
			}
		}

		/** A true level/run change ends old episodes; rebuilding the scene around one Level does not. */
		public static synchronized void synchronizeScope() {
			if (scopeLevel != Dungeon.level || scopeDepth != Dungeon.depth || scopeBranch != Dungeon.branch
					|| !Objects.equals(scopeRun, Dungeon.runId)) {
				cancelAll();
				scopeRun = Dungeon.runId;
				scopeLevel = Dungeon.level;
				scopeDepth = Dungeon.depth;
				scopeBranch = Dungeon.branch;
			}
		}

		public static synchronized void endRun() {
			cancelAll();
			endedRun = Dungeon.runId;
		}

		/** Explicit new/load run boundary. The ledger has no persistence representation. */
		public static synchronized void clear() {
			cancelAll();
			scopeRun = endedRun = null;
			scopeLevel = null;
		}

		private static void cancelAll() {
			for (PendingWarning warning : new ArrayList<>(pending)) warning.cancel();
		}

		void remember(int cell) {
			synchronized (PendingWarning.class) {
				synchronizeScope();
				if (active) observedCells.add(cell);
			}
		}

		static synchronized List<PendingWarning> current() {
			synchronizeScope();
			return new ArrayList<>(pending);
		}

		List<Integer> cells() {
			synchronized (PendingWarning.class) { return new ArrayList<>(observedCells); }
		}
	}

	@Override
	public void observeGameplayVisuals() {
		super.observeGameplayVisuals();
		if (texture != null && Game.observer.observesVisualCues()) {
			GameScene.observeTargetedCellDraw(this, pos);
		}
	}

	@Override
	public void update() {
		alpha -= Game.elapsed;
		if (time >= Actor.now()){
			alpha = Math.max(alpha, 0.6f);
		}
		if (alpha > 0) {
			alpha( alpha );
			scale.set( (float)Math.pow(alpha, 0.33f) );
		} else {
			time = 0;
			synchronized (cells) {
				cells.remove(pos);
			}
			killAndErase();
		}
	}

	public static void fixTime(float min){
		synchronized (cells){
			for (TargetedCell c : cells.valueList()){
				c.time -= min;
			}
		}
	}

	private static final String POS = "pos";
	private static final String COLOR = "color";
	private static final String TIME = "time";

	@Override
	public void storeInBundle(Bundle bundle) {
		bundle.put(POS, pos);
		bundle.put(TIME, time);
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		pos = bundle.getInt(POS);
		time = bundle.getInt(TIME);
	}

}
