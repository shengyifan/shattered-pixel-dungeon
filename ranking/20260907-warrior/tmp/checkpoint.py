#!/usr/bin/env python3
"""Compact, strictly read-only SPD save report. No UI or game-data writes.

Output is a persisted checkpoint, never proof of the current focused UI state.
--wait-new must start before the game lifecycle save is triggered.
"""
from __future__ import annotations
import argparse
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
SOURCE = '/Users/shengyifan/.codex/memories/extensions/ad_hoc/notes/20260824T193329+0800-spd-v338-readonly-inspector.md'
loader = importlib.machinery.SourceFileLoader('spd_readonly_inspector', SOURCE)
spec = importlib.util.spec_from_loader(loader.name, loader)
ins = importlib.util.module_from_spec(spec)
sys.modules[loader.name] = ins
loader.exec_module(ins)

def short(raw):
    return ins.short_class(raw.get('__className')) or '?'

def val(v):
    if isinstance(v, bool): return '1' if v else '0'
    return str(v)

def fields(raw, skip=()):
    return ','.join(f'{k}={val(v)}' for k, v in ins.scalar_fields(raw, skip).items())

def item(raw, game, detailed=False):
    if not isinstance(raw, dict): return '-'
    name = short(raw)
    qty = raw.get('quantity', 1)
    out = name + (f'x{qty}' if qty != 1 else '')
    # Compact notation still discloses every stored level/curse and knowledge flag.
    out += f"[{raw.get('level', 0):+d}{'K' if raw.get('levelKnown') else '?'},c{val(raw.get('cursed', False))}{'K' if raw.get('cursedKnown') else '?'}]"
    if game.get(name+'_label'):
        out += ':'+str(game[name+'_label'])+('K' if game.get(name+'_known') else '?')
    keys = ('charge', 'charges', 'partialCharge', 'chargeCap', 'curCharges', 'maxCharges',
            'volume', 'durability', 'augment', 'curse_infusion_bonus', 'mastery_potion_bonus')
    extras = [f'{k}={val(raw[k])}' for k in keys if k in raw and raw[k] not in (False, 'NONE', 0)]
    for k in ('enchantment','glyph','seal'):
        if isinstance(raw.get(k), dict): extras.append(k+'='+short(raw[k])+'{'+fields(raw[k])+'}')
    if extras: out += '{'+','.join(extras)+'}'
    if detailed: out += ' raw='+json.dumps(raw, ensure_ascii=False, separators=(',',':'))
    return out

