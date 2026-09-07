# D23 主线、酸蝎与炼金只读计划

本地v3.3.8/code896，地图42×45；初始快照d94d4541e28b，完整读取053ee071b11d（H32,20）。后续主代理已到27,18开始搜近Eye房暗门，近Eye被Dart打到80并引至25,17。这里记录路线/源码机制，**不能代替每次动作后的新保存与截图**。所有操作仅由root做UI，本代理只读源码/存档与写/tmp。

人物初入L18exp27、HP105/105、STR19、Haste2/Evasion3、Crossbow4Chilling、WarriorArmor6Thorns/Leap约90、Hourglass真实1容量6且满充、Heal7、Food1、Water3、Aqua109、Light约168/Torch2、Fireblast2当前2/4、Frost0当前1/2。当前已无Toxic；Alchemy初始能量3，拾5水晶可到8。

## 核心顺序

1. 入口西行，先隔离近Eye783(25,16)，取最后SoU(25,18)，安全处升Haste2→3。
2. 从SoU房西侧暗门22,16绕北水祭室，**可先取安全Key12,14与Food13,17**，不踩叠钥匙的Storm14,14。
3. 走祭室南侧暗门16,21→走廊16,22/23→门17,24拆Spawner20,26，避免先从19,19北门直面对酸蝎。
4. 准备后单杀Acidic778(19,21)，拿保证掉落Experience；或先用安全铁钥匙去入口Alchemy做控制/解酸工具，再回来打。
5. 最后经Spawner西走廊暗门15,23进入出口房，绕开Mimic13,24与Corrosion12,21，出10,27。北/南大支线不是必需。

## 近Eye与SoU：两只眼的视线不能混淆

近Eye783原25,16；北Eye777在25,12，旁边普通Scorpio782在26,12。近Eye房东入口**26,18是暗门**。从入口32,20→31,20→30,20→29,19→28,18→27,18搜索/开26,18。

候战H28,18：Eye在26,18门格或27,18时若瞄准Hero28,18蓄光，退**SE29,19**。其新弹道第一/第二格撞28,19实墙；旧线沿y18，不碰29,19。已用本版Ballistica验证。等beamCooldown>0再回28,18输出，别等Eye走进Hero候战格后还套旧墙角。不开火烧乱门和坟场；Dart/Frost引射、Slam收尾按当前资源处理。

**北Eye25,12到SoU25,18是6格无遮蔽纵线**，深渊25,13不挡激光。普通Scorpio26,12到此处多数弹道被26,14/15墙隔，但仍不可默认未醒。近Eye死后，取卷时还必须读北Eye的seen/beam状态；拿到后E退26,18会脱离旧x25纵线，新目标弹道被26,15墙挡。

保守取法：从H27,18冻结周围，W26,18→W25,18→拾SoU→E26,18→E27,18，Haste2下四次移动约2.466T加拾物1T，约耗2充能；搜暗门/其他额外动作不包含在预算。确认脱离北眼射线后取消冻结，再读SoU升级Haste3，**读卷会解冻，不能站卷轴格顺手升级**。

去西侧暗门22,16会再次接近北Eye射线。候选25,18→24,18→23,17→22,16（暗门先揭示），到门外21,16。北Eye若醒，先看beamTarget/time，必要用冻结完成搜索与跨过门；不盲连走。不要把北Eye还睡的旧状态当作这段必然安全。

## 安全铁钥匙、Food与Spawner

西门外21,16→20,15→19,14→18,13→暗门17,13→16,13→15,13→14,13→13,14→**IronKey12,14**。这里与西北Succ785(15,10)之间有实体墙，但开门同样先读状态。

**IronKey14,14与隐藏StormTrap完全同格，跳过它**；已有12,14这把足够开Alchemy，不为了第二把硬踩雷。

从12,14可沿11,15→12,16→Food13,17，或者按已见水祭室安全地走；中央石像是solid，不自动寻路踩14,14。水地可用Aqua补血。由Food13,17→14,18→15,19→15,20→暗门16,21→16,22→16,23→门17,24，进Spawner房。

