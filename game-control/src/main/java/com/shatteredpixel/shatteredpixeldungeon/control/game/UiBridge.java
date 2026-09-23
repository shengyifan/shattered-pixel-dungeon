package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionArea;
import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventoryPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventorySlot;
import com.shatteredpixel.shatteredpixeldungeon.ui.ItemSlot;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.QuickSlotButton;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Pasty;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.ui.HealthBar;
import com.shatteredpixel.shatteredpixeldungeon.ui.CharHealthIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.OptionSlider;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedStatus;
import com.shatteredpixel.shatteredpixeldungeon.ui.Banner;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameLog;
import com.shatteredpixel.shatteredpixeldungeon.ui.CurrencyIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.RadialMenu;
import com.shatteredpixel.shatteredpixeldungeon.ui.RightClickMenu;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.ui.changelist.ChangeButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndKeyBindings;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoItem;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import com.watabou.noosa.TextInput;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ui.Component;
import com.watabou.input.PointerEvent;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.game.util.WeakIdentityRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.lang.ref.WeakReference;

/**
 * A semantic view of the live game's existing controls. Call only on a stable render-thread
 * boundary. The caller owns request serialization, state versions and durable auditing.
 * No game model is reflected, no UI is constructed for discovery, and no input events are sent.
 */
public final class UiBridge {
    private final Supplier<Scene> sceneSource;
    private final WeakIdentityRegistry<String> identities = new WeakIdentityRegistry<>();
    private final WeakIdentityRegistry<Long> callbackIdentities = new WeakIdentityRegistry<>();
    private final Map<String, Gizmo> controls = new LinkedHashMap<>();
    private final IdentityHashMap<Gizmo, ScrollPane> entries = new IdentityHashMap<>();
    private final List<Map<String, Object>> nodes = new ArrayList<>();
    private final List<Map<String, Object>> actions = new ArrayList<>();
    // Visible GUI values also protect choices whose provenance is temporarily unavailable.
    // This process-local signature input is never exported as public text or a resource token.
    private final Map<String,Map<String,Object>> visibleTextSignature = new LinkedHashMap<>();
    private Scene scene;
    private Gizmo scope;
    private CellSelector cellSelector;
    private long nextIdentity = 1;
    private long nextCallbackIdentity = 1;
    private final WeakIdentityRegistry<DrawnEvidence> drawnEvidence=new WeakIdentityRegistry<>();
    private final WeakIdentityRegistry<Long> drawingIdentities=new WeakIdentityRegistry<>();
    private long nextDrawingIdentity=1;
    private WeakReference<Scene> drawnScene=new WeakReference<>(null);
    private WeakReference<Gizmo> drawnScope=new WeakReference<>(null);
    private WeakReference<Camera> drawnCamera=new WeakReference<>(null);
    private boolean hadDrawnCamera;
    private boolean readingIntent;

    private static final class DrawnEvidence {
        final List<Long> bindings;
        final Map<String,Object> display,text,textIntent,statusIntent;
        final String rawText;
        final boolean textVisible,clipped;
        DrawnEvidence(List<Long> bindings,Map<String,Object> display,Map<String,Object> text,Map<String,Object> textIntent,
                      Map<String,Object> statusIntent,String rawText,boolean textVisible,boolean clipped){
            this.bindings=bindings;this.display=display;this.text=text;this.textIntent=textIntent;this.statusIntent=statusIntent;
            this.rawText=rawText;this.textVisible=textVisible;this.clipped=clipped;
        }
    }

    public UiBridge() { this(Game::scene); }

    UiBridge(Supplier<Scene> sceneSource) { this.sceneSource = sceneSource; }

    /** Only display fields are frozen here: no control callback, hover label, action discovery or model getter. */
    public void captureDrawnEvidence(){
        drawnEvidence.clear();
        Scene current=sceneSource.get();drawnScene=new WeakReference<>(current);
        drawnCamera=new WeakReference<>(Camera.main);hadDrawnCamera=Camera.main!=null;
        Gizmo currentScope=displayScope(current);drawnScope=new WeakReference<>(currentScope);
        if(currentScope!=null)captureDrawn(currentScope);
    }

