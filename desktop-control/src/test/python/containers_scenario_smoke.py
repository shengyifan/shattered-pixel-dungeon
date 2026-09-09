#!/usr/bin/env python3
"""Original ground/container/key interactions; prepared fixtures, public CLI choices only."""
import argparse
import json
from pathlib import Path
import re
import traceback
import uuid

from fixture_smoke import FixtureClient, assert_gui_environment, checkpoint, freeze_runtime, game_prose_values
from english_protocol_smoke import start_or_continue
from low_frequency_smoke import act


CASES = ("heap-multi", "chest-hidden", "locked-chest", "crystal-chest", "tomb", "skeleton", "remains",
         "for-sale", "iron-door", "crystal-door")
TITLES = {"chest-hidden": "chest", "locked-chest": "locked chest", "crystal-chest": "crystal chest",
          "tomb": "tomb", "skeleton": "skeletal remains", "remains": "hero's remains"}
KEYS = {"locked-chest": ("golden key", "GoldenKey"), "crystal-chest": ("crystal key", "CrystalKey"),
        "iron-door": ("iron key", "IronKey"), "crystal-door": ("crystal key", "CrystalKey")}


def assertions(profile, state):
    """Postconditions only. Values returned here never choose an action, control or cell."""
    return checkpoint(profile, state["state_version"])["containers"]


def amount(state, name):
    return sum(item["quantity"] for item in state["observation"]["inventory"] if item["name"].casefold() == name.casefold())


def at_cell(state, cell):
    return [entity for entity in state["observation"]["visible_entities"] if entity.get("cell") == cell]


def ground(state, cell):
    return next((entity["item"] for entity in at_cell(state, cell) if entity.get("kind") == "item"), None)


def clock(profile, state):
    return assertions(profile, state)["hero_clock"]


def check_hidden(profile, state):
    private = assertions(profile, state)
    public = "\n".join(text for _, text in game_prose_values(state)).casefold()
    for hidden_name in private["unopened_payload_names"]:
        assert hidden_name.casefold() not in public, {"unopened_payload_name_leaked": hidden_name}


def inspect_closed(client, profile, state, cells):
    descriptions = []
    for cell in cells:
        before = clock(profile, state)
        opened = act(client, "cell.select", cell=cell, mode="examine")
        assert opened["observation"]["ui"]["modal"]
        assert opened["observation"]["ui"].get("inspected_item") is None, "A closed container must not expose an item subject"
        check_hidden(profile, opened)
        descriptions.append([node["text"] for node in opened["observation"]["ui"]["controls"] if node.get("text")])
        state = act(client, "ui.back")
        assert clock(profile, state) == before, "Original container inspection/back should not spend a turn"
    if len(cells) > 1:
        assert all(text == descriptions[0] for text in descriptions), "Same-looking closed containers revealed different contents"
        before_heaps = assertions(profile, state)["heaps"]
        payloads = [json.dumps(before_heaps[str(cell)]["items"], sort_keys=True) for cell in cells]
        assert len(set(payloads)) > 1, "The hidden-content contrast needs different actual payloads"
    return state


def collect_pile(client, profile, state, cell, maximum=4):
    gains = []
    for _ in range(maximum):
        item = ground(state, cell)
        if item is None:
            assert assertions(profile, state)["heaps"][str(cell)] is None
            return state, gains
        assert not any(entity.get("kind") == "character" for entity in at_cell(state, cell)), "Reassess a real occupied target; do not attack instead of pickup"
        before = amount(state, item["name"])
        gold_before = state["observation"]["hero"]["gold"]
        was_here = state["observation"]["hero"]["cell"] == cell
        next_state = act(client, "cell.select", cell=cell)
        # With a visible enemy, original Hero.handle first moves onto a standard pile.
        # Only a subsequent same-cell selection picks up; never pretend that movement is pickup.
        gained = amount(next_state, item["name"]) - before
        if item["name"].casefold() == "gold":
            gained = next_state["observation"]["hero"]["gold"] - gold_before
        if gained:
            assert gained == item["quantity"], {"wrong_original_pickup_quantity": item, "gained": gained}
            gains.append({"name": item["name"], "quantity": gained})
        else:
            assert not was_here and next_state["observation"]["hero"]["cell"] == cell
            assert next_state["observation"]["hero"]["hp"] > 0
        state = next_state
    raise AssertionError("Pile did not empty through the bounded original pickup sequence")


