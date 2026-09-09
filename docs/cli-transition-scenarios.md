# 跨层场景第一组回归

2026-09-09，本组五项隔离实机用例通过，一项尚未完成源状态准备。所有游戏窗口均为中文、窗口化；没有使用截图、键盘、鼠标或 computer use。初始条件由测试源集构造，所有被测转换从触发之前开始，通过公开 CLI 执行。没有直接创建目标场景来替代转换测试，不计入正式通关。

**语言版本说明：** 本页保存的是语言解耦改动之前的证据，当时 GUI 与公开 CLI 的 label/text 都是中文。用户随后要求 GUI 中文、全部 CLI 请求和响应英文；本组结果不作为新语言约定已通过的证明。测试脚本的公开标签匹配需要在新语言层完成后调整并复跑，原证据不回写。

## 已通过的实际用例

| case_id | 触发前条件与原操作 | 通过条件 |
| --- | --- | --- |
| `transition.fall` | 一层已清场，英雄旁有可见深渊。公开 `cell.select` 打开原 `Chasm.heroJump` 选择，先“不，我改主意了”，再重新选择“是的，我知道我在做什么”。 | 取消不改 HP 和位置；确认后同次终态已经在二层，HP 从 1000 变为 505，原 Bleeding 与 Cripple 已附加，Falling 已结算；scope 不变。 |
| `transition.branch_roundtrip` | 原 `Dungeon.newLevel()` 生成十四层 CavesLevel 及矿井入口。初态已有任务与镐子，尚未开始下矿。公开选择脚下入口，先取消再确认进入。 | 原 CavesLevel 确认通过后实际生成 MiningLevel；原退出警告也先取消再确认，返回同一十四层主线，scope 不变。未跳过任务入口或退出检查。 |
| `transition.story_prison` | 五层首领已清场的出口前。公开选择脚下原出口。 | 实际 InterlevelScene 故事与原“继续”按钮出现；点击后到达六层的 `player_ready`，scope 不变。 |
| `transition.story_caves` | 十层首领已清场的出口前。 | 同样实际回答区域故事后到达十一层的稳定游戏状态。 |
| `transition.story_city` | 十五层首领已清场、物理闸门已打开的出口前。 | 同样实际回答区域故事后到达十六层的稳定游戏状态。 |

“显示了过渡动画”不是通过条件。跌落必须等原着陆动作完成，支线必须真实切换层对象并返回，故事必须完成原 Continue 操作并抵达下一稳定楼层。

前四项使用冻结运行时 `runtime-259a313fcfc94bae9008272d6e890784`，城市故事最终通过使用 `runtime-f0b786b15dc94781818dbc14cdaa4e7b`。两者生产 build ID 均为 `ea06531e9e6700bf1aa57023b15f3d51e9f1eb9127562fba41bd9c74a1f63022`；后一运行时只更新了测试源初态。逐项 profile 和结果见 [cli-transition-scenarios-validation.json](cli-transition-scenarios-validation.json)。

## 尚未通过与测试准备失败

- 城市故事首次试跑只设置了 `locked=false`，没有打开 DM-300 场地的物理闸门。原 `CavesBossLevel.invalidHeroPos()` 正确拒绝了闸门上方的初始位置，将英雄放回入口；测试随后实际到了十四层，并未显示城市故事。这是测试准备错误，没有计为 CLI 故障。测试初态现已补齐物理开门，并增加原 `Dungeon.switchLevel()` 不得纠正源位置的断言；新 profile 通过。
- `transition.story_halls` 仍为 **pending**。首轮在准备二十层源场景期间超时；第二轮进程以 143 结束，退出原因未确认。两次均没有抵达被测的二十层出口操作，因此既不能算作 20→21 通过，也不能据此断言生产转换故障。尚未取得可用线程栈，未为让测试通过而改生产代码。
- 护符拾取后留下、原挑战首次启动、低层返程到 SurfaceScene、永久死亡、排名详情和重开属于下一组，当前未新增覆盖结论。护符菜单直接结束游戏的既有 P6 结果不等于地表结局。

## 测试边界与复跑

`TransitionScenarioFixtures` 在旧场景完成原 `destroy()` 后、新源 GameScene 的 `create()` 之前准备状态。它使用原层生成器、原 Level 子类和原 transition 描述；准备后不再设置游戏数据。玩家初始 HP、清场、已接任务、测试深渊和已打开闸门都是明确的测试条件。测试未声称实际打败这些首领或完成任务前置战斗。

游戏操作只使用公开观察中的角色位置、可见深渊和公开按钮。`fixture-assertions.jsonl` 的实际 Level 类、depth/branch 和 buff 类仅用于操作完成后的断言，不提供下一操作目标。没有检查正式存档。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/transition_scenario_smoke.py
```

默认仅执行已通过的五项。待验证的源场景可独立指定 `--cases story-halls`。上述脚本仍匹配当时的中文公开标签；语言解耦完成后需先更新到英文 CLI 约定再复跑。

本组只有测试源和脚本变动，未修改生产行为。`FixtureLauncher` 的 `scenario` 分支、prepare 分派及私有 checkpoint 字段为本组接线；窗口标题及独立 `ui-assertions.jsonl` 为同期其他测试工作，不能把后加的断言伪称已存在于这里保留的冻结运行时。
