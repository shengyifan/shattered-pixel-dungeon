# 15 — 九挑战战士：按推进阶段安排炼金

基线：游戏4.0.2 / code922，源码 `d04c8f862`，2026-10-11；读过当前AGENTS、[00速查](00-run-plan.md)、[07消耗品机制](07-identification-consumables-alchemy.md)与[第4层失败复盘](../2026-10-10-warrior-nine-challenges-retrospective.md)。本文只回答“什么时候保留、变什么、先花哪一份资源”，完整物品效果查07。本次重新核对实际 `Recipe` 注册、材料、产量和关键调用链；未进行新实战或胜率对照。

目标是减少本局死亡风险。下文排序是源码和已记录失败支持的**条件策略**，不是数学最优证明，不预设特定神器、法杖、药剂或任务材料必得。所有“留一份”指有该物品时的配置目标；不足时缩减支线与战斗范围，不能把未掉落资源记入计划。

## 一、核心顺序：先把资源变成能完成的下一段路线

通常先补眼前无法替代的缺口，再做长期强化：

1. **尚未解决的房间／主线门槛材料先锁定。** 需要飞过陷阱/悬崖时留原版浮空，需要过鱼池时留原版隐形；不得因异种看似更强而把唯一解法改没。
2. **缺营养：** 有净化药剂 Purity 时，全面净化合剂 Cleansing 常是最划算的4能量用途；缺的是应急脱离时，则先留一个当前可以实施的闪现、隐形、极速、恐惧或传送方案。营养不能代替躲眼睛射线，位移也不能长期替代营养。
3. **下一段必打而输出不足：** 用普通麻痹、腐蚀、投武/法杖充能等减少换血；原版已经能解决问题时，不先花钱做昂贵版本。
4. **前述都有余量：** 再比较水爆批量工具、转移结晶、长期抗魔、天赋扩展、附魔和根骨。不要为了“成型配方”把当前能用的力量药、食物或唯一脱困品留到人死。

上次失败发生在第4层，但透支始于第3层：多次休息、追逐和无终点诱敌消耗营养与照明；最后一个未知瓶造成麻痹，未返回可行动窗口。炼金计划必须在仍有粮、光、HP和退路时落地，而不是等到“最后5HP再赌瓶”。参考[复盘：食物、照明与未知药剂](../2026-10-10-warrior-nine-challenges-retrospective.md)。

## 二、首锅前：第1–2层与第3/4层的实际准备

