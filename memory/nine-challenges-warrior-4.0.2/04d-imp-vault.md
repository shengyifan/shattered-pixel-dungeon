# 都城小恶魔金库：装备暂存、一次性恢复机会与可选首领

核对基线 `8405ae82f`，上游 4.0.2 / code 922，2026-10-10。仅源码研究，未操作游戏/个人存档/私有审计，未做金库实战。这里是 4.0.0 之后的小恶魔任务；旧版“杀武僧/魔像拿代币换戒指”保留在兼容分支，不能当新局任务攻略。

**九挑战战士应把金库当独立的装备与潜行关卡评估。** 原有主武器、护甲、印章、戒指、神器、法杖、消耗品、金币和能量暂时收走；入场不是拿当前强构筑碾过去。可以中途通过逃脱水晶结算，有原生恢复和部分奖励价值；无需为了主线通关强求击败 600 HP 可选首领。是否进入应看入场时主线损耗、此局公开奖励、本人可安全探索的空间与退出收益。

## 入场、退出与真正可利用的限制边界

`CityLevel.activateTransition()` 先检查已接任务、未完成、非旧任务、非升华返程/失落背包。确认入场后：

1. `hero.live()` 移除非 `revivePersists` Buff，重新建立自然恢复与饥饿；随后 `HP=HT`。
2. `EscapeCrystal.storeHeroBelongings()` 把装备/背包/快捷栏/金币/能量放入原生临时 bundle，清空当前装备与金币/能量；发逃脱水晶和已鉴定布甲。
3. 更新 HT 后进入支线。英雄等级、基础力量、已选天赋/子职业并未在该路径重建为一级，但依赖原装备的能力自然暂时缺失。

