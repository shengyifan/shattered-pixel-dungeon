package com.shatteredpixel.shatteredpixeldungeon.control.desktop.store;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ProtocolException;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class SnapshotCodecTest {
    @Test
    public void roundTripPreservesAllJsonContentAndPrecision() {
        Map<String, Object> original = map("text", "中文 / 😀 / \\n / \n / \u0000", "null", null,
                "integer", new BigInteger("123456789012345678901234567890"),
                "decimal", new BigDecimal("0.123456789012345678900"),
                "boolean", false, "list", Arrays.asList("x", map("a", 1), 2.5));
        String before = JsonCodec.encode(original);
        byte[] compressed = SnapshotCodec.encode(original);
        Map<String, Object> restored = SnapshotCodec.decode(compressed);
        assertEquals(before, JsonCodec.encode(restored));
        assertEquals(before, JsonCodec.encode(original));
    }

    @Test
    public void malformedUtf16InAJavaStringIsPreservedAsJsonEscapes() {
        String unusual = "before\uD800after\uDC00";
        Map<String, Object> original = map(unusual, unusual);
        Map<String, Object> restored = SnapshotCodec.decode(SnapshotCodec.encode(original));
        assertEquals(unusual, restored.get(unusual));
    }

    @Test
    public void compressionIsDeterministicAndSubstantiallyReducesRepeatedSnapshots() {
        List<Object> repeated = new ArrayList<>();
        for (int i = 0; i < 2000; i++) repeated.add(map("type", "reference_graph", "field", "repeat", "value", 12345));
        Map<String, Object> snapshot = map("nodes", repeated);
        byte[] first = SnapshotCodec.encode(snapshot);
        assertArrayEquals(first, SnapshotCodec.encode(snapshot));
        assertTrue(first.length < JsonCodec.encode(snapshot).getBytes(StandardCharsets.UTF_8).length / 10);
        assertEquals(JsonCodec.encode(snapshot), JsonCodec.encode(SnapshotCodec.decode(first)));
    }

    @Test
    public void contentKeysAreDeterministicAndComponentBoundariesCannotCollide() {
        byte[] a = new byte[]{1};
        byte[] bc = new byte[]{2, 3};
        assertEquals(SnapshotCodec.contentId(a, bc), SnapshotCodec.contentId(a, bc));
        assertNotEquals(SnapshotCodec.contentId(a, bc), SnapshotCodec.contentId(new byte[]{1, 2}, new byte[]{3}));
        assertNotEquals(SnapshotCodec.contentId(a, bc), SnapshotCodec.contentId(bc, a));
    }

    @Test
    public void corruptGzipIsRejectedInsteadOfReturningPartialData() {
        byte[] compressed = SnapshotCodec.encode(map("secret", "complete"));
        compressed[compressed.length - 8] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(compressed));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(new byte[]{1, 2, 3}));
        byte[] truncated = Arrays.copyOf(SnapshotCodec.encode(map("a", 1)), 11);
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(truncated));
    }

    @Test
    public void invalidUtf8IsRejectedWithoutReplacementCharacters() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
            gzip.write(new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xC3, '(', '"', '}'});
        }
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(raw.toByteArray()));
    }

    @Test
    public void cyclesAndUnsupportedModelObjectsAreNotSerialized() {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);
        assertThrows(ProtocolException.class, () -> SnapshotCodec.encode(cycle));
        assertThrows(ProtocolException.class, () -> SnapshotCodec.encode(map("raw", new Object())));
    }

    @Test
    public void internalBlocksRoundTripEveryFieldWithoutMutatingInputOrCollidingWithUserKeys() {
        Map<String, Object> original = internalFixture(20);
        original.put("format", "spd-internal-snapshot-blocks-v1");
        original.put("blocks", map("user_data", "$ref"));
        original.put("snapshot_json", "an ordinary original field");
        String before = JsonCodec.encode(original);
        SnapshotCodec.Encoded encoded = SnapshotCodec.encodePair(map("visible", true), original);
        assertEquals(before, JsonCodec.encode(original));
        Map<String, Object> restored = SnapshotCodec.decodeInternal(encoded.internalBody, encoded.internalBlocks::get);
        assertEquals(before, JsonCodec.encode(restored));
        assertEquals(JsonCodec.encode(map("visible", true)), JsonCodec.encode(SnapshotCodec.decode(encoded.publicBody)));
        assertTrue(encoded.internalBlocks.size() >= 5);
    }

    @Test
    public void privateChangesCannotChangeAnyPublicCodecOutput() {
        Map<String, Object> visible = map("scene", "game", "known", Arrays.asList(1, 2));
        SnapshotCodec.Encoded first = SnapshotCodec.encodePair(visible, internalFixture(20));
        SnapshotCodec.Encoded second = SnapshotCodec.encodePair(visible, internalFixture(5));
        assertArrayEquals(first.publicBody, second.publicBody);
        assertEquals(first.publicContentId, second.publicContentId);
        assertNotEquals(first.internalContentId, second.internalContentId);
    }

    @Test
    public void unchangedFilesStaticMetadataAndNodesReuseTheSameCompleteBlocks() {
        SnapshotCodec.Encoded first = SnapshotCodec.encodePair(map(), internalFixture(20));
        SnapshotCodec.Encoded second = SnapshotCodec.encodePair(map(), internalFixture(5));
        java.util.Set<String> shared = new java.util.HashSet<>(first.internalBlocks.keySet());
        shared.retainAll(second.internalBlocks.keySet());
        assertEquals(first.internalBlocks.size() - 1, shared.size());
        for (String key : shared) assertArrayEquals(first.internalBlocks.get(key), second.internalBlocks.get(key));
        assertEquals(JsonCodec.encode(internalFixture(5)), JsonCodec.encode(
                SnapshotCodec.decodeInternal(second.internalBody, second.internalBlocks::get)));
    }

    @Test
    public void lastStableInternalWrapperUsesTheSameReusableBlocksWithoutDroppingAuditContext() {
        Map<String, Object> wrapped = map("audit_context", map("scope", "historical", "pending", true),
                "last_stable_internal", internalFixture(20));
        SnapshotCodec.Encoded direct = SnapshotCodec.encodePair(map(), internalFixture(20));
        SnapshotCodec.Encoded envelope = SnapshotCodec.encodePair(map(), wrapped);
        assertEquals(direct.internalBlocks.keySet(), envelope.internalBlocks.keySet());
        assertEquals(JsonCodec.encode(wrapped), JsonCodec.encode(
                SnapshotCodec.decodeInternal(envelope.internalBody, envelope.internalBlocks::get)));
    }

    @Test
    public void missingOrMismatchedInternalBlocksFailClosed() {
        SnapshotCodec.Encoded encoded = SnapshotCodec.encodePair(map(), internalFixture(20));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decodeInternal(encoded.internalBody, id -> null));
        byte[] wrong = SnapshotCodec.encode(map("unrelated", true));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decodeInternal(encoded.internalBody, id -> wrong));
    }

    @Test
    public void duplicateManifestPathsAndUnknownVersionsAreRejected() {
        SnapshotCodec.Encoded encoded = SnapshotCodec.encodePair(map(), internalFixture(20));
        Map<String, Object> manifest = SnapshotCodec.decode(encoded.internalBody);
        @SuppressWarnings("unchecked") List<Object> blocks = (List<Object>) manifest.get("blocks");
        blocks.add(blocks.get(0));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decodeInternal(
                SnapshotCodec.encode(manifest), encoded.internalBlocks::get));
        manifest.put("format", "future-unknown-version");
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decodeInternal(
                SnapshotCodec.encode(manifest), encoded.internalBlocks::get));
    }

    private static Map<String, Object> internalFixture(int hp) {
        return map("profile_files", map("status", "captured_declared_scope", "files", Arrays.asList(
                        map("path", "game1/depth1.dat", "encoding", "base64", "data", "AAECAwQ=", "size", 5))),
                "coverage", map("static_roots", Arrays.asList("Dungeon", "Actor"),
                        "included_fields", Arrays.asList("Actor#all", "Dungeon#hero"),
                        "limitations", Arrays.asList("native"), "unavailable", new ArrayList<>()),
                "build", map("version", "fixture"), "nodes", map(
                        "n1", map("type", "Hero", "hp", hp, "level", map("$ref", "n2")),
                        "n2", map("type", "Level", "map", Arrays.asList(1, 1, 3))),
                "empty", null, "large", new BigInteger("98765432109876543210987654321"));
    }
}
