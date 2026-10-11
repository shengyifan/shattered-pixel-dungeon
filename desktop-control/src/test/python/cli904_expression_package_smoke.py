#!/usr/bin/env python3
"""Actual packaged ARM64 JVM with a separate, explicit test-only scenario agent.

This is instrumented fixture evidence, not an ordinary launcher acceptance or a
playthrough. Native windows, recipes, item sprites, terrain presentation and audio
run in the unmodified production app. Every protocol choice uses its current
advertised binding; the driver never opens a save or a private audit database.
Build crashAgentJar and testClasses before invoking; no Gradle runs are implicit.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import time
import traceback
import uuid
import zipfile

from controller_package_smoke import ControllerClient, reach_warrior, write_json
from package_english_smoke import environment as clean_environment
from slow_quit_package_smoke import instrumentable_runtime
from spdctl_client import decode_wire_response, validate_intent


AGENT = "Cli904ExpressionFixtureAgent"
HELPER = "com/shatteredpixel/shatteredpixeldungeon/control/desktop/Cli904ExpressionScenarioFixtures"
PAGES = tuple("item_" + name + "_holder" for name in
              ("seed", "stone", "food", "potion", "scroll", "bomb", "missile", "elixir", "spell"))
CATALOG = tuple("item_" + name + "_holder" for name in ("weapon", "potion", "mob", "document"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_agent(root, output):
    """Copy only this agent/helper and ASM; never add host production classes."""
    jars = list((root / "desktop-control/build/libs").glob("test-only-engine-boundaries-*.jar"))
    assert len(jars) == 1, "Run :desktop-control:crashAgentJar first"
    agent_classes = list((root / "desktop-control/build/classes/java/crashAgent").glob(AGENT + "*.class"))
    tests = root / "desktop-control/build/classes/java/test"
    helper_classes = list((tests / Path(HELPER).parent).glob(Path(HELPER).name + "*.class"))
    assert agent_classes and helper_classes, "Compile the CLI904 test-only agent and scenario helper first"
    result = output / "test-only-cli904-expression.jar"
    with zipfile.ZipFile(result, "w", zipfile.ZIP_DEFLATED) as target:
        target.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: " + AGENT + "\n\n")
        with zipfile.ZipFile(jars[0]) as source:
            for name in source.namelist():
                if name.startswith("org/objectweb/asm/") and not name.endswith("/"):
                    target.writestr(name, source.read(name))
        for path in agent_classes:
            target.write(path, path.name)
        for path in helper_classes:
            target.write(path, path.relative_to(tests).as_posix())
    return result


def data(client):
    decoded = decode_wire_response(client.current)
    assert decoded.is_observation and not decoded.is_error, client.current
    return decoded.data


def action(client, op, **values):
    frame = decode_wire_response(client.current)
    validate_intent(frame, {"op": op, "rev": frame.frame["rev"], **values})
    # No action retry. A rejected/uncertain request fails this fixture with evidence.
    return client.action(op, **values)


def nodes(client):
    return data(client).get("ui", {}).get("nodes", [])


def activate(client, node):
    operations = [value for value in node.get("ops", []) if value.get("op") in {"click", "select"}]
    assert len(operations) == 1, node
    operation = operations[0]
    action(client, operation["op"], ctl=operation.get("ctl", node["id"]))


def icon_node(client, symbol):
    matches = [node for node in nodes(client) if node.get("icon", {}).get("symbol") == symbol
               and "selected" in node]
    assert len(matches) == 1, {"symbol": symbol, "matches": matches, "nodes": nodes(client)}
    return matches[0]


def close(client):
    for _ in range(5):
        if not data(client).get("ui", {}).get("modal"):
            return
        action(client, "back")
    raise AssertionError("Fixture modal stack did not close")


def stage(client, output, mode):
    token = uuid.uuid4().hex + "|" + mode
    temporary = output / "fixture.command.tmp"
    temporary.write_text(token, encoding="utf-8")
    temporary.replace(output / "fixture.command")
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        failure = output / "fixture.failure.json"
        if failure.exists():
            raise AssertionError(json.loads(failure.read_text()))
        done = output / "fixture.done.json"
        if done.exists():
            value = json.loads(done.read_text())
            if value.get("command") == token:
                assert "/Contents/runtime/" in value["java_home"], value
                assert "/Contents/app/" in value["production_code"], value
                assert "build/classes" not in value["production_code"], value
                # The fixture changes scenes out of band. info intentionally
                # returns game.latest rather than observing the injected scene;
                # its header scope need not describe the new native scene yet.
                # Select only handles already disclosed by public protocol data.
                info = client.unwrap(client.request({"op": "info"}))
                write_json(output / (mode + "-discovery-info.json"), info)
                assert info.get("v") == 9 and info.get("st") == "completed" and "err" not in info, info
                assert isinstance(info.get("s"), str) and info["s"], info
                assert isinstance(info.get("rev"), str) and info["rev"], info
                assert info.get("data", {}).get("request_prefix") == client.prefix, info
                assert info["data"].get("cli_version") == client.hello["data"]["cli_version"], info
                decode_wire_response(info)
                client.install(info, "info")
                value["discovery"] = {key: info[key] for key in ("id", "s", "rev")}
                if mode == "recipes-nohero":
                    selected_scope = info["data"].get("menu_scope_id")
                    scope_source = "public info.data.menu_scope_id"
                elif mode == "restore-game":
                    selected_scope = client.fixture_public_run_scope
                    scope_source = "public run observation recorded after reach_warrior"
                else:
                    selected_scope = info["s"]
                    scope_source = "public info header scope"
                assert isinstance(selected_scope, str) and selected_scope, {
                    "mode": mode, "missing_public_scope": scope_source, "info": info}
                value["out_of_band_fixture_scope_selection"] = {
                    "scope": selected_scope, "source": scope_source,
                    "info_refreshes_native_scene": False}
                reply = client.unwrap(client.request({"op": "state", "s": selected_scope}))
                assert "err" not in reply, reply # Stop; never retry a mismatched query.
                client.install(reply, "state")
                decode_wire_response(client.current)
                write_json(output / (mode + "-setup.json"), value)
                return value
        assert client.process.poll() is None, "Packaged fixture process exited"
        time.sleep(.025)
    raise TimeoutError("Test-only command did not complete: " + mode)


def frames(client, output, label, verify):
    records = []
    for name, query in (("play", {"view": "play"}), ("full", {"view": "full"}), ("src", {"src": True})):
        reply = client.unwrap(client.request({"op": "state", **query}))
        original = copy.deepcopy(reply)
        decoded = decode_wire_response(reply)
        assert not decoded.is_error and decoded.is_observation
        assert original == reply and decoded.raw == original, "Decoder mutated fixture wire evidence"
        verify(decoded.data)
        write_json(output / (label + "-" + name + ".json"), reply)
        client.install(reply, "state")
        records.append({"view": name, "id": reply["id"], "scope": reply["s"], "revision": reply["rev"],
                        "node_templates": len(reply["data"].get("ui", {}).get("node_templates", []))})
    return records


def grid_frame(client, output, label, *, catalog):
    """Validate only this frame's displayed evidence; offscreen controls may remain selectable."""
    write_json(output / (label + ".json"), client.current)
    entries = [node for node in nodes(client) if node.get("role") == "entry"]
    displayed = [node for node in entries if node.get("icon")]
    undisplayed = [node for node in entries if not node.get("icon")]
    assert entries and displayed, {"label": label, "entries": len(entries), "displayed": len(displayed)}
    for node in displayed:
        assert isinstance(node["icon"].get("symbol"), str) and node["icon"]["symbol"], node
        status = node.get("shown", {}).get("catalog_state")
        if status is not None:
            assert status in {"undiscovered", "discovered", "unread", "read"}, node
            if status == "undiscovered":
                assert node["icon"] == {"symbol": "catalog_undiscovered", "placeholder": True}, node
            else:
                assert node["icon"]["symbol"] != "catalog_undiscovered", node
        if node["icon"]["symbol"] == "catalog_undiscovered":
            assert node["icon"].get("placeholder") is True, node
    # The native catalog state is gated by the same current main image binding.
    # An icon can be fully visible while the larger background is clipped, so
    # its absence on an icon-bearing edge entry is not itself a failure.
    assert all("catalog_state" not in node.get("shown", {}) for node in undisplayed), undisplayed
    statuses = [node["shown"]["catalog_state"] for node in displayed
                if "catalog_state" in node.get("shown", {})]
    if catalog:
        assert statuses, {"label": label, "missing_visible_catalog_state": displayed}
    else:
        assert not statuses, {"notes_must_not_invent_catalog_state": statuses}
    record = {"frame": {key: client.current.get(key) for key in ("id", "s", "rev")},
              "entries": len(entries), "entries_with_displayed_icon": len(displayed),
              "entries_without_displayed_icon": len(undisplayed),
              "catalog_states": {status: statuses.count(status) for status in set(statuses)},
              "current_displayed_ids": [node["id"] for node in displayed],
              "current_unpictured_ids": [node["id"] for node in undisplayed]}
    return record


