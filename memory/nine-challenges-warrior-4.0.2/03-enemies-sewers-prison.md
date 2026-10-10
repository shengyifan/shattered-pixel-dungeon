# 九挑战战士：下水道、监狱与前两位首领

基准：仓库 `8405ae82f`，游戏 4.0.2 / code 922，2026-10-10。本篇是源码静态调研，未启动游戏、未读取个人存档、未进行九挑战实战或试验。标为“源码事实”的规则已追踪具体方法；“战术推论”是据此提出的条件性打法，不代表已验证无伤或必胜。怪物数值是未受冠军、减益、登顶等修正的模板值，不能用它们代替当前公开生命条或精确推断隐藏剩余 HP。

## 阅读约定与生成范围

- `ACC/EVA` 是命中/闪避参数，**不是百分比**；物理伤害区间与护甲 DR 通常使用 `Random.NormalIntRange`，不是等概率骰。DR 列仅表示该怪自身常规加值；冠军、状态、属性抵抗另外结算。
- 普通怪移动时间为 `1/speed()`，基础攻击间隔为 1；“移动两倍快”不自动等于攻击两倍快。小偷独立覆写攻击间隔为 0.5。[Mob.attackDelay](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L791)、[Mob.Hunting](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1393)。
- 偷袭成立要看怪物是否看见英雄、隐身、视野与英雄是否能偷袭；成立时普通 `Mob.defenseSkill()` 变 0。门后打一击是常用方案，持续站在门口并不保证后续仍算偷袭。用超力量需求装备等情况要检查英雄的偷袭资格。[Mob.defenseSkill/surprisedBy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L820)。
- `UNDEAD` 本身不等于所有毒免疫；`INORGANIC` 才明确免疫 Bleeding、ToxicGas、Poison。ACIDIC 抗 Corrosion、免疫 Ooze；ELECTRIC 抵抗若干雷电来源，不是“所有魔法免疫”。不要把腐蚀气体与毒气混为一谈。[Char.Property](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1418)。

