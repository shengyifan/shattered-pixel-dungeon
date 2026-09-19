package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Visual;
import com.watabou.noosa.Camera;
import com.watabou.noosa.particles.Emitter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class VisualCueAttachmentTest {
    @Test void aNewEmitterUsesTheSceneCameraBeforeItsFirstParticleCachesIt() throws Exception {
        Camera previous = Camera.main;
        Camera main = new Camera(0, 0, 64, 64, 1);
        Camera overlay = new Camera(0, 0, 32, 32, 1);
        Camera.main = main;
        try {
            GameScene scene = new GameScene();
            Group layer = new Group(); scene.add(layer);
            Emitter source = new Emitter(); layer.add(source);
            Method resolve = VisualCueCollector.class.getDeclaredMethod("resolvedCamera", Gizmo.class);
            resolve.setAccessible(true);
            assertSame(scene.camera(), resolve.invoke(null, source));
            assertNull(source.camera, "Observing must not populate the renderer's lazy camera cache");
            assertNull(layer.camera);
            layer.camera = overlay;
            assertSame(overlay, resolve.invoke(null, source));
            assertNull(source.camera);
            layer.erase(source);
            assertNull(resolve.invoke(null, source), "Detached emitters have no scene camera");
        } finally {
            Camera.main = previous;
        }
    }

    @Test void detachedHiddenAndOldSceneSourcesCannotBeObserved() throws Exception {
        GameScene scene = new GameScene();
        GameScene oldScene = new GameScene();
        VisualCueCollector collector = new VisualCueCollector(scene);
        Method attached = VisualCueCollector.class.getDeclaredMethod("attached", Gizmo.class);
        attached.setAccessible(true);
        Group layer = new Group(); scene.add(layer);
        Gizmo source = layer.add(new Gizmo());
        assertEquals(true, attached.invoke(collector, source));
        layer.visible = false;
        assertEquals(false, attached.invoke(collector, source));
        layer.visible = true;
        layer.erase(source);
        assertEquals(false, attached.invoke(collector, source));
        oldScene.add(source);
        assertEquals(false, attached.invoke(collector, source));
    }

    @Test void noAlphaOrNoGeometryIsNotDrawableEvidence() throws Exception {
        GameScene scene = new GameScene();
        VisualCueCollector collector = new VisualCueCollector(scene);
        Method drawable = VisualCueCollector.class.getDeclaredMethod("drawable", Visual.class);
        drawable.setAccessible(true);
        Visual source = new Visual(0, 0, 4, 4) { @Override public boolean isVisible() { return visible; } };
        scene.add(source);
        assertEquals(true, drawable.invoke(collector, source));
        source.am = source.aa = 0;
        assertEquals(false, drawable.invoke(collector, source));
        source.am = 1; source.width = 0;
        assertEquals(false, drawable.invoke(collector, source));
        source.width = 4; source.kill();
        assertEquals(false, drawable.invoke(collector, source));
    }
}