    private void captureDrawn(Gizmo owner){
        if(!shown(owner)||owner instanceof com.watabou.noosa.particles.Emitter)return;
        if(!(owner instanceof FloatingText)&&captureCandidate(owner)){
            DrawnEvidence evidence=readDisplay(owner);drawnEvidence.put(owner,evidence);
        }
        if(owner instanceof RenderedTextBlock)return;
        if(owner instanceof Group)for(Gizmo child:((Group)owner).childrenSnapshot())if(child!=null)captureDrawn(child);
    }

    private static boolean captureCandidate(Gizmo owner){
        return owner instanceof RenderedStatus||owner instanceof HealthBar||owner instanceof RenderedTextBlock
                ||owner instanceof BitmapText||owner instanceof Component||owner instanceof ActionArea;
    }

    private DrawnEvidence readDisplay(Gizmo owner){
        Map<String,Object> display=new LinkedHashMap<>(),textFields=new LinkedHashMap<>(),intent=new LinkedHashMap<>();
        RenderedTextBlock.VisibleText fragment=owner instanceof RenderedTextBlock?((RenderedTextBlock)owner).visibleTextFragment():null;
        String text=fragment==null?visibleText(owner):fragment.visible?fragment.text:null;
        boolean clipped=fragment!=null?fragment.clipped:clippedDirectText(owner);
        boolean textVisible=fragment==null||fragment.visible;
        if(text!=null&&(!text.isEmpty()||clipped))textFields.put("text",TextProvenance.INSTANCE.capture(owner,text,clipped));
        if(fragment!=null&&fragment.visible)textFields.putAll(fragment.styleData());
        else if(owner instanceof BitmapText&&!clipped)textFields.put("color",((BitmapText)owner).displayedTextColor());
        if(clipped)textFields.put("clipped",true);
        display.putAll(textFields);
        if(owner instanceof RenderedStatus){
            display.putAll(((RenderedStatus)owner).renderedStatus());
            intent.putAll(((RenderedStatus)owner).intentStatus());
        }
        if(owner instanceof IconButton&&((IconButton)owner).icon()!=null){
            boolean dimmed=((IconButton)owner).icon().am<=0.35f;
            display.put("dimmed",dimmed);intent.put("dimmed",dimmed);
        }
        if(owner instanceof HealthBar){
            Map<String,Object> bars=barFields((HealthBar)owner);display.putAll(bars);intent.putAll(bars);
        }
        Map<String,Object> frozenText=freezeEvidence(PublicEnglishProjection.freeze(textFields));
        Map<String,Object> textIntent=frozenText;
        if(owner instanceof RenderedTextBlock&&((RenderedTextBlock)owner).hasAnimatedColor()){
            Map<String,Object> meaning=new LinkedHashMap<>(frozenText);meaning.remove("color");
            if(meaning.get("styles") instanceof List){
                List<Object> styles=new ArrayList<>();
                for(Object raw:(List<?>)meaning.get("styles")){
                    Map<String,Object> style=new LinkedHashMap<>((Map<String,Object>)raw);style.remove("color");styles.add(style);
                }
                meaning.put("styles",styles);
            }
            textIntent=freezeEvidence(meaning);
        }
        return new DrawnEvidence(bindingFingerprint(owner),freezeEvidence(PublicEnglishProjection.freeze(display)),
                frozenText,textIntent,freezeEvidence(PublicEnglishProjection.freeze(intent)),text,textVisible,clipped);
    }

