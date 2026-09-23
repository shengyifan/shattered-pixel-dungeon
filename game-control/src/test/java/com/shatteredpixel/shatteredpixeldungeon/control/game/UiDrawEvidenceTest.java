package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.ui.CurrencyIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameLog;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit manual after-draw captures of in-memory display fixtures; these are not GPU evidence. */
class UiDrawEvidenceTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void bitmapTextColorAndSourceWaitForTheMatchingDrawWithoutALiveFallback() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Scene scene = new Scene();
            String first = ResourceTextFixture.source("windows.wndupgrade.blocking");
            String second = ResourceTextFixture.source("items.stones.stoneofaugmentation$wndaugment.defense");
            BitmapText text = bitmap(first, 0xFFFFFF); scene.add(text);
            UiBridge bridge = new UiBridge(() -> scene);
            assertNoDisplayText(bridge.describeUi());
            assertFalse(bridge.drawnIntentReady());

            bridge.captureDrawnEvidence();
            Map<String,Object> drawn = onlyText(bridge.describeUi());
            String serialized = JsonCodec.encode(drawn);
            assertEquals(ResourceTextFixture.english(first), drawn.get("text"));
            assertEquals(0xFFFFFF, drawn.get("color"));
            assertTrue(bridge.drawnIntentReady());

            // Simulate Game.step updating native fields after Game.draw, before response capture.
            text.text(second); text.hardlight(0);
            assertEquals(drawn, onlyText(bridge.describeUi()), "Do not attach a post-step color or source to older text");
            assertFalse(bridge.drawnIntentReady(), "Meaningful undrawn paint must postpone a terminal observation");
            bridge.captureDrawnEvidence();
            Map<String,Object> next = onlyText(bridge.describeUi());
            assertEquals(ResourceTextFixture.english(second), next.get("text"));
            assertEquals(0, next.get("color"), "Visible black is a value, not a missing-color default");
            assertTrue(bridge.drawnIntentReady());
            assertEquals(serialized, JsonCodec.encode(drawn), "A later draw cannot rewrite already returned evidence");
        }
    }

    @Test void equalGlyphsCannotRetroactivelyReplaceTheirFrozenProvenance() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Scene scene = new Scene();
            BitmapText text = bitmap(ResourceTextFixture.literal("4"), 0xFFFFFF); scene.add(text);
            UiBridge bridge = new UiBridge(() -> scene); bridge.captureDrawnEvidence();
            Map<String,Object> first = onlyText(bridge.describeUi());
            Object originalSource = sources(first).get("text");
            String sameGlyphs = TextProvenance.INSTANCE.onTextOperation("user", new String("4"));
            text.text(sameGlyphs);
            assertEquals(originalSource, sources(onlyText(bridge.describeUi())).get("text"));
            bridge.captureDrawnEvidence();
            Map<String,Object> next = onlyText(bridge.describeUi());
            assertEquals("4", next.get("text"));
            assertEquals("user", ((Map<?,?>)sources(next).get("text")).get("origin"));
            assertNotEquals(originalSource, sources(next).get("text"));
        }
    }

    @Test void renderedTextStylesAndTranslationAreFrozenAsOneCompletedDisplay() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Scene scene = new Scene();
            String blocking = ResourceTextFixture.source("windows.wndupgrade.blocking");
            String defense = ResourceTextFixture.source("items.stones.stoneofaugmentation$wndaugment.defense");
            MutableBlock block = new MutableBlock(twoStyles(blocking, defense)); scene.add(block);
            UiBridge bridge = new UiBridge(() -> scene);
            assertNoDisplayText(bridge.describeUi());
            bridge.captureDrawnEvidence();
            Map<String,Object> drawn = onlyText(bridge.describeUi());
            List<?> styles = (List<?>)drawn.get("styles");
            assertEquals(2, styles.size());
            assertEquals(ResourceTextFixture.english(blocking), ((Map<?,?>)styles.get(0)).get("text"));
            assertEquals(ResourceTextFixture.english(defense), ((Map<?,?>)styles.get(1)).get("text"));
            assertEquals(0xFF8800, ((Map<?,?>)styles.get(1)).get("color"));
            assertEquals("complete", PublicEnglishProjection.presentation(drawn).get("status"));

            block.setDisplayed(singleStyle(ResourceTextFixture.literal("Updated"), 0x44FF44, false));
            assertEquals(drawn, onlyText(bridge.describeUi()));
            assertFalse(bridge.drawnIntentReady());
            bridge.captureDrawnEvidence();
            Map<String,Object> next = onlyText(bridge.describeUi());
            assertEquals("Updated", next.get("text"));
            assertEquals(0x44FF44, next.get("color"));
            assertFalse(next.containsKey("styles"), "Old multi-color style runs must not survive a uniform new draw");
            assertTrue(bridge.drawnIntentReady());
        }
    }

    @Test void clippingChangesNeverMixAFullSourceWithAPartialDisplay() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Scene scene = new Scene();
            MutableBlock block = new MutableBlock(singleStyle(ResourceTextFixture.literal("Visible secret"), 0xFFFFFF, false));
            scene.add(block); UiBridge bridge = new UiBridge(() -> scene); bridge.captureDrawnEvidence();
            Map<String,Object> full = onlyText(bridge.describeUi());
            block.setDisplayed(singleStyle(ResourceTextFixture.literal("Visible"), 0xFF8800, true));
            assertEquals(full, onlyText(bridge.describeUi()));
            bridge.captureDrawnEvidence();
            Map<String,Object> clipped = onlyText(bridge.describeUi());
            assertEquals(true, clipped.get("clipped"));
            assertEquals(0xFF8800, clipped.get("color"));
            assertFalse(JsonCodec.encode(clipped).contains("secret"), "A new clipped draw cannot inherit the old complete source");
            assertEquals("Visible secret", full.get("text"));
        }
    }

    @Test void passiveLogAndCurrencyUpdatesNeitherStaleIntentNorDelayReadiness() throws Exception {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Scene scene = new Scene();
            GameLog log = groupWithoutConstructor(GameLog.class);
            CurrencyIndicator currency = groupWithoutConstructor(CurrencyIndicator.class);
            MutableBlock entry = new MutableBlock(singleStyle(ResourceTextFixture.literal("Earlier log"), 0xFFFFFF, false));
            BitmapText count = bitmap(ResourceTextFixture.literal("3"), 0xFFFF00);
            log.add(entry); currency.add(count); scene.add(log); scene.add(currency);
            UiBridge bridge = new UiBridge(() -> scene); bridge.captureDrawnEvidence();
            Map<String,Object> drawn = bridge.describeUi();
            String intent = bridge.intentSignature();
            entry.setDisplayed(singleStyle(ResourceTextFixture.literal("Later log"), 0xFF0000, false));
            count.text(ResourceTextFixture.literal("4")); count.hardlight(0x44CCFF);
            assertEquals(drawn, bridge.describeUi());
            assertEquals(intent, bridge.intentSignature());
            assertTrue(bridge.drawnIntentReady(), "Passive after-step notices must not stall continuous observations");
            bridge.captureDrawnEvidence();
            assertNotEquals(drawn, bridge.describeUi());
            assertEquals(intent, bridge.intentSignature());
            assertTrue(bridge.drawnIntentReady());
        }
    }

    @Test void annotatedColorPulseDoesNotStaleIntentButStillWaitsToPublishItsActualDraw() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Scene scene = new Scene(); String source = ResourceTextFixture.literal("Continue");
            MutableBlock block = new MutableBlock(singleStyle(source,0xFFFFFF,false));
            block.animatedColor(0xFFFFFF);scene.add(block);
            UiBridge bridge = new UiBridge(() -> scene);bridge.captureDrawnEvidence();
            Map<String,Object> first = onlyText(bridge.describeUi());String intent = bridge.intentSignature();
            block.setDisplayed(singleStyle(source,0xFFFF00,false));block.animatedColor(0xFFFF00);
            assertTrue(block.hasAnimatedColor());
            assertTrue(bridge.drawnIntentReady(),"A producer-annotated decorative pulse must not postpone input");
            assertEquals(intent,bridge.intentSignature());
            assertEquals(first,onlyText(bridge.describeUi()),"Even decorative colors remain tied to their own completed draw");
            bridge.captureDrawnEvidence();
            assertEquals(0xFFFF00,onlyText(bridge.describeUi()).get("color"));
            assertEquals(intent,bridge.intentSignature());

            block.hardlight(0xFF8800);block.setDisplayed(singleStyle(source,0xFF8800,false));
            assertFalse(block.hasAnimatedColor(),"Ordinary warning colors clear the producer's animation annotation");
            assertFalse(bridge.drawnIntentReady());assertNotEquals(intent,bridge.intentSignature());
            assertEquals(0xFFFF00,onlyText(bridge.describeUi()).get("color"));
            bridge.captureDrawnEvidence();
            assertEquals(0xFF8800,onlyText(bridge.describeUi()).get("color"));assertTrue(bridge.drawnIntentReady());

            block.animatedColor(0xFF8800);bridge.captureDrawnEvidence();
            String beforeTextChange=bridge.intentSignature();
            block.setDisplayed(singleStyle(ResourceTextFixture.literal("Delete"),0xFF8800,false));
            assertFalse(bridge.drawnIntentReady(),"The annotation exempts color, never new text or its provenance");
            assertNotEquals(beforeTextChange,bridge.intentSignature());
        }
    }

    @Test void animatedTextAnnotationResetsForContentResetColorAndPoolReuse() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            MutableBlock block = new MutableBlock(singleStyle(ResourceTextFixture.literal("Continue"),0xFFFFFF,false));
            block.animatedColor(0xFFFFFF);assertTrue(block.hasAnimatedColor());
            block.text("");assertFalse(block.hasAnimatedColor(),"The native text setter clears the old content's annotation");
            block.animatedColor(0xFFFFFF);block.resetColor();assertFalse(block.hasAnimatedColor());
            block.animatedColor(0xFFFFFF);block.revive();assertFalse(block.hasAnimatedColor(),"A pooled object cannot inherit a previous pulse policy");
        }
    }

    private static BitmapText bitmap(String value, int color) {
        BitmapText text = new BitmapText(value, null);
        text.camera = new Camera(0,0,100,100,1); text.x=text.y=10;text.width=40;text.height=8;text.hardlight(color);
        return text;
    }

    private static RenderedTextBlock.VisibleText singleStyle(String text, int color, boolean clipped) {
        return new RenderedTextBlock.VisibleText(text,clipped,true,
                Collections.singletonList(new RenderedTextBlock.VisibleStyle(text,color,0)));
    }
    private static RenderedTextBlock.VisibleText twoStyles(String first, String second) {
        return new RenderedTextBlock.VisibleText(ResourceTextFixture.join(first," ",second),false,true,
                Arrays.asList(new RenderedTextBlock.VisibleStyle(first,0xFFFFFF,0),
                        new RenderedTextBlock.VisibleStyle(second,0xFF8800,1)));
    }
    private static final class MutableBlock extends RenderedTextBlock {
        private VisibleText displayed;
        MutableBlock(VisibleText text) { super(6);camera=new Camera(0,0,100,100,1);setDisplayed(text); }
        void setDisplayed(VisibleText value) { displayed=value;text=value.text;Game.observer.onTextBound(this,text); }
        @Override public synchronized VisibleText visibleTextFragment() { return displayed; }
        @Override protected void layout() { }
    }
    @SuppressWarnings("unchecked") private static <T extends Group>T groupWithoutConstructor(Class<T> type) throws Exception {
        T group = FloatingAppearanceTest.allocate(type);
        group.exists=group.alive=group.active=group.visible=true;group.camera=new Camera(0,0,100,100,1);
        FloatingAppearanceTest.field(group,Group.class,"members",new ArrayList<Gizmo>());
        return group;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> onlyText(Map<String,Object> ui) {
        List<Map<String,Object>> text = new ArrayList<>();
        for (Object raw : (List<?>)ui.get("controls")) {
            Map<String,Object> node = (Map<String,Object>)raw;
            if ("text".equals(node.get("role"))) text.add(node);
        }
        assertEquals(1,text.size(),"Expected the one manually captured text fixture");return text.get(0);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> sources(Map<String,Object> node) {
        return (Map<String,Object>)node.get("text_sources");
    }
    private static void assertNoDisplayText(Map<String,Object> ui) {
        for(Object raw : (List<?>)ui.get("controls")) {
            Map<?,?> node = (Map<?,?>)raw;
            assertFalse(node.containsKey("text"));assertFalse(node.containsKey("color"));assertFalse(node.containsKey("styles"));
        }
    }
}
