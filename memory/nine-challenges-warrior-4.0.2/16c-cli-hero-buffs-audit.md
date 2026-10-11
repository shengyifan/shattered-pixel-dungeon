# CLI.9.0.3 英雄、状态、生命与天赋信息审计

核查日期：2026-10-11。源码基线：`d04c8f862b2f90418a6375f0b3ffc5c4325d0953`，CLI.9.0.3 / protocol 9 / schema 12 / game 4.0.2 code 922。范围为原生英雄/状态/天赋显示、公开观察投影、协议压缩、正式 Python 解码器；不包括装备全部属性、地图、控制器运输或个人局的现场状态。

本分支只读生产源码，新增本文及 `evidence/cli-hero/` 中纯合成验证文件；没有启动游戏、打开个人 profile、读取个人存档/私有 audit、重打包或提交。本次检查依据当前 [AGENTS](../../AGENTS.md)、[协议手册](../../docs/cli-help.md#L194)、[客户端文档](../../docs/cli-playthrough-client.md#L1) 与 [CLI 维护 skill](../../skills/shattered-pixel-dungeon-cli-release/SKILL.md)。

## 结论与证据等级

**在已核查的正常生产路径中，未发现英雄 HP/护盾、饥饿阶段、已显示状态计数/遮罩、天赋点因为压缩或 Python 解码而丢失或变值的已确认缺陷。** 饥饿精确内部计量没有公开，是正确知识边界；状态详情需开原生窗口，不等于默认观察缺失。英雄可以有原生可读的精确 `hp/ht/shield`，敌人的 `health_estimate` 则只是已显示的条形样本，两者不能混淆。

发现 **1 个已复现的 Python 校验覆盖缺口（建议 P3）**：留在 `ui.nodes[]` 上的 `health_estimate` 和 `turn_progress` 未调用对应语义校验，同样的非法数据放到 hero/entity/subject_data 会被拒绝。当前没有证据证明生产者实际发出这种坏值，不能报告成已发生的游戏血量丢失/误报。详情见第 6 节。

证据分层：

| 层次 | 本次取得的证据 | 能证明什么 |
| --- | --- | --- |
| 静态原生→观察审查 | 下文具体生产文件/方法 | 哪些公开值来自原生显示，哪些是有意隐藏，如何归属同帧主体 |
| 纯 Python 合成验证 | [脚本](evidence/cli-hero/probe_semantic_node_validation.py)、[结果 JSON](evidence/cli-hero/probe_semantic_node_validation.json) | 精确复现校验缺口，同时确认零值、false、负冷却文本、未知 metadata 在样本中保留 |
| 既有回归测试源码审查 | 第 7 节列出的测试 | 解释现有测试覆盖设计，不把“测试存在”当作本次执行通过 |
| 本次统一测试运行 | 由主审计执行并在总报告列新日志/统计 | 本分支不重复运行 Gradle 或相同 Python 套件，以免共享构建并发；不用历史测试总数替代 |
| 实际 packaged CLI / 九挑实战 | 本分支未运行 | 不声称验证了个人局、真实窗口每一状态组合或包与源码的同一性 |

## 1. 英雄主表：精确数值的公开来源

[PlayerObservation.hero](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L101) 输出位置、职业、子职业、等级、经验/升级需求、HP/最大HP、总护盾、基础/当前力量、ready、当前金币、炼金能量、当前深度、完整天赋条目和可用天赋点、图标可见的 buff。仅在传入 `scene="game"` 且 hero 非空时生成；其他场景不应从旧帧补回缺失英雄。

这些数值的原生公开对照包括：

- [StatusPane.update](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/StatusPane.java#L312) 原生读取 HP、shield、HT，生命文字显示 `health/max` 或 `health+shield/max`；公开精确英雄数值有来源，不是为了 CLI 偷读敌人隐藏血量。
- [WndHero.StatsTab](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndHero.java#L189) 显示力量基础/增减、HP/盾、经验等。**这个窗口的 gold/depth 字样用累计收集金币和最深楼层**，主表 `hero.gold/hero.depth` 则是当前余额/当前位置；二者是不同事实，不能因为标签相似而覆盖、去重或报“数值不一致”。
- [PlayerObservation.displayedShield](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L121) 避免调用会更新缓存的 `Char.shielding()`；缓存有效时取已有值，否则只读各 ShieldBuff 后台护盾字段，且排除 AscendedForm 已失效的 DivineShield。它没有把“冷却数字”加进总护盾。

协议别名由 [WireNames](../../control-protocol/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/protocol/WireNames.java#L27) 定义：`max_hp→ht`、`max_experience→mxp`、`subclass_name→sub_name`、`talent_points_available→tp`。正式 Python 解码保留这些 wire 名称，**不会把 `ht` 自动改回 `max_hp`**；调用方按当前文档读取，不能因取错字段名把现有数据误判为缺失。

## 2. 饥饿、光照和 buff 详情：哪些信息本来不可见

### 饥饿精确计量不可从默认观察反推

[Hunger.icon/name/desc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L169) 的公开表达是：未达到 HUNGRY 时无图标，HUNGRY 到 STARVING 之间为 hungry 图标/说明，达到 STARVING 为 starving 图标/说明。内部 `level`、`partialDamage` 和 `hunger()` 精确整数不是原生 UI 展示内容。

[PlayerObservation.buffs](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L134) 只列 `icon()!=BuffIndicator.NONE` 的状态，内容为名称、原生图标编号与 `details_via="ui.activate"`。未暴露 Hunger.level、未为无图标 Hunger 造出“450回合倒计时”，这是**正确保密边界**。无饥饿图标不能得出“当前满饱腹”或“刚吃过饭”；只能在完整当前英雄观察的语境下说明当前没有显示饥饿警告。WellFed 等状态还可暂停饥饿推进，所以历次动作数也不能无条件换算为精确余量。

### 默认状态名/图标与按需详情互补

默认 buff 主表并不批量调用每个 `buff.desc()`，因此冷却和效果说明未必在默认 buff 对象里成为 `desc` 字段。对应原生显示操作是：

- [BuffIndicator.BuffButton.onClick](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/BuffIndicator.java#L418) 打开 `WndInfoBuff`；英雄详情中的 [BuffSlot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndHero.java#L353) 也有详情入口。
- [WndInfoBuff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndInfoBuff.java#L41) 将当前 `buff.name()`、`buff.desc()` 渲染为原生文本；UI 观察收集这段实际窗口文字，经官方英文文本来源投影后保留。
- `via` 说明如何进一步观察，**不授权从名字编造 click**。实际行动仍必须选当前 `ui.nodes[].ops` 中提供的控制与动作；部分状态没有可见主 HUD 按钮时，先走英雄详情页等原生路径。

### 光照持续时间、普通增益与负面效果

[Light](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Light.java#L29) 继承 FlavourBuff；其原生大图标计数来自 [FlavourBuff.iconTextDisplay](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/FlavourBuff.java#L45)，为 `visualcooldown()` 的整数显示，详情通过 `dispTurns()`；小图标可能以灰色遮罩表达剩余进度。CLI 不应把灰罩覆盖像素直接还原成精确回合，也不应把图标上 `0` 当作效果已经移除。

同样原则适用于 Poison、Bleeding、Invisibility、Haste、WellFed 等：保留当前原生显示字符串与进度，详情按需读取；不能从内部计时器创建更精确的额外数据。原生没有精确倒计时的状态，就不能要求 CLI 替玩家算出隐藏状态。本文没有将所有状态类的 `icon()` 实现逐个做完整动态组合验证；目前结论是公共汇总/显示/解码的通用路径及重点战士状态。

## 3. buff 的 counter、progress 与战士护盾语义

[BuffIndicator.BuffButton.updateIcon](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/BuffIndicator.java#L376) 在**原生 UI 更新阶段**调用 `iconTextDisplayInfo()` 和 `iconFadePercent()`，选择已经显示的文本或灰罩，并缓存选中的计数含义。随后 [gameplayStatus](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/BuffIndicator.java#L326) 只读已存在的 BitmapText/Image 几何：

- 大图标实际显示文字：`shown.counter` 保留文本，包括 `"0"` 和负数，不强改成整数。
- 小图标/无计数字符串时：`shown.progress={covered,total,basis:"displayed"}` 表示灰罩覆盖像素。`covered=0` 是观察到未覆盖，不是“该 buff 不存在”；缺字段则未观察，不得补0。
- [BuffIcon.gameplayIcon](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/BuffIcon.java#L41) 使用原生已选图标及公开变体；[GameplayIcons.buff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/GameplayIcons.java#L136) 不调用 buff getter，图标绑定不匹配会标记 partial/unmapped。Kinetic 的渐变有专门公开 fraction 来源，未生成内部储存伤害精确数。

战士护盾尤其需要区分：

| 当前原生分支 | 输出 | 正确解释 |
| --- | --- | --- |
| 实际 shield>0 | `counter_kind:"shield"` 或 `progress_kind:"shield"` | 数字是当前盾量；小灰罩相对 initialShield 的已消耗部分 |
| 无盾且 cooldown>0 或 <0 | `counter_kind:"cooldown"` / `progress_kind:"cooldown"` | 数字是原生冷却计量，可能为负；不是生命盾量 |
| 无盾且 cooldown==0 | 计数字符串为空，进度可为0 | 准备状态；不能从空计数产生一个“0盾永远不可触发”的额外结论 |

分支来自 [BrokenSeal.WarriorShield.iconTextDisplayInfo](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L300)，不是按数值文本、亮度或英语 label 猜测。其 [desc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L311) 可进一步说明盾/冷却情况。若盾和冷却同时存在，原生计数优先显示盾；不能要求单个主 HUD 计数同时公开全部内部字段。

正式 Python 的 [_shown](../../desktop-control/client/spdctl_client.py#L209) 校验 kind 仅为 shield/cooldown，progress 要求整数、正 total、`0<=covered<=total` 与 displayed basis；未知扩展 metadata 则保留。此次 [合成样本](evidence/cli-hero/probe_semantic_node_validation.json) 已确认 `counter:"-2"`、kind=cooldown、未知 nested null 注释不会被丢弃。

## 4. health_estimate 与 incoming DOT：原生已布局读数，而不是精确伤害预言

### 采集层

[HealthBar.layout](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/HealthBar.java#L65) 已用原生血量/护盾/DOT安排三条前景；[gameplayStatus/renderedPixelWidths](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/HealthBar.java#L82) 只读取这几条已经布局的宽度和观察时相机缩放，导出四个整数：

`{total,filled,with_shield,incoming_dot,basis:"displayed"}`。

[StatusPane.gameplayStatus](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/StatusPane.java#L79) 对英雄原生 HUD 同样读已布局的 hp、shieldHP、Dot；同时读可见 turn counter 的 sweep。**`with_shield` 是“HP+盾的前景总宽度”，不是独立盾宽**。

`HealthBar.level(Char)` 和 `StatusPane.update()` 里的 `incomingDOT()` 调用属于原生 UI 自己更新；本次检索 `game-control/src/main/java` 未发现额外 `incomingDOT()`、`totalIncomingDMG()` 调用。观察钩子没有为了生成观测再调用会变更计数/缓存的 DOT getter。显示里的暗区也不是新的隐藏未来伤害承诺：其原生计算、取整、裁剪是显示的边界。

### 绘制证据与同帧归属

[UiBridge.captureDrawnEvidence](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L116) 保存已绘制的显示字段；它明确不在此时调用操作回调、hover动作发现或游戏 getter。后续状态读取用冻结显示证据，场景/显隐/绑定变化后需要匹配的新绘制，避免把后来字段和早先图像混成一条事实。

[UiBridge.publicSubjects/addBuffSubjects](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L452) 只将本帧已有英雄/角色/状态对象关联到本帧表索引，不按名称猜身份；[GameplayObservation.merge](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservation.java#L49) 将显式归属节点的 `shown`、health样本和turn sweep 合并入主对象，并仅在事实已保留下删除冗余节点。多条不相同的条宽样本 [mergeHealth](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservation.java#L250) 同时保留，不强行选一条覆盖其余条。

并非每个原生 HealthBar 都有主体：[WndInfoMob.MobTitle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndInfoMob.java#L60) 建立普通 `HealthBar`，其基类没有对应角色的 `gameplaySubject`。这类健康样本可留在 UI 节点中，**不能只读 hero/entities 而忽略 ui.nodes**。没有显式 subject 也不能用“旁边的敌人名字”偷偷造绑定；可在窗口上下文中阅读原生内容。

### 协议/解码层

默认 play、full、src 都保留公开语义事实；full/src 不是未压缩旁路，也不能取得精确敌人HP。[_health_estimate](../../desktop-control/client/spdctl_client.py#L700) 对受检位置要求：

- `total>0`；`0<=filled<=with_shield<=total+1`；`0<=incoming_dot<=with_shield`。
- 四个值必须是真正 JSON 整数，拒绝 bool/float/缺失；basis 必须 displayed。
- 一段过伸是原生背景 round 与前景 ceil 的差异，如 `21/22/22/0` 有效，不能强行夹成21。
- 缺失 `health_estimate` 或显式 null 表示没有样本；一旦给出具体 sample，该 sample 不能缺 incoming_dot。`incoming_dot:0` 才是观察到暗区为0。

[_turn_progress](../../desktop-control/client/spdctl_client.py#L690) 要求 sweep 为有限0..1数值，保留0；它是已显示的转盘进度，不是可以反推出未来怪物行动次序的内部 actor.time。

## 5. 天赋、选中页与压缩链路

[PlayerObservation.hero](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L102) 遍历所有当前 talent tier 条目，**0点条目保留**；每阶可用点数调用与原生一致的 [Hero.talentPointsAvailable](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L393)，含等级、副职业/护甲能力门槛与额外点数，不用“等级差”另算。主表提供每个 talent 的 tier/name/points；具体 max/升级条件与完整效果文案还可从当前原生天赋窗口读取。

[TalentButton.layout/gameplayStatus](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/TalentButton.java#L99) 缓存该控件原生填充条的 `spent/max`，因此排行榜等**无当前 hero**的窗口也能输出 `talent_points:{spent,max}`。`spent=0` 和 `selected:false` 不是可删默认值，[_semantic_fields 节点校验](../../desktop-control/client/spdctl_client.py#L931) 分别执行布尔/整数检查。选中状态或点数存在不表示可升级：执行权仍只来自同帧 ops。

压缩边界核查：

- [CompactProtocol.projectValue](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/CompactProtocol.java#L69) 对常规字段递归复制/别名变换；[simplifyFields](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/CompactProtocol.java#L151) 的默认值省略集中于 item 和 UI 的 enabled/dimmed 等，没有删除 hero.talents 中0点或 selected:false 的规则。
- [GameplayObservation.removeHeroText](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservation.java#L263) 只删除与本帧英雄数值精确重建相符的被动文字；有盾的 `HP+shield/HT` 与其他独立文案没有因此全部被丢弃。
- [Python._expand_observation](../../desktop-control/client/spdctl_client.py#L423) 从当前模板扩展 UI/actions，之后 [decode_data](../../desktop-control/client/spdctl_client.py#L998) 分别处理当前与 before/after；没有把上一帧值补进本帧。[_generic](../../desktop-control/client/spdctl_client.py#L160) 深复制未知字段，不做按 label 缩减。
- `actions` 只返回 UI/actions时用直接 `subject_data`；[_subject_data](../../desktop-control/client/spdctl_client.py#L748) 对其中 buff/health/turn同样校验。不能拿上一次 hero.buffs 去解析新的 actions-only 索引。

## 6. 已复现 P3：UI 节点上的 health/turn 语义没有统一校验

**位置：** [spdctl_client._semantic_fields](../../desktop-control/client/spdctl_client.py#L922)。节点循环检查 shown、selected、talent_points、icon、subject，但没有对 `node.health_estimate` 调用 `_health_estimate`，也没有对 `node.turn_progress` 调用 `_turn_progress`。相反，hero/entity/subject_data 分支已有这些调用。

**为何不是虚构字段位置：** 前述 `WndInfoMob` 的普通 HealthBar 没有明确 native subject；[GameplayObservation.merge](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservation.java#L54) 只有 `target!=null` 才迁移节点 health，因而合法生产协议允许 health 样本原地留在 `ui.nodes`。不能假设每个 health 样本一定已经迁移到 hero/entity。turn_progress 留节点则属于无法成功合并或缺主体的保留路径。

最小输入片段：

```json
{"v":9,"id":"audit.1","s":"s1","rev":"r1","st":"completed","data":{"ui":{"nodes":[{"id":"c1","role":"health_bar","health_estimate":{"samples":[{"total":16,"filled":8,"with_shield":8,"basis":"displayed"}]}}]}}}
```

该 sample 缺少协议9必需的 incoming_dot，却被接受。相同 sample 放到 `data.hero.health_estimate` 则抛 `DecodeError`，并正确保存原始解析JSON和失败身份。`ui.nodes[0].turn_progress.sweep=2` 同样被接受，放 hero 则拒绝。

可复核命令（纯内存构造、不启动任何游戏）：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 memory/nine-challenges-warrior-4.0.2/evidence/cli-hero/probe_semantic_node_validation.py
```

本次执行结果已保存 [JSON证据](evidence/cli-hero/probe_semantic_node_validation.json)：四个对照 case 分别 rejected/accepted/rejected/accepted，第五个有效组合样本 accepted；五个输入均未被修改。有效组合还验证了负数冷却字符串、0点天赋、false选中状态、0 sweep、0 incoming_dot、一段血条过伸和未知注释全部保留。

**影响与范围：** 是“已知语义类型检查位置不一致”的健壮性问题，不是已证实 producer 发送了错误血条，不是健康数据丢失；原合法值经过该节点分支仍保留。如果损坏/错误 producer 返回了这种数据，正式解码器不能在这些节点位置阻止使用不合法的健康/转盘事实。

**建议最小修复（本次未修改 production）：** 在节点循环对存在的 `health_estimate`、`turn_progress` 复用同一校验函数；保留已有 null/unknown约定、未知字段、原始错误证据与模板展开顺序。回归应覆盖节点直接记录和node_templates、play/full/src等相同结构、before/after独立解码，并保留已允许的一段血条过伸。不要为补这个位置而放宽 hero/entity 的现有边界。

## 7. 既有回归覆盖与可补的证据

本次阅读的相关现有测试：

| 测试 | 当前覆盖意图 |
| --- | --- |
| [HealthBarClientContractTest](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/HealthBarClientContractTest.java#L24) | 真实 native layout/export→Java codec play/full/src→Python，含21/4/22、21/22/22、一段过伸与DOT |
| [GameplayStatusTest](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayStatusTest.java#L54) | buff counter/mask取已显示值、不再调用背后buff getter；同样的“2”区分shield/cooldown、小图标分支和负冷却 |
| [UiDrawStatusEvidenceTest](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiDrawStatusEvidenceTest.java#L18) | 完成绘制前不发布显示值；native随后变化不改旧冻结证据；场景/隐藏/更换组件与实际buff灰罩更新 |
| [GameSnapshotterTest](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameSnapshotterTest.java#L202) | 失效DivineShield不虚报盾，读取护盾不修改cache/needsShieldUpdate |
| [PlayerTalentObservationTest](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerTalentObservationTest.java#L21) | 等级/消耗/子职业/护甲能力门槛、额外天赋点与读取不修改状态 |
| [NativeTabTalentObservationTest](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/NativeTabTalentObservationTest.java#L145) | 无hero排名页真实TalentButton填充、last-layout取值、before/after独立、原生tab选中状态 |
| [test_protocol9_facts.py](../../desktop-control/src/test/python/test_protocol9_facts.py#L92) | selected和talent_points经过模板/actions/冻结帧保留、非法类型拒绝，DOT多主体覆盖 |
| [test_health_estimate_rounding.py](../../desktop-control/src/test/python/test_health_estimate_rounding.py#L27) | 严格数值/顺序/basis、one-pixel overhang、actions subject_data、失败identity和原动作上下文 |
| [test_actions_subject_data.py](../../desktop-control/src/test/python/test_actions_subject_data.py#L50) | 五种内联主体、跨帧独立性、未解析主体null+诊断、嵌套非法状态失败 |

这些测试已很好覆盖受关注的旧风险，但现有 health Python 测试的重点是 hero/entities/subject_data；此次未绑定 node 验证缺口解释了为何已有检查仍可能遗漏一个字段位置。

后续若需要“真实游戏中的所有 buff 均可完整操作观察”的更强结论，应使用新隔离 fixture 验证至少：hungry→starving→进食、Light大/小UI与0计数、战士有盾同时冷却→无盾冷却→负冷却、多个同图标不同状态、英雄状态页滚动及详情弹窗、前景/背景血条在缩放与护盾超HT时的样本。这些属于尚未完成的动态覆盖，不得混为本次已复现缺陷；也不得为测试方便触碰个人进程或从私有存档读取隐藏数值。
