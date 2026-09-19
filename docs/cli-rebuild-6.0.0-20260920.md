# CLI.6.0.0 macOS 清理重建

2026-09-20，按用户要求根据现有协议校准文档，删除旧 build 产物及故障 JSON，重新构建当前 macOS ARM64 CLI，并推送当前分支。

- 产品源码基线：`4609292bea0b8e451c364a50027e1c282924c990`，分支 `feature/mac-cli`。
- 版本保持 CLI.6.0.0、协议 6、审计 schema 9、游戏 3.3.8。本批只修正文档及重建，不更改产品行为、不递增版本。
- 当前入口见[英文帮助](cli-help.md)与[代理约定](../AGENTS.md)。仓库使用 `AGENTS.md`，没有新增同义的 `AGENT.md`。

## 文档校准

对照包内控制器、身份映射和结构编码实现，修正残留 v5 profile 示例，使用初始随机请求后接 `info.request_prefix`＋短计数器的示例，补全响应版本／身份／状态校验及原请求保留。

明确控制器 `settle` 的内部跟踪、迟到历史回复与新观察的不同身份、退出包装、混合 UI 节点解码、同帧继承和子进程原始记录边界；终端仅编号 SEND／RECV 首行以相同亮青色加粗。旧实施与 token 报告保留原结论，并标记生成附件已清理。

## 已完成的清理

删除前逐项核对：目标均为 Git 忽略的生成路径、无跟踪文件，目标自身不是符号链接，全部在本项目内；递归删除不跟随内部链接。没有正在使用旧输出的游戏或查看器，先停止了遗留 Gradle daemon。

删除 14 处旧生成路径：

- 10 个 build 目录：项目根、SPD-classes、core、control-protocol、game-control、desktop、desktop-control、services、shatteredNews、githubUpdates。
- 项目 `.gradle/`，Python 测试和 fixtures 的两处 `__pycache__/`，生成的 `ios/robovm.properties`。

合计 **9,972 个文件、1,140,321,285 字节逻辑大小**；按文件统计的分配量为 **1,175,011,328 字节**，不是实际磁盘释放量测量。旧 build 中 **323 个 JSON／JSONL／NDJSON** 一同删除，包括旧 fixture、失败记录、验证与 token 基准输出。

`docs/cli-issues/` 已无独立故障 JSON；仓库 build 以外的六个 JSON 全部是已跟踪的静态测试输入或应用资源，保留。临时目录下找到的两处 spdctl 目录属于 token 分析及依赖环境，不是故障附件，保留。个人 Application Support 档案、完整 SEND／RECV 原始记录、源码、Markdown 历史和全局依赖未删除，也未读取个人存档或私有审计内容。

构建产物可重新生成；已删除的未跟踪旧测试／故障 JSON 不能从 Git 恢复。

## 本轮验证

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test \
  :desktop-control:packageMacArm64 :desktop-control:writeRuntimeClasspath \
  :desktop-control:writeTestRuntimeClasspath \
  --no-build-cache --rerun-tasks --offline --no-daemon --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s desktop-control/src/test/python -p 'test_*.py'
```

Gradle 干净构建用时 **57 秒，34 个任务全部执行**，没有使用旧项目产物或任务构建缓存；全局依赖缓存保留并离线使用。

| 检查 | 通过 | 失败／错误／跳过 |
| --- | ---: | ---: |
| control-protocol Java | 18 | 0 |
| game-control Java | 321 | 0 |
| desktop-control Java | 178 | 0 |
| Java 总计 | **517** | **0** |
| Python | **142** | **0** |

实际包验证通过：

- `spdctl --version` 为 `CLI.6.0.0 (protocol 6, game 3.3.8)`，包内 schema 为 9。
- 源码帮助、包内唯一帮助资源、实际 `--help` 输出和清单完全一致：**41,546 字节**，SHA-256 `0e1ed22cd3c59c08d76c9fdb3715ddaef7260c6f569d7f5a7afac0e0bf53fee3`。
- **3,313 项构建清单**全部匹配当前磁盘文件及 SHA-256，并重新计算 build ID。
- **2,848 个 main class、926 个资源文件项**与包内数据逐字节相符；资源项计数包含不同模块处理的重复资源，不表示 926 个独立资源名称。**214 个测试 class**及额外检查的 fixture/agent 入口未进入包，`StableController.class` 已入包。
- 应用 JAR 与 release JAR 一致；签名后原生 launcher 的代码段与字符串段与新编译输出一致。
- 包内 **34 个 Mach-O 文件**全部为 ARM64；整个应用及各 Mach-O 的本地 ad-hoc 签名核对通过。不是公证或真实 Intel 硬件验收。
- 在无效 `JAVA_HOME/JDK_HOME/CLASSPATH` 和仅系统 PATH 的环境下，实际 help/version 正常；实际控制器亦使用包内运行时。
- 包内控制器在全新隔离战士档案完成保存退出、重启继续和再次保存退出：会话前缀 `t1/t2`、8/7 条原始请求、4 个独立保存回执；旧 revision 在本地拒绝，每次仅一个子记录器，quit 后无额外 state。
- 实际包查看器验证只有编号 SEND/RECV 首行加粗，二者均为亮青色；去除 ANSI 后与无色输出一致，查看前后原始记录不变。这是渲染字节验证，不声称人工检查了 Terminal 窗口外观。
- 中文／空格路径的应用副本通过 5 帧原始管道测试、非法 UTF-8、重复请求、档案锁、EOF、无末尾换行、内置 JVM／SQLite、plist 及隔离审计完整性检查。
- schema 1–8 的 8 个隔离旧档案全部在写入前拒绝，原文件字节、目录内容及时间戳不变。

本轮构建标识：

```text
f86eb53d9c76ec85db45f68f5983d5f36099d78f73d177185bd003055812a7f8
```

帮助内容更新使 build ID 与上一实施批次不同，CLI 版本保持 6.0.0。既有 deprecated／unchecked 和 Python ResourceWarning 保留在日志中；没有把上批 297 帧 token 重放或 Boss 矩阵结果算成本轮重新执行的结果。

## 验证后清理与当前产物

保留一份新 ARM64 应用 `desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`、一份新的开发冻结运行时及本轮编译／测试输出。

验证后删除新建的 `desktop-control/build/fixtures/`（402 个文件、159,290,553 字节，含测试应用副本、隔离档案和 41 个结构化文件）以及重新生成的 iOS 配置。验证摘要先复制到 `desktop-control/build/rebuild-6.0.0-20260920/`；其中引用的原 fixture 路径只表示当时测试输入，已不再存在。

本轮保留的验证目录包含清理清单、构建和 Python 日志、Java 汇总、实际包完整性脚本及结果、控制器／原始管道／schema 拒绝摘要、验证后清理记录。个人实际会话的 `send.raw` 和 `recv.raw` SHA-256 与清理前记录一致。

## Git

开始时 fetch 确认 `origin/feature/mac-cli` 没有新增分歧，本地仅领先已签名的 CLI 6 功能提交。本轮文档和清理记录另作范围明确的 GPG 签名提交；提交前检查 staged diff，验签及检查工作区后普通推送到 `origin/feature/mac-cli`，并核对远端 SHA 和 ahead/behind。不强推、不打标签、不发布应用附件。
