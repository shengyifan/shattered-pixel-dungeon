package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.watabou.noosa.*;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FloatingWorldCueTest {
    private static Map<Object,SmartTexture> textures;
    private static SmartTexture previousTexture;
    @BeforeAll @SuppressWarnings("unchecked") static void syntheticTexture() throws Exception {
        Field cache=TextureCache.class.getDeclaredField("all");cache.setAccessible(true);
        textures=(Map<Object,SmartTexture>)cache.get(null);previousTexture=textures.get(Assets.Effects.TEXT_ICONS);
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        SmartTexture texture=(SmartTexture)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),SmartTexture.class);
        texture.width=70;texture.height=80;textures.put(Assets.Effects.TEXT_ICONS,texture);
    }
    @AfterAll static void restoreTexture(){
        if(previousTexture==null)textures.remove(Assets.Effects.TEXT_ICONS);else textures.put(Assets.Effects.TEXT_ICONS,previousTexture);
    }
    @Test void nativeFloatingAnchorSurvivesUiCameraWhileOrdinaryUiProducersStayExcluded() throws Exception {
        GameplayVisualTraversalTest.Fixture f=new GameplayVisualTraversalTest.Fixture();
        Group overlay=new Group();overlay.camera=new Camera(0,0,16,16,1);f.scene.add(overlay);
        FloatingText text=new FloatingText(){
            @Override public int observationCell(){return 22;}
            @Override public String visibleCueText(){return "3...";}
            @Override public String visibleCueText(boolean knownAnchor){return knownAnchor?"3...":null;}
            @Override public void observeGameplayVisuals(){f.collector.floatingTextDrawn(this,observationCell());}
        };
        overlay.add(text);
        overlay.add(new Visual(0,0,4,4){@Override public void observeGameplayVisuals(){fail("A UI camera is not a world-source authorization");}});
        f.traverse();assertEquals(Collections.singletonList(new VisualCue("bomb_countdown_3",22)),f.cues());
        f.clear();f.level.heroFOV[22]=false;f.traverse();assertTrue(f.cues().isEmpty());
        f.level.heroFOV[22]=true;overlay.visible=false;f.traverse();assertTrue(f.cues().isEmpty());
    }
    @Test void fontBackedGlyphDoesNotRequireImageTextureOrIntersectItsAnchorCell() throws Exception {
        GameplayVisualTraversalTest.Fixture f=new GameplayVisualTraversalTest.Fixture();
        FloatingText text=new FloatingText(){@Override public int observationCell(){return 22;}};f.scene.add(text);
        boolean[] fontReady={true};
        RenderedText word=new RenderedText(){@Override public boolean hasRenderableText(){return fontReady[0];}};
        word.x=1000;word.y=-1000;word.width=8;word.height=8;text.add(word);
        assertNull(word.texture,"The real font-backed RenderedText type has no Image.texture");
        Method eligible=GameplayVisualTraversalTest.method("floatingPart",FloatingText.class,Visual.class);
        assertEquals(true,eligible.invoke(f.collector,text,word));
        f.level.heroFOV[22]=false;assertEquals(false,eligible.invoke(f.collector,text,word));
        f.level.heroFOV[22]=true;fontReady[0]=false;assertEquals(false,eligible.invoke(f.collector,text,word));
        fontReady[0]=true;word.alpha(0);assertEquals(false,eligible.invoke(f.collector,text,word));
        word.alpha(1);text.erase(word);assertEquals(false,eligible.invoke(f.collector,text,word));
        Image emptyIcon=new Image();emptyIcon.width=emptyIcon.height=8;text.add(emptyIcon);
        assertEquals(false,eligible.invoke(f.collector,text,emptyIcon),"Non-font icons still require an actual texture");
    }
}
