# CLI.9.0.6 maintenance cleanup

## Status and boundary

Validated on **2026-10-12 (Asia/Shanghai)** from checkout
`5558756508338b002061b0f9176149c775999cdc`. The clean Java/package gate, complete
Python suites, package identity checks, ordinary controller/raw-transport probes,
instrumented slow quit and current save/startup failure fixtures passed. Rendered
presentation checks are recorded below. Git signing and remote delivery are
verified separately after this report; this file does not assert a commit or push.

The release identity is **CLI.9.0.6 / protocol 9 / audit schema 12 / game 4.0.2 /
code 922 / trace format 1**. The profile directory remains
`Shattered Pixel Dungeon CLI v9`. Native combat, AI, map generation and save rules
retain their upstream behavior. Existing nonempty-profile validation and immutable
historical handle/raw/reply records remain part of the current contract.

## Maintenance scope

- Remove production typed display-event polling/take adapters and migrate their
  tests to the existing FIFO peek/audit/acknowledge contract. Keep its failure
  retention and final-cut behavior. Remove unused production start forwarding;
  the simplified fake adapter now belongs only to test code.
- Remove old text/visual observer overloads, the test-certified text-resource
  branch and the RenderedStatus forwarding interface. All resource fixtures use
  their explicit GUI template, and all visual callbacks use the complete current
  occurrence/readiness contract. Native sources and semantic visibility rules
  remain unchanged.
- Remove retired CLI5/6/7 and depth-9 token-study entrypoints, historical decoder
  loaders, one-off research drivers and their dedicated tests from the maintained
  test tree. Dated reports and explicitly archived source copies remain evidence
  of their original versions.
- Replace the canonical-name protocol adapter in current acceptance clients with
  protocol-9 wire fields and the production Python decoder. Preserve complete
  failed frames, original action outcomes, save receipts and live observations
  as separate evidence.
- Require `pending_reason` and `stage` on every pending settle wrapper. Require
  the exact outstanding query as `pending_request` only for `response_pending`.
  Missing required fields fail decoding rather than entering an older-wrapper
  fallback. Completed and error wrappers retain their own schemas.
- Restrict public map/cell/ray helper input to one current wire response or
  controller wrapper. Retire implicit `{seq,response}` relay-envelope handling;
  historical archives remain unchanged and require explicit extraction before
  individual public replies are decoded.
- Remove the unreviewed pre-4.0 autonomous exploration policy while retaining
  interactive public intents, exact action settlement and current acceptance
  coverage. Native game rules do not become a Python policy.
- Synchronize active version/index/help/skill guidance and generate both runtime
  classpaths before dependent Python tests. Keep historical playthrough versions,
  source-copy bytes and dated validation results unchanged.

Current identity, profile and wire rejection tests remain current-contract tests;
they are not old-profile migration support. Base36 registry entries already in a
schema-12 profile remain immutable, while new normal/activity revisions continue
using decimal allocation. Existing GUI save loading and the branch's missing
`run_uuid` identity path remain unchanged; this cleanup does not redefine which
native saves may be loaded or silently remove their identity handling.

## Verification ledger

| Evidence | Status |
| --- | --- |
| Focused runtime/client regressions | Passed before the full gates; independent runtime/client review completed |
| Full control-protocol, game-control and desktop-control Java tests | **967 passed**: 20 + 686 + 261; zero failures, errors or skipped tests |
| Current desktop, source-inventory and public-helper Python suites | **295 + 8 + 36 passed**; desktop suite rerun after the final client binding changes |
| Current fixture/source inventories | Checked; 13,604 visual sites, no unreviewed/changed/outstanding sites; 210 text operations; 1,917 unchanged UI records |
| Guarded cleanup of generated project outputs | 12 pre-existing build/cache roots removed after containment, ignore, tracked-file and symlink checks |
| Rebuilt ARM64 app: version, exact help, catalog, contents and signature | Passed against the actual CLI.9.0.6 executable; 3,478 catalog entries checked |
| Isolated packaged controller and raw protocol | Passed new/empty profiles, saved restart, rejected old revision, original bytes and passive viewers |
| Instrumented packaged quit lifecycle | Passed initial in_progress, pending receipt, completed original receipt and exit; no post-quit state query |
| Current save/startup failure fixtures | Both save-file failure cases and the post-session startup failure case passed |
| Maintained direct development client | Full machine smoke passed with a fresh profile, using the rebuilt frozen development runtime |
| Rendered presentation boundaries | **5/5 passed** with the packaged production JAR, interface size 2 and 60 FPS; zero action retries |
| Scoped signed commit and remote branch delivery | Verified separately after this report; not inferred from local tests or package checks |

