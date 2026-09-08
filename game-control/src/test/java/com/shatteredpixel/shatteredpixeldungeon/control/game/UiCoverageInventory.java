package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** javac source parsing only: never loads, initializes or constructs game classes. */
public final class UiCoverageInventory {
    private static final Set<String> INPUT_METHODS = Set.of("onClick", "onRightClick", "onMiddleClick", "onLongClick",
            "onPointerDown", "onPointerUp", "onDrag", "onScroll", "onSignal", "onSelect", "onBackPressed",
            "enterPressed", "onActivate", "onChange", "itemSelectable", "textPrompt", "prompt");
    private final Path root;
    private final List<Type> types = new ArrayList<>();
    private final Map<String, Type> byName = new HashMap<>();
    private final Map<String, List<Type>> bySimpleName = new HashMap<>();

    private static final class Type {
        String name, simple, base, source, packageName;
        long line;
        List<String> interfaces = new ArrayList<>();
        Map<String, String> imports = new HashMap<>();
        List<Method> methods = new ArrayList<>();
        List<String> enumValues = new ArrayList<>();
        Type enclosing;
        int anonymous;
    }
    private static final class Method {
        String name, parameters, bodyDigest;
        long line;
    }
    private UiCoverageInventory(Path root) { this.root = root; }

