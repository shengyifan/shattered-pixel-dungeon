package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.game.PublicEnglishProjection;
import com.shatteredpixel.shatteredpixeldungeon.control.game.CompactProtocol;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** TEST ONLY. Reads public JSON and bundled strings; never creates a game, model, window or backend. */
public final class EnglishCorpusProbe {
    private static final Set<String> NODE_METADATA = new HashSet<>(Arrays.asList(
            "id", "role", "parent", "control", "action", "shortcut_action", "checked",
            "minimum", "maximum", "min", "max", "value", "enabled", "active", "visible",
            "available", "clipped", "scene", "ctl", "op", "g", "opt", "alt", "loc"));
    private final Map<List<Object>, Outcome> cache = new HashMap<>();
    private final Map<List<Object>, Map<String, Object>> issues = new LinkedHashMap<>();
    private final Map<String, Long> sceneOccurrences = new LinkedHashMap<>();
    private long frames, strings, translated, failures, partials, compactFrames;
    private boolean compactFrame;
    private final Map<String,String> compactDiagnostics = new LinkedHashMap<>();

    private static final class UiContext {
        final Map<String, Object> ui;
        final String key;
        UiContext(Map<String, Object> ui) {
            this.ui = ui;
            // Immutable cache identity. Later responses can change the same visible controls.
            key = JsonCodec.encode(ui);
        }
    }

    private static final class Outcome {
        final String status, reason;
        final boolean changed;
        Outcome(String status, String reason, boolean changed) {
            this.status = status; this.reason = reason; this.changed = changed;
        }
    }

    @SuppressWarnings("unchecked")
    public void inspect(Map<String, Object> response, Map<String, Object> sample) {
        frames++;
        compactFrame = response.get("v") instanceof Number && ((Number)response.get("v")).intValue() == 8;
        if (compactFrame) response=(Map<String,Object>)CompactProtocol.expandStructures(response);
        compactDiagnostics.clear();
        if (compactFrame) { compactFrames++; collectCompactDiagnostics(response, "/response"); }
        walk(response, null, null, null, map(), false, "/response", "/response", sample,
                new IdentityHashMap<>());
    }

    private void collectCompactDiagnostics(Object value, String path) {
        if (value instanceof Map) {
            Map<?,?> object = (Map<?,?>) value;
            Object presentation = object.get("pres");
            Object diagnostics = presentation instanceof Map ? ((Map<?,?>)presentation).get("diag") : null;
            if (diagnostics instanceof List) for (Object item : (List<?>)diagnostics) {
                if (!(item instanceof Map)) continue;
                Map<?,?> diagnostic = (Map<?,?>)item;
                Object field = diagnostic.get("field"), code = diagnostic.get("code");
                if (!(field instanceof String) || code == null) continue;
                String recorded = (String)field;
                String pointer = recorded.startsWith("$") ? "/response" : path;
                for (String part : recorded.replaceFirst("^\\$\\.?", "").replaceAll("\\[([0-9]+)\\]", ".$1").split("\\."))
                    if (!part.isEmpty()) pointer += "/" + part.replace("~", "~0").replace("/", "~1");
                compactDiagnostics.put(pointer, code.toString());
            }
            for (Map.Entry<?,?> entry : object.entrySet()) {
                String key = entry.getKey().toString();
                if (Arrays.asList("pres", "text_sources", "text_origins", "raw", "reply", "schema").contains(key)) continue;
                collectCompactDiagnostics(entry.getValue(), path + "/" + key.replace("~", "~0").replace("/", "~1"));
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>)value;
            for (int i = 0; i < list.size(); i++) collectCompactDiagnostics(list.get(i), path + "/" + i);
        }
    }

