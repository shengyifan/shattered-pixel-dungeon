> **Historical source copy; not live instructions.** Imported 2026-10-08. Historical CLI 5/6 and schema 9 incident record. Its old versions, startup workarounds, cleanup/push permissions and commands are not current operating instructions. Use the current protocol manual and CLI.8.0.1 report.
>
> Current [project agreements](../../AGENTS.md), [protocol manual](../../docs/cli-help.md) and user instructions take precedence. Never read personal saves/private audit or use GUI input based on this record. External paths below are historical citations, not dependencies. See the [import record](../IMPORTS.md).

<!-- BEGIN IMPORTED SOURCE -->
thread_id: 01a0b9f4-2813-7ee2-b258-499ec11f6c1d
updated_at: 2026-09-23T12:47:04+00:00
rollout_path: /Users/shengyifan/.codex/sessions/2026/09/19/rollout-2026-09-19T21-56-21-01a0b9f4-2813-7ee2-b258-499ec11f6c1d.jsonl
cwd: /Users/shengyifan/Workspace/shattered-pixel-dungeon
git_branch: feature/mac-cli

# CLI 6 implementation, clean ARM64 rebuild, and PTY startup fix for `shattered-pixel-dungeon`

Rollout context: The work occurred in `/Users/shengyifan/Workspace/shattered-pixel-dungeon` on branch `feature/mac-cli`. The user required CLI-facing protocol text in English while the GUI could remain Simplified Chinese/windowed, requested all gameplay control through `spdctl`, prohibited direct save/profile reads for gameplay, requested cleanup of generated build artifacts and incident JSON, asked for a macOS rebuild and push, and later asked to diagnose and fix default `spdctl control --machine` startup failures.

## Task 1: Launch the packaged CLI and begin a Warrior run

Outcome: partial

Preference signals:

- The user explicitly asked to launch with the packaged executable and later to use “仅使用 spdctl 进行所有操作” while allowing source inspection but forbidding direct save reads -> future gameplay work must keep one machine connection, use only advertised protocol controls, and avoid inspecting game saves, profile files, or private audit stores.
- The user instructed that abnormal `spdctl` behavior should first be retried/continued, but if it blocks progress the agent must pause and record the problem locally -> distinguish recoverable protocol outcomes from true blockers, preserve request IDs and traces, and write a local issue report only when continuation is genuinely impossible.

Key steps:

- Verified the packaged executable reported `CLI.5.0.0 (protocol 5, game 3.3.8)` before the later CLI 6 changes.
- The first launch used a noninteractive executor and stdin was closed; the agent correctly recognized that the machine connection could not be reused and restarted with a persistent PTY instead of leaving two instances.
- Created a fresh Warrior profile through public controls: title screen -> `Enter the Dungeon` -> Warrior -> `Start` -> `Continue`; a successful save receipt was observed before entering the dungeon.
- Used `cell`, `move`, `click`, `req`, and `state` through the machine protocol; handled `in_progress` travel by polling the original request receipt and then obtaining a fresh state.
- Reached depth 1, defeated several sewer enemies, collected public items/gold/potions/scrolls, and explored into an aquatic/piranha area. The run remained alive but was not completed in this rollout.

Failures and how to do differently:

- Noninteractive launch with `tty:false` closed stdin, making `write_stdin` fail with: `stdin is closed for this session; rerun exec_command with tty=true`. Persistent machine sessions require a PTY or another executor mode that retains stdin.
- The gameplay route became risky in the piranha room: repeated close combat reduced HP to 7/20. The agent recognized this as tactical danger, retreated, rested, and ate food through public UI, but the rollout switched into implementation/debugging work before the game was completed. Future runs should avoid entering aquatic multi-enemy rooms at low resources and should stop or document a blocker rather than claim progress as a win.
- The original machine session used protocol 5 semantics; after the implementation batch, all new clients and docs must use protocol 6/schema 9 and the new v6 profile directory.

Reusable knowledge:

