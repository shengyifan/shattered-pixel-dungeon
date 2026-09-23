package com.shatteredpixel.shatteredpixeldungeon.control.game.util;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Values must never retain their key. Identity, not String.equals, is the contract. */
public final class WeakIdentityRegistry<V> {
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final Map<Key, V> values = new HashMap<>();

    public synchronized V get(Object key) {
        try {
            drain();
            return key == null ? null : values.get(new Key(key, null));
        } finally { Reference.reachabilityFence(key); }
    }

    public synchronized void put(Object key, V value) {
        try {
            drain();
            if (key != null) values.put(new Key(key, queue), value);
        } finally { Reference.reachabilityFence(key); }
    }

    public synchronized void remove(Object key) {
        try {
            drain();
            if (key != null) values.remove(new Key(key, null));
        } finally { Reference.reachabilityFence(key); }
    }

    public synchronized void clear() {
        values.clear();
        while (queue.poll() != null) { }
    }

    private void drain() {
        Key key;
        while ((key = (Key) queue.poll()) != null) values.remove(key);
    }

    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            hash = System.identityHashCode(referent);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object mine = get();
            return other instanceof Key && mine != null && mine == ((Key) other).get();
        }
    }
}
