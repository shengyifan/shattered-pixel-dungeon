# 18 — 主动鉴定与试用：把未知物资及时变成战力

基线：2026-10-14，游戏 4.0.2 / code 922，CLI.9.0.6 / protocol 9；当前仓库 HEAD `bb0c91fb7c6fa8e33819c554427f81b6cf93f2f9`。本文复核了 [07 鉴定与消耗品](07-identification-consumables-alchemy.md)和 [15 炼金计划](15-alchemy-planning.md)中的相关规则，补上用户要求的主动使用策略。源码链接指本次实际读取的文件和行号；**源码事实**与**战术推导**分开，未启动实例、未恢复暂停游戏、未查询控制器，也未读个人存档、profile 或私有 audit。这里没有新的实战成功率或安全回合数验证。

用户策略：积极获取、使用神器；未知药剂和卷轴要在合适窗口积极尝试。执行目标是每段推进前尽量把未知资源转成已知能力，不能因存在风险而无限保留，也不能把未知物当成濒死时必然奏效的急救品。

## 1. 先修正战士的初始未知池

**源码事实：** 所有职业开始时已鉴定鉴定卷；战士额外已鉴定治疗药与狂怒卷。战士没有初始鉴定升级卷。[通用初始知识](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L117)、[战士初始化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L174)

因此正常新局战士的试用计划应从当前公开日记/物品知识确认这些事实后出发：

