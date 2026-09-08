package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.noosa.Game;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;

/** Capture is called only while the coordinator holds the stable game/render boundary. */
public final class GameSnapshotter {
    private static final List<String> PROFILE_FILES = Collections.unmodifiableList(Arrays.asList(
            "settings.xml", "settings.json", "settings.dat", "badges.dat", "journal.dat", "rankings.dat",
            "bones.dat", "keybinds.dat", "keybindings.dat", "bindings.dat"));
    private final Path profile;

    public GameSnapshotter() { this(null); }

    public GameSnapshotter(Path profile) {
        this.profile = profile == null ? null : profile.toAbsolutePath().normalize();
    }

    public Capture capture() {
        Object currentScene = Game.instance == null ? null : SnapshotFields.read(Game.instance, "scene");
        String scene = "uninitialized";
        if (currentScene != null) {
            scene = sceneName(currentScene.getClass().getSimpleName());
        }
        // Dungeon retains the previous run while non-game scenes are open.
        boolean playing = "game".equals(scene);
        Map<String, Object> publicState = PlayerObservation.capture(
                playing ? Dungeon.hero : null, playing ? Dungeon.level : null, scene);
        Map<String, Object> roots = playing ? map("active_hero", Dungeon.hero, "active_level", Dungeon.level) : map();
        if (Game.instance != null) {
            roots.put("current_scene", currentScene);
            roots.put("requested_scene", SnapshotFields.read(Game.instance, "requestedScene"));
            roots.put("requested_scene_class", SnapshotFields.read(Game.class, "sceneClass"));
            roots.put("scene_change_callback", SnapshotFields.read(Game.instance, "onChange"));
            roots.put("scene_change_requested", SnapshotFields.read(Game.instance, "requestedReset"));
        }
        Map<String, Object> internalState = new InternalGraphSnapshotter().captureGameRoots(roots);
        internalState.put("scene", currentScene == null ? null : currentScene.getClass().getName());
        internalState.put("profile_files", captureProfileFiles());
        internalState.put("random", randomState());
        internalState.put("build", BuildCatalog.current());
        return new Capture(publicState, internalState);
    }

    /** Pure observation over isolated fixture objects; never scans the user's profile. */
    public Capture capture(Hero hero, Level level, String scene) {
        return new Capture(PlayerObservation.capture(hero, level, scene),
                new InternalGraphSnapshotter().capture(map("hero", hero, "level", level)));
    }

    public Item resolveItem(String locator) {
        if (Game.instance == null || Game.scene() == null
                || !"game".equals(sceneName(Game.scene().getClass().getSimpleName()))) return null;
        return PlayerObservation.resolveItem(Dungeon.hero, locator);
    }

    public static Item resolveItem(Hero hero, String locator) {
        return PlayerObservation.resolveItem(hero, locator);
    }

    public Map<String, Object> captureProfileFiles() {
        List<Object> files = new ArrayList<>();
        List<Object> errors = new ArrayList<>();
        if (profile == null) return map("status", "not_configured", "files", files, "errors", errors);
        if (Files.isSymbolicLink(profile) || !Files.isDirectory(profile, LinkOption.NOFOLLOW_LINKS)) {
            return map("status", "unavailable", "files", files, "errors", errors);
        }
        try (DirectoryStream<Path> root = Files.newDirectoryStream(profile)) {
            List<Path> entries = new ArrayList<>();
            for (Path entry : root) entries.add(entry);
            entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isSymbolicLink(entry)) continue;
                if (PROFILE_FILES.contains(name) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    readFile(entry, files, errors);
                } else if (name.matches("game[0-9]+") && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    try (DirectoryStream<Path> game = Files.newDirectoryStream(entry)) {
                        List<Path> saves = new ArrayList<>();
                        for (Path save : game) {
                            if (save.getFileName().toString().matches("(?:game|depth[0-9]+(?:-branch[0-9]+)?)\\.dat")
                                    && Files.isRegularFile(save, LinkOption.NOFOLLOW_LINKS)) saves.add(save);
                        }
                        saves.sort(Comparator.comparing(path -> path.getFileName().toString()));
                        for (Path save : saves) readFile(save, files, errors);
                    }
                }
            }
        } catch (IOException failure) {
            errors.add(map("path", ".", "error", failure.getClass().getSimpleName()));
        }
        return map("status", errors.isEmpty() ? "captured_declared_scope" : "incomplete", "files", files,
                "errors", errors, "policy", "allowlisted_game_files_only_no_symlinks_no_audit");
    }

    private void readFile(Path file, List<Object> files, List<Object> errors) {
        try (InputStream stream = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readAllBytes();
            files.add(map("path", profile.relativize(file).toString(), "encoding", "base64",
                    "size", bytes.length, "data", Base64.getEncoder().encodeToString(bytes)));
        } catch (IOException failure) {
            errors.add(map("path", profile.relativize(file).toString(), "error", failure.getClass().getSimpleName()));
        }
    }

    private static Object randomState() {
        Boolean initialized = new ClassInitializationProbe().initialized(com.watabou.utils.Random.class);
        if (!Boolean.TRUE.equals(initialized)) return map("status", "unobserved", "reason",
                initialized == null ? "initialization_state_unavailable" : "not_initialized");
        try {
            // The engine's synchronized export hook must preserve generator order and Gaussian cache.
            byte[] state = (byte[]) com.watabou.utils.Random.class.getMethod("exportState").invoke(null);
            return map("status", "captured", "encoding", "base64", "data", Base64.getEncoder().encodeToString(state));
        } catch (ReflectiveOperationException failure) {
            return map("status", "unavailable", "reason", "rng_export_hook_unavailable");
        }
    }

    static String sceneName(String name) {
        switch (name) {
            case "TitleScene": return "title";
            case "StartScene": return "start";
            case "HeroSelectScene": return "hero_select";
            case "GameScene": return "game";
            case "AlchemyScene": return "alchemy";
            case "InterlevelScene": return "transition";
            case "SurfaceScene": return "surface";
            case "RankingsScene": return "rankings";
            case "BadgesScene": return "badges";
            case "WelcomeScene": return "welcome";
            case "IntroScene": return "intro";
            case "AmuletScene": return "amulet";
            default: return "other";
        }
    }

    public static final class Capture {
        public final Map<String, Object> publicState;
        public final Map<String, Object> internalState;

        public Capture(Map<String, Object> publicState, Map<String, Object> internalState) {
            this.publicState = publicState;
            this.internalState = internalState;
        }
    }
}
