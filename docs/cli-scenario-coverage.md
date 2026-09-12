# CLI.0.9.0 场景待验收计划

整理日期：2026-09-12。此文档保留下一轮工作的范围与检查方法：35 个计划场景、11 个场景族、146 个成员。本文不携带旧通过状态，也不把旧版本结果自动转为新版通过。下一轮按实际代码改动和实战发现选择相关项验证；保留历史说明用于评估可复用证据，不要求仅因删除 JSON 就无差别重跑所有已通过项。

旧运行数据已清理。本计划不包含旧证据索引、profile 路径、构建标识或通过统计。实现归属是保留的模块分工标签，不表示相应工作已开始或完成；“待分配”需要主任务在执行前明确承接者。

## 执行与验收约束

- 所有真实引擎专项使用新建的隔离测试 profile，GUI 为简体中文（`CHI_SMPL`）、窗口化（`fullscreen=false`），公开 CLI 游戏文案为英语。原始请求、用户原始输入、标识和路径依协议处理，不通过翻译改写数据。
- 中间状态只能在测试 source set 中准备，并标记 `test_fixture=true`、`counts_as_win=false`。准备完成后，按当前公开 NDJSON 状态和实际原控件执行操作；不通过修改存档、隐藏字段或直接调用完成方法制造成功。
- 后置检查绑定产生结果的同一 `state_version`。内部数据可核对实际消耗、地形、选择与效果，但不能用来选择下一动作或目标；不得用后续查询弥补动作响应过早宣告完成。
- 纯查询允许写审计记录，不得初始化尚不存在的游戏根对象，或改变 RNG、回合、UI、模型和游戏数据；不得为了查询构造信息窗口。原 inspect 有其游戏内语义，应作为动作单独记录。
- 每个请求由调用方生成 ID；查询和动作全串行。成功、取消、无资源、非法或过期目标、重复 ID、运行中活动及其合法取消都按相应协议验证，避免错误、ID 或目标检查成为隐藏信息探针。
- 必须完成消耗后的强制选择，保留连接处理实际提示；不通过 EOF、自动保存或退出丢弃未解决选择。无论成功或失败，都核对真实的请求终态、保存回执与进程退出。
- 不访问个人数据或正式实战 profile。旧暂停战士数据已按用户要求移除，不能依赖其继续状态；今后的新实战与本清单的中间状态专项分别管理。
- 不使用截图、键鼠模拟或 Computer Use 驱动游戏。原 click、右键、长按等含义只能通过已有的公开 CLI 输入语义执行。外部链接只验证公开入口和请求边界，不执行未经授权的外部请求、支付或账号操作。
- 每个新结果只覆盖明确执行的具体分支。源码变化后重跑受影响项；复用历史验证需说明版本和适用范围，静态清单、基类继承、共用路由或一次施放不能自动证明其余分支。

## 计划场景（35 项）

### 01. `menu.bootstrap`

**目的：** 首启、版本更新、标题、开始与存档入口。

**实现归属：** 主任务（root）；分组 `menus`。

**准备：** 独立临时 profile 分别准备首次安装、相同版本和旧版本设置；不读取正式存档。

**公开步骤：**

1. 从原 Welcome 进入；在 Title 打开 Start；新建六职业之一并返回。
2. 继续已有 fixture 存档；打开删除确认后取消、再对一次性 fixture 确认删除。

**检查：**

- 按公开 ui.scene 分别记录 Welcome/Title/Start/HeroSelect/Interlevel。
- 没有旧局 hero/map 混入菜单；scope 变化正确；删除仅作用目标 fixture。

### 02. `menu.options`

**目的：** 选角种子、每日、挑战与随机化。

**实现归属：** 主任务（root）；分组 `menus`。

**准备：** 独立已解锁对应选项的测试 profile；另保留锁定 profile。

**公开步骤：**

1. 原 GameOptions 打开 seed 文本窗，输入/清除/取消/确认；打开随机化面板。
2. 原每日和挑战列表开启/关闭合法项；锁定项只能读取说明。

**检查：**

- 文本与按钮状态吻合；取消不改变设置；明确记录各 anonymous 确认分支。

### 03. `settings.display_audio_language`

**目的：** 设置所有 tab 与缩放、声音、语言。

**实现归属：** 主任务（root）；分组 `settings`。

**准备：** 所有可视测试固定 CHI_SMPL 且 fullscreen=false；其他设置取原 profile 合法值。

**公开步骤：**

1. 打开原 WndSettings，逐 tab 列举可见控件；对各开关/滑条改值再恢复。
2. 验证字体/工具栏/界面重建、音频选项、语言说明与贡献名单。

**检查：**

- 公开 checked/value 与原设置实际结果一致；旧 control 失效；无隐藏窗口覆盖下的可操作控件。
- 不得将 GUI 留在全屏或非简中；语言切换专项在同一边界恢复简中，公开 CLI 游戏文案仍为英语。

### 04. `settings.keybindings`

**目的：** 键位设置与冲突/清除/恢复默认。

**实现归属：** 主任务（root）；分组 `settings`。

**准备：** 测试 profile 原键位表，保留可恢复基线。

**公开步骤：**

1. 原 Settings -> WndKeyBindings -> WndChangeBinding；通过 CLI 已有原按键绑定输入语义完成合法更改。
2. 执行冲突/清除/取消/重置并恢复。

**检查：**

- 需要的输入均有公开合法入口；冲突提示/滚动/保存回读；如果缺少原键位录入语义，明确记录 blocker。

### 05. `menus.help_changes`

**目的：** 说明、关于、更新列表及弹层。

**实现归属：** 主任务（root）；分组 `menus`。

**准备：** 测试 profile 和本地版本更新内容。

**公开步骤：**

1. 原菜单到 AboutScene/ChangesScene；按版本与条目打开 WndChanges/WndChangesTabbed；切 tab、滚动、返回。

**检查：**

- 显示文字完整对应当前视口；外部链接只检查广告和请求语义，不擅自外发。

### 06. `menus.news_support`

**目的：** 新闻、文章、支持与出错状态。

