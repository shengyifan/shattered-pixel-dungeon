package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.Preferences;
import com.shatteredpixel.shatteredpixeldungeon.Badges;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.StartOptions;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** In-memory profile policy tests; no files, game initialization or earned progress are needed. */
class StartOptionsTest {
    private final Map<Field, Object> originalFields = new LinkedHashMap<>();
    private final Map<String, Object> settings = new HashMap<>();
    private final HashSet<Badges.Badge> earned = new HashSet<>();
    private int writes;

    @BeforeEach void isolateProfile() throws Exception {
        preserve(GameSettings.class, "prefs");
        preserve(Game.class, "version");
        preserve(Badges.class, "global");
        preserve(Badges.class, "local");
        preserve(Badges.class, "saveNeeded");
        GameSettings.set((Preferences) Proxy.newProxyInstance(Preferences.class.getClassLoader(),
                new Class<?>[]{Preferences.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("contains")) return settings.containsKey(args[0]);
                    if (name.equals("getBoolean")) return settings.getOrDefault(args[0], args.length == 2 ? args[1] : false);
                    if (name.equals("putBoolean")) {
                        settings.put((String) args[0], args[1]);
                        writes++;
                        return proxy;
                    }
                    if (name.equals("flush")) { writes++; return null; }
                    throw new AssertionError("Unexpected preferences operation: " + name);
                }));
        Game.version = "4.0.2";
        field(Badges.class, "global").set(null, earned);
        field(Badges.class, "local").set(null, new HashSet<Badges.Badge>());
        field(Badges.class, "saveNeeded").set(null, false);
    }

    @AfterEach void restoreProfile() throws Exception {
        for (Map.Entry<Field, Object> saved : originalFields.entrySet()) saved.getKey().set(null, saved.getValue());
    }

    @Test void missingAndExplicitFalseSettingsRetainNativeGatesWithoutWrites() {
        assertFalse(SPDSettings.unlockStartOptions());
        assertNativeLocked();
        assertEquals(0, writes);
        assertFalse(settings.containsKey(SPDSettings.KEY_UNLOCK_START_OPTIONS));
        SPDSettings.unlockStartOptions(false);
        int before = writes;
        assertNativeLocked();
        assertEquals(before, writes);
    }

    @Test void nativeHeroEligibilityUsesOnlyItsOwnEarnedBadge() {
        HeroClass[] classes = {HeroClass.MAGE, HeroClass.ROGUE, HeroClass.HUNTRESS, HeroClass.DUELIST, HeroClass.CLERIC};
        Badges.Badge[] badges = {Badges.Badge.UNLOCK_MAGE, Badges.Badge.UNLOCK_ROGUE, Badges.Badge.UNLOCK_HUNTRESS,
                Badges.Badge.UNLOCK_DUELIST, Badges.Badge.UNLOCK_CLERIC};
        for (int i = 0; i < classes.length; i++) {
            earned.clear();
            earned.add(badges[i]);
            for (HeroClass heroClass : HeroClass.values()) {
                assertEquals(heroClass == HeroClass.WARRIOR || heroClass == classes[i], heroClass.isUnlocked(), heroClass.name());
            }
            assertFalse(StartOptions.advancedOptionsUnlocked());
        }
        earned.clear();
        earned.add(Badges.Badge.VICTORY);
        assertTrue(StartOptions.advancedOptionsUnlocked());
        for (HeroClass heroClass : classes) assertFalse(heroClass.isUnlocked());
    }

    @Test void nativePreviewEligibilityPreservesEveryIndependentPrerequisite() {
        assertEquals(1, StartOptions.talentPreviewTiers());
        earned.add(Badges.Badge.BOSS_SLAIN_2);
        earned.add(Badges.Badge.BOSS_SLAIN_4);
        assertTrue(StartOptions.subclassPreviewUnlocked());
        assertTrue(StartOptions.armorAbilityPreviewUnlocked());
        assertEquals(1, StartOptions.talentPreviewTiers());
        earned.add(Badges.Badge.LEVEL_REACHED_1);
        assertEquals(2, StartOptions.talentPreviewTiers());
        earned.add(Badges.Badge.LEVEL_REACHED_2);
        assertEquals(Talent.MAX_TALENT_TIERS, StartOptions.talentPreviewTiers());
        earned.remove(Badges.Badge.BOSS_SLAIN_4);
        assertEquals(3, StartOptions.talentPreviewTiers());
        assertFalse(StartOptions.armorAbilityPreviewUnlocked());
        earned.remove(Badges.Badge.BOSS_SLAIN_2);
        assertEquals(2, StartOptions.talentPreviewTiers());
        assertFalse(StartOptions.subclassPreviewUnlocked());
    }

    @Test void nativeDebugStillOpensItsOriginalGatesButNotInfoTalentTiers() {
        Game.version = "INDEV";
        assertAllAvailableExceptTalentTiers();
        assertEquals(1, StartOptions.talentPreviewTiers());
        assertTrue(earned.isEmpty());
        assertEquals(0, writes);
    }

    @Test void configuredAvailabilityIsPureAndDoesNotForgeProgressOrChangeRunState() throws Exception {
        SPDSettings.unlockStartOptions(true);
        settings.put(SPDSettings.KEY_INTRO, false);
        Map<String, Object> beforeSettings = new HashMap<>(settings);
        Object local = field(Badges.class, "local").get(null);
        Object hero = Dungeon.hero;
        int challenges = Dungeon.challenges;
        int beforeWrites = writes;
        for (int pass = 0; pass < 3; pass++) {
            assertAllAvailableExceptTalentTiers();
            assertEquals(Talent.MAX_TALENT_TIERS, StartOptions.talentPreviewTiers());
        }
        assertEquals("4.0.2", Game.version);
        assertSame(earned, field(Badges.class, "global").get(null));
        assertTrue(earned.isEmpty());
        assertSame(local, field(Badges.class, "local").get(null));
        assertEquals(new HashSet<>(), local);
        assertEquals(false, field(Badges.class, "saveNeeded").get(null));
        assertSame(hero, Dungeon.hero);
        assertEquals(challenges, Dungeon.challenges);
        assertEquals(beforeSettings, settings);
        assertEquals(beforeWrites, writes);
        assertFalse(Badges.isUnlocked(Badges.Badge.VICTORY));
    }

    @Test void disablingAvailabilityRevealsActualEarnedProgressAgain() {
        earned.add(Badges.Badge.UNLOCK_MAGE);
        SPDSettings.unlockStartOptions(true);
        assertAllAvailableExceptTalentTiers();
        SPDSettings.unlockStartOptions(false);
        assertTrue(HeroClass.MAGE.isUnlocked());
        assertFalse(HeroClass.CLERIC.isUnlocked());
        assertFalse(StartOptions.advancedOptionsUnlocked());
        assertEquals(1, StartOptions.talentPreviewTiers());
        assertEquals(1, earned.size());
    }

    private void assertNativeLocked() {
        for (HeroClass heroClass : HeroClass.values()) assertEquals(heroClass == HeroClass.WARRIOR, heroClass.isUnlocked(), heroClass.name());
        assertFalse(StartOptions.advancedOptionsUnlocked());
        assertFalse(StartOptions.subclassPreviewUnlocked());
        assertFalse(StartOptions.armorAbilityPreviewUnlocked());
        assertEquals(1, StartOptions.talentPreviewTiers());
    }

    private void assertAllAvailableExceptTalentTiers() {
        for (HeroClass heroClass : HeroClass.values()) assertTrue(heroClass.isUnlocked(), heroClass.name());
        assertTrue(StartOptions.advancedOptionsUnlocked());
        assertTrue(StartOptions.subclassPreviewUnlocked());
        assertTrue(StartOptions.armorAbilityPreviewUnlocked());
    }

    private void preserve(Class<?> type, String name) throws Exception {
        Field field = field(type, name);
        originalFields.put(field, field.get(null));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