def catalog_scroll(client, output, symbol):
    segments = [grid_frame(client, output, "catalog-" + symbol + "-top", catalog=True)]
    first = segments[0]
    scrolls = [node for node in nodes(client) if node.get("role") == "scroll"
               and any(op.get("op") == "scroll" for op in node.get("ops", []))]
    assert len(scrolls) == 1, scrolls
    scroll = scrolls[0]
    maximum = max(0, scroll["content_height"] - scroll["viewport_height"])
    transitions = {"scroll_exercised": False}
    if first["current_unpictured_ids"] and maximum > 0:
        action(client, "scroll", ctl=scroll["id"], y=maximum)
        last = grid_frame(client, output, "catalog-" + symbol + "-bottom", catalog=True)
        segments.append(last)
        entered = sorted(set(first["current_unpictured_ids"]) & set(last["current_displayed_ids"]))
        left = sorted(set(first["current_displayed_ids"]) & set(last["current_unpictured_ids"]))
        assert entered, {"symbol": symbol, "no_previously_unpictured_entry_entered_view": segments}
        # This is an evidence comparison only. Never reuse an old node to choose
        # an operation or copy its old icon into the new observation.
        transitions = {"scroll_exercised": True, "entered_displayed_view": entered,
                       "left_displayed_view": left}
        current_scrolls = [node for node in nodes(client) if node.get("role") == "scroll"
                           and any(op.get("op") == "scroll" for op in node.get("ops", []))]
        assert len(current_scrolls) == 1, current_scrolls
        action(client, "scroll", ctl=current_scrolls[0]["id"], y=0)
        returned = grid_frame(client, output, "catalog-" + symbol + "-returned", catalog=True)
        segments.append(returned)
        assert set(entered) & set(returned["current_unpictured_ids"]), {
            "symbol": symbol, "out_of_view_entry_retained_old_icon": returned}
    return {"symbol": symbol, "segments": segments, **transitions}


