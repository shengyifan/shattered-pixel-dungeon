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

package com.watabou.noosa;

import com.watabou.glwrap.Matrix;
import com.watabou.utils.Point;
import com.watabou.utils.PointF;
import com.watabou.utils.Random;

import java.util.ArrayList;

public class Camera extends Gizmo {

	private static ArrayList<Camera> all = new ArrayList<>();
	
	protected static float invW2;
	protected static float invH2;
	
	public static Camera main;

	public boolean fullScreen;

	public float zoom;
	
	public int x;
	public int y;
	public int width;
	public int height;
	
	int screenWidth;
	int screenHeight;
	
	public float[] matrix;

	public PointF edgeScroll;
	public PointF scroll;
	public PointF centerOffset;
	
	private float shakeMagX		= 10f;
	private float shakeMagY		= 10f;
	private float shakeTime		= 0f;
	private float shakeDuration	= 1f;
	
	protected float shakeX;
	protected float shakeY;

	private float unshakenTranslationX=Float.NaN,unshakenTranslationY=Float.NaN;
	private Object shakeEpisode=new Object();
	private Object matrixShakeEpisode=shakeEpisode;
	private long observedDrawGeneration;
	private DrawnTransform observedTransform;

	/** Immutable transform from the matrix submitted to a successful nonempty GPU draw. */
	public static final class DrawnTransform {
		public final float scrollX,scrollY,width,height,x,y,zoom,offsetX,offsetY;
		public final Object episode;
		DrawnTransform(float scrollX,float scrollY,float width,float height,float x,float y,float zoom,
		               float offsetX,float offsetY,Object episode) {
			this.scrollX=scrollX;this.scrollY=scrollY;this.width=width;this.height=height;
			this.x=x;this.y=y;this.zoom=zoom;this.offsetX=offsetX;this.offsetY=offsetY;this.episode=episode;
		}
		public float worldToScreenX(float value){return x+(value-scrollX)*zoom;}
		public float worldToScreenY(float value){return y+(value-scrollY)*zoom;}
		public float screenToWorldX(float value){return scrollX+(value-x)/zoom;}
		public float screenToWorldY(float value){return scrollY+(value-y)/zoom;}
	}

	/** Records only the neutral projection of the same committed matrix, never future shake parameters. */
	protected final void rememberUnshakenProjection(float scrollX,float scrollY) {
		unshakenTranslationX=-1+x*invW2-scrollX*matrix[0];
		unshakenTranslationY=+1-y*invH2-scrollY*matrix[5];
		matrixShakeEpisode=shakeEpisode;
	}

	/** Called when the renderer uploads this exact matrix; copying it does not advance the camera. */
	public synchronized DrawnTransform submittedTransform() {
		if(matrix==null||matrix.length<16||!Float.isFinite(invW2+invH2)||invW2<=0||invH2<=0
				||!Float.isFinite(unshakenTranslationX+unshakenTranslationY)||matrix[0]<=0||matrix[5]>=0)return null;
		float sx=(-1+x*invW2-matrix[12])/matrix[0],sy=(1-y*invH2-matrix[13])/matrix[5];
		float zx=matrix[0]/invW2,zy=-matrix[5]/invH2;
		if(!Float.isFinite(sx+sy+zx+zy)||zx<=0||Math.abs(zx-zy)>Math.max(Math.ulp(zx)*4,0.00001f))return null;
		float left=fullScreen?0:x,top=fullScreen?0:y;
		float screenW=fullScreen?Game.width:screenWidth,screenH=fullScreen?Game.height:screenHeight;
		if(screenW<=0||screenH<=0)return null;
		return new DrawnTransform(sx+(left-x)/zx,sy+(top-y)/zx,screenW/zx,screenH/zx,left,top,zx,
				(matrix[12]-unshakenTranslationX)/invW2,-(matrix[13]-unshakenTranslationY)/invH2,matrixShakeEpisode);
	}

