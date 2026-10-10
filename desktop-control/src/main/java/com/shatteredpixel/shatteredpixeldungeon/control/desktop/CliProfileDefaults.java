package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.badlogic.gdx.Preferences;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopProfileDefaults;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** CLI-only defaults, captured before this launch adds any profile contents. */
final class CliProfileDefaults {
    private CliProfileDefaults() { }

    static Consumer<Preferences> prepare(Path profile) throws IOException {
        return DesktopProfileDefaults.prepare(profile, () -> {
            // No application exists yet, so avoid fullscreen(boolean)'s live UI update.
            SPDSettings.put(SPDSettings.KEY_FULLSCREEN, false);
            SPDSettings.language(Languages.CHI_SMPL);
        });
    }

    static boolean isNewProfile(Path profile) throws IOException {
        return DesktopProfileDefaults.isNewProfile(profile);
    }
}