- The v6 controller and direct protocol require exact opaque `s`/`rev` values from the latest live observation. Do not derive, shorten, or infer them.
- For an `in_progress` action, expose the initial response immediately, query `req` using the original request scope and ID, preserve the original outcome/save receipts, and only then obtain a current live state. Never replay an uncertain or completed action.
- The machine protocol is serial NDJSON over one long-lived process; stdout must remain machine-clean and stderr is diagnostic. Successful synchronous action responses are already current observations and do not require unconditional extra `state` calls.

References:

- Packaged executable used during the initial v5 launch: `./desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl`.
- v5 trace from the initial launch: `/Users/shengyifan/Library/Logs/Shattered Pixel Dungeon CLI/transport/session-1789826246190761000-99641-sxMmSb`.
- Warrior run scope observed during gameplay: `run:8512f99b-e049-4d24-a648-33d2db722604`.
- Initial successful Warrior save receipt included `success:true`, `slot:1`, and receipt ID `932a0743-dfd9-4eea-a965-bc281cd9cd14`.

## Task 2: Implement and validate CLI 6 / protocol 6 / schema 9

Outcome: success

Preference signals:

- The user’s project policy, reinforced in memory, requires incrementing the CLI version after completed fixes and synchronizing launcher output, protocol metadata, build catalog, docs, help, and CHANGELOG -> the implementation was advanced to CLI 6 and the later PTY fix to CLI 6.0.1; future fixes must update every version surface and verify the emitted package version.
- The user selected a flat short-command protocol, independent new profile, complete raw transport records, and separate passive Terminal viewers -> preserve flat top-level commands, keep v6 data isolated from older profiles, ensure the child/native relay remains the sole raw recorder, and do not make viewers poll or send operations.
- The user wants coverage claims bounded by actual execution evidence -> distinguish static UI inventory, isolated fixtures, historical traces, package smoke, and real gameplay; do not turn aggregate test totals into full-game/all-class completion.

Key steps:

- Added protocol 6 and schema 9 behavior with profile-local typed base36 handles for scopes, revisions, activities, sessions, saves, maps, and current UI controls.
- Added packaged `spdctl control --machine`, which owns one `run --machine` child, allocates short request IDs after the handshake, binds actions to an explicitly displayed revision/scope, exposes initial `in_progress`, and uses local `settle` for pending actions.
- Implemented recovery bookkeeping that preserves original action outcomes, receipts, transport errors, discovery, fresh observations, and late responses separately; late historical replies cannot overwrite the live observation or revision.
- Added same-frame UI compression: operation dictionaries, node shapes, exact item-label inheritance, repeated entity/effect dictionaries, passive identity omission, and protected expanded nodes. Empty-slot removal was narrowed to confirmed visually empty slots in the fixed 20-position `InventoryPane` sidebar; dynamic `WndBag` capacity slots remain.
- Updated `docs/cli-help.md`, `AGENTS.md`, `README.md`, `docs/README.md`, `docs/cli.md`, `CHANGELOG.md`, `docs/cli6-implementation.md`, and `docs/cli6-incrementality-evaluation.md`.
- Replayed 297 real protocol-5 request/response pairs through production v6 encoders. All play/full semantic comparisons passed. Reference token totals were 20,867 requests and 698,425 responses; production v6 replay measured 8,897 requests and 538,710 responses, a 22.8679% response reduction and 23.8686% combined reduction using `tiktoken 0.12.0 / o200k_base`. This is wire-format measurement, not actual billing or full gameplay evidence.
- Clean ARM64 package validation passed: emitted `CLI.6.0.0 (protocol 6, game 3.3.8)` before the PTY fix, later `CLI.6.0.1 (protocol 6, game 3.3.8)` after it; help source/resource/output were byte-identical; package contents, ARM64 binaries, catalog hashes, and local ad-hoc signatures were checked.
- Final CLI 6 base implementation was locally GPG-signed in commit `4609292bea0b8e451c364a50027e1c282924c990`; the subsequent documentation/clean-rebuild commit `ca70cb9d10dbb0a859de266ab6b2bf43d8623f31` was pushed before the PTY fix. The final PTY fix commit was local only at rollout end.

