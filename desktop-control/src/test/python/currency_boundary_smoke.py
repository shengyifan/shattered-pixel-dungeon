#!/usr/bin/env python3
"""Original currency fades and real counter changes over protocol 8, without retries.

Only isolated test starting conditions are injected. The runner never reads a save,
SQLite database, or private fixture assertion to select an action or assert a result.
"""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, freeze_runtime
from tutorial_boundary_smoke import act, adjacent_cells, direction_to, preferences, start_warrior


def currency_text(state):
    nodes = state["observation"]["ui"]["controls"]
    inventory_ids = {node["id"] for node in nodes if node.get("shortcut_action") == "inventory"}
    return [node for node in nodes if node.get("role") == "text"
            and node.get("parent") in inventory_ids and node.get("text", "").isdigit()]


def visible_currency(client, state, value):
    # Initial injection is performed by the after-frame observer. Wait only for its
    # first public projection, never refresh/retry an action rejected as stale.
    deadline = time.monotonic() + 2
    while not any(node.get("text") == str(value) for node in currency_text(state)):
        assert time.monotonic() < deadline, {"currency_never_projected": value, "ui": state["observation"]["ui"]}
        state = client.state()
    return state


def safe_step(state):
    occupied = {entity["cell"] for entity in state["observation"]["visible_entities"]}
    return next(cell["cell"] for cell in adjacent_cells(state) if cell["cell"] not in occupied)


def assert_rejected_without_movement(client, response, expected_cell):
    assert response.get("error", {}).get("code") == "STALE_STATE", response
    receipt = client.request("request.get", {"target_id": response["id"], "get": ["before", "after"]})
    assert receipt.get("ok") and receipt["result"]["status"] == "REJECTED", receipt
    assert receipt["result"]["before_snapshot"] == receipt["result"]["after_snapshot"], receipt
    state = client.state()
    assert state["observation"]["hero"]["cell"] == expected_cell
    return state


def reject_previous_revision(client, previous, current):
    destination = safe_step(current)
    response = client.request("action.execute", {"action": "move.step", "direction": direction_to(current, destination)},
                              version=previous["state_version"])
    assert_rejected_without_movement(client, response, current["observation"]["hero"]["cell"])


def item_entity(state, name):
    return next(entity for entity in state["observation"]["visible_entities"]
                if entity.get("item", {}).get("name", "").lower() == name)


def pick_currency(client, name, field, amount):
    before = client.state()
    cell = item_entity(before, name)["cell"]
    old = before["observation"]["hero"][field]
    after = act(client, "cell.select", cell=cell, mode="act")
    if after["observation"]["hero"][field] == old:
        # Native travel may stop on arrival before pickup. A second choice is only
        # valid after confirming both the arrival and the same publicly visible heap.
        assert after["observation"]["hero"]["cell"] == cell
        assert item_entity(after, name)["cell"] == cell
        after = act(client, "cell.select", cell=cell, mode="act")
    assert after["observation"]["hero"][field] == old + amount, after["observation"]["hero"]
    assert after["state_version"] != before["state_version"]
    reject_previous_revision(client, before, after)
    return {"counter": field, "before": old, "after": old + amount,
            "real_change_advanced_revision": True, "old_revision_rejected_without_movement": True}


def buy_ankh(client):
    before = client.state()
    target = item_entity(before, "ankh")["cell"]
    opened = act(client, "cell.select", cell=target, mode="act")
    choices = [action for action in opened["actions"] if action.get("action") == "ui.activate"
               and action.get("label", "").startswith("Buy for ")]
    assert len(choices) == 1, opened["observation"]["ui"]
    choice = choices[0]
    price = int(choice["label"].removeprefix("Buy for ").removesuffix("g"))
    before_gold = opened["observation"]["hero"]["gold"]
    purchased = act(client, "ui.activate", control=choice["control"])
    assert purchased["observation"]["hero"]["gold"] == before_gold - price
    assert any(item["name"].lower() == "ankh" for item in purchased["observation"]["inventory"])
    reject_previous_revision(client, opened, purchased)
    return {"counter": "gold", "before": before_gold, "after": before_gold - price,
            "purchase_advanced_revision": True, "old_revision_rejected_without_movement": True}


