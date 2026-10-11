# 16d CLI 玩家信息审计：物品、背包、装备与鉴定边界

审计日期：2026-10-11（Asia/Shanghai）。源码基线 `d04c8f862b2f90418a6375f0b3ffc5c4325d0953`；CLI.9.0.3 / protocol 9 / schema 12，游戏4.0.2/code922。范围为原生玩家可见物品信息，经 native UI → Java collector → semantic merge/compact → Python 正式 decoder 的保留与含义。没有启动真实游戏、读取个人存档/私有audit、修改产品代码或提交。以下“覆盖”不表示已经枚举所有物品的每个动态分支做真实 GUI 回放。

**结论：核心物品知识、数量、力量估计、充能、显示等级、可用性、文字描述和当前操作已有明确实现与测试；但魔晶光效存在确定的 producer/decoder 契约不一致，会使带该光效的公开帧无法由正式 Python 客户端解码。** 全部既有测试通过也不能消除此跨语言缺口。无需、也不应通过暴露私有精确耐久解决它。

## 1. 证据与测试边界

- [定向 Java 日志](evidence/cli-items/focused-java-tests.log)：现有8个 suite/选定方法，58 tests，0 failures/errors/skips，`BUILD SUCCESSFUL in 11s`。包含真实 native `ItemSprite`/`ItemSlot` 的合成纹理夹具、知识边界、显示角色、模板/默认值和唯一绑定测试；不创建游戏 profile。
- [定向结果摘要](evidence/cli-items/focused-java-results.json)：从本次 JUnit XML 保存了 suite、数量与基线；避免后续 Gradle 覆写 test-results 后失去证据。
- [魔晶 Python 夹具](evidence/cli-items/crystal-glow-decoder-fixture.py) 与 [结果](evidence/cli-items/crystal-glow-decoder-fixture.json)：调用正式 `decode_wire_response`，包含18个合成协议案例。9个魔晶直接glow路径全部失败，3个嵌套item_icon案例接受，6个现有光效/状态控制接受；输入未修改，失败保留 raw 与 identity。
- [原生 Java 探针](evidence/cli-items/NativeCrystalGlowProbe.java)、[运行器](evidence/cli-items/run-native-crystal-probe.py)、[结果](evidence/cli-items/native-crystal-probe.json)：真实 `Crystal.glowing → ItemSprite → ItemSlot.gameplayStatus → PlayerObservation.item + GameplayObservation.merge → CompactProtocol.success`，三个阶段 × play/full/src 共9个正式Java生成响应，全部被正式Python拒绝；记录当前class加载位置，0输入修改、0raw保存失败。使用合成纹理和resourceOnlyRuntime，没有backend/GL/游戏profile。
- Java 定向测试入口：`Cli9ItemObservationTest`、`ItemStatusMeaningTest`、`GameplayStatusTest`、`GameplayObservationTest`、`InventoryAvailabilityTest`、`UiProjectionHintsTest`、`CompactProtocolMapAndKnowledgeTest`，以及 `GameSnapshotterTest` 中未鉴定身份、法杖知识、刻印名字、地面容器和定位器选定方法。
- 此处没有把“测试通过”延伸成包已重建、当前运行包一致、真实玩家九挑战全程覆盖。全局审计另收集根任务的 Java/Python 套件与其他观察域证据。

## 2. 信息链条与知识边界

`PlayerObservation.inventory` 枚举六个装备槽和递归背包，产生当前 `locator`；`UiBridge` 只把本帧真实对象身份与本帧公开物品记录绑定，`GameplayObservation.merge` 把已绘制的 `shown` 移至其 owner；`CompactProtocol` 重命名/模板化后由客户端独立展开。所有引用属于当前帧，不能保留上一帧 loc 当永久身份。

