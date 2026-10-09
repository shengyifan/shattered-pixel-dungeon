package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.BlacksmithRoom.SmithyVisuals;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.CustomTilemap;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import com.watabou.utils.Random;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.FloatingAppearanceTest.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated native-selected smithy presentation; no renderer, actor step or profile files. */
class CustomTerrainObservationTest {
    private Game oldGame;
    private Hero oldHero;
    private Level oldLevel;
    private Object oldSceneClass;
    private Map<Object, SmartTexture> textures;
    private SmartTexture oldTexture;
    private FixtureLevel level;
    private Hero hero;
    private GameScene scene;
    private SmithyVisuals smithy;
    private Tilemap visual;
    private TextObservationFixture textFixture;

    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @BeforeEach @SuppressWarnings("unchecked") void fixture() throws Exception {
        oldGame = Game.instance; oldHero = Dungeon.hero; oldLevel = Dungeon.level;
        oldSceneClass = SnapshotFields.read(Game.class, "sceneClass");
        textures = (Map<Object, SmartTexture>) SnapshotFields.read(TextureCache.class, "all");
        oldTexture = textures.get(Assets.Environment.CAVES_QUEST);
        SmartTexture texture = allocate(SmartTexture.class); texture.width = 64; texture.height = 128;
        textures.put(Assets.Environment.CAVES_QUEST, texture);
        level = new FixtureLevel(); Dungeon.level = level;
        hero = new Hero(); hero.pos = 17; Dungeon.hero = hero;
        scene = new GameScene(); new TestGame(scene);
        textFixture = new TextObservationFixture();
        smithy = new SmithyVisuals(); smithy.setRect(1, 1, 4, 3);
        for (int cell : new int[]{9, 10, 11}) level.setTerrain(cell, Terrain.CUSTOM_DECO);
        level.setTerrain(19, Terrain.CUSTOM_DECO_WTR);
        visual = smithy.create(); scene.add(visual); level.customTiles.add(smithy);
    }

    @AfterEach void restore() throws Exception {
        if (textFixture != null) textFixture.close();
        Game.instance = oldGame; Dungeon.hero = oldHero; Dungeon.level = oldLevel;
        field(null, Game.class, "sceneClass", oldSceneClass);
        if (oldTexture == null) textures.remove(Assets.Environment.CAVES_QUEST);
        else textures.put(Assets.Environment.CAVES_QUEST, oldTexture);
        Messages.setup(Languages.ENGLISH);
    }

    @Test void selectedSmithyFramesSeparateSolidDecorationFromOrdinaryWaterAndNativeCollision() {
        level.setTerrain(20, Terrain.WATER);
        assertEquals(18, visual.presentationFrameAt(2, 1));
        assertEquals(Terrain.CUSTOM_DECO_WTR, cell(19).get("terrain"));
        assertEquals("Smithy", cell(19).get("name"));
        assertEquals(Messages.get(SmithyVisuals.class, "desc"), cell(19).get("description"));
        for (int cell : new int[]{9, 10, 11}) assertEquals(Terrain.CUSTOM_DECO, cell(cell).get("terrain"));
        assertEquals(Terrain.WATER, cell(20).get("terrain"));
        assertFalse(cell(20).containsKey("description"));
        int collisionMode = Ballistica.STOP_TARGET | Ballistica.STOP_SOLID;
        assertEquals(18, new Ballistica(17, 21, collisionMode).collisionPos);
        scene.erase(visual); level.setTerrain(19, Terrain.WATER);
        assertEquals(Terrain.WATER, cell(19).get("terrain"));
        assertEquals(21, new Ballistica(17, 21, collisionMode).collisionPos);
    }

    @Test void nativeKnownScopeIncludesVisitedMappedAndOffscreenCellsButNeverUnknownTerrain() {
        level.heroFOV[9] = false; level.visited[9] = true;
        level.heroFOV[10] = false; level.mapped[10] = true;
        level.heroFOV[11] = false;
        visual.camera = new Camera(0, 0, 1, 1, 1);
        visual.camera.scroll.set(10000, 10000);
        Group modal = new Group(); scene.add(modal);
        assertEquals("visited", cell(9).get("visibility"));
        assertEquals("mapped", cell(10).get("visibility"));
        assertEquals(Terrain.CUSTOM_DECO, cell(9).get("terrain"));
        assertEquals(Terrain.CUSTOM_DECO, cell(10).get("terrain"));
        assertNull(cell(11));
        assertEquals(Terrain.CUSTOM_DECO_WTR, cell(19).get("terrain"));
    }

