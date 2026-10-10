# 九挑战战士：职业机制、天赋和构筑决策

核查日期：2026-10-10。源码基线：`8405ae82f`，游戏 4.0.2 / code 922。本文是源码研究，**没有启动游戏、没有操作真实存档、没有把理论构筑冒充九挑战通关验证**。老版本笔记只用作检索线索，下面数值已重新核查执行代码。`p` 表示对应天赋点数，`HT` 为生命上限。链接定位的是当前仓库文件与方法；后续更新应重新核对行号。

## 1. 职业定位与开局事实

战士的优势来自纹章、天赋、子职业，而不是额外的隐藏基础力量或生命。Hero 初始力量 10、攻击技能 10、防御技能 5，基础生命上限 `20+5×(等级−1)`；升级加攻击/防御而不自动加力量。九挑战会同时降低护甲及补给收益，不能把“战士”理解为允许站桩交换大量 HP。

来源：[Hero 字段与初始化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L205)、[updateHT/STR](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L260)、[升级流程](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2075)。

| 开局内容 | 当前源码事实 | 九挑战用途 |
| --- | --- | --- |
| 破旧短剑 | 已鉴定、T1；通用近战公式给基础 1–10 伤害 | 近战收尾；不要把决斗家的武器主动技能当成战士技能 |
| 投石 | 已鉴定，默认3枚；T1 基础 2–5 伤害，`baseUses=5`、`sticky=false` | 远处削血、拉怪、之后角斗士起连；石头不是无限耐久 |
| 布甲与破损纹章 | 布甲已鉴定，纹章附在布甲上 | Faith Is My Armor 没有删除开局护甲或纹章 |
| 口粮、水袋、绒布袋 | 共通开局物品；挑战的 `isItemBlocked` 这里只屏蔽露珠 | Barren Land 下水袋仍存在，但不能当作有露珠可喝 |
| 已知消耗品类型 | 战士治疗药剂、盛怒卷轴已识别；所有职业鉴定卷轴已识别 | 不代表开局赠送了这些药卷，也不代表治疗药在恐药症下安全 |

