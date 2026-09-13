#!/usr/bin/env python3
"""Bounded public-protocol playthrough client; never reads saves or audit databases.

Every decision uses the current player observation, displayed UI text, and advertised actions.
The initial policy is conservative Warrior exploration, not a claim of an autonomous clear.
"""
import argparse
from collections import Counter, deque
from datetime import datetime, timezone
import json
import re
from pathlib import Path
import shutil
import sys
import time
import uuid

from machine_smoke import Client
import protocol3


ROOT = Path(__file__).resolve().parents[4]
PLAYTHROUGHS = ROOT / "desktop-control" / "build" / "playthroughs"
WALKABLE = {1, 2, 3, 5, 6, 9, 11, 14, 15, 19, 20, 29, 30, 32}
STAIRS_DOWN = {8, 22}
STAIRS_UP = {7, 37}
DOORS = {5, 6}
HERO_NAMES = {
    "warrior": ("warrior", "战士"), "mage": ("mage", "法师"),
    "rogue": ("rogue", "盗贼"), "huntress": ("huntress", "女猎手"),
    "duelist": ("duelist", "决斗家"), "cleric": ("cleric", "牧师"),
}
FRIENDLY_NAMES = ("sad ghost", "悲伤幽灵", "悲伤的幽灵", "shopkeeper", "店主", "商人",
                  "wandmaker", "老杖匠", "blacksmith", "巨魔铁匠", "ambitious imp", "野心勃勃的小恶魔",
                  "rat king", "鼠王", "ward", "哨卫", "lotus", "莲花", "spirit hawk", "灵鹰")
FIRST_REGION_HOSTILES = ("rat", "老鼠", "蛇", "snake", "gnoll", "豺狼", "crab", "螃蟹",
                         "slime", "史莱姆", "swarm", "蝇群", "fly", "mimic", "宝箱怪",
                         "wraith", "怨灵", "ooze", "腐蚀软泥", "goo", "粘咕")
OPTIONAL_HIGH_RISK = ("piranha", "食人鱼", "statue", "雕像", "mimic", "宝箱怪", "fetid rat",
                      "gnoll trickster", "gnoll exile", "great crab", "red sentry", "wraith", "臭鼠", "豺狼诡术师", "豺狼流亡者", "巨型螃蟹", "红色哨卫", "怨灵")
CONFIRMED_WEAK_NAMES = {"rat", "marsupial rat", "albino rat", "sewer snake", "gnoll scout", "老鼠", "白化老鼠", "下水道蛇", "豺狼斥候"}
DAMAGE_DEBUFFS = ("burning", "poison", "bleed", "corros", "ooze", "caustic", "燃烧", "中毒", "流血", "腐蚀", "淤泥")


class Checkpoint(Exception):
    pass


def normalized(text):
    return str(text or "").strip().lower()


def contains(text, words):
    return any(word in normalized(text) for word in words)


def text_of(state):
    return "\n".join(str(n.get("text", n.get("label", "")))
                     for n in state.get("observation", {}).get("ui", {}).get("controls", []))


def resource_selector_kind(state):
    text = normalized(text_of(state))
    if state.get("phase") != "awaiting_input":
        return None
    if "upgrade an item" in text:
        return "upgrade"
    if "identify an item" in text or "choose an item to identify" in text:
        return "identify"
    return None


def available(state, action):
    return any(a.get("action") == action for a in state.get("actions", []))


def activation(state, words, exact=False):
    choices = [a for a in state.get("actions", []) if a.get("action") == "ui.activate"]
    for word in words:
        for action in choices:
            label = normalized(action.get("label"))
            if (label == word if exact else word in label):
                return action
    return None


