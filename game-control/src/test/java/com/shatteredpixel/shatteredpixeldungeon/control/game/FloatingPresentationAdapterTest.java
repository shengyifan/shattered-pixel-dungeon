package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
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

/** Supplies completed-draw cache records directly; real native drawing has a separate fixture. */
class FloatingPresentationAdapterTest {
    @BeforeAll static void resources() throws Exception {
        GameSnapshotterTest.resourceOnlyRuntime();
        @SuppressWarnings("unchecked") Map<Object,SmartTexture> textures=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");
        if(!textures.containsKey(Assets.Effects.TEXT_ICONS)) {
            SmartTexture dimensions=allocate(SmartTexture.class);dimensions.width=70;dimensions.height=80;
            textures.put(Assets.Effects.TEXT_ICONS,dimensions);
        }
    }
    private static class Choice extends Button {
        @Override protected void createChildren() { }
        @Override protected String hoverText(){return "Visible choice";}
        @Override protected void onClick(){throw new AssertionError("A query must not execute callbacks");}
    }
    @Test void unrenderedNumericKnownAndAmbiguousTextAllLeaveNoNodeOrIdSideChannel() throws Exception {
        Scene empty=new Scene();empty.add(new Choice());UiBridge baseline=new UiBridge(()->empty);
        for(String raw:List.of("314159","战士","闪避","夺命印记")) {
            Scene scene=new Scene();Group container=new Group();scene.add(container);
            container.add(floating(raw,null));container.add(floating(raw+"隐藏变化",null));scene.add(new Choice());
            UiBridge bridge=new UiBridge(()->scene);
            assertEquals(baseline.describeUi(),bridge.describeUi(),raw);
            assertEquals(baseline.describeActions(),bridge.describeActions(),raw);
            assertEquals(baseline.contextSignature(),bridge.contextSignature(),raw);
        }
    }
    @Test void publishedTextComesOnlyFromTheCompletedDrawCacheAndDoesNotRegenerateIt() throws Exception {
        Scene scene=new Scene();FloatingText floating=floating("private later value",new RenderedTextBlock.VisibleText(ResourceTextFixture.source("actors.char.def_verb"),false,true));
        scene.add(floating);UiBridge bridge=new UiBridge(()->scene);
        Map<?,?> node=nodes(bridge).get(0);assertEquals("floating_text",node.get("presentation"));assertEquals("dodged",node.get("text"));
        for(int i=0;i<10;i++)assertEquals(node,nodes(bridge).get(0));
        assertEquals("private later value",floating.text());assertEquals("闪避",floating.displayedText().text);
    }
    @Test void partialDisplayedWordsHaveIdenticalOutputAcrossDifferentUndrawnTails() throws Exception {
        Scene a=new Scene(),b=new Scene();
        a.add(floating("Visible secret A",new RenderedTextBlock.VisibleText("Visible",true,true)));
        b.add(floating("Visible secret B",new RenderedTextBlock.VisibleText("Visible",true,true)));
        Map<String,Object> first=new UiBridge(()->a).describeUi(),second=new UiBridge(()->b).describeUi();
        assertEquals(first,second);assertTrue(first.toString().contains("clipped=true"));assertFalse(first.toString().contains("secret"));
        assertFalse(first.toString().contains("presentation"));
    }
    @Test void incompleteChineseIsAnHonestPartialAndEmptyOrInvisibleCacheAllocatesNoHandle() throws Exception {
        Scene scene=new Scene();FloatingText floating=floating("闪避隐藏全文",new RenderedTextBlock.VisibleText("闪",true,true));scene.add(floating);
        UiBridge bridge=new UiBridge(()->scene);assertEquals("Partially displayed text",nodes(bridge).get(0).get("text"));
        for(RenderedTextBlock.VisibleText empty:List.of(new RenderedTextBlock.VisibleText("",true,true),new RenderedTextBlock.VisibleText("hidden",true,false))) {
            set(floating,FloatingText.class,"displayedText",empty);assertTrue(nodes(bridge).isEmpty());
        }
    }
    @Test void invisibleAndModalCoveredFloatingTextIsNotPublishedEvenWithAnEarlierCache() throws Exception {
        Scene scene=new Scene();FloatingText floating=floating("闪避",new RenderedTextBlock.VisibleText(ResourceTextFixture.source("actors.char.def_verb"),false,true));scene.add(floating);UiBridge bridge=new UiBridge(()->scene);
        floating.visible=false;assertTrue(nodes(bridge).isEmpty());floating.visible=true;
        Window modal=allocate(Window.class);init(modal);scene.add(modal);
        assertTrue(nodes(bridge).stream().noneMatch(n->n.containsKey("presentation")));
        scene.erase(modal);assertEquals("dodged",nodes(bridge).get(0).get("text"));
    }
    @Test void ordinaryStoredTextIsNeverGivenFloatingPresentation() throws Exception {
        Scene scene=new Scene();RenderedTextBlock ordinary=ResourceTextFixture.laidOut("闪避");scene.add(ordinary);
        Map<String,Object> observed=new UiBridge(()->scene).describeUi();
        assertEquals("partial",PublicEnglishProjection.presentation(observed).get("status"));
        assertFalse(observed.toString().contains("dodged"));
        assertFalse(observed.toString().contains("floating_text"));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> nodes(UiBridge bridge){return (List<Map<String,Object>>)bridge.describeUi().get("controls");}
    private static FloatingText floating(String text,RenderedTextBlock.VisibleText visible)throws Exception {
        FloatingText value=allocate(FloatingText.class);init(value);set(value,RenderedTextBlock.class,"text",text);set(value,FloatingText.class,"displayedText",visible);return value;
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