[CityLevel 入场](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CityLevel.java#L140)、[EscapeCrystal 暂存](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/EscapeCrystal.java#L300)、[Hero.live](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L452)

退出 `leaveVault()` 再 `hero.live(); HP=HT;`，恢复原装备/金币/能量、加入一件允许携带的战利品，再更新 HT、完成任务并返回都城。**这两处直接设 HP，没有药剂恐惧判定**，所以九挑战也有一次原生恢复价值；不能把“药剂不回血”误写为整个游戏所有恢复都失效。但 HP 赋值在装备换回/HT重算之前，有力量之戒等改最大 HP 的装备时，最终是否真正满原上限要重新观察。退出完成任务后不能再次进入，不能无限刷恢复。最初进度分 <50 时水晶只提示、不会放行，也不是无风险原地进出按钮。[退出](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/EscapeCrystal.java#L143)、[leaveVault](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/EscapeCrystal.java#L257)

金库初始铺 4 件 T0 装备、飞镖、5 件 T0 消耗品、3 份随机食物；黑暗另铺 2 火把；入口邻接房附近额外治疗药水。金库仍用实际药剂/种子/食物类：这里没有看见解除九挑战的总开关，**治疗药水仍必须按药剂恐惧处理，地上种子不恢复播种效果，食物不凭支线身份变回全额**。初始疗药保底并不等于九挑战回血保底。此地 `addRespawner()` 返回 null，没有普通周期刷新；固定敌人仍可巡逻和被惊动。[VaultLevel.build](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/VaultLevel.java#L130)、[createItems/respawner](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/VaultLevel.java#L597)、[PotionOfHealing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfHealing.java#L45)

金库封锁仅设 `locked=true`，特意不施加 `LockedFloor`，注释说明允许自然恢复且可离开。`Hunger.act()` 在 level.locked 时不增加饥饿；这可能给可选首领阶段的安全恢复/充能创造窗口，但场上的攻击不会为此暂停，必须先证明当前位置确实安全，不能把锁门后持续休息当通用解法。[VaultLevel.seal](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/VaultLevel.java#L644)、[Hunger.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L63)

战士镜子给一枚 +1、带随机刻印的破损纹章；要找到并领取，不能当进场已有。它的 unique 身份不能直接带出，退出若带镶有印章的护甲，会拆掉支线印章、保留刻印；原有印章随旧装备恢复，不能凭此复制第二枚永久印章。[VaultMirror.createReward](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/VaultMirror.java#L69)、[退出拆印章](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/EscapeCrystal.java#L263)

## 敌人不是主线原样复制

所有下列金库普通怪启用 `activateSteathGameplayBehaviour()`；初始普通巡逻/睡眠/调查 AI 改为适合潜行的版本，狩猎能力仍保留原怪特性。其 EXP 为 0，maxLvl=30，通常必掉代币；不能靠清金库小怪升级。金库 `createMob()` 直接构造，不走父类的冠军抽签；房间直接构造的专属怪亦如此。但激怒后的普通 Mob 共用行为仍需考虑当前挑战，不能认为金库是暂停挑战模式。[VaultLevel.createMob](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/VaultLevel.java#L528)、[Stealth AI](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1540)

| 金库敌人 | HP；近战；命中/闪避；DR | 与主线有关的变化和对策 |
| --- | --- | --- |
| VaultRat | 8；1–4；24/18；0–1 | HP/伤害虽低，命中/闪避已提高；低配近战仍不能以为必躲开 |
| VaultSkeleton | 25；2–10；24/20；4–9 | 继承死亡骨爆，薄甲且没有原装备时仍应远处收尾；DR 比监狱版高4 |
| VaultDM100 | 20；2–8；25/18；4–8 | 继承电击3–10，命中明显提高；转角贴近再打，别在射线上反复捡物品 |
| VaultShaman | 35；**5–25**；25/18；5–11 | 近战特意加强到暴徒水平，不能照主线萨满的5–10预算；法术6–15，内部类型40%虚弱/40%易伤/20%邪术；仅以公开效果判断类型 |
| VaultDM200 | 80；10–25；28/15；0–8 | 命中提高，保留无机、大型和卡路喷毒气 |
| VaultGhoul | 45；16–22；24/20；0–4 | 不生第二只伙伴，但附近食尸鬼仍能互救；距离<4时会被附近调查/狩猎食尸鬼吸引 |
| VaultElemental.Fire/Frost/Shock | 60；20–25；25/20；0–5 | 火/冰/电效果与相克照普通元素；40/40/20，没有混沌替代抽签 |
| VaultGolem | 120；25–30；28/15；0–12 | 潜行巡逻 AI 替掉闲逛自传送，**拉英雄到身边仍在**；不能卡门无脑射 |

来源：[Rat](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultRat.java#L33)、[Skeleton](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultSkeleton.java#L30)、[DM100](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultDM100.java#L29)、[Shaman](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultShaman.java#L36)、[DM200](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultDM200.java#L30)、[Ghoul](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultGhoul.java#L31)、[Elemental](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultElemental.java#L28)、[Golem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultGolem.java#L30)。父类详解见 [中段怪物](04-enemies-caves-city.md) 与早期怪物章节。

潜行应以可见移动、朝向/公开阴影和 `investigating` 提示逐步决策：巡逻按预设路点；刚发现时先调查，第一调查回合的发现概率减0.33；移动后的正前方75°、距离≤6更容易发现，背侧概率低到<10%时取0；睡眠发现也有平方衰减。这里是条件概率而非“背后永久看不见”，停在原地的怪会重置朝向计算。全局黑暗下公开 movement_shadow 是合法线索，但不授权从未知阴影重建未公开地图或确切敌种。[潜行检测](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1573)、[movementShadow](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L729)

## 扫描哨、激光、火焰装置

| 原生装置 | 核实行为 | 可用结论 |
| --- | --- | --- |
| VaultSentry 扫描哨 | 固定扇形/轮换方向，以 `STOP_SOLID|STOP_TARGET` 扫描；命中非隐形 ALLY 6–12电伤，连续上次受击目标为3–6；全免疫/无敌；部分模式不给红色预告 | 隐身是该扫描伤害分支的明确排除条件；普通走位等空档通过，不能打坏或控停；没有红标不证明不会扫描 |
| VaultLaser 激光 | 固定循环 `STOP_SOLID` 长射线，烧毁可燃物；伤ALLY角色10–20 DeathGaze，不检查隐身；全免疫/无敌 | 隐身不能穿激光；角色不能当挡光盾；真墙截断，但被毁草木会改变下一次几何 |
| VaultFlameTraps 火陷阱 | 按格冷却反复触发，有原生blob警告；施加4 Burning，烧物品、枯植物 | 看完整周期与落脚点；飞行不在这条免火检查里，不默认悬浮免疫；避免把收集品放在喷火格 |

[扫描哨](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/VaultSentry.java#L82)、[激光](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/VaultLaser.java#L76)、[火陷阱](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/VaultFlameTraps.java#L54)。表中 ALLY 包括英雄阵营；这几类风险不能统一叫“敌方会主动瞄准我的攻击”，它们按装置周期运作。

## 何时提前结算，何时考虑最终奖励

10枚代币能开最终门，代码会 `detachAll` 该代币堆，不只扣10。水晶按以下任务分决定可带出物品：

| 最终分 | 携出资格 |
| --- | --- |
| <50 | 水晶只提示，需要先取得少量进度 |
| 50–450 | 可以离开，不带奖励 |
| 500–1000 | 一件非unique消耗品，不能装备/法杖 |
| 1050–2000 | 一件最高+0的非unique物品（法杖可选；特殊印章甲/法师杖按分支处理） |
| 2050–3950 | 一件最高+1；并获得未来小恶魔商店资格 |
| 4000 | 一件无等级上限的非unique物品；拿到雕像固定4000 |

消耗品整堆携出会降为1个；恢复原装备不算在这唯一奖励里。[TokenDoor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/VaultTokenDoor.java#L63)、[物品选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/EscapeCrystal.java#L164)、[earnedShop](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Imp.java#L397)

未拿雕像时：探索最多1000（看过80%地格即满探索项）；未开门每代币100、最多1000，开门后这一项1250；首领项至多750；最后向下取整到50。**源码 127 行在首领仍活着时实际计算 `750 * (HP/HT)`，不是注释所称已造成伤害比例；首领消失时加750。** 这意味着该基线按字面是“剩血越多，该项越高”。这是已核代码、未做运行实验的实现差异，不能静默改成符合直觉的公式，也不据此许诺特定当前退出分；以公开退出文本及实际可选奖励为准。[分数计算](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/EscapeCrystal.java#L92)

任务 `score` 决定奖励/商店，与 `Statistics.questScores[3]` 中机关伤害扣分分开；机关先用 hazardFreebies，再扣评分，不直接降低这里重新计算的探索/代币/首领任务分。不要为了保高排行榜分牺牲通关概率，也不要误以为踩一激光就永远没有商店。

最终奖励候选预生成：神器(+5成长)或替代戒指、+2–4戒指、一件近战与一件投掷武器（T5为+2–4，T4为+3–5）、+2–4刻印板甲、+2–4满充能法杖，去诅咒。它们对升级稀缺挑战价值很高，但必须取得并满足携出资格，只能选一件；薄甲挑战下高甲的面板防御不能按普通局估值。[Imp.Quest.spawn 奖励](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Imp.java#L318)

安全收益判断：只要能低损耗探索到合法退出，入/出恢复、部分物品和未来商店都可能比强打首领划算；但是装备全部暂存、开局布甲且九挑战消耗品受限，使支线本身存在死亡成本。入场前把“获得多少分/看见怎样奖励就离开”写成目标，场内逐步调整；不要预先承诺清所有房间或为随机高阶武器刷完600 HP。

## 可选三相元素首领：覆盖边界

下节按本基线源码概括状态与弱点；精确逐格房间解法、全部环境技能的运行时周期尚未做实战/隔离夹具验证。遇见此首领前仍须根据当帧公开预警、位置、三类装备与机动消耗品做独立计划。

首领初始 **600 HP**、命中28、闪避20、DR0–10；火/电近战20–25，冰15–20。**按 `HT/5=120` 血档变形，不是每100血**；存活时大额跨档还有钳位。初形随机，火/冰/电权重初始各2、用过减1，不连续同形；`UNSTABLE` 仅保留枚举，当前未使用。源码未见本首领/最终房/金库层的 `STRONGER_BOSSES` 分支，不能自动套DM-300或矮人王强化；其他全局挑战仍有效。[初始属性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L85)、[变形阈值](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L416)

| 形态 | 原生攻防弱点 | 战士准备 |
| --- | --- | --- |
| 火 | 不相邻投掷攻击对其防御设0；英雄投掷伤害+10。`AntiMagic.RESISTS` 所列来源伤害÷4，法杖/牧师法术还加速它两种技能。FIERY；Frost/Chill不附着而造成5–10相克伤害 | 留真实可用的投掷武器和取回路线；不要照普通火元素用法杖大赚相克，首领相克仅5–10且魔法抗性更复杂 |
| 冰 | 英雄投掷伤害÷4且弹药滞留；英雄非投掷伤害+10，近战武器对其闪避减半。ICY；Burning转为5–10 | 近战是设计弱点，但仍要躲冰锥/冰涡；避免把所有投掷弹药卡在此形态 |
| 电 | 指定魔法来源+10，法杖/法术令英雄全部法杖各获0.2充能；非投掷角色伤害÷4，英雄非投掷攻击另反电5–10。ELECTRIC，但专门取消对雷霆法杖的电抗 | 留法杖电量；即使战士主近战也需切手段，薄甲硬敲反电极亏 |

[各形态伤害分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L342)、[属性与相克](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L464)。这里“指定魔法来源”有精确类表，不泛化为所有非物理损伤。

每形态都有一类预告特殊攻击和一类环境技能：

- **火球/火墙**：火球预判英雄移动方向的下一格，否则随机邻格，警示3×3非实心格，落地生火点燃；火墙横扫两格厚条带，留缺口，不能只躲火球不看火墙。[火攻击](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L636)
- **冰锥/冰涡**：冰锥范围10、50°、STOP_SOLID，初警近2格后每tick外扩2格，10–15且冻结5；冰涡锁建立时英雄格，斜向收缩再扩散，半血前两方向、半血后四方向，这条分支冻5但无直接伤害。顺着锥方向一直退可能继续吃扩张波，需检查当前原生 `vault_frost_*` 环境提示。[冰锥](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L896)、[冰涡](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L1058)
- **电束/追踪电**：电束是STOP_SOLID完整射线加目标点交错四邻格，20–30且麻痹1；追踪电半血前2个、后4个，有限转向，每tick约前进2格，10–15且麻痹1，碰实心停。不能看到一条直线就只横挪一格。电形两种技能冷却乘0.67。[电束](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L1247)、[追踪电](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L1326)

变形会取消当前待发特殊攻击，但特殊冷却设0、环境冷却减半，不是白送长安全窗。伤害本身也会缩短冷却。距最终宝门4格有最后警告，≤3触发封门生成；首次延迟1且预计算视野、避免偷开场伏击。房间会检查几类+2装备与是否领镜子，只是准备度提示，不保证能打赢；九挑战时仍须自己确认三类攻击手段与退出选项。[变形重置](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L180)、[最终房触发](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/vault/VaultFinalRoom.java#L266)

重载边界：当前形态、冷却、目标格会保存，但 `formChances` 没在该类当前持久化字段中；这只是静态发现的潜在非等价点，**没有证据保证重进给有利形态**。不将读档当必然取消预警、复位血量或免费换形，若未来要研究需单独隔离夹具且保留原生源码。[持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/quest/vault/VaultBossElemental.java#L525)