The clean gate used `--no-build-cache --rerun-tasks`: **37 tasks executed** and
the build completed successfully. The removed roots were `.gradle`, root `build`,
the build directories in SPD-classes/core/control-protocol/game-control/desktop/
desktop-control/services and both service providers, plus old client bytecode.
No previous application backup was retained. Personal profiles, external traces,
the Gradle wrapper and global tool/dependency caches were outside the cleanup.

Two non-failing Python SQLite `ResourceWarning` diagnostics remain in the desktop
suite, and Gradle reports existing deprecation warnings. Neither changed the
successful test or build result. This batch does not claim a full playthrough or
coverage of every native rendered scenario.

The source-review comparison first scanned the exact pre-cleanup HEAD and checked
it against the old ledger. It then retired 20 removed adapter sites, refreshed 10
existing body digests and 53 source-evidence line references, and removed three
empty groups. No new site was automatically approved. Retained classifications,
routes, visibility gates and lifecycles are unchanged. Removing RenderedStatus
reduced parsed UI types from 2,881 to 2,880; every existing UI record is identical.

## Actual package and fixture evidence

The delivered bundle is
`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`.
Its native CLI, JVM launcher, ordinary GUI launcher and bundled libjvm are ARM64.
The bundled JVM and SQLite JNI were observed in the ordinary raw-protocol probe.
Source, bundled and emitted help are byte-identical; all 3,478 catalog entries
match the checkout, including 3,473 corresponding packaged class/resource/help
entries. The retired RenderedStatus class and test-only launchers, benchmark
bridges and instrumentation agent are absent from the production JAR.

| Identity | SHA-256 / value |
| --- | --- |
| CLI output | `CLI.9.0.6 (protocol 9, game 4.0.2)` |
| Production JAR | `b015c6c73a044cd4a50b17ffd8cabdbdcddd5e825cd165479061a2ff9bca247a` |
| Build ID | `9ed2e5ad09942072eaec7cb6e2cb255178a9860e61776d0fe3ba96a6e33c0dbb` |
| Help | `4ef6ee3d1daa83d5ccf0a6df174a19fa52d13a9d73e2fc134a9ef5ac99fbf360` |

Fresh isolated evidence directories under `desktop-control/build/fixtures/`:

- `cli9-controller-6b9f12f28c5a495e8a5bb712aa3d2533`: ordinary bundled controller,
  29 + 7 requests over two sessions, four save receipts, unchanged saved restart,
  old revision rejected locally, help/source/default/full and viewer checks.
- `packaging9.0/transport-defaults-f4c7b587d37e4d9ebabdfb9406d36062`: missing and
  empty profiles; each saved and restarted; all four transport recordings complete
  and byte-exact. Separate passive viewer processes closed without changing play.
- `slow-quit-package-b0129cedac8f474097b305d7f52db3b8`: **test-instrumented** package
  lifecycle, one exposed in_progress reply, two settle calls, EXECUTING then
  COMPLETED original receipt, exit 0 and no unsolicited/post-quit state response.
- `runtime-517547ac12054e2a85ae0db7f8aa8cf2/save-failure-results.json`: two isolated
  **host-JVM fixture** cases. Game-file and depth-file failures retain UNKNOWN,
  refuse duplicate/uncertain actions and preserve the failed ID over restart;
  a new explicit save succeeds after the injected obstruction is removed.
- `packaging9.0/startup-failure-d94e0aaf13b04075ba143bdf48616744`: production
  launcher initializes and closes a valid profile, then an isolated import fault
  closes only the new session as FAILED with zero protocol replies.
- `runtime-5bc03bc71a4f4538bde3b1c6172e4c6c`: **packaged production JAR with the
  host JVM and test-only launcher**, covering giant/growing movement, chilled
  onset, sentry tail and chest-item glow. Each completed observation retained its
  revision across four delayed samples and accepted the original revision;
  subsequent stale actions were rejected without moving the hero. This batch
  checks one interface/FPS combination, not the preceding release's 20-case matrix.
- `../smoke/8aa9abb7-231e-4bca-ae6c-34e1e7af11bc`: direct development-client
  smoke passed after the profile-preparation and wire-field migration. This is
  the rebuilt frozen development runtime, separate from bundled-JVM acceptance.

Package identity details are in
`desktop-control/build/cli906-validation/package-identity.json`. Generated fixture
paths are local evidence, not tracked artifacts or personal-profile inputs.

Tests and package probes must use new, explicitly marked isolated profiles.
Personal saves, private audit stores and real playthrough progress are not test
inputs. A passing static inventory, a synthetic fixture, a packaged smoke and a
real playthrough remain different evidence categories.

The maintained build prerequisites are:

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s game-control/src/test/python -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s skills/pixel-dungeon-run-assistance/tests -p 'test_*.py'
```

Executed logs and the guarded-cleanup/source-review records are retained in
`desktop-control/build/cli906-validation/`. The package's verified local ad-hoc
signature does not establish notarization. Git signing, the remote branch tip and
the final clean worktree are checked separately when delivering this source.
