#!/usr/bin/env python3
"""Original CustomNote workflows; English input/prose, Chinese windowed isolated GUI.

Only same-version postconditions read the test-only Notes assertion stream.
No save bytes, SQLite or assertion values select a control, item or target.
"""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid

from fixture_smoke import FixtureClient, assert_gui_environment, checkpoint, freeze_runtime, select_inventory_item
from low_frequency_smoke import act
from english_protocol_smoke import start_or_continue


CASES = ("text", "floor", "inventory", "item-type", "item-shortcut")


def controls(state, action="ui.activate"):
    return [candidate for candidate in state["actions"] if candidate["action"] == action]


def choose(client, label):
    state = client.state()
    matches = [candidate for candidate in controls(state) if candidate.get("label") == label]
    assert len(matches) == 1, {"missing_or_ambiguous_label": label, "choices": [(c.get("label"), c["control"]) for c in controls(state)]}
    return act(client, "ui.activate", control=matches[0]["control"])


def shown(state, text):
    return any(node.get("text") == text or node.get("label") == text for node in state["observation"]["ui"]["controls"])


def close_windows(client):
    for _ in range(8):
        state = client.state()
        if not state["observation"]["ui"]["modal"]:
            return state
        assert not controls(state, "ui.text"), "Complete the original text selection before leaving"
        act(client, "ui.back")
    raise AssertionError("Original windows did not close")


def open_notes(client):
    state = close_windows(client)
    journal = [node for node in state["observation"]["ui"]["controls"]
               if str(node.get("shortcut_action", "")).lower() == "journal" and node.get("enabled")]
    assert len(journal) == 1, journal
    opened = act(client, "ui.activate", control=journal[0]["id"])
    if not any(c.get("label") == "Add a Custom Note" for c in controls(opened)):
        opened = choose(client, "Adventuring Notes")
    assert any(c.get("label") == "Add a Custom Note" for c in controls(opened))
    return opened


def item_note_button(client):
    opened = act(client, "inventory.open", locator="equipment.weapon")
    nodes = {node["id"]: node for node in opened["observation"]["ui"]["controls"]}
    candidates = [candidate for candidate in controls(opened) if not candidate.get("label")
                  and nodes[candidate["control"]].get("role") == "button"]
    assert len(candidates) == 1, {"unlabelled_item_note_controls": candidates}
    # The original journal icon is the sole unlabelled button in this actual item menu.
    return act(client, "ui.activate", control=candidates[0]["control"])


def begin_creation(client, case, floor, item_appearance=None):
    close_windows(client)
    if case == "item-shortcut":
        return item_note_button(client), item_appearance
    open_notes(client)
    choose(client, "Add a Custom Note")
    option = {"text": "New Text Note", "floor": "New Dungeon Floor Note",
              "inventory": "New Inventory Item Note", "item-type": "New Item Type Note"}[case]
    selected = choose(client, option)
    if case == "floor":
        selected = choose(client, str(floor))
    elif case == "inventory":
        selected = select_inventory_item(client, lambda label: label.casefold() == "worn shortsword")
    elif case == "item-type":
        candidates = [candidate for candidate in controls(selected)
                      if candidate.get("label", "").casefold().endswith("potion")]
        if item_appearance is not None:
            candidates = [candidate for candidate in candidates if candidate["label"] == item_appearance]
        assert candidates, {"no_public_potion_appearance": controls(selected)}
        item_appearance = candidates[0]["label"]
        selected = act(client, "ui.activate", control=candidates[0]["control"])
    assert len(controls(selected, "ui.text")) == 1, selected["actions"]
    return selected, item_appearance


def edit_input(client, text, positive):
    state = client.state()
    fields = controls(state, "ui.text")
    assert len(fields) == 1, state["actions"]
    changed = act(client, "ui.text", control=fields[0]["control"], text=text)
    field = next(node for node in changed["observation"]["ui"]["controls"] if node.get("role") == "text_input")
    assert field["value"] == text
    return choose(client, "Confirm" if positive else "Cancel")


