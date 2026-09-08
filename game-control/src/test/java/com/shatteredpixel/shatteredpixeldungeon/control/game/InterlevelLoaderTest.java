package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import static org.junit.Assert.*;

/** Exercises the loader lifetime gate without constructing textures or opening a graphical window. */
public class InterlevelLoaderTest {
    @Test public void clearingTheSharedThreadSlotDoesNotMakeAnAliveLoaderReadable() throws Exception {
        Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        sun.misc.Unsafe allocator=(sun.misc.Unsafe)unsafeField.get(null);
        InterlevelScene scene=(InterlevelScene)allocator.allocateInstance(InterlevelScene.class);
        StyledButton button=(StyledButton)allocator.allocateInstance(StyledButton.class);button.active=true;
        Field slot=field("thread");Object previous=slot.get(null);
        CountDownLatch release=new CountDownLatch(1);
        Thread loader=new Thread(()->{try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}},"Test retained interlevel loader");
        try{
            field("loaderForScene").set(scene,loader);
            Field phase=field("phase");
            @SuppressWarnings({"rawtypes","unchecked"}) Object waiting=Enum.valueOf((Class)phase.getType(),"STATIC");
            phase.set(scene,waiting);field("btnContinue").set(scene,button);field("textFadingIn").setBoolean(scene,false);
            loader.start();slot.set(null,null);
            assertFalse("An error window clearing the static slot must not bypass the actual worker",scene.awaitingUserInput());
            release.countDown();loader.join(2000);assertFalse(loader.isAlive());
            assertTrue("The same ready UI becomes readable only after the real loader terminates",scene.awaitingUserInput());
        }finally{release.countDown();loader.join(2000);slot.set(null,previous);}
    }
    private Field field(String name)throws Exception{Field field=InterlevelScene.class.getDeclaredField(name);field.setAccessible(true);return field;}
}
