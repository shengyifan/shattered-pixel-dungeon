#!/usr/bin/env python3
"""Verify fresh-profile unlocks through the production packaged controller.

Every profile is absent at first launch. No FixtureLauncher, settings preparation,
state injection, earned badge injection, private audit query, or personal profile
is used. Test choices come exclusively from complete public responses. Settings
are inspected only after orderly exit to verify persisted fresh-profile defaults.
The plain GUI entrypoint is a separate acceptance check, not inferred from this.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

from controller_package_smoke import ControllerClient, write_json
from package_english_smoke import environment
from spdctl_client import decode_wire_response


HEROES = ("warrior", "mage", "rogue", "huntress", "duelist", "cleric")
SEED = "ABC-DEF-GHI"


def node_label(node):
    labels = {operation["label"] for operation in node.get("ops", []) if operation.get("label")}
    if len(labels) == 1:
        return labels.pop()
    return node.get("text", node.get("label"))


class PublicMenus:
    def __init__(self, cli, profile, output):
        self.client = ControllerClient(cli, profile, output, environment())
        try:
            self.client.state()
            self.seen = []
            self.capture("initial")
        except Exception:
            self.client.cleanup()
            raise

    @property
    def data(self):
        return decode_wire_response(self.client.current).data

    @property
    def nodes(self):
        return self.data.get("ui", {}).get("nodes", [])

    @property
    def scene(self):
        return self.data.get("ui", {}).get("scene")

    @property
    def modal(self):
        return self.data.get("ui", {}).get("modal", False)

    @property
    def text(self):
        return "\n".join(n.get("text", "") for n in self.nodes)

    def acts(self, op="click"):
        return [a for a in self.data.get("acts", []) if a.get("op") == op]

    def action(self, op, **params):
        result = self.client.action(op, **params)
        if op != "quit":
            self.seen.append(self.scene)
        return result

    def choose(self, label):
        choices = [a for a in self.acts() if a.get("label", "").casefold() == label.casefold()]
        assert len(choices) == 1, {"label": label, "scene": self.scene, "choices": self.acts()}
        return self.action("click", ctl=choices[0]["ctl"])

    def click_node(self, node):
        ops = [a for a in node.get("ops", []) if a.get("op") == "click"]
        assert len(ops) == 1, node
        return self.action("click", ctl=ops[0].get("ctl", node["id"]))

    def capture(self, name):
        write_json(self.client.output / (name + "-frame.json"), self.client.current)

    def title(self):
        for _ in range(12):
            assert self.scene != "WelcomeScene", "A fresh unlocked profile must skip the introduction"
            if self.scene == "TitleScene" and not self.modal:
                return
            if any(a.get("label") == "Main Menu" for a in self.acts()):
                self.choose("Main Menu")
            elif self.acts("back"):
                self.action("back")
            elif self.acts("reveal"):
                self.action("reveal")
            else:
                raise AssertionError({"title_unreachable": self.data})
        raise AssertionError("Native back did not reach title")

    def selection(self, hero="warrior"):
        self.title()
        self.choose("Enter the Dungeon")
        if self.scene == "StartScene":
            self.choose("New Game")
        assert self.scene == "HeroSelectScene" and not self.modal, self.data
        classes = {a.get("label", "").casefold() for a in self.acts()} & set(HEROES)
        assert classes == set(HEROES), classes
        self.choose(hero)
        # Re-selecting the native remembered class opens its info window.
        if self.modal:
            assert not any(word in self.text.lower() for word in ("unlock the", "to unlock")), self.text
            self.action("back")
        assert any(a.get("label") == "Start" for a in self.acts()), self.data
        self.capture("selected-" + hero)

    def options(self):
        if not any(a.get("label") == "Custom Seed" for a in self.acts()):
            self.choose("Game Options")
        assert {"Custom Seed", "Challenges", "Randomize"} <= {a.get("label") for a in self.acts()}

    def checks(self):
        return [n for n in self.nodes if "checked" in n]

    def challenge_count(self, expected, editable=True):
        checks = self.checks()
        assert len(checks) == 9, checks
        assert sum(n["checked"] for n in checks) == expected, checks
        assert all(n.get("enabled") is editable for n in checks), checks
        return checks

    def seed(self, expected=None, new=None):
        self.choose("Custom Seed")
        entries = self.acts("text")
        assert len(entries) == 1, self.data
        field = entries[0]["ctl"]
        node = next(n for n in self.nodes if n.get("id") == field)
        if expected is not None:
            assert node["value"] == expected, node
        if new is not None:
            self.action("text", ctl=field, text=new)
            self.choose("Set" if new else "Clear")
        else:
            # Native WndTextInput deliberately ignores Back to protect typed text.
            # Re-accept the observed value through its existing Set/Clear control.
            self.choose("Set" if node["value"] else "Clear")

    def start(self, hero):
        self.choose("Start")
        self.arrive_game()
        hero_data = self.data.get("hero", {})
        assert hero_data.get("class") == hero and hero_data.get("level") == 1, hero_data
        assert hero_data.get("subclass", "none") == "none", hero_data
        assert not hero_data.get("armor_ability"), hero_data
        assert self.data["phase"] == "player_ready", self.data
        assert self.acts("wait") and self.acts("item"), "Normal toolbar and inventory must be ready without tutorial"
        self.capture("normal-start-" + hero)
        return hero_data

    def arrive_game(self):
        deadline = time.monotonic() + 40
        while not self.data.get("hero"):
            assert time.monotonic() < deadline, self.data
            if any(a.get("label") == "Continue" for a in self.acts()):
                # The native chapter introduction remains part of normal play;
                # skipping the first-run tutorial does not remove this control.
                self.choose("Continue")
            else:
                time.sleep(.1)
                self.client.state()
        while self.modal:
            self.action("back")

    def close(self):
        if self.modal:
            self.action("back")
        self.action("quit")
        self.client.finish_exit()


def defaults(profile):
    values = {entry.get("key"): entry.text or "" for entry in ET.parse(profile / "settings.xml").getroot()}
    assert values.get("unlock_start_options") == "true", values
    assert values.get("intro") == "false", values
    assert values.get("version") == "922", values
    return {key: values.get(key) for key in ("unlock_start_options", "intro", "version", "challenges", "custom_seed")}


def inspect_info(menu, hero, nested=False):
    menu.choose("Hero Info")
    assert menu.modal, menu.data
    tabs = [n for n in menu.nodes if "selected" in n]
    assert len(tabs) == 4 and sum(n["selected"] for n in tabs) == 1, tabs
    identities = [n.get("icon") for n in tabs]
    assert all(identities), tabs
    assert len({json.dumps(icon, sort_keys=True) for icon in identities}) == 4, identities
    report = []
    for identity in identities:
        tab = next(n for n in menu.nodes if n.get("icon") == identity and "selected" in n)
        if not tab["selected"]:
            menu.click_node(tab)
        assert any(n.get("icon") == identity and n.get("selected") for n in menu.nodes)
        title = next((n["text"] for n in menu.nodes if n.get("text")), "")
        record = {"icon": identity, "texts": [n["text"] for n in menu.nodes if n.get("text")]}
        assert not re.search(r"unlock.{0,30}tier", menu.text, re.IGNORECASE), menu.text
        if title.casefold() == "talents":
            points = [n["talent_points"] for n in menu.nodes if "talent_points" in n]
            assert len(points) == 9 and all(p["spent"] == 0 for p in points), points
            record["class_talents"] = len(points)
        if nested and title in ("Subclasses", "Armor Abilities"):
            tab_ids = {n["id"] for n in menu.nodes if "selected" in n}
            detail_controls = [a["ctl"] for a in menu.acts() if a["ctl"] not in tab_ids]
            assert len(detail_controls) == (2 if title == "Subclasses" else 3), detail_controls
            details = []
            for index in range(len(detail_controls)):
                # Bind every detail control again after the preceding modal closes.
                tab_ids = {n["id"] for n in menu.nodes if "selected" in n}
                current = [a for a in menu.acts() if a["ctl"] not in tab_ids]
                menu.action("click", ctl=current[index]["ctl"])
                points = [n["talent_points"] for n in menu.nodes if "talent_points" in n]
                assert points and all(p["spent"] == 0 for p in points), menu.data
                assert not re.search(r"unlock.{0,30}tier", menu.text, re.IGNORECASE), menu.text
                details.append({"texts": [n["text"] for n in menu.nodes if n.get("text")], "talents": points})
                menu.action("back")
            record["details"] = details
        report.append(record)
    assert {"talents", "subclasses", "armor abilities"} <= {
        text.casefold() for entry in report for text in entry["texts"]}, report
    menu.capture("hero-info-" + hero)
    menu.action("back")
    return report


def options_case(menu, report):
    menu.selection()
    report["previews"] = inspect_info(menu, "warrior", nested=True)
    menu.options()
    menu.seed(expected="")
    menu.choose("Challenges")
    menu.challenge_count(0)
    labels = [node_label(n) for n in menu.checks()]
    assert len(set(labels)) == 9, labels
    for label in labels:
        node = next(n for n in menu.checks() if node_label(n) == label)
        menu.click_node(node)
    menu.challenge_count(9)
    menu.action("back")
    menu.seed(new=SEED)
    menu.selection()
    menu.options()
    menu.seed(expected=SEED)
    menu.choose("Challenges")
    menu.challenge_count(9)
    menu.capture("nine-challenges-retained")
    menu.action("back")
    menu.choose("Randomize")
    assert {"Randomize Hero", "Randomize Challenges", "Confirm", "Cancel"} <= {a.get("label") for a in menu.acts()}
    menu.choose("Cancel")
    menu.choose("Daily Run")
    assert "today's daily" in menu.text, menu.text
    menu.choose("No")
    report.update(nine_challenges=labels, selected_options_retained_after_scene_reentry=True,
                  daily_native_prompt=True, random_native_full_dialog=True)


def reopen_options(menu, report):
    menu.selection()
    menu.options()
    menu.seed(expected=SEED)
    menu.choose("Challenges")
    menu.challenge_count(9)
    for _ in range(9):
        menu.click_node(next(n for n in menu.checks() if n["checked"]))
    menu.challenge_count(0)
    menu.action("back")
    menu.seed(new="")
    menu.choose("Randomize")
    for label in ("Randomize Hero", "Randomize Challenges"):
        node = next(n for n in menu.checks() if node_label(n) == label)
        if not node["checked"]:
            menu.click_node(node)
    slider = menu.acts("value")
    assert len(slider) == 1, slider
    menu.action("value", ctl=slider[0]["ctl"], value=2)
    menu.choose("Confirm")
    menu.challenge_count(2, editable=False)
    menu.capture("randomized-two-challenges")
    menu.action("back")
    menu.options()
    menu.choose("Challenges")
    menu.challenge_count(2)
    for _ in range(2):
        menu.click_node(next(n for n in menu.checks() if n["checked"]))
    menu.action("back")
    report.update(options_retained_across_restart=True, random_two_challenges=True)


def daily_button(menu):
    options = [a for a in menu.acts() if a.get("label") == "Daily Run" or
               re.fullmatch(r"[0-9]{2}:[0-9]{2}:[0-9]{2}\+?", a.get("label", ""))]
    assert len(options) == 1, options
    menu.action("click", ctl=options[0]["ctl"])


def save_details(menu, hero):
    menu.title()
    menu.choose("Enter the Dungeon")
    assert menu.scene == "StartScene", menu.data
    options = [a for a in menu.acts() if a.get("label", "").split("\n")[0].casefold() == hero]
    assert len(options) == 1, options
    menu.action("click", ctl=options[0]["ctl"])


def daily_case(menu, report):
    menu.selection()
    menu.options()
    daily_button(menu)
    assert "today's daily" in menu.text, menu.text
    menu.choose("Yes")
    menu.arrive_game()
    assert menu.data.get("hero", {}).get("class") == "warrior", menu.data
    original_scope = menu.client.scope
    menu.action("save")
    menu.selection()
    menu.options()
    daily_button(menu)
    assert "You already have a daily run in progress." in menu.text, menu.text
    menu.capture("daily-existing-refusal")
    menu.action("back")
    save_details(menu, "warrior")
    menu.choose("Erase")
    menu.choose("No, I want to continue")
    assert any(a.get("label") == "Continue" for a in menu.acts())
    menu.choose("Erase")
    menu.choose("Yes, delete this save")
    menu.selection()
    menu.options()
    daily_button(menu)
    assert "You have already played today's daily." in menu.text, menu.text
    menu.capture("daily-replay-confirmation")
    menu.choose("Yes")
    menu.arrive_game()
    assert menu.data.get("hero", {}).get("class") == "warrior", menu.data
    assert menu.client.scope != original_scope
    menu.action("save")
    report.update(daily_started=True, existing_daily_refused=True, erase_cancel_preserved_save=True,
                  native_erase_then_replay=True, new_scope=True, clock_changed=False)


def duplicate_case(menu, report):
    menu.selection()
    menu.start("warrior")
    menu.action("save")
    save_details(menu, "warrior")
    seeds = [n["text"] for n in menu.nodes if re.fullmatch(r"[A-Z]{3}-[A-Z]{3}-[A-Z]{3}", n.get("text", ""))]
    assert len(seeds) == 1, menu.data
    observed_seed = seeds[0]
    menu.action("back")
    menu.choose("New Game")
    menu.choose("warrior")
    if menu.modal:
        menu.action("back")
    menu.options()
    menu.seed(new=observed_seed)
    assert "You already have a regular game in progress with that seed." in menu.text, menu.text
    menu.capture("duplicate-seed-refusal")
    menu.action("back")
    menu.seed(expected="")
    report.update(seed_source="public native save-details window", duplicate_seed=observed_seed,
                  native_duplicate_refusal=True, rejected_seed_cleared=True)


def run(root, cli, case):
    out = root / "desktop-control/build/fixtures" / ("unlock-package-" + case + "-" + uuid.uuid4().hex)
    out.mkdir(parents=True)
    profile = out / "absent-profile"
    assert not profile.exists()
    report = {"test_fixture": True, "counts_as_win": False, "case": case, "profile": str(profile),
              "production_launcher": True, "bundled_controller_and_jvm": True, "fixture_launcher": False,
              "profile_prepared": False, "artifact": str(out)}
    menu = None
    try:
        menu = PublicMenus(cli, profile, out / "first-session")
        report["cli_version"] = menu.client.hello["data"]["cli_version"]
        report["build_id"] = menu.client.hello["data"]["build_id"]
        report["production_jar_sha256"] = hashlib.sha256(menu.client.codec_jar.read_bytes()).hexdigest()
        if case == "menus":
            options_case(menu, report)
        elif case == "daily":
            daily_case(menu, report)
        elif case == "duplicate":
            duplicate_case(menu, report)
        else:
            menu.selection(case)
            report["previews"] = inspect_info(menu, case)
            report["hero"] = menu.start(case)
            menu.action("save")
        menu.close()
        report["first_defaults"] = defaults(profile)
        menu.client.cleanup()
        menu = None
        if case in ("menus", "warrior"):
            menu = PublicMenus(cli, profile, out / "reopen-session")
            if case == "menus":
                reopen_options(menu, report)
            else:
                save_details(menu, case)
                menu.choose("Continue")
                menu.arrive_game()
                assert menu.data.get("hero", {}).get("class") == case, menu.data
                report["normal_save_reopened"] = True
            menu.close()
            report["reopen_defaults"] = defaults(profile)
        report["verified"] = True
    except Exception as error:
        report.update(verified=False, error=repr(error), traceback=traceback.format_exc())
        raise
    finally:
        if menu is not None:
            menu.client.cleanup()
        write_json(out / "unlock-result.json", report)
        print(json.dumps({k: v for k, v in report.items() if k not in ("previews", "hero", "traceback")}), flush=True)
    return report


def main():
    root = Path(__file__).resolve().parents[4]
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", type=Path, default=root / "desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl")
    parser.add_argument("--cases", default=",".join(("menus",) + HEROES + ("daily", "duplicate")))
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    cases = args.cases.split(",")
    assert cases and set(cases) <= set(HEROES) | {"menus", "daily", "duplicate"}, cases
    reports = [run(root, args.cli.resolve(), case) for case in cases]
    if args.summary:
        write_json(args.summary, reports)


if __name__ == "__main__":
    main()