def notes_after(profile, state):
    """Assertion only: none of these returned values may choose a later command."""
    return checkpoint(profile, state["state_version"])["notes"]


def assert_note(profile, state, case, title, body, floor):
    values = notes_after(profile, state)
    records = values["records"]
    assert len(records) == 1, records
    note = records[0]
    expected_type = {"text": "TEXT", "floor": "DEPTH", "inventory": "SPECIFIC_ITEM",
                     "item-type": "ITEM_TYPE", "item-shortcut": "SPECIFIC_ITEM"}[case]
    assert note["type"] == expected_type and note["title"] == title and note["body"] == body, note
    assert note["depth"] == (floor if case == "floor" else 0), note
    if case in {"inventory", "item-shortcut"}:
        assert values["inventory_note_ids"]["equipment.weapon"] == note["id"]


def open_saved_note(client, title, item_shortcut):
    if item_shortcut:
        close_windows(client)
        opened = item_note_button(client)
    else:
        state = open_notes(client)
        nodes = {node["id"]: node for node in state["observation"]["ui"]["controls"]}
        entries = [candidate for candidate in controls(state, "ui.select") if nodes[candidate["control"]].get("role") == "entry"]
        assert entries, "The custom-note section must expose its original note entry"
        # This fixture creates exactly one note. Its original icon is the first entry
        # under the visible Custom section, before the floor-landmark sections.
        opened = act(client, entries[0]["action"], control=entries[0]["control"])
    assert shown(opened, title), {"expected_note_title": title, "controls": opened["observation"]["ui"]["controls"]}
    assert any(c.get("label") == "Edit Title" for c in controls(opened))
    return opened


def save(client):
    stable = close_windows(client)
    saved = act(client, "game.save")
    receipt = saved.get("persistence", {}).get("last_save") or saved.get("last_save")
    assert receipt and receipt["success"] is True and receipt["scope_id"] == stable["scope_id"], saved.get("persistence")
    assert any(row["success"] is True and row["scope_id"] == stable["scope_id"]
               for row in saved.get("persistence", {}).get("saves_during_request", [])), "An earlier last_save is not proof this game.save ran"
    return saved


def quit(client):
    close_windows(client)
    act(client, "app.quit")
    client.process.wait(timeout=25)
    assert client.process.returncode == 0
    client.stderr.close()
    client.trace.close()


def workflow(client, profile, case, initial):
    floor = initial["observation"]["hero"]["depth"]
    assert notes_after(profile, initial)["records"] == []
    _, appearance = begin_creation(client, case, floor)
    cancelled = edit_input(client, "Discarded test note", False)
    assert notes_after(profile, cancelled)["records"] == []
    # Specific-item selectors allocate a note ID before title confirmation in the
    # original code. Cancellation means no record added, not that every counter is unchanged.
    begin_creation(client, case, floor, appearance)
    title = "Test route note"
    created = edit_input(client, title, True)
    if case == "item-shortcut":
        created = open_saved_note(client, title, True)
    assert shown(created, title)
    assert_note(profile, created, case, title, "", floor)

    choose(client, "Edit Title")
    cancelled = edit_input(client, "Discarded title", False)
    assert_note(profile, cancelled, case, title, "", floor)
    choose(client, "Edit Title")
    title = "Test route note revised"
    renamed = edit_input(client, title, True)
    assert shown(renamed, title)
    assert_note(profile, renamed, case, title, "", floor)

    choose(client, "Add Text")
    cancelled = edit_input(client, "Discarded body", False)
    assert_note(profile, cancelled, case, title, "", floor)
    choose(client, "Add Text")
    body = "Follow the known route.\nKeep the next door clear."
    written = edit_input(client, body, True)
    assert shown(written, body)
    assert_note(profile, written, case, title, body, floor)
    choose(client, "Edit Text")
    cancelled = edit_input(client, "Discarded replacement", False)
    assert_note(profile, cancelled, case, title, body, floor)
    choose(client, "Edit Text")
    body = "Check this route before descending.\nTest note text only."
    edited = edit_input(client, body, True)
    assert shown(edited, body)
    assert_note(profile, edited, case, title, body, floor)

    choose(client, "Delete")
    cancelled = choose(client, "Cancel")
    assert_note(profile, cancelled, case, title, body, floor)
    saved = save(client)
    assert_note(profile, saved, case, title, body, floor)
    return {"floor": floor, "title": title, "body": body, "public_item_appearance": appearance,
            "cancelled_creation_without_record": True, "title_cancel_and_confirm": True,
            "multiline_body_cancel_and_confirm": True, "delete_cancel_preserves_record": True,
            "note_type_and_specific_item_binding_checked": True, "original_save_receipt": saved.get("persistence")}