def journal(client, output):
    stage(client, output, "journal")
    report = {"pages": [], "catalog": [], "grid": []}
    for symbol in PAGES:
        activate(client, icon_node(client, symbol))
        page_nodes = [node for node in nodes(client) if node.get("icon", {}).get("symbol") in PAGES]
        assert len(page_nodes) == len(PAGES), page_nodes
        selected = [node["icon"]["symbol"] for node in page_nodes if node.get("selected") is True]
        assert selected == [symbol], selected
        assert all(type(node.get("selected")) is bool for node in page_nodes)
        report["pages"].append(symbol)
        write_json(output / ("journal-" + symbol + ".json"), client.current)
    activate(client, icon_node(client, "catalog"))
    for symbol in CATALOG:
        activate(client, icon_node(client, symbol))
        selected = [node["icon"]["symbol"] for node in nodes(client)
                    if node.get("icon", {}).get("symbol") in CATALOG and node.get("selected")]
        assert selected == [symbol], selected
        report["catalog"].append(catalog_scroll(client, output, symbol))
        write_json(output / "journal-progress.json", report)
    assert any(row["scroll_exercised"] and row["left_displayed_view"] for row in report["catalog"]), report
    activate(client, icon_node(client, "journal"))
    notes = grid_frame(client, output, "journal-notes-grid", catalog=False)
    entries = [node for node in nodes(client) if node.get("role") == "entry"
               and node.get("icon", {}).get("symbol") and node.get("ops")]
    assert entries, "No currently displayed selectable Notes grid item"
    chosen = entries[0]
    report["grid"].append({**notes, "selected_icon": chosen["icon"],
                           "selected_secondary_icon": chosen.get("secondary_icon"), "ops": chosen["ops"]})
    activate(client, chosen)
    assert any(node.get("text") or node.get("label") for node in nodes(client)), nodes(client)
    write_json(output / "journal-grid-selected.json", client.current)
    close(client)
    return report


