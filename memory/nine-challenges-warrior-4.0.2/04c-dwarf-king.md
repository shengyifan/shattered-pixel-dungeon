# 九挑战战士：矮人国王专项研究

版本基线：2026-10-10，仓库 `8405ae82f206e965d14b4a3b9d4fb282f2230e47`，游戏 4.0.2 / code 922。本文只分析本地源码，没有启动游戏、读取存档或验证实战。数值是类的基础规则，不是对任何当前敌人的隐藏状态读数；回合均指原生 Actor 时间，不能直接换成玩家操作次数。战斗时必须重新检查当前地图、视野、公开召唤提示、敌人和血条。

## 先记住这六条

1. **绝命头目国王是 450 HP，第一阶段只能压到 100 HP；多余单击伤害不能跳过第二阶段。** 进入二阶段会清掉敌对旧部属和未完成的旧召唤，因此接近门槛时，安全地推进国王阶段往往比逐个清理全部旧杂兵省资源。
2. **第二阶段是 450 护盾和 18 个部属的关卡。** 普通攻击不能伤王；部属真正死亡或转成友军才结算对应的 25 点护盾伤害。食尸鬼倒地并被同伴维持，不算真正死亡。
3. **第三阶段国王仍有 100 HP，伤害先全部进入递减结算的延迟池。** “已经造成 100 点待结算伤害”远不等于“马上死”。只投入刚好致死的池再藏起来，可能要承受很长的追加召唤期。
4. **不能靠麻痹国王停住全部召唤。** 一阶段先处理召唤才检查麻痹；二阶段召唤分支直接返回；三阶段同样先召唤才进入普通 AI。减速则通过普通 `Char.spend` 改变国王自身行动时间，和麻痹不是同一种效果。
5. **术士射线与魔像拉人必须分别处理。** 雕像遮断术士 `MAGIC_BOLT` 可用；魔像拉人采用非实心格连通距离判定，可以绕障碍，不等于普通直线弹道。
6. **没有小怪经验或战利品农场。** 国王召唤的四类部属均设置 `maxLvl=-2`；杀怪掉落/经验等级门槛使正常战士无法从中补给。首领层也没有普通复生器。

