# CLI.2.1.0 原文记录与终端查看器

> 本页为 CLI.2.1.0 历史记录；当前 CLI.3.0.1 的事件过滤、颜色和接口以[新版手册](cli-help.md)及[实施记录](cli3-implementation.md)为准。原始 transport 格式仍为版本 1。

机器入口默认记录全部收发原始字节，并打开一个独立 Terminal 查看窗口。记录器在游戏 JVM 外转发管道；原 GUI 和游戏操作仍由原机器协议驱动。

## 使用

```sh
./bin/spdctl run --machine
./bin/spdctl run --machine --no-terminal --trace-dir /absolute/transport-root
./bin/spdctl trace view --session /absolute/transport-session
./bin/spdctl trace open --session /absolute/transport-session
```

打包应用用 `Contents/MacOS/spdctl` 提供相同接口，无需外部 Java 或 Python。`--no-terminal` 仅关闭自动弹窗；原文始终记录。`--help`、`--version` 与 `trace` 命令不启动游戏或创建游戏会话。开发入口构建并冻结同一份 native 转发器与 Java classpath，运行期间后续构建不会替换其依赖。

默认记录根为 `~/Library/Logs/Shattered Pixel Dungeon CLI/transport/`，每次运行生成新的 session 子目录并在 stderr 打印位置。它必须与 `--data-dir` 指定的 profile 完全分离，符号链接别名也不能形成重叠。记录器不读取存档或审计 SQLite，不会为了显示额外发送查询。

## 原文与交付边界

- `send.raw`：实际写入游戏 JVM stdin 的字节；`recv.raw`：记录器实际从游戏 JVM stdout 读到的字节；`stderr.raw`：独立诊断流。
- `events.tsv` 使用 `SPDCTL_TRACE` 格式版本 1，按序号索引时间、方向、原始文件偏移、长度及状态。`DELIVERED` 记录向外层控制端实际写出的片段；它不证明控制端已解析或消费响应。
- 记录时不解析再序列化 JSON。CRLF、非法 UTF-8、无换行 EOF 尾帧、部分读写和失败时已接收片段均保持原字节。原文件不自动删除，不按大小截断。
- 查看器显示时间、SEND / RECV 和完整正文。异常控制字节及非法 UTF-8 用可见转义显示，避免把 ESC、退格等执行为终端控制命令；`.raw` 文件是逐字节依据。
- `.incomplete` 在开始记录时创建，只有确认记录正常收尾才移除；突然终止或记录故障不能假装成完整记录。游戏退出失败与传输记录是否完整分别判断。

公开审计的 `exchanges.response_json` 在 stdout 输出前提交，`requests.response_json` 还能随持续动作结算更新。因此实时展示从实际管道记录读取；SQLite 留作核对动作、保存回执与审计边界，不用后来查出的逻辑结果替换当时的响应。

## 查看器与故障

自动生成的 `open-viewer.command` 仅运行只读 `trace view`，由系统 Terminal 打开；它不拥有游戏管道。关闭查看窗口、Ctrl-C 或重新打开不会关闭游戏、重新发送请求或停止记录。查看器慢、暂停阅读或不运行也不影响记录器。

记录根／文件创建失败时不启动游戏。运行中遇到 `TRACE_IO_FAILED` 时停止新请求、关闭游戏 stdin 触发原 EOF 关闭流程，并尽力转发已执行动作的剩余响应；退出码非零，不插入虚构 JSON，不重放动作。stdout 断管同样停止新请求，避免游戏在失去控制端后无限运行。

Terminal 打开失败只报告查看故障和重开方法，原文继续落盘。强制终止进程不能保证游戏完成保存；恢复仍以原生成功存档为准。

## 新 profile 默认值

Java 启动器在创建本轮 profile 内容之前判断目录：不存在或为空才视为全新；任意已有内容都保留原生设置。成功通过审计 preflight、目录锁与审计打开后，在设置已加载、窗口设置尚未读取的回调中写入四项：`fullscreen=false`、`language=zh`、`intro=false`、当前游戏版本码。

全新 profile 直接显示中文窗口化标题菜单，仍按正常流程选职业并开局。游戏内教程关闭，但指引书、日志、职业解锁和徽章维持真实进度。已有教程存档不会被强制改标志，已有旧版本继续走原迁移流程；普通应用入口的默认行为不变。

