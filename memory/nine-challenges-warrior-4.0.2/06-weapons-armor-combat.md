# 06 战士九挑战：武器、投掷物、护甲与命中结算

核对日期：2026-10-10；源码基线 `8405ae82f206e965d14b4a3b9d4fb282f2230e47`，游戏 4.0.2 / code 922，CLI.9.0.3。本篇是静态源码研究，不代表已完成九挑战实战或验证当前背包。只读仓库源码；没有启动游戏、读取个人存档或私有审计。附魔、刻印和两类诅咒的完整清单见 [06b](06b-enchantments-glyphs.md)。

## 1. 首先改变装备评价标准

九挑战中，「信念护体」直接削弱的是护甲 DR 公式，不是所有减伤。圆盾、巨型方盾、铁头棍、武士刀的**武器防御仍然独立掷骰**；长鞭/长矛/关刀的距离优势、门口伏击、投掷物软控和法杖控制往往比护甲面板更能减少生命损失。但武器盾只能抵挡走 `drRoll` 的攻击，不能自动挡毒、火、饥饿或各类绕甲魔法。

以决策目标排序：先保证强度足够、能脱离接触，再看一次杀伤阈值/控制，最后才是无敌人 DR 时的平均每回合伤害。低力量重武器会同时丢失伏击、降低命中、延长攻击时间；低力量护甲会降低移动速度、闪避和实际 DR。在强敌、蜂拥、禁药叠加时，这些代价经常相互放大。

以上为由以下公式推导出的战术判断，不是固定掉落下的唯一最优解。终局武器应由实际获得的等级、附魔、法杖/戒指、子职业和剩余升级卷共同决定；不要为了等待某把“五阶毕业武器”而在前期持续失血。

## 2. 统一读表规则与战斗公式

- `t` = 装备阶数；`l` = 参与该伤害/DR 方法的有效等级（通常 `buffedLvl()`）；`u` = `level()` 所给等级。表中伤害未加多余力量、戒指、附魔、狂怒、易伤，也未减敌人 DR。
- 普通近战最小伤害 `t+l`，最大 `5(t+1)+(t+1)l`。普通投掷最小 `2t+l`，最大 `5t+tl`。伤害不是端点间均匀随机：默认 `Random.NormalIntRange`，中间值更常见；十三叶草饰品可替换英雄伤害分布。
- 普通近战/护甲力量要求 `8+2t−floor((sqrt(8 max(u,0)+1)−1)/2)`；投掷再减 1。升级在 `+1/+3/+6/+10/+15…` 降力量各 1；掌握药剂再减 2。巨斧、镐、镖另见表。诅咒注能对 `level()` 增加 `1+原等级/6`（Java 整数除法），不是无代价白送等级。
- 超额力量 `e=STR−STRReq>0` 时，近战和普通投掷另加 `NormalIntRange(0,e)`；在增幅后的武器伤害上相加。力量不足 `e<0` 不直接扣武器伤害范围，但命中乘 `1/1.5^(−e)`、攻击耗时乘 `1.2^(−e)`，并失去英雄伏击资格。
- 表中 `A` 是武器命中倍率、`T` 是基础攻击耗时、`R` 是近战距离；未写即 `A=1,T=1,R=1`。实际耗时还乘增幅与力量惩罚，再除狂怒之戒等攻击速度倍率。移动速度不等于攻击速度。
- 英雄起始基础命中 10、闪避 5，每升一级各加 1。普通命中比较两个独立均匀随机数 `U(0,A_eff)` 和 `U(0,D_eff)`；没有其他特例时，若 `A_eff≥D_eff`，命中率 `1−D_eff/(2A_eff)`，否则 `A_eff/(2D_eff)`。因此“命中 +28%”不是“命中概率 +28 个百分点”。祝福 ×1.25、虚弱诅咒 Hex ×0.8、Daze ×0.5 等在抽样后乘相应骰值。无限闪避优先于无限命中。

