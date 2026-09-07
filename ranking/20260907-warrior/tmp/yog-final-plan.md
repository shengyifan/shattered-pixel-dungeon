# Yog 最终战只读准备（等待D25实图，不预定拳种）

本地v3.3.8/code896源码复核，root操作UI。以L20、HF3/Cleave2/LD3、ChillingCrossbow4、ThornsWarriorArmor6、Haste3/Evasion3、Heal至少9、Hourglass至少2、Leap约85为起点；D24完成最后巢与STR后再读实际库存/生命/图。不要把本文件的准备状态当作D25实时存档。

## 入场准备与经验/T4事实

- 四个血巢全拆，核Statistics.spawnersAlive=0；普通Yog召唤循环全是Larva，不会夹Ripper。D24最后巢应再给Healing，预计入场10瓶，必须按实际袋中数量确认。
- 基础STR20，满115HP，照明保持；D24北火把应使备用Torch2。可在D24安全位置整理/吃饭/等自然少量充能，但不要在D25封门后无限空等：LockedFloor初始50T，无对Boss/拳的有效伤害会逐渐关掉有益被动回复，不能靠封门后无限等沙漏。
- **L20没有T4点，Yog拳与幼虫实际不给经验。**YogFist虽EXP25、Larva虽EXP5，但maxLvl=-2，Mob.die实际经验判定为hero.lvl<=maxLvl，故都为0。若D24只杀巢，将L20exp27入场，Yog本体最后给50仍只有exp77/105，不会自然到21。不要为了T4而等杀拳升级，也不必额外刷楼层。
- 若实际意外已有L21点，首点HeroicEnergy：Leap35→30.8，对每次跳有效；后续仍优先此项。DoubleJump1只对3T内第二跳省16%，当前谨慎单次避险不如全程减耗。BodySlam1伤害很低；ImpactWave可能把拳推回距Yog≤4的无敌区。当前L20默认无需任何T4操作。
- HallsBossLevel在离入口Chebyshev距离≥2时封门并创建Yog；先在入口确认当前资源/状态，再进。Yog phase0在英雄看见它时notice进入phase1，之后才开始技能。

## 激光：每动作读完整射线，伤害会推进下一次技能

建议用新只读助手：`python3 -B /tmp/spd-run-20260905/yog-lines.py`。它从当前game/depth读取Yog的targeted_cells，按同版Ballistica整数误差法展开WONT_STOP全线，输出Hero是否在线、每邻格是否在线/地形/怪占用/火毒与Yog.time−Hero.time。没有Yog时只返回不存在，不编造结果。保存仍须与root当前画面对应。

- **与Eye不同，Yog预警目标固定，不会随英雄横移重新瞄准。**但每条线贯穿整图、穿墙、穿角色，且超出原瞄准点；红点本身不是唯一危险格。羊/柱子/门都挡不了Yog光。
- `Yog.time − Hero.time`表示接下来Actor队列何时轮到Yog，**不是英雄位置变化前要等的时间**。Hero.getCloser先move(step)改变pos，再spend步时。一次合法移动到所有旧线外，即使步耗大于delta也能躲已固定这轮光。若两步中的第一格仍在线，需要比较**第一步耗时**是否小于delta，第二步位置会在其自身耗时结算前改变；不能拿两步总耗时来否定它。Haste3一步0.5246245T，Cripple下一步1.049249T，它增加后续怪物行动数，但不使一次直接脱线必然失败。Roots/Vertigo/占位/错误落点另算，必须真能移动到预期格。
- Yog攻击必中，Evasion3不能躲，普通物理甲不减。普通难度每轮20–30魔法伤害。源码用HashSet<Char>合并所有线，**同一轮交叉多束仍只对同一角色结算一次**，不是3×30；依然应离开全部线。
- 初始1束，HP≤600后2束，≤200后3束。生成时若所有相邻可通行地形都在线，会移除最后一束，但它不考虑怪占位/火/毒/羊，不能据此保证有实际安全空格。
- 对Yog造成每10实际伤害会减少abilityCooldown与summonCooldown各1T；别拿上一次“CD还剩5”去连打高伤。一次攻击可能立刻推来新预警。最后阶段甚至同一Yog act发旧光后立刻标新光，读回新targeted_cells。
- **英雄Rooted时Yog会暂缓已预警光的发射。**但解除Roots（Blink、Cleansing、Levitation或自然结束）后立即恢复常规发射可能性，不能原地喝解根就假定多出1T。Blink落点必须避所有旧线；Hourglass冻结后再解根、移位最稳。
- 闪避优先级：一个真实可走安全邻格→已核落点的单次Leap/Blink或Hourglass多步脱离。Leap先落地再spend1T；Blink也在user.next恢复Actor队列前完成落点，所以对固定Yog旧线，单次直接移到线外不要求delta≥1。它们仍受墙/占位/Roots等限制，且动作之后可有拳击、新预警与其它伤害；不是任何伤害都无敌。不能只因delta小就多花冻结。