	/** Called only after a nonempty draw using the previously uploaded transform. */
	public synchronized void recordRenderedTransform(DrawnTransform transform) {
		if(transform!=null){observedTransform=transform;observedDrawGeneration++;}
	}
	public synchronized long observedDrawGeneration(){return observedDrawGeneration;}

	/** Current draw geometry; the nominal fallback is layout-only and never proves an observed screen effect. */
	public synchronized DrawnTransform observedTransform() {
		if(observedTransform!=null)return observedTransform;
		DrawnTransform submitted=submittedTransform();
		return submitted!=null?submitted:new DrawnTransform(scroll.x,scroll.y,width,height,x,y,zoom,0,0,shakeEpisode);
	}
	
	public static Camera reset() {
		return reset( createFullscreen( 1 ) );
	}
	
	public static synchronized Camera reset( Camera newCamera ) {
		
		invW2 = 2f / Game.width;
		invH2 = 2f / Game.height;
		
		int length = all.size();
		for (int i=0; i < length; i++) {
			all.get( i ).destroy();
		}
		all.clear();
		
		return main = add( newCamera );
	}
	
	public static synchronized Camera add( Camera camera ) {
		all.add( camera );
		return camera;
	}
	
	public static synchronized Camera remove( Camera camera ) {
		all.remove( camera );
		return camera;
	}
	
	public static synchronized void updateAll() {
		int length = all.size();
		for (int i=0; i < length; i++) {
			Camera c = all.get( i );
			if (c != null && c.exists && c.active) {
				c.update();
			}
		}
	}
	
	public static Camera createFullscreen( float zoom ) {
		int w = (int)Math.ceil( Game.width / zoom );
		int h = (int)Math.ceil( Game.height / zoom );
		Camera c = new Camera(
				(int)(Game.width - w * zoom) / 2,
				(int)(Game.height - h * zoom) / 2,
				w, h, zoom );
		c.fullScreen = true;
		return c;
	}
	
	public Camera( int x, int y, int width, int height, float zoom ) {
		
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.zoom = zoom;
		
		screenWidth = (int)(width * zoom);
		screenHeight = (int)(height * zoom);

		edgeScroll = new PointF();
		scroll = new PointF();
		centerOffset = new PointF();
		
		matrix = new float[16];
		Matrix.setIdentity( matrix );
	}
	
	@Override
	public void destroy() {
		panIntensity = 0f;
	}
	
	public synchronized void zoom( float value ) {
		zoom( value,
			scroll.x + width / 2f,
			scroll.y + height / 2f );
	}
	
	public synchronized void zoom( float value, float fx, float fy ) {

		PointF offsetAdjust = centerOffset.clone();
		centerOffset.scale(zoom).invScale(value);

		zoom = value;
		width = (int)(screenWidth / zoom);
		height = (int)(screenHeight / zoom);
		
		snapTo( fx - offsetAdjust.x, fy - offsetAdjust.y );
	}
	
	public synchronized void resize( int width, int height ) {
		this.width = width;
		this.height = height;
		screenWidth = (int)(width * zoom);
		screenHeight = (int)(height * zoom);
	}
	
	Visual followTarget = null;
	PointF panTarget = new PointF();
	//camera moves at a speed such that it will pan to its current target in 1/intensity seconds
	//keep in mind though that this speed is constantly decreasing, so actual pan time is higher
	float panIntensity = 0f;

	//what percentage of the screen to ignore when follow panning.
	// 0% means always keep in the center, 50% would mean pan until target is within center 50% of screen
	float followDeadzone = 0f;
	