**实现归属：** 主任务（root）；分组 `menus`。

**准备：** 使用独立测试网络响应或禁网场景；不购买、不外发账号请求。

**公开步骤：**

1. 原 NewsScene 进入文章；覆盖加载、空、失败和缓存内容；SupporterScene/SupportPrompt 只在测试许可下操作。

**检查：**

- 原 WndArticle/WndError 内容、滚动及 Back；外部副作用边界明确。

### 07. `knowledge.catalog`

**目的：** 主菜单 JournalScene 与局内 Journal/文档/图鉴。

**实现归属：** 主任务（root）；分组 `knowledge`。

**准备：** 测试 profile 设置少量已见与未见条目、钥匙与文档页；不同隐藏内容保持公开状态相同。

**公开步骤：**

1. 从主菜单与局内分别打开；切文档/图鉴/笔记分类、检查未知与已知条目、翻页、滚动、返回。
2. 打开 JournalItem、ItemJournalButton 相关弹窗及徽章。

**检查：**

- 已见键/数量/文字正确；未见物品真实类型不经 label/id/error 泄漏；每个真实窗口独立记录。

### 08. `knowledge.notes`

**目的：** 自定义笔记的新增、编辑、删除及种类/深度/物品选择。

**实现归属：** 主任务（root）；分组 `knowledge`。

**准备：** 独立带已探索楼层的测试局。

**公开步骤：**

1. 原 CustomNoteButton 打开类型/深度/物品选择；先使用英语测试内容完成主流程，另把中文多行自定义输入作为按原始文本契约验收的独立变体。
2. 编辑、取消、删除取消和确认；原重启继续后回读。

**检查：**

- 每个 CustomNoteWindow anonymous 回调都有独立结果；只保存用户内容、不自动揭示未到达信息。

**适用边界：** 当前 `Notes.Record` 关联 depth，branch 规则独立核对；`CustomType` 没有 cell 坐标笔记类型。不要新增游戏原本不存在的笔记类型来填充测试。

### 09. `knowledge.inspect`

**目的：** 地图、怪物、植物、陷阱、物品、Buff 和角色详情。

**实现归属：** 待分配（unassigned）；分组 `knowledge`。

**准备：** 分别准备可见/仅记忆/未知地图、隐藏拟态、未鉴定物品与短时 Buff。

**公开步骤：**

1. 仅通过原 examine/右键/物品菜单打开各 WndInfo*；切 hero stats/talents。
2. 再次查询并比较 RNG/turn/模型无变化；只有原 inspect 语义允许其既有副作用。

**检查：**

- 所有信息来源与原 UI 等价；血条使用实际绘制宽度；所有输出路径双世界对照。

### 10. `knowledge.rankings`

**目的：** 排名详情、计分及历史物品详情。

**实现归属：** 运行时与生命周期（runtime_architecture）；分组 `knowledge`。

**准备：** 人为构造已结算测试记录，无正式通关声明。

**公开步骤：**

1. 原 Rankings 点击记录；切 stats/items/badges/talents；打开挑战/种子/计分窗口并返回。

**检查：**

- WndRanking、ScoreBreakdown 及其嵌套确认各有证据；历史scope不能误作当前局。

### 11. `growth.choices`

**目的：** 六职业转职、护甲能力与天赋真实选择。

**实现归属：** 待分配（unassigned）；分组 `growth`。

**准备：** 背包置合法 TomeOfMastery/ArmorKit、足够天赋点；不直接赋值最终选择。

**公开步骤：**

1. 原 USE 打开 WndChooseSubclass/WndChooseAbility；各选项预览/取消/确认。
2. 原 WndInfoTalent 查看/分配；覆盖已满、点数不足、尚未解锁 tier。

**检查：**

- 所有 hero-specific 选项可达；取消无消耗；确认原升级/转职结果；回调后同响应已完成。

### 12. `growth.trinity`

**目的：** 三位一体灌注与 Body/Mind/Spirit。

**实现归属：** 待分配（unassigned）；分组 `growth`。

**准备：** 合法 class armor、资源和各类候选物品；另缺少资源状态。

**公开步骤：**

1. 通过原 Trinity 选择类型、候选与确认，再分别激活 body/mind/spirit。
2. 取消、重复已有效果、资源不足与目标选择取消。

**检查：**

- 不把已赋值 blazing fixture 算灌注流程；消耗与选择只在原确认后生效。

### 13. `growth.abilities`

**目的：** 职业、子职业、武器、武僧与牧师能力回调余项。

**实现归属：** 待分配（unassigned）；分组 `growth`。

**准备：** 复用当前测试源码中的合法角色和能力初态，为需要复核的分支建立对应版本的验收。

**公开步骤：**

1. 逐能力从实际控件打开；成功/取消/无资源/无合法目标/过期目标。
2. 测试 info/长按、目标模式、同伴与跨层相关特殊菜单。

**检查：**

- 按实际方法与分支建立 case mapping；不能用一次施放为全类背书。

### 14. `items.equipment`

**目的：** 装备替换、双持、强度警告与纹章转移。

**实现归属：** 待分配（unassigned）；分组 `items`。

**准备：** 六职业合法装备，背包和槽位边界、诅咒已知与未知两世界。

**公开步骤：**

1. 原装备/卸下与替换确认；投掷武器拆分/装备；ClassArmor 能力选择。
2. 原纹章拆卸、附着、刻印取舍；不手改护甲/纹章。

**检查：**

- 确认取消不消耗；角色专属槽位正常；未知诅咒/等级不提前泄漏。

### 15. `items.artifacts`

**目的：** 神器的模式和风险确认。

**实现归属：** 待分配（unassigned）；分组 `items`。

**准备：** 各实际神器合法充能、诅咒可知性与等级边界。

**公开步骤：**

1. TimekeepersHourglass 两种模式；ChaliceOfBlood 刺血风险确认；Toolkit 准备/炼金；Spellbook 选卷。
2. 分别取消、确认及缺资源入口。

**检查：**

- 原 Window 分支被实际打开；动作后资源/Buff/选择结果同版本断言。

### 16. `items.potions`

