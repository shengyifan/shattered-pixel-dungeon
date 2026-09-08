package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionArea;
import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventoryPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.ItemSlot;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.QuickSlotButton;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Pasty;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.HealthBar;
import com.shatteredpixel.shatteredpixeldungeon.ui.CharHealthIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.OptionSlider;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.RadialMenu;
import com.shatteredpixel.shatteredpixeldungeon.ui.RightClickMenu;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.ui.changelist.ChangeButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndKeyBindings;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import com.watabou.noosa.TextInput;
import com.watabou.noosa.ui.Component;
import com.watabou.input.PointerEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A semantic view of the live game's existing controls. Call only on a stable render-thread
 * boundary. The caller owns request serialization, state versions and durable auditing.
 * No game model is reflected, no UI is constructed for discovery, and no input events are sent.
 */
public final class UiBridge {
    private final Supplier<Scene> sceneSource;
    private final IdentityHashMap<Gizmo, String> identities = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Long> callbackIdentities = new IdentityHashMap<>();
    private final Map<String, Gizmo> controls = new LinkedHashMap<>();
    private final IdentityHashMap<Gizmo, ScrollPane> entries = new IdentityHashMap<>();
    private final List<Map<String, Object>> nodes = new ArrayList<>();
    private final List<Map<String, Object>> actions = new ArrayList<>();
    private Scene scene;
    private Gizmo scope;
    private CellSelector cellSelector;
    private long nextIdentity = 1;
    private long nextCallbackIdentity = 1;

    public UiBridge() { this(Game::scene); }

    UiBridge(Supplier<Scene> sceneSource) { this.sceneSource = sceneSource; }

    public List<Map<String, Object>> describeActions() {
        refresh();
        return new ArrayList<>(actions);
    }

    public Map<String, Object> describeUi() {
        refresh();
        Map<String, Object> result = map("scene", scene == null ? "none" : scene.getClass().getSimpleName(),
                "modal", scope instanceof Window || scope instanceof RightClickMenu, "controls", new ArrayList<>(nodes));
        if (cellSelector != null && scope == scene && cellSelector.listener != null) {
            String prompt = cellSelector.listener.prompt();
            result.put("cell_prompt", prompt);
            result.put("cell_input", cellAvailable());
        }
        if (scope instanceof InventoryPane) {
            result.put("item_prompt", ((InventoryPane) scope).getSelector().textPrompt());
        }
        return result;
    }

    /** A process-local signature of public control state and live callback lifetimes. */
    public String contextSignature() {
        Map<String, Object> ui = describeUi();
        return (scene == null ? "none" : id(scene)) + ":" + (scope == null ? "none" : id(scope))
                + ":" + callbackId(cellSelector == null ? null : cellSelector.listener)
                + ":" + callbackId(scope instanceof InventoryPane ? ((InventoryPane) scope).getSelector() : null)
                + ":" + ui;
    }

