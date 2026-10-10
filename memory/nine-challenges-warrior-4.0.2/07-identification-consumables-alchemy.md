# 07 — 鉴定、药剂、卷轴、符石与炼金：九挑战战士

适用源码：游戏 4.0.2 / code 922，研究基线 `8405ae82f`，2026-10-10。这里记录**静态源码确认的规则**及明确标出的战术推论；没有启动游戏、试喝、读取个人存档，也没有证明某套资源组合在实际九挑战局必定出现。源码路径相对于本文件；实战物品名、已鉴定状态、数量与可执行操作一律以当前公开观察为准。

名称约定：表格优先使用当前[简体中文物品词条](../../core/src/main/assets/messages/items/items_zh.properties#L749)，正文偶用机制短称；普通 **净化药剂=Purity**，异种 **全面净化合剂=Cleansing**，二者务必区分。当前源码成品目录是准据，翻译文件仍可能保留已移除物品词条（如信使结晶、拆除符石），不能据词条把它们当当前可用道具。

## 先记住这些高价值结论

- **治疗瓶不能喝，异种护盾瓶也不能喝。** `PotionOfHealing.apply` 先清部分负面，再经 `heal` 在药物恐惧下施加毒；`PotionOfShielding.apply` 也直接调用同一药恐惩罚。毒参数是 `4 + lvl/2`，不能把“约 40% 最大生命”的源码注释当精确结算公式。[治疗实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfHealing.java#L52)、[护盾实现](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfShielding.java#L41)
- **全面净化合剂（Cleansing）是九挑战的重要食物与解控资源。** 普通净化药剂 Purity 变成异种 Cleansing，耗 4 能量。Cleansing 驱散多数 NEGATIVE buff，例外是 AllyBuff / LostInventory；直接 `Hunger.satisfy(450)`，并给 5 回合拒绝多数新负面 buff 的 Cleanse。`Hunger` 本身没有饱腹值除三，节食的除三发生在 `Food.satisfy`，所以此路径能把正常封顶 450 的饥饿清零。不能因此宣称它消除地面的气体、火或所有直接伤害。[cleanse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L71)、[Hunger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L133)、[Food](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L114)、[新负面拒绝](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L1221)
- **治疗瓶仍是原料。** 可以分解为 6 能量；也可与任一种子、1 能量合成不稳定合剂。后者饮用时在药恐下显式排除治疗，且重抽所有应投掷的危险药，结果是随机有益普通药效果；不会揭示被模拟药的颜色。不要因此把“随机有益”当作指定的隐身/极速急救。[UnstableBrew](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/UnstableBrew.java#L67)
- **炼金分解可以安全鉴定颜色/符文，但消耗这一份。** 能量先按当前未知价值计算，再鉴定；未知力量/经验/升级/嬗变不会先给已知后的 10 能量。不要拿稀少的升级或力量候选随意做“实验”。[WndEnergizeItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndEnergizeItem.java#L152)
- **盲读卷轴不等于安全。** 狂怒招全层敌人；报复让自己虚弱失明；催眠让自己也昏睡；传送可能把自己送到未探索处。盲喝在九挑战下尤其不合算：未知治疗也是毒，未知火焰/麻痹气体会在脚下生效。[Potion.apply](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L332)、[Rage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRage.java#L45)
- **不要用旧版“护甲/武器都靠走路鉴定”“一石一次猜”等记忆。** 当前鉴定有经验补充的使用配额；感知符石每两次确认猜测消耗一颗，无论猜对猜错。战士的 Veteran's Intuition +2 可穿上即鉴定护甲，未鉴定物的诅咒风险仍需先处理。[Weapon](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L123)、[Intuition](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfIntuition.java#L113)、[Talent](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L683)

## 鉴定：证据层级和实际决策

### 颜色、符文、宝石与物品个体

`ItemStatusHandler` 对每一种类随机分配一个未用标签，并分别保存标签映射和“种类已知”。同一局的同色普通药、同符文普通卷具有同种类；不能跨局沿用颜色。正常保存/继续会恢复既有映射，不是重新随机。异种药/卷与各自普通版本共享这一种类识别，转换本身不会自动把未知种类变成已知。[分配](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/ItemStatusHandler.java#L44)、[保存/恢复](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/ItemStatusHandler.java#L82)、[ExoticPotion](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/ExoticPotion.java#L91)、[ExoticScroll](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ExoticScroll.java#L87)

药/卷的种类识别是整局共享；戒指的宝石种类识别与**该枚的等级、诅咒**分开。感知石、预知卷揭示戒指种类，不能顺便保证这一枚无诅咒或是 +2。武器、护甲、法杖外形种类通常已公开，未知的是个体等级/诅咒/充能状态等；“蓝色有益魔法”也不能反推出确定升级数。

| 方法 | 真正得到的知识与成本 | 九挑战建议 |
| --- | --- | --- |
| 鉴定卷 `ScrollOfIdentify` | 对一个未完全鉴定物调用 `identify()`；若携带遗忘碎片，武器/护甲/戒指/法杖改成“可鉴定”状态而非自动完整揭示 | 优先解决会决定升级投入或诅咒安全的关键装备；不要为普通已确定来源药浪费。见 [IDItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfIdentify.java#L57) |
| 感知符石 `StoneOfIntuition` | 猜未知药/卷/戒指种类，正确才揭示；第一猜设置全英雄 tracker，第二猜耗石并移除 tracker；猜错也计数 | 先用房间/来源/排除法缩小候选。它不是“猜错无损”、不是每个物品各两次。见 [猜测提交](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfIntuition.java#L113) |
| 预知卷 `ScrollOfDivination` | 从药/卷/戒指未知种类池随机揭示最多 4 类，不要求实际持有；戒指仅 `setKnown()` | 前期减少危险未知池；不保证命中手上的关键物品。普通鉴定卷 +6 能量转换。见 [四次选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfDivination.java#L53) |
| 魔力探测石 `StoneOfDetectMagic` | `cursedKnown=true`；报告有害魔法与有益魔法。有益=真等级>0或良性附魔/刻印，有害=诅咒或诅咒附魔/刻印 | 排雷装备很值，但不揭示精确等级、不解咒、不识别戒指类型。见 [探测](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfDetectMagic.java#L45) |
| 未知卷→2 符石 | 0 能量，消耗 1 卷，产物类别固定并识别原卷；**合成前未知预览仅通用占位符** | 能把危险狂怒/报复转成可控单点工具，也可能误拆唯一升级。不要把打开预览说成免费鉴定。见 [ScrollToStone](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L308) |
| 药/卷分解能量 | 消耗所选 1 份或整叠，鉴定其种类；不能撤销 | 适合确无必要的未知重复品/已确认治疗瓶；仔细选择一份，避免整叠。见 [energizeOne](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndEnergizeItem.java#L143) |
| 三同种种子炼药 | 0 能量，消耗 3 粒；固定对应药并 `identify()`，但治疗生成还有独立限量重抽，见后文 | 可安全识别颜色。Rotberry 仅一颗时不能声称能单独炼力量。见 [SeedToPotion](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L468) |
| 喝/扔未知药 | 常规有益药需喝后鉴定；危险药/净化/浮空扔碎且碎点在视野内才触发识别；扔有益药通常只打碎、不鉴定、不获益 | 不以“扔全部未知瓶”作为安全识别流程；井/深渊走特殊掉落逻辑，不是摔碎实验台。见 [onThrow](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L308) |
| 盲读未知背包目标卷 | 第一次读即鉴定并移出背包，再选择物品；取消会出现“浪费此卷”的确认 | 不能通过打开选择窗口白嫖鉴定。升级还需实际升级窗口确认。见 [InventoryScroll](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/InventoryScroll.java#L40) |

日记中的已知类别、标签等属于公开游戏知识；不读保存文件的 `*_label`、`*_known` 来填表。排除法推断应保存为“候选/高概率”，直到原生鉴定或来源足够唯一；同一层多瓶/多个机关房不能把“必有一瓶”错误升级为“眼前这瓶必是”。

### 固定供给和来源推断

每 5 层区段的普通四层中，力量药分别在第 1–2、3–4 层各一瓶（较早层 50%）；升级卷生成计数每区段原为 3，禁忌符文移除每个全局偶数编号升级卷，因此九挑战正常主路 15 个生成机会只留下 8 张，而不是每区段固定 1 或 2 张。第 1–3 层保证一颗感知石；每区段第 3 或 4 层安排炼金房。[Dungeon.posNeeded/souNeeded](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L542)、[实际生成过滤](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L222)、[感知石与炼金房](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L594)

以下房间在建图时把解决资源加入**本层生成池**，并非保证放在门口。只有已经公开看到对应房间，才能利用这个知识；不能从源码预测本局未见房间。

| 已见结构 | 本层额外保证资源 | 源码 |
| --- | --- | --- |
| 木障储藏室 `StorageRoom` | 液火 | [L57](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/StorageRoom.java#L57) |
| 魔法火墙房 `MagicalFireRoom` | 冰霜 | [L112](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/MagicalFireRoom.java#L112) |
| 毒气房 `ToxicGasRoom` | 净化气体 Purity | [L107](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ToxicGasRoom.java#L107) |
| 陷阱房 `TrapsRoom` | 浮空 | [L122](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/TrapsRoom.java#L122) |
| 食人鱼池 `PoolRoom` | 隐身 | [L91](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PoolRoom.java#L91) |
| 哨卫房 `SentryRoom` | 极速 | [L161](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SentryRoom.java#L161) |
| 秘密悬崖宝箱房/秘密符石房 | 分别浮空/液火 | [Chasm](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretChestChasmRoom.java#L113)、[Runestone](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretRunestoneRoom.java#L64) |
| 普通商店 | 至少其固定清单包含治疗、鉴定、祛邪等；还有随机同类 | [ShopRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ShopRoom.java#L277) |

蝙蝠、蚊群、死灵法师的普通固定药掉落是治疗；恶魔血巢的固定药也是治疗。矮人术士可以掉治疗也可以掉其它普通药，所以不能用“术士掉的瓶”唯一鉴定。火/冰元素的固定药分别是液火/冰霜；蝎子随机药主动排除治疗和力量。需要确认当前掉落确实来自该来源，且没有财富之戒等额外战利品混入。[Bat](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Bat.java#L47)、[Swarm](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Swarm.java#L53)、[Necromancer](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Necromancer.java#L61)、[DemonSpawner](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DemonSpawner.java#L54)、[Warlock](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Warlock.java#L143)、[Elemental](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Elemental.java#L232)、[Scorpio](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Scorpio.java#L112)

### 装备被动鉴定不是无限刷次数

- 武器普通目标 20 个有效 `proc` 计数；初始可消耗额度只有一半，获得经验补充，上限仍为一半。穿戴的武器/携带的投掷武器获得经验，普通速度下累计半级经验足以补另一半。失败挥击不能未经调用链证明当作有效 proc。[Weapon.proc/onHeroGainExp](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java#L187)
- 护甲普通目标 10 次 `proc`，同样初始 5 次额度，穿戴并获得经验补充。不是为了识别而平白多挨 10 次攻击。[Armor.proc/onHeroGainExp](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L542)
- 法杖普通目标 10 次 `wandUsed()`，初始额度 5，累计约一级经验补另外 5；可用的次数不等于当前充能。种类本身公开，首次未知诅咒施法可能走 `CursedWand`，不是仅“歪一下”。[Wand counters](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L93)、[经验补充](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L283)、[使用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L458)、[未知诅咒](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L758)
- 戒指需穿戴并积累约一级的经验比例；等时间/空走不鉴定。战士基础无盗贼识戒加成。[Ring.onHeroGainExp](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/rings/Ring.java#L329)
- 战士 Veteran's Intuition +1/+2 武器识别系数 1.75/2.5；护甲 +1 系数实现为 2，+2 由穿戴钩子立即鉴定。源码注释的“2.5x”与实际 `1 + points` 有出入，采用表达式而非旧攻略/注释。遗忘碎片会改变被动识别完成行为。[Talent.itemIDSpeedFactor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L683)

**推荐决策（推论）：** 先看来源与公开诅咒信息 → 对将决定投入的主武器/戒指/法杖用探魔、祛邪或鉴定 → 其它无诅咒装备在正常战斗中积累识别。九挑战中 HP 和经验都宝贵，不为鉴定专门刷伤害或无限等待。

## 十二种普通药与十二种异种药

普通→异种一律消耗一瓶和 **4 能量**，保留同色知识，不自带鉴定；返回普通通常需嬗变卷，不要认为炼金转换可免费反悔。[ExoticPotion recipe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/ExoticPotion.java#L125)

表中时长是原生时间单位；走路速度、动作耗时、buff 延长和暂停时间机制会改变可执行步数。3 倍移动不等于攻击/饮用也 3 倍快。烟、气体、区域会扩散和衰减，应逐帧看实情。

| 普通药（用途/限制） | 对应异种药（用途/限制） | 实现 |
| --- | --- | --- |
| 力量 `Strength`：永久 +1 STR，通常立即使用以解除负重惩罚 | 肌肉记忆合剂 `Mastery`：选择一件武器/护甲降低其需求（具体需求公式见装备章）；每物品只能一次，失去这瓶的全局 +1 STR；原生选择界面操作 | [Strength](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfStrength.java#L43)、[Mastery](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfMastery.java#L77) |
| 治疗 `Healing`：九挑战施毒；仍先清 Poison/Cripple/Weakness/Vulnerable/Bleeding/Blindness/Drowsy/Slow/Vertigo | 奥术护盾合剂 `Shielding`：九挑战也施同一药恐毒，**没有护盾** | [Healing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfHealing.java#L52)、[Shielding](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfShielding.java#L41) |
| 灵视 `MindVision`：20 时间单位，揭示本层怪物周围公开视域；规划避敌/配合视野型卷轴 | 魔能透视合剂 `MagicalSight`：50 时间单位、感知范围 12，并解除/免疫失明，黑暗和光拳阶段有价值 | [MindVision](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfMindVision.java#L40)、[MagicalSight](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MagicalSight.java#L30)、[Level FOV](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1379) |
| 冰霜 `Frost`：投掷点九宫格非实心格建立 10 单位 Freezing，灭火/控制；留心自己与地面药瓶 | 极速冰冻合剂 `SnapFreeze`：九宫格立即调用 `Freezing.affect`，再给角色 `Roots.DURATION*2`，有冻结后仍扎根的用途 | [Frost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfFrost.java#L40)、[SnapFreeze](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfSnapFreeze.java#L42) |
| 液火 `LiquidFlame`：九宫格非实心格点 2 单位 Fire，烧障碍/迫敌绕行/持续伤害；会害自己、书卷和可燃地形 | 火龙吐息合剂 `DragonsBreath`：饮用选方向，长度 6、60°锥形；点燃、5 回合残废；不能把基础直射球路径当全部锥面 | [LiquidFlame](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfLiquidFlame.java#L40)、[DragonsBreath](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfDragonsBreath.java#L137) |
| 毒气 `ToxicGas`：投掷生成 1000 体积毒气，靠扩散/停留造成伤害；不能站气里硬扛 | 腐蚀酸雾合剂 `CorrosiveGas`：总基础体积 225，初始九宫格布置，强度 `2 + scalingDepth/5`；少瓶数高价值伤害，但留退出通路 | [ToxicGas](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfToxicGas.java#L39)、[CorrosiveGas](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCorrosiveGas.java#L40) |
| 极速 `Haste`：20 时间单位，移动速度 ×3；脱离、哨卫房、贴远程敌 | 精力回复合剂 `Stamina`：100 时间单位，移动速度 ×1.5；长距离跑图节食、长期风筝，瞬间逃命不如极速 | [Haste](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfHaste.java#L39)、[Stamina](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfStamina.java#L37)、[speed](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L773) |
| 隐身 `Invisibility`：20 时间单位，通鱼池、摆脱追兵；破隐动作按具体动作检查 | 暗夜迷雾合剂 `ShroudingFog`：九宫格起始烟幕，总体积 1620；遮挡非友方的普通视野，英雄/盟友和 GnollGeomancer 可透视；不提供通用伤害免疫，适合让战士单方面远攻/调整走位 | [Invisibility](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfInvisibility.java#L40)、[ShroudingFog](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfShroudingFog.java#L40)、[烟幕视线](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1347) |
| 浮空 `Levitation`：喝得 20 时间单位飞行、解除扎根；结束重新踩脚下格。扔则是 1000 体积混乱气体 | 暴风骤雨合剂 `StormClouds`：投掷雨云总基础体积 1080，逐格变水、清普通火，伤害火属性敌；本身不按普通药先踩触发落点 | [Levitation](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfLevitation.java#L44)、[飞行终止](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Levitation.java#L43)、[StormCloud](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/StormCloud.java#L34) |
| 麻痹气体 `ParalyticGas`：投掷 1000 体积，控群；不可让扩散把自己麻住，尤其配毒/火 | 大地护甲合剂 `EarthenArmor`：Barkskin 初值 `2 + lvl/3`，每 50 时间单位下降 1；是逐级衰减，不是“50 回合后全消失”；提供额外减伤需仍按原生减伤链计算 | [ParalyticGas](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfParalyticGas.java#L39)、[EarthenArmor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfEarthenArmor.java#L35)、[Barkskin](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Barkskin.java#L41) |
| 净化药剂 `Purity`：喝得 20 时间单位 BlobImmunity；扔清非实心可达距离 3 内所列有害 blob | 全面净化合剂 `Cleansing`：清身上多数负面、饥饿清零、5 回合多数负面免疫；直接扔中角色也可生效 | [Purity](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfPurity.java#L55)、[免疫表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/BlobImmunity.java#L64)、[Cleansing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L47) |
| 经验 `Experience`：给 `hero.maxExp()` 经验；不以此绕过当前原生等级上限与收益衰减 | 神意启发合剂 `DivineInspiration`：选择一阶天赋增加额外点数（英雄天赋计算读取 tracker），每阶最多使用一次；提升关键天赋组合（具体额外点数见战士章） | [Experience](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfExperience.java#L41)、[DivineInspiration](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfDivineInspiration.java#L55) |

**Purity 与 Cleansing 的互补（推论）：** 已中毒/流血/失明需要 Cleansing；需要穿过毒云/麻痹云/火/电区域优先核 Purity 的免疫表。Purity 不等于免疫药恐的 `Poison` buff，Cleansing 也不等于任意环境伤害免疫。灵视+视野群攻卷的目标确实按 `heroFOV` 选取，但面对首领抗性、无敌阶段、保护区，仍不能宣称一键秒杀。

**已有免毒时的药恐例外（静态调用链推论，非默认操作）：** 若已经带着仍有效的 `ToxicImbue`，其 Poison 免疫会让 `Buff.attachTo` 拒绝药恐的新毒；仍有效的 Cleanse 也会在 `Char.add` 拒绝负面 Poison。此时治疗瓶依然**不给治疗**，护盾药依然**不给其本体护盾**，但普通治疗瓶前置 cure 和战士正常药剂使用触发可发生。只有现成的免疫剩余时间与实际收益足够时，才考虑将其作为额外清负面/液态意志触发；专门花稀缺材料去“把毒瓶喝掉”通常不如分解。不将 Purity 的 blob 免疫误当 Poison 免疫。[Buff.attachTo](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Buff.java#L67)、[Poison 负面类型](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Poison.java#L42)、[ToxicImbue immunities](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ToxicImbue.java#L119)

## 十二种普通卷与十二种异种卷

普通→异种固定 **6 能量**。所有普通阅读统一受失明、MagicImmune 等门槛；`readAnimation` 会破隐、花时间。异种不是无条件更优：鉴定→预知丢失对单件装备的确定鉴定，祛邪→驱魔失去永久解咒，传送→回城不再是本层随机脱险。[Scroll.execute/readAnimation](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L173)、[ExoticScroll recipe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ExoticScroll.java#L121)

| 普通卷 | 异种卷 | 九挑战战士用途与源码 |
| --- | --- | --- |
| 升级 `Upgrade` | 注魔秘卷 `Enchantment` | 普通 +1 由原生升级窗口及装备类型处理，既有附魔/刻印保留风险看装备章；异种从 3 个不同选项择一附魔/刻印，**不加装备等级**。九挑战只剩 8 张常规升级供给，不机械转换。[Upgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfUpgrade.java#L82)、[Enchantment](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfEnchantment.java#L114) |
| 鉴定 `Identify` | 预知 `Divination` | 一个物品完整鉴定 vs 最多 4 个随机未知药/卷/戒种类；详见前节。[Identify](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfIdentify.java#L57)、[Divination](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfDivination.java#L53) |
| 祛邪 `RemoveCurse` | 驱魔 `AntiMagic` | 祛邪选择物品，去 cursed 与诅咒附魔/刻印，并清 Degrade；旁边有 TormentedSpirit 时转为净化它。驱魔给 20 时间单位魔法免疫表，同时会关闭自己许多魔法能力（含卷/法术/戒指效果），不视为完全无敌。[RemoveCurse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRemoveCurse.java#L55)、[uncurse](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRemoveCurse.java#L119)、[MagicImmune](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MagicImmune.java#L30) |
| 镜像 `MirrorImage` | 虹卫 `PrismaticImage` | 普通尝试在自身九宫格合法空位生 2 镜像，狭窄堵死可能少于 2；虹卫建立 PrismaticGuard，已有虹卫则回满其生命，能承接战士装备。先留召唤空位。[MirrorImage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfMirrorImage.java#L67)、[PrismaticImage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfPrismaticImage.java#L43) |
| 充能 `Recharging` | 魔能秘卷 `MysticalEnergy` | 普通加 30 时间单位法杖充能 buff；异种给神器 30 单位 recharge 并允许号角，区别于法杖。不要带着零相关设备就转换/读。[Recharging](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRecharging.java#L45)、[MysticalEnergy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfMysticalEnergy.java#L39) |
| 传送 `Teleportation` | 归返秘卷 `Passage` | 普通优先未探索可通行位置，锁层/特殊层用连通性约束；不是“任意穿过锁门”。异种返回之前区段入口深度，受 `interfloorTeleportAllowed`；锁层不能当保命保证。[Teleportation](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L147)、[Passage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfPassage.java#L40) |
| 催眠 `Lullaby` | 魅音秘卷 `SirensSong` | 普通令视野敌人与自己 Drowsy，5 时间单位后 MagicalSleep；睡眠可每时间单位回 1 HP，未走药恐 heal 路径，但自己不能行动，挨伤/敌人逼近使安全性不成立。异种永久魅惑一个合格目标为盟友，视野其他敌人普通魅惑；免疫目标只尝试普通 Charm。[Lullaby](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfLullaby.java#L44)、[MagicalSleep](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MagicalSleep.java#L59)、[SirensSong](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfSirensSong.java#L90) |
| 探地 `MagicMapping` | 先见 `Foresight` | 普通当前层可发现区域 mapped，并揭示秘密地形/陷阱；异种 400 时间单位、距离8的搜索加强，适合连续探索，不等价于立即全层地图。[Mapping](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfMagicMapping.java#L43)、[Foresight](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Foresight.java#L30)、[Hero.search](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2500) |
| 盛怒 `Rage` | 决斗 `Challenge` | 狂怒 beckon 全层，视野非友军 Amok 5。决斗也招全层，建立最多100时间单位竞技圈，离开圈会移除；受到伤害走对应路径 ×0.67，且 Hunger.act 在圈 buff 存在时停止饥饿进度/饥饿伤害。可用于有利地形战斗，不能忽视大批远程集火。[Rage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRage.java#L45)、[Challenge](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfChallenge.java#L52)、[伤害](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L465)、[饥饿暂停](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L66) |
| 复仇 `Retribution` | 灵爆 `PsionicBlast` | 普通视野群伤随自己缺血增强：每怪约 `0.1*敌HT + 0.225*敌当前HP*min(4,4.45*自身缺血比例)`，结算仍受目标免疫/抗性，自己虚弱失明。灵爆对每怪 `round(敌HT/2+敌当前HP/2)`，自己受 `round(0.5*自身HT*0.9^目标数)`，随后失明和更长虚弱；可致死。两卷只筛 `heroFOV`，**没有敌友过滤，会伤害玫瑰幽灵等友军与视野内中立Mob；灵视扩大目标范围也扩大误伤范围**。不能故意残血只为增伤。[Retribution](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfRetribution.java#L50)、[PsionicBlast](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfPsionicBlast.java#L55) |
| 恐惧 `Terror` | 梦魇秘卷 `Dread` | 普通视野非友军恐惧，攻击可能影响控制；Dread 对免疫者回退 Terror，合格敌人跑出视野且距英雄≥6后被移除，经验减半且不走普通死亡掉落。适合不值得耗血的危险敌人，不适合期待其任务/资源掉落。[Terror](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTerror.java#L43)、[Dread scroll](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfDread.java#L42)、[Dread buff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Dread.java#L60) |
| 嬗变 `Transmutation` | 蜕变 `Metamorphosis` | 嬗变同类装备保留多项等级/附魔信息，近战换同阶；药/卷实际只在普通↔异种配对翻转，**不是随机换别种药卷**。蜕变改职业天赋，候选与点数按当前原生界面，见战士章；需抽中才有的跨职业组合不能作为开局必备。[changeItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTransmutation.java#L157)、[药卷翻转](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTransmutation.java#L379)、[Metamorphosis](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfMetamorphosis.java#L60) |

催眠回血、肉类随机回血、战士天赋回血之类**不必然受药恐限制**，判断标准是实际调用链是否进入 `PotionOfHealing.heal` / 显式 `NO_HEALING` 分支，不能仅按物品有“治疗”两字判断。反过来，以治疗瓶为原料的回复秘药/再生炸弹确实会走药恐惩罚，见后节。

## 十二种符石：精确用途

普通卷拆石固定一卷→**2 颗**、0 能量；感知石还按全英雄每两次猜测消耗一颗。基础符石永远已鉴定，普通投掷会按原生射线落点、按石的实现激活；井/深渊等特殊落点行为应看 [Runestone.onThrow](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/Runestone.java#L49)。

| 符石 | 普通卷来源 | 战士用途/边界 | 实现 |
| --- | --- | --- | --- |
| 感知 Intuition | 鉴定 | 有来源依据时揭示药卷戒种类；详前节 | [L59](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfIntuition.java#L59) |
| 沉睡 DeepSleep | 催眠 | 单格角色若为 Mob，施 MagicalSleep；不会像催眠卷那样令自己昏睡；后续伤害/唤醒机制仍有效 | [L41](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfDeepSleep.java#L41) |
| 明示 Clairvoyance | 地图 | 投掷点周围固定半径区域地图/秘密揭示，精准侦查可替代整层卷；不等同敌人全视野 | [L34](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfClairvoyance.java#L34) |
| 羊群 Flock | 镜像 | 非实心可达距离2内合法空格生成持续约8时间单位羊，阻路/挡投射；双方和自己通路都要检查 | [L47](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfFlock.java#L47) |
| 震爆 Blast | 报复 | 立即调用 ConjuredBomb 爆炸；敌我、地形与物资爆炸风险都算，按炸弹伤害链 | [L34](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfBlast.java#L34) |
| 敌意 Aggression | 狂怒 | 给命中角色20时间单位 Aggression，改变仇恨目标；可让多敌围攻其一，需目标可达/可见 | [L44](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfAggression.java#L44) |
| 电击 Shock | 充能 | 非实心可达距离2内所有角色麻痹1，按命中角色数 `1 + hits` 充能；也算自己和盟友，避免贴身把自己控住 | [L48](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfShock.java#L48) |
| 探魔 DetectMagic | 祛邪 | 识诅咒/正负魔法，**不去除诅咒** | [L45](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfDetectMagic.java#L45) |
| 闪现 Blink | 传送 | PROJECTILE 射线；命中角色时改前一格，传送需合法且可达；可在危急时定点脱离，不能任意穿墙 | [L39](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfBlink.java#L39)、[teleportToLocation](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L77) |
| 恐惧 Fear | 恐惧 | 单格非友军施 Terror；少敌时比全屏卷省资源 | [L42](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfFear.java#L42) |
| 强化 Augmentation | 嬗变 | 选择武器速度/伤害、护甲闪避/防御取舍，非升级；详装备章 | [L49](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfAugmentation.java#L49) |
| 附魔 Enchantment | 升级 | 随机良性附魔/刻印，非附魔卷的三选一，非装备等级升级；优先用天然生成的石，不轻拆8张升级额度 | [L47](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/StoneOfEnchantment.java#L47) |

## 炼金：种子、能量和配方

### 荒芜大地没有禁止种子炼金

`Challenges.isItemBlocked` 在荒芜大地下只屏蔽露珠；`Level.plant` 在铺草后直接返回，不产生普通植物。种子仍可收集、炼药、制作药镖/其它配方。不能指望种阳春草回血、速行蓟时停、魔皇草清负面或消逝草传送。[Challenges](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Challenges.java#L71)、[Level.plant](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1067)

三种子→一药为 0 能量。三粒同种先按对应表选择并识别，最终治疗成品还受下述例外；两种不同种子有 25% 转为普通药默认随机，否则从三个输入位置随机选一粒对应药；三种不同有 50% 默认随机，否则从输入等概率选。**治疗配方例外：** 先 `identify` 后，若产物为治疗，按 `Random.Int(10) < COOKING_HP.count` 重抽默认药，每实际产出治疗增加计数。累计治疗炼制越多越不可靠，到计数10时不会再得到治疗；即使三阳春草也不能无条件写“必得治疗成品”。**最终重抽产物可能尚未鉴定，出锅必须重新观察名称与鉴定状态。**[完整选择次序](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L512)

| 种子 | 对应普通药 | 九挑战的主要去处（推论） |
| --- | --- | --- |
| Blindweed 致盲草 | 隐身 | 三同种识别/隐身储备；药镖控制 |
| Mageroyal 魔皇草 | Purity 净化气体 | 炼 Purity，再4能量变 Cleansing 作食物/解控 |
| Earthroot 地缚根 | 麻痹气体 | 群控或大地护甲 |
| Fadeleaf 消逝草 | 灵视 | 探路或魔法视野 |
| Firebloom 烈焰花 | 液火 | 烧障碍/火系原料 |
| Icecap 冰冠花 | 冰霜 | 冻肉、灭火、控制 |
| Rotberry 腐莓 | 力量 | 配方要求三粒输入，不能把单粒任务种子当固定力量药；它与另2粒混炼只可能按上述输入随机规则抽到力量，且会失去任务种子 |
| Sorrowmoss 断肠苔 | 毒气 | 腐蚀药、持续范围伤害 |
| Starflower 星陨花 | 经验 | 经验/神意，通常避免无计划拆散稀少种子 |
| Stormvine 风暴藤 | 浮空 | 过陷阱/跨沟或造水冲击配方 |
| Sungrass 阳春草 | 治疗 | 九挑战主要能量/不稳定合剂/喂盟友；治疗配方有上述重抽 |
| Swiftthistle 速行蓟 | 极速 | 逃命、哨卫、耐力长期跑图 |

来源为 [SeedToPotion.types](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L471)。中文名核对当前 [plants_zh.properties](../../core/src/main/assets/messages/plants/plants_zh.properties#L1)，类名用于避免混淆。

### 能量预算与鉴定门槛

[默认池权重](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L329) 中力量/升级权重为0；所以“随机默认药卷”不能补出额外这两类。普通药/卷基础分解 6 能量，已知力量/经验/升级/嬗变为10；普通符石3，附魔/强化石5；异种药比对应普通多4、异种卷多6。合成成品的回收价不等于配方总投入，分解未知高价值类会先按未知值给能量。`SimpleRecipe.testIngredients` 要求全部输入已鉴定；普通→异种、卷→石、三种子、不稳定合剂等自定义 Recipe 则按自己的规则。**未知普通药放炼金台，看到未知异种产物，不能免费读出真实名称。**[Recipe.SimpleRecipe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Recipe.java#L100)、[药能量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L446)、[卷能量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L284)

配方表 `+E` 是额外能量，未包含先把普通药/卷升异种的4/6能量；每行输入数量默认1。表中所有内容由当前配方类确认，不沿用旧版“催化剂”配方。

### 八种秘药

| 成品 | 输入 / 额外能量 / 数量 | 机制、九挑战取舍 | 源码 |
| --- | --- | --- | --- |
| 水灵秘药 AquaticRejuvenation | 治疗+GooBlob，+6E→1 | 药恐直接施毒；不能靠进水触发绕过。非九挑战才有1.5HT水中恢复池 | [L51](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfAquaticRejuvenation.java#L51)、[配方L152](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfAquaticRejuvenation.java#L152) |
| 抗魔秘药 ArcaneArmor | 大地护甲+GooBlob，+8E→1 | 强度 `5+lvl/2`，每80时间单位减1；针对 AntiMagic.RESISTS 来源的随机减伤，不是80回合全消失，也不是所有物理减伤。保留 GooBlob 有机会成本 | [L42](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfArcaneArmor.java#L42)、[buff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ArcaneArmor.java#L40)、[damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L947) |
| 龙血 DragonsBlood | 龙息，+10E→1 | 50时间单位 FireImbue，攻击点燃、免疫/清 Burning；火区和拳头需逐来源判断，非任意火色攻击全免疫 | [L40](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfDragonsBlood.java#L40)、[FireImbue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/FireImbue.java#L85) |
| 羽落 FeatherFall | 浮空，+10E→1 | 50时间单位内落层保护，每次落层额外扣10时间单位；不是持续飞行或跨越陷阱；具体落层逻辑见移动机制章 | [L44](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfFeatherFall.java#L44) |
| 圣愈蜜药 HoneyedHealing | 治疗+破蜂蜜罐，+2E→1 | 喝调用 healing→药恐毒，同时直接饱腹150；扔中盟友可治疗，扔中蜜蜂能转盟友；对英雄不推荐作为“回血食物” | [L45](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfHoneyedHealing.java#L45) |
| 晶触秘药 IcyTouch | 极寒，+6E→1 | 50时间单位免疫Frost/Chill，攻击施加3时间单位Chill；控制近战，免疫型敌人另计 | [L38](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfIcyTouch.java#L38)、[FrostImbue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/FrostImbue.java#L36) |
| 根骨 Might | 力量，+16E→1 | 仍永久+1STR，增加临时最大生命并 `updateHT(true)`；效果按5次升级渐衰，不是无穷叠加最大生命。价格高，先保障净化/控制/逃生能量 | [L49](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfMight.java#L49)、[HTBoost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfMight.java#L84) |
| 毒粹秘药 ToxicEssence | 腐蚀，+8E→1 | 50时间单位持续放毒气，免疫ToxicGas和Poison并清Poison；**不含CorrosiveGas免疫**，不可在腐蚀云里自信站桩 | [L38](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfToxicEssence.java#L38)、[ToxicImbue](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ToxicImbue.java#L70) |

### 六种合剂

| 成品 | 输入 / 额外能量 / 数量 | 效果与风险 | 源码 |
| --- | --- | --- | --- |
| 紊乱魔药 UnstableBrew | 任意普通/异种药+任意种子，+1E→1 | 喝只随机到可喝有益普通药，药恐排除治疗；扔只随机到可投药；匿名效果不鉴定颜色，随机性不能应急指定使用 | [L67](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/UnstableBrew.java#L67) |
| 水爆魔药 AquaBrew | 暴风骤雨，+8E→8 | 调用 GeyserTrap：距离2区域造水清火（外围概率），九宫格击退含自己，火属性敌受额外伤害；落点不先踩陷阱，可用于灭火/拆部分地形陷阱/推入深渊。每次自己与敌人落点都重新算 | [L38](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/AquaBrew.java#L38)、[GeyserTrap](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GeyserTrap.java#L55) |
| 冰暴魔药 BlizzardBrew | 冰霜，+8E→1 | 大量持久 Blizzard 区域；封路强，但自己撤退也可能被封 | [L41](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/BlizzardBrew.java#L41) |
| 淤泥魔药 CausticBrew | 毒气+GooBlob，+1E→1 | 非实心可达距离3内角色附 Ooze，包含自己；不是腐蚀气体，敌人踩水会受其原生清除逻辑影响 | [L49](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/CausticBrew.java#L49) |
| 炼狱魔药 InfernalBrew | 液火，+12E→1 | 大片持久 Inferno，总初始体积1080；先留安全路径，物资燃烧和免疫敌人另计 | [L41](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/InfernalBrew.java#L41) |
| 雷鸣魔药 ShockingBrew | 麻痹气体，+10E→1 | 非实心可达距离3内每格Electricity 20，持续区域控伤；不要站在区域里投。不能把视觉电流当危险地形唯一证据 | [L42](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/ShockingBrew.java#L42) |

### 十一种法术与投入方向

法术永远已鉴定；施放仍受其 `execute` 门槛（MagicImmune 等）与原生选目标/背包限制。`TargetedSpell` 默认 PROJECTILE，所以下表定点工具不能假设穿怪、穿墙。[Spell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/Spell.java#L55)、[TargetedSpell](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/TargetedSpell.java#L42)

| 法术 | 配方 / 额外能量 / 产量 | 作用与战士取舍 | 实现 |
| --- | --- | --- | --- |
| 炼金菱晶 Alchemize | 任一种子+任一符石，+2E→8 | 远程卖物/分解能量，减少饥饿回城；仍需有炼金台才能合成其它物品，不是随地完整实验室 | [L65](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/Alchemize.java#L65)、[配方L80](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/Alchemize.java#L80) |
| 返回晶柱 BeaconOfReturning | 回城卷，+12E→5 | 设置公开返回点花1时间单位，之后返回消耗；跨层有锁层/支路限制；适合做有计划的采购/回撤，不是事后给过去某处设点 | [L117](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/BeaconOfReturning.java#L117)、[配方L278](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/BeaconOfReturning.java#L278) |
| 诅咒菱晶 CurseInfusion | 祛邪卷+MetalShard，+6E→4 | 主动诅咒并提供对应注能升级，反复用可能换诅咒；收益公式与可接受诅咒见装备章；不为了纸面等级盲用 | [L53](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/CurseInfusion.java#L53) |
| 注魔菱晶 MagicalInfusion | 升级卷，+12E→1 | 升级并保留/处理既有附魔、刻印或诅咒注能路径，依物品而异；保留良性附魔的高等级主装可考虑，不增加可用升级总量 | [L91](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/MagicalInfusion.java#L91) |
| 转移结晶 PhaseShift | 传送卷，+10E→6 | 射中角色随机传送；怪物可重置追击且普通非首领目标麻痹；适合剥离危险敌，不保证落点、不能对所有首领生效 | [L48](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/PhaseShift.java#L48) |
| 陷阱晶柱 ReclaimTrap | 地图卷+MetalShard，+8E→5 | 先回收合格陷阱类型，之后定点放出保存的陷阱效果；每种陷阱禁用与触发规则必须临场读实现 | [L56](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/ReclaimTrap.java#L56) |
| 转换菱晶 Recycle | 嬗变卷，+12E→12 | 单份药/卷/种/石/药镖换不同类型，异种保持异种；按默认生成池，排除原类/挑战禁物；力量药/升级卷默认权重为0，因此不能靠此法生成它们 | [L55](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/Recycle.java#L55) |
| 唤魔晶柱 SummonElemental | Embers，+10E→6 | 默认召元素盟友；可耗已知液火/冰霜/充能/嬗变注入不同元素。尸尘/腐莓等任务路线不必获得余烬 | [L93](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/SummonElemental.java#L93)、[注入L188](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/SummonElemental.java#L188) |
| 念力结晶 TelekineticGrab | 液金10，+10E→8 | 从远处抓合法物堆/角色上插着的投掷物；`timeToCast=0` 但实际拾取调用决定花时；需核陷阱与容器类型，不是任意免费开宝箱 | [L61](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/TelekineticGrab.java#L61) |
| 无序结晶 UnstableSpell | 任意普通/异种卷+符石，+1E→1 | 根据视野是否有敌等条件选随机卷效果；匿名效果，不会揭示原卷类型；九挑战不把随机救命当可靠技能 | [L55](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/UnstableSpell.java#L55) |
| 强能结晶 WildEnergy | 充能卷+MetalShard，+4E→5 | 先做一次诅咒法杖效果，再给即时法杖1/神器4及8时间单位持续两种充能；随机诅咒可能有害，低血/险地不要把它当纯充电 | [L54](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/WildEnergy.java#L54) |

## 食物与炸弹：本章补充

普通 Food 饱腹能量九挑战 ÷3：标准口粮300→100、小口粮/熟肉150→50；食物天赋按相应事件调用。不要以“吃饱”假设能完整恢复上限450。冻肉 `FrozenCarpaccio.effect` 五选一：隐身、树肤、清治疗药那一组负面、直接回1/4HT、无额外效果；直接回血分支不走药恐。冻肉只是有利随机，不是指定药剂。[Food](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L114)、[FrozenCarpaccio](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/FrozenCarpaccio.java#L55)

肉饼：馅饼/幻影肉 + 标准口粮 + 任一生/熟/冻/炖肉，+6能量→1；本体能量900在节食下为300，并给 WellFed，节食下约150时间单位，暂停饥饿并每18时间单位额外回1HP，非药恐路径。原料本可分次触发战士进食收益，需结合天赋与背包作比较，不能无脑合成。[MeatPie](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/MeatPie.java#L35)、[WellFed](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/WellFed.java#L49)

炸弹增强共10种（1普通炸弹+1原料→1成品）：冰霜→冰霜炸弹 +0E；镜像→绵羊炸弹 +0E；液火→燃烧炸弹 +1E；狂怒→噪音炸弹 +1E；隐身→烟雾炸弹 +2E；充能→闪光弹 +2E；治疗→再生炸弹 +3E；祛邪→神圣炸弹 +3E；GooBlob→奥术炸弹 +6E；MetalShard→破片炸弹 +6E。每种爆炸形状、延时、是否破坏物品需临战按类核实，不把普通炸弹射程直接复制给增强版。[全部配方](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bombs/Bomb.java#L358)

**再生炸弹不能绕药恐/荒芜。** 它对同阵营角色调用 `PotionOfHealing.cure + heal`，英雄仍受毒；植物仍经过 `Level.plant` 被荒芜拒绝。它可以治疗符合阵营的盟友，但不能称英雄安全回血或种子生产机器。[RegrowthBomb.explode](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/bombs/RegrowthBomb.java#L63)

## 实战资源方案（源码支持的策略推论，非固定剧本）

1. 前期建立未知颜色/符文台账：记录公开来源、持有数量、候选类型、哪个房间需要它；优先用感知石确认力量与必要解谜瓶。没有可唯一推断的条件时宁可延后，也不在敌人旁盲喝。
2. 每次炼金先列必须保留：下一房间的原版解谜瓶、定点脱险、祛邪、主武器升级额度、首领控制。然后将确定多余治疗转能量，先保障 Purity→Cleansing 与最紧迫的控制/机动配方。
3. 饥饿与HP分开核算：Cleansing 是450饱腹与解负面，不直接补HP；催眠/冻肉/肉饼是另一路回血，条件和风险不同；不把“药恐”误解成全局禁止任何回血。
4. 毒/火/电+麻痹的组合要先建立自己安全的射线、扩散边界和退路；对敌人免疫、首领阶段、羊/召唤占位重算。控制工具减少被攻击次数，不能当永久无敌。
5. 战士 Liquid Willpower 在使用药剂事件提供屏障：按 `factor * HT * (0.030 + 0.035*天赋点)`，+1约6.5%、+2约10%；部分低成本批量炼金物有 `talentChance=1/产量`，不是水爆8瓶每瓶稳定给完整屏障。普通扔碎有益瓶也不普遍触发事件；此收益只作为有用药剂的额外回报，不值得为触发去喝治疗毒。[Talent.onPotionUsed](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L713)、[Potion投掷触发](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L322)、[AquaBrew概率](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/AquaBrew.java#L34)
6. 任何未知效果、错投、UI选择取消、异步动作都遵循项目控制协议：先确认原动作结果，再观察当前状态；不能借口研究“再发一次”。此文是决策资料，不授权开游戏或消费物品。

## 覆盖与未验证边界

已逐类核对 12 普通药、12 异种药、12 普通卷、12 异种卷、12 符石、8 秘药、6 合剂、11 法术的效果入口与配方；关键药恐/节食/荒芜路径继续追到 `Hunger`、`Food`、`Char`、buff 与 `Level`。装备个体鉴定、随机颜色与保存、保证生成和房间来源已经核对。炸弹配方与再生炸弹挑战陷阱单独核对；其余增强炸弹详细范围/延时、每种食物烹饪/特殊节日替代、所有敌人的逐项抗性、每种非战士蜕变组合不在本章完整穷举，遇到时按对应源码继续核实。未做个人存档分析、活局实验或九挑战胜率验证。