Failures and how to do differently:

- The first full Python discovery after protocol migration exposed two old protocol-5 tests; they were renamed/migrated to protocol-6 tests. Later a syntax error was introduced while moving a test assertion and then corrected. Run the complete suite after mechanical test migrations, not only focused tests.
- The initial full Gradle gate exposed stale UI inventory line hashes and a documentation link to the old implementation record. Regenerate the static UI baseline with `:game-control:generateUiCoverage` and update all current entry links before declaring the gate green.
- Do not describe old build/replay JSON paths as current after cleanup. The clean-rebuild record explicitly marks generated artifacts as removed while retaining historical Markdown conclusions.

Reusable knowledge:

- Current authoritative v6 help is `docs/cli-help.md`; `SpdctlLauncher` loads `/cli-help.md` from the package, and `desktop-control/build.gradle` hashes it into `control-build.json`. Package validation must compare emitted help bytes to the source and bundled resource.
- Narrow ARM64 gate: `./gradlew :control-protocol:test :game-control:test :desktop-control:test :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath :desktop-control:writeTestRuntimeClasspath --no-build-cache --rerun-tasks --offline --no-daemon --console=plain`, followed by `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s desktop-control/src/test/python -p 'test_*.py'`.
- Final successful pre-PTY-fix totals were 517 Java tests and 142 Python tests; the PTY fix added tests and the later focused/full runs reported 522 Java and 147 Python tests passing.

References:

- Production controller: `desktop-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/StableController.java`.
- Launcher: `desktop-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/SpdctlLauncher.java`.
- Native relay: `desktop-control/src/main/native/spdctl-bootstrap.c`.
- Protocol projection: `game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/CompactProtocol.java` and `CompactStructures.java`.
- Final clean-rebuild report: `docs/cli-rebuild-6.0.0-20260920.md`.
- Incrementality report: `docs/cli6-incrementality-evaluation.md`.

## Task 3: Diagnose and fix default `control --machine` PTY startup failure

Outcome: success

Preference signals:

- When the user asked why startup failed and then asked to fix it, the agent was expected to preserve the running game, diagnose from raw transport evidence, and make the default command work without requiring a workaround. Future startup fixes should test the exact user-facing command in a shared PTY, not only PIPE-based smoke.
- The user asked for documentation updates with the fix -> update help, AGENTS, README/docs indexes, CHANGELOG, and a dedicated incident/fix report; keep the failure boundary and workaround distinction explicit.

Key steps:

- Original failure evidence: the child’s native trace had a complete 6,704-byte handshake and `DELIVERED` record, but the controller’s external terminal output was partial and then it emitted generic `STARTUP_FAILED`. The workaround used separate file redirection for stdout/stderr, which allowed the same controller command to work.
- Root cause: `StableController` launched the child with `redirectError(INHERIT)`. In a shared PTY, the child relay’s `O_NONBLOCK` setup on inherited stderr altered the same open-file description used by the parent controller’s stdout. The parent `PrintStream` then encountered backpressure / errno 35 (`Resource temporarily unavailable`) while writing the long handshake.
- Fix: child stderr now uses a fresh `ProcessBuilder.Redirect.PIPE`; a dedicated byte-preserving diagnostic forwarder drains it independently, keeps draining after a broken display sink, and waits only a bounded 5 seconds after child exit. Controller diagnostics are sanitized to stage codes such as `CONTROLLER_CHILD_START_FAILED`, `CONTROLLER_OUTPUT_FAILED`, `CONTROLLER_INPUT_FAILED`, `CONTROLLER_FAILED`, and `CONTROLLER_DIAGNOSTIC_READ_FAILED` without leaking paths or stack details.
- Added `ControllerDiagnosticsTest` for split UTF-8/invalid bytes, exact forwarding, bounded waits, broken sinks, and sanitized errors.
- Added `test_controller_pty.py`, compiling the production Java controller/native relay with a synthetic engine. Five tests passed: shared PTY with 7 KiB and 128 KiB handshakes, fragmented UTF-8, slow consumer, unchanged parent flags, EOF diagnostic tails, broken stdout, and stalled stderr.
- Rebuilt CLI 6.0.1. Final package smoke in a shared PTY with no stdout/stderr redirection and automatic viewers succeeded: complete 6,704-byte handshake, `state` returned title/menu-ready revision `r1`, `quit` completed, and the child exited 0. The child trace contained exactly `info`, `state`, and `quit` with complete delivery.
- Final package smoke also validated isolated Warrior creation/save/quit/restart/save/quit, old-revision local rejection, four save receipts, and viewer formatting. Final package integrity reported 522 Java tests, 147 Python tests, ARM64 binaries, help SHA-256 `ca37d3d5c2a1c24f965bb64cf020d1eb406ceeb92371f2dc353b2fa1e7eadf05`, and build ID `05600247a4eb85a685db4513f5402f374c2e7ddb25260bd7e6f7a1b333d2b654`.
- Final fix was committed locally as signed commit `405c883e231f392040c28a4b09145e55068b5687` with subject `fix(cli): isolate controller diagnostics for terminal startup`. It was not pushed at the end of the rollout.

