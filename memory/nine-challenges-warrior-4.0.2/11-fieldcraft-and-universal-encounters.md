# 通用遭遇、视野、陷阱与探索预算

基线：游戏 4.0.2 / code 922，源码 `8405ae82f`；研究日期 2026-10-10。
本章的规则来自源码，策略是基于规则的推导；没有本次实玩证明。`d` 表示深度，
`l` 表示怪物实际设置的等级；敌人面板和当前公开地图优先，不能用下面的公式读取隐藏实例值。

## 1. 门、转角、隐身与出手节奏

- 普通怪物的伏击条件包括英雄隐身、敌人尚未看到英雄，或英雄已不在敌人自己的 FOV 内。
  可伏击时普通 `Mob.defenseSkill` 返回 0；麻痹也会使普通敌人返回 0 防御技能。
  这不是“所有敌人均无条件必中”：子类可重写，Champion、无敌、武器特殊规则也要一并核查。
  [Mob.defenseSkill / surprisedBy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L820)。
- 战士力量不足以使用当前武器时不能伏击；链枷也不能伏击。不能一面用未达力量的高阶武器，一面把过门当必中的保障。
  [Hero.canSurpriseAttack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L744)。
- “过门伏击”的有效条件是实际视线被门/转角/草阻断且敌方行动时刷新了视野，不能仅凭自己走过一道门就宣告成功。
  先读原生敌人状态和当前几何，再预期一次伏击。高草踩成低草、敌人绕到侧面、远程敌人可先射击，都可能使旧计划失效。
- 普通睡眠检测概率是 `1/(距离+目标潜行值)`；游荡检测是 `1/(距离/2+潜行值)`。
  睡眠有醒来耗时，游荡切到追猎的逻辑不同；不要把“看到我必定白送一回合”当统一规则。
  飞行者在相距至少 2 格时还影响睡眠唤醒候选筛选，但这不等于对所有追猎者永久隐身。
  [Mob.Sleeping / Wandering](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1220)。
- 怪物失去视线后仍可能走向最后目标格；猎杀状态并不在英雄隐身瞬间清空目标。
  被更近或可攻击的另一个角色打中时会考虑换目标，因此蜜蜂/分身分担伤害有条件，不能当固定嘲讽。
  [Mob.Hunting](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1359)。
- 开着集群智能时，先把交战点选在**已探索的撤退侧**，通常比向未探索房间追击更可控。
  一格通道可以减少普通近战同时接触，但投射勇士、远程法术、拉拽、穿墙射线和气体都能破坏“一次只打一只”的假设。
  具体召集距离与六种勇士词缀见 [01](01-challenges-and-survival.md)。

战士的现场节奏：远处投掷/法杖消耗或控制 → 在安全转角让敌人接近 → 伏击或持盾近战 →
检查纹章、饥饿、天赋与逃路 → 必要时在还有脱离资源时撤出。纯近战换血是资源决策，不能由职业名字自动决定。

## 2. 全区域可遇到的额外敌人

### 食人鱼及幻影食人鱼

| 对象 | 源码规则 | 九挑战战士对策 |
| --- | --- | --- |
| 食人鱼 Piranha | HP=`10+5d`、闪避技能=`10+2d`、准确=`20+2d`、伤害`d..(4+2d)`，额外DR`0..d`；速度2；0经验；水中寻路；离水或飞行会死亡 | 站干地进行有弹道的远程攻击；只在确有收益时花资源取肉/宝物。不要进水和2倍速鱼互换攻击 |
| 普通鱼的异常抵抗 | 免疫大部分 BlobImmunity 中气体/环境项以及燃烧，但特意不加入 Electricity/Freezing | 毒气/烧鱼不应作为默认计划；冰、电与位移的实际路径更值得检查；自己站水中也可能遭电链 |
| 幻影食人鱼 PhantomPiranha | 非相邻来源或无法识别攻击者的伤害减半；存活时可传到攻击者旁的空水格，否则向别处水域传送；离水会先尝试传走 | 隔水远射未必能留住它。自己周围水格会成为贴脸传送点，必须检查；仅推上岸不是稳定秒杀 |
| 幻影鱼的飞行例外 | `teleportAway()` 在 `flying` 时直接失败，因此离地死亡分支可成立 | 漂浮等手段是否成功仍要满足效果、弹道和免疫条件；不要把“普通鱼推离水”技巧原封套用 |

