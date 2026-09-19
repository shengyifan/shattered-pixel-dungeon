# CLI.3.0.0：短协议与彩色原文查看器

> 历史版本记录。当前版本为 CLI.5.0.0；使用方式见[当前手册](cli-help.md)，本批次验收见 [CLI 5 实施与验收](cli5-implementation.md)。下文版本、数据和结论仅属于当时的批次。

2026-09-13，基于 `9a8e28e96` 实现 CLI.3.0.0。基础游戏仍为 3.3.8，线协议升级到 3，审计 schema 升级到 6。当前操作格式以[英文手册](cli-help.md)和[中文入口说明](cli.md)为准，旧版本文档仅作为历史记录。

## 交付行为

- 请求使用平铺的 `v/id/s/rev/op` 及动作参数，取消旧 `action.execute/args` 包装；6 个查询、22 个动作和字段别名由 `WireNames` 集中维护。旧协议、旧字段和旧动作名在引擎观察/派发前拒绝。
- 成功响应为 `v/id/s/rev/st/data`，失败使用 `err`；没有恒真 ok 或重复 observation 包装。执行状态与游戏 phase 独立；实时结果采用有效的新作用域，历史查询不含顶层实时 rev。
- `CompactProtocol` 只转换已渲染的公开数据，不访问游戏对象。引擎、冻结快照和状态签名维持规范结构。字典地图、节点 ops 和全局 acts 减少重复，所有玩家决策字段、特殊控件、未知值、父级、disabled/dimmed 区别保留。
- 默认省略完整来源树；用户/外部来源、部分呈现和裁剪标记保留。`state src:true` 与 `req` 的显式快照详情提供同一冻结观察的来源，不能展开未显示内容。只有字段值和对应来源/诊断均相同时才合并节点和动作的重复描述。
- `req` 默认只读取状态回执，不展开 response/snapshot。显式选择 raw/reply/before/after/meta；原始首个交换与异步最终逻辑 reply 分开。raw/reply 不重新翻译、压缩或冒充新观察。
- 分页返回 items/next/end/until。第一次固定上界，后续页携带相同 until；保证查询自身也被审计时 limit=1 仍可结束。新轮询重新取得边界。
- 新默认 profile 为 `~/Library/Application Support/Shattered Pixel Dungeon CLI v3/`；旧 profile 不迁移或清空。schema 1–5 在创建目录、锁、应急文件或会话前被拒绝。

## 查看器与收发完整性

查看器只显示 SEND、RECV、ERROR；DELIVERED 与正常生命周期继续写入原始索引，不显示。相同 NDJSON 消息的连续分块共享头部，方向交错后显示 continued。原文排列不变。

`trace view --color auto|always|never`：auto 检查 TTY、TERM=dumb 和 NO_COLOR，显式参数优先。方向与错误标题分色，JSON key/字符串/数字/布尔及 null/标点分色。按方向保留词法器与 UTF-8 尾部，安全转义负载控制字符，不缓存完整大消息；ANSI 仅出现在查看器输出。

64 KiB native 缓冲始终是流式背压缓冲，并不是消息长度上限。Java 完整读取 NDJSON 帧，不加入整条消息字节限制。实际容量受内存、磁盘与操作系统/运行时约束；字段语义、JSON 深度、来源保护和游戏输入规则不变。外层工具的显示预算仍由该工具决定；手册客户端示例先完整收取/解析，再打印选定字段。

原始 transport 格式继续使用 `SPDCTL_TRACE 1`，因其字节记录格式未变；它不是协议版本 3 或审计 schema 6。

## 验证结果

| 项目 | 本轮结果 |
| --- | --- |
| Java | 协议 17、游戏控制 260、桌面控制 126，共 **403**；失败、错误和跳过均为 0 |
| Python | **82** 项通过，包含 **26** 项 native transport/查看器测试 |
| 大消息 | 单条 16 MiB 与 64 MiB JSON，完整 UTF-8、无末尾 LF、慢读和背压；接收、转发、raw 与显示内容核验通过 |
| 协议集成 | 顶层短动作到规范引擎参数、作用域切换、来源详情、raw/reply 保真、默认回执不读被破坏的快照、limit=1 有界分页通过 |
| 呈现 | 本地诊断与响应根诊断路径一致；事件嵌套/地图字典路径正确且去重；缺失/用户来源不会被伪造 |
| 旧数据保护 | Java 拒绝旧 schema 回归与实际包 schema 1–5 拒绝均通过；测试 DB、WAL/SHM 哨兵、其他文件及时间戳不变 |

Java 各组均在本批改动后执行并通过；最后聚合构建对未再次变化的协议/游戏控制任务复用同批结果。Python 有两条已有的 SQLite 连接 ResourceWarning，没有测试失败。

真实引擎验证均使用独立测试目录：

- **4 个击杀场景**：两种背包界面，各覆盖最后目标/仍有目标；返回版本跨过延迟后仍可使用，真正旧版本拒绝，无动作重试。
- **2 个原教程场景**：两种界面在立即响应时已就绪；等待跨过渐显时间后旧观察仍有效，无动作重试。
- **持续行走取消**：在中途原生边界取消，准备位置与最终位置一致，原请求 INTERRUPTED，重复取消与过期活动拒绝。
- **保存回执及重启**：初始菜单来源、单次保存、未保存操作的空回执、后续保存不改历史、EOF 原生保存与重启后历史一致。
- **4 个升级窗口场景**：已/未鉴定卷轴 × 固定/弹出背包；公开直觉符石识别、预览/返回/取消、护甲与纹章仅升一级、单张卷轴仅消耗一次，全部通过，无动作重试。
- 取消与保存测试的完整公开轨迹中没有 STALE_STATE；不是靠旧测试工具的潜在重试分支获得通过。

