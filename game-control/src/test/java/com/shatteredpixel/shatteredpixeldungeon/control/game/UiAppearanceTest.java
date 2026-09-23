package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameplayStatus;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UiAppearanceTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void namedTextWarningToneSurvivesBothViewsAndChangesCurrentIntent() {
        try(TextObservationFixture ignored=new TextObservationFixture()) {
            Scene scene=new Scene();BitmapText text=new BitmapText(ResourceTextFixture.literal("1"),null);
            text.camera=new Camera(0,0,100,100,1);text.x=10;text.y=10;text.width=8;text.height=8;
            scene.add(text);UiBridge bridge=new UiBridge(()->scene);
            String white=bridge.intentSignature();text.hardlight(0xFF8800);
            Map<String,Object> ui=UiDrawFixture.capture(bridge).describeUi();
            assertNotEquals(white,bridge.intentSignature());
            Map<?,?> node=(Map<?,?>)((List<?>)ui.get("controls")).get(0);
            assertFalse(node.containsKey("color"));assertEquals("orange",node.get("tone"));
            for(boolean full:Arrays.asList(false,true)) {
                Object frame=CompactProtocol.project(Collections.singletonMap("ui",ui),false,full);
                assertFalse(JsonCodec.encode(frame).contains("16746496"));assertTrue(JsonCodec.encode(frame).contains("orange"));
            }
            text.hardlight(0);assertFalse(((Map<?,?>)((List<?>)UiDrawFixture.capture(bridge).describeUi().get("controls")).get(0)).containsKey("color"));
        }
    }

    @Test void semanticWarningsStillExpireIntentAndSubjectReplacementRequiresANewCapture(){
        Scene scene=new Scene();SemanticButton button=new SemanticButton();scene.add(button);UiBridge bridge=new UiBridge(()->scene);
        UiDrawFixture.capture(bridge);String ordinary=bridge.intentSignature();assertTrue(bridge.drawnIntentReady());
        button.warning=true;assertNotEquals(ordinary,bridge.intentSignature());assertFalse(bridge.drawnIntentReady());
        UiDrawFixture.capture(bridge);assertTrue(bridge.drawnIntentReady());
        button.subject=new Object();assertFalse(bridge.drawnIntentReady(),"Equal labels and stats cannot reuse another native subject's capture");
        UiDrawFixture.capture(bridge);assertTrue(bridge.drawnIntentReady());
    }

    @Test void emptyBitmapAtViewportEdgeIsNotPartiallyDisplayedText(){
        Scene scene=new Scene();BitmapText empty=new BitmapText("",null);empty.camera=new Camera(0,0,100,100,1);empty.x=95;empty.y=10;empty.width=10;empty.height=8;scene.add(empty);
        UiBridge bridge=new UiBridge(()->scene);Map<String,Object> ui=UiDrawFixture.capture(bridge).describeUi();
        assertFalse(JsonCodec.encode(ui).contains("clipped"));assertFalse(JsonCodec.encode(ui).contains("Partially displayed text"));
        empty.text(ResourceTextFixture.literal("A real warning"));
        assertTrue(JsonCodec.encode(UiDrawFixture.capture(bridge).describeUi()).contains("clipped"));
    }

    private static final class SemanticButton extends Button implements GameplayStatus {
        Object subject=new Object();boolean warning;
        @Override protected void createChildren() { }
        @Override public Object gameplaySubject(){return subject;}
        @Override public Map<String,Object> gameplayStatus(){return Map.of("shown",Map.of("flags",warning?List.of("low_durability"):List.of()));}
    }

    @Test void visibleWordStyleDoesNotFlattenDifferentColors() {
        List<RenderedTextBlock.VisibleStyle> colors=Arrays.asList(
                new RenderedTextBlock.VisibleStyle(ResourceTextFixture.literal("Health"),0xFFFFFF,0),
                new RenderedTextBlock.VisibleStyle(ResourceTextFixture.literal("3"),0xFF0000,1));
        RenderedTextBlock.VisibleText fragment=new RenderedTextBlock.VisibleText("Health 3",false,true,colors);
        Map<String,Object> data=PublicEnglishProjection.copy(fragment.styleData());
        assertFalse(data.containsKey("color"));assertEquals(2,((List<?>)data.get("styles")).size());
        assertEquals("3",((Map<?,?>)((List<?>)data.get("styles")).get(1)).get("text"));
    }

    @Test void markupColorSegmentsSurviveFrozenRoundTripWithoutSourceLanguageOffsets() {
        String source=ResourceTextFixture.literal("Normal _warning_ normal");
        String styled=TextProvenance.INSTANCE.onTextOperation("markup_segment","warning",source,1,3);
        Map<String,Object> frozen=PublicEnglishProjection.freeze(Collections.singletonMap("text",styled));
        Map<String,Object> restored=PublicEnglishProjection.copy(JsonCodec.decode(JsonCodec.encode(frozen)));
        assertEquals("warning",restored.get("text"));
        assertEquals("complete",PublicEnglishProjection.presentation(restored).get("status"));
        assertFalse(JsonCodec.encode(CompactProtocol.project(restored,false)).contains("text_sources"));
        assertTrue(JsonCodec.encode(CompactProtocol.project(restored,true)).contains("markup_segment"));
        String invalid=TextProvenance.INSTANCE.onTextOperation("markup_segment","hidden",source,1,3);
        assertEquals("partial",PublicEnglishProjection.presentation(PublicEnglishProjection.copy(Collections.singletonMap("text",invalid))).get("status"));
    }
}
