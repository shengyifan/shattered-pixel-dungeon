package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Signal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WandTargetingFeedbackTest {

    @BeforeAll
    static void resources() {
        GameSnapshotterTest.resourceOnlyRuntime();
    }

    @AfterEach
    void clearFixture() {
        ProbeWand.bind(null, null);
        Dungeon.hero = null;
        Dungeon.level = null;
        Messages.setup(Languages.ENGLISH);
    }

    @Test
    void nativeTargetingDistinguishesSelfFromAnImmediatelyBlockedPathWithoutUsingTheWand() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Messages.setup(Languages.CHI_SMPL);
            FixtureLevel level = new FixtureLevel();
            Dungeon.level = level;
            Hero hero = new Hero();
            hero.pos = 12;
            Dungeon.hero = hero;
            ProbeWand wand = new ProbeWand();
            ProbeWand.bind(hero, wand);

            List<String> messages = new ArrayList<>();
            Signal.Listener<String> listener = value -> {
                messages.add(PublicEnglishProjection.text(value));
                return false;
            };
            GLog.update.add(listener);
            try {
                int charges = wand.curCharges;
                float cooldown = hero.cooldown();

                ProbeWand.select(hero.pos);
                assertEquals("You can't target yourself!", messages.get(messages.size()-1));
                assertEquals(charges, wand.curCharges);
                assertEquals(cooldown, hero.cooldown());
                assertEquals(0, wand.zapAttempts);

                level.block(13);
                ProbeWand.select(14);
                assertEquals("Your wand's path is blocked!", messages.get(messages.size()-1));
                assertEquals(charges, wand.curCharges);
                assertEquals(cooldown, hero.cooldown());
                assertEquals(0, wand.zapAttempts);
            } finally {
                GLog.update.remove(listener);
            }
        }
    }

    @Test
    void blockedPathResourceExistsInEveryEnabledLanguageWithoutPlaceholders() {
        for (Languages language : Languages.values()) {
            java.util.Properties items = ResourceTextFixture.properties("items", language);
            String key = "items.wands.wand.blocked_path";
            assertTrue(items.containsKey(key), language.code());
            String value = items.getProperty(key);
            assertFalse(value.isBlank(), language.code());
            assertFalse(value.contains("%"), language.code());
        }
    }

    private static final class ProbeWand extends Wand {
        int zapAttempts;

        static void bind(Hero hero, ProbeWand wand) {
            curUser = hero;
            curItem = wand;
        }

        static void select(int target) {
            zapper.onSelect(target);
        }

        @Override
        public boolean tryToZap(Hero owner, int target) {
            zapAttempts++;
            return super.tryToZap(owner, target);
        }

        @Override public void onZap(Ballistica attack) { }
        @Override public void onHit(MagesStaff staff, Char attacker, Char defender, int damage) { }
    }

    private static final class FixtureLevel extends Level {
        FixtureLevel() {
            blobs = new HashMap<>();
            setSize(5, 5);
            Arrays.fill(map, Terrain.EMPTY);
            buildFlagMaps();
        }

        void block(int cell) {
            map[cell] = Terrain.WALL;
            buildFlagMaps();
        }

        @Override protected boolean build() { return true; }
        @Override protected void createMobs() { }
        @Override protected void createItems() { }
    }
}
