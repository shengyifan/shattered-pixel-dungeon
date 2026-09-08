package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UiBridgeTest {
    private static class TestScene extends Scene {
        int backCount;
        @Override protected void onBackPressed() { backCount++; }
    }

    /** The control callbacks are real, while rendering/input registration is omitted. */
    private static class TestButton extends Button {
        int clicks;
        int rightClicks;
        int longClicks;
        String label = "Visible choice";
        @SuppressWarnings("unused") private final String hiddenValue = "DO_NOT_DISCLOSE";
        @Override protected void createChildren() { }
        @Override protected String hoverText() { return label; }
        @Override protected void onClick() { clicks++; }
        @Override protected void onRightClick() { rightClicks++; }
        @Override protected boolean onLongClick() { longClicks++; return true; }
    }

    @Test void discoversOnlyExistingControlsWithoutExecutingCallbacksOrReadingPrivateFields() {
        TestScene scene = new TestScene();
        TestButton button = new TestButton();
        scene.add(button);
        UiBridge bridge = new UiBridge(() -> scene);

        Map<String, Object> ui = bridge.describeUi();
        List<Map<String, Object>> actions = bridge.describeActions();
        bridge.describeUi();
        assertEquals(0, button.clicks + button.rightClicks + button.longClicks);
        assertTrue(ui.toString().contains("Visible choice"));
        assertFalse(ui.toString().contains("DO_NOT_DISCLOSE"));
        Map<String, Object> activation = actions.stream().filter(a -> "ui.activate".equals(a.get("action"))).findFirst().orElseThrow();
        assertEquals(List.of("click", "right", "long"), activation.get("gestures"));
    }

    @Test void invokesEachSemanticCallbackExactlyOnceAndRejectsUnsupportedGesture() {
        TestScene scene = new TestScene();
        TestButton button = new TestButton();
        scene.add(button);
        UiBridge bridge = new UiBridge(() -> scene);
        String control = firstButtonId(bridge);

        bridge.execute("ui.activate", Map.of("control", control));
        bridge.execute("ui.activate", Map.of("control", control, "gesture", "right"));
        bridge.execute("ui.activate", Map.of("control", control, "gesture", "long"));
        assertThrows(IllegalArgumentException.class, () -> bridge.execute("ui.activate", Map.of("control", control, "gesture", "middle")));
        assertEquals(1, button.clicks);
        assertEquals(1, button.rightClicks);
        assertEquals(1, button.longClicks);
    }

    @Test void rejectsDisabledHiddenDetachedAndReplacedControls() {
        TestScene scene = new TestScene();
        Group container = new Group();
        TestButton button = new TestButton();
        scene.add(container);
        container.add(button);
        UiBridge bridge = new UiBridge(() -> scene);
        String control = firstButtonId(bridge);

        container.active = false;
        assertUnavailable(bridge, control);
        container.active = true;
        container.visible = false;
        assertUnavailable(bridge, control);
        container.visible = true;
        container.erase(button);
        assertUnavailable(bridge, control);
        container.add(new TestButton());
        assertNotEquals(control, firstButtonId(bridge));
        assertUnavailable(bridge, control);
        assertEquals(0, button.clicks);
    }

    @Test void signatureTracksPublicValuesAndControlLifetimesWithoutChangingTheGame() {
        TestScene scene = new TestScene();
        TestButton button = new TestButton();
        scene.add(button);
        UiBridge bridge = new UiBridge(() -> scene);
        String original = bridge.contextSignature();
        assertEquals(original, bridge.contextSignature());
        button.label = "Different visible choice";
        String changed = bridge.contextSignature();
        assertNotEquals(original, changed);
        scene.erase(button);
        TestButton replacement = new TestButton();
        replacement.label = button.label;
        scene.add(replacement);
        assertNotEquals(changed, bridge.contextSignature());
        assertEquals(0, button.clicks + replacement.clicks);
    }

    @Test void sceneBackUsesNormalCallbackAndUnknownActionsDoNothing() {
        TestScene scene = new TestScene();
        UiBridge bridge = new UiBridge(() -> scene);
        bridge.execute("ui.back", Map.of());
        assertEquals(1, scene.backCount);
        assertThrows(IllegalArgumentException.class, () -> bridge.execute("ui.secret", Map.of()));
        assertEquals(1, scene.backCount);
        assertThrows(IllegalStateException.class, () -> bridge.execute("cell.select", Map.of("cell", 1)));
    }

    @Test void returningToASceneDoesNotResurrectOldHandles() {
        TestScene first = new TestScene();
        TestButton button = new TestButton();
        first.add(button);
        Scene[] current = { first };
        UiBridge bridge = new UiBridge(() -> current[0]);
        String oldId = firstButtonId(bridge);
        current[0] = new TestScene();
        bridge.describeUi();
        current[0] = first;
        assertNotEquals(oldId, firstButtonId(bridge));
        assertUnavailable(bridge, oldId);
    }

    @Test void validationNeverExecutesACallbackOrChangesThePublicContext() {
        TestScene scene = new TestScene();
        TestButton button = new TestButton();
        scene.add(button);
        UiBridge bridge = new UiBridge(() -> scene);
        String control = firstButtonId(bridge);
        String signature = bridge.contextSignature();
        bridge.validate("ui.activate", Map.of("control", control));
        bridge.validate("ui.activate", Map.of("control", control, "gesture", "long"));
        bridge.validate("ui.back", Map.of());
        assertEquals(signature, bridge.contextSignature());
        assertEquals(0, button.clicks + button.rightClicks + button.longClicks + scene.backCount);
    }

    @Test void invalidPreflightCannotAccidentallyTriggerOrChangeAControl() {
        TestScene scene = new TestScene();
        TestButton button = new TestButton();
        scene.add(button);
        UiBridge bridge = new UiBridge(() -> scene);
        String control = firstButtonId(bridge);
        String signature = bridge.contextSignature();
        assertThrows(IllegalArgumentException.class, () -> bridge.validate("ui.activate", Map.of("control", control, "gesture", "invalid")));
        assertThrows(IllegalArgumentException.class, () -> bridge.validate("ui.text", Map.of("control", control, "text", "value")));
        assertThrows(IllegalArgumentException.class, () -> bridge.validate("ui.scroll", Map.of("control", control, "y", 100)));
        assertThrows(IllegalArgumentException.class, () -> bridge.validate("ui.value", Map.of("control", control, "value", 1.5)));
        assertEquals(signature, bridge.contextSignature());
        assertEquals(0, button.clicks + button.rightClicks + button.longClicks);
    }

    private static String firstButtonId(UiBridge bridge) {
        return (String) bridge.describeActions().stream()
                .filter(a -> "ui.activate".equals(a.get("action"))).findFirst().orElseThrow().get("control");
    }

    private static void assertUnavailable(UiBridge bridge, String control) {
        assertThrows(IllegalStateException.class, () -> bridge.execute("ui.activate", Map.of("control", control)));
    }
}
