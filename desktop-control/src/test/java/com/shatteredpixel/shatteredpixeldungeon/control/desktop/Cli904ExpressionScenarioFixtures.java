package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Light;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MysteryMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.food.StewedMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfPurity;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.ExoticPotion;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Crystal;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.journal.Catalog;
import com.shatteredpixel.shatteredpixeldungeon.journal.Document;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.RitualSiteRoom;
import com.shatteredpixel.shatteredpixeldungeon.scenes.AlchemyScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.QuickRecipe;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndJournal;
import com.watabou.noosa.Game;
import com.watabou.noosa.audio.Sample;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** TEST SOURCE SET ONLY: never included in a production app or a formal run. */
public final class Cli904ExpressionScenarioFixtures {
    private static Path root;
    private static String consumed;
    private static String mode;
    private static Hero savedHero;
    private static int framesLeft;
    private static boolean preparing;
    private static Long audioId;

    public static void pulse() {
        try {
            if (root == null) {
                String directory = System.getenv("SPD_CLI904_FIXTURE_ROOT");
                if (directory == null) return;
                root = Path.of(directory).toRealPath();
                String nonce = System.getenv("SPD_CLI904_FIXTURE_NONCE");
                if (nonce == null || !Files.readString(root.resolve("fixture.nonce")).equals(nonce))
                    throw new IllegalStateException("Fixture nonce mismatch");
            }
            if (Game.instance == null || Game.scene() == null || Game.switchingScene()) return;
            if (preparing) {
                if (--framesLeft <= 0) {
                    preparing = false;
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("test_fixture", true);
                    result.put("counts_as_win", false);
                    result.put("command", consumed);
                    result.put("mode", mode);
                    result.put("pid", ProcessHandle.current().pid());
                    result.put("scene", Game.scene().getClass().getName());
                    result.put("hero_present", Dungeon.hero != null);
                    if (mode.startsWith("audio-")) result.put("native_sound_id", audioId);
                    result.put("java_home", System.getProperty("java.home"));
                    result.put("production_code", GameScene.class.getProtectionDomain().getCodeSource().getLocation().toString());
                    write("fixture.done.json", result);
                }
                return;
            }
            Path command = root.resolve("fixture.command");
            if (!Files.isRegularFile(command)) return;
            String value = Files.readString(command);
            if (value.equals(consumed)) return;
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2 || !parts[0].matches("[a-f0-9]{32}"))
                throw new IllegalArgumentException("Malformed one-shot fixture command");
            if (Game.scene() instanceof GameScene &&
                    (Dungeon.hero == null || !Dungeon.hero.ready || !Actor.isYielded())) return;
            if (Game.scene() instanceof GameScene) GameScene.atActorHandoff(() -> begin(value, parts[1]));
            else begin(value, parts[1]);
        } catch (Throwable failure) {
            preparing = false;
            try {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("command", consumed);
                error.put("error", failure.toString());
                error.put("pid", ProcessHandle.current().pid());
                write("fixture.failure.json", error);
            } catch (Exception ignored) { }
            throw new AssertionError("CLI904 isolated fixture failed", failure);
        }
    }

    private static void begin(String value, String requested) {
        consumed = value; // Consumed only inside the actual handoff; failed preparation is never replayed.
        mode = requested;
        preparing = true;
        framesLeft = 5; // Let real native layout and draw publish their evidence.
        prepare(requested);
    }

    private static void prepare(String requested) {
        if (requested.equals("ritual")) {
            Game.switchScene(GameScene.class, new Game.SceneChangeCallback() {
                @Override public void beforeCreate() { ritual(); }
                @Override public void afterCreate() { }
            });
            return;
        }
        if (requested.equals("restore-game")) {
            if (savedHero != null) Dungeon.hero = savedHero;
            Game.switchScene(GameScene.class);
            return;
        }
        if (requested.startsWith("crystal-")) {
            int durability = requested.endsWith("intact") ? 100 : requested.endsWith("flawed") ? 40 : 5;
            Sword sword = new Sword();
            Crystal crystal = new Crystal();
            crystal.setDurability(durability);
            sword.enchant(crystal);
            sword.identify(false);
            Dungeon.hero.belongings.clear();
            sword.collect();
            Item.updateQuickslot();
            return;
        }
        if (requested.startsWith("audio-")) {
            if (!alertLoaded())
                throw new IllegalStateException("Native ALERT sound is not loaded");
            boolean previous = Sample.INSTANCE.isEnabled();
            try {
                Sample.INSTANCE.enable(!requested.equals("audio-disabled"));
                audioId = Sample.INSTANCE.play(Assets.Sounds.ALERT, requested.equals("audio-zero") ? 0f : 1f);
            } finally { Sample.INSTANCE.enable(previous); }
            return;
        }
        if (requested.equals("journal")) {
            unlockPages();
            Notes.add(Notes.Landmark.WELL_OF_HEALTH);
            Notes.add(new IronKey(Dungeon.depth));
            Notes.add(new IronKey(Dungeon.depth));
            Catalog.setSeen(Sword.class);
            WndJournal.last_index = 2;
            WndJournal.AlchemyTab.currentPageIdx = 0;
            Game.scene().addToFront(new WndJournal());
            return;
        }
        if (requested.startsWith("recipes")) {
            if (requested.equals("recipes-nohero")) {
                savedHero = Dungeon.hero;
                Game.switchScene(TitleScene.class, new Game.SceneChangeCallback() {
                    @Override public void beforeCreate() { Dungeon.hero = null; }
                    @Override public void afterCreate() { Game.scene().addToFront(new RecipeWindow()); }
                });
            } else {
                Dungeon.hero.belongings.clear();
                new MysteryMeat().quantity(2).collect();
                PotionOfPurity ingredient = new PotionOfPurity();
                if (requested.equals("recipes-known")) ingredient.identify(false);
                ingredient.collect();
                Dungeon.energy = 100;
                if (requested.equals("recipes-alchemy")) {
                    AlchemyScene.clearToolkit();
                    Game.switchScene(AlchemyScene.class, new Game.SceneChangeCallback() {
                        @Override public void beforeCreate() { }
                        @Override public void afterCreate() { Game.scene().addToFront(new RecipeWindow()); }
                    });
                } else Game.scene().addToFront(new RecipeWindow());
            }
            return;
        }
        throw new IllegalArgumentException("Unknown CLI904 expression fixture: " + requested);
    }

    private static boolean alertLoaded() {
        try {
            java.lang.reflect.Field field = Sample.class.getDeclaredField("ids");
            field.setAccessible(true);
            synchronized (Sample.INSTANCE) {
                return ((Map<?, ?>)field.get(Sample.INSTANCE)).containsKey(Assets.Sounds.ALERT);
            }
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void unlockPages() {
        for (String page : Document.ALCHEMY_GUIDE.pageNames()) Document.ALCHEMY_GUIDE.findPage(page);
    }

    private static void ritual() {
        Hero hero = Dungeon.hero;
        Dungeon.depth = 7;
        Dungeon.branch = 0;
        Level level = Dungeon.newLevel();
        Dungeon.level = level;
        level.setSize(32, 32);
        level.mobs.clear(); level.heaps.clear(); level.traps.clear(); level.plants.clear();
        level.blobs.clear(); level.transitions.clear(); level.customTiles.clear();
        level.customWalls.clear(); level.customTerrain.clear();
        level.locked = false;
        Arrays.fill(level.map, Terrain.WALL);
        RitualSiteRoom room = new RitualSiteRoom();
        room.set(2, 2, 11, 11);
        room.paint(level); // The native room owns the marker and its semantic presentation.
        level.buildFlagMaps();
        Arrays.fill(level.visited, true);
        hero.live(); hero.updateHT(false); hero.HP = hero.HT;
        hero.ready = true; hero.curAction = hero.lastAction = null; hero.resting = false;
        Buff.affect(hero, Light.class, 1000f);
        Dungeon.switchLevel(level, 6 + 9 * level.width());
        for (Actor actor : new ArrayList<>(Actor.all()))
            if (actor instanceof MobSpawner) Actor.remove(actor);
    }

    /** Complete rendered native recipe rows, rather than manually dimmed ItemSlots. */
    private static final class RecipeWindow extends Window {
        RecipeWindow() {
            QuickRecipe[] recipes = {
                    new QuickRecipe(new StewedMeat.oneMeat()),
                    new QuickRecipe(new StewedMeat.twoMeat()),
                    new QuickRecipe(new StewedMeat.threeMeat()),
                    new QuickRecipe(new ExoticPotion.PotionToExotic(),
                            new ArrayList<>(Arrays.asList(new PotionOfPurity())),
                            new com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfCleansing())
            };
            int width = 0;
            for (int i = 0; i < recipes.length; i++) {
                add(recipes[i]); recipes[i].setPos(0, i * 24);
                width = Math.max(width, (int)Math.ceil(recipes[i].width()));
            }
            resize(width, recipes.length * 24);
        }
    }

    private static void write(String name, Object value) throws Exception {
        Path tmp = root.resolve(name + ".tmp");
        Files.writeString(tmp, JsonCodec.encode(value));
        Files.move(tmp, root.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