**目的：** 药剂通用与特有确认/选择。

**实现归属：** 待分配（unassigned）；分组 `items`。

**准备：** 已鉴定与未鉴定、已知危险/有益药剂；专用 Mastery/DivineInspiration/DragonsBreath。

**公开步骤：**

1. 原 DRINK/THROW 风险窗取消/确认；选择天赋 tier/装备/喷吐目标。

**检查：**

- 未知类型不通过可用动作或异常泄漏；消耗时刻一致；取消与目标变化按原流程。

### 17. `items.inventory_scrolls`

**目的：** 消耗后选物与强制放弃确认。

**实现归属：** 待分配（unassigned）；分组 `items`。

**准备：** 分别已知和未知原 InventoryScroll 与足够合法物品。

**公开步骤：**

1. READ -> 原选物；Back -> 放弃确认；分别 No 继续与 Yes 放弃。
2. 原 Upgrade preview 对武器/护甲/戒指等实际支持类别确认/取消。

**检查：**

- 保持消耗选择进程连接；不自动 save/quit 丢选择；精确记录不可逆放弃。

### 18. `items.exotic_scrolls`

**目的：** 附魔、嬗变/天赋替换与预知窗口。

**实现归属：** 待分配（unassigned）；分组 `items`。

**准备：** 合法 ExoticScroll 和多种可选装备/天赋。

**公开步骤：**

1. 原 enchant weapon/armor 选项及各取消确认；metamorph choose/replace；divination 可见结果窗。

**检查：**

- 每个嵌套 WndEnchantSelect/WndGlyphSelect/WndMetamorph* 独立；未选择的随机候选不提前公开。

### 19. `items.stones`

**目的：** 感知符石两次猜测与强化符石。

**实现归属：** 待分配（unassigned）；分组 `items`。

**准备：** 未知同色药/卷和符石；装备合法强化项。

**公开步骤：**

1. 原 WndGuess 选猜测仅改确认文字，再分别猜对/猜错与第二次消耗。
2. 原 WndAugment 各属性、清除、取消。

**检查：**

- 每次次数/消耗按原规则；不靠未知色名或私有映射选择正确答案。

### 20. `items.alchemize_trinket`

**目的：** 转化法术、饰物催化与警告。

**实现归属：** 待分配（unassigned）；分组 `alchemy`。

**准备：** 合法 Alchemize 和 TrinketCatalyst/能量，已知与未知物品。

**公开步骤：**

1. 原 Alchemize 选物与卖出/转能量/数量/饰物警告；原 catalyst 配方 -> WndTrinket -> RewardWindow。

**检查：**

- 一次性强制选择不可 Back 跳过；取消/资源不足/生成奖励都分别留证。

### 21. `items.staff_darts`

**目的：** 法杖灌注与飞镖涂毒/清洗。

**实现归属：** 待分配（unassigned）；分组 `items`。

**准备：** Mage staff、候选 wand、种子与原 dart/tipped dart。

**公开步骤：**

1. 原 MagesStaff imbue 确认取消/确认；Dart tip 选 seed，TippedDart clean 选数量。

**检查：**

- 目标选择、资源和旧装备变化与原 UI 一致；不能在 fixture 中直接完成灌注当成功。

### 22. `items.wands_beacons`

**目的：** 信标跨层、守卫交互与诅咒法杖特殊窗口。

**实现归属：** 待分配（unassigned）；分组 `items`。

**准备：** 分别准备合法 beacon tracker、visible ward 和 test-only 原诅咒随机分支入口。

**公开步骤：**

1. 原 WarpBeacon/BeaconOfReturning 传送、清除、取消与跨层限制。
2. 原 Ward 交互；CursedWand AbortRetryFail 原窗口各合法按钮。

**检查：**

- 隐藏层/随机效果不通过菜单 oracle 暴露；test-only 前置状态不得变成生产命令。

### 23. `alchemy.recipes_energy`

**目的：** 炼金配方、批量转能量与说明弹层。

**实现归属：** 待分配（unassigned）；分组 `alchemy`。

**准备：** 合法原炼金台、材料、饰物、能量及不足边界。

**公开步骤：**

1. 原 add/remove/craft；切配方页/说明窗；known/unknown 多类材料。
2. WndEnergizeItem 单件/多件/饰物警告；取消、确认、Back 回游戏。

**检查：**

- 未知材料和隐藏配方不外泄；费用/产出/材料守恒；返程 scope 稳定。

### 24. `input.radial_quickslots`

**目的：** 快捷栏/快捷包/炼金径向选择菜单。

**实现归属：** 待分配（unassigned）；分组 `input_layout`。

**准备：** 以原可选 UI 模式与支持控制方式准备；实际启用的按钮和输入路径。

**公开步骤：**

1. 原 Toolbar quickslot menu、bag -> item 径向级联；AlchemyScene 对应袋/物品径向菜单。
2. QuickBag、RadialMenu、滚动/长按/右键、选择取消与布局切换。

**检查：**

- 每个匿名径向回调独立；不是执行隐藏方法；退休 control 和底层覆盖节点不可操作。

### 25. `npc.quests`

**目的：** NPC 初次接任务、分支交付与 Rat King/Imp。

**实现归属：** 待分配（unassigned）；分组 `npc`。

**准备：** Ghost 各任务目标、Wandmaker 灰烬/尸尘/腐莓、Blacksmith 三种支线、Imp 任务、Rat King 原护甲交换前提。

**公开步骤：**

1. 原 NPC 交互 -> intro WndQuest；完整关闭触发原接任务回调。
2. 交付/领奖不同分支；缺道具提示；RatKing 交换取消/确认。

**检查：**

- intro 与领奖分别记录；任务指派/道具消耗由原回调产生。

### 26. `npc.shop_services`

**目的：** 商店与铁匠未测变体。

**实现归属：** 待分配（unassigned）；分组 `npc`。

**准备：** 金币不足、满包、原服务费用阶梯与合法/不合法装备组合。

**公开步骤：**

1. 原买卖/回购/闲聊与失败交易；服务已用次数后的费用、免费镐、不可重铸组合。

**检查：**

