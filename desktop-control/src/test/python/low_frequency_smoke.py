#!/usr/bin/env python3
"""P6 low-frequency UI fixtures; public NDJSON choices, test-only post-action assertions."""
import argparse
import json
from pathlib import Path
import re
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, checkpoint, close_choices, freeze_runtime, reach_game, select_inventory_item

CASES = ["shop-trade", "shop-steal", "shop-steal-warning", "ghost-reward", "wandmaker-reward",
         "blacksmith-cashout", "blacksmith-pickaxe", "blacksmith-reforge", "companion",
         "alchemy-energy", "resurrect", "amulet-stay", "amulet-end",
         "shop-stack", "shop-steal-failure", "blacksmith-harden", "blacksmith-upgrade", "blacksmith-smith",
         "companion-attack", "companion-resummon", "blessed-ankh", "amulet-pickup"]


def act(client, action, **args):
    for _ in range(6):
        response = client.request("action.execute", {"action": action, **args})
        if response.get("error", {}).get("code") == "STALE_STATE":
            client.state()
            continue
        assert response.get("ok"), response
        if response.get("status") == "in_progress":
            request_id, scope = response["id"], response["scope_id"]
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                record = client.request("request.get", {"target_id": request_id}, scope=scope)
                assert record["ok"], record
                if record["result"]["status"] not in {"RECEIVED", "EXECUTING"}:
                    response = record["result"]["response"]
                    assert response.get("ok"), response
                    break
                time.sleep(0.05)
            else:
                raise TimeoutError("Low-frequency action did not complete")
        result = response["result"]
        client.scope, client.version, client.last_state = result["scope_id"], result["state_version"], result
        return result
    raise AssertionError("No stable current intent version")


def click(client, predicate):
    state = client.state()
    matches = [action for action in controls(state) if predicate(action.get("label", ""))]
    assert matches, {"no_matching_control": controls(state)}
    return act(client, "ui.activate", control=matches[0]["control"])


def quantity(state, fragment):
    return sum(item["quantity"] for item in state["observation"].get("inventory", []) if fragment in item["name"].lower())


def controls(state):
    return [action for action in state["actions"] if action["action"] == "ui.activate"]


def choose(client, fragment):
    return click(client, lambda label: fragment.lower() in label.lower())


def npc(client, fragment):
    state = client.state()
    entity = next(e for e in state["observation"]["visible_entities"] if fragment in e.get("name", "").lower())
    return act(client, "cell.select", cell=entity["cell"])


def shown_price(action):
    numbers = re.findall(r"\d+", action["label"])
    assert numbers, action
    return int(numbers[-1])


def public_stock(state):
    return next(e for e in state["observation"]["visible_entities"] if "healing" in e.get("item", {}).get("name", "").lower())


def shop(client, profile, steal):
    initial = client.state()
    gold = initial["observation"]["hero"]["gold"]
    count = quantity(initial, "healing")
    stock = public_stock(initial)
    opened = act(client, "cell.select", cell=stock["cell"])
    action = next(a for a in controls(opened) if ("steal" if steal else "buy") in a.get("label", "").lower())
    if steal:
        assert "100%" in action["label"], action
    price = shown_price(action)
    cancelled = act(client, "ui.back")
    assert cancelled["observation"]["hero"]["gold"] == gold and quantity(cancelled, "healing") == count
    assert public_stock(cancelled)["cell"] == stock["cell"]
    act(client, "cell.select", cell=stock["cell"])
    before = checkpoint(profile, client.state()["state_version"])["low_frequency"]
    bought = choose(client, "steal" if steal else "buy")
    assert quantity(bought, "healing") == count + 1, bought
    assert bought["observation"]["hero"]["gold"] == gold if steal else bought["observation"]["hero"]["gold"] == gold - price
    if steal:
        after = checkpoint(profile, bought["state_version"])["low_frequency"]
        assert after["armband_charge"] < before["armband_charge"], (before, after)
        return {"buy_window_cancel_checked": True, "actual_100_percent_steal": True,
                "gold_unchanged": True, "charge_before": before["armband_charge"], "charge_after": after["armband_charge"]}

    npc(client, "shopkeeper")
    choose(client, "sell")
    select_inventory_item(client, lambda label: "healing" in label.lower())
    sell_window = client.state()
    sell_action = next(a for a in controls(sell_window) if "sell" in a.get("label", "").lower())
    sell_price = shown_price(sell_action)
    sell_cancelled = act(client, "ui.back")
    assert quantity(sell_cancelled, "healing") == count + 1
    assert sell_cancelled["observation"]["hero"]["gold"] == gold - price
    select_inventory_item(client, lambda label: "healing" in label.lower())
    sold = choose(client, "sell")
    assert quantity(sold, "healing") == count
    assert sold["observation"]["hero"]["gold"] == gold - price + sell_price
    return {"buy_cancel_checked": True, "sell_cancel_checked": True,
            "purchase_cost": price, "sale_value": sell_price, "inventory_quantity_round_trip": True}


