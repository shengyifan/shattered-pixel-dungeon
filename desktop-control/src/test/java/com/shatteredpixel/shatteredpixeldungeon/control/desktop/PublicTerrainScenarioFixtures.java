package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.BlacksmithRoom;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;

import java.util.Arrays;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** TEST SOURCE SET ONLY. Native room painting/presentation with isolated initial conditions. */
final class PublicTerrainScenarioFixtures {
    private static Map<String, Object> evidence = map("ready", false);
    private static int waterCell, decorationCell;

    static boolean supports(String name) { return name.equals("smithy"); }

    static void prepare(String name, Hero hero) {
        if (!supports(name)) throw new IllegalArgumentException("Unknown public terrain fixture");
        evidence = map("ready", false);
        Game.switchScene(GameScene.class, new Game.SceneChangeCallback() {
            @Override public void beforeCreate() { source(hero); }
            @Override public void afterCreate() {
                // Assertion-only native oracle, read by the driver after its public choices.
                evidence = map("ready", true, "native_room_paint", true,
                        "source", hero.pos, "water", nativeRay(hero.pos, waterCell),
                        "decoration", nativeRay(hero.pos, decorationCell));
            }
        });
    }

    private static void source(Hero hero) {
        Dungeon.depth = 12;
        Dungeon.branch = 0;
        Level level = Dungeon.newLevel();
        Dungeon.level = level;
        level.setSize(32, 32);
        level.mobs.clear();
        level.heaps.clear();
        level.traps.clear();
        level.plants.clear();
        level.blobs.clear();
        level.transitions.clear();
        level.customTiles.clear();
        level.customWalls.clear();
        level.customTerrain.clear();
        level.locked = false;
        Arrays.fill(level.map, Terrain.WALL);

        BlacksmithRoom room = new BlacksmithRoom();
        room.set(2, 2, 12, 12);
        room.paint(level);
        // The real native room creates both the CUSTOM_DECO_WTR model cell and
        // SmithyVisuals. GameScene.create invokes its unchanged create/map path.
        decorationCell = 6 + 5 * level.width();
        waterCell = 7 + 7 * level.width();
        if (level.map[decorationCell] != Terrain.CUSTOM_DECO_WTR)
            throw new IllegalStateException("Native smithy painting changed its fixture layout");
        level.map[waterCell] = Terrain.WATER;
        level.buildFlagMaps();
        Arrays.fill(level.visited, true);
        hero.lvl = 20;
        hero.STR = 18;
        hero.live();
        hero.updateHT(false);
        hero.HP = hero.HT;
        hero.ready = true;
        hero.curAction = hero.lastAction = null;
        hero.resting = false;
        Dungeon.switchLevel(level, 6 + 7 * level.width());
        Item.updateQuickslot();
    }

    private static Map<String, Object> nativeRay(int source, int target) {
        Ballistica ray = new Ballistica(source, target, Ballistica.PROJECTILE);
        return map("target", target, "collision", ray.collisionPos, "distance", ray.dist);
    }

    static Map<String, Object> assertions() { return evidence; }
}