def recipe_verify(expected, *, no_hero=False, alchemy=False):
    def verify(value):
        rows = value.get("ui", {}).get("nodes", [])
        input_rows = [row for row in rows if "ingredient_sufficient" in row.get("shown", {})]
        aggregate = [row for row in rows if "ingredients_sufficient" in row.get("shown", {})]
        if no_hero:
            assert "hero" not in value, value.keys()
            assert not input_rows and not aggregate, (input_rows, aggregate)
            assert any(row.get("label") or row.get("text") for row in rows), rows
        else:
            actual = [row["shown"]["ingredient_sufficient"] for row in input_rows]
            assert actual == expected, {"actual": actual, "expected": expected, "rows": rows}
            assert len(aggregate) == 8, aggregate # Four native arrows and four outputs.
            arrows = [row for row in aggregate if row.get("icon", {}).get("symbol") == "arrow"]
            assert len(arrows) == 4, arrows
            for row, enough in zip(arrows, expected):
                assert row["shown"]["ingredients_sufficient"] is enough
                permitted = bool(row.get("ops"))
                assert permitted == (alchemy and enough), row
            assert all(type(row["shown"]["ingredients_sufficient"]) is bool for row in aggregate)
    return verify


def recipe_cases(client, output):
    report = []
    for mode, expected in (("recipes-unknown", [True, True, False, False]),
                           ("recipes-known", [True, True, False, True]),
                           ("recipes-alchemy", [True, True, False, True])):
        stage(client, output, mode)
        views = frames(client, output, mode, recipe_verify(expected, alchemy=mode.endswith("alchemy")))
        record = {"mode": mode, "views": views,
                  "identity_control": "PotionOfPurity to PotionOfCleansing; native knowledge retained"}
        if mode.endswith("alchemy"):
            arrow = next(row for row in nodes(client) if row.get("icon", {}).get("symbol") == "arrow"
                         and row.get("shown", {}).get("ingredients_sufficient") is True and row.get("ops"))
            activate(client, arrow) # Native QuickRecipe fills the real AlchemyScene inputs.
            assert not data(client)["ui"].get("modal"), data(client)["ui"]
            assert data(client)["ui"].get("scene") == "AlchemyScene", data(client)["ui"]
            write_json(output / "native-alchemy-populated.json", client.current)
            record["native_fill_callback"] = True
            action(client, "back")
        else:
            close(client)
        report.append(record)
    stage(client, output, "recipes-nohero")
    report.append({"mode": "recipes-nohero", "views": frames(client, output, "recipes-nohero",
                                                               recipe_verify([], no_hero=True))})
    close(client)
    stage(client, output, "restore-game")
    return report


def ritual(client, output):
    stage(client, output, "ritual")
    def verify(value):
        dungeon_map = value["map"]
        cells = dungeon_map.get("decoded_cells", dungeon_map.get("cells", []))
        marked = [cell for cell in cells if cell.get("name") == "Ritual marker"]
        assert len(marked) == 9, {"marker_cells": marked, "map": dungeon_map}
        assert all("four corners" in cell.get("desc", "") for cell in marked), marked
        assert all("ritualPos" not in cell and "ritual_pos" not in cell for cell in marked)
    return frames(client, output, "ritual-default-map", verify)


