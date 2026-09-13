#!/usr/bin/env python3
"""Test-only character fixtures. All game choices use the public NDJSON protocol.

fixture-assertions.jsonl is read only after choosing/executing actions, for assertions.
It never supplies a destination, hidden target, inventory locator or action choice.
These artificially prepared runs are not victory evidence.
"""
import argparse
import json
from pathlib import Path
import re
import os
import shutil
import time
import unicodedata
import uuid
import zipfile
from machine_smoke import Client

CLASSES = ["WARRIOR", "MAGE", "ROGUE", "HUNTRESS", "DUELIST", "CLERIC"]
SUBCLASSES = {
    "BERSERKER": "WARRIOR", "GLADIATOR": "WARRIOR", "BATTLEMAGE": "MAGE", "WARLOCK": "MAGE",
    "ASSASSIN": "ROGUE", "FREERUNNER": "ROGUE", "SNIPER": "HUNTRESS", "WARDEN": "HUNTRESS",
    "CHAMPION": "DUELIST", "MONK": "DUELIST", "PRIEST": "CLERIC", "PALADIN": "CLERIC",
}
ARMOR = {
    "HeroicLeap": "WARRIOR", "Shockwave": "WARRIOR", "Endure": "WARRIOR",
    "ElementalBlast": "MAGE", "WildMagic": "MAGE", "WarpBeacon": "MAGE",
    "SmokeBomb": "ROGUE", "DeathMark": "ROGUE", "ShadowClone": "ROGUE",
    "SpectralBlades": "HUNTRESS", "NaturesPower": "HUNTRESS", "SpiritHawk": "HUNTRESS",
    "Challenge": "DUELIST", "ElementalStrike": "DUELIST", "Feint": "DUELIST",
    "AscendedForm": "CLERIC", "Trinity": "CLERIC", "PowerOfMany": "CLERIC", "Ratmogrify": "WARRIOR",
}
INITIAL_ITEM = {"WARRIOR": "worn shortsword", "MAGE": "staff", "ROGUE": "cloak",
                "HUNTRESS": "spirit bow", "DUELIST": "rapier", "CLERIC": "tome"}
FLOOR_TARGETS = {"HeroicLeap", "SmokeBomb", "Feint", "WarpBeacon", "PowerOfMany"}
SPELL_SELF = {"BlessSpell", "LayOnHands", "MnemonicPrayer"}
SPELL_FLOOR = {"Flash", "HallowedGround", "WallOfLight", "BeamingRay"}
SNEAK_WEAPONS = {"Dagger", "Dirk", "AssassinsBlade"}
GAME_PROSE_FIELDS = {"name", "class_name", "subclass_name", "label", "text", "description",
                     "prompt", "cell_prompt", "item_prompt", "options", "message", "title",
                     "hint", "tooltip", "disabled_reason"}
RAW_FIELDS = {"raw_request", "raw_response", "raw_bytes", "raw_format", "raw_json", "text_sources", "text_diagnostics", "node"}


def game_prose_values(value, path=(), prose=False):
    """Same prose/raw distinction as english_protocol_smoke; opaque data stays literal."""
    if isinstance(value, dict):
        for key, child in value.items():
            origin = value.get("text_sources", {}).get(key)
            def has_original(node):
                if isinstance(node, dict):
                    return node.get("origin") in {"user", "external"} or any(has_original(item) for item in node.values())
                return isinstance(node, list) and any(has_original(item) for item in node)
            original = set(value.get("text_origins", {}).get(key, [])) & {"user", "external"}
            if key not in RAW_FIELDS and not original and not has_original(origin):
                yield from game_prose_values(child, path + (key,), key in GAME_PROSE_FIELDS)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from game_prose_values(child, path + (index,), prose)
    elif prose and isinstance(value, str):
        yield path, value


def assert_game_prose_english(value):
    bad = [(path, text) for path, text in game_prose_values(value)
           if any(char.isalpha() and "LATIN" not in unicodedata.name(char, "") for char in text)]
    assert not bad, {"non_english_game_prose": bad[:10]}


def norm(text):
    return re.sub(r"[^a-z0-9]", "", text.lower())