- disabled 正常展示且不可调；拒绝无游戏副作用；原交易/服务不重复执行。

### 27. `npc.companions`

**目的：** 同伴完整生命周期与装备限制。

**实现归属：** 待分配（unassigned）；分组 `npc`。

**准备：** 原 DriedRose 接受/完成任务前后、合法与超力量/诅咒装备、可受伤同伴。

**公开步骤：**

1. 原装备菜单、指挥、召唤、战斗死亡、自然充能完整周期、再召唤。

**检查：**

- 不把冷却尾段 fixture 计成完整死亡周期；可见死亡/充能/物品归属正确。

### 28. `transition.fall`

**目的：** 跳崖取消、确认与受伤着陆。

**实现归属：** 运行时与生命周期（runtime_architecture）；分组 `lifecycle`。

**准备：** 合法已见 chasm 和可进入下层；无正式存档。

**公开步骤：**

1. 原 cell.select 深渊 -> Chasm 确认取消；再次确认 -> FALL -> landing。

**检查：**

- WndOptions 真出现；取消不动；实际落层、受伤/状态、Actor 稳定响应。

### 29. `transition.branch_roundtrip`

**目的：** 矿区支线入口和出口往返。

**实现归属：** 运行时与生命周期（runtime_architecture）；分组 `lifecycle`。

**准备：** 原 CavesLevel/MiningLevel 合法 transition 与任务前提。

**公开步骤：**

1. 原入口确认；进入支线；原出口警告取消再确认返回。

**检查：**

- 楼层/branch/持久化/同局 scope；Caves 与 Mining 各 anonymous Window 留证。

### 30. `transition.other_warnings`

**目的：** 城市、最终 Boss 门、返回/重置与失败弹窗。

**实现归属：** 运行时与生命周期（runtime_architecture）；分组 `lifecycle`。

**准备：** 独立合法 CityLevel/HallsBossLevel 与可控缺层/恢复失败 fixture。

**公开步骤：**

1. 原 transition 触发各限制与警告；Interlevel 原失败/恢复错误。
2. 通过原回城/层重置机制进入 RETURN/RESET，不反射直接换场景。

**检查：**

- 确切窗口回调、save receipt、scope 与错误净化；不读取真实存档。

### 31. `ending.amulet_ascend`

**目的：** 拾护符、原返程与真正 Surface 结算。

**实现归属：** 运行时与生命周期（runtime_architecture）；分组 `lifecycle`。

**准备：** 在测试局准备合法护符附近和返程可走地图；保留 test_fixture=true。

**公开步骤：**

1. 原地面护符 pickup -> AmuletScene 留下；原 Ascension 上行直到 SewerLevel SURFACE。
2. 原 SurfaceScene -> 排名/胜利窗口；实际 win hook。

**检查：**

- 不直接构造 SurfaceScene 或调用 win；进入返程和最终结算各有原回调证据；counts_as_win=false。

### 32. `ending.permanent_death`

**目的：** 原伤害永久死亡和死亡排名。

**实现归属：** 运行时与生命周期（runtime_architecture）；分组 `lifecycle`。

**准备：** 1 HP + 原 Poison，无 Ankh 的独立测试局。

**公开步骤：**

1. 原 wait -> 死亡 -> lost hook；打开排名记录和各实际详情。

**检查：**

- 真正 Dungeon.fail；永久失效 scope；不得用 HP/深度推断结算。

### 33. `ending.restart_after_death`

**目的：** 死亡后原菜单新开一局。

**实现归属：** 运行时与生命周期（runtime_architecture）；分组 `lifecycle`。

**准备：** 沿用本专项死亡 fixture。

**公开步骤：**

1. 从原死亡/排名返回标题；原新游戏；选战士并进入。

**检查：**

- 新 run scope；旧请求/旧 control 拒绝；旧事件保持历史，不污染当前状态。

### 34. `lifecycle.resurrection`

**目的：** 复活余项、终端断开与保存恢复。

**实现归属：** 运行时与生命周期（runtime_architecture）；分组 `lifecycle`。

**准备：** 原 blessed/unblessed Ankh，保留物品不足/满包/复活待选存档。

**公开步骤：**

1. 原死亡确认缺项 Yes 分支；fresh JVM Continue 后完成待选；复活后找回失物。
2. 原显式保存/EOF/取消活动/异常退出和恢复场景分别跑协议专项。

**检查：**

- 强制选择不允许退出/自动确认；历史查询不重写 wire；审计允许写但纯查询不改变游戏。

### 35. `input.framework`

**目的：** 公共输入分发与通用窗口族。

**实现归属：** 待分配（unassigned）；分组 `input_layout`。

**准备：** 真实 concrete UI 树和测试节点树分别验证；基础类不独立构造冒充场景。

**公开步骤：**

1. 绑定当前公开 control，测试 click/right-click/long-click、文本、滑条、滚动、Back/取消；布局/scene 切换后试旧目标。

**检查：**

- 区分真实引擎 fixture 与隔离 UI 单测；每个实际输入方法和回调分支都不能因共用路由而自动视为通过。

## 场景族与成员（11 族、146 项）

每个成员都要覆盖下列适用于它的变体并建立单独断言。相同变体集合在族内用 V1、V2 等简写，成员表完整保留对应关系；这不是所有条件笛卡尔积已覆盖的声明。源码链接用于寻找机制，不是通过证据。三个抽象基类保留在清单中作范围说明，不独立构造它们冒充实际场景。

### `world.player_knowledge` — 可见性、探索记忆、隐藏地形与拟态

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：3。

**准备：** 建立不同私有世界但公开玩家知识相同的独立对照 fixture；不读正式档。

**公开步骤：**

1. 对同一可见状态发 state/actions/context/log/events/history/request.get 等公开查询；原 inspect 信息窗另按语义动作验证。
2. 通过原 search、observe、接近/远离、失明/心眼等可见操作推进知识。

**检查：**

- 所有对外路径含错误/目标/ID/时间等待不得作为隐藏 oracle。
- 纯查询前后 RNG/turn/静态根/模型无变化；只允许审计记录写入。
- Window 原 inspect 引起的知识变化单独标为语义动作，不冒充 state 纯查询。

