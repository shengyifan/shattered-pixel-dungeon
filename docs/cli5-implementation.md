# CLI 5 implementation and validation

> Cleanup notice (2026-09-19): the generated build trees, raw fixture evidence and
> benchmark outputs referenced below were removed in the subsequent clean rebuild.
> The recorded results below remain historical evidence for this implementation batch;
> current package verification is in [the CLI 5 clean-rebuild record](cli-rebuild-5.0.0-20260919.md).

Release contract: CLI.5.0.0, protocol 5, audit schema 8, base game 3.3.8.
The authoritative English interface is [cli-help.md](cli-help.md); operational
requirements are in [AGENTS.md](../AGENTS.md). The default CLI v5 profile is separate
from prior profiles. No prior profile is migrated or used for regression testing.

## Public projection

The production projection shares the renderer's public-source classification.
Ordinary complete source trees are omitted by default; user/external origins,
unknown/unavailable sources, clipped text and partial diagnostics stay protected.
Source AST keys and immutable raw/reply payloads are never renamed.

UI capture carries immutable out-of-band hints without adding serialized canonical
fields or changing freshness-signature coverage. Exact displayed-item identity can
bind a current public inventory locator. Visible status, extra and level strings
and their metadata move into node.display only when they can be faithfully covered;
question marks and hidden-text boundaries remain intact. Empty placeholders and
passive covered text are pruned conservatively. Health/shield pixel bars, independent
operations, disabled explanations and dynamic bounds remain available.

Play can use per-observation entity_defs and map.effect_defs for repeated complete
descriptors only when the serialized fragment becomes smaller. Indexes never refer
to another observation. Full/source and historical before/after keep descriptors
inline. Uniform play row visibility uses one character; full expands per cell.
The existing map legend, sparse unknown gaps and 64/65-type boundary remain intact.
The sixteen field aliases and scoped defaults are listed in the authoritative help.
No general null/false/zero deletion, tile RLE, HP/XP positional tuple or ID truncation
is part of this release.

## Passive viewers

One unchanged format-1 transport directory records exact SEND, RECV and stderr bytes.
Run and trace open default to independent SEND and RECV + ERROR windows; --stream
selects a single window or direct combined view. The launcher passes argv to constant
AppleScript, verifies distinct newly created window identities, detaches game file
descriptors, bounds launch waits and never blindly retries uncertain delivery.
Automatic opening does not hold up the protocol relay. Reopen commands always use
the current trusted executable, not code loaded from a trace directory.

Both views validate all index rows/raw ranges and use the same bright bold syntax
palette without dim punctuation. ERROR bodies are bright red; Terminal backgrounds
and global settings are unchanged. ANSI and safe escaping belong only to presentation.
The two direct command files are open-send.command and open-recv.command. Closing
one viewer never stops the other, game or recorder. Ended interactive viewers retain
the existing Enter-to-close behavior.

## Verification method

All game-engine/package checks use fresh ignored fixture directories. The previous
729-frame public transcript is read only for offline production-encoder comparison;
no personal saves or private audit databases are read. Its missing new capture hints
are not guessed. Separate generated canonical fixtures exercise real hint behavior.
Reference token counts use pinned tiktoken 0.12.0/o200k_base, minified NDJSON including
LF. They are not model billing or evidence of a completed playthrough. The previous
38.20% prototype estimate is not a production acceptance claim.

## Production replay results

The final production encoder passed semantic checks for all 729 public replies in
both play and full views. This included 387 maps, 696 unknown item levels and 367
unknown curse values. Input send/receive SHA-256 and byte equality were verified
again after replay. No source capture hints were invented for the historical corpus.

| Measure | Previous recorded wire | CLI 5 play |
| --- | ---: | ---: |
| Response tokens | 1,973,022 | 1,392,278 |
| Response bytes | 6,474,647 | 4,280,193 |
| Median response tokens | 2,895 | 2,097 |
| p95 response tokens | 5,968 | 4,167 |
| Maximum response tokens | 6,459 | 4,432 |

Overall response tokens decreased **29.4342%**. Counts include all receipts and
handshakes; only the two info handshakes grew, by 582 tokens each, because they
advertise the new schema. Every other recorded response stayed equal or smaller.
Full projection totals 1,848,656 tokens, but cannot restore fields omitted by the
original recorded play response. It is not a captured full-state baseline.

