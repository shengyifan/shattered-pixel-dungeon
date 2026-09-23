package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.watabou.noosa.Camera;
import com.watabou.noosa.RenderedText;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Group.draw traversal and laid-out word geometry, without GL, textures or a game profile. */
class DisabledTextVisibilityTest {
    @org.junit.jupiter.api.BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    @Test void disabledButDrawnButtonKeepsItsCompleteLabelAndSourceWithoutBecomingClickable() throws Exception {
        try(TextObservationFixture ignored=new TextObservationFixture()) {
            Label label=new Label(ResourceTextFixture.source("windows.wndupgrade.upgrade"));
            Choice button=choice(label);Scene scene=new Scene();scene.add(button);
            button.enable(false);
            button.draw();
            assertEquals(1,label.word.draws);assertEquals(0.3f,label.word.am);
            RenderedTextBlock.VisibleText visible=label.visibleTextFragment();
            assertTrue(visible.visible);assertFalse(visible.clipped);
            Map<String,Object> rendered=TextProvenance.INSTANCE.render(TextProvenance.INSTANCE.capture(label,visible.text,visible.clipped));
            assertEquals("Upgrade",rendered.get("text"));assertEquals("complete",rendered.get("translation_status"));
            UiBridge bridge=new UiBridge(()->scene);Map<String,Object> ui=UiDrawFixture.capture(bridge).describeUi();
            Map<String,Object> control=nodes(ui).stream().filter(node->"button".equals(node.get("role"))).findFirst().orElseThrow();
            assertEquals(false,control.get("enabled"));assertEquals("Upgrade",control.get("text"));
            assertFalse(Boolean.TRUE.equals(control.get("clipped")));
            assertTrue(control.get("text_sources").toString().contains("windows.wndupgrade.upgrade"));
            assertTrue(bridge.describeActions().stream().noneMatch(action->control.get("id").equals(action.get("control"))));
        }
    }

    @Test void geometricClippingStillRedactsTheCompleteSourceForADisabledLabel() throws Exception {
        try(TextObservationFixture ignored=new TextObservationFixture()) {
            Label label=new Label(ResourceTextFixture.source("windows.wndupgrade.upgrade"));
            Choice button=choice(label);Scene scene=new Scene();scene.add(button);button.enable(false);
            label.word.x=95;button.draw();assertEquals(1,label.word.draws);
            RenderedTextBlock.VisibleText visible=label.visibleTextFragment();
            assertTrue(visible.visible);assertTrue(visible.clipped);
            Map<String,Object> rendered=TextProvenance.INSTANCE.render(TextProvenance.INSTANCE.capture(label,visible.text,true));
            assertEquals("partial",rendered.get("translation_status"));assertNull(rendered.get("source"));
            Map<String,Object> ui=UiDrawFixture.capture(new UiBridge(()->scene)).describeUi();
            assertTrue(nodes(ui).stream().anyMatch(node->Boolean.TRUE.equals(node.get("clipped"))));
            assertFalse(JsonCodec.encode(ui).contains("windows.wndupgrade.upgrade"));
        }
    }

    @Test void drawAndVisibilityUseExistsAndVisibleRatherThanActorActivityOrAliveFlags() {
        Label label=new Label(ResourceTextFixture.source("windows.wndupgrade.upgrade"));
        label.active=false;label.alive=false;label.word.active=false;label.word.alive=false;
        label.draw();assertEquals(1,label.word.draws);
        assertTrue(label.visibleTextFragment().visible);assertFalse(label.visibleTextFragment().clipped);
        label.word.visible=false;label.draw();assertEquals(1,label.word.draws);
        assertFalse(label.visibleTextFragment().visible);
        label.word.visible=true;label.word.exists=false;label.draw();assertEquals(1,label.word.draws);
        assertFalse(label.visibleTextFragment().visible);
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> nodes(Map<String,Object> ui){return (List<Map<String,Object>>)ui.get("controls");}
    private static Choice choice(Label label)throws Exception{
        Class<?> unsafe=Class.forName("jdk.internal.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        Choice result=(Choice)unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),Choice.class);
        result.initialize(label);return result;
    }
    private static final class Choice extends StyledButton {
        private Choice(){super(null,"");}
        void initialize(Label label){exists=alive=active=visible=true;members=new ArrayList<>();length=0;text=label;add(label);}
        @Override protected void onClick(){throw new AssertionError("Inspection may not activate a disabled control");}
    }
    private static final class Label extends RenderedTextBlock {
        final Word word;
        Label(String value){
            super(6);setHightlighting(false);text=value;camera=new Camera(0,0,100,100,1);
            word=new Word(value);words.add(word);add(word);
        }
        @Override protected void layout(){}
    }
    private static final class Word extends RenderedText {
        final String value;int draws;
        Word(String value){this.value=value;x=10;y=10;width=30;height=8;}
        @Override public String text(){return value;}
        @Override public boolean hasRenderableText(){return value!=null&&!value.isEmpty();}
        @Override public void draw(){draws++;}
    }
}
