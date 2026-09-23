#!/usr/bin/env python3
"""Deep protocol-8 text cases in independent processes and disposable profiles.

The parent freezes one runtime. Each Python worker gets its own GUI-language
environment and reuses the original scenario assertions with MatrixClient source
validation. No worker reads game saves or uses a non-CLI game control path.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import traceback

from fixture_smoke import freeze_runtime
from language_matrix_smoke import MatrixClient, validate


LANGUAGES = ("en", "zh", "zh-hant", "ja", "ko", "ru", "fr", "de", "tr")
CASES = tuple("upgrade:" + case for case in ("known-pane", "known-bag", "unknown-pane", "unknown-bag")) + tuple(
    "inspect:" + case for case in ("known", "unknown", "old-body", "containers-a", "containers-b")) + ("notes:text",)
DEFAULT_CASES = ("upgrade:known-pane", "upgrade:unknown-bag", "inspect:old-body", "notes:text")


def selected(value, allowed, defaults):
    choices = tuple(allowed if value == "all" else defaults if value is None else value.split(","))
    if not choices or any(choice not in allowed for choice in choices) or len(set(choices)) != len(choices):
        raise ValueError("Select distinct values from: " + ",".join(allowed))
    return choices


def validate_case_selection(cases):
    if ("inspect:containers-a" in cases) != ("inspect:containers-b" in cases):
        raise ValueError("Select both inspect:containers-a and inspect:containers-b to retain the hidden-world comparison")


def worker_environment(language, inherited=None):
    environment = dict(os.environ if inherited is None else inherited)
    environment["SPDCTL_TEST_LANGUAGE"] = language
    return environment


class Evidence:
    def __init__(self, language):
        self.language = language
        self.clients = []
        self.handshakes = []
        self.gui_languages = set()
        self.responses = 0
        self.actions = 0
        self.source_checks = 0
        self.failure = None


def client_type(evidence):
    class SensitiveMatrixClient(MatrixClient):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            evidence.clients.append(self)

        def request(self, op, args=None, **kwargs):
            if evidence.failure is not None:
                raise AssertionError("This case stopped at its first error; no further CLI requests may be sent")
            evidence.responses += 1
            evidence.actions += int(op == "action.execute")
            try:
                response = super().request(op, args, **kwargs)
                assert response.get("protocol_version") == 8, response
                assert response.get("ok"), response
                result = response.get("result")
                # Root MatrixClient checks dict results; events.read also returns a list.
                if op == "state.get":
                    failures = validate(result)
                    evidence.source_checks += 1
                    assert not failures, {"op": op, "source_failures": failures[:30]}
                if op == "protocol.info":
                    assert response["protocol_version"] == 8 and result["audit_schema_version"] == 11, result
                    assert result["cli_version"] == "CLI.8.0.0" and result["text_language"] == "en", result
                    assert result.get("build_id") and result.get("session_id"), result
                    metadata = {key: result[key] for key in ("build_id", "cli_version", "audit_schema_version", "text_language", "session_id")}
                    metadata["protocol_version"] = response["protocol_version"]
                    if evidence.handshakes:
                        assert metadata["build_id"] == evidence.handshakes[0]["build_id"], "A case changed frozen runtime builds"
                    evidence.handshakes.append(metadata)
                if isinstance(result, dict) and isinstance(result.get("observation"), dict):
                    display = result["observation"].get("ui", {}).get("display")
                    if display is not None:
                        assert display == {"language": evidence.language, "fullscreen": False}, display
                        evidence.gui_languages.add(display["language"])
                return response
            except Exception as error:
                evidence.failure = {"op": op, "action": args.get("action") if isinstance(args, dict) else None,
                                    "error_type": type(error).__name__, "error": str(error)[:5000]}
                raise
    return SensitiveMatrixClient


def run_scenario(root, classpath, runtime_id, case, evidence, preparation="intuition"):
    kind, name = case.split(":", 1)
    client = client_type(evidence)
    if kind == "upgrade":
        import upgrade_preview_smoke as scenario
        scenario.FixtureClient = client
        return scenario.run_case(root, classpath, runtime_id, name, preparation=preparation)
    if kind == "inspect":
        import inspected_item_smoke as scenario
        scenario.FixtureClient = client
        return scenario.run(root, classpath, runtime_id, name)
    import notes_scenario_smoke as scenario
    scenario.FixtureClient = client
    return scenario.run_one(root, classpath, runtime_id, name)


def run_worker(job_path):
    root = Path(__file__).resolve().parents[4]
    fixtures = (root / "desktop-control/build/fixtures").resolve()
    assert job_path.resolve().is_relative_to(fixtures) and job_path.name == "job.json" and not job_path.is_symlink()
    job = json.loads(job_path.read_text())
    assert re.fullmatch(r"runtime-[0-9a-f]{32}", job["runtime_id"])
    expected_root = root / "desktop-control/build/fixtures" / job["runtime_id"]
    assert job_path.resolve().is_relative_to(expected_root.resolve()) and job_path.name == "job.json"
    assert job["language"] in LANGUAGES and job["case"] in CASES
    assert os.environ.get("SPDCTL_TEST_LANGUAGE") == job["language"], "The parent must provide the worker language"
    evidence = Evidence(job["language"])
    report = {"test_fixture": True, "counts_as_win": False, "runtime_id": job["runtime_id"], "case": job["case"],
              "requested_gui_language": job["language"], "automatic_stale_retries": 0}
    started = time.monotonic()
    try:
        original = run_scenario(root, job["classpath"], job["runtime_id"], job["case"], evidence, job.get("known_preparation", "intuition"))
        report["scenario_report"] = original
        report["profile"] = original.get("profile")
        assert original.get("ok"), {"scenario_failed": original.get("error"), "cleanup_error": original.get("cleanup_error")}
        assert evidence.failure is None, evidence.failure
        assert evidence.handshakes and evidence.source_checks > 0, "No protocol-8 source evidence was recorded"
        assert evidence.gui_languages == {job["language"]}, "No matching live GUI-language observation was recorded"
        assert all(client.process.poll() == 0 for client in evidence.clients), "A scenario process did not exit cleanly"
        report["ok"] = True
    except Exception as error:
        report.update(ok=False, error_type=type(error).__name__, error=str(error)[:5000], traceback=traceback.format_exc())
    finally:
        # Existing scenarios normally own cleanup. This only handles an interrupted runner.
        for client in evidence.clients:
            if client.process.poll() is None:
                try:
                    if not client.process.stdin.closed:
                        client.process.stdin.close()
                    client.process.wait(timeout=35)
                except Exception as error:
                    report.update(ok=False, emergency_cleanup_error=repr(error))
                    client.process.terminate()
                    client.process.wait(timeout=10)
            if not client.stderr.closed:
                client.stderr.close()
            if not client.trace.closed:
                client.trace.close()
        report.update(elapsed_seconds=round(time.monotonic() - started, 3), handshakes=evidence.handshakes,
                      observed_gui_languages=sorted(evidence.gui_languages), response_checks=evidence.responses,
                      source_checks=evidence.source_checks, action_requests=evidence.actions, first_cli_error=evidence.failure,
                      process_exit_codes=[client.process.poll() for client in evidence.clients])
        target = job_path.with_name("result.json")
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report


def launch_worker(job_path, language):
    with job_path.with_name("worker-stdout.log").open("w") as stdout, job_path.with_name("worker-stderr.log").open("w") as stderr:
        completed = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--worker", str(job_path)],
                                   env=worker_environment(language), stdout=stdout, stderr=stderr, check=False)
    target = job_path.with_name("result.json")
    if target.is_file():
        report = json.loads(target.read_text())
    else:
        job = json.loads(job_path.read_text())
        report = {"ok": False, "case": job["case"], "requested_gui_language": language,
                  "error": "Worker exited before writing its case result", "worker_exit_code": completed.returncode}
    if completed.returncode != 0:
        report.update(ok=False, worker_exit_code=completed.returncode)
    report["report_path"] = str(target)
    return report


def container_pair_checks(results, languages, cases):
    checks = []
    if "inspect:containers-a" not in cases:
        return checks
    for language in languages:
        pair = [next(row for row in results if row["requested_gui_language"] == language and row["case"] == case)
                for case in ("inspect:containers-a", "inspect:containers-b")]
        same = all(row.get("ok") for row in pair) and pair[0]["scenario_report"]["evidence"]["public_signatures"] == pair[1]["scenario_report"]["evidence"]["public_signatures"]
        checks.append({"language": language, "ok": same, "check": "hidden_container_worlds_keep_identical_public_inspection"})
    return checks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--languages", help="Comma-separated deep languages, or all (the default nine)")
    parser.add_argument("--cases", help="Comma-separated family:case names, or all; default: " + ",".join(DEFAULT_CASES))
    parser.add_argument("--workers", type=int, default=1, help="Independent Python/game processes; serial by default")
    parser.add_argument("--identify-scroll-languages", default="", help="Explicit known-upgrade preparation override for selected languages; others retain Intuition")
    parser.add_argument("--worker", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        if not run_worker(args.worker).get("ok"):
            raise SystemExit(1)
        return
    try:
        languages = selected(args.languages, LANGUAGES, LANGUAGES)
        cases = selected(args.cases, CASES, DEFAULT_CASES)
        identify_languages = selected(args.identify_scroll_languages, LANGUAGES, ()) if args.identify_scroll_languages else ()
        if not set(identify_languages) <= set(languages):
            raise ValueError("--identify-scroll-languages must be a subset of --languages")
        validate_case_selection(cases)
        if not 1 <= args.workers <= 9:
            raise ValueError("--workers must be between 1 and 9")
    except ValueError as error:
        parser.error(str(error))
    root = Path(__file__).resolve().parents[4]
    classpath, runtime_id = freeze_runtime(root, (root / "desktop-control/build/test-runtime-classpath.txt").read_text().strip())
    output = root / "desktop-control/build/fixtures" / runtime_id / "text-sensitive-matrix"
    output.mkdir()
    jobs = []
    for language in languages:
        for case in cases:
            directory = output / (language + "--" + case.replace(":", "-"))
            directory.mkdir()
            job = directory / "job.json"
            preparation = "identify-scroll" if language in identify_languages and case.startswith("upgrade:known-") else "intuition"
            job.write_text(json.dumps({"language": language, "case": case, "runtime_id": runtime_id, "classpath": classpath,
                                       "known_preparation": preparation}, indent=2) + "\n")
            jobs.append((job, language))
    results = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(launch_worker, job, language) for job, language in jobs]
        for future in as_completed(futures):
            if future.cancelled():
                continue
            result = future.result()
            if not result.get("ok"):
                for queued in futures: queued.cancel()
            results.append(result)
            compact = {key: result.get(key) for key in ("requested_gui_language", "observed_gui_languages", "case", "ok", "elapsed_seconds", "profile", "source_checks", "report_path")}
            compact["build_id"] = result.get("handshakes", [{}])[0].get("build_id") if result.get("handshakes") else None
            if result.get("error"):
                compact["error"] = result["error"][:500]
            print(json.dumps(compact, ensure_ascii=False), flush=True)
    results.sort(key=lambda row: (languages.index(row["requested_gui_language"]), cases.index(row["case"])))
    pairs = container_pair_checks(results, languages, cases) if len(results) == len(jobs) else []
    builds = sorted({metadata["build_id"] for row in results for metadata in row.get("handshakes", [])})
    ok = len(results) == len(jobs) and all(row.get("ok") for row in results) and all(pair["ok"] for pair in pairs) and len(builds) == 1
    summary = {"test_fixture": True, "counts_as_win": False, "runtime_id": runtime_id, "languages": languages,
               "cases": cases, "workers": args.workers, "identify_scroll_languages": identify_languages,
               "passed": sum(bool(row.get("ok")) for row in results),
               "total": len(results), "requested_total": len(jobs), "ok": ok, "build_ids": builds, "container_pair_checks": pairs, "results": results}
    target = output / "results.json"
    target.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"report": str(target), "passed": summary["passed"], "total": summary["total"], "ok": ok}), flush=True)
    if not ok:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