| Request operation | Frames | Previous response tokens | CLI 5 response tokens | Reduction |
| --- | ---: | ---: | ---: | ---: |
| `back` | 6 | 29,790 | 20,836 | 30.06% |
| `cell` | 159 | 838,882 | 589,661 | 29.71% |
| `click` | 33 | 111,800 | 80,230 | 28.24% |
| `info` | 2 | 1,685 | 2,849 | -69.08% |
| `item` | 12 | 29,702 | 19,446 | 34.53% |
| `move` | 45 | 224,503 | 156,943 | 30.09% |
| `quit` | 1 | 5,458 | 3,761 | 31.09% |
| `req` | 335 | 28,806 | 28,696 | 0.38% |
| `rest` | 5 | 27,910 | 19,660 | 29.56% |
| `save` | 3 | 16,714 | 11,503 | 31.18% |
| `search` | 6 | 30,504 | 20,582 | 32.53% |
| `state` | 107 | 546,764 | 381,985 | 30.14% |
| `wait` | 15 | 80,504 | 56,126 | 30.28% |

Recorded requests account for 50,343 tokens with original spacing, or 43,502 after
minification. Those are separate request-format measurements, not part of the
response reduction above. The fixed sixteen aliases were used as approved;
play/full compares multiple rules and is not an isolated attribution of savings.

Six separate synthetic public fixtures cover displayed item water/strength/level,
health/shield pixels and dynamic operations, repeated hazards and ordered effects,
65-type maps, protected sources, Tengu bomb/countdown cues, and Dwarf King summoning
cues with explicit map-cell bindings. All passed; together they use 1,587 play tokens
and 1,744 full tokens (5,053 and 5,664 bytes). These constructed examples are kept
separate from historical measurements and actual-engine evidence.

Reproduction uses the checked-in `cli5_token_benchmark.py`, explicit public trace,
frozen test-only `fixtures/protocol4_public_adapter.py`, final test-runtime classpath,
and pinned tokenizer. Generated reports remain under the ignored
`desktop-control/build/fixtures/cli5-token/replay/` directory (`report.json`,
`per-message.json`, projected frames and synthetic fixtures). The historical adapter
is not available to the production protocol parser.

## Executed validation

On 2026-09-19 the full offline no-cache Gradle gate rebuilt and passed
`:control-protocol:test`, `:game-control:test`, `:desktop-control:test` and
`:desktop-control:packageMacArm64`, then wrote both runtime classpaths. After the first engine sweep
exposed an uncached scene-camera bug, the corrected production source was rebuilt through the same complete gate. Final XML totals:
**465 Java tests passed** (18 protocol, 310 game-control, 137 desktop-control), with no failures or skips.
The full Python discovery suite passed **122 tests**, including 36 native/PTY tests.

Regression coverage includes source classification against all 14 actual renderer
output kinds, protected partial sources, UI binding/pruning/state/bars, whole-list
annotations and diagnostic relocation, play/full/src/actions/history, dictionary
round trips, mixed/uniform visibility, map gaps, 64/65 types and unknown item values.
Native tests cover both viewers, filtered-index validation, bright non-dim colors,
red ERROR bodies, chunked UTF-8, invalid JSON/control bytes, large frames, independent
close/reopen and denied/uncertain/timed-out launch acknowledgements. Launch tests use
an explicit shim; they are not evidence of actual Terminal window appearance.

Documentation tests compare current versions, aliases, scoped defaults and visibility
with the production parser/info schema, parse JSON examples, validate local references,
and check bundled help equality. Native documentation tests tokenize current shell
examples and execute their options against the actual parser with isolated fixtures.
Current entry links point to CLI 5; historical CLI 2/3/4 bodies retain their original
versions and measurements.

Actual ARM64 package validation runs with no external Java home/classpath and a
restricted executable PATH, from a copied app path containing Chinese and spaces.
It passed raw UTF-8/error/duplicate-ID/EOF/lock cases and original game inventory,
throw/cancel, English-log versus Chinese-GUI, explicit save and same-run restart.
The loaded JVM and SQLite JNI are the packaged runtime dependencies. Both audit
stores pass integrity checks, and test launcher/agent classes are absent from the app.

