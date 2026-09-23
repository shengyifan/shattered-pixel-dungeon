package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameplayIcons;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strictly offline size probe. Input is a frozen public v7 assertion projection,
 * prepared from raw transport by cli8_token_study.py. This class cannot launch a
 * game, open a profile or reconstruct native capture identities from pixels.
 * Unmapped graphic descriptors remain explicit partial evidence.
 */
public final class Cli8CorpusProbe {
    private Cli8CorpusProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Cli8CorpusProbe INPUT_JSONL OUTPUT_JSONL");
        System.err.println("PRODUCTION_CLASS_SOURCES " + JsonCodec.encode(fields(
                "CompactProtocol", classSource(CompactProtocol.class),
                "GameplayObservation", classSource(GameplayObservation.class),
                "GameplayEvidence", classSource(GameplayEvidence.class))));
        int frames = 0;
        try (BufferedReader input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
             BufferedWriter output = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty()) throw new IllegalArgumentException("Empty input frame at " + frames);
                Map<String,Object> source = JsonCodec.decode(line);
                String id = string(source.get("id"), "id");
                String status = string(source.get("st"), "st");
                String scope = source.get("s") instanceof String ? (String)source.get("s") : null;
                Map<String,Object> canonical = map(source.get("result"));
                if (canonical.containsKey("schema")) {
                    canonical = copy(canonical);
                    canonical.put("schema", CompactProtocol.info());
                    canonical.put("cli_version", "CLI.8.0.0");
                    canonical.put("audit_schema_version", 11);
                    canonical.put("build_id", "offline_projection_not_a_build_identity");
                }
                Object observed = canonical.get("observation");
                if (observed instanceof Map) {
                    Map<String,Object> observation = copy(map(observed));
                    sanitizeObservation(observation);
                    attachPublicHints(observation, canonical);
                    canonical = copy(canonical);
                    canonical.put("observation", GameplayObservation.merge(observation));
                }
                if (scope != null) canonical.putIfAbsent("scope_id", scope);
                if (source.get("rev") instanceof String) canonical.putIfAbsent("state_version", source.get("rev"));
                Map<String,Object> projected = CompactProtocol.success(id, scope, status, canonical,
                        Boolean.TRUE.equals(source.get("live")),
                        Boolean.TRUE.equals(source.get("sources")),
                        Boolean.TRUE.equals(source.get("full")));
                output.write(JsonCodec.encode(projected));
                output.newLine();
                frames++;
            }
        }
        System.err.println("Cli8CorpusProbe projected " + frames + " frozen frames");
    }

    private static void sanitizeObservation(Map<String,Object> observation) {
        Object rawUi = observation.get("ui");
        if (rawUi instanceof Map) {
            Map<String,Object> ui = map(rawUi);
            Object rawControls = ui.get("controls");
            if (rawControls instanceof List) {
                List<Object> controls = new ArrayList<>();
                for (Object raw : (List<?>)rawControls) {
                    if (raw instanceof Map) controls.add(sanitizeNode(map(raw)));
                    else controls.add(raw);
                }
                ui.put("controls", controls);
            }
        }
        Object rawCues = observation.get("visual_cues");
        if (rawCues instanceof Map) {
            Map<String,Object> cues = map(rawCues);
            cues.remove("metrics");cues.remove("metrics_at");
            cues.remove("screen_effects");cues.remove("screen_effects_at");
            if ("last_rendered".equals(cues.get("status"))) cues.put("status", "last_observed");
            Object rawList = cues.get("cues");
            if (rawList instanceof List) {
                List<Object> semantic = new ArrayList<>();
                for (Object raw : (List<?>)rawList) {
                    if (!(raw instanceof Map)) { semantic.add(raw); continue; }
                    Map<String,Object> cue = copy(map(raw));
                    if ("sprite_state_appearance".equals(cue.get("kind"))) cue.put("kind", "sprite_state");
                    if (cue.get("appearance") instanceof Map) {
                        Map<String,Object> appearance = copy(map(cue.get("appearance")));
                        if (appearance.get("tint_style") instanceof String && !appearance.containsKey("style"))
                            appearance.put("style", appearance.remove("tint_style"));
                        cue.put("appearance", appearance);
                    }
                    semantic.add(GameplayEvidence.semantic(cue));
                }
                cues.put("cues", semantic);
            }
        }
    }

    private static Map<String,Object> sanitizeNode(Map<String,Object> source) {
        Map<String,Object> node = copy(source);
        Object color = node.remove("color");
        if (color instanceof Number && !node.containsKey("tone"))
            node.putAll(GameplayIcons.textTone(((Number)color).intValue()));
        Object rawStyles = node.remove("styles");
        if (rawStyles instanceof List) {
            List<Object> spans = new ArrayList<>();
            for (Object raw : (List<?>)rawStyles) {
                if (!(raw instanceof Map)) continue;
                Map<String,Object> span = copy(map(raw));
                Object runColor = span.remove("color");
                if (runColor instanceof Number)span.putAll(GameplayIcons.textTone(((Number)runColor).intValue()));
                spans.add(GameplayEvidence.semantic(span));
            }
            if (!spans.isEmpty()) node.put("spans", spans);
        }
        Object rawTurn = node.get("turn_progress");
        if (rawTurn instanceof Map) {
            Map<String,Object> turn = new LinkedHashMap<>();
            Object sweep = map(rawTurn).get("sweep");
            if (sweep instanceof Number && !(sweep instanceof Boolean))turn.put("sweep", sweep);
            node.put("turn_progress", turn);
        }
        Object overlay = node.remove("icon_overlay");
        if (overlay instanceof Map) {
            Map<String,Object> pixels = map(overlay);
            Object covered = pixels.get("covered_pixels"), total = pixels.get("total_pixels");
            if ("rendered_pixels".equals(pixels.get("measurement")) && covered instanceof Integer && total instanceof Integer
                    && (Integer)total > 0 && (Integer)covered >= 0 && (Integer)covered <= (Integer)total) {
                Map<String,Object> shown = node.get("shown") instanceof Map ? copy(map(node.get("shown"))) : new LinkedHashMap<>();
                shown.put("progress", fields("covered", covered, "total", total, "basis", "displayed"));
                node.put("shown", shown);
            } else node.put("unmapped_indicator", true);
        }
        for (String key : new String[]{"icon", "item_icon", "item_badge", "hero_portrait", "boss_icon",
                "target_icon", "spell_icon", "primary_icon", "secondary_icon"}) {
            if (node.get(key) instanceof Map) node.put(key, GameplayIcons.unmapped());
        }
        if (node.get("preview_icons") instanceof List) {
            List<Object> previews = new ArrayList<>();
            for (Object icon : (List<?>)node.get("preview_icons"))
                previews.add(icon instanceof Map ? GameplayIcons.unmapped() : icon);
            node.put("preview_icons", previews);
        }
        return GameplayEvidence.semantic(node);
    }

    private static void attachPublicHints(Map<String,Object> observation, Map<String,Object> canonical) {
        Object rawUi = observation.get("ui");
        if (!(rawUi instanceof Map) || !(map(rawUi).get("controls") instanceof List)) return;
        Map<String,Object> ui = map(rawUi);
        List<?> controls = (List<?>)ui.get("controls");
        Map<String,Integer> locators = new HashMap<>();
        if (observation.get("inventory") instanceof List)
            for (Object raw : (List<?>)observation.get("inventory")) countLocator(raw, locators);
        if (observation.get("visible_entities") instanceof List)
            for (Object raw : (List<?>)observation.get("visible_entities"))
                if (raw instanceof Map) countLocator(map(raw).get("item"), locators);
        Set<String> actionable = new HashSet<>();
        if (canonical.get("actions") instanceof List)
            for (Object raw : (List<?>)canonical.get("actions"))
                if (raw instanceof Map && map(raw).get("control") instanceof String)
                    actionable.add((String)map(raw).get("control"));
        Map<String,List<String>> owned = new HashMap<>();
        for (Object raw : controls) if (raw instanceof Map) {
            Map<String,Object> node = map(raw);
            if ("text".equals(node.get("role")) && node.get("parent") instanceof String
                    && node.get("id") instanceof String)
                owned.computeIfAbsent((String)node.get("parent"), ignored -> new ArrayList<>()).add((String)node.get("id"));
        }
        Map<String,UiProjectionHints.Node> hints = new LinkedHashMap<>();
        for (Object raw : controls) if (raw instanceof Map) {
            Map<String,Object> node = map(raw);
            if (!(node.get("id") instanceof String)) continue;
            String id = (String)node.get("id");
            String locator = node.get("locator") instanceof String ? (String)node.get("locator") : null;
            Map<String,Object> subject = locator != null && Objects.equals(locators.get(locator), 1)
                    ? fields("kind", "item", "loc", locator) : null;
            String feedback = null;
            if ("floating_text".equals(node.get("presentation"))) feedback = "floating";
            else if (node.get("banner_kind") instanceof String) feedback = "banner";
            else if ("game_log".equals(node.get("presentation"))) feedback = "log";
            List<String> children = owned.getOrDefault(id, Collections.emptyList());
            if (subject != null || feedback != null || !children.isEmpty() || actionable.contains(id))
                hints.put(id, new UiProjectionHints.Node(false, children, locator,
                        Collections.emptyMap(), subject, feedback, actionable.contains(id)));
        }
        if (!hints.isEmpty()) observation.put("ui", new UiProjectionHints(hints).attach(ui));
    }

    private static void countLocator(Object raw, Map<String,Integer> locators) {
        if (raw instanceof Map && map(raw).get("locator") instanceof String) {
            String loc = (String)map(raw).get("locator");
            locators.put(loc, locators.getOrDefault(loc, 0) + 1);
        }
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    private static Map<String,Object> copy(Map<String,Object> value) { return map(JsonCodec.decode(JsonCodec.encode(value))); }
    private static String string(Object value, String field) {
        if (!(value instanceof String) || ((String)value).isEmpty()) throw new IllegalArgumentException(field + " missing");
        return (String)value;
    }
    private static String classSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().toString();
    }
    private static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2)result.put((String)pairs[index], pairs[index + 1]);
        return result;
    }
}
