# P6 低频交互的真实 CLI 验证

> 文档整理说明：配套的历史验收 JSON 已按用户要求删除；原始运行数据也已清空。本页保留当时的验证说明，旧结构化结果可从 Git 历史查阅，不能作为 CLI.0.9.0 的新验收结果。

这一组专项使用 test-only `LowFrequencyFixtures` 准备独立场景，通过正常主菜单选战士进入游戏后，在首次安全边界布置物品、NPC、任务进度和资源。后续操作全部使用公开 NDJSON，复用生产 MachineSession、GameController、UiBridge 和原生窗口。没有截图、键鼠模拟或 Computer Use；没有新增生产命令或修改游戏规则。

最终采用的 **22 个具名场景全部通过**，覆盖商店交易/偷窃、Ghost 与 Wandmaker 奖励、铁匠服务、同伴装备与指挥、炼金能量与合成、死亡复活，以及护符选择和胜利窗口。逐例冻结运行时和结果见 cli-p6-low-frequency.json（历史 JSON 已删除，可查 Git 历史）。所有 profile、结果均标为 `test_fixture=true`、`counts_as_win=false`。

最终证据采用四次冻结运行时中的最新对应用例：`runtime-70a25f85898840c2b81ac4c308322fba` 的 8 项、准备状态校正后的 `runtime-2a04ee19328c475d9c1cb0e0f036e2ce` 的 4 项、新变体 `runtime-e81f17748d24403a9d2265cdd8a08c3b` 的 7 项，以及 `runtime-c6207353f0f042969904af6b52783270` 的 3 项。每项对应的实际运行时都保留在结果中；没有把多次运行称作同一冻结批次。

**护符结束场景会调用真实游戏的胜利流程并产生 fixture 内的 `run.ended: won`。这只是人为准备护符后的窗口测试，不是正常通关，不能计入 P9。**

## 每项入口、前提与断言

