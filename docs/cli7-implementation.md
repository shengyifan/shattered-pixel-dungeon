# CLI 7 implementation and validation

## Contract

CLI.7.0.1 uses protocol 7, audit schema 10 and the independent default profile
`Shattered Pixel Dungeon CLI v7`. Protocol 6 requests and schema 1–9 profiles are
rejected, without migration or changes to their original history. The game remains
3.3.8. Generated request counter suffixes remain decimal.

Protocol 7 introduced same-frame action sharing and common-field record
templates. CLI.7.0.1 adds [rendered combat evidence and reliability fixes](cli-combat-visuals.md)
without changing those encoding boundaries. There are no deltas, previous-frame dictionaries, shortened hero/gesture
notations, new map encodings, inventory capacity summaries or GUI semantic pruning.
The exact source transport remains separate from client expansion.

## Encoding and decoding

Every `acts` entry retains its position, including duplicates. A node's `ops` is an
ordered array of inline operation objects or integer indexes into that observation's
`acts`. An index is legal only when the referenced action's `ctl` equals the node's
`id`; its expansion omits that inherited `ctl`. An explicit-control or protected
operation remains inline. Equality includes unknown fields, parameter constraints,
types, order and all public metadata, not just the operation or label.

The only template tables are `act_templates` for `acts`, `inv_templates` for `inv`,
and `ui.node_templates` for `ui.nodes`. Each entry is `{common,fields}`. A record row
is `[templateIndex,...values]`, with exactly one value for each varying field.
Common and varying fields cannot overlap. Object records and rows may mix without
changing list positions. Missing fields use a different shape or an inline record,
never a fake null. JSON object member order is not a public semantic; array order is.

Strict validation requires exactly the `common` and `fields` keys in each template:
`common` is an object and `fields` is an array of unique string field names (possibly
empty), disjoint from `common`. A present table requires its corresponding sibling
record array. Every template is validated, even if no row references it. Template
and action indexes are zero-based, nonnegative JSON integers; booleans, floats and
out-of-range values are invalid. Rows have exactly `1 + len(fields)` entries.
Only the documented snapshot roots and their UI node lists own templates; identical
field names inside unknown extensions or source ASTs have no structural meaning.

Groups have identical ordered key sets and at least two records. Only exactly equal,
type-sensitive values become common fields. Candidates are accepted only when the
entire affected fragment, including its table, has fewer minified UTF-8 bytes.
This gate is deterministic and has no runtime tokenizer dependency. Token savings
are measured separately, with every definition included.

All play/full/src views use this structure. Full retains explicit field defaults
and literal labels; src adds original full source ASTs. Protected/source-bearing
records and ancestor-addressed diagnostic subtrees remain inline. No diagnostic
path is moved to an unrelated value or silently discarded to improve compression.
The old `op_defs`/`node_shapes` wire mechanisms are unsupported.

Encoding separates public field projection, live-play binding omissions, action
sharing, templates and final diagnostic collection. Decoders first restore acts
and inv, restore applicable activity/cancel bindings before copying operation
references, then expand nodes and resolve operations/labels and remaining defaults;
JSON object field order never determines reference resolution. Malformed references
fail closed while preserving original response and request identity.

Frozen before/after snapshots independently use these structures, without inheriting
the query's live scope/revision or another snapshot's tables. Stored raw/reply,
source ASTs and preserved evidence are opaque. Historical frames never install a
new controller binding. The controller continues forwarding compact child output.

## Reproducible checks

The production Java encoder and Python client are checked independently against
complete public values. The offline replay reads only explicitly supplied public
SEND/RECV files and verifies their pairing and unchanged hashes. It does not open
game saves, profiles or audit databases. Historical missing public information
cannot be reconstructed and is not counted as restored content.

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

`cli7_token_benchmark.py` replays historical public output through production
`CompactStructures` in sharing-only, template-only and combined modes. It includes
complete minified JSON plus LF and all tables, using tiktoken 0.12.0/o200k_base;
counts are reference representation tokens, not model billing. Legacy research
decoders are isolated from the protocol-7 production client.

## Historical public-corpus measurements (2026-09-20)

These measurements describe the implementation acceptance at `18816c6bd`, not a
new run during the [2026-09-21 clean rebuild](cli-rebuild-7.0.0-20260921.md). The old
generated replay files and fixture inputs were removed from the workspace during
that cleanup; the measured results remain historical evidence.

The replay keeps all historical public content and existing map/label/default
encoding, expands only the replaced v6 UI structures, then applies the production
v7 structure encoder. Envelope `v` becomes 7; historical info/build descriptions
remain historical values, not a fabricated live v7 handshake. The corpora are not
combined. Source hashes remain unchanged.

| Representation | Historical D9: 3,880 replies | Change | CLI.6.1.1 acceptance: 20 replies | Change |
| --- | ---: | ---: | ---: | ---: |
| Original complete response | 9,924,866 | — | 51,873 | — |
| Sharing only, without record templates | 11,871,596 | +19.61% | 51,665 | -0.40% |
| Record templates only | 9,824,581 | -1.01% | 45,094 | -13.07% |
| **Production combination** | **9,824,581** | **-1.01%** | **39,673** | **-23.52%** |

The sharing-only ablation removes the replaced v6 UI row/list encoding but does not
enable its v7 template replacement; it is not an additive patch on top of v6. The
historical CLI.6.0.1 corpus predates restoration of complete global node actions:
sharing changes zero of its 3,880 frames, versus 18 of the current sample's 20.
Missing historical operation copies are not invented to inflate savings.