普通生成由 `MobSpawner.getMobRotation → standardMobRotation → addRareMobs → swapMobAlts → shuffle` 组成；以下是**一轮候选组成**，不是每层固定总数。5/10 层虽然有表项，首领层自身的造怪规则另管，不能由表推出首领房自然刷这些怪。[MobSpawner](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/MobSpawner.java#L62)。

| 深度 | 一轮普通候选 |
| --- | --- |
| 1 | 老鼠×3，蛇×1 |
| 2 | 老鼠×2，蛇×1，豺狼侦察兵×2 |
| 3 | 老鼠×1，蛇×1，豺狼侦察兵×3，蝇群×1，螃蟹×1 |
| 4（表中也含5） | 豺狼×1，蝇群×1，螃蟹×2，史莱姆×2 |
| 6 | 骷髅×3，小偷×1，蝇群×1 |
| 7 | 骷髅×3，小偷×1，DM-100×1，狱卒×1 |
| 8 | 骷髅×2，小偷×1，DM-100×2，狱卒×2，死灵法师×1 |
| 9（表中也含10） | 骷髅×1，小偷×1，DM-100×2，狱卒×2，死灵法师×2 |

4 层每次建立轮转有 2.5% 机会多添一只小偷；9 层相同概率多添一只蝙蝠。每个可替换条目有 `1/50 × RatSkull.exoticChanceMultiplier()` 的稀有变体替换机会；这是生成时概率，不是见到每只普通怪时再独立鉴定的隐藏能力。[MobSpawner.addRareMobs/swapMobAlts](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/MobSpawner.java#L213)。9 层蝙蝠详见后续洞穴篇；鱼、宝箱怪、石像及其他通用遭遇见通用机制篇。

## 下水道：全部普通敌人和区域稀有变体

| 敌人（源码识别） | HP；伤害；ACC/EVA；DR | 关键源码事实 | 九挑战战士的战术推论 |
| --- | --- | --- | --- |
| [老鼠 Rat](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Rat.java#L31) | 8；1–4；8/2；0–1 | 普通相邻近战。基础 maxLvl=5。 | 用它练门后偷袭与一次一怪；九挑战没必要靠弱怪反复受击攒收益。开局盾要用来削减不可避免的损耗。 |
| [白化老鼠 Albino](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Albino.java#L30) | 12；继承1–4、8/2、0–1 | 造成正伤害时50%附加 Bleeding，初值随机2–3；肉掉落机会1。 | 正伤害触发条件使完全挡下这一击有价值，但流血不是固定只掉2–3血。若盾/减伤不足，先投掷削血，尽量一击收尾。掉肉仍服从挑战食物规则。 |
| [蛇 Snake](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Snake.java#L33) | 4；1–4；10/25；无额外DR | 高闪避、低血；25%种子掉率。 | 首选可靠偷袭或不进行普通命中检定的已知法杖伤害；站桩反复挥砍是在用稀缺生命赌命中。无草挑战下不要为了种子刷蛇。 |
| [豺狼侦察兵 Gnoll](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Gnoll.java#L29) | 12；1–6；10/4；0–2 | 普通近战；金币掉率50%。 | 绕门/拐角拉成一对一。它比老鼠更能惩罚为了捡物品多走一回合。 |
| [流放豺狼 GnollExile](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollExile.java#L41) | 24；1–10；15/6；普通豺狼DR再加0–1，即0–3 | 初始PASSIVE，不自动攻击；负面buff会使其脱离被动，攻击告警也可激怒；可沿不穿墙且不穿角色的≤2步路径攻击。额外掉2或3件随机物品。 | 默认绕开，先看公开描述是被动还是已激怒。被动时不要让火/毒/群伤“顺便碰到”。两格武器不保证安全，因为它也有2格路径攻击；只有明确高价值掉落与足够远程/控制时才主动挑战。 |
| [蝇群 Swarm](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Swarm.java#L39) | 50；1–4；10/5；无额外DR；飞行 | `defenseProc`中若HP≥本次伤害+2，且四方向有合法空格，生成下一代，分走受击后约一半HP；子代EXP=0；分裂复制Burning、短Poison及所有revivePersists buff。 | 首选窄处限制分裂出口、已知直接法杖伤害或持续/范围伤害，避免低伤害近战在大厅制造围攻。不能靠推坑消灭飞行蝇群。冠军buff也属于revivePersists，故**冠军蝇群会把冠军能力带给分身**，这是最高优先级危险组合之一。 |
| [螃蟹 Crab](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Crab.java#L29) | 15；1–7；12/5；0–4；移动速度2 | 每0.5时间移动一格，但攻击间隔仍1。肉掉率约1/6。 | 普通速度在开阔直线边退边打会被追上；先利用门/地形锁住相邻数量，较强一次性伤害或减速优先给它。3层前的冠军赋予代码排除Crab，4层开始则可出现冠军蟹。 |
| [寄居蟹 HermitCrab](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/HermitCrab.java#L29) | 25；1–7；12/5；2–6；移动速度1 | 更慢更硬；肉掉率50%，另在不过级时必掉随机护甲。 | 利用速度降低，用射程或控制削血；刀刀低伤容易被DR吃掉，法杖通常比低基础伤投掷更有效。信仰护体削弱护甲收益，不能只因“必掉甲”就付大量HP。 |
| [史莱姆 Slime](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Slime.java#L33) | 20；2–5；12/5；无额外DR | 对传入`damage`的≥5伤害压缩：5/7/10/14/19/25约变为5/6/7/8/9/10，向下截断；并非硬上限5。首次武器掉率20%，此后每次成功掉落使将来概率×1/4；武器为T2、等级0。 | 少量多段伤害相对大单发更有效；不要把唯一大爆发浪费在它身上。压缩对该`damage()`入口普遍生效，不能想当然说法杖无视。保留打螃蟹、冠军和首领的手段。 |
| [酸蚀史莱姆 CausticSlime](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CausticSlime.java#L33) | 继承史莱姆 | 每次成功攻击处理50%附加Ooze，独立于是否正伤害；ACIDIC属性；额外掉GooBlob，仍有英雄等级限制。 | 在可控制的水边战斗，或远程解决；盾挡直伤不保证免Ooze。腐蚀对它有抗性，污泥免疫；不要拿腐蚀资源当无条件强解。 |

蝇群分裂入口与通常法杖 `damage` 入口不同，因此“魔法直接伤害不触发这段分裂”是调用链事实；某件物品如果模拟一次普通攻击或另有覆写则需单独检查。掉治疗药不是九挑战可喝的治疗资源：蝇群基础掉率为 `1/[6×(代数+1)] × (5−既有掉落次数)/5`，既不能无限刷，也不应为它主动分裂。[Swarm.defenseProc/split/lootChance](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Swarm.java#L88)。

**水洗污泥的边界：** Ooze总持续20；下水道普通层每隔一回合1伤，Goo层每回合1伤；首次作用后，非飞行角色在水里会脱离。初次伤害时序可能先发生，站水不是“保证一滴血也不扣”。[Ooze.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Ooze.java#L91)。

## 悲伤幽灵的三种任务敌人

任务在2–4层出现：2层固定臭老鼠，3层固定豺狼诡术师，4层固定巨蟹；不是同一层随机三个。选报酬前看真实给出的装备、力量要求与是否马上能用，九挑战通常更重视能降低击杀时间的武器或特殊能力，不能仅凭甲阶数选高阶甲。[Ghost.Quest.spawn](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Ghost.java#L303)。

| 敌人 | 源码机制 | 针对战士的处理 |
| --- | --- | --- |
| [臭老鼠 FetidRat](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/FetidRat.java#L36) | HP20、EVA5、ACC12，继承老鼠1–4伤，DR老鼠0–1另加0–2。成功攻击1/3附加Ooze；每次`defenseProc`在原位置播种20强度StenchGas；免疫自身臭气。 | 最危险的是**臭气麻痹**，不是普通毒伤。`StenchGas.evolve`对非免疫角色反复续2回合麻痹，可能使战士被后续怪包围。攻击后不能原地连续打：预备撤离格，诱它离开旧气体，水只洗Ooze，**不能洗掉房间臭气**。远程直接伤害绕过该普通受击处理是可用优势，但仍需核实具体工具。 |
| [豺狼诡术师 GnollTrickster](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GnollTrickster.java#L43) | HP20、EVA5、ACC16，继承豺狼1–6伤、0–2DR。只能在不相邻且PROJECTILE线通时攻击；HUNTING移动分支后退。连续命中combo累计，使毒/燃烧更强；发生`getCloser`移动决策时combo清0。 | 不在开阔处追逐挨飞镖；退到拐角/门后诱其前进，再近身截住。逼它移动可重置连击，比原地和它交换远程伤害更划算。堵死逃路后相邻安全性较高，但仍计入附近别的怪。必掉的投掷武器当前实际实现是默认数量的一半向上取整、等级0、去诅咒并鉴定；不要沿用源码旧注释“数量1”。 |
| [巨蟹 GreatCrab](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GreatCrab.java#L42) | HP25、EVA0、继承螃蟹1–7伤、ACC12、0–4DR；速度1且每第3次靠近调用空过。看见当前目标、未睡/未麻痹、对方不隐身时返回无限闪避；相同条件下直接Wand/ClericSpell伤害也被挡，附加效果和环境伤照常。 | 反复门后/拐角偷袭，或隐身/麻痹制造不满足格挡条件的窗口；环境伤也是可选。**“法杖必中”不能破解看着你的巨蟹**，先确认来源和格挡状态。它的慢移是可利用的撤离窗口。 |

[StenchGas.evolve](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/StenchGas.java#L37)明确是Paralysis；该气体不得与ToxicGas、CorrosiveGas互换名称。三者都是MINIBOSS，不能默认魅惑盟友化或恐惧秒退等对普通怪的规则全部有效，详见属性免疫表。

## 监狱：全部普通敌人、稀有变体与召唤物

| 敌人 | HP；伤害；ACC/EVA；DR | 核心危险与可利用点 |
| --- | --- | --- |
| [骷髅 Skeleton](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Skeleton.java#L47) | 25；2–10；12/9；0–5 | 死亡对周围8格仍活着的角色造成6–12爆炸伤，正常`drRoll()`算两次，部分其他减伤也双倍；没有普通攻击命中判定。掉坑死直接跳过爆炸。INORGANIC，免疫流血/毒/毒气。 |
| [小偷 Thief](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Thief.java#L38) | 20；1–10；12/12；0–3；攻击间隔0.5 | 成功攻击尝试偷随机未装备、非unique、等级<1物品的一件，然后FLEEING；持物移速×5/6。若逃跑条件满足、位于视野外且距英雄≥6，会换位置并丢失被盗物（不再可取回）。 |
| [强盗 Bandit](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Bandit.java#L33) | 继承小偷 | 偷窃成功另外致盲5、Poison初值5–6、残废5。第一次额外装备掉落机会1，之后仍按小偷掉落递减。 |
| [DM-100](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM100.java#L40) | 20；近战2–8；11/8；0–4 | 非相邻且MAGIC_BOLT直达目标时每1时间电击3–10魔法伤；相邻走普通近战。INORGANIC+ELECTRIC。25%卷轴掉率。 |
| [狱卒 Guard](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Guard.java#L43) | 40；4–12；12/10；0–7 | 一生一次锁链：看见、不能直接攻击、距离<5、PROJECTILE首先碰到目标且近端不为坑，拉到离自己最近合法空位，给4回合残废。`chainsUsed`持久化。 |
| [死灵法师 Necromancer](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Necromancer.java#L50) | 40；本体不能普通攻击；EVA14；0–5DR | 看见且距≤4、无骨兵时召唤；第一次蓄1，之后2。骨兵起始20/25HP；每次照料优先回最大HP的1/5=5，否则给3回合Adrenaline（攻间隔÷1.5）。会传送看不见或路径太长的骨兵到英雄附近。 |
| [幽灵死灵法师 SpectralNecromancer](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/SpectralNecromancer.java#L40) | 继承死灵法师本体 | 替换`summonMinion`为持续召Wraith，不持有正常骨兵引用；第一次1、后续2的召唤节奏仍继承。召出的Wraith强制`adjustStats(4)`；击杀法师会清理其列表中仍同阵营的幽灵。额外掉祛邪卷轴。 |
| [普通幽灵 Wraith](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Wraith.java#L39) | 1；`1+floor(d/2)`至`2+d`；`10+d`/`5(10+d)`；无额外DR；飞行 | d通常是生成时scalingDepth；生出时enemySeen=true，不因“刚出现”自动吃偷袭。无经验，INORGANIC免毒/毒气/流血。召唤延迟2。幽灵死灵法师版本固定d4，即3–6伤、14/70。 |

**骷髅——把收尾位置当成攻击的一部分。** 战术推论：无甲使“双DR抵挡死爆”的常规舒适打法不可靠。先用近战削弱，再后退一格配合射程/法杖/投掷/控制远处收尾；能安全推入坑则同时免去爆炸，但不能为推坑让自己暴露。爆炸波及其他敌人，存在借一只收掉另一只的机会，也存在连续爆炸杀死自己的风险；精确剩余HP和伤害未知时不要假定能连锁清场。`NecroSkeleton`继承同一爆炸，杀掉法师时它的骨兵仍然`die(null)`，**不等于无爆炸消失**。[Skeleton.die](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Skeleton.java#L71)、[Necromancer.die](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Necromancer.java#L112)。

**小偷/强盗——半回合攻击比“偷一件东西”更先致命。** 战术推论：避免站桩让它连打，遇见时先选好用控制或远程收尾的位置。不要专门让它偷来刷逃跑掉金币；九挑战食物、盾和HP不支持此交易。已经被偷时区分物品是否值得追；强盗带着致盲和残废，盲追可能造成新的更大损失。被偷物在其真正逃掉前击杀可归还，不能承诺事后总能找回。[Thief.speed/attackDelay/steal/Fleeing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Thief.java#L75)、[Bandit.steal](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Bandit.java#L46)。

**DM-100——断线拉近优于站在射线上靠近。** 战术推论：看见它后退到门边或折角，让它自己接近，近战时只受2–8普通伤害而非3–10绕普通护甲的电击。不要用“我的长柄武器能打到”作为交换射击的理由；它即使被你远距近战攻击，仍可能选择魔法。也不要拿雷电杖打它期待一般伤害：ELECTRIC会抵抗对应来源。[DM100.canAttack/doAttack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM100.java#L75)。

**狱卒——先看拉过去后会遇见谁。** 战术推论：利用墙角/其他角色阻挡PROJECTILE可使锁链不能成立，迫其走近；可有意在安全地形消耗这一次锁链，但被拉落点和4回合残废都必须进入预算。锁链拉人本身不靠物理伤害触发，盾不会阻止。存读档不刷新`chainsUsed`。冠军的延伸攻击可能让`canAttack`更早为真，改变何时用链，不能机械套用普通狱卒节奏。[Guard.chain/pullEnemy/Hunting](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Guard.java#L70)。

**死灵法师——本体优先，但先解决骨兵站位。** 战术推论：它不靠普通近战输出，冲近本体后输出效率较高；骨兵被持续治疗时不宜正面互砍。能隔断本体对战斗的视线时，先处理孤立骨兵也可行，但它之后还能再召唤。占住召唤红格不是永久封印：源码会推开占位者；没有推位时，每次可对异阵营挡格者直接造成2–10伤害并再等1回合。召唤物也继承法师的`revivePersists`，包括冠军buff，因此“冠军法师召冠军骷髅/幽灵”要单列处理。[Necromancer.summonMinion/Hunting](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Necromancer.java#L203)、[SpectralNecromancer.summonMinion](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/SpectralNecromancer.java#L109)。

**幽灵——低HP不等于低成本。** 战术推论：门后偷袭、已知直接法杖伤害或符合属性的确定性环境伤是可靠方向；普通站桩攻击高闪避幽灵极不划算。飞行免坑，INORGANIC免毒；不能用毒气或推坑当作清除手段。魔杖有限时优先杀幽灵法师一次清掉其召唤物，避免给没有经验的幽灵逐个支付电量。不要把另一个来源的幽灵误认成属于该法师。

## 法杖匠任务：九挑战仍需认真争取的远程/控制补给

任务7–9层出现，随机三种任务；没有以NO_HERBALISM排除腐莓园的分支。奖励是两种不同类别的法杖二选一，各自清除诅咒并额外`upgrade()`一次；实际等级看奖励公开状态，不能只因“加一次”推成固定+1。[Wandmaker.Quest.spawnWandmaker/spawnRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Wandmaker.java#L333)。以下是先勘察再投入的优先事项。

### 尸尘／墓穴

墓穴门是木障，生成时向该层安排一瓶液火药水；房内有1或2只普通骷髅，尸尘混在骨堆里。不要见到药水就默认任意一瓶是液火，需用鉴别章节的证据规则识别。[MassGraveRoom.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MassGraveRoom.java#L55)。

先清房间骷髅、记住回法杖匠路线，再拿尸尘。携带尸尘每回合累积spawnPower；下一个幽灵所需能量为`min(49,(当前尸尘幽灵数+1)^2)`，故杀得越快，下一只反而可能更快到来。生成在英雄FOV内、不实心且无人占的格；距离必须`>round(hero.viewDistance/3)`。黑暗挑战视距较小使它们可能更近，不能把白天4格距离当定值。交任务消除DustGhostSpawner并驱散这类幽灵。[CorpseDust.DustGhostSpawner.act/dispel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/CorpseDust.java#L90)。

战术推论：安全、已清理的短路线通常比原地耗电杀每只更有价值。为攒某种战斗收益无限刷尸尘幽灵是特殊构筑玩法，不是九挑战战士默认计划；黑暗+集群智能+没有治疗的风险远大于其0经验收益。临时绕门偷袭用于解除堵路即可。

### 新生火元素 NewbornFireElemental

- 模板仍继承Elemental的**60HP**、DR0–5、飞行；本体ACC15/EVA12、近战10–12；敌对新生元素近战不附带普通火元素50%点燃。它是MINIBOSS与FIERY，免Burning，对火杖类有对应抵抗。[Elemental基础](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L68)、[NewbornFireElemental](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L260)。
- 远程冷却满足后，在英雄附近可连通的一格选落点，预警该点周围九宫格非实心格，下一次可行动时发射；射线用`STOP_SOLID|STOP_TARGET`，不靠其他角色挡火球。命中区域先点燃角色，再播火；水格的Fire种子强度较低（2而非8），**水格也不是绝对免疫该火球**。照已经公开预警撤出整个九宫格，不能只离开中心格。[NewbornFireElemental.doAttack/zap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L312)。
- 冰冻/冻伤添加不是通常的减速：FireElemental把Frost、Chill列为harmfulBuffs，`Elemental.add`把每次附加改成`HT/2..3HT/5`直接伤害并拒绝该buff，原生HP60时为30–36。因此冰霜杖的一次基础伤害再加Chill附加可能非常高，但不能在未知等级/抗性时保证一击。[Elemental.add](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L191)、[FireElemental.harmfulBuffs](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L237)、[WandOfFrost.onZap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFrost.java#L60)。
- 寒霜药水播3×3冷雾，每个非实心格强度10；冷雾逐回合尝试Chill，因此若元素持续留在冷雾，反复触发弱点可以很快杀死。**不是“投一瓶当场必死”**：它可能走出，Fire与Freezing同格会互相清除；要选择它下一步仍在雾里的投点，并远离自身。无草不影响药水与法杖，但禁止把寒冰种子种地当计划。[PotionOfFrost.shatter](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfFrost.java#L40)、[Freezing.evolve/freeze](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Freezing.java#L39)。

### 腐莓园：不必杀完鞭藤，千万别烧心脏

| 对象 | 源码事实 | 战术推论 |
| --- | --- | --- |
| [腐败鞭藤 RotLasher](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/RotLasher.java#L37) | HP80、EVA0、ACC25、伤10–20、DR0–8；固定不动、视距1；命中给2回合残废；不相邻于当前敌人时每行动恢复5。ToxicGas免疫。Burning伤害直接destroy而不是正常die。 | 完全不适合无甲战士站桩换血；绕路优先。远程慢慢磨可能被每回合回血抵消，浪费电量；局部烧藤可行但要检查火是否传播到心脏。 |
| [腐败之心 RotHeart](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/RotHeart.java#L37) | HP80、EVA0、无攻击、DR0–5、固定STATIC；每次普通`defenseProc`播ToxicGas，强度`5+3×相邻非实心格数`；免毒气。正常死亡掉腐莓种子并清全层RotLasher。Burning分支只destroy+视觉死亡，**不执行掉种子的die**。 | 不要烧心脏或放任火向它传播，否则可能丢掉任务物。检查安全站位，可用已知非火直接法杖伤害避免这段普通受击播气，或气体防护/分批近战退离等；长柄攻击也走普通受击处理，仍会产气，不能当完全免费。 |

`RotGardenRoom.validPlantPos`生成时维持从入口到心脏可通的路径；心脏附近禁用鞭藤全部8邻格，在更远处只禁4邻格，因此不能把生成保证解读成“整条路径从不贴任何鞭藤的对角格”。源码确实意图不要求杀完鞭藤。实战逐格观察，穿高草后看清下一格，优先找能安全停留并打心脏的位置；九挑战黑暗、集群智能带来的其他普通怪不属于这个静态房间保证。[RotGardenRoom.paint/validPlantPos](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/RotGardenRoom.java#L113)。

## Goo：5层，绝命头目下不能多贪一刀

**源码事实：** 开启STRONGER_BOSSES，生命100→120。EVA基础8，DR0–2；上半血普通攻击1–8、ACC10；半血及以下1–12、ACC15、EVA乘1.5。蓄力伤害区间乘3、ACC乘2，因此狂暴蓄力原始伤害可达3–36。它具有BOSS、DEMONIC、ACIDIC属性，腐蚀不能当无抗性输出。[Goo模板与伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L53)。

1. **蓄力节奏。** 普通攻击决策上半血1/5、下半血1/2概率开始蓄力。一般模式从pumpedUp=0到1再到2；九挑战直接加2，下一次可攻击时就释放。蓄力等待按`gate(attackDelay,ceil(enemy.cooldown),3×attackDelay)`给慢动作最低适应，不是永远固定给两次英雄动作。[Goo.doAttack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L179)。
2. **蓄力范围不是相邻。** 只要距离≤2，且Goo→英雄、英雄→Goo两条`STOP_TARGET|STOP_SOLID|IGNORE_SOFT_SOLID`都通即可攻击；这是少见的双向校验。相邻直退一格仍可能被打，高草这类软阻挡也不可靠。退到距离>2或绕硬墙切断弹道才有几何依据。进入移动追近/后退分支会清蓄力，离开HUNTING也清；预警图像淡出本身不等于任何分支已执行。[Goo.canAttack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L142)、[Goo.getCloser/getFurther](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L243)。
3. **水疗升级。** Goo非飞行、站水且不满血时每act回血，挑战连续为1→2→3→3；离水或没有满足回血条件时重置1。它可因此从狂暴血段回到上半。英雄宜有洗污泥的水格，同时让Goo留干地；不能站水里低伤磨它。[Goo.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L102)。
4. **污泥。** 成功攻击1/3附Ooze，5层每回合1伤。水能洗，但第一次作用时可能先扣1再脱离，不能许诺水中零伤。[Goo.attackProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L155)、[Ooze.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Ooze.java#L91)。
5. **不能无限等盾。** 挑战锁层被动恢复预算初始20、封顶50；每点对Goo有效伤害加1，每点Goo水疗扣1。预算耗尽使`regenOn()`关闭；战士纹章护盾恢复也依赖对应恢复条件。“让Goo水疗，我原地等盾”不能作为无限循环资源。[LockedFloor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/LockedFloor.java#L31)、[Goo.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L275)、[BrokenSeal.WarriorShield](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L324)。

**战术推论：** 开战前看清硬墙绕行回路、干地和水格，留出每个蓄力时至少一个安全动作，检查移动是否受超重/残废拖慢。九挑战攻击力往往不足以抢杀，尽量让普通攻击、投掷或法杖落在“下次出现蓄力仍能退出”的位置。前半血尚可评估交换，半血后蓄力概率翻高且普通伤上限提高，预留控制/远程资源给后段。为了打最后一刀赌36伤不应是默认选项。实际已知盾、HP、法杖电量和目标可达性决定方案，不能仅靠模板HP估算剩几刀。

楼层自身无普通刷怪器、无陷阱，figure-eight连通结构提供绕行机会；激活后入口变水并封层，不能把上楼当作途中撤退。[SewerBossLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/SewerBossLevel.java#L113)。`pumpedUp`与`healInc`明确保存；退出重进不清蓄力、不免费重置水疗递增。[Goo.storeInBundle/restoreFromBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L326)。

## Tengu：10层，陷阱路径与复合危险比近身输出更重要

**源码事实：** STRONGER_BOSSES生命200→250；普通攻击6–12，EVA15、DR0–5；相邻目标ACC10，非相邻ACC20，普通攻击检查PROJECTILE。相邻使普通飞镖命中参数降低，但不能因此忽略追近消耗与地面技能。[Tengu模板](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L89)。

### 伤害分段与阶段推进

`hpBracket=HT/8`整数除法，挑战为31。一次伤害不能跨过多段，过界会夹到`(curBracket−1)×31+1`；跨段后安排跳跃。第一阶段血量≤125时夹回125并进入过场。巨额单发可能浪费，不能一击跳过整个阶段。第二阶段收尾另走胜利逻辑。[Tengu.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L134)。

阶段一跳跃选择与英雄/敌人真距离>3.5的空位，然后清旧陷阱，重做布局。阶段二跳跃要求距英雄5–7、距旧位置≥5且无角色/物堆，最多4次；候选尝试失败可能不动。不能提前把某固定格当成下一落点。[Tengu.jump](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L257)。

### 第一阶段：短暂陷阱图案必须现场记录

- 挑战陷阱密度`0.675 + 普通fill/4`，约78%–90%；生成器保持英雄到Tengu的无陷阱路径，并约束绕行长度。密集不等于必踩。每次跳跃后的公开图案与上一轮不同，历史安全格立刻失效。[PrisonBossLevel.cleanTenguCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/PrisonBossLevel.java#L655)。
- `TenguDartTrap`不可搜索发现、不能把Tengu作为目标；挑战毒初值15，代码注释给累计毒伤50（普通8/17），另有4–8减护甲的飞镖直接伤。隐身角色被视作最远距离候选，**不是彻底不可选中**；用石头压陷阱也可能射英雄，不能用其反打Tengu。[TenguDartTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/TenguDartTrap.java#L29)、[PoisonDartTrap.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/PoisonDartTrap.java#L75)。
- 战术推论：优先用已知可达的远程伤害，少作跨房追近；必须走时只沿当前确知的无陷阱路线。浮空若用于地板陷阱，应按现有持续时间覆盖全部实际路径并留余量；它不帮助二阶段的直接技能伤害。九挑战没有常规治疗兜底，预先保留已鉴定的适用清负面/防护/机动资源，不能用治疗药或种植种子作中毒后补救。

半血过场会移除Tengu；这时其免伤且一般不接受新的debuff。沿过场路线走到触发位置才进竞技场，原地休息不能推进第二阶段；阶段转换清理/重建地图实体，不保证保留第一阶段的盟友、地形或陷阱。[Tengu.add/damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L124)、[PrisonBossLevel.progress](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/PrisonBossLevel.java#L468)。

### 第二阶段：每次技能经常是两种危险一起到

技能目标累计次数`1+2×arenaJumps+max(0,arenaJumps−2)`，4跳后为11。前两次通常炸弹、电击器；之后挑战在两者中选，每次成功使用二者还附带火浪。前两次无合法放置时可退为火浪；和上次相同技能有9/10概率重抽。挑战技能通常花1时间，落后目标次数≥4时甚至不花时间；快速跨血段可能让它追补技能。它有累计预算，不等于无限施法。[Tengu.targetAbilityUses/useAbility](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L484)。

| 危险 | 源码结算 | 战术推论 |
| --- | --- | --- |
| [炸弹](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L568) | 在英雄邻近、较靠近Tengu的非solid位置投放；3、2、1倒数后爆炸。按非solid可通路径半径2；10层15–30伤减护甲；不伤Tengu。 | 离开真实烟雾区域，同时检查所选位置下一拍的火浪和电击。浮空不能躲。炸弹不能捡起（doPickUp=false），也不能留在Boss旁期待反炸它。 |
| [火浪](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L753) | 以WONT_STOP算八方向，前方及左右邻方向扩散，墙体阻挡；前沿先显示后燃烧；非Fire免疫角色会Burning，Tengu特免。 | 优先横向摆脱推进方向或按当前前沿跨到已熄的一侧；每一步仍要与炸弹、电击联判。普通飞行没有免火条件。 |
| [电击器](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L936) | 投英雄相邻、距Tengu≥2且离其他器≥2处；正交/对角交替，中心一直危险。10层每次12电伤，没有普通护甲扣除；不可捡走。 | 最稳是离中心切比雪夫距离>1；必须穿过时按本次公开电格、相位及行动耗时检查，不能用上一次显示判断下一次。浮空不防。 |

三种技能都没有`flying`免伤检查；“喝浮空把第二阶段地板无视”是错误计划。[炸弹伤害段](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L631)、[FireBlob](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L877)、[ShockerBlob](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L1067)。

**视线和控制的限制：** Tengu的`handleUnreachableTarget`即使普通攻击够不到也尝试技能；技能始终瞄英雄，即使普通攻击在打盟友。因此隐身、召唤物挡飞镖、墙角都不能自动暂停二阶段危险。明确免疫Roots、Blindness、Dread、Terror，外加BOSS通用抗性/免疫；不要预留缠绕、致盲、恐惧当作必中的首领控制。[Tengu.Hunting](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L389)、[Tengu免疫](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L351)。

**战术推论：** 首选能边移位边继续生效、且确实适用于BOSS的伤害/防护组合；攻击时只选“这一击之后还有合法避险动作”的位置。不要为了贴身降低普通飞镖ACC，踩进可能12电伤+燃烧+炸弹的复合区。攻击后发生跳跃、新技能或阶段切换时重新审查全局，不连点原目标。可用剩余盾挡不可避免的普通伤，把机动资源留给无安全步的复合危险，而不是追一个预计马上跳走的Tengu。

### 退出重进：本篇涉及的确定持久化与未验证显示刷新

- Tengu技能计数、上次技能、跳跃数、冷却写存档；炸弹坐标/倒计时、火浪方向/前沿、电击器位置/正交对角相位也保存。不能靠正常重登清除它们。[Tengu.storeInBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L358)、[BombAbility保存](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L695)、[FireAbility保存](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L833)、[ShockerAbility保存](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L1028)。
- 阶段一`FadingTraps.create()`重建视觉时调用`setFade()`，但开始消退时对象已从`level.customTiles`移除。**已经淡出的图案重登不会由此恢复**，不能记为普适“读档看陷阱”。[FadingTraps.create/setFade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/PrisonBossLevel.java#L805)。
- 限定、未实测的推论：如果对象尚在customTiles列表的初始延迟内退出保存，重建可能再次显示；新造对象默认fadeDelay=1，原生成时设2，该字段未在这里单独保存。需隔离测试验证，不能作为九挑战必备路线的已证实优势。[FadingTraps字段](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/PrisonBossLevel.java#L791)、[Level保存customTiles](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L421)、[GameScene视觉重建](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L450)。

## 九挑战叠加时，本区域需要覆写的常规直觉

| 组合 | 为什么更危险 | 可利用的条件与限制 |
| --- | --- | --- |
| 集群智能×黑暗 | 敌人持续见到英雄/盟友时，警报半径每时间约+2，封顶12；外围可能在英雄未看清时开始接近。 | 尽早断视线并退到已探索窄口，别在新房门口拖长对战。警报不是“碰到一怪立即全层全部醒”，也不是走出英雄小视野就取消。见[Mob.processSwarmIntel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L315)。 |
| 冠军蝇群、冠军死灵法师 | 冠军buff设revivePersists，分裂/召唤显式复制该类buff。 | 优先用合适的直接法杖/控制处理母体；先看冠军种类，抗魔冠军不能按普通蝇群默认用攻击杖。 |
| 烈焰冠军×脆甲 | 近战伤害×1.25，非水格点燃目标，死亡周边生火；免Burning。 | 非飞行战士在水边可避免其点火条件；收尾后仍检查死亡火区。不能对它用燃烧持续伤作为主解。 |
| 延伸冠军×门口打法 | 近战伤×1.25，能沿不穿实心/角色的路径攻击到4格。 | 一格门口不再保证安全等待；要形成真正的路径阻隔、控制或足够距离。 |
| 巨型冠军×窄门 | 受伤×0.2、2格路径攻击，并获得LARGE。 | 普通狭窄通道的openSpace限制可阻止它走入，但站在门后第1格仍可能被2格攻击。保持额外距离，用现有远程慢打并预算饥饿；它不是免疫所有伤。 |
| 祝福冠军×蛇/幽灵 | 命中与闪避参数×4，使普通对砍更差。 | 偷袭让普通Mob的EVA变0仍有价值；明确不做普通命中检定的伤害可绕过高闪避，但不能忽略本体属性免疫。 |
| 成长冠军×无限拖延 | 从约1.2倍率开始，每4时间+0.01；增加伤害/命闪并降低受伤。 | 越拖越贵；应在合法安全窗口早处理或绕开，不为等满盾无限原地拖。 |
| 无治疗×毒/燃烧/流血 | 受到一次附加异常可能比当次物理伤大，普通治疗药不能作为救急。 | 预留与具体状态匹配的已识别解除/防护；水只处理适用状态，不能一概清毒麻痹。 |
| 无草×任务/种子 | 种子仍可能出现，房间任务仍可能要求腐莓；有种子不证明能种植触发。 | 优先交法杖匠拿本局可用工具，种子用途逐一遵循炼金与挑战章节，不把种草治疗/控制写入作战计划。 |

冠军数值、前期排除（3层及以下蟹、4层及以下小偷、7层及以下狱卒、9层及以下蝙蝠）和能力详见[ChampionEnemy.rollForChampion与子类](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L92)。这不授权从看不见的敌人或内部计数推断下一只的位置/类型；实战只按公开的冠军描述和现场布局行动。

## 实战前的最小调阅清单

1. 1–2层：能否偷袭蛇，是否会被第二只敌人接入，纹章当前是盾还是冷却。
2. 3–4层：螃蟹移动两倍速；冠军蝇群不要近战分裂；有水不等于臭气/毒免疫；被动流放豺狼不要误伤。
3. 5层：挑战Goo只有短蓄力窗口，退一格通常不够；英雄洗污泥、Goo离水；锁层被动恢复预算有限。
4. 6–7层：骷髅末击远离，普通小偷半回合攻击，DM-100先断射线；狱卒可能一次拉走并残废。
5. 8–9层：法师先看骨兵死爆范围；不能占召唤格永久封印；任务拿尸尘前清回程、腐莓心不烧、新生火元素用冰弱点。
6. 10层：记录每跳的新陷阱；半血走路线推进；二阶段每步联判炸弹/火浪/电击，浮空不防这些技能。

覆盖核对：本篇包括Bestiary REGIONAL下水道6种、监狱5种；区域稀有6种；幽灵任务3种；法杖匠新生火元素、腐败鞭藤、腐败之心、尸尘幽灵；普通/召唤幽灵与骨兵；Goo/Tengu及挑战分支。所有数值和战术均需在源码版本改变后重核，尤其4.0.2新增区域稀有怪、新生火元素火球与冷弱点。尚未验证：九挑战战士的具体道具组合成功率、重登FadingTraps短暂刷新、实际相位/动作时间下的无伤路线；这些不能在实战中当保证。