class FixtureClient(Client):
    def __init__(self, command, profile, verify_gui=False):
        from test_ui import configure_test_ui
        configure_test_ui(profile)
        self.trace = (profile / "public-trace.jsonl").open("a")
        self.last_state = None
        self.verify_gui = verify_gui
        self.gui_postconditions_checked = 0
        super().__init__(command, profile)

    def request(self, op, args=None, **kwargs):
        result = super().request(op, args, **kwargs)
        if result.get("ok") and isinstance(result.get("result"), dict) and "observation" in result["result"]:
            self.last_state = result["result"]
        self.trace.write(json.dumps({"test_fixture": True, "op": op, "args": args,
                                     "request": getattr(self, "last_wire_request", None),
                                     "response": getattr(self, "last_wire_response", result)}, ensure_ascii=False) + "\n")
        self.trace.flush()
        # Preserve evidence before rejecting a malformed translation. This includes
        # nested request.get/history/events responses; raw audit fields remain opaque.
        assert_game_prose_english(result)
        data = result.get("result")
        quitting = op == "action.execute" and isinstance(args, dict) and args.get("action") == "app.quit"
        if self.verify_gui and not quitting and result.get("ok") and isinstance(data, dict) and "observation" in data:
            assert_gui_environment(self.profile, data)
            self.gui_postconditions_checked += 1
        return result


def act(client, action, **args):
    old_choice = None
    if action == "ui.activate" and client.last_state:
        old_choice = next((a for a in client.last_state["actions"] if a.get("control") == args.get("control") and a["action"] == action), None)
    for _ in range(4):
        result = client.request("action.execute", {"action": action, **args})
        if result.get("ok"):
            if result.get("status") == "in_progress":
                request_id, scope = result["id"], result["scope_id"]
                deadline = time.monotonic() + 40
                while time.monotonic() < deadline:
                    record = client.request("request.get", {"target_id": request_id}, scope=scope)
                    assert record.get("ok"), record
                    if record["result"]["status"] not in {"RECEIVED", "EXECUTING"}:
                        record = client.request("request.get", {"target_id": request_id, "get": ["reply"]}, scope=scope)
                        assert record.get("ok"), record
                        result = record["result"]["response"]
                        assert result.get("ok"), result
                        break
                    time.sleep(0.05)
                else:
                    raise TimeoutError("Fixture action did not reach its own terminal response")
                if client.verify_gui and action != "app.quit":
                    assert_gui_environment(client.profile, result["result"])
                    client.gui_postconditions_checked += 1
            data = result["result"]
            client.scope, client.version, client.last_state = data["scope_id"], data["state_version"], data
            return data
        if result.get("error", {}).get("code") != "STALE_STATE":
            raise AssertionError(result)
        refreshed = client.state()
        if action == "ui.activate" and old_choice and old_choice.get("label"):
            if any(a["action"] == action and a.get("control") == args["control"] for a in refreshed["actions"]):
                continue
            replacement = [a for a in refreshed["actions"] if a["action"] == action and a.get("label") == old_choice["label"]]
            assert len(replacement) == 1, {"stale_action_is_ambiguous": old_choice, "candidates": replacement}
            args["control"] = replacement[0]["control"]
    raise AssertionError("UI did not settle after repeated read-only refreshes")


def click(client, predicate):
    state = client.state()
    candidates = [a for a in state["actions"] if a["action"] == "ui.activate" and predicate(a.get("label", ""))]
    assert candidates, {"no_matching_control": [a for a in state["actions"] if a["action"] == "ui.activate"]}
    return act(client, "ui.activate", control=candidates[0]["control"])


def reach_game(client, hero_class):
    selected = False
    for _ in range(35):
        state = client.state()
        observation = state["observation"]
        if observation.get("scene") == "game":
            assert observation["hero"]["class"] == hero_class.lower(), observation["hero"]
            assert observation["hero"]["class_name"] == hero_class.lower(), observation["hero"]
            return client.state()  # allow pending UI displays to refresh after fixture setup
        ui = observation.get("ui", {})
        if ui.get("scene") == "HeroSelectScene" and not selected and not ui.get("modal"):
            click(client, lambda label: norm(label) == norm(hero_class))
            selected = True
            continue
        if ui.get("scene") == "HeroSelectScene" and ui.get("modal") and selected:
            act(client, "ui.back")
            continue
        options = [a for a in state["actions"] if a["action"] == "ui.activate" and a.get("label")]
        choice = None
        # Exact English resource labels; GUI language is checked separately after actions.
        for label in ["Continue", "Enter the Dungeon", "Play", "New Game", "Start"]:
            choice = next((a for a in options if a["label"] == label), None)
            if choice:
                break
        if choice:
            act(client, "ui.activate", control=choice["control"])
        elif any(a["action"] == "ui.reveal" for a in state["actions"]):
            act(client, "ui.reveal")
        else:
            raise AssertionError({"cannot_start": ui, "actions": state["actions"]})
    raise AssertionError("Did not reach a playable fixture")


def has_action(state, action):
    return any(a["action"] == action for a in state["actions"])


