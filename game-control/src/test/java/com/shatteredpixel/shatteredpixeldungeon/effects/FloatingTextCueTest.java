package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Camera;
import com.watabou.noosa.RenderedText;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FloatingTextCueTest {
    @Test void onlyExactDisplayedCountdownLiteralsAreRecognized() {
        assertEquals("bomb_countdown_3", VisualCueCollector.countdownKind("3..."));
        assertEquals("bomb_countdown_2", VisualCueCollector.countdownKind("2..."));
        assertEquals("bomb_countdown_1", VisualCueCollector.countdownKind("1..."));
        for (String text : new String[]{null, "", "3", "3…", "3... ", "4...", "1 damage", "时间气泡"})
            assertNull(VisualCueCollector.countdownKind(text), "Unknown displayed wording must never be guessed");
    }

    @Test void textIsReadFromExistingRenderableWordsWithoutChangingTheirPresentation() throws Exception {
        withFloatingText(source -> {
            RenderedText word = new RenderedText();
            set(word, RenderedText.class, "text", "3...");
            set(word, RenderedText.class, "font", allocate(BitmapFont.class));
            word.x = 20; word.y = 20; word.width = 12; word.height = 8;
            source.add(word);
            source.camera = new Camera(0, 0, 100, 100, 1);
            @SuppressWarnings("unchecked") ArrayList<RenderedText> words = (ArrayList<RenderedText>) get(source, RenderedTextBlock.class, "words");
            words.add(word);
            set(source, RenderedTextBlock.class, "text", "3...");
            for (int i = 0; i < 10; i++) assertEquals("3...", source.visibleCueText());
            assertEquals("3...", word.text()); assertEquals(20, word.y); assertEquals(1, word.am);
            word.am = 0; assertNull(source.visibleCueText());
            word.am = 1; word.visible = false; assertNull(source.visibleCueText());
            word.visible = true; word.x = 95; assertNull(source.visibleCueText(), "A clipped digit or ellipsis is not expanded");
            word.x = 20; set(word, RenderedText.class, "font", null); assertNull(source.visibleCueText());
            assertEquals("3...", source.text(), "Projection never rewrites the original UI literal");
        });
    }

    @Test void pooledTextCannotInheritThePreviousGridAnnotation() throws Exception {
        withFloatingText(source -> {
            set(source, FloatingText.class, "observationCell", 22);
            source.kill(); source.revive();
            assertEquals(-1, get(source, FloatingText.class, "observationCell"));
        });
    }

    @Test void floatingBoundsMustFitTheViewportAndAvoidHudEvenWhenAnchorCellFits() {
        VisualCueProjection.Viewport view = new VisualCueProjection.Viewport(0, 0, 100, 100, 10, 20, 2);
        VisualCueProjection.Rect text = new VisualCueProjection.Rect(30, 40, 60, 50);
        assertTrue(VisualCueProjection.permitsScreenBounds(text, view, Collections.emptyList()));
        assertFalse(VisualCueProjection.permitsScreenBounds(new VisualCueProjection.Rect(5, 40, 60, 50), view, Collections.emptyList()));
        assertFalse(VisualCueProjection.permitsScreenBounds(text, view,
                Collections.singletonList(new VisualCueProjection.Rect(59, 40, 70, 70))));
        assertFalse(VisualCueProjection.permitsScreenBounds(new VisualCueProjection.Rect(Float.NaN, 40, 60, 50), view, Collections.emptyList()));
    }

    private interface Check { void run(FloatingText source) throws Exception; }
    private static void withFloatingText(Check check) throws Exception {
        @SuppressWarnings("unchecked") Map<Object, SmartTexture> textures = (Map<Object, SmartTexture>) get(null, TextureCache.class, "all");
        SmartTexture previous = textures.get(Assets.Effects.TEXT_ICONS);
        SmartTexture dimensions = allocate(SmartTexture.class); dimensions.width = 70; dimensions.height = 80;
        textures.put(Assets.Effects.TEXT_ICONS, dimensions);
        try { check.run(new FloatingText()); }
        finally { if (previous == null) textures.remove(Assets.Effects.TEXT_ICONS); else textures.put(Assets.Effects.TEXT_ICONS, previous); }
    }
    private static Object get(Object target, Class<?> type, String name) throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target); }
    private static void set(Object target, Class<?> type, String name, Object value) throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value); }
    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("jdk.internal.misc.Unsafe");
        return (T) unsafe.getMethod("allocateInstance", Class.class).invoke(get(null, unsafe, "theUnsafe"), type);
    }
}