**必需变体（required_variants）：**

- V1：FOV可见；visited记忆；mapped仅映射；未探索；秘密门/暗室未发现/搜索发现；失明/心眼/预感的真实UI表象。
- V2：休眠普通/金色/水晶拟态与对应容器双世界；Ebony Mimic伪装/发现/唤醒；不提前暴露敌人实际class/HP/掉落。
- V3：可见睡眠/警觉/友好/中立上下文；隐藏AI目标不同但外观相同；仅实际绘制血条像素/可见buff；场景/历史scope不混用。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `visibility_modes` | V1 | [PlayerObservation.java](../game-control/src/main/java/com/shatteredpixel/shatteredpixeldungeon/control/game/PlayerObservation.java)；文件级机制，不等于单一类声明 |
| `mimic_appearances` | V2 | [Mimic.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mimic.java)；文件级机制，不等于单一类声明 |
| `entity_public_state` | V3 | [Mob.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java)；文件级机制，不等于单一类声明 |

### `world.containers` — 所有堆/容器表象与开启拾取

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：8。

**准备：** 每个实际 Heap.Type，钥匙有/无、背包空/满、同堆多件；内容不同但未打开表象相同。

**公开步骤：**

1. 原 examine/context -> cell.select 开启；再次拾取；远处目标与脚下目标分别验证。
2. 允许原移动/操作动画/自动拾取完整结算；连续移动经过heap与把heap当最终目标分开。

**检查：**

- 未知内容不开箱不泄漏；开启后原露出的top item/数量正确。
- 开箱、拾取、移动的实际回合成本分别断言；无钥匙/满包不消耗错误资源。

**必需变体（required_variants）：**

- V1：未开启/开启；可见/记忆/隐藏；原上下文和实际资源变化。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `HEAP` | V1 | [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java) |
| `FOR_SALE` | V1 | [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java) |
| `CHEST` | V1 | [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java) |
| `LOCKED_CHEST` | V1 | [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java) |
| `CRYSTAL_CHEST` | V1 | [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java) |
| `TOMB` | V1 | [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java) |
| `SKELETON` | V1 | [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java) |
| `REMAINS` | V1 | [Heap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Heap.java) |

### `world.doors_keys` — 锁门、楼层关联钥匙与Journal数量

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：6。

**准备：** 用真实 Notes key 机制准备各钥匙、相邻门及错层钥匙；新增SkeletonKey特殊规则。

**公开步骤：**

1. 原 Journal 点击不同层/类型钥匙读实际详情。
2. 原 cell.select 无钥匙/错钥匙/正确钥匙门；取消路程，完成解锁再穿门。

**检查：**

- 不能由钥匙数量猜未见房间；钥匙类型和depth关联正确。
- 真正 onOperateComplete 消耗/改门；无钥匙不动世界；当前层与历史笔记隔离。

**必需变体（required_variants）：**

- V1：有正确钥匙；无钥匙/错层；原公开说明；消耗一次/重复ID不重复。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `LOCKED_DOOR:IronKey` | V1 | [Hero.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java) |
| `CRYSTAL_DOOR:CrystalKey` | V1 | [Hero.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java) |
| `LOCKED_EXIT:WornKey` | V1 | [Hero.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java) |
| `HERO_LKD_DR:SkeletonKey` | V1 | [Hero.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java) |
| `LOCKED_CHEST:GoldenKey` | V1 | [Hero.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java) |
| `CRYSTAL_CHEST:CrystalKey` | V1 | [Hero.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java) |

### `world.traps` — 陷阱发现、目标、触发和解除

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：34。

**准备：** 逐 concrete Trap 使用正常 visible/hidden 状态和合法触发者；单独隐藏双世界。

**公开步骤：**

1. 原 examine 已见陷阱，搜索发现隐藏陷阱；通过走入/投物等原触发路径。
2. 对原支持的拆除/回收机制走公开能力/物品选择；失败/失效陷阱再查。

**检查：**

- 隐藏陷阱不泄露类型/位置；已见外观/说明与原UI一致。
- 效果真正触发，持续/连锁与历史日志正确；不能用原cur/计时为策略决策。

**必需变体（required_variants）：**

- V1：hidden/visible/inactive；inspect/search；原触发；支持时原拆除。
- V2：原场景建立；可见/隐藏；实际触发/消除。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `AlarmTrap` | V1 | [AlarmTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/AlarmTrap.java) |
| `BlazingTrap` | V1 | [BlazingTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/BlazingTrap.java) |
| `BurningTrap` | V1 | [BurningTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/BurningTrap.java) |
| `ChillingTrap` | V1 | [ChillingTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/ChillingTrap.java) |
| `ConfusionTrap` | V1 | [ConfusionTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/ConfusionTrap.java) |
| `CorrosionTrap` | V1 | [CorrosionTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/CorrosionTrap.java) |
| `CursingTrap` | V1 | [CursingTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/CursingTrap.java) |
| `DisarmingTrap` | V1 | [DisarmingTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/DisarmingTrap.java) |
| `DisintegrationTrap` | V1 | [DisintegrationTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/DisintegrationTrap.java) |
| `DistortionTrap` | V1 | [DistortionTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/DistortionTrap.java) |
| `ExplosiveTrap` | V1 | [ExplosiveTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/ExplosiveTrap.java) |
| `FlashingTrap` | V1 | [FlashingTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/FlashingTrap.java) |
| `FlockTrap` | V1 | [FlockTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/FlockTrap.java) |
| `FrostTrap` | V1 | [FrostTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/FrostTrap.java) |
| `GatewayTrap` | V1 | [GatewayTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GatewayTrap.java) |
| `GeyserTrap` | V1 | [GeyserTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GeyserTrap.java) |
| `GnollRockfallTrap` | V1 | [GnollRockfallTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GnollRockfallTrap.java) |
| `GrimTrap` | V1 | [GrimTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GrimTrap.java) |
| `GrippingTrap` | V1 | [GrippingTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GrippingTrap.java) |
| `GuardianTrap` | V1 | [GuardianTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/GuardianTrap.java) |
| `OozeTrap` | V1 | [OozeTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/OozeTrap.java) |
| `PitfallTrap` | V1 | [PitfallTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/PitfallTrap.java) |
| `PoisonDartTrap` | V1 | [PoisonDartTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/PoisonDartTrap.java) |
| `RockfallTrap` | V1 | [RockfallTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/RockfallTrap.java) |
| `ShockingTrap` | V1 | [ShockingTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/ShockingTrap.java) |
| `StormTrap` | V1 | [StormTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/StormTrap.java) |
| `SummoningTrap` | V1 | [SummoningTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/SummoningTrap.java) |
| `TeleportationTrap` | V1 | [TeleportationTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/TeleportationTrap.java) |
| `TenguDartTrap` | V1 | [TenguDartTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/TenguDartTrap.java) |
| `ToxicTrap` | V1 | [ToxicTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/ToxicTrap.java) |
| `WarpingTrap` | V1 | [WarpingTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/WarpingTrap.java) |
| `WeakeningTrap` | V1 | [WeakeningTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/WeakeningTrap.java) |
| `WornDartTrap` | V1 | [WornDartTrap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/traps/WornDartTrap.java) |
| `ToxicGasRoom$ToxicVent` | V2 | [ToxicGasRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ToxicGasRoom.java) |

