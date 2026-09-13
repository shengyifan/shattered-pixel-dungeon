"""Exercise the production native byte proxy with disposable, GUI-free engines.

Run with: python3 -m unittest discover -s desktop-control/src/test/python -p test_transport.py
No Java runtime, game saves, Terminal.app or production profile is accessed.
"""
import json
import os
import pty
import re
from pathlib import Path
import resource
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest


ENGINE = r'''
import json, os, signal, sys, time
from pathlib import Path
mode = sys.argv[1]
os.write(2, ("ENGINE_PID=%d\n" % os.getpid()).encode())
if mode == "args":
    os.write(1, json.dumps(sys.argv[2:], ensure_ascii=False).encode() + b"\n")
elif mode == "profile":
    os.write(1, json.dumps({"profile": os.environ.get("SPDCTL_PROFILE")}).encode() + b"\n")
elif mode == "echo":
    while True:
        data = os.read(0, 8191)
        if not data: break
        os.write(1, data)
elif mode == "slow-echo":
    while True:
        data = os.read(0, 32749)
        if not data: break
        time.sleep(.0002)
        while data:
            data = data[os.write(1, data):]
elif mode == "gated":
    gate = Path(sys.argv[2])
    while True:
        data = bytearray()
        while not data.endswith(b"\n"):
            part = os.read(0, 1)
            if not part: break
            data.extend(part)
        if not data: break
        os.write(2, b"REQUEST_ACCEPTED\n")
        while not gate.exists(): time.sleep(.01)
        os.write(1, b"REPLY:" + data)
elif mode == "flood":
    for index in range(1024):
        os.write(1, bytes([index % 251]) * 8192)
    os.write(2, b"diagnostic:" + b"x" * 131072 + b"\n")
    count = 0
    while True:
        data = os.read(0, 16381)
        if not data: break
        count += len(data)
    os.write(1, ("\nINPUT_BYTES=%d\n" % count).encode())
elif mode == "close-input":
    os.close(0)
    os.write(1, b"ENGINE_CLOSED_INPUT\n")
    time.sleep(.05)
    sys.exit(7)
elif mode == "signal":
    def stopped(signum, frame):
        os.write(2, ("ENGINE_SIGNAL=%d\n" % signum).encode())
        sys.exit(128 + signum)
    signal.signal(signal.SIGTERM, stopped)
    signal.signal(signal.SIGINT, stopped)
    os.write(2, b"SIGNAL_READY\n")
    while True: time.sleep(.05)
elif mode == "fragmented":
    gates = Path(sys.argv[2])
    for index, value in enumerate("中文🐈".encode()):
        os.write(1, bytes([value]))
        while not (gates / str(index)).exists(): time.sleep(.005)
elif mode == "large-final":
    os.write(1, b"z" * 131072)
elif mode == "finite":
    os.write(1, b"final frame without LF")
    sys.exit(9)
else:
    raise RuntimeError("Unexpected synthetic engine mode")
'''


OPEN_SHIM = r"""
#include <errno.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

int spdctl_test_spawn(pid_t *pid, const char *path,
                      const posix_spawn_file_actions_t *actions,
                      const posix_spawnattr_t *attributes,
                      char *const argv[], char *const environment[]) {
    if (strcmp(path, "/usr/bin/open") != 0)
        return posix_spawn(pid, path, actions, attributes, argv, environment);
    const char *capture = getenv("SPDCTL_TEST_OPEN_CAPTURE");
    const char *behavior = getenv("SPDCTL_TEST_OPEN_BEHAVIOR");
    if (!capture || !argv[3]) return EINVAL;
    FILE *record = fopen(capture, "wb");
    if (!record) return errno;
    struct stat info;
    if (stat(argv[3], &info) != 0) { fclose(record); return errno; }
    fprintf(record, "%s\n%o\n", argv[3], (unsigned)(info.st_mode & 0777));
    FILE *script = fopen(argv[3], "rb");
    if (!script) { fclose(record); return errno; }
    char buffer[4096]; size_t count;
    while ((count = fread(buffer, 1, sizeof(buffer), script)) > 0)
        fwrite(buffer, 1, count, record);
    fclose(script); fclose(record);
    if (behavior && strcmp(behavior, "fail") == 0) return ENOENT;
    char *shell[] = {"/bin/sh", argv[3], NULL};
    return posix_spawn(pid, shell[0], actions, attributes, shell, environment);
}
"""


def wait_until(check, description, timeout=8):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(.01)
    raise AssertionError("Timed out waiting for " + description)