def inventory_control(client):
    current = data(client)
    controls = {node["id"] for node in current["ui"]["nodes"]
                if str(node.get("shortcut", "")).casefold() == "inventory"}
    choices = [op for op in current["acts"] if op.get("op") == "click"
               and op.get("ctl") in controls and "click" in op.get("gestures", ["click"])]
    assert len(choices) == 1, {"current_inventory_controls": choices}
    return choices[0]["ctl"]


def crystals(client, output):
    report = []
    for phase in ("intact", "flawed", "cracked"):
        stage(client, output, "crystal-" + phase)
        items = data(client)["inv"]
        assert len(items) == 1, items
        # The repaired producer is the real inventory ItemSlot. WndInfoItem's
        # IconTitle follows a different display path and cannot substitute for it.
        # Large native layouts can already show their InventoryPane. Detect that
        # using the current UI owner binding, never using glow as the predicate
        # (missing glow is precisely what this fixture must continue to reject).
        owner_loc = items[0]["loc"]
        initial_slots = [node for node in nodes(client)
                         if (node.get("subject") or {}).get("kind") == "item"
                         and node["subject"].get("loc") == owner_loc]
        opened_by_driver = not bool(initial_slots)
        if opened_by_driver:
            action(client, "click", ctl=inventory_control(client))
        opened = data(client)
        assert not opened["ui"].get("item_prompt"), "Expected inventory inspection, not an item selector"
        modal_bag = bool(opened["ui"].get("modal"))
        def verify(value):
            inventory = value.get("inv", [])
            assert len(inventory) == 1, inventory
            owner = inventory[0]
            glow = owner.get("shown", {}).get("glow")
            assert glow and glow.get("kind") == "crystal_condition" and glow.get("stage") == phase, {
                "missing_inventory_itemslot_glow": owner, "expected_stage": phase}
            # Compaction intentionally moves duplicate ItemSlot shown facts to
            # the same-frame inventory owner. Require the actual UI binding,
            # resolve against this response, and do not demand duplicate glow.
            bound = [node for node in value.get("ui", {}).get("nodes", [])
                     if node.get("subject", {}).get("kind") == "item"
                     and node["subject"].get("loc") == owner["loc"]]
            assert bound, {"missing_current_inventory_slot_subject": owner["loc"], "nodes": value["ui"]["nodes"]}
            for node in bound:
                own_glow = node.get("shown", {}).get("glow")
                if own_glow is not None:
                    assert own_glow.get("kind") == "crystal_condition" and own_glow.get("stage") == phase, node
        report.append({"phase": phase, "native_inventory_displayed": True,
                       "inventory_initially_displayed": bool(initial_slots),
                       "inventory_opened_by_driver": opened_by_driver,
                       "modal_bag": modal_bag, "same_frame_ui_inventory_binding": True,
                       "views": frames(client, output, "crystal-" + phase, verify)})
        if opened_by_driver:
            if modal_bag:
                close(client)
            else:
                # Undo only the native panel toggle performed by this driver.
                action(client, "click", ctl=inventory_control(client))
    return report


def events(client, after=0):
    found, until = [], None
    while True:
        query = {"op": "events", "after": after, "limit": 100}
        if until is not None: query["until"] = until
        reply = client.unwrap(client.request(query))
        assert "err" not in reply, reply
        page = reply["data"]
        found.extend(page["items"])
        until = page["until"]
        if page["end"]: return found, until
        after = page["next"]


def audio(client, output):
    report = []
    _, cursor = events(client)
    for mode, wanted in (("audio-alarm", 1), ("audio-disabled", 0), ("audio-zero", 0), ("audio-alarm", 1)):
        native = stage(client, output, mode)
        rows, cursor = events(client, cursor)
        alarms = [row for row in rows if row.get("kind") == "game.audio"]
        if wanted and not alarms:
            raise RuntimeError("AUDIO_NATIVE_PLAYBACK_UNCONFIRMED: no verified alarm occurrence; "
                               "native device/backend availability must be investigated, not counted as passed")
        assert len(alarms) == wanted, {"mode": mode, "alarms": alarms}
        for event in alarms:
            body = event["data"]
            assert body.get("format") == "gameplay_audio_occurrence_v1"
            assert body.get("kind") == "alarm" and body.get("basis") == "native_playback"
            assert set(body) in ({"format", "kind", "basis", "at"},
                                 {"format", "kind", "basis", "occurred_at"}), body
        report.append({"mode": mode, "events": alarms, "native": native})
    write_json(output / "native-audio-events.json", report)
    return report