def neighbours(cell, width, height):
    x, y = cell % width, cell // width
    for dx, dy in ((0, -1), (1, 0), (0, 1), (-1, 0), (1, -1), (1, 1), (-1, 1), (-1, -1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < width and 0 <= ny < height:
            yield ny * width + nx


def distance(a, b, width):
    return max(abs(a % width - b % width), abs(a // width - b // width))


def paths_from(start, cells, width, height, blocked):
    """Only observed, safe terrain is routable; unknown cells never enter this graph."""
    paths = {start: []}
    queue = deque([start])
    while queue:
        current = queue.popleft()
        for nxt in neighbours(current, width, height):
            if nxt in paths or nxt in blocked or nxt not in cells:
                continue
            if cells[nxt].get("terrain") not in WALKABLE:
                continue
            # Avoid diagonal squeezing between two observed solid cells.
            if nxt % width != current % width and nxt // width != current // width:
                sides = (current // width * width + nxt % width, nxt // width * width + current % width)
                if all(cells.get(s, {}).get("terrain") not in WALKABLE for s in sides):
                    continue
            paths[nxt] = paths[current] + [nxt]
            queue.append(nxt)
    return paths


def optional_threat_zone(entities, cells, width):
    threats = [e for e in entities if e.get("kind") == "character" and contains(e.get("name"), OPTIONAL_HIGH_RISK)]
    return {cell for cell in cells if any(distance(cell, e["cell"], width) <= 2 for e in threats)}


def visible_health_order(entity, observation):
    """Compare only already-rendered health bars; an absent bar supplies no HP fact."""
    bars = [n for n in observation.get("ui", {}).get("controls", [])
            if n.get("role") == "health_bar" and n.get("cell") == entity["cell"]
            and n.get("measurement") == "rendered_pixels" and n.get("total_pixels", 0) > 0]
    fraction = min((n["health_pixels"] / n["total_pixels"] for n in bars), default=1)
    return fraction, not bool(bars), entity["cell"]


def creature_kind(entity):
    name = normalized(entity.get("name"))
    if contains(name, OPTIONAL_HIGH_RISK):
        return "optional_risk"
    if name in CONFIRMED_WEAK_NAMES and entity.get("context_action") == "attack" and not entity.get("buffs"):
        return "confirmed_weak"
    if name in CONFIRMED_WEAK_NAMES and entity.get("context_action") == "attack" and entity.get("buffs") \
            and all(normalized(b.get("name")) == "amok" for b in entity["buffs"]):
        return "controlled_amok"
    if name == "swarm of flies" and entity.get("context_action") == "attack" and not entity.get("buffs"):
        return "split_swarm"
    if name == "sewer crab" and entity.get("context_action") == "attack" and not entity.get("buffs"):
        return "fast_armored"
    if name in FRIENDLY_NAMES and entity.get("context_action") == "interact":
        return "known_friendly"
    return "unknown"


def ordinary_weak_description(name, description):
    text = normalized(description)
    return (contains(name, ("rat", "snake", "gnoll", "鼠", "蛇", "豺狼"))
            and contains(text, ("rather weak", "weakest", "not very dangerous", "普通", "弱小"))
            and not contains(text, ("immune", "invulnerable", "poison", "corros", "disintegrat", "paralys",
                                    "extremely fast", "reflect", "powerful", "有毒", "免疫", "腐蚀")))


def explicitly_optional_description(description):
    return contains(description, ("isn't moving to attack", "could just let it pass", "will not attack unless",
                                  "won't attack unless", "不会主动攻击", "可以放它", "可以让它离开"))


def associated_sentry_floor(sentries, cells, width, height):
    """Infer only nearby connected visible floor markings, never private room bounds."""
    marked = {cell for cell, data in cells.items() if data.get("terrain") == 14}
    seeds = {cell for cell in marked if any(distance(cell, sentry, width) <= 2 for sentry in sentries)}
    region, queue = set(seeds), deque(seeds)
    while queue:
        for cell in neighbours(queue.popleft(), width, height):
            if cell in marked and cell not in region:
                region.add(cell)
                queue.append(cell)
    return region


def outcome_of(state):
    """Only explicit protocol settlement is authoritative; depth/HP/items never imply a win."""
    outcome = state.get("run_outcome")
    if not isinstance(outcome, dict):
        return None
    if str(outcome.get("scope_id", "")).startswith("run:") and outcome.get("scope_id") == state.get("scope_id") and outcome.get("result") in {"won", "lost"}:
        return outcome["result"]
    return None


class PublicClient(Client):
    def __init__(self, command, profile, public_log, maximum):
        self.public_log = public_log
        self.maximum = maximum
        self.action_count = 0
        self.last_state = None
        self.last_action_executed = False
        self.policy = None
        self.max_observed_health_loss = 0
        self.last_health_loss = 0
        self.last_action_name = None
        self.last_action_reason = None
        self.last_operation_response = None
        self.last_action_outcome = None
        self.cleanup = False
        super().__init__(command, profile)

    def log(self, kind, **fields):
        record = {"kind": kind, "at": datetime.now(timezone.utc).isoformat(), **fields}
        self.public_log.write(json.dumps(record, ensure_ascii=False) + "\n")
        self.public_log.flush()

    def request(self, op, args=None, request_id=None, scope=None, version=None):
        if op == "action.execute":
            if self.action_count >= self.maximum:
                raise Checkpoint("maximum_action_requests_reached")
            self.action_count += 1
        request_id = request_id or str(uuid.uuid4())
        self.log("request", request=protocol3.request(op, args, request_id, scope or self.scope, version or self.version),
                 action_number=self.action_count, cleanup=self.cleanup)
        response = super().request(op, args, request_id, scope, version)
        self.log("response", response=self.last_wire_response)
        if op == "action.execute":
            self.last_operation_response = response
        data = response.get("result")
        if response.get("ok") and isinstance(data, dict) and "observation" in data:
            self.last_state = data
            current_file = self.profile / "current-state-public.json"
            staged = self.profile / "current-state-public.json.tmp"
            staged.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            staged.replace(current_file)
        return response

    def bounded_action(self, state, reason, action, **args):
        if not available(state, action):
            raise Checkpoint("missing_public_action:" + action)
        self.log("decision", reason=reason, action=action, args=args,
                 observed_hero=state.get("observation", {}).get("hero"))
        self.last_action_executed = False
        result = self.request("action.execute", {"action": action, **args})
        self.last_action_outcome = result
        if not result.get("ok"):
            code = result.get("error", {}).get("code")
            if code == "STALE_STATE":
                return self.state()
            raise Checkpoint("action_rejected:" + str(code))
        self.last_action_executed = True
        if action == "app.quit":
            # Successful quit closes the pipe; another state.get would falsely report a shutdown failure.
            return result.get("result") or state
        if result.get("status") == "in_progress":
            original_id, original_scope = result["id"], result["scope_id"]
            previous_hp = state.get("observation", {}).get("hero", {}).get("hp")
            activity_loss = 0
            started = time.monotonic()
            for _ in range(150):
                record = self.request("request.get", {"target_id": original_id}, scope=original_scope)
                request = record.get("result") or {}
                if request.get("status") not in {"EXECUTING", "RECEIVED"}:
                    detail = self.request("request.get", {"target_id": original_id, "get": ["reply"]}, scope=original_scope)
                    assert detail.get("ok"), detail
                    request = detail["result"]
                    settled = request.get("response") or {}
                    self.last_action_outcome = settled
                    if not settled.get("ok"):
                        raise Checkpoint("operation_settlement_failed")
                    break
                progress = self.state()
                observed = progress.get("observation", {})
                current_hero = observed.get("hero") or {}
                if current_hero and previous_hp is not None:
                    activity_loss = max(activity_loss, max(0, previous_hp - current_hero["hp"]))
                    previous_hp = current_hero["hp"]
                self.log("activity_progress", target_id=original_id, phase=progress.get("phase"),
                         hero=current_hero, actions=progress.get("actions", []))
                threats = [e for e in observed.get("visible_entities", []) if e.get("kind") == "character"
                           and creature_kind(e) != "known_friendly"]
                buffs = " ".join(b.get("name", "") for b in current_hero.get("buffs", []))
                unsafe = activity_loss > 0 or threats or contains(buffs, DAMAGE_DEBUFFS + ("starving", "极度饥饿"))
                overdue = time.monotonic() - started > 8
                if progress.get("phase") == "continuous_activity" and (unsafe or overdue):
                    cancel = next((a for a in progress.get("actions", []) if a.get("action") == "action.cancel"
                                   and a.get("target_id") == original_id), None)
                    if not cancel:
                        raise Checkpoint("continuous_activity_has_no_advertised_cancel")
                    self.log("decision", reason="cancel_continuous_action_after_public_risk_or_time_bound",
                             target_id=original_id, unsafe=bool(unsafe), overdue=overdue)
                    cancellation = self.request("action.execute", {"action": "action.cancel", "target_id": original_id},
                                                scope=original_scope, version=progress.get("state_version"))
                    if not cancellation.get("ok"):
                        terminal = self.request("request.get", {"target_id": original_id}, scope=original_scope)
                        if terminal.get("result", {}).get("status") in {"EXECUTING", "RECEIVED"}:
                            raise Checkpoint("advertised_activity_cancel_rejected")
                time.sleep(0.1)
            else:
                raise Checkpoint("finite_action_did_not_settle")
            self.last_health_loss = activity_loss
        else:
            self.last_health_loss = 0
        after = self.state()
        before_hero = state.get("observation", {}).get("hero") or {}
        after_hero = after.get("observation", {}).get("hero") or {}
        if before_hero and after_hero and state.get("scope_id") == after.get("scope_id"):
            loss = max(0, before_hero.get("hp", 0) - after_hero.get("hp", 0))
            self.last_health_loss = max(self.last_health_loss, loss)
            self.max_observed_health_loss = max(self.max_observed_health_loss, loss)
        self.last_action_name = action
        self.last_action_reason = reason
        self.max_observed_health_loss = max(self.max_observed_health_loss, self.last_health_loss)
        return after


def complete_tutorial(client, state):
    if available(state, "inventory.open"):
        return state
    book = next((e for e in state.get("observation", {}).get("visible_entities", [])
                 if contains(e.get("item", {}).get("name"), ("tome of dungeon mastery", "guidebook", "指南", "地牢宝典"))), None)
    if not book:
        raise Checkpoint("tutorial_book_missing_from_public_observation")
    state = client.bounded_action(state, "normal_tutorial_book_pickup", "cell.select", cell=book["cell"], mode="act")
    state = click_label(client, state, ("journal", "日志"), "normal_tutorial_journal_open")
    state = close_windows(client, state)
    for _ in range(40):
        if available(state, "inventory.open"):
            return state
        time.sleep(0.05)
        state = client.state()
    raise Checkpoint("tutorial_controls_not_yet_available")


def reach_game(client, hero_class, resume):
    selected = False
    revealed = set()
    for _ in range(35):
        state = client.state()
        scene = state.get("observation", {}).get("scene")
        if scene == "game":
            actual = state["observation"].get("hero", {}).get("class")
            if actual != hero_class:
                raise Checkpoint(f"requested_hero_unavailable:requested={hero_class},actual={actual}")
            return state
        if client.action_count >= client.maximum - 6:
            raise Checkpoint("menu_action_budget")
        if resume and scene == "start" and not state.get("observation", {}).get("ui", {}).get("modal"):
            slots = [a for a in state.get("actions", []) if a.get("action") == "ui.activate"
                     and normalized(a.get("label")).split("\n")[0] in HERO_NAMES[hero_class]
                     and "\n" in str(a.get("label", ""))]
            if len(slots) != 1:
                raise Checkpoint("resume_slot_missing_or_ambiguous_in_public_menu")
            client.bounded_action(state, "resume_existing_public_save_slot", "ui.activate", control=slots[0]["control"])
            continue
        if resume and scene == "hero_select":
            raise Checkpoint("resume_unexpected_new_game_flow")
        if scene == "hero_select" and not selected:
            choice = activation(state, HERO_NAMES[hero_class], exact=True)
            if choice:
                state = client.bounded_action(state, "select_requested_public_hero", "ui.activate", control=choice["control"])
                selected = client.last_action_executed
                if contains(text_of(state), ("unlock", "解锁")) and not activation(state, ("enter", "进入地牢")):
                    raise Checkpoint("hero_locked_follow_normal_unlock_requirements")
                continue
        order = ("continue", "继续", "enter the dungeon", "enter", "进入地牢", "play", "开始游戏")
        order += ("continue", "继续游戏") if resume else ("new game", "新游戏", "start", "开始")
        choice = activation(state, order)
        if choice:
            client.bounded_action(state, "normal_menu_flow", "ui.activate", control=choice["control"])
        elif available(state, "ui.reveal") and scene not in revealed:
            client.bounded_action(state, "reveal_scene_controls_through_advertised_action", "ui.reveal")
            revealed.add(scene)
        else:
            raise Checkpoint("unrecognized_menu:" + str(scene))
    raise Checkpoint("menu_flow_limit")


def use_item(client, state, item, verb, reason):
    if client.maximum - client.action_count < 9:
        raise Checkpoint("reserve_shutdown_budget_before_item_choice")
    opened = open_inventory_item(client, state, item, reason + ":open")
    button = activation(opened, verb, exact=True)
    if button is None:
        client.bounded_action(opened, "close_unsupported_item_menu", "ui.back")
        raise Checkpoint("item_action_missing:" + normalized(item.get("name")))
    return client.bounded_action(opened, reason, "ui.activate", control=button["control"])


def click_label(client, state, words, reason, exact=True):
    for _ in range(4):
        button = activation(state, words, exact=exact)
        if not button:
            raise Checkpoint("missing_visible_control:" + reason)
        state = client.bounded_action(state, reason, "ui.activate", control=button["control"])
        if client.last_action_executed:
            return state
    raise Checkpoint("repeated_stale_control:" + reason)


def open_inventory_item(client, state, item, reason):
    for _ in range(4):
        current = next((i for i in state.get("observation", {}).get("inventory", [])
                        if i.get("locator") == item.get("locator") and i.get("name") == item.get("name")), None)
        if not current:
            raise Checkpoint("inventory_changed_before_open")
        state = client.bounded_action(state, reason, "inventory.open", locator=current["locator"])
        if client.last_action_executed:
            return state
    raise Checkpoint("repeated_stale_inventory")


def close_windows(client, state):
    for _ in range(4):
        if state.get("phase") == "player_ready":
            return state
        if not available(state, "ui.back"):
            raise Checkpoint("cannot_close_inspection_through_public_action")
        state = client.bounded_action(state, "close_finished_inspection", "ui.back")
    raise Checkpoint("inspection_did_not_close")


def equipment_stats(state):
    text = text_of(state).replace("_", "")
    match = re.search(r"tier[- ](\d+)\s+(armor|melee weapon)\s+(?:blocks|deals)\s+([\d.]+)-([\d.]+)\s+damage\s+and requires\s+(\d+)\s+strength", text, re.I)
    if not match:
        return None
    tier, kind, minimum, maximum, strength = match.groups()
    return {"tier": int(tier), "kind": "armor" if kind.lower() == "armor" else "weapon",
            "minimum": float(minimum), "maximum": float(maximum), "strength": int(strength),
            "typical": "typically this" in text.lower(), "displayed_text": text}


class Policy:
    def __init__(self, maximum_depth, allow_rest=False):
        self.maximum_depth = maximum_depth
        self.allow_rest = allow_rest
        self.visits = Counter()
        self.blocked = set()
        self.last_move = None
        self.inspected = set()
        self.snake_escape = None
        self.equipment_cache = {}
        self.container_attempts = set()
        self.talent_attempt = None
        self.waterskin_attempt = None
        self.searches = Counter()
        self.searched_walls = set()
        self.remembered_hazards = set()
        self.avoided_cells = set()
        self.safe_cells = set()
        self.sentries = set()
        self.unsafe_rest_cells = set()
        self.learned_weak = set()
        self.learned_optional = set()
        self.verified_neutral = set()
        self.reviewed_optional = set()
        self.optional_positions = {}
        self.two_weak_engagement = False
        self.regroup_steps = 0
        self.recent_navigation = deque(maxlen=16)
        self.pickup_attempts = Counter()
        self.swarm_waits = Counter()
        self.swarm_lures = Counter()
        self.last_character_count = None

    def restore_public_history(self, attempt, scope):
        previous = attempt / "checkpoint-game-public.json"
        if not previous.exists():
            previous = attempt / "checkpoint-public.json"
        if previous.exists():
            saved = json.loads(previous.read_text())
            if saved.get("scope_id") == scope and saved.get("strategy_state"):
                data = saved["strategy_state"]
                self.visits.update({(d, c): n for d, c, n in data.get("visits", [])})
                self.blocked.update(tuple(x) for x in data.get("blocked", []))
                self.equipment_cache.update(data.get("equipment_cache", {}))
                self.container_attempts.update(tuple(x) for x in data.get("container_attempts", []))
                self.avoided_cells.update(tuple(x) for x in data.get("avoided_cells", []))
                self.safe_cells.update(tuple(x) for x in data.get("safe_cells", []))
                self.sentries.update(tuple(x) for x in data.get("sentries", []))
                self.unsafe_rest_cells.update(tuple(x) for x in data.get("unsafe_rest_cells", []))
                self.learned_weak.update(data.get("learned_weak", []))
                self.learned_optional.update(data.get("learned_optional", []))
                self.verified_neutral.update(data.get("verified_neutral", []))
                self.reviewed_optional.update(data.get("reviewed_optional", []))
                self.optional_positions.update({(d, c): (name, radius) for d, c, name, radius in data.get("optional_positions", [])})
                self.searched_walls.update(tuple(x) for x in data.get("searched_walls", []))
                self.remembered_hazards.update(tuple(x) for x in data.get("remembered_hazards", []))
                if data.get("policy_memory_schema", 1) < 2:
                    self.restore_optional_history(attempt, scope)
                return
        # Older checkpoints can be reconstructed from our own public response log only.
        last = None
        for log in sorted(attempt.glob("strategy-public-*.ndjson"), key=lambda p: p.stat().st_mtime):
            with log.open(encoding="utf-8") as source:
                for line in source:
                    record = json.loads(line)
                    if record.get("kind") != "response":
                        continue
                    wire_response = record.get("response", {})
                    decoded = protocol3.response(wire_response) if wire_response.get("v") == 3 else wire_response
                    data = decoded.get("result", {})
                    if not isinstance(data, dict) or data.get("scope_id") != scope:
                        continue
                    hero = data.get("observation", {}).get("hero")
                    if hero:
                        cell = (hero["depth"], hero["cell"])
                        if cell != last:
                            self.visits[cell] += 1
                        last = cell
        self.restore_optional_history(attempt, scope)

    def restore_optional_history(self, attempt, scope):
        """Migrate only our public observations, replacing old permanent patrol avoidance."""
        legacy_regions = set()
        for log in sorted(attempt.glob("strategy-public-*.ndjson"), key=lambda p: p.stat().st_mtime):
            observed_scope = None
            with log.open(encoding="utf-8") as source:
                for line in source:
                    record = json.loads(line)
                    if record.get("kind") == "unknown_creature_public_inspection" and observed_scope == scope \
                            and explicitly_optional_description(record.get("displayed_text")):
                        self.verified_neutral.add(normalized(record["creature"]["name"]))
                        self.reviewed_optional.add(normalized(record["creature"]["name"]))
                        continue
                    wire_response = record.get("response", {})
                    decoded = protocol3.response(wire_response) if wire_response.get("v") == 3 else wire_response
                    data = decoded.get("result", {})
                    if isinstance(data, dict) and "observation" in data:
                        observed_scope = data.get("scope_id")
                    if not isinstance(data, dict) or data.get("scope_id") != scope:
                        continue
                    observation = data.get("observation", {})
                    hero, dungeon_map = observation.get("hero"), observation.get("map")
                    if not hero or not dungeon_map:
                        continue
                    cells = {c["cell"]: c for c in dungeon_map["cells"]}
                    optional = [e for e in observation.get("visible_entities", []) if e.get("kind") == "character"
                                and (creature_kind(e) == "optional_risk" or normalized(e.get("name")) in self.learned_optional)]
                    legacy_regions.update((hero["depth"], c) for c in cells
                                          if any(distance(c, e["cell"], dungeon_map["width"]) <= 2 for e in optional))
                    self.remember_optional_positions(hero["depth"], cells, optional, dungeon_map["width"])
        self.avoided_cells.difference_update(legacy_regions)
        self.avoided_cells.update(self.unsafe_rest_cells)

    def state_dict(self):
        return {"policy_memory_schema": 2,
                "visits": [[depth, cell, count] for (depth, cell), count in self.visits.items()],
                "blocked": [list(cell) for cell in sorted(self.blocked)],
                "equipment_cache": self.equipment_cache,
                "avoided_cells": [list(x) for x in sorted(self.avoided_cells)],
                "safe_cells": [list(x) for x in sorted(self.safe_cells)],
                "sentries": [list(x) for x in sorted(self.sentries)],
                "unsafe_rest_cells": [list(x) for x in sorted(self.unsafe_rest_cells)],
                "learned_weak": sorted(self.learned_weak),
                "learned_optional": sorted(self.learned_optional),
                "verified_neutral": sorted(self.verified_neutral),
                "reviewed_optional": sorted(self.reviewed_optional),
                "optional_positions": [[depth, cell, name, radius] for (depth, cell), (name, radius) in sorted(self.optional_positions.items())],
                "searched_walls": [list(x) for x in sorted(self.searched_walls)],
                "remembered_hazards": [list(x) for x in sorted(self.remembered_hazards)],
                "container_attempts": [list(x) for x in sorted(self.container_attempts)]}

    @staticmethod
    def gear_key(item, hero):
        return json.dumps([item.get("name"), item.get("level_known"), item.get("level"),
                           item.get("curse_known"), item.get("cursed"), hero.get("level"),
                           hero.get("strength")], ensure_ascii=False)

    def remember_visible_effects(self, depth, cells):
        for cell, data in cells.items():
            if data.get("visibility") != "visible":
                continue
            if data.get("environment"):
                self.remembered_hazards.add((depth, cell))
            else:
                self.remembered_hazards.discard((depth, cell))
        return {cell for floor, cell in self.remembered_hazards if floor == depth}

    def neutral_passage(self, entity):
        return normalized(entity.get("name")) in self.verified_neutral and not entity.get("buffs") \
                and entity.get("emotion") in {None, "sleeping"}

    def remember_optional_positions(self, depth, cells, optional, width):
        current = {e["cell"]: e for e in optional}
        for (floor, cell), (name, _) in list(self.optional_positions.items()):
            if floor == depth and cells.get(cell, {}).get("visibility") == "visible" \
                    and normalized(current.get(cell, {}).get("name")) != name:
                del self.optional_positions[(floor, cell)]
        for entity in optional:
            self.optional_positions[(depth, entity["cell"])] = (normalized(entity.get("name")), 0 if self.neutral_passage(entity) else 2)
        return {cell for cell in cells if any(floor == depth and distance(cell, old, width) <= radius
                    for (floor, old), (_, radius) in self.optional_positions.items())}

    def allocate_talent(self, client, state):
        hero = state["observation"]["hero"]
        if hero.get("class") != "warrior":
            return None
        talents = [t for t in hero.get("talents", []) if t.get("tier") == 1]
        spent = sum(t["points"] for t in talents)
        signature = (hero["level"], spent)
        if spent >= min(hero["level"] - 1, 5) or signature == self.talent_attempt:
            return None
        if client.maximum - client.action_count < 14:
            raise Checkpoint("talent_choice_checkpoint_before_budget")
        self.talent_attempt = signature
        chosen = None
        for preferred in ("iron will", "钢铁意志", "hearty meal", "丰盛一餐", "veteran's intuition", "老兵直觉"):
            chosen = next((t for t in talents if normalized(t["name"]) == preferred and t["points"] < 2), None)
            if chosen:
                break
        if not chosen:
            return None
        state = click_label(client, state, ("hero info", "英雄信息"), "open_hero_for_available_talent")
        if not activation(state, (normalized(chosen["name"]),), exact=True):
            state = click_label(client, state, ("talents", "天赋"), "open_talents_tab")
        state = click_label(client, state, (normalized(chosen["name"]),), "inspect_chosen_talent")
        state = click_label(client, state, ("upgrade talent", "升级天赋"), "spend_visible_talent_point")
        return close_windows(client, state)

    def inspect_and_equip(self, client, state):
        hero = state["observation"]["hero"]
        inventory = state["observation"]["inventory"]
        for item in inventory:
            if not (item["locator"] in {"equipment.weapon", "equipment.armor"}
                    or contains(item["name"], ("sword", "dagger", "spear", "mace", "hammer", "axe", "flail",
                                               "staff", "whip", "blade", "gauntlet", "shield", "armor", "mail", "剑", "甲", "矛", "锤"))):
                continue
            key = self.gear_key(item, hero)
            if key in self.equipment_cache:
                continue
            if client.maximum - client.action_count < 16:
                raise Checkpoint("equipment_inspection_checkpoint_before_budget")
            opened = open_inventory_item(client, state, item, "inspect_actual_equipment_description")
            stats = equipment_stats(opened)
            self.equipment_cache[key] = stats
            client.log("equipment_evidence", item=item, displayed_stats=stats)
            if stats and not item.get("equipped") and not item.get("cursed") and stats["strength"] <= hero["strength"]:
                existing = next((i for i in inventory if i["locator"] == "equipment." + stats["kind"]), None)
                current_stats = self.equipment_cache.get(self.gear_key(existing, hero)) if existing else None
                if current_stats and stats["maximum"] > current_stats["maximum"]:
                    if stats["kind"] == "armor" and contains(current_stats["displayed_text"], ("seal is affixed", "seal attached", "seal", "纹章")):
                        # Seal transfer is a separate meaningful choice; handle explicitly, never discard it.
                        state = close_windows(client, opened)
                        old_menu = open_inventory_item(client, state, existing, "detach_warrior_seal_before_armor_change")
                        detach = activation(old_menu, ("detach", "拆卸"))
                        if not detach:
                            raise Checkpoint("better_armor_requires_explicit_seal_transfer")
                        state = client.bounded_action(old_menu, "detach_visible_seal", "ui.activate", control=detach["control"])
                        state = close_windows(client, state)
                        candidate = next((i for i in state["observation"]["inventory"] if i["name"] == item["name"] and not i["equipped"]), None)
                        if not candidate:
                            raise Checkpoint("armor_changed_during_seal_transfer")
                        opened = open_inventory_item(client, state, candidate, "open_better_armor_after_detach")
                    reason = "equip_observed_upgrade" if item.get("curse_known") else "equip_unidentified_upgrade_with_recorded_curse_risk"
                    state = click_label(client, opened, ("equip", "装备"), reason)
                    state = close_windows(client, state)
                    if stats["kind"] == "armor":
                        seal = next((i for i in state["observation"]["inventory"] if contains(i["name"], ("broken seal", "破损纹章", "破损的纹章"))), None)
                        if seal:
                            seal_menu = open_inventory_item(client, state, seal, "reattach_warrior_seal")
                            state = click_label(client, seal_menu, ("affix", "粘贴", "贴附"), "choose_seal_affix", exact=False)
                            state = self.choose_item(client, state, item["name"], "affix_seal_to_new_equipped_armor")
                            state = close_windows(client, state)
                    return state
            return close_windows(client, opened)
        return None

    @staticmethod
    def choose_item(client, state, name, reason):
        choices = [a for a in state.get("actions", []) if a.get("action") == "ui.activate"
                   and normalized(a.get("label")) == normalized(name)]
        if len(choices) != 1:
            raise Checkpoint("ambiguous_or_missing_item_choice:" + normalized(name))
        return client.bounded_action(state, reason, "ui.activate", control=choices[0]["control"])

    def upgrade_equipment(self, client, state):
        hero = state["observation"]["hero"]
        inventory = state["observation"]["inventory"]
        scroll = next((i for i in inventory if i.get("type_known") and contains(i["name"], ("scroll of upgrade", "升级卷轴"))), None)
        if not scroll or client.maximum - client.action_count < 13:
            return None
        armor = next((i for i in inventory if i["locator"] == "equipment.armor"), None)
        weapon = next((i for i in inventory if i["locator"] == "equipment.weapon"), None)
        target = armor if armor and armor.get("level") == 0 and hero["class"] == "warrior" else None
        if target is None and weapon:
            stats = self.equipment_cache.get(self.gear_key(weapon, hero)) or {}
            if (stats.get("tier", 0) >= 3 and (weapon.get("level") or 0) < 3
                    or hero["depth"] >= 3 and weapon.get("level") == 0):
                target = weapon
        if target is None:
            return None
        state = use_item(client, state, scroll, ("read", "阅读"), "read_known_upgrade_scroll")
        state = self.choose_item(client, state, target["name"], "upgrade_selected_equipment_from_public_item_picker")
        return close_windows(client, state)

    def decide(self, client, state):
        observation = state.get("observation", {})
        if outcome_of(state):
            raise Checkpoint("official_outcome:" + outcome_of(state))
        if observation.get("scene") != "game":
            raise Checkpoint("non_game_scene_requires_review:" + str(observation.get("scene")))
        if state.get("phase") != "player_ready":
            raise Checkpoint("modal_or_unresolved_choice_requires_review")
        hero = observation.get("hero") or {}
        if not hero or hero.get("hp", 0) <= 0:
            raise Checkpoint("hero_not_actionable_no_victory_inference")
        depth, pos = hero["depth"], hero["cell"]
        if depth > self.maximum_depth:
            raise Checkpoint("depth_checkpoint")
        if depth % 5 == 0:
            raise Checkpoint("boss_floor_decision_checkpoint")
        dungeon_map = observation.get("map") or {}
        width, height = dungeon_map["width"], dungeon_map["height"]
        cells = {cell["cell"]: cell for cell in dungeon_map.get("cells", [])}
        hazard_cells = self.remember_visible_effects(depth, cells)
        entities = observation.get("visible_entities", [])
        inventory = observation.get("inventory", [])
        current = (depth, pos)
        if self.last_move:
            previous, intended = self.last_move
            if previous == current and intended != current:
                self.blocked.add(intended)
            self.last_move = None
        self.visits[current] += 1
        characters = [e for e in entities if e.get("kind") == "character"]
        previous_character_count = self.last_character_count
        self.last_character_count = len(characters)
        optional = [e for e in characters if creature_kind(e) == "optional_risk"
                    or normalized(e.get("name")) in self.learned_optional]
        controlled = [e for e in characters if creature_kind(e) == "controlled_amok"]
        if client.last_health_loss > 0:
            self.verified_neutral.difference_update(normalized(e.get("name")) for e in optional if distance(pos, e["cell"], width) <= 3)
        self.verified_neutral.difference_update(normalized(e.get("name")) for e in optional
                                               if e.get("buffs") or e.get("emotion") not in {None, "sleeping"})
        optional_cells = self.remember_optional_positions(depth, cells, optional, width)
        active_risks = [e for e in optional if not self.neutral_passage(e)]
        self.sentries.update((depth, e["cell"]) for e in optional if contains(e.get("name"), ("red sentry", "红色哨卫")))
        protected = associated_sentry_floor([c for d, c in self.sentries if d == depth], cells, width, height)
        self.avoided_cells.update((depth, cell) for cell in protected)
        learned = [e for e in characters if normalized(e.get("name")) in self.learned_weak
                   and e.get("context_action") == "attack" and not e.get("buffs")]
        hostiles = [e for e in characters if creature_kind(e) in {"confirmed_weak", "split_swarm", "fast_armored"} or e in learned]
        swarms = [e for e in hostiles if creature_kind(e) == "split_swarm"]
        crabs = [e for e in hostiles if creature_kind(e) == "fast_armored"]
        unknown = [e for e in characters if creature_kind(e) == "unknown" and e not in learned and e not in optional]
        unknown.extend(e for e in optional if normalized(e.get("name")) == "gnoll exile"
                       and normalized(e.get("name")) not in self.reviewed_optional)
        rest_lost_health = client.last_action_name == "rest" and client.last_health_loss > 0
        if rest_lost_health:
            self.unsafe_rest_cells.add(current)
            if not hostiles:
                self.avoided_cells.add(current)
            client.log("dangerous_rest_location", depth=depth, cell=pos, health_lost=client.last_health_loss)
        if unknown:
            creature = min(unknown, key=lambda e: distance(pos, e["cell"], width))
            inspected = client.bounded_action(state, "inspect_unknown_creature_without_advancing_a_turn", "cell.select",
                                               cell=creature["cell"], mode="examine")
            description = text_of(inspected)
            client.log("unknown_creature_public_inspection", creature=creature, displayed_text=description)
            print(json.dumps({"unknown_creature": creature["name"], "cell": creature["cell"],
                              "public_description": description}, ensure_ascii=False), flush=True)
            closed = close_windows(client, inspected)
            if creature in optional:
                self.reviewed_optional.add(normalized(creature["name"]))
            if explicitly_optional_description(description):
                self.learned_optional.add(normalized(creature["name"]))
                self.verified_neutral.add(normalized(creature["name"]))
                client.log("classification", creature=creature["name"], category="optional_risk_from_public_description", evidence=description)
                return closed
            if creature in optional:
                return closed
            if creature.get("context_action") == "attack" and not creature.get("buffs") and ordinary_weak_description(creature["name"], description):
                self.learned_weak.add(normalized(creature["name"]))
                client.log("classification", creature=creature["name"], category="ordinary_weak_from_public_description", evidence=description)
                return closed
            raise Checkpoint("unknown_creature_inspected_for_root_strategy:" + normalized(creature.get("name")))
        adjacent = [e for e in hostiles if distance(pos, e["cell"], width) <= 1]
        if len(adjacent) != 2:
            self.two_weak_engagement = False
            self.regroup_steps = 0
        buff_text = " ".join(b.get("name", "") for b in hero.get("buffs", []))
        harmful = contains(buff_text, DAMAGE_DEBUFFS)
        if not optional and not unknown and not harmful and client.last_health_loss == 0 and cells.get(pos, {}).get("terrain") != 14 and not cells.get(pos, {}).get("environment"):
            self.safe_cells.add(current)
        healing = next((i for i in inventory if i.get("type_known") and contains(i.get("name"),
                       ("potion of healing", "治疗药水"))), None)
        safe_food = any(contains(i.get("name"), ("ration of food", "口粮", "pasty", "肉馅饼", "chargrilled meat", "烤肉"))
                        for i in inventory)
        if contains(buff_text, ("starving", "极度饥饿")) and not safe_food and not healing \
                and hero["hp"] <= hero["max_hp"] * 0.5:
            raise Checkpoint("starving_without_known_safe_food_or_healing_requires_resource_review")
        visible_threat_near = any(distance(pos, e["cell"], width) <= 3 for e in hostiles + controlled) or any(distance(pos, e["cell"], width) <= 2 for e in active_risks)
        if hero["hp"] <= hero["max_hp"] * (0.5 if visible_threat_near else 0.3) and healing:
            return use_item(client, state, healing, ("drink", "饮用"), "heal_using_identified_potion")
        if hero["hp"] <= max(hero["max_hp"] * 0.35, client.max_observed_health_loss + 2) and visible_threat_near:
            raise Checkpoint("low_hp_visible_threat_requires_resource_or_escape_review")
        if pos in protected:
            blocked_escape = {e["cell"] for e in entities}
            blocked_escape.update(c["cell"] for c in cells.values() if c.get("environment"))
            retreat_paths = paths_from(pos, cells, width, height, blocked_escape)
            safe = [(len(path), cell, path) for cell, path in retreat_paths.items() if path and (depth, cell) in self.safe_cells
                    and (depth, cell) not in self.avoided_cells]
            if not safe:
                raise Checkpoint("sentry_area_has_no_known_safe_return_path")
            path = min(safe)[2]
            if hero["hp"] <= max(1, client.max_observed_health_loss) * len(path) + 2:
                raise Checkpoint("sentry_return_path_requires_resource_review")
            return self.move(client, state, depth, pos, path[0], "return_to_known_safe_cell_outside_sentry_markings")
        if rest_lost_health and not hostiles:
            raise Checkpoint("rest_lost_health_stop_before_any_repeat")
        if any(distance(pos, e["cell"], width) <= 2 for e in active_risks):
            forbidden = {e["cell"] for e in entities if e.get("kind") in {"trap", "plant", "character", "container", "object"}}
            escape = [n for n in neighbours(pos, width, height) if cells.get(n, {}).get("terrain") in WALKABLE
                      and n not in forbidden and not cells[n].get("environment")
                      and all(distance(n, e["cell"], width) > 2 for e in active_risks)]
            if escape:
                destination = max(escape, key=lambda n: min(distance(n, e["cell"], width) for e in active_risks))
                return self.move(client, state, depth, pos, destination, "retreat_from_optional_high_risk_target")
            raise Checkpoint("optional_high_risk_target_blocks_safe_retreat")
        if contains(buff_text, ("burning", "燃烧")) and cells.get(pos, {}).get("terrain") != 29:
            danger = {e["cell"] for e in entities if e.get("kind") in {"trap", "plant", "character"}}
            escape_paths = paths_from(pos, cells, width, height, danger)
            water = [(len(path), cell, path) for cell, path in escape_paths.items()
                     if path and cells.get(cell, {}).get("terrain") == 29]
            if water:
                return self.move(client, state, depth, pos, min(water)[2][0], "extinguish_visible_burning_in_observed_water")
        if cells.get(pos, {}).get("environment"):
            if contains(buff_text, ("rooted", "roots", "paraly", "缠绕", "麻痹")):
                raise Checkpoint("immobilized_in_visible_effect_requires_resource_review")
            escape_blocked = {e["cell"] for e in entities}
            escape_paths = paths_from(pos, cells, width, height, escape_blocked)
            destinations = [(len(path), self.visits[(depth, cell)] == 0, cell, path)
                            for cell, path in escape_paths.items() if path and not cells[cell].get("environment")
                            and (depth, cell) not in self.avoided_cells]
            if destinations:
                return self.move(client, state, depth, pos, min(destinations)[3][0], "leave_current_visible_environment_effect")
            raise Checkpoint("visible_effect_has_no_observed_safe_escape")
        if depth >= 3 and not characters and previous_character_count == 1 and not harmful \
                and client.last_action_reason == "melee_attack_visible_adjacent_hostile" \
                and any(not item.get("type_known") and contains(item.get("name"), ("scroll", "卷轴")) for item in inventory):
            raise Checkpoint("safe_post_combat_unknown_resource_review")
        if controlled and min(distance(pos, e["cell"], width) for e in controlled) <= 3:
            occupied = {e["cell"] for e in entities}
            current_distance = min(distance(pos, e["cell"], width) for e in controlled)
            escape = [(min(distance(n, e["cell"], width) for e in controlled + hostiles),
                       cells[n].get("terrain") in DOORS, -self.visits[(depth, n)], n)
                      for n in neighbours(pos, width, height) if cells.get(n, {}).get("terrain") in WALKABLE
                      and n not in occupied and n not in hazard_cells and not cells[n].get("environment")
                      and (depth, n) not in self.avoided_cells
                      and min(distance(n, e["cell"], width) for e in controlled) > current_distance]
            if escape:
                return self.move(client, state, depth, pos, max(escape)[3], "withdraw_from_visible_amok_enemies_while_they_fight")
            raise Checkpoint("controlled_enemies_block_safe_retreat_requires_review")
        single_file_enemies = swarms + crabs
        if single_file_enemies and min(distance(pos, e["cell"], width) for e in single_file_enemies) <= 6:
            occupied = {e["cell"] for e in entities}
            occupied.update(hazard_cells | optional_cells)
            staging_paths = paths_from(pos, cells, width, height, occupied)
            narrow = lambda cell: all(n in cells for n in neighbours(cell, width, height)) \
                    and sum(cells[n].get("terrain") in WALKABLE for n in neighbours(cell, width, height)) <= 2
            if not narrow(pos):
                posts = [(len(path), -min(distance(cell, e["cell"], width) for e in single_file_enemies), cell, path)
                         for cell, path in staging_paths.items() if path and len(path) <= 5 and narrow(cell)
                         and min(distance(cell, e["cell"], width) for e in single_file_enemies) >= 2]
                if posts:
                    reason = "stage_in_single_file_passage_before_physical_swarm_combat" if swarms else "stage_in_single_file_passage_before_fast_crab_combat"
                    return self.move(client, state, depth, pos, min(posts)[3][0], reason)
                raise Checkpoint("special_regular_enemy_has_no_observed_single_file_fighting_position")
            if not adjacent:
                all_sleeping = all(e.get("emotion") == "sleeping" for e in hostiles)
                wait_limit = 2 if all_sleeping else 8
                if self.swarm_waits[current] < wait_limit:
                    self.swarm_waits[current] += 1
                    return client.bounded_action(state, "hold_single_file_position_for_visible_special_enemy", "wait")
                if all_sleeping and self.swarm_lures[current] < 2:
                    door = next((n for n in neighbours(pos, width, height) if cells.get(n, {}).get("terrain") in DOORS
                                 and n not in occupied and min(distance(n, e["cell"], width) for e in single_file_enemies)
                                 < min(distance(pos, e["cell"], width) for e in single_file_enemies)), None)
                    if door is not None:
                        self.swarm_lures[current] += 1
                        return self.move(client, state, depth, pos, door, "briefly_show_at_door_without_attacking_sleeping_swarm")
                raise Checkpoint("swarm_single_file_wait_checkpoint")
        if adjacent:
            if len(adjacent) >= 2:
                occupied = {e["cell"] for e in entities}
                controlled_pair = len(hostiles) == 2 and len(adjacent) == 2 and not optional and not harmful \
                        and all(creature_kind(e) == "confirmed_weak" or e in learned for e in hostiles)
                if controlled_pair and hero["hp"] >= hero["max_hp"] * 0.75 and healing:
                    self.two_weak_engagement = True
                pair_can_fight = controlled_pair and self.two_weak_engagement and hero["hp"] > hero["max_hp"] * 0.5
                retreats = [(sum(distance(n, e["cell"], width) <= 1 for e in hostiles),
                             sum(cells.get(k, {}).get("terrain") in WALKABLE for k in neighbours(n, width, height)), n)
                            for n in neighbours(pos, width, height) if n in cells and cells[n].get("terrain") in WALKABLE
                            and n not in occupied and not cells[n].get("environment") and (depth, n) not in self.avoided_cells]
                retreats = [r for r in retreats if r[0] < len(adjacent)]
                if retreats:
                    return self.move(client, state, depth, pos, min(retreats)[2], "retreat_from_surrounding_regular_enemies")
                if not pair_can_fight:
                    raise Checkpoint("multiple_adjacent_enemies_no_safe_retreat")
                # A short move toward an observed door may initially leave both rats adjacent.
                # Re-evaluate after each step, and never keep circling a doorway indefinitely.
                if self.regroup_steps < 3 and cells.get(pos, {}).get("terrain") not in DOORS:
                    blocked_pair = occupied | hazard_cells | {c for c in cells if cells[c].get("environment")
                                               or (depth, c) in self.avoided_cells}
                    regroup = paths_from(pos, cells, width, height, blocked_pair)
                    doors = [(len(path), cell, path) for cell, path in regroup.items()
                             if 0 < len(path) <= 3 and cells[cell].get("terrain") in DOORS]
                    if doors:
                        self.regroup_steps += 1
                        return self.move(client, state, depth, pos, min(doors)[2][0], "regroup_two_weak_enemies_toward_observed_door")
            enemy = min(adjacent, key=lambda e: visible_health_order(e, observation))
            if contains(enemy.get("name"), ("snake", "蛇")) and cells.get(pos, {}).get("terrain") in DOORS:
                occupied = {e["cell"] for e in entities}
                occupied.update(hazard_cells)
                cross = [n for n in neighbours(pos, width, height) if cells.get(n, {}).get("terrain") in WALKABLE
                         and n not in occupied and not cells[n].get("environment") and (depth, n) not in self.avoided_cells
                         and distance(n, enemy["cell"], width) >= 2]
                if cross:
                    return self.move(client, state, depth, pos, max(cross, key=lambda n: distance(n, enemy["cell"], width)),
                                     "leave_door_tile_to_prepare_snake_surprise_attack")
            if len(hostiles) == 1 and contains(enemy.get("name"), ("snake", "蛇")) \
                    and cells.get(pos, {}).get("terrain") not in DOORS \
                    and cells.get(enemy["cell"], {}).get("terrain") not in DOORS:
                occupied = {e["cell"] for e in entities}
                occupied.update(hazard_cells)
                occupied.update(c for c in cells if cells[c].get("environment") or (depth, c) in self.avoided_cells)
                snake_paths = paths_from(pos, cells, width, height, occupied)
                doors = [(len(path), cell, path) for cell, path in snake_paths.items()
                         if 0 < len(path) <= 5 and cells[cell].get("terrain") in DOORS]
                if doors:
                    path = min(doors)[2]
                    if hero["hp"] > max(1, client.max_observed_health_loss) * len(path) + 2:
                        return self.move(client, state, depth, pos, path[0], "lead_single_snake_to_nearby_observed_door")
            if contains(enemy.get("name"), ("snake", "蛇")) and enemy.get("emotion") != "sleeping" and self.snake_escape != (depth, enemy["cell"]):
                doorway = next((n for n in neighbours(pos, width, height)
                                if cells.get(n, {}).get("terrain") in DOORS and n != enemy["cell"]), None)
                if doorway is not None:
                    self.snake_escape = (depth, enemy["cell"])
                    return self.move(client, state, depth, pos, doorway, "lure_snake_through_observed_door")
            reason = "controlled_focus_attack_two_weak_enemies" if len(adjacent) == 2 else "melee_attack_visible_adjacent_hostile"
            return client.bounded_action(state, reason, "cell.select",
                                         cell=enemy["cell"], mode="act")
        if contains(buff_text, ("starving", "极度饥饿")) and not optional and not any(distance(pos, e["cell"], width) < 5 for e in hostiles + controlled):
            food = next((i for i in inventory if contains(i.get("name"),
                        ("ration of food", "口粮", "pasty", "肉馅饼", "chargrilled meat", "烤肉"))), None)
            if food:
                return use_item(client, state, food, ("eat", "食用"), "eat_when_starving")
        if not hostiles and not controlled:
            strength_potion = next((i for i in inventory if i.get("type_known")
                                    and contains(i["name"], ("potion of strength", "力量药水"))), None)
            if strength_potion:
                return use_item(client, state, strength_potion, ("drink", "饮用"), "drink_identified_strength_potion")
            if hero["hp"] < hero["max_hp"] * 0.7 and self.waterskin_attempt != (depth, hero["hp"]):
                waterskin = next((i for i in inventory if contains(i["name"], ("waterskin", "水袋"))), None)
                if waterskin:
                    self.waterskin_attempt = (depth, hero["hp"])
                    opened = open_inventory_item(client, state, waterskin, "check_visible_waterskin_drink_action")
                    drink = activation(opened, ("drink", "饮用"), exact=True)
                    if drink:
                        return client.bounded_action(opened, "drink_available_healing_water", "ui.activate", control=drink["control"])
                    return close_windows(client, opened)
            if self.allow_rest and hero["hp"] < hero["max_hp"] * 0.85 and not optional and not unknown and not harmful \
                    and client.last_health_loss == 0 and current not in self.unsafe_rest_cells \
                    and cells.get(pos, {}).get("terrain") != 14 and not cells.get(pos, {}).get("environment") \
                    and not contains(buff_text, ("hungry", "starving", "饥饿")) and available(state, "rest"):
                return client.bounded_action(state, "ordinary_rest_while_well_fed_and_no_visible_hostile", "rest")
            for maintenance in (self.allocate_talent, self.inspect_and_equip, self.upgrade_equipment):
                result = maintenance(client, state)
                if result is not None:
                    return result
        ground = [e for e in entities if e.get("kind") == "item"]
        underfoot = next((e for e in ground if e["cell"] == pos), None)
        if underfoot:
            key = (depth, pos, underfoot.get("item", {}).get("name"),
                   tuple((i.get("name"), i.get("quantity")) for i in inventory))
            if self.pickup_attempts[key] >= 2:
                raise Checkpoint("repeated_pickup_without_inventory_change_requires_review")
            result = client.bounded_action(state, "pick_up_observed_item_underfoot", "cell.select", cell=pos, mode="act")
            if client.last_action_executed:
                self.pickup_attempts[key] += 1
            return result
        blocked = {cell for floor, cell in self.blocked if floor == depth}
        blocked.update(cell for floor, cell in self.avoided_cells if floor == depth)
        blocked.update(hazard_cells)
        blocked.update(optional_cells)
        blocked.update(c["cell"] for c in cells.values() if c.get("environment"))
        blocked.update(e["cell"] for e in entities if e.get("kind") in {"trap", "plant", "container", "object"}
                       and cells.get(e["cell"], {}).get("terrain") != 19)
        blocked.update(e["cell"] for e in characters)
        blocked.update(cell for cell in cells if any(distance(cell, e["cell"], width) <= 2 for e in controlled))
        for fish in (e for e in characters if contains(e.get("name"), ("piranha", "食人鱼"))):
            blocked.update(n for n in neighbours(fish["cell"], width, height)
                           if cells.get(n, {}).get("terrain") == 29)
        paths = paths_from(pos, cells, width, height, blocked)
        loot = [e for e in ground if e["cell"] in paths and paths[e["cell"]]]
        if loot:
            target = min(loot, key=lambda e: len(paths[e["cell"]]))
            return self.move(client, state, depth, pos, paths[target["cell"]][0], "approach_observed_loot")
        if hostiles:
            target = min(hostiles, key=lambda e: distance(pos, e["cell"], width))
            approach = [(len(path), cell, path) for cell, path in paths.items()
                        if path and distance(cell, target["cell"], width) == 1]
            if approach:
                _, _, path = min(approach)
                return self.move(client, state, depth, pos, path[0], "approach_observed_hostile_one_cell")
        candidates = []
        for cell, path in paths.items():
            if not path:
                continue
            fresh = sum(n not in cells for n in neighbours(cell, width, height))
            visits = self.visits[(depth, cell)]
            if fresh and visits < 2 or visits == 0 and cells[cell].get("terrain") == 15:
                candidates.append((not bool(fresh), len(path) + visits * 8 - min(fresh, 4) * 2, cell, path))
        if candidates:
            _, _, _, path = min(candidates)
            return self.move(client, state, depth, pos, path[0], "explore_only_observed_safe_terrain")
        containers = [e for e in entities if e.get("kind") in {"container", "object"}]
        inventory_signature = json.dumps([(i["name"], i["quantity"]) for i in inventory])
        for container in containers:
            key = (depth, container["cell"], container["name"], inventory_signature)
            if key in self.container_attempts:
                continue
            if contains(container.get("name"), ("remains", "tomb", "坟墓", "遗骸")):
                self.container_attempts.add(key)
                client.log("decision", reason="skip_optional_remains_or_tomb_during_bounded_first_region_run", container=container)
                continue
            if container.get("kind") == "object" or contains(container.get("description"), ("feels off", "bad idea", "古怪", "不对劲", "可疑")):
                self.container_attempts.add(key)
                client.log("decision", reason="avoid_suspicious_container_from_public_description", container=container)
                continue
            if hero["hp"] < hero["max_hp"] * 0.8:
                continue
            if distance(pos, container["cell"], width) <= 1:
                self.container_attempts.add(key)
                return client.bounded_action(state, "interact_with_observed_container_possible_unknown_risk", "cell.select",
                                             cell=container["cell"], mode="act")
            approaches = [(len(path), cell, path) for cell, path in paths.items()
                          if path and distance(cell, container["cell"], width) == 1]
            if approaches:
                return self.move(client, state, depth, pos, min(approaches)[2][0], "approach_observed_container")
        exits = [c for c in cells.values() if c.get("terrain") in STAIRS_DOWN]
        if exits and depth >= self.maximum_depth:
            raise Checkpoint("first_floor_reachable_exploration_checkpoint")
        for exit_cell in exits:
            target = exit_cell["cell"]
            if distance(pos, target, width) <= 1:
                return client.bounded_action(state, "descend_through_observed_stairs", "cell.select", cell=target, mode="act")
            approaches = [(len(path), cell, path) for cell, path in paths.items()
                          if path and distance(cell, target, width) == 1]
            if approaches:
                return self.move(client, state, depth, pos, min(approaches)[2][0], "approach_observed_downstairs")
        if available(state, "search") and not hostiles and not optional and not harmful \
                and not contains(buff_text, ("starving", "极度饥饿")):
            # Search a public room perimeter when no open route remains. A wall is merely
            # a possible place to search; no hidden terrain or private room bounds are read.
            walls = {c for c in cells if cells[c].get("terrain") in {4, 12}
                     and (depth, c) not in self.searched_walls
                     and any(n not in cells for n in neighbours(c, width, height))}
            search_spots = []
            for cell, path in paths.items():
                covered = {n for n in neighbours(cell, width, height) if n in walls}
                if covered:
                    search_spots.append((len(path) - min(len(covered), 3), -len(covered), cell, path, covered))
            if search_spots:
                _, _, cell, path, covered = min(search_spots)
                if path:
                    return self.move(client, state, depth, pos, path[0], "approach_unsearched_public_room_perimeter")
                observed = {c for c in covered if cells[c].get("visibility") == "visible"}
                if observed:
                    result = client.bounded_action(state, "search_visible_walls_for_a_normal_hidden_exit", "search")
                    if client.last_action_executed:
                        self.searched_walls.update((depth, c) for c in observed)
                    return result
        raise Checkpoint("no_safe_public_route_or_missing_interaction")

    def move(self, client, state, depth, pos, target, reason):
        observation = state["observation"]
        marker = (depth, pos, target, reason,
                  tuple((c["cell"], c.get("terrain")) for c in observation.get("map", {}).get("cells", [])),
                  tuple((i.get("name"), i.get("quantity")) for i in observation.get("inventory", [])),
                  observation.get("hero", {}).get("experience"),
                  tuple((e.get("cell"), e.get("name"), e.get("emotion"), tuple(b.get("name") for b in e.get("buffs", [])))
                        for e in observation.get("visible_entities", []) if e.get("kind") == "character"))
        if self.recent_navigation.count(marker) >= 3:
            raise Checkpoint("navigation_cycle_requires_public_route_review")
        result = client.bounded_action(state, reason, "cell.select", cell=target, mode="act")
        if client.last_action_executed:
            self.recent_navigation.append(marker)
        self.last_move = ((depth, pos), (depth, target)) if client.last_action_executed else None
        return result


def checkpoint(client, attempt, state, reason):
    record = {"reason": reason, "official_outcome": outcome_of(state or {}),
              "action_requests": client.action_count, "scope_id": client.scope,
              "state_version": client.version, "state": state,
              "strategy_state": client.policy.state_dict() if client.policy else None}
    (attempt / "checkpoint-public.json").write_text(json.dumps(record, ensure_ascii=False, indent=2))
    if (state or {}).get("observation", {}).get("scene") == "game":
        (attempt / "checkpoint-game-public.json").write_text(json.dumps(record, ensure_ascii=False, indent=2))
    client.log("checkpoint", **record)
    print(json.dumps({k: v for k, v in record.items() if k not in {"state", "strategy_state"}}, ensure_ascii=False), flush=True)


def interactive_public_session(client, attempt, state, reason="interactive_public_control", commands=None):
    """Keep the machine pipe alive while an operator resolves an actual public choice.

    This control stream accepts JSON intents, never game keyboard or pointer input.
    An unrecognized prompt or failed command remains connected and exposes its public UI.
    """
    commands = commands or sys.stdin
    def display():
        observation = state.get("observation", {})
        operation = getattr(client, "last_action_outcome", None) or getattr(client, "last_operation_response", None) or {}
        print(json.dumps({"interactive": True, "scope_id": state.get("scope_id"),
                          "state_version": state.get("state_version"), "phase": state.get("phase"),
                          "run_outcome": state.get("run_outcome"),
                          "hero": observation.get("hero"), "inventory": observation.get("inventory"),
                          "visible_entities": observation.get("visible_entities"),
                          "ui": observation.get("ui"), "actions": state.get("actions"),
                          "last_save": state.get("last_save"),
                          "last_operation": {"id": operation.get("id"), "ok": operation.get("ok"),
                                             "status": operation.get("status"), "error": operation.get("error"),
                                             "persistence": (operation.get("result") or {}).get("persistence")},
                          "action_requests": client.action_count}, ensure_ascii=False), flush=True)
    display()
    input_ended_reported = False
    while True:
        line = commands.readline()
        if not line:
            # EOF cannot authorize discarding a consumed scroll or an unresolved game choice.
            if not input_ended_reported:
                client.log("interactive_control_waiting", reason="operator_input_ended_game_connection_preserved")
                print(json.dumps({"interactive_waiting": "operator_input_ended_game_connection_preserved"}), flush=True)
                input_ended_reported = True
            time.sleep(0.25)
            continue
        try:
            command = json.loads(line)
            operation = command.get("command")
            if operation == "state":
                state = client.state()
            elif operation == "act":
                args = dict(command.get("args", {}))
                state = client.bounded_action(state, command.get("reason", "reviewed_public_action"),
                                              command["action"], **args)
            elif operation == "use_item":
                name = normalized(command["name"])
                candidates = [i for i in state.get("observation", {}).get("inventory", []) if normalized(i.get("name")) == name]
                if len(candidates) != 1:
                    raise Checkpoint("interactive_item_missing_or_ambiguous")
                state = use_item(client, state, candidates[0], tuple(command["verbs"]),
                                 command.get("reason", "reviewed_public_item_use"))
            elif operation == "choose":
                state = click_label(client, state, (normalized(command["label"]),),
                                    command.get("reason", "resolve_actual_public_choice"))
            elif operation == "checkpoint":
                checkpoint(client, attempt, state, command.get("reason", reason))
            elif operation == "play":
                amount = int(command.get("max_actions", 100))
                if not 1 <= amount <= 500:
                    raise Checkpoint("interactive_play_requires_1_to_500_action_budget")
                limit = min(client.maximum - 20, client.action_count + amount)
                while client.action_count < limit:
                    state = client.state()
                    state = client.policy.decide(client, state)
            elif operation == "stop":
                if state.get("phase") == "awaiting_input":
                    raise Checkpoint("resolve_current_visible_choice_before_stopping")
                return state, command.get("reason", "interactive_public_checkpoint")
            else:
                raise Checkpoint("unknown_interactive_command")
        except Exception as error:
            client.log("interactive_command_error", error_type=type(error).__name__, message=str(error))
            print(json.dumps({"interactive_command_error": type(error).__name__, "message": str(error),
                              "game_connection_preserved": True}), flush=True)
            state = client.last_state or state
        display()


def shutdown(client):
    client.cleanup = True
    state = client.state()
    if state.get("phase") == "awaiting_input":
        raise Checkpoint("resolve_current_visible_choice_before_shutdown")
    if state.get("phase") == "continuous_activity":
        cancel = next((a for a in state.get("actions", []) if a.get("action") == "action.cancel"), None)
        if not cancel:
            raise Checkpoint("cannot_close_running_activity_without_public_cancel")
        state = client.bounded_action(state, "cancel_activity_before_checkpoint_shutdown", "action.cancel",
                                      target_id=cancel["target_id"])
    if state.get("phase") == "player_ready" and available(state, "game.save"):
        for _ in range(8):
            state = client.bounded_action(state, "save_at_public_checkpoint", "game.save")
            if client.last_action_executed:
                break
        else:
            raise Checkpoint("checkpoint_save_repeatedly_stale")
    if available(state, "app.quit"):
        for _ in range(8):
            state = client.bounded_action(state, "close_checkpoint_session", "app.quit")
            if client.last_action_executed:
                break
        else:
            raise Checkpoint("checkpoint_quit_repeatedly_stale")
        client.process.wait(timeout=20)
    else:
        raise Checkpoint("no_advertised_graceful_shutdown")


def self_test():
    cells = {5: {"terrain": 1}, 6: {"terrain": 1}, 7: {"terrain": 8}, 9: {"terrain": 18}, 10: {"terrain": 1}}
    paths = paths_from(5, cells, 4, 4, {10})
    assert 6 in paths and 7 not in paths and 9 not in paths and 10 not in paths
    assert outcome_of({"observation": {"hero": {"depth": 26}, "inventory": [{"name": "Amulet"}]}}) is None
    assert outcome_of({"run_outcome": {"result": "won"}}) is None
    assert outcome_of({"scope_id": "run:test", "run_outcome": {"scope_id": "run:test", "result": "won"}}) == "won"
    assert outcome_of({"scope_id": "run:other", "run_outcome": {"scope_id": "run:test", "result": "won"}}) is None
    assert resource_selector_kind({"phase": "awaiting_input", "observation": {"ui": {"controls": [
        {"text": "Upgrade an item"}]}}}) == "upgrade"
    assert resource_selector_kind({"phase": "player_ready", "observation": {"ui": {"controls": [
        {"text": "Upgrade an item"}]}}}) is None
    description = {"observation": {"ui": {"controls": [{"text": "This _tier-2_ armor blocks _0-4 damage_ and requires _12 strength_ to use properly."}]}}}
    stats = equipment_stats(description)
    assert stats["kind"] == "armor" and stats["strength"] == 12 and stats["maximum"] == 4
    shore = {cell: {"terrain": 1} for cell in range(49)}
    danger = optional_threat_zone([{"kind": "character", "name": "giant piranha", "cell": 24,
                                   "context_action": "attack"}], shore, 7)
    assert 23 in danger and 22 in danger  # Land next to the fish is unsafe too.
    assert 0 not in danger
    assert 23 not in paths_from(0, shore, 7, 7, danger)
    assert creature_kind({"name": "rat", "context_action": "attack", "buffs": []}) == "confirmed_weak"
    assert creature_kind({"name": "marsupial rat", "context_action": "attack", "buffs": []}) == "confirmed_weak"
    assert ordinary_weak_description("marsupial rat", "They are aggressive but rather weak denizens of the sewers.")
    assert not ordinary_weak_description("strange rat", "Rather weak but attacks with poison.")
    assert creature_kind({"name": "great crab", "context_action": "attack"}) == "optional_risk"
    assert creature_kind({"name": "red sentry", "context_action": "interact"}) == "optional_risk"
    assert creature_kind({"name": "unfamiliar creature", "context_action": "interact"}) == "unknown"
    special = {cell: {"cell": cell, "terrain": 1} for cell in range(81)}
    for cell in (39, 38, 37, 2):
        special[cell]["terrain"] = 14
    defended = associated_sentry_floor([40], special, 9, 9)
    assert {37, 38, 39}.issubset(defended) and 2 not in defended
    assert 14 in WALKABLE  # Unrelated special floors are not globally prohibited.
    hazard_memory = Policy(4)
    assert hazard_memory.remember_visible_effects(2, {42: {"visibility": "visible", "environment": [{"type": "fire"}]}}) == {42}
    assert hazard_memory.remember_visible_effects(2, {42: {"visibility": "visited", "environment": []}}) == {42}
    assert hazard_memory.remember_visible_effects(2, {42: {"visibility": "visible", "environment": []}}) == set()
    mobile_memory = Policy(4)
    public_cells = {c: {"visibility": "visible", "terrain": 1} for c in range(81)}
    exile = {"kind": "character", "name": "gnoll exile", "cell": 40, "emotion": None, "buffs": []}
    mobile_memory.verified_neutral.add("gnoll exile")
    assert mobile_memory.remember_optional_positions(2, public_cells, [exile], 9) == {40}
    fogged = {**public_cells, 40: {"visibility": "visited", "terrain": 1}}
    assert mobile_memory.remember_optional_positions(2, fogged, [{**exile, "cell": 41}], 9) == {40, 41}
    assert mobile_memory.remember_optional_positions(2, public_cells, [{**exile, "cell": 41}], 9) == {41}
    aggressive_zone = mobile_memory.remember_optional_positions(2, public_cells, [{**exile, "cell": 41, "emotion": "alert"}], 9)
    assert 40 in aggressive_zone and 41 in aggressive_zone and len(aggressive_zone) > 1

    class MenuFixture:
        maximum = 100
        action_count = 0
        last_action_executed = False
        at = 0
        chosen = []
        states = [
            {"observation": {"scene": "start", "ui": {"modal": False}}, "actions": [
                {"action": "ui.activate", "control": "slot", "label": "Warrior\n6 minutes ago\n1\n1"},
                {"action": "ui.activate", "control": "new", "label": "New Game"}]},
            {"observation": {"scene": "start", "ui": {"modal": True}}, "actions": [
                {"action": "ui.activate", "control": "enter", "label": "Enter"}]},
            {"observation": {"scene": "game", "hero": {"class": "warrior"}}},
        ]
        def state(self):
            return self.states[self.at]
        def bounded_action(self, state, reason, action, **args):
            self.chosen.append(args.get("control"))
            assert args.get("control") != "new"
            self.action_count += 1
            self.last_action_executed = True
            self.at += 1
            return self.state()
    menu = MenuFixture()
    reach_game(menu, "warrior", True)
    assert menu.chosen == ["slot", "enter"]

    class PolicyFixture:
        maximum = 1000
        action_count = 0
        last_action_executed = True
        max_observed_health_loss = 0
        last_health_loss = 0
        last_action_name = None
        calls = None
        def __init__(self, state):
            self.original = state
            self.calls = []
        def log(self, *args, **kwargs):
            pass
        def bounded_action(self, state, reason, action, **args):
            self.calls.append((action, args, reason))
            if action == "cell.select" and args.get("mode") == "examine":
                inspected = dict(state)
                inspected["phase"] = "awaiting_input"
                inspected["observation"] = {**state["observation"], "ui": {"controls": [{"text": "An unfamiliar living creature."}]}}
                return inspected
            return self.original
    hero = {"depth": 1, "cell": 40, "hp": 16, "max_hp": 20, "level": 1, "class": "warrior",
            "strength": 10, "buffs": [], "talents": []}
    state = {"scope_id": "run:fixture", "phase": "player_ready", "observation": {"scene": "game", "hero": hero,
             "inventory": [], "visible_entities": [], "map": {"width": 9, "height": 9, "cells": list(special.values())}},
             "actions": [{"action": "cell.select"}, {"action": "ui.back"}, {"action": "rest"}]}
    loop_policy = Policy(4)
    loop_client = PolicyFixture(state)
    for _ in range(3):
        loop_policy.move(loop_client, state, 1, 40, 41, "approach_observed_loot")
    try:
        loop_policy.move(loop_client, state, 1, 40, 41, "approach_observed_loot")
        raise AssertionError("Repeated navigation without new information must stop")
    except Checkpoint as stopped:
        assert "navigation_cycle" in str(stopped)
    assert len(loop_client.calls) == 3
    changed_enemy = {**state, "observation": {**state["observation"], "visible_entities": [
                     {"kind": "character", "name": "swarm of flies", "cell": 48, "emotion": "alert"}]}}
    loop_policy.move(loop_client, changed_enemy, 1, 40, 41, "approach_observed_loot")
    assert len(loop_client.calls) == 4  # A newly awakened or moved enemy is material new information.
    class ShutdownFixture:
        def __init__(self):
            self.last_action_executed = False
            self.calls = []
            self.process = type("Process", (), {"wait": lambda _, timeout: None})()
        def state(self):
            return {"phase": "player_ready", "actions": [{"action": "game.save"}, {"action": "app.quit"}]}
        def bounded_action(self, state, reason, action, **args):
            self.calls.append(action)
            self.last_action_executed = self.calls.count(action) == 2
            return state
    stale_shutdown = ShutdownFixture()
    shutdown(stale_shutdown)
    assert stale_shutdown.calls == ["game.save", "game.save", "app.quit", "app.quit"]
    # Failed parsing and premature stop must preserve a live selection until a real choice resolves it.
    import io
    from contextlib import redirect_stdout
    class InteractiveFixture:
        action_count = 0
        def __init__(self):
            self.last_state = {"phase": "awaiting_input", "observation": {"ui": {"controls": [{"text": "Upgrade an item"}]}},
                               "actions": [{"action": "ui.activate", "control": "armor", "label": "Cloth Armor"}]}
            self.calls = []
        def log(self, *args, **kwargs):
            pass
        def bounded_action(self, state, reason, action, **args):
            self.calls.append((action, args))
            self.last_action_executed = True
            self.last_state = {"phase": "player_ready", "observation": {}, "actions": []}
            return self.last_state
    interactive_fixture = InteractiveFixture()
    commands = io.StringIO('not-json\n{"command":"stop"}\n{"command":"choose","label":"Cloth Armor"}\n{"command":"stop"}\n')
    with redirect_stdout(io.StringIO()) as shown:
        finished, _ = interactive_public_session(interactive_fixture, None, interactive_fixture.last_state, commands=commands)
    assert finished["phase"] == "player_ready"
    assert interactive_fixture.calls == [("ui.activate", {"control": "armor"})]
    assert '"game_connection_preserved": true' in shown.getvalue()
    wounded_rest = PolicyFixture(state)
    wounded_rest.last_action_name = "rest"
    wounded_rest.last_health_loss = 3
    policy = Policy(4, True)
    try:
        policy.decide(wounded_rest, state)
        raise AssertionError("A damaging rest must stop")
    except Checkpoint as stopped:
        assert "rest_lost_health" in str(stopped)
    assert (1, 40) in policy.unsafe_rest_cells and not wounded_rest.calls
    unfamiliar = {**state, "observation": {**state["observation"], "visible_entities": [
        {"kind": "character", "name": "unfamiliar creature", "cell": 42, "context_action": "interact"}]}}
    unknown_client = PolicyFixture(unfamiliar)
    try:
        Policy(4, True).decide(unknown_client, unfamiliar)
        raise AssertionError("Unknown creature must be inspected and reviewed")
    except Checkpoint as stopped:
        assert "unknown_creature_inspected" in str(stopped)
    assert unknown_client.calls[0][0:2] == ("cell.select", {"cell": 42, "mode": "examine"})
    assert all(call[0] != "rest" for call in unknown_client.calls)
    surrounded = {**state, "observation": {**state["observation"], "visible_entities": [
        {"kind": "character", "name": "marsupial rat", "cell": 39, "context_action": "attack", "buffs": []},
        {"kind": "character", "name": "marsupial rat", "cell": 41, "context_action": "attack", "buffs": []}]}}
    surrounded_client = PolicyFixture(surrounded)
    Policy(4).decide(surrounded_client, surrounded)
    assert surrounded_client.calls[0][2] == "retreat_from_surrounding_regular_enemies"
    amok_pair = {**surrounded, "observation": {**surrounded["observation"], "visible_entities": [
        {"kind": "character", "name": "gnoll scout", "cell": 48, "context_action": "attack", "buffs": [{"name": "amok"}]},
        {"kind": "character", "name": "gnoll scout", "cell": 49, "context_action": "attack", "buffs": [{"name": "amok"}]}]}}
    amok_client = PolicyFixture(amok_pair)
    Policy(4, True).decide(amok_client, amok_pair)
    assert amok_client.calls[0][2] == "withdraw_from_visible_amok_enemies_while_they_fight"
    assert amok_client.calls[0][1]["cell"] in {30, 31, 32}
    swarm_cells = [{"cell": cell, "terrain": terrain} for cell, terrain in
                   ((30, 1), (31, 1), (39, 1), (40, 6), (41, 1), (42, 1), (48, 1), (49, 1))]
    swarm_cells.extend({"cell": cell, "terrain": 4} for cell in range(81)
                       if cell not in {c["cell"] for c in swarm_cells})
    swarm_state = {**state, "actions": state["actions"] + [{"action": "wait"}],
                   "observation": {**state["observation"], "visible_entities": [
                    {"kind": "character", "name": "swarm of flies", "cell": 48, "context_action": "attack", "emotion": "sleeping", "buffs": []}],
                    "map": {"width": 9, "height": 9, "cells": swarm_cells}}}
    swarm_client = PolicyFixture(swarm_state)
    Policy(4).decide(swarm_client, swarm_state)
    assert swarm_client.calls[0][2] == "stage_in_single_file_passage_before_physical_swarm_combat"
    assert swarm_client.calls[0][1]["cell"] == 41
    # Two ordinary rats can be managed from a corner when healthy and carrying known healing.
    pair_cells = [{"cell": c, "terrain": terrain} for c, terrain in
                  ((40, 2), (31, 2), (22, 15), (21, 5), (32, 29), (41, 2))]
    pair = {**state, "observation": {**state["observation"], "hero": {**hero, "hp": 20},
            "inventory": [{"name": "potion of healing", "type_known": True}],
            "map": {"width": 9, "height": 9, "cells": pair_cells},
            "visible_entities": [
                {"kind": "character", "name": "marsupial rat", "cell": 32, "context_action": "attack", "buffs": []},
                {"kind": "character", "name": "marsupial rat", "cell": 41, "context_action": "attack", "buffs": []}]}}
    pair_client = PolicyFixture(pair)
    Policy(4).decide(pair_client, pair)
    assert pair_client.calls[0][1] == {"cell": 31, "mode": "act"}
    assert pair_client.calls[0][2] == "regroup_two_weak_enemies_toward_observed_door"
    corner = {**pair, "observation": {**pair["observation"],
              "map": {"width": 9, "height": 9, "cells": [c for c in pair_cells if c["cell"] != 21]},
              "ui": {"controls": [{"role": "health_bar", "cell": 41, "measurement": "rendered_pixels",
                                     "total_pixels": 48, "health_pixels": 12}]}}}
    corner_client = PolicyFixture(corner)
    Policy(4).decide(corner_client, corner)
    assert corner_client.calls[0][1] == {"cell": 41, "mode": "act"}
    assert corner_client.calls[0][2] == "controlled_focus_attack_two_weak_enemies"
    no_resources = {**corner, "observation": {**corner["observation"], "inventory": []}}
    try:
        Policy(4).decide(PolicyFixture(no_resources), no_resources)
        raise AssertionError("Two-enemy engagement needs the approved resources")
    except Checkpoint as stopped:
        assert "multiple_adjacent_enemies" in str(stopped)
    critical = {**surrounded, "observation": {**surrounded["observation"], "hero": {**hero, "hp": 4}}}
    critical_client = PolicyFixture(critical)
    try:
        Policy(4).decide(critical_client, critical)
        raise AssertionError("Low HP surrounded hero must checkpoint")
    except Checkpoint as stopped:
        assert "low_hp_visible_threat" in str(stopped)
    assert not critical_client.calls
    starving_alone = {**state, "observation": {**state["observation"],
                     "hero": {**hero, "hp": 8, "buffs": [{"name": "starving"}]}}}
    starving_client = PolicyFixture(starving_alone)
    try:
        Policy(4).decide(starving_client, starving_alone)
        raise AssertionError("Starvation must checkpoint even when no enemy is visible")
    except Checkpoint as stopped:
        assert "starving_without_known_safe_food" in str(stopped)
    assert not starving_client.calls
    snake_state = {**pair, "observation": {**pair["observation"], "visible_entities": [
                   {"kind": "character", "name": "sewer snake", "cell": 41, "context_action": "attack", "buffs": []}]}}
    snake_client = PolicyFixture(snake_state)
    Policy(4).decide(snake_client, snake_state)
    assert snake_client.calls[0][1] == {"cell": 31, "mode": "act"}
    assert snake_client.calls[0][2] == "lead_single_snake_to_nearby_observed_door"
    revealed_room = [{**cell, "terrain": 8 if cell["cell"] == 45 else 1} for cell in special.values()]
    stairs_state = {**state, "observation": {**state["observation"],
                    "map": {"width": 9, "height": 9, "cells": revealed_room}}}
    stairs_client = PolicyFixture(stairs_state)
    Policy(4).decide(stairs_client, stairs_state)
    assert stairs_client.calls[0][2] == "approach_observed_downstairs"
    closed_room_cells = [{"cell": y * 9 + x, "terrain": 4 if x in {1, 7} or y in {1, 7} else 1,
                         "visibility": "visible"} for y in range(1, 8) for x in range(1, 8)]
    closed_room = {**state, "actions": state["actions"] + [{"action": "search"}],
                   "observation": {**state["observation"],
                   "map": {"width": 9, "height": 9, "cells": closed_room_cells}}}
    room_client = PolicyFixture(closed_room)
    room_policy = Policy(4)
    room_policy.decide(room_client, closed_room)
    assert room_client.calls[0][2] == "approach_unsearched_public_room_perimeter"
    wall_adjacent = {**closed_room, "observation": {**closed_room["observation"],
                    "hero": {**hero, "cell": 20}}}
    room_client = PolicyFixture(wall_adjacent)
    room_policy.decide(room_client, wall_adjacent)
    assert room_client.calls[0][0] == "search"
    assert room_policy.searched_walls and all(depth == 1 for depth, _ in room_policy.searched_walls)
    poisoned = {**state, "observation": {**state["observation"], "hero": {**hero, "buffs": [{"name": "poisoned"}]}}}
    poisoned_client = PolicyFixture(poisoned)
    try:
        Policy(4, True).decide(poisoned_client, poisoned)
    except Checkpoint as stopped:
        assert "no_safe_public_route" in str(stopped)
    assert all(call[0] != "rest" for call in poisoned_client.calls)
    gas_cells = [{**c, "environment": [{"type": "gas", "description": "Visible toxic gas."}]} if c["cell"] == 40 else c
                 for c in special.values()]
    gas_state = {**state, "observation": {**state["observation"], "map": {"width": 9, "height": 9, "cells": gas_cells}}}
    gas_client = PolicyFixture(gas_state)
    Policy(4, True).decide(gas_client, gas_state)
    assert gas_client.calls[0][2] == "leave_current_visible_environment_effect"
    assert gas_client.calls[0][1]["cell"] != 40
    far_sentry = {**state, "observation": {**state["observation"], "visible_entities": [
        {"kind": "character", "name": "red sentry", "cell": 76, "context_action": "interact"}]}}
    sentry_client = PolicyFixture(far_sentry)
    try:
        Policy(4, True).decide(sentry_client, far_sentry)
    except Checkpoint as stopped:
        assert "no_safe_public_route" in str(stopped)
    assert all(call[0] != "rest" for call in sentry_client.calls)
    exposed = {**state, "observation": {**state["observation"], "hero": {**hero, "cell": 37}, "visible_entities": [
        {"kind": "character", "name": "red sentry", "cell": 40, "context_action": "interact"}]}}
    retreat_client = PolicyFixture(exposed)
    retreat_policy = Policy(4, True)
    retreat_policy.safe_cells.add((1, 36))
    retreat_policy.decide(retreat_client, exposed)
    assert retreat_client.calls[0][1] == {"cell": 36, "mode": "act"}

    # Exercise the actual progress/cancel client loop against public protocol-shaped responses.
    progress_client = object.__new__(PublicClient)
    progress_client.max_observed_health_loss = 0
    progress_client.last_health_loss = 0
    progress_client.last_action_name = None
    progress_client.log = lambda *args, **kwargs: None
    calls = []
    cancelled = [False]
    ready = {**state, "observation": {**state["observation"], "hero": {**hero, "hp": 15}}}
    activity = {**ready, "phase": "continuous_activity", "state_version": "activity:test:1",
                "actions": [{"action": "action.cancel", "target_id": "rest-test"}]}
    def protocol_request(op, args=None, request_id=None, scope=None, version=None):
        calls.append((op, args, scope, version))
        if op == "action.execute" and args["action"] == "rest":
            return {"ok": True, "status": "in_progress", "id": "rest-test", "scope_id": "run:fixture", "result": activity}
        if op == "action.execute" and args["action"] == "action.cancel":
            assert args["target_id"] == "rest-test" and version == "activity:test:1"
            cancelled[0] = True
            return {"ok": True, "status": "completed", "result": ready}
        if op == "request.get":
            return {"ok": True, "result": {"status": "INTERRUPTED" if cancelled[0] else "EXECUTING",
                    "response": {"ok": True, "status": "interrupted", "result": ready}}}
        raise AssertionError((op, args))
    progress_client.request = protocol_request
    progress_client.state = lambda: ready if cancelled[0] else activity
    progress_client.bounded_action(state, "test_rest_progress", "rest")
    assert cancelled[0] and progress_client.last_health_loss == 1
    assert progress_client.last_action_outcome["status"] == "interrupted"
    assert [call[1]["action"] for call in calls if call[0] == "action.execute"] == ["rest", "action.cancel"]
    print("public-policy self-test passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher", help="Already-built app launcher; no build is performed")
    parser.add_argument("--expect-cli-version", help="Require this public protocol.info cli_version before any game action")
    parser.add_argument("--classpath-file", type=Path, default=ROOT / "desktop-control/build/runtime-classpath.txt")
    parser.add_argument("--hero", choices=HERO_NAMES, default="warrior")
    parser.add_argument("--max-actions", type=int, default=100)
    parser.add_argument("--max-depth", type=int, default=1)
    parser.add_argument("--allow-rest", action="store_true", help="Only enable with a baseline supporting bounded continuous-action handling")
    parser.add_argument("--checkpoint-every", type=int, default=20)
    parser.add_argument("--resume-profile", type=Path)
    parser.add_argument("--profile", type=Path, help="Existing playthrough profile; preserves normally earned character unlocks")
    parser.add_argument("--new-run", action="store_true", help="Explicitly choose New Game in --profile through the real menu")
    parser.add_argument("--pause-file", type=Path, help="Creating this external control file requests a safe checkpoint")
    parser.add_argument("--interactive", action="store_true", help="Keep the public machine connection open for JSON control intents on stdin")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.max_actions < 12:
        parser.error("--max-actions must reserve at least 12 actions for menu and safe shutdown")
    if args.profile and args.resume_profile:
        parser.error("Use either --profile or --resume-profile")
    if args.new_run and (not args.profile or args.resume_profile):
        parser.error("--new-run requires explicit --profile and must not use --resume-profile")
    PLAYTHROUGHS.mkdir(parents=True, exist_ok=True)
    existing_profile = args.resume_profile or args.profile
    resuming = bool(existing_profile and not args.new_run)
    if existing_profile:
        attempt = existing_profile.resolve()
        if not attempt.is_relative_to(PLAYTHROUGHS.resolve()) or not attempt.is_dir():
            parser.error("Resume only an existing non-fixture build/playthroughs profile")
    else:
        name = datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + args.hero + "-" + uuid.uuid4().hex[:8]
        attempt = PLAYTHROUGHS / name
        attempt.mkdir()
    if args.launcher:
        command = [str(Path(args.launcher).resolve())]
    else:
        frozen_copy = attempt / ("classpath-" + uuid.uuid4().hex[:8] + ".txt")
        shutil.copyfile(args.classpath_file, frozen_copy)
        classpath = frozen_copy.read_text().strip()
        project_entries = [Path(entry) for entry in classpath.split(":") if str(ROOT) in entry]
        if not project_entries or any("runtime-images" not in str(entry) for entry in project_entries):
            parser.error("Classpath project entries must already be frozen under runtime-images")
        command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
                   "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
                   "com.shatteredpixel.shatteredpixeldungeon.control.desktop.SpdctlLauncher"]
    log_name = "strategy-public-" + uuid.uuid4().hex[:8] + ".ndjson"
    client = None
    reason, state = "not_started", None
    with (attempt / log_name).open("w", encoding="utf-8") as log:
        try:
            client = PublicClient(command, attempt, log, args.max_actions)
            client.log("attempt", profile=str(attempt), hero=args.hero, max_actions=args.max_actions,
                       max_depth=args.max_depth, resumed=resuming, explicit_new_run=args.new_run, policy="public-only-first-region-v5",
                       capabilities=["adjacent_exploration", "visible_melee_combat", "known_food_and_potions",
                                     "warrior_tier1_talents", "equipment_ui_stats", "known_upgrade_scrolls",
                                     "seal_transfer", "observed_stairs", "boss_checkpoint"],
                       requires_review=["npc_quest_choices", "unknown_scroll_selectors", "boss_combat",
                                        "class_specific_advanced_abilities"])
            print(json.dumps({"attempt": str(attempt), "public_log": log_name}, ensure_ascii=False), flush=True)
            hello = client.request("protocol.info")
            if not hello.get("ok"):
                raise Checkpoint("protocol_start_failed")
            if hello.get("result", {}).get("game_version") != "3.3.8":
                raise Checkpoint("policy_requires_review_for_this_game_version")
            if args.expect_cli_version and hello.get("result", {}).get("cli_version") != args.expect_cli_version:
                raise Checkpoint("unexpected_public_runtime_cli_version")
            state = reach_game(client, args.hero, resuming)
            state = complete_tutorial(client, state)
            policy = Policy(args.max_depth, args.allow_rest)
            if resuming:
                policy.restore_public_history(attempt, client.scope)
            client.policy = policy
            if args.interactive:
                state, reason = interactive_public_session(client, attempt, state)
            else:
                next_checkpoint = args.checkpoint_every
                while client.action_count < args.max_actions - 20:
                    state = client.state()
                    if args.pause_file and args.pause_file.exists():
                        raise Checkpoint("pause_file_requested")
                    if client.action_count >= next_checkpoint:
                        checkpoint(client, attempt, state, "periodic_public_checkpoint")
                        next_checkpoint += args.checkpoint_every
                    state = policy.decide(client, state)
                reason = "maximum_action_budget_checkpoint"
        except Checkpoint as stopped:
            reason = str(stopped)
        except BaseException as failure:
            reason = "failure:" + type(failure).__name__ + ":" + str(failure)
            if client:
                client.log("failure", type=type(failure).__name__, message=str(failure))
        finally:
            if client:
                state = client.last_state or state
                if client.process.poll() is None and (state or {}).get("phase") == "awaiting_input":
                    client.log("awaiting_input_held_open", reason=reason)
                    state, reason = interactive_public_session(client, attempt, state, reason)
                checkpoint(client, attempt, state, reason)
                try:
                    if client.process.poll() is None:
                        shutdown(client)
                except BaseException as shutdown_error:
                    client.log("shutdown_incomplete", error=str(shutdown_error))
                    if client.process.poll() is None:
                        client.process.stdin.close()
                        try:
                            client.process.wait(timeout=15)
                        except Exception:
                            client.process.terminate()
                            client.process.wait(timeout=10)
                finally:
                    client.stderr.close()
    print(json.dumps({"attempt": str(attempt), "result": reason,
                      "official_outcome": outcome_of(state or {}),
                      "actions": client.action_count if client else 0}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
