package com.shatteredpixel.shatteredpixeldungeon.desktop;

import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3FileHandle;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Preferences;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
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

import static org.junit.Assert.*;

public class DesktopPreferencesHookTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Field preferencesField, fileTypeField, defaultPathField;
    private Object previousPreferences, previousFileType, previousDefaultPath;

    @Before public void rememberGlobals() throws Exception {
        preferencesField = accessible(GameSettings.class, "prefs");
        fileTypeField = accessible(FileUtils.class, "defaultFileType");
        defaultPathField = accessible(FileUtils.class, "defaultPath");
        previousPreferences = preferencesField.get(null);
        previousFileType = fileTypeField.get(null);
        previousDefaultPath = defaultPathField.get(null);
    }

    @After public void restoreGlobals() throws Exception {
        preferencesField.set(null, previousPreferences);
        fileTypeField.set(null, previousFileType);
        defaultPathField.set(null, previousDefaultPath);
    }

    @Test public void hookSeesLoadedPreferencesAndFileBindingBeforeReturningToDisplaySetup() throws Exception {
        Path profile = temporary.newFolder().toPath();
        String base = profile.toString() + java.io.File.separator;
        Lwjgl3Preferences persisted = new Lwjgl3Preferences(new Lwjgl3FileHandle(
                base + SPDSettings.DEFAULT_PREFS_FILE, FileType.Absolute));
        persisted.putInteger(SPDSettings.KEY_WINDOW_WIDTH, 955).flush();
        AtomicInteger calls = new AtomicInteger();
        DesktopLauncher.loadPreferences(base, FileType.Absolute, () -> {
            assertEquals(955, SPDSettings.windowResolution().x);
            try {
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

    @Test public void emptyHookKeepsOrdinaryDesktopSettingsUnchanged() throws Exception {
        Path profile = temporary.newFolder().toPath();
        String base = profile.toString() + java.io.File.separator;
        Path settings = profile.resolve(SPDSettings.DEFAULT_PREFS_FILE);
        Lwjgl3Preferences persisted = new Lwjgl3Preferences(new Lwjgl3FileHandle(settings.toString(), FileType.Absolute));
        persisted.putBoolean(SPDSettings.KEY_FULLSCREEN, true).putBoolean(SPDSettings.KEY_INTRO, true)
                .putString(SPDSettings.KEY_LANG, "fr").putInteger(SPDSettings.KEY_VERSION, 800).flush();
        byte[] before = Files.readAllBytes(settings);
        DesktopLauncher.loadPreferences(base, FileType.Absolute, () -> {});
        assertArrayEquals(before, Files.readAllBytes(settings));
        assertEquals(persisted.get(), ((Lwjgl3Preferences)preferencesField.get(null)).get());
    }

    @Test public void hookFailurePropagatesBeforeDisplaySetupContinues() throws Exception {
        String base = temporary.newFolder().getAbsolutePath() + java.io.File.separator;
        IllegalStateException failure = new IllegalStateException("settings hook failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                DesktopLauncher.loadPreferences(base, FileType.Absolute, () -> { throw failure; })));
    }

    private static Field accessible(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
