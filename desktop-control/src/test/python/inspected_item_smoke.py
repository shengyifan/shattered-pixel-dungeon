#!/usr/bin/env python3
"""Real original item/container windows; only public CLI selects/opens/cancels them."""
import json
from pathlib import Path
import traceback
import uuid

from fixture_smoke import FixtureClient, reach_game, close_choices, freeze_runtime, checkpoint, run_one
from low_frequency_smoke import act

CASES = ["known", "unknown", "old-body", "floor-sale", "containers-a", "containers-b"]


def body(state):
    return "\n".join(node.get("text", "") for node in state["observation"]["ui"]["controls"])


def inspected(state, known):
    ui = state["observation"]["ui"]
    item = ui.get("inspected_item")
    assert item and set(item) == {"control", "level_known"}, item
    assert item["level_known"] is known, item
    owners = [node for node in ui["controls"] if node.get("id") == item["control"]]
    assert len(owners) == 1 and owners[0]["role"] == "window" and not owners[0].get("parent"), owners
    text = body(state)
    assert ("This deals _" if known else "This typically deals _") in text, text
    assert ("This typically deals _" if known else "This deals _") not in text, text
    return item, text


def run(root, cp, runtime_id, name):
    profile = root / "desktop-control/build/fixtures" / ("inspect-" + name + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", cp,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", "inspect:" + name]
    client = FixtureClient(command, profile, verify_gui=True)
    report = {"test_fixture": True, "counts_as_win": False, "fixture": "inspect:" + name,
              "profile": str(profile), "runtime_id": runtime_id, "cli_language": "en", "gui_language": "CHI_SMPL", "windowed": True}
    try:
        assert client.request("protocol.info")["ok"]
        state = reach_game(client, "DUELIST")
        if name in {"known", "unknown", "old-body"}:
            opened = act(client, "inventory.open", locator="equipment.weapon")
            projection, text = inspected(opened, name == "known")
            if name == "known":
                inventory = next(item for item in opened["observation"]["inventory"] if item["locator"] == "equipment.weapon")
                assert inventory["level_known"] is True and inventory["curse_known"] is False, inventory
            for _ in range(5):
                queried = client.state()
                assert inspected(queried, name == "known") == (projection, text)
                assert client.request("actions.list")["ok"]
            if name == "old-body":
                inventory = next(item for item in queried["observation"]["inventory"] if item["locator"] == "equipment.weapon")
                assert inventory["level_known"] is True, inventory
                assert checkpoint(profile, queried["state_version"])["inspection"]["identified_after_body"]
                act(client, "ui.back")
                reopened = act(client, "inventory.open", locator="equipment.weapon")
                inspected(reopened, True)
                assert body(reopened) != text
            act(client, "ui.back")
            assert client.state()["observation"]["ui"].get("inspected_item") is None
            report["evidence"] = {"final_response_has_bound_knowledge": True, "repeated_queries": 5,
                                  "old_body_preserved_after_native_identify": name == "old-body",
                                  "curse_knowledge_independent": name == "known"}
        else:
            entities = [e for e in state["observation"]["visible_entities"] if e["kind"] in {"item", "container"}]
            signatures = []
            expected = 2 if name == "floor-sale" else 6
            assert len(entities) == expected, entities
            for entity in sorted(entities, key=lambda e: e.get("name", e.get("item", {}).get("name", ""))):
                opened = act(client, "cell.select", cell=entity["cell"], mode="examine")
                if name == "floor-sale":
                    # The public entity's knowledge chooses the assertion, not a hidden heap peek.
                    known = entity.get("item", {}).get("level_known")
                    assert isinstance(known, bool), entity
                    inspected(opened, known)
                else:
                    assert opened["observation"]["ui"].get("inspected_item") is None
                    assert "This typically deals" not in body(opened)
                signatures.append({"name": entity.get("name", entity.get("item", {}).get("name")), "text": body(opened),
                                   "inspected_item": opened["observation"]["ui"].get("inspected_item")})
                act(client, "ui.back")
            report["evidence"] = {"native_windows": expected, "public_signatures": signatures}
        report.update(ok=True, every_response_game_prose_checked=True)
    except Exception as error:
        report.update(ok=False, error=str(error), traceback=traceback.format_exc())
    finally:
        try:
            close_choices(client, discard=True)
            client.finish()
            assert client.process.poll() == 0
        except Exception as error:
            report.update(ok=False, cleanup_error=str(error))
            if client.process.poll() is None:
                client.process.terminate()
                client.process.wait(timeout=10)
        report["gui_postconditions_checked"] = client.gui_postconditions_checked
        client.trace.close()
        (profile / "fixture-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return report


def main():
    root = Path(__file__).resolve().parents[4]
    cp, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    results = [run(root, cp, runtime_id, case) for case in CASES]
    results += [run_one(root, cp, "weapon:Spear", runtime_id), run_one(root, cp, "weapon:Spear:empty", runtime_id)]
    a, b = results[4], results[5]
    if a["ok"] and b["ok"]:
        assert a["evidence"]["public_signatures"] == b["evidence"]["public_signatures"], "Hidden container worlds changed public inspection"
    summary = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id,
               "passed": sum(r["ok"] for r in results), "total": len(results), "results": results}
    target = root / "desktop-control/build/fixtures" / runtime_id / "inspected-item-report.json"
    target.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"report": str(target), "passed": summary["passed"], "total": summary["total"]}), flush=True)
    assert all(r["ok"] for r in results)


if __name__ == "__main__":
    main()