The emitted version is `CLI.5.0.0 (protocol 5, game 3.3.8)`. The build catalog declares
protocol 5/schema 8 and build ID
`56e6b2ddd9e5929233bba01b6fe6e2c1899e09ab263548f48182a1bd463c62f1`.
Actual `spdctl --help`, the single packaged help resource and `docs/cli-help.md`
are byte-identical; catalog help byte count and SHA-256 match. All inspected launchers
and libjvm are ARM64, the plist passes validation, and the copied application passes
`codesign --verify --deep --strict`. This is local ad-hoc signing, not notarization.

The packaged launcher rejected disposable schema 1–7 profiles before writes.
Their paths, file bytes and file/directory modification timestamps remained unchanged.
The complete schema-7 pending-request fixture was also rejected without recovery.
The actual package's 47-frame public gameplay trace contains 64 current inventory
node bindings and 60 rendered-item display records, independently of synthetic hints.

The first strict Tengu-bomb test failed: its completed wait reply contained the
countdown but no smoke, while a later diagnostic state contained 20 smoke cells at
the same revision. The unchanged map and raw cue list ruled out protocol compaction.
A new emitter had no cached camera before its first particle drew; the observer's
read-only camera traversal omitted `Scene.camera()`'s main-camera fallback and
therefore skipped the existing first-draw readiness check. A regression reproduced
that null-camera result before the fix. The observer now recognizes that fallback
without populating renderer caches, emitting particles, reading private boss state,
or changing the action/receipt/waiting protocol. The original strict Tengu test then
passed smoke plus all countdowns in final action replies, modal/offscreen suppression,
natural fade and preserved public history. The initial failure evidence remains in
`cli5-validation/visual-cues.log` and `visual-camera-before.log`.

The unchanged protocol projection was replayed again against the final compiled
classes; the token totals above remain identical. A final additional documentation
link test and the full desktop-control suite passed without production changes.

The final engine sweep passed all six visual cases (Yog red warning, Goo charging,
hidden and frozen sources, Tengu bomb and hidden bomb) plus both Dwarf King cases
(visible four-style summons and hidden suppression). The actual package suite was
rerun against the final build and passed both raw-pipe and game/restart cases.
The production transport/defaults suite passed both initially missing and empty
profiles, exact format-1 transport, source-aware observations, passive viewer
close/reopen and saved restart. Seven old-schema refusal cases passed again.
The five original-UI fixtures (Warrior item inspection, identify, upgrade,
cancel-confirm and alchemy) passed earlier in this batch with the same v5 projection.

The English/Chinese deep-source matrix passed **12/12** cases: known-pane and
unknown-bag upgrade, old rendered item body after identification, user note text,
and two hidden-container worlds in each language. Both language-specific hidden-world
pairs produced identical public inspection signatures. Source assertions now request
explicit same-revision `src:true` detail instead of relying on ordinary ASTs leaking
into default play; final-action knowledge and displayed damage ranges are checked
separately. The first outdated harness assertion and the corrected full rerun are
retained in `text-matrix.log` and `text-matrix-final.log`.

Local detailed logs and receipts are in
`desktop-control/build/fixtures/cli5-validation/`: `gradle-final.log`,
`documentation-final.log`, `python-final.log`, `token-final.log`,
`visual-cues-final.log`, `king-visual-final.log`, `package-english-final.log`,
`transport-defaults-final.log`, `schema-refusal-final.log`, `text-matrix-final.log`
and `final-bundle.json`.
Generated profiles and raw traces remain ignored; only the source tests, tooling
and this human-readable validation record enter the commit.

## Acceptance limits

Actual Terminal UI access was refused by the available computer-use tool. Real
window topology and perceived brightness on the user's background therefore remain
**pending manual acceptance**. No alternate UI-control path was used to bypass that
restriction. Passing native/PTY/shim tests and compiling AppleScript do not satisfy
this separate visual check. Manual acceptance should verify two distinct titled
windows, correct channel content, bright JSON/ERROR text on the existing background,
independent closing/reopening, and the final Enter-to-close behavior.

No personal game save or private audit data was inspected or modified. These are
isolated regression and public-replay results, not a new playthrough, a victory,
all-class/all-boss coverage, Intel-hardware validation or Gatekeeper notarization.
