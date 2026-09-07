# Shattered Pixel Dungeon 本次通关临时状态

更新：2026-09-06，最新状态以本文件下列 snapshot 为准。这是本次运行的 /tmp 交接文档，不是长期记忆。恢复后第一件事是读取新存档和当前 UI；以下坐标不能作为未来实时状态。

## 任务与边界
- 用户目标：用已打开、由当前本地源码打包的 Shattered Pixel Dungeon，战士新存档通关；允许游戏进行时读取源码与存档。
- 当前源码 `/Users/shengyifan/Workspace/shattered-pixel-dungeon`，v3.3.8 / code 896，HEAD `4a272100b`（本次已 git rev-parse 复核）。源码和游戏存档仅只读，不修改数据、源码、设置来获得优势。
- 存档根目录 `/Users/shengyifan/Library/Application Support/Shattered Pixel Dungeon/`，本次 **slot 1**。所有 UI 交互只用 `mcp__cua_repl`，不用 OS 脚本、CGEvent、AppleScript 或外部键鼠自动化。
- 每次战斗仅一个输入，随后真实保存与截图核验；新敌人、掉血、buff、陷阱、物品、楼层转换立即停下重读。安全短路段主代理采用游戏自带点击寻路；曾向用户询问后说明默认推荐方式，用户没有回复。这是现有通关授权下的操作选择，不能把沉默写成新的明确授权。

