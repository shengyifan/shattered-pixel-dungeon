package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
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

/** CLI-only defaults, captured before this launch adds any profile contents. */
final class CliProfileDefaults {
    private CliProfileDefaults() { }

    static Runnable prepare(Path profile) throws IOException {
        boolean fresh = isNewProfile(profile);
        return () -> {
            if (!fresh) return;
            // No application exists yet, so avoid fullscreen(boolean)'s live UI update.
            SPDSettings.put(SPDSettings.KEY_FULLSCREEN, false);
            SPDSettings.language(Languages.CHI_SMPL);
            SPDSettings.intro(false);
            SPDSettings.version(Game.versionCode);
        };
    }

    static boolean isNewProfile(Path profile) throws IOException {
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
