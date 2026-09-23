package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.RenderedText;
import com.watabou.noosa.Scene;
import com.watabou.utils.Random;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Original visibility projection over explicit drawable words, without a native renderer. */
class UnrenderedTextVisibilityTest {
    @org.junit.jupiter.api.BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    @Test void zeroInvalidAndNegativeOpacityCannotPublishAControlOrItsFullResourceKey() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            byte[] random = Random.exportState();
            String source = ResourceTextFixture.source("windows.wndupgrade.upgrade");
            for (float alpha : new float[]{0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
                DrawableWord word = new DrawableWord(source, 10); word.am = alpha; word.aa = 0;
                Block block = new Block(source, word); Scene scene = new Scene(); scene.add(block);
                assertHidden(scene, block, "windows.wndupgrade.upgrade");
                assertSame(source, block.text()); assertNull(word.camera);
            }
            assertArrayEquals(random, Random.exportState());
        }
    }

    @Test void positiveGeometryWithoutANativeFontDoesNotMakeTextRenderable() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            String source = ResourceTextFixture.source("windows.wndupgrade.desc");
            NoFontWord word = new NoFontWord(source); // Keeps actual RenderedText.hasRenderableText and draw.
            assertTrue(word.width() > 0 && word.height() > 0); assertFalse(word.hasRenderableText());
            word.draw(); // Actual draw exits before touching GL when the font is absent.
            Block block = new Block(source, word); Scene scene = new Scene(); scene.add(block);
            assertHidden(scene, block, "windows.wndupgrade.desc");
            assertSame(source, block.text()); assertNull(word.camera);
        }
    }

    @Test void anInvisibleWordPreventsWholeMessageAndParameterProvenanceFromEscaping() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            String visibleSource = ResourceTextFixture.source("windows.wndupgrade.upgrade");
            String hiddenSource = ResourceTextFixture.source("windows.wndupgrade.remaining", 987654);
            String complete = ResourceTextFixture.join(visibleSource, " ", hiddenSource);
            DrawableWord visible = new DrawableWord(visibleSource, 10), hidden = new DrawableWord(hiddenSource, 20);
            hidden.am = hidden.aa = 0;
            Block block = new Block(complete, visible, hidden); Scene scene = new Scene(); scene.add(block);
            RenderedTextBlock.VisibleText fragment = block.visibleTextFragment();
            assertTrue(fragment.visible); assertTrue(fragment.clipped); assertEquals(visibleSource, fragment.text);
            String canonical = JsonCodec.encode(UiDrawFixture.capture(new UiBridge(() -> scene)).frozenUi());
            String wire = JsonCodec.encode(UiDrawFixture.capture(new UiBridge(() -> scene)).describeUi());
            for (String secret : List.of("windows.wndupgrade.upgrade", "windows.wndupgrade.remaining", "987654")) {
                assertFalse(canonical.contains(secret), canonical); assertFalse(wire.contains(secret), wire);
            }
            assertTrue(wire.contains("Partially displayed text"));
            assertSame(complete, block.text()); assertNull(visible.camera); assertNull(hidden.camera);
        }
    }

    @Test void disabledAndAdditiveNonzeroOpacityStillPermitActuallyDrawableText() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            String source = ResourceTextFixture.source("windows.wndupgrade.upgrade");
            DrawableWord word = new DrawableWord(source, 10); word.am = .3f; word.aa = 0; word.active = false;
            Block block = new Block(source, word); block.active = false;
            RenderedTextBlock.VisibleText fragment = block.visibleTextFragment();
            assertTrue(fragment.visible); assertFalse(fragment.clipped);
            assertEquals("Upgrade", TextProvenance.INSTANCE.render(TextProvenance.INSTANCE.capture(block, fragment.text, false)).get("text"));
            word.am = 0; word.aa = .3f;
            assertTrue(block.visibleTextFragment().visible); assertFalse(block.visibleTextFragment().clipped);
            assertNull(word.camera);
        }
    }

    private static void assertHidden(Scene scene, Block block, String key) {
        RenderedTextBlock.VisibleText fragment = block.visibleTextFragment();
        assertFalse(fragment.visible); assertTrue(fragment.clipped); assertEquals("", fragment.text);
        UiBridge bridge = new UiBridge(() -> scene);
        assertTrue(((List<?>) UiDrawFixture.capture(bridge).describeUi().get("controls")).isEmpty());
        assertFalse(JsonCodec.encode(UiDrawFixture.capture(bridge).frozenUi()).contains(key));
        Map<String,Object> token = TextProvenance.INSTANCE.capture(block, fragment.text, fragment.clipped);
        assertFalse(JsonCodec.encode(token).contains(key)); assertNull(TextProvenance.INSTANCE.render(token).get("source"));
    }

    private static final class Block extends RenderedTextBlock {
        Block(String source, RenderedText... glyphs) {
            super(6); setHightlighting(false); text = source; Game.observer.onTextBound(this, source);
            camera = new Camera(0, 0, 100, 100, 1);
            for (RenderedText glyph : glyphs) { words.add(glyph); add(glyph); }
        }
        @Override protected void layout() { }
    }
    private static final class DrawableWord extends RenderedText {
        final String value;
        DrawableWord(String value, float y) { this.value = value; x = 10; this.y = y; width = 30; height = 8; }
        @Override public String text() { return value; }
        @Override public boolean hasRenderableText() { return value != null && !value.isEmpty(); }
        @Override public void draw() { }
    }
    private static final class NoFontWord extends RenderedText {
        final String value;
        NoFontWord(String value) { this.value = value; x = 10; y = 10; width = 30; height = 8; }
        @Override public String text() { return value; }
    }
}
