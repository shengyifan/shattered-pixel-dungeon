package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.EyeSprite;
import com.watabou.noosa.MovieClip;
import com.watabou.noosa.VisualCue;
import com.watabou.utils.PointF;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CharacterAppearanceCueTest {
    @Test void hiddenIconPauseDarknessOpacityAndScaleComeFromRendererWithoutAnActor()throws Exception{
        CharSprite sprite=uninitializedSprite();Method project=CharSprite.class.getDeclaredMethod("renderedAppearanceCue",int.class);project.setAccessible(true);
        sprite.scale=new PointF(1,1);sprite.resetColor();assertNull(sprite.ch);
        VisualCue plain=(VisualCue)project.invoke(sprite,12);
        assertEquals(Boolean.FALSE,plain.appearance.get("flip_horizontal"));
        assertEquals(Boolean.FALSE,plain.appearance.get("flip_vertical"));
        sprite.paused=true;sprite.brightness(.4f);sprite.alpha(.6f);sprite.scale.set(1.25f);
        VisualCue cue=(VisualCue)project.invoke(sprite,12);
        assertEquals("sprite_state_appearance",cue.kind);assertEquals(.6f,cue.opacity);
        assertEquals(Boolean.TRUE,cue.appearance.get("paused"));
        assertEquals(Arrays.asList(1.25f,1.25f),cue.appearance.get("scale"));
        assertEquals(Arrays.asList(.4f,.4f,.4f),((Map<?,?>)cue.appearance.get("tint")).get("multiply"));
        assertNull(project.invoke(sprite,-1));sprite.alpha(0);assertNull(project.invoke(sprite,12));
    }

    @Test void currentCosmeticGlowPulseKeepsExactTintAlongsideStableStyle()throws Exception{
        CharSprite sprite=uninitializedSprite();sprite.scale=new PointF(1,1);sprite.resetColor();
        GlowBlock glow=new GlowBlock(sprite);
        Field field=CharSprite.class.getDeclaredField("glowBlock");field.setAccessible(true);field.set(sprite,glow);
        Method project=CharSprite.class.getDeclaredMethod("renderedAppearanceCue",int.class);project.setAccessible(true);
        sprite.tint(1.33f,1.33f,.83f,.4f);VisualCue first=(VisualCue)project.invoke(sprite,12);
        sprite.tint(1.33f,1.33f,.83f,.6f);VisualCue second=(VisualCue)project.invoke(sprite,12);
        assertNotEquals(first,second);assertEquals("golden_glow",first.appearance.get("tint_style"));
        assertEquals(first.appearance.get("tint_style"),second.appearance.get("tint_style"));
        sprite.resetColor();VisualCue plain=(VisualCue)project.invoke(sprite,12);
        assertFalse(plain.appearance.containsKey("tint"));
        assertFalse(plain.appearance.containsKey("tint_style"),"A controller alone is not evidence of an applied visible tint");
    }

    @Test void oppositeFacingChargedEyesDifferWithoutReadingAnAimOrActor()throws Exception{
        EyeSprite eye=uninitializedSprite(EyeSprite.class);eye.scale=new PointF(1,1);eye.resetColor();
        MovieClip.Animation charging=new MovieClip.Animation(12,true);
        Field declared=EyeSprite.class.getDeclaredField("charging");declared.setAccessible(true);declared.set(eye,charging);
        Field current=MovieClip.class.getDeclaredField("curAnim");current.setAccessible(true);current.set(eye,charging);
        Method posture=EyeSprite.class.getDeclaredMethod("renderedStateCue");posture.setAccessible(true);
        Method project=CharSprite.class.getDeclaredMethod("renderedAppearanceCue",int.class);project.setAccessible(true);
        assertNull(eye.ch);assertEquals("evil_eye_charging",posture.invoke(eye));
        VisualCue right=(VisualCue)project.invoke(eye,12);
        eye.flipHorizontal=true;VisualCue left=(VisualCue)project.invoke(eye,12);
        assertNotEquals(right,left);assertEquals(Boolean.FALSE,right.appearance.get("flip_horizontal"));
        assertEquals(Boolean.TRUE,left.appearance.get("flip_horizontal"));
        assertNull(right.color);assertNull(right.opacity);assertFalse(right.appearance.containsKey("tint"));
        eye.flipVertical=true;VisualCue inverted=(VisualCue)project.invoke(eye,12);
        assertEquals(Boolean.TRUE,inverted.appearance.get("flip_vertical"));
        assertEquals("evil_eye_charging",posture.invoke(eye));
    }

    private static CharSprite uninitializedSprite()throws Exception{
        return uninitializedSprite(CharSprite.class);
    }

    private static <T extends CharSprite> T uninitializedSprite(Class<T> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),type));
    }
}
