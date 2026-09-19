"""Real JVM controller/native-relay regression with disposable GUI-free engines.

Run through ordinary unittest discovery; only a JDK and C compiler are required.
The test compiles production sources into a temporary directory and never starts
the game, reads a profile, opens Terminal.app, or relies on a prebuilt package.
"""
import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import tty
import unittest


ROOT = Path(__file__).resolve().parents[4]
CONTROLLER_SOURCE = ROOT / "desktop-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/StableController.java"
HARNESS_CLASS = "com.shatteredpixel.shatteredpixeldungeon.control.desktop.ControllerPtyHarness"

HARNESS = r'''
package com.shatteredpixel.shatteredpixeldungeon.control.desktop;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

public final class ControllerPtyHarness {
    public static void main(String[] args) throws Exception {
        if ("control".equals(args[0])) {
            try {
                Path root = Paths.get(System.getenv("SPDCTL_TEST_ROOT"));
                System.err.print("CONTROLLER_READY\n"); System.err.flush();
                Files.createFile(root.resolve("controller-ready"));
                while (!Files.exists(root.resolve("release-controller"))) Thread.sleep(5);
                System.exit(StableController.launch(args, Paths.get(System.getenv("SPDCTL_PROFILE")), System.in, System.out));
            } catch (Exception failure) {
                // Test-only evidence channel: do not depend on a launcher's presentation policy.
                Files.write(Paths.get(System.getenv("SPDCTL_TEST_ROOT"), "controller-failure"),
                    (failure.getClass().getSimpleName() + ": " + failure.getMessage()).getBytes(StandardCharsets.UTF_8));
                System.exit(71);
            }
        }
        if (!"run".equals(args[0])) throw new IllegalArgumentException("Unexpected test engine command");
        Path root = Paths.get(System.getenv("SPDCTL_TEST_ROOT"));
        String payload = "x".repeat(Integer.parseInt(System.getenv("SPDCTL_TEST_BYTES"))) + "中文🐈";
        NdjsonReader input = new NdjsonReader(System.in);
        NdjsonReader.Frame frame;
        while ((frame = input.next()) != null) {
            if (frame.error != null) throw frame.error;
            Map<String,Object> request = JsonCodec.decode(frame.text);
            String op = (String)request.get("op");
            if ("info".equals(op)) {
                Files.createFile(root.resolve("engine-ready"));
                while (!Files.exists(root.resolve("release-hello"))) Thread.sleep(5);
            }
            if ("state".equals(op) && "true".equals(System.getenv("SPDCTL_TEST_PRESSURE"))) {
                System.err.print("ENGINE_PRESSURE:" + "e".repeat(32768) + "\n");
                System.err.flush();
            }
            Map<String,Object> data = "info".equals(op)
                ? map("request_prefix", "t1", "padding", payload)
                : "state".equals(op) ? map("phase", "menu_ready", "padding", payload)
                : map("phase", "menu_ready");
            Map<String,Object> response = map("v", 6, "id", request.get("id"), "st", "completed",
                "s", "s1", "rev", "info".equals(op) ? "r1" : "r2", "data", data);
            byte[] encoded = (JsonCodec.encode(response) + "\n").getBytes(StandardCharsets.UTF_8);
            // Split the final multi-byte characters as well as the surrounding JSON.
            int split = encoded.length - 7;
            System.out.write(encoded, 0, split);
            System.out.flush();
            for (int i = split; i < encoded.length; i++) {
                System.out.write(encoded[i]); System.out.flush();
            }
            if ("quit".equals(op)) break;
        }
        System.err.print("ENGINE_TAIL:" + "d".repeat(Integer.parseInt(System.getenv("SPDCTL_TEST_TAIL_BYTES"))) + "\n");
        System.err.flush();
    }
}
'''


