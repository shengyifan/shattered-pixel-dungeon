package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.CeremonialCandle;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.RitualSiteRoom.RitualMarker;
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

/** Native attached ritual artwork with synthetic texture metadata; no actor step or profile files. */
class RitualMarkerObservationTest {
    private Game oldGame;
    private Hero oldHero;
    private Level oldLevel;
    private int oldRitualPos;
    private Object oldSceneClass;
    private Map<Object, SmartTexture> textures;
    private SmartTexture oldTexture;
    private FixtureLevel level;
    private Hero hero;
    private GameScene scene;
    private GuardedRitualMarker marker;
    private Tilemap visual;
    private TextObservationFixture textFixture;

    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @BeforeEach @SuppressWarnings("unchecked") void fixture() throws Exception {
        oldGame = Game.instance; oldHero = Dungeon.hero; oldLevel = Dungeon.level;
        oldRitualPos = CeremonialCandle.ritualPos;
        oldSceneClass = SnapshotFields.read(Game.class, "sceneClass");
        textures = (Map<Object, SmartTexture>) SnapshotFields.read(TextureCache.class, "all");
        oldTexture = textures.get(Assets.Environment.PRISON_QUEST);
        SmartTexture texture = allocate(SmartTexture.class); texture.width = texture.height = 256;
        textures.put(Assets.Environment.PRISON_QUEST, texture);
        level = new FixtureLevel(); Dungeon.level = level;
        hero = new Hero(); hero.pos = 9; Dungeon.hero = hero;
        scene = new GameScene(); new TestGame(scene);
        textFixture = new TextObservationFixture();
        marker = new GuardedRitualMarker(); marker.pos(1, 1);
        for (int y = 2; y <= 4; y++) for (int x = 2; x <= 4; x++)
            level.setTerrain(x + y * level.width(), Terrain.CUSTOM_DECO_EMPTY);
        visual = marker.create(); scene.add(visual); level.customTiles.add(marker);
    }

    @AfterEach void restore() throws Exception {
        if (textFixture != null) textFixture.close();
        Game.instance = oldGame; Dungeon.hero = oldHero; Dungeon.level = oldLevel;
        CeremonialCandle.ritualPos = oldRitualPos;
        field(null, Game.class, "sceneClass", oldSceneClass);
        if (oldTexture == null) textures.remove(Assets.Environment.PRISON_QUEST);
        else textures.put(Assets.Environment.PRISON_QUEST, oldTexture);
        Messages.setup(Languages.ENGLISH);
    }

