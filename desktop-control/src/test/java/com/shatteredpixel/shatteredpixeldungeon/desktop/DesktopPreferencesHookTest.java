package com.shatteredpixel.shatteredpixeldungeon.desktop;

import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3FileHandle;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Preferences;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.watabou.noosa.Game;
import com.watabou.utils.FileUtils;
import com.watabou.utils.GameSettings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class DesktopPreferencesHookTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Field preferencesField, fileTypeField, defaultPathField;
    private Object previousPreferences, previousFileType, previousDefaultPath;
    private int previousVersion;

    @Before public void rememberGlobals() throws Exception {
        preferencesField = accessible(GameSettings.class, "prefs");
        fileTypeField = accessible(FileUtils.class, "defaultFileType");
        defaultPathField = accessible(FileUtils.class, "defaultPath");
        previousPreferences = preferencesField.get(null);
        previousFileType = fileTypeField.get(null);
        previousDefaultPath = defaultPathField.get(null);
        previousVersion = Game.versionCode;
    }

    @After public void restoreGlobals() throws Exception {
        preferencesField.set(null, previousPreferences);
        fileTypeField.set(null, previousFileType);
        defaultPathField.set(null, previousDefaultPath);
        Game.versionCode = previousVersion;
    }

    @Test public void hookSeesLoadedPreferencesAndFileBindingBeforeReturningToDisplaySetup() throws Exception {
        Path profile = temporary.newFolder().toPath();
        String base = profile.toString() + java.io.File.separator;
        Lwjgl3Preferences persisted = new Lwjgl3Preferences(new Lwjgl3FileHandle(
                base + SPDSettings.DEFAULT_PREFS_FILE, FileType.Absolute));
        persisted.putInteger(SPDSettings.KEY_WINDOW_WIDTH, 955).flush();
        AtomicInteger calls = new AtomicInteger();
        DesktopLauncher.loadPreferences(base, FileType.Absolute, preferences -> {
            assertEquals(955, SPDSettings.windowResolution().x);
            try {
                assertSame(preferences, preferencesField.get(null));
                assertEquals(FileType.Absolute, fileTypeField.get(null));
                assertEquals(base, defaultPathField.get(null));
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
            SPDSettings.put(SPDSettings.KEY_WINDOW_WIDTH, 1001);
            calls.incrementAndGet();
        });
        assertEquals(1, calls.get());
        assertEquals(1001, SPDSettings.windowResolution().x);
    }

    @Test public void existingGuiSettingsRemainByteForByteUnchanged() throws Exception {
        Path profile = temporary.newFolder().toPath();
        String base = profile.toString() + java.io.File.separator;
        Path settings = profile.resolve(SPDSettings.DEFAULT_PREFS_FILE);
        Lwjgl3Preferences persisted = new Lwjgl3Preferences(new Lwjgl3FileHandle(settings.toString(), FileType.Absolute));
        persisted.putBoolean(SPDSettings.KEY_FULLSCREEN, true).putBoolean(SPDSettings.KEY_INTRO, true)
                .putString(SPDSettings.KEY_LANG, "fr").putInteger(SPDSettings.KEY_VERSION, 800).flush();
        byte[] before = Files.readAllBytes(settings);
        DesktopLauncher.loadPreferences(base, FileType.Absolute, DesktopProfileDefaults.prepare(profile));
        assertArrayEquals(before, Files.readAllBytes(settings));
        assertEquals(persisted.get(), ((Lwjgl3Preferences)preferencesField.get(null)).get());
        assertFalse(SPDSettings.unlockStartOptions());
        assertFalse(SPDSettings.contains(SPDSettings.KEY_UNLOCK_START_OPTIONS));
    }

    @Test public void hookFailurePropagatesBeforeDisplaySetupContinues() throws Exception {
        String base = temporary.newFolder().getAbsolutePath() + java.io.File.separator;
        IllegalStateException failure = new IllegalStateException("settings hook failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                DesktopLauncher.loadPreferences(base, FileType.Absolute, preferences -> { throw failure; })));
    }

    @Test public void freshGuiDefaultsAreCapturedBeforeLockAndSkipIntroduction() throws Exception {
        Path profile = temporary.getRoot().toPath().resolve("fresh-gui");
        Consumer<Preferences> defaults = DesktopProfileDefaults.prepare(profile);
        assertFalse(Files.exists(profile));
        Game.versionCode = 922;
        try (ProfileLock ignored = new ProfileLock(profile)) {
            assertFalse(DesktopProfileDefaults.isNewProfile(profile));
            DesktopLauncher.loadPreferences(profile + java.io.File.separator, FileType.Absolute, defaults);
            assertTrue(SPDSettings.unlockStartOptions());
            assertFalse(SPDSettings.intro());
            assertEquals(Game.versionCode, SPDSettings.version());
            Preferences actual = (Preferences) preferencesField.get(null);
            assertEquals(3, actual.get().size());
            assertFalse(actual.contains(SPDSettings.KEY_LANG));
            assertFalse(actual.contains(SPDSettings.KEY_FULLSCREEN));
        }
        try (java.util.stream.Stream<Path> files = Files.list(profile)) {
            assertEquals(java.util.Set.of(".instance.lock", SPDSettings.DEFAULT_PREFS_FILE),
                    files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test public void guiReopenPreservesExplicitFalseAndOtherSettings() throws Exception {
        Path profile = temporary.newFolder().toPath();
        String base = profile + java.io.File.separator;
        DesktopLauncher.loadPreferences(base, FileType.Absolute, DesktopProfileDefaults.prepare(profile));
        assertTrue(SPDSettings.unlockStartOptions());
        SPDSettings.unlockStartOptions(false);
        SPDSettings.put(SPDSettings.KEY_LANG, "fr");
        SPDSettings.put(SPDSettings.KEY_FULLSCREEN, false);
        byte[] before = Files.readAllBytes(profile.resolve(SPDSettings.DEFAULT_PREFS_FILE));
        DesktopLauncher.loadPreferences(base, FileType.Absolute, DesktopProfileDefaults.prepare(profile));
        assertFalse(SPDSettings.unlockStartOptions());
        assertEquals("fr", ((Preferences) preferencesField.get(null)).getString(SPDSettings.KEY_LANG));
        assertFalse(SPDSettings.fullscreen());
        assertArrayEquals(before, Files.readAllBytes(profile.resolve(SPDSettings.DEFAULT_PREFS_FILE)));
    }

    @Test public void nonemptyGuiProfileWithoutSettingsRemainsLocked() throws Exception {
        Path profile = temporary.newFolder().toPath();
        Files.createDirectory(profile.resolve("game1"));
        DesktopLauncher.loadPreferences(profile + java.io.File.separator, FileType.Absolute,
                DesktopProfileDefaults.prepare(profile));
        assertFalse(SPDSettings.unlockStartOptions());
        assertTrue(SPDSettings.intro());
        assertEquals(0, SPDSettings.version());
        assertFalse(Files.exists(profile.resolve(SPDSettings.DEFAULT_PREFS_FILE)));
        assertTrue(((Preferences) preferencesField.get(null)).get().isEmpty());
    }

    @Test public void guiDefaultsDoNotReplaceSettingsCreatedAfterFreshCheck() throws Exception {
        Path profile = temporary.newFolder().toPath();
        Consumer<Preferences> defaults = DesktopProfileDefaults.prepare(profile);
        Path settings = profile.resolve(SPDSettings.DEFAULT_PREFS_FILE);
        Lwjgl3Preferences intervening = new Lwjgl3Preferences(new Lwjgl3FileHandle(settings.toString(), FileType.Absolute));
        intervening.putString("unrelated", "arrived-after-check").flush();
        byte[] before = Files.readAllBytes(settings);
        DesktopLauncher.loadPreferences(profile + java.io.File.separator, FileType.Absolute, defaults);
        assertFalse(SPDSettings.unlockStartOptions());
        assertTrue(SPDSettings.intro());
        assertEquals(0, SPDSettings.version());
        assertArrayEquals(before, Files.readAllBytes(settings));
    }

    private static Field accessible(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
