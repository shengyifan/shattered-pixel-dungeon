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
}
