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

import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.RenderedText;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class RenderedTextBlock extends Component {

	private int maxWidth = Integer.MAX_VALUE;
	public int nLines;

	private static final RenderedText SPACE = new RenderedText();
	private static final RenderedText NEWLINE = new RenderedText();
	
	protected String text;
	protected String[] tokens = null;
	protected ArrayList<RenderedText> words = new ArrayList<>();
	protected boolean multiline = false;
	private final IdentityHashMap<RenderedText, Integer> markupSegments = new IdentityHashMap<>();
	private int markupSegmentCount = 1;

	private int size;
	private float zoom;
	private int color = -1;
	private boolean animatedColor;

	/** Explicit native color-phase annotation; displayed colors remain public, only input meaning ignores the phase. */
	public synchronized void animatedColor(int color){hardlight(color);animatedColor=true;}
	public synchronized boolean hasAnimatedColor(){return animatedColor;}
	@Override public synchronized void revive(){super.revive();animatedColor=false;}
	
	private int hightlightColor = Window.TITLE_COLOR;
	private boolean highlightingEnabled = true;

	public static final int LEFT_ALIGN = 1;
	public static final int CENTER_ALIGN = 2;
	public static final int RIGHT_ALIGN = 3;
	private int alignment = LEFT_ALIGN;
	
	public RenderedTextBlock(int size){
		this.size = size;
	}

	public RenderedTextBlock(String text, int size){
		this.size = size;
		text(text);
	}

	public void text(String text){
		animatedColor=false;
		this.text = text;
		Game.observer.onTextBound(this, text);

		if (text != null && !text.equals("")) {
			
			tokens = Game.platform.splitforTextBlock(text, multiline);
			
			build();
		}
	}

	//for manual text block splitting, a space between each word is assumed
	public void tokens(String... words){
		animatedColor=false;
		String fullText = "";
		for (String word : words) {
			fullText = Messages.concat(fullText, word);
		}
		text = fullText;
		Game.observer.onTextBound(this, text);

		tokens = words;
		build();
	}

	public void text(String text, int maxWidth){
		this.maxWidth = maxWidth;
		multiline = true;
		text(text);
	}

	public String text(){
		return text;
	}

	/** A pure reading of already-laid-out words. Partly clipped words are never expanded to full text. */
	public synchronized VisibleText visibleTextFragment() {
		return visibleTextFragment(null);
	}

	/** Optional draw-time restriction, used for words later covered by another visible layer. */
	protected synchronized VisibleText visibleTextFragment(java.util.function.Predicate<RenderedText> completeWord) {
		return textFragment(completeWord,false);
	}

	/** Only a source with a certified current-FOV world anchor may use this camera-independent path. */
	protected synchronized VisibleText anchoredTextFragment(java.util.function.Predicate<RenderedText> completeWord) {
		return textFragment(completeWord,true);
	}

	private VisibleText textFragment(java.util.function.Predicate<RenderedText> completeWord,boolean anchored) {
		StringBuilder result = new StringBuilder(), separators = new StringBuilder();
		List<VisibleStyle> styles = new ArrayList<>();
		boolean clipped = false, visible = false, omitted = false;
		if (words == null) return new VisibleText("", false, false);
		for (RenderedText word : words) {
			if (word == SPACE) { separators.append(' '); continue; }
			if (word == NEWLINE) { separators.append('\n'); continue; }
			if (word == null) continue;
			boolean shown = true;
			Camera camera = null;
			for (Gizmo current = word; current != null; current = current.parent) {
				// Group.draw ignores update/interaction activity: disabled labels are still drawn.
				if (!current.exists || !current.visible) shown = false;
				if (camera == null && current.camera != null) camera = current.camera;
			}
			// Calling Visual.isVisible()/camera() here would populate camera caches during a query.
			float width = word.width(), height = word.height();
			float alpha = word.am + word.aa;
			Camera.DrawnTransform transform=camera!=null&&camera.scroll!=null?camera.observedTransform():null;
			// A positive layout rectangle alone does not draw letters: RenderedText.draw
			// needs a font, and zero effective shader opacity cannot expose its contents.
			boolean paintable = word.hasRenderableText() && Float.isFinite(alpha) && alpha > 0;
			boolean layout = shown && paintable && width > 0 && height > 0
					&& Float.isFinite(word.x+word.y+width+height)
					&& word.angle == 0 && word.origin.x == 0 && word.origin.y == 0;
			boolean intersects = layout && (anchored || camera != null && transform != null
					&& word.x < transform.scrollX + transform.width && word.x + width > transform.scrollX
					&& word.y < transform.scrollY + transform.height && word.y + height > transform.scrollY);
			boolean complete = intersects && (anchored || word.x >= transform.scrollX && word.y >= transform.scrollY
					&& word.x + width <= transform.scrollX + transform.width && word.y + height <= transform.scrollY + transform.height);
			complete &= completeWord == null || completeWord.test(word);
			visible |= intersects;
			if (complete && word.text() != null) {
				if (result.length() > 0) {
					if (omitted) result.append('\n');
					else result.append(separators);
				}
				result.append(word.text()); omitted = false;
				int segment = markupSegments == null ? 0 : markupSegments.getOrDefault(word, 0);
				int rgb = word.displayedTextColor();
				String separator = styles.isEmpty() ? "" : separators.toString();
				if (!styles.isEmpty() && styles.get(styles.size()-1).segment == segment
						&& styles.get(styles.size()-1).color == rgb) {
					VisibleStyle previous = styles.remove(styles.size()-1);
					styles.add(new VisibleStyle(previous.text + separator + word.text(), rgb, segment));
				} else styles.add(new VisibleStyle(word.text(), rgb, segment));
			} else { clipped = true; omitted = true; }
			separators.setLength(0);
		}
		// GameLog disables markup; preserve its original spacing when every rendered word fits.
		String shownText = !clipped && !highlightingEnabled && text != null ? text : result.toString();
		if (!clipped && text != null && shownText != text)
			shownText = Game.observer.onTextOperation("displayed", shownText, text, highlightingEnabled);
		List<VisibleStyle> frozen = new ArrayList<>();
		for (VisibleStyle style : styles) {
			String fragment = style.text;
			if (!clipped && text != null) {
				if (styles.size() == 1) fragment = shownText;
				else fragment = Game.observer.onTextOperation("markup_segment", fragment,
						text, style.segment, markupSegmentCount);
			}
			frozen.add(new VisibleStyle(fragment, style.color, style.segment));
		}
		return new VisibleText(shownText, clipped, visible, frozen);
	}

	public static final class VisibleStyle {
		public final String text;
		public final int color;
		private final int segment;
		public VisibleStyle(String text, int color, int segment) {
			this.text = text; this.color = color; this.segment = segment;
		}
	}

	public static final class VisibleText {
		public final String text;
		public final boolean clipped, visible;
		public final List<VisibleStyle> styles;
		public VisibleText(String text, boolean clipped, boolean visible) {
			this(text, clipped, visible, Collections.emptyList());
		}
		public VisibleText(String text, boolean clipped, boolean visible, List<VisibleStyle> styles) {
			this.text = text; this.clipped = clipped; this.visible = visible;
			this.styles = Collections.unmodifiableList(new ArrayList<>(styles));
		}
		/** Includes no text that failed the same visibility gate as the parent fragment. */
		public Map<String,Object> styleData() {
			Map<String,Object> result = new LinkedHashMap<>();
			if (styles.isEmpty()) return result;
			int color = styles.get(0).color;
			boolean uniform = true;
			for (VisibleStyle style : styles) uniform &= style.color == color;
			if (uniform) result.put("color", color);
			else {
				List<Map<String,Object>> runs = new ArrayList<>();
				for (VisibleStyle style : styles) {
					Map<String,Object> run = new LinkedHashMap<>();
					run.put("text", style.text); run.put("color", style.color);
					if (clipped) run.put("clipped", true);
					runs.add(Collections.unmodifiableMap(run));
				}
				result.put("styles", Collections.unmodifiableList(runs));
			}
			return Collections.unmodifiableMap(result);
		}
	}

	public void maxWidth(int maxWidth){
		if (this.maxWidth != maxWidth){
			this.maxWidth = maxWidth;
			multiline = true;
			text(text);
		}
	}

	public int maxWidth(){
		return maxWidth;
	}

	private synchronized void build(){
		if (tokens == null) return;
		
		clear();
		words = new ArrayList<>();
		markupSegments.clear();
		markupSegmentCount = 1;
		boolean highlighting = false;
		for (String str : tokens){

			//if highlighting is enabled, '_' or '**' is used to toggle highlighting on or off
			// the actual symbols are not rendered
			if ((str.equals("_") || str.equals("**")) && highlightingEnabled){
				highlighting = !highlighting;
				markupSegmentCount++;
			} else if (str.equals("\n")){
				words.add(NEWLINE);
			} else if (str.equals(" ")){
				words.add(SPACE);
			} else {
				RenderedText word = new RenderedText(str, size);
				
				if (highlighting) word.hardlight(hightlightColor);
				else if (color != -1) word.hardlight(color);
				word.scale.set(zoom);
				
				words.add(word);
				markupSegments.put(word, markupSegmentCount - 1);
				add(word);
				
				if (height < word.height()) height = word.height();
			}
		}
		layout();
	}

	public synchronized void zoom(float zoom){
		this.zoom = zoom;
		for (RenderedText word : words) {
			if (word != null) word.scale.set(zoom);
		}
		layout();
	}

	public synchronized void hardlight(int color){
		animatedColor=false;
		this.color = color;
		for (RenderedText word : words) {
			if (word != null) word.hardlight( color );
		}
	}
	
	public synchronized void resetColor(){
		animatedColor=false;
		this.color = -1;
		for (RenderedText word : words) {
			if (word != null) word.resetColor();
		}
	}
	
	public synchronized void alpha(float value){
		for (RenderedText word : words) {
			if (word != null) word.alpha( value );
		}
	}
	
	public synchronized void setHightlighting(boolean enabled){
		setHightlighting(enabled, Window.TITLE_COLOR);
	}
	
	public synchronized void setHightlighting(boolean enabled, int color){
		if (enabled != highlightingEnabled || color != hightlightColor) {
			animatedColor=false;
			hightlightColor = color;
			highlightingEnabled = enabled;
			build();
		}
	}

	public synchronized void invert(){
		animatedColor=false;
		if (words != null) {
			for (RenderedText word : words) {
				if (word != null) {
					word.ra = 0.77f;
					word.ga = 0.73f;
					word.ba = 0.62f;
					word.rm = -0.77f;
					word.gm = -0.73f;
					word.bm = -0.62f;
				}
			}
		}
	}

	public synchronized void align(int align){
		alignment = align;
		layout();
	}

	@Override
	protected synchronized void layout() {
		super.layout();
		float x = this.x;
		float y = this.y;
		float height = 0;
		nLines = 1;

		ArrayList<ArrayList<RenderedText>> lines = new ArrayList<>();
		ArrayList<RenderedText> curLine = new ArrayList<>();
		lines.add(curLine);

		width = 0;
		for (int i = 0; i < words.size(); i++){
			RenderedText word = words.get(i);
			if (word == SPACE){
				x += 1.667f;
			} else if (word == NEWLINE) {
				//newline
				y += height+2f;
				x = this.x;
				nLines++;
				curLine = new ArrayList<>();
				lines.add(curLine);
			} else {
				if (word.height() > height) height = word.height();

				float fullWidth = word.width();
				int j = i+1;

				//this is so that words split only by highlighting are still grouped in layout
				//Chinese/Japanese always render every character separately without spaces however
				while (Messages.lang() != Languages.CHI_SMPL && Messages.lang() != Languages.CHI_TRAD
						&& Messages.lang() != Languages.JAPANESE
						&& j < words.size() && words.get(j) != SPACE && words.get(j) != NEWLINE){
					fullWidth += words.get(j).width() - 0.667f;
					j++;
				}

				if ((x - this.x) + fullWidth - 0.001f > maxWidth && !curLine.isEmpty()){
					y += height+2f;
					x = this.x;
					nLines++;
					curLine = new ArrayList<>();
					lines.add(curLine);
				}

				word.x = x;
				word.y = y;
				PixelScene.align(word);
				x += word.width();
				curLine.add(word);

				if ((x - this.x) > width) width = (x - this.x);
				
				//Note that spacing currently doesn't factor in halfwidth and fullwidth characters
				//(e.g. Ideographic full stop)
				x -= 0.667f;

			}
		}
		this.height = (y - this.y) + height;

		if (alignment != LEFT_ALIGN){
			for (ArrayList<RenderedText> line : lines){
				if (line.size() == 0) continue;
				float lineWidth = line.get(line.size()-1).width() + line.get(line.size()-1).x - this.x;
				if (alignment == CENTER_ALIGN){
					for (RenderedText text : line){
						text.x += (width() - lineWidth)/2f;
						PixelScene.align(text);
					}
				} else if (alignment == RIGHT_ALIGN) {
					for (RenderedText text : line){
						text.x += width() - lineWidth;
						PixelScene.align(text);
					}
				}
			}
		}
	}
}