    @Test void relocatedScaledRotatedOrMalformedPresentationDoesNotDescribeTheOldCell() throws Exception {
        List<Runnable> invalidate = Arrays.asList(
                () -> visual.x += 16, () -> visual.y += 16,
                () -> visual.scale.x = 2, () -> visual.scale.y = 0,
                () -> visual.angle = 15, () -> visual.origin.x = 1,
                () -> visual.x = Float.NaN, () -> visual.width = Float.POSITIVE_INFINITY);
        for (Runnable invalid : invalidate) {
            visual.x = smithy.tileX * 16; visual.y = smithy.tileY * 16;
            visual.scale.set(1, 1); visual.angle = 0; visual.origin.set(0, 0); visual.width = smithy.tileW * 16;
            invalid.run();
            assertEquals(Terrain.WATER, cell(19).get("terrain"));
            assertFalse(cell(19).containsKey("description"));
        }
        visual.x = smithy.tileX * 16; visual.width = smithy.tileW * 16;
        field(visual, Tilemap.class, "cellW", Float.NaN);
        assertEquals(Terrain.WATER, cell(19).get("terrain"));
    }

    @Test void absentDetachedHiddenTransparentKilledAndUnselectedSourcesCannotRevealPrivateTerrain() throws Exception {
        List<Runnable> invalidate = Arrays.asList(
                () -> level.customTiles.clear(), () -> scene.erase(visual),
                () -> visual.visible = false, () -> visual.am = 0,
                () -> visual.alive = false, () -> visual.exists = false,
                () -> scene.visible = false, () -> new TestGame(new GameScene()),
                () -> ((int[]) SnapshotFields.read(visual, "data"))[6] = -1,
                () -> ((int[]) SnapshotFields.read(visual, "data"))[6] = 1000);
        for (Runnable invalid : invalidate) {
            new TestGame(scene); scene.visible = true;
            visual.visible = visual.alive = visual.exists = true; visual.am = 1;
            scene.add(visual); level.customTiles.clear(); level.customTiles.add(smithy);
            ((int[]) SnapshotFields.read(visual, "data"))[6] = 18;
            invalid.run();
            level.setTerrain(19, Terrain.WATER); Map<String, Object> water = cell(19);
            level.setTerrain(19, Terrain.CUSTOM_DECO_WTR);
            assertEquals(water, cell(19), "Unavailable source must not expose a private custom-terrain discriminator");
            assertEquals(Terrain.WATER, water.get("terrain"));
            assertFalse(water.containsKey("description"));
        }
        new TestGame(scene); scene.visible = true;
        visual.visible = visual.alive = visual.exists = true; visual.am = 1;
        scene.add(visual); level.customTiles.clear(); level.customTiles.add(smithy);
        ((int[]) SnapshotFields.read(visual, "data"))[6] = 18;
        Group hiddenParent = new Group(); scene.add(hiddenParent); hiddenParent.add(visual); hiddenParent.visible = false;
        assertEquals(Terrain.WATER, cell(19).get("terrain"));
    }

    @Test void semanticDescriptorDependsOnlyOnSelectedFrameRatherThanPrivateTerrainOrNativeGetter() throws Exception {
        level.setTerrain(19, Terrain.WATER);
        assertEquals(Terrain.CUSTOM_DECO_WTR, cell(19).get("terrain"));
        // Native SmithyVisuals.name consults map; the new reviewed hook must not.
        assertNull(smithy.name(2, 1));
        ((int[]) SnapshotFields.read(visual, "data"))[6] = 16;
        assertEquals(Terrain.CUSTOM_DECO, cell(19).get("terrain"));
        ((int[]) SnapshotFields.read(visual, "data"))[6] = 8;
        assertEquals(Terrain.WATER, cell(19).get("terrain"));
        assertFalse(cell(19).containsKey("description"));
        assertEquals(-1, visual.presentationFrameAt(-1, 1));
        assertEquals(-1, visual.presentationFrameAt(4, 1));
        assertEquals(-1, visual.presentationFrameAt(1, 3));
    }

    @Test void nativeCustomTilePrecedenceUsesLastValidSourceBeforeCustomTerrainFallback() throws Exception {
        UnreviewedTiles overlay = new UnreviewedTiles(); overlay.setRect(1, 1, 4, 3);
        Tilemap overlayVisual = overlay.create(); scene.add(overlayVisual); level.customTiles.add(overlay);
        assertEquals(Terrain.WATER, cell(19).get("terrain"), "Unreviewed top layer masks the underlying reviewed source");
        overlayVisual.visible = false;
        assertEquals(Terrain.CUSTOM_DECO_WTR, cell(19).get("terrain"));
        level.customTiles.clear(); level.customTerrain.add(smithy);
        assertEquals(Terrain.CUSTOM_DECO_WTR, cell(19).get("terrain"));
        overlayVisual.visible = true; level.customTiles.add(overlay);
        assertEquals(Terrain.WATER, cell(19).get("terrain"), "Any selected customTiles source precedes customTerrain");
        level.customTiles.clear(); level.customTerrain.add(overlay);
        assertEquals(Terrain.WATER, cell(19).get("terrain"), "The last selected customTerrain layer wins");
        assertEquals(0, overlay.nativeGetterCalls);
    }