**保持17,22（酸蝎房西门）关闭**，可先避开酸蝎。Spawner781在20,26，房内18..22×24..27无陷阱。初始Ripper790在20,27，随后必移动；接近门就看leap_pos，先处理扑击。建筑受伤会推进孵化CD，别连续点到多只Ripper围上来。拆掉必得Healing与15EXP，D24只剩最后一个Spawner。

## 酸蝎值得打，但不靠贴脸无脑砍

Acidic778(19,21)：继承Scorpio的110HP、30–40物理远射、攻36/防24、DR0–16、EXP14；相邻不能射，会后退。**远射命中必给Ooze20T，另50%Cripple；英雄相邻物理命中它也会反加Ooze。**D23每Ooze tick5HP，普通甲/闪避不能减少已经附着的酸伤。站水可洗，但新Ooze至少可能先结算一次5；不是站水就零反酸。法杖直接damage不走接触反酸，远距≥2的物理命中也不反酸。它免Ooze/抗Corrosion，但不免Chill、Burning或Bleeding；**必掉PotionOfExperience**。

### 失明+火瓶控制链（已核不会主动解除冻结）

当前BlindweedSeed2、LiquidFlame2可以组成两轮。**种子直接投其脚下只种植物，不立即触发**；没有Regrowth Lotus。需要同格一次hard press。

已完整核调用链：Item.execute/GameScene.cancel只取消选择；Item.cast→onThrow→Hero.spendAndNext均不调用Invisibility.dispel；Seed.onThrow仅Level.plant；Potion.cast只super；Potion.onThrow先pressCell再shatter；LiquidFlame只生成Fire；当前Talent.onPotionUsed也无dispel。因此**这两种投掷可在Hourglass冻结中连续完成**，与Runestone末尾显式dispel不同。冻结可能因充能耗尽结束，所以每投后仍核artifact.buff。

如果酸蝎仍19,21，且北路其他怪已隔开：

1. 在北门**19,19**取得实际投射通路后立即冻结；开门本身可能已唤醒它，别在门口先耗一回合等。
2. Blindweed投**19,21**。读回目标仍19,21、植物Blindweed同格、artifact.buff还在。
3. LiquidFlame投**同一实际落点19,21**。Potion先press，但冻结内会加入delayed presses；然后产生中心18..20×20..22的Fire，世界未演化。Hero19,19距中心2，初始火圈不含Hero。
4. **仍冻结时N退19,18**，远离可能起火的门19,19，再主动结束冻结。两投各1T+一移动约0.525T（Haste3），通常耗2充能，按真实turnsToCost计。
5. 解冻时TimeFreeze.triggerPresses通过VFX优先级先触发植物，再恢复世界。确认酸蝎得到**Blindness10T+Cripple10T**、转WANDERING，之后才演化火。植物已消费，后续烧草不会解除身上的失明。

失明让它只感知邻格，而Scorpio明确邻接不能射，所以盲期间其普通远射被封。保持≥2格，用投锤/远Dart/法杖补伤，不贴脸普攻；若它游荡接近，先退开或只用法杖。Blindness不是Roots，位置仍会变化。

北门19,19离开会关，并可能被相邻火点燃，约数个世界回合后烧毁。**门未打开/烧毁时，不从19,18浪费Dart打门；也不要站回燃烧门格。**在安全19,18读火量、门地形、酸蝎位置与Blindness剩余，出现通畅弹道再输出。可先让烧伤消耗其HP，不宣称一轮火+失明必杀。第二轮需同样锁住“植物+目标同格”，不能把种子投旧坐标。

寒冷弩/Dart触发Chill会灭Burning，Frost也会灭火；为保火伤可优先投锤或等火伤将结束再补Frost。失明临近结束还没杀掉则先保持退路/第二轮，不为补最后一箭站在酸射线上。**普通Healing不清Ooze**；有酸后往水祭室洗，或用下述Cleansing，七瓶Healing可补实际损血。必要时接受一次小量反酸完成确定Slam收尾，但不要把这种打法写成零伤害。

## Alchemy支路值得顺取，但只做少数直接有用配方

