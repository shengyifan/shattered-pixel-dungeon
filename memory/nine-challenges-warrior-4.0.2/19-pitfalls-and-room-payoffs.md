# 跳坑取宝与特殊房的主动兑现：4.0.2 全九挑战战士

研究日期：2026-10-14；核查源码 HEAD：`bb0c91fb7c6fa8e33819c554427f81b6cf93f2f9`，游戏 4.0.2 / code 922、CLI.9.0.6。当前游戏仍按用户要求暂停。本章只读项目源码与已有研究，并运行不连接游戏的公式计算；没有启动游戏、发送查询或操作、读取个人存档、私有审计或隐藏下一层。

用户本次策略要求是：积极获取和使用神器，适时主动试用未知药卷，安全时考虑跳楼取宝。本章负责最后一项，以及与之相似的“已有工具怎样换到实际资源”。完整房型与谜题药生成表见[13 特殊房](13-special-rooms.md)，层级安排见[12 楼层计划](12-floor-exploration-plan.md)，秘密房见[14 秘密房](14-secret-rooms-and-search.md)，材料取舍见[15 炼金](15-alchemy-planning.md)。这里补充坠落算账、盾触发边界、落地脱困与主动回收窗口；不把“可选房”自动等同于“应跳过”。

证据分为 **源码事实**（可从链接重读）、**条件推导**（明确前提的决策建议）和 **待实局验证**（当前没有公开执行记录）。房间生成概率不是当前隐藏奖励的观测。

## 1. 先分清四种坑与三种井

| 当前可见机制 | 源码事实 | 能否指望取得下层遗骨奖励 |
| --- | --- | --- |
| 薄弱地板房 `WeakFloorRoom` | 实际是深渊 `CHASM`，向内伸出的若干 `EMPTY_SP` 实地条；远处井的装饰格仍是深渊。生成时要求下一层加 `PitRoom`；下一层为 Boss 时不选此房 | 从本房内实际坠落会设 `fallIntoPit`；通常配对落在下层坑底房，不要求只跳装饰井那个格 |
| 普通深渊 | 坠落统一使深度 +1；默认落点排除陷阱格与被怪占据的格 | 不保证坑底房、宝物、井、楼梯或落点邻域安全 |
| 显形陷阱走廊 `TrapsRoom` | 约 1/4 为深渊版，其余铺一类显形陷阱；远端宝箱是本层奖品，安排浮空药进本层生成队列 | 标准目标是去程、开箱、取物、回程；从这里掉下不因此获配对坑底房 |
| 落穴陷阱 `PitfallTrap` | 触发后延迟 1 时间，覆盖中心九邻中适用的格；非飞行角色可能掉层。Boss 层、深度 >25、非主线分支触发时不建坑 | 是危险机关；是否进坑底仍由实际坠落格所属房间判定，不能把陷阱名称当宝房证明 |