def heap_multi(client, profile, initial):
    entities = [entity for entity in initial["observation"]["visible_entities"] if entity.get("kind") == "item"]
    assert len(entities) == 1, entities
    cell = entities[0]["cell"]
    assert entities[0]["item"]["name"].casefold() == "throwing stone" and entities[0]["item"]["quantity"] == 3
    assert len(assertions(profile, initial)["heaps"][str(cell)]["items"]) == 3
    before = clock(profile, initial)
    final, gains = collect_pile(client, profile, initial, cell)
    assert [(item["name"].casefold(), item["quantity"]) for item in gains] == [("throwing stone", 3), ("gold", 17), ("ration of food", 2)], gains
    assert clock(profile, final) - before == 4, "One diagonal move plus three normal pickup turns"
    return {"case_id": "containers.heap_multi", "one_visible_top_item_for_three_private_stacks": True,
            "original_top_to_bottom_pickups": gains, "heap_removed_when_empty": True, "native_turns": 4}


def container(client, profile, initial, name):
    boxes = sorted([entity for entity in initial["observation"]["visible_entities"]
                    if entity.get("kind") == "container" and entity.get("name", "").casefold() == TITLES[name]], key=lambda entity: entity["cell"])
    assert boxes, initial["observation"]["visible_entities"]
    cells = [box["cell"] for box in boxes]
    if len(boxes) > 1:
        assert [{key: value for key, value in box.items() if key != "cell"} for box in boxes].count(
            {key: value for key, value in boxes[0].items() if key != "cell"}) == len(boxes)
    check_hidden(profile, initial)
    state = inspect_closed(client, profile, initial, cells)
    key_checks = []
    if name in KEYS:
        before = assertions(profile, state)
        refused = act(client, "cell.select", cell=cells[0])
        after = assertions(profile, refused)
        assert before["hero_clock"] == after["hero_clock"] and before["heaps"] == after["heaps"] and before["keys"] == after["keys"]
        assert not refused["observation"]["ui"]["modal"], "Native locked-container refusal has no cancel confirmation"
        check_hidden(profile, refused)
        key_name, key_class = KEYS[name]
        key = next(entity for entity in refused["observation"]["visible_entities"] if entity.get("item", {}).get("name", "").casefold() == key_name)
        state = act(client, "cell.select", cell=key["cell"])
        expected = len(cells)
        key_id = key_class + ":" + str(state["observation"]["hero"]["depth"])
        assert assertions(profile, state)["keys"].get(key_id, 0) == expected
        key_checks.append({"no_key_refusal_spends_no_turn_or_payload": True, "original_key_pickup": key_name, "quantity": expected})
    opened_loot = []
    for cell in cells:
        state = client.state()
        before = assertions(profile, state)
        position = state["observation"]["hero"]["cell"]
        width = state["observation"]["map"]["width"]
        distance = max(abs(position % width - cell % width), abs(position // width - cell // width))
        opened = act(client, "cell.select", cell=cell)
        after = assertions(profile, opened)
        assert not opened["observation"]["ui"]["modal"], "The original container opens directly, not through an invented confirmation"
        assert after["heaps"][str(cell)]["type"] == "HEAP"
        assert after["hero_clock"] - before["hero_clock"] == 1 + max(0, distance - 1), "Adjacent opening costs one native turn, plus ordinary approach steps"
        item = ground(opened, cell)
        assert item and item["level_known"] is False and item["level"] is None
        opened_loot.append(item["name"])
        if name in KEYS:
            assert after["keys"].get(key_id, 0) == before["keys"].get(key_id, 0) - 1
        if name == "tomb":
            assert after["wraith_count"] > before["wraith_count"], "Original tomb opening must spawn actual wraiths"
        state, gains = collect_pile(client, profile, opened, cell)
        assert len(gains) == 1
        check_hidden(profile, state)
    return {"case_id": "containers." + name.replace("-", "_"), "closed_payloads_not_published": True,
            "native_container_inspection_no_turn": True, "different_hidden_payloads_same_public_description": len(cells) > 1,
            "native_direct_open_without_cancel_dialog": True, "revealed_item_names_after_open": opened_loot,
            "equipment_levels_remain_unknown": True, "original_pickup_after_open": True,
            "key_checks": key_checks, "native_wraith_spawn_checked": name == "tomb"}


def choose(client, predicate):
    state = client.state()
    choices = [a for a in state["actions"] if a["action"] == "ui.activate" and predicate(a.get("label", ""))]
    assert len(choices) == 1, state["actions"]
    return act(client, "ui.activate", control=choices[0]["control"])


def for_sale(client, profile, initial):
    stock = next(entity for entity in initial["observation"]["visible_entities"] if entity.get("kind") == "item")
    name = stock["item"]["name"]
    qty = amount(initial, name); gold = initial["observation"]["hero"]["gold"]
    opened = act(client, "cell.select", cell=stock["cell"])
    price_action = next(a for a in opened["actions"] if re.fullmatch(r"Buy for (\d+)g", a.get("label", "")))
    price = int(re.fullmatch(r"Buy for (\d+)g", price_action["label"])[1])
    before = clock(profile, opened)
    cancelled = act(client, "ui.back")
    assert amount(cancelled, name) == qty and cancelled["observation"]["hero"]["gold"] == gold and clock(profile, cancelled) == before
    act(client, "cell.select", cell=stock["cell"])
    bought = choose(client, lambda label: label == "Buy for " + str(price) + "g")
    assert amount(bought, name) == qty + stock["item"]["quantity"] and bought["observation"]["hero"]["gold"] == gold - price
    assert assertions(profile, bought)["heaps"][str(stock["cell"])] is None
    return {"case_id": "containers.for_sale", "original_purchase_window": True, "back_cancels_without_charge": True,
            "price_from_public_button": price, "original_buy_and_inventory_gain": True}


def door(client, profile, initial, name):
    label = "locked door" if name == "iron-door" else "crystal door"
    tile = next(tile for tile in initial["observation"]["map"]["cells"] if tile["visibility"] == "visible" and tile["name"].casefold() == label)
    before = assertions(profile, initial)
    refused = act(client, "cell.select", cell=tile["cell"])
    after = assertions(profile, refused)
    assert before["hero_clock"] == after["hero_clock"] and before["terrain"] == after["terrain"] and before["keys"] == after["keys"]
    assert not refused["observation"]["ui"]["modal"]
    key_name, key_class = KEYS[name]
    key = next(entity for entity in refused["observation"]["visible_entities"] if entity.get("item", {}).get("name", "").casefold() == key_name)
    state = act(client, "cell.select", cell=key["cell"])
    key_id = key_class + ":" + str(state["observation"]["hero"]["depth"])
    assert assertions(profile, state)["keys"].get(key_id, 0) == 1
    opened = act(client, "cell.select", cell=tile["cell"])
    assert assertions(profile, opened)["keys"].get(key_id, 0) == 0
    current = next(cell for cell in opened["observation"]["map"]["cells"] if cell["cell"] == tile["cell"])
    assert current["name"].casefold() != label
    crossed = act(client, "cell.select", cell=tile["cell"])
    assert crossed["observation"]["hero"]["cell"] == tile["cell"]
    return {"case_id": "containers." + name.replace("-", "_"), "no_key_refusal_without_turn": True,
            "original_key_pickup": key_name, "one_key_consumed": True, "unlocked_then_crossed": True,
            "cancel_dialog": "not_applicable_original_flow_is_direct"}


def run_one(root, classpath, runtime_id, name):
    profile = root / "desktop-control/build/fixtures" / ("containers-" + name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
               "-cp", classpath, "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "container:" + name]
    client = FixtureClient(command, profile, verify_gui=True)
    report = {"case_id": "containers." + name.replace("-", "_"), "test_fixture": True, "counts_as_win": False,
              "profile": str(profile.relative_to(root)), "runtime_id": runtime_id}
    try:
        hello = client.request("protocol.info"); assert hello["ok"]
        report["build_id"] = hello["result"]["build_id"]
        initial, _ = start_or_continue(client)
        report["scope_id"] = initial["scope_id"]
        report["gui_environment"] = assert_gui_environment(profile, initial)
        evidence = heap_multi(client, profile, initial) if name == "heap-multi" else for_sale(client, profile, initial) if name == "for-sale" else door(client, profile, initial, name) if name.endswith("-door") else container(client, profile, initial, name)
        report.update(evidence=evidence, cli_language="en")
        client.finish()
        assert client.process.poll() == 0
        report.update(ok=True, gui_postconditions_checked=client.gui_postconditions_checked)
    except Exception as error:
        report.update(ok=False, error_type=type(error).__name__, error=str(error)[:5000], traceback=traceback.format_exc())
    finally:
        if client.process.poll() is None:
            try:
                if not client.process.stdin.closed: client.process.stdin.close()
                client.process.wait(timeout=35)
                report["failure_cleanup_exit_code"] = client.process.returncode
            except Exception as cleanup:
                report.update(ok=False, cleanup_error=str(cleanup))
                client.process.terminate(); client.process.wait(timeout=10)
        if not client.stderr.closed: client.stderr.close()
        if not client.trace.closed: client.trace.close()
        (profile / "containers-scenario-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--cases", default="heap-multi,chest-hidden"); args = parser.parse_args()
    names = args.cases.split(","); assert all(name in CASES for name in names)
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    results = [run_one(root, classpath, runtime_id, name) for name in names]
    summary = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id, "total": len(results), "passed": sum(row["ok"] for row in results), "results": results}
    (root / "desktop-control/build/fixtures" / runtime_id / "containers-scenarios-results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    if not all(row["ok"] for row in results): raise SystemExit(1)


if __name__ == "__main__": main()
