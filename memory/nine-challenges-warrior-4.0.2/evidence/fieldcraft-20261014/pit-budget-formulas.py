#!/usr/bin/env python3
"""Offline arithmetic worksheet; no game launch, saves, transport, or random draws.

This is a conservative finite-horizon model, not an engine test or a safety oracle.
Only the named repository source files are read, to record their SHA-256 hashes.
Run from any directory; JSON is emitted to stdout and nothing is written implicitly.
"""

from pathlib import Path
import hashlib
import json
import math
import struct

ROOT = Path(__file__).resolve().parents[4]
PREFIX = "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/"
SOURCE_PATHS = [
    PREFIX + "levels/features/Chasm.java",
    PREFIX + "actors/buffs/Bleeding.java",
    PREFIX + "actors/Char.java",
    PREFIX + "items/BrokenSeal.java",
    "SPD-classes/src/main/java/com/watabou/utils/Random.java",
]


def f32(value):
    return struct.unpack("!f", struct.pack("!f", value))[0]


def bleed_input(hp, ht):
    ratio = f32(hp / ht)
    denominator = f32(6.0 + f32(6.0 * ratio))
    return math.floor(f32(ht / denominator) + 0.5)


def worksheet(hp, ht):
    direct_min = hp // 2
    direct_max = max(hp // 2, ht // 4)
    bleed = bleed_input(hp, ht)
    return {
        "hp": hp,
        "ht": ht,
        "direct_damage_range": [direct_min, direct_max],
        "bleed_input_before_random_pre_decay": bleed,
        "hp_after_direct_min_without_shields_or_modifiers": hp - direct_max,
        "hp_lower_bound_after_three_bleed_ticks_without_shields_or_modifiers": hp - direct_max - 3 * bleed,
        "interpretation": "Negative bounds mean the budget cannot certify survival; positive bounds cover only this finite damage model.",
    }


def seal_first_hit(hp, ht, existing_shield, seal_amount):
    damage = max(hp // 2, ht // 4)
    activates = hp <= ht // 2 or hp + existing_shield - damage <= ht // 2
    available = existing_shield + (seal_amount if activates else 0)
    return {
        "hp": hp,
        "ht": ht,
        "existing_shield": existing_shield,
        "ready_seal_amount": seal_amount,
        "direct_damage_upper_bound": damage,
        "seal_activates_on_this_hit": activates,
        "hp_after_this_hit": hp - max(0, damage - available),
        "shield_after_this_hit": max(0, available - damage),
    }


result = {
    "research_date": "2026-10-14",
    "reviewed_git_head": "bb0c91fb7c6fa8e33819c554427f81b6cf93f2f9",
    "scope": "Synthetic source-formula arithmetic only; no RNG, engine, actor scheduling, inventory, or live state.",
    "assumptions": [
        "No feather fall, pre-existing bleed, other damage, healing, resistance, damage multiplier, or immunity.",
        "Three ticks means three Bleeding.act executions, not three arbitrary player actions.",
        "Bleeding has no fixed documented maximum number of ticks; this does not bound total lifetime damage.",
        "Seal examples assume its buff exists, is ready, has the stated maximum, and has not decayed.",
    ],
    "source_sha256": {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in SOURCE_PATHS},
    "finite_horizon_rows": [worksheet(hp, ht) for hp, ht in [(20, 20), (25, 25), (17, 25), (12, 25), (6, 25), (50, 50), (33, 50)]],
    "seal_first_hit_examples": [seal_first_hit(hp, ht, 0, 5) for hp, ht in [(20, 20), (25, 25), (12, 25)]],
}
print(json.dumps(result, indent=2, ensure_ascii=False))
