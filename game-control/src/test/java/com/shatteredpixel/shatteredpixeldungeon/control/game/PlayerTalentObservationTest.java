package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.HeroicLeap;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfDivineInspiration.DivineInspirationTracker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTalentObservationTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void availablePointsFollowNativeLevelSpendAndSubclassGates() {
        Hero previous = Dungeon.hero;
        Hero hero = new Hero(); Dungeon.hero = hero;
        hero.heroClass = HeroClass.WARRIOR;
        Talent.initClassTalents(hero);
        try {
            hero.lvl = 1;
            assertEquals(List.of(0, 0, 0, 0), points(hero));
            hero.lvl = 7;
            hero.talents.get(0).put(Talent.HEARTY_MEAL, 2);
            assertEquals(List.of(3, 1, 0, 0), points(hero));
            hero.lvl = 13;
            assertEquals(0, points(hero).get(2));
            hero.subClass = HeroSubClass.BERSERKER;
            Talent.initSubclassTalents(hero);
            assertEquals(1, points(hero).get(2));
            hero.lvl = 21;
            assertEquals(0, points(hero).get(3));
            hero.armorAbility = new HeroicLeap();
            Talent.initArmorTalents(hero);
            assertEquals(1, points(hero).get(3));
        } finally { Dungeon.hero = previous; }
    }

    @Test void inspirationBonusIsObservedWithoutChangingItsTrackerOrTalentState() {
        Hero previous = Dungeon.hero;
        Hero hero = new Hero(); Dungeon.hero = hero;
        hero.heroClass = HeroClass.WARRIOR;
        Talent.initClassTalents(hero);
        hero.lvl = 7;
        DivineInspirationTracker tracker = new DivineInspirationTracker();
        try {
            assertTrue(tracker.attachTo(hero));
            tracker.setBoosted(2);
            assertEquals(List.of(5, 3, 0, 0), points(hero));
            int count = hero.buffs().size();
            Map<Talent, Integer> before = new java.util.LinkedHashMap<>(hero.talents.get(1));
            for (int i = 0; i < 4; i++) assertEquals(List.of(5, 3, 0, 0), points(hero));
            assertEquals(count, hero.buffs().size());
            assertEquals(before, hero.talents.get(1));
            assertTrue(tracker.isBoosted(2));
            assertFalse(tracker.isBoosted(1));
        } finally {
            if (tracker.target != null) tracker.detach();
            Dungeon.hero = previous;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> points(Hero hero) {
        Map<?, ?> value = (Map<?, ?>) PlayerObservation.capture(hero, null, "game").get("hero");
        return (List<Integer>) value.get("talent_points_available");
    }
}
