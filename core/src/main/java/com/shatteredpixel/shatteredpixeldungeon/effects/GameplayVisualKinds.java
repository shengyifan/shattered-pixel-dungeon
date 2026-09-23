package com.shatteredpixel.shatteredpixeldungeon.effects;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Reviewed visual meanings which invalidate an action decision; samples and completed-action feedback do not. */
public final class GameplayVisualKinds {
    private GameplayVisualKinds() {}
    private static final Set<String> STATEFUL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "red_target", "quickslot_target", "black_goo_droplets", "arcane_bomb_warning", "bomb_smoke", "pitfall_warning",
            "bomb_countdown_1", "bomb_countdown_2", "bomb_countdown_3", "falling_rock_warning",
            "evil_eye_charging", "dm300_charging", "dm300_supercharged", "necromancer_charging", "spectral_necromancer_charging",
            "ripper_leap_preparation", "sentry_charge_particles", "golem_teleport_particles",
            "summoning_bones", "summoning_shadow", "summoning_shadows", "summoning_sparks", "summoning_green_flames",
            "downed_ghoul", "downed_crystal_guardian", "prismatic_image_paused", "crystal_spire_state",
            "fading_trap_pattern", "tengu_trap_spark", "noisemaker_alarm", "warp_beacon", "challenge_arena", "lotus_range",
            "supernova_halo", "sprite_state", "character_burning", "character_levitating",
            "character_chilled", "character_marked", "character_healing", "character_hearts", "character_shielded",
            "character_illuminated", "character_aura", "ward_state", "statue_armor", "gnoll_earth_armor",
            "geomancer_stone_form", "item_glow", "item_status", "animated_container",
            "exposed_wiring", "metal_gate", "pylon_platform", "vault_entrance", "vault_barrier", "mine_exit", "spectral_wall"
    )));
    public static boolean affectsIntent(String kind) {
        // electricity_flow reports observed particle motion; map.env separately owns the stable damage cells.
        return STATEFUL.contains(kind);
    }
}
