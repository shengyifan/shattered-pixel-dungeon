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

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.effects.Beam;
import com.shatteredpixel.shatteredpixeldungeon.effects.MagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfWarding;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.Game;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.audio.Sample;
import com.watabou.noosa.tweeners.AlphaTweener;

public class WardSprite extends MobSprite {

	private Animation tierIdles[] = new Animation[7];
	private SmartTexture wardAppearanceTexture;
	private float selectedChargeBrightness = 1;

	public WardSprite(){
		super();

		texture(Assets.Sprites.WARDS);
		wardAppearanceTexture = texture;

		tierIdles[1] = new Animation( 1, true );
		tierIdles[1].frames(texture.uvRect(0, 0, 9, 10));

		tierIdles[2] = new Animation( 1, true );
		tierIdles[2].frames(texture.uvRect(10, 0, 21, 12));

		tierIdles[3] = new Animation( 1, true );
		tierIdles[3].frames(texture.uvRect(22, 0, 37, 16));

		tierIdles[4] = new Animation( 1, true );
		tierIdles[4].frames(texture.uvRect(38, 0, 44, 13));

		tierIdles[5] = new Animation( 1, true );
		tierIdles[5].frames(texture.uvRect(45, 0, 51, 15));

		tierIdles[6] = new Animation( 1, true );
		tierIdles[6].frames(texture.uvRect(52, 0, 60, 15));

	}

	@Override
	public void zap( int pos ) {
		idle();
		flash();
		emitter().burst(MagicMissile.WardParticle.UP, 2);
		if (Actor.findChar(pos) != null){
			parent.add(new Beam.DeathRay(center(), Actor.findChar(pos).sprite.center()).observeDraw(ch.pos, pos));
		} else {
			parent.add(new Beam.DeathRay(center(), DungeonTilemap.raisedTileCenterToWorld(pos)).observeDraw(ch.pos, pos));
		}
		Sample.INSTANCE.play( Assets.Sounds.RAY );
		((WandOfWarding.Ward)ch).onZapComplete();
	}
	
	@Override
	public void turnTo(int from, int to) {
		//do nothing
	}
	
	@Override
	public void die() {
		super.die();
		//cancels die animation and fades out immediately
		play(idle, true);
		emitter().burst(MagicMissile.WardParticle.UP, 10);
		parent.add( new AlphaTweener( this, 0, 2f ) {
			@Override
			protected void onComplete() {
				WardSprite.this.killAndErase();
				parent.erase( this );
			}
		} );
	}

	@Override
	public void resetColor() {
		super.resetColor();
		selectedChargeBrightness = 1;
		if (ch instanceof WandOfWarding.Ward){
			WandOfWarding.Ward ward = (WandOfWarding.Ward) ch;
			if (ward.tier <= 3){
				selectedChargeBrightness = Math.max(0.2f, 1f - (ward.totalZaps / (float)(2*ward.tier-1)));
				brightness(selectedChargeBrightness);
			}
		}
	}

	@Override
	public void observeGameplayVisuals() {
		super.observeGameplayVisuals();
		if (!Game.observer.observesVisualCues()) return;
		VisualCue cue = renderedWardCue(renderedCell());
		if (cue != null) GameScene.observeCellVisualDraw(this, cue);
	}

	@Override
	protected boolean gameplayTintOwned() { return true; }

	/** Current native form and its selected brightness, without consulting a Ward or remaining shots. */
	protected VisualCue renderedWardCue(int cell) {
		if (cell < 0 || !exists || !visible || !Float.isFinite(alpha()) || alpha() <= 0) return null;
		int tier = -1;
		if (texture != null && texture == wardAppearanceTexture && frame != null) {
			for (int candidate = 1; candidate < tierIdles.length; candidate++) {
				com.watabou.utils.RectF known = tierIdles[candidate].frames[0];
				if (frame.left == known.left && frame.top == known.top
						&& frame.right == known.right && frame.bottom == known.bottom) tier = candidate;
			}
		}
		java.util.Map<String,Object> state = new java.util.LinkedHashMap<>();
		if (tier < 0) {
			state.putAll(com.shatteredpixel.shatteredpixeldungeon.ui.GameplayStatus.unmappedIndicator("tier", "ward_form"));
		} else {
			state.put("tier", tier);
			if (tier <= 3) {
				if (Float.isFinite(selectedChargeBrightness) && selectedChargeBrightness >= 0.2f && selectedChargeBrightness <= 1) {
					state.put("charge", java.util.Map.of("fraction",
							com.shatteredpixel.shatteredpixeldungeon.ui.GameplayStatus.displayedChannel(selectedChargeBrightness), "basis", "displayed_brightness"));
				} else {
					state.putAll(com.shatteredpixel.shatteredpixeldungeon.ui.GameplayStatus.unmappedIndicator("charge", "ward_charge"));
				}
			}
		}
		return new VisualCue("ward_state", cell, null, null, null, null, state);
	}

	public void linkVisuals(Char ch ){

		if (ch instanceof WandOfWarding.Ward) {
			updateTier(((WandOfWarding.Ward) ch).tier);
		} else {
			updateTier(5); //defaults to 5
		}
		
	}

	public void updateTier(int tier){

		idle = tierIdles[tier];
		run = idle.clone();
		attack = idle.clone();
		die = idle.clone();

		//always render first
		if (parent != null) {
			parent.sendToBack(this);
		}

		resetColor();
		if (ch != null) place(ch.pos);
		idle();

		if (tier <= 3){
			shadowWidth     = shadowHeight    = 1f;
			perspectiveRaise = (16 - height()) / 32f; //center of the cell
		} else {
			shadowWidth     = 1.2f;
			shadowHeight    = 0.25f;
			perspectiveRaise = 6 / 16f; //6 pixels
		}

	}

	private float baseY = Float.NaN;

	@Override
	public void place(int cell) {
		super.place(cell);
		baseY = y;
	}

	@Override
	public void update() {
		super.update();
		//if tier is greater than 3
		if (perspectiveRaise >= 6 / 16f && !paused){
			if (Float.isNaN(baseY)) baseY = y;
			y = baseY + (float) Math.sin(Game.timeTotal);
			shadowOffset = 0.25f - 0.8f*(float) Math.sin(Game.timeTotal);
		}
	}

	@Override
	public int blood() {
		return 0xFFCC33FF;
	}
}