锁门32,23内有Healing(28,23)、EnergyCrystal5(31,26)、Alchemy(27,27)，没有原生怪/陷阱。用**安全IronKey12,14**，不拿Storm上的钥匙。北祭室返回入口候选：13,14→14,13→17,13→18,13→19,14→20,15→21,15→暗门22,16→23,16→24,16→25,17→门26,18→27,18→28,18→29,19→30,20→32,20→33,20→34,21→35,22→34,23→33,23→锁门32,23。**穿SoU坟场时仍会暴露于北Eye25,12，必须按当时射线/冻结窗口通过，不是无敌往返。**

推荐做：

| 材料→结果 | 能量 | 用途 |
|---|---:|---|
| Rage1→Aggression石×2 | **0** | Yog拳/小怪短时转火；Boss/Miniboss仅5T，不能停Yog激光 |
| Teleport1→Blink石×2 | **0** | 定点挪位并解除Roots，补HeroicLeap不能从Roots起跳的缺口；仍核PROJECTILE实际落点 |
| Purity1→Cleansing1 | **4** | 清Ooze/Cripple/Blindness等负面，并5T阻止新负面，作为酸蝎/拳头备用 |

两种卷轴转石都无需先读卷鉴定，实际拆解会识别原卷；在预览中若未知显示占位符，仍按真实卷种选择。拾能量后8，三项做完余4；无需炼更多。**当前已无Toxic，所有腐蚀药/Caustic旧建议作废。**DragonBlood要先LiquidFlame→DragonBreath花4，再炼花10，总14，当前8做不了。AntiMagic6会关Haste/Evasion/寒冷/荆棘与部分工具，不优先。

酸蝎已经失明后若要近身收尾，Cleansing5T可防新Ooze，但不是物理免伤；更适合作不慎中酸的解危工具。除Rage转石/Blink外，不为炼金多走危险南底支路。

## 出口与可跳过内容

Spawner门17,24→16,24→16,23→暗门15,23→14,23→13,23→12,24→11,25→10,26→出口10,27。**Mimic13,24仍PASSIVE，别点击它或用AoE唤醒**；实际144HP，里面Gold436+ForceCube0×3，不是必须打。Corrosion12,21隐藏，不从北侧直接下踩。

可顺取Levitation11,27；西池三Piranha(7,14)/(4,15)/(3,16)各125HP，箱Plate1无咒(5,18)远弱于现Armor6，跳过。北Eye7,7+Succ15,10保护迷宫，秘密食品间入口17,7有Pasty18,5、ChargrilledMeat19,7/20,7、BlandfruitBush20,5，但当前Food/饥饿足够，不为它加战斗。南深渊锁箱、Library、Succ24,41等支线也不必走。

## 完整初始索引

主要敌：Eye777(25,12)、779(7,7)、783(25,16)；Scorpio782(26,12)；Acidic778(19,21)；Succ785(15,10)、787(24,41)；Mimic780(13,24)；Spawner781(20,26)；Ripper790(20,27)后续动态移动；三Piranha776/784/786见上。

全层仅3初始陷阱：Storm14,14（叠IronKey），Corrosion12,21，Flashing21,37，均隐藏且active。其他物资：墓Gold403(24,15)/358(24,17)，Torch25,17/10,7；MindVision23,16；Gold495(22,21)酸蝎房；北MagicMapping9,5；南Identify24,34、Invis19,38、Library锁门22,32后RemoveCurse25,31。南四钥匙5,31/11,31/5,36/11,36对应深渊四锁箱：SungrassSeed7,33、Javelin0×3在9,33、Gold514在7,34、Mapping9,34，均非当前主线必需。

源码依据：Acidic.java:32/49；Scorpio.java:73；Plant.java:151；Level.java:1021/1230/1290；Potion.java:308/344；PotionOfLiquidFlame.java:40；Blindweed.java:46；TimekeepersHourglass.java:379/425；Item.java:639；Hero.java:824；Scroll.java:308；StoneOfBlink.java；PotionOfCleansing.java:75；ElixirOfDragonsBlood.java:46。
