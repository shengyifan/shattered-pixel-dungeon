# D19 炼金建议：只做两项，共12能量

本地源码v3.3.8/code896，只读分析；不操作UI、不修改游戏数据。按主代理所报取完两堆水晶后energy15、喝经验药升L16计算。读到的中间快照66100c75a2db仍L15、energy10，不能把计划值当作已完成。

## 推荐：1瓶水灵秘药 + 1张虹卫卷轴

| 优先 | 配方 | 能量 | 目标 |
|---|---|---:|---|
| 1 | PotionOfHealing×1 + GooBlob×1 → ElixirOfAquaticRejuvenation×1 | 6 | D19水地战斗后长期补血，减少普通治疗消耗 |
| 2 | ScrollOfMirrorImage×1 → ScrollOfPrismaticImage×1 | 6 | 长期守卫吸引仇恨，配合现有高强化鳞甲，支援魔像与矮人王 |

做完能量余3、普通Heal余4、GooBlob余1；Mirror转为Prismatic，其他药水/逃生资源保持可用。无需硬凑第三项。

### 水灵秘药（Aquatic Rejuvenation）

- Recipe明确只需已鉴定Healing1+GooBlob1，cost6。Healing已全局识别，GooBlob普通道具已识别，当前能直接做。
- 喝下时获得round(1.5×HT)的治疗储备。L16、Might0仍装备时预计HT98，所以**147HP储备**；最终以喝药当时HT为准。
- **不计持续时间，离水/满血不浪费储备**；只有非飞行、站水格、未满血时，每世界回合治疗约HT/50，当前约1.96HP。实际取整带随机，治疗至满血或储备耗尽停止。
- 可升L16后在安全位置提前喝，接下来D19广泛水廊自然恢复；战斗中也会持续恢复，但远小于魔像每拳，不把它当瞬时救命药，普通Healing仍需保留。
- 飞行/Levitation期间不会在水上治疗。D20主场源码没有布置水，CityPainter只装饰现有地形；因此**不能把该147HP算作矮人王战的现成回血**。它主要覆盖剩余魔像路线及后续有水楼层，无水时Buff能保留。

### 虹卫卷轴（Prismatic Image）

- 单放MirrorImage卷、选**6能量的异变卷轴输出PrismaticImage**。不要选旁边0能量的卷轴转石配方。原始MirrorImage卷尚未全局识别也能炼：Recipe.usableInRecipe允许未鉴定非诅咒普通道具，ScrollToExotic也不要求ID。
- 可在安全地读取，先成为PrismaticGuard Buff；没有固定倒计时。发现距离<5的可见HUNTING敌人后，自动在英雄邻格生成守卫。
- L16守卫最大HP=10+floor(16×2.5)=**50**，攻击**6–12**，并不是复制十字弩伤害或寒冷附魔。它用英雄实际DR，防御触发英雄护甲glyph，并获得部分闪避戒收益；当前Scale6很适合给它挡伤。
- 它的攻击会把目标仇恨转向自身，可帮魔像/Monk分担近战；也能承受术士注意力，但普通护甲不防术士魔法，勿放任双敌集火。
- 敌人不在其FOV后会收回为Buff，Buff状态以0.1HP/回合缓慢恢复（受Regeneration开关）；它没有免费死亡复活，50HP被耗尽后就需要新卷轴。
- 初始生成位置按靠近敌人的可走英雄邻格挑选，自动走位可能与计划不同。普通NPC是soft press，**不触发仍隐藏的陷阱，但会触发已揭露陷阱**；既有Flashing(21,16)如果还active且已揭露，需要考虑守卫走上它。不要因此自动揭露/踩阱。
- 对D20可在开Boss前安全读取，把卷轴行动提前完成；其防御和主动吸仇恨比基础MirrorImage两个脆弱幻像更配当前装备。也可以现在读取服务剩余魔像；是否留到D20由预期消耗决定。

## 当前不做的替代项与原因

- **CausticBrew（ToxicGas1+GooBlob1，cost1）：**数值上很合算，可给落点非实体路径半径3内所有角色Ooze，D20每回合约5点腐蚀，共20回合，适合DK小怪群/第3阶段。但当前ToxicGas_known=false，SimpleRecipe要求所有材料已鉴定，现阶段不能直接制作；为它增加鉴定流程不符合少量操作目标。如果之后自然识别Toxic且仍有Goo，可用剩余3能量中的1做此物。使用必须让英雄离实际落点路径距离>3，否则自中腐蚀；Purity不防Ooze。
- **WildEnergy（Recharge1+MetalShard1，cost4，产5）：**充能总量高，每次附带CursedWand随机法术；当前没有必要为资源效率接受随机诅咒效果，且Recharge尚未识别，SimpleRecipe还需先解决识别。不要把它当纯安全充能。
- **MysticalEnergy（Recharge→异变，cost6）：**只补ArtifactRecharge30，不能同时维持普通法杖充能；Hourglass后续自然补充，收益比50HP守卫与147HP水上储备更窄。
- **Stamina（Haste→异变，cost4）：**100世界回合移动×1.5，很持久，但会消耗唯一Haste的短期×3机动。现有精确撤退/沙漏方案仍需要爆发速度，暂保留Haste；若以后有第二瓶再考虑。
- **Cleansing（Purity→异变，cost4）：**完全清负面并满足450饥饿，外加5回合Cleanse，很实用。但已有Pasty/多瓶药、此次做推荐两项后只余3能量，暂不多拆资源凑第3项。未来需补食物时优先考虑把多余Purity转这一瓶。
- **EarthenArmor/ArcaneArmor：**缺ParalyticGas原料。EarthenArmor由麻痹气体转换cost4；ArcaneArmor还要EarthenArmor+GooBlob并cost8，所以目前不能从现有Purity/Levitation直接制作。
- **HoneyedHealing：**需要ShatteredPot，不是未打碎Honeypot。故当前不是现成两材料配方，别为了cost2的秘药先放出敌对蜜蜂再收碎罐。
- **DivineInspiration：**消耗唯一经验药；当前等级偏低，直接升L16带来的HP、基础命中/闪避与正常天赋更切合此次目标，保留原定喝经验药。

## 源码依据

- items/potions/elixirs/ElixirOfAquaticRejuvenation.java:55/83/152：150%HT储备、水上速率和6能量配方。
- items/scrolls/exotic/ExoticScroll.java:143：单卷异变6能量；items/Recipe.java:257：未识别非诅咒普通物品可入炼金。
- actors/buffs/PrismaticGuard.java:48/132：生成条件、50HP、储存恢复；actors/mobs/npcs/PrismaticImage.java:140–225：攻击/护甲/仇恨/免疫；Level.java:1183：NPC soft press忽略隐藏陷阱。
- CityBossLevel.java:133 与CityPainter.java：D20地形构造没有新增水。
- WildEnergy.java:53/85；ElixirOfHoneyedHealing.java:81；PotionOfCleansing.java:69；ExoticPotion.java:52/125。
