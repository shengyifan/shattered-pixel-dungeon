package com.shatteredpixel.shatteredpixeldungeon.control.game;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Capture-local evidence for the compact UI view. These hints are deliberately not map entries:
 * JSON, audit snapshots and intent signatures contain the original complete public UI only.
 * No hint retains a game object, callback, mutable source or information absent from that capture.
 */
public final class UiProjectionHints {
    private static final UiProjectionHints EMPTY = new UiProjectionHints(Collections.emptyMap());
    public final Map<String, Node> nodes;

    public static final class Node {
        public final boolean emptyPlaceholder;
        public final List<String> ownedTextChildren;
        public final String locator;
        /** Display field to an existing child control ID; text and provenance stay in that child. */
        public final Map<String, String> displayChildren;

        public Node(boolean emptyPlaceholder, List<String> ownedTextChildren, String locator,
                    Map<String, String> displayChildren) {
            this.emptyPlaceholder = emptyPlaceholder;
            this.ownedTextChildren = Collections.unmodifiableList(new ArrayList<>(ownedTextChildren));
            this.locator = locator;
            this.displayChildren = Collections.unmodifiableMap(new LinkedHashMap<>(displayChildren));
        }
    }

    public UiProjectionHints(Map<String, Node> nodes) {
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    public static UiProjectionHints get(Object value) {
        return value instanceof Carrier ? ((Carrier) value).hints : EMPTY;
    }

    public Map<String, Object> attach(Map<String, Object> canonical) {
        return nodes.isEmpty() ? canonical : new Carrier(canonical, this);
    }

    /** Preserve evidence across an ordinary public map copy without adding serializable fields. */
    public static Map<String, Object> preserve(Object source, Map<String, Object> replacement) {
        return get(source).attach(replacement);
    }

    /** Mutability follows the supplied map; freeze supplies an unmodifiable delegate. */
    private static final class Carrier extends AbstractMap<String, Object> {
        private final Map<String, Object> values;
        private final UiProjectionHints hints;

        private Carrier(Map<String, Object> values, UiProjectionHints hints) {
            this.values = values;
            this.hints = hints;
        }

        @Override public Set<Entry<String, Object>> entrySet() { return values.entrySet(); }
        @Override public Object get(Object key) { return values.get(key); }
        @Override public Object put(String key, Object value) { return values.put(key, value); }
        @Override public Object remove(Object key) { return values.remove(key); }
        @Override public void clear() { values.clear(); }
    }
}
