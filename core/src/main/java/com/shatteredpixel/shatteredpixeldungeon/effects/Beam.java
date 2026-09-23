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

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.watabou.glwrap.Blending;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.PointF;

public class Beam extends Image {
	
	private static final double A = 180 / Math.PI;
	
	private  float duration;
	
	private float timeLeft;
	protected String visualKind;
	private com.watabou.noosa.VisualCue observation;

	private Beam(PointF s, PointF e, Effects.Type asset, float duration) {
		super( Effects.get( asset ) );
		
		origin.set( 0, height / 2 );
		
		x = s.x - origin.x;
		y = s.y - origin.y;
		
		float dx = e.x - s.x;
		float dy = e.y - s.y;
		angle = (float)(Math.atan2( dy, dx ) * A);
		scale.x = (float)Math.sqrt( dx * dx + dy * dy ) / width;
		
		timeLeft = this.duration = duration;
		visualKind = asset == Effects.Type.DEATH_RAY ? "death_ray"
				: asset == Effects.Type.HEALTH_RAY ? "health_ray" : "light_ray";
	}

	/** Origin and endpoint of this existing beam, published only after it is actually drawn. */
	public Beam observeDraw(int from, int to) {
		if (Game.observer.observesVisualCues()) observation = new com.watabou.noosa.VisualCue(visualKind, to, from, null);
		return this;
	}

	public static class DeathRay extends Beam{
		public DeathRay(PointF s, PointF e){
			super(s, e, Effects.Type.DEATH_RAY, 0.5f);
		}
	}

	public static class LightRay extends Beam{
		public LightRay(PointF s, PointF e){
			super(s, e, Effects.Type.LIGHT_RAY, 1f);
		}
	}

	public static class SunRay extends Beam{
		public SunRay(PointF s, PointF e){
			super(s, e, Effects.Type.LIGHT_RAY, 1f);
			tint(1, 1, 0.25f, 1);
			super.visualKind = "sun_ray";
		}
	}

	public static class HealthRay extends Beam{
		public HealthRay(PointF s, PointF e){
			super(s, e, Effects.Type.HEALTH_RAY, 0.75f);
		}
	}
	
	@Override
	public void update() {
		super.update();
		
		float p = timeLeft / duration;
		alpha( p );
		scale.set( scale.x, p );
		
		if ((timeLeft -= Game.elapsed) <= 0) {
			killAndErase();
		}
	}
	
	@Override
	public void draw() {
		Blending.setLightMode();
		super.draw();
		Blending.setNormalMode();

	}

	@Override public void observeGameplayVisuals() {
		if (observation != null && texture != null && Game.observer.observesVisualCues())
			com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.observeCellVisualDraw(this, observation);
	}
}
