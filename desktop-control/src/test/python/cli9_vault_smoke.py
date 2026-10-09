#!/usr/bin/env python3
"""Native v4.0.2 Vault and item UI flows through protocol 9 on isolated source fixtures.

The fixture prepares only the starting state. This driver selects every target,
item and control from public observations, never reads a save/private checkpoint,
never retries a rejected action, and preserves the complete public wire trace.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, freeze_fixture_runtime, fixture_java_prefix
from tutorial_boundary_smoke import act, preferences


CLASSES = ("WARRIOR", "MAGE", "ROGUE", "HUNTRESS", "DUELIST", "CLERIC")
CASES = tuple("entry-" + cls for cls in CLASSES) + tuple("mirror-" + cls for cls in CLASSES) + (
    "tokens-0", "tokens-9", "tokens-10", "beacon", "injury", "exit-early", "exit-consumable",
    "exit-equipment", "exit-partial", "exit-victory", "exit-staff", "exit-seal", "exit-full", "staff-imbue", "augmentation")


class VaultClient(FixtureClient):
    def __init__(self, command, profile):
        self.exact_send = (profile / "exact-send.ndjson").open("ab")
        self.exact_recv = (profile / "exact-recv.ndjson").open("ab")
        super().__init__(command, profile, verify_gui=False)

    def request(self, *args, **kwargs):
        self.last_send_bytes = self.last_recv_bytes = None
        try:
            return super().request(*args, **kwargs)
        finally:
            if self.last_send_bytes is not None:
                self.exact_send.write(self.last_send_bytes)
                self.exact_send.flush()
            if self.last_recv_bytes is not None:
                self.exact_recv.write(self.last_recv_bytes)
                self.exact_recv.flush()


def inventory(state):
    return state["observation"]["inventory"]


def item(state, phrase):
    matches = [entry for entry in inventory(state) if phrase == entry["name"].lower()]
    if not matches:
        matches = [entry for entry in inventory(state) if phrase in entry["name"].lower()]
    assert len(matches) == 1, {"item": phrase, "matches": matches}
    return matches[0]


def inventory_signature(state):
    return [{key: entry[key] for key in ("name", "locator", "quantity", "level", "equipped", "available") if key in entry}
            for entry in inventory(state)]


def choices(state):
    return [action for action in state["actions"] if action["action"] == "ui.activate"]


def choose(client, state, label):
    matches = [action for action in choices(state) if action.get("label", "").casefold() == label.casefold()]
    assert len(matches) == 1, {"label": label, "choices": choices(state)}
    return act(client, "ui.activate", control=matches[0]["control"])


def choose_item(client, state, phrase):
    matches = [action for action in choices(state) if phrase in action.get("label", "").lower()]
    assert len(matches) == 1, {"item_choice": phrase, "choices": choices(state)}
    return act(client, "ui.activate", control=matches[0]["control"])


def use(client, state, phrase, operation):
    state = act(client, "inventory.open", locator=item(state, phrase)["locator"])
    return choose(client, state, operation)


def text(state):
    return "\n".join(node.get("text", "") for node in state["observation"]["ui"]["controls"])


def visible_entity(state, phrase):
    matches = [entity for entity in state["observation"]["visible_entities"]
               if phrase in entity.get("name", "").lower()]
    assert len(matches) == 1, {"entity": phrase, "matches": matches}
    return matches[0]


def start(client, hero_class):
    selected = False
    state = client.state()
    for _ in range(40):
        observation = state["observation"]
        ui = observation.get("ui", {})
        if observation.get("scene") == "game":
            assert observation["hero"]["class"] == hero_class.lower()
            deadline = time.monotonic() + 20
            while observation.get("hero", {}).get("depth") != 19:
                assert time.monotonic() < deadline, "Native vault source was not observed"
                time.sleep(0.05)
                state = client.state()
                observation = state["observation"]
                if observation.get("scene") != "game":
                    continue
            return state
        options = choices(state)
        if ui.get("scene") == "HeroSelectScene" and not selected:
            choice = next(action for action in options if action.get("label", "").lower() == hero_class.lower())
            selected = True
        elif ui.get("scene") == "HeroSelectScene" and ui.get("modal"):
            state = act(client, "ui.back")
            continue
        else:
            choice = next((action for label in ("Continue", "Enter the Dungeon", "Play", "New Game", "Start")
                           for action in options if action.get("label") == label), None)
            if choice is None and any(action["action"] == "ui.reveal" for action in state["actions"]):
                state = act(client, "ui.reveal")
                continue
            assert choice, {"cannot_start": ui, "actions": state["actions"]}
        state = act(client, "ui.activate", control=choice["control"])
    raise AssertionError("Native menu did not reach the vault fixture")


def assert_modal_blocks_map(client, state):
    assert state["observation"]["ui"]["modal"], state["observation"]["ui"]
    assert not any(action["action"] in ("move.step", "cell.select", "inventory.open") for action in state["actions"])
    response = client.request("action.execute", {"action": "move.step", "direction": "north"})
    assert not response.get("ok"), response
    receipt = client.request("request.get", {"target_id": response["id"]})
    assert receipt.get("ok") and receipt["result"]["status"] == "REJECTED", receipt
    current = client.state()
    assert current["observation"]["hero"]["cell"] == state["observation"]["hero"]["cell"]
    assert current["observation"]["ui"]["modal"]
    return current


def enter(client, state):
    original = [(entry["name"], entry["quantity"]) for entry in inventory(state)]
    entrance = state["observation"]["hero"]["cell"]
    imp = visible_entity(state, "ambitious imp")
    state = act(client, "cell.select", cell=imp["cell"], mode="act")
    assert "collector" in text(state).lower(), text(state)
    state = assert_modal_blocks_map(client, state)
    state = act(client, "ui.back")
    assert "can't teleport your gear" in text(state).lower(), text(state)
    state = act(client, "ui.back")
    state = act(client, "cell.select", cell=entrance, mode="act")
    if not state["observation"]["ui"]["modal"]:
        assert state["observation"]["hero"]["cell"] == entrance
        state = act(client, "cell.select", cell=entrance, mode="act")
    state = choose(client, state, "Stay here")
    assert [(entry["name"], entry["quantity"]) for entry in inventory(state)] == original
    state = act(client, "cell.select", cell=entrance, mode="act")
    before = state
    state = choose(client, state, "Descend into the vault")
    assert state["observation"]["hero"]["depth"] == 19
    assert state["observation"]["hero"]["gold"] == 0 and state["observation"]["hero"]["energy"] == 0
    assert item(state, "escape crystal")
    assert not any("round shield" in entry["name"].lower() for entry in inventory(state))
    assert len(inventory(state)) == 2, inventory(state)
    response = client.request("action.execute", {"action": "ui.back"}, version=before["state_version"])
    assert response.get("error", {}).get("code") == "STALE_STATE", response
    receipt = client.request("request.get", {"target_id": response["id"]})
    assert receipt.get("ok") and receipt["result"]["status"] == "REJECTED"
    return {"two_dialogue_intro": True, "cancel_preserved_inventory": True,
            "same_depth_branch_replaced_inventory": True, "old_revision_rejected": True}


def token_door(client, state, count):
    door = visible_entity(state, "strange door")
    assert door["kind"] == "object", door
    state = act(client, "cell.select", cell=door["cell"], mode="act")
    if count < 10:
        assert not any(action.get("label") == "Open the Door" for action in choices(state))
        assert ("don't have enough" if count else "find in the vault") in text(state).lower(), text(state)
        state = act(client, "ui.back")
        assert visible_entity(state, "strange door")
    else:
        state = choose(client, state, "Not Yet")
        assert item(state, "dwarf token")["quantity"] == 10
        state = act(client, "cell.select", cell=door["cell"], mode="act")
        state = choose(client, state, "Open the Door")
        assert not any("strange door" in entity.get("name", "").lower() for entity in state["observation"]["visible_entities"])
        assert not any("dwarf token" in entry["name"].lower() for entry in inventory(state))
        cell = next(cell for cell in state["observation"]["map"]["cells"] if cell["cell"] == door["cell"])
        assert "door" in cell["name"].lower() and "locked" not in cell["name"].lower(), cell
    return {"tokens": count, "native_interact_gate": True, "native_cancel_and_open": count == 10}


def mirror(client, state, hero_class):
    target = visible_entity(state, "strange mirror")
    assert target["kind"] == "object", target
    state = act(client, "cell.select", cell=target["cell"], mode="act")
    initial = inventory_signature(state)
    state = act(client, "ui.back")
    assert inventory_signature(state) == initial
    state = act(client, "cell.select", cell=target["cell"], mode="act")
    state = choose(client, state, "Take the Item")
    reward = {"WARRIOR": "broken seal", "MAGE": "staff", "ROGUE": "cloak of shadows",
              "HUNTRESS": "spirit bow", "DUELIST": "greatsword", "CLERIC": "holy tome"}[hero_class]
    assert item(state, reward)
    state = act(client, "ui.back")
    state = act(client, "cell.select", cell=target["cell"], mode="act")
    assert not any(action.get("label") == "Take the Item" for action in choices(state))
    state = act(client, "ui.back")
    token = next(entity for entity in state["observation"]["visible_entities"]
                 if "dwarf token" in entity.get("item", {}).get("name", "").lower())
    state = act(client, "cell.select", cell=token["cell"], mode="act")
    assert not any("dwarf token" in entry["name"].lower() for entry in inventory(state))
    assert not any("dwarf token" in entity.get("item", {}).get("name", "").lower()
                   for entity in state["observation"]["visible_entities"])
    return {"class": hero_class, "class_reward": reward, "one_time_grant": True, "later_tokens_discarded": True}


def assert_item_enabled(state, phrase, enabled):
    locator = item(state, phrase)["locator"]
    nodes = [node for node in state["observation"]["ui"]["controls"]
             if node.get("subject") == {"kind": "item", "locator": locator}]
    assert nodes, {"missing_selector_item": phrase, "ui": state["observation"]["ui"]}
    assert any(bool(node.get("enabled")) == enabled for node in nodes), nodes
    node_ids = {node["id"] for node in nodes}
    selectable = any(action.get("control") in node_ids for action in choices(state))
    assert selectable == enabled, {"item": phrase, "selectable": selectable, "expected": enabled}


def leave(client, state, name):
    state = act(client, "inventory.open", locator=item(state, "escape crystal")["locator"])
    assert {action.get("label", "").lower() for action in choices(state)}.isdisjoint({"drop", "throw"})
    state = choose(client, state, "USE")
    state = assert_modal_blocks_map(client, state)
    state = choose(client, state, "Stay Here")
    assert item(state, "escape crystal")
    state = use(client, state, "escape crystal", "USE")
    state = choose(client, state, "Leave the Vault")
    reward = None
    if name != "exit-early":
        assert_item_enabled(state, "escape crystal", False)
        assert_item_enabled(state, "potion of healing", True)
        assert_item_enabled(state, "sword", name != "exit-consumable")
        assert_item_enabled(state, "spear", name not in ("exit-consumable", "exit-equipment"))
        assert_item_enabled(state, "mace", name in ("exit-victory", "exit-staff", "exit-seal", "exit-full"))
        reward = {"exit-consumable": "potion of healing", "exit-equipment": "sword", "exit-partial": "spear",
                  "exit-victory": "mace", "exit-staff": "staff", "exit-seal": "leather armor", "exit-full": "mace"}[name]
        state = act(client, "ui.back")
        assert item(state, "escape crystal"), "Cancelling the native item picker must retain the vault"
        state = use(client, state, "escape crystal", "USE")
        state = choose(client, state, "Leave the Vault")
        state = choose_item(client, state, reward)
        assert "are you sure" in text(state).lower(), text(state)
        if name == "exit-staff": assert "wand will be automatically removed" in text(state).lower()
        if name == "exit-seal": assert "seal will be automatically removed" in text(state).lower()
        state = choose(client, state, "Stay Here")
        assert item(state, "escape crystal")
        state = use(client, state, "escape crystal", "USE")
        state = choose(client, state, "Leave the Vault")
        state = choose_item(client, state, reward)
        state = choose(client, state, "Leave the Vault")
    assert state["observation"]["hero"]["depth"] == 19
    assert state["observation"]["hero"]["gold"] == 47 and state["observation"]["hero"]["energy"] == 13
    original = item(state, "round shield")
    assert any(node.get("shortcut_action") == "quickslot_1"
               and node.get("subject") == {"kind": "item", "locator": original["locator"]}
               for node in state["observation"]["ui"]["controls"]), "Native return must restore the original quickslot binding"
    assert not any("escape crystal" in entry["name"].lower() for entry in inventory(state))
    if name == "exit-full":
        assert not any(entry["name"].lower() == "mace" for entry in inventory(state))
        for _ in range(3):
            reward_heap = [entity for entity in state["observation"]["visible_entities"]
                           if entity.get("item", {}).get("name", "").lower() == "mace"]
            if reward_heap:
                break
            state = act(client, "wait")
        assert len(reward_heap) == 1 and reward_heap[0]["item"]["level"] == 2, reward_heap
    elif name == "exit-staff":
        preserved = item(state, "wand of magic missile")
        assert preserved["level"] == 2, preserved
    elif reward:
        preserved = item(state, reward)
        assert preserved["quantity"] == 1, preserved
        if name == "exit-seal":
            state = act(client, "inventory.open", locator=preserved["locator"])
            assert not any(action.get("label", "").lower() == "detach" for action in choices(state))
    return {"native_reward_gate": name, "reward": reward, "cancel_paths_preserved_vault": True,
            "same_depth_return_restored_inventory_currency": True, "original_quickslot_restored": True,
            "full_inventory_reward_delivered_as_visible_heap": name == "exit-full"}


def extra(client, state, name):
    if name == "beacon":
        start_cell = state["observation"]["hero"]["cell"]
        state = use(client, state, "vault beacon", "CAST")
        assert item(state, "vault beacon")["quantity"] == 1
        assert state["observation"]["hero"]["cell"] != start_cell
        assert item(state, "escape crystal")
        return {"native_return_beacon_consumed_one": True, "remains_inside_vault": True}
    if name == "injury":
        if not state["observation"]["ui"]["modal"]:
            state = act(client, "wait")
        assert "pretty injured" in text(state).lower(), text(state)
        state = assert_modal_blocks_map(client, state)
        state = act(client, "ui.back")
        assert "pretty injured" in text(state).lower(), "Native injury modal must refuse back"
        state = choose(client, state, "Okay")
        assert not state["observation"]["ui"]["modal"]
        return {"native_forced_injury_modal": True, "back_refused": True, "explicit_okay_closed": True}
    if name == "staff-imbue":
        state = use(client, state, "staff", "IMBUE")
        state = choose_item(client, state, "wand of fireblast")
        assert "are you sure" in text(state).lower(), "An empty staff still needs native confirmation"
        state = choose(client, state, "No, I changed my mind")
        assert item(state, "wand of fireblast")
        state = use(client, state, "staff", "IMBUE")
        state = choose_item(client, state, "wand of fireblast")
        state = choose(client, state, "Yes, I'm sure")
        assert not any("wand of fireblast" in entry["name"].lower() for entry in inventory(state))
        assert item(state, "staff")["level"] == 2
        return {"empty_staff_confirmation_required": True, "cancel_preserved_wand": True, "native_imbue": True}
    if name == "augmentation":
        state = use(client, state, "stone of augmentation", "USE")
        state = choose_item(client, state, "sword")
        state = choose(client, state, "Never mind")
        assert item(state, "stone of augmentation")["quantity"] == 2
        state = choose_item(client, state, "sword")
        state = act(client, "ui.back")
        assert item(state, "stone of augmentation")["quantity"] == 2
        state = choose_item(client, state, "sword")
        state = choose(client, state, "+Speed -Damage")
        assert item(state, "stone of augmentation")["quantity"] == 1
        return {"cancel_reopened_selector": True, "back_reopened_selector": True, "native_augmentation_consumed_one": True}
    raise AssertionError(name)


def run_one(root, classpath, runtime_id, execution, interface_size, name):
    profile = root / "desktop-control/build/fixtures" / ("cli9-vault-" + name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    preferences(profile, interface_size)
    hero_class = name.split("-", 1)[1] if name.startswith(("entry-", "mirror-")) else (
        "MAGE" if name in ("exit-staff", "staff-imbue") else "WARRIOR")
    command = fixture_java_prefix(execution) + ["-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "vault:" + name]
    client = VaultClient(command, profile)
    result = {"test_fixture": True, "counts_as_win": False, "fixture": name, "runtime_id": runtime_id,
              "execution": execution, "interface_size": interface_size, "profile": str(profile.relative_to(root)),
              "action_retries": 0, "private_checkpoint_reads": 0}
    try:
        info = client.request("protocol.info")
        assert info.get("ok"), info
        result["build_id"] = info["result"]["build_id"]
        state = start(client, hero_class)
        if name.startswith("entry-"):
            result["evidence"] = enter(client, state)
        elif name.startswith("tokens-"):
            result["evidence"] = token_door(client, state, int(name.split("-")[1]))
        elif name.startswith("mirror-"):
            result["evidence"] = mirror(client, state, hero_class)
        elif name.startswith("exit-"):
            result["evidence"] = leave(client, state, name)
        else:
            result["evidence"] = extra(client, state, name)
        result["ok"] = True
    except Exception as error:
        result.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        # EOF invokes the native session shutdown even while a modal is open.
        try:
            if client.process.poll() is None:
                client.process.stdin.close()
                client.process.wait(timeout=35)
            assert client.process.returncode == 0, client.process.returncode
        except Exception as error:
            result.update(ok=False, cleanup_error=repr(error))
        finally:
            if client.process.poll() is None:
                client.process.terminate()
                client.process.wait(timeout=10)
            client.stderr.close()
            client.trace.close()
            client.exact_send.close()
            client.exact_recv.close()
        (profile / "cli9-vault-result.json").write_text(json.dumps(result, indent=2) + "\n")
    console = {key: result[key] for key in ("fixture", "ok", "runtime_id", "build_id", "profile", "evidence") if key in result}
    if not result.get("ok"):
        console["error"] = result.get("error", result.get("cleanup_error", "Unknown failure"))[:1200]
    print(json.dumps(console), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default=",".join(CASES))
    parser.add_argument("--interfaces", default="2")
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--bundle", type=Path, help="Use frozen packaged production code with the test-only launcher")
    options = parser.parse_args()
    names = options.cases.split(",")
    sizes = [int(value) for value in options.interfaces.split(",")]
    assert all(name in CASES for name in names)
    assert all(size in (0, 1, 2) for size in sizes)
    root = Path(__file__).resolve().parents[4]
    classpath_file = options.classpath_file or root / "desktop-control/build/test-runtime-classpath.txt"
    classpath, runtime_id, execution = freeze_fixture_runtime(root, classpath_file.read_text().strip(), options.bundle)
    driver = Path(__file__).read_bytes()
    frozen_driver = root / "desktop-control/build/fixtures" / runtime_id / "cli9_vault_smoke.py"
    frozen_driver.write_bytes(driver)
    execution["driver"] = {"sha256": hashlib.sha256(driver).hexdigest(), "frozen_source": str(frozen_driver)}
    reports = [run_one(root, classpath, runtime_id, execution, size, name) for size in sizes for name in names]
    output = root / "desktop-control/build/cli9-vault-validation.json"
    output.write_text(json.dumps(reports, indent=2) + "\n")
    if not all(report.get("ok") for report in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