def run_one(root, classpath, runtime_id, interface_size, name, expect_regression):
    profile = root / "desktop-control/build/fixtures" / ("currency-" + name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    preferences(profile, interface_size)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher",
               "--fixture", "currency:" + name]
    client = FixtureClient(command, profile)
    result = {"test_fixture": True, "counts_as_win": False, "fixture": name,
              "runtime_id": runtime_id, "interface_size": interface_size,
              "expect_regression": expect_regression, "profile": str(profile.relative_to(root))}
    try:
        hello = client.request("protocol.info")
        assert hello.get("ok"), hello
        result.update(build_id=hello["result"]["build_id"], cli_version=hello["result"]["cli_version"])
        initial = start_warrior(client)
        value = 3 if name == "entry-energy" else 806
        boundary = visible_currency(client, initial, value)
        if name == "shop-gold":
            # Let entry currency settle before isolating the real shop showGold path.
            time.sleep(2.3)
            ready = client.state()
            target = item_entity(ready, "ankh")["cell"]
            assert target == ready["observation"]["hero"]["cell"]
            opened = act(client, "cell.select", cell=target, mode="act")
            assert any(action.get("label", "").startswith("Buy for ") for action in opened["actions"])
            boundary = act(client, "ui.back")
            assert boundary["observation"]["hero"]["cell"] == target
        assert boundary["phase"] == "player_ready", boundary["phase"]
        assert boundary["observation"]["ui"]["display"] == {"language": "zh", "fullscreen": False}
        shown = currency_text(boundary)
        assert any(node.get("text") == str(value) for node in shown), shown
        destination = safe_step(boundary)
        direction = direction_to(boundary, destination)
        # Two seconds for a counter update, one second after closing a trade window.
        # Keeping the original revision is deliberate; a refresh cannot mask the bug.
        time.sleep(2.3)
        later = client.state()
        assert later["observation"]["hero"] == boundary["observation"]["hero"]
        assert not currency_text(later), currency_text(later)
        response = client.request("action.execute", {"action": "move.step", "direction": direction},
                                  version=boundary["state_version"])
        result["evidence"] = {"boundary_version": boundary["state_version"], "later_version": later["state_version"],
                              "public_currency_text_at_boundary": shown, "currency_text_disappeared": True,
                              "hero_unchanged_during_fade": True, "original_version_accepted": bool(response.get("ok")),
                              "action_retries": 0}
        if expect_regression:
            assert later["state_version"] != boundary["state_version"]
            assert_rejected_without_movement(client, response, boundary["observation"]["hero"]["cell"])
        else:
            assert later["state_version"] == boundary["state_version"]
            assert response.get("ok") and response.get("status") == "completed", response
            moved = response["result"]
            assert moved["observation"]["hero"]["cell"] == destination
            reject_previous_revision(client, boundary, moved)
            result["evidence"]["real_move_invalidated_old_revision"] = True
            changes = [pick_currency(client, "gold", "gold", 17),
                       pick_currency(client, "energy crystal", "energy", 2)]
            if name == "shop-gold":
                changes.append(buy_ankh(client))
            result["evidence"]["real_currency_changes"] = changes
        result["ok"] = True
    except Exception as error:
        result.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
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
        (profile / "currency-boundary-result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--interfaces", default="2,0")
    parser.add_argument("--cases", default="entry-gold,entry-energy,shop-gold")
    parser.add_argument("--classpath-file", type=Path,
                        help="Runtime classpath file; defaults to the compiled test runtime")
    parser.add_argument("--expect-regression", action="store_true", help="Verify currency fades reject unchanged game state on an unfixed runtime")
    options = parser.parse_args()
    sizes = [int(value) for value in options.interfaces.split(",")]
    names = options.cases.split(",")
    assert all(size in (0, 1, 2) for size in sizes)
    assert all(name in {"entry-gold", "entry-energy", "shop-gold"} for name in names)
    root = Path(__file__).resolve().parents[4]
    classpath_file = options.classpath_file or root / "desktop-control/build/test-runtime-classpath.txt"
    classpath, runtime_id = freeze_runtime(root, classpath_file.read_text().strip())
    reports = [run_one(root, classpath, runtime_id, size, name, options.expect_regression)
               for size in sizes for name in names]
    suffix = "baseline" if options.expect_regression else "validation"
    output = root / ("desktop-control/build/currency-boundary-" + suffix + ".json")
    output.write_text(json.dumps(reports, indent=2) + "\n")
    if not all(report.get("ok") for report in reports):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