来源：[国王属性与行动](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L83)、[阶段转换](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L492)、[召唤实例](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L697)、[经验](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L989)、[掉落等级门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1097)、[首领层无复生器](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CityBossLevel.java#L263)、[减速时间尺度](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1167)。

## 场地、基础战斗与挑战差异

`CityBossLevel` 有四个召唤基座、中央王座和两侧雕像。英雄越过南入口门进入竞技场时封门并生成国王；进入之前完成食物、火把、装备、快捷栏和法杖充能准备。北门在击杀前保持锁定，地形有效性也禁止英雄绕到北门以外。不要把静态源码布局当成当前已观察地图，更不能直接套未观察坐标。[布局](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CityBossLevel.java#L66)、[王座与雕像](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CityBossLevel.java#L162)、[封门与位置限制](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CityBossLevel.java#L306)。

| 项目 | 普通国王 | 开启绝命头目后 |
| --- | --- | --- |
| 初始 HP / 二阶段护盾 | 300 / 300 | 450 / 450 |
| 一阶段结束 HP | 50 | 100 |
| 一阶段召唤/技能基础冷却抽样 | 10–14 | 8–10 |
| 一、三阶段召唤延迟参数 | 3 | 2 |
| 一、三阶段召唤种类循环 | 每第 4 个武僧或术士 | 每第 3 个武僧或术士，但每第 9 个改为魔像 |
| 二阶段部属总额 | 12 | 18 |
| 单个二阶段部属完成后的王盾伤害 | 300/12=25 | 450/18=25 |
| 攻击 / 防御能力 | 26 / 22 | 相同 |
| 近战伤害 / 额外 DR 抽样 | 15–25 / 0–10 | 相同 |

来源：[基础属性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L83)、[冷却](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L112)、[召唤循环](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L304)、[阶段门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L492)、[部属结算伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L779)。

`BOSS` 使王免疫转换友军的 `AllyBuff` 和 `Dread`，并抗性减免 Grim、GrimTrap、Retribution、PsionicBlast；`UNDEAD` 本身不是“免疫所有毒/控制”的总开关。二阶段及以后，Doom 可以附着但没有伤害放大效果，因此不能用腐化王后触发 Doom 的办法放大最终阶段结算。二阶段还拥有 `IMMOVABLE`，强制推拉也不能用来跳阶段。[属性免疫/抗性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1417)、[Doom 特判](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L596)、[阶段固定位置](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L496)。

## 第一阶段：处理生命链接，尽快触发清场

召唤冷却和主动技能冷却初始为零。每次国王行动尝试召唤，成功后加上 8–10 的随机基础冷却；后续自身行动逐次减少。**实际 HP 损失每 8 点还会额外削减两个冷却各 1**，所以持续输出虽然推动阶段，也会加快召唤、传送和链接。不能从“刚刚用过技能”推导固定安全回合。麻痹只阻止该次后面的主动技能和普通动作，并不倒退已经创建的召唤。[行动顺序](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L155)、[受伤缩短冷却](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L492)。

主动技能初次随机各半；用过链接后下次 7/8 倾向传送，用过传送后下次 7/8 倾向链接，失败的链接会尝试传送。这是概率倾向，不是交替保证。

- **生命链接：** 选择离王最远、尚未与王链接的同阵营 Ghoul/Monk/Warlock/Golem，双方各附一个持续 100 时间的 `LifeLink`。一次普通伤害在本体和有效链接对象间按 `ceil(dmg/(链接数+1))` 分摊；链接转发伤害不会再无限递归。只打王会把部分伤害散给部属；打已确认链接的部属也会伤王。食尸鬼等部属基础 DR 比王低，若位置更安全，攻击它可兼顾清场和伤王，但必须看实际链接提示，而非按距离猜谁已链接。
- **传送部属：** 王先尝试向远离敌人的方向挪一格，优先取“敌人→王”延伸线的下一格，否则取更远的空邻格；然后把离王最远的部属传到英雄邻近、尽可能靠近王的一格。它会突然改变近战包围、后撤格和射线，不是单纯逃跑。若没有合适邻格，部属可以留在原处，技能仍有台词。
- **门槛清场：** 王 HP 到 100 或更低时立即钳成 100，传回王座、加 450 盾、取消未完成召唤、杀掉其当前同阵营部属、去掉自身生命链接。过量第一阶段伤害不带入三阶段。用昂贵大招之前对照血条估计阶段门槛；已经能安全推进时不必为了旧杂兵拖长战斗。

来源：[技能选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L174)、[链接选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L338)、[链接伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L841)、[传送算法](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L375)、[转阶段清理](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L496)。

这里的“同阵营部属”有实际区别：已经成功腐化成友军的部属不再被 `getSubjects()` 选中，也不再成为王的技能对象或自动清场对象。是否值得腐化取决于法杖等级、目标当前公开血条和所需充能，不能当作免费转化。[筛选](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L338)、[腐化成功改变阵营](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/AllyBuff.java#L43)、[腐化成功判定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfCorruption.java#L142)。

## 第二阶段：三波精确构成与真正的削盾条件

王的普通伤害免疫只给 `KingDamager` 开例外。`DKBarrior` 每次行动补回 Barrier 自然衰减的 1 点，因此等它自己耗尽不是策略。标准生成/结算路径下是下表；国王使用 `summonsMade` 和当前盾共同控制下一波，不是到固定时间自动推进。[无敌判定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L447)、[盾维持](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L806)、[Barrier 自然衰减](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Barrier.java#L51)。

| 波次 | 启动条件 | 发出召唤的批次 | 总构成 |
| --- | --- | --- | --- |
| 1 | 刚进入阶段2；召唤计数 <6 | 每批两食尸鬼，三批；每批国王 `spend(3*TICK)` | 6 食尸鬼 |
| 2 | 盾 ≤300 且计数 <12 | 先两食尸鬼+武僧，后两食尸鬼+术士；每批 `spend(3*TICK)` | 4 食尸鬼、1 武僧、1 术士 |
| 3 | 盾 ≤150 且计数 <18 | 先术士+武僧+两食尸鬼，王等待3；再两魔像，王等待1 | 2 食尸鬼、1 武僧、1 术士、2 魔像 |

所有上述召唤的 `delay` 都为 3；最后双魔像是“召唤后王等待1”，**不是魔像只需1时间现身**。召唤 Buff 的倒计时独立运行，玩家加速、减速、半回合攻击等都会改变可执行动作数；不得将表机械转换为“走三步一定安全”。完整挑战分支：[DwarfKing.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L202)。

每个二阶段新实例带 `KingDamager`：

- 死亡清理 Buff 时削王盾 25；变为非敌方后，`KingDamager.act` 主动 detach，也削盾 25。**成功腐化可以同时推进盾和获得帮手。** 普通狂乱 `Amok` 没有在这里改为 `ALLY` 的机制，不能仅看到怪物互打就算一份盾已结算。
- `KingDamager.revivePersists=true`，DKGhoul 倒地时保留它。DKGhoul 的 `partnerID=-2` 仅阻止它额外生一只伙伴，**没有禁掉食尸鬼托管复活**。只要另一个同阵营食尸鬼能看见尸体，或距离小于4，它就可能维持尸体；先后倒地增加复活延迟，复活 HP 为最大值的约 10%。优先把一组食尸鬼在复活窗口内彻底处理，或分隔到既没有可见性又足够远；不断击倒同一只不会重复白赚25盾伤。
- 转成友军的食尸鬼不再能为敌对尸体提供同阵营托管；但尸体可能转交另一个仍有效的敌对食尸鬼。确认所有公开尸体/站立同伴，不能把某一只托管者倒地当整组已死。

来源：[KingDamager](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L779)、[DKGhoul 覆写](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L605)、[倒地与保留 Buff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Ghoul.java#L152)、[复活与重新托管](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Ghoul.java#L235)、[寻找托管者](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Ghoul.java#L358)。

### 能否堵出生点利用规则？

基座抽样只排除“已有召唤在途”的基座，不排除站着角色的基座。召唤完成时先检查原格是否有人，有则随机挑一个可走且无人的八邻格；只有原格仍有人且周围没有可用格时，才向阻挡者造成 **20–40 的直接无护甲物理伤害**，并在二阶段结算一份王盾伤害。若阻挡者是羊，会杀羊后继续生成，而不是白白取消本次召唤。[选基座](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CityBossLevel.java#L232)、[占位回退与伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L680)、[伤害种类登记](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1078)。

这说明“站基座就能免打一只”是错误的。九挑战战士尤其不该主动用 HP 换这份盾。用现有友军、地形和控制偶然制造完整包围，在机制上可能少生成一个敌人，但代价、邻格占用和伤害必须当帧核实；本文没有做实际可重复阵型实验，不把它作为主计划。

### 二阶段各部属的优先级

| 部属 | 继承的威胁 | 对九挑战战士的处理原则 |
| --- | --- | --- |
| 食尸鬼 | 16–22 伤害，攻击24，基础额外DR0–4；同伴托管复活 | 分组收尾，避免只打倒不清完；两只都残血时用范围伤害集中完成。 |
| 武僧 | HP70、防御30、12–25伤害、攻击30、攻击时间减半；专注时无限闪避 | 不把昂贵普通物理单击撞到 Focus 上；先用廉价攻击消耗格挡，或核实必中/法术/麻痹手段。持续风筝会额外加快 Focus 冷却恢复，不能以为它一直不格挡。 |
| 术士 | HP70、防御18、暗弹12–18、命中英雄后50%施加30时间 Degrade | 当前有无遮挡魔法弹线的术士优先级通常最高；用已知雕像遮线、靠近迫其近战、控制或快速消灭。Degrade 会压缩升级物品有效等级，尤其会恶化少量升级卷轴集中投入的主武器。 |
| 魔像 | HP120、防御15、25–30伤害、攻击28、DR0–12；拉人；无机且大型 | 波3保留处理两只的远程/控制；别靠薄甲互换，也别以为单个雕像能彻底挡拉人。对毒气/毒药/流血免疫，选其它伤害类型。 |

来源：[Ghoul](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Ghoul.java#L64)、[Monk](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Monk.java#L40)、[Focus](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Monk.java#L81)、[Warlock 弹道与暗弹](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Warlock.java#L77)、[Degrade 数值](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Degrade.java#L30)、[Golem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Golem.java#L42)、[拉人与绕障碍规则](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Golem.java#L147)、[无机免疫](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1425)。

精英强敌不会自动再给这些召唤物附一层随机冠军：正常 `Level.createMob` 才调用 `ChampionEnemy.rollForChampion`；国王直接反射创建 DK 子类再 `GameScene.add`，两处均没有该调用。该结论只针对这条明确的原生召唤路径，不拿它推断其他来源的怪物。[正常冠军入口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L536)、[国王召唤入口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L697)、[GameScene.add](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L1281)。

## 第三阶段：扩大延迟池有价值，不能把它当瞬间击杀

盾归零立即进入阶段3、移除固定位置，王会近战追击。每次自身行动，只要在途 `Summoning` Buff 少于4就尝试发出一个延迟2的召唤，然后走普通 AI；**这个4限制的是在途召唤，不是场上活着的部属总数**。召唤计数设为1，选择种类后成功才递增，因此按当前挑战分支，新阶段前8次成功发出的种类依次为：**食尸鬼、武僧/术士、食尸鬼、食尸鬼、武僧/术士、食尸鬼、食尸鬼、魔像**。旁边“第三次武僧/术士”的代码注释不能替代这个实际运算；第三阶段重置值不同于第一阶段的0。实战没有连续观察到全部发出事件时，不可据此反推隐藏计数，当前公开预告优先。[阶段3行动](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L297)、[种类选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L304)、[阶段3初始化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L519)。

除 `Viscosity.DeferedDamage` 自己的结算伤害外，王接到的所有非负伤害都先加到延迟池并立即返回。物理攻击依然先受命中、格挡和原有 DR 等前置流程影响，不能把武器面板伤害直接当入池伤害。新池首次建立会延后一个 TICK；每次结算：

```text
tick_damage = max(1, floor(remaining_pool * 0.1))
remaining_pool -= tick_damage
```

来源：[王转入延迟池](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L471)、[池累加与首次延迟](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Viscosity.java#L126)、[池逐次结算](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Viscosity.java#L146)。

以下是按该递推独立计算的数学例子，**不是实玩记录或隐藏血量判断**：假设阶段3王恰有100 HP，瞬间放入一笔池、以后不补伤害、没有其它修正，池100需36次扣血才累计100；池150需11次，池200需7次，池300需4次。还需计入首次延迟与调度。结论是：额外打入一些“最终总伤害已经足够”的伤害，仍可通过增大每次扣血显著缩短被围攻时间；但要拿这份收益与多挨一轮、药剂/充能耗费比较。公开血条中的 incoming DOT 是当前可见测量，不是允许读取内部池或保证下一个动作会结束战斗。

战士实施次序：进入三阶段前保留短时生存/控制与一段可靠输出；盾破后抓住王仍近旁且没有危险术士射线的时机建立足够伤害池；一旦继续贴身的代价高于加快结算的收益，切换到遮线、控制和安全位移。不要把全部资源提前花在无敌王身上，也不要因为“池够总血”就长时间自动休息。首领尚未正式死亡之前，随时可能有新召唤、术士射线和魔像位移。

## 九挑战组合下的资源与有用的规则利用

- **输出推进可以换被动恢复预算。** 绝命头目的 `LockedFloor` 初始仅20时间，上限50。王实际接收伤害后按 `dmg/5` 加时间；二阶段的25点部属结算通常补5。延迟伤害最初入池时提前返回，不在当刻补预算，之后池伤结算才走该分支。长期卡波次可能冻结正常回血、法杖被动充能和战士护盾冷却，不能用无限等待准备满状态。[国王补时间](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L474)、[LockedFloor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/LockedFloor.java#L29)、[WarriorShield 冷却门控](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L322)、[法杖充能门控](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L854)。
- **额外召唤可提供战士击杀触发，但没有补给掉落。** 真实近战击杀仍会进入 `Mob.die` 的 Lethal Momentum 天赋检查，它与经验/掉落等级门槛分离；食尸鬼托管倒地提前返回，不等于已经触发死亡收益。对现有天赋使用收益，不能为刷触发故意拖第三阶段。[击杀天赋触发](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L1022)、[食尸鬼提前返回](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Ghoul.java#L152)。
- **生命链接是可反用的伤害分配，腐化是可反用的波次结算。** 二者都应当减少英雄直接承受的攻击，而不是为了展示技巧付出更多充能。全9条件下的禁药、信仰护体、饥饿和黑暗使这类资源效率更重要；具体食物、药剂、符石和法杖用量由全套挑战/物品章节与当前背包共同决定。
- **王冠能力不能预支。** 王死后才掉王冠、开门、清理剩余敌对同类部属，并自动移除英雄的 Degrade；首次下行国王战的主计划不能依赖战后才获得的战士护甲能力。[死亡奖励和清理](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L549)。

## 退出主菜单再读档：本战已有证据与边界

王显式保存并恢复 phase、summonsMade、召唤冷却、能力冷却、上次能力；二阶段恢复固定位置，三阶段恢复首领血条表现。每个未完成召唤保存 delay、pos、summon 类型。延迟池保存 damage；食尸鬼托管保存尸体对象与复活剩余时间；LockedFloor 保存剩余预算。因此源码不支持“回主菜单即可刷新王阶段/召唤/延迟池/恢复预算”的说法。[王状态持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L122)、[召唤持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L758)、[延迟池持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Viscosity.java#L111)、[尸体托管持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Ghoul.java#L340)、[锁层预算持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/LockedFloor.java#L61)。

这只证明上述字段明确保留，不声称整个游戏重载完全无变化。通用 AI 目标、随机状态、Actor 调度、其它 Buff 等由专门重载章节另审；此文没有进行重载对照实验，也没有操作任何个人存档。未经实证的退出技巧不应作为首领保命方案。

## 实战每次决策要读的公开证据

1. 先判断当前阶段：王的无敌/护盾表现、波次台词、狂怒台词及血条；不要读取 phase 私有值。
2. 用当帧召唤提示辨类型：源码提供 `summoning_bones`（食尸鬼）、`summoning_green_flames`（武僧）、`summoning_shadows`（术士）、`summoning_sparks`（魔像）。提示只在实际公开范围内有效，未看到提示不能推导没有在途召唤。[提示来源](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L730)。
3. 对当前最危险术士逐条验证实际魔法弹线，对魔像另检查拉人可能与落点；每次强制位移后重算。
4. 二阶段核食尸鬼站立/倒地、托管和盾变化；第三阶段核王的可见 HP、incoming DOT、活着的部属、下一次继续输出的代价。
5. 私有 cooldown、summonsMade、敌方 AI target、内部真实 HP/延迟池均不是可从源码直接补进当前观察的事实。用这里的规律提出风险范围，按当前公开观测选一个合法操作。

尚未实证的边界：完整占位取消召唤的可重复阵型、腐化在不同现有装备下的充能成本、三阶段真实时间与不同攻击速度的配合、退出重入引起的通用调度差异。本篇的所有战术建议均为源码推导，未声称已完成九挑战国王击杀。