来源：[薄弱地板](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/WeakFloorRoom.java#L39)、[配对与 Boss 前排除](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SpecialRoom.java#L158)、[判定坠落房](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Chasm.java#L99)、[跨层](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/InterlevelScene.java#L692)、[普通落点](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1153)、[陷阱走廊](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/TrapsRoom.java#L59)、[落穴触发](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/PitfallTrap.java#L50)、[延迟坠落](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/PitfallTrap.java#L95)。

薄弱地板房中“远处的井”是向下层奖励房的线索；坑底房角落是 **EMPTY_WELL 干井，不回血**；治疗井则具有 `WaterOfHealth` 效果，必须读当前公开名称和描述分辨。不能以“井”一字把三者合并。[上层井格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/WeakFloorRoom.java#L71)、[坑底干井](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PitRoom.java#L54)、[治疗井](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java#L48)。

**条件推导：** 已确认薄弱地板且已有可靠落地处理时，应把坑底房列为可认真执行的资源目标，尤其缺神器/戒指/有效装备时。也可先从普通楼梯探索下层、公开找到坑底水晶门与上楼路线，再回上层跳入：这会增加往返粮光成本，但能减少对房外路线的未知。没有看见的下一层不得提前按源码布局填图。

## 2. 坑底奖品、落点和出口账

`PitRoom` 的主奖励先等概率选择戒指、神器、装备三组；装备组内部近战/投掷/护甲权重为 2:1:2。神器生成池耗尽时会回退戒指，因此“选中神器组”不等于本局一定出神器。另有 1–2 件随机药/卷/食物/金币，并且遗骨堆包含**当前下层深度的水晶钥匙**。主奖励没有额外强制无咒或升级保证；若物品诅咒，遗骨会 haunted。它是有明确高价值奖池的冒险，不是保证马上能装备的神器。[奖品与钥匙](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PitRoom.java#L66)、[神器回退](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L706)。

配对落点从坑底房当前可走且无怪占据的候选格选取；不是固定落在干井或门边。房间禁止生成陷阱、草，这给落地处理留出空间，但“落点本格无怪”不证明房内外完全无怪、无危险或有现成安全上楼路线。候选为空时还会退到普通落点规则。[坑底落点](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L768)、[不放陷阱和草](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PitRoom.java#L105)。

水晶门是常规出口，需从遗骨取出本层水晶钥匙再开。钥匙按层绑定，捡钥匙、开门都要耗时；门外路线仍须公开探索。神器 Skeleton Key 的替锁是另一种有条件工具，不能凭背包有同名神器便假定当前可用。[钥匙深度与拾取](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/keys/Key.java#L38)、[水晶门消费本层钥匙](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2421)。

遗骨还包含战斗账：`haunted` 时尝试在遗骨格生成怨灵；该格被占时会尝试允许的八邻空格，**不是站在遗骨上就必定阻止生成**。只有无法找到位置才使打开者承受当前 HP/2 的一次伤害。普通怨灵仅 1 HP，但防御很高；其生成代码还有受饰品修正的稀有受难之魂分支。不要把“只有一只 1 HP 怪”写成必定一刀结束。[Heap.open](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L86)、[怨灵邻格后备与稀有分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Wraith.java#L120)、[怨灵属性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Wraith.java#L46)。

**条件推导：** 落地先处理持续伤害和残废，再从有退路的相邻格打开遗骨；准备可靠魔法、可实现的偷袭或控制，按新敌人的真实公开身份重算。钥匙是退出所需资源，物品堆全部拾取和怨灵处理不是零时间。若上层仍有已知安全粮食、火把或关键药卷，优先顺路收好再跳；回程不能只预算一次开门。

## 3. 无羽落坠落：直接伤害、流血、残废分别算

在没有羽落且没有其他伤害修正的基础情形，记落地占格处理后、计算坠落伤害时的生命为 `H`，最大生命为 `M`。若落点占格另触发格效，应先计入，不能把起跳 HP 无条件当作这里的 `H`：

```text
a = floor(H / 2)
b = floor(M / 4)
D = max(a, NormalIntRange(a, b))
B = round(M / (6 + 6 * H/M))
```

`D` 是直接传给英雄伤害流程的值；`B` 是传给新流血的强度输入。`heroLand` 先占格、施加 10 时间残废、设置流血，然后造成直接伤害。普通护甲 DR 没有在这条坠落调用前扣除；通用伤害倍率、免疫和盾仍按英雄/角色伤害路径处理，不能把基础公式直接替代带特殊 Buff 的实际结算。[落地顺序](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Chasm.java#L131)、[残废时间](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Cripple.java#L29)、[英雄伤害修正](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1628)、[角色伤害流程](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L830)。

`NormalIntRange` 的实现没有交换反向参数；当 `a>b` 时外层 `max` 保证直接伤害仍是 `a`。基础直接伤害范围可保守写作 `[a,max(a,b)]`；HP 不高于最大 HP 的约 1/4 时，直接伤害已经可能致死，之后还没算流血。[随机函数](../../SPD-classes/src/main/java/com/watabou/utils/Random.java#L147)。

新流血先取 `L₀=NormalFloat(B/2,B)`；每次流血 tick 扣 `round(L)`，再取 `L下一次=NormalFloat(L/2,L)`，衰减到四舍五入为零才移除。已有更强流血不会被较弱新值覆盖。源码血条用 `round(4*当前L)` 估计剩余流血伤害，并明确标注为 average；它不是保证总伤害或确定剩余回合数。故公开 `incoming_dot` 是重要估计，但不能用作安全证明。[流血设置](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Bleeding.java#L75)、[每 tick 结算](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Bleeding.java#L103)、[原生平均估计](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Bleeding.java#L159)。

在**无旧流血、无盾、无其他伤害/恢复/倍率**的人工条件下，前三次流血 tick 每次可用 `B` 作上界，得下表。它不是三个玩家动作：一次残废移动或吃饭可能跨多个 tick。表中正数只说明这段有限伤害模型有余量，负数说明该预算无法证明存活，均不计算怨灵和下层敌人。

| H/M | 直接伤害范围 D | 流血输入 B | 仅直接伤害后 HP 下界 | 再算三个流血 tick 后 HP 下界 |
| --- | --- | --- | --- | --- |
| 20/20 | 10 | 2 | 10 | 4 |
| 25/25 | 12 | 2 | 13 | 7 |
| 17/25 | 8 | 2 | 9 | 3 |
| 12/25 | 6 | 3 | 6 | -3 |
| 6/25 | 3–6 | 3 | 0 | -9 |
| 50/50 | 25 | 4 | 25 | 13 |
| 33/50 | 16 | 5 | 17 | 2 |

上述纯公式计算、前提和源码 SHA-256 保存在[脚本](evidence/fieldcraft-20261014/pit-budget-formulas.py)与[结果](evidence/fieldcraft-20261014/pit-budget-results.json)。没有引擎执行或随机试验；也没有声称完整流血存在一个固定最大 tick 数。

### 3.1 战士盾可以参与预算，但不能预支未触发的盾

坠落和流血不是饥饿，均会经过 `ShieldBuff.processDamage`；已有实际盾可吸收它们。战士纹章盾在非饥饿的正伤害发生时，若 `H<=floor(M/2)`，或 `H+当前盾-该次伤害<=floor(M/2)`，且纹章盾存在、不在冷却，先激活再吸收本次伤害。最大纹章盾为 `3+2*护甲阶数+钢铁意志点数`；并不因苦行者的普通护甲 DR 削弱而自动失效。[触发与吸收顺序](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L957)、[盾的饥饿例外](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ShieldBuff.java#L109)、[纹章盾上限](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L95)。

一个容易误算的条件例子：0 当前盾、可用的 5 点纹章盾、没有其他倍率时，`20/20` 坠落伤 10，触发盾后剩 15 HP；`25/25` 坠落伤 12 后剩 13 HP，仍高于 `floor(25/2)=12`，**这一次不触发**，后续流血可能才触发。不能照搬“满血跳一定触发盾”。已有其他盾也可能让触发延后。[离线例子](evidence/fieldcraft-20261014/pit-budget-results.json)。

盾显示的 cooldown 不是实际护盾；激活后还受无可见敌人约 5 tick 的消散规则影响，不能把剩余盾视作整段下层探索的永久生命。默认冷却基数 150，也不值得为一间房无预算地原地等满。[显示语义](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L301)、[衰减和激活](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L322)。

### 3.2 九挑战下的恢复和解除不能混用

- **羽落合剂优先比较。** 1 浮空药 + 10 能量制 1 羽落；Buff 为 50 时间。落地检查到它便减 10 持续时间并直接返回，避开本次 `heroLand` 后续的直接伤、流血和残废。没有“必须还剩 10 才保护这一次”的判断：存在时即保护，再扣减至零则移除。但不能因显示 0 而猜其仍在，实际到落地前必须有效。[羽落处理和配方](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfFeatherFall.java#L43)、[落地提前返回](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Chasm.java#L135)。
- **浮空是另一件工具。** 普通浮空 20 时间，饮用使 flying，适合往返陷阱走廊；在深渊上不会自然坠下，结束时会重新占格并可能坠落。投掷浮空药产生混乱气，不给自己羽落或浮空。[浮空结束](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Levitation.java#L40)、[非飞行才落坑](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1176)、[饮投差异](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfLevitation.java#L43)。
- **全面净化有直接用途。** 清除流血、残废等负面并消除普通饥饿，另给 5 时间 Cleanse，期间阻止适用负面 Buff 添加。事后用不能追回已扣 HP；跳前用需确认其短时效确实覆盖落地，且仍能承受直接坠落。它可以是主动取宝方案的一部分，不能默认等同羽落。[全面净化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L75)、[阻止负面](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1221)。
- **治疗药在恐药下不能当普通治疗。** 它先 cure（包括清流血和残废），随后施加 `4+英雄等级/2` 强度的中毒。确实清了旧状态，也确实添加新代价；不能据“能止血”便列为无代价落地恢复。治疗井则直接 cure、饱腹、净化已装备物和治疗，没有走治疗药恐药分支；鲜血试管可能把井的治疗改为延迟。[治疗药顺序](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfHealing.java#L51)、[治疗井](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java#L51)。
- **钢铁之胃不能作为跳层免伤凭据。** `beforeTransition` 明确移除该免伤，也移除决斗场 Buff，并把零碎行动时间推进到整时间。羽落、浮空没有在该清除列表中，但仍有自己的时效。[跨层边界](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L611)。

## 4. 一张实际可执行的跳前决策账

用当前公开状态填写，空白项保留未知；不从旧局、私有数据或源码生成数组补齐。

| 账项 | 跳前必须具体写下什么 |
| --- | --- |
| 回报 | 已确认薄弱地板配对机会；当前缺神器/戒指/装备哪一类；奖励仍未知时不预支具体属性 |
| 落地伤害 | 羽落是否确实有效；否则基础 D 范围、当前盾与触发条件、已有持续伤害、计划几 tick 内可靠止血 |
| 动作时间 | 残废期间基础移动速度减半，普通一格移动由 1 变 2 时间；加上接近遗骨、打开、处理怨灵、捡钥匙/奖励、开门。不能只算几何直线 |
| 后续战斗 | 能处理普通怨灵和意外不同敌人；没有可验证输出或退路时，不以“只有 1 HP”抹掉该项 |
| 粮光 | 覆盖上层整理、落地处理、房外探索/实际楼梯回程；下层理论会生成的食物和火把不等于手上储备 |
| 退出 | 门钥匙的获取计划，门外当前已知/未知；若不能回上层，继续向下是否仍有资源 |
| 最终决定 | 现在执行／先取已见工具后执行／本次延期。延期写明缺口及重评条件，不能永久忘记这间房 |

残废速度依据：[Char.speed](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L772)。

**条件推导：** 最值得主动执行的窗口是：已经看到该房，羽落可用，或直接伤可承受且有确定止血手段，怨灵输出与下层探索储备够用。裸跳也应按同一账评估，不能一律禁止；但仅“HP 大于某百分比”“战士会出盾”“落下有井”都不构成安全保证。用于继续探索的余量必须留在付完落地和开骨堆费用之后。发生计划外掉血、药剂身份不符、落点非预期或新敌人时立刻用新公开帧重算，不强行沿原取宝路线。

## 5. 类似主动资源技巧：以工具换收益的时机

这些机制在第 13 章有完整房型说明；下表强调何时应主动抓住机会，以及不能省略的代价。

| 机会 | 积极兑现方式（条件推导） | 经当前源码复核的边界 |
| --- | --- | --- |
| 墓室/遗骨 | 先准备可靠魔法或可实现偷袭，开一次、处理一次；有祛邪时评估墓室高区甲和战士盾收益 | `TOMB` 在英雄四邻尝试生成怨灵；haunted 遗骨是本格加八邻后备，不能混作同一种开法。稀有怨灵变体需重评。[Heap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L86)、[Wraith](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Wraith.java#L102) |
| 祭坛 | 把原本就能安全处理的近战怪、受控召唤物或怨灵引到有效九格内收尾；武器缺口与可解咒时主动投入有限步骤 | 标记者死亡时仍要在火源九邻；贡献随怪种和随机 2–3 倍变化，不能承诺固定三只。武器必已知诅咒，额外升级有条件；不要无限找怪献祭。[贡献](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/SacrificialFire.java#L153)、[奖励](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SacrificeRoom.java#L84) |
| 治疗井 | 已有未鉴定/诅咒装备的安全试装机会、HP 或饥饿缺口时，合并一次回血、饱腹和全套已装备祛邪收益；可以列为短途回收目标 | 水袋填充、祝福十字架、单物解咒与自己喝井争同一次资源。先穿会立即造成危险或无法承受战斗的诅咒装备仍不合理；鲜血试管会改变治疗时序。[WaterOfHealth](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java#L51) |
| 食人鱼池 | 干岸远程、有效击退上岸、已知能使鱼浮空的手段可换肉和箱子；或用隐身直接取箱，把非必要战斗省下 | 鱼基础速度 2、HP=10+5×深度；离水或 flying 会死亡，不能步入水中硬换血。它具有大量气体免疫；毒气不是通用廉价打鱼法。普通鱼有肉掉落，EXP=0。[鱼](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Piranha.java#L44)、[离水/浮空](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Piranha.java#L69)、[免疫](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Piranha.java#L159) |
| 鱼肉加工 | 安全拿到肉后，用顺路火/冰/炼金加工，把它作为额外进食及天赋触发机会；别等濒死才吃生肉 | 生肉有燃烧、扎根、中毒、减速分支。冻肉有正面随机分支，包括直接回复 HT/4 或 cure，但不是保底治疗；节食仍把营养除以 3。[生肉](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/MysteryMeat.java#L54)、[冻肉](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/FrozenCarpaccio.java#L54)、[节食](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L114) |
| 永燃火墙房 | 已有冰杖、冰霜瓶、合适的净化投掷/水爆时，主动用一份工具打开整条通路，回收后方物资 | 冰/暴雪在火格九邻或火格变水会清整组；火格四邻也可点燃英雄。浮空不是穿墙方案，喝净化也不自动拆墙。[EternalFire](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/MagicalFireRoom.java#L156)、[净化投掷](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfPurity.java#L55) |
| 毒气房 | 商店现金缺口明确、免疫及往返预算成立时主动取；水爆可改掉通风载体，构建后续可重复通路 | 投净化清掉的现有毒气还会补回；`ToxicGasSeed` 只有载体不再是失效陷阱地形才停止此处续气。已附 Poison 与气体伤害是不同问题。[气源](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ToxicGasRoom.java#L127)、[改水](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1118) |
| 哨兵房 | 先确认普通安全格，极速走危险路，安全格开箱/取物；水爆改出实际安全踏点或定点位移可作为替代 | 哨兵条件检查 EMPTY_SP、FOV 与房内位置，**不检查隐身**；离开这些条件会重置充能。改成水后不再满足其地板条件，但需观察实际改地结果，不猜外圈随机改水成功。[哨兵](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SentryRoom.java#L237)、[允许改水的地形](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1134) |

水爆不能把深渊改成水，所以“给哨兵造安全格”和“给深渊铺桥”不能相互套用。对鱼的击退、对墙的投药、对陷阱的投物都要用当前同帧几何和完整实体判断；先确认自己不会受推落坑、堵住退路或毁掉待取物。

## 6. 保存为后续决策习惯

每次遇到机会，至少列出一条当前已有工具的主动解法，比较其可兑现回报和完整退出成本。对已看到且安全可达的神器、药卷、粮食、火把，不因房型“可选”而默认略过；确实延期就记录具体所缺工具。新得到羽落原料、冰杖、机动神器、祛邪或净化时，回查已见目标是否已变便宜，并把同层回收合并到实际路线。

待实局验证的内容包括：当前包公开操作下的具体跳坑确认与落地调度、给定神器组合的跨层时序、具体残废状态下的安全开骨堆路线、受难之魂出现后的处理、以及有限资源策略能否提高本局胜率。本章的源码链和公式不替代这些执行证据；恢复游玩须由用户另行明确授权，随后通过原有单一 `spdctl` 连接重新观察。
