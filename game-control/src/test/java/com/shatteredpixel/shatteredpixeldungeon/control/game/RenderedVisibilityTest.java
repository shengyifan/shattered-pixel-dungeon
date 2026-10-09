package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.effects.CircleArc;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedAppearance;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.*;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native HUD visibility gates remain conservative after retiring pixel payload construction. */
class RenderedVisibilityTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void imageGateRequiresTextureAndFiniteFrameWithoutInterpretingItsAsset() throws Exception {
        assertFalse(RenderedAppearance.isImageVisible(null));
        assertFalse(RenderedAppearance.isImageVisible(new Visual(10,10,8,8)));
        Image image=image();assertTrue(RenderedAppearance.isImageVisible(image),"Unregistered textures still have visible geometry");
        SmartTexture texture=image.texture;image.texture=null;assertFalse(RenderedAppearance.isImageVisible(image));
        image.texture=texture;image.frame(new RectF(Float.NaN,0,1,1));
        image.width=image.height=8; // Keep finite geometry so the UV-frame gate itself is exercised.
        assertTrue(Float.isNaN(image.frame().left));assertFalse(RenderedAppearance.isImageVisible(image));
        image.frame(new RectF(0,0,1,1));image.rm=Float.NaN;
        assertTrue(RenderedAppearance.isImageVisible(image),"Tint classification never determined image presence");
    }

    @Test void imageGatePreservesParentCameraOpacityAndViewportChecks() throws Exception {
        Image image=image();Group parent=new Group();parent.add(image);
        parent.visible=false;assertFalse(RenderedAppearance.isImageVisible(image));parent.visible=true;
        parent.exists=false;assertFalse(RenderedAppearance.isImageVisible(image));parent.exists=true;
        image.visible=false;assertFalse(RenderedAppearance.isImageVisible(image));image.visible=true;
        image.alpha(0);assertFalse(RenderedAppearance.isImageVisible(image));
        image.alpha(Float.NaN);assertFalse(RenderedAppearance.isImageVisible(image));image.alpha(.5f);
        image.camera.visible=false;assertFalse(RenderedAppearance.isImageVisible(image));image.camera.visible=true;
        image.x=93;assertFalse(RenderedAppearance.isImageVisible(image));image.x=92;
        assertTrue(RenderedAppearance.isImageVisible(image),"An exactly bounded image is fully visible");
        Camera camera=image.camera;image.camera=null;assertFalse(RenderedAppearance.isImageVisible(image));
        parent.camera=camera;assertTrue(RenderedAppearance.isImageVisible(image));
        assertNull(image.camera,"Reading parent geometry must not populate the image's camera cache");
    }

    @Test void imageGateUsesTransformedBoundsAndRejectsNonfiniteGeometry() throws Exception {
        Image image=image();image.angle=45;assertTrue(RenderedAppearance.isImageVisible(image));
        image.x=1;assertFalse(RenderedAppearance.isImageVisible(image),"Rotation can move otherwise visible pixels outside the viewport");
        image.x=10;image.angle=Float.NaN;assertFalse(RenderedAppearance.isImageVisible(image));image.angle=0;
        image.scale.x=Float.POSITIVE_INFINITY;assertFalse(RenderedAppearance.isImageVisible(image));image.scale.x=1;
        image.origin.x=Float.NaN;assertFalse(RenderedAppearance.isImageVisible(image));image.origin.x=0;
        image.width=0;assertFalse(RenderedAppearance.isImageVisible(image));
    }

    @Test void arcGatePreservesSweepAndRejectsHiddenOrPartialGeometry() throws Exception {
        assertNull(RenderedAppearance.visibleSweep(null));
        CircleArc arc=FloatingAppearanceTest.allocate(CircleArc.class);
        arc.exists=arc.alive=arc.visible=arc.active=true;arc.x=arc.y=20;
        arc.origin=new PointF();arc.scale=new PointF(1,1);arc.am=1;arc.camera=new Camera(0,0,100,100,1);
        FloatingAppearanceTest.field(arc,CircleArc.class,"rad",3f);
        FloatingAppearanceTest.field(arc,CircleArc.class,"sweep",.25f);
        assertEquals(.25f,RenderedAppearance.visibleSweep(arc));
        arc.angle=73;arc.rm=Float.NaN;
        assertEquals(.25f,RenderedAppearance.visibleSweep(arc),"Only the existing sweep is a turn-progress fact");
        arc.origin.x=1;assertNull(RenderedAppearance.visibleSweep(arc));arc.origin.x=0;
        arc.x=98;assertNull(RenderedAppearance.visibleSweep(arc));arc.x=97;
        assertEquals(.25f,RenderedAppearance.visibleSweep(arc));
        arc.scale.x=2;assertNull(RenderedAppearance.visibleSweep(arc));arc.scale.x=1;
        arc.alpha(0);assertNull(RenderedAppearance.visibleSweep(arc));arc.alpha(1);
        Group parent=new Group();parent.add(arc);parent.visible=false;
        assertNull(RenderedAppearance.visibleSweep(arc));
    }

    private static Image image() throws Exception {
        SmartTexture texture=FloatingAppearanceTest.allocate(SmartTexture.class);texture.width=texture.height=8;
        Image image=new Image();image.texture=texture;image.frame(new RectF(0,0,1,1));
        image.x=image.y=10;image.camera=new Camera(0,0,100,100,1);return image;
    }
}
