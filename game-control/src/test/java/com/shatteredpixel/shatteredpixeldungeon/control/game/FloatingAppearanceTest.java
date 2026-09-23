package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class FloatingAppearanceTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    static class Word extends RenderedText {
        @Override public String text(){return "4";}
        @Override public boolean hasRenderableText(){return true;}
    }
    @Test void drawnStylesIconsAndCellAreIndependentAndReusePreservesNewOccurrence()throws Exception {
        Map<Object,SmartTexture> textures=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");
        SmartTexture previous=textures.get(Assets.Effects.TEXT_ICONS),texture=allocate(SmartTexture.class);
        texture.width=70;texture.height=80;textures.put(Assets.Effects.TEXT_ICONS,texture);
        RuntimeObserver original=Game.observer;
        List<Map<String,Object>> events=new ArrayList<>();
        Game.observer=new RuntimeObserver(){@Override public void onFloatingText(String run,Object level,int depth,String text,boolean clipped,Map<String,Object> appearance){events.add(new LinkedHashMap<>(appearance));}};
        try {
            FloatingText text=allocate(FloatingText.class);text.exists=text.alive=text.visible=text.active=true;
            field(text,Group.class,"members",new ArrayList<Gizmo>());
            field(text,RenderedTextBlock.class,"text","4");
            Word word=new Word();word.x=8;word.y=8;word.width=8;word.height=8;word.hardlight(0xFF8800);
            text.camera=new Camera(0,0,100,100,1);text.add(word);
            field(text,RenderedTextBlock.class,"words",new ArrayList<>(Collections.singletonList(word)));
            Image icon=new Image();icon.texture=texture;icon.x=20;icon.y=8;icon.width=7;icon.height=8;text.add(icon);
            field(text,FloatingText.class,"icon",icon);field(text,FloatingText.class,"displayedIconIndex",18);
            field(text,FloatingText.class,"observationCell",6);
            Method capture=FloatingText.class.getDeclaredMethod("recordDisplayedAppearance",Predicate.class,boolean.class);capture.setAccessible(true);
            Method publish=FloatingText.class.getDeclaredMethod("publishDisplayedText",String.class,Object.class,int.class);publish.setAccessible(true);
            Object level=new Object();
            capture.invoke(text,(Predicate<Visual>)v->true,true);
            assertEquals(0xFF8800,text.displayedAppearance().get("color"));assertEquals(6,text.displayedAppearance().get("cell"));
            assertEquals(18,((Map<?,?>)text.displayedAppearance().get("icon")).get("index"));
            publish.invoke(text,"fixture",level,1);publish.invoke(text,"fixture",level,1);assertEquals(1,events.size());
            capture.invoke(text,(Predicate<Visual>)v->v!=icon,false);
            assertFalse(text.displayedAppearance().containsKey("icon"));assertFalse(text.displayedAppearance().containsKey("cell"));
            assertEquals("4",text.displayedText().text);
            capture.invoke(text,(Predicate<Visual>)v->v==icon,true);
            assertNull(text.displayedText());assertTrue(text.displayedAppearance().containsKey("icon"));
            text.revive();assertNull(text.displayedText());assertNull(text.displayedAppearance());assertEquals(-1,text.observationCell());
            capture.invoke(text,(Predicate<Visual>)v->true,false);
            assertFalse(text.displayedAppearance().containsKey("icon"),"A pooled old image is not a new icon observation");
            publish.invoke(text,"fixture",level,1);assertEquals(2,events.size());
        }finally {
            Game.observer=original;if(previous==null)textures.remove(Assets.Effects.TEXT_ICONS);else textures.put(Assets.Effects.TEXT_ICONS,previous);
        }
    }
    static <T>T allocate(Class<T> type)throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));
    }
    static void field(Object value,Class<?> owner,String name,Object data)throws Exception {Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(value,data);}
}
