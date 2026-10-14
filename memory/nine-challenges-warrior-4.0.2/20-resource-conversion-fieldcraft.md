# 20 主动兑现资源：投掷、环境、食物与低成本转换

核对日期：**2026-10-14**；源码基线 `bb0c91fb7c6fa8e33819c554427f81b6cf93f2f9`，游戏 **4.0.2 / code 922**，CLI.9.0.6。
本篇响应用户“积极获取、积极使用神器，适时尝试未知药剂/卷轴，安全时考虑跳楼房，并积极使用法杖和投掷武器”的要求。
只读当前源码与已有项目研究；**现有游戏保持暂停，没有发送游戏请求，没有读取个人存档、profile 或私有 audit**。
以下“源码事实”是机制审查，“执行建议”是条件性战术推导；没有本次实玩或统计胜率验证。当前物资、敌人、冷却与地图必须在获准恢复后重新公开观察。

本篇补充 [06 装备机制](06-weapons-armor-combat.md)、[07 鉴定与炼金](07-identification-consumables-alchemy.md)、
[11 通用遭遇](11-fieldcraft-and-universal-encounters.md)、[15 炼金计划](15-alchemy-planning.md)。
法杖主动使用另见 [21](21-active-wand-play.md)；这里把便宜、可回收、可转换的物资安排到具体遭遇中，不重复物品全表。

## 1. 初始投石从第一层就用于先手、收尾和减一次近战

**源码事实：** 当前战士初始拿的是 **一组投石 ThrowingStone**，不是飞刺；默认一组 3 件。
投石为一阶、基础力量 9、基础伤害 `2..5`，还适用超额力量；不扎在普通敌人身上，成功命中后若未损毁就落地。
普通投掷对非相邻目标的命中倍率为 1.5，相邻为 0.5；未命中通常落地且不扣耐久，成功命中才进入耐久消耗。
投石基础每件 5 次有效使用，**耐久本来就是应当花掉的战斗资源**，不是必须全局保持完整的收藏。

**执行建议：** 见到有安全射线的未接触敌人，就比较“现在投一发，能否减少之后一次近战或直接收尾”。
可用投石接门口伏击、在敌人接近期间补伤害；力量合格且目标未察觉时也可利用投掷伏击。
非相邻窗口优先，敌人贴身后再比较近战/控制，不机械连投。安全顺路回收；回收一件普通物品通常也花 1 时间，
不为保住最后一颗石头额外挨打，不为耐久警告放弃可以避免受伤的击杀。眼前掉血风险大于便宜投武的剩余价值时，允许自然损毁。

