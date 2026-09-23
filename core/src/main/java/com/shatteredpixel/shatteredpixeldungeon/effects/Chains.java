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

import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.utils.Callback;
import com.watabou.utils.PointF;

public class Chains extends Group {

	private static final double A = 180 / Math.PI;

	private float spent = 0f;
	private float duration;

	private Callback callback;

	@Override
	public boolean hasPendingCallback() { return callback != null; }

	private Image[] chains;
	private int numChains;
	private float distance;
	private float rotation = 0;

	private PointF from, to;
	private final String observedKind;

	public Chains(int from, int to, Effects.Type type, Callback callback){
		this(DungeonTilemap.tileCenterToWorld(from),
				DungeonTilemap.tileCenterToWorld(to),
				type,
				callback);
	}

	public Chains(PointF from, PointF to, Effects.Type type, Callback callback){
		super();
		observedKind = type == Effects.Type.ETHEREAL_CHAIN ? "ethereal_chain_link" : "chain_link";

		this.callback = callback;

		this.from = from;
		this.to = to;

		float dx = to.x - from.x;
		float dy = to.y - from.y;
		distance = (float)Math.hypot(dx, dy);

		//base of 200ms, plus 50ms per tile travelled
		duration = distance/320f + 0.2f;

		rotation = (float)(Math.atan2( dy, dx ) * A) + 90f;

		numChains = Math.round(distance/6f)+1;

		chains = new Image[numChains];
		for (int i = 0; i < chains.length; i++){
			chains[i] = new Image(Effects.get(type));
			chains[i].angle = rotation;
			chains[i].origin.set( chains[i].width()/ 2, chains[i].height() );
			add(chains[i]);
		}
	}

	@Override public void observeGameplayVisuals() {
		super.observeGameplayVisuals();
		if (!Game.observer.observesVisualCues() || com.shatteredpixel.shatteredpixeldungeon.Dungeon.level == null) return;
		int width = com.shatteredpixel.shatteredpixeldungeon.Dungeon.level.width();
		for (Image chain : chains) {
			int cell = renderedCell(chain, width, com.shatteredpixel.shatteredpixeldungeon.Dungeon.level.height());
			if (cell < 0) continue;
			// Only links already drawn at their current extension; never publish 'to'.
			com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.observeCellVisualDraw(chain,
					new com.watabou.noosa.VisualCue(observedKind, cell));
		}
	}

	/** Bottom-pivot links rotate away from their untransformed layout center. */
	static int renderedCell(Image chain, int width, int height) {
		VisualCueProjection.Rect bounds = VisualCueProjection.worldBounds(chain);
		if (bounds == null || width <= 0 || height <= 0) return -1;
		float centerX = (bounds.left + bounds.right) / 2f, centerY = (bounds.top + bounds.bottom) / 2f;
		if (!Float.isFinite(centerX) || !Float.isFinite(centerY) || centerX < 0 || centerY < 0) return -1;
		int column = (int)(centerX / DungeonTilemap.SIZE), row = (int)(centerY / DungeonTilemap.SIZE);
		return column < width && row < height ? row*width+column : -1;
	}

	@Override
	public void update() {
		if ((spent += Game.elapsed) > duration) {

			killAndErase();
			if (callback != null) {
				callback.call();
			}

		} else {
			float dx = to.x - from.x;
			float dy = to.y - from.y;
			for (int i = 0; i < chains.length; i++) {
				chains[i].center(new PointF(
						from.x + ((dx * (i / (float)chains.length)) * (spent/duration)),
						from.y + ((dy * (i / (float)chains.length)) * (spent/duration))
				));
			}
		}
	}

}
