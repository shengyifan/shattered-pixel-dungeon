#!/usr/bin/env python3
"""Actual native text, item, buff and floating render fixtures, with public-only assertions."""
import argparse
import json
from pathlib import Path
import time
import combat_visual_smoke as runner
from fixture_smoke import freeze_runtime
from protocol8 import pages


def test_case(client, name):
    initial = client.last_state
    observation = initial["observation"]
    nodes = observation["ui"]["controls"]
    feedback = observation["ui"].get("feedback", [])
    if name in {"appearance-freecast", "appearance-paidcast"}:
        spells = [node for node in nodes if "spell_icon" in node]
        assert spells, nodes
        guiding = next(node for node in spells if "guiding light" in node.get("label", "").lower())
        assert guiding["spell_icon"].get("symbol"), guiding
        assert guiding["free_cast"] is (name == "appearance-freecast"), guiding
        assert "tint" not in guiding["spell_icon"], guiding
        return {"native_guiding_light_free_cast": guiding["free_cast"], "spell": guiding["spell_icon"]}
    if name == "appearance-quicktarget":
        markers = [node for node in nodes if node.get("quickslot") == 1 and node.get("targeting_marker") is True]
        assert markers, nodes
        cue = next(cue for cue in observation["visual_cues"]["cues"] if cue["kind"] == "quickslot_target")
        assert cue["cell"] == observation["hero"]["cell"] + 2, cue
        return {"native_slot_marker": markers, "known_target_cell": cue["cell"]}
    if name == "appearance-attackportrait":
        portraits = [node for node in nodes if "target_icon" in node]
        assert len(portraits) == 1 and portraits[0]["enabled"], portraits
        assert portraits[0]["target_icon"]["symbol"] == "rat_portrait", portraits
        assert "cell" not in portraits[0]["target_icon"], portraits
        return {"native_attack_portrait_without_private_target_cell": portraits}
    if name == "appearance-quickslot-preview":
        previews = next(node["preview_icons"] for node in nodes if "preview_icons" in node)
        assert len(previews) == 4 and previews[0]["symbol"] == "changes", previews
        item_icons = [icon for icon in previews if icon is not None and icon.get("symbol") != "changes"]
        assert len(item_icons) == 2 and all(icon.get("symbol") for icon in item_icons), previews
        assert all("atlas" not in icon for icon in previews if icon is not None), previews
        return {"native_ordered_preview_symbols": previews}
    if name == "appearance-banner-boss":
        banners = [entry for entry in feedback if entry.get("kind") == "banner"
                   and entry.get("banner_kind") == "boss_slain"]
        assert banners, feedback
        events = [event for page in pages(client, "events.read") for event in page if event["kind"] == "game.banner"]
        assert len(events) == 1 and events[0]["data"]["kind"] == "boss_slain", events
        time.sleep(0.15)
        later = client.state()
        assert later["state_version"] == initial["state_version"], "Banner lifetime must not expire an action binding"
        repeated = [event for page in pages(client, "events.read") for event in page if event["kind"] == "game.banner"]
        assert repeated == events, repeated
        return {"native_banner_feedback": banners, "one_actual_display_event": events}
    if name == "appearance-bosswarning":
        bosses = [entity for entity in observation["visible_entities"]
                  if entity.get("shown", {}).get("boss_warning") is True]
        inline = [node for node in nodes if node.get("shown", {}).get("boss_warning") is True]
        assert len(bosses) + len(inline) == 1, {"entities": bosses, "nodes": inline}
        if bosses:
            assert bosses[0]["name"].lower() == "goo", bosses
        return {"native_boss_warning": (bosses or inline)[0]["shown"]}
    if name == "appearance-actionicons":
        icons = next(node for node in nodes if "primary_icon" in node and "secondary_icon" in node)
        assert icons["primary_icon"]["symbol"] != icons["secondary_icon"]["symbol"], icons
        return {"native_champion_action_symbols": icons}
    if name == "appearance-projectile":
        events = [event for page in pages(client, "events.read") for event in page if event["kind"] == "game.visual"]
        projectiles = [cue for event in events for cue in event["data"]["cues"] if cue["kind"] == "missile_projectile"]
        assert projectiles, events
        assert all(cue.get("appearance", {}).get("symbol") and "source_cell" not in cue for cue in projectiles), projectiles
        return {"native_projectile_semantic_history": projectiles}
    if name == "appearance-floating":
        shown = [entry for entry in feedback if entry.get("kind") == "floating" and entry.get("text") == "4"]
        assert len(shown) == 2, feedback
        assert {entry["tone"] for entry in shown} == {"negative", "positive"}, shown
        assert {entry["icon"]["symbol"] for entry in shown} == {"physical_damage", "healing"}, shown
        assert all(entry["cell"] == observation["hero"]["cell"] for entry in shown), shown
        events = [event for page in pages(client, "events.read") for event in page if event["kind"] == "game.floating_text"]
        entries = [entry for event in events for entry in event["data"]["entries"] if entry.get("text") == "4"]
        assert len(entries) == 2 and {entry["icon"]["symbol"] for entry in entries} == {"physical_damage", "healing"}, entries
        time.sleep(2.2)
        faded = client.state()
        assert not any(entry.get("kind") == "floating" for entry in faded["observation"]["ui"].get("feedback", []))
        retained = [event for page in pages(client, "events.read") for event in page if event["kind"] == "game.floating_text"]
        assert retained == events, "Fading must not erase evidence or create duplicate occurrences"
        return {"distinct_semantic_occurrences": entries, "history_survives_fade": True}
    if name == "appearance-item":
        counts = [node for node in nodes if isinstance(node.get("shown"), dict)
                  and node["shown"].get("status", "").isdigit()]
        assert len(counts) == 2 and len({node["shown"]["status"] for node in counts}) == 1, counts
        assert {"last_use" in node["shown"].get("flags", []) for node in counts} == {False, True}, counts
        assert len({node["id"] for node in counts}) == 2, counts
        return {"same_count_distinct_last_use_warning": [node["shown"] for node in counts]}
    if name == "appearance-buff":
        buffs = observation["hero"]["buffs"]
        matches = [(index, buff) for index, buff in enumerate(buffs)
                   if "toxic" in buff.get("name", "").lower() and "shown" in buff]
        assert matches, buffs
        index, toxin = matches[0]
        progress = toxin["shown"]["progress"]
        assert progress["basis"] == "displayed" and 0 < progress["covered"] < progress["total"], toxin
        assert toxin["shown"]["symbol"] == "buff_imbue"
        assert toxin["shown"]["variant"] == "bright_yellow_green"
        assert any(node.get("subject") == {"kind": "hero_buff", "index": index} for node in nodes), nodes
        return {"native_buff_symbol_and_displayed_progress": toxin["shown"]}
    if name == "appearance-markup":
        phrase = "Every upgrade after +3 becomes exponentially weaker than the last one."
        described = [node for node in nodes if phrase in str(node.get("text", ""))]
        assert described, nodes
        assert all("unavailable" not in node["text"].lower() and "color" not in node and "styles" not in node
                   for node in described)
        assert any(len(node.get("spans", [])) > 1 and all(span.get("text") for span in node["spans"])
                   for node in described), described
        return {"complete_translated_buff_description": [node["text"] for node in described],
                "semantic_text_spans": [node.get("spans", []) for node in described]}
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