    public static Map<String, Object> generate(Path root) throws Exception {
        UiCoverageInventory inventory = new UiCoverageInventory(root);
        inventory.parse();
        return inventory.manifest();
    }
    public static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("settings.gradle"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Cannot locate repository root");
        return path;
    }
    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? repositoryRoot() : Path.of(args[0]).toAbsolutePath();
        Map<String, Object> manifest = generate(root);
        Files.createDirectories(root.resolve("docs"));
        Map<String, Object> header = new LinkedHashMap<>(manifest);
        Object records = header.remove("records");
        String prefix = JsonCodec.encode(header);
        StringBuilder json = new StringBuilder(prefix.substring(0, prefix.length() - 1)).append(",\"records\":[\n");
        boolean first = true;
        for (Object record : (List<?>) records) {
            if (!first) json.append(",\n");
            first = false;
            json.append(JsonCodec.encode(record));
        }
        json.append("\n]}\n");
        Files.writeString(root.resolve("docs/cli-ui-coverage.json"), json.toString(), StandardCharsets.UTF_8);
        System.out.println(JsonCodec.encode(manifest.get("summary")));
    }

    private void parse() throws Exception {
        List<Path> paths = new ArrayList<>();
        for (String module : Arrays.asList("core", "SPD-classes")) {
            try (Stream<Path> stream = Files.walk(root.resolve(module + "/src/main/java"))) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(paths::add);
            }
        }
        paths.sort(Comparator.comparing(Path::toString));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Source inventory requires a JDK");
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, Collections.singletonList("-proc:none"),
                    null, manager.getJavaFileObjectsFromPaths(paths));
            Trees trees = Trees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                String contents = unit.getSourceFile().getCharContent(true).toString();
                String source = root.relativize(Path.of(unit.getSourceFile().toUri())).toString().replace('\\', '/');
                String packageName = unit.getPackageName().toString();
                Map<String, String> imports = new HashMap<>();
                for (ImportTree entry : unit.getImports()) {
                    if (entry.isStatic()) continue;
                    String name = entry.getQualifiedIdentifier().toString();
                    imports.put(name.substring(name.lastIndexOf('.') + 1), name);
                }
                new TreePathScanner<Void, Void>() {
                    final Deque<Type> stack = new ArrayDeque<>();
                    int anonymous;
                    @Override public Void visitClass(ClassTree tree, Void unused) {
                        Type type = new Type();
                        type.enclosing = stack.peek();
                        type.simple = tree.getSimpleName().toString();
                        if (type.simple.isEmpty()) {
                            int ordinal = type.enclosing == null ? ++anonymous : ++type.enclosing.anonymous;
                            type.simple = "anonymous" + ordinal;
                        }
                        type.name = type.enclosing == null ? packageName + "." + type.simple : type.enclosing.name + "$" + type.simple;
                        type.packageName = packageName;
                        type.source = source;
                        type.imports = imports;
                        type.line = line(tree);
                        if (tree.getExtendsClause() != null) type.base = tree.getExtendsClause().toString();
                        Tree parent = getCurrentPath().getParentPath().getLeaf();
                        if (parent instanceof NewClassTree) type.base = ((NewClassTree) parent).getIdentifier().toString();
                        tree.getImplementsClause().forEach(i -> type.interfaces.add(i.toString()));
                        if (tree.getKind() == Tree.Kind.ENUM) {
                            for (Tree member : tree.getMembers()) {
                                if (member instanceof VariableTree && ((VariableTree) member).getInitializer() instanceof NewClassTree) {
                                    NewClassTree constructor = (NewClassTree) ((VariableTree) member).getInitializer();
                                    if (constructor.getIdentifier().toString().equals(type.simple)) type.enumValues.add(((VariableTree) member).getName().toString());
                                }
                            }
                        }
                        types.add(type);
                        byName.put(type.name, type);
                        bySimpleName.computeIfAbsent(type.simple, ignored -> new ArrayList<>()).add(type);
                        stack.push(type);
                        super.visitClass(tree, unused);
                        stack.pop();
                        return null;
                    }
                    @Override public Void visitMethod(MethodTree tree, Void unused) {
                        if (!stack.isEmpty() && tree.getReturnType() != null) {
                            Method method = new Method();
                            method.name = tree.getName().toString();
                            method.parameters = tree.getParameters().stream().map(p -> p.getType().toString()).collect(Collectors.joining(","));
                            method.line = line(tree);
                            int start = (int) trees.getSourcePositions().getStartPosition(unit, tree);
                            int end = (int) trees.getSourcePositions().getEndPosition(unit, tree);
                            method.bodyDigest = digest(contents.substring(start, end));
                            stack.peek().methods.add(method);
                        }
                        return super.visitMethod(tree, unused);
                    }
                    private long line(Tree tree) {
                        long position = trees.getSourcePositions().getStartPosition(unit, tree);
                        return position < 0 ? -1 : unit.getLineMap().getLineNumber(position);
                    }
                }.scan(unit, null);
            }
        }
    }

    private Map<String, Object> manifest() {
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Integer> categories = new TreeMap<>();
        int unmatched = 0;
        for (Type type : types) {
            String category = category(type);
            if (category != null) {
                records.add(record(type, null, category, classRoute(category)));
                categories.merge(category, 1, Integer::sum);
            }
            for (Method method : type.methods) {
                if (!input(type, method, category)) continue;
                String route = methodRoute(type, method, category);
                records.add(record(type, method, "input_method", route));
                categories.merge("input_method", 1, Integer::sum);
                if (route.equals("UNMAPPED")) unmatched++;
            }
        }
        records.sort(Comparator.comparing(r -> (String) r.get("id")));
        return map("schema", 1, "scope", Arrays.asList("core/src/main/java", "SPD-classes/src/main/java"),
                "method", "javac AST parse only; no game class loading or construction",
                "claim", "Static route inventory; runtime coverage and wins require separate evidence",
                "summary", map("parsed_types", types.size(), "records", records.size(), "unmapped_inputs", unmatched,
                        "categories", categories, "runtime_verified_by_inventory", false), "records", records);
    }
    private Map<String, Object> record(Type type, Method method, String category, String route) {
        String id = type.source + "#" + type.name.substring(type.packageName.length() + 1)
                + (method == null ? "" : "." + method.name + "(" + method.parameters + ")");
        Map<String, Object> result = map("id", id, "source", type.source, "line", method == null ? type.line : method.line,
                "category", category, "route", route, "verification", "static_only");
        if (method != null) result.put("method_sha256", method.bodyDigest);
        else if (!type.enumValues.isEmpty()) result.put("enum_values", type.enumValues);
        return result;
    }
    private String category(Type type) {
        if (inherits(type, "CellSelector.Listener")) return "cell_selector";
        if (inherits(type, "WndBag.ItemSelector")) return "item_selector";
        if (inherits(type, "ArmorAbility")) return "armor_ability";
        if (inherits(type, "ClericSpell")) return "cleric_spell";
        if (inherits(type, "ActionIndicator.Action")) return "indicator_action";
        if (inherits(type, "MonkEnergy.MonkAbility") || inherits(type, "MonkAbility")) return "monk_ability";
        if (inherits(type, "Item")) return "item_type";
        if (inherits(type, "ItemButton")) return "item_button";
        if (inherits(type, "RightClickMenu")) return "context_menu";
        if (inherits(type, "Button")) return "button";
        if (inherits(type, "ActionArea")) return "action_area";
        if (inherits(type, "TextInput")) return "text_input";
        if (inherits(type, "OptionSlider")) return "option_slider";
        if (inherits(type, "ScrollPane")) return "scroll_pane";
        if (inherits(type, "Window")) return "window";
        if (inherits(type, "PixelScene") || type.name.equals("com.watabou.noosa.Scene")) return "scene";
        if (inherits(type, "PointerArea")) return "pointer_area";
        if (inherits(type, "Component")) return "component";
        if (type.simple.equals("HeroClass")) return "hero_class_catalog";
        if (type.simple.equals("HeroSubClass")) return "hero_subclass_catalog";
        return null;
    }
    private boolean input(Type type, Method method, String category) {
        if (method.name.equals("prompt") && !method.parameters.isEmpty()) return false;
        if (method.name.equals("onSignal")) return method.parameters.contains("KeyEvent") || method.parameters.contains("PointerEvent") || method.parameters.contains("ScrollEvent");
        if (INPUT_METHODS.contains(method.name)) return category != null || method.name.equals("onSelect");
        return "item_type".equals(category) && (method.name.equals("actions") || method.name.equals("execute") || method.name.equals("duelistAbility"))
                || "indicator_action".equals(category) && method.name.equals("doAction")
                || "armor_ability".equals(category) && (method.name.equals("use") || method.name.equals("activate"))
                || "cleric_spell".equals(category) && method.name.equals("onCast")
                || "monk_ability".equals(category) && method.name.equals("doAbility");
    }
    private String classRoute(String category) {
        switch (category) {
            case "button": return "ui.activate; actual overrides/enabled state discovered at runtime";
            case "item_button": return "ui.activate descendant ItemSlot -> owning ItemButton callback";
            case "context_menu": return "topmost context_menu controls; ui.back -> dismiss";
            case "action_area": return "ui.activate; labelled visual action";
            case "text_input": return "ui.text; existing change/submit callbacks";
            case "option_slider": return "ui.value; existing onChange callback";
            case "scroll_pane": return "ui.scroll/ui.select; descendant controls remain discoverable";
            case "window": return "topmost window controls and ui.back";
            case "scene": return "current scene controls; coordinator must expose stable input phases";
            case "cell_selector": return "cell.select/cell.cancel; current listener only";
            case "item_selector": return "ui.activate existing enabled inventory slot; ui.back cancellation";
            case "indicator_action": return "visible ActionIndicator button -> doAction -> current prompt";
            case "armor_ability": return "ClassArmor action -> existing ability window/cell selector";
            case "cleric_spell": return "HolyTome -> current WndClericSpells buttons -> existing spell prompt";
            case "monk_ability": return "MonkEnergy -> current WndMonkAbilities buttons -> existing target prompt";
            case "item_type": return "owned item slot -> WndUseItem current actions; never arbitrary execute strings";
            case "pointer_area": return "input plumbing or reviewed special input; see declared method records";
            case "component": return "container/rendered text or reviewed list entry; see declared method records";
            default: return "build catalog only; does not instantiate or unlock characters";
        }
    }
    private String methodRoute(Type type, Method method, String category) {
        String file = type.source.substring(type.source.lastIndexOf('/') + 1);
        if (method.name.equals("onSignal")) {
            switch (file) {
                case "Button.java": return "ui.activate; keyboard/pointer dispatch plumbing";
                case "CellSelector.java": return "move.step/cell.select and view.zoom/view.pan; held movement is repeated legal steps";
                case "ScrollPane.java": return "ui.scroll; keyboard scroll equivalent";
                case "ScrollArea.java": return "framework scroll dispatch; concrete ScrollPane/CellSelector routes";
                case "Scene.java": return "ui.back -> current scene requestBack";
                case "WndTabbed.java": return "ui.activate existing tab; cycle selects the next tab";
                case "Window.java": case "WndBag.java": case "WndHero.java": case "WndJournal.java": return "ui.back; normal close/cancel callback";
                case "InventoryPane.java": return "ui.back -> cancelSelection; bag controls remain in pane";
                case "WndKeyBindings.java": return "ui.binding_key -> proposeKey; actual confirm button required";
                case "TitleScene.java": case "HeroSelectScene.java": return "ui.reveal -> same background-release callback";
                case "InterlevelScene.java": return "continue/hide-story buttons; coordinator must accept awaiting-continue phase";
                case "PixelScene.java": return "settings fullscreen checkbox; Alt+Enter equivalent";
                case "RightClickMenu.java": return "ui.back; outside dismissal plumbing";
                case "PointerArea.java": return "framework dispatch; concrete actions inventoried independently";
                default: return "UNMAPPED";
            }
        }
        if (method.name.equals("onBackPressed")) return "ui.back; original override including mandatory-window gates";
        if ("item_button".equals(category) || "context_menu".equals(category) || "option_slider".equals(category)) return classRoute(category);
        if ("button".equals(category)) {
            if (method.name.equals("onPointerDown") || method.name.equals("onPointerUp")) return "reviewed button press presentation; activation uses click/right/middle/long";
            return "ui.activate; declared semantic button callback";
        }
        if ("action_area".equals(category)) return "ui.activate -> onActivate; same action as pointer handler";
        if ("item_type".equals(category)) {
            if (method.name.equals("duelistAbility")) return file.equals("MeleeWeapon.java")
                    ? "base no-op; not a concrete weapon ability fixture"
                    : "owned equipped duelist weapon ability button -> concrete ability -> existing target/continuation";
            return "owned item actions through existing UI; follow resulting prompt";
        }
        if ("cell_selector".equals(category)) return "cell.select/cell.cancel; listener prompt and selection";
        if ("item_selector".equals(category)) return "existing item selector -> enabled slot callback or cancel";
        if (Set.of("indicator_action", "armor_ability", "cleric_spell", "monk_ability").contains(category == null ? "" : category)) return classRoute(category);
        if (file.equals("RadialMenu.java")) return "ui.choose; visible option text and alternate action";
        if (file.equals("WndTextInput.java") || file.equals("TextInput.java")) return "ui.text or displayed confirm/cancel button";
        if (file.equals("OptionSlider.java")) return "ui.value -> chooseValue -> same onChange callback";
        if (file.equals("CellSelector.java")) return "cell.select/cell.cancel/view.zoom/view.pan; map input plumbing";
        if (file.equals("Button.java")) return "ui.activate; pointer/key normalization only";
        if (file.equals("ScrollPane.java") || file.equals("ScrollArea.java")) return "ui.scroll/ui.select; scroll input plumbing";
        if (Set.of("Window.java", "WndStory.java", "WndBadge.java", "WndJournalItem.java", "WndChanges.java", "WndChangesTabbed.java").contains(file)) return "ui.back; same window dismissal callback";
        if (file.equals("InventoryPane.java")) return "ui.back cancellation or current inventory controls";
        if (file.equals("PointerArea.java")) return "framework dispatch; no standalone gameplay command";
        if (method.name.equals("onClick") && method.parameters.equals("float,float")) return "ui.select list/grid entry through owning ScrollPane";
        if (file.equals("ChangeButton.java")) return "ui.activate -> existing changelog action";
        if ("window".equals(category) && method.name.equals("onSelect")) return "window option button -> original onSelect";
        if ("window".equals(category) && method.name.equals("onClick") && method.parameters.equals("Tab")) return "ui.activate existing tab -> owning window selection callback";
        return "UNMAPPED";
    }
    private boolean inherits(Type type, String expected) { return inherits(type, expected, new HashSet<>()); }
    private boolean inherits(Type type, String expected, Set<String> seen) {
        if (!seen.add(type.name)) return false;
        String dottedName = type.name.replace('$', '.');
        if (dottedName.equals(expected) || dottedName.endsWith("." + expected)) return true;
        List<String> ancestors = new ArrayList<>(type.interfaces);
        if (type.base != null) ancestors.add(type.base);
        for (String raw : ancestors) {
            String parent = raw.replaceAll("<.*>", "").trim();
            if (parent.equals(expected) || parent.endsWith("." + expected)) return true;
            Type resolved = resolve(type, parent);
            if (resolved != null && inherits(resolved, expected, seen)) return true;
        }
        return false;
    }
    private Type resolve(Type context, String parent) {
        if (byName.containsKey(parent)) return byName.get(parent);
        String first = parent.contains(".") ? parent.substring(0, parent.indexOf('.')) : parent;
        String rest = parent.substring(first.length()).replace('.', '$');
        if (context.imports.containsKey(first)) {
            Type found = byName.get(context.imports.get(first) + rest);
            if (found != null) return found;
        }
        Type found = byName.get(context.packageName + "." + parent.replace('.', '$'));
        if (found != null) return found;
        for (Type enclosing = context.enclosing; enclosing != null; enclosing = enclosing.enclosing) {
            found = byName.get(enclosing.name + "$" + parent.replace('.', '$'));
            if (found != null) return found;
        }
        List<Type> candidates = bySimpleName.get(parent.substring(parent.lastIndexOf('.') + 1));
        return candidates != null && candidates.size() == 1 ? candidates.get(0) : null;
    }
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
