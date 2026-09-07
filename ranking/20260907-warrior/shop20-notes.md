# D20 王后天赋与商店

只读源码/存档分析，未操作UI。基准商店快照a7c501b4290c；主代理随后已买(6,18)Healing，更新为Heal5、gold1100。价格按Shopkeeper.sellPrice=item.value()*5*(20/5+1)=value*25，本版Imp没有折扣。未知药/卷当前按未鉴定价格，后续全局鉴定可能改变个别物品价格。

## 本次L17天赋

首选**LethalDefense1**，前提是继续在安全场合用Slam/Crush/Parry反击等连击战技收尾，并使用纹章盾。每个战技直接击杀使BrokenSeal.WarriorShield.cooldown减50；Crush每个溅射击杀分别算。普通近战/飞镖/法杖/Thorns独立击杀不算。可把CD存到最低−150；基础启动一次加150，因此负CD能抵扣下一次启动的冷却。它不立即发护盾，也不给HeroicLeap/ClassArmor充能。

Cleave1→2仅将击杀后的连击保留30→45回合，未杀命中仍只续到至少5；使用Slam/Crush后仍清连击。现HoldFast3原地不动已停止连击衰减，边际主要是移动途中多15回合。Strongman1按baseSTR18取floor(18*.08)=1，STR18→19；现装备不超重，主要是余力伤害上限+1，不加HP/护甲DR。当前防御收益排序LethalDefense1 > Cleave2 > Strongman1。

## 最新购买预算

现gold1100，已买治疗1，Heal总5。**卖Might0=75，再卖两瓶Purity中的一瓶=30 →1205。**购买sandBag1=750、SmallRation1=250、Torch1=200，共1200，余5。保留另一瓶Purity，Toxic与LiquidFlame暂不卖。Might已卸下，无使用代价；出售一份Purity时选择单瓶，不卖整组。

若改变卖物顺序：未ID Toxic1同样30，多余LiquidFlame1同样30；地面BattleAxe2(9,43)未ID售价80，但为补25金不值得专程回下方捡。它已知真实+2不等于角色levelKnown，不能报240；鉴定后才240。

沙袋拾取立刻提升Hourglass真实level1、容量从5→6，不自动填新增充能。`sandbags=5`是本局已生成袋子计数，不是已吃5袋、更不代表满级。当前沙漏真实level0、charge5；买一袋后核level1，容量6，显示等级可能按神器惯例翻倍。

## 实际库存（购买前基准，坐标均D20）

| 坐标 | 商品 | 当前价格 |
|---|---|---:|
| 8,13；9,13 | sandBag，各1 | 每袋750 |
| 4,13；6,13 | SmallRation，各1，150饱食度 | 每份250 |
| 4,19；8,19；10,16 | Torch，各1 | 每支200 |
| 10,15 | Healing1 | 750 |
| 6,18 | Healing1，**主代理已买** | 已花750 |
| 4,16 | Frost药1 | 750 |
| 4,17 | Levitation药1，未鉴定 | 750 |
| 5,19 | Identify卷1 | 750 |
| 10,13 | RemoveCurse卷1 | 750 |
| 10,19 | MagicMapping卷1，未鉴定 | 750（识别后通常1000） |
| 10,18 | Retribution卷1，未鉴定 | 750 |
| 4,18 | 未祝福Ankh1 | 1250 |
| 5,13 | Stylus1 | 750 |
| 7,13 | StoneOfAugmentation1 | 750 |
| 4,14 | Bomb1 | 375 |
| 9,19 | CleansingDart×2 | 整组375 |
| 10,17 | Trident0×3 | 整组1875 |
| 6,19 | WarScythe0，无咒无附魔 | 2500 |
| 4,15 | PlateArmor0，无咒无刻印 | 2500 |
| 10,14 | Alchemize×3 | 整组175 |

商人位置(7,16)，无其他活敌。现Main版装备Crossbow4 Chilling、WarriorArmor6 Thorns/Seal1、Evasion3/Accuracy0已经胜过库存+0近战/护甲，不换。Healing已5且四Spawner预计补4，当前一袋沙+起步粮火把比继续花750买第6瓶更平衡。其余预算留不出购买，直接继续D21主线。

源码：Shopkeeper.java:201；Ring.java:293（Might75）；Potion.java:440（未知普通药30）；MeleeWeapon.java:395（未知战斧80）；TimekeepersHourglass.java:196/508/530；SmallRation.java:42；Torch.java:97；Combo.java:414/472；BrokenSeal.java:347/358；HoldFast.java:61；Hero.java:281。
