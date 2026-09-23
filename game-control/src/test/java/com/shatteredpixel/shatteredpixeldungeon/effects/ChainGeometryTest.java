package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.watabou.noosa.Image;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ChainGeometryTest {
    @Test void bottomPivotRotationBindsTheActualLinkCenterAndUsesThatCellsFov() {
        Image link = new Image(); link.width=5;link.height=6;
        link.origin.set(2.5f,6);link.x=60.5f;link.y=37;
        assertEquals(23,Chains.renderedCell(link,10,10));
        link.angle=90;
        assertEquals(63,link.center().x);
        assertEquals(24,Chains.renderedCell(link,10,10),"The actual quad center is (66,43), in the next column");
        VisualCueProjection.Viewport viewport = new VisualCueProjection.Viewport(0,0,160,160,0,0,1);
        boolean[] visible = new boolean[100];visible[23]=true;
        assertFalse(VisualCueProjection.permits(Chains.renderedCell(link,10,10),10,100,visible,16,viewport,Collections.emptyList()));
        visible[24]=true;visible[23]=false;
        assertTrue(VisualCueProjection.permits(Chains.renderedCell(link,10,10),10,100,visible,16,viewport,Collections.emptyList()));
        link.angle=Float.NaN;assertEquals(-1,Chains.renderedCell(link,10,10));
    }

    @Test void scalingAndNegativeRotationStillUseTheTransformedQuad() {
        Image link = new Image();link.width=5;link.height=6;
        link.origin.set(2.5f,6);link.x=64;link.y=32;link.scale.set(2,2);link.angle=-90;
        VisualCueProjection.Rect bounds = VisualCueProjection.worldBounds(link);
        assertEquals(60.5f,(bounds.left+bounds.right)/2f);
        assertEquals(38f,(bounds.top+bounds.bottom)/2f);
        assertEquals(23,Chains.renderedCell(link,10,10));
        link.x=-100;assertEquals(-1,Chains.renderedCell(link,10,10));
    }
}
