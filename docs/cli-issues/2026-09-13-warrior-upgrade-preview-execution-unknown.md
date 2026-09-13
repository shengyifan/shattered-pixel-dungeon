# 阻塞问题：选择布甲进入升级预览时返回 EXECUTION_UNKNOWN

> 2026-09-13 后续：在独立测试实例中复现并定位到升级预览的“防御”英文歧义，已修复并通过四组合真实引擎回归，见[修复与验证](2026-09-13-fixes-and-validation.md)。原故障进程未恢复，下文保留当时的证据与不确定性。

记录日期：2026-09-13（Asia/Shanghai）。
状态：正式战士实战已暂停，未通关；机器会话持续返回 execution_unknown，无法继续正常游戏操作。
本次只记录公开证据和静态源码线索，没有修复、重建、重启、重试升级或继续开局。

## 任务边界和运行身份

用户要求重新用战士新存档通关已打开的本地源码游戏，所有游戏观察及操作仅使用 spdctl，允许读取源码，禁止越过 spdctl 直接读取游戏存档；遇到 CLI 无法操作或操作错误时暂停并记录。

- 实际启动入口：/Users/shengyifan/Workspace/shattered-pixel-dungeon/bin/spdctl run --machine。
- 本次沿用现有进程及其 stdio 连接，没有改用普通 GUI 或额外控制入口。
- CLI 版本 CLI.1.0.0；游戏版本 3.3.8；协议版本 1；审计 schema 4。
- build_id：04aa7e744fdb197b7623e0b74ed028a29ffbbf1ce1cac83a3f6f0a5a3bcbc742。
- 分支 feature/mac-cli；HEAD da53ba6c054d084ba138af8f19be7b05e921593b；开始和诊断时仅 docs/cli-issues/ 为已有未跟踪记录目录。
- CLI session_id：3bab6e9c-af59-4669-934c-d4fd56aa0b72。
- 本次新档 scope：run:66ef0bcb-d6e8-48c8-83a3-a442d454de2c；slot=1。
- 默认 profile：/Users/shengyifan/Library/Application Support/Shattered Pixel Dungeon CLI。
- 当前执行工具连接编号：22490，仅用于继续现有 stdio，不是 spdctl 参数。
- 最后成功观察到的 GUI：中文 language=zh、窗口化 fullscreen=false。

任务启动时，旧握手提供的 run scope 已不对应当前菜单，第一条 state.get 返回 SCOPE_MISMATCH；随后只读 protocol.info/state.get 同步到了 StartScene 的 New Game。这发生在本次任何游戏动作之前，没有重试旧游戏动作。本次新档正常显示完整工具栏，未再次触发上一份记录中的教程 STALE_STATE。

## 实战进展与最后可确认的状态

通过 New Game → warrior → Start → Continue 建立本次新档。之后正常完成了逐步移动、近战、投石、原生连续行走及完成查询、拾取、宝箱、开锁、查看物品、法杖施放和快捷栏设置。

解离法杖通过实际施放确认无诅咒；一次充能不足返回正常游戏提示，没有协议错误。直觉符石的候选图标没有直接名称，但预选后出现具名确认按钮；通过公开预选和确认成功识别 YNGVI 为升级卷轴，没有读取未知物品的内部类型。

出错前最后一条成功 state.get（warrior-retry-20260912-315）确认：

- 第 1 层、warrior、等级 1、经验 8/10。
- HP 20/20、shield 0、strength 10、gold 0，没有公开列出的 buff。
- cell=483；地图宽 34，零起点坐标 x=7、y=14，位于已打开的符石房。
- equipment.armor 是已装备、已鉴定、无诅咒的 cloth armor +0；先前正常物品说明确认带有破损纹章。
- 背包有已鉴定 scroll of upgrade，数量 1，locator=backpack.5。
- 当前阶段 awaiting_input，item_prompt 为 Upgrade an item。
- ui-215 是 enabled=true 的 Cloth Armor 控件，公开 actions 明确允许 click。

上述均为出错前或 last_stable_state 的观察。出错后的 API 没有提供新的实时观察，不能把这些字段当作已确认的出错后状态。

## 最短观察到的触发序列

