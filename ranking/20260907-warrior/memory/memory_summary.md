# Shattered Pixel Dungeon 项目记忆摘要

从系统摘要对应的磁盘副本中摘出本项目内容，删除通用个人画像和其他项目条目。以下历史记录是复盘线索，不代表新的实时游戏状态。

## 本项目使用偏好

- For current Shattered Pixel Dungeon runs, advise only one confirmed action at a time; re-check real state after every action or focus/lifecycle change.
- For Shattered Pixel Dungeon mechanism/equipment questions, inspect the current checkout first; give source linkage and conditions before a short practical conclusion, rather than treating historical v3.3.8 numbers as current live-state facts.

## 项目历史

### <project-root>

#### 2026-09-06

- Codex-assisted Warrior/Gladiator run handoff: `用战士通关本地游戏`, `checkpoint.py`, `resume-d15-notes.md`, `city19-notes.md`, DM300, Dwarf King, `英勇之跃`, blood nest, Ripper Demon
  - desc: Search first when continuing the observed local-game session or applying its read-only checkpoint/focus protocol; cwd=<project-root>. [skysight memory]
  - learnings: observed history now reaches D15, City/Dwarf King planning, and the floor-22 water-route encounter, but is stale; verify `game.dat` plus matching `depthN.dat` and current screen before one next action. A `clickStep is not defined`/stream failure proves no game result. [skysight memory]

- v3.3.8 Crossbow/Dart source audit: Crossbow.java, Chilling.java, Dart.baseUses=1000, TippedDart.baseUses=1, MagicalInfusion, `飞镖有耐久吗`
  - desc: Historical source-grounded answer for Chilling Crossbow Warrior use and ordinary versus tipped Dart durability; cwd=<project-root>.
  - learnings: historical `source-code` moved; recheck current HEAD/source before using enchantment probabilities or mechanics values.

#### 2026-09-05

- ARM64 macOS app packaging and Computer Use verification: `jpackage --type app-image`, `app-macos-arm64`, `Shattered Pixel Dungeon.app`, `Invalid app`, `getScreenshot`, `pressKey("Escape")`
  - desc: Use first for the verified local Apple-Silicon package route and raw-Java-versus-packaged-app CUA boundary; cwd=<project-root>.
  - learnings: raw `desktop:debug` Java/LWJGL was not an app target, while the ARM64 packaged `.app` was screenshot/click/key controllable; this proved menu-only reversible control, not gameplay automation or distribution readiness.

## 较早的项目主题

- Shattered Pixel Dungeon source-first Warrior support: `game.dat`, `depthN.dat`, `spdctl`, `CHANGELOG`, `Dwarf King`, `LvlTransition`
  - desc: Current-run read-only safety protocol, source mechanics, save-path/build facts, and user-fork phase-commit conventions; cwd=<project-root>. Stale game state is unsafe after each action; one input, readback, and transition/focus stop gates are mandatory.
