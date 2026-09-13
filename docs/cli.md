# spdctl 控制接口

本 CLI 基于原版 **Shattered Pixel Dungeon 3.3.8**（上游基线 `7b8b845a7`、游戏版本码 `896`）扩展。当前 CLI 版本为 `CLI.2.1.1`，协议版本 `2`、审计 schema `5`。三个版本独立；完整迁移进度及验收范围见 [CLI 2.0 实施记录](cli2-implementation.md)。

CLI 系统文案使用官方英文，GUI 可以选择任意已注册语言。原版 `Messages.get()`、`name()`、`desc()` 等接口继续返回 `String`；中央观测钩子按对象身份记录最终命中 key 和冻结参数，CLI 在玩家可见性筛选后输出英文和相邻的 `text_sources`。不根据中文、英文或其他字符串内容反查资源，不重建窗口或重跑显示分支。用户名字、笔记和外部内容保留原文及来源标记。

响应中的 `presentation.status` 独立于动作 `status`，取 `complete` 或 `partial`；字段诊断指出来源缺失、裁切或格式故障。纯文案故障使用安全 key 或英文占位，不把已经完成的动作改成 `EXECUTION_UNKNOWN`，也不能因此重放动作。真正的回调、稳定边界、关键快照和审计失败仍保留 UNKNOWN 保护。正文、key 和参数共用玩家知识与可见性边界；裁切或未绘制内容不会公开完整来源。

历史记录保存当时实际发送的响应，不按当前语言重译，读取历史也不要求当前游戏观察成功。旧协议及审计 schema 不兼容；启动遇到旧 schema 会在写入前拒绝，不自动迁移、删除或修复旧目录。

状态中的`observation.ui.display`给出实际GUI选定语言代码与窗口模式，例如`{"language":"zh","fullscreen":false}`。它描述当前界面，CLI文案语言仍为英文。

## 启动

`spdctl --help` 输出完整的[英文操作手册](cli-help.md)，包括启动、七个协议入口、从主菜单开局、游戏动作、多步选择、持续动作、历史查询和保存退出。该Markdown是帮助的唯一内容来源，构建时作为资源打入应用；包内帮助无需读取源码目录，也不会创建游戏profile或审计库。

源码开发入口（自动构建并冻结当前依赖，避免后续构建更换运行中的 JAR）：

```sh
./bin/spdctl run --machine --data-dir /absolute/path/to/isolated-profile
```

原生 ARM64 应用：

```sh
./gradlew :desktop-control:packageMacArm64
"desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl" run --machine --data-dir /absolute/path/to/isolated-profile
```

CLI 默认 profile 为 `~/Library/Application Support/Shattered Pixel Dungeon CLI v2/`，与普通 GUI 默认目录分开。指定的目录同时保存游戏进度与 `audit/public.sqlite3`、`audit/internal.sqlite3`；相同目录不能由两个游戏实例同时占用。

从 CLI.2.1.0 起，默认同时打开独立 Terminal 原文查看器，记录全部 SEND / RECV 字节。关闭查看器不关闭游戏，使用 `spdctl trace open --session /absolute/session` 可以重开；`trace view` 在当前终端跟随。`--no-terminal` 仅关闭自动弹窗，记录继续；`--trace-dir` 指定与 profile 不重叠的记录根。默认记录位于 `~/Library/Logs/Shattered Pixel Dungeon CLI/transport/`，不自动删除。完整格式与故障边界见[传输记录与验收](cli-transport.md)。

只有启动前不存在或为空的 profile 才初始化为窗口化、简体中文、跳过游戏内教程及首次前言。已有目录的任何内容都会保留其原设置和迁移流程；不改已有教程存档，不自动读取指引或授予解锁。之后的 GUI 设置修改会保留。

普通 `.app` 入口仍启动普通 GUI；包内 `spdctl` 入口才保持机器 stdin/stdout。仅 stdio 不支持接管另一个 Finder 已启动的实例。

## 一次请求，一次响应

