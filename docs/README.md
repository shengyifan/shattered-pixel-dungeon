# Maintained documentation

The current CLI contract is **CLI.8.0.2 / protocol 8 / audit schema 11**, with game
**3.3.8**. Begin with the maintained guides below. Old version numbers, test counts
and machine-specific output paths in historical material are not current evidence.

## CLI and AI playthroughs

- [CLI interface](cli.md): startup, profile isolation, controller connection and observations.
- [English CLI help](cli-help.md): authoritative command and schema manual, bundled verbatim as `spdctl --help`.
- [Architecture and verification](cli-architecture.md): source responsibilities, public boundaries, review ledgers and reproducible test/research entrypoints.
- [Public Python client](cli-playthrough-client.md): independent decoding, safe access, intent diagnostics and evidence separation.
- [Semantic combat observations](cli-combat-visuals.md): public state, UI feedback, FOV-scoped cues and native warning lifetimes.
- [Continuous actions and cancellation](cli-runtime-cancellation.md): protocol-8 cancellation, settlement and native ordering.
- [Static UI coverage](cli-ui-coverage.md): inventory generation, reviewed input routes and runtime verification limits.
- [Agent working agreements](../AGENTS.md): current repository operating and release rules.
- [Project-local gameplay resources](../memory/README.md): portable skills, read-only map/cell/ballistics tools, Warrior notes and playthrough lessons.

## Dated validation records

- [CLI.8.0.2 cleanup validation](cli-8.0.2-cleanup-validation.md): this batch's implementation, executed gates and package evidence.
- [CLI.8.0.1 recovery and warning fixes](cli-8.0.1-recovery-validation.md): preceding controller, warning-lifecycle and Python repair evidence.

Older per-version implementation, rebuild, incident, one-off acceptance and token
study reports have been removed from the active docs tree. They remain in Git;
[CHANGELOG](../CHANGELOG.md) links to their fixed pre-cleanup revision. This removes
obsolete operating instructions without rewriting historical results or the exact
imported source copies in [memory](../memory/IMPORTS.md).

## Upstream build guides

- [Android](getting-started-android.md)
- [Desktop](getting-started-desktop.md)
- [iOS](getting-started-ios.md)
- [Making your own version](recommended-changes.md)

## Inputs, outputs and evidence

The [UI inventory](../game-control/src/test/resources/cli-ui-coverage.json) and
[visual-review ledger](../game-control/src/test/resources/combat-visual-reviews.json)
are reviewed test inputs, not game saves or records of a successful playthrough.
Generated traces, JSON/NDJSON, profiles, databases and build reports belong in
ignored build directories. Commit concise validation conclusions with an explicit
version, build identity, scope and limitations. Do not restore deleted generated
attachments to make historical paths appear current.