    private void walk(Object value, String field, String publicScene, UiContext inheritedUi,
                      Map<String, Object> ownerMetadata, boolean clipped, String path, String pattern,
                      Map<String, Object> sample, IdentityHashMap<Map<?, ?>, UiContext> frameUis) {
        if (value instanceof Map) {
            Map<?, ?> object = (Map<?, ?>) value;
            String scene = scene(object, publicScene);
            Map<String, Object> foundUi = publicUi(object);
            UiContext context = foundUi == null ? inheritedUi : frameUis.computeIfAbsent(foundUi, ignored -> new UiContext(foundUi));
            Map<String, Object> metadata = metadata(object);
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                String key = (String) entry.getKey();
                if (Arrays.asList("text_sources","text_origins","text_diagnostics","presentation","pres","response","reply","raw","schema","response_json","raw_request","raw_bytes","original_payload").contains(key)) continue;
                String suffix = "/" + key.replace("~", "~0").replace("/", "~1");
                walk(entry.getValue(), key, scene, context, metadata,
                        key.equals("text") && Boolean.TRUE.equals(object.get("clipped")),
                        path + suffix, pattern + suffix, sample, frameUis);
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++)
                walk(list.get(i), field, publicScene, inheritedUi, ownerMetadata, clipped,
                        path + "/" + i, pattern + "/*", sample, frameUis);
        } else if (value instanceof String && field != null) {
            inspectString((String) value, field, publicScene, inheritedUi, ownerMetadata, clipped, path, pattern, sample);
        }
    }

    private void inspectString(String original, String field, String scene, UiContext ui,
                               Map<String, Object> metadata, boolean clipped,
                               String path, String pattern, Map<String, Object> sample) {
        strings++;
        // Source identity, rather than matching surface text or its script, controls rendering.
        String recordedDiagnostic = compactFrame ? compactDiagnostics.get(path) : null;
        List<Object> key = Arrays.asList(scene, field, clipped, original, metadata, compactFrame, recordedDiagnostic);
        // Protocol 7 already contains rendered public text. Omitted source trees
        // are not evidence of missing provenance: only its recorded diagnostics
        // identify unavailable/partial fields. Never reclassify user text by script.
        Outcome outcome = cache.computeIfAbsent(key, ignored -> compactFrame
                ? new Outcome(recordedDiagnostic == null ? "ok" : clipped ? "partial" : "unavailable", recordedDiagnostic, false)
                : translate(original, field, scene, ui, metadata, clipped));
        if (outcome.changed) translated++;
        if (outcome.status.equals("ok")) return;
        if (outcome.status.equals("unavailable")) failures++;
        else partials++;
        sceneOccurrences.merge(scene == null ? "<no-public-scene>" : scene, 1L, Long::sum);
        List<Object> issueKey = Arrays.asList(scene, pattern, clipped, original, outcome.status, outcome.reason);
        Map<String, Object> issue = issues.computeIfAbsent(issueKey, ignored -> map(
                "scene", scene, "field", field, "field_path", pattern, "clipped", clipped,
                "original", original, "status", outcome.status, "reason", outcome.reason,
                "frequency", 0L, "samples", new ArrayList<>()));
        issue.put("frequency", ((Number) issue.get("frequency")).longValue() + 1);
        @SuppressWarnings("unchecked") List<Map<String, Object>> samples = (List<Map<String, Object>>) issue.get("samples");
        if (samples.size() < 3) {
            Map<String, Object> occurrence = new LinkedHashMap<>(sample);
            occurrence.put("json_pointer", path);
            occurrence.put("node_metadata", metadata);
            occurrence.put("complete_public_ui_available", ui != null);
            samples.add(occurrence);
        }
    }

    private Outcome translate(String original, String field, String scene, UiContext ui,
                              Map<String, Object> metadata, boolean clipped) {
        // Delegate field selection to the actual production projector, not a copied whitelist.
        Map<String, Object> leaf = new LinkedHashMap<>(metadata);
        leaf.put(field, original);
        if (clipped) leaf.put("clipped", true);
        // Frozen protocol-2 output already has a source or a recorded per-field diagnostic.
        // Inspect this leaf without borrowing any neighboring field's provenance.
        for(String sidecar:Arrays.asList("text_sources","text_diagnostics")) {
            Object entries=metadata.get(sidecar);
            if(entries instanceof Map && ((Map<?,?>)entries).containsKey(field))
                leaf.put(sidecar,map(field,((Map<?,?>)entries).get(field)));
            else leaf.remove(sidecar);
        }
        Map<String,Object> result=project(leaf,scene,ui);
        Object diagnostics=result.get("text_diagnostics");
        Object reason=diagnostics instanceof Map?((Map<?,?>)diagnostics).get(field):null;
        if(reason!=null)return new Outcome(clipped?"partial":"unavailable",reason.toString(),!Objects.equals(original,result.get(field)));
        return new Outcome("ok",null,!Objects.equals(original,result.get(field)));
    }

    private static Map<String, Object> project(Map<String, Object> leaf, String scene, UiContext ui) {
        return ui == null ? PublicEnglishProjection.copyInScene(leaf, scene)
                : PublicEnglishProjection.copyWithUi(leaf, ui.ui);
    }

    private static Map<String, Object> metadata(Map<?, ?> owner) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : NODE_METADATA) {
            Object value = owner.get(key);
            if (owner.containsKey(key) && (value == null || value instanceof String
                    || value instanceof Number || value instanceof Boolean)) result.put(key, value);
        }
        for(String sidecar:Arrays.asList("text_sources","text_diagnostics"))
            if(owner.get(sidecar) instanceof Map)result.put(sidecar,owner.get(sidecar));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> publicUi(Map<?, ?> source) {
        Object data = source.get("data");
        if (data instanceof Map) return publicUi((Map<?,?>)data);
        Object observation = source.get("observation");
        if (observation instanceof Map) return publicUi((Map<?, ?>) observation);
        Object nested = source.get("ui");
        if (nested instanceof Map) return publicUi((Map<?, ?>) nested);
        return source.get("scene") instanceof String && (source.get("controls") instanceof List || source.get("nodes") instanceof List)
                ? (Map<String, Object>) source : null;
    }

    // Match the production DTO context order, using only fields already present in this response.
    // Never borrow a later/current scene to disambiguate historical event text.
    private static String scene(Map<?, ?> value, String inherited) {
        Object data = value.get("data");
        if (data instanceof Map) return scene((Map<?,?>)data, inherited);
        Object observation = value.get("observation");
        if (observation instanceof Map) return scene((Map<?, ?>) observation, inherited);
        Object ui = value.get("ui");
        if (ui instanceof Map) return scene((Map<?, ?>) ui, inherited);
        Object own = value.get("scene");
        return own instanceof String ? (String) own : inherited;
    }

    public Map<String, Object> report() {
        List<Map<String, Object>> result = new ArrayList<>(issues.values());
        result.sort(Comparator.comparingLong((Map<String, Object> value) ->
                ((Number) value.get("frequency")).longValue()).reversed());
        return map("test_only", true, "source", "closed_fixture_public_responses_only",
                "projection_context", "complete_public_ui_and_leaf_node_metadata",
                "frames", frames, "protocol_8_frames", compactFrames, "string_occurrences", strings, "unique_translation_inputs", cache.size(),
                "translated_occurrences", translated, "unavailable_occurrences", failures,
                "partial_occurrences", partials, "unique_issues", result.size(),
                "issue_occurrences_by_scene", sceneOccurrences, "issues", result,
                "limits", Arrays.asList("Protocol 5 rendered output is assessed using its recorded per-field presentation diagnostics; omitted source trees are not independently retranslated.",
                        "Unknown/opaque fields follow PublicEnglishProjection and are not reclassified as prose.",
                        "Each leaf retains public node identity/role/parent/control/shortcut/checkbox/slider metadata; the same response's complete public UI is context only, not recursively translated with the leaf.",
                        "Partial clipped fallback is reported separately from a rejected complete string.",
                        "No current scene is inferred for historical records without a scene in their own public DTO.",
                        "A historical Chinese model/hover field may now be generated directly in English before this projector; a corpus rejection alone is not proof of a current live failure.",
                        "Successful ASCII strings are summarized; only failures/partials retain original text."));
    }

    static Path checkedTrace(Path repo, String relative) throws IOException {
        Path fixtures = repo.resolve("desktop-control/build/fixtures").normalize();
        Path trace = repo.resolve(relative).normalize().toAbsolutePath();
        if (!trace.startsWith(fixtures) || trace.equals(fixtures)
                || !trace.getFileName().toString().equals("public-trace.jsonl"))
            throw new IllegalArgumentException("Only build/fixtures public-trace.jsonl inputs are accepted");
        rejectSymlinks(repo, trace);
        if (!Files.isRegularFile(trace, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Public trace is not a regular file");
        try (Stream<Path> files = Files.list(trace.getParent())) {
            if (files.noneMatch(path -> path.getFileName().toString().endsWith("-result.json")
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)))
                throw new IllegalArgumentException("Fixture has no completed test report");
        }
        String profile = trace.getParent().toString();
        Set<Long> probeAncestors = new HashSet<>();
        ProcessHandle ancestor = ProcessHandle.current();
        while (ancestor != null) {
            probeAncestors.add(ancestor.pid());
            ancestor = ancestor.parent().orElse(null);
        }
        boolean active;
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            active = processes.filter(process -> !probeAncestors.contains(process.pid())).anyMatch(process ->
                    Arrays.stream(process.info().arguments().orElse(new String[0])).anyMatch(argument -> argument.contains(profile)));
        }
        if (active) throw new IllegalArgumentException("Fixture is still referenced by a running process");
        return trace;
    }

    private static void rejectSymlinks(Path root, Path path) throws IOException {
        Path current = root;
        for (Path component : root.relativize(path)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not accepted");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: EnglishCorpusProbe REPO INPUT_MANIFEST OUTPUT_JSON");
        Path repo = Path.of(args[0]).toRealPath();
        Path manifestPath = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        Path destinationRoot = repo.resolve("desktop-control/build/english-corpus");
        if (!manifestPath.startsWith(destinationRoot) || !output.startsWith(destinationRoot))
            throw new IllegalArgumentException("Probe inputs manifest and diagnostics must stay in build/english-corpus");
        rejectSymlinks(repo, manifestPath);
        rejectSymlinks(repo, output);
        Map<String, Object> manifest = JsonCodec.decode(Files.readString(manifestPath));
        EnglishCorpusProbe probe = new EnglishCorpusProbe();
        List<Object> processed = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        for (Object input : (List<?>) manifest.get("traces")) {
            String relative = (String) input;
            Path trace;
            try { trace = checkedTrace(repo, relative); }
            catch (IOException | IllegalArgumentException rejected) {
                skipped.add(map("trace", relative, "reason", rejected.getMessage()));
                continue;
            }
            long size = Files.size(trace);
            FileTime modified = Files.getLastModifiedTime(trace);
            long lineNumber = 0, responses = 0;
            try (BufferedReader reader = Files.newBufferedReader(trace, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    Map<String, Object> row;
                    try { row = JsonCodec.decode(line); }
                    catch (RuntimeException invalid) {
                        skipped.add(map("trace", relative, "line", lineNumber, "reason", "invalid_public_json"));
                        continue;
                    }
                    Object response = row.get("response");
                    if (!(response instanceof Map)) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) response;
                    probe.inspect(value, map("profile", repo.relativize(trace.getParent()).toString(),
                            "trace", relative, "line", lineNumber, "request_id", value.get("id"), "op", row.get("op")));
                    responses++;
                }
            }
            if (Files.size(trace) != size || !Files.getLastModifiedTime(trace).equals(modified))
                throw new IllegalStateException("An input trace changed during the offline scan; discard this incomplete run");
            processed.add(map("trace", relative, "bytes", size, "lines", lineNumber, "responses", responses));
        }
        Map<String, Object> report = probe.report();
        report.put("processed", processed);
        report.put("skipped", skipped);
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonCodec.encode(report) + "\n", StandardCharsets.UTF_8);
        System.out.println(JsonCodec.encode(map("test_only", true, "profiles", processed.size(), "frames", probe.frames,
                "unavailable_occurrences", probe.failures, "partial_occurrences", probe.partials,
                "unique_issues", probe.issues.size(), "skipped", skipped.size(), "report", repo.relativize(output).toString())));
    }
}
