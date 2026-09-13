# CLI.3.0.0 macOS 清理重建

2026-09-13，按用户要求删除此前的项目构建产物和实战问题 JSON，从当前源码重新构建 macOS ARM64 CLI。

- 源码：`feature/mac-cli`，`94a9f97e8bff56cfbc1bfe6b52d72dc2dba7bb2b`。
- 版本：CLI.3.0.0、协议 3、审计 schema 6、基础游戏 3.3.8。本批没有代码修复，不递增版本。
- 工具链：ARM64 Temurin 25.0.4+7、Gradle 9.4.0，Java 字节码目标 11。
- 游戏运行验证仅使用本轮创建的独立 profile，没有读取、删除或恢复个人实战 profile。

## 清理范围

删除前检查所有目标都位于仓库内、不是符号链接、被 Git 忽略且不含已跟踪文件。共删除 16 处生成路径，删除前分配空间合计 1,724,702,720 字节，约 1.61 GiB：

- 10 个项目 `build/` 目录：根项目、SPD-classes、core、control-protocol、game-control、desktop、desktop-control、services、shatteredNews、githubUpdates。
- 项目 `.gradle/`、Python 测试 `__pycache__/`、生成的 `ios/robovm.properties`。
- 以下 3 份本地实战故障 JSON，文件内容合计 3,672,953 字节；删除后移除了空的 `docs/cli-issues/evidence/`。

```text
docs/cli-issues/2026-09-12-warrior-tutorial-stale-state-public-evidence.json
docs/cli-issues/2026-09-13-warrior-upgrade-preview-execution-unknown-public-evidence.json
docs/cli-issues/evidence/2026-09-13-cli-2.1.0-warrior-attack-indicator-stale-state.json
```

Markdown 问题分析保留，并将附件说明更新为已清理。保留源码、静态测试基线、Gradle wrapper 和全局依赖缓存。此前文档中的旧 build 路径仅为历史记录。

## 构建与测试

```sh
./gradlew \
  :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'

PYTHONDONTWRITEBYTECODE=1 python3 desktop-control/src/test/python/package_smoke.py \
  --bundle "$PWD/desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app"
```

全量构建耗时 50 秒，33 个任务全部执行，没有使用旧构建输出或构建缓存。

| 测试 | 通过 | 失败／错误／跳过 |
| --- | ---: | ---: |
| control-protocol Java | 17 | 0 |
| game-control Java | 260 | 0 |
| desktop-control Java | 126 | 0 |
| Python（含 native transport） | 82 | 0 |

Python 覆盖单条 16 MiB／64 MiB JSON、慢读与背压、原始字节一致性、颜色模式和跨块查看器。实际包测试通过中文及空格路径、内置 JVM／SQLite、协议握手、非法 UTF-8、重复请求、profile 锁、EOF 和无末尾换行帧、审计库完整性等检查。

另外使用包内 `spdctl trace view` 回读本轮测试的 3 个传输会话：三种颜色模式通过，去掉查看器自身 ANSI 颜色后与无色输出完全一致，显示 SEND／RECV 并隐藏 DELIVERED／正常 STATUS。本轮没有重跑完整游玩场景矩阵。

构建有既有的 deprecated／unchecked 提示；Python 输出测试连接的 ResourceWarning，测试结果为 OK。完整日志保留在本轮构建目录。

## 当前产物与保留记录

应用：`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`。

CLI 入口：`Contents/MacOS/spdctl`。另生成一份供 `bin/spdctl` 使用的新开发运行时。

新包构建标识：

```text
95f2660fa8db9b7a0813769500e0c5b270f01971f5d031c73a526aec39bb8a98
```

在仓库外执行包内入口，`--version` 输出 `CLI.3.0.0 (protocol 3, game 3.3.8)`，`--help` 与当前手册逐字节一致，两者 stderr 为空。4 个关键 Mach-O 均为 ARM64，plist 和 `codesign --verify --deep --strict` 通过。

包内清单与生成清单一致，3,301 个清单条目的大小和 SHA-256 全部匹配；2,836 个清单内生产类、额外 13 个 services 生产类及 461 个资源与本轮编译输出逐字节一致。已编译测试／代理类型未进入生产包。

验证后删除了本轮 13 个 fixture 目录（含打包验证的应用副本与独立 profile）、26 个 SQLite 文件，以及重新生成的 `ios/robovm.properties`。当前仅保留一份应用和一份开发运行时 image；`docs/cli-issues/` 下已无 JSON 文件。

JUnit 结果、构建日志和本轮验证摘要保留在 build 内。`desktop-control/build/rebuild-validation/` 记录清理清单、测试汇总、包验证、查看器验证及测试后清理结果；摘要中的 fixture 路径指向已清理的本轮临时数据。应用使用本地 ad-hoc 签名，本轮未进行公证或 Intel 硬件验证。