- **未知普通药不会再是治疗药。** 药物恐惧仍使已知治疗药有害，但不能把“未知治疗会毒死”列为正常战士每一瓶未知普通药的候选风险。
- **未知普通卷不会再是鉴定或狂怒。** 狂怒引怪是真实危险，通常是已知工具的使用选择，不是这位战士盲读普通卷时的随机结果。
- **升级仍在未知普通卷候选中。** 每次主动试读前准备一个确实愿意投入的升级目标，避免鉴定出升级后没有使用计划。稀缺升级候选不拿去盲拆符石或烧能量。
- 普通与对应异种共享种类知识；**已经是异种的未知物必须另列候选池**。普通报复和异种灵爆的自伤不同，普通净化和异种全面净化的防护也不同，不能混成“未知卷/药”一张风险表。[异种药知识映射](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/ExoticPotion.java#L91)、[异种卷知识映射](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ExoticScroll.java#L87)

这修正的是 [07 开头泛化提醒](07-identification-consumables-alchemy.md#先记住这些高价值结论)的适用范围；它不推翻治疗药中毒或狂怒引怪的机制本身。也不以本次源码检查替代恢复游戏后的当前公开物品知识。

## 2. 每次获得未知资源后的主动决策树

以下是**战术推导**。推荐在获得一批新药卷、完成一场危险遭遇、进炼金房、准备离层时各做一次简短检查；不要只在血量见底时打开背包。

1. **先找能立即兑现的目标。** 本层有已见机关房、下一段缺输出/脱困、装备缺力量、神器等着解咒，分别写出需要的工具。已经知道的药卷先正常使用，不为“研究所有未知”耽误眼前收益。
2. **利用同局公开来源缩小候选。** 已见机关房配套药、当前日记、同色堆叠、掉落的明确来源、感知石的猜错结果都能缩小范围。候选足够窄时，用感知石确认；不能把一个高概率猜测写成已鉴定。
3. **有直接安全的识别法就执行。** 关键装备优先鉴定/探魔；明确用途的未知药可用感知石；同种三种子可在锅里制造并识别对应颜色。能量分解或拆石会消耗物品，属于带机会成本的鉴定，不是免费预览。
4. **剩余未知普通药：主动建立试喝窗口。** 高血量、非饥荒、照明充足、无追兵/持续伤害、附近经过检查的落脚区、危险效果出现后仍有可行方案。若手上有多瓶、确需立即获得药剂能力，且没有更紧急的气体房/营养用途，先喝已知普通净化取得 BlobImmunity，再在保护仍充足时逐瓶试用；每瓶后重新观察。它为普通液火、冰霜、毒气、麻痹气体提供实际防护，但不能把保护结束后的地面危险当消失。[净化饮用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfPurity.java#L94)、[20 时间及免疫列表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/BlobImmunity.java#L49)
5. **剩余未知普通卷：提前准备目标后试读。** 带着愿意升级的物品、待排咒/解咒的装备、可以接受嬗变的备用品；选择附近无威胁、有营养和照明余量的地方。若有已知脱困品则保留给可能的传送落点。不要求当前层百分之百清空才读，避免为试卷本身全层绕路。
6. **试用一份后兑现结果。** 得到力量/经验立即计入能力；得到极速/隐形/浮空等短效增益则重新评估可完成的安全路线；识别了控制药就指定下一场用途。不能为了“继续鉴定”让刚获得的短效增益白白倒计时，更不能为避免浪费强行进危险房。
7. **当前窗口不够就指定下一个。** 例如“击退眼前敌人后在已知安全房试一张”“到已见炼金房后用石确认浮空”“补营养并确认无追兵后试药”。只有具体条件不满足时才暂缓；不要把暂缓写成永久不使用。

没有固定“HP 大于某个百分比就安全”的源码结论。麻痹、冰冻或魔法睡眠可能连续推进时间；坠落、精英追击、饥饿和持续伤害会叠加。试用预算应覆盖**恢复可行动能力后的脱离**，不能只覆盖喝/读的那一回合。

### 2.1 地点与顺序的具体准备

- **优先干燥、无可燃物的落脚点，旁边有可进入的普通水，另有不经过试用点的退路。** 水能帮助灭火，但普通冰霜在水中每次增加的寒冷为 5，旱地为 3；不能把“站水里盲喝”称为通用最安全方案。燃烧至少已作用过一次且角色不在飞行时，站水才触发该处熄灭判断；浮空状态不能想当然靠水灭火。[Freezing.freeze](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Freezing.java#L68)、[Burning.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Burning.java#L100)
- **同批先完成需要清晰视野的准备，再读未知卷。** 普通报复可能使自己失明，失明时不能继续读卷；若刚盲喝制造了火/寒区/气体，先退出并处理，不能原地接着盲读。已知普通净化可以先为一批普通未知药建立保护；异种全面净化只有短时拒绝负面效果，不能代替全部 blob 免疫。[读卷前置条件](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L173)、[全面净化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L75)
- **保护贵重物资，避免盲目丢地。** 持续燃烧可能烧掉背包直接携带、非 unique 的卷轴；容器内物品不在这次枚举中。把卷轴/药瓶丢在脚下会暴露给地面火/冰，且有重新拾取成本，不能作为默认保护法。[随身燃烧规则](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Burning.java#L123)、[冰冻影响地面堆](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Freezing.java#L92)
- **楼梯可以是撤离路线，不能证明试用安全。** 麻痹后不保证还能马上走楼梯；传送会离开楼梯；进食、饮用、读卷与追兵各有时间成本。先完成便宜且安全的眼前拾取，再开始可能改变位置/视野的试用，是避免漏拿入口资源的实用顺序。

## 3. 十二种普通药：效果、最坏风险与主动用途

所有普通饮用均走消耗物品、花 `TIME_TO_DRINK`、再应用效果的路径；四种投掷危险药没有独立有益喝法，继承 `apply -> shatter(hero.pos)`。未知有害药不会先弹已知危险药的饮用确认。[饮用与确认](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L237)、[drink/apply](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L288)

| 普通药 | 实际效果与候选风险（源码事实） | 主动使用窗口（战术推导） |
| --- | --- | --- |
| 力量 Strength | 永久 +1 STR；不是战士初始已知药。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfStrength.java#L43) | 最值得尽早识别、兑现的药之一。源于保底层段的单瓶只是候选，先用来源+感知石；没有石但试喝条件成立时，不为等远期炼金拖延成长。 |
| 经验 Experience | 获得当前一级所需经验 `hero.maxExp()`。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfExperience.java#L41) | 试出即取得成长；已知时比较眼前升级与神意启发的收益。不能事后把偶然升级算成盲试前保证回血。 |
| 治疗 Healing | 战士初始已知；先清列出的负面，再在药恐下施 `Poison(4 + lvl/2)`，不回血。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfHealing.java#L52) | 保留作炼金原料/能量。已知治疗不加入本局未知普通药试喝池；不为了触发战士药剂护盾而主动付毒伤。 |
| 灵视 MindVision | 加 MindVision 并更新观察。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfMindVision.java#L40) | 试出后立刻利用公开观察规划绕敌、定位或配合已知视野卷；不据效果推读隐藏存档。 |
| 极速 Haste | 加 Haste。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfHaste.java#L39) | 预先有一段安全移动任务时收益更好：回楼梯、贴近已知目标、绕开追兵；不把移动加速当攻击/喝药加速。 |
| 隐形 Invisibility | 加 Invisibility。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfInvisibility.java#L40) | 试出后考虑安静离开/绕敌/鱼池；逐次核原生隐形是否仍在，别边盲用物品边假设隐形持续。 |
| 浮空 Levitation | 喝后浮空；扔碎则产生 1000 体积混乱气体，二者不同。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfLevitation.java#L44) | 试出时只跨能在结束前落回安全地面的已知缺口；它不能直接代替羽落跳楼计划。扔未知瓶可能给自己添混乱气区。 |
| 净化 Purity | 喝后 20 时间 BlobImmunity；扔碎清理可达范围内对应 blob；不会补营养。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfPurity.java#L55)、[保护](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/BlobImmunity.java#L49) | 首瓶试出后可利用剩余保护继续一小批未知普通药，逐瓶核时间/地面；更缺营养时保留下一瓶变 Cleansing。 |
| 液火 LiquidFlame | 碎点九宫格非实心格各播 2 单位火，喝即脚下生火。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfLiquidFlame.java#L40) | 已知时烧木障、预置敌人路线；未知试喝需有 BlobImmunity 或能承受起火并实际可用的灭火/离场方案。不可在唯一可燃出口实验。 |
| 冰霜 Frost | 九宫格非实心格播 10 单位 Freezing；逐次寒冷并可能冻结，水中更快积累。[药剂](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfFrost.java#L40)、[寒冷到冻结](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Freezing.java#L68) | 已知时灭火、冻敌、封追路；未知试喝需能承受失去行动窗口，不能贴着追兵或在单出口水道中做。 |
| 毒气 ToxicGas | 碎点播 1000 体积毒气；喝在脚下，后续扩散伤害不等于一次固定伤害。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfToxicGas.java#L39) | 已知后主动远投以省近战换血；未知试喝应有保护或空旷退路，且不能把紧贴试用点的窄门算永久安全。 |
| 麻痹 ParalyticGas | 碎点播 1000 体积麻痹气；持续在气体内会重复延长麻痹。[药剂](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfParalyticGas.java#L39)、[重复延长](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/ParalyticGas.java#L41) | 未知普通药最应提前消除的风险之一：优先通过房间排除/感知/净化保护识别。有粮光、无追兵但低血仍不是充分条件；不能把“旁边有楼梯”当喝完一定能走。已知后用于远端控群并立即兑现输出或脱离。 |

**投掷鉴定的正确使用：** 液火、冰霜、毒气、麻痹、浮空、净化在碎点可见时会识别；力量等有益药继承普通 `shatter` 时通常仅碎裂，不识别且无饮用收益。投掷也会先 `pressCell`，可能触发目标陷阱；井/深渊走物品掉落逻辑。适合“来源已强烈指向危险药，并能顺便打敌/解机关”的试投，不适合把全部未知瓶逐个砸掉。[投掷分流](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L308)

## 4. 十二种普通卷：试读准备比长期囤积更有价值

| 普通卷 | 实际效果/最坏边界（源码事实） | 提前试读的准备与后续用途（战术推导） |
| --- | --- | --- |
| 鉴定 Identify | 战士初始已知，选择未完全鉴定物；遗忘碎片另有处理。[选择与鉴定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfIdentify.java#L57) | 用于能立即改变主装/诅咒决策的物品。若全部关键装备安全而药剂未知阻碍推进，也可以主动用一张确认关键药，不把“必须永远留给装备”当硬规则。 |
| 狂怒 Rage | 战士初始已知；遍历本层 mobs 向读卷位置 `beckon`，当前 FOV 非友方给 5 时间 Amok。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRage.java#L45) | 有计划地让可见敌人互斗、把敌人引到预先布置区；立即改变位置/脱离。无敌人在眼前也会有远处引怪成本，别只为消库存乱读。 |
| 升级 Upgrade | 未知时已在选择前消费；选中后还有 WndUpgrade 确认。[InventoryScroll](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/InventoryScroll.java#L41)、[二次窗口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfUpgrade.java#L64) | 试读前明确愿意升级的对象和理由；得到关键等级/力量门槛便立刻兑现，不因担心长期最优装备而浪费这一张。 |
| 祛邪 RemoveCurse | 通常选择装备/法杖；可用于诅咒尚未知物并标记已知。若相邻有痛苦灵魂则优先净化灵魂，直接消耗。[分支/可选条件](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRemoveCurse.java#L55)、[解咒](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRemoveCurse.java#L119) | 准备待解咒神器或值得排咒的未明装备即可，不必先穿上制造诅咒。想解自己装备时先避开痛苦灵魂的邻格。 |
| 嬗变 Transmutation | 对允许目标改变类别，可能失去原物用途；装备目标会执行重装备流程。[可选目标](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTransmutation.java#L70)、[执行](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTransmutation.java#L100) | 准备一个确实愿意换的多余/不适配物品。不要为避免“浪费已读卷”去变掉唯一脱困或必需机关材料；没有合适目标时可确认放弃，明确承认损失一张。 |
| 传送 Teleportation | 普通未锁 RegularLevel 优先未走过、可通行、非秘密且无人占据的候选；不是偏向已知安全房。若没有候选才转普通随机逻辑。[读卷](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L65)、[候选算法](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L147) | 试读前保留移动/控制应急能力、营养和照明；传送后重新观察立即立新计划。后期可用来脱围/找未探索处，也可拆成两个定点闪现石。 |
| 报复 Retribution | 可见 mobs 受伤并可能失明；自己始终获得虚弱、失明，**该方法不直接扣英雄 HP**，即使没有目标。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRetribution.java#L45) | 空闲时可付负面与时间换身份，随后离开试用循环处理状态；已知后用于多个可见敌人且自己血少、可兑现击杀/撤退的场合。不要把它当安全无代价的群攻。 |
| 催眠 Lullaby | 可见 mobs 与英雄都获得 Drowsy，随后 MagicalSleep；英雄满 HP 则睡眠立即解除，否则睡眠每时间步回 1 HP 至满或受伤被打断。[卷](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfLullaby.java#L44)、[睡眠](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MagicalSleep.java#L37)、[受伤解除](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L901) | 确有安全恢复窗口时主动用，能绕开药恐的治疗药惩罚；饥饿会伤害并打断，不能饿着期待睡满。盲试时若血量较低，必须预算不能行动的恢复过程。 |
| 恐惧 Terror | 对可见非友方施 Terror；没有目标照样识别并耗卷。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTerror.java#L43) | 安全空读身份有未来价值，但已知时保留用于完成跨越、清门口或撤退，别恐惧后又原地浪费窗口。 |
| 镜像 MirrorImage | 尝试在身边合法空格造两个镜像；空间不足可能少造或失败，仍消耗识别。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfMirrorImage.java#L45) | 试读留出邻近空格；已知时在开战前或拉开距离后造，帮助分担敌方行动。刚试出则评估能否带它们完成下一段。 |
| 充能 Recharging | 给 Recharging，不会因为无可充法杖而返还。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRecharging.java#L45) | 若已持法杖且可以安全正常使用，试读前让充能有收益；不为防止可能浪费而硬射墙/打危险敌。 |
| 探地 MagicMapping | 标记可发现地图并揭示秘密地形，空读也获得当前层信息。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfMagicMapping.java#L43) | 在还剩有价值未探索区、准备找出口/机关路线时试读更有效；不用为了最大化收益先盲闯新层再读。 |

### 4.1 未知目标卷不能“打开窗口，再取消白赚鉴定”

**源码事实：** `InventoryScroll.doRead` 在 `!isKnown()` 时先 `identify()`、`detach()`，才打开选物窗口。没有选目标时，会出现“确认浪费此卷”对话框；确认浪费会花读卷时间，拒绝则重新选物。已知普通目标卷通常到选择后才扣除，所以取消已有知识的卷与取消首次未知卷不同。未知升级虽有额外确认窗，返回也回到原选择流程，不能据此恢复那张已经 detach 的卷。[首次读与取消](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/InventoryScroll.java#L38)、[选择后处理](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/InventoryScroll.java#L115)、[升级窗口返回](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndUpgrade.java#L497)

**执行建议：** 把目标准备当主动试卷的常规步骤，而非必须等到装备齐全。只要有愿意升级的有效目标、一个待排咒的候选、一个可接受嬗变的备用品，大部分目标卷即可有用；若其中一类没有好目标，仍可在收益值得时接受那张可能浪费的代价。不能为补齐这三项而穿诅咒装备、拖延关键成长或额外冒险囤垃圾。

### 4.2 灵爆是异种卷：无人时仍会自伤

普通报复无直接英雄伤害不适用于灵爆 `PsionicBlast`。灵爆先收集 FOV 内 mobs（并不只筛敌对单位），然后给英雄发起 `round(HT × 0.5 × 0.9^N)` 的伤害请求；`N=0` 时仍为四舍五入的 **50% HT**，之后若活着再施失明与五倍常规虚弱时长。实际扣血还经过角色伤害链、护盾/抗性等，不能把公式直接称为所有构筑的固定 HP 损失。[完整调用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfPsionicBlast.java#L47)

**战术推导：** 未知异种卷不使用普通卷的空房试读标准。先用公开来源/感知石/鉴定解决可能的灵爆，或在真实能承担自伤的战场使用；“没有敌人所以更安全”在这里恰好失去减少自伤的目标数收益。已知灵爆可配合灵视扩大实际 FOV 目标，但应先核敌人抵抗、同伴与自己的血量/护盾；不把敌法精英计入必杀名单。

## 5. 房间、感知石和炼金：积极建立知识，减少无目的盲用

### 5.1 房间保证的是本层生成池，不是某一瓶身份

已公开看见下列结构后，可把相应药加入“本层至少有解法来源”的推理。药可能被房间、容器等生成安排放在别处；多瓶未知药或多机关房并存时不能唯一绑定。颜色在每局随机分配，不能沿用上局红/绿/蓝瓶身份。[颜色分配](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/ItemStatusHandler.java#L44)

| 已见机关/房间 | 本层配套药 | 怎样主动利用 |
| --- | --- | --- |
| 木障储藏室 | 液火；[StorageRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/StorageRoom.java#L57) | 缩小颜色候选，用感知石或有收益的安全试投确认；确认后烧开障碍取物。 |
| 魔法火墙 | 冰霜；[MagicalFireRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/MagicalFireRoom.java#L112) | 保留冰霜候选，不把它先分解；确认后清火形成可走路线。 |
| 毒气房 | 普通净化；[ToxicGasRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ToxicGasRoom.java#L107) | 先解决房间或明确跳过，再把多余净化转换成营养。 |
| 普通陷阱房 | 浮空；[TrapsRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/TrapsRoom.java#L122) | 感知确认后估算往返路线；这与向下跳入坑房是不同问题。 |
| 食人鱼池 | 隐形；[PoolRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PoolRoom.java#L91) | 未拿奖励时保留原版隐形或已知替代方案，不先加工成不等价烟雾。 |
| 哨卫房 | 极速；[SentryRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SentryRoom.java#L161) | 确认入口、奖励和退出步数后利用，不把慢一些的异种精力回复视为同一解法。 |

力量药的区段保底和物品来源也有用，但“该层应该还有力量”不是“眼前唯一未知瓶一定力量”的证明。详细生成机会与掉落例外保留在 [07 来源推断](07-identification-consumables-alchemy.md#固定供给和来源推断)。

### 5.2 非盲用方法的成本与主动优先级

| 方法 | 可靠知识/成本（源码事实） | 适合现在做什么（战术推导） |
| --- | --- | --- |
| 感知符石 StoneOfIntuition | 猜药/卷/戒指种类；正确才揭示。每次确认都会计数，英雄全局 tracker 的第一猜保留石，第二猜消耗；猜错也算。戒指只揭种类。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfIntuition.java#L113) | 给机关配套药、力量/升级候选和会改变构筑的戒指用，不长期压着等“100% 确认才猜”。两种候选时第一猜错便能支持第二猜；每次仍需读实际公开结果，不能把剩下类别自动写成原生已知。 |
| 鉴定卷 | 对一个目标鉴定，遗忘碎片例外；[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfIdentify.java#L57) | 关键神器/主装诅咒或等级决定下一段路线时立即用。未知药阻止解机关且其它方法成本更高时也可使用。 |
| 鉴定→两颗感知石 | 0E，原卷变两颗石；每两次确认消耗一颗，次数由英雄共用 tracker 决定：当前无 tracker 时两颗可确认4次，已有 tracker 时只剩3次；都不保证猜中。[映射/产量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L310) | 多个高置信来源候选，比单个装备完整鉴定更急时可转；有关键未明神器/装备时保护最后鉴定卷。 |
| 鉴定→预知卷 | 6E；从所有未知药/卷/戒指池随机揭最多四类，未必持有，戒指仅种类。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfDivination.java#L53)、[成本](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ExoticScroll.java#L121) | 前期未知很多且主要生存包已经足够时有持续价值；不保证解手上那个神器诅咒。 |
| 魔力探测石 | 标记诅咒知识；报告负面魔法/正面魔法，可两者都有；正面不是确定升级数，也不解咒。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfDetectMagic.java#L45) | 主动筛选新神器、戒指、法杖，发现无咒即可在安全窗口装备并使用；不必等整套装备全鉴定。 |
| 未知卷拆两石 | 0E、消费一卷并识别；未知合成预览只显示通用符石占位。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L343) | 适合已缩小且确定可牺牲的重复品；未排除升级时通常先试读/感知，避免一张永久升级换两石。 |
| 药卷能量分解 | 消耗所选数量，先计算未知价值、后鉴定；未知高价值物不会先变已知 10E。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndEnergizeItem.java#L143) | 已知治疗优先；为了马上做出净化/逃生而必须补能时，可有意识牺牲一份非关键候选。分解一份后可识别同色余量，不默认整叠。 |
| 三同种种子 | 0E，三种子映射出对应药并识别；阳春草 Sungrass治疗另有限量重抽，识别发生在重抽前，不能保证重抽后的随机瓶也已知。[实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L468) | 荒芜下种子仍可变成药与颜色知识，魔皇草→净化尤其有后续营养价值；不拿唯一任务腐莓凑不存在的三颗保底力量。 |

异种转换本身保留共享知识，不会免费识别；未知配方预览占位不是颜色泄露。完整炼金收益排序见 [15](15-alchemy-planning.md)，本章不把所有未知都转异种再喝。

## 6. 装备和神器也要主动把“拥有”变成“可用”

**源码事实：** 饰品穿戴成功时立即激活、揭示诅咒并花装备时间；神器在此后直接 `identify()`。因此神器不用靠升级经验慢慢识别，但诅咒可能已经生效。正常受咒装备不能卸下；“试一下再脱”不保证可撤销。[饰品装备](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/KindofMisc.java#L146)、[神器识别](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/Artifact.java#L65)、[诅咒卸装限制](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/EquipableItem.java#L126)

**主动决策：** 新神器到手 → 看公开来源、诅咒已知性、空槽与当前可用动作 → 有探魔/鉴定则先排雷；已有祛邪且无追兵、能承受装备和解咒耗时，也可安排受控试戴 → 无咒即装备，把充能用在下一段的具体目标上。祛邪能直接用于未鉴定且诅咒未知装备，不需要为了合法选中而先戴上。没有排雷工具时，根据神器具体诅咒、现在装备的替换成本和下一商店/资源机会做有意识选择，不能把所有神器无限期关在背包，也不能假定“神器都应立即盲戴”。[祛邪可选条件](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRemoveCurse.java#L87)

戒指要分清宝石种类和个体等级/诅咒；战士没有盗贼的初始识戒特权。无咒戒指可尽早穿戴参与正常经验获取，普通实现约积累一级经验比例后识别，不是站着等时间。武器/护甲通过正常使用与经验补充额度识别；战士老兵直觉 +2 穿甲即识别，但仍会面对穿戴诅咒，不能为鉴定故意挨打。[戒指经验识别](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/rings/Ring.java#L329)、[识别倍率](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L683)、[完整装备识别规则](07-identification-consumables-alchemy.md#装备被动鉴定不是无限刷次数)

## 7. 九挑战特有的预算与实战记录要求

- **药物恐惧：** 治疗与护盾类不可套普通局回血预期；但正常战士治疗初始已知，所以主动尝试未知普通药的风险主要是液火、寒区、毒气、麻痹及短效药浪费。已知不稳定合剂在药恐下排除治疗，并重抽应投掷的危险药，适合把已确认治疗转换为可用随机有益效果；它没有力量结果，也不保证抽到具体救命药。[UnstableBrew](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/UnstableBrew.java#L67)
- **节食：** 普通食物的营养除三；试用造成的不能行动时间依然要纳入预算。不要在饥荒中尝试让催眠恢复、等麻痹自然结束或连续试读失明后的卷。全面净化直接对 Hunger 满足到 450，绕开的是 Food 除三路径；它不直接回血。[Food](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L114)、[Hunger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L78)、[Cleansing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L75)
- **荒芜：** 普通种植在生成植物前直接返回，不能把种阳春草 Sungrass/冰冠花/魔皇草当这局的盲试后保险。种子炼药、药镖、特定神器途径仍应分别评估。[种植阻止点](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1068)
- **黑暗与精英：** 眼前没有敌人不证明全层安全；短视距更需要已确认的邻近地形和出口，精英最坏接战成本应包含在脱离预算。不能据未出现事件推断没有远处追兵。
- **每次试用留下决策记录：** 当前公开候选、选择方法、愿付的 HP/时间/材料成本、实际识别结果、现在可用的新能力、下一用途。未知结果只在该次公开反馈确认后写成已知；不要引用死后揭示、存档、旧颜色、旧句柄填补行动前知识。

本章已做静态源码复核；“已知普通净化保护下一批未知普通药”“安全催眠用于补血”“提前备好目标后试读”等是源码支持的条件策略，**尚未用新隔离实例或本局实战验证具体节奏**。未来获准继续游戏后按当前公开状态逐步实施，不预定连续多动作脚本，也不因研究完成自动恢复游戏。
