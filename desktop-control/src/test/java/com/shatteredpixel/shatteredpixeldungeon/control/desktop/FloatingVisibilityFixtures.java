package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Game;
import com.watabou.utils.PointF;
import com.watabou.utils.SparseArray;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Test-only existing FloatingText instances; never renders or calculates a combat result. */
final class FloatingVisibilityFixtures {
    private static final List<FloatingText> sources = new ArrayList<>();
    private static final List<String> texts = Arrays.asList("314159", "warrior", "dodged");
    private static int anchorCell = -1;
    private static boolean ready;
    static boolean supports(String name) { return name.equals("visibility"); }

    static void prepare(Hero hero) {
        sources.clear();
        anchorCell = hero.pos;
        ready = false;
        PointF center = hero.sprite.center();
        for (int i = 0; i < texts.size(); i++) {
            // Use the same native anchored presentation path as combat text.
            // The test extends only its lifetime after the queued native show.
            FloatingText.showOnCell(center.x + (i - 1) * 32, center.y + 32,
                    anchorCell, Messages.literal(texts.get(i)), 0xFFFFFF);
        }
        Game.runOnRenderThread(() -> {
            try {
                Field stacksField = FloatingText.class.getDeclaredField("stacks");
                stacksField.setAccessible(true);
                @SuppressWarnings("unchecked") SparseArray<ArrayList<FloatingText>> stacks =
                        (SparseArray<ArrayList<FloatingText>>)stacksField.get(null);
                ArrayList<FloatingText> atCell = stacks.get(anchorCell);
                if (atCell == null) throw new IllegalStateException("Native anchored floating stack missing");
                Field lifetime = FloatingText.class.getDeclaredField("timeLeft");
                lifetime.setAccessible(true);
                sources.clear();
                for (String text : texts) {
                    FloatingText found = null;
                    for (FloatingText source : atCell)
                        if (text.equals(source.text()) && source.alive && source.exists) {
                            if (found != null) throw new IllegalStateException("Ambiguous anchored source " + text);
                            found = source;
                        }
                    if (found == null || found.observationCell() != anchorCell)
                        throw new IllegalStateException("Native anchored source missing " + text);
                    lifetime.setFloat(found, 30f);
                    sources.add(found);
                }
                ready = true;
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot inspect isolated native floating fixture", error);
            }
        });
    }

    static Map<String, Object> assertions() throws ReflectiveOperationException {
        Field lifetime = FloatingText.class.getDeclaredField("timeLeft"); lifetime.setAccessible(true);
        List<Object> rows = new ArrayList<>();
        for (FloatingText source : sources) rows.add(map("stored_text", source.text(),
                "alive", source.alive, "exists", source.exists, "active", source.active,
                "time_left", lifetime.getFloat(source), "anchor_cell", source.observationCell(),
                "x", source.left(), "y", source.top()));
        return map("ready", ready, "anchor_cell", anchorCell, "sources", rows);
    }
}
