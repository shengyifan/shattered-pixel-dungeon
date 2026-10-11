#!/usr/bin/env python3
"""Protocol 9 provenance matrix on disposable test profiles; no direct save reads."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
import traceback
import uuid

from fixture_smoke import FixtureClient, freeze_runtime
from tutorial_boundary_smoke import start_warrior

PROSE = {"name", "class_name", 'sub_name', "text", "label", 'desc', "title",
         "prompt", "item_prompt", "message", "disabled_reason", "options"}
OPAQUE = {"text_sources", "text_diagnostics", "pres", "text_origins", "reply", "raw", "raw_request", "raw_bytes", "node"}


def validate(value, path="$", failures=None):
    failures = [] if failures is None else failures
    if isinstance(value, dict):
        def safe_reference(source):
            if isinstance(source, dict):
                if source.get("kind") == "resource" and source.get("visibility") == "partial":
                    return isinstance(source.get("key"), str) and source.get("args") == []
                return any(safe_reference(child) for child in source.values())
            return isinstance(source, list) and any(safe_reference(child) for child in source)
        diagnostics = value.get("pres", {}).get("diag", [])
        diagnostic_fields = {entry["field"] for entry in diagnostics}
        for entry in diagnostics:
            field, code = entry["field"], entry["code"]
            declared_visibility = code in {"argument_not_displayed", "format_precision_not_displayed"} and safe_reference(value.get("text_sources", {}).get(field))
            if code != "clipped_text" and not declared_visibility:
                failures.append({"field": path + "." + field, "code": code,
                                 'ctl': value.get("id"), "role": value.get("role")})
        for field, item in value.items():
            if field in OPAQUE:
                continue
            if field in PROSE and isinstance(item, str) and item and field not in diagnostic_fields:
                if field not in value.get("text_sources", {}):
                    failures.append({"field": path + "." + field, "code": "missing_wire_source", "text": item})
            validate(item, path + "." + field, failures)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            validate(item, path + f"[{index}]", failures)
    return failures


class MatrixClient(FixtureClient):
    def act(self, action, **args):
        response = super().act(action, **args)
        if response.ok and action != 'quit':
            observed = self.state(source=True)
            assert observed['rev'] == response.observation['rev'], "Source detail must describe the same settled state"
        return response

    def request(self, op, args=None, **kwargs):
        if op == 'state':
            args = {**(args or {}), "src": True}
        response = super().request(op, args, **kwargs)
        if ("err" not in response) and op == 'state':
            failures = validate(response["data"])
            assert not failures, {"source_failures": failures[:30], "op": op}
        return response


def exercise(root, classpath, code, fixture="class:WARRIOR"):
    os.environ["SPDCTL_TEST_LANGUAGE"] = code
    profile = root / "desktop-control/build/fixtures" / ("cli9-language-" + code + "-" + uuid.uuid4().hex)
    profile.mkdir(parents=True)
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
               "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", classpath,
               "com.shatteredpixel.shatteredpixeldungeon.control.desktop.FixtureLauncher", "--fixture", fixture]
    client = MatrixClient(command, profile)
    report = {"language": code, "profile": str(profile.relative_to(root)), "fixture": fixture,
              "test_fixture": True, "counts_as_win": False}
    try:
        hello = client.request('info')
        assert hello["v"] == 9, hello
        report["build_id"] = hello["data"]["build_id"]
        state = start_warrior(client)
        assert state["ui"]["display"]["language"] == code
        # Native cached inventory and item windows exercise resource composition and knowledge gates.
        item = next(item for item in state['inv'] if item.get("equipped") and "shortsword" in item["name"])
        response = client.act('item', loc=item['loc'])
        assert response.ok, response
        response = client.act('back')
        assert response.ok, response
        saved = client.act('save')
        assert saved.ok, saved
        client.state()
        report["ok"] = True
    except Exception as error:
        report.update(ok=False, error=repr(error), traceback=traceback.format_exc())
    finally:
        # EOF follows the normal audited shutdown path even when source validation failed.
        try:
            if client.process.poll() is None:
                client.process.stdin.close()
                client.process.wait(timeout=45)
        except Exception as error:
            report.update(ok=False, shutdown_error=repr(error))
            client.process.terminate()
            client.process.wait(timeout=10)
        client.stderr.close()
        client.trace.close()
        report["exit_code"] = client.process.poll()
    (profile / "language-result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--languages", default="all")
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--worker")
    parser.add_argument("--classpath")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    if args.worker:
        assert args.classpath
        result=exercise(root,args.classpath,args.worker)
        print(json.dumps(result,ensure_ascii=False),flush=True)
        return
    assert 1 <= args.workers <= 4
    source = (root / "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/messages/Languages.java").read_text()
    codes = re.findall(r'^\s*[A-Z_]+\("[^\"]*",\s*"([^\"]+)"', source, re.M)
    selected = codes if args.languages == "all" else args.languages.split(",")
    assert selected and all(code in codes for code in selected), selected
    classpath, runtime = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    def worker(code):
        completed=subprocess.run([sys.executable,str(Path(__file__).resolve()),"--worker",code,"--classpath",classpath],
                                 capture_output=True,text=True,check=False)
        try:
            result=json.loads(completed.stdout.strip().splitlines()[-1])
        except Exception:
            result={"language":code,"ok":False,"error":"worker_failure","stderr":completed.stderr[-4000:]}
        print(json.dumps(result,ensure_ascii=False),flush=True)
        return result
    results=[]
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures=[pool.submit(worker,code) for code in selected]
        for future in as_completed(futures):
            if future.cancelled(): continue
            result=future.result();results.append(result)
            if not result.get("ok"):
                for queued in futures: queued.cancel()
    results.sort(key=lambda row:selected.index(row["language"]))
    report = root / "desktop-control/build/fixtures" / runtime / "language-matrix-results.json"
    report.write_text(json.dumps({"registered_languages": codes, "selected": selected, "results": results}, indent=2) + "\n")
    if len(results) != len(selected) or not all(result["ok"] for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
