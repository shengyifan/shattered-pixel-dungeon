# 洞穴、矮人都城与 DM-300：九挑战战士源码手册

核对基线：上游 4.0.2 / code 922，项目提交 `8405ae82f`；2026-10-10。这是静态源码研究，没有启动游戏、读取个人存档或进行实战验证。数值表是普通敌人的初始值，**不是当前敌人的秘密 HP**；冠军、Buff、返程升华挑战会另行修正。实战依据当前公开描述、血条、地形、动作和预警更新决策。

本页覆盖洞穴 11–14 层与都城 16–19 层的全部常规轮换及其稀有替代、15 层 DM-300。矿洞见 [04b 黑铁矿洞](04b-blacksmith.md)，20 层首领见 [04c 矮人国王](04c-dwarf-king.md)，都城新金库支线见 [04d 金库](04d-imp-vault.md)。

## 先读：怎样解释下表

- `伤害` 是原生 `damageRoll()` 范围，通常为 `NormalIntRange` 的近似钟形分布；不是必然伤害，命中、护甲、易伤、冠军等另算。`命中/闪避` 是判定属性，不是百分比。`DR` 是该敌人额外随机物理减伤，普通父类基础为 0。
- 所有下列普通敌人默认移速 1、普攻耗时 1；吸血蝙蝠移速 2，武僧普攻耗时 0.5。**移速 2 不表示每回合普攻两次**。`Mob.attackDelay()` 与 `Char.speed()` 分开；天生快速移动的蝙蝠仍使用普通攻击耗时。[Mob.attackDelay](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L791)、[Char.baseSpeed](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L175)
- 萨满/术士远程命中调用 `hit(..., true)`，基础有 2 倍命中倍率；然后直接 `enemy.damage()`，不走普通普攻的 `drRoll()` 扣减路径。九挑战下物理甲本已很薄，更不能指望它挡这些法术。[Char.hit](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L615)、[物理攻击 DR 路径](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L388)
- `UNDEAD` **本身不附带毒、流血免疫**。只有 `INORGANIC` 在这里提供 `Bleeding/ToxicGas/Poison` 免疫；它没有通用的火、腐蚀、寒冷、电击免疫。不要凭外观认定亡灵免毒、机器人免电、金属怕电。[Char.Property](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1417)
- `LARGE` 只能在满足 `openSpace` 的格子移动，但这不等于隔着门一定打不到英雄。DM-200 的喷气、魔像的拉人专门解决“不能走过去”；巨型冠军也需额外检查。[DM200.Hunting](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM200.java#L140)、[Golem.Hunting](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Golem.java#L208)
- 本页“战术”均是上述规则的推论，未保证随机结果或当前物品可用。九挑战同时有黑暗、群聚、冠军、药剂失效与薄甲等制约：先确认安全撤退、同帧全部敌人和当前冠军属性，再选下面的单敌解法。

## 生成进程：哪层开始准备哪类威胁

原生轮换列表会洗牌使用；下表是每批候选的权重，不是该层精确敌人数。食尸鬼的列表单位还可能生成成对个体。

| 深度 | 常规轮换权重 |
| --- | --- |
| 11 | 蝙蝠 3、豺狼暴徒 1、萨满 1 |
| 12 | 蝙蝠 2、暴徒 2、萨满 1、洞穴蜘蛛 1 |
| 13 | 蝙蝠 1、暴徒 2、萨满 2、蜘蛛 2、DM-200 1 |
| 14（轮换函数也有 15 分支） | 蝙蝠 1、暴徒 1、萨满 2、蜘蛛 2、DM-200 2 |
| 16 | 食尸鬼 3、元素 1、术士 1 |
| 17 | 食尸鬼 1、元素 2、术士 1、武僧 1 |
| 18 | 食尸鬼 1、元素 1、术士 2、武僧 2、魔像 1 |
| 19（轮换函数也有 20 分支） | 元素 1、术士 2、武僧 2、魔像 3 |

14 层的罕见跨区追加是食尸鬼，19 层是魅魔：在追加环节各为 2.5%，不是每个刷怪位 2.5%。9 层也可能提前加蝙蝠。15/20 层正常首领楼层不据此表随意推断会刷常规怪。14/15 的源码注释误写 `2x DM-300`，实际构造为 `DM200.class`。稀有替代基础概率 1/50，再乘鼠头骨饰品系数：暴徒→装甲暴徒，DM-200→DM-201，武僧→大师。元素自己的 `random()` 做混沌替代。萨满普通颜色 40% 红、40% 蓝、20% 紫；元素非混沌部分 40% 火、40% 冰、20% 电。[MobSpawner](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/MobSpawner.java#L126)、[跨区/稀有](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/MobSpawner.java#L214)、[萨满颜色](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Shaman.java#L184)、[元素颜色](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L621)

## 洞穴逐敌

| 敌人 | HP | 近战伤害 | 命中 / 闪避 | DR | 特殊节奏 |
| --- | ---: | --- | --- | --- | --- |
| 吸血蝙蝠 Bat | 30 | 5–18 | 16 / 15 | 0–4 | 移速 2，飞行；伤害超过 4 的部分可吸血 |
| 豺狼暴徒 Brute | 40 | 5–25；狂暴 15–40 | 20 / 15 | 0–8 | 第一次致死进入耗尽式狂暴护盾 |
| 装甲暴徒 ArmoredBrute | 40 | 5–25；狂暴 15–40 | 20 / 15 | 4–12 | 狂暴消退极慢 |
| 红/蓝/紫萨满 Shaman | 35 | 5–10；法术 6–15 | 18 / 15 | 0–6 | 远程法术及颜色特定负面 |
| 洞穴蜘蛛 Spinner | 50 | 10–20 | 22 / 17 | 0–6 | 毒咬、预判三格蛛网、退走 |
| DM-200 | 80 | 10–25 | 20 / 12 | 0–8 | 大型无机；远距或卡路喷毒气 |
| DM-201 | 120 | 15–25 | 20 / 12 | 0–8 | 固定位置，受远程伤害后喷腐蚀气 |

### 吸血蝙蝠：薄甲环境下优先降低交换次数

源码 `attackProc()` 先取得普通攻击结算后的伤害，恢复 `min(damage - 4, HT - HP)`，仅当结果大于 0。这是**按结算伤害**吸血；不是每次攻击固定回血，也不是基于英雄实际扣到 HP 的量（护盾还在后面受伤路径）。飞行使普通坠落/地面限制方案不能机械照搬；死亡时才清除 flying。正常治疗药水掉率约 1/6 并受全局已掉数量递减，不能当九挑战补血来源。[Bat](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Bat.java#L33)

战术推论：蝙蝠 30 HP 是适合早期集中投掷/法杖削血再近战收尾的目标。不要指望普通移速一路后退摆脱两倍移速；用门角伏击、可靠控制、地形隔断或者真实速度优势。薄甲让其成功攻击更容易超过吸血门槛，拉长站撸会同时损失血量与输出。攻击后观察其公开血条是否上升；不要把低血误判为下一刀必杀。

### 暴徒：普通狂暴适合避战，装甲狂暴不能照搬

普通暴徒第一次生命耗尽会触发 `BruteRage`，原始护盾设置 `HT/2 + 4 = 24`，触发耗时 1，之后护盾每 tick 吸收 4 的自耗伤害；表现上约为 20 点衰减护盾、约 5 个后续 tick，但应按实际公开护盾和时间结算观察，不能把初始化 24 写成固定 6 次免费行动。HP 恢复为正会移除狂暴，坠落死亡不触发狂暴。狂暴伤害为 15–40，远高于普通 5–25。[Brute.isAlive/triggerEnrage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Brute.java#L83)、[BruteRage.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Brute.java#L128)

装甲暴徒继承 40 HP、命中/闪避，DR 加 4 成为 4–12；狂暴原始护盾为 21，每 3 tick 仅自耗 1，源码明确按约 60 回合衰亡设计。掉鳞甲为 3/4、板甲 1/4（均正常随机品质）；这不能推导出九挑战值得硬吃其伤害。[ArmoredBrute](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/ArmoredBrute.java#L37)

战术推论：普通暴徒先规划杀入狂暴后能后退的几格，再打致死伤；初始发怒耗时能给脱离机会，但冠军投射/速度效果可能取消传统风筝的安全性。装甲暴徒则准备控制与魔法/腐蚀等非物理手段削护盾，或真正隔离后离开；为等死绕 60 回合同时支付饥饿、黑暗和群聚刷怪成本，通常很亏。坠落直接解决普通/装甲暴徒是原生例外，但必须用当前已知落点、可击退性与弹道证明，不能对巨型/不动等当前状态盲推。

### 三色萨满：把远程 6–15 变成近战 5–10

萨满用 `MAGIC_BOLT` 检查射线。相邻或射线不能抵达敌人格则调用普通近战；否则 1 tick 发射。法术命中后 50% 追加负面，同时直接造成 6–15 魔法伤害。

| 颜色 | 命中法术时的 50% 负面 | 对战士的实际影响 |
| --- | --- | --- |
| 红 | Weakness 20 | 普通攻击伤害在减甲前乘 0.67，延长清怪时间 |
| 蓝 | Vulnerable 20 | 普通攻击经过护甲后伤害乘 1.33，薄甲时格外危险 |
| 紫 | Hex 30 | 命中与闪避抽样均乘 0.8，不能理解为直接减 20 个百分点 |

[Shaman.canAttack/zap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Shaman.java#L72)、[颜色效果](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Shaman.java#L151)、[Weakness/Vulnerable 结算](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L479)、[Hex 判定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L646)

战术推论：优先退到门后/拐角诱其接近，用遮挡改变其能攻击的方式；在开阔直线上直接向它走几步会连吃法术。冲锋、链子等强制位移只有已有装备且成本合适时考虑；普通长柄武器隔一格刺它仍可能处于其法术模式。三色中按当前场面排序：旁边有多个近战怪时蓝更危险，主要依赖普通武器收尾时红/紫显著增加回合成本。3% 初始法杖掉率每成功掉一根再乘 1/3，不能为刷法杖长期停留。[Shaman.lootChance](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Shaman.java#L78)

### 洞穴蜘蛛：真正陷阱是“中毒后追它”

命中后独立 50% 施加 7–8 的 Poison，调用 `set()` 取剩余值与新值较大者，然后蛛网冷却清零、进入逃跑；毒每 tick 按 `floor(left/3)+1` 伤害，随后递减，不能把 7–8 当作总伤害。蜘蛛有 Poison 抗性（非免疫）和 Web 免疫。[Spinner.attackProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Spinner.java#L119)、[Poison.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Poison.java#L108)、[蜘蛛抗性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Spinner.java#L219)

蜘蛛按敌人上次位置→当前移动方向延长射线，朝下一格及两侧共 3 格铺网；目标不移动时改瞄自己与敌人之间。若自己不在逃跑、敌人没移动且当前可近战，就不花招铺网。射网检查 `STOP_TARGET | STOP_SOLID`，不能穿实墙；网量 20，冷却 10，在狩猎/逃跑行动时递减。蜘蛛看到目标不再中毒会回到狩猎。[webPos/shootWeb](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Spinner.java#L136)、[Hunting/Fleeing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Spinner.java#L227)

踏网会清掉所踏网格并施加 5 回合 Roots；飞行不自动免 Web（占格检查在飞行分支之前）。网还把该格 `solid=true`、`flamable=true`，会影响法杖射线。物品重压可清网，需核对真实投掷落格，不能把网当普通空地。[Web](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Web.java#L61)、[Level.occupyCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1164)、[Level.pressCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1306)

战术推论：最经济是毒咬前杀/控；已中毒时优先退出混战与处理持续伤害，不顺着它预判的网路盲追。换方向、后撤到掩体让它返回、远程收尾均可能优于追逐。九挑战“荒芜”使种子种地解毒思路失效，使用已核实可用的解毒/清除方式；纯净对气体和已附着毒不是同一件事。网可烧，但火的扩散、物品损坏与自身燃烧必须一起算。

### DM-200：卡门有价值，但不产生无条件安全射击位

大型、无机，80 HP；面对有视野但不相邻的敌人，按距离尝试随机喷气，正常追路失败且能喷也会喷。`canVent()` 建立不穿 `solid` 的距离图，允许绕少量墙角，最大路径为棋盘距离 +1；因此它不采用标准直线 `MAGIC_BOLT` 的可射击判定。实际气线却是 `Ballistica.STOP_TARGET`：途经格各 20 毒气、目标 100，冷却 30 次本体 act。[DM200](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM200.java#L38)、[canVent](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM200.java#L117)

战术推论：窄门挡体型后先准备退离气体覆盖的路线，再远程输出；射线挡住视野/打得到与毒气会不会来到是不同问题。它免疫自身毒气，不会用喷气慢慢毒死自己。腐蚀不是 Poison，源码无无机通用腐蚀免疫，可用时是高 HP/DR 机器人的候选答案。近身输出虽能迫其普攻，10–25 在薄甲下依然昂贵，不应把“少吃魔法”当成“近战安全”。

### DM-201：绕过通常优于试探性远程打一下

120 HP，固定位置，不追不逃。受伤时，若伤害来自不相邻角色，或当前敌人不存在/不相邻，则置 `threatened=true`（Corruption 对象例外）；看到敌人后优先喷腐蚀：目标 15 气量、周围非实心格各 5，腐蚀强度 8，然后清 threatened。它覆盖 `canVent=false`，不用普通 DM-200 的毒气冷却机制。[DM201](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM201.java#L35)

战术推论：不必击杀时不要从远处“顺手打一发”，安全绕行有明确价值。必须杀时，远程打—转移要为每次反击留空间；持续伤害也可能再触发 threatened，不能只按首次喷雾规划。相邻进攻通常避免这一远程反击触发条件，但其 15–25 普攻不适合九挑战硬拼。它掉额外金属碎片且有等级上限，材料价值不等于生存收益。

## 矮人都城逐敌

| 敌人 | HP | 近战伤害 | 命中 / 闪避 | DR | 主要变化 |
| --- | ---: | --- | --- | --- | --- |
| 食尸鬼 Ghoul | 45 | 16–22 | 24 / 20 | 0–4 | 成对生成；附近同阵营食尸鬼可托管复活 |
| 火/冰/电/混沌元素 Elemental | 60 | 20–25 | 25 / 20 | 0–5 | 飞行，间歇远程属性效果 |
| 矮人术士 Warlock | 70 | 12–18；法术同范围 | 25 / 18 | 0–8 | 远距魔法穿普通护甲，50% 降级 |
| 矮人武僧 Monk | 70 | 12–25 | 30 / 30 | 0–2 | 普攻 0.5 tick，专注挡一次命中判定 |
| 武僧大师 Senior | 70 | 16–25 | 30 / 30 | 0–2 | 移动使专注恢复更快 |
| 魔像 Golem | 120 | 25–30 | 28 / 15 | 0–12 | 大型无机，能把敌人传到身边 |

### 食尸鬼：处理存活宿主，比补打倒地者重要

初始 `partnerID=-1` 的普通食尸鬼第一次 act 会在四向可行邻格生成伙伴，双方记录 partner，伙伴继承 `revivePersists` Buff（包括冠军）。生成权重 0.5。伙伴不是唯一复活源：**任意同阵营食尸鬼**满足“能看到尸体，或距离 <4”就可以托管。死亡后撤出当前 Mob/Actor 集合、留下倒地表现，`timesDowned*5` tick 后复活，HP 为 `round(45/10)=5`；第二次倒地等待更长。托管者移至看不到尸体且距离 ≥4 会断开，找不到新宿主才真正死亡。掉进深渊直接终结，不走托管。[Ghoul.act/die](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Ghoul.java#L104)、[GhoulLifeLink](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Ghoul.java#L235)、[searchForHost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Ghoul.java#L358)

战术推论：门口串行处理，击倒一只后尽快打倒全部可托管宿主，或把剩余宿主引到 ≥4 距离且断视线。只离开英雄视野不够，判定是宿主视野；一墙之隔但距离 3 也还可能互救。不要站在尸体上以为阻止复活，代码会挑附近空格，实在无空格才每 tick 重试。复活时多数非 `revivePersists` 负面已清，不能认定旧中毒会继续完成击杀。冠军食尸鬼会把冠军性质复制给伙伴，因此看到第一只就要预算两只的风险。

亡灵属性对棱光法杖有 4/3 伤害加成，鲜血法杖对它造成伤害并给施法者屏障，不是魅惑；这些是可用资源的特殊匹配，但必须遵守法杖成本和当前冠军抗魔。[棱光](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfPrismaticLight.java#L88)、[鲜血](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfTransfusion.java#L121)

### 元素共同框架与四种不同解法

元素飞行；狩猎时远程冷却递减，初始/射后为正态 3–5，`canAttack` 在冷却 <0 且 `MAGIC_BOLT` 通畅时允许远攻；近邻仍普攻。普通远程命中后执行属性效果，不额外调用普通 20–25 伤害。某些元素的相克 Buff 在 `add()` 被拦截，转为 `HT/2…3HT/5` 即普通 60 HP 时 30–36 直接伤害，Buff 本身不附着。[Elemental 基类](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L68)

| 元素 | 原生效果 / 抗性 | 战士可用对策 |
| --- | --- | --- |
| 火 | 近战 50% 给非水中目标点燃；远程命中给非水中目标约 4 的 Burning。FIERY：免 Burning/Blazing，抗火焰法杖和火元素。被施加 Frost 或 Chill 转成 30–36 相克伤害 | 水格能阻止该元素这两条点燃路径，但近战 20–25 仍在；冰霜/寒冷来源有额外杀伤价值，勿用火攻 |
| 冰 | 近战 1/3，或英雄在水里则必定调用 Freezing.freeze；远程命中必调用。ICY：免 Frost/Chill，抗冰霜法杖/冰元素；施加 Burning 转成 30–36 相克伤害 | 优先离水再打；火能造成相克伤害。别把单次 Freezing.freeze 说成直接必冻住：它先累计 Chill，干地 +3、水中 +5，到上限才 Frost |
| 电 | 近战以 Shocking 链电，周边受 0.4 倍此次结算攻击伤害；主目标在干地会从额外链电列表去掉，在水中则保留。远程命中给 5 回合致盲。ELECTRIC 是雷电相关伤害抗性，非全免 | 干地、与其他角色拉开，降低连锁；黑暗挑战下额外致盲可能直接毁掉撤退视野。电法杖对它不是优先解 |
| 混沌 | 近战命中还触发随机合法诅咒法杖效果；远程跳过普通命中判定，直接 cursedZap；掉嬗变卷轴 | 不以闪避保命，不为保证卷轴硬顶随机效果。优先短战、遮挡、可靠控制；“混沌可能帮我”不是计划 |

[火元素](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L227)、[冰元素](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L497)、[电元素](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L525)、[混沌元素](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L575)、[Freezing.freeze](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Freezing.java#L68)、[元素属性抗性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1427)

相克的 30–36 是原始触发伤害，当前抗性/冠军可能再修正，不承诺一瓶必杀。新生火元素属于监狱法杖匠任务，参数、预告火球和普通都城火元素不同，见早期敌人章节。

### 矮人术士：在围攻场景中优先断它的射线

相邻/射线不通时普通近战，否则 1 tick 暗影弹；法术命中后 50% 给英雄 `Degrade` 30 回合，然后直接造成 12–18。降级不会永久改装备，它在有效等级计算时把正等级变为 `round(sqrt(2*(level-1))+1)`；+0/+1/+2/+3 基本不变，+4→+3、+6→+4、+10→+5。正常 50% 药水掉率与治疗药水限额不能成为九挑战的回血来源。[Warlock](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Warlock.java#L43)、[Degrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Degrade.java#L32)

战术推论：虽九挑战升级少，精投主武器/戒指/法杖仍会受降级显著打击。拿“肉盾怪”挡暗影弹需实际 `MAGIC_BOLT` 射线成立，不能凭画面重叠。优先转角引近，避免一边打魔像/食尸鬼一边被术士射。长柄站两格外可能持续挨法术；成功贴身后仍需计算 12–18 普攻，在薄甲和冠军加成下不能无限站撸。亡灵匹配的棱光/鲜血法杖仍适用。

### 武僧与大师：专注优先于普通伏击必中

武僧普攻 0.5 tick，高命中 30、闪避 30、低 DR。狩猎状态若没有 Focus 且冷却 ≤0，会挂 Focus。专注且未麻痹/未睡眠时 `defenseSkill()` 直接返回 `INFINITE_EVASION`；`Char.hit()` 明确“无限闪避优先于无限命中”，**绕门伏击不能保证打穿当前专注**。一次需要命中判定的攻击被挡时调用 `defenseVerb()`，消耗 Focus 并设 6–7 冷却。正常花时间降低冷却，移动额外减 0.67；大师再额外减 1.66，合计约每步减 3.33，因此不停拉扯会使其不断恢复格挡。[Monk](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Monk.java#L37)、[Senior](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Senior.java#L28)、[无限判定优先级](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L636)

战术推论：可在有距离时用低价值攻击消掉已显示的专注，再在冷却内集中输出；这仍消耗时间、投掷耐久，必须有安全射线。直接 `damage()` 型法杖不走近战命中判定，可穿过“是否命中”的专注层，但并不等于必然移除 Focus；下一次普通攻击仍可能被挡。麻痹会使该 Focus 无限闪避分支失效，是另一原生控制窗口。不要拿最贵的连击终结/慢速重击测试一枚仍存在的专注；普通硬拼可能在一次慢攻击内吃到多次 12–25。大师必掉馅饼，但食物挑战会限制饱食收益，不能按普通局“稳赚大餐”估值。

### 魔像：堵窄门后的拉人会绕拐角

魔像 120 HP、25–30 近战、0–12 DR，大型无机。移动受窄路限制，但若看到且不能近战，会按距离随机尝试拉人，追路失败则直接尝试。拉人需要 cooldown≤0、目标非 IMMOVABLE，以及不穿实墙、路径长度至多直线棋盘距离+1；代码注释明确可绕挡路地形。拉人耗时 1，选其八邻可通行空格中相对英雄原位置最远的格子，将目标送到身边，冷却 20 次 act；MagicImmune 防此传送。闲逛受阻时还可能蓄 2 tick 自传送，成功后 30 act 冷却。[Golem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Golem.java#L40)、[teleportEnemy/canTele](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Golem.java#L147)

战术推论：远程输出仍很好，但每个所谓“卡门位”都需包含被拉到魔像旁边后的安全预算。最糟是拉进术士/武僧交叉火力；先分离、致盲/控住或打断敌方视野再清。它无机免毒不免腐蚀，HP 高使持续腐蚀可有性价比；需要严格看气体与自己路径。强制传送发生后立即重读地图与全体敌人，之前的逃跑方向和射线全部作废。掉落高阶装备概率会递减，绝不为了装备把 25–30 当可承受日常消耗。

## DM-300：九挑战下是 400 HP、三座激活塔、最后阶段持续电地

### 强化前后准确差异

| 规则 | 普通 | 开启 Badder Bosses（九挑战实际） |
| --- | --- | --- |
| 本体 HT | 300 | 400 |
| 阶段触发 | 200、100 HP | **300、200、100 HP** |
| 必须摧毁的激活塔 | 2 | **3**（场上总共 4 座） |
| 每座塔 HP | 50 | **80** |
| 进入超载额外蓄力时间 | 3 | **2** |
| 正常可达时能力冷却抽样 | 5–9 | **5–7**，实际触发为计数 `> cooldown` |
| 喷毒气数量倍率 | 1 | **2**（不等于每 tick 伤害翻倍） |
| 落石伤害 / 麻痹 | 6–12 / 3 | **10–20 / 5** |
| 每 tick 塔邻格电击 | 相对方向 0、4 | **0、3、5 三个方向**，每次旋转一步 |
| 地图非水空格生成线路概率 | 1/8 | **1/4** |
| 打伤首领/塔增加 LockedFloor 恢复计时 | 伤害值 | 约伤害值/2 |

来源：[DM300 初始化/冷却](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L81)、[阶段阈值](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L479)、[落石](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L690)、[Pylon](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Pylon.java#L49)、[地图](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CavesBossLevel.java#L123)

本体近战 15–25、命中 20、闪避 15、DR 0–10；大型无机首领，免 Sleep，抗 Terror/Charm/Vertigo/Cripple/Chill/Frost/Roots/Slow。BOSS 属性还抗 Grim、即死陷阱、复仇/灵爆，免 AllyBuff 与 Dread。麻痹没有出现在其专属免疫清单，但不能由此承诺任意来源都能施加。普通“腐化拉一个首领当盟友”被首领免疫阻止。[DM300.stats](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L93)、[DM300 抗性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L677)、[BOSS 属性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1417)

### 四个会让计划失败的细节

1. **阈值硬钳位，超载完全免伤。** 伤害跨过阈值后 HP 被重新钳到阈值并进入超载；大招不能一次跳过多阶段。超载移速变成 2，但普通攻击耗时仍独立。不能以“最后一发很强”继续轰无敌本体；立刻转向激活塔。[damage/supercharge](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L499)
2. **站最近塔旁边不会让最近塔被激活。** 激活从仍中立的塔里排除离英雄最近的一座，再随机选其余；不是固定顺/逆时针，也不保证最远。图中的电流流向是公开寻找激活源的线索；不要用粒子动画推导新的攻击 revision，也不要从源码/存档偷看当前隐藏选塔。[activatePylon](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CavesBossLevel.java#L382)
3. **未通电线路能给本体屏障。** 本体狩猎时踩 `INACTIVE_TRAP` 且该格无 PylonEnergy，`Barrier.setShield(30+(HT-HP)/10)`。这是设定屏障，不是永久加最大 HP；也不是站在水上回血。让它反复踩线会白白增加输出成本。[DM300.move](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L321)
4. **第三座塔破坏后，电地持续到本体死亡。** `eliminatePylon()` 只有剩塔数 >最终剩塔数才 fullyClear；九挑战最终剩 1 座。最后 100 HP 阶段虽然本体不再超载，电线/水格仍能伤人。胜利 `unseal()` 才再次清场。[eliminatePylon](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CavesBossLevel.java#L411)、[unseal](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CavesBossLevel.java#L348)

### 毒气与落石：不能靠视线外或窄墙永远躲

正常可达且有视野时，能力第一次随机各半，后续有 75% 倾向切换另一种；非相邻使用能力的这条分支不额外花 1 tick。敌人不可达时，从计数≥5 就更积极放技能；用 30° ConeAOE 检查喷气角度，不能喷或目标无机则落石，目标已经麻痹则避免再用这条落石路径。不能把“藏在大型怪走不过的缝里”理解成取消其能力。[DM300.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L159)

喷气沿 STOP_TARGET 射线：强化目标 200 气量、途经每格 40，并补足本体周围数量。气体生成延迟到目标下次行动之前，避免小数回合即时结算伤害；下一步仍要赶快脱离，不能把延迟误读为气体没产生。无机本体不吃毒气。纯净类气体保护/机动资源必须以当前物品规则核实。[ventGas](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L373)

落石：相邻先尝试击退 2 格，距离 2 且有视野尝试击退 1 格，之后以预计落点为中心从 7×7 采样危险格；中心邻居会选一个免落石的 safeCell，但该格仍可能是墙/电地，**“没有红色落石警告”不等于安全可走**。延迟钳在 1–3 tick，依据目标已有冷却向上取整，公开标出实际落石格。被命中 10–20 伤害再麻痹 5，对九挑战极易连锁致死。先依据被击退后的新位置和预警集合，挑可达、不带电、不在毒气中的格子。[dropRocks](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L409)

超载时普通绕路失败会砸毁附近可挖墙与光墙，强化砸墙耗时 2；因此石柱可拖时间但不能永久挡住本体，路线会被改写。隐身会阻止其这一段直接追踪英雄的 `beckon`，但不能把已有目标、旧路线或已经启动的危险视为清空。[超载追踪](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L286)、[getCloser 砸墙](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L607)

### 塔与电地是两套伤害

- 激活塔 80 HP，不动、无机、电属性、STATIC；中立塔免伤且拒绝所有 Buff，不能提前削血。激活后也免 STATIC 所列控制，因此不要用恐惧/冰冻去停止它转电。塔单次原始伤害≥15时压缩为 `14 + floor((sqrt(8*(dmg-14)+1)-1)/2)`：输入 15/17/20/24/29/36 变为 15/16/17/18/19/20，然后还走正常抗性。高额单发效率受限，快而可靠的输出常更省时间。[Pylon.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Pylon.java#L183)
- 塔每 tick 电八邻中 3 格，10–20 电伤，不排除飞行目标；只排除 DM-300。每次方向前进 1，当前闪电是已经施放的效果，不是永远固定安全邻格。长柄/远程可以免去贴塔旋转圈风险，但仍要处理追来的本体和地面电。
- 地面 PylonEnergy 则使带电格上**非飞行**、非 DM-300 的角色每次 blob evolve 受 6–12 Electricity。激活时电线、水、特定装饰入网，后续水也即刻补入。因此悬浮可越电地，**悬浮不免塔邻格电击**；倒水/造水也可能扩大电区。[Pylon.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Pylon.java#L95)、[PylonEnergy.evolve](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CavesBossLevel.java#L890)
- 地图生成验证所有塔可通过不踩水/电线的路线到达；这证明有干路，不证明最短路无危险，更不保证战斗中没有气体、本体或改地形。[干路连通检查](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CavesBossLevel.java#L181)

### 九挑战战士进场前与逐阶段计划

以下是规则导出的资源预算模板，未替代当前局的装备选择：

1. 带够照明观察实际路线，把 3 次去塔的移动算进食物/光照/充能预算；别只按本体 400 HP 估耗时。准备至少一套能摆脱超载追击的机动或遮断方案，以及有效打塔输出。普通回血药在药剂挑战下不能列入保险。
2. 本体阶段尽量在干地输出，避免诱其连续踩未通电线路刷屏障；能力预警出现时优先保命而非抢一刀。临近每个阈值先恢复动作余裕、确定多方向撤退口，再触发超载。
3. 看到超载/激活迹象后确认源塔，选当前已知干路或有完整持续时间余量的悬浮路线；不要朝“最近塔”自动移动。速度优势、可砸墙石柱与真正遮挡能减少被追打次数。
4. 对塔按当前公开放电方向、剩余血条与攻击耗时选择站位。慢武器会跨过多个方向变化；便宜多次攻击可能比大招更划算，不能只看面板伤害。击破后重新观察本体/电地，不沿用旧模式。
5. 最后 100 HP 阶段保留机动和至少一个对落石的应急位，牢记电地还在。若资源不够，不以“退出重进”期待重置本体 HP、塔阶段或危险：上述关键计数都有存储路径；具体重载细节由独立重载章节审查。

## 组合威胁与版本核对清单

| 当前公开组合 | 为什么危险 | 决策优先级 |
| --- | --- | --- |
| 蜘蛛毒/网 + 萨满 | 移动受限期间持续吃法术，网又改变弹道 | 先解除围困/断法术线，别追逃跑蜘蛛 |
| 蝙蝠 + 狂暴暴徒 | 蝙蝠快移堵路，狂暴暴徒单击极高 | 致死暴徒前先保留不会被蝙蝠占掉的撤退格 |
| DM-200 卡门 + 后排萨满 | 以为门是永久安全，实际气体赶人再吃法术 | 先确认喷气后的退路和第二个掩体 |
| 食尸鬼群 + 术士 | 复活拖长战斗，降级降低处理速度 | 断术士射线，再按托管源清食尸鬼 |
| 武僧 + 魔像 | 普攻被挡同时魔像拉人，慢武器来不及脱离 | 不在可被拉进的格子消专注；先分离 |
| 黑暗 + 冰/电元素 | 水中减速/致盲让未知格与远程敌人接管战场 | 火把状态和已知退路先于追杀 |
| 任意冠军版本 | 普通控场/法杖/门口范围假设可能失效 | 先读该个体当前冠军属性，再套本页基础模型 |

需要重新核对的触发：游戏版本/源码 hash 变化；出现未列出的公开能力；当前敌人名字同类但属性/预警不符；进入升华返程；物品、饰品或冠军改变了时间/范围。未覆盖的混沌随机效果详表与具体物品配方由装备/物品章节负责，本页不把随机收益当成可靠战术。
