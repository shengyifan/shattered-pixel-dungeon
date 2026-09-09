# 跨层场景回归

2026-09-09，六项隔离实机用例全部通过：公开 CLI 游戏文案为英文，GUI 为中文、窗口化，共执行 96 次 GUI 环境断言。每个被测转换均从触发前的中间状态开始，经原 CLI 控件完成。没有直接创建目标场景代替转换测试，没有截图、键盘、鼠标或 computer use，不计入正式通关。

当前英文证据见 [cli-transition-scenarios-english-validation.json](cli-transition-scenarios-english-validation.json)。原五项中文 CLI 基线和当时的 pending 记录仍在 [cli-transition-scenarios-validation.json](cli-transition-scenarios-validation.json)，原文件与旧 profile 均未改写。

## 六项通过的实际用例

| case_id | 原操作与最终断言 | GUI 断言次数 |
| --- | --- | --- |
| `transition.fall` | 一层已清场，选择公开可见 Chasm。原窗口先 `No, I changed my mind`，再重新 `Yes, I know what I'm doing`。取消不改 HP/位置；确认的同次终态已在二层，HP 1000→505，Bleeding/Cripple 已附加，Falling 已结算，scope 不变。 | 17 |
| `transition.branch_roundtrip` | 原生成器生成 Caves14 矿井入口，初态已接任务并持镐。原进入确认先取消后 `I'm Ready`，实际生成 MiningLevel；原退出警告先 `Not Yet` 后 `I'm Done`，返回同一十四层主线和 scope。 | 23 |
| `transition.story_prison` | 五层已清场的原出口，实际显示准确英文监狱故事，经原 `Continue` 到六层 `player_ready`。 | 14 |
| `transition.story_caves` | 十层已清场的原出口，实际显示准确英文洞穴故事，经原 `Continue` 到十一层稳定状态。 | 14 |
| `transition.story_city` | 十五层已清场且物理闸门开放的原出口，实际显示准确英文城市故事，经原 `Continue` 到十六层稳定状态。 | 14 |
| `transition.story_halls` | 二十层已清场、上下门开放的原出口，实际显示准确英文大厅故事，经原 `Continue` 到二十一层 `player_ready`。 | 14 |

所有区域故事均保持原 scope。过渡动画开始不是通过条件：跌落必须等原着陆完成，矿井必须实际往返，故事必须回答原 Continue 并到下一稳定楼层。每个响应执行英文游戏文案检查，每个非退出观察单独核对 GUI 实际中文/窗口化。

Halls 使用冻结运行时 `runtime-573a91c4c788421ca1585125092f01bd`，其余五项使用 `runtime-c734cad508874cf497192bc5300d05d4`；生产 build ID 同为 `2adcc5a0d82d2fd3ababa77fe660472c053dbc0dc2f409a46e0f79a8d7dad3b8`。Halls 独立通过后没有再重复执行。

## Halls 源状态错误的实证诊断

本轮先增加测试阶段标记和线程栈采集，再运行失败条件。异常发生时立即记录隔离 JVM 的线程栈；迟迟未完成源准备时，测试 daemon 在 8、16、24 秒采样。它只读线程状态，不中断游戏 Actor、不推进回合、不修改游戏对象。诊断只写测试 profile，不进入公开协议。

诊断 profile `scenario-story-halls-cd9f241b0767437d8afdea20c516ae57` 的阶段显示：

1. 原 `Dungeon.newLevel()` 成功生成 CityBossLevel，用时约 2.4 毫秒。
2. 准备出口位置 127 的 `invalidHeroPos` 为 true，因为顶门仍为 LOCKED_DOOR。
3. 原 `Dungeon.switchLevel()` 正确把英雄放回入口 667。
4. 测试自身的位置不应被纠正断言抛错。即时栈指向测试的 `beforeCreate`；这次证据不是楼层生成死循环。

只设置 `locked=false` 不足以表示首领已清场。修复仅补齐测试源初态：按原 `CityBossLevel.unseal()` 的最终地图状态开放上下门。没有调用胜利、放宽生产校验或覆盖目的地逻辑。

新 profile `scenario-story-halls-3c3db7eaf93d40c48734873e866a23dd` 记录了 `invalid_position=false`、原 switch 保持 127、源 GameScene 创建和稳定观察，随后才通过原出口、英文故事与 Continue 完成 20→21。

最初旧基线中的超时和退出码 143 保留当时的事实范围；没有把本轮栈回填成当时已经取得的证据。城市故事旧基线中的 DM-300 闸门初态错误也继续保留，本轮英文城市故事已经再次通过。

## 边界与复跑

Fixture 使用原层生成器、Level 子类和 transition 描述。初始高 HP、清场、已接任务、测试深渊与开放闸门都是明确人工条件，准备后不再设置游戏数据；测试不声称实际完成前置首领战或任务战斗。

下一操作只由公开角色位置、可见地形和按钮确定。私有阶段、Level 类型、depth/branch、buff 与线程栈只用于准备或操作后断言，不提供下一目标。未访问正式 profile。

```sh
./gradlew :desktop-control:writeTestRuntimeClasspath
python3 desktop-control/src/test/python/transition_scenario_smoke.py
```

默认执行六项，可用 `--cases story-halls` 单独验证。成功结果分别落盘，失败保留原异常和诊断路径，清理不会掩盖最初错误。本轮只有测试、诊断接线、脚本与报告变动，未修改生产语言类或游戏规则。Amulet/Surface、永久死亡、排名与重开留给后续独立小组，本报告没有新增其通过结论。