| 场景 | 准备的前置条件 | 公开 CLI 路线 | 实际验收 |
|---|---|---|---|
| shop-trade | 邻接真实 Shopkeeper、待售治疗药水、1000 金币。 | 公开地上物品 `cell.select` → WndTradeItem；NPC `cell.select` → Sell an item → 原选物窗口。 | 购买窗口取消无损；确认花 150 金币得到药水；卖出取消无损；确认得到 30 金币并移除该药水。 |
| shop-steal | 装备未诅咒盗贼袖章，使用等级 0 的合法 5 充能上限。 | 相同待售窗口 → 公开显示的 100% Steal。 | 真正偷到物品，金币不变，充能从 5 降到 2；此前关闭交易窗口不消耗资源。 |
| shop-steal-warning | 同样袖章但仅 1 充能。 | Steal → 原低成功率警告 → No, I changed my mind。 | 确认窗口真实出现，取消不偷取、不扣金币或充能。没有重复尝试随机失败来筛选成功。 |
| ghost-reward | 已击败任务目标、尚未领取奖励的真实 Ghost，待选武器/护甲。 | NPC interact → WndSadGhost → RewardWindow。 | 预览武器后取消仍未领取；再次确认得到奖励，并在同响应检查点完成任务。 |
| wandmaker-reward | 已接受灰烬任务，背包持有 Embers，Wandmaker 有两个奖励法杖。 | NPC interact → WndWandmaker → RewardWindow。 | 预览取消不消耗灰烬；确认得到法杖、移除 Embers 并清空待领奖励。 |
| blacksmith-cashout | 40 暗金、任务 Boss 已击败，经原 Quest.complete 得到 3000 favor。 | NPC interact → Cash Out → 原确认窗口。 | Nevermind 不改变 favor；确认得到 3000 金币，favor 归零。 |
| blacksmith-pickaxe | 40 暗金、未击败任务 Boss，经原 Quest.complete 得到 2000 favor。 | NPC interact → Pickaxe → 原确认窗口。 | 取消无损；确认得到镐，favor 从 2000 降至 1750。 |
| blacksmith-reforge | 正常任务结算后 3000 favor，两把已鉴定同类剑。 | Reforge → 两个原 ItemButton → 两次现有背包选物 → Reforge。 | 选物取消不消耗资源；两把剑合并为一把，保留较高等级再加一级，扣 500 favor。 |
| companion | 已完成 Ghost 任务，装备满充能 DriedRose，背包有同伴力量允许的剑与皮甲。 | OUTFIT → 选武器/护甲；SUMMON；DIRECT → cell selector。 | 装备选择可取消；两件装备确实转交；召唤消耗全部充能；指挥取消、驻守公开格子、跟随英雄均执行；最终通过原装备槽返还两件装备。 |
| alchemy-energy | 邻接原炼金台，两瓶已鉴定治疗药水，20 能量。 | 炼金台 → Energize Items → 选药水 → Turn 1；Add → 选另一瓶 → Craft。 | 能量窗口取消无损；一瓶转换为 6 能量；另一瓶花 4 能量合成为护盾药水，退回游戏后 scope 不变。 |
| resurrect | 1 HP、原生 Poison、未祝福 Ankh 和鉴定卷轴。 | 公开 wait 触发原中毒死亡 → WndResurrect → 选保留物品。 | Back 不能跳过强制窗口；选物可以取消；两槽选同物品导致缺项警告，取消警告后补选；保留指定物品、消耗 Ankh，恢复 HP 并保持同一 scope。 |
| amulet-stay | 持有真实 Amulet，记录为已取得，尚未开始返程挑战。 | 物品 END THE GAME → AmuletScene → I'm not done yet。 | 读取两种真实选项，选择留下后返回同一局，护符仍在背包。 |
| amulet-end | 相同人为准备的护符状态。 | END THE GAME → Let's call it a day → RankingsScene → WndVictoryCongrats。 | 记录测试局 won 事件；实际显示排名及胜利提示；Back 不跳过提示，原 Close 按钮能关闭。 |
| shop-stack | 真实 Shopkeeper，背包三瓶已鉴定治疗药水。 | Sell an item → Sell 1 / Sell all → NPC 原回购列表。 | 卖一瓶得到 30 金币，回购恢复；卖三瓶得到 90 金币，回购再恢复。每次核对数量和金币完整往返。 |
| shop-steal-failure | 1 充能袖章和高价值已鉴定 +3 板甲，原窗口显示 2% 成功率。 | Steal → 原风险确认 → Yes, I'm sure。 | 仅一次实际尝试，无 RNG 改动或重试筛选。本次失败，Shopkeeper 按原规则逃离；未取得物品，金币和充能按原失败分支保持不变。 |
| blacksmith-harden | 原任务结算 3000 favor，有可硬化的已鉴定剑。 | Harden → 原选物窗口。 | 取消无损；确认后真实 enchantHardened 标记增加，扣 500 favor。 |
| blacksmith-upgrade | 原任务结算 3000 favor，剑等级低于原服务上限。 | Upgrade → 原选物窗口。 | 取消无损；确认后物品等级加一，扣 1000 favor。 |
| blacksmith-smith | 原任务结算 3000 favor，尚未支付新锻造。 | Smith → 原购买确认 → WndSmith → RewardWindow。 | 购买取消不扣费；确认扣 2000，原代码生成奖励；Back 不能绕过待领奖励窗口，预览可取消；最终领取一件生成物品并清空奖励列表。 |
| companion-attack | 玫瑰、合法可装备武器，以及公开可见且被麻痹的真实 Rat。 | OUTFIT / SUMMON / DIRECT → 公开敌人格子 → 固定 8 次 wait。 | 指挥响应同版检查点立即绑定敌人；随后固定公开回合让原同伴 AI 接近并攻击，最后一个 wait 的同版检查点验证实际伤害。内部 HP 不决定是否继续等待或选择哪个目标。 |
| companion-resummon | 已有过召唤、当前无同伴、玫瑰 99 充能且正常部分充能接近满值。 | 原 wait 完成充能 → 实际 SUMMON。 | 充能自然达到 100，执行第二次召唤的原分支，生成同伴并消耗全部充能。该场景从冷却尾段开始，没有冒充整个前次同伴死亡及 500 回合充能过程。 |
| blessed-ankh | 1 HP、原生 Poison、已祝福 Ankh 和其他库存。 | 公开 wait 触发原中毒死亡。 | 原生自动恢复 HP，无保留物品窗口；Ankh 消耗、使用计数增加、其余库存保留、scope 不变。 |
| amulet-pickup | 未记录获得护符，邻接地面真实 Amulet。 | 公开地上物品 cell.select → 原 doPickUp 的延时 Actor 回调 → AmuletScene。 | 首次获得的故事文本和选择界面真实出现；选择留下返回原局，护符进入库存。 |