证据：[Piranha](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Piranha.java#L42)，
[PhantomPiranha.damage / dieOnLand](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/PhantomPiranha.java#L50)。
鱼有单独的伏击判定：睡眠、英雄在自身 FOV 外或隐身，且英雄武器允许伏击。肉的营养/副作用仍受九挑战规则约束，见 01/07。

### 拟态怪家族

| 对象 | 机制与收益 | 接敌方案 |
| --- | --- | --- |
| 普通拟态怪 Mimic | `l=scalingDepth`；HP=`6(1+l)`，闪避`2+l/2`，常态伤害`1+l..2+2l`；伪装状态初击固定高端伤害，目标不隐身时有无限准确 | 用公开检查文字/实体迹象识别；先站可撤退处远程揭露，别把可疑箱子直接当免费拾取 |
| 黄金拟态怪 GoldenMimic | 内部等级改为`round(1.33l)`；奖品可装备物/法杖去诅咒、去武器/护甲诅咒附魔，+0非神器奖品有50%升到+1 | 更强也有较高装备价值；先确认能承受它的实际战斗，再决定是否取资源 |
| 水晶拟态怪 CrystalMimic | 不用普通拟态的伪装额外伤害，改为偷未装备的非unique、等级<1物品；揭露会有1或2回合加速并逃跑；不在英雄FOV且距离≥6时可消失 | 在限制逃路且自己安全的位置揭露、预备控制/爆发；不能无限追进未知区。已知奖品保证无诅咒，但不保证升级 |
| 乌木拟态怪 EbonyMimic | 总是隐蔽；伪装初击是父类伤害的2倍；可伪装在门；合格装备/法杖奖品去诅咒，+0非神器提升到+1 | 高伤伏击对薄甲九挑战尤其危险。带拟态之牙时主动核查门口和可疑交互，不能用“文字没显示敌人”担保安全 |

普通拟态的中立 `interact` 可直接攻击英雄；若英雄隐身/时间暂停则走另一分支，但仍消耗时机。
对它造成伤害会结束伪装，所以远程唤醒绕开的是“开箱触发初击”路径，**不是免除所有敌人行动**。
证据：[Mimic.interact / stopHiding / setLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mimic.java#L155)，
[GoldenMimic](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GoldenMimic.java#L87)，
[CrystalMimic](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalMimic.java#L97)，
[EbonyMimic](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/EbonyMimic.java#L68)。

### 动画石像与披甲石像

- Statue：HP=`15+5d`、防御技能=`4+d`、0经验、初始PASSIVE、INORGANIC。伤害来自其真实武器，
  准确为`(int)((9+d)*武器准确倍率)`，攻击耗时、触及距离、附魔proc也来自武器；DR含`0..(d+武器格挡)`。
  **它拿长柄/盾/快速武器时也有对应特征**，不是固定模板敌人。
- 初始武器无诅咒且有正面附魔；正常死亡掉落并鉴定该武器，存在腐化复制不掉原物的例外。
  受到伤害或负面buff会激活，PASSIVE状态忽略普通 beckon；因此不能假设集群智能能免费把未激活石像带出来。
- ArmoredStatue：HP=`30+10d`，还穿无诅咒有刻印的护甲；其DR额外调用真实 `Armor.DRMin/Max`，
  护甲也会proc、影响闪避，死亡掉护甲。`FaithIsMyArmor` 是护甲函数全局分支，因此它的护甲部分也应按挑战公式算；
  武器格挡和石像基础DR不因此消失。
- 推导：公开说明可获知装备名字，先判断拿到的装备是否真能替换当前路线，再决定是否打。
  无机免疫毒/毒气/流血，适用的法术、控制、落坑和地形手段要分别核查；避免在宽阔区和有长触及/危险附魔的石像站撸。

证据：[Statue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Statue.java#L41)，
[ArmoredStatue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/ArmoredStatue.java#L43)，
[属性统一免疫表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1417)。

### 被折磨的灵魂与蜜蜂

- TormentedSpirit 继承 Wraith 的机制，伤害/命中等级项按1.5倍放大。
  邻接时读祛邪卷轴有专门净化选项；净化奖励无诅咒、正面附魔的武器或护甲，+0物品有50%升一级。
  这是消耗祛邪换装备的可选交易，和击杀普通怨灵不同；祛邪仍可能是解除自己关键装备诅咒的更高优先级。
  [TormentedSpirit.cleanse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/TormentedSpirit.java#L44)，
  [ScrollOfRemoveCurse.doRead](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRemoveCurse.java#L56)。
- Bee：飞行、0经验，`spawn(l)`设HT=`4(2+l)`、防御/准确=`9+l`、伤害`HT/10..HT/4`。
  蜂罐仍在时有独立选敌规则：有人拿罐优先追持罐者；落地罐附近距离≤3的可攻击非中立怪可成为目标；
  没有合格怪时，靠罐的英雄也可能被攻击。击中怪物会令其 `aggro(this)`，但普通AI可以再换目标。
  [Bee.spawn / attackProc / chooseEnemy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Bee.java#L89)。
- 推导：把罐投在敌人侧、自己站在罐的威胁范围外，有机会花一个物品换多次分担/输出；
  不能把中立/敌对蜜蜂当永久友军。对Yog拳头还要检查蜂诱导的位置是否把拳带回保护圈。

## 3. 搜索与陷阱：探索也花食物

战士普通搜索半径1，检查当前可见邻格（先见之明有特殊范围）。主动搜索对**可搜索**的普通隐门/陷阱成功率1，
不是需要无限重试的随机掷骰；`canBeSearched=false` 的陷阱除特定先见之明路径外不会因此显露。
被动发现普通陷阱概率`0.4-d/250`，隐门`0.2-d/100`；到深层不可依赖走路被动找隐门。
主动搜索耗时2，同时额外调整饥饿，普通未锁层总预算约6饥饿单位，诅咒先见护符约12。
[Hero.search / TIME_TO_SEARCH / HUNGER_FOR_SEARCH](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2490)。

| 场景 | 可靠规则 | 做法 |
| --- | --- | --- |
| 可疑隐藏陷阱区 | 英雄/绵羊踩格为hard press；普通非飞行怪为soft press，忽略隐藏陷阱 | 不能靠怪安全走过某格证明该格无暗陷阱；走危险区前有目的地搜一次 |
| 已揭露陷阱 | soft press也触发，飞行通常不踩；投落物品可hard press | 可先站安全处投无关物品引爆，或引普通敌人踩；先读该陷阱的攻击选择规则 |
| 沙漏/时停踏陷阱 | 暂时记录delayed press，效果结束后仍可能触发 | 离开危险位置并预算解除时机，不当永久拆除 |
| 普通陷阱被触发后 | 基类默认disarmedByActivation=true，但子类可设false | 每次重新观察active，不能把触发一次当作通路一定安全 |
| 深渊 | 非飞行mob走入时`mobFall → die(Chasm)`；英雄坠落则有直接伤害、流血、残废 | 击退秒普通敌人可以省升级与血，但掉落可能随坑，飞行/不可推动/首领例外逐个核对；自己无羽落跳坑要计算持续流血 |

证据：[Level.occupyCell / pressCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1163)，
[Trap.trigger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/Trap.java#L106)，
[Chasm.heroLand / mobFall](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Chasm.java#L150)。

特别有价值但也危险的“借陷阱杀敌”：

- 死神、解离、飞镖类陷阱先取本格角色，没有时从**陷阱为发射源**寻找可 `PROJECTILE` 命中的近角色。
  并不是攻击“扔东西的人”或“离英雄最近的怪”。基础范围至少6.5（`max(6,viewDistance)+0.5`），
  黑暗中英雄看不到的敌人可能仍落在陷阱候选范围；未知候选意味着不能保证替你打目标。
- 解离伤害`30..50 + scalingDepth`，还会炸毁陷阱格堆物；死神伤害`round(HT/2+HP/2)`，
  对英雄有`0.9HT`上限，对带相应抵抗/免疫的敌人也不是一律秒杀。
- 可执行条件：已知自己不在危险选敌线或有更可靠的本格敌人目标；核全部公开角色和路径；
  投掷物能到陷阱；知道范围效果不会封住逃路。任何一步不成立，就不把它当确定无伤技巧。

证据：[GrimTrap.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GrimTrap.java#L53)，
[DisintegrationTrap.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/DisintegrationTrap.java#L53)，
[WornDartTrap.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/WornDartTrap.java#L53)。
毒、燃烧、寒冷、落石、警报、召唤、诅咒、解除装备、传送等陷阱不共享一个结果模型；遭遇时读当前原生说明和具体类。
本章已审查统一踩踏/搜索和上述攻击型陷阱，不声称穷尽所有34种陷阱的全部组合。

## 4. 环境、负面效果与知识边界

- 非飞行角色进入**实际水地形**时会调用燃烧/淤泥buff的 `act()` 来清理；实现特意不直接detach，
  是为了处理尚未结算过伤害的情况。因此“下一步进水”不能保证没有最后一次伤害。
- 蛛网在普通飞行判断之前处理；飞行不自动代表对蛛网免疫。很多危害应按自己的免疫集合而非视觉外观判断。
- 毒（Poison附着）、毒气（ToxicGas地块）、腐蚀（Corrosion）、淤泥（Ooze）、流血（Bleeding）不是同一种负面；
  全面净化合剂Cleansing/净化药剂Purity/抗性药水、进水各有不同作用，见07。
- 高草可以阻挡视野并被踩/烧毁，地面燃烧、冰、电、气体有持续传播/结算时机。
  地图已经探明只证明地形知识，不证明当前没有不可见敌人，也不证明同一地点的气体仍和旧帧相同。
- `INORGANIC`统一免疫 Bleeding/ToxicGas/Poison；FIERY/ICY/ACIDIC/ELECTRIC有不同抗性与免疫。
  UNDEAD与DEMONIC标签本身在统一表没有“免疫全部负面”的定义。首领免疫盟友转化和Dread，
  部分强力杀伤列为抵抗而非绝对免疫。始终联合子类的免疫集合核查。

来源：[Level.occupyCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1163)，
[Char.Property](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1417)。

## 5. 不把刷怪/回头路当免费资源

- 常规层有怪物数量上限；补充生成通常要求数量低于上限，并在到英雄寻路距离至少12处寻找合法出生格。
  生成失败可一回合后重试。普通/黑暗feeling/取得护符后、以及日晷饰物的刷新间隔不同。
  [Level.mobCount / respawnCooldown / spawnMob](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L718)，
  [MobSpawner.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/MobSpawner.java#L37)。
- 敌人经验通常只在英雄等级≤其maxLvl时给予；普通掉落在英雄等级>maxLvl+2时被拦截。
  [Mob.destroy / rollToDropLoot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L989)。
  特殊奖励、任务、财富之戒和登顶另有路径，不能由这个普通规则推出“一切收益完全为零”。
- 九挑战中刷怪额外付出饥饿、火把、暴露于精英强敌和集群智能的风险，掉落治疗瓶也不能直接治病。
  没有已证明的充足食物/防伤/产出循环时，不为纹章冷却或刷某掉落无限等待。
- 路线建议：按“主线必需 → 安全补给 → 高收益任务 → 可选风险房”的顺序预算；有明确目的才回头。
  地图上记录未解决房型、钥匙、商店、炼金锅、治疗井和退路；一旦得到对应工具再评估回收是否划算。
  主线通关并不要求每个敌人/支线都清完；未来用户若要求全探索，应另算资源成本。

## 6. 每层/每次重要遭遇的实战记录模板

1. **当前事实**：当前公开帧身份、层数、坐标、HP/盾/冷却、饥饿、力量、装备、光照、未知物品、可用控制与位移。
2. **本层目标**：主楼梯/钥匙/力量与升级保底/必要任务/补给；不确定房间和高价值回收项。
3. **威胁**：该区敌人+可见勇士词缀；有无第二条远程线、召唤、拉拽、坑、水、电和已公开警告。
4. **战术与预算**：站哪里接战；先用何物；几次攻击内不能解决就改计划；最坏代价与逃路。
5. **执行**：只做一个当前广告允许的动作；同步回复即新观察；异步按skill保留原请求并settle。
6. **重评条件**：新敌人/移位/光照将尽/盾触发或耗尽/饥饿开始/阶段改变/随机效果不合预期。
7. **复盘**：实际投入和结果；确认的失误与仅理论可优化分开。战术结果沉淀进本目录时注明真实公开证据。

本章不替代 [项目实战skill](../../skills/pixel-dungeon-run-assistance/SKILL.md) 的单连接、完整观察和异常恢复规则。