def shop_stack(client):
    initial = client.state()
    count, gold = quantity(initial, "healing"), initial["observation"]["hero"]["gold"]
    assert count == 3
    prices = []
    for entire_stack in (False, True):
        npc(client, "shopkeeper")
        choose(client, "sell an item")
        select_inventory_item(client, lambda label: "healing" in label.lower())
        trade = client.state()
        action = next(a for a in controls(trade) if ("sell all" if entire_stack else "sell 1") in a.get("label", "").lower())
        price = shown_price(action)
        sold = act(client, "ui.activate", control=action["control"])
        assert quantity(sold, "healing") == (0 if entire_stack else count - 1)
        assert sold["observation"]["hero"]["gold"] == gold + price
        close_choices(client)
        opened = npc(client, "shopkeeper")
        buyback = next(a for a in controls(opened) if "healing" in a.get("label", "").lower())
        returned = act(client, "ui.activate", control=buyback["control"])
        assert quantity(returned, "healing") == count
        assert returned["observation"]["hero"]["gold"] == gold
        prices.append(price)
    return {"sell_one_and_buyback": True, "sell_all_and_buyback": True, "stack_restored_each_time": True,
            "sale_prices": prices, "gold_round_trip": True}


def shop_failed_theft(client, profile):
    initial = client.state()
    before = values(client, profile)
    stock = next(e for e in initial["observation"]["visible_entities"] if "plate armor" in e.get("item", {}).get("name", "").lower())
    opened = act(client, "cell.select", cell=stock["cell"])
    attempt = next(a for a in controls(opened) if "steal" in a.get("label", "").lower())
    shown_chance = int(re.search(r"(\d+)%", attempt["label"])[1])
    assert 0 < shown_chance < 10, attempt
    act(client, "ui.activate", control=attempt["control"])
    failed = choose(client, "yes, i'm sure")  # Exactly one real attempt, no RNG override or retry.
    assert quantity(failed, "plate armor") == 0, "The single random trial succeeded; failure path is not covered by this run"
    assert not any("shopkeeper" in e.get("name", "").lower() for e in failed["observation"]["visible_entities"])
    assert failed["observation"]["hero"]["gold"] == initial["observation"]["hero"]["gold"]
    assert checkpoint(profile, failed["state_version"])["low_frequency"]["armband_charge"] == before["armband_charge"]
    return {"actual_failed_theft": True, "attempts": 1, "rng_overridden": False,
            "displayed_success_percent": shown_chance, "shopkeeper_fled": True,
            "no_item_acquired": True, "no_gold_or_charge_spent_on_native_failure": True}


def reward(client, profile, wandmaker):
    initial = client.state()
    fragment = "magic missile" if wandmaker else "sword"
    count = quantity(initial, fragment)
    quest_count = quantity(initial, "embers")
    npc(client, "wandmaker" if wandmaker else "ghost")
    selected = choose(client, fragment)
    assert any("confirm" in a.get("label", "").lower() for a in controls(selected)), selected
    cancelled = choose(client, "cancel")
    assert quantity(cancelled, fragment) == count
    if wandmaker:
        assert quantity(cancelled, "embers") == quest_count
    choose(client, fragment)
    received = choose(client, "confirm")
    assert quantity(received, fragment) == count + 1
    values = checkpoint(profile, received["state_version"])["low_frequency"]
    assert values["wandmaker_rewards_claimed" if wandmaker else "ghost_quest_complete"], values
    if wandmaker:
        assert quantity(received, "embers") == quest_count - 1
    return {"reward_preview_cancel_checked": True, "reward_received_in_final_response": True,
            "quest_consumable_removed": wandmaker, "quest_completion_checked": True}


