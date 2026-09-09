# 已显示日志与视觉提示

CLI 的公开观察只记录已有控件和效果实际呈现的内容。`events.read` 新增 `game.log` 与 `game.visual` 两类显示快照；它们通过原公开事件分页读取，不主动向 stdout 推送。

## 游戏日志

`game.log` 的 `data` 结构为：

```json
{
  "format": "display_snapshot_v1",
  "occurred_at": "2026-09-09T00:00:00Z",
  "entries": [
    {"text": "当前可见文字", "color": 16777215, "clipped": false}
  ]
}
```

一条事件代表整个 GameLog 控件的一份显示快照。它不是“新增的一条独立消息”：原控件会合并同色文字、换行和裁掉旧条目，新快照可能包含之前已经出现的文字。事件顺序与所属 run 由外层 `sequence` 和 `scope_id` 给出，`occurred_at` 是绘制回调发生的 UTC 时间。

通知发生在原 `GameLog.draw()` 完成之后。没有观察器时，不进行每帧片段或 DTO 分配。只读取当前场景中可见、活动的控件；构造控件时的 run 身份必须仍与当前 run 一致。输入队列、原合并/裁剪算法和布局均不由查询或事件采集修改。

日志片段还受到已布局字词的 viewport 边界限制。完全不可见的条目不输出，也不占用公开 control ID；部分落在边缘、无法确认完整显示的词会省略，条目标记 `clipped=true`。不可见的顶部全文、条目数量和颜色不能通过日志事件或 GameLog 子树的公开形状泄漏。UiBridge 对 GameLog 文本使用同一投影；可滚动详情窗口继续保留原本合法查看的完整文字。

同一 run 的相同文字、颜色及裁剪标记不会反复产生事件，因此只重建相同 UI 不重复记录；新 run 的相同文字会独立记录。已经显示、后来滚出控件的旧快照仍可从公开事件历史读取。控制台、DeviceCompat 日志、原始 `GLog.update` 信号以及尚未实际绘制的中间文字不会直接成为公开游戏日志。

## 视觉提示

当前第一组视觉样式为 `red_target` 和 `black_goo_droplets`。来源是现存红色 TargetedCell 和真正绘制出的 Goo 粒子，不读取 AI 目标、蓄力计数或其它隐藏预测字段。

游戏状态中的 `observation.visual_cues` 示例：

```json
{
  "status": "last_rendered",
  "depth": 1,
  "map_context": "opaque-map-context",
  "cues": [{"kind": "red_target", "cell": 123}]
}
```

`map_context` 是控制层分配的、不含底层对象身份的标识。同一已加载地图仅重建 UI 时保持不变，加载其它地图或 run 时改变。没有匹配当前地图的完整绘制结果时，状态明确为 `not_rendered`；旧地图提示不会附到新地图上。

`game.visual` 变化事件使用相同的 `depth`、`map_context` 和 `cues`，另带 `format=display_snapshot_v1` 与回调 UTC `occurred_at`。提示消失时会记录空快照，消失前的提示仍在历史中。**空数组只说明最近这一帧没有相应的可见提示，不表示危险已经解除。** 红标会自然淡出，粒子也会闪烁。

## 完成边界与状态版本

引擎顺序是先绘制，再 update/step，然后通知 afterFrame。因此 GameScene 动作首次达到稳定状态后，协调器还要等一个后续完整绘制，并再次确认世界稳定。这样不会把本次动作之后尚未绘制的警告遗漏在“已完成”响应之外。

某些警告的首个粒子比一帧更晚出现。对通过视野、完整地图格 viewport、HUD 和实际绘制来源检查的合格 emitter，还会等待其首次真正可见的呈现；隐藏或不可绘制的来源不能触发等待。这个内部就绪标记不公开，后续自然闪烁不会反复等待。协调器只等待原渲染循环，不强制生成粒子、刷新效果或推进 Actor。

视觉提示与 GameLog 非交互文字不参与意图签名，避免淡出、闪烁或文字裁剪独自引起 `STALE_STATE`。真实 CLI 操作仍推进版本，真实 GUI 输入、交互窗口、按钮、selector 和公开世界状态继续使用原有保护；观测与事件中的显示证据完整保留。

## 构建身份与验证

`protocol.info.result` 同时提供公开 `build_id`、`session_id`（无会话时为 `null`）以及 `audit_schema_version`。调用方可以区分 CLI 版本字符串相同的不同构建，不需要依赖外部告知的冻结路径。

日志真实回归见 [cli-game-log-validation.json](cli-game-log-validation.json)。它使用中文、窗口化的隔离 fixture，在真正渲染路径上验证批量裁剪、超长单条日志的不可见顶部、滚动历史、同局 UI 重建、新局相同文字和控制台隔离。所有菜单及游戏操作使用公开 NDJSON；已知测试文字由独立 test-only agent 注入，不计作通关证据。

```sh
./gradlew :desktop-control:crashAgentClasses :desktop-control:writeTestRuntimeClasspath --console=plain
python3 desktop-control/src/test/python/game_log_smoke.py
```

首次使用测试 agent 的工作区还需先运行 `:desktop-control:crashAgentJar`，供脚本读取其固定版本 ASM 依赖。
