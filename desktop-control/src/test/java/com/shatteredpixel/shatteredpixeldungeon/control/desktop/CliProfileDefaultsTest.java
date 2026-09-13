package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3FileHandle;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Preferences;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class CliProfileDefaultsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Field preferencesField;
    private Object previousPreferences;
    private int previousVersion;

    @Before public void rememberGlobals() throws Exception {
        preferencesField = GameSettings.class.getDeclaredField("prefs");
        preferencesField.setAccessible(true);
        previousPreferences = preferencesField.get(null);
        previousVersion = Game.versionCode;
    }

    @After public void restoreGlobals() throws Exception {
        preferencesField.set(null, previousPreferences);
        Game.versionCode = previousVersion;
    }

    @Test public void missingAndEmptyDirectoriesAreFreshWithoutAnyWrites() throws Exception {
        Path missing = temporary.getRoot().toPath().resolve("missing").resolve("nested");
        assertTrue(CliProfileDefaults.isNewProfile(missing));
        CliProfileDefaults.prepare(missing);
        assertFalse(Files.exists(missing.getParent()));
        Path empty = temporary.newFolder().toPath();
        assertTrue(CliProfileDefaults.isNewProfile(empty));
        CliProfileDefaults.prepare(empty);
        try (java.util.stream.Stream<Path> entries = Files.list(empty)) {
            assertEquals(0, entries.count());
        }
    }

    @Test public void anyExistingEntryPreventsDefaultInitialization() throws Exception {
        Path profile = temporary.newFolder().toPath();
        Files.createFile(profile.resolve(".unrelated-file"));
        assertFalse(CliProfileDefaults.isNewProfile(profile));
        Runnable defaults = CliProfileDefaults.prepare(profile);
        Preferences preferences = preferences(profile);
        SPDSettings.set(preferences);
        defaults.run();
        assertTrue(preferences.get().isEmpty());
        assertFalse(Files.exists(profile.resolve(SPDSettings.DEFAULT_PREFS_FILE)));
    }

    @Test public void existingEmptySubdirectoryAlsoPreventsDefaults() throws Exception {
        Path profile = temporary.newFolder().toPath();
        Files.createDirectory(profile.resolve("audit"));
        assertFalse(CliProfileDefaults.isNewProfile(profile));
    }

    @Test public void freshDefaultsAreDeferredUntilSettingsHaveLoaded() throws Exception {
        Path profile = temporary.getRoot().toPath().resolve("new-profile");
        Runnable defaults = CliProfileDefaults.prepare(profile);
        assertFalse(Files.exists(profile));
        Files.createDirectories(profile);
        Files.createFile(profile.resolve(".profile-lock-created-after-check"));
        Preferences preferences = preferences(profile);
        SPDSettings.set(preferences);
        Game.versionCode = 912;
        assertTrue(preferences.get().isEmpty());
        defaults.run();
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put(SPDSettings.KEY_FULLSCREEN, false);
        expected.put(SPDSettings.KEY_LANG, "zh");
        expected.put(SPDSettings.KEY_INTRO, false);
        expected.put(SPDSettings.KEY_VERSION, 912);
        assertEquals(expected.keySet(), preferences.get().keySet());
        // Lwjgl3Preferences stores XML values as strings; its typed accessors
        // provide the same values without requiring an in-memory map shape.
        Preferences reloaded = preferences(profile);
        assertEquals(expected.keySet(), reloaded.get().keySet());
        assertFalse(reloaded.getBoolean(SPDSettings.KEY_FULLSCREEN));
        assertEquals("zh", reloaded.getString(SPDSettings.KEY_LANG));
        assertFalse(reloaded.getBoolean(SPDSettings.KEY_INTRO));
        assertEquals(912, reloaded.getInteger(SPDSettings.KEY_VERSION));
        assertFalse(SPDSettings.fullscreen());
        assertEquals(Languages.CHI_SMPL, SPDSettings.language());
        assertFalse(SPDSettings.intro());
        assertEquals(912, SPDSettings.version());
    }

    @Test public void existingPreferencesRemainByteForByteUnchanged() throws Exception {
        Path profile = temporary.newFolder().toPath();
        Preferences preferences = preferences(profile);
        preferences.putBoolean(SPDSettings.KEY_FULLSCREEN, true)
                .putString(SPDSettings.KEY_LANG, "en")
                .putBoolean(SPDSettings.KEY_INTRO, true)
                .putInteger(SPDSettings.KEY_VERSION, 800)
                .putString("unrelated", "preserve").flush();
        Path settings = profile.resolve(SPDSettings.DEFAULT_PREFS_FILE);
        byte[] before = Files.readAllBytes(settings);
        Runnable defaults = CliProfileDefaults.prepare(profile);
        SPDSettings.set(preferences);
        Game.versionCode = 912;
        defaults.run();
        assertArrayEquals(before, Files.readAllBytes(settings));
        assertTrue(SPDSettings.fullscreen());
        assertEquals(Languages.ENGLISH, SPDSettings.language());
        assertTrue(SPDSettings.intro());
        assertEquals(800, SPDSettings.version());
        assertEquals("preserve", preferences.getString("unrelated"));
    }

    @Test public void invalidProfileIsNotTreatedAsFresh() throws Exception {
        Path file = temporary.newFile().toPath();
        assertThrows(IOException.class, () -> CliProfileDefaults.prepare(file));
        assertThrows(IOException.class, () -> CliProfileDefaults.prepare(file.resolve("child")));
        Path dangling = temporary.getRoot().toPath().resolve("dangling");
        Files.createSymbolicLink(dangling, temporary.getRoot().toPath().resolve("absent-target"));
        assertThrows(IOException.class, () -> CliProfileDefaults.prepare(dangling));
        assertTrue(Files.isSymbolicLink(dangling));
    }

    @Test public void directoryAliasesUseExistingContents() throws Exception {
        Path profile = temporary.newFolder().toPath();
        Path alias = temporary.getRoot().toPath().resolve("alias");
        Files.createSymbolicLink(alias, profile);
        assertTrue(CliProfileDefaults.isNewProfile(alias));
        Files.createFile(profile.resolve("existing"));
        assertFalse(CliProfileDefaults.isNewProfile(alias));
    }

    private static Preferences preferences(Path profile) {
        return new Lwjgl3Preferences(new Lwjgl3FileHandle(
                profile.resolve(SPDSettings.DEFAULT_PREFS_FILE).toString(), FileType.Absolute));
    }
}
