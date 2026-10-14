# 17：积极获取与使用神器——战士九挑战执行卡

研究日期：2026-10-14。源码基线：`bb0c91fb7c6fa8e33819c554427f81b6cf93f2f9`，游戏4.0.2 / code922，CLI.9.0.6。本文只读当前源码和项目研究；没有启动、查询或操作暂停中的游戏，没有读取个人存档或私有audit。当前局面必须等用户恢复后重新公开观察，本文不证明本局已经获得任何神器。

**证据标签：**「源码确认」为此基线的实现；「战术推导」为依据规则提出的执行条件，不等于已验证胜率；「待实测」为尚未在公开实局/隔离fixture验证的交互。旧章[10](10-rings-artifacts-trinkets.md)保留戒指、饰物和完整数学背景；本章补上“发现后怎样拿、拿到怎样转成收益”。房间路线见[13](13-special-rooms.md)，能量机会成本见[15](15-alchemy-planning.md)。

## 1. 本局执行原则：每件神器都必须获得一个用途

以下是用户已指定的积极策略，实施时仍受生命、食物、照明和退路约束：

1. 看见可安全取得的神器就优先收取；楼梯附近物品也要在离层前核对，不因正在下楼而自动忽略。若暂取不了，记录**障碍、需要什么资源、何时回来**，避免无理由略过。
2. 到手后在安全格检查已知诅咒、可用槽位、充能和成长条件；完成“装备/鉴定/祛邪/暂存”的明确选择。有确定用途的无咒神器应尽早装备，让正常推进贡献充能或经验。
3. 为每件已装备神器写一条触发句，例如“远程敌人隔着已知墙时用链条断线”“下一个草区前用鞋的已储效果”“下一次合格进食窗口用号角snack”。每层、每次充满、每次获得成长材料都重查。
4. 积极使用可以是**预防即将发生的损失**。一次消耗能避免连吃射线、合围、长追逐或一次性消耗品支出，就不必等濒死。留电量时写清保留给哪一种风险与多少合法位移距离，不能把“应急”当永久满充的理由。
5. 神器与戒指竞争杂项槽；已经有更高即时收益的组合时，可以携带等待合适窗口，不要求把全部神器同时戴上。换装本身消耗时间；在安全点做换装决策。[槽位选择与装备](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/KindofMisc.java#L146)、[装备/卸下耗时](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/EquipableItem.java#L120)

## 2. 能获得哪些神器，去哪里争取

**源码确认：**生成目录有13类：11类权重1，暗影斗篷与神圣法典权重0；每次普通神器抽取会耗去该类权重，神器池不自动重置。普通随机神器从+0起、通用随机诅咒概率30%；具体来源可覆写诅咒。池耗尽时普通`Category.ARTIFACT`请求回退戒指。不能把“1/11”当每个地面物品的神器概率，也不能从已见神器推断未探索房间的实际奖励。[目录](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L560)、[不重复抽取](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L855)、[回退](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L706)、[随机诅咒](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/Artifact.java#L217)

| 获取渠道 | 源码确认的机会与限制 | 战术推导：怎样积极争取 |
| --- | --- | --- |
| 普通随机物品 | 两套35张总类别牌组交替，其中一套含1个神器类别，另一套含戒指；不是每层固定神器，也不是独立恒定1/70概率。[总牌组](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L235)、[抽取](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L675) | 正常探索与顺路拾取优先；不要为寻找“该轮必出的神器”无界耗粮刷图。 |
| 双水晶宝箱房 | 法杖/戒指/神器三类别洗牌取两类，因此池未耗尽时2/3会包含神器类别；真水晶箱可检视类别。第二份奖品基础10%改为水晶拟态，饰物另修正。[房间](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalVaultRoom.java#L53)、[类别](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalVaultRoom.java#L102)、[检视](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java#L399) | 已有可靠输出而缺机动/续航时提高神器类别优先级；仍比较现有缺口。拟态可以不花水晶钥匙取得其奖励，但需有安全击杀/封路计划，不能仅为双取冒险。 |
| 水晶二选一房 | 一边3–4随机药/卷，另一边普通箱，法杖/戒指/神器等概率取一；普通箱不提供水晶箱的类别预览。[奖励](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalChoiceRoom.java#L105) | 可见消耗品若能立即补粮/逃生就有确定收益；装备侧只是1/3神器机会，不能写成“神器房”。 |
| 薄地板下面的坑房 | 主奖励在戒指、神器、装备组间等概率；普通神器池耗尽可回退。另1–2份奖品和本层水晶钥匙，遗骨可能闹鬼；干井不回血。[PitRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PitRoom.java#L46) | 有羽落或足够坠落/流血/怨灵/返回预算时，将它作为真实的神器机会主动评估；不是见坑必跳，也不是一律跳过。 |
| 商店稀有品 | 稀有槽10%法杖、10%戒指、10%神器、70%铁笔；最终稀有品明确无咒且咒状态已知。[ShopRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ShopRoom.java#L332) | 真实见到有用神器时把购买列进预算；无咒降低试用成本。不要预支未生成商店的神器。沙漏持有条件影响未来流沙袋，见下文。 |
| 小偷战利品 | 原生小偷额外掉落类别在戒指/神器二选一，初始总掉率3%，每次该类掉落使后续概率乘1/3；财富和过级等还须走Mob规则。不是固定掉袖章。[Thief](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Thief.java#L43)、[衰减](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Thief.java#L91) | 正常可安全击杀就收取；不要为1.5%基础神器分支追着小偷消耗整层资源。追讨它偷走的真实物品与随机奖品分开计算。 |
| 财富额外装备奖励 | **进入额外装备奖励分支以后**，五分支中一支为神器；神器不能用普通升级逻辑增级，但这里明确洗成已知无咒。[RingOfWealth](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/rings/RingOfWealth.java#L270) | 已经装备财富时正常推进兑现；这不是每杀怪20%神器，也不构成九挑无限刷怪依据。 |
| 嬗变 | 从剩余普通神器池抽新类型；继承原神器咒状态、鉴定状态和显示等级映射。玫瑰所装武器/护甲会先落地。池空可失败。[changeArtifact](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTransmutation.java#L295) | 当前神器长时间不能有效用、且已有高成长时值得比较；能保存成长，不保证转到鞋/链条，也不解除诅咒。 |
| 幽灵等任务 | 悲伤幽灵任务给武器/护甲选择；完成任务解锁玫瑰召唤/花瓣条件，不直接保证奖励玫瑰。[WndSadGhost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndSadGhost.java#L90)、[玫瑰动作门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/DriedRose.java#L115) | 已持玫瑰时，完成仍可安全完成的幽灵任务价值上升；把任务武器与玫瑰解锁两项收益都算入，不虚构神器任务保底。 |

**职业与遗留边界：**暗影斗篷由[盗贼初装](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L205)取得，神圣法典由[牧师初装](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L248)取得；普通战士不能期待二者自然掉落或靠普通神器嬗变获得。不要把法典的职业治疗/护盾当战士计划。[荆棘斗篷](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/CapeOfThorns.java#L34)与[罗德信标](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/LloydsBeacon.java#L53)是源码保留类，未在上述当前普通生成目录，不列入新局正常可获取目标。“魔典”通常指下文可获得的无序魔典，不要和职业专属神圣法典混淆。

## 3. 未知神器的积极试戴与诅咒处理

**源码确认：**神器成功装备即鉴定；正常一次装备1回合、卸下1回合，替换现有装备还包含卸装成本。诅咒装备通常无法卸下；无序魔典诅咒还能封锁除祛邪/驱魔外的卷轴。因此“装备一下马上脱掉”不是免费鉴定。[Artifact](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/Artifact.java#L65)、[EquipableItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/EquipableItem.java#L120)、[卷轴门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L173)

| 当前知识/资源 | 执行动作（战术推导） | 必须计入的成本 |
| --- | --- | --- |
| 已知无咒，确有用途 | 安全窗口尽早装备，立即读到完整鉴定与当前动作，开始正常充能/成长 | 槽位、装备回合，以及换下原戒指/神器失去的效果 |
| 未知诅咒，有已鉴定可读祛邪卷 | 安全窗口试戴；若诅咒则择机祛邪，正常则保留卷轴。也可直接先祛邪未知物，让它成为已知无咒 | 一次装备；坏结果再用一卷祛邪及读卷时间。读卷受失明/魔免限制，别在邻敌时试。[祛邪可选条件](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRemoveCurse.java#L87)、[清咒](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRemoveCurse.java#L119) |
| 未知诅咒，仅有鉴定卷 | 能立即形成关键战力且无可靠清咒时，先鉴定；若无咒则装备，否则保留待清 | 消耗一卷鉴定，鉴定本身不解除诅咒。[Identify](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfIdentify.java#L45) |
| 附近已公开确认健康之井、路线安全 | 在不踏入井前完成值得试用的装备选择，然后英雄触井同时清已装备诅咒、补饱食和恢复；或将单件可清咒物投入井 | 井是单次资源，投物与英雄恢复互相竞争；试戴数步也耗时间。凝血试管可令井治疗变渐进。[WaterOfHealth](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java#L51) |
| 只有驱魔卷 | 可作为紧急卸咒装退路：魔免持续20时间，期间允许卸下诅咒装；脱掉后物品仍诅咒 | 消耗异域卷，并暂失神器、戒指等魔法功能；它不是永久祛邪，不能当作无代价试装。[卸装例外](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/EquipableItem.java#L128)、[驱魔](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfAntiMagic.java#L36)、[20时间](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MagicImmune.java#L30) |
| 无可靠清咒、当前战力/逃生很脆弱 | 先拿到背包并安排最近鉴定/清咒机会；只有比较具体诅咒后仍能承受才试戴 | “积极获取”不自动等于承担随机残废、怨灵或封卷；不能用未来可能买到祛邪当已拥有退路 |

## 4. 11种普通神器的使用执行表

表中 `L` 是内部等级，`E` 是能量戒等实际`artifactChargeMultiplier`，`p` 是本次经验占当前一级需求比例，`Δ` 是距当前充能目标缺额。所有数值须与实际公开充能核对；多数主动神器要求装备、无咒、非魔免。时间充能所用`regenOn()`主要受锁层规则限制，**挨饿并不自动禁止神器充能**；但等待仍消耗生命/光照并可能刷怪，不能为充电把安全推进改成长休。[Regeneration](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L113)

| 神器 | 成长与回充（源码确认） | 现在就值得用的触发窗口（战术推导） | 消耗、诅咒与停止条件 |
| --- | --- | --- | --- |
| **虚空锁链** | 内部0→5、显示0→10；初5电，软目标`5+2L`；自然每tick`E/(40−2Δ)`；经验给`6p`电，超软目标后递减，并累积等级经验。[回充](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/EtherealChains.java#L315) | 射线逼近时拉自己到已知安全墙角；把可移动远程怪拉入能立即解决的近处；有明确合法悬崖落点时拉落非飞怪。不要为了省电多吃数次远程攻击 | 按实际位移距离扣电，耗1回合并消隐；已探索/映射目标与可达性、落脚、邻固体、占格、扎根均须成立。不能拉不可移动怪；诅咒有1%/tick残废10时间风险。[目标/拉怪](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/EtherealChains.java#L123)、[拉自己](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/EtherealChains.java#L216) |
| **时光沙漏** | 内部0→5，容量5→10；自然`E/(90−3Δ)`。后续商店流沙袋升级，生成时须已持有、已鉴定、无咒。[回充](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/TimekeepersHourglass.java#L252)、[商店门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ShopRoom.java#L310) | **冻结**用于撤离包围、跨过已预算危险路线、调整到下一次攻击的安全位置；**停滞**用于原地让外界有限危险经过。提前用来完成路线，不等到只剩死角 | 冻结起手扣1，之后每2单位英雄耗时再扣1；攻击等消隐动作终止冻结。停滞最多耗2电，每电5时间，本人不能行动；自己的buff也受停滞限制，不当作回血。结束会处理延迟踩踏，须检查终点。咒沙漏约10%/tick让英雄额外花1时间。[模式](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/TimekeepersHourglass.java#L103)、[计费/延迟机关](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/TimekeepersHourglass.java#L351) |
| **自然之履** | 内部0→3；每阶段喂3/6/9种种子，阶段内不重复；最后一种成为当前效果。有效踏高草给`(3+2L)/6 × E`电，容量100。[喂食/回充](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L273) | 九挑优先争取。正常探索顺路踩高草充电；已记忆阳春草且缺血时找可守位置；速行蓟/魔皇草/致盲草按已见威胁主动用。准备进入草区而接近满电时先检查可兑现的效果 | 喂种与施放各1回合；更换效果也消耗对应种子，施放要求FOV内距离≤3并消隐。诅咒禁止喂/施法/回充，踩草正常种子收益受阻。详细见第5节，不能把种子一次喂入等同全效果自由切换。[选择器](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L235) |
| **神偷袖章** | 经验充`(3+0.15L)pE`，容量`5+floor(L/2)`；偷怪基础+3成长经验、偷袭额外+2，按阈值升级。[Thievery](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/MasterThievesArmband.java#L261) | 怪已邻接且能吃失明/残废时，先偷作控制，再把窗口用于离开、断线或击杀。掉落失败仍有控制尝试；不要只把它当商店省钱道具。伏击位置的控制时长额外+2 | 每次1电、基础失明/残废`3+floor(L/2)`；目标免疫仍有效。此入口无显式正向英雄耗时，拾取时抵消拾取耗时，但实际公开调度仍待实测；已尝试窃物与过级限制使同怪不提供无限战利品。商店只在明确可承担失败时偷，100%界面值最稳；失败商人离开。诅咒平均每5tick丢1金。[偷怪](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/MasterThievesArmband.java#L113)、[商店](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndTradeItem.java#L195) |
| **骷髅钥匙** | 初3电，容量`3+floor(L/2)`；自然`E/(120−7.5Δ)`。用功能获得经验，正常钥匙能开的锁另加2。[升级](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SkeletonKey.java#L114)、[回充](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SkeletonKey.java#L431) | 用锁门/钥匙墙把两路敌人分开、让远程失去普通射线，或提前开关键奖励门减少找钥匙返程；若已有足够电，可为水晶奖励多拿一份创造机会 | 普通锁门1、锁箱2、水晶门/箱5；锁普通门/造墙2，自己解锁自造锁门不耗电但耗时。初级容量3做不到5电水晶解锁，须先升级。不能开锁定出口；墙约10tick，不挡Yog穿墙射线。多余钥匙受回收规则处理，非无限套利；诅咒会使普通开锁/开箱5/6概率被干扰，额外花两倍解锁时间和饥饿。[咒干扰](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2398)[目标](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SkeletonKey.java#L130)、[墙](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SkeletonKey.java#L467) |
| **先见护符** | 自然`(0.05+0.005L)E`/tick，容量100；搜索发现陷阱/暗门另补电，扫描映射和发现提供经验。[扫描](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/TalismanOfForesight.java#L139)、[回充/警觉](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/TalismanOfForesight.java#L272) | 跨入高风险未知走廊前朝有实际路线价值的方向扫描；黑暗中用短期敌人/物堆感知少走错路；有警觉就定向扫描/搜索，别一直保满电 | 扇形约耗`3+1.08×距离`、1回合并消隐；射程还受等级/电量限制。被动只检查当前FOV内3格可搜暗陷阱，不是全图安全证书；诅咒禁自动发现暗门/暗陷阱，主动搜索常规可搜陷阱仍成功，但附加饥饿代价加倍。[搜索](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2554) |
| **无序魔典** | L0→10、容量`floor(0.6L)+2`，自然`E/(120−5Δ)`；喂公开需求前两种之一的已鉴定普通卷，耗1卷/2回合，升1。[需求](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/UnstableSpellbook.java#L373)、[回充](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/UnstableSpellbook.java#L343) | 进入新层后、已有安全退路和可用鉴定/祛邪目标时积极读，争取地图/鉴定/充能等重复收益；一次未知效果后重新规划。可喂的多余卷与本段所需救命卷分开 | 1电随机普通效果，禁嬗变、鉴定/祛邪/地图抽取频度减半；学过该种且还有电可再花1选异域。匿名效果不识别背包卷符文，不触发读卷天赋；会有招怪/传送等不合时宜结果。失明不能读；诅咒封多数卷。不是指定逃生按钮。[随机读卷](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/UnstableSpellbook.java#L141) |
| **丰饶之角** | 原营养每300升1，容量`5+floor(L/2)`；经验充`(1.25+0.625L)pE`；九挑每电30饱食；snack耗1，eat按饥饿多吃。[食用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/HornOfPlenty.java#L127)、[储食/经验](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/HornOfPlenty.java#L227) | 有合格丰盛一餐低血线、且当前吃食动作安全时用小口；需要恢复正常饱食推进时按需求eat。预计马上大量获经验且接近满电时先评估是否该兑现食物 | 不随休息产粮。喂食永久消耗真实食物并固定花3回合、不触发进食天赋；先保留本段营养。吃食通常3回合，有钢铁之胃才减为1。每次snack独立触发进食天赋，但每次都重查低血阈值；诅咒不充，普通食物在挑战除3后再乘0.67；已有储食仍能吃。 |
| **蓄血圣杯** | 无充能池；活过刺血升1。自然再生间隔由10降至`10−1.33−0.667L`再除E，必须未进入挨饿掉血阶段（`isStarving=false`）且允许恢复；盐晶另修正。[再生](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L56) | 已知无咒先戴+0也有收益，不以“还没升高”闲置。安全且确定可承受一次最大伤害时才刺；将加快的回血与正常探索结合，不专门站着耗尽饱食 | 九挑无甲+禁疗让高等级刺血昂贵；诅咒自然恢复间隔×1.5。每次升级都重新算上界、敌人行动与下一段HP；见第6节。[刺血](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/ChaliceOfBlood.java#L121) |
| **干枯玫瑰** | 完成幽灵任务才可召唤；100电召唤，花瓣升至10；任务完成且持有已鉴定无咒玫瑰，后续普通层生成花瓣，无须当时装备，已生成旧层不会补发。[花瓣](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java#L506) | 在预计要持续作战、可以控制幽灵位置且有可用闲置武器时提前召唤；给剩余装备找用途。主动指挥堵位、集火或撤回，避免让它孤身追怪耗掉整管HP | 召唤要满电，活着时不重充召唤，约500/E时间自然恢复一管幽灵HP；死后约500/E充满。无甲同样削弱幽灵甲，非免费坦克。卸下玫瑰或英雄魔免时幽灵每次行动掉1HP；咒玫瑰可招怨灵，不要为等500回合停推进。[召唤](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/DriedRose.java#L115)、[回充](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/DriedRose.java#L398) |
| **炼金工具箱** | 每级投入6炼金能量，经验产生`(2+L)pE`能量；装备后约`(10−L)²`时间预热，+10在下一次工具箱tick清预热；没有常见小电池上限。[执行](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/AlchemistsToolkit.java#L75)、[能量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/AlchemistsToolkit.java#L220) | 早点无咒装备让正常推进完成预热/积能；出现新的关键原料、需要解控或下一段机动时现场合成，省回锅路程。每次升级先留本段配方能量 | 仅由经验持续产能，站着等不产炼金能量。每多升1级花6E、每一级英雄经验只多1E，E=1时须再获6级经验才回本；晚期一般优先成品。咒工具箱连普通炼金锅都封锁。[Hero.actAlchemy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1084) |

## 5. 自然鞋是九挑战关键例外，但需要提前安排种子

**源码确认：**荒芜在正常投种/种植路径阻止植物；鞋调用`Seed.couch(cell,null)`然后直接`activate(目标角色)`，不经过被禁的正常种植路径。这是原生规则保留的用途。鞋的目标须当前FOV内、距英雄≤3；有益效果对准自己所在格，对敌人可能反而给敌人收益。[荒芜拦截](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Plant.java#L154)、[直接激活](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L338)

| 记忆种子 | 电量 | 可执行窗口与限制 |
| --- | ---: | --- |
| 阳春草 | 80 | 缺血、已守住的格子、能够承受恢复期间的饥饿/敌情时自用。初始恢复预算等于施放时HT，每tick累积`(40+HT)/150`；移动离开解除，**受伤本身不解除**。这条恢复不经过恐药治疗瓶路径，也不检查挨饿/锁层；满HP时仍耗治疗预算。不是即时救命，也不应因有恢复buff便自动长休。[Sungrass](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Sungrass.java#L50) |
| 地缚根 | 40 | 已决定守点作战或一次圣杯刺血时预先开；提供有限定点护甲池，离开失效，并非无限护盾。刺血仍至少结算1伤。[Earthroot](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Earthroot.java#L78) |
| 速行蓟 | 20 | 在实际路线已知时开6时间气泡完成走位/整理；攻击和其他消隐操作会结束，不能当6次免费攻击。[Swiftthistle](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Swiftthistle.java#L50)、[解除入口](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Invisibility.java#L100) |
| 魔皇草/消逝草/致盲草 | 各12 | 根据真实需要做净化、随机传送或控制；效果对象、楼层传送限制和敌人免疫分别核实。不要把12电随机传送等同可选安全落点。 |
| 烈焰花/冰冠花/风暴藤/断肠苔 | 各20 | 对当前可见敌人或适合地形使用；先确认自己不会被火/冰/混乱移动堵死退路。 |
| 星陨花 | 40 | 有真实战斗窗口时获取祝福收益，不能当升级卷轴。 |
| 腐莓 | 8 | 任务种子，喂入会消耗交任务材料。只有明确比较并决定放弃当前法杖任务奖励后才使用，不为便宜8电自动喂。 |

所有费用来自[seedChargeReqs](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L107)。**战术推导：**阶段内种子不得重复，因此不要在升级中途先用掉唯一阳春草，再把效果切走，却期待立即切回。若本阶段目标是治疗，在满足升级所需不同种子的过程中把阳春草安排在合适的最后一步，或保留下一颗阳春草到阶段表清空后使用。满级换效果仍要一颗新种子；没有对应种子就不能改成期望技能。[canUseSeed与阶段清空](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L235)

有效高草踩踏在+0每格0.5电，满级1.5电（无能量戒）；从0到80至少160/54格有效高草。鞋也把基础种子掉率由无鞋1/25提高到+0鞋1/21、满级1/9，饰物/矿区等另修正；这是顺路草区的真实价值，不代表已踩平草能重复刷电。[HighGrass](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/HighGrass.java#L80)

## 6. 圣杯与号角：把恢复变成净收益

**圣杯源码确认：**现内部等级L刺向L+1时，原始伤害范围为`ceil(3+2.5L²)`至`floor(7+3.5L²)`。L0=3–7、L1=6–10、L3=26–38、L6=93–133、L8=163–231、L9=206–290。代码先手动处理地缚护甲、相关职业/活土护甲与英雄DR，再至少保留1点，然后进入英雄伤害逻辑；刺血还消耗动作时间。**战术推导：**计算时以最坏伤害、真正可用的吸收/护盾、敌人下一轮攻击与退出余量为准，不用平均伤害或提示的死亡概率赌命；低级杯也有价值，积极使用不要求冲+10。[伤害公式与刺血](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/ChaliceOfBlood.java#L121)

**号角源码确认：**九挑战每充只有30营养，snack能单独触发一次`onFoodEaten`。战士丰盛一餐须每次吃之前HP/HT<0.334才回4/6，第一口把HP升出阈值后不能把第二口仍计为治疗；钢铁之胃把吃食从3时间改为1，不能因此保证在任何敌人行动进度下净收益为正。[号角食用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/HornOfPlenty.java#L127)、[进食天赋](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L587)

**战术推导：**号角已有充能又接近升级时，及时把合格的小口转成战士天赋收益，避免容量溢出；但不主动挨打凑丰盛阈值。决定喂角时写出“当前一餐立即的营养/天赋收益”和“以后还能获得多少经验、预计多回多少电”。饥饿、低血而下一补给未知时，食物先吃，不能一边饿死一边投资号角。圣杯则需要未进入挨饿掉血阶段的窗口（普通HUNGRY图标不等于STARVING），和号角配合能延长恢复收益，但二者都不解除本局的光照/敌人预算。

## 7. 沙漏、玫瑰、魔典、工具箱的提前准备

- **沙漏要在未来商店生成前完成鉴定与清咒。** 商店按持有的已鉴定无咒沙漏及剩余袋数生成流沙袋：6层20%、11层25%、16层50%、20/21层80%，均对剩余袋数向上取整。已经生成的商店不会因稍后鉴定自动补货；不能根据“到了商店”保证满级。流沙袋本身还需购买/拾取与无咒沙漏。[库存生成](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ShopRoom.java#L310)、[沙袋拾取](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/TimekeepersHourglass.java#L499)
- **玫瑰先配装再上前线。** 幽灵力量`13+floor(L/2)`；给它的武器/护甲要符合力量且已知无咒，拒绝unique物品及附有战士纹章的护甲。九挑无甲同样进入幽灵护甲的DR计算；可以让闲置武器增加输出，但不能把它当传统高防佣兵。[装备选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/DriedRose.java#L931)、[幽灵防御](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/DriedRose.java#L735)、[无甲规则](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L379)
- **无序魔典要主动用，也要准备接住随机结果。** 新层的地图收益高于已经全探完的旧层；安全撤退线路、可供鉴定/祛邪的目标和一份已知脱险用品，可以让随机效果更容易被利用。优先喂多余卷；当前唯一祛邪/传送等救命卷若马上有用，就不为升级喂掉。强化选择不能关掉后重抽，源码会强制执行原普通卷轴。[随机读卷](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/UnstableSpellbook.java#L141)、[防重抽](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/UnstableSpellbook.java#L223)
- **工具箱同时提供能量与免回锅能力。** +0预热100时间、+5约25、+10在下一次工具箱tick清预热；正常行走/战斗可完成预热，不必站定等。若手头只有6E，升级为未来缓慢回本与立即做关键4E转换互相竞争；需要救命配方时先做成品。重复装备会重置预热，战斗中临时换上不等于立刻有锅。[预热](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/AlchemistsToolkit.java#L75)、[装备预热重置](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/AlchemistsToolkit.java#L196)
- **秘能卷轴可以把已装备神器一起充起来。** 它给30单位`ArtifactRecharge`，逐tick调用各神器的`charge`，不是立即“全充满”；鞋每单位+2、链条+0.5、沙漏+0.25、魔典+0.1、工具箱+0.25等，各自有上限/咒/魔免判断。对鞋理论最多+60，0电鞋不能只靠一卷保证80电阳春草。优先在两件都有明显缺额、接下来的30时间能安全推进时使用；不要在濒死时把渐进充能当即时救命。[秘能](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfMysticalEnergy.java#L39)、[逐tick分配](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ArtifactRecharge.java#L44)、[鞋外部充能](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/SandalsOfNature.java#L168)

## 8. 新执行策略与旧研究的关系、待验证项目

本轮复核第10章主体机制一致，但恢复门槛用语需要改准；以下文字应作为更精确的执行补充，而非声称旧研究已经证明了通关路线：

- 旧章圣杯“必须未饥饿”须写成“`isStarving=false`”：普通HUNGRY（300–449）仍可自然恢复，STARVING=450才停止。不要把普通饥饿图标误当成停止回血。[Hunger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L40)、[再生门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Regeneration.java#L56)
- 旧表“持有沙漏后商店配流沙”需补足**生成时已鉴定且无咒**；只捡起来放包里仍可能错过袋子。
- 旧章已写阳春草受伤不解除、鞋可用于荒芜，本轮保留这一结论并增加种子喂入顺序，避免“知道效果却没有可切回的种子”。
- 旧表的魔典与法典必须在实战口语中分清：**无序魔典可掉，神圣法典普通战士不可获得**。
- 旧表工具箱“每级6E”优于源码内遗留的“5级回本”注释；按当前公式无能量戒需6级经验回本，不据注释提前承诺盈利。
- 旧特殊房/炼金章提出资源紧张时可跳过坑房，继续成立；本轮积极策略要求先列可得收益和安全方案，不再把“可选”直接变成“忽略”。

**待实测，不能包装成完成验证：**袖章零显式耗时在本版公开控制调度中的行动进度；沙漏冻结结束与具体机关/攻击动画先后；钥匙墙对具体敌人特殊攻击的效果；自然鞋阳春草在当前粮光/敌人密度下能兑现多少净HP；神器换装与首领锁层下各自实际充能窗口。实局验证须等用户明确恢复后，用单步公开结果记录；独立合成fixture必须与个人profile和当前进程隔离，并说明其验证范围，不读存档推导本局隐藏状态。

每次记录只需四项：`公开触发条件 → 为什么此刻用/为何留着 → 实际花费与结果 → 下次触发条件`。本章让神器进入日常决策，不把获得神器或满级神器本身当作通关证明。
