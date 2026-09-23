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

package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import com.watabou.utils.RectF;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

public class Banner extends Image implements GameplayStatus {

	private String observationKind;
	private com.watabou.gltextures.SmartTexture observationTexture;
	private RectF observationFrame;
	private boolean observed;

	/** Bound only at the two native combat-banner creation sites, after copying their actual pixels. */
	public Banner observationKind(String kind) {
		if(!"boss_slain".equals(kind)&&!"game_over".equals(kind))throw new IllegalArgumentException("Unknown combat banner");
		observationKind=kind;observationTexture=texture;observationFrame=frame();observed=false;return this;
	}

	private String displayedKind() {
		if(observationFrame==null||frame==null||texture!=observationTexture)return null;
		return frame.left==observationFrame.left&&frame.top==observationFrame.top
				&&frame.right==observationFrame.right&&frame.bottom==observationFrame.bottom?observationKind:null;
	}

	@Override public void draw() {
		super.draw();
		if(Game.observer.observesVisualCues()&&buffer!=null)recordDisplayedBanner();
	}

	private void recordDisplayedBanner() {
		Scene scene=Game.instance==null?null:Game.scene();String kind=displayedKind();
		if(observed||kind==null||!(scene instanceof GameScene)||Dungeon.runId==null
				||!RenderedAppearance.uncoveredInScene(this,scene))return;
		observed=true;Game.observer.onBanner(Dungeon.runId,kind,Collections.emptyMap());
	}

	@Override public Map<String,Object> gameplayStatus() {
		String kind=displayedKind();Scene scene=Game.instance==null?null:Game.scene();
		if(!observed||kind==null||!(scene instanceof GameScene)||!RenderedAppearance.uncoveredInScene(this,scene))return Collections.emptyMap();
		Map<String,Object> result=new LinkedHashMap<>();result.put("banner_kind",kind);return result;
	}

	@Override public Map<String,Object> gameplayIntentStatus(){return Collections.emptyMap();}

	private enum State {
		FADE_IN, STATIC, FADE_OUT
	}
	private State state;
	
	private float time;
	
	private int color;
	private float fadeTime;
	private float showTime;
	
	public Banner( Image sample ) {
		super();
		copy( sample );
		alpha( 0 );
	}
	
	public Banner( Object tx ) {
		super( tx );
		alpha( 0 );
	}
	
	public void show( int color, float fadeTime, float showTime ) {
		observed=false;
		
		this.color = color;
		this.fadeTime = fadeTime;
		this.showTime = showTime;
		
		state = State.FADE_IN;
		
		time = fadeTime;
	}
	
	public void show( int color, float fadeTime ) {
		show( color, fadeTime, Float.MAX_VALUE );
	}
	
	@Override
	public void update() {
		super.update();
		
		time -= Game.elapsed;
		if (time >= 0) {
			
			float p = time / fadeTime;
			
			switch (state) {
			case FADE_IN:
				tint( color, p );
				alpha( 1 - p );
				break;
			case STATIC:
				resetColor();
				break;
			case FADE_OUT:
				resetColor();
				alpha( p );
				break;
			}
			
		} else {
			
			switch (state) {
			case FADE_IN:
				time = showTime;
				state = State.STATIC;
				break;
			case STATIC:
				time = fadeTime;
				state = State.FADE_OUT;
				break;
			case FADE_OUT:
				killAndErase();
				break;
			}
				
		}
	}
}