来源：[HeroClass.initHero/initWarrior](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L100)、[Challenges.isItemBlocked](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Challenges.java#L71)、[WornShortsword](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/WornShortsword.java#L30)、[MeleeWeapon.min/max](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/MeleeWeapon.java#L249)、[ThrowingStone](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/ThrowingStone.java#L28)、[MissileWeapon.min/max](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L100)、[MissileWeapon.defaultQuantity](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L357)。

## 2. 破损纹章：必须用当前机制理解

### 2.1 护盾不是随时间永久再生的旧版护盾

当前纹章盾上限为 `3 + 2×护甲阶数 + 钢铁意志点数`，**不读取护甲强化等级**，虽然方法仍接收 `armLvl` 参数。布/皮/链/鳞/板基础上限依次 5/7/9/11/13，加满钢铁意志后为 7/9/11/13/15。需要纹章仍附在当前装备的护甲上。见 [BrokenSeal.maxShield](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L95)、[WarriorShield.maxShield](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L372)。

在 `Char.damage` 内，满足以下全部条件时，**本次扣伤前**自动补入一份上限护盾：

1. 伤害来源不是 Hunger，最终待扣伤害 `dmg>0`。
2. 当前 `HP<=HT/2`，或本击将使 `HP + 当前全部盾 − dmg <= HT/2`。这里 `HT/2` 是整数除法。
3. 存在 WarriorShield 且 `cooldown<=0`。

触发执行 `incShield(maxShield())`，然后 `cooldown=max(0,cooldown+150)`；它没有“当前纹章盾必须等于零”的限制。因此角斗士减冷却至负值时，下一次符合条件的伤害可在原盾尚存时继续补入。来源：[Char.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L957)、[WarriorShield.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L352)。

普通正冷却每 tick 减 1，条件是 `Regeneration.regenOn()`。**这个函数只检查 LockedFloor 的再生开关，不检查是否饥饿**：饥饿会阻止自然回血，但不会单独停止纹章冷却。饥饿伤害本身绕过所有盾，且不能触发纹章盾，故不能拿“还有盾”当作饥饿安全线。来源：[WarriorShield.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L323)、[Regeneration.act/regenOn](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L56)、[ShieldBuff.processDamage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ShieldBuff.java#L109)。

当有剩余纹章盾、看不见敌人且没有 Combo 时，累计 5 个衰减单位后清空剩盾；若仍在冷却，退款 `150×剩盾/initialShield÷2`，最多正常一份盾的 75 回合。见敌或存在 Combo 会重置离战计时。`HoldFast` 把累计速度降至 0.5/0.25/0：不动如山 3 点且不移动时，剩盾不会因这个离战计时自然清空。它仍会被攻击消耗。来源：[WarriorShield.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L328)。

**九挑战决策推论：** 高阶甲即使物理 DR 被削弱，仍可提供更大纹章盾，不能一律卖掉；但力量不足的护甲降低移动和闪避，可能得不偿失。纹章盾只是半血后的缓冲，不是主动维持半血的无条件理由。不得为了等 150 回合冷却在饥饿或刷新敌人的区域空等。

### 2.2 可转移 +1 与刻印的边界

- 纹章可单独升级一次；附纹章的护甲首次升级会把纹章升级到 +1。拆下时旧甲降 1 级，装上新甲时新甲升 1 级；不是复制升级，也不会把旧甲全部等级搬走。
- 新甲诅咒情况未知时不能直接附纹章；诅咒甲还有相应限制。盲穿甲即使借老兵直觉识别，也会先承受原生诅咒装备后果。
- 刻印只有在**纹章附着期间执行 `Armor.inscribe`** 才记入纹章。原甲已经带刻印，不代表后来附上的纹章自动记住它。
- 刻印转移 1 点可转 common/uncommon；2 点可转所有类型，包括 rare 和 curse。无适用天赋时拆下会清掉纹章持有的刻印。可先保留天赋点，确认要转移的实际刻印后、拆下前再分配。

来源：[BrokenSeal.isUpgradable/affixToArmor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L126)、[Armor.affixSeal/detachSeal](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L306)、[Armor.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L492)、[Armor.inscribe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L726)、[BrokenSeal.canTransferGlyph](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L72)。

## 3. 天赋点预算与 T1/T2

正常 T1/T2/T3/T4 分别从 2/7/13/21 级开始，共 5/6/8/10 点；单项上限分别 2/2/3/4。T3 还需要子职业，T4 还需要护甲能力。神圣灵感可给对应层额外 2 点，不改变单项上限。下面样例不假定会得到它。来源：[Talent.tierLevelThresholds](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L435)、[Hero.talentPointsAvailable/bonusTalentPoints](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L393)。

### 3.1 T1：5 点，四项选配

| 天赋 | +1 / +2 的执行效果 | 九挑战价值与边界 |
| --- | --- | --- |
| 丰盛一餐 HEARTY_MEAL | 吃食物时 HP/HT `<0.334`，直接回 4 / 6 HP | 没有按饱食度缩小，节食不把它除以 3；也没有治疗药剂/恐药症钩子。是少数可规划的额外回血。不要为多回 2 HP 把自己送进敌人一击致死区 |
| 老兵直觉 VETERANS_INTUITION | 近战武器鉴定进度 ×1.75 / ×2.5；护甲 +1 实际 ×2；+2 装备即鉴定，点满时也识别已穿甲 | +1 护甲描述写 2.5，但执行 L694 是 `1+p`，即 2；明确采用代码。被遗忘碎片抑制被动鉴定时改设 ID-ready。鉴定发生在装备后，不能防诅咒 |
| 受衅怒火 PROVOKED_ANGER | 任一种盾被 `ShieldBuff.processDamage` 打破，得 5 回合 tracker；下一次成功进入物理 `onAttackProc` 的攻击加 3 / 5，随后消耗 | 普通近战、投武均能利用；Barrier自然衰减及纹章离战清空不触发，狂暴自然耗盾特例走processDamage可触发。多层盾同一击破掉也不是无限叠层；未命中不从此路径消耗加伤 |
| 钢铁意志 IRON_WILL | 纹章盾上限 +1 / +2 | 稳定但边际线性；提高甲阶每阶 +2 盾不占天赋点。受装备与可安全触发次数约束 |

来源：[Talent.onFoodEaten](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L587)、[itemIDSpeedFactor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L683)、[onTalentUpgraded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L503)、[onItemEquipped](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L832)、[ShieldBuff.processDamage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ShieldBuff.java#L124)、[Talent.onAttackProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L868)、[英文描述](../../core/src/main/assets/messages/actors/actors.properties#L908)。

**候选分配（策略推论，不是强制模板）：**

- 装备身份尚不明、需要安全评估新甲：老兵 2、丰盛 1、受衅 1、钢铁 1。尽量用已排除诅咒的装备做瞬间鉴定，不靠随机盲穿。
- 早期 HP 最紧、已有可用且身份明确的装备：丰盛 2、受衅 1、钢铁 2；放弃鉴定便利，换 2 HP/餐和多 1 盾。需要真的有可吃资源，而不是虚构丰饶之角。
- 若伤害决定能否少挨一击：受衅 2 可胜过钢铁第二点，但必须有会破盾且能在 5 回合内兑现的战斗节奏。

### 3.2 T2：6 点，五项选配

| 天赋 | +1 / +2 的执行效果 | 九挑战价值与边界 |
| --- | --- | --- |
| 钢铁之胃 IRON_STOMACH | 普通食物进食 3→1 回合；剩余吃饭时间内伤害 ×0.25 / ×0 | +1 已拿到快吃；+2 可把吃饭的一次时间窗口当确定性伤害防护，但不是清除附加异常状态。零耗时食物未必产生免伤窗口 |
| 液蕴意志 LIQUID_WILLPOWER | 符合药剂使用钩子时 `round(factor×HT×0.065/0.10)` Barrier | 与原 Barrier 取较大值，不累加；会自然衰减。不动如山可保盾。力量/经验及指定衍生物 factor=2；高产炼金物另受触发概率限制 |
| 刻印转移 RUNIC_TRANSFERENCE | 1 点转 common/uncommon，2 点包含 rare/curse | 在获得值得保留的刻印后点；不是每局开局预付两点 |
| 手起刀落 LETHAL_MOMENTUM | 合格敌人击杀归因时 67% / 100% 产生 tracker，使对应攻击耗时归零 | +2 把随机变确定，适合群体压力下收尾再移动。并非任意法杖/毒气击杀都免费 |
| 即兴投掷 IMPROVISED_PROJECTILES | 非 MissileWeapon 物品抛到敌人格时，失明 2 / 3 回合，冷却 50 | 普通可回收废物也能作一次控场；仍遵守 PROJECTILE 碰撞及物品本来效果。对免盲对象会浪费冷却，需查敌人 |

来源：[Food.eatingTime/execute](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L74)、[Talent.onFoodEaten](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L597)、[Hero.damage 食物免伤](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1670)、[Talent.onPotionUsed](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L711)、[Item.cast](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java#L641)、[Mob.die](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1039)、[Hero.attackDelay](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L782)。

液蕴意志最容易被描述简化误导，执行时应检查：

1. 饮用需非 anonymous 且通过 `talentChance`；普通药剂通常直接触发。
2. 投掷必须落在正常地格并属于 `mustThrowPots`、`canThrowPots` 或 Brew；**不是任意药剂摔掉都有盾**。力量、经验等纯饮用药剂扔地不在投掷白名单。
3. 投入井或深渊走基类抛掷，不走此处药剂碎裂奖励。
4. 酸蚀、毒气、火焰等进攻投药可同时触发液蕴与即兴失明；但失明不阻止已经生成的气体伤害，也不保证远程特殊能力的每个实现都取消。
5. `ShieldBuff.setShield` 是 max 语义；在高 Barrier 未耗尽时连喝低盾药剂并不叠盾。`Barrier.act` 每 tick 累积 `min(1,shield/20)×HoldFastFactor`，累积到 1 损失 1 盾。

来源：[Potion.drink/onThrow](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L288)、[投掷药剂集合](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L108)、[ShieldBuff.setShield](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ShieldBuff.java#L65)、[Barrier.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Barrier.java#L45)。

**手起刀落的归因细节：** `Mob.die` 接受 cause 为 hero、Weapon 或 Weapon.Enchantment；投掷命中回调也显式消费 tracker 并不花攻击时间。这是代码归因，不是按名字分“物理/魔法”的绝对分类：个别护甲能力/范围效果若归因为 hero 也可能触发，而掉落深渊、独立气体、普通法杖等通常不同。不要用这个天赋预测一切伤害来源；特例核对具体 `damage(...,source)` 和何时消费 tracker。来源：[Mob.die](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1039)、[Item.cast 回调](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java#L674)。

**候选分配：** 没有关键刻印时，`手起2、即兴1、钢胃1、液蕴2` 是资源循环方案。魔法/远程威胁优先确保即兴 1；确知下个战斗需要吃饭免伤时可升钢胃 2，并让液蕴或其他尚无收益的一点；已获得关键稀有刻印且准备换甲时，先比较刻印转移 2 对这些即时战斗收益的取舍。天赋点可以暂存，不能假设未来必掉某件物品。

## 4. T3 共同天赋：九挑战的重要补偿

| 天赋 | +1 / +2 / +3 | 执行细节 |
| --- | --- | --- |
| 不动如山 HOLD_FAST | 原地额外 DR 1–2 / 2–4 / 3–6；连击及相关护盾衰减乘 0.5 / 0.25 / 0 | 等待一次后绑定当前位置，离开即失效；DR 加在 Hero.drRoll 的甲与武器分支之后，**不经过 Faith Is My Armor 的甲 DR 公式** |
| 力大无穷 STRONGMAN | 增加基础 STR 的 8% / 13% / 18%，向下取整 | 计算只乘 `Hero.STR` 基础字段，不把力量戒/肾上腺激素临时增益再乘一次 |

来源：[Hero.rest](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1505)、[HoldFast.armorBonus/buffDecayFactor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/HoldFast.java#L54)、[Hero.drRoll](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L643)、[Armor.DRMin/DRMax](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L379)、[Hero.STR](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L277)。

例如基础力量 14 时加成是 +1/+1/+2，16 时 +1/+2/+2，18 时 +1/+2/+3。升级点只在跨越真实装备门槛或产生下一点有效力量时最值钱，不能把 13% 永远记成 +2。

不动如山 3 点可以在安全的原地战斗站位保存 Combo、Barrier、纹章离战消散计时和狂暴盾，但**不冻结饥饿、敌人刷新、怪物攻击、DOT、火焰或法杖消耗**。不能把“保连击”写成无限无代价刷资源。恶魔眼、蝎子、Yog 预警等需要移动时，离开危险格优先于保天赋收益。

## 5. 角斗士：用命中、控制、免费收尾换取资源效率

### 5.1 连击生成与维持

普通近战和投掷武器成功命中合格敌人都 +1 Combo；不是必须造成正 HP 伤害，也不是法杖施法自动加。普通命中把短计时补至至少 5 回合；击杀或特定满血腐化转换把计时置为 `15+15×连战热忱点数`。未命中不自动清零，但照样花时间，计时可能过期。来源：[Hero.shoot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L471)、[Hero.onAttackComplete](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2354)、[Combo.hit/act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L90)。

投武的价值不只伤害：在安全距离先命中可更早解锁 2/4/6 连击动作。短视野九挑战下仍需当前公开敌人格和合法射线；不能靠源码预测隐藏敌人位置起连。

### 5.2 五个战技与真实范围

| 连击门槛 | 动作 | 执行结果 | 九挑战用法与陷阱 |
| --- | --- | --- | --- |
| 2 | 冲击 CLOBBER | 基础伤害乘 0；必中攻击流程；推 2 格；命中敌人再 +1 连击；每条连击只能用一次，不清连击 | 开距离、打断接触、调整射线。普通版本主动避免把非飞行敌人推入坑。不是无限每两拳推一次 |
| 4 | 撞击 SLAM | 普通攻击加 `round(自身本次drRoll×连击数/5)` 额外伤，清连击 | 护甲挑战削弱甲贡献，但武器格挡和不动如山仍能进入自身 DR；低甲时不应盲信旧版高伤 |
| 6 | 招架 PARRY | 付 1 回合，给下一个时间窗口 INFINITE_EVASION；可以攻击到对手且未被其魅惑时生成反击；成功反击 +1 连击；每条连击只用一次 | 处理可闪避攻击；未发生招架会清连击。环境/DOT/无命中检定攻击不应视为可挡；远程可挡也不等于能反击到对方 |
| 8 | 横扫 CRUSH | 主目标武器伤乘 `0.25×连击数`；以**英雄位置**为中心，对非 solid 可达 3 步的其他敌人分别重掷伤害再减半及扣各自 DR；清连击 | 不是真正以敌人为中心无条件 7×7 穿墙爆炸；每个副目标有独立伤害掷骰和护甲。适合已聚在安全走廊/门口的敌人，不能为AOE主动站进无退路包围 |
| 10 | 暴雨 FURY | 按连击数连续攻击同一目标，每击 60%武器伤、独立扣 DR、可触发附魔；整体消费一次攻击延迟并清连击 | 高甲目标会逐击吃 DR，未必优于高连击横扫。目标死亡/变友军/不能再攻击或英雄麻痹时提前结束；不能把剩余击数转给下一敌人 |

全部战技攻击统一走 `hero.attack(..., INFINITE_ACCURACY)`；“必中”不代表绕过无敌、护甲、格挡特殊防御或所有伤害免疫。来源：[ComboMove 枚举](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L211)、[Combo.useMove/ParryTracker](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L283)、[Combo.doAttack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L343)、[Hero.defenseSkill/defenseVerb](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L570)。

两项执行细节需要记住：

- 冲击“0 伤”只是前段 `dmgMulti=0`。后段攻击 proc 仍执行，受衅怒火等可添加伤害/效果。因此它可消耗正在准备的加伤；“用冲击绝不会打死”也不安全。链路：[Combo.doAttack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L354) → [Char.attack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L483) → [Talent.onAttackProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L868)。
- 强化 3 点的远距选目标代码没有筛选 moveBeingUsed，因此源码上冲击也进入同一跃击分支，而说明只列撞击/横扫/暴雨。**候选技巧，尚未隔离实测**；未来须用当前广告动作和公开结果确认，不能先承诺可用。来源：[Combo.listener](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L482)。

### 5.3 角斗士专属 T3

| 天赋 | +1 / +2 / +3 | 用法 |
| --- | --- | --- |
| 连战热忱 CLEAVE | 杀敌后连击保留 30 / 45 / 60 回合，基础 15 | +1 的跨战转场收益大；后续点须看地图连接与需求，食物压力下不要为保连多绕路 |
| 以战养战 LETHAL_DEFENSE | 战技杀敌或合格敌人变友军后，纹章冷却 −50 / −100 / −150 | 最低可到 −150。横扫副目标各杀各触发；3 点一次终结可把正常 150 冷却清完。不是直接回复 HP，也不是立刻无条件给盾 |
| 战技强化 ENHANCED_COMBO | +1：7 连以上冲击推3、眩晕3、允许推坑；+2：再加9连以上招架在一个窗口挡多次；+3：再加战技跃击，目标距离≤`1+floor(combo/3)`，落点是 PROJECTILE 命中目标前一格 | 跃击要求路径、落点可通行、没有扎根；落点 `occupyCell` 正常触发。强行推坑可能牺牲经验/战利品；是否划算看即时危险 |

来源：[Combo.hit](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L98)、[Combo.doAttack 战技与以战养战](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L373)、[WarriorShield.reduceCooldown](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L363)、[Combo.listener](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L494)、[Mob.die 深渊经验减半](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1025)。

**九挑战候选 8 点分配：**

- 原地防线与盾循环：不动 3、以战 3、连战 1、强化 1。低甲补 DR，7 连冲击可推坑；牺牲多次招架与跃击。
- 需要接近远程敌人且已有其他防御：强化 3、以战 3、连战 1、不动 1。不要把跃击当穿墙；目标必须可见且 PROJECTILE 直路有效。
- 主要问题是多敌短时间攻击：强化 2、以战 3、不动 3。没有连战点就仍只有基础 15 回合击杀窗口；可改一点不动给连战。
- 有实际力量门槛时从上述非核心位让点给力大；不会预先保证能穿高阶甲或拿到好盾牌。

## 6. 狂战士：低护甲环境下要更严格评估

### 6.1 怒气、伤害和狂暴护盾

普通 `Char.attack` 链调用 `Hero.defenseProc` 时，狂战士以传入的正伤害积怒，发生在自身护甲 DR 扣除之前、护甲刻印处理之前。一次增加 `damage/(4×HT)`，上限 `1+0.1667×洪荒之怒点数`。因此**良好减伤可在低 HP 损失下积怒**；但独立 `damage` 的法术、气体、陷阱不会因为掉了 HP 就自动积怒。来源：[Char.attack 扣伤顺序](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L483)、[Hero.defenseProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1585)、[Berserk.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L236)。

怒气给物理攻击乘 `min(1.5,1+power/2)`，100%及以上最高 +50% 伤。最近受击会给 3 tick 衰减缓冲；之后每 tick 损失 `clamp(power,0.1,1)×0.05×(HP/HT)^2`，低血保怒更久但也更危险。来源：[Berserk.damageFactor/act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L132)、[Char.attack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L433)。

怒气至少 100%、且纹章当前有效时可以主动狂暴。新增狂暴盾：

```text
round((8 + 2×护甲buffedLvl)
      × [1 + 2×(1−HP/HT)^3]
      × (power>1 ? power : 1))
```

这里看的是**护甲强化等级**，与普通纹章按护甲阶数的公式不同。满血倍数1，0HP倍数3；超100%怒气进一步放大。主动狂暴的恢复基础100回合，超100%怒气令其乘 `2−power`；低血自动触发使用另一种经验恢复，见下节。来源：[Berserk.currentShieldBoost/startBerserking/doAction](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L188)。

狂暴每 tick 以 `ceil(当前全部盾×0.025)×HoldFastFactor` 扣盾（小数随机取整），并通过 ShieldBuff 的优先级消费：普通纹章优先级2、Berserk为−1，因此狂暴自身盾通常最后被打掉。**它衰减时依据全部盾，消耗也可先落到其他盾**，不是只消耗狂暴那一条。全盾耗尽则退出狂暴、怒气归零，开始恢复。不动如山3能停止自然衰减，但不能阻止实际受击或饥饿绕盾。来源：[Berserk.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L101)、[Berserk 优先级](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L47)、[ShieldBuff.processDamage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ShieldBuff.java#L109)。

### 6.2 狂战士专属 T3

| 天赋 | +1 / +2 / +3 | 九挑战判断 |
| --- | --- | --- |
| 洪荒之怒 ENDLESS_RAGE | 最大怒气约116.67/133.34/150.01%；额外怒气乘盾、减少恢复时间 | 超100不再提高物理伤害。取得超额怒需要更多合格受击；不能假定低甲仍能安全积到150 |
| 不朽骤雨 DEATHLESS_FURY | HP降0、NORMAL且怒≥100时自动狂暴；之后恢复为3/2/1个英雄等级的经验份额，超怒进一步缩短 | 狂暴结束时若仍0HP仍死。恐药症下必须提前有真实可用的恢复方案；单纯“有不朽所以不会死”错误 |
| 怒气导魔 ENRAGED_CATALYST | 武器附魔/诅咒 proc 倍率额外加 `min(power,1)×0.15/0.30/0.45` | 是在通用触发倍率上相加，不是给所有附魔直接加15/30/45个百分点概率；与奥术戒并存时以具体附魔实现为准。有害诅咒也被增强 |

来源：[Berserk](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L168)、[Hero.isAlive](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2316)、[Hero.earnExp 恢复调用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2030)、[Weapon.Enchantment.genericProcChanceMultiplier](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L548)。

**九挑战默认倾向角斗士的理由（策略推论）：** 禁甲让“安全受击积怒”更难，禁药让0HP后的复原困难，禁卷让狂暴盾依赖强化的增长受限。角斗士可从投武、必中战技、以战养战和控场直接获益，不要求先吃大量攻击。狂战士仍不是禁选：若实际掉落能提供强武器格挡、不动如山站位、可靠减伤/吸血、优秀附魔，才重新比较长期增伤和狂暴盾收益。

候选 8 点：`不动3、洪荒3、不朽1、力大1`（力量点无实际门槛时另评）；强附魔已成型则可 `不动3、导魔3、洪荒1、不朽1`。若没有0HP后恢复手段，不应为“不朽循环”投入更多点，也不主动卖血触发它。两条方案均需要按实际资源调整，不是保证胜率的结论。

## 7. 护甲能力

### 7.1 职业护甲与共同 T4 天赋

王冠把当前护甲转换为职业护甲时，保留原甲阶数、强化、刻印、纹章、诅咒及对应加成，初始充能50%；**不会把布甲免费变成板甲**。选择能力/升级动作花1回合。正常最高充能100，每回合恢复 `0.2×能量戒护甲充能倍率`；无加成500回合从0充满。仍只检查 `Regeneration.regenOn()`，饥饿不单独停止充能，封锁楼层可能停止。

共同天赋 **英勇能量 HEROIC_ENERGY** 的 +1/+2/+3/+4 消耗倍率是 0.88/0.77/0.68/0.60。基础35的技能实际花30.8/26.95/23.8/21；基础50的技能花44/38.5/34/30。能量戒倍率 `1.175^有效等级加成`。充能和具体耗能应看当前公开显示，不能把UI整数当底层永远取整。

来源：[ClassArmor.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/ClassArmor.java#L98)、[执行限制](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/ClassArmor.java#L192)、[Charger.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/ClassArmor.java#L336)、[ArmorAbility.chargeUse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/ArmorAbility.java#L71)、[RingOfEnergy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/rings/RingOfEnergy.java#L84)、[KingsCrown](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/KingsCrown.java#L90)。

### 7.2 英勇之跃 HEROIC_LEAP：逃生与重排站位

基础消耗35，动作耗1回合、解除隐身；扎根时不能用。路线是 `Ballistica(STOP_TARGET|STOP_SOLID)`，忽略沿路角色，能跳过敌人/陷阱/深渊，但不能越实体墙。源码没有这项能力自己的固定最大射程；实际仍受原生目标选择、地图和阻挡约束。若目标格有角色，会沿路向后找空格。落地调用 `move` 和 `occupyCell`，所以落点陷阱/环境仍需评估。

| 天赋 | +1 / +2 / +3 / +4 | 执行与九挑战判断 |
| --- | --- | --- |
| 肉弹冲击 BODY_SLAM | 邻敌受 `1–4+25%自身DR` / `2–8+50%` / `3–12+75%` / `4–16+100%`，再减敌DR | 每敌独立掷骰；甲弱会降低收益，武器格挡仍能贡献DR。正常换格落地后不动如山旧位置无效，不能把跳前原地DR算进去 |
| 堕天一击 IMPACT_WAVE | 邻敌向外推2/3/4/5格；25/50/75/100%易伤5回合 | 真正的位移收益常高于低甲伤害；沿原生BlastWave推掷规则，可关门/碰撞/推坑，BOSS减半距离、扎根及IMMOVABLE免推 |
| 二段跳跃 DOUBLE_JUMP | 第二跳额外耗能乘 `0.84^p`，即0.84/0.7056/0.592704/0.49787136 | 首跳给3回合tracker，第二跳消费tracker；每两跳配对优惠，不是之后每跳都打折 |
| 英勇能量 HEROIC_ENERGY | 共同消耗倍率0.88/0.77/0.68/0.60 | 逃生能力必须留下真的足够的充能；先花光再说“有跳”无效 |

来源：[HeroicLeap.activate/chargeUse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/warrior/HeroicLeap.java#L47)、[落点与伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/warrior/HeroicLeap.java#L93)、[二段跳](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/warrior/HeroicLeap.java#L124)、[WandOfBlastWave.throwChar](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfBlastWave.java#L122)。

**候选10点：** 以活着脱离夹击为主，`英勇4、二段4、堕天2`；若需要确定性推离并施加易伤，可 `英勇4、堕天4、二段2`。英勇2+二段1时首对两跳约49.59充能，可在初始50附近提供两跳，但须实际检查充能/时间/是否已存在tracker。每次落地仍花1回合，新视野可能出现远程敌人；第一跳的落点必须为第二步留下余地。

### 7.3 震地冲击 SHOCKWAVE：依赖力量的远程群控

基础消耗35，动作1回合，解除隐身。默认5格/60度锥形；中心瞄准用 WONT_STOP，但实际锥体射线用 `STOP_SOLID|STOP_TARGET`，所以敌人不挡后排，墙会阻断。伤害为 `Hero.heroDamageIntRange(STR−5,2×STR−10)`，再乘强力冲击、四舍五入并减敌 DR。它**不使用武器基础伤害，也不做普通攻击命中检定**；活着的目标默认残废5回合。

| 天赋 | +1 / +2 / +3 / +4 | 执行与九挑战判断 |
| --- | --- | --- |
| 广域冲击 EXPANDING_WAVE | 射程6/7/8/9，角75/90/105/120度 | 适合已知敌群位置；黑暗下扩大范围不等于获得隐藏信息 |
| 复合震波 STRIKING_WAVE | 每目标30/60/90/100%触发 `hero.attackProc`，角斗士同时对合格目标 +1 Combo | 可一波起多连；4点另外给附魔通用倍率+0.2，不是所有伤害×1.2。该路径并非整套 `Char.attack`，不能推断所有普通攻击前后钩子都成立 |
| 强力冲击 SHOCK_FORCE | 伤害×1.2/1.4/1.6/1.8；25/50/75/100%把残废换成麻痹5回合 | 4点使基础概率确定，仍需目标可受麻痹且幸存；攻击/DOT可能打破麻痹，不能预支“五次免费打击” |
| 英勇能量 HEROIC_ENERGY | 共同消耗倍率 | 多次群控更灵活，但不能用时间空转无代价充能 |

来源：[Shockwave.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/warrior/Shockwave.java#L70)、[伤害/复合/麻痹](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/warrior/Shockwave.java#L112)、[Weapon复合冲击额外倍率](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L573)。

**候选10点：** 缺伤害与控制时 `强力4、英勇4、广域2`；角斗士有好附魔且主要用来起连时 `强力4、复合4、英勇2`。稀少升级卷使这种不依赖武器强化的基础伤害相对有价值，但它仍耗护甲能量、扣敌DR，不能代替所有普通输出。已有可靠逃生神器时它更容易与整体构筑互补；没有逃生手段时英勇之跃仍值得优先比较。

### 7.4 苦痛坚忍 ENDURE：确定的承伤窗口，不是免费反伤

基础消耗50，解除隐身，实际让英雄花3回合；清掉旧tracker再给新12f tracker，现有Combo延长3f。下次英雄获得行动时由 `Hero.act` 结束承受阶段，不能误把12f理解为能减伤12回合。

承受阶段，每次被此钩子处理的伤害先把一半累计为 int `damageBonus`，再让实际输入乘 `0.5×0.8^痛觉阈值点数`。普通攻击在目标护甲/defenseProc前处理；非Char来源在Hero.damage内处理，早于钢胃/坚韧戒。结束时先乘持续反击系数，再乘近敌系数，最后整除分到后续攻击；各 int 复合赋值可截断小数。

| 天赋 | +1 / +2 / +3 / +4 | 真实效果 |
| --- | --- | --- |
| 持续反击 SUSTAINED_RETRIBUTION | 总奖励×1.15/1.30/1.45/1.60，分2/3/4/5次命中 | 不是每一击都加全部存储伤害；分摊会受每击护甲影响 |
| 痛觉阈值 SHRUG_IT_OFF | 承伤×0.4/0.32/0.256/0.2048 | 对应减伤60%/68%/74.4%/79.52%，独立于甲挑战；免疫附加状态不能从此推出 |
| 情势反转 EVEN_THE_ODDS | 承受阶段结束时，距离≤2每敌令奖励+5/10/15/20% | 不是开招瞬间锁定数量；不要为了倍率主动增加围攻 |
| 英勇能量 HEROIC_ENERGY | 共同消耗倍率 | 基础50较贵，3回合承受窗口也不能主动中断成普通免费回合 |

奖励加入后续普通攻击命中分支的伤害，在目标护甲之前；未命中不从此路径消耗次数。狂暴等较早计算的乘数不能再乘后来才加进去的耐力平伤。文本说之后10回合内兑现，底层12f tracker和3回合延迟涉及Actor调度，实战应看公开buff剩余量。enduring/damage_bonus/hits_left 全部序列化，重进不是返还充能或叠伤依据。

来源：[Endure.activate/EndureTracker](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/warrior/Endure.java#L47)、[Endure调整与奖励](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/warrior/Endure.java#L102)、[Hero.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L837)、[Char.attack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L455)、[Hero.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1648)、[tracker持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/warrior/Endure.java#L159)。

**候选10点：** 已确定必须承受的爆发窗口可考虑 `痛觉4、英勇4、持续2`；九挑战一般不应为了反击奖励主动被围三回合，尤其存在扎根/麻痹/毒火连锁或首领高危险区域时。它能降低伤害，不能替代撤出可避免的公开预警。

### 7.5 鼠化术 RATMOGRIFY：关闭危险普通怪的特殊行为

获取需把未使用王冠带给鼠王、已穿护甲，走对话接受路径；常规路径意味着20层返回5层，再回深层。已用普通王冠选过能力后，本对话没有重新替换的入口。九挑战必须先预算往返食物、黑暗照明、路线和遭遇风险，不能仅因终局技能好看就默认值得回程。

基础消耗50，动作1回合、解除隐身。目标需要当前heroFOV，但这里**不做Ballistica检验**，对可见却被普通投射线挡住的危险目标可能有价值。可变敌对Mob，不能变原生Rat、盟友、BOSS或MINIBOSS。

变鼠不是把怪变成普通5HP老鼠。保留原HP/HT、防御技能、经验、等级上限和AI大态；攻击技能、攻击间隔、DR roll、伤害roll委托原体。替换为普通Mob行为可暂时禁用原怪的特殊AI/远程技能；没有逐一复制全部属性与buff，故不能笼统说“除外形外一切不变”。原体被保存，变回后可能恢复原来的内部状态；**不能仅因公开警告图像被转移/取消，就断言隐藏蓄力已清空**。

| 天赋 | +1 / +2 / +3 / +4 | 实际限制 |
| --- | --- | --- |
| 鼠手鼠脚 RATSISTANCE | 敌对变鼠伤害乘0.9/0.81/0.729/0.6561，再int截断 | 成为盟友后不再减伤；不是减半所有类型伤害的统一光环 |
| 外交鼠段 RATLOMACY | 对已经变鼠敌人**再施放一次**，令其永久盟友；2/3/4点另给2/4/6f肾上腺素 | 第二次也花完整能量；不点英勇能量，两次共100；英勇4两次共60。1点已解锁永久化功能 |
| 吱援部队 RATFORCEMENTS | 点自己召唤1/2/3/4只普通盟友Rat | 只占八邻域空且可走格，不够空格会少召，完全堵住也可能花费；这些是普通Rat，不继承高阶怪属性 |
| 英勇能量 HEROIC_ENERGY | 共同消耗倍率 | 变一次和招安两次的成本区别决定可靠性 |

变鼠持续6个其自身花费的行动时间，不是固定六次英雄操作。`TransmogRat.timeLeft=6f` 未被自身store/restore保存：**正常退出再加载可能重建为6f，延长变鼠，是待独立验证的重载候选**，不能从单类遗漏直接保证菜单操作一定走重建流程。永久盟友另存allied，且不再递减timeLeft。

来源：[RatKing交王冠](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/RatKing.java#L124)、[Ratmogrify.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/Ratmogrify.java#L61)、[变鼠初始化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/Ratmogrify.java#L203)、[TransmogRat计时/委托/招安](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/Ratmogrify.java#L234)、[TransmogRat.store/restore](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/Ratmogrify.java#L322)、[中文天赋名称](../../core/src/main/assets/messages/actors/actors_zh.properties#L1304)。

**候选10点：** 需要永久转化高威胁普通怪且确认能承担往返时，`英勇4、外交1、鼠手4、吱援1` 是一套功能分散方案；更看重盟友行动爆发可把鼠手点移到外交。未获得真实充能/路线保障前，不把鼠化作为默认末区答案。

## 8. 实战前的构筑决策顺序

1. **先解决本层可观测危险。** 当前 HP/盾/饥饿、纹章冷却、武器力量缺口、当前敌人的可闪避/可控性质优先于最终配点表。
2. **决定下一份永久资源的真实收益。** 禁卷使升级更稀缺，先计算这一次升级能否跨力量门槛、增加击杀速度或保障核心道具；纹章仅移动一个升级，不制造更多卷轴。
3. **确认打法有没有落地资源。** 没有投武/命中手段就不能假定轻松起连；没有强减伤就不能假定狂战积怒；没有食物/合格药剂就不能把丰盛或液蕴写成无限续航。
4. **优先避免伤害再考虑盾换血。** 门口、拐角、远程削血、即兴失明、击退、首领公开预警是处理威胁的第一层；纹章、钢胃、格挡是第二层保障。
5. **留点直到分支信息足够。** 稀有刻印、力量阈值、是否需要多次招架/跃击决定T2/T3的边际点。不是所有格子都必须一升级立刻点完。
6. **战技结算后重看。** 以战养战是否实际触发、盾的数字是shield还是cooldown、Combo是否保留、敌人是否仍可攻击，都以该次新公开观测为准。

## 9. 可复用结论与需要隔离验证的边界

已由执行源码确定：半血触发纹章盾；普通纹章盾看甲阶、狂暴盾看强化；饥饿绕盾但不单独停纹章冷却；丰盛固定回血；液蕴取最大Barrier且投掷有白名单；不动如山附加DR独立于护甲挑战；投武能起角斗士连击；以战养战可把冷却降至−150。

尚未实测、未来可建隔离夹具验证的交互：

- 强化3冲击远距跃击的原生UI路径；冲击叠受衅怒火/附魔的精确结果及是否消耗加伤。
- 钢胃2对特定首领同一时序攻击窗口的防伤与附带状态；不能仅凭总免伤文字推定无状态。
- 超100%怒气描述调用 `currentShieldBoost()` 有修改 recovery 字段的代码：NORMAL状态这些字段通常为0，不据此断言查看描述/重载能缩冷却；需要专门证明可到达状态。
- 持久化字段已检查：WarriorShield 保存 cooldown、离战计时、初始盾；Combo 保存次数、计时、冲击/招架已用标记；Berserk 保存怒气/状态/恢复。**退出菜单再进不应被当作这些技能的免费重置。**

来源：[WarriorShield.store/restore](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L385)、[Combo.store/restore](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L141)、[Berserk.store/restore](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L70)、[Berserk.currentShieldBoost/desc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L209)。
