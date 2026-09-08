package com.shatteredpixel.shatteredpixeldungeon.control.game;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;

/** Internal audit only. Explicit roots, reference-preserving field traversal, no game methods. */
public final class InternalGraphSnapshotter {
    private static final String GAME = "com.shatteredpixel.shatteredpixeldungeon.";

    // Checked-in root manifest, never classpath discovery or request-controlled reflection.
    public static final List<String> STATIC_ROOTS = loadStaticRoots();

    private final IdentityHashMap<Object, String> identities = new IdentityHashMap<>();
    private final Map<String, Object> nodes = new LinkedHashMap<>();
    private final ArrayDeque<Object> pending = new ArrayDeque<>();
    private final TreeSet<String> includedFields = new TreeSet<>();
    private final List<Object> excluded = new ArrayList<>();
    private final List<Object> unavailable = new ArrayList<>();
    private final List<Object> unobserved = new ArrayList<>();
    private final ClassInitializationProbe initialization = new ClassInitializationProbe();
    private boolean initializationUnknown;

    private static List<String> loadStaticRoots() {
        List<String> result = new ArrayList<>();
        InputStream resource = InternalGraphSnapshotter.class.getResourceAsStream("snapshot-static-roots.txt");
        if (resource == null) throw new IllegalStateException("Snapshot root manifest is missing");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!line.startsWith(GAME)) throw new IllegalStateException("Invalid snapshot root manifest");
                result.add(line);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Snapshot root manifest is unreadable", failure);
        }
        return java.util.Collections.unmodifiableList(result);
    }

    public Map<String, Object> captureGameRoots(Map<String, Object> additionalRoots) {
        Map<String, Object> roots = new LinkedHashMap<>();
        for (String className : STATIC_ROOTS) {
            try {
                Class<?> type = Class.forName(className, false, getClass().getClassLoader());
                Boolean initialized = initialization.initialized(type);
                if (!Boolean.TRUE.equals(initialized)) {
                    if (initialized == null) initializationUnknown = true;
                    unobserved.add(map("root", className, "reason", initialized == null
                            ? "initialization_state_unavailable" : "not_initialized"));
                    roots.put(className, map("$unobserved", initialized == null
                            ? "initialization_state_unavailable" : "not_initialized"));
                    continue;
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                Field[] declared = type.getDeclaredFields();
                Arrays.sort(declared, Comparator.comparing(Field::getName));
                for (Field field : declared) {
                    if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    String path = type.getName() + "#" + field.getName();
                    if (Modifier.isFinal(field.getModifiers())
                            && (field.getType().isPrimitive() || field.getType() == String.class)) continue;
                    fields.put(field.getName(), readField(null, field, path));
                }
                roots.put(className, fields);
            } catch (ReflectiveOperationException | LinkageError failure) {
                unavailable.add(map("root", className, "reason", failure.getClass().getSimpleName()));
            }
        }
        for (Map.Entry<String, Object> root : additionalRoots.entrySet()) {
            roots.put(root.getKey(), value(root.getValue(), root.getKey()));
        }
        return finish(roots);
    }

    /** Isolated-object entry point for reference/coverage/purity tests. */
    public Map<String, Object> capture(Map<String, Object> sourceRoots) {
        Map<String, Object> roots = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : sourceRoots.entrySet()) {
            roots.put(entry.getKey(), value(entry.getValue(), entry.getKey()));
        }
        return finish(roots);
    }

    private Map<String, Object> finish(Map<String, Object> roots) {
        while (!pending.isEmpty()) {
            Object object = pending.removeFirst();
            String id = identities.get(object);
            Map<String, Object> node = map("type", object.getClass().getName());
            nodes.put(id, node);
            if (object.getClass().isArray()) {
                List<Object> elements = new ArrayList<>();
                for (int i = 0; i < Array.getLength(object); i++) elements.add(value(Array.get(object, i), id + "[" + i + "]"));
                node.put("elements", elements);
            } else if (object instanceof Map<?, ?>) {
                List<Object> entries = new ArrayList<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                    entries.add(map("key", value(entry.getKey(), id + ".key"),
                            "value", value(entry.getValue(), id + ".value")));
                }
                node.put("entries", entries);
            } else if (object instanceof Collection<?>) {
                List<Object> elements = new ArrayList<>();
                for (Object element : (Collection<?>) object) elements.add(value(element, id + "[]"));
                node.put("elements", elements);
            } else {
                if (object instanceof Enum<?>) node.put("enum_name", ((Enum<?>) object).name());
                Map<String, Object> fields = new LinkedHashMap<>();
                for (Class<?> type = object.getClass(); type != null && type != Object.class
                        && type != Enum.class; type = type.getSuperclass()) {
                    Field[] declared = type.getDeclaredFields();
                    Arrays.sort(declared, Comparator.comparing(Field::getName));
                    for (Field field : declared) {
                        if (Modifier.isStatic(field.getModifiers())) continue;
                        String path = type.getName() + "#" + field.getName();
                        if (renderField(field)) {
                            excluded.add(map("field", path, "reason", "render_base_field"));
                            continue;
                        }
                        // Synthetic this$0, val$ and lambda arg$ fields hold pending player choices.
                        // They are real semantic state; retain their references in this same graph.
                        fields.put(path, readField(object, field, path));
                    }
                }
                node.put("fields", fields);
            }
        }
        return map("format", "reference_graph_v1", "roots", roots, "nodes", nodes,
                "coverage", map("static_roots", STATIC_ROOTS, "included_fields", new ArrayList<>(includedFields),
                        "excluded", excluded, "unavailable", unavailable, "unobserved", unobserved,
                        "status", unavailable.isEmpty() && !initializationUnknown ? "captured_declared_scope" : "incomplete",
                        "limitations", Arrays.asList("render_native_and_external_objects_excluded",
                                "statics_outside_explicit_roots_not_claimed", "collection_implementation_caches_not_captured",
                                "controller_audit_infrastructure_excluded_from_game_graph")));
    }

    private Object readField(Object object, Field field, String path) {
        // GDX IntMap iterator caches are mutated by its iteration methods. Capture backing fields,
        // exclude cached iterator objects, and never invoke keys()/values()/entries().
        if (field.getDeclaringClass().getName().startsWith("com.badlogic.gdx.utils.")
                && (field.getName().startsWith("entries") || field.getName().startsWith("values")
                || field.getName().startsWith("keys"))) {
            excluded.add(map("field", path, "reason", "collection_iterator_cache"));
            return map("$excluded", "collection_iterator_cache");
        }
        try {
            field.setAccessible(true);
            Object result = field.get(object);
            includedFields.add(path);
            return value(result, path);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            unavailable.add(map("field", path, "reason", failure.getClass().getSimpleName()));
            return map("$unavailable", failure.getClass().getSimpleName());
        }
    }

    private Object value(Object object, String path) {
        if (object == null || object instanceof String || object instanceof Boolean
                || object instanceof Byte || object instanceof Short || object instanceof Integer
                || object instanceof Long) return object;
        if (object instanceof Float || object instanceof Double) {
            double number = ((Number) object).doubleValue();
            return Double.isFinite(number) ? object : map("$number", object.toString());
        }
        if (object instanceof Character) return object.toString();
        if (object instanceof Class<?>) return map("$class", ((Class<?>) object).getName());
        if (object instanceof java.util.Date) return map("$date_millis", ((java.util.Date) object).getTime());
        if (excludedType(object.getClass())) {
            excluded.add(map("field", path, "type", object.getClass().getName(), "reason", "render_native_or_external"));
            return map("$excluded", "render_native_or_external", "type", object.getClass().getName());
        }
        String id = identities.get(object);
        if (id == null) {
            id = "n" + (identities.size() + 1);
            identities.put(object, id);
            pending.addLast(object);
        }
        return map("$ref", id);
    }

    private boolean excludedType(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type == Object.class || type == String.class
                || type == Class.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class || type.isEnum()
                || Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) return false;
        if (Thread.class.isAssignableFrom(type) || Buffer.class.isAssignableFrom(type)
                || ClassLoader.class.isAssignableFrom(type)) return true;
        String name = type.getName();
        // Preserve pending callback values without recursively capturing the audit machinery itself.
        if (name.equals(GAME + "control.game.GameController") || name.equals(GAME + "control.game.GameSnapshotter")
                || name.equals(GAME + "control.game.UiBridge") || name.startsWith(GAME + "control.desktop.")) return true;
        if (name.startsWith(GAME)) return name.startsWith(GAME + "sprites.") || name.startsWith(GAME + "effects.");
        if (semanticNoosaType(name) || name.startsWith("com.watabou.input.")
                || name.startsWith("com.badlogic.gdx.scenes.scene2d.ui.TextField")
                || name.startsWith("com.badlogic.gdx.scenes.scene2d.ui.TextArea")) return false;
        for (Class<?> ancestor = type; ancestor != null; ancestor = ancestor.getSuperclass()) {
            String ancestorName = ancestor.getName();
            if (ancestorName.startsWith("com.watabou.noosa.") || ancestorName.startsWith("com.watabou.gltextures.")
                    || ancestorName.startsWith("com.watabou.glwrap.")) return true;
        }
        return !name.startsWith("com.watabou.utils.") && !name.startsWith("com.badlogic.gdx.utils.");
    }

    private static boolean semanticNoosaType(String name) {
        for (String stem : Arrays.asList("Gizmo", "Group", "Scene", "PointerArea", "TextInput", "BitmapText", "ui.Component")) {
            if (name.equals("com.watabou.noosa." + stem) || name.startsWith("com.watabou.noosa." + stem + "$")) return true;
        }
        return false;
    }

    private static boolean renderField(Field field) {
        String owner = field.getDeclaringClass().getName();
        if (!owner.startsWith("com.watabou.noosa.")) return false;
        if (owner.equals("com.watabou.noosa.Gizmo")) return field.getName().equals("camera");
        if (owner.equals("com.watabou.noosa.BitmapText")) return !field.getName().equals("text");
        // Group.members preserves the semantic child order; Scene/PointerArea carry callbacks;
        // TextInput retains the actual TextField selection/text state, excluding its native skin/stage.
        return !semanticNoosaType(owner);
    }
}
