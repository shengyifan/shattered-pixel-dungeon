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
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.effects.Splash;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonWallsTilemap;
import com.watabou.noosa.TextureFilm;

public abstract class CrystalSpireSprite extends MobSprite {
	private com.watabou.gltextures.SmartTexture spireAppearanceTexture;

	{
		perspectiveRaise = 7 / 16f; //7 pixels

		shadowWidth     = 1f;
		shadowHeight    = 1f;
		shadowOffset    = 1f;
	}

	public CrystalSpireSprite(){
		texture( Assets.Sprites.CRYSTAL_SPIRE );
		spireAppearanceTexture=texture;

		TextureFilm frames = new TextureFilm( texture, 24, 41 );

		int c = texOffset();

		idle = new Animation(1, true);
		idle.frames( frames, 0+c );

		run = idle.clone();
		attack = idle.clone();
		zap = idle.clone();

		die = new Animation(1, false);
		die.frames( frames, 4+c );

		play(idle);
	}

	public void updateIdle(){
		float hpPercent = 1f;
		if (ch != null){
			hpPercent = ch.HP/(float)ch.HT;
		}

		TextureFilm frames = new TextureFilm( texture, 24, 41 );

		if (hpPercent > 0.9f){
			idle.frames( frames, 0+texOffset() );
		} else if (hpPercent > 0.67f){
			idle.frames( frames, 1+texOffset() );
		} else if (hpPercent > 0.33f){
			idle.frames( frames, 2+texOffset() );
		} else {
			idle.frames( frames, 3+texOffset() );
		}
		play(idle, true);
		run = idle.clone();
		attack = idle.clone();
		zap = idle.clone();
	}

	@Override
	public void link(Char ch) {
		super.link(ch);
		updateIdle();
	}

	@Override public void observeGameplayVisuals() {
		super.observeGameplayVisuals();
		if (!com.watabou.noosa.Game.observer.observesVisualCues() || texture == null) return;
		com.watabou.noosa.VisualCue cue=renderedSpireCue(renderedCell());
		if(cue!=null)GameScene.observeCellVisualDraw(this,cue);
	}

	/** The complete selected native frame includes its row; no spawner health is queried here. */
	protected com.watabou.noosa.VisualCue renderedSpireCue(int cell) {
		if(cell<0||!exists||!visible||!Float.isFinite(alpha())||alpha()<=0)return null;
		int displayed=-1;
		if(frame!=null&&texture!=null&&texture==spireAppearanceTexture&&texture.width>=24&&texture.height>=41) {
			float left=frame.left*texture.width,top=frame.top*texture.height;
			float right=frame.right*texture.width,bottom=frame.bottom*texture.height;
			int columns=texture.width/24,rows=texture.height/41;
			int column=Math.round(left/24f),row=Math.round(top/41f);
			if(column>=0&&column<columns&&row>=0&&row<rows&&left==column*24&&top==row*41
					&&right==(column+1)*24&&bottom==(row+1)*41)displayed=row*columns+column-texOffset();
		}
		java.util.Map<String,Object> state;
		if (displayed >= 0 && displayed <= 4) state = java.util.Collections.singletonMap("stage",
				new String[]{"intact", "cracked", "damaged", "heavily_damaged", "destroyed"}[displayed]);
		else state = com.shatteredpixel.shatteredpixeldungeon.ui.GameplayStatus.unmappedIndicator("stage", "crystal_damage_stage");
		return new com.watabou.noosa.VisualCue("crystal_spire_state", cell,null,null,null,null,state);
	}

	boolean wasVisible = false;

	@Override
	public void update() {
		super.update();
		if (curAnim != die && ch != null && visible != wasVisible){
			if (visible){
				DungeonWallsTilemap.skipCells.add(ch.pos - 2*Dungeon.level.width());
				DungeonWallsTilemap.skipCells.add(ch.pos - Dungeon.level.width());
			} else {
				DungeonWallsTilemap.skipCells.remove(ch.pos - 2*Dungeon.level.width());
				DungeonWallsTilemap.skipCells.remove(ch.pos - Dungeon.level.width());
			}
			GameScene.updateMap(ch.pos-2*Dungeon.level.width());
			GameScene.updateMap(ch.pos-Dungeon.level.width());
			wasVisible = visible;
		}
	}

	@Override
	public void die() {
		super.die();
		Splash.around(this, blood(), 100);
		if (ch != null && visible){
			DungeonWallsTilemap.skipCells.remove(ch.pos - 2*Dungeon.level.width());
			DungeonWallsTilemap.skipCells.remove(ch.pos - Dungeon.level.width());
			GameScene.updateMap(ch.pos-2*Dungeon.level.width());
			GameScene.updateMap(ch.pos-Dungeon.level.width());
		}
	}

	@Override
	public void turnTo(int from, int to) {
		//do nothing
	}

	protected abstract int texOffset();

	public static class Blue extends CrystalSpireSprite {
		@Override
		protected int texOffset() {
			return 0;
		}
		@Override
		public int blood() {
			return 0xFF8EE3FF;
		}
	}

	public static class Green extends CrystalSpireSprite {
		@Override
		protected int texOffset() {
			return 5;
		}
		@Override
		public int blood() {
			return 0xFF85FFC8;
		}
	}

	public static class Red extends CrystalSpireSprite {
		@Override
		protected int texOffset() {
			return 10;
		}
		@Override
		public int blood() {
			return 0xFFFFBB33;
		}
	}

}