### `world.plants` — 种植、原地践踏、投种与角色植物机制

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：13。

**准备：** 每种原Plant与Seed，正常地面/heap优先/楼梯/水/火、束缚/飞行条件和Warden对照。

**公开步骤：**

1. 原PLANT或THROW后观察实际植物；原context踩当前格或移动踩邻格。
2. 敌人/火等原机制作用植物；不同职业触发。

**检查：**

- 未激活种子不虚报效果；当前格的拾取/等待/楼梯不冒充践踏。
- 恢复/清状态/传送效果按实际规则与时序；每步重新公开观察。

**必需变体（required_variants）：**

- V1：PLANT；THROW；当前格/邻格原践踏；角色差异；可见/隐藏。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `BlandfruitBush` | V1 | [BlandfruitBush.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/BlandfruitBush.java) |
| `Blindweed` | V1 | [Blindweed.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Blindweed.java) |
| `Earthroot` | V1 | [Earthroot.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Earthroot.java) |
| `Fadeleaf` | V1 | [Fadeleaf.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Fadeleaf.java) |
| `Firebloom` | V1 | [Firebloom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Firebloom.java) |
| `Icecap` | V1 | [Icecap.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Icecap.java) |
| `Mageroyal` | V1 | [Mageroyal.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Mageroyal.java) |
| `Rotberry` | V1 | [Rotberry.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Rotberry.java) |
| `Sorrowmoss` | V1 | [Sorrowmoss.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Sorrowmoss.java) |
| `Starflower` | V1 | [Starflower.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Starflower.java) |
| `Stormvine` | V1 | [Stormvine.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Stormvine.java) |
| `Sungrass` | V1 | [Sungrass.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Sungrass.java) |
| `Swiftthistle` | V1 | [Swiftthistle.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/plants/Swiftthistle.java) |

### `world.environment` — 火、气体、冰、电、网与特殊持续区域

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：26。

**准备：** 每种实际环境对象，visible与hidden/记忆/窗口遮挡对照；固定合法持续输入。

**公开步骤：**

1. 原动作进入/离开、利用水/原专用资源处理；随后纯查询重复读取。
2. 查看WndInfoCell/环境描述和原绘制effect/log；自然结束后再读。

**检查：**

- 只公开可见表象/原tileDesc，不导出Blob.cur浓度、私有持续时间或未见来源。
- 水灭火/净化等时序与伤害顺序不简化；空cue数组不等于安全。

**必需变体（required_variants）：**

- V1：可见/隐藏；当前/历史地图；原触发或进入；消失/尾迹；查询纯度。
- V2：原场景建立；可见/隐藏；实际触发/消除。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `Alchemy` | V1 | [Alchemy.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Alchemy.java) |
| `Blizzard` | V1 | [Blizzard.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Blizzard.java) |
| `Blob` | V1 | [Blob.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Blob.java) |
| `ConfusionGas` | V1 | [ConfusionGas.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/ConfusionGas.java) |
| `CorrosiveGas` | V1 | [CorrosiveGas.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/CorrosiveGas.java) |
| `Electricity` | V1 | [Electricity.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Electricity.java) |
| `Fire` | V1 | [Fire.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Fire.java) |
| `Foliage` | V1 | [Foliage.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Foliage.java) |
| `Freezing` | V1 | [Freezing.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Freezing.java) |
| `GooWarn` | V1 | [GooWarn.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/GooWarn.java) |
| `Inferno` | V1 | [Inferno.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Inferno.java) |
| `ParalyticGas` | V1 | [ParalyticGas.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/ParalyticGas.java) |
| `Regrowth` | V1 | [Regrowth.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Regrowth.java) |
| `SacrificialFire` | V1 | [SacrificialFire.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/SacrificialFire.java) |
| `SmokeScreen` | V1 | [SmokeScreen.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/SmokeScreen.java) |
| `StenchGas` | V1 | [StenchGas.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/StenchGas.java) |
| `StormCloud` | V1 | [StormCloud.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/StormCloud.java) |
| `ToxicGas` | V1 | [ToxicGas.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/ToxicGas.java) |
| `VaultFlameTraps` | V1 | [VaultFlameTraps.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/VaultFlameTraps.java) |
| `WaterOfAwareness` | V1 | [WaterOfAwareness.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfAwareness.java) |
| `WaterOfHealth` | V1 | [WaterOfHealth.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WaterOfHealth.java) |
| `Web` | V1 | [Web.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/Web.java) |
| `WellWater` | V1 | [WellWater.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/blobs/WellWater.java)；抽象基类，不独立构造测试；具体子类另列 |
| `MagicalFireRoom$EternalFire` | V2 | [MagicalFireRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/MagicalFireRoom.java) |
| `ToxicGasRoom$ToxicGasSeed` | V2 | [ToxicGasRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ToxicGasRoom.java) |
| `WeakFloorRoom$WellID` | V2 | [WeakFloorRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/WeakFloorRoom.java) |