    /** Opaque provenance/presentation maps are evidence too: no mutable caller-owned extension survives. */
    @SuppressWarnings("unchecked") private static <T>T freezeEvidence(T value){
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Byte||value instanceof Short
                ||value instanceof Integer||value instanceof Long||value instanceof java.math.BigInteger||value instanceof java.math.BigDecimal)return value;
        if(value instanceof Float||value instanceof Double){
            if(!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Non-finite drawn UI evidence");
            return value;
        }
        if(value instanceof Map){
            Map<String,Object> copy=new LinkedHashMap<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){
                if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("Drawn UI evidence keys must be strings");
                copy.put((String)entry.getKey(),freezeEvidence(entry.getValue()));
            }
            return (T)Collections.unmodifiableMap(copy);
        }
        if(value instanceof List){List<Object> copy=new ArrayList<>();for(Object child:(List<?>)value)copy.add(freezeEvidence(child));return (T)Collections.unmodifiableList(copy);}
        throw new IllegalArgumentException("Drawn UI evidence must contain immutable JSON values only");
    }

    private static Map<String,Object> barFields(HealthBar bar){
        int[] pixels=bar.renderedPixelWidths();
        return pixels.length==3&&pixels[0]>0?map("total_pixels",pixels[0],"health_pixels",pixels[1],
                "health_and_shield_pixels",pixels[2],"measurement","rendered_pixels"):Collections.emptyMap();
    }

    private long drawingIdentity(Object owner){
        if(owner==null)return 0;
        Long identity=drawingIdentities.get(owner);
        if(identity==null){identity=nextDrawingIdentity++;drawingIdentities.put(owner,identity);}
        return identity;
    }

    private List<Long> bindingFingerprint(Gizmo owner){
        List<Long> result=new ArrayList<>();appendBindings(owner,result);return Collections.unmodifiableList(result);
    }
    private void appendBindings(Gizmo owner,List<Long> result){
        if(owner==null){result.add(0L);return;}
        result.add(drawingIdentity(owner));result.add(owner.observationLifetime());
        result.add(drawingIdentity(owner.parent));result.add(drawingIdentity(com.shatteredpixel.shatteredpixeldungeon.ui.RenderedAppearance.camera(owner)));
        result.add(owner.exists?1L:0L);result.add(owner.visible?1L:0L);
        if(owner instanceof Group){
            List<Gizmo> children=new ArrayList<>();
            for(Gizmo child:((Group)owner).childrenSnapshot())if(child!=null&&!(child instanceof com.watabou.noosa.particles.Emitter))children.add(child);
            result.add((long)children.size());for(Gizmo child:children)appendBindings(child,result);
        }
        else result.add(-1L);
    }

    private static Gizmo displayScope(Scene current){
        if(current==null)return null;
        Gizmo window=topWindow(current);if(window!=null)return window;
        InventoryPane selecting=selectingInventory(current);return selecting==null?current:selecting;
    }

    private boolean matchingDraw(){
        Scene current=sceneSource.get();Camera camera=drawnCamera.get();
        return current!=null&&drawnScene.get()==current&&drawnScope.get()==displayScope(current)
                &&(hadDrawnCamera?camera!=null&&camera==Camera.main:Camera.main==null);
    }

    private DrawnEvidence evidence(Gizmo owner){
        DrawnEvidence value=matchingDraw()?drawnEvidence.get(owner):null;
        if(value!=null&&(!shown(owner)||!value.bindings.equals(bindingFingerprint(owner)))){drawnEvidence.remove(owner);return null;}
        return value;
    }

    private void forgetHidden(Gizmo owner){
        if(owner==null)return;drawnEvidence.remove(owner);
        if(owner instanceof Group)for(Gizmo child:((Group)owner).childrenSnapshot())forgetHidden(child);
    }

    /** Meaningful changed display must survive a real draw; decorative animation never holds an input. */
    public boolean drawnIntentReady(){
        if(!matchingDraw())return false;
        return drawnIntentReady(displayScope(sceneSource.get()));
    }
    private boolean drawnIntentReady(Gizmo owner){
        if(owner==null)return true;
        if(owner instanceof com.watabou.noosa.particles.Emitter)return true;
        if(!shown(owner)){forgetHidden(owner);return true;}
        boolean passive=owner instanceof FloatingText||owner instanceof Banner||owner instanceof GameLog||owner instanceof CurrencyIndicator||isGameLogText(owner)
                ||owner instanceof BitmapText&&owner.parent instanceof CurrencyIndicator;
        if(!passive&&captureCandidate(owner)){
            DrawnEvidence live=readDisplay(owner),drawn=evidence(owner);
            boolean informative=!live.statusIntent.isEmpty()||!live.textIntent.isEmpty();
            if(informative&&(drawn==null||!drawn.statusIntent.equals(live.statusIntent)||!drawn.textIntent.equals(live.textIntent)))return false;
        }
        if(owner instanceof RenderedTextBlock)return true;
        if(owner instanceof Group)for(Gizmo child:((Group)owner).childrenSnapshot())if(!drawnIntentReady(child))return false;
        return true;
    }

    public List<Map<String, Object>> describeActions() {
        refresh();
        return PublicEnglishProjection.copy(new ArrayList<>(actions));
    }

    public List<Map<String,Object>> frozenActions() {
        refresh();
        return PublicEnglishProjection.freeze(new ArrayList<>(actions));
    }

    public Map<String,Object> describeUi() { return PublicEnglishProjection.copy(frozenUi()); }

    public Map<String, Object> frozenUi() { return frozenUi(Collections.emptyMap()); }

    /** The supplied public inventory was captured at this same stable render-thread boundary. */
    public Map<String, Object> frozenUi(Map<String, Object> observation) {
        refresh();
        Map<String, Object> result = map("scene", scene == null ? "none" : scene.getClass().getSimpleName(),
                "modal", scope instanceof Window || scope instanceof RightClickMenu, "controls", new ArrayList<>(nodes),
                "inspected_item",inspectedItemKnowledge());
        if(scene!=null&&Gdx.graphics!=null) {
            Languages selected=Boolean.TRUE.equals(new ClassInitializationProbe().initialized(Messages.class))?Messages.selectedLanguage():null;
            result.put("display",map("language",selected==null?null:selected.code(),"fullscreen",Gdx.graphics.isFullscreen()));
        }
        if (cellSelector != null && scope == scene && cellSelector.listener != null) {
            String prompt = cellSelector.listener.prompt();
            result.put("cell_prompt", prompt);
            result.put("cell_input", cellAvailable());
        }
        if (scope instanceof InventoryPane) {
            result.put("item_prompt", ((InventoryPane) scope).getSelector().textPrompt());
        }
        return PublicEnglishProjection.freeze(projectionHints(observation).attach(result));
    }

    private UiProjectionHints projectionHints(Map<String, Object> observation) {
        IdentityHashMap<Item, String> locators = publicItemLocators(observation);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) byId.put((String) node.get("id"), node);
        Map<String, UiProjectionHints.Node> hints = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String nodeId = (String) node.get("id");
            Gizmo control = controls.get(nodeId);
            List<String> owned = new ArrayList<>();
            // visibleText only aggregates direct text children of these groups. Text blocks and
            // text inputs have their own rendering rules and cannot claim their internal children.
            if (control instanceof Group && !(control instanceof RenderedTextBlock)
                    && !(control instanceof TextInput)) {
                for (Gizmo child : ((Group) control).childrenSnapshot()) {
                    if (!(child instanceof RenderedTextBlock || child instanceof BitmapText) || !shown(child)) continue;
                    String childId = identities.get(child);
                    Map<String, Object> childNode = byId.get(childId);
                    if (childNode != null && nodeId.equals(childNode.get("parent")) && childNode.containsKey("text"))
                        owned.add(childId);
                }
            }
            boolean empty = "text".equals(node.get("role")) && !node.containsKey("text")
                    && !Boolean.TRUE.equals(node.get("clipped"));
            String locator = null;
            Map<String, String> display = new LinkedHashMap<>();
            if (control instanceof ItemSlot) {
                ItemSlot slot = (ItemSlot) control;
                empty = emptyItemPlaceholder(slot);
                locator = locators.get(slot.displayedItem());
                for (Map.Entry<String, BitmapText> entry : slot.renderedTextComponents().entrySet()) {
                    BitmapText child = entry.getValue();
                    String childId = identities.get(child);
                    Map<String, Object> childNode = byId.get(childId);
                    if (shown(child) && childNode != null && nodeId.equals(childNode.get("parent"))
                            && childNode.containsKey("text")) display.put(entry.getKey(), childId);
                }
            }
            if (empty || !owned.isEmpty() || locator != null || !display.isEmpty())
                hints.put(nodeId, new UiProjectionHints.Node(empty, owned, locator, display));
        }
        return new UiProjectionHints(hints);
    }

    /** Only the fixed sidebar grid's empty inventory slots lack independent capacity information. */
    static boolean emptyItemPlaceholder(ItemSlot slot) {
        // WndBag creates one slot per remaining capacity. Unknown containers must retain
        // the same conservative behavior even when the slot's own pixels are decorative.
        return slot.emptyRenderedPlaceholder()
                && (!(slot instanceof InventorySlot) || slot.parent != null && slot.parent.getClass() == InventoryPane.class);
    }

    /** Match identities only among locators already exposed by this exact public inventory. */
    private static IdentityHashMap<Item, String> publicItemLocators(Map<String, Object> observation) {
        IdentityHashMap<Item, String> locators = new IdentityHashMap<>();
        Object inventory = observation.get("inventory");
        if (inventory instanceof List) for (Object raw : (List<?>) inventory) {
            if (!(raw instanceof Map) || !(((Map<?, ?>) raw).get("locator") instanceof String)) continue;
            String locator = (String) ((Map<?, ?>) raw).get("locator");
            Item item = PlayerObservation.resolveItem(Dungeon.hero, locator);
            if (item != null) locators.put(item, locators.containsKey(item) ? null : locator);
        }
        return locators;
    }

    private Map<String,Object> inspectedItemKnowledge() {
        if(!(scope instanceof WndInfoItem))return null;
        WndInfoItem window=(WndInfoItem)scope;
        Map<String,Object> knowledge=PlayerObservation.inspectedItemKnowledge(window.inspectedItem(),window.inspectedLevelKnown());
        if(knowledge!=null)knowledge.put("control",id(window));
        return knowledge;
    }

    /** A process-local signature of public control state and live callback lifetimes. */
    public String contextSignature() {
        return signature(frozenUi());
    }

    /**
     * Conservative intent signature. Floating combat/loot text and passive currency notices
     * remain in every observation, but their appearance/expiry does not change available input.
     * Keep all other data, including health bars and enabled flags, until independently audited.
     * The coordinator must combine this with world state and real input generations, and must
     * not also hash the unfiltered public UI into the same intent version.
     */
    public String intentSignature() {
        Map<String,Object> ui;
        readingIntent=true;
        try {ui=new LinkedHashMap<>(frozenUi());} finally {readingIntent=false;}
        List<Map<String, Object>> retained = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            Gizmo control=controls.get(node.get("id"));
            // CurrencyIndicator's two direct bitmap children only display the current totals.
            // World gold/energy remain protected by the coordinator's decision-state signature.
            // Do not exempt the Inventory button, other numeric text, or nested input controls.
            boolean currencyNotice=control instanceof BitmapText&&control.parent instanceof CurrencyIndicator;
            if (!(control instanceof FloatingText)&&!(control instanceof Banner)&&!isGameLogText(control)&&!currencyNotice) {
                Map<String,Object> decisionNode=node;
                if(control!=null&&captureCandidate(control)) {
                    decisionNode=new LinkedHashMap<>(node);
                    DrawnEvidence drawn=drawnEvidence.get(control);
                    if(drawn!=null)for(String key:drawn.display.keySet())decisionNode.remove(key);
                    DrawnEvidence live=readDisplay(control);
                    for(String key:live.display.keySet())decisionNode.remove(key);
                    decisionNode.putAll(live.textIntent);decisionNode.putAll(live.statusIntent);
                }
                retained.add(decisionNode);
            }
        }
        ui.put("controls", retained);
        return signature(ui);
    }

    private String signature(Map<String, Object> ui) {
        Map<String,Object> visibleValues=new LinkedHashMap<>();
        for(Object value:(List<?>)ui.get("controls"))if(value instanceof Map) {
            Object id=((Map<?,?>)value).get("id");
            if(visibleTextSignature.containsKey(id))visibleValues.put((String)id,visibleTextSignature.get(id));
        }
        return (scene == null ? "none" : id(scene)) + ":" + (scope == null ? "none" : id(scope))
                + ":" + callbackId(cellSelector == null ? null : cellSelector.listener)
                + ":" + callbackId(scope instanceof InventoryPane ? ((InventoryPane) scope).getSelector() : null)
                + ":" + PublicEnglishProjection.semantics(ui) + ":" + visibleValues;
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
                    // Native Button handling suppresses the subsequent click when a long
                    // press is not handled. It is a completed no-op, not an execution failure.
                    button.activate(gesture);
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
        refreshControls();
    }

    private void refreshControls() {
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
        visibleTextSignature.clear();
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
        // Laid-out text contains rendered leaves, not controls or windows. Calling
        // Visual.isVisible on words would populate camera caches during observation.
        if (gizmo instanceof RenderedTextBlock) return result;
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
        if (gizmo instanceof RenderedTextBlock) return null;
        if (gizmo instanceof Group) {
            for (Gizmo child : ((Group) gizmo).childrenSnapshot()) {
                InventoryPane found = selectingInventory(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void walk(Gizmo gizmo, String parentId, ScrollPane pane, boolean inUi) {
        if (!shown(gizmo)) {forgetHidden(gizmo);return;}
        RenderedTextBlock.VisibleText floatingFragment=floatingFragment(gizmo);
        if(gizmo instanceof FloatingText&&floatingFragment==null
                &&((FloatingText)gizmo).displayedAppearance()==null)return;
        DrawnEvidence drawn=gizmo instanceof FloatingText?null:readingIntent&&captureCandidate(gizmo)?readDisplay(gizmo):evidence(gizmo);
        if(gizmo instanceof RenderedTextBlock&&!(gizmo instanceof FloatingText)&&(drawn==null||!drawn.textVisible))return;
        boolean ui = inUi || gizmo instanceof Component || gizmo instanceof Window || gizmo instanceof ActionArea;
        String role = role(gizmo, pane);
        String nodeId = parentId;
        if (role != null && (ui || gizmo.parent == scene)) {
            nodeId = id(gizmo);
            controls.put(nodeId, gizmo);
            Map<String, Object> node = map("id", nodeId, "role", role, "enabled", gizmo.isActive());
            if (parentId != null) node.put("parent", parentId);
            Map<String,Object> visibleValues=new LinkedHashMap<>();
            visibleTextSignature.put(nodeId,visibleValues);
            String text = floatingFragment!=null?floatingFragment.text:drawn==null?null:drawn.rawText;
            boolean clipped=floatingFragment != null && floatingFragment.clipped||drawn!=null&&drawn.clipped;
            // Match the public text boundary: null and an unclipped empty string both
            // mean no visible text. Native label initialization must not expire intent.
            String liveText=visibleText(gizmo);boolean liveClipped=clippedDirectText(gizmo);
            if(liveText!=null&&(!liveText.isEmpty()||liveClipped))visibleValues.put("text",gizmo instanceof BitmapText&&liveClipped?"clipped_bitmap":liveText);
            if (gizmo instanceof FloatingText) {
                if(text!=null&&(!text.isEmpty()||clipped))node.put("text",TextProvenance.INSTANCE.capture(gizmo,text,clipped));
                Map<String,Object> appearance=((FloatingText)gizmo).displayedAppearance();
                if(appearance!=null) node.putAll(appearance);
                if(floatingFragment==null&&appearance!=null&&appearance.containsKey("icon"))node.put("presentation","floating_text");
            } else if(drawn!=null)node.putAll(drawn.display);
            if(clipped)node.put("clipped",true);
            if(floatingFragment!=null&&!floatingFragment.clipped)
                node.put("presentation","floating_text");
            if(floatingFragment!=null&&floatingFragment.clipped)node.put("clipped",true);
            if (gizmo instanceof Button) {
                Button button = (Button) gizmo;
                String label = buttonLabel(button);
                if (label != null && !label.isEmpty()) node.put("label", label);
                if (button.keyAction() != null) node.put("shortcut_action", button.keyAction().name());
                // Dimmed appearance comes from the completed draw; active/ops remain live capabilities.
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
                node.put("value", TextProvenance.INSTANCE.capture(null, Messages.userText(input.getText()), false));
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
            if (gizmo instanceof WndKeyBindings.BindingRow) {
                node.put("binding_slots",Arrays.asList(1, 2, 3));
                if(gizmo.isActive())addAction("ui.binding_slot", nodeId, node, "slots", Arrays.asList(1, 2, 3));
            }
            if (gizmo instanceof WndKeyBindings.BindingInput) {
                node.put("binding_input",true);
                if(gizmo.isActive())addAction("ui.binding_key", nodeId, node, "arguments", Collections.singletonList("keycode"));
            }
            if (gizmo instanceof CheckBox) node.put("checked", ((CheckBox) gizmo).checked());
            if (gizmo instanceof HealthBar) {
                // Association only; values above come from laid-out visuals, never HP/HT.
                if (gizmo instanceof CharHealthIndicator && currentlyVisible(((CharHealthIndicator) gizmo).target())) {
                    node.put("cell", ((CharHealthIndicator) gizmo).target().pos);
                } else if (gizmo instanceof TargetHealthIndicator && currentlyVisible(((TargetHealthIndicator) gizmo).target())) {
                    node.put("cell", ((TargetHealthIndicator) gizmo).target().pos);
                }
            }
            for(String field:Arrays.asList("label","options"))if(node.containsKey(field))visibleValues.put(field,node.get(field));
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
        if (gizmo instanceof RenderedStatus) return "status";
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
        if(gizmo instanceof FloatingText) {
            RenderedTextBlock.VisibleText fragment=floatingFragment(gizmo);
            return fragment==null?null:fragment.text;
        }
        RenderedTextBlock.VisibleText logFragment = gameLogFragment(gizmo);
        if (logFragment != null) return logFragment.visible ? logFragment.text : null;
        if (gizmo instanceof RenderedTextBlock) {
            RenderedTextBlock.VisibleText fragment=((RenderedTextBlock)gizmo).visibleTextFragment();
            return fragment.visible ? fragment.text : null;
        }
        if (gizmo instanceof BitmapText) return clippedDirectText(gizmo) ? "" : ((BitmapText) gizmo).text();
        if (gizmo instanceof TextInput) return null;
        String result = Messages.literal("");
        if (gizmo instanceof Group) {
            for (Gizmo child : ((Group) gizmo).childrenSnapshot()) {
                if (!shown(child)) continue;
                if (child instanceof RenderedTextBlock || child instanceof BitmapText) {
                    String text = visibleText(child);
                    if (text != null && !text.isEmpty()) {
                        if (result.length() > 0) result = Messages.concat(result, '\n');
                        result = Messages.concat(result, text);
                    }
                }
            }
        }
        return result;
    }

    private static boolean clippedDirectText(Gizmo gizmo) {
        if(gizmo instanceof RenderedTextBlock)return ((RenderedTextBlock)gizmo).visibleTextFragment().clipped;
        if(gizmo instanceof BitmapText) {
            BitmapText text=(BitmapText)gizmo;
            com.watabou.noosa.Camera camera=null;
            for(Gizmo node=gizmo;node!=null&&camera==null;node=node.parent)camera=node.camera;
            Camera.DrawnTransform transform=camera!=null&&camera.scroll!=null?camera.observedTransform():null;
            return transform==null||text.angle!=0||text.origin.x!=0||text.origin.y!=0
                    ||text.x<transform.scrollX||text.y<transform.scrollY
                    ||text.x+text.width()>transform.scrollX+transform.width||text.y+text.height()>transform.scrollY+transform.height;
        }
        if(gizmo instanceof Group)for(Gizmo child:((Group)gizmo).childrenSnapshot())
            if(shown(child)&&(child instanceof RenderedTextBlock||child instanceof BitmapText)&&clippedDirectText(child))return true;
        return false;
    }

    private static RenderedTextBlock.VisibleText floatingFragment(Gizmo gizmo) {
        if(!(gizmo instanceof FloatingText))return null;
        RenderedTextBlock.VisibleText text=((FloatingText)gizmo).displayedText();
        return text!=null&&text.visible&&!text.text.isEmpty()?text:null;
    }

    private static RenderedTextBlock.VisibleText gameLogFragment(Gizmo gizmo) {
        return isGameLogText(gizmo) ? ((RenderedTextBlock) gizmo).visibleTextFragment() : null;
    }
    private static boolean isGameLogText(Gizmo gizmo) {
        if (!(gizmo instanceof RenderedTextBlock)) return false;
        for (Gizmo parent = gizmo.parent; parent != null; parent = parent.parent)
            if (parent instanceof GameLog) return true;
        return false;
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

    private static String buttonLabel(Button button) {
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
        return button.accessibleLabel();
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
        if(gizmo instanceof BitmapText) {
            BitmapText text=(BitmapText)gizmo;
            if(!Float.isFinite(text.am+text.aa)||text.am+text.aa<=0)return false;
        }
        return gizmo instanceof ActionArea ? ((ActionArea) gizmo).semanticVisible()
                : gizmo != null && gizmo.exists && gizmo.isVisible();
    }

    private String id(Gizmo gizmo) {
        String value = identities.get(gizmo);
        if (value == null) { value = "c" + Long.toString(nextIdentity++, 36); identities.put(gizmo, value); }
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