铁匠夹具使用原任务结算方法产生 favor 和免费返还镐的标记。正常游戏在任务结算达到 2500 favor 时会免费返还镐，因此收费兑换场景必须使用未击败 Boss、2000 favor 的前提。早期“3000 favor 却强制付费”的准备已被替换，不作为最终收费兑换证据。

## 完成、取消与数据边界

涉及移动到物品或 NPC 时，CLI 可以先返回 `in_progress`。运行器使用新 ID 查询原请求的 `request.get`，等待同一请求的最终响应，不把活动中间状态当成窗口已打开，也不通过另发 `state.get` 来掩盖操作提前完成。原请求 ID 不会重用。

费用、任务标记、同伴装备和指挥目标等内部断言，按最终操作响应的同一 `state_version` 查找 test-only 检查点；这些值从不参与命令、控件或目标选择。目标只取当前公开地图/实体，物品和选项只取实际窗口树。原生空物品槽使用当前公开控件顺序，不构造窗口、不反射调用隐藏游戏动作。

未祝福 Ankh 的复活场景还验证 LostInventory 可用性边界：公开库存保留灰色未保留物品并标记 `available=false`；被保留且 `available=true` 的卷轴可以正常打开详情。对灰色物品使用 `inventory.open` 返回 `ACTION_UNAVAILABLE`，同一 ID 再试返回 `DUPLICATE_REQUEST_ID`。拒绝前后公开 hero、inventory、map、visible_entities 完全相同，没有因验证而修改世界。这是生产可用性修复后的真实引擎回归。

护符胜利后的短延时是原生排名界面的展示过渡。测试单独等待该界面并读取原局公开事件；不会将这段展示等待视为额外游戏动作。新菜单 scope 通过正常 `protocol.info` 刷新。

程序退出也属于验证：强制窗口通过实际 Close 按钮关闭，最后使用公开 `app.quit`。清理失败会使该用例失败，不能把强制终止后的结果记作完整通过。

## 复现与代码边界

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/low_frequency_smoke.py
```

也可用 `--cases shop-trade,ghost-reward` 选择小组。每次运行先冻结项目运行时，逐例新建 `desktop-control/build/fixtures/p6-<case>-<uuid>`，保留 `test_fixture.json`、公开 `public-trace.jsonl`、测试断言和 `low-frequency-result.json`。汇总位于对应 `runtime-*` 目录的 `low-frequency-results.json`。

本组新增类型与脚本都位于 test source-set。FixtureLauncher 仅增加 lowfreq 参数分派和测试断言字段。本组没有修改 UiBridge 或核心游戏文件；LostInventory 的生产可用性修复由协调器/观察实现负责，本运行器验证其实际行为。

## 仍需独立覆盖的变体

这 22 项证明对应入口可用、现有窗口可完整操作，并验证了表内的取消和结果。它们没有替代实际探索或穷尽所有组合。尚未单独运行的变体包括：金币不足时的交易；Ghost 的其他任务目标与选护甲分支、Wandmaker 的尸尘/腐莓任务；铁匠不合法装备组合、其他天赋/装备下的费用；同伴完整死亡到再次充满的长周期；其他炼金配方与未知材料；完整护符返程。静态通用路线不会自动把这些变体记为通过。
