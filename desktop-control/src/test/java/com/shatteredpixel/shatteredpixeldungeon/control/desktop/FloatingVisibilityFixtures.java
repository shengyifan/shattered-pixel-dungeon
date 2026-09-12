package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.utils.PointF;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Test-only existing FloatingText instances; never renders or calculates a combat result. */
final class FloatingVisibilityFixtures {
    private static final List<FloatingText> sources = new ArrayList<>();
    static boolean supports(String name) { return name.equals("visibility"); }

    static void prepare(Hero hero) throws ReflectiveOperationException {
        sources.clear();
        PointF center = hero.sprite.center();
        String[] texts = {"314159", "战士", "闪避"};
        Field lifetime = FloatingText.class.getDeclaredField("timeLeft");
        lifetime.setAccessible(true);
        for (int i = 0; i < texts.length; i++) {
            FloatingText source = GameScene.status();
            if (source == null) throw new IllegalStateException("No original GameScene status layer");
            source.reset(center.x + (i - 1) * 32, center.y + 32, texts[i], 0xFFFFFF, FloatingText.NO_ICON, false);
            lifetime.setFloat(source, 30f); // Real update and draw remain active, including the original drift.
            sources.add(source);
        }
    }

    static Map<String, Object> assertions() throws ReflectiveOperationException {
        Field lifetime = FloatingText.class.getDeclaredField("timeLeft"); lifetime.setAccessible(true);
        List<Object> rows = new ArrayList<>();
        for (FloatingText source : sources) rows.add(map("stored_text", source.text(),
                "alive", source.alive, "exists", source.exists, "active", source.active,
                "time_left", lifetime.getFloat(source), "x", source.left(), "y", source.top()));
        return map("sources", rows);
    }
}
