package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.ItemSlot;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UiProjectionHintsTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    private static class DisplayOnlyItem extends Item {
        @Override public String name() { throw new AssertionError("Must use captured display text"); }
        @Override public String status() { throw new AssertionError("Must not recompute item status"); }
    }

    private static class DisplayOnlySlot extends ItemSlot {
        @Override protected String hoverText() { return Messages.literal("Visible item"); }
    }

    @Test void sidecarSurvivesFreezeAndRenderWithoutChangingCanonicalJsonOrSemantics() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("controls", List.of(Map.of("id", "parent", "role", "button", "text", Messages.literal("Visible"))));
            List<String> children = new ArrayList<>(List.of("child"));
            Map<String, String> display = new LinkedHashMap<>(Map.of("status", "child"));
            UiProjectionHints.Node node = new UiProjectionHints.Node(false, children, "backpack.0", display);
            Map<String, UiProjectionHints.Node> nodes = new LinkedHashMap<>(Map.of("parent", node));
            UiProjectionHints hints = new UiProjectionHints(nodes);
            Map<String, Object> carried = hints.attach(canonical);
            Map<String, Object> frozen = PublicEnglishProjection.freeze(carried);
            children.clear(); display.clear(); nodes.clear();
            assertSame(hints, UiProjectionHints.get(frozen));
            assertEquals(List.of("child"), hints.nodes.get("parent").ownedTextChildren);
            assertEquals(Map.of("status", "child"), hints.nodes.get("parent").displayChildren);
            assertThrows(UnsupportedOperationException.class, () -> frozen.put("extra", true));
            assertThrows(UnsupportedOperationException.class, () -> hints.nodes.clear());
            assertThrows(UnsupportedOperationException.class, () -> node.displayChildren.clear());
            Map<String, Object> rendered = PublicEnglishProjection.copy(frozen);
            assertSame(hints, UiProjectionHints.get(rendered));
            assertEquals(JsonCodec.encode(PublicEnglishProjection.copy(canonical)), JsonCodec.encode(rendered));
            assertEquals(PublicEnglishProjection.semantics(PublicEnglishProjection.freeze(canonical)),
                    PublicEnglishProjection.semantics(frozen));
            assertTrue(UiProjectionHints.get(new LinkedHashMap<>(rendered)).nodes.isEmpty(),
                    "Only an explicit in-process map copy can retain hints; ordinary JSON data cannot");
        }
    }

    @Test void itemHintsUseVisibleExistingChildrenAndDoNotChangeTheCanonicalCapture() throws Exception {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Hero previous = Dungeon.hero;
            try {
                Dungeon.hero = new Hero();
                Item item = new DisplayOnlyItem();
                Dungeon.hero.belongings.backpack.items.add(item);
                DisplayOnlySlot slot = slot(item);
                BitmapText status = text("4/20"), extra = text("14?"), level = text("+2");
                display(slot, "status", status); display(slot, "extra", extra); display(slot, "level", level);
                Scene scene = new Scene(); scene.add(slot);
                UiBridge bridge = new UiBridge(() -> scene);
                Map<String, Object> observation = inventory("backpack.0");
                Map<String, Object> frozen = bridge.frozenUi(observation);
                Map<String, Object> rendered = PublicEnglishProjection.copy(frozen);
                String slotId = firstId(rendered);
                UiProjectionHints.Node hint = UiProjectionHints.get(rendered).nodes.get(slotId);
                assertEquals("backpack.0", hint.locator);
                assertEquals(List.of("status", "extra", "level"), new ArrayList<>(hint.displayChildren.keySet()));
                assertEquals(3, hint.ownedTextChildren.size());
                for (Map.Entry<String, String> entry : hint.displayChildren.entrySet()) {
                    Map<String, Object> child = node(rendered, entry.getValue());
                    assertEquals(slotId, child.get("parent"));
                    assertTrue(child.containsKey("text_sources"));
                }
                assertEquals("14?", node(rendered, hint.displayChildren.get("extra")).get("text"));
                assertEquals(JsonCodec.encode(bridge.describeUi()), JsonCodec.encode(rendered));
                String signature = bridge.intentSignature();
                bridge.frozenUi(observation);
                assertEquals(signature, bridge.intentSignature(), "Hint construction must not affect freshness");
                extra.visible = false;
                UiProjectionHints.Node hidden = UiProjectionHints.get(bridge.frozenUi(observation)).nodes.get(slotId);
                assertFalse(hidden.displayChildren.containsKey("extra"));
                slot.remove(level);
                UiProjectionHints.Node detached = UiProjectionHints.get(bridge.frozenUi(observation)).nodes.get(slotId);
                assertFalse(detached.displayChildren.containsKey("level"));
                status.alpha(0);
                UiProjectionHints.Node transparent = UiProjectionHints.get(bridge.frozenUi(observation)).nodes.get(slotId);
                assertFalse(transparent.displayChildren.containsKey("status"));
            } finally { Dungeon.hero = previous; }
        }
    }

    @Test void bindingsRequireUniqueIdentityInTheCurrentPublicInventory() throws Exception {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Hero previous = Dungeon.hero;
            try {
                Dungeon.hero = new Hero();
                Item first = new DisplayOnlyItem(), second = new DisplayOnlyItem();
                Dungeon.hero.belongings.backpack.items.add(first);
                Dungeon.hero.belongings.backpack.items.add(second);
                DisplayOnlySlot slot = slot(first); display(slot, "status", text("1"));
                Scene scene = new Scene(); scene.add(slot);
                UiBridge bridge = new UiBridge(() -> scene);
                Map<String, Object> fullInventory = inventory("backpack.0", "backpack.1");
                String id = firstId(bridge.describeUi());
                assertEquals("backpack.0", hints(bridge, fullInventory, id).locator);
                Collections.swap(Dungeon.hero.belongings.backpack.items, 0, 1);
                assertEquals("backpack.1", hints(bridge, fullInventory, id).locator);
                assertNull(hints(bridge, inventory("backpack.0"), id).locator,
                        "An item absent from this public inventory cannot be matched");
                Dungeon.hero.belongings.backpack.items.add(first);
                assertNull(hints(bridge, inventory("backpack.0", "backpack.1", "backpack.2"), id).locator,
                        "Duplicate identity has no unique public locator");
                field(slot, ItemSlot.class, "item", new DisplayOnlyItem());
                assertNull(hints(bridge, fullInventory, id).locator,
                        "A virtual copy with identical visible text is not the inventory object");
                assertNull(hints(bridge, Collections.emptyMap(), id).locator);
            } finally { Dungeon.hero = previous; }
        }
    }

    @Test void placeholdersExcludeActiveIconsAndCustomVisibleDecorations() throws Exception {
        DisplayOnlySlot slot = slot(null); slot.active = false;
        assertTrue(slot.emptyRenderedPlaceholder());
        slot.active = true;
        assertFalse(slot.emptyRenderedPlaceholder());
        slot.active = false;
        Gizmo customIcon = new Gizmo(); slot.add(customIcon);
        assertFalse(slot.emptyRenderedPlaceholder());
        customIcon.visible = false;
        assertTrue(slot.emptyRenderedPlaceholder());
        BitmapText status = text("4/20"); display(slot, "status", status);
        assertFalse(slot.emptyRenderedPlaceholder());
        status.visible = false;
        assertTrue(slot.emptyRenderedPlaceholder());
        slot.erase(status);
        assertTrue(slot.emptyRenderedPlaceholder(), "Erased group members can leave null list entries");
    }

    private static UiProjectionHints.Node hints(UiBridge bridge, Map<String, Object> observation, String id) {
        return UiProjectionHints.get(bridge.frozenUi(observation)).nodes.get(id);
    }

    private static Map<String, Object> inventory(String... locators) {
        List<Object> items = new ArrayList<>();
        for (String locator : locators) items.add(Map.of("locator", locator));
        return Map.of("inventory", items);
    }

    private static DisplayOnlySlot slot(Item item) throws Exception {
        DisplayOnlySlot slot = allocate(DisplayOnlySlot.class);
        slot.exists = slot.alive = slot.active = slot.visible = true;
        field(slot, Group.class, "members", new ArrayList<Gizmo>());
        field(slot, ItemSlot.class, "item", item);
        return slot;
    }

    private static BitmapText text(String text) {
        BitmapText value = new BitmapText(Messages.literal(text), null);
        value.camera = new Camera(0, 0, 100, 100, 1);
        value.x = value.y = 10; value.width = 20; value.height = 10;
        return value;
    }

    private static void display(ItemSlot slot, String name, BitmapText value) throws Exception {
        field(slot, ItemSlot.class, name, value); slot.add(value);
    }

    private static String firstId(Map<String, Object> ui) {
        return (String) ((Map<?, ?>) ((List<?>) ui.get("controls")).get(0)).get("id");
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> node(Map<String, Object> ui, String id) {
        for (Object raw : (List<?>) ui.get("controls"))
            if (id.equals(((Map<?, ?>) raw).get("id"))) return (Map<String, Object>) raw;
        throw new AssertionError("Missing captured node " + id);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
