# 铁匠矿洞：九挑战战士源码研究（4.0.2）

核对基线：`8405ae82f`，2026-10-10；研究本地源码，未启动游戏、未读取个人存档、未做实战验证。本页的数值是未额外加成的类基础值；实战只依据当帧公开观察确认位置、护盾、预警及关联，不能读取源码对应的隐藏实例字段。`NormalIntRange` 表示区间内偏向中值的随机数，并非恒定伤害。

## 先纠正常见版本误读

**当前正常生成只有水晶和豺狼人两种任务；菌类任务尚未实现。** `FUNGI=3` 是部分实现占位，铁匠在深度12–14生成，任务类型只抽 `Random.IntRange(1,2)`。不能因为仓库存在 Fungal 类便把菌洞列为本版本真实候选。

来源：[任务类型定义](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Blacksmith.java#L205)、[生成入口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Blacksmith.java#L347)。

## 入矿前的资源账与退出边界

- 地图固定组成：1巨型、3大型、6–8小型、2秘密洞室；金矿生成目标45–47。三个大型洞室分别生成水晶守卫，或一对工兵/守卫；巨型洞室放任务首领。矿洞正常怪数量较普通洞穴少1，重生间隔覆盖为 `3*50=150` 游戏时间单位。
- 原生矿怪创建直接 `new CrystalWisp/GnollGuard`，并未调用父类 `Level.createMob()` 中的冠军抽签；固定任务怪同样直接构造。因此**这些原生生成路径没有额外冠军附魔**，但继承 Mob 的集群智能仍会影响普通追猎怪。水晶守卫睡眠时忽略 beckon，不能把全图普通集群模型机械套给它。
- 水晶支线生成1份随机食物，豺狼人2份；黑暗挑战额外生成1火把。节食下食物仍只有1/3饱食，不是额外的无限续航。挖普通墙每次额外消耗3饥饿，加上动作约总4；挖金、晶体、岩块约总1。已饥饿时额外饥饿可能直接转化HP损失，护盾不能当食物。
- 挖晶体或邻晶普通岩壁会额外打碎周围八格晶体；挖金矿明确不触发邻晶连碎。可规划一次挖掘打开通道，亦可能意外打通水晶守卫的牢笼。
- 退出确认即 `Quest.complete()`：交出暗金与镐头，每块金50好感、金矿部分上限2000（40块），首领额外1000；总好感≥2500免费取回镐头。**“先退出补给、再回来把未交目标补齐”不是可逆任务进度策略。** 退出前看实际确认文案；未杀首领也可以接受较低收益退出。
- 敌人攻击造成的 `Statistics.questScores[2]-=100` 是评分惩罚；好感在 complete 时重新按暗金与首领计算。受击扣分不等于直接扣奖励货币。

来源：[布局、金矿、生成、食物火把](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/MiningLevel.java#L92)、[父类冠军入口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L536)、[固定怪配置](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MineLargeRoom.java#L76)、[挖掘成本与连碎](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1312)、[饥饿溢出损伤](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L136)、[节食倍率](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L114)、[退出确认](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/MiningLevel.java#L238)、[结算](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Blacksmith.java#L443)。

**战术推导：**九挑战矿洞的价值是好感升级/重铸机会；其成本主要是饥饿、不可恢复HP、照明与控制资源。先选“拿多少好感买什么”，再决定是否打首领。40金后多挖没有额外好感；不存在为了经验清空重生怪的合理常规收益。

## 基础敌人数据与继承规则

| 敌人 | HP | 命中/闪避 | 基础伤害 | 额外物理减伤 | 关键限制 |
| --- | ---: | --- | --- | --- | --- |
| 水晶幽灵 CrystalWisp | 30 | 18 / 16 | 近战5–10；光束5–10 | 0–5 | 飞行、无机；可走晶体；魔法光束1时间单位 |
| 水晶守卫 CrystalGuardian | 100 | 20 / 14 | 10–16 | 0–10 | 无机、小首领；不会真正死；倒地时闪避0 |
| 豺狼人守卫 GnollGuard | 35 | 20 / 15 | 相邻6–12；非相邻16–22 | 0–6 | 距离≤2且双向PROJECTILE通路；工兵活着伤害÷4 |
| 豺狼人工兵 GnollSapper | 45 | 18 / 15 | 普通近战1–6；投岩/落石6–12 | 0–6 | 小首领；固定区域，控场优先 |
| 豺狼人地卜师 GnollGeomancer | 150 | 定义命中20；常规攻击不执行 | 特技6–12；普通伤害3–6仅类定义 | 0–6 | 首领、不可强推；石甲与工兵保护分开处理 |
| 水晶尖塔 CrystalSpire | 300 | 交互伤害，不走普通命中 | 晶刺6–15 | 不以普通物理对战处理 | 只收镐伤害；全buff/debuff免疫 |

水晶幽灵、守卫、豺狼人守卫、工兵均 `maxLvl=-2`。虽然源码分别写了EXP=7或10，正常英雄不会满足经验条件；掉落也要求 `hero.lvl <= maxLvl+2`，所以不能期待守卫表面10%长矛掉率。尖塔还设中立阵营，不要把EXP=20字段当可获得经验承诺。

无机属性免疫出血、毒气、中毒；**没有因“水晶”一词自动获得火/冰免疫**。小首领免疫 AllyBuff、Dread；首领同样免疫，并对 Grim、GrimTrap、Retribution、PsionicBlast 有属性抗性。不可移动属性免疫眩晕方向扰乱（Vertigo）。所有最终行为还要叠加具体类覆写。

来源：[幽灵](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalWisp.java#L37)、[守卫](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalGuardian.java#L46)、[豺狼人守卫](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGuard.java#L35)、[工兵](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollSapper.java#L37)、[地卜师](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGeomancer.java#L67)、[尖塔](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalSpire.java#L61)、[经验门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L978)、[掉落门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1097)、[属性继承](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1417)。

## 水晶支线

### 水晶幽灵：短视距下优先处理的射手

源码事实：幽灵的MAGIC_BOLT可到达英雄时可远程攻击；相邻则普通近战。光束进行魔法命中判定，直接以 `LightBeam` 造成5–10伤害，不走普通近战护甲掷骰。它能穿行 `MINE_CRYSTAL`，但不能据此断言任何射线也能穿晶墙；射线仍由具体Ballistica碰撞规则决定。它飞行，不能靠普通深渊坠落解决。

战术推导：战士优先借岩壁/转角逼近或遮断一只幽灵射线，不在开阔矿厅隔着晶体与多只幽灵换血；近战本就只有5–10，而且移除持续远射。无机免毒使未鉴定药水乱扔及毒气消耗特别亏。黑暗下先照亮通路再敲尖塔，避免开战时才发现幽灵集结。

来源：[通行、近远程分支与光束](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalWisp.java#L68)。

### 水晶守卫：利用地形与尖塔，不做常规击杀

源码事实：生命≤0时被强制改为1，移除除 Doom/Cripple 外的buff并进入 recovering；每tick回5HP，满100恢复。倒地时英雄的近战在 `defenseProc` 里直接扣HP到最低1，可延后恢复，但不能杀死；其它盟友攻击倒地守卫受专门无敌判断阻止。恢复时不算伏击。普通情况下狭窄格移动速度最多只有原速1/4、最低0.25；为追击可破晶走捷径，打碎晶体再多花一次基础移动耗时。睡眠时仅在原生可走图有路径时才正常醒；普通beckon忽略睡眠守卫。

战术推导：保留晶体封闭区，避免无收益提前唤醒。迫不得已拉过窄口后趁移动慢甩开；不能把“倒地”当本层永久安全。要压住倒地守卫只做明确争取尖塔击杀窗口的动作，别无限消耗饥饿。狭窄格只降低移动速度，**并不把相邻攻击也变成四回合一次**。

来源：[恢复、战斗、假死](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalGuardian.java#L64)、[移动、破晶、醒来](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalGuardian.java#L205)。

### 水晶尖塔：镐交互＋逐波躲避＋借晶刺控守卫

源码事实：镐在背包中即可交互，不要求装备；伤害与耗时把尖塔作为镐的owner，只取镐等级/增幅相关因素，不吃英雄力量/装备加成。300HP，源码估算+0镐约33次、+1约27、+2约23、+3约20次敲击。第三次敲击才正式激活，初次技能冷却1；后续基础技能冷却15，每敲造成的伤害还使冷却减少 `dmg/10`。

尖塔能追踪隐形英雄。二选一技能是以英雄为中心的菱形区域，或沿尖塔至英雄方向最多7格的直线。低于2/3HP与1/3HP后分别多一波扩散；波次间一tick，新波包含前波格。伤害前会把预警格改为晶体。英雄晶刺伤害6–15并被向远离尖塔方向推；守卫受18–27、Cripple30，并向远离英雄方向推。幽灵和尖塔不吃晶刺。初次预警延迟按英雄当前动作冷却取向上整数并夹到1–3；后续仍要一波一波重新观察。

激活后会招来幽灵及守卫。仍睡眠且路径距离<20的守卫会被麻痹 `20-distance` 争取入场时间；已经醒来而远离的守卫则可能得到加速。尖塔被拆时，只在尖塔FOV范围清除晶体、给守卫其满血值伤害（仍只是使守卫倒地）并让幽灵失明5。

战术推导：先选一段可绕行的开放安全走廊，在第三击前把无机射手威胁和退路看清；优先逐波避开预警。用已观察到的守卫移动把它诱入下一波晶刺，让尖塔替战士打、减速、推走守卫，是源码明确支持的效率手段。不能因“初次技能已躲”就连敲：低血量有累积多波且敲击加速技能。隐身可帮助对付其它怪，但不能关闭尖塔的瞄准。为了这里单独给镐花稀缺升级卷轴，需计算主武器损失与少几次交互的收益，通常不能直接推荐。

来源：[尖塔调度与伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalSpire.java#L83)、[范围生成](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalSpire.java#L224)、[免疫与交互](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalSpire.java#L311)、[唤醒和收尾](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalSpire.java#L379)。

## 豺狼人支线

### 守卫：优先接近或让友军石头砸中

源码事实：距离≤2且双方PROJECTILE射线都互通才能长矛攻击，专门避免像英雄那样弯曲长武器攻击；相邻伤害6–12，非相邻16–22。活工兵连接存在时，`damage()` 把每次伤害整除4，包含落石伤害和DOT伤害入口；毒伤不能绕过关联减伤。工兵死后连接解除。平时巡逻朝关联工兵走。

战术推导：别在距离2反复挥低伤武器；贴身或利用转角使其双向射线失败。能安全击杀工兵时优先工兵，别向带75%减伤守卫倾倒法杖。工兵落石虽被关联减伤，仍能给守卫10回合麻痹，为绕过/杀工兵留窗口；控制价值大于伤害值。

来源：[连接减伤](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGuard.java#L52)、[伤害和射程](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGuard.java#L94)。

### 工兵：先识别投岩来源，不能只看工兵至英雄直线

源码事实：技能冷却4–6，在可见敌人的追猎回合递减，受到伤害又减 `dmg/10f`（字段是int，复合赋值存在整数转换，不要把小伤害视为完全不推进）。可见敌人时不主动贴近，只会相邻近战；看不见目标时也不追出出生点很远。其行动优先级排在守卫之后，正是为了可把守卫引入攻击。

投岩选择来源视野内、能向目标PROJECTILE直达的最近岩块。预警时记录原目标；真正发射用从岩块到该目标的MAGIC_BOLT，可能继续飞到后方碰撞。岩块在发射前被挖掉会取消该枚投岩。投岩伤害6–12、英雄麻痹3、守卫麻痹10、最多击退1格；地卜师不受它伤害。落石与投岩通常二选一，不连续主动落石，目标邻路障/入口时优先投岩，但若无可用岩块仍可落石。落石排除路障/入口周围，工兵范围参数2；命中同样6–12与3/10麻痹，事后部分格生岩块。

战术推导：侧移出**岩块→旧目标**射线，不能退到旧目标后方同线；可用守卫作为真正碰撞目标。若当前相邻岩块恰是已经预警的来源，挖掉能取消投岩，但要同时核对其它技能和麻痹风险，不能把这个用作盲目挖图。路障附近可限制落石覆盖，代价是仍须躲投岩，绝非永久安全点。杀工兵后守卫减伤解除，下一步重看血条和操作窗口。

来源：[技能与追逐](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollSapper.java#L93)、[投岩选源与碰撞](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGeomancer.java#L666)、[落石范围](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGeomancer.java#L785)。

### 地卜师：杀工兵→敲石甲→打本体，三次转移

源码事实：出生有50石甲；前三次镐交互才唤醒，睡眠每次镐伤最多15，避免提前破甲。石甲在时仅镐可伤；与活工兵连接时连镐也不能伤。清掉工兵后还需敲掉剩余石甲，然后才用主武器/法杖打150HP本体。

本体HP按50分段；越过分段会开凿并移动，重新25石甲，不能一次伤害跳过多个阶段，也不能在最终段之前直接死亡。连同唤醒最多转移3次，偏向工兵出生点；附近活工兵可能改为保护首领，原来的守卫随之失去关联甲。移动会开岩壁、释放原壁中的暗金，并产生新岩块，旧安全通路/视线随之失效。死亡清除自身距离6内岩块。

地卜师视距12、能看穿高草和烟幕；躲草/烟不能当可靠遮挡，真正岩墙可遮。它没有普通攻击循环，只放技能；有甲且目标距离>2或有工兵保护时，会额外加快技能冷却。技能基础间隔3–5，随三个HP段最多投1/2/3块岩石，落石范围参数2/4/6。投岩本身来自周围岩块，别只躲首领方向。

战术推导：有明确食物与远程输出优势时，先分拆解决三个工兵，再开首领可免去活工兵无敌屏障；代价是提前挖洞与饥饿，不能把它写成每局必做。首领开图会自动释放暗金，故若计划打首领，不必为了早取40金先挖遍同一区。每次阶段转移先重新观察连接、石甲、岩块与预警，再决定走位/敲击；镐不需要装备，不能把战士换成低阶镐去打裸露本体。低血最终段伤害密度最高，保留移动手段而非站桩抢最后几刀。

来源：[初始石甲](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MineGiantRoom.java#L120)、[无敌与交互](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGeomancer.java#L140)、[分段规则](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGeomancer.java#L275)、[开凿转移与关联](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGeomancer.java#L334)、[技能AI](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGeomancer.java#L569)、[透草/烟FOV](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1329)。

### 豺狼人落石陷阱

固定工兵房有已揭示陷阱。陷阱以路径距离2作约5×5覆盖，在矿洞中排除邻路障区域；造成6–12绕普通护甲伤害，麻痹英雄3、守卫10，地卜师免疫。部分空格变岩块。可在远离覆盖区时投物触发来控制守卫，但必须先核对完整范围与路障，不能只按画面上的一个陷阱格估算。

来源：[房间陷阱](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MineLargeRoom.java#L153)、[陷阱结算](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GnollRockfallTrap.java#L50)。

## 奖励顺序：禁忌符文下的额外升级来源

| 操作 | 好感成本 | 限制/收益 |
| --- | --- | --- |
| 重铸 | 首次500、第二次1500 | 两件同一具体类、已鉴定、无诅咒、可升级；消耗较低等级件，保留较高件并+1；不是两个等级相加，保留好附魔/刻印 |
| 强化附魔/刻印 | 首次500、第二次1500 | 已鉴定、无诅咒、可升级的武器/甲；不是直接提高伤害/防御等级 |
| 升级 | 首次1000、第二次2000 | 目标已鉴定、无诅咒、可升级且当前 `level()<2`；因此只可把低于+2的物品加1 |
| 打造 | 2000 | 在预生成的两种不同近战武器、一种投掷武器、一件甲中选；共享等级概率+0 30%、+1 45%、+2 20%、+3 5%；无诅咒，附魔另定 |
| 换钱 | 剩余好感等额金币 | 失去后续用该好感升级/重铸的可能 |

关键排序推导：若同一件+1主物品既能直接升级也有重铸副本，先1000好感升到+2，再500重铸到+3；反过来重铸到+2后不再符合直接升级条件。3000满收益能做1000升级+500首次重铸+1500第二次重铸，但前提是有合格同类副本；不能预先承诺整套奖励可用。九挑战少升级卷，保留同类法杖/戒指用于重铸尤其值得评估；防具是否值得投资需对照信仰护体的实际收益，而非照普通局无脑堆甲。

镐本体是二阶伤害、三阶力量要求的近战武器；其对无机额外伤害的主动能力是**决斗家能力**，战士不能照搬。镐交互挖首领不使用战士力量加成；免费取回也不代表值得把它当主武器。

来源：[奖励价格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndBlacksmith.java#L79)、[重铸选择与消耗](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndBlacksmith.java#L255)、[资格与升级上限](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndBlacksmith.java#L365)、[预生成奖励](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Blacksmith.java#L363)、[镐职业边界](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/Pickaxe.java#L48)。

## 退出主菜单再进入：本支线没有可依赖的重置收益

源码明确保存水晶守卫 recovering、水晶尖塔 hits/cooldown/剩余波次列表、工兵伙伴/技能冷却/投岩两端、地卜师 hits/冷却/投岩来源/工兵连接/剩余转移点。铁匠好感、购买次数与预生成奖励同样保存；中断打造选择重新交互会恢复该选择。**不能把重进当作消除预警攻击、重抽铁匠奖励、重置首领阶段或让倒地守卫永久消失的策略。** 本页仅核对这些类的持久化字段，未用隔离夹具验证场景重建调度细节；重载的其它普遍机制见独立重载研究页。

来源：[守卫持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalGuardian.java#L267)、[尖塔持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalSpire.java#L510)、[工兵持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollSapper.java#L242)、[地卜师持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollGeomancer.java#L884)、[铁匠任务持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Blacksmith.java#L268)、[中断奖励恢复](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Blacksmith.java#L154)。

## 附录：不可正常生成的菌类占位代码

以下用于覆盖仓库类，不是4.0.2实战计划，不应尝试修改任务类型或存档开启。

| 类 | 已实现代码 | 不能据此推定 |
| --- | --- | --- |
| FungalSpinner | 继承Spinner；HP40、闪避16、命中22、伤10–20、DR0–6、maxLvl=-2；把吐网格改播40强度Regrowth；不施毒；免Regrowth；九宫格高草/犁草首格使伤害×0.7，以后每格再减0.1 | 未完成支线的平衡、减伤极端值及完整生态不能视作经验证机制 |
| FungalSentry | HP200、闪避12、命中50、伤5–10；不可移动、小首领；MAGIC_BOLT可达即能攻击；攻击延长毒6；毒气/毒免疫；等待时见敌必发现 | 类中TODO明说攻击过宽与脱战恢复未定；不是可靠现行首领战 |
| FungalCore | HP300、EXP20、被动、不可移动、首领；死后设beatBoss | 没有完整技能循环，不能从名字补写BOSS机制 |

来源：[菌蛛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/FungalSpinner.java#L33)、[Spinner继承](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Spinner.java#L59)、[菌哨](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/FungalSentry.java#L32)、[菌核](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/FungalCore.java#L27)。
