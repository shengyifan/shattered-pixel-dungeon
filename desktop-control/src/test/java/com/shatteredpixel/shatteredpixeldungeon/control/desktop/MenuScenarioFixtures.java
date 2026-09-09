package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Badges;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.watabou.noosa.Game;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Profile progress preparation only. All menu changes and game creation use original public actions. */
final class MenuScenarioFixtures {
    static boolean supports(String name) { return name.equals("locked") || name.equals("unlocked") || name.equals("daily") || name.equals("random-confirm") || name.equals("daily-cycle") || name.equals("daily-future") || name.equals("seed-duplicate"); }
    static void prepare(String name) {
        if(!name.equals("locked")) {
            Badges.unlock(Badges.Badge.VICTORY);
            SPDSettings.victoryNagged(true); // This fixture represents a previously acknowledged victory.
        }
        if(name.equals("daily-future"))SPDSettings.lastDaily(Game.realTime+3L*24*60*60*1000);
    }
    static Map<String,Object> assertions() {
        return map("victory_unlocked",Badges.isUnlocked(Badges.Badge.VICTORY),
                "custom_seed",SPDSettings.customSeed(),"challenges",SPDSettings.challenges(),
                "daily",Dungeon.daily,"daily_replay",Dungeon.dailyReplay,
                "game_custom_seed",Dungeon.customSeedText,"game_challenges",Dungeon.challenges,
                "selected_class",GamesInProgress.selectedClass==null?null:GamesInProgress.selectedClass.name(),
                "class_randomized",GamesInProgress.randomizedClass);
    }
}
