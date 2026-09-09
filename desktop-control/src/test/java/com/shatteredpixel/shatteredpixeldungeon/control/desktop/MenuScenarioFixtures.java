package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Badges;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Profile progress preparation only. All menu changes and game creation use original public actions. */
final class MenuScenarioFixtures {
    static boolean supports(String name) { return name.equals("locked") || name.equals("unlocked"); }
    static void prepare(String name) {
        if(name.equals("unlocked")) {
            Badges.unlock(Badges.Badge.VICTORY);
            SPDSettings.victoryNagged(true); // This fixture represents a previously acknowledged victory.
        }
    }
    static Map<String,Object> assertions() {
        return map("victory_unlocked",Badges.isUnlocked(Badges.Badge.VICTORY),
                "custom_seed",SPDSettings.customSeed(),"challenges",SPDSettings.challenges(),
                "daily",Dungeon.daily,"daily_replay",Dungeon.dailyReplay);
    }
}
