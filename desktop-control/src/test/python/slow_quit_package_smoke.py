#!/usr/bin/env python3
"""Exercise real packaged async quit using a releasable, test-only JVM agent.

The agent pauses after the original native quit/save callback. The installed
package and its classes are unchanged; all profiles/traces are explicit fixtures.
This instrumented lifecycle test is distinct from the ordinary package smoke.
"""
import argparse
import json
from pathlib import Path
import shutil
import time
import uuid

from controller_package_smoke import ControllerClient, reach_warrior, save_receipts, validate_trace, write_json
from package_english_smoke import environment as clean_environment
from test_ui import configure_test_ui


def wait_for(check, message, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = check()
        if found:
            return found
        time.sleep(.025)
    raise AssertionError(message)


def complete_frames(path):
    raw = path.read_bytes()
    return [json.loads(line) for line in raw.split(b"\n")[:-1] if line]


def instrumentable_runtime(bundle):
    runtime = bundle / "Contents/runtime/Contents/Home"
    release = runtime / "release"
    properties = dict(line.split("=", 1) for line in release.read_text().splitlines() if "=" in line)
    modules = properties.get("MODULES", "").strip('"').split()
    native = runtime / "lib/libinstrument.dylib"
    assert "java.instrument" in modules and native.is_file(), (
        "The slow-quit package test requires bundled java.instrument and lib/libinstrument.dylib; "
        "rebuild packageMacArm64 with java.instrument in --add-modules. No host-JVM fallback is used.")
    return {"java_instrument_module": True, "native_agent_library": str(native),
            "runtime_java_version": properties.get("JAVA_VERSION", "").strip('"')}


def arm_next_request(client, profile):
    requests = complete_frames(client.trace_session() / "send.raw")
    sequence = max((int(item["id"].split(".")[1]) for item in requests
                    if item["id"].startswith(client.prefix + ".")), default=0)
    request_id = client.prefix + "." + str(sequence + 1)
    (profile / "barrier.armed").write_text(request_id, encoding="utf-8")
    return request_id


def settle_finished(client, request_id):
    for _ in range(12):
        result = client.request({"op": "settle", "rid": request_id, "timeout_ms": 5000})
        client.settle_calls += 1
        if result.get("st") == "pending" or result.get("err") in {"RESPONSE_TIMEOUT", "CHILD_EXIT_TIMEOUT"}:
            continue
        assert result.get("st") == "completed" and "err" not in result, result
        settled = result.get("outcome") if result.get("controller") == "exit" else result
        assert settled.get("controller") == "settle", settled
        assert "observation" not in settled and "discovery" not in settled, settled
        assert settled["outcome"]["data"]["id"] == request_id, settled
        assert settled["outcome"]["data"]["st"] == "COMPLETED", settled
        return settled
    raise AssertionError("Released packaged quit did not finish")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--agent", type=Path, required=True,
                        help="Test-only agent jar produced by :desktop-control:crashAgentJar")
    args = parser.parse_args()
    runtime_check = instrumentable_runtime(args.bundle.resolve())
    root = Path(__file__).resolve().parents[4]
    output = root / "desktop-control/build/fixtures" / ("slow-quit-package-" + uuid.uuid4().hex)
    output.mkdir(parents=True)
    profile = output / "profile"
    configure_test_ui(profile)
    write_json(profile / "test_fixture.json", {"test_fixture": True, "counts_as_win": False,
               "fixture": "packaged-slow-quit", "instrumented": True,
               "setup": "Chinese/windowed preferences and a test-only delay after native quit; no save injection"})
    agent = output / "test-only-engine-boundaries.jar"
    shutil.copy2(args.agent.resolve(), agent)
    cli = args.bundle.resolve() / "Contents/MacOS/spdctl"
    assert cli.is_file(), cli
    environment = clean_environment()
    option = f"-javaagent:{agent}=slow-quit;{profile}"
    assert '"' not in option, "The JVM option path cannot contain a double quote"
    environment["JAVA_TOOL_OPTIONS"] = '"' + option + '"'
    report = {"result": "running", "test_fixture": True, "counts_as_win": False,
              "instrumented_packaged_lifecycle": True, "ordinary_package_smoke": False,
              "bundle": str(args.bundle.resolve()), "profile": str(profile), "artifacts": str(output),
              "instrumentable_runtime": runtime_check,
              "personal_profile_or_audit_reads": False, "save_or_game_state_injection": False}
    client = None
    try:
        client = ControllerClient(cli, profile, output / "controller", environment)
        client.state()
        _, report["scenes"] = reach_warrior(client)
        for _ in range(6):
            client.state()
            request_id = arm_next_request(client, profile)
            initial = client.unwrap(client.request({"op": "quit", "rev": client.revision}))
            if initial.get("err") == "STALE_STATE":
                rejected = client.settle(initial)
                assert rejected.get("err") == "ACTION_REJECTED", rejected
                continue
            break
        else:
            raise AssertionError("Could not dispatch a current quit")
        marker = profile / "barrier.reached"
        wait_for(marker.exists, "The native quit callback did not reach the barrier")
        assert marker.read_text().splitlines()[0] == "slow-quit"
        # Both production deadlines are 30 seconds. A controller timeout may precede
        # the child's initial in_progress by a few milliseconds; recover that frame.
        if initial.get("controller") == "error":
            assert initial.get("err") == "RESPONSE_TIMEOUT", initial
            assert initial["request"]["id"] == request_id, initial
            recv = client.trace_session() / "recv.raw"
            wait_for(lambda: any(frame.get("id") == request_id and frame.get("st") == "in_progress"
                                for frame in complete_frames(recv)), "Missing initial in_progress wire response")
            recovered = client.request({"op": "settle", "rid": request_id})
            candidates = [client.unwrap(recovered)] + [item["response"] for item in recovered.get("late_responses", [])]
            initial = next((frame for frame in candidates if frame.get("id") == request_id
                            and frame.get("st") == "in_progress"), None)
            assert initial is not None, {"missing_recovered_initial": recovered}
        assert initial.get("id") == request_id and initial.get("st") == "in_progress", initial
        client.initial_pending += 1
        client.events.write(json.dumps({"initial_pending_exposed_before_release": request_id}) + "\n")
        client.events.flush()
        pending = client.request({"op": "settle", "rid": request_id, "timeout_ms": 1000})
        client.settle_calls += 1
        assert pending.get("st") == "pending" or pending.get("err") == "RESPONSE_TIMEOUT", pending
        assert pending["outcome"]["data"]["st"] == "EXECUTING", pending
        assert client.process.poll() is None, "Packaged process exited before its terminal receipt"
        (profile / "barrier.release").write_text("release native quit\n", encoding="utf-8")
        settled = settle_finished(client, request_id)
        report["save_receipts"] = save_receipts({"initial": initial, "settled": settled,
                                                "request_scope": initial["s"]})
        client.finish_exit()
        report["transport"] = validate_trace(client)
        statuses = (client.trace_session() / "events.tsv").read_text()
        for failure in ("CHILD_STDIN_BROKEN", "SHUTDOWN_TIMEOUT", "FORCED_TERMINATION", "DRAIN_TIMEOUT"):
            assert failure not in statuses, failure
        report.update(result="passed", initial_in_progress_exposed=True, receipt_while_blocked="EXECUTING",
                      terminal_receipt="COMPLETED", exit_code=client.process.returncode,
                      no_unsolicited_reply=True, no_state_after_quit=True, barrier_stack=marker.read_text().splitlines())
    except Exception as failure:
        report.update(result="failed", error=repr(failure))
        raise
    finally:
        # Release only this fixture barrier, even when an assertion fails.
        (profile / "barrier.release").write_text("fixture cleanup\n", encoding="utf-8")
        if client is not None:
            client.cleanup()
        write_json(output / "result.json", report)
        print(json.dumps({"result": report["result"], "report": str(output / "result.json")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
