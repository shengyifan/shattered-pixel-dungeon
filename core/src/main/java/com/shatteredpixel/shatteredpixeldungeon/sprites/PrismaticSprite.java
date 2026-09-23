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

package com.shatteredpixel.shatteredpixeldungeon.sprites;

import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.PrismaticImage;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.VisualCue;

public class PrismaticSprite extends MirrorSprite {

	public PrismaticSprite(){
		super();

		float interval = (Game.timeTotal % 9 ) /3f;
		tint(interval > 2 ? interval - 2 : Math.max(0, 1 - interval),
				interval > 1 ? Math.max(0, 2-interval): interval,
				interval > 2 ? Math.max(0, 3-interval): interval-1, 0.5f);
	}

	@Override
	public void updateArmor() {
		updateArmor( ((PrismaticImage)ch).armTier );
	}
	
	@Override
	public void update() {
		super.update();
		
		if (flashTime <= 0){
			float interval = (Game.timeTotal % 9 ) /3f;
			tint(interval > 2 ? interval - 2 : Math.max(0, 1 - interval),
					interval > 1 ? Math.max(0, 2-interval): interval,
					interval > 2 ? Math.max(0, 3-interval): interval-1, 0.5f);
		}
	}

	@Override
	public void draw() {
		super.draw();
		if (Game.observer.observesVisualCues() && texture != null && buffer != null) {
			VisualCue cue = renderedPrismaticCue(renderedCell());
			if (cue != null) GameScene.observeCellVisualDraw(this, cue);
		}
	}

	/** Report the frozen animation and its drawn transparency, never the image's death timer. */
	protected VisualCue renderedPrismaticCue(int cell) {
		float opacity = alpha();
		if (cell < 0 || !paused || !Float.isFinite(opacity) || opacity <= 0) return null;
		return new VisualCue("prismatic_image_paused", cell, null, null, null, Math.min(1f, opacity));
	}

	@Override
	protected String renderedTintStyle() {
		String controlled = super.renderedTintStyle();
		if (controlled != null) return controlled;
		return flashTime <= 0 && darkBlock == null ? "prismatic_cycle" : null;
	}
	
}