    /** All preflight checks are read-only: no control callback, scrolling, or hidden target probing. */
    public void validate(String action, Map<String, Object> args) {
        if (args == null) throw new IllegalArgumentException("Arguments are required");
        if (action == null) throw new IllegalArgumentException("Action is required");
        refresh();
        switch (action) {
            case "ui.activate": {
                Gizmo control = requireControl(args);
                String gesture = optionalString(args, "gesture", "click");
                if (control instanceof Button) {
                    if (!gestures((Button) control).contains(gesture)) throw new IllegalArgumentException("Unsupported activation gesture for this control");
                } else if (!(control instanceof ChangeButton || control instanceof ActionArea) || !gesture.equals("click")) {
                    throw new IllegalArgumentException("This control cannot be activated");
                }
                return;
            }
            case "ui.choose": {
                Gizmo control = requireControl(args);
                if (!(control instanceof RadialMenu)) throw new IllegalArgumentException("Not a choice control");
                int option = requiredInt(args, "option");
                if (option < 0 || option >= ((RadialMenu) control).optionLabels().length) throw new IllegalArgumentException("Option is out of range");
                optionalBoolean(args, "alternate");
                return;
            }
            case "ui.binding_slot": {
                Gizmo control = requireControl(args);
                if (!(control instanceof WndKeyBindings.BindingRow)) throw new IllegalArgumentException("Not a binding row");
                int slot = requiredInt(args, "slot");
                if (slot < 1 || slot > 3) throw new IllegalArgumentException("Binding slot is out of range");
                return;
            }
            case "ui.binding_key": {
                Gizmo control = requireControl(args);
                if (!(control instanceof WndKeyBindings.BindingInput)) throw new IllegalArgumentException("Not a binding input");
                if (!((WndKeyBindings.BindingInput) control).acceptsBindingKey(requiredInt(args, "keycode"))) {
                    throw new IllegalArgumentException("The key does not belong to this binding input");
                }
                return;
            }
            case "ui.text": {
                Gizmo control = requireControl(args);
                if (!(control instanceof TextInput)) throw new IllegalArgumentException("Not a text control");
                TextInput input = (TextInput) control;
                String text = requiredString(args, "text");
                if (input.maximumLength() > 0 && text.length() > input.maximumLength()
                        || !input.multiline() && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)) {
                    throw new IllegalArgumentException("The text does not fit this control");
                }
                if (optionalBoolean(args, "submit") && input.multiline()) {
                    throw new IllegalArgumentException("Use the displayed confirmation button for multiline text");
                }
                return;
            }
            case "ui.scroll": {
                Gizmo control = requireControl(args);
                if (!(control instanceof ScrollPane)) throw new IllegalArgumentException("Not a scroll control");
                optionalNumber(args, "x", 0); optionalNumber(args, "y", 0);
                return;
            }
            case "ui.select": {
                Gizmo control = requireControl(args);
                if (!(control instanceof Component) || entries.get(control) == null) throw new IllegalArgumentException("Not a selectable list entry");
                return;
            }
            case "ui.value": {
                Gizmo control = requireControl(args);
                if (!(control instanceof OptionSlider)) throw new IllegalArgumentException("Not a value control");
                int value = requiredInt(args, "value");
                OptionSlider slider = (OptionSlider) control;
                if (value < slider.minimumValue() || value > slider.maximumValue()) throw new IllegalArgumentException("Slider value is out of range");
                return;
            }
            case "ui.back":
                if (scene == null) throw new IllegalStateException("There is no scene");
                return;
            case "ui.reveal":
                if (scope != scene) throw new IllegalStateException("A modal interaction is active");
                if (!(scene instanceof TitleScene || scene instanceof HeroSelectScene)) throw new IllegalStateException("There are no hidden scene controls to reveal");
                return;
            case "view.zoom": {
                if (cellSelector == null || scope != scene) throw new IllegalStateException("Map view is blocked");
                int value = requiredInt(args, "zoom");
                if (value < PixelScene.minZoom || value > PixelScene.maxZoom) throw new IllegalArgumentException("Zoom is out of range");
                return;
            }
            case "view.pan":
                if (cellSelector == null || scope != scene) throw new IllegalStateException("Map view is blocked");
                optionalNumber(args, "x", 0); optionalNumber(args, "y", 0);
                return;
            case "cell.select": {
                if (!cellAvailable()) throw new IllegalStateException("Cell input is not available");
                int cell = requiredInt(args, "cell");
                if (Dungeon.level == null || cell < 0 || cell >= Dungeon.level.length()) throw new IllegalArgumentException("Cell is out of bounds");
                if (!Arrays.asList("act", "context", "examine").contains(optionalString(args, "mode", "act"))) throw new IllegalArgumentException("Unsupported cell selection mode");
                return;
            }
            case "cell.cancel":
                if (cellSelector == null || scope != scene || !GameScene.isSelectingCell()) {
                    throw new IllegalStateException("No pending cell selection");
                }
                return;
            default: throw new IllegalArgumentException("Unsupported UI action");
        }
    }

    public void execute(String action, Map<String, Object> args) {
        validate(action, args);
        switch (action) {
            case "ui.activate": {
                Gizmo control = requireControl(args);
                String gesture = optionalString(args, "gesture", "click");
                if (control instanceof Button) {
                    Button button = (Button) control;
                    if (!gestures(button).contains(gesture)) {
                        throw new IllegalArgumentException("Unsupported activation gesture for this control");
                    }
                    if (!button.activate(gesture)) {
                        throw new IllegalStateException("The control did not accept this activation");
                    }
                } else if (control instanceof ChangeButton && gesture.equals("click")) {
                    ((ChangeButton) control).activate();
                } else if (control instanceof ActionArea && gesture.equals("click")) {
                    ((ActionArea) control).activate();
                } else {
                    throw new IllegalArgumentException("This control cannot be activated");
                }
                return;
            }
            case "ui.choose": {
                Gizmo control = requireControl(args);
                if (!(control instanceof RadialMenu)) throw new IllegalArgumentException("Not a choice control");
                ((RadialMenu) control).chooseOption(requiredInt(args, "option"), optionalBoolean(args, "alternate"));
                return;
            }
            case "ui.binding_slot": {
                Gizmo control = requireControl(args);
                if (!(control instanceof WndKeyBindings.BindingRow)) throw new IllegalArgumentException("Not a binding row");
                ((WndKeyBindings.BindingRow) control).chooseBindingSlot(requiredInt(args, "slot"));
                return;
            }
            case "ui.binding_key": {
                Gizmo control = requireControl(args);
                if (!(control instanceof WndKeyBindings.BindingInput)) throw new IllegalArgumentException("Not a binding input");
                ((WndKeyBindings.BindingInput) control).chooseBindingKey(requiredInt(args, "keycode"));
                return;
            }
            case "ui.text": {
                Gizmo control = requireControl(args);
                if (!(control instanceof TextInput)) throw new IllegalArgumentException("Not a text control");
                TextInput input = (TextInput) control;
                boolean submit = optionalBoolean(args, "submit");
                if (submit && input.multiline()) {
                    throw new IllegalArgumentException("Use the displayed confirmation button for multiline text");
                }
                input.replaceText(requiredString(args, "text"));
                if (submit) input.enterPressed();
                return;
            }
            case "ui.scroll": {
                Gizmo control = requireControl(args);
                if (!(control instanceof ScrollPane)) throw new IllegalArgumentException("Not a scroll control");
                ScrollPane pane = (ScrollPane) control;
                float x = optionalNumber(args, "x", pane.content().camera.scroll.x);
                float y = optionalNumber(args, "y", pane.content().camera.scroll.y);
                pane.scrollTo(x, y);
                return;
            }
            case "ui.select": {
                Gizmo control = requireControl(args);
                ScrollPane pane = entries.get(control);
                if (pane == null || !(control instanceof Component)) {
                    throw new IllegalArgumentException("Not a selectable list entry");
                }
                pane.selectContent((Component) control);
                return;
            }
            case "ui.value": {
                Gizmo control = requireControl(args);
                if (!(control instanceof OptionSlider)) throw new IllegalArgumentException("Not a value control");
                ((OptionSlider) control).chooseValue(requiredInt(args, "value"));
                return;
            }
            case "ui.back":
                if (scope instanceof RightClickMenu) ((RightClickMenu) scope).dismiss();
                else if (scope instanceof Window) ((Window) scope).onBackPressed();
                else if (scope instanceof InventoryPane) ((InventoryPane) scope).cancelSelection();
                else if (scene != null) scene.requestBack();
                else throw new IllegalStateException("There is no scene");
                return;
            case "ui.reveal":
                if (scope != scene) throw new IllegalStateException("A modal interaction is active");
                if (scene instanceof TitleScene) ((TitleScene) scene).revealControls();
                else if (scene instanceof HeroSelectScene) ((HeroSelectScene) scene).revealControls();
                else throw new IllegalStateException("There are no hidden scene controls to reveal");
                return;
            case "view.zoom":
                if (cellSelector == null || scope != scene) throw new IllegalStateException("Map view is blocked");
                cellSelector.chooseViewZoom(requiredInt(args, "zoom"));
                return;
            case "view.pan":
                if (cellSelector == null || scope != scene) throw new IllegalStateException("Map view is blocked");
                cellSelector.shiftView(optionalNumber(args, "x", 0f), optionalNumber(args, "y", 0f));
                return;
            case "cell.select": {
                if (!cellAvailable()) throw new IllegalStateException("Cell input is not available");
                int cell = requiredInt(args, "cell");
                if (Dungeon.level == null || cell < 0 || cell >= Dungeon.level.length()) {
                    throw new IllegalArgumentException("Cell is out of bounds");
                }
                String mode = optionalString(args, "mode", "act");
                switch (mode) {
                    case "act": cellSelector.select(cell, PointerEvent.LEFT); break;
                    case "context": cellSelector.select(cell, PointerEvent.RIGHT); break;
                    case "examine": GameScene.examineCell(cell); break;
                    default: throw new IllegalArgumentException("Unsupported cell selection mode");
                }
                return;
            }
            case "cell.cancel":
                if (cellSelector == null || scope != scene) throw new IllegalStateException("No cell input to cancel");
                if (!GameScene.cancelCellSelector()) throw new IllegalStateException("No pending cell selection");
                return;
            default:
                throw new IllegalArgumentException("Unsupported UI action");
        }
    }

    private void refresh() {
        Scene current = sceneSource.get();
        if (current != scene) {
            identities.clear();
            callbackIdentities.clear();
        }
        scene = current;
        controls.clear();
        entries.clear();
        nodes.clear();
        actions.clear();
        cellSelector = null;
        scope = scene;
        if (scene == null) return;
        discover(scene);
        Gizmo window = topWindow(scene);
        if (window != null) scope = window;
        else {
            InventoryPane selecting = selectingInventory(scene);
            if (selecting != null) scope = selecting;
        }
        walk(scope, null, null, false);
        actions.add(map("action", "ui.back"));
        if (scope == scene && (scene instanceof TitleScene || scene instanceof HeroSelectScene)) {
            actions.add(map("action", "ui.reveal"));
        }
        if (cellSelector != null && scope == scene) {
            actions.add(map("action", "view.zoom", "minimum", PixelScene.minZoom, "maximum", PixelScene.maxZoom));
            actions.add(map("action", "view.pan", "arguments", Arrays.asList("x", "y"), "units", "map_view"));
        }
        if (cellAvailable()) {
            actions.add(map("action", "cell.select", "modes", Arrays.asList("act", "examine", "context"),
                    "arguments", map("cell", "integer", "mode", "optional string")));
            if (GameScene.isSelectingCell()) {
                actions.add(map("action", "cell.cancel"));
            }
        }
    }

    private void discover(Gizmo gizmo) {
        if (gizmo == null || !gizmo.exists) return;
        if (gizmo instanceof CellSelector) cellSelector = (CellSelector) gizmo;
        if (gizmo instanceof Group) {
            for (Gizmo child : ((Group) gizmo).childrenSnapshot()) discover(child);
        }
    }

    private static Gizmo topWindow(Gizmo gizmo) {
        if (!shown(gizmo)) return null;
        Gizmo result = gizmo instanceof Window || gizmo instanceof RightClickMenu ? gizmo : null;
        if (gizmo instanceof Group) {
            for (Gizmo child : ((Group) gizmo).childrenSnapshot()) {
                Gizmo next = topWindow(child);
                if (next != null) result = next;
            }
        }
        return result;
    }

    private static InventoryPane selectingInventory(Gizmo gizmo) {
        if (!shown(gizmo)) return null;
        if (gizmo instanceof InventoryPane && ((InventoryPane) gizmo).isSelecting()) return (InventoryPane) gizmo;
        if (gizmo instanceof Group) {
            for (Gizmo child : ((Group) gizmo).childrenSnapshot()) {
                InventoryPane found = selectingInventory(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void walk(Gizmo gizmo, String parentId, ScrollPane pane, boolean inUi) {
        if (!shown(gizmo)) return;
        boolean ui = inUi || gizmo instanceof Component || gizmo instanceof Window || gizmo instanceof ActionArea;
        String role = role(gizmo, pane);
        String nodeId = parentId;
        if (role != null && (ui || gizmo.parent == scene)) {
            nodeId = id(gizmo);
            controls.put(nodeId, gizmo);
            Map<String, Object> node = map("id", nodeId, "role", role, "enabled", gizmo.isActive());
            if (parentId != null) node.put("parent", parentId);
            String text = visibleText(gizmo);
            if (text != null && !text.isEmpty()) node.put("text", text);
            if (gizmo instanceof Button) {
                Button button = (Button) gizmo;
                String hover = hoverText(button);
                if (hover != null && !hover.isEmpty()) node.put("label", hover);
                if (button.keyAction() != null) node.put("shortcut_action", button.keyAction().name());
                if (button instanceof IconButton && ((IconButton) button).icon() != null) {
                    // Some controls (notably cleric spells) are visually dimmed but remain
                    // clickable to explain a failed precondition. Do not conflate this with active.
                    node.put("dimmed", ((IconButton) button).icon().am <= 0.35f);
                }
                List<String> gestures = gestures(button);
                node.put("gestures", gestures);
                if (gizmo.isActive() && !gestures.isEmpty()) addAction("ui.activate", nodeId, node, "gestures", gestures);
            } else if (gizmo instanceof ChangeButton) {
                node.put("label", ((ChangeButton) gizmo).accessibleTitle());
                if (gizmo.isActive()) addAction("ui.activate", nodeId, node, "gestures", Collections.singletonList("click"));
            } else if (gizmo instanceof ActionArea) {
                node.put("label", ((ActionArea) gizmo).accessibleLabel());
                if (gizmo.isActive()) addAction("ui.activate", nodeId, node, "gestures", Collections.singletonList("click"));
            } else if (gizmo instanceof TextInput) {
                TextInput input = (TextInput) gizmo;
                node.put("value", input.getText());
                node.put("max_length", input.maximumLength());
                node.put("multiline", input.multiline());
                if (gizmo.isActive()) addAction("ui.text", nodeId, node, "submit_supported", !input.multiline());
            } else if (gizmo instanceof OptionSlider) {
                OptionSlider slider = (OptionSlider) gizmo;
                node.put("value", slider.getSelectedValue());
                node.put("minimum", slider.minimumValue());
                node.put("maximum", slider.maximumValue());
                if (gizmo.isActive()) addAction("ui.value", nodeId, node, "range", Arrays.asList(slider.minimumValue(), slider.maximumValue()));
            } else if (gizmo instanceof ScrollPane) {
                ScrollPane scroll = (ScrollPane) gizmo;
                node.put("scroll_x", scroll.content().camera.scroll.x);
                node.put("scroll_y", scroll.content().camera.scroll.y);
                node.put("content_width", scroll.content().width());
                node.put("content_height", scroll.content().height());
                node.put("viewport_width", scroll.width());
                node.put("viewport_height", scroll.height());
                if (gizmo.isActive()) addAction("ui.scroll", nodeId, node, "arguments", Arrays.asList("x", "y"));
            } else if (role.equals("entry") && pane != null) {
                entries.put(gizmo, pane);
                if (gizmo.isActive()) addAction("ui.select", nodeId, node, "list", id(pane));
            }
            if (gizmo instanceof RadialMenu) {
                List<String> options = Arrays.asList(((RadialMenu) gizmo).optionLabels());
                node.put("options", options);
                node.put("option_index_base", 0);
                if (gizmo.isActive()) addAction("ui.choose", nodeId, node, "options", options);
            }
            if (gizmo instanceof WndKeyBindings.BindingRow && gizmo.isActive()) {
                addAction("ui.binding_slot", nodeId, node, "slots", Arrays.asList(1, 2, 3));
            }
            if (gizmo instanceof WndKeyBindings.BindingInput && gizmo.isActive()) {
                addAction("ui.binding_key", nodeId, node, "arguments", Collections.singletonList("keycode"));
            }
            if (gizmo instanceof CheckBox) node.put("checked", ((CheckBox) gizmo).checked());
            if (gizmo instanceof HealthBar) {
                int[] pixels = ((HealthBar) gizmo).renderedPixelWidths();
                if (pixels.length == 3 && pixels[0] > 0) {
                    node.put("total_pixels", pixels[0]);
                    node.put("health_pixels", pixels[1]);
                    node.put("health_and_shield_pixels", pixels[2]);
                    node.put("measurement", "rendered_pixels");
                }
                // Association only; values above come from laid-out visuals, never HP/HT.
                if (gizmo instanceof CharHealthIndicator && currentlyVisible(((CharHealthIndicator) gizmo).target())) {
                    node.put("cell", ((CharHealthIndicator) gizmo).target().pos);
                } else if (gizmo instanceof TargetHealthIndicator && currentlyVisible(((TargetHealthIndicator) gizmo).target())) {
                    node.put("cell", ((TargetHealthIndicator) gizmo).target().pos);
                }
            }
            nodes.add(node);
        }
        // A text block already carries its original complete rendered string.
        if (gizmo instanceof RenderedTextBlock) return;
        if (gizmo instanceof Group) {
            ScrollPane nextPane = gizmo instanceof ScrollPane ? (ScrollPane) gizmo : pane;
            for (Gizmo child : ((Group) gizmo).childrenSnapshot()) walk(child, nodeId, nextPane, ui);
        }
    }

    private void addAction(String action, String control, Map<String, Object> node, String key, Object value) {
        Map<String, Object> result = map("action", action, "control", control, key, value);
        if (node.containsKey("label")) result.put("label", node.get("label"));
        else if (node.containsKey("text")) result.put("label", node.get("text"));
        actions.add(result);
    }

    private static String role(Gizmo gizmo, ScrollPane pane) {
        if (gizmo instanceof Button || gizmo instanceof ChangeButton || gizmo instanceof ActionArea) return "button";
        if (gizmo instanceof TextInput) return "text_input";
        if (gizmo instanceof OptionSlider) return "slider";
        if (gizmo instanceof ScrollPane) return "scroll";
        if (gizmo instanceof Window) return "window";
        if (gizmo instanceof RightClickMenu) return "context_menu";
        if (gizmo instanceof HealthBar) return "health_bar";
        if (gizmo instanceof RenderedTextBlock || gizmo instanceof BitmapText) return "text";
        if (pane != null && gizmo instanceof Component && hasMethod(gizmo.getClass(), "onClick", float.class, float.class)) {
            // Compound rows such as changelog groups dispatch to their individual buttons.
            for (Gizmo child : ((Component) gizmo).childrenSnapshot()) {
                if (child instanceof Button || child instanceof ChangeButton) return null;
            }
            return "entry";
        }
        return null;
    }

    private static String visibleText(Gizmo gizmo) {
        if (gizmo instanceof RenderedTextBlock) return ((RenderedTextBlock) gizmo).text();
        if (gizmo instanceof BitmapText) return ((BitmapText) gizmo).text();
        if (gizmo instanceof StyledButton) return ((StyledButton) gizmo).text();
        if (gizmo instanceof TextInput) return null;
        StringBuilder result = new StringBuilder();
        if (gizmo instanceof Group) {
            for (Gizmo child : ((Group) gizmo).childrenSnapshot()) {
                if (!shown(child)) continue;
                if (child instanceof RenderedTextBlock || child instanceof BitmapText) {
                    String text = visibleText(child);
                    if (text != null && !text.isEmpty()) {
                        if (result.length() > 0) result.append('\n');
                        result.append(text);
                    }
                }
            }
        }
        return result.toString();
    }

    private static List<String> gestures(Button button) {
        List<String> result = new ArrayList<>();
        String[] gestures = {"click", "right", "middle", "long"};
        String[] callbacks = {"onClick", "onRightClick", "onMiddleClick", "onLongClick"};
        for (int i = 0; i < gestures.length; i++) {
            for (Class<?> type = button.getClass(); type != null && type != Button.class; type = type.getSuperclass()) {
                try {
                    type.getDeclaredMethod(callbacks[i]);
                    result.add(gestures[i]);
                    break;
                } catch (NoSuchMethodException ignored) { }
            }
        }
        return result;
    }

    private static String hoverText(Button button) {
        if (button instanceof ItemSlot && ((ItemSlot) button).displayedItem() instanceof Pasty) {
            // Preserve custom hover overrides (e.g. WndQuickBag deliberately has no tooltip).
            // The standard ItemSlot path and QuickSlot's non-empty fallback both call name(),
            // which would populate Holiday.cached for Pasty during a query.
            for (Class<?> type = button.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    type.getDeclaredMethod("hoverText");
                    if (type == ItemSlot.class || type.getEnclosingClass() == QuickSlotButton.class) {
                        return Messages.titleCase(PlayerObservation.displayItemName(((ItemSlot) button).displayedItem()));
                    }
                    break;
                } catch (NoSuchMethodException ignored) { }
            }
        }
        return button.accessibleHoverText();
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... arguments) {
        for (; type != null && type != Component.class; type = type.getSuperclass()) {
            try { type.getDeclaredMethod(name, arguments); return true; }
            catch (NoSuchMethodException ignored) { }
        }
        return false;
    }

    private boolean cellAvailable() {
        return scene instanceof GameScene && scope == scene && cellSelector != null && cellSelector.enabled
                && Dungeon.hero != null && Dungeon.hero.ready && !GameScene.interfaceBlockingHero();
    }

    private static boolean currentlyVisible(Char target) {
        return target != null && Dungeon.level != null && target.pos >= 0
                && Dungeon.level.heroFOV != null && target.pos < Dungeon.level.heroFOV.length
                && Dungeon.level.heroFOV[target.pos] && target.sprite != null && target.sprite.visible;
    }

    private Gizmo requireControl(Map<String, Object> args) {
        Gizmo control = controls.get(requiredString(args, "control"));
        if (control == null || !shown(control) || !control.isActive()) {
            throw new IllegalStateException("The control is stale, hidden, disabled, or blocked");
        }
        return control;
    }

    private static boolean shown(Gizmo gizmo) {
        return gizmo instanceof ActionArea ? ((ActionArea) gizmo).semanticVisible()
                : gizmo != null && gizmo.exists && gizmo.isVisible();
    }

    private String id(Gizmo gizmo) {
        String value = identities.get(gizmo);
        if (value == null) { value = "ui-" + nextIdentity++; identities.put(gizmo, value); }
        return value;
    }

    private String callbackId(Object callback) {
        if (callback == null) return "none";
        Long value = callbackIdentities.get(callback);
        if (value == null) { value = nextCallbackIdentity++; callbackIdentities.put(callback, value); }
        return Long.toString(value);
    }

    private static String requiredString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected string argument: " + key);
        return (String) value;
    }

    private static String optionalString(Map<String, Object> args, String key, String fallback) {
        return args.containsKey(key) ? requiredString(args, key) : fallback;
    }

    private static int requiredInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Expected integer argument: " + key);
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Expected integer argument: " + key);
        }
        return (int) number;
    }

    private static float optionalNumber(Map<String, Object> args, String key, float fallback) {
        if (!args.containsKey(key)) return fallback;
        Object value = args.get(key);
        if (!(value instanceof Number) || !Float.isFinite(((Number) value).floatValue())) {
            throw new IllegalArgumentException("Expected finite numeric argument: " + key);
        }
        return ((Number) value).floatValue();
    }

    private static boolean optionalBoolean(Map<String, Object> args, String key) {
        if (!args.containsKey(key)) return false;
        Object value = args.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean argument: " + key);
        return (Boolean) value;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}