Failures and how to do differently:

- The first direct PTY launch failed because the control path shared terminal descriptors; redirecting to files was only a workaround. Future validation must use both PIPE and shared-PTY modes, since PIPE-based package smoke alone misses open-file-description inheritance bugs.
- The failed attempt left two viewer processes; they were explicitly stopped. Future failure cleanup should terminate only the isolated test/controller process group and verify no owned viewers/children remain.
- The controller’s original generic startup error did not preserve errno or a profile emergency log because control mode failed before `profileAccepted`; the fix now reports sanitized stage/class diagnostics instead of exposing private paths.

Reusable knowledge:

- Default CLI 6.0.1 startup should work directly in a terminal/PTY; no output-file redirection is required. Keep stdout and stderr separate when parsing NDJSON because deliberately merging diagnostics and JSON can still interleave text.
- The child remains the sole owner of raw transport recording; controller-local wrappers/intents and diagnostics are not child wire evidence. Do not reconstruct raw traffic from SQLite.
- The final package is local ad-hoc signed only; this does not establish notarization, Gatekeeper, distribution signing, or Intel hardware validation.

References:

- Incident report: `docs/cli-issues/2026-09-20-cli-6.0.1-controller-pty-startup.md`.
- PTY regression: `desktop-control/src/test/python/test_controller_pty.py`.
- Diagnostic unit tests: `desktop-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/ControllerDiagnosticsTest.java`.
- Final package executable: `desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl`.
- Final trace from successful PTY smoke: `/private/tmp/spdctl-6.0.1-pty-package-cYkqea/transport/session-1789840194177331000-37592-9kBtsO`.
- Final source/package version check: `CLI.6.0.1 (protocol 6, game 3.3.8)`.

## Task 4: Final local fix commit and rollout state

Outcome: partial

Preference signals:

- The user’s project workflow requires a GPG-signed local commit after verified fixes, but pushing requires explicit user authorization. The final CLI 6 base/docs work had been pushed earlier; this PTY fix was intentionally committed locally only because the user had not asked to push in the final fix request.

Key steps:

- `git diff --cached --check` passed before commit.
- `git verify-commit HEAD` reported a good GPG signature; trust status was `U`/unknown, not a signature failure.
- Worktree was clean immediately after the local PTY fix commit; no final push was performed.

Failures and how to do differently:

- Do not report the final PTY fix as remotely available: the verified final local HEAD is `405c883e2`, while the remote had the earlier CLI 6/docs state. Push only after the user explicitly requests it.

References:

- Final local commit: `405c883e231f392040c28a4b09145e55068b5687`.
- Earlier pushed CLI 6/docs commit: `ca70cb9d10dbb0a859de266ab6b2bf43d8623f31`.
- Branch: `feature/mac-cli`; remote: `origin`.


<!-- END IMPORTED SOURCE -->
