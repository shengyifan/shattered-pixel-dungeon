package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.sprites.PrismaticSprite;
import com.watabou.noosa.VisualCue;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class PrismaticVisualCueTest {
    @Test void pausedTransparencyComesOnlyFromTheSpriteAndPreservesDifferentDrawnStages()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");
        Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        PrismaticSprite sprite=(PrismaticSprite)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),PrismaticSprite.class);
        Method appearance=PrismaticSprite.class.getDeclaredMethod("renderedPrismaticCue",int.class);appearance.setAccessible(true);
        assertNull(sprite.ch);
        sprite.alpha(1f);assertNull(appearance.invoke(sprite,12));
        sprite.paused=true;
        VisualCue opaque=(VisualCue)appearance.invoke(sprite,12);
        assertEquals("prismatic_image_paused",opaque.kind);assertEquals(1f,opaque.opacity);
        sprite.alpha(.75f);VisualCue faded=(VisualCue)appearance.invoke(sprite,12);
        assertEquals(.75f,faded.opacity);assertNotEquals(opaque,faded);
        assertNull(appearance.invoke(sprite,-1));
        sprite.alpha(Float.NaN);assertNull(appearance.invoke(sprite,12));
        sprite.alpha(0f);assertNull(appearance.invoke(sprite,12));
    }
}
