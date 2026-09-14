# Project working agreements

- Read `docs/cli-help.md` before operating or changing `spdctl`. It is the authoritative English protocol manual and the bundled `--help` source. Keep this file concise; put detailed schemas and validation results in `docs/`.
- Keep CLI requests, responses, help, diagnostics and test output English. The ordinary game GUI may remain Simplified Chinese and windowed.
- During gameplay, use the same `spdctl run --machine` process and its serial NDJSON connection. Do not read personal game saves or private audit state to choose actions. Isolated, explicitly marked test fixtures are separate from gameplay evidence.
- Send one request with a fresh ID, receive and parse its entire response through the newline, then present selected fields to the model. Preserve all original transport bytes independently of display limits.
- A successful synchronous action reply is already the current observation. Do not add an unconditional `state` query after every action.
- After `in_progress`, inspect the original request's small `req` receipt. On `COMPLETED`, `AWAITING_INPUT` or `INTERRUPTED`, obtain one fresh live `state`; do not fetch historical `reply` on the normal successful path.
- If the initial finite operation was `resolving`/`cancelling`, discover the current scope with `info` after its successful terminal receipt before reading state. Preserve any discovery failure. Ordinary `continuous_activity` needs no extra discovery.
- Keep the original action outcome and its save receipts separate from the current observation. A completed state query does not prove the previous action succeeded or saved.
- Historical `raw/reply/before/after` are for explicit diagnosis and verification. Never let a historical revision replace the current live `s/rev`, or invent an original wire response from a fresh state.
- On `REJECTED`, `UNKNOWN`, response loss, parsing/display failure or timeout, preserve the original ID and establish its outcome before any further decision. Never replay a pending, completed or uncertain action. Follow the user's stated stopping policy.
- Retain interruptible travel/rest observations and cancellation. After successful `quit`, wait for process exit; do not send another state query.
- Decode every default observation independently. Omitted schema defaults are not deltas; item and control locators are current bindings, not permanent identities. Inspect the original UI or request full detail when needed.
- Keep personal profiles untouched during implementation and regression testing. Use new isolated profiles, and report fixture outcomes separately from real playthrough progress.
- After verified changes, synchronize CLI/protocol/schema versions, launcher, build catalog, help and changelog as applicable; rebuild and verify the actual packaged executable.
- Make a scoped local GPG-signed commit after validation, check `git diff --cached --check`, and verify the signature and worktree. Push, tags and publication require a separate user request.