def report(s, a):
    h = s.hero
    x, y = s.xy(s.hero_cell)
    delta = (s.level_read.stamp.mtime_ns-s.game_read.stamp.mtime_ns)/1e6
    age = s.age_seconds
    print(f'SAVE slot={s.slot} id={s.snapshot_id[:12]} age={age:.1f}s {s.coherence} delta={delta:.1f}ms '+
          ('STALE>15s ' if age>15 else '')+'persisted; no shared txn/current FOV')
    buffs = h.get('buffs', [])
    hunger = next((b.get('level') for b in buffs if short(b)=='Hunger'), None)
    shield = sum(b.get('shielding',0) for b in buffs)
    print(f"H D{s.depth}/b{s.branch} {h.get('class')}/{h.get('subClass')} p={s.hero_cell}({x},{y}) "+
          f"HP={h.get('HP')}/{h.get('HT')} SH={shield} L={h.get('lvl')} exp={h.get('exp')} STR={h.get('STR')} "+
          f"hunger={hunger}/450 turn={s.game.get('duration')} gold={s.game.get('gold')}")
    print('Buff '+('; '.join(short(b)+'{'+fields(b,('id',))+'}' for b in buffs) or '-'))
    equipped = [(k,h.get(k)) for k in ('weapon','armor','artifact','misc','ring','second_wep') if isinstance(h.get(k),dict)]
    print('EQ '+('; '.join(k+'='+item(i,s.game) for k,i in equipped) or '-'))
    print('Bag '+('; '.join(item(i,s.game) for i in ins.iter_item_tree(h.get('inventory',[]))) or '-'))
    talents = [(k.replace('talents_tier_', 't'),v) for k,v in h.items() if k.startswith('talents_tier_') and v]
    if talents: print('Talents '+json.dumps(dict(talents),ensure_ascii=False,separators=(',',':')))
    near = lambda c: s.valid_cell(c) and ins.chebyshev(s,s.hero_cell,c)<=a.radius
    pos = lambda c: str(c)+'('+','.join(map(str,s.xy(c)))+')'
    mobs = sorted((m for m in s.level.get('mobs',[]) if near(m.get('pos'))), key=lambda m:ins.chebyshev(s,s.hero_cell,m['pos']))
    print(f'Mobs r{a.radius} '+('; '.join(f"{short(m)}@{pos(m['pos'])} HP={m.get('HP')}/{m.get('HT')} {m.get('state')}"+
            (' buffs='+','.join(short(b)+'{'+fields(b,('id',))+'}' for b in m.get('buffs',[])) if m.get('buffs') else '') for m in mobs) or '-'))
    print('Heaps '+('; '.join(f"{pos(m['pos'])}:{m.get('type','HEAP')}="+','.join(item(i,s.game) for i in m.get('items',[])) for m in s.level.get('heaps',[]) if near(m.get('pos'))) or '-'))
    print('Traps '+('; '.join(f"{short(m)}@{pos(m['pos'])} active={val(m.get('active',True))} visible={val(m.get('visible'))}" for m in s.level.get('traps',[]) if near(m.get('pos'))) or '-'))
    hazards = [short(m)+'@'+pos(m['pos']) for m in s.level.get('plants',[]) if near(m.get('pos'))]
    for m in s.level.get('blobs',[]):
        cells = [c for c in ins.blob_cells(s,m) if near(c['cell'])]
        if cells: hazards.append(short(m)+'@'+','.join(pos(c['cell'])+':'+str(c['volume']) for c in cells))
    if hazards: print('Plants/blobs '+'; '.join(hazards))
    print('Stairs '+'; '.join(f"{t.get('type')}@{pos(t['center'])}->D{t.get('dest_depth')}/b{t.get('dest_branch')}" for t in s.level.get('transitions',[])))
    if a.detail:
        matches = [(k,i) for k,i in equipped]+[('bag',i) for i in ins.iter_item_tree(h.get('inventory',[]))]
        for where,i in matches:
            if re.search(a.detail,short(i),re.I): print('Detail '+where+' '+item(i,s.game,True))
    if a.map:
        rendered, conflicts = ins.render_map(s,False)
        print(rendered)
        print('Map: @hero Mmob Uupgrade Sstrength Hhealing Ffood Kkey !potion ?scroll ^trap $gold aarmor wweapon iother; whole persisted map, not FOV')
        if conflicts: print('Layers: '+'; '.join(conflicts))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--slot',default='1')
    p.add_argument('--root',type=Path,default=ins.DEFAULT_ROOT)
    p.add_argument('--radius',type=int,default=8,help='Chebyshev radius; saved entities, not line of sight')
    p.add_argument('--wait-new',action='store_true')
    p.add_argument('--timeout',type=float,default=2)
    p.add_argument('--max-age',type=float,help='Reject checkpoints older than this many seconds')
    p.add_argument('--full',action='store_true',help='Full normalized JSON with equipment/buff fields and all entities')
    p.add_argument('--map',action='store_true',help='Append whole persisted map')
    p.add_argument('--detail',help='Regex of equipment/bag class names to append complete stored item JSON')
    a=p.parse_args()
    try:
        slot=ins.choose_slot(a.root,a.slot)
        s=ins.read_snapshot(a.root,slot,a.timeout,80,a.wait_new,a.max_age)
        if a.full: print(json.dumps(ins.snapshot_model(s),ensure_ascii=False,separators=(',',':')))
        else: report(s,a)
    except ins.InspectError as e:
        print('ERROR '+e.code+': '+str(e),file=sys.stderr)
        return 2
    return 0

if __name__=='__main__': raise SystemExit(main())
