# CLI 6: offline incrementality evaluation

This report evaluates a possible future protocol direction. CLI 6 still emits
self-contained observations; no map, inventory or log deltas are enabled.

## Evidence and production baseline

The source is one recorded protocol-5 session containing 297 request/response
pairs, covering the menu and dungeon depths 1–2. Only the explicitly supplied
`send.raw` and `recv.raw` transport files were read. No personal save or audit
database was opened. Their bytes and SHA-256 digests were checked before and after:

- SEND: `ac7aaa81d319601271c1b42d822e4bc901a06fff184f5591921656e844be7adb`
- RECV: `25e0c1ff60367daa12260722dfa9b8164be84bf4e3705f8aad4cd20c1b4b087f`

`Cli6TokenSamples` feeds decoded public observations into the production
`PublicHandles`, `AuditStore` and `CompactProtocol` implementations. A fresh,
explicitly labeled, isolated audit pair beneath the output directory holds only
benchmark handle mappings. It never connects to a game. All 297 play and full
outputs passed semantic comparisons, preserving unknown values, map visibility,
resource counts, visual cues, actions and metadata. Passive ID omission is accepted
only for unreferenced display leaves; missing text or controls fail the comparison.

| Complete NDJSON reference tokens | Protocol 5 | Production CLI 6 replay |
| --- | ---: | ---: |
| Requests, total | 20,867 | 8,897 |
| Responses, total | 698,425 | 538,710 |
| Responses, median | 2,850 | 2,203 |
| Responses, p95 | 3,681 | 2,854 |
| Responses, maximum | 3,795 | 2,937 |
| First info response | 1,408 | 1,554 |

The measured response reduction is **22.87%**, and combined request/response
reduction is **23.87%**. The current discovery schema, persistent handle capability
and request prefix are included. The first info response is the only larger frame.
A representative 32-hex startup request ID and subsequent `t1.<base36>` IDs are
included; the historically duplicated request ID remains duplicated and the
malformed historical request remains malformed. These are representation tests,
not replayed game actions or repaired historical outcomes.

The tokenizer is `tiktoken 0.12.0 / o200k_base`, applied to each complete UTF-8
NDJSON frame including LF. This is not actual model billing: tool envelopes,
context reuse, controller-local greetings and model-generated control code are
outside this wire measurement. Capture-only empty-slot and child-ownership hints
were unavailable in old transport; none were invented. Only already emitted `loc`
bindings were reused as identity evidence for label inheritance. Current empty-slot
capture behavior is covered separately by isolated Java tests.
Only visually empty slots belonging to the fixed 20-position inventory sidebar
are eligible for that omission. Dynamic bag-window slots remain visible because
their count conveys free bag capacity; unknown parent containers are retained.

## Exact-value references for map and inventory

The experiment below does not design a deployed delta protocol. It replaces an
unchanged field with an explicit same-context baseline reference, and transmits a
new numbered baseline with the complete value when it changes. Context includes
scope, scene, depth and map identity. All known cells, terrain dictionaries,
visibility and effects remain part of the equality comparison. The counters are
field-local, with distinct base36 identifiers. Results count standalone field JSON,
including baseline/reference overhead, rather than claiming another measured
reduction of the complete live protocol.

| Field-level experiment | Map | Inventory |
| --- | ---: | ---: |
| Observed frames | 230 | 230 |
| Distinct complete values | 88 | 19 |
| Unchanged from preceding value in the same context | 124 | 209 |
| Self-contained field tokens | 128,825 | 31,419 |
| Theoretical reference/baseline tokens | 59,318 | 4,379 |
| Potential field tokens avoided | 69,507 | 27,040 |
| With an extra full refresh every 10 field observations | 65,102 | 7,137 |
| With an extra full refresh every 25 field observations | 60,604 | 5,481 |
| With an extra full refresh every 50 field observations | 59,487 | 4,930 |

Periodic full refreshes show the cost of deliberately re-establishing a baseline;
they do not simulate network loss or prove a safe resynchronization algorithm.
This corpus has no boss, shop, resurrection or ending coverage. Exact equality
also understates potential cell patches while avoiding assumptions about unstable
per-frame terrain dictionaries and inventory locators.

Any future implementation needs explicit negotiation, baseline identity, sequence
and acknowledgement rules, complete replacement versus deletion operations,
independent visibility/effect changes, map/scene reset rules and mandatory full
resynchronization after a missing baseline. Missing fields cannot silently mean
unchanged: current protocol omission means absence in this observation. Inventory
locators can move after sorting or pickup, so they cannot become permanent item
identities. Historical replies must never establish the live baseline.

For model token savings, the representation shown to the model matters. If a
controller expands each reference into the entire map and inventory before showing
it, wire traffic shrinks but model input does not. If the model sees references,
its exact baseline must remain available after context compaction or reconnect;
otherwise a full observation must be supplied before another decision. Baseline
cache changes cannot implicitly replace the revision attached to an action.

## Logs require event identity before incremental delivery

The recorded UI repeats `You defeated marsupial rat.` in 138 responses and
`You defeated sewer snake.` in 86. It also repeats ordinary current-state values,
including the version string in 221 responses and `lv. 1` in 195. The transport
does not classify those plain text nodes as log events. Repeated text, or the same
UI node showing text again, does not prove that it is the same game event.

No safe log-delta token saving is claimed. A future log interface should expose
append-only event sequence identities and distinguish the current HUD, temporary
combat text and retained historical log. It needs cursor bounds, retention-gap
signaling, duplicate-event handling and a full resynchronization path. Distinct
events with identical English text must remain distinct. Current HP, buffs, hazard
warnings and talent-point availability remain current state, independent of an
old log line announcing an earlier change.

## Reproduction and decision

Compile `:desktop-control:writeTestRuntimeClasspath`, then run the checked-in
`desktop-control/src/test/python/cli6_token_benchmark.py` using a Python environment
with `tiktoken==0.12.0`:

```sh
python desktop-control/src/test/python/cli6_token_benchmark.py \
  --trace-dir /absolute/path/to/the/explicit/public/transport/session \
  --classpath-file desktop-control/build/test-runtime-classpath.txt \
  --output desktop-control/build/cli6-token-replay
```

The output includes `report.json`, per-message metrics, canonical public inputs,
mapped canonical expectations, production play/full responses, projected requests
and the isolated handle registry. Original transport remains unchanged. The frozen
protocol-5 assertion adapter lives only under test `fixtures`; current clients
encode protocol 6.

Keep self-contained observations as the CLI 6 default. Before considering
negotiated map/inventory references, measure end-to-end model-visible costs and
exercise lost-baseline recovery with isolated fixtures. Establish a real public
log event identity first, then measure cursor-based delivery separately.