## 本体与拳的阶段规则

Yog1000HP，700/400/100依次生成三拳，正常每对选一并乱序。**任何拳活着时Yog无敌；拳自身距锚点`exit + 3*width`≤4时无敌**，必须把拳拉到Chebyshev≥5。别只看英雄距Boss够远，要看拳的实际格。

拳新生成`GameScene.add(fist,4)`有约4T行动延迟，阈值切阶段会把技能/召唤CD至少抬到5，可用于向外站位，勿在拳还受保护时倾泻资源。各拳300HP、物理基础18–36、攻36、防20、DR0–15；普通拳远招CD8–12，Bright/Dark例外无远招CD且邻接改物理。

Yog是STATIC：免Chill/Frost/Slow/Paralysis等控制，寒冷弩和冰杖不会延迟本体激光；控制留给拳/幼虫。Yog激光不会伤同阵营拳和幼虫，别复用D24“Eye友军互射”技巧。

|拳|实际关键机制|本局优先处理|
|---|---|---|
|Burning|FIERY免Burning/Blazing，额外免Frost冻结；不免Chill。Fireblast伤害减半。每次act蒸发脚下及部分邻水并补3×3火，远招烧英雄周围|用Chill弩/Dart或Frost减速、保持真实安全落脚；不能把水永久当Aqua治疗格，火拳会蒸干。飞行不防火。|
|Soiled|3×3每格高草/犁草减伤1/6，6格可几乎无伤；可挂Burning但Burning伤害为0。远招命中缠绕3T并种草|Fireblast/Flame烧草，等地形真清掉再物理；保留Levitation或Blink解根。不要用纯燃烧DOT作为主输出。|
|Rotting|直接伤害转为0.6×伤的Bleeding，新流血取更高值而非叠加；站水每次act回6HP。毒气免疫，攻击50%施Ooze，远招在英雄格放100毒气|把拳拉离水；Slam大单击很好，已有高Bleeding时可控位等结算。它不像Acidic蝎有相邻反酸，酸来自攻击Proc；中酸用水/Cleansing，离毒气。|
|Rusted|22–44物伤；所有正常伤害进Viscosity延期池；INORGANIC免Bleed/Poison/Toxic|读延期池而非只看HP条，Thorns无流血输出；Chill/Fire可用，控制后等结算。LARGE会受窄地形限制，但先确保其已出Yog四格无敌区。远招必中式施Cripple4T，别仍按0.525步时算。|
|Bright|每次远射10–20魔法+Blind5T；邻接改物理。跨过半血且没被杀会置150HP、传送、Blind15T；死亡Blind30T|Leap/走位接近，在甲保护下近战。半血后可用MindVision找位置/Heal清盲，不凭旧屏幕点；Cleansing5T只能短时免新盲。|
|Dark|每次远射10–20魔法且Light削50T；邻接改物理。半血传送并移除Light，死亡再次移除Light|贴近压远射，备用两火把处理半血/死亡。传送后用存档和MindVision重获定位。|

## 资源如何花