## 验证与交付记录

最终构建标识为 `003d61975161460f81d9d978d30bb538c879342ab349b9d3a3543a9ae9c8f2bf`。测试使用独立目录，合成传输故障不启动真实游戏；实际包测试只通过 CLI 操作独立新局，不读取用户存档。

| 验证 | 结果 |
|---|---:|
| Java 协议 / 游戏控制 / 桌面控制 | 12 / 245 / 120，共 377 项通过 |
| 原有 Python 客户端工具 | 41 项通过 |
| 新 native 集成测试 | 19 项通过 |
| 实际原文核对器自测 | 2 项通过 |
| 实际新 profile / 重启 | 缺失与空目录共 4 个正常 JVM 会话，65 次请求及来源检查 |
| 新局传输逐字节核对 | SEND 11,276 字节；RECV / DELIVERED 1,309,373 字节 |
| 实际包原功能 | 原始五帧、原教程、投掷取消、进食日志、保存重启与历史均通过 |
| 真实启动失败 | 预期退出 1、零 stdout、两库同为 FAILED / launcher_failure |

19 项 native 测试包含 8 MiB 双向背压、游戏子进程已退出后控制端慢读 6 秒仍收齐最终响应、把中文及 emoji 拆为十个单字节 RECV 事件、信号转发／回收、查看器关闭隔离、路径和脚本转义、记录写失败，以及旧 `.incomplete` 的文件锁判定。Terminal 打开故障与篡改脚本测试使用测试专用编译 shim，没有生产注入开关。

实际新 profile 首次均显示 `TitleScene`、`zh`、窗口化；战士开局后背包立即可用，教程完成动作数为零。两次被动查看器启动、终止和重开后游戏连接、英雄和背包不变；保存后第二个包内 JVM 恢复相同 run 及原始历史响应。旧 profile 的原教程仍通过独立包回归。

先删除 13 处旧生成路径，共 507,418,762 字节；最终 native 边界修正完成后再次清空候选产物并全量重建，避免源码、native 二进制和构建清单错配。最终离线无缓存构建在 48 秒完成，33 个任务全部执行，377 项 Java 测试重新通过。构建清单中的 native 源码、开发入口与构建参数摘要和最终源码一致。

实际包 `--version` 和开发入口均输出 `CLI.2.1.0 (protocol 2, game 3.3.8)`；28,783 字节帮助与仓库手册完全相同。ARM64、深度严格签名、包内 JVM 与 SQLite JNI 通过核验；开发运行时冻结的 native 文件与本次编译结果逐字节一致。两份原实战问题 JSON 的大小和修改时间保持不变。

实际包另行验证旧 schema 1 / 2 / 3 / 4：四项均在启动游戏之前返回 `AUDIT_SCHEMA_UNSUPPORTED`，退出码 1、stdout 为空；各 profile 的文件摘要、修改时间和目录项完全不变，记录仅写入其独立测试根。

真实自动弹窗验证已启动一个新的中文窗口化标题会话；系统 Terminal 自动产生本会话的 native `trace view` 进程，关联真实 `ttys000`。但 Computer Use 工具明确禁止访问 Terminal，无法代点窗口关闭按钮；人工确认在测试等待期内未返回，所以**真实红色关闭按钮的人工 UI 验收未完成**。查看器进程的关闭／重开隔离已由 native 测试及实际游戏被动查看器测试验证，不把这两类证据混写。等待结束后测试游戏通过 CLI 正常退出、原文正常收尾，只终止了本次测试的 native 查看器；没有操控其他 Terminal 窗口。

源码及已验证测试的签名提交为 `11ef57f01`（`feat: add CLI 2.1 wire recording and independent terminal viewer`），`git verify-commit --raw` 返回 GOODSIG / VALIDSIG。之后清除测试 profile、测试复制包、原文测试数据、Python 缓存及生成的 iOS 配置，共 197,191,356 字节，保留一份当前 ARM64 应用、冻结开发运行时和 Java 测试报告。最终记录补充仅改文档，不改变已验证构建。未推送、打 tag 或发布。
