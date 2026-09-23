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
    @Test void flareUnknownMeaningUsesTypedDiagnosticWithoutInventingAuraOrLootTier() throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Flare flare=(Flare)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),Flare.class);
        Field rays=Flare.class.getDeclaredField("nRays");rays.setAccessible(true);rays.setInt(flare,4);
        flare.hardlight(0xFF8800);assertEquals(Map.of("variant","blazing"),flare.auraFact());
        flare.hardlight(0x123456);Map<String,Object> unknown=flare.auraFact();
        assertEquals(true,unknown.get("unmapped_indicator"));assertFalse(unknown.containsKey("variant"));
        assertEquals(Map.of("status","partial","diagnostics",List.of(Map.of("field","variant","code","unmapped_indicator","indicator","character_aura"))),unknown.get("presentation"));
        Field radius=Flare.class.getDeclaredField("visualRadius");radius.setAccessible(true);radius.setFloat(flare,24f);
        rays.setInt(flare,6);flare.observeLootTier(2);
        Map<String,Object> loot=flare.lootFact();assertEquals(true,loot.get("unmapped_indicator"));assertFalse(loot.containsKey("tier"));
        assertEquals(Map.of("status","partial","diagnostics",List.of(Map.of("field","tier","code","unmapped_indicator","indicator","loot_flare"))),loot.get("presentation"));
    }
    @Test void ordinaryWhiteHitFlashFadeAndScaleDoNotProduceUnknownGameplayIndicators() throws Exception {
        CharSprite sprite=uninitializedSprite();sprite.scale=new PointF(1,1);sprite.resetColor();
        Method project=CharSprite.class.getDeclaredMethod("renderedAppearanceCue",int.class);project.setAccessible(true);
        sprite.alpha(.6f);sprite.scale.set(.75f);assertNull(project.invoke(sprite,12));
        sprite.flash();assertNull(project.invoke(sprite,12));
        sprite.paused=true;VisualCue paused=(VisualCue)project.invoke(sprite,12);
        assertEquals(Collections.singletonMap("paused",true),paused.appearance);
    }
    @Test void hiddenIconPauseDarknessOpacityAndScaleComeFromRendererWithoutAnActor()throws Exception{
        CharSprite sprite=uninitializedSprite();Method project=CharSprite.class.getDeclaredMethod("renderedAppearanceCue",int.class);project.setAccessible(true);
        sprite.scale=new PointF(1,1);sprite.resetColor();assertNull(sprite.ch);
        VisualCue plain=(VisualCue)project.invoke(sprite,12);
        assertNull(plain, "Plain sprite direction and drawing defaults are not gameplay facts");
        sprite.paused=true;sprite.brightness(.4f);sprite.alpha(.6f);sprite.scale.set(1.25f);
        VisualCue cue=(VisualCue)project.invoke(sprite,12);
        assertEquals("sprite_state",cue.kind);assertNull(cue.opacity);
        assertEquals(Boolean.TRUE,cue.appearance.get("paused"));
        assertFalse(cue.appearance.containsKey("resized"));assertFalse(cue.appearance.containsKey("translucent"));
        assertEquals(true,cue.appearance.get("unmapped_indicator"));assertFalse(cue.appearance.containsKey("tint"));
        Map<?,?> presentation=(Map<?,?>)cue.appearance.get("presentation");assertEquals("partial",presentation.get("status"));
        assertEquals(Map.of("field","style","code","unmapped_indicator","indicator","character_tint"),((List<?>)presentation.get("diagnostics")).get(0));
        assertNull(project.invoke(sprite,-1));sprite.alpha(0);assertNull(project.invoke(sprite,12));
    }

    @Test void currentCosmeticGlowPulseKeepsExactTintAlongsideStableStyle()throws Exception{
        CharSprite sprite=uninitializedSprite();sprite.scale=new PointF(1,1);sprite.resetColor();
        GlowBlock glow=new GlowBlock(sprite);
        Field field=CharSprite.class.getDeclaredField("glowBlock");field.setAccessible(true);field.set(sprite,glow);
        Method project=CharSprite.class.getDeclaredMethod("renderedAppearanceCue",int.class);project.setAccessible(true);
        sprite.tint(1.33f,1.33f,.83f,.4f);VisualCue first=(VisualCue)project.invoke(sprite,12);
        sprite.tint(1.33f,1.33f,.83f,.6f);VisualCue second=(VisualCue)project.invoke(sprite,12);
        assertEquals(first,second);assertEquals("golden_glow",first.appearance.get("style"));
        assertEquals(first.appearance.get("style"),second.appearance.get("style"));
        sprite.resetColor();VisualCue plain=(VisualCue)project.invoke(sprite,12);
        assertNull(plain,"A controller alone is not evidence of an applied visible tint");
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
        assertNull(right);assertNull(left,"Cosmetic facing must not recreate ordinary appearance metadata");
        eye.flipVertical=true;assertNull(project.invoke(eye,12));
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
