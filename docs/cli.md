# spdctl 控制接口

本 CLI 基于原版 **Shattered Pixel Dungeon 3.3.8**（上游基线 `7b8b845a7`、游戏版本码 `896`）扩展。当前 CLI 版本 `CLI.1.0.0`，协议版本 `1`；三个版本标识独立。完整实施/未完成事项及本次构建验证见 [实施记录](cli-implementation.md)。2026-09-12按用户要求直接升级至CLI.1.0.0并重新构建，取代先前CLI.0.9.0计划。旧运行数据已清空；正式实战需要新profile，不能继续已删除的旧战士存档。版本升级不代表已完成全部场景验收或正式战士通关。

语言约定：游戏GUI使用中文；CLI操作名、名称、提示、说明和错误采用英文。`protocol.info.text_language`为`en`。标识、路径、原始请求等原始数据保留原值，旧审计在查询呈现时翻译，底层记录不回写。无法安全翻译的完整文案明确报`PUBLIC_TEXT_UNAVAILABLE`；不补充被裁掉的隐藏内容。玩家自定义文字的进一步处理待确认。基础真实流程与固定旧公开语料已经通过英文复核，全部场景覆盖仍在推进。

状态中的`observation.ui.display`给出实际GUI选定语言代码与窗口模式，例如`{"language":"zh","fullscreen":false}`。它描述当前界面，CLI文案语言仍为英文。

## 启动

源码开发入口（自动构建并冻结当前依赖，避免后续构建更换运行中的 JAR）：

```sh
./bin/spdctl run --machine --data-dir /absolute/path/to/isolated-profile
```

原生 ARM64 应用：

```sh
./gradlew :desktop-control:packageMacArm64
"desktop-control/build/app-macos-arm64/Shattered Pixel Dungeon.app/Contents/MacOS/spdctl" run --machine --data-dir /absolute/path/to/isolated-profile
```

CLI 默认 profile 为 `~/Library/Application Support/Shattered Pixel Dungeon CLI/`，与普通 GUI 默认目录分开。指定的目录同时保存游戏进度与 `audit/public.sqlite3`、`audit/internal.sqlite3`；相同目录不能由两个游戏实例同时占用。

普通 `.app` 入口仍启动普通 GUI；包内 `spdctl` 入口才保持机器 stdin/stdout。仅 stdio 不支持接管另一个 Finder 已启动的实例。

## 一次请求，一次响应

每一行是一个完整 UTF-8 JSON。LF、CRLF 和 EOF 前无换行的最后一条输入保留原始字节；非法 UTF-8 返回 `INVALID_ENCODING`，不替换成其他字符后执行。ID 与 target_id 禁止控制字符和不成对的 Unicode 代理项。所有查询和操作必须有调用方生成的 ID，建议 UUID。同一 `scope_id` 下 ID 一经登记永久占用，重复返回 `DUPLICATE_REQUEST_ID`。重新读取/重新决策使用新 ID。

先查询协议，获取菜单及当前作用域：

```json
{"id":"q1","op":"protocol.info"}
```

然后把响应中的当前 `scope_id` 放入每次请求：

```json
{"id":"q2","scope_id":"menu:<返回的UUID>","op":"state.get"}
```

操作还必须原样携带最近一次观察的 `state_version`：

```json
{"id":"a1","scope_id":"menu:<返回的UUID>","op":"action.execute","state_version":"<返回的版本>","args":{"action":"ui.activate","control":"<actions中列出的control>","gesture":"click"}}
```

动作名称、目标和可选输入以当前 `actions` 为准。`move.step` 表示一次普通方向输入，复用键盘路径，可能按原生规则触发相邻攻击、拾取、门或楼梯；不能把它理解为直接改坐标或保证只推进一个回合。

`state.get`/`actions.list` 不推进游戏、识别物品、更新图鉴或打开窗口；实际查看页面是 `ui.activate`/`cell.select` 等操作，会保留原有已读/图鉴行为。每次查询仍会写审计数据库。

查询旧结果必须给新查询 ID：

```json
{"id":"q3","scope_id":"run:<原游戏UUID>","op":"request.get","args":{"target_id":"a1"}}
```

`history.list` 返回分页索引，`events.read` 返回公开事件；不提供通用 SQL、内部状态或诊断导出。每局可以复用另局的 ID，但必须显式指定作用域，不能把旧作用域的请求当成新局操作。

## 持续行动与退出

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