@unittest.skipUnless(sys.platform == "darwin", "The production native relay targets macOS")
class ControllerPtyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix="spdctl-controller-pty-build-")
        cls.addClassCleanup(cls.build.cleanup)
        cls.build_path = Path(cls.build.name).resolve()
        cls.binary = cls.build_path / "spdctl"
        cls.classes = cls.build_path / "classes"
        cls.classes.mkdir()
        cls.java = shutil.which("java")
        javac = shutil.which("javac")
        if not cls.java or not javac:
            raise unittest.SkipTest("Controller/native integration tests require a JDK")
        harness = cls.build_path / "ControllerPtyHarness.java"
        harness.write_text(HARNESS)
        protocol = ROOT / "control-protocol/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/protocol"
        subprocess.run([javac, "-encoding", "UTF-8", "-d", str(cls.classes),
                        *map(str, sorted(protocol.glob("*.java"))), str(CONTROLLER_SOURCE), str(harness)],
                       check=True, capture_output=True)
        subprocess.run([shutil.which("cc") or "/usr/bin/cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                        str(ROOT / "desktop-control/src/main/native/spdctl-bootstrap.c"), "-o", str(cls.binary)],
                       check=True, capture_output=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="spdctl-controller-pty-case-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.process = None
        self.descriptors = []
        self.capture = bytearray()
        self.addCleanup(self.stop)

    def stop(self):
        if self.process and self.process.poll() is None:
            # A failing test may leave the synthetic relay waiting for its gate.
            # Kill only this isolated test process group, never a running game.
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            except PermissionError:
                self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=3)
        if self.process:
            for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
                if stream and not stream.closed:
                    stream.close()
        for descriptor in self.descriptors:
            os.close(descriptor)

    def launch(self, size=7000, *, terminal=True, pressure=False):
        environment = os.environ.copy()
        environment.pop("SPDCTL_PROFILE", None)
        tail_size = 128 if terminal else 98304
        self.diagnostic_tail = b"ENGINE_TAIL:" + b"d" * tail_size + b"\n"
        self.diagnostic_pressure = b"ENGINE_PRESSURE:" + b"e" * 32768 + b"\n" if pressure else b""
        environment.update(SPDCTL_TEST_ROOT=str(self.root), SPDCTL_TEST_BYTES=str(size), SPDCTL_TEST_TAIL_BYTES=str(tail_size),
                           SPDCTL_TEST_PRESSURE=str(pressure).lower())
        engine = [self.java, "-cp", str(self.classes), HARNESS_CLASS]
        command = [str(self.binary), "--engine-argc", str(len(engine)), *engine, "--", "control", "--machine",
                   "--data-dir", str(self.root / "profile"), "--trace-dir", str(self.root / "trace"), "--no-terminal"]
        if terminal:
            master, slave = pty.openpty()
            self.descriptors.extend((master, slave))
            tty.setraw(slave)
            self.master, self.slave = master, slave
            self.process = subprocess.Popen(command, stdin=slave, stdout=slave, stderr=slave,
                                            env=environment, start_new_session=True)
        else:
            self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.PIPE, env=environment, start_new_session=True)
        self.wait_for(lambda: (self.root / "controller-ready").exists(), "Test controller JVM did not start")
        if terminal:
            self.read_pty_until(lambda: b"\n" in self.capture)
            self.assertEqual(b"CONTROLLER_READY\n", bytes(self.capture))
            self.capture.clear()
            # Establish the baseline after the JVM's own first write (macOS Java
            # enables a private no-SIGPIPE flag), but before it launches the relay.
            self.before_flags = fcntl.fcntl(self.slave, fcntl.F_GETFL)
            self.assertFalse(self.before_flags & os.O_NONBLOCK)
        (self.root / "release-controller").touch()
        self.wait_for(lambda: (self.root / "engine-ready").exists(), "Synthetic child did not receive info")
        if terminal:
            self.read_pty_until(lambda: b"\n" in self.capture)
            self.assertTrue(bytes(self.capture).startswith(b"spdctl: TRACE_SESSION "))
            self.assertEqual(1, self.capture.count(b"\n"))
            self.capture.clear()

    def wait_for(self, condition, message, timeout=10):
        deadline = time.monotonic() + timeout
        while not condition():
            if time.monotonic() >= deadline:
                self.fail(message)
            time.sleep(.01)

    def read_pty_until(self, condition, timeout=12):
        deadline = time.monotonic() + timeout
        while not condition():
            if time.monotonic() >= deadline:
                detail = (self.root / "controller-failure").read_text() if (self.root / "controller-failure").exists() else "none"
                self.fail(f"Incomplete controller output: {len(self.capture)} bytes, exit={self.process.poll()}, failure={detail}")
            if select.select([self.master], [], [], .1)[0]:
                try:
                    chunk = os.read(self.master, 4093)
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
                    chunk = b""
                self.capture.extend(chunk)
                # Intentionally slower than the producing JVM, with a bounded test runtime.
                time.sleep(.001)

    def send_intent(self, value):
        os.write(self.master, json.dumps(value, separators=(",", ":")).encode() + b"\n")

    def assert_wire(self, operations, replies):
        sessions = list((self.root / "trace").iterdir())
        self.assertEqual(1, len(sessions), "Only the run child owns a raw trace")
        session = sessions[0]
        requests = [json.loads(line) for line in (session / "send.raw").read_bytes().splitlines()]
        self.assertEqual(operations, [request["op"] for request in requests])
        self.assertEqual(["t1." + str(index) for index in range(1, len(requests))],
                         [request["id"] for request in requests[1:]])
        raw = (session / "recv.raw").read_bytes()
        self.assertTrue(raw.endswith(b"\n"))
        self.assertEqual(replies, [json.loads(line) for line in raw.splitlines()])
        # The child bytes remain pristine even though diagnostics and JSON share a terminal.
        self.assertNotIn(b"ENGINE_TAIL", raw)
        self.assertNotIn(b"\x1b", raw)
        self.assertEqual(self.diagnostic_pressure + self.diagnostic_tail, (session / "stderr.raw").read_bytes())
        self.assertIn("EXITED:0", (session / "events.tsv").read_text())
        return raw

    def parent_diagnostics(self, stderr):
        ready, startup, diagnostics = stderr.split(b"\n", 2)
        self.assertEqual(b"CONTROLLER_READY", ready)
        self.assertTrue(startup.startswith(b"spdctl: TRACE_SESSION "))
        return diagnostics

    def exercise_shared_pty(self, size):
        self.launch(size)
        during_flags = fcntl.fcntl(self.slave, fcntl.F_GETFL)
        (self.root / "release-hello").touch()
        # No input or output consumption until the child has produced a whole handshake.
        time.sleep(.25)
        self.read_pty_until(lambda: b"\n" in self.capture)
        first = bytes(self.capture).splitlines()[0]
        try:
            hello = json.loads(first)
        except (json.JSONDecodeError, UnicodeDecodeError):
            detail = (self.root / "controller-failure").read_text() if (self.root / "controller-failure").exists() else "none"
            self.fail(f"Invalid handshake: {len(first)} bytes, flags {self.before_flags}->{during_flags}, failure={detail}")
        self.assertEqual("x" * size + "中文🐈", hello["data"]["padding"])
        self.assertEqual("completed", hello["st"])
        self.assertIsNone(self.process.poll(), "Delayed stdin must not be treated as EOF")
        self.send_intent({"op": "state"})
        self.read_pty_until(lambda: self.capture.count(b"\n") >= 2)
        state = json.loads(bytes(self.capture).splitlines()[1])
        self.assertEqual("menu_ready", state["data"]["phase"])
        self.assertEqual(hello["data"]["padding"], state["data"]["padding"])
        self.send_intent({"op": "quit", "rev": state["rev"]})
        self.read_pty_until(lambda: self.capture.count(b"\n") >= 4)
        self.process.wait(timeout=5)
        self.assertEqual(0, self.process.returncode)
        self.assertEqual(self.before_flags, during_flags, "The child changed its controller's shared PTY file status flags")
        self.assertEqual(self.before_flags, fcntl.fcntl(self.slave, fcntl.F_GETFL))
        self.assertEqual(1, self.capture.count(self.diagnostic_tail))
        pure = bytes(self.capture).replace(self.diagnostic_tail, b"")
        replies = [json.loads(line) for line in pure.splitlines()]
        self.assertEqual(3, len(replies))
        self.assertEqual(["completed"] * 3, [reply["st"] for reply in replies])
        self.assertEqual(pure, self.assert_wire(["info", "state", "quit"], replies))

    def test_shared_pty_handshake_larger_than_observed_failure(self):
        self.exercise_shared_pty(7000)

    def test_shared_pty_over_64k_with_slow_consumer_and_fragmented_utf8(self):
        self.exercise_shared_pty(131072)

    def test_stdin_eof_drains_final_diagnostics_without_an_extra_request(self):
        self.launch(7000, terminal=False)
        (self.root / "release-hello").touch()
        hello = json.loads(self.process.stdout.readline())
        self.process.stdin.close()
        self.process.stdin = None
        stdout, stderr = self.process.communicate(timeout=15)
        self.assertEqual(0, self.process.returncode)
        self.assertEqual(b"", stdout)
        self.assertEqual(self.diagnostic_tail, self.parent_diagnostics(stderr))
        self.assert_wire(["info"], [hello])

    def test_broken_output_is_a_failure_and_never_replays_the_handshake(self):
        self.launch(131072, terminal=False)
        self.process.stdout.close()
        self.process.stdout = None
        (self.root / "release-hello").touch()
        self.process.communicate(timeout=12)
        self.assertNotEqual(0, self.process.returncode)
        self.assertEqual("LaunchFailure: CONTROLLER_OUTPUT_FAILED", (self.root / "controller-failure").read_text())
        session = next((self.root / "trace").iterdir())
        requests = [json.loads(line) for line in (session / "send.raw").read_bytes().splitlines()]
        self.assertEqual(["info"], [request["op"] for request in requests])

    def test_stalled_stderr_does_not_lock_independent_stdout(self):
        self.launch(7000, terminal=False, pressure=True)
        (self.root / "release-hello").touch()
        hello = json.loads(self.process.stdout.readline())
        self.process.stdin.write(b'{"op":"state"}\n')
        self.process.stdin.flush()
        # The outer diagnostic pipe is deliberately not consumed yet. A shared
        # output monitor would couple protocol progress to that blocked writer.
        deadline = time.monotonic() + 5
        response = bytearray()
        while not response.endswith(b"\n"):
            left = deadline - time.monotonic()
            self.assertGreater(left, 0, "Stalled diagnostics blocked an independent stdout reply")
            ready = select.select([self.process.stdout], [], [], left)[0]
            self.assertTrue(ready, "Stalled diagnostics blocked an independent stdout reply")
            chunk = os.read(self.process.stdout.fileno(), 4096)
            self.assertTrue(chunk, "Controller ended while diagnostics were backpressured")
            response.extend(chunk)
        state = json.loads(response)
        self.assertEqual("menu_ready", state["data"]["phase"])
        self.process.stdin.write(json.dumps({"op": "quit", "rev": state["rev"]}).encode() + b"\n")
        self.process.stdin.flush()
        stdout, stderr = self.process.communicate(timeout=15)
        self.assertEqual(0, self.process.returncode)
        quit_reply = json.loads(stdout)
        self.assertEqual(self.diagnostic_pressure + self.diagnostic_tail, self.parent_diagnostics(stderr))
        self.assert_wire(["info", "state", "quit"], [hello, state, quit_reply])


if __name__ == "__main__":
    unittest.main()
