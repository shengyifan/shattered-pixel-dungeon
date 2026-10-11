#!/usr/bin/env python3
"""Native tab/point expression via isolated packaged menus and native death/ranking callbacks."""
import argparse
import hashlib
import json
from pathlib import Path
import uuid

from controller_package_smoke import ControllerClient, reach_warrior, write_json
from package_english_smoke import environment
from spdctl_client import decode_wire_response
from fixture_smoke import FixtureClient, freeze_fixture_runtime, fixture_java_prefix
from english_protocol_smoke import activate, execute
from ending_scenario_smoke import original_death, death_menu, ui_assertion
from fixture_support import metadata, safe_profile, stop


def tabs(nodes):
    result = [n for n in nodes if "selected" in n]
    assert result and all(type(n["selected"]) is bool for n in result), result
    assert sum(n["selected"] for n in result) == 1, result
    assert all(n.get("icon", {}).get("symbol") for n in result), result
    return result


def packaged(root, cli):
    out = root / "desktop-control/build/fixtures" / ("tab-expression-package-" + uuid.uuid4().hex)
    out.mkdir(parents=True)
    client = None
    report = {"test_fixture": True, "counts_as_win": False, "ordinary_packaged_controller": True,
              "profile": str(out / "new-profile"), "windows": []}
    try:
        client = ControllerClient(cli, out / "new-profile", out / "transport-client", environment())
        report["production_jar"] = {"source": str(client.codec_jar),
                                    "sha256": hashlib.sha256(client.codec_jar.read_bytes()).hexdigest()}
        reach_warrior(client)

        def data():
            return decode_wire_response(client.current).data

        def choose(label):
            choices = [a for a in data()["acts"] if a.get("op") == "click" and a.get("label") == label]
            assert len(choices) == 1, {"label": label, "choices": choices}
            client.action("click", ctl=choices[0]["ctl"])

        def close():
            while data()["ui"].get("modal"):
                client.action("back")

        def exercise(name, expected):
            initial = tabs(data()["ui"]["nodes"])
            symbols = {n["icon"]["symbol"] for n in initial}
            assert symbols == set(expected), {"window": name, "symbols": symbols}
            record = {"window": name, "symbols": sorted(symbols), "selected": [], "talents": []}
            for symbol in expected:
                current = tabs(data()["ui"]["nodes"])
                node = next(n for n in current if n["icon"]["symbol"] == symbol)
                before = client.revision
                if not node["selected"]:
                    ops = [op for op in node["ops"] if op["op"] == "click"]
                    assert len(ops) == 1, node
                    client.action("click", ctl=ops[0].get("ctl", node["id"]))
                    assert client.revision != before, {"window": name, "symbol": symbol}
                selected = [n for n in tabs(data()["ui"]["nodes"]) if n["selected"]]
                assert selected[0]["icon"]["symbol"] == symbol, selected
                record["selected"].append(symbol)
                if symbol == "talent":
                    points = [n["talent_points"] for n in data()["ui"]["nodes"] if "talent_points" in n]
                    assert points and all(p["spent"] == 0 and p["max"] > 0 for p in points), points
                    record["talents"] = points
            report["windows"].append(record)
            write_json(out / (name + "-frame.json"), client.current)

        choose("Hero Info")
        exercise("hero", ["rankings", "talent", "buffs"])
        close()
        choose("Journal")
        exercise("journal", ["journal", "item_mastery", "alchemy", "catalog", "badges"])
        close()
        choose("Menu")
        choose("Settings")
        native_tabs = tabs(data()["ui"]["nodes"])
        native_symbols = {n["icon"]["symbol"] for n in native_tabs}
        assert {"prefs", "data", "audio", "langs"} <= native_symbols, native_symbols
        assert len(native_symbols & {"display_port", "display_land"}) == 1, native_symbols
        assert native_symbols <= {"display_port", "display_land", "prefs", "data", "audio", "langs", "keyboard", "controller"}
        exercise("settings", [n["icon"]["symbol"] for n in native_tabs])
        close()
        client.action("quit")
        client.finish_exit()
        report.update(verified=True, cli_version=client.hello["data"]["cli_version"])
    finally:
        write_json(out / "tab-expression-result.json", report)
        if client:
            client.cleanup()
    print(json.dumps({"artifact": str(out), **report}), flush=True)
    return report


