# 战士新档实战暂停：教程结束后下一次移动返回 STALE_STATE

> 2026-09-13 后续：已完成源码修复、定向单元测试和两种界面的真实生产入口回归，见[修复与验证](2026-09-13-fixes-and-validation.md)。下文保留发现问题时的原始记录。

> CLI.2.1.1 后续：原教程修复保留，并在两种界面的全新测试 profile 中再次验证通过；与本次击杀后状态失效的统一分析见[状态边界修复](2026-09-13-cli-2.1.1-stale-state-fixes.md)。

记录日期：2026-09-12（Asia/Shanghai）
状态：已暂停，未通关；仅记录和只读定位，未修改源码、重建、重试移动或重新开局。
分类：已观察到的可恢复版本拒绝；教程完成边界是否需要修复，仍待隔离复现和设计评估。

## 本次任务与边界

用户要求在已打开的本地游戏中使用战士新存档通关，所有游戏操作仅使用 spdctl；允许读取源码，禁止越过 spdctl 直接读取游戏存档；遇到 spdctl 无法操作或操作错误时暂停并记录。

本次连接沿用此前通过仓库 bin/spdctl run --machine 启动的进程。全部游戏观察和输入使用该进程的 stdin/stdout 公开协议。没有使用鼠标、键盘模拟、截图、调试器、反射入口、夹具或直接文件读取获取游戏进度。没有读取游戏 profile 下的存档、设置、审计库或内部诊断文件。本次任务开始前的窗口化启动及设置调整属于上一个用户请求。

## 运行身份

- 启动入口：/Users/shengyifan/Workspace/shattered-pixel-dungeon/bin/spdctl run --machine
- 分支：feature/mac-cli
- 检查时 HEAD：da53ba6c054d084ba138af8f19be7b05e921593b
- 任务开始时工作区：git status --short 无输出。
- CLI：CLI.1.0.0；协议：1；基础游戏：3.3.8。
- build_id：04aa7e744fdb197b7623e0b74ed028a29ffbbf1ce1cac83a3f6f0a5a3bcbc742
- CLI session_id：3bab6e9c-af59-4669-934c-d4fd56aa0b72
- run scope：run:de76a9d4-d755-4d0a-9092-2f5ebc9147c4
- 默认 profile：/Users/shengyifan/Library/Application Support/Shattered Pixel Dungeon CLI
- 本任务的执行工具连接编号：22490。它仅用于继续现有 stdio 连接，不是 spdctl 参数。
- 实际 GUI 公开状态：language=zh，fullscreen=false。
- 新档职业：warrior；slot=1（来自公开保存回执）。

## 实际经过

1. 从欢迎界面的 Enter the Dungeon 进入角色选择，选 warrior，按 Start，在下楼说明页按 Continue。
2. 出现在第 1 层入口 cell=932，等级 1，HP 20/20。
3. 分别发送 north、northwest、northwest、north、north，每次等待完整公开响应再决定下一步。位置依次为 891、849、807、766、725。
4. 在 cell=725 取得 Tome of Dungeon Mastery。
5. 激活 Journal，阅读引言窗口，再用 ui.back 关闭。
6. ui.back 请求 warrior-20260912-12 成功返回 completed、phase=player_ready、版本 becce27e-7cbf-4588-be60-cba0a0807b39:13，并广告了 move.step。
7. 使用该最新返回版本发送 move.step west（新请求 ID warrior-20260912-13），返回 STALE_STATE。
8. 停止所有 action.execute。之后仅发送 state.get 与 request.get 来核实；没有重发失败请求，也没有使用新 ID 重试移动。

失败记录的 created_at 为 2026-09-12T15:26:51.929711Z，即北京时间 2026-09-12 23:26:51.929711。

## 失败请求与结果

```json
{
  "id": "warrior-20260912-13",
  "op": "action.execute",
  "scope_id": "run:de76a9d4-d755-4d0a-9092-2f5ebc9147c4",
  "state_version": "becce27e-7cbf-4588-be60-cba0a0807b39:13",
  "args": {
    "action": "move.step",
    "direction": "west"
  }
}
```

```json
{
  "id": "warrior-20260912-13",
  "scope_id": "run:de76a9d4-d755-4d0a-9092-2f5ebc9147c4",
  "ok": false,
  "error": {
    "code": "STALE_STATE"
  }
}
```

通过 request.get 查询该 ID，记录状态为 REJECTED，返回的 before_snapshot 和 after_snapshot 完全相同。结合状态 readback，确认没有发生这次向西移动。

## 公开证据对比

