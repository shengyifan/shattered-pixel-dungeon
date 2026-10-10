# 正常返回主菜单后继续：哪些状态会变，哪些不会

核查基线：游戏 4.0.2 / code 922，CLI.9.0.3，提交 `8405ae82f206e965d14b4a3b9d4fb282f2230e47`。研究日期：2026-10-10。

**结论：正常退出到主菜单再继续确实有少数可改变战斗的序列化缺口；不能把它泛化成重掷、免费回血、免费充能、清空敌人仇恨或取消首领蓄力。** 本文把源码结论、隔离测试和战术推论分开。没有打开游戏，没有读取个人存档或私有审计，没有实际执行九挑战读档战术。

## 1. 本文的“重入”指什么

原生菜单 `WndGame` 的 Main menu 按钮先 `Dungeon.saveAll()`，然后切到 `TitleScene`；继续按钮选中原槽位，进入 `InterlevelScene.Mode.CONTINUE`。`restore()` 先加载英雄和全局进度，再加载当前层，再以英雄原位置 `switchLevel`。这不是恢复到上一回合，更不是强杀进程、复制存档、修改存档或回滚坏结果。

- [WndGame.java:101 Main menu](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndGame.java#L101)
- [WndGameInProgress.java:116 continue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndGameInProgress.java#L116)
- [InterlevelScene.java:750 restore](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/InterlevelScene.java#L750)
- [Dungeon.java:730 saveAll、754 loadGame、860 loadLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L730)

`saveAll()` 调用 `Actor.fixTime()` 统一平移时间原点，并保存各 Actor 的时间；`switchLevel()` 重建调度、恢复敌人引用、重新观察、再次保存，清空英雄的 `curAction/lastAction`。单纯菜单切换没有原生 `hero.spend(...)` 的回合费用，但恢复场景后原本到期的 Actor 仍可能获得调度，不能保证“刚回来必定能先行动”。同一时刻、同一优先级的 Actor 也没有可用作战术保证的稳定排序：集合为 `HashSet`，调度只按时间和优先级比较。

[Actor.java:121 序列化、161 clear、176 fixTime、201 init、325 调度](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Actor.java#L121)，[Dungeon.java:477 switchLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L477)。

## 2. 已做的隔离验证

[ReloadSerializationProbe.java](evidence/reload/ReloadSerializationProbe.java) 用原生 `Bundle.put → gzip write → read → get` 创建全新对象，不是把字段手工拷回原对象。全部实验设置 `Dungeon.challenges=511`。反射只设置/检查明确标记的合成测试对象，不读取真实局面。

运行：`python3 memory/nine-challenges-warrior-4.0.2/evidence/reload/run_probe.py`。

[runner](evidence/reload/run_probe.py) 只读取现有构建 classpath，编译本研究 fixture 到忽略的 `build/research-reload-fixtures/`，不启动游戏或重编产品。使用的既有 runtime 来自本基线的 CLI.9.0.3 构建；[结果](evidence/reload/result.txt) 保存了源码 HEAD、21 个被审查源码的 SHA-256、两个 runtime jar SHA-256 和 **36 条通过的断言**。源码或构建变化后必须重新核对，runner 本身不会保证旧 jar 与新源码一致。

测试范围包括原生对象序列化，以及 `Corruption.act()` 的纯扣血算术；它不验证 UI 可点击的时机、整层装载、动画回调或真实战斗胜率。

## 3. 确实变化的字段与战术价值

| 对象 | 原生保存/恢复结果 | 对战士的含义 | 证据级别与边界 |
|---|---|---|---|
| Ratmogrify 的敌对 `TransmogRat.timeLeft` | 初值 6，随它 `spend(time)` 减少，但 store/restore 只额外保存原怪和 allied；fixture `1.25 → 6.0` | **有实际延长变鼠压制的潜力**：变鼠即将结束但仍存活时正常重入，可把剩余变鼠时间恢复到 6 个该怪的时间单位；无需再次支付变鼠的护甲能量 | 字段重置已测；整局连续重入未测。要先取得 Ratmogrify，正常使用已经花费护甲能量和英雄一回合。不能对首领/小首领用，已经还原的敌人不能靠重入再次变鼠。速度/减速影响怪物自己的 spend，不能直接等同于 6 次英雄操作 |
| 重型回旋镖 `CircleBack.left` | `setup()` 设为 5；保存返航坐标、层、分支、镖本体，却没保存 left；fixture `4 → 0` | **有提前返航的潜力**：回到同层同分支后，下次该 buff 执行时 left 再减 1，立即进入返航逻辑；可更快拿回镖或更早打返航点上的敌人 | 字段重置和坐标保留已测；动画/拾取/回程命中未测。Actor 时间仍保存，不是读档瞬间凭空多一次攻击；返航点仍是原投掷者位置，不会改追当前位置。可能毁掉事先安排的返航命中时机 |
| `Corruption.partialDamage` | float 写入却 `getInt` 读回；fixture `0.75 → 0.0` | **有延长腐化盟友寿命的潜力**：每次重入最多丢掉尚未兑现的不足 1 HP 自损。若怪物 HT < 100，且能在每次扣血 buff tick 之后、余额累积到 1 之前重入，理论上可以反复抹去这部分自损 | 截断已测；纯算术 fixture 的 HT=20、20 ticks：不重载失 4 HP，每 tick 后 roundtrip 失 0 HP。这个循环不是实战 UI 演示。不会回补已经损失的 HP，不清除外来伤害；HT≥100 每 tick 的整数伤害仍发生。必须有腐化手段和可行的安全返回时机 |
| `DM201.threatened` | 默认 false，远程等伤害设 true；类未序列化该字段，fixture `true → false` | **条件性取消尚未执行的腐蚀气体报复**。若受伤后它还没得到行动且英雄已重新可操作，重入可消除这次待报复标志 | 字段重置已测；可靠可用窗口未实玩证实。通常完整一回合攻击会让敌人先还击，菜单无法撤销已经喷出的气体。之后再伤害仍会重新置 true，近身普通攻击也不受此技巧阻止 |
| 巨型螃蟹 `GreatCrab.moving` | 每第 3 次 getCloser 尝试停步，计数未保存；fixture `2 → 0` | **可能对玩家不利**：原本下一次移动会停步，重入后下一次又会正常追上来 | 字段重置已测。不能把反复重入当成让螃蟹更慢的技巧 |
| 普通 `Mob.alerted`、临时 path、recentlyAttackedBy | 不在 Mob/Char 序列化里；alerted true→false 已测。path 重算，近期攻击者列表重建 | 可能改变下一步探路/选敌分支；**不够构成可预测的甩脱技巧** | `state/target/enemySeen/enemyID` 都保存；Swarm Intelligence 的 timeSeenAt 也保存。不要把丢失一个瞬时标志写成全体失忆 |

直接证据：

- [Ratmogrify.java:78 activate 限制、234 timeLeft、256 spend、325 store/restore](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/abilities/Ratmogrify.java#L234)。`allied=true` 后 spend 不再减时，恢复时虽 timeLeft 重设为 6，但已永久结盟的老鼠不会因此重新变回原敌人。
- [HeavyBoomerang.java:84 CircleBack、98 setup、120 act、174 store/restore](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/missiles/HeavyBoomerang.java#L84)。回程仍遵守拾取/落地/命中和耐久规则，不复制物品。
- [Corruption.java:37 partialDamage、61 act、95 store/restore](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Corruption.java#L37)。正常每 tick 累积 `HT/100f`，取整数伤害后保留余数，丢失的是这个余数。
- [DM201.java:52 threatened、55 damage、70 zap、116 Hunting.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM201.java#L52)，[DM200.java:90 ventCooldown 序列化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM200.java#L90)。DM201 继承的普通 ventCooldown 有保存；不要混淆两个字段。
- [GreatCrab.java:60 moving](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/GreatCrab.java#L60)，[Mob.java:170 store、208 restore、267 alerted、591 getCloser、1356 recentlyAttackedBy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java#L170)，[Char.java:340 store](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L340)。

这几种缺口没有九挑战专属的“加载时修复”分支；隔离实验也是 511。**这只表示九挑战不自动堵住字段重置，不表示九挑战环境会给出安全使用窗口。** 若实战采用，只能根据已经公开看到的变鼠/投掷/腐化/敌人动作做决定，不能读上述内部字段、当前敌人 HP 或隐藏调度时间来定时。

## 4. 明确排除的常见猜想

| 猜想 | 当前 4.0.2 的核查结果 |
|---|---|
| 回主菜单回血、补饥饿、清中毒/流血/燃烧 | 不成立。Char 保存 HP/HT/所有 buff；Hunger 保存 level，Poison/Bleeding/Burning 保存各自强度/持续状态，Actor 保存 buff 时间。单纯现实等候不推进原生回合 |
| 免费重置战士盾 | 不成立。`shielding` 来自 ShieldBuff，`cooldown/turnsSinceEnemies/initialShield` 均保存；fixture 四字段保持 |
| 重置狂战士怒气或冷却、斗士连击 | Berserk 保存 state/power/powerLossBuffer/levelRecovery/turnRecovery；Combo 保存连击相关状态。fixture 验证了 Berserk 的怒气和两种恢复字段 |
| 重入会把强化敌人换一种或清除成长 | 不成立。Buff 类型随 Char 保存，Growing.multiplier 也明确保存；fixture 倍率 1.75 保留。`Dungeon.mobsToChampion` 已保存，恢复不是重新 rollForChampion |
| 刷怪计时每次变回满额 | **旧版本传言不能套用本版。** Level 保存 `respawner` 对象，MobSpawner 继承 Actor.time。addRespawner 仅首次创建时设置完整延迟，已有计时保留，若大于当前应有冷却才缩短；fixture 17.5 保持 |
| Goo 蓄力消失，或把强化 Goo 的连续水疗增量归 1 | 不成立。pumpedUp、healInc 都保存，fixture 分别保留 2 和 3 |
| 恶魔之眼蓄力消失 | 不成立。beamCharged、beamTarget、beamCooldown 都保存；fixture true/55/4 保留。几何 beam 可重建，不能把图像重建当成攻击取消 |
| DM-300、天狗计时和炸弹重新开始 | 不成立。DM300 的能力时间/冷却/上次能力、天狗的能力进度/冷却/跳跃，以及 BombAbility.timer 都保存；相关 fixture 保留 |
| Yog 的红色预警图像消失就没有射线 | 不成立。Yog 本体保存 targetedCells、phase、ability/summon cooldown 和拳头队列。观察层的生命周期标记重建不等于原生攻击被撤销 |
| 神器书/饰品选择退出后重抽 | 不成立。UnstableSpellbook.ExploitHandler 保存已抽卷轴，恢复执行原普通卷轴；TrinketCatalyst 保存 rolledTrinkets，场景会重新打开选择窗口 |
| 法杖重入免费恢复充能 | 不成立。Wand 保留 curCharges/partialCharge；Charger.attachTo 对“读档时英雄已花掉部分回合”的情况有主动延迟，防止半回合装载白充能 |

依据：

- [Char.java:332–365](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L332)，[Hunger.java:50](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L50)，[Poison.java:48](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Poison.java#L48)，[Bleeding.java:58](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Bleeding.java#L58)，[Burning.java:75](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Burning.java#L75)。
- [BrokenSeal.java:390](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/BrokenSeal.java#L390)，[Berserk.java:77](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L77)，[Combo.java:149](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Combo.java#L149)。
- [ChampionEnemy.java:94 rollForChampion、294 Growing 保存](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L294)，[Dungeon.java:660、777](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L660)。
- [Level.java:464 respawner 恢复、504 保存、739 addRespawner](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L739)，[MobSpawner.java:34](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/MobSpawner.java#L34)。
- [Goo.java:330](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L330)，[Eye.java:253](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L253)，[DM300.java:133](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L133)，[Tengu.java:364、699](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java#L364)，[YogDzewa.java:605](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L605)。
- [UnstableSpellbook.java:223](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/UnstableSpellbook.java#L223)，[TrinketCatalyst.java:86](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/trinkets/TrinketCatalyst.java#L86)，[GameScene.java:845](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L845)，[Wand.java:815](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L815)。

## 5. 随机数、现实时间与战士的特殊疑点

**随机数：既不保证“重入后完全重复”，也不保证“重入后变好”。** Dungeon 保存种子、道具生成器的牌堆/类别种子和已抽数量，但不保存全局 `Random.generators` 的完整战斗 RNG 状态。同进程回菜单不会把底层 RNG 回滚到旧进度；重新构造一些对象本身会消耗随机数（例如 DM300 的初始 abilityCooldown 随机初始化后又被存档值覆盖），未来随机分支可能变化。真正重启应用则会重新创建默认 RNG。地图、已经抽定的物品和已结算伤害已经保存，不会据此恢复到先前状态。

[Dungeon.java:647 saveGameChecked](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L647)，[Generator.java:900 保存/926 恢复类别 seed/dropped](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L900)，[Random.java:46 默认 RNG 与 53 resetGenerators](../../SPD-classes/src/main/java/com/watabou/utils/Random.java#L46)，[DM300.java:116 构造时随机 cooldown](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java#L116)。源码中的 `Random.exportState()` 是审计快照接口，不是 Dungeon 的加载接口。

**现实时间例外：时空日晷。** 它在计算刷怪倍率时读取本机 `GregorianCalendar` 的小时：08:00–19:59 日间，20:00–07:59 夜间；+3 日间 ×0.8、夜间 ×2。改变的是刷怪冷却公式，而且原有 respawner 冷却不会无条件重置。等待到白天的价值来自现实时间机制，不来自“读档刷新”；无需退出也会在下一次相关计算时读取时钟。这不授权改系统时钟。[DimensionalSundial.java:59](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/trinkets/DimensionalSundial.java#L59)，[Level.java:752 respawnCooldown](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L752)。

**狂战士 `currentShieldBoost()` 的副作用不能直接当成刷新收益。** 方法在 power>1 时确会把 levelRecovery、turnRecovery 乘 `2-power`；但描述中调用它的是 NORMAL 分支，实际进入狂暴时 `startBerserking()` 先设置本次恢复值再调用一次，BERSERK/RECOVERING 描述不走这个调用。恢复本身也没有调用 currentShieldBoost。现有源码证据不能推出“反复看描述或重入可以缩短正在进行的狂暴冷却”。[Berserk.java:187 startBerserking、209 currentShieldBoost、360 desc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Berserk.java#L187)。

返程护符阶段 `switchLevel()` 会再次调用 `AscensionChallenge.onLevelSwitch()`，但新增上升效果以 `depth < highestAscent` 为门槛，同层继续不会反复触发新一层的奖励/清场。[AscensionChallenge.java:251](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/AscensionChallenge.java#L251)。

## 6. 后续实战怎样使用这份知识

最值得在明确隔离实战场景进一步验证的是**变鼠延时、回旋镖提前返航、腐化余数截断**。DM201 只在公开动作能证明“受伤且还未报复、英雄已恢复输入”的特殊窗口才值得考虑，不能依赖查看其私有 threatened。

如果正常实战允许使用原生重入操作，要先收敛当前控制器原请求，确认已保存，再经当前广告的主菜单/继续控件完成；重入后只用全新公开观察的 `s/rev`、句柄、行动列表。保存结果与动作结果分别保留。没有新观测时，不延续旧目标句柄、旧技能状态或旧射线图。

没有任何本页结论允许通过个人存档检查缺失计时器、事先探知敌人的私有状态，或用“返回主菜单”覆盖用户的暂停/不退出政策。尤其不能根据红色警告淡出决定踩回旧射线：它保存了原生蓄力状态时仍可立即结算。