def ranking(root, bundle=None):
    classpath, runtime_id, execution = freeze_fixture_runtime(
        root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip(), bundle)
    driver = Path(__file__).read_bytes()
    frozen_driver = root / "desktop-control/build/fixtures" / runtime_id / Path(__file__).name
    frozen_driver.write_bytes(driver)
    execution["driver"] = {"sha256": hashlib.sha256(driver).hexdigest(), "frozen_source": str(frozen_driver)}
    profile = safe_profile(root, "ranking-talent-expression")
    metadata(profile, "ending:death-ranking-talents", runtime_id, execution=execution)
    command = fixture_java_prefix(execution) + ["-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher",
               "--fixture", "ending:death-ranking-talents"]
    client = FixtureClient(command, profile, verify_gui=True)
    report = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id,
              "execution": execution, "profile": str(profile)}
    try:
        hello = client.request("info")
        assert ("err" not in hello), hello
        _, dead, _, _ = original_death(client, profile)
        death_menu(client, profile)
        ranked = activate(client, "Rankings").observation
        rows = [a for a in ranked["acts"] if a.get("op") == "click"
                and "succumbed to poison" in a.get("label", "").lower()]
        assert len(rows) == 1, rows
        detail = execute(client, "click", ctl=rows[0]["ctl"]).observation
        ui_assertion(profile, detail, "com.shatteredpixel.shatteredpixeldungeon.windows.WndRanking")
        initial = tabs(detail["ui"]["nodes"])
        assert {n["icon"]["symbol"] for n in initial} == {"rankings", "talent", "backpack_lrg", "badges"}, initial
        choice = next(n for n in initial if n["icon"]["symbol"] == "talent")
        detail = execute(client, "click", ctl=choice["id"]).observation
        assert detail["ui"]["scene"] == "RankingsScene"
        assert "hero" not in detail, "Ranking points cannot depend on current hero projection"
        ui_assertion(profile, detail, "com.shatteredpixel.shatteredpixeldungeon.windows.WndRanking")
        selected = [n for n in tabs(detail["ui"]["nodes"]) if n["selected"]]
        assert selected[0]["icon"]["symbol"] == "talent"
        points = {n.get("label"): n["talent_points"] for n in detail["ui"]["nodes"] if "talent_points" in n}
        assert points["Hearty Meal"] == {"spent": 1, "max": 2}, points
        assert points["Veteran's Intuition"] == {"spent": 2, "max": 2}, points
        write_json(profile / "ranking-talent-frame.json", detail)
        report.update(verified=True, original_death=True, native_rankings_window=True,
                      no_hero_projection=True, displayed_points=points)
        execute(client, "back")
        execute(client, "back")
        stop(client)
    finally:
        write_json(profile / "ranking-talent-result.json", report)
        if client.process.poll() is None:
            client.process.stdin.close()
            try:
                client.process.wait(timeout=35)
            except Exception:
                client.process.terminate()
                client.process.wait(timeout=10)
        client.stderr.close()
        client.trace.close()
    print(json.dumps(report), flush=True)
    return report


def main():
    root = Path(__file__).resolve().parents[4]
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("package", "ranking", "all"), default="all")
    parser.add_argument("--cli", type=Path, default=root / "desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl")
    parser.add_argument("--bundle", type=Path,
                        help="Ranking uses this packaged production JAR with host JVM and separate test-only fixture classes")
    args = parser.parse_args()
    if args.mode in {"package", "all"}:
        packaged(root, args.cli)
    if args.mode in {"ranking", "all"}:
        ranking(root, args.bundle)


if __name__ == "__main__":
    main()