| 请求 ID 后缀 | 操作 | 返回结果 |
| --- | --- | --- |
| 310 | 直觉符石确认 YNGVI 猜测为 scroll of upgrade | completed；背包显示已鉴定升级卷轴 |
| 312 | inventory.open，locator=backpack.5 | awaiting_input；卷轴菜单含 READ |
| 314 | ui.activate READ，control=ui-414 | awaiting_input；Upgrade an item，广告 Cloth Armor |
| 315 | state.get | 当前版本 :176；ui-215 仍可点击 |
| 316 | ui.activate Cloth Armor，control=ui-215，gesture=click | EXECUTION_UNKNOWN |
| 317 | state.get | execution_unknown，state_version=null，actions=[]，snapshot_status=last_stable |
| 318 | request.get，target_id=warrior-retry-20260912-316 | 原请求 status=UNKNOWN，after_snapshot=null |
| 321 | 再次 state.get | 仍为 execution_unknown，且没有可执行动作 |

所有后缀使用完整前缀 warrior-retry-20260912-。本控制客户端每个游戏动作前均刷新 state.get，使用新请求 ID，并校验当前广告的动作/控件。失败请求使用的 :176 与紧邻的成功观察一致，不能按普通旧版本重发来处理。

失败请求接收时间：2026-09-12T16:05:23.854456Z，即北京时间 2026-09-13 00:05:23.854456。
失败记录更新时间：2026-09-12T16:05:24.116153Z。

## 原始失败请求与响应

```json
{
  "id": "warrior-retry-20260912-316",
  "op": "action.execute",
  "scope_id": "run:66ef0bcb-d6e8-48c8-83a3-a442d454de2c",
  "state_version": "becce27e-7cbf-4588-be60-cba0a0807b39:176",
  "args": {
    "action": "ui.activate",
    "control": "ui-215",
    "gesture": "click"
  }
}
```

```json
{
  "id": "warrior-retry-20260912-316",
  "scope_id": "run:66ef0bcb-d6e8-48c8-83a3-a442d454de2c",
  "ok": false,
  "error": {
    "code": "EXECUTION_UNKNOWN"
  },
  "result": {
    "persistence": {
      "last_save": {
        "receipt_id": "bf35736c-266f-4912-93ce-bf2dcae8ac82",
        "scope_id": "run:66ef0bcb-d6e8-48c8-83a3-a442d454de2c",
        "slot": 1,
        "success": true,
        "occurred_at": "2026-09-12T15:52:35.441447Z",
        "origin_scope_id": "run:66ef0bcb-d6e8-48c8-83a3-a442d454de2c",
        "origin_request_id": "warrior-retry-20260912-204"
      },
      "saves_during_request": []
    }
  }
}
```

## 预期与实际

预期：选择可升级的布甲后，打开原生升级预览/确认窗口，通过公开状态展示升级前后的属性及 Upgrade/Back 等操作。用户或控制器确认 Upgrade 后才应用升级。

实际：点击被广告为可用的 Cloth Armor 后立即得到 EXECUTION_UNKNOWN。随后两次只读 state.get 都不提供新的 state_version 或动作，而只返回旧的物品选择状态；request.get 的原请求为 UNKNOWN，after_snapshot=null。

本客户端没有发送 WndUpgrade 的最终 Upgrade 确认动作。因此不能声称布甲已经升到 +1，也不能声称卷轴已经消耗或仍然完整。错误响应中的 saves_during_request=[] 也没有确认本次请求保存过结果。

本次记录包含 321 条连续编号的控制客户端协议请求的简要时间线（不含本轮最初单独发送的 protocol.info 握手），154 条 action.execute，其中这条是唯一返回协议错误的游戏动作；其后没有任何 action.execute。

## 静态源码线索

这些线索定位触发路径和错误隔离行为；公开协议没有暴露原始异常，根因尚未确定。

1. core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/InventoryPane.java:531-535
   物品选择时先取出并清空 selector，再调用 activating.onSelect(item)，然后更新背包。失败可能已跨过一部分 UI 回调，不能假设原选择状态仍可重放。

