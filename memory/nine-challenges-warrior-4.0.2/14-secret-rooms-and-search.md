# 14　秘密房、搜索与探图：把情报变成有预算的探索

研究日期：2026-10-11。源码基线 `d04c8f862b2f90418a6375f0b3ffc5c4325d0953`，游戏4.0.2 / code922、CLI.9.0.3 / protocol9。已重读[记忆索引](../README.md)、[决策速查](00-run-plan.md)与[第4层饥饿死亡复盘](../2026-10-10-warrior-nine-challenges-retrospective.md)。以下为源码事实和战术推导，没有操作真实游戏、读取个人存档/私有审计或验证新九挑战通关。

本章的核心策略是：**主动搜只覆盖有理由怀疑、能被本次动作实际检查的格；找到秘密后，仍先判断奖励是否值得走完取得并撤回的路线。** 九挑战的最后一份食物、最后一段照明和最后的安全退路，不能拿来给“这面墙说不定有宝物”无限续费。

## 1. 秘密房的数量、轮转和真正能推断的事

### 1.1 正常生成时的区域配额

`SecretRoom.initForRun`在新局开始时给五区分别定配额，普通楼层再按剩余配额/剩余楼层随机向上或向下取整分配。不是每层独立固定概率，也不是每层必有一间。

| 区域 | 普通主线楼层 | 区域基础配额 | 对当前楼层的正确理解 |
| --- | --- | --- | --- |
| 下水道 | 1–4 | 2；第1层明确返回0，因此分到2–4层 | 某一层0间正常；最后第4层承接本区未分配余额 |
| 监狱 | 6–9 | 2，25%概率为3 | 数量在开局已确定，玩家没有因此知道这个随机结果 |
| 洞穴 | 11–14 | 2，50%概率为3 | 不能凭前三层“没发现”断言第14层有几间 |
| 都城 | 16–19 | 2，75%概率为3 | 被动搜门越来越弱，定向探图的价值上升 |
| 恶魔大厅 | 21–24 | 3 | 不等于要为凑齐3间在高危层全图扫墙 |

正常主线基础合计11–14间、期望12.5间，另加每个`Feeling.SECRETS`楼层的1间；鼠王房、矿区秘密房是额外机制。这里的数量是**生成规则**，不是当前局可读取的隐藏计数，也不是“已找到几间”与“其余楼层还缺几间”的观测。因为未发现房间可能已经生成在上一层，所以“我找到0间”不等于“配额还剩2间”。