def main():
    root = Path(__file__).resolve().parents[4]
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--cases", default="ritual,journal,recipes,crystal,audio")
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    nonce = uuid.uuid4().hex
    output = root / "desktop-control/build/fixtures" / ("cli904-expression-" + nonce)
    output.mkdir(parents=True)
    (output / "fixture.nonce").write_text(nonce)
    report = {"test_fixture": True, "counts_as_win": False, "instrumented_actual_package": True,
              "ordinary_package_smoke": False, "save_or_game_state_injection": True,
              "personal_profile_or_private_audit_reads": False, "status": "running",
              "bundle": str(bundle), "artifacts": str(output), "cases": {}}
    driver = Path(__file__).read_bytes()
    frozen_driver = output / "frozen-driver.py"
    frozen_driver.write_bytes(driver)
    report["driver"] = {"source": str(Path(__file__).resolve()), "frozen_source": str(frozen_driver),
                        "sha256": hashlib.sha256(driver).hexdigest(), "executed_from_frozen_copy": False}
    write_json(output / "test_fixture.json", report)
    agent = make_agent(root, output)
    report["agent_sha256"] = digest(agent)
    report["instrumentable_runtime"] = instrumentable_runtime(bundle)
    for jar in (bundle / "Contents/app").glob("*.jar"):
        with zipfile.ZipFile(jar) as source:
            assert not any(AGENT in name or HELPER in name for name in source.namelist()), jar
    env = clean_environment()
    env["SPD_CLI904_FIXTURE_ROOT"] = str(output)
    env["SPD_CLI904_FIXTURE_NONCE"] = nonce
    env["JAVA_TOOL_OPTIONS"] = '"-javaagent:' + str(agent) + '=' + str(output) + '"'
    client = None
    try:
        client = ControllerClient(bundle / "Contents/MacOS/spdctl", output / "profile", output / "controller", env)
        report["controller_pid"] = client.process.pid
        report["production_jar_sha256"] = digest(client.codec_jar)
        reach_warrior(client)
        client.fixture_public_run_scope = client.scope
        report["initial_public_run_binding"] = {
            key: client.current.get(key) for key in ("id", "s", "rev")}
        runners = {"ritual": ritual, "journal": journal, "recipes": recipe_cases, "crystal": crystals, "audio": audio}
        for name in args.cases.split(","):
            assert name in runners, name
            report["cases"][name] = runners[name](client, output)
            write_json(output / "result.json", report)
        close(client)
        action(client, "quit")
        client.finish_exit()
        report["controller_exit_code"] = client.process.returncode
        agent_pids = [int(path.stem.split("-")[1]) for path in output.glob("agent-*.ready")]
        report["agent_processes"] = []
        for pid in agent_pids:
            try: os.kill(pid, 0)
            except ProcessLookupError: alive = False
            else: alive = True
            report["agent_processes"].append({"pid": pid, "exited": not alive})
        assert report["agent_processes"] and all(row["exited"] for row in report["agent_processes"])
        report["status"] = "passed"
    except Exception as error:
        report.update(status="blocked" if "AUDIO_NATIVE_PLAYBACK_UNCONFIRMED" in str(error) else "failed",
                      error=str(error), traceback=traceback.format_exc())
        raise
    finally:
        if client:
            client.cleanup()
            report["controller_exit_code"] = client.process.returncode
        process_states = []
        for path in output.glob("agent-*.ready"):
            pid = int(path.stem.split("-")[1])
            try: os.kill(pid, 0)
            except ProcessLookupError: alive = False
            else: alive = True
            process_states.append({"pid": pid, "exited_after_cleanup": not alive})
        report["all_agent_processes_after_cleanup"] = process_states
        write_json(output / "result.json", report)
        print(json.dumps({"status": report["status"], "artifact": str(output)}), flush=True)


if __name__ == "__main__":
    main()
