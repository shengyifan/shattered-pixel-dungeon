#!/usr/bin/env python3
"""Read-only Yog laser union and adjacent cell facts from the current save."""
import json
import sys
sys.dont_write_bytecode = True
sys.path.insert(0, '/tmp/spd-run-20260905')
import checkpoint as c


def ray(source, target, width, height):
    x, y = source % width, source // width
    tx, ty = target % width, target // width
    dx, dy = tx-x, ty-y
    if dx == 0 and dy == 0:
        return [source]
    sx, sy = (1 if dx > 0 else -1), (1 if dy > 0 else -1)
    dx, dy = abs(dx), abs(dy)
    major, minor = max(dx, dy), min(dx, dy)
    err = major // 2
    cells = []
    while 0 < x < width-1 and 0 < y < height-1:
        cells.append(x+y*width)
        if dx > dy:
            x += sx
        else:
            y += sy
        err += minor
        if err >= major:
            err -= major
            if dx > dy:
                y += sy
            else:
                x += sx
    return cells


def main():
    s = c.ins.read_snapshot(c.ins.DEFAULT_ROOT, 1, 2, 80, False, None)
    yog = next((m for m in s.level.get('mobs', []) if c.short(m) == 'YogDzewa'), None)
    if yog is None:
        print(json.dumps({'snapshot': s.snapshot_id[:12], 'depth': s.depth,
                          'note': 'No YogDzewa saved yet; no laser result.'}))
        return
    model = c.ins.snapshot_model(s)
    targets = yog.get('targeted_cells', [])
    lines = [ray(yog['pos'], target, s.width, s.height) for target in targets]
    danger = set(cell for line in lines for cell in line)
    xy = lambda cell: [cell % s.width, cell // s.width]
    mobs = {m['pos']: m for m in s.level.get('mobs', [])}
    traps = {t['pos']: t for t in s.level.get('traps', []) if t.get('active', True)}
    passable_ids = {1, 2, 3, 5, 6, 7, 8, 9, 11, 14, 15, 19, 20, 22, 29, 30, 32, 37}
    solid_ids = {4, 5, 10, 12, 13, 16, 21, 23, 25, 26, 27, 28, 31, 33, 34, 35, 36, 38}
    def terrain_solid(x, y):
        return (not (0 < x < s.width-1 and 0 < y < s.height-1)
                or s.level['map'][x+y*s.width] in solid_ids)
    def large_open(cell):
        x, y = xy(cell)
        return not terrain_solid(x,y) and any(
            not terrain_solid(x+dx,y) and not terrain_solid(x,y+dy)
            and not terrain_solid(x+dx,y+dy)
            for dx,dy in [(1,1),(1,-1),(-1,1),(-1,-1)])
    def magic_ray_clear_after_hero_moves(caster, destination):
        for pos in ray(caster['pos'],destination,s.width,s.height)[1:]:
            # A normal step onto a door opens it, and leaving an open door closes it.
            terrain = s.level['map'][pos]
            solid = terrain in solid_ids
            if pos == destination and terrain == 5:
                solid = False
            if pos == s.hero_cell and destination != s.hero_cell and terrain == 6:
                solid = True
            if solid:
                return False
            if pos == destination:
                return True
            if pos in mobs:
                return False
        return False
    def deferred_info(mob):
        buff = next((b for b in mob.get('buffs',[])
                     if c.short(b).endswith('DeferedDamage')),None)
        if buff is None:
            return {'pending_deferred_damage':0,'deferred_next_tick_damage':0,
                    'deferred_next_tick_delta':None,'deferred_next_5_ticks':[],
                    'deferred_ticks_to_death_if_no_new_damage':None}
        pending = int(buff.get('damage',0))
        hp = mob.get('HP',0)
        protected = max(abs(mob['pos']%s.width-yog['pos']%s.width),
                        abs(mob['pos']//s.width-yog['pos']//s.width)) <= 4
        pool = pending
        ticks = []
        death_tick = None
        for idx in range(1,1001):
            nominal = max(1,int(pool*0.1))
            applied = 0 if protected else nominal
            hp -= applied
            pool -= nominal
            if idx <= 5:
                ticks.append({'tick':idx,'nominal_damage':nominal,
                              'applied_if_position_unchanged':applied,
                              'HP_after':max(0,hp),'pool_after':max(0,pool)})
            if hp <= 0:
                death_tick = idx
                break
            if pool <= 0:
                break
        return {'pending_deferred_damage':pending,
                'deferred_next_tick_damage':max(1,int(pending*0.1)),
                'deferred_next_tick_delta':buff.get('time',0)-s.hero.get('time',0),
                'deferred_blocked_by_yog_if_position_unchanged':protected,
                'deferred_next_5_ticks':ticks,
                'deferred_ticks_to_death_if_no_new_damage':death_tick}
    terrain_names = {0:'CHASM', 1:'EMPTY', 2:'GRASS', 4:'WALL', 5:'DOOR', 6:'OPEN_DOOR',
                     7:'ENTRANCE', 8:'EXIT', 9:'EMBERS', 10:'LOCKED_DOOR', 11:'PEDESTAL',
                     12:'WALL_DECO', 13:'BARRICADE', 14:'EMPTY_SP', 15:'HIGH_GRASS',
                     16:'SECRET_DOOR', 17:'SECRET_TRAP', 18:'TRAP', 19:'INACTIVE_TRAP',
                     20:'EMPTY_DECO', 21:'LOCKED_EXIT', 22:'UNLOCKED_EXIT', 24:'WELL',
                     25:'STATUE', 26:'STATUE_SP', 27:'BOOKSHELF', 28:'ALCHEMY',
                     29:'WATER', 30:'FURROWED_GRASS', 31:'CRYSTAL_DOOR',
                     33:'REGION_DECO', 34:'REGION_DECO_ALT'}
    blob_by_cell = {}
    for blob in model['entities']['blobs']:
        for bcell in blob['cells']:
            blob_by_cell.setdefault(bcell['cell'], []).append([blob['class_short'], bcell['volume']])
    hx, hy = xy(s.hero_cell)
    neighbors = []
    for label, dx, dy in [('N',0,-1),('NE',1,-1),('E',1,0),('SE',1,1),
                          ('S',0,1),('SW',-1,1),('W',-1,0),('NW',-1,-1)]:
        x, y = hx+dx, hy+dy
        if not (0 < x < s.width-1 and 0 < y < s.height-1):
            continue
        cell = x+y*s.width
        terrain = s.level['map'][cell]
        m = mobs.get(cell)
        neighbors.append({'direction':label, 'cell':cell, 'xy':[x,y],
                          'on_laser':cell in danger,
                          'terrain':terrain_names.get(terrain, str(terrain)),
                          'ordinary_passable':terrain in passable_ids,
                          'large_open_terrain':large_open(cell),
                          'occupant':None if m is None else [c.short(m),m.get('HP')],
                          'active_trap':None if cell not in traps else c.short(traps[cell]),
                          'blobs':blob_by_cell.get(cell,[]),
                          'bright_ranged_geometry':[
                              {'bright_xy':xy(bright['pos']),
                               'can_shoot_if_position_unchanged':
                                   max(abs(bright['pos']%s.width-x),
                                       abs(bright['pos']//s.width-y)) > 1
                                   and magic_ray_clear_after_hero_moves(bright,cell)}
                              for bright in s.level.get('mobs',[])
                              if c.short(bright).endswith('BrightFist')]})
    result = {
        'snapshot':s.snapshot_id[:12], 'depth':s.depth, 'age_seconds':round(s.age_seconds,2),
        'hero':{'xy':[hx,hy], 'HP':s.hero.get('HP'), 'HT':s.hero.get('HT'),
                'time':s.hero.get('time',0), 'on_laser':s.hero_cell in danger,
                'buffs':[[c.short(b),c.ins.scalar_fields(b)] for b in s.hero.get('buffs',[])]},
        'yog':{'xy':xy(yog['pos']), 'HP':yog.get('HP'), 'phase':yog.get('phase'),
               'time':yog.get('time',0), 'delta_time':yog.get('time',0)-s.hero.get('time',0),
               'ability_cd':yog.get('ability_cd'), 'summon_cd':yog.get('summon_cd')},
        'targeted_cells':targets, 'targeted_xy':[xy(p) for p in targets],
        'lines_xy':[[xy(p) for p in line] for line in lines], 'neighbors':neighbors,
        'fists':[{'class':c.short(m),'xy':xy(m['pos']),'HP':m.get('HP'),
                  **deferred_info(m),
                  'distance_yog':max(abs(m['pos']%s.width-yog['pos']%s.width),
                                     abs(m['pos']//s.width-yog['pos']//s.width)),
                  'ranged_cd':m.get('ranged_cooldown'),
                  'ranged_ray_to_hero_clear':magic_ray_clear_after_hero_moves(m,s.hero_cell),
                  'large_open_terrain':large_open(m['pos']),
                  'tall_grass_count':sum(
                      s.level['map'][m['pos']+dx+dy*s.width] in {15,30}
                      for dx in [-1,0,1] for dy in [-1,0,1]),
                  'tall_grass_within_1':[
                      xy(m['pos']+dx+dy*s.width)
                      for dx in [-1,0,1] for dy in [-1,0,1]
                      if s.level['map'][m['pos']+dx+dy*s.width] in {15,30}],
                  'tall_grass_fire':[
                      {'xy':xy(m['pos']+dx+dy*s.width),
                       'fire_volume':sum(volume for cls,volume in blob_by_cell.get(
                           m['pos']+dx+dy*s.width,[]) if cls == 'Fire')}
                      for dx in [-1,0,1] for dy in [-1,0,1]
                      if s.level['map'][m['pos']+dx+dy*s.width] in {15,30}],
                  'time':m.get('time',0),'buffs':m.get('buffs',[])}
                 for m in s.level.get('mobs',[]) if c.short(m).endswith('Fist')],
        'notes':['Persisted save only; root must verify matching current UI.',
                 'Full WONT_STOP rays; walls, sheep, and other characters do not block Yog laser.',
                 'All rays in a volley damage each character at most once.',
                 'Passable means terrain only, not an instruction to move.',
                 'large_open_terrain follows Level: any one clear 2x2 corner, not a fully clear 3x3 square.',
                 'Nominal Haste3 step is 0.5246245T; two steps are 1.049249T. Buffs can change it.',
                 'A legal move changes position before its time cost advances other actors; one off-ray step can evade an already fixed volley even when its cost exceeds yog.delta_time.',
                 'If an intermediate step is still on a ray, compare the time spent BEFORE the final position change with yog.delta_time, not the total movement cost.',
                 'Deferred forecasts assume no new damage, no healing, and unchanged fist position/protection; Chill does not slow the independent deferred buff ticks.',
                 'Bright ranged geometry uses its current position, hypothetical hero destination, terrain and other characters; it is not saved FOV/AI state and does not predict later movement.',
                 'Rooted hero delays pending Yog fire; clearing roots can re-enable it immediately.']
    }
    print(json.dumps(result, ensure_ascii=False, separators=(',', ':')))


if __name__ == '__main__':
    main()
