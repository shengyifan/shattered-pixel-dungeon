# CLI.1.0.1 macOS 完整清理重建

> 后续清理说明：2026-09-13 本次 CLI.3.0.0 清理重建已按用户要求删除旧 build 产物及三份本地实战故障 JSON。以下保留 CLI.1.0.1 当时的清理与验证记录；旧输出路径及当时保留的附件不表示文件目前仍存在。

2026-09-13，按用户要求移除之前的项目 build 产物，并从当前源码重新构建 macOS CLI。

- 源码：`feature/mac-cli`，`7373c07a3fb968657b5a4b3fed312c4017e03307`。
- CLI：CLI.1.0.1；基础游戏：3.3.8；协议：1。此次仅清理、重建和记录验证，没有功能修复，不递增版本。
- Java：本机 ARM64 Temurin 25.0.4+7；Gradle：9.4.0；项目 Java 字节码目标：11。
- 本轮未启动游戏窗口或新存档，不读取或清理个人 `Library/Application Support` 游戏数据。

## 旧产物清理

删除前逐项核对路径位于仓库内、不是符号链接、被 Git 忽略且不含任何已跟踪文件。共删除 13 处生成路径，删除前分配空间合计 1,070,964,736 字节，约 1.0 GiB：

```text
build/
SPD-classes/build/
core/build/
control-protocol/build/
game-control/build/
desktop/build/
desktop-control/build/
services/build/
services/news/shatteredNews/build/
services/updates/githubUpdates/build/
.gradle/
desktop-control/src/test/python/__pycache__/
ios/robovm.properties
```

其中包含所有旧应用包、冻结运行时、隔离测试 profile、账本和 build 内的验证报告。没有保留旧构建副本；清理清单只记录路径和体积等元数据。

保留 Git 跟踪的源码、配置、文档、Gradle wrapper，以及全局依赖缓存。两份 `docs/cli-issues/*-public-evidence.json` 是本地故障证据，不属于 build 产物，保留原路径；删除前后核对其大小和修改时间未变。没有使用全仓 `git clean -fdX`。

旧 CLI 工具会话 22490 在清理前已返回退出码 0，因此无需终止游戏来移除旧 runtime-images；该退出结果不用于推断任何存档的最终内容。

## 全量构建

```sh
./gradlew \
  :control-protocol:test \
  :game-control:test \
  :desktop-control:test \
  :desktop-control:packageMacArm64 \
  :desktop-control:writeRuntimeClasspath \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
```

从已清空的项目输出目录执行；禁用构建缓存与任务复用，离线使用已安装依赖。构建使用一次性 Gradle daemon，并在结束后停止。仅调用上述明确任务，没有执行 Android/iOS 构建任务。

构建日志报告 `BUILD SUCCESSFUL in 46s`，`31 actionable tasks: 31 executed`，没有 `UP-TO-DATE` 或 `FROM-CACHE` 的任务产物复用。

| 测试模块 | 通过 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| control-protocol | 8 | 0 | 0 | 0 |
| game-control | 276 | 0 | 0 | 0 |
| desktop-control | 96 | 0 | 0 | 0 |
| 合计 | 380 | 0 | 0 | 0 |

测试结束后，清理其新生成的 12 个 ledger 测试目录及 24 个 SQLite 文件。Gradle 配置阶段再次生成的 `ios/robovm.properties` 也已移除。保留 JUnit 结果与构建日志；当前 build 内没有测试 SQLite、旧场景 profile 或旧冻结运行时。

## 新产物核验

应用位置：`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`。

机器入口：`Contents/MacOS/spdctl`。本次也生成一份新的开发运行时，供 `bin/spdctl` 使用，路径由 `desktop-control/build/runtime-classpath.txt` 记录。

新包实测构建标识：

```text
83ba4a3dbd3239d214d0ad23f4c0479da1cfac3dbeba6088f7ca6029aa13868a
```

以下验证通过：

- 包内入口和新开发运行时的 `--version` 均输出 `CLI.1.0.1 (protocol 1, game 3.3.8)`。
- 在仓库外执行包内 `--help`，24,027 字节输出与 `docs/cli-help.md` 完全一致，stderr 为空。
- 包内 `control-build.json` 与新生成的构建清单一致，CLI 和游戏版本正确。
- 六个生产模块的 2,838 个项目 class 与本轮编译输出逐字节一致，Java class major version 均为 55（Java 11）。
- 包内没有测试夹具或测试代理类。
- `spdctl`、`spdctl-jvm` 和普通 GUI 启动器都是 ARM64；plist 与 `codesign --verify --deep --strict` 校验通过。
- 当前只保留一份 `.app` 与一份本次生成的开发 runtime image。

这是本地 ad-hoc 签名应用。此次没有重新执行 GUI 游玩场景或协议握手，不把过去的六场景结果记成本轮实测。

## 当前验证记录

原始数据位于 Git 忽略的 `desktop-control/build/rebuild-validation/`：

```text
cleanup.json
build.log
test-summary.json
package-verification.json
post-test-cleanup.json
```

此前其他文档中的 build 路径属于历史记录，本轮清理后不再表示对应原始文件仍然存在。两份本地公开故障 JSON 和已提交的 Markdown 分析继续保留。
