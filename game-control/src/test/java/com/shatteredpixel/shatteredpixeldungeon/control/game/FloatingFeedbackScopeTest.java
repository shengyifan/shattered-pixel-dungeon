package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;
import static org.junit.jupiter.api.Assertions.*;

/** Tests projection of collector-certified native feedback; it never manufactures collector/FOV acceptance. */
class FloatingFeedbackScopeTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void modalScopeRetainsCertifiedWorldFeedbackButNotOrdinaryBackgroundUi()throws Exception {
        try(TextObservationFixture ignored=new TextObservationFixture()){
            GameScene scene=group(GameScene.class);
            FloatingText floating=group(FloatingText.class);
            FloatingAppearanceTest.field(floating,FloatingText.class,"displayedText",new RenderedTextBlock.VisibleText(ResourceTextFixture.literal("4"),false,true));
            FloatingAppearanceTest.field(floating,FloatingText.class,"displayedAppearance",map("cell",42,"tone","negative","icon",map("symbol","physical_damage")));
            scene.add(floating);
            BitmapText background=new BitmapText(ResourceTextFixture.literal("Background UI only"),null);
            background.camera=new Camera(0,0,100,100,1);background.x=10;background.y=10;background.width=60;background.height=8;scene.add(background);
            Window modal=group(Window.class);scene.add(modal);
            UiBridge bridge=new UiBridge(()->scene);bridge.captureDrawnEvidence();
            Map<String,Object> observation=GameplayObservation.merge(map("ui",bridge.frozenUi()));
            Map<String,Object> rendered=PublicEnglishProjection.copy(observation);
            List<?> feedback=(List<?>)((Map<?,?>)rendered.get("ui")).get("feedback");
            assertEquals(1,feedback.size());Map<?,?> entry=(Map<?,?>)feedback.get(0);
            assertEquals("floating",entry.get("kind"));assertEquals("4",entry.get("text"));assertEquals(42,entry.get("cell"));
            assertFalse(rendered.toString().contains("Background UI only"));
            floating.visible=false;bridge.captureDrawnEvidence();
            Map<?,?> hidden=(Map<?,?>)GameplayObservation.merge(map("ui",bridge.frozenUi())).get("ui");
            assertFalse(hidden.containsKey("feedback"),"An old certified fragment cannot outlive its native visible owner");
        }
    }

    private static <T extends Group>T group(Class<T> type)throws Exception {
        T value=FloatingAppearanceTest.allocate(type);value.exists=value.visible=value.active=value.alive=true;
        FloatingAppearanceTest.field(value,Group.class,"members",new ArrayList<Gizmo>());return value;
    }
}