def run_one(root, classpath, runtime_id, case):
    profile = root / "desktop-control/build/fixtures" / ("notes-" + case + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "notes:" + case]
    report = {"case_id": "notes." + case.replace("-", "_"), "test_fixture": True, "counts_as_win": False,
              "profile": str(profile.relative_to(root)), "runtime_id": runtime_id,
              "preparation": "ordinary Warrior; no game-stat, map, item or note injection"}
    client = FixtureClient(command, profile, verify_gui=True)
    try:
        hello = client.request("protocol.info"); assert hello["ok"]
        report["build_id"] = hello["result"]["build_id"]
        initial, _ = start_or_continue(client)
        report["scope_id"] = initial["scope_id"]
        report["gui_environment"] = assert_gui_environment(profile, initial)
        data = workflow(client, profile, case, initial)
        quit(client)
        client = FixtureClient(command, profile, verify_gui=True)
        assert client.request("protocol.info")["ok"]
        restored, _ = start_or_continue(client, resume=True)
        assert restored["scope_id"] == report["scope_id"]
        assert_note(profile, restored, case, data["title"], data["body"], data["floor"])
        opened = open_saved_note(client, data["title"], case == "item-shortcut")
        assert shown(opened, data["body"])
        choose(client, "Delete")
        deleted = choose(client, "Confirm")
        assert notes_after(profile, deleted)["records"] == []
        final = save(client)
        assert notes_after(profile, final)["records"] == []
        quit(client)
        report.update(ok=True, cli_language="en", evidence={**data, "same_scope_fresh_jvm_note_restored": True,
                      "restored_note_opened_from_original_entry": True, "delete_confirm_removes_record": True,
                      "deletion_saved_normally": True})
    except Exception as error:
        report.update(ok=False, error_type=type(error).__name__, error=str(error)[:5000], traceback=traceback.format_exc())
    finally:
        if client.process.poll() is None:
            try:
                if report.get("ok") is False:
                    # A failed projection may no longer provide observation/actions.
                    # Preserve the primary failure and let the original EOF lifecycle stop.
                    if not client.process.stdin.closed: client.process.stdin.close()
                    client.process.wait(timeout=35)
                    report["failure_cleanup_exit_code"] = client.process.returncode
                else:
                    quit(client)
            except Exception as error:
                report.update(ok=False, cleanup_error=str(error)[:2000])
                if client.process.poll() is None:
                    client.process.terminate(); client.process.wait(timeout=10)
        if not client.stderr.closed: client.stderr.close()
        if not client.trace.closed: client.trace.close()
        (profile / "notes-scenario-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="text")
    args = parser.parse_args()
    names = args.cases.split(",")
    assert all(name in CASES for name in names)
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    reports = [run_one(root, classpath, runtime_id, name) for name in names]
    summary = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id,
               "total": len(reports), "passed": sum(row["ok"] for row in reports), "results": reports}
    (root / "desktop-control/build/fixtures" / runtime_id / "notes-scenarios-results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    if not all(row["ok"] for row in reports): raise SystemExit(1)


if __name__ == "__main__":
    main()
