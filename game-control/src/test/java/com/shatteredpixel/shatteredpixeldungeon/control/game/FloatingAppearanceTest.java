package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameplayIcons;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class FloatingAppearanceTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    private Map<Object,SmartTexture> textures;
    private SmartTexture previousTexture,texture;

    @BeforeEach @SuppressWarnings("unchecked") void installTextIconTexture() throws Exception {
        textures=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");
        previousTexture=textures.get(Assets.Effects.TEXT_ICONS);
        texture=allocate(SmartTexture.class);texture.width=70;texture.height=80;
        // FloatingText's static iconFilm must find this before either test initializes that class.
        textures.put(Assets.Effects.TEXT_ICONS,texture);
    }

    @AfterEach void restoreTextIconTexture() {
        if(textures!=null) {
            if(previousTexture==null)textures.remove(Assets.Effects.TEXT_ICONS);
            else textures.put(Assets.Effects.TEXT_ICONS,previousTexture);
        }
    }
    static class Word extends RenderedText {
        @Override public String text(){return "4";}
        @Override public boolean hasRenderableText(){return true;}
    }
    @Test void certifiedWorldAnchorPreservesOffscreenGlyphsWithoutGrantingUnanchoredOrHiddenText() throws Exception {
        FloatingText text=allocate(FloatingText.class);text.exists=text.alive=text.visible=text.active=true;
        field(text,Group.class,"members",new ArrayList<Gizmo>());
        field(text,RenderedTextBlock.class,"text","4");field(text,FloatingText.class,"gameplayTone",Collections.emptyMap());
        Word word=new Word();word.x=500;word.y=500;word.width=8;word.height=8;
        text.camera=new Camera(0,0,100,100,1);text.add(word);
        field(text,RenderedTextBlock.class,"words",new ArrayList<>(Collections.singletonList(word)));
        field(text,FloatingText.class,"observationCell",6);
        Method capture=FloatingText.class.getDeclaredMethod("recordDisplayedAppearance",Predicate.class,boolean.class);capture.setAccessible(true);
        capture.invoke(text,(Predicate<Visual>)v->true,false);assertNull(text.displayedText());
        capture.invoke(text,(Predicate<Visual>)v->true,true);
        assertEquals("4",text.displayedText().text);assertFalse(text.displayedText().clipped);assertEquals(6,text.displayedAppearance().get("cell"));
        assertNull(text.visibleCueText());assertEquals("4",text.visibleCueText(true));
        field(text,FloatingText.class,"observationCell",-1);
        capture.invoke(text,(Predicate<Visual>)v->true,true);assertNull(text.displayedText());assertNull(text.visibleCueText(true));
        field(text,FloatingText.class,"observationCell",6);word.visible=false;
        capture.invoke(text,(Predicate<Visual>)v->true,true);assertNull(text.displayedText());
        word.visible=true;word.angle=15;
        capture.invoke(text,(Predicate<Visual>)v->true,true);assertNull(text.displayedText());
        word.angle=0;word.am=0;
        capture.invoke(text,(Predicate<Visual>)v->true,true);assertNull(text.displayedText());
    }
    @Test void semanticFeedbackIconsAndCellAreIndependentAndReusePreservesNewOccurrence()throws Exception {
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
            field(text,FloatingText.class,"gameplayTone",GameplayIcons.feedback(0xFF8800));
            Method capture=FloatingText.class.getDeclaredMethod("recordDisplayedAppearance",Predicate.class,boolean.class);capture.setAccessible(true);
            Method publish=FloatingText.class.getDeclaredMethod("publishDisplayedText",String.class,Object.class,int.class);publish.setAccessible(true);
            Object level=new Object();
            capture.invoke(text,(Predicate<Visual>)v->true,true);
            assertEquals("warning",text.displayedAppearance().get("tone"));
            assertFalse(text.displayedAppearance().containsKey("color"));assertFalse(text.displayedAppearance().containsKey("styles"));assertEquals(6,text.displayedAppearance().get("cell"));
            assertEquals("healing",((Map<?,?>)text.displayedAppearance().get("icon")).get("symbol"));
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
            Game.observer=original;
        }
    }
    static <T>T allocate(Class<T> type)throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));
    }
    static void field(Object value,Class<?> owner,String name,Object data)throws Exception {Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(value,data);}
}