每一行是一个完整 UTF-8 JSON，所有请求（包括首次握手）必须显式携带整数 `protocol_version: 2`。LF、CRLF 和 EOF 前无换行的最后一条输入保留原始字节；非法 UTF-8 返回 `INVALID_ENCODING`，不替换成其他字符后执行。ID 与 target_id 禁止控制字符和不成对的 Unicode 代理项。所有查询和操作必须有调用方生成的 ID，建议 UUID。同一 `scope_id` 下 ID 一经登记永久占用，重复返回 `DUPLICATE_REQUEST_ID`。重新读取/重新决策使用新 ID。

先查询协议，获取菜单及当前作用域：

```json
{"protocol_version":2,"id":"q1","op":"protocol.info"}
```

然后把响应中的当前 `scope_id` 放入每次请求：

```json
{"protocol_version":2,"id":"q2","scope_id":"menu:<返回的UUID>","op":"state.get"}
```

操作还必须原样携带最近一次观察的 `state_version`：

```json
{"protocol_version":2,"id":"a1","scope_id":"menu:<返回的UUID>","op":"action.execute","state_version":"<返回的版本>","args":{"action":"ui.activate","control":"<actions中列出的control>","gesture":"click"}}
```

动作名称、目标和可选输入以当前 `actions` 为准。`move.step` 表示一次普通方向输入，复用键盘路径，可能按原生规则触发相邻攻击、拾取、门或楼梯；不能把它理解为直接改坐标或保证只推进一个回合。

`state.get`/`actions.list` 不推进游戏、识别物品、更新图鉴或打开窗口；实际查看页面是 `ui.activate`/`cell.select` 等操作，会保留原有已读/图鉴行为。每次查询仍会写审计数据库。

查询旧结果必须给新查询 ID：

```json
{"protocol_version":2,"id":"q3","scope_id":"run:<原游戏UUID>","op":"request.get","args":{"target_id":"a1"}}
```

`history.list` 返回分页索引，`events.read` 返回公开事件；不提供通用 SQL、内部状态或诊断导出。每局可以复用另局的 ID，但必须显式指定作用域，不能把旧作用域的请求当成新局操作。

## 持续行动与退出

`STALE_STATE` 表示原动作在游戏回调前被拒绝。教程渐显、攻击指示器延迟停用等有限交互变化应在稳定响应前完成；真实输入和上下文变化仍会使版本失效。服务端不自动替换版本或重放动作。

允许恢复的客户端可以重新观察，在同一有效 scope、可操作阶段中核对目标、提示、资源及广告动作，重新取得 control/locator，再以新 ID 和新版本提交仍然合适的动作，并设置较小的次数上限。仅标签相同不足以证明动作含义不变。响应丢失先查原请求；成功、进行中或 UNKNOWN 均不能按陈旧拒绝重试。任务若要求出错暂停，应保留证据并停止操作。详见[英文手册中的恢复规则](cli-help.md#recovering-from-a-stale-state)。

`in_progress` 不是失败，不得重发原 ID。用新 ID 查询原请求。当前支持原生休息及已经开始移动的连续路径取消；活动版本、目标原请求 ID 和审计顺序见 [持续取消](cli-runtime-cancellation.md)。每条请求只有一条线上响应，最终结果通过主动查询取得。

`app.quit` 走可确认的保存/退出流程；先通过原有取消/返回控件收束窗口。stdin EOF 作为系统生命周期记录处理，不伪造调用方请求或主动输出额外响应。

保存回执详见 [保存与会话记录](cli-save-receipts.md)。操作结果的 `persistence.saves_during_request` 列出本次请求中原生保存流程实际报告的结果；空列表不确认保存。`state.get`/`actions.list` 的 `last_save` 是最近一次保存尝试，也可能失败。回执不代表全部 UI 或任意内存状态可恢复。

数据库事务不涵盖游戏存档文件。进程在执行意图与最终记录之间崩溃时会保留 UNKNOWN；不会重放旧动作补存档。公开历史只含当时真实公开的数据，内部快照、原始异常和工程日志隔离在内部库。

## 验证

```sh
./gradlew :control-protocol:test :game-control:test :desktop-control:test
./gradlew :desktop-control:writeRuntimeClasspath
python3 desktop-control/src/test/python/machine_smoke.py
```

`FixtureLauncher` 只存在于测试 source set，测试矩阵也只使用 `build/fixtures`；发布版没有设置人物属性、解锁、修改存档或读取全知数据的命令。正式游玩策略只使用公开 CLI，夹具结果不能算作通关。