def close_choices(client, discard=False):
    for _ in range(8):
        state = client.state()
        if state["phase"] != "awaiting_input" and not state["observation"].get("ui", {}).get("modal"):
            return state
        if discard:
            confirmation = next((a for a in state["actions"] if a["action"] == "ui.activate" and a.get("label", "").lower().startswith("yes,")), None)
            if confirmation:
                act(client, "ui.activate", control=confirmation["control"])
                continue
        act(client, "cell.cancel" if has_action(state, "cell.cancel") else "ui.back")
    raise AssertionError("Prompt did not close through its normal cancellation path")


def visible_target(state, floor=False):
    observation = state["observation"]
    hero = observation["hero"]["cell"]
    width = observation["map"]["width"]
    characters = [e for e in observation["visible_entities"] if e["kind"] == "character"]
    if not floor and characters:
        return min(characters, key=lambda e: abs(e["cell"] % width - hero % width) + abs(e["cell"] // width - hero // width))["cell"]
    occupied = {e["cell"] for e in characters}
    cells = [c for c in observation["map"]["cells"] if c["visibility"] == "visible"
             and c["cell"] != hero and c["cell"] not in occupied
             and max(abs(c["x"] - hero % width), abs(c["y"] - hero // width)) == 1
             and any(word in c["name"].lower() for word in ("floor", "grass", "water", "door"))]
    assert cells, {"no_visible_target": observation["map"]}
    return cells[0]["cell"]


def checkpoint(profile, version):
    """Assertion data only: never pass values returned here to an action command."""
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        path = profile / "fixture-assertions.jsonl"
        if path.exists():
            for line in path.read_text().splitlines():
                row = json.loads(line)
                if row["state_version"] == version:
                    return row
        time.sleep(0.02)
    raise AssertionError("Missing internal test assertion checkpoint for completed public response")


def assert_gui_environment(profile, state):
    """Postcondition only: UiSceneAssertions never supplies an action or target."""
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        path = profile / "ui-assertions.jsonl"
        if path.exists():
            for line in path.read_text().splitlines():
                row = json.loads(line)
                if row["state_version"] == state["state_version"]:
                    assert row["scope_id"] == state["scope_id"], row
                    display = state["observation"]["ui"]["display"]
                    assert row["language_code"] == display["language"] == os.environ.get("SPDCTL_TEST_LANGUAGE", "zh"), row
                    assert row["fullscreen"] is False and display["fullscreen"] is False, row
                    return {"gui_language": row["language"], "language_code": row["language_code"], "fullscreen": row["fullscreen"],
                            "asserted_state_version": row["state_version"]}
        time.sleep(0.02)
    raise AssertionError("Missing original GUI language/window assertion for completed public response")


def initial_item_test(client, hero_class):
    state = client.state()
    item = next(i for i in state["observation"]["inventory"] if INITIAL_ITEM[hero_class] in i["name"].lower())
    opened = act(client, "inventory.open", locator=item["locator"])
    assert opened["phase"] == "awaiting_input"
    assert any(a["action"] == "ui.activate" for a in opened["actions"])
    close_choices(client)
    return {"initial_item": item["name"], "opened_real_item_window": True}


def select_inventory_item(client, predicate):
    state = client.state()
    if any(a["action"] == "ui.activate" and predicate(a.get("label", "")) for a in state["actions"]):
        return click(client, predicate)
    bags = [n.get("label") for n in state["observation"]["ui"]["controls"]
            if n.get("enabled") and n.get("shortcut_action", "").startswith("bag_") and n.get("label")]
    for label in bags:
        click(client, lambda text: text == label)
        state = client.state()
        if any(a["action"] == "ui.activate" and predicate(a.get("label", "")) for a in state["actions"]):
            return click(client, predicate)
    raise AssertionError({"item_not_in_any_visible_bag": bags})


def armor_test(client, profile, name, empty):
    state = client.state()
    before = checkpoint(profile, state["state_version"])
    act(client, "inventory.open", locator="equipment.armor")
    result = click(client, lambda text: norm(text) == norm(name))
    cancelled = False
    selected_target = None
    if not empty and has_action(result, "cell.cancel"):
        after_cancel = act(client, "cell.cancel")
        assert checkpoint(profile, after_cancel["state_version"])["armor_charge"] == before["armor_charge"]
        cancelled = True
        act(client, "inventory.open", locator="equipment.armor")
        result = click(client, lambda text: norm(text) == norm(name))
    if not empty and name == "Trinity":
        result = click(client, lambda text: "blazing" in text.lower())
    if not empty and has_action(result, "cell.cancel"):
        selected_target = visible_target(result, name in FLOOR_TARGETS)
        result = act(client, "cell.select", cell=selected_target)
    response_values = checkpoint(profile, result["state_version"])
    if not empty and name != "WarpBeacon":
        assert response_values["armor_charge"] < before["armor_charge"], {"action_response_preceded_armor_effect": name, "before": before, "response": response_values}
    close_choices(client)
    result = client.state()
    after = checkpoint(profile, result["state_version"])
    if empty:
        assert before["armor_charge"] == after["armor_charge"] == 0, (before, after)
        assert not has_action(result, "cell.cancel")
    elif name == "WarpBeacon":
        # Placement is free; exercise the actual paid teleport through the following menu too.
        act(client, "inventory.open", locator="equipment.armor")
        reopened = click(client, lambda text: norm(text) == norm(name))
        assert reopened["phase"] == "awaiting_input", reopened
        teleported = click(client, lambda text: "teleport" in text.lower())
        after = checkpoint(profile, teleported["state_version"])
        assert after["armor_charge"] < before["armor_charge"], after
        assert teleported["observation"]["hero"]["cell"] == selected_target
        close_choices(client)
    else:
        assert after["armor_charge"] < before["armor_charge"], {"ability_did_not_spend": name, "before": before, "after": after}
    return {"ability": name, "target_cancel_checked": cancelled, "resource_empty_checked": empty,
            "paid_teleport_checked": name == "WarpBeacon" and not empty,
            "charge_before": before["armor_charge"], "charge_after": after["armor_charge"]}


def subclass_test(client, profile, name):
    state = client.state()
    assert state["observation"]["hero"]["subclass"] == name.lower()
    before = checkpoint(profile, state["state_version"])
    passive = name in {"BATTLEMAGE", "WARLOCK", "WARDEN"}
    if passive:
        item = next(i for i in state["observation"]["inventory"] if ("staff" if name != "WARDEN" else "spirit bow") in i["name"].lower())
        act(client, "inventory.open", locator=item["locator"])
        result = click(client, lambda label: norm(label) == ("zap" if name != "WARDEN" else "shoot"))
    else:
        nodes = state["observation"]["ui"]["controls"]
        indicator = next((n for n in nodes if n.get("shortcut_action") == "tag_action" and n.get("enabled")), None)
        assert indicator, {"missing_indicator": name, "controls": nodes}
        result = act(client, "ui.activate", control=indicator["id"])
        if name == "GLADIATOR":
            result = click(client, lambda label: "slam" in label.lower())
        elif name == "MONK":
            result = click(client, lambda label: "dragon kick" in label.lower())
    if has_action(result, "cell.cancel"):
        result = act(client, "cell.select", cell=visible_target(result))
    response_values = checkpoint(profile, result["state_version"])
    if name == "MONK":
        assert response_values["monk_energy"] < before["monk_energy"], {"action_response_preceded_monk_effect": response_values}
    elif name in {"PRIEST", "PALADIN"}:
        assert response_values["tome_charge"] < before["tome_charge"], {"action_response_preceded_spell_effect": response_values}
    elif name == "CHAMPION":
        old = next(i["name"] for i in state["observation"]["inventory"] if i["locator"] == "equipment.weapon")
        new = next(i["name"] for i in result["observation"]["inventory"] if i["locator"] == "equipment.weapon")
        assert old != new, {"action_response_preceded_weapon_swap": (old, new)}
    elif name in {"BERSERKER", "FREERUNNER"}:
        assert state["observation"]["hero"]["buffs"] != result["observation"]["hero"]["buffs"], {
            "action_response_preceded_subclass_effect": name}
    else:
        assert response_values["target_hp"] < before["target_hp"], {"action_response_preceded_attack_effect": name, "response": response_values}
    close_choices(client)
    after_state = client.state()
    after = checkpoint(profile, after_state["state_version"])
    if name == "CHAMPION":
        old = next(i["name"] for i in state["observation"]["inventory"] if i["locator"] == "equipment.weapon")
        new = next(i["name"] for i in after_state["observation"]["inventory"] if i["locator"] == "equipment.weapon")
        assert old != new, (old, new)
    elif name == "MONK":
        assert after["monk_energy"] < before["monk_energy"], (before, after)
    elif name in {"PRIEST", "PALADIN"}:
        assert after["tome_charge"] < before["tome_charge"], (before, after)
    elif name not in {"BERSERKER", "FREERUNNER"}:
        assert after["target_hp"] < before["target_hp"], (before, after)
    else:
        assert state["observation"]["hero"]["buffs"] != after_state["observation"]["hero"]["buffs"]
    return {"subclass": name, "active_indicator": not passive, "public_action_and_effect_checked": True}


def ui_test(client, name):
    state = client.state()
    inventory = state["observation"]["inventory"]
    if name == "alchemy":
        original_scope = state["scope_id"]
        station = next(c for c in state["observation"]["map"]["cells"] if c["visibility"] == "visible" and "alchemy" in c["name"].lower())
        entered = act(client, "cell.select", cell=station["cell"])
        assert entered["scope_id"] == original_scope, "Alchemy must remain in the same run scope"
        assert entered["observation"]["ui"]["scene"] == "AlchemyScene"
        for _ in range(3):
            click(client, lambda label: "add" in label.lower())
            select_inventory_item(client, lambda label: "sungrass" in label.lower() and "seed" in label.lower())
        click(client, lambda label: "craft" in label.lower())
        act(client, "ui.back")
        after = client.state()
        assert after["scope_id"] == original_scope
        assert any("healing" in i["name"].lower() for i in after["observation"]["inventory"]), after
        assert not any("sungrass" in i["name"].lower() for i in after["observation"]["inventory"])
        return {"alchemy_crafted_from_three_seeds": True, "same_run_scope": True}
    if name == "identify":
        item = next(i for i in inventory if norm(i["name"]) == "sword")
        assert not item["level_known"] and item["level"] is None, item
        scroll = next(i for i in inventory if "identify" in i["name"].lower())
    elif name == "upgrade":
        item = next(i for i in inventory if i["locator"] == "equipment.weapon")
        scroll = next(i for i in inventory if "upgrade" in i["name"].lower())
    else:
        item = next(i for i in inventory if i["locator"] == "equipment.weapon")
        scroll = next(i for i in inventory if "scroll" in i["name"].lower() and not i["type_known"])
    act(client, "inventory.open", locator=scroll["locator"])
    click(client, lambda label: norm(label) == "read")
    if name == "cancel-confirm":
        warning = act(client, "ui.back")
        assert warning["observation"]["ui"]["modal"]
        assert any(a.get("label", "").lower().startswith("no,") for a in warning["actions"])
        blocked_back = act(client, "ui.back")
        assert blocked_back["observation"]["ui"] == warning["observation"]["ui"], "Mandatory cancellation confirmation must not be bypassed by back"
        click(client, lambda label: label.lower().startswith("no,"))
    click(client, lambda label: norm(label) == "sword")
    if name == "upgrade":
        preview = client.state()
        assert preview["observation"]["ui"]["modal"]
        click(client, lambda label: norm(label) == "upgrade")
    close_choices(client)
    after = client.state()["observation"]["inventory"]
    if name == "identify":
        identified = next(i for i in after if i["locator"] == item["locator"])
        assert identified["level_known"] and identified["level"] == 2, identified
    elif name == "upgrade":
        upgraded = next(i for i in after if i["locator"] == "equipment.weapon")
        assert upgraded["level"] == item["level"] + 1, upgraded
    else:
        changed = next(i for i in after if i["locator"] == "equipment.weapon")
        assert changed["name"] != item["name"], changed
    return {"interaction": name, "original_selector_and_confirmation": True, "public_result_verified": True}


def spell_test(client, profile, name, empty=False):
    state = client.state()
    before = checkpoint(profile, state["state_version"])
    tome = next(i for i in state["observation"]["inventory"] if "tome" in i["name"].lower())
    act(client, "inventory.open", locator=tome["locator"])
    click(client, lambda label: norm(label) == "cast")
    expected = norm(name[:-5] if name.endswith("Spell") else name)
    menu = client.state()
    available = [a for a in menu["actions"] if a["action"] == "ui.activate"
                 and norm(a.get("label", "").split("\n")[0]) == expected]
    if empty:
        assert available, {"missing_original_dimmed_spell_control": name}
        node = next(n for n in menu["observation"]["ui"]["controls"] if n["id"] == available[0]["control"])
        assert node.get("dimmed"), {"empty_spell_not_visually_dimmed": node}
        refused = act(client, "ui.activate", control=available[0]["control"])
        assert not has_action(refused, "cell.cancel")
        refused_values = checkpoint(profile, refused["state_version"])
        assert refused_values["tome_charge"] == before["tome_charge"] == 0, (before, refused_values)
        close_choices(client)
        return {"spell": name, "empty_resource_native_refusal_checked": True, "dimmed_control_checked": True}
    assert available, {"spell_not_available": name, "actions": menu["actions"]}
    result = act(client, "ui.activate", control=available[0]["control"])
    cancelled = False
    if has_action(result, "cell.cancel"):
        cancelled_state = act(client, "cell.cancel")
        cancelled_values = checkpoint(profile, cancelled_state["state_version"])
        assert cancelled_values["tome_charge"] == before["tome_charge"], (before, cancelled_values)
        cancelled = True
        act(client, "inventory.open", locator=tome["locator"])
        click(client, lambda label: norm(label) == "cast")
        result = click(client, lambda label: norm(label.split("\n")[0]) == expected)
    if name == "HolyIntuition":
        result = select_inventory_item(client, lambda label: norm(label) == "sword")
    elif name in {"BodyForm", "MindForm", "SpiritForm"}:
        item_hint = {"BodyForm": "blazing", "MindForm": "magic missile", "SpiritForm": "accuracy"}[name]
        if name == "BodyForm":
            options = client.state()
            option = next(a for a in options["actions"] if a["action"] == "ui.activate" and item_hint in a.get("label", "").lower())
            unchanged = act(client, "ui.activate", control=option["control"], gesture="long")
            assert unchanged["observation"]["ui"] == options["observation"]["ui"], "Unimplemented native long press must not synthesize a click"
            assert checkpoint(profile, unchanged["state_version"])["tome_charge"] == before["tome_charge"]
        result = click(client, lambda label: item_hint in label.lower())
        result = click(client, lambda label: label.lower().startswith("assign to"))
    elif has_action(result, "cell.cancel"):
        target = result["observation"]["hero"]["cell"] if name in SPELL_SELF else visible_target(result, name in SPELL_FLOOR)
        result = act(client, "cell.select", cell=target)
    response_values = checkpoint(profile, result["state_version"])
    assert response_values["tome_charge"] + response_values["tome_partial"] < before["tome_charge"] + before["tome_partial"], {"action_response_preceded_spell_effect": name, "response": response_values}
    close_choices(client)
    state = client.state()
    after = checkpoint(profile, state["state_version"])
    assert after["tome_charge"] + after["tome_partial"] < before["tome_charge"] + before["tome_partial"], {"spell_not_cast": name, "before": before, "after": after}
    if name in {"BodyForm", "MindForm", "SpiritForm"}:
        assert after["trinity_form"], after
    if name == "HolyIntuition":
        assert next(i for i in state["observation"]["inventory"] if norm(i["name"]) == "sword")["curse_known"]
    if name == "Cleanse":
        assert not any("Poison" in b for b in after["effect_buffs"]), after
    return {"spell": name, "cast_and_resource_spend_verified": True, "target_cancel_checked": cancelled,
            "unhandled_long_noop_checked": name == "BodyForm",
            "charge_before": before["tome_charge"] + before["tome_partial"], "charge_after": after["tome_charge"] + after["tome_partial"]}


def weapon_ability_control(client):
    state = client.state()
    excluded = {"equip", "unequip", "drop", "throw", "journal", "notes"}
    choices = [a for a in state["actions"] if a["action"] == "ui.activate" and a.get("label") and norm(a["label"]) not in excluded]
    assert len(choices) == 1, {"ambiguous_weapon_ability": choices}
    return choices[0]


def weapon_test(client, profile, name, empty=False):
    state = client.state()
    before = checkpoint(profile, state["state_version"])
    start_cell = state["observation"]["hero"]["cell"]
    act(client, "inventory.open", locator="equipment.weapon")
    choice = weapon_ability_control(client)
    result = act(client, "ui.activate", control=choice["control"])
    cancelled = False
    if not empty and has_action(result, "cell.cancel"):
        cancelled_state = act(client, "cell.cancel")
        assert checkpoint(profile, cancelled_state["state_version"])["weapon_charge"] == before["weapon_charge"]
        cancelled = True
        act(client, "inventory.open", locator="equipment.weapon")
        choice = weapon_ability_control(client)
        result = act(client, "ui.activate", control=choice["control"])
    if not empty and has_action(result, "cell.cancel"):
        result = act(client, "cell.select", cell=visible_target(result, name in SNEAK_WEAPONS))
    response_values = checkpoint(profile, result["state_version"])
    if not empty:
        assert response_values["weapon_charge"] < before["weapon_charge"], {"action_response_preceded_weapon_effect": name, "response": response_values}
    close_choices(client)
    state = client.state()
    after = checkpoint(profile, state["state_version"])
    if empty:
        assert before["weapon_charge"] == after["weapon_charge"] == 0, (before, after)
        assert not has_action(state, "cell.cancel")
    else:
        assert after["weapon_charge"] < before["weapon_charge"], {"weapon_ability_not_used": name, "before": before, "after": after}
        if name in SNEAK_WEAPONS:
            assert state["observation"]["hero"]["cell"] != start_cell
    return {"weapon": name, "ability_label": choice["label"], "actual_resource_spend_verified": not empty,
            "resource_empty_checked": empty, "target_cancel_checked": cancelled,
            "charge_before": before["weapon_charge"], "charge_after": after["weapon_charge"]}


def monk_test(client, profile, name, empty=False):
    state = client.state()
    before = checkpoint(profile, state["state_version"])
    indicator = next((n for n in state["observation"]["ui"]["controls"] if n.get("shortcut_action") == "tag_action" and n.get("enabled")), None)
    if empty and name == "Flurry":
        assert indicator is None and before["monk_energy"] == 0
        return {"monk_ability": name, "zero_energy_no_indicator": True}
    assert indicator, name
    act(client, "ui.activate", control=indicator["id"])
    menu = client.state()
    matching = [a for a in menu["actions"] if a["action"] == "ui.activate" and norm(a.get("label", "")).startswith(norm(name))]
    if empty:
        assert not matching, {"insufficient_monk_energy": name, "choices": matching}
        close_choices(client)
        return {"monk_ability": name, "insufficient_energy_disables_choice": True}
    assert matching, name
    result = act(client, "ui.activate", control=matching[0]["control"])
    if has_action(result, "cell.cancel"):
        result = act(client, "cell.select", cell=visible_target(result, name == "Dash"))
    after = checkpoint(profile, result["state_version"])
    assert after["monk_energy"] < before["monk_energy"], {"action_response_preceded_monk_effect": name, "response": after}
    if name == "Meditate":
        assert not any("Poison" in buff for buff in after["effect_buffs"]), after
    close_choices(client)
    return {"monk_ability": name, "actual_ability_and_energy_spend": True, "energy_before": before["monk_energy"], "energy_after": after["monk_energy"]}


def run_one(root, classpath, fixture, runtime_id):
    profile = root / "desktop-control/build/fixtures" / (fixture.replace(":", "-") + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    kind, name, *mode = fixture.split(":")
    hero_class = name if kind == "class" else SUBCLASSES[name] if kind == "subclass" else "WARRIOR" if kind == "ui" else "CLERIC" if kind == "spell" else "DUELIST" if kind in {"weapon", "monk"} else ARMOR[name]
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", fixture]
    client = FixtureClient(command, profile, verify_gui=True)
    report = {"test_fixture": True, "counts_as_win": False, "fixture": fixture, "profile": str(profile), "runtime_id": runtime_id}
    try:
        assert client.request("protocol.info")["ok"]
        started = reach_game(client, hero_class)
        report.update(cli_language="en", gui_environment=assert_gui_environment(profile, started))
        if kind == "class":
            evidence = initial_item_test(client, hero_class)
        elif kind == "armor":
            evidence = armor_test(client, profile, name, bool(mode))
        elif kind == "ui":
            evidence = ui_test(client, name)
        elif kind == "spell":
            evidence = spell_test(client, profile, name, bool(mode))
        elif kind == "weapon":
            evidence = weapon_test(client, profile, name, bool(mode))
        elif kind == "monk":
            evidence = monk_test(client, profile, name, bool(mode))
        else:
            evidence = subclass_test(client, profile, name)
        report.update(ok=True, evidence=evidence, every_response_game_prose_checked=True,
                      gui_postconditions_checked=client.gui_postconditions_checked)
    except Exception as error:
        report.update(ok=False, error=str(error))
    finally:
        try:
            close_choices(client, discard=True)
            client.finish()
            assert client.process.poll() == 0, {"fixture_exit_code": client.process.poll()}
        except Exception as error:
            report.update(ok=False, cleanup_error=str(error))
            try:
                if client.process.poll() is None:
                    client.process.terminate()
                    client.process.wait(timeout=10)
            except Exception as termination_error:
                report["cleanup_termination_error"] = str(termination_error)
        report["gui_postconditions_checked"] = client.gui_postconditions_checked
        client.trace.close()
        (profile / "fixture-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def freeze_runtime(root, classpath):
    """Keep Gradle rebuilds in other tasks from rewriting jars beneath a live JVM."""
    runtime_id = "runtime-" + uuid.uuid4().hex
    directory = root / "desktop-control/build/fixtures" / runtime_id
    directory.mkdir(parents=True)
    frozen = []
    sources = []
    for index, value in enumerate(classpath.split(os.pathsep)):
        source = Path(value).resolve()
        if source.exists() and source.is_relative_to(root):
            destination = directory / (str(index) + "-" + source.name)
            if source.is_dir():
                shutil.copytree(source, destination)
            else:
                shutil.copy2(source, destination)
                if source.suffix == ".jar":
                    with zipfile.ZipFile(destination) as archive:
                        assert archive.testzip() is None, source
            frozen.append(str(destination))
            sources.append({"source": str(source), "frozen": str(destination)})
        elif source.exists():
            frozen.append(str(source))  # versioned Gradle dependency cache artifacts are immutable
    (directory / "test-runtime.json").write_text(json.dumps({"test_fixture": True, "runtime_id": runtime_id, "copies": sources}, indent=2) + "\n")
    return os.pathsep.join(frozen), runtime_id


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", help="Comma-separated fixture IDs; default is all 6 classes, 12 subclasses and 19 armor abilities")
    parser.add_argument("--include-empty", action="store_true", help="Also test selected armor/spell/weapon cases with zero resource")
    parser.add_argument("--families", default="base", help="Comma-separated base,spell,weapon,monk,ui; spell/weapon/monk cases come from current source inventory")
    parser.add_argument("--frozen-runtime", type=Path, help="Copy a previously frozen test runtime; never reload mutable repository build products")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    classpath = (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip()
    if args.frozen_runtime:
        manifest = json.loads((args.frozen_runtime / "test-runtime.json").read_text())
        copies = {entry["source"]: entry["frozen"] for entry in manifest["copies"]}
        selected = []
        for value in classpath.split(os.pathsep):
            source = Path(value).resolve()
            if source.is_relative_to(root) and source.exists():
                assert str(source) in copies, {"not_in_frozen_runtime": str(source)}
                frozen = Path(copies[str(source)])
                assert frozen.exists(), frozen
                selected.append(str(frozen))
            elif source.exists():
                selected.append(str(source))
        classpath = os.pathsep.join(selected)
    classpath, runtime_id = freeze_runtime(root, classpath)
    print(json.dumps({"started_runtime": runtime_id, "results": str(root / "desktop-control/build/fixtures" / runtime_id / "results.json"),
                      "copied_from": str(args.frozen_runtime) if args.frozen_runtime else None}), flush=True)
    cases = []
    if args.cases:
        cases = args.cases.split(",")
    else:
        for family in args.families.split(","):
            if family == "base":
                cases += ["class:" + n for n in CLASSES] + ["subclass:" + n for n in SUBCLASSES] + ["armor:" + n for n in ARMOR]
            elif family == "ui":
                cases += ["ui:" + n for n in ("identify", "upgrade", "cancel-confirm", "alchemy")]
            elif family == "spell":
                manifest = json.loads((root / "game-control/src/test/resources/cli-ui-coverage.json").read_text())
                names = [Path(r["source"]).stem for r in manifest["records"] if r["category"] == "cleric_spell"
                         and Path(r["source"]).stem not in {"ClericSpell", "InventoryClericSpell", "TargetedClericSpell"}]
                assert len(set(names)) == len(names) == 27
                cases += ["spell:" + n for n in sorted(names)]
            elif family == "weapon":
                manifest = json.loads((root / "game-control/src/test/resources/cli-ui-coverage.json").read_text())
                names = [Path(r["source"]).stem for r in manifest["records"] if r["category"] == "input_method"
                         and ".duelistAbility(" in r["id"] and Path(r["source"]).stem != "MeleeWeapon"]
                assert len(names) == 32  # 31 in weapon/melee plus the quest Pickaxe.
                cases += ["weapon:" + n for n in names]
            elif family == "monk":
                manifest = json.loads((root / "game-control/src/test/resources/cli-ui-coverage.json").read_text())
                names = [r["id"].split("$")[-1] for r in manifest["records"] if r["category"] == "monk_ability" and not r["id"].endswith("$MonkAbility")]
                assert len(names) == 5
                cases += ["monk:" + n for n in names]
            else:
                raise ValueError("Unknown family: " + family)
    if args.include_empty:
        cases += [fixture + ":empty" for fixture in list(cases) if fixture.split(":")[0] in {"armor", "spell", "weapon", "monk"} and not fixture.endswith(":empty")]
    reports = [run_one(root, classpath, fixture, runtime_id) for fixture in cases]
    summary = {"test_fixture": True, "counts_as_win": False, "completion_assertions": "final_action_response",
               "runtime_id": runtime_id, "total": len(reports), "passed": sum(r["ok"] for r in reports), "results": reports}
    (root / "desktop-control/build/fixtures" / runtime_id / "results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    (root / "desktop-control/build/fixtures/latest-results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    if not all(r["ok"] for r in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