## 最近真实存档状态
- **Goo 与 Tengu 两阶段均已击败；已选择 GLADIATOR；通关仍未完成，继续推进取 Amulet。**
- 最新助手只读检查点 **5f5b294f8174**（此前切层入口为6d291f7b9444）：**D12/b0，地图宽36高48，Hero(28,22)，HP67/67，L10 exp7，基础STR14+RingMight0有效15，hunger33/450，turn1834，gold66，shield0**。Root正在操作入口南暗门，恢复时必须重读真实保存和UI。
- 装备 **Crossbow+3 Chilling、LeatherArmor+2 Thorns / Seal+1、RingMight+0**，均已鉴定无咒。护甲仍偏低，但已留1SoU，D12还可取1；喝D12Strength后有效STR16，将可将未来Plate0用Seal1+两SoU做到Plate3/STR16/DR3–25（此为未来方案，尚未找到板甲）。
- T1：HeartyMeal2/VeteransIntuition2/IronWill1；T2：RunicTransference2/IronStomach1/**LethalMomentum1**。L10已点手起刀落1。
- Quick1现在为**普通Dart×1（无限耐久）**；Quick2 Waterskin3；Quick3 Fireblast+1 3/3；Quick4 BloomingTomahawk×2耐久59.998；Quick5 Frost+0 2/2；Quick6 Healing×4。原2枚致盲镖涂层均耗尽，不能再计划致盲效果。
- **第二枚普通Dart已确认为D12(11,6)地面heap**。它在D11第一只Bat死于(19,33)深渊时落下；未跳下追，曾NE误触深渊提示已取消、无伤。D12最初6d291的game.dropped12队列后经正常场景生成，现5f5b真实heap落点11,6。该格靠近BlueShaman12,7，等南部强化后处理出口敌时顺收。
- 背包当前：ThrowingStone耐久19.996、Tomahawk×2、Dart×1、Fireblast1/Frost0均满充能、**仅Pasty1**（最后Food已吃）；Waterskin3；Healing4；MindVision/ToxicGas/LiquidFlame/Haste各1；**RemoveCurse2**、Transmutation1、Identify1、**Upgrade1留护甲**；未祝福Ankh、**Stylus1**、VelvetPouch；FirebloomSeed2、Earthroot/Swiftthistle/Icecap/Sorrowmoss/Blindweed各1；ClairvoyanceStone2、EnchantmentStone1、GooBlob2。
- 当前入口周围尚无战斗；D12初始怪由全图只读核实，但root移动后所有怪坐标必须新读。活动陷阱为Confusion23,20/Frost13,25/PoisonDart16,43，见D12专用笔记。

## D11 已完成记录
- 卖HandAxe2=120、两备用Leather各60；买BlindingDart2=225；取Gold236、IronKey、RunicBlade，卖RunicBlade=80，再买Healing=450。最终66金，以上交易已完成，旧预期余额不再待办。
- D11三初始Bat与一只新刷Bat已全部杀死。已取免费Healing（总库存4）、Haste1、SoU1、RemoveCurse1（总2）、Stylus1；Food已吃，只剩Pasty。
- D11北Shaman/Brute与哨卫房等未必清理；不为完整探索折返。D11入口金币/钥匙/武器、图书室升级和西Stylus均已取，不重复追旧坐标。
- 已确认占用快捷栏可**右键该栏**重分配；`QuickSlotButton`源码和UI都已验证。Quick1已从石头→致盲镖→普通Dart，普通左键是使用。

## 当前 D12 优先事项与支援笔记
- 完整16初始heaps/宝箱内容、全部8敌人与3陷阱、第二枚Dart落点、逐格路线见 **[caves12-notes.md](/tmp/spd-run-20260905/caves12-notes.md)**。D11历史静态资料仍见[caves-notes.md](/tmp/spd-run-20260905/caves-notes.md)。
- 连击操作与Bat/Brute源码见 **[gladiator-notes.md](/tmp/spd-run-20260905/gladiator-notes.md)**。`X`打开菜单；先核Combo的count/combotime/clobber_used/parry_used。2冲击、4撞击、6招架、8横扫、10暴雨，不是3连击招架。
- **D12无任何护甲。Strength在普通箱24,38；Upgrade22,40；Invisibility24,40。**先经南暗门28,23到入口西小房取Food18,18、StoneEnchantment18,19、Gold190于19,19、IronKey18,20；避开Confusion23,20。
- 小房建议原路东门22,19离开，再沿东隧道到暗门22,28入中央南半，避免南门19,22可能唤醒Spinner11,23。到21,32取CrystalKey前需处理Bat21,33，然后取南部Strength/SoU/Recharging20,34。
- 南晶库锁门16,40需IronKey，晶箱二选一：**明确选Hourglass+0无咒5charge(15,44)**；另一箱(17,42)是SharpshootingRing0诅咒。神射0解咒只让当前弩3普通Dart7–21→8–23，均伤+1.5，不加近战；沙漏更有利于现阶段撤离和治疗，保留两SoU给甲。
- 沙漏5charge外界时停最多10标准行动回合：可移动/开门/拾物/吃喝；近战、对敌投射即使miss、施法、读卷轴都会解时停并照常花攻击时间。外界时停期间Healing不跳tick。可喝治疗后选**自身停滞**：最多2charge换10世界回合，Hero.damage免疫而Healing仍tick。陷阱只是延迟触发，绕开晶库PoisonDart16,43。
- 拿南部奖励后原东路返回入口，经北门27,16和走廊到门20,7处理BlueShaman12,7，再收Dart11,6并处理出口Bat8,7。出口D13为7,6。西侧Spinner11,23、Brute8,29和3Piranha池可绕过。

## Tengu 与 D9 完成记录
- D9关键Food/钥匙/Identify/Frost法杖/Upgrade与炼金房Haste、5能量已取；第3张监狱Upgrade已将弩升至 **+3**。RunicTransference已补至2。
- **Tengu P1、P2均击败，出战后HP55/62，取得Boss挑战徽章**；根代理确认未踩毒镖陷阱、炸弹、电击或火墙。
- Tengu合计消耗：**Healing1、Haste1、Fireblast3发（P1两发/P2一发）、Frost2发**。战后回收Tomahawk×2（耐久59.998）及ThrowingStone（19.996）。
- 已使用Tengu面具选择 **Gladiator / 角斗士**，此步骤完成，不再找面具或重复选职业。

## 存档检查工具
运行 `python3 /tmp/spd-run-20260905/checkpoint.py`；可加 `--map` / `--full` / `--detail 'Armor|Spear|Wand'` / `--radius 8`。默认 slot1，附近距离是切比雪夫距离，不是FOV。短状态通常约800字符，物品多时会更长。
- 装备 `[+1K,c0K]` 表示等级+1已知、无咒已知；`?` 是角色未知，字段仍展示获准读取的存档实值。
- `--max-age 5` 拒绝过旧存档；`--wait-new --timeout 30` 必须先启动，再触发生命周期保存。
- 底层复用既有只读 inspector：`.spdtmp` 闸门、按game depth/branch选楼层、完整双读 inode/mtime/hash、一致性与保存年龄。稳定双文件没有共同事务ID，不能把稳定旧档当实时。

## CUA 控制循环（主代理已有的持久会话）
- `app = await cua.getApp('Shattered Pixel Dungeon')`。
- `step(key)` 实现次序：`pressKey` → `getAXState` → `click(3)` 最小化促成保存 → `getAXState` → `performSecondaryAction(0,'Raise')` → `getAXStateAndScreenshot`。`clickStep(point)` 用点击代替键盘，后续保存/恢复相同。
- 上述是主代理已有 helper 的行为摘要，不保证会话变量在重置后存在。重置后须遵循 CUA 入口文档，仅选择 app，再检查返回 API 和当前 AX。
- `getAXState` 确认真实应用/焦点；通常 hero 屏幕中心约 (384,432)，地砖27px，但每次必须按新截图重新校准，不能无条件沿用坐标。AX编号也应结合当前getAXState确认。
- 工具最小化产生的存档才是动作后检查依据；焦点切换或 Esc 本身不是保存证明。截屏出现新遮挡/弹窗/切层时停止路线。

## D4 冰阱已经解决
- **(13,36) ChillingTrap 已被石子触发，现 active=false/visible=true。Freezing 总体积已复核为0，英雄无损通过。** 不需要重复处理或再等待。
- 曾否决的方案要记住：从(14,34)瞄准(13,36)，Ballistica会先经过墙(13,35)，即使压住门(14,35)也会撞墙。最终从门附近触发后退(14,34)，逐步等Freezing归零才通过。
- Door.leave 确实在门格有HEAP时不关门，但压门不能修复撞侧墙的投射线。

## D9路线与全部重要loot历史（关键强化已完成；勿重复执行）
- 唯一Upgrade **28,14**；出口房箱**19,5=WandOfFrost+0无咒，初始2充能**，值得Tengu前取得。无力量/护甲/戒指/神器。唯一地面武器Scimitar+0无咒29,28，tier3 STR14，没必要替换Crossbow+2。
- 铁钥匙12,2和30,16。Identify12,7；Food6,18已收；Rage2,18；炼金锁门28,21，室内Haste27,23/能量晶体5于27,20。入口南锁门7,28内Garden有Sungrass5,32，Foliage是花园安全遮蔽效果，非毒气。
- 推荐西北路线：当前6,17利用柱5,17/4,18/5,18挡DM远射。此前探6,16促醒后退6,17成功让DM追到5,16邻接，现HP9待击杀。
- 杀后6,16→7,15→门7,14，沿x7北到7,7；不要误入门11,11的中央岛3怪房。门8,7内DM9,6睡，开后退7,7关门，等其踏8,7近战。
- 再取Identify12,7，沿x12北到Key12,2。去Frost箱前站**16,5**柱17,5西侧，探16,4远距促醒Guard22,5后退16,5，等其从17,4绕柱到邻接；切勿站**PoisonDart17,6**或**隐藏Toxic19,7**。
- 拿Frost19,5后：20,6→21,7→22,7→23,8→门24,9→25,9→26,9→27,9→28,10→28,11→28,12→门28,13→**Upgrade28,14**。再29,15→**Key30,16**，原路回28,14/28,13；墙x31/y14..16隔开东侧Skeleton32,14/Necro33,16，**不踏y17连通水带**。
- 返回出口房可沿y9从24,9走到**17,9停**，再单独下Tengu17,8。全程绕开19,7/17,6活动陷阱。
- 第3陷阱是隐藏Toxic4,10，西北路线沿x7，别往4,10走。其他没有陷阱。
- 剩余敌初始：DM9,6 HP20；Guard22,5 HP40；Skel32,14 HP25/Necro33,16 HP40（升级东隔间）；中央岛Guard15,12 HP40/Skel13,14 HP25/Necro14,17 HP40；下方Thief28,27 HP20。除当前DM外均睡。关键loot路线可跳中央岛、东隔间、下方Thief。

## D8关键loot历史（已完成）
- 全12个heap已查，本层**没有Upgrade**。关键坐标是**Strength29,14、IronKey31,12、RingOfMight38,14**；不是初看ASCII猜的28,14/32,12。
- **RingOfMight +0，cursed=false，角色未知，外观agate**。无STR穿戴要求，实际+1STR、最大HP乘1.035。喝本层力量后基础14，戴戒总15；备用Chilling Crossbow升+1即需STR15，可考虑作为后续强化。这里只读评估，尚未获取/穿戴/升级。
- 锁房门29,21，Armor28,25为**LeatherArmor+1无咒Viscosity，STR11**，不优于现+2Thorns。另Tomahawk×3 +0无咒Blooming(27,24)，tier4投掷STR15。无其它主武器/法杖/神器。
- **StoneOfEnchantment25,24**在SecretRunestoneRoom，前半Clairvoyance Stone22,20及24,21。x21..25/y22的`b`是BOOKSHELF，非骨堆，要烧书架才能进后半；有Fireblast可另行审查灭火/残火安全，不用专门买燃烧药。
- 入口先13,12→12,13→12,14→13,15，现有墙挡中央DM射线；不要自动跑到18,15使中央群同时看见。**AlarmTrap12,9隐藏活动**，别在西门外直往北踩它。
- 中央两DM和两Skeleton已清。**力量房北门精确为30,13，29,13是墙**（早期ASCII误读已纠正）。Key31,12；Strength29,14。当前Thief30,14 HP20/HUNTING，先处理再拾力量，可退30,12让门关闭后等其踏30,13邻接。
- 力量房后经30,15→31,15→Gold160于32,15→33,15→34,14→35,14→**36,14**，来到Guard柱西侧。墙37,14挡Guard39,14直链。
- 若Guard仍睡，可仅探到36,13促醒后立即回36,14；其绕柱走38,13或38,15时，对36,14的Ballistica先撞37,14墙，走到37,13/37,15才与英雄邻接，canAttack=true时不会链。这个方案绑定其实际位置，若改变路线则重读，不在36,13等远程链。杀后37,13→Ring38,14或37,15→Ring38,14。
- **Ring旁Guard39,14 HP40，chainsused=false**；准备柱角接战，别隔两三格直线曝露被链。**GrippingTrap39,12隐藏活动**在出口39,11南一格，不要从正南踩着它下楼。
- 其他敌Necromancer8,18 HP40，西南支路非必要不接。其他隐藏陷阱Chilling5,4和13,3。全层无植物/活动blob。
- 剩余物品：Food40,13；Gold160在32,15；Frost4,9；LiquidFlame38,9；两个Clairvoyance与Enchantment如上。D9出口39,11，过渡前需重读Guard/陷阱。

## D7往D8路线历史（已完成）
- 推荐东侧已走路线，处理游荡DM100一只。西侧绕PoisonDart26,7到旧三骷髅房虽约35步，但贴近全部3只；东路处理DM后约36步到出口矩形外，几乎不多走。
- 先32,7→32,8→门33,9→32,10→32,11→32,12→32,13→**32,14门前停**。门32,15通旧升级房。DM正从24,25往29,19，可能此时进房，必须新读后再开门。
- DM100射线无额外固定距离阈值，AI看见且MAGIC_BOLT直线可达即可远射3–10；不能把隔4格视作安全。若已在房内，可开门让它看见后立即退32,14关门挡线，等它追到32,15后邻接近战。门不能有HEAP压住，否则不关；每拍检查实际terrain/monster位置。
- DM处理后，经31,16→30,17→29,18→28,19→门27,20→26,21→26,22→26,23→26,24→25,25→24,25→23,25→22,24→门21,24。
- 然后20,23→19,22→19,21→18,20→17,19→16,19→15,19→14,19→13,19→12,20→11,21→门12,22→13,22→**14,23**停，下一拍独立下楼14,22。
- 这段从上方绕过原DM100(16,26)，保留其房门(18,26)关闭；别沿y26贴近它，别把21,28的旧钥匙/花园路线当去出口路线。
- 其他三Skeleton仍旧睡，不必唤醒；入口26,7毒镖、爆炸阵门34,15、PitRoom晶门30,9继续避开。

## D7腐莓回程历史（任务/Guard/种子已完成）
- 先取Swiftthistle(24,39)，当前24,40→24,39与Guard之间有24,37/24,38/23,39墙阻挡，但行动后必须重读。
- Guard目标22,39，预计会走24,36→23,37→22,38→22,39，正经过Icecap种子，不要现在连点23,37。园南口袋向北的窄点23,37/24,36在它路上，不能认为能无风险绕行。
- 可用防链墙角**(23,40)**：从Swift退24,40→23,40。Guard在24,36/23,37/22,38到该格的Ballistica分别被24,37/23,38/23,39墙挡；它到22,39时已斜邻，canAttack=true不会用链。适用于它保持此路线；若目标/位置变化要重算。
- Guard链一次性：chainsused=false且看到英雄、距离<5且不能近战时，可沿无阻Ballistica拉到自己附近并Cripple4。不要站22,38迎面被拉。Guard给7 EXP，当前28/35，杀它恰好升L7。
- 地面种子：Swiftthistle24,39；Icecap23,37；Sorrowmoss23,35；Blindweed21,35；Firebloom20,38，均尚待收。若Guard清掉，可从南往西取Fire20,38，再22,39→22,38→Icecap23,37→24,36→Sorrowmoss23,35→Blindweed21,35→22,34→23,34→门24,33离园。
- **(22,30)BurningTrap已处理，active=false/visible=true**，可回读后正常通过；(15,35)仍active=true/visible=false，别为Gold118踩它。
- 返回Wandmaker之前Rotberry不要种植/炼金消耗；按已确定任务方案领取Fireblast+1。

## D7初始loot/危险历史（关键强化与腐莓已完成）
- 没有力量药水、地面主武器/护甲/戒指/神器；法杖来自Wandmaker任务（另agent在查奖励）。Upgrade(29,18)为本层主要固定强化。
- 从入口30,6→31,7→32,8→门33,9→32,10，沿x32南到门32,15，共9步地形，门前停查Thief(29,16)HP20/SLEEPING。清掉后31,16→30,17→Upgrade29,18。避免第二次偷弩后失控长追。
- 门34,15通爆炸阵，x34..38/y16..19共20格ExplosiveTrap，箱36,20仅IronKey，不值得进。另两个隐藏BurningTrap为**22,30**（腐莓路线）及**15,35**（南部金币）。
- 升级房西门27,20→26,21/22/23/24→25,25→24,25→23,25→22,26→22,27→门21,28→20,28→19,29→18,30→17,31→**IronKey18,32**，约17步地形。避开22,30，不从该隐藏火阱上直走。接近南Skeleton16,33时停。
- 入口晶门30,9后为PitRoom（rooms已确认），骨堆29,12 **haunted=true**，内容CrystalKey+Retribution+**Bolas×3,+1,cursed=true,Sacrificial**。钥匙在室内、需上层弱地板掉入，收益低且有怨灵，跳过。
- 南骨堆12,32 **haunted=false**，仅Teleportation卷轴。附近Pasty12,34、Gold118于15,36（北格15,35隐藏火阱）。东北Treasury锁房门28,30，箱30,33 Gold141、箱32,34 Gold156，只有金币。
- Levitation(18,5)，Invisibility(10,12)。入口西群Skeleton17,12/17,13/13,11均HP25，非必要不要同时引。Guard11,27 HP40（chainsused=false），DM10016,26 HP20，南Skeleton16,33 HP25。
- RotGarden锁门24,33，RotHeart25,41 HP80，RotLasher21,35/23,35/27,36/25,37/20,38/24,39均HP80；这部分交任务agent，不盲入。
- D8出口14,22，在西侧牢房布局中；前往先以任务/loot完成情况定路线，别直接穿转换格。

## D6中央取物历史（已完成）
- 新骷髅最新(31,30)已在中央房，而非最初(26,28)；保持西门(28,30)DOOR关闭，将原(27,33)睡眠Skeleton隔在出口房。先在中央房单独处理新骷髅，别为了引它重新跨进出口房。
- 它目标(34,8)正向北游荡，可能沿桥迎面遇到英雄；如接近东房门(40,24)，可退(40,23)用该门，必须单步核对是否已看见英雄。不承诺固定格一定偷袭。
- 新骷髅处理后取投石(29,32)，可顺收1金币；**Purity箱(33,30)未开**。中央130金箱(29,30)已经收完，不要重复点空格。
- 再从(29,30)开西门(28,30)，在门东侧逐步引原Skeleton(27,33)，先清单只再入房。**免费Frost(26,28)**仍在，出口房另Gold108(22,28)，出口(25,32)。
- 入口东房**AlarmTrap(40,28)**仍隐藏且日记页压其上；Stylus(41,28)暂缓。不要在回路误踩。
- 两骷髅的骨爆伤害6–12，护甲DR对此双倍；单操作回读。

## D6火墙/商店历史（火墙loot已取）
- 火墙原在x26/y3..9，现已熄；Upgrade(24,5)，Food(23,5)/(25,3)两份已收。以下仅保留本次成功操作的源码依据，勿重复执行。
- 门(29,9)，隐藏BurningTrap(28,8)。站(29,9)向水格(27,9)扔Frost，弹道29,9→28,9→27,9无墙，英雄距落点2在冻结范围外。任意一段EternalFire接触或邻接Freezing就会整条清除。
- 投后原位单回合等Freezing总量0，才经(28,9)/(27,9)/(26,9)过墙。不要为上绕走(28,8)。
- 商店另售Frost(32,5)、Healing(32,10)、SandalsOfNature+0无咒(32,6)、PotionBandolier(38,10)。只记录可选货品，不表示已买。
- 中央落石金币房x20..27/y20..25有大量可见RockfallTrap，跳过；西北房隐藏ToxicTrap(3,11)，Skeleton(4,15)，麻痹气药水(9,16)。Swarm(12,25)仍睡眠。底部弱地板房(29,41)的w是井标记，不是装备。

## D5鼠王金币与出口历史（已完成）
- 最近站(9,29)，搜索揭露暗门(9,30)，过门到(9,31)。11个箱全部普通、无幽灵的金币CHEST，总计**192金币**，全收时353→545；不要将箱子打开即视为已捡金。
- 外圈遍历，不必碰RatKing(8,32)：(8,31)21金→(7,31)13→(6,31)19→(6,32)24→(6,33)25→(6,34)15→(7,34)11→(8,34)19→(9,34)12→(9,33)19→(9,32)14→回(9,31)。每箱独立打开，再点击地上金币走上拾取，逐次保存检查。鼠王interact会从SLEEPING变WANDERING，可能堵路线，没必要叫醒。
- 从(9,31)回(9,30)/(9,29)，沿x9北上至(9,19)，过门(9,18)后经(8,17)→(7,16)→**(6,15)**，共约16步地形路径，结束在出口矩形外。
- 出口中心(6,14)实际是LOCKED_EXIT，尽管ASCII图因transition覆盖显示`>`。从(6,15)对(6,14)交互开锁，自动从Notes消耗D5 WornKey并改UNLOCKED_EXIT，耗1回合；开锁不会直接代表已下楼。随后单独执行D6切层，重读新地图。
- 出口transition矩形为x5..7/y13..14，先停(6,15)；不要普通寻路无意跨进转换边界。

## D4已完成路线历史（不要当当前目标）
1. **左Strength/Upgrade/Gold111/ToxicGas/Earthroot已收，两Slime已死；两张升级已用在现在+2HandAxe；Swarm和全部分裂体已死。不要重复寻旧物品/旧怪。**
2. 先补3 EXP升L4再Goo：Crab(20,26)给4 EXP、Gnoll(23,15)只给2 EXP。当前优先前者，不为雕像再开战。
3. 由(13,19)→(14,18)→(15,17)沿y17向东到(19,17)→(20,18)→(21,19)→(22,20)，沿x22南下到(22,24)再核对蟹。地形段约13步，途中无存档活动陷阱。蟹周围(18..21,25)和外围多高草，可利用遮挡但不能直接断言下一击必定偷袭。
4. 蟹baseSpeed=2，移动很快；不要以普通速度持续后退放风筝。它攻击延迟不因此变成半回合。+2手斧/+2荆棘皮甲可就地逐击检查。
5. 杀蟹后沿x22北回(22,19)，经(21,18)/(20,17)到门(20,16)；从这扇门准备处理楼梯房Gnoll(23,15)。绕开活动飞镖陷阱(23,13)，从x20..22侧前进，铁钥匙(22,11)在楼梯(22,10)南一格。到楼梯前单独确认装备、HP、饥饿、天赋与Goo准备。
6. 雕像保留评估但不是当前必要目标；避免因可见奖励触发其战斗。

## D4 装备、重要战利品与源代码边界
- 唯一新武器：**Statue (27,10)，HP35，PASSIVE，持有 +1 Shocking Spear，cursed=false**。力量需求11，基础伤害3–24，攻击耗时1.5，射程2；雕像同样使用它攻击，有基础防御8、攻击技能13、0–4减伤。死亡才识别并掉落长矛。
- 雕像 INORGANIC 明确免疫 ToxicGas / Poison / Bleeding；不要用毒气或流血计划磨它。房门 **(29,11)** 锁门，铁钥匙 **(22,11)** 在D5楼梯房，楼梯本身 **(22,10)**。
- 全层无其它主武器、法杖、戒指、神器；所有16个heap/container和雕像装备均已读。
- 右侧悬崖4锁箱：**(28,28) Transmutation；(30,28) Recharging；(28,30) Purity potion；(30,30) RemoveCurse**。四金钥匙在(26,26)/(32,26)/(26,32)/(32,32)。无装备，暂不优先。
- 对应 **Levitation potion (21,40)** 确有，charcoal；同南房 **Food (24,36)**，但初始有Gnoll(25,38)HP12和Crab(25,39)HP15。
- 东北 **Chest (32,5) = Transmutation**，旁边初始Slime(33,6)HP20，优先级低。
- 另一陷阱仅 **WornDartTrap (23,13)**，可见/活动。此前没有植物或其它活动blob。
- 尚存怪最近仍为：Crab(20,26)HP15；Gnoll(23,15)HP12；Slime(33,6)HP20；南房Gnoll/Crab如上，均睡眠；Statue仍被动。这些坐标仍须重读。Swarm及左两Slime已清。

## 已完成的只读审查摘要
- D3 无装备；已取东房(36,18)Upgrade和(36,20)MindVision，通过(21,28)入口暗门绕过召唤阵。D3其余大多跳过。
- D4强化完成未碰雕像；D5 Goo/鼠王完成；D6关键强化完成；D7腐莓任务完成；D8关键loot已收，弩升+2、Might+0、基础STR14实际15。当前D12 L10 Gladiator，D11关键loot已收；当前主线见文件顶部。胜利目标尚未完成，继续玩，不要把阶段性报告当最终回复。