2. core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/InventoryScroll.java:39-49、:115-134
   对已鉴定的升级卷轴，READ 打开物品选择；选择目标时保留升级卷轴的独立确认流程。本次卷轴已经由直觉符石鉴定，应重点覆盖 identifiedByUse=false 的分支。

3. core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfUpgrade.java:62-64
   onItemSelected 通过 GameScene.show(new WndUpgrade(this, item, identifiedByUse)) 打开预览。

4. core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndUpgrade.java:75、:231-260、:437-455
   构造器展示当前和升级后护甲属性；真正的 readAnimation、upgradeItem 和消耗卷轴位于 btnUpgrade.onClick。当前失败请求是选择护甲，而不是这个确认按钮。

5. game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameController.java:336-364、:466-505
   操作回调后还需要完成状态捕获及 UI 描述；需要区分原生回调/窗口构造异常与后续观察、投影或快照异常。

6. game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PublicEnglishProjection.java:142-143
   已有 upgrade_preview 上下文识别规则。后续排查可检查真实的带纹章布甲预览是否满足它以及相关数值/文本投影；本记录没有证据证明翻译层就是根因。

7. desktop-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/desktop/MachineSession.java:136-148、:244-263、:274-282
   派发后遇到无法证明未执行的异常会置 executionUncertain、记录 UNKNOWN 并返回 EXECUTION_UNKNOWN；之后阻止新操作、跳过新的正常观察，返回 state_version=null、actions=[] 和 last_stable_state。这与两次只读 readback 一致。

通过 events.read 取得的本局公开事件序号 16-42 包括拾取、战斗、保存和直觉符石鉴定成功日志，没有升级成功事件或可用的原始异常栈。内部异常、数据库、存档和进程内存均未读取。

## 后续修复与验证建议（本次未执行）

- 用独立测试 profile 构造或正常建立：中文窗口 GUI、战士、带破损纹章的已鉴定 cloth armor +0、已通过直觉符石鉴定的单张升级卷轴，走原有 READ → 装备栏选择布甲路径。
- 覆盖已鉴定/使用时鉴定两种卷轴路径，以及固定背包面板/弹出背包窗口；不要只测试最终 Upgrade 按钮。
- 预览动作应返回可观察的 awaiting_input 和新版本，含可用的确认/返回操作；该阶段不应提前修改装备或消耗已经鉴定的卷轴。
- 之后分别验证确认一次只升级一次、消耗一次，返回/取消按原版规则处理，并核对真实保存回执。
- 保留实际部分执行异常的 UNKNOWN 隔离语义；不要简单吞异常、清除 executionUncertain 或重放旧请求来掩盖问题。
- 需要进一步证据才能确定实际异常层。当前会话不能通过公开接口重新取得实时状态。

## 暂停及保存边界

最后一次明确成功的 game.save 是 warrior-retry-20260912-204：
- receipt_id：bf35736c-266f-4912-93ce-bf2dcae8ac82。
- occurred_at：2026-09-12T15:52:35.441447Z，即北京时间 2026-09-12 23:52:35。
- scope：本次 run:66ef0bcb-d6e8-48c8-83a3-a442d454de2c，slot=1。
- 当时已拾取解离法杖和两种未鉴定卷轴，角色位于 cell=431；之后的西侧探索、符石拾取与鉴定没有新的成功保存回执。

当前游戏进程和 stdio 连接保持运行。出错后仅调用 state.get、request.get 和 events.read，没有 ui.back、game.save、app.quit、重新启动、重放动作或 OS 游戏输入。这里的暂停是停止推进并保留故障现场；当前 CLI 没有可用的保存/退出动作，不声称故障现场已持久化。

## 附件

- 原本地附件 `2026-09-13-warrior-upgrade-preview-execution-unknown-public-evidence.json` 曾记录关键公开请求、读回、事件及完整简要时间线；2026-09-13 本次 CLI.3.0.0 清理重建已按用户要求删除该附件。
- [上一轮教程状态版本问题](2026-09-12-warrior-tutorial-stale-state.md)

原 JSON 附件的游戏数据全部来自 spdctl 公开响应。当时的时间线记录了本轮连续编号请求的 ID、动作、版本和摘要，关键步骤记录了完整响应；没有复制原始 Codex 会话或内部游戏数据。本文继续保留问题分析摘要。