@unittest.skipUnless(sys.platform == "darwin", "The release native launcher targets macOS")
class NativeTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix="spdctl-native-test-")
        cls.build_path = Path(cls.build.name).resolve()
        cls.binary = cls.build_path / "spdctl"
        root = Path(__file__).resolve().parents[4]
        source = root / "desktop-control/src/main/native/spdctl-bootstrap.c"
        subprocess.run([shutil.which("cc") or "/usr/bin/cc", "-std=c11", "-O2", "-Wall", "-Wextra",
                        "-Werror", str(source), "-o", str(cls.binary)], check=True, capture_output=True)
        cls.open_binary = cls.build_path / "spdctl-open-test"
        shim = cls.build_path / "open_shim.c"
        shim.write_text(OPEN_SHIM)
        object_path = cls.build_path / "transport-open-test.o"
        subprocess.run([shutil.which("cc") or "/usr/bin/cc", "-std=c11", "-O2", "-Wall", "-Wextra",
                        "-Werror", "-Dposix_spawn=spdctl_test_spawn", "-c", str(source), "-o", str(object_path)],
                       check=True, capture_output=True)
        subprocess.run([shutil.which("cc") or "/usr/bin/cc", "-std=c11", "-O2", "-Wall", "-Wextra",
                        "-Werror", str(object_path), str(shim), "-o", str(cls.open_binary)],
                       check=True, capture_output=True)
        cls.engine = cls.build_path / "synthetic_engine.py"
        cls.engine.write_text(ENGINE)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="spdctl-transport-case-")
        self.root = Path(self.temp.name).resolve()
        self.profile = self.root / "profile"
        self.trace = self.root / "trace"
        self.processes = []
        self.environment = os.environ.copy()
        self.environment.pop("SPDCTL_PROFILE", None)
        self.environment["HOME"] = str(self.root / "home")

    def tearDown(self):
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=4)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=4)
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream and not stream.closed:
                    stream.close()
        self.temp.cleanup()

    def command(self, mode="echo", extra_engine=(), profile=None, trace=None):
        engine = [sys.executable, "-u", str(self.engine), mode, *map(str, extra_engine)]
        return [str(self.binary), "--engine-argc", str(len(engine)), *engine, "--",
                "run", "--machine", "--data-dir", str(profile or self.profile), "--no-terminal",
                "--trace-dir", str(trace or self.trace)]

    def start(self, mode="echo", extra_engine=(), **kwargs):
        process = subprocess.Popen(self.command(mode, extra_engine), stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   env=self.environment, **kwargs)
        self.processes.append(process)
        return process

    def session(self):
        def locate():
            files = list(self.trace.glob("*/events.tsv"))
            return files[0].parent if len(files) == 1 else None
        return wait_until(locate, "one trace session")

    @staticmethod
    def read_bytes(path):
        try:
            return path.read_bytes()
        except FileNotFoundError:
            return b""

    def events(self, session):
        lines = (session / "events.tsv").read_text().splitlines()
        self.assertEqual("SPDCTL_TRACE\t1", lines[0])
        rows = []
        for line in lines[1:]:
            columns = line.split("\t")
            self.assertEqual(6, len(columns), line)
            seq, epoch, kind, offset, length, detail = columns
            rows.append((int(seq), int(epoch), kind, int(offset), int(length), detail))
        self.assertEqual(sorted(set(row[0] for row in rows)), [row[0] for row in rows])
        self.assertTrue(all(row[1] > 0 for row in rows))
        return rows

    def assert_index_matches_raw(self, session, rows):
        for kind, filename in (("SEND", "send.raw"), ("RECV", "recv.raw"), ("STDERR", "stderr.raw")):
            offset = 0
            for row in rows:
                if row[2] != kind:
                    continue
                self.assertEqual(offset, row[3], (kind, row))
                self.assertGreater(row[4], 0)
                offset += row[4]
            self.assertEqual(len((session / filename).read_bytes()), offset, kind)

    def assert_complete(self, session):
        self.assertFalse((session / ".incomplete").exists())
        rows = self.events(session)
        self.assert_index_matches_raw(session, rows)
        self.assertTrue((session / "open-viewer.command").is_file())
        return rows


    def fixture_session(self, events):
        """Create only transport records; this fixture never opens a game profile."""
        session = self.root / "fixture-session"
        session.mkdir()
        payloads = {"SEND": bytearray(), "RECV": bytearray(), "STDERR": bytearray()}
        index = ["SPDCTL_TRACE\t1\n"]
        for sequence, (kind, value) in enumerate(events, 1):
            if kind in payloads:
                offset, length, detail = len(payloads[kind]), len(value), "-"
                payloads[kind].extend(value)
            else:
                offset, length, detail = 0, 0, value
            index.append(f"{sequence}\t{1700000000000000000 + sequence}\t{kind}\t{offset}\t{length}\t{detail}\n")
        for kind, name in (("SEND", "send.raw"), ("RECV", "recv.raw"), ("STDERR", "stderr.raw")):
            (session / name).write_bytes(payloads[kind])
        (session / "events.tsv").write_text("".join(index))
        return session

    def view(self, session, color=None, environment=None):
        command = [str(self.binary), "trace", "view", "--session", str(session)]
        if color:
            command.extend(["--color", color])
        result = subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True,
                                env=environment or self.environment, timeout=45)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(result.stderr)
        return result.stdout

    def test_viewer_filters_success_events_and_keeps_all_failures(self):
        failures = ["EXITED:9", "SIGNAL:2", "CHILD_STDIN_BROKEN", "TRACE_IO_FAILED", "FUTURE_FAILURE"]
        session = self.fixture_session([
            ("STATUS", "STARTED"), ("SEND", b'{"v":3}\n'), ("DELIVERED", "-"),
            ("STATUS", "EOF_SENT"), ("RECV", b'{"st":"done"}\n'),
            ("STDERR", b"engine diagnostic\n"), *[("STATUS", value) for value in failures],
            ("STATUS", "EXITED:0")])
        original = {path.name: path.read_bytes() for path in session.iterdir()}
        rendered = self.view(session)
        for hidden in (b"DELIVERED", b"STATUS", b"STARTED", b"EOF_SENT", b"EXITED:0", b"STDERR"):
            self.assertNotIn(hidden, rendered)
        for failure in failures:
            self.assertIn(failure.encode(), rendered)
        self.assertEqual(1, len(re.findall(rb"\] #\d+ SEND\n", rendered)))
        self.assertEqual(1, len(re.findall(rb"\] #\d+ RECV\n", rendered)))
        self.assertEqual(len(failures) + 1, len(re.findall(rb"\] #\d+ ERROR\n", rendered)))
        self.assertEqual(original, {path.name: path.read_bytes() for path in session.iterdir()})

    def test_viewer_keeps_ndjson_frames_contiguous_across_chunks(self):
        request = b'{"key": "first", "values": [1, true, null]}\n'
        response = '{"text":"中文🐈"}\n'.encode()
        session = self.fixture_session([
            *[("SEND", request[i:i + 1]) for i in range(len(request))],
            *[("RECV", response[i:i + 1]) for i in range(len(response))],
            ("RECV", b'{"more":2}\n{"final":3}'), ("STATUS", "EXITED:0")])
        rendered = self.view(session, "never")
        self.assertIn(request, rendered)
        self.assertIn(response, rendered)
        self.assertIn(b'{"more":2}\n', rendered)
        self.assertIn(b'{"final":3}\nSession ended.', rendered)
        self.assertEqual(4, len(re.findall(rb"\] #\d+ (?:SEND|RECV)\n", rendered)))
        self.assertNotIn(b"continued", rendered)

    def test_color_lexer_survives_interleaving_escapes_and_utf8(self):
        request = '{"key":"中文🐈 \\"quoted\\"","array":["value",{"nested":-1.5e+2}],"bool":true,"nil":null}\n'.encode()
        # Split every byte, including escapes and UTF-8. Every RECV interruption
        # must leave SEND's object/string grammar intact.
        events = []
        for index, byte in enumerate(request):
            events.append(("SEND", bytes([byte])))
            if index in (9, 15, 32):
                events.append(("RECV", b'{"reply":"ok"}\n'))
        session = self.fixture_session(events)
        colored = self.view(session, "always")
        plain = self.view(session, "never")
        self.assertEqual(plain, re.sub(rb"\x1b\[[0-9;]*m", b"", colored))
        self.assertEqual(3, plain.count(b"SEND (continued)"))
        self.assertIn(b'\x1b[34m"key"', colored)
        self.assertIn(b'\x1b[32m"value"', colored)
        self.assertIn(b'\x1b[34m"nested"', colored)
        self.assertIn(b'\x1b[33m-1.5e+2', colored)
        self.assertIn(b'\x1b[35mtrue', colored)
        self.assertIn(b'\x1b[35mnull', colored)
        self.assertIn(b'\x1b[2m', colored)
        colored.decode("utf-8", errors="strict")
        self.assertNotIn(b"\\xE4", colored)

    def test_color_modes_tty_environment_and_explicit_precedence(self):
        session = self.fixture_session([("SEND", b'{"key":"value"}\n')])
        self.assertNotIn(b"\x1b", self.view(session))
        self.assertNotIn(b"\x1b", self.view(session, "auto"))
        self.assertIn(b"\x1b", self.view(session, "always", {**self.environment, "NO_COLOR": "1", "TERM": "dumb"}))
        for color, extras, expect_color in [
                (None, {"TERM": "xterm-256color", "NO_COLOR": None}, True),
                ("auto", {"TERM": "dumb", "NO_COLOR": None}, False),
                ("auto", {"TERM": "xterm", "NO_COLOR": "1"}, False),
                ("never", {"TERM": "xterm", "NO_COLOR": None}, False),
                ("always", {"TERM": "dumb", "NO_COLOR": "1"}, True)]:
            with self.subTest(color=color, extras=extras):
                environment = self.environment.copy()
                for key, value in extras.items():
                    if value is None:
                        environment.pop(key, None)
                    else:
                        environment[key] = value
                master, slave = pty.openpty()
                try:
                    command = [str(self.binary), "trace", "view", "--session", str(session)]
                    if color:
                        command.extend(["--color", color])
                    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=slave,
                                               stderr=subprocess.PIPE, env=environment)
                    self.processes.append(process)
                    os.close(slave)
                    slave = -1
                    chunks = []
                    while True:
                        ready, _, _ = select.select([master], [], [], 5)
                        self.assertTrue(ready, "TTY viewer did not finish")
                        try:
                            chunk = os.read(master, 65536)
                        except OSError:
                            break
                        if not chunk:
                            break
                        chunks.append(chunk)
                    process.wait(timeout=5)
                    self.assertEqual(0, process.returncode)
                    self.assertEqual(expect_color, b"\x1b" in b"".join(chunks))
                finally:
                    os.close(master)
                    if slave >= 0:
                        os.close(slave)
        rejected = subprocess.run([str(self.binary), "trace", "view", "--session", str(session),
                                   "--color", "rainbow"], capture_output=True, env=self.environment)
        self.assertEqual(64, rejected.returncode)

    def test_colored_malformed_json_and_incomplete_unicode_are_safe(self):
        body = b'{"key":"raw\x1b[2J\x07", !!! ["value", -3]\n' + b'"incomplete:\xf0\x9f'
        session = self.fixture_session([("SEND", body[:12]), ("SEND", body[12:])])
        rendered = self.view(session, "always")
        plain = re.sub(rb"\x1b\[[0-9;]*m", b"", rendered)
        self.assertNotIn(b"\x1b", plain)
        self.assertNotIn(b"\x07", plain)
        self.assertIn(b"\\x1B[2J\\x07", plain)
        self.assertIn(b'"incomplete:\\xF0\\x9F', plain)
        plain.decode("utf-8", errors="strict")

    def test_single_16_and_64_mib_json_are_lossless_with_slow_consumers_and_viewer(self):
        for mib, final_lf in ((16, True), (64, False)):
            with self.subTest(mib=mib, final_lf=final_lf):
                self.trace = self.root / f"large-{mib}"
                prefix = '{"text":"中文🐈'.encode()
                suffix = b'"}' + (b"\n" if final_lf else b"")
                payload = prefix + b"x" * (mib * 1024 * 1024 - len(prefix) - len(suffix)) + suffix
                process = self.start("slow-echo")
                write_errors = []
                def write_input():
                    try:
                        process.stdin.write(payload)
                        process.stdin.close()
                    except Exception as error:
                        write_errors.append(error)
                writer = threading.Thread(target=write_input)
                writer.start()
                response = bytearray()
                while True:
                    chunk = process.stdout.read(65521)
                    if not chunk:
                        break
                    response.extend(chunk)
                    time.sleep(.0003)
                writer.join(timeout=10)
                self.assertFalse(writer.is_alive())
                self.assertFalse(write_errors)
                process.wait(timeout=10)
                self.assertEqual(0, process.returncode, process.stderr.read())
                self.assertTrue(payload == response, "Native response differs from the full input JSON")
                del response
                session = self.session()
                self.assertTrue(payload == (session / "send.raw").read_bytes(), "SEND raw is truncated or changed")
                self.assertTrue(payload == (session / "recv.raw").read_bytes(), "RECV raw is truncated or changed")
                rows = self.assert_complete(session)
                self.assertEqual(len(payload), sum(row[4] for row in rows if row[2] == "DELIVERED"))
                rendered = self.view(session, "never")
                # Direction changes insert display-only line breaks; reconstruct
                # the single frame in each direction and compare every body byte.
                recovered = {b"SEND": bytearray(), b"RECV": bytearray()}
                direction = None
                for line in rendered.splitlines():
                    match = re.fullmatch(rb"\[\d+\.\d+\] #\d+ (SEND|RECV|ERROR)(?: \(continued\))?", line)
                    if match:
                        direction = match[1]
                    elif line.startswith(b"Session ended."):
                        direction = None
                    elif direction in recovered:
                        recovered[direction].extend(line)
                for direction, body in recovered.items():
                    self.assertTrue(payload.rstrip(b"\n") == body, f"{direction.decode()} viewer body was truncated or changed")
                self.assertNotIn(b"DELIVERED", rendered)
                self.assertNotIn(b" STATUS", rendered)

    def test_exact_binary_transport_and_raw_event_offsets(self):
        payload = ('{"text":"中文、emoji 🐈","spaces": [1, 2]}\r\n'.encode()
                   + b'  {"invalid":"\xff\xfe"}\r\n'
                   + bytes(range(256)) * 600
                   + b'{"final":"no newline"}')
        process = self.start()
        output, errors = process.communicate(payload, timeout=15)
        self.assertEqual(0, process.returncode, errors)
        self.assertEqual(payload, output)
        session = self.session()
        self.assertEqual(payload, (session / "send.raw").read_bytes())
        self.assertEqual(payload, (session / "recv.raw").read_bytes())
        rows = self.assert_complete(session)
        delivered = [row for row in rows if row[2] == "DELIVERED"]
        self.assertEqual(len(payload), sum(row[4] for row in delivered))
        self.assertIn("EOF_SENT", [row[5] for row in rows if row[2] == "STATUS"])

    def test_default_profile_uses_v3_without_accessing_v2(self):
        old_profile = self.root / "home/Library/Application Support/Shattered Pixel Dungeon CLI v2"
        old_profile.mkdir(parents=True)
        sentinel = old_profile / "do-not-read-or-change"
        sentinel.write_bytes(b"previous profile")
        command = self.command("profile")
        index = command.index("--data-dir")
        del command[index:index + 2]
        result = subprocess.run(command, input=b"", capture_output=True, env=self.environment, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        expected = self.root / "home/Library/Application Support/Shattered Pixel Dungeon CLI v3"
        self.assertEqual(str(expected), json.loads(result.stdout)["profile"])
        self.assertFalse(expected.exists(), "Native profile resolution unexpectedly created game data")
        self.assertEqual(b"previous profile", sentinel.read_bytes())
        self.assertEqual([sentinel], list(old_profile.iterdir()))
        self.assert_complete(self.session())

    def test_engine_argument_boundaries_and_trace_flags_are_not_forwarded(self):
        literal = "spaces ' quotes ; $(touch should-not-exist) 中文"
        process = self.start("args", [literal, "--looks-like-a-flag"])
        output, errors = process.communicate(timeout=10)
        self.assertEqual(0, process.returncode, errors)
        arguments = json.loads(output)
        self.assertEqual([literal, "--looks-like-a-flag", "run", "--machine", "--data-dir", str(self.profile)], arguments)
        self.assertFalse((self.root / "should-not-exist").exists())
        self.assert_complete(self.session())

    def test_send_is_recorded_before_delayed_response(self):
        gate = self.root / "allow-response"
        process = self.start("gated", [gate])
        request = '{"request":"等待响应"}\r\n'.encode()
        process.stdin.write(request)
        process.stdin.flush()
        session = self.session()
        wait_until(lambda: self.read_bytes(session / "send.raw") == request, "recorded send before response")
        self.assertEqual(b"", self.read_bytes(session / "recv.raw"))
        self.assertIsNone(process.poll())
        self.assertTrue((session / ".incomplete").exists())
        gate.touch()
        output, errors = process.communicate(timeout=10)
        self.assertEqual(0, process.returncode, errors)
        self.assertEqual(b"REPLY:" + request, output)
        self.assert_complete(session)

    def test_viewer_exit_and_reopen_do_not_end_or_replay_engine(self):
        gate = self.root / "allow-response"
        process = self.start("gated", [gate])
        request = b'{"id":"only-once"}\n'
        process.stdin.write(request)
        process.stdin.flush()
        session = self.session()
        wait_until(lambda: self.read_bytes(session / "send.raw") == request, "initial request")
        for viewer_signal in (signal.SIGINT, signal.SIGTERM):
            viewer = subprocess.Popen([str(self.binary), "trace", "view", "--session", str(session)],
                                      stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                      env=self.environment)
            self.processes.append(viewer)
            ready, _, _ = select.select([viewer.stdout], [], [], 4)
            self.assertTrue(ready, "Viewer did not render existing events")
            displayed = os.read(viewer.stdout.fileno(), 65536)
            self.assertTrue(displayed)
            viewer.send_signal(viewer_signal)
            viewer.wait(timeout=5)
            self.assertIsNone(process.poll(), "Viewer exit stopped the game engine")
            self.assertEqual(request, self.read_bytes(session / "send.raw"))
        gate.touch()
        output, errors = process.communicate(timeout=10)
        self.assertEqual(0, process.returncode, errors)
        self.assertEqual(b"REPLY:" + request, output)
        self.assert_complete(session)

    def test_reopen_script_quotes_paths_without_shell_interpolation(self):
        self.trace = self.root / "trace space ' $(touch INJECTED)"
        process = self.start()
        output, errors = process.communicate(b"original\n", timeout=10)
        self.assertEqual(0, process.returncode, errors)
        self.assertEqual(b"original\n", output)
        script = self.session() / "open-viewer.command"
        subprocess.run(["/bin/sh", "-n", str(script)], check=True, capture_output=True)
        viewer = subprocess.Popen(["/bin/sh", str(script)], stdin=subprocess.DEVNULL,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  env=self.environment, cwd=self.root)
        self.processes.append(viewer)
        try:
            rendered, diagnostics = viewer.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            viewer.terminate()
            rendered, diagnostics = viewer.communicate(timeout=5)
        self.assertIn(b"original", rendered)
        self.assertFalse((self.root / "INJECTED").exists())
        self.assertFalse(diagnostics, diagnostics)

    def test_finished_incomplete_session_ignores_reused_or_invalid_pid_metadata(self):
        process = self.start()
        process.communicate(b"recorded before a simulated crash\n", timeout=10)
        self.assertEqual(0, process.returncode)
        session = self.session()
        index = session / "events.tsv"
        lines = index.read_text().splitlines(keepends=True)
        self.assertIn("EXITED:0", lines[-1])
        index.write_text("".join(lines[:-1]))
        for metadata in ("recorder_pid=%d\n" % os.getpid(), "invalid or obsolete marker contents\n"):
            with self.subTest(metadata=metadata):
                marker = session / ".incomplete"
                marker.write_text(metadata)
                marker.chmod(0o600)
                viewer = subprocess.run([str(self.binary), "trace", "view", "--session", str(session)],
                                        stdin=subprocess.DEVNULL, capture_output=True,
                                        env=self.environment, timeout=3)
                self.assertEqual(0, viewer.returncode, viewer.stderr)
                self.assertIn(b"Recording incomplete", viewer.stdout)

    def test_trace_open_uses_current_binary_and_never_executes_session_script(self):
        process = self.start()
        _, errors = process.communicate(b"trusted original bytes\n", timeout=10)
        self.assertEqual(0, process.returncode, errors)
        session = self.session()
        marker = self.root / "UNTRUSTED_SCRIPT_EXECUTED"
        (session / "open-viewer.command").write_text("#!/bin/sh\ntouch '" + str(marker) + "'\n")
        capture = self.root / "intercepted-open.txt"
        environment = {**self.environment, "SPDCTL_TEST_OPEN_CAPTURE": str(capture)}
        result = subprocess.run([str(self.open_binary), "trace", "open", "--session", str(session)],
                                stdin=subprocess.DEVNULL, capture_output=True, env=environment, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(marker.exists())
        command, mode, generated_script = capture.read_text().split("\n", 2)
        self.assertEqual("700", mode)
        self.assertIn(str(self.open_binary), generated_script)
        self.assertIn(" trace view --session ", generated_script)
        self.assertIn(str(session), generated_script)
        self.assertNotEqual(session / "open-viewer.command", Path(command))
        self.assertFalse(Path(command).exists(), "Temporary Terminal command was not removed")
        self.assertFalse(Path(command).parent.exists(), "Temporary Terminal directory was not removed")

    def test_terminal_open_failure_keeps_recording_and_engine_operation(self):
        capture = self.root / "failed-open.txt"
        environment = {**self.environment, "SPDCTL_TEST_OPEN_CAPTURE": str(capture),
                       "SPDCTL_TEST_OPEN_BEHAVIOR": "fail"}
        command = self.command()
        command[0] = str(self.open_binary)
        command.remove("--no-terminal")
        result = subprocess.run(command, input=b"request-after-viewer-failure\n",
                                capture_output=True, env=environment, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b"request-after-viewer-failure\n", result.stdout)
        self.assertIn(b"TERMINAL_OPEN_FAILED", result.stderr)
        temporary_command = Path(capture.read_text().splitlines()[0])
        self.assertFalse(temporary_command.exists())
        self.assertFalse(temporary_command.parent.exists())
        session = self.session()
        self.assertEqual(result.stdout, (session / "send.raw").read_bytes())
        self.assertEqual(result.stdout, (session / "recv.raw").read_bytes())
        self.assert_complete(session)

    def test_terminal_control_and_invalid_utf8_are_rendered_safely(self):
        payload = '中文'.encode() + b'\x1b]52;c;ZWNo\x07\x1b[2J\r\x00\x08\xff\xfe\n'
        process = self.start()
        output, errors = process.communicate(payload, timeout=10)
        self.assertEqual(0, process.returncode, errors)
        self.assertEqual(payload, output)
        session = self.session()
        viewer = subprocess.Popen([str(self.binary), "trace", "view", "--session", str(session)],
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  env=self.environment)
        self.processes.append(viewer)
        try:
            rendered, diagnostics = viewer.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            viewer.terminate()
            rendered, diagnostics = viewer.communicate(timeout=5)
        rendered.decode("utf-8", errors="strict")
        self.assertIn('中文'.encode(), rendered)
        for dangerous in (b'\x1b]52', b'\x1b[2J', b'\x07', b'\x00', b'\x08', b'\xff', b'\xfe'):
            self.assertNotIn(dangerous, rendered)
        self.assertEqual(payload, (session / "recv.raw").read_bytes())
        self.assertFalse(diagnostics, diagnostics)

    def test_valid_utf8_split_across_raw_events_stays_readable(self):
        gates = self.root / "fragment-acknowledgements"
        gates.mkdir()
        expected = "中文🐈".encode()
        process = self.start("fragmented", [gates])
        session = self.session()
        for index in range(len(expected)):
            wait_until(lambda: len(self.read_bytes(session / "recv.raw")) == index + 1,
                       "individual UTF-8 byte %d" % index)
            (gates / str(index)).touch()
        output, errors = process.communicate(timeout=10)
        self.assertEqual(0, process.returncode, errors)
        self.assertEqual(expected, output)
        rows = self.assert_complete(session)
        self.assertEqual([1] * len(expected), [row[4] for row in rows if row[2] == "RECV"])
        viewer = subprocess.run([str(self.binary), "trace", "view", "--session", str(session)],
                                stdin=subprocess.DEVNULL, capture_output=True,
                                env=self.environment, timeout=5)
        self.assertEqual(0, viewer.returncode, viewer.stderr)
        decoded = viewer.stdout.decode("utf-8", errors="strict")
        for character in "中文🐈":
            self.assertIn(character, decoded)
        self.assertNotIn("\\xE4", decoded)
        self.assertNotIn("\\xe4", decoded)

    def test_bidirectional_backpressure_preserves_large_output_and_diagnostics(self):
        payload = b"request-content\n" * 131072
        process = self.start("flood")
        output, errors = process.communicate(payload, timeout=25)
        expected = b"".join(bytes([index % 251]) * 8192 for index in range(1024))
        expected += ("\nINPUT_BYTES=%d\n" % len(payload)).encode()
        self.assertEqual(0, process.returncode, errors[-2000:])
        self.assertEqual(expected, output)
        session = self.session()
        self.assertEqual(payload, (session / "send.raw").read_bytes())
        self.assertEqual(expected, (session / "recv.raw").read_bytes())
        self.assertIn(b"diagnostic:" + b"x" * 131072 + b"\n", (session / "stderr.raw").read_bytes())
        self.assert_complete(session)

    def test_slow_controller_can_read_final_response_after_engine_has_exited(self):
        process = self.start("large-final")
        session = self.session()
        raw = wait_until(lambda: self.read_bytes(session / "stderr.raw"), "engine PID")
        pid = int(raw.split(b"ENGINE_PID=", 1)[1].splitlines()[0])

        def engine_has_exited():
            try:
                os.kill(pid, 0)
                return False
            except ProcessLookupError:
                return True

        wait_until(engine_has_exited, "engine exited before controller reads output")
        self.assertIsNone(process.poll(), "Fixture did not produce stdout backpressure")
        time.sleep(6)
        self.assertIsNone(process.poll(), "The recorder discarded a slow controller's final response")
        output, errors = process.communicate(timeout=10)
        self.assertEqual(0, process.returncode, errors)
        self.assertEqual(b"z" * 131072, output)
        self.assertEqual(output, (session / "recv.raw").read_bytes())
        rows = self.assert_complete(session)
        self.assertNotIn("DRAIN_TIMEOUT", [row[5] for row in rows if row[2] == "STATUS"])

    def test_nonzero_engine_exit_and_final_frame_are_preserved(self):
        process = self.start("finite")
        output, _ = process.communicate(timeout=10)
        self.assertEqual(9, process.returncode)
        self.assertEqual(b"final frame without LF", output)
        self.assert_complete(self.session())

    def test_early_engine_stdin_close_does_not_kill_proxy_with_sigpipe(self):
        process = self.start("close-input")
        output, _ = process.communicate(b"request\n" * 262144, timeout=10)
        self.assertGreaterEqual(process.returncode, 0)
        self.assertIn(b"ENGINE_CLOSED_INPUT", output)
        session = self.session()
        self.assertEqual(output, (session / "recv.raw").read_bytes())
        rows = self.assert_complete(session)
        self.assertIn("CHILD_STDIN_BROKEN", [row[5] for row in rows if row[2] == "STATUS"])

    def assert_signal_is_forwarded_and_reaps_engine(self, requested_signal):
        process = self.start("signal")
        session = self.session()
        raw = wait_until(lambda: (data if b"SIGNAL_READY" in (data := self.read_bytes(session / "stderr.raw")) else None), "engine signal handler")
        pid = int(raw.split(b"ENGINE_PID=", 1)[1].splitlines()[0])
        process.send_signal(requested_signal)
        process.communicate(timeout=10)
        self.assertEqual(128 + requested_signal, process.returncode)
        self.assertIn(("ENGINE_SIGNAL=%d" % requested_signal).encode(), (session / "stderr.raw").read_bytes())
        statuses = [row[5] for row in self.events(session) if row[2] == "STATUS"]
        self.assertIn("SIGNAL:%d" % requested_signal, statuses)
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_interrupt_forwards_signal_and_reaps_engine(self):
        self.assert_signal_is_forwarded_and_reaps_engine(signal.SIGINT)

    def test_termination_forwards_signal_and_reaps_engine(self):
        self.assert_signal_is_forwarded_and_reaps_engine(signal.SIGTERM)

    def test_closed_outer_stdout_stops_and_reaps_flooding_engine(self):
        process = self.start("flood")
        session = self.session()
        raw = wait_until(lambda: self.read_bytes(session / "stderr.raw"), "engine PID")
        pid = int(raw.split(b"ENGINE_PID=", 1)[1].splitlines()[0])
        process.stdout.close()
        process.stdout = None
        process.communicate(b"request\n", timeout=10)
        self.assertNotEqual(0, process.returncode)
        statuses = [row[5] for row in self.events(session) if row[2] == "STATUS"]
        self.assertIn("STDOUT_BROKEN", statuses)
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_trace_profile_ancestors_and_symlink_aliases_are_rejected(self):
        real = self.root / "existing-profile"
        real.mkdir()
        sentinel = real / "untouched"
        sentinel.write_bytes(b"preserve")
        alias = self.root / "profile-alias"
        alias.symlink_to(real, target_is_directory=True)
        paths = [(real, real), (real, real / "trace"), (real / "child", real),
                 (alias, real / "trace"), (real, alias / "trace")]
        for profile, trace in paths:
            with self.subTest(profile=profile, trace=trace):
                result = subprocess.run(self.command(profile=profile, trace=trace), input=b"",
                                        capture_output=True, env=self.environment, timeout=5)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(b"", result.stdout)
                self.assertNotIn(b"ENGINE_PID=", result.stderr)
                self.assertEqual(b"preserve", sentinel.read_bytes())
        self.assertEqual(["untouched"], sorted(path.name for path in real.iterdir()))

    def test_trace_io_failure_keeps_incomplete_and_does_not_hang(self):
        def restrict_record_size():
            resource.setrlimit(resource.RLIMIT_FSIZE, (32768, 32768))
            signal.signal(signal.SIGXFSZ, signal.SIG_IGN)
        process = self.start(preexec_fn=restrict_record_size)
        payload = b"never-silently-drop\n" * 32768
        output, _ = process.communicate(payload, timeout=15)
        self.assertNotEqual(0, process.returncode)
        session = self.session()
        self.assertTrue((session / ".incomplete").exists())
        self.assertLess(len((session / "send.raw").read_bytes()), len(payload))
        statuses = [row[5] for row in self.events(session) if row[2] == "STATUS"]
        self.assertIn("TRACE_IO_FAILED", statuses)
        self.assertTrue(payload.startswith(output), "Fault recovery reordered response bytes")


if __name__ == "__main__":
    unittest.main()
