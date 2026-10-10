package com.shatteredpixel.shatteredpixeldungeon.desktop;

import com.badlogic.gdx.Preferences;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.watabou.noosa.Game;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/** Desktop defaults captured before a launch creates any profile contents. */
public final class DesktopProfileDefaults {
    private DesktopProfileDefaults() { }

    public static Consumer<Preferences> prepare(Path profile) throws IOException {
        return prepare(profile, () -> {});
    }

    /** The callback runs only after these exact preferences are bound to SPDSettings. */
    public static Consumer<Preferences> prepare(Path profile, Runnable additionalDefaults) throws IOException {
        boolean fresh = isNewProfile(profile);
        return preferences -> {
            // Another launch may have populated settings after our initial directory check.
            // A captured fresh result must never replace any subsequently loaded setting.
            if (!fresh || !preferences.get().isEmpty()) return;
            SPDSettings.unlockStartOptions(true);
            SPDSettings.intro(false);
            SPDSettings.version(Game.versionCode);
            additionalDefaults.run();
        };
    }

    public static boolean isNewProfile(Path profile) throws IOException {
        try {
            // NOFOLLOW distinguishes a missing path from an existing dangling symlink.
            Files.readAttributes(profile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            // Some providers also report ENOTDIR as NoSuchFileException. A missing
            // descendant is fresh only when its first existing ancestor is a directory.
            Path parent = profile.toAbsolutePath().normalize().getParent();
            while (parent != null) {
                try {
                    Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!Files.isDirectory(parent)) throw new NotDirectoryException(parent.toString());
                    break;
                } catch (NoSuchFileException missingParent) {
                    parent = parent.getParent();
                }
            }
            return true;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(profile)) {
            return !entries.iterator().hasNext();
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
    }
}