源码：[SecretRoom.initForRun / secretsForFloor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretRoom.java#L45)、[RegularLevel.initRooms：秘密层额外+1](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L158)。严格边界：这是正常构建的设计配额；异常的绘制失败会让外层重新build，而配额在initRooms已消费，不能据此宣称对所有异常生成路径有不可破坏的总数保证（[RegularLevel.build](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L104)、[Level.create重试](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L302)、[RegularPainter失败分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/painters/RegularPainter.java#L124)）。本研究未运行生成统计，也未认定某真实局发生此异常。

### 1.2 12种房型轮转，不是独立均匀抽奖

普通池包含Garden、Laboratory、Library、Larder、Well、Runestone、Artillery、ChestChasm、Honeypot、Hoard、Maze、Summoning。开局洗牌一次；每次从队首前3个按60%/30%/10%选择，选中的移到队尾。它鼓励多样性，但不保证前12次恰好各一种，更不保证下一间一定是最需要的食品房或治疗井。队列和各区剩余数保存进游戏存档；源码研究只说明算法，**禁止为规划读取这些私有字段**。

来源：[SecretRoom.ALL_SECRETS / createRoom / 持久化](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretRoom.java#L37)。

### 1.3 隐藏门不等于奖励秘密房

**新profile第2层的推进例外：** `GUIDE_SEARCHING`尚未找到时，原入口UNLOCKED门会强制变HIDDEN，入口同时放Searching指南页；不要求该层是SECRETS，也不要求intro开启。桌面新档只是关闭intro、开放起始选项，没有授予指南进度。因此即使跳教学也可能需要搜索入口暗门。按公开指南页/当前无通路情况定向搜入口邻墙，而非默认开展全层奖励密室搜索。[RegularPainter](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/painters/RegularPainter.java#L268)、[指南页投放](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/entrance/EntranceRoom.java#L120)、[Document初始NOT_FOUND](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/journal/Document.java#L270)、[DesktopProfileDefaults](../../desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopProfileDefaults.java#L33)。

普通房间连接也可能是隐藏门。非第1层时，普通门尝试隐藏的概率为`min(1,depth/20)`，但随后有图连通性约束，故不是地图中每扇门的最终隐藏概率。通常保留普通房的非隐藏连通；`SECRETS`层放宽成非完全孤立，可能需要搜门才能继续发现普通区域或出口。秘密层还把上述尝试概率改为`(0.5+p)/2`，不是简单“所有楼层都更多门”。

秘密房分支不从`ConnectionRoom`直接起接；若中间要加连接房，会使用`MazeConnectionRoom`，它的门也隐藏。因此发现一条隐蔽迷宫连接，终点还可能需要一次新的定向搜索。普通房门的默认候选在房边、排除房角；这可以帮助给**已经看见的墙面**排搜索优先级，不能据此把未知区域补成矩形房或确定哪格是门。

来源：[RegularPainter.hiddenDoorChance / paintDoors](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/painters/RegularPainter.java#L186)、[RegularBuilder.createBranches](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/builders/RegularBuilder.java#L143)、[MazeConnectionRoom隐藏连接](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/connection/MazeConnectionRoom.java#L32)、[Room.canConnect排除角落](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/Room.java#L206)。

可见的锁房、火墙房、弱地板房、宝库、陷阱宝物房等属于各自特殊房机制，不都在这12种池里；一扇隐藏门也可能只是一条捷径。**搜出门≠获得食物/法杖/治疗井。**

## 2. 战士搜索：范围、概率、饥饿和重复动作

### 2.1 主动搜一次究竟检查哪里

无跨职业天赋的战士，搜索半径1，即英雄周围3×3区域，排除自身格；还要满足该格在英雄当前`fieldOfView`中。盲目照着地图上“相邻”就认定已搜过不够，遮挡/失明等可能使某格不受检查。盗贼基础半径2；WIDE_SEARCH再加1，1点时使用圆形，2点时方形。战士没有这些原生盗贼收益；只有本局实际获得对应天赋才改算。

对已覆盖格：先见秘卷buff可直接成功；否则`canBeSearched=false`的陷阱永远不被普通搜索找到；其余普通隐藏门和可搜隐藏陷阱，**主动搜索成功率1**。当前明确的例外是天狗飞镖陷阱。不是“原地搜三次更保险”；如果第一次确实覆盖该普通格，第二次不会提高已完成确定检测的可靠性。要搜索此前未覆盖的另一面墙，应改变覆盖位置/视野再判断。

发现陷阱只令它可见，**没有解除其触发能力**；探地、先见、明示符石等调用同样的discover/reveal路径。先看陷阱类别与当前active呈现，再决定绕过、浮空、解除或安全利用，不能搜出红色机关后按普通地板走。

来源：[Hero.search范围和检测顺序](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2490)、[TenguDartTrap.canBeSearched=false](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/TenguDartTrap.java#L29)。新档默认跳引导，不把源码中的intro禁止发现分支当普通实战规则；仍以当前菜单设置为准。

### 2.2 被动搜不是每一次state查询抽奖

普通移动成功时调用`search(false)`，本身不再加搜索耗时。对每个范围内、FOV内、尚隐藏的可搜对象：

| 对象 | 无诅咒先见护符的被动发现概率 | 例子 |
| --- | --- | --- |
| 隐藏陷阱 | `0.4−depth/250` | 第2层39.2%，第24层30.4% |
| 隐藏门 | `0.2−depth/100` | 第2层18%，第9层11%，第19层1%，第20层起不可能靠这一普通概率发现 |

装备诅咒先见护符时普通被动搜总失败；主动搜普通门/可搜陷阱仍成功，但营养更贵。`Foresight`秘卷buff另行覆盖这些概率。普通原地等待和CLI只读查询不应被当作额外免费搜索尝试；加载时已有搜寻buff有单独刷新路径，这不是不停退菜单刷普通被动搜的保证。

来源：[Hero移动后search(false)](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1903)、[Hero.search概率](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2554)、[加载搜寻buff处理](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L878)。

### 2.3 主动搜2时间，通常总计6饥饿

`TIME_TO_SEARCH=2`、`HUNGER_FOR_SEARCH=6`。正常非锁层先执行`affectHunger(2−6)`，直接增加4饥饿，再推进2时间，正常饥饿速率下再涨约2，总预算6。不是“花2饥饿”，也不是“额外6再加2”。诅咒先见护符直接增加10，常规总预算12。光照和其他buff按2时间消耗；敌人也能行动。

这是基准预算：锁层跳过额外加饥饿；WellFed、Shadows、SaltCube、饥荒临界和actor调度会改变具体结果。已经饥荒时，`affectHunger`的超出量会转入饥饿伤害累计，不能把主动搜当“饥荒条满了所以额外营养无所谓”。不要读取隐藏Hunger.level/partialDamage反推出精确可活步数。

九挑战口粮100营养作量纲比较：5次常规主动搜≈30营养+10时间，10次≈60营养+20时间，**还没算到位、绕墙、开门、取物、接敌和返回**。这不是吃一餐就获得16次免费搜索的承诺；食物还有自然再生、丰盛一餐、钢铁之胃及主线推进价值。

来源：[Hero搜索常数](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L210)、[主动搜索费用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2609)、[Hunger.act / affectHunger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L64)。

## 3. 探图工具分别揭示什么

| 手段 | 真正得到的公开信息 | 不能据此推出的事 |
| --- | --- | --- |
| 普通视野/火把 | 受视野与遮挡限制的当前地形、可见实体 | 原生隐藏门仍画墙、隐藏陷阱仍像普通地；光照不是揭密术 |
| 主动search | 范围/FOV内普通秘密变为已揭示门/陷阱 | 不测绘墙后全室、不保证里面奖励、不侦察整层 |
| 探地卷轴（魔法地图） | 本层discoverable地形全部mapped，并揭其中秘密 | 物品位置、活敌分布仍未知；地图已知不意味着沿路安全 |
| 先见秘卷 ScrollOfForesight | 400时间内，搜索变为圆形半径8、不需要原视线，测绘范围并确定揭密；开启时立即扫描，行走继续扫描 | 不直接给整层敌人/物品列表；停在同一处不会自动扫描远处 |
| 先见护符被动“不安” | 当前FOV、方形半径3内存在可搜隐藏陷阱 | **明确排除隐藏门**；无提示不证明没有秘密房/天狗不可搜陷阱 |
| 先见护符主动探查 | 穿墙扇形测绘+揭秘密，范围内敌人与物品堆获得限时感知 | 不是全图全知；范围取决于目标距离/神器等级/充能，未覆盖区域仍未知 |
| 明示符石 StoneOfClairvoyance | 实际落点周围圆形半径20测绘、揭密，穿墙扫描 | 不是探魔符石；投射落点仍受投掷碰撞，空悬崖落点不激活普通符石 |
| 灵视药剂 | 20时间内感知全层合格mob及各自周围九宫格，另有近身2格感知 | 不揭空秘密房、不把隐藏门变普通门；隐匿宝箱怪和OBJECT有排除项 |
| 魔能透视合剂 | 50时间、圆形半径12穿墙视野，解除/免疫失明 | 不调用discover；能看到墙后布局/物件不等于隐藏门身份被揭示，战士普通search范围仍是1 |
| 棱光法杖 | 到真实碰撞点的每条路径格周围九宫格测绘、揭密 | 不是无阻挡长距离线；不要忽视充能、施法1时间、伤害与战斗需求 |
| 探魔符石 StoneOfDetectMagic | 对所选背包装备/法杖揭诅咒知识并报告正/负魔法；正面可能是等级>0或好附魔/刻印 | **不查房、不查门、不查地面宝物坐标**；也不等于完整数值鉴定 |
| 传送卷轴 | 正常未锁RegularLevel偏选未visited可站位置；有机会带到秘密房入口旁并自动揭门 | 随机且有筛选，不保证秘密房、不保证安全；不是指定隐藏房传送 |

依据与细节：

- **地图与秘卷**：[ScrollOfMagicMapping.doRead](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfMagicMapping.java#L43)、[Foresight的400/8与初始扫描](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Foresight.java#L28)、[ScrollOfForesight](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfForesight.java#L37)、[Hero.search先见分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2500)。先见的优点是跨多个层的持续探索价值；缺点是需要实际走过覆盖位置，400时间不是400个新格。地图则立即给本层路线，适合出口未知而预算紧、密集陷阱或关键层；先算是否值得为升级成秘卷支付炼金成本。
- **护符**：[被动警告](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/TalismanOfForesight.java#L303)；[主动扇形](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/TalismanOfForesight.java#L135)。主动距离上限`min(5+2×level,(charge−3)/1.08)`；角度约`round(200×.92^距离)`，越远越窄；敌/堆感知`5+2×level`时间，消耗约`3+1.08×距离`充能并推进1时间、解除隐身。应把一个窄长疑似连接或相邻多面墙放进有效锥形，而非只按鼠标所点的一个格理解扫描。
- **明示符石**：[StoneOfClairvoyance.activate](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfClairvoyance.java#L34)、[Runestone.onThrow悬崖/魔免/触格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/Runestone.java#L48)。可在安全已知落点覆盖大片疑似空白；不要把符石投到空悬崖上期待以落点为中心扫描，也要检查落点陷阱。
- **灵视/透视**：[MindVision20时间](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MindVision.java#L28)、[MagicalSight50时间/12范围](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MagicalSight.java#L30)、[Level穿墙sense](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1376)、[全层mob感知与排除](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1434)。地图与灵视互补：前者找路和揭门，后者提前定位威胁；二者都不免除移动/射线风险。
- **棱光**：[WandOfPrismaticLight.affectMap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/WandOfPrismaticLight.java#L110)；普通魔弹式路径在硬墙前格停，其九宫格依旧可能覆盖紧邻隐藏门。结合[08弹道](08-ballistics-and-wands.md)分析有效覆盖；用一发同时战斗与检查壁面比纯空射更有预算价值，但不为了“顺便探图”烧掉最后脱困充能。
- **探魔**：[StoneOfDetectMagic](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfDetectMagic.java#L44)。中文正式名可见[探魔符石](../../core/src/main/assets/messages/items/items_zh.properties#L1411)，与地图/透视/先见分别记录，避免“探魔能感到墙后的宝物”这种错误。
- **传送**：[ScrollOfTeleportation.teleportPreferringUnseen](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L147)。它会排除部分有锁门/水晶门/路障的特殊房候选，选中秘密房时尝试放到门外相邻通行格并揭隐藏门；也可能落到别处。随机传送用于脱险/减少回头时同时获得信息是附带收益，不为了刷房无计划地连续消耗。

## 4. 线索可信度：怀疑和确认分开写

| 线索层级 | 可合理作出的决定 | 保留的未知 |
| --- | --- | --- |
| 已实际揭示门/已测绘通路/当前可见奖励 | 算真实来回路程、守军、机关、所需工具，然后决定取或暂缓 | 箱内物、未照见敌人、动态伏击仍未知 |
| 当前原生“隐秘层”感觉或对应日志 | 本层正常额外1间秘密房，提高一次有限定向搜索/探图的价值；必要时为继续主路搜门 | 没有给房间坐标、奖励、基础房数量或安全性 |
| 实际ROGUES_FORESIGHT天赋发出的secret_hint | 该机制在普通层至少有1间、隐秘层至少2间才可能提示；可提高探索优先级 | 战士通常没有此天赋；提示仍不给位置/具体数量 |
| 护符“不安” | 对下一步附近隐藏陷阱提高警惕，主动搜或探查其可能覆盖区 | 不能转译为“附近必有密室”；没警告不排除隐藏门 |
| 公开地图出现疑似墙后空腔、被截断的走廊、可见迷宫连接、隔墙可见物件/敌人 | 将附近**已知且能覆盖的墙格**列为候选，优先让一次search覆盖多个未检查候选 | 可能是普通环路、外墙、特殊房、另一条远端入口，不保证门在眼前 |
| “这一区还没找到配额”“地图看起来不对称”“上局这里有房” | 最多作低置信线索；不能单独触发长距离扫墙 | 当前私有配额、种子、房型、隐藏布局全部未知 |

隐秘层消息在`GameScene`公开输出并记Notes；盗贼先见天赋1/2点的提示概率为75%/100%，用楼层seed固定结果，所以1点没提示不排除秘密房，重复进出同层也不重新抽提示。战士只有真实获得该跨职业天赋后才适用。来源：[GameScene提示与门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L737)、[Feeling.SECRETS公开日志/记事](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java#L792)。

## 5. 门、FOV、物品堆和CLI的知识边界

**门上有物品堆时不自动关门。** 原生`Door.leave`只有无Heap且角色数≤1才关；在门口投物引怪后，要重新看门的实际开闭。门开闭改变视线与普通弹道，但“英雄看不到敌人”“敌人看不到英雄”“投射线被挡”“能否偷袭”是不同问题。隐藏门在揭示前是墙的公开外观；开灯、examine或取full/src不自动揭露其隐藏身份。

来源：[Door.leave](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Door.java#L45)、[Level.discover才转换地形/揭陷阱](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1110)、[08具体弹道](08-ballistics-and-wands.md)。历史九挑战诡术师伏击门格有金币堆，成功不能解释成门自动关上；见[真实复盘](../2026-10-10-warrior-nine-challenges-retrospective.md#三诡术师阻射与伏击有效但没有设低收益尝试的终点)。

CLI允许利用原生玩家已经得到的知识：

- `map`返回完整已知层，`visible/visited/mapped`必须分别保存。未知格被省略，不补成墙、地板或房室。
- 投影把原生`SECRET_DOOR`变成公开`WALL`、`SECRET_TRAP`变成`EMPTY`；看见一个普通墙描述不能判断真假门。source视图不授权读取私有地图数组。
- 活角色依公开FOV/原生呈现；地图测绘本身不使整层敌人可见。地面堆需已seen且在已知格，箱子可只显示容器描述；不要因为地图空腔上没有`item`条目就说奖励不存在。
- 已揭陷阱才有公开trap实体。灵视/透视/哨卫扩展原生FOV后，按新完整观察使用新知识；它们不是从文件偷看实例状态。
- `search`是耗时的原生动作；查询state、查看已公开UI/单元格说明、读取源码、运行只读map/cells分析与搜索不是同一回事。要发search仍复制当前advertised操作和rev，一次执行后重新观察。

来源：[PlayerObservation.terrain与伪装地形](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L284)、[entities的FOV/seen/visible门槛](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L336)、[协议已知地图语义](../../docs/cli-help.md#L265)、[原生search动作](../../docs/cli-help.md#L696)。

## 6. 十二种奖励秘密房与额外秘密区域

以下房名以源码类名为精确索引，中文是用途说明。某格仅像这种房而没有充分公开特征时，先标“疑似”；不要套未发现奖励。食品/井的价值要与本局九挑战资源缺口比较，机关房则先算解法，不能只看宝箱数。

| 房型 | 生成的奖励/布局 | 九挑战下的用途、风险与取舍 |
| --- | --- | --- |
| Garden 秘密花园 | 高草、星花/种子荚/露珠草/额外一株，以及整室Foliage | 荒芜之地阻止四株植物生成，不能按普通局计算种子/露珠收益。Foliage提供Shadows遮蔽；可作条件性整理/休整点，但不能挨着敌人指望隐身，也不是饥荒免伤区 |
| Laboratory 秘密实验室 | 炼金台，两个各3–5点能量堆，共6–10；2–3种不同基础类别药剂，池中无力量 | 较高价值：当场把已知资源变成当前所需控制/净化/脱困。瓶子可能仍未知，含治疗瓶，不因这里是实验室就能安全试饮 |
| Library 秘密图书馆 | 内圈可达书架布局；2–3种不同基础类别卷轴，池中无升级卷 | 九挑战禁忌咒文不删除这些普通卷轴。**已有通路，不要为了到内圈烧书架**；附近地面普通卷轴可被火烧毁 |
| Larder 秘密粮仓 | 中央无味果株；额外食品总基础营养`150×(1+floor(depth/5))`，优先按450分成馅饼、其余熟肉 | 荒芜下无味果株消失，直接食品仍在。五区节食后合计50/100/150/200/250营养；是具体补给目标，但返程+接敌成本照算 |
| Well 秘密井 | 觉察井/治疗井各50%；井边3×3悬崖，入口到井有直线EMPTY走道 | **已有地板通路，不要求悬浮**。治疗井能直接解饥饿/治疗，价值高；先选直接使用还是投物，踩井即可消费。无效投井物品可被弹进旁边悬崖 |
| Runestone 秘密符石室 | 近侧2随机符石；书架隔断另一侧固定附魔石；另往本层待生成物品加1液焰瓶 | 附魔能改善少卷轴构筑，但要处理隔断。先取近侧可安全拿的符石，再考虑火/爆破/其他通路工具；补的液焰不保证在门口或已鉴定 |
| Artillery 秘密军械室 | DoubleBomb一组（拾取成两炸弹）+两件随机投掷武器 | 保留远程资源、处理路障/陷阱或安全起连；不是保证高阶/已升级投武。炸弹要区分库存和可安全投掷空间，不在补给堆旁试炸 |
| ChestChasm 悬崖宝箱室 | 悬崖中的4个锁箱落点，四角按实际箱数放本层金钥匙；全层额外生成1浮空药剂 | 房尺寸设计注释要求两瓶浮空完成，但只补1瓶。不得看见一瓶便认定可全取；把拿钥匙、开箱、拾取、返程分别计时，可部分取物或直接跳过 |
| Honeypot 秘密蜜罐室 | 靠门碎蜜罐与同格一只满血深度缩放蜜蜂；另有完整蜜罐1与随机炸弹1 | 蜜蜂/完整蜜罐可作引怪或战斗资源，不等于食物。门口蜂能立刻限制通路；不能因为“秘密房通常没普通怪”忽视专属蜂。蜜药等衍生物按恐药分支另审 |
| Hoard 秘密金库陷阱室 | 总金币约8个普通金堆量，分在半数内部格；同种明陷阱：50%落石，否则前期毒镖、depth≥10解离；各EMPTY格50%放陷阱 | 金币与陷阱可同格，捡钱不是安全路径。落石范围可能整室，解离能毁物；没有可靠触发/解除/回收路线时可只取边缘安全钱或不取 |
| Maze 秘密迷宫 | 14–18边长，奖品放入口可达路径最远处；武器/护甲各半，按高一区tier概率生成，去诅咒/诅咒附魔，额外1/3概率upgrade，普通箱 | 可能解决装备缺口，但“更高一区概率”不是保证T5或力量够。路远且可能有背景陷阱，先探路和预算；当前已有合格装备时不必付完整迷宫成本 |
| Summoning 秘密召唤室 | 内部3×3至6×6全铺召唤陷阱，通常隐藏；中心随机物品骸骨，诅咒物可能使骸骨闹鬼 | 极高连锁风险：召怪落在其他陷阱上会继续触发，且会生成冠军。不要把从门外扔一物解释为只召1只；无明确控场、清场、撤退方案时跳过 |

房型逐项来源：[Garden.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretGardenRoom.java#L35)、[Laboratory池/paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLaboratoryRoom.java#L54)、[Library池/paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLibraryRoom.java#L60)、[Larder.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLarderRoom.java#L48)、[Well.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretWellRoom.java#L44)、[Runestone.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretRunestoneRoom.java#L35)、[Artillery.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretArtilleryRoom.java#L33)、[ChestChasm.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretChestChasmRoom.java#L36)、[Honeypot.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretHoneypotRoom.java#L37)、[Hoard.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretHoardRoom.java#L41)、[Maze.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretMazeRoom.java#L62)、[Summoning.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretSummoningRoom.java#L48)。

### 6.1 补给房的精确例外

**花园没有绕过荒芜。** `Level.plant`在NO_HERBALISM直接返回null；Foliage的Shadows仍存在，但只在没有相邻异阵营角色等条件下发挥遮蔽。未饥荒时Shadows使饥饿积累速率为普通的2/3；已饥荒进入另一段伤害逻辑，没有这项减缓。它可提高一次已有明确终点的休整效率，不能支持无限等待。来源：[Level.plant](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1068)、[Foliage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Foliage.java#L86)、[Shadows](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Shadows.java#L58)、[Hunger两个分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L78)。

**粮仓的直接食物组合**依次为：下水道1熟肉；监狱2熟肉；洞穴1馅饼；都城1馅饼+1熟肉；大厅1馅饼+2熟肉。食物本身仍走战士进食天赋，因此多个食物单件的价值还含各自满足条件的丰盛回血/钢胃窗口，不只看总营养。但别为了每次卡丰盛阈值主动挨打。来源：[SecretLarderRoom预算循环](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLarderRoom.java#L60)、[Food节食/天赋](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L114)、[02战士进食机制](02-warrior-build.md)。

**秘密实验室/图书馆提供来源约束，不能提供安全承诺。** 若公开证据能确认物品确来自该房固定掉落，实验室没有力量药，图书馆没有升级卷；同房抽中基础类型后将权重置0，因此基础类别不重复。异晶可能把物品转换成对应合剂/秘卷，所以按当前原生物品显示及已知生成来源解释。单凭“不是力量”不能安全试喝，死亡复盘已经展示这种误判的代价。NO_SCROLLS减少的是保底升级卷路径，不等于没普通卷可用；NO_HEALING也不把实验室治疗瓶从池里删除。来源：[实验室权重/不重复](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLaboratoryRoom.java#L54)、[图书馆权重/不重复](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLibraryRoom.java#L60)、[Level保底升级卷挑战分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L232)。

**井是一次机会，要先决定用途。** 治疗井对活英雄执行治疗药的cure、装备祛邪、清450饥饿，并直接满血；鲜血之瓶饰物可把这份回血延迟。这里没有把治疗药饮用的恐药毒分支套进来，也不把饱腹除三。它也可选择给水袋装满、祝福未祝福十字架或给合格物品祛邪；同一次井不能既被英雄踩完又再填水袋。觉察井观察装备、揭全层秘密并给2时间Awareness（物品堆感知），但本身没有把全层mapped置true，故“揭所有门”不等于立刻拿到全层已知地形；投未鉴定物品则鉴定该物。无效物品随机弹到8邻可通行/可避让格，悬崖符合avoid，可能掉到下一层；别随便往这口井扔关键装备试效果。来源：[WaterOfHealth](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java#L51)、[WaterOfAwareness](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfAwareness.java#L45)、[Awareness时长](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Awareness.java#L33)、[WellWater消耗/弹物](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WellWater.java#L56)。

觉察井的`belongings.observe()`通常鉴定已装备物，背包装备/法杖只置诅咒已知；遗忘碎片还会改变被动鉴定结果。它不是“全背包都完全鉴定”，药剂/卷轴身份和未鉴定等级要看操作后实际公开输出。来源：[Belongings.observe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Belongings.java#L338)。

### 6.2 “没有专属机关”不等于安全地板

背景随机陷阱会收集各room允许的`Terrain.EMPTY`格，默认`Room.canPlaceTrap`允许；因此秘密井走道、符石室入口侧、蜜罐室和迷宫等仍可能出现本层普通随机陷阱。金库房显式禁额外背景陷阱，但已经有自身机关；鼠王房显式禁背景陷阱。普通初始怪/再生点主要选StandardRoom，也不保证开门后游荡怪不进来。所有房仍按当前公开trap/敌人/FOV逐步检查。

来源：[RegularPainter.paintTraps](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/painters/RegularPainter.java#L429)、[Room.canPlaceTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/Room.java#L337)、[RegularLevel.createMobs](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L224)、[Hoard禁止背景trap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretHoardRoom.java#L79)。

符石室用火/炸弹处理隔断前，先保护补给。书架可燃；普通地面卷轴会被火烧毁，炸弹还会对爆区Heap调用explode并摧毁普通符石，附魔石有unique保护。可以先取走近侧两枚，再从已核验位置开隔断；不要把这个方案套图书馆，那里本有通路。来源：[Terrain书架](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Terrain.java#L101)、[Heap.burn](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L217)、[Bomb爆区](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bombs/Bomb.java#L158)、[Heap.explode](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L286)、[StoneOfEnchantment.unique](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfEnchantment.java#L43)。

召唤室尤其不能“先扔垃圾看看”：一枚陷阱召1–3只，选择邻近可站格，调用本层createMob；新怪出现的格若还有active陷阱，会手动激活继续连锁。全屋陷阱布置把局部触发变成复杂群怪问题，冠军和集群智能进一步恶化。中心只有随机物，不能保证收益覆盖控场/血量成本。可靠的飞行/绕陷阱或一次性处理方案也须按当前资源和结束前落点核算。来源：[SummoningTrap生成与递归触发](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/SummoningTrap.java#L45)、[SecretSummoningRoom中心奖励](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretSummoningRoom.java#L48)。

### 6.3 危险房的可操作解法门槛

**金库不能把所有陷阱当成脚下单格伤害。** 落石在RegularLevel房间中影响所属房间全部非solid格，基础伤害`5+d…10+2d`减drRoll，并可能麻痹10；门槛可能仍属于范围。毒镖优先脚下角色，否则寻找最近的PROJECTILE可达角色，射程至少6.5，基础物伤4…8附毒`8+round(2d/3)`；黑暗视距2不让陷阱缩成2格。解离同样有索敌线，伤害`30…50+d`不走普通drRoll，并先破坏陷阱本格物堆。把敌人引到可用陷阱线、从真正范围外触发是**条件性战术**：要同时算英雄、掉落、被毁资源和接下来的逃路，不先花HP“试一下”。浮空能规避脚踏触发，但其他角色/物品触发仍可能伤到英雄。

来源：[RockfallTrap范围/伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/RockfallTrap.java#L58)、[PoisonDartTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/PoisonDartTrap.java#L73)、[DisintegrationTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/DisintegrationTrap.java#L53)、[Paralysis时长](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Paralysis.java#L34)。

**深渊四箱的计时不能只数走路。** 普通浮空20时间，饮药1；状态结束会立即重新occupyCell，若站悬崖便坠落。全取四箱通常至少4次拿钥匙+4次开箱+4次拿物=12时间，再加移动、饮药和返回。可以把路径分成“安全岛→取一组→安全岛补状态→退出”，或只拿能安全取得的一两箱。物品栏里的浮空瓶还要确认身份、当前剩余时间与可饮时机；已知位移工具若能替代也须按原生目标约束计划。念力能取钥匙/开箱后普通堆，不能直接抽出锁箱奖励。正常坠落有伤害/出血/残废，不能当免费返程。

来源：[Levitation20与detach](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Levitation.java#L40)、[PotionOfLevitation](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfLevitation.java#L58)、[Hero开箱耗时](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1195)、[Hero完成解锁消钥匙](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2457)、[Key拾取](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/keys/Key.java#L54)、[Item拾取](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java#L704)、[TelekineticGrab目标](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/TelekineticGrab.java#L102)、[Chasm坠落](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Chasm.java#L131)。

**蜜罐房可借蜂打怪，但先控制绑定。** 房内蜂HT=`4(d+2)`、攻防技能`9+d`、伤害`HT/10…HT/4`，飞行、经验0。蜂有罐位置时偏向守罐周围目标；拾起破罐会把英雄设成持罐人并追击，放下/投出改守新位置，毁罐不令蜂消失。完整罐投到非悬崖可召蜂，落点被占用时尝试四邻，找不到空位则保持罐完整。利用门口和怪物接近罐的路线可让蜂承担战斗，但不要在无出口房间里先捡破罐。蜂蜜治疗秘药投蜂可以治疗、转盟友并解除守罐绑定；对英雄饮用仍经过恐药的治疗药分支，不能因附带饱腹就当安全食物。

来源：[Bee基础数值](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Bee.java#L89)、[Bee守罐/选敌](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Bee.java#L145)、[Honeypot召蜂](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Honeypot.java#L88)、[破罐持有/投放](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Honeypot.java#L162)、[蜂蜜治疗秘药](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfHoneyedHealing.java#L45)。

**召唤房的中心骸骨还有第二层风险。** 单陷阱召1/2/3只概率50%/25%/25%，不代表整屋总数；`Level.createMob`照常抽冠军。中心诅咒物让骸骨haunted，打开会尝试生成怨灵，不能生成时会伤英雄；它不是普遍“四怨灵坟墓”。search/探地只揭陷阱，不解除。炸弹落地会先压格，因而“炸开骨堆绕过打开”也不能自动规避召唤连锁。判定没有可靠收益方案就留在门外，不能用可能得到经验为大量精英风险背书。

来源：[Level.createMob冠军](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L536)、[Heap.open骸骨](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L86)、[Heap.explode](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L269)、[Level.drop压格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1044)。

**迷宫奖励值得计划，不值得预支。** 按下一大区tier概率抽装备，所以第1区秘密迷宫T2/T3/T4/T5概率25/50/20/5%，第2区0/40/50/10%，第3区0/20/40/40%，第4–5区0/0/20/80%。这是源码生成分布，不是对当前未开箱的身份推断。去诅咒只保证该专属来源物的诅咒状态，`cursedKnown=true`不等于等级鉴定。先比较已有武器/力量/防御缺口：缺装备时可投入探图和明示符石以减少迷路；装备已够而营养/光照紧时，最远处一件随机物不是必做。

来源：[Generator阶数表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L613)、[Generator武器/护甲阶数选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L780)、[SecretMaze奖品处理](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretMazeRoom.java#L98)。

### 6.4 不在12种轮转池中的两类

**第5层鼠王房**由SewerBossLevel额外加入；不直接连接Boss入口房，固定外7×7/内5×5，鼠王在中心，雕塑避开入口附近，剩余可用非中心地格每格5–20金币。本版是地面金币与雕塑布局，**不是旧版一圈宝箱**；禁止水/草/背景陷阱。搜索可增加第6层购物资金，但先完成Boss安全状态，再衡量搜门和绕房时间。拿到王冠后把它交鼠王会涉及职业护甲能力选择，不为找钱就默认需要大范围返回；机制见[战士研究](02-warrior-build.md)。来源：[SewerBossLevel.initRooms](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/SewerBossLevel.java#L85)、[RatKingRoom限制与paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/RatKingRoom.java#L44)。

**矿区两个MineSecretRoom**由MiningLevel单独添加，不消费主线区域SecretRoom配额。水晶任务房内是矿水晶，豺狼任务房有一箱4–5暗金；其余实现按任务类型填地形并放4–5个暗金墙候选。它们服务矿区任务收益，应并入采矿路线/任务完成度预算，不能为主线“缺的秘密房”回头凑数。仍以本局实际任务类型、已知暗金位置、挖掘/敌人风险判断。来源：[MiningLevel两个秘密房](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/MiningLevel.java#L115)、[MineSecretRoom.paint](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MineSecretRoom.java#L42)。

## 7. 有限搜索与放弃策略

### 7.1 每次开始搜索前记录一张小单

```text
当前公开帧身份/层/英雄格：……
具体目标：找通往出口的路 / 已有可靠奖励线索 / 排除下一步陷阱
证据：公开提示、已测绘通路、实际护符警告、具体墙形
这次search真正覆盖的候选格：……；之前已检查的格：……
到位+搜索+预期取物+撤出预算：……时间；营养/光照/HP和工具余量：……
停止条件：覆盖本组后无新线索 / 新敌人或受伤 / 余量不足 / 已得到目标
```

预算是有限而可重估的，不把“还有可能”当续搜理由。记录格子基于当前已知地图；不恢复游戏私有`secret`位图。手动搜索已确定覆盖的普通墙不在下一轮候选重复出现，除非位置/视野/地形真的变化。

### 7.2 怎样选一个有价值的站位

先顺路经过房边，让原生被动搜索附带发生；不要为抽低概率绕同一墙反复走。再把确实未主动检查、当前有理由怀疑的已知墙/可搜格组成集合U。对安全可达候选站位p，按**战士半径1与该站位实际可见条件**估计可覆盖U的哪些格；优先一个位置覆盖多个候选，减少搬位。未走到p前不能保证新FOV，抵达后重核。优先走已经为推进/拾取而需要走的路径，把搜索当附加任务。

若需要查的是大面积未知布局，比较一张地图/一枚明示符石/已充能护符/棱光的成本和覆盖；它们可能比十几次搜索便宜，但也占用应急或未来关键层资源。选定探图动作后先读结果再路线规划，不预发一串搜索/移动。

### 7.3 这些情形明确不继续搜

1. 普通格已被一次有效主动搜覆盖，没有新增证据；不原地重复。
2. 主线出口/关键资源路线已经明确、当前缺粮缺光，而只剩“配额应当还有”的推测；离开该层，允许漏掉可选秘密房。
3. 已饥荒、HP低、没有确定恢复来源；继续扫墙只会消耗生命窗口。秘密食品房不是承诺，不能用未知奖励支付确定成本。
4. 存在未隔离的远程敌人、毒/火/气体或密集追兵；搜索2时间可能让威胁多次结算，先脱困或推进。
5. 发现房间但缺必要工具或安全回程。记公开位置后暂缓；不能为了挽回已花搜索成本再付致命取物成本。
6. 准备首领所需食物/火把/控制品会被这条支线吃掉；已有装备足以尝试推进时，优先保留进入首领阶段的余量。
7. 只有日志表明本层“有秘密”，却没有缩小到有限候选，且没有高效探图工具；提示提高存在概率，不把全图扫墙变成必做任务。

**必须继续解决的是“没有已知可走的主线”这一具体问题，不是“探索率没到100%”。** 隐秘层可能真的把普通路线藏在门后，此时搜索有推进价值：先把当前可达区域的高信息门候选查完，必要时用探图，仍维持费用上限。跨层回头只有在已知奖励能填补具体缺口、路线安全且净收益有预算时才成立；不能因为曾看到治疗井就忽略往返耗粮、光照和新刷怪。

## 8. 结论的验证范围

本章逐项核查实际源码和公开观察投影；未运行新生成统计、真实搜索或房间取物实验。数量表是正常生成配额公式，费用表是明确条件下的基准，策略预算尚不是最优胜率结论。后续实战记录应写下“已有线索→实际搜哪些格→花多少→发现了什么→为何继续/放弃”，同时保留未发现的可能性；死亡后揭图或私有存档不能回填行动前知识。
