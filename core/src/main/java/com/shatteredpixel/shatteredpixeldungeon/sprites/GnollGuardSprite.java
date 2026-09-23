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
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollGuard;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.EarthParticle;
import com.watabou.noosa.TextureFilm;
import com.watabou.noosa.particles.Emitter;

public class GnollGuardSprite extends MobSprite {

	private Emitter earthArmor;
	private Object gameplayArmorEpisode;

	public GnollGuardSprite() {
		super();

		texture(Assets.Sprites.GNOLL_GUARD );

		TextureFilm frames = new TextureFilm( texture, 12, 16 );

		idle = new Animation( 2, true );
		idle.frames( frames, 0, 0, 0, 1, 0, 0, 1, 1 );

		run = new Animation( 12, true );
		run.frames( frames, 4, 5, 6, 7 );

		attack = new Animation( 12, false );
		attack.frames( frames, 2, 3, 0 );

		die = new Animation( 12, false );
		die.frames( frames, 8, 9, 10 );

		play( idle );
	}

	@Override
	public void link( Char ch ) {
		super.link( ch );

		if (ch instanceof GnollGuard && ((GnollGuard) ch).hasSapper()){
			setupArmor();
		}
	}

	public void setupArmor(){
		if (earthArmor == null) {
			earthArmor = emitter();
			earthArmor.fillTarget = false;
			earthArmor.y = height()/2f;
			earthArmor.x = (2*scale.x);
			earthArmor.width = width()-(4*scale.x);
			earthArmor.height = height() - (10*scale.y);
			earthArmor.pour(EarthParticle.SMALL, 0.15f);
			gameplayArmorEpisode=earthArmor.observedDrawEpisode();
		}
	}

	public void loseArmor(){
		if (earthArmor != null){
			earthArmor.on = false;
			earthArmor = null;
		}
	}

	@Override public void observeGameplayVisuals() {
		super.observeGameplayVisuals();
		if(!com.watabou.noosa.Game.observer.observesVisualCues())return;
		com.watabou.noosa.VisualCue armor=gameplayArmorCue();
		if(armor!=null)com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.observeCellVisualDraw(this,armor);
	}

	/** The selected native armor emitter, never the actor's sapper/armor state. */
	public com.watabou.noosa.VisualCue gameplayArmorCue() {
		int cell=renderedCell();
		if(cell<0||!exists||!visible||!Float.isFinite(alpha())||alpha()<=0||parent==null
				||earthArmor==null||!earthArmor.exists||!earthArmor.visible||!earthArmor.on||earthArmor.parent==null
				||earthArmor.observedDrawEpisode()!=gameplayArmorEpisode)return null;
		com.watabou.noosa.Gizmo sourceRoot=this,armorRoot=earthArmor;
		while(sourceRoot.parent!=null)sourceRoot=sourceRoot.parent;
		while(armorRoot.parent!=null)armorRoot=armorRoot.parent;
		return sourceRoot==armorRoot?new com.watabou.noosa.VisualCue("gnoll_earth_armor",cell):null;
	}

	@Override
	public void update() {
		super.update();

		if (earthArmor != null){
			earthArmor.visible = visible;
		}
	}

	@Override
	public void die() {
		super.die();
		if (earthArmor != null){
			earthArmor.on = false;
			earthArmor = null;
		}
	}

	@Override
	public void kill() {
		super.kill();
		if (earthArmor != null){
			earthArmor.on = false;
			earthArmor = null;
		}
	}


}
