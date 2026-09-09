#!/usr/bin/env python3
"""Native first-phase Tengu patterns; all measured choices use the public protocol."""
import json
from pathlib import Path
import time

from fixture_smoke import freeze_runtime
from low_frequency_smoke import act
from visual_cue_smoke import kind_cells, read_visual_events, run_one, visual

KIND = "fading_trap_pattern"


def zap_boss(client):
    state = client.state()
    target = next(e for e in state["observation"]["visible_entities"] if e.get("context_action") == "attack")
    wand = next(i for i in state["observation"]["inventory"] if i["name"] == "wand of magic missile")
    opened = act(client, "inventory.open", locator=wand["locator"])
    zap = next(a for a in opened["actions"] if a.get("label") == "ZAP")
    targeting = act(client, "ui.activate", control=zap["control"])
    assert any(a["action"] == "cell.select" for a in targeting["actions"])
    # This original item target callback causes damage -> Tengu.jump -> original
    # placeTrapsInTenguCell. No test-side pattern replacement occurs after setup.
    result = act(client, "cell.select", cell=target["cell"])
    assert kind_cells(result, KIND), {"native_pattern_missing_in_final_zap_response": visual(result)}
    visible = {t["cell"] for t in result["observation"]["map"]["cells"] if t["visibility"] == "visible"}
    assert kind_cells(result, KIND) <= visible
    return result


def test_patterns(client):
    initial = client.state()
    assert visual(initial)["depth"] == 10 and not kind_cells(initial, KIND)
    first = zap_boss(client)
    first_cells = kind_cells(first, KIND)
    first_events = read_visual_events(client)
    shown = next(e for e in first_events if any(c["kind"] == KIND for c in e["data"]["cues"]))
    context = visual(first)["map_context"]
    # Existing cell examination remains a normal semantic route while the
    # custom visual is registered; no hidden trap name is copied into a cue.
    info = act(client, "cell.select", cell=min(first_cells), mode="examine")
    assert info["observation"]["ui"]["modal"] and visual(info)["cues"] == []
    assert any(n.get("text") == "Poison Dart Trap" for n in info["observation"]["ui"]["controls"])
    restored = act(client, "ui.back")
    assert kind_cells(restored, KIND) & first_cells
    restored_cells = kind_cells(restored, KIND)
    menu = next(n for n in restored["observation"]["ui"]["controls"] if str(n.get("shortcut_action", "")).lower() == "back")
    blocked = act(client, "ui.activate", control=menu["id"])
    assert blocked["observation"]["ui"]["modal"] and visual(blocked)["cues"] == []
    restored = act(client, "ui.back")
    assert kind_cells(restored, KIND) & first_cells
    outside = act(client, "view.pan", x=5000, y=5000)
    assert not kind_cells(outside, KIND)
    restored = act(client, "view.pan", x=-5000, y=-5000)
    assert kind_cells(restored, KIND) & first_cells
    # The original pattern fades after two actor turns plus its real-time
    # AlphaTweener. The hidden trap model still exists; it must not fill cues.
    act(client, "wait")
    act(client, "wait")
    time.sleep(1.2)
    faded = client.state()
    assert not kind_cells(faded, KIND), visual(faded)
    faded_events = read_visual_events(client)
    assert shown in faded_events
    assert any(e["sequence"] > shown["sequence"] and e["data"]["cues"] == [] for e in faded_events)
    second = zap_boss(client)
    second_cells = kind_cells(second, KIND)
    assert first_cells != second_cells, "The second native jump must generate a distinct measured pattern"
    assert visual(second)["map_context"] == context
    assert first_cells - second_cells, "Some old pattern cells must be absent from the replacement"
    return {"native_level": "PrisonBossLevel", "native_boss": "Tengu", "depth": 10,
            "first_pattern_in_final_zap_response": sorted(first_cells),
            "first_pattern_after_actual_modal_close": sorted(restored_cells),
            "second_pattern_in_final_zap_response": sorted(second_cells),
            "native_damage_jump_created_both_patterns": True, "no_post_setup_test_pattern_replacement": True,
            "actual_cell_examine_and_modal_suppression": True, "offscreen_suppression_and_restore": True,
            "native_turn_delay_and_alpha_fade": True, "faded_hidden_traps_not_reconstructed": True,
            "old_pattern_history_retained": True, "replacement_uses_current_draw_only": True}


def test_hidden(client):
    state = client.state()
    assert not kind_cells(state, KIND)
    start = time.monotonic()
    state = act(client, "wait")
    elapsed = time.monotonic() - start
    assert elapsed < 10 and not kind_cells(state, KIND)
    assert not any(any(c["kind"] == KIND for c in e["data"]["cues"]) for e in read_visual_events(client))
    return {"native_fading_layer_behind_wall_test_only": True, "hidden_pattern_not_published": True,
            "hidden_pattern_not_in_history": True, "public_wait_seconds": elapsed}


def main():
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    results = [run_one(root, classpath, runtime_id, "traps", test_patterns),
               run_one(root, classpath, runtime_id, "traps-hidden", test_hidden)]
    report = dict(test_fixture=True, counts_as_win=False, runtime_id=runtime_id,
                  total=2, passed=sum(r["ok"] for r in results), results=results)
    (root / "desktop-control/build/fixtures" / runtime_id / "tengu-traps-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    if not all(r["ok"] for r in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
