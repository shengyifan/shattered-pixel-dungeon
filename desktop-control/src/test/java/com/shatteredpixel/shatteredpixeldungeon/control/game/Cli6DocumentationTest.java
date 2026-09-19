package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ControlRequest;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.Identifiers;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.WireNames;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Current documentation is executable public protocol evidence, never a game fixture. */
public class Cli6DocumentationTest {
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern FENCE = Pattern.compile("(?ms)^```(json|python)([^\\r\\n]*)\\r?\\n(.*?)^```[ \\t]*$");
    private static final List<String> ALIASED_FIELDS = Arrays.asList(
            "max_experience", "max_hp", "subclass_name", "talent_points_available",
            "details_via", "shortcut_action", "cell_prompt", "continuous_activity",
            "snapshot_status", "inspected_item", "saves_during_request", "last_save",
            "receipt_id", "origin_scope_id", "origin_request_id", "occurred_at");

    private static Path root() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("docs/cli-help.md"))
                    && Files.isDirectory(candidate.resolve("control-protocol"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new AssertionError("Cannot locate the source checkout containing docs/cli-help.md");
    }

    private static String document(String name) throws Exception {
        return Files.readString(root().resolve(name), StandardCharsets.UTF_8);
    }

    private static Map<?, ?> object(Object value) {
        assertTrue("Expected a documented JSON object: " + value, value instanceof Map);
        return (Map<?, ?>) value;
    }

    private static List<?> array(Object value) {
        assertTrue("Expected a documented JSON array: " + value, value instanceof List);
        return (List<?>) value;
    }

    private static List<Set<String>> tableCodeRows(String text) {
        List<Set<String>> rows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.stripLeading().startsWith("|")) continue;
            Set<String> tokens = new HashSet<>();
            Matcher code = CODE.matcher(line);
            while (code.find()) tokens.add(code.group(1));
            rows.add(tokens);
        }
        return rows;
    }

    private static boolean hasRow(List<Set<String>> rows, String... tokens) {
        for (Set<String> row : rows) if (row.containsAll(Arrays.asList(tokens))) return true;
        return false;
    }

    private static String firstSection(String text) {
        int nextSection = text.indexOf("\n## ");
        return nextSection < 0 ? text : text.substring(0, nextSection);
    }

    private static void hasVersion(String description, String text, String label, int version) {
        assertTrue(description + " must declare " + label + " " + version,
                Pattern.compile("(?i)\\b" + Pattern.quote(label) + "[\\s.-]+" + version + "\\b").matcher(text).find());
    }

    @Test public void currentEntryPointsDeclareTheProductionVersionsAndIndependentProfile() throws Exception {
        int protocol = ControlRequest.PROTOCOL_VERSION;
        Map<String, Object> build = BuildCatalog.current();
        assertEquals("This suite protects the CLI 6 contract", 6, protocol);
        assertEquals(9, AuditStore.SCHEMA_VERSION);
        assertEquals(protocol, ((Number) build.get("protocol_version")).intValue());
        assertEquals(AuditStore.SCHEMA_VERSION, ((Number) build.get("audit_schema_version")).intValue());
        String cli = (String) build.get("cli_version");
        assertNotNull("The production build catalog must supply cli_version", cli);
        assertTrue(cli.startsWith("CLI.6."));

        String help = document("docs/cli-help.md");
        assertTrue("The help introduction must declare the current CLI version", firstSection(help).contains(cli));
        hasVersion("The help introduction", firstSection(help), "protocol", protocol);
        hasVersion("The help", help, "schema", AuditStore.SCHEMA_VERSION);
        assertTrue("The current profile path must be documented", help.contains(
                "~/Library/Application Support/Shattered Pixel Dungeon CLI v" + protocol + "/"));
        assertTrue("The current index must declare the production CLI version", firstSection(document("docs/cli.md")).contains(cli));
        String agreements = document("AGENTS.md");
        assertTrue("Project agreements must point to the authoritative help", agreements.contains("docs/cli-help.md"));
        hasVersion("Project agreements", agreements, "CLI", protocol);
        hasVersion("Project agreements", agreements, "protocol", protocol);
        hasVersion("Project agreements", agreements, "schema", AuditStore.SCHEMA_VERSION);
        assertTrue("The current implementation reference must exist", Files.isRegularFile(root().resolve("docs/cli6-implementation.md")));
    }

    @Test public void allSixteenNewAliasesMatchTheProductionWireAndInfoTables() throws Exception {
        List<Set<String>> rows = tableCodeRows(document("docs/cli-help.md"));
        Map<?, ?> advertised = object(CompactProtocol.info().get("aliases"));
        assertEquals(16, ALIASED_FIELDS.size());
        for (String canonical : ALIASED_FIELDS) {
            String wire = WireNames.field(canonical);
            assertNotEquals("A CLI 6 alias must exist for " + canonical, canonical, wire);
            assertEquals("info must advertise the same alias", canonical, advertised.get(wire));
            assertTrue("Help needs a table row mapping " + canonical + " to " + wire, hasRow(rows, canonical, wire));
        }
        assertEquals("shortcut", WireNames.field("shortcut_action"));
        for (Set<String> row : rows) {
            if (row.contains("shortcut_action")) assertFalse("shortcut_action must not be documented as key", row.contains("key"));
        }
    }

    @Test public void documentedScopedDefaultsAndVisibilityMatchInfo() throws Exception {
        String help = document("docs/cli-help.md");
        List<Set<String>> rows = tableCodeRows(help);
        Map<?, ?> defaults = object(CompactProtocol.info().get("defaults"));
        for (String context : Arrays.asList("item", "ui_node", "ui")) {
            for (Map.Entry<?, ?> entry : object(defaults.get(context)).entrySet()) {
                Object value = entry.getValue();
                String literal = value instanceof String ? (String) value : JsonCodec.encode(value);
                assertTrue("Help needs scoped default " + context + "." + entry.getKey() + "=" + literal,
                        hasRow(rows, context, String.valueOf(entry.getKey()), literal));
            }
        }
        Map<?, ?> map = object(CompactProtocol.info().get("map"));
        for (Map.Entry<?, ?> entry : object(map.get("vis")).entrySet()) {
            assertTrue("Help needs visibility " + entry.getKey() + "=" + entry.getValue(),
                    hasRow(rows, String.valueOf(entry.getKey()), String.valueOf(entry.getValue())));
        }
        assertTrue("Help must carry the exact advertised tile alphabet", help.contains((String) map.get("alphabet")));
        StringBuilder rowLayout = new StringBuilder("\\[\\s*");
        for (Object field : array(map.get("rows"))) {
            if (rowLayout.length() > "\\[\\s*".length()) rowLayout.append("\\s*,\\s*");
            rowLayout.append(Pattern.quote(String.valueOf(field)));
        }
        rowLayout.append("\\s*\\]");
        assertTrue("Help row layout must match info.map.rows", Pattern.compile(rowLayout.toString()).matcher(help).find());
    }

    /** A JSON fence may contain one value or serial NDJSON examples. Historical
     * and explicitly invalid fences are not advertised runnable requests. */
    private static List<Object> examples(String document) {
        List<Object> result = new ArrayList<>();
        Matcher fence = FENCE.matcher(document);
        while (fence.find()) {
            if (!fence.group(1).equals("json") || fence.group(2).matches("(?i).*(invalid|rejected|negative|historical).*$")) continue;
            String body = fence.group(3).strip();
            if (body.isEmpty()) continue;
            try {
                result.add(JsonCodec.decode("{\"example\":" + body + "}").get("example"));
            } catch (RuntimeException wholeFenceFailure) {
                for (String line : body.split("\\R")) {
                    if (!line.isBlank()) result.add(JsonCodec.decode("{\"example\":" + line + "}").get("example"));
                }
            }
        }
        return result;
    }

    @Test public void advertisedJsonRequestsParseWithTheProductionProtocolAndIdentifiers() throws Exception {
        Set<String> operations = new HashSet<>();
        for (Object example : examples(document("docs/cli-help.md"))) {
            if (!(example instanceof Map)) continue;
            Map<?, ?> request = object(example);
            if (!request.containsKey("op") || request.containsKey("st") || request.containsKey("err") || request.containsKey("data")) continue;
            if (!request.containsKey("v")) {
                // The packaged controller accepts intents; its inner child alone sees direct wire frames.
                assertFalse("Controller examples do not allocate their own id",request.containsKey("id"));
                assertFalse("Controller examples do not substitute their own scope",request.containsKey("s"));
                String operation=(String)request.get("op");
                assertTrue("Controller operation must be advertised or local settle",
                        "settle".equals(operation) || WireNames.operations().contains(operation));
                if (!"settle".equals(operation) && !WireNames.isQuery(operation))
                    assertTrue("Controller actions must bind an explicit shown revision",request.get("rev") instanceof String);
                continue;
            }
            ControlRequest parsed = ControlRequest.parse(JsonCodec.encode(request));
            assertEquals(ControlRequest.PROTOCOL_VERSION, parsed.protocolVersion);
            operations.add(parsed.wireOp);
            for (String key : Arrays.asList("id", "s", "rev", "rid", "ctl", "loc")) {
                if (!request.containsKey(key)) continue;
                assertTrue("Documented " + key + " must be a string", request.get(key) instanceof String);
                assertTrue("Even illustrative " + key + " placeholders must be valid identifiers",
                        Identifiers.valid((String) request.get(key), key.equals("id") ? 128 : 256));
            }
            if (!WireNames.isQuery(parsed.wireOp)) {
                assertNotNull("Action examples need scope", parsed.scopeId);
                assertNotNull("Action examples need revision", parsed.stateVersion);
            }
        }
        assertTrue("The help must include discovery, observation, receipt and action examples",
                operations.containsAll(Arrays.asList("info", "state", "req", "move", "text")));
    }

    @Test public void pythonClientProtocolLiteralMatchesTheProductionParser() throws Exception {
        Matcher fence = FENCE.matcher(document("docs/cli-help.md"));
        int versions = 0;
        while (fence.find()) {
            if (!fence.group(1).equals("python")) continue;
            Matcher version = Pattern.compile("[\"']v[\"']\\s*:\\s*(\\d+)").matcher(fence.group(3));
            while (version.find()) {
                assertEquals("The sample client's request version must match the production parser",
                        ControlRequest.PROTOCOL_VERSION, Integer.parseInt(version.group(1)));
                versions++;
            }
        }
        assertTrue("The complete-response client must declare its request protocol version", versions > 0);
    }

    @Test public void compactExamplesHaveValidLocalDictionariesAndVisibility() throws Exception {
        Set<String> demonstrated = new HashSet<>();
        for (Object example : examples(document("docs/cli-help.md"))) validateCompact(example, demonstrated);
        assertTrue("Document both dictionary forms with decodable JSON examples",
                demonstrated.containsAll(Arrays.asList("entity_defs", "effect_defs")));
        assertTrue("Document row visibility with a decodable example", demonstrated.contains("rows"));
    }

    private static int index(Object value, List<?> definitions, String field) {
        assertTrue(field + " must be an integer reference", value instanceof Long || value instanceof Integer);
        long index = ((Number) value).longValue();
        assertTrue(field + " must refer to this example's definitions", index >= 0 && index < definitions.size());
        return (int) index;
    }

    private static void validateCompact(Object value, Set<String> demonstrated) {
        if (value instanceof List) {
            List<?> values = array(value);
            if (values.size() == array(object(CompactProtocol.info().get("map")).get("rows")).size()
                    && values.get(0) instanceof Number && values.get(1) instanceof Number
                    && (values.get(2) instanceof String || values.get(2) instanceof List) && values.get(3) instanceof String) {
                validateRow(values, null);
                demonstrated.add("rows");
            } else for (Object child : values) validateCompact(child, demonstrated);
            return;
        }
        if (!(value instanceof Map)) return;
        Map<?, ?> record = object(value);
        if (record.containsKey("entity_defs")) {
            List<?> definitions = array(record.get("entity_defs"));
            assertFalse("Entity definitions should demonstrate a used dictionary", definitions.isEmpty());
            for (Object definition : definitions) assertFalse("An entity definition excludes its cell binding", object(definition).containsKey("cell"));
            List<?> entities = array(record.get("entities"));
            boolean used = false;
            for (Object raw : entities) {
                Map<?, ?> entity = object(raw);
                if (!entity.containsKey("def")) continue;
                index(entity.get("def"), definitions, "entities[].def");
                assertTrue("A referenced entity retains its cell", entity.get("cell") instanceof Number);
                used = true;
            }
            assertTrue("Entity dictionary example must contain a reference", used);
            demonstrated.add("entity_defs");
        } else if (record.get("entities") instanceof List) {
            for (Object entity : array(record.get("entities"))) assertFalse("Entity references require local definitions", object(entity).containsKey("def"));
        }
        if (record.containsKey("effect_defs")) {
            List<?> definitions = array(record.get("effect_defs"));
            assertFalse("Effect definitions should demonstrate a used dictionary", definitions.isEmpty());
            for (Object definition : definitions) for (Object effect : array(definition)) object(effect);
            boolean used = false;
            for (Object effects : object(record.get("env")).values()) {
                if (effects instanceof Number) { index(effects, definitions, "map.env[]"); used = true; }
                else for (Object effect : array(effects)) object(effect);
            }
            assertTrue("Effect dictionary example must contain a reference", used);
            demonstrated.add("effect_defs");
        } else if (record.get("env") instanceof Map) {
            for (Object effects : object(record.get("env")).values()) assertTrue("Effect references require local definitions", effects instanceof List);
        }
        if (record.containsKey("rows")) {
            List<?> types = record.containsKey("types") ? array(record.get("types")) : null;
            for (Object row : array(record.get("rows"))) validateRow(array(row), types);
            demonstrated.add("rows");
        }
        for (Object child : record.values()) validateCompact(child, demonstrated);
    }

    private static void validateRow(List<?> row, List<?> types) {
        Map<?, ?> schema = object(CompactProtocol.info().get("map"));
        assertEquals(array(schema.get("rows")).size(), row.size());
        assertTrue("Row y must be nonnegative", row.get(0) instanceof Number && ((Number) row.get(0)).longValue() >= 0);
        assertTrue("Row x_start must be nonnegative", row.get(1) instanceof Number && ((Number) row.get(1)).longValue() >= 0);
        Object tiles = row.get(2);
        int count;
        if (tiles instanceof String) {
            String alphabet = (String) schema.get("alphabet");
            count = ((String) tiles).length();
            if (types != null) assertTrue("String indexes are limited by the advertised alphabet", types.size() <= alphabet.length());
            for (char tile : ((String) tiles).toCharArray()) {
                int index = alphabet.indexOf(tile);
                assertTrue("Unknown tile alphabet character", index >= 0);
                if (types != null) assertTrue("Tile reference must be local and in range", index < types.size());
            }
        } else {
            List<?> indexes = array(tiles);
            count = indexes.size();
            for (Object raw : indexes) {
                assertTrue("Array tile index must be an integer", raw instanceof Long || raw instanceof Integer);
                assertTrue("Tile index must be nonnegative", ((Number) raw).longValue() >= 0);
                if (types != null) index(raw, types, "map.rows[].tiles[]");
            }
        }
        assertTrue("Empty rows are invalid", count > 0);
        assertTrue("Row visibility must be a string", row.get(3) instanceof String);
        String visibility = (String) row.get(3);
        assertTrue("Visibility is one repeated symbol or exactly one per tile", visibility.length() == 1 || visibility.length() == count);
        for (char symbol : visibility.toCharArray()) assertTrue("Unknown visibility symbol", object(schema.get("vis")).containsKey(String.valueOf(symbol)));
    }

    @Test public void bundledHelpIsByteForByteTheAuthoritativeDocument() throws Exception {
        try (InputStream resource = Cli6DocumentationTest.class.getResourceAsStream("/cli-help.md")) {
            assertNotNull("The runtime must include cli-help.md", resource);
            assertArrayEquals(Files.readAllBytes(root().resolve("docs/cli-help.md")), resource.readAllBytes());
        }
    }

    @Test public void currentEntryPointLinksResolveWithinTheCheckout() throws Exception {
        Pattern links = Pattern.compile("\\[[^\\]\\r\\n]*\\]\\(([^)\\s]+)\\)");
        for (String name : Arrays.asList("README.md", "AGENTS.md", "docs/README.md", "docs/cli.md",
                "docs/cli-help.md", "docs/cli-playthrough-client.md", "docs/cli6-implementation.md")) {
            Matcher link = links.matcher(document(name));
            while (link.find()) {
                String target = link.group(1);
                if (target.startsWith("#") || target.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) continue;
                String path = target.split("#", 2)[0];
                assertTrue(name + " contains a broken current entry link: " + target,
                        Files.exists(root().resolve(name).getParent().resolve(path).normalize()));
            }
        }
        for (String name : Arrays.asList("README.md", "docs/README.md", "docs/cli.md"))
            assertTrue(name + " must link to the current implementation record", document(name).contains("cli6-implementation.md"));
    }
}
