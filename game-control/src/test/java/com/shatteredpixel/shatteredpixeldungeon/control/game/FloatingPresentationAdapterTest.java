package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Adapter tests stub only the existing renderer's pure complete-visibility predicate. */
class FloatingPresentationAdapterTest {
    @BeforeAll static void resources() throws Exception {
        GameSnapshotterTest.resourceOnlyRuntime();
        @SuppressWarnings("unchecked") Map<Object,SmartTexture> textures=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");
        if(!textures.containsKey(Assets.Effects.TEXT_ICONS)) {
            SmartTexture dimensions=allocate(SmartTexture.class);dimensions.width=70;dimensions.height=80;
            textures.put(Assets.Effects.TEXT_ICONS,dimensions);
        }
    }
    private static class DrawnFloating extends FloatingText {
        String completeVisible;
        @Override public String visibleCueText(){return completeVisible;}
    }
    @Test void completeVisibilityMustMatchTheExactStoredPublicTextBeforeMarkerIsAdded() throws Exception {
        Scene scene=new Scene();DrawnFloating floating=floating("闪避","闪避");scene.add(floating);
        UiBridge bridge=new UiBridge(()->scene);
        Map<?,?> node=nodes(bridge).get(0);assertEquals("floating_text",node.get("presentation"));assertEquals("dodged",node.get("text"));
        floating.completeVisible="闪";
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,bridge::describeUi);
        floating.completeVisible=null;
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,bridge::describeUi);
    }
    @Test void invisibleAndModalCoveredFloatingTextIsNotPublished() throws Exception {
        Scene scene=new Scene();DrawnFloating floating=floating("闪避","闪避");scene.add(floating);UiBridge bridge=new UiBridge(()->scene);
        floating.visible=false;assertTrue(nodes(bridge).isEmpty());floating.visible=true;
        Window modal=allocate(Window.class);init(modal);scene.add(modal);
        assertTrue(nodes(bridge).stream().noneMatch(n->n.containsKey("presentation")));
        scene.erase(modal);assertEquals("dodged",nodes(bridge).get(0).get("text"));
    }
    @Test void ordinaryStoredTextIsNeverGivenFloatingPresentation() throws Exception {
        Scene scene=new Scene();RenderedTextBlock ordinary=allocate(RenderedTextBlock.class);init(ordinary);
        set(ordinary,RenderedTextBlock.class,"text","闪避");scene.add(ordinary);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->new UiBridge(()->scene).describeUi());
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> nodes(UiBridge bridge){return (List<Map<String,Object>>)bridge.describeUi().get("controls");}
    private static DrawnFloating floating(String text,String visible)throws Exception {
        DrawnFloating value=allocate(DrawnFloating.class);init(value);set(value,RenderedTextBlock.class,"text",text);value.completeVisible=visible;return value;
    }
    private static void init(Group value)throws Exception {
        value.visible=value.active=value.exists=value.alive=true;set(value,Group.class,"members",new ArrayList<Gizmo>());set(value,Group.class,"length",0);
    }
    private static <T>T allocate(Class<T> type)throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    private static void set(Object value,Class<?> type,String name,Object content)throws Exception {Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(value,content);}
}