### `world.rooms.special` — 特殊房间的原规则与公开观察

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：24。

**准备：** 使用原 room.paint /合法 level 生成构建一次性中间状态；保留房门与奖励/危险的原关系。

**公开步骤：**

1. 从可见入口按原cell/物品/技能处理房间机关；不要直接打开箱子、赋予最终奖励或调用完成。
2. 探索/发现、取消、资源不足、成功/失败分别保留。

**检查：**

- 房间未见/秘密未发现时无类型/位置泄漏；实际可见线索足以使用合法操作。
- 按具体房间机制记录所需输入，不把普通移动case算完整房间通过。

**必需变体（required_variants）：**

- V1：未发现/已发现；原房间入口和机关；危险/可选交互；真实奖励取得。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `ArmoryRoom` | V1 | [ArmoryRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ArmoryRoom.java) |
| `CryptRoom` | V1 | [CryptRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CryptRoom.java) |
| `CrystalChoiceRoom` | V1 | [CrystalChoiceRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalChoiceRoom.java) |
| `CrystalPathRoom` | V1 | [CrystalPathRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalPathRoom.java) |
| `CrystalVaultRoom` | V1 | [CrystalVaultRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/CrystalVaultRoom.java) |
| `DemonSpawnerRoom` | V1 | [DemonSpawnerRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/DemonSpawnerRoom.java) |
| `GardenRoom` | V1 | [GardenRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/GardenRoom.java) |
| `LaboratoryRoom` | V1 | [LaboratoryRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/LaboratoryRoom.java) |
| `LibraryRoom` | V1 | [LibraryRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/LibraryRoom.java) |
| `MagicWellRoom` | V1 | [MagicWellRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/MagicWellRoom.java) |
| `MagicalFireRoom` | V1 | [MagicalFireRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/MagicalFireRoom.java) |
| `PitRoom` | V1 | [PitRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PitRoom.java) |
| `PoolRoom` | V1 | [PoolRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/PoolRoom.java) |
| `RunestoneRoom` | V1 | [RunestoneRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/RunestoneRoom.java) |
| `SacrificeRoom` | V1 | [SacrificeRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SacrificeRoom.java) |
| `SentryRoom` | V1 | [SentryRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SentryRoom.java) |
| `ShopRoom` | V1 | [ShopRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ShopRoom.java) |
| `SpecialRoom` | V1 | [SpecialRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/SpecialRoom.java)；抽象基类，不独立构造测试；具体子类另列 |
| `StatueRoom` | V1 | [StatueRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/StatueRoom.java) |
| `StorageRoom` | V1 | [StorageRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/StorageRoom.java) |
| `ToxicGasRoom` | V1 | [ToxicGasRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/ToxicGasRoom.java) |
| `TrapsRoom` | V1 | [TrapsRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/TrapsRoom.java) |
| `TreasuryRoom` | V1 | [TreasuryRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/TreasuryRoom.java) |
| `WeakFloorRoom` | V1 | [WeakFloorRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/special/WeakFloorRoom.java) |

### `world.rooms.secret` — 隐藏房间的原规则与公开观察

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：14。

**准备：** 使用原 room.paint /合法 level 生成构建一次性中间状态；保留房门与奖励/危险的原关系。

**公开步骤：**

1. 从可见入口按原cell/物品/技能处理房间机关；不要直接打开箱子、赋予最终奖励或调用完成。
2. 探索/发现、取消、资源不足、成功/失败分别保留。

**检查：**

- 房间未见/秘密未发现时无类型/位置泄漏；实际可见线索足以使用合法操作。
- 按具体房间机制记录所需输入，不把普通移动case算完整房间通过。

**必需变体（required_variants）：**

- V1：未发现/已发现；原房间入口和机关；危险/可选交互；真实奖励取得。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `RatKingRoom` | V1 | [RatKingRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/RatKingRoom.java) |
| `SecretArtilleryRoom` | V1 | [SecretArtilleryRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretArtilleryRoom.java) |
| `SecretChestChasmRoom` | V1 | [SecretChestChasmRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretChestChasmRoom.java) |
| `SecretGardenRoom` | V1 | [SecretGardenRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretGardenRoom.java) |
| `SecretHoardRoom` | V1 | [SecretHoardRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretHoardRoom.java) |
| `SecretHoneypotRoom` | V1 | [SecretHoneypotRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretHoneypotRoom.java) |
| `SecretLaboratoryRoom` | V1 | [SecretLaboratoryRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLaboratoryRoom.java) |
| `SecretLarderRoom` | V1 | [SecretLarderRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLarderRoom.java) |
| `SecretLibraryRoom` | V1 | [SecretLibraryRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretLibraryRoom.java) |
| `SecretMazeRoom` | V1 | [SecretMazeRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretMazeRoom.java) |
| `SecretRoom` | V1 | [SecretRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretRoom.java)；抽象基类，不独立构造测试；具体子类另列 |
| `SecretRunestoneRoom` | V1 | [SecretRunestoneRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretRunestoneRoom.java) |
| `SecretSummoningRoom` | V1 | [SecretSummoningRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretSummoningRoom.java) |
| `SecretWellRoom` | V1 | [SecretWellRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/secret/SecretWellRoom.java) |

### `world.rooms.quest` — 任务房间的原规则与公开观察

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：10。

**准备：** 使用原 room.paint /合法 level 生成构建一次性中间状态；保留房门与奖励/危险的原关系。

**公开步骤：**

1. 从可见入口按原cell/物品/技能处理房间机关；不要直接打开箱子、赋予最终奖励或调用完成。
2. 探索/发现、取消、资源不足、成功/失败分别保留。

**检查：**

- 房间未见/秘密未发现时无类型/位置泄漏；实际可见线索足以使用合法操作。
- 按具体房间机制记录所需输入，不把普通移动case算完整房间通过。

**必需变体（required_variants）：**