Historical median/p95/max move from 2,794/3,447/4,094 to 2,777/3,394/4,026 tokens.
There are 2,095 smaller frames, 1,420 larger frames (36.60%), and 365 unchanged
frames. The byte gate is not a per-frame token guarantee. The current sample moves
from 3,943/3,956/4,895 to 2,980/2,992/3,386, with 18 smaller, zero larger and two
unchanged frames. This sample covers menus/D1 only, not an entire current dungeon.

All 3,900 frames pass all three production modes: **11,700 Java round-trips** and
**11,700 Python decoded-content comparisons**. The historical v6 decoder is frozen
for these checks; the production client remains v7-only. Both corpus totals pass
the combined-token reduction gate. Earlier 24.60% research results included other
codecs and are not claimed for this release.

Metrics, per-frame costs, original/combined examples and complete replay files were
generated under the ignored `desktop-control/build/cli7-token-study-20260920/`.

## Historical package and regression validation (2026-09-20)

The following build identity, help byte count and fixture paths belong to the
implementation acceptance. Those generated artifacts have been cleaned. The
subsequent [CLI.7.0.0 rebuild](cli-rebuild-7.0.0-20260921.md) is also historical;
current package evidence is in the [CLI.7.0.1 clean rebuild report](cli-rebuild-7.0.1-clean-20260923.md).
The [CLI.7.0.1 implementation acceptance report](cli-rebuild-7.0.1-20260923.md)
retains the earlier implementation and native fixture results; its original
generated attachments were removed during the subsequent clean rebuild.

The final no-cache/rerun/offline Gradle gate completed all 34 tasks successfully:
**558 Java tests** (control-protocol 18, game-control 348, desktop-control 192),
with zero failures, errors or skips. The complete Python discovery suite passed
**246 tests**. Existing compiler deprecation notices and an intermittent SQLite
fixture ResourceWarning are warnings, not suppressed failures.

Independent review found and fixed three boundary cases before the final checks:

- All reserved-template-name collisions are rejected in an atomic encoding preflight,
  including protected roots and a later snapshot, without partially changing input.
- Activity/cancel bindings are restored before copying node action references in
  both Java and Python; pure expansion and historical snapshot contexts stay separate.
- DecodeError retains a deep copy of the failed response, valid request identity
  and enclosing controller context, without any automatic replay.

The actual rebuilt executable reports `CLI.7.0.0 (protocol 7, game 3.3.8)`.
Its **45,250-byte** `--help` output equals `docs/cli-help.md` byte for byte. The bundled
build catalog reports protocol 7/schema 10 and build ID
`11ea5f0f7353177becb2f5fe7f241a134dd338cf70d2ad7d2c33afce1f60dc2f`.
The native spdctl, JVM launcher, ordinary game launcher and libjvm are ARM64;
`codesign --verify --deep --strict` and plist validation pass. The signature supports
local testing; this is not Developer ID notarization or a distribution claim.

Actual-package isolated checks:

- Raw-pipe package test: five frames, Unicode/spaced bundle and profile paths,
  bundled JVM/SQLite, exact wire bytes, invalid UTF-8 rejection, duplicate query,
  exclusive profile lock, EOF shutdown and final frame without LF. The isolated
  audit pair passes integrity checks; a lock contender does not change emergency files.
- The actual launcher rejects schema 1–9, preserving all fixture files, bytes and
  directory/file mtimes. Java tests additionally cover a complete schema-9 pair
  with a pending action and prove it is not recovered or modified.
- Packaged controller: two sessions, **24 + 7 requests**, a fresh Warrior on D1,
  decimal request IDs across 9/10, sidebar inventory open/toggle, native item-menu
  selection and Back closure without resource use. Hero/inventory remain unchanged.
- Play/full decoded public content agrees in the dungeon, inventory and item-menu
  states. `src:true` yields 171, 135 and 97 source fields respectively. The dungeon
  src reply actually uses one action template and five node templates; source-heavy
  protected item records remain inline. The item-menu source reply legitimately
  uses no templates. Source evidence and all original wire values remain unchanged.
- Four distinct save receipts are verified. Save/restart restores matching hero
  and inventory; the old revision is rejected locally before reaching the child.
- Viewer checks preserve exact plain payload text and raw files, with bold restricted
  to SEND/RECV headers. All successfully started game sessions exit normally.

Generated evidence was written under these ignored fixture directories, which
have since been cleaned:

- `desktop-control/build/fixtures/cli7-controller-a7a1f967375e48eca9c129ed6dd58286/`
- `desktop-control/build/fixtures/packaging7.0/raw-81706453497a4e0992d205b276e20d7e/`
- `desktop-control/build/fixtures/packaging7.0/old-schema-a1f771926c6344709b5b2fe618ec7736/`

The final production codec was replayed again after review fixes; all 11,700 Java
and 11,700 Python corpus checks pass and the measured token totals above are unchanged.
No personal save or private audit was read, no previous profile was migrated, and
the pre-existing stash was neither applied nor removed. No test fixture counts as
a playthrough. Actual continuous activities did not occur in these short package
sessions; interruption/settle coverage is from automated regression fixtures, not
a new full-dungeon run. Real transport-loss injection, independent Terminal window
UI interaction, Intel hardware and Gatekeeper notarization remain outside this batch.
