/*
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.watabou.utils.DeviceCompat;

/** Profile availability for starting runs and previewing hero choices.
 * These reads never grant badges or alter a run's progression. */
public final class StartOptions {
	private StartOptions() {}

	public static boolean heroClassUnlocked(HeroClass heroClass) {
		if (SPDSettings.unlockStartOptions() || DeviceCompat.isDebug()) return true;
		switch (heroClass) {
			case WARRIOR: default:
				return true;
			case MAGE:
				return Badges.isUnlocked(Badges.Badge.UNLOCK_MAGE);
			case ROGUE:
				return Badges.isUnlocked(Badges.Badge.UNLOCK_ROGUE);
			case HUNTRESS:
				return Badges.isUnlocked(Badges.Badge.UNLOCK_HUNTRESS);
			case DUELIST:
				return Badges.isUnlocked(Badges.Badge.UNLOCK_DUELIST);
			case CLERIC:
				return Badges.isUnlocked(Badges.Badge.UNLOCK_CLERIC);
		}
	}

	public static boolean advancedOptionsUnlocked() {
		return SPDSettings.unlockStartOptions() || DeviceCompat.isDebug()
				|| Badges.isUnlocked(Badges.Badge.VICTORY);
	}

	public static boolean subclassPreviewUnlocked() {
		return SPDSettings.unlockStartOptions() || DeviceCompat.isDebug()
				|| Badges.isUnlocked(Badges.Badge.BOSS_SLAIN_2);
	}

	public static boolean armorAbilityPreviewUnlocked() {
		return SPDSettings.unlockStartOptions() || DeviceCompat.isDebug()
				|| Badges.isUnlocked(Badges.Badge.BOSS_SLAIN_4);
	}

	public static int talentPreviewTiers() {
		if (SPDSettings.unlockStartOptions()) return Talent.MAX_TALENT_TIERS;
		// Preserve the native INFO preview rules, including their lack of a debug override.
		if (!Badges.isUnlocked(Badges.Badge.LEVEL_REACHED_1)) return 1;
		if (!Badges.isUnlocked(Badges.Badge.LEVEL_REACHED_2)
				|| !Badges.isUnlocked(Badges.Badge.BOSS_SLAIN_2)) return 2;
		if (!Badges.isUnlocked(Badges.Badge.BOSS_SLAIN_4)) return 3;
		return Talent.MAX_TALENT_TIERS;
	}
}