def values(client, profile):
    return checkpoint(profile, client.state()["state_version"])["low_frequency"]


def empty_slot(client, index=0):
    state = client.state()
    slots = [a for a in controls(state) if not a.get("label")]
    assert len(slots) > index, {"empty_slot_missing": controls(state)}
    return act(client, "ui.activate", control=slots[index]["control"])


def blacksmith(client, profile, case):
    initial = client.state()
    before = values(client, profile)
    npc(client, "blacksmith")
    if case == "blacksmith-reforge":
        choose(client, "reforge")
        empty_slot(client)
        act(client, "ui.back")  # cancel the first item selector without consuming favor or gear
        assert values(client, profile)["blacksmith_favor"] == before["blacksmith_favor"]
        empty_slot(client)
        select_inventory_item(client, lambda label: label.lower().startswith("sword"))
        empty_slot(client)
        selection = client.state()
        swords = [a for a in controls(selection) if a.get("label", "").lower().startswith("sword")]
        assert len(swords) == 2, swords
        act(client, "ui.activate", control=swords[1]["control"])
        forged = choose(client, "reforge")
        after = checkpoint(profile, forged["state_version"])["low_frequency"]
        assert after["blacksmith_favor"] == before["blacksmith_favor"] - 500
        old = [i for i in initial["observation"]["inventory"] if i["name"].lower() == "sword"]
        new = [i for i in forged["observation"]["inventory"] if i["name"].lower() == "sword"]
        assert len(old) == 2 and len(new) == 1 and new[0]["level"] == max(i["level"] for i in old) + 1
        return {"selector_cancel_checked": True, "two_items_reforged_into_one": True, "favor_cost": 500}
    option = "pickaxe" if case == "blacksmith-pickaxe" else "cash out"
    choose(client, option)
    choose(client, "nevermind")
    assert values(client, profile)["blacksmith_favor"] == before["blacksmith_favor"]
    choose(client, option)
    done = choose(client, "yes, i want" if case == "blacksmith-pickaxe" else "give me the gold")
    after = checkpoint(profile, done["state_version"])["low_frequency"]
    if case == "blacksmith-pickaxe":
        assert quantity(done, "pickaxe") == quantity(initial, "pickaxe") + 1
        assert after["blacksmith_favor"] == before["blacksmith_favor"] - 250
    else:
        assert done["observation"]["hero"]["gold"] == initial["observation"]["hero"]["gold"] + before["blacksmith_favor"]
        assert after["blacksmith_favor"] == 0
    return {"confirmation_cancel_checked": True, "reward_and_favor_verified": True,
            "favor_before": before["blacksmith_favor"], "favor_after": after["blacksmith_favor"]}


def smith_service(client, profile, case):
    initial = client.state()
    before = values(client, profile)
    npc(client, "blacksmith")
    service = case.removeprefix("blacksmith-")
    choose(client, service)
    if service == "smith":
        choose(client, "nevermind")
        assert values(client, profile)["blacksmith_favor"] == before["blacksmith_favor"]
        choose(client, service)
        paid = choose(client, "warm the forge")
        paid_values = checkpoint(profile, paid["state_version"])["low_frequency"]
        assert paid_values["blacksmith_favor"] == before["blacksmith_favor"] - 2000
        assert paid_values["blacksmith_rewards_pending"]
        blocked_back = act(client, "ui.back")
        assert blocked_back["observation"]["ui"] == paid["observation"]["ui"]
        choices = [a for a in controls(blocked_back) if a.get("label")]
        assert len(choices) >= 2, choices
        label = choices[0]["label"]
        act(client, "ui.activate", control=choices[0]["control"])
        choose(client, "cancel")
        assert values(client, profile)["blacksmith_rewards_pending"]
        choose(client, label)
        reward_state = choose(client, "confirm")
        after = checkpoint(profile, reward_state["state_version"])["low_frequency"]
        assert not after["blacksmith_rewards_pending"] and after["blacksmith_smiths"] == before["blacksmith_smiths"] + 1
        assert sum(i["quantity"] for i in reward_state["observation"]["inventory"]) == sum(i["quantity"] for i in initial["observation"]["inventory"]) + 1
        return {"smith_cost": 2000, "purchase_confirmation_cancelled": True,
                "mandatory_reward_window_back_blocked": True, "reward_preview_cancelled": True,
                "generated_reward_received": True, "reward_list_cleared": True}
    act(client, "ui.back")
    assert values(client, profile)["blacksmith_favor"] == before["blacksmith_favor"]
    choose(client, service)
    result = click(client, lambda label: label.lower().startswith("sword"))
    after = checkpoint(profile, result["state_version"])["low_frequency"]
    cost = 500 if service == "harden" else 1000
    assert after["blacksmith_favor"] == before["blacksmith_favor"] - cost
    if service == "harden":
        assert after["hardened_swords"] == before["hardened_swords"] + 1
    else:
        assert sum(after["sword_levels"]) == sum(before["sword_levels"]) + 1
    return {"service": service, "item_selector_cancelled": True, "favor_cost": cost,
            "actual_item_effect_in_final_response": True}


