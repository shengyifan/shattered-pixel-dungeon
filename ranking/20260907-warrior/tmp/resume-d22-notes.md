# 本次续玩临时恢复笔记：D22已取升级、准备收Food后下D23

仅/tmp任务备忘，不是长期记忆。本文件依据主代理交接消息整理；写入期间未读取或修改游戏数据、未操作UI。**这里不是最新实时状态证明，恢复时必须先读新checkpoint和截图，不直接照旧坐标输入。**

## 用户目标与权限

用户要求用战士新档通关已经打开的本地源码打包版Shattered Pixel Dungeon，允许游戏中读取存档/源码。当前继续同一slot1战士/角斗士，禁止通过修改存档或源码作弊。所有游戏动作仅CUA，源码/存档只读；只在/tmp/spd-run-20260905写报告。未获用户新授权不要写memory目录。

源码目录/版本：/Users/shengyifan/Workspace/shattered-pixel-dungeon，v3.3.8/code896，HEAD4a272100b。游戏数据：/Users/shengyifan/Library/Application Support/Shattered Pixel Dungeon/game1/。打开的是macOS打包App Shattered Pixel Dungeon，不是原始Java窗口。

## 交接时真实进度（主代理报告）

- **D22，45×39；H(3,15)，L18 exp14，HP98/105，基础STR19。**SoU(3,15)已收并用，Haste戒已从+1升到+2。
- 装备：Crossbow+4 Chilling；WarriorArmor+6 Thorns/Seal+1，原Scale的tier4保留；HeroicLeap charge80.4；RingOfHaste+2与RingOfEvasion+3；Accuracy+0在包。
- Hourglass真实+1，**6/6**；无已知活动freeze（恢复必须核artifact.buff）。`sandbags`是已生成袋子计数，不是实际神器升级级数；真实容量=5+level。
- Fireblast+2当前1/4；Frost+0当前2/2。Dart×2已回收。另有ThrowingHammer×3及Blooming Tomahawk，精确耐久恢复时读新存档。
- Healing×7，祝福Ankh仍未用。AquaHealing剩115，Waterskin只剩1滴，Light218，Pasty1、Torch2。
- T1 HeartyMeal2/VeteransIntuition2/IronWill1；T2 IronStomach2/RunicTransference2/LethalMomentum2；T3 **Cleave2/HoldFast3/LethalDefense1**；T4尚无点（L21才开始）。纹章盾cooldown已预存到**−150**，当前未激活的盾不要误算已有shielding。
- D20 Dwarf King已败，皇冠已使用并选HeroicLeap，Imp四魔像任务已完成并领+3Evasion、祛邪后替Might。D20剩余商店是旧楼，不需要现在折返。
- D21、D22的DemonSpawner都已拆，Yog小怪压力已降低两份。**D23/D24各还有一个必须优先拆。**
- D22已杀：南Succ707、北Succ711、西Succ709；孵化器衍生Ripper **715/723/727/732/738全部死**。
- D22未杀：西Eye初始(8,22)/(7,26)睡眠；东北Eye(33,15)+Succ(32,15)睡眠；**新Eye ID722在东侧入口一带游荡**，恢复时读其真实pos/target/beam状态，别按原始“入口已清空”规划。
- Prism盟友在D21被Eye激光击散，**现在没有PrismaticGuard/虹卫盟友**，不可再把它当可用承伤资源。

未在本次交接中明确复核的包内物品：先前有Accuracy0、MindVision1、Toxic1、LiquidFlame2、Invis1、Purity1、Haste药1、Levitation1、Frost药1、Rage卷新掉落、Retribution卷、RemoveCurse2、Transmutation1、Teleport1、GooBlob1、MetalShard2、种子若干、Clairvoyance/Enchantment石各2；**不要把这个旧列表当现数，恢复时看Bag。**先前Gold336（不保证此刻未变化）。

## 现在要继续的短目标

本层力量、疾速戒、Spawner、SoU都已完成。剩：**Food(21,21)、IronKey(21,22) → 出口(19,32)**。不要去Eye旁Healing(8,26)，不为北炮室/Armory/诅咒Regrowth折返。

1. 从SoU房(3,15)沿已清地回门(11,17)：例如(4,16)→(5,16)→(6,16)→(7,16)→(8,16)→(9,17)→(10,17)→门(11,17)。每步避免深渊，D22西Succ709已死。
2. 门外(12,17)→(13,17)→(14,17)→门(14,18)→(14,19)→(14,20)→(14,21)。**Disintegration(13,16)仍active，不能向北踩上去。**
3. 去Food支房：门(16,21)→(17,21)→(18,22)→(19,23)→门(20,23)→(21,23)→IronKey(21,22)→Food(21,21)。这房原生无怪，但新生怪仍按最新保存核。
4. 返西骷髅房东缘：回(14,21)，沿(13,22)→(13,23)→(13,24)→(13,25)→(13,26)→(13,27)，中央实体石像可遮两Eye，但不能把静态遮挡当永久安全。
5. 南回廊：(13,27)→(12,28)→(13,29)→(14,29)→(15,29)→门(16,29)→(17,30)→(18,31)→出口(19,32)。**(12,28)/(13,29)会接近南Eye7,26的可视射线**，必须逐步读beam。若Eye仍在7,26，旧指向13,29的光束延伸下一段会碰14,30墙，而(14,29)已超出该Eye6格视距；这是待核的具体脱离候选，不能机械连走。

