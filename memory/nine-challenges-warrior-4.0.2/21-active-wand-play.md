# 21　战士主动使用法杖：把充能兑现为少挨打、控制和通路

研究日期：2026-10-14。逐项复核基线：`bb0c91fb7`，游戏 4.0.2 / code 922，CLI.9.0.6。
本篇响应用户“还要积极使用法杖和投掷武器”的策略要求；现局保持暂停，本研究没有查询或操作游戏，没有启动实例，没有读取个人存档、profile 或私有审计。没有假设本局已经获得任何一根法杖。

**源码确认**是当前实现直接支持的规则；**条件推导**是据此制定、须用当前公开观察判断的行动准则；**待实测**表示尚未在本局或隔离场景验证收益。旧研究[08 弹道与十三杖](08-ballistics-and-wands.md)提供完整数值、偏点瞄准与几何验证；[15 炼金规划](15-alchemy-planning.md)提供炼金机会成本。本篇补充“拿到后何时实际用”的决策，不把旧坐标、物品句柄或未观察的敌人血量当现局事实。

## 一、主动使用的执行规则

**条件推导：每次交战都先把现有法杖放进候选动作，尤其是仍有距离、对手难命中、危险近战尚未贴身、已经出现必须跨越的通路时。** 比较一次施法后能少受几次攻击、能否完成一次脱离/击杀/拾取，以及与投掷、近战、移动的总回合和消耗。不能等到已经被围、没有退路或血线只够一次攻击时才想起法杖。

以下任一项成立，就应明确考虑花充能，而非默认继续近战：

