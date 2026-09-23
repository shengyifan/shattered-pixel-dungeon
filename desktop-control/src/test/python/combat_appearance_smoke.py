#!/usr/bin/env python3
"""Actual native text, item, buff and floating render fixtures, with public-only assertions."""
import argparse
import json
from pathlib import Path
import time
import combat_visual_smoke as runner
from fixture_smoke import freeze_runtime
from protocol7 import pages


def test_case(client, name):
    initial = client.last_state
    nodes = initial["observation"]["ui"]["controls"]
    if name in {"appearance-freecast", "appearance-paidcast"}:
        spells=[node for node in nodes if "spell_icon" in node]
        assert spells,nodes
        guiding=next(node for node in spells if "guiding light" in node.get("label","").lower())
        expected=3 if name=="appearance-freecast" else 1
        assert guiding["spell_icon"]["tint"]["multiply"]==[expected]*3,guiding
        return {"native_guiding_light_appearance":guiding}
    if name == "appearance-quicktarget":
        markers=[node for node in nodes if node.get("quickslot")==1 and "targeting_marker" in node]
        assert markers,markers
        cue=next(cue for cue in initial["observation"]["visual_cues"]["cues"] if cue["kind"]=="quickslot_target")
        assert cue["cell"]==initial["observation"]["hero"]["cell"]+2,cue
        return {"native_slot_marker":markers,"actual_crosshair_cell":cue}
    if name == "appearance-attackportrait":
        portraits=[node for node in nodes if "target_icon" in node]
        assert len(portraits)==1 and portraits[0]["enabled"],portraits
        assert portraits[0]["target_icon"]["atlas"].endswith("/rat.png"),portraits
        assert "cell" not in portraits[0]["target_icon"],portraits
        return {"native_attack_portrait_without_private_target_cell":portraits}
    if name == "appearance-quickslot-preview":
        previews=next(node["preview_icons"] for node in nodes if "preview_icons" in node)
        assert len(previews)==4 and previews[0]["symbol"]=="changes",previews
        assert sum(icon is not None and icon.get("atlas", "").endswith("/items.png") for icon in previews)==2,previews
        return {"native_ordered_preview_slots":previews}
    if name == "appearance-banner-boss":
        banners=[node for node in nodes if node.get("banner_kind")=="boss_slain"]
        assert banners,banners
        events=[event for page in pages(client,"events.read") for event in page if event["kind"]=="game.banner"]
        assert len(events)==1 and events[0]["data"]["kind"]=="boss_slain",events
        time.sleep(0.15)
        later=client.state()
        assert later["state_version"]==initial["state_version"],"Banner fade must not expire an action binding"
        repeated=[event for page in pages(client,"events.read") for event in page if event["kind"]=="game.banner"]
        assert repeated==events,repeated
        return {"native_banner":banners,"one_actual_display_event":events}
    if name == "appearance-bosswarning":
        boss=next(node for node in nodes if "boss_icon" in node)
        assert boss["boss_icon"]["tint"]["add"][0]>0,boss
        return {"native_boss_warning":boss}
    if name == "appearance-actionicons":
        icons=next(node for node in nodes if "primary_icon" in node and "secondary_icon" in node)
        assert icons["primary_icon"]["frame_pixels"]!=icons["secondary_icon"]["frame_pixels"],icons
        return {"native_champion_icons":icons}
    if name == "appearance-projectile":
        events=[event for page in pages(client,"events.read") for event in page if event["kind"]=="game.visual"]
        projectiles=[cue for event in events for cue in event["data"]["cues"] if cue["kind"]=="missile_projectile"]
        assert projectiles,events
        assert all("appearance" in cue and "source_cell" not in cue for cue in projectiles),projectiles
        return {"native_projectile_history":projectiles}
    if name == "appearance-floating":
        shown = [node for node in nodes if node.get("presentation") == "floating_text" and node.get("text") == "4"]
        assert len(shown) == 2, shown
        assert {node["color"] for node in shown} == {0xFF0000, 0x00FF00}, shown
        assert {node["icon"]["index"] for node in shown} == {0, 18}, shown
        assert all(node["cell"] == initial["observation"]["hero"]["cell"] for node in shown), shown
        events = [event for page in pages(client, "events.read") for event in page if event["kind"] == "game.floating_text"]
        entries = [entry for event in events for entry in event["data"]["entries"] if entry.get("text") == "4"]
        assert len(entries) == 2, entries
        assert {entry["icon"]["index"] for entry in entries} == {0, 18}, entries
        time.sleep(2.2)
        faded = client.state()
        assert not any(node.get("presentation") == "floating_text" for node in faded["observation"]["ui"]["controls"])
        retained = [event for page in pages(client, "events.read") for event in page if event["kind"] == "game.floating_text"]
        assert retained == events, "Fading must not erase evidence or create duplicate occurrences"
        return {"drawn_occurrences": entries, "history_survives_fade": True}
    if name == "appearance-item":
        counts = [node for node in nodes if node.get("role") == "text" and node.get("text", "").isdigit()]
        assert len(counts)==2 and len({node["text"] for node in counts})==1,counts
        assert {node.get("color") for node in counts} == {0xFFFFFF, 0xFF8800}, counts
        assert len({node["parent"] for node in counts}) == 2, counts
        return {"same_count_distinct_warning_color": counts}
    if name == "appearance-buff":
        buffs = [node for node in nodes if node.get("icon", {}).get("atlas") == "buff_small" and "icon_overlay" in node]
        assert buffs, nodes
        toxin = next(node for node in buffs if node.get("icon", {}).get("index") == 55)
        overlay = toxin["icon_overlay"]
        assert overlay["measurement"] == "rendered_pixels" and 0 < overlay["covered_pixels"] < overlay["total_pixels"], toxin
        assert "tint" in toxin["icon"], toxin
        return {"native_buff_pixels_and_tint": toxin}
    if name == "appearance-markup":
        styled = [node for node in nodes if len(node.get("styles", [])) > 1]
        assert styled, nodes
        for node in styled:
            assert len({run["color"] for run in node["styles"]}) > 1
            assert all(run.get("text") and "unavailable" not in run["text"].lower() for run in node["styles"]), node
        return {"translated_native_markup_runs": styled}
    raise AssertionError(name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="appearance-floating,appearance-item,appearance-buff,appearance-markup,appearance-freecast,appearance-paidcast,appearance-quicktarget,appearance-attackportrait,appearance-bosswarning,appearance-actionicons,appearance-projectile,appearance-quickslot-preview,appearance-banner-boss")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    classpath, runtime = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    runner.test_case = test_case
    results = [runner.run_one(root, classpath, runtime, case) for case in args.cases.split(",")]
    report = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime,
              "total": len(results), "passed": sum(row["ok"] for row in results), "results": results}
    (root / "desktop-control/build/fixtures" / runtime / "combat-appearance-results.json").write_text(json.dumps(report, indent=2) + "\n")
    if not all(row["ok"] for row in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
