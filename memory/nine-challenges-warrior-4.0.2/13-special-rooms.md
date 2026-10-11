# 特殊房与有战术价值的常规房：九挑战战士探索决策

研究日期：2026-10-11。工作树基线 `d04c8f862`，游戏规则仍为此前核查的 `8405ae82f` / 4.0.2 / code922。本章仅静态阅读本项目源码与项目内已公开复盘；没有启动/操作游戏、读取个人存档或私有审计，也没有把源码生成数组当当前地图观测。建议以降低九挑战通关过程中的无效损耗为目标，尚无胜率统计或九挑战成功实测。

先读 [00资源止损](00-run-plan.md#31-资源与遭遇的止损规则) 和 [2026-10-10失败复盘](../2026-10-10-warrior-nine-challenges-retrospective.md)。失败局在第4层饥饿死亡的主要教训适用于每个房：**进入、解谜、拾取、战斗、离开、回收全部都消耗营养/照明，房间奖励并不自动抵消这些成本。** 主动搜索、无限等怪、站着回血不可挤掉补给与出口预算。隐藏房由独立章节讨论，本章不重复推导秘密门搜索策略。

## 1. 生成规则、证据等级与房型总清单

`levels/rooms/special/` 共 **24个Java文件：1个基类、23个具体房类**。19类常规轮换（9装备倾向、10消耗品倾向），另有实验室、坑底、商店、恶魔血巢4类由独立规则加入。当前这23类都有活跃生成路径，没有发现该目录内仅存档兼容、不再正常生成的具体房类。

常规轮换并非每房独立均匀抽签：开局分别洗牌装备/消耗品列表，然后交错排队；每次偏向队首前三位，权重6:3:1，用过的移到队尾。同层每种最多一次，**水晶钥匙类最多一类、6种保底谜题药水房最多一类**。薄弱地板在下层是首领层时排除；出现后要求下一层补坑底房，且坑底不挤掉原定特殊房数量。实验室每区域第三/第四普通层（3/4、8/9、13/14、18/19、23/24）加入，前一候选有1/2机会，否则后一层补齐。[SpecialRoom队列](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SpecialRoom.java#L82)、[createRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SpecialRoom.java#L158)、[RegularLevel.initRooms](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L124)、[labRoomNeeded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L602)

普通楼层特殊房基础数：下水道1–2（均值1.8），监狱1–3（2），洞穴2–3（2.2），都城2–3（约2.33），大厅2–3（2.5）；大型楼层取该区最大数再加1。商店、血巢与坑底的额外加入要另算，因此不能通过“已经见了两个特殊房”断言本层无其他重要房。[Sewer](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/SewerLevel.java#L95)、[Prison](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/PrisonLevel.java#L102)、[Caves](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CavesLevel.java#L102)、[City](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CityLevel.java#L100)、[Halls](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/HallsLevel.java#L104)

| 具体房类 | 生成组 | 可观察的典型特征 | 本章核查位置 |
| --- | --- | --- | --- |
| ArmoryRoom | 装备轮换 | 锁门、装备/炸弹散放、角落静态雕像 | §3 |
| CryptRoom | 装备轮换 | 锁门、墓穴、两侧静态雕像 | §3 |
| PoolRoom | 装备轮换、谜题药 | 水池、食人鱼、对侧奖励 | §5 |
| SentryRoom | 装备轮换、谜题药 | 红色哨卫、装饰危险地板、奖励台座 | §5 |
| StatueRoom | 装备轮换 | 锁门、带真实武器的活化石像 | §3 |
| CrystalVaultRoom | 装备轮换、水晶钥匙 | 双水晶宝箱选择 | §6 |
| CrystalChoiceRoom | 装备轮换、水晶钥匙 | 两支奖励路线/水晶门 | §6 |
| SacrificeRoom | 装备轮换 | 深渊环绕的小路、祭火台座与掩体雕像 | §4 |
| WeakFloorRoom | 装备轮换 | 破旧/薄弱地板、可掉落下层的井口区域 | §7 |
| RunestoneRoom | 消耗品轮换 | 锁门、深渊外圈、通到中央的真实桥、符石 | §3 |
| GardenRoom | 消耗品轮换 | 锁门、草地、高草与Foliage效果 | §4 |
| LibraryRoom | 消耗品轮换 | 锁门、书架、卷轴 | §3 |
| StorageRoom | 消耗品轮换、谜题药 | 路障封口与储藏物 | §5 |
| TreasuryRoom | 消耗品轮换 | 锁门、中央静态雕像、多金币堆/宝箱 | §3 |
| MagicWellRoom | 消耗品轮换 | 锁门、单口有明确水效的井 | §4 |
| ToxicGasRoom | 消耗品轮换、谜题药 | 持续毒气陷阱与内部奖励 | §5 |
| MagicalFireRoom | 消耗品轮换、谜题药 | 整条永燃魔法火与内部奖励 | §5 |
| TrapsRoom | 消耗品轮换、谜题药 | 密集显形陷阱/深渊与对侧奖励 | §5 |
| CrystalPathRoom | 消耗品轮换、水晶钥匙 | 连续水晶门与分段奖励 | §6 |
| LaboratoryRoom | 区域独立保底 | 锁门、炼金锅、5能量晶体及1–2奖品 | §3 |
| PitRoom | 上层WeakFloor触发 | 落点井、墓骸、水晶钥匙/分支奖励 | §7 |
| ShopRoom | 6/11/16层独立加入 | 店主和标价商品 | §8 |
| DemonSpawnerRoom | 21–24层独立加入 | 不会随机隐藏的门、血巢 | §8 |

“可观察特征”只是候选识别，必须以当前门、物品、环境描述与完整已知格验证；不能从局部图案推断墙后一定是什么房。目录基类 `SpecialRoom` 本身不生成房间；以上类别顺序不是当前局生成顺序。

### 1.1 四种不能混同的保证

1. `addItemToSpawn(new X)`：本层生成队列增加一个X；不意味着此房地上那件就是X、不意味着已捡到、不意味着背包某颜色已鉴定。
2. `findPrizeItem(X)`：从本层原有生成队列取走一个匹配物品，把它改放在房内；**不是额外赠送一件X**。不匹配可返回null并走随机奖励分支。[Level.findPrizeItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L821)
3. `Generator.random(...)`：该类别随机物品，等级/诅咒/具体种类除非房间代码另强制，否则仍不确定。随机“装备”不等于可立刻穿且更强。
4. 当前公开鉴定/已知排除：才是可执行物品选择的证据。房间线索可以缩小假设，但未知药剂试用仍须能承受麻痹、毒气、燃烧/冻结等最坏分支。死亡后鉴定与上一局颜色不能移用。

## 2. 每间房的进入、暂缓与回收决策

建立一行房间账：`当前可见收益 → 必要动作/耗材 → 可能敌人及最坏后果 → 退出路线 → 回收触发条件`。优先次序是已能安全兑现的永久升级/生存工具/粮光/有效炼金，而不是按房名固定全清或全跳。

| 当前状况 | 决定 | 回收条件 |
| --- | --- | --- |
| 锁门且同层钥匙未见 | 记录门与钥匙需求，沿有效主路线找钥匙 | 回程实际多走的格数、开门/拾取耗时仍在粮光预算内 |
| 明确需要的解谜瓶未鉴定 | 保留可能瓶，先做安全鉴定/补完同层关键探索 | 已有确切可用物品或源码支持的替代解法 |
| 仅欠一种解法、奖励仍隐藏且耗材稀少 | 暂缓，优先首领/续航必需储备 | 替代方案变便宜、主线获得新工具、奖励信息可见 |
| 当前饥荒、无光/无脱困，任务收益不确定 | 缩短可选房投入，转已知补给/出口；不追加无限等待 | 获得真实营养/光照/恢复后重新定价，不沿旧坐标自动回来 |
| 房里存有确定治疗井/可用法杖/关键炼金 | 允许有预算的有目的回头 | 把返程遭遇、两次楼梯与已刷新敌人风险计入，不把旧安全图当当前敌情 |

物品堆拾取可能连续走入并拾取，爆炸/火/冻结可能毁掉或改变掉落；同帧清单须包括宝箱、墓骸、植物、怪、陷阱。对谜题房先沿已知路径算“去+开容器+捡物+回来”，持续时间不能仅算直线距离。钥匙按深度绑定，不应浪费主线生命去寻找根本不在该层适用的钥匙。[Heap爆炸/冻结](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L268)

## 3. 有钥匙通常可直接取的资源房：奖励仍有细节

| 房型 | 确认的奖励/生成事实 | 九挑战战士建议 |
| --- | --- | --- |
| Armory | 2–3件，从炸弹、近战武器、护甲、投掷武器四类别不重复抽；若本层队列有饰品催化剂再额外放它。锁门+同层铁钥匙 | 通常值得短绕路；先按真实力量需求/诅咒知识检视，别为了有新甲而降低移速。炸弹/投掷是解谜和远程资源，非纯售卖品 |
| Library | 1–3件，第一件50%鉴定卷/50%祛邪卷；后续先取队列催化剂，其次队列卷轴，最后随机卷轴。锁门+铁钥匙 | 首件在**种类集合**上保底但外观仍未知；两种都可能解决九挑战关键装备问题，不应把第一件直接当祛邪读来赌。房内书架不是药水谜题火障 |
| Runestone | 2–3件：先队列催化剂、其次队列符石，否则随机符石。锁门+铁钥匙；外圈深渊但入口已有实地桥 | 不需要因为有深渊便浪费浮空；沿当前已知桥取。符石已知用途可换掉许多高成本解法 |
| Laboratory | 炼金锅、**5能量晶体**、1–2奖品；奖品优先催化剂，再本层保底力量药队列，最后随机药/符石；另按图鉴进度放配方页。锁门+铁钥匙 | 最迟区域第4普通层应有一间；若正缺已知转化可优先找。力量药可能在这里但并非额外保底、不自动鉴定。先列具体要做的配方和成本，再开炼金界面 |
| Treasury | 2–3个主要奖励，优先催化剂否则随机金币；整房50%宝箱布局/50%散钱布局，散钱再有6堆5–12金币。宝箱且depth>1，每箱基础20%×拟箱饰品系数替换拟态怪。锁门+铁钥匙 | 见宝箱先检视；为近期商店明确缺口取钱优于盲赌怪。金币不能直接替代食物/HP，最后口粮耗尽时不为随机钱堆长绕 |
| Crypt | 高一个区域装备表的护甲，必为已知诅咒；若生成时本来不诅咒，则额外+1，若没有正面刻印再加诅咒刻印；墓穴容器，锁门+铁钥匙 | 不是“必定+1以上好甲”：原本就诅咒的分支无额外升。需要解咒且盔甲挑战大削DR；高阶甲仍可增战士普通盾，但必须力量够。开墓有怨灵，不贴脸多开 |
| Statue | 一只可见持武器的被动活化石像，锁门+铁钥匙；普通石像有不诅咒的附魔武器，稀有装甲石像还有不诅咒刻印甲 | 先检视公开奖励种类/附魔、HP与站位再激活；无收益不激怒。负面Buff或伤害会使其狩猎，远程武器特性也由实持武器决定，不能把它当固定一格慢怪 |

房型源码：[Armory](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ArmoryRoom.java#L38)、[Library](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/LibraryRoom.java#L39)、[Runestone](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/RunestoneRoom.java#L46)、[Laboratory](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/LaboratoryRoom.java#L46)、[Treasury](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/TreasuryRoom.java#L39)、[Crypt](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CryptRoom.java#L39)、[StatueRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/StatueRoom.java#L34)。

墓穴 `TOMB` 打开会在**英雄周围** `Wraith.spawnAround()`，不是只在墓碑格出怪。墓骸 `SKELETON` 若 haunted，试图在其格生成怨灵；生成失败则英雄扣当前HP/2，不能用占住生成格当免费避怪。容器与地上堆不同，火、炸、冰对它们的处理也不同。[Heap.open](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L86)

石像HP `15+5*depth`、基础闪避 `4+depth`，武器决定伤害/攻击耗时/触及；无机免毒与流血，不能用“毒雾慢磨所有敌人”覆盖它。装甲石像HP翻倍 `30+10*depth`，追加实际护甲DR和刻印效果；稀有概率基础10%，鼠头骨修正另算。普通死亡掉其已鉴定武器，装甲版再掉甲（特定腐化复制追踪有不掉原装的例外）。[Statue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Statue.java#L56)、[ArmoredStatue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/ArmoredStatue.java#L43)

## 4. 井、花园与祭坛：续航价值取决于真正生效的机制

### 4.1 治疗井与觉察井：一次有效使用即耗尽

当前 `MagicWellRoom` 默认仅在 **WaterOfHealth / WaterOfAwareness** 两类等概率选择，入口锁门并补铁钥匙。本基线没有 `WaterOfTransmutation` 类或普通“炼金井”第三种；炼金锅 `ALCHEMY` 是实验室设施，不能按井的投入物机制使用。[MagicWellRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/MagicWellRoom.java#L35)

治疗井对英雄：清除 `PotionOfHealing.cure()` 规定的负面，净化全部已装备可祛邪物品，`Hunger.satisfy(STARVING)` 清空普通饥饿，HP设到HT（鲜血试管饰品可改成其延迟治疗）。这里没有治疗药剂恐药分支、也没食物除三分支。**一口井可以同时兑现回血、饱腹、解咒**，九挑战下是高价值资源；但不要为最大化这三项主动挨到危险血线或先装备会立即致险的诅咒品。对英雄的直接治疗优先于同格堆物效果。[WaterOfHealth](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java#L48)、[uncurseEquipped](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Belongings.java#L395)

治疗井投物有三个有效方向：未满水袋→装满；未祝福十字架→祝福；可祛邪物品→解咒。它们与直接踩井**互斥消耗同一次井水**。水袋装满后20滴按每滴约5%最大HP可便携恢复，荒芜禁的是自然露珠生成，不抹掉水袋已有水；代价是放弃当次饱腹/全套已穿解咒。若当下饥荒低血且没有安全推进路线，便携总量不能替代立即喝井的组合收益。[井投物](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java#L79)、[Waterskin.fill](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Waterskin.java#L186)、[Dewdrop.consumeDew](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Dewdrop.java#L82)

觉察井对英雄：鉴定已装备物（遗忘碎片存在时部分装备变成待鉴定），让背包装备/法杖诅咒状态已知，发现本层秘密地形，施加Awareness。**不是全背包所有药剂卷轴全鉴定**。投一件尚未鉴定物走正式 `ScrollOfIdentify.IDItem`，是单物明确鉴定；根据已穿未知件数、地图探查价值与一件关键装备的重要性选择。[WaterOfAwareness](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfAwareness.java#L42)、[Belongings.observe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Belongings.java#L338)

`WellWater.affect` 只有英雄/物品成功生效才清水、转EMPTY_WELL；无效投物被抛到可落相邻格，不消耗井，但投掷仍耗英雄动作，也会丢失原位置绑定。不要投珍贵未知瓶试井类型，井的原生名称/描述才是先验检查。[WellWater](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WellWater.java#L56)

### 4.2 花园：仍可休整，但不是无限安全回血点

Garden锁门+铁钥匙，高草边缘/草地中心，Foliage覆盖内部。普通局植物抽样为：1/3阳春草、1/3无味果、1/15两者、4/15都没有；**九挑战下这几个 `level.plant` 调用都会被荒芜中止，不能期待花园保底阳春草或水果。** 草地/Foliage依然存在。[GardenRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/GardenRoom.java#L36)、[Level.plant荒芜](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1051)

Foliage每tick给站在内部的英雄延长Shadows2；有敌对怪相邻时Shadows不能附加，已有也会移除。它是隐形派生Buff，不是阻挡怪物的墙。非饥荒时Shadows令饥饿增长延迟乘1.5（消耗速率为普通2/3）；**已饥荒的HP损耗分支不因此下降**。Regeneration仍要求未饥荒，花园没有单独提升自然再生速度。九挑战可用作“有粮时、已脱离相邻敌人、限定目标与时长”的休整点，不能重复发持续rest直到最后食物/光照耗尽。[Foliage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Foliage.java#L86)、[Shadows](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Shadows.java#L57)、[Hunger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L78)、[Regeneration](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L56)

### 4.3 祭坛：顺路献祭可能赚钱，追怪献祭可能重演死亡局

无需钥匙；中心祭火及周围3×3余烬，深渊窄路、四向静态雕像提供部分遮挡。火量初始 `6+4*depth`；它不是普通燃烧伤害blob，而给中心九格角色续2回合祭祀标记。标记者真正死亡时还必须在祭火九邻内找到火源，才按死者类型累计贡献；不是站过祭坛后在房外随便杀也算。大部分有EXP怪贡献 `(1+EXP)*随机2或3`，石像/拟态/鱼/蜂/怨灵有专门估值，蝇群无经验分体也给1再乘2–3。因此不是固定“杀三只”。[SacrificeRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SacrificeRoom.java#L45)、[SacrificialFire标记/献祭](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/SacrificialFire.java#L73)

献祭成功奖励高一个区域表的武器，必为已知诅咒；原来不诅咒的武器会额外升一级，无正面附魔时再附诅咒。未完成的有效献祭给额外生成额度；全层 `mobCount()==0`、祭坛已访问且尚有额度时可调用 `spawnMob(4)`补怪，初始额度3。它是减少无聊的原生补怪，不是营养无成本刷怪发动机；集群/精英挑战让长时间拖怪更危险。[SacrificialFire.sacrifice](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/SacrificialFire.java#L153)、[祭坛奖励](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SacrificeRoom.java#L84)

战术推导：若本来要杀的近战敌人可用少量步骤引入、周边有当前已知退路、且有解咒/更换武器需求，值得做。若需探索大半层找怪、长等返门、无粮无光或已有可用武器，则暂缓。先把敌人移入祭火九格再计划收尾，别在目标离开范围后继续花稀缺弹药。祭坛显示火焰密度是公开进度线索，不能从私有volume窥测准确剩余需求。

## 5. 六种谜题药水房：保底药品与替代方案

| 房型 | 额外放入同层队列的药品 | 标准方案 | 关键反例 |
| --- | --- | --- | --- |
| Pool | 隐形1瓶 | 隐形期间越水取箱再回岸 | 浮空不是隐形；岸上相邻也可能挨鱼咬 |
| Sentry | 极速1瓶 | 三倍移速在充能完成前穿危险段 | **隐形无效**；攻击/开箱/拾取不能都按三倍算 |
| Storage | 液火1瓶 | 点燃木障并安全等其燃尽 | 不是保底食物；火可能烧掉内部卷轴 |
| ToxicGas | 净化1瓶 | 喝下20时间气体免疫，预算全程 | 投净化仅清当前气，不停止永久气源 |
| MagicalFire | 冰霜1瓶 | 熄灭任一段从而清整条永燃火 | 四邻也点燃；喝净化不等于移除通行障碍 |
| Traps | 浮空1瓶 | 喝下20时间飞行，往返结束前着陆安全格 | 投浮空是混乱气；不能据此获得飞行 |

上述六种同层互斥，但“有这个房→这层加入一瓶对应药”仍不是“手上这瓶颜色已知”。队列物品可被其它房 `findPrizeItem()`取去作为奖品，因此“解谜药必散在普通走廊，绝不在另一特殊房”也没有保证。剩余队列才在普通物品生成阶段落地。[普通队列落地](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L450)

### 5.1 食人鱼池 PoolRoom

墙内水池，入口岸线，远侧台座1宝箱、池内3只 `Piranha.random()`；鱼可稀有变幻影版，不能默认三只普通。奖品1/3先尝试本层队列，未取到则按高一个区域装备表40%近战/20%投掷/40%护甲。**后备装备分支**去诅咒、标无咒，1/3额外+1；队列物品不套这条去咒升级保证。[PoolRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PoolRoom.java#L51)

隐形持续20，必须覆盖下水、走路、开箱、拿物和回来，途中攻击会涉及破隐身。鱼移速2，HP=`10+5*depth`、命中=`20+2*depth`、闪避=`10+2*depth`，EXP0；有肉不证明打鱼赚营养。从真正非相邻且鱼不可接近的位置远程清除是一种解法；普通鱼被击退到非水格/飞起会死亡，但幻影鱼受非相邻伤害减半且会传送，上岸优先找水逃，不能把普通鱼解法套给它。鱼拥有大部分Blob免疫、例外包括Electricity/Freezing，还免Burning，毒火不宜默认清鱼。[Piranha](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Piranha.java#L44)、[PhantomPiranha](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/PhantomPiranha.java#L49)

取舍：若隐形是仅有的可靠脱战品且奖励尚无明确用途，可以保留隐形先推进。已有合适远程/击退且实测落点明确时可省药，但等待法杖恢复也有营养与光照成本；不要把“可充能”写成“免费”。

### 5.2 红色哨兵 SentryRoom

雕像线迫使绕行，主要地面是 `EMPTY_SP`，入口和奖励附近有普通 `EMPTY` 安全地，台座放1箱。奖励50%尝试队列，否则同Pool的高区装备后备规则。危险往返长度 `dangerDist=2*(dimension-5)`，初始充能 `dangerDist/3+0.1`，之后每1游戏时间再射；哨兵魔法命中 `20+2*depth`、伤害 `2+floor(depth/2)…4+depth`。它无限闪避、忽略伤害、拒绝Buff，不能殴打/麻痹/魅惑停机。[SentryRoom布局](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SentryRoom.java#L57)、[Sentry行为](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SentryRoom.java#L224)

开火条件同时要求英雄在其FOV、脚下是EMPTY_SP、在房矩形内、没有LostInventory。**不检查invisible**。离开危险地板/FOV会重置充能。因此标准解法是极速的三倍移动，先辨清安全停顿格；不要把全部交互耗时乘1/3。水爆可将EMPTY_SP变WATER，此格不再满足瞄准条件，可构建安全踏点；闪现石可跳到已知普通地/台座，但仍有投射阻挡和返程资源要求。机制允许成功遮挡FOV中止瞄准，本章没有证明任意烟/草方案必然成功。[Haste](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Haste.java#L33)、[Blink](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfBlink.java#L38)

取舍：路线尚未知、速度Buff不足、最后保命资源仅够去程时延期。九挑战薄甲下不可用HP测试哨兵射速。

### 5.3 木障储藏室 StorageRoom

入口BARRICADE、内部EMPTY_SP、3–4堆奖励；50%机会放一次完整蜂蜜罐，其余每件2/3先尝试队列，未取到则随机药/卷/食物/金币。不能承诺有食物。补液火1瓶。[StorageRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/StorageRoom.java#L35)

木障可燃但实心，液火投在邻接可落格生成火也能传播到它，不一定能/需要直接砸木障。火焰法杖有近距离点墙点燃木障/书架的专门处理；破坏性炸弹能毁可燃地形，但爆炸半径和堆物损毁必须一起核算。先取安全站位，再有限等待火与通行状态确实变化，不开无限rest。看到内部食物才提高它在当前营养危机中的确定收益；火后仍可能剩伤害区。[液火](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfLiquidFlame.java#L39)、[Fire](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Fire.java#L49)、[Fireblast](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFireblast.java#L98)、[Bomb](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bombs/Bomb.java#L147)

### 5.4 毒气通风房 ToxicGasRoom

中央雕像，开局内地板铺30毒气，多个已揭示ToxicVent位于INACTIVE_TRAP，每处气源ToxicGasSeed12。通风口trap.activate不做事，不是触发一次就解除的普通陷阱。远侧一堆双倍随机金币的骷髅堆，另2宝箱优先催化剂否则金币；本房代码不对这两箱额外抽拟态。[ToxicGasRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ToxicGasRoom.java#L49)

喝净化提供20时间BlobImmunity，覆盖ToxicGas；毒气每次演化伤 `1+scalingDepth()/5`，非普通护甲路径。**投净化只清有限范围现有毒气，ToxicGasSeed仍会补气**；解Poison状态也不是毒气免疫。水爆把通风口改水移除载体后，气源演化因不再INACTIVE_TRAP而停止；其他源和已有气仍须处理。[ToxicGasSeed](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ToxicGasRoom.java#L127)、[Purity](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfPurity.java#L44)、[ToxicGas](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/ToxicGas.java#L37)

取舍：多为金币，HP/粮光不足时优先延期；20时间包括开骷髅、开箱、捡三处、返回。不要先喝再临时决定动线。已有净化也可能是其他首领/气体危机的优质保命储备。

### 5.5 永燃火房 MagicalFireRoom

整条EternalFire横/竖分隔，后方3–4奖励，50%一次蜂蜜罐，其余生成规则类似Storage；普通入口门、补冰霜1瓶。火格既不可通行也不可avoid，**不是可硬走过去的普通火**；火格及四邻会施Burning4。站在火旁边试瓶也危险。[MagicalFireRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/MagicalFireRoom.java#L57)、[EternalFire](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/MagicalFireRoom.java#L156)

它检查火格九邻的Freezing/Blizzard或火格变水，清任一段会全组fullyClear并重建碰撞。除了冰霜瓶，冰霜法杖明确清碰撞格并补查下一格；投净化可清EternalFire；水爆确实把火格改水也能灭全条。喝净化虽然可免对应点燃却不自动清阻挡；浮空也不是穿这条屏障的许可。等永燃火自然耗尽无效。[Frost瓶](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfFrost.java#L39)、[Frost杖](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfFrost.java#L60)、[Purity清blob](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfPurity.java#L55)

取舍：充足法杖当前电量可换掉珍贵药水；但为了等一发长休息也可能更贵。先避开四邻、确认整条火和collision已清，再执行取物路线。

### 5.6 显形陷阱/深渊房 TrapsRoom

房宽高6–8，25%深渊版、75%同类显形陷阱铺满版，远端1箱，补浮空1瓶。各区陷阱候选：下水道夹伤/传送/羊群，监狱毒镖/夹伤/爆炸，洞穴毒镖/闪光/爆炸，都城空间扭曲/闪光/解离，大厅即死。陷阱版远端一排是普通地面；深渊版远端仍是深渊，仅箱子格改安全地/台座，不能猜有整排立足点。奖励2/3先尝试队列，否则高一区50%近战/50%护甲、去咒标明、1/3额外+1。[TrapsRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/TrapsRoom.java#L50)

喝浮空20时间使flying，非飞行占格才检查坠坑/地陷阱；**浮空结束会再occupyCell**，绝不可在坑/陷阱上等显示0。投瓶产生混乱气，不给自身飞行。闪现可跨到已知安全格但传送也调用occupyCell、且一颗石不保证往返；念力抓取只能拿HEAP，未开箱不能被直接抓取，失败还可能耗时。投物hard-press某些一次性陷阱可以解除，但毒镖/解离/即死的射击、爆炸连锁都可能伤英雄，不能统称安全排雷。[Levitation](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Levitation.java#L40)、[瓶饮投差异](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfLevitation.java#L43)、[占格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1176)、[抓取](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/TelekineticGrab.java#L102)

### 5.7 水爆这个通用替代品的准确边界

`AquaBrew.shatter → GeyserTrap.activate → Level.setCellToWater(true)`：距离<2的受影响格确定改水，距离2仅2/3概率；可以把EMPTY_SP、普通/隐藏/失效陷阱改成水，清除原trap，却**不能把CHASM改水**。因此它能给哨兵房造安全踏点、关闭毒通风载体、铺局部陷阱路、灭永燃火，但不通吃深渊房。它还会击退附近角色，先验证英雄/敌人的受推方向与末端；改地形后重新观察，不能把随机外圈预填成已知安全水格。[AquaBrew](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/AquaBrew.java#L38)、[GeyserTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GeyserTrap.java#L55)、[setCellToWater](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1118)

## 6. 水晶钥匙三选题：先读真正容器类型

水晶门是SOLID但不挡视线，能看到后方不等于能通行/射穿。门和水晶宝箱都消费当前深度的水晶钥匙，常规开锁花1时间；打开门不会自动毁掉另一侧奖励，“互斥”主要来自钥匙预算。Pit/Vault/Choice/Path同层互斥，不能计划到同层另一水晶房再找一把补齐。[Key深度](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/keys/Key.java#L38)、[Hero开门](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2421)、[Terrain](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Terrain.java#L96)

| 水晶房 | 明确提供 | 能公开知道什么 | 九挑战选择原则 |
| --- | --- | --- | --- |
| CrystalVault | 外铁锁+1铁钥匙；内2水晶箱+1水晶钥匙。法杖/戒指/神器洗牌取前2类别；神器池空可回退戒指 | **真实水晶箱检视给类别**，不给具体种类/等级/诅咒；两个实物类别不绝对不同 | 依据当前缺口比较法杖/戒指/神器。无可靠伤害手段时杖类别的用途可能高，但不保证是期望杖 |
| CrystalChoice | 外铁锁+1铁钥匙；内2水晶门+1水晶钥匙。一边3–4随机药/卷，另一边普通CHEST，内随机法杖/戒指/神器 | **普通箱不能预览类别**；明面消耗品仍按已知鉴定/外观判断 | 比较可见确定收益与未知装备的一份机会，不能把箱子说成已知法杖/戒指；无力量/升级专项保底 |
| CrystalPath | 6小间6水晶门，仅3钥匙；药剂一侧3件、卷轴一侧3件，同侧去重，普通/异域对应算同类 | 最常见放中间、最稀有最深，另一个最近；最深奖励通常需先开前门才可见。稀有排序不是鉴定 | 一侧最深需2钥匙，余1另选，预算不够两侧最深。按当帧可见需求选，不为“深处一定神药”先耗光 |

[CrystalVault](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalVaultRoom.java#L53)、[CrystalChoice](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalChoiceRoom.java#L105)、[CrystalPath布局](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalPathRoom.java#L74)、[容器检视](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L399)、[神器回退](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L706)

CrystalPath只保证“嬗变卷轴/其异域”与“经验药剂/其异域”**二者之一**，不是两者都有；其余五件随机，同侧类别去重。深处稀有药不自动等于经验、也不保证普通而非异域；注意经验药与神意启发等形态的即时收益不同。[Path奖品](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalPathRoom.java#L171)、[排序去重](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalPathRoom.java#L196)

CrystalVault第二箱基础10%可能换水晶拟态，受鼠颅/拟态牙修正。若能正确识别并安全击败拟态，可不花水晶钥匙拿其奖励、保留钥匙开真箱，两份都拿；但它会偷合格未装备物、逃跑，出视野且距离≥6可能逃掉。九挑战先有封路/控制/伤害预算才利用这个机会，不因可能双奖盲点箱。[CrystalMimic](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/CrystalMimic.java#L68)

随机传送不提供穿水晶锁取宝保证：优先未探索传送会跳过仍有水晶门的SpecialRoom，兜底主要挑普通房；定点传送要求可达连通。念力抓取不直接开箱。不要为“我还有传送”就把最后一把钥匙花到不能回来的路上。[Teleport](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L147)、[定点连通验证](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L77)

## 7. 薄弱地板与下层坑底：跨层取宝不是无伤井跳

WeakFloorRoom实际铺CHASM与伸出的EMPTY_SP实地条，装饰井仍是CHASM并仅附可见地标；不是整片普通地板都会自动塌。**从该房实际坠落格掉下**即标记fallIntoPit，下一层选Pit可走无怪落点，不必只跳装饰井那个格；当前实际地形必须观察确认。[WeakFloor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/WeakFloorRoom.java#L39)、[Chasm](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Chasm.java#L99)、[落点](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L768)

PitRoom外门水晶锁，中间遗骨含**本层水晶钥匙**，主奖励为戒指/神器/装备组三路均等；装备组近战2、投掷1、护甲2权重。另有1–2件药/卷/食物/金币。没有必升/必无咒保证；诅咒物会使遗骨haunted。落点井是EMPTY_WELL干井，**不会跳下后回血**。房内不生成草/陷阱，但怨灵与开遗骨后的处境另算。[PitRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PitRoom.java#L46)

无羽落时：落地瘸腿、流血初值约 `round(HT/(6+6*HP/HT))`，并承受 `max(HP/2, NormalIntRange(HP/2, HT/4))` 直接坠落伤。整数取整与流血随机衰减都存在，护甲不是防坠来源；“扣半血所以当前HP的一半肯定够活”是错误预算。[heroLand](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Chasm.java#L131)、[Bleeding](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Bleeding.java#L75)

优先比较羽落合剂：1浮空药+10能量→1羽落，Buff50时间，每次坠落再消耗10；落地有羽落会提前返回，免上述伤害/流血/瘸腿。**浮空与羽落不同**：浮空飞过坑不会进入下层奖励；在悬崖上等浮空结束仍是坠落，不自动变安全。全面净化能事后清流血/瘸腿并清饥饿，但不能追回先前坠落直接HP损失。[羽落](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfFeatherFall.java#L44)、[Cleansing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L75)

跨层回收模板：

1. 跳前完成上层仍值得且可安全取的关键物资，记录未取项目和原因；预算下层新探索、异常落点与返楼梯成本，不能假定楼梯就在门外。
2. 明确羽落/落地损伤处理和粮光余量，再选择实际坠落动作；不得用保存读取隐藏下层图规划。
3. 落地先看HP、出血、周围堆与怪，从旁开遗骨，给怨灵生成与自己撤退留格；占住遗骨令haunted生成失败可能扣当前HP/2，不能当“堵刷怪”。
4. 拿当前层水晶钥匙开门，探明正常上楼梯；若计划继续下行则不为“上层还有随机箱”无条件回头。
5. 上下层物品/控制句柄、scope/rev各自更新，不沿用落前操作绑定；没有本局完成证据前不把理论掉落写成已获奖励。

## 8. 商店和血巢：特殊房目录中的主线价值点

ShopRoom普通生成固定6、11、16层，独立于19类轮换。基础库存含适区+0无咒近战/投掷/护甲、药镖2、炼化2–3、一个按背包节省空间挑选的尚未生成袋子、治疗1+随机药2、鉴定/祛邪/魔法地图各1、另2随机药或卷、**小口粮2**、炸弹/双炸弹/蜂蜜罐其一、十字架、强化符石以及一个稀有品（法杖/戒指/神器各10%，其余铁笔）。20/21库存分支供小恶魔商店等调用，普通`shopOnLevel()`并不保证那些层自动出现商店。[ShopRoom.generateItems](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ShopRoom.java#L222)、[店层](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L445)

九挑战优先按当前确定缺口买：两小口粮对生命时间和丰盛一餐都有价值；鉴定/祛邪可能解锁已背着的有效装备；魔法地图可缩短昂贵搜索；袋子与应急投射/控制按背包和路线评估。治疗瓶不是即时治疗储备。标价为物品value×5×区域倍率，深层回头卖/买须计入路线；不为便宜旧商店来回穿两区。先看真实库存、钱、物品价值，不套固定“每店必买所有”清单。[Shopkeeper价格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Shopkeeper.java#L200)

攻击/负面影响可警告店主，后续伤害令其逃走并撤走商品；首次警告会清附近有害blob但不是可无限白用的净化服务。商店内不要试未知危险药/范围法杖。[Shopkeeper.processHarm](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Shopkeeper.java#L93)

DemonSpawnerRoom由HallsLevel额外加入，血巢房门显式UNLOCKED、不会随机隐藏，不生成陷阱/水/草。清巢有明确最终首领价值，优先级高于普通随机财宝，但仍按21–24层实际战斗与返程预算安排；详细怪物/全九挑战Yog联系见 [05](05-enemies-halls-yog.md)。不能因为常规特殊房数量已满足便漏找血巢。[Halls.initRooms](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/HallsLevel.java#L91)、[DemonSpawnerRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/DemonSpawnerRoom.java#L36)

## 9. 十类容易误认成特殊奖励房的常规房

以下10类在StandardRoom的额外小权重区真实生成；它们一般**不补对应解谜药水**。其余常规几何房（桥、环、柱、雕像线、书架等）主要改变视线/通路，不在本章声称逐模板最优路线。[StandardRoom活跃表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/StandardRoom.java#L125)

| 常规房 | 当前源码实际内容 | 九挑战处理 |
| --- | --- | --- |
| Aquarium | 中央水区1–3条按尺寸的鱼；常规物品/角色生成排除中央水；无Pool式奖励箱/隐形补给保证 | 不为误认“水池必有隐形”倒推药剂。岸路照常走，杀鱼须算肉收益与零经验成本 |
| Platform | 深渊与有真实桥连通的平台，可与相邻深渊类合并 | 先看连通实桥，不因为平台隔一格就喝浮空；强制位移/眩晕时远离边缘 |
| Burned | 烧焦斑块里混普通地、余烬、显形燃烧陷阱、隐藏燃烧陷阱、失效陷阱 | 余烬不意味着已排雷；有限有针对性搜索/绕已知安全地，不全房反复按搜索 |
| Fissure | 房中心裂隙深渊，外圈通路 | 无WeakFloor→Pit保证；普通掉落下一层不保证宝藏和钥匙 |
| GrassyGrave | 多墓穴，1个随机物品其余金币 | 每个TOMB都可能引出周围怨灵，不能把多墓当免战钱堆；从可控方位逐个开 |
| Plants | 高草与1/2/4株布局，正常种子排除烈焰花 | 荒芜下plant被阻，仍有高草遮线；没有花园Foliage就没有Shadows省粮收益 |
| Striped | 条纹/同心高草与EMPTY_SP | 用真实高草遮线伏击；踩倒后路径/视线改变，不把图案当固定隐形 |
| Study | 书架环、中央台座1奖品；50%先试队列，否则随机药/卷 | 无铁锁且常短绕，可优先取已见资源；烧书架前防台座药卷受损；没额外冰霜/液火保证 |
| SuspiciousChest | 台座1箱，优先本层队列否则金币，基础1/3×拟态牙倍率为拟态怪 | 检视且准备当前能承受的开箱/攻击；不是特殊双水晶箱，不能预览类别 |
| Minefield | 随尺寸放多个爆炸陷阱，默认隐藏；周围随机余烬，饰品可揭示部分 | 余烬只是风险线索，不是安全证明；炸一个会牵涉周边，别用当前HP试路 |

逐类来源：[Aquarium](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/AquariumRoom.java#L48)、[Platform](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/PlatformRoom.java#L62)、[Burned](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/BurnedRoom.java#L69)、[Fissure](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/FissureRoom.java#L49)、[GrassyGrave](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/GrassyGraveRoom.java#L47)、[Plants](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/PlantsRoom.java#L63)、[Striped](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/StripedRoom.java#L49)、[Study](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/StudyRoom.java#L50)、[SuspiciousChest](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/SuspiciousChestRoom.java#L47)、[Minefield](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/MinefieldRoom.java#L48)

## 10. 供下一局逐房记录的最小经验条目

记录：游戏/CLI版本；楼层与本次公开房型证据；进入前HP/饥饿/粮/光/工具；选择和明确目标；原动作结果；实际耗时/损伤/耗材/掉落；为何拿/留/退出；下次改变。若只看到后墙一角，记“候选房型”，不记“必有某药”；若本次失败/退出，保留投入成本，不把后续死后鉴定倒填为当时可用知识。

本章覆盖全部23具体special房和10个具有独立风险/资源逻辑的standard房。未包括secret目录详解、每种常规几何的最优路线、当前CLI是否把每种门/机关的所有事实完备表达；那些由独立研究/审计负责。替代方案是源码可行性，尚未在九挑战真局验证收益，未来实战须逐步核验而非批量自动套用。
