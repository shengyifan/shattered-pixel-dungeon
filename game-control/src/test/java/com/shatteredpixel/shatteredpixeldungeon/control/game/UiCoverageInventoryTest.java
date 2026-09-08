package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class UiCoverageInventoryTest {
    private static Map<String, Object> manifest;
    private static List<Map<String, Object>> records;

    @SuppressWarnings("unchecked")
    @BeforeAll static void parseCurrentSources() throws Exception {
        manifest = UiCoverageInventory.generate(UiCoverageInventory.repositoryRoot());
        records = (List<Map<String, Object>>) manifest.get("records");
    }

    @Test void checkedInventoryMatchesCurrentDeclarationsAndMethodBodies() throws Exception {
        Map<String, Object> baseline = JsonCodec.decode(Files.readString(UiCoverageInventory.repositoryRoot().resolve("docs/cli-ui-coverage.json")));
        assertEquals(JsonCodec.encode(baseline), JsonCodec.encode(manifest),
                "UI source changed: run :game-control:generateUiCoverage and review the inventory diff; this is not runtime acceptance");
    }

    @Test void everyDiscoveredInputHasAnExplicitStaticRoute() {
        List<String> missing = records.stream().filter(r -> "UNMAPPED".equals(r.get("route")))
                .map(r -> (String) r.get("id")).collect(Collectors.toList());
        assertTrue(missing.isEmpty(), () -> "No semantic route for:\n" + String.join("\n", missing));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) manifest.get("summary")).get("runtime_verified_by_inventory"));
        assertTrue(records.stream().allMatch(r -> "static_only".equals(r.get("verification"))));
    }

    @Test void scannerIncludesNestedAndNonButtonInteractionFamilies() {
        String text = JsonCodec.encode(records);
        for (String needed : List.of("Trinity$WndUseTrinity", "Trinity$WndItemtypeSelect", "DriedRose$WndGhostHero",
                "ScrollOfMetamorphosis$WndMetamorphChoose", "ScrollOfEnchantment$WndConfirmCancel",
                "WndKeyBindings$WndChangeBinding", "RightClickMenu", "RadialMenu", "ItemButton",
                "CellSelector$Listener", "WndBag$ItemSelector", "WndBlacksmith$WndReforge")) {
            assertTrue(text.contains(needed), needed);
        }
    }

    @Test void currentCharacterAndAbilityInventoryHasTheExpectedScope() {
        Map<String, Object> hero = records.stream().filter(r -> "hero_class_catalog".equals(r.get("category"))).findFirst().orElseThrow();
        Map<String, Object> subclass = records.stream().filter(r -> "hero_subclass_catalog".equals(r.get("category"))).findFirst().orElseThrow();
        assertEquals(List.of("WARRIOR", "MAGE", "ROGUE", "HUNTRESS", "DUELIST", "CLERIC"), hero.get("enum_values"));
        assertEquals(13, ((List<?>) subclass.get("enum_values")).size()); // NONE plus 12 actual subclasses.
        assertEquals(20, records.stream().filter(r -> "armor_ability".equals(r.get("category"))).count()); // Abstract base + 19.
        assertEquals(30, records.stream().filter(r -> "cleric_spell".equals(r.get("category"))).count()); // 3 bases + 27 spells.
    }

    @Test void unknownInputCannotBeSilentlyMarkedCoveredAndParsingDoesNotInitializeClasses(@TempDir Path root) throws Exception {
        Path source = root.resolve("core/src/main/java/fake/NewInput.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(root.resolve("SPD-classes/src/main/java"));
        Files.writeString(source, "package fake; class NewInput extends PointerArea {"
                + " static { if (true) throw new AssertionError(\"must never initialize\"); }"
                + " boolean onSignal(KeyEvent event) { return true; } }");
        Map<String, Object> generated = UiCoverageInventory.generate(root);
        assertEquals(1, ((Number) ((Map<?, ?>) generated.get("summary")).get("unmapped_inputs")).intValue());
    }
}