    @Test void exactlyTheNineSelectedInnerFramesDescribeTheMarkerWithoutChangingFloor() {
        int described = 0;
        for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) {
            int position = (x + 1) + (y + 1) * level.width();
            Map<String, Object> cell = cell(position);
            boolean inner = x >= 1 && x <= 3 && y >= 1 && y <= 3;
            assertEquals(32 + x + 16 * y, marker.presentationFrameAt(x, y));
            assertEquals(Terrain.EMPTY, cell.get("terrain"));
            if (inner) {
                described++;
                assertEquals("Ritual marker", cell.get("name"));
                assertEquals(Messages.get(RitualMarker.class, "desc"), cell.get("description"));
            } else {
                assertEquals(level.tileName(Terrain.EMPTY), cell.get("name"));
                assertFalse(cell.containsKey("description"), "Border artwork has no native inspectable marker");
            }
        }
        assertEquals(9, described);
        assertEquals(0, marker.nativeGetterCalls);
    }

    @Test void descriptorPreservesPublicBaseTerrainAndDoesNotRecoverPrivateTerrainFlags() {
        for (int terrain : new int[]{Terrain.EMPTY, Terrain.WATER, Terrain.TRAP,
                Terrain.CUSTOM_DECO_EMPTY, Terrain.SECRET_DOOR, Terrain.SECRET_TRAP}) {
            level.setTerrain(27, terrain);
            Map<String, Object> cell = cell(27);
            assertEquals(PlayerObservation.terrainAppearance(terrain), cell.get("terrain"));
            assertEquals("Ritual marker", cell.get("name"));
            assertFalse(cell.containsKey("solid"));
            assertFalse(cell.containsKey("passable"));
            assertFalse(cell.containsKey("ritualPos"));
        }
        CustomTilemap.PublicTerrain legacy = new CustomTilemap.PublicTerrain(Terrain.CUSTOM_DECO, "Name", "Description");
        assertEquals(Integer.valueOf(Terrain.CUSTOM_DECO), legacy.terrain);
        assertNull(marker.publicTerrain(66).terrain);
    }

    @Test void knownScopeIncludesVisitedMappedAndOffscreenMarkerButNeverUnknownCells() {
        level.heroFOV[18] = false; level.visited[18] = true;
        level.heroFOV[19] = false; level.mapped[19] = true;
        level.heroFOV[20] = false;
        visual.camera = new Camera(0, 0, 1, 1, 1);
        visual.camera.scroll.set(10000, 10000);
        scene.add(new Group());
        assertEquals("visited", cell(18).get("visibility"));
        assertEquals("mapped", cell(19).get("visibility"));
        assertEquals("Ritual marker", cell(18).get("name"));
        assertEquals("Ritual marker", cell(19).get("name"));
        assertNull(cell(20));
        assertEquals("Ritual marker", cell(27).get("name"));
    }

    @Test void unavailableTransformedAndUnselectedPresentationsDoNotInventAMarker() {
        List<Runnable> invalidate = Arrays.asList(
                () -> level.customTiles.clear(), () -> scene.erase(visual),
                () -> visual.visible = false, () -> visual.am = 0,
                () -> visual.alive = false, () -> visual.exists = false,
                () -> visual.x += 16, () -> visual.scale.x = 2,
                () -> scene.visible = false, () -> new TestGame(new GameScene()),
                () -> frames()[12] = 32, () -> frames()[12] = -1, () -> frames()[12] = 1000);
        for (Runnable invalid : invalidate) {
            new TestGame(scene); scene.visible = true;
            visual.visible = visual.alive = visual.exists = true; visual.am = 1;
            visual.x = marker.tileX * 16; visual.scale.set(1, 1);
            scene.add(visual); level.customTiles.clear(); level.customTiles.add(marker); frames()[12] = 66;
            invalid.run();
            assertEquals(Terrain.EMPTY, cell(27).get("terrain"));
            assertEquals(level.tileName(Terrain.EMPTY), cell(27).get("name"));
            assertFalse(cell(27).containsKey("description"));
        }
        assertEquals(0, marker.nativeGetterCalls);
    }

    @Test void selectedUnreviewedOverlaysStillMaskTheMarkerAndCustomTilesHavePriority() {
        UnreviewedTiles overlay = new UnreviewedTiles(); overlay.setRect(1, 1, 5, 5);
        Tilemap overlayVisual = overlay.create(); scene.add(overlayVisual); level.customTiles.add(overlay);
        assertFalse(cell(27).containsKey("description"));
        overlayVisual.visible = false;
        assertEquals("Ritual marker", cell(27).get("name"));
        level.customTiles.clear(); level.customTerrain.add(marker);
        assertEquals("Ritual marker", cell(27).get("name"));
        overlayVisual.visible = true; level.customTiles.add(overlay);
        assertFalse(cell(27).containsKey("description"));
        level.customTiles.clear(); level.customTerrain.add(overlay);
        assertFalse(cell(27).containsKey("description"));
        assertEquals(0, overlay.nativeGetterCalls);
        assertEquals(0, marker.nativeGetterCalls);
    }

    @Test void captureDoesNotReadRitualPositionOrMutateStateRngAndSelectedBuffers() {
        byte[] random = Random.exportState();
        int[] mapBefore = level.map.clone(), framesBefore = frames().clone();
        Map<String, Object> internal = new InternalGraphSnapshotter().capture(map("hero", hero, "level", level));
        Object updated = SnapshotFields.read(visual, "updated");
        Object buffer = SnapshotFields.read(visual, "buffer"), quads = SnapshotFields.read(visual, "quads");
        Object fullUpdate = SnapshotFields.read(visual, "fullUpdate");
        CeremonialCandle.ritualPos = 27;
        Map<String, Object> expected = PlayerObservation.capture(hero, level, "game");
        for (int ritualPos : new int[]{0, 63, -1, Integer.MAX_VALUE}) {
            CeremonialCandle.ritualPos = ritualPos;
            assertEquals(expected, PlayerObservation.capture(hero, level, "game"));
            assertEquals(ritualPos, CeremonialCandle.ritualPos);
        }
        assertArrayEquals(random, Random.exportState());
        assertArrayEquals(mapBefore, level.map); assertArrayEquals(framesBefore, frames());
        assertEquals(internal, new InternalGraphSnapshotter().capture(map("hero", hero, "level", level)));
        assertSame(updated, SnapshotFields.read(visual, "updated"));
        assertSame(buffer, SnapshotFields.read(visual, "buffer"));
        assertSame(quads, SnapshotFields.read(visual, "quads"));
        assertEquals(fullUpdate, SnapshotFields.read(visual, "fullUpdate"));
    }

    @Test void englishSourceBoundMarkerTextSurvivesPlayFullSrcAndFrozenHistory() {
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
        Map<?, ?> marker = ((List<?>) map.get("types")).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> Objects.equals("Ritual marker", value.get("name"))).findFirst().orElseThrow();
        assertEquals(Terrain.EMPTY, marker.get("terrain"));
        assertTrue(((String) marker.get("desc")).contains("four corners"));
        if (sources) {
            assertTrue(marker.get("text_sources").toString().contains("ritualsiteroom$ritualmarker.name"));
            assertTrue(marker.get("text_sources").toString().contains("ritualsiteroom$ritualmarker.desc"));
        }
        assertFalse(marker.containsKey("frame")); assertFalse(marker.containsKey("solid"));
        assertFalse(marker.containsKey("ritualPos"));
    }

    private int[] frames() { return (int[]) SnapshotFields.read(visual, "data"); }

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

    private static final class GuardedRitualMarker extends RitualMarker {
        int nativeGetterCalls;
        @Override public Image image(int x, int y) { nativeGetterCalls++; throw new AssertionError("Observation must not create an image"); }
        @Override public String name(int x, int y) { nativeGetterCalls++; throw new AssertionError("Observation must not invoke native name"); }
        @Override public String desc(int x, int y) { nativeGetterCalls++; throw new AssertionError("Observation must not invoke native description"); }
    }

    private static final class UnreviewedTiles extends CustomTilemap {
        int nativeGetterCalls;
        UnreviewedTiles() { texture = Assets.Environment.PRISON_QUEST; }
        @Override public Tilemap create() {
            Tilemap result = super.create(); int[] data = new int[tileW * tileH];
            Arrays.fill(data, 66); result.map(data, tileW); return result;
        }
        @Override public Image image(int x, int y) { nativeGetterCalls++; throw new AssertionError("Observation must not create an image"); }
        @Override public String name(int x, int y) { nativeGetterCalls++; throw new AssertionError("Unreviewed name getter"); }
        @Override public String desc(int x, int y) { nativeGetterCalls++; throw new AssertionError("Unreviewed description getter"); }
    }

    private static final class FixtureLevel extends Level {
        FixtureLevel() {
            width = height = 8; length = width * height;
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