	@Override
	public synchronized void update() {
		super.update();

		float deadX = 0;
		float deadY = 0;
		if (followTarget != null && followTarget.visible){
			//manually assign here to avoid an allocation from sprite.center()
			panTarget.x = followTarget.x + followTarget.width()/2;
			panTarget.y = followTarget.y + followTarget.height()/2;
			panTarget.offset(centerOffset);
			deadX = width * followDeadzone /2f;
			deadY = height * followDeadzone /2f;
		}
		
		if (panIntensity > 0f){

			float panX = panTarget.x - (scroll.x + width/2f);
			float panY = panTarget.y - (scroll.y + height/2f);

			if (panX > deadX){
				panX -= deadX;
			} else if (panX < -deadX){
				panX += deadX;
			} else {
				panX = 0;
			}

			if (panY > deadY){
				panY -= deadY;
			} else if (panY < -deadY){
				panY += deadY;
			} else {
				panY = 0;
			}

			panX *= Math.min(1f, Game.elapsed * panIntensity);
			panY *= Math.min(1f, Game.elapsed * panIntensity);

			scroll.offset(panX, panY);
		}
		
		if ((shakeTime -= Game.elapsed) > 0) {
			float damping = shakeTime / shakeDuration;
			shakeX = Random.Float( -shakeMagX, +shakeMagX ) * damping;
			shakeY = Random.Float( -shakeMagY, +shakeMagY ) * damping;
		} else {
			shakeX = 0;
			shakeY = 0;
		}
		
		updateMatrix();
	}
	
	public PointF center() {
		return new PointF( width / 2, height / 2 );
	}
	
	public boolean hitTest( float x, float y ) {
		return x >= this.x && y >= this.y && x < this.x + screenWidth && y < this.y + screenHeight;
	}
	
	public synchronized void shift( PointF point ){
		scroll.offset(point);
		panIntensity = 0f;
	}

	public synchronized void setCenterOffset( float x, float y ){
		scroll.x    += x - centerOffset.x;
		scroll.y    += y - centerOffset.y;
		if (panTarget != null) {
			panTarget.x += x - centerOffset.x;
			panTarget.y += y - centerOffset.y;
		}
		centerOffset.set(x, y);
	}
	
	public synchronized void snapTo(float x, float y ) {
		scroll.set( x - width / 2f, y - height / 2f ).offset(centerOffset);
		panIntensity = 0f;
		followTarget = null;
	}
	
	public void snapTo(PointF point ) {
		snapTo( point.x, point.y );
	}
	
	public synchronized void panTo( PointF dst, float intensity ){
		panTarget = dst.offset(centerOffset);
		panIntensity = intensity;
		followTarget = null;
	}
	
	public synchronized void panFollow(Visual target, float intensity ){
		followTarget = target;
		panIntensity = intensity;
	}

	public synchronized Visual followTarget(){
		return followTarget;
	}

	public synchronized void setFollowDeadzone( float deadzone ){
		followDeadzone = deadzone;
	}
	
	public PointF screenToCamera( int x, int y ) {
		return new PointF(
			(x - this.x) / zoom + scroll.x,
			(y - this.y) / zoom + scroll.y );
	}
	
	public Point cameraToScreen( float x, float y ) {
		return new Point(
			(int)((x - scroll.x) * zoom + this.x),
			(int)((y - scroll.y) * zoom + this.y));
	}
	
	public float screenWidth() {
		return width * zoom;
	}
	
	public float screenHeight() {
		return height * zoom;
	}
	
	protected void updateMatrix() {

	/*	Matrix.setIdentity( matrix );
		Matrix.translate( matrix, -1, +1 );
		Matrix.scale( matrix, 2f / G.width, -2f / G.height );
		Matrix.translate( matrix, x, y );
		Matrix.scale( matrix, zoom, zoom );
		Matrix.translate( matrix, scroll.x, scroll.y );*/
		
		matrix[0] = +zoom * invW2;
		matrix[5] = -zoom * invH2;
		
		matrix[12] = -1 + x * invW2 - (scroll.x + shakeX) * matrix[0];
		matrix[13] = +1 - y * invH2 - (scroll.y + shakeY) * matrix[5];
		rememberUnshakenProjection(scroll.x,scroll.y);
		
	}
	
	public synchronized void shake( float magnitude, float duration ) {
		shakeEpisode=new Object();
		shakeMagX = shakeMagY = magnitude;
		shakeTime = shakeDuration = duration;
	}
}
