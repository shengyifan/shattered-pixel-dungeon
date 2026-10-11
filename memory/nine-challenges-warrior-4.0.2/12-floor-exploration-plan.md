# 12 — 九挑战战士的逐层探索与推进决策

核查：2026-10-11，工作树基线 `d04c8f862`，4.0.2 / code 922、CLI.9.0.3。`git diff 8405ae82f..d04c8f862 -- core/src/main/java` 为空，本章核查的游戏逻辑与原研究基线相同。本次只读源码、已有项目内复盘并作生成公式的纯计算；没有启动游戏、读取个人存档/私有审计或验证九挑战通关率。

目标是**提高完成正常九挑战通关的机会**，不追求每层全探索、全怪、满分或无必要的上行。下列规则分为源码保证、公式推导和战术判断；后者是可执行的条件决策，未经多局模拟/实战比较，不宣称数学最优或必胜。先读[当前决策卡](00-run-plan.md)和[第4层饥饿死亡复盘](../2026-10-10-warrior-nine-challenges-retrospective.md)，物品/敌人细节按相应章节查。

## 1. 从“清完再下楼”改为“取到能支持下一关的资源就评估离层”

上一局的公开结果是：第3层长时间等待、诱敌和休息耗掉营养与照明，进入第4层时6/35HP、无食物无照明；这不是“所有支线都不能做”的证明，而是**可选任务没有停止条件会把前期资源变成无出口的危局**。本章对每层只安排当前有收益的目的，不按探索百分比或等待到没粮才停止。

每次下楼或新威胁出现后，先回答五个问题：

1. **现在最缺什么？** 是力量不足导致武器/护甲拖慢、伤害不足、没有远程/控制，还是HP/营养/照明已不可支撑一次额外遭遇？同样一个支线奖励，在不同缺口下价值不同。
2. **已经知道什么能补？** 区分背包已拥有、看见但未取得、由生成规律得知本区域有额度、完全未知四种状态。只有前两者可用于具体路线预算。
3. **到它再到安全出口，需付出多少？** 算实际连通路、开门/拾取/解锁/战斗/撤回，不算直线距离；还要算敌人能否借这段时间包围。未知路段不能赋予零敌人、零陷阱或固定步数。
4. **若未得到预期效果，怎样停止？** 不同支线分别设置“最多探索到下一个分岔”“一轮诱敌后重评”“只取已见关键物”等终点。没有新事实不刷新已经耗掉的预算。
5. **此刻下楼，会把短缺转移成什么风险？** 离层可省绕路，但不能消除饥饿、DOT、未鉴定装备或下一层更强怪；未知下一层不是免费补给仓。生命已危急时比较当前所有可见选项，不能把“速降”说成必然安全。

探索率不是存活率。`RegularLevel.levelExplorePercent` 会把未开容器、未取钥匙、未打石像/宝箱怪、未解除魔法火等算作未探索；为了把这个数做满，可能恰好需要消耗九挑战最稀缺的资源。[探索评分逻辑](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L792)。

## 2. 固定供给的准确范围：生成额度不等于已拾到

### 主线正常层的共通额度

主线普通层为1–4、6–9、11–14、16–19、21–24；5/10/15/20/25为专用首领层，26为护符层。[Dungeon.newLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L314)。以下仅统计这些正常生成路径，不包括任务、商店、额外房间、财富之戒、饰物和随机敌人掉落。

