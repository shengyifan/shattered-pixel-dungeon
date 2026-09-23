package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UiBridgeTest {
    @org.junit.jupiter.api.BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    private static class TestBindingRow extends TestButton implements com.shatteredpixel.shatteredpixeldungeon.windows.WndKeyBindings.BindingRow {
        int callbacks;
        @Override public void chooseBindingSlot(int slot) {callbacks++;}
    }
    private static class TestBindingInput extends TestButton implements com.shatteredpixel.shatteredpixeldungeon.windows.WndKeyBindings.BindingInput {
        int chosen;
        @Override public boolean acceptsBindingKey(int code) {return code>=0;}
        @Override public void chooseBindingKey(int code) {chosen++;}
    }
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
        @Override protected String hoverText() { return ResourceTextFixture.literal(label); }
        @Override protected void onClick() { clicks++; }
        @Override protected void onRightClick() { rightClicks++; }
        @Override protected boolean onLongClick() { longClicks++; return true; }
    }

    @Test void discoversOnlyExistingControlsWithoutExecutingCallbacksOrReadingPrivateFields() {
        TestScene scene = new TestScene();
        TestButton button = new TestButton();
        scene.add(button);
        UiBridge bridge = new UiBridge(() -> scene);

        Map<String, Object> ui = UiDrawFixture.capture(bridge).describeUi();
        List<Map<String, Object>> actions = bridge.describeActions();
        UiDrawFixture.capture(bridge).describeUi();
        assertEquals(0, button.clicks + button.rightClicks + button.longClicks);
        assertTrue(ui.toString().contains("Visible choice"));
        assertFalse(ui.toString().contains("DO_NOT_DISCLOSE"));
        Map<String, Object> activation = actions.stream().filter(a -> "ui.activate".equals(a.get("action"))).findFirst().orElseThrow();
        assertEquals(List.of("click", "right", "long"), activation.get("gestures"));
    }

    @Test void publishedBindingSlotsMatchTheExistingActionAndDoNotEnableAnInactiveRow() {
        TestScene scene=new TestScene();TestBindingRow row=new TestBindingRow();scene.add(row);
        UiBridge bridge=new UiBridge(()->scene);
        @SuppressWarnings("unchecked") List<Map<String,Object>> nodes=(List<Map<String,Object>>)UiDrawFixture.capture(bridge).describeUi().get("controls");
        Map<String,Object> described=nodes.stream().filter(n->n.containsKey("binding_slots")).findFirst().orElseThrow();
        Map<String,Object> action=bridge.describeActions().stream().filter(a->a.get("action").equals("ui.binding_slot")).findFirst().orElseThrow();
        assertEquals(List.of(1,2,3),described.get("binding_slots"));assertEquals(action.get("slots"),described.get("binding_slots"));
        assertEquals(action.get("control"),described.get("id"));assertEquals(0,row.callbacks);
        row.active=false;
        assertTrue(bridge.describeActions().stream().noneMatch(a->a.get("action").equals("ui.binding_slot")));
        assertTrue(UiDrawFixture.capture(bridge).describeUi().toString().contains("binding_slots"));assertEquals(0,row.callbacks);
    }

    @Test void publishedBindingInputMatchesTheExistingKeyActionWithoutInvokingIt() {
        TestScene scene=new TestScene();TestBindingInput input=new TestBindingInput();scene.add(input);
        UiBridge bridge=new UiBridge(()->scene);
        @SuppressWarnings("unchecked") List<Map<String,Object>> nodes=(List<Map<String,Object>>)UiDrawFixture.capture(bridge).describeUi().get("controls");
        Map<String,Object> node=nodes.stream().filter(n->Boolean.TRUE.equals(n.get("binding_input"))).findFirst().orElseThrow();
        Map<String,Object> action=bridge.describeActions().stream().filter(a->a.get("action").equals("ui.binding_key")).findFirst().orElseThrow();
        assertEquals(node.get("id"),action.get("control"));assertEquals(0,input.chosen);
        input.active=false;
        assertTrue(bridge.describeActions().stream().noneMatch(a->a.get("action").equals("ui.binding_key")));
        assertTrue(UiDrawFixture.capture(bridge).describeUi().toString().contains("binding_input"));assertEquals(0,input.chosen);
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

    @Test void unhandledNativeLongPressCompletesWithoutClickOrFailure() {
        TestScene scene = new TestScene();
        TestButton button = new TestButton() {
            @Override protected boolean onLongClick() { longClicks++; return false; }
        };
        scene.add(button);
        UiBridge bridge = new UiBridge(() -> scene);
        String control = firstButtonId(bridge);
        assertDoesNotThrow(() -> bridge.execute("ui.activate", Map.of("control", control, "gesture", "long")));
        assertEquals(1, button.longClicks);
        assertEquals(0, button.clicks);
        assertEquals(0, button.rightClicks);
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
        String original = UiDrawFixture.capture(bridge).contextSignature();
        assertEquals(original, UiDrawFixture.capture(bridge).contextSignature());
        button.label = "Different visible choice";
        String changed = UiDrawFixture.capture(bridge).contextSignature();
        assertNotEquals(original, changed);
        scene.erase(button);
        TestButton replacement = new TestButton();
        replacement.label = button.label;
        scene.add(replacement);
        assertNotEquals(changed, UiDrawFixture.capture(bridge).contextSignature());
        assertEquals(0, button.clicks + replacement.clicks);
    }

    @Test void unclassifiedVisibleChoiceStillInvalidatesItsOldIntent() {
        TestScene scene=new TestScene();
        TestButton button=new TestButton(){@Override protected String hoverText(){return label;}};
        scene.add(button); UiBridge bridge=new UiBridge(()->scene);
        String initial=bridge.intentSignature();
        assertEquals("partial",PublicEnglishProjection.presentation(UiDrawFixture.capture(bridge).describeUi()).get("status"));
        button.label="A different unclassified choice";
        assertNotEquals(initial,bridge.intentSignature());
    }

    @Test void intentSignatureRetainsRealControlAvailabilityAndIdentity() {
        TestScene scene = new TestScene();
        TestButton button = new TestButton();
        scene.add(button);
        UiBridge bridge = new UiBridge(() -> scene);
        String before = bridge.intentSignature();
        assertEquals(before, bridge.intentSignature());
        button.active = false;
        String disabled = bridge.intentSignature();
        assertNotEquals(before, disabled);
        button.active = true;
        button.label = "A different real option";
        String relabelled = bridge.intentSignature();
        assertNotEquals(before, relabelled);
        scene.erase(button);
        TestButton replacement = new TestButton(); replacement.label = button.label; scene.add(replacement);
        assertNotEquals(relabelled, bridge.intentSignature());
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
        UiDrawFixture.capture(bridge).describeUi();
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
        String signature = UiDrawFixture.capture(bridge).contextSignature();
        bridge.validate("ui.activate", Map.of("control", control));
        bridge.validate("ui.activate", Map.of("control", control, "gesture", "long"));
        bridge.validate("ui.back", Map.of());
        assertEquals(signature, UiDrawFixture.capture(bridge).contextSignature());
        assertEquals(0, button.clicks + button.rightClicks + button.longClicks + scene.backCount);
    }

    @Test void invalidPreflightCannotAccidentallyTriggerOrChangeAControl() {
        TestScene scene = new TestScene();
        TestButton button = new TestButton();
        scene.add(button);
        UiBridge bridge = new UiBridge(() -> scene);
        String control = firstButtonId(bridge);
        String signature = UiDrawFixture.capture(bridge).contextSignature();
        assertThrows(IllegalArgumentException.class, () -> bridge.validate("ui.activate", Map.of("control", control, "gesture", "invalid")));
        assertThrows(IllegalArgumentException.class, () -> bridge.validate("ui.text", Map.of("control", control, "text", "value")));
        assertThrows(IllegalArgumentException.class, () -> bridge.validate("ui.scroll", Map.of("control", control, "y", 100)));
        assertThrows(IllegalArgumentException.class, () -> bridge.validate("ui.value", Map.of("control", control, "value", 1.5)));
        assertEquals(signature, UiDrawFixture.capture(bridge).contextSignature());
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
