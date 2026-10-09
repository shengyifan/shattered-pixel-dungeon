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

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.ui.Component;

public class HealthBar extends Component implements GameplayStatus {

	private static final int COLOR_BG	= 0xFFAA0000;
	private static final int COLOR_HP	= 0xFF00EE00;
	private static final int COLOR_SHLD = 0xFFFFFFFF;
	private static final int COLOR_DOT	= 0x66000000;
	
	private static final int HEIGHT	= 2;
	
	private ColorBlock Bg;
	private ColorBlock Shld;
	private ColorBlock Hp;
	private ColorBlock Dot;
	
	private float health;
	private float shield;
	private float incomingDOT;
	
	@Override
	protected void createChildren() {
		Bg = new ColorBlock( 1, 1, COLOR_BG );
		add( Bg );

		Shld = new ColorBlock( 1, 1, COLOR_SHLD );
		add( Shld );
		
		Hp = new ColorBlock( 1, 1, COLOR_HP );
		add( Hp );

		Dot = new ColorBlock( 1, 1, COLOR_DOT );
		add( Dot );
		
		height = HEIGHT;
	}
	
	@Override
	protected void layout() {
		
		Bg.x = Shld.x = Hp.x = Dot.x = x;
		Bg.y = Shld.y = Hp.y = Dot.y = y;
		
		Bg.size( width, height );
		
		//logic here rounds up to the nearest pixel
		float pixelWidth = width;
		if (camera() != null) pixelWidth *= camera().zoom;
		Shld.size( width * (float)Math.ceil(shield * pixelWidth)/pixelWidth, height );
		Hp.size( width * (float)Math.ceil(health * pixelWidth)/pixelWidth, height );

		Dot.size( width * (float)Math.ceil(incomingDOT * pixelWidth)/pixelWidth, height );
		Dot.scale.x = Math.min(Dot.scale.x, Shld.scale.x); //DOT darken can't go outside of HP bar
		Dot.x += Shld.width() - Dot.width();
	}
	
	@Override public java.util.Map<String,Object> gameplayStatus() {
		int[] pixels=renderedPixelWidths();
		return displayedHealth(pixels);
	}

	/** Shared by native health bars and HUD bars; values are laid-out display segments, never HP. */
	static java.util.Map<String,Object> displayedHealth(int[] pixels) {
		if(pixels.length!=4 || pixels[0]<=0)return java.util.Collections.emptyMap();
		java.util.Map<String,Object> sample=new java.util.LinkedHashMap<>();
		sample.put("total",pixels[0]);sample.put("filled",pixels[1]);sample.put("with_shield",pixels[2]);
		sample.put("incoming_dot",pixels[3]);
		sample.put("basis","displayed");
		return java.util.Collections.singletonMap("health_estimate",
				java.util.Collections.singletonMap("samples",java.util.Collections.singletonList(sample)));
	}

	public void level( float value ) {
		level( value, 0f, 0f );
	}

	/** Widths of the bars already laid out for drawing, in display pixels. */
	public int[] renderedPixelWidths() {
		if(Bg==null||Hp==null||Shld==null||Dot==null)return new int[0];
		return displayedPixelWidths(this,Bg.width(),Hp.width(),Shld.width(),Dot.width());
	}

	static int[] displayedPixelWidths(com.watabou.noosa.Gizmo owner,float total,float filled,float shield,float dot) {
		while (owner != null && owner.camera == null) owner = owner.parent;
		if (owner == null || owner.camera.scroll == null || !Float.isFinite(total+filled+shield+dot)) return new int[0];
		float zoom = owner.camera.observedTransform().zoom;
		if(!Float.isFinite(zoom)||zoom<=0)return new int[0];
		return new int[]{Math.round(total*zoom),Math.round(filled*zoom),Math.round(shield*zoom),Math.round(dot*zoom)};
	}

	public void level( float health, float shield, float DOT ){
		this.health = health;
		this.shield = shield;
		this.incomingDOT = DOT;
		layout();
	}

	public void level(Char c){
		if (c != null) {
			float health = c.HP;
			float shield = c.shielding();
			float incomingDot = c.incomingDOT();
			float max = Math.max(health + shield, c.HT);

			level(health / max, (health + shield) / max, incomingDot / max);
		}
	}
}
