package com.shatteredpixel.shatteredpixeldungeon.effects;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class VisualCueProjectionTest {
    private static VisualCueProjection.Viewport view(float left, float top, float width, float height) {
        return new VisualCueProjection.Viewport(left, top, width, height, 10, 20, 2);
    }

    @Test void unknownAndOutOfBoundsCellsCannotBecomeCues() {
        boolean[] visible = new boolean[100]; visible[22] = true;
        VisualCueProjection.Viewport camera = view(0, 0, 160, 160);
        assertTrue(VisualCueProjection.permits(22, 10, 100, visible, 16, camera, Collections.emptyList()));
        assertFalse(VisualCueProjection.permits(23, 10, 100, visible, 16, camera, Collections.emptyList()));
        assertFalse(VisualCueProjection.permits(-1, 10, 100, visible, 16, camera, Collections.emptyList()));
        assertFalse(VisualCueProjection.permits(100, 10, 100, visible, 16, camera, Collections.emptyList()));
    }

    @Test void partialViewportAndAnyHudOverlapAreConservativelyOmitted() {
        boolean[] visible = new boolean[100]; Arrays.fill(visible, true);
        assertFalse(VisualCueProjection.permits(22, 10, 100, visible, 16, view(33, 0, 160, 160), Collections.emptyList()));
        VisualCueProjection.Viewport camera = view(0, 0, 160, 160);
        // Cell 22 projects to [74,84 .. 106,116] on this observed camera.
        assertFalse(VisualCueProjection.permits(22, 10, 100, visible, 16, camera,
                Collections.singletonList(new VisualCueProjection.Rect(105, 84, 200, 200))));
        assertTrue(VisualCueProjection.permits(22, 10, 100, visible, 16, camera,
                Collections.singletonList(new VisualCueProjection.Rect(106, 84, 200, 200))));
    }

    @Test void queriesDoNotModifyVisibilityOrUseUnavailableCameraGeometry() {
        boolean[] visible = new boolean[100]; visible[22] = true;
        boolean[] original = visible.clone();
        for (int i = 0; i < 10; i++) assertTrue(VisualCueProjection.permits(22, 10, 100, visible, 16, view(0, 0, 160, 160), Collections.emptyList()));
        assertArrayEquals(original, visible);
        assertFalse(VisualCueProjection.permits(22, 10, 100, visible, 16,
                new VisualCueProjection.Viewport(Float.NaN, 0, 160, 160, 0, 0, 1), Collections.emptyList()));
        assertFalse(VisualCueProjection.permits(22, 10, 100, visible, 16, null, Collections.emptyList()));
    }

    @Test void onlyActualAlignedFullTileGeometryMapsToAnInBoundsCell() {
        assertEquals(22, VisualCueProjection.gridCell(32,32,16,16,16,10,100));
        assertEquals(-1, VisualCueProjection.gridCell(33,32,16,16,16,10,100));
        assertEquals(-1, VisualCueProjection.gridCell(32,32,8,16,16,10,100));
        assertEquals(-1, VisualCueProjection.gridCell(160,32,16,16,16,10,100), "Do not wrap an out-of-bounds column to another row");
        assertEquals(-1, VisualCueProjection.gridCell(32,160,16,16,16,10,100));
        assertEquals(-1, VisualCueProjection.gridCell(Float.NaN,32,16,16,16,10,100));
    }

    @Test void transformedQuadCenterAccountsForRotationAroundANoncentralOrigin() {
        com.watabou.noosa.Visual source=new com.watabou.noosa.Visual(0,0,2,8);
        source.origin.set(1,8);source.angle=90;
        VisualCueProjection.Rect bounds=VisualCueProjection.worldBounds(source);
        assertNotNull(bounds);assertEquals(1,bounds.left,0.0001f);assertEquals(7,bounds.top,0.0001f);
        assertEquals(9,bounds.right,0.0001f);assertEquals(9,bounds.bottom,0.0001f);
        assertEquals(5,(bounds.left+bounds.right)/2,0.0001f);
        assertNotEquals(source.center().x,(bounds.left+bounds.right)/2);
    }

    @Test void physicalFovFootprintUsesTheDrawTransformAndExclusiveRightAndBottomEdges() {
        boolean[] visible=new boolean[100];visible[22]=true;
        VisualCueProjection.Viewport view=new VisualCueProjection.Viewport(16,16,100,100,10,20,2);
        assertTrue(VisualCueProjection.permitsVisibleFootprint(view.screenRect(44,32,4,4),view,16,10,100,visible));
        assertFalse(VisualCueProjection.permitsVisibleFootprint(view.screenRect(46,32,4,4),view,16,10,100,visible));
        assertFalse(VisualCueProjection.permitsVisibleFootprint(view.screenRect(49,32,4,4),view,16,10,100,visible));
        assertFalse(VisualCueProjection.permitsVisibleFootprint(view.screenRect(-1,32,4,4),view,16,10,100,visible));
        assertFalse(VisualCueProjection.permitsVisibleFootprint(new VisualCueProjection.Rect(Float.NaN,20,40,40),view,16,10,100,visible));
    }
}
