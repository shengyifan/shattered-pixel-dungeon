#!/usr/bin/env python3
"""Isolated real runtime plus lossless synthetic audit benchmark; no victory claim.

Every game/menu input uses public NDJSON. The arm marker requests a test-only read probe,
after timed requests finish; probe results never choose a gameplay action.
"""
import argparse
import json
import math
import os
from pathlib import Path
import subprocess
import time
import uuid

from fixture_smoke import FixtureClient, act, freeze_runtime, reach_game


def describe(values):
    ordered = sorted(values)
    return {"n": len(values), "min": ordered[0], "p50": ordered[math.ceil(len(values)*.5)-1],
            "p95": ordered[math.ceil(len(values)*.95)-1], "max": ordered[-1]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", choices=["menu", "simple", "dense"], default="simple")
    parser.add_argument("--requests", type=int, default=30)
    parser.add_argument("--history", type=int, default=1000)
    options = parser.parse_args()
    assert 5 <= options.requests <= 1000
    root = Path.cwd()
    directory = root / "desktop-control/build/fixtures"
    profile = directory / ("performance-" + options.scenario + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    vm = [java, "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
          "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath]
    client = FixtureClient(vm + ["com.shatteredpixel.shatteredpixeldungeon.control.desktop.PerformanceLauncher",
                                 "--scenario", options.scenario], profile)
    report = {"test_fixture": True, "counts_as_win": False, "scenario": options.scenario,
              "profile": str(profile), "runtime_id": runtime_id,
              "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
              "game_inputs": "public NDJSON only; no screenshots, native keys, mouse, or computer use",
              "latency_scope": "Python request encode/write through matching complete NDJSON response decode; includes scheduling, all audit transactions and pipe I/O"}
    try:
        assert client.request("protocol.info")["ok"]
        if options.scenario == "menu":
            for _ in range(10):
                state = client.state()
                if state["observation"].get("scene") == "title":
                    break
                if state["observation"].get("scene") in {"hero_select", "start"}:
                    act(client, "ui.back")
                    continue
                candidates = [a for a in state["actions"] if a.get("action") == "ui.activate" and
                              any(word in a.get("label", "").lower() for word in ["enter", "continue", "进入", "继续"])]
                assert candidates, state
                act(client, "ui.activate", control=candidates[0]["control"])
            else:
                raise AssertionError("Did not reach title menu")
        else:
            state = reach_game(client, "WARRIOR")
        report["scene"] = state["observation"]["scene"]
        for _ in range(5):
            client.state()
        latency, versions = [], []
        for _ in range(options.requests):
            start = time.perf_counter_ns()
            state = client.state()
            latency.append((time.perf_counter_ns()-start)/1e6)
            versions.append(state["state_version"])
        report["state_get_roundtrip_ms"] = latency
        report["state_get_roundtrip_summary_ms"] = describe(latency)
        report["observed_state_version_count"] = len(set(versions))
        last_query_id = client.prefix + "-" + str(client.counter)
        history, get_request = [], []
        for _ in range(options.requests):
            start = time.perf_counter_ns()
            result = client.request("history.list", {"after": 0, "limit": 100})
            assert result["ok"], result
            history.append((time.perf_counter_ns()-start)/1e6)
        for _ in range(options.requests):
            start = time.perf_counter_ns()
            result = client.request("request.get", {"target_id": last_query_id})
            assert result["ok"], result
            get_request.append((time.perf_counter_ns()-start)/1e6)
        report["history_list_roundtrip_ms"] = history
        report["request_get_roundtrip_ms"] = get_request
        if options.scenario != "menu":
            inventory_latency = []
            for _ in range(10):
                ready = client.state()
                item = ready["observation"]["inventory"][0]
                start = time.perf_counter_ns()
                opened = client.request("action.execute", {"action": "inventory.open", "locator": item["locator"]})
                assert opened.get("ok") and opened["result"]["phase"] == "awaiting_input", opened
                inventory_latency.append((time.perf_counter_ns()-start)/1e6)
                act(client, "ui.back")
            report["inventory_open_roundtrip_ms"] = inventory_latency
            report["action_latency_scope"] = "10 actual inventory.open operations, each with complete before/after audit; ui.back between samples; no consumed game turns"
        report["actual_public_requests_before_probe"] = client.counter
        (profile / "benchmark.arm").write_text("test-only read probe\n")
        deadline = time.monotonic() + 90
        while not (profile / "benchmark-capture.json").exists():
            if client.process.poll() is not None:
                raise RuntimeError("Probe runtime exited: " + str(profile))
            if time.monotonic() > deadline:
                raise TimeoutError("Probe did not finish: " + str(profile))
            time.sleep(.05)
        client.finish()
        report["capture"] = json.loads((profile / "benchmark-capture.json").read_text())
        if options.scenario == "dense":
            assert report["capture"]["mob_count"] == 48, report["capture"]
            assert report["capture"]["ally_count"] == 8, report["capture"]
            assert report["capture"]["blob_types"] == 4, report["capture"]
        # Separate JVM: no live game can progress while its audit performance is sampled.
        subprocess.run([java, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                        "com.shatteredpixel.shatteredpixeldungeon.control.desktop.PerformanceAuditBenchmark",
                        str(profile), str(options.history)], check=True, stdout=subprocess.PIPE, text=True)
        report["audit"] = json.loads((profile / "benchmark-audit.json").read_text())
        report["summary_ms"] = {key: describe(value) for key, value in report.items() if key.endswith("_ms") and isinstance(value, list)}
        for group in ["capture", "audit"]:
            report[group]["summary_ms"] = {key: describe(value) for key, value in report[group].items() if key.endswith("_ms") and isinstance(value, list)}
        (profile / "performance-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"profile": str(profile), "scenario": options.scenario, "summary_ms": report["summary_ms"],
                          "capture_ms": report["capture"]["summary_ms"], "audit_ms": report["audit"]["summary_ms"],
                          "sizes": report["audit"]["sizes"], "synthetic_history": options.history}, ensure_ascii=False))
    finally:
        if client.process.poll() is None:
            client.process.stdin.close()
            try:
                client.process.wait(timeout=40)
            except Exception:
                client.process.terminate()
                client.process.wait(timeout=10)
        client.stderr.close()
        client.trace.close()


if __name__ == "__main__":
    main()