1. **先手压血：** 当前能打到而敌人尚不能有效还击，提前减掉一轮近战；目标高闪避或物理防御高时尤其值得比较。一般直伤法杖直接调用 `Char.damage`，没有普通武器的命中判定，但仍有魔法免疫、减伤和护盾，不能称“必扣面板伤害”。[魔弹伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfMagicMissile.java#L57)、[伤害免疫/冠军减伤](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L929)
2. **控制后立即兑现：** 寒冷、致盲、魅惑、缠绕、麻痹或击退后，明确下一步是拉开、过门、脱离射线、打死挡路者还是取得关键物品。控制期间多搜一个支线房不是原控制目标；每个动作重看剩余效果和威胁。
3. **收尾省下危险一击：** 公开生命条只支持区间判断；若低伤害掷点仍可能不死，必须预算剩余攻击。能安全投掷/近战结束时可保留唯一解围充能；下一次敌方动作可能致命或附带恶性状态时应优先花掉可靠的收尾手段。
4. **解决当前瓶颈：** 法杖能清已知火障、烧/解离路障、揭示疑似机关通道、把敌人推离狭道、设置哨卫阵地，就在具体房间方案中比较，避免为“以后也许有用”无期限放弃已知奖励。
5. **满充工具有当前用途：** 只要能减少代价或推进明确目标，就优先让它重新进入充能周期；不为腾空而空射、不为回充而无目标等几十回合。
6. **应急储备按功能留：** 若这根杖是目前唯一脱围工具，先保留足以完成已知危险动作的充能；另一根输出杖、可回收投掷或天然掩体能覆盖的功能不应重复留到全部满充。出现生命风险时，应急份额就是现在使用的资源。

**源码确认：** 普通施法耗时 1，并驱散隐形；驱魔的 `MagicImmune` 会阻止正常施法。射击后站在原地的这一回合以及隐形消失后的暴露都要计入方案。[Wand 门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L150)、[耗时与隐形](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L540)

## 二、十三杖的主动使用表

表内“用”均以**未诅咒、当前有足够充能、操作可用、同帧弹道/范围已检查**为前提。`L` 通常为实际本次 `buffedLvl()`；未知等级不套乐观值。通常每次 1 充能；焰浪、再生按当前余量的 30% 向上取整，限制为 1–3，因此余量 1–3/4–6/7–10 分别耗 1/2/3。不能把它们算作固定“一发一格电”。[基础消耗](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L489)、[焰浪](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFireblast.java#L252)、[再生](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfRegrowth.java#L278)

| 法杖 | 条件推导：主动花充能的窗口 | 源码确认：本次必须核对的代价与边界 |
| --- | --- | --- |
| **魔弹** | 先手、可靠弹道的收尾；若魔弹等级较高且另一杖的下一发确实更有价值，先命中角色再在短窗口里接较低级杖 | 命中角色才有机会施加 `MagicCharge`；效果持续 4 时间，让较低级杖借等级一次，并非给它补充能。空地不触发。两次施法的敌方动作都须预算，不能为组合牺牲当下撤退。[命中/加持](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfMagicMissile.java#L57)、[持续](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfMagicMissile.java#L96)、[消耗加持](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L491) |
| **霜冻** | 危险近战还未贴身时先打一发，利用减速拉开并接投掷/别的杖；已确认永燃火挡住奖励时，比较用杖逐格开通路 | 存活目标受寒冷，水中持续更长；已有寒冷会降低下一发伤害，已有冻结则本次不伤害。会冻结落点物堆并清该格火，永燃火额外检查下一格；落点为空会压机关。冻结物堆风险应先检查，不把水中减速当永久定身。[Frost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFrost.java#L62)、[寒冷速度](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Chill.java#L45) |
| **雷霆** | 多敌已聚集、尤其初始命中者站水中且整个电弧链离英雄安全时，用一发降低群战成本；单敌高物理防御时也可直伤 | 链从每个节点递归：水中节点传播范围 2，其他 1；英雄只需邻接任一链节点就可能受半倍率伤害。初始落点水中取消多目标伤害分摊。非初始盟友可能不受伤却仍作为传播节点；直接瞄准盟友不受这条豁免。[伤害/盟友](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLightning.java#L72)、[传播](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLightning.java#L144) |
| **解离** | 已知敌人排成线时、多目标射线前存在已知墙体时，或需要破坏可燃路障时积极使用；有当前公开定位和合法瞄准时可比较隔墙射击 | 穿墙穿角色，射程为路径步 `6+2L`；命中人数和命中前穿过的 solid 增伤，不含最后目标后的墙。同线盟友也可能受伤；穿墙不等于获得隐藏敌人位置。破坏可燃地形会改视线和后续接敌，行动后重看。[Disintegration](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfDisintegration.java#L74)、[射程](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfDisintegration.java#L138) |
| **焰浪** | 成群敌人仍在前方、已备后撤路线时先群控；有已知路障和可控火势时用于开路，不等贴脸再喷 | 真锥形可能覆盖中央线两侧；2 充能附残废、3 充能附麻痹，各 4 时间。施法者格跳过不等于后续火安全；烧草、物堆，打开门并改视线。不能让火覆盖唯一回程或关键补给；火焰冠军免燃烧但不是自动免所有直接伤害。[范围与环境](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFireblast.java#L86)、[附状态](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFireblast.java#L138)、[锥形](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFireblast.java#L219) |
| **冲击** | 有已知落坑/撞墙路径时以位移解除高血高甲瓶颈；近敌堵出口时比较推开；已确认机关及范围安全时可远处触发 | 先压爆心九宫格机关（天狗飞镖陷阱例外），再伤害/推动。周边盟友免直接杖伤但照样被推，中心没有盟友豁免；英雄也会被推。中心后续射线决定方向，偏点可改变它。rooted/不可移动不推，大型需连续宽空间，Boss推力减半。[爆心](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfBlastWave.java#L75)、[推动限制](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfBlastWave.java#L122) |
| **棱光** | 有行动余裕时先射威胁远程者，若实际致盲再穿越/断线；有明确疑似机关走廊时让侦查与攻击同发完成；亡灵/恶魔是较佳输出对象 | 致盲是概率效果，低级不能作必成的撤退保证；致盲也非麻痹。沿射线及邻格揭示秘密/测绘，黑暗挑战的 Light 仅 `2+L` 时间，不能替代整层火把。按实际新公开地图决定是否取物。[PrismaticLight](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfPrismaticLight.java#L70) |
| **腐蚀** | 怪群、窄口后方或可持续封锁的房间：先把云放在敌人需要停留/经过处，然后退出扩散区；能同时伤多个目标时比逐个换血更合算 | 弹道穿角色但不穿实体地形，在所选目标/墙前落点播云；角色无阵营豁免。腐蚀伤害递增，走出云后已附的 DOT 仍可继续；先保证回程不在云里。同层未散气体共享只升不降的强度，加持大云也提高自身风险。[Corrosion 杖](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfCorrosion.java#L49)、[气体](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/CorrosiveGas.java#L43)、[DOT](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Corrosion.java#L104) |
| **腐化** | 已经削弱或附状态的目标优先；转换一名能立即牵制其他敌人的目标，或为强敌叠负面，而非满血硬刷至空电 | 抗性受敌种、血量比例和现有负面影响：满血系数 5，半血 2，四分之一血 1.25；重大负面各乘 0.5，其他合适负面各乘 0.75。转换会回满其血并清大多数负面，之后逐步自耗；不保证永久随从。免腐化时尝试 Doom，仍要查 Doom 免疫。[判定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfCorruption.java#L142)、[免疫/转换](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfCorruption.java#L195)、[回满和自耗](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Corruption.java#L39) |
| **再生** | 可生长地面上的近战敌人需要被暂时留住，或需要造草切断普通视线时使用；缠绕后从射程外投掷/换线，不因“荒芜不能种草药”而闲置整根杖 | 只有源码允许的空地/草/余烬等格才对角色缠绕，水、门、陷阱等不适用；飞行者不能附 Roots。缠绕不禁攻击，尤其远程/延伸冠军仍可能攻击。荒芜下不产植物/露珠，造草和直接召莲花仍可发生；新草也可能挡自己的后续观察。[Regrowth](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfRegrowth.java#L99)、[Roots](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Roots.java#L37)、[禁种](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1068) |
| **活土** | 预计必须接敌时提前施法，边压血边建立岩甲；已形成守护者时射敌续它血、让其分担攻击；也可直接打守护者补给以维持当前有效阵地 | 空射不凭空产岩甲。岩甲在 `Hero.defenseProc` 中消耗并吸收部分攻击伤害，不是所有 DOT/饥饿的通用护盾；达到阈值可能转成守护者。守护者在信念护体下 DR 更低，不能把其持续生存当保证；生成位置、占位与我方退路都要重看。[触发](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLivingEarth.java#L74)、[岩甲](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLivingEarth.java#L286)、[守护者](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLivingEarth.java#L387)、[吸收入口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1606) |
| **注魂** | 未魅惑的活敌逼近时一发争取移动窗口并给自己盾；亡灵目标则作为直接输出兼护盾。先用盾/魅惑完成撤离或关键攻击，不必一律留到低血 | 对敌人分支给自己 `5+L` 盾；活敌尝试魅惑，亡灵受伤。**盟友或已经带 Charm 的目标优先进入治疗目标、伤自己 `round(HT×0.05)` 分支。** 再次点同一敌人前必须重查魅惑，不能当连续免费刷盾；英雄攻击会加速其魅惑结束。[Transfusion](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfTransfusion.java#L90)、[魅惑受击](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Charm.java#L70) |
| **哨卫** | 有安全布置窗口的已知通路、需额外观察的岔口、将要引怪的阵地：提前部署，再把敌人带进火力范围；当前满充且阵地马上有用时不要等开战后才连铺数回合 | 可见目标格仅 `STOP_TARGET`，可越沿途墙/角色部署，但终点需通行、空位和容量；放置会压目标格。炮台自身射击仍用 `MAGIC_BOLT`，不能隔墙开火。1/2/3阶累计可射1/3/5次，升级不是重置已射次数；高阶按每发扣血。视距从4逐阶提高，提供原生额外视野，不是查看隐藏地图的权限。[部署](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfWarding.java#L65)、[升级/视野](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfWarding.java#L233)、[射线/消耗](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfWarding.java#L358)、[视野并入](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1494) |

**条件推导：一根低级杖也可以改变房间方案。** 例如已知霜冻可处理永燃火、焰浪/解离可处理可燃阻挡、哨卫可以把部署与额外视野结合。先比较“取得已知奖励的完整往返成本”和现有替代；不能因为该杖不适合当前怪物就直接归为卖物。触发机关不是自动拆除机关，炸点、气体、召唤、坠落等具体效果另查后再决定。

## 三、冠军、强制位移与组合使用的三个校正

**源码确认：抗魔冠军并非一句“所有法杖无效”。** 其收到伤害还乘 0.5，并免疫 `AntiMagic.RESISTS` 中的直接伤害/效果：其中列有魔弹、霜冻、雷霆、解离、焰浪、冲击、棱光、活土、注魂伤害及哨卫炮击，也列有 `Charm/Weakness/Vulnerable/Hex/Degrade` 等具体状态；没有把 `CorrosiveGas`、`Corrosion`、`Roots`、`Blindness`、`Chill` 或 `Knockback` 一并列入。原物种/其他属性仍可另外免疫。因而针对抗魔者应核查并比较腐蚀、可生长地面缠绕、已知击退路径与物理投掷，不能重复用零直接伤害的杖硬烧充能；也不能反过来把所有间接效果当必成功。[冠军](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L193)、[免疫清单](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/AntiMagic.java#L80)、[汇总物种/属性/buff免疫](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1380)

**源码确认：巨大、祝福和火焰冠军应分别处理。** 巨大受伤乘 0.2，且可沿最多2步的有效路径攻击，不保证门外隔一格就安全；它是大型角色，冲击可能因狭道不能推动。祝福命中/闪避乘4，提高直接法杖伤害相对普通投掷/近战的吸引力。火焰免燃烧，死亡可能向周围非水、非solid格放火；被推入坑死亡有避免该放火分支的条件，但飞行者另算。[巨大/祝福](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L211)、[火焰](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L126)

**条件推导：冲击击落是省资源选项，不只是紧急按钮。** 对高血/高甲敌人，若明确不飞行、可被推动、落点为当前已知悬崖、英雄与盟友不在危险九宫格/推线路径，值得用1充能换掉整场换血。代价包括原生坠落击杀经验减半、战利品和回收路线的机会成本；不能为追回掉落再用更多生命下坑。[Mob 坠落经验](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1023)、[推动实际占格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfBlastWave.java#L162)

## 四、回充经济：正常推进时用，不靠无目标长等补

**源码确认：** 每根持有并挂上 `Charger` 的法杖有独立当前充能和部分进度。普通自然回充每 tick 增加
`RingOfEnergy倍率 / (10 + 40 × scalingFactor^缺失充能数)`；普通 `scalingFactor=.875`，魔法枪套 `.85`。
无戒指的普通杖，缺1格时速率相当于45时间/格，缺2格时40.625时间/格；补进一格后缺口变小，之后速度也随之改变。满充时清零部分进度，满充期间不积攒下一发。不能将这些速率说成“打两发后恰好若干回合必满”。[持有绑定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L166)、[Charger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L805)、[枪套](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bags/MagicalHolster.java#L36)

| 条件 | 源码确认 | 条件推导 |
| --- | --- | --- |
| 饥饿/饥荒 | 法杖自然充能调用 `Regeneration.regenOn()`；该函数只查锁层是否允许。HP自然回复另查 `!isStarving()`，法杖这里没有饥饿检查 | 饥饿不等于法杖停充；但等电仍耗照明/饥荒HP并可能遇敌，不能把“可充”当免费休息。[回血/锁层区别](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L56)、[regenOn](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L113) |
| 锁层 | 自然项仅在 `regenOn()` 为真时增加；有 `LockedFloor` 不等于任何时刻都绝对停充，要看其许可。源码初始有利被动窗口在绝命头目下为20时间，战斗逻辑可增减 | 首领前尽量准备真实充能，锁层中不承诺无限拖延补满；这个初始常量不授权推算当前隐藏剩余计时。[自然项](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L846)、[锁层许可](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/LockedFloor.java#L31) |
| 充能buff | 每个有效 `Recharging` 另加 `.25 × remainder()`，在自然项开关之外；普通充能卷是给30时间buff而非立即补满。英雄 `MagicImmune` 会跳过整个 `recharge()` | 锁层下可比较明确的充能buff，但施卷耗回合、回充有过程；不能先驱魔再承诺法杖续航。[Charger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L828)、[充能卷](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRecharging.java#L45) |
| 获得经验 | `onHeroGainExp` 补的是未知杖“可计入鉴定的使用额度”，不是普通自然充能 | 不为“杀怪回电”追逐低收益敌人；正常推进所得经验可让之后的有效施法继续鉴定。[经验与鉴定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L283) |
| 已满充 | 当前等于上限时 `partialCharge=0` | 有安全收益就用，走路/捡物/战斗的正常时间能同时给它充能；满充闲置是放弃恢复机会，不是额外安全层。[满充处理](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L829) |

**条件推导：满充焰浪/再生还有模式差异。** 较多当前充能意味着更宽更远的锥形和一次多耗，焰浪还提高控制档位；因此“提前用掉一发”会改变下一发的范围和控制，不能只按同质弹药处理。若马上要解决的群战需要2/3充能档，保留该档有具体理由；若没有该需求而小档已足够，就可以现在用。

## 五、刚拿到未知杖：尽早获得可用信息，保留可承担的首发窗口

**源码确认：** 普通随机法杖的生成可有诅咒；未知等级和未知诅咒是不同知识。诅咒施法改走 `CursedWand` 和 `MAGIC_BOLT`，不执行本类型的正常效果；完成一次获准施法会公开诅咒状态，**不等于已经知道等级、当前充能或完全鉴定**。普通使用鉴定需10份进度，初始可计额度仅5，经验补充额度；战士没有默认法师鉴定天赋，遗忘碎片等现有天赋/饰品还可能改变被动鉴定。[初始鉴定预算](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L93)、[使用鉴定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L458)、[诅咒分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L757)

**条件推导：取得后在安全推进中安排首用，不把所有未知杖永远当不可用。** 优先选有HP余量、没有紧迫围攻、有已知出口、不靠近需保护的物堆和脆弱盟友的窗口；把它当一次可能异常的试验，另有已知保命手段。若只有已知霜冻能救眼前火障，但此杖诅咒未知，它就不是已建立的救命方案。试过确认未诅咒后，即使等级尚未知，也可按保守效果估计参与正常战斗并自然鉴定；不为了“凑10发”在空房耗尽粮光。

**源码确认：诅咒首发风险远不止伤害浮动。** 坏效果包含对英雄施加燃烧/冻结、在落点放大量随机气体、随机传送使用者、反向伤害英雄、警报引怪和召唤敌人等不同分支；安全房只能降低一部分后果，不能保证可随时取消。它不是与药剂/卷轴共用的随机身份池，也不能由法杖外观排除某个诅咒效果。首次试射是先结算随机效果再获信息，已知诅咒后不因“积极使用”继续反复赌。[燃烧/冻结](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/CursedWand.java#L221)、[传送/气体](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/CursedWand.java#L250)、[生命转移自伤](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/CursedWand.java#L467)、[警报](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/CursedWand.java#L639)、[召唤](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/CursedWand.java#L786)

## 六、树脂、升级、重复杖与出售：保留功能，及时改善关键短板

| 选择 | 源码确认 | 条件推导：现在作决定的门槛 |
| --- | --- | --- |
| **树脂强化** | 目标要已鉴定且当前 `level()<3`；升一级花当前等级+1树脂，+0→+3共6；施用使当前充能+1、花1时间。不执行驱邪，已鉴定诅咒杖也可被选 | 已有明确会反复用的主输出/控制杖，提升能改变下一段战斗、容量或控制时就强化；勿把树脂当解除未知风险。战斗中施用也消耗回合，通常在安全窗口处理。[选择/应用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/ArcaneResin.java#L105) |
| **拆杖炼树脂** | 必须明确非诅咒；耗整根杖和5炼金能量，产量 `2×(level−resinBonus+1)`，树脂强化部分不能再次回收增殖 | 先列这根的独特功能、备用充能池、下一层用途，确认这些可被其他现有物资覆盖后再拆。不能因为低等级或重复就自动拆。[配方](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/ArcaneResin.java#L151) |
| **原生升级卷轴** | 升级会先去掉一份已有树脂奖励再加基础等级，可能本次有效等级不涨；普通升级只有1/3概率驱邪。容量通常为 `min(initialCharges+level,10)`，当前只增1并受上限约束 | 有限卷轴先比较主武器可用门槛/持续输出与杖的可兑现收益；若需要更高永久法杖等级，就把树脂与卷轴顺序算清楚。不能先树脂+3再把一张升级预算为+4；不能把升级当确定祛邪。[Wand.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L346)、[容量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L428) |
| **保留重复杖** | 独立杖各有独立充能器；重复哨卫杖还都参与容量和的 `2+level()` | 若控制次数/储能缺口是真实短板，保留两根比合成一根更好；若一根长期无用途、包格紧张、另一根强化能明显减少每战回合，才比较树脂。高等级魔弹还可能带动低级工具杖，不只看裸面板。[独立收集](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L166)、[哨卫容量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfWarding.java#L98) |
| **出售换补给** | 出售移除当前杖及其以后可用的独立充能；金币收益以当前商店公开报价为准 | 明确要买的粮食/包/关键消耗若能立即解决更大的短板，可以卖掉用途已经覆盖的多余杖。先比较普通过时近战/护甲等替代卖物；不为腾一个格就丢掉唯一控制、唯一破障或唯一安全远程手段。[商店/炼金原则](15-alchemy-planning.md) |

**源码确认：拆解不是通用增值出售技巧。** 无额外天赋的+0非诅咒杖原价值75，拆成2树脂总价值60，还花5能量。未知等级杖的树脂配方预览可能只给占位1份，不能据此断定实际仅产1份，也不能读隐藏等级提前算产量；按当前公开预览与炼成结果处理。[杖价值](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L577)、[树脂价值](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/ArcaneResin.java#L88)、[未知等级配方预览](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/ArcaneResin.java#L166)

**条件推导：战士的近战和法杖应互补。** 力量不足、暂时没有合用主武器时，法杖和投掷先把危险敌人压到可承受的范围；有好近战后，法杖仍负责远程威胁、控制、击落和房间解法。靠近战解决安全残血能给各杖积累正常回充时间；有直伤/控制可免一轮危险换血时，不为省充能硬挨。普通战士持有法杖不会获得 `onHit(MagesStaff,...)` 的灌注法杖近战效果，不凭空预算法师的回充、免费注魂或草地治疗。[法杖的专用近战接口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L138)、[魔弹法师近战](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfMagicMissile.java#L81)

## 七、恢复游戏后每次施法的短检查

1. **公开状态：** 当前杖的类型、诅咒知识、等级知识、可用充能和 advertised operation；英雄HP/盾、隐形、视野、敌我角色和退路均来自同帧。
2. **具体收益：** 写出“此发后接什么”——击杀、远程削血、过门、离开射线、取物、换阵地、开路之一；没有收益时不空耗。
3. **全部范围：** 普通首个碰撞、锥形两侧、完整电弧链、冲击第二条推线、腐蚀扩散以及盟友/补给/机关。遇未知输入保留不确定性；旧弹道例子不能自动套现在的地图。
4. **代价与失败分支：** 1回合、1–3充能、隐形解除、可能不致死/不致盲/不移动、后续DOT和敌方反击；控制成功也要立即兑现。
5. **观察结果再下一步：** 一次当前操作、精确当前rev、收全响应/按协议处理pending。实际没命中、敌人换位、状态免疫、炮台挡路或火云改路，就重做方案，不连发旧决定。

**待实测：** 这一策略尚没有新的实战收益记录；没有证明某根杖是九挑战最高胜率选择，也没有证明组合必定命中、控制或安全。后续仅在用户恢复游戏后，用正常公开记录保留“施法前目标与余量 → 选择/替代 → 实际位置/状态/HP代价 → 后续兑现动作”，特别追踪满充闲置、控制未兑现、误伤/毁物、对免疫目标浪费和重复杖出售后的功能缺口。现有[08 的21个合成弹道用例](08-ballistics-and-wands.md)只能支持它列出的几何条件，不能替代上述实战检验。