这些构造场景不计正式通关，未读取或修改个人实战目录。

## Token 与体积

`CompactProtocolSamples` 仅读取已有的公开协议证据，使用实际 Java 投影生成输入。两侧均为相同的 minified UTF-8 JSON，基准使用 `tiktoken 0.14.0` 的 `o200k_base`；分词器只安装于 build 内的独立虚拟环境，没有进入应用依赖。

| 固定样本 | 原字节 | 新字节 | 原 tokens | 新 tokens | token 减少 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 地牢完整决策状态 | 90,128 | 20,132 | 24,113 | 7,105 | **70.53%** |
| 同状态，显式来源 | 90,128 | 32,974 | 24,113 | 10,343 | 57.11% |
| 动作发现 | 26,961 | 11,907 | 7,315 | 3,586 | 50.98% |
| 其他 4 个默认状态 | 87,776–89,350 | 18,902–19,620 | 23,483–23,893 | 6,747–6,949 | 70.92%–71.27% |

代表地牢状态字节减少 **77.66%**，达到至少 70% 字节/60% 基准 token 的目标。详情模式不受这个阈值约束。这些是固定公开样本结果，不是所有地图、所有模型或整次游戏费用的保证。

## 实际 macOS 应用

原路径已重建：`desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app`。

- 包内和开发入口报告 `CLI.3.0.0 (protocol 3, game 3.3.8)`。
- 包内帮助、`--help` 与源码逐字节一致，**20,078 字节**。
- ARM64 入口与内置 JVM/SQLite、plist 和本地签名通过；生产转换/会话编译类一致，测试夹具与样本导出器未进入包。
- 中文带空格路径下的实际包通过原始帧、非法 UTF-8、重复请求、锁竞争、EOF、SQLite 完整性验证。
- 缺失/空测试 profile 各完成开局及保存重启，共 **4 次 JVM 会话**、**65 次协议请求**、**35 次显式来源检查**；中文窗口、跳过教程默认值、单次消耗及同一存档历史恢复均通过。**8,184 字节 SEND、701,079 字节 RECV** 逐字节一致。
- 原生查看器进程关闭、重开不改变游戏；在已结束的实际测试记录上验证包内 `--color always/never`，去掉 SGR 后两份正文完全一致，无 DELIVERED/STATUS 事件头。
- 实际包拒绝 schema 1–5 且测试目录不变；会话创建后、引擎启动前的应急归档冲突也正确标记 FAILED，并保留原文件。

构建标识：`7c8bbca1fd33a24bcf6d9d4fe6d0cd13dda45cd800ead90d79e01631fe3434f5`。

颜色自动模式通过 TTY 测试，实际包的 ANSI 输出通过原文校验；本批没有把 Terminal.app 窗口的截图或人工观感作为已完成验收。应用为本地签名包，不包含 Apple 公证或 Intel 实机验证。

## 可重跑的检查与本地证据

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test :desktop-control:writeRuntimeClasspath :desktop-control:writeTestRuntimeClasspath :desktop-control:packageMacArm64 --offline --console=plain
python3 -m unittest discover -s desktop-control/src/test/python -p 'test_*.py'
python3 desktop-control/src/test/python/attack_boundary_smoke.py
python3 desktop-control/src/test/python/tutorial_boundary_smoke.py
python3 desktop-control/src/test/python/cancellation_smoke.py --mode travel
python3 desktop-control/src/test/python/save_receipt_smoke.py
python3 desktop-control/src/test/python/upgrade_preview_smoke.py
python3 desktop-control/src/test/python/package_smoke.py --bundle 'desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app'
python3 desktop-control/src/test/python/transport_game_smoke.py --bundle 'desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app'
python3 desktop-control/src/test/python/schema_refusal_smoke.py --launcher 'desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl'
```

证据保留在忽略的 `desktop-control/build/`：

- `cli3-token-samples.json`、`cli3-token-report.json`、`cli3-package-verification.json`。
- `cli3-viewer-color.txt`、`cli3-viewer-plain.txt`，来自同一实际包测试记录。
- `attack-boundary-validation.json`、`tutorial-boundary-validation.json`；其他场景在各独立 profile 中保留 result 与真实 v3 public-trace。
- `fixtures/packaging3.0/transport-defaults-b0d176c5299249699586b2c226bd1b67/result.json`：实际包新档/重启/原文逐字节验证。
- `fixtures/packaging3.0/old-schema-df9443cc3498407b8eebe4d57eac6430/result.json`：实际包旧 schema 拒绝。

测试内部的规范断言适配器不是旧格式服务端兼容入口。其输入和原始轨迹始终使用实际 v3 报文；缺失来源、历史详情或游戏属性不会被补造。历史 metadata 的短 op、错误响应中的已确认保存信息与实时 revision 隔离均有回归覆盖。
