#!/usr/bin/env python3
"""Pure exact enumeration of the 4.0.2 ordinary-floor upgrade-slot formulas.

No game launch, random draws, seeds, save files, profiles, or private audits.
Model assumptions:
  * main branch ordinary floors are generated sequentially;
  * each region has exactly three original slots across four ordinary floors;
  * Dungeon.souNeeded uses slots_left / floors_left;
  * Level.create increments the global slot count before keeping odd slots only.
Source provenance hashes are of repository source text, not any game instance.
"""
from collections import defaultdict
from fractions import Fraction
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SOURCES = {
    'Dungeon.java': 'core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java',
    'Level.java': 'core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java',
}


def distribution(previous_original_slots):
    states = {(0, ()): Fraction(1)}
    for floor in range(1, 5):
        next_states = defaultdict(Fraction)
        for (spawned, kept), probability in states.items():
            remaining = 3 - spawned
            q = Fraction(remaining, 5 - floor) if remaining > 0 else Fraction(0)
            assert 0 <= q <= 1
            for generated, branch_probability in ((False, 1 - q), (True, q)):
                if not branch_probability:
                    continue
                new_count = spawned + int(generated)
                keep = generated and (previous_original_slots + new_count) % 2 == 1
                new_kept = kept + (floor,) if keep else kept
                next_states[(new_count, new_kept)] += probability * branch_probability
        assert sum(next_states.values()) == 1
        states = dict(next_states)
    assert all(count == 3 for count, _ in states)
    return states


def main():
    regions = []
    for region in range(1, 6):
        offset = 5 * (region - 1)
        states = distribution(3 * (region - 1))
        patterns = [
            {'depths': [offset + f for f in kept], 'probability': str(p)}
            for (_, kept), p in sorted(states.items())
        ]
        chances = {
            str(offset + f): str(sum(p for (_, kept), p in states.items() if f in kept))
            for f in range(1, 5)
        }
        totals = {len(kept) for _, kept in states}
        assert len(totals) == 1
        regions.append({
            'region': region,
            'retained_slots': totals.pop(),
            'retained_depth_patterns': patterns,
            'per_depth_marginal_probability': chances,
        })
    assert [r['retained_slots'] for r in regions] == [2, 1, 2, 1, 2]
    assert sum(r['retained_slots'] for r in regions) == 8
    result = {
        'kind': 'pure_formula_enumeration_not_level_generation',
        'game_version': '4.0.2 / code 922',
        'reviewed_repository_commit': 'd04c8f862',
        'source_rules': {
            'Dungeon.souNeeded': 'Dungeon.java:558-566',
            'increment_then_keep_odd': 'levels/Level.java:232-240',
        },
        'source_sha256': {
            name: hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
            for name, relative in SOURCES.items()
        },
        'regions': regions,
        'total_retained_mainline_slots': 8,
        'limitations': [
            'Models normal sequential mainline generation, not arbitrary modified slot history.',
            'Does not reveal the current run identity, locations, collected items, or RNG outcomes.',
            'Does not count special extra sources and cannot identify an unknown scroll.',
            'An uncollected generated item does not cause its slot to be regenerated later.',
        ],
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