对比 ui.back 的成功响应与失败后的第一个 state.get：

- state_version 从 :13 变为 :14。
- phase 仍为 player_ready。
- observation 顶层只有 ui 不同；hero、inventory、map、visible_entities、visual_cues 和 coverage 均相同。
- ui.controls 从 12 项变为 93 项，增加 81 项，移除 0 项。
- 新出现的控件包括快捷栏、Wait、Examine、Inventory、装备/背包物品和背包标签。
- 角色仍为 cell=725（地图宽 41，零起点 x=28、y=17），第 1 层，等级 1，HP 20/20，力量 10，经验 0/10，金币 0。
- 后续第二次 state.get 仍返回 :14、player_ready 和同一位置/生命，没有继续版本变化。

完整请求/响应记录在同目录的 `2026-09-12-warrior-tutorial-stale-state-public-evidence.json`，仅本地保留，不随 Git 提交。其中包含 request.get 通过公开接口返回的历史快照；这些不是直接读取存档或数据库得到的数据。证据中的 received_at 是控制客户端收到并整理完整响应的时刻，游戏侧请求时间以 request.get 记录为准。

## 源码定位与判断

以下是静态源码支持的最可能解释，尚未在独立测试实例中复现，因此不把它表述为已完全验证的根因。

- core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java:1303：endIntro() 创建一个 2 秒 Tweener。前半段显示英雄状态栏、隐藏 toolbar/inventory；后半段才显示并激活 toolbar/inventory。该变化与本次新增控件吻合。
- SPD-classes/src/main/java/com/watabou/noosa/tweeners/Tweener.java:27：Tweener 没有覆写 hasPendingCallback()。
- SPD-classes/src/main/java/com/watabou/noosa/Gizmo.java:50：默认 hasPendingCallback() 返回 false。
- game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameController.java:251：stable() 检查 pending callbacks/effects、Actor 交接、移动和 hero ready 等条件。
- 同文件 :458：hasPendingEffects() 递归询问节点的 hasPendingCallback()；本次教程 Tweener 的交互控件变化没有通过该默认接口声明为待完成工作。
- 同文件 :428：renderedBoundaryReady() 等待后续绘制，但没有直接等待教程 2 秒控件渐显全部完成。
- 同文件 :499：版本签名包含 ui.intentSignature()。UiBridge.java:125 保留普通按钮及其可用状态，所以新增工具栏/背包控件足以使版本失效。
- desktop-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/MachineSession.java:148、:198：执行前重新观察，再检查版本；过期版本会被拒绝。
- docs/cli-help.md 第 8、9 节本来就规定 STALE_STATE 应重新观察并重新决策。本次遵守用户的出错暂停要求，仅刷新观察，没有恢复执行。

这里已经确认的是版本拒绝及其 UI 差异。没有证据表明向西移动回调执行错误、英雄被移错位置或游戏数据损坏；也没有用新版本试验该移动，因此不能声称 spdctl 持续无法移动。后续需要判断这是客户端应处理的正常异步失效，还是应完善“动作完成后返回稳定边界”的教程处理。

## 后续修复/回归建议（本次未执行）

1. 在独立、全新且保留首次教程的 profile 中重现取书、Journal、ui.back 流程，记录立即及跨过教程渐显中点后的状态。
2. 检查教程这种有限、会改变交互控件集合的动画是否应纳入稳定边界，或由客户端在继续操作前进行适当的状态刷新。
3. 如调整稳定边界，应保证教程原有渐显和交互规则保持原样；不要全局忽略按钮出现/可用性变化，也不要关闭 STALE_STATE 校验。
4. 回归需要确认真实上下文变化仍拒绝旧版本，普通装饰性浮字仍不会造成不必要失效。
5. 恢复本局时先通过现有 stdio 连接重新查询 protocol.info/state.get，使用全新请求 ID 和新返回版本；不得重放已成功的动作或读取存档来补齐状态。

## 暂停与保存边界

游戏窗口和 spdctl 连接保持运行，停在 player_ready 的回合边界，没有打开暂停菜单、退出进程或发送新的游戏动作。此处“暂停”指停止推进回合，保留现场。

公开 last_save 的最近成功回执是新建存档时的：
- receipt_id：8caa2860-d3c3-4f89-9d8b-08fbf6b0b0e9
- occurred_at：2026-09-12T15:25:01.730862Z
- origin_request_id：warrior-20260912-4

它仅确认新档创建时保存成功，不能证明后续取得手册和当前站位已再次保存。当前进度由尚未退出的会话保留。未为确认持久化而直接读取任何游戏文件。
