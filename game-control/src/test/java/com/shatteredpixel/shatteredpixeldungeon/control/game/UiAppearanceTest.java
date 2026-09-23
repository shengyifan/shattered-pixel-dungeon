package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UiAppearanceTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void warningColorSurvivesAllViewsAndChangesTheCurrentIntent() {
        try(TextObservationFixture ignored=new TextObservationFixture()) {
            Scene scene=new Scene();BitmapText text=new BitmapText(ResourceTextFixture.literal("1"),null);
            text.camera=new Camera(0,0,100,100,1);text.x=10;text.y=10;text.width=8;text.height=8;
            scene.add(text);UiBridge bridge=new UiBridge(()->scene);
            String white=bridge.intentSignature();text.hardlight(0xFF8800);
            Map<String,Object> ui=UiDrawFixture.capture(bridge).describeUi();
            assertNotEquals(white,bridge.intentSignature());
            Map<?,?> node=(Map<?,?>)((List<?>)ui.get("controls")).get(0);
            assertEquals(0xFF8800,node.get("color"));
            for(boolean full:Arrays.asList(false,true)) {
                Object frame=CompactProtocol.project(Collections.singletonMap("ui",ui),false,full);
                assertTrue(JsonCodec.encode(frame).contains("16746496"));
            }
            text.hardlight(0);assertEquals(0,((Map<?,?>)((List<?>)UiDrawFixture.capture(bridge).describeUi().get("controls")).get(0)).get("color"));
        }
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
