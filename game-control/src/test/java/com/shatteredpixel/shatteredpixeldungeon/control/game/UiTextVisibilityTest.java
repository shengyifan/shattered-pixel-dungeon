package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.watabou.noosa.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UiTextVisibilityTest {
    @org.junit.jupiter.api.BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    @Test void clippedBitmapCannotExposeItsSourceOrInvalidateIntentThroughItsHiddenSuffix() {
        try(TextObservationFixture ignored=new TextObservationFixture()) {
        Scene scene=new Scene();
        BitmapText text=new BitmapText();
        text.camera=new Camera(0,0,100,100,1); text.x=95;text.y=10;text.width=100;text.height=10;
        text.text(ResourceTextFixture.source("windows.wndupgrade.blocking"));scene.add(text);
        UiBridge ui=new UiBridge(()->scene);
        String signature=ui.intentSignature();
        Map<String,Object> initial=ui.describeUi();
        assertTrue(initial.toString().contains("clipped_text"));
        assertFalse(initial.toString().contains("windows.wndupgrade.blocking"));
        text.text(ResourceTextFixture.source("items.stones.stoneofaugmentation$wndaugment.defense"));
        assertEquals(signature,ui.intentSignature());assertEquals(initial,ui.describeUi());
        text.x=10;text.width=30;
        assertTrue(ui.describeUi().toString().contains("Defense"));
        assertNotEquals(signature,ui.intentSignature());
        }
    }

    @Test void transparentBitmapHasNoPublicTextOrSource() {
        BitmapText text=new BitmapText(ResourceTextFixture.source("windows.wndupgrade.blocking"),null);
        text.camera=new Camera(0,0,100,100,1);text.x=10;text.y=10;text.width=30;text.height=10;text.alpha(0);
        Scene scene=new Scene();scene.add(text);
        Map<String,Object> hidden=new UiBridge(()->scene).describeUi();
        assertFalse(hidden.toString().contains("windows.wndupgrade.blocking"));
        assertFalse(hidden.toString().contains("Blocking"));
        assertTrue(((List<?>)hidden.get("controls")).isEmpty());
    }

    @Test void styledButtonCannotPublishItsUndrawnCachedBodyThroughAnAggregateShortcut() throws Exception {
        StyledButton button=allocate(StyledButton.class);
        button.exists=button.alive=button.active=button.visible=true;
        field(button,Group.class,"members",new ArrayList<Gizmo>());
        RenderedTextBlock body=ResourceTextFixture.laidOut(ResourceTextFixture.source("windows.wndupgrade.blocking"));
        field(button,StyledButton.class,"text",body);button.add(body);body.visible=false;
        Scene scene=new Scene();scene.add(button);UiBridge ui=new UiBridge(()->scene);
        Map<String,Object> hidden=ui.describeUi();
        assertFalse(hidden.toString().contains("Blocking"));
        assertFalse(hidden.toString().contains("windows.wndupgrade.blocking"));
        body.visible=true;
        Map<String,Object> shown=ui.describeUi();
        assertTrue(shown.toString().contains("Blocking"));
        assertTrue(shown.toString().contains("windows.wndupgrade.blocking"));
    }
    private static <T>T allocate(Class<T> type)throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    private static void field(Object target,Class<?> type,String name,Object value)throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