普通炼金房在每区段第3或4层生成：3/4、8/9、13/14、18/19、23/24。它有锁门与本层铁钥匙、5能量晶体、1–2份物品；奖励优先从层内待放物品取饰品催化剂，再取力量药，再随机药/石。因此未见力量药时，钥匙与已见炼金房可能很关键；这不允许猜未观察地图或认定已看见哪一瓶的身份。秘密炼金房是额外偶遇收益，给两堆各3–5能量，不纳入保底路线。[锅生成时机](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L602)、[强制房间选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SpecialRoom.java#L131)、[普通房](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/LaboratoryRoom.java#L46)、[奖励选择](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/LaboratoryRoom.java#L125)、[秘密房](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLaboratoryRoom.java#L64)

到锅前的保留清单：

| 已有物品 | 首锅前怎样处理 | 原因 |
| --- | --- | --- |
| 已知力量药、升级卷 | 力量通常及时喝；升级先处理已落实的生存/力量门槛，不为昂贵配方拖延关键收益 | 不能用随机普通药/卷生成补回；禁忌咒文主路常规供给仅8张升级。把本局已拥有的升级视为稀缺实物 |
| 已确认治疗瓶 | 保留作分解6E的高优先级来源；没有锅时不喝、不改想象中的护盾瓶 | 药恐伤害照常，背包里“可变能量”还不是现成救命物 |
| Purity | 本层尚需过已见气体房时先留原版；否则准备4E制Cleansing | 这是环境免疫与饱腹/解负面的二选一；普通净化不补营养 |
| 浮空 | 未解陷阱/跨沟房先保留；暂不承诺羽落或水爆 | 三条配方互相消耗同一瓶；有药不代表三个用途全有 |
| 传送卷／已得闪现石 | 保留可执行脱困；到锅后按路径选择0E拆2闪现，或10E做6转移 | 原卷是自己随机传送；闪现是可达点位移；转移可移走敌人，作用不同 |
| 麻痹／毒气／冰霜／液火／隐形／极速 | 保留原版解决已见问题，不为以后配方拒绝眼前有价值的使用 | 第一次锅前没有必要等合成才控制螃蟹、烧障碍、灭火或脱离 |
| 种子 | 优先凑3同种魔皇草做Purity，或保留所需药镖/自然之履用途；其余到锅再评估 | 荒芜下普通种植不生效，但炼金、药镖与特定神器路径仍有效 |
| 食物、肉 | 食物先服务当前推进；未知下层有无锅/原料时，不饿着等肉饼 | 初期没有锅不能靠未来肉饼救现在；已知安全冻肉/烤肉来源可先用，不能为烹饪走进危险火/陷阱 |
| 未知药卷 | 保存来源与排除记录；用感知石/原生鉴定，预先确定至少一种应急品 | 不在低血、饥荒、近敌时把未知饮用当保命标准流程 |

相关源码：默认池力量/升级权重0见[Generator](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java#L329)；每偶数编号升级移除见[Level](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L233)；[药恐](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/PotionOfHealing.java#L58)；[种子映射与重抽](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L468)。

**没有锅的备选计划就是用现成工具推进。** 已鉴定饮用/投掷/卷轴、先天控制、门角位移与有限等待不依赖炼金。没有Purity、没有安全取钥匙路线、或当前HP不足以为进锅付出战斗代价时，取消“必须做出某瓶”的假设；先保当前生命、关键物资与可达出口。炼金房生成事实不能证明它这次可安全取得。

## 三、每口锅的决策卡

到锅后先写六项：`下一明确目的 / 当前饥饿与HP / 粮光余量 / 已知脱困 / 必留原料 / 可花能量`。未公开精确营养时，只按公开饥饿状态与已确认行动作保守预算，不读取或反推当前私有累积量。再做以下有顺序的工作：

1. **确认材料知识。** `SimpleRecipe` 要全部输入已鉴定；自定义的异种转换、卷拆石、三种子等另有规则。看到未知异种预览并不免费揭示身份；把未知卷拆石会消耗并鉴定它，可能拆掉升级。不要用“先试放进去”推导隐藏类型。[Recipe.testIngredients](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Recipe.java#L100)、[ScrollToStone](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L308)
2. **锁定本段必须材料。** 原版解谜药、最后已知脱困、要投入的力量/升级、必要祛邪、已承诺交任务的腐莓/余烬均不可顺手分解。力量/升级可以有条件转换成精通/注魔，但不拿来烧成能量或当随机配方饲料。
3. **按缺口合成最小可用包。** 例如 `Cleansing1 + 闪现2` 可只需4E与两个对应材料；不要把零能量拆石忽略后，又称必须10E才能脱困。
4. **有剩余再做伤害和强化。** 别一次把所有钱、材料变成同一种工具。实际出锅后核成品数量、已鉴定状态、剩余能量，给每份成品指定用途或储备条件。
5. **一次完成可预见的合成，避免来回跑。** 相邻交互进炼金界面本身用 `ready/switchScene`，合成扣材料/能量而非“每配方固定若干回合”；真正要预算的是到锅、找钥匙、接敌、拾取、回来和饮用。不能把界面动画/现实思考时间当饥饿时间，也不能把寻找炼金房称零成本。[Hero.actAlchemy](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L1084)、[AlchemyScene.combine](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/AlchemyScene.java#L687)

### 能量从哪里来，怎么避免拆掉生存能力

| 来源 | 当前固定能量 | 怎样排序 |
| --- | --- | --- |
| 普通炼金房能量晶体 | 5 | 拾到才计入；不是每层5，也不是每次开锅重置5 |
| 已确认治疗药／普通多余药卷 | 普通6/份 | 治疗瓶通常优先；其它药卷先问“本区会不会用掉它省血”，再分解 |
| 普通种子／普通符石 | 2/粒、3/颗 | 先保护三同种关键配方、药镖、最后闪现/沉睡/恐惧等；荒芜不等于所有种子都是垃圾 |
| 附魔石、强化石 | 5/颗 | 未完成主装选择时通常不拆；它们可能避免牺牲升级卷 |
| 异种药／异种卷 | 对应普通价值+4/+6 | 花能量升级再分解不制造利润；合成前就做价值判断 |
| 已知力量、经验、升级、嬗变 | 10/份 | **力量/升级不拆**；经验/嬗变也先比较等级/天赋/修正构筑收益，不因10E就排在治疗之前 |

来源：[Plant.Seed.energyVal](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Plant.java#L218)、[Runestone](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/Runestone.java#L84)、[药](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L446)、[卷](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L284)、[异种药](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/ExoticPotion.java#L119)、[异种卷](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ExoticScroll.java#L115)。

分解未知物先按未知 `energyVal` 入账、再识别，所以未知力量/升级仍只按6E入账，且永久失去这份物资。[WndEnergizeItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndEnergizeItem.java#L152)。已有合格炼金工具箱可改变可用能量/开锅地点，应另按神器实情计入；不把随机神器作为这套计划前提。商店固定有治疗、鉴定、祛邪、探地和两份小口粮；**没有固定Purity、浮空、麻痹或传送**，不能为不存在的货写采购必得清单。[ShopRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ShopRoom.java#L277)

## 四、最有竞争关系的配方：必须比较失去的原版用途

`E`均指已有全局炼金能量；“总E”从表内普通原料开始计算。成品可能来自直接掉落，此时只支付尚未完成的那一段，不重复扣异种转换成本。配方当前真实注册见[Recipe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Recipe.java#L177)。

| 决策 | 确切输入→产量；总E | 通常提高优先级的条件 | 不转换／延后的条件 |
| --- | --- | --- | --- |
| Purity→Cleansing | 1净化药剂→1全面净化合剂；4E | 营养接近枯竭且有下一段可推进路线；或需要解除危险负面 | 唯一原版净化仍须过有害气体/电/火区域；临战需求仅是blob免疫 |
| 浮空→羽落 | 1浮空→1羽落秘药；10E | 已发现真实跳楼坑房，预计装备收益值得10E+往返粮光，能安全取战利品并离开 | 只是“可能下层有坑”；缺逃生/食物；原浮空还要跨悬崖/陷阱；随机坑房不是保底治疗 |
| 浮空→水爆 | 1浮空→1暴风骤雨（4E）→8水爆（8E）；总12E | 需要多次推离/清火/造水、地图有明确可利用击退位置；工具足以替代多次换血 | 不懂推力/自己落点，或会让粘咕/腐拳回血；唯一过沟资源不能牺牲；已掉暴风骤雨则只需8E |
| 传送→闪现 | 1传送卷→2闪现符石；0E | 主要缺英雄在已知可达空格之间的定点脱离，能验证PROJECTILE射线 | 想把远处敌人送走；没有合法落点/通路；某些紧急包围仍需保留原卷随机传送 |
| 传送→转移 | 1传送卷→6转移结晶；10E | 多次移走可传送的危险精英、远程敌/堵口者可节约大量HP；锁层阶段另核合法目标 | **未锁定的普通RegularLevel不能用它送走敌法精英**；只有5E或更少时别等它才做逃生；不可移动/传送失败与首领边界见下文 |
| 隐形→暗夜迷雾 | 1隐形→1暗夜迷雾合剂；4E | 稳定遮挡敌人普通视线、建立战士可透视的射击/走位区 | 需要携带随身、穿过鱼池或直接绕敌；烟雾区域不是隐形，不挡Yog穿墙预警射线 |
| 麻痹保原版／变大地护甲 | 保原版0E；1麻痹→1大地护甲合剂4E | 多敌危急更偏原版群控；确定将长期打物理近战且群控已有替代才比较树肤 | 两者解决不同问题，不能提前把唯一群控瓶全喝成护甲；施毒/腐蚀组合先留自己不进麻痹云的路 |
| 毒气→腐蚀酸雾 | 1毒气→1腐蚀酸雾合剂；4E | 下一区普通危险敌/群体可困在区域，且无明显免疫/抗性劣势 | 即将只打抵抗腐蚀的酸性敌；自己无撤离空间；不能投无敌首领然后算输出已完成 |
| 极速→精力回复 | 1极速→1精力回复合剂；4E | 已知长路线，×1.5移动持续100时间比极速×3持续20更适合整段推进 | 当次需要迅速贴远程敌/脱险/过哨卫，原版速度更重要；不是攻击速度药 |
| 力量→根骨 | 1力量→1根骨秘药；16E | 原瓶尚未饮用、能量充足、临时HT/对应HP提升能实质改善下一战，同时关键消耗包已齐 | 开局为等16E推迟力量；缺饱腹/逃生；重复根骨并不叠出无限HT，效果随5次升级递减 |
| 力量→肌肉记忆 | 1力量→1肌肉记忆合剂；4E | 已确定的一件优质武器/护甲刚好缺2STR，提前达标能立即改善存活 | 为不确定未来装备用；仅差1STR时直接力量通常覆盖更多装备，保全局STR收益 |
| 升级→注魔秘卷 | 1升级→1注魔秘卷；6E | 主装已确定，三个候选附魔/刻印预计收益高于少1永久等级，且天然附魔石未能满足需要 | 主武器/盾/法杖仍缺关键等级或力量门槛；不能把“有6E”当丢弃升级价值的理由 |
| 经验→神意启发 | 1经验→1神意启发合剂；4E | 指定已可用天赋阶增加2点可完成关键组合；每阶仅一次 | 眼前等级/最大生命/命中/闪避或下一阶解锁更紧迫，直接经验有明确收益；未解锁阶点数不能立即花 |
| 祛邪→驱魔 | 1祛邪→1驱魔秘卷；6E | 已无待解装备诅咒，下一战以免疫表内魔法为主要威胁，20时间内可靠物理/药剂作战 | 还需祛邪，或主要靠法杖/戒指/魔法逃生；不能失明后读卷，不能读完立刻接灵爆 |
| 三食品→肉饼 | 1馅饼（或幻影肉）+1标准口粮+1肉（生/熟/冻/炖）；6E→1 | 原料齐、目前有余粮，约150时间WellFed与周期HP收益有用途，能省进食动作 | 要饿着囤材料；依赖三次安全丰盛回血；用幻影肉/冻肉时还会失去各自独立效果 |

配方证据：[普通药变异种4E](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/ExoticPotion.java#L125)、[普通卷变异种6E](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ExoticScroll.java#L121)、[羽落10E](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfFeatherFall.java#L82)、[水爆8E/8瓶](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/AquaBrew.java#L63)、[闪现拆石](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L310)、[转移10E/6个](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/PhaseShift.java#L79)、[根骨16E](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfMight.java#L70)、[肉饼6E](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/MeatPie.java#L51)。

**转移的楼层分支必须分开：** `PhaseShift` 调 `teleportChar(ch)`，默认来源为 `ScrollOfTeleportation.class`。未锁定普通 `RegularLevel` 会检查目标来源免疫；敌法精英继承 `AntiMagic.RESISTS`，其中包含传送卷，因此不能把它列为普通层可送走的应急目标。锁层或非RegularLevel会先进入 `teleportInNonRegularLevel`，该分支检查不可移动和可达合法落点，没有复用前述来源免疫；这不证明特殊层必能传送，也不能把普通层结论直接推广过去，须按当前楼层、目标与落点另核。[PhaseShift调用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/PhaseShift.java#L48)、[普通层与分流](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L99)、[敌法精英免疫](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/ChampionEnemy.java#L193)、[传送免疫条目](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/glyphs/AntiMagic.java#L96)、[特殊层分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/ScrollOfTeleportation.java#L226)

### 五个不能混算的收益

- **Cleansing最大是450饱腹，不是450HP。** 在饥饿封顶时，等价4.5份普通口粮的100营养，且不受Food除三；它不触发丰盛一餐，不直接回血。它可以重新开放普通再生窗口，但光照、移动、交战仍消耗时间；别重复上次“吃完长休息到再次饿”的模式。药剂使用可触发已有液蕴意志，护盾与饥饿伤害仍分开算。[Cleansing](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfCleansing.java#L75)、[Hunger](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Hunger.java#L136)、[Food](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/Food.java#L114)、[药剂天赋](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L713)
- **肉饼不是把三份营养凭空翻几倍。** 普通馅饼450+标准口粮300+肉150，在节食下分吃总营养300；肉饼900/3也是300，额外优势是约150时间WellFed停饥饿、通常每18时间额外1HP及少吃两次的动作成本（携带盐晶时实际计时可延长，依当前道具修正）。损失是至多3次食物触发合为1次；不能把三次丰盛收益当必得，因为每次都要满足安全且低于0.334血线。幻影肉本来就直接回HT/4等，不能随意当普通肉饼材料；冻肉随机收益也会失去。[MeatPie](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/MeatPie.java#L35)、[WellFed](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/WellFed.java#L44)、[PhantomMeat](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/PhantomMeat.java#L51)、[进食天赋](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Talent.java#L587)
- **肌肉记忆对选定物品减2需求，但放弃了全局+1STR。** 例：目前STR10、已确认主武器需求12，直接力量使自己11仍差1；精通使该武器需求10立即达标。代价是其它装备、超额力量与未来换装收益没有获得那1STR。只用已鉴定、确实打算长期用且非诅咒陷阱的目标推算。[Mastery](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/exotic/PotionOfMastery.java#L77)、[近战需求减2](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/melee/MeleeWeapon.java#L261)、[护甲减2](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/armor/Armor.java#L691)
- **根骨买的是临时HT/HP增量而非另一种恢复药。** 例如无其它HT修改、等级4、HT35时，初始加 `round(4+35/20)=6` HT，`updateHT(true)` 同增6HP；仍+1STR。16E可与四次4E转换竞争（也必须真有四份对应材料），而根骨余量随升级降低。不为该6HP延迟本来现在就需要的力量。[Might](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfMight.java#L49)、[HTBoost](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfMight.java#L92)、[Hero.updateHT](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L260)
- **神意启发是选定阶+2点，经验是当前经验额度。** 经验升级通常增加HT与战斗属性，还推进鉴定经验计数；满级则另走Bless，不能继续当升级。神意适合把已经有用的天赋补满，但阶未解锁／没子职／没护甲能力时，额外点数也受同样门槛。[Hero.bonusTalentPoints](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L405)、[经验升级](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L2060)

## 五、羽落与跳坑：提高获取效率，而不是给跳层找理由

羽落初始50时间单位，每次坠落额外扣10，**不是5次保证免费跳楼**。`Chasm.heroLand` 有它时跳过普通落地的踩格/残废/流血/即时伤害分支，但饮用、走到缺口、下层取物、开门与继续走都会消耗窗口；两次坠落之间经过40时间，就不能再按“还有四次”计划。[FeatherBuff](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfFeatherFall.java#L51)、[落地分支](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/features/Chasm.java#L135)

对已发现的薄地板/坑房，先核五项：`本层核心物资是否已取 → 坑房是否值得 → 羽落能否覆盖实际动作 → 下层拾取/幽灵风险 → 返回或直接下行路线`。坑房会给随机主装备、1–2份随机奖励与水晶钥匙，**不保证食物、治疗或需要的主武器**；诅咒装备残骸还可闹鬼。因此首锅仅5E、唯一浮空或急缺营养时，默认跳过可选坑房；已经有多余10E、合适原料与明确安全路线时，羽落才提高优先级。[PitRoom](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PitRoom.java#L48)

普通浮空是20时间飞行、能跨悬崖与避踩陷阱，结束重新触发占格；羽落没有这些能力。水爆是推力/造水工具，也不是飞行。不要把三者都写成“可以过危险地形”，再误花唯一那瓶。[Levitation](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/Levitation.java#L40)

## 六、能量贫乏／中等／宽裕三档：只在原料真实存在时选包

这里以**扣除本段已承诺合成后的可用能量**划档；数字不是游戏规则，也不是拥有这些能量就必须花完。原版已经能脱险时，保留能量等下一份重要材料是合法选择。

| 档位 | 优先行动 | 可核算的条件示例 | 暂缓 |
| --- | --- | --- | --- |
| 紧张：0–5E | 先利用0E拆石/三同种；把唯一4E给眼前最大缺口 | 只有首锅5E且真有Purity+传送卷：Cleansing1（4E）+闪现2（0E），剩1E；若不饿但腐蚀能避免必经硬仗，4E改给腐蚀 | 羽落10、水爆12、转移10、根骨16、为了6E而拆升级 |
| 中等：6–15E | 一份续航或解控，再选一份已知控制/脱困；尽量别都押同一伤害类型 | 首锅5E+一治疗瓶6E=11：Cleansing4+腐蚀4，剩3；或单做转移10剩1，前提营养已有其它方案；两包不能同时当11E内完成 | 把原料和能量全花在单次高价秘药；没有坑房却先做羽落 |
| 宽裕：≥16E | 先满足下一段与下个首领储备，再批量工具／特定强化 | 5E+两治疗瓶12E=17且真有Purity+浮空：Cleansing4+水爆12=16，剩1；若还需过沟则此包不成立，应保浮空 | 将“≥16”误解为无限预算；根骨16与上述16E整包互为机会成本 |

附加便宜选项：1/2/3份生肉可用1/2/2E炖成等量熟食；原版生肉副作用比这点能量危险时可用，但有已知安全免费烹饪就不必花。治疗瓶+任一种子+1E→1紊乱魔药，在药恐下饮用排除治疗及应投掷危险药，但仍随机，**不能替代指定Cleansing或逃生**；把同一治疗瓶分解可立即供给6E，通常应先比较两者。[StewedMeat recipes](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/food/StewedMeat.java#L39)、[UnstableBrew](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/brews/UnstableBrew.java#L83)

## 七、五区与首领：把储备逐步做出来，不背完整购物单

| 推进阶段／普通锅 | 优先处理 | 首领/下一阶段储备与不必做的事 |
| --- | --- | --- |
| **下水道1–2 → 首锅3/4** | 正常识别并使用力量，确定至少一件已知脱困；治疗留作能量；魔皇草/Purity留续航；不为等配方空耗食物火把 | 首锅先修营养或脱困缺口。Goo前要有避蓄力通路与抗失误工具，原版控制也能用；不要把高成本羽落/根骨列首锅必做 |
| **5粘咕 → 6商店 → 8/9锅** | 正常掉落的GooBlob保留比较抗魔用途，已知DM-100/死灵/狱卒威胁提升遮线、位移价值；商店先买实际缺项 | 天狗前至少一个可靠机动手段与可用远程/持续输出。极速通常比为了100时长换耐力更适合短促闪避；保原版浮空按具体机关通路使用。首领阶段不是无限休息补满充能点 |
| **11–14洞穴 → 13/14锅** | 萨满射线、蜘蛛毒网、DM-200区域：区分Cleansing解身上负面与Purity防环境；若长期远程魔法压力高且有GooBlob，比较抗魔秘药 | DM-300跑塔更重视机动、实际干地路线和强制位移恢复；不要为“水爆多次”到处造水扩大电场通路。矿区/坑房只在有独立余量时进入 |
| **16–19都城 → 18/19锅** | 把群体控制/范围伤害、术士断线与退出工具形成至少两种独立方案；有明确主装再比较注魔/神意/根骨 | 国王前保留二阶段清部属与三阶段输出/脱离各自份额；不在一阶段为了方便清完所有旧部属把群攻用光，转阶段本会清其敌对旧部属 |
| **21–24大厅 → 23/24末锅** | 前半区就保Yog清盲/复明/抗燃/防气原料；魔能透视可解眼蝎定位与光拳盲，不等于全局安全；血巢治疗瓶仅为到锅后的能量潜力 | 末锅把要用的材料做成成品，并留光/暗拳两次阈值事件的应对；不要为了固定“全部配方齐全”无尽回层搜药。当前危险敌需要用储备才能活就先用，再降低后续计划范围 |
| **25 Yog** | 每轮双拳按实际组合选择用品，避免两个拳和本体同时能打到；先离保护区再做持续输出 | 首领锁层停止饥饿进度/饥荒伤害，因此Cleansing主要值在解控，Purity/魔能透视/机动常更关键；不能据此承诺普通回血/法杖充能无限持续 |

抗魔秘药为 `麻痹→大地护甲4E + GooBlob + 8E = 总12E`，产1；强度 `5+lvl/2`，每80时间减1，只作用于审查过的魔法伤害来源。它不像驱魔关闭自身魔法，但也不是伤害全免。是否值得花GooBlob，要和当前法杖输出、可用断线及其它材料用途比较。[ElixirOfArcaneArmor](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/elixirs/ElixirOfArcaneArmor.java#L42)、[Char.damage](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java#L945)

**任务材料不是免费药材。** 腐莓之种、余烬或尸尘用于交法杖任务；未交前将腐莓混药/分解、将余烬做召唤，会失去该任务交付物。三种子配方里的腐莓可能贡献力量结果，但不是单粒保证力量，默认随机池也不会给力量。只有明确决定放弃法杖奖励、并比较当前可见奖励价值后才考虑改用途；此方案不默认放弃。尸尘还会引出持续风险，不为收藏背着跑无目的路线。[WndWandmaker](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndWandmaker.java#L64)、[种子配方](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L515)、[SummonElemental recipe](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/SummonElemental.java#L244)

## 八、国王与Yog储备的最低功能集合

不规定“每种必备X瓶”，而是临进门逐项写出能兑现的方案与份额，缺一项就认清实际风险；不存在的替代品不算覆盖。

### 矮人国王：分三段保留，不把所有药投到王盾上

- **第一阶段**需可靠推进王血线和断术士射线；接近转阶段时，额外昂贵群攻可能浪费在马上会清掉的敌对旧部属。
- **第二阶段**需真正处理18部属的群控/伤害工具；腐蚀/麻痹/羊群/召唤/魅音等按当前目标和位置选择。麻痹国王并不停止全部召唤；食尸鬼暂倒地不等于完成盾伤。
- **第三阶段**另保一段可靠输出及逃离拥堵的动作：伤害进入延迟池，仅够累计100不代表马上死；一味逃跑等池慢扣会拖出更多召唤。
- **驱魔不是通用连招开头。** 它会阻止读取卷轴/施法杖/施法术，**投掷符石也不激活**，因此闪现石、转移结晶、返回晶柱都不能当驱魔期间备用逃生。它还关闭戒指、武器附魔/护甲刻印等效果；若依赖力量之戒，失去STR可能重新超重，失去HT可能立即截低现有HP，结束恢复HT并不补回那段HP。应先确定20时间内靠什么物理输出、移动和药剂续命。Cleansing不能提前洗掉这个正面MagicImmune。若打算用群攻卷，别先自封魔。[MagicImmune](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MagicImmune.java#L30)、[卷读取门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/Scroll.java#L173)、[法术门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/Spell.java#L55)、[符石不激活](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/stones/Runestone.java#L49)、[戒指失效](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/rings/Ring.java#L357)、[HT钳制](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java#L260)

完整阶段与召唤证据见[04c国王专项](04c-dwarf-king.md)；当前源码[阶段处理](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L492)、[KingDamager](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java#L779)。

### Yog：末锅保留的是功能，而不是一堆同名瓶

| 风险功能 | 可用候选（有才选） | 必须保留的边界 |
| --- | --- | --- |
| 光拳半血与死亡失明 | 魔能透视50时间：1灵视+4E；或Cleansing分别覆盖两次、明确安全等待 | 一瓶Cleansing的5时间不能无条件覆盖两次；驱魔不等于清盲/免盲；失明时不能读驱魔或灵爆 |
| 暗拳半血与死亡熄灯 | 实际火把、先离危险线再重燃的行动窗口 | Cleansing不是火把；药剂/眼睛感知不能直接假定替代所有照明需求 |
| 草拳根缚＋本体预警 | 预先保留移动空间；Cleansing解根、浮空解根、或已核可用位移 | 解根可能让此前因英雄不能动而延迟的射线恢复结算，不能在预警线上“先喝完再说” |
| 火拳直接点燃／地面火分开处理 | 龙血50时间免疫Burning并立即清已有燃烧；原版Purity20时间仅覆盖免疫表内Fire等环境blob；水爆可应急清火/推离 | **Purity不免火拳直接附加的Burning，也不清已有燃烧**。龙血从液火算总14E（4+10），高价且不免物理拳；火拳蒸水，水爆非永久安全水池 |
| 腐拳气体／黏液 | 原版Purity、防气工具、英雄自己能到的洗黏液水格 | 不要同时让腐拳站水回血；其酸性抗腐蚀，纯腐蚀包不是万能解 |
| 双拳／幼虫包围 | 合法闪现点、隐形/极速、暗夜迷雾遮普通视线、已核位移/群控 | 本体WONT_STOP射线穿墙，烟幕不把已公开预警取消；转移对首领无额外麻痹保证，不可移动目标不能送走 |
| 锈拳及持久输出 | 可持续的有效伤害和保持它在保护圈外的路线 | 延迟伤害池不是已扣血；不能为“已经够伤害”就自动长休息 |

来源：[Purity环境免疫表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/BlobImmunity.java#L61)、[火拳直接点燃](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L258)、[龙血免Burning并清除](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/FireImbue.java#L117)、[BrightFist](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L501)、[DarkFist](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogFist.java#L569)、[魔能透视清盲](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/buffs/MagicalSight.java#L56)、[Yog射线](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java#L199)、[拳头细则](05-enemies-halls-yog.md)、[暗夜迷雾视线](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java#L1347)、[转移](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/spells/PhaseShift.java#L48)。

**灵视＋灵爆**可减少杂兵，但不是本篇默认通关套餐：两次动作/材料、英雄自伤、失明/虚弱、首领抗性都要预算，而且灵爆视野目标集合不筛敌友，未有对应免疫的盟友/中立者也受伤。玫瑰幽灵明确免疫报复与灵爆是个别例外，不能把这个保护推广给所有盟友；它仍可进入灵爆目标集合，目标数也不是击杀数。没有灵爆时靠逐批控制与杀敌，同样要完成主线，不能为等理想组合耗尽大厅粮光。[PsionicBlast](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/exotic/ScrollOfPsionicBlast.java#L55)、[幽灵免疫表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/DriedRose.java#L868)

## 九、离锅前最后检查

- 下一段的Cleansing是饱腹、解控还是Yog清盲储备？同一瓶不能在预算里花两次。
- 原版Purity、浮空、隐形、极速、传送是否因为转换失去仍需的功能？是否真有替代？
- 原料、能量、成品数量是否重新核对？三同种阳春草有治疗限量重抽例外，最终产物可能未鉴定，不依据旧输入强行识别。[SeedToPotion](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java#L534)
- 是否因“省药”将持续换血、超长等待或最后未知饮用当替代？现在用一份已知工具能避免死亡或保下下一段资源时就用。
- 是否只是为了花光能量而制作新东西？没有具体用途的高价配方留到下一口锅再决定；但不能把走回锅的粮光成本记为零。

优先保留的可证伪实战记录是：每次进锅的原料/能量/粮光、选择与放弃的配方、计划省下什么、之后实际用了几份以及是否缩短了路线或减少伤害。未来据真实结果调整排序，不能只因某局成功便称其为最高胜率构筑。
