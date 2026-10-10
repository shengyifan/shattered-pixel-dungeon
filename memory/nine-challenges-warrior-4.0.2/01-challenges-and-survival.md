# 九挑战：限制、例外与战士生存预算

核查日期：2026-10-10。源码基线：`8405ae82f206e965d14b4a3b9d4fb282f2230e47`，上游 4.0.2 / code 922，本项目 CLI.9.0.3。本文来自当前仓库静态源码与原生说明；未启动游戏、未读取个人存档、未进行九挑战实战验证。**规则**表示直接代码事实，**战术推导**表示利用这些事实提出的条件性决策，不能代替当前公开观察。不要把旧版本战士印章、旧版挑战描述或其他角色的技能直接套入本局。

## 1. 九项挑战的准确身份

全开位掩码为 **511**，共 9 项。内部名字有历史遗留：`NO_FOOD` 并非不生成食物，`NO_ARMOR` 并非不能穿护甲，`NO_HEALING` 并非禁止一切回血，`NO_SCROLLS` 并非移除全部卷轴。定义见 [Challenges 常量与 MASKS](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Challenges.java#L29)；本表名称直接核对 [英文原生说明](../../core/src/main/assets/messages/misc/misc.properties#L216) 与 [简中原生说明](../../core/src/main/assets/messages/misc/misc_zh.properties#L216)。

| 中文 / English | flag / 值 | 对九挑战士最直接的影响 |
| --- | --- | --- |
| 精英强敌 / Hostile champions | `CHAMPION_ENEMIES` / 128 | 六种精英效果，需要逐只读取，普通敌人的旧打法不再充分 |
| 绝命头目 / Badder bosses | `STRONGER_BOSSES` / 256 | 五首领特定强化；锁层被动恢复预算更紧 |
| 缩餐节食 / On diet | `NO_FOOD` / 1 | 食物及丰饶之角饱腹量降至 1/3，部分直接饱腹途径不受影响 |
| 信念护体 / Faith is my armor | `NO_ARMOR` / 2 | 护甲 DR 最小值为 0，基础/升级上限大幅降低；并非移除所有减伤 |
| 恐药异症 / Pharmacophobia | `NO_HEALING` / 4 | 治疗药剂及指定衍生物对英雄改为中毒；其他回血须按具体调用链判断 |
| 荒芜之地 / Barren land | `NO_HERBALISM` / 8 | 露珠掉落被阻止、普通植物无法生成；种子仍掉落并有炼金/涂镖/自然鞋用途 |
| 集群智能 / Swarm intelligence | `SWARM_INTELLIGENCE` / 16 | 敌人持续看见我方时召集范围从 2 增至 12；断视野可重置累积 |
| 没入黑暗 / Into darkness | `DARKNESS` / 32 | 常规视距 2；火把是 250 回合、视距 6 的主要照明预算 |
| 禁忌咒文 / Forbidden runes | `NO_SCROLLS` / 64 | 正常生成的 15 个升级卷轴槽只保留奇数槽，即常规主线共 8 张 |

## 2. 缩餐节食：按时间和实际回血，而不是按食物图标预算

### 直接规则

- [Food.satisfy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L114) 只把 `energy` 除以 3；若还装备诅咒丰饶之角，再乘 0.67。普通口粮 `energy=300`，挑战下饱腹 **100**；小份口粮、各种普通肉 `150→50`；常规馅饼 `450→150`；肉派 `900→300`。节日馅饼有额外分支，实际名称/说明优先，不能一概套常规馅饼数值。
- [Hunger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L40)：饥饿阈值 300，饥肠辘辘/饥饿伤害阈值 450。通常每世界回合增长 1，**挑战没有将增长速度乘 3**。从满饱腹出发仍有约 450 回合到饥饿伤害。
- 满饱腹不能通常超储食物：`Hunger.affectHunger` 默认将负 hunger 截到 0。饥饿伤害阶段每 tick 累加 `HT/1000`，累计 **大于** 1 才造成整数伤害，跨入 starving 当刻另有 1 伤害。不要声称每 N 回合必定掉恰好 1 血，部分累积和 HT 变化都会影响时点。
- [ShieldBuff.processDamage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ShieldBuff.java#L109) 明确让 Hunger 穿过护盾；[Char.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L957) 也不让饥饿伤害触发战士护盾。因此“盾已满，饿着休息就行”是错误方案。
- [Regeneration.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L44) 基础回血约 **1 HP/10 回合**，必须未 starving 且 `regenOn()`。仅处于 hungry（300–449）仍可回血。无其他修正时，一份挑战口粮提供的 100 饱腹约对应最多 10 HP 自然恢复窗口，而不是立刻回复 10 HP。
- [Hunger.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L64) 在 `Dungeon.level.locked`、WellFed、ChallengeArena 或教学状态时暂停饥饿推进/饥饿伤害。**锁层停止饥饿，并不保证自然回血和法杖/神器充能始终运行**，后者受 LockedFloor 的预算约束。

### 食物天赋没有一起被削成三分之一

[Food.execute](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L74) 在饱腹计算之后，以原 `energy` 调用 `Talent.onFoodEaten`。当前 [战士 Hearty Meal](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L587) 在进食时 `HP/HT < 0.334` 回复 **4/6 HP**，不检查 NO_HEALING，也不按 NO_FOOD 除 3。它不是旧版“半血以下也有一档治疗”。食物通常耗时 3，投入 Iron Stomach 后耗时 1；该进食窗口的减伤是 1 点天赋减至 1/4、2 点降至 0，见 [Food.eatingTime](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L101)、[Hero.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1670)。

**战术推导：** 食物同时是饱腹、回血和安全行动窗口，进食应按这三个收益安排。安全时尽量避免将饱腹溢出；若已接近被杀，不能为了等三分之一血线而故意再挨打。九挑下小食物也能触发完整进食天赋，因而食物数量和每次吃的时机有独立价值。

### 直接饱腹的例外

| 来源 | 九挑下精确行为 | 代价和决策 |
| --- | --- | --- |
| [全面净化合剂 PotionOfCleansing.cleanse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L75) | `Hunger.satisfy(450)`，不打三折；移除符合条件的负面 buff，给 5 回合 Cleanse | 同时解决饥饿及多种负面状态；其基底是普通净化药剂 Purity 的异化关系，实际炼金需查配方，不可误用普通 PotionOfPurity |
| [治疗井 WaterOfHealth.affectHero](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java#L51) | 满血（鲜血饰品可改延迟）、解除装备诅咒、`satisfy(450)`、清除治疗药剂的那组负面状态 | 九挑关键恢复点；能安全利用时，将回血/饱腹/去装备诅咒一并考虑，不为极限收益跨越致死阈值 |
| [蜂蜜治疗合剂 ElixirOfHoneyedHealing.apply](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfHoneyedHealing.java#L45) | 药剂治疗部分仍中毒；但另外直接恢复 **150** 饱腹、调用进食天赋 | 它不是无条件安全食物。已有 Cleanse 可阻止新负面 buff，构成条件性组合；不要为 150 饱腹裸喝并承担毒伤 |
| [丰饶之角](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/HornOfPlenty.java#L127) | 每充能原 90，挑战下 **30**；并未绕过挑战 | 若得到，应兼顾按次触发 Hearty Meal；饱腹供给有限，不等于无限自然回血 |

净化的“免疫”是 [Char.add](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1221) 阻止指定负面 buff 附着；它不等于免疫一切伤害、陷阱或直接攻击。蜂蜜合剂+净化是源码可解释的转换方案，**尚未用隔离实例验证**；净化剩余时间、动作耗时、配方成本和更重要的危机用途必须先核实。

### 肉派、冰冻肉和盐块

- [MeatPie](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/MeatPie.java#L33) 用馅饼/幻影肉、口粮、肉制作，耗 6 炼金能量；直接饱腹 300，另重设 [WellFed](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/WellFed.java#L43) 的 `left=150`。WellFed 暂停 Hunger，每 `left%18==0` 直接回 1 HP，这段不受恐药影响。其 `reset` 并不把 Hunger 本身重置为 0。
- 原材料挑战饱腹合计也是 300，肉派主要增加约 150 回合饥饿暂停及额外回血；但把三次进食合成一次，会少两次战士进食天赋触发，还要花能量。当前循环从 150 递减到 0，理论触发回血的 left 为 144、126、108、90、72、54、36、18、0（最多 9 次，满血时不储存）。这只是未受打断、无时间修正的代码计数。
- [FrozenCarpaccio.effect](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/FrozenCarpaccio.java#L54) 五等分结果：隐身、树肤、清除特定异常、直接回复 `HT/4`、无额外效果各 1/5；回血路径不经 `PotionOfHealing.heal`，所以恐药不拦截。其饱腹仍只有 50，不能把随机回血当急救保证。
- [PhantomMeat.effect](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/PhantomMeat.java#L51) 的幻影肉同时给树肤、隐身、直接 `HT/4` 回血与特定清异常，不是冰冻肉的五选一；其饱腹仍由450减至150。实际掉落稀有，未获得不能计入计划。
- [SaltCube](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/trinkets/SaltCube.java#L58) 延长食物维持时间的同时削弱自然恢复：+0/+1/+2/+3 的饥饿增长倍率分别 0.8、2/3、4/7、0.5；自然回血倍率 0.84、0.73、0.66、0.6。+3 一份饱腹对应时间翻倍但单位时间自然回血仅 60%，合起来每饱腹自然回血潜力约 1.2 倍，**不是回血翻倍**。WellFed 也受其时间减速，但额外回复次数不减少；已经 starving 时其增长减速不能消除 starvation 分支的固定累积伤害。

## 3. 恐药异症：哪些治疗有毒，哪些仍有效

### 明确有毒的路径

[PotionOfHealing.apply/heal/pharmacophobiaProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfHealing.java#L51)：先鉴定、`cure`，再对英雄施加 Poison `4 + hero.lvl/2`（Java 整数除法），而不是 Healing。注释“约最大血量 40%”只作规模提示，不能换算成必定固定伤害。`cure` 仍清除 Poison、Cripple、Weakness、Vulnerable、Bleeding、Blindness、Drowsy、Slow、Vertigo，然后普通药剂重新添毒。

以下来源也中毒而非治疗：

1. [护盾药剂 PotionOfShielding.apply](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfShielding.java#L40)：没有护盾，直接调用过敏路径。
2. [水灵回复合剂 ElixirOfAquaticRejuvenation.apply](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfAquaticRejuvenation.java#L50)：没有水中治疗，改为中毒。
3. 蜂蜜治疗合剂：调用上述 `heal(hero)`，仍中毒；独立饱腹和进食天赋如上。
4. [阳光果 Blandfruit.execute](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Blandfruit.java#L96)：带 `PotionOfHealing` 属性的果实会调用该药剂 `apply(hero)`，仍过敏。把药剂效果装进食物不能规避。
5. [再生炸弹 RegrowthBomb.explode](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bombs/RegrowthBomb.java#L61)：范围内同阵营角色调用 `cure/heal`，英雄仍中毒，其他盟友仍治疗；其普通植物生成也被荒芜挡住。不能把治疗药剂炼成再生炸弹当成安全群体自疗。
6. [紊乱魔药 UnstableBrew.apply](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/UnstableBrew.java#L82) **主动把 Healing 权重降到 0**，并重抽 must-throw 药剂；不会靠随机喝出一次正常治疗。“不会中毒”是该饮用抽取路径的限制，不代表可站毒气里或万物免疫。

[战士初始化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L174) 已直接鉴定治疗药剂和狂怒卷轴，因此正常新局战士不是从完全未知的治疗药剂颜色开始；仍必须以本局公开鉴定状态为准。

这一挑战的判断对象是英雄。`PotionOfHealing.heal(ch)` 对非英雄仍正常工作；蜂蜜合剂可投给盟友恢复/清异常，并有令蜜蜂转为友方的专门路径。不要将“过敏治疗”投敌当毒药：其他生物会得到正常治疗。普通治疗药剂的打碎也不是通用盟友治疗手段，必须使用实际实现了 `shatter` 治疗的道具。

### 九挑战士可用的恢复/保护分类

| 来源 | 是否保留 | 限制 |
| --- | --- | --- |
| 自然回血、圣杯加速自然回血 | 是 | 未 starving、LockedFloor 恢复预算有效；圣杯刺伤升级风险另查神器章 |
| Hearty Meal 直接回血 | 是 | 必须低于精确阈值、当前有天赋、消耗食物 |
| 治疗井 | 是 | 一次性资源；鲜血饰品可能让回复延后 |
| WellFed 额外回血、冰冻肉抽到的直接回血、幻影肉直接回血 | 是 | 肉派时长削弱、冰冻肉不保证抽中，幻影肉稀有 |
| [魔法睡眠 MagicalSleep.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MagicalSleep.java#L63) | 是，友方每 tick 直接 +1 HP | [催眠卷轴](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfLullaby.java#L44) 先让视野内目标 Drowsy；自身随后睡眠且不能操作，伤害在 [Char.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L901) 唤醒，饥饿伤害也算。应在安全、未饥饿处使用 |
| [吸血附魔 Vampiric.proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Vampiric.java#L38) | 是，直接加 HP | 基础触发率随缺血为 5%–30%，回复伤害量约一半；不应为触发而跟危险怪换血 |
| 自然鞋激活阳春草 | 是，见荒芜例外 | 80 充能、选自己当前格、先解除自身隐身、耗1回合、原地渐进治疗、移动解除；不能穿鞋种一地普通草来自动回血 |
| 水袋中实际已有的水 | 是 | 荒芜切断通常露珠来源，治疗井仍可填袋；鲜血饰品可将多滴水的回复转成渐进 Healing，不能一律计为即时救命 |
| 战士印章、格挡/屏障等 | 是保护，非回血 | 一般不受 NO_ARMOR、NO_HEALING 的全局抑制，但各自触发/冷却不同；饥饿穿盾 |

[WaterOfHealth.affectItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java#L79) 可以填满不满的水袋、祝福未祝福十字架、解除合适物品诅咒。[Waterskin.execute](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Waterskin.java#L87) 按每滴基础 5% 最大 HP 计算所需水量，再调用露水消费逻辑。携带有效鲜血饰品时，`Waterskin` 先按增益调整所需滴数，而 [Dewdrop.consumeDew](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Dewdrop.java#L107) 对实际消耗多于1滴的回血改为 `Healing.setHeal` 渐进恢复；单滴不走此延迟分支。因此水袋和治疗井都必须结合当前饰品确认即时可用血量，不能只看总回复量。**战术推导：** 若本人血量/饱腹都好，保存治疗井作可携带的水袋恢复可能比立刻踩井更适合后面的首领；如果需要恢复大量血量、去装备诅咒或饱腹，踩井可能更有价值；是否即时满血仍取决于鲜血饰品。三种用途争夺同一个一次性井，不能全部计入资源总额。

## 4. 荒芜之地：普通种植禁用，种子没有变成垃圾

规则集中在 [Challenges.isItemBlocked](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Challenges.java#L71)、[Level.plant](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1051)、[Plant.Seed.onThrow](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Plant.java#L153)：

- `Dewdrop` 被阻止掉落。高草仍执行它的普通随机流程，但露珠对象不能形成可收集的地面资源。
- `Level.plant` 做必要地形处理后在挑战下返回 `null`，不会产生普通植物。直接“种植”和把种子扔在脚下/怪物脚下，都不能期待植物效果；Seed.onThrow 在挑战下按普通物品落地处理。
- [HighGrass.trample](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/HighGrass.java#L80) 仍允许自然鞋充能、种子/相关饰品符石掉落、迷彩触发。普通高草种子基础掉率 1/25，鞋子可改善，矿洞真菌及宝库另有降低；不要为低概率种子无预算绕圈踩草。
- [GameScene 跌落物处理](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L672) 和 [CursedWand 的植物效果](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/CursedWand.java#L444) 同样显式尊重荒芜，不能靠下层接种子或诅咒法杖补回普通植物。

**仍可利用的用途：** 种子炼药、制作涂药飞镖、喂自然鞋；普通抛掷种子会落地，战士 [Improvised Projectiles](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java#L665) 可以用非投掷武器命中敌人，致盲 `1+天赋点` 回合、冷却 50 回合。种子属于可选投掷物，荒芜使其不会在落点自动变成普通植物；仍要核弹道、能否回收及行动时间，不把一次投掷当无代价控制。

### 自然鞋直接种子效果：真正的例外

[SandalsOfNature.cellSelector](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L335) 只要求可见且距离不超过 3，然后直接 `Seed.couch(cell, null)` 并 `plant.activate(Actor.findChar(cell))`，**没有经过受 NO_HERBALISM 拦截的 Level.plant**。装备、非诅咒、无 MagicImmune、已喂入对应种子、充能够，才会提供使用动作。实际激活在 [351–365 行](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L351) **先执行 `Invisibility.dispel(curUser)`，再对目标格当前角色生效，最后耗1回合**。阳春草给自己治疗必须选英雄当前格；选空格没有可治疗对象，选别的角色则效果给对方。不能把“隐身后安全启动鞋子自疗”作为成立的战术，必须先确保破隐后敌人仍打不到自己。

- 阳春草消耗 80 充能；[Sungrass.activate/Health](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Sungrass.java#L49) 为普通战士加入最大 HP 大小的治疗池，每 tick 累加 `(40+HT)/150`，在原地逐步恢复。移动离开记录格会解除；这里没有全局恐药检查，也没有普通自然回血的未饥饿要求。饥饿仍可能同时扣血、敌人仍会行动；不能把理论治疗池当即刻可用 HP。
- 地缚、速行、盲目草等直接效果也可通过这条路径生效，费用分别由 [seedChargeReqs](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L107) 决定。鞋子不会把所有种子效果常驻一起持有，当前 `curSeedEffect` 是最后喂入的种子；更换会消耗种子和 1 回合。
- +0 到 +3 内部鞋级时，[Naturalism.charge](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L273) 每次有效踩草基础获得 `(3+2*level)/6` 充能，再乘神器充能倍率。**80 充能不是短暂等待即可补满**，需要足够草与移动预算。

### 再生法杖不是只剩空效果

[WandOfRegrowth.onZap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfRegrowth.java#L105) 仍铺普通/高草、给命中的角色 Roots `4*消耗充能` 回合；生成特殊植物时走 `Level.plant`，被荒芜拦截。其草可用来断视线、触发迷彩、为自然鞋充能，是否值得投升级取决于装备组合与食物/法杖充能预算。`totChrgUsed`/等级上限会使后续生成更多犁草，不能无条件视为无限种子/充能工厂；见 [chargeLimit](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfRegrowth.java#L203)。

## 5. 信念护体：DR 被削弱，盾、附魔和位置价值更高

[Armor.DRMax/DRMin](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L375) 在挑战下：

`DRMin(level)=0`；`DRMax(level)=1+tier+level+augment.defenseFactor(level)`。

无强化石调整时，+0 布/皮/锁/鳞/板甲的 DR 范围分别为 **0–2、0–3、0–4、0–5、0–6**；每升一级只加 1 点上限、不加最小值。正常护甲上限是 `tier*(2+level)`，因此高阶甲/大量升级的收益差距很大。布甲 +0 的范围没有变，布甲升级后的最低减伤仍被归零。

**并未移除：** 护甲刻印、闪避调整、战士印章、武器格挡、树肤、独立减伤等各自机制。不要从 flag 名推导“脱甲更好”。[Armor.evasionFactor/speedFactor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L413) 的力量不足惩罚照旧：每欠 1 力量闪避除 1.5、速度除 1.2。为了多 1–2 上限 DR 而穿超重甲，可能增加大量被追打次数。

灵壤守卫受到额外针对：[WandOfLivingEarth.EarthGuardian.drRoll](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLivingEarth.java#L418) 其附加 DR 改为 `NormalIntRange(wandLevel, 2+wandLevel)`；普通为更高成长。**英雄身上的 RockArmor 不是同一实体**：[RockArmor.absorb](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfLivingEarth.java#L296) 仍用库存吸收约一半符合调用路径的伤害，[Hero.defenseProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1606) 调用它，并没有 NO_ARMOR 分支。不能把“灵壤守卫防御降低”扩大成“法杖提供的一切防护都无效”。

**战术推导：** 先满足力量与移动安全，再比较刻印/印章/减伤组合。护甲升级仍可能为了降力量需求、增强刻印、支持战士特定能力，但在仅 8 张升级下，把大量卷轴堆到护甲期待普通局的站桩免伤很危险。围绕门、草、拐角、射程和可中断控制减少敌方攻击次数，通常比赌 DR 更可靠。

## 6. 集群智能：持续暴露是可控的负债

[Mob.act/processSwarmIntel/swarmAlertRange](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L291) 的条件是：发信者阵营 ENEMY、状态 HUNTING、正在看见存活且非隐身的敌对目标，目标阵营 ALLY。**盟友也能触发**，不是仅英雄。

第一次范围 2；持续可见的时间增加时范围按 `2*floor(now-timeSeenAt)` 长到 12。范围以**发信怪自己的位置**为中心，循环会 `beckon(enemy.pos)` 给范围内、未 HUNTING、未瘫痪的 ENEMY 怪。这里没有墙体/路径长度/双方视线过滤，所以隔墙房间也可能被拉来；其到达英雄处仍需要实际走路。原生说明中的“每回合 +2”是世界时间尺度，快速或缓慢单位的行动频率不能粗暴等同于英雄输入次数。

- 不满足条件就把累积重置；失去视野、隐身使其不再看到目标、状态变化、瘫痪等可影响该条件。门造成的短暂断视野也算，不能只在长走廊后退让怪持续看见自己。
- 已被召集的怪不会因为当前半径归零而自动忘记 beckon 目标；重置只减少后续召集。
- 保存读档不是字段丢失漏洞：`timeSeenAt` 在 [Mob.storeInBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L195) 写入 `SWARM_TIME`，在 [restoreFromBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L247) 恢复。不能声称重进自动清掉累积。

**战术推导：** 遇敌先找一门或一拐角后的可退位置，优先让危险远程敌人走到失去远程优势的位置。需要清某片已知区域时，可在有退出路线的门口短暂引来附近敌人，减少往返探索；收益是节省步数与形成单入口，代价是同时拉来精英/远程怪。只在已见威胁和资源允许时实施，不把隐匿区域的怪数量当作已知。召唤盟友站在远处也可能开辟第二条拉怪链。

## 7. 没入黑暗：火把是战斗资源，不是装饰

[Level.viewDistance](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L158) 从通常 8 改为 2。[Torch.execute](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Torch.java#L57) 消耗 1 回合并添加 [Light](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Light.java#L35)：每根增加 **250** 回合光照，视距至少 **6**（`Buff.affect` 对已有 Light 增加时长，与棱光的 `prolong` 不同）。这并未让敌人视距同步降为 2；不能以“我看不见”推断“敌人也看不见”。光照更主要是让我们及时看见敌人/地形，不代表范围内自动可命中。

生成不是笼统“所有楼层各一”：

- [RegularLevel.createItems](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L472) 普通层新增 1 根，LARGE 普通层再加 1 根；独立随机生成器减少对其他生成的影响。
- [MiningLevel.createItems](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/MiningLevel.java#L210) 矿区另加 1 根。
- [VaultLevel.createItems](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/VaultLevel.java#L613) 宝库内部加 2 根；[VaultEntranceRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/vault/VaultEntranceRoom.java#L118) 入口房另有挑战火把。
- 专用首领层不能套普通层保证；为进入首领前照明留存。
- [Yog.updateVisibility](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L480) 还会随阶段降低基础视野，挑战时取不超过 2；Light 存在时保留其视距。因此最终战火把余量比“刚到恶魔大厅时还剩几根”更有实际意义。

[棱光法杖 onZap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfPrismaticLight.java#L69) 在基础视距 <6 时，挑战照明为 `2+buffedLvl`，通常为 `10+5*buffedLvl`，即 1/5 **时长**而非视距五分之一。`Buff.prolong` 的语义是延长到至少该剩余时间，不要把每次射击都当成可无上限叠加时长。非战士的 [Radiance](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/spells/Radiance.java#L63) 是 20 对 100，[ElementalBlast 棱光分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/mage/ElementalBlast.java#L410) 也有挑战折减；它们是源码覆盖项，不是战士默认可用技能。

**战术推导：** 新区域、会远程攻击的敌群、首领转换前使用火把；在已清且短小的安全路段可以省，但需要当前观察支持。火把持续按实际回合耗，绕路/搜索/休息都吃预算；首领前不要把 200 回合安全休息和随后 100 回合战斗同时记在同一根火把上。地图记忆不等于当前敌人位置。

## 8. 禁忌咒文：8 张主线升级，区域分配 2/1/2/1/2

[Dungeon.souNeeded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L558) 每五层组有 3 个正常升级生成槽；[Level.create](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L232) 先递增全局生成计数，再只生成奇数编号。因此 15 个通常主线槽保留 1、3、5、7、9、11、13、15，即：

| 区域 | 正常槽号 | 九挑保留数 | 累计 |
| --- | --- | --- | --- |
| 下水道 1–4 | 1–3 | 2 | 2 |
| 监狱 6–9 | 4–6 | 1 | 3 |
| 洞穴 11–14 | 7–9 | 2 | 5 |
| 矮人都市 16–19 | 10–12 | 1 | 6 |
| 恶魔大厅 21–24 | 13–15 | 2 | 8 |

这些是**生成额度，不是每层固定位置或已经获得的数量**；有未搜索房间、掉落损毁/未取回、支线和特殊来源时应以当前公开物品核算。不能根据挑战关闭时的同种子地图预读九挑物品位置：`Level.create` 的注释也明确说删除卷轴仍可能影响生成 RNG。

其他卷轴、力量药水、刻笔的生成不被此开关一并砍半；`Level.create` 保留对应独立流程。**战术推导：** 以“监狱结束只有 3 张、都市结束只有 6 张”的保守预算做升级承诺，优先选择可以用到后期的主输出/关键控制来源；任务奖励自带等级、铁匠服务、奥术树脂等另算。不要因“禁忌”而舍弃鉴定/祛邪/控制卷轴，也不要默认必须把 8 张全部留到终局再用，前期死亡会让保留预算失去意义。

## 9. 精英强敌：覆盖普通敌人基础规则的六层修正

### 生成不是独立伯努利投骰

[Dungeon.init](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L247) 初始化 `mobsToChampion=1`；[ChampionEnemy.rollForChampion](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L94) 每次合适的生成调用减 1，达到阈值并成功附加后再加 `8-min(20,scalingDepth-1)/10`，即间隔随深度由 8 降到 6，且每次随机选六种之一。原生说明用“1/8 到 1/6”表达频率；战术上**不要解读为每只独立 12.5%/16.7% 抽取，也不能用计数器窥探未见怪物**。

前期保护：Crab 在 scalingDepth≤3、Thief≤4、Guard≤7、Bat≤9 不被附加；达到阈值但碰到此类会提前返回，计数压力可延续到后面的普通生成。成功后非 PASSIVE 怪改为 WANDERING，因此不会按普通沉睡怪处理。敌人生成警告不等于知道其位置。所有精英免疫 `AllyBuff`，常规腐化转友不能作为通用解法。

| 精英 | 直接规则 | 对战士的对策推导 |
| --- | --- | --- |
| 烈焰 Blazing | 近战伤害×1.25；攻击非水格目标点燃；自身免疫 Burning；死亡周围九宫格非固体、非水格起火，坠坑死亡有专门排除 | 让自己站水上可阻止此次点燃，但火/其他伤害仍要核；尽量远程终结、退离尸体火域。水能隔离火势；别让高草链火烧到卷轴/路线。不能靠火焰输出磨它 |
| 索敌 Projecting | 近战伤害×1.25；额外攻击可达距离 4，**用可通行路径距离**判定，角色占位阻挡，不是普通法杖直线 | 不能把“隔了一个拐角”当绝对安全，也不能按普通一格怪隔 2 格投掷。要用真正超过路径4的障碍/距离或控制；狭道拉开前先核当前位置是否已在攻击范围 |
| 敌法 AntiMagic | 所受伤害×0.5；免疫的是 `AntiMagic.RESISTS` 列表，不是所有道具/环境 | 用物理武器、投掷武器和未列入免疫的环境/控制；毒、腐蚀等具体来源需核基础怪物免疫。魔法直伤、魅惑、魔法睡眠、部分削弱与传送等在列表，盲目倾泻法杖会浪费。法杖有附带地形/控制时另查调用链，不能因直伤免疫便断言其全部副作用无效 |
| 巨型 Giant | 所受伤害×0.2；路径攻击范围2；获得 LARGE，不能进入窄处 | 利用它不能进入的门道/狭窄地形，自己仍需离其路径攻击范围2以外；不是站在门的另一侧紧贴着就安全。让其卡住后用安全输出/地形处理，留意原怪本来就有的远程能力 |
| 天佑 Blessed | 命中及闪避×4 | 主动利用能触发的伏击来避免普通命中竞赛；法杖直伤等无需普通武器命中检定的路径更有价值。伏击资格仍受武器、力量、敌人状态等限制；不能承诺“门后必命中”而不核状态 |
| 成长 Growing | 初始约 +20%近战伤害/命中/闪避；所受伤害乘其倒数；每4世界回合 multiplier +0.01 | 能安全处理时避免长期拖延；不要来回刷草/等充能让其持续成长。效果增长不等于真实HP增长；它是减伤倍率改变 |

来源：[Blazing/Projecting](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L126)、[AntiMagic/Giant/Blessed/Growing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L193)、[AntiMagic.RESISTS 完整定义](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/AntiMagic.java#L80)。[Char.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L938) 对精英减伤**向上取整**，所以“伤害×0.2”并不一定把低额正伤害变成 0；这也影响持续伤害与小额多段攻击的估算。[Char.properties](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1411) 添加 Giant 的 LARGE 属性。

成长倍率在 [Growing.storeInBundle/restoreFromBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L291) 持久化；重进不是重置成长的可靠手段。精英与原怪物的攻击方式、抗性、速度、死亡效果同时存在，本表不能代替区域敌人条目。

## 10. 绝命头目：开关差异及恢复时间

详细走位/首领阶段按本专题的区域与首领章节使用。本表记录挑战开关直接改变了什么，防止拿普通局血量/节奏来预算。

| 首领 | 当前挑战差异（相对普通） | 对战士最关键的准备 |
| --- | --- | --- |
| Goo 粘咕 | 100→120 HP；连续满足水中受伤回血条件时 `healInc` 从1长到3，否则归1；蓄力一次直接 `pumpedUp+=2`，等待耗时还会按目标 cooldown 在攻击间隔至3倍间隔间限幅 | 首先把战场离开能让 Goo 持续回血的水；不要按普通“还能再贪一刀”的两步蓄力节奏操作 |
| Tengu 天狗 | 200→250 HP；毒镖陷阱强度8→15（源码注释总毒伤约17→50）；第一阶段陷阱填充率更高；第二阶段每次成功使用非火技能时还补火，技能追赶速度更快 | 火、雷、炸弹与毒陷阱可能叠加；准备解除/抵御异常与高效远程输出，不能依赖普通治疗药瓶兜底 |
| DM-300 | 300→400 HP；主动技能冷却上限9→7（下限5）；毒气量×2；过载节点由两次改为75%/50%/25%三次；启动过载与破墙硬直3→2；岩落伤害6–12→10–20、麻痹3→5 | 预留三段跑塔路径；地表导线随机生成机会1/8→1/4；别把动画电流等同于当前稳定危险图；带移动/净化/气体对策 |
| Pylon 能量塔 | 50→80 HP；环周放电目标格从原模式增加两格；三座需摧毁 | 跑过去后还需要更长输出时间，不能只预算到塔的路程 |
| Dwarf King 矮人国王 | 300→450 HP；第一阶段冷却10–14→8–10、召唤延迟3→2；常规召唤每3次出武僧/术士、每9次为魔像；第二阶段每轮6个，共18；第三阶段保留100 HP而非50，召唤延迟2 | 区域控制和阻断术士线优先；别将国王表面最后100HP当可无条件站桩解决 |
| Yog-Dzewa | 每轮两只拳头、三轮总六只，安排保证同一配对不会同轮；光束伤害20–30→30–50；常规召唤池六项固定含两只 YogRipper，其余四项由存活恶魔血巢数决定，可含 YogEye/YogScorpio | 为完整六拳而非三拳保留控制；破坏四个血巢仍很有价值，即使全清也不消除所有撕裂者；火把和双拳站位预算必须独立 |

主来源：[Goo](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L102)、[Goo.doAttack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L208)、[Tengu](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L487)、[TenguDartTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/TenguDartTrap.java#L36)、[PrisonBossLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/PrisonBossLevel.java#L680)、[DM300](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L487)、[FallingRock](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L693)、[CavesBossLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CavesBossLevel.java#L131)、[Pylon](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Pylon.java#L90)、[DwarfKing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L202)、[Yog 召唤池](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L108)、[Yog 激光](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L225)。

**文字说明与代码要分开：** 原生说明称 DM-300 过载“更快”，当前 `speed()` 的 `supercharged ? 2 : 1` 本身不含挑战分支；明确挑战差异包括过载启动和破墙等待从 3 降为 2、地表导线及技能变化。不能另造一个“挑战下移动速度又乘某倍数”。Yog“激光+60%”是区间均值由25→40的比率，实际伤害范围为30–50，不能把普通每次掷骰简单乘1.6。

### LockedFloor：停止饥饿，不等于无限免费休息

[LockedFloor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/LockedFloor.java#L29) 初始可用被动恢复时间由50减为20，每tick递减；`addTime` 上限50；`regenOn` 只在剩余≥1为真。造成符合条件的首领伤害通常补充时间，九挑下量变少：

| 伤害目标 | 挑战下补时 | 普通补时 |
| --- | --- | --- |
| Goo | dmg | 1.5×dmg |
| Tengu | 2×dmg/3 | dmg |
| DM300 / Pylon | dmg/2 | dmg |
| DwarfKing | dmg/5 | dmg/3 |
| Yog 本体 | dmg/3 | dmg/2 |
| YogFist | dmg/4 | dmg/2 |

各类使用 `dmg` 还是实际 `dmgTaken` 以方法实现为准；免疫/无敌时的检查不同，不能根据一次攻击动画宣称补时成功。Goo 在水里回血还会扣恢复时间（挑战扣 `healInc`，通常扣1.5倍）。相关分支：[Goo.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L275)、[Tengu.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L156)、[Pylon.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Pylon.java#L205)、[DwarfKing.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L486)、[Yog.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L440)、[YogFist.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L154)。

**战术推导：** 已安全拉开、又有近期有效输出补时，可以利用短恢复窗口；不能无限等法杖/圣杯。某项独立直接回血不检查 `regenOn()`，可能仍继续，但要查它自身代码；把所有被动效果都当“LockedFloor一归零全失效”同样不准确。首领前的充能、食物、火把、符石准备不能推迟到锁层后再补。

## 11. 全九项的组合优先级与可利用之处

1. **把少受攻击作为第一防御资源。** 信念护体使高甲站桩收益低，恐药/荒芜切断主要恢复，精英与绝命头目又提高单次失误代价。可控的失去敌方视野、拉到单入口、先手输出、移动/致盲/眩晕等通常同时应对多项限制。是否能做到由当前地形、弹道、动作耗时和敌人特性决定。
2. **把非食物饱腹与非药剂回血单列。** 净化、治疗井、自然鞋阳春草、战士进食天赋、催眠、吸血等不能随常规治疗药剂一起判废；又不能把随机/耗时/原地治疗当即时救命水。列清“现在能回复多少”“需要几回合”“恢复期间谁能攻击”。
3. **把种子的用途转移到炼金和工具。** 不能种草救命，但可制药/涂镖/鞋子效果/应急投掷；不要因种植失败丢掉所有种子。再生法杖仍可能提供草与扎根的战术价值。
4. **允许利用集群召集减少往返，但必须有退出条件。** 一扇门后的已知安全口袋可以让附近怪主动来，节省饥饿与照明；一旦出现天佑远程、索敌、巨型的特殊范围或第二方向来怪，立即重做计划，不能执行预设“守门十回合”。
5. **少量升级要求有主次，不要求机械单一构筑。** 早期仅2张、监狱结束仅3张；将任务装备、可回收的一阶印章升级、刻印/法杖/投掷/神器的独立成长路径交叉比较。当前战士特点以本专题战士章为准。
6. **火把与食物用实际路线预算。** 避免为了一个价值不确定的物品反复往返；也不能为了省50饱腹而漏掉能决定构筑的已知安全奖励。价值判断要考虑收集路线和下一首领阈值。
7. **重进游戏不是常规回血/清除挑战手段。** 本文确认 Hunger 的 level/partialDamage、Regeneration.partialRegen、LockedFloor.left、ChampionEnemy.Growing.multiplier、SwarmIntel.timeSeenAt 都保存。具体场景重建差异按专门重载章节判断，不把界面重画/粒子消失当取消攻击。

### 每层开始的九挑速查卡

- 本层可见怪是否精英，精英效果与原敌人叠加后，哪个可在下一次行动打到我？
- 退路上最近能真正断开视野的门/拐角/高草在哪里？巨型/索敌是否仍能隔着它打到我？
- 当前饱腹能支持多少实际回合，食物天赋是否可触发，退出 starvation 有何成本？
- 可用恢复中，哪些是即时、哪些随机、哪些要睡眠/原地等待，哪些药剂会中毒？
- 还剩几张主线升级预期、当前可用装备力量要求是否满足？
- 火把剩余覆盖探索还是首领；接下来是否必须预留一根？
- 能否以一次合适的净化/治疗井同时解决多个问题？为等待最大收益多承受的风险是否更大？

## 12. 覆盖范围、复核方法与尚未验证的边界

本次检索覆盖 `core/src/main/java` 中九个 `Challenges.*` 常量的全部直接引用，再跟进关键基类/间接调用；并检查 `Challenges.isItemBlocked` 的未限定常量引用。直接分支族如下，未来代码更新应重跑检索而不是只看本文：

- `NO_FOOD`：Food、HornOfPlenty、WellFed。
- `NO_ARMOR`：Armor、WandOfLivingEarth（运行规则和展示）。
- `NO_HEALING`：PotionOfHealing、PotionOfShielding、ElixirOfAquaticRejuvenation、UnstableBrew；间接调用追到 HoneyedHealing、Blandfruit、RegrowthBomb。
- `NO_HERBALISM`：Challenges、Level.plant、Plant.Seed、GameScene、CursedWand。
- `SWARM_INTELLIGENCE`：Mob.processSwarmIntel。
- `DARKNESS`：Level、RegularLevel、MiningLevel、VaultLevel、VaultEntranceRoom、YogDzewa、WandOfPrismaticLight、Radiance、ElementalBlast。
- `NO_SCROLLS`：Level.create；配合 Dungeon.souNeeded 计算额度。
- `CHAMPION_ENEMIES`：ChampionEnemy.rollForChampion；配合 Dungeon 初始化/保存及 Char 通用伤害与属性机制。
- `STRONGER_BOSSES`：LockedFloor、Goo、Tengu、PrisonBossLevel、TenguDartTrap、DM300、Pylon、CavesBossLevel、DwarfKing、YogDzewa、YogFist。Yog 的特殊徽章判定也有该开关，但九挑通关不要求保留四血巢去追额外徽章。

尚未现场验证：自然鞋在本项目实际 CLI UI 中的操作表现、净化期间蜂蜜合剂组合、肉派零计数末次回复的观察时点、精英碰撞/状态变化与各具体敌人的组合。这些具有代码依据，仍需在授权游戏的当前公开操作/效果中复核。本文未读取隐藏生成计数/存档来给任何一局定位资源，也不提供未见地图、敌人或未来随机数。
