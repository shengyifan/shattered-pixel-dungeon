package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.EbonyMimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ShieldBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.effects.EmoIcon;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.AscendedForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.DivineIntervention;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.curses.Stench;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Obfuscation;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Pasty;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfMight;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfHaste;
import com.shatteredpixel.shatteredpixeldungeon.utils.Holiday;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.BurningTrap;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.watabou.utils.GameSettings;
import com.watabou.utils.Random;
import com.watabou.utils.SparseArray;
import com.watabou.noosa.Group;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Scene;
import com.watabou.noosa.ui.Component;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.ui.ItemSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.reflect.Modifier;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;
import static org.junit.jupiter.api.Assertions.*;

class GameSnapshotterTest {
    @BeforeAll
    static void resourceOnlyRuntime() {
        // No native backend, GL context, real preferences, game launch or user save access.
        Preferences preferences = (Preferences) Proxy.newProxyInstance(Preferences.class.getClassLoader(),
                new Class<?>[]{Preferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("contains")) return false;
                    if (method.getName().equals("get")) return new HashMap<>();
                    if (method.getName().startsWith("get") && args != null && args.length == 2) return args[1];
                    if (method.getReturnType() == Preferences.class) return proxy;
                    return primitiveDefault(method.getReturnType());
                });
        GameSettings.set(preferences);
        Path assets = Path.of("../core/src/main/assets").toAbsolutePath().normalize();
        Gdx.files = (Files) Proxy.newProxyInstance(Files.class.getClassLoader(), new Class<?>[]{Files.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("internal")) return new FileHandle(assets.resolve((String) args[0]).toFile());
                    if (method.getReturnType() == FileHandle.class) return new FileHandle((String) args[0]);
                    return primitiveDefault(method.getReturnType());
                });
        Gdx.app = (Application) Proxy.newProxyInstance(Application.class.getClassLoader(), new Class<?>[]{Application.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPreferences")) return preferences;
                    if (method.getName().equals("getType")) return Application.ApplicationType.Desktop;
                    return primitiveDefault(method.getReturnType());
                });
        Messages.setup(Languages.ENGLISH);
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    @AfterEach
    void clearFixture() {
        Dungeon.hero = null;
        Dungeon.level = null;
    }

    @Test
    void hiddenWorldChangesDoNotChangePublicObservation() throws Exception {
        Hero hero = hero();
        FixtureLevel level = new FixtureLevel();
        Dungeon.level = level;
        ClothArmor armor = new ClothArmor();
        hero.belongings.backpack.items.add(armor);
        level.map[5] = Terrain.WALL;
        level.visited[5] = level.heroFOV[5] = true;
        level.map[6] = Terrain.EMPTY;
        level.visited[6] = level.heroFOV[6] = true;
        Rat hidden = new Rat();
        hidden.pos = 15;
        level.mobs.add(hidden);
        Map<String, Object> first = PlayerObservation.capture(hero, level, "game");
        set(armor, Item.class, "level", 7);
        armor.cursed = true;
        hidden.HP = 1;
        hidden.pos = 14;
        level.map[5] = Terrain.SECRET_DOOR;
        level.map[6] = Terrain.SECRET_TRAP;
        level.map[15] = Terrain.EXIT;
        BurningTrap trap = new BurningTrap();
        trap.pos = 6;
        trap.visible = false;
        level.traps.put(6, trap);
        assertEquals(JsonCodec.encode(first), JsonCodec.encode(PlayerObservation.capture(hero, level, "game")));
        assertFalse(JsonCodec.encode(first).contains("BurningTrap"));
        assertFalse(JsonCodec.encode(first).contains("Rat"));
    }

    @Test
    void unknownConsumableIdentityNeverEscapesNameDescriptionOrShape() {
        Map<String, Object> healing = PlayerObservation.item(itemFixture(PotionOfHealing.class), "backpack.0", false);
        Map<String, Object> frost = PlayerObservation.item(itemFixture(PotionOfFrost.class), "backpack.0", false);
        assertEquals(healing, frost);
        assertEquals(PlayerObservation.item(itemFixture(ScrollOfIdentify.class), "backpack.0", false),
                PlayerObservation.item(itemFixture(ScrollOfUpgrade.class), "backpack.0", false));
        assertEquals(PlayerObservation.item(itemFixture(RingOfMight.class), "backpack.0", false),
                PlayerObservation.item(itemFixture(RingOfHaste.class), "backpack.0", false));
        String json = JsonCodec.encode(healing);
        assertFalse(json.contains("healing"));
        assertFalse(json.contains("PotionOf"));
    }

    @Test
    void irrelevantConsumableIdentificationBitsDoNotBecomeAHiddenMetadataOracle() {
        Item first = itemFixture(PotionOfHealing.class);
        Item second = itemFixture(PotionOfFrost.class);
        first.levelKnown = true;
        first.cursedKnown = true;
        assertEquals(PlayerObservation.item(first, "backpack.0", false),
                PlayerObservation.item(second, "backpack.0", false));
        Map<String, Object> projected = PlayerObservation.item(first, "backpack.0", false);
        assertNull(projected.get("curse_known"));
        assertNull(projected.get("level_known"));
    }

    @Test
    void spiritBowShowsTheHeroLevelDerivedUpgradeWithoutReadingHiddenRawLevel() throws Exception {
        Hero hero = hero();
        hero.lvl = 17;
        SpiritBow bow = new SpiritBow();
        bow.levelKnown = true;
        set(bow, Item.class, "level", 99);
        assertEquals(3, PlayerObservation.item(bow, "backpack.0", false).get("level"));
        assertEquals(99, SnapshotFields.integer(bow, "level"));
    }

    @Test
    void expiredDivineShieldDoesNotExposeInactiveBackingShieldPoints() throws Exception {
        Hero hero = hero();
        DivineIntervention.DivineShield shield = new DivineIntervention.DivineShield();
        set(shield, ShieldBuff.class, "shielding", 100);
        @SuppressWarnings("unchecked") Set<Buff> buffs = (Set<Buff>) SnapshotFields.read(hero, "buffs");
        buffs.add(shield);
        assertEquals(0, PlayerObservation.displayedShield(hero));
        buffs.add(new AscendedForm.AscendBuff());
        assertEquals(100, PlayerObservation.displayedShield(hero));
        assertTrue(hero.needsShieldUpdate);
        assertEquals(100, SnapshotFields.integer(shield, "shielding"));
        set(hero, Char.class, "cachedShield", 73);
        hero.needsShieldUpdate = false;
        assertEquals(73, PlayerObservation.displayedShield(hero));
        assertFalse(hero.needsShieldUpdate);
    }

    @Test
    void stealthyDisguisedMimicRemainsDisplayedOutsideFovJustLikeItsChestAppearance() throws Exception {
        Hero hero = hero();
        FixtureLevel level = new FixtureLevel();
        Dungeon.level = level;
        Mimic mimic = new Mimic();
        mimic.pos = 6;
        mimic.alignment = Char.Alignment.NEUTRAL;
        mimic.state = mimic.PASSIVE;
        set(mimic, Mimic.class, "stealthy", true);
        level.mobs.add(mimic);
        level.visited[6] = level.heroFOV[6] = true;
        Object first = PlayerObservation.capture(hero, level, "game").get("visible_entities");
        level.heroFOV[6] = false;
        assertEquals(first, PlayerObservation.capture(hero, level, "game").get("visible_entities"));
        assertTrue(JsonCodec.encode(first).contains("container"));
    }

    @Test
    void ebonyMimicOnlyDisclosesItsDisplayedOutlineNotAContainerOrEnemyClassification() {
        Hero hero = hero();
        FixtureLevel level = new FixtureLevel();
        Dungeon.level = level;
        EbonyMimic mimic = new EbonyMimic();
        mimic.pos = 6;
        mimic.alignment = Char.Alignment.NEUTRAL;
        level.mobs.add(mimic);
        level.visited[6] = level.heroFOV[6] = true;
        @SuppressWarnings("unchecked") List<Map<String, Object>> entities = (List<Map<String, Object>>)
                PlayerObservation.capture(hero, level, "game").get("visible_entities");
        assertEquals(1, entities.size());
        assertEquals("object", entities.get(0).get("kind"));
        assertEquals("suspicious outline", entities.get(0).get("name"));
        assertFalse(JsonCodec.encode(entities).contains("EbonyMimic"));
        assertFalse(entities.get(0).containsKey("hp"));
        assertFalse(entities.get(0).containsKey("buffs"));
    }

    @Test
    void environmentalDescriptionsAreVisibleOnlyAndDoNotExposeAmountsOrHiddenEffects() {
        Hero hero = hero();
        FixtureLevel level = new FixtureLevel();
        Dungeon.level = level;
        level.visited[6] = level.heroFOV[6] = true;
        Fire fire = new Fire();
        fire.cur = new int[16]; fire.cur[6] = fire.volume = 1;
        ToxicGas gas = new ToxicGas();
        gas.cur = new int[16]; gas.cur[15] = gas.volume = 4;
        level.blobs.put(Fire.class, fire); level.blobs.put(ToxicGas.class, gas);
        Map<String, Object> first = PlayerObservation.capture(hero, level, "game");
        String publicJson = JsonCodec.encode(first);
        assertTrue(publicJson.contains("\"type\":\"fire\""));
        assertFalse(publicJson.contains("ToxicGas"));
        fire.cur[6] = fire.volume = 999;
        gas.cur[15] = gas.volume = 777;
        assertEquals(first, PlayerObservation.capture(hero, level, "game"));
        gas.cur[6] = 1;
        String revealed = JsonCodec.encode(PlayerObservation.capture(hero, level, "game"));
        assertTrue(revealed.contains("\"type\":\"gas\""));
        assertFalse(revealed.contains("999"));
        assertFalse(revealed.contains("777"));
        level.heroFOV[6] = false;
        String fog = JsonCodec.encode(PlayerObservation.capture(hero, level, "game"));
        assertFalse(fog.contains("\"type\":\"fire\""));
        assertFalse(fog.contains("\"type\":\"gas\""));
    }

    @Test
    void characterInteractionMatchesContextMenuAndEmotionComesFromExistingVisibleIcon() throws Exception {
        Hero hero = hero();
        FixtureLevel level = new FixtureLevel(); Dungeon.level = level;
        level.visited[6] = level.heroFOV[6] = true;
        Rat rat = new Rat(); rat.pos = 6; level.mobs.add(rat);
        CharSprite sprite = allocateFixture(CharSprite.class);
        sprite.exists = sprite.alive = sprite.visible = true;
        EmoIcon.Sleep sleep = allocateFixture(EmoIcon.Sleep.class);
        sleep.exists = sleep.alive = sleep.visible = true;
        set(sprite, CharSprite.class, "emo", sleep); rat.sprite = sprite;
        rat.state = rat.HUNTING;
        @SuppressWarnings("unchecked") List<Map<String, Object>> visible = (List<Map<String, Object>>)
                PlayerObservation.capture(hero, level, "game").get("visible_entities");
        assertEquals("attack", visible.get(0).get("context_action"));
        assertEquals("sleeping", visible.get(0).get("emotion"));
        // Changing private AI state alone cannot affect the display projection.
        rat.state = rat.SLEEPING;
        assertEquals(visible, PlayerObservation.capture(hero, level, "game").get("visible_entities"));
        sleep.visible = false;
        rat.alignment = Char.Alignment.NEUTRAL;
        @SuppressWarnings("unchecked") List<Map<String, Object>> changed = (List<Map<String, Object>>)
                PlayerObservation.capture(hero, level, "game").get("visible_entities");
        assertEquals("interact", changed.get(0).get("context_action"));
        assertNull(changed.get(0).get("emotion"));
    }

    @Test
    void knownCurseAndLevelAreSeparateAndReadDoesNotCallWandLevel() throws Exception {
        hero();
        Wand wand = new WandOfMagicMissile();
        set(wand, Item.class, "level", 3);
        set(wand, Wand.class, "curseInfusionBonus", true);
        set(wand, Wand.class, "resinBonus", 2);
        wand.cursed = false;
        wand.cursedKnown = true;
        Map<String, Object> unknown = PlayerObservation.item(wand, "backpack.0", false);
        assertNull(unknown.get("level"));
        assertEquals(false, unknown.get("cursed"));
        wand.levelKnown = true;
        Map<String, Object> known = PlayerObservation.item(wand, "backpack.0", false);
        assertEquals(5, known.get("level"));
        assertEquals(true, SnapshotFields.read(wand, "curseInfusionBonus"));
        Map<?, ?> charges = (Map<?, ?>) known.get("charges");
        assertNull(charges.get("current"));
    }

    @Test
    void curseGlyphIsHiddenUntilKnownButVisibleGoodGlyphIsPreserved() {
        hero();
        ClothArmor armor = new ClothArmor();
        Map<String, Object> plain = PlayerObservation.item(armor, "backpack.0", false);
        armor.glyph = new Stench();
        assertEquals(plain, PlayerObservation.item(armor, "backpack.0", false));
        armor.cursedKnown = true;
        assertNotEquals(plain.get("name"), PlayerObservation.item(armor, "backpack.0", false).get("name"));
        armor.cursedKnown = false;
        armor.glyph = new Obfuscation();
        assertNotEquals(plain.get("name"), PlayerObservation.item(armor, "backpack.0", false).get("name"));
    }

    @Test
    void seasonalNameDoesNotFillHolidayCache() {
        Pasty food = new Pasty();
        Holiday.clearCachedHoliday();
        Map<String, Object> observation = PlayerObservation.item(food, "backpack.0", false);
        assertNotNull(observation.get("name"));
        assertNull(SnapshotFields.read(Holiday.class, "cached"));
    }

    @Test
    void repeatedUiItemHoverDescriptionsDoNotPopulateThePastyHolidayCache() throws Exception {
        Pasty food = new Pasty();
        ItemSlot slot = allocateFixture(ItemSlot.class);
        slot.exists = slot.alive = slot.active = slot.visible = true;
        set(slot, Group.class, "members", new ArrayList<Gizmo>());
        set(slot, ItemSlot.class, "item", food);
        Scene scene = new Scene();
        scene.add(slot);
        UiBridge bridge = new UiBridge(() -> scene);
        Holiday.clearCachedHoliday();
        String expected = Messages.titleCase(PlayerObservation.displayItemName(food));
        for (int i = 0; i < 10; i++) {
            assertTrue(JsonCodec.encode(bridge.describeUi()).contains(expected));
            bridge.describeActions();
            assertNull(SnapshotFields.read(Holiday.class, "cached"));
        }
    }

    @Test
    void alchemyOmitsUnavailableRunFieldsAndSubclassNoneHasNoMissingTextMarker() {
        Hero hero = hero();
        Map<String, Object> alchemy = PlayerObservation.capture(hero, new FixtureLevel(), "alchemy");
        assertFalse(alchemy.containsKey("hero"));
        assertFalse(alchemy.containsKey("inventory"));
        assertFalse(alchemy.containsKey("map"));
        assertFalse(alchemy.containsKey("visible_entities"));
        assertEquals("alchemy", GameSnapshotter.sceneName("AlchemyScene"));
        Map<?, ?> projectedHero = (Map<?, ?>) PlayerObservation.capture(hero, new FixtureLevel(), "game").get("hero");
        assertEquals("none", projectedHero.get("subclass"));
        assertNull(projectedHero.get("subclass_name"));
        assertFalse(JsonCodec.encode(projectedHero).contains("NO TEXT FOUND"));
    }

    @Test
    void unopenedChestAndCoveredHeapItemsStayPrivate() {
        Hero hero = hero();
        FixtureLevel level = new FixtureLevel();
        Dungeon.level = level;
        level.visited[6] = level.heroFOV[6] = true;
        Heap heap = new Heap();
        heap.pos = 6;
        heap.seen = true;
        heap.type = Heap.Type.CHEST;
        heap.items.add(itemFixture(PotionOfHealing.class));
        level.heaps.put(6, heap);
        Map<String, Object> first = PlayerObservation.capture(hero, level, "game");
        heap.items.clear();
        heap.items.add(new WandOfMagicMissile());
        assertEquals(first, PlayerObservation.capture(hero, level, "game"));
        heap.type = Heap.Type.HEAP;
        first = PlayerObservation.capture(hero, level, "game");
        heap.items.add(new ClothArmor());
        assertEquals(first, PlayerObservation.capture(hero, level, "game"));
    }

    @Test
    void repeatedCaptureDoesNotChangeModelOrRandom() throws Exception {
        Hero hero = hero();
        FixtureLevel level = new FixtureLevel();
        Dungeon.level = level;
        hero.belongings.backpack.items.add(itemFixture(PotionOfHealing.class));
        level.visited[hero.pos] = level.heroFOV[hero.pos] = true;
        byte[] random = Random.exportState();
        String before = JsonCodec.encode(new InternalGraphSnapshotter().capture(map("hero", hero, "level", level)));
        for (int i = 0; i < 100; i++) PlayerObservation.capture(hero, level, "game");
        String after = JsonCodec.encode(new InternalGraphSnapshotter().capture(map("hero", hero, "level", level)));
        assertEquals(before, after);
        assertArrayEquals(random, Random.exportState());
        assertTrue(hero.needsShieldUpdate);
        assertEquals(0, SnapshotFields.integer(hero, "id"));
    }

    @Test
    void inventoryLocatorsResolveOnlyCurrentVisibleSlots() {
        Hero hero = hero();
        Bag bag = new Bag();
        Item item = itemFixture(PotionOfHealing.class);
        hero.belongings.backpack.items.add(bag);
        bag.items.add(item);
        assertSame(item, GameSnapshotter.resolveItem(hero, "backpack.0.0"));
        assertNull(GameSnapshotter.resolveItem(hero, "backpack.-1"));
        assertNull(GameSnapshotter.resolveItem(hero, "backpack.999999999999999999999"));
        assertNull(GameSnapshotter.resolveItem(hero, "PotionOfHealing"));
        assertNull(GameSnapshotter.resolveItem(hero, "equipment.thrownWeapon"));
    }

    @Test
    void graphPreservesAliasingCyclesAndHiddenFieldsWithoutCallingGetters() {
        GraphFixture fixture = new GraphFixture();
        fixture.self = fixture;
        fixture.shared = new ArrayList<>(Arrays.asList("hidden"));
        fixture.same = fixture.shared;
        fixture.thread = Thread.currentThread();
        Map<String, Object> captured = new InternalGraphSnapshotter().capture(map("fixture", fixture));
        String json = JsonCodec.encode(captured);
        assertTrue(json.contains("hidden"));
        assertTrue(json.contains("$ref"));
        assertTrue(json.contains("render_native_or_external"));
        assertEquals(0, fixture.getterCalls);
        Map<?, ?> nodes = (Map<?, ?>) captured.get("nodes");
        assertEquals(2, nodes.size());
    }

    @Test
    void profileReadContainsOnlyGameFilesAndNeverAuditOrSymlink(@TempDir Path profile) throws Exception {
        java.nio.file.Files.writeString(profile.resolve("settings.xml"), "settings");
        java.nio.file.Files.createDirectories(profile.resolve("game1"));
        java.nio.file.Files.write(profile.resolve("game1/depth2.dat"), new byte[]{0, 1, 2, 3});
        java.nio.file.Files.createDirectories(profile.resolve("audit"));
        java.nio.file.Files.writeString(profile.resolve("audit/internal.sqlite"), "INTERNAL_AUDIT");
        java.nio.file.Files.writeString(profile.resolve("secret.txt"), "UNRELATED");
        java.nio.file.Files.createSymbolicLink(profile.resolve("game1/game.dat"), profile.resolve("secret.txt"));
        Map<String, Object> capture = new GameSnapshotter(profile).captureProfileFiles();
        String json = JsonCodec.encode(capture);
        assertTrue(json.contains("settings.xml"));
        assertTrue(json.contains("depth2.dat"));
        assertTrue(json.contains(Base64.getEncoder().encodeToString(new byte[]{0, 1, 2, 3})));
        assertFalse(json.contains("internal.sqlite"));
        assertFalse(json.contains("secret.txt"));
        assertFalse(json.contains("game1/game.dat"));
        assertEquals("captured_declared_scope", capture.get("status"));
    }

    @Test
    void initializationProbeNeverInitializesDormantClass() {
        ClassInitializationProbe probe = new ClassInitializationProbe();
        Boolean before = probe.initialized(DormantModel.class);
        assertEquals(Boolean.FALSE, before, "Configured CLI test runtime must provide the initialization probe");
        assertEquals(0, initializedModels);
        // Repeated metadata/probe reads must not execute the class initializer.
        for (int i = 0; i < 10; i++) probe.initialized(DormantModel.class);
        assertEquals(0, initializedModels);
        // On the packaged runtime with --add-opens, the gate is verifiably available.
        if (Boolean.TRUE.equals(probe.initialized(Hero.class))) assertEquals(Boolean.FALSE, before);
    }

    @Test
    void staticFieldInventoryMustBeUpdatedWhenEngineDeclarationsChange() throws Exception {
        Set<String> expected = new TreeSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                GameSnapshotter.class.getResourceAsStream("snapshot-static-fields.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.startsWith("#") && !line.isEmpty()) expected.add(line);
            }
        }
        Set<String> actual = new TreeSet<>();
        for (String root : InternalGraphSnapshotter.STATIC_ROOTS) {
            Class<?> type = Class.forName(root, false, getClass().getClassLoader());
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                if (Modifier.isFinal(field.getModifiers())
                        && (field.getType().isPrimitive() || field.getType() == String.class)) continue;
                actual.add(root + "#" + field.getName());
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void rootManifestCoversAllDeclaredStaticsInReviewedModelPackages() throws Exception {
        String prefix = "com.shatteredpixel.shatteredpixeldungeon.";
        Set<String> top = new HashSet<>(Arrays.asList("Dungeon", "Statistics", "GamesInProgress", "Badges",
                "Bones", "Challenges", "QuickSlot", "Rankings", "SPDSettings", "SPDAction", "DungeonSeed"));
        Path source = Path.of(Dungeon.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> classNames = new ArrayList<>();
        if (java.nio.file.Files.isDirectory(source)) {
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(source)) {
                files.filter(path -> path.toString().endsWith(".class")).forEach(path ->
                        classNames.add(source.relativize(path).toString().replace('/', '.').replaceAll("\\.class$", "")));
            }
        } else {
            try (JarFile jar = new JarFile(source.toFile())) {
                jar.stream().filter(entry -> entry.getName().endsWith(".class")).forEach(entry ->
                        classNames.add(entry.getName().replace('/', '.').replaceAll("\\.class$", "")));
            }
        }
        Set<String> actual = new TreeSet<>();
        for (String name : classNames) {
            if (!name.startsWith(prefix)) continue;
            String suffix = name.substring(prefix.length());
            if (!(suffix.startsWith("actors.") || suffix.startsWith("items.") || suffix.startsWith("levels.")
                    || suffix.startsWith("plants.") || suffix.startsWith("mechanics.") || suffix.startsWith("journal.")
                    || suffix.startsWith("scenes.") || suffix.startsWith("windows.") || suffix.startsWith("ui.")
                    || suffix.equals("utils.Holiday") || suffix.equals("utils.DungeonSeed")
                    || top.contains(suffix.split("\\$")[0]))) continue;
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                if (Modifier.isFinal(field.getModifiers())
                        && (field.getType().isPrimitive() || field.getType() == String.class)) continue;
                actual.add(name);
            }
        }
        assertEquals(actual, new TreeSet<>(InternalGraphSnapshotter.STATIC_ROOTS));
    }

    @Test
    void inactiveSceneDoesNotRevealResidualDungeonHero() {
        Hero hero = hero();
        Dungeon.level = new FixtureLevel();
        hero.belongings.backpack.items.add(itemFixture(PotionOfHealing.class));
        GameSnapshotter.Capture snapshot = new GameSnapshotter().capture();
        assertEquals("uninitialized", snapshot.publicState.get("scene"));
        assertNull(snapshot.publicState.get("hero"));
        assertNull(snapshot.publicState.get("map"));
        assertFalse(snapshot.publicState.containsKey("inventory"));
        Map<String, Object> direct = PlayerObservation.capture(hero, Dungeon.level, "title");
        assertNull(direct.get("hero"));
        assertNull(direct.get("map"));
        assertFalse(direct.containsKey("inventory"));
    }

    @Test
    void semanticWindowChildrenPrivateChoicesAndCallbackCapturesShareTheModelGraph() throws Exception {
        Hero hero = hero();
        Item selected = itemFixture(PotionOfHealing.class);
        SemanticWindowFixture window = allocateFixture(SemanticWindowFixture.class);
        SemanticChoiceFixture choice = new SemanticChoiceFixture();
        choice.selected = selected;
        choice.index = 3;
        int[] callbackCalls = {0};
        choice.callback = new Runnable() {
            @Override public void run() {
                callbackCalls[0]++;
                window.selected = selected;
            }
        };
        set(window, Group.class, "members", new ArrayList<Gizmo>(Arrays.asList(choice)));
        set(window, Group.class, "length", 1);
        choice.parent = window;
        window.selected = selected;
        String before = JsonCodec.encode(new InternalGraphSnapshotter().capture(map("window", window, "item", selected)));
        Map<String, Object> captured = new InternalGraphSnapshotter().capture(map("window", window, "item", selected));
        String after = JsonCodec.encode(captured);
        assertEquals(before, after);
        assertEquals(0, callbackCalls[0]);
        assertTrue(after.contains("SemanticChoiceFixture#index"));
        assertTrue(after.contains("val$selected"));
        assertTrue(after.contains("com.watabou.noosa.Group#members"));
        Map<?, ?> roots = (Map<?, ?>) captured.get("roots");
        Map<?, ?> itemRef = (Map<?, ?>) roots.get("item");
        String itemId = (String) itemRef.get("$ref");
        assertNotNull(itemId);
        Map<?, ?> nodes = (Map<?, ?>) captured.get("nodes");
        boolean sharedCapture = false;
        for (Object nodeValue : nodes.values()) {
            Map<?, ?> fields = (Map<?, ?>) ((Map<?, ?>) nodeValue).get("fields");
            if (fields == null) continue;
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                if (field.getKey().toString().endsWith("#val$selected")) {
                    assertEquals(itemRef, field.getValue());
                    sharedCapture = true;
                }
            }
        }
        assertTrue(sharedCapture);
    }

    private static int initializedModels;

    private static final class DormantModel {
        static Object state = initialize();
        static Object initialize() { initializedModels++; return new Object(); }
    }

    private static Hero hero() {
        Hero hero = new Hero();
        hero.pos = 5;
        Dungeon.hero = hero;
        return hero;
    }

    private static <T extends Item> T itemFixture(Class<T> type) {
        // Real item subclasses initialize render icon textures in their instance constructors.
        // A field-defined isolated model avoids native/UI initialization in these projection tests.
        try {
            T item = allocateFixture(type);
            set(item, Item.class, "quantity", 1);
            if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion)
                set(item, com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion.class, "color", "crimson");
            if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll)
                set(item, com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll.class, "rune", "KAUNAN");
            if (item instanceof Ring) set(item, Ring.class, "gem", "diamond");
            return item;
        } catch (Exception failure) {
            throw new AssertionError("Cannot construct isolated item fixture", failure);
        }
    }

    private static <T> T allocateFixture(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    private static void set(Object object, Class<?> owner, String field, Object value) throws Exception {
        Field declared = owner.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(object, value);
    }

    private static final class FixtureLevel extends Level {
        FixtureLevel() {
            width = height = 4;
            length = 16;
            map = new int[length];
            visited = new boolean[length];
            mapped = new boolean[length];
            heroFOV = new boolean[length];
            mobs = new HashSet<>();
            blobs = new HashMap<>();
            heaps = new SparseArray<>();
            traps = new SparseArray<>();
            plants = new SparseArray<>();
        }
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() {}
        @Override protected void createItems() {}
        @Override public String tilesTex() { return ""; }
        @Override public String waterTex() { return ""; }
    }

    private static final class GraphFixture {
        private GraphFixture self;
        private List<String> shared;
        private List<String> same;
        private Thread thread;
        private int getterCalls;
        public String dangerousGetter() { getterCalls++; return "secret"; }
    }

    private static final class SemanticWindowFixture extends Window {
        private Item selected;
    }

    private static final class SemanticChoiceFixture extends Component {
        private Item selected;
        private int index;
        private Runnable callback;
    }
}