- **Healing**：HT115一瓶总106回血，首tick约27，然后约20/15/11…逐步回，不是即时满血。Healing act优先于怪物/伤害buff，但必须在危险前喝；同时面对拳和下一轮光或DOT时约70HP就用，普通受控近战约60可用，不等到30。先走出当前必发射线再花1T喝，或Freeze→喝→走到安全格→取消（冻结期间回血tick本身不前进，别把未结算HP当已到账）。会立即清Blindness、Cripple、Bleeding、Poison、Weakness、Slow、Vertigo等；**不清Ooze、Burning、Roots**。连续喝不会叠加两份总量，只取较大剩余，所以默认等前瓶结算/控位，除非实际急救需要再喝。
- **LD3纹章**：HP≤57或此次伤害会降到半时自动给12盾，现−150预存可连续激活两次。后续Slam收20HP幼虫每杀重置150CD，保持盾循环；别为保持负CD而放弃必要治疗。Larva物伤15–25、DR0–4，甲+HF能很好处理，仍会堵避光格。
- **Food/IronStomach2**：正常时间吃饭的整个吃饭动作免伤，可在不宜移动的一轮预警时吃饭接光并补饥饿；低于1/3HP另HeartyMeal2回6。**冻结中吃饭可能因hero.cooldown()==0而不给FoodImmunity，不能依赖它。**
- **Hourglass**：每充能2英雄动作T；Haste3下每充能可走3步（4步2.098T会用第二充能）。真正无安全邻格时直接Freeze，逐步退出所有线再取消。攻击、法杖、读卷、Leap、Runestone会解除；普通药/种子投掷不会主动解除。有限充能优先交叉光/被堵/解根，不为普通单线浪费。
- **HastePotion20T**：叠Haste3后一步约0.174875T；多步能否在旧光前脱离，比较最终脱线那一步之前已消耗的时间。六步位置可以在第五步累计0.8744T后生效，不能只因六步总耗时1.0493T就断言赶不上1T。药不加快普攻/喝药/投石。适合最后拳快死前或追Bright的安全时段；已站预警线上时不能原地喝药代替移动，可以先Freeze再喝再移。
- **Leap**：当前35充能，每次1T，落地触发地形，Roots下不可用；能跨角色但最终有人会往回找落点。用于跨火/怪堵或贴Bright/Dark，既看所有Yog射线也看实际落地格。
- **Blink1**：可解除Roots、瞬间位置变更，但投掷动作仍约1T且会解冻，途中敌/羊/实墙可截落点。只投实际PROJECTILE可到且不在线的安全地格。不要把它当无条件穿墙跳。
- **Cleansing1**：清所有一般负面并5T免新负面，还清饥饿；优先Ooze+Cripple/Blind叠加或草拳解根。若正在因Roots享受Yog延迟发射，先冻结或准备安全位再清。
- **Levitation2**：20T，立即解除Roots且飞行期间Roots不能再附着，适合Soiled；同时不能靠水洗Ooze/Burning、不能Aqua回，因此Rotting战慎用；它本身不防火。
- **Aggression2**：投到已经离开四格保护的拳上，附近看见它的非睡眠怪会转火5T；转火伤拳只有一半。最后phase5可标Yog让2–3只爆发幼虫转火，伤Yog为原四分之一。**不会停止Yog激光**，拳活时标无敌Yog只有分散小怪作用，优先标可受伤拳。
- **Flock1**：按可扩散路径半径2生成羊，寿命6–10T，能挡拳行走/普通射线，但也挡自己走路和投射。远离自己的躲光空间使用，不在自己四周造笼。羊不能挡Yog激光；Yog会杀相邻羊继续召唤/出拳，所以不是封Boss办法。
- **Swiftthistle2**：是另外两份约6T时间气泡，但需先投到安全邻格种植、再踏上才触发；种子投掷本身1T，不是菜单里一按即生效。怪也可触发并得到自己的气泡，火/激光可毁植物，不预种在敌即将走的格。安全技能间隙可种近身安全格，下一步踏上；攻击/法杖等同样会结束气泡。Hourglass中踩植物press会延期，要等沙漏结束才触发，不能假定立刻双层叠加。
- **Blindweed1**：能盲拳10T抑制远射，但需种在其实际脚下再硬press触发；可复用Freeze+Seed+Flame，仅在安全CD/可控落点实施，不为复杂控制错过当前激光。对STATIC Yog的盲不停止直接瞄英雄pos的技能。
- **MindVision1**：20T，留Bright/Dark半血传送后找拳；不等于照明、不清Blindness，但会揭示怪位置及相关周围格。是否可选中目标要看当前UI/FOV，不只凭存档全图。
- **Retribution1**：只作用于英雄当前FOV怪，越残血越强，Boss属性减半；自己附Blind10+Weakness，读卷会解冻。不能当满血清场键，也不能为增伤故意压到濒死。对最后100HP Yog即便最强也只有约95伤，不能宣称一读必杀；在较低Boss血量且安全窗口可作远程收尾，或配合Heal清副作用。
- **Transmutation1、Enchantment2**：不临战随机改掉已经可靠的Haste/Evasion/Chill/Thorns。当前通关资源足够，无需随机赌装备。

## 最后100HP与真正终点

最后拳死亡后phase5：Yog仍100HP，summonCooldown=-15会立刻补2–3只幼虫，激光CD上限2、召唤上限3。准备安全格/沙漏/Haste，继续每动作读线。若自然积累Combo10并保留到本体，Fury一次攻击时间内多次0.6武器伤可迅速收尾；没有10就用已有Slam/正常攻击，不为攒数在交叉线站桩。

Yog死后清掉普通召唤、解封出口；仍要进D26拿Amulet并在实际结算选择结束冒险。D26没有新怪，不默认额外做上行挑战。读游戏胜利/Amulet结果后才汇报通关。

源码：YogDzewa.act/damage/addFist/processFistDeath/storeInBundle；YogFist各子类；HallsBossLevel.occupyCell/seal；Char.Property和hit/damage；Mob.die；PotionOfHealing/Healing；LockedFloor；StoneOfAggression/StoneOfFlock/StoneOfBlink；Swiftthistle；HeroicLeap/ArmorAbility；Food天赋与Hero.damage。
