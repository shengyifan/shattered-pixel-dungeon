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

import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.ui.Component;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.utils.Signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.regex.Pattern;

public class GameLog extends Component implements Signal.Listener<String> {

	private static final int MAX_LINES = 3;

	private static final Pattern PUNCTUATION = Pattern.compile( ".*[.,;?! ]$" );

	private RenderedTextBlock lastEntry;
	private int lastColor;
	private final String logContextId = Dungeon.runId;
	private final IdentityHashMap<RenderedTextBlock, Integer> entryColors = new IdentityHashMap<>();

	private static ArrayList<Entry> entries = new ArrayList<>();

	public GameLog() {
		super();
		GLog.update.replace( this );

		recreateLines();
	}
	
	private static ArrayList<String> textsToAdd = new ArrayList<>();
	
	@Override
	public synchronized void update() {

		synchronized (textsToAdd){
			if (!textsToAdd.isEmpty()){
				int maxLines = SPDSettings.interfaceSize() > 0 ? 5 : 3;
				for (String text : textsToAdd){
					if (length != entries.size()){
						clear();
						recreateLines();
					}

					if (text.equals( GLog.NEW_LINE )){
						lastEntry = null;
						continue;
					}

					int color = CharSprite.DEFAULT;
					if (text.startsWith( GLog.POSITIVE )) {
						text = text.substring( GLog.POSITIVE.length() );
						color = CharSprite.POSITIVE;
					} else
					if (text.startsWith( GLog.NEGATIVE )) {
						text = text.substring( GLog.NEGATIVE.length() );
						color = CharSprite.NEGATIVE;
					} else
					if (text.startsWith( GLog.WARNING )) {
						text = text.substring( GLog.WARNING.length() );
						color = CharSprite.WARNING;
					} else
					if (text.startsWith( GLog.HIGHLIGHT )) {
						text = text.substring( GLog.HIGHLIGHT.length() );
						color = CharSprite.NEUTRAL;
					}

					if (lastEntry != null && color == lastColor && lastEntry.nLines < maxLines) {

						String lastMessage = lastEntry.text();
						lastEntry.text( lastMessage.length() == 0 ? text : lastMessage + " " + text );

						entries.get( entries.size() - 1 ).text = lastEntry.text();

					} else {

						lastEntry = PixelScene.renderTextBlock( text, 6 );
							lastEntry.hardlight( color );
							entryColors.put(lastEntry, color);
						lastColor = color;
						add( lastEntry );

						entries.add( new Entry( text, color ) );

					}

					if (length > 0) {
						int nLines;
						do {
							nLines = 0;
							for (int i = 0; i < length-1; i++) {
								nLines += ((RenderedTextBlock) members.get(i)).nLines;
							}

							if (nLines > maxLines) {
								RenderedTextBlock r = ((RenderedTextBlock) members.get(0));
								remove(r);
								entryColors.remove(r);
								r.destroy();

								entries.remove( 0 );
							}
						} while (nLines > maxLines);
						if (entries.isEmpty()) {
							lastEntry = null;
						}
					}
				}

				layout();
				textsToAdd.clear();
			}
		}
		super.update();
	}
	
	private synchronized void recreateLines() {
		entryColors.clear();
		for (Entry entry : entries) {
			lastEntry = PixelScene.renderTextBlock( entry.text, 6 );
			lastEntry.hardlight( lastColor = entry.color );
			entryColors.put(lastEntry, entry.color);
			add( lastEntry );
		}
	}

	@Override
	public synchronized void draw() {
		super.draw();
		RuntimeObserver observer = Game.observer;
		if (observer == RuntimeObserver.NONE || !exists || !alive || !isVisible() || !isActive()
				|| logContextId == null || !Objects.equals(logContextId, Dungeon.runId) || Game.instance == null) return;
		Gizmo root = this;
		while (root.parent != null) root = root.parent;
		if (root != Game.scene()) return;
		ArrayList<RuntimeObserver.LogEntry> displayed = new ArrayList<>();
		for (Gizmo child : members) {
			if (!(child instanceof RenderedTextBlock) || !child.exists || !child.alive
					|| !child.isVisible() || !child.isActive()) continue;
			RenderedTextBlock text = (RenderedTextBlock) child;
			Integer color = entryColors.get(text);
			RenderedTextBlock.VisibleText fragment = text.visibleTextFragment();
			if (color != null && fragment.visible)
				displayed.add(new RuntimeObserver.LogEntry(fragment.text, color, fragment.clipped));
		}
		observer.onGameLog(logContextId, Collections.unmodifiableList(displayed));
	}

	public synchronized void newLine() {
		lastEntry = null;
	}

	@Override
	public boolean onSignal( String text ) {
		synchronized (textsToAdd) {
			textsToAdd.add(text);
		}
		return false;
	}

	@Override
	protected void layout() {
		float pos = y;
		for (int i=length-1; i >= 0; i--) {
			RenderedTextBlock entry = (RenderedTextBlock)members.get( i );
			entry.setHightlighting(false);
			entry.maxWidth((int)width);
			entry.setPos(x, pos-entry.height());
			pos -= entry.height()+2;
		}
	}

	private static class Entry {
		public String text;
		public int color;
		public Entry( String text, int color ) {
			this.text = text;
			this.color = color;
		}
	}

	public static void wipe() {
		synchronized (textsToAdd) {
			entries.clear();
			textsToAdd.clear();
		}
	}
}