| 资源 | 源码供给 | 对路线的意义与限制 |
| --- | --- | --- |
| 食物 | 每个主线普通层安排1份FOOD；LARGE感觉层再加1份。起始另有1份Food。FOOD默认种类权重为Food:Pasty:MysteryMeat=4:1:0 | 共20普通层各1份的基准是“安排生成”；并非每层正好一份口粮，也不保证当时可安全拿到。普通口粮九挑100饱腹，馅饼/节日变体须看实际效果；不要把所有食物都算100或把商店食物算免费。[Level.create](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L224)、[LARGE](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L276)、[FOOD池](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L537)、[初始食物](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L108) |
| 力量药 | 每区两瓶：区内第1/2普通层一瓶、第3/4普通层一瓶，各对的前层50%出现，否则后层出现 | 普通主线累计10瓶。只按“已鉴定并实际饮用”的瓶数计算可穿装备；不能用区域额度替代力量状态。[posNeeded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L542) |
| 升级卷 | 每区原安排3个槽，按全局槽序号删除偶数；九挑共8张，分区2/1/2/1/2 | 主线少量但可靠的成长来源，应优先处理当前构筑门槛；已经生成却没拿的卷不会因为未拾取在下层补发。[souNeeded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L558)、[NO_SCROLLS过滤](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L232) |
| 火把，1–19普通层 | 每普通层黑暗额外1支，LARGE再多1支 | 首领层不走这一保证。250时间/支是照明，不是可随意等250次；点火与回收本身也要安全。[RegularLevel.createItems](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L472) |
| 火把，21–24 | `HallsLevel.create()`先安排2支，再叠黑暗普通层1支，故**每层基准3支**，LARGE为4 | 大厅供给较多，不需要照搬“每层仅一支”的前期预算；但生成位置未知，眼前快熄灯时不能提前花未来三支。[HallsLevel.create](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/HallsLevel.java#L118) |
| 炼金房 | 各区第3或第4普通层：3/4、8/9、13/14、18/19、23/24二选一 | 不是进该区即有锅。普通炼金房带锁门与本层铁钥匙，室内5能量晶体及1–2奖品；进入和带原料至此是路线的一部分。[labRoomNeeded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L602)、[安排房间](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SpecialRoom.java#L131)、[LaboratoryRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/LaboratoryRoom.java#L65) |
| 刻笔 | 每区普通层中1支 | 不是保证某种刻印；只能在实际得到、当前护甲可用时比较收益。[asNeeded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L569) |
| 感知符石、魔能触媒 | 各有一份安排在1–3层 | 感知符石有助早期鉴药；触媒是获得饰物选择的入口，却还要炼金能量，不能让寻找/制作饰物挤掉眼前生存开销。[intStoneNeeded/trinketCataNeeded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L592) |
| 附魔石 | 6–9或11–14这8层中的一次基准安排 | 未见之前不是构筑承诺；找到后可减少把升级卷转换附魔的需求。[enchStoneNeeded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L579) |
| 一般随机物资 | 普通层另生成3/4/5个随机物品，权重60%/30%/10%；LARGE再+2 | 不保证恰有武器、控制杖或净化；也不等于所有都安全摆在地上，容器和宝箱怪有额外规则。[RegularLevel.createItems](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L376) |

**重要反例：保底物并不全在普通地板。** 房间生成先可调用`Level.findPrizeItem()`从`itemsToSpawn`取走保底物，作为本房奖励。炼金房优先取触媒、其次力量药；图书馆可取Scroll，包括安排的升级卷；储藏室、食人鱼池、陷阱房、魔法火房、哨卫房等可取一般奖品。因此“已走遍普通大厅，缺的力量/升级一定不在本层”不成立，“特殊房全不做也能拿足保底”也不成立。反过来，仅见到一个尚未开的房间不能断言里头就是缺少的保底。[Level.findPrizeItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L827)、[炼金房奖品](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/LaboratoryRoom.java#L125)、[图书馆奖品](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/LibraryRoom.java#L70)、[池房](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PoolRoom.java#L108)。

### 九挑战升级卷还可缩小到哪些层？

这是**源码公式推导，不是读取隐藏生成结果**。假设正常按序生成主线20个普通层、各区原3槽正常推进，`souNeeded`使区内四层有三层安排原槽；四种“缺哪一层”的模式各1/4。再按全局槽编号删偶数，得到：

| 区域 | 九挑保留层的可能集合 | 每层边际概率，仅统计原生主线槽 |
| --- | --- | --- |
| 下水道1–4 | `{2,4}` 1/4；`{1,4}` 1/2；`{1,3}` 1/4 | 1/2/3/4层：3/4、1/4、1/4、3/4 |
| 监狱6–9 | `{7}` 1/2；`{8}` 1/2 | **6和9的该类槽不保留**；7/8各1/2 |
| 洞穴11–14 | `{12,14}` 1/4；`{11,14}` 1/2；`{11,13}` 1/4 | 11/12/13/14层：3/4、1/4、1/4、3/4 |
| 都市16–19 | `{17}` 1/2；`{18}` 1/2 | **16和19的该类槽不保留**；17/18各1/2 |
| 大厅21–24 | `{22,24}` 1/4；`{21,24}` 1/2；`{21,23}` 1/4 | 21/22/23/24层：3/4、1/4、1/4、3/4 |

可复算证据：[纯计算脚本](evidence/exploration/upgrade_slot_distribution.py)、[本次结果JSON](evidence/exploration/upgrade_slot_distribution.json)。在仓库根运行`python3 memory/nine-challenges-warrior-4.0.2/evidence/exploration/upgrade_slot_distribution.py`即可重算；脚本只读两份源码文本作hash，不加载游戏。

本次用`fractions.Fraction`枚举每区四次`P(产生原槽)=剩余槽/(5−区内层号)`，按奇数全局槽保留，得到上述精确分数。未运行地图生成器，也未使用本局seed、存档计数或实测地图。**这些概率不能让某个未知符文自动变成升级、不能排除特殊额外来源、不能用“没找到”当“没有生成”。** 若某个非标准事件改变生成顺序/计数，重新核适用条件。

因此，因“缺本区唯一升级”而在6或9层无限搜墙没有此主线槽依据；应核7/8层已见物资与未完成房间。相反，9层仍可能有力量药、炼金、任务、食物和火把，不能仅因没有该升级槽便判整层无价值。

### 区域结束的成长参照，而非过关硬门槛

| 到首领前 | 若本区及此前所有基准力量药都已喝，裸基础STR参照 | 主线升级卷累计生成量 | 解释 |
| --- | ---: | ---: | --- |
| 5层前 | 12 | 2 | 不含戒指、天赋、临时减益、其它力量来源；不是要求特定两张必须投哪里 |
| 10层前 | 14 | 3 | 法杖匠奖励的自带等级另算 |
| 15层前 | 16 | 5 | 铁匠独立服务另算，有适用条件 |
| 20层前 | 18 | 6 | 不假定宝库奖励/财富掉落必得 |
| 25层前 | 20 | 8 | 若已成型，不为仅凑这些参照冒致死回收风险 |

起始STR10见[Hero](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L207)，力量药+1见[PotionOfStrength](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfStrength.java#L43)。这里的意义是帮助发现“力量仍10却准备进高伤怪层，前面两瓶尚未转成实际战力”这一类缺口，不是规定一个武器/等级/生命的唯一正确阈值。

## 3. 三种探索模式与切换条件

| 模式 | 何时选择 | 执行边界 |
| --- | --- | --- |
| 关键资源探索 | 仍有明确缺口，当前层有已见高价值资源/必要房间/可信的未取额度，粮光与退路允许 | 以最近有价值分岔或具体物为一段；顺路取安全小物，不每块墙都搜、不追已逃离的非必要敌人；新战斗可能改变资源后重排 |
| 轻探索并找出口 | 主输出和下一关控制已有，额外收益不确定，或者粮/光仅够一段合理通路 | 沿已知连通结构扩展找出口，略过低价值锁房/重复金币/危险石像；遇到可安全拿的力量、升级、食物、火把仍可取，不能机械不捡 |
| 直接离层或撤回已知补给 | 多项余量不足、当前支线没有可落地收益，且已有实际可达出口/补给路线 | 先比较进下层与回已知安全资源谁更可行；不要求把当前地图补齐。进入未知下层后立即重新观察，不把下一层入口附近“通常较安全”当无敌保证 |

首层值得更认真收集已有低风险资源：普通生成初始安排8只怪，拿护符前`mobLimit()`为0，所以不会像后续普通层那样持续补充普通怪。它仍有冠军/蛇等风险，也有饥饿，不能由“无普通刷新”得出可无限休息。后续普通层初始怪数范围随区内深度增长；LARGE增加房间、初始怪和物资，但不自动多给与地图面积同比的营养。[RegularLevel.mobLimit/createMobs](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L205)。

普通新层初始怪生成避开入口8格FOV/可通路附近，属于**创建时放置约束**；特殊房敌人、后来移动、刷新、跨层返回都不能套成同样安全圈。[初始放置检查](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L235)。普通刷新在低于上限且有合法落点时尝试，基础间隔50；DARK感觉层约33.33，日晷等另改倍率。失败会1回合后重试。无需也不应推断本局隐藏倒计时；结论只是“清过的后路久后不能当永久空无敌人”。[MobSpawner.act](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/MobSpawner.java#L38)、[Level.respawnCooldown](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L752)。

### 搜索与回收的真实预算

战士普通搜索半径1，主动搜索对可搜索秘密门/陷阱在符合当前FOV的检查范围内成功；有`canBeSearched=false`的机关不能靠普通搜索解决。主动一次耗2世界时间并额外加4饥饿，通常总营养成本约6，盐晶/饥饿暂停/诅咒护符等另算。**十次无目标搜索≈20照明时间与60营养，已超过半份挑战口粮。** 搜索应针对已见可疑缺口、当前必要通路或一个具体未解决危险，不能常规沿全层每块墙重复搜索。[Hero.search](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2490)、[耗时与饥饿](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2609)。

默认秘密房按区域安排约2、2.25、2.5、2.75、3的数量（小数是多一间的概率），再分配到该区普通层；1层不生成这组秘密房，SECRETS感觉另加一间。这是创建规则，不是允许读取本局剩余秘密房数；**不因为“本区通常两间”就一定搜齐两间**。暗门可能也服务其它房型；1层无这组秘密房不等于可以无视一切隐藏通路/陷阱。[SecretRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretRoom.java#L45)、[SECRETS追加](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L158)。

延期回收只登记已经公开发现的目标：层/支路、物品或房间、未取得原因、需要何种工具、从楼梯往返路线、什么时候重评。新拿到冰杖可使旧魔法火房变便宜，新力量解除超重可使旧装备有价值；仅仅已经在任务上花过很多回合不是回去的理由。回收前合并同层多个高价值目标，计入重复上下楼和重新遭遇，不为了卖一件廉价物单独往返两区。

## 4. 1–26层逐层目标表

记号：`P前/后`表示该区一对力量药的前/后层；若前层确知已取并饮用，后层不再为该同一瓶搜索；若只是未见，不据此确定落点。`U`仅指上一节的主线升级槽。`F/T`是普通非LARGE层基准食物份数/火把支数，均是生成量，不含随机/商店。表内行动为战术判断。

### 下水道：尽早把低风险资源换成对螃蟹与Goo的实际能力

| 层 | 基准供给/节点 | 优先目标与可以停止的边界 | 可放弃什么／何时重新评估回收 |
| --- | --- | --- | --- |
| 1 | P第一对前层；U可能；F1/T1；1–3感知符石/触媒之一；无普通补怪 | 取实际可达的食物、火把、力量/升级与投掷；对蛇建立偷袭，避免冠军硬换血。以走遍已知低风险主连通房、找到出口与关键物为目标；不因无补怪无限等盾或搜墙。刚有一级经验可分天赋时先兑现，再决定额外接敌。 | 可放弃无证据的搜墙和触媒投资；已见安全食物/火把或能立即解除装备门槛的药卷，离层前优先回收。 |
| 2 | P第一对后层；U可能；F1/T1；幽灵可能为臭鼠 | 若入口无路，先检查Searching指南页提示与入口邻墙；新profile即使跳教学也可能需搜入口暗门（见下文）。若力量仍10，优先核第一对药的公开来源、未知颜色及已见受阻房；别把瓶一直留到危局才猜。臭鼠只在有离臭气的线路、远程或可承受的短战条件下做；水不能解臭气麻痹。路线中取关键物后可离层，不等同清光所有怪。 | 可放弃高耗臭鼠、金币拐路；若下一层武器超重且本层/1层有已见力量或升级解法，先重评回收，不能只继续饿等。 |
| 3 | P第二对前层；U可能；F1/T1；炼金房可能；幽灵可能为诡术师 | 螃蟹开始出现，移动速度与攻击范围需核。优先打通粮光→炼金/关键物→出口的路线。诡术师没有可达输出端点时暂缓；初次3–5次单wait只是可调试探预算，累计成本不能清零续等。上一局在此耗尽余量，不再把任务完成当下楼前提。 | 可放弃没输出终点的诡术师；粮光不足时优先已见补给/出口，缺Goo关键工具而已有安全锅/原料时先兑现。 |
| 4 | P第二对后层；U可能且本区保留总数2；F1/T1；若3层无锅则在此；幽灵最迟巨蟹 | 作为Goo前最后常规整备层：核力量、可用装备、电量、洗污泥及蓄力避险手段，优先补能改变首领可行性的缺口。满足实际作战条件后停止金币/隐房/稀有怪支线。若缺口来自已见旧层资源，比较回收与继续风险；不能由“4层必有资源额度”顶着巨型怪去捡。 | 可放弃稀有怪、隐房、低值金币；Goo躲蓄力/输出方案不成立时必须回查已知可改善物，不能仅因出口近便开战。 |
| 5 | Goo，120HP；无普通F/T保证；锁出口需本层WornKey | 开战前布置干地/水边与硬墙绕行，短蓄力必须留安全动作；普通换血损耗与半血后强化分开预算。击败→拿钥匙→解锁→进入6层补给，非必要不滞留；锁层恢复有20初始/50上限预算，不能无限等满。 | 可放弃非必要战后逗留；钥匙和解锁是正常出口门槛，先确认取得，若补给在旧层且Boss未激活可事前回收。 |

**第2层新profile推进例外：** 若Searching指南页尚未找到，生成器会把入口房原UNLOCKED门强制隐藏，同时入口投放该指南页。这个条件独立于intro；桌面新档跳教学不会把指南页变成已找到。看见对应原生提示且无可走主线时，应在入口做有目标的搜索，不把它误作可选奖励密室。依据：[RegularPainter入口隐藏](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/painters/RegularPainter.java#L268)、[EntranceRoom指南页](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/standard/entrance/EntranceRoom.java#L120)、[Document初始状态](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/journal/Document.java#L270)、[桌面新档默认](../../desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopProfileDefaults.java#L33)。

敌人和首领细节见[03](03-enemies-sewers-prison.md)。Goo钥匙与锁出口见[Goo.die](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java#L283)、[SewerBossExitRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/sewerboss/SewerBossExitRoom.java#L57)。

### 监狱：补上远程/控制，避免把生命交给骷髅末击和DM-100射线

| 层 | 基准供给/节点 | 优先目标与可以停止的边界 | 可放弃什么／何时重新评估回收 |
| --- | --- | --- | --- |
| 6 | 普通商店；P第一对前层；U本层该槽不保留；F1/T1 | 商店先列背包容量、可卖旧装、祛邪/识别、营养、脱险工具与金币留额，不机械买治疗或不合力量的甲。骷髅尽量远处收尾。已有下区可用输出时轻探索；不要因找“本区唯一升级”在6层无限搜索，它在7/8的主线槽中。 | 可放弃为了主线升级槽进行的搜墙；若已见祛邪/可用武器能解除当前咒装/输出危局，采购或回收后再深入。 |
| 7 | P第一对后层；U可能为本区唯一；F1/T1；法杖匠可能 | 取到/识别能用的主线升级与力量，留好法杖匠路线。狱卒链拉和DM-100远程线使看得见的物资不一定能直取；断线拉近、单入口接敌优先于硬冲。任务不在此也不等待它出现。 | 可放弃不改善战力的石像/金币；缺本区升级且已见候选/受阻奖品时优先解决，危险过高则记录而非硬捡。 |
| 8 | P第二对前层；U可能为本区唯一；F1/T1；炼金可能；法杖匠可能 | 法杖匠提高优先级，按尸尘/新生元素/腐莓的具体解决条件做，不能统一正面莽。法师骨兵会治疗且死亡爆炸，本体末击也先核骨兵位置。炼金尽量一趟完成识别/净化/控制/首领准备，避免来回。 | 可放弃无限打召唤骨兵、无用途隐房；缺有效远程且任务已可安全完成时，进Tengu前重评兑现杖奖励。 |
| 9 | P第二对后层；U该主线槽不保留；F1/T1；若8无锅则此；法杖匠最迟 | Tengu前补足实际能跨两个阶段的远程、机动、异常对策；尚无战斗法杖时优先比较已知任务收益。满足首领计划就停止可选清图；没找到升级先核7/8旧记录，而不是机械重搜9层每块墙。 | 可放弃全层清怪与找不存在的本层主线升级；若Tengu输出/避险储备不够且有已见锅、任务或旧层药卷解法，应先回收。 |
| 10 | Tengu，250HP；两阶段；无普通F/T保证 | 预留照明与足够输出/躲危险的动作资源；第一阶段记当前陷阱图，半血沿过场路线推进，第二阶段联判炸弹/火/电。浮空不防第二阶段技能。胜后拾取并使用真实掉落的面具选择子职业，再进入11层整备；不以旧帧判断新阶段。 | 可放弃过场原地刷恢复；面具为子职业关键奖励，胜后确认拾取/使用，未补两阶段消耗前不应从9层仓促开战。 |

普通商店来源见[Dungeon.shopOnLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L445)。法杖匠7–9层与额外升级奖励见[Wandmaker.Quest](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Wandmaker.java#L333)。

### 洞穴：铁匠是可分档完成的投资，不必为了满好感搏命

| 层 | 基准供给/节点 | 优先目标与可以停止的边界 | 可放弃什么／何时重新评估回收 |
| --- | --- | --- | --- |
| 11 | 商店；P第一对前层；U可能；F1/T1 | 进入后核子职业真正已选择、天赋点/快捷工具已配置。商店购买针对DM-300跑塔和洞穴远程敌人的缺口；不预设本层有铁匠。关键物就位可轻探索，别留着已能解决当前弱点的升级等假想T5。 | 可放弃没有明确收益的补刷经验；当前弱点能由本店已售工具弥补时先比较采购，不能把12层未知铁匠当救急。 |
| 12 | P第一对后层；U可能；F1/T1；铁匠可能 | 先查看铁匠实际服务和已有可重铸同类装备，再决定是否下矿。矿区有自身危险、食物和火把安排，不能把它当主线层顺手多开一个房；若主线资源吃紧而无明确服务目标，暂缓入口。 | 可放弃全矿/支线Boss目标；已有适用装备且低档服务可改变战力时，按确定好感目标回收矿/结算。 |
| 13 | P第二对前层；U可能；F1/T1；炼金可能；铁匠可能 | 合并主线药卷回收、炼金与铁匠计划。采矿只做到当前目标好感也可；不是“未打支线Boss就不能离开”。每个巫师射线/蜘蛛毒路都要按实际退路处理，生命持续下滑便缩小支线范围。 | 可放弃超出本次服务目标的矿石与岔路；缺跑塔/气体工具但已见原料和锅时，离区前集中回收制作。 |
| 14 | P第二对后层；U可能且本区总2；F1/T1；锅/铁匠最迟 | DM-300前三段跑塔准备：已知伤害手段、移动能力、电/毒/落石应对；如铁匠能用低风险好感解决即时门槛，结算后推进。别为了2000/3000全好感再花一层的饥饿与风险，也别把它的升级菜单当可给任意高等级装备加级。 | 可放弃为了满好感硬打支线Boss；DM三次跑塔方案不足时回查已知补给/铁匠服务，无法安全补齐不伪称已准备。 |
| 15 | DM-300，400HP、三次过载；无普通F/T保证 | 先识别公开场地与稳定电危险，按照三次跑塔而非两次分配机动/控制。每次塔死亡、Boss状态改变后重作局部路线。胜后收实际掉落（包括金属碎片的炼金用途），向16层商店整备。 | 可放弃战后原地等到全满；拾实际关键掉落再去16店，别把金属碎片等已能合成脱险的资源遗漏。 |

铁匠出现在12–14，当前新局随机晶体/豺狼支线，见[Blacksmith.Quest.spawn](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Blacksmith.java#L347)。带镐可提前离矿并结算，矿石不足/未杀Boss是提示，见[MiningLevel.activateTransition](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/MiningLevel.java#L239)。每矿50好感，上限2000，支线Boss另1000；首次重铸500、首次普通升级1000且只能升已鉴定无咒、等级<2的装备，不能给现有+6主武器直接再升。[好感](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Blacksmith.java#L443)、[服务限制](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndBlacksmith.java#L113)。因此“20矿→1000好感→一次适用升级”或“10矿→500→一次合适重铸”是可比较的任务档位，仍不保证这批矿可无代价取得。详情见[04b铁匠](04b-blacksmith.md)。

### 矮人都市：先确认最后商店资格，不能等21层才补采购

| 层 | 基准供给/节点 | 优先目标与可以停止的边界 | 可放弃什么／何时重新评估回收 |
| --- | --- | --- | --- |
| 16 | 最后一家无任务前提的普通商店；P第一对前层；U该主线槽不保留；F1/T1 | 此处集中处理后续群怪/术士、城王、Yog准备中的确定缺口。不要把“以后21有店”当储备，当前新局最后店有任务门槛且位于20。神器/戒指若已成型，优先让现有组合工作，不为追第二套未见构筑消耗粮光。 | 可放弃找本层主线升级；末段无保证店，已知净化/祛邪/控制缺口有本店解法时优先处理，不能期待21层补买。 |
| 17 | P第一对后层；U可能为本区唯一；F1/T1；小恶魔宝库任务可能 | 先找实际药卷/出口，读宝库原生选项和入场规则后再定目标。新任务不是刷和尚/魔像代币。只为通关时宝库可选，明确此次是取一件物、争最后商店资格或使用一次原生退出恢复，不能无差别满探索。 | 可放弃宝库满分与低值支线；缺本区升级或明确宝库回报可解决眼前缺口时，先比较安全投入再决定。 |
| 18 | P第二对前层；U可能为本区唯一；F1/T1；炼金可能；宝库可能 | 以城王能用的范围控制、术士断线、召唤物站位和最终装备为目标。宝库与主线任务不要同时开过多未完成回收链；先保留一次能完成的往返。升级缺口只在17/18主线额度中追踪，不查私有计数。 | 可放弃平行多条未完成回收链；已见锅/唯一升级可支撑城王方案时优先兑现，成型后缩减其它探索。 |
| 19 | P第二对后层；U该主线槽不保留；F1/T1；锅/宝库最迟 | 城王前最后常规整备。若决定不做宝库或达不到店资格，直接按“20后无店”预算；这是可接受的取舍，不应为弥补已投入而硬打支线。若已有高价值可安全采购条件，才将金币变成相应计划。 | 可放弃宝库/刷金币来赌未来商店；必须明确有没有>2000分商店资格，若没有就回查现有工具能否覆盖终局。 |
| 20 | 城王，450HP/三阶段；战后条件商店；无普通F/T保证 | 按强化波次与术士优先级作战。胜后拾王冠、选择可落地的护甲能力；若任务达商店资格，确认商店真实生成再采购，包含其3支火把与小口粮的实际价钱。若无店，向21层实际供给规划，不能等待店凭空出现。 | 可放弃无必要战后搜图；王冠与已真实生成商店的关键采购要结清，再进入没有默认店的21层。 |

新小恶魔任务在17–19层生成`AmbitiousImpRoom`，`oldQuest=false`；旧代币流程只是旧档兼容。[Imp.Quest.spawn](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Imp.java#L305)。新局最后店在**20层城王后**，条件`completed && score>2000`（恰好2000不够），不是21层保证店；城王死后`unseal`按此生成。[资格](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/npcs/Imp.java#L385)、[CityBossLevel.unseal](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CityBossLevel.java#L368)。

宝库是同层编号branch1，普通楼梯不能直接离开，要用EscapeCrystal；结算≥500可选一件允许物品带出，店资格另需>2000。退出原生路径会`hero.live()`、`HP=HT`并恢复原装备，这是一次任务状态转换的恢复机会，不经治疗药恐分支，也不是无限反复刷新的资源。**不能只看这项收益便假定任何血量进入宝库都安全**：先读实际入场装备替换、角色状态、逃脱晶体的可用性与整段战斗风险。细节由宝库/特殊房间章节展开。[VaultLevel.activateTransition](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/VaultLevel.java#L205)、[EscapeCrystal奖励与退出](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/EscapeCrystal.java#L164)、[leaveVault](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/EscapeCrystal.java#L257)。

### 恶魔大厅与终局：血巢是影响最终战的高价值目标，普通杂兵不是必清

| 层 | 基准供给/节点 | 优先目标与可以停止的边界 | 可放弃什么／何时重新评估回收 |
| --- | --- | --- | --- |
| 21 | P第一对前层；U可能；F1/T3；1血巢；无默认商店 | 优先照明、已见关键物、血巢和出口；把血巢战与路线附近恶魔分开拉开处理。模板静止不等于无威胁，击打血巢会缩短出怪计时。主装已足够时跳过不服务终局的杂兵/低值房。 | 可放弃低价值杂兵/装饰支路；血巢与明确可补当前光粮的物优先回收，留下血巢须承认最终战代价。 |
| 22 | P第一对后层；U可能；F1/T3；1血巢 | 在清血巢路径上尽量处理必要补给；恶魔眼预警与魅惑改变近身方案，不能为地上物跨未核射线。血巢尚未找到时也不把“本层有一个”变成沿每块墙无限搜索，其房门本来不随机隐藏。 | 可放弃在恶魔眼射线上捡非关键物；缺力量/照明而旧路有安全解法时先比回收，不因T3生成额度盲冲。 |
| 23 | P第二对前层；U可能；F1/T3；1血巢；炼金可能 | 蝎子远程代价上升，以控制/遮蔽和实际射线为依据。开始最后一轮炼金：照明、气体/火/盲应对、位移、范围控制与足以处理双拳的伤害，保留未来可能用到的原版药。 | 可放弃不服务Yog的装备搜集；下一层之前可安全完成终局炼金就做，未见24层锅不是立即可用补给。 |
| 24 | P第二对后层；U可能且本区总2；F1/T3；最后血巢；若23无锅则此 | Yog前最终整备，不为了“8张全拿齐”“STR20”死追已被重兵挡住的物资。尽量消灭可处理的最后血巢，并核四层哪些确实已毁；若要留下一个，应把强化Yog召唤池的实际后果纳入计划。关键准备完成后直赴25，停止收益不明确的回头清场。 | 可放弃为凑8卷/STR20硬闯；若双拳/射线/清盲等方案不成立且已有安全回收解法，必须先重评该解法再触发Yog。 |
| 25 | Yog，强化射线、三轮双拳；无普通F/T保证 | 用当前已公开拳头、保护圈、射线与召唤物安排每个动作；全清血巢仍不消除挑战自带撕裂者。照明、控制/机动分别预算整个六拳过程，不能拿一个阶段短暂回血当无限恢复。击败后确认出口开启，去26取护符。 | 可放弃无目的拖延；胜后核Yog实际死亡与出口开放，当前终局不是回头补做低价值任务的时机。 |
| 26 | 护符；无普通食物/火把补给，原生不生普通怪 | 沿真实路线取护符，选择符合用户目标的正常结束并核公开`won/ended`。普通九挑战通关不必额外上行；若用户明确要带护符登顶，应另立上行预算，不能在已可完成目标时默认增加整段风险。 | 可放弃额外上行；护符与正常结束为当前目标必要节点，确认won/ended后停止，不再为探索分数继续冒险。 |

`HallsLevel.initRooms`每层添加一间血巢房；其门不随机隐藏，仍必须实际找到路线。[HallsLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/HallsLevel.java#L87)、[DemonSpawnerRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/DemonSpawnerRoom.java#L47)。血巢模板120HP、击打缩短出怪冷却，固定治疗药不适合作英雄恢复，详见[DemonSpawner.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DemonSpawner.java#L134)及[05终局](05-enemies-halls-yog.md)。26层`createItems()`只掉护符，`addRespawner()`返回null，不能套`Level.create`中的普通食物安排推断26层有补给。[LastLevel](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/LastLevel.java#L174)。


## 5. 商店、炼金和任务应服务下一个门槛

每家普通商店固定清单含2份SmallRation、治疗、鉴定、祛邪、地图，以及装备/投武等；小口粮在挑战下通常仅50饱腹，治疗不能喝来救命。商店能让金币转化成确定工具，却不是“进店自动回满”。采购前先列**必须保留的下一关工具**，把识别/祛邪/粮/容器与伤害升级的实际收益放在一起比较。[ShopRoom.generateItems](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ShopRoom.java#L222)。

炼金房不仅是强化饰物的地点。九挑战中，已知Purity加4能量变Cleansing，可获得450直接饱腹与清多数负面；治疗瓶可分解成能量或转作合适原料。优先级是“解除当前生存缺口→下一场首领有明确用途的工具→长期投资”。先看实际配方/原料/当前能量，留好别的房间仍要用的原版解谜药；不能为了做净化，把眼前毒房唯一Purity全转走再硬闯。[PotionOfCleansing.cleanse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L75)、[07炼金](07-identification-consumables-alchemy.md)。

同一个“选做任务”有不同回报确定性：法杖匠给无咒且额外升一级的两类杖二选一，对缺远程战士常有直接价值；幽灵奖品等级/阶数随机且任务怪可能高耗；铁匠可用较低好感办一项具体服务；宝库既有装备选择/一次退出恢复，也有装备转换和独立危险。因此**没有所有任务都必做或所有任务都必跳的统一答案**。任务开始前写出此次要兑现哪个回报、退出方式和不能超出的成本，达到后就允许离开。

## 6. 首领前最低检查门槛：用于排除明显不可行方案，不是胜率保证

不能给出“Goo必须满血、Tengu必须某等级/某件装备”作为源码保证；不同武器、攻速、盾、法杖、控制、附魔和布局可改变所需资源。以下门槛是每场战斗开始前都必须能解释的条件，而非要求凑齐一种固定掉落。

| 首领 | 输出与移动最低检查 | 储备与停止条件 |
| --- | --- | --- |
| Goo | 知道目前攻击在其DR/抗性下确有正收益；能在短蓄力后走到>2距离或硬墙断线；自身水洗点不让Goo持续水疗 | 半血后更高普通伤和蓄力概率仍有处理手段；护盾和HP能承受计划中不可避免的普通交换。不满足躲蓄力条件时先改战场，不用“还有盾”硬开 |
| Tengu | 第一阶段可达伤害与安全路径，第二阶段有能一边避开叠加区域一边输出的方案；知道缠绕/致盲/恐惧无效 | 两阶段分别分配电量、投武和机动；第一阶段地板浮空不算第二阶段免伤；照明覆盖剩余战斗，不假定过场能无限休息补资源 |
| DM-300 | 能完成三次过载跑塔并击破80HP塔；正常阶段也有稳定伤害 | 跑路、塔输出、再定位分别计入机动时间；毒/落石/电的源不同，必须对应工具有效。不能把只够一次跑塔的加速写成全战计划 |
| 城王 | 有针对多敌的处理次序，术士远程线能被打断或控制；不把国王面板血条当唯一需支付的敌方行动 | 18个二阶段波次单位、末段召唤与延迟伤害都需预算；大量资源已耗尽却仍未能处理第一轮时，承认进入前方案不足，不能靠连读未知卷赌翻盘 |
| Yog | 能按两拳组合与召唤物重做走位，理解保护圈和穿墙射线；每种遇到的拳头有至少一条当前可用对策 | 储备覆盖六拳与本体收尾，火把/清盲/脱险不能全押一瓶；剩余血巢对召唤池的负担已计入。不要为了额外徽章主动留四血巢 |

通用门槛：当前真正可吸收战斗伤害的盾与冷却分开，饥饿/DOT另计；有限增益用实际动作时间减，不能把持续10当作10次任意攻击；陌生物品先识别或检查来源；所有“最后关头可用”必须已经在背包、可执行且对该敌有效。若只剩未识别瓶，就没有一条已验证的保命资源，不能把它计为净化/隐身。

首领锁层禁止正常楼梯撤退，而且被动恢复预算有限；要在**进入锁层触发位置前**完成上述检查，而不是Boss已经醒了才开始长休息。具体数值与机制见[01锁层](01-challenges-and-survival.md)、区域首领章节。

## 7. “跳层”必须分清三种操作

### 少探索而走楼梯：通常是应优先比较的省资源方式

普通楼梯不检查杀怪数、探索率或本层支线任务完成；常规下楼仅到depth+1。英雄已站楼梯且未锁层、未被扎根等条件允许时，附近敌人不构成一条全局禁止切层规则；但在远处点楼梯首先要走过去，那段路仍危险。[LevelTransition](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/LevelTransition.java#L64)、[Hero.actTransition](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1429)、[Hero.handle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1991)。

因此，可以留下未清的普通怪、非必要秘密房和低价值奖励，直接走当前可达出口；不能因为“地图未全亮”强制停留。反之，出口已见并不等于出口路线已通、安全、或允许切层，必须看当前地形、敌人、锁层和状态。

### 主动落坑：不是免代价快速下楼

坠落也只是depth+1，按下层`fallCell()`落点，不是自动跨过下一层Boss。普通落点会避开已占角色/陷阱格，但不保证落点附近无敌、无射线或有安全回上楼路线；弱地板的特定下落可进对应PitRoom。[InterlevelScene.fall](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/InterlevelScene.java#L692)、[Level.fallCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1153)、[RegularLevel.fallCell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L769)。

无羽落时，落地给残废、流血，并造成`max(当前HP/2, NormalIntRange(当前HP/2, HT/4))`即时伤害；血越低流血越强，低血还可能即时死亡。九挑战不能用“下层有食物”“可以治疗瓶解流血”抵销这笔风险。[Chasm.heroLand](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Chasm.java#L131)。

**可考虑的条件：** 已经有实际羽落buff，确认剩余持续覆盖落地，且这次落点类型/下一段脱困优于已知楼梯路线；或是无其它可行选项的明确赌博。羽落50时间，每次落层再减10，先处理并返回从而避开上述落地伤害；它不是普通持续浮空，也不保证落点怪物不会打人。[FeatherBuff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfFeatherFall.java#L43)。已有浮空时踩坑不会自然下落，结束时站坑仍有风险。钢铁之胃等过场清除/耗时不能被假定能跨层保住这次裸跳。[Level.beforeTransition](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L611)。

### 跳过主线首领：没有在这里证明通用合法方案

常规流程5/10/15/20/25的原生出口分别由钥匙、阶段地图或锁门/出口开放控制；Tengu竞技场还清空普通transitions。不能把“普通楼层可以直接下楼”推成“隐身跑过Boss就下楼”。[Goo锁出口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/sewerboss/SewerBossExitRoom.java#L57)、[Tengu竞技场](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/PrisonBossLevel.java#L244)、[DM-300开门](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CavesBossLevel.java#L349)、[城王开门](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/CityBossLevel.java#L368)、[Yog开出口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/HallsBossLevel.java#L287)。

同时，源码没有一条通用“Boss层绝对不能坠落”的检查；部分视觉深渊位于出口侧或不可达处，能否接近取决于实际地图与动作。本文不把尚未验证的穿墙/坠落/传送绕关当成默认通关策略，也不虚构一个全局禁止机制。特殊WeakFloorRoom在下一层是Boss时从生成池移除，只约束该房型。[SpecialRoom.createRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SpecialRoom.java#L170)。

## 8. 正常胜利与可直接复用的决策记录

25层击败Yog、26层拿护符、选择正常结束是三个节点。拾护符展示原生选择，正常结束按钮调用`Dungeon.win(Amulet.class)`；选择继续后若从25层入口上到24才开始上行确认，增加AscensionChallenge，护符也不再提供原来的直接结束操作。用户若只要求九挑战通关，就在正常终局完成后按停止政策收手，不擅自把目标扩展成登顶。[Amulet.doPickUp](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Amulet.java#L72)、[AmuletScene结束](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/AmuletScene.java#L78)、[上行确认](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/HallsBossLevel.java#L334)。

每层记录可以压成以下字段，不需要重复粘贴整篇研究：

- **当前证据：** 自己HP/盾与冷却、饥饿、照明/火把、已鉴定逃生、已见敌人/危险、可通楼梯；记录所属公开frame，禁止旧frame配新rev。
- **这层一个主要目标：** 例如“拿已见力量并回锅做净化，然后找出口”；不同时承诺清任务、找所有暗门和满血。
- **可选目标及停止条件：** 有明确工具/顺路才做；粮光留不出下一段余量、遭遇升级、无输出窗口或累计成本达限就改计划。
- **未取关键资源：** 只记公开目标与尚需工具，另列基准额度与尚未核实项，不把两者混成坐标知识。
- **下层门槛：** 下一层新增威胁/首领、需要保留的控制/机动/照明；本层已经解决什么缺口。

**尚未证明的部分：** 没有对不同探索覆盖、休息/进食策略、任务档位进行多种子九挑战胜率实验；没有确定每层统一HP/等级阈值；没有验证绕过主线首领方案。纯计算仅确认升级槽分布，并不预测本局物品身份、位置或敌方随机结果。后续实战应把真实花费、错过的公开资源、延期回收结果和最终结局回写项目memory，以逐步校正这些条件策略。
