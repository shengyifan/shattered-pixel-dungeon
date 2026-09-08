package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Lossless JSON compression for audit storage. Content keys are never player snapshot IDs. */
public final class SnapshotCodec {
    public static final String ENCODING = "gzip-json-utf8-v1";
    public static final String INTERNAL_ENCODING = "gzip-json-manifest-v1";
    private static final String MANIFEST_FORMAT = "spd-internal-snapshot-blocks-v1";
    private static final String BLOCK_FORMAT = "spd-internal-snapshot-block-v1";
    private static final byte[] HASH_DOMAIN = "spd-snapshot-content-v1\0".getBytes(StandardCharsets.US_ASCII);

    private SnapshotCodec() {}

    public static byte[] encode(Map<String, Object> value) {
        Objects.requireNonNull(value, "Snapshot must not be null");
        byte[] json = escapedUnpairedSurrogates(JsonCodec.encode(value)).getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(json); }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot compress snapshot", failure);
        }
    }

    public static Map<String, Object> decode(byte[] compressed) {
        Objects.requireNonNull(compressed, "Snapshot bytes must not be null");
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] raw = gzip.readAllBytes();
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
            return JsonCodec.decode(json);
        } catch (CharacterCodingException malformedText) {
            throw new IllegalArgumentException("Snapshot does not contain valid UTF-8", malformedText);
        } catch (IOException malformedCompression) {
            throw new IllegalArgumentException("Snapshot compression is invalid", malformedCompression);
        }
    }

    /**
     * Compress paired observations with independent public/private content keys. The caller still
     * assigns a fresh opaque snapshot UUID independent of both keys for every audit occurrence.
     */
    public static Encoded encodePair(Map<String, Object> publicValue, Map<String, Object> internalValue) {
        Objects.requireNonNull(publicValue, "Public snapshot must not be null");
        Objects.requireNonNull(internalValue, "Internal snapshot must not be null");
        byte[] publicBody = encode(publicValue);
        // JsonCodec validates the graph and gives us a detached JSON copy without invoking model code.
        Map<String, Object> snapshot = JsonCodec.decode(JsonCodec.encode(internalValue));
        Map<String, byte[]> blocks = new LinkedHashMap<>();
        List<Object> references = new ArrayList<>();
        extractSnapshotBlocks(snapshot, Collections.emptyList(), blocks, references);
        if (snapshot.get("last_stable_internal") instanceof Map<?, ?>) {
            extractSnapshotBlocks(snapshot, Collections.singletonList("last_stable_internal"), blocks, references);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("format", MANIFEST_FORMAT);
        // String wrapping preserves the entire JsonCodec depth budget of the original snapshot.
        envelope.put("snapshot_json", JsonCodec.encode(snapshot));
        envelope.put("blocks", references);
        byte[] internalBody = encode(envelope);
        return new Encoded(publicBody, internalBody, contentId(publicBody), contentId(internalBody), blocks);
    }

    private static void extractSnapshotBlocks(Map<String, Object> snapshot, List<?> prefix,
                                              Map<String, byte[]> blocks, List<Object> references) {
        Object source = snapshot;
        for (Object part : prefix) source = child(source, part, true);
        if (!(source instanceof Map<?, ?>)) return;
        Map<?, ?> scoped = (Map<?, ?>) source;
        Object profile = scoped.get("profile_files");
        if (profile instanceof Map<?, ?>) {
            Object files = ((Map<?, ?>) profile).get("files");
            if (files instanceof List<?>) {
                for (int i = 0; i < ((List<?>) files).size(); i++) {
                    extract(snapshot, path(prefix, "profile_files", "files", i), blocks, references);
                }
            }
        }
        for (String field : Arrays.asList("static_roots", "included_fields", "limitations")) {
            extract(snapshot, path(prefix, "coverage", field), blocks, references);
        }
        for (String field : Arrays.asList("build", "build_info", "versions", "file_versions")) {
            extract(snapshot, path(prefix, field), blocks, references);
        }
        // Stable model/static nodes can be shared even when the surrounding snapshot changes.
        Object nodes = scoped.get("nodes");
        if (nodes instanceof Map<?, ?>) {
            for (Object key : new ArrayList<>(((Map<?, ?>) nodes).keySet())) {
                extract(snapshot, path(prefix, "nodes", key), blocks, references);
            }
        }
    }

    private static List<Object> path(List<?> prefix, Object... parts) {
        List<Object> result = new ArrayList<>(prefix);
        result.addAll(Arrays.asList(parts));
        return result;
    }

    public static Map<String, Object> decodeInternal(byte[] root, Function<String, byte[]> resolver) {
        Objects.requireNonNull(resolver, "Internal block resolver must not be null");
        Map<String, Object> envelope = decode(root);
        if (!MANIFEST_FORMAT.equals(envelope.get("format")) || !(envelope.get("snapshot_json") instanceof String)
                || !(envelope.get("blocks") instanceof List<?>)) {
            throw new IllegalArgumentException("Unsupported internal snapshot manifest");
        }
        Map<String, Object> snapshot = JsonCodec.decode((String) envelope.get("snapshot_json"));
        java.util.HashSet<List<?>> seenPaths = new java.util.HashSet<>();
        for (Object reference : (List<?>) envelope.get("blocks")) {
            if (!(reference instanceof Map<?, ?>)) throw new IllegalArgumentException("Invalid internal block reference");
            Map<?, ?> entry = (Map<?, ?>) reference;
            Object pathValue = entry.get("path"), idValue = entry.get("content_id");
            if (!(pathValue instanceof List<?>) || !(idValue instanceof String)
                    || !((String) idValue).matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid internal block reference");
            }
            List<?> path = (List<?>) pathValue;
            if (!seenPaths.add(path)) throw new IllegalArgumentException("Duplicate internal block path");
            byte[] body = resolver.apply((String) idValue);
            if (body == null) throw new IllegalArgumentException("Missing internal snapshot block");
            if (!contentId(body).equals(idValue)) throw new IllegalArgumentException("Internal snapshot block key mismatch");
            Map<String, Object> block = decode(body);
            if (!BLOCK_FORMAT.equals(block.get("format")) || !(block.get("value_json") instanceof String)) {
                throw new IllegalArgumentException("Unsupported internal snapshot block");
            }
            Map<String, Object> wrapper = JsonCodec.decode((String) block.get("value_json"));
            if (!wrapper.containsKey("value")) throw new IllegalArgumentException("Invalid internal snapshot block value");
            replace(snapshot, path, wrapper.get("value"), true);
        }
        return snapshot;
    }

    private static void extract(Map<String, Object> snapshot, List<?> path, Map<String, byte[]> blocks,
                                List<Object> references) {
        Object current = snapshot;
        for (Object part : path) {
            current = child(current, part, false);
            if (current == null) return;
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("value", current);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("format", BLOCK_FORMAT);
        block.put("value_json", JsonCodec.encode(wrapper));
        byte[] body = encode(block);
        String key = contentId(body);
        blocks.putIfAbsent(key, body);
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("path", new ArrayList<>(path));
        reference.put("content_id", key);
        references.add(reference);
        replace(snapshot, path, null, false);
    }

    private static Object child(Object container, Object part, boolean required) {
        if (container instanceof Map<?, ?> && part instanceof String) {
            if (!required || ((Map<?, ?>) container).containsKey(part)) return ((Map<?, ?>) container).get(part);
        } else if (container instanceof List<?> && part instanceof Number) {
            long index = ((Number) part).longValue();
            if (((Number) part).doubleValue() == index && index >= 0 && index < ((List<?>) container).size()) {
                return ((List<?>) container).get((int) index);
            }
        }
        if (!required) return null;
        throw new IllegalArgumentException("Invalid internal block path");
    }

    @SuppressWarnings("unchecked")
    private static void replace(Map<String, Object> snapshot, List<?> path, Object value, boolean requirePlaceholder) {
        if (path.isEmpty()) throw new IllegalArgumentException("Internal block path must not be empty");
        Object parent = snapshot;
        for (int i = 0; i < path.size() - 1; i++) parent = child(parent, path.get(i), true);
        Object last = path.get(path.size() - 1);
        Object old = child(parent, last, true);
        if (requirePlaceholder && old != null) throw new IllegalArgumentException("Internal block placeholder is missing");
        if (parent instanceof Map<?, ?> && last instanceof String) ((Map<String, Object>) parent).put((String) last, value);
        else if (parent instanceof List<?> && last instanceof Number) ((List<Object>) parent).set(((Number) last).intValue(), value);
        else throw new IllegalArgumentException("Invalid internal block path");
    }

    public static final class Encoded {
        public final byte[] publicBody;
        public final byte[] internalBody;
        public final String publicContentId;
        public final String internalContentId;
        public final Map<String, byte[]> internalBlocks;

        private Encoded(byte[] publicBody, byte[] internalBody, String publicContentId, String internalContentId,
                        Map<String, byte[]> internalBlocks) {
            this.publicBody = publicBody;
            this.internalBody = internalBody;
            this.publicContentId = publicContentId;
            this.internalContentId = internalContentId;
            this.internalBlocks = Collections.unmodifiableMap(new LinkedHashMap<>(internalBlocks));
        }
    }

    /**
     * Storage-only key. Length-prefixes separate components; callers must not expose a key derived
     * from internal content in the public database, response, snapshot UUID or event stream.
     */
    public static String contentId(byte[]... bodies) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(HASH_DOMAIN);
            for (byte[] body : bodies) {
                Objects.requireNonNull(body, "Content body must not be null");
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(body.length).array());
                digest.update(body);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte part : hash) {
                hex.append(Character.forDigit((part >>> 4) & 15, 16));
                hex.append(Character.forDigit(part & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String escapedUnpairedSurrogates(String json) {
        StringBuilder escaped = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < json.length()
                    && Character.isLowSurrogate(json.charAt(i + 1))) {
                escaped.append(c).append(json.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                escaped.append("\\u");
                for (int shift = 12; shift >= 0; shift -= 4) escaped.append(Character.forDigit((c >>> shift) & 15, 16));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