本层额外危险：Frost(8,20)隐藏、Blazing(17,34)隐藏、Corrosion(41,23)隐藏、Blazing(27,2)隐藏；东北门(34,20)不要开，背后是Eye33,15+Succ32,15。CrystalKey19,34已拿并开Haste箱；它不是出口钥匙。晶箱房另一箱34,24是诅咒Talisman0，已放弃。

完整路线/实体参考：/tmp/spd-run-20260905/halls22-notes.md。**最新怪物移动以checkpoint为准，旧报告里初始坐标仅作识别，不照搬。**

## 下一张SoU与最终阶段方向

剩余最后一张SoU预计D23或D24，计划给**Haste2→3**，保住弩4Chilling。现Haste2速度1.175³=1.622234375、每格约0.61643T；Haste3速度1.90612539、每格约0.52462T。**Haste3两步仍1.04925T，不保证Yog1T预警内双走。**攻击、法杖、读卷与Leap仍不加速。

普通弩4→5有10%洗寒冷，4→6累计28%；Infusion保附魔每张需额外12能量，当前先前只有3且D22无Alchemy，不当成现成方案。Fireblast2已跨双充Cripple档，不需再投SoU去追并不存在的+3麻痹。

D23/D24：火把→Spawner→Strength/SoU/Food→出口，四层合计Spawner各必掉Healing/15EXP；Ripper不给杀怪经验，不刷。D25完整源计划：/tmp/spd-run-20260905/demon-halls-plan.md。

## 关键机制，避免恢复后套旧印象

- **Eye会重新瞄准。**蓄光后只要仍看到英雄，Eye.canAttack会更新beamTarget；仅横移旧线不保证安全。断实体墙/真正关门，或者离其6格视距并脱离旧线，再让它射空。已蓄光伤害承受÷4，别盲赌秒杀。每步看id/pos/seen/state/beamCharged/beamTarget/beamCooldown/time。
- **Yog与Eye不同。**Yog targeted_cells锁点，Ballistica.WONT_STOP展开整条穿墙射线；激光20–30魔法且必中，闪避戒不能靠概率躲。拳距王≤4无敌，须引到≥5；任一拳活着王无敌；最后拳死还有本体100HP快节奏阶段。别忘击杀后下去拿Amulet并完成胜利结算。
- Ripper leap_pos会预测移动方向，离实际落点，不沿原方向连续走；跳中有必中流血，鳞甲不减这份血。扑击预警后以实时time为准。
- Succubus可闪现近身；Charm时普通攻击魅惑者受阻，改法杖/走位。不要在草门边乱喷火烧到自己或毁掉遮挡。
- Fireblast2：当前充能≥4才一次耗2并Cripple4；1–3充能每发耗1、无Cripple。Cripple不取消Eye已排队激光、不阻Succ瞬移。普通麻痹需当前≥7充能，现杖达不到。
- Frost0只有Chill，陆地加2/目标水格加4回合，不自动冻结。Chill与Burning互相移除，不能把火焰持续伤与寒冷当稳定叠加。
- HeroicLeap无等级门槛，35护甲charge、落地1T，Rooted不能跳；落点可踩阱/坠坑。落地Invisibility.dispel会结束Hourglass冻结。
- Cleave2仅后续直接攻击击杀才设45回合，不追溯延长现有combo；Slam/Crush结束仍清连击，DoT独立击杀不保证触发。HoldFast3站定后停止combo/纹章盾离战衰减。
- LethalDefense1仅战技直接击杀令纹章盾CD−50，最低−150，不给HeroicLeap充能。已有负CD可通过正常纹章盾激活消耗，别忘该资源。

## 工具与恢复协议

根代理CUA会话曾自动重置，**app、clickStep已重新定义**；不能假定其他旧变量仍存在。所有UI仅mcp__cua_repl.js，禁止用OS脚本/exec模拟按键或点击。

根代理常用app=cua.getApp('Shattered Pixel Dungeon')；若工具会话真的重置，首调用必须遵守CUA初始化说明，只执行选择app这一条入口API，再读返回文档/状态。已有会话不要重复初始化或盲用不存在变量。

已采用动作保存协议：单个游戏按键/点击→getAXState确认焦点→点窗口最小化保存（此前AX按钮3，**当前索引需用新AX确认**）→getAXState→performSecondaryAction(0,'Raise')→getAXStateAndScreenshot→只读checkpoint。step(key)/clickStep(point)是在CUA内包这一流程，不是外部脚本。画面常见hero中心384,432、格约27px只是旧观察，**每次截图必须重新校准**，不能盲用。

只读检查命令：

```sh
python3 /tmp/spd-run-20260905/checkpoint.py
python3 /tmp/spd-run-20260905/checkpoint.py --map
python3 /tmp/spd-run-20260905/checkpoint.py --full
python3 /tmp/spd-run-20260905/checkpoint.py --detail 'TimekeepersHourglass|WarriorArmor|RingOfHaste'
```

脚本读取game.dat+当前depth/branch.dat稳定双文件，报告snapshot id/age；stable_pair只是文件一致性，不代表实时UI。**Hourglass活动freeze可能保存在hero.artifact.buff，不在hero.buffs列表**，必须用--detail或--full看turnsToCost/delayed presses。不要只看hero.buffs否认冻结。

当前只读支援代理/root/checkpoint_helper与其子代理caves_mobs可继续查源/路线，不操作UI。若任务压缩，先以本笔记恢复目标和协议，再读新存档截图确定真正下一步。
