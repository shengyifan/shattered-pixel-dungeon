package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.control.game.GameController;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** TEST ONLY postconditions. The public client must never use this stream to choose an action. */
final class UiSceneAssertions {
    static void record(Path profile, GameController.State state) throws IOException {
        List<String> windows = new ArrayList<>();
        collect(Game.scene(), windows);
        Files.writeString(profile.resolve("ui-assertions.jsonl"), JsonCodec.encode(map(
                "test_fixture", true, "internal_assertion_only", true,
                "state_version", state.version, "scope_id", state.scopeId,
                "scene", Game.scene() == null ? null : Game.scene().getClass().getName(),
                "window_classes", windows, "language", Messages.selectedLanguage().name(),
                "language_code", Messages.selectedLanguage().code(),
                "input_generation", Game.inputHandler==null?0:Game.inputHandler.interactionGeneration(),
                "public_ui", com.shatteredpixel.shatteredpixeldungeon.control.game.PublicEnglishProjection.copy(state.publicState.get("ui")),
                "fullscreen", Gdx.graphics.isFullscreen())) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void collect(Gizmo node, List<String> windows) {
        if (node == null || !node.exists || !node.alive || !node.visible) return;
        if (node instanceof Window) windows.add(node.getClass().getName());
        if (node instanceof Group) for (Gizmo child : ((Group) node).childrenSnapshot()) collect(child, windows);
    }
}
