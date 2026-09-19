# CLI.5.0.0 macOS 清理重建

2026-09-19，按用户要求移除此前全部 build 产物及实战问题 JSON，从当前最新源码完整重建 macOS ARM64 CLI，并推送分支。

- 构建源码：`feature/mac-cli`，`00037e180881327186259b75a134114b64cef6ed`。
- 版本保持 CLI.5.0.0、协议 5、审计 schema 8、基础游戏 3.3.8。本轮不修改产品行为，不递增版本。
- 当前协议见[英文手册](cli-help.md)，前一实施批次的语义压缩和场景结果见 [CLI 5 实施与验收](cli5-implementation.md)。

## 清理范围

删除前确认目标无 Git 跟踪文件、由 Git 忽略、不是指向外部的目录符号链接，且没有正在使用这些输出的游戏或查看器。

移除 16 处旧生成路径：

- 10 个 build 目录：根项目、SPD-classes、core、control-protocol、game-control、desktop、desktop-control、services、shatteredNews、githubUpdates。
- 项目 `.gradle/`、Python 测试及其 fixtures 的两处 `__pycache__/`、生成的 `ios/robovm.properties`。
- `/tmp/spdctl-dual-viewer-compile-check` 临时 ARM64 测试程序及 `/tmp/spdctl-dual-viewer-tests.log`。

共计 13,557 个文件，逻辑大小 2,023,736,720 字节，按文件统计的分配量为 2,087,235,584 字节（约 2.09 GB；不是文件系统实际释放空间的测量）。其中 566 个 JSON／JSONL／NDJSON 连同旧 build 删除，包括旧 fixture 结果、失败记录及基准生成数据。

`docs/cli-issues/` 中已无独立故障 JSON／JSONL／NDJSON；旧的 `/tmp/spdctl-currency-stale-evidence-20260914.json` 也已不存在，没有重复计为本轮删除。仓库 build 以外的六个 JSON 均为已跟踪的静态测试输入或资源，全部保留。

保留 Markdown 历史说明、源码、Gradle wrapper、全局依赖、个人 Application Support 档案、完整传输原文以及临时游戏会话的完整请求／响应副本。本轮没有读取个人存档或私有审计数据。历史 `build/playthroughs` 在清理前已经不存在。

## 干净构建与检查

```sh
./gradlew \
  :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'

PYTHONDONTWRITEBYTECODE=1 python3 desktop-control/src/test/python/package_smoke.py \
  --bundle "$PWD/desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app"

PYTHONDONTWRITEBYTECODE=1 python3 desktop-control/src/test/python/schema_refusal_smoke.py \
  --launcher "$PWD/desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl"
```

完整 Gradle 构建用时 52 秒，34 个任务全部重新执行，未使用旧项目输出或任务构建缓存。

| 检查 | 通过 | 失败／错误／跳过 |
| --- | ---: | ---: |
| control-protocol Java | 18 | 0 |
| game-control Java | 310 | 0 |
| desktop-control Java | 137 | 0 |
| Python | 122 | 0 |

实际应用通过中文及空格路径、内置 JVM／SQLite、5 个原始管道帧、非法 UTF-8、重复请求、档案锁、EOF、无末尾换行帧、审计完整性和 plist 验证。schema 1–7 的七个隔离旧档案均在写入前拒绝，原文件内容、路径及文件／目录修改时间不变。

实际包验证同时核对：

- `spdctl --version` 为 `CLI.5.0.0 (protocol 5, game 3.3.8)`。
- `spdctl --help`、包内唯一帮助资源和 `docs/cli-help.md` 字节一致，清单大小及 SHA-256 相符。
- 3,305 个构建清单条目完整匹配当前文件及 SHA-256，并重新计算清单 build ID。
- 2,840 个生产 class、461 个资源与包内 JAR 逐字节一致；206 个测试 class 路径及测试依赖未进入应用。
- 应用 JAR 与新 release JAR 一致，native 启动器代码段与新编译输出一致。
- 34 个应用／运行时 Mach-O 均含 ARM64，依赖指向系统或包内相对位置。
- `codesign --verify --deep --strict` 通过，签名类型为本地 ad-hoc；不代表公证或 Gatekeeper 验收。

构建标识与同源码上一轮构建一致：

```text
56e6b2ddd9e5929233bba01b6fe6e2c1899e09ab263548f48182a1bd463c62f1
```

已有 deprecated／unchecked、LWJGL Unsafe 和 Python ResourceWarning 保留在日志中。本轮没有重跑完整场景矩阵或 729 帧 token 基准，也没有将上一批次结果改写成本次执行结果。真实 Terminal 窗口外观仍沿用前一批次的待人工验收状态。

## 当前产物与记录

应用：`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`；CLI：`Contents/MacOS/spdctl`。保留一份新应用、一份供 `bin/spdctl` 使用的新冻结运行时，以及本次编译输出。

验证完成后已移除新建的 `desktop-control/build/fixtures/`（含应用副本、测试档案和 357 个文件）及重新生成的 iOS 配置。保留的测试报告内，相关 fixture 路径只代表当时的测试输入，已不再存在。

本轮报告保存在 `desktop-control/build/rebuild-5.0.0-20260919/`：清理清单、构建和 Python 日志、Java 汇总、实际包完整性验证、package smoke、schema 拒绝及验证后清理记录。实际包核对脚本 `verify-bundle.py` 也保存在该目录。旧 CLI 5 实施批次的生成报告与失败 JSON 已随旧 build 删除；历史 Markdown 中的数值和结论保留。

## Git

推送目标为 `origin/feature/mac-cli`。开始时 fetch 确认本地仅领先既有 CLI 5 签名提交，没有远端分歧。本轮清理记录另作范围明确的 GPG 签名提交，验签并检查工作区后普通推送；不强推、不打标签、不发布应用附件。
