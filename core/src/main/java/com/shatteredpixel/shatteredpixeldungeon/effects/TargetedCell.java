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
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class TargetedCell extends Image {

	private float alpha;
	private final int visualCell;
	private final boolean redTarget;
	final PendingWarning warning;

	public TargetedCell( int pos, int color ) {
		this(pos, color, null);
	}

	public TargetedCell( int pos, int color, PendingWarning warning ) {
		super(Icons.get(Icons.TARGET));
		this.warning = warning;
		visualCell = pos;
		redTarget = (color & 0xFFFFFF) == 0xFF0000;
		hardlight(color);

		origin.set( width/2f );

		point( DungeonTilemap.tileToWorld( pos ) );

		alpha = 1f;
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
		if (redTarget && texture != null && Game.observer.observesVisualCues()) {
			GameScene.observeTargetedCellDraw(this, visualCell);
		}
	}

	@Override
	public void update() {
		if ((alpha -= Game.elapsed/2f) > 0) {
			alpha( alpha );
			scale.set( alpha );
		} else {
			killAndErase();
		}
	}
}
