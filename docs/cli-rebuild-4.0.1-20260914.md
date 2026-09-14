# CLI.4.0.1 macOS 清理重建

2026-09-14，按用户要求清理此前 build 产物和实战故障 JSON，从当前最新源码重新构建 macOS ARM64 CLI，并推送分支。

- 构建源码：`feature/mac-cli`，`727de3ca716d0cdcc087596106ee4c23943c6543`。
- 版本保持 CLI.4.0.1、协议 4、审计 schema 7、基础游戏 3.3.8。本批只清理、重建和维护记录，不递增版本。
- 工具链：ARM64 Temurin 25.0.4+7、Gradle 9.4.0，Java 字节码目标 11。

## 清理范围

删除前确认仓库内目标均被 Git 忽略、不含已跟踪文件、不是符号链接且不越出仓库；没有运行中的游戏或查看器使用这些输出。

共删除 15 处旧生成路径，删除前按文件统计的分配空间合计 **3,109,097,472 字节，约 3.11 GB**，逻辑文件大小为 3,026,495,261 字节：

- 10 个 build 目录：根项目、SPD-classes、core、control-protocol、game-control、desktop、desktop-control、services、shatteredNews、githubUpdates。
- 项目 `.gradle/`、Python 测试 `__pycache__/`、生成的 `ios/robovm.properties`。
- 实战故障附件 `docs/cli-issues/evidence/2026-09-14-cli-3.0.0-currency-stale-state.json`，434,713 字节。
- 同一故障的临时副本 `/tmp/spdctl-currency-stale-evidence-20260914.json`，434,713 字节。

删除后移除了空的 `evidence/`；`docs/cli-issues/` 中已无 JSON/JSONL。相关 Markdown 保留历史结论，并更新附件和旧 build 的现存状态。

保留源码、静态测试 JSON、Gradle wrapper、全局依赖缓存、用户存档以及 `~/Library/Logs/Shattered Pixel Dungeon CLI/transport/` 的原始传输记录。本轮没有读取或操作个人游戏进度。

## 干净构建与检查

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

全量构建用时 53 秒，33 个 Gradle 任务全部重新执行；没有复用旧项目构建输出或任务构建缓存。

| 检查 | 通过 | 失败／错误／跳过 |
| --- | ---: | ---: |
| control-protocol Java | 18 | 0 |
| game-control Java | 282 | 0 |
| desktop-control Java | 129 | 0 |
| Python | 102 | 0 |

实际包通过内置 JVM／SQLite、中文及空格路径、完整原始管道、非法 UTF-8 恢复、重复请求、profile 锁、EOF、无末尾换行帧、审计完整性、plist 与本地签名验证。所有 profile 都是本轮独立测试目录。

实际包生成的重开脚本包含 `--color always`。在 `TERM=dumb`、`NO_COLOR=1` 的 PTY 中产生 796 个 ANSI 序列，去色正文与 `--color never` 逐字节一致，原始收发与索引未改变。这里验证终端输出字节，没有操作或目测 Terminal.app 窗口。

既有 deprecated／unchecked、LWJGL Unsafe 和 Python 测试连接 ResourceWarning 保留在日志中。本轮没有重跑完整游戏场景矩阵，测试结果不计正式通关。

## 新产物

应用：`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`；CLI 入口：`Contents/MacOS/spdctl`。另保留供 `bin/spdctl` 使用的一份新冻结开发运行时。

在仓库外执行实际包，版本为：

```text
CLI.4.0.1 (protocol 4, game 3.3.8)
```

构建标识与此前同源码构建一致：

```text
78caa3f2c65d95c00db7ad43520946d037f787c14130f765d28510405eb052e3
```

27,771 字节帮助与源码、包内资源一致，help/version stderr 为空。3,301 个清单条目大小及 SHA-256 匹配；2,836 个项目生产类与包内类逐字节一致，测试类未进入应用。四个关键 Mach-O 为 ARM64，`codesign --verify --deep --strict` 通过。

验证后删除了新生成的测试 fixture 树（含应用副本、测试 profile 和 262 个文件），以及重新生成的 iOS 配置。当前只保留一份应用、一份开发 runtime image、当前编译输出及本轮报告。

本轮记录位于 `desktop-control/build/rebuild-4.0.1-20260914/`：清理清单、构建/Python 日志、JUnit 汇总、实际包与颜色验证、验证后清理结果。日志中的 fixture 路径代表当时的测试输入，相关临时目录已经删除。

## Git

推送目标为 `origin/feature/mac-cli`。开始时已获取远端，确认本地仅领先三个既有签名提交、没有远端分歧；本轮清理记录另作本地签名提交，随后使用普通 fast-forward push，不进行强推、打 tag 或发布应用附件。