    @Test void repeatedQueriesPreserveNativeStateRngAndSelectedRenderBuffers() {
        byte[] random = Random.exportState();
        int[] mapBefore = level.map.clone();
        int[] framesBefore = ((int[]) SnapshotFields.read(visual, "data")).clone();
        Map<String, Object> internal = new InternalGraphSnapshotter().capture(map("hero", hero, "level", level));
        Object updated = SnapshotFields.read(visual, "updated");
        Object buffer = SnapshotFields.read(visual, "buffer"), quads = SnapshotFields.read(visual, "quads");
        Object fullUpdate = SnapshotFields.read(visual, "fullUpdate");
        for (int i = 0; i < 5; i++) assertEquals(Terrain.CUSTOM_DECO_WTR, cell(19).get("terrain"));
        assertArrayEquals(random, Random.exportState());
        assertArrayEquals(mapBefore, level.map);
        assertArrayEquals(framesBefore, (int[]) SnapshotFields.read(visual, "data"));
        assertEquals(internal, new InternalGraphSnapshotter().capture(map("hero", hero, "level", level)));
        assertSame(updated, SnapshotFields.read(visual, "updated"));
        assertSame(buffer, SnapshotFields.read(visual, "buffer"));
        assertSame(quads, SnapshotFields.read(visual, "quads"));
        assertEquals(fullUpdate, SnapshotFields.read(visual, "fullUpdate"));
    }

    @Test void englishSourceBoundDescriptionsSurvivePlayFullSrcAndFrozenHistory() {
        Messages.setup(Languages.CHI_SMPL);
        Map<String, Object> frozen = PublicEnglishProjection.freeze(PlayerObservation.capture(hero, level, "game"));
        TextProvenance.INSTANCE.clear();
        Map<String, Object> english = PublicEnglishProjection.copy(frozen);
        for (boolean src : new boolean[]{false, true}) for (boolean full : new boolean[]{false, true}) {
            Map<String, Object> projected = object(CompactProtocol.project(english, src, full));
            assertWireDescriptor(object(projected.get("map")), src);
            Map<String, Object> history = object(CompactProtocol.project(map("before", english, "after", english), src, full));
            assertWireDescriptor(object(object(history.get("before")).get("map")), src);
            assertWireDescriptor(object(object(history.get("after")).get("map")), src);
        }
    }

    private static void assertWireDescriptor(Map<String, Object> map, boolean sources) {
        Map<?, ?> smithy = ((List<?>) map.get("types")).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> Objects.equals(Terrain.CUSTOM_DECO_WTR, value.get("terrain"))).findFirst().orElseThrow();
        assertEquals("Smithy", smithy.get("name"));
        assertTrue(((String) smithy.get("desc")).contains("blacksmith"));
        if (sources) {
            assertTrue(smithy.get("text_sources").toString().contains("blacksmithroom$smithyvisuals.name"));
            assertTrue(smithy.get("text_sources").toString().contains("blacksmithroom$smithyvisuals.desc"));
        }
        assertFalse(smithy.containsKey("frame")); assertFalse(smithy.containsKey("solid"));
    }

    private Map<String, Object> cell(int position) {
        Map<String, Object> map = object(PlayerObservation.capture(hero, level, "game").get("map"));
        for (Object value : (List<?>) map.get("cells")) {
            Map<String, Object> cell = object(value);
            if (Objects.equals(position, cell.get("cell"))) return cell;
        }
        return null;
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }

    private static final class TestGame extends Game {
        TestGame(Scene value) { super(Scene.class, Game.platform); scene = value; requestedReset = false; }
    }

    private static final class UnreviewedTiles extends CustomTilemap {
        int nativeGetterCalls;
        UnreviewedTiles() { texture = Assets.Environment.CAVES_QUEST; }
        @Override public Tilemap create() {
            Tilemap result = super.create(); int[] data = new int[tileW * tileH];
            Arrays.fill(data, 18); result.map(data, tileW); return result;
        }
        @Override public Image image(int x, int y) { nativeGetterCalls++; throw new AssertionError("Observation must not create an image"); }
        @Override public String name(int x, int y) { nativeGetterCalls++; throw new AssertionError("Unreviewed name getter"); }
        @Override public String desc(int x, int y) { nativeGetterCalls++; throw new AssertionError("Unreviewed description getter"); }
    }

    private static final class FixtureLevel extends Level {
        FixtureLevel() {
            width = 8; height = 5; length = width * height;
            map = new int[length]; Arrays.fill(map, Terrain.EMPTY);
            heroFOV = new boolean[length]; Arrays.fill(heroFOV, true);
            visited = new boolean[length]; mapped = new boolean[length];
            passable = new boolean[length]; Arrays.fill(passable, true);
            solid = new boolean[length]; avoid = new boolean[length];
            customTiles = new ArrayList<>(); customTerrain = new ArrayList<>();
        }
        void setTerrain(int cell, int terrain) {
            map[cell] = terrain;
            passable[cell] = (Terrain.flags[terrain] & Terrain.PASSABLE) != 0;
            solid[cell] = (Terrain.flags[terrain] & Terrain.SOLID) != 0;
            avoid[cell] = (Terrain.flags[terrain] & Terrain.AVOID) != 0;
        }
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() { }
        @Override protected void createItems() { }
    }
}
