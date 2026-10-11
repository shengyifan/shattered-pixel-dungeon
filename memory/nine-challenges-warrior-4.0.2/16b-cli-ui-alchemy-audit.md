# CLI 用户信息审计：炼金、日志图鉴、任务、商店与原生窗口

审查日期：2026-10-11。源码基线 `d04c8f862b2f90418a6375f0b3ffc5c4325d0953`，游戏 4.0.2/code 922，CLI.9.0.3/protocol 9。范围是**用户已经打开的原生界面中，现有可读信息能否进入 spdctl 公开观察**。没有启动个人游戏、读取个人存档或审计、执行购买/合成/选择奖励，也没有修改产品代码。

结论：原生文本、配方能量数、价格、材料/结果名称和数量、禁用控件的操作约束有既有传输路径；但**部分图标入口的身份/选中表示，以及配方材料不足的原生提示，存在已复现的静默遗漏**。未打开物品说明或任务窗口时不预先公开其内容，是按需观察边界，不列作缺陷。

## 1. 真实的数据链与证据层级

本基线没有名为 `UiSemanticCollector` 的类；实际入口为 `UiBridge`：

1. 原生控件产生 `RenderedTextBlock` / `BitmapText`，或通过 `GameplayStatus → RenderedStatus` 给出已绘制的语义。`UiBridge.captureDrawnEvidence()` 捕获既有图形对象的显示证据；不创建 UI、不执行回调来探查信息。[UiBridge.java:116](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L116)
2. `UiBridge.walk()` 建立控件树、文本/label、enabled、当前可执行操作。角色识别只覆盖 Button、TextInput、Slider、ScrollPane、Window、RenderedStatus、文本及可点击列表 entry；**普通 Image 本身没有 role**。[UiBridge.java:877](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L877)、[role:991](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L991)
3. `GameController.capture()` 调用 frozenUi，再由 `GameplayObservation.merge()` 关联同帧 subject、合并已知文本、保存所有可执行约束。虚拟配方/奖励物品不是当前背包中的真实实例时不硬绑定库存 subject，它们的现有 label/shown/操作仍内联。[GameController.java:703](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameController.java#L703)、[GameplayObservation.java:34](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameplayObservation.java#L34)、[UiBridge.java:369](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L369)
4. `PublicEnglishProjection` 从原始文本来源渲染英文，`CompactProtocol.project()` 和 `CompactStructures` 压缩 nodes/acts、保存顺序和父节点。`src` 增加已捕获文本的来源 AST，**不会重新捕获前端已经漏掉的图标或状态**。[PublicEnglishProjection.java:18](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PublicEnglishProjection.java#L18)、[CompactProtocol.java:51](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/CompactProtocol.java#L51)、[CompactStructures.java:38](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/CompactStructures.java#L38)

证据分三层：源码映射；已构造原生对象的隔离捕获/压缩 fixture；真实启动游戏后的端到端 GUI/CLI 对照。本次做到前两层，不能声称第三层已覆盖所有窗口。

## 2. 已确认缺口

### UI-1：炼金手册页面、图鉴子类别的纯图标按钮失去身份

`WndJournal.AlchemyTab` 的九个页面入口是 `new RedButton("")`，图标分别为种子、符石、食物、药水、卷轴、炸弹、投掷物、合剂、法术；尚未发现的页改为 SOMETHING 并禁用。当前页通过 `icon().color(TITLE_COLOR)` 高亮。`CatalogTab` 的装备、消耗品、怪物、文献四入口也是同一种空文字 RedButton。

- 原生来源：[WndJournal.java:285–386 AlchemyTab](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndJournal.java#L285)、[CatalogTab:582–642](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndJournal.java#L582)。
- 漏失位置：`StyledButton extends Button`，不实现 GameplayStatus/RenderedStatus；其 `icon` 只是 Image 子对象。`UiBridge.role()` 不输出普通 Image，也没有针对 StyledButton 的图标适配。[StyledButton.java:33](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/StyledButton.java#L33)、[UiBridge.java:146](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L146)、[UiBridge.java:991](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/UiBridge.java#L991)。
- **复现**：创建原生匿名类 `WndJournal$AlchemyTab$1`，附加原生来源的 seed_holder 图像；捕获后把同一图像换为 potion_holder，再更改为原生 TITLE_COLOR。图标辅助器能分别识别 seed/potion，但 `UiBridge` 输出、play 和 full+src 输出全部不变。只有 `id/role/enabled/gestures`，既无 label/icon/selected，也无缺失诊断。
- 影响：AI 看得到多个可点击入口，却无法通过这帧判断哪一个是药水页或法术页。当前打开页的正文标题仍可说明当前内容，所以不能夸大为“完全不知道正在读哪页”；缺的是入口身份及入口自身的选中语义。
- 已有 `WndTabbed.IconTab` 修复不能覆盖此处。它实现 GameplayStatus 并显式输出 selected/source icon，而这些子分类按钮不是该类型。[WndTabbed.java:165](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndTabbed.java#L165)、[IconTab:251](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndTabbed.java#L251)。

### UI-2：日志/图鉴网格项失去其原生图标身份

`NotesTab` 把地标、钥匙、自定义记录变成 `ScrollingGridPane.GridItem(rec.icon())`；物品图鉴、怪物/陷阱/植物图鉴及文献也使用 GridItem。用户在网格上先看到图标，再点开标题和说明。

- 原生来源：[WndJournal.java:497–553 Notes](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndJournal.java#L497)、[物品网格:746–874](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndJournal.java#L746)、[实体网格:877](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndJournal.java#L877)、[ScrollingGridPane.java:177](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ScrollingGridPane.java#L177)。
- 漏失位置：GridItem 是 Component，不提供语义；其 icon 以及 Image 型 secondIcon 都是普通 Image。UiBridge 只把外层识别成 `role:"entry"` 并提供 select。
- **复现**：同一原生 GridItem 的图标从 GOLD 换为 ENERGY，公开捕获完全一致：`{"id":"c3","role":"entry","enabled":true,"parent":"c2"}`。select 仍存在，但没有图标/名称/诊断。
- 影响：日志当前层的地标、钥匙等先成为匿名 entry，只能逐项打开确认；这与 GUI 可直接识别图标不等价。已打开 `WndJournalItem` 的标题/说明走文本路径，**不是连详情也不可读**。
- 不可过度泛化 secondIcon：钥匙数量是 `BitmapText`，按普通文本节点保留；丢失的是 Image 类型的徽标/图标，不是所有右下角数值。[Notes.java:312–324](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/journal/Notes.java#L312)。

UI-1 与 UI-2 可在根报告按“缺少原生 Image 拥有者语义适配”归并；它们在不同的原生控件家族上各有复现，不应再把每个页面、每种图鉴计作独立缺陷。

### UI-3：QuickRecipe 的逐材料“数量不足”状态丢失

`QuickRecipe` 遍历每种材料，以原生 `getAllSimilar()`、身份已知限制和 quantity 比较所需数量；不足时将**该材料 sprite** 的 alpha 设为 0.3，输出图标也变暗。材料 ItemSlot 仍可点击查看详情。箭头在任何材料不足时禁用；仅箭头禁用无法说明究竟缺哪个材料。

- 原生来源：[QuickRecipe.java:119–168](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/QuickRecipe.java#L119)。
- 漏失位置：`ItemSlot.gameplayStatus()` 读取状态文本、强度、等级、徽标、glow 和已支持的效果；不保留 QuickRecipe 已选定的“此材料不足”分支。`ItemSprite.gameplayItemStatus()` 仅处理 ItemStatusEmitter，没有这种规则。一般 alpha 只用于“是否可见”门槛，不能自动变成足够/不足语义。[ItemSlot.java:53](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ItemSlot.java#L53)、[ItemSprite.java:275](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ItemSprite.java#L275)。
- **复现**：持有 Ration of Food 的现有 ItemSlot，sprite alpha 从 1.0 变成 0.3，`UiBridge` 输出完全相同，没有 shortage/insufficient/dimmed 或 partial 信息。此 fixture 只复现原生源码已确定的显示分支，不运行配方搜索，也不读取背包。
- 影响：用户直接看到哪一种材料不足；CLI 只能看到材料名/所需数量和配方整体是否可操作。当前炼金场景本来没有默认 hero/inventory 表可供客户端补算，即使有也不应靠隐藏身份补算原生 UI 的结论。[GameSnapshotter.java:45](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/GameSnapshotter.java#L45)、[PlayerObservation.java:85](../../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java#L85)。
- 仍被保留的边界：箭头禁用时没有填入操作，缺材料并不会因此被 CLI 错误授权合成。修复应只记录原生已选显示语义，不输出 RGB/alpha，不在观察时重新遍历库存推断。

## 3. 覆盖矩阵：保留、按需、候选与未验证

| 原生用户信息 | 原生来源 | 公开观察途径 | 当前判断/实战用法 |
|---|---|---|---|
| 炼金总能量、工具箱额外能量 | AlchemyScene 429–440、878–888，文本形如 Energy N+M | 原生 RenderedTextBlock → TextProvenance → 英文 text | 源码路径成立；不应把缺少 hero.energy 表误报为该场景没有总能量。工具箱图像本身为独立 Image，不在通用 UI 图标输出内；文本的 N+M 仍保留 |
| 当前配方费用、是否够能量 | AlchemyScene 645–665、CombineButton 1138–1154 | costText 保留；button enabled 与当前 ops | 路径成立。费用为 0 时原生本来就隐藏 costText；缺失费用文本不等于传输丢零 |
| 当前材料与产物名称、数量、升级/充能显示 | AlchemyScene InputButton 974、OutputSlot 1163；ItemSlot.updateText 312 | ItemSlot hover label + shown/status/level；虚拟物品不强行建立库存 locator | 路径成立；原生点击 output 或长按 input 打开 WndInfoItem，完整性质按需取得 |
| 多种可合成结果与每个 Craft 的对应 | AlchemyScene 385–403 成对添加 CombineButton/OutputSlot，645–665 设置同排 | 节点顺序保留，但两个容器自身无 role，结果 label 与 Craft/费用成为邻接节点 | **候选，未列确认缺陷**：需整场景多结果 fixture 检查语义分组、顺序和来源是否足以无歧义选择，不能仅因没有结构化 recipe 字段判失败 |
| 手册配方材料、结果和所需数量 | QuickRecipe 109–171 的 ItemSlot 与箭头 | 材料 hover 名、显示数量；箭头有 icon，费用 BitmapText；点击材料/产物可打开说明 | 一般名称/数量/成本通路成立；逐材料不足提示见 UI-3；匿名页面入口见 UI-1 |
| 手册页正文和配方说明 | WndJournal 407–418 | IconTitle 的文字 + body RenderedTextBlock | 按当前已打开、可见片段读取。滚动视口外不一次泄露；scroll/selected当前可用操作另行取 |
| 商店购买价、出售单件/全部价 | WndTradeItem 92–122、146–160 | 带数字的 RedButton text；Buy 按当前 gold 禁用 | 源码路径成立，隔离正对照数字价格文本 survives play。购买 icon 金币未单独保留，但价格按钮文本已说明用途；不能据此声称价格丢失 |
| 偷窃成功率与臂章消耗 | WndTradeItem 164–170 | btnSteal 文本包含公开计算结果，后续警告 WndOptions | 按需打开出售物品窗口；数字随当前原生状态生成，不通过私有字段另算 |
| 鬼魂/法杖匠的任务说明与奖励候选 | WndSadGhost 54–102；WndWandmaker 57–103 | 标题/任务正文 + ItemButton 内 ItemSlot label/shown；打开 RewardWindow 后 info 与确认按钮 | 源码路径成立。初选图标是“查看奖励”，并不是立即接受；不能把奖励信息只在后续窗口出现算默认遗漏 |
| 铁匠声望、服务价格、服务禁用 | WndBlacksmith 72–169 | prompt(favor)、各带 cost 的 RedButton、enabled/ops | 源码路径成立。重铸材料限制在选择器原生界面反映，不能把游戏内部 itemSelectable 规则提前作为新操作广告 |
| 普通任务对话/需求 | WndQuest → WndTitledMessage | 原生 message RenderedTextBlock | 按需交互读取；没有统一 quest 对象并不等于需求不可读 |
| 日志地标/钥匙/自定义记录入口 | WndJournal NotesTab 497–553 | headers + entry/select；数值 secondIcon 为 text 时保留 | 图标身份缺失已确认 UI-2；点开的 WndJournalItem 内容仍可读 |
| 图鉴物品/敌人/陷阱/植物/文献详情 | WndJournal 746–1066 → WndJournalItem/WndStory | 名称/描述/发现提示/统计文本，经当前窗口输出 | 详情按需，源路径成立；入口图标或是否已发现的视觉差异属于 UI-2 的同根因扩展，未逐项跑实际场景 |
| 主标签页 selected 和 source icon | WndTabbed.Tab/IconTab 165–315 | GameplayStatus → UiBridge → compact node fields | 已有专门适配；不要拿它证明 RedButton 子类别也有同样覆盖 |
| 未鉴定物品说明边界 | QuickRecipe.anonymize 207–214、WndInfoItem 92–118、PlayerObservation 205–237 | 原生 info 已决定可读程度；inspected_item.level_known 可附带说明知识 | 可见的“未知/典型/已知”区别应保留，不能把配方图示匿名化反向用来读取本局未鉴定映射 |
| src AST 与协议节点模板 | PublicEnglishProjection / CompactProtocol / CompactStructures | 来源 AST、node_templates、ops 引用按每帧展开 | 编码只能保留已采集内容；UI-1 已实测 full+src 无法补回。客户端必须读 resolved ops，不能只找 node.text |

相关源码入口：

- [AlchemyScene.java:429 能量](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/AlchemyScene.java#L429)、[645 多结果费用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/AlchemyScene.java#L645)、[974 输入](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/AlchemyScene.java#L974)、[1138 费用/禁用](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/AlchemyScene.java#L1138)、[1163 产物](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/AlchemyScene.java#L1163)。
- [QuickRecipe.java:109](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/QuickRecipe.java#L109)、[ItemSlot.java:312](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ItemSlot.java#L312)、[ItemButton.java:43](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ui/ItemButton.java#L43)。
- [WndTradeItem.java:92](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndTradeItem.java#L92)、[WndSadGhost.java:54](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndSadGhost.java#L54)、[WndWandmaker.java:57](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndWandmaker.java#L57)、[WndBlacksmith.java:72](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndBlacksmith.java#L72)。
- [WndQuest.java:29](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndQuest.java#L29)、[WndTitledMessage.java:45](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndTitledMessage.java#L45)、[WndJournalItem.java:31](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndJournalItem.java#L31)、[WndInfoItem.java:92](../../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndInfoItem.java#L92)。

## 4. 可复现材料与具体测试范围

- [UiAlchemyAuditProbe.java](evidence/cli-ui/UiAlchemyAuditProbe.java)：原生 `WndJournal$AlchemyTab$1`、GridItem、ItemSlot、IconButton 及 UiBridge，采用现有测试的资源-only runtime；无 GL draw、游戏启动、用户 preference/profile 文件访问。用 Unsafe 只分配隔离控件并设定原生源码对应的显示状态，避免创建真实场景和执行回调。
- [run_probe.py](evidence/cli-ui/run_probe.py)：使用已有构建 classpath，只编译这个 fixture 到忽略目录 `build/research-cli-ui-fixtures/`。
- [result.txt](evidence/cli-ui/result.txt)：保存源码 HEAD、相关源码/JAR SHA-256、原始节点输出及 **13 条通过断言**。

运行命令：

```sh
python3 memory/nine-challenges-warrior-4.0.2/evidence/cli-ui/run_probe.py
```

正对照包括：相同显示素材由真实 `IconButton` 承载时可以识别 icon；原生页面按钮 active 时确有 activate，禁用后该操作消失；带价格的可见英文 BitmapText 在 play 中保留。这排除了“fixture 的所有图标都不在视口”“只是没有声明操作”“所有文本都失败”的解释。

测试不声称真实配方窗口、奖励窗口或商店已经完整走过一遍。确认遗漏发生于原生对象→UiBridge 的采集阶段，full/src/客户端展开都不可能凭空恢复；未确认的多结果配方关系保留为后续集成测试候选。

## 5. 修复与实战建议边界

本轮仅审查，没有实现修复。若后续授权维护，优先在**原生拥有者已经选择图标/状态的地方**补语义：StyledButton 子分类的 icon/selected、GridItem 的主/次图标及公开发现状态、QuickRecipe 的逐材料不足标记。不要在观察器中按按钮序号猜“第几个是药水”，不要把 atlas/alpha/RGB 放进协议，也不要重跑配方匹配或读取隐藏物品身份去计算缺什么。

修复应补真实窗口的多页面、多结果配方、未知物品、滚动裁剪和奖励预览测试，验证默认/full/src、模板展开及当前 ops。已有的原生 IconTab 回归测试只覆盖该类，不能作为本页控件的替代证据。

未修复前，实战可以通过当前已广告的入口逐次打开窗口、读取新页标题或物品说明来确认，但必须承认它是额外检查成本；不使用旧坐标、静态按钮序号或隐藏字段来填补匿名入口。不为降低 tokens 丢弃成本、文本less操作、disabled状态或当前节点顺序。