- V1：未发现/已发现；原房间入口和机关；危险/可选交互；真实奖励取得。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `AmbitiousImpRoom` | V1 | [AmbitiousImpRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/AmbitiousImpRoom.java) |
| `BlacksmithRoom` | V1 | [BlacksmithRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/BlacksmithRoom.java) |
| `MassGraveRoom` | V1 | [MassGraveRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MassGraveRoom.java) |
| `MineEntrance` | V1 | [MineEntrance.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MineEntrance.java) |
| `MineGiantRoom` | V1 | [MineGiantRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MineGiantRoom.java) |
| `MineLargeRoom` | V1 | [MineLargeRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MineLargeRoom.java) |
| `MineSecretRoom` | V1 | [MineSecretRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MineSecretRoom.java) |
| `MineSmallRoom` | V1 | [MineSmallRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/MineSmallRoom.java) |
| `RitualSiteRoom` | V1 | [RitualSiteRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/RitualSiteRoom.java) |
| `RotGardenRoom` | V1 | [RotGardenRoom.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/rooms/quest/RotGardenRoom.java) |

### `items.knowledge_and_selection` — 未知消耗品、装备鉴定与消耗后的选择

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：3。

**准备：** 使用同外观的隐藏类型双世界、未知装备等级/诅咒/附魔，各种数量和可鉴定条件。

**公开步骤：**

1. 原name/detail/actions读取；通过真正READ/DRINK/EQUIP/IDENTIFY逐步揭示。
2. 每个awaiting_input保留连接，原物品/目标/确认/放弃完整完成；过期ID和失效目标分别验证。

**检查：**

- 公开名称/描述/flags/actions/错误/历史均不得泄漏未获得知识。
- 消耗前后与取消/放弃分支精确；等待选择不能被save/quit隐式丢弃。
- 静态物品类型需要按类别和特殊回调建立具体案例；能力矩阵不能替代全部物品。

**必需变体（required_variants）：**

- V1：首次外观未知；同色已知；全局图鉴与本件level独立；诅咒/附魔发现条件。
- V2：未鉴定强度/数值；诅咒已知/未知；附魔/刻印显隐；升级预览实际合法数值。
- V3：未知卷轴READ后选择；mandatory确认取消/继续/放弃；原选择完成前禁止退出；历史结果不冒充当前。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `potion_scroll_ring_identification` | V1 | [Potion.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/potions/Potion.java)；文件级机制，不等于单一类声明 |
| `equipment_levels_and_curses` | V2 | [Item.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java)；文件级机制，不等于单一类声明 |
| `consumed_selector` | V3 | [InventoryScroll.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/scrolls/InventoryScroll.java) |

### `world.boss_mechanics` — Boss公开预兆与完整分阶段操作

**实现归属：** 玩家知识边界与世界交互（visibility_boundary）。成员数：5。

**准备：** 独立合法各Boss中间状态；每种攻击/转阶段/附属物原AI触发，不手调完成或击杀结果。

**公开步骤：**

1. 只依实际绘制cue、日志、条形血量/原说明决策；逐步原移动/攻击/道具。
2. 阶段转换、hazard消失、可见性/遮挡对照和结算分开记录。

**检查：**

- 可见cue专项不等于打败Boss；不导出私有AI计时、下一目标/真实数值。
- 每个Boss的特殊合法输入及原阶段自然回调单独验证。

**必需变体（required_variants）：**

- V1：主要攻击/阶段；原可见预兆；合法反制；原结束/结算。

| 成员 | 必需变体 | 源码与适用范围 |
|---|---|---|
| `Goo` | V1 | [Goo.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java) |
| `Tengu` | V1 | [Tengu.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java) |
| `DM300` | V1 | [DM300.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java) |
| `DwarfKing` | V1 | [DwarfKing.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java) |
| `YogDzewa` | V1 | [YogDzewa.java](../core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java) |

## 必须保持的覆盖边界

1. 预先准备角色、子职业、技能或装备后的操作，只验证该操作分支；真实获得天赋、转职和选择护甲能力需要通过原成长窗口另测。
2. NPC 领奖不能替代首次对话和接任务。幽灵、法杖工匠、铁匠等的 intro、任务分支、缺道具提示与奖励选择分别验收。
3. 全布局下 `GameScene.selectItem` 可能使用 `InventoryPane`；只有实际打开 `WndBag` 的布局才覆盖该窗口。基类或组件被间接使用不能算另一具体窗口完成。
4. Trinity 的能力激活不能替代灌注类型选择、物品选择和确认；已有灌注初态必须与原灌注流程区分。
5. 护符界面的直接结束不能替代 `SurfaceScene`；独立返程起点与独立地表结算不能拼接为一局完整上行。完整返程要求同一测试局经原楼梯、Ascension 与 SURFACE 路径，仍不计正式实战胜利。
6. Boss 可见预兆与实际绘制测试不能替代击败 Boss，也不能代表全部攻击或阶段。血条、日志、浮字和 cue 必须取已显示信息，不能读取私有 AI 倒计时或下一目标作决策。
7. 楼层故事、支线、恢复错误与匿名确认回调需要精确入口的验收依据；旧版本的语言或窗口设置不能被追认到当前包，其他未选分支也不能借用结果。
8. scene/window 清单只覆盖 UI 维度；容器、门钥匙、陷阱、植物、环境、特殊/秘密/任务房间、未知物品知识与 Boss 机制由上述场景族分别验证。

## 实施时如何维护本计划

- 从一个可重复的小组开始，使用当前源码确认入口和合法初态，再通过公开 CLI 完成原流程。保留测试源码和可重跑步骤；成功与失败都只报告实际观察，不将环境或脚本问题隐藏成通过。
- 当前文档保留 35 个工作项与全部 146 个成员；抽象基类依据来自当前源码。代码增删类型后，以位于 `game-control/src/test/resources/cli-ui-coverage.json` 的静态测试基线及其生成器核对新增入口，再更新相应计划条目。静态基线不是运行结果。
- 后续验收结论另以当前版本的简洁文档维护，不重新向本计划灌入已删除的历史 evidence registry、运行路径或构建标识。