证据：[战士初装](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L174)、
[投石特性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/ThrowingStone.java#L27)、
[默认数量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L357)、
[远近命中](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L223)、
[命中落地/耐久](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L411)、
[普通拾取时间](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java#L66)。

## 2. 手里剑把已经可用的瞬发窗口变成额外伤害

**源码事实：** 没有 `ShurikenInstantTracker` 时，本次投掷 `castDelay=0`；之后添加该 tracker，
其显示机制的总时长常量为 **20**，代码实际添加 19 以补偿本次瞬发。冷却在时继续投掷走正常耗时。
这不是旧版“每走一步就能瞬发”；同一英雄上的不同手里剑也检查同一个 tracker。

**执行建议：** 有合格力量、有效目标和安全射线时，把一次瞬发接到普通攻击/法杖/撤退之前，主动利用现成窗口。
它可以降低击杀所需的付费攻击数，不能保证命中或穿过角色阻挡。不要在空地浪费瞬发，也不要为等冷却绕圈 20 回合；
自然推进后公开观察到冷却解除，再重新安排下一次。冷却中仍可按普通投武价值继续用，不把它锁成“只能瞬发才允许投”的物品。

证据：[Shuriken.onThrow / castDelay / tracker](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/Shuriken.java#L48)。

## 3. 投武升级会补齐整组，液金属则把闲置组变成主力组续航

**源码事实：** 普通投武升级直接恢复满耐久与 `defaultQuantity()`，并登记该组的新等级门槛。
因此背包里只剩一件也能补到默认数量；同组仍在地上/敌人身上的旧等级物品，之后拾取会化尘。
不同掉落组不因名称相同就自动获得升级。升级的伤害、力量门槛与耐久共同提高；耐久按 `1.5^level` 成长，
并非每次升级都已经无限耐久。投武的诅咒、附魔保留规则见06，不能把升级当确定解除诅咒。

液金属配方消耗 **3 E 和所选投武组**，要求已知非诅咒，产量取决于实际数量、耐久和等级；组外散件被登记为无效，之后会化尘。
涂用液金属可修复非飞镖投武；耐久已足、数量低于默认数时，还可在材料足够的条件下补回一件。
每件完整耐久的材料尺度为 `5×(tier+1)×1.35^level`，余量和取整决定实际花费，按当前 UI 成品/用量确认。

**执行建议：** 评估升级时至少写出一个收益阈值：提前可用的力量、少一次近战、控制更可靠、补回缺失数量或提高耐久。
**已决定升级且背包有该组剩余件时，不必为旧件回危险区域；升级本身会补齐。** 安全顺路回收可以，但不是升级前置条件。
准备熔成液金属时则相反：安全可收的同组散件会影响产量，先考虑回收再熔。
优先把用不上的已知非诅咒组换成正在频繁使用的高价值投武续航，别在主力破损前一直囤着修理材料。
若修理/补件能支持下一段安全远程输出，就在安全窗口做；不要为省一组投武无限消耗口粮、火把或升级卷。

证据：[升级恢复数量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L147)、
[旧组拾取化尘](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L635)、
[等级门槛判定](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L825)、
[耐久公式](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L454)、
[液金属修复/补件](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/LiquidMetal.java#L127)、
[配方与产量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/LiquidMetal.java#L192)。

## 4. 门、草与转角要兑现一次有价值的伏击，门上物品会改变路线

**源码事实：** 普通怪物的伏击条件包含敌人未察觉、英雄隐形，或英雄不在该敌人当前 FOV 内；
英雄武器力量不足、使用链枷时不能伏击。关闭的门和高草挡视线，开门不挡。
**门格上有物品堆时，角色离开不会把门关上**；所以把投武、废装备丢在门格会破坏原定的关门伏击。
战士踩高草/犁草会踩成普通草，不能套用女猎手的保草规则。

**执行建议：** 战前在已探索一侧选一处真正能遮线的位置，用投掷先削血，再让敌人进入近战击杀窗口。
门不关时先看门格堆物；安全拾取既增加资源也可能恢复遮线能力。重要退路的高草先保留，不为顺手找种子把掩护全部踩掉。
反复绕柱/过门只有在减少实际受击、完成击杀或真正脱离追击时才值得；每次额外移动都计入饥饿、照明、增援风险。
失去视线不等于敌人立刻忘记旧位置，不把“又绕了一圈”算成已脱困。

证据：[伏击](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L907)、
[力量/链枷限制](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L744)、
[门草视线](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Terrain.java#L89)、
[门上物品阻止关门](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Door.java#L45)、
[战士踩草](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/HighGrass.java#L55)、
[饥饿逐tick代价](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L64)。

## 5. 投物压陷阱：同时决定落点、攻击目标、损毁和回收

**源码事实：** 普通物品走 `PROJECTILE`，会被途中角色/阻挡物截停。正常落地调用 `Level.drop→pressCell`，
是能触发暗陷阱的 hard press；投武打中角色后可能扎入目标，未必像普通落地物品一样压中你原先指定的格。
陷阱通常触发后失效，但子类可重复触发。把物品丢进去不代表效果已散去、路线已安全、物品仍可收回。

**执行建议：** 在安全距离，用愿意损失的低价值物品远程压已知陷阱；先核真实射线，再查具体陷阱范围。
烈焰陷阱按非实心寻路距离2铺火，寒气陷阱影响本格及周围非实心格，不能只站离落点一格就算安全。
解离会爆炸落点物品堆；火会烧纸、冰会碎药，传送/塌落等还有自己的处置，珍贵资源不作通用压板。

若要借陷阱杀敌，另做一次**以陷阱为起点**的选敌检查：解离/死神先取陷阱格角色，否则选可直射的近角色，
同距时英雄会被其他候选替换，但看不见的候选、隐形距离规则、抗性仍可改变结果。
不把这种顺序当“投出去必打我选的怪”。让普通非飞行敌人踩已显露陷阱也是方案；怪走过未显露暗陷阱不证明该格安全。

证据：[普通投射碰撞](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java#L633)、
[落地按压](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1004)、
[hard/soft press](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1226)、
[投武命中与落地分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/MissileWeapon.java#L270)、
[陷阱失效规则](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/Trap.java#L94)、
[烈焰范围](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/BlazingTrap.java#L47)、
[寒气范围](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/ChillingTrap.java#L44)、
[解离选敌及堆物爆炸](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/DisintegrationTrap.java#L53)、
[死神选敌](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GrimTrap.java#L65)。

## 6. 战士“即兴投掷”把非投武物品变成短致盲

**源码事实：** 已点 `IMPROVISED_PROJECTILES`、自身50时间冷却不存在时，投掷**非 MissileWeapon**物品到异阵营角色，
普通投物回调会给予 `1+天赋点数` 时间致盲，并给英雄50时间冷却；它不要求先通过投武的 `shoot` 命中骰。
这仍要求真实碰撞落点就是目标，物品自身效果也照常执行。天赋属于战士第二阶，没点就没有这个效果。

**执行建议：** 中期规划天赋后，把可承受损失的普通物品留作已知工具，主动用于接近远程敌人、遮断普通视线或退出接触。
先投再立即利用短窗口；不要致盲后原地继续做无关动作。若用药瓶、符石、炸弹，必须同时承担它们本身的效果；
普通废装备可避免“未知瓶子+致盲”叠出不可控结果。致盲不是沉默、麻痹或取消所有已蓄力攻击，具体敌人仍按源码检查。

证据：[实际触发与冷却](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java#L649)、
[战士二阶天赋列表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L1009)。

## 7. 肉要积极吃，但先用低成本加工消除生肉风险

**源码事实：** 生、烤、冻、炖肉基础营养均150，节食后通常仅 **50**；诅咒丰饶之角还会再降低， 加工不增加饱腹。
生肉五等概率分支中四个是燃烧、扎根、中毒、迟缓，只有一个无额外负面。
冻肉五等概率分支为隐身、树肤、清治疗药可清的负面、直接恢复 `floor(HT/4)` HP、无额外效果；
直接回血不走禁药挑战的治疗药副作用，但只有20%概率，不能作为必得救命回血。
炼金炖肉1/2/3块分别花 **1/2/2 E**。

**执行建议：** 顺路有安全火/冰来源时，把肉单独成堆再加工；没有安全来源且已在锅边，就用便宜炖肉，
不要为了追求冻肉随机回血绕远或自造冰区堵唯一退路。已有冻肉优先保留其随机额外收益，别再烧成烤肉。
同堆火会烧非unique卷轴、蒸发露珠、点爆炸弹；同堆冰会碎非unique药瓶并触发瓶效，封闭箱也不等同普通堆物。
加工后按当前饥饿和丰盛一餐条件及时进食，进食时间仍要留出；“可以出回血”不等于在追兵脸上吃是安全的。

证据：[生肉分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/MysteryMeat.java#L39)、
[节食除三](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L114)、
[冻肉效果](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/FrozenCarpaccio.java#L39)、
[炖肉配方](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/StewedMeat.java#L40)、
[同堆燃烧](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L217)、
[同堆冻结](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L323)。

## 8. 荒芜的种子优先变成眼前能用的药镖或确定配方

**源码事实：** 荒芜阻止正常种植，但不阻止种子涂镖。普通飞镖在背包即可涂药，
**一粒种子最多涂两枚镖、花1时间，不需炼金台**；涂层耗尽通常留下普通镖供回收再涂。
三粒种子炼一药为0 E；三同种通常对应固定药并鉴定，治疗配方存在限量重抽例外。
普通种植在荒芜下可能只是花时间把种子丢在脚下，不能用来种草回血、冻肉或净化。

**执行建议：** 已有两枚普通镖和种子、下一段缺控制，就提前涂制，主动把冰冠、致盲草、地缚根等转换为战斗窗口。
不要因“以后可能凑三粒”让当前可用控制一直闲置；已有明确三同种目标则保留对应材料。
水中目标能延长寒霜镖的寒冷；净化镖能去掉部分敌方正面buff，但**明确不能移除 ChampionEnemy 精英词缀**。
种子→药镖→普通镖回收再涂是持续资源循环，前提仍是当前命中、免疫、回收路线合格。

证据：[背包涂镖操作与时间](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/Dart.java#L266)、
[涂层/普通镖恢复](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/TippedDart.java#L128)、
[荒芜种子投落](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Plant.java#L153)、
[三种子配方](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L468)、
[寒霜镖水域效果](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/ChillingDart.java#L37)、
[净化镖排除精英词缀](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/darts/CleansingDart.java#L52)。

## 9. 传送卷拆两颗闪现石是0 E，提前换成已知落点的脱离

**源码事实：** 一张传送卷→两颗闪现符石，配方 **0 E**。闪现投射仍走 `PROJECTILE`；
末端有角色时退到路径前一格。传送函数还验证地形、占位与连通，实际落点执行 `occupyCell`，因此陷阱/深渊仍可能产生后果。
它不是任意穿墙或无条件到达鼠标/CLI选格，也不能用来把敌人传走。

**执行建议：** 如果本段缺的是短程定点拉开距离，而且已知安全落点存在，主动把一张卷转成两次可计划的位移。
在被完全围住前使用，落点先验可达、无角色、无危险且有下一步路线；不要等只剩一条被角色挡住的射线才想起合成。
若当前确实需要原卷的远距离脱离/探索能力，保留原卷；转换依据是具体需求，不能把所有传送卷都一律拆掉。

证据：[0 E配方/两颗产量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L308)、
[闪现碰撞和退格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfBlink.java#L38)、
[落点合法性与占格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L77)。

## 10. 全面净化把一瓶原版净化转换为一次饱腹重置窗口

**源码事实：** 原版净化药剂 Purity 变全面净化合剂 Cleansing 花 **4 E**。
全面净化会调用 `Hunger.satisfy(Hunger.STARVING)`，通常最多消除450饥饿值，不经 Food 的节食除三；
另去掉适用负面buff并给予5时间 Cleanse。它不是直接治疗，也不触发丰盛一餐。

**执行建议：** 原版净化已知、原版用途不是本段必需、饥饿接近封顶且还有可推进路线时，安排在最近安全锅边转换。
这能把资源用来支持后续探索、战斗和自然恢复，而不是长时间等待恢复又耗光新营养。
如果它仍是过有害环境的唯一原版净化，先保留解谜/穿越用途。低成本可用包可以是“4 E全面净化+0 E两闪现”，
不是必须攒很多能量才有资格获得饱腹与脱离手段。

证据：[4 E转换](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/ExoticPotion.java#L125)、
[净化与饥饿](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L75)、
[饥饿界限](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L40)。

## 11. 水爆一料八瓶，可把造水、拆部分陷阱、推离和寒霜镖串起来

**源码事实：** 一瓶暴风骤雨+8 E→8瓶水爆；若从浮空开始，前置变异再花4 E。
水爆使用喷泉陷阱逻辑：内部可通达格尝试造水，距离2的外围每格只有2/3概率；中心及周围角色可能被推2格，包含英雄。
普通地面陷阱/暗陷阱可被改水移除，但特殊地形可拒绝变化、实心阻隔与范围概率会限制结果。
中心角色清燃烧还有分支条件，不能泛称投任意水爆必定给自己灭火。

**执行建议：** 需求已有时一次制成数瓶，把低单次材料成本用在眼前的障碍、陷阱和脱离窗口，
也可造水后接寒霜镖；不要只为“以后总有用”把唯一浮空解谜瓶提前花掉。
投前同时算英雄与敌人的受推格、深渊、墙、植物和退路；投后公开确认陷阱确已消失、目标确在水中，再执行下一步。
不能把不确定的外围造水结果当作预先保证的路线。

证据：[水爆配方](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/AquaBrew.java#L63)、
[造水范围/概率](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GeyserTrap.java#L59)、
[受推与中心分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GeyserTrap.java#L107)、
[地形变水/移除陷阱](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1118)。

## 12. 把本来要打的敌人和已有环境，转成奖励房进度

**源码事实与联动：** 普通食人鱼只沿水域追击，离水或飞行会死；它们没有经验，主要收益是肉与房间奖品。
已有位移、浮空效果或安全干地射线时，先检查能否低成本完成水池房；幻影鱼离水会尝试传走，不能照搬普通鱼推上岸结论。
捕鱼矛会把对鱼伤害提高到至少其**当前HP的一半**，仍有命中/结算边界，不是保证两击必杀。

献祭火会给周围角色标记，角色死亡时调用献祭；同时要求死亡位置仍邻近有效火格。
可把本来就必须处理的合格追兵引到祭坛侧再击杀，用同一场战斗推进奖励，
而不是清光全层以后再绕远招怪。祭坛生成的奖励武器带已知诅咒，获取计划也要包含祛邪或可接受的用途。

**执行建议：** 每遇奖励房先列“已有资源中，哪一件能省去正面换血”：投掷先削血、法杖位移、敌人踩公开陷阱、
附近普通鱼/追兵贡献等。计划同时写出材料、往返步数、可收奖励与退出办法；有有效低成本解法就积极执行。
如果必须额外绕层、无限等刷新、牺牲唯一退路或吃无法承担的伤害，先记录待取条件，并随新掉落重评。
跳楼房的坠落与流血预算见 [19](19-pitfalls-and-room-payoffs.md)，完整特殊房见 [13](13-special-rooms.md)。

证据：[普通鱼离水/飞行死亡](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Piranha.java#L69)、
[水域寻路](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Piranha.java#L132)、
[幻影鱼离水例外](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/PhantomPiranha.java#L80)、
[捕鱼矛当前HP规则](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/FishingSpear.java#L39)、
[献祭标记](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/SacrificialFire.java#L82)、
[献祭位置与贡献](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/SacrificialFire.java#L153)、
[死亡结算](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/SacrificialFire.java#L221)、
[献祭奖品诅咒](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SacrificeRoom.java#L84)。

## 恢复游玩后的执行卡

用户进一步要求**充分利用背包里的各种资源**。每件物资都应有清楚的去向：现在用、为一个具体触发条件保留、
转化为当前缺口，或安全出售；“留着以后用”若没有条件和目标，就在本层规划时重新评估。

**取物前先确认实际用途。** 例如英文名 `tome of dungeon mastery` 对应 `Guidebook`，拾取是在日志中获得七页教学，
不是战士转职物、神器或战斗强化；名称里的 `mastery` 不能单独证明用途。入口附近顺手获取信息也有价值，
但拾取有正常时间成本。已走远后是否回头取，应比较信息收益与往返粮光，不能只为弥补“之前没拿”的姿态走回头路。
这个例子只记录识别原则，不确认暂停游戏入口处某件物品的当前身份或位置。
证据：[英文名](../../core/src/main/assets/messages/items/items.properties#L683)、
[Guidebook七页与拾取时间](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/journal/Guidebook.java#L45)。

下面是组合执行的例子，必须先满足各件物资的当前公开条件：

| 背包组合 | 眼前可以完成什么 | 必须一起核实的边界 |
| --- | --- | --- |
| 投石/飞刀 + 关闭的门或高草 + 普通近战 | 远程先手削血，遮线接伏击，再近战收尾；战后顺路收投武和掉落 | 投武力量、真实射线、门格是否被堆物撑开；不能为第二次伏击无限绕圈 |
| 地缚根种子 + 两枚普通镖 + 炸弹 | 先在安全处涂制麻痹镖，把原本闲置种子变成控制；目标仍能留在范围且自己能退开时，再使用点燃投掷炸弹 | 普通炸弹是延迟引信，非即投即爆；麻痹可被伤害打断，不能默认持续到爆炸；爆炸伤及所有范围角色并毁堆物 |
| 恐惧符石 + 已知退出路线或闪现符石 | 让一只可受恐惧的挡路目标转入逃离，再走完脱离或取物的关键几步 | 恐惧符石影响碰撞格角色，实际免疫/范围以当前效果为准；继续追打会浪费所买窗口，落点不能封住自己 |
| 传送卷 + 10 E → 六个相位转移法术 | 当实际需求是移走挡路敌人而非移动英雄时，主动制造数次隔离手段，之后收取目标物资或穿过出口 | 必须碰到角色且传送成功；追猎怪改游荡，不保证永不回来；BOSS/MINIBOSS不获后续麻痹，不能套用普通怪结论 |
| 多余普通装备 + 已点即兴投掷 + 后续安全商店 | 先作为短致盲工具帮忙接近/脱离，安全可回收再卖；或明确保留一件以备下一次冷却结束 | 丢在门格可能撑门，压危险陷阱可能损毁；不得把已承诺升级、祛邪或任务的材料随手当废物 |
| 暂时用不上的投武组/种子 + 炼金台 + 主力投武/低饥饿 | 先比较液金属、药镖、确定药配方和能量的具体收益，补当前的输出、控制或饱腹缺口 | 先保护唯一解谜瓶、最后脱离手段与明确任务材料；材料有更好即时用途时，不只按售价或能量数决定 |

组合补充证据：[炸弹点燃投掷/延迟引信](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bombs/Bomb.java#L104)、
[爆炸范围/损毁/全角色伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bombs/Bomb.java#L138)、
[恐惧石目标](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfFear.java#L42)、
[相位转移效果与配方](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/PhaseShift.java#L47)。

每层把“已有远程伤害/控制、可回收投武、神器充能、可试用未知物、可转换材料”列在计划中，
给它们安排本层的优先用途。对每次遭遇依次比较：**远程先手能否省受击 → 控制/位移能否创造输出或退出 → 近战收尾 → 安全回收**。
不是所有资源都只保留给濒死一刻，也不是每次都耗最珍贵物品；优先花能明显减少本次总成本的现有工具。

一个可复用的记录句式是：

> 当前可用资源___；本次主动使用___，预计换来___；用完后的退路/剩余底牌___；
> 若当前公开结果出现___就停止追加投入，转向___。回收或等充能的额外步数___，是否值得___。

尚未验证：手里剑瞬发与临界冷却的完整实机调度、各陷阱/物品组合的全场景表现、上述资源包的通关胜率。
本文不从历史坐标、私有敌人位置或配方内部随机种子推断本局事实；机制预测只指导当前公开操作的选择。
