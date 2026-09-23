package com.shatteredpixel.shatteredpixeldungeon.control.game;

/** Explicit completed-draw simulation for existing layout/projection tests; never claims a native GL draw. */
final class UiDrawFixture {
    private UiDrawFixture() { }
    static UiBridge capture(UiBridge bridge) { bridge.captureDrawnEvidence(); return bridge; }
}