def rose_menu(client, action):
    state = client.state()
    rose = next(i for i in state["observation"]["inventory"] if "dried rose" in i["name"].lower())
    act(client, "inventory.open", locator=rose["locator"])
    return choose(client, action)


def companion(client, profile):
    initial = client.state()
    rose_menu(client, "outfit")
    empty_slot(client)
    act(client, "ui.back")
    assert values(client, profile)["rose_weapon"] is None
    empty_slot(client)
    select_inventory_item(client, lambda label: label.lower().startswith("sword"))
    assert values(client, profile)["rose_weapon"] == "Sword"
    empty_slot(client)
    select_inventory_item(client, lambda label: "leather armor" in label.lower())
    assert values(client, profile)["rose_armor"] == "LeatherArmor"
    act(client, "ui.back")
    summoned = rose_menu(client, "summon")
    summoned_values = checkpoint(profile, summoned["state_version"])["low_frequency"]
    assert summoned_values["rose_charge"] == 0
    assert any("ghost" in e.get("name", "").lower() for e in summoned["observation"]["visible_entities"])
    targeting = rose_menu(client, "direct")
    cancelled = act(client, "cell.cancel")
    assert checkpoint(profile, cancelled["state_version"])["low_frequency"]["ghost_defending_cell"] == -1
    targeting = rose_menu(client, "direct")
    hero = targeting["observation"]["hero"]["cell"]
    width = targeting["observation"]["map"]["width"]
    occupied = {e["cell"] for e in targeting["observation"]["visible_entities"]}
    target = next(tile["cell"] for tile in targeting["observation"]["map"]["cells"] if tile["visibility"] == "visible"
                  and tile["name"].lower() == "floor" and tile["cell"] not in occupied
                  and max(abs(tile["x"] - hero % width), abs(tile["y"] - hero // width)) == 2)
    directed = act(client, "cell.select", cell=target)
    assert checkpoint(profile, directed["state_version"])["low_frequency"]["ghost_defending_cell"] == target
    following = rose_menu(client, "direct")
    following = act(client, "cell.select", cell=following["observation"]["hero"]["cell"])
    assert checkpoint(profile, following["state_version"])["low_frequency"]["ghost_defending_cell"] == -1
    rose_menu(client, "outfit")
    choose(client, "sword")
    choose(client, "leather armor")
    returned = act(client, "ui.back")
    restored = checkpoint(profile, returned["state_version"])["low_frequency"]
    assert restored["rose_weapon"] is None and restored["rose_armor"] is None
    assert quantity(returned, "sword") == quantity(initial, "sword") and quantity(returned, "leather armor") == 1
    return {"outfit_selector_cancel_checked": True, "weapon_and_armor_equipped": True,
            "summon_consumed_full_charge": True, "direct_cancel_checked": True, "defend_target_assigned": True,
            "follow_hero_command_checked": True, "equipment_returned_to_inventory": True}


def companion_variant(client, profile, resummon):
    initial = client.state()
    before = values(client, profile)
    if resummon:
        assert before["rose_first_summon"] and before["rose_charge"] == 99
        charged = act(client, "wait")
        assert checkpoint(profile, charged["state_version"])["low_frequency"]["rose_charge"] == 100
        summoned = rose_menu(client, "summon")
        assert checkpoint(profile, summoned["state_version"])["low_frequency"]["rose_charge"] == 0
        assert any("ghost" in e.get("name", "").lower() for e in summoned["observation"]["visible_entities"])
        return {"setup_previous_summon_with_nearly_full_recharge": True, "native_wait_completed_recharge": True,
                "second_summon_branch_executed": True, "full_charge_consumed": True}
    rose_menu(client, "outfit")
    empty_slot(client)
    select_inventory_item(client, lambda label: label.lower().startswith("sword"))
    act(client, "ui.back")
    rose_menu(client, "summon")
    targeting = rose_menu(client, "direct")
    target = next(e for e in targeting["observation"]["visible_entities"] if e.get("context_action") == "attack")
    directed = act(client, "cell.select", cell=target["cell"])
    assert checkpoint(profile, directed["state_version"])["low_frequency"]["ghost_enemy_cell"] == target["cell"]
    # A fixed, public sequence lets the original ally scheduler approach and attack.
    # The hidden HP assertion below never chooses whether or where to act.
    for _ in range(8):
        advanced = act(client, "wait")
    after = checkpoint(profile, advanced["state_version"])["low_frequency"]
    assert after["fixture_rat_hp"] < before["fixture_rat_hp"]
    return {"visible_enemy_directed": True, "target_assignment_in_final_response": True,
            "fixed_public_waits": 8, "actual_companion_damage_verified": True}


def steal_warning(client, profile):
    initial = client.state()
    before = values(client, profile)
    act(client, "cell.select", cell=public_stock(initial)["cell"])
    warning = choose(client, "steal")
    assert any("changed my mind" in a.get("label", "").lower() for a in controls(warning))
    cancelled = choose(client, "changed my mind")
    assert cancelled["observation"]["hero"]["gold"] == initial["observation"]["hero"]["gold"]
    assert quantity(cancelled, "healing") == quantity(initial, "healing")
    assert checkpoint(profile, cancelled["state_version"])["low_frequency"]["armband_charge"] == before["armband_charge"]
    return {"risky_steal_warning_shown": True, "native_confirmation_cancelled_without_cost": True}


def alchemy_energy(client, profile):
    initial = client.state()
    station = next(tile for tile in initial["observation"]["map"]["cells"] if "alchemy" in tile["name"].lower())
    act(client, "cell.select", cell=station["cell"])
    before = values(client, profile)["dungeon_energy"]
    choose(client, "energize items")
    select_inventory_item(client, lambda label: "healing" in label.lower())
    act(client, "ui.back")
    assert values(client, profile)["dungeon_energy"] == before
    select_inventory_item(client, lambda label: "healing" in label.lower())
    energized = choose(client, "turn 1 into")
    after_energy = checkpoint(profile, energized["state_version"])["low_frequency"]["dungeon_energy"]
    assert after_energy > before
    act(client, "ui.back")
    choose(client, "add")
    select_inventory_item(client, lambda label: "healing" in label.lower())
    brewed = choose(client, "craft")
    after_brew = checkpoint(profile, brewed["state_version"])["low_frequency"]["dungeon_energy"]
    assert after_brew == after_energy - 4
    after = act(client, "ui.back")
    assert after["scope_id"] == initial["scope_id"]
    assert quantity(after, "shielding") == 1 and quantity(after, "healing") == 0
    return {"energize_cancel_checked": True, "one_item_converted_to_energy": after_energy - before,
            "exotic_potion_crafted": True, "energy_spent": 4, "same_scope": True}


def resurrection(client, profile):
    initial = client.state()
    before = values(client, profile)
    assert initial["observation"]["hero"]["hp"] == 1 and quantity(initial, "ankh") == 1
    dead = act(client, "wait")
    assert any("preserve these items" in a.get("label", "").lower() for a in controls(dead)), dead
    blocked_back = act(client, "ui.back")
    assert blocked_back["observation"]["ui"] == dead["observation"]["ui"]
    choose(client, "worn shortsword")
    act(client, "ui.back")
    assert values(client, profile)["ankhs_used"] == before["ankhs_used"]
    choose(client, "worn shortsword")
    select_inventory_item(client, lambda label: "cloth armor" in label.lower())
    warning = choose(client, "preserve these items")
    assert any("reconsider" in a.get("label", "").lower() for a in controls(warning))
    choose(client, "reconsider")
    empty_slot(client)
    select_inventory_item(client, lambda label: "identify" in label.lower())
    revived = choose(client, "preserve these items")
    assert revived["scope_id"] == initial["scope_id"]
    assert revived["observation"]["hero"]["hp"] > 0
    assert quantity(revived, "ankh") == 0 and quantity(revived, "identify") == 1
    assert checkpoint(profile, revived["state_version"])["low_frequency"]["ankhs_used"] == before["ankhs_used"] + 1
    kept = next(item for item in revived["observation"]["inventory"] if item.get("available") is True and "identify" in item["name"].lower())
    inspected = act(client, "inventory.open", locator=kept["locator"])
    assert inspected["observation"]["ui"]["modal"]
    act(client, "ui.back")
    for _ in range(5):
        current = client.state()
        unavailable = next(item for item in current["observation"]["inventory"] if item.get("available") is False)
        failed_id = "lost-inventory-gray-" + uuid.uuid4().hex
        refused = client.request("action.execute", {"action": "inventory.open", "locator": unavailable["locator"]}, request_id=failed_id)
        if refused.get("error", {}).get("code") != "STALE_STATE":
            break
    assert refused.get("error", {}).get("code") == "ACTION_UNAVAILABLE", refused
    duplicate = client.request("action.execute", {"action": "inventory.open", "locator": unavailable["locator"]}, request_id=failed_id)
    assert duplicate.get("error", {}).get("code") == "DUPLICATE_REQUEST_ID", duplicate
    after_rejection = client.state()
    for key in ("hero", "inventory", "map", "visible_entities"):
        assert current["observation"][key] == after_rejection["observation"][key], {"gray_item_rejection_changed_world": key}
    return {"death_from_original_poison_tick": True, "mandatory_window_back_does_not_skip": True,
            "item_selector_cancel_checked": True, "missing_item_warning_cancelled": True,
            "selected_items_preserved": True, "ankh_consumed": True, "same_scope_after_resurrection": True,
            "kept_item_details_available": True, "lost_gray_item_preserved_in_public_inventory": True,
            "lost_gray_item_open_rejected": "ACTION_UNAVAILABLE", "rejected_request_id_burned": True,
            "rejection_did_not_change_observed_world": True}


def blessed_ankh(client, profile):
    initial = client.state()
    before = values(client, profile)
    assert initial["observation"]["hero"]["hp"] == 1 and quantity(initial, "ankh") == 1
    revived = act(client, "wait")
    assert revived["scope_id"] == initial["scope_id"]
    assert revived["observation"]["hero"]["hp"] > 0 and quantity(revived, "ankh") == 0
    assert not revived["observation"]["ui"]["modal"]
    assert not any("preserve these items" in a.get("label", "").lower() for a in controls(revived))
    assert checkpoint(profile, revived["state_version"])["low_frequency"]["ankhs_used"] == before["ankhs_used"] + 1
    assert quantity(revived, "identify") == quantity(initial, "identify")
    return {"original_poison_death": True, "automatic_blessed_resurrection": True,
            "no_item_selection_window": True, "ankh_consumed_inventory_preserved": True, "same_scope": True}


def amulet_pickup(client, profile):
    initial = client.state()
    assert quantity(initial, "amulet") == 0
    heap = next(e for e in initial["observation"]["visible_entities"] if "amulet" in e.get("item", {}).get("name", "").lower())
    first_choice = act(client, "cell.select", cell=heap["cell"])
    assert first_choice["observation"]["ui"]["scene"] == "AmuletScene"
    texts = " ".join(str(n.get("text", "")) for n in first_choice["observation"]["ui"]["controls"])
    assert len(texts) > 100, "The first-pickup story must be displayed, not just the noText re-opened menu"
    stayed = choose(client, "not done yet")
    assert stayed["scope_id"] == initial["scope_id"] and quantity(stayed, "amulet") == 1
    return {"ground_item_pickup_used": True, "native_delayed_pickup_callback_completed": True,
            "first_acquisition_story_shown": True, "stay_retains_amulet_and_scope": True}


def current_scene(client):
    response = client.request("state.get")
    if response.get("error", {}).get("code") == "SCOPE_MISMATCH":
        assert client.request("protocol.info")["ok"]
        response = client.request("state.get")
    assert response["ok"], response
    return response["result"]


def amulet(client, profile, end):
    initial = client.state()
    item = next(i for i in initial["observation"]["inventory"] if "amulet of yendor" in i["name"].lower())
    act(client, "inventory.open", locator=item["locator"])
    opened = choose(client, "end the game")
    assert opened["observation"]["ui"]["scene"] == "AmuletScene", opened
    assert any("not done yet" in a.get("label", "").lower() for a in controls(opened))
    if not end:
        stayed = choose(client, "not done yet")
        assert stayed["observation"]["scene"] == "game" and stayed["scope_id"] == initial["scope_id"]
        assert quantity(stayed, "amulet of yendor") == 1
        return {"amulet_scene_choice_read": True, "stay_returns_to_same_run": True}
    choose(client, "call it a day")
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        ranked = current_scene(client)
        if ranked["observation"]["ui"]["scene"] == "RankingsScene":
            break
        time.sleep(0.1)  # Wait only for the explicit post-victory presentation transition.
    else:
        raise AssertionError("Victory did not reach its normal ranking scene")
    events = client.request("events.read", {"limit": 100}, scope=initial["scope_id"])
    assert events["ok"], events
    assert any(event["kind"] == "run.ended" and event["data"].get("result") == "won" for event in events["result"]), events
    # This is an artificially prepared Amulet fixture, never a legitimate playthrough win.
    texts = " ".join(str(node.get("text", "")) for node in ranked["observation"]["ui"]["controls"])
    assert ranked["observation"]["ui"]["modal"], {"expected_victory_window": texts}
    assert "victory" in texts.lower() or "congratulations" in texts.lower(), texts
    blocked_back = act(client, "ui.back")
    assert blocked_back["observation"]["ui"] == ranked["observation"]["ui"]
    dismissed = choose(client, "close")
    assert not dismissed["observation"]["ui"]["modal"]
    return {"amulet_scene_choice_read": True, "test_only_win_event_recorded": True,
            "rankings_and_victory_window_shown": True, "victory_close_button_used": True,
            "counts_as_legitimate_win": False}


def run_one(root, classpath, runtime_id, case):
    profile = root / "desktop-control/build/fixtures" / ("p6-" + case + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "lowfreq:" + case]
    client = FixtureClient(command, profile)
    report = dict(test_fixture=True, counts_as_win=False, case=case, profile=str(profile.relative_to(root)), runtime_id=runtime_id)
    try:
        assert client.request("protocol.info")["ok"]
        reach_game(client, "WARRIOR")
        if case == "shop-stack":
            evidence = shop_stack(client)
        elif case == "shop-steal-failure":
            evidence = shop_failed_theft(client, profile)
        elif case == "shop-steal-warning":
            evidence = steal_warning(client, profile)
        elif case.startswith("shop-"):
            evidence = shop(client, profile, case == "shop-steal")
        elif case in {"blacksmith-harden", "blacksmith-upgrade", "blacksmith-smith"}:
            evidence = smith_service(client, profile, case)
        elif case.startswith("blacksmith-"):
            evidence = blacksmith(client, profile, case)
        elif case == "companion":
            evidence = companion(client, profile)
        elif case.startswith("companion-"):
            evidence = companion_variant(client, profile, case == "companion-resummon")
        elif case == "alchemy-energy":
            evidence = alchemy_energy(client, profile)
        elif case == "resurrect":
            evidence = resurrection(client, profile)
        elif case == "blessed-ankh":
            evidence = blessed_ankh(client, profile)
        elif case == "amulet-pickup":
            evidence = amulet_pickup(client, profile)
        elif case.startswith("amulet-"):
            evidence = amulet(client, profile, case == "amulet-end")
        else:
            evidence = reward(client, profile, case == "wandmaker-reward")
        report.update(ok=True, evidence=evidence)
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        try:
            close_choices(client)
            client.finish()
        except Exception as error:
            report["ok"] = False
            report["cleanup_error"] = str(error)
            if client.process.poll() is None:
                client.process.terminate()
                client.process.wait(timeout=10)
        client.trace.close()
        (profile / "low-frequency-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default=",".join(CASES))
    args = parser.parse_args()
    assert all(case in CASES for case in args.cases.split(",")), args.cases
    root = Path(__file__).resolve().parents[4]
    classpath = (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip()
    classpath, runtime_id = freeze_runtime(root, classpath)
    results = [run_one(root, classpath, runtime_id, case) for case in args.cases.split(",")]
    summary = dict(test_fixture=True, counts_as_win=False, runtime_id=runtime_id,
                   total=len(results), passed=sum(r["ok"] for r in results), results=results)
    (root / "desktop-control/build/fixtures" / runtime_id / "low-frequency-results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    if not all(r["ok"] for r in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
