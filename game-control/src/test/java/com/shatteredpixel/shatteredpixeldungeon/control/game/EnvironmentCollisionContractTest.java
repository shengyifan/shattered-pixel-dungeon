package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Web;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.WallOfLight.LightWall;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.SkeletonKey.KeyWall;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.MagicalFireRoom.EternalFire;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.watabou.utils.SparseArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;
import static org.junit.jupiter.api.Assertions.*;

/** Native in-memory flag hooks and Ballistica -> public observation -> Python. No game/profile I/O. */
class EnvironmentCollisionContractTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }
    @AfterEach void clear() { Actor.clear(); Dungeon.hero = null; Dungeon.level = null; }

    @Test void nativeDynamicBlockersAgreeWithPythonInEveryViewAndOccupancyCase() throws Exception {
        List<Object> cases = new ArrayList<>();
        int[] modes = {Ballistica.PROJECTILE, Ballistica.MAGIC_BOLT, Ballistica.STOP_SOLID};
        String[] names = {"projectile", "magicbolt", "solidbeam"};
        for (int base : new int[]{Terrain.EMPTY, Terrain.DOOR, Terrain.WALL}) {
            for (int mask = 0; mask < 16; mask++) for (boolean occupied : new boolean[]{false, true}) {
                Grid level = grid();
                level.map[44] = base;
                Blob[] effects = {new LightWall(), new KeyWall(), new EternalFire(), new Web()};
                for (int i = 0; i < effects.length; i++) if ((mask & (1 << i)) != 0) add(level, effects[i], 44);
                level.buildFlagMaps();
                if (occupied) {
                    Rat rat = new Rat(); rat.pos = 44; level.mobs.add(rat); Actor.add(rat);
                }
                Map<String,Object> capture = PlayerObservation.capture(Dungeon.hero, level, "game");
                for (int i = 0; i < modes.length; i++) for (int target : new int[]{44, 47}) {
                    int collision = new Ballistica(42, target, modes[i]).collisionPos;
                    // The originally reported three failures all stop before the hazard.
                    if (base == Terrain.EMPTY && !occupied && (mask == 1 || mask == 2 || mask == 4))
                        assertEquals(43, collision);
                    for (int view = 0; view < 3; view++) {
                        Map<String,Object> wire = CompactProtocol.success("hazard", "s1", "completed", capture,
                                true, view == 2, view != 0);
                        wire.put("rev", "r1");
                        cases.add(map("wire", wire, "mode", names[i], "target", target,
                                "native_collision", collision, "mask", mask, "base", base,
                                "native_flags", map("passable", level.passable[44], "avoid", level.avoid[44], "solid", level.solid[44])));
                    }
                }
            }
        }
        python(cases);
    }

    @Test void publicOverridesRemainPartialAndDoNotReadCombinedPrivateFlags() {
        Grid level = grid();
        Blob[] effects = {new LightWall(), new KeyWall(), new EternalFire(), new Web(), new Fire()};
        List<?> expected = List.of(map("passable", false, "avoid", false, "solid", true),
                map("passable", false, "avoid", false, "solid", true),
                map("passable", false, "avoid", false), map("solid", true));
        for (int i = 0; i < effects.length; i++) {
            level.blobs.clear(); add(level, effects[i], 44); level.buildFlagMaps();
            List<Map<String,Object>> visible = environment(level, 44);
            assertEquals(1, visible.size());
            Map<String,Object> effect = visible.get(0);
            if (i < expected.size()) assertEquals(expected.get(i), effect.get("collision"));
            else assertFalse(effect.containsKey("collision"), "Ordinary fire does not invent blocking flags");
            assertNotNull(effect.get("description"));
            Map<String,Object> before = PlayerObservation.capture(Dungeon.hero, level, "game");
            boolean[] passable = level.passable.clone(), avoid = level.avoid.clone(), solid = level.solid.clone();
            int[] amount = effects[i].cur.clone();
            assertEquals(before, PlayerObservation.capture(Dungeon.hero, level, "game"));
            assertArrayEquals(passable, level.passable); assertArrayEquals(avoid, level.avoid); assertArrayEquals(solid, level.solid);
            assertArrayEquals(amount, effects[i].cur);
            level.passable[44] = !level.passable[44]; level.avoid[44] = !level.avoid[44]; level.solid[44] = !level.solid[44];
            effects[i].cur[44] = effects[i].volume = 500;
            assertEquals(before, PlayerObservation.capture(Dungeon.hero, level, "game"),
                    "Private combined flags and amounts cannot alter source-bound public effect facts");
        }
    }

    @Test void collisionFactsUseTheSameNativeVisibilityGateAsTheirDescription() {
        for (Blob blob : new Blob[]{new LightWall(), new KeyWall(), new EternalFire(), new Web()}) {
            Grid level = grid(); add(level, blob, 44); level.buildFlagMaps();
            assertTrue(environment(level, 44).get(0).containsKey("collision"));
            level.heroFOV[44] = false;
            assertEquals(blob.alwaysVisible ? 1 : 0, environment(level, 44).size());
            level.visited[44] = false; level.mapped[44] = true;
            assertEquals(blob.alwaysVisible ? 1 : 0, environment(level, 44).size());
            level.mapped[44] = false;
            assertFalse(cells(level).stream().anyMatch(cell -> cell.get("cell").equals(44)),
                    "An alwaysVisible effect must not create an unknown cell");
        }
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> cells(Grid level) {
        return (List<Map<String,Object>>)((Map<?,?>)PlayerObservation.capture(Dungeon.hero, level, "game").get("map")).get("cells");
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> environment(Grid level, int cell) {
        return (List<Map<String,Object>>)cells(level).stream().filter(row -> row.get("cell").equals(cell)).findFirst().orElseThrow().get("environment");
    }
    private static Grid grid() {
        Actor.clear(); Grid level = new Grid(); Dungeon.level = level;
        Dungeon.hero = new Hero(); Dungeon.hero.pos = 42;
        return level;
    }
    private static void add(Grid level, Blob blob, int cell) {
        blob.cur = new int[level.length()]; blob.cur[cell] = blob.volume = 10;
        level.blobs.put(blob.getClass(), blob);
    }

    private static void python(List<Object> cases) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("desktop-control/client"))) root = root.getParent();
        assertNotNull(root);
        String script = String.join("\n", "import copy,json,sys", "sys.path.insert(0,sys.argv[1])",
                "sys.path.insert(0,sys.argv[2])", "from spdctl_client import decode_client_response", "import ray",
                "model=ray.source_model()", "cases=json.load(sys.stdin)", "for case in cases:",
                "    wire=case['wire']; before=copy.deepcopy(wire)",
                "    frame=decode_client_response(wire).require_current_frame()",
                "    label={k:v for k,v in case.items() if k!='wire'}",
                "    result=ray.predict(frame.frame,case['target'],case['mode'],model)",
                "    collision=result['conditional_collision_prediction']",
                "    assert collision is not None and collision['cell']==case['native_collision'], (label,collision,result['first_unknown_stops_prediction'])",
                "    assert result['first_unknown_stops_prediction'] is None, (label,result['first_unknown_stops_prediction'])",
                "    row=next((r for r in result['evaluated_path_including_blocking_probe'] if r['cell']==44),None)",
                "    if row is not None: assert row['flags']==case['native_flags'], (label,row['flags'])",
                "    assert wire==before", "print('PASS: %d native effect/terrain/occupant/target/view comparisons' % len(cases))");
        ProcessBuilder builder = new ProcessBuilder("python3", "-c", script,
                root.resolve("desktop-control/client").toString(),
                root.resolve("skills/pixel-dungeon-run-assistance/scripts").toString()).redirectErrorStream(true);
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process process = builder.start();
        try {
            process.getOutputStream().write(JsonCodec.encode(cases).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Offline helper did not terminate");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output); assertTrue(output.startsWith("PASS:"), output);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static final class Grid extends Level {
        Grid() {
            setSize(10, 10); Arrays.fill(map, Terrain.EMPTY); Arrays.fill(visited, true); Arrays.fill(heroFOV, true);
            mobs = new HashSet<>(); heaps = new SparseArray<>(); blobs = new HashMap<>();
            traps = new SparseArray<>(); plants = new SparseArray<>(); customTiles = new ArrayList<>(); customTerrain = new ArrayList<>();
            buildFlagMaps();
        }
        protected boolean build() { return false; }
        protected void createMobs() {}
        protected void createItems() {}
    }
}
