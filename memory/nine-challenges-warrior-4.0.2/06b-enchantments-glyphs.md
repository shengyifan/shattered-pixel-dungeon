# 战士九挑战：武器附魔、护甲刻印与诅咒

研究日期：2026-10-10。源码基线：`8405ae82f`，游戏 4.0.2 / code 922，CLI.9.0.3。本文是源码静态调查；没有启动游戏、读取个人存档或用真实局验证概率。数值、名称和可用操作在实战时仍须以当前公开观察确认。

本文覆盖当前注册的 **17 种武器附魔、10 种武器诅咒、13 种护甲刻印、8 种护甲诅咒**。中文名取自 [items_zh.properties](../../core/src/main/assets/messages/items/items_zh.properties#L2)。与基础装备章节配合阅读。

## 先掌握共同规则

- 下文 `L=max(0,buffedLvl)`，`M` 为实际附魔/刻印倍率，默认 1。写成 `P=公式×M` 的是每次进入该 `proc` 后的概率，不是每回合概率。一般武器须成功命中，护甲刻印须进入受击处理；躲掉的攻击不会替你充法杖。常规攻击先执行防御方 `defenseProc`，再减普通 DR，再处理粘稠，再执行武器 `attackProc`：[Char.attack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L388)。
- 多数附魔在 `P>1` 后把超额概率转成 `Q=max(1,P)` 的效果强度；不可一律把超过 100% 的部分当浪费。无概率的效果通常直接乘 `M`。表中未特别写 `Q` 的基础效果按默认倍率解释，精确倍数见对应方法。
- `M` 以奥术之戒 `1.175^有效戒指加成` 为基础；武器额外吃狂战士怒气催化：`min(1,怒气)×0.15×天赋点数` **加到倍率上**，满怒 3 点是加 0.45，而非全体附魔触发率直接加 45 个百分点；护甲无此狂战士加成。[Weapon.Enchantment.genericProcChanceMultiplier](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L548)、[Berserk.enchantFactor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L168)、[Armor.Glyph.genericProcChanceMultiplier](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L822)、[RingOfArcana](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/rings/RingOfArcana.java#L60)。
- 诅咒通常也吃 `M`，所以奥术戒并非对所有诅咒都安全。极化可以转成稳定增伤，喧闹、炫目、血祭、恶臭则更频繁。
- `MagicImmune` 会停用武器附魔及护甲刻印；需要以抗魔消除危险状态时，不能同时默认索敌、狱火、迷彩仍工作。[Weapon.proc/hasEnchant](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L137)、[Armor.hasGlyph](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L751)。

## 九挑战中特别有价值的结论

1. **信念护体没有禁用刻印。** `NO_ARMOR` 将普通护甲 DR 改为 `0 .. 1+tier+L+防御附魔修正`，不统一削弱刻印。涌流/迷彩/狱火/敌法和控制刻印仍完整工作；不能因此把护甲视为无价值，但少量升级是否用于护甲要比较整套资源。[Armor.DRMax/DRMin](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L379)。
2. **药物禁用不是所有回血都禁用。** 血饮附魔和代谢诅咒直接增加 HP，没有 `NO_HEALING` 分支；血饮可形成额外恢复来源，代谢把本来能用于自然恢复的饱食立即变现，受饥饿挑战牵制，不能靠挨打白赚血。[Vampiric.proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Vampiric.java#L38)、[Metabolism.proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/Metabolism.java#L41)。
3. **荒芜之地不等于高草失效。** 繁茂仍可生成高草，遮挡视线、重置接敌和触发迷彩均有用；种子仍能掉落并用于炼金，露珠会被 `Challenges.isItemBlocked` 拦下。不能规划露珠回血。[Blooming.plantGrass](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Blooming.java#L96)、[HighGrass.trample](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/HighGrass.java#L127)、[Challenges.isItemBlocked](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Challenges.java#L71)。
4. **丛生诅咒仍会直接激活植物。** 荒芜之地拦的是 `Seed.onThrow` 种植入口；丛生直接 `couch(...).activate(defender)`，不走这个检查。它可能触发阳春草恢复，也可能毒、火、冰、传送或其他危险植物。这是源码支持的随机机会，绝不是九挑战稳定治疗方案。[Overgrowth.proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/Overgrowth.java#L42)、[Plant.Seed.onThrow/couch](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Plant.java#L153)、[Sungrass.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Sungrass.java#L50)。
5. **缠绕给的是站定土甲，当前代码不额外给扎根。** 只要站在原格，土甲每次普通防御处理可挡 `floor((scalingDepth+5)/2)`，容量约 `5+2L`；移动离格即解除。它不受信念护体的普通护甲 DR 公式直接削减，也不走种植入口。不能把“缠绕”名字推断成免疫击退。[Entanglement.proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Entanglement.java#L42)、[Earthroot.Armor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Earthroot.java#L90)。
6. **磐岩不是九挑战通用解。** 它把本可闪避的攻击变为命中，以 75% 的原闪避概率折算减伤；普通护甲 DR 小时，不能期待高 DR 抹掉余伤，还会更频繁吃命中附带效果。优势是伤害更平滑，缺点是失去整击闪避；特别慎对附带减益的敌人。[Hero.defenseSkill](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L601)、[Stone.proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Stone.java#L43)。

## 完整武器附魔表

普通随机附魔按组权重 50/40/10，再在组内均匀选择：常见 5 种各 10%，罕见 8 种各 5%，稀有 4 种各 2.5%。这只适用于无排除项的 `Enchantment.random()`，不是“每件装备有该附魔的概率”。普通装备另有约 10% 正面附魔、30% 诅咒的生成入口，且受羊皮卷轴碎片等规则影响。[概率表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L518)、[Weapon.random](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L421)。

| 附魔 / 稀有度 | 源码效果与量化条件 | 战士九挑战判断 |
| --- | --- | --- |
| 烈焰 Blazing / 常见 | `P=(L+1)/(L+3)×M`。未燃烧则点燃 8 回合；已燃烧时追加约 `0.67×Normal(1,3+depth/4)×Q` 魔法伤害。初次点燃消耗一份 Q。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Blazing.java#L40) | 伤害/风筝强，但火可毁草、卷轴并威胁自身；没有防火和撤离空间时需慎选。火免疫敌人无此收益。 |
| 寒霜 Chilling / 常见 | `P=(L+1)/(L+4)×M`，每次增加 `3Q` 回合 Chill，总持续限制 `6Q`。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Chilling.java#L38) | 延缓近战敌人，有射程/移速优势时用于少挨打；不是冻结，更不是保证每击控制。 |
| 恒动 Kinetic / 常见 | 下击加入所储伤害；击杀的超额伤害减去此次使用的旧储量后，再乘 M 形成新储量。每回合衰减 `max(2.5%储量,0.1)`，向上取整作为可用加伤。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Kinetic.java#L39)、[Char.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1008) | 适合高单击伤害/接连击杀；旧储量不能无限滚雪球，也不该为了保储量冲入包围。 |
| 电击 Shocking / 常见 | 固定 `P=M/3`。原目标不吃额外电击；向周围路径距离 2 传播，后续干地跳 1、水上非飞行者跳 2，对异阵营链中角色造成 `round(原伤害×0.5Q)`。排除攻击者，不伤同阵营。[proc/arc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Shocking.java#L47) | 群体战收益高，单挑基本没有额外伤害；不要为触发链而主动被围。水扩大链距，非任意穿墙。 |
| 猛毒 Venomous / 常见 | 固定 `P=M/3`，延长毒 `Q×(3+L/2)`；新上毒首跳延迟 3 回合，已有毒不重置这段等待。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Venomous.java#L39) | 适合上毒后脱离；毒抗/免疫目标差。首中不会马上替你杀敌。 |
| 招架 Blocking / 罕见 | `P=(L+4)/(L+40)×M`，给 `round((2+buffedLvl)Q)` 护盾，5 回合期限，每次触发 `setShield` 而非累加。[proc/BlockBuff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Blocking.java#L45) | 护盾不按信念护体护甲公式削弱，能补战士生存；基础触发率低，不得当下一击必有的保命承诺。 |
| 繁茂 Blooming / 罕见 | `P=(L+1)/(L+3)×M`，随机舍入生成 `(1.5+0.1L)Q` 草格；先敌人格再周围，攻击者格最后。仅若干地面类型可长；`regenOn=false` 时改生犁草。[proc/plantGrass](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Blooming.java#L44) | 配迷彩、断视线、种子资源很强；NO_HERBALISM 不禁生成草。草改变视野和目标选择，但没有SOLID，标准PROJECTILE/MAGIC_BOLT/STOP_SOLID射线不会仅因高草被挡；失视也不证明已蓄力攻击取消。每次触发后重观测，见08。 |
| 异质 Eldritch / 罕见 | `P=(L+1)/(L+4)×M`；使能看见攻击者或受击者的其他异阵营角色恐惧 `5Q` 回合。跳过攻击者、原目标、同阵营。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Eldritch.java#L40) | 群体战隔离其他敌人，原先正在打的目标不会因此逃跑；恐惧者可能绕路回来，不能等同永久清场。 |
| 弹性 Elastic / 罕见 | `P=(L+1)/(L+5)×M`，沿原攻击方向延长的弹道推 `round(2Q)` 格；近战允许碰撞伤害，投掷/灵弓不启用该项。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Elastic.java#L38) | 制造距离、推落悬崖；先看被推方向、落点和敌人免推属性。对远程敌人可能把它推回远射位置。 |
| 幸运 Lucky / 罕见 | `P=(L+4)/(L+40)×M`，标记只覆盖本次攻击击杀时的额外耗材掉落；基础 `ringLevel=-5`，注释对应普通80%/非普通20%。不在此击杀则短暂标记消失；下一次失败触发清掉旧标记。受 `hero.lvl <= maxLvl+2` 掉落门槛。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Lucky.java#L39)、[Mob.rollToDropLoot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1097) | 紧缺资源局长期收益，但不能无限刷低级怪；不提高这击的即时伤害/防御。 |
| 索敌 Projecting / 罕见 | 近战射程加 `round(M)`。投掷：目标为可通行/危险可行格或有角色，且距离不超过 `round(4M)`，可直接指定落点绕过普通投掷阻挡。[reachFactor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L334)、[MissileWeapon.throwPos](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L192) | 长柄再加射程、隔障投掷很有价值；近战仍受可攻击几何规则，不能把投掷穿障特性移植给所有近战。 |
| 紊乱 Unstable / 罕见 | 每击均匀随机选 14 种效果，再按选中附魔自身概率执行；排除索敌、魔晶和自身。旧恒动储伤额外加入并清空。[列表/proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Unstable.java#L34) | 无法保证控制，可能点火/位移破坏计划。可用但不适合作为九挑战精确保命的核心。 |
| 锋锐 Vorpal / 罕见 | 固定 `P=M/4`。在 `Char.damage` 把这击直接伤害转为 `Q×(2+dmg/2)` 的流血初始参数，随后 `Bleeding.set` 随机预衰减；不是“普通伤害再加流血”。若直接伤害严格 `>HP+shield`，取消转换立即杀；等于时仍可能转换。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Vorpal.java#L37)、[转换/提前返回](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L913)、[Bleeding.set/act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Bleeding.java#L75) | 强力延迟伤害，适合打后退；不要把敌人血条估计见底当作当回合必杀。重复 set 取较强值，不简单累加。 |
| 腐化 Corrupting / 稀有 | `P=(L+5)/(L+20)×M`。友方攻击者命中未腐化、非免疫的存活 Mob 时设短 tracker；该击杀死后复制并腐化该敌人，高 Q 还给肾上腺素。[proc/tracker](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Corrupting.java#L41) | 将必经击杀转成援军，减少群敌压力；不能对任意 Boss 保证生效，也不是非致死命中立即收服。 |
| 魔晶 Crystal / 稀有 | 每击额外 `ceil(0.33×传入伤害×M)` 魔法伤害。近战有魔晶耐久，携带期间在 `regenOn()` 下每回合修复0.2，投掷修复减半；破碎会失去武器。[proc/repair](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Crystal.java#L59)、[CrystalRepair](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Crystal.java#L225) | 需监看公开完好/瑕疵/裂纹描述。当前耗损表达式与注释冲突，见下方专门说明；不得凭“平均50击”推断必然寿命。 |
| 死神 Grim / 稀有 | 命中后对仍有 HP 的目标，`P=(0.5+0.05L)×M×(已损HP比例)^2`，追加当前 HP 乘其 Grim 抗性；免疫不进入。不是基础50%秒杀。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Grim.java#L37)、[Char.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L991) | 对残血高血量怪有帮助，首击满血不稳定，首领抗性/免疫另核。 |
| 血饮 Vampiric / 稀有 | `P=(0.05+0.25×缺血比例)×M`；对异阵营且非普通中立者（宝箱怪例外），回复 `round(0.5×传入伤害×Q)`，不超最大HP且必须尚活着。无 NO_HEALING 分支。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Vampiric.java#L38) | 九挑战珍贵恢复；低血提升触发率只是补偿，不能主动压血赌触发。源码用传入伤害，不是读取敌人最终扣血数。 |

### 魔晶耐久的源码与注释矛盾

`Crystal.durability` 初值是 100；`proc` 的实值为：

```text
avgUses = 50 - min(20, 20*weapon.buffedLvl()/6)  // 近战
durability -= NormalFloat(0.5,1.5) * (1/avgUses) * weapon.delayFactor(attacker)
```

在 +0、耗时 1 的近战武器上，每击平均仅减约 0.02；同期允许修复时每回合加 0.2。源码注释写“平均50击”与此相差100倍。本文只确认表达式，不修正原生规则、不声称已验证无限耐久；耐久可能来自已有实例状态，公开裂纹警告仍应认真对待。升级若保留魔晶则修复100；耐久和视觉耐久都有序列化，不支持“重进自动修好”的假设。[Crystal字段/损耗](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Crystal.java#L50)、[保存恢复](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/enchantments/Crystal.java#L201)、[升级修复](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L399)。

## 完整护甲刻印表

随机权重也是组内均分的50/40/10：常见4种各12.5%，罕见6种各6.67%，稀有3种各3.33%。[Glyph概率表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L793)。

| 刻印 / 稀有度 | 源码效果与量化条件 | 战士九挑战判断 |
| --- | --- | --- |
| 晦暗 Obfuscation / 常见 | 潜行值加 `(1+L/3)M`。[stealthBoost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Obfuscation.java#L38) | 降低被发现风险、改善先手；不是隐身，也不会取消已经在追击的敌人。 |
| 迅捷 Swiftness / 常见 | 无敌方角色处于从自身出发、可通行路径3格内时，移速乘 `(1.2+0.04L)M`；可见陷阱与坑因该地图定义可算路障。[speedBoost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Swiftness.java#L43) | 探索省饥饿/照明，拉开3格后逃离有用；贴身没有加速，不可用公开加速倒推隐藏敌人具体位置。 |
| 粘稠 Viscosity / 常见 | 普通 DR 后延缓 `ceil(dmg×(L+1)/(L+6)×M)`；比例>1时先把伤害除以比例，全部延缓。首跳晚1回合，此后每回合扣 `max(1,floor(延迟池×0.1))`，总池写入存档；钢铁意志食物免疫存在时跳过刻印。[deferDamage/DeferedDamage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Viscosity.java#L67) | 主要是争取行动时间，默认不会抹去伤害。九挑战不能靠普通疗伤药解围，更要把延迟池算入生存预算；重进不清池。 |
| 电势 Potential / 常见 | `P=(L+1)/(L+6)×M`；玩家被击时对随身法杖执行 `belongings.charge(Q)`。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Potential.java#L40) | 多法杖时回报高，但九挑战挨打昂贵；应把不可避免命中换成额外控制，不为充能主动受击。 |
| 狱火 Brimstone / 罕见 | `glyphLevel>=0` 就加入 `Burning` 免疫，无需随机触发。[Char.isImmune](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1380) | 能解锁安全用火/穿火战术；源码保证的是 Burning，不能概括为所有名称含火的直接魔法都免疫。抗魔状态会停用刻印。 |
| 磐岩 Stone / 罕见 | 强制自身闪避为0；受击计算原本命中率 h，剩余伤害系数 `clamp(0.25,(1+3h)/4,1)`。原闪避越高减伤越多，最多75%。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Stone.java#L43) | 平滑伤害而牺牲整次闪避。九挑战低DR且敌人有命中减益时要慎重；不是每次减75%。 |
| 缠绕 Entanglement / 罕见 | 固定 `P=M/4`，土甲容量设为至少 `round((5+2L)Q)`，只在原格有效，每击最多挡 `floor((scalingDepth+5)/2)`。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Entanglement.java#L42)、[Earthroot.Armor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Earthroot.java#L90) | 狭道站定战实用，不受荒芜禁种植影响；不为保土甲而站在已预警的危险格。 |
| 反斥 Repulsion / 罕见 | 仅相邻攻击者，`P=(L+1)/(L+5)×M`，向远离自身方向推 `round(2Q)` 格，可碰撞伤害。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Repulsion.java#L37) | 缓解围攻、制造逃跑窗口；推走远程怪反而可能让其射击，后续须重新判断。 |
| 迷彩 Camouflage / 罕见 | 踩真正高草后隐身 `round((3+L/2)M)` 回合；HighGrass 对犁草的分支不调用激活。[activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Camouflage.java#L43)、[HighGrass](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/HighGrass.java#L61) | 九挑战高价值的脱离/绕位/伏击工具。繁茂在 Boss 禁自然恢复阶段产生的是犁草，不能当作无限迷彩。 |
| 涌流 Flow / 罕见 | 站水上移速乘 `(2+0.5L)M`，不限附近有无敌人。[speedBoost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Flow.java#L41) | 已知连续水域可强力风筝。离水即失去，需核下一格水地形以及电/冰危险。 |
| 魅惑 Affection / 稀有 | `P=(L+3)/(L+20)×M`，使攻击者对自己魅惑 `round(10Q)` 回合。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Affection.java#L39) | 随机创造撤离时间；继续攻击会影响魅惑寿命，不应触发后机械连续打。 |
| 敌法 AntiMagic / 稀有 | 指定来源伤害额外挡 `Normal(round(LM),round((3+1.5L)M))`。列表包括 DM-100、萨满、术士、邪眼、光暗拳等光束/法术，不包括所有危害。[RESISTS/drRoll](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/AntiMagic.java#L80) | 信念护体下仍保留独立法术减伤，针对后期远程很有价值；不能误判为免疫毒/火/腐蚀/Yog穿墙射线。 |
| 荆棘 Thorns / 稀有 | `P=(L+2)/(L+12)×M`，给异阵营攻击者初始流血参数 `round((4+L)Q)`，实际衰减按 Bleeding。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Thorns.java#L36) | 被迫接战的反伤补偿；免流血目标收益差，不能拿命换触发。 |

`Regeneration.regenOn()` 只检查锁层恢复条件，并不等同“未饥饿”或“药物禁用关掉自然恢复”。繁茂高草/犁草和魔晶修复依赖的是这个函数。[Regeneration.regenOn](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L113)。

## 完整武器诅咒表

`randomCurse()` 在当前10种中均匀抽取；排除旧诅咒时余项均分。以下概率均再乘 M。[curses/randomCurse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L537)。

| 诅咒 | 效果 | 风险或可利用条件 |
| --- | --- | --- |
| 喧闹 Annoying | 1/20：召唤全层 Mob 向攻击者位置靠拢，解除隐身。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Annoying.java#L44) | 与集群智能、黑暗叠加极危险；即使某次可在狭道接敌，也不把全层引怪当稳定收益。 |
| 炫目 Dazzling | 1/10：能看见受击格的角色致盲，攻击者10回合、其余5回合。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Dazzling.java#L41) | 自己失去情报/伏击条件，敌方致盲只是补偿；不知道周边敌人时尤其差。 |
| 转移 Displacing | 1/12：非 IMMOVABLE 敌人随机传送；若正在狩猎改成游荡。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Displacing.java#L41) | 能暂脱离强敌，但击杀掉落被拖延、敌人重新出现不可控。 |
| 易爆 Explosive | 耐久初100，每击减 `round(Int[0,10]×M)`；过50/10阈值警告，耗尽爆炸再加100。爆点为敌人周围最靠近攻击者的非实体阻挡格，相邻近战通常炸自己。投掷额外损9次使用耐久。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Explosive.java#L50) | 长射程/投掷有几何利用空间，近战危险。温度与计数保存，进主菜单重进不清零。 |
| 友善 Friendly | 1/10：自己对目标魅惑10回合，目标对自己5回合且忽略本次打击；若已被该目标魅惑则这击伤害为0。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Friendly.java#L37) | 可随机创造撤离窗口，却会阻断击杀，不能依赖稳定输出。 |
| 极化 Polarized | `P=M/2` 成功伤害×1.5，否则伤害0。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Polarized.java#L34) | 默认平均仅0.75倍伤害、零伤害极危险。**M>=2 时成为100%触发的1.5倍伤害**；单未诅咒奥术戒+4给有效加成5，`1.175^5≈2.24`，+3约1.906不足。怒气催化可降低门槛，但怒气衰减后必须重新算。 |
| 激流 Pressurized | 1/8：在敌人位置激活喷泉效果；近战设置中心击退方向。附近变水、灭火，并把中心/邻近角色推2格，邻格包括战士。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Pressurized.java#L37)、[GeyserTrap.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GeyserTrap.java#L55) | 可灭火、造水配涌流、推敌；也可把自己推下坑或撞墙。近战必须同时核自身与敌人落点。 |
| 血祭 Sacrificial | 1/10：令 `b=(HP/HT)^2×HT/8`，再以 `min(1,b)` 判定给自己 `max(1,b)` 流血参数。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Sacrificial.java#L36) | 越高当前HP自伤参数越大，但九挑战无法把自伤当免费怒气；低血时也不完全安全。 |
| 妄为 Wayward | 无debuff时1/4加10回合妄为；已有时下一次进入成功命中 proc 清掉。期间该武器命中倍率除5。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Wayward.java#L38)、[accuracyFactor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L289) | 利用可靠伏击/必中机制可缩短影响，但普通连续攻击可能多次落空，浪费血与饥饿。 |
| 奇迹 Wondrous | 1/8调用 `CursedWand.randomValidEffect`；奇迹树脂可提高只抽有益效果的机会。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/curses/Wondrous.java#L39) | 依赖诅咒法杖完整随机池，默认不可控；不能把“可能治疗/传送”当紧急操作。 |

极化可利用条件是源码推论，不是推荐为了赌它消耗九挑战的大部分升级；还需与戒指槽、基础伤害、解除装备绑定及备选武器成本比较。

## 完整护甲诅咒表

`randomCurse()` 在8种中均匀抽取。以下触发概率均乘 M；臃肿用移速公式。[Glyph.curses](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L811)。

| 诅咒 | 效果 | 风险或可利用条件 |
| --- | --- | --- |
| 反熵 AntiEntropy | 1/8：冻结周围8格；自己若不站水中则燃烧4回合。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/AntiEntropy.java#L44) | **站水可避免这次自燃**，用敌人冻结争取逃跑；但需要主动吃命中，且水边冰冻/道具损毁另核，不视为稳定防御。 |
| 臃肿 Bulk | 站关闭门/开门格时移速倍率 `M/3`，其他格正常。[speedBoost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/Bulk.java#L42) | 会破坏门后反复伏击/撤退时序。高M会减轻甚至反转惩罚，但不值得未算装备成本就追。 |
| 腐蚀 Corrosion | 1/10：自身与周围8格角色沾半持续的 Ooze，即10回合。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/Corrosion.java#L39) | 水可洗掉自身黏液，仍需结合敌人/水位置；无水时长期额外失血很危险。 |
| 定相 Displacement | 1/20：尝试将自身随机传送，当前伤害返回0，即使传送调用失败也返回0。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/Displacement.java#L35) | 有概率避伤/脱离，但可能落入未知区域，受黑暗与集群夹击；不能把下一次被打当必救。 |
| 代谢 Metabolism | 1/6：若是玩家、缺血且尚未饥饿，回复最多4HP，每HP消耗10饱食。先扣饱食再直接加HP。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/Metabolism.java#L41) | NO_HEALING不禁；只能把有限饱食快速换血，饥饿后无效。无食挑战下仍不推荐主动挨打换。 |
| 分身 Multiplicity | 1/20：若身边可生成，玩家有一半概率生成镜像，否则复制攻击者或生成本层怪。复制恢复满血，保留大部分buff；不会复制插着的投掷物/贼偷的物品等。[proc/duplicate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/Multiplicity.java#L54) | 可得帮手，也会复制危险敌人、冠军状态；额外敌人不能按普通净收益算。不能复制偷窃物品套利。 |
| 丛生 Overgrowth | 1/20：从种子默认表随机生成临时植物对象，立即在自身激活；不走荒芜禁种植入口。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/Overgrowth.java#L42) | 有机会阳春草治疗，但也有火毒等；已见阳春效果且当前位置安全时才讨论站定收益，不允许靠盲受击求治疗。 |
| 恶臭 Stench | 1/8：自身格生成体积250毒气。[proc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/curses/Stench.java#L37) | 有净化/毒气免疫或可靠撤路时可伤敌；否则无药物恢复条件下极危险，封闭房/门口尤其差。 |

## 诅咒绑定、重附魔和升级必须分开判断

`Item.cursed` 决定装备绑定等诅咒状态；`enchantment.curse()` / `glyph.curse()` 决定负面附加效果。二者是不同字段。

- 驱邪卷轴 `uncurse` 同时清 `cursed` 并移除负面附魔/刻印，因此是完整驱邪。[ScrollOfRemoveCurse.uncurse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRemoveCurse.java#L119)。
- 附魔石/注魔卷轴只调用 `enchant`/`inscribe` 替换效果，该 setter **不会把 `cursed=false`**。所以负面效果改成正面后，绑定可能仍在。[StoneOfEnchantment](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfEnchantment.java#L59)、[Weapon.enchant](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L454)、[Armor.inscribe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L726)。
- 普通升级清 `cursed`，但负面附魔只有1/3概率移除；解除绑定不等于负面效果消失。投掷武器另有“仍有负面附魔则恢复原诅咒状态”的特例。[Weapon.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L377)、[Armor.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L458)、[MissileWeapon.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L154)。
- 正面附魔在普通升级从 +4/+5/+6/+7/+8 时分别有10/20/40/80/100%消失风险；硬化先在从+6/+7/+8/+9/+10时按相同序列掉硬化。注魔秘卷的选择界面给“常见1、罕见1、随机1”，不是保证给稀有。实战必须权衡升级武器和保留核心控场附魔。[Weapon.upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L377)、[ScrollOfEnchantment选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfEnchantment.java#L122)。
- 诅咒注能使装备绑定，增加等级 `1+floor(原等级/6)`，并保留/抽取负面效果；重复注能会换掉原诅咒。移除负面附魔时注能加成也消失。只有已核实可承受的诅咒组合才考虑，不把额外等级当免费升级。[CurseInfusion.onItemSelected](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/CurseInfusion.java#L58)、[Weapon.level/enchant](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L366)。

## 实战检索顺序

1. 看当前装备的真实公开名称、等级、力量需求、附魔/刻印及诅咒绑定状态；不靠此前出现过的句柄辨认物品。
2. 先问能否减少承受攻击：射程、涌流、迷彩、寒霜、弹性通常比“挨打后才有收益”的效果更好规划。
3. 再确认敌人免疫、场地火草水、反伤/击退路径；非当前敌人的状态不能从附魔触发反推。
4. 把恢复来源按真实入口分清：血饮/代谢/丛生阳春草不等于药剂治疗，也不保证触发。
5. 凡涉及随机附魔、未知触发、位移/新草/隐身解除，完成一击后重新观察；不得在旧弹道/旧敌位上继续批量出手。