源码链：[inventory/resolveItem](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L144) → [UiBridge.projectionHints](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L368) → [merge](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservation.java#L32) → [compact item知识](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/CompactProtocol.java#L153) → [Python `_item`](../../desktop-control/client/spdctl_client.py#L197)。协议依据：[help知识/默认值](../../docs/cli-help.md#L362)、[client语义规则](../../docs/cli-playthrough-client.md#L69)。

对 `level/cursed` 必须区分三种状态：缺失=不适用，`null`=玩家未知，明确0/false=已知零级/已知无绑定诅咒。`type_known` 只表示药剂/卷轴/戒指类型是否已识别；不能拿已知类型推断等级或诅咒。`ui.item_info.level_known` 是当前原生信息窗生成说明时缓存的知识分支，不是另一条读取隐藏等级的途径。其用途是避免未知装备的“典型+0数值”被误解为真实当前数值。

## 3. 覆盖证据矩阵

| 原生玩家信息/入口 | 产生与投影 | 当前结论及边界 |
|---|---|---|
| 装备六槽、各包内物品、堆叠数量 | `PlayerObservation.inventory/bag` → `inv[].loc/qty/equipped`；数量默认1可省略后恢复 | **覆盖，定向测试。** 递归包定位器按索引走当前包；类型名、隐藏 actor ID 都不是合法 loc。真实物品是否在开放包中显示的额外 UI 状态另由 ItemSlot 捕获。 |
| LostInventory 时灰掉的物品/仍可使用保留物 | `inventoryItemAvailable` 与 `InventorySlot.item` 同一 `lostInventory/keptThroughLostInventory` 条件；`available:false`，原生节点 enabled/ops | **覆盖，定向测试。** “仍出现在inv”不等于可用；无ops不得由名字补动作。恢复背包后可用性变化，不把旧绑定当新行动授权。 |
| 未鉴定药剂颜色、卷轴符文、戒指宝石名 | `Potion/Scroll/Ring.name/desc/isKnown` 原生门槛 → `name/desc/type_known` | **覆盖，定向反泄漏测试。** 两个不同隐藏类型若显示同一假身份，应输出同一公开描述；不显示内部class以破坏鉴定。 |
| 已知类型、未知级别/诅咒 | `itemInCurrentLanguage` 分别门控 levelKnown/cursedKnown | **覆盖。** 不把所有`Item`内通用字段都列出来：普通耗材不适用的grade/curse字段在compact移除，避免元数据成为类型oracle。 |
| 原生物品信息窗完整描述、伤害/护甲/戒指效果、神器/饰品效果 | `WndInfoItem.fillFields(item)` 缓存 `item.info()` 的 RenderedTextBlock；UiBridge/TextProvenance读取已生成、已绘制文本 | **有完整通用路径。** 背包基本记录仅药/卷/戒直接带`desc`；其他物品更详细说明须通过当前`item`操作打开原生窗。不能因inv没有额外硬编码damage字段就判断信息不可获得。尚未对每种动态info分支逐一native回放。 |
| 信息窗当前知识分支 | `WndInfoItem.inspectedLevelKnown` → `ui.item_info.level_known` | **覆盖。** heap FOR_SALE可绑定其已公开top item；封闭容器无item subject，不能显示容器内物品私有数据。 |
| 力量要求数字、未知时`14?`、力量不足红色、掌握黄色 | `ItemSlot.updateText` 源分支缓存value/estimated/insufficient/mastered；`gameplayStatus`只在对应现成文本仍一致且可见时输出 → `shown.strength` | **覆盖，定向测试。** 未知时的数值是`STRReq(0)`估计，不是隐藏装备等级反解。文字改变后旧语义绑定被撤销；不可见extra不凭物品getter补造。 |
| 已知升级等级和临时强化/降级的不同颜色 | native `visiblyUpgraded/buffedVisiblyUpgraded` → `shown.level` 与 flags upgraded/degraded/curse_infused/enhanced/reduced；`inv.level`保持基本公开等级 | **覆盖，源码+显示夹具。** 两值不同时保留额外显示等级；不能把减益期间的橙色`+n`覆盖成永恒真实等级。 |
| 灵能弓、神器的显示级别换算 | `displayedLevel`专门模拟原生公开数值：灵弓英雄等级/5，神器`round(raw×10/levelCap)` | **覆盖。** 它们不等同私有原始level；灵弓有专门测试。神器换算有源码核对，未逐神器做显示运行测试。 |
| 法杖剩余/上限、未知剩余`?/max`、已知空0 | native `Wand.status`；collector仅levelKnown时加charges，current依据curChargeKnown置null/整数，max可见 | **覆盖，定向测试。** 不调用`Wand.level()`的惰性变更；不把unknown null省成0。树脂和诅咒注能只模拟当前公开显示level，不显示私有partialCharge。 |
| 神器次数、百分比、冷却数字，包括数字“1” | `Artifact.status`及子类覆盖 → ItemSlot的现成status文本 → `shown.status` | **覆盖通用文本路径，专门测试避免丢“1”。** 只有原生`Item.status`/`MissileWeapon.status`确认为数量；神器charge/cooldown不因恰等qty而被压掉。未给所有神器status加独立charge_kind，不意味着可读私有charge/cooldown。 |
| 水袋等非堆叠计数文本 | 子类`status`已绘制文本 → shown.status | **覆盖通用路径。** 数字、分数或百分号原样留存，不使用物品名白名单推断字段含义。 |
| 投掷武器最后一次耐久橙色警告 | `ItemSlot.updateText`判定 `durabilityLeft<=50 && <=durabilityPerUse`，源绑定 `last_use` → flags | **覆盖。** 输出阶段警告而非精确私有耐久；升级销毁在外旧投掷件等警告走原生信息窗文本。 |
| 附魔、刻印、诅咒的名称与说明 | Weapon/Armor.name遵守cursedKnown/正面效果可见规则，`info`在窗中补描述；光效有独立语义 | **覆盖，刻印名字测试。** effect curse与`Item.cursed`绑定不同；“cursed:false”不证明没有负面附魔，原生名称/desc仍需读。未鉴定负面刻印名称不可提前泄漏。 |
| 物品图标/识别小徽记 | native ItemSprite与itemIcon选定后 GameplayIcons语义符号 → shown.badge/icon | **源码路径覆盖，图标专门审计在下节。** 不用atlas/frame/RGB推断隐秘类型；unmapped需保持诊断。 |
| 魔晶完好/瑕疵/裂纹光效 | native Crystal.glowing选定hint → GameplayGlow(kind crystal_condition/stage) → ItemSlot.shown.glow 或世界item_glow | **发现阻断缺陷。** Java侧已实现、测试通过，Python `_glow`拒绝kind/stage，详见第4节。 |
| 升级预览比较、未知典型值、附魔丢失概率、树脂/诅咒/投掷化尘警告 | `WndUpgrade.fillFields`逐行添加标题、旧值、新值；后加原生警告、Upgrade/Cancel控制；通用UI保持顺序/父节点/text | **源码通用路径覆盖，未完成此窗完整native回放。** 不直接重算装备私有数据。未知戒指提示和`? → +1?`必须跟数值一并读。表列没有专门from/to schema，不能只抽零散数字丢列顺序。 |
| 背包筛选中不可选物品、包标签、空位容量 | WndBag原生InventorySlot与selector enable；selected tab语义；UiProjectionHints只剔除固定sidebar空占位 | **覆盖路径及容量测试。** WndBag空格计容量，不能按“空控件”全部删掉。selector过滤不等于物品消失；具体可选状态遵从当前ops。 |
| 地面已见堆顶物品、商店物品、关闭容器 | `PlayerObservation.entities` known-cell/heap.seen，只普通HEAP/FOR_SALE暴露top，其他heap只title/info | **覆盖，定向反泄漏测试。** 堆下面未见物品不能因遍历库存那样递归输出；商店购买能力和价格依原生窗口。 |
| 独立actions回复 | GameplayObservation.inlineSubjects → 节点subject_data，或null+unresolved_subject | **覆盖，定向测试。** 此回复无inv/hero表时不留下悬空subject，不引用旧帧库存；标签与ops仍保留。 |

主要native证据：[ItemSlot状态/力量/等级](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ItemSlot.java#L315)、[InventorySlot背景及可用性](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/InventorySlot.java#L78)、[WndInfoItem](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndInfoItem.java#L43)、[Wand.status](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/wands/Wand.java#L333)、[Artifact.status](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/artifacts/Artifact.java#L189)、[Trinket.info](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/trinkets/Trinket.java#L64)、[Ring.info](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/rings/Ring.java#L182)、[WndUpgrade](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndUpgrade.java#L107)、[升级逐列文本](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndUpgrade.java#L515)、[包容量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndBag.java#L243)。

## 4. 确认缺陷 I-01：魔晶公开光效令正式客户端拒收

### 玩家可见事实

`Crystal.glowing()` 通过原生实际选中的Glowing对象表示intact/flawed/cracked；`GameplayGlow`将它转成，例如：

```json
{"variant":"crystal_blue","kind":"crystal_condition","stage":"flawed"}
```

该阶段只取原生已选定的显示对象，不能从私有durability重新求值。Java测试已经证明：修改Crystal内部耐久但不重新选择sprite光效时，观察仍保持旧显示阶段；真正重新选定后才切换。不可见sprite/复用sprite不能沿用旧光效。此行为符合玩家显示边界。

### 失败位置与后果

`spdctl_client.py::_glow`目前仅允许 `kind∈{explosive_heat,resin_fortified}` 与 `stage∈{cool,warm,hot}`，因此在合法生产者输出进入 `inv[].shown.glow`、UI节点shown或世界 `item_glow` 时抛出：

```text
...glow.kind is not a reviewed glow meaning
```

删掉kind也不能修复，因为晶态stage同样不在允许列表。正式decoder因此不能返回完整可决策帧；这不是单纯少了一个装饰字段，也不能要求使用者删除glow绕过。错误raw/identity有保留，发生在动作回执后的帧时仍必须先按协议确立原动作结果，不能重放动作。

来源：[GameplayGlow](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/GameplayGlow.java#L60)、[ItemSlot.gameplayStatus](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ItemSlot.java#L53)、[Python `_glow`](../../desktop-control/client/spdctl_client.py#L294)、[Java Crystal tests](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/Cli9ItemObservationTest.java#L62)。

### 最小复现与对照

原生探针已先确认三种实际Sprite阶段在play/full/src共9个响应全部报同一错误：`$.data.inv[0].shown.glow.kind is not a reviewed glow meaning`；这不是只手工拼JSON的推测。另一个 `crystal-glow-decoder-fixture.py`将三种真实生产者定义的阶段放进三条正式验证路径，共9例都失败；旧易爆三个阶段与纹章/蜡烛/自然之力三种状态均接受。另把同一glow嵌在item_icon中3例接受，说明目前验证路径覆盖不同：**不能把嵌套接受解读成主问题不存在，也不能把其较宽松验证当作修复方式。** 后续产品修复应统一语义契约，并从原生producer到compact到Python建立跨语言回归。

建议最小回归矩阵：三个晶态 × inventory/UI/world/standalone actions，play/full/src分别展开；保留unknown/null/raw和保护元数据；在native选择后改变未公开私有耐久，确认不会改变输出。此报告不修改产品。

### 图标、光效与状态emitter的独立核对

物品域当前88处`new Glowing`常量均在`GameplayGlow.selected`具名颜色映射内，凉鞋动态12种seedColors也有映射。普通颜色只报告variant，不把同色直接认成某个附魔/刻印身份。晶光的来源绑定与三阶段Java生产本身正确，已确认错误在Python的枚举校验。未知药卷戒图标按当前实际frame映射为`item_potion_*`/`item_scroll_*`/`item_ring_*`等外观语义，不能用真实类名替换假身份。

纹章/蜡烛状态由`ItemStatusEmitter`核当前target、factory/episode/lifetime、on、存在/可见父链、同一root；不会因物品后台仍有纹章字段而伪造已脱离的粒子显示。`ItemSlot`识别小徽记也是先由原生`item.isIdentified()`（戒指有known例外）生成，再读取实际图像；不由collector替玩家鉴定。

来源：[GameplayGlow颜色表](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/GameplayGlow.java#L10)、[GameplayIcons当前图像](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/GameplayIcons.java#L62)、[ItemStatusEmitter生命周期](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/effects/ItemStatusEmitter.java#L29)、[ItemSprite已选定glow](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ItemSprite.java#L268)、[徽记门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ItemSlot.java#L336)。这里的88处/12色是源码枚举检查，未宣称88种真实屏幕逐项回放。

## 5. 已核实没有丢失的信息，和不应要求展示的私有内容

- `GameplayObservation.minimizeItemShown` 仅在同帧可唯一重建时去掉重复字符串：来源明确的数量status等于qty、已知显示等级文本等于level、额外文字等于strength.value/estimated。它**不删除**last_use/增强/削弱等flags；非quantity的神器status即便也为“1”仍保留。两个当前控件有冲突显示时不会强行合并抹掉一方。
- 原生背景颜色对装备/诅咒/未鉴定的表达通常已由equipped、cursed、level、type_known、charges和enabled承载；不输出RGB是设计要求，不能按“没RGB”自动记缺陷。若以后找到某一原生背景表示的独立信息无法由同帧字段或文字恢复，应以具体原生分支证明，而不是要求复制所有颜色。
- 装备伤害/护甲与戒指效果的**未知时典型值**和真实隐藏等级不同；`WndInfoItem`及`WndUpgrade`的问号/未知提示要一起保留。此审计没有要求收集私有STRReq真实值来替代估计。
- 法杖内部curCharges只有原生知识门槛允许时才公开；partialCharge、诅咒法杖未来随机效果、隐藏树脂数量、私有精确投掷/魔晶耐久、神器内部exp/cooldown/charge不应越过原生status/info显示粒度。
- 始终保存原生说明中的不确定词和数字/零/false/null；不能靠“玩家可能推得出”将隐藏字段直接补成确定知识。镜像外观、匿名药卷戒与当前显示名称都遵从相同原则。

来源：[精确去冗余](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservation.java#L230)、[状态意义测试](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/ItemStatusMeaningTest.java#L17)、[冲突/独立actions测试](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservationTest.java#L103)、[背包绑定/容量测试](../../game-control/src/test/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiProjectionHintsTest.java#L115)。

## 6. 未升级为缺陷的候选验证与限制

1. **升级比较窗缺少专门结构化列身份。** 源码插入顺序保留了标题→当前→下一等级，所有文字和父关系有通用捕获路径；目前没有证据证明实际输出丢列或交换列，所以只列验证候选。最小native夹具：未知/已知/注能武器、树脂法杖、未知戒指各一例，冻结一次绘制，检查两列及警告/确定取消按钮在play/full/src的独立解码结果。
2. **神器子类status与各饰品动态statsDesc尚未逐个跑native窗口。** 已验证基类“数字不是数量”及通用文本链，不能据此声称每种神器的所有状态已实测。最小夹具：冷却数字、百分比、分数充能、无限上限数字、未鉴定/诅咒隐藏五分支；每个使用原生实际status生成再捕获，不能把内部charge硬拼成期望。
3. **实际打包版/真实屏幕尺寸下的裁切、包选择器、比较窗状态未在本子任务运行。** 本任务只做代码与隔离测试，未重启或操控用户游戏。根审计如果另有新隔离包证据，应独立标记；不从当前工作树推断正在运行的包已包含全部代码。
4. **局部裁切与未映射光效只列健壮性候选。** UI `GameplayIcons.displayed` 要求图标角点均在视口，部分可见物品图标可能不输出完整符号；仅凭剩余像素不能证明某一语义仍可可靠辨认，尚无确认信息损失。`GameplayGlow` 未知颜色返回 `unmapped_indicator` partial对象而不带variant，Python `_glow`要求variant；现有原生颜色库枚举未触发，故不与魔晶可达故障同等定性。来源：[图标显示门槛](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/GameplayIcons.java#L476)、[未知光效fallback](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/GameplayGlow.java#L59)。
5. **新增物品/附魔/刻印必须检查跨语言枚举。** 本次晶光错误表明仅增加Java native测试不足。所有reviewed kind/stage/symbol从producer到decoder的契约应有参数化清单测试，同时保留未知内容的partial诊断与现有隐私边界。

审计结果应与其他16系列章节一同使用；这是物品域的明确覆盖和缺口清单，不是对全部游戏信息的完整性背书。
