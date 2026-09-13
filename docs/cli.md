# spdctl 控制接口

当前版本 **CLI.3.0.0**，基础游戏 **3.3.8**，协议 **3**，审计 schema **6**。完整接口以随包提供的[英文操作手册](cli-help.md)为准；本次实现与验收见[CLI 3.0 记录](cli3-implementation.md)。历史版本文档不作为新版请求格式。

## 启动与目录

```sh
./bin/spdctl run --machine
"desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl" run --machine
```

默认使用 `~/Library/Application Support/Shattered Pixel Dungeon CLI v3/`，保留原 v2 目录不动。显式指定旧审计目录时会在写入前拒绝，不迁移、不清空。新目录默认中文窗口、跳过教程和首次前言；已有目录的设置与游戏规则保留。

机器入口在同一 JVM 启动 GUI 和串行 stdin/stdout 管道；始终保持原连接。它不能接管另一个普通 GUI 进程。所有游戏决策使用公开协议，不需要截图或 OS 键鼠输入。

## 短命令

```json
{"v":3,"id":"q1","op":"info"}
{"v":3,"id":"q2","s":"<返回的作用域>","op":"state"}
{"v":3,"id":"a1","s":"<当前作用域>","rev":"<当前版本>","op":"move","dir":"N"}
{"v":3,"id":"a2","s":"<当前作用域>","rev":"<当前版本>","op":"click","ctl":"<当前控件>"}
```

每帧需要 `v:3` 和新的 `id`；除首次 `info` 外还需要 `s`，动作需要 `rev`。参数直接放在顶层。旧请求名、旧 envelope 和 `args` 均拒绝。短 ID 仍须在整个 scope 内唯一，含存档重启后的历史；scope/rev 仍是不透明令牌。

成功返回 `v/id/s/rev/st/data`，没有 `ok`、`result.observation` 和重复的作用域。失败使用 `err`。`st` 表示请求执行状态，`data.phase` 表示游戏阶段，不能互相替代。开局等实时结果以返回的新 `s` 为准；历史查询不提供顶层实时 `rev`。

查询为 `info/state/actions/req/history/events`。游戏、控件和界面操作直接使用 `move/cell/item/click/text/choose/save/quit` 等短命令，完整 22 项见英文手册。可用操作来自 `acts` 和 `ui.nodes[].ops`；控件已有的标签、手势和范围可以由其 ops 共享。

## 精简观察与详情

默认仍返回完整的当前决策状态，不是差量。背包为 `inv`，可见实体为 `entities`，视觉线索为 `cues`；保留英雄、未知物品属性、所有独立文本/控件、父子关系、禁用和变暗状态。

地图使用 `w/h/types/cols/cells/env`，每行 `[cell,tile,vis]`，tile 指向本次地形字典。vis 的 v/s/m 分别为可见/已探索/已探明；x/y 由 cell 和 w 精确计算。只转换玩家已知地图，未知格仍省略。

CLI 系统文字为官方英文；用户和外部文字保留原文及 `text_origins` 标记。完整来源树默认省略，使用 `state` 的 `src:true` 获取同一冻结观察的来源。`pres`、裁剪和部分呈现诊断仍保留，不得因为文案降级重放已经完成的动作。裁剪来源详情也不能暴露未显示的文字或参数。

`req` 默认只返回原请求执行状态、错误、保存回执和可选详情。`get:["raw","reply","before","after","meta"]` 显式选取；before/after 来自冻结公开快照，reply 是保存的最终逻辑结果，raw 是首个实际交换的原文及输出尝试记录。它们不触发新的引擎观察，不反推丢失信息。

`history/events` 的分页数据为 `items/next/end/until`。后续页使用 after=next 并沿用同一 until 上界，保证每页一条时也能结束；新一轮轮询省略 until。保留条数限制，分页不属于传输截断。

## 执行保护与持久化

进行中的动作通过 `req` 查询，终态后显式获取 reply，再读实时状态。原生持续移动/休息可通过当前广告的 `cancel` 取消；`untarget` 只取消选格。成功、进行中和 UNKNOWN 都不能按陈旧拒绝重试。

`STALE_STATE` 表示游戏回调前拒绝；允许恢复的客户端应重新观察、核对目标/资源/控件后以新 ID、新 rev 重新决策，并设有限次数。服务端不替换版本重放。任务规定遇错暂停时，记录证据并停止操作。教程和攻击指示器的有限更新仍纳入稳定边界。

`save` 后检查 `data.persistence.saves_during_request`；空数组不确认保存。关闭选择后再 `quit`，等待响应和进程结束。存档文件与审计事务不是同一个事务；不会重放历史动作来补齐存档。公开/内部审计分离，协议不提供 SQL 或私有状态导出。

## Terminal 查看器与长度

默认打开独立 Terminal 查看器；关闭查看器不关闭游戏或记录。`--no-terminal` 仅关闭自动弹窗，`--trace-dir` 指定不与 profile 重叠的记录目录。

```sh
spdctl trace open --session /absolute/session
spdctl trace view --session /absolute/session --color auto
```

查看器只显示 SEND、RECV、ERROR。DELIVERED 和正常生命周期仍写入原始索引，但不显示。相同消息的连续分块不插入重复头，方向切换显示延续标记。颜色支持 auto/always/never；auto 遵循 TTY、TERM 和 NO_COLOR。JSON key、字符串、数字、布尔/null 分色，正文不重排；原文控制字符安全转义，颜色不进入机器 stdout 或 raw 文件。

传输和查看器没有人为的整条消息字节上限。64 KiB 是有背压的循环缓冲，不是消息上限；实际容量仍受内存、磁盘和系统资源约束。字段语义、JSON 层数、来源渲染保护和游戏输入限制保留。外层 AI 工具可以限制显示量，不能把该截断片段当完整 JSON；随附示例先完整收发，再选择交给 AI 的内容。
