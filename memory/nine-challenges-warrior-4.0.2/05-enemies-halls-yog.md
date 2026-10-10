# 九挑战战士：恶魔大厅与 Yog-Dzewa

核对日期：2026-10-10；源码基线：`8405ae82f`，上游游戏 4.0.2 / code 922。这里是**静态源码规则与据此推导的战术**，没有运行游戏，没有读取个人存档，没有证明任何战术已完成九挑战实战。表中数值是原生基础值；冠军词缀、特殊增益、上行挑战及战士自身装备会改变实际结果。只能从当前公开观察识别当前敌人、状态与可执行动作，不能把源码中的私有字段变成实时情报。

已读项目 `memory/README.md`、游玩 skill 与 2026-10-08 复盘。旧局“用魔皇草清盲”“喝治疗药补回”等做法不能直接搬到九挑战；荒芜/恐药会改变资源可用性。本文中的源码链接以本文件所在目录为基准。

## 1. 大厅路线与全部原生敌人

常规生成轮转是：21 层魅魔:恶魔之眼 = 2:1；22 层 = 1:1；23 层魅魔:恶魔之眼:蝎子 = 1:2:1；24 层 = 1:2:3。这是抽取轮转构成，不是每层固定数量。每层另有一个恶魔血巢房；撕裂魔由血巢生成。蝎子有酸液蝎变种，基础替换概率为每个轮转位置 `1/50 × RatSkull.exoticChanceMultiplier()`；魅魔和恶魔之眼没有该表中的原生稀有替换。19 层也有小概率把魅魔加入轮转。来源：[MobSpawner.standardMobRotation/addRareMobs/swapMobAlts](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/MobSpawner.java#L187)、[HallsLevel.initRooms](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/HallsLevel.java#L88)。

| 敌人 | 基础 HP；命中/闪避技能；物理伤害；本类额外 DR | 决策要点 |
| --- | --- | --- |
| 魅魔 `Succubus` | 80；40/25；25–30；0–10 | 远距可闪现近身，近战概率魅惑；攻击已魅惑目标能回血/转护盾。 |
| 恶魔之眼 `Eye` | 100；30/20；近战 20–30；0–10 | 两倍攻击延迟蓄力，蓄力期承伤除以 4；射线 30–50 魔法伤害且穿角色。 |
| 蝎子 `Scorpio` | 110；36/24；30–40；0–16 | 不能攻击相邻目标，猎敌时后退；攻击 50% 残废。 |
| 酸液蝎 `Acidic` | 继承蝎子 | 远程命中或遭相邻攻击可给目标腐蚀黏液；必定经验药水掉落。 |
| 撕裂魔 `RipperDemon` | 60；30/22；15–25；0–4 | 移动基础速度 1，近战延迟 0.5；远处预判跳跃造成流血。 |
| 恶魔血巢 `DemonSpawner` | 120；闪避 0；不主动攻击；0–12 | 静态不可移动；持续生成撕裂魔，承伤压缩，受伤又加快召唤。 |

范围中的“0–10”等是各类 `drRoll()` 在 `super.drRoll()` 上额外加的正态随机范围；不能把它当当前固定护甲，更不能据此推断公开血条以外的实时精确 HP。来源：[Succubus](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Succubus.java#L55)、[Eye](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L50)、[Scorpio](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Scorpio.java#L41)、[RipperDemon](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/RipperDemon.java#L48)、[DemonSpawner](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DemonSpawner.java#L43)。

黑暗挑战将楼层基础视距限制到 2；这些主要大厅怪物自己的视距直接设为 `Light.DISTANCE=6`。光照给英雄至少 6 视距、基础持续 250 回合；大厅普通层额外投放两支火把。**省火把不能省到让自己在 2 格视距中接连续远射**：短视距没有同步削弱敌人。进入未知房间前先点亮；在已清空走廊则按余时、饥饿、可见敌人与路线预算。来源：[Level.viewDistance](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L158)、[Light](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Light.java#L35)、[HallsLevel.create](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/HallsLevel.java#L118)。

## 2. 单敌处理

### 魅魔：允许它靠近，但不要站着挨魅惑回血

- **闪现规则。** 猎近目标可见、距离大于 2、冷却到期且未扎根时，沿 `PROJECTILE` 取碰撞终点；终点已有角色便回退一格，并另行处理危险地形/大型体型。成功后冷却为随机 4–6，冷却主要在后续 `getCloser` 分支扣减，不应简单承诺“4 回合后必闪”。成功闪现有负耗时抵消普通接近消耗，不能把闪现看成给英雄白送一个安全回合。来源：[Succubus.getCloser/blink](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Succubus.java#L110)。
- **魅惑规则。** 普通命中约 1/3 施加 `Charm.DURATION/2=5` 的魅惑并指定魅魔为来源。对已带魅惑的目标攻击时，按 `5 + damage` 恢复自身损血，溢出转 Barrier；不要用“反正不能普攻，原地等魅惑结束”当默认战术。后续受伤会缩短魅惑，但刚施加它的那次命中有 `ignoreNextHit`。来源：[Succubus.attackProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Succubus.java#L76)、[Charm.recover](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Charm.java#L68)。
- **战士决策。** 已有安全近战空间时，让闪现省掉自己追击步数；先处理旁边恶魔眼/蝎子射线。受魅惑后优先位移、断线、清负面，或使用当前仍原生允许的法杖/非普攻手段；英雄直接攻击来源会被拒绝。魅魔免疫魅惑，别浪费同类控制。来源：[Hero.actAttack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1462)、[Succubus.immunities](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Succubus.java#L182)。
- **可利用掉落。** 基础 33% 掉随机卷轴，明确排除鉴定和升级。其掉落来源可以缩小未知卷轴候选集；不能因此唯一识别，不能刷它解决禁咒的升级短缺。来源：[Succubus.createLoot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Succubus.java#L172)。

### 恶魔之眼：躲整条真实射线，蓄力期不要低效硬打

- 射击瞄准使用 `STOP_SOLID`，不是普通投射物；无 `STOP_CHARS`，会贯穿沿途角色，命中各角色时各作魔法命中检定。实际魔法伤害 30–50，不经过普通物理护甲骰；它飞行，不能指望深渊即死。蓄力耗 `attackDelay×2`，发射耗一次攻击延迟，射后冷却随机 4–6。来源：[Eye.canAttack/doAttack/deathGaze](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L90)。
- 蓄力中 `damage` 先作整数 `dmg/=4`，强攻常是资源陷阱。它的专用 `Hunting.act` 会在充能后即使目标离开视线仍执行既定攻击；失去目标视线不等于取消。若发射前重新可见目标并满足条件，`canAttack` 又会建立瞄准，所以不要把“一步移开旧线但依然暴露”当跨所有局面的必中规避。贴到实墙/转角后的完整遮挡位置最稳定，等待其射击后再打。来源：[Eye.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L152)、[Eye.Hunting.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L275)。
- 射线会破坏可燃地形；高草、木门的旧遮挡不能在射后继续沿用。可借敌眼射线击伤其他敌人，但英雄必须先证实自己不在整条射线上；先知全图无法代替当前公开可见角色及障碍。它抗解离法杖、其他恶魔眼 DeathGaze 与解离陷阱，均来自明确抗性表。来源：[Eye.deathGaze/resistances](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L167)。
- 九挑战不能把眼怪露珠掉落当补血计划：荒芜会直接屏蔽 `Dewdrop` 的投放；种子/符石掉落仍需按各自规则使用。来源：[Eye.createLoot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L221)、[Challenges.isItemBlocked](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Challenges.java#L71)。

### 蝎子与酸液蝎：破坏射击条件，不在开放地形慢追

- `canAttack` 第一条就是不相邻；远程使用 `PROJECTILE`，会受首个角色/地形阻挡。相邻且无法后退时才是可利用的无攻击窗口；它在 `HUNTING` 的 `getCloser` 实际调用 `getFurther`。当前相邻只证明当前不能远射，退开一步或被推开后会立刻改变。来源：[Scorpio.canAttack/getCloser](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Scorpio.java#L72)。
- 每次成功攻击约 50% 施加通常 10 回合残废；开放区域追击容易变成“追一步—被后退射击—残废更追不上”。优先转角让它断视线，利用其追踪/走位重新接敌；再用已验证的定身、减速、拉近/推位、跳跃能力或射线武器建立优势。槊/长鞭等长触及不等于已经贴身，仍可能吃射击。来源：[Scorpio.attackProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Scorpio.java#L78)、[Cripple.DURATION](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Cripple.java#L26)。
- 酸液蝎在攻击时加 Ooze；遭相邻攻击也在 `defenseProc` 给攻击者 Ooze。用远程/延长近战距离可以避免“相邻反溅”，但仍要防它自己远射。ACIDIC 抗腐蚀、免黏液；不适合用腐蚀药作为最高效单体解。经验药是其固定掉落，普通蝎子基础 50% 随机药水却排除治疗、力量；这些来源约束可用于鉴别。来源：[Acidic](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Acidic.java#L32)、[Scorpio.createLoot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Scorpio.java#L107)、[Char.Property.ACIDIC](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1431)。
- Ooze 在 21–24 层通常每 tick 5 伤、基础 20 回合；25 层通常 6 伤。站水中且非飞行会洗去，但源码特意保留刚附加后的第一次作用：不要保证“站水里被上黏液绝对零伤”。必须确认实际脱落，悬浮中水不会清洗。来源：[Ooze.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Ooze.java#L90)。

### 撕裂魔与血巢：消除长期生成源，利用跳点而不是直线跑

- 撕裂魔相邻优先半回合近战，远距至少 3 格、可见、未扎根且跳冷却到期才准备跳。若英雄刚移动，按前一位置推算下一步方向，目标往当前格的前方偏移一格；不合法时再尝试当前格。准备延迟被夹在自身一次至三次攻击延迟之间，并考虑英雄动作剩余时间。看到跳点后应离开**预警落格**或向另一方向变向，不要机械重复上一步。来源：[RipperDemon.Hunting.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/RipperDemon.java#L234)。
- 起跳前重算 `STOP_TARGET|STOP_SOLID`，途经角色不拦跳；落点有异阵营角色时用无限精准的物理命中调用，附 `Bleeding.set(0.75×damageRoll)`，不是普通护甲抵扣的直接近战伤害。落点已有角色会找旁格弹开，根缚会取消已准备的跳。`Bleeding.set` 会再随机缩减初始强度、每 tick 随机衰减；不要把图标数字当总伤害。来源：[RipperDemon.Hunting.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/RipperDemon.java#L152)、[Bleeding.set/act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Bleeding.java#L71)。
- 击杀普通撕裂魔不提供常规练级回报：`maxLvl=-2`，`EXP=9` 注明用于腐化结算；不要把血巢留作战士经验农场。对九挑战战士，半回合贴身压力和出血都很贵，先清单只撕裂魔再打巢，留一条能变向躲跳的出口。来源：[RipperDemon.initializer](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/RipperDemon.java#L48)。
- 血巢周期约 21/22/23/24 层 60/53.33/46.66/40 回合，首次冷却初值 0；生成需要相邻可走空位。受伤 ≥20 会压缩：20/22/25/29/34/40/47/55/64/74/85 输入，约变成 20/21/22/23/24/25/26/27/28/29/30；转换后的 `dmg` 又从生成冷却中扣除。因此爆发虽然能迅速结束战斗，却不是原伤害等额击杀，且会引发额外小怪。小额可靠伤害/连续攻击可减少过量压缩，但绝不能为刷出数量而无意义拖延。来源：[DemonSpawner.act/damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DemonSpawner.java#L78)。
- 四巢全清会显著减轻强敌 Yog 的召唤池，详见下节。巢固定掉治疗药不等于九挑战可喝回血；其价值应依恐药的炼金/投掷用途处理。血巢房入口原生不可随机暗门、房内不生成陷阱/水/草，可在已公开确认房间后作为布置战斗路线的条件；不要未观察就断言具体格无陷阱。来源：[DemonSpawner.loot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DemonSpawner.java#L54)、[DemonSpawnerRoom.paint/canPlaceTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/DemonSpawnerRoom.java#L40)。

## 3. 强敌 Yog：六拳、阶段、召唤与视距

Yog 基础 HP 1000，固定不动，普通战斗不进行近战普攻。开始后在 700、400、100 HP 处各召一轮拳头；**九挑战含强敌，每轮两只，共六只全部遇到**。拳头存在时 Yog 无敌，前几阶段还有本体血量门槛，无法一击越过整场。最后一轮两拳都死亡才进入末尾 100 HP 的高频阶段。来源：[YogDzewa.damage/processFistDeath/isInvulnerable](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L365)。

六拳分三族：火/草、腐/锈、光/暗。每轮双拳来自不同族；源码打乱主拳后把对应另一半错位，所以**火+草、腐+锈、光+暗这三种组合不会同轮出现**。不能预先知道本局顺序，也不能为了“等火拳替我烧草拳”设计不可能的同轮方案。来源：[YogDzewa.getPairedFist / initializer](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L97)。

强敌召唤池固定六项循环，顺序先打乱：最后两项永远是 YogRipper；前四项中，每个存活血巢把一个 Larva 替换为交替的 YogEye/YogScorpio。因此全清四巢时仍是 **4 幼虫 + 2 撕裂魔**，而不是纯幼虫；全留则是 **2 恶魔眼 + 2 蝎子 + 2 撕裂魔**。Yog 变体设 `maxLvl=-2`、加 BOSS_MINION；幼虫 20 HP、15–25 伤、命中 30、闪避 12、额外 DR 0–4。留四巢可争取专门首领挑战徽章/更高分，但“九挑战通关”本身并不要求这样做；为胜率应清巢。来源：[YogDzewa.regularSummons](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L129)、[die](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L529)、[Larva/Yog variants](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L647)。

普通召唤/射线基础冷却随机 10–15，再按阶段减；伤害 Yog 本体又每实际 10 伤加速冷却约 1。拳头在场时召唤间隔另加 `10-(phase-1)`；最后 100 HP 射线剩余 CD 被压到不超过 2，召唤不超过 3，并触发一波突发召唤。该阶段更适合留一组高可靠爆发尽快结束，而不是继续处理每只幼虫。Yog 死亡会清掉自己的这些召唤物。来源：[YogDzewa.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L303)、[damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L412)。

九挑战无光照时，初始/前两轮仍最多 2 格，最后一轮随阶段压到 1 格；Light 存在时 `updateVisibility` 不覆盖英雄的光照视距。暗拳会削弱/移除 Light，光拳会致盲；库存火把不是可省略的“探索便利品”。来源：[YogDzewa.updateVisibility](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L480)。

## 4. Yog 射线与三种弹道不能混淆

| 攻击 | 真正的碰撞/目标规则 | 战术含义 |
| --- | --- | --- |
| 恶魔眼 DeathGaze | `STOP_SOLID`，遍历至碰撞格，逐格攻击 | 角色不挡光束；真正实墙挡，且可燃物会被毁。 |
| 六拳远程资格，含光/暗拳 | `MAGIC_BOLT` 的首个碰撞必须恰是敌人 | 角色与实物可遮挡，不是 Yog 的穿墙射线；光/暗的视觉光束不可反推为贯穿攻击。 |
| Yog 本体 DeathRay | `WONT_STOP`，遍历整条 `path` | 穿墙、穿所有角色到地图边缘；普通 ray 工具的 solidbeam 模型不适用。 |

来源：[Eye.deathGaze](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L167)、[YogFist.canAttack](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L103)、[YogDzewa.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L194)、[Ballistica flags/build](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/mechanics/Ballistica.java#L41)。

- Yog 不靠 `enemySeen` 才能发动射线，直接用英雄当前格及附近格选线；隐身、转墙不能代替离线。射线数为 `1+(1000-HP)/400` 整数除法，即最多三条；附加瞄点选英雄相邻且不比英雄更靠近 Yog 的格。若所有相邻可走格都被选线覆盖，会删去最后一条，但这不是对拥堵、陷阱、敌占位、减速的安全保证。来源：[YogDzewa.act aiming](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L251)。
- 发射预告耗 1–3 的夹紧延迟，按英雄已有动作的剩余时间给机会；实际命中依然用本体无限命中技能的魔法检定，强敌为 30–50 伤。多条线用 `HashSet<Char>` 去重，同一轮重叠不会对同一角色逐条叠加，但一次已足够致命。来源：[YogDzewa.attackSkill](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L156)、[act firing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L194)。
- **扎根只延迟发射，不取消。** `targetedCells` 非空但英雄 rooted 时跳过发射段。草拳扎根与本体射线联动时，不能看到提示淡去便认为安全；解绑后的下一次本体行动仍可能射。先确认已知线和解绑后可移动落点。代码不是允许把根缚当永久免伤。来源同上。
- 场景粒子/预警图像淡出不是攻击状态。协议中公开的绑定警告按真实生命周期；无可靠来源的恢复标记是 unknown，不证明待发或已取消。实战需要保留已看到的全部射线路径，直到确实发射/取消；不能根据墙遮挡、窗口大小或红格消失自行删除危险。

## 5. 六拳共通规则

每拳基础 300 HP、命中 36、闪避 20、物理 18–36、额外 DR 0–15；锈拳物理伤害为 22–44。普通拳远程后增加随机 8–12 冷却，未麻痹时每 act 扣 1；火/草/腐/锈即使贴身仍可在冷却到期施法。光/暗没有远程冷却，但设 `canRangedInMelee=false`，相邻就回普通近战。所有拳免 Sleep，且具有 BOSS 通用抗性与免疫：抗即死/报复/灵爆等类别，免 AllyBuff 与 Dread；不能把恐惧惊骇、腐化收服或灵爆当无条件首领解。来源：[YogFist.base](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L65)、[Char.Property.BOSS](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1418)。

**先拉离保护区再花主要输出。** 保护区是拳头到 Yog 固定中心的棋盘距离 `<=4`，不是英雄到拳头距离，也不是肉眼圆圈；距离 5 才出圈。拳被推回、追召唤物回头、另一拳卡路时都可能重新无敌。普通观察与移动之后重算；攻击无敌拳、给它积延迟伤/流血在圈内都无效或浪费。来源：[YogFist.isNearYog/isInvulnerable](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L114)。

双拳战的基本优先级并非固定杀某一种：先解当前秒杀威胁，再争取同时只有一拳拥有有效攻击线。用保护圈外、与 Yog 有够远侧向空间的区域接战，保留躲本体线的两方向出口；不要把自己夹在两拳、墙与草之间。以下每个“利用”都是源码推导，需在当前资源允许时执行。

### 火拳 BurningFist

每次行动在自己周围九格补火；踩到水会蒸干，随机再尝试蒸发周围一至二格。远程会蒸干目标所站水格；目标不在水则直接点燃，并在其周围补火。**水只可能挡当次直接点燃，不能提供长期安全平台**。来源：[BurningFist.act/zap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L224)。

它有 FIERY：免 Burning/Blazing、抗火焰冲击；另免 Frost，抗 StormCloud/GeyserTrap，所以不要把冰冻控住火拳或以火烧死火拳作为计划。火焰之血给予 50 回合 FireImbue，免并清除 Burning，可为贴身输出争取窗口；仍会吃拳头物理攻击和另一拳/本体伤害。来源：[BurningFist.immunities](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L280)、[Char.Property.FIERY](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1427)、[FireImbue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/FireImbue.java#L117)、[ElixirOfDragonsBlood.apply](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfDragonsBlood.java#L39)。

### 草拳 SoiledFist

每 act 在周围播普通草，并随机把已有普通草变成犁草；范围限制在 Yog 保护区以外。承伤乘 `(6-g)/6`，g 是自身加八邻格内 HIGH_GRASS/FURROWED_GRASS 的数量；g=3 约半伤，g=5 约六分一，g>=6 实际无效。普通矮草不计，踩倒/烧掉后要重新数；长时间在同一块空地站桩会让减伤重建。草拳可以着火但 `Burning` 伤害直接被忽略，**火主要用来清草而不是烧拳**。来源：[SoiledFist.act/damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L295)、[Char.damage negative guard](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L830)。

远程作魔法命中检定，命中根缚 3 回合，并在目标周围播草/概率犁草。清草后立即借无草位置输出，或把拳移离原草区；爆炸波会按九格 `pressCell` 并推走角色，可同时清草/位移，但必须核实自己也在推力范围、落点和拳是否被推回保护区。不要在计划里使用“火拳烧草拳”，同族不会同轮。来源：[SoiledFist.zap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L359)、[WandOfBlastWave.onZap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfBlastWave.java#L74)。

### 腐拳 RottingFist

站水每 act 恢复 `HT/50=6 HP`。非无敌时，绝大多数伤害转换为 `Bleeding.set(0.6×经抗性后的伤害)`；已有流血只在新随机初值更高时被替换，不按每次命中相加。因此高强度一击挂血再走位，常比弱小多段硬换更好；不能把“打出 30”当立即扣 30 HP，也不能把每次强度简单相加。源代码特例保留 Bleeding 本身以及 Sickle 的特定 tracker 路径，这不等于战士普遍拥有镰刀决斗家能力。来源：[RottingFist.act/damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L407)、[Bleeding.set](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Bleeding.java#L71)。

远程在目标格投 100 体积 ToxicGas；近战 50% Ooze。拳免 ToxicGas，ACIDIC 抗 Corrosion/免 Ooze；别在水里让它一边回血一边承自己的弱流血。把拳引至干地，英雄附近保留洗黏液用水且不让拳也站进去；用普通净化药剂 Purity 的 BlobImmunity 可以防气体 20 回合，但 Purity 不负责移除已经附着的所有负面。来源：[RottingFist.zap/attackProc](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L442)、[PotionOfPurity.apply](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfPurity.java#L93)、[BlobImmunity](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/BlobImmunity.java#L49)。

### 锈拳 RustedFist

LARGE、INORGANIC；免流血/毒/毒气，物理 22–44。所有非无敌的普通伤害先累计到 `Viscosity.DeferedDamage`，新池首次延迟 1 回合，以后每 tick 扣 `max(1,floor(剩余池×0.1))`。图标是剩余池，不是已经扣掉的血量。远程**无需命中检定**直接给 4 回合残废。来源：[RustedFist](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L466)、[Viscosity.DeferedDamage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/Viscosity.java#L103)、[Char.Property.INORGANIC](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1425)。

可利用：累计合理池后腾时间处理另一拳、幼虫或躲线，避免为加快已经足够的致死池继续挨重拳。保持它出保护区；延迟 buff 先请求造成伤害，再从池扣同量，若回圈无敌便发生**池减少却不掉 HP**。大型限制也可用于公开已知窄空间的路线规划，但不能推断一切狭路都安全。它免毒/流血意味着毒镖、毒气及流血堆叠很差；腐蚀不在 INORGANIC 的免疫列表，不能把“无机”泛化为免所有持续伤害。

### 光拳 BrightFist

远程没有冷却，成功魔法命中 10–20 加 5 回合失明；相邻改近战。半血穿越时若仍存活，把 HP 回到 150，给英雄 15 回合失明，再传送到当前视野外、非固体无角色且可达出口的格；死亡另给 30 回合失明。它有 ELECTRIC 抗闪电、电击等；想稳定换掉远程连盲，应断线后贴身，但九挑战弱甲下近战并非免费。来源：[BrightFist](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L501)、[Blindness.DURATION](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Blindness.java#L27)。

要为**半血和死亡两次**准备不同恢复窗口；全面净化合剂 Cleansing 可移负面并给予 5 回合 Cleanse，若两次跨越靠得足够近，免疫时间也可能覆盖后一次，但不能未经计时就把一瓶算成包办两次。荒芜不能默认现场种魔皇草，旧复盘不是九挑战资源证明。注意一个源码边界：半血判断含 `isAlive()`；足够大的一次伤害从 >150 直接致死会跳过半血传送，但死亡失明仍存在。不要假定当前装备已能做到。来源：[BrightFist.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L543)、[PotionOfCleansing.cleanse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L71)。

### 暗拳 DarkFist

远程同样无冷却，成功魔法命中 10–20，并把目标 Light 剩余时长减 50；相邻改近战。半血存活时回到 150、直接移除英雄 Light、传视野外；死亡再移除 Light。致盲清除剂不能代替火把；重新点亮要占用动作，也可能再被远射削时。考虑在半血/死亡后，先确保没有本体待发线和别的敌人射线，再点亮。与光拳一样，一击越过零血可跳过半血分支，但不跳过死亡熄灯。来源：[DarkFist](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L569)。

## 6. 入场清单与真正结束点

九挑战战士在 24 层下楼前应按现有资源完成以下计划，而不是照列表凑指定装备：

1. 四巢是否确实死亡；当前主输出能否在弱甲、少升级下击杀出圈拳。留下用于最后 100 HP 的可靠爆发。
2. 有足够照明与至少一次光/暗拳半血后的重新定位方案；有光拳最终死亡后的清盲/安全等待方案。计划须包括点火、喝药、移动的行动成本。
3. 至少一种稳定断线/控距手段，尤其是双拳加幼虫拥堵时的出口；事先记住 Yog 线穿墙，不能把墙当本体线的解。
4. 火拳的灭火/抗燃窗口、腐拳的防气/洗黏液路线、草拳的清草办法、锈拳延迟池读数和避免回圈的方法。不要把一个“通用免疫药”当成对所有伤害都免疫。
5. 食物、可用回复、护盾与复活分开预算。已清的路线能省探索消耗，但首领锁层不允许假定无穷安全休息；消耗品是否受恐药/荒芜限制依其实现逐项核对。

击败 Yog 只会解除封层并清召唤，尚未等于已经结算胜利。拿护符会打开 AmuletScene；选择结束才调用 `Dungeon.win(Amulet.class)`。选择留下仍可继续，而带护符返回 25 层入口会触发明确的上行挑战选择；上行是另一段需要物资与停机政策的任务，不应为了“九挑战通关”擅自开始。来源：[YogDzewa.die](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L529)、[Amulet.doPickUp/showAmuletScene](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Amulet.java#L71)、[AmuletScene exit](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/AmuletScene.java#L78)、[HallsBossLevel.activateTransition](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/HallsBossLevel.java#L335)。

## 7. 存读档边界与待实战验证点

本区域核心威胁明确持久化：恶魔眼目标/冷却/蓄力，撕裂魔上一敌位/跳点/冷却，血巢生成冷却/存活计数，拳远程冷却，Yog 阶段/两拳列表/召唤池/冷却/待发目标格，均写入 bundle。**退出主菜单重进不能被当成取消已蓄射线或跳跃、重抽拳头、重置巢的保证。** 这里只读序列化源码，没有实际读取用户存档。来源：[Eye.storeInBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Eye.java#L248)、[RipperDemon.storeInBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/RipperDemon.java#L91)、[DemonSpawner.storeInBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DemonSpawner.java#L159)、[YogFist.storeInBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L202)、[YogDzewa.storeInBundle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L593)。

未用隔离运行验证的边界：恶魔眼在不同动作时序下重新瞄准与旧线保留、双拳拥堵下逐步控距、光暗拳从半血以上一击击杀的实际可达构筑、根缚解除瞬间与本体 actor 顺序。这些需要以后依据当前公开观察按一动作一验证；不能把此研究称为已完成九挑战通关或无伤演示。