源码：[基础近战](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/MeleeWeapon.java#L227)、[武器力量/速度](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L292)、[英雄伤害与 DR](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L643)、[命中](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L619)、[初始属性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L219)、[升级属性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2075)、[随机分布](../../SPD-classes/src/main/java/com/watabou/utils/Random.java#L149)。命中概率是对这些源码抽样规则的数学推导。

### 伏击、长武器和结算次序

`Mob.surprisedBy` 要求攻击者是英雄，且英雄隐形、敌人尚未察觉英雄，或英雄不在该敌人的 FOV；攻击时还须满足 `Hero.canSurpriseAttack()`。**链枷永远不能伏击，力量不足的武器也不能伏击**。普通敌人在伏击/麻痹时闪避返回 0；特定敌人的覆写、无敌、招架仍须看敌人条目。除了明确有伏击伤害特性的匕首系、飞刀、苦无，普通武器的伏击主要保证命中，不通用翻倍。

长武器判定不是法杖弹道：先检查地图距离不超过 `R`，再在非实心格上做有距离上限的寻路，其他角色格阻断通路。它可以在符合这套规则的转角命中；不要拿 `Ballistica` 单线结果替代。紧邻敌人一律先被 `Hero.canAttack` 允许，再由攻击本身判定无敌/命中等。当前公开动作始终优先于手工猜测。

一般攻击：命中 → 掷敌人 DR → 掷伤害与狂怒/弱化等倍率 → 敌人 `defenseProc`（包含护甲刻印、狂战士怒气积累等）→ 减 DR 并截到 0 → 粘稠延迟 → 易伤 ×1.33 → 攻击者 `attackProc`（天赋、附魔等）→ `Char.damage` 的抗性/护盾/生命处理。附魔不一定等同于普通物理伤害，多种效果在此链条不同节点发生。狂战士挨打积怒发生在扣 DR 前，因此武器盾能保命并积怒；但“可以积怒”不意味着九挑战值得主动吃攻击。

源码：[伏击](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L907)、[战士武器限制](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L744)、[长武器寻路](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/KindOfWeapon.java#L264)、[攻击结算](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L370)、[防御过程](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1586)。

### 增幅与换装

武器速度增幅：伤害 ×0.7 后四舍五入，耗时 ×2/3；伤害增幅：伤害 ×1.5，耗时 ×5/3。低伤快打会被每击敌人 DR 多次削减；慢攻可能让敌人连动，特别在失去距离/控制时。攻击速度、每击附魔概率是否校正、连击需求要一起看，不能只按“速度增幅每回合倍率更高”下结论。

正常装备空槽消耗 1 时间；**替换已有武器/护甲会先卸旧再穿新，分别各花 1，通常共 2 时间**。战士不能默认拥有决斗家的 Swift Equip。脱卸被诅咒绑定的装备会被拒绝（魔法免疫是代码中的例外）；发现诅咒后再寻找解除手段，不能把探装当无成本鉴定。

源码：[增幅](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L98)、[卸装耗时](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/EquipableItem.java#L120)、[换武器](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/KindOfWeapon.java#L106)、[换护甲](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L230)。

## 3. 全部近战武器：战士能获得的是基本属性，不是决斗家技能

每行伤害写为 `最小..最大`。表中未写的 `A/T/R` 均为 1。加防武器的 `D` 表示一次 `NormalIntRange(0,D)`，不是固定减伤；其力量不足时还会扣 `2×不足力量` 再与 0 比较。武器附魔同样可以触发，见 06b。

**武器描述中的突刺、顺劈、旋风、招架、蓄力射击、潜行、剑舞等 `duelistAbility` 不会因为战士持有该武器就解锁。** `MeleeWeapon.actions` 只给装备了武器的决斗家提供 ABILITY。下表策略只利用战士真实可用的被动属性；不要误把普通镐当作战士对无机物/甲壳生物额外伤害，也不要误把十字弩当作战士可主动蓄力的武器。

源码：[职业检查](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/MeleeWeapon.java#L73)。

| 阶/基础力量 | 武器与源文件 | 伤害 | 基本特性、九挑战判断 |
|---|---|---|---|
| 1/10 | [破旧的短剑 WornShortsword](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/WornShortsword.java#L30) | `1+l..10+2l` | 战士初始武器；普通伏击能提高安全性。要比较当前升级与新武器力量门槛再替换。 |
| 1/10 | [匕首 Dagger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Dagger.java#L43) | `1+l..8+2l` | 伏击将下限提高至 `min+round(.75(max−min))`；适合重复造伏击，正面输出较低。 |
| 1/10 | [镶钉手套 Gloves](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Gloves.java#L29) | `1+l..5+l` | `T=.5`；每击 DR 吃亏；多余力量和命中后效果的价值较高。 |
| 1/10 | [刺剑 Rapier](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Rapier.java#L42) | `1+l..8+2l` | `D=1`；小幅减伤，不能使用决斗家突刺。 |
| 1/10 | [权杖 Cudgel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Cudgel.java#L29) | `1+l..8+2l` | `A=1.40`；正面可靠性较高；伏击已必中时此优势无收益。 |
| 1/10 | [法师魔杖 MagesStaff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/MagesStaff.java#L72) | `1+l..6+2l` | 法师专属初始物，普通掉落权重 0；正常战士路线不把它列为可获得构筑核心。充能/灌注与战斗法师效果不等于战士能力。 |
| 2/12 | [短剑 Shortsword](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Shortsword.java#L30) | `2+l..15+3l` | 无额外被动，均衡。 |
| 2/12 | [手斧 HandAxe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/HandAxe.java#L29) | `2+l..12+3l` | `A=1.32`；代价是上限低于短剑。 |
| 2/12 | [长矛 Spear](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Spear.java#L40) | `2+l..20+4l` | `T=1.5,R=2`；站位可先打、隔障碍打；敌人接近后慢攻危险，速度增幅可使基础耗时回到 1。 |
| 2/12 | [铁头棍 Quarterstaff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Quarterstaff.java#L33) | `2+l..12+3l` | `D=2`，信念护体不削这部分。战士没有决斗家三倍闪避姿态。 |
| 2/12 | [长匕首 Dirk](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Dirk.java#L31) | `2+l..12+3l` | 伏击下限 `min+round(.67(max−min))`；比硬拼更依赖视线控制。 |
| 2/12 | [短柄镰 Sickle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Sickle.java#L39) | `2+l..20+3l` | `A=.68`；仍能伏击，善用伏击抵消低命中；正面打高闪避敌人不稳。 |
| 2/14 | [镐 Pickaxe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/Pickaxe.java#L48) | `2+l..15+3l` | 比同阶多需 2 力；矿洞任务工具，基础攻击没有破甲/虫类特攻；普通掉落权重 0。 |
| 3/14 | [单手剑 Sword](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Sword.java#L40) | `3+l..20+4l` | 均衡；没有战士可用的顺劈主动。 |
| 3/14 | [硬头锤 Mace](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Mace.java#L40) | `3+l..16+4l` | `A=1.28`；正面稳定，伏击机会多则命中增益相对贬值。 |
| 3/14 | [弯刀 Scimitar](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Scimitar.java#L32) | `3+l..16+4l` | `T=.8`；能灵活插入行动，是否少挨打取决于实际行动条，不能承诺每击白打。 |
| 3/14 | [圆盾 RoundShield](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/RoundShield.java#L36) | `3+l..12+2l` | `D=4+l`；九挑战可作物理生存支点，需法杖/附魔弥补杀敌速度。 |
| 3/14 | [双钗 Sai](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Sai.java#L42) | `3+l..10+2l` | `T=.5`；适合角斗士积连击/命中效果，强甲敌人逐击抵消多；决斗家连击突刺非战士功能。 |
| 3/14 | [长鞭 Whip](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Whip.java#L37) | `3+l..15+3l` | `R=3,T=1`；九挑战可贵的距离控制，被包围/远程对射时不自动安全。 |
| 4/16 | [长剑 Longsword](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Longsword.java#L30) | `4+l..25+5l` | 均衡伤害，力量可用时是稳妥选项。 |
| 4/16 | [战斧 BattleAxe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/BattleAxe.java#L29) | `4+l..20+5l` | `A=1.24`；正面高闪避目标更可靠。 |
| 4/16 | [链枷 Flail](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Flail.java#L39) | `4+l..35+8l` | `A=.8`，**不能伏击**；九挑战损失常用保命手段，不因高面板无条件选。战士没有旋转必中主动。 |
| 4/16 | [符文之刃 RunicBlade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/RunicBlade.java#L39) | `4+l..20+6l` | 高升级成长，但 `+5` 才追平长剑的伤害端点；禁忌符文下别只凭“成长高”重投。 |
| 4/16 | [暗杀之刃 AssassinsBlade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/AssassinsBlade.java#L31) | `4+l..20+5l` | 伏击下限 `min+round(.5(max−min))`；与可控隐形/遮线协同，不赋予刺客子职业处决。 |
| 4/16 | [十字弩 Crossbow](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Crossbow.java#L37) | `4+l..20+4l` | 装备后普通镖/涂药镖伤害改 `4+l..12+3l`（再叠神射之戒），且继承弩附魔；详见第 5 节。 |
| 4/16 | [武士刀 Katana](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Katana.java#L30) | `4+l..20+5l` | `D=3`；比盾更偏输出的少量物理减伤。 |
| 5/18 | [巨剑 Greatsword](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Greatsword.java#L30) | `5+l..30+6l` | 均衡终局武器；按能否及时满足力量决定投入。 |
| 5/18 | [战锤 WarHammer](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/WarHammer.java#L29) | `5+l..24+6l` | `A=1.20`；不要把“重锤”理解为基础破甲。 |
| 5/18 | [关刀 Glaive](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Glaive.java#L29) | `5+l..40+8l` | `T=1.5,R=2`；速度增幅基础耗时 1，距离控制和伤害兼得，仍受力量/路径/敌人远程威胁约束。 |
| 5/20 | [巨斧 Greataxe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Greataxe.java#L37) | `5+l..45+6l` | 额外力量门槛 20；少升级条件下可能很晚才能安全使用。战士没有决斗家低血大招。 |
| 5/18 | [巨型方盾 Greatshield](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Greatshield.java#L29) | `5+l..18+4l` | `D=6+2l`，信念护体不削；注意源码注释写“+3”是旧注释，真实 `lvl*(tier−1)=4l`。 |
| 5/18 | [魔岩拳套 Gauntlet](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/Gauntlet.java#L29) | `5+l..15+3l` | `T=.5`；角斗士/附魔机会多，但高 DR 会削弱。不能把视觉快当做敌人永不出手。 |
| 5/18 | [战镰 WarScythe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/WarScythe.java#L29) | `5+l..40+6l` | `A=.8`，**仍可伏击**；相对链枷可用伏击消除命中缺陷，成长较低。 |

掉落边界：普通 `Generator.randomWeapon/randomArmor/randomMissile` 所用区域阶数权重按数组依次为 `0/75/20/4/1`、`0/25/50/20/5`、`0/0/40/50/10`、`0/0/20/40/40`、`0/0/0/20/80`，列对应 1–5 阶；这是该生成入口的条件权重，不是每层必出某阶，也不覆盖所有任务/宝箱/特殊房间。不要把表中每把武器都当作一局必然获得。来源：[生成表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L418)、[区域权重](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L613)。

## 4. 全部普通投掷武器：有限资源，但不是一次性垃圾

投掷不需先装备，战士同样能用。默认临近目标命中 ×0.5，非临近目标 ×1.5，再乘力量与武器倍率；Point Blank 是其他职业天赋，不能默认给战士。伏击可使敌人闪避为 0；力量不足仍然取消伏击。多数成功命中后扎在存活敌人身上，待回收；miss 通常落地且不扣耐久，成功命中才消耗，不能把“投出去”次数等于“消耗次数”。

默认一组 3 件，共用该组的耐久消耗逻辑；一次消耗到断裂会移除一件，再以余下件继续，具体剩余要看原生说明。默认每件 `N=baseUses×1.5^u×其他耐久倍率` 次；装备魔法枪套 ×1.2；速度增幅令可用次数 ×1.5，伤害增幅 ×0.6；达到 **100 次**阈值才无限耐久，阈值判断在取整前。水晶附魔另把次数减半并封顶 30。真实每次扣 `100/round(N)+.001`。升级恢复耐久与默认数量，记录组 ID；留在敌人/地面的旧等级同组武器拾取时可能化尘，**升级前先安全回收整组**。

源码：[投掷伤害/力量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L100)、[距离命中](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L217)、[落地/耐久](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L411)、[升级组](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L149)、[旧件拾取](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L650)。

| 阶/力量 | 名称与源文件 | 伤害 | 基础次数、特性与九挑战应用 |
|---|---|---|---|
| 1/9 | [投石 ThrowingStone](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/ThrowingStone.java#L27) | `2+l..5+l` | 5；不扎敌，普通拾取；卖价减半。安全先手、拉怪，不值得为了回收冲进火线。 |
| 1/9 | [飞刀 ThrowingKnife](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/ThrowingKnife.java#L32) | `2+l..6+2l` | 5；伏击下限 `min+round(.75(max−min))`，善用未察觉目标。 |
| 1/9 | [飞刺 ThrowingSpike](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/ThrowingSpike.java#L27) | `2+l..5+l` | 12；战士开局远程资源，耐用先手。 |
| 2/11 | [捕鱼矛 FishingSpear](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/FishingSpear.java#L29) | `4+l..10+2l` | 8；对食人鱼 `damage=max(当前伤害,鱼当前HP/2)`，在攻击 proc 节点执行；不是固定“两击杀鱼”。 |
| 2/11 | [飞槌 ThrowingClub](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/ThrowingClub.java#L27) | `4+l..8+2l` | 12；不扎敌，拾取零耗时，便于安全循环。 |
| 2/11 | [手里剑 Shuriken](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/Shuriken.java#L31) | `4+l..8+2l` | 5；没有瞬发冷却时本次投掷零耗时，随后添加 `ShurikenInstantTracker`（时长20，内部添加19补偿本次零耗时）。**4.0.2 不是走一步就重新瞬发的旧机制**；之后投掷走正常耗时。 |
| 3/13 | [投矛 ThrowingSpear](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/ThrowingSpear.java#L27) | `6+l..15+3l` | 8；可靠基础远程杀伤。 |
| 3/13 | [苦无 Kunai](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/Kunai.java#L32) | `6+l..12+3l` | 8；伏击下限 `min+round(.6(max−min))`。 |
| 3/13 | [流星索 Bolas](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/Bolas.java#L30) | `4..9+2l` | 5；命中施加5时间残废；用减速拉开距离/增加远程窗口，比伤害本身更关键。 |
| 4/15 | [标枪 Javelin](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/Javelin.java#L27) | `8+l..20+4l` | 8；普通高阶单体输出。 |
| 4/15 | [飞斧 Tomahawk](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/Tomahawk.java#L34) | `6+l..16+3l` | 5；另加流血强度 `NormalFloat(3+l/2,6+l)`，受增幅，不取超额力量；流血不被普通护甲 DR 减免，可打后撤退。总流血不是固定“强度×回合”。 |
| 4/15 | [重型回旋镖 HeavyBoomerang](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/HeavyBoomerang.java#L36) | `8+l..16+3l` | 5；命中/未命中后约5次同层 tick 返回**原投掷格**。英雄仍在该格则零耗时接取；别人占格则另一次攻击，可再次耗耐久。回程临近命中也用×1.5。别误认为跟踪英雄新位置。 |
| 5/17 | [三叉戟 Trident](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/Trident.java#L27) | `10+l..25+5l` | 8；普通高伤害投掷。 |
| 5/17 | [投掷锤 ThrowingHammer](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/ThrowingHammer.java#L27) | `10+l..20+5l` | 12；不扎敌，零耗时拾取。 |
| 5/17 | [震爆方石 ForceCube](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/ForceCube.java#L43) | `10+l..30+5l` | 5；落点及周围8格逐一 `shoot`，每目标仍需命中；包括自己/友军！触发这些格的压力/陷阱（周边 TenguDartTrap 特判除外），不靠默认击退伤害；悬空无角色坑格不触发。适合处理集群前必须把自身、盟友、陷阱纳入范围。 |

特别提示：回旋镖 `CircleBack.left` 在 `setup` 设为5，却未写入 store/restore；读档新对象默认0，下一次符合楼层 tick 可能提前回程。[09重载篇](09-save-reload-state.md)已用原生Bundle隔离验证`4→0`，但没有验证完整场景返航、动画和拾取时序，不能据此承诺可控即时返还。实战不为了试验破坏安全状态。

## 5. 飞镖、十字弩与全部涂药镖

普通飞镖 `Dart`：一阶，力量9，基础 `1+l..2+2l`，自身不能升级，基础耐久1000（按公式属于无限），默认2件。普通镖共享 set ID，易于回收循环。**十字弩只需装备即可让战士射出的镖继承其远程伤害和附魔**：弩有效等级 `b` 时为 `4+b..12+3b`，再叠镖伤害等级（主要是神射戒）`+l..+2l`，最后加镖自身超额力量。镖的力量要求与攻击速度走镖本身，不能把弩的近战力量/速度增幅自动套到镖；弩附魔包括其诅咒也会通过 `bow.proc` 处理。弩的升级并不让战士获得 Charged Shot。

一颗种子可涂2枚普通镖；所有涂药镖是二阶（力量11），**直接伤害仍与普通镖相同**，不按二阶普通投掷的4–10计算。标准涂层基础只承受1次命中，再产生普通镖；枪套/神射戒/天赋/金莲等可改变涂层寿命，腐莓例外固定5次。绝大多数状态在命中后才触发，距离、命中、抗性/免疫必须先考虑。荒芜不能种植，不等于不能用种子涂镖：`Dart` 的种子涂层入口没有 `NO_HERBALISM` 阻止。

源码：[Dart.damage/弩绑定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/Dart.java#L55)、[涂镖数量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/Dart.java#L286)、[涂层阶数/变回普通镖](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/TippedDart.java#L60)、[种子映射](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/TippedDart.java#L222)。下表时长为设置值，不保证敌人抗性调整后仍完整持续。

| 种子 → 涂药镖 | 源码效果 | 九挑战使用要点 |
|---|---|---|
| 速行蓟 → [激素 Adrenaline](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/AdrenalineDart.java#L38) | 友方无伤，肾上腺素10；敌方残废5 | 低成本放风筝或强化真实盟友。 |
| 致盲草 → [致盲 Blinding](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/BlindingDart.java#L37) | 致盲10 | 打断依赖视野的远程压制，结合脱离旧位置；不是所有敌人攻击都依赖视线。 |
| 冰冠花 → [寒霜 Chilling](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/ChillingDart.java#L37) | 普通格6，水中10寒冷 | 用水提高控制，控制时长仍会自然衰减。 |
| 魔皇草 Mageroyal → [净化 Cleansing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/CleansingDart.java#L52) | 友方无伤并 cleanse；敌方清除正面 buff，但明确保留 ChampionEnemy、ChargedShot，随后让 hunting/fleeing mob 变 wandering并beckon | **不能净化精英词缀**；可剥除豺狼暴徒维持生存的狂暴。转入 wandering 不等于永久消失或必然安全。 |
| 梦夜花 → [传送 Displacing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/DisplacingDart.java#L44) | 非 IMMOVABLE 目标移到距攻击者路径距离8–10的合法空格，优先可见、再选最靠近目标的格；无合法格无效 | 主动隔离危险近战；不是随机瞬移任意层，不对不可移动目标生效。 |
| 阳春草 → [治疗 Healing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/HealingDart.java#L49) | cure + Healing `floor(.5×目标HT+30)`；对盟友不伤害并跳过附魔 | 给盟友用；射敌人会治疗它。普通自投不会进入shoot，因此不能借此给战士自疗。 |
| 星陨花 → [神圣 Holy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/HolyDart.java#L53) | 盟友祝福30；恶魔/亡灵另受 `10+d/3..20+d/3`；其他敌人反获祝福30 | 必须先确认敌人属性；不能见到高威胁就盲射，可能帮它加命中闪避。`d=scalingDepth`。 |
| 烈焰花 → [燃烧 Incendiary](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/IncendiaryDart.java#L41) | 命中点燃；也能点燃可燃空格，消耗涂层 | 可处理障碍/制造火区；检查英雄、纸张、草与后续退路。 |
| 地缚根 → [麻痹 Paralytic](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/ParalyticDart.java#L36) | 麻痹5 | 宝贵的脱离窗口；后续受伤会累计破麻痹判定，不能承诺免费打满5回合。 |
| 断肠苔 → [毒液 Poison](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/PoisonDart.java#L37) | 毒强度 `3+d/2` | 打后撤退；毒免疫/无机物需先查。 |
| 腐莓 → [腐莓 Rot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/RotDart.java#L38) | 普通敌人腐蚀时长10、初伤d；BOSS/MINIBOSS时长5、初伤`d/3`；固定5次使用 | 持续伤害很强，需与任务交付/力量收益比较种子机会成本，不能假定一局必有。 |
| 风暴藤 → [电击 Shocking](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/ShockingDart.java#L44) | 额外电伤 `5+d/4..10+d/4` | 当前代码为该目标单次 Electricity 伤害，不是电击附魔的水域连锁。 |

`d/2,d/3,d/4` 是 Java 整数除法。麻痹受伤解脱判定见 [Paralysis.processDamage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Paralysis.java#L51)。治疗镖在其自身实现没有禁药挑战判断，但“给其他角色治愈”和“英雄能安全自疗”是不同命题；以当前真实可达/可投目标为准。

灵能短弓 `SpiritBow` 是女猎手初始专属物，普通战士路线不以其为资源计划。它不能靠升级卷升级，伤害随英雄等级成长；普通箭无限生成。弓/法师魔杖列入这里是为了避免把别职业默认装备混入战士方案，完整法杖打法另见弹道/法杖章节。来源：[SpiritBow](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/SpiritBow.java#L184)。

## 6. 全护甲与「信念护体」精确影响

普通护甲阶级只有五种，职业护甲保留转化前阶数。以下普通公式取非负等级、没有造成异常端点的情况；负等级/特殊增幅边缘分支以 `Armor.DRMin/DRMax` 原式为准。

| 护甲 | t/基础力量 | 正常 `+l` DR 范围 | 信念护体未增幅 DR | 信念护体下的实际评价 |
|---|---|---|---|---|
| [布甲](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/ClothArmor.java#L26) | 1 / 10 | `l..2+l` | `0..2+l` | 初始等级0时上限与正常相同，但升级后的下限始终0；轻、易穿。 |
| [皮甲](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/LeatherArmor.java#L26) | 2 / 12 | `l..4+2l` | `0..3+l` | 多1阶只多1点DR上限；免费高等级/好刻印可用。 |
| [链甲](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/MailArmor.java#L26) | 3 / 14 | `l..6+3l` | `0..4+l` | 判断力量/刻印/纹章护盾，不为常规“肉装”预期重投。 |
| [鳞甲](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/ScaleArmor.java#L26) | 4 / 16 | `l..8+4l` | `0..5+l` | 正常高阶升级收益被显著压低。 |
| [板甲](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/PlateArmor.java#L26) | 5 / 18 | `l..10+5l` | `0..6+l` | 例：+3仅0–9而非3–25；仍有刻印、纹章护盾、护甲技能载体价值。 |

挑战准确源码：`NO_ARMOR → DRMin=0, DRMax=1+t+l+augment.defenseFactor(l)`。因此：

- 无增幅：`0..1+t+l`。
- 闪避增幅：防御因子 `−(2+l)`，得到 **`0..t−1`，与升级等级无关**；闪避额外 `+2(2+l)`。
- 防御增幅：防御因子 `+(2+l)`，得到 **`0..t+3+2l`**；闪避额外 `−2(2+l)`，英雄最终通常至少1闪避，磐岩特例为0。
- 例：+3布甲：无增幅0–5；闪避0–0并+10闪避；防御0–10并−10闪避。+3板甲：分别0–9、0–4、0–14。以上只为护甲贡献，不含武器盾、Hold Fast、其他机制。
- 力量不足 `n`：护甲随机 DR 再减 `2n`、闪避除 `1.5^n`、移动速度除 `1.2^n`。额外的重甲惩罚是真实的，升级降低力量门槛仍有价值。

这意味着“信念护体一律不升甲/一律闪避增幅”都过于绝对。免费/既有高强化护甲能改善刻印、增幅收益与力量门槛；**更高护甲阶数**才提高普通纹章盾，强化等级本身不提高它。狂战士主动狂暴盾另按强化计算，详见02。但有限升级卷若只换每级平均约0.5普通护甲 DR，一般应和武器、控制法杖等比较。防御增幅在该挑战里每级多一份 DR 成长，代价是闪避；面对能闪避的高伤攻击时不能只看 DR 上限。磐岩刻印对闪避的特殊转换见06b。

来源：[Armor DR](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L374)、[增幅](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L97)、[负重与闪避](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L413)、[Hero.drRoll独立武器项](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L643)。

矮人国王后的职业护甲 `ClassArmor.upgrade` 会复制原甲真等级、**tier**、增幅、刻印、纹章、硬化、诅咒、注能、掌握药剂等，再初始给50充能；不是自动换成五阶板甲。战士具体三种护甲技能见战士章节。来源：[ClassArmor.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/ClassArmor.java#L98)。

## 7. 升级、鉴定与诅咒：资源不够时更不能赌错

### 升级与附魔保留

普通武器/护甲升级先处理附魔再加级，风险看**升级前**的 `level()`：普通正面附魔/刻印从 `+4→+5` 起有10%丢失，随后20%、40%、80%、100%（`+8→+9`）。硬化后从 `+6→+7` 起按同序列消耗硬化，触发时移除硬化，**该次不同时丢掉附魔**。`upgrade(true)` 是保留/赋予附魔分支，普通升级卷走 `upgrade()`；实际保留手段应查秘卷/炼金条目，不能把不同入口混称升级。

普通升级清除装备的 `cursed` 绑定，但诅咒附魔/刻印只有1/3概率被清掉，因此可出现“能脱下、诅咒效果仍在”的弱诅咒状态。**投掷物有例外**：升级后若仍有诅咒附魔，`MissileWeapon.upgrade(boolean)` 恢复原 `cursed`，不享受同样的弱化语义。注能等级也依赖诅咒存在，净化之前要核对会损失的战斗等级与力量门槛。

来源：[Weapon.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L380)、[Armor.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L455)、[MissileWeapon.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L149)、[Upgrade scroll](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfUpgrade.java#L89)。

### 被动鉴定不是随便挥20下

武器默认20次有效使用、投掷10、护甲10，但开始只给一半可用鉴定进度；随后英雄获得经验按等级比例恢复可用进度，天赋改变速度。武器进度在命中 proc 推进，护甲在防御 proc 推进；不是向空气挥击/零经验刷怪即可立刻鉴定。遗忘碎片饰品可能禁被动自动鉴定，只标记 ready。投掷一组的鉴定在 parent/child 之间同步，最后一件即将损毁还有跳过鉴定的特判。

战士天赋能改变此流程，具体点数用战士章节；不要为了鉴定在禁药挑战下故意站着挨打。装备描述“未知时的典型伤害/力量”是 **+0 参考值**，不是当前隐藏等级事实。识别出不能穿/有诅咒的信息前，预留解除诅咒、原装备与安全地点。

来源：[Weapon ID](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L123)、[Armor ID](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L553)、[Missile ID](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L95)。

### 九挑战装备动作前的短检查

1. **本次是否直接避免挨打？** 用投掷/控制/遮线/长武器可先削血；优先少打一轮，而非为了面板更高冒负重风险。
2. **力量是否满足，替换是否值得2时间？** 检查新装备实际已知状态和当前敌人距离；不要在被包围时连换装。
3. **升级后哪条阈值改变？** 击杀次数、力量门槛、法杖效果、武器盾 DR、投掷耐久/数量、刻印收益，至少要能说明一项实际收益。
4. **附魔、诅咒、增幅是否改变计划？** 链枷丢伏击、烈焰烧退路、击退改位置、水晶/投掷耗尽等都应在消耗前预算。
5. **回收是否安全？** 能回收不等于值得追；危机中丢一件便宜投掷物通常比多吃一次攻击便宜。
6. **机制是否本职业可用？** 武器基础特性属于战士，决斗家主动、女猎手灵能弓和法师战斗魔杖不是默认工具。

## 8. 覆盖范围与待验证项

已逐个核对所有32个 `melee` 具体类、任务镐、15种普通投掷、普通飞镖和12种涂药镖；`MeleeWeapon/MissileWeapon` 为基类，不算具体装备。五种普通甲、职业甲转换、公共命中/伤害/力量/速度/DR/增幅/升级/鉴定入口已覆盖。附魔/刻印完整表另列06b。

本篇没有以测试夹具跑统计分布，也没有证明某装备组合能稳定九挑战通关。重点待隔离验证：重型回旋镖读档提前返还的精确调度窗口；各种持续伤害/护盾/附魔组合在特定 Boss 上的结果；瞬发手里剑冷却在行动条和UI上的完整表现。源码表中的数值是当前提交事实，策略是推断，临场当前状态来自授权公开观察。源码更新后先核对此提交差异，尤其不要用旧版手里剑/投掷耐久/附魔名单覆盖本篇。
